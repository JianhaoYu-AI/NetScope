package com.netscope.core.agent

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEvidenceProjectionTest {
    @Test fun masksResponseCredentialsWithoutChangingCollectedEvidence() {
        val original = Evidence(
            id = "E001",
            type = EvidenceType.HTTP_PROBE,
            status = ProbeStatus.OK,
            metrics = mapOf("statusCode" to "200"),
            rawSnapshot = "HTTP/1.1 200 OK\nSet-Cookie: session=private; Path=/\nContent-Type: text/html\n",
            source = "live HTTP response",
            timestampMs = 1L,
        )

        val projected = forModel(original)

        assertTrue(original.rawSnapshot.contains("session=private"))
        assertFalse(projected.rawSnapshot.contains("session=private"))
        assertTrue(projected.rawSnapshot.contains("Set-Cookie: [REDACTED_FOR_MODEL]"))
        assertTrue(projected.rawSnapshot.contains("Content-Type: text/html"))
        assertEquals(original.metrics, projected.metrics)
        assertEquals(original.id, projected.id)
    }
}
