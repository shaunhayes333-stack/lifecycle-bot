package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * FluidLearningAI - Layer 23: Centralized Fluidity Control
 * 
 * PHILOSOPHY: The AI should start LOOSE and tighten as it learns.
 * This layer is the SINGLE SOURCE OF TRUTH for all fluid thresholds.
 * 
 * All components (FDG, Scanners, MarketStructureRouter, CashGenerationAI, etc.)
 * should query this layer for their thresholds instead of using hardcoded values.
 * 
 * LEARNING CURVE:
 *   0 trades   → 0% learned   → Use BOOTSTRAP values (very loose)
 *   125 trades → 25% learned  → Slightly tighter
 *   250 trades → 50% learned  → Moderate thresholds
 *   375 trades → 75% learned  → Getting strict
 *   500 trades → 100% learned → Use MATURE values (full strictness)
 * 
 * Win rate also affects learning speed:
 *   - High win rate (>60%) → Learn faster (+10% bonus)
 *   - Low win rate (<40%)  → Learn slower (-10% penalty)
 */
object FluidLearningAI {
    
    private const val TAG = "FluidLearningAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING PROGRESS TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val sessionTrades = AtomicInteger(0)
    private val sessionWins = AtomicInteger(0)
    private val lastProgressUpdate = AtomicLong(0)
    private var cachedProgress = 0.0
    
    // Trades needed to reach full maturity
    private const val TRADES_FOR_MATURITY = 500
    
    /**
     * Get current learning progress (0.0 = brand new, 1.0 = fully mature).
     * Cached for 10 seconds to avoid expensive recalculations.
     */
    fun getLearningProgress(): Double {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate.get() < 10_000 && cachedProgress > 0) {
            return cachedProgress
        }
        
        // Get historical + session trades
        val historicalStats = try {
            TradeHistoryStore.getStats()
        } catch (_: Exception) { null }
        
        val historicalTrades = historicalStats?.totalTrades ?: 0
        val historicalWinRate = historicalStats?.winRate ?: 50.0
        
        val totalTrades = historicalTrades + sessionTrades.get()
        val sessionWinRate = if (sessionTrades.get() > 0) {
            sessionWins.get().toDouble() / sessionTrades.get() * 100
        } else historicalWinRate
        
        // Blend historical and session win rates
        val blendedWinRate = if (historicalTrades > 0 && sessionTrades.get() > 0) {
            (historicalWinRate * historicalTrades + sessionWinRate * sessionTrades.get()) / 
            (historicalTrades + sessionTrades.get())
        } else if (historicalTrades > 0) {
            historicalWinRate
        } else {
            sessionWinRate
        }
        
        // Base progress from trade count
        var progress = (totalTrades.toDouble() / TRADES_FOR_MATURITY).coerceIn(0.0, 1.0)
        
        // Win rate bonus/penalty
        when {
            blendedWinRate > 60 -> progress = (progress * 1.1).coerceAtMost(1.0)  // +10% faster
            blendedWinRate < 40 -> progress = (progress * 0.9)                     // -10% slower
        }
        
        cachedProgress = progress
        lastProgressUpdate.set(now)
        
