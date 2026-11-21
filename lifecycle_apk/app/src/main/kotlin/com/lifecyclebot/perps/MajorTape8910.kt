package com.lifecyclebot.perps

import com.lifecyclebot.network.SharedHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8910 §THE_MAJORS_REAL_TAPE — the data hole under MajorSetups8910: every PerpsMarketData producer synthesises the 24 h
 * high / low from the change and returns funding and open interest as 0.0 (PerpsModels 8535 provenance flags), so no setup that
 * needs a real range, a volume spike, who was buying, or the derivatives crowd could be read.
 *
 * This reads it, for the majors only ([MAJORS_8910]), off the scan's path: a single background thread refreshes a symbol's
 * hourly candles (Binance's public market-data mirror, data-api.binance.vision — the host PriceAggregator already reads — 56
 * one-hour klines with base volume and taker-buy base volume) at most every [CANDLE_TTL_MS_8910], and, only while that tape
 * shows a flush in its last six hours, its perpetual funding rate and four-hour open-interest change (Bybit v5 public market
 * data, then OKX v5 public data; no key, nothing signed) at most every [DERIV_TTL_MS_8910]. Only closed candles are kept. A
 * symbol the exchange does not list is not asked again for six hours. A tape older than [STALE_MS_8910] reads as none, and
 * an exchange that does not answer leaves the value unobserved (null): nothing is filled in.
 */
object MajorTape8910 {
    internal val MAJORS_8910 = setOf(
        "BTC", "ETH", "SOL", "BNB", "XRP", "ADA", "DOGE", "TRX", "TON", "AVAX", "LINK", "DOT", "LTC", "BCH", "XLM", "ETC",
        "NEAR", "APT", "ARB", "OP", "ICP", "FIL", "HBAR", "VET", "RENDER", "TAO", "INJ", "SUI", "JUP", "PYTH", "RAY", "JTO",
        "SEI", "AAVE", "UNI", "ATOM")
    private const val HOUR_MS = 3_600_000L
    internal const val CANDLE_TTL_MS_8910 = 5L * 60_000L
    internal const val DERIV_TTL_MS_8910 = 15L * 60_000L
    internal const val STALE_MS_8910 = 3L * HOUR_MS
    private const val UNLISTED_MS_8910 = 6L * HOUR_MS
    private const val KLINES_8910 = 56

    private class Tape(@Volatile var candles: List<MajorSetups8910.Candle8910>, @Volatile var atMs: Long)
    private class Derivs(@Volatile var funding8h: Double?, @Volatile var oi4hPct: Double?, @Volatile var atMs: Long, @Volatile var src: String)

