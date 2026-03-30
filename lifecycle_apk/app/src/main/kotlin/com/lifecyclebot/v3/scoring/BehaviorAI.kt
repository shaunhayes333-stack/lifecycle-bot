package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * BehaviorAI - Layer 25: Trading Behavior Pattern Recognition
 * 
 * PROBLEM: The bot doesn't learn from its own behavioral patterns:
 *   - Loss streaks compound into emotional overtrading
 *   - Big wins (10x, 100x) not recognized as behavioral milestones
 *   - No connection between behavior patterns and fluid thresholds
 * 
 * SOLUTION: Track behavior patterns and feed them to:
 *   1. FluidLearningAI - Bad behavior tightens thresholds, good behavior loosens
 *   2. MetaCognitionAI - Behavioral context for decision-making
 *   3. FearGreedAI integration - Bot's internal "fear/greed" state
 * 
 * BEHAVIOR TRACKING:
 *   BAD BEHAVIORS:
 *     - Loss streaks (3+ consecutive losses)
 *     - Tilt trading (rapid trades after losses)
 *     - Stop-loss cascades (multiple stops in short window)
 *     - FOMO entries (buying near recent highs)
 *     - Revenge trading (doubling down after loss)
 *   
 *   GOOD BEHAVIORS:
 *     - Win streaks (3+ consecutive wins)
 *     - Mega pumps (50%+ gains)
 *     - 10x+ runs
 *     - 100x moonshots
 *     - Disciplined exits (taking profits at targets)
 *     - Patience (waiting for quality setups)
 * 
 * SCORING OUTPUT:
 *   - behaviorScore: -30 to +30 adjustment to final score
 *   - fluidAdjustment: Modifier for FluidLearningAI thresholds
 *   - tradingAllowed: Hard block if in "tilt protection" mode
 */
object BehaviorAI {
    
    private const val TAG = "BehaviorAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BEHAVIORAL STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Current behavior state
    private val currentStreak = AtomicInteger(0)  // Positive = wins, negative = losses
    private val consecutiveLosses = AtomicInteger(0)
    private val consecutiveWins = AtomicInteger(0)
    private val tiltLevel = AtomicInteger(0)  // 0-100, higher = more tilted
    private val disciplineScore = AtomicInteger(50)  // 0-100
    private val lastTradeTime = AtomicReference(0L)
    
    // Milestones achieved
    private val tenXCount = AtomicInteger(0)       // 10x or greater
    private val hundredXCount = AtomicInteger(0)   // 100x moonshots
    private val megaPumpCount = AtomicInteger(0)   // 50%+ gains
    private val bigLossCount = AtomicInteger(0)    // -50% or worse losses
    
    // Session tracking
    private val sessionTrades = AtomicInteger(0)
    private val sessionWins = AtomicInteger(0)
    private val sessionLosses = AtomicInteger(0)
    private val sessionBigWins = AtomicInteger(0)  // 50%+ wins this session
    
