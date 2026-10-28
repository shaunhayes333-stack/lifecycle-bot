package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8359 §PUMP_FUN_CANDLES_BEHIND_THE_TAPE — owner: "we should still be hitting pumpfun coins ... same as chart info
 * candles etc. ... free data points that require no key".
 *
 * pump.fun's frontend serves one-minute candles for a curve coin without a key
 * (`frontend-api-v3.pump.fun/candlesticks/{mint}?offset=0&limit=N&timeframe=1`, the route ExpertWallets7962 already reads).
 * It sits behind Cloudflare and is flaky (5.0.8341 pumpfun sr=5%), so it is ONE source behind the coin's own local tape
 * (TradeTape8100 / the Helius curve prints), never the only one and never on the caller's thread:
 *
 *  - asked only for a curve coin whose local tape had no series ([SolanaOhlcvFeed6916.localTapeCandles8285]);
 *  - fetched off-thread, one request per [MIN_GAP_MS_8359], answers cached per mint for [CACHE_MS_8359];
 *  - its own back-off: a 403 / 429 / 5xx / Cloudflare challenge closes it for 60 s doubling to 15 min; one served
 *    answer reopens it;
 *  - units checked against a price the bot already observed ([unitScale8359]): pump.fun has served SOL-denominated and
 *    USD-denominated closes; a series whose last close matches neither is dropped, never rescaled by guesswork.
 */
