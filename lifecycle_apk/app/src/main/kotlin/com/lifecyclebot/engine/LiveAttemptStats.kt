package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * LiveAttemptStats — rolling 5-minute counter of live-trade attempts.
 *
 * Background: users cannot tell from the dashboard whether the bot is
 * actively hunting live trades or silently skipping them at the size
 * floor. This tracker records every live-execute attempt from each
 * trader (CryptoAlt, TokenizedStocks, etc.) and exposes a compact
 * snapshot for the UI.
 *
 * Outcomes recorded:
 *   • EXECUTED       — trade actually placed on-chain
 *   • FLOOR_SKIPPED  — bailed because wallet below min size floor
 *   • FAILED         — submitted but returned failure (Jupiter error etc.)
 */
object LiveAttemptStats {

    enum class Outcome { EXECUTED, FLOOR_SKIPPED, FAILED }

    private data class Event(val tsMs: Long, val trader: String, val outcome: Outcome)

    private const val WINDOW_MS = 5L * 60_000L  // 5 minutes
    private const val MAX_EVENTS = 500

    private val lock = ReentrantLock()
    private val events = ArrayDeque<Event>()

    fun record(trader: String, outcome: Outcome) {
        val now = System.currentTimeMillis()
        lock.withLock {
            events.addLast(Event(now, trader, outcome))
            trim(now)
        }
    }

    data class Snapshot(
        val attempts: Int,
        val executed: Int,
        val floorSkipped: Int,
        val failed: Int,
    )

    fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        lock.withLock {
            trim(now)
            var ex = 0; var fs = 0; var fa = 0
            for (e in events) when (e.outcome) {
                Outcome.EXECUTED      -> ex++
                Outcome.FLOOR_SKIPPED -> fs++
                Outcome.FAILED        -> fa++
            }
            return Snapshot(attempts = events.size, executed = ex, floorSkipped = fs, failed = fa)
        }
    }

    /** Compact one-line summary for small UI badges. */
    fun summaryLine(): String {
        val s = snapshot()
        return "5m: ${s.attempts} att · ${s.executed} live · ${s.floorSkipped} floor · ${s.failed} fail"
    }

    private fun trim(now: Long) {
        val cutoff = now - WINDOW_MS
        while (events.isNotEmpty() && events.first().tsMs < cutoff) events.removeFirst()
        while (events.size > MAX_EVENTS) events.removeFirst()
    }
}
