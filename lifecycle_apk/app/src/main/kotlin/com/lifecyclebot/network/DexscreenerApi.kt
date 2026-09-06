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
    val baseTokenAddress: String = "", // compatibility alias; use tokenAddress for multichain identity
    val quoteTokenAddress: String = "", // compatibility alias; use quoteAddress for multichain identity
    // V5.0.6544 — preserve provider-native multichain pair identity end-to-end.
    val chainId: String = "",
    val dexId: String = "",
    val tokenAddress: String = baseTokenAddress,
    val quoteAddress: String = quoteTokenAddress,
    val pairCreatedAt: Long = pairCreatedAtMs,
    // V5.9.911 — SOCIAL SIGNAL HARVEST. DexScreener already returns these in
    // info.socials / info.websites on every token-pairs response — they were
    // being dropped at parse time (memory #87 #1 "dropped signal = dropped
    // AGI sample"). Surfacing them here enables TokenSocialScorer to apply
    // a soft-shape trust multiplier without burning any new network requests.
    // Defaulted to empty lists so EVERY existing PairInfo call site stays
    // source-compatible.
    val socials: List<String> = emptyList(),     // social platform types: ["twitter","telegram","discord","medium",...]
    val websites: List<String> = emptyList(),    // website urls (deduped)
    val hasImage: Boolean = false,                // imageUrl present in info block
)

/**
 * V5.0.6682 — provider-boundary chain canonicalization.
 * Crypto Universe intentionally keeps source-native identities, but DexScreener's
 * token-pairs endpoint expects its own canonical chain ids. Passing discovery
 * aliases such as `eth`, `matic`, `arb`, or `op` straight through caused healthy
 * DexScreener to answer with no pair, which surfaced downstream as PRICE_UNAVAILABLE.
 */
internal fun normalizeDexscreenerChain6682(raw: String): String = when (raw.trim().lowercase()) {
    "eth", "ethereum", "erc20", "ethereum-mainnet" -> "ethereum"
    "sol", "solana", "solana-mainnet" -> "solana"
    "bnb", "bnbchain", "bsc", "binance-smart-chain", "binance_smart_chain" -> "bsc"
    "matic", "polygon", "polygon-pos", "polygon_pos" -> "polygon"
    "arb", "arbitrum", "arbitrum-one", "arbitrum_one" -> "arbitrum"
    "op", "optimism", "optimism-mainnet" -> "optimism"
    "base-mainnet", "base_mainnet", "base" -> "base"
    else -> raw.trim().lowercase()
}

class DexscreenerApi {

    private val http = SharedHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // V5.9.1030 — readTimeout shrunk 15s → 4s so a wedged DexScreener
        // socket can't tie up a supervisor chunk worker past the 4.5s
        // budget. Cache TTL of 45s + stale-tolerance of 135s mean any
        // dropped fetch is recovered on the next cycle.
        .readTimeout(4, TimeUnit.SECONDS)
        // V5.9.1459 — HARD CALL CEILING on the per-token hot path. getBestPair() is
        // the FIRST network hit inside processTokenCycle (BotService:~12995) and runs
        // under the 8s supervisor worker budget. Without callTimeout, dispatcher
        // queue-wait (48 workers vs maxRequestsPerHost) + connect + read could pin a
        // worker past 8s → it times out but the wedged socket keeps its IO thread →
        // IO-pool starvation cascade (session 9551671c: 526 worker_timeouts/10min,
        // processed=0). callTimeout bounds the WHOLE call (incl. queue wait); 6s < 8s
        // guarantees the worker is freed inside its lease. Cache TTL 45s + stale
        // tolerance recovers any dropped fetch next cycle — no quality loss.
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    
    // Simple cache for getBestPair results - avoids repeated API calls
    private data class CachedPair(val pair: PairInfo?, val timestamp: Long)
    private val pairCache = java.util.concurrent.ConcurrentHashMap<String, CachedPair>()
    private val CACHE_TTL_MS = 45_000L  // 45 seconds cache (was 15) - reduce API calls

