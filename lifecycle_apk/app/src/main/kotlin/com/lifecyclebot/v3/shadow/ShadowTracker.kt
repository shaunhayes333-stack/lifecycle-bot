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
    val capturedAtMs: Long,
    // V5.0.7452 — causal markout state. Mutable because the same tracked
    // decision is marked forward at fixed horizons without creating new rows.
    val markoutsPct7452: MutableMap<Int, Double> = java.util.concurrent.ConcurrentHashMap(),
    var peakPnlPct7452: Double = 0.0,
    var troughPnlPct7452: Double = 0.0,
    var firstStopHitAtMs7452: Long = 0L,
    var firstTpHitAtMs7452: Long = 0L,
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
    
    companion object {
        private val HORIZONS_SEC_7452 = intArrayOf(2, 5, 10, 15, 30, 60, 120, 300, 600)
        private const val SIM_STOP_PCT_7452 = -15.0
        private const val SIM_TP_PCT_7452 = 30.0
    }

    data class MarkoutUpdate7452(
        val newlyRecorded: Map<Int, Double>,
        val firstHit: String?,
    )

    /**
     * Mark a tracked rejection using a price the normal V3 pipeline already
     * has. No provider call is made here.
     */
    fun observePrice7452(
        mint: String,
        currentPrice: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): MarkoutUpdate7452? {
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return null
        val s = tracked[mint] ?: return null
        val start = s.startPrice ?: return null
        if (!start.isFinite() || start <= 0.0) return null
        val pnl = ((currentPrice - start) / start) * 100.0
        val ageMs = (nowMs - s.capturedAtMs).coerceAtLeast(0L)
        s.peakPnlPct7452 = maxOf(s.peakPnlPct7452, pnl)
        s.troughPnlPct7452 = minOf(s.troughPnlPct7452, pnl)
        if (pnl <= SIM_STOP_PCT_7452 && s.firstStopHitAtMs7452 <= 0L) s.firstStopHitAtMs7452 = nowMs
        if (pnl >= SIM_TP_PCT_7452 && s.firstTpHitAtMs7452 <= 0L) s.firstTpHitAtMs7452 = nowMs

        val added = linkedMapOf<Int, Double>()
        for (sec in HORIZONS_SEC_7452) {
            if (ageMs >= sec * 1000L && !s.markoutsPct7452.containsKey(sec)) {
                s.markoutsPct7452[sec] = pnl
                added[sec] = pnl
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_MARKOUT_RECORDED_7452_${sec}S")
                } catch (_: Throwable) {}
            }
        }
        val firstHit = firstHit7452(s)
        return MarkoutUpdate7452(added, firstHit)
    }

    private fun firstHit7452(s: ShadowSnapshot): String? = when {
        s.firstStopHitAtMs7452 > 0L && s.firstTpHitAtMs7452 > 0L ->
            if (s.firstStopHitAtMs7452 < s.firstTpHitAtMs7452) "STOP_FIRST" else "TP_FIRST"
        s.firstStopHitAtMs7452 > 0L -> "STOP_FIRST"
        s.firstTpHitAtMs7452 > 0L -> "TP_FIRST"
        else -> null
    }

    fun firstHitOutcome7452(mint: String): String? = tracked[mint]?.let { firstHit7452(it) }

    fun markoutPct7452(mint: String, horizonSec: Int): Double? =
        tracked[mint]?.markoutsPct7452?.get(horizonSec)

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
