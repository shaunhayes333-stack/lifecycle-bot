package com.lifecyclebot.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CoinGecko Trending Feed
 *
 * Free endpoint — no API key required.
 * Returns top 7 trending coins by search volume over last 24h.
 *
 * Why this matters for Solana meme trading:
 *   • A Solana token appearing in CoinGecko trending = mainstream attention arriving
 *   • This often precedes a second pump as new money enters from CoinGecko users
 *   • Can add +15 entry score and reduce exit urgency on trending tokens
 *
 * Refresh: every 10 minutes (CoinGecko updates trending hourly)
 * Endpoint: https://api.coingecko.com/api/v3/search/trending
 */
class CoinGeckoTrending {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class TrendingToken(
        val id: String,
        val symbol: String,
        val name: String,
        val marketCapRank: Int,
        val priceChangePercent: Double,
        val sparklineThumbUrl: String,
        val score: Int,   // CoinGecko trending rank 0-6 (0 = hottest)
    )

    private var cachedTrending   = listOf<TrendingToken>()
    private var lastFetch        = 0L
    private val CACHE_TTL_MS     = 10 * 60_000L

    // Quick lookup: symbol.lowercase() → TrendingToken
    private var trendingBySymbol = mapOf<String, TrendingToken>()

    fun getTrending(): List<TrendingToken> {
        if (System.currentTimeMillis() - lastFetch < CACHE_TTL_MS) return cachedTrending
        return refresh()
    }

    fun refresh(): List<TrendingToken> {
        val body = get("https://api.coingecko.com/api/v3/search/trending") ?: return cachedTrending
        return try {
            val json  = JSONObject(body)
            val coins = json.optJSONArray("coins") ?: return cachedTrending
            val list  = mutableListOf<TrendingToken>()

            for (i in 0 until coins.length()) {
                val item = coins.optJSONObject(i)?.optJSONObject("item") ?: continue
                list.add(TrendingToken(
                    id                  = item.optString("id", ""),
                    symbol              = item.optString("symbol", "").lowercase(),
                    name                = item.optString("name",   ""),
                    marketCapRank       = item.optInt("market_cap_rank", 999),
                    priceChangePercent  = item.optJSONObject("data")
                        ?.optDouble("price_change_percentage_24h", 0.0) ?: 0.0,
                    sparklineThumbUrl   = item.optJSONObject("data")
                        ?.optString("sparkline", "") ?: "",
                    score               = i,
                ))
            }

            cachedTrending   = list
            trendingBySymbol = list.associateBy { it.symbol.lowercase() }
            lastFetch        = System.currentTimeMillis()
            list
        } catch (_: Exception) { cachedTrending }
    }

    /**
     * Check if a token symbol is currently trending.
     * Returns the trending rank (0 = hottest, 6 = least hot) or null if not trending.
     */
    fun getTrendingRank(symbol: String): Int? {
        getTrending()  // refresh if stale
        return trendingBySymbol[symbol.lowercase()]?.score
    }

    /**
     * Entry score boost for a trending token.
     * Rank 0 (hottest) = +25 pts, rank 6 = +5 pts
     */
    fun entryScoreBoost(symbol: String): Double {
        val rank = getTrendingRank(symbol) ?: return 0.0
        return 25.0 - rank * 3.0   // 25, 22, 19, 16, 13, 10, 7
    }

    /**
     * Check if NFT collections or DeFi protocols are trending —
     * can indicate broader Solana ecosystem momentum
     */
    fun getSolanaEcosystemMomentum(): Double {
        val trending = getTrending()
        val solanaRelated = trending.count { t ->
            t.id.contains("sol") || t.symbol in listOf("sol", "jup", "ray", "orca", "bonk")
        }
        return (solanaRelated / 7.0 * 100.0).coerceIn(0.0, 100.0)
    }

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("Accept", "application/json")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
