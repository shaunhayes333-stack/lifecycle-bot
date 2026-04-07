package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.BotConfig

/**
 * FluidLearning - Makes Paper & Shadow Mode fully functional learning environments
 * 
 * PROBLEM: Paper mode traditionally uses "unlimited funds" which doesn't teach:
 * - Realistic position sizing
 * - Scaling into positions
 * - Treasury management
 * - Drawdown protection
 * - Top-up logic
 * 
 * SOLUTION: Track a simulated balance that:
 * - Starts at a configurable amount (default: 5 SOL)
 * - Increases/decreases based on paper trade P&L
 * - Enables ALL profit optimization tools
 * - Teaches the AI realistic constraints
 * 
 * This makes paper mode a TRUE learning environment, not a sandbox.
 */
object FluidLearning {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SIMULATED BALANCE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile private var simulatedBalanceSol: Double = 5.0
    @Volatile private var simulatedPeakSol: Double = 5.0
    @Volatile private var totalPaperPnlSol: Double = 0.0
    @Volatile private var paperTradeCount: Int = 0
    @Volatile private var paperWinCount: Int = 0
    @Volatile private var paperLossCount: Int = 0
    
    // Simulated open positions for exposure tracking
    private val simulatedExposure = mutableMapOf<String, Double>()  // mint → SOL exposed
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE IMPACT SIMULATION
    // In live trading, YOUR trades move the market. Paper trades don't.
    // This simulates the price impact your trades WOULD have had.
    // ═══════════════════════════════════════════════════════════════════════════
    private val priceImpactOffsets = mutableMapOf<String, Double>()  // mint → cumulative impact %
    
    /**
     * Get the simulated price impact for a token.
     * Returns a multiplier (e.g., 1.02 means price is 2% higher due to your buys)
     */
    fun getPriceImpact(mint: String): Double {
        return 1.0 + (priceImpactOffsets[mint] ?: 0.0) / 100.0
    }
    
    /**
     * Record price impact from a paper trade.
     * BUY: Pushes price UP (positive impact)
     * SELL: Pushes price DOWN (negative impact)
     */
    fun recordPriceImpact(mint: String, solAmount: Double, liquidityUsd: Double, isBuy: Boolean) {
        // Calculate impact based on trade size relative to liquidity
        // Larger trades relative to liquidity = more impact
        val solPrice = 140.0  // Approximate, could be passed in
        val tradeUsd = solAmount * solPrice
        
        // Impact formula: tradeSize / liquidity * impactMultiplier
        // Small trades in deep liquidity = minimal impact
        // Large trades in thin liquidity = significant impact
        val impactPct = when {
            liquidityUsd < 5000 -> (tradeUsd / liquidityUsd) * 15.0   // Very thin = 15x impact
            liquidityUsd < 20000 -> (tradeUsd / liquidityUsd) * 8.0   // Thin = 8x impact
            liquidityUsd < 50000 -> (tradeUsd / liquidityUsd) * 4.0   // Medium = 4x impact
            liquidityUsd < 100000 -> (tradeUsd / liquidityUsd) * 2.0  // Good = 2x impact
            else -> (tradeUsd / liquidityUsd) * 1.0                    // Deep = 1x impact
        }.coerceAtMost(10.0)  // Cap at 10% impact per trade
        
        val currentImpact = priceImpactOffsets[mint] ?: 0.0
        val newImpact = if (isBuy) {
            // Buying pushes price UP
            currentImpact + impactPct
        } else {
            // Selling pushes price DOWN
            currentImpact - impactPct
        }
        
        // Impact decays over time (mean reversion) - cap total impact
        priceImpactOffsets[mint] = newImpact.coerceIn(-20.0, 20.0)
        
        ErrorLogger.debug("FluidLearning", 
            "📊 Price impact: ${if(isBuy) "BUY" else "SELL"} ${String.format("%.4f", solAmount)} SOL " +
            "| liq=$${liquidityUsd.toLong()} | impact=${String.format("%+.2f", impactPct)}% " +
            "| cumulative=${String.format("%+.2f", newImpact)}%")
    }
    
    /**
     * Clear price impact for a token (e.g., when position fully closed)
     */
    fun clearPriceImpact(mint: String) {
        priceImpactOffsets.remove(mint)
    }
    
    /**
     * Decay all price impacts (call periodically to simulate market absorption)
     */
    fun decayPriceImpacts(decayPct: Double = 10.0) {
        priceImpactOffsets.keys.toList().forEach { mint ->
            val current = priceImpactOffsets[mint] ?: return@forEach
            val decayed = current * (1.0 - decayPct / 100.0)
            if (kotlin.math.abs(decayed) < 0.1) {
                priceImpactOffsets.remove(mint)
            } else {
                priceImpactOffsets[mint] = decayed
            }
        }
    }
    
