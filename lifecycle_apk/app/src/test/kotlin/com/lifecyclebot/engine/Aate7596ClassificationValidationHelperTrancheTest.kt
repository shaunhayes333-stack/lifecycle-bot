package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7596ClassificationValidationHelperTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `classified rows still exist`() {
  run { val s=src("engine/CanonicalLearning.kt"); assertTrue(s.contains("fun isRealLearningSize")) }
  run { val s=src("engine/BannedTokens.kt"); assertTrue(s.contains("fun getBanTimestamp")); assertTrue(s.contains("fun getBannedList")); assertTrue(s.contains("fun getDetailedBannedList")); assertTrue(s.contains("fun getReason")) }
  run { val s=src("engine/BehaviorLearning.kt"); assertTrue(s.contains("fun getBroadSignature")); assertTrue(s.contains("fun getFineSignature")); assertTrue(s.contains("fun getRichSignature")); assertTrue(s.contains("fun getSignature")) }
  run { val s=src("engine/BirdeyeBudgetGate.kt"); assertTrue(s.contains("fun isLockedDown")) }
  run { val s=src("engine/CloseOutcomeLabelSanitizer.kt"); assertTrue(s.contains("fun isDirtyForTraining")) }
  run { val s=src("engine/DeadAILayerFilter.kt"); assertTrue(s.contains("fun isNotApplicable")) }
  run { val s=src("engine/ExecutionHealthGuard.kt"); assertTrue(s.contains("fun isEmergencyReason")) }
  run { val s=src("engine/HardRugPreFilter.kt"); assertTrue(s.contains("fun isViable")) }
  run { val s=src("engine/HotfixRules.kt"); assertTrue(s.contains("fun isSignatureValid")) }
  run { val s=src("engine/LaneTag.kt"); assertTrue(s.contains("fun isMeme")) }
  run { val s=src("engine/LearningPnlSanitizer.kt"); assertTrue(s.contains("fun isTrainablePct")); assertTrue(s.contains("fun isTrainableTrade")) }
  run { val s=src("engine/LiquidityClassifier.kt"); assertTrue(s.contains("fun isBondingCurveQuote")) }
  run { val s=src("engine/sell/LivePositionCloseAuthority.kt"); assertTrue(s.contains("fun isUntrustedRpc")) }
  run { val s=src("engine/execution/MintIntegrityGate.kt"); assertTrue(s.contains("fun isSilentStablePark")) }
  run { val s=src("engine/PendingSellQueue.kt"); assertTrue(s.contains("fun isTemporary")) }
  run { val s=src("engine/sell/SellIntentSeverity.kt"); assertTrue(s.contains("fun isEmergencyBand")); assertTrue(s.contains("fun isEmergencyBand")) }
  run { val s=src("engine/sell/SellReconciler.kt"); assertTrue(s.contains("fun isLiveAlive")) }
  run { val s=src("engine/SolanaBlueChipWatchlist.kt"); assertTrue(s.contains("fun isBlueChip")) }
  run { val s=src("engine/execution/UniversalRouteEngine.kt"); assertTrue(s.contains("fun isPumpFamilyMint")) }
  run { val s=src("engine/WalletReconciler.kt"); assertTrue(s.contains("fun isHeldOrOpen")); assertTrue(s.contains("fun knownMintContains")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("409 / 1,458")); assertTrue(a.contains("1049 remain")) }
}