    /** Returns the best-scoring pair for this mint on Solana, or null. */
    fun getBestPair(mint: String): PairInfo? = getBestPairInternal("solana", mint, allowDexPaprika = true)

    /**
     * V5.0.6544 — chain-aware DexScreener hydration for Crypto Universe.
     * This does not change MemeTrader's Solana-specialized getBestPair(mint).
     */
    fun getBestPair(chainId: String, tokenAddress: String): PairInfo? {
        val canonicalChainId = normalizeDexscreenerChain6682(chainId)
        if (canonicalChainId.isBlank() || tokenAddress.isBlank()) return null
        return getBestPairInternal(canonicalChainId, tokenAddress.trim(), allowDexPaprika = canonicalChainId == "solana")
    }

    private fun getBestPairInternal(rawChainId: String, tokenAddress: String, allowDexPaprika: Boolean): PairInfo? {
        // V5.0.6683 — normalize once at the provider boundary and keep the
        // canonical provider identity named `chainId` throughout URL selection,
        // response-chain validation and address case-sensitivity. This preserves
        // the V5.0.6493/6544 mint-identity Golden Tape while retaining 6682 aliases.
        val chainId = normalizeDexscreenerChain6682(rawChainId)
        val cacheKey = "$chainId|$tokenAddress"
        val cached = pairCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) return cached.pair

        // DexPaprika token hydration is explicitly Solana-only. Never project it
        // onto Base/ETH/BSC/etc identities.
        if (allowDexPaprika && RateLimiter.allowRequest("dexpaprika")) {
            val paprika = fetchDexPaprikaToken6512(tokenAddress)
            if (paprika != null) {
                pairCache[cacheKey] = CachedPair(paprika, now)
                return paprika
            }
        }

        if (cached != null && now - cached.timestamp < CACHE_TTL_MS * 3) {
            if (!RateLimiter.allowRequest("dexscreener")) return cached.pair
        } else if (!RateLimiter.allowRequest("dexscreener")) return null

