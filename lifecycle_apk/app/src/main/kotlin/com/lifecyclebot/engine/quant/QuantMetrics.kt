package com.lifecyclebot.engine.quant

import com.lifecyclebot.engine.ErrorLogger
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.ln

/**
 * QUANTITATIVE METRICS ENGINE
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * Professional-grade portfolio analytics:
 * - Sharpe Ratio (risk-adjusted returns)
 * - Sortino Ratio (downside-adjusted returns)
 * - Maximum Drawdown tracking with alerts
 * - Calmar Ratio (return/max drawdown)
 * - Win Rate & Profit Factor
 * - Value at Risk (VaR)
 * - Portfolio Heat Map (exposure concentration)
 * - Token Correlation Analysis
 */
object QuantMetrics {
    
    private const val TAG = "QuantMetrics"
    
    // ════════════════════════════════════════════════════════════════════════
    // TRADE HISTORY FOR CALCULATIONS
    // ════════════════════════════════════════════════════════════════════════
    
    data class TradeRecord(
        val timestamp: Long,
        val symbol: String,
        val mint: String,
        val pnlSol: Double,
        val pnlPct: Double,
        val holdTimeMinutes: Double,
        val entryPhase: String,
        val quality: String,
    )
    
    private val tradeHistory = mutableListOf<TradeRecord>()
    private val dailyReturns = mutableListOf<DailyReturn>()
    private val equityCurve = mutableListOf<EquityPoint>()
    
    data class DailyReturn(
        val date: String,  // YYYY-MM-DD
        val returnPct: Double,
        val pnlSol: Double,
    )
    
    data class EquityPoint(
        val timestamp: Long,
        val equitySol: Double,
        val drawdownPct: Double,
    )
    
    // ════════════════════════════════════════════════════════════════════════
    // MAXIMUM DRAWDOWN TRACKING
    // ════════════════════════════════════════════════════════════════════════
    
    private var peakEquity: Double = 0.0
    private var currentEquity: Double = 0.0
    private var maxDrawdownPct: Double = 0.0
    private var maxDrawdownSol: Double = 0.0
    private var drawdownStartTime: Long = 0L
    private var inDrawdown: Boolean = false
    
    // Alert thresholds
    private const val DRAWDOWN_WARNING_PCT = 15.0   // Yellow alert
    private const val DRAWDOWN_DANGER_PCT = 25.0    // Red alert
    private const val DRAWDOWN_CRITICAL_PCT = 40.0  // Circuit breaker territory
    
    data class DrawdownAlert(
        val level: AlertLevel,
        val currentDrawdownPct: Double,
        val maxDrawdownPct: Double,
        val drawdownSol: Double,
        val durationMinutes: Long,
        val recommendation: String,
    )
    
    enum class AlertLevel { NONE, WARNING, DANGER, CRITICAL }
    
