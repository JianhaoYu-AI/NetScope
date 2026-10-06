package com.netscope.ui.collect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectUiStateTest {
    @Test fun modelAndExportOperationsPreventAConcurrentNewCollection() {
        assertTrue(CollectUiState().canCollect)
        assertFalse(CollectUiState(agentRunning = true).canCollect)
        assertFalse(CollectUiState(exporting = true).canCollect)
        assertFalse(CollectUiState(verifying = true).canCollect)
        assertFalse(CollectUiState(saving = true).canCollect)
        assertFalse(CollectUiState(collecting = true).canCollect)
    }
}
