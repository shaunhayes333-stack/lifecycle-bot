package com.lifecyclebot.engine.quant

import com.lifecyclebot.engine.ErrorLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * QuantMetrics
 *
 * Serious portfolio / strategy analytics layer for AATE.
 *
 * Design goals:
 * - thread-safe
 * - numerically stable
 * - equity-aware
 * - conservative on low sample sizes
 * - usable for both paper and live risk control
 */
object QuantMetrics {

    private const val TAG = "QuantMetrics"

    // -------------------------------------------------------------------------
    // CONFIG
    // -------------------------------------------------------------------------

    private const val MAX_TRADES = 5_000
    private const val MAX_EQUITY_POINTS = 20_000
    private const val MAX_DAILY_RETURNS = 730 // ~2 years

    // Approx 4% annualized risk-free rate converted to daily
    private const val RISK_FREE_RATE_ANNUAL = 0.04
    private const val TRADING_DAYS_PER_YEAR = 252.0
    private const val CALENDAR_DAYS_PER_YEAR = 365.25
    private val RISK_FREE_RATE_DAILY = RISK_FREE_RATE_ANNUAL / TRADING_DAYS_PER_YEAR

    // Drawdown alert levels
    private const val DRAWDOWN_WARNING_PCT = 15.0
    private const val DRAWDOWN_DANGER_PCT = 25.0
    private const val DRAWDOWN_CRITICAL_PCT = 40.0

    private val lock = Any()

    // -------------------------------------------------------------------------
    // DATA MODELS
    // -------------------------------------------------------------------------

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

    data class DailyReturn(
        val date: String,          // yyyy-MM-dd
        val returnPct: Double,     // daily arithmetic return in percent
        val pnlSol: Double,
    )

    data class EquityPoint(
        val timestamp: Long,
        val equitySol: Double,
        val drawdownPct: Double,
    )

    data class DrawdownAlert(
        val level: AlertLevel,
        val currentDrawdownPct: Double,
        val maxDrawdownPct: Double,
        val drawdownSol: Double,
        val durationMinutes: Long,
        val recommendation: String,
    )

    enum class AlertLevel { NONE, WARNING, DANGER, CRITICAL }

    data class WinRateStats(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val scratches: Int,
        val winRate: Double,
        val lossRate: Double,
        val scratchRate: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val expectancyPct: Double,
        val expectancySol: Double,
        val largestWinPct: Double,
        val largestLossPct: Double,
        val largestWinSol: Double,
        val largestLossSol: Double,
        val winLossRatio: Double,
        val maxConsecutiveWins: Int,
        val maxConsecutiveLosses: Int,
        val currentStreak: Int, // positive = wins, negative = losses
    )

    data class RiskStats(
        val var95Pct: Double,
        val cvar95Pct: Double,
        val var99Pct: Double,
        val cvar99Pct: Double,
        val downsideDeviationPct: Double,
        val volatilityPct: Double,
    )

    data class QuantReport(
        val sharpeRatio: Double,
        val sortinoRatio: Double,
        val calmarRatio: Double,
        val profitFactor: Double,
        val payoffRatio: Double,
        val maxDrawdownPct: Double,
        val currentDrawdownPct: Double,
        val riskStats: RiskStats,
        val winRateStats: WinRateStats,
        val grade: String,
        val sampleAdequacy: String,
        val summary: String,
    )

    // -------------------------------------------------------------------------
    // STATE
    // -------------------------------------------------------------------------

    private val tradeHistory = ArrayDeque<TradeRecord>()
    private val dailyReturns = LinkedHashMap<String, DailyReturn>()
    private val equityCurve = ArrayDeque<EquityPoint>()

