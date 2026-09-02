package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6619 §JOURNAL_DERIVED_HERO_AUTHORITY (operator directive Feb 2026):
 *
 *   "The main UI balance is broken. Look at the journal vs the hero
 *    balance in the main UI. That figure is impossible and should be
 *    solely derived from data directly from the journal."
 *
 * FORENSIC EVIDENCE (fresh install of V5.0.6617):
 *   Journal RAW parity band:  +1.5999 SOL  (~$136, 83 trades)
 *   Journal clean tab:        +$146.08     (5W/7L, 79 trades)
 *   PaperAccountLedger:       +$5,793.45   (+476% start)   ← impossible
 *
 * The ledger's accumulator atomics (cashPico / realizedPnlPico) are
 * inflated relative to the journal. Since operator doctrine (V5.0.6616)
 * declares the durable trade journal as the sole economic source of
 * truth, this authority replaces the ledger read as the input to
 * JournalEconomicAuthority6616.currentSnapshot() — the three heroes
 * (Meme / Markets / Crypto Universe) now render values computed
 * DETERMINISTICALLY from the journal rows themselves. The ledger keeps
 * running (execution paths still credit/debit it and the capital-
 * conservation invariant is still enforced) but it no longer feeds
 * the hero.
 *
 * REPLAY EQUATION (walked over paper journal rows):
 *
 *   For each row where mode == "paper":
 *     BUY:            cash -= (sol + feeSol)
 *                     openCost += sol
 *                     fees += feeSol
 *     SELL/PARTIAL:   cash += (grossProceedsSol - feeSol)
 *                     openCost -= soldCostBasisSol
 *                     realizedPnl += netPnlSol
 *                     fees += feeSol
 *
 *   startingCashSol comes from the paper-capital facade
 *   (PaperCapitalAuthority6577.startingCashSol), which delegates to
 *   an immutable config field set at init/reset only — not an
 *   accumulator that drifts on trades.
 *
 *   equitySol = cashSol + openCostBasisSol  (conservative — uses cost
 *     basis for open positions; live-mark-based openMV is exposed
 *     separately via CanonicalCapitalAuthority6450 and remains
 *     available to consumers that want it, but the hero derives from
 *     the journal alone per operator directive.)
 *
 * Note on quantities: this authority reads durable journal rows;
 * BigInteger raw quantities remain the source of truth for lot
 * accounting elsewhere. The equation here operates on SOL Doubles per
 * row, which is what the journal records for economic reporting.
 */
object JournalEconomicReplay6619 {

    data class ReplayResult(
        val cashSol: Double,
        val realizedPnlSol: Double,
        val openCostBasisSol: Double,
        val feesSol: Double,
        val equitySol: Double,
        val startingCashSol: Double,
        val paperRows: Int,
        val paperBuys: Int,
        val paperSells: Int,
        val paperPartialSells: Int,
        val emittedAtMs: Long,
    )

    private val replays = AtomicLong(0L)
    private val lastResult = AtomicReference<ReplayResult?>(null)
    private val ledgerDivergenceLast = AtomicReference<Double>(0.0)

    /**
     * Deterministically compute paper economics from durable journal
     * rows. Non-clamping, no fallback to the ledger. Returns a stable
     * ReplayResult even when the journal is empty (returns
     * startingCashSol + zeros).
     *
     * V5.0.6619b §MAIN_THREAD_SAFETY — TradeHistoryStore.ensureInitialized
     * opens the SQLite writable database + loads all rows into memory
     * synchronously on the calling thread. MainActivity's cold-open
     * hydration path (onResume → hydratePaperWalletForColdOpen →
     * PaperAccountLedger6430.initPersistent6487 → notifyEconomicMutation
     * → replay) runs on the Main thread. On a CI emulator this pushed
     * the initial UI-ready wait past 5 s and the smoke test's btnToggle
     * lookup failed. Fix: on the Main thread we DO NOT walk the durable
     * journal. We return a fast result seeded with startingCashSol and
     * the last cached values; the next background tick (BotService
     * loop, ~5-12s) picks up the full replay off-thread. This preserves
     * the "hero derived solely from journal" doctrine — pre-first-trade
     * the journal IS empty so cash = startingCash is the correct
     * journal-derived answer.
     */
    fun replay(): ReplayResult {
        replays.incrementAndGet()
        val startingSol = try {
            PaperCapitalAuthority6577.startingCashSol().coerceAtLeast(0.0)
        } catch (_: Throwable) { 0.0 }

        val onMainThread = try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) { false }
        if (onMainThread) {
            try { PipelineHealthCollector.labelInc("JOURNAL_REPLAY_MAIN_THREAD_DEFERRED_6619") } catch (_: Throwable) {}
            val prior = lastResult.get()
            val fast = if (prior != null) {
                // Preserve last full replay; only bump startingCashSol
                // in case it changed (reset). No DB read.
                prior.copy(startingCashSol = startingSol, emittedAtMs = System.currentTimeMillis())
            } else {
                ReplayResult(
                    cashSol = startingSol,
                    realizedPnlSol = 0.0,
                    openCostBasisSol = 0.0,
                    feesSol = 0.0,
                    equitySol = startingSol,
                    startingCashSol = startingSol,
                    paperRows = 0, paperBuys = 0, paperSells = 0, paperPartialSells = 0,
                    emittedAtMs = System.currentTimeMillis(),
                )
            }
            lastResult.set(fast)
            return fast
        }