        val url = "https://api.dexscreener.com/token-pairs/v1/${encode(chainId)}/${encode(tokenAddress)}"
        val body = get(url) ?: run {
            pairCache[cacheKey] = CachedPair(null, System.currentTimeMillis())
            return null
        }
        val pairs = JSONArray(body)
        if (pairs.length() == 0) {
            pairCache[cacheKey] = CachedPair(null, System.currentTimeMillis())
            return null
        }
        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until pairs.length()) {
            val row = pairs.getJSONObject(i)
            val rowChain = normalizeDexscreenerChain6682(row.optString("chainId", ""))
            if (rowChain.isNotBlank() && rowChain != chainId) continue
            val baseAddress = row.optJSONObject("baseToken")?.optString("address", "") ?: ""
            if (!baseAddress.equals(tokenAddress, ignoreCase = chainId != "solana")) continue
            val score = scorePair(row)
            if (score > bestScore) { bestScore = score; best = row }
        }
        val result = best?.let { parsePair(it) }
        pairCache[cacheKey] = CachedPair(result, System.currentTimeMillis())
        if (pairCache.size > 400) {
            val cutoffNow = System.currentTimeMillis()
            pairCache.entries.removeIf { cutoffNow - it.value.timestamp > CACHE_TTL_MS * 4 }
        }
        return result
    }

    /** Search by query string — used for token discovery */
    fun search(query: String): List<PairInfo> {
        if (!RateLimiter.allowRequest("dexscreener")) {
            // Silently return empty - don't spam logs
            return emptyList()
        }
        val url = "https://api.dexscreener.com/latest/dex/search?q=${encode(query)}"
        val body = get(url) ?: return emptyList()
        val arr  = JSONObject(body).optJSONArray("pairs") ?: return emptyList()
        return (0 until minOf(arr.length(), 20)).mapNotNull { parsePair(arr.getJSONObject(it)) }
            .filter { it.candle.priceUsd > 0 }
    }
    // ── internals ──────────────────────────────────────────

    private fun fetchDexPaprikaToken6512(mint: String): PairInfo? {
        val body = get("https://api.dexpaprika.com/networks/solana/tokens/$mint", "dexpaprika") ?: return null
        return try {
            val json = JSONObject(body)
            if (!json.optString("id", "").equals(mint, true)) return null
            val summary = json.optJSONObject("summary") ?: return null
            val h1 = summary.optJSONObject("1h")
            val h24 = summary.optJSONObject("24h")
            val price = summary.optDouble("price_usd", 0.0)
            if (!price.isFinite() || price <= 0.0) return null
            PairInfo(
                pairAddress = "",
                baseSymbol = json.optString("symbol", ""),
                baseName = json.optString("name", ""),
                url = "https://dexpaprika.com/solana/token/$mint",
                candle = Candle(
                    ts = System.currentTimeMillis(), priceUsd = price, marketCap = 0.0,
                    volumeH1 = h1?.optDouble("volume_usd", 0.0) ?: 0.0,
                    volume24h = h24?.optDouble("volume_usd", 0.0) ?: 0.0,
                    buysH1 = h1?.optInt("buys", 0) ?: 0,
                    sellsH1 = h1?.optInt("sells", 0) ?: 0,
                    buys24h = h24?.optInt("buys", 0) ?: 0,
                    sells24h = h24?.optInt("sells", 0) ?: 0,
                ),
                pairCreatedAtMs = try { java.time.Instant.parse(json.optString("added_at", "")).toEpochMilli() } catch (_: Throwable) { 0L },
                liquidity = summary.optDouble("liquidity_usd", 0.0),
                fdv = summary.optDouble("fdv", 0.0),
                baseTokenAddress = mint,
                quoteTokenAddress = "USD",
                chainId = "solana", dexId = "dexpaprika",
                tokenAddress = mint, quoteAddress = "USD",
                pairCreatedAt = try { java.time.Instant.parse(json.optString("added_at", "")).toEpochMilli() } catch (_: Throwable) { 0L },
            )
        } catch (_: Throwable) { null }
    }

    private fun scorePair(p: JSONObject): Double {
        val liq  = p.optJSONObject("liquidity")?.optDouble("usd",  0.0) ?: 0.0
        val vol  = p.optJSONObject("volume")?.optDouble("h24",     0.0) ?: 0.0
        val txns = p.optJSONObject("txns")?.optJSONObject("h24")
        val cnt  = (txns?.optInt("buys", 0) ?: 0) + (txns?.optInt("sells", 0) ?: 0)
        return liq * 1.5 + vol + cnt * 10.0
    }

    private fun parsePair(p: JSONObject): PairInfo {
        val base    = p.optJSONObject("baseToken")
        val quote   = p.optJSONObject("quoteToken")
        val vol     = p.optJSONObject("volume")
        val txns    = p.optJSONObject("txns")
        val h1      = txns?.optJSONObject("h1")
        val h24     = txns?.optJSONObject("h24")

        val candle = Candle(
            ts          = System.currentTimeMillis(),
            priceUsd    = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0,
            // V5.0.6492 — marketCap and FDV are different economics.
            // Never silently relabel `fdv` as circulating market cap; PairInfo
            // already carries the provider's FDV separately.
            marketCap   = p.optDouble("marketCap", 0.0),
            volumeH1    = vol?.optDouble("h1",  0.0) ?: 0.0,
            volume24h   = vol?.optDouble("h24", 0.0) ?: 0.0,
            buysH1      = h1?.optInt("buys",   0) ?: 0,
            sellsH1     = h1?.optInt("sells",  0) ?: 0,
            buys24h     = h24?.optInt("buys",  0) ?: 0,
            sells24h    = h24?.optInt("sells", 0) ?: 0,
        )

        // V5.9.911 — Harvest social/website signals from info block.
        // DexScreener returns these on every token-pairs response and we
        // were silently discarding them (dropped AGI sample per memory #87).
        val infoBlock = p.optJSONObject("info")
        val socialsList = mutableListOf<String>()
        val websitesList = mutableListOf<String>()
        var imagePresent = false
        if (infoBlock != null) {
            imagePresent = infoBlock.optString("imageUrl", "").isNotBlank()
            val socialsArr = infoBlock.optJSONArray("socials")
            if (socialsArr != null) {
                for (i in 0 until socialsArr.length()) {
                    val s = socialsArr.optJSONObject(i) ?: continue
                    val type = s.optString("type", "").lowercase().trim()
                    if (type.isNotBlank() && type !in socialsList) socialsList += type
                }
            }
            val websitesArr = infoBlock.optJSONArray("websites")
            if (websitesArr != null) {
                for (i in 0 until websitesArr.length()) {
                    val w = websitesArr.optJSONObject(i) ?: continue
                    val url = w.optString("url", "").trim()
                    if (url.isNotBlank() && url !in websitesList) websitesList += url
                }
            }
        }

        return PairInfo(
            pairAddress      = p.optString("pairAddress", ""),
            baseSymbol       = base?.optString("symbol", "") ?: "",
            baseName         = base?.optString("name",   "") ?: "",
            url              = p.optString("url", ""),
            candle           = candle,
            pairCreatedAtMs  = p.optLong("pairCreatedAt", 0L),
            liquidity        = (p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0),
            fdv              = p.optDouble("fdv", 0.0),
            baseTokenAddress = base?.optString("address", "") ?: "",
            quoteTokenAddress = quote?.optString("address", "") ?: "",
            chainId          = normalizeDexscreenerChain6682(p.optString("chainId", "")),
            dexId            = p.optString("dexId", ""),
            tokenAddress     = base?.optString("address", "") ?: "",
            quoteAddress     = quote?.optString("address", "") ?: "",
            pairCreatedAt    = p.optLong("pairCreatedAt", 0L),
            socials          = socialsList.toList(),
            websites         = websitesList.toList(),
            hasImage         = imagePresent,
        )
    }

    /**
     * V5.9.730 — Batch price fetch for open-position 1Hz tick loop.
     *
     * DexScreener supports comma-separated mint lists at /tokens/v1/solana/<a>,<b>,<c>
     * with up to 30 mints per call. Returns a map of mint → priceUsd for every
     * pair found; mints with no pair are simply absent from the map.
     *
     * Bypasses the 45s pair cache because the whole point of this call is a
     * fresh tick. Respects the rate-limiter so we cannot hammer DS into a 429.
     * If the rate-limit denies us, returns an empty map (the position monitor
     * will just keep its existing prices for one more cycle — no rug-escape
     * because lastPriceUpdate is not bumped on empty result).
     *
     * Used by BotService.openPositionTickLoop. Do NOT use this for scanner
     * intake — that path has its own caching and scoring needs.
     */
    fun batchPriceFetch(mints: List<String>): Map<String, Double> {
        if (mints.isEmpty()) return emptyMap()
        if (!RateLimiter.allowRequest("dexscreener")) return emptyMap()

        // DS hard limit: 30 mints per request. Trim defensively.
        val take = mints.take(30).joinToString(",")
        val url  = "https://api.dexscreener.com/tokens/v1/solana/$take"
        val body = get(url) ?: return emptyMap()

        val out = HashMap<String, Double>(mints.size)
        try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val base = p.optJSONObject("baseToken") ?: continue
                val mint = base.optString("address", "") ?: continue
                if (mint.isBlank()) continue
                val priceUsd = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
                if (priceUsd <= 0.0) continue
                // If multiple pairs returned for the same mint, keep the
                // best-liquidity one (higher = more trustworthy mid-price).
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val existing = out[mint]
                if (existing == null || liq > 0.0) out[mint] = priceUsd
            }
        } catch (_: Exception) { /* return whatever we have */ }

        return out
    }

    private fun get(url: String, host: String = "dexscreener"): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0").build()
        // V5.0.6495 — never bypass HealthAwareHttp/ApiBackoff with a raw retry.
        // A wrapper/network failure is a provider failure, not permission to fire
        // a second same-cycle request that defeats the circuit breaker.
        val resp = com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = host)
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (e: Exception) { null }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