        return progress
    }
    
    /**
     * Record a trade for learning progress.
     */
    fun recordTrade(isWin: Boolean) {
        sessionTrades.incrementAndGet()
        if (isWin) sessionWins.incrementAndGet()
        cachedProgress = 0.0  // Force recalculation
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERPOLATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Linear interpolation between bootstrap (loose) and mature (strict) values.
     */
    fun lerp(bootstrap: Double, mature: Double): Double {
        val progress = getLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * Inverse lerp - returns HIGHER value in bootstrap (for bonuses/scores).
     */
    fun lerpInverse(bootstrap: Double, mature: Double): Double {
        val progress = getLearningProgress()
        return mature + (bootstrap - mature) * (1.0 - progress)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIQUIDITY THRESHOLDS (Used by FDG, Scanners, Eligibility)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Watchlist floor: minimum liquidity to even consider a token
    private const val LIQ_WATCHLIST_BOOTSTRAP = 500.0    // $500 in bootstrap
    private const val LIQ_WATCHLIST_MATURE = 2000.0      // $2000 when mature
    
    // Execution floor: minimum liquidity to actually trade
    private const val LIQ_EXECUTION_BOOTSTRAP = 1500.0   // $1500 in bootstrap
    private const val LIQ_EXECUTION_MATURE = 10000.0     // $10000 when mature
    
    // Scanner minimum: for fresh token discovery
    private const val LIQ_SCANNER_BOOTSTRAP = 300.0      // $300 in bootstrap
    private const val LIQ_SCANNER_MATURE = 1500.0        // $1500 when mature
    
    fun getWatchlistFloor(): Double = lerp(LIQ_WATCHLIST_BOOTSTRAP, LIQ_WATCHLIST_MATURE)
    fun getExecutionFloor(): Double = lerp(LIQ_EXECUTION_BOOTSTRAP, LIQ_EXECUTION_MATURE)
    fun getScannerMinLiquidity(): Double = lerp(LIQ_SCANNER_BOOTSTRAP, LIQ_SCANNER_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIDENCE THRESHOLDS (Used by FDG, CashGenerationAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val CONF_BOOTSTRAP = 30.0    // 30% confidence floor at start
    private const val CONF_MATURE = 75.0       // 75% confidence when mature
    
    private const val CONF_PAPER_BOOTSTRAP = 15.0   // Paper mode even looser
    private const val CONF_PAPER_MATURE = 45.0      // Paper mode target
    
    fun getLiveConfidenceFloor(): Double = lerp(CONF_BOOTSTRAP, CONF_MATURE)
    fun getPaperConfidenceFloor(): Double = lerp(CONF_PAPER_BOOTSTRAP, CONF_PAPER_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORE THRESHOLDS (Used by V3 Scoring, CashGenerationAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val SCORE_BOOTSTRAP = 10     // Very low score threshold at start
    private const val SCORE_MATURE = 30        // Higher threshold when mature
    
    fun getMinScoreThreshold(): Int = lerp(SCORE_BOOTSTRAP.toDouble(), SCORE_MATURE.toDouble()).toInt()
    
    // Score bonuses are HIGHER in bootstrap to encourage more mode diversity
    fun getScoreBonus(baseBonus: Double): Double = lerpInverse(baseBonus * 1.8, baseBonus)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADING MODE THRESHOLDS (Used by MarketStructureRouter)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Fresh token age threshold
    private const val FRESH_AGE_BOOTSTRAP = 120.0  // 2 hours = fresh in bootstrap
    private const val FRESH_AGE_MATURE = 30.0      // 30 min = fresh when mature
    
    // History candles required
    private const val MIN_HISTORY_BOOTSTRAP = 5
    private const val MIN_HISTORY_MATURE = 30
    
    // Breakout detection threshold (% of recent high)
    private const val BREAKOUT_BOOTSTRAP = 0.75    // 75% of high in bootstrap
    private const val BREAKOUT_MATURE = 0.92       // 92% of high when mature
    
    fun getFreshTokenAgeMinutes(): Double = lerp(FRESH_AGE_BOOTSTRAP, FRESH_AGE_MATURE)
    fun getMinHistoryCandles(): Int = lerp(MIN_HISTORY_BOOTSTRAP.toDouble(), MIN_HISTORY_MATURE.toDouble()).toInt()
    fun getBreakoutThreshold(): Double = lerp(BREAKOUT_BOOTSTRAP, BREAKOUT_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TREASURY MODE THRESHOLDS (Used by CashGenerationAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val TREASURY_CONF_BOOTSTRAP = 30
    private const val TREASURY_CONF_MATURE = 80
    
    private const val TREASURY_LIQ_BOOTSTRAP = 3000.0
    private const val TREASURY_LIQ_MATURE = 15000.0
    
    private const val TREASURY_TOP_HOLDER_BOOTSTRAP = 40.0  // Allow 40% top holder
    private const val TREASURY_TOP_HOLDER_MATURE = 12.0     // Strict 12% when mature
    
    private const val TREASURY_BUY_PRESSURE_BOOTSTRAP = 35.0
    private const val TREASURY_BUY_PRESSURE_MATURE = 58.0
    
    fun getTreasuryConfidenceThreshold(): Int = lerp(TREASURY_CONF_BOOTSTRAP.toDouble(), TREASURY_CONF_MATURE.toDouble()).toInt()
    fun getTreasuryMinLiquidity(): Double = lerp(TREASURY_LIQ_BOOTSTRAP, TREASURY_LIQ_MATURE)
    fun getTreasuryMaxTopHolder(): Double = lerp(TREASURY_TOP_HOLDER_BOOTSTRAP, TREASURY_TOP_HOLDER_MATURE)
    fun getTreasuryMinBuyPressure(): Double = lerp(TREASURY_BUY_PRESSURE_BOOTSTRAP, TREASURY_BUY_PRESSURE_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RUG FILTER THRESHOLDS (Used by HardRugPreFilter)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val RUG_LIQ_FRESH_BOOTSTRAP = 300.0    // $300 for fresh tokens
    private const val RUG_LIQ_FRESH_MATURE = 1500.0
    
    private const val RUG_LIQ_YOUNG_BOOTSTRAP = 500.0    // $500 for 5-30min tokens
    private const val RUG_LIQ_YOUNG_MATURE = 2500.0
    
    private const val RUG_LIQ_ESTABLISHED_BOOTSTRAP = 800.0
    private const val RUG_LIQ_ESTABLISHED_MATURE = 3500.0
    
    fun getRugFilterLiqFresh(): Double = lerp(RUG_LIQ_FRESH_BOOTSTRAP, RUG_LIQ_FRESH_MATURE)
    fun getRugFilterLiqYoung(): Double = lerp(RUG_LIQ_YOUNG_BOOTSTRAP, RUG_LIQ_YOUNG_MATURE)
    fun getRugFilterLiqEstablished(): Double = lerp(RUG_LIQ_ESTABLISHED_BOOTSTRAP, RUG_LIQ_ESTABLISHED_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & DEBUGGING
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class FluidState(
        val learningProgressPct: Int,
        val totalTrades: Int,
        val sessionTrades: Int,
        val sessionWinRate: Double,
        val isBootstrap: Boolean,
        val watchlistFloor: Int,
        val executionFloor: Int,
        val scannerMinLiq: Int,
        val confidenceFloor: Int,
        val scoreThreshold: Int,
        val freshAgeMinutes: Int,
    ) {
        fun summary(): String = buildString {
            append("🧠 FLUID AI: ${learningProgressPct}% learned ($totalTrades trades)")
            if (isBootstrap) append(" [BOOTSTRAP]")
            append("\n  Liq: watch≥\$$watchlistFloor exec≥\$$executionFloor scan≥\$$scannerMinLiq")
            append("\n  Conf≥${confidenceFloor}% Score≥$scoreThreshold Fresh≤${freshAgeMinutes}min")
        }
    }
    
    fun getState(): FluidState {
        val progress = getLearningProgress()
        val historicalStats = try { TradeHistoryStore.getStats() } catch (_: Exception) { null }
        val historicalTrades = historicalStats?.totalTrades ?: 0
        val totalTrades = historicalTrades + sessionTrades.get()
        val sessionWinRate = if (sessionTrades.get() > 0) {
            sessionWins.get().toDouble() / sessionTrades.get() * 100
        } else 0.0
        
        return FluidState(
            learningProgressPct = (progress * 100).toInt(),
            totalTrades = totalTrades,
            sessionTrades = sessionTrades.get(),
            sessionWinRate = sessionWinRate,
            isBootstrap = progress < 0.1,
            watchlistFloor = getWatchlistFloor().toInt(),
            executionFloor = getExecutionFloor().toInt(),
            scannerMinLiq = getScannerMinLiquidity().toInt(),
            confidenceFloor = getLiveConfidenceFloor().toInt(),
            scoreThreshold = getMinScoreThreshold(),
            freshAgeMinutes = getFreshTokenAgeMinutes().toInt(),
        )
    }
    
    fun logState() {
        val state = getState()
        ErrorLogger.info(TAG, state.summary())
    }
}