    private var prefs: SharedPreferences? = null
    
    /**
     * Initialize with context for persistence
     */
    fun init(ctx: Context, startingBalance: Double = 5.0) {
        prefs = ctx.getSharedPreferences("fluid_learning", Context.MODE_PRIVATE)
        loadState()
        
        // If first run, set starting balance
        if (simulatedBalanceSol <= 0) {
            simulatedBalanceSol = startingBalance
            simulatedPeakSol = startingBalance
            saveState()
        }
        
        ErrorLogger.info("FluidLearning", "📊 INIT: balance=${simulatedBalanceSol.fmt(4)} SOL | peak=${simulatedPeakSol.fmt(4)} | trades=$paperTradeCount | winRate=${getWinRate().toInt()}%")
    }
    
    /**
     * Get current simulated balance for paper trading
     * This is what SmartSizer should use in paper mode for realistic sizing
     */
    fun getSimulatedBalance(): Double = simulatedBalanceSol
    
    /**
     * Alias for getSimulatedBalance() for backward compatibility
     */
    fun getPaperBalance(): Double = simulatedBalanceSol
    
    /**
     * Get simulated peak (for drawdown calculations)
     */
    fun getSimulatedPeak(): Double = simulatedPeakSol
    
    /**
     * Get current simulated exposure (total SOL in open paper positions)
     */
    fun getSimulatedExposure(): Double = simulatedExposure.values.sum()
    
    /**
     * Get win rate for paper trades
     */
    fun getWinRate(): Double {
        val total = paperWinCount + paperLossCount
        return if (total > 0) (paperWinCount.toDouble() / total) * 100 else 50.0
    }
    
    /**
     * Get total trade count
     */
    fun getTradeCount(): Int = paperTradeCount
    
    /**
     * Get total P&L in SOL
     */
    fun getTotalPnl(): Double = totalPaperPnlSol
    
    /**
     * Get recovery ratio (current balance vs peak)
     */
    fun getRecoveryRatio(): Double {
        return if (simulatedPeakSol > 0) {
            simulatedBalanceSol / simulatedPeakSol
        } else 1.0
    }
    
