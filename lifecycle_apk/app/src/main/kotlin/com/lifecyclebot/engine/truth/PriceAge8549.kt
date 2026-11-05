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
    private val total = AtomicLong(0)

    /** Pure: the bucket index of [ageMs]. */
    fun bucket8549(ageMs: Long): Int {
        if (ageMs < 0L) return buckets.size
        for ((i, b) in buckets.withIndex()) if (ageMs <= b) return i
        return buckets.size
    }

    /** CandidateAdmission8136.assess8136: the price age this decision was taken on. */
    fun noteDecision8549(lane: String, priceAgeMs: Long) {
        val row = counts.getOrPut(lane.trim().uppercase()) { LongArray(buckets.size + 1) }
        synchronized(row) { row[bucket8549(priceAgeMs)]++ }
        total.incrementAndGet()
    }

    fun statusLine8549(): String =
        "decisionPriceAge8549[n=${total.get()} buckets=<=5s/15s/30s/60s/5m/older " + counts.entries.sortedByDescending { e -> e.value.sum() }
            .take(8).joinToString(" ") { (l, r) -> "$l{${synchronized(r) { r.joinToString("/") }}}" } + "]"
}
