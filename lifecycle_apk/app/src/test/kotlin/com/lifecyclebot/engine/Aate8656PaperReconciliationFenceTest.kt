package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Reproduces the 8655 device-smoke ledger/journal lag without a fake profit or tolerant pass. */
class Aate8656PaperReconciliationFenceTest {
    @Before fun before() = synchronized(PaperAccountLedger6430) {
        PaperEconomicAtomicCommit6632.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
    }

    @After fun after() = synchronized(PaperAccountLedger6430) {
        PaperEconomicAtomicCommit6632.resetForTest()
        PaperAccountLedger6430.resetForTest()
    }

    @Test fun buy_debited_while_journal_replay_is_running_cannot_be_undone() = synchronized(PaperAccountLedger6430) {
        val priorOps = PaperAccountLedger6430.snapshotAtomic6643().operationCount
        val priorJournalRevision = TradeHistoryStore.journalRevision7343()
        assertTrue(PaperAccountLedger6430.onBuy(0.107333, 0.002630))
        val debited = PaperAccountLedger6430.snapshotAtomic6643()
        assertFalse(PaperAccountLedger6430.reconcileFromJournal6663(
            cashSol = 10.0, openCostSol = 0.0, realizedSol = 0.0, feesSol = 0.0,
            expectedLedgerOperations8656 = priorOps,
            expectedJournalRevision8656 = priorJournalRevision,
        ))
        val after = PaperAccountLedger6430.snapshotAtomic6643()
        assertEquals(debited.cashSol, after.cashSol, 1e-9)
        assertEquals(debited.openCostBasisSol, after.openCostBasisSol, 1e-9)
        assertEquals(debited.operationCount, after.operationCount)
        assertNull(PaperAccountLedger6430.assertInvariant())
    }

    @Test fun journal_revision_advancing_invalidates_replay_without_overwriting_ledger() {
        assertFalse(PaperAccountLedger6430.reconcileFenceAllows8656(
            expectedLedgerOperations = 8, currentLedgerOperations = 8,
            expectedJournalRevision = 101, currentJournalRevision = 102,
            hasIncompleteEconomicCommit = false,
        ))
        assertTrue(PaperAccountLedger6430.reconcileFenceAllows8656(
            expectedLedgerOperations = 8, currentLedgerOperations = 8,
            expectedJournalRevision = 102, currentJournalRevision = 102,
            hasIncompleteEconomicCommit = false,
        ))
    }

    @Test fun pending_ledger_only_fill_blocks_a_balanced_but_obsolete_replay() {
        val side = PaperEconomicAtomicCommit6632.Side.BUY
        assertEquals(PaperEconomicAtomicCommit6632.Verdict.PROCEED,
            PaperEconomicAtomicCommit6632.stampLedger("unlanded-8656", "MINT8656", side, "test"))
        assertTrue(PaperEconomicAtomicCommit6632.hasUnpairedForReconcile8656())
        val ops = PaperAccountLedger6430.snapshotAtomic6643().operationCount
        val rev = TradeHistoryStore.journalRevision7343()
        assertFalse(PaperAccountLedger6430.reconcileFromJournal6663(10.0, 0.0, 0.0, 0.0, ops, rev))
        assertEquals(PaperEconomicAtomicCommit6632.Verdict.PROCEED,
            PaperEconomicAtomicCommit6632.stampJournal("unlanded-8656", "MINT8656", side, "test"))
        assertFalse(PaperEconomicAtomicCommit6632.hasUnpairedForReconcile8656())
    }
}
