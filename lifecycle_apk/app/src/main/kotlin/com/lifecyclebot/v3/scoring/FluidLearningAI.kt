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
    // DATA CLASSES (defined at top for visibility)
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class HeuristicSignal(
        val shouldEnter: Boolean,
        val confidence: Double,
        val reason: String,
    )
    
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
            "bootstrap=0-$BOOTSTRAP_PHASE_END | mature=$BOOTSTRAP_PHASE_END-$MATURE_PHASE_END | continuous=$MATURE_PHASE_END+ | " +
            "currentProgress=${(getLearningProgress() * 100).toInt()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING PROGRESS TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val sessionTrades = AtomicInteger(0)
    private val sessionWins = AtomicInteger(0)
    private val lastProgressUpdate = AtomicLong(0)
    private var cachedProgress = 0.0
    
    // V5.3: 3-Phase learning curve
    //   Phase 1 Bootstrap (0-500 trades):   progress 0.0→0.5 (permissive thresholds, learning mode)
    //   Phase 2 Mature   (500-1000 trades): progress 0.5→1.0 (tightening thresholds)
    //   Phase 3 Continuous (1000+ trades):  progress 1.0 ± win-rate adjustment (adaptive)
    private const val BOOTSTRAP_PHASE_END = 500
    private const val MATURE_PHASE_END = 1000
    
    /**
     * V5.2: Reset all learning progress.
     * WARNING: This clears ALL learned data - use with caution!
     * Called from BehaviorActivity reset button.
     */
    fun resetAllLearning(context: android.content.Context) {
        // Reset session counters
        sessionTrades.set(0)
        sessionWins.set(0)
        cachedProgress = 0.0
        lastProgressUpdate.set(0)
        
        // Reset behavior modifier
        behaviorModifier = 0.0
        aggressionModifier = 0.0
        
        // Clear historical trade stats
        try {
            TradeHistoryStore.clearAllTrades()
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to clear TradeHistoryStore: ${e.message}")
        }
        
        // Clear any other learning-related storage
        try {
            val prefs = context.getSharedPreferences("fluid_learning", android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (_: Exception) {}
        
        ErrorLogger.warn(TAG, "🔄 ALL LEARNING RESET | Progress now 0%")
    }
    
    /**
     * Get total trade count (historical + session).
     * Used for bootstrap calculations and logging.
     */
    fun getTotalTradeCount(): Int {
        val historicalStats = try {
            TradeHistoryStore.getStats()
        } catch (_: Exception) { null }
        
        val historicalTrades = historicalStats?.totalTrades ?: 0
        return historicalTrades + sessionTrades.get()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BEHAVIOR MODIFIER (From BehaviorAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Behavior modifier: -1.0 to +1.0
    // Negative = tighten thresholds (bad behavior like loss streaks)
    // Positive = loosen thresholds (good behavior like win streaks, 10x runs)
    @Volatile
    private var behaviorModifier = 0.0
    
    // V5.2: Aggression modifier from BehaviorAI dashboard (0-11 scale → -0.5 to +0.5)
    private var aggressionModifier = 0.0
    
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
    // V5.2: MORE LENIENT conditions during learning - we NEED trades to learn!
    // 1. Minimum age 1 minute (quick price discovery)
    // 2. Buy pressure >= 45% (decent demand)
    // 3. Score >= 50 (reasonable setup - not too strict!)
    // 4. Liquidity >= $2K (basic safety)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.2: Looser bootstrap to generate more trades for learning
    private const val MIN_TOKEN_AGE_BOOTSTRAP = 1.0   // Minutes - quick entry
    private const val MIN_BUY_PRESSURE_BOOTSTRAP = 40.0  // V5.2 FIX: Lowered from 45 - was too strict
    private const val MIN_SCORE_BOOTSTRAP = 45  // V5.2 FIX: Lowered from 50 - allow more learning
    private const val MIN_LIQUIDITY_BOOTSTRAP = 5000.0  // V5.2 FIX: Lowered from 7500 - was strangling
    
    /**
     * Check if we should force a bootstrap entry to break the cold-start deadlock.
     * V5.2: MORE LENIENT to get more trades for learning
     */
    fun shouldForceBootstrapEntry(
        score: Int,
        liquidityUsd: Double,
        tokenAgeMinutes: Double,
        buyPressurePct: Double,
        isPaper: Boolean
    ): Boolean {
        // V5.3: Apply throughout entire bootstrap phase (learning < 50% = first 500 trades)
        if (getLearningProgress() >= 0.50) return false
        
        // V5.2: Quick age check - only wait 1 minute
        if (tokenAgeMinutes < MIN_TOKEN_AGE_BOOTSTRAP) {
            ErrorLogger.debug(TAG, "⏳ Bootstrap skip: age=${tokenAgeMinutes}m < ${MIN_TOKEN_AGE_BOOTSTRAP}m min")
            return false
        }
        
        // V5.2: Looser buy pressure threshold
        if (buyPressurePct < MIN_BUY_PRESSURE_BOOTSTRAP) {
            ErrorLogger.debug(TAG, "📉 Bootstrap skip: buy%=$buyPressurePct < $MIN_BUY_PRESSURE_BOOTSTRAP min")
            return false
        }
        
        // Paper mode: MORE LENIENT to generate learning data
        if (isPaper) {
            return score >= MIN_SCORE_BOOTSTRAP && 
                   liquidityUsd >= MIN_LIQUIDITY_BOOTSTRAP &&  // $5K minimum
                   tokenAgeMinutes >= MIN_TOKEN_AGE_BOOTSTRAP &&
                   buyPressurePct >= MIN_BUY_PRESSURE_BOOTSTRAP
        }
        
        // Live mode: slightly stricter but still reasonable
        return score >= 55 &&  // V5.2 FIX: Lowered from 60
               liquidityUsd >= MIN_LIQUIDITY_BOOTSTRAP &&  // $5K minimum
               tokenAgeMinutes >= MIN_TOKEN_AGE_BOOTSTRAP &&
               buyPressurePct >= 45  // V5.2 FIX: Lowered from 50
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
        val boost = (tradeCount * 0.5).coerceAtMost(25.0)
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
     * V5.2: Set aggression modifier from BehaviorAI dashboard.
     * Range: -0.5 (ultra defensive) to +0.5 (goes to 11)
     */
    fun setAggressionModifier(modifier: Double) {
        aggressionModifier = modifier.coerceIn(-0.5, 0.5)
        ErrorLogger.info(TAG, "🎚️ Aggression modifier: ${if (modifier >= 0) "+" else ""}${(modifier * 100).toInt()}%")
    }
    
    fun getAggressionModifier(): Double = aggressionModifier
    
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
        
        // V5.3: 3-Phase learning curve
        val baseProgress = when {
            totalTrades <= BOOTSTRAP_PHASE_END ->
                // Phase 1 Bootstrap (0-500 trades): 0.0 → 0.5
                (totalTrades.toDouble() / BOOTSTRAP_PHASE_END) * 0.5
            totalTrades <= MATURE_PHASE_END ->
                // Phase 2 Mature (500-1000 trades): 0.5 → 1.0
                0.5 + ((totalTrades - BOOTSTRAP_PHASE_END).toDouble() / BOOTSTRAP_PHASE_END) * 0.5
            else ->
                // Phase 3 Continuous (1000+ trades): full maturity baseline
                1.0
        }

        val progress = when {
            totalTrades > MATURE_PHASE_END -> {
                // Phase 3: Continuous adaptive adjustment based on recent performance
                // Poor performance loosens thresholds slightly so bot can learn from more trades
                // Strong performance keeps thresholds tight (quality over quantity)
                when {
                    blendedWinRate > 60 -> baseProgress                              // Performing well: stay strict
                    blendedWinRate < 30 -> (baseProgress - 0.25).coerceAtLeast(0.6) // Very poor: loosen significantly
                    blendedWinRate < 40 -> (baseProgress - 0.15).coerceAtLeast(0.7) // Poor: loosen a bit
                    else -> baseProgress
                }
            }
            else -> {
                // Phase 1-2: Win rate speeds/slows learning progression
                when {
                    blendedWinRate > 60 -> (baseProgress * 1.1).coerceAtMost(1.0)  // +10% faster
                    blendedWinRate < 40 -> baseProgress * 0.9                        // -10% slower
                    else -> baseProgress
                }
            }
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
    // V4.1.1: LOWERED bootstrap from $1500 to $800 to allow learning on smaller tokens
    private const val LIQ_EXECUTION_BOOTSTRAP = 800.0    // $800 in bootstrap - allow more learning
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
    
    private const val SCORE_BOOTSTRAP = 20     // V5.5b: Reverted — raising to 30 blocked all early_unknown tokens
    private const val SCORE_MATURE = 35        // V5.5b: Modest raise from 30; at 63% lerp → ~29 (allows score-21 EXECUTE_SMALL)
    
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
    // V5.0: RAISED bootstrap thresholds - too much garbage was getting through!
    // V5.1: SLIGHTLY LOWERED to allow more trading during bootstrap
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val TREASURY_CONF_BOOTSTRAP = 30   // V5.1: Lowered from 35 - allow more trades
    private const val TREASURY_CONF_MATURE = 45      // Raise as we learn (normal progression)
    
    private const val TREASURY_LIQ_BOOTSTRAP = 3000.0   // V5.1: Lowered from 5000 - allow smaller liq
    private const val TREASURY_LIQ_MATURE = 10000.0      // Raise threshold as we learn
    
    private const val TREASURY_TOP_HOLDER_BOOTSTRAP = 40.0  // V5.1: Raised from 35 - more permissive
    private const val TREASURY_TOP_HOLDER_MATURE = 25.0     // Tighten as we learn
    
    private const val TREASURY_BUY_PRESSURE_BOOTSTRAP = 35.0  // V5.1: Lowered from 40 - allow more
    private const val TREASURY_BUY_PRESSURE_MATURE = 50.0     // Raise as we learn
    
    private const val TREASURY_SCORE_BOOTSTRAP = 15    // V5.5b: Reverted — Treasury has own scoring system, don't over-gate
    private const val TREASURY_SCORE_MATURE = 32       // V5.5b: Modest raise from 30
    
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
     * V5.1: TIGHTER bootstrap stops - don't let losses run while learning
     * Bootstrap: Use tighter stops (-6% max) to protect capital while learning
     * Mature: Use mode-specific stops (can be wider with experience)
     */
    fun getFluidStopLoss(modeDefaultStop: Double): Double {
        val progress = getLearningProgress()
        
        // V5.6: Tightened bootstrap SL cap from 6% to 4%
        // At 75% learning, losses were running to 9%+ while wins only captured 3-5%
        // 4% cap forces quicker cuts — better to lose small and reload on a better setup
        val bootstrapStop = maxOf(modeDefaultStop, 4.0)  // Cap at -4% during bootstrap
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
        
        // V5.2.11: Raised bootstrap TP from 8% to 15%
        // 8% was selling winners too early before they could run
        val bootstrapTp = minOf(modeDefaultTp, 15.0)  // Max +15% TP during bootstrap
        val matureTp = modeDefaultTp                   // Use mode's intended TP when mature
        
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
            isBootstrap = progress < 0.5,  // V5.3: Bootstrap = first 500 trades (progress < 0.5)
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: FLUID HOLDING TIMES (Per Layer)
    // 
    // Each trading layer has optimal hold times that FLUID learns over time:
    // 
    // LAYER          BOOTSTRAP       MATURE          RATIONALE
    // ─────────────────────────────────────────────────────────────────────────
    // Treasury       3-8 min         1-5 min         Quick scalps, tighten with experience
    // ShitCoin       5-15 min        3-10 min        Fast plays, learn optimal timing
    // V3 Quality     15-60 min       10-45 min       Quality setups need time
    // Blue Chip      30-120 min      20-90 min       Let winners ride longer
    // Moonshot       30-180 min      45-240 min      Big moves need patience
    // 
    // Bootstrap: WIDER hold windows (learning what works)
    // Mature: TIGHTER windows (optimized from data)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Treasury Layer - Quick scalps
    private const val TREASURY_MIN_HOLD_BOOTSTRAP = 3.0     // 3 min minimum
    private const val TREASURY_MIN_HOLD_MATURE = 1.0        // 1 min minimum (fast exits OK)
    private const val TREASURY_MAX_HOLD_BOOTSTRAP = 12.0    // 12 min max during learning
    private const val TREASURY_MAX_HOLD_MATURE = 8.0        // 8 min max when optimized
    
    // ShitCoin Layer - V5.2: NO HOLD TIME RESTRICTIONS - exit on signals only!
    // ShitCoins exit on: SL, TP, trailing stop, momentum death, rug detection
    // NOT on time - memes can moon or dump at any moment
    private const val SHITCOIN_MIN_HOLD_BOOTSTRAP = 0.0     // V5.2: No min hold
    private const val SHITCOIN_MIN_HOLD_MATURE = 0.0        // V5.2: No min hold
    private const val SHITCOIN_MAX_HOLD_BOOTSTRAP = 999.0   // Effectively no limit
    private const val SHITCOIN_MAX_HOLD_MATURE = 999.0      // Effectively no limit
    
    // V3 Quality Layer - Solid setups
    private const val V3_MIN_HOLD_BOOTSTRAP = 5.0           // 5 min minimum
    private const val V3_MIN_HOLD_MATURE = 3.0              // 3 min minimum
    private const val V3_MAX_HOLD_BOOTSTRAP = 90.0          // 90 min max during learning
    private const val V3_MAX_HOLD_MATURE = 60.0             // 60 min max when optimized
    
    // Blue Chip Layer - Quality holds
    private const val BLUECHIP_MIN_HOLD_BOOTSTRAP = 10.0    // 10 min minimum
    private const val BLUECHIP_MIN_HOLD_MATURE = 5.0        // 5 min minimum
    private const val BLUECHIP_MAX_HOLD_BOOTSTRAP = 180.0   // 3 hours max during learning
    private const val BLUECHIP_MAX_HOLD_MATURE = 120.0      // 2 hours max when optimized
    
    // Moonshot Layer - Let big plays ride
    private const val MOONSHOT_MIN_HOLD_BOOTSTRAP = 15.0    // 15 min minimum
    private const val MOONSHOT_MIN_HOLD_MATURE = 10.0       // 10 min minimum
    private const val MOONSHOT_MAX_HOLD_BOOTSTRAP = 300.0   // 5 hours max during learning
    private const val MOONSHOT_MAX_HOLD_MATURE = 240.0      // 4 hours max when optimized
    
    /**
     * Get fluid minimum hold time for a layer (in minutes).
     * Bootstrap: More patient (avoid early exits while learning)
     * Mature: Tighter (optimized from experience)
     */
    fun getFluidMinHoldMinutes(layer: String): Double {
        return when (layer.uppercase()) {
            "TREASURY" -> lerp(TREASURY_MIN_HOLD_BOOTSTRAP, TREASURY_MIN_HOLD_MATURE)
            "SHITCOIN", "SHITCOIN_EXPRESS", "EXPRESS" -> lerp(SHITCOIN_MIN_HOLD_BOOTSTRAP, SHITCOIN_MIN_HOLD_MATURE)
            "V3", "V3_QUALITY", "QUALITY" -> lerp(V3_MIN_HOLD_BOOTSTRAP, V3_MIN_HOLD_MATURE)
            "BLUECHIP", "BLUE_CHIP" -> lerp(BLUECHIP_MIN_HOLD_BOOTSTRAP, BLUECHIP_MIN_HOLD_MATURE)
            "MOONSHOT", "MOONSHOT_ORBITAL", "MOONSHOT_LUNAR", "MOONSHOT_MARS", "MOONSHOT_JUPITER" -> 
                lerp(MOONSHOT_MIN_HOLD_BOOTSTRAP, MOONSHOT_MIN_HOLD_MATURE)
            else -> lerp(V3_MIN_HOLD_BOOTSTRAP, V3_MIN_HOLD_MATURE)  // Default to V3
        }
    }
    
    /**
     * Get fluid maximum hold time for a layer (in minutes).
     * Bootstrap: Wider windows (learning optimal timing)
     * Mature: Tighter windows (exit before profits decay)
     */
    fun getFluidMaxHoldMinutes(layer: String): Double {
        return when (layer.uppercase()) {
            "TREASURY" -> lerp(TREASURY_MAX_HOLD_BOOTSTRAP, TREASURY_MAX_HOLD_MATURE)
            "SHITCOIN", "SHITCOIN_EXPRESS", "EXPRESS" -> lerp(SHITCOIN_MAX_HOLD_BOOTSTRAP, SHITCOIN_MAX_HOLD_MATURE)
            "V3", "V3_QUALITY", "QUALITY" -> lerp(V3_MAX_HOLD_BOOTSTRAP, V3_MAX_HOLD_MATURE)
            "BLUECHIP", "BLUE_CHIP" -> lerp(BLUECHIP_MAX_HOLD_BOOTSTRAP, BLUECHIP_MAX_HOLD_MATURE)
            "MOONSHOT", "MOONSHOT_ORBITAL", "MOONSHOT_LUNAR", "MOONSHOT_MARS", "MOONSHOT_JUPITER" -> 
                lerp(MOONSHOT_MAX_HOLD_BOOTSTRAP, MOONSHOT_MAX_HOLD_MATURE)
            else -> lerp(V3_MAX_HOLD_BOOTSTRAP, V3_MAX_HOLD_MATURE)  // Default to V3
        }
    }
    
    /**
     * Check if a position has exceeded its optimal hold time.
     * Returns true if position should be considered for exit due to time.
     */
    fun isHoldTimeExceeded(layer: String, holdTimeMinutes: Double): Boolean {
        val maxHold = getFluidMaxHoldMinutes(layer)
        return holdTimeMinutes > maxHold
    }
    
    /**
     * Check if a position is being exited too early.
     * Returns true if we should wait longer before exiting (unless in profit).
     */
    fun isHoldTimeTooShort(layer: String, holdTimeMinutes: Double, pnlPct: Double): Boolean {
        // If in significant profit (>5%), allow early exit
        if (pnlPct >= 5.0) return false
        
        val minHold = getFluidMinHoldMinutes(layer)
        return holdTimeMinutes < minHold
    }
    
    /**
     * Get hold time exit urgency (0.0 = no urgency, 1.0 = exit now).
     * Starts ramping up urgency as hold time approaches max.
     */
    fun getHoldTimeUrgency(layer: String, holdTimeMinutes: Double): Double {
        val maxHold = getFluidMaxHoldMinutes(layer)
        
        // No urgency until 80% of max hold time
        if (holdTimeMinutes < maxHold * 0.8) return 0.0
        
        // Linear ramp from 80% to 100% of max hold
        val urgencyStart = maxHold * 0.8
        val progress = (holdTimeMinutes - urgencyStart) / (maxHold - urgencyStart)
        return progress.coerceIn(0.0, 1.0)
    }
    
    /**
     * Get fluid hold time parameters for a layer.
     */
    data class FluidHoldParams(
        val layer: String,
        val minHoldMinutes: Double,
        val maxHoldMinutes: Double,
        val learningProgress: Double,
    ) {
        fun summary(): String = "Hold: ${minHoldMinutes.toInt()}-${maxHoldMinutes.toInt()}min " +
            "[${(learningProgress * 100).toInt()}% learned]"
    }
    
    fun getLayerHoldParams(layer: String): FluidHoldParams {
        return FluidHoldParams(
            layer = layer,
            minHoldMinutes = getFluidMinHoldMinutes(layer),
            maxHoldMinutes = getFluidMaxHoldMinutes(layer),
            learningProgress = getLearningProgress(),
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: FEE-AWARE PROFIT CALCULATIONS
    // 
    // CRITICAL: Every trade incurs Solana + Jupiter fees. The bot MUST account
    // for these when calculating REAL P&L and making exit decisions.
    // 
    // Fee breakdown per round-trip (buy + sell):
    //   Solana network fee:  ~0.00001 SOL (2 transactions)
    //   Jupiter protocol:    0.3% per swap × 2 = 0.6% total
    //   Price impact:        Variable (higher for thin liquidity)
    // 
    // TOTAL ROUND-TRIP COST: ~0.6% + price impact
    // 
    // This means:
    // - A 1% gross profit could be a 0.4% NET profit (or even negative!)
    // - Minimum viable profit target should be >1% to cover fees
    // - Larger positions = more fees in absolute terms
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Fee constants (mirrored from SlippageGuard for consistency)
    private const val SOLANA_TX_FEE_SOL = 0.000005          // Per transaction
    private const val JUPITER_FEE_PCT = 0.003               // 0.3% per swap
    private const val ROUND_TRIP_FEE_PCT = 0.006            // 0.6% total (buy + sell)
    
    /**
     * Calculate net P&L after deducting estimated fees.
     * 
     * @param grossPnlSol Gross profit/loss in SOL
     * @param positionSizeSol Total position size in SOL
     * @return Pair of (netPnlSol, totalFeesSol)
     */
    fun calculateNetPnl(grossPnlSol: Double, positionSizeSol: Double): Pair<Double, Double> {
        val jupiterFees = positionSizeSol * ROUND_TRIP_FEE_PCT
        val networkFees = SOLANA_TX_FEE_SOL * 2  // Buy + Sell
        val totalFees = jupiterFees + networkFees
        val netPnl = grossPnlSol - totalFees
        return netPnl to totalFees
    }
    
    /**
     * Calculate minimum profitable exit percentage for a given position.
     * This accounts for fees to ensure we don't exit at a NET LOSS.
     * 
     * @param positionSizeSol Position size in SOL
     * @param liquidityUsd Pool liquidity (affects price impact)
     * @return Minimum % gain needed to break even after fees
     */
    fun getMinProfitableExitPct(positionSizeSol: Double, liquidityUsd: Double): Double {
        // Base fee cost as percentage
        var minPct = ROUND_TRIP_FEE_PCT * 100  // 0.6%
        
        // Add estimated price impact based on liquidity
        val solPrice = 150.0  // Approximate - could be passed in
        val positionUsd = positionSizeSol * solPrice
        
        val priceImpactPct = when {
            liquidityUsd < 5000 -> (positionUsd / liquidityUsd) * 5.0   // Very thin liquidity
            liquidityUsd < 20000 -> (positionUsd / liquidityUsd) * 2.5  // Thin liquidity
            liquidityUsd < 50000 -> (positionUsd / liquidityUsd) * 1.5  // Medium liquidity
            else -> (positionUsd / liquidityUsd) * 0.5                   // Good liquidity
        }.coerceAtMost(5.0)  // Cap at 5% impact
        
        minPct += priceImpactPct * 2  // Impact on both buy and sell
        
        // Add safety buffer (20%)
        minPct *= 1.2
        
        return minPct.coerceAtLeast(1.0)  // Minimum 1% to be worth trading
    }
    
    /**
     * Check if a trade would be profitable after fees.
     * 
     * @param grossPnlPct Gross P&L percentage
     * @param positionSizeSol Position size
     * @param liquidityUsd Pool liquidity
     * @return True if trade is net profitable, false if fees would eat the profit
     */
    fun isNetProfitable(grossPnlPct: Double, positionSizeSol: Double, liquidityUsd: Double): Boolean {
        val minRequired = getMinProfitableExitPct(positionSizeSol, liquidityUsd)
        return grossPnlPct >= minRequired
    }
    
    /**
     * Get fee-adjusted take profit target.
     * Ensures TP target accounts for fees to deliver REAL profit.
     * 
     * @param rawTpPct Raw take profit percentage from mode
     * @param positionSizeSol Position size
     * @param liquidityUsd Pool liquidity
     * @return Adjusted TP that ensures net profit after fees
     */
    fun getFeeAdjustedTakeProfit(rawTpPct: Double, positionSizeSol: Double, liquidityUsd: Double): Double {
        val minRequired = getMinProfitableExitPct(positionSizeSol, liquidityUsd)
        
        // TP should be at least minRequired + 50% buffer for actual profit
        val minTp = minRequired * 1.5
        
        return maxOf(rawTpPct, minTp)
    }
    
    /**
     * Log fee analysis for a potential trade.
     */
    fun logFeeAnalysis(
        symbol: String,
        positionSizeSol: Double,
        liquidityUsd: Double,
        targetTpPct: Double,
    ) {
        val minProfitable = getMinProfitableExitPct(positionSizeSol, liquidityUsd)
        val adjustedTp = getFeeAdjustedTakeProfit(targetTpPct, positionSizeSol, liquidityUsd)
        val (_, fees) = calculateNetPnl(positionSizeSol * targetTpPct / 100, positionSizeSol)
        
        ErrorLogger.debug(TAG, "💰 FEE ANALYSIS: $symbol | " +
            "size=${positionSizeSol}SOL | liq=\$${liquidityUsd.toInt()} | " +
            "minProfit=${minProfitable.toInt()}% | rawTP=${targetTpPct.toInt()}% | " +
            "adjTP=${adjustedTp.toInt()}% | fees=${fees}SOL")
    }
}
