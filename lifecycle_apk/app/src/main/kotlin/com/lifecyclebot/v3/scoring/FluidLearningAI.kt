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
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile
    private var isInitialized = false
    
    fun init() {
        // V4.0 CRITICAL: Guard against re-initialization
        if (isInitialized) {
            ErrorLogger.warn(TAG, "⚠️ init() called again - BLOCKED (already initialized)")
            return
        }
        isInitialized = true
        ErrorLogger.info(TAG, "🧠 FluidLearningAI initialized (ONE-TIME) | " +
            "maturityTarget=$TRADES_FOR_MATURITY trades | " +
            "currentProgress=${(getLearningProgress() * 100).toInt()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING PROGRESS TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val sessionTrades = AtomicInteger(0)
    private val sessionWins = AtomicInteger(0)
    private val lastProgressUpdate = AtomicLong(0)
    private var cachedProgress = 0.0
    
    // Trades needed to reach full maturity
    // V4.0: Increased from 500 to 1000 for better sample size and slower, quality learning
    private const val TRADES_FOR_MATURITY = 1000
    
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
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.1 COLD-START FIX: BOOTSTRAP ENTRY OVERRIDE
    // 
    // Problem: No trades → no data → no learning → no confidence → no trades
    // This is a classic cold-start deadlock.
    //
    // Solution: Force controlled entries during bootstrap to break the deadlock:
    // 1. Allow entry if score >= 80 AND liquidity >= $3K AND age < 10min
    // 2. Add synthetic confidence boost based on trade count
    // 3. Use micro-positions (0.01-0.05 SOL) to limit risk
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if we should force a bootstrap entry to break the cold-start deadlock.
     * Returns true if conditions are exceptional enough to warrant forced learning.
     */
    fun shouldForceBootstrapEntry(
        score: Int,
        liquidityUsd: Double,
        tokenAgeMinutes: Double,
        buyPressurePct: Double,
        isPaper: Boolean
    ): Boolean {
        // Only apply during true bootstrap (learning < 5%)
        if (getLearningProgress() >= 0.05) return false
        
        // Paper mode: be more aggressive to learn faster
        if (isPaper) {
            return score >= 70 && 
                   liquidityUsd >= 1500 && 
                   tokenAgeMinutes <= 15 &&
                   buyPressurePct >= 40
        }
        
        // Live mode: strict conditions only
        return score >= 85 && 
               liquidityUsd >= 3000 && 
               tokenAgeMinutes <= 10 &&
               buyPressurePct >= 55
    }
    
    /**
     * Get synthetic confidence boost for bootstrap mode.
     * This simulates early confidence until real learning kicks in.
     * 
     * Formula: bootstrapBoost = min(20%, tradeCount * 0.5%)
     * 
     * At 0 trades: +0%
     * At 10 trades: +5%
     * At 20 trades: +10%
     * At 40+ trades: +20% (capped)
     */
    fun getBootstrapConfidenceBoost(): Double {
        val tradeCount = getTotalTradeCount()
        val boost = (tradeCount * 0.5).coerceAtMost(20.0)
        return boost
    }
    
    /**
     * Get adjusted confidence including bootstrap boost.
     * Use this instead of raw confidence during bootstrap.
     */
    fun getAdjustedConfidence(rawConfidence: Double, isPaper: Boolean): Double {
        val progress = getLearningProgress()
        
        // Apply bootstrap boost only during early learning
        val boost = if (progress < 0.3) {
            getBootstrapConfidenceBoost()
        } else {
            0.0
        }
        
        // Apply behavior modifier
        val behaviorAdjustment = rawConfidence * behaviorModifier * 0.1
        
        return (rawConfidence + boost + behaviorAdjustment).coerceIn(0.0, 100.0)
    }
    
    /**
     * Get bootstrap position size multiplier.
     * During bootstrap, use micro-positions (0.01-0.05 SOL).
     */
    fun getBootstrapSizeMultiplier(): Double {
        val progress = getLearningProgress()
        
        return when {
            progress < 0.05 -> 0.10  // 10% of normal size (micro-position)
            progress < 0.15 -> 0.25  // 25% of normal size
            progress < 0.30 -> 0.50  // 50% of normal size
            progress < 0.50 -> 0.75  // 75% of normal size
            else -> 1.0               // Full size when mature
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.1: HEURISTIC FALLBACK (When patterns = 0)
    // 
    // When CollectiveAI has no patterns, use a simple heuristic model
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class HeuristicSignal(
        val shouldEnter: Boolean,
        val confidence: Double,
        val reason: String,
    )
    
    /**
     * Simple heuristic model for when AI has no learned patterns.
     * Uses basic market signals: buy pressure, liquidity, age, momentum.
     */
    fun getHeuristicSignal(
        buyPressurePct: Double,
        liquidityUsd: Double,
        tokenAgeMinutes: Double,
        momentum: Double,
        score: Int,
    ): HeuristicSignal {
        var confidence = 20.0  // Base confidence
        val reasons = mutableListOf<String>()
        
        // Buy pressure signal
        when {
            buyPressurePct >= 70 -> { confidence += 25; reasons.add("strong_buy_pressure") }
            buyPressurePct >= 55 -> { confidence += 15; reasons.add("good_buy_pressure") }
            buyPressurePct >= 45 -> { confidence += 5; reasons.add("neutral_pressure") }
            else -> { confidence -= 10; reasons.add("weak_pressure") }
        }
        
        // Liquidity signal
        when {
            liquidityUsd >= 10000 -> { confidence += 15; reasons.add("high_liquidity") }
            liquidityUsd >= 5000 -> { confidence += 10; reasons.add("good_liquidity") }
            liquidityUsd >= 2000 -> { confidence += 5; reasons.add("ok_liquidity") }
            else -> { confidence -= 5; reasons.add("low_liquidity") }
        }
        
        // Age signal (fresher = better for momentum plays)
        when {
            tokenAgeMinutes <= 5 -> { confidence += 15; reasons.add("very_fresh") }
            tokenAgeMinutes <= 15 -> { confidence += 10; reasons.add("fresh") }
            tokenAgeMinutes <= 30 -> { confidence += 5; reasons.add("young") }
            else -> { confidence -= 5; reasons.add("older") }
        }
        
        // Momentum signal
        when {
            momentum >= 20 -> { confidence += 15; reasons.add("strong_momentum") }
            momentum >= 10 -> { confidence += 10; reasons.add("good_momentum") }
            momentum >= 0 -> { confidence += 5; reasons.add("neutral_momentum") }
            else -> { confidence -= 10; reasons.add("negative_momentum") }
        }
        
        // Score signal
        when {
            score >= 80 -> { confidence += 10; reasons.add("high_score") }
            score >= 50 -> { confidence += 5; reasons.add("good_score") }
            else -> {}
        }
        
        confidence = confidence.coerceIn(0.0, 100.0)
        
        // Threshold for entry
        val shouldEnter = confidence >= 45  // Lower threshold for heuristic mode
        
        return HeuristicSignal(
            shouldEnter = shouldEnter,
            confidence = confidence,
            reason = reasons.joinToString(", ")
        )
    }
    
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
     * V4.0: Now uses tiered weights based on trade type.
     * Use recordLiveTrade(), recordPaperTrade(), or recordShadowTrade() for proper weighting.
     * 
     * @deprecated Use the specific trade type methods instead
     */
    fun recordTrade(isWin: Boolean) {
        // Legacy method - defaults to paper weight for backwards compatibility
        recordPaperTrade(isWin)
    }
    
    /**
     * Record that a trade has started (for bootstrap counting).
     * Call this when any layer opens a position.
     */
    fun recordTradeStart() {
        sessionTrades.incrementAndGet()
        lastProgressUpdate.set(0)  // Force progress recalculation
        ErrorLogger.debug(TAG, "📊 Trade started | total=${getTotalTradeCount()} | progress=${(getLearningProgress()*100).toInt()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.0: TIERED LEARNING WEIGHT SYSTEM
    // 
    // Different trade types contribute differently to maturity:
    // - LIVE trades:   0.5 weight (real money, real consequences - most valuable)
    // - PAPER trades:  0.1 weight (real decisions, simulated consequences)
    // - SHADOW trades: 0.025 weight (simulated decisions, simulated consequences)
    // 
    // This ensures the bot learns appropriately from each type:
    // - 200 live trades = full contribution
    // - 1000 paper trades = full contribution
    // - 4000 shadow trades = full contribution
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val LIVE_LEARNING_WEIGHT = 0.5      // 50% weight - real money matters most
    private const val PAPER_LEARNING_WEIGHT = 0.1     // 10% weight - real decisions count
    private const val SHADOW_LEARNING_WEIGHT = 0.025  // 2.5% weight - simulations help slowly
    
    // Accumulators for fractional trade progress
    private var liveTradeAccumulator = 0.0
    private var paperTradeAccumulator = 0.0
    private var shadowTradeAccumulator = 0.0
    private val tradeAccumulatorLock = Any()
    
    /**
     * Record a LIVE trade with highest learning weight (0.5 per trade).
     * 200 live trades = 100% maturity contribution from live.
     * Real money, real consequences - this is the gold standard for learning.
     */
    fun recordLiveTrade(isWin: Boolean) {
        synchronized(tradeAccumulatorLock) {
            liveTradeAccumulator += LIVE_LEARNING_WEIGHT
            
            // When accumulator reaches 1.0, count as one full trade
            while (liveTradeAccumulator >= 1.0) {
                sessionTrades.incrementAndGet()
                if (isWin) sessionWins.incrementAndGet()
                liveTradeAccumulator -= 1.0
            }
            
            cachedProgress = 0.0  // Force recalculation
        }
        
        ErrorLogger.debug(TAG, "🧠 LIVE trade recorded (${LIVE_LEARNING_WEIGHT}x weight) | " +
            "Progress: ${(getLearningProgress()*100).toInt()}%")
    }
    
    /**
     * Record a PAPER trade with medium learning weight (0.1 per trade).
     * 1000 paper trades = 100% maturity contribution from paper.
     * Real decisions, simulated consequences - valuable for learning patterns.
     */
    fun recordPaperTrade(isWin: Boolean) {
        synchronized(tradeAccumulatorLock) {
            paperTradeAccumulator += PAPER_LEARNING_WEIGHT
            
            // When accumulator reaches 1.0, count as one full trade
            while (paperTradeAccumulator >= 1.0) {
                sessionTrades.incrementAndGet()
                if (isWin) sessionWins.incrementAndGet()
                paperTradeAccumulator -= 1.0
            }
            
            cachedProgress = 0.0  // Force recalculation
        }
        
        ErrorLogger.debug(TAG, "🧠 PAPER trade recorded (${PAPER_LEARNING_WEIGHT}x weight) | " +
            "Progress: ${(getLearningProgress()*100).toInt()}%")
    }
    
    /**
     * Record a SHADOW trade with lowest learning weight (0.025 per trade).
     * 4000 shadow trades = 100% maturity contribution from shadow.
     * Simulated everything - helps learn slowly without inflating maturity.
     */
    fun recordShadowTrade(isWin: Boolean) {
        synchronized(tradeAccumulatorLock) {
            shadowTradeAccumulator += SHADOW_LEARNING_WEIGHT
            
            // When accumulator reaches 1.0, count as one full trade
            while (shadowTradeAccumulator >= 1.0) {
                sessionTrades.incrementAndGet()
                if (isWin) sessionWins.incrementAndGet()
                shadowTradeAccumulator -= 1.0
            }
            
            cachedProgress = 0.0  // Force recalculation
        }
        
        ErrorLogger.debug(TAG, "🧠 SHADOW trade recorded (${SHADOW_LEARNING_WEIGHT}x weight) | " +
            "Progress: ${(getLearningProgress()*100).toInt()}%")
    }
    
    /**
     * Get current learning weights for display/debugging.
     */
    fun getLearningWeights(): Triple<Double, Double, Double> = 
        Triple(LIVE_LEARNING_WEIGHT, PAPER_LEARNING_WEIGHT, SHADOW_LEARNING_WEIGHT)
    
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
    // V4.0 FIX: Treasury was TOO LOOSE at bootstrap - 30 losing trades in 2 mins.
    // Now starts with REASONABLE quality requirements that loosen gradually.
    // Treasury still uses looser thresholds than V3 because:
    // - Tight stop losses (-2%)
    // - Quick exits (5-10% TP)
    // - Short hold times (max 8 min)
    // But it should NOT take garbage trades on first startup!
    // V4.0.1: LOWERED bootstrap thresholds - they were blocking ALL trades!
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val TREASURY_CONF_BOOTSTRAP = 20   // V4.0.1: Lowered from 35 - allow learning
    private const val TREASURY_CONF_MATURE = 35      // Raise as we learn (normal progression)
    
    private const val TREASURY_LIQ_BOOTSTRAP = 1500.0   // V4.0.1: Lowered from 5000 - allow smaller plays
    private const val TREASURY_LIQ_MATURE = 5000.0       // Raise threshold as we learn
    
    private const val TREASURY_TOP_HOLDER_BOOTSTRAP = 40.0  // V4.0.1: Raised from 25 - allow more tokens
    private const val TREASURY_TOP_HOLDER_MATURE = 25.0     // Tighten as we learn
    
    private const val TREASURY_BUY_PRESSURE_BOOTSTRAP = 30.0  // V4.0.1: Lowered from 50 - too restrictive
    private const val TREASURY_BUY_PRESSURE_MATURE = 45.0     // Raise as we learn
    
    private const val TREASURY_SCORE_BOOTSTRAP = 10    // V4.0.1: Lowered from 25 - allow more trades
    private const val TREASURY_SCORE_MATURE = 25       // Raise as we learn
    
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V3.3: DYNAMIC FLUID STOP LOSS
    // 
    // The stop loss MOVES with the token position as it develops:
    // 1. Entry phase: Wide stop (allow for normal volatility/retraces)
    // 2. Profit phase: Trailing stop that ratchets up with price
    // 3. Learning-aware: Tighter stops as bot learns what works
    // 
    // This prevents getting stopped out before pump completes,
    // while protecting gains as they accumulate.
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V3.3: Get dynamic fluid stop loss that moves with position.
     * 
     * @param modeDefaultStop Base stop loss % for this trading mode
     * @param currentPnlPct Current P&L percentage of position
     * @param peakPnlPct Highest P&L achieved this position
     * @param holdTimeSeconds How long position has been held
     * @param volatility Current volatility estimate (0-100)
     * @return Dynamic stop loss percentage (negative, e.g., -8.0 means exit at -8%)
     */
    fun getDynamicFluidStop(
        modeDefaultStop: Double,
        currentPnlPct: Double,
        peakPnlPct: Double,
        holdTimeSeconds: Double,
        volatility: Double = 50.0
    ): Double {
        val progress = getLearningProgress()
        
        // Base stop from mode default, adjusted by learning
        val baseStop = getFluidStopLoss(modeDefaultStop)
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 1: ENTRY PROTECTION (first 60s)
        // Allow wider stops during entry volatility - tokens often wick
        // down 5-10% before pumping. Don't get shaken out early.
        // ═══════════════════════════════════════════════════════════════
        if (holdTimeSeconds < 60) {
            // Bootstrap: Even wider entry protection (15%)
            // Mature: Tighter entry protection (12%)
            val entryProtection = lerp(15.0, 12.0)
            return -entryProtection
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 2: VOLATILITY ADJUSTMENT
        // High volatility = wider stops to avoid noise exits
        // Low volatility = tighter stops for quick protection
        // ═══════════════════════════════════════════════════════════════
        val volatilityMult = when {
            volatility > 70 -> 1.3   // High vol: 30% wider stops
            volatility > 50 -> 1.1   // Med vol: 10% wider
            volatility < 30 -> 0.85  // Low vol: 15% tighter
            else -> 1.0
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 3: PROFIT TRAILING (V4.1: MORE AGGRESSIVE)
        // User feedback: "fluid stop loss should move UP as coin makes profit
        // to lock in those gains and tighten to ensure losses are mitigated"
        // ═══════════════════════════════════════════════════════════════
        if (currentPnlPct > 0 && peakPnlPct > 3.0) {  // V4.1: Start trailing earlier (at 3% not 5%)
            // V4.1: Much tighter trailing - lock in more gains
            // Bootstrap: Keep 60% of peak gain (was 50%)
            // Mature: Keep 80% of peak gain (was 70%)
            val keepRatio = lerp(0.60, 0.80)
            
            // Trail stop = Peak gain minus trail distance
            // E.g., peak=20%, keepRatio=0.8 → trail distance=4% → stop at +16%
            val trailDistance = peakPnlPct * (1.0 - keepRatio)
            val trailingStop = peakPnlPct - trailDistance
            
            // V4.1: Once we've seen 8%+ profit, NEVER go below +2% (guaranteed small win)
            if (peakPnlPct > 8.0) {
                val minStop = 2.0  // Lock in at least 2% profit
                return -maxOf(minStop, trailingStop)
            }
            
            // V4.1: Once we've seen 15%+ profit, lock in at least 5%
            if (peakPnlPct > 15.0) {
                val minStop = 5.0
                return -maxOf(minStop, trailingStop)
            }
            
            // V4.1: Once we've seen 25%+ profit, lock in at least 10%
            if (peakPnlPct > 25.0) {
                val minStop = 10.0
                return -maxOf(minStop, trailingStop)
            }
            
            // For smaller gains (3-8%), use trailing but allow down to base stop
            return -maxOf(trailingStop, baseStop)
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 4: RETRACEMENT ALLOWANCE (allowing pre-pump retrace)
        // If we're slightly negative but within normal volatility,
        // give extra room to allow for typical pump patterns.
        // ═══════════════════════════════════════════════════════════════
        if (currentPnlPct > -5.0 && currentPnlPct < 0) {
            // In "retrace zone" - allow wider stop to not exit before pump
            // Bootstrap: 12% retrace allowance
            // Mature: 8% retrace allowance (tighter as we learn patterns)
            val retraceAllowance = lerp(12.0, 8.0)
            return -(retraceAllowance * volatilityMult)
        }
        
        // ═══════════════════════════════════════════════════════════════
        // DEFAULT: Use base fluid stop with volatility adjustment
        // ═══════════════════════════════════════════════════════════════
        return -(baseStop * volatilityMult)
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
    
    /**
     * Get maturity as percentage (0-100) for UI display
     */
    fun getMaturityPercent(): Double {
        return getLearningProgress() * 100.0
    }
}
