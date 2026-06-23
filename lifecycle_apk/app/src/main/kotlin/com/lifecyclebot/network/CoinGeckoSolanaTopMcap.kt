package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * V5.0.4097 — CoinGecko Solana top-by-market-cap feeder.
 *
 * Operator P0: lane-eval starvation. The SolanaMarketScanner deepScan batch
 * is dominated by pump.fun / raydium-new-pool / dex-boosted sources, all of
 * which surface fresh-launch / sub-microcap meme tokens. Established
 * lanes (BLUECHIP / DIP_HUNTER / QUALITY) saw 0–1 lane evals per cycle in
 * the V5.0.4096 operational report because no scanner was actually feeding
 * them established Solana mid/large-cap assets.
 *
 * Endpoint:
 *   GET /api/v3/coins/markets
 *       ?vs_currency=usd
 *       &category=solana-ecosystem
 *       &order=market_cap_desc
 *       &per_page=100
 *       &page=1
 *
 * Returns the top 100 Solana ecosystem tokens by market cap with current
 * price, 24h volume, 24h change. The response does NOT include the Solana
 * mint address, so we cross-reference with /api/v3/coins/list?include_platform=true
 * (cached 24h — the Solana token universe rarely changes daily).
 *
 * Free tier — no API key required. ~30 calls/min budget; we refresh markets
 * every 30 minutes (≤2/hour) to leave room for the rest of the bot's
 * coingecko usage.
 */
class CoinGeckoSolanaTopMcap {

    private val http: OkHttpClient = SharedHttpClient.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class EstablishedToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val coinGeckoId: String,
        val marketCapUsd: Double,
        val volume24hUsd: Double,
        val priceUsd: Double,
        val priceChange24hPct: Double,
        val mcapRank: Int,
    )

    // id -> solana mint map. Built once, refreshed every 24h.
    @Volatile private var idToMintCache: Map<String, String> = emptyMap()
    @Volatile private var idToMintFetchedAt: Long = 0L
    private val ID_MAP_TTL_MS = 24L * 60 * 60 * 1000

    @Volatile private var establishedCache: List<EstablishedToken> = emptyList()
    @Volatile private var establishedFetchedAt: Long = 0L
    private val MARKETS_TTL_MS = 8L * 60 * 1000  // V5.0.4108 — 8min refresh (was 30min) so SOL-wide fresh data lands faster

    /** Eager prime — call from BotService init so the cache populates
     *  on bot start instead of waiting for the first scanner pass. */
    fun primeOnStart() {
        try { refresh() } catch (_: Throwable) { }
    }

    /** Returns top-N Solana ecosystem tokens by mcap with mint addresses
     *  (only those that resolved successfully via the platform map). */
    fun getEstablished(): List<EstablishedToken> {
        val now = System.currentTimeMillis()
        if (now - establishedFetchedAt < MARKETS_TTL_MS && establishedCache.isNotEmpty()) {
            return establishedCache
        }
        return refresh()
    }

    fun refresh(): List<EstablishedToken> {
        val map = ensureIdToMintMap()
        if (map.isEmpty()) return establishedCache
        val url = "https://api.coingecko.com/api/v3/coins/markets" +
            "?vs_currency=usd" +
            "&category=solana-ecosystem" +
            "&order=market_cap_desc" +
            "&per_page=100" +
            "&page=1" +
            "&price_change_percentage=24h"
        val body = get(url) ?: return establishedCache
        return try {
            val arr = JSONArray(body)
            val list = mutableListOf<EstablishedToken>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val mint = map[id] ?: continue
                if (mint.isBlank()) continue
                list.add(
                    EstablishedToken(
                        mint = mint,
                        symbol = obj.optString("symbol", "").uppercase(),
                        name = obj.optString("name", ""),
                        coinGeckoId = id,
                        marketCapUsd = obj.optDouble("market_cap", 0.0),
                        volume24hUsd = obj.optDouble("total_volume", 0.0),
                        priceUsd = obj.optDouble("current_price", 0.0),
                        priceChange24hPct = obj.optDouble("price_change_percentage_24h", 0.0),
                        mcapRank = obj.optInt("market_cap_rank", 9999),
                    )
                )
            }
            establishedCache = list
            establishedFetchedAt = System.currentTimeMillis()
            ErrorLogger.info(
                "CoinGeckoTopMcap",
                "✅ refreshed established Solana top-mcap: ${list.size} tokens"
            )
            list
        } catch (e: Exception) {
            ErrorLogger.debug("CoinGeckoTopMcap", "parse error: ${e.message}")
            establishedCache
        }
    }

    private fun ensureIdToMintMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - idToMintFetchedAt < ID_MAP_TTL_MS && idToMintCache.isNotEmpty()) {
            return idToMintCache
        }
        val body = get("https://api.coingecko.com/api/v3/coins/list?include_platform=true")
            ?: return idToMintCache
        return try {
            val arr = JSONArray(body)
            val map = HashMap<String, String>(8192)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val platforms = obj.optJSONObject("platforms") ?: continue
                val solMint = platforms.optString("solana", "")
                if (id.isNotBlank() && solMint.isNotBlank() && solMint.length in 32..64) {
                    map[id] = solMint
                }
            }
            idToMintCache = map
            idToMintFetchedAt = System.currentTimeMillis()
            ErrorLogger.info(
                "CoinGeckoTopMcap",
                "✅ id→mint map cached: ${map.size} solana tokens"
            )
            map
        } catch (e: Exception) {
            ErrorLogger.debug("CoinGeckoTopMcap", "id-map parse error: ${e.message}")
            idToMintCache
        }
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "AATE/5.0 (Solana trader)")
            .build()
        val resp = HealthAwareHttp.execute(http, req, host = "coingecko")
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
