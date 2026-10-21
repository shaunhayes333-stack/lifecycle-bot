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
    // V5.0.7758 — the poll already returns the five-minute tape and the price
    // changes the disabled DexScreener socket used to supply. NaN / -1 = absent.
    val buysM5: Int = -1,
    val sellsM5: Int = -1,
    val volumeM5: Double = Double.NaN,
    val priceChangeM5: Double = Double.NaN,
    val priceChangeH1: Double = Double.NaN,
)

class DexscreenerApi {

    // V5.0.7771 — provider bodies are untrusted. 5.0.7749 OOMd in JSONTokener
    // after OkHttp materialised a large response. Bound before JSON parsing.
    private companion object {
        const val MAX_DEXSCREENER_RESPONSE_CHARS_7771 = 2_000_000
        const val MAX_DEXSCREENER_CONTENT_BYTES_7771 = 4_000_000L
    }

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

    /**
     * V5.0.7884 — Cortex Phase 0 (Marks C1): how old the Solana pair this
     * instance would serve for [mint] is (Long.MAX_VALUE when not cached).
     * getBestPair can return a cached pair up to 45 s old (135 s when rate
     * limited); a caller that stamps "now" on it must check this first.
     */
    fun pairAgeMs7884(mint: String): Long =
        pairCache["solana|$mint"]?.let { (System.currentTimeMillis() - it.timestamp).coerceAtLeast(0L) } ?: Long.MAX_VALUE

    /** Returns the best-scoring pair for this mint on Solana, or null. */
    // V5.0.6946 — DexPaprika removed (HTTP 402, paid product). Never routed to.
    fun getBestPair(mint: String): PairInfo? = getBestPairInternal("solana", mint, allowDexPaprika = false)

    /**
     * V5.0.6544 — chain-aware DexScreener hydration for Crypto Universe.
     * This does not change MemeTrader's Solana-specialized getBestPair(mint).
     */
    fun getBestPair(chainId: String, tokenAddress: String): PairInfo? {
        val chain = chainId.trim().lowercase()
        if (chain.isBlank() || tokenAddress.isBlank()) return null
        return getBestPairInternal(chain, tokenAddress.trim(), allowDexPaprika = false)
    }

    private fun getBestPairInternal(chainId: String, tokenAddress: String, allowDexPaprika: Boolean): PairInfo? {
        val cacheKey = "$chainId|$tokenAddress"
        val cached = pairCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) return cached.pair

