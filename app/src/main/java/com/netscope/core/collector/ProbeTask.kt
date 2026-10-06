package com.netscope.core.collector

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.util.Clock
import com.netscope.core.util.EvidenceIdGenerator
import com.netscope.core.util.NsLog
import com.netscope.core.util.ProbeResult
import com.netscope.core.util.toEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ProbeTask @Inject constructor(
    private val clock: Clock,
    private val idGen: EvidenceIdGenerator,
) {
    /**
     * 执行一次采集。
     *
     * @param collector 待执行的采集器。
     * @param timeoutMs 超时上限（毫秒）。默认 8000。
     * @param source 证据来源字符串，写入 [Evidence.source]，便于在 UI 与归档里定位 API 调用。
     * @return 成功或失败一律返回 [Evidence]；协程取消（[CancellationException] / [InterruptedException]）会向上抛。
     */
    suspend fun run(
        collector: Collector<*>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        source: String,
    ): Evidence {
        val startedAt = clock.elapsedMillis()
        val id = idGen.next()
        val type: EvidenceType = collector.type

        val outcome: ProbeResult<*> = try {
            withTimeout(timeMillis = timeoutMs) {
                collector.collect()
            }
        } catch (e: TimeoutCancellationException) {
            ProbeResult.Timeout(
                timeoutMs = timeoutMs,
                rawSnapshot = "probe=${type.toolName}\nreason=timeout\ntimeoutMs=$timeoutMs",
                durationMs = clock.elapsedMillis() - startedAt,
            )
        } catch (e: CancellationException) {
            // 协程结构化取消必须传播，**绝对不可**包装成 Failed 证据——
            // 否则上层 ViewModel 会看到一条「假失败」而把功能当作降级处理。
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            ProbeResult.Failed(
                error = "${t::class.java.simpleName}: ${t.message ?: "<无说明>"}",
                rawSnapshot = "probe=${type.toolName}\nreason=uncaught\nclass=${t::class.java.name}\nmessage=${t.message ?: "<null>"}",
                cause = t,
                durationMs = clock.elapsedMillis() - startedAt,
            )
        }

        val evidence = outcome.toEvidence(
            id = id,
            type = type,
            source = source,
            timestampMs = clock.nowMillis(),
        )
        // 校正耗时：若 outcome 内部没填（如采集器直接构造 Success 时填 0），用外层实测覆盖
        val finalEvidence = if (evidence.durationMs == 0L) {
            evidence.copy(durationMs = clock.elapsedMillis() - startedAt)
        } else evidence

        NsLog.i(
            "探针 ${type.toolName} 完成：id=${finalEvidence.id} " +
                "status=${finalEvidence.status.name} " +
                "durationMs=${finalEvidence.durationMs}",
        )
        return finalEvidence
    }

    private companion object {
        /** 默认单探针超时 8 秒——足够网络层往返，且不会让用户等太久。 */
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}