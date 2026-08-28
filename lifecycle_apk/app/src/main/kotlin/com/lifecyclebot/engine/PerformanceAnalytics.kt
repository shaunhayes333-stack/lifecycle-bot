package com.lifecyclebot.engine

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

/**
 * PerformanceAnalytics — Deep trading performance insights
 *
 * Decisive-trade classification:
 * - WIN    = pnlPct >= 0.5
 * - LOSS   = pnlPct <= -2.0
 * - SCRATCH = between those thresholds, ignored in win/loss analytics
 */
object PerformanceAnalytics {

    // V5.0.6576 §P0-3 — thresholds are now derived from the single
    // CanonicalOutcomeClassifier6576 (symmetric ±0.5%). Prior 0.5% / -2.0%
    // asymmetric doctrine put 60+ closes into a "scratch" bucket the other
    // learners were treating as losses/wins — the source of the 0W/7L/67BE
    // vs RewardPurityGate 75L/0BE contradiction.
    private const val WIN_THRESHOLD_PCT = com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.BREAKEVEN_BAND_PCT
    private const val LOSS_THRESHOLD_PCT = -com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.BREAKEVEN_BAND_PCT
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    // V5.9.1378 (P0 #8/#10) — cache the most recent analyze() result + a lifetime
    // closed-trade count so the health-diagnosis (PipelineHealthCollector) can read
    // realized performance truth without re-running analyze() on the main thread.
    @Volatile private var lastSnapshot: AnalyticsSnapshot? = null
    @Volatile private var lifetimeClosed: Int = 0
    /** Most recent analyze() snapshot, or null if analyze() hasn't run yet. */
    fun lastSnapshotOrNull(): AnalyticsSnapshot? = lastSnapshot
    /** Lifetime count of closed (decisive) trades observed by the last analyze() pass. */
    fun lifetimeClosedCount(): Int = lifetimeClosed

