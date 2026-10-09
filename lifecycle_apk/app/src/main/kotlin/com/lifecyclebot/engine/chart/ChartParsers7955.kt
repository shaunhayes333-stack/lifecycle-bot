package com.lifecyclebot.engine.chart

import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7955 — pure parsers for every free market-data source the chart library
 * reads (ChartSources7955). Candle parsers return bars oldest first with the
 * malformed rows dropped; listing parsers return the identifiers to fetch next.
 * Exchange candles that publish a taker-buy split fill [Bar7950.buyV].
 */
object ChartParsers7955 {

    /** A tradable listing: provider id ("BTC-USDT"), normalised base ("BTC"), ranking volume. */
    data class Listing7955(val id: String, val base: String, val vol: Double)

    /** A DEX pool on a GeckoTerminal network ("solana", "bsc", "base", "eth"). */
    data class Pool7955(val network: String, val address: String)

    // ── shared ──

    private fun num(x: Any?): Double = when (x) {
        is Number -> x.toDouble()
        is String -> x.trim().toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    private fun arrNum(a: JSONArray, i: Int): Double = if (i < a.length()) num(a.opt(i)) else Double.NaN

    private fun objNum(o: JSONObject, k: String): Double = if (o.has(k)) num(o.opt(k)) else Double.NaN

    /** Seconds or milliseconds -> milliseconds. */
    private fun ms(t: Double): Long = if (!t.isFinite() || t <= 0.0) 0L else if (t < 1e11) (t * 1000.0).toLong() else t.toLong()

    private fun isoMs(s: String): Long = try { java.time.Instant.parse(s.trim()).toEpochMilli() } catch (_: Throwable) {
        try { java.time.OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli() } catch (_: Throwable) { 0L }
    }

    /** A sane candle or null: positive finite prices, high/low enclosing open/close. */
    fun bar7955(t: Long, o: Double, h: Double, l: Double, c: Double, v: Double, buyV: Double = Double.NaN): Bar7950? {
        if (t <= 0L) return null
        if (!(o > 0.0 && h > 0.0 && l > 0.0 && c > 0.0) || !o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) return null
        val eps = 1e-9 * h
        if (h + eps < maxOf(o, c) || l - eps > minOf(o, c)) return null
        val vol = if (v.isFinite() && v >= 0.0) v else 0.0
        val bv = if (buyV.isFinite() && buyV >= 0.0 && vol > 0.0) buyV.coerceAtMost(vol) else Double.NaN
        return Bar7950(t, o, h, l, c, vol, bv)
    }

    private fun sorted(out: List<Bar7950>): List<Bar7950> = out.sortedBy { it.t }.distinctBy { it.t }

    /** Normalised base symbol of an exchange pair ("BTC-USDT", "BTC_USDT", "XXBTZUSD", "BTCUSDT" -> "BTC"). */
    fun baseOf7955(pair: String): String {
        var s = pair.uppercase().replace("-", "").replace("_", "").replace("/", "")
        var quote = ""
        for (q in listOf("USDT", "USDC", "ZUSD", "USD")) if (s.endsWith(q) && s.length > q.length) { s = s.removeSuffix(q); quote = q; break }
        // Kraken's legacy names: XXBTZUSD, XETHZUSD.
        if (quote == "ZUSD" && s.length == 4 && s.startsWith("X")) s = s.substring(1)
        return when (s) { "XBT" -> "BTC"; "XDG" -> "DOGE"; else -> s }
    }

    // ── exchange candles ──

    /** OKX /api/v5/market/history-candles: data[[ts,o,h,l,c,vol,...]] newest first. */
    fun okx7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val k = d.optJSONArray(i) ?: continue
            bar7955(ms(arrNum(k, 0)), arrNum(k, 1), arrNum(k, 2), arrNum(k, 3), arrNum(k, 4), arrNum(k, 5))?.let { out += it }
        }
        return sorted(out)
    }

    /** Bybit v5 /market/kline: result.list[[start,o,h,l,c,vol,turnover]] newest first. */
    fun bybit7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val k = d.optJSONArray(i) ?: continue
            bar7955(ms(arrNum(k, 0)), arrNum(k, 1), arrNum(k, 2), arrNum(k, 3), arrNum(k, 4), arrNum(k, 5))?.let { out += it }
        }
        return sorted(out)
    }

    /** Kraken /0/public/OHLC: result{PAIR:[[time,o,h,l,c,vwap,vol,count]], last}. */
    fun kraken7955(root: JSONObject): List<Bar7950> {
        val r = root.optJSONObject("result") ?: return emptyList()
        val name = r.keys().asSequence().firstOrNull { it != "last" } ?: return emptyList()
        val d = r.optJSONArray(name) ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val k = d.optJSONArray(i) ?: continue
            bar7955(ms(arrNum(k, 0)), arrNum(k, 1), arrNum(k, 2), arrNum(k, 3), arrNum(k, 4), arrNum(k, 6))?.let { out += it }
        }
        return sorted(out)
    }

    /** Coinbase Exchange /products/{id}/candles: [[time, low, high, open, close, volume]] newest first. */
    fun coinbase7955(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            bar7955(ms(arrNum(k, 0)), arrNum(k, 3), arrNum(k, 2), arrNum(k, 1), arrNum(k, 4), arrNum(k, 5))?.let { out += it }
        }
        return sorted(out)
    }

    /** KuCoin /api/v1/market/candles: data[[time, open, close, high, low, volume, turnover]] newest first. */
    fun kucoin7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val k = d.optJSONArray(i) ?: continue
            bar7955(ms(arrNum(k, 0)), arrNum(k, 1), arrNum(k, 3), arrNum(k, 4), arrNum(k, 2), arrNum(k, 5))?.let { out += it }
        }
        return sorted(out)
    }

    /** Gate.io /api/v4/spot/candlesticks: [[t, quoteVol, close, high, low, open, baseVol, closed]] oldest first. */
    fun gate7955(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            val v = arrNum(k, 6).takeIf { it.isFinite() } ?: arrNum(k, 1)
            bar7955(ms(arrNum(k, 0)), arrNum(k, 5), arrNum(k, 3), arrNum(k, 4), arrNum(k, 2), v)?.let { out += it }
        }
        return sorted(out)
    }

    /** CoinPaprika /coins/{id}/ohlcv/historical: [{time_open, open, high, low, close, volume}]. */
    fun paprika7955(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            bar7955(isoMs(o.optString("time_open")), objNum(o, "open"), objNum(o, "high"), objNum(o, "low"), objNum(o, "close"), objNum(o, "volume"))?.let { out += it }
        }
        return sorted(out)
    }

    /** CoinGecko /coins/{id}/ohlc: [[t_ms, o, h, l, c]] (no volume). */
    fun coingecko7955(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            bar7955(ms(arrNum(k, 0)), arrNum(k, 1), arrNum(k, 2), arrNum(k, 3), arrNum(k, 4), 0.0)?.let { out += it }
        }
        return sorted(out)
    }

    /** CryptoCompare histominute/histohour: Data.Data[{time, high, low, open, close, volumefrom}] (zero rows are padding). */
    fun cryptoCompare7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONObject("Data")?.optJSONArray("Data") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            bar7955(ms(objNum(o, "time")), objNum(o, "open"), objNum(o, "high"), objNum(o, "low"), objNum(o, "close"), objNum(o, "volumefrom"))?.let { out += it }
        }
        return sorted(out)
    }

    // ── DEX / meme candles ──

    /** pump.fun frontend /candlesticks/{mint}: [{timestamp, open, high, low, close, volume}]. */
    fun pumpFun7955(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            bar7955(ms(objNum(o, "timestamp")), objNum(o, "open"), objNum(o, "high"), objNum(o, "low"), objNum(o, "close"), objNum(o, "volume"))?.let { out += it }
        }
        return sorted(out)
    }

    /** GMGN token_kline: {data:{list:[{time, open, close, high, low, volume}]}} (or data as the list). */
    fun gmgn7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONObject("data")?.optJSONArray("list") ?: root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            bar7955(ms(objNum(o, "time")), objNum(o, "open"), objNum(o, "high"), objNum(o, "low"), objNum(o, "close"), objNum(o, "volume"))?.let { out += it }
        }
        return sorted(out)
    }

    /** Moralis Solana pair OHLCV: {result:[{timestamp(ISO), open, high, low, close, volume}]}. */
    fun moralis7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            val t = o.opt("timestamp").let { if (it is Number) ms(it.toDouble()) else isoMs(it?.toString().orEmpty()) }
            bar7955(t, objNum(o, "open"), objNum(o, "high"), objNum(o, "low"), objNum(o, "close"), objNum(o, "volume"))?.let { out += it }
        }
        return sorted(out)
    }

    /**
     * Bitquery Solana DEXTradeByTokens 1-minute aggregation:
     * data.Solana.DEXTradeByTokens[{Block{Timefield}, Trade{open,high,low,close}, volume, buyVolume}].
     * Volumes are the SOL side, so buyVolume is the buy split.
     */
    fun bitquery7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONObject("data")?.optJSONObject("Solana")?.optJSONArray("DEXTradeByTokens") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            val t = isoMs(o.optJSONObject("Block")?.optString("Timefield").orEmpty())
            val tr = o.optJSONObject("Trade") ?: continue
            bar7955(t, objNum(tr, "open"), objNum(tr, "high"), objNum(tr, "low"), objNum(tr, "close"), objNum(o, "volume"), objNum(o, "buyVolume"))?.let { out += it }
        }
        return sorted(out)
    }

    /** SolanaTracker data API /chart/{token}: {oclhv:[{open, close, low, high, volume, time}]}. */
    fun solanaTracker7955(root: JSONObject): List<Bar7950> {
        val d = root.optJSONArray("oclhv") ?: return emptyList()
        val out = ArrayList<Bar7950>(d.length())
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            bar7955(ms(objNum(o, "time")), objNum(o, "open"), objNum(o, "high"), objNum(o, "low"), objNum(o, "close"), objNum(o, "volume"))?.let { out += it }
        }
        return sorted(out)
    }

    /** Codex getBars: data.getBars{t[], o[], h[], l[], c[], volume[], buyVolume[]} (columns). */
    fun codex7955(root: JSONObject): List<Bar7950> {
        val g = root.optJSONObject("data")?.optJSONObject("getBars") ?: return emptyList()
        val t = g.optJSONArray("t") ?: return emptyList()
        val o = g.optJSONArray("o") ?: return emptyList()
        val h = g.optJSONArray("h") ?: return emptyList()
        val l = g.optJSONArray("l") ?: return emptyList()
        val c = g.optJSONArray("c") ?: return emptyList()
        val v = g.optJSONArray("volume") ?: g.optJSONArray("v")
        val bv = g.optJSONArray("buyVolume")
        val out = ArrayList<Bar7950>(t.length())
        for (i in 0 until t.length()) {
            bar7955(ms(arrNum(t, i)), arrNum(o, i), arrNum(h, i), arrNum(l, i), arrNum(c, i),
                v?.let { arrNum(it, i) } ?: 0.0, bv?.let { arrNum(it, i) } ?: Double.NaN)?.let { out += it }
        }
        return sorted(out)
    }

    // ── listings ──

    /** Objects ranked by a volume field: Binance / MEXC (symbol, quoteVolume), Gate (currency_pair, quote_volume). */
    fun rankObjects7955(arr: JSONArray, nameKey: String, volKey: String, quote: String): List<Listing7955> {
        val out = ArrayList<Listing7955>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString(nameKey)
            if (!name.uppercase().replace("-", "").replace("_", "").endsWith(quote)) continue
            val vol = objNum(o, volKey)
            out += Listing7955(name, baseOf7955(name), if (vol.isFinite()) vol else 0.0)
        }
        return out.sortedByDescending { it.vol }
    }

    /** OKX /market/tickers?instType=SPOT: data[{instId, volCcy24h}] (USDT quotes). */
    fun okxTickers7955(root: JSONObject): List<Listing7955> = rankObjects7955(root.optJSONArray("data") ?: JSONArray(), "instId", "volCcy24h", "USDT")

    /** Bybit /market/tickers?category=spot: result.list[{symbol, turnover24h}]. */
    fun bybitTickers7955(root: JSONObject): List<Listing7955> =
        rankObjects7955(root.optJSONObject("result")?.optJSONArray("list") ?: JSONArray(), "symbol", "turnover24h", "USDT")

    /** KuCoin /market/allTickers: data.ticker[{symbol, volValue}]. */
    fun kucoinTickers7955(root: JSONObject): List<Listing7955> =
        rankObjects7955(root.optJSONObject("data")?.optJSONArray("ticker") ?: JSONArray(), "symbol", "volValue", "USDT")

    /** Kraken /0/public/Ticker: result{PAIR:{c:[last], v:[today, 24h]}} -> USD pairs by quote volume. */
    fun krakenTickers7955(root: JSONObject): List<Listing7955> {
        val r = root.optJSONObject("result") ?: return emptyList()
        val out = ArrayList<Listing7955>()
        for (pair in r.keys()) {
            if (!pair.endsWith("USD")) continue
            val o = r.optJSONObject(pair) ?: continue
            val last = o.optJSONArray("c")?.let { arrNum(it, 0) } ?: Double.NaN
            val v = o.optJSONArray("v")?.let { arrNum(it, 1) } ?: Double.NaN
            val qv = last * v
            out += Listing7955(pair, baseOf7955(pair), if (qv.isFinite()) qv else 0.0)
        }
        return out.sortedByDescending { it.vol }
    }

    /** Coinbase Exchange /products: [{id, quote_currency, status, trading_disabled}] -> online USD products. */
    fun coinbaseProducts7955(arr: JSONArray): List<Listing7955> {
        val out = ArrayList<Listing7955>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("quote_currency") != "USD" || o.optBoolean("trading_disabled", false)) continue
            if (o.optString("status", "online") != "online") continue
            val id = o.optString("id")
            if (id.isNotBlank()) out += Listing7955(id, o.optString("base_currency").uppercase().ifBlank { baseOf7955(id) }, 0.0)
        }
        return out
    }

    /** CoinPaprika /tickers: [{id, symbol, rank}] by rank. */
    fun paprikaTickers7955(arr: JSONArray): List<Listing7955> {
        val out = ArrayList<Listing7955>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val rank = o.optInt("rank", 0)
            if (id.isBlank() || rank <= 0) continue
            out += Listing7955(id, o.optString("symbol").uppercase(), -rank.toDouble())
        }
        return out.sortedByDescending { it.vol }
    }

    /** CoinGecko /coins/markets: [{id, symbol, total_volume}] in the requested order. */
    fun coingeckoMarkets7955(arr: JSONArray): List<Listing7955> {
        val out = ArrayList<Listing7955>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out += Listing7955(id, o.optString("symbol").uppercase(), objNum(o, "total_volume").takeIf { it.isFinite() } ?: 0.0)
        }
        return out
    }

    /** CoinMarketCap /v1/cryptocurrency/listings/latest: data[{symbol, quote.USD.volume_24h}]. */
    fun cmcListings7955(root: JSONObject): List<Listing7955> {
        val d = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Listing7955>()
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            val sym = o.optString("symbol").uppercase()
            val vol = o.optJSONObject("quote")?.optJSONObject("USD")?.let { objNum(it, "volume_24h") } ?: Double.NaN
            if (sym.isNotBlank()) out += Listing7955(sym, sym, if (vol.isFinite()) vol else 0.0)
        }
        return out
    }

    /** CryptoCompare /data/top/totalvolfull: Data[{CoinInfo{Name}}]. */
    fun cryptoCompareTop7955(root: JSONObject): List<Listing7955> {
        val d = root.optJSONArray("Data") ?: return emptyList()
        val out = ArrayList<Listing7955>()
        for (i in 0 until d.length()) {
            val name = d.optJSONObject(i)?.optJSONObject("CoinInfo")?.optString("Name").orEmpty().uppercase()
            if (name.isNotBlank()) out += Listing7955(name, name, 0.0)
        }
        return out
    }

    /** GeckoTerminal pool lists: data[{attributes{address}}]. */
    fun geckoPools7955(root: JSONObject): List<String> {
        val d = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) {
            val a = d.optJSONObject(i)?.optJSONObject("attributes")?.optString("address").orEmpty()
            if (a.isNotBlank()) out += a
        }
        return out
    }

    private val DEX_NETWORKS = mapOf("solana" to "solana", "bsc" to "bsc", "base" to "base", "ethereum" to "eth")

    /** GeckoTerminal network for a DexScreener chain id, or null when not charted. */
    private fun geckoNetwork7955(chainId: String): String? = DEX_NETWORKS[chainId.lowercase()]

    /** DexScreener token-boosts / token-profiles: [{chainId, tokenAddress}] (charted chains only). */
    fun dexTokens7955(arr: JSONArray): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val chain = o.optString("chainId").lowercase()
            val addr = o.optString("tokenAddress")
            if (geckoNetwork7955(chain) != null && addr.isNotBlank()) out += chain to addr
        }
        return out.toList()
    }

    /** DexScreener pairs (tokens/v1 array or search {pairs}): the deepest pair per base token. */
    fun dexPairs7955(body: Any): List<Pool7955> {
        val arr = when (body) { is JSONArray -> body; is JSONObject -> body.optJSONArray("pairs") ?: JSONArray(); else -> JSONArray() }
        val best = LinkedHashMap<String, Pair<Pool7955, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val net = geckoNetwork7955(o.optString("chainId")) ?: continue
            val pair = o.optString("pairAddress")
            if (pair.isBlank()) continue
            val base = o.optJSONObject("baseToken")?.optString("address").orEmpty().ifBlank { pair }
            val liq = o.optJSONObject("liquidity")?.let { objNum(it, "usd") }?.takeIf { it.isFinite() } ?: 0.0
            val cur = best[base]
            if (cur == null || liq > cur.second) best[base] = Pool7955(net, pair) to liq
        }
        return best.values.map { it.first }
    }

    /** Raydium v3 /pools/info/list: data.data[{id}]. */
    fun raydiumPools7955(root: JSONObject): List<String> {
        val d = root.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) d.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    /** Meteora DLMM /pair/all_with_pagination: {pairs:[{address}]} (or data[]). */
    fun meteoraPools7955(root: JSONObject): List<String> {
        val d = root.optJSONArray("pairs") ?: root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) d.optJSONObject(i)?.optString("address")?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    /** pump.fun coins (array, or a single king-of-the-hill object): mint -> PumpSwap/Raydium pool ("" while on the curve). */
    fun pumpCoins7955(body: Any): List<Pair<String, String>> {
        val arr = when (body) {
            is JSONArray -> body
            is JSONObject -> body.optJSONArray("coins") ?: JSONArray().put(body)
            else -> JSONArray()
        }
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mint = o.optString("mint")
            if (mint.isBlank()) continue
            val pool = listOf("pump_swap_pool", "raydium_pool").map { if (o.isNull(it)) "" else o.optString(it) }.firstOrNull { it.length >= 32 }.orEmpty()
            out += mint to pool
        }
        return out
    }

    /** GMGN rank: {data:{rank:[{address}]}}. */
    fun gmgnRank7955(root: JSONObject): List<String> {
        val d = root.optJSONObject("data")?.optJSONArray("rank") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) d.optJSONObject(i)?.optString("address")?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    /** CoinMarketCap DEX v4 spot pairs: data[{contract_address | pair_address, network_slug}]. */
    fun cmcDexPairs7955(root: JSONObject): List<String> {
        val d = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            val a = o.optString("contract_address").ifBlank { o.optString("pair_address") }
            if (a.isNotBlank()) out += a
        }
        return out
    }

    /** Moralis pump.fun new / graduated: {result:[{tokenAddress}]}. */
    fun moralisTokens7955(root: JSONObject): List<String> {
        val d = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) d.optJSONObject(i)?.optString("tokenAddress")?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    /** Moralis /token/mainnet/{mint}/pairs: the deepest pairAddress, or null. */
    fun moralisBestPair7955(root: JSONObject): String? {
        val d = root.optJSONArray("pairs") ?: root.optJSONArray("result") ?: return null
        var best: String? = null
        var bestLiq = -1.0
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            val a = o.optString("pairAddress")
            if (a.isBlank()) continue
            val liq = objNum(o, "liquidityUsd").takeIf { it.isFinite() } ?: 0.0
            if (liq > bestLiq) { bestLiq = liq; best = a }
        }
        return best
    }

    /** SolanaTracker /tokens/trending: [{token{mint}}]. */
    fun solanaTrackerTrending7955(arr: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject("token")?.optString("mint")?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    /** True when a response is a Cloudflare challenge / bot wall rather than data. */
    fun isChallenge7955(code: Int, body: String?): Boolean {
        if (code == 403 || code == 503) {
            val b = body?.take(4_000)?.lowercase().orEmpty()
            if (b.contains("cf-chl") || b.contains("just a moment") || b.contains("challenge-platform") || b.contains("cf-browser-verification") || b.contains("attention required")) return true
        }
        if (code in 200..299) {
            val b = body?.trimStart()?.take(400)?.lowercase().orEmpty()
            return b.startsWith("<!doctype html") || b.startsWith("<html")
        }
        return false
    }
}
