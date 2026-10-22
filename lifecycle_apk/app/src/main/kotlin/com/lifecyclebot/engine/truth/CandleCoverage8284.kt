package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8284 §A_BETTER_CANDLE_IS_NOT_BLOCKED_BY_AN_OCCUPIED_MINUTE (owner directive 8282, P1).
 *
 * 8282: heliusSwaps barsDelivered=434 merged=387, and HeliusSwapCandles7819 merged only into minutes the history did not
 * hold, so a poll snapshot or a mark-only bar in a minute blocked the authentic trade OHLCV for that minute. It asked
 * only while ts.history.size < 6 and refused any token whose active timeframe was above 1 m (an established token on
 * 4 h bars could never get 1 m bars).
 *
 * This holds what a candle IS (its quality, source and venue), merges by mint / venue / interval / minute keeping the
 * better candle and replacing the worse, never two candles for one minute from a merge, and states 15 s / 1 m / 5 m /
 * 15 m coverage independently from age and the bars actually held. Every candle merged here was built from observed
 * trades or fetched klines; nothing is interpolated and no minute without a trade gets a bar.
 */
object CandleCoverage8284 {

    /** Ordered best first; [rank] is the merge order (higher wins). */
    enum class Quality8284(val rank: Int) {
        TRADE_OHLCV(5), VALID_POOL_OHLCV(4), TRADE_AGGREGATED(3), MARK_ONLY(2), INCOMPLETE(1), UNKNOWN(0)
    }

    enum class Coverage8284 { NOT_YET_POSSIBLE, MISSING, PARTIAL, COVERED }

    data class Tag8284(val quality: Quality8284, val source: String, val pool: String)

    data class Merge8284(val returned: Int, val added: Int, val replaced: Int, val kept: Int) {
        val changed: Int get() = added + replaced
    }

    private const val BUCKET_MS = 60_000L
    private const val MAX_HISTORY = 300
    private const val STORE_BARS = 180
    private const val MAX_STORE_MINTS = 400
    private const val MAX_TRACKED = 2_000
    private const val MIN_1M_BARS_8284 = 6
    private const val MIN_TF_BARS_8284 = 3
    val TIMEFRAMES_8284 = listOf("15s", "1m", "5m", "15m")

    /** Candle -> what it is. Weak: an entry goes when no history holds the candle any more. */
    private val tags = Collections.synchronizedMap(WeakHashMap<Candle, Tag8284>())

    /** 1 m bars for a mint whose ts.history is on a longer timeframe (or is a held position's tick tape). */
    private val store1m = ConcurrentHashMap<String, ArrayDeque<Candle>>()

    private val latest = ConcurrentHashMap<String, Array<Coverage8284>>()
    private val needAt = ConcurrentHashMap<String, Long>()
    private val ttfCount = AtomicLong(0L)
    private val ttfSumMs = AtomicLong(0L)
    private val ttfMaxMs = AtomicLong(0L)
    private val ttfRecent = ArrayDeque<Long>()
    private val bySource = ConcurrentHashMap<String, LongArray>()
    private val storeBars = AtomicLong(0L)

    fun tag8284(c: Candle, quality: Quality8284, source: String, pool: String = ""): Candle {
        tags[c] = Tag8284(quality, source, pool)
        return c
    }

    fun tagOf8284(c: Candle): Tag8284? = tags[c]

    fun qualityOf8284(c: Candle): Quality8284 = tags[c]?.quality ?: infer8284(c)

    /**
     * Pure: the quality an untagged candle shows on its face. A point without open/high/low is INCOMPLETE; a full OHLC
     * with trade counts is the realtime tape candle whose OHLC is copied from a polled price, so UNKNOWN; a full OHLC with
     * volume and no counts is a provider kline (VALID_POOL_OHLCV); a full OHLC without volume is MARK_ONLY, and a flat one
     * (one polled point stamped as a candle) INCOMPLETE.
     */
    fun infer8284(c: Candle): Quality8284 {
        if (!c.priceUsd.isFinite() || c.priceUsd <= 0.0) return Quality8284.INCOMPLETE
        val ohlc = c.openUsd > 0.0 && c.highUsd > 0.0 && c.lowUsd > 0.0
        if (!ohlc) return Quality8284.INCOMPLETE
        // One polled observation stamped as a flat "candle" (open = high = low = close) is a point, not a bar.
        val flat = c.highUsd == c.lowUsd && c.openUsd == c.priceUsd && c.highUsd == c.priceUsd
        val point = if (flat) Quality8284.INCOMPLETE else Quality8284.MARK_ONLY
        if (c.synthetic) return point
        if (c.buysH1 > 0 || c.sellsH1 > 0) return Quality8284.UNKNOWN
        return if (c.volumeH1 > 0.0 || c.volume24h > 0.0) Quality8284.VALID_POOL_OHLCV else point
    }