    data class AnalyticsSnapshot(
        val totalTrades: Int = 0,
        val winCount: Int = 0,
        val lossCount: Int = 0,
        val breakEvenCount: Int = 0,
        val winRate: Double = 0.0,
        val totalPnlSol: Double = 0.0,
        val avgPnlSol: Double = 0.0,
        val avgWinSol: Double = 0.0,
        val avgLossSol: Double = 0.0,
        val profitFactor: Double = 0.0,
        val expectancy: Double = 0.0,

        val currentStreak: Int = 0,
        val longestWinStreak: Int = 0,
        val longestLossStreak: Int = 0,

        val maxDrawdownSol: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val currentDrawdownPct: Double = 0.0,

        val winRateByPhase: Map<String, Double> = emptyMap(),
        val avgPnlByPhase: Map<String, Double> = emptyMap(),
        val tradeCountByPhase: Map<String, Int> = emptyMap(),

        val winRateByHour: Map<Int, Double> = emptyMap(),
        val tradeCountByHour: Map<Int, Int> = emptyMap(),
        val bestHour: Int = 0,
        val worstHour: Int = 0,

        val winRateByScoreRange: Map<String, Double> = emptyMap(),
        val avgPnlByScoreRange: Map<String, Double> = emptyMap(),
        val optimalScoreRange: String = "",

        val winRateByRegime: Map<String, Double> = emptyMap(),

        val avgHoldMinsWin: Double = 0.0,
        val avgHoldMinsLoss: Double = 0.0,
        val optimalHoldMins: Double = 0.0,

        val insights: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    fun analyze(trades: List<TradeRecord>): AnalyticsSnapshot {
        if (trades.isEmpty()) return AnalyticsSnapshot()

        val closedTradesRaw = trades.filter { sanitizeDouble(it.exitPrice) > 0.0 && it.tsExit > 0L }
        if (closedTradesRaw.isEmpty()) {
            return AnalyticsSnapshot(totalTrades = 0)
        }        // V5.9.1365 — ACCOUNTING POISON GUARD. The CYCLIC lane is a separate
        // $500→$1M compounding ring that deploys its FULL virtual balance each
        // cycle (by design — see CyclicTradeEngine). In paper that ring balloons
        // to thousands of SOL (e.g. 7950 SOL notional). Summing those giant
        // notional swings into the same P&L pool as 0.01-0.5 SOL meme trades
        // produced the snapshot contradiction: dashboard shows +53% WR / healthy,
        // while this analytics block reported -7937 SOL / 5.4% WR / 28-loss
        // streak — pure artifact. The cyclic ring tracks its OWN balance
        // (ringBalanceUsd); it must NOT pollute the meme/lane aggregate. We also
        // defensively drop any trade whose |pnlSol| exceeds 50× its own notional
        // (solIn) — that is a sizing/accounting artifact, never a real outcome
        // (mirrors the StrategyTelemetry guard, memory #58). Per-lane expectancy
        // telemetry elsewhere is untouched; this only de-contaminates the
        // aggregate P&L / streak / profit-factor.
        val closedTrades = closedTradesRaw.filter { t ->
            val m = t.mode.uppercase()
            if (m.contains("CYCLIC")) return@filter false
            val notional = abs(sanitizeDouble(t.solIn)).coerceAtLeast(0.0001)
            val pnl = abs(sanitizeDouble(t.pnlSol))
            pnl <= notional * 50.0
        }
        // V5.0.6499 §1 — TERMINAL CLOSE AUTHORITY. Partial sells are
        // not terminal — analytics must NEVER count them as closed
        // trades. Operator 6498 dump proved 4 partials + 2 terminals
        // were being reported as 6 closed trades / 5W-1L with a
        // PF=3435 nonsense.
        val closedTradesTerminalOnly = closedTrades.filter { t ->
            com.lifecyclebot.engine.truth.TerminalCloseAuthority6499.isTerminalClose(t)
        }
        if (closedTradesTerminalOnly.isEmpty()) {
            return AnalyticsSnapshot(totalTrades = 0)
        }
        // V5.0.6499 §2 — CANONICAL P&L AUTHORITY. Journal `pnlSol`
        // is a display-time approximation; PaperAccountLedger6430
        // holds the atomically-committed realized P&L in pico-SOL.
        // When both are available and disagree by more than a
        // material band, EMIT the divergence label and use the
        // canonical value for the totalPnl field. Downstream WR /
        // PF / expectancy still consume per-trade pnlSol so they
        // remain classifier-consistent — but total P&L cannot lie.
        val canonicalRealizedPnl = try {
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.realizedPnlSol()
        } catch (_: Throwable) { Double.NaN }
        if (closedTrades.isEmpty()) {
            return AnalyticsSnapshot(totalTrades = 0)
        }

        val decisiveTrades = closedTradesTerminalOnly.filter { isDecisive(it) }
        // V5.0.6548 §P1-E — canonical vs window divergence advisory. Emit
        // when the canonical closed-position count differs from the
        // window's terminal-close count so operator can see the drift
        // without breaking the Trades==W+L+BE invariant.
        try {
            val canonicalCount = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
                .closedPositions()
                .distinctBy { "${it.mode.lowercase()}|${it.positionId}|${it.openedAtMs}" }
                .size
            if (canonicalCount != closedTradesTerminalOnly.size) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_CLOSE_COUNT_DIVERGENCE_6548")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANONICAL_CLOSE_COUNT_DIVERGENCE_6548",
                    "canonical=$canonicalCount window=${closedTradesTerminalOnly.size} " +
                        "decisive=${decisiveTrades.size} scratches=${closedTradesTerminalOnly.size - decisiveTrades.size} " +
                        "using=window scope=analytics_snapshot",
                )
            }
        } catch (_: Throwable) {}
        if (decisiveTrades.isEmpty()) {
            return AnalyticsSnapshot(
                totalTrades = 0,
                winCount = 0,
                lossCount = 0,
                winRate = 0.0,
                totalPnlSol = 0.0,
                avgPnlSol = 0.0,
                avgWinSol = 0.0,
                avgLossSol = 0.0,
                profitFactor = 0.0,
                expectancy = 0.0,
                insights = listOf("No decisive trades yet — all recent closes are scratches"),
                warnings = emptyList()
            )
        }

