package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9011 — the harvest module: a position's stage by its confirmed peak (seed under 2x, runner 2x-10x, moon 10x+); a
 * launch rider's doctrine line arms at a 2x peak (was 10x) while its rungs still wait for 10x; and a ledger of every live
 * position that reached +200 % — banked, share of peak, round trips, the exit that closed it.
 */
class Aate9011DegenHarvestTest {
    private val h = DegenHarvest9011
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aPositionsStageIsItsConfirmedPeak() {
        assertEquals(DegenHarvest9011.Stage9011.SEED, h.stage9011(Double.NaN))
        assertEquals(DegenHarvest9011.Stage9011.SEED, h.stage9011(99.9))
        assertEquals(DegenHarvest9011.Stage9011.RUNNER, h.stage9011(100.0))
        assertEquals(DegenHarvest9011.Stage9011.RUNNER, h.stage9011(899.0))
        assertEquals(DegenHarvest9011.Stage9011.MOON, h.stage9011(900.0))
        assertEquals(100.0, h.RUNNER_FROM_PCT_9011, 0.0); assertEquals(200.0, h.BIG_WIN_PCT_9011, 0.0); assertEquals(50.0, h.ROUND_TRIP_PCT_9011, 0.0)
    }

    @Test fun aRidersLineArmsAtTwoXAndItsRungsStillWaitForTenX() {
        val d = RunnerDoctrine8584
        // a 5x peak ridden back to 2x: the line (55 % of 5x = 2.75x) is breached once the arm is a 2x peak
        assertTrue(d.trailBreached8630("MOONSHOT", 100.0, 400.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        assertFalse("above the line it rides", d.trailBreached8630("MOONSHOT", 200.0, 400.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        assertFalse("a +80 % peak has not armed it", d.trailBreached8630("MOONSHOT", 10.0, 80.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        // a 2x peak keeps 1.5x; a 3x peak keeps 1.65x
        assertTrue(d.trailBreached8630("MOONSHOT", 49.0, 100.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        assertFalse(d.trailBreached8630("MOONSHOT", 51.0, 100.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        assertTrue(d.trailBreached8630("MOONSHOT", 64.0, 200.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        assertFalse(d.trailBreached8630("MOONSHOT", 66.0, 200.0, 25.0, armPct = h.RUNNER_FROM_PCT_9011))
        // the other profit exits: held above the stop below 2x, above the line from 2x
        fun held(pnl: Double, peak: Double) = d.holds8584("SHITCOIN", "TAKE_PROFIT", pnl, peak, 25.0, rider8941 = true, heldMs8941 = 600_000L)
        assertTrue(held(-10.0, 80.0)); assertTrue(held(130.0, 300.0)); assertFalse("a 4x peak keeps 2.2x", held(110.0, 300.0))
        assertFalse("a stop always sells", d.holds8584("SHITCOIN", "STOP_LOSS", 130.0, 300.0, 25.0, rider8941 = true))
        // rungs below 10x still wait
        assertEquals(2, d.riderRungFloor8941(RunnerGrab7967.TIERS_7967))
        assertEquals(900.0, d.LAUNCH_RIDE_FROM_PCT_8941, 0.0)
        val doc = src("engine/RunnerDoctrine8584.kt")
        assertTrue(doc.contains("if (launchRider8941(ts)) DegenHarvest9011.RUNNER_FROM_PCT_9011 else TRAIL_ARM_PEAK_PCT_8630"))
        assertTrue(doc.contains("peak < 1.0 + DegenHarvest9011.RUNNER_FROM_PCT_9011 / 100.0) {"))
    }

    @Test fun theLedgerBooksOnlyPositionsThatReachedTwoHundredPercent() {
        val b = DegenHarvest9011.Book9011()
        assertFalse(b.add(199.0, 120.0, "TAKE_PROFIT", false))
        assertFalse(b.add(Double.NaN, 120.0, "TAKE_PROFIT", false))
        assertEquals("reached+200%=0", b.line())
        assertTrue(b.add(417.0, 80.8, "SPIKE_CAPTURE_7943_T2_417PCT", true))
        assertTrue(b.add(300.0, 20.0, "RAPID_TRAILING_STOP", false))          // a round trip
        assertTrue(b.add(1_200.0, 640.0, "RUNNER_DOCTRINE_TRAIL_8630", true))
        assertTrue(b.add(250.0, 110.0, "SPIKE_CAPTURE_7943_T1_115PCT", false))
        assertEquals(4L, b.reached); assertEquals(1L, b.kept); assertEquals(1L, b.roundTrips); assertEquals(2L, b.riders)
        assertEquals(2L, b.byExit[DegenHarvest9011.family9011("SPIKE_CAPTURE_7943_T2_417PCT")]!![0])
        val line = b.line()
        assertTrue(line.contains("reached+200%=4 kept>=+200%=1 roundTrips(<+50%)=1 riders=2"))
        assertTrue(line.contains("RAPID_TRAILING_STOP=1/rt1"))
        // share of peak: 80.8/417, 20/300, 640/1200, 110/250
        assertEquals((80.8 / 417 + 20.0 / 300 + 640.0 / 1200 + 110.0 / 250), b.sumShare, 1e-9)
        val r = DegenHarvest9011.Book9011().also { it.decode(b.encode()) }
        assertEquals(b.line(), r.line())
    }

    @Test fun anExitFamilyDropsItsFigures() {
        assertEquals(h.family9011("SPIKE_CAPTURE_7943_T1_115PCT"), h.family9011("SPIKE_CAPTURE_7943_T2_417PCT"))
        assertEquals("RUNNER_DOCTRINE_TRAIL", h.family9011("RUNNER_DOCTRINE_TRAIL_8630"))
        assertEquals("MANUAL", h.family9011(" manual "))
        assertEquals("UNNAMED", h.family9011("")); assertEquals("UNNAMED", h.family9011(null)); assertEquals("UNNAMED", h.family9011("-20%"))
    }

    @Test fun itIsWiredToTheCloseTheSellDoorAndTheReport() {
        // superseded by V5.0.9012 — the close also carries how long the position was held
        assertTrue(src("engine/truth/RunnerCapture8017.kt").contains("DegenHarvest9011.onClose9011(env.mode, env.terminal, env.positionId, env.exitReason, env.realizedReturnPct, env.mfePct, env.holdingTimeMs, env.tradeId, env.atMs)"))   // superseded by V5.0.9025 — the trade id and close time ride too
        assertTrue(src("engine/Executor.kt").contains("DegenHarvest9011.noteRider9011(ts.position.positionId, rider8941)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("DegenHarvest9011.statusLine9011()"))
        assertTrue(h.statusLine9011().startsWith("degenHarvest9011["))
        // a paper close, a non-terminal close and a small win book nothing
        h.onClose9011("PAPER", true, "p1", "TAKE_PROFIT", 300.0, 400.0)
        h.onClose9011("LIVE", false, "p2", "TAKE_PROFIT", 300.0, 400.0)
        h.onClose9011("LIVE", true, "p3", "TAKE_PROFIT", 40.0, 120.0)
        h.noteRider9011("", true); h.noteRider9011("p4", false)
    }
}