        // V5.0.6894 §THE_DEAD_PROVIDER_WAS_FIRST_IN_LINE.
        //
        // DexPaprika used to be attempted BEFORE DexScreener on every Solana
        // hydration. Operator 5.0.6892 measured what that costs:
        //   dexpaprika    sr=0%  4xx=12  5xx=1716
        //   dexscreener   sr=99% s=1491
        // and this function's own header notes it is "the FIRST network hit
        // inside processTokenCycle... under the 8s supervisor worker budget"
        // with a 6s callTimeout. So every token paid up to six seconds on a
        // corpse before reaching the provider that works. That is where
        // workerTimeout=8, avgCycle=7123ms and — because exit marks come
        // through this same call — missingMark=99 of 100 open positions came
        // from.
        //
        // DexScreener is keyless, 300 req/min, and the healthiest provider in
        // the fleet, so it goes first. DexPaprika stays as a genuine fallback
        // for the case DexScreener has no pair, but only while it is actually
        // alive: ApiHealthMonitor.isCircuitBroken already encodes "this host
        // has proven itself dead" (>=30 5xx/net with sr<10%) and was wired for
        // birdeye only. Nothing is removed and no key is required anywhere in
        // this path.
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS * 3) {
            if (!RateLimiter.allowRequest("dexscreener")) return cached.pair
        } else if (!RateLimiter.allowRequest("dexscreener")) return null

        val url = "https://api.dexscreener.com/token-pairs/v1/${encode(chainId)}/${encode(tokenAddress)}"
        val body = get(url)
        var best: JSONObject? = null
        if (body != null) {
            val pairs = JSONArray(body)
            var bestScore = -1.0
            for (i in 0 until pairs.length()) {
                val row = pairs.getJSONObject(i)
                val baseAddress = row.optJSONObject("baseToken")?.optString("address", "") ?: ""
                if (!baseAddress.equals(tokenAddress, ignoreCase = chainId != "solana")) continue
                if (chainId == "solana") DexPools8285.rowOf8285(row, now)?.let { DexPools8285.record8285(listOf(it)) }  // V5.0.8285 — pool identity kept
                val score = scorePair(row)
                if (score > bestScore) { bestScore = score; best = row }
            }
        }
        var result = best?.let { parsePair(it) }
        if (chainId == "solana") result = result?.let { withHolders8128(it, tokenAddress) }  // V5.0.8128
        // V5.0.8169 — every best pair served is a dated flow reading for the setup inputs (SetupFeatureResolver8169).
        if (chainId == "solana" && best != null) result?.let { p ->
            // V5.0.8285 FEATURE_ARRIVED_POOL_FLOW(mint)
            try { com.lifecyclebot.engine.truth.PairFlow8169.notePair8169(tokenAddress, p, now) } catch (_: Throwable) {}
        }
        // Fallback only — and only to a host that is not circuit-broken.
        if (result == null && allowDexPaprika) {
            // V5.0.7809 — also out when DexPaprika is latched terminal (401/402/403).
            val paprikaDead6894 = try {
                com.lifecyclebot.engine.ApiHealthMonitor.isCircuitBroken("dexpaprika") ||
                    SolanaOhlcvFeed6916.paprikaTerminal7809()
            } catch (_: Throwable) { false }
            if (paprikaDead6894) {
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector
                        .labelInc("DEXPAPRIKA_SKIPPED_CIRCUIT_BROKEN_6894")
                } catch (_: Throwable) {}
            } else if (RateLimiter.allowRequest("dexpaprika")) {
                result = fetchDexPaprikaToken6512(tokenAddress)
                if (result != null) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector
                            .labelInc("DEXPAPRIKA_FALLBACK_HIT_6894")
                    } catch (_: Throwable) {}
                }
            }
        }
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

    /**
     * V5.0.8128 — DexScreener carries no holder count, and nothing else filled Candle.holderCount, so
     * peakHolderCount / holderGrowthRate read 0 on every token. Jupiter's count (sweep row or paced
     * on-demand search) is stamped on the best pair's candle; no answer leaves it 0 (unknown).
     */
    private fun withHolders8128(pair: PairInfo, mint: String): PairInfo {
        if (pair.candle.holderCount > 0) return pair
        val h = try { JupiterHolders8128.holders8128(mint) } catch (_: Throwable) { null } ?: return pair
        return pair.copy(candle = pair.candle.copy(holderCount = h))
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

        // V5.0.7385 — every pair served carries its creation time; record it per
        // base mint (earliest wins, so a graduated token keeps its launch time).
        try {
            val baseAddr7385 = base?.optString("address", "").orEmpty()
            if (p.optString("chainId", "").equals("solana", true) || p.optString("chainId", "").isBlank()) {
                com.lifecyclebot.engine.truth.PoolCreationTime7385.record(
                    baseAddr7385, p.optLong("pairCreatedAt", 0L), "DEXSCREENER",
                )
                // V5.0.8169 — named birth witness: a pump mint's own curve pair is its birth, any other pool a floor.
                com.lifecyclebot.engine.truth.AdmissionBirth8144.notePairCreated8169(baseAddr7385, p.optString("dexId", ""), p.optLong("pairCreatedAt", 0L))
            }
        } catch (_: Throwable) {}
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
            chainId          = p.optString("chainId", ""),
            dexId            = p.optString("dexId", ""),
            tokenAddress     = base?.optString("address", "") ?: "",
            quoteAddress     = quote?.optString("address", "") ?: "",
            pairCreatedAt    = p.optLong("pairCreatedAt", 0L),
            socials          = socialsList.toList(),
            websites         = websitesList.toList(),
            hasImage         = imagePresent,
            buysM5           = txns?.optJSONObject("m5")?.optInt("buys", -1) ?: -1,
            sellsM5          = txns?.optJSONObject("m5")?.optInt("sells", -1) ?: -1,
            volumeM5         = vol?.takeIf { it.has("m5") }?.optDouble("m5", Double.NaN) ?: Double.NaN,
            priceChangeM5    = p.optJSONObject("priceChange")?.takeIf { it.has("m5") }?.optDouble("m5", Double.NaN) ?: Double.NaN,
            priceChangeH1    = p.optJSONObject("priceChange")?.takeIf { it.has("h1") }?.optDouble("h1", Double.NaN) ?: Double.NaN,
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
    /**
     * V5.0.7392 — price EXACT pools. The token register seals the pool a position
     * was bought from; this reads that pool and nothing else, so the "best pair"
     * guess (the source of the 283x wrong-pair marks) is never involved.
     * Returns pairAddress -> (baseMint, priceUsd). Up to 30 pools per call.
     */
    fun pairPriceFetch7392(pairAddresses: List<String>): Map<String, Pair<String, Double>> {
        val wanted = pairAddresses.map { it.trim() }.filter { it.length in 32..44 }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        if (!RateLimiter.allowRequest("dexscreener")) return emptyMap()
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana/${wanted.take(30).joinToString(",")}"
        val body = get(url) ?: return emptyMap()
        val out = HashMap<String, Pair<String, Double>>()
        try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("pairs") ?: root.optJSONObject("pair")?.let { JSONArray().put(it) } ?: return emptyMap()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pairAddr = p.optString("pairAddress", "").trim()
                val base = p.optJSONObject("baseToken")?.optString("address", "")?.trim().orEmpty()
                val px = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
                if (pairAddr.isBlank() || base.isBlank() || !px.isFinite() || px <= 0.0) continue
                out[pairAddr] = base to px
            }
        } catch (_: Throwable) {}
        return out
    }

    /**
     * V5.0.8087 §P1.5 — why a held-mark batch came back empty. MARK_BATCH_EMPTY_6970 was logged
     * "cause=rate_limited_or_no_data" 447 times beside riskClockStale=445: the tick could not tell a
     * starved limiter (retry now) from a provider refusal (back off) from an unlisted token (go to
     * chain). An unknown quote is unknown, never zero liquidity; the cause now travels with the batch.
     */
    enum class BatchCause8087 { PRICED, LIMITER_REFUSED, PROVIDER_FAILED, NO_DATA, UNPARSEABLE }
    data class BatchResult8087(val prices: Map<String, Double>, val cause: BatchCause8087, val unlisted: List<String>)

    fun batchPriceFetch(mints: List<String>, slotWaitMs7948: Long = 0L): Map<String, Double> =
        batchPriceFetchTyped8087(mints, slotWaitMs7948).prices

    fun batchPriceFetchTyped8087(mints: List<String>, slotWaitMs7948: Long = 0L): BatchResult8087 {
        if (mints.isEmpty()) return BatchResult8087(emptyMap(), BatchCause8087.PRICED, emptyList())
        // V5.0.7948 — the held-mark batch (rapid stop-loss tick) waits briefly for
        // the shared 25 ms limiter slot instead of returning an empty batch whenever
        // any scanner call landed in the same instant (MARK_BATCH_EMPTY_6970=681).
        if (!RateLimiter.waitForSlot("dexscreener", slotWaitMs7948)) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MARK_BATCH_LIMITER_REFUSED_7948") } catch (_: Throwable) {}
            return BatchResult8087(emptyMap(), BatchCause8087.LIMITER_REFUSED, emptyList())
        }

        // DS hard limit: 30 mints per request. Trim defensively.
        val take = mints.take(30).joinToString(",")
        val url  = "https://api.dexscreener.com/tokens/v1/solana/$take"
        // V5.0.8095 — the held-position batch (the only caller that waits for a slot) is exit traffic: a scanner 429 put
        // the shared "dexscreener" host in OUR lockout and the stop-loss marks came back empty on 1,948 of 2,297 ticks
        // (4abd6b13a, cause=PROVIDER_FAILED). Exit traffic is never refused by our own lockout (ExitHttpScope7314).
        val body = get8095(url, exit8095 = slotWaitMs7948 > 0L) ?: return BatchResult8087(emptyMap(), BatchCause8087.PROVIDER_FAILED, emptyList())

        val out = HashMap<String, Double>(mints.size)
        // V5.0.7026 — the liquidity behind each mint's currently-chosen pair,
        // so "best" can actually be compared rather than asserted.
        val bestLiqByMint = HashMap<String, Double>(mints.size)
        val batchAtMs8169 = System.currentTimeMillis()
        try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val base = p.optJSONObject("baseToken") ?: continue
                val mint = base.optString("address", "") ?: continue
                if (mint.isBlank()) continue
                val priceUsd = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
                if (priceUsd <= 0.0) continue
                // V5.0.7026 §THE_COMMENT_SAID_BEST_LIQUIDITY_THE_CODE_SAID_LAST.
                //
                // Intent, unchanged since this was written: "If multiple pairs
                // returned for the same mint, keep the best-liquidity one
                // (higher = more trustworthy mid-price)."
                //
                // What it did: `if (existing == null || liq > 0.0)` overwrites
                // on ANY pair carrying liquidity above zero, so the winner was
                // simply the LAST such pair in DexScreener's response order.
                // Nothing compared one liquidity against another — the only
                // quantity the rule is about was never used as a comparison.
                //
                // That matters because a mint routinely returns several pairs
                // and some are near-empty junk whose mid-price is meaningless.
                // The operator's 5.0.7024 snapshot shows what arrives when one
                // of those lands last:
                //
                //   STALE_PRICE_QUARANTINED mint=Dz9mQ9NzkB
                //     entryPrice=2.53e-07 lastPrice=0.2521 gainMultiple=995863
                //   HERO_OPENMV_PER_POSITION_QUARANTINE_6604 mint=XsqE9cRRpz
                //     costBasis=0.0375 rawMark=14499.39 ratio=386650.3x
                //
                //   ... 2,400 quarantines in one session, all src=DEXSCREENER_BATCH.
                //
                // The downstream guards caught every one and refused to trade or
                // learn on them, which is why this cost noise rather than money.
                // But a mark that has to be thrown away is a mark the exit
                // engine never got, and this is the fix at the source.
                //
                // Now genuinely best-of: remember the winning pair's liquidity
                // and only replace when a strictly better one arrives.
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val bestLiq = bestLiqByMint[mint]
                DexPools8285.rowOf8285(p, batchAtMs8169)?.let { DexPools8285.record8285(listOf(it)) }  // V5.0.8285 — every pool's identity kept
                if (bestLiq == null || liq > bestLiq) {
                    bestLiqByMint[mint] = liq
                    out[mint] = priceUsd
                    // V5.0.8169 — the same pair's flow (txns / priceChange / liquidity) was dropped after the price.
                    // V5.0.8285 FEATURE_ARRIVED_POOL_FLOW(mint)
                    try { com.lifecyclebot.engine.truth.PairFlow8169.note8169(mint, com.lifecyclebot.engine.truth.PairFlow8169.fromJson8169(p, batchAtMs8169)) } catch (_: Throwable) {}
                }
            }
        } catch (_: Exception) {
            // return whatever we have; a body that parsed to nothing is UNPARSEABLE, not "no such token"
            if (out.isEmpty()) return BatchResult8087(emptyMap(), BatchCause8087.UNPARSEABLE, emptyList())
        }

        // Mints the provider answered without a pair are unlisted there: the caller routes them
        // to chain/venue pricing at once instead of re-asking a provider that has no answer.
        val unlisted = mints.take(30).filter { it !in out }
        return BatchResult8087(out, if (out.isEmpty()) BatchCause8087.NO_DATA else BatchCause8087.PRICED, unlisted)
    }

    private fun get(url: String, host: String = "dexscreener"): String? = get8095(url, host, exit8095 = false)

    /**
     * V5.0.8285 — ordinary pair updates in batches: /tokens/v1/solana/{up to 30 mints} (keyless, ~300 req/min, one
     * limiter slot for 30 coins instead of 30). Every pool returned keeps its identity in [DexPools8285]; each mint's
     * flow is read from its deliberately chosen data pool ([DexPools8285.choose8285]: the pool already in use, else the
     * deepest SOL / stable-quoted pool) — never a blend of pools. The HTTP answer feeds the provider's own backoff
     * (ProviderLanes8285): a DexScreener cooldown never touches another provider. Nothing is inferred for a mint the
     * answer did not list.
     */
    fun batchPairs8285(mints: List<String>, nowMs: Long = System.currentTimeMillis()): BatchPairs8285 {
        val want = mints.map { it.trim() }.filter { it.length in 32..44 }.distinct().take(DexPools8285.BATCH_MAX_8285)
        if (want.isEmpty()) return BatchPairs8285(emptySet(), emptyList(), BatchCause8087.NO_DATA)
        val lanes = com.lifecyclebot.engine.ProviderLanes8285
        if (lanes.cooling8285(lanes.DEX_8285, nowMs) || !RateLimiter.allowRequest("dexscreener"))
            return BatchPairs8285(emptySet(), want, BatchCause8087.LIMITER_REFUSED)
        val code = IntArray(1)
        val body = get8095("https://api.dexscreener.com/tokens/v1/solana/${want.joinToString(",")}", exit8095 = false, code8285 = code)
        try { lanes.noteResult8285(lanes.DEX_8285, code[0], nowMs) } catch (_: Throwable) {}
        if (body == null) return BatchPairs8285(emptySet(), want, BatchCause8087.PROVIDER_FAILED)
        val arr = try { JSONArray(body) } catch (_: Throwable) { return BatchPairs8285(emptySet(), want, BatchCause8087.UNPARSEABLE) }
        val rows = ArrayList<DexPools8285.PoolRow8285>()
        val json = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = DexPools8285.rowOf8285(o, nowMs) ?: continue
            if (r.mint !in want) continue
            rows += r; json[r.pairAddress] = o
        }
        DexPools8285.record8285(rows)
        val answered = HashSet<String>()
        for ((mint, mine) in rows.groupBy { it.mint }) {
            val pick = DexPools8285.choose8285(mine, DexPools8285.chosen8285(mint)) ?: continue
            val o = json[pick.pairAddress] ?: continue
            DexPools8285.markChosen8285(mint, pick.pairAddress)
            // V5.0.8285 FEATURE_ARRIVED_POOL_FLOW(mint)
            try { com.lifecyclebot.engine.truth.PairFlow8169.note8169(mint, com.lifecyclebot.engine.truth.PairFlow8169.fromJson8169(o, nowMs)) } catch (_: Throwable) {}
            if (pick.liquidityUsd.isFinite() && pick.liquidityUsd > 0.0) {
                // V5.0.8285 FEATURE_ARRIVED_LIQUIDITY(mint)
                DexPools8285.liquidityRows8285.incrementAndGet()
            }
            answered += mint
        }
        DexPools8285.batches8285.incrementAndGet()
        val unlisted = want.filter { it !in answered }
        return BatchPairs8285(answered, unlisted, if (answered.isEmpty()) BatchCause8087.NO_DATA else BatchCause8087.PRICED)
    }

    /** V5.0.8285 — a batch answer: mints whose chosen pool's flow was recorded, mints not listed / not asked, and why. */
    data class BatchPairs8285(val answered: Set<String>, val notAnswered: List<String>, val cause: BatchCause8087)

    /** V5.0.8095 — [get], optionally as exit traffic (never refused by our own ApiBackoff lockout). */
    private fun get8095(url: String, host: String = "dexscreener", exit8095: Boolean, code8285: IntArray? = null): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .apply { if (exit8095) header(ExitHttpScope7314.HEADER, "held-marks") }
            .build()
        // V5.0.6495 — never bypass HealthAwareHttp/ApiBackoff with a raw retry.
        // V5.0.7771 — never materialise an unbounded provider body.
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = host).use { resp ->
            if (code8285 != null && code8285.isNotEmpty()) code8285[0] = resp.code   // V5.0.8285 — the provider's own backoff
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val contentLength = body.contentLength()
            if (contentLength > MAX_DEXSCREENER_CONTENT_BYTES_7771) {
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DEXSCREENER_RESPONSE_OVERSIZE_REFUSED_7771")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle("DEXSCREENER_RESPONSE_OVERSIZE_REFUSED_7771", "host=$host bytes=$contentLength")
                } catch (_: Throwable) {}
                return@use null
            }
            val reader = body.charStream()
            val initial = if (contentLength in 1..MAX_DEXSCREENER_RESPONSE_CHARS_7771.toLong()) contentLength.toInt() else 16_384
            val out = StringBuilder(initial.coerceAtMost(MAX_DEXSCREENER_RESPONSE_CHARS_7771))
            val buf = CharArray(8_192)
            var total = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DEXSCREENER_RESPONSE_CHARS_7771) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DEXSCREENER_RESPONSE_STREAM_CAP_7771")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle("DEXSCREENER_RESPONSE_STREAM_CAP_7771", "host=$host chars=$total")
                    } catch (_: Throwable) {}
                    return@use null
                }
                out.append(buf, 0, n)
            }
            out.toString()
        }
    } catch (e: Exception) { null }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/**
 * V5.0.8285 — every DexScreener pool of a mint keeps its identity (pair address, dex, quote, liquidity, flow age), and
 * the pool a mint's flow is read from is chosen deliberately ([choose8285]) and kept stable: the pool already in use
 * while it is still listed with liquidity, else the deepest SOL / stable-quoted pool, else the deepest pool. Bounded.
 */
