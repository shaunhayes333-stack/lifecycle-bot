package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexOutcomeBook8372
import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.LaneRead8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.State8144
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Aate8395HandoffScopeTest {
    @After fun reset() { SpecialistElection8136.resetForTests8136() }

    // ── R7 ──
    @Test fun namedUnavailableCyclicHigherRawScoreStillHandsToMoonshot() {
        val reads = listOf(LaneRead8144("CYCLIC", true, 90, "ok"), LaneRead8144("MOONSHOT", true, 70, "ok"),
            LaneRead8144("TREASURY", false, 80, "ROLE_FLOOR"))
        assertEquals("MOONSHOT", AdmissionHandoff8144.receiver8144("TREASURY", "SHITCOIN", reads))
        assertFalse(AdmissionHandoff8144.schedulable8395("CYCLIC"))
        assertFalse(AdmissionHandoff8144.schedulable8395("CORE"))
        assertTrue(AdmissionHandoff8144.schedulable8395("MOONSHOT"))
        // the task layer applies the same policy: a CYCLIC task cannot be opened
        assertNull(HandoffReceiverTasks8153.open8153(null, "m", "CYCLIC", "SHITCOIN", "FIT", 1, 1000, 1000))
        assertNotNull(HandoffReceiverTasks8153.open8153(null, "m", "MOONSHOT", "SHITCOIN", "FIT", 1, 1000, 1000))
    }
    @Test fun validNamedReceiverIsPreserved() {
        val reads = listOf(LaneRead8144("QUALITY", true, 40, "ok", 55.0), LaneRead8144("MOONSHOT", true, 99, "ok", 95.0))
        assertEquals("QUALITY", AdmissionHandoff8144.receiver8144("QUALITY", "CORE", reads))
    }
    @Test fun onlyExcludedLanesEligibleIsAnExplicitNoCompatibleReceiver() {
        val reads = listOf(LaneRead8144("CYCLIC", true, 90, "ok"), LaneRead8144("CORE", true, 90, "ok"), LaneRead8144("SHITCOIN", false, 10, "X"))
        assertNull(AdmissionHandoff8144.receiver8144(null, "SHITCOIN", reads))
        val h = AdmissionHandoff8144.Handoff8144("m", "SHITCOIN", null, "FIT", 1000)
        val out = AdmissionHandoff8144.onNative8144(h, reads, 1000)
        assertNotEquals(State8144.RECEIVER_QUALIFIED, out.state)
    }
    @Test fun normalizedConvictionNotRawScoreOrdersTheFallback() {
        val reads = listOf(LaneRead8144("BLUECHIP", true, 95, "ok", 51.0), LaneRead8144("EXPRESS", true, 60, "ok", 88.0))
        assertEquals("EXPRESS", AdmissionHandoff8144.receiver8144(null, "CORE", reads))
        val tie = listOf(LaneRead8144("QUALITY", true, 70, "ok"), LaneRead8144("DIP_HUNTER", true, 70, "ok"))
        assertEquals("DIP_HUNTER", AdmissionHandoff8144.receiver8144(null, "CORE", tie))
    }
    @Test fun qualificationWithoutCommittedTaskIsRetryableNotClaimed() {
        val h = AdmissionHandoff8144.Handoff8144("m", "SHITCOIN", null, "FIT", 1000, receiver = "MOONSHOT", state = State8144.RECEIVER_QUALIFIED)
        val back = AdmissionHandoff8144.taskNotCommitted8395(h, "MOONSHOT")
        assertEquals(State8144.EMITTED, back.state); assertNull(back.receiver); assertTrue(back.open)
        assertTrue(back.verdict.startsWith("TASK_NOT_COMMITTED_8395"))
    }
    @Test fun freshMaterialEvidenceReopensAnUnevaluatedLedgerOnceOnly() {
        val expired = AdmissionHandoff8144.Handoff8144("m", "SHITCOIN", null, "FIT", 1000, state = State8144.EXPIRED_NOT_EVALUATED,
            verdict = "NAMED_-_NEVER_EVALUATED", settledAtMs = 5000)
        val sameRefusal = listOf(LaneRead8144("MOONSHOT", false, 30, "PRICE_STALE"))
        assertSame(expired, AdmissionHandoff8144.reopenOnMaterial8395(expired, sameRefusal, 6000))
        assertSame("a read older than the settlement is not new evidence", expired,
            AdmissionHandoff8144.reopenOnMaterial8395(expired, listOf(LaneRead8144("MOONSHOT", true, 80, "ok")), 4000))
        val reopened = AdmissionHandoff8144.reopenOnMaterial8395(expired, listOf(LaneRead8144("MOONSHOT", true, 80, "ok")), 6000)
        assertEquals(State8144.EMITTED, reopened.state); assertTrue(reopened.reopened8395)
        val again = reopened.copy(state = State8144.EXPIRED_NOT_EVALUATED, settledAtMs = 7000)
        assertSame(again, AdmissionHandoff8144.reopenOnMaterial8395(again, listOf(LaneRead8144("MOONSHOT", true, 80, "ok")), 8000))
        val judged = expired.copy(state = State8144.ARRIVED_EVALUATED, verdict = "FDG_REFUSED:RUG")
        assertSame("settled judgements never reopen", judged,
            AdmissionHandoff8144.reopenOnMaterial8395(judged, listOf(LaneRead8144("MOONSHOT", true, 80, "ok")), 9000))
    }

    // ── R6 ──
    @Test fun dataDeferredPrimaryKeepsRescueOpenAndOnlyMaterialRecoveryClearsIt() {
        val judgement: (String) -> Boolean = { it.contains("RUG") }
        assertEquals(SpecialistElection8136.RefusalScope8395.DATA,
            SpecialistElection8136.refusalScope8395("NATIVE_MCAP_UNHYDRATED_DEFER_8236", false, false, judgement))
        assertEquals(SpecialistElection8136.RefusalScope8395.JUDGEMENT,
            SpecialistElection8136.refusalScope8395("RUG_PULL", true, false, judgement))
        assertEquals(SpecialistElection8136.RefusalScope8395.FIT,
            SpecialistElection8136.refusalScope8395("LANE_MCAP_ABOVE_BAND", true, false, judgement))
        SpecialistElection8136.notePrimaryRefusal8136("mint8395", "MOONSHOT", "NATIVE:NATIVE_MCAP_UNHYDRATED_DEFER_8236", false, 1000, dataScoped8395 = true)
        val st = SpecialistElection8136.primaryState8395("mint8395")!!
        assertFalse("data never becomes a binding judgement", st.second); assertTrue(st.third)
        assertTrue("a qualified alternate can still be evaluated", SpecialistElection8136.rescueOpen8395("mint8395"))
        SpecialistElection8136.clearPrimaryDataDefer8395("mint8395", "MOONSHOT")
        assertEquals("", SpecialistElection8136.primaryState8395("mint8395")!!.first)
    }
    @Test fun judgementStillBindsAndIsNotClearedByRecovery() {
        SpecialistElection8136.notePrimaryRefusal8136("mintJ", "MOONSHOT", "NATIVE:RUG_PULL", true, 1000)
        SpecialistElection8136.clearPrimaryDataDefer8395("mintJ", "MOONSHOT")
        val st = SpecialistElection8136.primaryState8395("mintJ")!!
        assertTrue(st.second); assertFalse(SpecialistElection8136.rescueOpen8395("mintJ"))
    }

    // ── R1 (outcome ownership) ──
    @Test fun unavailableShadowDoesNotSuppressTheValidForwardOfTheSameDecision() {
        val b = CortexOutcomeBook8372()
        assertTrue(b.reserve("s1", 1000)); assertTrue(b.bind("s1", "f1"))
        assertTrue(b.finishedShadow("s1", learned = false))
        assertEquals(CortexOutcomeBook8372.Action.LEARN, b.forwardPlan("f1").action)
        b.learnedForward("f1")
        assertEquals("duplicate forward is observed only", CortexOutcomeBook8372.Action.OBSERVE_ONLY, b.forwardPlan("f1").action)
    }
    @Test fun forwardThenSelectedShadowRevisesOneContribution() {
        val b = CortexOutcomeBook8372()
        b.reserve("s2", 1000); b.bind("s2", "f2"); b.learnedForward("f2")
        val plan = b.shadowPlan("s2")
        assertEquals(CortexOutcomeBook8372.Action.REVISE, plan.action); assertEquals("f2", plan.decisionId)
    }
    @Test fun wrongEpisodeCannotBindAnotherDecision() {
        val b = CortexOutcomeBook8372()
        b.reserve("s3", 1000); assertTrue(b.bind("s3", "f3"))
        assertFalse(b.bind("s3", "f-other"))
        b.reserve("s4", 2000); assertFalse("a label owned by s3 cannot move to s4", b.bind("s4", "f3"))
    }
    @Test fun runtimeUsesContributionReceiptNotEligibilityAsLearnedProof() {
        val c = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertFalse(c.contains("gradeBook8144.decision(id8372)?.graded == true"))
        assertTrue(c.contains("gradeBook8144.decision(id8372)?.modelContribution8395 == id8372"))
        assertTrue(c.contains("modelContribution8395 = \"\$key|\${p.atMs}\""))
    }
}
