package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.F
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7924 — a wide setup library per lane; each setup fires only on its own measured conditions. */
class Aate7924PlaybookLibraryTest {
    private val n = Double.NaN
    private fun base(age: Double = n, dd: Double = n, bp: Double = n, chg5m: Double = n, chg1h: Double = n, liq: Double = n,
                     pxPeak: Double = n) =
        F(age, n, pxPeak, dd, bp, liq, n, n, chg5m, chg1h, n, n, "", "")

    private fun fires(lane: String, f: F, id: String) = LanePlaybook7907.matches(lane, f)!!.any { it.id == id }

    @Test fun everyLaneHasAWidePlaybook() {
        for (lane in listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "QUALITY", "BLUECHIP", "DIP_HUNTER",
            "TREASURY", "CASHGEN", "CYCLIC", "CORE")) {
            assertTrue(lane, LanePlaybook7907.menuIds(lane).size >= 9)
        }
        assertTrue(LanePlaybook7907.menuIds("SHITCOIN").size >= 15)
    }

    @Test fun launchTapeSetupsReadTheTape() {
        val b = base(age = 4.0, bp = 60.0)
        val hot = F(b.age, n, n, n, 60.0, n, n, n, n, n, n, n, "", "",
            tapeCrowd = 45.0, tapeNetSol = 12.0, tapeBuyersPerMin = 11.0, tapeBuyShare = 70.0, tapeLargest = 12.0,
            tapeTop3 = 30.0, tapeDevSold = 0.0, tapeFromPeak = -10.0)
        assertTrue(fires("SHITCOIN", hot, "FAST_CROWD"))
        assertTrue(fires("SHITCOIN", hot, "BROAD_DISTRIBUTION"))
        assertTrue(fires("SHITCOIN", hot, "NET_INFLOW_SURGE"))
        val devSold = F(4.0, n, n, n, 60.0, n, n, n, n, n, n, n, "", "",
            tapeCrowd = 45.0, tapeBuyersPerMin = 11.0, tapeBuyShare = 70.0, tapeLargest = 12.0, tapeTop3 = 30.0, tapeDevSold = 1.0)
        assertFalse(fires("SHITCOIN", devSold, "FAST_CROWD"))
        // Unknown tape fires nothing on the tape.
        assertFalse(fires("SHITCOIN", b, "FAST_CROWD"))
    }

    /** V5.0.7926 — a launch cell the selector has proven is a setup on the launch lanes. */
    @Test fun provenLaunchLadderIsASetup() {
        val proven = F(n, n, n, n, n, n, n, n, n, n, n, n, "", "", launchLadderProven = true)
        for (lane in listOf("SHITCOIN", "MOONSHOT", "PROJECT_SNIPER", "EXPRESS")) assertTrue(lane, fires(lane, proven, "LAUNCH_LADDER_PROVEN"))
        assertFalse(fires("SHITCOIN", F(n, n, n, n, n, n, n, n, n, n, n, n, "", ""), "LAUNCH_LADDER_PROVEN"))
    }

    @Test fun structureSetupsNeedTheirConditions() {
        assertTrue(fires("BLUECHIP", base(dd = 35.0, chg5m = 2.0, chg1h = -20.0, liq = 80_000.0), "MEAN_REVERSION_OVERSOLD"))
        assertFalse(fires("BLUECHIP", base(dd = 35.0, chg5m = -2.0, chg1h = -20.0, liq = 80_000.0), "MEAN_REVERSION_OVERSOLD"))
        assertTrue(fires("QUALITY", base(chg1h = 15.0, chg5m = -3.0, bp = 55.0), "MOMENTUM_PULLBACK_5M"))
        val opp = F(n, n, n, n, n, n, n, n, n, n, n, n, "", "", oppSetup = "EARLY_MOMENTUM_IGNITION", oppPercentile = 0.8)
        assertTrue(fires("MOONSHOT", opp, "OPP_EARLY_IGNITION"))
    }
}
