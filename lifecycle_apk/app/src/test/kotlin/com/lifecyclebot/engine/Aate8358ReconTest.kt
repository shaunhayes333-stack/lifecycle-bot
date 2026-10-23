package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.JournalEconomicReplay6619
import com.lifecyclebot.engine.truth.ReconLag8358
import java.io.File
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8358 — CI 5.0.8356: cashDelta=0.129750 openCostDelta=0.129105 quantityDeltaRaw=84930968, all receipts
 * committed. 0.129105 x 0.005 = 0.000645 is Executor.paperTopUp's fee on a CanonicalPaperTransaction6486.add leg.
 * A replay computed before that leg was compared against the ledger and key after it, and the unchanged-state guard
 * then kept the FAILED verdict.
 */
class Aate8358ReconTest {

    private val start = 12.0
    private val addCost = 0.129105
    private val addFee = addCost * 0.005

    private fun replay(cash: Double, openCost: Double, rev: Long, basis: Map<String, Double>) =
        JournalEconomicReplay6619.ReplayResult(
            cashSol = cash, realizedPnlSol = 0.4096, openCostBasisSol = openCost, feesSol = 0.0,
            equitySol = cash + openCost, startingCashSol = start, paperRows = 8193, paperBuys = 4097,
            paperSells = 4096, paperPartialSells = 0, emittedAtMs = 0L,
            openBasisByPosition = basis, journalRevision8358 = rev,
        )

    @Before fun reset() { ReconLag8358.resetForTest8358() }

    @Test fun stale_replay_reproduces_the_ci_gap_and_the_fresh_replay_agrees() {
        // Writer sequence: worker replays at revision R; add() debits the ledger, grows the position and appends its
        // journal row (revision R+1); the worker then reconciles with the revision-R replay.
        val baseCash = 3.694715
        val baseOpen = 8.410743
        val ledgerCash = baseCash - addCost - addFee
        val ledgerOpen = baseOpen + addCost
        val stale = replay(baseCash, baseOpen, rev = 100L, basis = mapOf("P" to 0.2))
        val fresh = replay(ledgerCash, ledgerOpen, rev = 101L, basis = mapOf("P" to 0.2 + addCost))

        // Before the fix the stale replay was the evidence.
        assertEquals(0.129750, kotlin.math.abs(stale.cashSol - ledgerCash), 1e-6)
        assertEquals(0.129105, kotlin.math.abs(stale.openCostBasisSol - ledgerOpen), 1e-6)

        var keyCleared = false
        val used = ReconLag8358.currentReplay8358(stale, 101L, { fresh }, { keyCleared = true })
        assertSame(fresh, used)
        assertFalse(keyCleared)
        assertEquals(0.0, kotlin.math.abs(used!!.cashSol - ledgerCash), 1e-9)
        assertEquals(0.0, kotlin.math.abs(used.openCostBasisSol - ledgerOpen), 1e-9)
        assertEquals(1L, ReconLag8358.staleReplays8358())
    }

    @Test fun replay_at_the_key_revision_is_used_unchanged() {
        val r = replay(1.0, 1.0, rev = 7L, basis = emptyMap())
        var freshCalled = false
        assertSame(r, ReconLag8358.currentReplay8358(r, 7L, { freshCalled = true; null }, {}))
        assertFalse(freshCalled)
        assertFalse(ReconLag8358.isStaleReplay8358(-1L, 7L))
        assertTrue(ReconLag8358.isStaleReplay8358(6L, 7L))
    }

    @Test fun journal_still_moving_clears_the_key_and_names_the_stale_replay() {
        val stale = replay(1.0, 1.0, rev = 5L, basis = emptyMap())
        val newer = replay(1.0, 1.0, rev = 9L, basis = emptyMap())
        var keyCleared = false
        val used = ReconLag8358.currentReplay8358(stale, 7L, { newer }, { keyCleared = true })
        assertSame(newer, used)
        assertTrue(keyCleared)
        val src = ReconLag8358.attribute8358(0.1, 0.1, BigInteger.ONE, emptyMap(), emptyMap(), emptyMap(), 0L, "rev=9/7")
        assertEquals("STALE_REPLAY rev=9/7", src)
    }

    @Test fun a_real_divergence_still_fails_and_names_the_leg() {
        // Fresh replay, same revision, journal genuinely missing the add leg: the delta stays and is named.
        val src = ReconLag8358.attribute8358(
            cashDeltaSol = addCost + addFee, basisDeltaSol = addCost, quantityDeltaRaw = BigInteger.valueOf(84930968L),
            journalBasisByPosition = mapOf("P" to 0.2, "Q" to 0.5),
            canonicalBasisByPosition = mapOf("P" to 0.2 + addCost, "Q" to 0.5),
            mintByPosition = mapOf("P" to "MintPPPPPPPPPPPPPPPPPP"),
            trimmedRows = 0L, stale = "",
        )
        assertEquals("LEDGER_ONLY_BUY_LEG MintPPPPPPPPPPPP 0.129105", src)
        assertEquals("none", ReconLag8358.attribute8358(0.0, 0.0, BigInteger.ZERO, emptyMap(), emptyMap(), emptyMap(), 0L, ""))
        assertEquals("WINDOW_CAP 12", ReconLag8358.attribute8358(0.01, 0.0, BigInteger.ZERO, emptyMap(), emptyMap(), emptyMap(), 12L, ""))
        assertTrue(ReconLag8358.attribute8358(0.01, 0.0, BigInteger.ZERO, emptyMap(), emptyMap(), emptyMap(), 0L, "").startsWith("UNATTRIBUTED"))
    }

    private fun read(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun hook_sites_are_pinned() {
        val fr = read("engine/truth/ForensicReconciliation6635.kt")
        assertTrue(fr.contains("val replay6647 = ReconLag8358.currentReplay8358(replayFirst8358, key7387?.substringBefore('|')?.toLongOrNull() ?: -1L, { JournalEconomicReplay6619.replay() }, { lastKey7387.set(null) })"))
        assertTrue(fr.contains("ReconLag8358.recordAttribution8358(cashDelta, openCostDelta, quantityDeltaRaw6647, replay6647)"))
        // The tolerance and the delta checks are untouched.
        assertTrue(fr.contains("internal const val DELTA_TOLERANCE_SOL = 1e-6"))
        assertTrue(fr.contains("if (cashDelta > DELTA_TOLERANCE_SOL) {"))
        val jr = read("engine/truth/JournalEconomicReplay6619.kt")
        assertTrue(jr.contains("val journalRevision8358: Long = -1L,"))
        assertTrue(jr.contains("journalRevision8358 = memoKey7343.substringBefore('|').toLongOrNull() ?: -1L,"))
        val audit = read("engine/truth/AcceptanceInvariantAudit6441.kt")
        assertTrue(audit.contains("deltaFrom8358=\${ReconLag8358.lastSource8358()}"))
        val ths = read("engine/TradeHistoryStore.kt")
        assertTrue(ths.contains("ReconLag8358.noteWindowTrimmed8358(evicted8269.size)"))
        assertTrue(ths.contains("ReconLag8358.noteWindowTrimmed8358(trades.size - MAX_IN_MEMORY_TRADES)"))
        // The paper top-up leg the CI gap matched: 0.5 % fee on CanonicalPaperTransaction6486.add.
        val ex = read("engine/Executor.kt")
        assertTrue(ex.contains("val addedFee6539 = sol * 0.005"))
    }
}
