package com.lifecyclebot.engine

import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Task8153
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8419 §A_DATA_WAIT_IS_NOT_A_VERDICT — a receiver task's data windows agree with the fetch they wait for, a spent
 * wait is not the receiver's evaluation, the data's arrival reopens the same task, and a transfer does not carry the
 * refusing lane's wait. Behaviour on the pure task functions and the bounded runtime table.
 */
class Aate8419HandoffDataWaitTest {
    private val t0 = 1_700_000_000_000L

    private fun opened(mint: String = "mint-8419"): Task8153 =
        HandoffReceiverTasks8153.open8153(null, mint, "MOONSHOT", "PROJECT_SNIPER", "PHASE", 10L, t0, t0)!!

    @Test fun spentDataWaitSettlesDeferredDataNotEvaluated() {
        val t = opened()
        for (reason in listOf(CandidateAdmission8136.DATA_NEVER_ARRIVED_8418, CandidateAdmission8136.DATA_WAIT_SPENT_8418)) {
            // The receipt stays a REFUSE for the election and the ledger (8418 contract) ...
            assertEquals(FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, reason))
            assertTrue(reason, HandoffReceiverTasks8153.spentDataWait8419(reason))
            // ... but the task ends DEFERRED_DATA by name, with what it waited for, not as the receiver's evaluation.
            val settled = HandoffReceiverTasks8153.onFdg8153(t, "MOONSHOT", false, reason, t0 + 1_000L,
                outcome = FdgReceipt8397.outcome(false, reason), missing = setOf("liq:UNKNOWN"))
            assertFalse(settled.pending)
            assertEquals(Outcome8153.DEFERRED_DATA, settled.outcome)
            assertTrue(settled.verdict, settled.verdict.startsWith(HandoffReceiverTasks8153.SPENT_PREFIX_8419))
            assertEquals(setOf("liq:UNKNOWN"), settled.missingFeatures8397)
            // A fresh native read after the settlement reopens it (the 120 s hold is for judged tasks only).
            assertTrue(HandoffReceiverTasks8153.reopensOnFreshEvidence8190(settled, t0 + 2_000L))
        }
        assertFalse(HandoffReceiverTasks8153.spentDataWait8419("ENTRY8114_LANE_REALISED_LOSING_BINDS_8329"))
        val judged = HandoffReceiverTasks8153.onFdg8153(t, "MOONSHOT", false, "ENTRY8114_LANE_REALISED_LOSING_BINDS_8329", t0 + 1_000L)
        assertEquals(Outcome8153.EVALUATED, judged.outcome)
        assertNull(HandoffReceiverTasks8153.reopen8419(judged, setOf("liq"), t0 + 2_000L))
    }

    @Test fun arrivalOfTheMissingFeatureReopensTheSameTaskInsideItsMaxAge() {
        val t = opened()
        val settled = HandoffReceiverTasks8153.onFdg8153(t, "MOONSHOT", false, CandidateAdmission8136.DATA_NEVER_ARRIVED_8418, t0 + 1_000L,
            outcome = FdgReceipt8397.Outcome.REFUSE, missing = setOf("liq:UNKNOWN"))
        // Another feature does not answer it.
        assertNull(HandoffReceiverTasks8153.reopen8419(settled, setOf("shape"), t0 + 3_000L))
        // The liquidity reading does: same mint / lane / from / reason / version, pending, valid TTL from now.
        val reopened = HandoffReceiverTasks8153.reopen8419(settled, setOf("liquidity"), t0 + 3_000L)
        assertNotNull(reopened)
        assertTrue(reopened!!.pending)
        assertEquals(Outcome8153.PENDING, reopened.outcome)
        assertEquals(t.mint, reopened.mint); assertEquals(t.lane, reopened.lane); assertEquals(t.from, reopened.from)
        assertEquals(t.reason, reopened.reason); assertEquals(t.version, reopened.version); assertEquals(t.createdAtMs, reopened.createdAtMs)
        assertEquals(t0 + 3_000L + HandoffReceiverTasks8153.TASK_TTL_MS_8153, reopened.validUntilMs)
        assertEquals("", reopened.verdict); assertEquals(0, reopened.schedules); assertEquals(0L, reopened.dataDeferredAtMs8397)
        assertTrue(reopened.missingFeatures8397.isEmpty())
        // Never past the max age from creation, never before the settlement, never a pending task.
        assertNull(HandoffReceiverTasks8153.reopen8419(settled, setOf("liq"), t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153 + 1L))
        assertNull(HandoffReceiverTasks8153.reopen8419(settled, setOf("liq"), t0 + 500L))
        assertNull(HandoffReceiverTasks8153.reopen8419(t, setOf("liq"), t0 + 3_000L))
        assertNull(HandoffReceiverTasks8153.reopen8419(null, setOf("liq"), t0 + 3_000L))
    }

    @Test fun runtimeArrivalReopensAndCountsIt() {
        HandoffReceiverTasks8153.resetForTests8153()
        val mint = "So11111111111111111111111111111111111118419"
        val opened = HandoffReceiverTasks8153.openTask8153(mint, "MOONSHOT", "PROJECT_SNIPER", "PHASE", 10L, t0, t0)
        assertNotNull(opened); assertTrue(opened!!.pending)
        // A spent wait relayed by the FDG adapter settles it DEFERRED_DATA.
        HandoffReceiverTasks8153.onFdgVerdict8153(mint, "MOONSHOT", false, CandidateAdmission8136.DATA_NEVER_ARRIVED_8418, t0 + 1_000L, 10L,
            FdgReceipt8397.Outcome.REFUSE)
        val settled = HandoffReceiverTasks8153.taskOf8153(mint, t0 + 1_500L)!!
        assertEquals(Outcome8153.DEFERRED_DATA, settled.outcome)
        assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()[Outcome8153.DEFERRED_DATA.name])
        assertNull(HandoffReceiverTasks8153.outcomeCounts8153()[Outcome8153.EVALUATED.name])
        // The receipt kept no missing set here (no held admission record in this JVM): any arrival answers it.
        assertTrue(HandoffReceiverTasks8153.onEvidenceArrived8397(mint, setOf("liq"), t0 + 4_000L))
        val again = HandoffReceiverTasks8153.taskOf8153(mint, t0 + 4_500L)!!
        assertTrue(again.pending)
        assertEquals(10L, again.version)
        assertEquals(1L, HandoffReceiverTasks8153.outcomeCounts8153()["DATA_ARRIVAL_REOPENED_8419"])
        assertTrue(HandoffReceiverTasks8153.isDue8153(mint, "MOONSHOT", t0 + 5_000L, 10L))
        assertTrue(HandoffReceiverTasks8153.statusLine8153(t0 + 5_000L).contains("dataReopened8419=1"))
        // A second arrival on a pending task without a data wait does nothing.
        assertFalse(HandoffReceiverTasks8153.onEvidenceArrived8397(mint, setOf("liq"), t0 + 6_000L))
        HandoffReceiverTasks8153.resetForTests8153()
    }

    @Test fun aDataDeferralsValidityCoversTheFetchItWaitsFor() {
        val t = opened()
        assertEquals(t0 + HandoffReceiverTasks8153.TASK_TTL_MS_8153, t.validUntilMs)
        // Deferred 1 s before expiry: the task now lives one input-refresh window past the deferral, retry in 5 s.
        val at = t0 + HandoffReceiverTasks8153.TASK_TTL_MS_8153 - 1_000L
        val deferred = HandoffReceiverTasks8153.onFdg8153(t, "MOONSHOT", false, "DATA_INCOMPLETE_DEFER_8136", at,
            outcome = FdgReceipt8397.Outcome.DEFER_DATA, missing = setOf("shape"))
        assertTrue(deferred.pending)
        assertEquals(at + HandoffReceiverTasks8153.DATA_WAIT_MS_8419, deferred.validUntilMs)
        assertEquals(at + 5_000L, deferred.dataRetryAtMs8397)
        assertTrue(deferred.validUntilMs - at >= HandoffReceiverTasks8153.DATA_WAIT_MS_8419)
        // Still pending at the old expiry; DEFERRED_DATA by name once the covered window is spent.
        assertSame(deferred, HandoffReceiverTasks8153.expire8153(deferred, t0 + HandoffReceiverTasks8153.TASK_TTL_MS_8153 + 1L))
        assertEquals(Outcome8153.DEFERRED_DATA, HandoffReceiverTasks8153.expire8153(deferred, deferred.validUntilMs + 1L).outcome)
        // Never past the max age from creation.
        val late = t.copy(validUntilMs = t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153)
        val lateAt = t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153 - 10_000L
        assertEquals(t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153, HandoffReceiverTasks8153.dataWaitUntil8419(late, lateAt))
        // A deferral early in the validity never shortens it.
        assertEquals(t.validUntilMs, HandoffReceiverTasks8153.dataWaitUntil8419(t, t0 + 1_000L))
    }

    @Test fun aTransferCarriesTheClaimNotTheRefusingLanesDataWait() {
        val t = opened()
        val deferred = HandoffReceiverTasks8153.onFdg8153(t, "MOONSHOT", false, "DATA_INCOMPLETE_DEFER_8136", t0 + 1_000L,
            outcome = FdgReceipt8397.Outcome.DEFER_DATA, missing = setOf("shape"))
        assertTrue(deferred.dataDeferredAtMs8397 > 0L)
        val moved = HandoffReceiverTasks8153.transfer8153(deferred, "MOONSHOT", "LANE_QUARANTINE_MOONSHOT", mapOf("SHITCOIN" to 50.0), t0 + 2_000L)
        assertTrue(moved.pending)
        assertEquals("SHITCOIN", moved.lane)
        // The claim's evidence survives ...
        assertEquals("PROJECT_SNIPER", moved.from); assertEquals("PHASE", moved.reason); assertEquals(10L, moved.version)
        assertEquals(t.createdAtMs, moved.createdAtMs); assertTrue(moved.tried.contains("MOONSHOT"))
        // ... the previous lane's FDG wait does not: the new receiver is due at once, and no stale missing set gates its resume.
        assertEquals(0L, moved.dataDeferredAtMs8397); assertEquals(0L, moved.dataRetryAtMs8397); assertTrue(moved.missingFeatures8397.isEmpty())
        assertTrue(HandoffReceiverTasks8153.due8153(moved, "SHITCOIN", t0 + 2_001L, 10L))
    }
}
