package com.lifecyclebot.xstocks

import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.0.8802 — what the xStocks desk reads, each from the instrument it actually trades (GPT's audit of 8800: the old stock
 * trader priced a tokenized share by the UNDERLYING's Pyth price, assumed $10M of liquidity and slept outside US hours,
 * while an xStock is a Solana token with its own secondary price, depth and 24/7 market):
 *  - [pools] / [dailyBars]: the token's deepest pool and its daily candles in USD (GeckoTerminal) — the desk's history;
 *  - [roundTrip]: an executable Jupiter quote SOL -> token at the desk's size and token -> SOL for what came back — the
 *    price paid, the price received, and the round-trip cost (spread, impact, route fees) actually measured;
 *  - [mintInfo]: the Token-2022 mint's decimals and Scaled UI multiplier (xStocks fold splits and dividends into it):
 *    shares per raw unit = multiplier / 10^decimals. PnL is measured in SOL in and SOL out, which the multiplier cannot
 *    change; the multiplier matters for the share price, never for the book.
 */
object XsApi {
    private const val GECKO = "https://api.geckoterminal.com/api/v2/networks/solana"
    const val SOL_MINT = "So11111111111111111111111111111111111111112"

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    // V5.0.8805 — 8802 on the device: every xStock NO_HISTORY and every cost "unquoted" with no reason shown. The reads now
    // go through the app's host-health client like DynamicAltTokenRegistry's GeckoTerminal reads, and each outcome is counted.
    private val geckoOk = java.util.concurrent.atomic.AtomicLong(); private val geckoFail = java.util.concurrent.atomic.AtomicLong()
    private val quoteOk = java.util.concurrent.atomic.AtomicLong(); private val quoteFail = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastGecko = "-"
    @Volatile private var lastQuote = "-"

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json;version=20230302").build()
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, "geckoterminal").use { r ->
            val body = if (r.isSuccessful) r.body?.string() else null
            if (body != null) geckoOk.incrementAndGet() else { geckoFail.incrementAndGet(); lastGecko = "http ${r.code}" }
            body
        }
    } catch (t: Throwable) { geckoFail.incrementAndGet(); lastGecko = "${t.javaClass.simpleName}:${t.message?.take(60)}"; null }

    fun line(): String = "api{gecko ok=${geckoOk.get()} fail=${geckoFail.get()} last=$lastGecko quotes ok=${quoteOk.get()} fail=${quoteFail.get()} last=$lastQuote}"

    data class Pool(val address: String, val reserveUsd: Double, val volume24hUsd: Double)

    /** Pure: GeckoTerminal's pools of a token, deepest first. */
    fun parsePools(body: String?): List<Pool> {
        val arr = try { JSONObject(body ?: return emptyList()).optJSONArray("data") } catch (_: Throwable) { null } ?: return emptyList()
        val out = ArrayList<Pool>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val addr = a.optString("address")
            val res = a.optString("reserve_in_usd").toDoubleOrNull() ?: continue
            val vol = a.optJSONObject("volume_usd")?.optString("h24")?.toDoubleOrNull() ?: 0.0
            if (addr.isNotBlank() && res > 0.0) out.add(Pool(addr, res, vol))
        }
        return out.sortedByDescending { it.reserveUsd }
    }

    fun pools(mint: String): List<Pool> = parsePools(get("$GECKO/tokens/$mint/pools?page=1"))

    /** Pure: daily candles [day -> o, h, l, c, volume USD] of GeckoTerminal's ohlcv_list ([ts s, o, h, l, c, v], newest first). */
    fun parseDailyBars(body: String?): Map<Long, DoubleArray> {
        val list = try { JSONObject(body ?: return emptyMap()).optJSONObject("data")?.optJSONObject("attributes")?.optJSONArray("ohlcv_list") }
            catch (_: Throwable) { null } ?: return emptyMap()
        val out = HashMap<Long, DoubleArray>()
        for (i in 0 until list.length()) {
            val r = list.optJSONArray(i) ?: continue
            if (r.length() < 6) continue
            val ts = r.optLong(0, 0L)
            val b = DoubleArray(5) { r.optDouble(it + 1, Double.NaN) }
            if (ts > 0L && b[3] > 0.0) out[Math.floorDiv(ts * 1000L, 86_400_000L)] = b
        }
        return out
    }

    /** The token's daily candles (USD, priced in the token itself on its deepest pool) for up to [limit] days. */
    fun dailyBars(pool: String, mint: String, limit: Int = 1000): Map<Long, DoubleArray> =
        parseDailyBars(get("$GECKO/pools/$pool/ohlcv/day?aggregate=1&limit=${limit.coerceIn(1, 1000)}&currency=usd&token=$mint"))

    data class MintInfo(val decimals: Int, val multiplier: Double)

    /** Pure: decimals and the effective Scaled UI multiplier of a jsonParsed mint account (1.0 without the extension). */
    fun parseMint(value: JSONObject?, nowMs: Long): MintInfo? {
        val info = value?.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: return null
        val decimals = info.optInt("decimals", -1).takeIf { it in 0..18 } ?: return null
        var m = 1.0
        val ext = info.optJSONArray("extensions") ?: JSONArray()
        for (i in 0 until ext.length()) {
            val e = ext.optJSONObject(i) ?: continue
            if (e.optString("extension") != "scaledUiAmountConfig") continue
            val st = e.optJSONObject("state") ?: continue
            val cur = st.optString("multiplier").toDoubleOrNull() ?: st.optDouble("multiplier", Double.NaN)
            val next = st.optString("newMultiplier").toDoubleOrNull() ?: st.optDouble("newMultiplier", Double.NaN)
            val at = st.optLong("newMultiplierEffectiveTimestamp", 0L) * 1000L
            m = if (next.isFinite() && next > 0.0 && at in 1L..nowMs) next else cur
        }
        return if (m.isFinite() && m > 0.0) MintInfo(decimals, m) else null
    }

    fun mintInfo(mint: String): MintInfo? {
        val params = JSONArray().put(mint).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed"))
        val r = try { com.lifecyclebot.network.KeylessRpc8359.post8359(http, "getAccountInfo", params, "8802mint", maxRungs = 3) } catch (_: Throwable) { null }
        return parseMint(r?.optJSONObject("value"), System.currentTimeMillis())
    }

    /** A measured round trip: SOL in, tokens out, SOL back for those tokens. */
    data class RoundTrip(val inLamports: Long, val outRaw: Long, val backLamports: Long, val atMs: Long) {
        /** Pure: USD paid per raw-decimal unit (the ask), at [solUsd]. */
        fun askUsd(decimals: Int, solUsd: Double): Double = if (outRaw > 0L) inLamports / 1e9 * solUsd / (outRaw / Math.pow(10.0, decimals.toDouble())) else Double.NaN
        /** Pure: USD received per unit (the bid). */
        fun bidUsd(decimals: Int, solUsd: Double): Double = if (outRaw > 0L) backLamports / 1e9 * solUsd / (outRaw / Math.pow(10.0, decimals.toDouble())) else Double.NaN
        /** Pure: the fraction a buy-then-sell loses (spread + impact + route fees), per side. */
        val costSide: Double get() = if (inLamports > 0L && backLamports > 0L) maxOf(0.0, 1.0 - backLamports.toDouble() / inLamports) / 2.0 else Double.NaN
    }

    private val jupiter by lazy { JupiterApi("", observationOnly7397 = true) }

    /** Quotes SOL -> [mint] for [lamports] and back; null when either leg has no route. */
    fun roundTrip(mint: String, lamports: Long): RoundTrip? = try {
        val buy = jupiter.getQuote(SOL_MINT, mint, lamports, 100)
        if (buy.outAmount <= 0L) { quoteFail.incrementAndGet(); lastQuote = "buy out=0"; null }
        else {
            val sell = jupiter.getQuote(mint, SOL_MINT, buy.outAmount, 100)
            if (sell.outAmount <= 0L) { quoteFail.incrementAndGet(); lastQuote = "sell out=0"; null }
            else { quoteOk.incrementAndGet(); RoundTrip(lamports, buy.outAmount, sell.outAmount, System.currentTimeMillis()) }
        }
    } catch (t: Throwable) { quoteFail.incrementAndGet(); lastQuote = "${t.javaClass.simpleName}:${t.message?.take(80)}"; null }
}
