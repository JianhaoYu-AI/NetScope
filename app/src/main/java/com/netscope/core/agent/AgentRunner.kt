package com.netscope.core.agent

import com.netscope.core.model.Claim
import com.netscope.core.model.Evidence
import com.netscope.core.model.ProbeStatus
import com.netscope.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

data class AgentToolCall(val id: String, val name: String, val argumentsJson: String)
data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val requireToolCall: Boolean = false,
)

sealed interface ModelTurn {
    val traceId: String?
    data class Tools(val calls: List<AgentToolCall>, override val traceId: String?) : ModelTurn
    data class Final(val claims: List<ProposedClaim>, override val traceId: String?) : ModelTurn
}

interface ModelClient {
    suspend fun complete(messages: List<AgentMessage>, timeoutMs: Long): ModelTurn
}

class ModelUnavailableException(message: String) : Exception(message)
class ModelProtocolException(message: String) : Exception(message)

enum class AgentOutcome { COMPLETED, MODEL_UNAVAILABLE, INCONCLUSIVE }

data class AgentToolStep(
    val name: String,
    val evidenceId: String? = null,
    val status: ProbeStatus? = null,
    val rejectedReason: String? = null,
)

data class AgentReport(
    val outcome: AgentOutcome,
    val claims: List<Claim>,
    val evidences: List<Evidence>,
    val attemptedToolCalls: Int,
    val rejectedClaims: Int,
    val structuralRejectionRate: Double?,
    val semanticReviewPending: Boolean,
    val traceIds: List<String>,
    val reason: String? = null,
    val toolSteps: List<AgentToolStep> = emptyList(),
    val durationMs: Long? = null,
    /** Review-only proposals, including rejected claims; never shown as accepted conclusions. */
    val proposedClaims: List<ProposedClaim> = emptyList(),
)