    private fun trades(c: Candle): Int = c.buysH1 + c.sellsH1

    /**
     * Pure on [target] (the caller holds its lock): merge [bars] by minute. An empty minute gets the bar. A closed minute
     * whose best candle ranks below the bar is replaced by it (every lower candle of that minute goes, so the minute holds
     * one candle); an equal-rank trade bar from the same venue with more trades replaces it (the earlier read was a partial
     * minute). The still-open minute ([nowMs]) is never displaced. Otherwise the existing candle is kept.
     */
    fun mergeInto8284(target: ArrayDeque<Candle>, bars: List<Candle>, nowMs: Long, max: Int = MAX_HISTORY): Merge8284 {
        if (bars.isEmpty()) return Merge8284(0, 0, 0, 0)
        val openMinute = nowMs / BUCKET_MS
        val incoming = java.util.TreeMap<Long, Candle>()
        for (b in bars) {
            if (b.ts <= 0L || !b.priceUsd.isFinite() || b.priceUsd <= 0.0) continue
            val m = b.ts / BUCKET_MS
            val prior = incoming[m]
            if (prior == null || better8284(b, prior)) incoming[m] = b
        }
        val byMinute = HashMap<Long, MutableList<Candle>>()
        for (c in target) if (c.ts > 0L) byMinute.getOrPut(c.ts / BUCKET_MS) { ArrayList(2) }.add(c)
        val remove = Collections.newSetFromMap(IdentityHashMap<Candle, Boolean>())
        val add = ArrayList<Candle>()
        var added = 0; var replaced = 0; var kept = 0
        for ((m, bar) in incoming) {
            val existing = byMinute[m]
            if (existing.isNullOrEmpty()) { add.add(bar); added++; continue }
            if (m >= openMinute || existing.any { it == bar }) { kept++; continue }
            val best = existing.reduce { a, b -> if (better8284(b, a)) b else a }
            if (better8284(bar, best)) {
                existing.forEach { remove.add(it) }
                add.add(bar); replaced++
            } else kept++
        }
        if (add.isNotEmpty()) {
            val all = target.filter { it !in remove } + add
            target.clear()
            for (c in all.sortedBy { it.ts }.takeLast(max)) target.addLast(c)
        }
        return Merge8284(bars.size, added, replaced, kept)
    }

    /** Pure-ish: does [a] beat [b] for one minute? Higher quality; or equal trade quality, same venue, more trades. */
    private fun better8284(a: Candle, b: Candle): Boolean {
        val qa = qualityOf8284(a); val qb = qualityOf8284(b)
        if (qa.rank != qb.rank) return qa.rank > qb.rank
        if (qa.rank < Quality8284.TRADE_AGGREGATED.rank) return false
        val pa = tags[a]?.pool.orEmpty(); val pb = tags[b]?.pool.orEmpty()
        if (pa.isNotBlank() && pb.isNotBlank() && pa != pb) return false
        return trades(a) > trades(b)
    }

    /**
     * Merge [bars] (each tagged [quality] from [source] / [pool]) for [ts]: into ts.history when it is a 1 m series of
     * a candidate, else into the compatible 1 m store (an established token on 4 h bars; a held position whose history is
     * the open-position tick tape, which is never rewritten).
     */
    fun merge8284(ts: TokenState, bars: List<Candle>, quality: Quality8284, source: String, pool: String, nowMs: Long): Merge8284 {
        for (b in bars) tag8284(b, quality, source, pool)
        val open = try { ts.position.isOpen } catch (_: Throwable) { true }
        val toHistory = ts.candleTimeframeMinutes <= 1 && !open
        val r = try {
            if (toHistory) synchronized(ts) { mergeInto8284(ts.history, bars, nowMs) } else mergeStore8284(ts.mint, bars, nowMs)
        } catch (_: Throwable) { Merge8284(bars.size, 0, 0, bars.size) }
        noteMerge8284(source, r)
        return r
    }

    private fun mergeStore8284(mint: String, bars: List<Candle>, nowMs: Long): Merge8284 {
        val q = store1m.computeIfAbsent(mint) { ArrayDeque() }
        val r = synchronized(q) { mergeInto8284(q, bars, nowMs, STORE_BARS) }
        storeBars.addAndGet(r.changed.toLong())
        if (store1m.size > MAX_STORE_MINTS) {
            store1m.entries.sortedBy { e -> synchronized(e.value) { e.value.lastOrNull()?.ts ?: 0L } }
                .take(MAX_STORE_MINTS / 10).forEach { store1m.remove(it.key) }
        }
        return r
    }

