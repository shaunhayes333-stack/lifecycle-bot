package com.lifecyclebot.engine

/**
 * V5.0.8144 — one provider attempt per (mint, feature) at a time, retried on a schedule, capped per window.
 *
 * Several data paths asked the same provider for the same mint every cycle and got the same nothing: the chart backfill
 * re-asked a short tape every 10 minutes forever, the PumpSwap pool resolver re-read the same non-pool addresses every
 * 3 minutes for as long as a coin was held. Each key here is admitted at most once while in flight, waits an
 * exponential back-off after a futile attempt, and takes at most [maxAttemptsPerWindow] attempts per [windowMs].
 *
 *   RUN        go ahead (the attempt is claimed; report it with [done8144]).
 *   IN_FLIGHT  the same key is already being fetched: coalesced.
 *   BACKOFF    the last attempt was futile and its retry is not due.
 *   CAPPED     this window's attempts are spent (a named terminal until the window reopens).
 *
 * Pure JVM, synchronized, bounded.
 */
class ProofDataCoalescer8144(
    private val windowMs: Long,
    private val maxAttemptsPerWindow: Int,
    private val baseRetryMs: Long,
    private val maxRetryMs: Long,
    private val cap: Int = 4_000,
) {
    enum class Admit8144 { RUN, IN_FLIGHT, BACKOFF, CAPPED }

    private class Row(
        var inFlight: Boolean,
        var attempts: Int,
        var failures: Int,
        var windowStartMs: Long,
        var lastAtMs: Long,
        var nextAtMs: Long,
    )

    private val rows = HashMap<String, Row>()
    private var runs = 0L
    private var coalesced = 0L
    private var backoffs = 0L
    private var capped = 0L
    private var successes = 0L
    private var failures = 0L

    /** Pure: wait after the [failures]th consecutive futile attempt. */
    fun retryDelayMs8144(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 20)
        val d = baseRetryMs shl shift
        return if (d <= 0L || d > maxRetryMs) maxRetryMs else d
    }

    private fun verdict(r: Row?, nowMs: Long): Admit8144 {
        if (r == null) return Admit8144.RUN
        if (r.inFlight && nowMs - r.lastAtMs < windowMs) return Admit8144.IN_FLIGHT
        if (nowMs - r.windowStartMs < windowMs && r.attempts >= maxAttemptsPerWindow) return Admit8144.CAPPED
        if (nowMs < r.nextAtMs) return Admit8144.BACKOFF
        return Admit8144.RUN
    }

    /** What [admit8144] would answer now, without claiming or counting anything. */
    @Synchronized
    fun peek8144(key: String, nowMs: Long): Admit8144 = verdict(rows[key], nowMs)

    /** Claim an attempt for [key]; RUN means the caller must report the outcome with [done8144]. */
    @Synchronized
    fun admit8144(key: String, nowMs: Long): Admit8144 {
        val r = rows[key]
        val v = verdict(r, nowMs)
        when (v) {
            Admit8144.IN_FLIGHT -> { coalesced++; return v }
            Admit8144.CAPPED -> { capped++; return v }
            Admit8144.BACKOFF -> { backoffs++; return v }
            Admit8144.RUN -> {}
        }
        val row = r ?: Row(false, 0, 0, nowMs, nowMs, 0L).also { rows[key] = it }
        if (nowMs - row.windowStartMs >= windowMs) { row.attempts = 0; row.windowStartMs = nowMs }
        row.inFlight = true
        row.attempts++
        row.lastAtMs = nowMs
        runs++
        if (rows.size > cap) trim8144(nowMs)
        return Admit8144.RUN
    }

    /** The attempt for [key] ended: [useful] resets the back-off, a futile one schedules the next retry. */
    @Synchronized
    fun done8144(key: String, useful: Boolean, nowMs: Long) {
        val r = rows[key] ?: return
        r.inFlight = false
        r.lastAtMs = nowMs
        if (useful) {
            r.failures = 0
            r.nextAtMs = 0L
            successes++
        } else {
            r.failures++
            r.nextAtMs = nowMs + retryDelayMs8144(r.failures)
            failures++
        }
    }

    @Synchronized fun size8144(): Int = rows.size

    /**
     * Pure (V5.0.8419): an attempt claimed [RUN] whose [done8144] never came (the caller threw past its finally, or the
     * process died mid-fetch) is abandoned once a whole window has passed since it was claimed. [verdict] already lets such a
     * key run again; the trim treats it as idle too, so a leaked claim never pins a row (pre-8419 every pass kept in-flight
     * rows, and past [cap] only non-in-flight rows were evicted: leaked rows grew without bound).
     */
    private fun abandonedInFlight8419(inFlight: Boolean, lastAtMs: Long, nowMs: Long): Boolean = inFlight && nowMs - lastAtMs >= windowMs

    private fun idle8419(r: Row, nowMs: Long): Boolean = !r.inFlight || abandonedInFlight8419(r.inFlight, r.lastAtMs, nowMs)

    /** Drop idle rows whose window and back-off are both over, then the oldest idle ones past [cap]. */
    @Synchronized
    fun trim8144(nowMs: Long): Int {
        val before = rows.size
        rows.entries.removeIf { idle8419(it.value, nowMs) && nowMs - it.value.windowStartMs >= windowMs && nowMs >= it.value.nextAtMs }
        if (rows.size > cap) {
            rows.entries.filter { idle8419(it.value, nowMs) }.sortedBy { it.value.lastAtMs }.take(rows.size - cap).forEach { rows.remove(it.key) }
        }
        return (before - rows.size).coerceAtLeast(0)
    }

    @Synchronized
    fun line8144(): String =
        "runs=$runs coalesced=$coalesced backoff=$backoffs capped=$capped useful=$successes futile=$failures keys=${rows.size} inFlight8419=${rows.values.count { it.inFlight }}"
}

