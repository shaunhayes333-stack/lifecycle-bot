package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7601V4MetaApiTrancheTest { private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `surfaces remain source visible`() {
  run { val s=src("v4/meta/CrossMarketRegimeAI.kt"); assertTrue(s.contains("fun getCapitalBias")); assertTrue(s.contains("fun getRegimeFitMultiplier")); assertTrue(s.contains("fun lastAssessAgeMs")); assertTrue(s.contains("fun trackedMarketCount")) }
  run { val s=src("v4/meta/ExecutionPathAI.kt"); assertTrue(s.contains("fun recordExecution")) }
  run { val s=src("v4/meta/LeverageSurvivalAI.kt"); assertTrue(s.contains("fun getAllowedLeverage")) }
  run { val s=src("v4/meta/LiquidityFragilityAI.kt"); assertTrue(s.contains("fun getMaxSafeSize")); assertTrue(s.contains("fun getMaxSafeSizeFor")); assertTrue(s.contains("fun isTradeAllowed")); assertTrue(s.contains("fun recordBreakout")); assertTrue(s.contains("fun recordWick")) }
  run { val s=src("v4/meta/PortfolioHeatAI.kt"); assertTrue(s.contains("fun excess")); assertTrue(s.contains("fun isNewEntryAllowed")) }
  run { val s=src("v4/meta/StrategyTrustAI.kt"); assertTrue(s.contains("fun getQuarantineUntil")); assertTrue(s.contains("fun getTrustRecord")); assertTrue(s.contains("fun setQuarantine")) }
  run { val s=src("v4/meta/TradeLessonRecorder.kt"); assertTrue(s.contains("fun getLeverageLessons")); assertTrue(s.contains("fun getNarrativeLessons")); assertTrue(s.contains("fun getRegimeLessons")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("539 / 1,458")); assertTrue(a.contains("919 remain")) }
}