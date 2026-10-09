package com.lifecyclebot.engine.chart

import android.content.Context
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7955 — every free market-data source, feeding the chart library.
 *
 * One provider = one pacing gap under its free limit, its own backoff (429 ->
 * escalating minutes; 401/403/Cloudflare challenge -> 6 h; 402 plan -> 24 h) and
 * its own queue. The build thread round-robins over the providers whose next slot
 * is open, so a dead or rate-limited provider never blocks the others; inside a
 * provider's queue, jobs from different discovery origins are interleaved too.
 * Crypto candles may not run far ahead of meme candles (no single family
 * dominates the reservoir). Candle jobs carry stable keys, so the builder's
 * done-set resumes a build after a restart.
 *
 *  No key: Binance, OKX, Bybit, Gate.io, MEXC, KuCoin, Coinbase Exchange, Kraken,
 *          CoinPaprika, CoinGecko, GeckoTerminal (solana/bsc/base/eth), DexScreener
 *          discovery, Raydium v3 / Meteora DLMM discovery, pump.fun frontend,
 *          GMGN (optional, polite; disabled 6 h on a challenge — never circumvented).
 *  Keyed (skipped while the key is blank): CoinMarketCap, Moralis, Bitquery,
 *          SolanaTracker, Codex, CryptoCompare.
 *
 * Live backfill: held positions and chart-reader candidates with fewer than 21
 * live one-minute candles are seeded from the first source that answers, so the
 * reader can read a token the moment it is seen.
 */
object ChartSources7955 {

    // ── providers ──

    private class Prov(val id: String, val gapMs: Long, val keyed: Boolean, val meme: Boolean) {
        val calls = AtomicLong(0)
        val ok = AtomicLong(0)
        val fail = AtomicLong(0)
        val bars = AtomicLong(0)
        val skips = AtomicLong(0)
        @Volatile var nextAtMs = 0L
        @Volatile var backoffUntilMs = 0L
        @Volatile var strikes = 0
        @Volatile var why = ""
    }

    private const val BINANCE = "binance"
    private const val OKX = "okx"
    private const val BYBIT = "bybit"
    private const val GATE = "gate"
    private const val MEXC = "mexc"
    private const val KUCOIN = "kucoin"
    private const val COINBASE = "coinbase"
    private const val KRAKEN = "kraken"
    private const val GECKO = "geckoterminal"
    private const val DEXS = "dexscreener"
    private const val PUMP = "pumpfun"
    private const val RAYDIUM = "raydium"
    private const val METEORA = "meteora"
    private const val GMGN = "gmgn"
    private const val PAPRIKA = "coinpaprika"
    private const val COINGECKO = "coingecko"
    private const val CMC = "coinmarketcap"
    private const val CRYPTOCOMPARE = "cryptocompare"
    private const val MORALIS = "moralis"
    private const val SOLTRACKER = "solanatracker"
    private const val CODEX = "codex"
    private const val BITQUERY = "bitquery"

    /** Order matters: the first listing round claims crypto bases in this order (best data first). */
    private val PROVS: List<Prov> = listOf(
        Prov(BINANCE, 350L, false, false),
        Prov(OKX, 250L, false, false),
        Prov(BYBIT, 250L, false, false),
        Prov(GATE, 300L, false, false),
        Prov(MEXC, 300L, false, false),
        Prov(KUCOIN, 500L, false, false),
        Prov(COINBASE, 400L, false, false),
        Prov(KRAKEN, 1_500L, false, false),
        Prov(GECKO, 10_000L, false, true),
        Prov(DEXS, 1_200L, false, true),
        Prov(PUMP, 2_000L, false, true),
        Prov(RAYDIUM, 1_000L, false, true),
        Prov(METEORA, 1_000L, false, true),
        Prov(GMGN, 20_000L, false, true),
        Prov(PAPRIKA, 2_500L, false, false),
        Prov(COINGECKO, 15_000L, false, false),
        Prov(CMC, 2_500L, true, false),
        Prov(CRYPTOCOMPARE, 1_000L, true, false),
        Prov(MORALIS, 1_500L, true, true),
        Prov(SOLTRACKER, 1_100L, true, true),
        Prov(CODEX, 3_000L, true, true),
        Prov(BITQUERY, 15_000L, true, true),
    )
    private val byId: Map<String, Prov> = PROVS.associateBy { it.id }

    // ── keys (BotConfig) ──

    private class Keys(
        val cmc: String = "", val moralis: String = "", val bitquery: String = "",
        val solanaTracker: String = "", val codex: String = "", val cryptoCompare: String = "",
    )
    @Volatile private var keys = Keys()
    @Volatile private var keysAtMs = 0L
    @Volatile private var appCtx: Context? = null

