package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7569Original113PerpsPredictiveBlockTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `original 113 perps predictive block is helper or snapshot shaped`() {
        assertTrue(src("perps/PerpsAdvancedAI.kt").contains("fun getHourlyWinRate("))
        val replay = src("perps/PerpsAutoReplayLearner.kt")
        assertTrue(replay.contains("fun getWinningPatterns()"))
        assertTrue(replay.contains("fun getLosingPatterns()"))
        val models = src("perps/PerpsModels.kt")
        assertTrue(models.contains("fun isHighConfidence(): Boolean = confidence >= 80"))
        assertTrue(models.contains("fun getSignalStrength(): String = when"))
        assertTrue(src("perps/PerpsLearningBridge.kt").contains("fun getStockLayerRecommendations("))
        val notif = src("perps/PerpsNotificationManager.kt")
        assertTrue(notif.contains("fun notifyPatternDiscovered("))
        assertTrue(notif.contains("fun notifyStrongSignal("))
        assertTrue(src("perps/PerpsTradeHeatmap.kt").contains("fun getAIRecommendation(): String"))
        assertTrue(src("perps/PerpsTraderAI.kt").contains("fun getLifetimeWinRatePct(): Int"))
        assertTrue(src("v3/scoring/QualityTraderAI.kt").contains("fun getRecommendedLeverage("))
    }

    @Test
    fun `audit explicitly closes original 113 items 20 through 30`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7569 — original-113 A_PREDICT perps/markets block (items 20–30)"))
        assertTrue(audit.contains("Original-113 A_PREDICT items 20–30 are now classified/closed as a contiguous block."))
    }
}
