package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.436 — HOLD-DURATION EXPECTANCY TRACKER (per-layer)
 *
 * Tracks realised pnlPct by hold-time bucket per V3 sub-trader. Surfaces
 * provably-bad hold windows so checkExit() / time-based gates can be
 * tuned by ground-truth, not hand-coded heuristics.
 *
 * Buckets (minutes): 0-1, 1-3, 3-7, 7-15, 15-30, 30-60, 60-180, 180+
 *
 * Use cases:
 *   - "Quality holds 30-60min are net -3%" → tighten time exit window
 *   - "Moonshot holds >180min are net +47%" → don't flush dead bags too soon
 *
 * Pure in-memory, thread-safe, fail-open.
 */
object HoldDurationTracker {

    private const val TAG = "HoldDuration"

    private const val WINDOW = 200
    private val BUCKET_EDGES = longArrayOf(1, 3, 7, 15, 30, 60, 180)
    // bucket index 0..7 → 0-1, 1-3, 3-7, 7-15, 15-30, 30-60, 60-180, 180+

    private val windows = ConcurrentHashMap<String, ArrayDeque<Double>>()

    private fun bucketIndex(holdMinutes: Long): Int {
        for (i in BUCKET_EDGES.indices) {
            if (holdMinutes < BUCKET_EDGES[i]) return i
        }
        return BUCKET_EDGES.size
    }

    private fun keyOf(layer: String, holdMinutes: Long): String =
        "${layer.uppercase()}:${bucketIndex(holdMinutes)}"

    fun record(layer: String, holdMinutes: Long, pnlPct: Double) {
        try {
            val key = keyOf(layer, holdMinutes)
            val w = windows.computeIfAbsent(key) { ArrayDeque(WINDOW + 1) }
            synchronized(w) {
                w.addLast(pnlPct)
                while (w.size > WINDOW) w.removeFirst()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "record error: ${e.message}")
        }
    }

    fun bucketMean(layer: String, holdMinutes: Long): Double? {
        val w = windows[keyOf(layer, holdMinutes)] ?: return null
        synchronized(w) {
            if (w.size < 25) return null
            return w.sum() / w.size
        }
    }

    fun snapshot(layer: String? = null): String {
        val parts = mutableListOf<String>()
        windows.toSortedMap().forEach { (key, w) ->
            if (layer != null && !key.startsWith("${layer.uppercase()}:")) return@forEach
            synchronized(w) {
                if (w.isNotEmpty()) {
                    val mean = w.sum() / w.size
                    val (lay, idxStr) = key.split(":")
                    val idx = idxStr.toInt()
                    val label = when (idx) {
                        0 -> "0-1m"; 1 -> "1-3m"; 2 -> "3-7m"
                        3 -> "7-15m"; 4 -> "15-30m"; 5 -> "30-60m"
                        6 -> "60-180m"; else -> "180m+"
                    }
                    parts.add("$lay[$label]n=${w.size}/μ=${"%+.1f".format(mean)}%")
                }
            }
        }
        return if (parts.isEmpty()) "no samples yet" else parts.joinToString(" ")
    }

    fun reset() { windows.clear() }
}
