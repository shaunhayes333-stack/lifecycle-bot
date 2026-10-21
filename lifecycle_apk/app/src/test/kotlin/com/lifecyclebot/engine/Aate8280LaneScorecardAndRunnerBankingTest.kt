package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8280 — (1) the lane scorecard folds the canonical live cohort per entry lane (WR / PF / expectancy) and flags a
 * lane bleeding this session; (2) the first profit rung (capital recovery) fires at the proven runner cell's own
 * conservative target when that is below the lane default, never under 1.2x, and never raises the rung.
 */
class Aate8280LaneScorecardAndRunnerBankingTest {
    @Test fun scorecardFoldIsWrPfExpectancyOfTheNetRows() {
        val s = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(listOf(0.010, -0.004, -0.002, 0.006))
        assertEquals(4, s.canonicalN)
        assertEquals(2, s.wins)
        assertEquals(2, s.losses)
        assertEquals(50.0, s.winRatePct, 1e-9)
        assertEquals(16.0 / 6.0, s.profitFactor, 1e-9)
        assertEquals(0.0025, s.expectancySol, 1e-12)
        val empty = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(emptyList())
        assertEquals(0, empty.canonicalN)
        assertEquals(0.0, empty.profitFactor, 0.0)
        assertEquals("-".padEnd(32), PipelineHealthCollector.laneCell8280(empty))
        assertEquals("-".padEnd(32), PipelineHealthCollector.laneCell8280(null))
    }

    @Test fun aLaneBleedsOnThreeCanonicalClosesWithNegativeExpectancyNotBefore() {
        val three = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(listOf(-0.003, -0.005, -0.001))
        assertTrue(PipelineHealthCollector.laneBleeding8280(three))
        assertTrue(PipelineHealthCollector.laneCell8280(three).startsWith("n=3 wr=0% pf=0.00 exp=-0.0030"))
        val two = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(listOf(-0.003, -0.005))
        assertFalse(PipelineHealthCollector.laneBleeding8280(two))
        val paying = LiveEntrySafetyHold.LiveConfidenceStats.fold8280(listOf(0.02, -0.003, -0.003))
        assertFalse(PipelineHealthCollector.laneBleeding8280(paying))
        assertFalse(PipelineHealthCollector.laneBleeding8280(null))
    }

    @Test fun theFirstRungFiresAtTheProvenCellTargetOnlyEverLowerNeverUnder1point2x() {
        // proven cell lower bound +60 % under the 2x default: rung at 1.6x
        assertEquals(1.6, RunnerGrab7967.firstRungMultiple8280(2.0, 60.0), 1e-9)
        // the cell pays more than the default: the 8259 cap stands, the rung is not raised
        assertEquals(2.0, RunnerGrab7967.firstRungMultiple8280(2.0, 437.0), 1e-9)
        // the proven margin (+20 %) is the floor of what a cell can prove: 1.2x, never lower
        assertEquals(RunnerGrab7967.FIRST_RUNG_MIN_MULTIPLE_8280, RunnerGrab7967.firstRungMultiple8280(2.0, 20.0), 1e-9)
        assertEquals(RunnerGrab7967.FIRST_RUNG_MIN_MULTIPLE_8280, RunnerGrab7967.firstRungMultiple8280(2.0, 5.0), 1e-9)
        // no proven cell, or an unreadable target: the lane default is unchanged
        assertEquals(2.0, RunnerGrab7967.firstRungMultiple8280(2.0, null), 1e-9)
        assertEquals(3.0, RunnerGrab7967.firstRungMultiple8280(3.0, Double.NaN), 1e-9)
    }

    @Test fun executorReadsTheProvenCellRungAfterThe8259CapAndTheReportCarriesTheScorecard() {
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = ex.substringAfter("CAPITAL_RECOVERY_LEARNED_RUNG_CAPPED_8259").substringBefore("return Pair(capitalRecoveryMultiple, profitLockMultiple)")
        assertTrue(block.contains("RunnerGrab7967.provenCellTargetPct8280(ts)"))
        assertTrue(block.contains("RunnerGrab7967.firstRungMultiple8280(capitalRecoveryMultiple, provenTarget8280)"))
        assertTrue(block.contains("if (rung8280 < capitalRecoveryMultiple) {"))
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val header = phc.substringAfter("===== AATE Pipeline Health Snapshot =====").substringBefore("Cortex scoreboard")
        assertTrue(header.contains("appendLaneScorecard8280(sb)"))
        assertTrue(phc.contains("LiveConfidenceStats.byLane8280(LiveEntrySafetyHold.governorWindowStart())"))
        assertTrue(phc.contains("LiveConfidenceStats.byLane8280(0L)"))
        // 8281: an UNKNOWN narrative is not a proven kind (NARR|lane|UNKNOWN = the unclassified rest of the lane)
        val frl = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val kind = frl.substringAfter("fun kindStatsFor8063(").substringBefore("private fun laneStat(")
        assertTrue(kind.contains("if (narr.isBlank() || narr == \"UNKNOWN\") null else"))
    }
}
