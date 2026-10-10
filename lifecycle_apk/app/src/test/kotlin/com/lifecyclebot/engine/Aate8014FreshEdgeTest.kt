package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.HiveEdge8000
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8014FreshEdgeTest {
    @Test fun aCellsRecordTurnsWhenTheMarketTurns() {
        val s = CortexLedger7885.Stat()
        repeat(2_000) { s.add(10.0, false) }          // weeks of +10%
        repeat(500) { s.add(-10.0, false) }           // the market turns
        assertEquals(CortexLedger7885.STAT_WINDOW_8014, s.n, 1e-6)
        assertTrue("recent losses dominate: ${s.mean()}", s.mean() < -2.0)
    }

    @Test fun labelCellsKeepTheirMostRecentWindow() {
        val t = ForwardReturnLabeler7731.Tally()
        t.n60 = 900; t.sum60 = 900.0; t.sumSq60 = 9_000.0; t.win60 = 600; t.runner60 = 90
        t.window8014(ForwardReturnLabeler7731.CELL_WINDOW_8014)
        assertEquals(300, t.n60)
        assertEquals(300.0, t.sum60, 1e-9)          // mean +1% kept
        assertEquals(200, t.win60); assertEquals(30, t.runner60)
    }

    @Test fun theNetworkNeverSwampsLocalFreshLabels() {
        val big = doubleArrayOf(10_000.0, 50_000.0, 900_000.0, 6_000.0, 500.0, 900.0)
        val c = HiveEdge8000.capWeight8014(big, HiveEdge8000.HIVE_WINDOW_8014)
        assertEquals(1_000.0, c[0], 1e-9); assertEquals(5_000.0, c[1], 1e-9)   // mean +5% kept
        assertEquals(900.0, c[5], 1e-9)                                         // best kept
        val small = doubleArrayOf(40.0, 80.0, 400.0, 20.0, 2.0, 50.0)
        assertTrue(HiveEdge8000.capWeight8014(small, HiveEdge8000.HIVE_WINDOW_8014) === small)
    }
}
