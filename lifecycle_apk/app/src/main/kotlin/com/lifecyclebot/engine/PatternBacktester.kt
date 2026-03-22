package com.lifecyclebot.engine

import android.content.Context

/**
 * PatternBacktester — Analyzes historical trades to find which patterns work best
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Uses the TradeDatabase to compute statistics on:
 * - Which chart patterns (DOUBLE_BOTTOM, CUP_HANDLE, BULL_FLAG) produce wins
 * - Which EMA fan states correlate with profitable trades
 * - Which entry phases have the best win rates
 * - Optimal hold times for different patterns
 * 
 * This helps tune pattern confidence thresholds based on REAL performance data.
 */
object PatternBacktester {

    data class PatternStats(
        val patternName: String,
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,           // 0-100%
        val avgWinPct: Double,         // Average gain on wins
        val avgLossPct: Double,        // Average loss on losses
        val avgHoldMins: Double,       // Average hold time
        val profitFactor: Double,      // Total wins / Total losses
        val expectancy: Double,        // Expected return per trade
        val bestTrade: Double,         // Best single trade %
        val worstTrade: Double,        // Worst single trade %
        val recommendation: String,    // BOOST / KEEP / REDUCE / DISABLE
    )

    data class BacktestReport(
        val generatedAt: Long,
        val totalTrades: Int,
        val overallWinRate: Double,
        val patterns: List<PatternStats>,
        val emaFanStats: List<PatternStats>,
        val phaseStats: List<PatternStats>,
        val sourceStats: List<PatternStats>,
        val insights: List<String>,
    )

    /**
     * Run full backtest analysis on all historical trades.
     * Returns a comprehensive report with pattern performance.
     */
    fun runBacktest(db: TradeDatabase): BacktestReport {
        val trades = db.getAllTrades()
        
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

        val overallWinRate = trades.count { it.isWin }.toDouble() / trades.size * 100

        // Analyze by EMA fan state
        val emaFanStats = analyzeByField(trades) { it.emaFan }

        // Analyze by entry phase
        val phaseStats = analyzeByField(trades) { it.entryPhase }

        // Analyze by source
        val sourceStats = analyzeByField(trades) { it.source ?: "UNKNOWN" }

        // Analyze chart patterns (from entry phase or logs)
        // Note: Chart patterns are detected during entry and stored in entryPhase
        val chartPatternStats = analyzeChartPatterns(trades)

        // Generate insights
        val insights = generateInsights(trades, emaFanStats, phaseStats, sourceStats, chartPatternStats)

        return BacktestReport(
            generatedAt = System.currentTimeMillis(),
            totalTrades = trades.size,
            overallWinRate = overallWinRate,
            patterns = chartPatternStats,
            emaFanStats = emaFanStats,
            phaseStats = phaseStats,
            sourceStats = sourceStats,
            insights = insights,
        )
    }

    /**
     * Analyze trades grouped by a specific field.
     */
    private fun analyzeByField(
        trades: List<TradeRecord>,
        fieldExtractor: (TradeRecord) -> String
    ): List<PatternStats> {
        return trades
            .groupBy { fieldExtractor(it) }
            .filter { it.value.size >= 3 }  // Need at least 3 trades for meaningful stats
            .map { (name, groupTrades) -> computeStats(name, groupTrades) }
            .sortedByDescending { it.winRate }
    }

    /**
     * Analyze chart patterns specifically.
     * Chart patterns may be encoded in entry_phase or detected patterns.
     */
    private fun analyzeChartPatterns(trades: List<TradeRecord>): List<PatternStats> {
        // Group by pattern indicators in entry phase
        val patternKeywords = mapOf(
            "DOUBLE_BOTTOM" to listOf("double_bottom", "w_bottom", "reclaim"),
            "CUP_HANDLE" to listOf("cup", "handle", "rounded"),
            "BULL_FLAG" to listOf("flag", "consolidation", "cooling"),
            "EMA_FAN" to listOf("pumping", "pre_pump", "expansion"),
        )

        val patternTrades = mutableMapOf<String, MutableList<TradeRecord>>()

        for (trade in trades) {
            val phase = trade.entryPhase.lowercase()
            val ema = trade.emaFan.lowercase()

            for ((pattern, keywords) in patternKeywords) {
                if (keywords.any { phase.contains(it) || ema.contains(it) }) {
                    patternTrades.getOrPut(pattern) { mutableListOf() }.add(trade)
                }
            }
        }

        return patternTrades
            .filter { it.value.size >= 2 }
            .map { (name, groupTrades) -> computeStats(name, groupTrades) }
            .sortedByDescending { it.profitFactor }
    }

