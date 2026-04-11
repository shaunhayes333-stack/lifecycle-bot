package com.lifecyclebot.engine.quant

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v4.meta.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ===============================================================================
 * QUANT MIND V2 — Unified Intelligence Overlay
 * ===============================================================================
 *
 * Upgrades the base QuantMetrics with V4 meta-intelligence integration.
 * This is the brain that ties QuantMetrics + V4 + CrossTalk into one system.
 *
 * FEATURES:
 *   - Regime-aware risk metrics (Sharpe/Sortino per regime)
 *   - Strategy-weighted portfolio analytics
 *   - Adaptive risk thresholds from LeverageSurvivalAI
 *   - Cross-market correlation stress from PortfolioHeatAI
 *   - Execution quality tracking from ExecutionPathAI
 *   - Narrative-adjusted expectancy from NarrativeFlowAI
 *   - Lead-lag alpha attribution from CrossAssetLeadLagAI
 *   - Dynamic Kelly criterion with fragility adjustment
 *   - Multi-timeframe momentum decomposition
 *
 * ===============================================================================
 */
object QuantMindV2 {

    private const val TAG = "QuantMindV2"

    // Current quant state
    private val currentState = AtomicReference<QuantMindState?>(null)

    // Per-regime performance tracking
    private val regimePerformance = ConcurrentHashMap<GlobalRiskMode, RegimePerformance>()

    // Per-strategy quant metrics
    private val strategyMetrics = ConcurrentHashMap<String, StrategyQuantProfile>()

    // Momentum decomposition
    private val momentumLayers = ConcurrentHashMap<String, MomentumDecomposition>()

    // ═══════════════════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════════════════

    data class QuantMindState(
        val overallGrade: String,              // S, A+, A, B+, B, C, D, F
        val adaptiveSharpe: Double,            // Regime-adjusted Sharpe
        val adaptiveSortino: Double,           // Regime-adjusted Sortino
        val kellyFraction: Double,             // Dynamic Kelly with fragility
        val optimalLeverage: Double,           // Kelly-derived optimal leverage
        val riskBudgetUsed: Double,            // 0-1 how much risk budget is consumed
        val regimeFitScore: Double,            // How well current strategy mix fits regime
        val executionAlpha: Double,            // Alpha/drag from execution quality
        val narrativeAlpha: Double,            // Alpha from narrative timing
        val leadLagAlpha: Double,              // Alpha from cross-asset rotation
        val correlationStress: Double,         // Portfolio correlation stress
        val momentumState: String,             // ACCELERATING, STEADY, DECELERATING, REVERSING
        val edgeDecay: Double,                 // 0-1 how much edge is decaying
        val recommendation: String,            // Human-readable action
        val timestamp: Long = System.currentTimeMillis()
    )

    data class RegimePerformance(
        val regime: GlobalRiskMode,
        var trades: Int = 0,
        var wins: Int = 0,
        var totalPnlPct: Double = 0.0,
        var totalPnlSol: Double = 0.0,
        var bestTradePct: Double = 0.0,
        var worstTradePct: Double = 0.0,
        val pnlHistory: MutableList<Double> = mutableListOf()
    ) {
        val winRate: Double get() = if (trades > 0) wins.toDouble() / trades else 0.0
        val avgPnlPct: Double get() = if (trades > 0) totalPnlPct / trades else 0.0
        val sharpe: Double get() {
            if (pnlHistory.size < 5) return 0.0
            val mean = pnlHistory.average()
            val std = pnlHistory.standardDeviation()
            return if (std > 0) mean / std else 0.0
        }
    }

    data class StrategyQuantProfile(
        val strategy: String,
        var trades: Int = 0,
        var expectancy: Double = 0.0,
        var profitFactor: Double = 1.0,
        var avgHoldMinutes: Double = 0.0,
        var bestRegime: GlobalRiskMode = GlobalRiskMode.RISK_ON,
        var worstRegime: GlobalRiskMode = GlobalRiskMode.CHAOTIC,
        var kellyFraction: Double = 0.0,
        var edgeScore: Double = 0.0
    )

