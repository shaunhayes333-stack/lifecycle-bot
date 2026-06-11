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

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0
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
        }

        // V5.9.1365 — ACCOUNTING POISON GUARD. The CYCLIC lane is a separate
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
        if (closedTrades.isEmpty()) {
            return AnalyticsSnapshot(totalTrades = 0)
        }

        val decisiveTrades = closedTrades.filter { isDecisive(it) }
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
        val totalPnl = decisiveTrades.sumOf { sanitizeDouble(it.pnlSol) }
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
            totalTrades = decisiveTrades.size,
            winCount = wins.size,
            lossCount = losses.size,
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

        // V5.9.1499 — DRAWDOWN INTEGRITY. The previous logic seeded peak=0 and
        // only recorded a DD% once peak crossed a 0.05 SOL floor. Consequence:
        // a curve that loses FIRST (peak stuck at 0) or whose wins/losses net to
        // a small peak reported 0.0% DD even with a real intra-session dip —
        // exactly the "Max Drawdown 0.0%" seen with +3.8 SOL / 175 losses.
        // Correct definition: peak = running max of equity INCLUDING the implicit
        // 0 starting baseline; maxDD(SOL) = largest peak→trough drop anywhere on
        // the curve. The % is expressed against the running peak when it is
        // meaningfully positive, otherwise against the gross capital deployed up
        // to that trough (so a pre-peak bleed still yields a real, bounded %).
        var peak = 0.0          // running equity high-water mark (>= 0 baseline)
        var equity = 0.0
        var maxDdSol = 0.0
        var maxDdPct = 0.0
        var grossDeployed = 0.0 // cumulative |risk| proxy for pre-peak DD%

        for (trade in sorted) {
            val pnl = sanitizeDouble(trade.pnlSol)
            grossDeployed += kotlin.math.abs(sanitizeDouble(trade.solIn).let { if (it > 0.0) it else pnl })
            equity += pnl
            if (equity > peak) peak = equity

            val dd = peak - equity        // always >= 0
            if (dd > maxDdSol) {
                maxDdSol = dd
                val denom = if (peak >= 0.05) peak else grossDeployed
                maxDdPct = if (denom > 0.0) ((dd / denom) * 100.0).coerceIn(0.0, 100.0) else 0.0
            }
        }

        val curDd = peak - equity
        val curDenom = if (peak >= 0.05) peak else grossDeployed
        val currentDdPct = if (curDd > 0.0 && curDenom > 0.0) {
            ((curDd / curDenom) * 100.0).coerceIn(0.0, 100.0)
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
            .append(stats.lossCount).append("L)\n")
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
        return sanitizeDouble(trade.pnlPct) >= WIN_THRESHOLD_PCT
    }

    private fun isLoss(trade: TradeRecord): Boolean {
        return sanitizeDouble(trade.pnlPct) <= LOSS_THRESHOLD_PCT
    }

    private fun isDecisive(trade: TradeRecord): Boolean {
        return isWin(trade) || isLoss(trade)
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