object DexPools8285 {
    const val BATCH_MAX_8285 = 30
    private const val MAX_MINTS_8285 = 2_000
    private const val WSOL_8285 = "So11111111111111111111111111111111111111112"
    private val STABLE_QUOTES_8285 = setOf(
        WSOL_8285,
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",   // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",   // USDT
    )

    data class PoolRow8285(
        val mint: String, val pairAddress: String, val dexId: String, val quote: String,
        val liquidityUsd: Double, val volumeH24: Double, val priceUsd: Double, val atMs: Long,
    )

    private val pools = java.util.concurrent.ConcurrentHashMap<String, Map<String, PoolRow8285>>()
    private val chosen = java.util.concurrent.ConcurrentHashMap<String, String>()
    val batches8285 = java.util.concurrent.atomic.AtomicLong(0L)
    val liquidityRows8285 = java.util.concurrent.atomic.AtomicLong(0L)
    private val switches8285 = java.util.concurrent.atomic.AtomicLong(0L)

    /** Pure: one DexScreener pair object as a pool row (null without a Solana base mint, a pair address or a price). */
    fun rowOf8285(p: JSONObject, atMs: Long): PoolRow8285? {
        val chain = p.optString("chainId", "")
        if (chain.isNotBlank() && !chain.equals("solana", true)) return null
        val mint = p.optJSONObject("baseToken")?.optString("address", "")?.trim().orEmpty()
        val pair = p.optString("pairAddress", "").trim()
        val px = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
        if (mint.isBlank() || pair.isBlank() || !px.isFinite() || px <= 0.0) return null
        val liq = p.optJSONObject("liquidity")?.takeIf { it.has("usd") }?.optDouble("usd", Double.NaN) ?: Double.NaN
        return PoolRow8285(
            mint, pair, p.optString("dexId", ""), p.optJSONObject("quoteToken")?.optString("address", "")?.trim().orEmpty(),
            liq, p.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0, px, atMs,
        )
    }

