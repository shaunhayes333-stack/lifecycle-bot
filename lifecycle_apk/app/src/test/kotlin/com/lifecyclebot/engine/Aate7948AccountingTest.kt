package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AccountingIntegrity7948
import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.7948 — Accounting: journal coverage, host projection gap, partial-leg identity, residue labels. */
class Aate7948AccountingTest {

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private fun pos(
        mode: String = "live",
        qty: Long = 1_234_500_000L,
        cost: Double = 0.05,
        entryUsd: Double = 0.0004,
        scale: Int = 6,
    ) = CanonicalPositionAuthority6441.Position(
        positionId = "LIVE:Mint7948:run:1", mode = mode, mint = "Mint7948", symbol = "SPK", lane = "SHITCOIN",
        runId = "run", openedAtMs = 1_000L, entryCostSol = cost,
        remainingQtyRaw = BigInteger.valueOf(qty), originalQtyRaw = BigInteger.valueOf(qty),
        soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = 0.0,
        tokenDecimals = scale, quantityScale = scale,
        lifecycle = CanonicalPositionAuthority6441.Lifecycle.OPEN, lastMutationMs = 1_000L, quarantineReason = "",
        entryPriceUsd = entryUsd, entryPriceSource = "LIVE_PROOF_COST_BASIS",
    )

    // ── journal coverage ─────────────────────────────────────────────────────
    @Test fun backfill_is_due_only_after_grace_and_only_when_unjournaled() {
        val g = AccountingIntegrity7948.GRACE_MS_7948
        assertFalse(AccountingIntegrity7948.dueForBackfill7948(10_000L, 10_000L + g - 1, journaled = false))
        assertTrue(AccountingIntegrity7948.dueForBackfill7948(10_000L, 10_000L + g, journaled = false))
        assertFalse("a journaled buy is never backfilled",
            AccountingIntegrity7948.dueForBackfill7948(10_000L, 10_000L + g * 5, journaled = true))
        assertFalse(AccountingIntegrity7948.dueForBackfill7948(0L, g * 5, journaled = false))
    }

    @Test fun backfilled_buy_row_carries_the_canonical_fill() {
        val t = AccountingIntegrity7948.backfillBuyTrade7948(pos(), "SIG7948")
        assertNotNull(t)
        t!!
        assertEquals("BUY", t.side)
        assertEquals("live", t.mode)
        assertEquals("LIVE:Mint7948:run:1", t.positionId)
        assertEquals(0.05, t.entryCostSol, 1e-12)
        assertEquals(0.05, t.sol, 1e-12)
        assertEquals(0.0004, t.price, 1e-12)
        assertEquals(0.0004, t.entryPriceSnapshot, 1e-12)
        assertEquals(1234.5, t.entryQtyToken, 1e-9)
        assertEquals(BigInteger.valueOf(1_234_500_000L), t.entryRawQty)
        assertEquals(6, t.tokenDecimals)
        assertEquals("SIG7948", t.sig)
        assertEquals("LIVE_SIG_CONFIRMED", t.proofState)
        assertEquals("SHITCOIN", t.tradingMode)
        // It passes the journal's own BUY validity rule (price, cost, entry snapshot > 0).
        assertTrue(t.price > 0.0 && t.entryCostSol > 0.0 && t.entryPriceSnapshot > 0.0)
    }

    @Test fun backfill_never_invents_a_row() {
        assertNull(AccountingIntegrity7948.backfillBuyTrade7948(pos(mode = "paper"), "S"))
        assertNull(AccountingIntegrity7948.backfillBuyTrade7948(pos(qty = 0L), "S"))
        assertNull(AccountingIntegrity7948.backfillBuyTrade7948(pos(cost = 0.0), "S"))
        assertNull(AccountingIntegrity7948.backfillBuyTrade7948(pos(entryUsd = 0.0), "S"))
        assertEquals("LIVE_BALANCE_CONFIRMED", AccountingIntegrity7948.backfillBuyTrade7948(pos(), "")!!.proofState)
    }

    @Test fun coverage_line_reports_the_gap_and_journaling_clears_pending() {
        AccountingIntegrity7948.resetForTest7948()
        AccountingIntegrity7948.onLiveBuyCommitted7948("p1", 1L)
        AccountingIntegrity7948.onLiveBuyCommitted7948("p2", 1L)
        assertTrue(AccountingIntegrity7948.coverageLine7948(5, 1).startsWith("GAP confirmed=5 journaled=1 missing=4"))
        assertTrue(AccountingIntegrity7948.coverageLine7948(5, 1).contains("awaitingJournal=2"))
        AccountingIntegrity7948.onLiveBuyJournaled7948("p1")
        assertTrue(AccountingIntegrity7948.coverageLine7948(5, 5).startsWith("OK "))
        assertTrue(AccountingIntegrity7948.coverageLine7948(5, 5).contains("awaitingJournal=1"))
        AccountingIntegrity7948.resetForTest7948()
    }

    @Test fun every_confirmed_live_buy_is_tracked_and_swept() {
        val phc = src("engine/PipelineHealthCollector.kt")
        assertTrue(phc.contains("AccountingIntegrity7948.onLiveBuyCommitted7948(positionId)"))
        assertTrue(phc.contains("Journal coverage (§7948)"))
        assertTrue(src("engine/TradeHistoryStore.kt").contains("AccountingIntegrity7948.onLiveBuyJournaled7948(tradeToStore.positionId)"))
        assertTrue(src("engine/WalletReconciler.kt").contains("AccountingIntegrity7948.sweepLiveBuyJournal7948()"))
        assertTrue(src("engine/sell/LiveWalletReconciler.kt").contains("AccountingIntegrity7948.sweepLiveBuyJournal7948()"))
    }

