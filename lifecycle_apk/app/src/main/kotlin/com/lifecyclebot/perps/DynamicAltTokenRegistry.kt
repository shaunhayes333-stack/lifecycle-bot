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

    /** Call once at startup — seeds the 56 static PerpsMarket tokens immediately */
    fun seedStaticTokens() {
        PerpsMarket.values()
            .filter { it.isCrypto && !it.isSolPerp }
            .forEach { market ->
                val key = "static:${market.symbol}"
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
        val existing = registry[tok.mint]
        if (existing?.isStatic == true) {
            // Enrich static with live price data only
            registry[tok.mint] = existing.copy(
                price         = tok.price.takeIf { it > 0 } ?: existing.price,
                priceChange24h= tok.priceChange24h.takeIf { it != 0.0 } ?: existing.priceChange24h,
                mcap          = tok.mcap.takeIf { it > 0 } ?: existing.mcap,
                liquidityUsd  = tok.liquidityUsd.takeIf { it > 0 } ?: existing.liquidityUsd,
                volume24h     = tok.volume24h.takeIf { it > 0 } ?: existing.volume24h,
                isTrending    = tok.isTrending || existing.isTrending,
                trendingRank  = if (tok.trendingRank >= 0) tok.trendingRank else existing.trendingRank,
                isBoosted     = tok.isBoosted || existing.isBoosted,
                logoUrl       = existing.logoUrl.ifBlank { tok.logoUrl },
                lastUpdatedMs = System.currentTimeMillis(),
            )
        } else {
            registry[tok.mint] = tok.copy(lastUpdatedMs = System.currentTimeMillis())
        }
        if (tok.symbol.isNotBlank()) symbolIndex[tok.symbol] = tok.mint
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
        val m = mapOf(
            // Major L1s
            "BTC"     to "1/small/bitcoin.png",
            "ETH"     to "279/small/ethereum.png",
            "SOL"     to "4128/small/solana.png",
            "BNB"     to "825/small/bnb-icon2_2x.png",
            "XRP"     to "44/small/xrp-symbol-white-128.png",
            "ADA"     to "975/small/cardano.png",
            "DOGE"    to "5/small/dogecoin.png",
            "AVAX"    to "12559/small/Avalanche_Circle_RedWhite_Trans.png",
            "DOT"     to "12171/small/polkadot.png",
            "MATIC"   to "4713/small/matic-token-icon.png",
            "POL"     to "4713/small/matic-token-icon.png",
            "LTC"     to "2/small/litecoin.png",
            "ATOM"    to "1481/small/cosmos_hub.png",
            "NEAR"    to "10365/small/near.jpg",
            "TON"     to "17980/small/ton_symbol.png",
            "TRX"     to "1094/small/tron-logo.png",
            "APT"     to "26455/small/aptos_round.png",
            "SUI"     to "26375/small/sui_asset.jpeg",
            "ICP"     to "4943/small/icp.jpeg",
            "FTM"     to "2943/small/fantom.png",
            "ONE"     to "3945/small/ONE.png",
            "ALGO"    to "4030/small/algorand.png",
            "HBAR"    to "4642/small/hedera-hashgraph.png",
            "XLM"     to "100/small/Stellar_symbol_black_RGB.png",
            "XMR"     to "69/small/monero_logo.png",
            "ZEC"     to "486/small/zcash.png",
            "EOS"     to "738/small/eos-eos-logo.png",
            "XTZ"     to "1975/small/Tezos-logo.png",
            "BCH"     to "780/small/bitcoin-cash-circle.png",
            "VET"     to "3077/small/vechain.png",
            "THETA"   to "2538/small/theta-token-logo.png",
            "ZIL"     to "2797/small/Zilliqa-logo.png",
            "EGLD"    to "6892/small/multiversx-egld-white.png",
            "SEI"     to "28205/small/sei.jpeg",
            "TIA"     to "31967/small/tia.jpg",
            "BLAST"   to "35453/small/blast.jpeg",
            "STX"     to "4847/small/stacks.png",
            // L2s
            "ARB"     to "16547/small/photo_2023-03-29_21.47.00.jpeg",
            "OP"      to "25244/small/Optimism.png",
            "STRK"    to "26433/small/starknet.png",
            "IMX"     to "17233/small/immutableX-symbol-BLK-RGB.png",
            "METIS"   to "15395/small/metis.jpeg",
            // DeFi
            "LINK"    to "877/small/chainlink-new-logo.png",
            "UNI"     to "12504/small/uniswap-uni.png",
            "AAVE"    to "7278/small/aave-v3-logo.png",
            "MKR"     to "1027/small/Maker_Icon_Purple.png",
            "SNX"     to "2586/small/synthetix-snx-logo.png",
            "CRV"     to "12124/small/Curve.png",
            "LDO"     to "18901/small/lido_staked_ether.png",
            "RPL"     to "2090/small/rpl.png",
            "COMP"    to "7083/small/Compound_v2_Icon_-_DARK.png",
            "SUSHI"   to "12271/small/512x512_Logo_no_chop.png",
            "1INCH"   to "13469/small/1inch-token.png",
            "BAL"     to "11683/small/Balancer.png",
            "CAKE"    to "10808/small/pancakeswap-cake-logo.png",
            "JUP"     to "34188/small/jup.png",
            "RAY"     to "8526/small/raydium.png",
            "ORCA"    to "26234/small/orca.png",
            "PENDLE"  to "15069/small/Pendle_Logo_Normal-03.png",
            "ENA"     to "36530/small/ethena.png",
            "GMX"     to "18323/small/arbit.png",
            "RUNE"    to "4157/small/thorchain.png",
            "OSMO"    to "20396/small/osmosis.png",
            "JTO"     to "33410/small/jto.png",
            // AI/Agents
            "WLD"     to "31069/small/worldcoin.jpeg",
            "TAO"     to "28452/small/ARUsPeNQ_400x400.jpeg",
            "RNDR"    to "11636/small/rndr.png",
            "FET"     to "5681/small/fet_new.png",
            "OCEAN"   to "3687/small/ocean-protocol-logo.png",
            "INJ"     to "12882/small/Secondary_Symbol.png",
            // Gaming
            "AXS"     to "6945/small/AXS.png",
            "SAND"    to "12129/small/sandbox_logo.jpg",
            "MANA"    to "1966/small/decentraland-mana.png",
            "GALA"    to "12493/small/GALA-COINGECKO.png",
            "RON"     to "14101/small/token_logo.png",
            "MAGIC"   to "23299/small/magic_logo.png",
            // Meme
            "SHIB"    to "11939/small/shiba.png",
            "FLOKI"   to "16746/small/PNG_image.png",
            "PEPE"    to "29850/small/pepe-token.jpeg",
            "WIF"     to "33566/small/dogwifhat.jpg",
            "BONK"    to "28600/small/bonk.jpg",
            "POPCAT"  to "35262/small/popcat.jpeg",
            "TRUMP"   to "39490/small/trump.png",
            "BOME"    to "35822/small/bome.jpeg",
            "SLERF"   to "35818/small/SLERF.jpeg",
            "PONKE"   to "35616/small/ponke.jpg",
            "MYRO"    to "33520/small/myro.jpeg",
            "WEN"     to "33975/small/wen.jpeg",
            "MEW"     to "36120/small/mew.jpeg",
            "PNUT"    to "36069/small/pnut.jpeg",
            "GOAT"    to "35942/small/goat.jpeg",
            "FARTCOIN" to "35956/small/fartcoin.jpeg",
            // NFT/Other
            "ENS"     to "13855/small/ENS.png",
            "BLUR"    to "28451/small/blur.png",
            "PYTH"    to "31234/small/pyth.png",
            "JTO"     to "33410/small/jto.png",
            "MSOL"    to "13737/small/msol.png",
            "GMT"     to "18867/small/stepn.png",
        )
        val path = m[symbol.uppercase()] ?: return ""
        return "https://assets.coingecko.com/coins/images/$path"
    }
}
