package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * V5.0.7822 — clean live-pipeline contracts from the 7820 runtime.
 */
class Aate7822PipelineCleanupTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun rugcheck_cannot_consume_most_of_supervisor_lease() {
        val s = src("engine/TokenSafetyChecker.kt")
        assertTrue(s.contains("RUGCHECK_ATTEMPT_CAP_MS_7819: Long = 3_500L"))
        assertTrue(s.contains("RUGCHECK_TOTAL_BUDGET_MS_7819: Long = 4_500L"))
        assertTrue(s.contains("INFLIGHT_WAIT_MS: Long = 4_000L"))
    }

    @Test fun fanout_budget_is_shared_by_candidate_generation_across_sources() {
        val s = src("engine/truth/IntakeFanoutGovernor6835.kt")
        assertTrue(s.contains("private fun opportunityKey7822"))
        assertTrue(s.contains("LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(s.contains("opportunityKey7822(mint, causalRoot) + \"::LANE::\""))
        assertTrue(s.contains("val opportunity7822 = opportunityKey7822(mint, causalRoot)"))
    }

    @Test fun live_parity_never_compares_or_rebuilds_the_paper_legacy_registry() {
        val s = src("engine/truth/PositionRegistryParityAudit6464.kt")
        assertTrue(s.contains("val registryComparable7822 = activeMode6490 == \"paper\""))
        assertTrue(s.contains("POSITION_PARITY_LEGACY_PAPER_OUT_OF_SCOPE_7822"))
        assertTrue(s.contains("POSITION_PARITY_AUTO_HEAL_SKIPPED_LIVE_7822"))
        assertFalse(s.contains("val registryMap: Map<String, EmergentGuardrails.RegistryEntry> = try {\n            EmergentGuardrails.snapshot()"))
    }

    @Test fun held_mark_worker_refreshes_when_canonical_exit_mark_is_missing() {
        val s = src("engine/truth/HeldHotMarkAuthority7419.kt")
        val block = s.substringAfter("val stale = activeOpen().filter { p ->")
            .substringBefore("if (stale.isEmpty()) return")
        assertTrue(block.contains("currentCanonicalTs(p.mint)"))
        assertTrue(block.contains("canonicalTs7822 <= 0L"))
        assertTrue(block.contains("runtimeStale7822 || canonicalStale7822"))
        assertTrue(block.contains("HELD_HOT_CANONICAL_MARK_GAP_7822"))
    }
}