/**
 * V5.0.8144 — a bounded deferral episode per key. A candidate deferred for missing data (liquidity unread, birth not
 * hydrated) used to defer on every evaluation for as long as it stayed on the watchlist: an unbounded loop with no
 * outcome. Each key now defers for at most [budgetMs]; past that it reads TERMINAL (a named refusal, never an
 * approval) for [restMs], after which a fresh bounded episode may start. [resolved8144] ends the episode when the
 * data arrives.
 */
class ProofDataDeferral8144(
    private val budgetMs: Long,
    private val restMs: Long,
    private val cap: Int = 4_000,
) {
    enum class Step8144 { DEFER, TERMINAL }

    private data class Row(val firstAtMs: Long, val count: Int, val terminalAtMs: Long)

    private val rows = HashMap<String, Row>()
    private var terminals = 0L

    @Synchronized
    fun step8144(key: String, nowMs: Long): Step8144 {
        val r = rows[key]
        if (r == null) {
            rows[key] = Row(nowMs, 1, 0L)
            if (rows.size > cap) trim(nowMs)
            return Step8144.DEFER
        }
        if (r.terminalAtMs > 0L) {
            if (nowMs - r.terminalAtMs < restMs) return Step8144.TERMINAL
            rows[key] = Row(nowMs, 1, 0L)
            return Step8144.DEFER
        }
        if (nowMs - r.firstAtMs >= budgetMs) {
            rows[key] = r.copy(count = r.count + 1, terminalAtMs = nowMs)
            terminals++
            return Step8144.TERMINAL
        }
        rows[key] = r.copy(count = r.count + 1)
        return Step8144.DEFER
    }

    @Synchronized fun resolved8144(key: String) { rows.remove(key) }

    @Synchronized fun terminals8144(): Long = terminals

    @Synchronized fun size8144(): Int = rows.size

    private fun trim(nowMs: Long) {
        rows.entries.removeIf { val t = it.value.terminalAtMs; (t > 0L && nowMs - t >= restMs) || (t == 0L && nowMs - it.value.firstAtMs >= budgetMs + restMs) }
        if (rows.size > cap) rows.entries.sortedBy { it.value.firstAtMs }.take(rows.size - cap).map { it.key }.forEach { rows.remove(it) }
    }
}
