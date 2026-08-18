package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6461 §P0-#3 — REBUILD PAPER ACCOUNT FROM ECONOMIC EVENTS.
 *
 * OPERATOR MANDATE (6457 dump):
 *   "Replay the current run from confirmed BUY fills, PARTIAL sells,
 *    FULL sells, and fees. Reconstruct cash, remaining cost, realized
 *    PnL, and total equity. Do not blindly migrate the old aggregate
 *    counter."
 *
 * DESIGN
 * ──────
 * Read-only replay of TradeHistoryStore into a canonical
 * (cash, openCostBasis, realizedPnl, fees, equity) snapshot derived
 * *only* from the confirmed economic events. This is the AUTHORITATIVE
 * shadow the reconciler compares against PaperAccountLedger6430's live
 * counters. Divergence beyond ledger tolerance emits a forensic event.
 *
 * Replay algebra (per Trade row, PAPER mode, valid accounting):
 *
 *   BUY:            cash -= (sol + feeSol) ; openCost += sol
 *   PARTIAL_SELL:   cash += (netProceeds − feeSol) ; openCost -= costBasisSold
 *                   realized += (netProceeds − costBasisSold)
 *                   feesPaid += feeSol
 *   SELL (full):    same as PARTIAL_SELL for accounting; openCost clamped to 0
 *
 * `netProceeds` and `costBasisSold` come from Trade fields:
 *   - `sol` is gross proceeds on SELL/PARTIAL_SELL rows
 *   - `pnlSol` is realized (gross) delta already computed by Executor
 *   - `feeSol` is the sell-side fee
 *
 * NOTE: This module NEVER mutates the ledger. It emits a report and lets
 * the reconciler decide whether to heal PaperAccountLedger6430. This
 * preserves the operator invariant "no historical rewrite by a passive
 * telemetry mirror". Healing is done in-place at BotService.repair sites.
 */
object PaperAccountReplay6461 {

    data class Snapshot(
        val startingCashSol: Double,
        val cashSol: Double,
        val openCostBasisSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val buyCount: Int,
        val partialSellCount: Int,
        val fullSellCount: Int,
        val skippedInvalidPnl: Int,
        val equityShadowSol: Double, // cash + openCostBasis (no mark)
    )

    private val lastSnapshot = AtomicReference<Snapshot?>(null)
    private val replays = AtomicLong(0L)
    private val divergences = AtomicLong(0L)

    /**
     * Run a full replay from TradeHistoryStore. Cheap enough to call
     * once per pipeline audit tick (bounded by getAllValidTradesSnapshot
     * limit). Returns Snapshot even when store empty.
     */
    fun replay(startingCashSol: Double): Snapshot {
        replays.incrementAndGet()
        val trades: List<Trade> = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
        } catch (_: Throwable) { emptyList() }

        var cash = startingCashSol.coerceAtLeast(0.0)
        var openCost = 0.0
        var realized = 0.0
        var fees = 0.0
        var buys = 0
        var partials = 0
        var fulls = 0
        var skipped = 0

        // Trades are newest-first from asReversed; economic replay must be
        // oldest-first so cash/openCost flow correctly.
        for (t in trades.asReversed()) {
            if (!t.mode.equals("paper", ignoreCase = true)) continue
            val side = t.side.uppercase()
            val sol = t.sol.takeIf { it.isFinite() && it >= 0.0 } ?: continue
            val fee = t.feeSol.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            when (side) {
                "BUY" -> {
                    val total = sol + fee
                    // Skip impossible buys — a paper buy that would take
                    // cash negative indicates a corrupted row; the ledger
                    // gate already refuses these in real time.
                    if (cash - total < -1e-6) { skipped++; continue }
                    cash -= total
                    openCost += sol
                    fees += fee
                    buys++
                }
                "PARTIAL_SELL", "SELL" -> {
                    // V5.0.6461 — economic replay uses ONLY confirmed
                    // Trade fields. pnlSol is the source of realized
                    // delta (gross of fees per PaperAccountLedger6430
                    // convention). Cash mutation uses NET PROCEEDS =
                    // gross − fee. Cost basis reduction reconstructed
                    // as (gross − pnlSol).
                    val gross = sol
                    val pnl = t.pnlSol.takeIf { it.isFinite() } ?: 0.0
                    // Sanity gate — the Fi4FaM incident had pnlSol values
                    // filled with pnlPct scalars (30x too large). Skip
                    // rows where |pnl| > 30 SOL (see PartialSellUnitTypes6461).
                    if (kotlin.math.abs(pnl) > 30.0) {
                        skipped++
                        try {
                            ForensicLogger.lifecycle(
                                "PAPER_REPLAY_SKIP_FI4FAM_ROW_6461",
                                "side=$side mint=${t.mint.take(10)} pnlSol=${"%.4f".format(pnl)} sol=${"%.4f".format(gross)}",
                            )
                            PipelineHealthCollector.labelInc("PAPER_REPLAY_SKIP_FI4FAM_ROW_6461")
                        } catch (_: Throwable) {}
                        continue
                    }
                    val costBasisSold = (gross - pnl).coerceAtLeast(0.0)
                    val netProceeds = (gross - fee).coerceAtLeast(0.0)
                    cash += netProceeds
                    openCost = (openCost - costBasisSold).coerceAtLeast(0.0)
                    realized += pnl
                    fees += fee
                    if (side == "PARTIAL_SELL") partials++ else fulls++
                }
                else -> {
                    // Ignore non-economic rows.
                }
            }
        }