/** A pure session loop: model-selected tools still pass through the fixed ToolInvoker boundary. */
class AgentRunner(private val clock: Clock, private val auditor: HallucinationAuditor) {
    suspend fun run(
        initial: List<Evidence>,
        complete: suspend (List<AgentMessage>, Long) -> ModelTurn,
        invoke: suspend (String, String, BudgetGuard) -> ToolOutcome,
    ): AgentReport {
        require(initial.isNotEmpty()) { "Agent 需要本次真机证据" }
        val startedAt = clock.elapsedMillis()
        val budget = BudgetGuard(clock)
        val evidences = initial.toMutableList()
        val traces = mutableListOf<String>()
        val toolSteps = mutableListOf<AgentToolStep>()
        val messages = mutableListOf(
            AgentMessage("system", SYSTEM_PROMPT),
            AgentMessage(
                "user",
                "本次 Android 真机证据 JSON。仅依据其中实际状态和指标诊断；" +
                    "需要更多观测时选择固定工具。\n" +
                    Json.encodeToString(ListSerializer(Evidence.serializer()), initial.map(::forModel)),
                requireToolCall = initial.any { it.status == ProbeStatus.FAILED || it.status == ProbeStatus.TIMEOUT },
            ),
        )
        var outcome = AgentOutcome.INCONCLUSIVE
        var reason: String? = "模型未给出可审计结论"
        var audit: ClaimAudit? = null
        var proposedClaims = emptyList<ProposedClaim>()

        try {
            for (round in 0 until 10) {
                val remaining = budget.remainingMillis()
                if (remaining == 0L) {
                    reason = "90 秒诊断预算耗尽"
                    break
                }
                val turn = withTimeout(remaining) { complete(messages.toList(), remaining) }
                turn.traceId?.let(traces::add)
                when (turn) {
                    is ModelTurn.Final -> {
                        proposedClaims = turn.claims
                        audit = auditor.audit(turn.claims, evidences)
                        outcome = if (audit!!.accepted.isNotEmpty() && audit!!.rejectedCount == 0)
                            AgentOutcome.COMPLETED else AgentOutcome.INCONCLUSIVE
                        reason = when {
                            outcome == AgentOutcome.COMPLETED -> null
                            audit!!.rejectedCount > 0 ->
                                "模型有 ${audit!!.rejectedCount} 条断言未通过引用有效性审计；本轮模型结论不展示"
                            else -> "模型结论缺少有效证据引用"
                        }
                        break
                    }
                    is ModelTurn.Tools -> {
                        if (turn.calls.isEmpty() || turn.calls.any { it.id.isBlank() }) {
                            reason = "模型工具调用格式无效"
                            break
                        }
                        messages += AgentMessage("assistant", toolCalls = turn.calls)
                        for (call in turn.calls) {
                            val result = invoke(call.name, call.argumentsJson, budget)
                            val content = when (result) {
                                is ToolOutcome.Completed -> {
                                    evidences += result.evidence
                                    toolSteps += AgentToolStep(call.name, result.evidence.id, result.evidence.status)
                                    Json.encodeToString(Evidence.serializer(), forModel(result.evidence))
                                }
                                is ToolOutcome.Rejected -> {
                                    toolSteps += AgentToolStep(call.name, rejectedReason = result.reason)
                                    "{\"error\":${JsonPrimitive(result.reason)}}"
                                }
                            }
                            messages += AgentMessage("tool", content = content, toolCallId = call.id)
                        }
                        if (budget.attemptedCalls >= 8) {
                            reason = "8 次工具调用预算耗尽"
                            break
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            reason = "90 秒诊断预算耗尽"
        } catch (e: ModelUnavailableException) {
            outcome = AgentOutcome.MODEL_UNAVAILABLE
            reason = e.message ?: "模型网关不可用"
        } catch (e: ModelProtocolException) {
            reason = e.message ?: "模型响应不可解析"
        } catch (e: CancellationException) {
            throw e
        }
        return AgentReport(
            outcome = outcome,
            claims = if (outcome == AgentOutcome.COMPLETED) audit?.accepted.orEmpty() else emptyList(),
            evidences = evidences,
            attemptedToolCalls = budget.attemptedCalls,
            rejectedClaims = audit?.rejectedCount ?: 0,
            structuralRejectionRate = audit?.structuralRejectionRate,
            semanticReviewPending = outcome == AgentOutcome.COMPLETED && audit?.semanticReviewPending == true,
            traceIds = traces,
            reason = reason,
            toolSteps = toolSteps,
            durationMs = clock.elapsedMillis() - startedAt,
            proposedClaims = proposedClaims,
        )
    }

    private companion object {
        const val SYSTEM_PROMPT = "你是 NetScope 网络诊断 Agent。所有网络事实只来自给定 Evidence 或五个白名单工具。" +
            "不得猜测缺失值。最终 claims 的 evidenceIds 只允许引用状态为 OK 或 DEGRADED 的本次证据；" +
            "FAILED/TIMEOUT/UNSUPPORTED 证据仅供选择复测工具与判断能力边界，不可作为最终 Claim 引用。" +
            "失败证据的 metrics 为空，不能据此编造成功率、IP、耗时或具体根因。" +
            "模型传输副本中 [REDACTED_FOR_MODEL] 表示敏感响应头已遮蔽，不可推断原值。" +
            "只能依据证据直接陈述观测；SSID/BSSID 不可得时不得推断原因，" +
            "TCP 端口连通不能证明 DNS 查询服务正常、路由或防火墙整体正常，" +
            "冻结指标口径：tcp_probe.handshakeMs 是成功 TCP connect 耗时的中位数（毫秒），不是平均值；" +
            "targetCount 是测试目标数，successCount 是成功建连次数，不能把次数说成目标数。" +
            "dns_resolve.avgResolveMs/maxResolveMs 只统计成功解析项；" +
            "dns_resolve.systemDns/systemDnsCount 表示系统配置，不证明每次解析实际使用哪个 DNS 服务器；" +
            "跨探针对比必须同时引用双方证据，不把配置列表说成已观测到的请求路径。" +
            "NXDOMAIN 是当前采集器由 UnknownHostException 映射的失败标签，不是已验证的 DNS 报文响应码；" +
            "不能仅凭该标签断定域名不存在、DNS 服务器不可达或返回无效响应。" +
            "SSID/BSSID 的 <null> 或系统占位符只表示本次标识不可得，不得称为系统读取故障或推断权限原因。" +
            "wifi_detail.linkSpeedMbps 是链路协商速率，不是互联网实测带宽。" +
            "原始逐次耗时与聚合指标不同；直接引用指标时保留其统计口径，不将中位数改称平均值。" +
            "没有独立证据时不得断定 DNS 故障的具体根因。" +
            "若本轮存在 FAILED/TIMEOUT 探针，先自主选择最相关的一个白名单工具复测；" +
            "复测仍失败时不得继续猜测根因，也不得把失败证据说成成功指标。" +
            "每条结论必须引用本次证据 ID。最终只返回 JSON：" +
            "{\"claims\":[{\"text\":\"...\",\"evidenceIds\":[\"E001\"],\"confidence\":0.5}]}。" +
            "最终结论应逐条描述可用证据直接给出的观测，例如 TCP 测试目标的实际成功次数；" +
            "不得把单个测试端口连通扩大成整个网络正常。无法用可用证据支撑时返回 {\"claims\":[]}。"
    }
}

class AgentSession @Inject constructor(
    private val client: GatewayLlmClient,
    private val invoker: ToolInvoker,
    private val auditor: HallucinationAuditor,
    private val clock: Clock,
) {
    suspend fun run(initial: List<Evidence>): AgentReport =
        AgentRunner(clock, auditor).run(initial, client::complete, invoker::invoke)
}
