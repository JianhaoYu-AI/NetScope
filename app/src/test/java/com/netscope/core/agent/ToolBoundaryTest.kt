package com.netscope.core.agent

import com.netscope.core.util.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBoundaryTest {
    @Test fun allowlistRejectsUnknownToolsAndAllArguments() {
        assertEquals(5, ToolCatalog.specs.size)
        assertTrue(ToolCatalog.accepts("dns_resolve", "{}"))
        assertFalse(ToolCatalog.accepts("shell", "{}"))
        assertFalse(ToolCatalog.accepts("http_probe", "{\"url\":\"http://example.com\"}"))
        assertFalse(ToolCatalog.accepts("tcp_probe", "not-json"))
    }

    @Test fun budgetStopsAtEightAttemptsAndNinetySeconds() {
        val clock = FixedClock()
        val budget = BudgetGuard(clock)
        repeat(8) { assertTrue(budget.reserve()) }
        assertFalse(budget.reserve())
        assertEquals(8, budget.attemptedCalls)

        val second = BudgetGuard(clock)
        clock.advance(90_000)
        assertFalse(second.reserve())
        assertEquals(0, second.attemptedCalls)
    }
}
