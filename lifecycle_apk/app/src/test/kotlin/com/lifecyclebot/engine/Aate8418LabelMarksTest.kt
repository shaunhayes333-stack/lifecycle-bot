package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexLabelFeed8291
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.KeylessMarks8359
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 §EVERY_LABEL_GETS_ITS_MARK — 5.0.8416 (3.7 h live): Forward labels observed=8349 booked60=1532 lostMark=6669,
 * offWatch priced=2700 while the DexScreener pair lane ran ~20 of its 120 calls a minute; every lane SUSPENDED. A due,
 * unpriced forward observation now rides the pair lane (after held and shadow mints) and the keyless curve read; a lane
 * answer is the labeler's off-watch mark; a measured fit with no score spread is INSUFFICIENT, not UNCALIBRATED.
 */
class Aate8418LabelMarksTest {
    private val h1 = 60_000L
    private val h5 = 5L * 60_000L
    private val h60 = 60L * 60_000L
    private val horizons = longArrayOf(h1, h5, h60, -1L)
    private val graces = longArrayOf(
        ForwardReturnLabeler7731.graceFor7946(h1), ForwardReturnLabeler7731.graceFor7946(h5), ForwardReturnLabeler7731.graceFor7946(h60), 0L)
    private val lead = CortexLabelFeed8291.LABEL_LEAD_MS_8418

    private fun end(ageMs: Long, first: Boolean = false, hs: LongArray = horizons): Long =
        CortexLabelFeed8291.windowEnd8418(1_000_000L, ageMs, first, 60_000L, hs, graces, lead)

    @Test fun anUnpricedObservationIsDueOnlyInsideOrJustBeforeItsWindow() {
        // re-base window: due until 60 s after the decision
        assertEquals(1_000_000L + 60_000L, end(10_000L, first = true))
        // 1-minute horizon: from 30 s before it to its 60 s grace
        assertEquals(1_000_000L + h1 + 60_000L, end(35_000L))
        assertEquals(1_000_000L + h1 + 60_000L, end(h1 + 59_000L))
        // between windows nothing is due (2:01 .. 4:29)
        assertEquals(0L, end(h1 + 61_000L))
        assertEquals(0L, end(h5 - lead - 1L))
        // 5-minute read: 4:30 .. 7:30 (the Field-Manual grace is half the horizon; nothing widens it)
        assertEquals(1_000_000L + h5 + 150_000L, end(h5 - lead))
        assertEquals(1_000_000L + h5 + 150_000L, end(h5 + 150_000L))
        assertEquals(0L, end(h5 + 150_001L))
        // the hour: 59:30 .. 70:00
        assertEquals(1_000_000L + h60 + 600_000L, end(h60 + 300_000L))
        // a closed horizon is never due
        assertEquals(0L, end(h5, hs = longArrayOf(h1, -1L, h60, -1L)))
        // two overlapping windows: the one closing soonest wins
        assertEquals(1_000_000L + 100_000L, CortexLabelFeed8291.windowEnd8418(1_000_000L, 90_000L, false, 60_000L,
            longArrayOf(80_000L, 95_000L), longArrayOf(20_000L, 60_000L), lead))
    }

    @Test fun labelMintsRideAfterHeldAndShadowMints() {
        assertEquals(listOf("H1", "S1", "S2", "L1"), KeylessMarks8359.priorityOrder8418(listOf("H1"), listOf("S1", "H1", "S2"), listOf("S1", "L1", "L2"), 4))
        assertEquals(listOf("H1", "S1"), KeylessMarks8359.priorityOrder8418(listOf("H1"), listOf("S1"), listOf("L1"), 2))
        KeylessMarks8359.resetForTests8359()
        val now = System.currentTimeMillis()
        val shadow = "Shadow111111111111111111111111111111pump"
        val label = "Label1111111111111111111111111111111pump"
        KeylessMarks8359.putWantedForTests8359(shadow, now + 60_000L)
        KeylessMarks8359.putLabelWantedForTests8418(label, now + 60_000L)
        KeylessMarks8359.putLabelWantedForTests8418("Closed11111111111111111111111111111pump", now - 1L)
        assertTrue(KeylessMarks8359.isLabelWanted8418(label, now))
        assertFalse(KeylessMarks8359.isLabelWanted8418(shadow, now))
        assertEquals(listOf(label), KeylessMarks8359.labelMints8418(now))
        // the curve read carries held, then shadow, then label curves
        assertEquals(listOf("HeldMint", shadow, label), KeylessMarks8359.withWantedCurves8359(listOf("HeldMint"), 40))
        KeylessMarks8359.resetForTests8359()
    }