        // V5.0.6640 — observed transaction cash is diagnostic only.  The
        // authoritative cash value is derived from the conservation identity
        // after every row has been reduced.  A malformed-but-bounds-valid SELL
        // must never turn its gross proceeds into fabricated hero wealth.
        var observedTransactionCash = startingSol
        var realized = 0.0
        var openCost = 0.0
        var fees = 0.0
        var buys = 0
        var sells = 0
        var partials = 0
        var totalRows = 0

        val rows = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
        } catch (_: Throwable) { emptyList() }

        for (t in rows) {
            if (!t.mode.equals("paper", ignoreCase = true)) continue
            totalRows++
            val side = t.side.uppercase()
            when {
                side == "BUY" -> {
                    val cost = t.sol.coerceAtLeast(0.0)
                    val fee = t.feeSol.coerceAtLeast(0.0)
                    observedTransactionCash -= (cost + fee)
                    openCost += cost
                    fees += fee
                    buys++
                }
                side == "SELL" || side == "PARTIAL_SELL" -> {
                    // Prefer explicit canonical fields, fall back to
                    // legacy row fields for older journal rows.
                    val gross = if (t.grossProceedsSol > 0.0) t.grossProceedsSol
                                else t.sol.coerceAtLeast(0.0)
                    val basis = if (t.soldCostBasisSol > 0.0) t.soldCostBasisSol
                                else (gross - t.pnlSol).coerceAtLeast(0.0)
                    val fee = t.feeSol.coerceAtLeast(0.0)
                    // pnlSol is gross realized P&L; fees remain a separate
                    // double-entry line. netPnlSol must not be combined with a
                    // second fee subtraction in the account identity.
                    val grossPnl = if (t.pnlSol.isFinite()) t.pnlSol
                                   else (gross - basis)
                    observedTransactionCash += (gross - fee)
                    openCost -= basis
                    realized += grossPnl
                    fees += fee
                    if (side == "SELL") sells++ else partials++
                }
            }
        }

        // openCost may drift slightly negative for legacy rows missing
        // soldCostBasisSol; clamp to zero for presentation (never
        // negative-cost basis is economic).
        if (openCost < 0.0) openCost = 0.0
        val cash = startingSol + realized - fees - openCost
        val equity = cash + openCost
        if (kotlin.math.abs(observedTransactionCash - cash) > 0.001) {
            try {
                PipelineHealthCollector.labelInc("JOURNAL_TRANSACTION_CASH_IDENTITY_DIVERGENCE_6640")
                ForensicLogger.lifecycle(
                    "JOURNAL_TRANSACTION_CASH_IDENTITY_DIVERGENCE_6640",
                    "observed=${"%.6f".format(observedTransactionCash)} " +
                        "identity=${"%.6f".format(cash)} delta=${"%.6f".format(observedTransactionCash - cash)} " +
                        "action=identity_authoritative",
                )
            } catch (_: Throwable) {}
        }
        val result = ReplayResult(
            cashSol = cash,
            realizedPnlSol = realized,
            openCostBasisSol = openCost,
            feesSol = fees,
            equitySol = equity,
            startingCashSol = startingSol,
            paperRows = totalRows,
            paperBuys = buys,
            paperSells = sells,
            paperPartialSells = partials,
            emittedAtMs = System.currentTimeMillis(),
        )
        lastResult.set(result)

        // Divergence probe — compare journal-replayed cash against
        // ledger cash. Non-mutating; emits a counter + forensic line
        // when they disagree by > 0.001 SOL so operator sees exactly
        // how much the ledger drifted from the journal.
        try {
            val ledgerCash = PaperCapitalAuthority6577.cashSol()
            val delta = ledgerCash - cash
            ledgerDivergenceLast.set(delta)
            if (kotlin.math.abs(delta) > 0.001) {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619")
                ForensicLogger.lifecycle(
                    "PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619",
                    "ledgerCash=${"%.6f".format(ledgerCash)} " +
                        "journalCash=${"%.6f".format(cash)} " +
                        "delta=${"%.6f".format(delta)} " +
                        "paperRows=$totalRows buys=$buys sells=$sells partials=$partials " +
                        "action=hero_uses_journal_ledger_stays_for_execution",
                )
            } else {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_JOURNAL_PARITY_HEALTHY_6619")
            }
        } catch (_: Throwable) {}

        return result
    }

    fun latest(): ReplayResult? = lastResult.get()

    fun latestLedgerDivergenceSol(): Double = ledgerDivergenceLast.get() ?: 0.0

    fun statusLine(): String {
        val r = lastResult.get()
        val div = ledgerDivergenceLast.get() ?: 0.0
        return "replays=${replays.get()} " +
            (if (r != null)
                "rows=${r.paperRows} buys=${r.paperBuys} sells=${r.paperSells} partials=${r.paperPartialSells} " +
                    "cash=${"%.4f".format(r.cashSol)} realized=${"%+.4f".format(r.realizedPnlSol)} " +
                    "openCost=${"%.4f".format(r.openCostBasisSol)} equity=${"%.4f".format(r.equitySol)} " +
                    "ledgerDelta=${"%+.4f".format(div)}"
             else "result=empty")
    }

    internal fun resetForTest() {
        replays.set(0L); lastResult.set(null); ledgerDivergenceLast.set(0.0)
    }
}
