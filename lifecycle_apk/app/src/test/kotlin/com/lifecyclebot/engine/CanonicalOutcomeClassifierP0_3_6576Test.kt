package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6576 §P0-3 — canonical outcome classifier pins the WIN/LOSS/BREAKEVEN
 * band across every terminal learner. Prior to this commit each consumer had
 * its own band (>0 vs +/-0.5% vs 0.5%/-2.0% asymmetric), producing the 6573
 * forensic contradiction:
 *
 *   RewardPurityGate     : 75L / 0BE
 *   PerformanceAnalytics : 0W / 7L / 67BE
 *   GrowthRewardShaper   : 10L
 *   LearnerRewardBridge  : 10BE
 *
 * These tests assert that:
 *   1. The classifier itself uses the symmetric ±0.5% band.
 *   2. RewardPurityGate now delegates to the classifier (source-string).
 *   3. PerformanceAnalytics now delegates to the classifier (source-string).
 *   4. TacticSwitcher now delegates to the classifier (source-string).
 */
class CanonicalOutcomeClassifierP0_3_6576Test {

    private val rpgSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/RewardPurityGate6441.kt").readText()
    private val paSrc  = File("src/main/kotlin/com/lifecyclebot/engine/PerformanceAnalytics.kt").readText()
    private val tsSrc  = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()

    @Before
    fun reset() {
        CanonicalOutcomeClassifier6576.resetForTest()
    }

    @Test
    fun band_is_symmetric_half_percent() {
        assertEquals(0.5, CanonicalOutcomeClassifier6576.BREAKEVEN_BAND_PCT, 1e-9)
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.WIN,
            CanonicalOutcomeClassifier6576.classifyReadonly(0.6)
        )
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.LOSS,
            CanonicalOutcomeClassifier6576.classifyReadonly(-0.6)
        )
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.BREAKEVEN,
            CanonicalOutcomeClassifier6576.classifyReadonly(0.4)
        )
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.BREAKEVEN,
            CanonicalOutcomeClassifier6576.classifyReadonly(-0.4)
        )
    }

    @Test
    fun classify_pnl_uses_cost_basis() {
        // +1% return → WIN
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.WIN,
            CanonicalOutcomeClassifier6576.classifyPnl(0.01, 1.0)
        )
        // -1% return → LOSS
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.LOSS,
            CanonicalOutcomeClassifier6576.classifyPnl(-0.01, 1.0)
        )
        // fees at breakeven (0.1% loss) → BREAKEVEN
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.BREAKEVEN,
            CanonicalOutcomeClassifier6576.classifyPnl(-0.001, 1.0)
        )
        // no cost basis (defensive) → BREAKEVEN, never a WIN attribution
        assertEquals(
            CanonicalOutcomeClassifier6576.Class.BREAKEVEN,
            CanonicalOutcomeClassifier6576.classifyPnl(0.5, 0.0)
        )
    }

    @Test
    fun divergence_probe_counts_mismatches() {
        val before = CanonicalOutcomeClassifier6576.divergenceCount()
        CanonicalOutcomeClassifier6576.reportConsumerClass(
            positionId = "test-pid-1",
            consumer = "TEST_CONSUMER",
            callerClass = CanonicalOutcomeClassifier6576.Class.LOSS, // caller says LOSS
            returnPct = 5.0,                                          // canonical says WIN
        )
        assertEquals(before + 1L, CanonicalOutcomeClassifier6576.divergenceCount())
        // Agreement does not increment divergence.
        val mid = CanonicalOutcomeClassifier6576.divergenceCount()
        CanonicalOutcomeClassifier6576.reportConsumerClass(
            positionId = "test-pid-2",
            consumer = "TEST_CONSUMER",
            callerClass = CanonicalOutcomeClassifier6576.Class.BREAKEVEN,
            returnPct = 0.1,
        )
        assertEquals(mid, CanonicalOutcomeClassifier6576.divergenceCount())
    }

    @Test
    fun reward_purity_gate_delegates_to_classifier() {
        assertTrue(
            "RewardPurityGate must classify via CanonicalOutcomeClassifier6576 (not raw >0/<0)",
            rpgSrc.contains("CanonicalOutcomeClassifier6576.classifyPnl(realizedPnlSol, costBasis6576)")
        )
        // The pre-6576 `realizedPnlSol > 0.0 -> Outcome.WIN` fragment must not exist anymore.
        assertTrue(
            "RewardPurityGate must not carry the pre-6576 raw >0.0 classifier",
            !rpgSrc.contains("realizedPnlSol > 0.0 -> Outcome.WIN")
        )
    }

    @Test
    fun performance_analytics_delegates_to_classifier() {
        assertTrue(
            "PerformanceAnalytics must derive WIN_THRESHOLD_PCT from CanonicalOutcomeClassifier6576",
            paSrc.contains("CanonicalOutcomeClassifier6576.BREAKEVEN_BAND_PCT")
        )
        assertTrue(
            "PerformanceAnalytics isWin/isLoss/isDecisive must delegate to the classifier",
            paSrc.contains("CanonicalOutcomeClassifier6576\n            .classifyReadonly")
                || paSrc.contains(".classifyReadonly(sanitizeDouble(trade.pnlPct))")
        )
        // Prior asymmetric 0.5/-2.0 doctrine must not persist as literal constants.
        assertTrue(
            "PerformanceAnalytics must not retain the pre-6576 -2.0 loss doctrine as a literal",
            !paSrc.contains("private const val LOSS_THRESHOLD_PCT = -2.0")
        )
    }

    @Test
    fun tactic_switcher_delegates_to_classifier() {
        assertTrue(
            "TacticSwitcher must classify via CanonicalOutcomeClassifier6576 (not raw pnlPct > 0.0)",
            tsSrc.contains("CanonicalOutcomeClassifier6576.classifyReadonly(pnlPct)")
        )
        assertTrue(
            "TacticSwitcher must attribute breakevens via TACTIC_BREAKEVEN_6576",
            tsSrc.contains("TACTIC_BREAKEVEN_6576")
        )
    }
}
