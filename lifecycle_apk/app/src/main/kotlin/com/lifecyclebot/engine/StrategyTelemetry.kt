package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.9.806 — Operator-only telemetry: per-strategy expectancy aggregator.
 *
 * Read-only summary of which `tradingMode` strategies are profitable and
 * which are bleeding. Reads exclusively from `TradeHistoryStore.getAllTrades()`
 * — no new hooks in hot trading paths, no new fields on trade records,
 * nothing that can affect entry/exit decisions or trading volume.
 *
 * Aggregation is performed lazily on each call (no background tasks, no
 * memoised cache that could go stale and silently lie). At journal sizes
 * of <10k trades the full sort takes well under 20ms on-device.
 *
 * Output is plugged into [PipelineHealthCollector.dumpText] so the operator
 * can see, at a glance:
 *   • the top 5 profitable strategies (positive EV)
 *   • the bottom 5 bleeding strategies (negative EV)
 *   • absolute trade counts so a "good EV on 3 trades" doesn't fool anyone
 *
 * NO trading decision consults this object. It exists purely so the
 * operator can answer "which of my strategies is making me money?" without
 * having to dump the journal CSV and pivot it manually.
 */
object StrategyTelemetry {

    data class StrategyMetric(
        val strategy: String,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val scratches: Int,
        val sumPnlPct: Double,
        val meanPnlPct: Double,
        val winRatePct: Double,
        val totalSolPnl: Double,
    ) {
        val isStatisticallyMeaningful: Boolean get() = trades >= 5
    }

    /**
     * Aggregate all SELL trades (those carry the realised pnlPct) by their
     * `tradingMode` field. BUYs are excluded — they have pnlPct=0.0 by
     * definition and would skew the EV calculation.
     */
    fun computeLeaderboard(): List<StrategyMetric> {
        val all = try { TradeHistoryStore.getAllTrades() } catch (_: Throwable) { emptyList() }
        if (all.isEmpty()) return emptyList()

        val sellsByStrategy: Map<String, List<Trade>> = all
            .asSequence()
            .filter { it.side.equals("SELL", ignoreCase = true) }
            .groupBy { it.tradingMode.ifBlank { "UNKNOWN" } }

        return sellsByStrategy.map { (strategy, trades) ->
            val wins = trades.count { it.pnlPct > 1.0 }
            val losses = trades.count { it.pnlPct < -1.0 }
            val scratches = trades.size - wins - losses
            val sumPnl = trades.sumOf { it.pnlPct }
            val mean = if (trades.isNotEmpty()) sumPnl / trades.size else 0.0
            val wlDenom = wins + losses
            val wr = if (wlDenom > 0) (wins.toDouble() / wlDenom) * 100.0 else 0.0
            val totalSol = trades.sumOf { it.pnlSol }
            StrategyMetric(
                strategy   = strategy,
                trades     = trades.size,
                wins       = wins,
                losses     = losses,
                scratches  = scratches,
                sumPnlPct  = sumPnl,
                meanPnlPct = mean,
                winRatePct = wr,
                totalSolPnl = totalSol,
            )
        }
    }

    /** Top-N by mean PnL%, restricted to strategies with ≥5 trades (avoids
     *  "+47% EV on 1 trade" noise dominating the leaderboard). */
    fun winners(n: Int = 5): List<StrategyMetric> =
        computeLeaderboard()
            .filter { it.isStatisticallyMeaningful }
            .sortedByDescending { it.meanPnlPct }
            .take(n)

    /** Bottom-N by mean PnL%, same statistical-meaning filter. */
    fun bleeders(n: Int = 5): List<StrategyMetric> =
        computeLeaderboard()
            .filter { it.isStatisticallyMeaningful }
            .sortedBy { it.meanPnlPct }
            .take(n)