    @Test fun onlyStaleUnaskedLabelMintsJoinTheLaneMostUrgentFirst() {
        val now = 10_000_000L
        val marks = mapOf("FRESH" to now - 5_000L, "OLD" to now - 60_000L)
        val asked = mapOf("ASKED" to now - 3_000L, "ASKED_LONG_AGO" to now - 40_000L)
        val ask = KeylessMarks8359.labelLaneAsk8418(listOf("SHADOW", "FRESH", "OLD", "ASKED", "ASKED_LONG_AGO", "NEVER", "EXTRA"),
            setOf("SHADOW"), { marks[it] ?: 0L }, { asked[it] ?: 0L }, now, 20_000L, 3)
        assertEquals(listOf("OLD", "ASKED_LONG_AGO", "NEVER"), ask)
    }

    @Test fun aLaneAnswerBecomesTheLabelersObservedMarkAndNothingIsInvented() {
        KeylessMarks8359.resetForTests8359()
        val now = System.currentTimeMillis()
        val label = "LaneLabel11111111111111111111111111pump"
        val other = "NotLabel111111111111111111111111111pump"
        KeylessMarks8359.putLabelWantedForTests8418(label, now + 120_000L)
        KeylessMarks8359.onLaneAnswer8418(listOf(label, other), mapOf(label to 0.0042, other to 0.5))
        val got = ForwardReturnLabeler7731.observedMark8372(label, System.currentTimeMillis())
        assertNotNull(got)
        assertEquals(0.0042, got!!.first, 0.0)
        assertNull("a mint no label wants is not stored", ForwardReturnLabeler7731.observedMark8372(other, System.currentTimeMillis()))
        assertEquals(1L, KeylessMarks8359.labelCounts8418()[2])
        // an unpriced / invalid answer leaves no mark; an older answer never replaces a newer one
        val unpriced = "Unpriced11111111111111111111111111pump"
        KeylessMarks8359.putLabelWantedForTests8418(unpriced, now + 120_000L)
        KeylessMarks8359.onLaneAnswer8418(listOf(unpriced), mapOf(unpriced to Double.NaN))
        assertNull(ForwardReturnLabeler7731.observedMark8372(unpriced, System.currentTimeMillis()))
        assertFalse(ForwardReturnLabeler7731.noteLaneMark8418(label, 0.9, got.second - 1L))
        assertFalse(ForwardReturnLabeler7731.noteLaneMark8418(label, -1.0, got.second + 1L))
        assertEquals(0.0042, ForwardReturnLabeler7731.observedMark8372(label, System.currentTimeMillis())!!.first, 0.0)
        KeylessMarks8359.resetForTests8359()
    }

    @Test fun aLaneAskedLabelMintIsNotAskedTwiceOnTheSameHost() {
        KeylessMarks8359.resetForTests8359()
        val now = System.currentTimeMillis()
        val label = "Covered111111111111111111111111111pump"
        KeylessMarks8359.putLabelWantedForTests8418(label, now + 60_000L)
        assertFalse("not yet asked of the lane", KeylessMarks8359.laneCovered8418(label, now))
        assertFalse(KeylessMarks8359.laneCovered8418("Unwanted1111111111111111111111111pump", now))
        KeylessMarks8359.resetForTests8359()
    }

    @Test fun markFamiliesAndLostClassesAreReportedHonestly() {
        assertEquals("CURVE", CortexLabelFeed8291.markFamily8418("CURVE_ACCOUNT"))
        assertEquals("CURVE", CortexLabelFeed8291.markFamily8418("PUMP_CURVE_RPC_KEYLESS_8359"))
        assertEquals("TAPE", CortexLabelFeed8291.markFamily8418("CURVE_FRAME"))
        assertEquals("DEX", CortexLabelFeed8291.markFamily8418("OFFWATCH"))
        assertEquals("DEX", CortexLabelFeed8291.markFamily8418(null))
        assertFalse(CortexLabelFeed8291.lostLate8418(markAfterWindow = false, everAccepted = false, anyMarkSeen = false))
        assertTrue(CortexLabelFeed8291.lostLate8418(markAfterWindow = true, everAccepted = false, anyMarkSeen = false))
        assertTrue(CortexLabelFeed8291.lostLate8418(markAfterWindow = false, everAccepted = true, anyMarkSeen = true))
        val line = CortexLabelFeed8291.labelMarksLine8418(10, 4, 3, 1, 1, 1, longArrayOf(20, 18, 9, 2, 5))
        assertEquals("labelMarks8418[due=10, priced{dex=4,curve=3,tape=1}, lost{noSource=1,late=1}, " +
            "route{noted=20,laneQueued=18,laneMarks=9,curvePasses=2,curveMarks=5}]", line)
        assertTrue(CortexLabelFeed8291.labelMarksLine8418(0, 0, 0, 0, 0, 0, null).contains("route{noted=0,"))
    }

