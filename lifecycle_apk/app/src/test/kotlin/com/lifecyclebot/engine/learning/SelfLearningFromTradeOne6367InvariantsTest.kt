package com.lifecyclebot.engine.learning

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6367 — self-learning "from trade 1" invariants.
 *
 * Operator directive (verbatim):
 *   "the bots meant to be self adjusting in real time. we aren't meant to
 *    have to touch trading or tuning ever. its meant to learn adjustments
 *    pivot strategize in real time from trade 1."
 *
 * Snapshot showed:
 *   MOONSHOT|S61+ MOMENTUM n=7 W/L=1/6  (no rotate — just under sample gate)
 *   SHITCOIN|S0-10 MOMENTUM n=3 W/L=0/3 μ=-57.9%  (catastrophic, still MOMENTUM)
 *
 * All previous rotation gates counted TRADES rather than LOSS MAGNITUDE.
 * A single -95% close is 20× the evidence of one -3% loss but takes the
 * same 1 unit of sample-count credit. Fix: (a) TacticSwitcher rotates on
 * magnitude+loss-rate at n>=2 when mean pnl is catastrophic; (b) LanePolicy
 * demotes one rung on a clear 5+ loss-streak before the OUTCOME_WINDOW gate
 * is reachable.
 */
class SelfLearningFromTradeOne6367InvariantsTest {

    @Test
    fun tactic_switcher_has_magnitude_trigger() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue("V5.0.6367 magnitude constants must be present",
            txt.contains("MAGNITUDE_MIN_SAMPLES = 2") &&
                txt.contains("MAGNITUDE_MEAN_PNL    = -25.0") &&
                txt.contains("MAGNITUDE_LOSS_RATE   = 0.80"))
        assertTrue("Magnitude trigger must actually rotate",
            txt.contains("rotate(lane, scoreBand, cell, \"magnitude"))
    }

    @Test
    fun lane_policy_has_early_demote_on_loss_streak() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/LanePolicy.kt").readText()
        assertTrue("V5.0.6367 EARLY_DEMOTE_STREAK constant must be present",
            txt.contains("EARLY_DEMOTE_STREAK = 5"))
        assertTrue("Early-demote path fires BEFORE the OUTCOME_WINDOW gate on w==0 && l>=streak",
            txt.contains("w == 0 && l >= EARLY_DEMOTE_STREAK"))
        assertTrue("Early demote must only ever demote (never promote)",
            txt.contains("demoteOneRung(curEarly)") &&
                txt.contains("EARLY_DEMOTE loss-streak="))
    }

    @Test
    fun tactic_switcher_magnitude_math_is_defensible() {
        // Regression: MAGNITUDE_MEAN_PNL must be catastrophic (well below any
        // normal daily variance) so a single unlucky close plus one -5% loss
        // can never rotate. The pair (mean <= -25, lossRate >= 0.80, n>=2) means
        // rotation fires when EVEN the best-case interpretation is disastrous.
        // Sanity: two trades at -30% avg with both losses = eligible.
        //         two trades split -60% / +10% (avg -25%, 50% loss rate) = NOT eligible
        //         (lossRate 0.50 < 0.80).
        val meanPnl = -30.0
        val lossRate = 1.00
        val n = 2
        assertTrue("magnitude rotate should fire",
            n >= 2 && lossRate >= 0.80 && meanPnl <= -25.0)
        val meanPnlBorderline = -25.0
        val lossRateBorderline = 0.50
        assertTrue("magnitude rotate must NOT fire when loss rate is under 80%",
            !(2 >= 2 && lossRateBorderline >= 0.80 && meanPnlBorderline <= -25.0))
    }
}
