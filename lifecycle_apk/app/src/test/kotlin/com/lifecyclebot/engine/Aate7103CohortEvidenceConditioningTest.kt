package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.7103 — the oracle's cell evidence must be CONDITIONED, not averaged.
 *
 * The scenario is the one that makes an unconditional model useless: the same
 * lane and the same score band, strongly positive in one regime and strongly
 * negative in another. Pooled, that reports ~0 and the oracle calls it neutral
 * in both regimes. Conditioned, it reports the truth in each.
 */
class Aate7103CohortEvidenceConditioningTest {

    private val lane = "SNIPETEST7103"
    private val score = 50   // band S40

    @Before fun setUp() { ForwardOutcomeModel.reset() }
    @After fun tearDown() { ForwardOutcomeModel.reset() }

    private fun feed(regime: String, pnlPct: Double, count: Int, tag: String) {
        repeat(count) { i ->
            val mint = "mint7103$tag$i"
            ForwardOutcomeModel.stamp(mint, lane, score, "GOO", regime, "EARLY")
            ForwardOutcomeModel.recordOutcome(mint, pnlPct)
        }
    }

    @Test
    fun oppositeRegimesCancelWhenPooledAndSurviveWhenConditioned() {
        // MIN_SAMPLES is 10, so 12 per regime clears the bar on both sides.
        feed("BULL", 50.0, 12, "bull")
        feed("BEAR", -50.0, 12, "bear")

        // No regime passed — every existing caller's behaviour, unchanged.
        val pooled = ForwardOutcomeModel.cohortEvidence6911(lane, score)
        assertEquals(24L, pooled.samples)
        assertTrue(
            "pooling opposite regimes must average to roughly nothing, which is the defect: ${pooled.expectedPnlPct}",
            kotlin.math.abs(pooled.expectedPnlPct) < 1.0,
        )

        val bull = ForwardOutcomeModel.cohortEvidence6911(lane, score, "BULL")
        assertEquals("regime_mode", bull.level)
        assertEquals(12L, bull.samples)
        assertTrue("BULL cohort must report its own +50%, got ${bull.expectedPnlPct}", bull.expectedPnlPct > 45.0)
        assertTrue("BULL cohort is all wins", bull.pWin > 0.99)

        val bear = ForwardOutcomeModel.cohortEvidence6911(lane, score, "BEAR")
        assertEquals("regime_mode", bear.level)
        assertEquals(12L, bear.samples)
        assertTrue("BEAR cohort must report its own -50%, got ${bear.expectedPnlPct}", bear.expectedPnlPct < -45.0)
        assertTrue("BEAR cohort has no wins", bear.pWin < 0.01)
    }

    @Test
    fun aThinRegimeFallsBackInsteadOfStarving() {
        feed("BULL", 50.0, 12, "bull")
        feed("CHOP", 10.0, 3, "chop")   // below MIN_SAMPLES on its own

        // The thin regime must NOT return a 3-sample answer, and must not return
        // nothing either. It falls back to the next level up, which still
        // carries real evidence. This is the property that makes 7103 strictly a
        // refinement: no caller can end up with less evidence than before.
        val chop = ForwardOutcomeModel.cohortEvidence6911(lane, score, "CHOP")
        assertTrue("a thin regime must fall back, not answer from 3 samples", chop.level != "regime_mode")
        assertEquals(15L, chop.samples)
    }

    @Test
    fun anUnknownLaneReportsNoEvidenceRatherThanAPrior() {
        feed("BULL", 50.0, 12, "bull")
        val other = ForwardOutcomeModel.cohortEvidence6911("NOSUCHLANE7103", score, "BULL")
        assertEquals("none", other.level)
        assertEquals(0L, other.samples)
    }

    @Test
    fun laneMatchingIsBySegmentSoAShadowLaneCannotPoolIntoARealOne() {
        // The pre-7103 code matched on the substring "|LANE|BAND|" and its own
        // comment flagged the hazard: drop the leading pipe and "CORE|S40|" also
        // matches the read-only shadow key "P|V3_CORE|S40|...", pooling a shadow
        // lane's outcomes into a real lane's admission evidence. That code got
        // the pipe right; the point here is that the property no longer depends
        // on remembering to, because matching is now by segment. Locked so it
        // stays that way.
        feed("BULL", 50.0, 12, "bull")
        repeat(12) { i ->
            val mint = "mint7103shadow$i"
            ForwardOutcomeModel.stamp(mint, "V3_$lane", score, "GOO", "BULL", "EARLY")
            ForwardOutcomeModel.recordOutcome(mint, -90.0)
        }
        val real = ForwardOutcomeModel.cohortEvidence6911(lane, score, "BULL")
        assertEquals("the shadow lane must not be pooled in", 12L, real.samples)
        assertTrue("the real lane keeps its own expectancy", real.expectedPnlPct > 45.0)
    }
}
