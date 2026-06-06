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
            // V5.9.1043 — also collapse legacy bin names at read time so
            // historical trades recorded before V5.9.1038's choke-point
            // normalization (e.g. BLUE_CHIP) merge with the canonical
            // current names (BLUECHIP). Without this, the leaderboard
            // still shows ghost duplicate bins from old persisted data.
            .groupBy {
                // V5.9.1331 — a blank tradingMode is an unclassified-lane trade, not a
                // distinct "UNKNOWN" strategy. Bin it as STANDARD (the convention used at
                // every other lane-name site) so historical blank-mode rows stop forming a
                // phantom bucket the honest backtest misreads as a losing lane to retire.
                val raw = it.tradingMode.ifBlank { "STANDARD" }
                val norm = try { TradeHistoryStore.normalizeTradeModeName(raw) } catch (_: Throwable) { raw }
                if (norm.isBlank()) "STANDARD" else norm
            }

        return sellsByStrategy.map { (strategy, trades) ->
            val wins = trades.count { it.pnlPct > 1.0 }
            val losses = trades.count { it.pnlPct < -1.0 }
            val scratches = trades.size - wins - losses
            // V5.9.1357 — clamp per-trade pnl% for the EV/mean view so a single
            // feed-artifact outlier (e.g. +1,340,125% from a glitched price tick)
            // can't make a bleeding lane read as a megawinner (the "lie of
            // averages"). totalSolPnl below stays RAW so real SOL accounting is
            // untouched — this only sanitizes the percentage expectancy display.
            fun sanePct(p: Double): Double = when {
                p.isNaN() || p.isInfinite() -> 0.0
                p > 5000.0 -> 5000.0
                p < -100.0 -> -100.0
                else -> p
            }
            val sumPnl = trades.sumOf { sanePct(it.pnlPct) }
            val mean = if (trades.isNotEmpty()) sumPnl / trades.size else 0.0
            val wlDenom = wins + losses
            val wr = if (wlDenom > 0) (wins.toDouble() / wlDenom) * 100.0 else 0.0
            // V5.9.1360 P0.4 — SOL ACCOUNTING SANITY. totalSolPnl was a RAW sum, so
            // a single feed-artifact close (glitched near-zero price → astronomical
            // pnl) produced impossible values like +62,218 SOL that then poisoned
            // tuning/readiness/live-gating. A real meme close cannot net more than
            // ~50x its own deployed size (that is already a +5000% move). Any trade
            // whose |pnlSol| exceeds 50x its position size — or whose size is
            // missing while pnlSol is huge — is excluded from the SOL total used for
            // learning. Counters surface how often this fires.
            fun saneSol(t: com.lifecyclebot.data.Trade): Double? {
                val p = t.pnlSol
                if (p.isNaN() || p.isInfinite()) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PNL_UNIT_MISMATCH_REJECTED") } catch (_: Throwable) {}
                    return null
                }
                val size = t.sol.takeIf { it > 0.0 } ?: 0.0
                val cap = if (size > 0.0) size * 50.0 else 5.0  // no-size fallback: 5 SOL hard ceiling
                if (kotlin.math.abs(p) > cap) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ACCOUNTING_OUTLIER_NOT_TRAINED") } catch (_: Throwable) {}
                    return null
                }
                return p
            }
            val totalSol = trades.mapNotNull { saneSol(it) }.sum()
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
    // V5.9.1053 — NO AUTO-DISABLE. Operator directive:
    // "no strategies should be permanently disabled. we are meant to help
    // anything lagging to learn better. any issue like this is meant to
    // be self healed."
    //
    // isDisabled() always returns false. BrainConsensusGate may still
    // emit a SOFT advisory when a strategy is bleeding, but it can never
    // produce a HARD_BLOCK via this path. The strategy keeps running,
    // keeps collecting samples, and self-heals as WR improves.
    //
    // clearDisabled() and clearDisabledIfInsufficient() are kept as no-ops
    // so existing call sites compile without changes.
    // ───────────────────────────────────────────────────────────────────

    /** Always returns false — no strategy is ever auto-disabled. */
    fun isDisabled(strategy: String): Boolean = false

    /** Always returns empty — nothing is disabled. */
    fun getDisabled(): Set<String> = emptySet()

    /** No-op — nothing to clear. */
    fun clearDisabled() {}

    /** No-op — nothing to clear. */
    fun clearDisabledIfInsufficient(minTrades: Int) {}


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
        // V5.9.1048 — bin glossary clarifier. Operator V5.9.1047 dump
        // flagged STANDARD lane (n=32 WR=100% +207%/trade) as
        // suspicious-good. STANDARD is the default tradingMode assigned
        // when a trade has no specific lane affinity (TokenMemory.kt
        // fallback) — typically high-quality v3 entries that don't fit
        // a curated bucket. The 100% WR is partly survivor-bias: many
        // STANDARD candidates get auto-promoted to a specific lane mid-
        // trade and get re-classified, leaving only the cleanest setups
        // in the bin. Treat it as a "clean V3" tier, not a separate
        // strategy to scale.
        sb.append("  Bin glossary: STANDARD=V3 default (no lane affinity)  ·  BLUECHIP/SHITCOIN/MOONSHOT/etc=lane-specific  ·  CASHGEN/TREASURY=lifecycle exits\n")

        // V5.9.1053: no auto-disable — strategies self-heal via learning

        return sb.toString()
    }
}
