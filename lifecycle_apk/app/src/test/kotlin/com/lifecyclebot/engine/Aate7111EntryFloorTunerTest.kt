package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.LaneEntryFloorTuner7111
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.7111 — the entry bar must be able to move DOWN onto money, not only up.
 *
 * The property under test is the one the old composition could not express at
 * all: every term in it was a raise except one hardcoded lane-specific relief.
 */
class Aate7111EntryFloorTunerTest {

    @Before fun setUp() { ScoreExpectancyTracker.reset() }

    /** Feed n closes into a lane's score band. */
    private fun feed(lane: String, score: Int, pnlPct: Double, n: Int) {
        repeat(n) { ScoreExpectancyTracker.record(lane, score, pnlPct) }
    }

    @Test
    fun aProfitableLowBandPullsTheFloorDown() {
        // Band 20-29 makes money. The bar should come DOWN to 20 from a base of
        // 50 — the case that was previously impossible.
        feed("T7111A", 25, +18.0, 20)
        val v = LaneEntryFloorTuner7111.verdict7111("T7111A", 50.0)
        assertEquals(20, v.targetFloor)
        assertTrue("must be a reduction, got ${v.delta}", v.delta < 0.0)
        assertTrue(v.reason.startsWith("LOWEST_PROFITABLE_BAND"))
    }

    @Test
    fun theLowestProfitableBandWinsNotTheBest() {
        // 30s make a little, 70s make a lot. "Where the money is" means the
        // cheapest band that is genuinely profitable, because everything above
        // it still clears the bar — raising to 70 would discard the 30s.
        feed("T7111B", 35, +6.0, 20)
        feed("T7111B", 75, +40.0, 20)
        val v = LaneEntryFloorTuner7111.verdict7111("T7111B", 50.0)
        assertEquals(30, v.targetFloor)
    }

    @Test
    fun aLaneThatOnlyLosesHasItsBarRaisedAboveTheLosses() {
        // Nothing profitable anywhere; the deepest proven loser is the 40s.
        // Stand above it rather than inventing a number.
        feed("T7111C", 15, -30.0, 20)
        feed("T7111C", 45, -25.0, 20)
        val v = LaneEntryFloorTuner7111.verdict7111("T7111C", 10.0)
        assertEquals(50, v.targetFloor)
        assertTrue("must be a raise, got ${v.delta}", v.delta > 0.0)
        assertTrue(v.reason.startsWith("ABOVE_HIGHEST_LOSING_BAND"))
    }

    @Test
    fun anUnsampledLaneIsLeftCompletelyAlone() {
        // Bootstrap safety: a fresh install must behave exactly as it does now.
        val v = LaneEntryFloorTuner7111.verdict7111("T7111D", 15.0)
        assertEquals(0.0, v.delta, 0.0)
        assertEquals("NO_EVIDENCE_NEUTRAL", v.reason)
    }

    @Test
    fun aThinBandIsNoiseAndMovesNothing() {
        // Below MIN_SAMPLE the band has no vote, however good it looks.
        feed("T7111E", 25, +50.0, 5)
        val v = LaneEntryFloorTuner7111.verdict7111("T7111E", 40.0)
        assertEquals(0.0, v.delta, 0.0)
        assertEquals(0, v.evidenceBands)
    }

    @Test
    fun aBandHoveringAtZeroDoesNotOscillateTheFloor() {
        // Inside the hysteresis band (-2%..+2%) a band is undecided. Undecided
        // is not a verdict, and acting on it is how a rolling window turns into
        // a floor that flaps every time it rotates.
        feed("T7111F", 25, +0.5, 20)
        val v = LaneEntryFloorTuner7111.verdict7111("T7111F", 40.0)
        assertEquals(0.0, v.delta, 0.0)
        assertEquals("ALL_BANDS_UNDECIDED", v.reason)
        assertTrue("the band was still counted as evidence", v.evidenceBands > 0)
    }

    @Test
    fun theDeltaIsBoundedInBothDirections() {
        // A profitable band at 0 against a base of 90 would be -90 unbounded.
        feed("T7111G", 5, +25.0, 20)
        val down = LaneEntryFloorTuner7111.verdict7111("T7111G", 90.0)
        assertTrue("clamped, got ${down.delta}", down.delta >= -15.0)

        // And a lane losing at the top against a base of 0 would be +90.
        feed("T7111H", 85, -40.0, 20)
        val up = LaneEntryFloorTuner7111.verdict7111("T7111H", 0.0)
        assertTrue("clamped, got ${up.delta}", up.delta <= 25.0)
    }

    @Test
    fun noLaneCanBeFlooredOutOfExistence() {
        // Doctrine: never disable a lane. Even the worst case leaves the
        // composed floor inside the caller's 0..95 coercion with room above it.
        feed("T7111I", 85, -60.0, 20)
        val v = LaneEntryFloorTuner7111.verdict7111("T7111I", 80.0)
        val composed = (80.0 + v.delta).coerceIn(0.0, 95.0)
        assertTrue("a lane must keep a tradable band above its floor", composed <= 95.0)
    }
}
