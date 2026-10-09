package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7967 — grab a forming runner in a proven cell, hold it on the owner's ladder, audit the misses. */
class Aate7967RunnerGrabTest {
    private fun read(hhHl: Int, higherLow: Boolean, broke: Boolean, buyShare: Double, close: Double = 1.2, lastLow: Double = 1.0) =
        StructureTracker7962.Read7962(30, hhHl, lastLow, 1.1, true, higherLow, true, true, buyShare, close, broke)

    @Test fun provenCellIsTheLabelsOwnRecord() {
        assertTrue(RunnerGrab7967.provenCell7967(49, 162.6, 40.0))     // the +160% cell
        assertFalse(RunnerGrab7967.provenCell7967(10, 162.6, 40.0))    // too thin
        assertFalse(RunnerGrab7967.provenCell7967(607, 9.3, 2.0))      // pays, but not a runner cell
        assertFalse(RunnerGrab7967.provenCell7967(49, 162.6, Double.POSITIVE_INFINITY))
    }

    @Test fun firmingIsHigherLowsWithBuyers() {
        assertTrue(RunnerGrab7967.firming7967(read(3, true, false, 0.7)))
        assertTrue(RunnerGrab7967.firming7967(read(2, true, false, Double.NaN)))   // tape share unknown
        assertFalse(RunnerGrab7967.firming7967(read(1, true, false, 0.7)))         // no run yet
        assertFalse(RunnerGrab7967.firming7967(read(3, true, true, 0.7)))          // broken
        assertFalse(RunnerGrab7967.firming7967(read(3, true, false, 0.4)))         // sellers dominate
        assertFalse(RunnerGrab7967.firming7967(read(3, true, false, 0.7, close = 0.9)))   // under the last low
        assertFalse(RunnerGrab7967.firming7967(null))
    }

    @Test fun holdDefersSoftExitsOnly() {
        assertTrue(RunnerGrab7967.deferrable7967("TICK_PROFIT_LOCK_peak6_now1", 40.0))
        assertTrue(RunnerGrab7967.deferrable7967("TICK_HARD_FLOOR_-20PCT", -20.0))          // Frank's shakeout
        assertTrue(RunnerGrab7967.deferrable7967("THIN_LIQ_EARLY_RUG_BACKSTOP_-10", -10.0))
        assertFalse(RunnerGrab7967.deferrable7967("TICK_HARD_FLOOR_-40PCT", -40.0))         // past -35
        assertFalse(RunnerGrab7967.deferrable7967("DEV_SOLD", 20.0))
        assertFalse(RunnerGrab7967.deferrable7967("RUG_LIQUIDITY_PULLED", 5.0))
        assertFalse(RunnerGrab7967.deferrable7967("RUNNER_STRUCTURE_BREAK_7967_1M_300PCT", 300.0))
        assertEquals(listOf(100.0 to 0.50, 400.0 to 0.35, 1_000.0 to 0.35), RunnerGrab7967.TIERS_7967)
    }

    @Test fun recordStandsDownWhenGrabsLose() {
        val lose = CortexLedger7885.Stat().also { s -> repeat(25) { i -> s.add(if (i % 2 == 0) -30.0 else -5.0, false) } }
        val win = CortexLedger7885.Stat().also { s -> repeat(25) { i -> s.add(if (i % 2 == 0) 120.0 else -20.0, false) } }
        assertTrue(RunnerGrab7967.recordStandsDown7967(lose))
        assertFalse(RunnerGrab7967.recordStandsDown7967(win))
        assertFalse(RunnerGrab7967.recordStandsDown7967(CortexLedger7885.Stat()))
    }

    @Test fun missedRunnersAreNamed() {
        RunnerGrab7967.resetForTest7967()
        assertEquals("CORTEX_7885_C3_PROVEN", RunnerGrab7967.reasonKey7967("CORTEX_7885_C3_PROVEN_NEGATIVE_EDGE_MOONSHOT"))
        RunnerGrab7967.onRunnerLabel7967("M1", "SPIRAL", "MOONSHOT", false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", 1500.0)
        RunnerGrab7967.onRunnerLabel7967("M1", "SPIRAL", "SHITCOIN", false, "X", 1600.0)   // once per mint
        RunnerGrab7967.onRunnerLabel7967("M2", "WIN", "MOONSHOT", true, null, 450.0)
        val s = RunnerGrab7967.statusLine7967()
        assertTrue(s.contains("caught=1"))
        assertTrue(s.contains("missed=1"))
        assertTrue(s.contains("PLAYBOOK_NO_TRIGGER"))
        RunnerGrab7967.resetForTest7967()
    }

    @Test fun wired() {
        fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/engine/$rel").readText()
        assertTrue(src("chart/ChartReader7950.kt").contains("RunnerGrab7967.grab7967(mint, nowMs)"))
        assertTrue(src("Executor.kt").contains("RunnerGrab7967.deferSell7967(ts, reason)"))
        assertTrue(src("SpikeCapture7943.kt").contains("RunnerGrab7967.tiersFor7967(ts)"))
        assertTrue(src("SpikeCapture7943.kt").contains("RunnerGrab7967.heldExit7967(ts, px, nowMs, sell)"))
        assertTrue(src("truth/TraderSizingBridge6444.kt").contains("RunnerGrab7967.sizeMult7967(mintForSeal,"))
        assertTrue(src("truth/ForwardReturnLabeler7731.kt").contains("RunnerGrab7967.onRunnerLabel7967("))
        assertTrue(src("LiveCanonicalRecovery6686.kt").contains("MANUAL_HOLDING_LEFT_ALONE_7967"))
    }
}