    // ───────────────────────────────────────────────────────────────────
    // V5.9.806 — P1 auto-retirement.
    //
    // A strategy that has bled for ≥50 trades with mean PnL ≤ -5%/trade
    // is auto-disabled. Sub-traders consult `isDisabled(mode)` at their
    // entry-decision point and refuse to emit entries while disabled.
    // The operator can manually un-retire via `clearDisabled()` (used
    // e.g. on fresh re-install when the historical journal carries
    // pre-fix data the operator doesn't want held against the strategy).
    //
    // Memoised for 60s. Aggregation cost is a single journal pass which
    // is also reused by the leaderboard formatter.
    // ───────────────────────────────────────────────────────────────────
    @Volatile private var disabledSet: Set<String> = emptySet()
    @Volatile private var disabledComputedAt: Long = 0L
    private const val DISABLED_TTL_MS = 60_000L
    private const val DISABLE_MIN_TRADES = 50
    private const val DISABLE_MEAN_PNL_THRESHOLD = -5.0

    @Volatile private var manualUnretire = mutableSetOf<String>()

    private fun refreshDisabledSet() {
        val now = System.currentTimeMillis()
        if ((now - disabledComputedAt) < DISABLED_TTL_MS && disabledSet.isNotEmpty()) return
        val fresh = HashSet<String>()
        try {
            for (m in computeLeaderboard()) {
                if (m.trades >= DISABLE_MIN_TRADES &&
                    m.meanPnlPct <= DISABLE_MEAN_PNL_THRESHOLD &&
                    !manualUnretire.contains(m.strategy)
                ) {
                    fresh += m.strategy
                }
            }
        } catch (_: Throwable) {}
        disabledSet = fresh
        disabledComputedAt = now
    }

    fun isDisabled(strategy: String): Boolean {
        val key = strategy.ifBlank { "UNKNOWN" }
        refreshDisabledSet()
        return disabledSet.contains(key)
    }

    fun getDisabled(): Set<String> {
        refreshDisabledSet()
        return disabledSet.toSet()
    }

    /** Operator escape hatch — call once on fresh install to start clean. */
    fun clearDisabled() {
        synchronized(manualUnretire) {
            manualUnretire.addAll(disabledSet)
        }
        disabledSet = emptySet()
        disabledComputedAt = System.currentTimeMillis()
    }

    /**
     * Format a compact human-readable block for embedding in the pipeline
     * health dump. Returns an empty string when there isn't enough data
     * to be useful (so the dump doesn't sprout an empty header at boot).
     */
    fun formatForPipelineDump(): String {
        val all = computeLeaderboard()
        val meaningful = all.filter { it.isStatisticallyMeaningful }
        if (meaningful.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n===== Strategy expectancy (V5.9.806) =====\n")
        sb.append("  (sells with ≥5 trades, sorted by mean PnL%)\n\n")

        val winners = meaningful.sortedByDescending { it.meanPnlPct }.take(5)
        sb.append("  Top 5 winners:\n")
        for (m in winners) {
            sb.append(
                "    %-22s n=%-4d W/L/S=%d/%d/%d  WR=%5.1f%%  EV=%+6.2f%%/trade  PnL=%+.4f SOL\n".format(
                    m.strategy.take(22), m.trades, m.wins, m.losses, m.scratches,
                    m.winRatePct, m.meanPnlPct, m.totalSolPnl,
                )
            )
        }

        val bleeders = meaningful.sortedBy { it.meanPnlPct }.take(5)
        sb.append("\n  Bottom 5 bleeders:\n")
        for (m in bleeders) {
            sb.append(
                "    %-22s n=%-4d W/L/S=%d/%d/%d  WR=%5.1f%%  EV=%+6.2f%%/trade  PnL=%+.4f SOL\n".format(
                    m.strategy.take(22), m.trades, m.wins, m.losses, m.scratches,
                    m.winRatePct, m.meanPnlPct, m.totalSolPnl,
                )
            )
        }

        sb.append("\n  Note: read-only telemetry. No strategy is auto-disabled — operator decides what to retire.\n")

        // V5.9.806 — surface auto-retirement set (P1).
        val disabled = getDisabled()
        if (disabled.isNotEmpty()) {
            sb.append("\n  ⚠ AUTO-DISABLED strategies (≥${DISABLE_MIN_TRADES} trades, mean PnL ≤ ${DISABLE_MEAN_PNL_THRESHOLD}%):\n")
            disabled.forEach { sb.append("    • $it\n") }
            sb.append("    (operator can re-enable via StrategyTelemetry.clearDisabled())\n")
        }

        return sb.toString()
    }
}