    private fun refreshKeys(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - keysAtMs < 60_000L) return
        keysAtMs = now
        val ctx = appCtx ?: return
        try {
            val k = com.lifecyclebot.data.ConfigStore.marketDataKeys7958(ctx)
            keys = Keys(k[0], k[1], k[2], k[3], k[4], k[5])
        } catch (_: Throwable) {}
    }

    private fun keyOf(id: String): String = keys.let {
        when (id) {
            CMC -> it.cmc; MORALIS -> it.moralis; BITQUERY -> it.bitquery
            SOLTRACKER -> it.solanaTracker; CODEX -> it.codex; CRYPTOCOMPARE -> it.cryptoCompare
            else -> ""
        }
    }

    private fun hasKey(p: Prov): Boolean = !p.keyed || keyOf(p.id).isNotBlank()

    // ── pure policy (unit-tested) ──

    /** Pure: how long a provider rests after a response; 0 = no rest (the job alone fails). */
    fun backoffMs7955(code: Int, strikes: Int, challenge: Boolean): Long {
        val s = strikes.coerceIn(0, 6)
        return when {
            challenge || code == 401 || code == 403 -> 6L * 3_600_000L
            code == 402 -> 24L * 3_600_000L
            code == 429 -> (10L * 60_000L shl s).coerceAtMost(2L * 3_600_000L)
            code < 0 || code >= 500 -> (30_000L shl s).coerceAtMost(30L * 60_000L)
            else -> 0L
        }
    }

    /** Pure: the first ready provider at or after [cursor] (cyclic), or -1. */
    fun pickNext7955(ready: BooleanArray, cursor: Int): Int {
        val n = ready.size
        if (n == 0) return -1
        for (k in 0 until n) {
            val i = ((cursor % n) + n + k) % n
            if (ready[i]) return i
        }
        return -1
    }

    /**
     * Pure: may a crypto candle job run now? Crypto may lead meme motifs by at most
     * 2x + 3,000 this build, unless every meme source is idle (empty or resting long).
     */
    fun familyAllowed7955(crypto: Boolean, cryptoMotifs: Long, memeMotifs: Long, memeIdle: Boolean): Boolean =
        !crypto || memeIdle || cryptoMotifs <= memeMotifs * 2 + 3_000L

    /** Round-robin over discovery origins: one queue per origin, served in turn. */
    class RoundRobin7955<T> {
        private val queues = LinkedHashMap<String, ArrayDeque<T>>()
        private var cursor = 0

        @Synchronized fun add(origin: String, item: T) { queues.getOrPut(origin) { ArrayDeque() }.addLast(item) }

        @Synchronized fun size(): Int = queues.values.sumOf { it.size }

        @Synchronized fun clear() { queues.clear(); cursor = 0 }

        private fun headKey(): String? {
            val keys = queues.keys.toList()
            if (keys.isEmpty()) return null
            for (k in keys.indices) {
                val key = keys[(cursor + k) % keys.size]
                if (queues[key]?.isNotEmpty() == true) return key
            }
            return null
        }

        /** What [poll] would return, without taking it. */
        @Synchronized fun peek(): T? = headKey()?.let { queues[it]?.firstOrNull() }

        @Synchronized fun poll(): T? {
            val key = headKey() ?: return null
            val keys = queues.keys.toList()
            cursor = (keys.indexOf(key) + 1) % keys.size
            return queues[key]?.removeFirstOrNull()
        }
    }

    // ── jobs ──

    private class Out(val bars: List<Bar7950> = emptyList(), val more: List<Job> = emptyList())

    /** [key] null = a listing / discovery call (never recorded as done). */
    private class Job(
        val key: String?, val prov: String, val origin: String, val source: Int, val url: String,
        val headers: Map<String, String> = emptyMap(), val post: String? = null,
        /** V5.0.7959 — tried when this job's endpoint refuses for good (e.g. a moved API). */
        val fallback: Job? = null, val parse: (String) -> Out,
    ) { var tries = 0 }

    private val queues: Map<String, RoundRobin7955<Job>> = PROVS.associate { it.id to RoundRobin7955<Job>() }
    private val cryptoBases: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pools: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var done: MutableSet<String> = HashSet()

    private val jobsDone = AtomicLong(0)
    private val jobsQueued = AtomicLong(0)
    private val jobsSkippedDone = AtomicLong(0)
    private val cryptoMotifs = AtomicLong(0)
    private val memeMotifs = AtomicLong(0)

    private fun enqueue(j: Job) {
        if (j.key != null && j.key in done) { jobsSkippedDone.incrementAndGet(); return }
        queues[j.prov]?.add(j.origin, j) ?: return
        jobsQueued.incrementAndGet()
    }

    private val STABLES = setOf("USDT", "USDC", "FDUSD", "TUSD", "DAI", "USDP", "BUSD", "EUR", "AEUR", "USDE", "USD1", "PAXG", "XUSD", "BFUSD", "PYUSD", "USDD", "GUSD", "XAUT", "EURC", "USDS")

    private fun leveraged(b: String): Boolean =
        b.length > 4 && (b.endsWith("DOWN") || b.endsWith("BULL") || b.endsWith("BEAR") || b.endsWith("UP") || b.endsWith("3L") || b.endsWith("3S") || b.endsWith("5L") || b.endsWith("5S"))

    /** First [n] listings whose base no earlier exchange has claimed (breadth over duplicate majors). */
    private fun claim(list: List<ChartParsers7955.Listing7955>, n: Int): List<ChartParsers7955.Listing7955> {
        val out = ArrayList<ChartParsers7955.Listing7955>()
        for (l in list) {
            if (out.size >= n) break
            val b = l.base.uppercase()
            if (b.isBlank() || b in STABLES || leveraged(b)) continue
            if (cryptoBases.add(b)) out += l
        }
        return out
    }

    private fun json(body: String): Any = body.trimStart().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }

    private fun crypto(prov: String, key: String, url: String, headers: Map<String, String> = emptyMap(), parse: (String) -> List<Bar7950>) =
        Job(key, prov, prov, ChartLibrary7950.SRC_CRYPTO, url, headers) { Out(parse(it)) }

    private fun listing(prov: String, origin: String, url: String, headers: Map<String, String> = emptyMap(), post: String? = null, parse: (String) -> List<Job>) =
        Job(null, prov, origin, -1, url, headers, post) { Out(more = parse(it)) }

    private fun sourceFor(network: String): Int = if (network == "solana") ChartLibrary7950.SRC_SOL_MEME else ChartLibrary7950.SRC_BSC_MEME

    private const val GECKO_API = "https://api.geckoterminal.com/api/v2"
    private val GECKO_FULL = listOf("minute" to 1, "minute" to 5, "minute" to 15, "hour" to 1, "day" to 1)
    private val GECKO_SHORT = listOf("minute" to 1, "minute" to 5, "minute" to 15)

    /** GeckoTerminal candle jobs for a pool (keys match 5.0.7950's "G|net|addr|tfN"). */
    private fun geckoPool(network: String, address: String, origin: String, full: Boolean = false): List<Job> {
        if (address.isBlank() || !pools.add("$network|$address")) return emptyList()
        return (if (full) GECKO_FULL else GECKO_SHORT).map { (tf, agg) ->
            Job("G|$network|$address|$tf$agg", GECKO, origin, sourceFor(network), "$GECKO_API/networks/$network/pools/$address/ohlcv/$tf?aggregate=$agg&limit=1000") {
                Out(ChartLibraryBuilder7950.parseGecko(JSONObject(it)))
            }
        }
    }

    /** The famous runners, found by name on Solana (pump.fun era and its peers). */
    private val FAMOUS = listOf(
        "fartcoin", "goat", "pnut", "moodeng", "chillguy", "pippin", "zerebro", "griffain", "act",
        "fwog", "michi", "retardio", "swarms", "arc", "vine", "house", "troll", "jailstool", "butthole",
        "alch", "useless", "aura", "titcoin", "ban", "luce", "lockin", "gork", "pwease", "nub", "wojak",
        "giga", "popcat", "wif", "bonk", "bome", "mew", "ponke", "slerf", "myro", "boden",
    )

    private val pumpMints: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private const val PUMP_API = "https://frontend-api-v3.pump.fun"
    private const val PUMP_SWAP_API = "https://swap-api.pump.fun"

    /** V5.0.7959 — swap-api candles come as a bare array or wrapped ({candles|data:[...]}). */
    private fun pumpCandleArray7959(body: String): JSONArray {
        val t = body.trimStart()
        if (t.startsWith("[")) return JSONArray(t)
        val o = JSONObject(t)
        return o.optJSONArray("candles") ?: o.optJSONArray("data") ?: JSONArray()
    }
    private const val MORALIS_API = "https://solana-gateway.moralis.io"
    private const val SOL_MINT = "So11111111111111111111111111111111111111112"
    private const val CODEX_SOLANA = 1399811149

    private val JSON_MT = "application/json".toMediaType()

    private fun codexBody(mint: String, fromSec: Long, toSec: Long): String =
        JSONObject().put("query",
            "query B(\$s: String!, \$f: Int!, \$t: Int!, \$r: String!) { getBars(symbol: \$s, from: \$f, to: \$t, resolution: \$r, removeEmptyBars: true) { t o h l c volume buyVolume } }")
            .put("variables", JSONObject().put("s", "$mint:$CODEX_SOLANA").put("f", fromSec).put("t", toSec).put("r", "1"))
            .toString()

    private fun bitqueryBody(mint: String, sinceMs: Long): String =
        JSONObject().put("query",
            "query B(\$m: String!, \$since: DateTime!) { Solana { DEXTradeByTokens(orderBy: {descendingByField: \"Block_Timefield\"}, " +
                "where: {Trade: {Currency: {MintAddress: {is: \$m}}, Side: {Currency: {MintAddress: {is: \"$SOL_MINT\"}}}}, Block: {Time: {since: \$since}}}, limit: {count: 300}) { " +
                "Block { Timefield: Time(interval: {in: minutes, count: 1}) } volume: sum(of: Trade_Side_Amount) " +
                "buyVolume: sum(of: Trade_Side_Amount, if: {Trade: {Side: {Type: {is: buy}}}}) " +
                "Trade { high: Price(maximum: Trade_Price) low: Price(minimum: Trade_Price) open: Price(minimum: Block_Slot) close: Price(maximum: Block_Slot) } } } }")
            .put("variables", JSONObject().put("m", mint).put("since", java.time.Instant.ofEpochMilli(sinceMs).toString()))
            .toString()

    private fun codexHeaders(): Map<String, String> = mapOf("Authorization" to keys.codex, "content-type" to "application/json")
    private fun bitqueryHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer ${keys.bitquery}", "content-type" to "application/json")
    private fun moralisHeaders(): Map<String, String> = mapOf("X-API-Key" to keys.moralis)
    private fun solTrackerHeaders(): Map<String, String> = mapOf("x-api-key" to keys.solanaTracker)

    /** Keyed per-mint candle jobs for a pump.fun-discovered mint (Codex, Bitquery: both carry the buy split). */
    private fun keyedMintJobs(mint: String): List<Job> {
        if (!pumpMints.add(mint)) return emptyList()
        val out = ArrayList<Job>()
        val to = nowSec()
        if (keys.codex.isNotBlank() && pumpMints.size <= 20) {
            out += Job("CX|$mint|1", CODEX, PUMP, ChartLibrary7950.SRC_SOL_MEME, "https://graph.codex.io/graphql", codexHeaders(), codexBody(mint, to - 24 * 3600, to)) {
                Out(ChartParsers7955.codex7955(JSONObject(it)))
            }
        }
        if (keys.bitquery.isNotBlank() && pumpMints.size <= 10) {
            out += Job("BQ|$mint|1", BITQUERY, PUMP, ChartLibrary7950.SRC_SOL_MEME, "https://streaming.bitquery.io/eap", bitqueryHeaders(),
                bitqueryBody(mint, System.currentTimeMillis() - 6 * 3_600_000L)) { Out(ChartParsers7955.bitquery7955(JSONObject(it))) }
        }
        return out
    }

    /** Every provider's first calls (listing / discovery). Keyed providers only when their key is set. */
    private fun seedJobs(): List<Job> {
        val out = ArrayList<Job>()
        val bin = "https://data-api.binance.vision/api/v3"
        out += listing(BINANCE, BINANCE, "$bin/ticker/24hr") { body ->
            claim(ChartParsers7955.rankObjects7955(JSONArray(body), "symbol", "quoteVolume", "USDT"), 200).flatMap { l ->
                listOf("1m", "5m", "15m", "1h", "4h", "1d").map { iv ->
                    crypto(BINANCE, "B|${l.id}|$iv", "$bin/klines?symbol=${l.id}&interval=$iv&limit=1000") { ChartLibraryBuilder7950.parseBinance(JSONArray(it)) }
                }
            }
        }
        out += listing(OKX, OKX, "https://www.okx.com/api/v5/market/tickers?instType=SPOT") { body ->
            claim(ChartParsers7955.okxTickers7955(JSONObject(body)), 80).flatMap { l ->
                listOf("1m", "5m", "15m", "1H", "4H", "1D").map { b ->
                    crypto(OKX, "OKX|${l.id}|$b", "https://www.okx.com/api/v5/market/history-candles?instId=${l.id}&bar=$b&limit=100") { ChartParsers7955.okx7955(JSONObject(it)) }
                }
            }
        }
        out += listing(BYBIT, BYBIT, "https://api.bybit.com/v5/market/tickers?category=spot") { body ->
            claim(ChartParsers7955.bybitTickers7955(JSONObject(body)), 80).flatMap { l ->
                listOf("1", "5", "15", "60", "240", "D").map { iv ->
                    crypto(BYBIT, "BY|${l.id}|$iv", "https://api.bybit.com/v5/market/kline?category=spot&symbol=${l.id}&interval=$iv&limit=1000") { ChartParsers7955.bybit7955(JSONObject(it)) }
                }
            }
        }
        out += listing(GATE, GATE, "https://api.gateio.ws/api/v4/spot/tickers") { body ->
            claim(ChartParsers7955.rankObjects7955(JSONArray(body), "currency_pair", "quote_volume", "USDT"), 80).flatMap { l -> gateJobs(l.id, GATE, listOf("1m", "5m", "15m", "1h", "4h", "1d")) }
        }
        out += listing(MEXC, MEXC, "https://api.mexc.com/api/v3/ticker/24hr") { body ->
            claim(ChartParsers7955.rankObjects7955(JSONArray(body), "symbol", "quoteVolume", "USDT"), 80).flatMap { l ->
                listOf("1m", "5m", "15m", "60m", "4h", "1d").map { iv ->
                    crypto(MEXC, "MX|${l.id}|$iv", "https://api.mexc.com/api/v3/klines?symbol=${l.id}&interval=$iv&limit=1000") { ChartLibraryBuilder7950.parseBinance(JSONArray(it)) }
                }
            }
        }
        out += listing(KUCOIN, KUCOIN, "https://api.kucoin.com/api/v1/market/allTickers") { body ->
            claim(ChartParsers7955.kucoinTickers7955(JSONObject(body)), 60).flatMap { l ->
                listOf("1min", "5min", "15min", "1hour", "4hour", "1day").map { t ->
                    crypto(KUCOIN, "KC|${l.id}|$t", "https://api.kucoin.com/api/v1/market/candles?type=$t&symbol=${l.id}") { ChartParsers7955.kucoin7955(JSONObject(it)) }
                }
            }
        }
        out += listing(COINBASE, COINBASE, "https://api.exchange.coinbase.com/products") { body ->
            claim(ChartParsers7955.coinbaseProducts7955(JSONArray(body)), 60).flatMap { l ->
                listOf(60, 300, 900, 3600, 21600, 86400).map { g ->
                    crypto(COINBASE, "CB|${l.id}|$g", "https://api.exchange.coinbase.com/products/${l.id}/candles?granularity=$g") { ChartParsers7955.coinbase7955(JSONArray(it)) }
                }
            }
        }
        out += listing(KRAKEN, KRAKEN, "https://api.kraken.com/0/public/Ticker") { body ->
            claim(ChartParsers7955.krakenTickers7955(JSONObject(body)), 40).flatMap { l ->
                listOf(1, 5, 15, 60, 240, 1440).map { iv ->
                    crypto(KRAKEN, "KR|${l.id}|$iv", "https://api.kraken.com/0/public/OHLC?pair=${l.id}&interval=$iv") { ChartParsers7955.kraken7955(JSONObject(it)) }
                }
            }
        }
        out += geckoSeeds()
        out += dexScreenerSeeds()
        out += listing(PUMP, PUMP, "$PUMP_API/coins/currently-live?offset=0&limit=60&sort=currently_live&order=DESC&includeNsfw=false") { pumpCoins(it) }
        out += listing(PUMP, PUMP, "$PUMP_API/coins/king-of-the-hill?includeNsfw=false") { pumpCoins(it) }
        out += listing(RAYDIUM, RAYDIUM, "https://api-v3.raydium.io/pools/info/list?poolType=all&poolSortField=volume24h&sortType=desc&pageSize=100&page=1") { body ->
            ChartParsers7955.raydiumPools7955(JSONObject(body)).take(60).flatMap { geckoPool("solana", it, RAYDIUM) }
        }
        out += listing(METEORA, METEORA, "https://dlmm-api.meteora.ag/pair/all_with_pagination?page=0&limit=60&sort_key=volume&order_by=desc") { body ->
            ChartParsers7955.meteoraPools7955(JSONObject(body)).take(60).flatMap { geckoPool("solana", it, METEORA) }
        }
        out += listing(GMGN, GMGN, "https://gmgn.ai/defi/quotation/v1/rank/sol/swaps/1h?orderby=volume&direction=desc") { body ->
            val to = nowSec()
            ChartParsers7955.gmgnRank7955(JSONObject(body)).take(25).map { a ->
                Job("GM|$a|1m", GMGN, GMGN, ChartLibrary7950.SRC_SOL_MEME, "https://gmgn.ai/api/v1/token_kline/sol/$a?resolution=1m&from=${to - 24 * 3600}&to=$to") {
                    Out(ChartParsers7955.gmgn7955(JSONObject(it)))
                }
            }
        }
        out += listing(PAPRIKA, PAPRIKA, "https://api.coinpaprika.com/v1/tickers") { body ->
            val start = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(364).toString()
            claim(ChartParsers7955.paprikaTickers7955(JSONArray(body)), 60).map { l ->
                crypto(PAPRIKA, "CP|${l.id}|1d", "https://api.coinpaprika.com/v1/coins/${l.id}/ohlcv/historical?start=$start&interval=1d&limit=366") { ChartParsers7955.paprika7955(JSONArray(it)) }
            }
        }
        out += listing(COINGECKO, COINGECKO, "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=volume_desc&per_page=250&page=1") { body ->
            claim(ChartParsers7955.coingeckoMarkets7955(JSONArray(body)), 250).flatMap { l ->
                listOf(1, 30).map { d ->
                    crypto(COINGECKO, "CG|${l.id}|$d", "https://api.coingecko.com/api/v3/coins/${l.id}/ohlc?vs_currency=usd&days=$d") { ChartParsers7955.coingecko7955(JSONArray(it)) }
                }
            }
        }
        out += keyedSeeds()
        return out
    }

    private fun gateJobs(pair: String, origin: String, intervals: List<String>): List<Job> = intervals.map { iv ->
        Job("GT|$pair|$iv", GATE, origin, ChartLibrary7950.SRC_CRYPTO, "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=$pair&interval=$iv&limit=1000") {
            Out(ChartParsers7955.gate7955(JSONArray(it)))
        }
    }

    private fun geckoSeeds(): List<Job> {
        val out = ArrayList<Job>()
        fun pages(network: String, path: String, n: Int, full: Boolean, origin: String) {
            for (p in 1..n) out += listing(GECKO, origin, "$GECKO_API/$path${if (path.contains('?')) '&' else '?'}page=$p") { body ->
                ChartParsers7955.geckoPools7955(JSONObject(body)).flatMap { geckoPool(network, it, origin, full) }
            }
        }
        // 5.0.7950's lists (all timeframes), then 5.0.7955's new networks and new pools.
        pages("solana", "networks/solana/dexes/pumpswap/pools", 5, true, "g-pumpswap")
        pages("solana", "networks/solana/dexes/pump-fun/pools", 3, true, "g-pumpfun")
        pages("solana", "networks/solana/trending_pools", 3, true, "g-sol-trending")
        pages("solana", "networks/solana/pools", 3, true, "g-sol-top")
        pages("bsc", "networks/bsc/trending_pools", 3, true, "g-bsc-trending")
        pages("bsc", "networks/bsc/pools", 2, true, "g-bsc-top")
        pages("solana", "networks/solana/new_pools", 3, false, "g-sol-new")
        pages("bsc", "networks/bsc/new_pools", 2, false, "g-bsc-new")
        pages("base", "networks/base/trending_pools", 3, false, "g-base-trending")
        pages("base", "networks/base/new_pools", 2, false, "g-base-new")
        pages("eth", "networks/eth/trending_pools", 2, false, "g-eth-trending")
        pages("eth", "networks/eth/new_pools", 1, false, "g-eth-new")
        for (name in FAMOUS) out += listing(GECKO, "g-famous", "$GECKO_API/search/pools?query=$name&network=solana") { body ->
            ChartParsers7955.geckoPools7955(JSONObject(body)).take(1).flatMap { geckoPool("solana", it, "g-famous", true) }
        }
        return out
    }

    private fun dexScreenerSeeds(): List<Job> {
        val out = ArrayList<Job>()
        val api = "https://api.dexscreener.com"
        for (path in listOf("token-boosts/latest/v1", "token-boosts/top/v1", "token-profiles/latest/v1")) {
            out += listing(DEXS, DEXS, "$api/$path") { body ->
                ChartParsers7955.dexTokens7955(JSONArray(body)).groupBy({ it.first }, { it.second }).flatMap { (chain, addrs) ->
                    addrs.distinct().chunked(30).map { chunk ->
                        listing(DEXS, DEXS, "$api/tokens/v1/$chain/${chunk.joinToString(",")}") { b2 ->
                            ChartParsers7955.dexPairs7955(json(b2)).flatMap { geckoPool(it.network, it.address, DEXS) }
                        }
                    }
                }
            }
        }
        for (q in listOf("pump", "bonk", "solana", "bnb", "base")) {
            out += listing(DEXS, DEXS, "$api/latest/dex/search?q=$q") { body ->
                ChartParsers7955.dexPairs7955(json(body)).take(30).flatMap { geckoPool(it.network, it.address, DEXS) }
            }
        }
        return out
    }

    private fun pumpCoins(body: String): List<Job> {
        val out = ArrayList<Job>()
        for ((mint, pool) in ChartParsers7955.pumpCoins7955(json(body))) {
            // V5.0.7959 — the v3 candlestick route failed 64/65 live; pump.fun's swap API serves
            // the same candles. Try it first, fall back to v3 once it refuses.
            val v3 = Job("PF|$mint|1m", PUMP, PUMP, ChartLibrary7950.SRC_SOL_MEME, "$PUMP_API/candlesticks/$mint?offset=0&limit=1000&timeframe=1") {
                Out(ChartParsers7955.pumpFun7955(JSONArray(it)))
            }
            out += Job("PF|$mint|1m", PUMP, PUMP, ChartLibrary7950.SRC_SOL_MEME, "$PUMP_SWAP_API/v2/coins/$mint/candles?interval=1m&limit=1000&currency=USD", fallback = v3) {
                Out(ChartParsers7955.pumpFun7955(pumpCandleArray7959(it)))
            }
            if (pool.isNotBlank()) out += geckoPool("solana", pool, PUMP)
            out += keyedMintJobs(mint)
        }
        return out
    }

    private fun keyedSeeds(): List<Job> {
        val out = ArrayList<Job>()
        val k = keys
        if (k.cmc.isNotBlank()) {
            val h = mapOf("X-CMC_PRO_API_KEY" to k.cmc)
            // CMC's free plan has no historical OHLCV; its listings add the long tail, charted on Gate.io.
            out += listing(CMC, CMC, "https://pro-api.coinmarketcap.com/v1/cryptocurrency/listings/latest?limit=300&sort=volume_24h", h) { body ->
                claim(ChartParsers7955.cmcListings7955(JSONObject(body)), 60).flatMap { l -> gateJobs("${l.base}_USDT", CMC, listOf("5m", "1h", "4h")) }
            }
            out += listing(CMC, CMC, "https://pro-api.coinmarketcap.com/v4/dex/spot-pairs/latest?network_slug=solana&sort=volume_24h&limit=100", h) { body ->
                ChartParsers7955.cmcDexPairs7955(JSONObject(body)).take(40).flatMap { geckoPool("solana", it, CMC) }
            }
        }
        if (k.cryptoCompare.isNotBlank()) {
            val h = mapOf("authorization" to "Apikey ${k.cryptoCompare}")
            out += listing(CRYPTOCOMPARE, CRYPTOCOMPARE, "https://min-api.cryptocompare.com/data/top/totalvolfull?limit=100&tsym=USD", h) { body ->
                claim(ChartParsers7955.cryptoCompareTop7955(JSONObject(body)), 60).flatMap { l ->
                    listOf("histominute", "histohour").map { ep ->
                        crypto(CRYPTOCOMPARE, "CC|${l.id}|$ep", "https://min-api.cryptocompare.com/data/v2/$ep?fsym=${l.id}&tsym=USD&limit=2000", h) { ChartParsers7955.cryptoCompare7955(JSONObject(it)) }
                    }
                }
            }
        }
        if (k.moralis.isNotBlank()) {
            for (kind in listOf("graduated", "new")) {
                out += listing(MORALIS, MORALIS, "$MORALIS_API/token/mainnet/exchange/pumpfun/$kind?limit=25", moralisHeaders()) { body ->
                    ChartParsers7955.moralisTokens7955(JSONObject(body)).take(25).map { mint ->
                        listing(MORALIS, MORALIS, "$MORALIS_API/token/mainnet/$mint/pairs", moralisHeaders()) { b2 ->
                            val pair = ChartParsers7955.moralisBestPair7955(JSONObject(b2)) ?: return@listing emptyList()
                            val to = nowSec()
                            listOf(Job("MO|$pair|1min", MORALIS, MORALIS, ChartLibrary7950.SRC_SOL_MEME, moralisOhlcvUrl(pair, to - 24 * 3600, to), moralisHeaders()) {
                                Out(ChartParsers7955.moralis7955(JSONObject(it)))
                            })
                        }
                    }
                }
            }
        }
        if (k.solanaTracker.isNotBlank()) {
            out += listing(SOLTRACKER, SOLTRACKER, "https://data.solanatracker.io/tokens/trending", solTrackerHeaders()) { body ->
                val to = nowSec()
                ChartParsers7955.solanaTrackerTrending7955(JSONArray(body)).take(30).flatMap { mint ->
                    listOf("1m", "5m").map { t ->
                        Job("ST|$mint|$t", SOLTRACKER, SOLTRACKER, ChartLibrary7950.SRC_SOL_MEME,
                            "https://data.solanatracker.io/chart/$mint?type=$t&time_from=${to - 3 * 86400}&time_to=$to", solTrackerHeaders()) {
                            Out(ChartParsers7955.solanaTracker7955(JSONObject(it)))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun moralisOhlcvUrl(pair: String, fromSec: Long, toSec: Long): String =
        "$MORALIS_API/token/mainnet/pairs/$pair/ohlcv?timeframe=1min&currency=usd&fromDate=$fromSec&toDate=$toSec&limit=1000"

    // ── HTTP ──

    private val http by lazy { com.lifecyclebot.network.SharedHttpClient.base }
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private class Res(val code: Int, val body: String?)

    /** Claim [p]'s next slot now (true) or not (false); shared by the build and backfill threads. */
    private fun tryClaim(p: Prov, now: Long): Boolean {
        synchronized(p) {
            if (!hasKey(p) || now < p.backoffUntilMs || now < p.nextAtMs) return false
            p.nextAtMs = now + p.gapMs
            return true
        }
    }

    /** Wait at most [maxWaitMs] for [p]'s slot (backfill: never queue behind a resting provider). */
    private fun claimWithin(p: Prov, maxWaitMs: Long): Boolean {
        val until = System.currentTimeMillis() + maxWaitMs
        while (true) {
            val now = System.currentTimeMillis()
            if (tryClaim(p, now)) return true
            if (!hasKey(p) || p.backoffUntilMs > now || now >= until) return false
            Thread.sleep((p.nextAtMs - now).coerceIn(50L, 1_000L))
        }
    }

    /** One call on an already-claimed slot; counts it and applies the provider's backoff. */
    private fun call(p: Prov, url: String, headers: Map<String, String>, post: String?): Res {
        p.calls.incrementAndGet()
        val r = try {
            val b = Request.Builder().url(url).header("accept", "application/json").header("user-agent", UA)
            for ((k, v) in headers) b.header(k, v)
            if (post != null) b.post(post.toRequestBody(JSON_MT))
            http.newCall(b.build()).execute().use { resp -> Res(resp.code, resp.body?.string()) }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Throwable) {
            Res(-1, null)
        }
        val challenge = ChartParsers7955.isChallenge7955(r.code, r.body)
        if (r.code in 200..299 && !challenge && r.body != null) {
            p.ok.incrementAndGet()
            p.strikes = 0
            return r
        }
        p.fail.incrementAndGet()
        val rest = backoffMs7955(r.code, p.strikes, challenge)
        if (rest > 0L) {
            synchronized(p) {
                p.backoffUntilMs = maxOf(p.backoffUntilMs, System.currentTimeMillis() + rest)
                p.strikes = (p.strikes + 1).coerceAtMost(6)
                p.why = if (challenge) "cloudflare" else if (r.code < 0) "io" else r.code.toString()
            }
            if (rest >= 6L * 3_600_000L) try {
                ForensicLogger.lifecycle("CHART_SOURCE_DISABLED_7955", "provider=${p.id} why=${p.why} hours=${rest / 3_600_000L}")
            } catch (_: Throwable) {}
        }
        return Res(if (challenge) 403 else r.code, null)
    }

    // ── build ──

    private const val LONG_REST_MS = 20L * 60_000L

    /**
     * Run every provider's jobs into the library. [done] holds finished candle-job
     * keys (resumable). Returns true when every queue drained, false when the only
     * work left belongs to providers resting longer than 20 minutes (resume later).
     */
    fun build7955(done: MutableSet<String>, checkpoint: () -> Unit, tick: () -> Unit): Boolean {
        refreshKeys(force = true)
        this.done = done
        queues.values.forEach { it.clear() }
        cryptoBases.clear(); pools.clear(); pumpMints.clear()
        cryptoMotifs.set(0); memeMotifs.set(0)
        seedJobs().forEach { enqueue(it) }
        val n = PROVS.size
        var cursor = 0
        var sinceSave = 0
        while (true) {
            tick()
            refreshKeys()
            val now = System.currentTimeMillis()
            val memeIdle = PROVS.none { it.meme && queues[it.id]?.peek() != null && it.backoffUntilMs - now <= LONG_REST_MS }
            val ready = BooleanArray(n) { i ->
                val p = PROVS[i]
                val head = queues[p.id]?.peek()
                head != null && hasKey(p) && now >= p.backoffUntilMs && now >= p.nextAtMs &&
                    (head.key == null || familyAllowed7955(head.source == ChartLibrary7950.SRC_CRYPTO, cryptoMotifs.get(), memeMotifs.get(), memeIdle))
            }
            val i = pickNext7955(ready, cursor)
            if (i < 0) {
                val pending = PROVS.filter { queues[it.id]?.peek() != null }
                // A provider that rests 6 h+ (403 / challenge / plan) or lost its key drops this build's jobs.
                pending.filter { !hasKey(it) || it.backoffUntilMs - now >= 6L * 3_600_000L }.forEach { queues[it.id]?.clear() }
                val live = PROVS.filter { queues[it.id]?.peek() != null }
                if (live.isEmpty()) return true
                if (live.all { it.backoffUntilMs - now > LONG_REST_MS }) return false
                val wait = live.minOf { maxOf(it.nextAtMs, it.backoffUntilMs) - now }
                Thread.sleep(wait.coerceIn(250L, 5_000L))
                continue
            }
            cursor = (i + 1) % n
            val p = PROVS[i]
            if (p.id == GECKO && !geckoFeedReady(p, now)) continue
            if (!tryClaim(p, now)) continue
            val job = queues[p.id]?.poll() ?: continue
            sinceSave += runJob(p, job)
            if (sinceSave >= 2_000) { sinceSave = 0; checkpoint() }
        }
    }

    /** The live candle feed owns GeckoTerminal: never call while it is cooling down or holding its slot. */
    private fun geckoFeedReady(p: Prov, now: Long): Boolean {
        val slot = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809(now) } catch (_: Throwable) { null } ?: return true
        if (!slot.cooldown && slot.waitMs <= 0L) return true
        p.nextAtMs = now + slot.waitMs.coerceIn(1_000L, 15_000L)
        p.skips.incrementAndGet()
        return false
    }

    /** Run one job; returns motifs added. */
    private fun runJob(p: Prov, job: Job): Int {
        val r = call(p, job.url, job.headers, job.post)
        val body = r.body
        if (body == null) {
            val transient = r.code < 0 || r.code == 429 || r.code >= 500 || r.code == 401 || r.code == 403
            if (transient && ++job.tries < 3) queues[job.prov]?.add(job.origin, job)
            else job.fallback?.let { queues[it.prov]?.add(it.origin, it) }
            return 0
        }
        val out = try { job.parse(body) } catch (_: Throwable) { p.fail.incrementAndGet(); Out() }
        out.more.forEach { enqueue(it) }
        if (job.key == null) return 0
        var motifs = 0
        if (out.bars.size > ChartMotif7950.WINDOW + ChartMotif7950.HORIZON + 2 && job.source >= 0) {
            motifs = ChartLibrary7950.ingestSeries(out.bars, job.source, 40)
            if (job.source == ChartLibrary7950.SRC_CRYPTO) cryptoMotifs.addAndGet(motifs.toLong()) else memeMotifs.addAndGet(motifs.toLong())
        }
        p.bars.addAndGet(out.bars.size.toLong())
        done += job.key
        jobsDone.incrementAndGet()
        return motifs
    }

    // ── live backfill ──

    private const val BACKFILL_TICK_MS = 8_000L
    private const val BACKFILL_RETRY_MS = 10L * 60_000L
    private const val BACKFILL_MINUTES = 90
    private val backfillTried = ConcurrentHashMap<String, Long>()
    private val backfillAsked = AtomicLong(0)
    private val backfillSeeded = AtomicLong(0)
    private val backfillBars = AtomicLong(0)
    private val backfillBySource = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var backfillStarted = false

    /** BotService start (via ChartLibraryBuilder7950): remember the context for keys and start the backfill thread. */
    fun start7955(context: Context) {
        appCtx = context.applicationContext
        refreshKeys(force = true)
        if (backfillStarted) return
        backfillStarted = true
        Thread({
            try {
                Thread.sleep(45_000L)
                while (true) {
                    try { backfillOnce() } catch (e: InterruptedException) { throw e } catch (_: Throwable) {}
                    Thread.sleep(BACKFILL_TICK_MS)
                }
            } catch (_: InterruptedException) {}
        }, "chart-backfill-7955").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun heldMints(): List<String> = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.protectiveInventory7807().map { it.mint }.distinct()
    } catch (_: Throwable) { emptyList() }

    /** Pure: the next mint to backfill — held first, then candidates — that is short and not tried recently. */
    fun nextBackfill7955(held: List<String>, candidates: List<String>, liveBars: (String) -> Int, triedAt: (String) -> Long, nowMs: Long, retryMs: Long = BACKFILL_RETRY_MS): String? {
        for (m in held + candidates) {
            if (!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(m)) continue
            if (nowMs - triedAt(m) < retryMs) continue
            if (liveBars(m) >= ChartMotif7950.WINDOW + 1) continue
            return m
        }
        return null
    }

    private fun backfillOnce() {
        refreshKeys()
        val now = System.currentTimeMillis()
        val mint = nextBackfill7955(heldMints(), ChartReader7950.shortTapes7955(), { ChartReader7950.liveBars7955(it) }, { backfillTried[it] ?: 0L }, now) ?: return
        backfillTried[mint] = now
        if (backfillTried.size > 3_000) backfillTried.entries.removeIf { now - it.value > BACKFILL_RETRY_MS }
        backfillAsked.incrementAndGet()
        val fromSec = now / 1000L - BACKFILL_MINUTES * 60L
        val toSec = now / 1000L
        val sources: List<Pair<String, () -> List<Bar7950>?>> = listOf(
            "geckoFeed" to { feedBars(mint) },
            MORALIS to { moralisBars(mint, fromSec, toSec) },
            SOLTRACKER to { keyedBars(SOLTRACKER, "https://data.solanatracker.io/chart/$mint?type=1m&time_from=$fromSec&time_to=$toSec", solTrackerHeaders(), null) { ChartParsers7955.solanaTracker7955(JSONObject(it)) } },
            CODEX to { keyedBars(CODEX, "https://graph.codex.io/graphql", codexHeaders(), codexBody(mint, fromSec, toSec)) { ChartParsers7955.codex7955(JSONObject(it)) } },
            BITQUERY to { keyedBars(BITQUERY, "https://streaming.bitquery.io/eap", bitqueryHeaders(), bitqueryBody(mint, now - BACKFILL_MINUTES * 60_000L)) { ChartParsers7955.bitquery7955(JSONObject(it)) } },
        )
        for ((name, fetch) in sources) {
            val bars = try { fetch() } catch (e: InterruptedException) { throw e } catch (_: Throwable) { null }
            if (bars == null || bars.size < 5) continue
            // Live tape volumes are SOL; Bitquery already reports the SOL side, but its PRICES are
            // SOL per token (V5.0.7955 review) and the tape is USD: convert, or skip with no SOL price.
            val sol = solUsd()
            val ready = if (name == BITQUERY) {
                if (!(sol > 0.0)) continue
                bars.map { it.copy(o = it.o * sol, h = it.h * sol, l = it.l * sol, c = it.c * sol) }
            } else usdBarsToSol7955(bars, sol)
            val seeded = ChartReader7950.seedBars7955(mint, ready, now)
            if (seeded > 0) {
                backfillSeeded.incrementAndGet()
                backfillBars.addAndGet(seeded.toLong())
                backfillBySource.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CHART_BACKFILL_7955_${name.uppercase()}") } catch (_: Throwable) {}
            }
            return
        }
    }

    private fun solUsd(): Double = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }

    /** Pure: USD-volume candles -> SOL-volume candles (no SOL price -> zero volume, no split). */
    fun usdBarsToSol7955(bars: List<Bar7950>, solUsdPrice: Double): List<Bar7950> = bars.map { b ->
        val v = com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(b.v, solUsdPrice)
        val bv = com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(b.buyV, solUsdPrice)
        if (v.isFinite()) b.copy(v = v, buyV = if (bv.isFinite()) bv else Double.NaN) else b.copy(v = 0.0, buyV = Double.NaN)
    }

    /** The app's keyless candle feed (DexPaprika, then the GeckoTerminal pool), already paced by its own gate. */
    private fun feedBars(mint: String): List<Bar7950>? {
        val c = com.lifecyclebot.network.SolanaOhlcvFeed6916.fetchCandles6916(mint, "1m", BACKFILL_MINUTES)
        if (c.isEmpty()) return null
        return c.mapNotNull { k ->
            ChartParsers7955.bar7955(k.ts, if (k.openUsd > 0.0) k.openUsd else k.priceUsd, if (k.highUsd > 0.0) k.highUsd else k.priceUsd,
                if (k.lowUsd > 0.0) k.lowUsd else k.priceUsd, k.priceUsd, k.vol)
        }
    }

    private fun keyedBars(id: String, url: String, headers: Map<String, String>, post: String?, parse: (String) -> List<Bar7950>): List<Bar7950>? {
        val p = byId[id] ?: return null
        if (!hasKey(p) || !claimWithin(p, 3_000L)) return null
        val body = call(p, url, headers, post).body ?: return null
        return parse(body).also { p.bars.addAndGet(it.size.toLong()) }
    }

    private fun moralisBars(mint: String, fromSec: Long, toSec: Long): List<Bar7950>? {
        val p = byId[MORALIS] ?: return null
        if (!hasKey(p) || !claimWithin(p, 3_000L)) return null
        val pairs = call(p, "$MORALIS_API/token/mainnet/$mint/pairs", moralisHeaders(), null).body ?: return null
        val pair = ChartParsers7955.moralisBestPair7955(JSONObject(pairs)) ?: return null
        return keyedBars(MORALIS, moralisOhlcvUrl(pair, fromSec, toSec), moralisHeaders(), null) { ChartParsers7955.moralis7955(JSONObject(it)) }
    }

    // ── diag ──

    /** One line per provider: calls, ok, fail, bars, rest / disabled, key. */
    fun diagLines7955(nowMs: Long = System.currentTimeMillis()): List<String> {
        val out = ArrayList<String>(PROVS.size + 2)
        out += "chart sources (§7955): jobsDone=${jobsDone.get()} queued=${jobsQueued.get()} skippedDone=${jobsSkippedDone.get()} " +
            "pending=${queues.values.sumOf { it.size() }} motifs[crypto=${cryptoMotifs.get()} meme=${memeMotifs.get()}]"
        for (p in PROVS) {
            val state = when {
                p.keyed && !hasKey(p) -> "no key"
                p.backoffUntilMs - nowMs >= 6L * 3_600_000L -> "disabled ${(p.backoffUntilMs - nowMs) / 60_000L}m (${p.why})"
                p.backoffUntilMs > nowMs -> "backoff ${(p.backoffUntilMs - nowMs) / 1_000L}s (${p.why})"
                else -> "ok"
            }
            val key = if (!p.keyed) "free" else if (hasKey(p)) "present" else "missing"
            out += "  ${p.id.padEnd(14)} calls=${p.calls.get()} ok=${p.ok.get()} fail=${p.fail.get()} bars=${p.bars.get()} " +
                "skips=${p.skips.get()} queue=${queues[p.id]?.size() ?: 0} state=$state key=$key"
        }
        out += "  backfill       asked=${backfillAsked.get()} seeded=${backfillSeeded.get()} bars=${backfillBars.get()} " +
            "by=${backfillBySource.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} short=${ChartReader7950.shortTapes7955().size}"
        return out
    }

    /** Short summary for the builder's status line. */
    fun summary7955(): String =
        "jobsDone=${jobsDone.get()} pending=${queues.values.sumOf { it.size() }} skippedDone=${jobsSkippedDone.get()} " +
            "geckoFeedBusy=${byId[GECKO]?.skips?.get() ?: 0} live=${PROVS.count { it.calls.get() > 0 && it.ok.get() > 0 }}/${PROVS.size}"
}
