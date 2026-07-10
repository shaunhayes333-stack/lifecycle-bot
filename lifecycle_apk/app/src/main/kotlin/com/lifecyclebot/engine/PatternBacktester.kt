package com.lifecyclebot.engine

import android.database.Cursor
import java.util.Locale
import kotlin.math.abs

/**
 * PatternBacktester — Analyzes historical trades to find which patterns work best.
 *
 * Uses the TradeDatabase to compute statistics on:
 * - Which chart patterns produce wins
 * - Which EMA fan states correlate with profitable trades
 * - Which entry phases have the best win rates
 * - Optimal hold times for different patterns
 */
object PatternBacktester {

    data class PatternStats(
        val patternName: String,
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgWinPct: Double,
        val avgLossPct: Double,
        val avgHoldMins: Double,
        val profitFactor: Double,
        val expectancy: Double,
        val bestTrade: Double,
        val worstTrade: Double,
        val recommendation: String
    )

    data class BacktestReport(
        val generatedAt: Long,
        val totalTrades: Int,
        val overallWinRate: Double,
        val patterns: List<PatternStats>,
        val emaFanStats: List<PatternStats>,
        val phaseStats: List<PatternStats>,
        val sourceStats: List<PatternStats>,
        val insights: List<String>
    )

    fun runBacktest(db: TradeDatabase): BacktestReport {
        val rawTrades = db.getAllTrades()

        // V5.9.495z21 — STRATEGY TRAINING GATE applied to chart-pattern
        // backtest input. Filters out trades whose mint was flagged by the
        // execution pipeline as a partial-bridge / output-mismatch / recovery
        // event (target token never actually landed). Without this filter
        // PatternAutoTuner would tune chart-pattern multipliers using
        // phantom outcomes, biasing future entry sizing on patterns that
        // never produced a real win or loss.
        val trades = rawTrades.filter {
            it.mint.isBlank() ||
            com.lifecyclebot.engine.execution.ExecutionStatusRegistry.shouldTrainStrategy(it.mint)
        }

        if (trades.isEmpty()) {
            return BacktestReport(
                generatedAt = System.currentTimeMillis(),
                totalTrades = 0,
                overallWinRate = 0.0,
                patterns = emptyList(),
                emaFanStats = emptyList(),
                phaseStats = emptyList(),
                sourceStats = emptyList(),
                insights = listOf("No trades to analyze yet. Keep trading!")
            )
        }

        val overallWinRate =
            (trades.count { it.isWin == true }.toDouble() / trades.size.toDouble()) * 100.0

        val emaFanStats = analyzeByField(trades) { it.emaFan.ifBlank { "UNKNOWN" } }
        val phaseStats = analyzeByField(trades) { it.entryPhase.ifBlank { "UNKNOWN" } }
        val sourceStats = analyzeByField(trades) { it.source.ifBlank { "UNKNOWN" } }
        val chartPatternStats = analyzeChartPatterns(trades)
        val insights = generateInsights(
            trades = trades,
            emaStats = emaFanStats,
            phaseStats = phaseStats,
            sourceStats = sourceStats,
            patternStats = chartPatternStats
        )

        return BacktestReport(
            generatedAt = System.currentTimeMillis(),
            totalTrades = trades.size,
            overallWinRate = overallWinRate,
            patterns = chartPatternStats,
            emaFanStats = emaFanStats,
            phaseStats = phaseStats,
            sourceStats = sourceStats,
            insights = insights
        )
    }

    private fun analyzeByField(
        trades: List<TradeRecord>,
        fieldExtractor: (TradeRecord) -> String
    ): List<PatternStats> {
        return trades
            .groupBy { fieldExtractor(it).ifBlank { "UNKNOWN" } }
            .filter { it.value.size >= 3 }
            .map { (name, groupTrades) -> computeStats(name, groupTrades) }
            .sortedWith(compareByDescending<PatternStats> { it.winRate }.thenByDescending { it.profitFactor })
    }

    private fun analyzeChartPatterns(trades: List<TradeRecord>): List<PatternStats> {
        val patternKeywords = mapOf(
            "DOUBLE_BOTTOM" to listOf("double_bottom", "w_bottom", "reclaim"),
            "CUP_HANDLE" to listOf("cup", "handle", "rounded"),
            "BULL_FLAG" to listOf("flag", "consolidation", "cooling"),
            "EMA_FAN" to listOf("pumping", "pre_pump", "expansion")
        )

        val patternTrades = mutableMapOf<String, MutableList<TradeRecord>>()

        for (trade in trades) {
            val phase = trade.entryPhase.lowercase(Locale.US)
            val ema = trade.emaFan.lowercase(Locale.US)

            for ((pattern, keywords) in patternKeywords) {
                if (keywords.any { keyword -> phase.contains(keyword) || ema.contains(keyword) }) {
                    patternTrades.getOrPut(pattern) { mutableListOf() }.add(trade)
                }
            }
        }

        return patternTrades
            .filter { it.value.size >= 2 }
            .map { (name, groupTrades) -> computeStats(name, groupTrades) }
            .sortedWith(compareByDescending<PatternStats> { it.profitFactor }.thenByDescending { it.winRate })
    }

