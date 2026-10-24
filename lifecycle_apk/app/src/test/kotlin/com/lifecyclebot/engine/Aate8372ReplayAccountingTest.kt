package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForensicReconciliation6635
import com.lifecyclebot.engine.truth.JournalEconomicReplay6619
import com.lifecyclebot.engine.truth.ReconLag8358
import com.lifecyclebot.engine.truth.RevisionedReplayMemo8372
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual replay value and freshness/attribution code; runs in the Android JVM suite, not the adapter harness. */
class Aate8372ReplayAccountingTest {
    private val position = "TRUMP:8372"
    private val rawLeg = BigInteger("60707108925")
    private val ledgerCash = 10.161408
    private val ledgerOpen = 2.0
    private val currentKey = "101|12.0|0:0"

    private fun result(revision: Long, cash: Double, basis: Double, raw: BigInteger) =
        JournalEconomicReplay6619.ReplayResult(
            cashSol = cash, realizedPnlSol = 0.4096, openCostBasisSol = basis, feesSol = 0.248192,
            equitySol = cash + basis, startingCashSol = 12.0, paperRows = 8192, paperBuys = 4096,
            paperSells = 4096, paperPartialSells = 0, emittedAtMs = 0L,
            openBasisByPosition = mapOf(position to basis), openRawQtyByPosition = mapOf(position to raw),
            journalRevision8358 = revision,
        )

    @Before fun resetFreshnessDiagnostics() { ReconLag8358.resetForTest8358() }

    @Test fun the_observed_one_sol_gap_cannot_be_latched_under_a_newer_memo_key() {
        val stale = result(100, ledgerCash + 1.0, 1.0, rawLeg)
        val current = result(101, ledgerCash, ledgerOpen, rawLeg * BigInteger.TWO)
        val memo = RevisionedReplayMemo8372<JournalEconomicReplay6619.ReplayResult> { it.journalRevision8358 }
        assertTrue(memo.publish(currentKey, 101, current))
        assertFalse(memo.publish(currentKey, 101, stale))
        assertEquals(1.0, stale.cashSol - ledgerCash, 1e-9)
        assertEquals(1.0, ledgerOpen - stale.openCostBasisSol, 1e-9)
        assertEquals(rawLeg, current.openRawQtyByPosition.getValue(position) - stale.openRawQtyByPosition.getValue(position))

        var refreshes = 0
        var cleared = false
        val used = ReconLag8358.currentReplay8358(stale, 101, {
            refreshes++
            memo.get(currentKey, 101)
        }, { cleared = true })
        assertSame(current, used)
        assertEquals(1, refreshes)
        assertFalse(cleared)
        assertEquals(0.0, used!!.cashSol - ledgerCash, 0.0)
        assertEquals(0.0, used.openCostBasisSol - ledgerOpen, 0.0)
    }

    @Test fun a_genuine_current_revision_gap_is_preserved_and_names_the_missing_leg() {
        // Same inputs/revision, genuinely absent journal leg: neither the memo nor freshness repair may invent it.
        val missing = result(101, ledgerCash + 1.0, 1.0, rawLeg)
        val memo = RevisionedReplayMemo8372<JournalEconomicReplay6619.ReplayResult> { it.journalRevision8358 }
        assertTrue(memo.publish(currentKey, 101, missing))
        var refreshCalled = false
        val used = requireNotNull(ReconLag8358.currentReplay8358(memo.get(currentKey, 101), 101, {
            refreshCalled = true
            result(101, ledgerCash, ledgerOpen, rawLeg * BigInteger.TWO)
        }, { fail("A current revision must not be reclassified as stale") }))
        assertSame(missing, used)
        assertFalse(refreshCalled)
        val cashDelta = kotlin.math.abs(used.cashSol - ledgerCash)
        val basisDelta = kotlin.math.abs(used.openCostBasisSol - ledgerOpen)
        assertTrue(cashDelta > ForensicReconciliation6635.DELTA_TOLERANCE_SOL)
        assertTrue(basisDelta > ForensicReconciliation6635.DELTA_TOLERANCE_SOL)
        assertEquals("LEDGER_ONLY_BUY_LEG TRUMP 1.000000", ReconLag8358.attribute8358(
            cashDelta, basisDelta, rawLeg,
            used.openBasisByPosition, mapOf(position to ledgerOpen), mapOf(position to "TRUMP"), 0L, "",
        ))
    }
}