    // ── host projection (5 canonical vs 4 host) ──────────────────────────────
    @Test fun projection_gap_names_the_missing_mint_and_its_tracker_status() {
        val gap = AccountingIntegrity7948.projectionGap7948(
            setOf("AAAAAA1", "BBBBBB2", "CCCCCC3"), setOf("AAAAAA1", "BBBBBB2"),
        ) { m -> if (m == "CCCCCC3") "CLOSED_STALE_RECOVERY_UNHELD" else "OPEN_TRACKING" }
        assertEquals("canonicalOnly=[CCCCCC:CLOSED_STALE_RECOVERY_UNHELD] hostOnly=[]", gap)
        assertTrue(AccountingIntegrity7948.projectionGap7948(setOf("X"), setOf("X")) { null }.startsWith("none"))
        assertTrue(AccountingIntegrity7948.projectionGap7948(setOf("NOROW99"), emptySet()) { null }.contains("NOROW9:NO_TRACKER_ROW"))
    }

    @Test fun stale_unheld_row_reopens_only_for_a_canonical_live_wallet_held_position() {
        assertTrue(AccountingIntegrity7948.reopenForCanonicalLive7948("CLOSED_STALE_RECOVERY_UNHELD", true, true))
        assertFalse(AccountingIntegrity7948.reopenForCanonicalLive7948("CLOSED_STALE_RECOVERY_UNHELD", false, true))
        assertFalse("external holdings stay closed",
            AccountingIntegrity7948.reopenForCanonicalLive7948("CLOSED_STALE_RECOVERY_UNHELD", true, false))
        assertFalse("dust-unroutable stays closed",
            AccountingIntegrity7948.reopenForCanonicalLive7948("CLOSED_DUST_UNROUTABLE", true, true))
        val tracker = src("engine/HostWalletTokenTracker.kt")
        assertTrue(tracker.contains("TRACKER_STALE_UNHELD_REOPENED_CANONICAL_7948"))
        assertTrue(tracker.contains("TRACKER_STALE_CLOSE_DEFERRED_CANONICAL_LIVE_7948"))
    }

    @Test fun paper_exit_can_never_mark_the_live_tracker_row() {
        assertEquals("PAPER_EXIT:STRICT_SL_-8", AccountingIntegrity7948.paperTrackerReason7948("STRICT_SL_-8"))
        assertEquals("PAPER_TP", AccountingIntegrity7948.paperTrackerReason7948("PAPER_TP"))
        assertTrue(AccountingIntegrity7948.paperTrackerReason7948("x").contains("PAPER"))
        val exec = src("engine/Executor.kt")
        val paperSell = exec.substringAfter("fun paperSell(").substringBefore("private fun liveSell(")
        assertTrue(paperSell.contains("AccountingIntegrity7948.paperTrackerReason7948(reason)"))
        assertFalse(paperSell.contains("recordSellConfirmed(ts.mint, ts.symbol, price, pnlP, reason)"))
    }

    // ── confirmed partial sells (SPIKE_CAPTURE_7943 → requestPartialSellConfirmed6566) ──
    @Test fun live_partial_leg_carries_canonical_identity_and_raw_quantities() {
        val exec = src("engine/Executor.kt")
        val leg = exec.substringAfter("val settled7835 = commitVerifiedLiveSlice7835(ts, proof7835, reason) ?: return")
            .substringBefore("recordTrade(ts, liveTrade)")
        assertTrue(leg.contains("positionId = pos.positionId.ifBlank"))
        assertTrue(leg.contains("canonicalConsumedRaw = settled7835.actualConsumedRaw"))
        assertTrue(leg.contains("remainingRawQty = settled7835.remainingRaw"))
        assertTrue(leg.contains("soldQtyToken = proof7835.uiTokenConsumed"))
        assertTrue(src("engine/BotService.kt").contains("executor.requestPartialSellConfirmed6566(ts, fraction, reason, wallet, bal).applied"))
    }

    // ── historical residue is labelled as such ────────────────────────────────
    @Test fun lot_violation_scope_splits_replay_history_from_runtime() {
        assertEquals(" runtime7948=0 paperReplayHistory7948=0", CanonicalLotQuantity6464.violationScope7948(0, 0))
        val hist = CanonicalLotQuantity6464.violationScope7948(45, 45)
        assertTrue(hist.contains("runtime7948=0 paperReplayHistory7948=45"))
        assertTrue(hist.contains("paper replay residue"))
        assertFalse(hist.contains("RUNTIME:"))
        assertTrue(CanonicalLotQuantity6464.violationScope7948(45, 40).contains("runtime7948=5"))
        assertTrue(CanonicalLotQuantity6464.violationScope7948(45, 40).contains("RUNTIME:"))
    }

    @Test fun runtime_sell_without_buy_counts_as_runtime_violation() {
        CanonicalLotQuantity6464.resetForTest()
        CanonicalLotQuantity6464.onSellFilled("NoSuchPos7948", "NoMint7948", BigInteger.TEN)
        val line = CanonicalLotQuantity6464.statusLine()
        assertTrue(line, line.contains("invariantViolations=1"))
        assertTrue(line, line.contains("runtime7948=1 paperReplayHistory7948=0"))
        CanonicalLotQuantity6464.resetForTest()
    }

    @Test fun residue_status_lines_say_paper_history_not_live_inventory() {
        assertTrue(src("engine/truth/ContaminatedPartialQuarantine7032.kt").contains("scope=paper_history learning=excluded liveInventory=none"))
        assertTrue(src("engine/truth/CanonicalPaperReplay6464.kt").contains("scope=paper_durable_history invalidRows=excluded_from_replay liveInventory=none"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("unfunded=historical residue: excluded from learning and live inventory"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Host projection gap (§7948)"))
    }
}