    private fun computeStats(name: String, trades: List<TradeRecord>): PatternStats {
        val wins = trades.filter { it.isWin == true }
        val losses = trades.filter { it.isWin == false }

        val winRate = if (trades.isNotEmpty()) {
            (wins.size.toDouble() / trades.size.toDouble()) * 100.0
        } else {
            0.0
        }

        val avgWinPct = if (wins.isNotEmpty()) wins.map { sanitizeDouble(it.pnlPct) }.average() else 0.0
        val avgLossPct = if (losses.isNotEmpty()) losses.map { sanitizeDouble(it.pnlPct) }.average() else 0.0
        val avgHoldMins = if (trades.isNotEmpty()) trades.map { sanitizeDouble(it.heldMins) }.average() else 0.0

        val totalWinValue = wins.sumOf { sanitizeDouble(it.pnlPct).coerceAtLeast(0.0) }
        val totalLossValue = losses.sumOf { abs(sanitizeDouble(it.pnlPct)) }.coerceAtLeast(0.001)
        val profitFactor = totalWinValue / totalLossValue

        val winProb = winRate / 100.0
        val lossProb = 1.0 - winProb
        val expectancy = (winProb * avgWinPct) + (lossProb * avgLossPct)

        val bestTrade = trades.maxOfOrNull { sanitizeDouble(it.pnlPct) } ?: 0.0
        val worstTrade = trades.minOfOrNull { sanitizeDouble(it.pnlPct) } ?: 0.0

        val recommendation = when {
            trades.size < 5 -> "NEED_DATA"
            winRate >= 65.0 && profitFactor >= 2.0 -> "BOOST"
            winRate >= 55.0 && profitFactor >= 1.5 -> "KEEP"
            winRate >= 45.0 && profitFactor >= 1.0 -> "KEEP"
            winRate >= 35.0 -> "REDUCE"
            else -> "DISABLE"
        }

        return PatternStats(
            patternName = name,
            totalTrades = trades.size,
            wins = wins.size,
            losses = losses.size,
            winRate = sanitizeDouble(winRate),
            avgWinPct = sanitizeDouble(avgWinPct),
            avgLossPct = sanitizeDouble(avgLossPct),
            avgHoldMins = sanitizeDouble(avgHoldMins),
            profitFactor = sanitizeDouble(profitFactor),
            expectancy = sanitizeDouble(expectancy),
            bestTrade = sanitizeDouble(bestTrade),
            worstTrade = sanitizeDouble(worstTrade),
            recommendation = recommendation
        )
    }

