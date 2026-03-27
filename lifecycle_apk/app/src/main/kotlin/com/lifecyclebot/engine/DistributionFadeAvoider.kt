package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Candle
import java.util.concurrent.ConcurrentHashMap

/**
 * DistributionFadeAvoider — Anti-Buy Guardrail for Damaged Tokens
 * 
 * This is NOT a buy mode. It is an anti-buy guardrail that:
 *   - Downgrades score sharply after recent stop-loss
 *   - Suppresses raw strategy buy spam for cooldown period
 *   - Requires reclaim + improved buy% before reactivation
 *   - Blocks if distribution persists across multiple scans
 * 
 * Triggers:
 *   - Recent DISTRIBUTION edge hit
 *   - Falling buy%
 *   - Bounce attempts on weakening liquidity
 *   - Prior stop-loss inside cooldown window
 *   - Local highs getting sold quickly
 *   - Volume expansion without follow-through
 */
object DistributionFadeAvoider {
    
    private const val TAG = "DistFade"
    
    // Track distribution events per token
    private val distributionHistory = ConcurrentHashMap<String, DistributionTracker>()
    private const val TRACKER_TTL_MS = 300_000L  // 5 minutes
    
    data class DistributionTracker(
        val firstSeen: Long,
        var hitCount: Int = 1,
        var lastHit: Long = System.currentTimeMillis(),
        var lastBuyRatio: Double = 0.5,
        var peakPrice: Double = 0.0,
        var stopLossTime: Long = 0L,
    )
    
