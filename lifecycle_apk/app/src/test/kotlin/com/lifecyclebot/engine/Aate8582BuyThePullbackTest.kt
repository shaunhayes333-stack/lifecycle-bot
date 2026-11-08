package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8582 §BUY_THE_PULLBACK — 8577 edge report, meme lanes: at the high n119 -26.9 % PF 0.02; up >= 10 % in 5 min PF ~0.1;
 * buy pressure >= 60 % PF <= 0.03; 10-25 % off the peak n63 +12.5 % PF 1.81; buy pressure < 40 % n115 +9.4 % PF 1.72.
 */
class Aate8582BuyThePullbackTest {
    @Test fun a_meme_lane_never_buys_the_high_but_buys_the_pullback() {
        assertTrue(LaneAuthority8577.memeBuysTheHigh8582("SHITCOIN", 0.99, 40.0, 55.0))     // at the high after a run
        // V5.0.8603 — the "spiking" leg is removed: a launch up 12 % in 5 min is not refused (it refused the runners: 3.1 %
        // went +100 % vs 1.7 % of the coins it passed, 18,735 labelled decisions).
        assertFalse(LaneAuthority8577.memeBuysTheHigh8582("MOONSHOT", 0.85, 20.0, 50.0))
        assertFalse("a breakout can have strong buyers without being a chase",
            LaneAuthority8577.memeBuysTheHigh8582("EXPRESS", 0.85, 20.0, 72.0))
        assertTrue("an actual price peak still binds with strong buyers",
            LaneAuthority8577.memeBuysTheHigh8582("EXPRESS", 0.99, 20.0, 72.0))
        assertFalse(LaneAuthority8577.memeBuysTheHigh8582("SHITCOIN", 0.82, 40.0, 35.0))   // 18 % off the peak, sellers in control
        assertFalse(LaneAuthority8577.memeBuysTheHigh8582("SHITCOIN", 1.0, 0.0, 50.0))      // flat, never ran: not "the high"
        assertFalse(LaneAuthority8577.memeBuysTheHigh8582("SHITCOIN", Double.NaN, Double.NaN, Double.NaN))  // unknown never refuses
        assertFalse(LaneAuthority8577.memeBuysTheHigh8582("TREASURY", 0.99, 40.0, 80.0))   // not a meme lane
    }

    @Test fun it_binds_on_a_live_admit_and_leaves_paper_alone() {
        val c = Candidate8136(mint = "M8582", lane = "SHITCOIN", selectedSetup = "X", costPct = 2.0, live = true,
            nativeQualified8213 = true, nativeSetup8213 = "X", genericScore = 40.0, genericFloor = 15.0)
        val admit = Decision8136(Terminal8136.ADMIT, "NATIVE_QUALIFIED_ADMIT_8259", false, "X", 5.0, emptyList())
        val r = LaneAuthority8577.apply8577(c, admit, LaneAuthority8577.MEME_BUYS_THE_HIGH_8582)
        assertEquals(Terminal8136.REFUSE, r.terminal); assertEquals(LaneAuthority8577.MEME_BUYS_THE_HIGH_8582, r.reason)
        assertSame(admit, LaneAuthority8577.apply8577(c.copy(live = false), admit, LaneAuthority8577.MEME_BUYS_THE_HIGH_8582))
        // V5.0.8604 — the other chase / high doors stay advisory (their refused records were thin and not negative).
        assertFalse(LaneAuthority8577.binding8577("LIVE_INTO_HIGH_UNPROVEN_8545"))
        assertFalse(LaneAuthority8577.binding8577("CHASING_SPIKE_8065"))
        assertFalse("V5.0.8603 — the deleted 8555 chase guard no longer binds", LaneAuthority8577.binding8577("CHASE_AT_HIGH_8555"))
        assertTrue("hard safety still binds", LaneAuthority8577.binding8577("RUG_PREFILTER_HARD_FAIL"))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        // V5.0.8626 — the read is back in the live admission (paper's profit was stale lost marks), with the coin's stage.
        assertTrue(adm.contains("if (candidate.live) LaneAuthority8577.memeEntryRefusal8626(ts, candidate.lane, candidate.stage, nowMs) else null"))
        assertTrue(LaneAuthority8577.binding8577(LaneAuthority8577.MEME_BUYS_THE_HIGH_8582))
        assertTrue(LaneAuthority8577.binding8577(LaneAuthority8577.LIVE_STAGE_LOSES_8626))
    }

    @Test fun liveDoesNotBuyAtThePeakOrInTheDumpUntilTheLaneProvesItThere() {
        assertTrue("14:12 SHITCOIN PEAK n23 -52.1", LaneAuthority8577.stageLoses8626("PEAK_EXHAUSTION", 23, -52.1, 10.0))
        assertTrue("14:12 EXPRESS DUMP n56 -39.5", LaneAuthority8577.stageLoses8626("DUMPING", 56, -39.5, 5.0))
        assertTrue("a thin positive record has not turned", LaneAuthority8577.stageLoses8626("DUMPING", 12, 20.0, 5.0))
        assertFalse("a lane that proves the stage pays may buy it", LaneAuthority8577.stageLoses8626("DUMPING", 40, 12.0, 4.0))
        assertFalse(LaneAuthority8577.stageLoses8626("FRESH_LAUNCH", 50, -10.0, 2.0))
        assertFalse(LaneAuthority8577.stageLoses8626("CONTROLLED_MARKUP", 50, -10.0, 2.0))
        assertFalse("unknown stage never refuses", LaneAuthority8577.stageLoses8626(null, 0, Double.NaN, Double.NaN))
        assertFalse(LaneAuthority8577.stageLoses8626("", 0, Double.NaN, Double.NaN))
    }
}
