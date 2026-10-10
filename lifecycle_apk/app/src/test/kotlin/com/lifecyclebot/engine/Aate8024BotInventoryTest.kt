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

    @Test fun theCostOfAHoldingIsReplayedFromTheChainAndMustMatchTheBalance() {
        val M = com.lifecyclebot.engine.truth.OnChainCost8024
        fun mv(slot: Long, tok: Long, sol: Long) = com.lifecyclebot.engine.truth.OnChainCost8024.Move(0L, slot, java.math.BigInteger.valueOf(tok), sol)
        // buy 7,486 for 0.015 SOL, buy 24,081 for 0.03 SOL, nothing sold -> 31,567 held at 0.045 SOL
        val r = M.replay8024(listOf(mv(2, 24_081, -30_000_000), mv(1, 7_486, -15_000_000)))
        assertEquals(java.math.BigInteger.valueOf(31_567), r.heldRaw)
        assertEquals(45_000_000.0, r.costLamports, 1.0)
        assertTrue(M.reconciles8024(r.heldRaw, java.math.BigInteger.valueOf(31_567)))
        assertFalse(M.reconciles8024(java.math.BigInteger.valueOf(7_486), java.math.BigInteger.valueOf(31_567)))
        // buy 1,000 for 1 SOL, sell 500 (half the cost leaves), buy 500 for 2 SOL -> 1,000 held at 2.5 SOL
        val r2 = M.replay8024(listOf(mv(1, 1_000, -1_000_000_000), mv(2, -500, 600_000_000), mv(3, 500, -2_000_000_000)))
        assertEquals(java.math.BigInteger.valueOf(1_000), r2.heldRaw)
        assertEquals(2_500_000_000.0, r2.costLamports, 1.0)
        assertEquals(2, r2.buys); assertEquals(1, r2.sells)
        // a transfer in costs nothing; a full exit resets the cost
        val r3 = M.replay8024(listOf(mv(1, 100, -5), mv(2, -100, 9), mv(3, 50, 0)))
        assertEquals(0.0, r3.costLamports, 0.0); assertEquals(1, r3.transfersIn)
        val rec = src("engine/LiveCanonicalRecovery6686.kt")
        assertTrue(rec.contains("chainBasis8024(mint, chain8024, amount, basisRaw7959) ?: spentReceiptGuard7959(mint, amount, ts, basisRaw7959)"))
        assertTrue(rec.contains("source = \"ONCHAIN_COST_8024\""))
        assertTrue(M.statusLine().contains("reconciled="))
    }
}
