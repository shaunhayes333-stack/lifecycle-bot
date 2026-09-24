package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.ApiHealthMonitor
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    /** True when a call may proceed now; also claims the slot. */
    private fun rateGateOpen6944(): Boolean {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs.get()) { cooldownSkips.incrementAndGet(); return false }
        val prev = lastCallAtMs.get()
        if (now - prev < MIN_INTERVAL_MS) { rateLimited.incrementAndGet(); return false }
        return lastCallAtMs.compareAndSet(prev, now)
    }

    private fun noteResponse6944(code: Int) {
        if (code == 429 || code >= 500) {
            if (consecutiveRejects.incrementAndGet() >= 5L) {
                cooldownUntilMs.set(System.currentTimeMillis() + COOLDOWN_MS)
                consecutiveRejects.set(0L)
            }
        } else if (code in 200..299) {
            consecutiveRejects.set(0L)
        }
    }

    private val fetches = AtomicLong(0L)
    private val served = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val emptyResults = AtomicLong(0L)
    private val poolResolves = AtomicLong(0L)
    private val barsDelivered = AtomicLong(0L)
    private val rowsRejected = AtomicLong(0L)

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
    private class Fetched6982(val json: JSONObject?, val askedProvider: Boolean)

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
                noteResponse6944(resp.code)
                if (!resp.isSuccessful) return Fetched6982(null, askedProvider = true)
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return Fetched6982(null, askedProvider = true)
                Fetched6982(JSONObject(body), askedProvider = true)
            }
        } catch (e: Throwable) {
            try { ApiHealthMonitor.recordNetworkError(HOST, e.message) } catch (_: Throwable) {}
            Fetched6982(null, askedProvider = true)
        }
    }

    /**
     * Resolve the deepest pool for a mint. Prefers a caller-supplied pool
     * address (the TokenState already carries `pairAddress` /
     * `lastPricePoolAddr` for most tokens, so the common path costs no extra
     * request at all).
     */
    private class PoolLookup6982(val pool: String?, val askedProvider: Boolean)

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
        poolResolves.incrementAndGet()
        val fetched = fetch6982("$BASE/tokens/$mint/pools?page=1")
        val json = fetched.json ?: return PoolLookup6982(null, fetched.askedProvider)
        val resolved = try {
            val arr = json.optJSONArray("data")
                ?: return PoolLookup6982(null, askedProvider = true)
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
        cache[key]?.let {
            if (now - it.atMs <= CACHE_TTL_MS) { cacheHits.incrementAndGet(); return it.candles }
        }
        // V5.0.6944 — negative cache. 1990 of 2008 results came back empty, and
        // without this the same poolless mints are re-requested every scan pass,
        // which is most of how the request budget was burned.
        negativeCache[mint]?.let {
            if (now - it <= NEGATIVE_TTL_MS) { negativeHits.incrementAndGet(); return emptyList() }
            negativeCache.remove(mint)
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
                cache[key] = Cached(paprika7293, now)
                served.incrementAndGet()
                paprikaServed7293.incrementAndGet()
                barsDelivered.addAndGet(paprika7293.size.toLong())
                try {
                    PipelineHealthCollector.labelInc("OHLCV_DEXPAPRIKA_SERVED_7293")
                    PipelineHealthCollector.labelInc("OHLCV_KEYLESS_SERVED_6916_${timeframeLabel.uppercase()}")
                } catch (_: Throwable) {}
                return paprika7293
            }
        } catch (_: Throwable) {}
        val lookup6982 = resolvePool(mint, poolHint)
        val pool = lookup6982.pool
        if (pool == null) {
            if (lookup6982.askedProvider) {
                emptyResults.incrementAndGet()
                negativeCache[mint] = now
            } else {
                localSkips6982.incrementAndGet()
            }
            return emptyList()
        }
        val url = "$BASE/pools/$pool/ohlcv/$unit?aggregate=$aggregate&limit=$n&currency=usd"
        val candles6982 = fetch6982(url)
        val json = candles6982.json ?: run {
            if (candles6982.askedProvider) emptyResults.incrementAndGet()
            else localSkips6982.incrementAndGet()
            return emptyList()
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
        if (chronological.isEmpty()) { emptyResults.incrementAndGet(); return emptyList() }
        cache[key] = Cached(chronological, now)
        served.incrementAndGet()
        barsDelivered.addAndGet(chronological.size.toLong())
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

    private fun paprikaGet7293(url: String): String? {
        paprikaLastCode7295 = 0
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
                if (resp.code == 429 || resp.code >= 500) paprikaCooldownUntilMs7293.set(System.currentTimeMillis() + COOLDOWN_MS)
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
            "localSkips6982=${localSkips6982.get()} " +
            "minIntervalMs=$MIN_INTERVAL_MS " +
            "| dexpaprika7293 served=${paprikaServed7293.get()} empty=${paprikaEmpty7293.get()} pools=${paprikaPools7293.size}"

    internal fun resetForTest() {
        cache.clear(); poolCache.clear()
        fetches.set(0L); served.set(0L); cacheHits.set(0L); emptyResults.set(0L)
        poolResolves.set(0L); barsDelivered.set(0L); rowsRejected.set(0L)
        negativeCache.clear(); lastCallAtMs.set(0L); cooldownUntilMs.set(0L)
        consecutiveRejects.set(0L); rateLimited.set(0L); cooldownSkips.set(0L); negativeHits.set(0L); localSkips6982.set(0L)
    }
}