    // Tilt protection
    private var tiltProtectionUntil = 0L
    private var lastLossStreakEnd = 0L
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val LOSS_STREAK_THRESHOLD = 3    // Trigger tilt after 3 consecutive losses
    private const val WIN_STREAK_BONUS = 3         // Bonus after 3 consecutive wins
    private const val MEGA_PUMP_PCT = 50.0         // 50%+ = mega pump
    private const val TEN_X_PCT = 900.0            // 900%+ = 10x
    private const val HUNDRED_X_PCT = 9900.0       // 9900%+ = 100x
    private const val BIG_LOSS_PCT = -50.0         // -50% or worse
    // V4.0: Reduced tilt cooldown from 15 min to 1 min - don't want to miss opportunities
    private const val TILT_COOLDOWN_MS = 1 * 60 * 1000L  // 1 min cooldown after tilt
    private const val RAPID_TRADE_MS = 60 * 1000L  // 60 sec = rapid trading
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a completed trade for behavior analysis.
     * Call this after every SELL execution.
     */
    fun recordTrade(pnlPct: Double, reason: String, mint: String) {
        val now = System.currentTimeMillis()
        val timeSinceLastTrade = now - lastTradeTime.get()
        lastTradeTime.set(now)
        
        sessionTrades.incrementAndGet()
        
        // Classify the trade
        when {
            // ═══════════════════════════════════════════════════════════════
            // MEGA WINS
            // ═══════════════════════════════════════════════════════════════
            pnlPct >= HUNDRED_X_PCT -> {
                hundredXCount.incrementAndGet()
                tenXCount.incrementAndGet()
                megaPumpCount.incrementAndGet()
                sessionBigWins.incrementAndGet()
                consecutiveWins.incrementAndGet()
                consecutiveLosses.set(0)
                disciplineScore.addAndGet(20)
                tiltLevel.addAndGet(-30)  // Big win reduces tilt significantly
                
                ErrorLogger.info(TAG, "🚀🌕 100X MOONSHOT! $mint +${pnlPct.toInt()}% | Streak: ${consecutiveWins.get()}W")
                updateFluidBonus(0.3)  // Major positive fluid adjustment
            }
            
            pnlPct >= TEN_X_PCT -> {
                tenXCount.incrementAndGet()
                megaPumpCount.incrementAndGet()
                sessionBigWins.incrementAndGet()
                consecutiveWins.incrementAndGet()
                consecutiveLosses.set(0)
                disciplineScore.addAndGet(15)
                tiltLevel.addAndGet(-20)
                
                ErrorLogger.info(TAG, "🚀 10X RUN! $mint +${pnlPct.toInt()}% | Streak: ${consecutiveWins.get()}W")
                updateFluidBonus(0.2)
            }
            
            pnlPct >= MEGA_PUMP_PCT -> {
                megaPumpCount.incrementAndGet()
                sessionBigWins.incrementAndGet()
                consecutiveWins.incrementAndGet()
                consecutiveLosses.set(0)
                disciplineScore.addAndGet(10)
                tiltLevel.addAndGet(-10)
                
                ErrorLogger.info(TAG, "💰 MEGA PUMP! $mint +${pnlPct.toInt()}% | Streak: ${consecutiveWins.get()}W")
                updateFluidBonus(0.1)
            }
            
            // ═══════════════════════════════════════════════════════════════
            // REGULAR WINS
            // ═══════════════════════════════════════════════════════════════
            pnlPct > 2.0 -> {
                sessionWins.incrementAndGet()
                consecutiveWins.incrementAndGet()
                consecutiveLosses.set(0)
                disciplineScore.addAndGet(2)
                tiltLevel.addAndGet(-3)
                
                // Win streak bonus
                if (consecutiveWins.get() >= WIN_STREAK_BONUS) {
                    ErrorLogger.info(TAG, "🔥 WIN STREAK: ${consecutiveWins.get()} consecutive wins!")
                    disciplineScore.addAndGet(5)
                    updateFluidBonus(0.05)
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // SCRATCH (break even)
            // ═══════════════════════════════════════════════════════════════
            pnlPct in -2.0..2.0 -> {
                // Neutral - no streak change
                disciplineScore.addAndGet(1)  // Small bonus for not losing
            }
            
            // ═══════════════════════════════════════════════════════════════
            // LOSSES
            // ═══════════════════════════════════════════════════════════════
            pnlPct <= BIG_LOSS_PCT -> {
                bigLossCount.incrementAndGet()
                sessionLosses.incrementAndGet()
                consecutiveLosses.incrementAndGet()
                consecutiveWins.set(0)
                disciplineScore.addAndGet(-15)
                tiltLevel.addAndGet(25)
                
                ErrorLogger.warn(TAG, "💀 BIG LOSS! $mint ${pnlPct.toInt()}% | Loss streak: ${consecutiveLosses.get()}")
                checkTiltProtection()
                updateFluidPenalty(0.15)
            }
            
            pnlPct < -2.0 -> {
                sessionLosses.incrementAndGet()
                consecutiveLosses.incrementAndGet()
                consecutiveWins.set(0)
                disciplineScore.addAndGet(-3)
                tiltLevel.addAndGet(8)
                
                // Check for rapid trading after loss (tilt indicator)
                if (timeSinceLastTrade < RAPID_TRADE_MS && consecutiveLosses.get() >= 2) {
                    tiltLevel.addAndGet(10)
                    ErrorLogger.warn(TAG, "⚠️ RAPID LOSS TRADING detected - potential tilt")
                }
                
                // Loss streak warning
                if (consecutiveLosses.get() >= LOSS_STREAK_THRESHOLD) {
                    ErrorLogger.warn(TAG, "🔴 LOSS STREAK: ${consecutiveLosses.get()} consecutive losses")
                    checkTiltProtection()
                    updateFluidPenalty(0.1)
                }
            }
        }
        
        // Clamp values
        disciplineScore.set(disciplineScore.get().coerceIn(0, 100))
        tiltLevel.set(tiltLevel.get().coerceIn(0, 100))
        
        // Update current streak
        currentStreak.set(
            if (consecutiveWins.get() > 0) consecutiveWins.get()
            else -consecutiveLosses.get()
        )
        
        // Log state
        logBehaviorState()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TILT PROTECTION
    // V5.2: More lenient during bootstrap phase to prevent over-blocking
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun checkTiltProtection() {
        val losses = consecutiveLosses.get()
        val tilt = tiltLevel.get()
        
        // V5.2: Get learning progress - be much more lenient during bootstrap
        val learningProgress = try {
            FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }
        
        // V5.2: Scale tilt thresholds based on learning progress
        // At bootstrap (0-20% learning): require 15 losses or 150% tilt (basically never tilt)
        // At mature (100% learning): require 5 losses or 80% tilt
        val lossThreshold = when {
            learningProgress < 0.1 -> 15     // Very early: almost never tilt
            learningProgress < 0.2 -> 12     // Bootstrap: very lenient
            learningProgress < 0.4 -> 10     // Learning: still lenient  
            learningProgress < 0.6 -> 8      // Progressing: moderate
            learningProgress < 0.8 -> 6      // Approaching mature
            else -> 5                        // Mature: standard threshold
        }
        
        val tiltThreshold = when {
            learningProgress < 0.1 -> 150    // Very early: effectively disabled
            learningProgress < 0.2 -> 120    // Bootstrap: very high threshold
            learningProgress < 0.4 -> 100    // Learning: high threshold
            learningProgress < 0.6 -> 90     // Progressing: still high
            else -> 80                       // Mature: standard threshold
        }
        
        if (losses >= lossThreshold || tilt >= tiltThreshold) {
            // V5.2: Shorter cooldown during bootstrap
            val cooldownMs = when {
                learningProgress < 0.2 -> 30 * 1000L      // 30 seconds at bootstrap
                learningProgress < 0.5 -> 45 * 1000L     // 45 seconds during learning
                else -> TILT_COOLDOWN_MS                  // 1 min at mature
            }
            
            tiltProtectionUntil = System.currentTimeMillis() + cooldownMs
            lastLossStreakEnd = System.currentTimeMillis()
            
            ErrorLogger.warn(TAG, "🛑 TILT PROTECTION ACTIVATED | ${cooldownMs / 1000}s cooldown | " +
                "learning=${(learningProgress * 100).toInt()}% | threshold=$lossThreshold losses")
            ErrorLogger.warn(TAG, "🛑 Reason: ${losses} consecutive losses (threshold=$lossThreshold), " +
                "tilt level ${tilt}% (threshold=$tiltThreshold)")
        }
    }
    
    /**
     * Check if trading should be blocked due to tilt protection.
     */
    fun isTiltProtectionActive(): Boolean {
        if (tiltProtectionUntil > System.currentTimeMillis()) {
            val remaining = (tiltProtectionUntil - System.currentTimeMillis()) / 1000
            ErrorLogger.debug(TAG, "🛑 Tilt protection: ${remaining}s remaining")
            return true
        }
        return false
    }
    
    /**
     * Get remaining tilt protection time in seconds.
     */
    fun getTiltProtectionRemaining(): Long {
        val remaining = tiltProtectionUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID LEARNING INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Fluid adjustment factor (-1.0 to +1.0)
    // Negative = tighten thresholds (bad behavior)
    // Positive = loosen thresholds (good behavior)
    private val fluidAdjustment = AtomicReference(0.0)
    
    private fun updateFluidBonus(bonus: Double) {
        val current = fluidAdjustment.get()
        val newValue = (current + bonus).coerceIn(-1.0, 1.0)
        fluidAdjustment.set(newValue)
        
        // Notify FluidLearningAI
        try {
            FluidLearningAI.applyBehaviorModifier(newValue)
        } catch (_: Exception) { }
    }
    
    private fun updateFluidPenalty(penalty: Double) {
        val current = fluidAdjustment.get()
        val newValue = (current - penalty).coerceIn(-1.0, 1.0)
        fluidAdjustment.set(newValue)
        
        // Notify FluidLearningAI
        try {
            FluidLearningAI.applyBehaviorModifier(newValue)
        } catch (_: Exception) { }
    }
    
    /**
     * Get the current fluid adjustment factor.
     * Used by FluidLearningAI to modify thresholds.
     */
    fun getFluidAdjustment(): Double = fluidAdjustment.get()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING - For UnifiedScorer integration
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get behavior-based score adjustment for a trade decision.
     * This is used by UnifiedScorer to influence the final score.
     */
    fun getScoreAdjustment(): Int {
        var score = 0
        
        // V3.3: Scale penalties by learning progress
        // At bootstrap (0-20% learning), behavior penalties are reduced
        // This prevents no-data penalties from crushing early trades
        val learningProgress = try {
            FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }
        
        // Penalty multiplier: 0.3 at bootstrap → 1.0 at mature
        val penaltyMult = (0.3 + (learningProgress * 0.7)).coerceIn(0.3, 1.0)
        
        // Tilt penalty (reduced during bootstrap)
        val tilt = tiltLevel.get()
        if (tilt >= 80) score -= (15 * penaltyMult).toInt()
        else if (tilt >= 60) score -= (10 * penaltyMult).toInt()
        else if (tilt >= 40) score -= (5 * penaltyMult).toInt()
        
        // Discipline bonus/penalty
        val discipline = disciplineScore.get()
        if (discipline >= 80) score += 10  // Bonuses stay full
        else if (discipline >= 60) score += 5
        else if (discipline <= 20) {
            // V3.3: Reduced penalty during bootstrap (no trade history = low discipline score)
            score -= (5 * penaltyMult).toInt()
        }
        
        // Current streak effect (bonuses full, penalties scaled)
        val streak = currentStreak.get()
        when {
            streak >= 5 -> score += 8   // 5+ win streak = confidence
            streak >= 3 -> score += 4   // 3+ win streak = momentum
            streak <= -5 -> score -= (10 * penaltyMult).toInt() // 5+ loss streak = caution
            streak <= -3 -> score -= (5 * penaltyMult).toInt()  // 3+ loss streak = warning
        }
        
        // Recent big wins boost confidence
        if (sessionBigWins.get() >= 2) score += 5
        
        return score.coerceIn(-20, 20)
    }
    
    /**
     * Get a confidence adjustment based on behavior.
     * Used by MetaCognitionAI for meta-confidence calculation.
     */
    fun getConfidenceModifier(): Int {
        var mod = 0
        
        // Discipline directly affects confidence
        val discipline = disciplineScore.get()
        mod += (discipline - 50) / 10  // -5 to +5
        
        // Tilt reduces confidence
        val tilt = tiltLevel.get()
        mod -= tilt / 20  // 0 to -5
        
        // Streaks affect confidence
        val streak = currentStreak.get()
        if (streak >= 3) mod += 3
        if (streak <= -3) mod -= 3
        
        return mod.coerceIn(-10, 10)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SENTIMENT INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get internal "fear/greed" state based on behavior.
     * Integrates with FearGreedAI for composite sentiment.
     * 
     * Returns 0-100 where:
     *   0-24: Internal Fear (loss streak, tilt)
     *   25-44: Internal Caution
     *   45-55: Neutral
     *   56-75: Internal Confidence  
     *   76-100: Internal Greed (win streak, euphoria)
     */
    fun getInternalSentiment(): Int {
        var sentiment = 50  // Start neutral
        
        // Win streak increases greed
        val streak = currentStreak.get()
        if (streak > 0) {
            sentiment += streak * 5  // +5 per win
        } else {
            sentiment += streak * 8  // -8 per loss (fear builds faster)
        }
        
        // Big wins push toward greed
        sentiment += sessionBigWins.get() * 5
        
        // Tilt pushes toward fear
        sentiment -= tiltLevel.get() / 4
        
        // Discipline provides stability
        if (disciplineScore.get() >= 70) {
            sentiment = ((sentiment - 50) * 0.7 + 50).toInt()  // Dampen extremes
        }
        
        return sentiment.coerceIn(0, 100)
    }
    
    /**
     * Get sentiment classification.
     */
    fun getSentimentClassification(): String {
        val sentiment = getInternalSentiment()
        return when {
            sentiment <= 24 -> "EXTREME_FEAR"
            sentiment <= 44 -> "FEAR"
            sentiment <= 55 -> "NEUTRAL"
            sentiment <= 75 -> "CONFIDENCE"
            else -> "EUPHORIA"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE & STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class BehaviorState(
        val currentStreak: Int,          // + = wins, - = losses
        val consecutiveWins: Int,
        val consecutiveLosses: Int,
        val tiltLevel: Int,              // 0-100
        val disciplineScore: Int,        // 0-100
        val internalSentiment: Int,      // 0-100
        val sentimentClass: String,
        val tiltProtectionActive: Boolean,
        val tiltProtectionRemaining: Long,  // seconds
        val fluidAdjustment: Double,     // -1.0 to +1.0
        val scoreAdjustment: Int,        // -20 to +20
        val sessionTrades: Int,
        val sessionWins: Int,
        val sessionLosses: Int,
        val sessionBigWins: Int,
        // Milestones
        val tenXCount: Int,
        val hundredXCount: Int,
        val megaPumpCount: Int,
        val bigLossCount: Int,
    ) {
        fun summary(): String = buildString {
            val streakEmoji = when {
                currentStreak >= 5 -> "🔥🔥"
                currentStreak >= 3 -> "🔥"
                currentStreak <= -5 -> "💀💀"
                currentStreak <= -3 -> "💀"
                else -> "📊"
            }
            append("$streakEmoji BEHAVIOR: streak=$currentStreak ")
            append("tilt=$tiltLevel% disc=$disciplineScore% ")
            append("sentiment=$sentimentClass ")
            if (tiltProtectionActive) append("[TILT PROTECTED ${tiltProtectionRemaining}s] ")
            append("fluid=${if (fluidAdjustment >= 0) "+" else ""}${(fluidAdjustment * 100).toInt()}%")
        }
    }
    
    fun getState(): BehaviorState {
        return BehaviorState(
            currentStreak = currentStreak.get(),
            consecutiveWins = consecutiveWins.get(),
            consecutiveLosses = consecutiveLosses.get(),
            tiltLevel = tiltLevel.get(),
            disciplineScore = disciplineScore.get(),
            internalSentiment = getInternalSentiment(),
            sentimentClass = getSentimentClassification(),
            tiltProtectionActive = isTiltProtectionActive(),
            tiltProtectionRemaining = getTiltProtectionRemaining(),
            fluidAdjustment = fluidAdjustment.get(),
            scoreAdjustment = getScoreAdjustment(),
            sessionTrades = sessionTrades.get(),
            sessionWins = sessionWins.get(),
            sessionLosses = sessionLosses.get(),
            sessionBigWins = sessionBigWins.get(),
            tenXCount = tenXCount.get(),
            hundredXCount = hundredXCount.get(),
            megaPumpCount = megaPumpCount.get(),
            bigLossCount = bigLossCount.get(),
        )
    }
    
    private fun logBehaviorState() {
        val state = getState()
        ErrorLogger.info(TAG, state.summary())
    }
    
    /**
     * Load historical behavior from TradeHistoryStore.
     * Call this on startup to restore behavioral context.
     */
    fun loadFromHistory() {
        try {
            val trades = TradeHistoryStore.getAllTrades()
                .filter { it.side == "SELL" }
                .sortedBy { it.ts }
            
            if (trades.isEmpty()) {
                ErrorLogger.info(TAG, "📊 No trade history - starting fresh")
                return
            }
            
            // Reset counters
            tenXCount.set(0)
            hundredXCount.set(0)
            megaPumpCount.set(0)
            bigLossCount.set(0)
            
            // Analyze historical trades
            var tempStreak = 0
            var maxWinStreak = 0
            var maxLossStreak = 0
            
            for (trade in trades) {
                val pnl = trade.pnlPct
                
                // Count milestones
                if (pnl >= HUNDRED_X_PCT) hundredXCount.incrementAndGet()
                if (pnl >= TEN_X_PCT) tenXCount.incrementAndGet()
                if (pnl >= MEGA_PUMP_PCT) megaPumpCount.incrementAndGet()
                if (pnl <= BIG_LOSS_PCT) bigLossCount.incrementAndGet()
                
                // Track streaks
                if (pnl > 2.0) {
                    if (tempStreak > 0) tempStreak++ else tempStreak = 1
                    maxWinStreak = maxOf(maxWinStreak, tempStreak)
                } else if (pnl < -2.0) {
                    if (tempStreak < 0) tempStreak-- else tempStreak = -1
                    maxLossStreak = maxOf(maxLossStreak, -tempStreak)
                }
            }
            
            // Set current streak based on recent trades
            val recentTrades = trades.takeLast(10)
            var recentStreak = 0
            for (trade in recentTrades.reversed()) {
                if (trade.pnlPct > 2.0) {
                    if (recentStreak >= 0) recentStreak++ else break
                } else if (trade.pnlPct < -2.0) {
                    if (recentStreak <= 0) recentStreak-- else break
                }
            }
            
            currentStreak.set(recentStreak)
            if (recentStreak > 0) {
                consecutiveWins.set(recentStreak)
                consecutiveLosses.set(0)
            } else {
                consecutiveWins.set(0)
                consecutiveLosses.set(-recentStreak)
            }
            
            // Calculate discipline based on overall win rate
            val stats = TradeHistoryStore.getStats()
            val winRate = stats.winRate
            disciplineScore.set(when {
                winRate >= 60 -> 70
                winRate >= 50 -> 60
                winRate >= 40 -> 50
                else -> 40
            })
            
            // Set initial tilt based on recent performance
            if (recentStreak <= -3) {
                tiltLevel.set(30)
            }
            
            ErrorLogger.info(TAG, "📊 Loaded behavior from ${trades.size} trades | " +
                "10x=${tenXCount.get()} 100x=${hundredXCount.get()} | " +
                "maxWin=${maxWinStreak} maxLoss=${maxLossStreak} | " +
                "currentStreak=$recentStreak")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load history: ${e.message}")
        }
    }
    
    /**
     * Reset session counters (call on bot restart or daily reset).
     */
    fun resetSession() {
        sessionTrades.set(0)
        sessionWins.set(0)
        sessionLosses.set(0)
        sessionBigWins.set(0)
        
        // Decay tilt and restore discipline
        tiltLevel.set((tiltLevel.get() * 0.5).toInt())
        if (disciplineScore.get() < 50) {
            disciplineScore.set(50)
        }
        
        // Reset fluid adjustment to neutral
        fluidAdjustment.set(0.0)
        
        ErrorLogger.info(TAG, "🔄 Session reset | Tilt decayed to ${tiltLevel.get()}%")
    }
}
