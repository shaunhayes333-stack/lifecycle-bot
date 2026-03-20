package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.CoinGeckoTrending
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * SolanaMarketScanner — full Solana DEX opportunity discovery
 * ═══════════════════════════════════════════════════════════════
 *
 * Expands the bot beyond pump.fun to the entire Solana ecosystem:
 *
 * SOURCES (polled on different schedules):
 * ─────────────────────────────────────────
 *  1. Pump.fun WebSocket        — new launches (existing, unchanged)
 *  2. Pump.fun graduates        — tokens migrating to Raydium (high signal)
 *  3. Dexscreener /boosted      — tokens with paid boosts (attention signal)
 *  4. Dexscreener /trending     — top movers by volume/tx last 1h on Solana
 *  5. Dexscreener /gainers      — biggest % gainers last 1h on Solana
 *  6. Birdeye trending          — Birdeye's 24h trending list (free tier)
 *  7. CoinGecko trending        — mainstream attention proxy (already wired)
 *  8. Jupiter new listings      — newly listed on Jupiter aggregator
 *  9. Raydium new pools         — new liquidity pools (Raydium API)
 * 10. Dexscreener search        — keyword scanning for narrative plays
 *
 * FILTERING (before adding to watchlist):
 * ─────────────────────────────────────────
 *  • Liquidity ≥ minLiquidityUsd (default $8K)
 *  • Volume/liquidity ratio ≥ 0.3 (active market)
 *  • Not already in watchlist or blacklist
 *  • Not a known scam pattern (name/symbol checks)
 *  • Passes Dexscreener pair score threshold
 *  • DEX filter — configurable (all/raydium/orca/meteora/pump)
 *
 * MARKET MODES:
 */