    data class FadeResult(
        val shouldBlock: Boolean,
        val reason: String?,
        val scoreMultiplier: Double,  // 0.0 to 1.0 (1.0 = no penalty)
        val cooldownRemainingMs: Long,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN EVALUATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate if a token is in distribution fade state.
     * Call this BEFORE strategy scoring to potentially block or penalize.
     */
    fun evaluate(ts: TokenState, currentEdge: String? = null): FadeResult {
        val mint = ts.mint
        val now = System.currentTimeMillis()
        val hist = ts.history.toList()
        
        // Get or create tracker
        val tracker = distributionHistory.getOrPut(mint) {
            DistributionTracker(
                firstSeen = now,
                peakPrice = hist.maxOfOrNull { it.priceUsd } ?: 0.0,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 1: Recent stop-loss (hardest block)
        // ─────────────────────────────────────────────────────────────────
        val stopLossCooldownMs = 180_000L  // 3 minutes
        if (tracker.stopLossTime > 0) {
            val timeSinceStop = now - tracker.stopLossTime
            if (timeSinceStop < stopLossCooldownMs) {
                return FadeResult(
                    shouldBlock = true,
                    reason = "STOP_LOSS_COOLDOWN: ${(stopLossCooldownMs - timeSinceStop) / 1000}s remaining",
                    scoreMultiplier = 0.0,
                    cooldownRemainingMs = stopLossCooldownMs - timeSinceStop,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 2: Distribution edge active
        // ─────────────────────────────────────────────────────────────────
        if (currentEdge?.uppercase()?.contains("DISTRIBUTION") == true) {
            tracker.hitCount++
            tracker.lastHit = now
            
            // Multiple distribution hits = stronger block
            if (tracker.hitCount >= 2) {
                val blockDurationMs = 60_000L * tracker.hitCount  // 1 min per hit
                return FadeResult(
                    shouldBlock = true,
                    reason = "DISTRIBUTION_REPEATED: ${tracker.hitCount} hits",
                    scoreMultiplier = 0.0,
                    cooldownRemainingMs = blockDurationMs,
                )
            }
            
            // Single distribution hit = heavy penalty
            return FadeResult(
                shouldBlock = false,
                reason = "DISTRIBUTION_ACTIVE: penalized",
                scoreMultiplier = 0.3,
                cooldownRemainingMs = 60_000L,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 3: Buy ratio degrading
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 3) {
            val recentBuyRatios = hist.takeLast(3).map { it.buyRatio }
            val avgBuyRatio = recentBuyRatios.average()
            val isFalling = recentBuyRatios.zipWithNext { a, b -> b < a }.count { it } >= 2
            
            if (avgBuyRatio < 0.40 && isFalling) {
                return FadeResult(
                    shouldBlock = false,
                    reason = "FALLING_BUY_RATIO: ${(avgBuyRatio * 100).toInt()}%",
                    scoreMultiplier = 0.5,
                    cooldownRemainingMs = 30_000L,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 4: Dead bounce pattern (price spike then immediate sell)
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 5) {
            val prices = hist.takeLast(5).map { it.priceUsd }
            val recentHigh = prices.maxOrNull() ?: 0.0
            val current = prices.lastOrNull() ?: 0.0
            
            // Update peak
            if (recentHigh > tracker.peakPrice) {
                tracker.peakPrice = recentHigh
            }
            
            // Check for rejected bounce
            val rejectionPct = if (tracker.peakPrice > 0) {
                ((tracker.peakPrice - current) / tracker.peakPrice) * 100
            } else 0.0
            
            val lastCandle = hist.lastOrNull()
            val volumeSpikeNoFollow = lastCandle != null && 
                lastCandle.vol > hist.dropLast(1).takeLast(3).map { it.vol }.average() * 1.5 &&
                lastCandle.buyRatio < 0.45
            
            if (rejectionPct > 20 && volumeSpikeNoFollow) {
                return FadeResult(
                    shouldBlock = false,
                    reason = "DEAD_BOUNCE: high rejected -${rejectionPct.toInt()}%",
                    scoreMultiplier = 0.4,
                    cooldownRemainingMs = 45_000L,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 5: Liquidity draining during bounce attempt
        // ─────────────────────────────────────────────────────────────────
        val currentLiq = ts.lastLiquidityUsd
        if (tracker.hitCount > 0 && currentLiq < 3000) {
            return FadeResult(
                shouldBlock = true,
                reason = "BOUNCE_ON_DRAINING_LIQ: $${currentLiq.toInt()}",
                scoreMultiplier = 0.0,
                cooldownRemainingMs = 120_000L,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────
        // NO FADE DETECTED - Clear old tracker if clean
        // ─────────────────────────────────────────────────────────────────
        if (now - tracker.lastHit > TRACKER_TTL_MS) {
            distributionHistory.remove(mint)
        }
        
        return FadeResult(
            shouldBlock = false,
            reason = null,
            scoreMultiplier = 1.0,
            cooldownRemainingMs = 0L,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // RECORD EVENTS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Record a stop-loss event for a token.
     */
    fun recordStopLoss(mint: String) {
        val tracker = distributionHistory.getOrPut(mint) {
            DistributionTracker(firstSeen = System.currentTimeMillis())
        }
        tracker.stopLossTime = System.currentTimeMillis()
        tracker.hitCount++
        ErrorLogger.info(TAG, "🛑 Stop-loss recorded for $mint - cooldown started")
    }
    
    /**
     * Record a distribution edge detection.
     */
    fun recordDistribution(mint: String) {
        val tracker = distributionHistory.getOrPut(mint) {
            DistributionTracker(firstSeen = System.currentTimeMillis())
        }
        tracker.hitCount++
        tracker.lastHit = System.currentTimeMillis()
    }
    
    /**
     * Check if token can be reconsidered after cooldown.
     * Requires material improvement in conditions.
     */
    fun canReconsider(ts: TokenState): Boolean {
        val tracker = distributionHistory[ts.mint] ?: return true
        val now = System.currentTimeMillis()
        val hist = ts.history.toList()
        
        // Must have waited minimum cooldown
        val minCooldown = 60_000L * tracker.hitCount
        if (now - tracker.lastHit < minCooldown) return false
        
        // Must show improved buy ratio
        val currentBuyRatio = hist.lastOrNull()?.buyRatio ?: 0.5
        if (currentBuyRatio < 0.50) return false
        
        // Must have stable/improving liquidity
        if (ts.lastLiquidityUsd < 2500) return false
        
        // Clear tracker on reconsideration
        distributionHistory.remove(ts.mint)
        ErrorLogger.info(TAG, "✅ ${ts.symbol} cleared for reconsideration")
        return true
    }
    
    /**
     * Cleanup expired trackers.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        distributionHistory.entries.removeIf { now - it.value.lastHit > TRACKER_TTL_MS * 2 }
    }
}
