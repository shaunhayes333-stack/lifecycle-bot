package com.lifecyclebot.v3.scoring

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import org.json.JSONObject
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
     * 
     * V5.2 CHANGES:
     * - Paper mode has NO behavior penalty (it's not real money, it's killing learning!)
     * - Live mode has much gentler penalties (we have full sentient learning)
     * - Only big losses (>30%) apply 0.25 penalty to learning progression
     */
    // V5.9.388 — per-asset-class counters so tilt / discipline / streak
    // state isn't contaminated by trades from the wrong asset class. Meme
    // base still drives the legacy global counters (consecutiveLosses /
    // tiltLevel / disciplineScore) so all existing read paths are
    // backwards-compatible.
    private val perAssetTrades = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val perAssetWins   = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val perAssetLosses = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun recordTrade(pnlPct: Double, reason: String, mint: String, isPaperMode: Boolean = true) =
        recordTradeForAsset(pnlPct, reason, mint, isPaperMode, assetClass = "MEME")

    /**
     * V5.9.388 — asset-class-scoped recordTrade. For MEME (the default),
     * behaviour is unchanged — legacy tilt / discipline / streak counters
     * all update. For every other class (SHITCOIN / QUALITY / BLUECHIP /
     * MOONSHOT / TREASURY / PERPS / STOCK / FOREX / METAL / COMMODITY) we
     * only update the per-asset counters, leaving the global tilt state
     * alone. This stops five ShitCoin rug losses from locking the meme
     * trader into PROTECT / tilt-cooldown.
     */
    fun recordTradeForAsset(pnlPct: Double, reason: String, mint: String,
                            isPaperMode: Boolean = true, assetClass: String = "MEME") {
        val cls = assetClass.uppercase().ifBlank { "MEME" }
        perAssetTrades.getOrPut(cls) { AtomicInteger(0) }.incrementAndGet()
        if (pnlPct >= 1.0)      perAssetWins.getOrPut(cls)   { AtomicInteger(0) }.incrementAndGet()
        else if (pnlPct < -1.0) perAssetLosses.getOrPut(cls) { AtomicInteger(0) }.incrementAndGet()
        if (cls != "MEME") return  // Skip global tilt / discipline state for non-meme classes.

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
            // V5.9.320: unified win threshold >= 1.0 (matches Education/Fluid/all traders)
            pnlPct >= 1.0 -> {
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
            // SCRATCH (break even) — matches Education scratch range (-1% to +1%)
            // V5.9.320: was -2.0..2.0, now -1.0..1.0 to match unified thresholds
            pnlPct in -1.0..1.0 -> {
                // Neutral - no streak change
                disciplineScore.addAndGet(1)  // Small bonus for not losing
            }
            
            // ═══════════════════════════════════════════════════════════════
            // LOSSES
            // V5.2: Paper mode = NO penalties (killing learning!)
            // Live mode = Only big losses (>30%) apply 0.25 penalty
            // ═══════════════════════════════════════════════════════════════
            pnlPct <= BIG_LOSS_PCT -> {
                bigLossCount.incrementAndGet()
                sessionLosses.incrementAndGet()
                consecutiveLosses.incrementAndGet()
                consecutiveWins.set(0)
                
                // V5.9.662d — operator clarification: 'its meant to be
                // learning and adjusting. but not that strictly. it needs
                // more range of data to be that sure about its decisions
                // check the path to 5000 trades and beyond.'
                //
                // V5.9.662c zeroed paper penalties entirely — too far.
                // Right model: SCALE the penalty by sample size so the
                // bot is gentle pre-5000 trades (paired with FreeRangeMode
                // WIDE_OPEN_FLOOR_TRADES=3000 / CEIL=5000) and ramps to
                // full strictness from 5000 onwards.
                //
                //   trades <  3000  → 5% of full penalty   (basically off)
                //   3000..4999      → linear 5% → 100%
                //   trades >= 5000  → full original deltas
                //
                // Stats counters above (bigLossCount, sessionLosses,
                // consecutiveLosses) are always incremented so learning
                // STILL happens — only the tilt/discipline reaction scales.
                if (isPaperMode) {
                    val totalTrades = try {
                        com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats().totalSells
                    } catch (_: Throwable) { 0 }
                    val maturityScale = when {
                        totalTrades >= 5000 -> 1.0
                        totalTrades >= 3000 -> 0.05 + 0.95 * (totalTrades - 3000) / 2000.0
                        else                -> 0.05
                    }
                    val discDelta = (-15.0 * maturityScale).toInt()  // full strictness = -15
                    val tiltDelta = (25.0 * maturityScale).toInt()   // full strictness = +25
                    if (discDelta != 0) disciplineScore.addAndGet(discDelta)
                    if (tiltDelta != 0) tiltLevel.addAndGet(tiltDelta)
                    ErrorLogger.debug(
                        TAG,
                        "💀 PAPER BIG LOSS: $mint ${pnlPct.toInt()}% | trades=$totalTrades scale=${"%.2f".format(maturityScale)} disc${discDelta} tilt+${tiltDelta}"
                    )
                } else {
                    // Live mode: Still gentler than before
                    disciplineScore.addAndGet(-10)  // Was -15
                    tiltLevel.addAndGet(15)          // Was 25
                    
                    ErrorLogger.warn(TAG, "💀 LIVE BIG LOSS! $mint ${pnlPct.toInt()}% | Streak: ${consecutiveLosses.get()}")
                    
                    // V5.2: Only losses > 30% apply 0.25 penalty to learning
                    if (pnlPct <= -30.0) {
                        updateFluidPenalty(0.25)  // User spec: big losses over 30%
                        ErrorLogger.warn(TAG, "📉 CATASTROPHIC LOSS (${pnlPct.toInt()}%) - 0.25x learning penalty applied")
                    }
                }
            }
            
            pnlPct < -2.0 -> {
                sessionLosses.incrementAndGet()
                consecutiveLosses.incrementAndGet()
                consecutiveWins.set(0)
                
                // V5.9.662d — same trade-maturity ramp as the BIG_LOSS
                // branch above. -2..-BIG_LOSS_PCT range is small daily
                // wear-and-tear; ramp to full -3 disc / +8 tilt only at
                // 5000 trades.
                if (isPaperMode) {
                    val totalTrades = try {
                        com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats().totalSells
                    } catch (_: Throwable) { 0 }
                    val maturityScale = when {
                        totalTrades >= 5000 -> 1.0
                        totalTrades >= 3000 -> 0.05 + 0.95 * (totalTrades - 3000) / 2000.0
                        else                -> 0.05
                    }
                    val discDelta = (-3.0 * maturityScale).toInt()
                    val tiltDelta = (8.0 * maturityScale).toInt()
                    if (discDelta != 0) disciplineScore.addAndGet(discDelta)
                    if (tiltDelta != 0) tiltLevel.addAndGet(tiltDelta)
                } else {
                    // Live mode: Gentler penalties
                    disciplineScore.addAndGet(-2)  // Was -3
                    tiltLevel.addAndGet(4)         // Was 8
                    
                    // Check for rapid trading after loss (tilt indicator)
                    if (timeSinceLastTrade < RAPID_TRADE_MS && consecutiveLosses.get() >= 2) {
                        tiltLevel.addAndGet(5)  // Was 10
                        ErrorLogger.warn(TAG, "⚠️ RAPID LOSS TRADING detected - potential tilt")
                    }
                    
                    // Loss streak warning - but NO fluid penalty in live (we have full sentient learning)
                    if (consecutiveLosses.get() >= LOSS_STREAK_THRESHOLD) {
                        ErrorLogger.warn(TAG, "🔴 LOSS STREAK: ${consecutiveLosses.get()} consecutive losses")
                        checkTiltProtection()
                        // V5.2: NO updateFluidPenalty here - let sentient learning handle it
                    }
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
        
        // V5.6.28e: Auto-save after behavior state change
        save()
    }

    // V5.9.8: Collective Hive Mind pattern gating
    private val suppressedPatterns = mutableSetOf<String>()
    private val boostedPatterns    = mutableSetOf<String>()

    fun suppressPattern(name: String) { suppressedPatterns.add(name); boostedPatterns.remove(name) }
    fun boostPattern(name: String)    { boostedPatterns.add(name); suppressedPatterns.remove(name) }
    fun isPatternSuppressed(name: String): Boolean = suppressedPatterns.contains(name)
    fun isPatternBoosted(name: String): Boolean    = boostedPatterns.contains(name)

    
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
        
        // V5.2: Heavily reduced penalties during bootstrap/learning phase
        // Learning is about experimentation - don't crush confidence too early
        val learningProgress = try {
            FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }
        
        // V5.2: Much lower penalty multiplier during bootstrap
        // 0.1 at 0% learning → 0.5 at 50% → 1.0 at 100%
        val penaltyMult = when {
            learningProgress < 0.2 -> 0.1   // V5.2: Almost no penalty at bootstrap
            learningProgress < 0.4 -> 0.25  // Light penalty as we learn
            learningProgress < 0.6 -> 0.5   // Half penalty mid-learning
            learningProgress < 0.8 -> 0.75  // Moderate penalty
            else -> 1.0                      // Full penalty when mature
        }
        
        // Tilt penalty (heavily reduced during bootstrap)
        val tilt = tiltLevel.get()
        if (tilt >= 80) score -= (15 * penaltyMult).toInt()
        else if (tilt >= 60) score -= (10 * penaltyMult).toInt()
        else if (tilt >= 40) score -= (5 * penaltyMult).toInt()
        
        // Discipline bonus/penalty
        val discipline = disciplineScore.get()
        if (discipline >= 80) score += 10  // Bonuses stay full
        else if (discipline >= 60) score += 5
        else if (discipline <= 20) {
            // V5.2: Minimal penalty during bootstrap (no trade history = low discipline score)
            score -= (3 * penaltyMult).toInt()  // Was 5
        }
        
        // Current streak effect (bonuses full, penalties heavily scaled during bootstrap)
        val streak = currentStreak.get()
        when {
            streak >= 5 -> score += 8   // 5+ win streak = confidence
            streak >= 3 -> score += 4   // 3+ win streak = momentum
            streak <= -10 -> score -= (8 * penaltyMult).toInt()  // V5.2: Only penalize massive streaks
            streak <= -5 -> score -= (4 * penaltyMult).toInt()   // V5.2: Reduced (was 10)
            streak <= -3 -> score -= (2 * penaltyMult).toInt()   // V5.2: Minimal (was 5)
        }
        
        // Recent big wins boost confidence
        if (sessionBigWins.get() >= 2) score += 5

        // V5.9.11: Symbolic modulation — mood nudges score
        val symScore = try {
            val mood = com.lifecyclebot.engine.SymbolicContext.emotionalState
            when (mood) {
                "PANIC"    -> -8
                "FEARFUL"  -> -4
                "EUPHORIC" -> +4
                "GREEDY"   -> +2
                else       -> 0
            }
        } catch (_: Exception) { 0 }
        score += (symScore * penaltyMult).toInt()

        return score.coerceIn(-18, 22)  // V5.9.11: widened to admit symbolic swing
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

        // V5.9.11: Symbolic edge adds a small confidence tilt
        val symMod = try {
            val edge = com.lifecyclebot.engine.SymbolicContext.edgeStrength
            ((edge - 0.5) * 10.0).toInt()  // -5..+5
        } catch (_: Exception) { 0 }
        mod += symMod

        return mod.coerceIn(-12, 12)
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

        // V5.9.353: EUPHORIA cap when actively bleeding. User log showed
        // sentiment=EUPHORIA while streak=-3 and tilt=8% because historical
        // sessionBigWins overpowered recent loss data. Force-cap at 70
        // (high-CONFIDENCE) whenever the bot is on a losing streak or
        // tilting — you cannot be euphoric while bleeding.
        if (streak <= -2 || tiltLevel.get() >= 30) {
            sentiment = sentiment.coerceAtMost(70)
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
            // V5.9.401 — Sentience hook #5: surface latest post-mortem soft rule.
            try {
                val hint = com.lifecyclebot.engine.SentienceHooks.lastPostMortemHint
                if (hint.isNotBlank()) append(" | 🔬 ${hint.take(60)}")
            } catch (_: Throwable) {}
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
                if (pnl > 0.5) {
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
                if (trade.pnlPct > 0.5) {
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: AGGRESSION TUNING (0-11 scale like a stereo dial)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // 0 = Ultra Defensive, 5 = Normal, 11 = Maximum Aggression
    private val aggressionLevel = AtomicInteger(5)
    private var lastAggressionChangeMs = 0L
    private const val AGGRESSION_CHANGE_COOLDOWN_MS = 5000L  // 5 second minimum between changes
    
    /**
     * Set the aggression level (0-11).
     * This affects all trading behavior across layers.
     * 
     * V5.2: Rate-limited to prevent rapid mid-loop mutations.
     * 
     * 0-2: Defensive (tighter stops, higher quality required, smaller sizes)
     * 3-4: Conservative
     * 5: Normal (balanced)
     * 6-7: Aggressive (looser criteria, larger sizes)
     * 8-9: Very Aggressive
     * 10-11: Maximum ("Goes to 11" - full degen mode)
     */
    fun setAggressionLevel(level: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val clamped = level.coerceIn(0, 11)
        val currentLevel = aggressionLevel.get()
        
        // V5.2: Rate limit to prevent rapid mid-loop mutations
        if (!force && currentLevel == clamped) {
            return  // No change needed
        }
        
        if (!force && now - lastAggressionChangeMs < AGGRESSION_CHANGE_COOLDOWN_MS) {
            ErrorLogger.debug(TAG, "⏳ Aggression change blocked (cooldown): $currentLevel → $clamped")
            return  // Still in cooldown
        }
        
        aggressionLevel.set(clamped)
        lastAggressionChangeMs = now
        
        // Apply fluid strangles based on aggression
        val fluidMod = when (clamped) {
            0 -> -0.4    // Ultra defensive: tighten everything
            1 -> -0.3
            2 -> -0.2
            3 -> -0.1
            4 -> -0.05
            5 -> 0.0     // Normal: neutral
            6 -> 0.05
            7 -> 0.1
            8 -> 0.2
            9 -> 0.3
            10 -> 0.4    // Degen: loosen everything
            11 -> 0.5    // Goes to 11: maximum
            else -> 0.0
        }
        
        // Apply to fluid learning
        try {
            FluidLearningAI.setAggressionModifier(fluidMod)
        } catch (_: Exception) { }
        
        ErrorLogger.info(TAG, "🎚️ Aggression set to $clamped (${getAggressionName(clamped)}) | fluidMod=$fluidMod")
    }
    
    fun getAggressionLevel(): Int = aggressionLevel.get()
    
    fun getAggressionName(level: Int = aggressionLevel.get()): String = when (level) {
        0 -> "ULTRA_DEFENSIVE"
        1 -> "VERY_DEFENSIVE"
        2 -> "DEFENSIVE"
        3 -> "CONSERVATIVE"
        4 -> "SLIGHTLY_CONSERVATIVE"
        5 -> "NORMAL"
        6 -> "SLIGHTLY_AGGRESSIVE"
        7 -> "AGGRESSIVE"
        8 -> "VERY_AGGRESSIVE"
        9 -> "HYPER_AGGRESSIVE"
        10 -> "DEGEN"
        11 -> "GOES_TO_11"
        else -> "NORMAL"
    }
    
    /**
     * Get entry threshold modifier based on aggression.
     * Lower values = easier to enter trades.
     */
    fun getEntryThresholdMod(): Double = when (aggressionLevel.get()) {
        0 -> 15.0    // +15 to entry threshold (harder)
        1 -> 10.0
        2 -> 5.0
        3 -> 3.0
        4 -> 1.0
        5 -> 0.0     // Normal
        6 -> -2.0
        7 -> -5.0
        8 -> -8.0
        9 -> -12.0
        10 -> -15.0  // -15 to entry threshold (easier)
        11 -> -20.0  // Maximum ease
        else -> 0.0
    }
    
    /**
     * Get stop loss modifier based on aggression.
     * Positive = wider stops, Negative = tighter stops.
     */
    fun getStopLossModPct(): Double = when (aggressionLevel.get()) {
        0 -> -3.0    // Tighter stops
        1 -> -2.0
        2 -> -1.0
        3 -> -0.5
        4 -> 0.0
        5 -> 0.0     // Normal
        6 -> 0.5
        7 -> 1.0
        8 -> 2.0
        9 -> 3.0
        10 -> 4.0    // Wider stops
        11 -> 5.0
        else -> 0.0
    }
    
    /**
     * Get sizing multiplier based on aggression.
     */
    fun getSizingMultiplier(): Double = when (aggressionLevel.get()) {
        0 -> 0.5     // Half size
        1 -> 0.6
        2 -> 0.7
        3 -> 0.8
        4 -> 0.9
        5 -> 1.0     // Normal
        6 -> 1.1
        7 -> 1.2
        8 -> 1.3
        9 -> 1.4
        10 -> 1.5    // 1.5x size
        11 -> 1.75   // Maximum size
        else -> 1.0
    }
    
    /**
     * Get minimum quality grade required based on aggression.
     * Returns: A, B, C, D, F
     */
    fun getMinQualityGrade(): String = when (aggressionLevel.get()) {
        0, 1 -> "A"       // Only A grades
        2, 3 -> "B"       // B or better
        4, 5, 6 -> "C"    // C or better (normal)
        7, 8 -> "D"       // D or better
        9, 10, 11 -> "F"  // Accept everything
        else -> "C"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: FULL RESET
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full reset of all behavior state (for dashboard reset button).
     */
    fun reset() {
        currentStreak.set(0)
        consecutiveLosses.set(0)
        consecutiveWins.set(0)
        tiltLevel.set(0)
        disciplineScore.set(50)
        lastTradeTime.set(0)
        
        sessionTrades.set(0)
        sessionWins.set(0)
        sessionLosses.set(0)
        sessionBigWins.set(0)
        
        tiltProtectionUntil = 0
        lastLossStreakEnd = 0
        
        fluidAdjustment.set(0.0)
        
        // Keep milestones (they're historical achievements)
        // tenXCount, hundredXCount, megaPumpCount, bigLossCount
        
        ErrorLogger.info(TAG, "🔄 Full behavior reset")
        save()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6.28e: PERSISTENCE - Save/Restore behavior state across app restarts
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val PREFS_NAME = "behavior_ai_state"
    @Volatile private var ctx: Context? = null
    @Volatile private var lastSaveTime: Long = 0L
    private const val SAVE_THROTTLE_MS = 10_000L  // Only save every 10 seconds max
    
    /**
     * Initialize BehaviorAI with context and restore persisted state.
     * Call from BotService.onCreate() BEFORE trading starts.
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        restore()
        ErrorLogger.info(TAG, "💾 BehaviorAI initialized | streak=${currentStreak.get()} | tilt=${tiltLevel.get()}% | disc=${disciplineScore.get()}%")
    }
    
    /**
     * Save behavior state to SharedPreferences.
     * Throttled to max once per 10 seconds to prevent excessive I/O.
     */
    fun save(force: Boolean = false) {
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        
        // Throttle saves unless forced
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) {
            return
        }
        lastSaveTime = now
        
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject().apply {
                put("current_streak", currentStreak.get())
                put("consecutive_losses", consecutiveLosses.get())
                put("consecutive_wins", consecutiveWins.get())
                put("tilt_level", tiltLevel.get())
                put("discipline_score", disciplineScore.get())
                put("ten_x_count", tenXCount.get())
                put("hundred_x_count", hundredXCount.get())
                put("mega_pump_count", megaPumpCount.get())
                put("big_loss_count", bigLossCount.get())
                put("aggression_level", aggressionLevel.get())
                put("fluid_adjustment", fluidAdjustment.get())
                put("session_trades", sessionTrades.get())
                put("session_wins", sessionWins.get())
                put("session_losses", sessionLosses.get())
                put("session_big_wins", sessionBigWins.get())
                put("saved_at", System.currentTimeMillis())
            }
            prefs.edit().putString("state", obj.toString()).apply()
            ErrorLogger.debug(TAG, "💾 Saved BehaviorAI: streak=${currentStreak.get()} tilt=${tiltLevel.get()}%")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "💾 Save failed: ${e.message}")
        }
    }
    
    /**
     * Restore behavior state from SharedPreferences.
     */
    private fun restore() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("state", null) ?: return
            
            val obj = JSONObject(json)
            
            currentStreak.set(obj.optInt("current_streak", 0))
            consecutiveLosses.set(obj.optInt("consecutive_losses", 0))
            consecutiveWins.set(obj.optInt("consecutive_wins", 0))
            tiltLevel.set(obj.optInt("tilt_level", 0))
            disciplineScore.set(obj.optInt("discipline_score", 50))
            tenXCount.set(obj.optInt("ten_x_count", 0))
            hundredXCount.set(obj.optInt("hundred_x_count", 0))
            megaPumpCount.set(obj.optInt("mega_pump_count", 0))
            bigLossCount.set(obj.optInt("big_loss_count", 0))
            aggressionLevel.set(obj.optInt("aggression_level", 5))
            fluidAdjustment.set(obj.optDouble("fluid_adjustment", 0.0))
            sessionTrades.set(obj.optInt("session_trades", 0))
            sessionWins.set(obj.optInt("session_wins", 0))
            sessionLosses.set(obj.optInt("session_losses", 0))
            sessionBigWins.set(obj.optInt("session_big_wins", 0))
            
            ErrorLogger.info(TAG, "💾 Restored BehaviorAI: streak=${currentStreak.get()} tilt=${tiltLevel.get()}% disc=${disciplineScore.get()}%")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "💾 Restore failed: ${e.message}")
        }
    }
}
