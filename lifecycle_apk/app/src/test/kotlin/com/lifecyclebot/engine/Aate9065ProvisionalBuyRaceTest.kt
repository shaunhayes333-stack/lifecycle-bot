package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperAccountLedger6430 as Ledger
import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632 as Receipts
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** 9064 smoke: reconciliation adopted the pre-MANDY journal after its debit,
 * before its final receipt. That refunded 0.107333334 SOL basis plus its fee. */
class Aate9065ProvisionalBuyRaceTest {
    private val cost = 0.107333334
    private val fee = 0.002629666
    private val key = "9065:MANDY:BUY"

    @Before fun setup() { Receipts.resetForTest(); Ledger.initialize(10.0) }
    @After fun cleanup() { Receipts.resetForTest(); Ledger.resetForTest() }

    private fun oldReplay(token: Ledger.JournalReconcileToken8674) =
        Ledger.reconcileFromJournal6663(10.0, 0.0, 0.0, 0.0, token)

    private fun assertFunded() {
        val s = Ledger.snapshotAtomic6643()
        assertEquals(10.0 - cost - fee, s.cashSol, 1e-8)
        assertEquals(cost, s.openCostBasisSol, 1e-8)
        assertEquals(fee, s.feesSol, 1e-8)
        assertNull(Ledger.assertInvariant())
    }

    @Test fun debitBeforeReceiptCannotBeRefundedEvenWhenReplayStartsAfterDebit() {
        assertTrue(Ledger.onBuyPending9065(cost, fee, key))
        assertFalse("no premature final receipt", Receipts.hasUnpaired8674())
        assertTrue(Ledger.hasPendingBuy9065())
        val capturedInsideGap = Ledger.captureJournalReconcileToken8674()
        assertFalse(oldReplay(capturedInsideGap))
        assertFunded()
    }

    @Test fun finalReceiptTakesOverExclusionWithoutAnUnprotectedGap() {
        assertTrue(Ledger.onBuyPending9065(cost, fee, key))
        val capturedInsideGap = Ledger.captureJournalReconcileToken8674()
        assertFalse(Ledger.finishPendingBuy9065(key))
        assertTrue(Ledger.hasPendingBuy9065())
        Receipts.stampLedger(key, "MANDY", Receipts.Side.BUY, "finalized")
        assertTrue(Ledger.finishPendingBuy9065(key))
        assertFalse(Ledger.hasPendingBuy9065())
        assertTrue(Receipts.hasUnpaired8674())
        assertFalse(oldReplay(Ledger.captureJournalReconcileToken8674()))
        Receipts.stampJournal(key, "MANDY", Receipts.Side.BUY, "durable")
        assertFalse(oldReplay(capturedInsideGap))
        assertTrue(Ledger.reconcileFromJournal6663(10.0-cost-fee, cost, 0.0, fee,
            Ledger.captureJournalReconcileToken8674()))
        assertFunded()
    }

    @Test fun rollbackRefundsOnlyItsOwnDebitOnceAndLeavesNoReceipt() {
        assertTrue(Ledger.onBuyPending9065(cost, fee, key))
        assertTrue(Ledger.onBuyPending9065(0.2, 0.001, "other"))
        assertTrue(Ledger.rollbackPendingBuy9065(key, "proof failed"))
        assertFalse(Ledger.rollbackPendingBuy9065(key, "duplicate"))
        assertTrue(Ledger.hasPendingBuy9065())
        assertEquals(9.799, Ledger.cashSol(), 1e-8)
        assertEquals(0.2, Ledger.openCostBasisSol(), 1e-8)
        assertFalse(Receipts.hasUnpaired8674())
        assertTrue(Ledger.rollbackPendingBuy9065("other", "cancelled"))
        assertFalse(Ledger.hasPendingBuy9065())
        assertEquals(10.0, Ledger.cashSol(), 1e-8)
        assertNull(Ledger.assertInvariant())
    }

    @Test fun duplicateAndInvalidAttemptsNeverDebit() {
        assertFalse(Ledger.onBuyPending9065(cost, fee, ""))
        assertFalse(Ledger.onBuyPending9065(cost, Double.NaN, key))
        assertFalse(Ledger.onBuyPending9065(11.0, fee, key))
        assertFalse(Ledger.hasPendingBuy9065())
        assertTrue(Ledger.onBuyPending9065(cost, fee, key))
        assertFalse(Ledger.onBuyPending9065(cost, fee, key))
        assertFunded()
    }

    @Test fun resetDoesNotLetAnOldRollbackCreditTheNewAccount() {
        assertTrue(Ledger.onBuyPending9065(cost, fee, key))
        val token = Ledger.captureJournalReconcileToken8674()
        Ledger.initialize(10.0)
        assertFalse(Ledger.rollbackPendingBuy9065(key, "old generation"))
        assertFalse(oldReplay(token))
        assertEquals(10.0, Ledger.cashSol(), 1e-8)
    }

    @Test fun executorUsesPendingDebitCompensationAndFinalReceiptHandoff() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val debit = s.indexOf("PaperAccountLedger6430.onBuyPending9065(actualSol, fee6485, entryFinalityId6497)")
        val receipt = s.indexOf("\"Executor.paperBuy.atomic6485.finalized\"", debit)
        val handoff = s.indexOf("PaperAccountLedger6430.finishPendingBuy9065(entryFinalityId6497)", receipt)
        val journal = s.indexOf("recordTrade(ts, trade)", handoff)
        assertTrue(debit > 0 && receipt > debit && handoff > receipt && journal > handoff)
        assertTrue(s.contains("PaperAccountLedger6430.rollbackPendingBuy9065(entryFinalityId6497, reason)"))
    }
}
