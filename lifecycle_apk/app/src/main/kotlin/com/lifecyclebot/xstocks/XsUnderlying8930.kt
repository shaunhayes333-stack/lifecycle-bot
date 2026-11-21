package com.lifecyclebot.xstocks

import com.lifecyclebot.network.SharedHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8930 §THE_SHARE_BEFORE_ITS_TOKEN — owner, 22 Nov 04:16 / 04:53: "theres no reason it cant be trading raydium x stocks.
 * they dont trading hours yeah?", then "4 fix".
 *
 * XsDesk grades every strategy on a 420-day panel, its first half to train and its second half to test (HlBrain.evaluate). The
 * token's own history comes from GeckoTerminal, whose free API returns about the last six months of daily bars (CoinGecko's
 * changelog, "extended historical data for onchain OHLCV": beyond six months is paid). So the train half held no bar at all:
 * the 04:08 capture shows every family `train{n210d +0.000±0.000%/d ... turn0.00}`, no family earned in training, the blend
 * stayed empty and the desk could never arm, whatever the costs.
 *
 * An xStock is a share held 1:1 by its issuer, so its own history before its pool existed is the share's: the underlying's
 * split- and dividend-adjusted daily closes (Yahoo's chart API, the host PerpsMarketDataFetcher already reads), put on the
 * token's own price level by the median token/share ratio over the days both have. Nothing is spliced unless the token is
 * proven to track its share on those days: at least [MIN_OVERLAP_8930] common days and 90 % of them within
 * [MAX_DEVIATION_8930] of the median ratio. Spliced days come only before the token's first own bar, carry no volume (the
 * surge feature reads none), and a day the exchange was shut carries the last close forward. The token's own stored history
 * is never changed: the splice is built for the panel only.
 */
object XsUnderlying8930 {
    internal const val MIN_OVERLAP_8930 = 30
    internal const val MAX_DEVIATION_8930 = 0.02
    private const val DAY_MS = 86_400_000L
    private const val YAHOO = "https://query1.finance.yahoo.com/v8/finance/chart/"

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, TreeMap<Long, DoubleArray>>>()   // ticker -> (day read, bars)
    private val asked = AtomicLong(); private val answered = AtomicLong(); private val failed = AtomicLong()
    private val spliced = ConcurrentHashMap<String, String>()   // symbol -> outcome of its last splice

    /** Pure: the Yahoo ticker of an xStock's underlying [symbol] ("BRK.B" / "BRKB" -> "BRK-B"). */
    internal fun ticker8930(symbol: String): String {
        val s = symbol.trim().uppercase()
        return if (s == "BRKB") "BRK-B" else s.replace('.', '-')
    }

