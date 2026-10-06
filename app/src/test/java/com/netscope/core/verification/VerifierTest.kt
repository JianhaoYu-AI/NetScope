package com.netscope.core.verification

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierTest {
    private val verifier = Verifier()

    @Test fun realStatusRecoveryRequiresFreshUsableEvidenceAndRuleDisappearance() {
        val report = verifier.compare(
            before = listOf(e("E001", EvidenceType.DNS_RESOLVE, ProbeStatus.FAILED)),
            after = listOf(e("E006", EvidenceType.DNS_RESOLVE, ProbeStatus.OK, mapOf("successCount" to "3"))),
            priorRuleIds = setOf("B07"), currentRuleIds = emptySet(),
        )
        assertEquals(VerificationOutcome.OBSERVED_RECOVERY, report.outcome)
        assertEquals("E001", report.comparisons.single().beforeId)
        assertEquals("E006", report.comparisons.single().afterId)
    }

    @Test fun persistentRuleCannotBeCalledRecovered() {
        val before = listOf(e("E001", EvidenceType.DNS_RESOLVE, ProbeStatus.OK, mapOf("avgResolveMs" to "3500")))
        val after = listOf(e("E006", EvidenceType.DNS_RESOLVE, ProbeStatus.OK, mapOf("avgResolveMs" to "3600")))
        val report = verifier.compare(before, after, setOf("B08"), setOf("B08"))
        assertEquals(VerificationOutcome.STILL_AFFECTED, report.outcome)
        assertEquals(listOf(MetricComparison("avgResolveMs", "3500", "3600")), report.comparisons.single().metrics)
    }

    @Test fun missingProbeOrFailedRuleEvaluationIsInconclusive() {
        val before = listOf(e("E001", EvidenceType.DNS_RESOLVE, ProbeStatus.FAILED))
        assertEquals(VerificationOutcome.INCONCLUSIVE,
            verifier.compare(before, emptyList(), setOf("B07"), emptySet()).outcome)
        assertEquals(VerificationOutcome.INCONCLUSIVE,
            verifier.compare(before, listOf(e("E006", EvidenceType.DNS_RESOLVE, ProbeStatus.OK)), setOf("B07"), null).outcome)
    }

    @Test fun unavailableMetricNeverBecomesNumericZero() {
        val before = listOf(e("E001", EvidenceType.WIFI_DETAIL, ProbeStatus.OK, mapOf("rssiDbm" to "<null>")))
        val after = listOf(e("E006", EvidenceType.WIFI_DETAIL, ProbeStatus.OK, mapOf("rssiDbm" to "-42")))
        val report = verifier.compare(before, after, emptySet(), emptySet())
        assertEquals(VerificationOutcome.NO_PRIOR_ISSUE, report.outcome)
        assertTrue(report.comparisons.single().metrics.isEmpty())
    }

    @Test fun newlyAppearingFailureIsNotHealthy() {
        val before = listOf(e("E001", EvidenceType.HTTP_PROBE, ProbeStatus.OK))
        val after = listOf(e("E006", EvidenceType.HTTP_PROBE, ProbeStatus.FAILED))
        assertEquals(VerificationOutcome.STILL_AFFECTED,
            verifier.compare(before, after, emptySet(), emptySet()).outcome)
    }

    private fun e(id: String, type: EvidenceType, status: ProbeStatus, metrics: Map<String, String> = emptyMap()) =
        Evidence(id, type, status, metrics, "raw", "test", if (id == "E001") 1L else 2L)
}
