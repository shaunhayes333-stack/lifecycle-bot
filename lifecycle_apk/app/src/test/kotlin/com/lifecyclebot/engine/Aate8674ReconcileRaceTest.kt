package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperAccountLedger6430 as Ledger
import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632 as Receipts
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 8673 runtime: CLMFND's close was erased by a replay prepared before its
 * journal arrived. Reproduce both interleavings against the real ledger. */
class Aate8674ReconcileRaceTest {
    private val basis = 0.107333334
    private val gross = 0.059657334
    private val fee = 0.001551334
    private val key = "8674:CLMFND:SELL"

    @Before fun setup() {
        Receipts.resetForTest()
        Ledger.initialize(10.0)
        assertTrue(Ledger.onBuy(basis))
    }

    @After fun cleanup() {
        Receipts.resetForTest()
        Ledger.resetForTest()
    }

    private fun sell() = assertTrue(Ledger.onSellAtomic6632(
        gross, basis, fee, "CLMFND", key, enforceSolanaMintQuarantine = false,
    ))

    private fun journal() {
        Receipts.stampJournal(key, "CLMFND", Receipts.Side.SELL, "durable_test")
    }

    private fun oldReplay(token: Ledger.JournalReconcileToken8674) =
        Ledger.reconcileFromJournal6663(10.0 - basis, basis, 0.0, 0.0, token)

    private fun assertCloseRetained() {
        val s = Ledger.snapshotAtomic6643()
        assertEquals(10.0 - basis + gross - fee, s.cashSol, 1e-8)
        assertEquals(0.0, s.openCostBasisSol, 1e-8)
        assertEquals(-0.047676, s.realizedPnlSol, 1e-8)
        assertEquals(fee, s.feesSol, 1e-8)
        assertNull(Ledger.assertInvariant())
    }

    @Test fun completedConcurrentCloseInvalidatesEarlierReplay() {
        val token = Ledger.captureJournalReconcileToken8674()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val writer = Thread {
            try { sell(); journal() } catch (t: Throwable) { error.set(t) }
            finally { done.countDown() }
        }
        writer.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        writer.join(1000)
        error.get()?.let { throw it }
        assertFalse(Receipts.hasUnpaired8674())
        assertFalse(oldReplay(token))
        assertCloseRetained()
    }

    @Test fun closeAlreadyInFlightBeforeCaptureCannotBeOverwritten() {
        sell()
        val token = Ledger.captureJournalReconcileToken8674()
        assertTrue(Receipts.hasUnpaired8674())
        assertFalse(oldReplay(token))
        assertCloseRetained()
        journal()
        val settled = Ledger.captureJournalReconcileToken8674()
        assertTrue(Ledger.reconcileFromJournal6663(
            10.0 - basis + gross - fee, 0.0, gross - basis, fee, settled,
        ))
        assertCloseRetained()
    }

    @Test fun concurrentBuyCannotBeRefundedByEarlierReplay() {
        val token = Ledger.captureJournalReconcileToken8674()
        assertTrue(Ledger.onBuy(0.2))
        assertFalse(oldReplay(token))
        assertEquals(basis + 0.2, Ledger.openCostBasisSol(), 1e-8)
    }

    @Test fun resetAndIdenticalReopenCannotReuseOldToken() {
        val token = Ledger.captureJournalReconcileToken8674()
        Ledger.initialize(10.0)
        assertTrue(Ledger.onBuy(basis))
        // Same cash/basis/opCount as before the reset, different generation.
        assertFalse(oldReplay(token))
    }

    @Test fun stableReplayStillRepairsScalarsAndPreservesTreasury() {
        assertEquals(0.5, Ledger.moveCashToTreasury7294(0.5, "test"), 1e-9)
        val token = Ledger.captureJournalReconcileToken8674()
        assertTrue(Ledger.reconcileFromJournal6663(9.0, 0.5, -0.4, 0.1, token))
        val s = Ledger.snapshotAtomic6643()
        assertEquals(8.5, s.cashSol, 1e-9)
        assertEquals(0.5, s.treasurySol, 1e-9)
        assertNull(Ledger.assertInvariant())
        assertFalse(oldReplay(token))
    }

    @Test fun journalOnlyReceiptCannotBeAdoptedAsCompleteEconomics() {
        journal()
        assertTrue(Receipts.hasUnpaired8674())
        assertFalse(oldReplay(Ledger.captureJournalReconcileToken8674()))
        assertEquals(basis, Ledger.openCostBasisSol(), 1e-8)
    }
}