    private val tapes = ConcurrentHashMap<String, Tape>()
    private val derivs = ConcurrentHashMap<String, Derivs>()
    private val asked = ConcurrentHashMap<String, Long>()       // symbol -> last refresh request
    private val derivAsked = ConcurrentHashMap<String, Long>()
    private val unlisted = ConcurrentHashMap<String, Long>()
    private val fetched = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val derivFetched = AtomicLong(0L)
    private val derivFailed = AtomicLong(0L)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "majorTape8910").apply { isDaemon = true } }
    private val client by lazy { SharedHttpClient.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build() }

    // ── pure ──

    /** Pure: Binance klines [body] as closed candles at [nowMs] (a candle still open is dropped), oldest first. */
    internal fun parseKlines8910(body: String, nowMs: Long): List<MajorSetups8910.Candle8910> {
        val a = JSONArray(body)
        val out = ArrayList<MajorSetups8910.Candle8910>(a.length())
        for (i in 0 until a.length()) {
            val k = a.optJSONArray(i) ?: continue
            val openMs = k.optLong(0, 0L)
            val closeMs = k.optLong(6, 0L)
            if (openMs <= 0L || closeMs >= nowMs) continue
            fun d(j: Int) = k.optString(j, "").toDoubleOrNull() ?: Double.NaN
            val c = MajorSetups8910.Candle8910(openMs, d(1), d(2), d(3), d(4), d(5), d(9))
            if (c.open > 0.0 && c.high >= c.low && c.low > 0.0 && c.close > 0.0 && c.volume.isFinite()) out += c
        }
        return out.sortedBy { it.openMs }
    }

    /** Pure: the funding rate (fraction per interval) in a Bybit v5 tickers [body]; null when absent. */
    internal fun bybitFunding8910(body: String): Double? =
        JSONObject(body).optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)?.optString("fundingRate")?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Pure: the open-interest change (%) between the newest and the 4-hours-older point of a Bybit v5 open-interest [body]. */
    internal fun bybitOi4h8910(body: String): Double? {
        val l = JSONObject(body).optJSONObject("result")?.optJSONArray("list") ?: return null
        if (l.length() < 5) return null
        val now = l.optJSONObject(0)?.optString("openInterest")?.toDoubleOrNull() ?: return null
        val then = l.optJSONObject(4)?.optString("openInterest")?.toDoubleOrNull() ?: return null
        return if (then > 0.0 && now.isFinite()) (now / then - 1.0) * 100.0 else null
    }

    /** Pure: the funding rate in an OKX v5 funding-rate [body]; null when absent. */
    internal fun okxFunding8910(body: String): Double? =
        JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("fundingRate")?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Pure: the four-hour open-interest change (%) in an OKX v5 open-interest-history [body] (rows newest first: ts, oi, ...). */
    internal fun okxOi4h8910(body: String): Double? {
        val d = JSONObject(body).optJSONArray("data") ?: return null
        if (d.length() < 5) return null
        val now = d.optJSONArray(0)?.optString(1)?.toDoubleOrNull() ?: return null
        val then = d.optJSONArray(4)?.optString(1)?.toDoubleOrNull() ?: return null
        return if (then > 0.0 && now.isFinite()) (now / then - 1.0) * 100.0 else null
    }

    // ── reads (cache only; a stale entry asks for a background refresh) ──

    /** [symbol]'s closed hourly candles, or null when it is not a major, unread or stale. Never blocks. */
    fun candles8910(symbol: String, nowMs: Long = System.currentTimeMillis()): List<MajorSetups8910.Candle8910>? {
        val sym = symbol.trim().uppercase()
        if (sym !in MAJORS_8910) return null
        val t = tapes[sym]
        if (t == null || nowMs - t.atMs >= CANDLE_TTL_MS_8910) refresh8910(sym, nowMs)
        if (t == null || nowMs - t.atMs !in 0L until STALE_MS_8910 || t.candles.size < 24) return null
        return t.candles
    }

    /** Every major with a fresh tape (for the relative-strength rank). */
    fun fresh8910(nowMs: Long = System.currentTimeMillis()): Map<String, List<MajorSetups8910.Candle8910>> =
        tapes.entries.filter { nowMs - it.value.atMs in 0L until STALE_MS_8910 && it.value.candles.size >= 24 }.associate { it.key to it.value.candles }

    /** [symbol]'s (funding per interval, 4 h open-interest change %), each null when unobserved; null when never read. */
    fun derivs8910(symbol: String, nowMs: Long = System.currentTimeMillis()): Pair<Double?, Double?>? {
        val sym = symbol.trim().uppercase()
        val d = derivs[sym]
        val c = tapes[sym]?.candles
        if (c != null && MajorSetups8910.flushIndex8910(c, 6) >= 0 && (d == null || nowMs - d.atMs >= DERIV_TTL_MS_8910)) refreshDerivs8910(sym, nowMs)
        if (d == null || nowMs - d.atMs !in 0L until 2 * DERIV_TTL_MS_8910) return null
        return d.funding8h to d.oi4hPct
    }

    private fun get8910(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else if (r.code == 400) "" else null }
    } catch (_: Throwable) { null }

    private fun refresh8910(sym: String, nowMs: Long) {
        val last = asked[sym]
        if (last != null && nowMs - last in 0L until CANDLE_TTL_MS_8910) return
        val un = unlisted[sym]
        if (un != null && nowMs - un in 0L until UNLISTED_MS_8910) return
        asked[sym] = nowMs
        try {
            io.execute {
                val body = get8910("https://data-api.binance.vision/api/v3/klines?symbol=${sym}USDT&interval=1h&limit=$KLINES_8910")
                when {
                    body == null -> failed.incrementAndGet()
                    body.isEmpty() -> { unlisted[sym] = System.currentTimeMillis() }   // 400: the exchange lists no such pair
                    else -> try {
                        val now = System.currentTimeMillis()
                        val c = parseKlines8910(body, now)
                        if (c.isNotEmpty()) { tapes[sym] = Tape(c, now); fetched.incrementAndGet() } else failed.incrementAndGet()
                    } catch (_: Throwable) { failed.incrementAndGet() }
                }
                Unit
            }
        } catch (_: Throwable) {}
    }

    private fun refreshDerivs8910(sym: String, nowMs: Long) {
        val last = derivAsked[sym]
        if (last != null && nowMs - last in 0L until DERIV_TTL_MS_8910) return
        derivAsked[sym] = nowMs
        try {
            io.execute {
                fun read(url: String, parse: (String) -> Double?): Double? = try {
                    val body = get8910(url)
                    if (body.isNullOrEmpty()) null else parse(body)
                } catch (_: Throwable) { null }
                val bybitFunding = read("https://api.bybit.com/v5/market/tickers?category=linear&symbol=${sym}USDT") { bybitFunding8910(it) }
                val funding = bybitFunding ?: read("https://www.okx.com/api/v5/public/funding-rate?instId=$sym-USDT-SWAP") { okxFunding8910(it) }
                val oi = read("https://api.bybit.com/v5/market/open-interest?category=linear&symbol=${sym}USDT&intervalTime=1h&limit=5") { bybitOi4h8910(it) }
                    ?: read("https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history?instId=$sym-USDT-SWAP&period=1H&limit=5") { okxOi4h8910(it) }
                val src = when { bybitFunding != null -> "bybit"; funding != null -> "okx"; oi != null -> "oi"; else -> "-" }
                if (funding == null && oi == null) derivFailed.incrementAndGet() else derivFetched.incrementAndGet()
                derivs[sym] = Derivs(funding, oi, System.currentTimeMillis(), src)
                Unit
            }
        } catch (_: Throwable) {}
    }

    fun statusLine8910(nowMs: Long = System.currentTimeMillis()): String {
        val fresh = fresh8910(nowMs).size
        val d = derivs.entries.filter { nowMs - it.value.atMs < 2 * DERIV_TTL_MS_8910 }.joinToString(",") { (s, v) ->
            "$s:f${v.funding8h?.let { "%.3f".format(it * 100) } ?: "-"}/oi${v.oi4hPct?.let { "%.1f".format(it) } ?: "-"}@${v.src}"
        }.ifBlank { "-" }
        return "tape8910{fresh=$fresh/${MAJORS_8910.size} fetched=${fetched.get()} failed=${failed.get()} unlisted=${unlisted.size} derivs=${derivFetched.get()}/${derivFailed.get()} {$d}}"
    }

    internal fun putForTest8910(symbol: String, c: List<MajorSetups8910.Candle8910>, atMs: Long) {
        tapes[symbol] = Tape(c, atMs); asked[symbol] = atMs
    }
    internal fun resetForTest8910() { tapes.clear(); derivs.clear(); asked.clear(); derivAsked.clear(); unlisted.clear() }
}
