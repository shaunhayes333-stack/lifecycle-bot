package com.lifecyclebot.engine

import android.content.Context
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
 * ScannerLearning — Tracks which sources/characteristics produce winners
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * Learns over time:
 *  - Which TokenSource produces most winners
 *  - Which liquidity ranges perform best
 *  - Which age profiles (fresh vs established) win more
 *  - Which mcap ranges are optimal
 *
 * Used to boost/penalize discovery scores based on historical performance.
 */
object ScannerLearning {
    private const val PREFS_NAME = "scanner_learning"
    private var ctx: Context? = null
    
    // Track wins/losses by source
    private val sourceWins = ConcurrentHashMap<String, Int>()
    private val sourceLosses = ConcurrentHashMap<String, Int>()
    
    // Track by liquidity bucket (0-5k, 5k-20k, 20k-100k, 100k+)
    private val liqBucketWins = ConcurrentHashMap<String, Int>()
    private val liqBucketLosses = ConcurrentHashMap<String, Int>()
    
    // Track by age bucket (0-1h, 1-6h, 6-24h, 24h+)
    private val ageBucketWins = ConcurrentHashMap<String, Int>()
    private val ageBucketLosses = ConcurrentHashMap<String, Int>()
    
    // Initialize with context and load saved state
    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info("ScannerLearning", "📊 Loaded state: ${getStats()}")
    }
    
    fun recordTrade(source: String, liqUsd: Double, ageHours: Double, isWin: Boolean) {
        // Source tracking
        if (isWin) sourceWins.merge(source, 1) { a, b -> a + b }
        else sourceLosses.merge(source, 1) { a, b -> a + b }
        
        // Liquidity bucket tracking
        val liqBucket = when {
            liqUsd < 5_000 -> "liq_0_5k"
            liqUsd < 20_000 -> "liq_5k_20k"
            liqUsd < 100_000 -> "liq_20k_100k"
            else -> "liq_100k_plus"
        }
        if (isWin) liqBucketWins.merge(liqBucket, 1) { a, b -> a + b }
        else liqBucketLosses.merge(liqBucket, 1) { a, b -> a + b }
        
        // Age bucket tracking
        val ageBucket = when {
            ageHours < 1 -> "age_0_1h"
            ageHours < 6 -> "age_1_6h"
            ageHours < 24 -> "age_6_24h"
            else -> "age_24h_plus"
        }
        if (isWin) ageBucketWins.merge(ageBucket, 1) { a, b -> a + b }
        else ageBucketLosses.merge(ageBucket, 1) { a, b -> a + b }
        
        // Save after each trade
        save()
        
        ErrorLogger.info("ScannerLearning", "📊 Recorded ${if (isWin) "WIN" else "LOSS"}: src=$source liq=$liqBucket age=$ageBucket")
    }
    
    // Get win rate for a source (0.0 to 1.0)
    fun getSourceWinRate(source: String): Double {
        val wins = sourceWins[source] ?: 0
        val losses = sourceLosses[source] ?: 0
        val total = wins + losses
        return if (total >= 5) wins.toDouble() / total else 0.5  // Default 50% until enough data
    }
    
    // Get score bonus/penalty based on historical performance
    fun getDiscoveryBonus(source: String, liqUsd: Double, ageHours: Double): Double {
        var bonus = 0.0
        
        // Source-based bonus (-10 to +10)
        val srcRate = getSourceWinRate(source)
        bonus += (srcRate - 0.5) * 20.0  // 60% win rate = +2, 40% = -2
        
        // Liquidity-based bonus
        val liqBucket = when {
            liqUsd < 5_000 -> "liq_0_5k"
            liqUsd < 20_000 -> "liq_5k_20k"
            liqUsd < 100_000 -> "liq_20k_100k"
            else -> "liq_100k_plus"
        }
        val liqWins = liqBucketWins[liqBucket] ?: 0
        val liqLosses = liqBucketLosses[liqBucket] ?: 0
        val liqTotal = liqWins + liqLosses
        if (liqTotal >= 5) {
            val liqRate = liqWins.toDouble() / liqTotal
            bonus += (liqRate - 0.5) * 15.0
        }
        
        // Age-based bonus
        val ageBucket = when {
            ageHours < 1 -> "age_0_1h"
            ageHours < 6 -> "age_1_6h"
            ageHours < 24 -> "age_6_24h"
            else -> "age_24h_plus"
        }
        val ageWins = ageBucketWins[ageBucket] ?: 0
        val ageLosses = ageBucketLosses[ageBucket] ?: 0
        val ageTotal = ageWins + ageLosses
        if (ageTotal >= 5) {
            val ageRate = ageWins.toDouble() / ageTotal
            bonus += (ageRate - 0.5) * 15.0
        }
        
        return bonus.coerceIn(-20.0, 20.0)
    }
    
    fun getStats(): String {
        val sources = (sourceWins.keys + sourceLosses.keys).distinct()
        val stats = sources.map { src ->
            val w = sourceWins[src] ?: 0
            val l = sourceLosses[src] ?: 0
            val rate = if (w + l > 0) (w * 100 / (w + l)) else 50
            "$src: ${w}W/${l}L ($rate%)"
        }
        return "ScannerLearning: ${stats.joinToString(" | ")}"
    }
    
    // ── Persistence ─────────────────────────────────────────────────────
    
    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("sourceWins", mapToJson(sourceWins))
                putString("sourceLosses", mapToJson(sourceLosses))
                putString("liqBucketWins", mapToJson(liqBucketWins))
                putString("liqBucketLosses", mapToJson(liqBucketLosses))
                putString("ageBucketWins", mapToJson(ageBucketWins))
                putString("ageBucketLosses", mapToJson(ageBucketLosses))
                apply()
            }
        } catch (_: Exception) {}
    }
    
    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            jsonToMap(prefs.getString("sourceWins", null)).forEach { (k, v) -> sourceWins[k] = v }
            jsonToMap(prefs.getString("sourceLosses", null)).forEach { (k, v) -> sourceLosses[k] = v }
            jsonToMap(prefs.getString("liqBucketWins", null)).forEach { (k, v) -> liqBucketWins[k] = v }
            jsonToMap(prefs.getString("liqBucketLosses", null)).forEach { (k, v) -> liqBucketLosses[k] = v }
            jsonToMap(prefs.getString("ageBucketWins", null)).forEach { (k, v) -> ageBucketWins[k] = v }
            jsonToMap(prefs.getString("ageBucketLosses", null)).forEach { (k, v) -> ageBucketLosses[k] = v }
        } catch (_: Exception) {}
    }
    
    private fun mapToJson(map: Map<String, Int>): String {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
    
    private fun jsonToMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optInt(it, 0) }
        } catch (_: Exception) { emptyMap() }
    }
}

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
                               source: TokenSource, score: Double, liquidityUsd: Double) -> Unit,
    private val onLog: (String) -> Unit,
    private val getBrain: () -> BotBrain? = { null },  // AI learning integration
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
        .connectTimeout(10, TimeUnit.SECONDS)  // Increased from 8s
        .readTimeout(15, TimeUnit.SECONDS)     // Increased from 10s for slow APIs
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
    private val SEEN_TTL   = 15_000L          // forget after 15 sec — ULTRA fast refresh
    
    // Track rejected tokens separately - very short cooldown for paper mode learning
    private val rejectedMints = ConcurrentHashMap<String, Long>()
    private val REJECTED_TTL = 30_000L        // forget rejected tokens after 30 sec
    
    // Memory protection: limit concurrent operations
    private val semaphore = kotlinx.coroutines.sync.Semaphore(3)  // max 3 concurrent scans
    
    // Memory-safe mode - enables when OOM detected
    @Volatile private var memorySafeMode = false
    @Volatile private var oomCount = 0
    
    // Scan rotation - alternate between different scan sources for variety
    @Volatile private var scanRotation = 0
    
    // Running state
    @Volatile private var isRunning = false
    
    // Public status for debugging
    fun getStatus(): String {
        return "Scanner: running=$isRunning seenMints=${seenMints.size} rejectedMints=${rejectedMints.size} scanRotation=$scanRotation"
    }
    
    // Force clear all maps (emergency reset)
    fun forceReset() {
        seenMints.clear()
        rejectedMints.clear()
        scanRotation = 0
        ErrorLogger.info("Scanner", "Force reset - cleared all maps")
        onLog("🔄 Scanner reset - maps cleared")
    }
    
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
            for (i in 0 until minOf(profiles.length(), 50)) {
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
            // Use configured interval, minimum 10 seconds for fast scanning
            val scanIntervalMs = maxOf((c.scanIntervalSecs * 1000L).toLong(), 10_000L)
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
                scanRotation = (scanRotation + 1) % 4  // 4 rotations for more variety
                
                // FORCE RESET every 20 cycles to prevent scanner staleness
                if (scanRotation == 0 && seenMints.size > 50) {
                    val resetCount = seenMints.size
                    seenMints.clear()
                    rejectedMints.entries.removeIf { System.currentTimeMillis() - it.value > 60_000 }  // Keep only recent rejects
                    onLog("🔄 Scanner refresh: cleared $resetCount seen tokens")
                    ErrorLogger.info("Scanner", "Forced refresh: cleared $resetCount seen mints")
                }
                
                onLog("🌐 Scan #$scanRotation${_tn} - Starting scan cycle")
                ErrorLogger.info("Scanner", "Scan cycle #$scanRotation starting")
                
                // GC before scan
                System.gc()
                
                var tokensFoundThisCycle = 0
                val isPaperMode = cfg().paperMode
                
                // ALWAYS scan pump.fun first (priority) - BOTH direct API and profiles
                onLog("🚀 Scanning: Pump.fun tokens (PRIORITY)...")
                runScan("scanPumpFunDirect") { scanPumpFunDirect() }  // Direct pump.fun API
                delay(200)
                runScan("scanPumpFunActive") { scanPumpFunActive() }  // DexScreener profiles
                delay(200)
                
                // PAPER MODE: Scan ALL sources every cycle for maximum learning
                if (isPaperMode) {
                    onLog("📚 PAPER MODE: Scanning ALL sources...")
                    runScan("scanPumpGraduates") { scanPumpGraduates() }
                    delay(150)
                    runScan("scanDexBoosted") { scanDexBoosted() }
                    delay(150)
                    runScan("scanFreshLaunches") { scanFreshLaunches() }
                    delay(150)
                    runScan("scanDexTrending") { scanDexTrending() }
                    delay(150)
                    runScan("scanDexGainers") { scanDexGainers() }
                } else {
                    // REAL MODE: Rotate through secondary sources
                    when (scanRotation) {
                        0 -> {
                            // Pump.fun graduates + boosted
                            onLog("🔍 Scanning: Pump.fun graduates...")
                            runScan("scanPumpGraduates") { scanPumpGraduates() }
                            delay(200)
                            onLog("🔍 Scanning: DexScreener boosted...")
                            runScan("scanDexBoosted") { scanDexBoosted() }
                        }
                        1 -> {
                            // Fresh launches + trending
                            onLog("🔍 Scanning: Fresh launches...")
                            runScan("scanFreshLaunches") { scanFreshLaunches() }
                            delay(200)
                            onLog("🔍 Scanning: DexScreener trending...")
                            runScan("scanDexTrending") { scanDexTrending() }
                        }
                        2 -> {
                            // Volume + gainers
                            onLog("🔍 Scanning: Pump.fun high volume...")
                            runScan("scanPumpFunVolume") { scanPumpFunVolume() }
                            delay(200)
                            onLog("🔍 Scanning: New Solana pairs...")
                            runScan("scanDexGainers") { scanDexGainers() }
                        }
                        3 -> {
                            // Different combo - boosted + fresh
                            onLog("🔍 Scanning: DexScreener boosted...")
                            runScan("scanDexBoosted") { scanDexBoosted() }
                            delay(200)
                            onLog("🔍 Scanning: Fresh profiles...")
                            runScan("scanFreshLaunches") { scanFreshLaunches() }
                        }
                    }
                }
                
                // GC after scan
                System.gc()
                onLog("✅ Scan cycle #$scanRotation complete")
                
                // Clean up old seen/rejected entries every cycle
                cleanupSeenMaps()
                
                // Log map sizes every cycle for debugging discovery issues
                val watchlistSize = cfg().watchlist.size
                if (scanRotation == 0) {
                    ErrorLogger.info("Scanner", "Discovery health: seen=${seenMints.size} rejected=${rejectedMints.size} watchlist=$watchlistSize")
                    onLog("📊 Discovery: seen=${seenMints.size} rejected=${rejectedMints.size} watchlist=$watchlistSize")
                }
                
                // AUTO-RECOVER: If watchlist is empty or very small, force scanner refresh
                if (watchlistSize <= 2 && seenMints.size > 30) {
                    onLog("⚠️ Watchlist depleted - forcing scanner refresh")
                    ErrorLogger.warn("Scanner", "Auto-recovery: watchlist=$watchlistSize, clearing ${seenMints.size} seen mints")
                    seenMints.clear()
                    rejectedMints.clear()
                }
                
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
            
            for (i in 0 until minOf(profiles.length(), 50)) {
                if (found >= 15) break
                
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
     * DIRECT PUMP.FUN SCAN - Fetch newest tokens directly from pump.fun API
     * This is the PRIMARY source for early pump.fun entries
     */
    private suspend fun scanPumpFunDirect() {
        // Pump.fun API endpoints - try multiple in case some are blocked/changed
        val urls = listOf(
            // Primary: newest coins sorted by creation
            "https://frontend-api.pump.fun/coins?offset=0&limit=50&sort=created_timestamp&order=DESC&includeNsfw=false",
            // Alternative: recently updated/active
            "https://frontend-api.pump.fun/coins?offset=0&limit=30&sort=last_trade_timestamp&order=DESC&includeNsfw=false",
        )
        
        ErrorLogger.info("Scanner", "scanPumpFunDirect: fetching from pump.fun APIs...")
        
        var totalFound = 0
        
        for (url in urls) {
            try {
                val body = get(url) ?: continue
                
                // Parse response - could be array or object with coins property
                val coins: JSONArray = when {
                    body.startsWith("[") -> JSONArray(body)
                    body.startsWith("{") -> {
                        val obj = JSONObject(body)
                        obj.optJSONArray("coins") ?: obj.optJSONArray("data") ?: continue
                    }
                    else -> continue
                }
                
                val now = System.currentTimeMillis()
                var found = 0
                
                for (i in 0 until minOf(coins.length(), 30)) {
                    if (found >= 10) break
                    
                    val coin = coins.optJSONObject(i) ?: continue
                    
                    // Pump.fun coin structure
                    val mint = coin.optString("mint", "")
                        .ifBlank { coin.optString("address", "") }
                        .ifBlank { coin.optString("token_address", "") }
                    
                    if (mint.isBlank() || isSeen(mint)) continue
                    
                    val symbol = coin.optString("symbol", "")
                        .ifBlank { coin.optString("ticker", "") }
                    val name = coin.optString("name", "")
                    
                    if (symbol.isBlank()) continue
                    
                    // Get additional data from DexScreener for liquidity/volume
                    val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }
                    
                    val liq = pair?.liquidity ?: coin.optDouble("usd_market_cap", 0.0) * 0.1
                    val mcap = pair?.candle?.marketCap ?: coin.optDouble("usd_market_cap", 0.0)
                    val vol = pair?.candle?.volumeH1 ?: 0.0
                    
                    // Calculate age from created_timestamp
                    val createdTs = coin.optLong("created_timestamp", 0L)
                    val ageHours = if (createdTs > 0) {
                        (now - createdTs) / 3_600_000.0
                    } else {
                        (pair?.pairCreatedAtMs?.let { (now - it) / 3_600_000.0 }) ?: 24.0
                    }
                    
                    // Skip very old tokens (focus on fresh ones)
                    if (ageHours > 48) continue
                    
                    // Pump.fun specific bonus - these are the tokens we want!
                    val pumpBonus = when {
                        ageHours < 0.25 -> 50.0  // < 15 mins = VERY fresh
                        ageHours < 0.5 -> 45.0   // < 30 mins
                        ageHours < 1 -> 40.0     // < 1 hour
                        ageHours < 3 -> 30.0     // < 3 hours
                        ageHours < 6 -> 20.0     // < 6 hours
                        else -> 10.0
                    }
                    
                    val token = ScannedToken(
                        mint = mint,
                        symbol = symbol,
                        name = name,
                        source = TokenSource.PUMP_FUN_NEW,
                        liquidityUsd = liq,
                        volumeH1 = vol,
                        mcapUsd = mcap,
                        pairCreatedHoursAgo = ageHours,
                        dexId = "pump.fun",
                        priceChangeH1 = 0.0,
                        txCountH1 = pair?.candle?.let { it.buysH1 + it.sellsH1 } ?: 0,
                        score = scoreToken(liq, vol, 0, mcap, 0.0, ageHours) + pumpBonus
                    )
                    
                    if (passesFilter(token)) {
                        emitWithRugcheck(token)
                        found++
                        totalFound++
                        val freshIcon = when {
                            ageHours < 0.25 -> "🔥"
                            ageHours < 1 -> "🆕"
                            ageHours < 6 -> "📈"
                            else -> "📊"
                        }
                        onLog("$freshIcon PUMP: $symbol | ${(ageHours * 60).toInt()}m old | mcap=\$${mcap.toInt()}")
                    }
                }
                
                ErrorLogger.info("Scanner", "scanPumpFunDirect: found $found from ${url.take(50)}...")
                delay(100)  // Small delay between API calls
                
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ErrorLogger.warn("Scanner", "scanPumpFunDirect error: ${e.message}")
            }
        }
        
        if (totalFound > 0) {
            onLog("🚀 Pump.fun direct: $totalFound new tokens found")
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
            
            for (i in 0 until minOf(boosted.length(), 50)) {
                if (found >= 15) break
                
                val item = boosted.optJSONObject(i) ?: continue
                
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanPumpFunVolume: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                if (liq < 1000) continue  // Boosted tokens should have some liquidity
                
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
                if (found >= 15) break
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
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 500 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanFreshLaunches: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
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
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanDexTrending: fetching from DexScreener...")
        val body = get(url)
        if (body == null) {
            ErrorLogger.warn("Scanner", "scanDexTrending: no response from API")
            return
        }
        try {
            // Validate JSON array format
            if (!body.trim().startsWith("[")) {
                ErrorLogger.warn("Scanner", "scanDexTrending: invalid response format")
                return
            }
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
                
                // If DexScreener returned $0 liquidity, try Birdeye as fallback
                // Then try using FDV/mcap as a proxy (typical ratio is 10-20% of mcap)
                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    // Try Birdeye first
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0
                    
                    if (fallbackLiq > 0) {
                        ErrorLogger.info("Scanner", "scanDexTrending: ${pair.baseSymbol} used Birdeye liq=\$${fallbackLiq.toInt()}")
                    } else if (pair.fdv > 0 || pair.candle.marketCap > 0) {
                        // Estimate liquidity as ~10% of FDV/mcap (conservative estimate)
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = mcap * 0.10
                        if (fallbackLiq > 1000) {  // Only use if meaningful
                            ErrorLogger.info("Scanner", "scanDexTrending: ${pair.baseSymbol} estimated liq=\$${fallbackLiq.toInt()} from mcap=\$${mcap.toInt()}")
                        } else {
                            fallbackLiq = 0.0  // Too small, skip
                        }
                    }
                }
                
                val token = buildScannedToken(mint, pair, TokenSource.DEX_TRENDING, fallbackLiq) ?: continue
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
                if (found >= 15) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanDexGainers: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                if (liq < 1000) continue  // Min $1K liquidity
                
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
            // Validate JSON array format
            if (!body.trim().startsWith("[")) {
                ErrorLogger.warn("Scanner", "scanDexBoosted: invalid response format")
                return
            }
            val arr = JSONArray(body)
            for (i in 0 until minOf(arr.length(), 12)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress","")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip only stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0
                    
                    if (fallbackLiq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                    }
                }
                
                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                val token = buildScannedToken(mint, pair, TokenSource.DEX_BOOSTED, fallbackLiq) ?: continue
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
            
            for (i in 0 until minOf(boosted.length(), 50)) {
                if (found >= 15) break
                
                val item = boosted.optJSONObject(i) ?: continue
                
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1500 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanPumpGraduates: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                if (liq < 1500) continue  // Graduates should have decent liquidity
                
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
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0
                    
                    if (fallbackLiq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                    }
                }
                
                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                val token = buildScannedToken(mint, pair, TokenSource.BIRDEYE_TRENDING, fallbackLiq) ?: continue
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
            
            for (i in 0 until minOf(profiles.length(), 50)) {
                if (found >= 15) break
                
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
                if (liq < 1000) continue
                
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
    
    /**
     * Get AI-driven boost for a source based on historical win rates.
     * Returns a score adjustment (-20 to +20) based on how well this source has performed.
     */
    private fun getAISourceBoost(source: TokenSource): Double {
        val brain = getBrain() ?: return 0.0
        val sourceName = source.name
        val boost = brain.getSourceBoost(sourceName)
        
        // Scale the boost for discovery scoring
        return when {
            boost >= 15.0 -> 15.0   // Highly profitable source — prioritize
            boost >= 10.0 -> 10.0
            boost >= 5.0 -> 5.0
            boost <= -15.0 -> -15.0  // Consistently losing source — deprioritize
            boost <= -10.0 -> -10.0
            boost <= -5.0 -> -5.0
            else -> 0.0
        }
    }
    
    /**
     * Get AI-driven risk check for a token based on TradingMemory.
     * Returns true if the token should be skipped.
     */
    private fun aiShouldSkipToken(mint: String, symbol: String, liquidity: Double, mcap: Double): Boolean {
        // Check TradingMemory for past losses on this token
        val tokenHistory = TradingMemory.getTokenLossHistory(mint)
        if (tokenHistory != null && tokenHistory.lossCount >= 2) {
            onLog("🤖 AI SKIP: $symbol — ${tokenHistory.lossCount} prior losses")
            return true
        }
        
        // Check for creator blacklist (if available via TradingMemory)
        // Note: Would need creator wallet to check, but we don't have it in scanner
        
        return false
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
        val isPaperMode = c.paperMode

        // ═══════════════════════════════════════════════════════════════════
        // PAPER MODE: Skip permanent ban check - we want to trade everything
        // This allows learning from tokens that were previously banned
        // ═══════════════════════════════════════════════════════════════════
        if (!isPaperMode && BannedTokens.isBanned(token.mint)) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: PERMANENTLY BANNED")
            return false
        }

        // Check if this is a pump.fun token - they get special treatment
        val isPumpFunToken = token.source == TokenSource.PUMP_FUN_NEW || 
                             token.dexId == "pump.fun" ||
                             token.pairCreatedHoursAgo < 1.0  // Very new = likely pump.fun
        
        // PAPER MODE: No hard minimums - trade everything to learn
        if (isPaperMode) {
            // Only reject if literally zero or negative values
            if (token.mcapUsd < 0) return false
            // Allow everything else in paper mode
            return true
        }
        
        // REAL MODE: Apply filters as normal
        // HARD MINIMUM MCAP - LOWER for pump.fun tokens
        val HARD_MIN_MCAP = if (isPumpFunToken) 500.0 else 2_000.0
        if (token.mcapUsd > 0 && token.mcapUsd < HARD_MIN_MCAP) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} < hard min $${HARD_MIN_MCAP.toInt()}")
            return false
        }

        // Minimum liquidity - MUCH LOWER for pump.fun to catch early entries
        val HARD_MIN_LIQ = if (isPumpFunToken) 100.0 else 500.0
        if (token.liquidityUsd < HARD_MIN_LIQ && token.liquidityUsd > 0) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: liq $${token.liquidityUsd.toInt()} < min $${HARD_MIN_LIQ.toInt()}")
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

        // Minimum discovery score - VERY LOW for maximum discovery
        // We want to discover as many tokens as possible - let strategy filter
        val MIN_SCORE = 5.0  // Lowered from 10 - let more through
        if (token.score < MIN_SCORE) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: score ${token.score.toInt()} < min ${MIN_SCORE.toInt()}")
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

    /**
     * Build a ScannedToken from pair data.
     * @param fallbackLiquidity Optional liquidity from another source (e.g., Birdeye) 
     *                          to use if DexScreener returns $0
     */
    private fun buildScannedToken(
        mint: String,
        pair: com.lifecyclebot.network.PairInfo,
        source: TokenSource,
        fallbackLiquidity: Double = 0.0,
    ): ScannedToken? {
        if (pair.candle.priceUsd <= 0) return null
        
        // Use DexScreener liquidity, or fallback if DexScreener returned $0
        val liquidity = if (pair.liquidity > 0) pair.liquidity else fallbackLiquidity
        
        // Skip only if BOTH sources returned $0 liquidity
        if (liquidity <= 0) {
            ErrorLogger.debug("Scanner", "Skipping ${pair.baseSymbol}: $0 liquidity from all sources")
            return null
        }
        
        val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
        return ScannedToken(
            mint               = mint,
            symbol             = pair.baseSymbol,
            name               = pair.baseName,
            source             = source,
            liquidityUsd       = liquidity,
            volumeH1           = pair.candle.volumeH1,
            mcapUsd            = pair.candle.marketCap,
            pairCreatedHoursAgo = ageHours.coerceAtLeast(0.0),
            dexId              = "solana",
            priceChangeH1      = 0.0,
            txCountH1          = pair.candle.buysH1 + pair.candle.sellsH1,
            score              = scoreToken(liquidity, pair.candle.volumeH1,
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
    
    // Public function for BotService to mark tokens as rejected
    fun markTokenRejected(mint: String) {
        rejectedMints[mint] = System.currentTimeMillis()
        ErrorLogger.info("Scanner", "Token ${mint.take(12)} marked as rejected for ${REJECTED_TTL/60000}min")
    }
    
    // Clean up old entries from seen/rejected maps periodically
    private fun cleanupSeenMaps() {
        val now = System.currentTimeMillis()
        val seenBefore = seenMints.size
        val rejectedBefore = rejectedMints.size
        
        // Use safer cleanup - iterate and remove explicitly
        val seenToRemove = seenMints.entries.filter { now - it.value > SEEN_TTL }.map { it.key }
        seenToRemove.forEach { seenMints.remove(it) }
        
        val rejectedToRemove = rejectedMints.entries.filter { now - it.value > REJECTED_TTL }.map { it.key }
        rejectedToRemove.forEach { rejectedMints.remove(it) }
        
        val seenRemoved = seenBefore - seenMints.size
        val rejectedRemoved = rejectedBefore - rejectedMints.size
        
        if (seenRemoved > 0 || rejectedRemoved > 0) {
            ErrorLogger.info("Scanner", "Cleanup: removed $seenRemoved seen, $rejectedRemoved rejected. " +
                "Remaining: ${seenMints.size} seen, ${rejectedMints.size} rejected")
            onLog("🧹 Map cleanup: seen=${seenMints.size} rejected=${rejectedMints.size}")
        }
        
        // AGGRESSIVE cleanup if maps are getting large - keep fewer entries
        if (seenMints.size > 100) {
            val toKeep = seenMints.entries.sortedByDescending { it.value }.take(50).map { it.key }
            seenMints.keys.retainAll(toKeep.toSet())
            ErrorLogger.warn("Scanner", "Aggressive seen cleanup: reduced to ${seenMints.size}")
            onLog("⚠️ Seen map trimmed to ${seenMints.size}")
        }
        
        if (rejectedMints.size > 200) {
            val toKeep = rejectedMints.entries.sortedByDescending { it.value }.take(100).map { it.key }
            rejectedMints.keys.retainAll(toKeep.toSet())
            ErrorLogger.warn("Scanner", "Aggressive rejected cleanup: reduced to ${rejectedMints.size}")
            onLog("⚠️ Rejected map trimmed to ${rejectedMints.size}")
        }
    }

    private fun emit(token: ScannedToken) {
        // AI check - skip tokens that have failed repeatedly
        if (aiShouldSkipToken(token.mint, token.symbol, token.liquidityUsd, token.mcapUsd)) {
            return  // Don't emit - AI says this token is bad
        }
        
        // Apply AI-driven source boost to score
        val aiBoost = getAISourceBoost(token.source)
        
        // Apply ScannerLearning boost (from historical trade outcomes)
        val scannerLearningBoost = ScannerLearning.getDiscoveryBonus(
            source = token.source.name,
            liqUsd = token.liquidityUsd,
            ageHours = if (token.pairCreatedHoursAgo > 0) token.pairCreatedHoursAgo else 1.0
        )
        
        val totalBoost = aiBoost + scannerLearningBoost
        val adjustedScore = (token.score + totalBoost).coerceIn(0.0, 100.0)
        val adjustedToken = token.copy(score = adjustedScore)
        
        seenMints[token.mint] = System.currentTimeMillis()
        val boostIndicator = if (totalBoost != 0.0) " AI${if(totalBoost>0) "+" else ""}${totalBoost.toInt()}" else ""
        onLog("🔍 Found: ${token.symbol} (${token.source.name}$boostIndicator) " +
              "liq=$${(token.liquidityUsd/1000).toInt()}K " +
              "vol=$${(token.volumeH1/1000).toInt()}K " +
              "score=${adjustedScore.toInt()}")
        
        // Record liquidity snapshot for LiquidityDepthAI
        LiquidityDepthAI.recordSnapshot(
            mint = token.mint,
            liquidityUsd = token.liquidityUsd,
            mcapUsd = token.mcapUsd,
            holderCount = 0  // Holder count not available from scanner
        )
        
        onTokenFound(adjustedToken.mint, adjustedToken.symbol, adjustedToken.name, adjustedToken.source, adjustedToken.score, adjustedToken.liquidityUsd)
    }
    
    // Separate HTTP client for rugcheck with SHORT timeout
    private val rugcheckHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    
    /**
     * Quick rugcheck - returns immediately if API is slow
     * VERY LENIENT: Only block tokens that are literally marked as RUGGED
     * Most meme coins have low rugcheck scores but are still tradeable
     * The real protection comes from our distribution detection and exit strategies
     */
    private fun quickRugcheck(mint: String): Boolean {
        val isPaperMode = cfg().paperMode
        
        try {
            val url = "https://api.rugcheck.xyz/v1/tokens/$mint/report/summary"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            
            val resp = rugcheckHttp.newCall(request).execute()
            if (!resp.isSuccessful) {
                ErrorLogger.debug("Scanner", "Rugcheck API failed for ${mint.take(8)}, passing through")
                return true  // Pass if API error
            }
            
            val body = resp.body?.string() ?: return true
            val json = JSONObject(body)
            
            val scoreNormalized = json.optInt("score_normalised", json.optInt("score", 50))
            val rugged = json.optString("rugged", "").lowercase()
            
            // PAPER MODE: Only block confirmed rugs - allow everything else for learning
            if (isPaperMode) {
                if (rugged == "true" || rugged == "yes") {
                    onLog("🚫 RUG: ${mint.take(8)}... ALREADY RUGGED (confirmed)")
                    ErrorLogger.info("Scanner", "quickRugcheck BLOCK: ${mint.take(12)} rugged=true (paper mode)")
                    return false
                }
                // Paper mode: pass everything else
                if (scoreNormalized < 20) {
                    ErrorLogger.debug("Scanner", "RC ${mint.take(8)}: score=$scoreNormalized (PAPER: passing for learning)")
                }
                return true
            }
            
            // LIVE MODE: Block confirmed rugs and extremely dangerous tokens
            if (rugged == "true" || rugged == "yes") {
                onLog("🚫 RUG: ${mint.take(8)}... ALREADY RUGGED (confirmed)")
                ErrorLogger.info("Scanner", "quickRugcheck BLOCK: ${mint.take(12)} rugged=true (live mode)")
                return false
            }
            
            // ONLY block extremely dangerous tokens (score < 5)
            // Most meme coins have scores 10-30 which is fine
            if (scoreNormalized < 5) {
                onLog("🚫 BLOCKED: ${mint.take(8)}... score=$scoreNormalized (extremely risky)")
                ErrorLogger.info("Scanner", "quickRugcheck BLOCK: ${mint.take(12)} score=$scoreNormalized < 5 (live mode)")
                return false
            }
            
            // Log for debugging but PASS
            if (scoreNormalized < 20) {
                ErrorLogger.debug("Scanner", "RC ${mint.take(8)}: score=$scoreNormalized (low but OK)")
            }
            
            return true  // Pass - let other safety checks handle it
            
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Timeout or error - pass through (don't block on API issues)
            ErrorLogger.debug("Scanner", "RC exception for ${mint.take(8)}: ${e.message}, passing")
            return true
        }
    }
    
    /**
     * Emit with optional quick rugcheck
     * VERY LENIENT - Only block truly dangerous rugs
     */
    private suspend fun emitWithRugcheck(token: ScannedToken) {
        // Quick check with 2-second timeout - always pass on any error
        val passed = try {
            withContext(Dispatchers.IO) { 
                try {
                    withTimeout(2000L) { quickRugcheck(token.mint) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    ErrorLogger.debug("Scanner", "RC timeout for ${token.symbol}, passing through")
                    true  // Timeout = pass through
                } catch (e: Exception) {
                    ErrorLogger.debug("Scanner", "RC error for ${token.symbol}: ${e.message}, passing through")
                    true  // Any error = pass through
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Re-throw job cancellation
        } catch (e: Exception) {
            ErrorLogger.debug("Scanner", "RC outer error for ${token.symbol}: ${e.message}, passing through")
            true  // Any other error = pass through
        }
        
        if (!passed) {
            ErrorLogger.info("Scanner", "Rugcheck blocked ${token.symbol} (quickRugcheck returned false)")
            return
        }
        
        emit(token)
    }

    private fun get(url: String, apiKey: String = ""): String? = try {
        val builder = Request.Builder().url(url)
            // Use browser-like headers to avoid Cloudflare blocks
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Connection", "keep-alive")
        if (apiKey.isNotBlank()) builder.header("X-API-KEY", apiKey)
        ErrorLogger.debug("Scanner", "HTTP GET: ${url.take(60)}...")
        val resp = http.newCall(builder.build()).execute()
        if (resp.isSuccessful) {
            val body = resp.body?.string()
            // Validate it looks like JSON before returning
            if (body != null && (body.trim().startsWith("{") || body.trim().startsWith("["))) {
                ErrorLogger.debug("Scanner", "HTTP OK: ${body.length} bytes from ${url.take(40)}")
                body
            } else {
                ErrorLogger.warn("Scanner", "HTTP non-JSON response from ${url.take(50)}")
                null
            }
        } else {
            // Don't spam logs with 429/530 errors - just return null
            if (resp.code != 429 && resp.code != 530 && resp.code != 403) {
                ErrorLogger.warn("Scanner", "HTTP FAIL: ${resp.code} from ${url.take(50)}")
            }
            null
        }
    } catch (e: java.net.SocketTimeoutException) {
        // Timeouts are expected occasionally - don't log as error
        ErrorLogger.warn("Scanner", "HTTP timeout for ${url.take(50)}")
        null
    } catch (e: Exception) { 
        ErrorLogger.warn("Scanner", "HTTP error: ${e.message?.take(30)} for ${url.take(50)}")
        null 
    }
  }
}
}
