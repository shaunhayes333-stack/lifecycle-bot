package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the real normal/early FDG adapters and the actual evidence-arrival callback, not a copied state machine. */
class Aate8397HandoffContinuityTest {
    private val t0 = 10_000_000L
    private val lane = "MOONSHOT"
    private val mint = "handoff8397"

    @Before fun reset() {
        SpecialistElection8136.resetForTests8136()
        AdmissionHandoff8144.resetForTest8397()
        OwnershipChain8364.resetForTests8364()
    }
    @After fun clean() = reset()

    private fun pending(version: Long = 1L, missing: List<String> = listOf("liq:UNKNOWN")) {
        SpecialistElection8136.registerNative8136(mint, version, t0, mapOf(
            "SHITCOIN" to SpecialistElection8136.NativeView8136(true, 70.0, "READY"),
            lane to SpecialistElection8136.NativeView8136(true, 80.0, "READY")), t0)
        SpecialistElection8136.cyclePrimary8136(mint, version, "SHITCOIN", false, null, t0)
        SpecialistElection8136.nameHandoffReceiver8153(mint, "SHITCOIN", lane, t0)
        assertTrue(SpecialistElection8136.claimHandoffReceiver8153(mint, lane, "SHITCOIN", null, t0).executable)
        HandoffReceiverTasks8153.openTask8153(mint, lane, "SHITCOIN", "FIT", version, t0, t0)
        HandoffReceiverTasks8153.markScheduled8153(mint, lane, t0 + 100)
        AdmissionHandoff8144.installForTest8397(AdmissionHandoff8144.Handoff8144(mint, "SHITCOIN", lane, "FIT", t0,
            receiver = lane, state = AdmissionHandoff8144.State8144.RECEIVER_QUALIFIED))
        CandidateAdmission8136.holdDeferForTest8252(mint, lane, missing, t0)
    }

    private fun early(reason: String) = FinalDecisionGate.FinalDecision(false, FinalDecisionGate.TradeMode.LIVE,
        FinalDecisionGate.ApprovalClass.BLOCKED, "C", 50.0, FinalDecisionGate.EdgeVerdict.SKIP, reason,
        FinalDecisionGate.BlockLevel.EDGE, 0.0, listOf("lane:$lane"), mint, "TEST", "pending data", emptyList())

