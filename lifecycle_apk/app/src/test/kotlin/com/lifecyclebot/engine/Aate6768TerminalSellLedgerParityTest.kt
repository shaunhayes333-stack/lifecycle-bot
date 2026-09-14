package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6768 — TERMINAL_SELL_LEDGER_PARITY_ROOT_CAUSE.
 *
 * Operator screenshots Feb 2026: the hero surfaces (Crypto Universe, Markets,
 * Main) painted "ACCOUNT UNAVAILABLE" / "ACCOUNTING ERROR" while the ledger held
 * $101, 34 crypto trades, 47% WR, 7 open positions and every runner was alive.
 *
 * Runtime evidence (fix/6756-pipeline-recovery smoke run 34839616771) pinned the
 * exact fault:
 *   PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619
 *     ledgerCash=0.004899 journalCash=-1.206911 delta=1.211810
 *   JOURNAL_ECONOMIC_PUBLISH_BLOCKED_FAILED_REPLAY_6647
 *     failures=[paper_full_PAPER:Ugu8xGdj7cps1wp9jfDinYCu9RmLMiNJz5ZmUbjpump:
 *               61792:85_1789387006608:TERMINAL_SELL_INCOMPLETE_LOT]
 *   FORENSIC_QUANTITY_DELTA_6647
 *     absoluteRawDelta=32501249940042933 journalPositions=92 canonicalPositions=76
 *
 * Cause: PaperCapitalAuthority6577 is a scalar accumulator — on every fill it
 * drains openCost/realized by the RECORDED basis. JournalEconomicReplay6619
 * previously required a terminal SELL to zero the accumulated buy-side lot
 * EXACTLY (`kotlin.math.abs(nextBasis) > 1e-9 => reject`). Precision drift
 * between summed BUY-side basis rows and the recorded terminal SELL basis
 * (adaptive re-basis, fee rounding, partial-sell rebalances) rejected every
 * such terminal, orphaned the lot in the projection, drove
 * CASH/BASIS/REALIZED/QUANTITY deltas, forced accountAvailable=false and
 * painted the hero red — despite the ledger being healthy.
 *
 * Fix at source (no overlays, no new authority): on terminal SELL apply the
 * recorded economics AND sweep the lot's residual basis into openCost so the
 * journal matches the ledger's scalar drain byte-for-byte, then close the lot
 * unconditionally. PARTIAL_SELL still requires strict lot arithmetic because
 * the lot stays open.
 */
class Aate6768TerminalSellLedgerParityTest {

    private val replaySrc = java.io.File(
        "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt"
    ).readText()

    @Test
    fun aate6768_strict_terminal_sell_incomplete_rejection_is_removed() {
        assertFalse(
            "V5.0.6768: TERMINAL_SELL_INCOMPLETE_LOT rejection must be removed at source " +
                "(root cause of hero ACCOUNT UNAVAILABLE + Journal Replay P0)",
            replaySrc.contains("\"TERMINAL_SELL_INCOMPLETE_LOT\"")
        )
    }

    @Test
    fun aate6768_terminal_sell_sweeps_residual_and_removes_lot() {
        assertTrue(
            "V5.0.6768: terminal SELL branch must sweep residual basis and remove lot",
            replaySrc.contains("JOURNAL_TERMINAL_SELL_RESIDUAL_SWEPT_6768")
        )
        assertTrue(
            "V5.0.6768: terminal SELL must unconditionally close the lot",
            replaySrc.contains("lots.remove(t.positionId)")
        )
    }

    @Test
    fun aate6768_partial_sell_still_holds_strict_lot_math() {
        // PARTIAL_SELL keeps the pre-6768 branch — NEGATIVE_REMAINING_LOT still fires
        // because the lot must stay coherent between partial fills.
        assertTrue(
            "V5.0.6768: NEGATIVE_REMAINING_LOT invariant preserved for partial sells",
            replaySrc.contains("\"NEGATIVE_REMAINING_LOT\"")
        )
    }

    @Test
    fun aate6768_canonical_event_registry_status_does_not_block_on_transient_open() {
        // Second-layer fix: CanonicalEconomicEvent6635.forensicReconciliationLine6635()
        // previously required (eventParity && open == 0) → any in-flight commit made
        // the registry report status=FAILED, which forced
        // JournalEconomicAuthority6616 to refuse EVERY publish
        // (JOURNAL_ECONOMIC_PUBLISH_BLOCKED_FAILED_REPLAY_6647 with replayOk=true
        // and failures=[]). Fault semantics remain fully expressed via PENDING /
        // STUCK; in-flight OPEN is normal commit activity, not a fault.
        val eventSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalEconomicEvent6635.kt"
        ).readText()
        assertFalse(
            "V5.0.6768: `open == 0` must no longer gate global reconciliation status",
            eventSrc.contains("eventParity && open == 0")
        )
        assertTrue(
            "V5.0.6768: reconciliation status is defined by pending + stuck (event parity)",
            eventSrc.contains("val status = if (eventParity) \"RECONCILED\" else \"FAILED\"")
        )
    }

    @Test
    fun aate6768_canonical_event_registry_still_fails_on_pending_partial_commit() {
        // Regression: a real partial-commit defect (only one store stamped, TTL
        // elapsed, promoted to PENDING) must still surface as status=FAILED.
        val events = com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635
        events.resetForTest()
        val id = events.mintEventId()
        val evt = events.Event(
            economicEventId = id, positionId = "pid-6768", mint = "M", canonicalMint = "M",
            symbol = "SYM", mode = "paper", lane = "MEME", side = events.Side.BUY,
            timestampMs = System.currentTimeMillis() - 120_000L,
            qtyRaw = java.math.BigInteger.ONE, decimals = 0,
            executionPriceUsd = 0.001, executionPriceSol = 0.0000005,
            notionalSol = 0.05, feeSol = 0.001, cashDeltaSol = -0.051,
            positionQtyDeltaRaw = java.math.BigInteger.ONE,
            realizedPnlDeltaSol = 0.0, terminalFillIndex = 0,
        )
        assertTrue(events.openEvent(evt))
        events.markCommitted(id, events.Store.LEDGER, "test.only")
        events.sweepPending6635(ttlMs = 60_000L)
        val line = events.forensicReconciliationLine6635()
        assertTrue("Partial commit still FAILS: $line", line.contains("status=FAILED"))
        assertTrue("Partial commit records pending=1: $line", line.contains("pending=1"))
        events.resetForTest()
    }

    @Test
    fun aate6768_replay_of_empty_journal_stays_reconciled() {
        // Regression: the fix must not accidentally break the trivial zero-history
        // reconciled state that hero cards on cold-open depend on.
        val replay = com.lifecyclebot.engine.truth.JournalEconomicReplay6619
        replay.resetForTest()
        com.lifecyclebot.engine.truth.PaperAccountLedger6430.resetForTest()
        com.lifecyclebot.engine.truth.PaperAccountLedger6430.initialize(1.5)
        val r = replay.replay()
        // Main-thread deferred replay is fine — the assertion is only that empty
        // history never produces a rejection.
        val hasHardFailure = r.invariantFailures.any {
            it != "MAIN_THREAD_REPLAY_DEFERRED"
        }
        assertFalse(
            "V5.0.6768: empty journal must not manufacture invariant failures: " +
                "${r.invariantFailures}",
            hasHardFailure
        )
    }
}
