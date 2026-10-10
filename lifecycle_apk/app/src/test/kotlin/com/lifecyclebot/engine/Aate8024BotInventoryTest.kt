package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.LiveReceiptSpent7959
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8024BotInventoryTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aMintTheJournalTradedLiveIsBotInventory() {
        val t = listOf(
            Trade(side = "BUY", mode = "live", sol = 0.015, price = 2.2e-4, ts = 1L, mint = "Gyk9Z93Ri2JUjqES9HTpc7pxCJTLD7vhBhEjBhyApump"),
            Trade(side = "SELL", mode = "live", sol = 0.03, price = 2.9e-4, ts = 2L, mint = "7536qC4LEs3W2GtSKbhVGNJSiRfuXJhKVthxsgfkpump"),
            Trade(side = "BUY", mode = "paper", sol = 0.1, price = 1.0, ts = 3L, mint = "PAPERONLY"),
        )
        assertEquals(setOf("Gyk9Z93Ri2JUjqES9HTpc7pxCJTLD7vhBhEjBhyApump", "7536qC4LEs3W2GtSKbhVGNJSiRfuXJhKVthxsgfkpump"), BotJournalMints8024.liveMints8024(t))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt").contains("BotJournalMints8024.botTraded8024(mint) ||"))
        assertTrue(src("engine/OwnerManualHoldings7976.kt").contains("if (p == null || BotJournalMints8024.botTraded8024(mint, nowMs)) false else {"))
    }

    @Test fun aResidualIsRetriedNotParked() {
        assertFalse(LiveReceiptSpent7959.readoptDue8024(60_000L))
        assertTrue(LiveReceiptSpent7959.readoptDue8024(LiveReceiptSpent7959.READOPT_WINDOW_MS_8024))
        LiveReceiptSpent7959.resetForTest()
        assertFalse(LiveReceiptSpent7959.shouldPark("RESID8024", 1_000_000L))                     // first: free
        assertTrue(LiveReceiptSpent7959.shouldPark("RESID8024", 1_060_000L))                      // inside the window
        assertFalse(LiveReceiptSpent7959.shouldPark("RESID8024", 1_000_000L + LiveReceiptSpent7959.READOPT_WINDOW_MS_8024 + 1))
        val g = src("engine/LiveCanonicalRecovery6686.kt").substringAfter("private fun spentReceiptGuard7959(").substringBefore("private fun residualBasis7962(")
        assertTrue(g.contains("if (park) return null"))
        assertFalse(g.contains("markDustUnroutable7714(mint)"))
        assertTrue(BotJournalMints8024.statusLine().contains("journalLiveMints="))
    }
}
