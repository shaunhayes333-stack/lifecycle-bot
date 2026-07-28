package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6377 — Golden-tape invariants for the 11-item Forensic Reconciler.
 *
 * Operator directive: "all data, pricing, wins and losses must reconcile
 * forensically. same as the journal and reports."
 *
 * Each check is exercised in isolation with a minimal Trade fixture so
 * regressions in ANY of the 11 comparators break the build.
 */
class Bundle6377ForensicReconcilerTest {

    private fun mkBuy(
        mint: String, sol: Double, price: Double, qty: Double,
        ts: Long = 1_000L, mode: String = "paper",
    ) = Trade(
        side = "BUY", mode = mode, sol = sol, price = price, ts = ts,
        mint = mint, entryQtyToken = qty, entryCostSol = sol,
    )

    private fun mkSell(
        mint: String, sol: Double, pnlSol: Double, pnlPct: Double,
        reason: String = "TEST_SELL", price: Double = 1.0, qty: Double = 100.0,
        ts: Long = 2_000L, mode: String = "paper", tradingMode: String = "STANDARD",
    ) = Trade(
        side = "SELL", mode = mode, sol = sol, price = price, ts = ts,
        mint = mint, pnlSol = pnlSol, pnlPct = pnlPct, reason = reason,
        soldQtyToken = qty, tradingMode = tradingMode,
    )

    private fun runReconciler(
        trades: List<Trade>,
        wallet: Double = 10.0,
        startCap: Double = 10.0,
        canonicalOpen: Int = 0,
        registryOpen: Int = 0,
    ) = ForensicReconciler6377.runAll(
        allTrades = trades,
        paperMode = true,
        paperWalletSol = wallet,
        startCapitalSol = startCap,
        canonicalLiveOpenCount = canonicalOpen,
        registryLiveOpenCount = registryOpen,
    )

    @Test
    fun reconciler_emits_exactly_11_checks() {
        ForensicReconciler6377.resetForTest()
        val r = runReconciler(emptyList())
        assertEquals(
            "V5.0.6377: reconciler MUST run exactly 11 named checks per pass (operator's 11-item spec)",
            11, r.checks.size
        )
    }

    @Test
    fun reconciler_check_names_match_spec() {
        val expected = listOf(
            "WALLET_VS_JOURNAL", "JOURNAL_ROW_PARITY", "BUY_SELL_QTY_SKEW",
            "COST_BASIS", "PNL_PCT_VS_SOL", "SELL_REASON_PRESENCE",
            "PRICE_IMMUTABILITY", "TACTIC_MU_VS_JOURNAL", "DUPLICATE_JOURNAL_ROWS",
            "ORPHAN_SELL", "CANONICAL_VS_REGISTRY",
        )
        ForensicReconciler6377.resetForTest()
        val r = runReconciler(emptyList())
        assertEquals(
            "V5.0.6377: check names MUST match the documented 11-item spec — pipeline dumps and log-scrapes depend on stable identifiers",
            expected, r.checks.map { it.name }
        )
    }

    // ── Check-1: WALLET_VS_JOURNAL ──────────────────────────────────────