    private fun generateInsights(
        trades: List<TradeRecord>,
        emaStats: List<PatternStats>,
        phaseStats: List<PatternStats>,
        sourceStats: List<PatternStats>,
        patternStats: List<PatternStats>
    ): List<String> {
        val insights = mutableListOf<String>()

        emaStats.firstOrNull { it.totalTrades >= 5 }?.let {
            if (it.winRate >= 55.0) {
                insights.add("🎯 Best EMA state: ${it.patternName} (${it.winRate.toInt()}% win rate, ${it.totalTrades} trades)")
            }
        }

        emaStats.lastOrNull { it.totalTrades >= 5 }?.let {
            if (it.winRate < 45.0) {
                insights.add("⚠️ Avoid: ${it.patternName} entries (${it.winRate.toInt()}% win rate)")
            }
        }

        sourceStats.firstOrNull { it.totalTrades >= 5 && it.winRate >= 55.0 }?.let {
            insights.add("📈 Top source: ${it.patternName} (${it.winRate.toInt()}% win, PF=${it.profitFactor.format(2)})")
        }

        phaseStats.firstOrNull { it.totalTrades >= 5 && it.winRate >= 55.0 }?.let {
            insights.add("✅ Best entry phase: ${it.patternName} (${it.winRate.toInt()}% win)")
        }

        patternStats.filter { it.recommendation == "BOOST" }.forEach {
            insights.add("🚀 BOOST ${it.patternName}: ${it.winRate.toInt()}% win, ${it.profitFactor.format(2)} PF")
        }

        patternStats.filter { it.recommendation == "DISABLE" }.forEach {
            insights.add("🛑 DISABLE ${it.patternName}: Only ${it.winRate.toInt()}% win rate")
        }

        val winHoldList = trades.filter { it.isWin == true }.map { sanitizeDouble(it.heldMins) }
        val lossHoldList = trades.filter { it.isWin == false }.map { sanitizeDouble(it.heldMins) }

        val avgHoldWins = if (winHoldList.isNotEmpty()) winHoldList.average() else 0.0
        val avgHoldLosses = if (lossHoldList.isNotEmpty()) lossHoldList.average() else 0.0

        if (avgHoldWins > 0.0 && avgHoldLosses > 0.0) {
            if (avgHoldWins > avgHoldLosses * 1.5) {
                insights.add("⏱️ Winners held ${avgHoldWins.toInt()}min vs losers ${avgHoldLosses.toInt()}min - let winners run longer!")
            }
        }

        val bigWinners = trades.filter { sanitizeDouble(it.pnlPct) >= 100.0 }
        if (bigWinners.isNotEmpty()) {
            val avgBigWinHold = bigWinners.map { sanitizeDouble(it.heldMins) }.average()
            insights.add("💎 ${bigWinners.size} trades hit 100%+, avg hold: ${avgBigWinHold.toInt()}min")
        }

        val recentTrades = trades.sortedByDescending { it.tsExit }.take(20)
        if (recentTrades.size >= 10) {
            val recentWinRate =
                (recentTrades.count { it.isWin == true }.toDouble() / recentTrades.size.toDouble()) * 100.0
            val overallWinRate =
                (trades.count { it.isWin == true }.toDouble() / trades.size.toDouble()) * 100.0

            if (recentWinRate > overallWinRate + 10.0) {
                insights.add("📊 Recent performance improving: ${recentWinRate.toInt()}% vs ${overallWinRate.toInt()}% overall")
            } else if (recentWinRate < overallWinRate - 10.0) {
                insights.add("⚠️ Recent slump: ${recentWinRate.toInt()}% vs ${overallWinRate.toInt()}% overall - review strategy")
            }
        }

        if (insights.isEmpty()) {
            insights.add("📊 Keep trading to gather more data for meaningful insights")
        }

        return insights
    }

    fun formatReport(report: BacktestReport): String {
        val sb = StringBuilder()

        sb.append("═══════════════════════════════════════════════════════════\n")
        sb.append("          PATTERN BACKTEST REPORT\n")
        sb.append("═══════════════════════════════════════════════════════════\n\n")
        sb.append("Total Trades: ").append(report.totalTrades).append('\n')
        sb.append("Overall Win Rate: ").append(report.overallWinRate.toInt()).append("%\n\n")

        if (report.patterns.isNotEmpty()) {
            sb.append("── CHART PATTERNS ────────────────────────────────────────\n")
            for (p in report.patterns) {
                sb.append(p.recommendation.padEnd(10))
                    .append(' ')
                    .append(p.patternName.padEnd(15))
                    .append(" Win:")
                    .append(p.winRate.toInt())
                    .append("% PF:")
                    .append(p.profitFactor.format(2))
                    .append(" (")
                    .append(p.wins)
                    .append("W/")
                    .append(p.losses)
                    .append("L) Exp:")
                    .append(p.expectancy.format(1))
                    .append("%\n")
            }
            sb.append('\n')
        }

        if (report.emaFanStats.isNotEmpty()) {
            sb.append("── EMA FAN STATES ────────────────────────────────────────\n")
            for (p in report.emaFanStats.take(5)) {
                sb.append(p.patternName.padEnd(12))
                    .append(" Win:")
                    .append(p.winRate.toInt())
                    .append("% (")
                    .append(p.totalTrades)
                    .append(" trades) Avg:")
                    .append(p.avgWinPct.format(1))
                    .append("%W/")
                    .append(p.avgLossPct.format(1))
                    .append("%L\n")
            }
            sb.append('\n')
        }

        if (report.phaseStats.isNotEmpty()) {
            sb.append("── ENTRY PHASES ──────────────────────────────────────────\n")
            for (p in report.phaseStats.take(5)) {
                sb.append(p.patternName.padEnd(18))
                    .append(" Win:")
                    .append(p.winRate.toInt())
                    .append("% (")
                    .append(p.totalTrades)
                    .append(" trades) Best:+")
                    .append(p.bestTrade.toInt())
                    .append("%\n")
            }
            sb.append('\n')
        }

        if (report.insights.isNotEmpty()) {
            sb.append("── INSIGHTS ──────────────────────────────────────────────\n")
            for (insight in report.insights) {
                sb.append(insight).append('\n')
            }
            sb.append('\n')
        }

        sb.append("═══════════════════════════════════════════════════════════\n")
        sb.append("Generated: ").append(report.generatedAt)

        return sb.toString()
    }

