package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6396 — INTAKE AND SCORE DISTRIBUTION TELEMETRY.
 *
 * Rolling score histograms per bucket. The governor may adapt only after
 * sufficient clean samples; baseline floor should sit near the 45th..60th
 * percentile of hard-safety-passed executable candidates while remaining
 * clamped to [12, 22].
 *
 * Buckets (§"INTAKE AND SCORE DISTRIBUTION TELEMETRY"):
 *   HYDRATED_ALL
 *   HARD_SAFETY_PASSED
 *   FDG_BUY_CANDIDATES
 *   LIVE_ADMITTED
 *   LIVE_REJECTED
 *   CONFIRMED_BUYS
 *   CANONICAL_SETTLED_CLOSES
 */
object ScoreDistributionHistogram6396 {

    enum class Bucket {
        HYDRATED_ALL, HARD_SAFETY_PASSED, FDG_BUY_CANDIDATES,
        LIVE_ADMITTED, LIVE_REJECTED, CONFIRMED_BUYS, CANONICAL_SETTLED_CLOSES,
    }

    /** Rolling window; keep at most this many samples per bucket. */
    const val WINDOW_SIZE: Int = 512
    /** Governor may not adapt with fewer than this many clean samples. */
    const val MIN_SAMPLES_FOR_ADAPT: Int = 40

    data class Percentiles(
        val samples: Int,
        val min: Double, val p10: Double, val p25: Double, val median: Double,
        val p75: Double, val p90: Double, val max: Double,
    )

    private data class Ring(val deque: ConcurrentLinkedDeque<Double> = ConcurrentLinkedDeque(),
                            val size: AtomicInteger = AtomicInteger(0))

    private val rings = ConcurrentHashMap<Bucket, Ring>().apply {
        Bucket.values().forEach { put(it, Ring()) }
    }

    fun record(bucket: Bucket, score: Double) {
        val ring = rings.getValue(bucket)
        ring.deque.addLast(score)
        val n = ring.size.incrementAndGet()
        while (n > WINDOW_SIZE && ring.size.get() > WINDOW_SIZE) {
            if (ring.deque.pollFirst() != null) ring.size.decrementAndGet()
        }
    }

    fun percentiles(bucket: Bucket): Percentiles {
        val ring = rings.getValue(bucket)
        val snapshot = ring.deque.toList().sorted()
        if (snapshot.isEmpty())
            return Percentiles(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        fun pct(p: Double): Double {
            val idx = (p * (snapshot.size - 1)).coerceIn(0.0, (snapshot.size - 1).toDouble())
            return snapshot[idx.toInt()]
        }
        return Percentiles(
            samples = snapshot.size,
            min = snapshot.first(), p10 = pct(0.10), p25 = pct(0.25),
            median = pct(0.50), p75 = pct(0.75), p90 = pct(0.90),
            max = snapshot.last(),
        )
    }

    /**
     * Adaptive baseline recommendation. Returns null when the sample count is
     * below MIN_SAMPLES_FOR_ADAPT — the governor must not adapt from noise.
     * The recommendation is clamped to the V5.0.6396 canonical range.
     */
    fun recommendAdaptiveBaseline(): Int? {
        val hs = percentiles(Bucket.HARD_SAFETY_PASSED)
        if (hs.samples < MIN_SAMPLES_FOR_ADAPT) return null
        // Sit near the p45..p60 of hard-safety-passed executable candidates.
        // We approximate with the median which sits at p50.
        val proposed = hs.median.toInt()
        return LiveEntryThresholdAuthority6396.clampFloor(proposed)
    }

    internal fun clearAllForTest() {
        Bucket.values().forEach {
            val r = rings.getValue(it); r.deque.clear(); r.size.set(0)
        }
    }
}
