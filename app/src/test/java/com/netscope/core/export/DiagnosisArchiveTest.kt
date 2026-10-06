package com.netscope.core.export

import com.netscope.core.agent.AgentOutcome
import com.netscope.core.agent.AgentReport
import com.netscope.core.agent.ProposedClaim
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DiagnosisArchiveTest {
    private fun evidence() = Evidence("E001", EvidenceType.HTTP_PROBE, ProbeStatus.FAILED,
        emptyMap(), "Set-Cookie: unit-test-only-cookie\nreason=timeout", "unit-test", 123L)

    @Test fun exportPreservesFailureAndMissingValuesWhileRedactingOnlyTheCopy() {
        val original = evidence()
        val bytes = DiagnosisArchive.encode("archive-1", 456L, "test", listOf(original), emptyList(), null, null)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val exported = root.getValue("evidences").jsonArray.single().jsonObject
        assertEquals("FAILED", exported.getValue("status").jsonPrimitive.content)
        assertTrue(exported.getValue("metrics").jsonObject.isEmpty())
        assertEquals("123", exported.getValue("timestampMs").jsonPrimitive.content)
        assertTrue(exported.getValue("rawSnapshot").jsonPrimitive.content.contains("[REDACTED_FOR_MODEL]"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("unit-test-only-cookie"))
        assertTrue(original.rawSnapshot.contains("unit-test-only-cookie"))
        assertEquals(JsonNull, root.getValue("agent"))
        assertEquals(JsonNull, root.getValue("humanSemanticReview"))
        assertEquals("false", root.getValue("countsAsBenchmarkResult").jsonPrimitive.content)
    }

    @Test fun rejectedProposalsAreReviewDataAndDoNotBecomeAcceptedClaims() {
        val report = AgentReport(AgentOutcome.INCONCLUSIVE, emptyList(), listOf(evidence()), 1, 1,
            1.0, false, listOf("trace-test"), durationMs = 876L,
            proposedClaims = listOf(ProposedClaim("unit-test rejected assertion", listOf("E001"))))
        val root = Json.parseToJsonElement(DiagnosisArchive.encode("a", 456L, "test", report.evidences,
            emptyList(), report, null).toString(Charsets.UTF_8)).jsonObject
        val agent = root.getValue("agent").jsonObject
        assertTrue(agent.getValue("acceptedClaims").jsonArray.isEmpty())
        assertEquals(1, agent.getValue("proposedClaimsForReview").jsonArray.size)
        assertEquals("876", agent.getValue("durationMs").jsonPrimitive.content)
        assertEquals("1", agent.getValue("rejectedClaims").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun repeatedIdsFromMixedSessionsCannotBeExported() {
        DiagnosisArchive.encode("a", 1L, "test", listOf(evidence(), evidence()), emptyList(), null, null)
    }
}
