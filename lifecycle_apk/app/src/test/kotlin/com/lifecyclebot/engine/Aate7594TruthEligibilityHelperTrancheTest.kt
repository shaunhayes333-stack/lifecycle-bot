package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7594TruthEligibilityHelperTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `truth eligibility rows remain explicit predicates or lookups`() {
  run { val s=src("engine/truth/BleederLaneProbation6747.kt"); assertTrue(s.contains("fun isOnProbation")) }
  run { val s=src("engine/truth/CanaryReleaseGate6386.kt"); assertTrue(s.contains("fun canAcceptBuy")) }
  run { val s=src("engine/truth/CanonicalPositionAuthority6441.kt"); assertTrue(s.contains("fun canAffordPaperBuy")) }
  run { val s=src("engine/truth/CapitalPreservationCreed6439.kt"); assertTrue(s.contains("fun isAlignedWithDailyTarget")) }
  run { val s=src("engine/truth/DataProviderFaultCircuits6468.kt"); assertTrue(s.contains("fun isNetworkAllowed")) }
  run { val s=src("engine/truth/EarlyEntryAndPeakCapture6390.kt"); assertTrue(s.contains("fun isAlarm")) }
  run { val s=src("engine/truth/GovernorRecoverySubstrate6388.kt"); assertTrue(s.contains("fun isHeld")); assertTrue(s.contains("fun isHistoricalAudit")); assertTrue(s.contains("fun isRecoveryEligible")) }
  run { val s=src("engine/truth/FdgFanoutControl6396.kt"); assertTrue(s.contains("fun canShadowLaneExecute")) }
  run { val s=src("engine/truth/FillLotLedger6504.kt"); assertTrue(s.contains("fun canonicalQtyOf")) }
  run { val s=src("engine/truth/GovernorRecovery6388.kt"); assertTrue(s.contains("fun lastDemotionReason")) }
  run { val s=src("engine/truth/LaneCapitalFairness6732.kt"); assertTrue(s.contains("fun hasHeadroom")) }
  run { val s=src("engine/truth/LiveContinuity6392.kt"); assertTrue(s.contains("fun isBluechip")) }
  run { val s=src("engine/truth/LiveTruthExitAuthority6387.kt"); assertTrue(s.contains("fun currentIndex")); assertTrue(s.contains("fun isConfirmedZero")); assertTrue(s.contains("fun isJobActive")) }
  run { val s=src("engine/truth/SellOnlyHoldRepair6391.kt"); assertTrue(s.contains("fun hasAnyProof")); assertTrue(s.contains("fun isValidEvent")) }
  run { val s=src("engine/truth/PaperAccountLedger6430.kt"); assertTrue(s.contains("fun canAffordBuy")); assertTrue(s.contains("fun hasPersistentState6487")) }
  run { val s=src("engine/truth/PaperCatastrophicCloseIdempotency6497.kt"); assertTrue(s.contains("fun isClaimed")) }
  run { val s=src("engine/truth/PerpsSandbox6463.kt"); assertTrue(s.contains("fun leverageOf")) }
  run { val s=src("engine/truth/PositionIdentity6395.kt"); assertTrue(s.contains("fun canonicalId")) }
  run { val s=src("engine/truth/PositionLifecycleFormalization6617.kt"); assertTrue(s.contains("fun lastDeltas")) }
  run { val s=src("engine/truth/PositionViewModelStore6395.kt"); assertTrue(s.contains("fun canShowLockedPercent")); assertTrue(s.contains("fun getByMint")) }
  run { val s=src("engine/truth/PreSupervisorBudgetGuard6437.kt"); assertTrue(s.contains("fun canRun")) }
  run { val s=src("engine/truth/RootCauseFreshnessAuthority6496.kt"); assertTrue(s.contains("fun isHistoricalOnly")) }
  run { val s=src("engine/truth/SentienceLabRewardBridge6444.kt"); assertTrue(s.contains("fun canonicalWLTrio")) }
  run { val s=src("engine/truth/SoftScoreShaping6400.kt"); assertTrue(s.contains("fun lastShaping")) }
  run { val s=src("engine/truth/SpecialistProposalArbiter6629.kt"); assertTrue(s.contains("fun currentDecision6629")) }
  run { val s=src("engine/truth/StartupReconciliation6635.kt"); assertTrue(s.contains("fun isQuarantined6635")) }
  run { val s=src("engine/truth/V3VerdictContract6622.kt"); assertTrue(s.contains("fun isFatal6622")) }
  run { val s=src("engine/truth/WalletAssetClassification6387.kt"); assertTrue(s.contains("fun countsAsFreeEntrySlot")); assertTrue(s.contains("fun isDeletedMint")); assertTrue(s.contains("fun isLearningEligible")) }
  run { val s=src("engine/truth/WorkerPoolDomainRegistry6411.kt"); assertTrue(s.contains("fun canDegradedEntry")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("352 / 1,458")); assertTrue(a.contains("1,106 remain")) }
}