package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SetupTimingGrades8528
import com.lifecyclebot.engine.truth.StrategyRank8153
import org.junit.Assert.*
import org.junit.Test

class Aate8528SetupTimingTest {
    private val at = 1_000_000L
    @Test fun earlyDeadlineWinsOverTheLongerFirstFillWindow() {
        val feed = com.lifecyclebot.engine.cortex.CortexLabelFeed8291
        assertEquals(at + 20_000, feed.windowEnd8418(at, 15_000, true, 60_000,
            longArrayOf(60_000, 15_000), longArrayOf(60_000, 5_000), 30_000))
        assertEquals(at + 15_000, feed.requiredObservationAt8521(at, at + 16_000,
            longArrayOf(60_000, 15_000), longArrayOf(60_000, 5_000)))
        assertTrue(feed.needsIndependentRefresh8521(at + 20_000, at + 16_000, true, true,
            at + 14_000, requiredAfterMs = at + 15_000))
    }
    @Test fun ignitionAndPatientBaseInSameLaneHaveDifferentFrozenClocks() {
        val ignition = SetupTimingGrades8528.plan("OPP_EARLY_IGNITION", "EARLY")!!
        val base = SetupTimingGrades8528.plan("PRE_IGNITION_BASE", "EARLY")!!
        assertEquals(listOf(15_000L, 30_000L, 60_000L), ignition.horizons)
        assertEquals(listOf(60_000L, 300_000L), base.horizons)
        assertEquals(ignition, SetupTimingGrades8528.restorePlan(ignition.json()))
        assertNull(SetupTimingGrades8528.plan("DATA_INCOMPLETE", "EARLY"))
    }
    @Test fun oldPriceAndLatePriceCannotPretendToBeFifteenSecondFeedback() {
        fun grade(source: Long, now: Long, basis: Long = at + 1000, valid: Boolean = true) =
            SetupTimingGrades8528.grade(at, 15_000, basis, source, now, valid)
        assertEquals(SetupTimingGrades8528.Grade.WAIT, grade(at + 14_999, at + 15_000))
        assertEquals(SetupTimingGrades8528.Grade.OBSERVED, grade(at + 15_000, at + 16_000))
        assertEquals(SetupTimingGrades8528.Grade.UNAVAILABLE, grade(at + 31_000, at + 31_000))
        assertEquals(SetupTimingGrades8528.Grade.UNAVAILABLE, grade(at + 1000, at + 31_000))
        assertEquals(SetupTimingGrades8528.Grade.UNAVAILABLE, grade(at + 18_000, at + 31_000, valid = false))
        assertEquals(SetupTimingGrades8528.Grade.UNAVAILABLE, grade(at + 18_000, at + 31_000, basis = at + 18_000))
    }
    @Test fun earlyPopAndLaterCollapseRemainDifferentRecordsAcrossRestart() {
        val p = SetupTimingGrades8528.plan("QUICK_FLIP", "EARLY")!!
        val book = SetupTimingGrades8528.Book()
        repeat(40) { book.record("EXPRESS", p, 30_000, 6.0); book.record("EXPRESS", p, 60_000, -20.0) }
        val restored = SetupTimingGrades8528.Book().also { it.restore(book.encode()) }
        assertEquals(2.0, restored.adjustment("EXPRESS", "QUICK_FLIP", "EARLY"), 0.0)
        assertEquals(0.0, restored.adjustment("MOONSHOT", "QUICK_FLIP", "EARLY"), 0.0)
        assertEquals(0.0, restored.adjustment("EXPRESS", "QUICK_FLIP", "LATE"), 0.0)
        assertEquals(80L, restored.observed)
    }
    @Test fun patientSetupIsNotPenalisedForAnEarlyPullback() {
        val p = SetupTimingGrades8528.plan("PRE_IGNITION_BASE", "EARLY")!!
        val book = SetupTimingGrades8528.Book()
        repeat(40) { book.record("MOONSHOT", p, 60_000, -8.0) }
        assertEquals(0.0, book.adjustment("MOONSHOT", p.setup, p.band), 0.0)
        repeat(40) { book.record("MOONSHOT", p, 300_000, 5.0) }
        assertEquals(2.0, book.adjustment("MOONSHOT", p.setup, p.band), 0.0)
    }
    @Test fun replayAndRestartDoNotDoubleGradeAndLaterRallyCannotRewriteEarlyLoss() {
        val p = SetupTimingGrades8528.plan("QUICK_FLIP", "EARLY")!!
        val book = SetupTimingGrades8528.Book(); val done = mutableSetOf<Long>()
        repeat(4) { book.observe("EXPRESS", p, done, at, at + 1000, at + 15_000, at + 15_000, true, -5.0) }
        assertEquals(1L, book.observed)
        val restored = SetupTimingGrades8528.Book().also { it.restore(book.encode()) }
        val restoredDone = done.toMutableSet()
        restored.observe("EXPRESS", p, restoredDone, at, at + 1000, at + 15_000, at + 15_000, true, -5.0)
        assertEquals(1L, restored.observed)
        restored.observe("EXPRESS", p, restoredDone, at, at + 1000, at + 30_000, at + 30_000, true, 80.0)
        assertEquals(2L, restored.observed)
        assertEquals(setOf(15_000L, 30_000L), restoredDone)
        assertTrue(restored.encode().contains("-5"))
    }

    @Test fun insufficientSamplesOrMissingObservationsNeverCreateAnEdge() {
        val p = SetupTimingGrades8528.plan("QUICK_FLIP", "EARLY")!!
        val book = SetupTimingGrades8528.Book()
        repeat(29) { book.record("EXPRESS", p, 30_000, 10.0) }
        repeat(100) { book.missing() }
        assertEquals(0.0, book.adjustment("EXPRESS", p.setup, p.band), 0.0)
        assertEquals(29L, book.observed)
        assertEquals(100L, book.unavailable)
    }
    @Test fun timingCanSelectAnUnprovenSetupButCannotCertifyProfitabilityOrReviveALoser() {
        fun option(id: String, n: Int, mean: Double, timing: Double) =
            StrategyRank8153.Option8153(id, n, mean, 1.0, entryTimingAdjustment8528 = timing)
        val chosen = StrategyRank8153.select8153("OLD", listOf(option("OLD", 0, Double.NaN, 0.0),
            option("EARLY", 0, Double.NaN, 2.0), option("LOSER", 40, -10.0, 2.0)), 4.0)
        assertEquals("EARLY", chosen.id)
        assertEquals(StrategyRank8153.Tier8153.UNPROVEN, chosen.tier)
        assertTrue(chosen.rejected.any { it.startsWith("LOSER:") })
        val supported = StrategyRank8153.select8153("OLD", listOf(option("PROVEN", 40, 8.0, -2.0),
            option("EARLY", 0, Double.NaN, 2.0)), 4.0)
        assertEquals("PROVEN", supported.id)
        assertEquals(8.0 - 1.0, StrategyRank8153.score8153(option("PROVEN", 40, 8.0, -2.0), 4.0), 0.0)
    }
}