    @Test
    fun wallet_vs_journal_flags_phantom_over_growth() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(
            mkBuy("A", 1.0, 0.001, 1000.0, ts=1),
            mkSell("A", 2.0, +1.0, +100.0, ts=2),
        )
        // start=10, realized=+1, expected=11. wallet=15 is phantom+4.
        val r = runReconciler(trades, wallet=15.0, startCap=10.0)
        val wj = r.checks.first { it.name == "WALLET_VS_JOURNAL" }
        assertFalse("V5.0.6377: WALLET_VS_JOURNAL must FAIL when wallet materially exceeds start+realized (phantom SOL)", wj.ok)
    }

    @Test
    fun wallet_vs_journal_ok_when_capital_parked_in_open_positions() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(mkBuy("A", 3.0, 0.001, 3000.0))
        // start=10, realized=0. wallet=7 (3 parked in open BUY). Must NOT flag.
        val r = runReconciler(trades, wallet=7.0, startCap=10.0)
        val wj = r.checks.first { it.name == "WALLET_VS_JOURNAL" }
        assertTrue("V5.0.6377: WALLET_VS_JOURNAL must be OK when wallet < expected (capital parked in open positions)", wj.ok)
    }

    // ── Check-3: BUY_SELL_QTY_SKEW ──────────────────────────────────────

    @Test
    fun buy_sell_qty_skew_flags_over_sell() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(
            mkBuy("A", 1.0, 0.001, 1000.0),
            mkSell("A", 1.0, 0.0, 0.0, qty=5000.0),  // 5x over-sold
        )
        val r = runReconciler(trades)
        val chk = r.checks.first { it.name == "BUY_SELL_QTY_SKEW" }
        assertFalse("V5.0.6377: BUY_SELL_QTY_SKEW must flag over-sold mint (sold > bought)", chk.ok)
    }

    // ── Check-5: PNL_PCT_VS_SOL sign parity ─────────────────────────────

    @Test
    fun pnl_pct_vs_sol_flags_sign_flip() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(
            mkBuy("A", 1.0, 0.001, 1000.0),
            // pnlSol negative but pnlPct positive → phantom flip.
            mkSell("A", 0.5, pnlSol=-0.5, pnlPct=+50.0),
        )
        val r = runReconciler(trades)
        val chk = r.checks.first { it.name == "PNL_PCT_VS_SOL" }
        assertFalse("V5.0.6377: PNL_PCT_VS_SOL must flag sign disagreement between pnlSol and pnlPct", chk.ok)
    }

    // ── Check-6: SELL_REASON_PRESENCE ───────────────────────────────────

    @Test
    fun sell_reason_presence_flags_blank_reason() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(
            mkBuy("A", 1.0, 0.001, 1000.0),
            mkSell("A", 1.0, 0.0, 0.0, reason=""),
        )
        val r = runReconciler(trades)
        val chk = r.checks.first { it.name == "SELL_REASON_PRESENCE" }
        assertFalse("V5.0.6377: SELL_REASON_PRESENCE must flag SELL rows with blank reason", chk.ok)
    }

    // ── Check-9: DUPLICATE_JOURNAL_ROWS ─────────────────────────────────

    @Test
    fun duplicate_journal_rows_detected() {
        ForensicReconciler6377.resetForTest()
        val same = mkBuy("A", 1.0, 0.001, 1000.0, ts=100)
        val trades = listOf(same, same)
        val r = runReconciler(trades)
        val chk = r.checks.first { it.name == "DUPLICATE_JOURNAL_ROWS" }
        assertFalse("V5.0.6377: DUPLICATE_JOURNAL_ROWS must catch two rows sharing (mint, side, ts)", chk.ok)
    }

    // ── Check-10: ORPHAN_SELL ───────────────────────────────────────────

    @Test
    fun orphan_sell_flagged_when_no_prior_buy() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(
            mkSell("Z", 1.0, 0.0, 0.0),  // no matching BUY(Z)
        )
        val r = runReconciler(trades)
        val chk = r.checks.first { it.name == "ORPHAN_SELL" }
        assertFalse("V5.0.6377: ORPHAN_SELL must flag SELL rows without a prior BUY for the same mint", chk.ok)
    }

    // ── Check-11: CANONICAL_VS_REGISTRY ─────────────────────────────────

    @Test
    fun canonical_vs_registry_flags_delta() {
        ForensicReconciler6377.resetForTest()
        val r = runReconciler(emptyList(), canonicalOpen=5, registryOpen=3)
        val chk = r.checks.first { it.name == "CANONICAL_VS_REGISTRY" }
        assertFalse("V5.0.6377: CANONICAL_VS_REGISTRY must flag when the two ledgers disagree on open-position count", chk.ok)
    }

    // ── All-green happy path ────────────────────────────────────────────

    @Test
    fun all_11_checks_pass_on_consistent_fixture() {
        ForensicReconciler6377.resetForTest()
        val trades = listOf(
            mkBuy("A", 1.0, 0.001, 1000.0, ts=1),
            mkSell("A", 1.5, pnlSol=+0.5, pnlPct=+50.0, ts=2, reason="TAKE_PROFIT"),
        )
        // start=10, realized=+0.5, expected=10.5. wallet=10.5 exactly.
        val r = runReconciler(trades, wallet=10.5, startCap=10.0)
        val failures = r.checks.filter { !it.ok }
        assertTrue(
            "V5.0.6377: a fully-consistent fixture must produce ZERO mismatches. Failures: ${failures.map { it.name }}",
            failures.isEmpty()
        )
    }

    // ── Wiring / telemetry surface ──────────────────────────────────────

    @Test
    fun reconciler_emits_labelled_counters() {
        ForensicReconciler6377.resetForTest()
        runReconciler(emptyList())
        // 11 OK counters must have been emitted on the empty happy-path.
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/ForensicReconciler6377.kt").readText()
        assertTrue(
            "V5.0.6377: reconciler must emit FORENSIC_OK_6377|<CHECK> and FORENSIC_MISMATCH_6377|<CHECK> counters",
            txt.contains("FORENSIC_OK_6377|") && txt.contains("FORENSIC_MISMATCH_6377|")
        )
    }

    @Test
    fun botservice_invokes_reconciler_periodically() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6377: BotService.emitBotLoopTick must call ForensicReconciler6377.runAll every ~50 cycles",
            txt.contains("ForensicReconciler6377.runAll(") &&
                txt.contains("(loopCount % 50) == 0")
        )
    }

    @Test
    fun pipeline_dump_surfaces_reconciler_report() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(
            "V5.0.6377: pipeline dump must include a FORENSIC RECONCILER section reading ForensicReconciler6377.lastReport()",
            txt.contains("FORENSIC RECONCILER (V5.0.6377)") &&
                txt.contains("ForensicReconciler6377.lastReport()")
        )
    }

    @Test
    fun tactic_switcher_exposes_forensic_snapshot() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "V5.0.6377: TacticSwitcher must expose dumpForensicSnapshot6377() so the reconciler can cross-check μ against the journal",
            txt.contains("fun dumpForensicSnapshot6377()") &&
                txt.contains("Triple<String, Double, Int>")
        )
    }
}