    /** Pure: Yahoo chart JSON -> day -> [open, high, low, close, NaN], on the adjusted close (o / h / l scaled alike). */
    internal fun parse8930(body: String?): TreeMap<Long, DoubleArray> {
        val out = TreeMap<Long, DoubleArray>()
        val r = try { JSONObject(body ?: return out).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) } catch (_: Throwable) { null } ?: return out
        val ts = r.optJSONArray("timestamp") ?: return out
        val ind = r.optJSONObject("indicators") ?: return out
        val q = ind.optJSONArray("quote")?.optJSONObject(0) ?: return out
        val adj = ind.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
        val o = q.optJSONArray("open"); val h = q.optJSONArray("high"); val l = q.optJSONArray("low"); val c = q.optJSONArray("close")
        fun at(a: org.json.JSONArray?, i: Int): Double = if (a == null || i >= a.length() || a.isNull(i)) Double.NaN else a.optDouble(i, Double.NaN)
        for (i in 0 until ts.length()) {
            val close = at(c, i); val ac = at(adj, i).takeIf { it.isFinite() && it > 0.0 } ?: close
            if (!(close > 0.0) || !(ac > 0.0)) continue
            val k = ac / close
            val day = Math.floorDiv(ts.optLong(i) * 1000L, DAY_MS)
            out[day] = doubleArrayOf(at(o, i) * k, at(h, i) * k, at(l, i) * k, ac, Double.NaN)
        }
        return out
    }

    /**
     * Pure: [own] (the token's daily bars) with the share's bars [under] before its first own day, from [fromDay], on the
     * token's price level; (bars, outcome). The bars are [own] unchanged when the tracking test fails or nothing is missing.
     */
    internal fun splice8930(own: TreeMap<Long, DoubleArray>, under: TreeMap<Long, DoubleArray>, fromDay: Long): Pair<TreeMap<Long, DoubleArray>, String> {
        if (own.isEmpty()) return own to "NO_OWN_HISTORY"
        if (under.isEmpty()) return own to "NO_UNDERLYING"
        val firstOwn = own.firstKey()
        if (firstOwn <= fromDay) return own to "OWN_COVERS"
        val ratios = own.entries.mapNotNull { (d, b) -> under[d]?.get(3)?.let { u -> if (b[3] > 0.0 && u > 0.0) b[3] / u else null } }.sorted()
        if (ratios.size < MIN_OVERLAP_8930) return own to "OVERLAP_${ratios.size}d"
        val m = ratios[ratios.size / 2]
        val dev = ratios.map { kotlin.math.abs(it / m - 1.0) }.sorted()
        val p90 = dev[(dev.size * 9 / 10).coerceAtMost(dev.size - 1)]
        if (!(p90 <= MAX_DEVIATION_8930)) return own to "NOT_TRACKING_p90=${"%.1f".format(p90 * 100)}%"
        val out = TreeMap(own)
        var last = Double.NaN
        var added = 0
        for (d in fromDay until firstOwn) {
            val u = under[d]
            if (u != null) {
                last = u[3] * m
                out[d] = doubleArrayOf(u[0] * m, u[1] * m, u[2] * m, last, Double.NaN); added++
            } else if (last.isFinite()) {
                out[d] = doubleArrayOf(last, last, last, last, Double.NaN); added++   // the exchange was shut: the last close stands
            }
        }
        return out to "SPLICED_${added}d"
    }

    /** XsDesk.daily: the share's daily bars for [symbol], read at most once a day ([today]); empty when unanswered. */
    private fun bars8930(symbol: String, today: Long): TreeMap<Long, DoubleArray> {
        val t = ticker8930(symbol)
        cache[t]?.let { if (it.first == today) return it.second }
        asked.incrementAndGet()
        val body = try {
            val req = Request.Builder().url("$YAHOO$t?range=2y&interval=1d&events=div%2Csplit")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36").build()
            com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, "yahoo_chart").use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (_: Throwable) { null }
        val bars = parse8930(body)
        if (bars.isEmpty()) { failed.incrementAndGet(); return bars }
        answered.incrementAndGet()
        cache[t] = today to bars
        return bars
    }

    /** XsDesk.daily: the panel's history of [symbol] — its own bars, with the share's before them when the token tracks it. */
    fun panelBars8930(symbol: String, own: TreeMap<Long, DoubleArray>, fromDay: Long, today: Long): TreeMap<Long, DoubleArray> {
        if (own.isNotEmpty() && own.firstKey() <= fromDay) { spliced[symbol] = "OWN_COVERS"; return own }
        val (bars, why) = splice8930(own, bars8930(symbol, today), fromDay)
        spliced[symbol] = why
        return bars
    }

    fun line8930(): String {
        val by = spliced.values.groupingBy {
            when {
                it.startsWith("SPLICED_") -> "SPLICED"
                it.startsWith("NOT_TRACKING") -> "NOT_TRACKING"
                it.startsWith("OVERLAP_") -> "SHORT_OVERLAP"
                else -> it
            }
        }.eachCount()
        return "underlying8930[asked=${asked.get()} answered=${answered.get()} failed=${failed.get()} " +
            "{${by.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }}} " +
            "rule=before an xStock's own pool history (GeckoTerminal free: ~6 months), the share's adjusted daily closes on the token's level, " +
            "only when 30+ common days have 90 % within 2 % of the median ratio; panel only]"
    }

    internal fun clearForTest8930() { cache.clear(); spliced.clear() }
}