    /**
     * Compute comprehensive statistics for a group of trades.
     */
    private fun computeStats(name: String, trades: List<TradeRecord>): PatternStats {
        val wins = trades.filter { it.isWin }
        val losses = trades.filter { !it.isWin }

        val winRate = if (trades.isNotEmpty()) wins.size.toDouble() / trades.size * 100 else 0.0
        val avgWinPct = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 0.0
        val avgLossPct = if (losses.isNotEmpty()) losses.map { it.pnlPct }.average() else 0.0
        val avgHoldMins = if (trades.isNotEmpty()) trades.map { it.heldMins }.average() else 0.0

        val totalWinValue = wins.sumOf { it.pnlPct.coerceAtLeast(0.0) }
        val totalLossValue = losses.sumOf { kotlin.math.abs(it.pnlPct) }.coerceAtLeast(0.001)
        val profitFactor = totalWinValue / totalLossValue

        // Expectancy = (Win% × AvgWin) - (Loss% × AvgLoss)
        val winProb = winRate / 100.0
        val lossProb = 1.0 - winProb
        val expectancy = (winProb * avgWinPct) + (lossProb * avgLossPct)  // avgLossPct is negative

        val bestTrade = trades.maxOfOrNull { it.pnlPct } ?: 0.0
        val worstTrade = trades.minOfOrNull { it.pnlPct } ?: 0.0

        // Generate recommendation
        val recommendation = when {
            trades.size < 5 -> "NEED_DATA"
            winRate >= 65 && profitFactor >= 2.0 -> "BOOST"      // Great pattern
            winRate >= 55 && profitFactor >= 1.5 -> "KEEP"       // Good pattern
            winRate >= 45 && profitFactor >= 1.0 -> "KEEP"       // Acceptable
            winRate >= 35 -> "REDUCE"                             // Underperforming
            else -> "DISABLE"                                      // Bad pattern
        }

        return PatternStats(
            patternName = name,
            totalTrades = trades.size,
            wins = wins.size,
            losses = losses.size,
            winRate = winRate,
            avgWinPct = avgWinPct,
            avgLossPct = avgLossPct,
            avgHoldMins = avgHoldMins,
            profitFactor = profitFactor,
            expectancy = expectancy,
            bestTrade = bestTrade,
            worstTrade = worstTrade,
            recommendation = recommendation,
        )
    }

    /**
     * Generate actionable insights from the backtest data.
     */
    private fun generateInsights(
        trades: List<TradeRecord>,
        emaStats: List<PatternStats>,
        phaseStats: List<PatternStats>,
        sourceStats: List<PatternStats>,
        patternStats: List<PatternStats>,
    ): List<String> {
        val insights = mutableListOf<String>()

        // Best EMA fan state
        emaStats.firstOrNull { it.totalTrades >= 5 }?.let {
            if (it.winRate >= 55) {
                insights.add("🎯 Best EMA state: ${it.patternName} (${it.winRate.toInt()}% win rate, ${it.totalTrades} trades)")
            }
        }

        // Worst EMA fan state
        emaStats.lastOrNull { it.totalTrades >= 5 }?.let {
            if (it.winRate < 45) {
                insights.add("⚠️ Avoid: ${it.patternName} entries (${it.winRate.toInt()}% win rate)")
            }
        }

        // Best source
        sourceStats.firstOrNull { it.totalTrades >= 5 && it.winRate >= 55 }?.let {
            insights.add("📈 Top source: ${it.patternName} (${it.winRate.toInt()}% win, PF=${it.profitFactor.format(2)})")
        }

        // Best phase
        phaseStats.firstOrNull { it.totalTrades >= 5 && it.winRate >= 55 }?.let {
            insights.add("✅ Best entry phase: ${it.patternName} (${it.winRate.toInt()}% win)")
        }

        // Pattern recommendations
        patternStats.filter { it.recommendation == "BOOST" }.forEach {
            insights.add("🚀 BOOST ${it.patternName}: ${it.winRate.toInt()}% win, ${it.profitFactor.format(2)} PF")
        }
        patternStats.filter { it.recommendation == "DISABLE" }.forEach {
            insights.add("🛑 DISABLE ${it.patternName}: Only ${it.winRate.toInt()}% win rate")
        }

        // Hold time analysis
        val avgHoldWins = trades.filter { it.isWin }.map { it.heldMins }.average()
        val avgHoldLosses = trades.filter { !it.isWin }.map { it.heldMins }.average()
        if (avgHoldWins > 0 && avgHoldLosses > 0) {
            if (avgHoldWins > avgHoldLosses * 1.5) {
                insights.add("⏱️ Winners held ${avgHoldWins.toInt()}min vs losers ${avgHoldLosses.toInt()}min - let winners run longer!")
            }
        }

        // Big winners analysis
        val bigWinners = trades.filter { it.pnlPct >= 100 }
        if (bigWinners.isNotEmpty()) {
            val avgBigWinHold = bigWinners.map { it.heldMins }.average()
            insights.add("💎 ${bigWinners.size} trades hit 100%+, avg hold: ${avgBigWinHold.toInt()}min")
        }

        // Recent performance (last 20 trades)
        val recentTrades = trades.sortedByDescending { it.tsExit }.take(20)
        if (recentTrades.size >= 10) {
            val recentWinRate = recentTrades.count { it.isWin }.toDouble() / recentTrades.size * 100
            val overallWinRate = trades.count { it.isWin }.toDouble() / trades.size * 100
            if (recentWinRate > overallWinRate + 10) {
                insights.add("📊 Recent performance improving: ${recentWinRate.toInt()}% vs ${overallWinRate.toInt()}% overall")
            } else if (recentWinRate < overallWinRate - 10) {
                insights.add("⚠️ Recent slump: ${recentWinRate.toInt()}% vs ${overallWinRate.toInt()}% overall - review strategy")
            }
        }

        if (insights.isEmpty()) {
            insights.add("📊 Keep trading to gather more data for meaningful insights")
        }

        return insights
    }

