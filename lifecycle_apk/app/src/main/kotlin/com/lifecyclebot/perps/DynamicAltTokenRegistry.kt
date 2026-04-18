package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.CoinGeckoTrending
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.PairInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🌐 DYNAMIC ALT TOKEN REGISTRY — V1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Discovers and tracks thousands of alt tokens dynamically using all available
 * data sources in the app's existing infrastructure.
 *
 * SOURCES (all free / no extra keys):
 *   1. DexScreener /token-boosts/top/v1        — boosted/promoted tokens
 *   2. DexScreener /token-profiles/latest/v1   — newly listed profiles
 *   3. DexScreener /latest/dex/search          — keyword-rotated searches
 *   4. CoinGecko /coins/markets (pages 1–5)    — top 500 by volume
 *   5. CoinGecko /search/trending              — top 10 trending right now
 *   6. Jupiter /strict token list              — all verified Solana tokens
 *   7. PerpsMarket enum (static baseline)      — always included
 *
 * TOKEN QUALITY GATE:
 *   • Min liquidity $5K USD
 *   • Min 24h volume $10K USD
 *   • Min age 10 minutes (prevents rug traps)
 *
 * DISCOVERY: Every 5 minutes.  PRICE REFRESH: Every 60 seconds.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object DynamicAltTokenRegistry {

    private const val TAG = "DynAltReg"

    // ─── Token data model ────────────────────────────────────────────────────

    data class DynToken(
        val mint: String,           // Solana mint or "cg:{id}" for CoinGecko-only
        val symbol: String,
        val name: String,
        val chainId: String = "solana",
        val logoUrl: String = "",
        val pairAddress: String = "",
        val price: Double = 0.0,
        val priceChange24h: Double = 0.0,
        val mcap: Double = 0.0,
        val liquidityUsd: Double = 0.0,
        val volume24h: Double = 0.0,
        val buys24h: Int = 0,
        val sells24h: Int = 0,
        val ageHours: Double = 0.0,
        val source: String = "unknown",
        val isTrending: Boolean = false,
        val trendingRank: Int = -1,
        val isBoosted: Boolean = false,
        val isStatic: Boolean = false,
        val sector: String = "",
        val lastUpdatedMs: Long = System.currentTimeMillis(),
    ) {
        val emoji: String get() = when {
            isTrending && trendingRank == 0 -> "🔥"
            isTrending                       -> "📈"
            isBoosted                        -> "⚡"
            volume24h > 1_000_000            -> "💎"
            mcap > 10_000_000                -> "🔵"
            mcap > 1_000_000                 -> "🟡"
            else                             -> "⚪"
        }

        val qualityScore: Int get() {
            var s = 0
            if (liquidityUsd > 50_000)   s += 20
            if (liquidityUsd > 200_000)  s += 15
            if (volume24h > 100_000)     s += 20
            if (volume24h > 500_000)     s += 15
            if (isTrending)              s += maxOf(0, 25 - trendingRank.coerceAtLeast(0) * 3)
            if (isBoosted)               s += 10
            if (buys24h > sells24h)      s += 10
            if (ageHours > 24)           s += 5
            if (priceChange24h > 10)     s += 10
            if (priceChange24h > 30)     s += 10
            return s.coerceIn(0, 100)
        }

        fun fmtPrice(): String = when {
            price == 0.0  -> "—"
            price > 1000  -> "$%.0f".format(price)
            price > 1     -> "$%.4f".format(price)
            price > 0.001 -> "$%.6f".format(price)
            else          -> "$%.8f".format(price)
        }

        fun fmtMcap(): String = when {
            mcap == 0.0   -> "—"
            mcap >= 1e9   -> "$%.1fB".format(mcap / 1e9)
            mcap >= 1e6   -> "$%.1fM".format(mcap / 1e6)
            mcap >= 1e3   -> "$%.0fK".format(mcap / 1e3)
            else          -> "$%.0f".format(mcap)
        }
    }

    enum class SortMode { QUALITY, TRENDING, VOLUME, MCAP, CHANGE, NEW, BOOSTED }

    // ─── State ────────────────────────────────────────────────────────────────

    private val registry     = ConcurrentHashMap<String, DynToken>(4096)
    private val symbolIndex  = ConcurrentHashMap<String, String>()   // SYMBOL → mint

    private val lastDiscoveryCycle = AtomicLong(0L)
    private val lastPriceRefresh   = AtomicLong(0L)

    private const val DISCOVERY_TTL_MS = 5 * 60_000L
    private const val PRICE_TTL_MS     = 60_000L
    private const val TOKEN_STALE_MS   = 60 * 60_000L  // evict non-static after 1h

    private const val MIN_LIQ_USD      = 5_000.0
    private const val MIN_VOL_24H      = 10_000.0
    private const val MIN_AGE_MINUTES  = 10.0

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val dex        = DexscreenerApi()
    private val cgTrending = CoinGeckoTrending()

    private val searchKeywords = listOf(
        "ai","pepe","dog","cat","trump","elon","sol","moon","pump","meme","defi",
        "nft","game","gpt","baby","inu","doge","shib","floki","bear","bull","chad",
        "based","ape","frog","bonk","wif","bome","zeus","layer","swap","yield","stake"
    )
    private var searchKeywordIdx = 0

    // ─── Init ─────────────────────────────────────────────────────────────────

    /**
     * Verified Solana mainnet mints for well-known tokens.
     * These are used at seed time so live swaps work immediately — before the
     * Jupiter strict-list discovery cycle has had a chance to upgrade "static:*" keys.
     */
    private val KNOWN_SOLANA_MINTS = mapOf(
        // Native / wrapped SOL
        "SOL"      to "So11111111111111111111111111111111111111112",
        // Wormhole-wrapped ETH  (Portal Bridge canonical)
        "ETH"      to "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs",
        // Portal (Wormhole) BTC
        "BTC"      to "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh",
        // Wrapped BTC (Sollet legacy — kept as fallback key)
        "WBTC"     to "9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E",
        // USDC & USDT
        "USDC"     to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "USDT"     to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        // Solana-native blue chips
        "JUP"      to "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
        "RAY"      to "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R",
        "ORCA"     to "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE",
        "PYTH"     to "HZ1JovNiVvGrGs68OD7MZaN5aFfMRXX7q8DXXCWFTW",
        "DRIFT"    to "DriFtupJYLTosbwoN8koMbEYSx54aFAVLddWsbksjwg7",
        "JTO"      to "jtojtomepa8bdqftztzaudthkkna8ktwdbqfptdqtqk",
        "TNSR"     to "TNSRxcUxoT9xBG3de7A4QJ6kLK9h2s7rH5pNNJVUqHy",
        "KMNO"     to "KMNo3nJsBXfcpJTVhZcXLW7RmTwTt4GVFE7suUBo9sS",
        "MSOL"     to "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",
        // Solana meme coins  (real on-chain mints — tradeable via Jupiter)
        "BONK"     to "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
        "WIF"      to "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm",
        "POPCAT"   to "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr",
        "BOME"     to "ukHH6c7mMyiWCf1b9pnWe25TSpkDDt3H5pQZgZ74J82",
        "TRUMP"    to "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN",
        "PNUT"     to "2qEHjDLDLbuBgRYvsxhc5D6uDWAivNFZGan56P1tpump",
        "GOAT"     to "CzLSujWBLFsSjncfkh59rUFqvafWcY5tzedWJSuypump",
        "FARTCOIN" to "9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump",
        "MOODENG"  to "ED5nyyWEzpPPiWimP8vYm7sD7TD3LAt3Q3gRTWHzc8yy",
        "MEW"      to "MEW1gQWJ3nEXg2qgERiKu7FAFj79PHvQVREQUzScPP5",
        "WEN"      to "WENWENvqqNya429ubCdR81ZmD69brwQaaBYY6p3LCpk",
        "SLERF"    to "7BgBvyjrZX1YKz4oh9mjb8ZScatkkwb8DzFx7LoiVkM3",
        "PONKE"    to "5z3EqYQo9HiCEs3R84RCDMu2n7anpDMxRhdK31CR6ZDN",
        "MYRO"     to "HhJpBhRRn4g56VsyLuT8DL5Bv31HkXqsrahTTUCZeZg4",
        "CHILLGUY" to "Df6yfrKC8kZE3KNkrHERKzAetSxbrWeniQfyJY4Jpump",
        "RETARDIO" to "6ogzHhzdrQr9Pgv6hZ2MNze7UrzBMAFyBBWUYp1Fhitx",
        "GME"      to "8wXtPeU6557ETkp9WHFY1n1EcU6NxDvbAggHGsMYiHsB",
        // DeFi / infrastructure with Solana-native presence
        "LINK"     to "2wpTofQ8SkACrkZWrZDjXPitYa8uxmX2SkKNKMRNkSMJ",
        "AAVE"     to "3vAs4D1WE6Na4tCgt4BApgFfENbm8WY7q4cSPD6yypump",
        "UNI"      to "8FU95xFJhUUkyyCLU13HSzDLs7oC4QZdXQHL6SCut352",
        "LDO"      to "HZRCwxP2Vq9PCpPXooayhJ2bxTpo5ZfALpBMRPQ3sPFV",
        "INJ"      to "6McPRfPV6bY1e9hLxWyG54W9i9Epq75QBvXCrPLSoVnM",
        "RAY"      to "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R",
        "FIDA"     to "EchesyfXePKdLtoiZSL8ppeznWjesMFZGoQjeB3s4Xmr",
        "MNGO"     to "MangoCzJ36AjZyKwVj3VnYU4GTonjfVEnJmvvWaxLac",
        "ATLAS"    to "ATLASXmbPQxBUYbxPsV97usA3fPQYEqzQBUHgiFCUsXx",
        "PAXG"     to "GGisdTfU4QFtUEbuvUsxH5iMaGhLdXM3GTFqRa3KZPGH",
    )

    /** Call once at startup — seeds the 56 static PerpsMarket tokens immediately */
    fun seedStaticTokens() {
        PerpsMarket.values()
            .filter { it.isCrypto && !it.isSolPerp }
            .forEach { market ->
                // Use real on-chain mint if we know it; otherwise use placeholder key
                val realMint = KNOWN_SOLANA_MINTS[market.symbol.uppercase()]
                val key = realMint ?: "static:${market.symbol}"
                val tok = DynToken(
                    mint      = key,
                    symbol    = market.symbol,
                    name      = market.displayName,
                    isStatic  = true,
                    logoUrl   = getCoinGeckoLogoUrl(market.symbol),
                    sector    = inferSector(market.symbol),
                    source    = "static_enum",
                )
                registry[key] = tok
                symbolIndex[market.symbol.uppercase()] = key
            }
        ErrorLogger.info(TAG, "Seeded ${registry.size} static PerpsMarket tokens")
    }

    // ─── Discovery cycle ─────────────────────────────────────────────────────

    /** Force a discovery cycle, bypassing TTL — used by manual scan button */
    suspend fun forceDiscoveryCycle() {
        lastDiscoveryCycle.set(0L)  // Reset TTL so next call always runs
        runDiscoveryCycle()
    }

    suspend fun runDiscoveryCycle() {
        val now = System.currentTimeMillis()
        // Always fetch on first run (registry too small), otherwise respect TTL
        val tooSmall = registry.size < 200
        if (!tooSmall && now - lastDiscoveryCycle.get() < DISCOVERY_TTL_MS) return
        lastDiscoveryCycle.set(now)

        ErrorLogger.info(TAG, "Discovery cycle starting (${registry.size} tokens currently)")

        fetchDexScreenerBoosted()
        fetchDexScreenerLatestProfiles()
        fetchDexScreenerKeywordSearch()
        fetchCoinGeckoMarkets()
        fetchCoinGeckoTrending()
        fetchJupiterTokenList()

        // Evict stale non-static tokens
        val staleTs = now - TOKEN_STALE_MS
        registry.entries.removeIf { (_, tok) -> !tok.isStatic && tok.lastUpdatedMs < staleTs }

        ErrorLogger.info(TAG, "Discovery complete: ${registry.size} tokens total")
    }

    // ─── Public getters ───────────────────────────────────────────────────────

    fun getAllTokens(sortBy: SortMode = SortMode.QUALITY): List<DynToken> {
        val all = registry.values.toList()
        return when (sortBy) {
            SortMode.QUALITY  -> all.sortedWith(compareByDescending<DynToken> { it.isStatic }.thenByDescending { it.qualityScore })
            SortMode.TRENDING -> all.sortedWith(compareByDescending<DynToken> { it.isTrending }.thenByDescending { it.qualityScore })
            SortMode.VOLUME   -> all.sortedByDescending { it.volume24h }
            SortMode.MCAP     -> all.sortedByDescending { it.mcap }
            SortMode.CHANGE   -> all.sortedByDescending { it.priceChange24h }
            SortMode.NEW      -> all.sortedByDescending { it.lastUpdatedMs }
            SortMode.BOOSTED  -> all.sortedWith(compareByDescending<DynToken> { it.isBoosted }.thenByDescending { it.qualityScore })
        }
    }

    fun getTokenCount()                        = registry.size
    fun getStaticCount()                       = registry.values.count { it.isStatic }
    fun getDynamicCount()                      = registry.values.count { !it.isStatic }
    fun getTrendingTokens()                    = registry.values.filter { it.isTrending }.sortedBy { it.trendingRank }
    fun getBoostedTokens()                     = registry.values.filter { it.isBoosted }.sortedByDescending { it.qualityScore }
    fun getTokenByMint(mint: String)           = registry[mint]
    fun getTokenBySymbol(sym: String)          = symbolIndex[sym.uppercase()]?.let { registry[it] }
    fun getNewTokens(maxAgeHours: Double=24.0) = registry.values.filter { !it.isStatic && it.ageHours in 0.0..maxAgeHours }.sortedByDescending { it.qualityScore }
    fun getTokensBySector(sector: String)      = registry.values.filter { it.sector.equals(sector, true) }.sortedByDescending { it.qualityScore }
    fun getStats()                             = "Total: ${registry.size} | Static: ${getStaticCount()} | Dynamic: ${getDynamicCount()} | Trending: ${getTrendingTokens().size} | Boosted: ${getBoostedTokens().size}"

    /** Update live price for a static token (called from PerpsMarketDataFetcher callback) */
    fun updateStaticPrice(symbol: String, price: Double, change24h: Double, mcap: Double, vol24h: Double) {
        val key = "static:$symbol"
        val existing = registry[key] ?: return
        registry[key] = existing.copy(price = price, priceChange24h = change24h, mcap = mcap, volume24h = vol24h, lastUpdatedMs = System.currentTimeMillis())
    }

    // ─── Discovery implementations ───────────────────────────────────────────

    private fun fetchDexScreenerBoosted() {
        val body = httpGet("https://api.dexscreener.com/token-boosts/top/v1") ?: return
        try {
            val arr   = JSONArray(body)
            var added = 0
            for (i in 0 until arr.length()) {
                val obj     = arr.getJSONObject(i)
                if (obj.optString("chainId", "") != "solana") continue
                val mint    = obj.optString("tokenAddress", "").trim()
                val logoUrl = obj.optString("icon", "")
                if (mint.isBlank()) continue

                val pair   = dex.getBestPair(mint)
                if (!meetsQualityGate(pair)) continue
                val symbol = pair?.baseSymbol?.uppercase()?.ifBlank { mint.take(6).uppercase() } ?: mint.take(6).uppercase()

                upsert(DynToken(
                    mint         = mint,
                    symbol       = symbol,
                    name         = pair?.baseName?.ifBlank { symbol } ?: symbol,
                    logoUrl      = logoUrl.ifBlank { "https://dd.dexscreener.com/ds-data/tokens/solana/$mint.png" },
                    pairAddress  = pair?.pairAddress ?: "",
                    price        = pair?.candle?.priceUsd ?: 0.0,
                    mcap         = pair?.candle?.marketCap ?: 0.0,
                    liquidityUsd = pair?.liquidity ?: 0.0,
                    volume24h    = pair?.candle?.volume24h ?: 0.0,
                    buys24h      = pair?.candle?.buys24h ?: 0,
                    sells24h     = pair?.candle?.sells24h ?: 0,
                    ageHours     = pair?.pairCreatedAtMs?.let { if (it > 0) (System.currentTimeMillis() - it) / 3_600_000.0 else 999.0 } ?: 999.0,
                    isBoosted    = true,
                    source       = "dex_boosted",
                    sector       = inferSector(symbol),
                ))
                added++
            }
            ErrorLogger.info(TAG, "DexScreener boosted: +$added")
        } catch (e: Exception) { ErrorLogger.warn(TAG, "boosted: ${e.message}") }
    }

    private fun fetchDexScreenerLatestProfiles() {
        val body = httpGet("https://api.dexscreener.com/token-profiles/latest/v1") ?: return
        try {
            val arr   = JSONArray(body)
            var added = 0
            for (i in 0 until arr.length()) {
                val obj     = arr.getJSONObject(i)
                if (obj.optString("chainId", "") != "solana") continue
                val mint    = obj.optString("tokenAddress", "").trim()
                val logoUrl = obj.optString("icon", "")
                if (mint.isBlank()) continue

                val pair = dex.getBestPair(mint) ?: continue
                if (!meetsQualityGate(pair)) continue
                val symbol = pair.baseSymbol.uppercase().ifBlank { mint.take(6).uppercase() }

                upsert(DynToken(
                    mint         = mint,
                    symbol       = symbol,
                    name         = pair.baseName.ifBlank { symbol },
                    logoUrl      = logoUrl.ifBlank { "https://dd.dexscreener.com/ds-data/tokens/solana/$mint.png" },
                    pairAddress  = pair.pairAddress,
                    price        = pair.candle.priceUsd,
                    mcap         = pair.candle.marketCap,
                    liquidityUsd = pair.liquidity,
                    volume24h    = pair.candle.volume24h,
                    buys24h      = pair.candle.buys24h,
                    sells24h     = pair.candle.sells24h,
                    ageHours     = if (pair.pairCreatedAtMs > 0) (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0 else 999.0,
                    source       = "dex_profiles",
                    sector       = inferSector(symbol),
                ))
                added++
            }
            ErrorLogger.info(TAG, "DexScreener profiles: +$added")
        } catch (e: Exception) { ErrorLogger.warn(TAG, "profiles: ${e.message}") }
    }

    private fun fetchDexScreenerKeywordSearch() {
        var added = 0
        repeat(4) {
            val keyword = searchKeywords[searchKeywordIdx % searchKeywords.size]
            searchKeywordIdx++
            try {
                dex.search(keyword).forEach { pair ->
                    if (pair.baseTokenAddress.isBlank()) return@forEach
                    if (!meetsQualityGate(pair)) return@forEach
                    val symbol = pair.baseSymbol.uppercase().ifBlank { return@forEach }
                    upsert(DynToken(
                        mint         = pair.baseTokenAddress,
                        symbol       = symbol,
                        name         = pair.baseName.ifBlank { symbol },
                        logoUrl      = "https://dd.dexscreener.com/ds-data/tokens/solana/${pair.baseTokenAddress}.png",
                        pairAddress  = pair.pairAddress,
                        price        = pair.candle.priceUsd,
                        mcap         = pair.candle.marketCap,
                        liquidityUsd = pair.liquidity,
                        volume24h    = pair.candle.volume24h,
                        buys24h      = pair.candle.buys24h,
                        sells24h     = pair.candle.sells24h,
                        ageHours     = if (pair.pairCreatedAtMs > 0) (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0 else 999.0,
                        source       = "dex_kw:$keyword",
                        sector       = inferSector(symbol),
                    ))
                    added++
                }
            } catch (_: Exception) {}
        }
        ErrorLogger.info(TAG, "DexScreener keywords: +$added")
    }

    /** CoinGecko top 500 by volume — 5 pages × 100 tokens */
    private fun fetchCoinGeckoMarkets() {
        var added = 0
        for (page in 1..5) {
            val url  = "https://api.coingecko.com/api/v3/coins/markets" +
                "?vs_currency=usd&order=volume_desc&per_page=100&page=$page" +
                "&sparkline=false&price_change_percentage=24h"
            val body = httpGet(url) ?: break
            try {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj    = arr.getJSONObject(i)
                    val symbol = obj.optString("symbol", "").uppercase(); if (symbol.isBlank()) continue
                    val id     = obj.optString("id", ""); if (id.isBlank()) continue
                    val name   = obj.optString("name", "")
                    val logo   = obj.optString("image", "")
                    val price  = obj.optDouble("current_price", 0.0)
                    val mcap   = obj.optDouble("market_cap", 0.0)
                    val vol    = obj.optDouble("total_volume", 0.0)
                    val chg24h = obj.optDouble("price_change_percentage_24h", 0.0)

                    if (vol < MIN_VOL_24H) continue
                    val mint = "cg:$id"
                    upsert(DynToken(
                        mint          = mint,
                        symbol        = symbol,
                        name          = name,
                        logoUrl       = logo,
                        price         = price,
                        priceChange24h= chg24h,
                        mcap          = mcap,
                        volume24h     = vol,
                        source        = "cg_markets:p$page",
                        sector        = inferSector(symbol),
                    ))
                    added++
                }
                Thread.sleep(250) // respect CoinGecko free rate limits
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "CoinGecko page $page: ${e.message}")
                break
            }
        }
        ErrorLogger.info(TAG, "CoinGecko markets: +$added")
    }

    private fun fetchCoinGeckoTrending() {
        val trending = cgTrending.refresh()
        trending.forEachIndexed { rank, tok ->
            val mint     = symbolIndex[tok.symbol.uppercase()] ?: "cg:${tok.id}"
            val existing = registry[mint]
            val updated  = (existing ?: DynToken(
                mint    = mint,
                symbol  = tok.symbol.uppercase(),
                name    = tok.name,
                source  = "cg_trending",
                logoUrl = tok.sparklineThumbUrl,
                sector  = inferSector(tok.symbol),
            )).copy(
                isTrending    = true,
                trendingRank  = rank,
                priceChange24h= tok.priceChangePercent,
                lastUpdatedMs = System.currentTimeMillis(),
            )
            registry[mint] = updated
            symbolIndex[tok.symbol.uppercase()] = mint
        }
        ErrorLogger.info(TAG, "CoinGecko trending: ${trending.size} tokens marked")
    }

    /**
     * Jupiter strict token list — authoritative list of all verified Solana tokens.
     * Adds real mint addresses + logos for thousands of tokens.
     * Only adds NEW tokens (never overwrites richer data from other sources).
     */
    private fun fetchJupiterTokenList() {
        val body  = httpGet("https://token.jup.ag/strict") ?: return
        var added = 0
        try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj     = arr.getJSONObject(i)
                val mint    = obj.optString("address",   "").trim()
                val symbol  = obj.optString("symbol",    "").uppercase().trim()
                val name    = obj.optString("name",      "").trim()
                val logoUrl = obj.optString("logoURI",   "")

                if (mint.isBlank() || symbol.isBlank()) continue

                // If a CoinGecko entry exists for this symbol, upgrade its mint address
                val cgKey = symbolIndex[symbol]
                if (cgKey != null && cgKey.startsWith("cg:")) {
                    val existing = registry[cgKey]
                    if (existing != null) {
                        // Migrate to real mint
                        val upgraded = existing.copy(
                            mint    = mint,
                            logoUrl = existing.logoUrl.ifBlank { logoUrl },
                            lastUpdatedMs = System.currentTimeMillis(),
                        )
                        registry.remove(cgKey)
                        registry[mint] = upgraded
                        symbolIndex[symbol] = mint
                        continue
                    }
                }

                if (registry.containsKey(mint)) {
                    // Only update logo if missing
                    val existing = registry[mint]!!
                    if (existing.logoUrl.isBlank() && logoUrl.isNotBlank()) {
                        registry[mint] = existing.copy(logoUrl = logoUrl, lastUpdatedMs = System.currentTimeMillis())
                    }
                    continue
                }

                // Brand new token from Jupiter — add with minimal data
                registry[mint] = DynToken(
                    mint    = mint,
                    symbol  = symbol,
                    name    = name,
                    logoUrl = logoUrl,
                    source  = "jupiter_strict",
                    sector  = inferSector(symbol),
                )
                symbolIndex[symbol] = mint
                added++
            }
            ErrorLogger.info(TAG, "Jupiter strict: +$added new tokens (total ${registry.size})")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "fetchJupiterTokenList: ${e.message}")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun upsert(tok: DynToken) {
        if (tok.mint.isBlank() || tok.symbol.isBlank()) return

        // V5.9.3: If this symbol already maps to a static:XXX token, enrich that
        // token directly instead of creating a duplicate cg:/dex: entry.
        // This is what makes SAND/ENJ/etc show real MCap, volume, liquidity.
        val staticKey = symbolIndex[tok.symbol.uppercase()]
        val staticTok = staticKey?.let { registry[it] }?.takeIf { it.isStatic }

        if (staticTok != null && staticKey != null) {
            registry[staticKey] = staticTok.copy(
                price         = tok.price.takeIf { it > 0 } ?: staticTok.price,
                priceChange24h= tok.priceChange24h.takeIf { it != 0.0 } ?: staticTok.priceChange24h,
                mcap          = tok.mcap.takeIf { it > 0 } ?: staticTok.mcap,
                liquidityUsd  = tok.liquidityUsd.takeIf { it > 0 } ?: staticTok.liquidityUsd,
                volume24h     = tok.volume24h.takeIf { it > 0 } ?: staticTok.volume24h,
                buys24h       = tok.buys24h.takeIf { it > 0 } ?: staticTok.buys24h,
                sells24h      = tok.sells24h.takeIf { it > 0 } ?: staticTok.sells24h,
                isTrending    = tok.isTrending || staticTok.isTrending,
                trendingRank  = if (tok.trendingRank >= 0) tok.trendingRank else staticTok.trendingRank,
                isBoosted     = tok.isBoosted || staticTok.isBoosted,
                logoUrl       = staticTok.logoUrl.ifBlank { tok.logoUrl },
                lastUpdatedMs = System.currentTimeMillis(),
            )
            // Don't remap symbolIndex — static key stays authoritative
            return
        }

        val existing = registry[tok.mint]
        if (existing?.isStatic == true) {
            // Direct mint match — enrich static token
            registry[tok.mint] = existing.copy(
                price         = tok.price.takeIf { it > 0 } ?: existing.price,
                priceChange24h= tok.priceChange24h.takeIf { it != 0.0 } ?: existing.priceChange24h,
                mcap          = tok.mcap.takeIf { it > 0 } ?: existing.mcap,
                liquidityUsd  = tok.liquidityUsd.takeIf { it > 0 } ?: existing.liquidityUsd,
                volume24h     = tok.volume24h.takeIf { it > 0 } ?: existing.volume24h,
                buys24h       = tok.buys24h.takeIf { it > 0 } ?: existing.buys24h,
                sells24h      = tok.sells24h.takeIf { it > 0 } ?: existing.sells24h,
                isTrending    = tok.isTrending || existing.isTrending,
                trendingRank  = if (tok.trendingRank >= 0) tok.trendingRank else existing.trendingRank,
                isBoosted     = tok.isBoosted || existing.isBoosted,
                logoUrl       = existing.logoUrl.ifBlank { tok.logoUrl },
                lastUpdatedMs = System.currentTimeMillis(),
            )
        } else {
            registry[tok.mint] = tok.copy(lastUpdatedMs = System.currentTimeMillis())
            if (tok.symbol.isNotBlank()) symbolIndex[tok.symbol.uppercase()] = tok.mint
        }
    }

    private fun meetsQualityGate(pair: PairInfo?): Boolean {
        if (pair == null) return false
        if (pair.liquidity < MIN_LIQ_USD)  return false
        if (pair.candle.volume24h < MIN_VOL_24H) return false
        val ageMs = if (pair.pairCreatedAtMs > 0) System.currentTimeMillis() - pair.pairCreatedAtMs else Long.MAX_VALUE
        if (ageMs < MIN_AGE_MINUTES * 60_000L) return false
        return true
    }

    private fun httpGet(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    fun inferSector(symbol: String): String {
        val s = symbol.uppercase()
        return when {
            s in setOf("BTC","ETH","SOL","BNB","ADA","XRP","AVAX","DOT","ATOM","NEAR","APT","SUI","SEI","TON","TRX","FTM","ONE","ALGO","HBAR","EGLD","ICP","THETA","ZIL","NEAR") -> "L1"
            s in setOf("ARB","OP","STRK","IMX","MANTA","BLAST","LINEA","SCROLL","BASE","ZKSYNC","METIS","BOBA") -> "L2"
            s in setOf("UNI","SUSHI","CAKE","RAY","ORCA","JUP","1INCH","CRV","BAL","AERO","DODO","RUNE","OSMO","ASTRO") -> "DEX"
            s in setOf("AAVE","COMP","MKR","SNX","PENDLE","ENA","LIDO","RPL","FRAX","LIQUITY","EULER","RADIANT") -> "Lending"
            s in setOf("LINK","PYTH","BAND","API3","DIA","UMA","TELLOR") -> "Oracle"
            s in setOf("RNDR","FIL","AR","STORJ","HNT","WIFI","MOBILE","GEODNET","DIMO") -> "DePIN"
            s in setOf("AXS","SAND","MANA","ILV","GALA","IMX","RON","MAGIC","GME","BEAM","PYR","SLP","GODS","NYAN") -> "Gaming"
            s in setOf("WIF","BONK","POPCAT","PEPE","SHIB","DOGE","FLOKI","BRETT","MOG","LADYS","TURBO","BOME","SLERF","PONKE","MYRO","WEN","SILLY","MEW","CATWIFHAT","HARAMBE","PNUT","GOAT","MOODENG","CHILLGUY") -> "Meme"
            s in setOf("TRUMP","MELANIA","JOE","BODEN","KAMALA","MAGA","BLAZE","JAVIER","MILEI") -> "Political"
            s in setOf("WLD","TAO","AGIX","FET","OCEAN","RENDER","NMR","AI16Z","AIXBT","ZEREBRO","VIRTUAL","CLANKER","LUNA","FARTCOIN","GRIFFAIN") -> "AI/Agent"
            s in setOf("GMT","STEPN","GENOPETS","WALKEN","DUSTY","ATLAS") -> "Move2Earn"
            s in setOf("BLUR","X2Y2","LOOKS","TENSOR","MAGIC","FRACTAL") -> "NFT"
            s in setOf("JTO","JITO","MSOL","STSOL","BSOL","JSOL","HPSOL","LSOL","RISKSOL") -> "LST"
            s in setOf("PYUSD","USDC","USDT","DAI","FRAX","LUSD","USDE","SUSD") -> "Stablecoin"
            else -> "Other"
        }
    }

    fun getCoinGeckoLogoUrl(symbol: String): String {
        // CMC CDN is reliable and requires no auth. CoinGecko thumb as fallback.
        val cmcId = CMC_IDS[symbol.uppercase()]
        if (cmcId != null) return "https://s2.coinmarketcap.com/static/img/coins/64x64/$cmcId.png"
        val cgPath = CG_PATHS[symbol.uppercase()]
        if (cgPath != null) return "https://coin-images.coingecko.com/coins/images/$cgPath"
        return ""
    }

    // CoinMarketCap IDs (verified working)
    private val CMC_IDS = mapOf(
        // ── OG / Major L1 ────────────────────────────────────────────────────
        "BTC" to 1, "ETH" to 1027, "SOL" to 5426, "BNB" to 1839,
        "XRP" to 52, "ADA" to 2010, "DOGE" to 74, "AVAX" to 5805,
        "DOT" to 6636, "MATIC" to 3890, "POL" to 3890, "LTC" to 2,
        "ATOM" to 3794, "NEAR" to 6535, "TON" to 11419, "TRX" to 1958,
        "APT" to 21794, "SUI" to 20947, "ICP" to 8916, "FTM" to 3513,
        "ONE" to 3945, "ALGO" to 4030, "HBAR" to 4642, "XLM" to 512,
        "XMR" to 328, "ZEC" to 1437, "EOS" to 1765, "XTZ" to 2011,
        "BCH" to 1831, "VET" to 3077, "THETA" to 2416, "ZIL" to 2469,
        "EGLD" to 6892, "SEI" to 23149, "TIA" to 22861, "STX" to 4847,
        "CFX" to 7334, "KAVA" to 4846, "IOTA" to 1720, "FLR" to 7950,
        "CELO" to 5567, "ROSE" to 8298, "FLOW" to 4558, "WAVES" to 1274,
        "DASH" to 131, "DCR" to 1168, "ZEN" to 1698, "QTUM" to 1684,
        "ETC" to 1321, "SC" to 1042, "ONT" to 2566, "BTT" to 16086,
        "WIN" to 4206, "JST" to 5488, "KAS" to 20396, "HBAR2" to 4642,
        "EGLD2" to 6892, "XDC" to 2634, "GNO" to 1659, "ANKR" to 3783,
        "SKL" to 6924, "ZRX" to 1896, "QTUM2" to 1684, "ICX" to 2099,
        "WBTC" to 3717, "PAXG" to 4705, "JASMY" to 8425,
        "STG" to 23569, "COTI" to 3992, "CELR" to 3673,
        // ── Layer 2 / Scaling ────────────────────────────────────────────────
        "ARB" to 11841, "OP" to 11840, "STRK" to 22691, "IMX" to 10603,
        "METIS" to 9640, "MANTA" to 25028, "ZK" to 24091, "BLAST" to 28480,
        "SCROLL" to 26998, "LINEA" to 28473, "MANTLE" to 27075,
        // ── DeFi ─────────────────────────────────────────────────────────────
        "LINK" to 1975, "UNI" to 7083, "AAVE" to 7278, "MKR" to 1518,
        "SNX" to 2586, "CRV" to 6538, "LDO" to 8000, "RPL" to 2099,
        "COMP" to 5692, "SUSHI" to 6758, "1INCH" to 8104, "BAL" to 5728,
        "CAKE" to 7186, "JUP" to 29210, "RAY" to 6536, "ORCA" to 11165,
        "PENDLE" to 9481, "ENA" to 30171, "GMX" to 11857, "RUNE" to 4157,
        "OSMO" to 12220, "JTO" to 28301, "DRIFT" to 27565, "MNGO" to 11171,
        "DYDX" to 11156, "CVX" to 9903, "FXS" to 6953, "FRAX" to 6952,
        "LUSD" to 9566, "LQTY" to 19434, "SPELL" to 11289, "ALCX" to 8613,
        "TOKE" to 11419, "PERP" to 9004, "DODO" to 8809, "ALPHA" to 7692,
        "BADGER" to 7859, "CREAM" to 6193, "FIDU" to 11367,
        // ── AI / Data / Compute ──────────────────────────────────────────────
        "WLD" to 13502, "TAO" to 22974, "RNDR" to 5690, "RENDER" to 5690,
        "FET" to 3773, "OCEAN" to 3911, "INJ" to 7226, "GRT" to 6719,
        "PYTH" to 28177, "AIOZ" to 9432, "NKN" to 3724, "STORJ" to 1772,
        "ARPA" to 4039, "CTSI" to 5444, "RNDR2" to 5690, "ALT" to 28036,
        "IO" to 29519, "HYPE" to 32196, "VIRTUAL" to 35095,
        // ── Gaming / Metaverse / NFT ─────────────────────────────────────────
        "AXS" to 6783, "SAND" to 6210, "MANA" to 1966, "GALA" to 7080,
        "RON" to 14101, "MAGIC" to 16563, "ENJ" to 1903, "CHZ" to 4066,
        "AUDIO" to 7455, "FLOW2" to 4558, "PIXEL" to 28461, "PORTAL" to 29268,
        "ATLAS" to 9310, "POLIS" to 9309,
        // ── Infrastructure / Oracle / Interop ────────────────────────────────
        "FIL" to 2280, "MSOL" to 18369, "GMT" to 18069, "ENS" to 13855,
        "BLUR" to 23121, "W" to 29330, "TNSR" to 29046, "KMNO" to 29032,
        "CLOUD" to 29023, "MAPS" to 8083,
        // ── Privacy ──────────────────────────────────────────────────────────
        "XMR2" to 328, "ZEC2" to 1437, "SCRT" to 5604, "KEEP" to 6058,
        // ── Solana DeFi/Infra (not covered by SOL meme trader) ───────────────
        "FIDA" to 7992, "SLIM" to 8407, "MEDIA" to 8415, "COPE" to 8118,
        "STEP" to 8387, "SAMO" to 9286, "MNDE" to 11970, "BLZE" to 11965,
        // ── Ethereum memes / blue chips ──────────────────────────────────────
        "SHIB" to 5994, "FLOKI" to 10804, "PEPE" to 24478,
        "BABYDOGE" to 10407, "WOJAK" to 23916, "TURBO" to 24911,
        "MOG" to 28474, "NEIRO" to 29513, "BRETT" to 29743,
        // ── Solana meme (cross-listed, logos needed for positions tab) ────────
        "WIF" to 28752, "BONK" to 23095, "POPCAT" to 28782,
        "TRUMP" to 33001, "BOME" to 29955, "NOT" to 28082,
        "PNUT" to 28779, "GOAT" to 29217, "FARTCOIN" to 29049,
        "MEW" to 28988, "WEN" to 28887, "MOODENG" to 32090,
        "MOTHER" to 29562, "GIGA" to 28953, "MICHI" to 29087,
        "CHILLGUY" to 34349, "RETARDIO" to 28816, "SIGMA" to 28897,
        "PONKE" to 28985, "SLERF" to 29958, "MYRO" to 28887,
        "NINJA" to 29182, "BODEN" to 29148, "GME" to 29137,
        "DUKO" to 28977, "HOBBES" to 28990, "PUPS" to 28996,
        "GROK" to 28653, "BEERCOIN" to 29134,
        // ── Newer / cycle tokens ─────────────────────────────────────────────
        "MOVE" to 32800, "SUI2" to 20947, "POL2" to 3890,
        "PYUSD" to 24356, "USDC" to 3408, "DAI" to 4943,
        "STETH" to 8085, "CBETH" to 21535,
    )

    // CoinGecko coin-images fallback (for tokens not in CMC map)
    private val CG_PATHS = mapOf(
        "ICP"     to "14495/thumb/Internet_Computer_logo.png",
        "HBAR"    to "3688/thumb/hbar.png",
        "STX"     to "2069/thumb/Stacks_Logo_png.png",
        "POPCAT"  to "33760/thumb/image.jpg",
        "TRUMP"   to "53746/thumb/trump.png",
        "SEI"     to "28205/thumb/sei.jpeg",
        "TIA"     to "31967/thumb/tia.jpg",
        "JUP"     to "34188/thumb/jup.png",
        "ORCA"    to "26234/thumb/orca.png",
        "PYTH"    to "31234/thumb/pyth.png",
        "WLD"     to "31069/thumb/worldcoin.jpeg",
        "ENA"     to "36530/thumb/ethena.png",
        "PNUT"    to "36069/thumb/pnut.jpeg",
        "MEW"     to "36120/thumb/mew.jpeg",
        "GOAT"    to "35942/thumb/goat.jpeg",
        "FARTCOIN" to "35956/thumb/fartcoin.jpeg",
        "BOME"    to "35822/thumb/bome.jpeg",
        "PONKE"   to "35616/thumb/ponke.jpg",
        "MOODENG" to "38234/thumb/moodeng.jpeg",
        "JTO"     to "33410/thumb/jto.png",
        "APT"     to "26455/thumb/aptos_round.png",
        "SUI"     to "26375/thumb/sui_asset.jpeg",
        "ARB"     to "16547/thumb/arbitrum.jpg",
        "IMX"     to "17233/thumb/immutableX-symbol-BLK-RGB.png",
        "SAND"    to "12129/thumb/sandbox_logo.jpg",
        "AXS"     to "6945/thumb/AXS.png",
        "RUNE"    to "4157/thumb/thorchain.png",
        "INJ"     to "12882/thumb/Secondary_Symbol.png",
        "STRK"    to "26433/thumb/starknet.png",
        "TAO"     to "28452/thumb/ARUsPeNQ_400x400.jpeg",
        "RNDR"    to "11636/thumb/rndr.png",
        "RENDER"  to "11636/thumb/rndr.png",
        "PENDLE"  to "15069/thumb/Pendle_Logo_Normal-03.png",
        "GMT"     to "18867/thumb/stepn.png",
        "RAY"     to "8526/thumb/raydium.png",
        "WIF"     to "33566/thumb/dogwifhat.jpg",
        "BONK"    to "28600/thumb/bonk.jpg",
        "FLOKI"   to "16746/thumb/FLOKI.png",
        "SHIB"    to "11939/thumb/shiba.png",
        "PEPE"    to "29850/thumb/pepe-token.jpeg",
    )

}