    @Test fun aFitWithoutScoreSpreadIsInsufficientNotUncalibrated() {
        val oneScore = CortexCalibration7901()
        val inverted = CortexCalibration7901()
        val good = CortexCalibration7901()
        repeat(CortexCalibration7901.ISO_MIN_N_8366 + 20) { i ->
            // every pair carries the same score: no rank question can be asked of it
            oneScore.learn("CORE", 5.0, 0.0, if (i % 2 == 0) -30.0 else 25.0, 5.0)
            inverted.learn("CORE", i.toDouble(), 0.0, 40.0 - i, i.toDouble())
            good.learn("CORE", i.toDouble(), 0.0, i - 20.0, i.toDouble())
        }
        assertEquals("INSUFFICIENT_SPREAD", oneScore.rankState8418("CORE"))
        assertFalse(oneScore.netFit8372("CORE")!!.rankable8418)
        assertFalse(oneScore.uncalibrated8372("CORE"))
        assertFalse("no spread in the deviations either: the slope rule cannot suspend it", oneScore.suspends8372("CORE", 0.5))
        assertTrue(oneScore.insufficient8418("CORE"))
        assertEquals("UNCALIBRATED", inverted.rankState8418("CORE"))
        assertTrue(inverted.uncalibrated8372("CORE"))
        assertTrue(inverted.suspends8372("CORE", 0.5))
        assertFalse(inverted.insufficient8418("CORE"))
        assertEquals("CALIBRATED", good.rankState8418("CORE"))
        assertFalse(good.suspends8372("CORE", 0.5))
        // two score values are enough to ask the question
        val two = CortexCalibration7901()
        repeat(40) {
            two.learn("CORE", 0.0, 0.0, 2.0, 0.0)
            two.learn("CORE", 1.0, 0.0, 1.0, 1.0)
        }
        assertEquals("UNCALIBRATED", two.rankState8418("CORE"))
        assertTrue(two.netFit8372("CORE")!!.rankable8418)
    }

    @Test fun tooFewPairsIsInsufficientAndAZeroSlopeIsNeverReadFromNoPairs() {
        val none = CortexCalibration7901()
        assertEquals("INSUFFICIENT_PAIRS", none.rankState8418("CORE"))
        assertEquals(1.0, none.slope("CORE"), 0.0)
        assertFalse(none.measured8291("CORE"))
        assertFalse(none.suspends8372("CORE", 0.5))
        val thin = CortexCalibration7901()
        repeat(CortexCalibration7901.ISO_MIN_N_8366 - 1) { i -> thin.learn("CORE", i.toDouble(), 0.0, 40.0 - i, i.toDouble()) }
        assertEquals("INSUFFICIENT_PAIRS", thin.rankState8418("CORE"))
        assertFalse(thin.uncalibrated8372("CORE"))
        // 150 cold-start grades with no deviation: identity slope, never a 0.00 fit, never suspendable
        val cold = CortexCalibration7901()
        repeat(150) { i -> cold.learn("CORE", 0.0, 0.0, if (i % 2 == 0) -20.0 else 10.0) }
        assertEquals(1.0, cold.slope("CORE"), 0.0)
        assertFalse(cold.suspendable8322("CORE"))
        assertFalse(cold.suspends8372("CORE", 0.5))
        // a real 0.00 needs a measured fit whose deviations move against the outcomes
        val neg = CortexCalibration7901()
        repeat(150) { i -> val d = (i % 10).toDouble(); neg.learn("CORE", d, 0.0, -10.0 * d) }
        assertEquals(0.0, neg.slope("CORE"), 0.0)
        assertTrue(neg.suspendable8322("CORE"))
    }
}
