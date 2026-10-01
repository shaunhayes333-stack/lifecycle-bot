package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7692ModeScopedPerformanceEvidenceTest {
    private fun close(mode: String) = Trade(
        side = "SELL",
        mode = mode,
        sol = 0.1,
        price = 1.0,
        ts = 1L,
    )

    @Test fun liveEvidenceExcludesPaperCloses() {
        val rows = listOf(close("paper"), close("LIVE"), close("PaPeR"))
        assertEquals(listOf("LIVE"), scopePerformanceEvidence4517(rows, "live").map { it.mode })
    }

    @Test fun paperEvidenceExcludesLiveCloses() {
        val rows = listOf(close("paper"), close("LIVE"), close("PAPER"))
        assertEquals(listOf("paper", "PAPER"), scopePerformanceEvidence4517(rows, "paper").map { it.mode })
    }

    @Test fun historicalReportingCanStillRequestAllModes() {
        val rows = listOf(close("paper"), close("live"))
        assertEquals(rows, scopePerformanceEvidence4517(rows, null))
        assertTrue(scopePerformanceEvidence4517(rows, "unknown").isEmpty())
    }
}
