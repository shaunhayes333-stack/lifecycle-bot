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
        return try {
            // V5.0.8813 — the full coin list is ~4 MB (17,000+ coins, each with every chain's address): parsed as a JSONArray it
            // became tens of MB of objects on a heap the 8809 device ran at 280-512 MB of 512 (largest coingecko body 3.9 MB;
            // OutOfMemoryError after 2.5 h). Scanned in place, keeping only id -> Solana mint.
            val map = com.lifecyclebot.engine.JobCost8813.measure8813("coingeckoCoinList") {
                val body = get("https://api.coingecko.com/api/v3/coins/list?include_platform=true") ?: return idToMintCache
                idMintMap8813(body)
            }
            if (map.isEmpty()) return idToMintCache
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
        resp.use { if (it.isSuccessful) it.body?.string() else null }   // V5.0.8773 — closed on every path
    } catch (_: Exception) { null }

    companion object {
        /**
         * Pure (V5.0.8813): CoinGecko /coins/list?include_platform=true -> id to Solana mint, scanned without building the JSON
         * tree. Only a coin's top-level "id" and its "platforms" object's "solana" string are read; anything else is skipped.
         */
        fun idMintMap8813(body: CharSequence): HashMap<String, String> {
            val out = HashMap<String, String>(8192)
            val n = body.length
            var i = 0; var depth = 0
            var key: String? = null
            var id = ""; var mint = ""; var inPlatforms = false
            val sb = StringBuilder()
            fun readString(): String {           // at the opening quote; leaves i after the closing quote
                sb.setLength(0); i++
                while (i < n) {
                    val c = body[i]
                    if (c == '\\') {
                        if (i + 1 >= n) { i++; break }
                        when (val e = body[i + 1]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { if (i + 5 < n) { body.subSequence(i + 2, i + 6).toString().toIntOrNull(16)?.let { sb.append(it.toChar()) }; i += 4 } }
                            else -> sb.append(e)
                        }
                        i += 2; continue
                    }
                    if (c == '"') { i++; break }
                    sb.append(c); i++
                }
                return sb.toString()
            }
            while (i < n) {
                when (body[i]) {
                    '"' -> {
                        val str = readString()
                        var j = i
                        while (j < n && body[j].isWhitespace()) j++
                        if (j < n && body[j] == ':') { key = str; i = j + 1 }
                        else {
                            if (depth == 2 && key == "id") id = str
                            else if (depth == 3 && inPlatforms && key == "solana") mint = str
                            key = null
                        }
                    }
                    '{' -> {
                        val opening = depth
                        depth++
                        if (opening == 1) { id = ""; mint = ""; inPlatforms = false }
                        else if (opening == 2 && key == "platforms") inPlatforms = true
                        key = null; i++
                    }
                    '}' -> {
                        if (depth == 3) inPlatforms = false
                        if (depth == 2 && id.isNotBlank() && mint.length in 32..64) out[id] = mint
                        depth--; key = null; i++
                    }
                    '[' -> { depth++; key = null; i++ }
                    ']' -> { depth--; key = null; i++ }
                    else -> i++
                }
            }
            return out
        }
    }
}