    /**
     * Format the backtest report for display.
     */
    fun formatReport(report: BacktestReport): String {
        val sb = StringBuilder()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("          PATTERN BACKTEST REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("Total Trades: ${report.totalTrades}")
        sb.appendLine("Overall Win Rate: ${report.overallWinRate.toInt()}%")
        sb.appendLine()

        if (report.patterns.isNotEmpty()) {
            sb.appendLine("── CHART PATTERNS ────────────────────────────────────────")
            report.patterns.forEach { p ->
                sb.appendLine("${p.recommendation.padEnd(10)} ${p.patternName.padEnd(15)} " +
                    "Win:${p.winRate.toInt()}% PF:${p.profitFactor.format(2)} " +
                    "(${p.wins}W/${p.losses}L) Exp:${p.expectancy.format(1)}%")
            }
            sb.appendLine()
        }

        if (report.emaFanStats.isNotEmpty()) {
            sb.appendLine("── EMA FAN STATES ────────────────────────────────────────")
            report.emaFanStats.take(5).forEach { p ->
                sb.appendLine("${p.patternName.padEnd(12)} Win:${p.winRate.toInt()}% " +
                    "(${p.totalTrades} trades) Avg:${p.avgWinPct.format(1)}%W/${p.avgLossPct.format(1)}%L")
            }
            sb.appendLine()
        }

        if (report.phaseStats.isNotEmpty()) {
            sb.appendLine("── ENTRY PHASES ──────────────────────────────────────────")
            report.phaseStats.take(5).forEach { p ->
                sb.appendLine("${p.patternName.padEnd(18)} Win:${p.winRate.toInt()}% " +
                    "(${p.totalTrades} trades) Best:+${p.bestTrade.toInt()}%")
            }
            sb.appendLine()
        }

        if (report.insights.isNotEmpty()) {
            sb.appendLine("── INSIGHTS ──────────────────────────────────────────────")
            report.insights.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("Generated: ${report.generatedAt}")

        return sb.toString()
    }

    /**
     * Get recommended confidence adjustments based on backtest.
     * Returns a map of pattern -> multiplier (e.g., BULL_FLAG -> 1.2 means boost by 20%)
     */
    fun getConfidenceAdjustments(report: BacktestReport): Map<String, Double> {
        val adjustments = mutableMapOf<String, Double>()

        report.patterns.forEach { p ->
            val multiplier = when (p.recommendation) {
                "BOOST" -> 1.0 + (p.profitFactor - 1.5).coerceIn(0.0, 0.5) * 0.5  // Up to 1.25x
                "KEEP" -> 1.0
                "REDUCE" -> 0.7 + (p.winRate / 100.0) * 0.3  // 0.7 to 1.0
                "DISABLE" -> 0.3
                else -> 1.0
            }
            adjustments[p.patternName] = multiplier
        }

        return adjustments
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}

/**
 * Extension to get all trades from database for backtesting.
 */
fun TradeDatabase.getAllTrades(): List<TradeRecord> {
    val trades = mutableListOf<TradeRecord>()
    val db = this.readableDatabase
    
    val cursor = db.rawQuery(
        "SELECT * FROM trades ORDER BY ts_exit DESC LIMIT 1000", null
    )

    cursor.use { c ->
        while (c.moveToNext()) {
            try {
                fun getCol(name: String): Int = c.getColumnIndex(name).coerceAtLeast(0)
                fun getColOrNeg(name: String): Int = c.getColumnIndex(name)
                
                trades.add(TradeRecord(
                    id = c.getLong(getCol("id")),
                    tsEntry = c.getLong(getCol("ts_entry")),
                    tsExit = c.getLong(getCol("ts_exit")),
                    symbol = c.getString(getCol("symbol")) ?: "",
                    mint = c.getString(getCol("mint")) ?: "",
                    mode = c.getString(getCol("mode")) ?: "",
                    dex = c.getString(getCol("dex")) ?: "",
                    entryPrice = if (getColOrNeg("entry_price") >= 0) c.getDouble(getCol("entry_price")) else 0.0,
                    entryScore = if (getColOrNeg("entry_score") >= 0) c.getDouble(getCol("entry_score")) else 0.0,
                    entryPhase = c.getString(getCol("entry_phase")) ?: "",
                    emaFan = c.getString(getCol("ema_fan")) ?: "",
                    volScore = if (getColOrNeg("vol_score") >= 0) c.getDouble(getCol("vol_score")) else 0.0,
                    pressScore = if (getColOrNeg("press_score") >= 0) c.getDouble(getCol("press_score")) else 0.0,
                    momScore = if (getColOrNeg("mom_score") >= 0) c.getDouble(getCol("mom_score")) else 0.0,
                    stochSignal = if (getColOrNeg("stoch_signal") >= 0) c.getInt(getCol("stoch_signal")) else 0,
                    volDiv = if (getColOrNeg("vol_div") >= 0) c.getInt(getCol("vol_div")) == 1 else false,
                    mtf5m = c.getString(getCol("mtf_5m")) ?: "NEUTRAL",
                    tokenAgeHours = if (getColOrNeg("token_age_hours") >= 0) c.getDouble(getCol("token_age_hours")) else 0.0,
                    holderCount = if (getColOrNeg("holder_count") >= 0) c.getInt(getCol("holder_count")) else 0,
                    holderGrowth = if (getColOrNeg("holder_growth") >= 0) c.getDouble(getCol("holder_growth")) else 0.0,
                    liquidityUsd = if (getColOrNeg("liquidity_usd") >= 0) c.getDouble(getCol("liquidity_usd")) else 0.0,
                    mcapUsd = if (getColOrNeg("mcap_usd") >= 0) c.getDouble(getCol("mcap_usd")) else 0.0,
                    pullbackEntry = if (getColOrNeg("pullback_entry") >= 0) c.getInt(getCol("pullback_entry")) == 1 else false,
                    exitPrice = if (getColOrNeg("exit_price") >= 0) c.getDouble(getCol("exit_price")) else 0.0,
                    exitScore = if (getColOrNeg("exit_score") >= 0) c.getDouble(getCol("exit_score")) else 0.0,
                    exitPhase = c.getString(getCol("exit_phase")) ?: "",
                    exitReason = c.getString(getCol("exit_reason")) ?: "",
                    heldMins = if (getColOrNeg("held_mins") >= 0) c.getDouble(getCol("held_mins")) else 0.0,
                    topUpCount = if (getColOrNeg("top_up_count") >= 0) c.getInt(getCol("top_up_count")) else 0,
                    partialSold = if (getColOrNeg("partial_sold") >= 0) c.getDouble(getCol("partial_sold")) else 0.0,
                    solIn = if (getColOrNeg("sol_in") >= 0) c.getDouble(getCol("sol_in")) else 0.0,
                    solOut = if (getColOrNeg("sol_out") >= 0) c.getDouble(getCol("sol_out")) else 0.0,
                    pnlSol = if (getColOrNeg("pnl_sol") >= 0) c.getDouble(getCol("pnl_sol")) else 0.0,
                    pnlPct = if (getColOrNeg("pnl_pct") >= 0) c.getDouble(getCol("pnl_pct")) else 0.0,
                    isWin = if (getColOrNeg("is_win") >= 0) c.getInt(getCol("is_win")) == 1 else false,
                    source = c.getString(getCol("source")) ?: "",
                    extraJson = c.getString(getCol("extra_json")) ?: "",
                ))
            } catch (e: Exception) {
                // Skip malformed records
            }
        }
    }

    return trades
}