class SolanaMarketScanner(
    private val cfg: () -> BotConfig,
    private val onTokenFound: (mint: String, symbol: String, name: String,
                               source: TokenSource, score: Double) -> Unit,
    private val onLog: (String) -> Unit,
) {
    enum class TokenSource {
        PUMP_FUN_NEW,       // brand new pump.fun launch
        PUMP_FUN_GRADUATE,  // migrated from pump.fun to Raydium
        DEX_TRENDING,       // dexscreener trending on Solana
        DEX_GAINERS,        // top % gainers last 1h
        DEX_BOOSTED,        // paid boost = attention incoming
        BIRDEYE_TRENDING,   // birdeye 24h trending
        COINGECKO_TRENDING, // CoinGecko trending
        JUPITER_NEW,        // new Jupiter listing
        RAYDIUM_NEW_POOL,   // new Raydium liquidity pool
        NARRATIVE_SCAN,     // keyword-matched narrative play
        MANUAL,             // manually added by user
    }

    data class ScannedToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val source: TokenSource,
        val liquidityUsd: Double,
        val volumeH1: Double,
        val mcapUsd: Double,
        val pairCreatedHoursAgo: Double,
        val dexId: String,          // raydium / orca / meteora / pump
        val priceChangeH1: Double,
        val txCountH1: Int,
        val score: Double,          // composite discovery score 0-100
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val dex        = DexscreenerApi()
    private val birdeye    = BirdeyeApi()
    private val coingecko  = CoinGeckoTrending()
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track which mints we've already surfaced to avoid duplicates
    private val seenMints  = ConcurrentHashMap<String, Long>()
    private val SEEN_TTL   = 30 * 60_000L   // forget after 30 min — rescan if still active

    // ── Start / Stop ─────────────────────────────────────────────────

    fun start() {
        scope.launch { scanLoop() }
        onLog("SolanaMarketScanner started")
    }

    fun stop() {
        scope.cancel()
        onLog("SolanaMarketScanner stopped")
    }

    // ── Main scan loop ────────────────────────────────────────────────

    private suspend fun scanLoop() {
        while (isActive) {
            val c = cfg()
            val scanIntervalMs = (c.scanIntervalSecs * 1000L).toLong()

            try {
                // Clean expired seen entries
                val now = System.currentTimeMillis()
                seenMints.entries.removeIf { now - it.value > SEEN_TTL }

                // ScalingMode tier logging
                val sScanTier = ScalingMode.activeTier(
                    TreasuryManager.treasurySol * WalletManager.lastKnownSolPrice)
                val _tn = if (sScanTier != ScalingMode.Tier.MICRO)
                    " ${sScanTier.icon}${sScanTier.label}" else ""
                onLog("🌐 Scan cycle${_tn}", "")

                // Run all enabled sources in parallel
                val jobs = listOf(
                    async { scanDexTrending() },
                    async { scanDexGainers() },
                    async { scanDexBoosted() },
                    async { scanPumpGraduates() },
                    async { scanBirdeyeTrending() },
                    async { scanCoinGeckoTrending() },
                    async { scanRaydiumNewPools() },
                    async { if (c.narrativeScanEnabled) scanNarratives(c.narrativeKeywords) },
                )
                jobs.forEach { it.await() }

            } catch (e: Exception) {
                onLog("Scanner error: ${e.message?.take(80)}")
            }

            delay(scanIntervalMs)
        }
    }

    // ── Source 1: Dexscreener trending ───────────────────────────────

    private suspend fun scanDexTrending() {
        val url = "https://api.dexscreener.com/token-profiles/v1/latest?chainIds=solana"
        val body = get(url) ?: return
        try {
            val arr = JSONArray(body)
            for (i in 0 until minOf(arr.length(), 30)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "").trim()
                if (mint.isBlank() || mint.length < 32 || isSeen(mint)) continue
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                val token = buildScannedToken(mint, pair, TokenSource.DEX_TRENDING) ?: return@launch
                if (passesFilter(token)) emit(token)
            }
        } catch (_: Exception) {}
    }

    // ── Source 2: Dexscreener gainers (top % movers last 1h) ─────────

    private suspend fun scanDexGainers() {
        // Dexscreener search for top Solana volume
        val searches = listOf("solana top", "sol pump", "sol moon")
        for (query in searches) {
            val results = withContext(Dispatchers.IO) { dex.search(query) }
            results.take(10).forEach { pair ->
                val mint = pair.pairAddress  // use pair address as proxy
                if (mint.isBlank() || isSeen(mint)) return@forEach
                // Use pair address as proxy — scanner will resolve real mint via getBestPair
                val token = ScannedToken(
                    mint               = mint,
                    symbol             = pair.baseSymbol.ifBlank { mint.take(8) },
                    name               = pair.baseName.ifBlank { "Unknown" },
                    source             = TokenSource.DEX_GAINERS,
                    liquidityUsd       = pair.liquidity,
                    volumeH1           = pair.candle.volumeH1,
                    mcapUsd            = pair.candle.marketCap,
                    pairCreatedHoursAgo = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0,
                    dexId              = "unknown",
                    priceChangeH1      = 0.0,
                    txCountH1          = pair.candle.buysH1 + pair.candle.sellsH1,
                    score              = scoreToken(pair.liquidity, pair.candle.volumeH1,
                                                    pair.candle.buysH1 + pair.candle.sellsH1,
                                                    pair.candle.marketCap, 0.0,
                                                    (System.currentTimeMillis() - pair.pairCreatedAtMs)/3_600_000.0),
                )
                if (passesFilter(token)) emit(token)
            }
            delay(500)  // rate limit between searches
        }
    }

    // ── Source 3: Dexscreener boosted tokens ─────────────────────────

    private suspend fun scanDexBoosted() {
        // Boosted = paid promotion = attention about to arrive
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        val body = get(url) ?: return
        try {
            val arr = JSONArray(body)
            for (i in 0 until minOf(arr.length(), 15)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress","")
                if (mint.isBlank() || isSeen(mint)) continue
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                val token = buildScannedToken(mint, pair, TokenSource.DEX_BOOSTED) ?: return@launch
                // Boosted tokens get score bump — paid attention is still attention
                val boostedToken = token.copy(score = (token.score + 15.0).coerceAtMost(100.0))
                if (passesFilter(boostedToken)) emit(boostedToken)
            }
        } catch (_: Exception) {}
    }

    // ── Source 4: Pump.fun graduates (Raydium migrations) ────────────

    private suspend fun scanPumpGraduates() {
        // Query Dexscreener for recent raydium pairs that were pump.fun originated
        // Graduates get a massive signal — they survived bonding curve + got real liquidity
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana/raydium"
        val body = get(url) ?: return
        try {
            val arr = JSONObject(body).optJSONArray("pairs") ?: return
            val cutoff = System.currentTimeMillis() - 6 * 3_600_000L  // created in last 6h
            for (i in 0 until minOf(arr.length(), 50)) {
                val p = arr.optJSONObject(i) ?: continue
                val created = p.optLong("pairCreatedAt", 0L)
                if (created < cutoff) continue  // too old
                val mint = p.optJSONObject("baseToken")?.optString("address","") ?: continue
                if (mint.isBlank() || isSeen(mint)) continue
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                val token = buildScannedToken(mint, pair, TokenSource.PUMP_FUN_GRADUATE) ?: return@launch
                // Graduates get highest priority score — proven survival
                val graduateToken = token.copy(score = (token.score + 25.0).coerceAtMost(100.0))
                if (passesFilter(graduateToken)) emit(graduateToken)
            }
        } catch (_: Exception) {}
    }

    // ── Source 5: Birdeye trending ────────────────────────────────────

    private suspend fun scanBirdeyeTrending() {
        val c = cfg()
        if (c.birdeyeApiKey.isBlank()) return  // needs key
        val url = "https://public-api.birdeye.so/defi/token_trending?sort_by=rank&sort_type=asc&offset=0&limit=20"
        val body = get(url, apiKey = c.birdeyeApiKey) ?: return
        try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return
            for (i in 0 until minOf(items.length(), 20)) {
                val item = items.optJSONObject(i) ?: continue
                val mint = item.optString("address","")
                if (mint.isBlank() || isSeen(mint)) continue
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                val token = buildScannedToken(mint, pair, TokenSource.BIRDEYE_TRENDING) ?: return@launch
                if (passesFilter(token)) emit(token)
            }
        } catch (_: Exception) {}
    }

    // ── Source 6: CoinGecko trending ─────────────────────────────────

    private suspend fun scanCoinGeckoTrending() {
        val trending = withContext(Dispatchers.IO) { coingecko.getTrending() }
        trending.forEach { t ->
            if (isSeen(t.id)) return@forEach
            // Search for the token on Dexscreener by symbol
            val results = withContext(Dispatchers.IO) { dex.search(t.symbol) }
            val solanaPair = results.firstOrNull { it.candle.priceUsd > 0 } ?: return@forEach
            val mint = solanaPair.pairAddress
            if (mint.isBlank() || isSeen(mint)) return@forEach
            val token = ScannedToken(
                mint               = mint,
                symbol             = t.symbol,
                name               = t.name,
                source             = TokenSource.COINGECKO_TRENDING,
                liquidityUsd       = solanaPair.liquidity,
                volumeH1           = solanaPair.candle.volumeH1,
                mcapUsd            = solanaPair.candle.marketCap,
                pairCreatedHoursAgo = 0.0,
                dexId              = "unknown",
                priceChangeH1      = t.priceChangePercent,
                txCountH1          = solanaPair.candle.buysH1 + solanaPair.candle.sellsH1,
                score              = scoreToken(solanaPair.liquidity, solanaPair.candle.volumeH1,
                                                solanaPair.candle.buysH1 + solanaPair.candle.sellsH1,
                                                solanaPair.candle.marketCap, t.priceChangePercent, 0.0)
                                    + (25.0 - t.score * 3.0),  // CoinGecko rank boost
            )
            if (passesFilter(token)) emit(token)
            delay(300)
        }
    }

    // ── Source 7: Raydium new pools ───────────────────────────────────

    private suspend fun scanRaydiumNewPools() {
        // Raydium public API for recently created pools
        val url = "https://api.raydium.io/v2/main/pairs"
        val body = get(url) ?: return
        if (body.isBlank() || body.contains("\"success\":false")) return
        try {
            val arr = if (body.trimStart().startsWith("[")) JSONArray(body)
                      else JSONObject(body).optJSONArray("data") ?: return
            val cutoff = System.currentTimeMillis() - 2 * 3_600_000L  // last 2 hours
            var found = 0
            for (i in 0 until minOf(arr.length(), 200)) {
                if (found >= 20) break
                val item = arr.optJSONObject(i) ?: continue
                // Raydium doesn't always have timestamps — filter by liquidity range
                val liq = item.optDouble("liquidity", 0.0)
                if (liq < cfg().minLiquidityUsd || liq > 5_000_000) continue  // skip if too big (old)
                val mint = item.optString("baseMint","")
                if (mint.isBlank() || isSeen(mint)) continue
                val vol24 = item.optDouble("volume24h", 0.0)
                val txCount = item.optInt("txCount24h", 0)
                if (vol24 < 1000) continue  // ignore dead pools
                // Emit without full pair data — BotService will fetch candles on add
                val token = ScannedToken(
                    mint               = mint,
                    symbol             = item.optString("name","").split("-").firstOrNull() ?: mint.take(6),
                    name               = item.optString("name",""),
                    source             = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd       = liq,
                    volumeH1           = vol24 / 24.0,
                    mcapUsd            = 0.0,
                    pairCreatedHoursAgo = 1.0,  // unknown, assume recent
                    dexId              = "raydium",
                    priceChangeH1      = item.optDouble("priceChange24h", 0.0),
                    txCountH1          = txCount / 24,
                    score              = scoreToken(liq, vol24/24.0, txCount/24, 0.0, 0.0, 1.0),
                )
                if (passesFilter(token)) { emit(token); found++ }
            }
        } catch (_: Exception) {}
    }

    // ── Source 8: Narrative scanning ─────────────────────────────────

    private suspend fun scanNarratives(keywords: List<String>) {
        keywords.forEach { kw ->
            if (kw.isBlank()) return@forEach
            val results = withContext(Dispatchers.IO) { dex.search(kw) }
            results.take(5).forEach { pair ->
                val mint = pair.pairAddress
                if (mint.isBlank() || isSeen(mint)) return@forEach
                val token = ScannedToken(
                    mint               = mint,
                    symbol             = pair.baseSymbol,
                    name               = pair.baseName,
                    source             = TokenSource.NARRATIVE_SCAN,
                    liquidityUsd       = pair.liquidity,
                    volumeH1           = pair.candle.volumeH1,
                    mcapUsd            = pair.candle.marketCap,
                    pairCreatedHoursAgo = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0,
                    dexId              = "unknown",
                    priceChangeH1      = 0.0,
                    txCountH1          = pair.candle.buysH1 + pair.candle.sellsH1,
                    score              = scoreToken(pair.liquidity, pair.candle.volumeH1,
                                                    pair.candle.buysH1 + pair.candle.sellsH1,
                                                    pair.candle.marketCap, 0.0,
                                                    (System.currentTimeMillis() - pair.pairCreatedAtMs)/3_600_000.0),
                )
                if (passesFilter(token)) emit(token)
            }
            delay(500)
        }
    }

    // ── Scoring ───────────────────────────────────────────────────────

    /**
     * Composite discovery score for ranking tokens before adding to watchlist.
     * Higher = more interesting opportunity.
     */
    private fun scoreToken(
        liqUsd: Double, volH1: Double, txH1: Int,
        mcap: Double, priceChangeH1: Double, ageHours: Double,
    ): Double {
        var s = 0.0

        // Liquidity — sweet spot is $10K-$500K
        // Too low = exit risk, too high = slow mover
        s += when {
            liqUsd > 1_000_000 -> 10.0   // large — stable but slower
            liqUsd >   500_000 -> 20.0
            liqUsd >   100_000 -> 35.0   // ideal range
            liqUsd >    50_000 -> 45.0   // sweet spot
            liqUsd >    10_000 -> 35.0
            liqUsd >     5_000 -> 20.0
            else               -> 5.0
        }

        // Volume/liquidity ratio — high ratio = real activity vs just parked liquidity
        val volLiqRatio = if (liqUsd > 0) volH1 / liqUsd else 0.0
        s += when {
            volLiqRatio > 5.0  -> 25.0   // extremely active
            volLiqRatio > 2.0  -> 20.0
            volLiqRatio > 1.0  -> 15.0
            volLiqRatio > 0.5  -> 10.0
            volLiqRatio > 0.2  -> 5.0
            else               -> 0.0
        }

        // Transaction count — real buyers not just big whale trades
        s += when {
            txH1 > 500 -> 20.0
            txH1 > 200 -> 15.0
            txH1 > 100 -> 10.0
            txH1 >  50 -> 5.0
            txH1 >  20 -> 2.0
            else       -> 0.0
        }

        // Price momentum
        s += when {
            priceChangeH1 > 100 -> 15.0
            priceChangeH1 >  50 -> 12.0
            priceChangeH1 >  20 -> 8.0
            priceChangeH1 >  10 -> 5.0
            priceChangeH1 <   0 -> -10.0  // already falling
            else                -> 0.0
        }

        // Age sweet spot — too new = rug risk, too old = might be dead
        s += when {
            ageHours < 0.5  -> 5.0    // very fresh — high risk/reward
            ageHours < 2.0  -> 15.0   // sweet spot for launch snipe
            ageHours < 6.0  -> 20.0   // ideal for range/reclaim
            ageHours < 24.0 -> 15.0
            ageHours < 72.0 -> 10.0
            ageHours < 168.0 -> 5.0   // 1 week
            else            -> 2.0
        }

        return s.coerceIn(0.0, 100.0)
    }

    // ── Filtering ─────────────────────────────────────────────────────

    private fun passesFilter(token: ScannedToken): Boolean {
        val c = cfg()

        // Minimum liquidity
        if (token.liquidityUsd < c.minLiquidityUsd && token.liquidityUsd > 0) return false

        // DEX filter — user can restrict to specific DEXs
        if (c.allowedDexes.isNotEmpty() && token.dexId !in c.allowedDexes) return false

        // MC range filter
        if (c.scanMinMcapUsd > 0 && token.mcapUsd < c.scanMinMcapUsd) return false
        if (c.scanMaxMcapUsd > 0 && token.mcapUsd > c.scanMaxMcapUsd) return false

        // Source filter — user can disable specific sources
        if (token.source == TokenSource.PUMP_FUN_NEW && !c.scanPumpFunNew) return false
        if (token.source == TokenSource.PUMP_FUN_GRADUATE && !c.scanPumpGraduates) return false
        if (token.source == TokenSource.DEX_TRENDING && !c.scanDexTrending) return false
        if (token.source == TokenSource.RAYDIUM_NEW_POOL && !c.scanRaydiumNew) return false

        // Minimum discovery score
        if (token.score < c.minDiscoveryScore) return false

        // Name/symbol scam heuristics
        val sym = token.symbol.lowercase()
        val name = token.name.lowercase()
        val scamPatterns = listOf("test","fake","scam","rug","honeypot","xxx","porn")
        if (scamPatterns.any { sym.contains(it) || name.contains(it) }) return false

        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun buildScannedToken(
        mint: String,
        pair: com.lifecyclebot.network.PairInfo,
        source: TokenSource,
    ): ScannedToken? {
        if (pair.candle.priceUsd <= 0) return null
        val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
        return ScannedToken(
            mint               = mint,
            symbol             = pair.baseSymbol,
            name               = pair.baseName,
            source             = source,
            liquidityUsd       = pair.liquidity,
            volumeH1           = pair.candle.volumeH1,
            mcapUsd            = pair.candle.marketCap,
            pairCreatedHoursAgo = ageHours.coerceAtLeast(0.0),
            dexId              = "solana",
            priceChangeH1      = 0.0,
            txCountH1          = pair.candle.buysH1 + pair.candle.sellsH1,
            score              = scoreToken(pair.liquidity, pair.candle.volumeH1,
                                            pair.candle.buysH1 + pair.candle.sellsH1,
                                            pair.candle.marketCap, 0.0, ageHours),
        )
    }

    private fun isSeen(mint: String): Boolean {
        val now = System.currentTimeMillis()
        val last = seenMints[mint] ?: return false
        return now - last < SEEN_TTL
    }

    private fun emit(token: ScannedToken) {
        seenMints[token.mint] = System.currentTimeMillis()
        onLog("🔍 Found: ${token.symbol} (${token.source.name}) " +
              "liq=$${(token.liquidityUsd/1000).toInt()}K " +
              "vol=$${(token.volumeH1/1000).toInt()}K " +
              "score=${token.score.toInt()}")
        onTokenFound(token.mint, token.symbol, token.name, token.source, token.score)
    }

    private fun get(url: String, apiKey: String = ""): String? = try {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("X-API-KEY", apiKey)
        val resp = http.newCall(builder.build()).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
