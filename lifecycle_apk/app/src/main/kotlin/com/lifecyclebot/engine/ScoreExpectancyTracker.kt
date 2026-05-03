package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.435 — SCORE EXPECTANCY TRACKER
 *
 * Closes the open feedback loop in the meme learning system.
 *
 * BEFORE: ShitCoinTraderAI's "learning" was purely a maturity progression
 * (parameters lerp from bootstrap→mature based on totalTrades + WR). Nothing
 * actually attributed wins/losses back to the *score range* that produced
 * them. With R:R ~2:1 and ~5000 trades stuck at 30% WR, the bot was
 * net-bleeding while never narrowing toward profitable score brackets.
 *
 * AFTER: every V3 close is bucketed by its entry score (10-pt buckets:
 * 0-9, 10-19, …, 90-99, 100+). For each bucket we maintain a rolling
 * window of the last N pnlPct outcomes. An entry can be soft-rejected if
 * its bucket has a clear negative expectancy AND we have enough samples.
 *
 * The bot stays exploratory at low samples (no rejection until 25+ closes
 * in the bucket), and only avoids buckets whose mean pnlPct is below
 * -2.0% — i.e. the bucket is provably losing real money on average.
 *
 * Pure in-memory, thread-safe, fail-open.
 */
object ScoreExpectancyTracker {

    private const val TAG = "ScoreExpectancy"

    /** Width of one score bucket (pts). */
    private const val BUCKET_WIDTH = 10

    /** Per-bucket rolling window size. */
    private const val WINDOW = 200

    /** Need at least this many closed trades in a bucket before we trust the mean. */
    private const val MIN_SAMPLES_FOR_REJECT = 25

    /** Bucket mean pnlPct must be below this to soft-reject new entries. */
    private const val REJECT_MEAN_PNL_PCT = -2.0

    /** Rolling pnlPct windows keyed by bucket id. */
    private val windows = ConcurrentHashMap<Int, ArrayDeque<Double>>()

    private fun bucketOf(score: Int): Int {
        val s = score.coerceAtLeast(0)
        return s / BUCKET_WIDTH
    }

    /**
     * Record the outcome of a closed trade.
     * @param score the entry score that was used to qualify the trade
     * @param pnlPct realised P&L of the trade in percent (positive = win)
     */
    fun record(score: Int, pnlPct: Double) {
        try {
            val bucket = bucketOf(score)
            val w = windows.computeIfAbsent(bucket) { ArrayDeque(WINDOW + 1) }
            synchronized(w) {
                w.addLast(pnlPct)
                while (w.size > WINDOW) w.removeFirst()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "record error: ${e.message}")
        }
    }

    /**
     * @return rolling mean pnlPct for the given score bucket, or null
     * when we don't have enough samples yet (stay exploratory).
     */
    fun bucketMean(score: Int): Double? {
        val w = windows[bucketOf(score)] ?: return null
        synchronized(w) {
            if (w.size < MIN_SAMPLES_FOR_REJECT) return null
            return w.sum() / w.size
        }
    }

    /**
     * @return number of closed trades currently held in this score's bucket.
     */
    fun bucketSamples(score: Int): Int {
        val w = windows[bucketOf(score)] ?: return 0
        synchronized(w) { return w.size }
    }

    /**
     * Decide whether to reject a candidate entry purely on rolling
     * expectancy of its score bucket. Returns false (allow) when:
     *   - we have fewer than MIN_SAMPLES_FOR_REJECT closes in the bucket
     *   - the rolling mean pnlPct is at or above REJECT_MEAN_PNL_PCT
     */
    fun shouldReject(score: Int): Boolean {
        val mean = bucketMean(score) ?: return false  // fail-open, exploratory
        return mean < REJECT_MEAN_PNL_PCT
    }

    /**
     * One-line snapshot of all buckets with samples — for log / UI.
     */
    fun snapshot(): String {
        val parts = mutableListOf<String>()
        windows.toSortedMap().forEach { (bucket, w) ->
            synchronized(w) {
                if (w.isNotEmpty()) {
                    val mean = w.sum() / w.size
                    val lo = bucket * BUCKET_WIDTH
                    val hi = lo + BUCKET_WIDTH - 1
                    parts.add("[$lo-$hi]n=${w.size}/μ=${"%+.1f".format(mean)}%")
                }
            }
        }
        return if (parts.isEmpty()) "no samples yet" else parts.joinToString(" ")
    }

    /** Wipe all buckets — used by tests / journal-clear. */
    fun reset() {
        windows.clear()
    }
}