        val wins = decisiveTrades.filter { isWin(it) }
        val losses = decisiveTrades.filter { isLoss(it) }

        val winRate = percentage(wins.size, wins.size + losses.size)
        val journalTotalPnl = decisiveTrades.sumOf { sanitizeDouble(it.pnlSol) }
        // V5.0.6499 §2 — canonical P&L overrides journal aggregate.
        // Emits CANONICAL_PNL_DIVERGENCE_6499 when the two disagree
        // materially (> 0.1 SOL absolute or > 20% relative) so the
        // divergence is visible in the root-cause ENTRY_FINALITY
        // tier without silencing the analytics.
        val totalPnl = if (canonicalRealizedPnl.isFinite()) {
            val absDelta = kotlin.math.abs(canonicalRealizedPnl - journalTotalPnl)
            val relDelta = if (kotlin.math.abs(canonicalRealizedPnl) > 1e-9)
                absDelta / kotlin.math.abs(canonicalRealizedPnl) else 0.0
            if (absDelta > 0.1 && relDelta > 0.20) {
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "CANONICAL_PNL_DIVERGENCE_6499",
                        "journal=${"%.4f".format(journalTotalPnl)} canonical=${"%.4f".format(canonicalRealizedPnl)} absDelta=${"%.4f".format(absDelta)} using=canonical",
                    )
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_PNL_DIVERGENCE_6499")
                } catch (_: Throwable) {}
            }
            canonicalRealizedPnl
        } else journalTotalPnl
        val avgPnl = safeAverage(decisiveTrades.map { sanitizeDouble(it.pnlSol) })
        val avgWin = safeAverage(wins.map { sanitizeDouble(it.pnlSol) })
        val avgLoss = abs(safeAverage(losses.map { sanitizeDouble(it.pnlSol) }))

        val grossProfit = wins.sumOf { sanitizeDouble(it.pnlSol).coerceAtLeast(0.0) }
        val grossLoss = abs(losses.sumOf { sanitizeDouble(it.pnlSol).coerceAtMost(0.0) })
        val profitFactor = if (grossLoss > 0.0) {
            grossProfit / grossLoss
        } else if (grossProfit > 0.0) {
            grossProfit
        } else {
            0.0
        }

        val expectancy = ((winRate / 100.0) * avgWin) - (((100.0 - winRate) / 100.0) * avgLoss)

        val streaks = calculateStreaks(decisiveTrades)
        val drawdown = calculateDrawdown(decisiveTrades)
        val phaseStats = analyzeByPhase(decisiveTrades)
        val timeStats = analyzeByHour(decisiveTrades)
        val scoreStats = analyzeByScore(decisiveTrades)
        val regimeStats = analyzeByRegime(decisiveTrades)
        val holdStats = analyzeHoldTime(decisiveTrades)

        val insights = generateInsights(
            winRate = winRate,
            avgPnl = avgPnl,
            phaseStats = phaseStats,
            timeStats = timeStats,
            scoreStats = scoreStats,
            holdStats = holdStats,
            currentStreak = streaks.first,
            maxDdPct = drawdown.second
        )

        val warnings = generateWarnings(
            winRate = winRate,
            currentStreak = streaks.first,
            maxDdPct = drawdown.second,
            currentDdPct = drawdown.third,
            tradeCount = decisiveTrades.size
        )

        return AnalyticsSnapshot(
            // V5.0.6548 §P1-E — REPORTING SCOPE INVARIANT.
            // Operator report: dashboard said `Trades: 38 (30W / 53L)` where
            // 30 + 53 = 83, not 38. Root cause: `totalTrades` was taken from
            // CanonicalPositionAuthority6441.closedPositions().size (session
            // scope) while wins/losses came from `decisiveTrades` (input
            // window scope). Two different data sources → sums lie.
            // Fix: total = winCount + lossCount + breakEvenCount, all
            // computed from the same closedTradesTerminalOnly window so the
            // invariant `Trades == W + L + BE` holds for every displayed
            // window. Canonical closed-position count is now emitted as a
            // divergence advisory instead of overwriting the total.
            totalTrades = wins.size + losses.size + (closedTradesTerminalOnly.size - decisiveTrades.size),
            winCount = wins.size,
            lossCount = losses.size,
            breakEvenCount = closedTradesTerminalOnly.size - decisiveTrades.size,
            winRate = sanitizeDouble(winRate),
            totalPnlSol = sanitizeDouble(totalPnl),
            avgPnlSol = sanitizeDouble(avgPnl),
            avgWinSol = sanitizeDouble(avgWin),
            avgLossSol = sanitizeDouble(avgLoss),
            profitFactor = sanitizeDouble(profitFactor),
            expectancy = sanitizeDouble(expectancy),

            currentStreak = streaks.first,
            longestWinStreak = streaks.second,
            longestLossStreak = streaks.third,

            maxDrawdownSol = sanitizeDouble(drawdown.first),
            maxDrawdownPct = sanitizeDouble(drawdown.second),
            currentDrawdownPct = sanitizeDouble(drawdown.third),

            winRateByPhase = phaseStats.first,
            avgPnlByPhase = phaseStats.second,
            tradeCountByPhase = phaseStats.third,

            winRateByHour = timeStats.first,
            tradeCountByHour = timeStats.second,
            bestHour = timeStats.third,
            worstHour = timeStats.fourth,

            winRateByScoreRange = scoreStats.first,
            avgPnlByScoreRange = scoreStats.second,
            optimalScoreRange = scoreStats.third,

            winRateByRegime = regimeStats,

            avgHoldMinsWin = sanitizeDouble(holdStats.first),
            avgHoldMinsLoss = sanitizeDouble(holdStats.second),
            optimalHoldMins = sanitizeDouble(holdStats.third),

            insights = insights,
            warnings = warnings
        ).also {
            // V5.9.1378 — publish the snapshot + lifetime sample for health-truth diagnosis.
            lastSnapshot = it
            lifetimeClosed = it.totalTrades
        }
    }

    private fun calculateStreaks(trades: List<TradeRecord>): Triple<Int, Int, Int> {
        var tempStreak = 0
        var longestWin = 0
        var longestLoss = 0

        for (trade in trades.sortedBy { it.tsEntry }) {
            if (isWin(trade)) {
                tempStreak = if (tempStreak >= 0) tempStreak + 1 else 1
                longestWin = max(longestWin, tempStreak)
            } else {
                tempStreak = if (tempStreak <= 0) tempStreak - 1 else -1
                longestLoss = max(longestLoss, abs(tempStreak))
            }
        }

        return Triple(tempStreak, longestWin, longestLoss)
    }

    private fun calculateDrawdown(trades: List<TradeRecord>): Triple<Double, Double, Double> {
        val sorted = trades.sortedBy { it.tsExit }

        // V5.0.6506 §P1 — DRAWDOWN AUTHORITY REBASED ON CANONICAL EQUITY.
        // Operator mandate: "Drawdown authority must use the canonical
        // equity curve: DD = (equityHighWater - currentEquity) /
        // equityHighWater with valid positive high-water initialization.
        // Do not derive account drawdown from losing-streak PnL, closed-
        // trade windows, zero baselines or incomplete restored inventory."
        //
        // Prior logic seeded peak=0 and used a `grossDeployed` proxy when
        // peak was too small — that produced "100% current/max drawdown"
        // with positive account equity because a first-loss trade could
        // arithmetically hit dd/grossDeployed ≈ 100%.
        //
        // New logic seeds the equity curve at the CANONICAL STARTING
        // CASH (PaperAccountLedger6430.startingCashSol) so the equity
        // high-water is always a real positive account value and the DD%
        // matches the operator's mental model: DD as a fraction of the
        // canonical account, never as a fraction of "risk deployed so far".
        val startingCash = try {
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.startingCashSol()
        } catch (_: Throwable) { 0.0 }
        val baseline = if (startingCash.isFinite() && startingCash > 0.0) startingCash else 0.0
        var peak = baseline
        var equity = baseline
        var maxDdSol = 0.0
        var maxDdPct = 0.0

        for (trade in sorted) {
            val pnl = sanitizeDouble(trade.pnlSol)
            equity += pnl
            if (equity > peak) peak = equity

            val dd = peak - equity            // always >= 0
            if (dd > maxDdSol) {
                maxDdSol = dd
                // Denominator is the equity high-water — canonical per
                // operator mandate. Guaranteed positive (baseline > 0
                // when startingCash > 0; otherwise falls back to 100%
                // clamp only if peak==0 which means no positive equity
                // was ever reached).
                maxDdPct = if (peak > 0.0) ((dd / peak) * 100.0).coerceIn(0.0, 100.0) else 0.0
            }
        }

        val curDd = peak - equity
        val currentDdPct = if (curDd > 0.0 && peak > 0.0) {
            ((curDd / peak) * 100.0).coerceIn(0.0, 100.0)
        } else 0.0

        return Triple(
            sanitizeDouble(maxDdSol),
            sanitizeDouble(maxDdPct),
            sanitizeDouble(currentDdPct)
        )
    }

    private fun analyzeByPhase(
        trades: List<TradeRecord>
    ): Triple<Map<String, Double>, Map<String, Double>, Map<String, Int>> {
        // V5.9.1409 — FIX: The Pipeline Health and Dashboard use "PhaseStats" to
        // show "By lane" performance, but it was mistakenly grouping by entryPhase
        // (which is usually empty/unknown) instead of tradingMode (mapped to `mode`).
        val byPhase = trades.groupBy { it.mode.ifBlank { "unknown" } }

        val winRates = byPhase.mapValues { (_, list) ->
            percentage(list.count { isWin(it) }, list.count { isDecisive(it) })
        }

        val avgPnl = byPhase.mapValues { (_, list) ->
            safeAverage(list.map { sanitizeDouble(it.pnlSol) })
        }

        val counts = byPhase.mapValues { (_, list) ->
            list.count { isDecisive(it) }
        }

        return Triple(winRates, avgPnl, counts)
    }

    private fun analyzeByHour(
        trades: List<TradeRecord>
    ): Quadruple<Map<Int, Double>, Map<Int, Int>, Int, Int> {
        val byHour = trades.groupBy { trade ->
            Calendar.getInstance(UTC).apply {
                timeInMillis = trade.tsEntry
            }.get(Calendar.HOUR_OF_DAY)
        }

        val winRates = byHour.mapValues { (_, list) ->
            percentage(list.count { isWin(it) }, list.count { isDecisive(it) })
        }

        val counts = byHour.mapValues { (_, list) ->
            list.count { isDecisive(it) }
        }

        val bestHour = winRates
            .filter { (counts[it.key] ?: 0) >= 3 }
            .maxByOrNull { it.value }
            ?.key ?: 0

        val worstHour = winRates
            .filter { (counts[it.key] ?: 0) >= 3 }
            .minByOrNull { it.value }
            ?.key ?: 0

        return Quadruple(winRates, counts, bestHour, worstHour)
    }

    private fun analyzeByScore(
        trades: List<TradeRecord>
    ): Triple<Map<String, Double>, Map<String, Double>, String> {
        val ranges = listOf(
            "35-45" to (35..45),
            "46-55" to (46..55),
            "56-65" to (56..65),
            "66-75" to (66..75),
            "76+" to (76..100)
        )

        val winRates = mutableMapOf<String, Double>()
        val avgPnl = mutableMapOf<String, Double>()

        for ((label, range) in ranges) {
            val inRange = trades.filter { sanitizeDouble(it.entryScore).toInt() in range }
            val decisive = inRange.filter { isDecisive(it) }
            if (decisive.isNotEmpty()) {
                winRates[label] = percentage(decisive.count { isWin(it) }, decisive.size)
                avgPnl[label] = safeAverage(decisive.map { sanitizeDouble(it.pnlSol) })
            }
        }

        val optimalRange = avgPnl
            .filter { (winRates[it.key] ?: 0.0) >= 50.0 }
            .maxByOrNull { it.value }
            ?.key ?: ""

        return Triple(winRates, avgPnl, optimalRange)
    }

    private fun analyzeByRegime(trades: List<TradeRecord>): Map<String, Double> {
        val byRegime = trades.groupBy { it.mode.ifBlank { "NORMAL" } }

        return byRegime.mapValues { (_, list) ->
            percentage(list.count { isWin(it) }, list.count { isDecisive(it) })
        }
    }

    private fun analyzeHoldTime(trades: List<TradeRecord>): Triple<Double, Double, Double> {
        val wins = trades.filter { isWin(it) && sanitizeDouble(it.heldMins) > 0.0 }
        val losses = trades.filter { isLoss(it) && sanitizeDouble(it.heldMins) > 0.0 }

        val avgHoldWin = safeAverage(wins.map { sanitizeDouble(it.heldMins) })
        val avgHoldLoss = safeAverage(losses.map { sanitizeDouble(it.heldMins) })

        val profitable = trades.filter { isWin(it) && sanitizeDouble(it.heldMins) > 0.0 }
        val optimalHold = if (profitable.isNotEmpty()) {
            profitable.sortedByDescending { sanitizeDouble(it.pnlSol) }
                .take(5)
                .map { sanitizeDouble(it.heldMins) }
                .average()
        } else {
            avgHoldWin
        }

        return Triple(
            sanitizeDouble(avgHoldWin),
            sanitizeDouble(avgHoldLoss),
            sanitizeDouble(optimalHold)
        )
    }

    private fun generateInsights(
        winRate: Double,
        avgPnl: Double,
        phaseStats: Triple<Map<String, Double>, Map<String, Double>, Map<String, Int>>,
        timeStats: Quadruple<Map<Int, Double>, Map<Int, Int>, Int, Int>,
        scoreStats: Triple<Map<String, Double>, Map<String, Double>, String>,
        holdStats: Triple<Double, Double, Double>,
        currentStreak: Int,
        maxDdPct: Double
    ): List<String> {
        val insights = mutableListOf<String>()

        val bestPhase = phaseStats.first
            .filter { (phaseStats.third[it.key] ?: 0) >= 3 }
            .maxByOrNull { it.value }

        bestPhase?.let {
            insights.add("🎯 Best phase: ${it.key} (${it.value.fmt(1)}% win rate)")
        }

        val bestHour = timeStats.third
        val bestHourWR = timeStats.first[bestHour] ?: 0.0
        if (bestHourWR > winRate + 10.0) {
            insights.add("⏰ Best hour: ${bestHour}:00 UTC (${bestHourWR.fmt(1)}% win rate)")
        }

        if (scoreStats.third.isNotBlank()) {
            insights.add("📊 Optimal entry score: ${scoreStats.third}")
        }

        if (holdStats.first > 0.0 && holdStats.second > 0.0) {
            if (holdStats.second > holdStats.first * 1.5) {
                insights.add(
                    "⏱ Cut losses faster — losers held ${holdStats.second.fmt(1)}min vs winners ${holdStats.first.fmt(1)}min"
                )
            }
        }

        if (currentStreak >= 3) {
            insights.add("🔥 Hot streak! $currentStreak consecutive wins")
        }

        if (avgPnl > 0.0 && maxDdPct < 15.0) {
            insights.add("✅ Positive expectancy with controlled drawdown")
        }

        return insights
    }

    private fun generateWarnings(
        winRate: Double,
        currentStreak: Int,
        maxDdPct: Double,
        currentDdPct: Double,
        tradeCount: Int
    ): List<String> {
        val warnings = mutableListOf<String>()

        if (winRate < 40.0 && tradeCount >= 10) {
            warnings.add("⚠️ Win rate below 40% — review entry criteria")
        }

        if (currentStreak <= -3) {
            warnings.add("🔴 Cold streak: ${abs(currentStreak)} consecutive losses")
        }

        if (currentDdPct > 20.0) {
            warnings.add("📉 Currently in ${currentDdPct.fmt(1)}% drawdown")
        }

        if (maxDdPct > 30.0) {
            warnings.add("⚠️ Max drawdown ${maxDdPct.fmt(1)}% — consider reducing size")
        }

        return warnings
    }

    fun formatSummary(stats: AnalyticsSnapshot): String {
        val sb = StringBuilder()

        sb.append("📊 *PERFORMANCE ANALYTICS*\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n\n")
        sb.append("*Overall:*\n")
        sb.append("  Trades: ").append(stats.totalTrades).append(" (")
            .append(stats.winCount).append("W / ")
            .append(stats.lossCount).append("L / ")
            .append(stats.breakEvenCount).append("BE)\n")
        sb.append("  Win Rate: ").append(stats.winRate.fmt(1)).append("%\n")
        sb.append("  Total P&L: ").append(stats.totalPnlSol.fmt(4)).append(" SOL\n")
        sb.append("  Profit Factor: ").append(stats.profitFactor.fmt(2)).append("\n")
        sb.append("  Expectancy: ").append(stats.expectancy.fmt(4)).append(" SOL/trade\n\n")

        sb.append("*Risk:*\n")
        sb.append("  Max Drawdown: ").append(stats.maxDrawdownPct.fmt(1)).append("%\n")
        sb.append("  Current DD: ").append(stats.currentDrawdownPct.fmt(1)).append("%\n")
        sb.append("  Longest Loss Streak: ").append(stats.longestLossStreak).append("\n\n")

        if (stats.insights.isNotEmpty()) {
            sb.append("*Insights:*\n")
            for (insight in stats.insights) {
                sb.append("  ").append(insight).append('\n')
            }
            sb.append('\n')
        }

        if (stats.warnings.isNotEmpty()) {
            sb.append("*Warnings:*\n")
            for (warning in stats.warnings) {
                sb.append("  ").append(warning).append('\n')
            }
        }

        return sb.toString()
    }

    private fun isWin(trade: TradeRecord): Boolean {
        // V5.0.6576 §P0-3 — delegate to CanonicalOutcomeClassifier6576.
        return com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576
            .classifyReadonly(sanitizeDouble(trade.pnlPct)) == com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.Class.WIN
    }

    private fun isLoss(trade: TradeRecord): Boolean {
        return com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576
            .classifyReadonly(sanitizeDouble(trade.pnlPct)) == com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.Class.LOSS
    }

    private fun isDecisive(trade: TradeRecord): Boolean {
        return com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576
            .classifyReadonly(sanitizeDouble(trade.pnlPct)) != com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.Class.BREAKEVEN
    }

    private fun percentage(count: Int, total: Int): Double {
        return if (total > 0) (count.toDouble() / total.toDouble()) * 100.0 else 0.0
    }

    private fun safeAverage(values: List<Double>): Double {
        return if (values.isNotEmpty()) values.average() else 0.0
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }

    private fun Double.fmt(d: Int): String {
        return String.format(Locale.US, "%.${d}f", sanitizeDouble(this))
    }
}