object PumpFunCandles8359 {
    private const val MIN_GAP_MS_8359 = 3_000L
    private const val CACHE_MS_8359 = 30_000L
    const val BACKOFF_BASE_MS_8359 = 60_000L
    const val BACKOFF_MAX_MS_8359 = 15L * 60_000L
    private const val MAX_CACHE_8359 = 600

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    }

    private data class Cached8359(val candles: List<Candle>, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Cached8359>()
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastCallMs = 0L
    @Volatile private var closedUntilMs = 0L
    @Volatile private var level = 0
    private val asked = AtomicLong(0L)
    private val served = AtomicLong(0L)
    private val refused = AtomicLong(0L)
    private val unitRejected = AtomicLong(0L)
    private val cacheServed = AtomicLong(0L)
    // V5.0.8418 — 5.0.8416 read asked=664 served=0 refused=2: 662 answers left no trace. Each outcome is counted now.
    private val circuitBlocked8418 = AtomicLong(0L)
    private val nonSuccess8418 = AtomicLong(0L)
    private val emptyBars8418 = AtomicLong(0L)

    /** Pure: the back-off after the [level]-th consecutive refusal (60 s doubling, capped at 15 min). */
    fun backoffMs8359(level: Int): Long = (BACKOFF_BASE_MS_8359 shl level.coerceIn(0, 4)).coerceAtMost(BACKOFF_MAX_MS_8359)

    /**
     * Pure: the multiplier that turns pump.fun closes into USD, or null when the units cannot be told. [lastClose] is the
     * series' newest close, [refUsd] a USD price the bot observed for the coin, [solUsd] the SOL price. A close within
     * 0.5x..2x of the USD price is USD (1.0); within that band of the SOL-denominated price it is SOL (x [solUsd]).
     */
    fun unitScale8359(lastClose: Double, refUsd: Double, solUsd: Double): Double? {
        if (!(lastClose.isFinite() && lastClose > 0.0 && refUsd.isFinite() && refUsd > 0.0)) return null
        val usdRatio = lastClose / refUsd
        if (usdRatio in 0.5..2.0) return 1.0
        if (solUsd.isFinite() && solUsd > 0.0) {
            val solRatio = lastClose * solUsd / refUsd
            if (solRatio in 0.5..2.0) return solUsd
        }
        return null
    }

    /** Pure: the request URL (one-minute candles). */
    fun url8359(mint: String, limit: Int): String =
        "https://frontend-api-v3.pump.fun/candlesticks/$mint?offset=0&limit=${limit.coerceIn(10, 1000)}&timeframe=1"

    /**
     * The cached series for [mint] when one is fresh; otherwise schedules one off-thread fetch (when the source is open and
     * its gap has passed) and returns null now. [refUsd] / [solUsd] check the units of what comes back.
     */
    fun candles8359(mint: String, n: Int, refUsd: Double, solUsd: Double, nowMs: Long = System.currentTimeMillis()): List<Candle>? {
        cache[mint]?.let { c ->
            if (nowMs - c.atMs in 0L until CACHE_MS_8359) { cacheServed.incrementAndGet(); return c.candles.takeLast(n) }
        }
        if (nowMs < closedUntilMs || nowMs - lastCallMs < MIN_GAP_MS_8359) return null
        if (!inFlight.compareAndSet(false, true)) return null
        lastCallMs = nowMs
        try {
            Thread({
                try { fetch8359(mint, n, refUsd, solUsd) } catch (_: Throwable) {} finally { inFlight.set(false) }
            }, "pump-candles-8359").apply { isDaemon = true }.start()
        } catch (_: Throwable) { inFlight.set(false) }
        return null
    }

    private fun refuse8359(nowMs: Long) {
        refused.incrementAndGet()
        closedUntilMs = nowMs + backoffMs8359(level)
        level = (level + 1).coerceAtMost(5)
    }

    private fun fetch8359(mint: String, n: Int, refUsd: Double, solUsd: Double) {
        asked.incrementAndGet()
        val req = Request.Builder().url(url8359(mint, maxOf(n, 60))).header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").build()
        val now = System.currentTimeMillis()
        val body: String? = try {
            com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, "pumpfun_candles_8359").use { r ->
                if (HostCircuitInterceptor.isSyntheticBlock(r)) { circuitBlocked8418.incrementAndGet(); refuse8359(now); return }
                val b = r.body?.string()
                if (r.code == 403 || r.code == 429 || r.code >= 500 || com.lifecyclebot.engine.chart.ChartParsers7955.isChallenge7955(r.code, b)) {
                    refuse8359(now); return
                }
                if (!r.isSuccessful) { nonSuccess8418.incrementAndGet(); null } else b
            }
        } catch (_: Throwable) { refuse8359(now); return }
        if (body.isNullOrBlank()) { emptyBars8418.incrementAndGet(); return }
        val arr = try {
            val t = body.trimStart()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> o.optJSONArray("candles") ?: o.optJSONArray("data") ?: JSONArray() }
        } catch (_: Throwable) { return }
        val bars = try { com.lifecyclebot.engine.chart.ChartParsers7955.pumpFun7955(arr) } catch (_: Throwable) { emptyList() }
        level = 0
        closedUntilMs = 0L
        if (bars.isEmpty()) { emptyBars8418.incrementAndGet(); return }
        val scale = unitScale8359(bars.last().c, refUsd, solUsd)
        if (scale == null) { unitRejected.incrementAndGet(); return }
        val candles = bars.map { b ->
            Candle(ts = b.t, priceUsd = b.c * scale, marketCap = 0.0, volumeH1 = 0.0,
                volume24h = if (b.v.isFinite() && b.v >= 0.0) b.v else 0.0,
                highUsd = b.h * scale, lowUsd = b.l * scale, openUsd = b.o * scale)
        }
        if (cache.size >= MAX_CACHE_8359) cache.entries.removeIf { now - it.value.atMs >= CACHE_MS_8359 }
        if (cache.size >= MAX_CACHE_8359) cache.clear()
        cache[mint] = Cached8359(candles, System.currentTimeMillis())
        served.incrementAndGet()
    }

    /** pumpCandles8359[asked=.. served=.. cacheServed=.. refused=.. unitRejected=.. closedS=.. level=..] */
    fun statusLine8359(nowMs: Long = System.currentTimeMillis()): String =
        "pumpCandles8359[asked=${asked.get()} served=${served.get()} cacheServed=${cacheServed.get()} refused=${refused.get()} " +
            "unitRejected=${unitRejected.get()} circuitBlocked8418=${circuitBlocked8418.get()} nonSuccess8418=${nonSuccess8418.get()} empty8418=${emptyBars8418.get()} closedS=${((closedUntilMs - nowMs) / 1000L).coerceAtLeast(0L)} level=$level]"
}

/** V5.0.8359 — SolanaOhlcvFeed6916's pump.fun step for a curve coin with no local series: refs read here, units checked. */
internal object PumpFunCandleStep8359 {
    private const val REF_FRESH_MS_8359 = 120_000L

    fun forCurveCoin8359(mint: String, n: Int, nowMs: Long): List<Candle>? {
        val keys = PumpCurveKeys7269
        val curveLike = !keys.isGraduated7392(mint) && (keys.keyFor(mint) != null || mint.endsWith("pump", ignoreCase = true))
        if (!curveLike) return null
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return null
        val refUsd = ts.lastPrice
        if (!(refUsd.isFinite() && refUsd > 0.0) || ts.lastPriceUpdate <= 0L || nowMs - ts.lastPriceUpdate > REF_FRESH_MS_8359) return null
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        return PumpFunCandles8359.candles8359(mint, n, refUsd, solUsd, nowMs)?.takeIf { it.isNotEmpty() }
    }
}
