package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionSpineAcceptance6647
import com.lifecyclebot.engine.truth.ExecutionSpineAcceptanceWindow6647
import com.lifecyclebot.engine.truth.ForensicReconciliation6635
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/** Runtime 6596: a current accounting pass caught a 2.467-second OPEN between its ledger and journal writes. */
class Aate8398YoungReceiptGraceTest {
    private val start = 1_000_000L
    private val deltaFailures = listOf("CASH_DELTA", "BASIS_DELTA", "REALIZED_DELTA", "QUANTITY_DELTA")

    // The emitted values are rounded independently; keep the actual reported deltas from the failed witness.
    private fun pendingPass(key: String = "current") = ForensicReconciliation6635.CompletedAccounting8372(
        key = key,
        cashLedger = 6.525635, cashJournal = 6.623884, cashDelta = 0.098250,
        realizedLedger = 0.409600, realizedJournal = 0.409600, realizedDelta = 0.0,
        openCostLedger = 5.589480, openCostJournal = 5.491719, openCostDelta = 0.097761,
        quantityDeltaRaw = BigInteger.valueOf(49_876_548L), cashStatus = "FAILED",
    )

    private fun capture(
        pass: ForensicReconciliation6635.CompletedAccounting8372 = pendingPass(),
        currentKey: String = "current",
        journalPending: Boolean = true,
    ) = ForensicReconciliation6635.captureAccounting8397(
        pass, pass.key, currentKey, currentKey,
        if (journalPending) "openInFlight=1 status=FAILED" else "openInFlight=0 status=RECONCILED",
        if (journalPending) "id=PAPER6486:OPEN:STOCK_1793034091927;terminal=OPEN;ageMs=2467;pos=true;led=true;jrn=false;flt=true;term=true"
        else "none",
    )

    private fun defer(
        forensic: ForensicReconciliation6635.Deltas6647?, wait: AtomicLong, at: Long, young: Boolean,
    ) = ExecutionSpineAcceptanceWindow6647.shouldDeferAccounting8397(forensic, young, wait, at)

    /** The actual witness's other invariants pass; unreconciled accounting still uses the real closer's sentinels. */
    private fun verdict(forensic: ForensicReconciliation6635.Deltas6647) =
        ExecutionSpineAcceptance6647.evaluate(ExecutionSpineAcceptance6647.Observation(
            durationMs = 122_007L, safety = 68L, v3 = 62L, bgSplitRuntimeIntakeZombie = 0L,
            configuredWorkers = 12, currentWorkerHeartbeats = 12, phantomSizedOnly = 0L, sizePending = 0L,
            fdgAllowWithoutIntent = 0L, dispatches = 11L, immutableIntentsForDispatches = 11L,
            terminalResultsForDispatches = 11L, cryptoOpenConfirmed = 0L,
            maxExitStartDelayCycles = 0L, exitStart = 4L, exitDone = 4L, canonicalOpen = 23L,
            exitEvaluations = 33L, supervisorForcedLeaseReleases = 0L,
            cashDeltaSol = if (forensic.reconciled) forensic.cashSol else Double.NaN,
            basisDeltaSol = if (forensic.reconciled) forensic.basisSol else Double.NaN,
            realizedDeltaSol = if (forensic.reconciled) forensic.realizedSol else Double.NaN,
            quantityDeltaRaw = if (forensic.reconciled) forensic.quantityRaw else BigInteger.ONE,
            heroJournalParityFail = 0L, invalidGrowthOrLearningUpdates = 0L,
        ))

    @Test fun currentFailureWithoutAYoungReceiptDoesNotStartGrace() {
        val wait = AtomicLong(0L)
        val failure = capture(journalPending = false)
        assertTrue(failure.accountingCurrent8397)
        assertFalse(failure.reconciled)
        assertFalse(defer(failure, wait, start, young = false))
        assertEquals(0L, wait.get())
        assertEquals(deltaFailures, verdict(failure).failures)
    }

    @Test fun youngCurrentJournalGapWaitsThenFreshCompletionCanPass() {
        val wait = AtomicLong(0L)
        val gap = capture()
        assertTrue(gap.accountingCurrent8397)
        assertFalse(gap.cashSideReconciled8397)
        assertEquals(0.098250, gap.cashSol, 0.0)
        assertEquals(0.097761, gap.basisSol, 0.0)
        assertEquals(BigInteger.valueOf(49_876_548L), gap.quantityRaw)
        assertTrue(defer(gap, wait, start, young = true))
        assertEquals(start, wait.get())
        assertEquals(deltaFailures, verdict(gap).failures)

        val pass = pendingPass("after-journal")
        val fresh = capture(pass.copy(
            cashJournal = pass.cashLedger, cashDelta = 0.0,
            openCostJournal = pass.openCostLedger, openCostDelta = 0.0,
            quantityDeltaRaw = BigInteger.ZERO, cashStatus = "RECONCILED",
        ), currentKey = "after-journal", journalPending = false)
        assertTrue(fresh.reconciled)
        assertFalse(defer(fresh, wait, start + 5_000L, young = false))
        assertTrue(verdict(fresh).passed)
    }

    @Test fun completedReceiptCannotHideAPersistentCurrentFailure() {
        val wait = AtomicLong(0L)
        assertTrue(defer(capture(), wait, start, young = true))
        val failure = capture(pendingPass("after-journal"), currentKey = "after-journal", journalPending = false)
        assertTrue(failure.accountingCurrent8397)
        assertFalse(failure.reconciled)
        assertFalse(defer(failure, wait, start + 5_000L, young = false))
        assertEquals(start, wait.get())
        assertEquals(deltaFailures, verdict(failure).failures)
    }

    @Test fun currentYoungAndStaleGapsShareTheOriginalThirtySecondDeadline() {
        val wait = AtomicLong(0L)
        val gap = capture()
        val stale = capture(pendingPass("before-journal"), journalPending = false)
        assertFalse(stale.accountingCurrent8397)
        assertTrue(defer(gap, wait, start, young = true))
        assertTrue(defer(stale, wait, start + 5_000L, young = false))
        for (elapsed in 10_000L..25_000L step 5_000L) {
            assertTrue(defer(gap, wait, start + elapsed, young = true))
            assertEquals(start, wait.get())
        }
        assertFalse(defer(gap, wait, start + 30_000L, young = true))
        assertEquals(start, wait.get())
        assertEquals(deltaFailures, verdict(gap).failures)
        assertFalse(defer(stale, wait, start + 35_000L, young = false))
        assertEquals(deltaFailures, verdict(stale).failures)
        assertEquals(30_000L, ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_WAIT_GRACE_MS_8339)
        assertEquals(5_000L, ExecutionSpineAcceptanceWindow6647.IN_FLIGHT_RETRY_MS_8339)
    }
}
