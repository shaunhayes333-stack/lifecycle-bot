package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7603RuntimeQuantHelperTrancheTest { private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `runtime quant helper rows remain visible`() {
  run { val s=src("engine/runtime/ColdStreakDamper.kt"); assertTrue(s.contains("fun currentLossStreak")); assertTrue(s.contains("fun currentWinStreak")); assertTrue(s.contains("fun effectiveLossStreak6991")); assertTrue(s.contains("fun gateNormalEntry")); assertTrue(s.contains("fun recordCall")); assertTrue(s.contains("fun shouldCall")) }
  run { val s=src("engine/quant/EVCalculator.kt"); assertTrue(s.contains("fun getKellySize")); assertTrue(s.contains("fun isPositiveEV")) }
  run { val s=src("engine/runtime/ExecutionCounterContract.kt"); assertTrue(s.contains("fun recordCloseAttempt")); assertTrue(s.contains("fun recordCloseSuccess")); assertTrue(s.contains("fun recordJournalBuyWrite")); assertTrue(s.contains("fun recordOpenAttempt")); assertTrue(s.contains("fun recordOpenSuccess")) }
  run { val s=src("learning/LayerVoteStore.kt"); assertTrue(s.contains("fun drainVotes")); assertTrue(s.contains("fun purgeStale")) }
  run { val s=src("engine/quant/QuantMetrics.kt"); assertTrue(s.contains("fun calculateCVaR")); assertTrue(s.contains("fun calculateCalmarRatio")); assertTrue(s.contains("fun calculateSharpeRatio")); assertTrue(s.contains("fun calculateSortinoRatio")); assertTrue(s.contains("fun calculateVaR")); assertTrue(s.contains("fun updateEquity")) }
 }
 @Test fun `corrected audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("554 / 1,458 classified; 904 remain")); assertTrue(a.contains("575 / 1,458")); assertTrue(a.contains("883 remain")) }
}