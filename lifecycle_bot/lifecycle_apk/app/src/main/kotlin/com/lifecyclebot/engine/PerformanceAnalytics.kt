package com.lifecyclebot.engine

import com.lifecyclebot.data.TradeRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PerformanceAnalytics — Deep trading performance insights
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Analyzes your trading history to identify:
 *   📊 Win rates by phase, time, market regime
 *   🎯 Best entry conditions (score ranges, phases)
 *   ⏰ Optimal trading hours
 *   📉 Drawdown analysis
 *   🔥 Streak tracking
 *   💡 Actionable insights for improvement
 */
object PerformanceAnalytics {

    data class AnalyticsSnapshot(
        // Overall stats
        val totalTrades: Int = 0,
        val winCount: Int = 0,
        val lossCount: Int = 0,
        val winRate: Double = 0.0,
        val totalPnlSol: Double = 0.0,
        val avgPnlSol: Double = 0.0,
        val avgWinSol: Double = 0.0,
        val avgLossSol: Double = 0.0,
        val profitFactor: Double = 0.0,  // gross profit / gross loss
        val expectancy: Double = 0.0,     // avg gain per trade
        
        // Streak tracking
        val currentStreak: Int = 0,       // positive = wins, negative = losses
        val longestWinStreak: Int = 0,
        val longestLossStreak: Int = 0,
        
        // Drawdown
        val maxDrawdownSol: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val currentDrawdownPct: Double = 0.0,
        
        // Phase analysis
        val winRateByPhase: Map<String, Double> = emptyMap(),
        val avgPnlByPhase: Map<String, Double> = emptyMap(),
        val tradeCountByPhase: Map<String, Int> = emptyMap(),
        
        // Time analysis
        val winRateByHour: Map<Int, Double> = emptyMap(),
        val tradeCountByHour: Map<Int, Int> = emptyMap(),
        val bestHour: Int = 0,
        val worstHour: Int = 0,
        
        // Entry score analysis
        val winRateByScoreRange: Map<String, Double> = emptyMap(),
        val avgPnlByScoreRange: Map<String, Double> = emptyMap(),
        val optimalScoreRange: String = "",
        
        // Regime analysis
        val winRateByRegime: Map<String, Double> = emptyMap(),
        
        // Hold time analysis
        val avgHoldMinsWin: Double = 0.0,
        val avgHoldMinsLoss: Double = 0.0,
        val optimalHoldMins: Double = 0.0,
        
        // Insights
        val insights: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    /**
     * Generate full analytics snapshot from trade history.
     */
    fun analyze(trades: List<TradeRecord>): AnalyticsSnapshot {
        if (trades.isEmpty()) return AnalyticsSnapshot()
        
        val closedTrades = trades.filter { it.exitPrice > 0 && it.tsExit > 0 }
        if (closedTrades.isEmpty()) return AnalyticsSnapshot(totalTrades = trades.size)
        
        // Basic stats
        val wins = closedTrades.filter { it.pnlSol >= 0 }
        val losses = closedTrades.filter { it.pnlSol < 0 }
        val winRate = wins.size.toDouble() / closedTrades.size * 100
        val totalPnl = closedTrades.sumOf { it.pnlSol }
        val avgPnl = totalPnl / closedTrades.size
        val avgWin = if (wins.isNotEmpty()) wins.sumOf { it.pnlSol } / wins.size else 0.0
        val avgLoss = if (losses.isNotEmpty()) abs(losses.sumOf { it.pnlSol }) / losses.size else 0.0
        
        // Profit factor
        val grossProfit = wins.sumOf { it.pnlSol }
        val grossLoss = abs(losses.sumOf { it.pnlSol })
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else grossProfit
        
        // Expectancy
        val expectancy = (winRate / 100 * avgWin) - ((100 - winRate) / 100 * avgLoss)
        
        // Streaks
        val (currentStreak, longestWin, longestLoss) = calculateStreaks(closedTrades)
        
        // Drawdown
        val (maxDdSol, maxDdPct, currentDdPct) = calculateDrawdown(closedTrades)
        
        // Phase analysis
        val phaseStats = analyzeByPhase(closedTrades)
        
        // Time analysis
        val timeStats = analyzeByHour(closedTrades)
        
        // Score analysis
        val scoreStats = analyzeByScore(closedTrades)
        
        // Regime analysis
        val regimeStats = analyzeByRegime(closedTrades)
        
        // Hold time analysis
        val holdStats = analyzeHoldTime(closedTrades)
        
        // Generate insights
        val insights = generateInsights(
            winRate, avgPnl, phaseStats, timeStats, scoreStats, 
            holdStats, currentStreak, maxDdPct
        )
        
        val warnings = generateWarnings(
            winRate, currentStreak, maxDdPct, currentDdPct, closedTrades.size
        )
        
        return AnalyticsSnapshot(
            totalTrades = closedTrades.size,
            winCount = wins.size,
            lossCount = losses.size,
            winRate = winRate,
            totalPnlSol = totalPnl,
            avgPnlSol = avgPnl,
            avgWinSol = avgWin,
            avgLossSol = avgLoss,
            profitFactor = profitFactor,
            expectancy = expectancy,
            currentStreak = currentStreak,
            longestWinStreak = longestWin,
            longestLossStreak = longestLoss,
            maxDrawdownSol = maxDdSol,
            maxDrawdownPct = maxDdPct,
            currentDrawdownPct = currentDdPct,
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
            avgHoldMinsWin = holdStats.first,
            avgHoldMinsLoss = holdStats.second,
            optimalHoldMins = holdStats.third,
            insights = insights,
            warnings = warnings,
        )
    }

    private fun calculateStreaks(trades: List<TradeRecord>): Triple<Int, Int, Int> {
        var currentStreak = 0
        var longestWin = 0
        var longestLoss = 0
        var tempStreak = 0
        
        for (trade in trades.sortedBy { it.tsEntry }) {
            if (trade.pnlSol >= 0) {
                if (tempStreak >= 0) tempStreak++ else tempStreak = 1
                longestWin = max(longestWin, tempStreak)
            } else {
                if (tempStreak <= 0) tempStreak-- else tempStreak = -1
                longestLoss = max(longestLoss, abs(tempStreak))
            }
        }
        currentStreak = tempStreak
        
        return Triple(currentStreak, longestWin, longestLoss)
    }

    private fun calculateDrawdown(trades: List<TradeRecord>): Triple<Double, Double, Double> {
        val sorted = trades.sortedBy { it.tsExit }
        var peak = 0.0
        var equity = 0.0
        var maxDdSol = 0.0
        var maxDdPct = 0.0
        
        for (trade in sorted) {
            equity += trade.pnlSol
            if (equity > peak) peak = equity
            val dd = peak - equity
            if (dd > maxDdSol) {
                maxDdSol = dd
                maxDdPct = if (peak > 0) dd / peak * 100 else 0.0
            }
        }
        
        val currentDdPct = if (peak > 0 && equity < peak) (peak - equity) / peak * 100 else 0.0
        
        return Triple(maxDdSol, maxDdPct, currentDdPct)
    }

    private fun analyzeByPhase(trades: List<TradeRecord>): Triple<Map<String, Double>, Map<String, Double>, Map<String, Int>> {
        val byPhase = trades.groupBy { it.entryPhase.ifBlank { "unknown" } }
        
        val winRates = byPhase.mapValues { (_, list) ->
            list.count { it.pnlSol >= 0 }.toDouble() / list.size * 100
        }
        
        val avgPnl = byPhase.mapValues { (_, list) ->
            list.sumOf { it.pnlSol } / list.size
        }
        
        val counts = byPhase.mapValues { it.value.size }
        
        return Triple(winRates, avgPnl, counts)
    }

    private fun analyzeByHour(trades: List<TradeRecord>): Quadruple<Map<Int, Double>, Map<Int, Int>, Int, Int> {
        val byHour = trades.groupBy { 
            java.util.Calendar.getInstance().apply { 
                timeInMillis = it.tsEntry 
            }.get(java.util.Calendar.HOUR_OF_DAY)
        }
        
        val winRates = byHour.mapValues { (_, list) ->
            list.count { it.pnlSol >= 0 }.toDouble() / list.size * 100
        }
        
        val counts = byHour.mapValues { it.value.size }
        
        val bestHour = winRates.maxByOrNull { it.value }?.key ?: 0
        val worstHour = winRates.filter { counts[it.key] ?: 0 >= 3 }.minByOrNull { it.value }?.key ?: 0
        
        return Quadruple(winRates, counts, bestHour, worstHour)
    }

    private fun analyzeByScore(trades: List<TradeRecord>): Triple<Map<String, Double>, Map<String, Double>, String> {
        val ranges = listOf(
            "35-45" to (35..45),
            "46-55" to (46..55),
            "56-65" to (56..65),
            "66-75" to (66..75),
            "76+" to (76..100),
        )
        
        val winRates = mutableMapOf<String, Double>()
        val avgPnl = mutableMapOf<String, Double>()
        
        for ((label, range) in ranges) {
            val inRange = trades.filter { it.entryScore.toInt() in range }
            if (inRange.isNotEmpty()) {
                winRates[label] = inRange.count { it.pnlSol >= 0 }.toDouble() / inRange.size * 100
                avgPnl[label] = inRange.sumOf { it.pnlSol } / inRange.size
            }
        }
        
        val optimalRange = avgPnl.filter { (winRates[it.key] ?: 0.0) >= 50 }
            .maxByOrNull { it.value }?.key ?: ""
        
        return Triple(winRates, avgPnl, optimalRange)
    }

    private fun analyzeByRegime(trades: List<TradeRecord>): Map<String, Double> {
        val byRegime = trades.groupBy { it.mode.ifBlank { "NORMAL" } }
        return byRegime.mapValues { (_, list) ->
            list.count { it.pnlSol >= 0 }.toDouble() / list.size * 100
        }
    }

    private fun analyzeHoldTime(trades: List<TradeRecord>): Triple<Double, Double, Double> {
        val wins = trades.filter { it.pnlSol >= 0 && it.heldMins > 0 }
        val losses = trades.filter { it.pnlSol < 0 && it.heldMins > 0 }
        
        val avgHoldWin = if (wins.isNotEmpty()) wins.sumOf { it.heldMins } / wins.size else 0.0
        val avgHoldLoss = if (losses.isNotEmpty()) losses.sumOf { it.heldMins } / losses.size else 0.0
        
        // Optimal hold = time that maximizes profit
        val profitable = trades.filter { it.pnlSol > 0 && it.heldMins > 0 }
        val optimalHold = if (profitable.isNotEmpty()) {
            profitable.sortedByDescending { it.pnlSol }.take(5).map { it.heldMins }.average()
        } else avgHoldWin
        
        return Triple(avgHoldWin, avgHoldLoss, optimalHold)
    }

    private fun generateInsights(
        winRate: Double,
        avgPnl: Double,
        phaseStats: Triple<Map<String, Double>, Map<String, Double>, Map<String, Int>>,
        timeStats: Quadruple<Map<Int, Double>, Map<Int, Int>, Int, Int>,
        scoreStats: Triple<Map<String, Double>, Map<String, Double>, String>,
        holdStats: Triple<Double, Double, Double>,
        currentStreak: Int,
        maxDdPct: Double,
    ): List<String> {
        val insights = mutableListOf<String>()
        
        // Phase insights
        val bestPhase = phaseStats.first.filter { (phaseStats.third[it.key] ?: 0) >= 3 }
            .maxByOrNull { it.value }
        bestPhase?.let {
            insights.add("🎯 Best phase: ${it.key} (${it.value.fmt(1)}% win rate)")
        }
        
        // Time insights
        val bestHour = timeStats.third
        val bestHourWR = timeStats.first[bestHour] ?: 0.0
        if (bestHourWR > winRate + 10) {
            insights.add("⏰ Best hour: ${bestHour}:00 UTC (${bestHourWR.fmt(1)}% win rate)")
        }
        
        // Score insights
        if (scoreStats.third.isNotBlank()) {
            insights.add("📊 Optimal entry score: ${scoreStats.third}")
        }
        
        // Hold time insights
        if (holdStats.first > 0 && holdStats.second > 0) {
            if (holdStats.second > holdStats.first * 1.5) {
                insights.add("⏱ Cut losses faster — losers held ${holdStats.second.fmt(1)}min vs winners ${holdStats.first.fmt(1)}min")
            }
        }
        
        // Streak insights
        if (currentStreak >= 3) {
            insights.add("🔥 Hot streak! $currentStreak consecutive wins")
        }
        
        return insights
    }

    private fun generateWarnings(
        winRate: Double,
        currentStreak: Int,
        maxDdPct: Double,
        currentDdPct: Double,
        tradeCount: Int,
    ): List<String> {
        val warnings = mutableListOf<String>()
        
        if (winRate < 40 && tradeCount >= 10) {
            warnings.add("⚠️ Win rate below 40% — review entry criteria")
        }
        
        if (currentStreak <= -3) {
            warnings.add("🔴 Cold streak: ${abs(currentStreak)} consecutive losses")
        }
        
        if (currentDdPct > 20) {
            warnings.add("📉 Currently in ${currentDdPct.fmt(1)}% drawdown")
        }
        
        if (maxDdPct > 30) {
            warnings.add("⚠️ Max drawdown ${maxDdPct.fmt(1)}% — consider reducing size")
        }
        
        return warnings
    }

    /**
     * Format analytics for display or Telegram.
     */
    fun formatSummary(stats: AnalyticsSnapshot): String = buildString {
        appendLine("📊 *PERFORMANCE ANALYTICS*")
        appendLine("━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("*Overall:*")
        appendLine("  Trades: ${stats.totalTrades} (${stats.winCount}W / ${stats.lossCount}L)")
        appendLine("  Win Rate: ${stats.winRate.fmt(1)}%")
        appendLine("  Total P&L: ${stats.totalPnlSol.fmt(4)} SOL")
        appendLine("  Profit Factor: ${stats.profitFactor.fmt(2)}")
        appendLine("  Expectancy: ${stats.expectancy.fmt(4)} SOL/trade")
        appendLine()
        appendLine("*Risk:*")
        appendLine("  Max Drawdown: ${stats.maxDrawdownPct.fmt(1)}%")
        appendLine("  Current DD: ${stats.currentDrawdownPct.fmt(1)}%")
        appendLine("  Longest Loss Streak: ${stats.longestLossStreak}")
        appendLine()
        if (stats.insights.isNotEmpty()) {
            appendLine("*Insights:*")
            stats.insights.forEach { appendLine("  $it") }
            appendLine()
        }
        if (stats.warnings.isNotEmpty()) {
            appendLine("*Warnings:*")
            stats.warnings.forEach { appendLine("  $it") }
        }
    }

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
    
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
