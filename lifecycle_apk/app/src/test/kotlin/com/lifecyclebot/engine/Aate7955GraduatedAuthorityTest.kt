package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7955 — the Cortex earns power by degree; proven edge is expressed as size. */
class Aate7955GraduatedAuthorityTest {

    /** [n] outcomes alternating mean +/- sd. */
    private fun stat(n: Int, mean: Double, sd: Double): CortexLedger7885.Stat {
        val s = CortexLedger7885.Stat()
        repeat(n) { i -> s.add(mean + if (i % 2 == 0) sd else -sd, false) }
        return s
    }

    private val none = CortexLedger7885.Stat()

    private fun frac(strong: CortexLedger7885.Stat, neutral: CortexLedger7885.Stat = none, inverted: Boolean = false) =
        CortexScoreboard7885.authorityFraction7955(strong, neutral, inverted)

    // ── fraction curve ──

    @Test fun belowFifteenGradesThereIsNoAuthority() {
        assertEquals(0.0, frac(stat(CortexScoreboard7885.GRAD_MIN_N_7955 - 1, 30.0, 5.0)), 1e-12)
    }

    @Test fun todaysBarIsFullAuthority() {
        val proven = stat(50, 6.0, 3.0)
        assertTrue(CortexScoreboard7885.overruleProven(proven))
        assertEquals(1.0, frac(proven), 1e-12)
    }

    @Test fun theMoonshotRecordBelowFortyHoldsMostButNotAllAuthority() {
        // 5.0.7953: MOONSHOT STRONG n37 +23.4%: shadow under the cliff, now strong partial authority.
        val f = frac(stat(37, 23.4, 30.0))
        assertTrue(f >= 0.9)
        assertTrue(f <= CortexScoreboard7885.GRAD_PARTIAL_CAP_7955)
        assertFalse(f >= 1.0)
    }

    @Test fun smallSamplesNeedALargerEdge() {
        // n=20 (lower bound at 1 SE): +6% with sd 5 clears; +3.5% only partly; +2.5% hardly.
        val big = frac(stat(20, 6.0, 5.0))
        val mid = frac(stat(20, 3.5, 5.0))
        val small = frac(stat(20, 2.5, 5.0))
        assertEquals(0.6, big, 1e-9)
        assertTrue(mid > 0.0 && mid < big)
        assertTrue(small < mid)
        // The same edge on more grades earns more authority.
        assertTrue(frac(stat(30, 6.0, 5.0)) > big)
        // A negative or sub-margin edge earns nothing.
        assertEquals(0.0, frac(stat(30, 1.5, 1.0)), 1e-12)
        assertEquals(0.0, frac(stat(30, -4.0, 1.0)), 1e-12)
    }

    @Test fun aPositiveNeutralRecordRaisesTheBarAndANegativeOneDoesNot() {
        val strong = stat(20, 6.0, 5.0)
        assertEquals(0.0, frac(strong, stat(10, 5.0, 2.0)), 1e-12)
        assertEquals(frac(strong), frac(strong, stat(10, -5.0, 2.0)), 1e-12)
        // A thin NEUTRAL record is not a baseline.
        assertEquals(frac(strong), frac(strong, stat(4, 5.0, 2.0)), 1e-12)
    }

