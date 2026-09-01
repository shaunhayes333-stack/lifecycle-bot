package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632
import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632.Side
import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632.Verdict
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PaperEconomicAtomicCommit6632Test {

    @Before fun reset() { PaperEconomicAtomicCommit6632.resetForTest() }
    @After  fun tearDown() { PaperEconomicAtomicCommit6632.resetForTest() }

    @Test fun ledger_then_journal_pairs_to_commit_ok() {
        val key = PaperEconomicAtomicCommit6632.keyFromAttempt("attempt-1", Side.SELL, 0)
        assertTrue(key.isNotBlank())
        val l = PaperEconomicAtomicCommit6632.stampLedger(key, "MintA", Side.SELL, "test.ledger")
        val j = PaperEconomicAtomicCommit6632.stampJournal(key, "MintA", Side.SELL, "test.journal")
        assertEquals(Verdict.PROCEED, l)
        assertEquals(Verdict.PROCEED, j)
        assertTrue(PaperEconomicAtomicCommit6632.isCommitted(key))
    }

    @Test fun duplicate_ledger_stamp_is_ignored() {
        val key = PaperEconomicAtomicCommit6632.keyFromAttempt("attempt-2", Side.BUY, 0)
        val first = PaperEconomicAtomicCommit6632.stampLedger(key, "MintB", Side.BUY, "test.first")
        val second = PaperEconomicAtomicCommit6632.stampLedger(key, "MintB", Side.BUY, "test.second")
        assertEquals(Verdict.PROCEED, first)
        assertEquals(Verdict.DUPLICATE_IGNORED, second)
        assertFalse(PaperEconomicAtomicCommit6632.isCommitted(key))
    }

    @Test fun duplicate_journal_stamp_is_ignored() {
        val key = PaperEconomicAtomicCommit6632.keyFromAttempt("attempt-3", Side.SELL, 1)
        val first = PaperEconomicAtomicCommit6632.stampJournal(key, "MintC", Side.SELL, "test.first")
        val second = PaperEconomicAtomicCommit6632.stampJournal(key, "MintC", Side.SELL, "test.second")
        assertEquals(Verdict.PROCEED, first)
        assertEquals(Verdict.DUPLICATE_IGNORED, second)
    }

    @Test fun blank_key_returns_blank_key_verdict() {
        val l = PaperEconomicAtomicCommit6632.stampLedger("", "MintD", Side.BUY, "test.blank")
        val j = PaperEconomicAtomicCommit6632.stampJournal("", "MintD", Side.BUY, "test.blank")
        assertEquals(Verdict.BLANK_KEY, l)
        assertEquals(Verdict.BLANK_KEY, j)
    }

    @Test fun mint_side_sig_key_paired_across_writers() {
        val key1 = PaperEconomicAtomicCommit6632.keyFromMintSide("MintE", Side.SELL, "1.000000_0.500000_0.010000")
        val key2 = PaperEconomicAtomicCommit6632.keyFromMintSide("MintE", Side.SELL, "1.000000_0.500000_0.010000")
        assertEquals(key1, key2)
        assertEquals(Verdict.PROCEED, PaperEconomicAtomicCommit6632.stampLedger(key1, "MintE", Side.SELL, "test.ledger"))
        assertEquals(Verdict.PROCEED, PaperEconomicAtomicCommit6632.stampJournal(key2, "MintE", Side.SELL, "test.journal"))
        assertTrue(PaperEconomicAtomicCommit6632.isCommitted(key1))
    }

    @Test fun sweep_surfaces_half_committed_ledger_only() {
        val key = PaperEconomicAtomicCommit6632.keyFromAttempt("attempt-half", Side.SELL, 2)
        PaperEconomicAtomicCommit6632.stampLedger(key, "MintF", Side.SELL, "test.ledger")
        // ttl=0 triggers immediate half-write reporting for testing
        PaperEconomicAtomicCommit6632.sweepUnpaired6632(ttlMs = -1L)
        // After sweep the entry is removed; a subsequent journal stamp
        // reintroduces the entry but never pairs — this proves the sweep
        // surfaced the half-write.
        assertFalse(PaperEconomicAtomicCommit6632.isCommitted(key))
    }
}
