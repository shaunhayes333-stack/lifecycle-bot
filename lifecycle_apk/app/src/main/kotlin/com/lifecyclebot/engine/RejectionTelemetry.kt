package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.633 — Reject-Reason Aggregator
 *
 * Operator forensics need: 1518 mints scanned in 24h but only 85 trades
 * actually executed. The ~95% drop-off was opaque — every layer
 * (FDG / V3 / Treasury / ShitCoin / Moonshot / BlueChip / Quality / Express
 * / Dip / Manip / Bridge / Safety / Slippage) writes its own log line, but
 * nothing aggregated which gate is the loudest dampener.
 *
 * This singleton records every rejection by (layer, normalised reason)
 * and exposes a sorted top-N summary so the bot can periodically log
 * "Top 5 reject reasons (last 5min): low_v3_score=423 (42%), …" — and
 * the UI can later surface the top-1 in real time.
 *
 * Cost: a single ConcurrentHashMap.compute per reject. Negligible.
 *
 * Window strategy: dual-tracked.
 *   • sessionCounts — never resets, used for cumulative ratios.
 *   • windowCounts  — resets every [WINDOW_MS] (5 min) so the periodic
 *     dump shows what's choking us *right now*, not what choked us at
 *     boot.
 */
object RejectionTelemetry {

    private const val TAG = "RejectTelemetry"
    private const val WINDOW_MS = 5 * 60_000L

    private val sessionCounts = ConcurrentHashMap<String, AtomicLong>()
    private val windowCounts  = ConcurrentHashMap<String, AtomicLong>()
    private val totalSession = AtomicLong(0)
    private val totalWindow  = AtomicLong(0)
    @Volatile private var windowStartMs = System.currentTimeMillis()

    /** Normalise a reason string to a small bounded set so we don't blow
     *  up the map with mint-specific or numeric chatter. Keep the leading
     *  upper-snake-case "key" and drop any trailing variable text. */
    private fun normalise(reason: String): String {
        if (reason.isBlank()) return "unspecified"
        val trimmed = reason.trim().take(80)
        // Collapse common suffixes like ": 0.42 < 0.50" or " (mint=…)" into
        // their key. We keep the first 6 words max — enough to disambiguate
        // (e.g. "low fluid conf", "blacklisted by safety", "manip suspect").
        val words = trimmed.split(Regex("[\\s|:=()\\[\\]]+"))
            .filter { it.isNotBlank() }
            .take(6)
        if (words.isEmpty()) return "unspecified"
        // If any word looks like a number, replace with "N" so
        // "score 23 < 50" and "score 47 < 50" merge.
        val key = words.joinToString("_") { w ->
            if (w.matches(Regex("-?\\d+(\\.\\d+)?%?"))) "N" else w.lowercase()
        }
        return key.take(60)
    }

    /**
     * Record a rejection. Layer = which subsystem (V3, FDG, MOONSHOT, …);
     * reason = the explanation string the layer would normally log.
     */
    fun record(layer: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - windowStartMs > WINDOW_MS) {
            // Roll the window. Simple snapshot-and-reset; not exact but
            // operator-grade is fine.
            windowCounts.clear()
            totalWindow.set(0)
            windowStartMs = now
        }
        val key = "$layer:${normalise(reason)}"
        sessionCounts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        windowCounts.computeIfAbsent(key)  { AtomicLong(0) }.incrementAndGet()
        totalSession.incrementAndGet()
        totalWindow.incrementAndGet()
    }

    data class TopRow(val key: String, val count: Long, val pct: Double)

    /** Top-N reasons in the rolling 5-min window, sorted desc. */
    fun topWindow(n: Int = 5): List<TopRow> {
        val total = totalWindow.get().coerceAtLeast(1)
        return windowCounts.entries
            .asSequence()
            .map { TopRow(it.key, it.value.get(), it.value.get() * 100.0 / total) }
            .sortedByDescending { it.count }
            .take(n)
            .toList()
    }

    /** Top-N reasons since boot, sorted desc. */
    fun topSession(n: Int = 5): List<TopRow> {
        val total = totalSession.get().coerceAtLeast(1)
        return sessionCounts.entries
            .asSequence()
            .map { TopRow(it.key, it.value.get(), it.value.get() * 100.0 / total) }
            .sortedByDescending { it.count }
            .take(n)
            .toList()
    }

    fun totalSessionCount(): Long = totalSession.get()
    fun totalWindowCount(): Long  = totalWindow.get()

    /** One-line operator summary for periodic log dumps. */
    fun summaryLine(top: Int = 5): String {
        val rows = topWindow(top)
        val total = totalWindow.get()
        if (rows.isEmpty() || total == 0L) {
            return "📊 RejectStats (5m): no rejections yet"
        }
        val parts = rows.joinToString(" · ") { r ->
            "${r.key}=${r.count}(${"%.0f".format(r.pct)}%)"
        }
        return "📊 RejectStats (5m, total=$total): $parts"
    }

    fun reset() {
        sessionCounts.clear()
        windowCounts.clear()
        totalSession.set(0)
        totalWindow.set(0)
        windowStartMs = System.currentTimeMillis()
    }
}
