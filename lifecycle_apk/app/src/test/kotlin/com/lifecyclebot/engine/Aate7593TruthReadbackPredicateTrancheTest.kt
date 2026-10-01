package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7593TruthReadbackPredicateTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `truth rows remain explicit state accessors or predicates`() {
  run { val s=src("engine/truth/AntiRewardHackingGuard6439.kt"); assertTrue(s.contains("fun currentHigh")) }
  run { val s=src("engine/truth/BackgroundTradingAuthority6469.kt"); assertTrue(s.contains("fun currentJobId")) }
  run { val s=src("engine/truth/CanonicalSettlement6389.kt"); assertTrue(s.contains("fun currentRunId")); assertTrue(s.contains("fun currentStartMs")); assertTrue(s.contains("fun eligibleForFreshMetrics")); assertTrue(s.contains("fun isAmbiguous")); assertTrue(s.contains("fun isForbiddenSource")); assertTrue(s.contains("fun isLegalSource")); assertTrue(s.contains("fun stallCountAbove700ms")) }
  run { val s=src("engine/truth/CanonicalExecutionTruth6394.kt"); assertTrue(s.contains("fun currentExecutionId")); assertTrue(s.contains("fun isConsumed")); assertTrue(s.contains("fun isTagged")) }
  run { val s=src("engine/truth/ForensicFinalityAndTuner6393.kt"); assertTrue(s.contains("fun canonicalLearningEligible")); assertTrue(s.contains("fun countHeld")); assertTrue(s.contains("fun countZero")); assertTrue(s.contains("fun eligibleForGovernorInfluence")); assertTrue(s.contains("fun eligibleForZeroClose")); assertTrue(s.contains("fun isValidTransition")); assertTrue(s.contains("fun isWalletBalanceAuthority")) }
  run { val s=src("engine/truth/CanonicalFinalizedTradeBus6464.kt"); assertTrue(s.contains("fun isExcluded")) }
  run { val s=src("engine/truth/CanonicalOutcomeClassifier6576.kt"); assertTrue(s.contains("fun counts")) }
  run { val s=src("engine/truth/CanonicalPositionAuthority6441.kt"); assertTrue(s.contains("fun openCountForValuation")) }
  run { val s=src("engine/truth/CanonicalTokenMetricsSnapshot6725.kt"); assertTrue(s.contains("fun isDyingToken")); assertTrue(s.contains("fun isHealthyRunner")) }
  run { val s=src("engine/truth/CanonicalTradeStream6501.kt"); assertTrue(s.contains("fun isEligible")) }
  run { val s=src("engine/truth/PaperCapitalAuthority6577.kt"); assertTrue(s.contains("fun accountId")); assertTrue(s.contains("fun invariantCounts")) }
  run { val s=src("engine/truth/ProtectiveExitScheduler6450.kt"); assertTrue(s.contains("fun armedCount7027")); assertTrue(s.contains("fun noMarkCount7027")) }
  run { val s=src("engine/truth/RiskExitPriorityDomain6461.kt"); assertTrue(s.contains("fun laneStatsAgeMs")) }
  run { val s=src("engine/truth/RootCauseClassifier6471.kt"); assertTrue(s.contains("fun lastResult")) }
  run { val s=src("engine/truth/RuntimeTune6833.kt"); assertTrue(s.contains("fun accountingBoundaryViolations6833")); assertTrue(s.contains("fun markContractHonoredCount6833")); assertTrue(s.contains("fun phantomSizedRejectedCount6833")) }
  run { val s=src("engine/truth/TokenMapVersionGuard6411.kt"); assertTrue(s.contains("fun currentLaneRoutingVersion")); assertTrue(s.contains("fun currentMappingVersion")) }
  run { val s=src("engine/truth/UniversalSlLeaseRegistry6402.kt"); assertTrue(s.contains("fun oldestLeaseAgeMs")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("314 / 1,458")); assertTrue(a.contains("1,144 remain")) }
}