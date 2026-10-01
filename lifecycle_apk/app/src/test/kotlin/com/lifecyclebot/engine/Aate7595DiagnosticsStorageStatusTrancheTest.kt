package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7595DiagnosticsStorageStatusTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `classified rows still exist`() {
  run { val s=src("engine/ErrorLogger.kt"); assertTrue(s.contains("fun getCrashesOnly")); assertTrue(s.contains("fun getCurrentSessionLogs")); assertTrue(s.contains("fun getErrorsAndCrashes")); assertTrue(s.contains("fun getLogsByComponent")) }
  run { val s=src("engine/PatternAutoTuner.kt"); assertTrue(s.contains("fun getLastUpdateTs")); assertTrue(s.contains("fun getTradesAnalyzed")) }
  run { val s=src("engine/RateLimiter.kt"); assertTrue(s.contains("fun getAllUsage")); assertTrue(s.contains("fun getEffectiveLimit")); assertTrue(s.contains("fun getRetryAfterMs")); assertTrue(s.contains("fun getUsage")) }
  run { val s=src("engine/ReentryGuard.kt"); assertTrue(s.contains("fun getLockoutInfo")); assertTrue(s.contains("fun getRemainingMinutes")); assertTrue(s.contains("fun isSecondMoonHot")) }
  run { val s=src("engine/RuggedContracts.kt"); assertTrue(s.contains("fun getCount")) }
  run { val s=src("engine/TokenSafetyChecker.kt"); assertTrue(s.contains("fun getWhitelistedMints")); assertTrue(s.contains("fun isWhitelisted")) }
  run { val s=src("engine/ScannerFanoutDedupe6374.kt"); assertTrue(s.contains("fun currentTtlMs")) }
  run { val s=src("engine/WalletPositionLock.kt"); assertTrue(s.contains("fun getBreakdown")); assertTrue(s.contains("fun getExposurePct")); assertTrue(s.contains("fun getTotalDeployed")) }
  run { val s=src("engine/WalletTokenMemory.kt"); assertTrue(s.contains("fun getAllEntries")); assertTrue(s.contains("fun isKnownOpenPosition")) }
  run { val s=src("engine/WalletManager.kt"); assertTrue(s.contains("fun getCurrentRpcUrl")) }
  run { val s=src("engine/PipelineTracer.kt"); assertTrue(s.contains("fun getLoopAggression")) }
  run { val s=src("engine/PersonalityMemoryStore.kt"); assertTrue(s.contains("fun getBio")) }
  run { val s=src("engine/PersistentLearning.kt"); assertTrue(s.contains("fun getStoragePath")) }
  run { val s=src("engine/TradeLifecycle.kt"); assertTrue(s.contains("fun getCompleted")) }
  run { val s=src("engine/TradeStateMachine.kt"); assertTrue(s.contains("fun getCooldownRemaining")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("380 / 1,458")); assertTrue(a.contains("1078 remain")) }
}