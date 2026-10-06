package com.netscope.core.rules

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator()

    @Test
    fun offlineRuleRequiresObservedNone() {
        val rule = DiagnosisRule("B12", "NO_ACTIVE_NETWORK", "无活动网络", 0.97,
            listOf(RuleCondition(EvidenceType.NET_OVERVIEW, ProbeStatus.OK,
                "networkType", "eq", "NONE")))
        val none = evidence("E001", EvidenceType.NET_OVERVIEW, ProbeStatus.OK,
            mapOf("networkType" to "NONE"))
        assertEquals(listOf("E001"), evaluator.evaluate(listOf(rule), listOf(none)).single().evidenceIds)
        assertTrue(evaluator.evaluate(listOf(rule), listOf(none.copy(metrics = emptyMap()))).isEmpty())
    }

    @Test
    fun dnsFailureNeedsRealIpConnectSuccess() {
        val rule = DiagnosisRule("B07", "DNS_RESOLUTION_UNAVAILABLE", "DNS 解析不可用", 0.78,
            listOf(
                RuleCondition(EvidenceType.DNS_RESOLVE, ProbeStatus.FAILED),
                RuleCondition(EvidenceType.TCP_PROBE, ProbeStatus.OK,
                    "successCount", "gte", "1"),
            ))
        val dns = evidence("E001", EvidenceType.DNS_RESOLVE, ProbeStatus.FAILED)
        val tcp = evidence("E002", EvidenceType.TCP_PROBE, ProbeStatus.OK,
            mapOf("successCount" to "1"))
        assertEquals(1, evaluator.evaluate(listOf(rule), listOf(dns, tcp)).size)
        assertTrue(evaluator.evaluate(listOf(rule), listOf(dns,
            tcp.copy(status = ProbeStatus.UNSUPPORTED, metrics = emptyMap()))).isEmpty())
        assertTrue(evaluator.evaluate(listOf(rule), listOf(dns,
            tcp.copy(metrics = mapOf("successCount" to "<null>")))).isEmpty())
    }

    @Test
    fun slowDnsRuleDoesNotFlagOneTransientSlowDomain() {
        val rule = DiagnosisRule("B08", "DNS_SLOW_RESOLUTION", "DNS 平均解析慢", 0.72,
            listOf(RuleCondition(EvidenceType.DNS_RESOLVE, ProbeStatus.OK,
                "avgResolveMs", "gte", "3000")))
        val dns = evidence("E001", EvidenceType.DNS_RESOLVE, ProbeStatus.OK,
            mapOf("avgResolveMs" to "1043"))
        assertTrue(evaluator.evaluate(listOf(rule), listOf(dns)).isEmpty())
        assertEquals(1, evaluator.evaluate(listOf(rule),
            listOf(dns.copy(metrics = mapOf("avgResolveMs" to "3000")))).size)
    }

    private fun evidence(id: String, type: EvidenceType, status: ProbeStatus,
        metrics: Map<String, String> = emptyMap()) = Evidence(
        id = id, type = type, status = status, metrics = metrics,
        rawSnapshot = "unit-test-only", source = "unit-test-only", timestampMs = 1L,
    )
}
