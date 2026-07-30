package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V5.0.6396 — DETERMINISTIC REJECTION DEDUPE.
 *
 * A token rejected only because of score must not be reconsidered until at
 * least one material condition changes. Key:
 *
 *   mint + lane + metricEpoch + finalFloor + hardSafetyVersion
 *
 * Material change triggers (§"DETERMINISTIC REJECTION DEDUPE"):
 *   - score changes by 2 or more points
 *   - pair hydration completes
 *   - liquidity/volume change materially
 *   - holder proof improves
 *   - safety proof version changes
 *   - tactic or lane changes legitimately
 *   - governor floor changes
 *   - cooldown expires (60s)
 *
 * A repeated identical rejection must emit only
 * ENTRY_REJECT_DEDUPE_SUPPRESSED — NEVER another BUY_FAILED and NEVER an
 * executor invocation.
 */
object EntryRejectionDedupeCache6396 {

    const val COOLDOWN_MS: Long = 60_000L
    const val MATERIAL_SCORE_DELTA: Double = 2.0

    data class RejectionKey(
        val mint: String, val lane: String, val metricEpoch: Long,
        val finalFloor: Int, val hardSafetyVersion: String,
    )
    data class RejectionEntry(
        val key: RejectionKey,
        val effectiveScoreAtReject: Double,
        val rejectedAtMs: Long,
        var suppressCount: Long = 0L,
    )

    private val cache = ConcurrentHashMap<RejectionKey, RejectionEntry>()
    val suppressedEmissions = AtomicLong(0L)
    val freshRejections = AtomicLong(0L)

    /**
     * Should we emit a fresh rejection for this candidate, or suppress
     * because we've seen the same conditions recently? Returns true if the
     * caller should emit ENTRY_REJECTED_SCORE_FLOOR; false if it should
     * emit only ENTRY_REJECT_DEDUPE_SUPPRESSED.
     */
    @Synchronized
    fun shouldEmitFresh(
        mint: String,
        lane: String,
        metricEpoch: Long,
        finalFloor: Int,
        hardSafetyVersion: String,
        effectiveScore: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = RejectionKey(mint, lane, metricEpoch, finalFloor, hardSafetyVersion)
        val prev = cache[key]
        if (prev == null) {
            cache[key] = RejectionEntry(key, effectiveScore, nowMs)
            freshRejections.incrementAndGet()
            return true
        }
        // Material score change?
        if (abs(effectiveScore - prev.effectiveScoreAtReject) >= MATERIAL_SCORE_DELTA) {
            cache[key] = RejectionEntry(key, effectiveScore, nowMs)
            freshRejections.incrementAndGet()
            return true
        }
        // Cooldown expired?
        if (nowMs - prev.rejectedAtMs >= COOLDOWN_MS) {
            cache[key] = RejectionEntry(key, effectiveScore, nowMs)
            freshRejections.incrementAndGet()
            return true
        }
        // Suppressed.
        prev.suppressCount += 1L
        suppressedEmissions.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_REJECT_DEDUPE_SUPPRESSED_6396") } catch (_: Throwable) {}
        return false
    }

    fun suppressedCount(mint: String): Long =
        cache.values.filter { it.key.mint == mint }.sumOf { it.suppressCount }

    fun clearMint(mint: String) {
        cache.keys.removeIf { it.mint == mint }
    }

    internal fun clearAllForTest() {
        cache.clear(); suppressedEmissions.set(0L); freshRejections.set(0L)
    }
}
