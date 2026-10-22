package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.ApiHealthMonitor
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.FeatureSourceCounters8285
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6916 §THE_PATTERN_STACK_WAS_STARVED_BY_ONE_DEAD_KEY.
 *
 * OPERATOR QUESTION (5.0.6915):
 *
 *   "does the brain network actually get fed enough information to truly be
 *    predictive in a live state? ... it has the full crypto cheet sheet, a
 *    list of patterns and chart shapes a fuck load of things im certain are
 *    untouched"
 *
 * They are untouched, and this is why. An audit of every OHLCV path in the
 * codebase found exactly one data source behind all of them:
 *
 *   DataOrchestrator.seedCandleHistory   birdeye.getCandles(mint,"1m",120)
 *                                        birdeye.getCandles(mint,"5m",60)
 *                                        birdeye.getCandles(mint,"15m",48)
 *                                        birdeye.getCandles(mint,"4H",120)
 *   HistoricalChartScanner:400           $BIRDEYE_BASE/defi/ohlcv
 *   ChartHistoryFetcher.fetchCandles     BirdeyeApi().getCandles(...) first,
 *                                        then CoinGecko (only for "cg:"
 *                                        prefixed ids) and Yahoo (perps only)
 *                                        — no keyless path for a Solana mint
 *
 * And Birdeye has been dead all session: `birdeye sr=0% 4xx=60`, credential
 * verdict `http=401 BIRDEYE_UNHEALTHY`, plus `BIRDEYE_SEED_SKIPPED_BUDGET=483`
 * throttling the seed even when the key worked.
 *
 * So ts.history / history5m / history15m are empty, and every pattern engine
 * has a minimum bar count it can never reach:
 *
 *   MovementPatternSignal     size < 4  -> no signal
 *   HistoricalChartScanner    size < 5  -> no scan
 *   SmartChartScanner         size < 5  -> no scan (three separate guards)
 *   PatternClassifier / SemanticPatternGraph setups — nothing to classify
 *
 * That is the honest answer to the question: the brain network is NOT fed
 * enough to be predictive, because the chart-shape and pattern layer — the
 * largest single block of intelligence in the app — receives zero bars. It was
 * never disabled and never wrong. It was starved by one expired API key, and
 * ChartHistoryFetcher (which exists to solve exactly this) is called only from
 * a UI screen and never from the trading path.
 *
 * THIS ADDS THE MISSING KEYLESS SOURCE. GeckoTerminal publishes pool OHLCV
 * without a key, on a host this app already uses successfully
 * (`geckoterminal sr=71%` in the same snapshot, and SolanaMarketScanner:3311
 * already reads `/networks/solana/pools`). Endpoint shape:
 *
 *   /api/v2/networks/solana/pools/{pool}/ohlcv/{timeframe}?aggregate=N&limit=M
 *   -> { data: { attributes: { ohlcv_list: [[ts,o,h,l,c,vol], ...] } } }
 *
 * UNVERIFIED FROM THE BUILD HOST, AND HANDLED AS SUCH. This environment's
 * egress policy answers 403 to CONNECT for api.geckoterminal.com, so the
 * response shape could not be probed here. Three consequences, all deliberate:
 *   * Birdeye is still tried FIRST by the caller, so nothing regresses if the
 *     key is ever rotated back in.
 *   * Every request is recorded to ApiHealthMonitor under "geckoterminal", so a
 *     wrong path or shape surfaces as a failing provider in the operator's API
 *     health table instead of silently returning nothing forever.
 *   * The parser accepts the documented nesting AND a bare top-level
 *     `ohlcv_list`, and validates every row, so a shape change degrades to
 *     "no candles" rather than to garbage bars feeding the pattern engines.
 *
 * Garbage bars would be worse than no bars: OpenPnlSanity, the peak tracker
 * and every chart shape read this history. So validation here is strict —
 * a row must have 6 finite numeric fields with positive OHLC and
 * low <= min(o,c) <= max(o,c) <= high, or it is dropped.
 */
object SolanaOhlcvFeed6916 {

    private const val TAG = "SolanaOhlcv6916"
    private const val HOST = "geckoterminal"
    private const val BASE = "https://api.geckoterminal.com/api/v2/networks/solana"

    /** Candle sets are reused across the 5s scan cadence; 90s keeps them fresh
     *  without re-fetching per tick. Pattern shapes do not change in 90s. */
    private const val CACHE_TTL_MS = 90_000L
    private const val MAX_CACHE_KEYS = 1_500
    /** Pool resolution is immutable per mint in practice; cache it for longer. */
    private const val POOL_CACHE_TTL_MS = 30L * 60_000L

    private val http = SharedHttpClient.builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private class Cached(val candles: List<Candle>, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private class PoolRef(val pool: String, val atMs: Long)
    private val poolCache = ConcurrentHashMap<String, PoolRef>()

    /* ══════ V5.0.6944 — RATE LIMIT. This was missing and it broke the feed. ══
     *
     * Operator snapshot: geckoterminal sr=1% 5xx=1921, fetches=2008 served=18
     * empty=1990. 2008 requests over 560s of uptime is 215/min against
     * GeckoTerminal's free-tier allowance of roughly 30/min — SEVEN TIMES over.
     * The host was shedding essentially everything, so the pattern stack stayed
     * starved exactly as it was before V5.0.6916 tried to feed it.
     *
     * I shipped that build without a limiter because the egress proxy here
     * returns 403 for api.geckoterminal.com, so I could not probe the endpoint
     * and did not think about call volume. Writing the parser defensively while
     * ignoring the request budget was the wrong half to be careful about.
     *
     * MIN_INTERVAL_MS is 2500 => 24/min, under the limit with headroom. Callers
     * that arrive while the gate is closed get null immediately rather than
     * queueing: this is a best-effort enrichment feed on a 5s scan cadence, so
     * a skipped fetch costs one stale cache entry, while a queue would pile up
     * coroutines behind a shared lock on the hot path.
     */
    private const val MIN_INTERVAL_MS = 2_500L
    private val lastCallAtMs = AtomicLong(0L)

    /**
     * Sustained rejection backoff. A 429 or 5xx storm means the host is
     * actively refusing us; continuing to knock makes it worse and buries the
     * real signal in ApiHealthMonitor.
     */
    private const val COOLDOWN_MS = 60_000L
    private val cooldownUntilMs = AtomicLong(0L)
    private val consecutiveRejects = AtomicLong(0L)

    /**
     * Negative cache. 1990 of 2008 results were empty, and without this the
     * same mints are re-requested every scan pass forever — which is most of
     * how the budget got burned. A mint with no GeckoTerminal pool is a stable
     * fact for minutes, not something to rediscover every 5 seconds.
     */
    private const val NEGATIVE_TTL_MS = 10L * 60_000L
    private val negativeCache = ConcurrentHashMap<String, Long>()

    private val rateLimited = AtomicLong(0L)
    private val cooldownSkips = AtomicLong(0L)
    private val negativeHits = AtomicLong(0L)

    /**
     * V5.0.6982 — attempts the local rate gate declined, so nothing reached the
     * provider. Kept separate from [emptyResults] because "we did not ask" and
     * "there is nothing there" are different facts and only one of them is
     * evidence about a mint.
     */
    private val localSkips6982 = AtomicLong(0L)
    private val providerErrors8078 = AtomicLong(0L)  // V5.0.8078 — asked, errored, NOT cached as absence

    /** True when a call may proceed now; also claims the slot. */
    private fun rateGateOpen6944(): Boolean {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs.get()) { cooldownSkips.incrementAndGet(); return false }
        val prev = lastCallAtMs.get()
        if (now - prev < geckoIntervalMs8285.get()) { rateLimited.incrementAndGet(); return false }
        return lastCallAtMs.compareAndSet(prev, now)
    }

