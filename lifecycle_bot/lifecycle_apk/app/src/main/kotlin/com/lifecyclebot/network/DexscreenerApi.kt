package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.RateLimiter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PairInfo(
    val pairAddress: String,
    val baseSymbol: String,
    val baseName: String,
    val url: String,
    val candle: Candle,
    val pairCreatedAtMs: Long = 0L,   // epoch ms when pair was created
    val liquidity: Double = 0.0,       // USD liquidity
    val fdv: Double = 0.0,             // fully diluted valuation
)

class DexscreenerApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Returns the best-scoring pair for this mint on Solana, or null. */
    fun getBestPair(mint: String): PairInfo? {
        if (!RateLimiter.allowRequest("dexscreener")) return null
        val url = "https://api.dexscreener.com/token-pairs/v1/solana/$mint"
        val body = get(url) ?: return null
        val pairs = JSONArray(body)
        if (pairs.length() == 0) return null

        // Score each pair by liquidity + volume + tx count
        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val score = scorePair(p)
            if (score > bestScore) { bestScore = score; best = p }
        }
        return best?.let { parsePair(it) }
    }

    /** Search by query string — used for token discovery */
    fun search(query: String): List<PairInfo> {
        if (!RateLimiter.allowRequest("dexscreener")) return emptyList()
        val url = "https://api.dexscreener.com/latest/dex/search?q=${encode(query)}"
        val body = get(url) ?: return emptyList()
        val arr  = JSONObject(body).optJSONArray("pairs") ?: return emptyList()
        return (0 until minOf(arr.length(), 20)).mapNotNull { parsePair(arr.getJSONObject(it)) }
            .filter { it.candle.priceUsd > 0 }
    }

    // ── internals ──────────────────────────────────────────

    private fun scorePair(p: JSONObject): Double {
        val liq  = p.optJSONObject("liquidity")?.optDouble("usd",  0.0) ?: 0.0
        val vol  = p.optJSONObject("volume")?.optDouble("h24",     0.0) ?: 0.0
        val txns = p.optJSONObject("txns")?.optJSONObject("h24")
        val cnt  = (txns?.optInt("buys", 0) ?: 0) + (txns?.optInt("sells", 0) ?: 0)
        return liq * 1.5 + vol + cnt * 10.0
    }

    private fun parsePair(p: JSONObject): PairInfo {
        val base    = p.optJSONObject("baseToken")
        val vol     = p.optJSONObject("volume")
        val txns    = p.optJSONObject("txns")
        val h1      = txns?.optJSONObject("h1")
        val h24     = txns?.optJSONObject("h24")

        val candle = Candle(
            ts          = System.currentTimeMillis(),
            priceUsd    = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0,
            marketCap   = p.optDouble("marketCap", 0.0).let {
                if (it == 0.0) p.optDouble("fdv", 0.0) else it
            },
            volumeH1    = vol?.optDouble("h1",  0.0) ?: 0.0,
            volume24h   = vol?.optDouble("h24", 0.0) ?: 0.0,
            buysH1      = h1?.optInt("buys",   0) ?: 0,
            sellsH1     = h1?.optInt("sells",  0) ?: 0,
            buys24h     = h24?.optInt("buys",  0) ?: 0,
            sells24h    = h24?.optInt("sells", 0) ?: 0,
        )

        return PairInfo(
            pairAddress     = p.optString("pairAddress", ""),
            baseSymbol      = base?.optString("symbol", "") ?: "",
            baseName        = base?.optString("name",   "") ?: "",
            url             = p.optString("url", ""),
            candle          = candle,
            pairCreatedAtMs = p.optLong("pairCreatedAt", 0L),
            liquidity       = (p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0),
            fdv             = p.optDouble("fdv", 0.0),
        )
    }

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0").build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (e: Exception) { null }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
