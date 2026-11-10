package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.LaneSweep8555
import com.lifecyclebot.v3.scoring.DipHunterAI
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinExpress
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8555 — owner: "run a set up and strategy audit and development sweep on the meme trader per lane". Evidence (8550 log,
 * 559-decision export): BASE was the only positive stage (+18.7..+28.4 %), BREAKOUT / PEAK lost 12-22 %, buys within 8 % of
 * the peak were the worst bucket, 5 of 6 MOONSHOT live closes bought the top, and several lane rules contradicted their own
 * gates. What tightens applies in both modes; what loosens is PAPER-only until proven (n>=30, mean-se>0, PF>=1.3).
 */
class Aate8555LaneSweepTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // superseded by V5.0.8603 — chaseSetupsDoNotFireAtTheHigh pinned the CHASE_AT_HIGH_8555 setup guard, which is deleted:
    // on 18,735 labelled decisions the coins it refused netted -1.0 % (launch lanes) vs -5.6 % for the coins it passed.
    @Test fun theInvertedChaseGuardIsGoneAndTheDataBackedPeakGuardStays() {
        val p = src("engine/cortex/LanePlaybook7907.kt")
        assertFalse("the 8555 setup chase guard is deleted", p.contains("fun chaseRefused8555("))
        assertFalse(p.contains("-> \"CHASE_AT_HIGH_8555\""))
        assertTrue("the peak band still refuses the unambiguous top", p.contains("timingBand8094(f.runup, f.pxPeak) == \"PEAK\" -> \"PEAK_BAND_CHASE_8586\""))
    }

    @Test fun contradictionsAndChasesAreRetiredNotDeleted() {
        assertTrue(LanePlaybook7907.retired8555("PROJECT_SNIPER", "POST_MIGRATION_HOLD"))
        assertTrue(LanePlaybook7907.retired8555("DIP_HUNTER", "FIRST_DIP_BOUGHT"))
        assertTrue(LanePlaybook7907.retired8555("TREASURY", "YOUNG_LIQUID_8383"))
        assertTrue(LanePlaybook7907.retired8555("MOONSHOT", "BASE_BREAKOUT_FLOW"))
        assertFalse(LanePlaybook7907.retired8555("SHITCOIN", "PLAN_PULLBACK_RECLAIM"))
        val p = src("engine/cortex/LanePlaybook7907.kt")
        assertFalse("superseded: retirement skipped whenever the native brain owned selection (every lane)",
            p.contains("val fired = if (Cortex7885.nativeOwnsSelection8506(lane)) fired0 else fired0.filter { !retired8383(lane, it) }"))
        assertTrue(LanePlaybook7907.sweepLine8555().startsWith("laneSweep8555{"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("LanePlaybook7907.sweepLine8555()"))
    }

    @Test fun moonshotDoesNotBuyItsHigh() {
        assertTrue(MoonshotTraderAI.nearHighRefused8555(0.95, 80.0))
        assertFalse("a base at its high stays open", MoonshotTraderAI.nearHighRefused8555(0.97, 30.0))
        assertFalse(MoonshotTraderAI.nearHighRefused8555(Double.NaN, 80.0))
        val m = src("v3/scoring/MoonshotTraderAI.kt")
        assertFalse(m.contains("buyPressurePct > 75 -> 18"))
        assertTrue(m.contains("\"PRE_IGNITION\", \"IGNITION\", \"EXPANDING\" -> 0"))
    }

    @Test fun launchLanesStopChasing() {
        // superseded by V5.0.8605 — EXPRESS's chaseExtended8555 is deleted (refused -0.1 % / 3.7 % runners vs allowed -2.7 % / 1.6 %).
        assertFalse(java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText().contains("fun chaseExtended8555("))
        assertTrue(ManipulatedTraderAI.manipulationEntry8555(45.0, 2.0))
        assertFalse("the spike route is gone", ManipulatedTraderAI.manipulationEntry8555(10.0, 25.0))
        assertFalse("a bundle already spiking is not entered", ManipulatedTraderAI.manipulationEntry8555(45.0, 12.0))
        assertEquals(-10, ShitCoinTraderAI.momentumScore8555(25.0))
        assertEquals(0, ShitCoinTraderAI.momentumScore8555(12.0))
        assertEquals(5, ShitCoinTraderAI.momentumScore8555(4.0))
        assertEquals("A shallow pullback scores like flat", 3, ShitCoinTraderAI.momentumScore8555(-3.0))
        assertEquals("PAPER scores a held pullback like flat", 3, ShitCoinTraderAI.momentumScore8555(-3.0))
        assertEquals(-15, ShitCoinTraderAI.momentumScore8555(-9.0))
    }

    @Test fun paperExitsDoNotBankNetLosses() {
        assertEquals(30L to -3.5, ShitCoinTraderAI.neverGreenCut8555())
        assertEquals(-15, ShitCoinTraderAI.momentumScore8555(-6.01))
        assertFalse(LaneSweep8555.profitExitAllowed(0.5))
        assertFalse(LaneSweep8555.profitExitAllowed(2.0))
        assertTrue(LaneSweep8555.profitExitAllowed(4.5))
        assertTrue(src("v3/scoring/ShitCoinExpress.kt").contains("LaneSweep8555.profitExitAllowed(pnlPct)"))
        assertTrue(src("v3/scoring/ProjectSniperAI.kt").contains("LaneSweep8555.profitExitAllowed(pnlPct)"))
        assertTrue(src("engine/CyclicTradeEngine.kt").contains("paper8555 && positionHighWaterPnlPct >= 8.0 && positionHighWaterPnlPct < 15.0 -> lockMin8555"))
    }

    @Test fun establishedLanesTradeEstablishedCoins() {
        val q = src("v3/scoring/QualityTraderAI.kt")
        assertTrue(q.contains("internal const val MIN_LIQUIDITY_USD = 25_000.0"))
        assertTrue(q.contains("internal const val MIN_AGE_MINUTES_BOOTSTRAP = 120"))
        assertTrue(src("v3/scoring/BlueChipTraderAI.kt").contains("internal const val BC_LIQ_BOOTSTRAP = MIN_LIQUIDITY_USD"))
        val c = src("v3/scoring/CashGenerationAI.kt")
        assertTrue(c.contains("val learnedTreasuryMinLiq = com.lifecyclebot.engine.TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY"))
        assertTrue(c.contains("private const val WIN_STREAK_SCALE = 0.0"))
        assertEquals(24.0, com.lifecyclebot.engine.cortex.LaneCheatSheet8323.CYCLIC_MIN_AGE_HOURS_8323, 0.0)
    }

    @Test fun theDipLaneJudgesStaticGatesBeforeWaiting() {
        assertEquals("DIP_STATIC_MCAP_LOW_8555", DipHunterAI.staticRefusal8555(20_000.0, 30_000.0, 5.0))
        assertEquals("DIP_STATIC_LIQ_RATIO_8555", DipHunterAI.staticRefusal8555(1_000_000.0, 20_000.0, 5.0))
        assertEquals("DIP_STATIC_TOO_YOUNG_8555", DipHunterAI.staticRefusal8555(500_000.0, 100_000.0, 0.5))
        assertNull(DipHunterAI.staticRefusal8555(500_000.0, 100_000.0, 5.0))
        assertNull("unread values are not judged here", DipHunterAI.staticRefusal8555(0.0, 0.0, Double.NaN))
        val b = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.indexOf("DipHunterAI.staticRefusal8555(") in 0 until b.indexOf("DipHistory8535.State.WAIT) {"))
        assertFalse(src("engine/cortex/LaneCheatSheet8323.kt").contains("listOf(\"RAYDIUM\", \"NEW_POOL\") to listOf(\"MOONSHOT\", \"SHITCOIN\", \"MANIPULATED\", \"DIP_HUNTER\")"))
    }
}
