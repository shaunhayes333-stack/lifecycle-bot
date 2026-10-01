package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7604EngineStatePredicateTrancheTest { private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `engine state predicate rows remain source visible`() {
  run { val s=src("engine/AntiChokeManager.kt"); assertTrue(s.contains("fun currentLevel")) }
  run { val s=src("engine/BotBrain.kt"); assertTrue(s.contains("fun isRecentRegimeFavorable")) }
  run { val s=src("engine/EdgeLearning.kt"); assertTrue(s.contains("fun getLiveTradesSeen")) }
  run { val s=src("engine/EfficiencyLayer.kt"); assertTrue(s.contains("fun getTopCandidates")) }
  run { val s=src("engine/EmergentGuardrails.kt"); assertTrue(s.contains("fun getFrozenAggression")); assertTrue(s.contains("fun getTradesLastMinute")); assertTrue(s.contains("fun isAggressionFrozen")) }
  run { val s=src("engine/ExitManager.kt"); assertTrue(s.contains("fun hasCriticalCondition")) }
  run { val s=src("engine/FinalExecutionPermit.kt"); assertTrue(s.contains("fun getRejectionReason")) }
  run { val s=src("engine/FluidLearning.kt"); assertTrue(s.contains("fun getAvailableBalance")); assertTrue(s.contains("fun getLearningScaleMultiplier")); assertTrue(s.contains("fun isLearningAvailable")) }
  run { val s=src("engine/GeminiCopilot.kt"); assertTrue(s.contains("fun getRateLimitRemainingMinutes")) }
  run { val s=src("engine/GlobalTradeRegistry.kt"); assertTrue(s.contains("fun getTotalExposure")); assertTrue(s.contains("fun isInProbation")) }
  run { val s=src("engine/HoldingLogicLayer.kt"); assertTrue(s.contains("fun getAllModeParams")) }
  run { val s=src("engine/LaneAutoPauseGuard.kt"); assertTrue(s.contains("fun isPausedLive")) }
  run { val s=src("engine/LiquidityBucketRouter.kt"); assertTrue(s.contains("fun isModeAppropriate")) }
  run { val s=src("engine/LiquidityDepthAI.kt"); assertTrue(s.contains("fun getEntryLiquidity")) }
  run { val s=src("engine/LiveEntrySafetyHold.kt"); assertTrue(s.contains("fun currentFloorAdjustment")) }
  run { val s=src("engine/LiveProbeEntry.kt"); assertTrue(s.contains("fun hasActiveProbe")) }
  run { val s=src("engine/sell/LiveWalletReconciler.kt"); assertTrue(s.contains("fun isStarted")); assertTrue(s.contains("fun lastBuySignature")); assertTrue(s.contains("fun lastRunMs")) }
  run { val s=src("engine/MarketRegimeAI.kt"); assertTrue(s.contains("fun getRegimeDurationMinutes")); assertTrue(s.contains("fun getRegimeInfo")) }
  run { val s=src("engine/PerpsLaneGate.kt"); assertTrue(s.contains("fun isShadowEnabled")) }
  run { val s=src("engine/ProfitabilityLayer.kt"); assertTrue(s.contains("fun isEvicted")) }
  run { val s=src("engine/RemoteKillSwitch.kt"); assertTrue(s.contains("fun getMaxPositionOverride")); assertTrue(s.contains("fun isForcePaperMode")) }
  run { val s=src("engine/SentientPersonality.kt"); assertTrue(s.contains("fun getLatestThought")) }
  run { val s=src("engine/SentimentEngine.kt"); assertTrue(s.contains("fun getSentiment")) }
  run { val s=src("engine/ShadowLearningEngine.kt"); assertTrue(s.contains("fun getBestVariant")) }
  run { val s=src("engine/SuperBrainEnhancements.kt"); assertTrue(s.contains("fun getBreadthTrend")) }
  run { val s=src("engine/SymbolicContext.kt"); assertTrue(s.contains("fun getAge")); assertTrue(s.contains("fun isFundingUnfavourable")) }
  run { val s=src("engine/TimeOptimizationAI.kt"); assertTrue(s.contains("fun getCurrentDayOfWeek")); assertTrue(s.contains("fun getCurrentSession")); assertTrue(s.contains("fun isWeekend")) }
  run { val s=src("engine/ToxicModeCircuitBreaker.kt"); assertTrue(s.contains("fun getLiquidityFloor")); assertTrue(s.contains("fun isModeDisabled")) }
  run { val s=src("engine/TradeAuthorizer.kt"); assertTrue(s.contains("fun getShadowTracking")); assertTrue(s.contains("fun getTokenBooks")); assertTrue(s.contains("fun getTokenState")); assertTrue(s.contains("fun getTokensInBook")); assertTrue(s.contains("fun hasOpenPositionInBook")) }
  run { val s=src("engine/TradeDatabase.kt"); assertTrue(s.contains("fun getTradesByPhase")) }
  run { val s=src("engine/TradeHistoryStore.kt"); assertTrue(s.contains("fun getAllTradesIncludingInvalidForensics")) }
  run { val s=src("engine/TradingCopilot.kt"); assertTrue(s.contains("fun getAssetWindow")); assertTrue(s.contains("fun isAggressiveHunt")); assertTrue(s.contains("fun isEmergencyBrake")) }
  run { val s=src("engine/TreasuryOpportunityEngine.kt"); assertTrue(s.contains("fun getActiveDeployments")) }
  run { val s=src("engine/VoiceManager.kt"); assertTrue(s.contains("fun getBackend")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("628 / 1,458")); assertTrue(a.contains("830 remain")) }
}