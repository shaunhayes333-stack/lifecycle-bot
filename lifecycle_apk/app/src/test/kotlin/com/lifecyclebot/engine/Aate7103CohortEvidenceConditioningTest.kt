package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Aate7103CohortEvidenceConditioningTest {
    private val lane = "SNIPETEST7103"
    private val score = 50

    @Before fun setUp() { ForwardOutcomeModel.reset() }
    @After fun tearDown() { ForwardOutcomeModel.reset() }

    private fun feed(regime: String, pnlPct: Double, count: Int, tag: String, mode: String = "PAPER") {
        LearningEnvironment7835.withMode(mode) {
            repeat(count) { i ->
                val mint = "mint7103$tag$i"
                ForwardOutcomeModel.stamp(mint, lane, score, "GOO", regime, "EARLY")
                ForwardOutcomeModel.recordOutcome(mint, pnlPct)
            }
        }
    }

    @Test fun regimeSpecificEvidenceWinsWithinOneMode() {
        feed("BULL", 50.0, 12, "bull")
        feed("BEAR", -50.0, 12, "bear")
        val bull = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.cohortEvidence6911(lane, score, "BULL") }
        assertEquals("regime_mode", bull.level)
        assertEquals(12L, bull.samples)
        assertTrue(bull.expectedPnlPct > 45.0)
        val bear = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.cohortEvidence6911(lane, score, "BEAR") }
        assertEquals("regime_mode", bear.level)
        assertEquals(12L, bear.samples)
        assertTrue(bear.expectedPnlPct < -45.0)
    }

    @Test fun thinRegimeFallsBackToSameModeParent() {
        feed("BULL", 50.0, 12, "bull")
        feed("CHOP", 10.0, 3, "chop")
        val result = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.cohortEvidence6911(lane, score, "CHOP") }
        assertEquals("mode", result.level)
        assertEquals(15L, result.samples)
    }

    @Test fun unrelatedLaneHasNoEvidence() {
        feed("BULL", 50.0, 12, "bull")
        val result = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.cohortEvidence6911("NOSUCHLANE7103", score, "BULL") }
        assertEquals("none", result.level)
        assertEquals(0L, result.samples)
    }

    @Test fun shadowLaneCannotPoolIntoRealLane() {
        feed("BULL", 50.0, 12, "bull")
        LearningEnvironment7835.withMode("PAPER") {
            repeat(12) { i ->
                val mint = "mint7103shadow$i"
                ForwardOutcomeModel.stamp(mint, "V3_$lane", score, "GOO", "BULL", "EARLY")
                ForwardOutcomeModel.recordOutcome(mint, -90.0)
            }
        }
        val real = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.cohortEvidence6911(lane, score, "BULL") }
        assertEquals(12L, real.samples)
        assertTrue(real.expectedPnlPct > 45.0)
    }

    @Test fun paperAndLiveEvidenceAreIsolated() {
        feed("BULL", -60.0, 20, "paperLoss", "PAPER")
        feed("BULL", 40.0, 12, "liveWin", "LIVE")
        val live = LearningEnvironment7835.withMode("LIVE") { ForwardOutcomeModel.cohortEvidence6911(lane, score, "BULL") }
        val paper = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.cohortEvidence6911(lane, score, "BULL") }
        assertEquals(12L, live.samples)
        assertTrue("paper losses leaked into LIVE", live.expectedPnlPct > 35.0)
        assertEquals(20L, paper.samples)
        assertTrue("live wins leaked into PAPER", paper.expectedPnlPct < -55.0)
        val thinLive = LearningEnvironment7835.withMode("LIVE") { ForwardOutcomeModel.cohortEvidence6911("UNRELATED7103", score, "BULL") }
        assertEquals("none", thinLive.level)
        assertEquals(0L, thinLive.samples)
    }
}
