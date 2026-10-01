package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7580LearningReportReadbackTrancheTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()
    @Test fun `learning tranche is counters snapshots and reports`() {
        assertTrue(src("engine/BehaviorLearning.kt").contains("fun getWinLossCount(): Int ="))
        assertTrue(src("engine/CloudLearningSync.kt").contains("fun getCommunityStats(): String"))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getSessionPnlStats(): SessionPnlSnapshot"))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getSubTraderTradeCount(): Int ="))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getSubTraderWinCount():"))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getMarketsTradeCount(): Int ="))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getAltsTradeCount(): Int ="))
        assertTrue(src("engine/PerformanceAnalytics.kt").contains("fun lifetimeClosedCount(): Int = lifetimeClosed"))
        assertTrue(src("v3/learning/ShadowLearningEngine.kt").contains("fun getTrackedTradesCount(): Int"))
        assertTrue(src("v3/learning/ShadowLearningEngine.kt").contains("fun getVolatilityPlayStats(): VolatilityStats"))
    }
    @Test fun `audit count advances`() {
        val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("107 / 1,458"))
        assertTrue(a.contains("1,351 remain"))
    }
}