    data class MomentumDecomposition(
        val symbol: String,
        val shortTermMom: Double,      // 5-period
        val mediumTermMom: Double,     // 20-period
        val longTermMom: Double,       // 60-period
        val acceleration: Double,       // Rate of change of momentum
        val state: String              // ACCELERATING, STEADY, DECELERATING, REVERSING
    )

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD TRADE — Feed from TradeLessonRecorder
    // ═══════════════════════════════════════════════════════════════════════

    fun recordTrade(lesson: TradeLesson) {
        // 1. Update regime performance
        val regimePerf = regimePerformance.getOrPut(lesson.entryRegime) {
            RegimePerformance(lesson.entryRegime)
        }
        regimePerf.trades++
        if (lesson.outcomePct > 0) regimePerf.wins++
        regimePerf.totalPnlPct += lesson.outcomePct
        regimePerf.bestTradePct = maxOf(regimePerf.bestTradePct, lesson.outcomePct)
        regimePerf.worstTradePct = minOf(regimePerf.worstTradePct, lesson.outcomePct)
        synchronized(regimePerf.pnlHistory) {
            regimePerf.pnlHistory.add(lesson.outcomePct)
            if (regimePerf.pnlHistory.size > 200) regimePerf.pnlHistory.removeAt(0)
        }

        // 2. Update strategy quant profile
        val stratProfile = strategyMetrics.getOrPut(lesson.strategy) {
            StrategyQuantProfile(lesson.strategy)
        }
        stratProfile.trades++
        val wins = strategyMetrics.values.filter { it.strategy == lesson.strategy }.sumOf {
            it.trades.coerceAtLeast(1)
        }
        stratProfile.expectancy = regimePerf.avgPnlPct
        stratProfile.avgHoldMinutes = (stratProfile.avgHoldMinutes * (stratProfile.trades - 1) + lesson.holdSec / 60.0) / stratProfile.trades

        // 3. Calculate Kelly fraction for this strategy
        val wr = regimePerf.winRate
        val avgWin = if (regimePerf.wins > 0) regimePerf.totalPnlPct.coerceAtLeast(0.0) / regimePerf.wins.coerceAtLeast(1) else 1.0
        val avgLoss = if (regimePerf.trades - regimePerf.wins > 0) {
            abs(regimePerf.pnlHistory.filter { it < 0 }.average().takeIf { !it.isNaN() } ?: 1.0)
        } else 1.0
        val winLossRatio = if (avgLoss > 0) avgWin / avgLoss else 1.0
        val kelly = if (winLossRatio > 0) wr - (1 - wr) / winLossRatio else 0.0
        stratProfile.kellyFraction = kelly.coerceIn(0.0, 0.5) // Half-Kelly max

        // 4. Recalculate overall state
        recalculateState()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECALCULATE — Full quant mind state update
    // ═══════════════════════════════════════════════════════════════════════

    private fun recalculateState() {
        val snapshot = CrossTalkFusionEngine.getSnapshot()
        val currentRegime = snapshot?.globalRiskMode ?: GlobalRiskMode.RISK_ON
        val currentRegimePerf = regimePerformance[currentRegime]

        // 1. Adaptive Sharpe (weighted by regime)
        val adaptiveSharpe = currentRegimePerf?.sharpe ?: 0.0

        // 2. Adaptive Sortino
        val negReturns = currentRegimePerf?.pnlHistory?.filter { it < 0 } ?: emptyList()
        val downsideDev = if (negReturns.size >= 3) negReturns.standardDeviation() else 1.0
        val adaptiveSortino = if (downsideDev > 0) {
            (currentRegimePerf?.avgPnlPct ?: 0.0) / downsideDev
        } else 0.0

        // 3. Dynamic Kelly with fragility adjustment
        val avgKelly = strategyMetrics.values.map { it.kellyFraction }.average().takeIf { !it.isNaN() } ?: 0.0
        val fragilityPenalty = snapshot?.fragilityMap?.values?.maxOrNull() ?: 0.0
        val kellyFraction = (avgKelly * (1.0 - fragilityPenalty * 0.5)).coerceIn(0.0, 0.25)

        // 4. Optimal leverage from Kelly
        val optimalLeverage = (kellyFraction * 10).coerceIn(0.0,
            snapshot?.leverageCap ?: 5.0)

        // 5. Risk budget used
        val portfolioHeat = snapshot?.portfolioHeat ?: 0.0
        val riskBudgetUsed = portfolioHeat

        // 6. Regime fit score
        val regimeFitScore = if (currentRegimePerf != null && currentRegimePerf.trades >= 10) {
            currentRegimePerf.winRate * 0.5 + (1.0 - abs(currentRegimePerf.avgPnlPct).coerceAtMost(5.0) / 5.0) * 0.5
        } else 0.5

        // 7. Execution alpha
        val executionAlpha = try {
            val execStats = ExecutionPathAI.getVenueStats()
            val avgSlippage = execStats.values.map { it.avgSlippageBps }.average().takeIf { !it.isNaN() } ?: 30.0
            -(avgSlippage / 10000.0) // Negative = drag
        } catch (_: Exception) { 0.0 }

        // 8. Narrative alpha
        val narrativeAlpha = try {
            val hotNarratives = NarrativeFlowAI.getHotNarratives()
            hotNarratives.sumOf { it.narrativeHeat * 0.01 }.coerceAtMost(0.05)
        } catch (_: Exception) { 0.0 }

        // 9. Lead-lag alpha
        val leadLagAlpha = try {
            val links = CrossAssetLeadLagAI.getActiveLinks()
            links.sumOf { it.rotationProbability * 0.02 }.coerceAtMost(0.05)
        } catch (_: Exception) { 0.0 }

        // 10. Correlation stress
        val correlationStress = try {
            PortfolioHeatAI.getReport()?.correlationStress ?: 0.0
        } catch (_: Exception) { 0.0 }

        // 11. Momentum state
        val momentumState = when {
            adaptiveSharpe > 1.5 && adaptiveSortino > 2.0 -> "ACCELERATING"
            adaptiveSharpe > 0.5 -> "STEADY"
            adaptiveSharpe > -0.5 -> "DECELERATING"
            else -> "REVERSING"
        }

        // 12. Edge decay
        val recentTrades = regimePerformance.values.sumOf { it.trades }
        val edgeDecay = if (recentTrades > 100) {
            val recent50 = regimePerformance.values.flatMap {
                synchronized(it.pnlHistory) { it.pnlHistory.takeLast(25) }
            }
            val older50 = regimePerformance.values.flatMap {
                synchronized(it.pnlHistory) { it.pnlHistory.take(25) }
            }
            val recentAvg = recent50.average().takeIf { !it.isNaN() } ?: 0.0
            val olderAvg = older50.average().takeIf { !it.isNaN() } ?: 0.0
            if (olderAvg > 0 && recentAvg < olderAvg) {
                ((olderAvg - recentAvg) / olderAvg).coerceIn(0.0, 1.0)
            } else 0.0
        } else 0.0

        // 13. Overall grade
        val compositeScore = (adaptiveSharpe * 0.25 +
            adaptiveSortino * 0.15 +
            kellyFraction * 10 * 0.15 +
            regimeFitScore * 0.15 +
            (1.0 - correlationStress) * 0.10 +
            (1.0 - edgeDecay) * 0.10 +
            narrativeAlpha * 20 * 0.05 +
            leadLagAlpha * 20 * 0.05)

        val overallGrade = when {
            compositeScore >= 2.0 -> "S"
            compositeScore >= 1.5 -> "A+"
            compositeScore >= 1.2 -> "A"
            compositeScore >= 0.9 -> "B+"
            compositeScore >= 0.6 -> "B"
            compositeScore >= 0.3 -> "C"
            compositeScore >= 0.0 -> "D"
            else -> "F"
        }

        // 14. Recommendation
        val recommendation = when {
            overallGrade == "S" || overallGrade == "A+" -> "Full allocation. System performing at peak. Maintain current strategy mix."
            overallGrade == "A" || overallGrade == "B+" -> "Standard allocation. Good regime fit. Watch for edge decay."
            overallGrade == "B" -> "Reduced allocation. Consider tightening entry filters."
            overallGrade == "C" -> "Minimum allocation. Review strategy trust scores. Possible regime mismatch."
            else -> "HALT new entries. System underperforming. Wait for regime shift or edge recovery."
        }

        val state = QuantMindState(
            overallGrade = overallGrade,
            adaptiveSharpe = adaptiveSharpe,
            adaptiveSortino = adaptiveSortino,
            kellyFraction = kellyFraction,
            optimalLeverage = optimalLeverage,
            riskBudgetUsed = riskBudgetUsed,
            regimeFitScore = regimeFitScore,
            executionAlpha = executionAlpha,
            narrativeAlpha = narrativeAlpha,
            leadLagAlpha = leadLagAlpha,
            correlationStress = correlationStress,
            momentumState = momentumState,
            edgeDecay = edgeDecay,
            recommendation = recommendation
        )

        currentState.set(state)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MOMENTUM DECOMPOSITION
    // ═══════════════════════════════════════════════════════════════════════

    fun updateMomentum(symbol: String, returns: List<Double>) {
        if (returns.size < 60) return

        val shortTerm = returns.takeLast(5).average()
        val mediumTerm = returns.takeLast(20).average()
        val longTerm = returns.takeLast(60).average()
        val acceleration = shortTerm - mediumTerm

        val state = when {
            shortTerm > mediumTerm && mediumTerm > longTerm && acceleration > 0 -> "ACCELERATING"
            shortTerm > 0 && mediumTerm > 0 -> "STEADY"
            shortTerm < mediumTerm && acceleration < 0 -> "DECELERATING"
            else -> "REVERSING"
        }

        momentumLayers[symbol] = MomentumDecomposition(symbol, shortTerm, mediumTerm, longTerm, acceleration, state)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getState(): QuantMindState? = currentState.get()
    fun getOverallGrade(): String = currentState.get()?.overallGrade ?: "UNRATED"
    fun getKellyFraction(): Double = currentState.get()?.kellyFraction ?: 0.1
    fun getOptimalLeverage(): Double = currentState.get()?.optimalLeverage ?: 1.0
    fun getMomentumState(): String = currentState.get()?.momentumState ?: "UNKNOWN"
    fun getEdgeDecay(): Double = currentState.get()?.edgeDecay ?: 0.0
    fun getRecommendation(): String = currentState.get()?.recommendation ?: "Insufficient data"

    fun getRegimePerformance(): Map<GlobalRiskMode, RegimePerformance> = regimePerformance.toMap()
    fun getStrategyMetrics(): Map<String, StrategyQuantProfile> = strategyMetrics.toMap()
    fun getMomentumDecomposition(symbol: String): MomentumDecomposition? = momentumLayers[symbol]

    fun getRegimeSharpe(regime: GlobalRiskMode): Double = regimePerformance[regime]?.sharpe ?: 0.0

    /**
     * Get the quant-mind-adjusted size multiplier.
     * Combines Kelly fraction, regime fit, fragility, and edge decay.
     */
    fun getSizeMultiplier(): Double {
        val state = currentState.get() ?: return 0.5
        val base = state.kellyFraction * 4  // Scale Kelly to 0-1 range
        val regimeAdj = state.regimeFitScore
        val edgeAdj = 1.0 - state.edgeDecay
        val fragAdj = 1.0 - state.riskBudgetUsed * 0.5
        return (base * regimeAdj * edgeAdj * fragAdj).coerceIn(0.1, 1.0)
    }

    fun getReport(): String {
        val state = currentState.get() ?: return "QuantMind V2: No data yet"
        return buildString {
            appendLine("QUANT MIND V2 — Grade: ${state.overallGrade}")
            appendLine("Sharpe: ${"%.2f".format(state.adaptiveSharpe)} | Sortino: ${"%.2f".format(state.adaptiveSortino)}")
            appendLine("Kelly: ${"%.1f".format(state.kellyFraction * 100)}% | Opt Lev: ${"%.1f".format(state.optimalLeverage)}x")
            appendLine("Regime Fit: ${"%.0f".format(state.regimeFitScore * 100)}% | Momentum: ${state.momentumState}")
            appendLine("Edge Decay: ${"%.0f".format(state.edgeDecay * 100)}% | Corr Stress: ${"%.0f".format(state.correlationStress * 100)}%")
            appendLine("Exec Alpha: ${"%.3f".format(state.executionAlpha)} | Narr Alpha: ${"%.3f".format(state.narrativeAlpha)}")
            appendLine(state.recommendation)
        }
    }

    fun clear() {
        currentState.set(null)
        regimePerformance.clear()
        strategyMetrics.clear()
        momentumLayers.clear()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun List<Double>.standardDeviation(): Double {
        if (size < 2) return 0.0
        val mean = average()
        val variance = map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
}