        val snap = Snapshot(
            startingCashSol = startingCashSol,
            cashSol = cash,
            openCostBasisSol = openCost,
            realizedPnlSol = realized,
            feesSol = fees,
            buyCount = buys,
            partialSellCount = partials,
            fullSellCount = fulls,
            skippedInvalidPnl = skipped,
            equityShadowSol = cash + openCost,
        )
        lastSnapshot.set(snap)
        return snap
    }

    /**
     * Compare replay against PaperAccountLedger6430. Emits a forensic
     * report when |ledger.cash − replay.cash| > tolerance OR
     * |ledger.realized − replay.realized| > tolerance. Non-mutating.
     */
    fun auditAgainstLedger(startingCashSol: Double, toleranceSol: Double = 0.01): Snapshot {
        val snap = replay(startingCashSol)
        val ledgerCash = try { PaperAccountLedger6430.cashSol() } catch (_: Throwable) { Double.NaN }
        val ledgerRealized = try { PaperAccountLedger6430.realizedPnlSol() } catch (_: Throwable) { Double.NaN }
        val ledgerOpen = try { PaperAccountLedger6430.openCostBasisSol() } catch (_: Throwable) { Double.NaN }

        val cashDelta = if (ledgerCash.isFinite()) snap.cashSol - ledgerCash else 0.0
        val realizedDelta = if (ledgerRealized.isFinite()) snap.realizedPnlSol - ledgerRealized else 0.0
        val openDelta = if (ledgerOpen.isFinite()) snap.openCostBasisSol - ledgerOpen else 0.0

        val diverged = kotlin.math.abs(cashDelta) > toleranceSol ||
                       kotlin.math.abs(realizedDelta) > toleranceSol ||
                       kotlin.math.abs(openDelta) > toleranceSol
        if (diverged) {
            divergences.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "PAPER_REPLAY_DIVERGENCE_6461",
                    "cashΔ=${"%.4f".format(cashDelta)} realizedΔ=${"%.4f".format(realizedDelta)} openΔ=${"%.4f".format(openDelta)} " +
                        "replay(cash=${"%.4f".format(snap.cashSol)} realized=${"%.4f".format(snap.realizedPnlSol)} openCost=${"%.4f".format(snap.openCostBasisSol)}) " +
                        "ledger(cash=${"%.4f".format(ledgerCash)} realized=${"%.4f".format(ledgerRealized)} openCost=${"%.4f".format(ledgerOpen)}) " +
                        "buys=${snap.buyCount} partials=${snap.partialSellCount} fulls=${snap.fullSellCount} fi4famSkips=${snap.skippedInvalidPnl}",
                )
                PipelineHealthCollector.labelInc("PAPER_REPLAY_DIVERGENCE_6461")
            } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("PAPER_REPLAY_CONVERGENT_6461") } catch (_: Throwable) {}
        }
        return snap
    }

    fun lastSnapshot(): Snapshot? = lastSnapshot.get()

    fun statusLine(): String {
        val s = lastSnapshot.get()
        return if (s == null) "no_replay_yet replays=${replays.get()} divergences=${divergences.get()}"
        else "cash=${"%.4f".format(s.cashSol)} openCost=${"%.4f".format(s.openCostBasisSol)} " +
            "realized=${"%+.4f".format(s.realizedPnlSol)} fees=${"%.4f".format(s.feesSol)} " +
            "buys=${s.buyCount} partials=${s.partialSellCount} fulls=${s.fullSellCount} " +
            "fi4famSkips=${s.skippedInvalidPnl} replays=${replays.get()} divergences=${divergences.get()}"
    }

    internal fun resetForTest() {
        lastSnapshot.set(null)
        replays.set(0L); divergences.set(0L)
    }
}