    fun updateEquity(newEquitySol: Double): DrawdownAlert? {
        currentEquity = newEquitySol
        
        // Update peak
        if (newEquitySol > peakEquity) {
            peakEquity = newEquitySol
            inDrawdown = false
            drawdownStartTime = 0L
        }
        
        // Calculate current drawdown
        val drawdownSol = peakEquity - newEquitySol
        val drawdownPct = if (peakEquity > 0) (drawdownSol / peakEquity) * 100.0 else 0.0
        
        // Track if entering drawdown
        if (drawdownPct > 5.0 && !inDrawdown) {
            inDrawdown = true
            drawdownStartTime = System.currentTimeMillis()
        }
        
        // Update max drawdown
        if (drawdownPct > maxDrawdownPct) {
            maxDrawdownPct = drawdownPct
            maxDrawdownSol = drawdownSol
        }
        
        // Record equity curve
        equityCurve.add(EquityPoint(System.currentTimeMillis(), newEquitySol, drawdownPct))
        if (equityCurve.size > 10000) equityCurve.removeAt(0)  // Rolling window
        
        // Generate alert if needed
        return when {
            drawdownPct >= DRAWDOWN_CRITICAL_PCT -> DrawdownAlert(
                level = AlertLevel.CRITICAL,
                currentDrawdownPct = drawdownPct,
                maxDrawdownPct = maxDrawdownPct,
                drawdownSol = drawdownSol,
                durationMinutes = if (drawdownStartTime > 0) (System.currentTimeMillis() - drawdownStartTime) / 60000 else 0,
                recommendation = "CRITICAL: ${drawdownPct.fmt(1)}% drawdown. Circuit breaker should trigger. Consider pausing all new entries."
            )
            drawdownPct >= DRAWDOWN_DANGER_PCT -> DrawdownAlert(
                level = AlertLevel.DANGER,
                currentDrawdownPct = drawdownPct,
                maxDrawdownPct = maxDrawdownPct,
                drawdownSol = drawdownSol,
                durationMinutes = if (drawdownStartTime > 0) (System.currentTimeMillis() - drawdownStartTime) / 60000 else 0,
                recommendation = "DANGER: ${drawdownPct.fmt(1)}% drawdown. Reduce position sizes. Only take A+ setups."
            )
            drawdownPct >= DRAWDOWN_WARNING_PCT -> DrawdownAlert(
                level = AlertLevel.WARNING,
                currentDrawdownPct = drawdownPct,
                maxDrawdownPct = maxDrawdownPct,
                drawdownSol = drawdownSol,
                durationMinutes = if (drawdownStartTime > 0) (System.currentTimeMillis() - drawdownStartTime) / 60000 else 0,
                recommendation = "WARNING: ${drawdownPct.fmt(1)}% drawdown. Tighten risk. Be selective on entries."
            )
            else -> null
        }
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // SHARPE RATIO
    // ════════════════════════════════════════════════════════════════════════
    // Measures risk-adjusted returns: (Return - RiskFreeRate) / StdDev
    // Higher = better risk-adjusted performance
    // > 1.0 = Good, > 2.0 = Very Good, > 3.0 = Excellent
    
    private const val RISK_FREE_RATE_DAILY = 0.0001  // ~3.6% annualized
    
    fun calculateSharpeRatio(periodDays: Int = 30): Double {
        val returns = getReturnsForPeriod(periodDays)
        if (returns.size < 5) return 0.0
        
        val avgReturn = returns.average()
        val stdDev = returns.standardDeviation()
        
        if (stdDev == 0.0) return 0.0
        
        // Daily Sharpe, annualized
        val dailySharpe = (avgReturn - RISK_FREE_RATE_DAILY) / stdDev
        return dailySharpe * sqrt(252.0)  // Annualize (252 trading days)
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // SORTINO RATIO
    // ════════════════════════════════════════════════════════════════════════
    // Like Sharpe but only penalizes downside volatility
    // Better for strategies with asymmetric returns (like moonshot hunting)
    
    fun calculateSortinoRatio(periodDays: Int = 30): Double {
        val returns = getReturnsForPeriod(periodDays)
        if (returns.size < 5) return 0.0
        
        val avgReturn = returns.average()
        val downsideReturns = returns.filter { it < 0 }
        
        if (downsideReturns.isEmpty()) return Double.MAX_VALUE  // No downside = infinite Sortino
        
        val downsideDeviation = downsideReturns.map { it.pow(2) }.average().let { sqrt(it) }
        if (downsideDeviation == 0.0) return Double.MAX_VALUE
        
        val dailySortino = (avgReturn - RISK_FREE_RATE_DAILY) / downsideDeviation
        return dailySortino * sqrt(252.0)
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // CALMAR RATIO
    // ════════════════════════════════════════════════════════════════════════
    // Annual Return / Max Drawdown
    // Measures return per unit of drawdown risk
    // > 1.0 = Acceptable, > 3.0 = Excellent
    
    fun calculateCalmarRatio(periodDays: Int = 365): Double {
        if (maxDrawdownPct == 0.0) return Double.MAX_VALUE
        
        val returns = getReturnsForPeriod(periodDays)
        if (returns.isEmpty()) return 0.0
        
        val totalReturn = returns.sum()
        val annualizedReturn = totalReturn * (365.0 / periodDays.coerceAtLeast(1))
        
        return annualizedReturn / maxDrawdownPct
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // PROFIT FACTOR
    // ════════════════════════════════════════════════════════════════════════
    // Gross Profit / Gross Loss
    // > 1.0 = Profitable, > 1.5 = Good, > 2.0 = Excellent
    
    fun calculateProfitFactor(periodDays: Int = 30): Double {
        val cutoff = System.currentTimeMillis() - (periodDays * 24 * 60 * 60 * 1000L)
        val recentTrades = tradeHistory.filter { it.timestamp >= cutoff }
        
        val grossProfit = recentTrades.filter { it.pnlSol > 0 }.sumOf { it.pnlSol }
        val grossLoss = recentTrades.filter { it.pnlSol < 0 }.sumOf { abs(it.pnlSol) }
        
        if (grossLoss == 0.0) return Double.MAX_VALUE
        return grossProfit / grossLoss
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // VALUE AT RISK (VaR)
    // ════════════════════════════════════════════════════════════════════════
    // Estimates maximum expected loss at a confidence level
    // "95% VaR of 5% means: 95% of the time, you won't lose more than 5%"
    
    fun calculateVaR(confidenceLevel: Double = 0.95, periodDays: Int = 30): Double {
        val returns = getReturnsForPeriod(periodDays)
        if (returns.size < 10) return 0.0
        
        val sorted = returns.sorted()
        val index = ((1 - confidenceLevel) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        
        return abs(sorted[index])  // Return as positive percentage
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // WIN RATE & EXPECTANCY
    // ════════════════════════════════════════════════════════════════════════
    
    data class WinRateStats(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val scratches: Int,
        val winRate: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val expectancy: Double,  // Expected value per trade
        val largestWin: Double,
        val largestLoss: Double,
        val winLossRatio: Double,
        val consecutiveWins: Int,
        val consecutiveLosses: Int,
    )
    
    fun calculateWinRateStats(periodDays: Int = 30): WinRateStats {
        val cutoff = System.currentTimeMillis() - (periodDays * 24 * 60 * 60 * 1000L)
        val trades = tradeHistory.filter { it.timestamp >= cutoff }
        
        val wins = trades.filter { it.pnlPct >= 5.0 }
        val losses = trades.filter { it.pnlPct <= -5.0 }
        val scratches = trades.filter { it.pnlPct > -5.0 && it.pnlPct < 5.0 }
        
        val winRate = if (trades.isNotEmpty()) wins.size.toDouble() / trades.size * 100 else 0.0
        val avgWin = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { abs(it.pnlPct) }.average() else 0.0
        
        // Expectancy = (Win% × AvgWin) - (Loss% × AvgLoss)
        val winPct = wins.size.toDouble() / trades.size.coerceAtLeast(1)
        val lossPct = losses.size.toDouble() / trades.size.coerceAtLeast(1)
        val expectancy = (winPct * avgWin) - (lossPct * avgLoss)
        
        // Consecutive tracking
        var maxConsecWins = 0
        var maxConsecLosses = 0
        var currentConsecWins = 0
        var currentConsecLosses = 0
        
        for (trade in trades.sortedBy { it.timestamp }) {
            if (trade.pnlPct >= 5.0) {
                currentConsecWins++
                currentConsecLosses = 0
                maxConsecWins = maxOf(maxConsecWins, currentConsecWins)
            } else if (trade.pnlPct <= -5.0) {
                currentConsecLosses++
                currentConsecWins = 0
                maxConsecLosses = maxOf(maxConsecLosses, currentConsecLosses)
            }
        }
        
        return WinRateStats(
            totalTrades = trades.size,
            wins = wins.size,
            losses = losses.size,
            scratches = scratches.size,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLoss,
            expectancy = expectancy,
            largestWin = trades.maxOfOrNull { it.pnlPct } ?: 0.0,
            largestLoss = trades.minOfOrNull { it.pnlPct } ?: 0.0,
            winLossRatio = if (avgLoss > 0) avgWin / avgLoss else Double.MAX_VALUE,
            consecutiveWins = maxConsecWins,
            consecutiveLosses = maxConsecLosses,
        )
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // RECORD TRADE
    // ════════════════════════════════════════════════════════════════════════
    
    fun recordTrade(
        symbol: String,
        mint: String,
        pnlSol: Double,
        pnlPct: Double,
        holdTimeMinutes: Double,
        entryPhase: String,
        quality: String,
    ) {
        val record = TradeRecord(
            timestamp = System.currentTimeMillis(),
            symbol = symbol,
            mint = mint,
            pnlSol = pnlSol,
            pnlPct = pnlPct,
            holdTimeMinutes = holdTimeMinutes,
            entryPhase = entryPhase,
            quality = quality,
        )
        tradeHistory.add(record)
        
        // Keep rolling window of 1000 trades
        if (tradeHistory.size > 1000) tradeHistory.removeAt(0)
        
        // Update daily returns
        val today = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
        val todayReturn = dailyReturns.find { it.date == today }
        if (todayReturn != null) {
            val index = dailyReturns.indexOf(todayReturn)
            dailyReturns[index] = todayReturn.copy(
                returnPct = todayReturn.returnPct + pnlPct,
                pnlSol = todayReturn.pnlSol + pnlSol,
            )
        } else {
            dailyReturns.add(DailyReturn(today, pnlPct, pnlSol))
        }
        
        // Keep rolling 365 days
        if (dailyReturns.size > 365) dailyReturns.removeAt(0)
        
        ErrorLogger.debug(TAG, "📊 Trade recorded: $symbol ${pnlPct.fmt(1)}% | " +
            "Sharpe: ${calculateSharpeRatio(30).fmt(2)} | " +
            "Sortino: ${calculateSortinoRatio(30).fmt(2)} | " +
            "PF: ${calculateProfitFactor(30).fmt(2)}")
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // FULL METRICS REPORT
    // ════════════════════════════════════════════════════════════════════════
    
    data class QuantReport(
        val sharpeRatio: Double,
        val sortinoRatio: Double,
        val calmarRatio: Double,
        val profitFactor: Double,
        val maxDrawdownPct: Double,
        val currentDrawdownPct: Double,
        val var95: Double,
        val winRateStats: WinRateStats,
        val grade: String,
        val summary: String,
    )
    
    fun generateReport(periodDays: Int = 30): QuantReport {
        val sharpe = calculateSharpeRatio(periodDays)
        val sortino = calculateSortinoRatio(periodDays)
        val calmar = calculateCalmarRatio(periodDays)
        val pf = calculateProfitFactor(periodDays)
        val var95 = calculateVaR(0.95, periodDays)
        val winStats = calculateWinRateStats(periodDays)
        val currentDD = if (peakEquity > 0) ((peakEquity - currentEquity) / peakEquity) * 100 else 0.0
        
        // Grade the strategy
        val grade = when {
            sharpe >= 3.0 && pf >= 2.0 && winStats.expectancy >= 10 -> "A+"
            sharpe >= 2.0 && pf >= 1.5 && winStats.expectancy >= 5 -> "A"
            sharpe >= 1.5 && pf >= 1.3 && winStats.expectancy >= 2 -> "B+"
            sharpe >= 1.0 && pf >= 1.1 && winStats.expectancy >= 0 -> "B"
            sharpe >= 0.5 && pf >= 1.0 -> "C"
            else -> "D"
        }
        
        val summary = buildString {
            append("📊 QUANT REPORT ($periodDays days)\n")
            append("═══════════════════════════════════\n")
            append("Sharpe Ratio: ${sharpe.fmt(2)} ${sharpeGrade(sharpe)}\n")
            append("Sortino Ratio: ${sortino.fmt(2)} ${sortinoGrade(sortino)}\n")
            append("Profit Factor: ${pf.fmt(2)} ${pfGrade(pf)}\n")
            append("Calmar Ratio: ${calmar.fmt(2)}\n")
            append("95% VaR: ${var95.fmt(1)}%\n")
            append("───────────────────────────────────\n")
            append("Win Rate: ${winStats.winRate.fmt(1)}%\n")
            append("Expectancy: ${winStats.expectancy.fmt(2)}% per trade\n")
            append("Avg Win/Loss: ${winStats.avgWin.fmt(1)}% / ${winStats.avgLoss.fmt(1)}%\n")
            append("Win/Loss Ratio: ${winStats.winLossRatio.fmt(2)}\n")
            append("───────────────────────────────────\n")
            append("Max Drawdown: ${maxDrawdownPct.fmt(1)}%\n")
            append("Current Drawdown: ${currentDD.fmt(1)}%\n")
            append("───────────────────────────────────\n")
            append("Overall Grade: $grade\n")
        }
        
        return QuantReport(
            sharpeRatio = sharpe,
            sortinoRatio = sortino,
            calmarRatio = calmar,
            profitFactor = pf,
            maxDrawdownPct = maxDrawdownPct,
            currentDrawdownPct = currentDD,
            var95 = var95,
            winRateStats = winStats,
            grade = grade,
            summary = summary,
        )
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════
    
    private fun getReturnsForPeriod(days: Int): List<Double> {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return tradeHistory
            .filter { it.timestamp >= cutoff }
            .map { it.pnlPct / 100.0 }  // Convert to decimal
    }
    
    private fun List<Double>.standardDeviation(): Double {
        if (size < 2) return 0.0
        val mean = average()
        return sqrt(map { (it - mean).pow(2) }.average())
    }
    
    private fun sharpeGrade(sharpe: Double) = when {
        sharpe >= 3.0 -> "⭐⭐⭐"
        sharpe >= 2.0 -> "⭐⭐"
        sharpe >= 1.0 -> "⭐"
        sharpe >= 0.5 -> "○"
        else -> "✗"
    }
    
    private fun sortinoGrade(sortino: Double) = when {
        sortino >= 4.0 -> "⭐⭐⭐"
        sortino >= 2.5 -> "⭐⭐"
        sortino >= 1.5 -> "⭐"
        sortino >= 0.5 -> "○"
        else -> "✗"
    }
    
    private fun pfGrade(pf: Double) = when {
        pf >= 2.5 -> "⭐⭐⭐"
        pf >= 1.8 -> "⭐⭐"
        pf >= 1.3 -> "⭐"
        pf >= 1.0 -> "○"
        else -> "✗"
    }
    
    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
    
    // ════════════════════════════════════════════════════════════════════════
    // RESET
    // ════════════════════════════════════════════════════════════════════════
    
    fun reset() {
        tradeHistory.clear()
        dailyReturns.clear()
        equityCurve.clear()
        peakEquity = 0.0
        currentEquity = 0.0
        maxDrawdownPct = 0.0
        maxDrawdownSol = 0.0
        drawdownStartTime = 0L
        inDrawdown = false
    }
}
