package com.lifecyclebot.engine.chart

import android.content.Context
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7950 — builds the chart library in the app, from real charts.
 *
 *  - Top ~200 crypto by 24h quote volume (Binance public market data,
 *    data-api.binance.vision): 1m / 5m / 15m / 1h / 4h / 1d candles with the
 *    taker-buy split.
 *  - Solana memes (GeckoTerminal): PumpSwap and pump.fun curve pools, trending
 *    and top pools, plus the famous pump.fun runners by name; BSC trending and
 *    top pools: 1m / 5m / 15m / 1h / 1d candles.
 *
 * Runs on one low-priority background thread, paced well under each provider's
 * free limit (GeckoTerminal one call per [GECKO_GAP_MS], backing off on 429),
 * resumes where it stopped after a restart, and rebuilds weekly. Each series
 * contributes at most [WINDOWS_PER_SERIES] evenly spread fingerprints, and the
 * library keeps a uniform sample of everything seen. The library file lives in
 * the app's files directory and is loaded at start.
 */
object ChartLibraryBuilder7950 {
    private const val FILE = "chart_library_7950.bin"
    private const val PREFS = "chart_library_builder_7950"
    private const val REBUILD_MS = 7L * 24 * 60 * 60_000L
    private const val GECKO_GAP_MS = 10_000L
    private const val BINANCE_GAP_MS = 350L
    private const val WINDOWS_PER_SERIES = 40
    private const val TOP_CRYPTO = 200
    private const val GECKO = "https://api.geckoterminal.com/api/v2"
    private const val BINANCE = "https://data-api.binance.vision/api/v3"

    /** The famous runners, found by name on Solana (pump.fun era and its peers). */
    private val FAMOUS = listOf(
        "fartcoin", "goat", "pnut", "moodeng", "chillguy", "pippin", "zerebro", "griffain", "act",
        "fwog", "michi", "retardio", "swarms", "arc", "vine", "house", "troll", "jailstool", "butthole",
        "alch", "useless", "aura", "titcoin", "ban", "luce", "lockin", "gork", "pwease", "nub", "wojak",
        "giga", "popcat", "wif", "bonk", "bome", "mew", "ponke", "slerf", "myro", "boden",
    )
    private val STABLES = setOf("USDC", "FDUSD", "TUSD", "DAI", "USDP", "BUSD", "EUR", "AEUR", "USDE", "USD1", "PAXG", "XUSD", "BFUSD")

    private val running = AtomicBoolean(false)
    private val calls = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val seriesDone = AtomicInteger(0)
    private val seriesTotal = AtomicInteger(0)
    @Volatile private var phase = "idle"
    @Volatile private var file: File? = null
    @Volatile private var lastGeckoMs = 0L

    private val http by lazy { com.lifecyclebot.network.SharedHttpClient.base }

