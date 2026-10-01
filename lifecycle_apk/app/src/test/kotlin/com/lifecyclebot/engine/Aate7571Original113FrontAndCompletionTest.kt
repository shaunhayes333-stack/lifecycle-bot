package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7571Original113FrontAndCompletionTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `front block closed loop evidence is real`() {
        assertTrue(src("engine/market/LaneHunter7297.kt").contains("MomentumPredictorAI.getStrongMomentumTokens()"))
        val oracle = src("engine/truth/PredictiveEntryOracle6915.kt")
        assertTrue(oracle.contains("calculateAgePatternScore(tokenAgeMinutes, hasGraduated)"))
        assertTrue(oracle.contains("getHistoricalRecommendation(liquidityUsd, volumeUsd, 0.0)"))
        assertTrue(src("engine/Executor.kt").contains("ExplorationBudget.allowShadowSignal(shadowLane7537)"))
    }

    @Test
    fun `background sidecars remain off meme hot path`() {
        val hot = src("engine/BotService.kt") + "\n" + src("engine/FinalDecisionGate.kt")
        assertFalse(hot.contains("getSolanaEcosystemMomentum("))
        assertFalse(hot.contains("getNetworkBoostForMint("))
        assertFalse(hot.contains("getActionableSignals("))
        assertFalse(hot.contains("scoreSentimentWithLlm("))
        assertFalse(hot.contains("getEdgeLedger("))
    }

    @Test
    fun `legacy parallel scoring is not silently layered onto fdg`() {
        val fdg = src("engine/FinalDecisionGate.kt")
        assertFalse(fdg.contains("EdgeOptimizer.calculateWeightedScores("))
        assertTrue(src("engine/truth/ExecutableEntryAuthority6450.kt").contains("fun scoreFloorDelta6487(): Int = 0"))
    }

    @Test
    fun `audit marks original 113 fully reconciled`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7571 — original-113 A_PREDICT front (items 1–19) + 113 reconciliation complete"))
        assertTrue(audit.contains("ORIGINAL 113 COMPLETE:"))
        assertTrue(audit.contains("A_PREDICT 47/47"))
        assertTrue(audit.contains("B_RISK 12/12"))
        assertTrue(audit.contains("C_EXIT 54/54"))
    }
}
