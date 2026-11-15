package com.lifecyclebot.hyperliquid

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.8795 / V5.0.8797 — Hyperliquid's public REST API (POST /info for data, POST /exchange for signed actions), on the
 * bot's shared HTTP client. Every network call returns null on any failure: the desk treats "no answer" as "do nothing".
 *
 * Perp dexes: index 0 is Hyperliquid's own (dex ""); every other index is a HIP-3 builder dex (stocks, indices, metals,
 * commodities) whose coins are named "dex:COIN" and whose asset id is 100000 + dexIndex * 10000 + index in its meta. Each
 * dex keeps its own clearinghouse (its own balance and positions) unless the owner unifies them on Hyperliquid, and HIP-3
 * markets margin isolated only.
 */
object HlApi {
    const val MAINNET = "https://api.hyperliquid.xyz"
    const val TESTNET = "https://api.hyperliquid-testnet.xyz"
    private val JSON_TYPE = "application/json".toMediaType()

    /** One market. [index] is the asset id orders use; [dex] is "" for Hyperliquid's own perps. */
    data class Asset(val index: Int, val dex: String, val name: String, val szDecimals: Int, val maxLeverage: Int,
                     val onlyIsolated: Boolean, val markPx: Double, val oraclePx: Double, val fundingHourly: Double,
                     val premium: Double, val dayNtlVlm: Double, val openInterest: Double, val prevDayPx: Double)
    data class Dex(val index: Int, val name: String, val collateralToken: Int, val assets: List<Asset>)
    data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double, val closeTime: Long)
    data class Funding(val time: Long, val rate: Double)
    data class Book(val bids: List<Pair<Double, Double>>, val asks: List<Pair<Double, Double>>)
    data class Position(val coin: String, val szi: Double, val entryPx: Double, val liquidationPx: Double = Double.NaN,
                        val marginUsed: Double = Double.NaN, val unrealizedPnl: Double = Double.NaN,
                        val isolated: Boolean = false, val leverage: Int = 0)
    data class Account(val accountValue: Double, val withdrawable: Double, val positions: List<Position>,
                       val totalNtlPos: Double = Double.NaN, val maintenanceUsed: Double = Double.NaN)

    /** One order's outcome from an /exchange "order" answer. */
    sealed class OrderResult {
        data class Filled(val totalSz: Double, val avgPx: Double, val oid: Long) : OrderResult()
        data class Resting(val oid: Long) : OrderResult()
        data class Rejected(val error: String) : OrderResult()
    }

    private fun post(base: String, path: String, body: String): String? = try {
        val req = Request.Builder().url(base + path).post(body.toRequestBody(JSON_TYPE)).build()
        com.lifecyclebot.network.SharedHttpClient.base.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Throwable) { null }

    private fun info(base: String, req: JSONObject): String? = post(base, "/info", req.toString())

    private fun num(o: JSONObject, k: String): Double = if (o.isNull(k)) Double.NaN else o.optString(k, "").toDoubleOrNull() ?: Double.NaN

    /** Pure: a HIP-3 coin always carries its dex prefix; Hyperliquid's own never does. */
    fun coinName(dex: String, raw: String): String = if (dex.isEmpty() || raw.startsWith("$dex:")) raw else "$dex:$raw"

    /** Pure: the asset id of position [i] in the meta of dex number [dexIndex]. */
    fun assetId(dexIndex: Int, i: Int): Int = if (dexIndex == 0) i else 100_000 + dexIndex * 10_000 + i

    /** Pure: the dex names by index from a [perpDexs] body (index 0, Hyperliquid's own, is ""). */
    fun parsePerpDexs(body: String): List<String> {
        val arr = JSONArray(body)
        return List(arr.length()) { i -> if (arr.isNull(i)) "" else arr.optJSONObject(i)?.optString("name", "").orEmpty() }
    }

    /** Pure: one dex's markets with their live context, from a [metaAndAssetCtxs] body; delisted markets are left out. */
    fun parseMetaAndCtxs(body: String, dex: String = "", dexIndex: Int = 0): Dex {
        val arr = JSONArray(body)
        val meta = arr.getJSONObject(0)
        val universe = meta.getJSONArray("universe")
        val ctxs = arr.getJSONArray(1)
        val out = ArrayList<Asset>(universe.length())
        for (i in 0 until universe.length()) {
            val u = universe.getJSONObject(i)
            if (u.optBoolean("isDelisted", false)) continue
            val c = ctxs.optJSONObject(i) ?: continue
            val isolated = u.optBoolean("onlyIsolated", false) || dexIndex != 0 ||
                u.optString("marginMode", "").let { it == "strictIsolated" || it == "noCross" }
            out.add(Asset(assetId(dexIndex, i), dex, coinName(dex, u.getString("name")), u.optInt("szDecimals", 0),
                u.optInt("maxLeverage", 1), isolated, num(c, "markPx"), num(c, "oraclePx"), num(c, "funding"), num(c, "premium"),
                num(c, "dayNtlVlm"), num(c, "openInterest"), num(c, "prevDayPx")))
        }
        return Dex(dexIndex, dex, meta.optInt("collateralToken", 0), out)
    }

    fun perpDexs(base: String): List<String>? = info(base, JSONObject().put("type", "perpDexs"))
        ?.let { try { parsePerpDexs(it) } catch (_: Throwable) { null } }

    fun metaAndCtxs(base: String, dex: String = "", dexIndex: Int = 0): Dex? =
        info(base, JSONObject().put("type", "metaAndAssetCtxs").put("dex", dex))
            ?.let { try { parseMetaAndCtxs(it, dex, dexIndex) } catch (_: Throwable) { null } }

    /** Pure: coins at their open-interest cap (no new exposure accepted) from a [perpsAtOpenInterestCap] body. */
    private fun parseNames(body: String): Set<String> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun atOpenInterestCap(base: String, dex: String): Set<String>? =
        info(base, JSONObject().put("type", "perpsAtOpenInterestCap").put("dex", dex))
            ?.let { b -> try { parseNames(b).map { coinName(dex, it) }.toSet() } catch (_: Throwable) { null } }

    /** Pure: candles from a [candleSnapshot] body. */
    fun parseCandles(body: String): List<Candle> {
        val arr = JSONArray(body)
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out.add(Candle(c.getLong("t"), num(c, "o"), num(c, "h"), num(c, "l"), num(c, "c"), num(c, "v"), c.optLong("T", 0L)))
        }
        return out
    }

    fun candles(base: String, coin: String, interval: String, startMs: Long, endMs: Long): List<Candle>? =
        info(base, JSONObject().put("type", "candleSnapshot").put("req", JSONObject().put("coin", coin).put("interval", interval)
            .put("startTime", startMs).put("endTime", endMs)))?.let { try { parseCandles(it) } catch (_: Throwable) { null } }

    /** Pure: hourly funding rates from a [fundingHistory] body. */
    fun parseFunding(body: String): List<Funding> {
        val arr = JSONArray(body)
        val out = ArrayList<Funding>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            out.add(Funding(f.getLong("time"), num(f, "fundingRate")))
        }
        return out
    }

    fun fundingHistory(base: String, coin: String, startMs: Long, endMs: Long): List<Funding>? =
        info(base, JSONObject().put("type", "fundingHistory").put("coin", coin).put("startTime", startMs).put("endTime", endMs))
            ?.let { try { parseFunding(it) } catch (_: Throwable) { null } }

    /** Pure: the order book (best first) from an [l2Book] body. */
    fun parseBook(body: String): Book {
        val levels = JSONObject(body).getJSONArray("levels")
        fun side(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { num(it, "px") to num(it, "sz") } }
            .filter { it.first > 0.0 && it.second > 0.0 }
        return Book(side(levels.getJSONArray(0)), side(levels.getJSONArray(1)))
    }

    fun book(base: String, coin: String): Book? = info(base, JSONObject().put("type", "l2Book").put("coin", coin))
        ?.let { try { parseBook(it) } catch (_: Throwable) { null } }

    /** Pure: one dex's account from a [clearinghouseState] body. */
    fun parseAccount(body: String): Account {
        val o = JSONObject(body)
        val ms = o.optJSONObject("marginSummary") ?: JSONObject()
        val ps = o.optJSONArray("assetPositions") ?: JSONArray()
        val positions = ArrayList<Position>(ps.length())
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i).optJSONObject("position") ?: continue
            val lev = p.optJSONObject("leverage")
            positions.add(Position(p.getString("coin"), num(p, "szi"), num(p, "entryPx"), num(p, "liquidationPx"), num(p, "marginUsed"),
                num(p, "unrealizedPnl"), lev?.optString("type") == "isolated", lev?.optInt("value", 0) ?: 0))
        }
        return Account(num(ms, "accountValue"), num(o, "withdrawable"), positions, num(ms, "totalNtlPos"), num(o, "crossMaintenanceMarginUsed"))
    }

    fun account(base: String, user: String, dex: String = ""): Account? =
        info(base, JSONObject().put("type", "clearinghouseState").put("user", user).put("dex", dex))
            ?.let { try { parseAccount(it) } catch (_: Throwable) { null } }

    /** Pure: the outcome of each order in an /exchange order answer; a refused request is one Rejected per order sent. */
    fun parseOrderResponse(body: String?, orders: Int = 1): List<OrderResult> {
        if (body == null) return List(orders) { OrderResult.Rejected("no answer") }
        return try {
            val o = JSONObject(body)
            if (o.optString("status") != "ok") return List(orders) { OrderResult.Rejected(o.opt("response")?.toString()?.take(200) ?: "refused") }
            val st = o.getJSONObject("response").getJSONObject("data").getJSONArray("statuses")
            (0 until st.length()).map { i ->
                val s: JSONObject? = st.optJSONObject(i)
                val filled = s?.optJSONObject("filled")
                val resting = s?.optJSONObject("resting")
                when {
                    s == null -> OrderResult.Rejected(st.optString(i, "unknown"))
                    filled != null -> OrderResult.Filled(num(filled, "totalSz"), num(filled, "avgPx"), filled.optLong("oid"))
                    resting != null -> OrderResult.Resting(resting.optLong("oid"))
                    else -> OrderResult.Rejected(s?.optString("error", s.toString())?.take(200) ?: "unknown")
                }
            }
        } catch (_: Throwable) { List(orders) { OrderResult.Rejected(body.take(200)) } }
    }

    /** Pure: an /exchange answer of type "default" (updateLeverage and the like) accepted. */
    fun parseOk(body: String?): Boolean = try { body != null && JSONObject(body).optString("status") == "ok" } catch (_: Throwable) { false }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k.toString(), toJson(x)) }
        is List<*> -> JSONArray().also { a -> for (x in v) a.put(toJson(x)) }
        else -> v ?: JSONObject.NULL
    }

    /** Pure: the /exchange request body for a signed action. */
    fun exchangeBody(action: Map<String, Any?>, nonce: Long, sig: HlSigner.Sig): String = JSONObject()
        .put("action", toJson(action)).put("nonce", nonce)
        .put("signature", JSONObject().put("r", sig.r).put("s", sig.s).put("v", sig.v))
        .put("vaultAddress", JSONObject.NULL).toString()

    fun exchange(base: String, action: Map<String, Any?>, nonce: Long, sig: HlSigner.Sig): String? =
        post(base, "/exchange", exchangeBody(action, nonce, sig))
}
