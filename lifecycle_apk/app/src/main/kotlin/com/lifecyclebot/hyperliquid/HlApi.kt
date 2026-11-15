package com.lifecyclebot.hyperliquid

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.8795 — Hyperliquid's public REST API (POST /info for data, POST /exchange for signed actions), on the bot's shared
 * HTTP client. Every call returns null on any failure: the desk treats "no answer" as "do nothing".
 */
object HlApi {
    const val MAINNET = "https://api.hyperliquid.xyz"
    const val TESTNET = "https://api.hyperliquid-testnet.xyz"
    private val JSON_TYPE = "application/json".toMediaType()

    data class Asset(val index: Int, val name: String, val szDecimals: Int, val maxLeverage: Int, val markPx: Double,
                     val fundingHourly: Double, val dayNtlVlm: Double, val openInterest: Double)
    data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val closeTime: Long)
    data class Funding(val time: Long, val rate: Double)
    data class Position(val coin: String, val szi: Double, val entryPx: Double)
    data class Account(val accountValue: Double, val withdrawable: Double, val positions: List<Position>)

    private fun post(base: String, path: String, body: String): String? = try {
        val req = Request.Builder().url(base + path).post(body.toRequestBody(JSON_TYPE)).build()
        com.lifecyclebot.network.SharedHttpClient.base.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Throwable) { null }

    private fun info(base: String, req: JSONObject): String? = post(base, "/info", req.toString())

    private fun num(o: JSONObject, k: String): Double = o.optString(k, "").toDoubleOrNull() ?: Double.NaN

    /** Pure: the perp universe with each asset's live context ([metaAndAssetCtxs] response body). */
    fun parseMetaAndCtxs(body: String): List<Asset> {
        val arr = JSONArray(body)
        val universe = arr.getJSONObject(0).getJSONArray("universe")
        val ctxs = arr.getJSONArray(1)
        val out = ArrayList<Asset>(universe.length())
        for (i in 0 until universe.length()) {
            val u = universe.getJSONObject(i)
            if (u.optBoolean("isDelisted", false)) continue
            val c = ctxs.optJSONObject(i) ?: continue
            out.add(Asset(i, u.getString("name"), u.optInt("szDecimals", 0), u.optInt("maxLeverage", 1), num(c, "markPx"),
                num(c, "funding"), num(c, "dayNtlVlm"), num(c, "openInterest")))
        }
        return out
    }

    fun metaAndCtxs(base: String): List<Asset>? = info(base, JSONObject().put("type", "metaAndAssetCtxs"))
        ?.let { try { parseMetaAndCtxs(it) } catch (_: Throwable) { null } }

    /** Pure: candles from a [candleSnapshot] response body. */
    fun parseCandles(body: String): List<Candle> {
        val arr = JSONArray(body)
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out.add(Candle(c.getLong("t"), num(c, "o"), num(c, "h"), num(c, "l"), num(c, "c"), c.optLong("T", 0L)))
        }
        return out
    }

    fun candles(base: String, coin: String, interval: String, startMs: Long, endMs: Long): List<Candle>? =
        info(base, JSONObject().put("type", "candleSnapshot").put("req", JSONObject().put("coin", coin).put("interval", interval)
            .put("startTime", startMs).put("endTime", endMs)))?.let { try { parseCandles(it) } catch (_: Throwable) { null } }

    /** Pure: hourly funding rates from a [fundingHistory] response body. */
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

    /** Pure: the account from a [clearinghouseState] response body. */
    fun parseAccount(body: String): Account {
        val o = JSONObject(body)
        val ms = o.optJSONObject("marginSummary") ?: JSONObject()
        val ps = o.optJSONArray("assetPositions") ?: JSONArray()
        val positions = ArrayList<Position>(ps.length())
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i).optJSONObject("position") ?: continue
            positions.add(Position(p.getString("coin"), num(p, "szi"), num(p, "entryPx")))
        }
        return Account(num(ms, "accountValue"), o.optString("withdrawable", "").toDoubleOrNull() ?: Double.NaN, positions)
    }

    fun account(base: String, user: String): Account? =
        info(base, JSONObject().put("type", "clearinghouseState").put("user", user))?.let { try { parseAccount(it) } catch (_: Throwable) { null } }

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
