package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7984 — is the bot discovering, or re-reading the same coins? Every intake call is
 * either a mint not seen in the last hour (new) or a repeat. The diag prints both, per the
 * last 10 minutes and for the session, beside the launch tape's own launch count.
 */
object DiscoveryRate7984 {
    private const val SEEN_MS = 60L * 60_000L
    private const val WINDOW_MS = 10L * 60_000L
    private const val MAX_SEEN = 20_000

    private val seen = ConcurrentHashMap<String, Long>()
    private val newTimes = java.util.ArrayDeque<Long>()
    private val repeatTimes = java.util.ArrayDeque<Long>()
    private val newTotal = AtomicLong(0)
    private val repeatTotal = AtomicLong(0)

    /** BotService intake: one call per intake attempt. */
    fun note7984(mint: String, firstInWindow: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val prev = seen.put(mint, nowMs)
        val isNew = firstInWindow && (prev == null || nowMs - prev > SEEN_MS)
        synchronized(this) {
            val q = if (isNew) newTimes else repeatTimes
            q.addLast(nowMs)
            while (q.size > 50_000) q.removeFirst()
            prune(nowMs)
        }
        if (isNew) newTotal.incrementAndGet() else repeatTotal.incrementAndGet()
        if (seen.size > MAX_SEEN) seen.entries.removeIf { nowMs - it.value > SEEN_MS }
    }

    private fun prune(nowMs: Long) {
        while (newTimes.isNotEmpty() && nowMs - newTimes.peekFirst() > WINDOW_MS) newTimes.removeFirst()
        while (repeatTimes.isNotEmpty() && nowMs - repeatTimes.peekFirst() > WINDOW_MS) repeatTimes.removeFirst()
    }

    /** Pure: share of intake calls that were new mints, 0..100. */
    fun newSharePct7984(newN: Long, repeatN: Long): Int =
        if (newN + repeatN <= 0L) 0 else (newN * 100 / (newN + repeatN)).toInt()

    fun line7984(nowMs: Long = System.currentTimeMillis()): String {
        val (n10, r10) = synchronized(this) { prune(nowMs); newTimes.size.toLong() to repeatTimes.size.toLong() }
        return "last10m new=$n10 repeat=$r10 newShare=${newSharePct7984(n10, r10)}% · session new=${newTotal.get()} repeat=${repeatTotal.get()} newShare=${newSharePct7984(newTotal.get(), repeatTotal.get())}%"
    }
}
