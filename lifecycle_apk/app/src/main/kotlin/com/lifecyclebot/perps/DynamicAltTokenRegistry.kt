package com.lifecyclebot.perps

import com.lifecyclebot.network.SharedHttpClient

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.CoinGeckoTrending
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.PairInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        val mint: String,           // raw provider token address / established placeholder
        val symbol: String,
        val name: String,
        val chainId: String = "",  // unknown stays unknown; never implicitly Solana
        val tokenAddress: String = mint,
        val dexId: String = "",
        val logoUrl: String = "",
        val pairAddress: String = "",
        val quoteAddress: String = "",
        val pairCreatedAtMs: Long = 0L,
        val firstSeenMs: Long = System.currentTimeMillis(),
        val price: Double = 0.0,
        val priceChange24h: Double = 0.0,
        val mcap: Double = 0.0,
        val fdv: Double = 0.0,
        val mcapSource: String = "",
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
        val canonicalIdentity6544: String get() = canonicalIdentity6544(chainId, tokenAddress)
        val discoveryUrgency6544: String get() = when {
            isFresh6544 -> "FRESH"
            isTrending || isBoosted || kotlin.math.abs(priceChange24h) >= 8.0 -> "ACTIVE_TRENDING"
            else -> "ESTABLISHED"
        }
        val discoveryAgeHours6544: Double get() = when {
            pairCreatedAtMs > 0L -> ((System.currentTimeMillis() - pairCreatedAtMs).coerceAtLeast(0L) / 3_600_000.0)
            ageHours > 0.0 -> ageHours
            !isStatic && (source.startsWith("dex_") || source.startsWith("gecko")) ->
                ((System.currentTimeMillis() - firstSeenMs).coerceAtLeast(0L) / 3_600_000.0)
            else -> Double.POSITIVE_INFINITY
        }
        val isFresh6544: Boolean get() = !isStatic && discoveryAgeHours6544 < 1.0
        val isProbation6544: Boolean get() = !isStatic && discoveryAgeHours6544 * 60.0 < FRESH_PROBATION_MINUTES_6544
        val opportunityScore6544: Int get() = (qualityScore + when {
            isFresh6544 -> 35
            isTrending -> 25
            isBoosted -> 20
            kotlin.math.abs(priceChange24h) >= 8.0 -> 15
            isStatic -> 5
            else -> 0
        }).coerceIn(0, 135)

        val hasTrustedMarketCap6492: Boolean get() = mcap.isFinite() && mcap > 0.0 && mcapSource in setOf(
            "COINGECKO_MARKET_CAP", "DEXSCREENER_BASE_MINT_MARKET_CAP", "BIRDEYE_MARKET_CAP", "STATIC_TRUSTED_MARKET_CAP"
        )

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

    /** V5.0.6544 canonical dynamic identity. Symbol is never identity. */
    fun canonicalIdentity6544(chainId: String, tokenAddress: String): String {
        val token = tokenAddress.trim()
        if (token.startsWith("static:") || token.startsWith("cg:")) return token
        val chain = chainId.trim().lowercase().ifBlank { "unknown" }
        val normalizedToken = if (chain == "solana") token else token.lowercase()
        return "$chain|$normalizedToken"
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private val registry     = ConcurrentHashMap<String, DynToken>(4096) // canonical mint/asset-id → row
    private val symbolIndex  = ConcurrentHashMap<String, String>()   // DISPLAY preference only; never execution identity
    private val symbolCandidates6493 = ConcurrentHashMap<String, MutableSet<String>>()

    private fun indexSymbol6493(symbol: String, canonicalKey: String) {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank() || canonicalKey.isBlank()) return
        symbolCandidates6493.computeIfAbsent(sym) { ConcurrentHashMap.newKeySet<String>() }.add(canonicalKey)
        symbolIndex.putIfAbsent(sym, canonicalKey)
    }

    private val lastDiscoveryCycle = AtomicLong(0L)
    private val lastFreshDiscovery6544 = AtomicLong(0L)
    private val lastActiveDiscovery6544 = AtomicLong(0L)
    private val lastEstablishedDiscovery6544 = AtomicLong(0L)
    private val lastPriceRefresh   = AtomicLong(0L)

    private const val FRESH_DISCOVERY_MS_6544 = 15_000L
    private const val ACTIVE_DISCOVERY_MS_6544 = 60_000L
    private const val ESTABLISHED_DISCOVERY_MS_6544 = 5 * 60_000L
    private const val DISCOVERY_TTL_MS = ESTABLISHED_DISCOVERY_MS_6544
    private const val PRICE_TTL_MS     = 60_000L

    private val networksObserved6544 = ConcurrentHashMap.newKeySet<String>()
    private val dexesObserved6544 = ConcurrentHashMap.newKeySet<String>()
    private val newDiscoveries6544 = AtomicLong(0L)
    private val knownRefreshes6544 = AtomicLong(0L)
    private val freshPoolsDiscovered6544 = AtomicLong(0L)
    private val freshReachedBrain6544 = AtomicLong(0L)
    private val freshReachedFdg6544 = AtomicLong(0L)
    private val evaluationStarted6567 = AtomicLong(0L)
    private val evaluationDisposition6567 = ConcurrentHashMap<String, AtomicLong>()
    private val evaluationProgress6570 = ConcurrentHashMap<String, AtomicLong>()
    // V5.0.6580 §P0-f — bounded evidence deadline. First-seen timestamp per
    // (identity, state-key) so a second stamp of the same non-terminal state
    // more than EVIDENCE_TTL_MS_6580 later reaps into STALE_EXPIRED_6580_<state>.
    private val evaluationProgressStamp6580 = ConcurrentHashMap<String, Long>()
    private val EVIDENCE_TTL_MS_6580: Long = 5L * 60L * 1000L  // 5 minutes
    // V5.0.6587 §P0-4 — global sweep bookkeeping. Reaper runs at most once
    // per SWEEP_INTERVAL_MS regardless of how many progress stamps arrive.
    private val lastGlobalSweepMs6587 = java.util.concurrent.atomic.AtomicLong(0L)
    private val SWEEP_INTERVAL_MS_6587: Long = 30L * 1000L  // sweep every 30s
    private val paperOnlyNoRoute6544 = AtomicLong(0L)
    private val liveRoutable6544 = AtomicLong(0L)
    private val staticEvaluated6544 = AtomicLong(0L)
    private val dynamicEvaluated6544 = AtomicLong(0L)
    private val geckoNetworks6544 = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val geckoNetworkCursor6544 = java.util.concurrent.atomic.AtomicInteger(0)
    // V5.9.495z24 — operator: "the registry should hold 500+ mints in persistent
    // memory and constantly accumulate". Old 1-hour stale window evicted everything
    // not seen on the last cycle. Now: tokens with a real on-chain Solana mint stay
    // for 7 days even if not re-confirmed. CoinGecko-only ("cg:") placeholders keep
    // the original 1-hour window because they aren't directly tradeable anyway.
    private const val TOKEN_STALE_MS         = 7L * 24 * 60 * 60_000L  // 7 days for real mints
    private const val PLACEHOLDER_STALE_MS   = 60 * 60_000L            // 1 hour for cg:/static: keys

    private const val MIN_LIQ_USD      = 5_000.0
    private const val MIN_VOL_24H      = 10_000.0
    private const val FRESH_PROBATION_MINUTES_6544 = 10.0

    // V5.9.495z24 — disk persistence so the universe survives restarts.
    @Volatile private var appCtx: android.content.Context? = null
    private const val PERSIST_FILE = "dynamic_alt_token_registry.json"
    private val persistLock = Any()
    private val persistDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val PERSIST_DEBOUNCE_MS = 5_000L
    @Volatile private var persistJob: Job? = null
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val http = SharedHttpClient.builder()
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
                val rawIdentity = realMint ?: "static:${market.symbol}"
                val explicitChain = if (realMint != null) "solana" else "established"
                val key = canonicalIdentity6544(explicitChain, rawIdentity)
                val tok = DynToken(
                    mint      = rawIdentity,
                    tokenAddress = rawIdentity,
                    chainId   = explicitChain,
                    symbol    = market.symbol,
                    name      = market.displayName,
                    isStatic  = true,
                    logoUrl   = getCoinGeckoLogoUrl(market.symbol),
                    sector    = inferSector(market.symbol),
                    source    = "static_enum",
                )
                registry[key] = tok
                indexSymbol6493(market.symbol, key)
            }
        ErrorLogger.info(TAG, "Seeded ${registry.size} static PerpsMarket tokens")
    }

    // ─── V5.9.495z24 — Persistent disk storage ────────────────────────────────
    /**
     * Operator: "the dynamic token registry is meant to be constantly finding
     * new token mints and storing them to ensure they are held in the
     * persistent memory. the crypto trader should have 500 plus tokens mints
     * already in its memory."
     *
     * Three new behaviours wired from this point:
     *   1. init(ctx): hydrate the registry from disk (instant 500+ token startup
     *      if the previous session already discovered them).
     *   2. seedStaticTokens() + restoreFromDisk() are idempotent — call both at
     *      every bot start to combine static enum + persisted dynamic universe.
     *   3. After every discovery cycle, schedule a debounced save() to a JSON
     *      file in app filesDir. We coalesce with PERSIST_DEBOUNCE_MS so a burst
     *      of upserts during one cycle writes once.
     *
     * persistJob lives on persistScope (Dispatchers.IO).
     */
    fun init(context: android.content.Context) {
        appCtx = context.applicationContext
        seedStaticTokens()
        // V5.0.6401 ANR-KILLER — restoreFromDisk showed up in the
        // pipeline health snapshot as a main-thread ANR contributor
        // (SourceFile:237 stall). The registry API guards missing
        // entries by returning null / empty defaults, so restoring
        // asynchronously is safe: worst case, a caller hits the
        // registry during the ~50ms restore window and gets an
        // empty result once (recovered on the next tick).
        Thread({
            try {
                restoreFromDisk()
                ErrorLogger.info(TAG, "Init complete | total=${registry.size} | static=${getStaticCount()} | dynamic=${getDynamicCount()}")
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "async restoreFromDisk failed: ${t.message}")
            }
        }, "DynamicAltTokenRegistry-Restore-6401").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }

    /** Schedule a debounced save on background thread. Coalesces bursts. */
    fun scheduleSave() {
        if (appCtx == null) return
        persistDirty.set(true)
        synchronized(persistLock) {
            if (persistJob?.isActive == true) return
            persistJob = persistScope.launch {
                try {
                    delay(PERSIST_DEBOUNCE_MS)
                    if (persistDirty.compareAndSet(true, false)) saveToDisk()
                } catch (_: Throwable) {}
            }
        }
    }

    /** Synchronous save — fire-and-forget on caller thread. */
    @Synchronized
    private fun saveToDisk() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            for (tok in registry.values) {
                // Skip CoinGecko-only placeholders — they aren't tradeable and
                // bloat the file. Static keys ARE persisted so logos / sector
                // tags survive restarts.
                if (tok.mint.startsWith("cg:")) continue
                val o = JSONObject().apply {
                    put("mint", tok.mint)
                    put("symbol", tok.symbol)
                    put("name", tok.name)
                    put("chainId", tok.chainId)
                    put("tokenAddress", tok.tokenAddress)
                    if (tok.dexId.isNotBlank()) put("dexId", tok.dexId)
                    if (tok.logoUrl.isNotBlank()) put("logoUrl", tok.logoUrl)
                    if (tok.pairAddress.isNotBlank()) put("pairAddress", tok.pairAddress)
                    if (tok.quoteAddress.isNotBlank()) put("quoteAddress", tok.quoteAddress)
                    put("pairCreatedAtMs", tok.pairCreatedAtMs)
                    put("firstSeenMs", tok.firstSeenMs)
                    put("price", tok.price)
                    put("priceChange24h", tok.priceChange24h)
                    put("mcap", tok.mcap)
                    put("fdv", tok.fdv)
                    put("mcapSource", tok.mcapSource)
                    put("liquidityUsd", tok.liquidityUsd)
                    put("volume24h", tok.volume24h)
                    put("buys24h", tok.buys24h)
                    put("sells24h", tok.sells24h)
                    put("ageHours", tok.ageHours)
                    put("source", tok.source)
                    put("isTrending", tok.isTrending)
                    put("trendingRank", tok.trendingRank)
                    put("isBoosted", tok.isBoosted)
                    put("isStatic", tok.isStatic)
                    if (tok.sector.isNotBlank()) put("sector", tok.sector)
                    put("lastUpdatedMs", tok.lastUpdatedMs)
                }
                arr.put(o)
            }
            val file = java.io.File(ctx.filesDir, PERSIST_FILE)
            file.writeText(arr.toString())
            ErrorLogger.info(TAG, "💾 Persisted ${arr.length()} tokens to ${file.name} (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveToDisk failed: ${e.message}")
        }
    }

    /** Hydrate the registry from disk on startup. Idempotent — never overwrites richer in-memory data. */
    @Synchronized
    private fun restoreFromDisk() {
        val ctx = appCtx ?: return
        val file = java.io.File(ctx.filesDir, PERSIST_FILE)
        if (!file.exists()) {
            ErrorLogger.info(TAG, "📂 No persisted token file yet — fresh start")
            return
        }
        try {
            val arr = JSONArray(file.readText())
            var loaded = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mint = o.optString("mint", "").trim()
                if (mint.isBlank()) continue
                if (mint.startsWith("cg:")) continue
                val restoredSource6544 = o.optString("source", "")
                val restoredChain6544 = o.optString("chainId", "").ifBlank {
                    // Source-proven Solana rows may migrate; unknown rows stay unknown.
                    if (restoredSource6544.contains("jupiter", true)) "solana" else ""
                }
                val restoredTokenAddress6544 = o.optString("tokenAddress", mint).ifBlank { mint }
                val restoredKey6544 = canonicalIdentity6544(restoredChain6544, restoredTokenAddress6544)
                // Don't clobber a higher-quality in-memory entry we just seeded.
                val existing = registry[restoredKey6544]
                if (existing != null && !existing.isStatic && existing.lastUpdatedMs > o.optLong("lastUpdatedMs", 0L)) continue
                val restoredMcapSource6493 = o.optString("mcapSource", "").takeIf {
                    it in setOf("DEXSCREENER_BASE_MINT_MARKET_CAP", "BIRDEYE_MARKET_CAP", "STATIC_TRUSTED_MARKET_CAP")
                } ?: ""
                val restoredMcap6493 = o.optDouble("mcap", 0.0).takeIf {
                    restoredMcapSource6493.isNotBlank() && it.isFinite() && it > 0.0
                } ?: 0.0
                val tok = DynToken(
                    mint           = mint,
                    symbol         = o.optString("symbol", "").uppercase(),
                    name           = o.optString("name", ""),
                    chainId        = restoredChain6544,
                    tokenAddress    = restoredTokenAddress6544,
                    dexId           = o.optString("dexId", ""),
                    logoUrl        = o.optString("logoUrl", ""),
                    pairAddress    = o.optString("pairAddress", ""),
                    quoteAddress   = o.optString("quoteAddress", ""),
                    pairCreatedAtMs= o.optLong("pairCreatedAtMs", 0L),
                    firstSeenMs    = o.optLong("firstSeenMs", o.optLong("lastUpdatedMs", System.currentTimeMillis())),
                    price          = o.optDouble("price", 0.0),
                    priceChange24h = o.optDouble("priceChange24h", 0.0),
                    mcap           = restoredMcap6493,
                    fdv            = o.optDouble("fdv", 0.0),
                    mcapSource     = restoredMcapSource6493,
                    liquidityUsd   = o.optDouble("liquidityUsd", 0.0),
                    volume24h      = o.optDouble("volume24h", 0.0),
                    buys24h        = o.optInt("buys24h", 0),
                    sells24h       = o.optInt("sells24h", 0),
                    ageHours       = o.optDouble("ageHours", 0.0),
                    source         = o.optString("source", "restored"),
                    isTrending     = o.optBoolean("isTrending", false),
                    trendingRank   = o.optInt("trendingRank", -1),
                    isBoosted      = o.optBoolean("isBoosted", false),
                    isStatic       = o.optBoolean("isStatic", false),
                    sector         = o.optString("sector", ""),
                    lastUpdatedMs  = o.optLong("lastUpdatedMs", System.currentTimeMillis()),
                )
                // V5.0.6493 — restore by canonical mint only; ticker never joins rows.
                registry[mint] = tok
                if (tok.symbol.isNotBlank()) indexSymbol6493(tok.symbol, mint)
                loaded++
            }
            ErrorLogger.info(TAG, "📂 Restored $loaded tokens from disk")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "restoreFromDisk failed: ${e.message}")
        }
    }

    /** Background discovery scheduler with urgency split (V5.0.6544). */
    @Volatile private var discoveryJob: Job? = null
    fun startBackgroundDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = persistScope.launch {
            ErrorLogger.info(TAG, "▶ discovery loop started fresh=15s active=60s established=5m")
            delay(5_000L)
            while (isActive) {
                try { runDiscoveryCycle(); scheduleSave() }
                catch (e: Throwable) { ErrorLogger.warn(TAG, "discovery cycle err: ${e.message}") }
                delay(FRESH_DISCOVERY_MS_6544)
            }
        }
    }

    fun stopBackgroundDiscovery() {
        try { discoveryJob?.cancel() } catch (_: Throwable) {}
        discoveryJob = null
    }

    /** Force all three urgency tiers; used by the manual scan button. */
    suspend fun forceDiscoveryCycle() {
        lastFreshDiscovery6544.set(0L)
        lastActiveDiscovery6544.set(0L)
        lastEstablishedDiscovery6544.set(0L)
        runDiscoveryCycle(forceAll6544 = true)
    }

    suspend fun runDiscoveryCycle(forceAll6544: Boolean = false) {
        val now = System.currentTimeMillis()
        val freshDue = forceAll6544 || now - lastFreshDiscovery6544.get() >= FRESH_DISCOVERY_MS_6544
        val activeDue = forceAll6544 || now - lastActiveDiscovery6544.get() >= ACTIVE_DISCOVERY_MS_6544
        val establishedDue = forceAll6544 || now - lastEstablishedDiscovery6544.get() >= ESTABLISHED_DISCOVERY_MS_6544
        if (!freshDue && !activeDue && !establishedDue) return
        lastDiscoveryCycle.set(now)
        ErrorLogger.info(TAG, "Discovery cycle fresh=$freshDue active=$activeDue established=$establishedDue total=${registry.size}")

        // Fresh/active discovery must not be choked by the slow-background gate.
        if (freshDue) {
            lastFreshDiscovery6544.set(now)
            fetchDexScreenerBoosted()
            fetchDexScreenerLatestProfiles()
            fetchGeckoPools6544(newPools = true)
        }
        if (activeDue) {
            lastActiveDiscovery6544.set(now)
            fetchDexScreenerKeywordSearch()
            fetchGeckoPools6544(newPools = false)
        }
        if (establishedDue) {
            val skipSlow = try { com.lifecyclebot.engine.LiveExecutionGate.shouldSkipSlowBackgroundScans() } catch (_: Throwable) { false }
            if (!skipSlow) {
                lastEstablishedDiscovery6544.set(now)
                refreshGeckoNetworks6544()
                fetchCoinGeckoMarkets()
                fetchCoinGeckoTrending()
                fetchJupiterTokenList()
            } else ErrorLogger.debug(TAG, "🚦 established discovery deferred — live path busy")
        }

        val realStaleTs = now - TOKEN_STALE_MS
        val placeholderStaleTs = now - PLACEHOLDER_STALE_MS
        var evicted = 0
        var freshEvicted6547 = 0
        registry.entries.removeIf { (_, tok) ->
            if (tok.isStatic) return@removeIf false
            val placeholder = tok.tokenAddress.startsWith("cg:") || tok.tokenAddress.startsWith("static:")
            val drop = tok.lastUpdatedMs < if (placeholder) placeholderStaleTs else realStaleTs
            if (drop) {
                evicted++
                // V5.0.6547 §P1-3 — expose fresh-drop attrition. If a
                // fresh discovery is evicted before hitting the brain,
                // it likely didn't finish enrichment in time. Counter
                // is diagnostic; behaviour unchanged.
                if (tok.isFresh6544) freshEvicted6547++
            }
            drop
        }
        if (freshEvicted6547 > 0) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_FRESH_EVICTED_6547")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "CRYPTO_FRESH_EVICTED_6547",
                "count=$freshEvicted6547 stage=discoveryCycle.eviction paper=false action=diagnose_enrichment_race",
            )
        } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "Discovery complete: ${registry.size} tokens (-$evicted evicted, fresh=$freshEvicted6547)")
        scheduleSave()
    }

    // ─── Public getters ───────────────────────────────────────────────────────

    fun getAllTokens(sortBy: SortMode = SortMode.QUALITY): List<DynToken> {
        val all = registry.values.toList()
        return when (sortBy) {
            SortMode.QUALITY  -> all.sortedByDescending { it.opportunityScore6544 }
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
    fun getTokenByCanonicalIdentity6544(identity: String) = registry[identity]
    /** Compatibility metadata lookup. Raw addresses resolve only when unique across chains. */
    fun getTokenByMint(mint: String): DynToken? {
        registry[mint]?.let { return it }
        val matches = registry.values.filter { it.tokenAddress == mint || it.mint == mint }
        return matches.singleOrNull()
    }

    /** Display/metadata lookup only. Ambiguous tickers return null. */
    fun getTokenBySymbol(sym: String): DynToken? {
        val symbol = sym.trim().uppercase()
        val candidates = symbolCandidates6493[symbol].orEmpty().mapNotNull { registry[it] }
            .filter { it.symbol.equals(symbol, true) }
        val trustedMetadata = candidates.filter { it.hasTrustedMarketCap6492 }
        if (trustedMetadata.size == 1) return trustedMetadata.single()
        val staticRows = candidates.filter { it.isStatic }
        if (staticRows.size == 1 && candidates.size == 1) return staticRows.single()
        if (candidates.size == 1) return candidates.single()
        if (candidates.size > 1) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_SYMBOL_AMBIGUOUS_REJECTED_6493")
        } catch (_: Throwable) {}
        return null
    }

    /** Authorizing lookup: a ticker may resolve only when exactly one real mint owns it. */
    fun getUniqueExecutableTokenBySymbol6493(sym: String): DynToken? {
        val symbol = sym.trim().uppercase()
        val candidates = symbolCandidates6493[symbol].orEmpty().mapNotNull { registry[it] }
            .filter { it.symbol.equals(symbol, true) }
            .filter { !it.mint.startsWith("cg:") && !it.mint.startsWith("static:") }
            .filter { com.lifecyclebot.engine.execution.MintIntegrityGate.isLikelyMint(it.mint) }
            .distinctBy { it.mint }
        if (candidates.size > 1) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EXEC_MINT_AMBIGUOUS_REJECTED_6493")
        } catch (_: Throwable) {}
        return candidates.singleOrNull()
    }
    fun getNewTokens(maxAgeHours: Double=24.0) = registry.values
        .filter { !it.isStatic && it.discoveryAgeHours6544 <= maxAgeHours }
        .sortedByDescending { it.opportunityScore6544 }
    /** Existing registry's blended queue: fresh, trending/boosted, changed, then established by opportunity strength.
     *
     *  V5.0.6588 §P0-5 — FRESH-DISCOVERY PRIORITY.
     *  Operator forensic (6580): 772 identities, 157 new, only 1 reaches
     *  CryptoBrain. Root cause: prior blended sort was opportunityScore
     *  DESC then volume24h DESC — fresh tokens have low volume24h so they
     *  never bubble to top-25. Fresh-pool hunter contract required them
     *  first. isFresh6544 now becomes the primary sort key (true first),
     *  then opportunityScore, then volume — every top-25 scan is guaranteed
     *  to include every currently-fresh token before falling through to
     *  established ranking. */
    fun getBlendedOpportunityQueue6544(): List<DynToken> = registry.values.sortedWith(
        compareByDescending<DynToken> { it.isFresh6544 }
            .thenByDescending { it.opportunityScore6544 }
            .thenByDescending { it.volume24h }
            .thenByDescending { it.lastUpdatedMs }
    )
    fun getTokensBySector(sector: String)      = registry.values.filter { it.sector.equals(sector, true) }.sortedByDescending { it.qualityScore }
    fun getStats()                             = "Total: ${registry.size} | Static: ${getStaticCount()} | Dynamic: ${getDynamicCount()} | Trending: ${getTrendingTokens().size} | Boosted: ${getBoostedTokens().size}"

    fun markEvaluation6544(tok: DynToken) {
        if (tok.isStatic) staticEvaluated6544.incrementAndGet() else dynamicEvaluated6544.incrementAndGet()
        if (tok.isFresh6544) {
            freshReachedBrain6544.incrementAndGet()
            // V5.0.6547 §P1-3 — publish CRYPTO_FRESH_BRAIN as a pipeline
            // counter so the health dump can trace fresh discovery from
            // scanner → CryptoBrain without diving into the registry's
            // internal AtomicLong.
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_FRESH_BRAIN_6547") } catch (_: Throwable) {}
        }
    }
    fun markEvaluationStarted6567(tok: DynToken) {
        evaluationStarted6567.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_STARTED_6567") } catch (_: Throwable) {}
    }
    fun markEvaluationProgress6570(tok: DynToken?, state: String) {
        if (tok == null) return
        val key = state.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(72)
        evaluationProgress6570.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_PROGRESS_6570|$key")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("CRYPTO_EVAL_PROGRESS_6570",
                "identity=${tok.canonicalIdentity6544} symbol=${tok.symbol} chain=${tok.chainId.ifBlank { "unknown" }} state=$key terminal=false coalesced=true")
        } catch (_: Throwable) {}
        // V5.0.6580 §P0-f — BOUNDED EVIDENCE DEADLINE.
        // Operator directive (6578 forensic): 159/200 crypto evaluations never
        // terminalize because SHARED_INTELLIGENCE_BACKLOG_COALESCED_REQUEUE and
        // SPECIALIST_SILENCE_SHARED_EVIDENCE stamp indefinitely without a
        // deadline. This block records the first-seen timestamp per identity
        // per state key; a second stamp for the same identity/state more than
        // EVIDENCE_TTL_MS after the first triggers a terminal STALE_EXPIRED_6580
        // disposition so operator's 'no permanent missing bucket' invariant
        // holds. Discovery breadth is not reduced — the token remains in the
        // registry, just with an explicit terminal disposition.
        try {
            val progressKey6580 = "${tok.canonicalIdentity6544}|$key"
            val firstSeenAt = evaluationProgressStamp6580.putIfAbsent(progressKey6580, System.currentTimeMillis())
            if (firstSeenAt != null) {
                val age = System.currentTimeMillis() - firstSeenAt
                if (age > EVIDENCE_TTL_MS_6580) {
                    evaluationProgressStamp6580.remove(progressKey6580)
                    markEvaluationDisposition6567(tok, "STALE_EXPIRED_6580_$key")
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_STALE_REAPED_6580")
                }
            }
        } catch (_: Throwable) {}
        // V5.0.6587 §P0-4 — GLOBAL STALE SWEEP.
        // Operator forensic (6580 → 6586): 438 tokens stuck at
        // SPECIALIST_SILENCE_SHARED_EVIDENCE + SHARED_INTELLIGENCE_BACKLOG_
        // COALESCED_REQUEUE. The per-token TTL added in 6580 catches only
        // re-stamps. If a token is stamped ONCE and never re-appears in a
        // scanner window, its entry sits forever. Every markEvaluationProgress
        // call now also sweeps the whole progress map — any entry older than
        // EVIDENCE_TTL_MS_6580 is reaped as STALE_EXPIRED_6587_<state> even
        // if the caller never revisits it. Batched so at most one sweep
        // per SWEEP_INTERVAL_MS.
        try {
            val nowMs6587 = System.currentTimeMillis()
            if (nowMs6587 - lastGlobalSweepMs6587.get() > SWEEP_INTERVAL_MS_6587) {
                lastGlobalSweepMs6587.set(nowMs6587)
                val iter = evaluationProgressStamp6580.entries.iterator()
                var reaped = 0
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (nowMs6587 - entry.value > EVIDENCE_TTL_MS_6580) {
                        val split = entry.key.indexOf('|')
                        if (split > 0) {
                            val identity6587 = entry.key.substring(0, split)
                            val state6587 = entry.key.substring(split + 1)
                            val stale6587 = getTokenByCanonicalIdentity6544(identity6587)
                            if (stale6587 != null) markEvaluationDisposition6567(stale6587, "STALE_EXPIRED_6587_$state6587")
                        }
                        iter.remove()
                        reaped++
                    }
                }
                if (reaped > 0) {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_STALE_SWEEP_REAPED_6587")
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "CRYPTO_EVAL_STALE_SWEEP_6587",
                            "reaped=$reaped mapSize=${evaluationProgressStamp6580.size}",
                        )
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    fun markEvaluationDisposition6567(tok: DynToken?, reason: String) {
        if (tok == null) return
        val key = reason.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(72)
        evaluationDisposition6567.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_TERMINAL_6567|$key")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("CRYPTO_EVAL_TERMINAL_6567",
                "identity=${tok.canonicalIdentity6544} symbol=${tok.symbol} chain=${tok.chainId.ifBlank { "unknown" }} dex=${tok.dexId.ifBlank { "unknown" }} reason=$key")
        } catch (_: Throwable) {}
    }

    fun markFdgReach6544(tok: DynToken?, liveRoutable: Boolean, paperOnlyNoRoute: Boolean) {
        if (tok?.isFresh6544 == true) {
            freshReachedFdg6544.incrementAndGet()
            // V5.0.6547 §P1-3 — same-tier counter for FDG stage.
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_FRESH_FDG_6547") } catch (_: Throwable) {}
        }
        if (liveRoutable) liveRoutable6544.incrementAndGet()
        if (paperOnlyNoRoute) paperOnlyNoRoute6544.incrementAndGet()
    }
    fun discoveryReport6544(): String {
        val rows = registry.values.toList()
        val byChain = rows.groupingBy { it.chainId.ifBlank { "unknown" } }.eachCount().toList().sortedByDescending { it.second }
        val lt5 = rows.count { !it.isStatic && it.discoveryAgeHours6544 < (5.0 / 60.0) }
        val lt15 = rows.count { !it.isStatic && it.discoveryAgeHours6544 < (15.0 / 60.0) }
        val lt1h = rows.count { !it.isStatic && it.discoveryAgeHours6544 < 1.0 }
        return buildString {
            append("networks observed=").append(networksObserved6544.sorted().joinToString(",")).append('\n')
            append("DEXes observed=").append(dexesObserved6544.sorted().joinToString(",")).append('\n')
            append("fresh pools discovered=").append(freshPoolsDiscovered6544.get()).append('\n')
            append("unique chain+token identities=").append(rows.map { it.canonicalIdentity6544 }.toSet().size).append('\n')
            append("discoveries by chain=").append(byChain.joinToString { "${it.first}:${it.second}" }).append('\n')
            append("pool cohorts <5m=").append(lt5).append(" <15m=").append(lt15).append(" <1h=").append(lt1h).append('\n')
            append("new vs previously-known=").append(newDiscoveries6544.get()).append('/').append(knownRefreshes6544.get()).append('\n')
            append("fresh reaching CryptoBrain=").append(freshReachedBrain6544.get()).append('\n')
            append("fresh reaching V3/FDG=").append(freshReachedFdg6544.get()).append('\n')
            append("paper-only unavailable live route=").append(paperOnlyNoRoute6544.get()).append('\n')
            append("live-routable candidates=").append(liveRoutable6544.get()).append('\n')
            append("static-vs-dynamic evaluation share=").append(staticEvaluated6544.get()).append('/').append(dynamicEvaluated6544.get()).append('\n')
            val terminal6567 = evaluationDisposition6567.values.sumOf { it.get() }
            append("evaluation terminal dispositions=started:").append(evaluationStarted6567.get())
                .append(" terminal:").append(terminal6567)
                .append(" missing:").append((evaluationStarted6567.get() - terminal6567).coerceAtLeast(0L)).append('\n')
            append("evaluation non-terminal progress=").append(evaluationProgress6570.entries
                .sortedByDescending { it.value.get() }.joinToString { "${it.key}:${it.value.get()}" }).append('\n')
            append("evaluation terminal reasons=").append(evaluationDisposition6567.entries
                .sortedByDescending { it.value.get() }.joinToString { "${it.key}:${it.value.get()}" })
        }
    }

    /** Update live price for a static token (called from PerpsMarketDataFetcher callback) */
    fun updateStaticPrice(symbol: String, price: Double, change24h: Double, mcap: Double, vol24h: Double) {
        val key = "static:$symbol"
        val existingEntry = registry.entries.firstOrNull { it.value.isStatic && it.value.symbol.equals(symbol, true) } ?: return
        val existing = existingEntry.value
        registry[existingEntry.key] = existing.copy(
            price = price, priceChange24h = change24h,
            mcap = mcap.takeIf { it.isFinite() && it > 0.0 } ?: existing.mcap,
            mcapSource = if (mcap.isFinite() && mcap > 0.0) "STATIC_TRUSTED_MARKET_CAP" else existing.mcapSource,
            volume24h = vol24h, lastUpdatedMs = System.currentTimeMillis(),
        )
    }

    /**
     * V5.9.147 — lazy price fallback for tokens seeded without price.
     *
     * Jupiter strict (`fetchJupiterTokenList`) dumps ~200+ verified mints into
     * the registry with `price=0.0` — they never get scanned downstream
     * because `runDynamicTokenScan` short-circuits on `price<=0`. The result
     * was a DynScan "ghost town": universe=201 but scanned=0.
     *
     * This helper does a best-effort, cache-first, rate-limited DexScreener
     * lookup and hydrates the registry entry in place so the next scan cycle
     * can analyze the token. Returns the resolved price (0 if still unknown).
     *
     * Safe to call from the hot scan loop: `DexscreenerApi.getBestPair` is
     * 45s-cached and RateLimiter-gated, so it will silently return null under
     * load rather than hammering the API.
     */
    fun refreshPriceForMintBlocking(identityOrAddress: String): Double {
        val existing = registry[identityOrAddress] ?: getTokenByMint(identityOrAddress) ?: return 0.0
        if (existing.price > 0.0) return existing.price
        if (existing.tokenAddress.startsWith("cg:") || existing.tokenAddress.startsWith("static:")) return 0.0
        val chain = existing.chainId.trim().lowercase()
        if (chain.isBlank() || chain == "unknown" || chain == "established") return 0.0
        val pair = try { dex.getBestPair(chain, existing.tokenAddress) } catch (_: Exception) { null } ?: return 0.0
        val price = pair.candle.priceUsd
        if (price <= 0.0) return 0.0
        val key = existing.canonicalIdentity6544
        registry[key] = existing.copy(
            price = price,
            mcap = pair.candle.marketCap.takeIf { it.isFinite() && it > 0.0 } ?: existing.mcap,
            fdv = pair.fdv.takeIf { it.isFinite() && it > 0.0 } ?: existing.fdv,
            mcapSource = if (pair.candle.marketCap.isFinite() && pair.candle.marketCap > 0.0)
                "DEXSCREENER_BASE_MINT_MARKET_CAP" else existing.mcapSource,
            liquidityUsd = pair.liquidity.takeIf { it > 0.0 } ?: existing.liquidityUsd,
            volume24h = pair.candle.volume24h.takeIf { it > 0.0 } ?: existing.volume24h,
            buys24h = pair.candle.buys24h.takeIf { it > 0 } ?: existing.buys24h,
            sells24h = pair.candle.sells24h.takeIf { it > 0 } ?: existing.sells24h,
            dexId = existing.dexId.ifBlank { pair.dexId },
            pairAddress = existing.pairAddress.ifBlank { pair.pairAddress },
            quoteAddress = existing.quoteAddress.ifBlank { pair.quoteAddress },
            pairCreatedAtMs = existing.pairCreatedAtMs.takeIf { it > 0L } ?: pair.pairCreatedAt,
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return price
    }

    // ─── Discovery implementations ───────────────────────────────────────────

    private fun dynTokenFromPair6544(
        pair: PairInfo,
        source: String,
        logoUrl: String = "",
        boosted: Boolean = false,
        trending: Boolean = false,
    ): DynToken {
        val chain = pair.chainId.trim().lowercase()
        val token = pair.tokenAddress.ifBlank { pair.baseTokenAddress }.trim()
        val symbol = pair.baseSymbol.uppercase().ifBlank { token.take(8).uppercase() }
        val createdAt = pair.pairCreatedAt.takeIf { it > 0L } ?: pair.pairCreatedAtMs
        return DynToken(
            mint = token, tokenAddress = token, chainId = chain, dexId = pair.dexId,
            symbol = symbol, name = pair.baseName.ifBlank { symbol },
            logoUrl = logoUrl.ifBlank { "https://dd.dexscreener.com/ds-data/tokens/$chain/$token.png" },
            pairAddress = pair.pairAddress, quoteAddress = pair.quoteAddress,
            pairCreatedAtMs = createdAt,
            price = pair.candle.priceUsd, mcap = pair.candle.marketCap, fdv = pair.fdv,
            liquidityUsd = pair.liquidity, volume24h = pair.candle.volume24h,
            buys24h = pair.candle.buys24h, sells24h = pair.candle.sells24h,
            ageHours = if (createdAt > 0L) ((System.currentTimeMillis() - createdAt).coerceAtLeast(0L) / 3_600_000.0) else 999.0,
            isBoosted = boosted, isTrending = trending, source = source,
            sector = inferSector(symbol),
        )
    }

    private fun fetchDexScreenerBoosted() {
        val body = httpGet("https://api.dexscreener.com/token-boosts/top/v1") ?: return
        try {
            val arr = JSONArray(body)
            var added = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val chain = obj.optString("chainId", "").trim().lowercase()
                val token = obj.optString("tokenAddress", "").trim()
                if (chain.isBlank() || token.isBlank()) continue
                val pair = dex.getBestPair(chain, token) ?: continue
                if (!meetsQualityGate(pair)) continue
                upsert(dynTokenFromPair6544(pair, "dex_boosted", obj.optString("icon", ""), boosted = true))
                added++
            }
            ErrorLogger.info(TAG, "DexScreener boosted multichain: +$added")
        } catch (e: Exception) { ErrorLogger.warn(TAG, "boosted: ${e.message}") }
    }

    private fun fetchDexScreenerLatestProfiles() {
        val body = httpGet("https://api.dexscreener.com/token-profiles/latest/v1") ?: return
        try {
            val arr = JSONArray(body)
            var added = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val chain = obj.optString("chainId", "").trim().lowercase()
                val token = obj.optString("tokenAddress", "").trim()
                if (chain.isBlank() || token.isBlank()) continue
                val pair = dex.getBestPair(chain, token) ?: continue
                if (!meetsQualityGate(pair)) continue
                upsert(dynTokenFromPair6544(pair, "dex_profiles", obj.optString("icon", "")))
                added++
            }
            ErrorLogger.info(TAG, "DexScreener profiles multichain: +$added")
        } catch (e: Exception) { ErrorLogger.warn(TAG, "profiles: ${e.message}") }
    }

    private fun fetchDexScreenerKeywordSearch() {
        var added = 0
        repeat(4) {
            val keyword = searchKeywords[searchKeywordIdx % searchKeywords.size]
            searchKeywordIdx++
            try {
                dex.search(keyword).forEach { pair ->
                    if (pair.chainId.isBlank() || pair.tokenAddress.isBlank()) return@forEach
                    if (!meetsQualityGate(pair)) return@forEach
                    upsert(dynTokenFromPair6544(pair, "dex_kw:$keyword"))
                    added++
                }
            } catch (_: Exception) {}
        }
        ErrorLogger.info(TAG, "DexScreener keywords multichain: +$added")
    }

    private val geckoPriorityNetworks6544 = listOf("eth", "base", "bsc", "arbitrum", "polygon_pos", "avax", "optimism")

    private fun refreshGeckoNetworks6544() {
        val body = httpGet("https://api.geckoterminal.com/api/v2/networks?page=1") ?: return
        try {
            val data = JSONObject(body).optJSONArray("data") ?: return
            val observed = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id", "")?.takeIf(String::isNotBlank) }
            val prioritized = geckoPriorityNetworks6544.filter { it in observed }
            if (prioritized.isNotEmpty()) {
                geckoNetworks6544.clear()
                geckoNetworks6544.addAll(prioritized)
            }
        } catch (e: Throwable) { ErrorLogger.debug(TAG, "Gecko networks: ${e.message}") }
    }

    private fun fetchGeckoPools6544(newPools: Boolean) {
        if (geckoNetworks6544.isEmpty()) geckoNetworks6544.addAll(geckoPriorityNetworks6544)
        val idx = kotlin.math.abs(geckoNetworkCursor6544.getAndIncrement()) % geckoNetworks6544.size
        val network = geckoNetworks6544[idx]
        val endpoint = if (newPools) "new_pools" else "trending_pools"
        val body = httpGet("https://api.geckoterminal.com/api/v2/networks/$network/$endpoint?page=1") ?: return
        try {
            val data = JSONObject(body).optJSONArray("data") ?: return
            var accepted = 0
            for (i in 0 until minOf(data.length(), 40)) {
                val pool = data.optJSONObject(i) ?: continue
                val attrs = pool.optJSONObject("attributes") ?: continue
                val rel = pool.optJSONObject("relationships") ?: continue
                val baseId = rel.optJSONObject("base_token")?.optJSONObject("data")?.optString("id", "") ?: continue
                val quoteId = rel.optJSONObject("quote_token")?.optJSONObject("data")?.optString("id", "") ?: ""
                val token = baseId.removePrefix("${network}_")
                val quote = quoteId.removePrefix("${network}_")
                if (token.isBlank() || token == baseId) continue
                val name = attrs.optString("name", "")
                val symbol = name.substringBefore('/').trim().uppercase().ifBlank { token.take(8).uppercase() }
                val price = attrs.optString("base_token_price_usd", "0").toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue
                val createdAt = try { java.time.Instant.parse(attrs.optString("pool_created_at", "")).toEpochMilli() } catch (_: Throwable) { 0L }
                val ageHours = if (createdAt > 0L) ((System.currentTimeMillis() - createdAt).coerceAtLeast(0L) / 3_600_000.0) else 999.0
                val dexId = rel.optJSONObject("dex")?.optJSONObject("data")?.optString("id", "") ?: ""
                val poolId = pool.optString("id", "").removePrefix("${network}_")
                val tx = attrs.optJSONObject("transactions")?.optJSONObject("h24")
                val tok = DynToken(
                    mint=token, tokenAddress=token, chainId=network, dexId=dexId,
                    symbol=symbol, name=name.ifBlank { symbol }, pairAddress=poolId, quoteAddress=quote,
                    pairCreatedAtMs=createdAt, price=price,
                    priceChange24h=attrs.optJSONObject("price_change_percentage")?.optString("h24", "0")?.toDoubleOrNull() ?: 0.0,
                    mcap=attrs.optString("market_cap_usd", "0").toDoubleOrNull() ?: 0.0,
                    fdv=attrs.optString("fdv_usd", "0").toDoubleOrNull() ?: 0.0,
                    liquidityUsd=attrs.optString("reserve_in_usd", "0").toDoubleOrNull() ?: 0.0,
                    volume24h=attrs.optJSONObject("volume_usd")?.optString("h24", "0")?.toDoubleOrNull() ?: 0.0,
                    buys24h=tx?.optInt("buys", 0) ?: 0, sells24h=tx?.optInt("sells", 0) ?: 0,
                    ageHours=ageHours, source=if (newPools) "geckoterminal_new" else "geckoterminal_trending",
                    isTrending=!newPools, sector=inferSector(symbol),
                )
                upsert(tok)
                accepted++
            }
            ErrorLogger.info(TAG, "GeckoTerminal $endpoint network=$network accepted=$accepted")
        } catch (e: Throwable) { ErrorLogger.warn(TAG, "GeckoTerminal $endpoint/$network: ${e.message}") }
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
                        tokenAddress  = mint,
                        chainId       = "coingecko",
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
            val mint     = "cg:${tok.id}"
            val existing = registry[mint]
            val updated  = (existing ?: DynToken(
                mint    = mint,
                tokenAddress = mint,
                chainId = "coingecko",
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
            indexSymbol6493(tok.symbol, mint)
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

                // V5.0.6493 — NEVER migrate CoinGecko data onto a Jupiter
                // mint by ticker. Mint/contract address is canonical identity.

                val canonicalKey6544 = canonicalIdentity6544("solana", mint)
                if (registry.containsKey(canonicalKey6544)) {
                    // Only update logo if missing
                    val existing = registry[canonicalKey6544]!!
                    if (existing.logoUrl.isBlank() && logoUrl.isNotBlank()) {
                        registry[canonicalKey6544] = existing.copy(logoUrl = logoUrl, lastUpdatedMs = System.currentTimeMillis())
                    }
                    continue
                }

                // Brand new token from Jupiter — established Solana enrichment source.
                registry[canonicalKey6544] = DynToken(
                    mint    = mint,
                    tokenAddress = mint,
                    chainId = "solana",
                    symbol  = symbol,
                    name    = name,
                    logoUrl = logoUrl,
                    source  = "jupiter_strict",
                    sector  = inferSector(symbol),
                )
                indexSymbol6493(symbol, canonicalKey6544)
                added++
            }
            ErrorLogger.info(TAG, "Jupiter strict: +$added new tokens (total ${registry.size})")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "fetchJupiterTokenList: ${e.message}")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun upsert(rawTok6492: DynToken) {
        if (rawTok6492.mint.isBlank() || rawTok6492.symbol.isBlank()) return
        val inferredMcapSource6492 = when {
            rawTok6492.mcapSource.isNotBlank() -> rawTok6492.mcapSource
            rawTok6492.mcap <= 0.0 -> ""
            rawTok6492.source.startsWith("cg_") -> "COINGECKO_MARKET_CAP"
            rawTok6492.source.startsWith("dex_") -> "DEXSCREENER_BASE_MINT_MARKET_CAP"
            rawTok6492.source.contains("birdeye", true) -> "BIRDEYE_MARKET_CAP"
            else -> ""
        }
        // Unknown semantics are never promoted to market-cap truth.
        val tok = rawTok6492.copy(
            mcap = rawTok6492.mcap.takeIf { inferredMcapSource6492.isNotBlank() && it.isFinite() && it > 0.0 } ?: 0.0,
            mcapSource = inferredMcapSource6492,
        )

        // V5.0.6544 — merge only by canonical chain+token identity.
        // Symbol and bare token address cannot establish multichain identity.
        val canonicalKey6544 = tok.canonicalIdentity6544
        val existing = registry[canonicalKey6544]
        if (tok.chainId.isNotBlank()) networksObserved6544.add(tok.chainId.lowercase())
        if (tok.dexId.isNotBlank()) dexesObserved6544.add(tok.dexId.lowercase())
        if (existing == null) {
            newDiscoveries6544.incrementAndGet()
            if (tok.isFresh6544) freshPoolsDiscovered6544.incrementAndGet()
        } else knownRefreshes6544.incrementAndGet()
        if (existing?.isStatic == true) {
            // Direct mint match — enrich static token
            registry[canonicalKey6544] = existing.copy(
                price         = tok.price.takeIf { it > 0 } ?: existing.price,
                priceChange24h= tok.priceChange24h.takeIf { it != 0.0 } ?: existing.priceChange24h,
                mcap          = tok.mcap.takeIf { tok.hasTrustedMarketCap6492 } ?: existing.mcap,
                fdv           = tok.fdv.takeIf { it > 0 } ?: existing.fdv,
                mcapSource    = tok.mcapSource.takeIf { tok.hasTrustedMarketCap6492 } ?: existing.mcapSource,
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
            registry[canonicalKey6544] = if (existing == null) tok.copy(lastUpdatedMs = System.currentTimeMillis()) else existing.copy(
                symbol = tok.symbol.ifBlank { existing.symbol }, name = tok.name.ifBlank { existing.name },
                chainId = tok.chainId.ifBlank { existing.chainId }, tokenAddress = tok.tokenAddress.ifBlank { existing.tokenAddress },
                dexId = tok.dexId.ifBlank { existing.dexId }, logoUrl = existing.logoUrl.ifBlank { tok.logoUrl },
                pairAddress = tok.pairAddress.ifBlank { existing.pairAddress },
                quoteAddress = tok.quoteAddress.ifBlank { existing.quoteAddress },
                pairCreatedAtMs = tok.pairCreatedAtMs.takeIf { it > 0L } ?: existing.pairCreatedAtMs,
                firstSeenMs = minOf(existing.firstSeenMs, tok.firstSeenMs),
                price = tok.price.takeIf { it > 0.0 } ?: existing.price,
                priceChange24h = tok.priceChange24h.takeIf { it != 0.0 } ?: existing.priceChange24h,
                mcap = tok.mcap.takeIf { tok.hasTrustedMarketCap6492 } ?: existing.mcap,
                fdv = tok.fdv.takeIf { it > 0.0 } ?: existing.fdv,
                mcapSource = tok.mcapSource.takeIf { tok.hasTrustedMarketCap6492 } ?: existing.mcapSource,
                liquidityUsd = tok.liquidityUsd.takeIf { it > 0.0 } ?: existing.liquidityUsd,
                volume24h = tok.volume24h.takeIf { it > 0.0 } ?: existing.volume24h,
                buys24h = tok.buys24h.takeIf { it > 0 } ?: existing.buys24h,
                sells24h = tok.sells24h.takeIf { it > 0 } ?: existing.sells24h,
                ageHours = tok.ageHours.takeIf { it > 0.0 } ?: existing.ageHours,
                source = tok.source, isTrending = tok.isTrending || existing.isTrending,
                trendingRank = if (tok.trendingRank >= 0) tok.trendingRank else existing.trendingRank,
                isBoosted = tok.isBoosted || existing.isBoosted, sector = tok.sector.ifBlank { existing.sector },
                lastUpdatedMs = System.currentTimeMillis(),
            )
            if (tok.symbol.isNotBlank()) indexSymbol6493(tok.symbol, canonicalKey6544)
        }
    }

    private fun meetsQualityGate(pair: PairInfo?): Boolean {
        if (pair == null) return false
        if (pair.liquidity < MIN_LIQ_USD)  return false
        if (pair.candle.volume24h < MIN_VOL_24H) return false
        // V5.0.6544 — fresh-pool age is intelligence/probation, not discovery veto.
        // Deterministic execution safety remains in FDG/route proof.
        return pair.candle.priceUsd > 0.0
    }

    private fun httpGet(url: String): String? = try {
        val host = when {
            url.contains("geckoterminal") -> "geckoterminal"
            url.contains("dexscreener") -> "dexscreener"
            url.contains("coingecko") -> "coingecko"
            url.contains("jup.ag") -> "jupiter"
            else -> "dynamic_alt_discovery"
        }
        val accept = if (host == "geckoterminal") "application/json;version=20230302" else "application/json"
        val req = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", accept).build()
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host).use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
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