    /**
     * Pure: the data pool among [rows] (one mint's pools). [preferred] — the pool already in use — stays while listed with
     * liquidity (a stable identity, no flapping between pools); else the deepest SOL / stable-quoted pool (ties by 24 h
     * volume); else the deepest pool. Null for no rows.
     */
    fun choose8285(rows: List<PoolRow8285>, preferred: String?): PoolRow8285? {
        if (rows.isEmpty()) return null
        if (!preferred.isNullOrBlank()) rows.firstOrNull { it.pairAddress == preferred && it.liquidityUsd.isFinite() && it.liquidityUsd > 0.0 }?.let { return it }
        val depth = compareBy<PoolRow8285>({ if (it.liquidityUsd.isFinite()) it.liquidityUsd else -1.0 }, { it.volumeH24 })
        return rows.filter { it.quote in STABLE_QUOTES_8285 }.maxWithOrNull(depth) ?: rows.maxWithOrNull(depth)
    }

    fun record8285(rows: List<PoolRow8285>) {
        for ((mint, mine) in rows.groupBy { it.mint }) {
            pools.merge(mint, mine.associateBy { it.pairAddress }) { old, new -> old + new }
        }
        if (pools.size > MAX_MINTS_8285) {
            val drop = pools.entries.sortedBy { e -> e.value.values.maxOfOrNull { it.atMs } ?: 0L }.take(MAX_MINTS_8285 / 10)
            for (e in drop) { pools.remove(e.key); chosen.remove(e.key) }
        }
    }

    fun chosen8285(mint: String): String? = chosen[mint]

    fun markChosen8285(mint: String, pair: String) {
        val prior = chosen.put(mint, pair)
        if (prior != null && prior != pair) switches8285.incrementAndGet()
    }

    /** The data pool for [mint] now ([hint] = a pool the caller already trades / reads), or null when none is known. */
    fun dataPool8285(mint: String, hint: String = ""): PoolRow8285? =
        choose8285(pools[mint]?.values?.toList().orEmpty(), hint.ifBlank { chosen[mint] })

    fun poolsOf8285(mint: String): List<PoolRow8285> = pools[mint]?.values?.toList().orEmpty()

    fun statusLine8285(): String =
        "dexPools8285[mints=${pools.size} chosen=${chosen.size} batches=${batches8285.get()} liqRows=${liquidityRows8285.get()} switches=${switches8285.get()}]"

    internal fun resetForTests8285() { pools.clear(); chosen.clear(); batches8285.set(0L); liquidityRows8285.set(0L); switches8285.set(0L) }
}
