package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z43 — operator spec item D.
 *
 * Forensics export showed RAPID_TRAILING_STOP firing SELL_BLOCKED_ALREADY_IN_PROGRESS
 * 20+ times in a single tick, drowning real signals. This guard suppresses
 * duplicate "blocked" log emissions when:
 *   • the same mint is already being processed (single-flight),
 *   • the same priority reason has been logged within COOLDOWN_MS.
 *
 * Higher-priority reasons can ALWAYS punch through immediately — for
 * example, a RUG_DRAIN must not be silenced by a previous RAPID_TRAILING
 * suppression.
 */
object SellSpamGuard {

    private const val COOLDOWN_MS = 30_000L

    private data class Entry(val reason: String, val priority: Int, val lastMs: Long)
    private val state = ConcurrentHashMap<String, Entry>()

    /** Lower number = higher priority. Mirrors the operator's intent. */
    fun priorityFor(reason: String?): Int {
        val r = (reason ?: "").uppercase()
        return when {
            r.contains("RUG") || r.contains("CATASTROPHE") || r.contains("HARD_FLOOR") -> 0
            r.contains("MANUAL")                                                       -> 1
            r.contains("STOP_LOSS") || r.contains("HARD_STOP")                         -> 2
            r.contains("PROFIT_LOCK") || r.contains("PARTIAL") || r.contains("TAKE_PROFIT") -> 3
            r.contains("TRAIL")                                                        -> 4
            else                                                                       -> 5
        }
    }

    /**
     * Returns true when the caller SHOULD emit the "blocked" log line, false
     * when it should be suppressed. Only the highest-priority pending reason
     * gets through every COOLDOWN_MS per mint.
     */
    fun shouldLogBlocked(mint: String, reason: String?): Boolean {
        val p = priorityFor(reason)
        val now = System.currentTimeMillis()
        val prev = state[mint]
        if (prev == null) {
            state[mint] = Entry(reason.orEmpty(), p, now)
            return true
        }
        // Higher-priority reason → always log + replace.
        if (p < prev.priority) {
            state[mint] = Entry(reason.orEmpty(), p, now)
            return true
        }
        // Same/lower priority within cooldown → suppress.
        if (now - prev.lastMs < COOLDOWN_MS) return false
        // Outside cooldown → log + bump.
        state[mint] = Entry(reason.orEmpty(), p, now)
        return true
    }

    /** Cleared when the actual sell starts or completes. */
    fun clear(mint: String) { state.remove(mint) }
}
