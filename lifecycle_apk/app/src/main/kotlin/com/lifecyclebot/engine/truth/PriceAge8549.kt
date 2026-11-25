package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8549 §THE_PRICE_IS_AS_OLD_AS_ITS_FETCH — two timing defects the data audit found on the meme path:
 *  1. BotService stamped a DexScreener pair served from the shared cache (up to 45 s old, 135 s when rate limited:
 *     DexscreenerApi.CACHE_TTL_MS) with the poll time, so a decision read a stale price as fresh. The evidence time is now
 *     the pair's own fetch time ([pairEvidenceAt8549]).
 *  2. Admission accepted a meme price up to 5 minutes old (60 s for a hot launch) in LIVE; a meme coin moves 10 %+ in
 *     seconds (8326: +13.5 % between decision and fill in 3 s). LIVE now needs [LIVE_STALE_MS_8549] (hot launch
 *     [LIVE_HOT_STALE_MS_8549]); a staler price defers for a fresh mark (CandidateMarkFresh8094 asks every 15 s). Paper
 *     keeps the old bars so it still grades what LIVE would have waited on.
 * The price age every admission decided on is counted per lane, in buckets ([statusLine8549]) — it was never measured.
 */
object PriceAge8549 {
    const val LIVE_STALE_MS_8549 = 30_000L
    const val LIVE_HOT_STALE_MS_8549 = 15_000L

    /** Pure: the evidence time of a pair served [cacheAgeMs] after its fetch, read at [nowMs]; unknown age = now. */
    fun pairEvidenceAt8549(nowMs: Long, cacheAgeMs: Long): Long =
        if (cacheAgeMs in 0L..(10L * 60_000L)) nowMs - cacheAgeMs else nowMs

    private val buckets = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 300_000L)
    private val counts = ConcurrentHashMap<String, LongArray>()   // lane -> [<=5s, <=15s, <=30s, <=60s, <=5m, older/unknown]
    private val unknown8870 = ConcurrentHashMap<String, AtomicLong>()
    private val total = AtomicLong(0)

    /** Pure: the bucket index of [ageMs]. */
    fun bucket8549(ageMs: Long): Int {
        if (ageMs < 0L) return buckets.size
        for ((i, b) in buckets.withIndex()) if (ageMs <= b) return i
        return buckets.size
    }

    /** CandidateAdmission8136.assess8136: the price age this decision was taken on. */
    /**
     * V5.0.9019 §A_PRICE_SEEN_AFTER_THE_CLOCK_WAS_READ_IS_THE_FRESHEST_PRICE — owner, 25 Nov 22:08: "stale decision and stale
     * pricing are meant to be fixed already". They were measured as fixed by age, and the age was computed wrong: every
     * door took `now - observedAt` with `now` read at the START of its cycle (5.1 s average on the 21:53 capture, 12 s
     * worst) and called a NEGATIVE age stale. A price that printed after the cycle's clock was read — the freshest price
     * there is — was therefore refused as stale: unknown8870{MOONSHOT=84,EXPRESS=81,SHITCOIN=47,CORE=23} in 13 minutes
     * while EXPRESS had 2 decisions on a price 30 s to 5 min old; the wait{PRICE_STALE} of 61 evidence coins; and a ticket
     * dropped ENTRY_CHASE_DECISION_STALE with its price 0 s old.
     * The age of an observation is never negative: one stamped up to [AHEAD_MAX_MS_9019] after the clock that judges it
     * was seen after that clock was read (or by a feed whose own clock runs ahead) and is 0 ms old. Stamped further
     * ahead than that it is not a credible time and the age is unknown, as an unstamped price always was.
     */
    const val AHEAD_MAX_MS_9019 = 60_000L
    private val seenAhead9019 = AtomicLong()

    /** Pure: the age at [nowMs] of something observed at [observedAtMs]; Long.MAX_VALUE = unknown. */
    fun ageMs9019(observedAtMs: Long, nowMs: Long): Long = when {
        observedAtMs <= 0L -> Long.MAX_VALUE
        observedAtMs <= nowMs -> nowMs - observedAtMs
        observedAtMs - nowMs <= AHEAD_MAX_MS_9019 -> 0L
        else -> Long.MAX_VALUE
    }

    /** [ageMs9019], counting an observation that was seen after the clock was read. */
    fun ageCounted9019(observedAtMs: Long, nowMs: Long): Long {
        if (observedAtMs > nowMs && observedAtMs - nowMs <= AHEAD_MAX_MS_9019) seenAhead9019.incrementAndGet()
        return ageMs9019(observedAtMs, nowMs)
    }

    fun aheadLine9019(): String = "seenAfterClock9019=${seenAhead9019.get()}"

    fun noteDecision8549(lane: String, priceAgeMs: Long) {
        val row = counts.getOrPut(lane.trim().uppercase()) { LongArray(buckets.size + 1) }
        synchronized(row) { row[bucket8549(priceAgeMs)]++ }
        if (priceAgeMs == Long.MAX_VALUE || priceAgeMs < 0L)
            unknown8870.computeIfAbsent(lane.trim().uppercase()) { AtomicLong() }.incrementAndGet()
        total.incrementAndGet()
    }

    fun statusLine8549(): String =
        "decisionPriceAge8549[n=${total.get()} buckets=<=5s/15s/30s/60s/5m/olderOrUnknown " + counts.entries.sortedByDescending { e -> e.value.sum() }
            .take(8).joinToString(" ") { (l, r) -> "$l{${synchronized(r) { r.joinToString("/") }}}" } +
            " unknown8870{" + unknown8870.entries.sortedByDescending { it.value.get() }.take(8)
                .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "} ${aheadLine9019()}]"
}
