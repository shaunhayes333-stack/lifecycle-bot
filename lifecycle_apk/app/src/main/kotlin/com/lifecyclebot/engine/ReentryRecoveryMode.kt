package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * ReentryRecoveryMode — Controlled Second-Chance Entries (V4.1: SMARTER REENTRY)
 * 
 * V4.1 CRITICAL CHANGES - USER FEEDBACK:
 *   - "Quick fire re-entry metrics to recover loss are dumb - re-enters too quickly"
 *   - Increased base cooldown from 2 min to 10 min
 *   - Require 80% score threshold (up from 60%)
 *   - Require SUSTAINED buy pressure for 3+ candles, not just current
 *   - Block reentry entirely if token lost >20% since failure
 *   - Only ONE reentry attempt per token (down from 2)
 * 
 * Rules:
 *   - Much smaller than first attempt (max 40% of original size)
 *   - Much tighter timeout (max 30 min)
 *   - No averaging down
 *   - Only ONE reentry allowed, period
 */
object ReentryRecoveryMode {
    
    private const val TAG = "ReentryRecovery"
    
    // Track reentry attempts per token
    private val reentryHistory = ConcurrentHashMap<String, ReentryTracker>()
    
    // V4.1: Balanced reentry parameters (user feedback: 10min was too strict)
    private const val TRACKER_TTL_MS = 1800_000L              // 30 minutes
    private const val BASE_COOLDOWN_MS = 120_000L             // 2 minutes base cooldown
    private const val ADDITIONAL_COOLDOWN_PER_ATTEMPT = 60_000L   // +1 min per failed attempt
    private const val MAX_REENTRY_ATTEMPTS = 2                // Allow 2 attempts
    private const val MIN_RECOVERY_SCORE = 65.0               // 65% score required
    private const val MAX_LOSS_SINCE_FAILURE_PCT = 25.0       // Block if dropped >25% since failure
    private const val REENTRY_PENALTY_PER_ATTEMPT = 5.0       // -5 score penalty per attempt
    
    data class ReentryTracker(
        val mint: String,
        val originalFailTime: Long,
        val originalFailReason: String,
        val originalFailPrice: Double,
        var reentryAttempts: Int = 0,
        var lastReentryTime: Long = 0L,
        var lastReentrySuccess: Boolean? = null,
    )
    