    /** The compatible-store 1 m bars for [mint], oldest first (a copy). */
    fun oneMinuteBars8284(mint: String): List<Candle> = store1m[mint]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    fun noteMerge8284(source: String, r: Merge8284) {
        val a = bySource.computeIfAbsent(source) { LongArray(4) }
        synchronized(a) { a[0] += r.returned.toLong(); a[1] += r.added.toLong(); a[2] += r.replaced.toLong(); a[3] += r.kept.toLong() }
    }

    /** Pure: coverage of one timeframe from token age, distinct bars held and the bars it needs. */
    fun coverageOf8284(ageMs: Long?, distinctBars: Int, intervalMs: Long, minBars: Int): Coverage8284 {
        if (distinctBars >= minBars) return Coverage8284.COVERED
        if (ageMs != null && ageMs >= 0L && ageMs < intervalMs * minBars) return Coverage8284.NOT_YET_POSSIBLE
        return if (distinctBars > 0) Coverage8284.PARTIAL else Coverage8284.MISSING
    }

    private fun distinctBuckets(bars: Collection<Candle>, intervalMs: Long, sinceMs: Long, minRank: Int): Int {
        val seen = HashSet<Long>()
        for (c in bars) if (c.ts >= sinceMs && c.priceUsd > 0.0 && qualityOf8284(c).rank >= minRank) seen.add(c.ts / intervalMs)
        return seen.size
    }

    /** [ts]'s 1 m bars (history when it is the 1 m series, plus the compatible store). */
    private fun bars1m8284(ts: TokenState): List<Candle> {
        val fromHistory: List<Candle> = if (ts.candleTimeframeMinutes > 1) emptyList()
            else (try { synchronized(ts) { ts.history.toList() } } catch (_: Throwable) { emptyList() })
        return fromHistory + oneMinuteBars8284(ts.mint)
    }

    /** The 15 s / 1 m / 5 m / 15 m coverage of [ts] now, each stated on its own (index order = [TIMEFRAMES_8284]). */
    fun coverage8284(ts: TokenState, nowMs: Long, ageMs: Long? = ageOf(ts, nowMs)): Array<Coverage8284> {
        val p15 = try { TradeTape8100.prints8128(ts.mint, nowMs, 3L * 60_000L) } catch (_: Throwable) { emptyList() }
        val c15 = coverageOf8284(ageMs, p15.map { it.atMs / 15_000L }.toSet().size, 15_000L, MIN_TF_BARS_8284)
        val c1 = coverageOf8284(ageMs, distinctBuckets(bars1m8284(ts), BUCKET_MS, nowMs - 30L * 60_000L, Quality8284.MARK_ONLY.rank),
            BUCKET_MS, min1mBars8317(ageMs))
        val h5 = try { synchronized(ts.history5m) { ts.history5m.toList() } } catch (_: Throwable) { emptyList() }
        val c5 = coverageOf8284(ageMs, distinctBuckets(h5, 5L * BUCKET_MS, nowMs - 2L * 3_600_000L, Quality8284.UNKNOWN.rank),
            5L * BUCKET_MS, MIN_TF_BARS_8284)
        val h15 = try { synchronized(ts.history15m) { ts.history15m.toList() } } catch (_: Throwable) { emptyList() }
        val c15m = coverageOf8284(ageMs, distinctBuckets(h15, 15L * BUCKET_MS, nowMs - 6L * 3_600_000L, Quality8284.UNKNOWN.rank),
            15L * BUCKET_MS, MIN_TF_BARS_8284)
        val out = arrayOf(c15, c1, c5, c15m)
        latest[ts.mint] = out
        if (latest.size > MAX_TRACKED) latest.keys.take(MAX_TRACKED / 10).forEach { latest.remove(it) }
        return out
    }

