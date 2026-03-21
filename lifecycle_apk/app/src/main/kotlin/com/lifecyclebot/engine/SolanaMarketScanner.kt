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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // AGGRESSIVE Memory optimization
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .cache(null)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 2  // Only 2 concurrent requests max
            maxRequestsPerHost = 1
        })
        .build()

    private val dex        = DexscreenerApi()
    private val coingecko  = CoinGeckoTrending()
    
    // Use a mutable scope that can be recreated when starting
    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null
    
    // Create birdeye lazily with API key from config
    private val birdeye: BirdeyeApi
        get() = BirdeyeApi(cfg().birdeyeApiKey)

    // Track which mints we've already surfaced to avoid duplicates
    private val seenMints  = ConcurrentHashMap<String, Long>()
    private val SEEN_TTL   = 30 * 60_000L   // forget after 30 min — don't keep rescanning same tokens
    
    // Track rejected tokens separately - longer cooldown
    private val rejectedMints = ConcurrentHashMap<String, Long>()
    private val REJECTED_TTL = 60 * 60_000L  // forget rejected tokens after 1 hour
    
    // Memory protection: limit concurrent operations
    private val semaphore = kotlinx.coroutines.sync.Semaphore(3)  // max 3 concurrent scans
    
    // Memory-safe mode - enables when OOM detected
    @Volatile private var memorySafeMode = false
    @Volatile private var oomCount = 0
    
    // Scan rotation - alternate between different scan sources for variety
    @Volatile private var scanRotation = 0
    
    // Running state
    @Volatile private var isRunning = false
    
    // Coroutine exception handler for scanner - logs errors without crashing
    private val scannerExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Don't log cancellation as error - it's normal when scanner stops
        if (throwable is kotlinx.coroutines.CancellationException) {
            ErrorLogger.info("Scanner", "Scanner coroutine cancelled (normal shutdown)")
            return@CoroutineExceptionHandler
        }
        ErrorLogger.error("Scanner", 
            "Scanner coroutine exception: ${throwable.javaClass.simpleName}: ${throwable.message}", 
            throwable
        )
        onLog("⚠️ Scanner error: ${throwable.javaClass.simpleName} - ${throwable.message?.take(50)}")
        // Don't crash - just log and the scan loop will continue
    }
    
    // Helper to run scan functions with proper cancellation handling
    private suspend fun runScan(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Normal cancellation - re-throw to stop properly
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.warn("Scanner", "$name error: ${e.message}")
        }
    }

    // ── Start / Stop ─────────────────────────────────────────────────

    fun start() {
        if (isRunning) {
            ErrorLogger.warn("Scanner", "Scanner already running, ignoring start()")
            return
        }
        
        ErrorLogger.info("Scanner", "SolanaMarketScanner.start() called")
        isRunning = true
        
        // Create a fresh scope each time we start - with exception handler
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scannerExceptionHandler)
        scanJob = scope?.launch { 
            ErrorLogger.info("Scanner", "scanLoop starting...")
            
            // IMMEDIATE TEST SCAN - verify API works before main loop
            runTestScan()
            
            scanLoop() 
        }
        onLog("SolanaMarketScanner started")
    }
    
    private suspend fun runTestScan() {
        // LIFECYCLE-BASED test scan - finds newest tokens by profile, not by keyword
        // Uses token-profiles API which returns the most recently created tokens
        try {
            onLog("🧪 Running lifecycle test scan...")
            
            // Use token-profiles API - returns newest Solana tokens (no keywords needed)
            val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
            ErrorLogger.info("Scanner", "TEST: Fetching newest token profiles...")
            
            val body = get(url)
            if (body == null) {
                ErrorLogger.error("Scanner", "TEST FAILED: No response from DexScreener API")
                onLog("❌ API test failed - no response")
                return
            }
            
            val profiles = if (body.startsWith("[")) JSONArray(body) else {
                ErrorLogger.error("Scanner", "TEST FAILED: Invalid response format")
                onLog("❌ API test failed - bad format")
                return
            }
            
            ErrorLogger.info("Scanner", "TEST OK: Got ${profiles.length()} token profiles")
            onLog("✅ API OK: ${profiles.length()} profiles received")
            
            // For each token profile, get full pair data
            var added = 0
            var checked = 0
            for (i in 0 until minOf(profiles.length(), 20)) {
                if (added >= 5) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data for this token
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }
                if (pair == null) {
                    ErrorLogger.debug("Scanner", "TEST: No pair data for ${mint.take(12)}...")
                    continue
                }
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = pair.liquidity
                if (liq < 1000) continue
                
                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                
                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq,
                    volumeH1 = pair.candle.volumeH1,
                    mcapUsd = pair.candle.marketCap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = pair.candle.buysH1 + pair.candle.sellsH1,
                    score = 60.0
                )
                
                if (!passesFilter(token)) {
                    ErrorLogger.info("Scanner", "TEST: ${pair.baseSymbol} rejected by filters")
                    continue
                }
                
                emitWithRugcheck(token)
                added++
                ErrorLogger.info("Scanner", "TEST: Added ${pair.baseSymbol} (age=${ageHours.toInt()}h)")
                onLog("🎯 Test: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
            }
            
            if (added == 0) {
                onLog("⚠️ Test: No tokens passed (checked $checked)")
            } else {
                onLog("✅ Test: $added diverse tokens added")
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine was cancelled - this is normal when scanner is stopped
            ErrorLogger.info("Scanner", "TEST: Scan cancelled")
            throw e  // Re-throw to properly cancel
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "TEST ERROR: ${e.message}", e)
            onLog("❌ Test error: ${e.message?.take(50)}")
        }
    }

    fun stop() {
        ErrorLogger.info("Scanner", "SolanaMarketScanner.stop() called")
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        scope?.cancel()
        scope = null
        onLog("SolanaMarketScanner stopped")
    }

    // ── Main scan loop ────────────────────────────────────────────────

    private suspend fun scanLoop() {
        ErrorLogger.info("Scanner", "scanLoop() entered")
        while (isRunning) {
            val c = cfg()
            // Use configured interval, minimum 15 seconds (was 60 - too slow!)
            val scanIntervalMs = maxOf((c.scanIntervalSecs * 1000L).toLong(), 15_000L)
            ErrorLogger.debug("Scanner", "Scan interval: ${scanIntervalMs}ms")

            try {
                // Clean expired seen entries - use safe iteration
                val now = System.currentTimeMillis()
                val expiredKeys = seenMints.entries
                    .filter { now - it.value > SEEN_TTL }
                    .map { it.key }
                expiredKeys.forEach { seenMints.remove(it) }

                // ScalingMode tier logging
                val sScanTier = ScalingMode.activeTier(
                    TreasuryManager.treasurySol * WalletManager.lastKnownSolPrice)
                val _tn = if (sScanTier != ScalingMode.Tier.MICRO)
                    " ${sScanTier.icon}${sScanTier.label}" else ""
                
                // PUMP.FUN PRIORITY: Scan pump.fun EVERY cycle, plus rotate secondary sources
                scanRotation = (scanRotation + 1) % 3
                onLog("🌐 Scan #$scanRotation${_tn} - Starting scan cycle")
                ErrorLogger.info("Scanner", "Scan cycle #$scanRotation starting")
                
                // GC before scan
                System.gc()
                
                var tokensFoundThisCycle = 0
                
                // ALWAYS scan pump.fun first (priority)
                onLog("🚀 Scanning: Pump.fun tokens (PRIORITY)...")
                runScan("scanPumpFunActive") { scanPumpFunActive() }
                delay(500)
                
                // Then rotate through secondary sources
                when (scanRotation) {
                    0 -> {
                        // Pump.fun graduates + boosted
                        onLog("🔍 Scanning: Pump.fun graduates...")
                        runScan("scanPumpGraduates") { scanPumpGraduates() }
                        delay(500)
                        onLog("🔍 Scanning: DexScreener boosted...")
                        runScan("scanDexBoosted") { scanDexBoosted() }
                    }
                    1 -> {
                        // Pump.fun volume + fresh launches
                        onLog("🔍 Scanning: Pump.fun high volume...")
                        runScan("scanPumpFunVolume") { scanPumpFunVolume() }
                        delay(500)
                        onLog("🔍 Scanning: Fresh launches...")
                        runScan("scanFreshLaunches") { scanFreshLaunches() }
                    }
                    2 -> {
                        // DexScreener trending + gainers
                        onLog("🔍 Scanning: DexScreener trending...")
                        runScan("scanDexTrending") { scanDexTrending() }
                        delay(500)
                        onLog("🔍 Scanning: New Solana pairs...")
                        runScan("scanDexGainers") { scanDexGainers() }
                    }
                }
                
                // GC after scan
                System.gc()
                onLog("✅ Scan cycle #$scanRotation complete")
                
                // Clean up old seen/rejected entries every cycle
                cleanupSeenMaps()
                
                // GC after scan
                System.gc()

            } catch (e: OutOfMemoryError) {
                oomCount++
                ErrorLogger.crash("Scanner", "OutOfMemoryError #$oomCount", Exception(e.message))
                onLog("⚠️ Memory critical #$oomCount - pausing 30s")
                System.gc()
                delay(30_000)  // 30 second pause
                if (oomCount >= 5) {
                    onLog("⛔ Too many OOM errors - scanner pausing. Restart to retry.")
                    ErrorLogger.error("Scanner", "Scanner paused after $oomCount OOM errors")
                    delay(300_000)  // 5 minute pause, then continue
                    oomCount = 0  // Reset counter to try again
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation - don't log as error
                ErrorLogger.info("Scanner", "Scanner coroutine cancelled (normal shutdown)")
                throw e  // Re-throw to properly cancel
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ErrorLogger.error("Scanner", "Scanner error: ${e.message}", e)
                onLog("Scanner: ${e.message?.take(50)}")
            }

            delay(scanIntervalMs)
        }
    }


    // ══════════════════════════════════════════════════════════════════════════
    // PUMP.FUN FOCUSED SCANNING - Finding best profit opportunities
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * LIFECYCLE-BASED: Scan for newest token profiles.
     * Gets the most recently created tokens from DexScreener's token-profiles API.
     * No keywords - pure lifecycle discovery.
     */
    private suspend fun scanPumpFunActive() {
        // Use token-profiles API - returns newest tokens (no keywords)
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanPumpFunActive: fetching newest token profiles...")
        val body = get(url) ?: return
        
        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(profiles.length(), 25)) {
                if (found >= 8) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data for this token
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = pair.liquidity
                if (liq < 1000) continue
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Lifecycle scoring - newer tokens get bonus
                val ageBonus = when {
                    ageHours < 0.5 -> 35.0  // < 30 mins = very fresh
                    ageHours < 1 -> 30.0    // < 1 hour
                    ageHours < 3 -> 25.0    // < 3 hours
                    ageHours < 6 -> 20.0    // < 6 hours
                    ageHours < 12 -> 15.0   // < 12 hours
                    ageHours < 24 -> 10.0   // < 24 hours
                    else -> 5.0
                }
                
                val token = ScannedToken(
                    mint = mint, 
                    symbol = pair.baseSymbol, 
                    name = pair.baseName,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq, 
                    volumeH1 = vol, 
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, 
                    dexId = "solana",
                    priceChangeH1 = 0.0, 
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + ageBonus
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    val freshIcon = if (ageHours < 1) "🆕" else if (ageHours < 6) "📈" else "📊"
                    onLog("$freshIcon NEW: ${pair.baseSymbol} | ${ageHours.toInt()}h old | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunActive: found $found tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunActive error: ${e.message}")
        }
        System.gc()
    }

    /**
     * LIFECYCLE-BASED: Scan for BOOSTED tokens.
     * Tokens that are being promoted = attention incoming.
     * No keywords - pure discovery based on which tokens are being boosted.
     */
    private suspend fun scanPumpFunVolume() {
        // Use boosted tokens API - tokens being promoted (no keywords)
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        ErrorLogger.info("Scanner", "scanPumpFunVolume: fetching boosted tokens...")
        val body = get(url) ?: return
        
        try {
            val boosted = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(boosted.length(), 20)) {
                if (found >= 8) break
                
                val item = boosted.optJSONObject(i) ?: continue
                
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = pair.liquidity
                if (liq < 2000) continue  // Boosted tokens should have some liquidity
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Boosted tokens get attention bonus
                val boostBonus = 20.0
                
                val token = ScannedToken(
                    mint = mint, 
                    symbol = pair.baseSymbol, 
                    name = pair.baseName,
                    source = TokenSource.DEX_BOOSTED,
                    liquidityUsd = liq, 
                    volumeH1 = vol, 
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, 
                    dexId = "solana",
                    priceChangeH1 = 0.0, 
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + boostBonus
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("💎 BOOST: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunVolume: found $found boosted tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunVolume error: ${e.message}")
        }
        System.gc()
    }

    /**
     * Scan for FRESH LAUNCHES - tokens created in the last 30 minutes.
     * Early entry = maximum profit potential (but also maximum risk).
     * Uses token-profiles API which returns newest tokens.
     */
    private suspend fun scanFreshLaunches() {
        // Use token-profiles API to find fresh Solana tokens
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanFreshLaunches: looking for tokens < 30 mins old...")
        val body = get(url) ?: return
        
        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            
            for (i in 0 until minOf(profiles.length(), 30)) {
                if (found >= 10) break
                val profile = profiles.optJSONObject(i) ?: continue
                
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "") 
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                // Get pair data for this token
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val ageMinutes = ageHours * 60.0
                
                // Focus on fresh tokens (< 2 hours for more results)
                if (ageHours > 2) continue
                
                val liq = pair.liquidity
                // Fresh tokens - lower liquidity threshold
                if (liq < 500) continue
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val name = pair.baseName
                val symbol = pair.baseSymbol
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                val dexId = "solana"
                
                // Fresh launch bonus - the newer, the better
                val freshnessBonus = when {
                    ageMinutes < 5 -> 40.0   // < 5 mins = ultra fresh
                    ageMinutes < 10 -> 35.0  // < 10 mins
                    ageMinutes < 15 -> 30.0  // < 15 mins
                    ageMinutes < 30 -> 25.0  // < 30 mins
                    ageMinutes < 60 -> 20.0  // < 1 hour
                    else -> 15.0             // < 2 hours
                }
                
                val token = ScannedToken(
                    mint = mint, symbol = symbol, name = name,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq, volumeH1 = vol, mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, dexId = dexId,
                    priceChangeH1 = 0.0, txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + freshnessBonus
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("🆕 FRESH: $symbol | ${ageMinutes.toInt()}m old | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanFreshLaunches: found $found fresh tokens")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanFreshLaunches error: ${e.message}")
        }
        System.gc()
    }


    // ── Source 1: Dexscreener trending ───────────────────────────────

    private suspend fun scanDexTrending() {
        // Look for trending Solana tokens
        val url = "https://api.dexscreener.com/token-profiles/v1/latest?chainIds=solana"
        ErrorLogger.info("Scanner", "scanDexTrending: fetching from DexScreener...")
        val body = get(url)
        if (body == null) {
            ErrorLogger.warn("Scanner", "scanDexTrending: no response from API")
            return
        }
        try {
            val arr = JSONArray(body)
            ErrorLogger.info("Scanner", "scanDexTrending: got ${arr.length()} token profiles")
            var processed = 0
            var passed = 0
            for (i in 0 until minOf(arr.length(), 15)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "").trim()
                if (mint.isBlank() || mint.length < 32 || mint.startsWith("0x") || isSeen(mint)) continue
                processed++
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }
                if (pair == null) {
                    ErrorLogger.debug("Scanner", "scanDexTrending: no pair for ${mint.take(12)}")
                    continue
                }
                
                // Skip major stablecoins only
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val token = buildScannedToken(mint, pair, TokenSource.DEX_TRENDING) ?: continue
                if (passesFilter(token)) {
                    val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                    emitWithRugcheck(token)
                    passed++
                    onLog("📈 Trending: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanDexTrending: processed=$processed passed=$passed")
        } catch (e: OutOfMemoryError) {
            ErrorLogger.error("Scanner", "OOM in scanDexTrending", Exception(e.message))
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexTrending error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 2: LIFECYCLE-BASED new token discovery ─────────

    private suspend fun scanDexGainers() {
        // LIFECYCLE-BASED: Use token-profiles API for diverse new token discovery
        // No keywords - finds tokens purely by when they were created
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanDexGainers: fetching newest token profiles...")
        val body = get(url)
        if (body == null) {
            ErrorLogger.warn("Scanner", "scanDexGainers: no response from API")
            return
        }
        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            if (profiles.length() == 0) {
                ErrorLogger.warn("Scanner", "scanDexGainers: no profiles in response")
                return
            }
            ErrorLogger.info("Scanner", "scanDexGainers: got ${profiles.length()} token profiles")
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(profiles.length(), 30)) {
                if (found >= 10) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = pair.liquidity
                if (liq < 2000) continue  // Min $2K liquidity
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Lifecycle-based scoring - new tokens get bonus
                val ageBonus = when {
                    ageHours < 1 -> 30.0   // < 1 hour = very fresh
                    ageHours < 6 -> 25.0   // < 6 hours = fresh
                    ageHours < 12 -> 20.0  // < 12 hours
                    ageHours < 24 -> 15.0  // < 24 hours = new
                    ageHours < 48 -> 10.0  // < 48 hours
                    else -> 5.0
                }
                
                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = when {
                        ageHours < 6 -> TokenSource.PUMP_FUN_NEW
                        ageHours < 24 -> TokenSource.PUMP_FUN_GRADUATE
                        else -> TokenSource.RAYDIUM_NEW_POOL
                    },
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + ageBonus,
                )
                if (passesFilter(token)) { 
                    emitWithRugcheck(token)
                    found++
                    val src = when {
                        ageHours < 1 -> "🚀 FRESH"
                        ageHours < 6 -> "🆕 NEW"
                        ageHours < 24 -> "📈 YOUNG"
                        else -> "📊 TOKEN"
                    }
                    onLog("$src: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }
            if (found > 0) ErrorLogger.info("Scanner", "scanDexGainers: found $found tokens (checked $checked)")
            else ErrorLogger.info("Scanner", "scanDexGainers: no tokens passed filters (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexGainers error: ${e.message}")
        }
        System.gc()
    }
    // ── Source 3: Dexscreener boosted tokens ─────────────────────────

    private suspend fun scanDexBoosted() {
        // Boosted = paid promotion = attention arriving
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        val body = get(url) ?: return
        try {
            val arr = JSONArray(body)
            for (i in 0 until minOf(arr.length(), 12)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress","")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip only stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                val token = buildScannedToken(mint, pair, TokenSource.DEX_BOOSTED) ?: continue
                // Boosted tokens get score bump
                val boostedToken = token.copy(score = (token.score + 15.0).coerceAtMost(100.0))
                if (passesFilter(boostedToken)) {
                    emit(boostedToken)
                    onLog("💎 Boosted: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexBoosted error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 4: LIFECYCLE-BASED Pump.fun graduates ────────────

    private suspend fun scanPumpGraduates() {
        // LIFECYCLE-BASED: Use boosted tokens API which often contains recently graduated tokens
        // Boosted tokens are typically new and attention-worthy
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        ErrorLogger.info("Scanner", "scanPumpGraduates: fetching boosted tokens for graduates...")
        val body = get(url) ?: return
        try {
            val boosted = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(boosted.length(), 25)) {
                if (found >= 8) break
                
                val item = boosted.optJSONObject(i) ?: continue
                
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = pair.liquidity
                if (liq < 3000) continue  // Graduates should have decent liquidity
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Graduate/attention bonus scoring
                val graduateBonus = when {
                    ageHours < 1 -> 30.0   // Just launched
                    ageHours < 6 -> 25.0   // Fresh
                    ageHours < 12 -> 20.0  // Recent
                    ageHours < 24 -> 15.0  // New
                    else -> 10.0
                }
                
                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.PUMP_FUN_GRADUATE,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + graduateBonus,
                )
                if (passesFilter(token)) { 
                    emitWithRugcheck(token)
                    found++ 
                    val label = when {
                        ageHours < 1 -> "🎓 JUST GRAD"
                        ageHours < 6 -> "🎓 GRADUATE"
                        else -> "📊 PROMOTED"
                    }
                    onLog("$label: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }
            if (found > 0) ErrorLogger.info("Scanner", "scanPumpGraduates: found $found tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpGraduates error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 5: Birdeye trending ────────────────────────────────────

    private suspend fun scanBirdeyeTrending() {
        val c = cfg()
        if (c.birdeyeApiKey.isBlank()) return  // needs key
        val url = "https://public-api.birdeye.so/defi/token_trending?sort_by=rank&sort_type=asc&offset=0&limit=15"
        val body = get(url, apiKey = c.birdeyeApiKey) ?: return
        try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return
            for (i in 0 until minOf(items.length(), 12)) {
                val item = items.optJSONObject(i) ?: continue
                val mint = item.optString("address","")
                if (mint.isBlank() || isSeen(mint)) continue
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip only stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                val token = buildScannedToken(mint, pair, TokenSource.BIRDEYE_TRENDING) ?: continue
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    onLog("🦅 Birdeye: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanBirdeye error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 5b: Top Volume Tokens (new diverse source) ─────────────

    private suspend fun scanTopVolumeTokens() {
        // LIFECYCLE-BASED: Use token-profiles API for diverse discovery
        // Gets the most recent token profiles - no keywords
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        
        try {
            val body = get(url) ?: return
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            ErrorLogger.info("Scanner", "scanTopVolume: got ${profiles.length()} token profiles")
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(profiles.length(), 25)) {
                if (found >= 8) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY", "JUP")) continue
                
                val liq = pair.liquidity
                if (liq < 2000) continue
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Volume activity bonus
                val volBonus = when {
                    vol > 50000 -> 25.0
                    vol > 20000 -> 20.0
                    vol > 10000 -> 15.0
                    vol > 5000 -> 10.0
                    else -> 5.0
                }
                
                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.DEX_TRENDING,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + volBonus,
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("📊 ACTIVE: ${pair.baseSymbol} | vol=\$${vol.toInt()} | liq=\$${liq.toInt()}")
                }
            }
            
            if (found > 0) ErrorLogger.info("Scanner", "scanTopVolume: found $found tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanTopVolume error: ${e.message}")
        }
        System.gc()
    }


    // ── Source 6: CoinGecko trending ─────────────────────────────────

    private suspend fun scanCoinGeckoTrending() {
        // Skip CoinGecko - it requires extra API calls to resolve tokens
        ErrorLogger.debug("Scanner", "Skipping CoinGecko scan (memory optimization)")
        return
    }

    // ── Source 7: Raydium new pools ───────────────────────────────────

    private suspend fun scanRaydiumNewPools() {
        // Skip Raydium pools scan - returns huge JSON responses
        ErrorLogger.debug("Scanner", "Skipping Raydium scan (memory optimization)")
        return
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
                if (passesFilter(token)) emitWithRugcheck(token)
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
        val passed = passesFilterInternal(token)
        if (!passed) {
            // Mark rejected tokens so we don't keep re-scanning them
            markRejected(token.mint)
        } else {
            // Mark PASSING tokens as seen immediately to prevent duplicate processing
            // This prevents the same token from being evaluated multiple times
            // while waiting for rugcheck or other async operations
            seenMints[token.mint] = System.currentTimeMillis()
            ErrorLogger.info("Scanner", "FILTER PASS ${token.symbol}: liq=\$${token.liquidityUsd.toInt()} score=${token.score.toInt()}")
        }
        return passed
    }
    
    private fun passesFilterInternal(token: ScannedToken): Boolean {
        val c = cfg()

        // HARD MINIMUM MCAP - never trade tokens under $8K mcap
        // These are extremely high-risk micro caps
        val HARD_MIN_MCAP = 8_000.0
        if (token.mcapUsd > 0 && token.mcapUsd < HARD_MIN_MCAP) {
            onLog("🚫 BLOCK: ${token.symbol} - mcap \$${(token.mcapUsd/1000).toInt()}K too low")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} < hard min $${HARD_MIN_MCAP.toInt()}")
            return false
        }

        // Minimum liquidity
        if (token.liquidityUsd < c.minLiquidityUsd && token.liquidityUsd > 0) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: liq $${token.liquidityUsd.toInt()} < min $${c.minLiquidityUsd.toInt()}")
            return false
        }

        // DEX filter — user can restrict to specific DEXs
        if (c.allowedDexes.isNotEmpty() && token.dexId !in c.allowedDexes) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: DEX ${token.dexId} not in allowed list")
            return false
        }

        // MC range filter (user configurable, in addition to hard minimum)
        if (c.scanMinMcapUsd > 0 && token.mcapUsd < c.scanMinMcapUsd) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} < min $${c.scanMinMcapUsd.toInt()}")
            return false
        }
        if (c.scanMaxMcapUsd > 0 && token.mcapUsd > c.scanMaxMcapUsd) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} > max $${c.scanMaxMcapUsd.toInt()}")
            return false
        }

        // Source filter — user can disable specific sources
        if (token.source == TokenSource.PUMP_FUN_NEW && !c.scanPumpFunNew) return false
        if (token.source == TokenSource.PUMP_FUN_GRADUATE && !c.scanPumpGraduates) return false
        if (token.source == TokenSource.DEX_TRENDING && !c.scanDexTrending) return false
        if (token.source == TokenSource.RAYDIUM_NEW_POOL && !c.scanRaydiumNew) return false

        // Minimum discovery score
        if (token.score < c.minDiscoveryScore) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: score ${token.score.toInt()} < min ${c.minDiscoveryScore.toInt()}")
            return false
        }

        // Name/symbol scam heuristics
        val sym = token.symbol.lowercase()
        val name = token.name.lowercase()
        val scamPatterns = listOf("test","fake","scam","rug","honeypot","xxx","porn")
        if (scamPatterns.any { sym.contains(it) || name.contains(it) }) {
            onLog("🚫 BLOCK: ${token.symbol} - scam pattern")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: scam pattern detected")
            return false
        }
        
        // Block tokens impersonating ONLY the actual major infrastructure tokens
        // We want meme coins like "Baby Doge" or "Shiba Moon" - those are fine
        // We only block tokens pretending to BE Solana/Raydium/Jupiter etc.
        val infrastructureTokens = listOf(
            "solana", "wrapped sol", "wrapped solana",  // Core chain token
            "raydium", "jupiter", "jito", "pyth", "marinade", "orca",  // Solana infrastructure
            "pump", "pumpfun", "pump.fun"  // Pump.fun itself
        )
        
        val symLower = token.symbol.lowercase().trim()
        val nameLower = token.name.lowercase().trim()
        
        // Block exact matches of major token symbols only
        val blockedSymbols = listOf("sol", "wsol", "usdt", "usdc", "ray", "jup")
        if (symLower in blockedSymbols) {
            onLog("🚫 BLOCK: ${token.symbol} - reserved symbol")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: blocked symbol '$symLower'")
            return false
        }
        
        for (infra in infrastructureTokens) {
            // Only block exact name matches for infrastructure tokens
            if (nameLower == infra || nameLower.startsWith("$infra ")) {
                onLog("🚫 BLOCK: ${token.symbol} - impersonates $infra")
                ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: impersonates '$infra' (name='${token.name}')")
                return false
            }
        }
        
        // Block tokens with suspicious market caps (over $500M is likely fake data)
        if (token.mcapUsd > 500_000_000) {
            onLog("🚫 BLOCK: ${token.symbol} - fake mcap \$${(token.mcapUsd/1_000_000).toInt()}M")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: suspicious mcap $${(token.mcapUsd/1_000_000).toInt()}M")
            return false
        }
        
        // Token passed all filters!
        ErrorLogger.info("Scanner", "FILTER PASS ${token.symbol}: liq=$${token.liquidityUsd.toInt()} score=${token.score.toInt()}")
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
        
        // Check if rejected (1 hour cooldown)
        val rejectedAt = rejectedMints[mint]
        if (rejectedAt != null && now - rejectedAt < REJECTED_TTL) {
            return true  // Still in cooldown from rejection
        }
        
        // Check if recently seen (30 min cooldown)
        val seenAt = seenMints[mint]
        if (seenAt != null && now - seenAt < SEEN_TTL) {
            return true
        }
        
        // Check if already in watchlist (via config)
        val watchlist = cfg().watchlist
        if (mint in watchlist) {
            return true  // Already being tracked
        }
        
        return false
    }
    
    // Mark a token as rejected (longer cooldown than just "seen")
    private fun markRejected(mint: String) {
        rejectedMints[mint] = System.currentTimeMillis()
    }
    
    // Clean up old entries from seen/rejected maps periodically
    private fun cleanupSeenMaps() {
        val now = System.currentTimeMillis()
        seenMints.entries.removeIf { now - it.value > SEEN_TTL }
        rejectedMints.entries.removeIf { now - it.value > REJECTED_TTL }
    }

    private fun emit(token: ScannedToken) {
        seenMints[token.mint] = System.currentTimeMillis()
        onLog("🔍 Found: ${token.symbol} (${token.source.name}) " +
              "liq=$${(token.liquidityUsd/1000).toInt()}K " +
              "vol=$${(token.volumeH1/1000).toInt()}K " +
              "score=${token.score.toInt()}")
        onTokenFound(token.mint, token.symbol, token.name, token.source, token.score)
    }
    
    // Separate HTTP client for rugcheck with SHORT timeout
    private val rugcheckHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    
    /**
     * Quick rugcheck - returns immediately if API is slow
     * Only blocks on OBVIOUS rugs (very low scores)
     */
    private fun quickRugcheck(mint: String): Boolean {
        try {
            val url = "https://api.rugcheck.xyz/v1/tokens/$mint/report/summary"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            
            val resp = rugcheckHttp.newCall(request).execute()
            if (!resp.isSuccessful) return true  // Pass if API error
            
            val body = resp.body?.string() ?: return true
            val json = JSONObject(body)
            
            // Only block on VERY obvious rugs
            val scoreNormalized = json.optInt("score_normalised", 50)
            
            // Block if score < 10 (extremely risky)
            if (scoreNormalized < 10) {
                onLog("🚫 RUG: ${mint.take(8)}... score=$scoreNormalized")
                return false
            }
            
            return true  // Pass
            
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Timeout or error - pass through (don't block on API issues)
            return true
        }
    }
    
    /**
     * Emit with optional quick rugcheck
     * Non-blocking - if rugcheck is slow or fails, emit anyway
     */
    private suspend fun emitWithRugcheck(token: ScannedToken) {
        // Quick check with 2-second timeout - always pass on any error
        val passed = try {
            withContext(Dispatchers.IO) { 
                try {
                    withTimeout(2000L) { quickRugcheck(token.mint) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    true  // Timeout = pass through
                } catch (e: Exception) {
                    true  // Any error = pass through
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Re-throw job cancellation
        } catch (e: Exception) {
            true  // Any other error = pass through
        }
        
        if (!passed) {
            ErrorLogger.info("Scanner", "Rugcheck blocked ${token.symbol}")
            return
        }
        
        emit(token)
    }

    private fun get(url: String, apiKey: String = ""): String? = try {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("X-API-KEY", apiKey)
        ErrorLogger.debug("Scanner", "HTTP GET: ${url.take(60)}...")
        val resp = http.newCall(builder.build()).execute()
        if (resp.isSuccessful) {
            val body = resp.body?.string()
            ErrorLogger.debug("Scanner", "HTTP OK: ${body?.length ?: 0} bytes from ${url.take(40)}")
            body
        } else {
            ErrorLogger.warn("Scanner", "HTTP FAIL: ${resp.code} from ${url.take(50)}")
            null
        }
    } catch (e: Exception) { 
        ErrorLogger.error("Scanner", "HTTP ERROR: ${e.message} for ${url.take(50)}")
        null 
    }
}
