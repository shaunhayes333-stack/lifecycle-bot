package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7591MaintenanceRecoveryControlTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `maintenance tranche remains explicit state management`() {
  assertTrue(src("engine/AutoModeEngine.kt").contains("fun clearCopy()"))
  assertTrue(src("engine/BirdeyeBudgetGate.kt").contains("fun setDailyCap("))
  assertTrue(src("engine/DataOrchestrator.kt").contains("fun setDevWallet("))
  assertTrue(src("engine/EfficiencyLayer.kt").contains("fun removeCandidate("))
  assertTrue(src("engine/EmergentGuardrails.kt").contains("fun enableConfigChanges()"))
  assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun setCrossLearningEnabled("))
  assertTrue(src("engine/GlobalTradeRegistry.kt").contains("fun pruneDormant("))
  assertTrue(src("v3/scoring/InsiderTrackerAI.kt").contains("fun removeCustomWallet("))
  assertTrue(src("perps/PerpsTrailingStop.kt").contains("fun setGlobalConfig("))
  assertTrue(src("engine/ReentryGuard.kt").contains("fun clearLockout("))
  assertTrue(src("engine/RemoteKillSwitch.kt").contains("fun clearLocalKill()"))
  val sh=src("engine/SelfHealingDiagnostics.kt"); assertTrue(sh.contains("fun clearPoisonedMemory(")); assertTrue(sh.contains("fun manualClearMemory("))
  assertTrue(src("engine/TradeHistoryStore.kt").contains("fun invalidateStatsCache()"))
  assertTrue(src("engine/TokenMetaCache.kt").contains("fun pruneStale("))
  assertTrue(src("engine/WhaleDetector.kt").contains("fun clearToken("))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("269 / 1,458")); assertTrue(a.contains("1,189 remain")) }
}