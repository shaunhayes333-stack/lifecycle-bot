package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * V5.9.495z34 — rolling 5-minute counter for "observe-don't-purge"
 * activity. The Meme tab displays a tile like:
 *
 *   🛡 24 deferred · 3 background-classed · 6 expired (5m)
 *
 * so the operator can SEE the bot patiently watching candidates
 * instead of feeling like it's stuck.
 */
object DeferActivityTracker {

    enum class Kind { DEFERRED, BACKGROUND_CLASSED, EXPIRED }

    private data class Event(val tsMs: Long, val kind: Kind, val symbol: String)

    private const val WINDOW_MS = 5L * 60_000L
    private const val MAX_EVENTS = 500

    private val lock = ReentrantLock()
    private val events = ArrayDeque<Event>()

    fun record(kind: Kind, symbol: String) {
        val now = System.currentTimeMillis()
        lock.withLock {
            events.addLast(Event(now, kind, symbol))
            trim(now)
        }
    }

    data class Snapshot(
        val deferred: Int,
        val backgroundClassed: Int,
        val expired: Int,
    )

    fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        lock.withLock {
            trim(now)
            var d = 0; var b = 0; var e = 0
            for (ev in events) when (ev.kind) {
                Kind.DEFERRED            -> d++
                Kind.BACKGROUND_CLASSED  -> b++
                Kind.EXPIRED             -> e++
            }
            return Snapshot(deferred = d, backgroundClassed = b, expired = e)
        }
    }

    /** Compact one-line summary for the Meme tab tile. */
    fun summaryLine(): String {
        val s = snapshot()
        return "🛡 ${s.deferred} deferred · ${s.backgroundClassed} background-classed · ${s.expired} expired (5m)"
    }

    private fun trim(now: Long) {
        val cutoff = now - WINDOW_MS
        while (events.isNotEmpty() && events.first().tsMs < cutoff) events.removeFirst()
        while (events.size > MAX_EVENTS) events.removeFirst()
    }
}
