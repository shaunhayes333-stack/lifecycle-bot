package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.436 — EXIT-REASON EXPECTANCY TRACKER (per-layer)
 *
 * Tracks realised pnlPct by exit reason (TAKE_PROFIT, STOP_LOSS,
 * TRAILING_STOP, TIME_EXIT, RUG_DETECTED, FLAT_EXIT, etc.) per V3
 * sub-trader. Surfaces which exit pathways actually print money.
 *
 * Use cases:
 *   - "ShitCoin TIME_EXIT averages -4.2%" → tighten time-exit gates so
 *     they fire less often on borderline positions
 *   - "Moonshot TRAILING_STOP averages +18%" → keep the trailing system
 *
 * Pure in-memory, thread-safe, fail-open.
 */
object ExitReasonTracker {

    private const val TAG = "ExitReason"

    private const val WINDOW = 200

    private val windows = ConcurrentHashMap<String, ArrayDeque<Double>>()

    private fun keyOf(layer: String, exitReason: String): String =
        "${layer.uppercase()}:${exitReason.uppercase()}"

    fun record(layer: String, exitReason: String, pnlPct: Double) {
        try {
            val key = keyOf(layer, exitReason)
            val w = windows.computeIfAbsent(key) { ArrayDeque(WINDOW + 1) }
            synchronized(w) {
                w.addLast(pnlPct)
                while (w.size > WINDOW) w.removeFirst()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "record error: ${e.message}")
        }
    }

    fun reasonMean(layer: String, exitReason: String): Double? {
        val w = windows[keyOf(layer, exitReason)] ?: return null
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
                    parts.add("$key n=${w.size}/μ=${"%+.1f".format(mean)}%")
                }
            }
        }
        return if (parts.isEmpty()) "no samples yet" else parts.joinToString(" ")
    }

    fun reset() { windows.clear() }
}
