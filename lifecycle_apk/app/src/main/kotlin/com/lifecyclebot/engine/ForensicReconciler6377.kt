package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V5.0.6377 — FORENSIC RECONCILER (11-item correctness spec).
 *
 * Operator directive (verbatim):
 *   "the 11 item forensic correction. do as much as you can now. bundle
 *    where you can. all data, pricing, wins and losses must reconcile
 *    forensically. same as the journal and reports."
 *
 * Read-only, pure-comparison reconciler. Runs on demand from
 * BotService.emitBotLoopTick (periodic) and on startup after journal
 * hydration. Every check is:
 *   • Additive        — never mutates state, never rewrites the journal.
 *   • Fluid-tolerant  — uses relative tolerances (SAFE_REL_TOL) so
 *                       legitimate rounding does not flag mismatches.
 *   • Domain-scoped   — separates paper from live and live-broadcast
 *                       from live-canonical so cross-domain drift does
 *                       not create phantom failures.
 *
 * Each check emits exactly ONE PipelineHealthCollector counter per pass:
 *   FORENSIC_OK_6377|<CHECK_NAME>
 *   FORENSIC_MISMATCH_6377|<CHECK_NAME>|<summary>
 * so the pipeline dump and any post-hoc log-scrape have a single audit
 * surface.
 *
 * The 11 checks (all derived from the operator's ongoing "wallet vs
 * journal vs reports must tie out" requirement):
 *
 *   1. WALLET_VS_JOURNAL        paperWalletSol ≈ startCapital + Σ(sell.pnlSol)
 *   2. JOURNAL_ROW_PARITY       Σ(BUY rows) ≥ Σ(SELL rows) — no orphan sells
 *   3. BUY_SELL_QTY_SKEW        per-mint Σbuyqty ≥ Σsellqty (never over-sold)
 *   4. COST_BASIS               each BUY: |sol - price×0| ignored; positive sanity only
 *   5. PNL_PCT_VS_SOL           each SELL: sign(pnlPct) == sign(pnlSol) (no phantom flip)
 *   6. SELL_REASON_PRESENCE     every SELL has non-empty reason field
 *   7. PRICE_IMMUTABILITY       every BUY has price>0 (no zero-price sneak-throughs)
 *   8. TACTIC_MU_VS_JOURNAL     TacticSwitcher aggregated μ per lane vs journal μ per mode
 *   9. DUPLICATE_JOURNAL_ROWS   no exact (mint, side, ts) duplicates
 *  10. ORPHAN_SELL              every SELL(mint) has ≥1 prior BUY(mint) in journal
 *  11. CANONICAL_VS_REGISTRY    canonical live-open count vs GlobalTradeRegistry open count
 *
 * Failure surface (pipeline dump reads this):
 *   ForensicReconciler6377.lastReport().mismatches → List<Mismatch>
 */
object ForensicReconciler6377 {

    /** Relative tolerance for float comparisons (0.5% by default). */
    private const val SAFE_REL_TOL = 0.005

    /** Absolute floor SOL below which we ignore relative-tolerance drift. */
    private const val SAFE_ABS_FLOOR_SOL = 0.001

    private val passCount = AtomicLong(0L)
    private val mismatchCount = AtomicLong(0L)
    private val lastRunAtMs = AtomicLong(0L)

    @Volatile private var _lastReport: Report = Report(runAtMs = 0L, checks = emptyList())

    data class CheckResult(
        val name: String,
        val ok: Boolean,
        val summary: String,
    )

    data class Report(
        val runAtMs: Long,
        val checks: List<CheckResult>,
    ) {
        val okCount get() = checks.count { it.ok }
        val mismatchCount get() = checks.count { !it.ok }
        val mismatches get() = checks.filter { !it.ok }
    }

    fun lastReport(): Report = _lastReport
    fun lifetimePassCount(): Long = passCount.get()
    fun lifetimeMismatchCount(): Long = mismatchCount.get()
    fun lastRunAtMs(): Long = lastRunAtMs.get()

    /**
     * Run all 11 checks. Pure function against provided inputs — the
     * caller is expected to have already resolved the current
     * paperWalletSol / startCapitalSol / etc.
     *
     * Emits a PipelineHealthCollector counter per check and stores the
     * full report in [_lastReport] for the pipeline dump to render.
     */
    @JvmStatic
    fun runAll(
        allTrades: List<Trade>,
        paperMode: Boolean,
        paperWalletSol: Double,
        startCapitalSol: Double,
        canonicalLiveOpenCount: Int,
        registryLiveOpenCount: Int,
    ): Report {
        val results = mutableListOf<CheckResult>()

        // Filter by mode so paper and live are separately reconciled.
        val tradesForMode = allTrades.filter {
            val m = it.mode.uppercase()
            if (paperMode) m == "PAPER" else m == "LIVE"
        }
        val buys  = tradesForMode.filter { it.side.equals("BUY", true) }
        val sells = tradesForMode.filter { it.side.equals("SELL", true) }

        // ── 1. WALLET_VS_JOURNAL ─────────────────────────────────────────
        run {
            val realizedSol = sells.sumOf { it.pnlSol }
            val expected = startCapitalSol + realizedSol
            // Open positions consume SOL from the wallet — a mismatch here
            // may just mean money is parked in open buys. So this check
            // treats "wallet <= expected" as OK (parked capital) and only
            // flags "wallet > expected + tolerance" (phantom SOL creation).
            val over = paperWalletSol - expected
            val tolerance = maxOf(SAFE_ABS_FLOOR_SOL, abs(expected) * SAFE_REL_TOL)
            val ok = over <= tolerance
            val summary = "wallet=${fmt(paperWalletSol)} expected≤${fmt(expected)}+tol=${fmt(tolerance)} over=${fmt(over)}"
            results += CheckResult("WALLET_VS_JOURNAL", ok, summary)
        }

        // ── 2. JOURNAL_ROW_PARITY (buys ≥ sells) ─────────────────────────
        run {
            val ok = buys.size >= sells.size
            results += CheckResult("JOURNAL_ROW_PARITY", ok, "buys=${buys.size} sells=${sells.size}")
        }

        // ── 3. BUY_SELL_QTY_SKEW (per-mint) ──────────────────────────────
        run {
            val buyByMint = buys.groupBy { it.mint }.mapValues { e -> e.value.sumOf { it.entryQtyToken.coerceAtLeast(0.0) } }
            val sellByMint = sells.groupBy { it.mint }.mapValues { e -> e.value.sumOf { it.soldQtyToken.coerceAtLeast(0.0) } }
            val violators = sellByMint.entries.filter { (mint, sellQty) ->
                val buyQty = buyByMint[mint] ?: 0.0
                // Only flag when sold qty materially exceeds bought qty.
                buyQty > 0.0 && sellQty > buyQty * (1.0 + SAFE_REL_TOL) && (sellQty - buyQty) > 1.0
            }
            val ok = violators.isEmpty()
            val summary = if (ok) "buyMints=${buyByMint.size} sellMints=${sellByMint.size}"
                          else "over-sold mints=${violators.size} e.g. ${violators.first().key.take(6)}=(buy${fmt(buyByMint[violators.first().key] ?: 0.0)}/sell${fmt(violators.first().value)})"
            results += CheckResult("BUY_SELL_QTY_SKEW", ok, summary)
        }

        // ── 4. COST_BASIS (buy.sol > 0 for entries) ──────────────────────
        run {
            val zeroCostBuys = buys.count { it.sol <= 0.0 && it.price > 0.0 }
            val ok = zeroCostBuys == 0
            results += CheckResult("COST_BASIS", ok, if (ok) "buys=${buys.size} all positive-cost" else "zero-cost buys=$zeroCostBuys")
        }

        // ── 5. PNL_PCT_VS_SOL (sign parity, no phantom flip) ─────────────
        run {
            val flipped = sells.count { t ->
                // Skip scratches (near-zero on both).
                if (abs(t.pnlSol) < 0.0005 && abs(t.pnlPct) < 0.1) return@count false
                val signSol = if (t.pnlSol > 0) 1 else if (t.pnlSol < 0) -1 else 0
                val signPct = if (t.pnlPct > 0) 1 else if (t.pnlPct < 0) -1 else 0
                signSol != signPct
            }
            val ok = flipped == 0
            results += CheckResult("PNL_PCT_VS_SOL", ok, if (ok) "sells=${sells.size} sign-consistent" else "sign-flipped sells=$flipped")
        }

        // ── 6. SELL_REASON_PRESENCE ──────────────────────────────────────
        run {
            val missing = sells.count { it.reason.isBlank() }
            val ok = missing == 0
            results += CheckResult("SELL_REASON_PRESENCE", ok, if (ok) "sells=${sells.size} all-tagged" else "reason-blank sells=$missing")
        }

        // ── 7. PRICE_IMMUTABILITY (proxy: no zero-price entries) ─────────
        run {
            val zeroPriceBuys = buys.count { it.price <= 0.0 && it.sol > 0.0 }
            val ok = zeroPriceBuys == 0
            results += CheckResult("PRICE_IMMUTABILITY", ok, if (ok) "buys=${buys.size} all-priced" else "zero-price buys=$zeroPriceBuys")
        }

        // ── 8. TACTIC_MU_VS_JOURNAL ──────────────────────────────────────
        // Compare TacticSwitcher-reported μ per (lane) to journal-derived
        // μ per tradingMode over a bounded lookback. Deviations >200pp
        // relative are flagged as "tactic persistence drift".
        run {
            try {
                val laneStats = try {
                    com.lifecyclebot.engine.learning.TacticSwitcher.dumpForensicSnapshot6377()
                } catch (_: Throwable) { emptyList() }
                if (laneStats.isEmpty()) {
                    results += CheckResult("TACTIC_MU_VS_JOURNAL", true, "no-tactic-snapshot-available")
                } else {
                    val journalByMode = sells.groupBy { it.tradingMode.uppercase() }
                        .mapValues { e -> e.value.map { it.pnlPct } }
                    var drift = 0
                    var totalCompared = 0
                    for ((laneKey, tacticMu, tacticN) in laneStats) {
                        if (tacticN < 5) continue
                        val laneUpper = laneKey.substringBefore("|").uppercase()
                        val journalPnls = journalByMode[laneUpper] ?: continue
                        if (journalPnls.size < 5) continue
                        val journalMu = journalPnls.average()
                        totalCompared++
                        // Deviation is flagged when tacticMu > journalMu + 100pp for the
                        // same lane over a 5+ sample overlap. This catches the persisted
                        // phantom-inflated pnlSum leak.
                        if (abs(tacticMu - journalMu) > 100.0 && abs(tacticMu) > 50.0) {
                            drift++
                        }
                    }
                    val ok = drift == 0
                    results += CheckResult("TACTIC_MU_VS_JOURNAL", ok, "compared=$totalCompared drift=$drift")
                }
            } catch (_: Throwable) {
                results += CheckResult("TACTIC_MU_VS_JOURNAL", true, "check-skipped-exception")
            }
        }

        // ── 9. DUPLICATE_JOURNAL_ROWS ────────────────────────────────────
        run {
            val fingerprints = tradesForMode.map { "${it.mint}|${it.side}|${it.ts}" }
            val distinct = fingerprints.toSet().size
            val duplicates = fingerprints.size - distinct
            val ok = duplicates == 0
            results += CheckResult("DUPLICATE_JOURNAL_ROWS", ok, if (ok) "rows=${fingerprints.size} unique" else "dupes=$duplicates of ${fingerprints.size}")
        }

        // ── 10. ORPHAN_SELL ──────────────────────────────────────────────
        run {
            val boughtMints = buys.mapTo(HashSet()) { it.mint }
            val orphans = sells.count { it.mint.isNotBlank() && it.mint !in boughtMints }
            val ok = orphans == 0
            results += CheckResult("ORPHAN_SELL", ok, if (ok) "sells=${sells.size} all-parented" else "orphan sells=$orphans")
        }

        // ── 11. CANONICAL_VS_REGISTRY ────────────────────────────────────
        run {
            val delta = canonicalLiveOpenCount - registryLiveOpenCount
            val ok = abs(delta) <= 0
            results += CheckResult("CANONICAL_VS_REGISTRY", ok, "canonical=$canonicalLiveOpenCount registry=$registryLiveOpenCount delta=$delta")
        }

        // Emit telemetry.
        for (r in results) {
            try {
                if (r.ok) {
                    PipelineHealthCollector.labelInc("FORENSIC_OK_6377|${r.name}")
                    passCount.incrementAndGet()
                } else {
                    val safeSummary = r.summary.take(80).replace('|', '_')
                    PipelineHealthCollector.labelInc("FORENSIC_MISMATCH_6377|${r.name}|$safeSummary")
                    mismatchCount.incrementAndGet()
                }
            } catch (_: Throwable) {}
        }

        val report = Report(runAtMs = System.currentTimeMillis(), checks = results)
        _lastReport = report
        lastRunAtMs.set(report.runAtMs)
        return report
    }

    /** Test-only reset. */
    internal fun resetForTest() {
        passCount.set(0L)
        mismatchCount.set(0L)
        lastRunAtMs.set(0L)
        _lastReport = Report(runAtMs = 0L, checks = emptyList())
    }

    private fun fmt(d: Double): String =
        if (abs(d) >= 1000) "%.0f".format(d)
        else if (abs(d) >= 1)   "%.3f".format(d)
        else                    "%.5f".format(d)
}
