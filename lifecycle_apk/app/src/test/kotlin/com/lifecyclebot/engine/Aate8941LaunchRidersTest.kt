package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8941 — a launch rider (a pump.fun mint bought within 15 minutes of its chain birth) rides to 10x: its rungs below 10x
 * wait, its trail arms at 10x, its profit exits are held above the lane stop, its time exit passes at the hour. Stops and
 * every safety exit are unchanged; every other position keeps the doctrine.
 */
class Aate8941LaunchRidersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val pump = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmpump"
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"

    @Test fun aRiderIsAPumpFunMintBoughtInItsFirstFifteenMinutes() {
        assertTrue(RunnerDoctrine8584.launchRider8941(pump, 5L * 60_000L))
        assertTrue(RunnerDoctrine8584.launchRider8941(pump, 0L))
        assertFalse(RunnerDoctrine8584.launchRider8941(pump, 16L * 60_000L))
        assertFalse("an unresolved birth is not a rider", RunnerDoctrine8584.launchRider8941(pump, null))
        assertFalse("off pump.fun keeps the doctrine", RunnerDoctrine8584.launchRider8941(other, 60_000L))
    }

    @Test fun aRiderHoldsItsProfitExitsAboveTheStopAndItsStopsStillSell() {
        fun held(reason: String, pnl: Double, peak: Double, rider: Boolean, heldMin: Long = 10L) =
            RunnerDoctrine8584.holds8584("SHITCOIN", reason, pnl, peak, 25.0, rider8941 = rider, heldMs8941 = heldMin * 60_000L)
        assertFalse("the doctrine sells +15 % after a +60 % peak", held("TAKE_PROFIT", 15.0, 60.0, rider = false))
        assertTrue("a rider holds it", held("TAKE_PROFIT", 15.0, 60.0, rider = true))
        assertTrue("and holds a small loss above its stop", held("TAKE_PROFIT", -10.0, 60.0, rider = true))
        assertFalse("at the stop it sells", held("TAKE_PROFIT", -30.0, 60.0, rider = true))
        assertFalse("a stop always sells", held("STOP_LOSS", 15.0, 60.0, rider = true))
        assertFalse("a rug exit always sells", held("RUG_DETECTED", 15.0, 60.0, rider = true))
        assertTrue("a time exit inside the hour is held", held("MAX_HOLD_TIME", 40.0, 120.0, rider = true, heldMin = 30L))
        assertFalse("past the hour it passes", held("MAX_HOLD_TIME", 40.0, 120.0, rider = true, heldMin = 61L))
        assertFalse("a coin that never reached +50 % may still time out", held("MAX_HOLD_TIME", 10.0, 30.0, rider = true, heldMin = 30L))
        // from 10x the doctrine's own line again: 55 % of the peak multiple
        assertFalse(held("TAKE_PROFIT", 300.0, 1_000.0, rider = true))
        assertTrue(held("TAKE_PROFIT", 600.0, 1_000.0, rider = true))
    }

    @Test fun aRidersTrailArmsAtTenXAndItsRungsBelowTenXWait() {
        assertTrue("the doctrine trail sells 2x after a 6x peak", RunnerDoctrine8584.trailBreached8630("MOONSHOT", 100.0, 500.0, 25.0))
        assertFalse("a rider's trail is not armed under 10x",
            RunnerDoctrine8584.trailBreached8630("MOONSHOT", 100.0, 500.0, 25.0, armPct = RunnerDoctrine8584.LAUNCH_RIDE_FROM_PCT_8941))
        assertTrue(RunnerDoctrine8584.trailBreached8630("MOONSHOT", 300.0, 1_000.0, 25.0, armPct = RunnerDoctrine8584.LAUNCH_RIDE_FROM_PCT_8941))
        val floor = RunnerDoctrine8584.riderRungFloor8941(RunnerGrab7967.TIERS_7967)
        assertEquals("the 2x and 5x rungs wait", 2, floor)
        assertEquals("the first sale at 10x is the 10x rung's own quarter", 0.25,
            SpikeCapture7943.crossedTierFraction8079(RunnerGrab7967.TIERS_7967, floor, 3), 1e-12)
        val spike = src("engine/SpikeCapture7943.kt")
        assertTrue(spike.contains("if (reached <= riderFloor8941) return 0"))
        assertTrue(spike.contains("crossedTierFraction8079(tiers, maxOf(done, riderFloor8941), reached)"))
        assertTrue(src("engine/BotService.kt").contains("armPct = RunnerDoctrine8584.trailArm8941(ts))) return false"))
        assertTrue(src("engine/Executor.kt").contains("val rider8941 = RunnerDoctrine8584.launchRider8941(ts)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 129. Launch runners ride (8941)"))
    }
}
