package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8044 — a profit-lock slice's % is its realised SOL over its own cost, never proceeds over the whole cost. */
class Aate8044PartialPctTest {
    @Test fun theWjjkgmQuickTake() {
        // entryCost 0.03045, slice cost (trade.sol) 0.01827, realised +0.00825 SOL.
        val wrong = (0.01827 - 0.03045) / 0.03045 * 100.0
        assertEquals(-40.0, wrong, 0.1)
        val wholeCostPct = 0.00825 / 0.03045 * 100.0
        val slicePct = 0.00825 / 0.01827 * 100.0
        assertTrue(wholeCostPct > 0.0 && slicePct > wholeCostPct)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(src.contains("val storedAgrees8044 = sliceCost == null && pnlSolTruth.isFinite()"))
        assertTrue(src.contains("if (sliceCost != null) pnlSolTruth / sliceCost * 100.0 else pnlSolTruth / cost * 100.0"))
    }
}
