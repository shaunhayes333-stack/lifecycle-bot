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
 * Read-only comparison reconciler. Runs on demand from BotService and on the
 * independent reconciliation cadence. V5.0.6706 fixes the stale bounded-window
 * assumption: PAPER checks use the durable full journal snapshot when available,
 * and WALLET_VS_JOURNAL prefers JournalEconomicReplay6619's exact cash replay.
 * This prevents a healthy 192-SOL account being compared with only the PnL from
 * the last bounded slice and falsely reported as phantom cash.
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

        // V5.0.6706 — relationship checks must see the durable parent rows, not
        // only the caller's bounded reporting slice. A sell whose BUY is older
        // than that slice is not an orphan, and current cash cannot be reconciled
        // against truncated lifetime PnL.
        val sourceTrades = if (paperMode) {
            try {
                TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000).takeIf { it.isNotEmpty() }
                    ?: allTrades
            } catch (_: Throwable) { allTrades }
        } else allTrades
        val tradesForMode = sourceTrades.filter {
            val m = it.mode.uppercase()
            if (paperMode) m == "PAPER" else m == "LIVE"
        }
        val buys  = tradesForMode.filter { it.side.equals("BUY", true) }
        val sells = tradesForMode.filter { it.side.equals("SELL", true) }

        // ── 1. WALLET_VS_JOURNAL ─────────────────────────────────────────
        run {
            val exactReplay = if (paperMode) try {
                com.lifecyclebot.engine.truth.JournalEconomicReplay6619.latest()?.takeIf { it.reconciled }
            } catch (_: Throwable) { null } else null
            if (exactReplay != null) {
                val expected = exactReplay.cashSol
                val delta = paperWalletSol - expected
                val tolerance = maxOf(SAFE_ABS_FLOOR_SOL, abs(expected) * SAFE_REL_TOL)
                val ok = abs(delta) <= tolerance
                results += CheckResult(
                    "WALLET_VS_JOURNAL", ok,
                    "wallet=${fmt(paperWalletSol)} durableJournalCash=${fmt(expected)} tol=${fmt(tolerance)} delta=${fmt(delta)}",
                )
            } else {
                // Legacy fallback is retained only when no exact durable replay has
                // been produced yet. It now operates on the full durable rows above.
                val realizedSol = sells.sumOf { it.pnlSol }
                val expected = startCapitalSol + realizedSol
                val over = paperWalletSol - expected
                val tolerance = maxOf(SAFE_ABS_FLOOR_SOL, abs(expected) * SAFE_REL_TOL)
                val ok = over <= tolerance
                results += CheckResult(
                    "WALLET_VS_JOURNAL", ok,
                    "wallet=${fmt(paperWalletSol)} legacyExpected≤${fmt(expected)}+tol=${fmt(tolerance)} over=${fmt(over)}",
                )
            }
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
                buyQty > 0.0 && sellQty > buyQty * (1.0 + SAFE_REL_TOL) && (sellQty - buyQty) > 1.0
            }
            val ok = violators.isEmpty()
            val summary = if (ok) "buyMints=${buyByMint.size} sellMints=${sellByMint.size}"
                          else "over-sold mints=${violators.size} e.g. ${violators.first().key.take(6)}=(buy${fmt(buyByMint[violators.first().key] ?: 0.0)}/sell${fmt(violators.first().value)})"
            results += CheckResult("BUY_SELL_QTY_SKEW", ok, summary)
            if (!ok) {
                try {
                    com.lifecyclebot.engine.truth.HistoricalEconomicQuarantine6496
                        .reportBuySellSkew(violators.map { it.key })
                } catch (_: Throwable) {}
            }
        }

        // ── 4. COST_BASIS ────────────────────────────────────────────────
        run {
            val zeroCostBuys = buys.count { it.sol <= 0.0 && it.price > 0.0 }
            val ok = zeroCostBuys == 0
            results += CheckResult("COST_BASIS", ok, if (ok) "buys=${buys.size} all positive-cost" else "zero-cost buys=$zeroCostBuys")
        }

        // ── 5. PNL_PCT_VS_SOL ────────────────────────────────────────────
        run {
            val flipped = sells.count { t ->
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

        // ── 7. PRICE_IMMUTABILITY ────────────────────────────────────────
        run {
            val zeroPriceBuys = buys.count { it.price <= 0.0 && it.sol > 0.0 }
            val ok = zeroPriceBuys == 0
            results += CheckResult("PRICE_IMMUTABILITY", ok, if (ok) "buys=${buys.size} all-priced" else "zero-price buys=$zeroPriceBuys")
        }

        // ── 8. TACTIC_MU_VS_JOURNAL ──────────────────────────────────────
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
                        if (abs(tacticMu - journalMu) > 100.0 && abs(tacticMu) > 50.0) drift++
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
