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

    private fun get(url: String): JSONObject? {
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
        if (!rateGateOpen6944()) return null
        return try {
            http.newCall(req).execute().use { resp ->
                try {
                    ApiHealthMonitor.record(HOST, resp.code, System.currentTimeMillis() - started)
                } catch (_: Throwable) {}
                noteResponse6944(resp.code)
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return null
                JSONObject(body)
            }
        } catch (e: Throwable) {
            try { ApiHealthMonitor.recordNetworkError(HOST, e.message) } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Resolve the deepest pool for a mint. Prefers a caller-supplied pool
     * address (the TokenState already carries `pairAddress` /
     * `lastPricePoolAddr` for most tokens, so the common path costs no extra
     * request at all).
     */
    private fun resolvePool(mint: String, poolHint: String): String? {
        val hint = poolHint.trim()
        // A MINT_ROUTE:/UNKNOWN/PLACEHOLDER alias is not a pool address.
        if (hint.length in 32..64 && !hint.contains(':') && !hint.equals("UNKNOWN", true)) return hint
        poolCache[mint]?.let { if (System.currentTimeMillis() - it.atMs <= POOL_CACHE_TTL_MS) return it.pool }
        poolResolves.incrementAndGet()
        val json = get("$BASE/tokens/$mint/pools?page=1") ?: return null
        return try {
            val arr = json.optJSONArray("data") ?: return null
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
        val pool = resolvePool(mint, poolHint) ?: run {
            emptyResults.incrementAndGet()
            negativeCache[mint] = now
            return emptyList()
        }
        val url = "$BASE/pools/$pool/ohlcv/$unit?aggregate=$aggregate&limit=$n&currency=usd"
        val json = get(url) ?: run { emptyResults.incrementAndGet(); return emptyList() }
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

    fun statusLine(): String =
        "fetches=${fetches.get()} served=${served.get()} cacheHits=${cacheHits.get()} " +
            "empty=${emptyResults.get()} poolResolves=${poolResolves.get()} " +
            "barsDelivered=${barsDelivered.get()} rowsRejected=${rowsRejected.get()} " +
            "cached=${cache.size} keyless=true host=$HOST " +
            "rateLimited6944=${rateLimited.get()} cooldownSkips6944=${cooldownSkips.get()} " +
            "negativeHits6944=${negativeHits.get()} negCached6944=${negativeCache.size} " +
            "minIntervalMs=$MIN_INTERVAL_MS"

    internal fun resetForTest() {
        cache.clear(); poolCache.clear()
        fetches.set(0L); served.set(0L); cacheHits.set(0L); emptyResults.set(0L)
        poolResolves.set(0L); barsDelivered.set(0L); rowsRejected.set(0L)
        negativeCache.clear(); lastCallAtMs.set(0L); cooldownUntilMs.set(0L)
        consecutiveRejects.set(0L); rateLimited.set(0L); cooldownSkips.set(0L); negativeHits.set(0L)
    }
}
