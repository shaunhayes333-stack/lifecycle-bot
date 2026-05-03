package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.436 — SCORE EXPECTANCY TRACKER (per-layer)
 *
 * Closes the open feedback loop in the meme learning system across EVERY
 * V3 sub-trader (ShitCoin, Moonshot, BlueChip, Quality, Manipulated,
 * CashGen). Before this tracker:
 *
 *   - sub-trader "learning" was a maturity progression (parameters lerp
 *     bootstrap→mature based on totalTrades + WR). Nothing attributed
 *     wins/losses back to the *score range* that produced them.
 *   - 5000 trades stuck at 30% WR with R:R ~2:1 = exact breakeven post-fee.
 *
 * After: every V3 close is bucketed by `(layer, score)`. Per-bucket rolling
 * window of last 200 pnlPct outcomes. An entry can be soft-rejected when
 * its bucket has 25+ samples AND mean pnlPct < -2.0% (provably losing
 * real money on average for that lane in that score range).
 *
 * Per-LAYER buckets prevent cross-contamination — a 60-69 score on
 * ShitCoin is a completely different signal than a 60-69 on BlueChip.
 *
 * Stays exploratory at low samples. Pure in-memory, thread-safe, fail-open.
 */
object ScoreExpectancyTracker {

    private const val TAG = "ScoreExpectancy"

    private const val BUCKET_WIDTH = 10
    private const val WINDOW = 200
    private const val MIN_SAMPLES_FOR_REJECT = 25
    private const val REJECT_MEAN_PNL_PCT = -2.0

    /** Rolling pnlPct windows keyed by "LAYER:bucket". */
    private val windows = ConcurrentHashMap<String, ArrayDeque<Double>>()

    private fun keyOf(layer: String, score: Int): String {
        val s = score.coerceAtLeast(0)
        return "${layer.uppercase()}:${s / BUCKET_WIDTH}"
    }

    /**
     * Record the outcome of a closed trade for [layer] at [score].
     */
    fun record(layer: String, score: Int, pnlPct: Double) {
        try {
            val key = keyOf(layer, score)
            val w = windows.computeIfAbsent(key) { ArrayDeque(WINDOW + 1) }
            synchronized(w) {
                w.addLast(pnlPct)
                while (w.size > WINDOW) w.removeFirst()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "record error: ${e.message}")
        }
    }

    /** Mean pnlPct for [layer]@[score] bucket, or null when under-sampled. */
    fun bucketMean(layer: String, score: Int): Double? {
        val w = windows[keyOf(layer, score)] ?: return null
        synchronized(w) {
            if (w.size < MIN_SAMPLES_FOR_REJECT) return null
            return w.sum() / w.size
        }
    }

    /** Sample count in this bucket (0 if never recorded). */
    fun bucketSamples(layer: String, score: Int): Int {
        val w = windows[keyOf(layer, score)] ?: return 0
        synchronized(w) { return w.size }
    }

    /**
     * Decide whether to reject a candidate entry for [layer]@[score].
     * Allows trade through (returns false) when:
     *   - fewer than MIN_SAMPLES_FOR_REJECT closes in the bucket
     *   - rolling mean pnlPct >= REJECT_MEAN_PNL_PCT
     */
    fun shouldReject(layer: String, score: Int): Boolean {
        val mean = bucketMean(layer, score) ?: return false
        return mean < REJECT_MEAN_PNL_PCT
    }

    /** One-line snapshot for [layer], or all layers if null. */
    fun snapshot(layer: String? = null): String {
        val parts = mutableListOf<String>()
        windows.toSortedMap().forEach { (key, w) ->
            if (layer != null && !key.startsWith("${layer.uppercase()}:")) return@forEach
            synchronized(w) {
                if (w.isNotEmpty()) {
                    val mean = w.sum() / w.size
                    val (lay, bucketStr) = key.split(":")
                    val bucket = bucketStr.toInt()
                    val lo = bucket * BUCKET_WIDTH
                    val hi = lo + BUCKET_WIDTH - 1
                    parts.add("$lay[$lo-$hi]n=${w.size}/μ=${"%+.1f".format(mean)}%")
                }
            }
        }
        return if (parts.isEmpty()) "no samples yet" else parts.joinToString(" ")
    }

    fun reset() { windows.clear() }
}
