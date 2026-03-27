package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * ReentryRecoveryMode — Controlled Second-Chance Entries
 * 
 * Different from ordinary pullback mode. This handles tokens that:
 *   - Were previously blocked by stop-loss or distribution guard
 *   - Have completed cooldown period
 *   - Show materially improved structure
 *   - Have recovered buy pressure
 *   - Have stabilized liquidity
 * 
 * Rules:
 *   - Smaller than first attempt
 *   - Tighter timeout
 *   - No averaging down
 *   - Only ONE reentry per token unless it proves itself
 */
object ReentryRecoveryMode {
    
    private const val TAG = "ReentryRecovery"
    
    // Track reentry attempts per token
    private val reentryHistory = ConcurrentHashMap<String, ReentryTracker>()
    private const val TRACKER_TTL_MS = 600_000L  // 10 minutes
    
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
        // CHECK 1: Cooldown completed
        // ─────────────────────────────────────────────────────────────────
        val minCooldownMs = 120_000L + (60_000L * tracker.reentryAttempts)  // 2 min + 1 min per attempt
        val timeSinceFailure = now - tracker.originalFailTime
        
        if (timeSinceFailure < minCooldownMs) {
            required.add("Cooldown: ${(minCooldownMs - timeSinceFailure) / 1000}s remaining")
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
        met.add("✅ Cooldown completed")
        score += 20.0
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 2: Too many reentry attempts
        // ─────────────────────────────────────────────────────────────────
        if (tracker.reentryAttempts >= 2) {
            required.add("Max reentry attempts reached (${tracker.reentryAttempts})")
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
        met.add("✅ Attempts: ${tracker.reentryAttempts}/2")
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 3: New higher low or reclaim
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 5) {
            val prices = hist.takeLast(5).map { it.priceUsd }
            val currentPrice = prices.lastOrNull() ?: 0.0
            val recentLow = prices.minOrNull() ?: 0.0
            
            // Must be above original fail price OR showing higher low structure
            val aboveFailPrice = currentPrice > tracker.originalFailPrice
            val higherLow = prices.dropLast(1).minOrNull()?.let { recentLow > it * 0.98 } ?: false
            
            if (aboveFailPrice) {
                met.add("✅ Above fail price")
                score += 25.0
            } else if (higherLow) {
                met.add("✅ Higher low forming")
                score += 20.0
            } else {
                required.add("Need higher low or reclaim above fail price")
            }
        } else {
            required.add("Insufficient history for structure check")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 4: Improved buy pressure
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 3) {
            val recentBuyRatios = hist.takeLast(3).map { it.buyRatio }
            val avgBuyRatio = recentBuyRatios.average()
            val isImproving = recentBuyRatios.zipWithNext { a, b -> b >= a }.count { it } >= 1
            
            if (avgBuyRatio > 0.52 && isImproving) {
                met.add("✅ Buy pressure recovered: ${(avgBuyRatio * 100).toInt()}%")
                score += 25.0
            } else if (avgBuyRatio > 0.48) {
                met.add("✅ Buy pressure stabilizing")
                score += 15.0
            } else {
                required.add("Need buy ratio > 48% (currently ${(avgBuyRatio * 100).toInt()}%)")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 5: Better liquidity than at failure
        // ─────────────────────────────────────────────────────────────────
        val currentLiq = ts.lastLiquidityUsd
        if (currentLiq > 3000) {
            met.add("✅ Liquidity: $${currentLiq.toInt()}")
            score += 15.0
        } else {
            required.add("Need liquidity > $3000 (currently $${currentLiq.toInt()})")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 6: No active distribution
        // ─────────────────────────────────────────────────────────────────
        if (DistributionFadeAvoider.canReconsider(ts)) {
            met.add("✅ Distribution cleared")
            score += 15.0
        } else {
            required.add("Still in distribution cooldown")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CALCULATE RESULT
        // ─────────────────────────────────────────────────────────────────
        val canReenter = required.isEmpty() && score >= 60
        
        // Size multiplier decreases with each attempt
        val sizeMultiplier = when (tracker.reentryAttempts) {
            0 -> 0.6   // First reentry: 60% of normal size
            1 -> 0.4   // Second reentry: 40% of normal size
            else -> 0.0
        }
        
        // Tighter timeout for recovery trades
        val maxHoldMins = when (tracker.reentryAttempts) {
            0 -> 45    // First reentry: 45 min
            1 -> 30    // Second reentry: 30 min
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
