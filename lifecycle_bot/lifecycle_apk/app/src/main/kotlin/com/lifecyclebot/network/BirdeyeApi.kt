package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Birdeye API client — free tier, no key required for basic endpoints.
 *
 * Provides:
 *   1. OHLCV candle history — proper candles, not fake ones from polling
 *   2. Token security report — mint authority, freeze, top holders
 *   3. Token overview — price, volume, liquidity, holder count
 *
 * Free endpoints (no API key):
 *   /defi/price                   — current price
 *   /defi/ohlcv                   — OHLCV candles (requires free key for higher limits)
 *   /defi/token_security           — security data
 *   /defi/token_overview           — comprehensive token data
 *
 * Free API key at: https://birdeye.so/developer (takes 1 minute)
 * With key: 100 req/min. Without: 10 req/min.
 *
 * We seed the candle history with real Birdeye OHLCV data when a token
 * is first added to the watchlist. This means the strategy has 100 real
 * candles immediately instead of waiting 14 minutes to accumulate them.
 */
class BirdeyeApi(private val apiKey: String = "") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val BASE = "https://public-api.birdeye.so"

    // ── OHLCV candle history ──────────────────────────────────────────

    /**
     * Fetch the last [count] candles for a token.
     * timeframe: "1m" | "3m" | "5m" | "15m" | "30m" | "1H"
     *
     * We default to 1-minute candles to seed the strategy with
     * ~100 candles of real price history immediately.
     */
    fun getCandles(
        mint: String,
        timeframe: String = "1m",
        count: Int = 100,
    ): List<Candle> {
        val now      = System.currentTimeMillis() / 1000
        val interval = timeframeToSeconds(timeframe)
        val from     = now - (count * interval)

        val url = "$BASE/defi/ohlcv?address=$mint" +
                  "&type=$timeframe&time_from=$from&time_to=$now"

        val body = get(url) ?: return emptyList()
        return try {
            val json  = JSONObject(body)
            val items = json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                Candle(
                    ts         = item.optLong("unixTime", 0L) * 1000L,
                    priceUsd   = item.optDouble("c", 0.0),   // close
                    marketCap  = 0.0,                         // filled by overview
                    volumeH1   = item.optDouble("v", 0.0),
                    volume24h  = 0.0,
                    buysH1     = 0,
                    sellsH1    = 0,
                    // Real OHLC — the key improvement over polling
                    highUsd    = item.optDouble("h", 0.0),
                    lowUsd     = item.optDouble("l", 0.0),
                    openUsd    = item.optDouble("o", 0.0),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── Token security ────────────────────────────────────────────────

    data class SecurityReport(
        val mintAuthorityDisabled: Boolean?,
        val freezeAuthorityDisabled: Boolean?,
        val lpLockPct: Double,
        val top10HolderPct: Double,
        val creatorBalance: Double,         // how much token the creator still holds
        val isOpenSource: Boolean?,
    )

    fun getTokenSecurity(mint: String): SecurityReport? {
        val body = get("$BASE/defi/token_security?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            SecurityReport(
                mintAuthorityDisabled   = data.optInt("mintable", -1)
                    .let { if (it < 0) null else it == 0 },
                freezeAuthorityDisabled = data.optInt("freezeable", -1)
                    .let { if (it < 0) null else it == 0 },
                lpLockPct               = data.optDouble("lpPercentage", -1.0),
                top10HolderPct          = data.optDouble("top10HolderPercent", -1.0) * 100,
                creatorBalance          = data.optDouble("creatorBalance", 0.0),
                isOpenSource            = data.optInt("isOpenSource", -1)
                    .let { if (it < 0) null else it == 1 },
            )
        } catch (_: Exception) { null }
    }

    // ── Token overview ────────────────────────────────────────────────

    data class TokenOverview(
        val symbol: String,
        val name: String,
        val priceUsd: Double,
        val marketCap: Double,
        val liquidity: Double,
        val volume24h: Double,
        val priceChange24h: Double,
        val holderCount: Int,
        val createdAt: Long,          // epoch ms
    )

    fun getTokenOverview(mint: String): TokenOverview? {
        val body = get("$BASE/defi/token_overview?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            TokenOverview(
                symbol        = data.optString("symbol", ""),
                name          = data.optString("name",   ""),
                priceUsd      = data.optDouble("price",  0.0),
                marketCap     = data.optDouble("mc",     0.0),
                liquidity     = data.optDouble("liquidity", 0.0),
                volume24h     = data.optDouble("v24hUSD",   0.0),
                priceChange24h = data.optDouble("priceChange24hPercent", 0.0),
                holderCount   = data.optInt("holder", 0),
                createdAt     = data.optLong("createdAt", 0L) * 1000L,
            )
        } catch (_: Exception) { null }
    }

    // ── HTTP helper ───────────────────────────────────────────────────

    private fun get(url: String): String? = try {
        val builder = Request.Builder().url(url)
            .header("accept", "application/json")
            .header("x-chain", "solana")
        if (apiKey.isNotBlank()) {
            builder.header("X-API-KEY", apiKey)
        }
        val resp = http.newCall(builder.build()).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    private fun timeframeToSeconds(tf: String): Long = when (tf) {
        "1m"  -> 60L
        "3m"  -> 180L
        "5m"  -> 300L
        "15m" -> 900L
        "30m" -> 1800L
        "1H"  -> 3600L
        else  -> 60L
    }
}
