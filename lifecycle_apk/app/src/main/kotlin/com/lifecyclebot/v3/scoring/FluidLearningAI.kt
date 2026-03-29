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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BEHAVIOR MODIFIER (From BehaviorAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Behavior modifier: -1.0 to +1.0
    // Negative = tighten thresholds (bad behavior like loss streaks)
    // Positive = loosen thresholds (good behavior like win streaks, 10x runs)
    @Volatile
    private var behaviorModifier = 0.0
    
    /**
     * Apply a behavior modifier from BehaviorAI.
     * This affects all fluid thresholds.
     * 
     * @param modifier -1.0 (very bad behavior) to +1.0 (excellent behavior)
     */
    fun applyBehaviorModifier(modifier: Double) {
        behaviorModifier = modifier.coerceIn(-1.0, 1.0)
        ErrorLogger.info(TAG, "🧠 Behavior modifier: ${if (modifier >= 0) "+" else ""}${(modifier * 100).toInt()}%")
    }
    
    /**
     * Get the current behavior modifier.
     */
    fun getBehaviorModifier(): Double = behaviorModifier
    
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
     * Now includes behavior modifier:
     *   - Negative behavior → pushes toward MATURE (stricter)
     *   - Positive behavior → pushes toward BOOTSTRAP (looser)
     */
    fun lerp(bootstrap: Double, mature: Double): Double {
        var progress = getLearningProgress()
        
        // Apply behavior modifier
        // Negative behavior (loss streaks) → increase progress (tighter thresholds)
        // Positive behavior (win streaks, 10x) → decrease progress (looser thresholds)
        if (behaviorModifier != 0.0) {
            val behaviorEffect = -behaviorModifier * 0.15  // Max 15% adjustment
            progress = (progress + behaviorEffect).coerceIn(0.0, 1.0)
        }
        
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * Raw lerp without behavior modifier (for display/comparison).
     */
    fun lerpRaw(bootstrap: Double, mature: Double): Double {
        val progress = getLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * Inverse lerp - returns HIGHER value in bootstrap (for bonuses/scores).
     * Also includes behavior modifier.
     */
    fun lerpInverse(bootstrap: Double, mature: Double): Double {
        var progress = getLearningProgress()
        
        // Apply behavior modifier (inverse effect)
        if (behaviorModifier != 0.0) {
            val behaviorEffect = -behaviorModifier * 0.15
            progress = (progress + behaviorEffect).coerceIn(0.0, 1.0)
        }
        
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
    // 
    // Treasury uses LOOSER thresholds because it has:
    // - Tight stop losses (5%)
    // - Quick exits (5-10% TP)
    // - Short hold times (max 8 min)
    // So it can afford to take more shots with lower conviction
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val TREASURY_CONF_BOOTSTRAP = 15   // Very low - take many shots with tight stops
    private const val TREASURY_CONF_MATURE = 50      // Still lower than normal trading
    
    private const val TREASURY_LIQ_BOOTSTRAP = 2000.0   // Lower - quick scalps work with less liquidity
    private const val TREASURY_LIQ_MATURE = 8000.0
    
    private const val TREASURY_TOP_HOLDER_BOOTSTRAP = 35.0  // More tolerant during bootstrap
    private const val TREASURY_TOP_HOLDER_MATURE = 15.0
    
    private const val TREASURY_BUY_PRESSURE_BOOTSTRAP = 35.0  // Lower bar
    private const val TREASURY_BUY_PRESSURE_MATURE = 52.0
    
    private const val TREASURY_SCORE_BOOTSTRAP = 5    // Very low - Treasury has its own exit timing
    private const val TREASURY_SCORE_MATURE = 25
    
    fun getTreasuryConfidenceThreshold(): Int = lerp(TREASURY_CONF_BOOTSTRAP.toDouble(), TREASURY_CONF_MATURE.toDouble()).toInt()
    fun getTreasuryMinLiquidity(): Double = lerp(TREASURY_LIQ_BOOTSTRAP, TREASURY_LIQ_MATURE)
    fun getTreasuryMaxTopHolder(): Double = lerp(TREASURY_TOP_HOLDER_BOOTSTRAP, TREASURY_TOP_HOLDER_MATURE)
    fun getTreasuryMinBuyPressure(): Double = lerp(TREASURY_BUY_PRESSURE_BOOTSTRAP, TREASURY_BUY_PRESSURE_MATURE)
    fun getTreasuryScoreThreshold(): Int = lerp(TREASURY_SCORE_BOOTSTRAP.toDouble(), TREASURY_SCORE_MATURE.toDouble()).toInt()
    
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
    // FLUID STOP LOSS & TAKE PROFIT (Per Mode)
    // ═══════════════════════════════════════════════════════════════════════════
    // 
    // PHILOSOPHY: While learning, use WIDE stop losses and TIGHT take profits.
    // - Wide SL (-10% to -15%) prevents learning from noise during bootstrap
    // - Tight TP (+5% to +8%) captures wins early while learning what works
    // 
    // As bot matures:
    // - SL tightens (-3% to -8%) to protect gains
    // - TP widens (+15% to +50%) to let winners run
    // 
    // Each mode has its own fluid parameters that intertwine with mode strategy.
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get fluid stop loss percentage for a trading mode.
     * Bootstrap: LOOSE stops (allow learning from volatile moves)
     * Mature: TIGHT stops (protect capital with experience)
     */
    fun getFluidStopLoss(modeDefaultStop: Double): Double {
        val progress = getLearningProgress()
        
        // Bootstrap: Use max of mode default or 10% (protect during learning)
        // Mature: Use tighter mode-specific stops
        val bootstrapStop = maxOf(modeDefaultStop, 10.0)  // At least -10% during bootstrap
        val matureStop = modeDefaultStop                   // Use mode's intended stop when mature
        
        return lerp(bootstrapStop, matureStop)
    }
    
    /**
     * Get fluid take profit percentage for a trading mode.
     * Bootstrap: TIGHT take profits (secure wins while learning)
     * Mature: WIDE take profits (let winners run with confidence)
     */
    fun getFluidTakeProfit(modeDefaultTp: Double): Double {
        val progress = getLearningProgress()
        
        // Bootstrap: Take quick 5-8% profits (learn from small wins)
        // Mature: Use mode's full TP target
        val bootstrapTp = minOf(modeDefaultTp, 8.0)  // Max +8% TP during bootstrap
        val matureTp = modeDefaultTp                  // Use mode's intended TP when mature
        
        return lerp(bootstrapTp, matureTp)
    }
    
    /**
     * Get fluid trailing stop percentage.
     * Bootstrap: No trailing stops (too tight, gets stopped out during learning)
     * Mature: Use mode's trailing stop
     */
    fun getFluidTrailingStop(modeDefaultTrailing: Double): Double {
        val progress = getLearningProgress()
        
        // Trailing stops only activate after 50% learning progress
        if (progress < 0.5) return 0.0
        
        // Scale from 0 to full trailing as we mature
        val scaledProgress = (progress - 0.5) * 2.0  // 0.5-1.0 → 0.0-1.0
        return modeDefaultTrailing * scaledProgress
    }
    
    /**
     * Mode-specific fluid parameters container.
     */
    data class FluidModeParams(
        val modeName: String,
        val stopLossPct: Double,      // Current fluid stop loss
        val takeProfitPct: Double,    // Current fluid take profit
        val trailingStopPct: Double,  // Current fluid trailing stop
        val learningProgress: Double,
    ) {
        fun summary(): String = "SL=${stopLossPct.toInt()}% TP=${takeProfitPct.toInt()}% " +
            "Trail=${trailingStopPct.toInt()}% [${(learningProgress*100).toInt()}% learned]"
    }
    
    /**
     * Get all fluid parameters for a specific trading mode.
     * This is the main API for BotService/Executor to use.
     */
    fun getModeParams(
        modeName: String,
        defaultStopPct: Double,
        defaultTpPct: Double,
        defaultTrailingPct: Double
    ): FluidModeParams {
        return FluidModeParams(
            modeName = modeName,
            stopLossPct = getFluidStopLoss(defaultStopPct),
            takeProfitPct = getFluidTakeProfit(defaultTpPct),
            trailingStopPct = getFluidTrailingStop(defaultTrailingPct),
            learningProgress = getLearningProgress()
        )
    }
    
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
        // V3.3: Fluid SL/TP status
        val exampleStopLoss: Double,   // Example using 20% mode default
        val exampleTakeProfit: Double, // Example using 40% mode default
        val exampleTrailing: Double,   // Example using 15% mode default
    ) {
        fun summary(): String = buildString {
            append("🧠 FLUID AI: ${learningProgressPct}% learned ($totalTrades trades)")
            if (isBootstrap) append(" [BOOTSTRAP]")
            append("\n  Liq: watch≥\$$watchlistFloor exec≥\$$executionFloor scan≥\$$scannerMinLiq")
            append("\n  Conf≥${confidenceFloor}% Score≥$scoreThreshold Fresh≤${freshAgeMinutes}min")
            append("\n  SL=${exampleStopLoss.toInt()}% TP=${exampleTakeProfit.toInt()}% Trail=${exampleTrailing.toInt()}%")
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
        
        // Example fluid SL/TP using typical meme mode values
        val exampleParams = getModeParams("MEME_BREAKOUT", 20.0, 40.0, 15.0)
        
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
            exampleStopLoss = exampleParams.stopLossPct,
            exampleTakeProfit = exampleParams.takeProfitPct,
            exampleTrailing = exampleParams.trailingStopPct,
        )
    }
    
    fun logState() {
        val state = getState()
        ErrorLogger.info(TAG, state.summary())
    }
}
