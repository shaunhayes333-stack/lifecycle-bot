package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7568PredictiveHelperLedgerTruthTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `shadow exploration budget is already production wired`() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("ExplorationBudget.allowShadowSignal(shadowLane7537)"))
        assertTrue(exec.contains("PAPER_EXPLORATION_SHADOW_BUDGET_ADMIT_7537"))
    }

    @Test
    fun `legacy score floor accessor stays neutral`() {
        val auth = src("engine/truth/ExecutableEntryAuthority6450.kt")
        assertTrue(auth.contains("fun scoreFloorDelta6487(): Int = 0"))
        assertTrue(auth.contains("fun sizeMultiplier6487(): Double = 1.0"))
    }

    @Test
    fun `remaining predictive rows are metric calibration or helper shaped`() {
        assertTrue(src("engine/FluidLearning.kt").contains("fun getExitTagWinRate("))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getHeuristicSignal("))
        assertTrue(src("engine/truth/ForensicEventEnvelope6430.kt").contains("fun setLedgerEpoch("))
        assertTrue(src("perps/PerpsTradeHeatmap.kt").contains("fun getAIRecommendation(): String"))
        assertTrue(src("perps/PerpsTraderAI.kt").contains("fun getLifetimeWinRatePct(): Int"))
        assertTrue(src("engine/truth/ScoreDistributionHistogram6396.kt").contains("fun recommendAdaptiveBaseline(): Int?"))
        assertTrue(src("engine/SmartExitOptimizer.kt").contains("fun getMinConfidenceAdvisory("))
    }

    @Test
    fun `audit records stale and helper classifications`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7568 — A_PREDICT stale-ledger + calibration/helper cleanup"))
        assertTrue(audit.contains("CLOSED_LOOP / LEDGER_STALE (7537)"))
        assertTrue(audit.contains("INTENTIONALLY NEUTRALIZED COMPAT ACCESSOR"))
        assertTrue(audit.contains("THRESHOLD CALIBRATION HELPER / TESTED"))
    }
}
