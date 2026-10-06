package com.netscope.core.agent

import org.junit.Assert.*
import org.junit.Test

class GatewayAccessTest {
    @Test fun rejectsCleartextExternalHostsAndCredentialUrls() {
        listOf("http://example.com/v1/chat/completions", "https://user:secret@example.com/v1/chat/completions",
            "https://example.com/v1/chat/completions?key=secret", "https://example.com/v1/chat/completions#fragment",
            "https://example.com:0/v1/chat/completions", "https://example.com/redirect").forEach {
            assertFalse(it, validGatewayEndpoint(it))
        }
        assertTrue(validGatewayEndpoint("https://example.com/v1/chat/completions"))
        assertTrue(validGatewayEndpoint("http://127.0.0.1:8787/v1/chat/completions"))
    }

    @Test fun invalidConfigurationPreservesLastWorkingConnectionWithoutLeakingCode() {
        val access = GatewayAccess()
        val code = "synthetic-unit-test-access-code-12345"
        assertTrue(access.configure("https://example.com/v1/chat/completions", code))
        assertFalse(access.configure("http://example.com/v1/chat/completions", code))
        assertFalse(access.configure("https://example.com/v1/chat/completions", ""))
        assertFalse(access.snapshot().toString().contains(code))
        assertEquals(code, access.snapshot().accessCode)
        assertTrue(access.configure("http://127.0.0.1:8787/v1/chat/completions", ""))
        assertEquals("", access.snapshot().accessCode)
    }
}