    private fun assertDeferredThenRecover() {
        val deferred = HandoffReceiverTasks8153.taskOf8153(mint, t0 + 1_000)!!
        assertTrue(deferred.pending)
        assertEquals(setOf("liq:UNKNOWN"), deferred.missingFeatures8397)
        assertFalse("backoff without a material arrival", HandoffReceiverTasks8153.isDue8153(mint, lane, t0 + 1_001, 1))
        assertTrue(AdmissionHandoff8144.handoffOf8144(mint, t0 + 1_000)!!.open)
        assertEquals(SpecialistElection8136.Terminal8136.DEFERRED_DATA, SpecialistElection8136.terminalOf8136(mint, lane)!!.first)
        assertTrue("the named yield was not completed by missing data", SpecialistElection8136.isNamedReceiver8144(mint, lane))
        assertEquals(0L, HandoffReceiverTasks8153.outcomeCounts8153()["EVALUATED"] ?: 0L)

        SpecialistElection8136.noteNotEvaluated8136(mint, lane, "SHITCOIN", true, 80.0, t0 + 1_100)
        assertEquals("a skipped backoff cycle does not consume the named rescue", lane, SpecialistElection8136.rescueOf8136(mint))
        assertEquals(SpecialistElection8136.Terminal8136.DEFERRED_DATA, SpecialistElection8136.terminalOf8136(mint, lane)!!.first)
        assertTrue(SpecialistElection8136.isNamedReceiver8144(mint, lane))

        CandidateAdmission8136.evidenceArrived8252(mint, setOf("bp"), t0 + 1_500)
        assertFalse("unrelated flow does not answer the liquidity wait", HandoffReceiverTasks8153.isDue8153(mint, lane, t0 + 1_501, 1))
        CandidateAdmission8136.evidenceArrived8252(mint, setOf("liquidity"), t0 + 2_000)
        assertTrue("the real admission backoff and named task recognize the same feature alias",
            CandidateAdmission8136.reevalDueForTest8252(mint, "liq:UNKNOWN", t0 + 2_001))
        val resumed = HandoffReceiverTasks8153.taskOf8153(mint, t0 + 2_001)!!
        assertEquals(deferred.createdAtMs, resumed.createdAtMs)
        assertEquals(deferred.version, resumed.version)
        assertTrue(HandoffReceiverTasks8153.isDue8153(mint, lane, t0 + 2_001, 1))
        assertTrue("the recovered task retains its usable election claim",
            SpecialistElection8136.claimHandoffReceiver8153(mint, lane, "SHITCOIN", null, t0 + 2_001).executable)
        assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()["DATA_ARRIVAL_RESUMED_8397"])
        CandidateAdmission8136.evidenceArrived8252(mint, setOf("liq"), t0 + 2_002)
        assertEquals("repeat arrival cannot multiply the transfer", 1L, HandoffReceiverTasks8153.outcomeCounts8153()["DATA_ARRIVAL_RESUMED_8397"])

        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "SAFETY_REFUSED_8136", t0 + 3_000)
        assertEquals(HandoffReceiverTasks8153.Outcome8153.EVALUATED, HandoffReceiverTasks8153.taskOf8153(mint, t0 + 3_001)!!.outcome)
        assertEquals(AdmissionHandoff8144.State8144.ARRIVED_EVALUATED, AdmissionHandoff8144.handoffOf8144(mint, t0 + 3_001)!!.state)
        assertFalse(HandoffReceiverTasks8153.onEvidenceArrived8397(mint, setOf("liq"), t0 + 4_000))
        assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()["EVALUATED"])
    }

    @Test fun normalFdgDataReceiptAndActualArrivalKeepTheNamedTransfer() {
        pending()
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "DATA_INCOMPLETE_DEFER_8136", t0 + 1_000)
        assertDeferredThenRecover()
    }

    @Test fun earlyFdgAdapterKeepsTheSameDataContract() {
        pending()
        FdgOutcomeContinuity8197.recordEarlyVerdict8397(mint, lane, 1, early("DATA_DEFER_FETCH_NEVER_COMPLETED_8204"), t0 + 1_000)
        assertDeferredThenRecover()
    }

    @Test fun aggregatePairPressureCannotWakeAWaitForAnActualTradePrint() {
        pending(missing = listOf("tape:NO_PRINTS"))
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "DATA_INCOMPLETE_DEFER_8136", t0 + 1_000)
        CandidateAdmission8136.evidenceArrived8252(mint, CandidateAdmission8136.PAIR_FEATURES_8252, t0 + 1_500)
        assertFalse(CandidateAdmission8136.reevalDueForTest8252(mint, "tape:NO_PRINTS", t0 + 1_501))
        assertFalse(HandoffReceiverTasks8153.isDue8153(mint, lane, t0 + 1_501, 1))
        assertEquals(0L, HandoffReceiverTasks8153.outcomeCounts8153()["DATA_ARRIVAL_RESUMED_8397"] ?: 0L)

        CandidateAdmission8136.evidenceArrived8252(mint, setOf("swaps"), t0 + 2_000)
        assertTrue(CandidateAdmission8136.reevalDueForTest8252(mint, "tape:NO_PRINTS", t0 + 2_001))
        assertTrue(HandoffReceiverTasks8153.isDue8153(mint, lane, t0 + 2_001, 1))
        assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()["DATA_ARRIVAL_RESUMED_8397"])
        assertTrue(HandoffReceiverTasks8153.taskOf8153(mint, t0 + 2_001)!!.pending)
        assertEquals(0L, HandoffReceiverTasks8153.outcomeCounts8153()["EVALUATED"] ?: 0L)
        assertTrue(FdgReceipt8397.answersFeature("bp:UNKNOWN", setOf("tape")))
        assertFalse(FdgReceipt8397.answersFeature("swaps:NO_PRINTS", setOf("bp")))
    }

    @Test fun recoveredReceiverCanAllowExactlyOnce() {
        pending()
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "ADMISSION_COST_REQUOTE_UNAVAILABLE_8395", t0 + 1_000)
        CandidateAdmission8136.evidenceArrived8252(mint, setOf("liq"), t0 + 2_000)
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, true, null, t0 + 3_000)
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, true, null, t0 + 3_001)
        assertEquals("FDG_ALLOW", HandoffReceiverTasks8153.taskOf8153(mint, t0 + 3_002)!!.verdict)
        assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()["EVALUATED"])
        assertFalse(SpecialistElection8136.isNamedReceiver8144(mint, lane))
    }

    @Test fun simultaneousEvidenceArrivalsWakeTheTaskOnce() {
        pending()
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "DATA_INCOMPLETE_DEFER_8136", t0 + 1_000)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0..7).map { workers.submit<Boolean> {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                HandoffReceiverTasks8153.onEvidenceArrived8397(mint, setOf("liq"), t0 + 2_000)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            assertEquals(1, jobs.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()["DATA_ARRIVAL_RESUMED_8397"])
            assertTrue(HandoffReceiverTasks8153.isDue8153(mint, lane, t0 + 2_001, 1))
        } finally { workers.shutdownNow() }
    }

    @Test fun dataWaitExpiresBoundedlyAndWrongGenerationCannotSettleIt() {
        pending(2)
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "SAFETY_REFUSED_8136", t0 + 1_000)
        assertTrue(HandoffReceiverTasks8153.taskOf8153(mint, t0 + 1_001)!!.pending)
        assertTrue(AdmissionHandoff8144.handoffOf8144(mint, t0 + 1_001)!!.open)
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 2, false, "DATA_DEFER_BUDGET_EXHAUSTED_8136", t0 + 2_000)
        val expired = HandoffReceiverTasks8153.taskOf8153(mint, t0 + HandoffReceiverTasks8153.TASK_TTL_MS_8153 + 1)!!
        assertEquals(HandoffReceiverTasks8153.Outcome8153.DEFERRED_DATA, expired.outcome)
        assertFalse(HandoffReceiverTasks8153.onEvidenceArrived8397(mint, setOf("liq"), t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153 + 1))
    }

    @Test fun aBindingLaneJudgementStillClosesADeferredElection() {
        pending()
        SpecialistOwnership7951.onFdgVerdict7951(mint, lane, 1, false, "DATA_INCOMPLETE_DEFER_8136", t0 + 1_000)
        SpecialistElection8136.noteGateReason8144(mint, lane, "MEME_PWIN_GATE_6604")
        SpecialistElection8136.noteNotEvaluated8136(mint, lane, "SHITCOIN", true, 80.0, t0 + 1_100)
        assertEquals(SpecialistElection8136.Terminal8136.INELIGIBLE, SpecialistElection8136.terminalOf8136(mint, lane)!!.first)
        assertFalse(SpecialistElection8136.isNamedReceiver8144(mint, lane))
    }

    @Test fun explicitSafetyAndUnknownNegativeProofRemainRefusals() {
        assertEquals(FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, "SAFETY_REFUSED_8136:DATA_INCOMPLETE"))
        assertEquals(FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, "C6_SETUP_DEAD_UNKNOWN"))
        assertEquals(FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, "EXECUTION_ECONOMICS_8136"))
        assertEquals(FdgReceipt8397.Outcome.DEFER_DATA, FdgReceipt8397.outcome(false, "ADMISSION_COST_REQUOTE_UNAVAILABLE_8395"))
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.terminalOf8136("DEFER", "DATA_DEFER_BUDGET_EXHAUSTED_8136"))
        assertEquals("a policy wait is not a missing-data receipt", CandidateAdmission8136.Terminal8136.REFUSE,
            CandidateAdmission8136.terminalOf8136("DEFER", "LIVE_PROVEN_SETUP_ONLY_8059"))
        assertTrue(FdgReceipt8397.answersFeature("bp:UNKNOWN", setOf("tape")))
        assertFalse(FdgReceipt8397.answersFeature("liq:UNKNOWN", setOf("tape")))
    }
}
