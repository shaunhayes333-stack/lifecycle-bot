package com.lifecyclebot.engine.runtime

import android.os.SystemClock
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1323 — UI Refresh Throttle Gate (P0-1 + P0-2 surgical).
 *
 * Activities consult this gate before doing a full dashboard rebuild
 * (buildFullDashboard / renderTokenList / renderNetworkSignals). The
 * gate enforces a minimum interval between full rebuilds per surface
 * — by default 1000ms while the bot is active. User-initiated taps
 * use force=true to bypass.
 *
 * This is INTENTIONALLY a band-aid before the proper RecyclerView
 * conversion (Phase 2 of the operator's UI mandate). It directly
 * addresses the ANR symptom (full rebuild churn) without rewriting
 * 6 activities at once.
 *
 * NEVER blocks rendering forever — gate auto-clears every 5s so a
 * stuck `lastRender` timestamp can't kill the UI.
 */
object UiRefreshGate {

    private const val DEFAULT_MIN_INTERVAL_MS = 1000L
    private const val MAX_SUPPRESSION_MS = 5000L  // hard ceiling

    private val lastRenderMs = ConcurrentHashMap<String, AtomicLong>()
    private val suppressedCounts = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Returns true if the surface should render now, false if it should skip.
     * Always returns true on `force=true` (user action) or if more than
     * MAX_SUPPRESSION_MS has elapsed.
     */
    fun shouldRender(surface: String, force: Boolean = false, minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        if (force) {
            stamp(surface)
            return true
        }
        val now = SystemClock.uptimeMillis()
        val cell = lastRenderMs.computeIfAbsent(surface) { AtomicLong(0L) }
        val prev = cell.get()
        val elapsed = now - prev
        if (prev == 0L || elapsed >= minIntervalMs || elapsed >= MAX_SUPPRESSION_MS) {
            // Try to claim this render slot atomically.
            if (cell.compareAndSet(prev, now)) {
                return true
            }
        }
        // Suppressed — bump counter for visibility in snapshot.
        suppressedCounts.computeIfAbsent(surface) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("UI_REFRESH_SUPPRESSED|$surface") } catch (_: Throwable) {}
        return false
    }

    /** Force-stamp a render (used by code paths that already did the work). */
    fun stamp(surface: String) {
        lastRenderMs.computeIfAbsent(surface) { AtomicLong(0L) }.set(SystemClock.uptimeMillis())
    }

    fun snapshotSuppressed(): Map<String, Long> = suppressedCounts.mapValues { it.value.get() }

    fun resetForTests() {
        lastRenderMs.clear()
        suppressedCounts.clear()
    }
}