    fun getConfidenceAdjustments(report: BacktestReport): Map<String, Double> {
        val adjustments = mutableMapOf<String, Double>()

        for (p in report.patterns) {
            val multiplier = when (p.recommendation) {
                "BOOST" -> 1.0 + (p.profitFactor - 1.5).coerceIn(0.0, 0.5) * 0.5
                "KEEP" -> 1.0
                "REDUCE" -> 0.7 + (p.winRate / 100.0) * 0.3
                "DISABLE" -> 0.3
                else -> 1.0
            }
            adjustments[p.patternName] = multiplier
        }

        return adjustments
    }

    private fun Double.format(decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", sanitizeDouble(this))
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }
}

/**
 * Extension to get all trades from database for backtesting.
 */
fun TradeDatabase.getAllTrades(): List<TradeRecord> {
    val trades = mutableListOf<TradeRecord>()
    val db = this.readableDatabase

    val cursor = db.rawQuery(
        "SELECT * FROM trades ORDER BY ts_exit DESC LIMIT 1000",
        null
    )

    cursor.use { c ->
        while (c.moveToNext()) {
            try {
                trades.add(
                    TradeRecord(
                        id = c.getLongOrDefault("id"),
                        tsEntry = c.getLongOrDefault("ts_entry"),
                        tsExit = c.getLongOrDefault("ts_exit"),
                        symbol = c.getStringOrDefault("symbol"),
                        mint = c.getStringOrDefault("mint"),
                        mode = c.getStringOrDefault("mode"),
                        dex = c.getStringOrDefault("dex"),
                        entryPrice = c.getDoubleOrDefault("entry_price"),
                        entryScore = c.getDoubleOrDefault("entry_score"),
                        entryPhase = c.getStringOrDefault("entry_phase"),
                        emaFan = c.getStringOrDefault("ema_fan"),
                        volScore = c.getDoubleOrDefault("vol_score"),
                        pressScore = c.getDoubleOrDefault("press_score"),
                        momScore = c.getDoubleOrDefault("mom_score"),
                        stochSignal = c.getIntOrDefault("stoch_signal"),
                        volDiv = c.getBooleanIntOrDefault("vol_div"),
                        mtf5m = c.getStringOrDefault("mtf_5m", "NEUTRAL"),
                        tokenAgeHours = c.getDoubleOrDefault("token_age_hours"),
                        holderCount = c.getIntOrDefault("holder_count"),
                        holderGrowth = c.getDoubleOrDefault("holder_growth"),
                        liquidityUsd = c.getDoubleOrDefault("liquidity_usd"),
                        mcapUsd = c.getDoubleOrDefault("mcap_usd"),
                        pullbackEntry = c.getBooleanIntOrDefault("pullback_entry"),
                        exitPrice = c.getDoubleOrDefault("exit_price"),
                        exitScore = c.getDoubleOrDefault("exit_score"),
                        exitPhase = c.getStringOrDefault("exit_phase"),
                        exitReason = c.getStringOrDefault("exit_reason"),
                        heldMins = c.getDoubleOrDefault("held_mins"),
                        topUpCount = c.getIntOrDefault("top_up_count"),
                        partialSold = c.getDoubleOrDefault("partial_sold"),
                        solIn = c.getDoubleOrDefault("sol_in"),
                        solOut = c.getDoubleOrDefault("sol_out"),
                        pnlSol = c.getDoubleOrDefault("pnl_sol"),
                        pnlPct = c.getDoubleOrDefault("pnl_pct"),
                        isWin = c.getBooleanIntOrDefault("is_win"),
                        source = c.getStringOrDefault("source"),
                        extraJson = c.getStringOrDefault("extra_json")
                    )
                )
            } catch (_: Exception) {
                // Skip malformed records
            }
        }
    }

    return trades
}

private fun Cursor.getColumnIndexSafe(name: String): Int {
    return getColumnIndex(name)
}

private fun Cursor.getStringOrDefault(name: String, default: String = ""): String {
    val idx = getColumnIndexSafe(name)
    return if (idx >= 0 && !isNull(idx)) getString(idx) ?: default else default
}

private fun Cursor.getIntOrDefault(name: String, default: Int = 0): Int {
    val idx = getColumnIndexSafe(name)
    return if (idx >= 0 && !isNull(idx)) getInt(idx) else default
}

private fun Cursor.getLongOrDefault(name: String, default: Long = 0L): Long {
    val idx = getColumnIndexSafe(name)
    return if (idx >= 0 && !isNull(idx)) getLong(idx) else default
}

private fun Cursor.getDoubleOrDefault(name: String, default: Double = 0.0): Double {
    val idx = getColumnIndexSafe(name)
    return if (idx >= 0 && !isNull(idx)) getDouble(idx) else default
}

private fun Cursor.getBooleanIntOrDefault(name: String, default: Boolean = false): Boolean {
    val idx = getColumnIndexSafe(name)
    return if (idx >= 0 && !isNull(idx)) getInt(idx) == 1 else default
}