package com.lifecyclebot.backtest

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🧪 BACKTEST ENGINE — V5.9.375
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Offline strategy replay harness. Reads historical SELL trades from
 * TradeHistoryStore (and optionally per-trader paper state), re-applies a
 * pluggable StrategyConfig, and produces hypothetical PnL/WR/drawdown so new
 * strategies can be validated against 17k+ historical trades BEFORE they ever
 * touch live flow.
 *
 * This is the counter to "learn in production only" — the V5.9.374 lane fix
 * revealed 5000+ trades of signal that was wasted. With this engine a new
 * strategy can get evaluated in minutes, not months.
 *
 * PUBLIC API (stable):
 *   BacktestEngine.replay(cfg)                 → BacktestResult
 *   BacktestEngine.compareStrategies(list)     → List<BacktestResult>
 *   BacktestEngine.assetClassBreakdown()       → Map<AssetClass, BacktestResult>
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object BacktestEngine {

    private const val TAG = "🧪Backtest"

    enum class AssetClass { MEME, ALT, PERPS, STOCK, FOREX, METAL, COMMODITY, UNKNOWN }

    /**
     * Map a Trade's tradingMode / reason / mint shape to an AssetClass bucket.
     * TradeHistoryStore stores every closed trade in one table; this routes each
     * one to its lane based on metadata. `tradingMode` is the primary signal;
     * fallback heuristics catch older records that didn't carry it.
     */
    private fun classify(t: Trade): AssetClass {
        val mode = t.tradingMode.uppercase()
        if ("FOREX" in mode || t.mint.length in 6..7 && t.mint.contains("USD", ignoreCase = true)) return AssetClass.FOREX
        if ("METAL" in mode || t.mint in setOf("XAU", "XAG", "XPT", "XPD", "XCU", "XAL", "XNI", "ZINC", "LEAD", "TIN", "IRON", "COBALT", "LITHIUM", "URANIUM", "XTI")) return AssetClass.METAL
        if ("COMM" in mode || t.mint in setOf("BRENT", "WTI", "NATGAS", "RBOB", "HEATING", "CORN", "WHEAT", "SOYBEAN", "COFFEE", "COCOA", "SUGAR", "COTTON", "LUMBER", "OJ", "CATTLE", "HOGS")) return AssetClass.COMMODITY
        if ("PERPS" in mode || "LEVERAGE" in mode || "LEV" in mode) return AssetClass.PERPS
        if ("STOCK" in mode) return AssetClass.STOCK
        if ("ALT" in mode) return AssetClass.ALT
        // Default: Solana mint addresses = meme
        if (t.mint.length >= 32) return AssetClass.MEME
        return AssetClass.UNKNOWN
    }

    /**
     * Pluggable strategy config. Every new strategy proposed for an asset class
     * implements this interface; the engine runs it against historical records
     * and reports the delta vs. what actually happened.
     */
    data class StrategyConfig(
        val name: String,
        val assetClass: AssetClass,
        /** Entry filter: return true if the historical trade would've been taken. */
        val shouldEnter: (Trade) -> Boolean = { true },
        /** Exit transformer: given the actual pnlPct, return the hypothetical pnlPct
         *  under the new exit rules. E.g. tighter stop = clamp negatives harder. */
        val transformPnlPct: (Trade) -> Double = { it.pnlPct },
        /** Per-trade size multiplier (leverage/sizing strategy). Default = 1.0. */
        val sizeMultiplier: (Trade) -> Double = { 1.0 },
    )

    data class BacktestResult(
        val strategyName: String,
        val assetClass: AssetClass,
        val tradesEvaluated: Int,
        val tradesTaken: Int,
        val wins: Int,
        val losses: Int,
        val scratches: Int,
        val totalPnlPct: Double,
        val avgPnlPct: Double,
        val maxDrawdownPct: Double,
        val maxRunupPct: Double,
        val hypotheticalPnlSol: Double,
        val actualPnlSol: Double,
        val pnlDeltaSol: Double,
        val sharpe: Double,
        val notes: List<String>,
    ) {
        val winRate: Double get() = if (wins + losses > 0) wins * 100.0 / (wins + losses) else 0.0
        fun oneLine(): String = "[$strategyName/$assetClass] took=$tradesTaken/${tradesEvaluated} " +
            "WR=${"%.1f".format(winRate)}% avgPnl=${"%.2f".format(avgPnlPct)}% " +
            "totalPnl=${"%.2f".format(totalPnlPct)}% sharpe=${"%.2f".format(sharpe)} " +
            "Δsol=${"%+.4f".format(pnlDeltaSol)}"
    }

    /**
     * Run a strategy across historical sells and return the replay result.
     * Reads from TradeHistoryStore (the full SQLite trade table). Asset-class
     * filter is applied from the StrategyConfig.
     */
    fun replay(cfg: StrategyConfig): BacktestResult {
        val allTrades = try { TradeHistoryStore.getAllTrades() } catch (_: Exception) { emptyList() }
        val sells = allTrades.filter { it.side.equals("SELL", true) }

        val inAsset = sells.filter { classify(it) == cfg.assetClass || cfg.assetClass == AssetClass.UNKNOWN }
        val taken = inAsset.filter { cfg.shouldEnter(it) }

        if (taken.isEmpty()) {
            return BacktestResult(
                strategyName = cfg.name,
                assetClass = cfg.assetClass,
                tradesEvaluated = inAsset.size,
                tradesTaken = 0,
                wins = 0, losses = 0, scratches = 0,
                totalPnlPct = 0.0, avgPnlPct = 0.0,
                maxDrawdownPct = 0.0, maxRunupPct = 0.0,
                hypotheticalPnlSol = 0.0,
                actualPnlSol = inAsset.sumOf { it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol },
                pnlDeltaSol = 0.0,
                sharpe = 0.0,
                notes = listOf("No trades matched entry filter (evaluated=${inAsset.size} in asset=${cfg.assetClass})"),
            )
        }

        var wins = 0; var losses = 0; var scratches = 0
        var totalPnlPct = 0.0
        var hypotheticalSol = 0.0
        var actualSol = 0.0
        var equity = 0.0
        var peak = 0.0
        var trough = 0.0
        var maxDD = 0.0
        var maxRunup = 0.0
        val returns = mutableListOf<Double>()

        for (t in taken) {
            val hypPct = cfg.transformPnlPct(t)
            val sizeMul = cfg.sizeMultiplier(t)
            val tradeCostSol = t.sol
            val hypSol = tradeCostSol * (hypPct / 100.0) * sizeMul
            val actSol = t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol

            totalPnlPct += hypPct
            hypotheticalSol += hypSol
            actualSol += actSol
            returns.add(hypPct)

            when {
                hypPct >= 1.0 -> wins++
                hypPct <= -0.5 -> losses++
                else -> scratches++
            }

            equity += hypPct
            if (equity > peak) peak = equity
            if (equity < trough) trough = equity
            val dd = peak - equity
            if (dd > maxDD) maxDD = dd
            val runup = equity - trough
            if (runup > maxRunup) maxRunup = runup
        }

        val avgPnl = if (taken.isNotEmpty()) totalPnlPct / taken.size else 0.0
        val stdev = if (returns.size > 1) {
            val mean = returns.average()
            val v = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
            kotlin.math.sqrt(v)
        } else 0.0
        val sharpe = if (stdev > 0) avgPnl / stdev * kotlin.math.sqrt(returns.size.toDouble()) else 0.0

        val notes = mutableListOf<String>()
        notes.add("Evaluated ${inAsset.size} historical sells in ${cfg.assetClass}")
        notes.add("Strategy kept ${taken.size} (${((taken.size * 100.0) / max(1, inAsset.size)).toInt()}%)")
        if (hypotheticalSol > actualSol) {
            notes.add("✅ Strategy beats actual by ${"%.4f".format(hypotheticalSol - actualSol)} SOL")
        } else if (hypotheticalSol < actualSol) {
            notes.add("⚠️ Strategy UNDER-PERFORMS actual by ${"%.4f".format(actualSol - hypotheticalSol)} SOL")
        }

        return BacktestResult(
            strategyName = cfg.name,
            assetClass = cfg.assetClass,
            tradesEvaluated = inAsset.size,
            tradesTaken = taken.size,
            wins = wins, losses = losses, scratches = scratches,
            totalPnlPct = totalPnlPct,
            avgPnlPct = avgPnl,
            maxDrawdownPct = maxDD,
            maxRunupPct = maxRunup,
            hypotheticalPnlSol = hypotheticalSol,
            actualPnlSol = actualSol,
            pnlDeltaSol = hypotheticalSol - actualSol,
            sharpe = sharpe,
            notes = notes,
        )
    }

    /** Run multiple strategies side-by-side against the same historical window. */
    fun compareStrategies(configs: List<StrategyConfig>): List<BacktestResult> =
        configs.map { replay(it) }.sortedByDescending { it.hypotheticalPnlSol }

    /**
     * Baseline report — runs a passthrough strategy per asset class so the user
     * can see exactly what the bot actually did historically, segmented.
     */
    fun assetClassBreakdown(): Map<AssetClass, BacktestResult> {
        val out = mutableMapOf<AssetClass, BacktestResult>()
        AssetClass.values().forEach { ac ->
            val cfg = StrategyConfig(
                name = "BASELINE",
                assetClass = ac,
                // passthrough — take everything, use actual pnl, no transform
            )
            val r = replay(cfg)
            if (r.tradesEvaluated > 0) out[ac] = r
        }
        return out
    }

    /**
     * Human-readable report. Hooked from UI Dev panel / log dumps.
     */
    fun renderReport(results: List<BacktestResult>): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine("🧪 BACKTEST REPORT — V5.9.375")
        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine()
        if (results.isEmpty()) {
            sb.appendLine("(no results)")
            return sb.toString()
        }
        results.forEach { r ->
            sb.appendLine("─── ${r.strategyName} / ${r.assetClass} ───")
            sb.appendLine("  Evaluated:     ${r.tradesEvaluated}")
            sb.appendLine("  Taken:         ${r.tradesTaken}")
            sb.appendLine("  W/L/S:         ${r.wins}/${r.losses}/${r.scratches}")
            sb.appendLine("  Win Rate:      ${"%.1f".format(r.winRate)}%")
            sb.appendLine("  Avg PnL:       ${"%.2f".format(r.avgPnlPct)}%")
            sb.appendLine("  Total PnL:     ${"%.2f".format(r.totalPnlPct)}%")
            sb.appendLine("  Max DD:        ${"%.2f".format(r.maxDrawdownPct)}%")
            sb.appendLine("  Max Runup:     ${"%.2f".format(r.maxRunupPct)}%")
            sb.appendLine("  Sharpe (approx): ${"%.2f".format(r.sharpe)}")
            sb.appendLine("  Hypothetical:  ${"%+.4f".format(r.hypotheticalPnlSol)} SOL")
            sb.appendLine("  Actual:        ${"%+.4f".format(r.actualPnlSol)} SOL")
            sb.appendLine("  Delta:         ${"%+.4f".format(r.pnlDeltaSol)} SOL")
            r.notes.forEach { sb.appendLine("  · $it") }
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * Convenience: run a strategy and log the report.
     */
    fun runAndLog(cfg: StrategyConfig): BacktestResult {
        val r = replay(cfg)
        ErrorLogger.info(TAG, r.oneLine())
        return r
    }

    /**
     * Convenience: print a full asset-class baseline to the log.
     */
    fun logAssetClassBaseline() {
        val results = assetClassBreakdown().values.toList()
            .sortedByDescending { it.hypotheticalPnlSol }
        ErrorLogger.info(TAG, "=== BASELINE BREAKDOWN ===")
        results.forEach { ErrorLogger.info(TAG, it.oneLine()) }
    }
}