    /**
     * Get performance context for SmartSizer
     */
    fun getPerformanceContext(): SmartSizer.PerformanceContext {
        return SmartSizer.PerformanceContext(
            recentWinRate = getWinRate(),
            winStreak = 0,  // TODO: Track streaks
            lossStreak = 0,
            sessionPeakSol = simulatedPeakSol,
            totalTrades = paperTradeCount,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PAPER TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a paper BUY - deduct from simulated balance, track exposure
     */
    fun recordPaperBuy(mint: String, solAmount: Double) {
        simulatedExposure[mint] = (simulatedExposure[mint] ?: 0.0) + solAmount
        // Don't deduct from balance yet - it's "in position"
        ErrorLogger.debug("FluidLearning", "📥 PAPER BUY: $mint | ${solAmount.fmt(4)} SOL | exposure=${getSimulatedExposure().fmt(4)}")
    }
    
    /**
     * Record a paper SELL - return to balance with P&L
     */
    fun recordPaperSell(mint: String, originalSol: Double, pnlSol: Double) {
        val returnedSol = originalSol + pnlSol
        
        // Update balance
        simulatedBalanceSol += pnlSol  // Add the P&L (could be negative)
        totalPaperPnlSol += pnlSol
        paperTradeCount++
        
        // V5.6.16: Only count meaningful trades (not scratches)
        // Scratches are trades between -2% and +2% - they're essentially break-even
        val pnlPct = if (originalSol > 0) (pnlSol / originalSol) * 100 else 0.0
        val isScratch = pnlPct >= -2.0 && pnlPct <= 2.0
        
        if (!isScratch) {
            if (pnlSol > 0) {
                paperWinCount++
                // Update peak if new high
                if (simulatedBalanceSol > simulatedPeakSol) {
                    simulatedPeakSol = simulatedBalanceSol
                }
            } else {
                paperLossCount++
            }
        } else {
            // Update peak for scratches too if positive
            if (simulatedBalanceSol > simulatedPeakSol) {
                simulatedPeakSol = simulatedBalanceSol
            }
        }
        
        // Clear exposure for this position
        simulatedExposure.remove(mint)
        
        val emoji = when {
            isScratch -> "➖"
            pnlSol > 0 -> "✅"
            else -> "❌"
        }
        val scratchTag = if (isScratch) " [SCRATCH]" else ""
        ErrorLogger.info("FluidLearning", "$emoji PAPER SELL: $mint | pnl=${pnlSol.fmt(4)} SOL | balance=${simulatedBalanceSol.fmt(4)} | winRate=${getWinRate().toInt()}%$scratchTag")
        
        saveState()
    }
    
    /**
     * Record top-up in paper mode
     */
    fun recordPaperTopUp(mint: String, additionalSol: Double) {
        simulatedExposure[mint] = (simulatedExposure[mint] ?: 0.0) + additionalSol
        ErrorLogger.debug("FluidLearning", "📈 PAPER TOP-UP: $mint | +${additionalSol.fmt(4)} SOL | total exposure=${getSimulatedExposure().fmt(4)}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING PHASE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class LearningPhase {
        BOOTSTRAP,    // 0-30 trades: Very loose, maximum exploration
        DEVELOPING,   // 31-100 trades: Starting to learn patterns
        REFINING,     // 101-300 trades: Tightening based on data
        MATURE,       // 300+ trades: Full constraints applied
    }
    
    fun getLearningPhase(): LearningPhase {
        return when {
            paperTradeCount < 30 -> LearningPhase.BOOTSTRAP
            paperTradeCount < 100 -> LearningPhase.DEVELOPING
            paperTradeCount < 300 -> LearningPhase.REFINING
            else -> LearningPhase.MATURE
        }
    }
    
    /**
     * Get scaling multiplier based on learning phase
     * Earlier phases scale more aggressively to learn faster
     */
    fun getLearningScaleMultiplier(): Double {
        return when (getLearningPhase()) {
            LearningPhase.BOOTSTRAP -> 1.5   // 50% larger positions to learn faster
            LearningPhase.DEVELOPING -> 1.25 // 25% larger
            LearningPhase.REFINING -> 1.1    // 10% larger
            LearningPhase.MATURE -> 1.0      // Normal sizing
        }
    }
    
    /**
     * Should we enable all features in paper mode?
     * Yes - we want to learn top-ups, profit locks, scaling, etc.
     */
    fun shouldEnableAllFeatures(cfg: BotConfig): Boolean {
        return cfg.fluidLearningEnabled && cfg.paperMode
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun saveState() {
        prefs?.edit()?.apply {
            putFloat("simulated_balance", simulatedBalanceSol.toFloat())
            putFloat("simulated_peak", simulatedPeakSol.toFloat())
            putFloat("total_pnl", totalPaperPnlSol.toFloat())
            putInt("trade_count", paperTradeCount)
            putInt("win_count", paperWinCount)
            putInt("loss_count", paperLossCount)
            apply()
        }
    }
    
    private fun loadState() {
        prefs?.let { p ->
            simulatedBalanceSol = p.getFloat("simulated_balance", 5.0f).toDouble()
            simulatedPeakSol = p.getFloat("simulated_peak", 5.0f).toDouble()
            totalPaperPnlSol = p.getFloat("total_pnl", 0.0f).toDouble()
            paperTradeCount = p.getInt("trade_count", 0)
            paperWinCount = p.getInt("win_count", 0)
            paperLossCount = p.getInt("loss_count", 0)
        }
    }
    
    /**
     * Reset simulated balance to starting amount
     */
    fun reset(startingBalance: Double = 5.0) {
        simulatedBalanceSol = startingBalance
        simulatedPeakSol = startingBalance
        totalPaperPnlSol = 0.0
        paperTradeCount = 0
        paperWinCount = 0
        paperLossCount = 0
        simulatedExposure.clear()
        saveState()
        ErrorLogger.info("FluidLearning", "🔄 RESET: Starting fresh with ${startingBalance.fmt(4)} SOL")
    }
    
    /**
     * Get summary for logging
     */
    fun getSummary(): String {
        val phase = getLearningPhase()
        val drawdown = if (simulatedPeakSol > 0) {
            ((simulatedPeakSol - simulatedBalanceSol) / simulatedPeakSol) * 100
        } else 0.0
        
        return "FluidLearning[$phase]: ${simulatedBalanceSol.fmt(2)} SOL | " +
               "peak=${simulatedPeakSol.fmt(2)} | dd=${drawdown.toInt()}% | " +
               "trades=$paperTradeCount | win=${getWinRate().toInt()}%"
    }
    
    private fun Double.fmt(decimals: Int = 4): String = "%.${decimals}f".format(this)
}
