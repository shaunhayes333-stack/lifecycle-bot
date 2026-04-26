package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🎯 KELLY CRITERION POSITION SIZER - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Optimal position sizing based on the Kelly Criterion formula.
 * 
 * KELLY FORMULA:
 * ─────────────────────────────────────────────────────────────────────────────
 *   f* = (p * b - q) / b
 *   
 *   Where:
 *     f* = fraction of bankroll to bet
 *     p  = probability of winning
 *     b  = win/loss ratio (avg win / avg loss)
 *     q  = probability of losing (1 - p)
 * 
 * MODIFICATIONS:
 * ─────────────────────────────────────────────────────────────────────────────
 *   - HALF KELLY: f/2 - More conservative, smoother equity curve
 *   - QUARTER KELLY: f/4 - Very conservative
 *   - VOLATILITY ADJUSTED: Scale by inverse volatility
 *   - CONFIDENCE SCALED: Scale by AI confidence score
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsPositionSizer {
    
    private const val TAG = "🎯PositionSizer"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class SizingMode {
        FIXED_PCT,          // Fixed percentage of balance
        FULL_KELLY,         // Full Kelly Criterion
        HALF_KELLY,         // Half Kelly (recommended)
        QUARTER_KELLY,      // Quarter Kelly (conservative)
        VOLATILITY_SCALED,  // Scale by inverse volatility
        CONFIDENCE_SCALED,  // Scale by AI confidence
        HYBRID,             // Kelly + Volatility + Confidence
    }
    
    data class SizingConfig(
        val mode: SizingMode = SizingMode.HALF_KELLY,
        val maxPositionPct: Double = 25.0,      // Max 25% of balance per position
        val minPositionPct: Double = 1.0,       // Min 1% of balance
        val kellyMultiplier: Double = 0.5,      // 0.5 = Half Kelly
        val volatilityWeight: Double = 0.3,     // How much volatility affects sizing
        val confidenceWeight: Double = 0.3,     // How much AI confidence affects sizing
        val lookbackTrades: Int = 50,           // Trades to consider for win rate
    )
    
    private var config = SizingConfig()
    
    // Historical trade data for calculating win rate and ratios
    data class TradeRecord(
        val pnlPct: Double,
        val isWin: Boolean,
        val market: PerpsMarket,
        val timestamp: Long,
    )
    
    private val tradeHistory = ConcurrentHashMap<PerpsMarket, MutableList<TradeRecord>>()
    private val totalTrades = AtomicInteger(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION SIZING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate optimal position size as percentage of balance
     */
    fun calculatePositionSize(
        market: PerpsMarket,
        balance: Double,
        aiConfidence: Int,
        volatility: Double = 0.0,  // Optional: daily volatility %
    ): PositionSizeResult {
        
        return when (config.mode) {
            SizingMode.FIXED_PCT -> fixedPctSize(balance)
            SizingMode.FULL_KELLY -> kellySize(market, balance, 1.0)
            SizingMode.HALF_KELLY -> kellySize(market, balance, 0.5)
            SizingMode.QUARTER_KELLY -> kellySize(market, balance, 0.25)
            SizingMode.VOLATILITY_SCALED -> volatilityScaledSize(market, balance, volatility)
            SizingMode.CONFIDENCE_SCALED -> confidenceScaledSize(market, balance, aiConfidence)
            SizingMode.HYBRID -> hybridSize(market, balance, aiConfidence, volatility)
        }
    }
    
    data class PositionSizeResult(
        val sizePct: Double,        // Percentage of balance to use
        val sizeSol: Double,        // Actual SOL amount
        val reasoning: String,      // Explanation
        val kellyFraction: Double?, // Raw Kelly value (if calculated)
        val adjustments: List<String>, // What adjustments were made
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SIZING ALGORITHMS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun fixedPctSize(balance: Double): PositionSizeResult {
        val sizePct = 10.0  // Fixed 10%
        return PositionSizeResult(
            sizePct = sizePct.coerceIn(config.minPositionPct, config.maxPositionPct),
            sizeSol = balance * sizePct / 100,
            reasoning = "Fixed 10% position size",
            kellyFraction = null,
            adjustments = emptyList(),
        )
    }
    
    private fun kellySize(market: PerpsMarket, balance: Double, multiplier: Double): PositionSizeResult {
        val stats = getMarketStats(market)
        val adjustments = mutableListOf<String>()
        
        // Need minimum trades for reliable Kelly
        if (stats.totalTrades < 10) {
            adjustments.add("Insufficient data (${stats.totalTrades} trades), using default")
            return PositionSizeResult(
                sizePct = 5.0,
                sizeSol = balance * 0.05,
                reasoning = "Default size - insufficient trade history",
                kellyFraction = null,
                adjustments = adjustments,
            )
        }
        
        // Calculate Kelly fraction
        val p = stats.winRate / 100  // Probability of winning
        val q = 1 - p                 // Probability of losing
        val b = if (stats.avgLoss != 0.0) stats.avgWin / kotlin.math.abs(stats.avgLoss) else 1.0
        
        // Kelly formula: f* = (p*b - q) / b
        val kellyFraction = if (b > 0) (p * b - q) / b else 0.0
        
        // Apply multiplier (Half Kelly, etc.)
        var sizePct = kellyFraction * multiplier * 100
        
        // Handle negative Kelly (don't trade or very small)
        if (kellyFraction <= 0) {
            adjustments.add("Negative Kelly (${kellyFraction.fmt(3)}), edge not favorable")
            sizePct = config.minPositionPct
        }
        
        // Clamp to limits
        val finalSizePct = sizePct.coerceIn(config.minPositionPct, config.maxPositionPct)
        if (sizePct > config.maxPositionPct) {
            adjustments.add("Capped from ${sizePct.fmt(1)}% to ${config.maxPositionPct}%")
        }
        
        val multiplierName = when (multiplier) {
            1.0 -> "Full"
            0.5 -> "Half"
            0.25 -> "Quarter"
            else -> "${(multiplier * 100).toInt()}%"
        }
        
        return PositionSizeResult(
            sizePct = finalSizePct,
            sizeSol = balance * finalSizePct / 100,
            reasoning = "$multiplierName Kelly: WR=${stats.winRate.fmt(1)}% | W/L=${b.fmt(2)} | f*=${kellyFraction.fmt(3)}",
            kellyFraction = kellyFraction,
            adjustments = adjustments,
        )
    }
    
    private fun volatilityScaledSize(market: PerpsMarket, balance: Double, volatility: Double): PositionSizeResult {
        val adjustments = mutableListOf<String>()
        
        // Base size from Kelly
        val kellyResult = kellySize(market, balance, config.kellyMultiplier)
        var sizePct = kellyResult.sizePct
        
        // Scale inversely with volatility
        // Higher volatility = smaller position
        if (volatility > 0) {
            val volatilityFactor = 1.0 / (1 + volatility * config.volatilityWeight)
            sizePct *= volatilityFactor
            adjustments.add("Volatility adjustment: ${volatility.fmt(1)}% vol → ${(volatilityFactor * 100).toInt()}% size")
        }
        
        return PositionSizeResult(
            sizePct = sizePct.coerceIn(config.minPositionPct, config.maxPositionPct),
            sizeSol = balance * sizePct / 100,
            reasoning = "Volatility-scaled: base=${kellyResult.sizePct.fmt(1)}% | vol=${volatility.fmt(1)}%",
            kellyFraction = kellyResult.kellyFraction,
            adjustments = kellyResult.adjustments + adjustments,
        )
    }
    
    private fun confidenceScaledSize(market: PerpsMarket, balance: Double, confidence: Int): PositionSizeResult {
        val adjustments = mutableListOf<String>()
        
        // Base size from Kelly
        val kellyResult = kellySize(market, balance, config.kellyMultiplier)
        var sizePct = kellyResult.sizePct
        
        // Scale by AI confidence (50-100 range typically)
        val confidenceFactor = (confidence.toDouble() / 100).coerceIn(0.3, 1.5)
        sizePct *= confidenceFactor
        adjustments.add("Confidence adjustment: ${confidence}% conf → ${(confidenceFactor * 100).toInt()}% size multiplier")
        
        return PositionSizeResult(
            sizePct = sizePct.coerceIn(config.minPositionPct, config.maxPositionPct),
            sizeSol = balance * sizePct / 100,
            reasoning = "Confidence-scaled: base=${kellyResult.sizePct.fmt(1)}% | conf=${confidence}%",
            kellyFraction = kellyResult.kellyFraction,
            adjustments = kellyResult.adjustments + adjustments,
        )
    }
    
    private fun hybridSize(market: PerpsMarket, balance: Double, confidence: Int, volatility: Double): PositionSizeResult {
        val adjustments = mutableListOf<String>()
        
        // Start with Kelly
        val kellyResult = kellySize(market, balance, config.kellyMultiplier)
        var sizePct = kellyResult.sizePct
        
        // Apply volatility adjustment
        if (volatility > 0) {
            val volatilityFactor = 1.0 / (1 + volatility * config.volatilityWeight)
            sizePct *= volatilityFactor
            adjustments.add("Vol: ${volatility.fmt(1)}% → ${(volatilityFactor * 100).toInt()}%")
        }
        
        // Apply confidence adjustment
        val confidenceFactor = (confidence.toDouble() / 100).coerceIn(0.5, 1.3)
        sizePct *= confidenceFactor
        adjustments.add("Conf: ${confidence}% → ${(confidenceFactor * 100).toInt()}%")
        
        return PositionSizeResult(
            sizePct = sizePct.coerceIn(config.minPositionPct, config.maxPositionPct),
            sizeSol = balance * sizePct / 100,
            reasoning = "Hybrid: Kelly + Vol + Conf",
            kellyFraction = kellyResult.kellyFraction,
            adjustments = kellyResult.adjustments + adjustments,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a completed trade for statistics
     */
    fun recordTrade(market: PerpsMarket, pnlPct: Double) {
        val record = TradeRecord(
            pnlPct = pnlPct,
            isWin = pnlPct >= 1.0,  // V5.9.225: 1% floor
            market = market,
            timestamp = System.currentTimeMillis(),
        )
        
        val history = tradeHistory.getOrPut(market) { mutableListOf() }
        synchronized(history) {
            history.add(record)
            // Keep only recent trades
            while (history.size > config.lookbackTrades * 2) {
                history.removeAt(0)
            }
        }
        
        totalTrades.incrementAndGet()
        
        ErrorLogger.debug(TAG, "🎯 Trade recorded: ${market.symbol} | PnL=${pnlPct.fmt(1)}% | Total=${totalTrades.get()}")
    }
    
    /**
     * Get statistics for a market
     */
    data class MarketStats(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val profitFactor: Double,
        val expectancy: Double,
    )
    
    fun getMarketStats(market: PerpsMarket): MarketStats {
        val history = tradeHistory[market] ?: return MarketStats(0, 0, 0, 50.0, 5.0, -5.0, 1.0, 0.0)
        
        val recent = synchronized(history) {
            history.takeLast(config.lookbackTrades)
        }
        
        if (recent.isEmpty()) {
            return MarketStats(0, 0, 0, 50.0, 5.0, -5.0, 1.0, 0.0)
        }
        
        val wins = recent.filter { it.isWin }
        val losses = recent.filter { !it.isWin }
        
        val winRate = wins.size.toDouble() / recent.size * 100
        val avgWin = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 5.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { it.pnlPct }.average() else -5.0
        
        val totalWinPct = wins.sumOf { it.pnlPct }
        val totalLossPct = kotlin.math.abs(losses.sumOf { it.pnlPct })
        val profitFactor = if (totalLossPct > 0) totalWinPct / totalLossPct else 1.0
        
        val expectancy = (winRate / 100 * avgWin) + ((100 - winRate) / 100 * avgLoss)
        
        return MarketStats(
            totalTrades = recent.size,
            wins = wins.size,
            losses = losses.size,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLoss,
            profitFactor = profitFactor,
            expectancy = expectancy,
        )
    }
    
    /**
     * Get overall stats across all markets
     */
    fun getOverallStats(): MarketStats {
        val allTrades = tradeHistory.values.flatten().takeLast(config.lookbackTrades)
        
        if (allTrades.isEmpty()) {
            return MarketStats(0, 0, 0, 50.0, 5.0, -5.0, 1.0, 0.0)
        }
        
        val wins = allTrades.filter { it.isWin }
        val losses = allTrades.filter { !it.isWin }
        
        val winRate = wins.size.toDouble() / allTrades.size * 100
        val avgWin = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 5.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { it.pnlPct }.average() else -5.0
        
        val totalWinPct = wins.sumOf { it.pnlPct }
        val totalLossPct = kotlin.math.abs(losses.sumOf { it.pnlPct })
        val profitFactor = if (totalLossPct > 0) totalWinPct / totalLossPct else 1.0
        
        val expectancy = (winRate / 100 * avgWin) + ((100 - winRate) / 100 * avgLoss)
        
        return MarketStats(
            totalTrades = allTrades.size,
            wins = wins.size,
            losses = losses.size,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLoss,
            profitFactor = profitFactor,
            expectancy = expectancy,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun setConfig(newConfig: SizingConfig) {
        config = newConfig
        ErrorLogger.info(TAG, "🎯 Position sizing config updated: ${config.mode}")
    }
    
    fun getConfig(): SizingConfig = config
    
    // Helper extension
    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}
