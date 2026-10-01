package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7597V3AccessorPredicateTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `v3 accessor rows remain named surfaces`() {
  run { val s=src("v3/core/AIStartupCoordinator.kt"); assertTrue(s.contains("fun isInitialized")) }
  run { val s=src("v3/scoring/BehaviorAI.kt"); assertTrue(s.contains("fun getAggressionName")) }
  run { val s=src("v3/scoring/BootstrapAdaptiveEngine.kt"); assertTrue(s.contains("fun getMultiplier")) }
  run { val s=src("v3/scoring/CashGenerationAI.kt"); assertTrue(s.contains("fun getCurrentTreasuryBalance")) }
  run { val s=src("v3/decision/DecisionEngine.kt"); assertTrue(s.contains("fun getStarvationRelief")) }
  run { val s=src("v3/scoring/DipHunterAI.kt"); assertTrue(s.contains("fun getFluidRecoveryTarget")) }
  run { val s=src("v3/scoring/EducationSubLayerAI.kt"); assertTrue(s.contains("fun getBootstrapRelaxation")) }
  run { val s=src("v3/eligibility/EligibilityGate.kt"); assertTrue(s.contains("fun isCoolingDown")); assertTrue(s.contains("fun isGlobalExposureMaxed")); assertTrue(s.contains("fun isTokenAlreadyOpen")) }
  run { val s=src("v3/scoring/FluidLearningAI.kt"); assertTrue(s.contains("fun getAltsLearningProgress")); assertTrue(s.contains("fun getBehaviorModifier")); assertTrue(s.contains("fun getFreshTokenAgeMinutes")); assertTrue(s.contains("fun getLearningWeights")); assertTrue(s.contains("fun getMinHistoryCandles")); assertTrue(s.contains("fun getScannerMinLiquidity")) }
  run { val s=src("v3/scoring/FundingRateAwarenessAI.kt"); assertTrue(s.contains("fun getFundingApr")) }
  run { val s=src("v3/scoring/LayerTransitionManager.kt"); assertTrue(s.contains("fun getCurrentLayer")); assertTrue(s.contains("fun getLayerState")) }
  run { val s=src("v3/scoring/LiquidityCycleAI.kt"); assertTrue(s.contains("fun isOutflowing")) }
  run { val s=src("v3/scoring/ManipulatedTraderAI.kt"); assertTrue(s.contains("fun getFluidMaxAge")); assertTrue(s.contains("fun getFluidPositionSize")) }
  run { val s=src("v3/modes/MarketStructureRouter.kt"); assertTrue(s.contains("fun getAIWeights")); assertTrue(s.contains("fun getRawPositionParams")); assertTrue(s.contains("fun isSuitableForLiquidity")) }
  run { val s=src("v3/scoring/MetaCognitionAI.kt"); assertTrue(s.contains("fun getLayerDashboard")) }
  run { val s=src("v3/scoring/MoonshotTraderAI.kt"); assertTrue(s.contains("fun getDailyHundredX")); assertTrue(s.contains("fun getDailyTenX")) }
  run { val s=src("v3/scoring/NewsShockAI.kt"); assertTrue(s.contains("fun getSlope")) }
  run { val s=src("v3/scoring/OrderFlowImbalanceAI.kt"); assertTrue(s.contains("fun getCumulativeDelta")); assertTrue(s.contains("fun isAbsorbing")) }
  run { val s=src("v3/scoring/ProjectSniperAI.kt"); assertTrue(s.contains("fun getActiveMissionCount")) }
  run { val s=src("v3/scoring/ScoreCard.kt"); assertTrue(s.contains("fun hasFatal")) }
  run { val s=src("v3/shadow/ShadowTracker.kt"); assertTrue(s.contains("fun isTracked")) }
  run { val s=src("v3/scoring/SmartMoneyDivergenceAI.kt"); assertTrue(s.contains("fun hasBearishDivergence")); assertTrue(s.contains("fun hasBullishDivergence")) }
  run { val s=src("v3/scoring/StablecoinFlowAI.kt"); assertTrue(s.contains("fun getRegimeBias")) }
  run { val s=src("v3/scoring/UltraFastRugDetectorAI.kt"); assertTrue(s.contains("fun getMonitoredCount")) }
  run { val s=src("v3/bridge/V3Adapter.kt"); assertTrue(s.contains("fun getLearningStore")); assertTrue(s.contains("fun getOrchestrator")) }
  run { val s=src("v3/V3EngineManager.kt"); assertTrue(s.contains("fun getMode")); assertTrue(s.contains("fun isSuccess")); assertTrue(s.contains("fun shouldExecute")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("452 / 1,458")); assertTrue(a.contains("1006 remain")) }
}