    private var peakEquity: Double = 0.0
    private var currentEquity: Double = 0.0
    private var troughEquitySincePeak: Double = 0.0
    private var maxDrawdownPct: Double = 0.0
    private var maxDrawdownSol: Double = 0.0
    private var drawdownStartTime: Long = 0L
    private var longestDrawdownMinutes: Long = 0L
    private var inDrawdown: Boolean = false

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    /**
     * Update equity and derive drawdown state.
     * This should be fed from wallet / portfolio NAV, not trade pnl alone.
     */
    fun updateEquity(newEquitySol: Double, timestamp: Long = System.currentTimeMillis()): DrawdownAlert? {
        synchronized(lock) {
            currentEquity = newEquitySol

            if (peakEquity <= 0.0) {
                peakEquity = newEquitySol
                troughEquitySincePeak = newEquitySol
            }

            if (newEquitySol >= peakEquity) {
                // Recovery / new high
                peakEquity = newEquitySol
                troughEquitySincePeak = newEquitySol

                if (inDrawdown && drawdownStartTime > 0L) {
                    val ddMinutes = ((timestamp - drawdownStartTime) / 60_000L).coerceAtLeast(0L)
                    longestDrawdownMinutes = maxOf(longestDrawdownMinutes, ddMinutes)
                }

                inDrawdown = false
                drawdownStartTime = 0L
            } else {
                troughEquitySincePeak = minOf(troughEquitySincePeak, newEquitySol)
            }

            val drawdownSol = (peakEquity - newEquitySol).coerceAtLeast(0.0)
            val drawdownPct = if (peakEquity > 0.0) {
                (drawdownSol / peakEquity) * 100.0
            } else {
                0.0
            }

            if (drawdownPct > 0.0 && !inDrawdown) {
                inDrawdown = true
                drawdownStartTime = timestamp
            }

            if (drawdownPct > maxDrawdownPct) {
                maxDrawdownPct = drawdownPct
                maxDrawdownSol = drawdownSol
            }

            equityCurve.addLast(
                EquityPoint(
                    timestamp = timestamp,
                    equitySol = newEquitySol,
                    drawdownPct = drawdownPct,
                )
            )
            trimEquityCurve()

            return buildDrawdownAlert(drawdownPct, drawdownSol, timestamp)
        }
    }

