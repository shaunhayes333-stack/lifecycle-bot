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

    /**
     * V5.0.8292 — one scanner sighting reaches intake two or three times inside seconds (the direct callback, the
     * merge-queue emit 2-5 s later). Those echoes are one discovery, not repeats: counting them as repeats put
     * newShare at ~14% (5.0.8286) when the scanner's own re-serving was the real churn. An intake within
     * [ECHO_MS_8292] of the mint's last accepted intake is counted as an echo instead.
     */
    const val ECHO_MS_8292 = 15_000L
    private val echoTotal8292 = AtomicLong(0)

    /** Pure. */
    fun isEcho8292(prevAcceptAtMs: Long?, nowMs: Long): Boolean =
        prevAcceptAtMs != null && nowMs - prevAcceptAtMs in 0L until ECHO_MS_8292

    /** BotService intake: a mint already accepted — an echo of that sighting, or a real repeat. */
    fun noteRepeat8292(mint: String, prevAcceptAtMs: Long?, nowMs: Long = System.currentTimeMillis()) {
        if (isEcho8292(prevAcceptAtMs, nowMs)) echoTotal8292.incrementAndGet() else note7984(mint, false, nowMs)
    }

    private fun prune(nowMs: Long) {
        while (newTimes.isNotEmpty() && nowMs - newTimes.peekFirst() > WINDOW_MS) newTimes.removeFirst()
        while (repeatTimes.isNotEmpty() && nowMs - repeatTimes.peekFirst() > WINDOW_MS) repeatTimes.removeFirst()
    }

    /** Pure: share of intake calls that were new mints, 0..100. */
    fun newSharePct7984(newN: Long, repeatN: Long): Int =
        if (newN + repeatN <= 0L) 0 else (newN * 100 / (newN + repeatN)).toInt()

    /** V5.0.8441 — DiagnosisBrief8438 G INPUTS: (new mints, repeat intakes) this session. */
    fun session8441(): Pair<Long, Long> = newTotal.get() to repeatTotal.get()

    fun line7984(nowMs: Long = System.currentTimeMillis()): String {
        val (n10, r10) = synchronized(this) { prune(nowMs); newTimes.size.toLong() to repeatTimes.size.toLong() }
        return "last10m new=$n10 repeat=$r10 newShare=${newSharePct7984(n10, r10)}% · session new=${newTotal.get()} repeat=${repeatTotal.get()} newShare=${newSharePct7984(newTotal.get(), repeatTotal.get())}% echo8292=${echoTotal8292.get()}"
    }
}
