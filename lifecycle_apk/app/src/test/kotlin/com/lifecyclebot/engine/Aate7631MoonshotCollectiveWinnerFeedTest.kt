package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7631MoonshotCollectiveWinnerFeedTest {
    @Test fun `moonshot collective memory consumes only raw ten-x peer outcomes`() {
        val s = File("src/main/kotlin/com/lifecyclebot/v3/scoring/CollectiveIntelligenceAI.kt").readText()
        val fn = s.substringAfter("private suspend fun refreshNetworkSignals()")
            .substringBefore("fun getNetworkHotMints()")
        assertTrue(fn.contains("signal.pnlPct >= 900.0"))
        assertTrue(fn.contains("MoonshotTraderAI.recordCollectiveWinner("))
        assertTrue(fn.contains("networkTraders = 1"))
        assertTrue(fn.contains("avgEntryMcap = 0.0"))
        assertTrue(fn.contains("signal.confidence.coerceIn(0, 100) / 100.0"))
        assertTrue(fn.contains("MOONSHOT_COLLECTIVE_10X_FEED_7631"))
    }

    @Test fun `weaker duplicate peer row cannot overwrite stronger mint signal`() {
        val s = File("src/main/kotlin/com/lifecyclebot/v3/scoring/CollectiveIntelligenceAI.kt").readText()
        val fn = s.substringAfter("private suspend fun refreshNetworkSignals()")
            .substringBefore("fun getNetworkHotMints()")
        assertTrue(fn.contains("networkSignalsCache.merge(signal.mint, signal)"))
        assertTrue(fn.contains("if (incoming.pnlPct > old.pnlPct) incoming else old"))
        assertTrue(fn.contains("MoonshotTraderAI.cleanCollectiveWinners()"))
    }
}