    /** BotService start: load the library, then build / refresh it in the background. */
    fun start(context: Context) {
        val app = context.applicationContext
        val f = File(app.filesDir, FILE)
        file = f
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                phase = "loading"
                val n = ChartLibrary7950.load(f)
                try { ForensicLogger.lifecycle("CHART_LIBRARY_LOADED_7950", "motifs=$n") } catch (_: Throwable) {}
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val last = prefs.getLong("builtAtMs", 0L)
                if (System.currentTimeMillis() - last > REBUILD_MS || n < ChartLibrary7950.CAPACITY / 4) {
                    build(prefs)
                    prefs.edit().putLong("builtAtMs", System.currentTimeMillis()).remove("done").apply()
                }
                phase = "live"
                // Live motifs keep arriving through ChartReader7950; save them periodically.
                while (true) {
                    Thread.sleep(10L * 60_000L)
                    ChartLibrary7950.save(f)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                phase = "error:${t.javaClass.simpleName}"
            } finally {
                running.set(false)
            }
        }, "chart-library-7950").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private data class Series(val key: String, val source: Int, val url: String, val binance: Boolean)

    private fun build(prefs: android.content.SharedPreferences) {
        phase = "listing"
        val done = HashSet(prefs.getStringSet("done", emptySet()) ?: emptySet())
        val series = ArrayList<Series>()
        series += cryptoSeries()
        series += memeSeries()
        val todo = series.filter { it.key !in done }
        seriesTotal.set(series.size)
        seriesDone.set(series.size - todo.size)
        phase = "building"
        var sinceSave = 0
        for (s in todo) {
            val bars = try { if (s.binance) binanceBars(s.url) else geckoBars(s.url) } catch (_: Throwable) { null }
            if (bars != null && bars.size > ChartMotif7950.WINDOW + ChartMotif7950.HORIZON + 2) {
                sinceSave += ChartLibrary7950.ingestSeries(bars, s.source, WINDOWS_PER_SERIES)
            }
            done += s.key
            seriesDone.incrementAndGet()
            if (sinceSave >= 2_000) {
                sinceSave = 0
                file?.let { ChartLibrary7950.save(it) }
                prefs.edit().putStringSet("done", HashSet(done)).apply()
            }
        }
        file?.let { ChartLibrary7950.save(it) }
        try {
            PipelineHealthCollector.labelInc("CHART_LIBRARY_BUILT_7950")
            ForensicLogger.lifecycle("CHART_LIBRARY_BUILT_7950", "series=${series.size} ${ChartLibrary7950.statusLine()}")
        } catch (_: Throwable) {}
    }

    // ── sources ──

    private fun cryptoSeries(): List<Series> {
        val body = get("$BINANCE/ticker/24hr", false) ?: return emptyList()
        val arr = try { JSONArray(body) } catch (_: Throwable) { return emptyList() }
        val pairs = ArrayList<Pair<String, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("symbol")
            if (!sym.endsWith("USDT")) continue
            val base = sym.removeSuffix("USDT")
            if (base in STABLES || base.endsWith("UP") || base.endsWith("DOWN") || base.endsWith("BULL") || base.endsWith("BEAR")) continue
            pairs += sym to o.optString("quoteVolume").toDoubleOrNull().let { it ?: 0.0 }
        }
        val top = pairs.sortedByDescending { it.second }.take(TOP_CRYPTO).map { it.first }
        val out = ArrayList<Series>()
        for (sym in top) for (iv in listOf("1m", "5m", "15m", "1h", "4h", "1d")) {
            out += Series("B|$sym|$iv", ChartLibrary7950.SRC_CRYPTO, "$BINANCE/klines?symbol=$sym&interval=$iv&limit=1000", true)
        }
        return out
    }

    private fun memeSeries(): List<Series> {
        val pools = LinkedHashMap<String, Int>() // "network|address" -> source
        fun addPools(network: String, path: String, pages: Int, source: Int) {
            for (p in 1..pages) {
                val body = get("$GECKO/$path${if (path.contains('?')) '&' else '?'}page=$p", true) ?: break
                val data = try { JSONObject(body).optJSONArray("data") } catch (_: Throwable) { null } ?: break
                if (data.length() == 0) break
                for (i in 0 until data.length()) {
                    val addr = data.optJSONObject(i)?.optJSONObject("attributes")?.optString("address").orEmpty()
                    if (addr.isNotBlank()) pools.putIfAbsent("$network|$addr", source)
                }
            }
        }
        addPools("solana", "networks/solana/dexes/pumpswap/pools", 5, ChartLibrary7950.SRC_SOL_MEME)
        addPools("solana", "networks/solana/dexes/pump-fun/pools", 3, ChartLibrary7950.SRC_SOL_MEME)
        addPools("solana", "networks/solana/trending_pools", 3, ChartLibrary7950.SRC_SOL_MEME)
        addPools("solana", "networks/solana/pools", 3, ChartLibrary7950.SRC_SOL_MEME)
        addPools("bsc", "networks/bsc/trending_pools", 3, ChartLibrary7950.SRC_BSC_MEME)
        addPools("bsc", "networks/bsc/pools", 2, ChartLibrary7950.SRC_BSC_MEME)
        for (name in FAMOUS) {
            val body = get("$GECKO/search/pools?query=$name&network=solana", true) ?: continue
            val data = try { JSONObject(body).optJSONArray("data") } catch (_: Throwable) { null } ?: continue
            val addr = data.optJSONObject(0)?.optJSONObject("attributes")?.optString("address").orEmpty()
            if (addr.isNotBlank()) pools.putIfAbsent("solana|$addr", ChartLibrary7950.SRC_SOL_MEME)
        }
        val out = ArrayList<Series>()
        for ((key, source) in pools) {
            val (net, addr) = key.split('|', limit = 2).let { it[0] to it[1] }
            for ((tf, agg) in listOf("minute" to 1, "minute" to 5, "minute" to 15, "hour" to 1, "day" to 1)) {
                out += Series("G|$key|$tf$agg", source, "$GECKO/networks/$net/pools/$addr/ohlcv/$tf?aggregate=$agg&limit=1000", false)
            }
        }
        return out
    }

    // ── fetch + parse ──

    private fun get(url: String, gecko: Boolean): String? {
        repeat(2) { attempt ->
            if (gecko) {
                val wait = lastGeckoMs + GECKO_GAP_MS - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                // V5.0.7951 review — the live candle feed owns GeckoTerminal: wait out its cooldown and its slot.
                var guard = 0
                while (guard++ < 60) {
                    val slot = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809() } catch (_: Throwable) { null } ?: break
                    if (!slot.cooldown && slot.waitMs <= 0L) break
                    Thread.sleep(slot.waitMs.coerceIn(1_000L, 30_000L))
                }
                lastGeckoMs = System.currentTimeMillis()
            } else Thread.sleep(BINANCE_GAP_MS)
            calls.incrementAndGet()
            try {
                val req = Request.Builder().url(url).header("accept", "application/json")
                    .header("user-agent", "Mozilla/5.0 (Android) AATE/5.0").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.string()
                    if (resp.code == 429 && attempt == 0) { Thread.sleep(60_000L); return@repeat }
                    failures.incrementAndGet()
                    return null
                }
            } catch (_: InterruptedException) {
                throw InterruptedException()
            } catch (_: Throwable) {
                failures.incrementAndGet()
            }
        }
        return null
    }

    private fun binanceBars(url: String): List<Bar7950>? {
        val arr = JSONArray(get(url, false) ?: return null)
        return parseBinance(arr)
    }

    /** Pure: Binance klines -> candles (taker-buy volume is the buy split). */
    fun parseBinance(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            val o = k.optString(1).toDoubleOrNull() ?: continue
            val h = k.optString(2).toDoubleOrNull() ?: continue
            val l = k.optString(3).toDoubleOrNull() ?: continue
            val c = k.optString(4).toDoubleOrNull() ?: continue
            val v = k.optString(5).toDoubleOrNull() ?: 0.0
            val bv = k.optString(9).toDoubleOrNull() ?: Double.NaN
            out += Bar7950(k.optLong(0), o, h, l, c, v, bv)
        }
        return out
    }

    private fun geckoBars(url: String): List<Bar7950>? {
        val body = get(url, true) ?: return null
        return parseGecko(JSONObject(body))
    }

    /** Pure: GeckoTerminal ohlcv_list (newest first) -> candles oldest first. */
    fun parseGecko(root: JSONObject): List<Bar7950> {
        val list = root.optJSONObject("data")?.optJSONObject("attributes")?.optJSONArray("ohlcv_list") ?: return emptyList()
        val out = ArrayList<Bar7950>(list.length())
        for (i in list.length() - 1 downTo 0) {
            val k = list.optJSONArray(i) ?: continue
            if (k.length() < 6) continue
            val c = k.optDouble(4)
            if (!(c > 0.0)) continue
            out += Bar7950(k.optLong(0) * 1000L, k.optDouble(1), k.optDouble(2), k.optDouble(3), c, k.optDouble(5).takeIf { it.isFinite() } ?: 0.0)
        }
        return out
    }

    fun statusLine(): String =
        "phase=$phase series=${seriesDone.get()}/${seriesTotal.get()} calls=${calls.get()} failures=${failures.get()}"
}
