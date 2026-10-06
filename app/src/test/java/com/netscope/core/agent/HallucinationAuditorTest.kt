package com.netscope.core.agent

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationAuditorTest {
    @Test fun rejectsEmptyUnknownAndUnavailableReferences() {
        val evidence = listOf(
            Evidence("E001", EvidenceType.DNS_RESOLVE, ProbeStatus.OK, emptyMap(), "raw", "test", 1),
            Evidence("E002", EvidenceType.HTTP_PROBE, ProbeStatus.FAILED, emptyMap(), "raw", "test", 1),
        )
        val result = HallucinationAuditor().audit(listOf(
            ProposedClaim("valid reference only", listOf("E001")),
            ProposedClaim("empty", emptyList()),
            ProposedClaim("unknown", listOf("E999")),
            ProposedClaim("unavailable", listOf("E002")),
        ), evidence)
        assertEquals(1, result.accepted.size)
        assertTrue(result.accepted.single().verified)
        assertEquals(3, result.rejectedCount)
        assertEquals(0.75, result.structuralRejectionRate!!, 0.0)
        assertTrue(result.semanticReviewPending)
    }

    @Test fun emptyOutputHasNoInventedHallucinationRate() {
        val result = HallucinationAuditor().audit(emptyList(), emptyList())
        assertEquals(null, result.structuralRejectionRate)
        assertEquals(false, result.semanticReviewPending)
    }
}
