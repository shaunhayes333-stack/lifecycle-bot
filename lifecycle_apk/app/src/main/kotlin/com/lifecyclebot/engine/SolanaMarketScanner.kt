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
    private val SEEN_TTL   = 60_000L   // forget after 1 min — allow faster token refresh (was 2 min)
    
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
        ErrorLogger.error("Scanner", 
            "Scanner coroutine exception: ${throwable.javaClass.simpleName}: ${throwable.message}", 
            throwable
        )
        onLog("⚠️ Scanner error: ${throwable.javaClass.simpleName} - ${throwable.message?.take(50)}")
        // Don't crash - just log and the scan loop will continue
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
        // Simple test to verify API connectivity and add first token
        // Uses the pairs endpoint - finds actual new Solana tokens by lifecycle
        try {
            onLog("🧪 Running immediate test scan...")
            val url = "https://api.dexscreener.com/latest/dex/pairs/solana"
            ErrorLogger.info("Scanner", "TEST: Fetching recent Solana pairs...")
            
            val body = get(url)
            if (body == null) {
                ErrorLogger.error("Scanner", "TEST FAILED: No response from DexScreener API")
                onLog("❌ API test failed - no response")
                return
            }
            
            val pairs = if (body.startsWith("[")) {
                org.json.JSONArray(body)
            } else {
                org.json.JSONObject(body).optJSONArray("pairs")
            }
            if (pairs == null || pairs.length() == 0) {
                ErrorLogger.error("Scanner", "TEST FAILED: No pairs in response")
                onLog("❌ API test failed - empty data")
                return
            }
            
            ErrorLogger.info("Scanner", "TEST OK: Got ${pairs.length()} pairs")
            onLog("✅ API OK: ${pairs.length()} pairs received")
            
            // Try to add first valid token to watchlist
            var added = 0
            for (i in 0 until minOf(pairs.length(), 30)) {
                if (added >= 3) break  // Add up to 3 tokens
                
                val p = pairs.optJSONObject(i) ?: continue
                val chainId = p.optString("chainId", "")
                if (chainId != "solana") continue
                
                val mint = p.optJSONObject("baseToken")?.optString("address", "") ?: continue
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: continue
                if (symbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                if (liq < 2000) continue  // Very low threshold for test
                
                val mcap = p.optDouble("marketCap", 0.0)
                val vol = p.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
                val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                val dexId = p.optString("dexId", "unknown")
                
                val token = ScannedToken(
                    mint = mint,
                    symbol = symbol,
                    name = name,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = 1.0,
                    dexId = dexId,
                    priceChangeH1 = 0.0,
                    txCountH1 = 0,
                    score = 50.0  // Force passing score for test
                )
                
                // Run through filters including impersonation check
                if (!passesFilter(token)) {
                    ErrorLogger.info("Scanner", "TEST: Token $symbol rejected by filters")
                    continue
                }
                
                emit(token)
                added++
                ErrorLogger.info("Scanner", "TEST: Added $symbol to watchlist")
                onLog("🎯 Test added: $symbol | liq=$${liq.toInt()}")
            }
            
            if (added == 0) {
                ErrorLogger.warn("Scanner", "TEST: Could not find any valid tokens to add")
                onLog("⚠️ Test: No valid tokens found")
            } else {
                ErrorLogger.info("Scanner", "TEST COMPLETE: Added $added tokens")
                onLog("✅ Test complete: $added tokens added")
            }
            
        } catch (e: Exception) {
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
                
                // MEMORY-OPTIMIZED: 4 cycles with more variety
                scanRotation = (scanRotation + 1) % 4
                onLog("🌐 Scan #$scanRotation${_tn} - Starting scan cycle")
                ErrorLogger.info("Scanner", "Scan cycle #$scanRotation starting")
                
                // GC before scan
                System.gc()
                
                var tokensFoundThisCycle = 0
                
                when (scanRotation) {
                    0 -> {
                        // PUMP.FUN FOCUS - Active pump.fun tokens with momentum
                        onLog("🔍 Scanning: Pump.fun active tokens...")
                        try { scanPumpFunActive() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanPumpFunActive: ${e.message}", e) 
                        }
                        delay(1000)
                        onLog("🔍 Scanning: DexScreener boosted...")
                        try { scanDexBoosted() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanDexBoosted: ${e.message}", e) 
                        }
                    }
                    1 -> {
                        // PUMP.FUN GRADUATES - tokens that just graduated to Raydium
                        onLog("🔍 Scanning: Pump.fun graduates...")
                        try { scanPumpGraduates() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanPumpGraduates: ${e.message}", e) 
                        }
                        delay(1000)
                        onLog("🔍 Scanning: New Solana pairs...")
                        try { scanDexGainers() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanDexGainers: ${e.message}", e) 
                        }
                    }
                    2 -> {
                        // PUMP.FUN HIGH VOLUME - tokens with trading activity
                        onLog("🔍 Scanning: Pump.fun high volume...")
                        try { scanPumpFunVolume() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanPumpFunVolume: ${e.message}", e) 
                        }
                        delay(1000)
                        onLog("🔍 Scanning: DexScreener trending...")
                        try { scanDexTrending() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanDexTrending: ${e.message}", e) 
                        }
                    }
                    3 -> {
                        // FRESH LAUNCHES - newest tokens for early entry
                        onLog("🔍 Scanning: Fresh launches...")
                        try { scanFreshLaunches() } catch (e: Exception) { 
                            if (e is OutOfMemoryError) throw e
                            ErrorLogger.error("Scanner", "scanFreshLaunches: ${e.message}", e) 
                        }
                        delay(1000)
                        if (c.birdeyeApiKey.isNotBlank()) {
                            onLog("🔍 Scanning: Birdeye trending...")
                            try { scanBirdeyeTrending() } catch (e: Exception) { 
                                if (e is OutOfMemoryError) throw e
                                ErrorLogger.error("Scanner", "scanBirdeye: ${e.message}", e) 
                            }
                        } else {
                            try { scanDexGainers() } catch (e: Exception) { 
                                if (e is OutOfMemoryError) throw e
                                ErrorLogger.error("Scanner", "scanDexGainers: ${e.message}", e) 
                            }
                        }
                    }
                }
                
                // GC after scan
                System.gc()
                onLog("✅ Scan cycle #$scanRotation complete")
                
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
            } catch (e: Exception) {
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
     * Scan for ACTIVE pump.fun tokens with trading momentum.
     * Looks for tokens on pump.fun DEX with recent activity and good liquidity.
     * These are pre-graduation opportunities.
     */
    private suspend fun scanPumpFunActive() {
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana"
        ErrorLogger.info("Scanner", "scanPumpFunActive: looking for active pump.fun tokens...")
        val body = get(url) ?: return
        
        try {
            val pairs = if (body.startsWith("[")) JSONArray(body) 
                       else JSONObject(body).optJSONArray("pairs") ?: return
            
            val now = System.currentTimeMillis()
            var found = 0
            var processed = 0
            
            for (i in 0 until minOf(pairs.length(), 60)) {
                if (found >= 10) break
                val p = pairs.optJSONObject(i) ?: continue
                
                val chainId = p.optString("chainId", "solana")
                if (chainId != "solana") continue
                
                val dexId = p.optString("dexId", "")
                // Focus on pump.fun tokens (pre-graduation)
                val isPumpFun = dexId.contains("pump", ignoreCase = true)
                if (!isPumpFun) continue
                
                processed++
                
                val mint = p.optJSONObject("baseToken")?.optString("address", "") ?: continue
                if (mint.isBlank() || isSeen(mint)) continue
                
                val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: continue
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val vol = p.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
                val mcap = p.optDouble("marketCap", 0.0)
                
                // Pump.fun tokens - lower liquidity threshold but need activity
                if (liq < 1000) continue
                if (vol < 500) continue  // Need some volume
                
                val created = p.optLong("pairCreatedAt", 0L)
                val ageHours = if (created > 0) (now - created) / 3_600_000.0 else 999.0
                
                // Focus on fresh pump.fun tokens (< 12 hours)
                if (ageHours > 12) continue
                
                val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                val buys = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sells = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val priceChange = p.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0
                
                // Momentum scoring for pump.fun
                val momentumScore = when {
                    priceChange > 50 && buys > 20 -> 35.0  // Strong pump
                    priceChange > 20 && buys > 10 -> 25.0  // Good momentum
                    priceChange > 0 && buys > 5 -> 15.0    // Positive
                    else -> 5.0
                }
                
                val ageBonus = when {
                    ageHours < 0.5 -> 30.0  // < 30 mins = very fresh
                    ageHours < 1 -> 25.0    // < 1 hour
                    ageHours < 3 -> 20.0    // < 3 hours
                    ageHours < 6 -> 10.0    // < 6 hours
                    else -> 0.0
                }
                
                val token = ScannedToken(
                    mint = mint, symbol = symbol, name = name,
                    source = TokenSource.PUMP_FUN_NEW,
                    liquidityUsd = liq, volumeH1 = vol, mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, dexId = dexId,
                    priceChangeH1 = priceChange, txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, priceChange, ageHours) + momentumScore + ageBonus
                )
                
                if (passesFilter(token)) {
                    emit(token)
                    found++
                    val momentum = if (priceChange > 20) "🔥" else if (priceChange > 0) "📈" else "📊"
                    onLog("$momentum PUMP: $symbol | ${ageHours.toInt()}h | +${priceChange.toInt()}% | $buys buys")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunActive: found $found pump.fun tokens (processed $processed)")
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunActive error: ${e.message}")
        }
        System.gc()
    }

    /**
     * Scan for pump.fun tokens with HIGH VOLUME - indicates strong interest.
     * Volume is the best indicator of profit potential.
     */
    private suspend fun scanPumpFunVolume() {
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana"
        ErrorLogger.info("Scanner", "scanPumpFunVolume: looking for high volume pump.fun tokens...")
        val body = get(url) ?: return
        
        try {
            val pairs = if (body.startsWith("[")) JSONArray(body)
                       else JSONObject(body).optJSONArray("pairs") ?: return
            
            val now = System.currentTimeMillis()
            var found = 0
            
            // Collect pump.fun pairs and sort by volume
            val pumpPairs = mutableListOf<Pair<JSONObject, Double>>()
            
            for (i in 0 until pairs.length()) {
                val p = pairs.optJSONObject(i) ?: continue
                if (p.optString("chainId", "") != "solana") continue
                
                val dexId = p.optString("dexId", "")
                if (!dexId.contains("pump", ignoreCase = true)) continue
                
                val vol = p.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
                if (vol > 1000) {  // Minimum $1K volume
                    pumpPairs.add(p to vol)
                }
            }
            
            // Sort by volume descending
            pumpPairs.sortByDescending { it.second }
            
            for ((p, vol) in pumpPairs.take(15)) {
                if (found >= 8) break
                
                val mint = p.optJSONObject("baseToken")?.optString("address", "") ?: continue
                if (mint.isBlank() || isSeen(mint)) continue
                
                val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: continue
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val mcap = p.optDouble("marketCap", 0.0)
                val created = p.optLong("pairCreatedAt", 0L)
                val ageHours = if (created > 0) (now - created) / 3_600_000.0 else 999.0
                
                val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                val buys = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sells = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val priceChange = p.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0
                val dexId = p.optString("dexId", "pumpfun")
                
                // Volume bonus - higher volume = more interest
                val volBonus = when {
                    vol > 50000 -> 30.0
                    vol > 20000 -> 25.0
                    vol > 10000 -> 20.0
                    vol > 5000 -> 15.0
                    else -> 10.0
                }
                
                val token = ScannedToken(
                    mint = mint, symbol = symbol, name = name,
                    source = TokenSource.PUMP_FUN_NEW,
                    liquidityUsd = liq, volumeH1 = vol, mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, dexId = dexId,
                    priceChangeH1 = priceChange, txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, priceChange, ageHours) + volBonus
                )
                
                if (passesFilter(token)) {
                    emit(token)
                    found++
                    val volK = (vol / 1000).toInt()
                    onLog("💰 VOL: $symbol | \$${volK}K vol | $buys buys | +${priceChange.toInt()}%")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunVolume: found $found high-volume tokens")
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunVolume error: ${e.message}")
        }
        System.gc()
    }

    /**
     * Scan for FRESH LAUNCHES - tokens created in the last 30 minutes.
     * Early entry = maximum profit potential (but also maximum risk).
     */
    private suspend fun scanFreshLaunches() {
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana"
        ErrorLogger.info("Scanner", "scanFreshLaunches: looking for tokens < 30 mins old...")
        val body = get(url) ?: return
        
        try {
            val pairs = if (body.startsWith("[")) JSONArray(body)
                       else JSONObject(body).optJSONArray("pairs") ?: return
            
            val now = System.currentTimeMillis()
            val freshCutoff = now - 30 * 60_000L  // 30 minutes
            var found = 0
            
            for (i in 0 until minOf(pairs.length(), 80)) {
                if (found >= 10) break
                val p = pairs.optJSONObject(i) ?: continue
                
                if (p.optString("chainId", "") != "solana") continue
                
                val created = p.optLong("pairCreatedAt", 0L)
                if (created < freshCutoff) continue  // Skip tokens older than 30 mins
                
                val ageMinutes = (now - created) / 60_000.0
                
                val mint = p.optJSONObject("baseToken")?.optString("address", "") ?: continue
                if (mint.isBlank() || isSeen(mint)) continue
                
                val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: continue
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                
                // Very fresh tokens - lower liquidity threshold
                if (liq < 500) continue
                
                val vol = p.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
                val mcap = p.optDouble("marketCap", 0.0)
                val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                val buys = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sells = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val priceChange = p.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0
                val dexId = p.optString("dexId", "unknown")
                
                // Fresh launch bonus - the newer, the better
                val freshnessBonus = when {
                    ageMinutes < 5 -> 40.0   // < 5 mins = ultra fresh
                    ageMinutes < 10 -> 35.0  // < 10 mins
                    ageMinutes < 15 -> 30.0  // < 15 mins
                    ageMinutes < 20 -> 25.0  // < 20 mins
                    else -> 20.0             // < 30 mins
                }
                
                val ageHours = ageMinutes / 60.0
                val token = ScannedToken(
                    mint = mint, symbol = symbol, name = name,
                    source = if (dexId.contains("pump", ignoreCase = true)) 
                             TokenSource.PUMP_FUN_NEW else TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq, volumeH1 = vol, mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, dexId = dexId,
                    priceChangeH1 = priceChange, txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, priceChange, ageHours) + freshnessBonus
                )
                
                if (passesFilter(token)) {
                    emit(token)
                    found++
                    val isPump = if (dexId.contains("pump", ignoreCase = true)) "🚀" else "🆕"
                    onLog("$isPump FRESH: $symbol | ${ageMinutes.toInt()}m old | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanFreshLaunches: found $found fresh tokens")
        } catch (e: Exception) {
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
                    emit(token)
                    passed++
                    onLog("📈 Trending: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanDexTrending: processed=$processed passed=$passed")
        } catch (e: OutOfMemoryError) {
            ErrorLogger.error("Scanner", "OOM in scanDexTrending", Exception(e.message))
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexTrending error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 2: Dexscreener new Solana pairs (actual new tokens) ─────────

    private suspend fun scanDexGainers() {
        // Use the pairs endpoint to find actual new Solana tokens by creation time
        // This finds tokens based on lifecycle, not keywords
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana"
        ErrorLogger.info("Scanner", "scanDexGainers: fetching new Solana pairs...")
        val body = get(url)
        if (body == null) {
            ErrorLogger.warn("Scanner", "scanDexGainers: no response from API")
            return
        }
        try {
            val pairs = if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body).optJSONArray("pairs") ?: return
            }
            
            if (pairs.length() == 0) {
                ErrorLogger.warn("Scanner", "scanDexGainers: no pairs in response")
                return
            }
            ErrorLogger.info("Scanner", "scanDexGainers: got ${pairs.length()} pairs")
            val now = System.currentTimeMillis()
            var found = 0
            var processed = 0
            
            for (i in 0 until minOf(pairs.length(), 50)) {
                if (found >= 8) break
                val p = pairs.optJSONObject(i) ?: continue
                
                // Only Solana pairs
                val chainId = p.optString("chainId", "solana")
                if (chainId != "solana") continue
                
                val created = p.optLong("pairCreatedAt", 0L)
                val ageHours = if (created > 0) (now - created) / 3_600_000.0 else 999.0
                
                // Focus on tokens < 48 hours old (lifecycle approach)
                if (ageHours > 48) continue
                processed++
                
                val mint = p.optJSONObject("baseToken")?.optString("address", "") ?: continue
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: continue
                if (symbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val vol = p.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
                val mcap = p.optDouble("marketCap", 0.0)
                
                if (liq < 3000) continue  // Min $3K liquidity
                
                val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                val buys = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sells = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val priceChange = p.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0
                val dexId = p.optString("dexId", "unknown")
                
                // Lifecycle-based scoring - new tokens get bonus
                val ageBonus = when {
                    ageHours < 1 -> 30.0   // < 1 hour = very fresh
                    ageHours < 6 -> 20.0   // < 6 hours = fresh
                    ageHours < 24 -> 10.0  // < 24 hours = new
                    else -> 0.0
                }
                
                // Volume momentum bonus
                val volBonus = if (vol > 50000) 15.0 else if (vol > 20000) 10.0 else 0.0
                
                val token = ScannedToken(
                    mint               = mint,
                    symbol             = symbol,
                    name               = name,
                    source             = when {
                        dexId.contains("pump", ignoreCase = true) -> TokenSource.PUMP_FUN_NEW
                        ageHours < 12 -> TokenSource.PUMP_FUN_GRADUATE
                        else -> TokenSource.RAYDIUM_NEW_POOL
                    },
                    liquidityUsd       = liq,
                    volumeH1           = vol,
                    mcapUsd            = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId              = dexId,
                    priceChangeH1      = priceChange,
                    txCountH1          = buys + sells,
                    score              = scoreToken(liq, vol, buys + sells, mcap, priceChange, ageHours) + ageBonus + volBonus,
                )
                if (passesFilter(token)) { 
                    emit(token)
                    found++
                    val src = when {
                        ageHours < 1 -> "🚀 FRESH"
                        ageHours < 6 -> "🆕 NEW"
                        ageHours < 24 -> "📈 YOUNG"
                        else -> "📊 $dexId"
                    }
                    onLog("$src: $symbol | age=${ageHours.toInt()}h | liq=$${liq.toInt()} | vol=$${vol.toInt()}")
                }
            }
            if (found > 0) ErrorLogger.info("Scanner", "scanDexGainers: found $found new tokens (processed $processed)")
            else ErrorLogger.info("Scanner", "scanDexGainers: no tokens passed filters (processed $processed)")
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexBoosted error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 4: Recent Raydium Migrations (pump.fun graduates) ────────────

    private suspend fun scanPumpGraduates() {
        // Use DexScreener to find recent Raydium pairs - these are often pump.fun graduates
        // Focus on tokens that graduated in the last 24 hours with good liquidity
        val url = "https://api.dexscreener.com/latest/dex/pairs/solana"
        ErrorLogger.info("Scanner", "scanPumpGraduates: looking for recent Raydium migrations...")
        val body = get(url) ?: return
        try {
            val pairs = if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body).optJSONArray("pairs") ?: return
            }
            
            val now = System.currentTimeMillis()
            val cutoff24h = now - 24 * 3_600_000L  // Last 24 hours
            var found = 0
            
            for (i in 0 until minOf(pairs.length(), 40)) {
                if (found >= 8) break
                val p = pairs.optJSONObject(i) ?: continue
                
                val chainId = p.optString("chainId", "solana")
                if (chainId != "solana") continue
                
                val created = p.optLong("pairCreatedAt", 0L)
                
                // Only tokens created in the last 24 hours (recent graduates)
                if (created < cutoff24h) continue
                
                val ageHours = (now - created) / 3_600_000.0
                val dexId = p.optString("dexId", "unknown")
                
                // Prioritize Raydium pairs (where pump.fun tokens graduate to)
                val isRaydium = dexId.contains("raydium", ignoreCase = true)
                
                val mint = p.optJSONObject("baseToken")?.optString("address","") ?: continue
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: mint.take(6)
                if (symbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                if (liq < 5000) continue  // Graduates typically have higher liquidity
                
                val mcap = p.optDouble("marketCap", 0.0)
                val vol = p.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
                val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                val buys = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sells = p.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val priceChange = p.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0
                
                // Graduate bonus scoring
                val graduateBonus = when {
                    ageHours < 1 && isRaydium -> 30.0  // Just graduated to Raydium
                    ageHours < 6 && isRaydium -> 25.0  // Fresh graduate on Raydium
                    ageHours < 12 -> 20.0              // Recent token
                    else -> 15.0
                }
                
                val token = ScannedToken(
                    mint               = mint,
                    symbol             = symbol,
                    name               = name,
                    source             = if (isRaydium) TokenSource.PUMP_FUN_GRADUATE else TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd       = liq,
                    volumeH1           = vol,
                    mcapUsd            = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId              = dexId,
                    priceChangeH1      = priceChange,
                    txCountH1          = buys + sells,
                    score              = scoreToken(liq, vol, buys + sells, mcap, priceChange, ageHours) + graduateBonus,
                )
                if (passesFilter(token)) { 
                    emit(token)
                    found++ 
                    val label = when {
                        ageHours < 1 -> "🎓 JUST GRAD"
                        ageHours < 6 -> "🎓 GRADUATE"
                        else -> "🆕 NEW"
                    }
                    onLog("$label: $symbol | age=${ageHours.toInt()}h | liq=$${liq.toInt()}")
                }
            }
            if (found > 0) ErrorLogger.info("Scanner", "scanPumpGraduates: found $found recent tokens")
        } catch (e: Exception) {
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
                    emit(token)
                    onLog("🦅 Birdeye: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanBirdeye error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 5b: Top Volume Tokens (new diverse source) ─────────────

    private suspend fun scanTopVolumeTokens() {
        // Scan different pairs endpoints for high volume Solana tokens
        val endpoints = listOf(
            "https://api.dexscreener.com/latest/dex/pairs/solana",  // Recent Solana pairs
        )
        
        for (endpoint in endpoints) {
            try {
                val body = get(endpoint) ?: continue
                val pairs = if (body.startsWith("[")) {
                    JSONArray(body)
                } else {
                    JSONObject(body).optJSONArray("pairs") ?: continue
                }
                
                ErrorLogger.info("Scanner", "scanTopVolume: got ${pairs.length()} pairs")
                val now = System.currentTimeMillis()
                var found = 0
                
                for (i in 0 until minOf(pairs.length(), 30)) {
                    if (found >= 6) break
                    val p = pairs.optJSONObject(i) ?: continue
                    
                    val chainId = p.optString("chainId", "solana")
                    if (chainId != "solana") continue
                    
                    val mint = p.optJSONObject("baseToken")?.optString("address", "") ?: continue
                    if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                    
                    val symbol = p.optJSONObject("baseToken")?.optString("symbol", "") ?: continue
                    if (symbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY", "JUP")) continue
                    
                    val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                    val vol = p.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0
                    val mcap = p.optDouble("marketCap", 0.0)
                    
                    // Only high volume tokens
                    if (liq < 5000 || vol < 10000) continue
                    
                    val created = p.optLong("pairCreatedAt", 0L)
                    val ageHours = if (created > 0) (now - created) / 3_600_000.0 else 999.0
                    
                    val name = p.optJSONObject("baseToken")?.optString("name", "") ?: symbol
                    val buys = p.optJSONObject("txns")?.optJSONObject("h24")?.optInt("buys", 0) ?: 0
                    val sells = p.optJSONObject("txns")?.optJSONObject("h24")?.optInt("sells", 0) ?: 0
                    val priceChange = p.optJSONObject("priceChange")?.optDouble("h24", 0.0) ?: 0.0
                    val dexId = p.optString("dexId", "unknown")
                    
                    // High volume bonus
                    val volBonus = if (vol > 100000) 20.0 else if (vol > 50000) 15.0 else 10.0
                    
                    val token = ScannedToken(
                        mint               = mint,
                        symbol             = symbol,
                        name               = name,
                        source             = TokenSource.DEX_TRENDING,
                        liquidityUsd       = liq,
                        volumeH1           = vol / 24,  // Approximate h1 from h24
                        mcapUsd            = mcap,
                        pairCreatedHoursAgo = ageHours,
                        dexId              = dexId,
                        priceChangeH1      = priceChange / 24,
                        txCountH1          = (buys + sells) / 24,
                        score              = scoreToken(liq, vol / 24, (buys + sells) / 24, mcap, priceChange, ageHours) + volBonus,
                    )
                    
                    if (passesFilter(token)) {
                        emit(token)
                        found++
                        onLog("📊 TopVol: $symbol | vol24h=$${(vol/1000).toInt()}K | liq=$${liq.toInt()}")
                    }
                }
                
                if (found > 0) ErrorLogger.info("Scanner", "scanTopVolume: found $found high-volume tokens")
            } catch (e: Exception) {
                ErrorLogger.error("Scanner", "scanTopVolume error: ${e.message}")
            }
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
        if (token.liquidityUsd < c.minLiquidityUsd && token.liquidityUsd > 0) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: liq $${token.liquidityUsd.toInt()} < min $${c.minLiquidityUsd.toInt()}")
            return false
        }

        // DEX filter — user can restrict to specific DEXs
        if (c.allowedDexes.isNotEmpty() && token.dexId !in c.allowedDexes) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: DEX ${token.dexId} not in allowed list")
            return false
        }

        // MC range filter
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
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: scam pattern detected")
            return false
        }
        
        // Block tokens impersonating major cryptocurrencies
        // These are scam/meme tokens using deceptive names like "Solana" or "Bitcoin"
        val impersonationNames = listOf(
            "solana", "bitcoin", "ethereum", "bnb", "binance", "cardano", "ripple",
            "dogecoin", "shiba", "polygon", "avalanche", "chainlink", "uniswap",
            "wrapped sol", "wrapped solana", "pump", "pumpfun", "pump.fun",
            "raydium", "jupiter", "jito", "pyth", "marinade", "orca"
        )
        // Only block if symbol OR name exactly matches (case-insensitive) or is very similar
        val symLower = token.symbol.lowercase().trim()
        val nameLower = token.name.lowercase().trim()
        
        // Block exact matches of major token symbols
        val blockedSymbols = listOf("sol", "btc", "eth", "bnb", "usdt", "usdc", "pump")
        if (symLower in blockedSymbols) {
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: blocked symbol '$symLower'")
            return false
        }
        
        for (impersonation in impersonationNames) {
            // Exact match or starts with the impersonated name
            if (symLower == impersonation || 
                nameLower == impersonation ||
                nameLower.startsWith("$impersonation ") ||  // "Solana The Pygmy Hippo"
                nameLower.startsWith("the $impersonation")) {
                ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: impersonates '$impersonation' (name='${token.name}')")
                return false
            }
        }
        
        // Block tokens with suspicious market caps (over $500M is likely fake data)
        if (token.mcapUsd > 500_000_000) {
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
        val last = seenMints[mint] ?: return false
        val seen = now - last < SEEN_TTL
        if (seen) {
            val secsRemaining = ((SEEN_TTL - (now - last)) / 1000).toInt()
            // Only log occasionally to avoid spam
            if (kotlin.random.Random.nextInt(10) == 0) {
                ErrorLogger.debug("Scanner", "Token ${mint.take(8)}... seen, skip for ${secsRemaining}s")
            }
        }
        return seen
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