    @Test fun anInvertedLaneHoldsZero() {
        assertEquals(0.0, frac(stat(50, 6.0, 3.0), none, inverted = true), 1e-12)
        val board = CortexScoreboard7885()
        repeat(60) { i -> board.record("SHITCOIN", Bucket.STRONG, false, 5.0 + if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        repeat(30) { i -> board.record("SHITCOIN", Bucket.NEUTRAL, false, 15.0 + if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        assertTrue(board.inverted7948("SHITCOIN"))
        assertEquals(0.0, board.fractionFor7955("SHITCOIN"), 1e-12)
    }

    @Test fun theBooleanPowerIsFullAuthority() {
        // V5.0.8025 — evidence still grades the record (partial at 30, full at the old bar), but authority is
        // evidence x maturity over every STRONG grade ever: 70 grades is nowhere near "learnt".
        val board = CortexScoreboard7885()
        assertEquals(0.0, board.fractionFor7955("MOONSHOT"), 1e-12)
        repeat(30) { i -> board.record("MOONSHOT", Bucket.STRONG, false, 10.0 + if (i % 2 == 0) 2.0 else -2.0, 0.0) }
        val partial = board.evidenceFor8025("MOONSHOT")
        assertTrue(partial >= 0.5 && partial < 1.0)
        assertFalse(board.overruleAuthority("MOONSHOT"))
        repeat(40) { i -> board.record("MOONSHOT", Bucket.STRONG, false, 10.0 + if (i % 2 == 0) 2.0 else -2.0, 0.0) }
        assertEquals(1.0, board.evidenceFor8025("MOONSHOT"), 1e-12)
        assertEquals(70.0 / 1_070.0, board.fractionFor7955("MOONSHOT"), 1e-9)
        assertFalse(board.overruleAuthority("MOONSHOT"))
        // Unknown lane through the runtime: no authority, never a throw.
        assertEquals(0.0, Cortex7885.fractionFor7955("NO_SUCH_LANE_7955"), 1e-12)
    }

    // ── multiplier ──

    @Test fun sizeUpScalesWithAuthorityAndIsCapped() {
        // Kelly stake 2x the request: half authority -> 1.5x, full -> 2x.
        assertEquals(1.5, Cortex7885.graduatedMult7955(0.5, 0.10, 0.05), 1e-12)
        assertEquals(2.0, Cortex7885.graduatedMult7955(1.0, 0.10, 0.05), 1e-12)
        // Capped at 2.5x however large the Kelly stake or the fraction.
        assertEquals(2.5, Cortex7885.graduatedMult7955(1.0, 5.0, 0.05), 1e-12)
        assertEquals(2.5, Cortex7885.graduatedMult7955(3.0, 5.0, 0.05), 1e-12)
        // Never below the request.
        assertEquals(1.0, Cortex7885.graduatedMult7955(1.0, 0.01, 0.05), 1e-12)
        assertEquals(1.0, Cortex7885.graduatedMult7955(0.0, 5.0, 0.05), 1e-12)
        assertEquals(1.0, Cortex7885.graduatedMult7955(Double.NaN, 5.0, 0.05), 1e-12)
        assertEquals(1.0, Cortex7885.graduatedMult7955(0.8, 5.0, 0.0), 1e-12)
    }

    @Test fun sizeCompoundsWithEquity() {
        // Quarter-Kelly of +10% / sd 20% is 62.5% of equity: the stake grows with the wallet.
        val small = Cortex7885.kellyStakeSol(10.0, 400.0, 0.02)
        val large = Cortex7885.kellyStakeSol(10.0, 400.0, 0.03)
        assertEquals(1.2, Cortex7885.graduatedMult7955(0.8, small, 0.01), 1e-9)
        assertEquals(1.7, Cortex7885.graduatedMult7955(0.8, large, 0.01), 1e-9)
    }

    // ── playbook setups ──

    @Test fun aStrongSetupEarnsAuthorityFromFifteenLabels() {
        assertEquals(0.0, LanePlaybook7907.setupFraction7955(stat(14, 8.0, 6.0)), 1e-12)
        assertTrue(LanePlaybook7907.setupFraction7955(stat(15, 8.0, 6.0)) >= 0.5)
        // Positive but inside the small-sample margin: nothing until the full proof.
        assertEquals(0.0, LanePlaybook7907.setupFraction7955(stat(20, 1.5, 6.0)), 1e-12)
        // The old proof (30+, mean - SE > 0) is full authority.
        assertEquals(1.0, LanePlaybook7907.setupFraction7955(stat(30, 3.0, 6.0)), 1e-12)
        assertEquals(0.0, LanePlaybook7907.setupFraction7955(null), 1e-12)
        // Proven-losing keeps its own evidence bar.
        assertFalse(LanePlaybook7907.provenLosing(stat(20, -10.0, 2.0), runnerLane = false))
    }

    @Test fun setupAdmissionNeedsHalfAuthorityMoreLabelsThanTheCellAndNoProvenLoss() {
        val auth = doubleArrayOf(0.6, 20.0)
        assertTrue(LiveEdgeGate7877.setupAdmits7955(auth, 0, "CELL_EDGE_BELOW_MARGIN_1.0PCT"))
        assertFalse(LiveEdgeGate7877.setupAdmits7955(auth, 30, "CELL_EDGE_BELOW_MARGIN_1.0PCT"))
        assertFalse(LiveEdgeGate7877.setupAdmits7955(doubleArrayOf(0.4, 20.0), 0, "CELL_EDGE_BELOW_MARGIN_1.0PCT"))
        assertFalse(LiveEdgeGate7877.setupAdmits7955(auth, 0, "RUNNER_CELL_PROVEN_NEGATIVE_-9.0PCT"))
        assertFalse(LiveEdgeGate7877.setupAdmits7955(auth, 0, "RUNNER_COHORT_PROVEN_LOSING"))
        assertFalse(LiveEdgeGate7877.setupAdmits7955(null, 0, "CELL_EDGE_BELOW_MARGIN_1.0PCT"))
    }

    @Test fun softBlockKeepsHardSafetyAndRugOutOfReach() {
        assertFalse(Cortex7885.softBlock("RUG_PRONE_7930", false))
        assertFalse(Cortex7885.softBlock("C1_HARD_SAFETY", false))
        assertTrue(Cortex7885.softBlock("CELL_EDGE_BELOW_MARGIN_1.0PCT", false))
    }
}
