package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6766 §RAISE_ENTRY_FLOOR + §PROBE_CONTAMINATION_FILTER regression fence.
 *
 * Triage agent Feb 2026 identified the primary defect behind the 4.3% WR:
 *   1. `REGIME_BASE_MIN_SCORE_6747 = 15` was letting garbage candidates
 *      through — a score of 16 cleared even a regime-boosted floor.
 *   2. 150 probe-style events (79 dust + 71 zero-signal) at 0.04 SOL were
 *      polluting the clean-paper leaderboard so LaneExpectancyDamper and
 *      CatastrophicLaneAutoVeto6763 saw a fake picture of lane toxicity.
 *
 * These fences lock the two source-level fixes:
 *   1. Base admission floor raised to 35 (effective floor in CHOP/DUMP
 *      becomes 40 via existing +5 regime delta).
 *   2. StrategyTelemetry.computeCleanPaperTerminalLeaderboard excludes
 *      dust closes (< 0.05 SOL entry cost) from the WR/PF calculation.
 *
 * No new authority layer added — both are direct edits to existing code.
 */
class Aate6766EntryQualityAtSourceTest {

    @Test fun regime_base_min_score_is_35_not_15() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "REGIME_BASE_MIN_SCORE_6747 must be raised to 35",
            src.contains("private const val REGIME_BASE_MIN_SCORE_6747 = 35"),
        )
        assertTrue(
            "the 6766 change docblock must be present so future refactors know why",
            src.contains("§RAISE_ENTRY_FLOOR"),
        )
    }

    @Test fun clean_paper_leaderboard_excludes_dust_probes() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTelemetry.kt").readText()
        assertTrue(
            "computeCleanPaperTerminalLeaderboard must filter dust (< 0.05 SOL entry)",
            src.contains("§PROBE_CONTAMINATION_FILTER") &&
                src.contains(">= 0.05"),
        )
        assertTrue(
            "filter must use entryCostSol as the primary size signal",
            src.contains("entryCostSol.takeIf"),
        )
    }
}