    // ══════ V5.0.8285 — adaptive GeckoTerminal pacing (free tier ~10-30 calls/min) ══════
    // The slot starts at [MIN_INTERVAL_MS] (24/min). A 429 widens it x1.5 up to [MAX_INTERVAL_MS_8285] (10/min); every
    // [NARROW_AFTER_OK_8285] consecutive 2xx narrow it 250 ms back toward the floor. The 7819 cooldown ladder is unchanged.
    private const val MAX_INTERVAL_MS_8285 = 6_000L
    private const val NARROW_AFTER_OK_8285 = 20L
    private val geckoIntervalMs8285 = AtomicLong(MIN_INTERVAL_MS)
    private val okStreak8285 = AtomicLong(0L)

    /** Pure: the next pacing interval after an answer [code] at [currentMs] with [okStreak] consecutive 2xx before it. */
    internal fun nextIntervalMs8285(currentMs: Long, code: Int, okStreak: Long): Long = when {
        code == 429 -> (currentMs * 3 / 2).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS_8285)
        code in 200..299 && (okStreak + 1) % NARROW_AFTER_OK_8285 == 0L -> (currentMs - 250L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS_8285)
        else -> currentMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS_8285)
    }

    private fun pace8285(code: Int) {
        val streak = if (code in 200..299) okStreak8285.get() else 0L
        geckoIntervalMs8285.set(nextIntervalMs8285(geckoIntervalMs8285.get(), code, streak))
        if (code in 200..299) okStreak8285.incrementAndGet() else okStreak8285.set(0L)
        try { com.lifecyclebot.engine.ProviderLanes8285.noteResult8285(com.lifecyclebot.engine.ProviderLanes8285.GECKO_8285, code) } catch (_: Throwable) {}
    }

    /**
     * V5.0.7809 — how long until the provider's request gate may open, without
     * claiming the slot. [cooldown] true means the host is actively refusing us
     * (429/5xx storm), so a caller must not queue behind it (Field Manual L404:
     * check provider health before relying on its data).
     */
    data class SlotWait7809(val waitMs: Long, val cooldown: Boolean)

    fun providerSlotWait7809(nowMs: Long = System.currentTimeMillis()): SlotWait7809 {
        val cd = cooldownUntilMs.get() - nowMs
        if (cd > 0L) return SlotWait7809(cd, cooldown = true)
        // V5.0.8332 — a dead source is a cooldown to its callers: one routed read (local candles), no waiting on its probe.
        val probe8332 = OhlcvRoute8332.probeWaitMs8332(OhlcvRoute8332.GECKO_8332, nowMs)
        if (probe8332 > 0L) return SlotWait7809(probe8332, cooldown = true)
        val gap = geckoIntervalMs8285.get() - (nowMs - lastCallAtMs.get())
        return SlotWait7809(gap.coerceAtLeast(0L), cooldown = false)
    }

    /**
     * V5.0.8307 — pure: the cooldown after a GeckoTerminal 429 / 5xx streak: the provider's own Retry-After when it states
     * one (5 s .. 15 min), else the 7819 escalation ladder for [level].
     */
    internal fun cooldownMs8307(level: Long, retryAfterSec: Long?): Long =
        if (retryAfterSec != null && retryAfterSec > 0L) (retryAfterSec * 1000L).coerceIn(5_000L, MAX_COOLDOWN_MS_7819)
        else escalatedCooldownMs7819(level)

    private fun noteResponse6944(code: Int, retryAfterSec8307: Long? = null) {
        pace8285(code)  // V5.0.8285 — adaptive pacing + this provider's own lane backoff
        if (code == 429 || code >= 500) {
            // V5.0.7819 §BACK_OFF_LIKE_YOU_MEAN_IT (Field Manual L404). 5.0.7813:
            // geckoterminal sr=15% 5xx=82 — a flat 60 s cooldown after five
            // rejects let the feed walk straight back into the same wall every
            // minute. A 429 is the host saying stop, so it cools down at once;
            // a 5xx streak cools after three. Each consecutive cooldown doubles
            // (60 s -> 15 min); one 2xx resets the ladder.
            if (code == 429 || consecutiveRejects.incrementAndGet() >= 3L) {
                val level = cooldownLevel7819.getAndIncrement()
                cooldownUntilMs.set(System.currentTimeMillis() + cooldownMs8307(level, if (code == 429) retryAfterSec8307 else null))  // V5.0.8307 — Retry-After honoured
                consecutiveRejects.set(0L)
                cooldowns7819.incrementAndGet()
            }
        } else if (code in 200..299) {
            consecutiveRejects.set(0L)
            cooldownLevel7819.set(0L)
        }
    }

    private const val MAX_COOLDOWN_MS_7819 = 15L * 60_000L
    private val cooldownLevel7819 = AtomicLong(0L)
    private val cooldowns7819 = AtomicLong(0L)
    private val geckoServed7819 = AtomicLong(0L)
    private val geckoBars7819 = AtomicLong(0L)
    private val paprikaBars7819 = AtomicLong(0L)

    /** V5.0.7819 — pure: cooldown for the [level]-th consecutive provider cooldown. */
    fun escalatedCooldownMs7819(level: Long): Long =
        (COOLDOWN_MS shl level.coerceIn(0L, 4L).toInt()).coerceAtMost(MAX_COOLDOWN_MS_7819)

    private val fetches = AtomicLong(0L)
    private val served = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val emptyResults = AtomicLong(0L)
    private val poolResolves = AtomicLong(0L)
    private val barsDelivered = AtomicLong(0L)
    private val rowsRejected = AtomicLong(0L)
    // V5.0.7484 — coalesce identical OHLCV demand inside the provider's own
    // minimum request interval. This is work suppression, not data suppression:
    // the next provider-eligible request still traverses the complete stack.
    private val sameKeyLastAttempt7484 = ConcurrentHashMap<String, Long>()
    private val sameKeyCoalesced7484 = AtomicLong(0L)
    private const val SAME_KEY_COALESCE_MS_7484 = 2_500L
    private const val SAME_KEY_MAX_7484 = 8_000

    /**
     * GeckoTerminal expresses timeframes as a base unit plus an aggregate, not
     * as free-form strings: minute/1, minute/5, minute/15, hour/4. The app's
     * internal labels are Birdeye-style ("1m","5m","15m","4H"), so translate
     * rather than pass through.
     */
    private fun tfFor(label: String): Pair<String, Int>? = when (label.trim().lowercase()) {
        "1m" -> "minute" to 1
        "5m" -> "minute" to 5
        "15m" -> "minute" to 15
        "1h" -> "hour" to 1
        "4h" -> "hour" to 4
        "1d", "1day" -> "day" to 1
        else -> null
    }

    private fun sweep(nowMs: Long) {
        if (cache.size > MAX_CACHE_KEYS) {
            try { cache.entries.removeIf { nowMs - it.value.atMs > CACHE_TTL_MS } } catch (_: Throwable) {}
        }
        if (poolCache.size > MAX_CACHE_KEYS) {
            try { poolCache.entries.removeIf { nowMs - it.value.atMs > POOL_CACHE_TTL_MS } } catch (_: Throwable) {}
        }
    }

    /**
     * V5.0.6982 — the outcome of an ATTEMPT, which is not the outcome of a CALL.
     *
     * [askedProvider] is false when the local rate gate declined and nothing
     * went on the wire. A null [json] then carries no information whatsoever
     * about the mint or the provider, and must never be recorded as if it did.
     */
    // V5.0.8078 — [failed8078]: the provider was asked but errored (HTTP error, empty body, transport). That is NOT
    // confirmed absence and must never be negative-cached as "this mint has no pool".
    private class Fetched6982(val json: JSONObject?, val askedProvider: Boolean, val failed8078: Boolean = false)

    /**
     * V5.0.8191 §THE_POOL_RESOLVE_ATE_THE_CANDLE_SLOT — 5.0.8186: fetches=347 poolResolves=326 barsDelivered=0
     * rateLimited6944=74 localSkips. A mint without a pool hint spent the 2.5 s slot on /tokens/{mint}/pools, and the
     * OHLCV request right behind it was then declined by the same slot every time: the pool resolved, the candles were
     * never asked for. The OHLCV request that follows a fresh resolve now waits for the next slot (bounded by
     * [maxWaitMs]); a host in 429/5xx cool-down is still never waited on. Callers are background workers.
     */
    private fun awaitSlot8191(maxWaitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var spins = 0
        while (spins++ < 64) {
            val now = System.currentTimeMillis()
            if (now < cooldownUntilMs.get()) return false
            val gap = geckoIntervalMs8285.get() - (now - lastCallAtMs.get())
            if (gap <= 0L) return true
            if (now + gap > deadline) return false
            try { Thread.sleep(gap.coerceIn(10L, MAX_INTERVAL_MS_8285)) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return false
    }

    private val slotWaits8191 = AtomicLong(0L)

    private fun fetch6982(url: String): Fetched6982 {
        val started = System.currentTimeMillis()
        // The version-pinned Accept header and a real User-Agent are required:
        // V5.9.1567 recorded that plain `Accept: application/json` was 4xx'ing
        // about 47% of GeckoTerminal calls from Android.
        val req = Request.Builder().url(url)
            .header("Accept", "application/json;version=20230302")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        // V5.0.6944 — the request budget gate. Without this the feed ran 7x over
        // GeckoTerminal's free-tier allowance and the host shed ~99% of calls.
        if (!rateGateOpen6944()) return Fetched6982(null, askedProvider = false)
        return try {
            http.newCall(req).execute().use { resp ->
                try {
                    ApiHealthMonitor.record(HOST, resp.code, System.currentTimeMillis() - started)
                } catch (_: Throwable) {}
                noteResponse6944(resp.code, resp.header("Retry-After")?.trim()?.toLongOrNull())
                if (!resp.isSuccessful) return Fetched6982(null, askedProvider = true, failed8078 = true)
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return Fetched6982(null, askedProvider = true, failed8078 = true)
                Fetched6982(JSONObject(body), askedProvider = true)
            }
        } catch (e: Throwable) {
            try { ApiHealthMonitor.recordNetworkError(HOST, e.message) } catch (_: Throwable) {}
            Fetched6982(null, askedProvider = true, failed8078 = true)
        }
    }

    /**
     * Resolve the deepest pool for a mint. Prefers a caller-supplied pool
     * address (the TokenState already carries `pairAddress` /
     * `lastPricePoolAddr` for most tokens, so the common path costs no extra
     * request at all).
     */
    private class PoolLookup6982(val pool: String?, val askedProvider: Boolean, val failed8078: Boolean = false)

    private fun resolvePool(mint: String, poolHint: String): PoolLookup6982 {
        val hint = poolHint.trim()
        // A MINT_ROUTE:/UNKNOWN/PLACEHOLDER alias is not a pool address.
        if (hint.length in 32..64 && !hint.contains(':') && !hint.equals("UNKNOWN", true)) {
            return PoolLookup6982(hint, askedProvider = false)
        }
        poolCache[mint]?.let {
            if (System.currentTimeMillis() - it.atMs <= POOL_CACHE_TTL_MS) {
                return PoolLookup6982(it.pool, askedProvider = false)
            }
        }
        // V5.0.8285 — the data pool DexScreener already named for this mint (identity kept, chosen deliberately) costs no
        // GeckoTerminal request; the /tokens/{mint}/pools resolve is spent only for a mint no batch has listed.
        val dexPool8285 = try { DexPools8285.dataPool8285(mint)?.pairAddress } catch (_: Throwable) { null }
        if (dexPool8285 != null) {
            poolCache[mint] = PoolRef(dexPool8285, System.currentTimeMillis())
            return PoolLookup6982(dexPool8285, askedProvider = false)
        }
        poolResolves.incrementAndGet()
        val fetched = fetch6982("$BASE/tokens/$mint/pools?page=1")
        val json = fetched.json ?: return PoolLookup6982(null, fetched.askedProvider, fetched.failed8078)
        val resolved = try {
            val arr = json.optJSONArray("data")
                ?: return PoolLookup6982(null, askedProvider = true, failed8078 = true)  // malformed answer, not an empty list
            var best: String? = null
            var bestLiq = -1.0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val attrs = o.optJSONObject("attributes") ?: continue
                val addr = attrs.optString("address", "").ifBlank { o.optString("id", "") }
                    .substringAfter("solana_", "").ifBlank { attrs.optString("address", "") }
                if (addr.isBlank()) continue
                val liq = attrs.optString("reserve_in_usd", "0").toDoubleOrNull() ?: 0.0
                if (liq > bestLiq) { bestLiq = liq; best = addr }
            }
            best?.also { poolCache[mint] = PoolRef(it, System.currentTimeMillis()) }
        } catch (_: Throwable) { null }
        return PoolLookup6982(resolved, askedProvider = true)
    }

    /**
     * Real OHLCV bars for a Solana mint, keyless. Empty list on any failure —
     * callers must treat empty as "no history", never as a flat chart.
     *
     * @param poolHint pool/pair address if already known (free path)
     */
    fun cachedCandles8253(mint: String, timeframeLabel: String, limit: Int,
                           nowMs: Long = System.currentTimeMillis()): List<Candle> {
        val (unit, aggregate) = tfFor(timeframeLabel) ?: return emptyList()
        val key = "$mint|$unit|$aggregate|${limit.coerceIn(2, 1000)}"
        val row = cache[key] ?: return emptyList()
        if (nowMs - row.atMs !in 0L..CACHE_TTL_MS) return emptyList()
        cacheHits.incrementAndGet()
        return row.candles
    }

    fun fetchCandles6916(
        mint: String,
        timeframeLabel: String,
        limit: Int,
        poolHint: String = "",
    ): List<Candle> {
        if (mint.isBlank() || mint.length < 32) return emptyList()
        val (unit, aggregate) = tfFor(timeframeLabel) ?: return emptyList()
        val n = limit.coerceIn(2, 1000)
        val now = System.currentTimeMillis()
        try { sweep(now) } catch (_: Throwable) {}
        val key = "$mint|$unit|$aggregate|$n"
        cachedCandles8253(mint, timeframeLabel, n, now).takeIf { it.isNotEmpty() }?.let { return it }

        // V5.0.7484 — if another lane requested this exact OHLCV key inside
        // the existing 2.5s provider budget window, a second provider request
        // cannot legally be made yet. Avoid repeating pool/fallback work.
        val priorSameKey7484 = sameKeyLastAttempt7484.put(key, now)
        if (priorSameKey7484 != null && now - priorSameKey7484 < SAME_KEY_COALESCE_MS_7484) {
            sameKeyCoalesced7484.incrementAndGet()
            try { PipelineHealthCollector.labelInc("OHLCV_SAME_KEY_WINDOW_COALESCED_7484") } catch (_: Throwable) {}
            return emptyList()
        }
        if (sameKeyLastAttempt7484.size > SAME_KEY_MAX_7484) {
            val cutoff7484 = now - maxOf(CACHE_TTL_MS, NEGATIVE_TTL_MS)
            sameKeyLastAttempt7484.entries.removeIf { it.value < cutoff7484 }
            if (sameKeyLastAttempt7484.size > SAME_KEY_MAX_7484) sameKeyLastAttempt7484.clear()
        }
        fetches.incrementAndGet()
        // V5.0.6982 §WE_WROTE_DOWN_OUR_OWN_SILENCE_AS_THE_TOKEN_HAVING_NO_CHART.
        //
        // This used to be:
        //
        //     val pool = resolvePool(mint, poolHint) ?: run {
        //         emptyResults.incrementAndGet()
        //         negativeCache[mint] = now        // <-- poison
        //         return emptyList()
        //     }
        //
        // resolvePool returned null for two completely different reasons, and
        // the caller could not tell them apart:
        //
        //   1. GeckoTerminal answered and this mint genuinely has no pool.
        //      Negative-caching that is correct and is what 6944 added it for.
        //   2. OUR OWN rate gate declined and nothing went on the wire. We
        //      learned nothing. Negative-caching that records an answer we
        //      never received.
        //
        // Case 2 dominates. The operator's 5.0.6972 snapshot:
        //
        //     fetches=895 served=1 empty=894 poolResolves=447
        //     rateLimited6944=574 cooldownSkips6944=285
        //     negCached6944=446 negativeHits6944=1654
        //
        // 574 + 285 = 859 of 895 attempts never reached the network, and
        // negCached (446) tracks poolResolves (447) almost exactly — i.e.
        // essentially every pool resolve that got throttled then marked its
        // mint as chartless. Those 446 entries went on to refuse 1654 further
        // requests without asking anyone. One mint in 895 got candles.
        //
        // That is why ts.history is empty across the board and every pattern
        // engine downstream sees a flat chart: not because the data is absent,
        // but because we told ourselves it was after declining to look.
        //
        // A local decline is the absence of an observation. Return empty for
        // now — there genuinely are no bars this instant — but write nothing
        // down, and count it as a skip rather than as an empty result.
        // V5.0.7293 — DexPaprika first. GeckoTerminal answered 5xx on every
        // call for several sessions (geckoterminal sr=0% 5xx=95, served=0);
        // DexPaprika publishes pool OHLCV keyless on a host this app already
        // reads for prices. GeckoTerminal remains the fallback.
        try {
            val paprika7293 = fetchDexPaprika7293(mint, poolHint, timeframeLabel, n)
            if (paprika7293.isNotEmpty()) {
                OhlcvRoute8332.served8332(OhlcvRoute8332.PAPRIKA_8332, mint, paprika7293, now)   // V5.0.8332 — source + newest-bar stamp
                cache[key] = Cached(paprika7293, now)
                served.incrementAndGet()
                paprikaServed7293.incrementAndGet()
                barsDelivered.addAndGet(paprika7293.size.toLong())
                paprikaBars7819.addAndGet(paprika7293.size.toLong())
                try {
                    PipelineHealthCollector.labelInc("OHLCV_DEXPAPRIKA_SERVED_7293")
                    PipelineHealthCollector.labelInc("OHLCV_KEYLESS_SERVED_6916_${timeframeLabel.uppercase()}")
                } catch (_: Throwable) {}
                return paprika7293
            }
        } catch (_: Throwable) {}
        // Only GeckoTerminal established this absence. Its negative cache cannot veto DexPaprika.
        // V5.0.6944 — negative cache. 1990 of 2008 results came back empty, and
        // without this the same poolless mints are re-requested every scan pass,
        // which is most of how the request budget was burned.
        negativeCache[mint]?.let {
            if (now - it <= NEGATIVE_TTL_MS) { negativeHits.incrementAndGet(); return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList() }
            negativeCache.remove(mint)
        }
        // V5.0.8285 — GeckoTerminal cooling down is THIS provider's state: the coin's own executed prints (one venue) are an
        // independent local source of one-minute candles and are served instead of nothing.
        if (now < cooldownUntilMs.get()) {
            FeatureSourceCounters8285.note8285("shape", "gecko", FeatureSourceCounters8285.THROTTLED)
            localTapeCandles8285(mint, timeframeLabel, n, now)?.let { return it }
        }
        // V5.0.8332 — a DEAD source (OhlcvRoute8332: consecutive zero-bar answers; 5.0.8326 gecko 0 bars / 1,615 attempts) is
        // not asked on every read: the coin's own local candles (Helius swap backfill + venue prints on the tape) are served
        // and the source is probed once per back-off interval. Nothing is cached as the provider's answer.
        if (OhlcvRoute8332.dead8332(OhlcvRoute8332.GECKO_8332) && !OhlcvRoute8332.probeDue8332(OhlcvRoute8332.GECKO_8332, now)) {
            OhlcvRoute8332.noteRoutedAroundDead8332(OhlcvRoute8332.GECKO_8332)
            FeatureSourceCounters8285.note8285("shape", "gecko", FeatureSourceCounters8285.THROTTLED)
            return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList()
        }
        FeatureSourceCounters8285.note8285("shape", "gecko", FeatureSourceCounters8285.ATTEMPT)
        val lookup6982 = resolvePool(mint, poolHint)
        val pool = lookup6982.pool
        if (pool == null) {
            if (lookup6982.askedProvider && lookup6982.failed8078) {
                providerErrors8078.incrementAndGet()
            } else if (lookup6982.askedProvider) {
                emptyResults.incrementAndGet()
                negativeCache[mint] = now
                OhlcvRoute8332.noteAnswer8332(OhlcvRoute8332.GECKO_8332, 0, now)   // V5.0.8332 — answered, no bars
            } else {
                localSkips6982.incrementAndGet()
            }
            return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList()   // V5.0.8285 — the local alternate
        }
        val url = "$BASE/pools/$pool/ohlcv/$unit?aggregate=$aggregate&limit=$n&currency=usd"
        // V5.0.8191 — the pool was just resolved on the wire: wait for the next slot instead of being declined by it.
        if (lookup6982.askedProvider && awaitSlot8191(MIN_INTERVAL_MS + 500L)) slotWaits8191.incrementAndGet()
        val candles6982 = fetch6982(url)
        if (!candles6982.askedProvider) FeatureSourceCounters8285.note8285("shape", "gecko", FeatureSourceCounters8285.THROTTLED)
        val json = candles6982.json ?: run {
            if (candles6982.failed8078) providerErrors8078.incrementAndGet()
            else if (candles6982.askedProvider) emptyResults.incrementAndGet()
            else localSkips6982.incrementAndGet()
            return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList()   // V5.0.8285 — the local alternate
        }
        val list = try {
            json.optJSONObject("data")?.optJSONObject("attributes")?.optJSONArray("ohlcv_list")
                ?: json.optJSONArray("ohlcv_list")
        } catch (_: Throwable) { null } ?: run { emptyResults.incrementAndGet(); return emptyList() }

        val out = ArrayList<Candle>(list.length())
        for (i in 0 until list.length()) {
            val row = list.optJSONArray(i) ?: continue
            if (row.length() < 5) { rowsRejected.incrementAndGet(); continue }
            // GeckoTerminal returns seconds; the app's Candle.ts is millis.
            val tsSec = row.optLong(0, 0L)
            val o = row.optDouble(1, Double.NaN)
            val h = row.optDouble(2, Double.NaN)
            val l = row.optDouble(3, Double.NaN)
            val c = row.optDouble(4, Double.NaN)
            val vol = if (row.length() >= 6) row.optDouble(5, 0.0) else 0.0
            // Strict validation. Garbage bars are worse than no bars: this
            // history feeds OpenPnlSanity, the peak tracker and every chart
            // shape, so a malformed row is dropped rather than flattened.
            val sane = tsSec > 0L &&
                o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() &&
                o > 0.0 && h > 0.0 && l > 0.0 && c > 0.0 &&
                l <= minOf(o, c) + 1e-12 && h >= maxOf(o, c) - 1e-12
            if (!sane) { rowsRejected.incrementAndGet(); continue }
            out.add(
                Candle(
                    ts = if (tsSec > 4_000_000_000L) tsSec else tsSec * 1000L,
                    priceUsd = c,
                    marketCap = 0.0,
                    volumeH1 = 0.0,
                    volume24h = if (vol.isFinite() && vol >= 0.0) vol else 0.0,
                    highUsd = h,
                    lowUsd = l,
                    openUsd = o,
                )
            )
        }
        // GeckoTerminal returns newest-first; the app's history is oldest-first
        // (addLast + removeFirst on overflow), and every pattern engine reads it
        // chronologically. Reversing here is not cosmetic — a backwards series
        // inverts every trend, breakout and wick the classifiers look for.
        val chronological = out.sortedBy { it.ts }
        if (chronological.isEmpty()) {
            if (list.length() > 0) FeatureSourceCounters8285.note8285("shape", "gecko", FeatureSourceCounters8285.INVALID)
            OhlcvRoute8332.noteAnswer8332(OhlcvRoute8332.GECKO_8332, 0, now)   // V5.0.8332 — answered, no sane bar
            emptyResults.incrementAndGet(); return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList()
        }
        FeatureSourceCounters8285.note8285("shape", "gecko", FeatureSourceCounters8285.RETURNED)
        OhlcvRoute8332.noteAnswer8332(OhlcvRoute8332.GECKO_8332, chronological.size, now)   // V5.0.8332 — a served answer revives the source
        OhlcvRoute8332.served8332(OhlcvRoute8332.GECKO_8332, mint, chronological, now)
        cache[key] = Cached(chronological, now)
        served.incrementAndGet()
        barsDelivered.addAndGet(chronological.size.toLong())
        geckoServed7819.incrementAndGet()
        geckoBars7819.addAndGet(chronological.size.toLong())
        try {
            PipelineHealthCollector.labelInc("OHLCV_KEYLESS_SERVED_6916")
            PipelineHealthCollector.labelInc("OHLCV_KEYLESS_SERVED_6916_${timeframeLabel.uppercase()}")
        } catch (_: Throwable) {}
        return chronological
    }

    // ══════ V5.0.7293 — DexPaprika keyless OHLCV ══════
    //
    //   GET https://api.dexpaprika.com/networks/solana/pools/{pool}/ohlcv
    //       ?start={unixSec}&limit={n}&interval={1m|5m|15m|1h|6h|24h}
    //   -> [ {time_open, time_close, open, high, low, close, volume}, ... ]
    //      ascending by time_open (ISO-8601).
    //
    // Free tier is keyless at ~15 requests/minute, so the gate is 4.5 s and a
    // 429/5xx backs the host off for a minute. Pool comes from the caller's
    // hint, else /networks/solana/tokens/{mint}/pools (deepest by volume),
    // cached with the GeckoTerminal pool cache's TTL. Bars pass the same strict
    // validation as 6916; a shape this parser does not recognise yields no
    // bars, never flattened ones.
    private const val PAPRIKA_HOST_7293 = "dexpaprika"
    private const val PAPRIKA_BASE_7293 = "https://api.dexpaprika.com/networks/solana"
    private const val PAPRIKA_MIN_INTERVAL_MS_7293 = 4_500L
    private val paprikaLastCallMs7293 = AtomicLong(0L)
    private val paprikaCooldownUntilMs7293 = AtomicLong(0L)
    private val paprikaServed7293 = AtomicLong(0L)
    private val paprikaEmpty7293 = AtomicLong(0L)
    private val paprikaPools7293 = ConcurrentHashMap<String, PoolRef>()

    /** V5.0.7295 — last HTTP status from DexPaprika (0 = none / gated). */
    @Volatile private var paprikaLastCode7295 = 0
    // V5.0.7446 — 401/402/403 are not transient OHLCV failures. 7438 showed
    // DexPaprika at 0% while the app kept paying calls. Latch for this process;
    // app restart/provider credential change naturally gives it another probe.
    private val paprikaTerminalDisabled7446 = AtomicBoolean(false)
    @Volatile private var paprikaTerminalCode7446 = 0

    /**
     * V5.0.7809 — DexPaprika answered 401/402/403 this session (auth or paid
     * endpoint). Other DexPaprika readers consult this so a terminal host stays
     * out of every live path, not only OHLCV (Field Manual L404).
     */
    fun paprikaTerminal7809(): Boolean = paprikaTerminalDisabled7446.get()

    private fun paprikaGet7293(url: String): String? {
        paprikaLastCode7295 = 0
        if (paprikaTerminalDisabled7446.get()) {
            try { PipelineHealthCollector.labelInc("DEXPAPRIKA_TERMINAL_SKIP_7446") } catch (_: Throwable) {}
            return null
        }
        val now = System.currentTimeMillis()
        if (now < paprikaCooldownUntilMs7293.get()) return null
        val prev = paprikaLastCallMs7293.get()
        if (now - prev < PAPRIKA_MIN_INTERVAL_MS_7293 || !paprikaLastCallMs7293.compareAndSet(prev, now)) return null
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        return try {
            http.newCall(req).execute().use { resp ->
                try { ApiHealthMonitor.record(PAPRIKA_HOST_7293, resp.code, System.currentTimeMillis() - now) } catch (_: Throwable) {}
                paprikaLastCode7295 = resp.code
                // V5.0.7295 — 5.0.7293 showed dexpaprika 4xx=18 5xx=4 served=0 and
                // no way to tell a wrong path (400) from an unindexed pool (404)
                // from a rate limit (429). Every non-2xx is named by code.
                if (!resp.isSuccessful) try { PipelineHealthCollector.labelInc("DEXPAPRIKA_HTTP_${resp.code}_7295") } catch (_: Throwable) {}
                if (resp.code in setOf(401, 402, 403)) {
                    paprikaTerminalCode7446 = resp.code
                    if (paprikaTerminalDisabled7446.compareAndSet(false, true)) {
                        try {
                            PipelineHealthCollector.labelInc("DEXPAPRIKA_TERMINAL_DISABLE_7446_${resp.code}")
                            ForensicLogger.lifecycle(
                                "DEXPAPRIKA_TERMINAL_DISABLE_7446",
                                "http=${resp.code} action=session_disable reason=auth_or_paid_endpoint",
                            )
                        } catch (_: Throwable) {}
                    }
                } else if (resp.code == 429 || resp.code >= 500) {
                    paprikaCooldownUntilMs7293.set(System.currentTimeMillis() + COOLDOWN_MS)
                }
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Throwable) {
            try { ApiHealthMonitor.recordNetworkError(PAPRIKA_HOST_7293, e.message) } catch (_: Throwable) {}
            null
        }
    }

    private val paprikaHintRejected7295 = ConcurrentHashMap<String, Long>()

    private fun paprikaPool7293(mint: String, poolHint: String): String? {
        val hint = poolHint.trim()
        val hintRejected7295 = paprikaHintRejected7295[mint]?.let { System.currentTimeMillis() - it < POOL_CACHE_TTL_MS } == true
        if (!hintRejected7295 && hint.length in 32..64 && !hint.contains(':') && !hint.equals("UNKNOWN", true)) return hint
        paprikaPools7293[mint]?.let {
            if (System.currentTimeMillis() - it.atMs <= POOL_CACHE_TTL_MS) return it.pool
        }
        if (!hintRejected7295) poolCache[mint]?.let { return it.pool }
        val body = paprikaGet7293("$PAPRIKA_BASE_7293/tokens/$mint/pools?limit=5&order_by=volume_usd&sort=desc") ?: return null
        return try {
            val trimmed = body.trim()
            val arr = if (trimmed.startsWith("[")) org.json.JSONArray(trimmed)
                else JSONObject(trimmed).optJSONArray("pools") ?: return null
            var best: String? = null
            var bestVol = -1.0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").ifBlank { o.optString("address", "") }
                if (id.length !in 32..64) continue
                val vol = o.optDouble("volume_usd", 0.0).takeIf { it.isFinite() } ?: 0.0
                if (vol > bestVol) { bestVol = vol; best = id }
            }
            best?.also { paprikaPools7293[mint] = PoolRef(it, System.currentTimeMillis()) }
        } catch (_: Throwable) { null }
    }

    private fun fetchDexPaprika7293(mint: String, poolHint: String, timeframeLabel: String, n: Int): List<Candle> {
        val (interval, stepSec) = when (timeframeLabel.trim().lowercase()) {
            "1m" -> "1m" to 60L
            "5m" -> "5m" to 300L
            "15m" -> "15m" to 900L
            "1h" -> "1h" to 3_600L
            "4h" -> "6h" to 21_600L
            "1d", "1day" -> "24h" to 86_400L
            else -> return emptyList()
        }
        val pool = paprikaPool7293(mint, poolHint) ?: return emptyList()
        val limit = n.coerceIn(2, 366)
        val startSec = System.currentTimeMillis() / 1000L - stepSec * limit
        // V5.0.7295 — RFC3339 start (documented alongside unix seconds).
        val startIso = java.time.Instant.ofEpochSecond(startSec).toString()
        var body = paprikaGet7293("$PAPRIKA_BASE_7293/pools/$pool/ohlcv?start=$startIso&limit=$limit&interval=$interval")
        // V5.0.7295 — the caller's pool hint is often a DexScreener pair or a
        // pump.fun curve that DexPaprika does not index (404). Ask DexPaprika
        // for its own deepest pool for the token once and retry on the next pass.
        if (body == null && (paprikaLastCode7295 == 404 || paprikaLastCode7295 == 400) && pool == poolHint.trim()) {
            paprikaHintRejected7295[mint] = System.currentTimeMillis()
            try { PipelineHealthCollector.labelInc("DEXPAPRIKA_HINT_POOL_UNINDEXED_7295") } catch (_: Throwable) {}
        }
        if (body == null) return emptyList()
        val arr = try {
            val t = body.trim()
            if (t.startsWith("[")) org.json.JSONArray(t) else JSONObject(t).optJSONArray("data")
        } catch (_: Throwable) { null } ?: run { paprikaEmpty7293.incrementAndGet(); return emptyList() }
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tsMs = try { java.time.Instant.parse(o.optString("time_open", "")).toEpochMilli() } catch (_: Throwable) { 0L }
            val op = o.optDouble("open", Double.NaN)
            val h = o.optDouble("high", Double.NaN)
            val l = o.optDouble("low", Double.NaN)
            val c = o.optDouble("close", Double.NaN)
            val vol = o.optDouble("volume", 0.0)
            val sane = tsMs > 0L &&
                op.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() &&
                op > 0.0 && h > 0.0 && l > 0.0 && c > 0.0 &&
                l <= minOf(op, c) + 1e-12 && h >= maxOf(op, c) - 1e-12
            if (!sane) { rowsRejected.incrementAndGet(); continue }
            out.add(Candle(
                ts = tsMs, priceUsd = c, marketCap = 0.0, volumeH1 = 0.0,
                volume24h = if (vol.isFinite() && vol >= 0.0) vol else 0.0,
                highUsd = h, lowUsd = l, openUsd = op,
            ))
        }
        if (out.isEmpty()) paprikaEmpty7293.incrementAndGet()
        return out.sortedBy { it.ts }
    }

    fun statusLine(): String =
        "fetches=${fetches.get()} served=${served.get()} cacheHits=${cacheHits.get()} " +
            "empty=${emptyResults.get()} poolResolves=${poolResolves.get()} " +
            "barsDelivered=${barsDelivered.get()} rowsRejected=${rowsRejected.get()} " +
            "cached=${cache.size} keyless=true host=$HOST " +
            "rateLimited6944=${rateLimited.get()} cooldownSkips6944=${cooldownSkips.get()} " +
            "negativeHits6944=${negativeHits.get()} negCached6944=${negativeCache.size} " +
            "sameKeyCoalesced7484=${sameKeyCoalesced7484.get()} " +
            "localSkips6982=${localSkips6982.get()} providerErrors8078=${providerErrors8078.get()} " +
            "minIntervalMs=$MIN_INTERVAL_MS slotWaits8191=${slotWaits8191.get()} " +
            "| dexpaprika7293 served=${paprikaServed7293.get()} empty=${paprikaEmpty7293.get()} pools=${paprikaPools7293.size} " +
            "terminalDisabled=${paprikaTerminalDisabled7446.get()} terminalCode=$paprikaTerminalCode7446 " +
            "| perSource7819[gecko served=${geckoServed7819.get()} bars=${geckoBars7819.get()} " +
            "dexpaprika served=${paprikaServed7293.get()} bars=${paprikaBars7819.get()} " +
            "heliusSwaps ${com.lifecyclebot.engine.truth.HeliusSwapCandles7819.servedBars7819()}] " +
            "geckoBackoff7819[level=${cooldownLevel7819.get()} cooldowns=${cooldowns7819.get()} " +
            "cooldownLeftMs=${(cooldownUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)}] " +
            "note7819=rateLimited6944_is_our_own_slot_gate_not_provider_429 " +
            "gecko8285[intervalMs=${geckoIntervalMs8285.get()} localTapeServed=${localServed8285.get()} " +
            "trades{asked=${tradesAsked8285.get()} taped=${tradesTaped8285.get()} invalid=${tradesInvalid8285.get()} " +
            "skipped=${tradesSkipped8285.get()}}] " + OhlcvRoute8332.statusLine8332()

    internal fun resetForTest() {
        OhlcvRoute8332.resetForTest8332()
        cache.clear(); poolCache.clear()
        fetches.set(0L); served.set(0L); cacheHits.set(0L); emptyResults.set(0L)
        poolResolves.set(0L); barsDelivered.set(0L); rowsRejected.set(0L)
        negativeCache.clear(); lastCallAtMs.set(0L); cooldownUntilMs.set(0L)
        cooldownLevel7819.set(0L); cooldowns7819.set(0L); geckoServed7819.set(0L); geckoBars7819.set(0L); paprikaBars7819.set(0L)
        consecutiveRejects.set(0L); rateLimited.set(0L); cooldownSkips.set(0L); negativeHits.set(0L); localSkips6982.set(0L); providerErrors8078.set(0L)
        sameKeyLastAttempt7484.clear(); sameKeyCoalesced7484.set(0L)
        paprikaTerminalDisabled7446.set(false); paprikaTerminalCode7446 = 0
        paprikaLastCode7295 = 0; paprikaCooldownUntilMs7293.set(0L); paprikaLastCallMs7293.set(0L)
        geckoIntervalMs8285.set(MIN_INTERVAL_MS); okStreak8285.set(0L); localServed8285.set(0L)
        tradesAsked8285.set(0L); tradesTaped8285.set(0L); tradesInvalid8285.set(0L); tradesSkipped8285.set(0L); tradesAt8285.clear()
        synchronized(tradesMinute8285) { tradesMinute8285.clear() }
    }

    // ══════ V5.0.8285 — the local alternate and the GeckoTerminal pool-trades fallback ══════

    private val localServed8285 = AtomicLong(0L)

    /**
     * One-minute candles from the coin's own executed prints (TradeTape8100, one venue, real sizes), when a provider
     * cannot serve: null for any other timeframe, without a fresh SOL price, or under LocalTapeBars8128's minimum of
     * traded minutes. Never cached as the provider's answer; never interpolated (a minute nobody traded stays absent).
     */
    private fun localTapeCandles8285(mint: String, timeframeLabel: String, n: Int, nowMs: Long): List<Candle>? {
        if (timeframeLabel.trim().lowercase() != "1m") return null
        FeatureSourceCounters8285.note8285("shape", "localTape", FeatureSourceCounters8285.ATTEMPT)
        // V5.0.8287 — 8286 localTape a=1475 r=0: executed prints only (every curve snapshot is price-only since 8285), >= 10
        // traded minutes whatever the token's age, and a SOL price seen in the last 5 minutes. Now: the venue's observed
        // prices (price-only minutes are MARK_ONLY bars, zero volume), the minutes the token's lifecycle needs, and the last
        // observed SOL price as the USD level. Nothing interpolated: an unobserved minute has no bar.
        val c = observedCandles8287(mint, n, nowMs) ?: return null
        localServed8285.incrementAndGet()
        FeatureSourceCounters8285.note8285("shape", "localTape", FeatureSourceCounters8285.RETURNED)
        try { PipelineHealthCollector.labelInc("OHLCV_LOCAL_TAPE_ALTERNATE_8285") } catch (_: Throwable) {}
        val served8332 = c.takeLast(n)
        OhlcvRoute8332.served8332(OhlcvRoute8332.LOCAL_8332, mint, served8332, nowMs)   // V5.0.8332 — source + newest-bar stamp
        return served8332
    }

    /** V5.0.8287 — one-minute candles from the mint's observed prices (PriceShapeBars8287), lifecycle-aware minimum, or null. */
    private fun observedCandles8287(mint: String, n: Int, nowMs: Long): List<Candle>? {
        val prints = try { com.lifecyclebot.engine.truth.TradeTape8100.observedPrints8287(mint, nowMs, n.coerceIn(2, 65) * 60_000L) } catch (_: Throwable) { null }
            ?: return null
        val shape = com.lifecyclebot.engine.truth.PriceShapeBars8287
        return try { shape.series8287(prints, shape.solUsd8287(nowMs), shape.minMinutes8287(shape.ageMin8287(mint, nowMs))) } catch (_: Throwable) { null }
    }

    /** Pure: executed prints -> one-minute USD candles (LocalTapeBars8128's rule: >= its minimum traded minutes), or null. */
    internal fun candlesFromPrints8285(prints: List<com.lifecyclebot.engine.truth.TradeTape8100.Print8128>, solUsd: Double): List<Candle>? {
        val bars = com.lifecyclebot.engine.chart.LocalTapeBars8128.fromPrints8128(prints, solUsd) ?: return null
        return bars.map { b ->
            Candle(ts = b.t, priceUsd = b.c, marketCap = 0.0, volumeH1 = 0.0,
                volume24h = if (b.v.isFinite() && b.v >= 0.0) b.v else 0.0, highUsd = b.h, lowUsd = b.l, openUsd = b.o)
        }.takeIf { it.isNotEmpty() }
    }

    private const val WSOL_8285 = "So11111111111111111111111111111111111111112"
    private const val TRADES_GAP_MS_8285 = 90_000L
    private val tradesAt8285 = ConcurrentHashMap<String, Long>()
    private val tradesAsked8285 = AtomicLong(0L)
    private val tradesTaped8285 = AtomicLong(0L)
    private val tradesInvalid8285 = AtomicLong(0L)
    private val tradesSkipped8285 = AtomicLong(0L)
    /** The pool-trades fallback's share of the GeckoTerminal budget: at most this many asks a minute (OHLCV keeps the rest). */
    const val TRADES_PER_MIN_8285 = 6
    private val tradesMinute8285 = java.util.ArrayDeque<Long>()

    /** Pure: may one more ask be made at [nowMs] given the asks [recent] (oldest first) of the last minute? */
    internal fun tradesSlotFree8285(recent: List<Long>, nowMs: Long, perMin: Int = TRADES_PER_MIN_8285): Boolean =
        recent.count { nowMs - it in 0L until 60_000L } < perMin

    private fun claimTradesSlot8285(nowMs: Long): Boolean = synchronized(tradesMinute8285) {
        while (tradesMinute8285.isNotEmpty() && nowMs - tradesMinute8285.peekFirst() >= 60_000L) tradesMinute8285.pollFirst()
        if (!tradesSlotFree8285(tradesMinute8285.toList(), nowMs)) false else { tradesMinute8285.addLast(nowMs); true }
    }

    /** One GeckoTerminal pool trade with its provenance (SOL side and token side observed; price = SOL / tokens). */
    data class PoolTrade8285(val signature: String, val eventMs: Long, val isBuy: Boolean, val sol: Double, val tokens: Double)

    /**
     * Pure: GeckoTerminal /pools/{pool}/trades rows for [mint] against WSOL. A row is kept only when its side, its
     * signature, its block time and BOTH amounts are stated and the swap is mint<->WSOL (a USD-only row has no SOL size).
     * Returns the trades and the number of rows refused.
     */
    internal fun parsePoolTrades8285(json: JSONObject, mint: String): Pair<List<PoolTrade8285>, Int> {
        val arr = json.optJSONArray("data") ?: return emptyList<PoolTrade8285>() to 0
        val out = ArrayList<PoolTrade8285>(arr.length())
        var bad = 0
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i)?.optJSONObject("attributes")
            if (a == null) { bad++; continue }
            val kind = a.optString("kind", "").lowercase()
            val sig = a.optString("tx_hash", "").trim()
            val at = try { java.time.Instant.parse(a.optString("block_timestamp", "")).toEpochMilli() } catch (_: Throwable) { 0L }
            val from = a.optString("from_token_address", "").trim()
            val to = a.optString("to_token_address", "").trim()
            val fromAmt = a.optString("from_token_amount", "").toDoubleOrNull() ?: Double.NaN
            val toAmt = a.optString("to_token_amount", "").toDoubleOrNull() ?: Double.NaN
            val buy = kind == "buy" && from == WSOL_8285 && to == mint
            val sell = kind == "sell" && from == mint && to == WSOL_8285
            val sol = if (buy) fromAmt else toAmt
            val tok = if (buy) toAmt else fromAmt
            if (!(buy || sell) || sig.isBlank() || at <= 0L || !sol.isFinite() || sol <= 0.0 || !tok.isFinite() || tok <= 0.0) { bad++; continue }
            out += PoolTrade8285(sig, at, buy, sol, tok)
        }
        return out.sortedBy { it.eventMs } to bad
    }

    /**
     * A bounded fallback for a held / near-decision coin with no live trade stream: the pool's recent trades from
     * GeckoTerminal onto the executed-trade tape (source gecko, the pool's identity, signature, block time — the tape
     * refuses a transaction another feed already delivered). One ask per mint per [TRADES_GAP_MS_8285], through the
     * provider's own adaptive slot; a provider in cooldown is never waited on. Returns the prints taped.
     */
    fun fetchPoolTrades8285(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()): Int {
        val p = pool.trim()
        if (mint.length < 32 || p.length !in 32..64 || p.contains(':')) return 0
        val last = tradesAt8285[mint]
        if (last != null && nowMs - last in 0L until TRADES_GAP_MS_8285) { tradesSkipped8285.incrementAndGet(); return 0 }
        if (nowMs < cooldownUntilMs.get()) {
            tradesSkipped8285.incrementAndGet()
            FeatureSourceCounters8285.note8285("tape", "geckoTrades", FeatureSourceCounters8285.THROTTLED)
            return 0
        }
        if (!claimTradesSlot8285(nowMs)) {
            tradesSkipped8285.incrementAndGet()
            FeatureSourceCounters8285.note8285("tape", "geckoTrades", FeatureSourceCounters8285.THROTTLED)
            return 0
        }
        tradesAt8285[mint] = nowMs
        if (tradesAt8285.size > MAX_CACHE_KEYS) tradesAt8285.entries.removeIf { nowMs - it.value > TRADES_GAP_MS_8285 }
        FeatureSourceCounters8285.note8285("tape", "geckoTrades", FeatureSourceCounters8285.ATTEMPT)
        val f = fetch6982("$BASE/pools/$p/trades")
        if (!f.askedProvider) {
            tradesAt8285.remove(mint)   // our own slot declined: nothing was asked, the next caller may ask
            FeatureSourceCounters8285.note8285("tape", "geckoTrades", FeatureSourceCounters8285.THROTTLED)
            return 0
        }
        tradesAsked8285.incrementAndGet()
        val json = f.json ?: return 0
        val (trades, bad) = try { parsePoolTrades8285(json, mint) } catch (_: Throwable) { emptyList<PoolTrade8285>() to 1 }
        tradesInvalid8285.addAndGet(bad.toLong())
        if (bad > 0 && trades.isEmpty()) FeatureSourceCounters8285.note8285("tape", "geckoTrades", FeatureSourceCounters8285.INVALID)
        var taped = 0
        for (t in trades) {
            val ok = try {
                com.lifecyclebot.engine.truth.TradeTape8100.note8285(mint, t.sol / t.tokens, t.sol, t.tokens, t.isBuy,
                    com.lifecyclebot.engine.truth.TradeTape8100.SRC_GECKO_8285, pool = p, signature = t.signature,
                    eventMs = t.eventMs, recvMs = nowMs)
            } catch (_: Throwable) { false }
            if (ok) taped++
        }
        tradesTaped8285.addAndGet(taped.toLong())
        if (taped > 0) FeatureSourceCounters8285.note8285("tape", "geckoTrades", FeatureSourceCounters8285.RETURNED)
        return taped
    }
}
