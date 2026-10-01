package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7587AnalyticsAdvisoryTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `analytics tranche stays read only or advisory`() {
  assertTrue(src("v3/arb/ArbLearning.kt").contains("fun getTypeStats()"))
  val e=src("v3/scoring/EducationSubLayerAI.kt"); assertTrue(e.contains("fun getLayerLevel(")); assertTrue(e.contains("fun getLayerLevelProgress(")); assertTrue(e.contains("fun getStockLearningStats(")); assertTrue(e.contains("fun getTopWinningReasons(")); assertTrue(e.contains("fun getTopLosingReasons("))
  val p=src("perps/PerpsPerformanceAttribution.kt"); assertTrue(p.contains("fun getLayerStats(")); assertTrue(p.contains("fun getTopLayers(")); assertTrue(p.contains("fun getBottomLayers(")); assertTrue(p.contains("fun getLayersNeedingAttention(")); assertTrue(p.contains("fun getSuggestedWeightAdjustments()"))
  assertTrue(src("perps/PerpsPositionSizer.kt").contains("fun getMarketStats("))
  val h=src("perps/PerpsTradeHeatmap.kt"); assertTrue(h.contains("fun getAvgPnl()")); assertTrue(h.contains("fun getHeatmapData()")); assertTrue(h.contains("fun getBestSetup()")); assertTrue(h.contains("fun getBestTierForMarket(")); assertTrue(h.contains("fun getBestTimeSlot()"))
  val q=src("engine/quant/QuantMindV2.kt"); assertTrue(q.contains("fun getOverallGrade()")); assertTrue(q.contains("fun getRegimePerformance()")); assertTrue(q.contains("fun getStrategyMetrics()")); assertTrue(q.contains("fun getRegimeSharpe("))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("195 / 1,458")); assertTrue(a.contains("1,263 remain")) }
}