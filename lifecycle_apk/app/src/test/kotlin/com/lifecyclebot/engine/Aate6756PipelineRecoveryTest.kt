package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6756 source-level locks for the 5.0.6755 operator snapshot repair.
 *
 * Locks the causal fixes rather than outcome numbers:
 *  - lane-local capital headroom must not collapse at 90% utilisation;
 *  - normal ~50-position inventory must not stack a global turnover choke;
 *  - collapsed EXPRESS must enter earned-recovery execution;
 *  - CYCLIC must have an explicit execution-election identity/priority;
 *  - all paper UI economics must come from one immutable journal revision;
 *  - observation freshness may be 300s while executable freshness remains 120s.
 */
class Aate6756PipelineRecoveryTest {

    private fun src(path: String): String = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test
    fun `lane fairness has target hysteresis without removing hard portfolio guard`() {
        val fairness = src("engine/truth/LaneCapitalFairness6732.kt")
        val throughput = src("engine/truth/ExitThroughputAuthority6727.kt")
        assertTrue(fairness.contains("LANE_HEADROOM_RATIO = 1.10"))
        assertTrue(throughput.contains("POSITION_HARD_CAP"))
        assertTrue(throughput.contains("LaneCapitalFairness6732.hasHeadroom"))
    }

    @Test
    fun `normal inventory no longer stacks turnover cadence`() {
        val slot = src("engine/SlotHealthGate.kt")
        assertTrue(slot.contains("TURNOVER_SOFT_START_6709 = 64"))
        assertTrue(slot.contains("TURNOVER_MEDIUM_START_6709 = 72"))
        assertTrue(slot.contains("MEME_TURNOVER_PRESSURE_DEFER_6709"))
    }

    @Test
    fun `express collapsed lane must earn economic execution back`() {
        val bucket = src("engine/BucketExecutionState.kt")
        val election = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(bucket.contains("EXPRESS_RECOVERY_MIN_TRADES = 5"))
        assertTrue(bucket.contains("EXPRESS_RECOVERY_MAX_WR_PCT = 20.0"))
        assertTrue(bucket.contains("EXPRESS_RECOVERY_SCORE_FLOOR = 70"))
        assertTrue(bucket.contains("expressRecoveryShadow6756"))
        assertTrue(election.contains("learnedPenalty6756"))
        assertTrue(election.contains("LaneExpectancyDamper.sizeMultiplier(\"EXPRESS\")"))
    }

    @Test
    fun `cyclic is explicit execution specialist not fallback lane`() {
        val election = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(election.contains("\"CYCLIC\" to 88"))
        assertFalse(election.contains("\"CYCLIC\" to 50"))
    }

    @Test
    fun `paper heroes consume one immutable economic revision`() {
        val account = src("engine/truth/UnifiedAccountSnapshot6635.kt")
        assertTrue(account.contains("JournalEconomicAuthority6616.currentSnapshot()"))
        assertTrue(account.contains("economicRevision"))
        assertTrue(account.contains("RETAIN_LAST_RECONCILED"))
        assertTrue(account.contains("journal.cashSol"))
        assertTrue(account.contains("journal.equitySol"))
    }

    @Test
    fun `observation unchoke never widens executable freshness`() {
        val marks = src("engine/truth/CanonicalPriceMark6522.kt")
        assertTrue(marks.contains("MARK_FRESHNESS_WINDOW_MS_6739 = 120_000L"))
        assertTrue(marks.contains("OBSERVATION_FRESHNESS_WINDOW_MS_6743 = 300_000L"))
        assertTrue(marks.contains("ageMs !in -5_000L..OBSERVATION_FRESHNESS_WINDOW_MS_6743"))
        assertTrue(marks.contains("val executableFresh = age in -5_000L..MARK_FRESHNESS_WINDOW_MS_6739"))
        assertFalse(marks.contains("if (ageMs !in -5_000L..120_000L) return false"))
    }
}
