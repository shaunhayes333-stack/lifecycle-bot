package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionSpineAcceptance6647
import com.lifecyclebot.engine.truth.ExecutionSpineAcceptanceWindow6647
import com.lifecyclebot.engine.truth.ForensicReconciliation6635
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/** The META receipt completed after the cached accounting pass: wait for current evidence within the old budget. */
class Aate8397AccountingFreshnessTest {
    private val start = 1_000_000L
    private val committed = "FORENSIC_ACCOUNTING_RECONCILIATION_6635 openInFlight=0 status=RECONCILED"
    private val deltaFailures = listOf("CASH_DELTA", "BASIS_DELTA", "REALIZED_DELTA", "QUANTITY_DELTA")

    private fun completed(key: String = "current", failed: Boolean = false) =
        ForensicReconciliation6635.CompletedAccounting8372(
            key = key,
            cashLedger = 7.981452,
            cashJournal = if (failed) 8.312263 else 7.981452,
            cashDelta = if (failed) 0.330811 else 0.0,
            realizedLedger = 0.409600, realizedJournal = 0.409600, realizedDelta = 0.0,
            openCostLedger = 4.143249,
            openCostJournal = if (failed) 3.814083 else 4.143249,
            openCostDelta = if (failed) 0.329166 else 0.0,
            quantityDeltaRaw = if (failed) BigInteger.valueOf(52_245_500L) else BigInteger.ZERO,
            cashStatus = if (failed) "FAILED" else "RECONCILED",
        )

    private fun capture(
        pass: ForensicReconciliation6635.CompletedAccounting8372,
        current: String = "current",
        after: String = current,
        eventLine: String = committed,
    ) = ForensicReconciliation6635.captureAccounting8397(
        pass, pass.key, current, after, eventLine, "none",
    )

    private fun defer(
        forensic: ForensicReconciliation6635.Deltas6647?, wait: AtomicLong, at: Long,
        young: Boolean = false,
    ) = ExecutionSpineAcceptanceWindow6647.shouldDeferAccounting8397(forensic, young, wait, at)

    /** The same unreconciled sentinels and real evaluator used by closeCompletedWindow. */
    private fun verdict(forensic: ForensicReconciliation6635.Deltas6647?, terminals: Long = 5L) =
        ExecutionSpineAcceptance6647.evaluate(ExecutionSpineAcceptance6647.Observation(
            durationMs = 125_000L, safety = 38L, v3 = 35L, bgSplitRuntimeIntakeZombie = 0L,
            configuredWorkers = 12, currentWorkerHeartbeats = 12, phantomSizedOnly = 0L, sizePending = 0L,
            fdgAllowWithoutIntent = 0L, dispatches = 5L, immutableIntentsForDispatches = 5L,
            terminalResultsForDispatches = terminals, cryptoOpenConfirmed = 0L,
            maxExitStartDelayCycles = 0L, exitStart = 4L, exitDone = 4L, canonicalOpen = 12L,
            exitEvaluations = 6L, supervisorForcedLeaseReleases = 0L,
            cashDeltaSol = if (forensic?.reconciled == true) forensic.cashSol else Double.NaN,
            basisDeltaSol = if (forensic?.reconciled == true) forensic.basisSol else Double.NaN,
            realizedDeltaSol = if (forensic?.reconciled == true) forensic.realizedSol else Double.NaN,
            quantityDeltaRaw = if (forensic?.reconciled == true) forensic.quantityRaw else BigInteger.ONE,
            heroJournalParityFail = 0L, invalidGrowthOrLearningUpdates = 0L,
        ))

    @Test fun staleDivergenceWaitsThenFreshReconciledEvidenceCanPass() {
        val wait = AtomicLong(0L)
        val stale = capture(completed("before-journal-receipt", failed = true))
        assertFalse(stale.accountingCurrent8397)
        assertFalse(stale.reconciled)
        assertTrue("all receipts committed does not make the old pass current", defer(stale, wait, start))
        assertEquals(start, wait.get())
        assertFalse("stale evidence itself never passes", verdict(stale).passed)

        val fresh = capture(completed())
        assertTrue(fresh.accountingCurrent8397)
        assertTrue(fresh.reconciled)
        assertFalse(defer(fresh, wait, start + 5_000L))
        assertTrue(verdict(fresh).passed)
    }

    @Test fun aCurrentRealFailureEndsTheWaitImmediatelyWithoutAYoungReceipt() {
        val wait = AtomicLong(0L)
        assertTrue(defer(capture(completed("old", failed = true)), wait, start, young = true))
        val failure = capture(completed(failed = true))
        assertTrue(failure.accountingCurrent8397)
        assertFalse(failure.cashSideReconciled8397)
        assertFalse(failure.reconciled)
        // The earlier young receipt has now completed.
        // A current persistent failure gets no grace once there is no young OPEN receipt.
        assertFalse(defer(failure, wait, start + 5_000L, young = false))
        assertEquals(start, wait.get())
        assertEquals(deltaFailures, verdict(failure).failures)
    }

