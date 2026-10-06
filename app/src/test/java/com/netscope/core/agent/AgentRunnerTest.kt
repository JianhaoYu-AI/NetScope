package com.netscope.core.agent

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import com.netscope.core.util.FixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerTest {
    private fun evidence(id: String) = Evidence(
        id = id,
        type = EvidenceType.DNS_RESOLVE,
        status = ProbeStatus.OK,
        metrics = mapOf("successCount" to "3"),
        rawSnapshot = "unit-test observation",
        source = "unit-test",
        timestampMs = 1L,
    )

    @Test fun missingGatewayDegradesWithoutInventingClaims() = runBlocking {
        val initial = evidence("E001")
        val report = AgentRunner(FixedClock(), HallucinationAuditor()).run(
            initial = listOf(initial),
            complete = { _, _ -> throw ModelUnavailableException("HTTP 503") },
            invoke = { _, _, _ -> error("no tool should run") },
        )
        assertEquals(AgentOutcome.MODEL_UNAVAILABLE, report.outcome)
        assertTrue(report.claims.isEmpty())
        assertEquals(listOf(initial), report.evidences)
        assertEquals(0, report.attemptedToolCalls)
    }

    @Test fun toolResultMustBeAuditedBeforeClaimIsShown() = runBlocking {
        var turns = 0
        val report = AgentRunner(FixedClock(), HallucinationAuditor()).run(
            initial = listOf(evidence("E001")),
            complete = { messages, _ ->
                turns++
                if (turns == 1) ModelTurn.Tools(listOf(AgentToolCall("call-1", "dns_resolve", "{}")), "trace-1")
                else {
                    assertTrue(messages.any { it.role == "tool" && it.toolCallId == "call-1" && it.content!!.contains("E002") })
                    ModelTurn.Final(listOf(ProposedClaim("解析成功", listOf("E002"))), "trace-2")
                }
            },
            invoke = { name, args, budget ->
                assertEquals("dns_resolve", name)
                assertEquals("{}", args)
                assertTrue(budget.reserve())
                ToolOutcome.Completed(evidence("E002"))
            },
        )
        assertEquals(AgentOutcome.COMPLETED, report.outcome)
        assertEquals(1, report.claims.size)
        assertTrue(report.claims.single().verified)
        assertTrue(report.semanticReviewPending)
        assertEquals(1, report.attemptedToolCalls)
        assertEquals(listOf(AgentToolStep("dns_resolve", "E002", ProbeStatus.OK)), report.toolSteps)
        assertEquals(listOf("trace-1", "trace-2"), report.traceIds)
    }

    @Test fun failedInitialProbeRequiresOneModelSelectedRecheckOnlyOnFirstRound() = runBlocking {
        var rounds = 0
        val failed = evidence("E001").copy(status = ProbeStatus.FAILED, metrics = emptyMap())
        AgentRunner(FixedClock(), HallucinationAuditor()).run(
            initial = listOf(failed),
            complete = { messages, _ ->
                rounds++
                if (rounds == 1) {
                    assertEquals("required", toolChoiceFor(messages))
                    ModelTurn.Tools(listOf(AgentToolCall("call-1", "dns_resolve", "{}")), null)
                } else {
                    assertEquals("auto", toolChoiceFor(messages))
                    ModelTurn.Final(emptyList(), null)
                }
            },
            invoke = { _, _, budget ->
                assertTrue(budget.reserve())
                ToolOutcome.Completed(evidence("E002"))
            },
        )
        assertEquals(2, rounds)
    }

    @Test fun unknownCitationCannotBecomeAClaim() = runBlocking {
        val report = AgentRunner(FixedClock(), HallucinationAuditor()).run(
            initial = listOf(evidence("E001")),
            complete = { _, _ -> ModelTurn.Final(listOf(ProposedClaim("无依据结论", listOf("E999"))), null) },
            invoke = { _, _, _ -> error("no tool should run") },
        )
        assertEquals(AgentOutcome.INCONCLUSIVE, report.outcome)
        assertEquals(1, report.rejectedClaims)
        assertEquals(1.0, report.structuralRejectionRate!!, 0.0)
        assertFalse(report.semanticReviewPending)
    }

    @Test fun aRejectedClaimPreventsPartialModelDiagnosisFromBeingShown() = runBlocking {
        val report = AgentRunner(FixedClock(), HallucinationAuditor()).run(
            initial = listOf(evidence("E001")),
            complete = { _, _ -> ModelTurn.Final(listOf(
                ProposedClaim("解析成功", listOf("E001")),
                ProposedClaim("没有对应证据", listOf("E999")),
            ), null) },
            invoke = { _, _, _ -> error("no tool should run") },
        )
        assertEquals(AgentOutcome.INCONCLUSIVE, report.outcome)
        assertTrue(report.claims.isEmpty())
        assertEquals(1, report.rejectedClaims)
        assertEquals(0.5, report.structuralRejectionRate!!, 0.0)
        assertFalse(report.semanticReviewPending)
    }

    @Test fun repeatedToolCallsStopAtEightAttempts() = runBlocking {
        var sequence = 1
        val report = AgentRunner(FixedClock(), HallucinationAuditor()).run(
            initial = listOf(evidence("E001")),
            complete = { _, _ -> ModelTurn.Tools(listOf(AgentToolCall("call-${sequence++}", "dns_resolve", "{}")), null) },
            invoke = { _, _, budget ->
                assertTrue(budget.reserve())
                ToolOutcome.Completed(evidence("E${sequence.toString().padStart(3, '0')}"))
            },
        )
        assertEquals(AgentOutcome.INCONCLUSIVE, report.outcome)
        assertEquals(8, report.attemptedToolCalls)
        assertTrue(report.claims.isEmpty())
    }

    @Test fun promptMatchesFrozenCitationPolicyAndAuditRetainsRejectedProposals() = runBlocking {
        val clock = FixedClock()
        val proposal = ProposedClaim("unit-test rejected claim", listOf("E999"))
        val report = AgentRunner(clock, HallucinationAuditor()).run(
            initial = listOf(evidence("E001")),
            complete = { messages, _ ->
                val prompt = messages.first().content!!
                assertTrue(prompt.contains("只允许引用状态为 OK 或 DEGRADED"))
                assertTrue(prompt.contains("不可作为最终 Claim 引用"))
                assertTrue(prompt.contains("tcp_probe.handshakeMs 是成功 TCP connect 耗时的中位数（毫秒），不是平均值"))
                assertTrue(prompt.contains("链路协商速率，不是互联网实测带宽"))
                assertTrue(prompt.contains("NXDOMAIN 是当前采集器由 UnknownHostException 映射的失败标签，不是已验证的 DNS 报文响应码"))
                assertTrue(prompt.contains("跨探针对比必须同时引用双方证据"))
                assertTrue(prompt.contains("不得称为系统读取故障或推断权限原因"))
                clock.advance(321L)
                ModelTurn.Final(listOf(proposal), null)
            },
            invoke = { _, _, _ -> error("no tool should run") },
        )
        assertEquals(321L, report.durationMs)
        assertEquals(listOf(proposal), report.proposedClaims)
        assertTrue(report.claims.isEmpty())
    }
}

class ModelResponseParserTest {
    @Test fun parsesToolCallsAndStructuredClaims() {
        val tool = parseTurn(
            """{"choices":[{"message":{"tool_calls":[{"id":"c1","function":{"name":"dns_resolve","arguments":"{}"}}]}}]}""",
            "t1",
        ) as ModelTurn.Tools
        assertEquals("dns_resolve", tool.calls.single().name)
        assertEquals("t1", tool.traceId)

        val final = parseTurn(
            """{"choices":[{"message":{"content":"{\"claims\":[{\"text\":\"DNS 可达\",\"evidenceIds\":[\"E001\"]}]}"}}]}""",
            null,
        ) as ModelTurn.Final
        assertEquals(listOf("E001"), final.claims.single().evidenceIds)
    }

    @Test(expected = ModelProtocolException::class)
    fun rejectsUnstructuredOutput() {
        parseTurn("""{"choices":[{"message":{"content":"我觉得网络很好"}}]}""", null)
    }
}
