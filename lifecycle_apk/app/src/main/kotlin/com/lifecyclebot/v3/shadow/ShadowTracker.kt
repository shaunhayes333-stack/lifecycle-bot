package com.lifecyclebot.v3.shadow

import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scoring.ScoreCard

/**
 * V3 Shadow Outcome
 * Classification of what happened to tracked candidates
 */
enum class ShadowOutcome {
    BREAKOUT_WINNER,
    FAILED_BREAKOUT,
    RUG,
    SLOW_BLEED,
    BOUNCE_ONLY,
    NO_OPPORTUNITY
}

/**
 * V3 Shadow Snapshot
 * Captured state at tracking time
 */
data class ShadowSnapshot(
    val mint: String,
    val symbol: String,
    val startPrice: Double?,
    val startLiquidity: Double,
    val startScore: Int,
    val startConfidence: Int,
    val reasonTracked: String,
    val capturedAtMs: Long
)

/**
 * V3 Shadow Tracker
 * Tracks near-misses and blocked candidates to learn from outcomes
 */
class ShadowTracker {
    private val tracked = mutableMapOf<String, ShadowSnapshot>()
    
    /**
     * Start tracking a candidate
     */
    fun track(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        confidence: Int,
        reason: String
    ) {
        tracked[candidate.mint] = ShadowSnapshot(
            mint = candidate.mint,
            symbol = candidate.symbol,
            startPrice = candidate.extraDouble("price").takeIf { it > 0 },
            startLiquidity = candidate.liquidityUsd,
            startScore = scoreCard.total,
            startConfidence = confidence,
            reasonTracked = reason,
            capturedAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * V3.2: Track EARLY (before scoring) for known losers
     */
    fun trackEarly(
        candidate: CandidateSnapshot,
        memoryScore: Int,
        reason: String
    ) {
        tracked[candidate.mint] = ShadowSnapshot(
            mint = candidate.mint,
            symbol = candidate.symbol,
            startPrice = candidate.extraDouble("price").takeIf { it > 0 },
            startLiquidity = candidate.liquidityUsd,
            startScore = memoryScore,  // Use memory score as proxy
            startConfidence = 0,       // No confidence calculated yet
            reasonTracked = reason,
            capturedAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * Check if a token is being tracked
     */
    fun isTracked(mint: String): Boolean = tracked.containsKey(mint)
    
    /**
     * Get tracked snapshot
     */
    fun getSnapshot(mint: String): ShadowSnapshot? = tracked[mint]
    
    /**
     * Get all tracked mints
     */
    fun allTracked(): Set<String> = tracked.keys.toSet()
    
    /**
     * Remove from tracking
     */
    fun untrack(mint: String) {
        tracked.remove(mint)
    }
    
    /**
     * Clear old entries (older than ttlMs)
     */
    fun clearOld(ttlMs: Long, nowMs: Long = System.currentTimeMillis()) {
        tracked.entries.removeIf { nowMs - it.value.capturedAtMs > ttlMs }
    }
}