    @Test fun permanentlyStaleZeroDeltasFailAtTheOriginalThirtySecondDeadline() {
        val wait = AtomicLong(0L)
        val staleZero = capture(completed("old"))
        for (elapsed in 0L..25_000L step 5_000L) {
            assertTrue(defer(staleZero, wait, start + elapsed))
            assertEquals(start, wait.get())
        }
        assertFalse(defer(staleZero, wait, start + 30_000L))
        assertEquals(start, wait.get())
        assertEquals(deltaFailures, verdict(staleZero).failures)
        assertFalse(defer(staleZero, wait, start + 35_000L))
    }

    @Test fun unknownEvidenceAndYoungReceiptTransitionsShareTheSameBudget() {
        val wait = AtomicLong(0L)
        val youngReceipt = capture(completed(), eventLine = "openInFlight=1 status=FAILED")
        assertTrue(youngReceipt.accountingCurrent8397)
        assertTrue(youngReceipt.cashSideReconciled8397)
        assertTrue(defer(youngReceipt, wait, start, young = true))
        // The receipt arrives, invalidating the previous pass; this does not restart the wait clock.
        assertTrue(defer(capture(completed("before-receipt")), wait, start + 25_000L))
        assertFalse(defer(null, wait, start + 30_000L))
        assertEquals(start, wait.get())
        assertEquals(deltaFailures, verdict(null).failures)

        val unknown = capture(ForensicReconciliation6635.CompletedAccounting8372())
        assertFalse(unknown.accountingCurrent8397)
        assertFalse(unknown.reconciled)
        val unknownWait = AtomicLong(0L)
        assertTrue(defer(unknown, unknownWait, start))
        assertFalse(defer(unknown, unknownWait, start + 30_000L))
    }

    @Test fun freshAccountingDoesNotExcuseAMissingDispatchTerminal() {
        val wait = AtomicLong(0L)
        assertTrue(defer(capture(completed("old")), wait, start))
        val fresh = capture(completed())
        assertFalse(defer(fresh, wait, start + 5_000L))
        assertEquals(listOf("DISPATCH_TERMINAL_CARDINALITY"), verdict(fresh, terminals = 4L).failures)
    }

    @Test fun capturedValuesDiagnosticsAndFreshnessBelongToOneCompletedPass() {
        val pass = completed(failed = true)
        val captured = capture(pass)
        assertTrue(captured.accountingCurrent8397)
        assertEquals(0.330811, captured.cashSol, 0.0)
        assertEquals(0.329166, captured.basisSol, 0.0)
        assertEquals(BigInteger.valueOf(52_245_500L), captured.quantityRaw)
        assertTrue(captured.healthLine8397.contains("cashDelta=${"%.6f".format(captured.cashSol)}"))
        assertTrue(captured.healthLine8397.contains("openCostDelta=${"%.6f".format(captured.basisSol)}"))
        assertTrue(captured.healthLine8397.contains("quantityDeltaRaw=${captured.quantityRaw}"))
        assertTrue(captured.healthLine8397.contains("accountingCurrent=true accountingStatus=FAILED"))
        assertEquals("none", captured.incompleteReceipts8397)

        // A later zero-delta publication cannot rewrite the earlier diagnostic packet.
        val next = capture(completed())
        assertTrue(next.reconciled)
        assertEquals(0.330811, captured.cashSol, 0.0)
        assertTrue(captured.healthLine8397.endsWith("status=FAILED"))
        // A receipt changing during the capture invalidates freshness even if the pass was reconciled.
        val moved = capture(completed(), after = "receipt-changed")
        assertFalse(moved.accountingCurrent8397)
        assertFalse(moved.reconciled)
    }

    @Test fun closerUsesTheCapturedPacketAndCannotRepairTheAccount() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        val close = s.substringAfter("fun closeCompletedWindow(")
        assertTrue(close.contains("shouldDeferAccounting8397(forensic, youngOpen8397, inFlightWaitSinceMs8339, nowMs)"))
        assertTrue(close.contains("scheduleRetry8339(start)"))
        assertTrue(close.contains("forensic?.healthLine8397?.take(520)"))
        assertTrue(close.contains("forensic?.incompleteReceipts8397"))
        assertFalse(close.contains("ForensicReconciliation6635.healthLine6635()"))
        assertFalse(close.contains("reconcile6635("))
        assertFalse(close.contains("reconcileJournalAuthority6663("))
        assertEquals(30_000L, ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_WAIT_GRACE_MS_8339)
        assertEquals(5_000L, ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_RETRY_MS_8339)
    }
}
