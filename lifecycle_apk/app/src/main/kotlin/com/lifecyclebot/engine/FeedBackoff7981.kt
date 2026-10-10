package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7981 — a feed that keeps failing is asked less often.
 *
 * 5.0.7976: pump.fun king-of-the-hill answered 404 on every poll and the callouts
 * API answered 530 (Cloudflare origin down) on every tick; each attempt still paid a
 * TLS handshake and a thread. After [FREE_FAILS] consecutive failures a feed waits
 * 1, 2, 4 ... up to 30 minutes between attempts; one success resets it.
 */
object FeedBackoff7981 {

    private const val FREE_FAILS = 2
    private const val BASE_MS = 60_000L
    private const val MAX_MS = 30L * 60_000L

    /** Pure: wait after [consecutiveFails] failures (0 while under the free allowance). */
    fun waitMs7981(consecutiveFails: Int): Long {
        if (consecutiveFails < FREE_FAILS) return 0L
        val steps = (consecutiveFails - FREE_FAILS).coerceAtMost(10)
        return (BASE_MS shl steps).coerceAtMost(MAX_MS)
    }

    private class State(@Volatile var fails: Int = 0, @Volatile var lastFailMs: Long = 0L, @Volatile var skipped: Long = 0L)

    private val feeds = ConcurrentHashMap<String, State>()

    /** True when [feed] may be called now; a refused attempt is counted. */
    fun allow7981(feed: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = feeds[feed] ?: return true
        val ok = nowMs - s.lastFailMs >= waitMs7981(s.fails)
        if (!ok) s.skipped++
        return ok
    }

    fun ok7981(feed: String) { feeds[feed]?.fails = 0 }

    fun fail7981(feed: String, nowMs: Long = System.currentTimeMillis()) {
        val s = feeds.getOrPut(feed) { State() }
        s.fails++
        s.lastFailMs = nowMs
        if (s.fails == FREE_FAILS) try { PipelineHealthCollector.labelInc("FEED_BACKOFF_ARMED_7981_$feed") } catch (_: Throwable) {}
    }

    fun line7981(): String =
        feeds.entries.sortedBy { it.key }.joinToString(" ") { (k, s) -> "$k:fails=${s.fails}/skipped=${s.skipped}" }.ifBlank { "-" }
}