    /**
     * Record a trade outcome.
     */
    fun recordTrade(
        symbol: String,
        mint: String,
        pnlSol: Double,
        pnlPct: Double,
        holdTimeMinutes: Double,
        entryPhase: String,
        quality: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            tradeHistory.addLast(
                TradeRecord(
                    timestamp = timestamp,
                    symbol = symbol,
                    mint = mint,
                    pnlSol = pnlSol,
                    pnlPct = pnlPct,
                    holdTimeMinutes = holdTimeMinutes,
                    entryPhase = entryPhase,
                    quality = quality,
                )
            )
            trimTradeHistory()

            val date = dateKey(timestamp)
            val existing = dailyReturns[date]
            dailyReturns[date] = if (existing == null) {
                DailyReturn(
                    date = date,
                    returnPct = pnlPct,
                    pnlSol = pnlSol,
                )
            } else {
                existing.copy(
                    returnPct = existing.returnPct + pnlPct,
                    pnlSol = existing.pnlSol + pnlSol,
                )
            }
            trimDailyReturns()

            val sharpe = calculateSharpeRatioLocked(30)
            val sortino = calculateSortinoRatioLocked(30)
            val pf = calculateProfitFactorLocked(30)

            ErrorLogger.debug(
                TAG,
                "📊 Trade recorded: $symbol ${pnlPct.fmt(1)}% | Sharpe: ${sharpe.fmt(2)} | Sortino: ${sortino.fmt(2)} | PF: ${pf.fmt(2)}"
            )
        }
    }

    fun calculateSharpeRatio(periodDays: Int = 30): Double = synchronized(lock) {
        calculateSharpeRatioLocked(periodDays)
    }

    fun calculateSortinoRatio(periodDays: Int = 30): Double = synchronized(lock) {
        calculateSortinoRatioLocked(periodDays)
    }

    fun calculateCalmarRatio(periodDays: Int = 365): Double = synchronized(lock) {
        calculateCalmarRatioLocked(periodDays)
    }

    fun calculateProfitFactor(periodDays: Int = 30): Double = synchronized(lock) {
        calculateProfitFactorLocked(periodDays)
    }

    fun calculateVaR(confidenceLevel: Double = 0.95, periodDays: Int = 30): Double = synchronized(lock) {
        calculateRiskStatsLocked(periodDays).let {
            when {
                confidenceLevel >= 0.99 -> it.var99Pct
                else -> it.var95Pct
            }
        }
    }

    fun calculateCVaR(confidenceLevel: Double = 0.95, periodDays: Int = 30): Double = synchronized(lock) {
        calculateRiskStatsLocked(periodDays).let {
            when {
                confidenceLevel >= 0.99 -> it.cvar99Pct
                else -> it.cvar95Pct
            }
        }
    }

    fun calculateWinRateStats(periodDays: Int = 30): WinRateStats = synchronized(lock) {
        calculateWinRateStatsLocked(periodDays)
    }

    fun generateReport(periodDays: Int = 30): QuantReport = synchronized(lock) {
        val sharpe = calculateSharpeRatioLocked(periodDays)
        val sortino = calculateSortinoRatioLocked(periodDays)
        val calmar = calculateCalmarRatioLocked(periodDays)
        val profitFactor = calculateProfitFactorLocked(periodDays)
        val riskStats = calculateRiskStatsLocked(periodDays)
        val winStats = calculateWinRateStatsLocked(periodDays)
        val currentDd = currentDrawdownPctLocked()
        val payoffRatio = if (winStats.avgLoss > 0.0) winStats.avgWin / winStats.avgLoss else 0.0

        val sampleSize = tradesForPeriodLocked(periodDays).size
        val sampleAdequacy = when {
            sampleSize >= 100 -> "ROBUST"
            sampleSize >= 40 -> "USABLE"
            sampleSize >= 15 -> "THIN"
            else -> "INSUFFICIENT"
        }

        val grade = gradeStrategy(
            sharpe = sharpe,
            sortino = sortino,
            calmar = calmar,
            pf = profitFactor,
            expectancy = winStats.expectancyPct,
            maxDd = maxDrawdownPct,
            sampleAdequacy = sampleAdequacy,
        )

        val summary = buildString {
            appendLine("📊 QUANT REPORT ($periodDays days)")
            appendLine("════════════════════════════════════")
            appendLine("Sample Quality: $sampleAdequacy")
            appendLine("Sharpe Ratio: ${sharpe.fmt(2)} ${sharpeGrade(sharpe)}")
            appendLine("Sortino Ratio: ${sortino.fmt(2)} ${sortinoGrade(sortino)}")
            appendLine("Calmar Ratio: ${calmar.fmt(2)}")
            appendLine("Profit Factor: ${profitFactor.fmt(2)} ${pfGrade(profitFactor)}")
            appendLine("Payoff Ratio: ${payoffRatio.fmt(2)}")
            appendLine("Volatility: ${riskStats.volatilityPct.fmt(2)}%")
            appendLine("Downside Dev: ${riskStats.downsideDeviationPct.fmt(2)}%")
            appendLine("VaR 95: ${riskStats.var95Pct.fmt(2)}%")
            appendLine("CVaR 95: ${riskStats.cvar95Pct.fmt(2)}%")
            appendLine("VaR 99: ${riskStats.var99Pct.fmt(2)}%")
            appendLine("CVaR 99: ${riskStats.cvar99Pct.fmt(2)}%")
            appendLine("────────────────────────────────────")
            appendLine("Trades: ${winStats.totalTrades}")
            appendLine("Win / Loss / Scratch: ${winStats.wins} / ${winStats.losses} / ${winStats.scratches}")
            appendLine("Win Rate: ${winStats.winRate.fmt(1)}%")
            appendLine("Loss Rate: ${winStats.lossRate.fmt(1)}%")
            appendLine("Avg Win / Loss: ${winStats.avgWin.fmt(2)}% / ${winStats.avgLoss.fmt(2)}%")
            appendLine("Expectancy: ${winStats.expectancyPct.fmt(2)}% per trade")
            appendLine("Largest Win / Loss: ${winStats.largestWinPct.fmt(2)}% / ${winStats.largestLossPct.fmt(2)}%")
            appendLine("Max Win Streak: ${winStats.maxConsecutiveWins}")
            appendLine("Max Loss Streak: ${winStats.maxConsecutiveLosses}")
            appendLine("Current Streak: ${formatStreak(winStats.currentStreak)}")
            appendLine("────────────────────────────────────")
            appendLine("Max Drawdown: ${maxDrawdownPct.fmt(2)}%")
            appendLine("Current Drawdown: ${currentDd.fmt(2)}%")
            appendLine("Longest Drawdown: ${longestDrawdownMinutes} min")
            appendLine("────────────────────────────────────")
            appendLine("Overall Grade: $grade")
        }

        QuantReport(
            sharpeRatio = sharpe,
            sortinoRatio = sortino,
            calmarRatio = calmar,
            profitFactor = profitFactor,
            payoffRatio = payoffRatio,
            maxDrawdownPct = maxDrawdownPct,
            currentDrawdownPct = currentDd,
            riskStats = riskStats,
            winRateStats = winStats,
            grade = grade,
            sampleAdequacy = sampleAdequacy,
            summary = summary,
        )
    }

    fun reset() {
        synchronized(lock) {
            tradeHistory.clear()
            dailyReturns.clear()
            equityCurve.clear()

            peakEquity = 0.0
            currentEquity = 0.0
            troughEquitySincePeak = 0.0
            maxDrawdownPct = 0.0
            maxDrawdownSol = 0.0
            drawdownStartTime = 0L
            longestDrawdownMinutes = 0L
            inDrawdown = false
        }
    }

    // -------------------------------------------------------------------------
    // INTERNAL METRIC CALCULATIONS
    // -------------------------------------------------------------------------

    private fun calculateSharpeRatioLocked(periodDays: Int): Double {
        val returns = periodicReturnsLocked(periodDays)
        if (returns.size < 5) return 0.0

        val avg = returns.average()
        val stdev = returns.standardDeviation()
        if (stdev <= 1e-12) return 0.0

        val excess = avg - RISK_FREE_RATE_DAILY
        return (excess / stdev) * sqrt(TRADING_DAYS_PER_YEAR)
    }

    private fun calculateSortinoRatioLocked(periodDays: Int): Double {
        val returns = periodicReturnsLocked(periodDays)
        if (returns.size < 5) return 0.0

        val avg = returns.average()
        val downside = returns.map { minOf(0.0, it - RISK_FREE_RATE_DAILY) }
        val downsideDeviation = sqrt(downside.map { it.pow(2) }.average())

        if (downsideDeviation <= 1e-12) {
            return if (avg > RISK_FREE_RATE_DAILY) 999.0 else 0.0
        }

        return ((avg - RISK_FREE_RATE_DAILY) / downsideDeviation) * sqrt(TRADING_DAYS_PER_YEAR)
    }

    private fun calculateCalmarRatioLocked(periodDays: Int): Double {
        val returns = periodicReturnsLocked(periodDays)
        if (returns.size < 5 || maxDrawdownPct <= 1e-12) return 0.0

        val cumulative = returns.fold(1.0) { acc, r -> acc * (1.0 + r) }
        if (cumulative <= 0.0) return -999.0

        val years = (periodDays.toDouble() / CALENDAR_DAYS_PER_YEAR).coerceAtLeast(1.0 / CALENDAR_DAYS_PER_YEAR)
        val cagr = cumulative.pow(1.0 / years) - 1.0
        val maxDdDecimal = maxDrawdownPct / 100.0

        return if (maxDdDecimal > 0.0) cagr / maxDdDecimal else 0.0
    }

    private fun calculateProfitFactorLocked(periodDays: Int): Double {
        val trades = tradesForPeriodLocked(periodDays)
        if (trades.isEmpty()) return 0.0

        val grossProfit = trades.filter { it.pnlSol > 0.0 }.sumOf { it.pnlSol }
        val grossLoss = trades.filter { it.pnlSol < 0.0 }.sumOf { abs(it.pnlSol) }

        return when {
            grossProfit > 0.0 && grossLoss <= 1e-12 -> 999.0
            grossLoss <= 1e-12 -> 0.0
            else -> grossProfit / grossLoss
        }
    }

    private fun calculateRiskStatsLocked(periodDays: Int): RiskStats {
        val returns = periodicReturnsLocked(periodDays)
        if (returns.size < 10) {
            return RiskStats(
                var95Pct = 0.0,
                cvar95Pct = 0.0,
                var99Pct = 0.0,
                cvar99Pct = 0.0,
                downsideDeviationPct = 0.0,
                volatilityPct = 0.0,
            )
        }

        val sorted = returns.sorted()
        val var95 = percentileLoss(sorted, 0.95)
        val cvar95 = conditionalVar(sorted, 0.95)
        val var99 = percentileLoss(sorted, 0.99)
        val cvar99 = conditionalVar(sorted, 0.99)
        val downside = returns.filter { it < 0.0 }
        val downsideDev = if (downside.isEmpty()) 0.0 else sqrt(downside.map { it.pow(2) }.average())
        val vol = returns.standardDeviation()

        return RiskStats(
            var95Pct = var95 * 100.0,
            cvar95Pct = cvar95 * 100.0,
            var99Pct = var99 * 100.0,
            cvar99Pct = cvar99 * 100.0,
            downsideDeviationPct = downsideDev * 100.0,
            volatilityPct = vol * 100.0,
        )
    }

    private fun calculateWinRateStatsLocked(periodDays: Int): WinRateStats {
        val trades = tradesForPeriodLocked(periodDays).sortedBy { it.timestamp }

        val wins = trades.filter { it.pnlPct > 0.0 }
        val losses = trades.filter { it.pnlPct < 0.0 }
        val scratches = trades.filter { it.pnlPct == 0.0 }

        val total = trades.size.coerceAtLeast(1)

        val winRate = wins.size.toDouble() / total * 100.0
        val lossRate = losses.size.toDouble() / total * 100.0
        val scratchRate = scratches.size.toDouble() / total * 100.0

        val avgWinPct = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 0.0
        val avgLossPct = if (losses.isNotEmpty()) losses.map { abs(it.pnlPct) }.average() else 0.0
        val avgWinSol = if (wins.isNotEmpty()) wins.map { it.pnlSol }.average() else 0.0
        val avgLossSol = if (losses.isNotEmpty()) losses.map { abs(it.pnlSol) }.average() else 0.0

        val winProb = wins.size.toDouble() / total
        val lossProb = losses.size.toDouble() / total

        val expectancyPct = (winProb * avgWinPct) - (lossProb * avgLossPct)
        val expectancySol = (winProb * avgWinSol) - (lossProb * avgLossSol)

        var maxWins = 0
        var maxLosses = 0
        var currentWins = 0
        var currentLosses = 0
        var currentStreak = 0

        for (trade in trades) {
            when {
                trade.pnlPct > 0.0 -> {
                    currentWins++
                    currentLosses = 0
                    maxWins = maxOf(maxWins, currentWins)
                    currentStreak = if (currentStreak >= 0) currentStreak + 1 else 1
                }
                trade.pnlPct < 0.0 -> {
                    currentLosses++
                    currentWins = 0
                    maxLosses = maxOf(maxLosses, currentLosses)
                    currentStreak = if (currentStreak <= 0) currentStreak - 1 else -1
                }
                else -> {
                    currentWins = 0
                    currentLosses = 0
                    currentStreak = 0
                }
            }
        }

        return WinRateStats(
            totalTrades = trades.size,
            wins = wins.size,
            losses = losses.size,
            scratches = scratches.size,
            winRate = winRate,
            lossRate = lossRate,
            scratchRate = scratchRate,
            avgWin = avgWinPct,
            avgLoss = avgLossPct,
            expectancyPct = expectancyPct,
            expectancySol = expectancySol,
            largestWinPct = trades.maxOfOrNull { it.pnlPct } ?: 0.0,
            largestLossPct = trades.minOfOrNull { it.pnlPct } ?: 0.0,
            largestWinSol = trades.maxOfOrNull { it.pnlSol } ?: 0.0,
            largestLossSol = trades.minOfOrNull { it.pnlSol } ?: 0.0,
            winLossRatio = if (avgLossPct > 0.0) avgWinPct / avgLossPct else 0.0,
            maxConsecutiveWins = maxWins,
            maxConsecutiveLosses = maxLosses,
            currentStreak = currentStreak,
        )
    }

    // -------------------------------------------------------------------------
    // INTERNAL HELPERS
    // -------------------------------------------------------------------------

    /**
     * Prefer daily equity / aggregated daily returns if available.
     * Falls back to trade returns if daily aggregation is thin.
     */
    private fun periodicReturnsLocked(periodDays: Int): List<Double> {
        val cutoffDate = System.currentTimeMillis() - periodDays * 24L * 60L * 60L * 1000L

        val daily = dailyReturns.values
            .filter { parseDateKey(it.date) >= cutoffDate }
            .map { it.returnPct / 100.0 }

        if (daily.size >= 5) return daily

        return tradesForPeriodLocked(periodDays).map { it.pnlPct / 100.0 }
    }

    private fun tradesForPeriodLocked(periodDays: Int): List<TradeRecord> {
        val cutoff = System.currentTimeMillis() - periodDays * 24L * 60L * 60L * 1000L
        return tradeHistory.filter { it.timestamp >= cutoff }
    }

    private fun currentDrawdownPctLocked(): Double {
        return if (peakEquity > 0.0) {
            ((peakEquity - currentEquity) / peakEquity * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }
    }

    private fun buildDrawdownAlert(drawdownPct: Double, drawdownSol: Double, now: Long): DrawdownAlert? {
        if (drawdownPct < DRAWDOWN_WARNING_PCT) return null

        val durationMinutes = if (drawdownStartTime > 0L) {
            ((now - drawdownStartTime) / 60_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        return when {
            drawdownPct >= DRAWDOWN_CRITICAL_PCT -> DrawdownAlert(
                level = AlertLevel.CRITICAL,
                currentDrawdownPct = drawdownPct,
                maxDrawdownPct = maxDrawdownPct,
                drawdownSol = drawdownSol,
                durationMinutes = durationMinutes,
                recommendation = "CRITICAL: ${drawdownPct.fmt(1)}% drawdown. Halt new risk, cut gross exposure, and review all live positions."
            )
            drawdownPct >= DRAWDOWN_DANGER_PCT -> DrawdownAlert(
                level = AlertLevel.DANGER,
                currentDrawdownPct = drawdownPct,
                maxDrawdownPct = maxDrawdownPct,
                drawdownSol = drawdownSol,
                durationMinutes = durationMinutes,
                recommendation = "DANGER: ${drawdownPct.fmt(1)}% drawdown. Reduce size, restrict entries to top-tier setups only."
            )
            else -> DrawdownAlert(
                level = AlertLevel.WARNING,
                currentDrawdownPct = drawdownPct,
                maxDrawdownPct = maxDrawdownPct,
                drawdownSol = drawdownSol,
                durationMinutes = durationMinutes,
                recommendation = "WARNING: ${drawdownPct.fmt(1)}% drawdown. Tighten filters and monitor risk concentration."
            )
        }
    }

    private fun percentileLoss(sortedReturns: List<Double>, confidence: Double): Double {
        if (sortedReturns.isEmpty()) return 0.0
        val alpha = (1.0 - confidence).coerceIn(0.0, 1.0)
        val index = (alpha * (sortedReturns.size - 1)).toInt().coerceIn(0, sortedReturns.lastIndex)
        return abs(sortedReturns[index].coerceAtMost(0.0))
    }

    private fun conditionalVar(sortedReturns: List<Double>, confidence: Double): Double {
        if (sortedReturns.isEmpty()) return 0.0
        val alpha = (1.0 - confidence).coerceIn(0.0, 1.0)
        val cutoffIndex = (alpha * (sortedReturns.size - 1)).toInt().coerceIn(0, sortedReturns.lastIndex)
        val tail = sortedReturns.take(cutoffIndex + 1).filter { it <= 0.0 }
        if (tail.isEmpty()) return 0.0
        return abs(tail.average())
    }

    private fun trimTradeHistory() {
        while (tradeHistory.size > MAX_TRADES) {
            tradeHistory.removeFirst()
        }
    }

    private fun trimEquityCurve() {
        while (equityCurve.size > MAX_EQUITY_POINTS) {
            equityCurve.removeFirst()
        }
    }

    private fun trimDailyReturns() {
        while (dailyReturns.size > MAX_DAILY_RETURNS) {
            val oldestKey = dailyReturns.keys.firstOrNull() ?: break
            dailyReturns.remove(oldestKey)
        }
    }

    private fun gradeStrategy(
        sharpe: Double,
        sortino: Double,
        calmar: Double,
        pf: Double,
        expectancy: Double,
        maxDd: Double,
        sampleAdequacy: String,
    ): String {
        if (sampleAdequacy == "INSUFFICIENT") return "UNRATED"

        return when {
            sharpe >= 2.5 && sortino >= 3.0 && pf >= 1.8 && expectancy >= 1.5 && maxDd <= 15.0 && calmar >= 1.5 -> "A+"
            sharpe >= 2.0 && sortino >= 2.5 && pf >= 1.5 && expectancy >= 1.0 && maxDd <= 20.0 && calmar >= 1.0 -> "A"
            sharpe >= 1.5 && sortino >= 2.0 && pf >= 1.3 && expectancy >= 0.5 && maxDd <= 25.0 -> "B+"
            sharpe >= 1.0 && pf >= 1.1 && expectancy >= 0.0 && maxDd <= 30.0 -> "B"
            sharpe >= 0.5 && pf >= 1.0 && maxDd <= 35.0 -> "C"
            else -> "D"
        }
    }

    private fun sharpeGrade(value: Double): String = when {
        value >= 3.0 -> "⭐⭐⭐"
        value >= 2.0 -> "⭐⭐"
        value >= 1.0 -> "⭐"
        value >= 0.5 -> "○"
        else -> "✗"
    }

    private fun sortinoGrade(value: Double): String = when {
        value >= 4.0 -> "⭐⭐⭐"
        value >= 2.5 -> "⭐⭐"
        value >= 1.5 -> "⭐"
        value >= 0.5 -> "○"
        else -> "✗"
    }

    private fun pfGrade(value: Double): String = when {
        value >= 2.5 -> "⭐⭐⭐"
        value >= 1.8 -> "⭐⭐"
        value >= 1.3 -> "⭐"
        value >= 1.0 -> "○"
        else -> "✗"
    }

    private fun formatStreak(streak: Int): String = when {
        streak > 0 -> "W$streak"
        streak < 0 -> "L${abs(streak)}"
        else -> "FLAT"
    }

    private fun dateKey(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
    }

    private fun parseDateKey(date: String): Long {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

    private fun List<Double>.standardDeviation(): Double {
        if (size < 2) return 0.0
        val mean = average()
        val variance = map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
}