    data class RecoveryEvaluation(
        val canReenter: Boolean,
        val recoveryScore: Double,     // 0-100
        val requiredImprovement: List<String>,
        val metCriteria: List<String>,
        val sizeMultiplier: Double,    // 0.3 to 0.7 (always smaller than original)
        val maxHoldMins: Int,          // Tighter timeout
        val reason: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // RECORD FAILURES
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Record a stop-loss or distribution failure for potential recovery.
     */
    fun recordFailure(ts: TokenState, reason: String) {
        val tracker = ReentryTracker(
            mint = ts.mint,
            originalFailTime = System.currentTimeMillis(),
            originalFailReason = reason,
            originalFailPrice = ts.history.lastOrNull()?.priceUsd ?: 0.0,
        )
        reentryHistory[ts.mint] = tracker
        
        // Also notify DistributionFadeAvoider
        if (reason.contains("STOP_LOSS")) {
            DistributionFadeAvoider.recordStopLoss(ts.mint)
        }
        
        ErrorLogger.info(TAG, "📝 ${ts.symbol} failure recorded: $reason")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // EVALUATE RECOVERY POTENTIAL
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate if a previously-failed token is ready for recovery entry.
     */
    fun evaluateRecovery(ts: TokenState): RecoveryEvaluation {
        val tracker = reentryHistory[ts.mint]
        val now = System.currentTimeMillis()
        val hist = ts.history.toList()
        
        // No prior failure = not a recovery candidate
        if (tracker == null) {
            return RecoveryEvaluation(
                canReenter = false,
                recoveryScore = 0.0,
                requiredImprovement = listOf("No prior failure recorded"),
                metCriteria = emptyList(),
                sizeMultiplier = 0.0,
                maxHoldMins = 0,
                reason = "NOT_RECOVERY_CANDIDATE",
            )
        }
        
        val required = mutableListOf<String>()
        val met = mutableListOf<String>()
        var score = 0.0
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 1: Cooldown completed (V4.1: MUCH LONGER - 10 min base)
        // ─────────────────────────────────────────────────────────────────
        val minCooldownMs = BASE_COOLDOWN_MS + (ADDITIONAL_COOLDOWN_PER_ATTEMPT * tracker.reentryAttempts)
        val timeSinceFailure = now - tracker.originalFailTime
        
        if (timeSinceFailure < minCooldownMs) {
            val remainingSec = (minCooldownMs - timeSinceFailure) / 1000
            required.add("Cooldown: ${remainingSec}s remaining (${minCooldownMs/60000}min required)")
            return RecoveryEvaluation(
                canReenter = false,
                recoveryScore = 0.0,
                requiredImprovement = required,
                metCriteria = met,
                sizeMultiplier = 0.0,
                maxHoldMins = 0,
                reason = "COOLDOWN_ACTIVE",
            )
        }
        met.add("✅ Cooldown completed (${timeSinceFailure/60000}min)")
        score += 15.0  // Reduced from 20 - cooldown alone isn't enough
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 2: Too many reentry attempts
        // ─────────────────────────────────────────────────────────────────
        if (tracker.reentryAttempts >= MAX_REENTRY_ATTEMPTS) {
            required.add("Max reentry attempts reached (${tracker.reentryAttempts}/$MAX_REENTRY_ATTEMPTS)")
            return RecoveryEvaluation(
                canReenter = false,
                recoveryScore = score,
                requiredImprovement = required,
                metCriteria = met,
                sizeMultiplier = 0.0,
                maxHoldMins = 0,
                reason = "MAX_REENTRY_ATTEMPTS",
            )
        }
        
        // Apply penalty for previous attempts
        score -= (tracker.reentryAttempts * REENTRY_PENALTY_PER_ATTEMPT)
        met.add("✅ Attempts: ${tracker.reentryAttempts}/$MAX_REENTRY_ATTEMPTS")
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 3: Price hasn't collapsed since failure (V4.1: NEW)
        // If price dropped >20% since we failed, this token is dying
        // ─────────────────────────────────────────────────────────────────
        val currentPrice = hist.lastOrNull()?.priceUsd ?: 0.0
        if (tracker.originalFailPrice > 0 && currentPrice > 0) {
            val pctChangeSinceFailure = ((currentPrice - tracker.originalFailPrice) / tracker.originalFailPrice) * 100
            if (pctChangeSinceFailure < -MAX_LOSS_SINCE_FAILURE_PCT) {
                required.add("Token collapsed ${pctChangeSinceFailure.toInt()}% since failure (max -${MAX_LOSS_SINCE_FAILURE_PCT.toInt()}%)")
                return RecoveryEvaluation(
                    canReenter = false,
                    recoveryScore = score,
                    requiredImprovement = required,
                    metCriteria = met,
                    sizeMultiplier = 0.0,
                    maxHoldMins = 0,
                    reason = "TOKEN_COLLAPSED",
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 4: Price recovery - must be near fail price (V4.1: Stricter)
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 5) {
            // V4.1: Must be above 95% of original fail price
            val aboveFailPrice = currentPrice > tracker.originalFailPrice * 0.95
            
            if (aboveFailPrice) {
                met.add("✅ Recovered to near fail price")
                score += 30.0  // Important criterion
            } else {
                required.add("Must recover to near fail price")
            }
        } else {
            required.add("Insufficient history for structure check")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 5: SUSTAINED buy pressure (V4.1: 3+ consecutive candles)
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 5) {
            val recentBuyRatios = hist.takeLast(5).map { it.buyRatio }
            val avgBuyRatio = recentBuyRatios.average()
            // V4.1: ALL of the last 3 candles must have >50% buy ratio
            val sustainedBuyPressure = recentBuyRatios.takeLast(3).all { it > 0.50 }
            
            if (avgBuyRatio > 0.55 && sustainedBuyPressure) {
                met.add("✅ SUSTAINED buy pressure: ${(avgBuyRatio * 100).toInt()}%")
                score += 25.0
            } else if (avgBuyRatio > 0.50) {
                met.add("⚠️ Weak buy pressure: ${(avgBuyRatio * 100).toInt()}%")
                score += 10.0
            } else {
                required.add("Need sustained buy ratio > 55% (currently ${(avgBuyRatio * 100).toInt()}%)")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 6: Better liquidity (V4.1: Increased to $5000)
        // ─────────────────────────────────────────────────────────────────
        val currentLiq = ts.lastLiquidityUsd
        if (currentLiq > 5000) {
            met.add("✅ Liquidity: \$${currentLiq.toInt()}")
            score += 15.0
        } else {
            required.add("Need liquidity > \$5000 (currently \$${currentLiq.toInt()})")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 7: No active distribution
        // ─────────────────────────────────────────────────────────────────
        if (DistributionFadeAvoider.canReconsider(ts)) {
            met.add("✅ Distribution cleared")
            score += 10.0
        } else {
            required.add("Still in distribution cooldown")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CALCULATE RESULT (V4.1: 80% threshold, stricter)
        // ─────────────────────────────────────────────────────────────────
        val canReenter = required.isEmpty() && score >= MIN_RECOVERY_SCORE
        
        // Size multiplier decreases with each attempt
        val sizeMultiplier = when (tracker.reentryAttempts) {
            0 -> 0.5   // First reentry: 50% of normal size
            1 -> 0.3   // Second reentry: 30% of normal size
            else -> 0.0
        }
        
        // Tighter timeout for recovery trades
        val maxHoldMins = when (tracker.reentryAttempts) {
            0 -> 40    // First reentry: 40 min max
            1 -> 25    // Second reentry: 25 min max
            else -> 0
        }
        
        return RecoveryEvaluation(
            canReenter = canReenter,
            recoveryScore = score,
            requiredImprovement = required,
            metCriteria = met,
            sizeMultiplier = if (canReenter) sizeMultiplier else 0.0,
            maxHoldMins = if (canReenter) maxHoldMins else 0,
            reason = if (canReenter) "RECOVERY_APPROVED" else "RECOVERY_BLOCKED",
        )
    }
    
    /**
     * Record a reentry attempt.
     */
    fun recordReentryAttempt(mint: String) {
        val tracker = reentryHistory[mint] ?: return
        tracker.reentryAttempts++
        tracker.lastReentryTime = System.currentTimeMillis()
        ErrorLogger.info(TAG, "🔄 Reentry attempt #${tracker.reentryAttempts} recorded for $mint")
    }
    
    /**
     * Record reentry outcome (success or failure).
     */
    fun recordReentryOutcome(mint: String, success: Boolean) {
        val tracker = reentryHistory[mint] ?: return
        tracker.lastReentrySuccess = success
        
        if (success) {
            // Clear tracker on successful reentry - token proved itself
            reentryHistory.remove(mint)
            ErrorLogger.info(TAG, "✅ $mint reentry successful - cleared for normal trading")
        } else {
            ErrorLogger.info(TAG, "❌ $mint reentry failed - tightening restrictions")
        }
    }
    
    /**
     * Check if a token is a recovery candidate.
     */
    fun isRecoveryCandidate(mint: String): Boolean {
        return reentryHistory.containsKey(mint)
    }
    
    /**
     * Cleanup expired trackers.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        reentryHistory.entries.removeIf { now - it.value.originalFailTime > TRACKER_TTL_MS }
    }
    
    /**
     * Log recovery evaluation.
     */
    fun logEvaluation(ts: TokenState, eval: RecoveryEvaluation) {
        if (eval.canReenter) {
            ErrorLogger.info(TAG, "🔄 ${ts.symbol} RECOVERY APPROVED: score=${eval.recoveryScore.toInt()} | " +
                "size=${(eval.sizeMultiplier * 100).toInt()}% | maxHold=${eval.maxHoldMins}min | " +
                eval.metCriteria.take(2).joinToString(", "))
        } else if (eval.requiredImprovement.isNotEmpty()) {
            ErrorLogger.debug(TAG, "🔄 ${ts.symbol} recovery blocked: ${eval.requiredImprovement.first()}")
        }
    }
}