    private fun ageOf(ts: TokenState, nowMs: Long): Long? =
        try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null }

    /**
     * V5.0.8317 — the distinct 1 m bars coverage needs for a token [ageMs] old: three inside the launch window
     * (PriceShapeBars8287.ageAwareMin8317), else [MIN_1M_BARS_8284]. 8314: a launch read NOT_YET_POSSIBLE until it was
     * six minutes old, and the setups waited on it.
     */
    fun min1mBars8317(ageMs: Long?): Int =
        PriceShapeBars8287.ageAwareMin8317(if (ageMs != null && ageMs >= 0L) ageMs / 60_000.0 else Double.NaN, MIN_1M_BARS_8284)

    /** A request saw [ts]'s 1 m coverage: start the time-to-feature clock when it is missing. Returns the 1 m state. */
    fun noteNeed8284(ts: TokenState, nowMs: Long, ageMs: Long? = ageOf(ts, nowMs)): Coverage8284 {
        val c = coverage8284(ts, nowMs, ageMs)[1]
        if (c == Coverage8284.MISSING || c == Coverage8284.PARTIAL) {
            needAt.putIfAbsent(ts.mint, nowMs)
            // V5.0.8289 — hard cap: the 1 h TTL alone never held with more than 2,000 mints needing bars inside an hour.
            if (needAt.size > MAX_TRACKED) MemoryBounds8289.capByAge8289(needAt, MAX_TRACKED, 3_600_000L, nowMs) { it }
        }
        return c
    }

    /** Merged candles landed for [ts]: when 1 m coverage is now complete, record the time since it was first needed. */
    fun noteArrived8284(ts: TokenState, nowMs: Long, ageMs: Long? = ageOf(ts, nowMs)) {
        val since = needAt[ts.mint] ?: return
        if (coverage8284(ts, nowMs, ageMs)[1] != Coverage8284.COVERED) return
        if (!needAt.remove(ts.mint, since)) return
        val ms = (nowMs - since).coerceAtLeast(0L)
        ttfCount.incrementAndGet(); ttfSumMs.addAndGet(ms)
        ttfMaxMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
        synchronized(ttfRecent) { ttfRecent.addLast(ms); while (ttfRecent.size > 64) ttfRecent.removeFirst() }
    }

    fun statusLine8284(): String {
        val src = bySource.entries.sortedBy { it.key }.joinToString(",") { (k, a) ->
            synchronized(a) { "$k:ret=${a[0]}/add=${a[1]}/repl=${a[2]}/kept=${a[3]}" }
        }.ifBlank { "-" }
        val snap = latest.values.toList()
        val cov = TIMEFRAMES_8284.indices.joinToString(" ") { i ->
            val counts = snap.groupingBy { it[i] }.eachCount()
            "${TIMEFRAMES_8284[i]}{" + Coverage8284.values().joinToString(",") { s -> "${s.name}=${counts[s] ?: 0}" } + "}"
        }
        val n = ttfCount.get()
        val med = synchronized(ttfRecent) { ttfRecent.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] } }
        return "candles8284[merge{$src} store1m=${store1m.size}mints/${storeBars.get()}bars cov{$cov} " +
            "ttf1m{n=$n avgS=${if (n > 0) ttfSumMs.get() / n / 1000L else 0L} medS=${med / 1000L} maxS=${ttfMaxMs.get() / 1000L} waiting=${needAt.size}}]"
    }

    // ── V5.0.8289 — memory census and pressure trim (MemoryGuard7977 registry) ──
    /** One [tags] entry (weak-reference node + its [Tag8284]); one stored 1 m [Candle] (as the token-row census counts it). */
    private const val TAG_BYTES_8289 = 88L
    const val BAR_BYTES_8289 = 108L

    fun tagCount8289(): Int = tags.size
    fun storeMints8289(): Int = store1m.size
    internal fun needCount8289(): Int = needAt.size
    fun storeBarCount8289(): Int {
        var n = 0
        for (q in store1m.values) n += synchronized(q) { q.size }
        return n
    }
    fun estBytes8289(): Long = tagCount8289() * TAG_BYTES_8289 + storeBarCount8289() * BAR_BYTES_8289 +
        (latest.size + needAt.size) * 120L

    /**
     * Pressure trim of the compatible 1 m store (coverage reads only its last 30 minutes): a store whose newest bar is
     * older than 2 h always goes; under HARD an unprotected store quiet for 30 min, under CRITICAL every unprotected
     * store. [tags] is weak (it empties with the candles that hold it) and is never trimmed here. Returns bars freed.
     */
    fun trim8289(hard: Boolean, critical: Boolean, nowMs: Long, protect: (String) -> Boolean): Int {
        var freed = 0
        for ((mint, q) in store1m.entries.toList()) {
            val newest = synchronized(q) { q.lastOrNull()?.ts ?: 0L }
            val stale = nowMs - newest > 2L * 3_600_000L
            val drop = stale || (hard && (critical || nowMs - newest > 30L * 60_000L) && !(try { protect(mint) } catch (_: Throwable) { true }))
            if (drop && store1m.remove(mint, q)) freed += synchronized(q) { q.size }
        }
        MemoryBounds8289.capByAge8289(needAt, if (hard) MAX_TRACKED / 4 else MAX_TRACKED, 3_600_000L, nowMs) { it }
        return freed
    }

    internal fun resetForTest8284() {
        tags.clear(); store1m.clear(); latest.clear(); needAt.clear(); bySource.clear()
        ttfCount.set(0L); ttfSumMs.set(0L); ttfMaxMs.set(0L); storeBars.set(0L)
        synchronized(ttfRecent) { ttfRecent.clear() }
    }
}
