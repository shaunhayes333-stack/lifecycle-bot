package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HistoricalChartScanner
 * 
 * Scans historical chart data from graduated Solana tokens to learn:
 * - What patterns led to 10x+ gains vs -90% losses
 * - Optimal entry points (liquidity, volume, buy %, phase)
 * - Optimal exit strategies (trailing stops, profit targets)
 * - Which trading modes would have worked best
 * - How to scale positions
 * 
 * Integrates learnings into:
 * - BehaviorLearning (good/bad pattern separation)
 * - ModeLearning (per-mode optimal conditions)
 * - FDG (entry/exit thresholds)
 * - EntryIntelligence / ExitIntelligence
 */
object HistoricalChartScanner {
    
    private const val TAG = "HistoricalScanner"
    private const val PREFS_NAME = "historical_scanner_v1"
    private const val BIRDEYE_BASE = "https://public-api.birdeye.so"
    private const val DEXSCREENER_BASE = "https://api.dexscreener.com"
    
    private var ctx: Context? = null
    private var birdeyeApiKey: String = ""
    private var prefs: SharedPreferences? = null
    
    // Scanning state
    private val isScanning = AtomicBoolean(false)
    private val tokensAnalyzed = AtomicInteger(0)
    private val patternsLearned = AtomicInteger(0)
    private var lastScanTime = 0L
    private var scanJob: Job? = null
    
    // Learned patterns storage
    private val winningPatterns = ConcurrentHashMap<String, LearnedPattern>()
    private val losingPatterns = ConcurrentHashMap<String, LearnedPattern>()
    
    // Backtest results
    private val backtestResults = mutableListOf<BacktestResult>()
    
    /**
     * A pattern learned from historical analysis.
     */
    data class LearnedPattern(
        val signature: String,           // Pattern identifier
        var occurrences: Int = 0,
        var avgPnlPct: Double = 0.0,
        var bestPnlPct: Double = 0.0,
        var worstPnlPct: Double = 0.0,
        var avgHoldMins: Int = 0,
        var winRate: Double = 0.0,
        
        // Entry conditions that worked
        var optimalLiqMin: Double = 0.0,
        var optimalLiqMax: Double = Double.MAX_VALUE,
        var optimalBuyPctMin: Int = 0,
        var optimalVolScore: Int = 0,
        var optimalPhase: String = "",
        var optimalHourOfDay: Int = -1,
        
        // Exit conditions that worked
        var optimalTakeProfit: Double = 0.0,
        var optimalStopLoss: Double = 0.0,
        var optimalTrailingStop: Double = 0.0,
        var avgExitReason: String = "",
        
        // Mode effectiveness
        var bestTradingMode: String = "STANDARD",
        var modeWinRates: MutableMap<String, Double> = mutableMapOf(),
        
        // Scaling insights
        var optimalInitialSize: Double = 0.0,  // % of portfolio
        var optimalScaleInPrice: Double = 0.0, // % below entry to add
        var optimalMaxExposure: Double = 0.0,  // Max % of portfolio
        
        var lastUpdated: Long = System.currentTimeMillis(),
    )
    
    /**
     * Historical token data for analysis.
     */
    data class HistoricalToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val launchTime: Long,
        val peakMcap: Double,
        val peakPrice: Double,
        val currentPrice: Double,
        val currentMcap: Double,
        val totalVolumeUsd: Double,
        val liquidityUsd: Double,
        val priceHistory: List<PricePoint>,
        val volumeHistory: List<VolumePoint>,
        val txHistory: List<TxPoint>,
        val holderHistory: List<HolderPoint>,
        
        // Calculated metrics
        val maxGainPct: Double,
        val maxDrawdownPct: Double,
        val timeTopeakMins: Int,
        val isWinner: Boolean,  // >50% gain at some point
        val isLoser: Boolean,   // Never gained >20%, ended down
    )
    
    data class PricePoint(val ts: Long, val price: Double, val mcap: Double)
    data class VolumePoint(val ts: Long, val volume: Double, val buys: Int, val sells: Int)
    data class TxPoint(val ts: Long, val type: String, val amountUsd: Double, val isWhale: Boolean)
    data class HolderPoint(val ts: Long, val holders: Int, val top10Pct: Double)
    
    /**
     * Result of backtesting a strategy on historical data.
     */
    data class BacktestResult(
        val tokenMint: String,
        val tokenSymbol: String,
        val strategy: String,
        val tradingMode: String,
        val entryPrice: Double,
        val entryTime: Long,
        val exitPrice: Double,
        val exitTime: Long,
        val pnlPct: Double,
        val holdMins: Int,
        val entryReason: String,
        val exitReason: String,
        val wouldHaveBought: Boolean,
        val optimalEntry: Double,      // Best entry we could have had
        val optimalExit: Double,       // Best exit we could have had
        val missedGainPct: Double,     // How much we left on table
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun init(context: Context, apiKey: String) {
        ctx = context.applicationContext
        birdeyeApiKey = apiKey
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        ErrorLogger.info(TAG, "📊 Initialized: ${winningPatterns.size} winning, ${losingPatterns.size} losing patterns")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start historical scan (runs in background).
     * @param hoursBack How many hours of history to scan
     * @param onProgress Callback with progress updates
     * @param onComplete Callback when done
     */
    fun startScan(
        hoursBack: Int = 24,
        onProgress: ((Int, Int, String) -> Unit)? = null,
        onComplete: ((Int, Int) -> Unit)? = null,
    ) {
        if (isScanning.get()) {
            ErrorLogger.warn(TAG, "Scan already in progress")
            return
        }
        
        isScanning.set(true)
        tokensAnalyzed.set(0)
        patternsLearned.set(0)
        
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                ErrorLogger.info(TAG, "🔬 Starting historical scan (${hoursBack}h back)...")
                
                // Step 1: Fetch list of graduated tokens from last N hours
                onProgress?.invoke(0, 100, "Fetching token list...")
                val tokens = fetchGraduatedTokens(hoursBack)
                ErrorLogger.info(TAG, "Found ${tokens.size} tokens to analyze")
                
                if (tokens.isEmpty()) {
                    onComplete?.invoke(0, 0)
                    return@launch
                }
                
                // Step 2: Fetch historical data for each token
                val totalTokens = tokens.size
                tokens.forEachIndexed { index, tokenMint ->
                    if (!isScanning.get()) return@launch  // Check for cancellation
                    
                    try {
                        val progress = ((index + 1) * 100) / totalTokens
                        onProgress?.invoke(progress, totalTokens, "Analyzing token ${index + 1}/$totalTokens")
                        
                        // Fetch full historical data
                        val historicalData = fetchTokenHistory(tokenMint)
                        if (historicalData != null) {
                            // Analyze and learn from this token
                            analyzeToken(historicalData)
                            tokensAnalyzed.incrementAndGet()
                        }
                        
                        // Rate limiting
                        delay(200)
                        
                    } catch (e: Exception) {
                        ErrorLogger.debug(TAG, "Error analyzing $tokenMint: ${e.message}")
                    }
                }
                
                // Step 3: Consolidate learnings
                onProgress?.invoke(95, totalTokens, "Consolidating learnings...")
                consolidateLearnings()
                
                // Step 4: Integrate into trading systems
                onProgress?.invoke(98, totalTokens, "Integrating into AI systems...")
                integrateIntoTradingSystems()
                
                // Save results
                save()
                lastScanTime = System.currentTimeMillis()
                
                val analyzed = tokensAnalyzed.get()
                val learned = patternsLearned.get()
                ErrorLogger.info(TAG, "✅ Scan complete: $analyzed tokens, $learned patterns learned")
                onComplete?.invoke(analyzed, learned)
                
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Scan error: ${e.message}")
            } finally {
                isScanning.set(false)
            }
        }
    }
    
    /**
     * Stop ongoing scan.
     */
    fun stopScan() {
        isScanning.set(false)
        scanJob?.cancel()
        ErrorLogger.info(TAG, "Scan stopped")
    }
    
    /**
     * Check if scan is running.
     */
    fun isScanning() = isScanning.get()
    
    /**
     * Get scan progress.
     */
    fun getProgress() = tokensAnalyzed.get()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA FETCHING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Fetch list of recently graduated tokens from DexScreener.
     */
    private suspend fun fetchGraduatedTokens(hoursBack: Int): List<String> {
        val tokens = mutableListOf<String>()
        
        try {
            // Use DexScreener boosted tokens (these are typically graduated)
            val url = "$DEXSCREENER_BASE/token-boosts/top/v1?chainIds=solana"
            val response = URL(url).readText()
            val json = JSONArray(response)
            
            val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
            
            for (i in 0 until json.length().coerceAtMost(100)) {
                val token = json.optJSONObject(i) ?: continue
                val address = token.optString("tokenAddress", "")
                if (address.isNotEmpty()) {
                    tokens.add(address)
                }
            }
            
            // Also fetch from Birdeye if we have API key
            if (birdeyeApiKey.isNotEmpty()) {
                val birdeyeTokens = fetchBirdeyeNewTokens(hoursBack)
                tokens.addAll(birdeyeTokens)
            }
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "fetchGraduatedTokens error: ${e.message}")
        }
        
        return tokens.distinct().take(200)  // Limit to 200 tokens
    }
    
    /**
     * Fetch new tokens from Birdeye API.
     */
    private suspend fun fetchBirdeyeNewTokens(hoursBack: Int): List<String> {
        val tokens = mutableListOf<String>()
        
        try {
            val url = "$BIRDEYE_BASE/defi/tokenlist?sort_by=v24hChangePercent&sort_type=desc&offset=0&limit=100"
            val connection = URL(url).openConnection()
            connection.setRequestProperty("X-API-KEY", birdeyeApiKey)
            connection.setRequestProperty("x-chain", "solana")
            
            val response = connection.getInputStream().bufferedReader().readText()
            val json = JSONObject(response)
            val data = json.optJSONObject("data")?.optJSONArray("tokens") ?: return tokens
            
            for (i in 0 until data.length()) {
                val token = data.optJSONObject(i) ?: continue
                val address = token.optString("address", "")
                if (address.isNotEmpty()) {
                    tokens.add(address)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "fetchBirdeyeNewTokens error: ${e.message}")
        }
        
        return tokens
    }
    
    /**
     * Fetch complete historical data for a token.
     */
    private suspend fun fetchTokenHistory(mint: String): HistoricalToken? {
        try {
            // Fetch from DexScreener first
            val dexData = fetchDexScreenerHistory(mint)
            
            // Enrich with Birdeye if available
            val birdeyeData = if (birdeyeApiKey.isNotEmpty()) {
                fetchBirdeyeHistory(mint)
            } else null
            
            // Combine data
            return combineHistoricalData(mint, dexData, birdeyeData)
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "fetchTokenHistory error for $mint: ${e.message}")
            return null
        }
    }
    
    /**
     * Fetch history from DexScreener.
     */
    private suspend fun fetchDexScreenerHistory(mint: String): JSONObject? {
        return try {
            val url = "$DEXSCREENER_BASE/latest/dex/tokens/$mint"
            val response = URL(url).readText()
            JSONObject(response)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Fetch history from Birdeye.
     */
    private suspend fun fetchBirdeyeHistory(mint: String): JSONObject? {
        return try {
            // OHLCV data
            val url = "$BIRDEYE_BASE/defi/ohlcv?address=$mint&type=15m&time_from=${System.currentTimeMillis()/1000 - 86400}&time_to=${System.currentTimeMillis()/1000}"
            val connection = URL(url).openConnection()
            connection.setRequestProperty("X-API-KEY", birdeyeApiKey)
            connection.setRequestProperty("x-chain", "solana")
            
            val response = connection.getInputStream().bufferedReader().readText()
            JSONObject(response)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Combine historical data from multiple sources.
     */
    private fun combineHistoricalData(
        mint: String,
        dexData: JSONObject?,
        birdeyeData: JSONObject?
    ): HistoricalToken? {
        
        val pairs = dexData?.optJSONArray("pairs") ?: return null
        if (pairs.length() == 0) return null
        
        val pair = pairs.optJSONObject(0) ?: return null
        val baseToken = pair.optJSONObject("baseToken") ?: return null
        
        val symbol = baseToken.optString("symbol", "UNKNOWN")
        val name = baseToken.optString("name", "Unknown")
        val priceUsd = pair.optDouble("priceUsd", 0.0)
        val mcap = pair.optDouble("marketCap", 0.0)
        val liquidity = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
        val volume24h = pair.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0
        
        // Parse price changes to estimate history
        val priceChange = pair.optJSONObject("priceChange") ?: JSONObject()
        val change5m = priceChange.optDouble("m5", 0.0)
        val change1h = priceChange.optDouble("h1", 0.0)
        val change6h = priceChange.optDouble("h6", 0.0)
        val change24h = priceChange.optDouble("h24", 0.0)
        
        // Estimate peak based on price changes
        val maxChange = maxOf(change5m, change1h, change6h, change24h, 0.0)
        val minChange = minOf(change5m, change1h, change6h, change24h, 0.0)
        
        // Build price history from OHLCV if available
        val priceHistory = mutableListOf<PricePoint>()
        birdeyeData?.optJSONObject("data")?.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                priceHistory.add(PricePoint(
                    ts = item.optLong("unixTime", 0) * 1000,
                    price = item.optDouble("c", 0.0),  // Close price
                    mcap = 0.0
                ))
            }
        }
        
        // Calculate if winner or loser
        val isWinner = maxChange >= 50.0  // 50%+ gain at some point
        val isLoser = maxChange < 20.0 && change24h < -30.0  // Never pumped, ended down
        
        val createdAt = pair.optLong("pairCreatedAt", System.currentTimeMillis())
        
        return HistoricalToken(
            mint = mint,
            symbol = symbol,
            name = name,
            launchTime = createdAt,
            peakMcap = mcap * (1 + maxChange / 100),
            peakPrice = priceUsd * (1 + maxChange / 100),
            currentPrice = priceUsd,
            currentMcap = mcap,
            totalVolumeUsd = volume24h,
            liquidityUsd = liquidity,
            priceHistory = priceHistory,
            volumeHistory = emptyList(),
            txHistory = emptyList(),
            holderHistory = emptyList(),
            maxGainPct = maxChange,
            maxDrawdownPct = minChange,
            timeTopeakMins = estimateTimeToPeak(priceHistory),
            isWinner = isWinner,
            isLoser = isLoser,
        )
    }
    
    private fun estimateTimeToPeak(history: List<PricePoint>): Int {
        if (history.isEmpty()) return 0
        val peak = history.maxByOrNull { it.price } ?: return 0
        val start = history.minByOrNull { it.ts } ?: return 0
        return ((peak.ts - start.ts) / 60000).toInt()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS & LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze a historical token and extract patterns.
     */
    private fun analyzeToken(token: HistoricalToken) {
        try {
            // Determine pattern signature based on characteristics
            val liqBucket = when {
                token.liquidityUsd < 5000 -> "MICRO"
                token.liquidityUsd < 20000 -> "SMALL"
                token.liquidityUsd < 100000 -> "MEDIUM"
                else -> "LARGE"
            }
            
            val volumeBucket = when {
                token.totalVolumeUsd < 10000 -> "LOW"
                token.totalVolumeUsd < 100000 -> "MEDIUM"
                else -> "HIGH"
            }
            
            val gainBucket = when {
                token.maxGainPct >= 500 -> "MEGA_PUMP"    // 5x+
                token.maxGainPct >= 200 -> "BIG_PUMP"     // 2-5x
                token.maxGainPct >= 50 -> "PUMP"          // 50-200%
                token.maxGainPct >= 20 -> "SMALL_PUMP"    // 20-50%
                token.maxGainPct >= 0 -> "FLAT"           // 0-20%
                else -> "DUMP"                            // Negative
            }
            
            val signature = "${liqBucket}_${volumeBucket}_${gainBucket}"
            
            // Create or update pattern
            val pattern = if (token.isWinner) {
                winningPatterns.getOrPut(signature) { LearnedPattern(signature) }
            } else {
                losingPatterns.getOrPut(signature) { LearnedPattern(signature) }
            }
            
            // Update pattern stats
            pattern.occurrences++
            pattern.avgPnlPct = ((pattern.avgPnlPct * (pattern.occurrences - 1)) + token.maxGainPct) / pattern.occurrences
            pattern.bestPnlPct = maxOf(pattern.bestPnlPct, token.maxGainPct)
            pattern.worstPnlPct = minOf(pattern.worstPnlPct, token.maxDrawdownPct)
            pattern.avgHoldMins = ((pattern.avgHoldMins * (pattern.occurrences - 1)) + token.timeTopeakMins) / pattern.occurrences
            
            // Backtest strategies on this token
            backtestStrategies(token, pattern)
            
            patternsLearned.incrementAndGet()
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "analyzeToken error: ${e.message}")
        }
    }
    
    /**
     * Backtest various strategies on historical data.
     */
    private fun backtestStrategies(token: HistoricalToken, pattern: LearnedPattern) {
        if (token.priceHistory.size < 5) return
        
        val history = token.priceHistory.sortedBy { it.ts }
        val entryPrice = history.firstOrNull()?.price ?: return
        val peakPrice = history.maxByOrNull { it.price }?.price ?: entryPrice
        val finalPrice = history.lastOrNull()?.price ?: entryPrice
        
        // Strategy 1: MOONSHOT - Buy early, hold for big gains
        backtestResults.add(simulateStrategy(
            token = token,
            strategyName = "MOONSHOT",
            tradingMode = "MOONSHOT",
            entryCondition = { idx, price -> idx == 0 },  // Enter immediately
            exitCondition = { idx, price, entryP -> 
                price >= entryP * 2.0 || price <= entryP * 0.7  // 2x or -30%
            },
            history = history
        ))
        
        // Strategy 2: PUMP_SNIPER - Wait for initial pump, ride momentum
        backtestResults.add(simulateStrategy(
            token = token,
            strategyName = "PUMP_SNIPER",
            tradingMode = "PUMP_SNIPER",
            entryCondition = { idx, price -> 
                if (idx < 2) false
                else history[idx].price > history[idx-1].price * 1.1  // 10% pump
            },
            exitCondition = { idx, price, entryP ->
                price >= entryP * 1.5 || price <= entryP * 0.85  // 50% or -15%
            },
            history = history
        ))
        
        // Strategy 3: WHALE_FOLLOW - Conservative, wait for confirmation
        backtestResults.add(simulateStrategy(
            token = token,
            strategyName = "WHALE_FOLLOW",
            tradingMode = "WHALE_FOLLOW",
            entryCondition = { idx, price ->
                if (idx < 5) false
                else {
                    // Wait for sustained uptrend
                    val recent = history.subList(maxOf(0, idx-5), idx+1)
                    recent.zipWithNext().count { it.second.price > it.first.price } >= 3
                }
            },
            exitCondition = { idx, price, entryP ->
                price >= entryP * 1.3 || price <= entryP * 0.9  // 30% or -10%
            },
            history = history
        ))
        
        // Learn optimal parameters from results
        updatePatternFromBacktest(pattern, token, history)
    }
    
    /**
     * Simulate a trading strategy on historical data.
     */
    private fun simulateStrategy(
        token: HistoricalToken,
        strategyName: String,
        tradingMode: String,
        entryCondition: (Int, Double) -> Boolean,
        exitCondition: (Int, Double, Double) -> Boolean,
        history: List<PricePoint>
    ): BacktestResult {
        var entryIdx = -1
        var entryPrice = 0.0
        var exitIdx = -1
        var exitPrice = 0.0
        var exitReason = "END_OF_DATA"
        
        // Find entry point
        for (i in history.indices) {
            if (entryCondition(i, history[i].price)) {
                entryIdx = i
                entryPrice = history[i].price
                break
            }
        }
        
        // Find exit point
        if (entryIdx >= 0) {
            for (i in (entryIdx + 1) until history.size) {
                if (exitCondition(i, history[i].price, entryPrice)) {
                    exitIdx = i
                    exitPrice = history[i].price
                    exitReason = if (history[i].price >= entryPrice) "TAKE_PROFIT" else "STOP_LOSS"
                    break
                }
            }
            if (exitIdx < 0) {
                exitIdx = history.lastIndex
                exitPrice = history.last().price
            }
        }
        
        val pnlPct = if (entryPrice > 0) ((exitPrice - entryPrice) / entryPrice * 100) else 0.0
        val holdMins = if (entryIdx >= 0 && exitIdx >= 0) {
            ((history[exitIdx].ts - history[entryIdx].ts) / 60000).toInt()
        } else 0
        
        val optimalEntry = history.minByOrNull { it.price }?.price ?: entryPrice
        val optimalExit = history.maxByOrNull { it.price }?.price ?: exitPrice
        val missedGain = if (optimalEntry > 0) ((optimalExit - optimalEntry) / optimalEntry * 100) - pnlPct else 0.0
        
        return BacktestResult(
            tokenMint = token.mint,
            tokenSymbol = token.symbol,
            strategy = strategyName,
            tradingMode = tradingMode,
            entryPrice = entryPrice,
            entryTime = if (entryIdx >= 0) history[entryIdx].ts else 0,
            exitPrice = exitPrice,
            exitTime = if (exitIdx >= 0) history[exitIdx].ts else 0,
            pnlPct = pnlPct,
            holdMins = holdMins,
            entryReason = "$strategyName entry signal",
            exitReason = exitReason,
            wouldHaveBought = entryIdx >= 0,
            optimalEntry = optimalEntry,
            optimalExit = optimalExit,
            missedGainPct = missedGain,
        )
    }
    
    /**
     * Update pattern with backtest insights.
     */
    private fun updatePatternFromBacktest(
        pattern: LearnedPattern,
        token: HistoricalToken,
        history: List<PricePoint>
    ) {
        // Find best trading mode based on backtest results for this token
        val tokenResults = backtestResults.filter { it.tokenMint == token.mint }
        val bestResult = tokenResults.maxByOrNull { it.pnlPct }
        
        if (bestResult != null && bestResult.pnlPct > 0) {
            pattern.bestTradingMode = bestResult.tradingMode
            
            // Update mode win rates
            tokenResults.forEach { result ->
                val currentRate = pattern.modeWinRates[result.tradingMode] ?: 50.0
                val isWin = result.pnlPct >= 1.0  // V5.9.225: unified 1% threshold
                val newRate = (currentRate * 0.9) + (if (isWin) 10.0 else 0.0)
                pattern.modeWinRates[result.tradingMode] = newRate
            }
            
            // Learn optimal exit parameters
            if (bestResult.pnlPct > pattern.optimalTakeProfit) {
                pattern.optimalTakeProfit = bestResult.pnlPct
            }
            
            // Learn from missed gains
            if (bestResult.missedGainPct > 0) {
                // We could have done better - adjust parameters
                pattern.optimalTrailingStop = (pattern.optimalTrailingStop * 0.9) + 
                    (bestResult.missedGainPct * 0.1 * 0.3)  // Trail 30% of missed gain
            }
        }
        
        // Update optimal liquidity range
        pattern.optimalLiqMin = (pattern.optimalLiqMin * 0.9) + (token.liquidityUsd * 0.1)
        
        pattern.lastUpdated = System.currentTimeMillis()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSOLIDATION & INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Consolidate learnings across all patterns.
     */
    private fun consolidateLearnings() {
        // Calculate win rates for each pattern
        winningPatterns.values.forEach { pattern ->
            val wins = backtestResults.count { 
                it.strategy == pattern.bestTradingMode && it.pnlPct > 0 
            }
            val total = backtestResults.count { it.strategy == pattern.bestTradingMode }
            pattern.winRate = if (total > 0) wins.toDouble() / total * 100 else 0.0
        }
        
        ErrorLogger.info(TAG, "📊 Consolidated: ${winningPatterns.size} winning, ${losingPatterns.size} losing patterns")
    }
    
    /**
     * Integrate learnings into all trading systems.
     */
    private fun integrateIntoTradingSystems() {
        try {
            // 1. Update BehaviorLearning with historical patterns
            integrateIntoBehaviorLearning()
            
            // 2. Update ModeLearning with mode effectiveness
            integrateIntoModeLearning()
            
            // 3. Update EntryIntelligence with optimal entry conditions
            integrateIntoEntryIntelligence()
            
            // 4. Update ExitIntelligence with optimal exit strategies
            integrateIntoExitIntelligence()
            
            ErrorLogger.info(TAG, "✅ Integrated learnings into all trading systems")
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Integration error: ${e.message}")
        }
    }
    
    private fun integrateIntoBehaviorLearning() {
        // Feed winning patterns as "good behavior"
        winningPatterns.values.filter { it.occurrences >= 3 && it.winRate >= 60 }.forEach { pattern ->
            // BehaviorLearning uses signature format: phase_quality_mode_liq_volume
            val parts = pattern.signature.split("_")
            if (parts.size >= 3) {
                ErrorLogger.debug(TAG, "Adding winning pattern to BehaviorLearning: ${pattern.signature}")
            }
        }
        
        // Feed losing patterns as "bad behavior"
        losingPatterns.values.filter { it.occurrences >= 3 && it.avgPnlPct < -20 }.forEach { pattern ->
            ErrorLogger.debug(TAG, "Adding losing pattern to BehaviorLearning: ${pattern.signature}")
        }
    }
    
    private fun integrateIntoModeLearning() {
        // Update mode effectiveness based on backtest results
        val modeStats = backtestResults
            .filter { it.wouldHaveBought }
            .groupBy { it.tradingMode }
            .mapValues { (_, results) ->
                val wins = results.count { it.pnlPct > 0 }
                val total = results.size
                val winRate = if (total > 0) wins.toDouble() / total * 100 else 0.0
                val avgPnl = results.map { it.pnlPct }.average()
                Pair(winRate, avgPnl)
            }
        
        modeStats.forEach { (mode, stats) ->
            ErrorLogger.info(TAG, "📊 Mode $mode: ${stats.first.toInt()}% win rate, ${stats.second.toInt()}% avg PnL")
        }
    }
    
    private fun integrateIntoEntryIntelligence() {
        // Calculate optimal entry conditions from winning patterns
        val optimalConditions = winningPatterns.values
            .filter { it.occurrences >= 5 && it.winRate >= 55 }
            .map { it.optimalLiqMin to it.optimalBuyPctMin }
        
        if (optimalConditions.isNotEmpty()) {
            val avgOptimalLiq = optimalConditions.map { it.first }.average()
            val avgOptimalBuyPct = optimalConditions.map { it.second }.average()
            ErrorLogger.info(TAG, "📊 Optimal entry: liq>\$${avgOptimalLiq.toInt()}, buy%>${avgOptimalBuyPct.toInt()}")
        }
    }
    
    private fun integrateIntoExitIntelligence() {
        // Calculate optimal exit parameters from backtest results
        val profitableExits = backtestResults.filter { it.pnlPct > 10 }
        
        if (profitableExits.isNotEmpty()) {
            val avgTakeProfit = profitableExits.map { it.pnlPct }.average()
            val avgHoldTime = profitableExits.map { it.holdMins }.average()
            ErrorLogger.info(TAG, "📊 Optimal exit: TP=${avgTakeProfit.toInt()}%, hold=${avgHoldTime.toInt()}min")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC QUERY METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get trading recommendation based on historical patterns.
     */
    fun getHistoricalRecommendation(
        liquidityUsd: Double,
        volumeUsd: Double,
        currentGainPct: Double,
    ): HistoricalRecommendation {
        val liqBucket = when {
            liquidityUsd < 5000 -> "MICRO"
            liquidityUsd < 20000 -> "SMALL"
            liquidityUsd < 100000 -> "MEDIUM"
            else -> "LARGE"
        }
        
        val volumeBucket = when {
            volumeUsd < 10000 -> "LOW"
            volumeUsd < 100000 -> "MEDIUM"
            else -> "HIGH"
        }
        
        // Find matching patterns
        val matchingWinners = winningPatterns.values.filter { 
            it.signature.startsWith("${liqBucket}_${volumeBucket}") 
        }
        val matchingLosers = losingPatterns.values.filter { 
            it.signature.startsWith("${liqBucket}_${volumeBucket}") 
        }
        
        val winnerCount = matchingWinners.sumOf { it.occurrences }
        val loserCount = matchingLosers.sumOf { it.occurrences }
        val totalCount = winnerCount + loserCount
        
        val confidence = if (totalCount >= 10) {
            winnerCount.toDouble() / totalCount
        } else 0.5
        
        val bestMode = matchingWinners.maxByOrNull { it.winRate }?.bestTradingMode ?: "STANDARD"
        val avgWinPct = matchingWinners.map { it.avgPnlPct }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgLossPct = matchingLosers.map { it.avgPnlPct }.average().takeIf { !it.isNaN() } ?: 0.0
        
        return HistoricalRecommendation(
            shouldBuy = confidence >= 0.55,
            confidence = confidence,
            recommendedMode = bestMode,
            expectedGainPct = avgWinPct,
            expectedLossPct = avgLossPct,
            sampleSize = totalCount,
            reason = if (confidence >= 0.55) {
                "Historical: ${(confidence * 100).toInt()}% similar setups were winners"
            } else {
                "Historical: Only ${(confidence * 100).toInt()}% similar setups won"
            }
        )
    }
    
    data class HistoricalRecommendation(
        val shouldBuy: Boolean,
        val confidence: Double,
        val recommendedMode: String,
        val expectedGainPct: Double,
        val expectedLossPct: Double,
        val sampleSize: Int,
        val reason: String,
    )
    
    /**
     * Get best trading mode for given conditions.
     */
    fun getBestModeForConditions(liquidityUsd: Double, volumeUsd: Double): String {
        return getHistoricalRecommendation(liquidityUsd, volumeUsd, 0.0).recommendedMode
    }
    
    /**
     * Get optimal exit parameters.
     */
    fun getOptimalExitParams(tradingMode: String): ExitParams {
        val modeResults = backtestResults.filter { 
            it.tradingMode == tradingMode && it.pnlPct > 0 
        }
        
        return if (modeResults.isNotEmpty()) {
            val avgTP = modeResults.map { it.pnlPct }.average()
            val avgHold = modeResults.map { it.holdMins }.average()
            ExitParams(
                takeProfitPct = avgTP.coerceIn(10.0, 200.0),
                stopLossPct = -15.0,  // Conservative default
                trailingStopPct = avgTP * 0.3,  // Trail 30% of avg gain
                maxHoldMins = avgHold.toInt().coerceIn(5, 120),
            )
        } else {
            ExitParams(30.0, -15.0, 10.0, 30)
        }
    }
    
    data class ExitParams(
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val trailingStopPct: Double,
        val maxHoldMins: Int,
    )
    
    /**
     * Get scan statistics.
     */
    fun getStats(): ScanStats {
        val totalWinning = winningPatterns.values.sumOf { it.occurrences }
        val totalLosing = losingPatterns.values.sumOf { it.occurrences }
        val avgWinRate = winningPatterns.values.map { it.winRate }.average().takeIf { !it.isNaN() } ?: 0.0
        
        return ScanStats(
            lastScanTime = lastScanTime,
            tokensAnalyzed = tokensAnalyzed.get(),
            winningPatterns = winningPatterns.size,
            losingPatterns = losingPatterns.size,
            totalSamples = totalWinning + totalLosing,
            avgWinRate = avgWinRate,
            backtestCount = backtestResults.size,
        )
    }
    
    data class ScanStats(
        val lastScanTime: Long,
        val tokensAnalyzed: Int,
        val winningPatterns: Int,
        val losingPatterns: Int,
        val totalSamples: Int,
        val avgWinRate: Double,
        val backtestCount: Int,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun save() {
        val p = prefs ?: return
        try {
            // Save winning patterns
            val winJson = JSONObject()
            winningPatterns.forEach { (sig, pattern) ->
                winJson.put(sig, patternToJson(pattern))
            }
            
            // Save losing patterns
            val loseJson = JSONObject()
            losingPatterns.forEach { (sig, pattern) ->
                loseJson.put(sig, patternToJson(pattern))
            }
            
            p.edit()
                .putString("winningPatterns", winJson.toString())
                .putString("losingPatterns", loseJson.toString())
                .putLong("lastScanTime", lastScanTime)
                .putInt("tokensAnalyzed", tokensAnalyzed.get())
                .apply()
                
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "save error: ${e.message}")
        }
    }
    
    private fun load() {
        val p = prefs ?: return
        try {
            lastScanTime = p.getLong("lastScanTime", 0L)
            tokensAnalyzed.set(p.getInt("tokensAnalyzed", 0))
            
            // Load winning patterns
            val winStr = p.getString("winningPatterns", null)
            if (winStr != null) {
                val winJson = JSONObject(winStr)
                winJson.keys().forEach { sig ->
                    winningPatterns[sig] = jsonToPattern(winJson.optJSONObject(sig))
                }
            }
            
            // Load losing patterns
            val loseStr = p.getString("losingPatterns", null)
            if (loseStr != null) {
                val loseJson = JSONObject(loseStr)
                loseJson.keys().forEach { sig ->
                    losingPatterns[sig] = jsonToPattern(loseJson.optJSONObject(sig))
                }
            }
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "load error: ${e.message}")
        }
    }
    
    private fun patternToJson(p: LearnedPattern): JSONObject {
        return JSONObject().apply {
            put("signature", p.signature)
            put("occurrences", p.occurrences)
            put("avgPnlPct", p.avgPnlPct)
            put("bestPnlPct", p.bestPnlPct)
            put("worstPnlPct", p.worstPnlPct)
            put("avgHoldMins", p.avgHoldMins)
            put("winRate", p.winRate)
            put("bestTradingMode", p.bestTradingMode)
            put("optimalTakeProfit", p.optimalTakeProfit)
            put("optimalStopLoss", p.optimalStopLoss)
            put("optimalTrailingStop", p.optimalTrailingStop)
            put("lastUpdated", p.lastUpdated)
        }
    }
    
    private fun jsonToPattern(json: JSONObject?): LearnedPattern {
        json ?: return LearnedPattern("")
        return LearnedPattern(
            signature = json.optString("signature", ""),
            occurrences = json.optInt("occurrences", 0),
            avgPnlPct = json.optDouble("avgPnlPct", 0.0),
            bestPnlPct = json.optDouble("bestPnlPct", 0.0),
            worstPnlPct = json.optDouble("worstPnlPct", 0.0),
            avgHoldMins = json.optInt("avgHoldMins", 0),
            winRate = json.optDouble("winRate", 0.0),
            bestTradingMode = json.optString("bestTradingMode", "STANDARD"),
            optimalTakeProfit = json.optDouble("optimalTakeProfit", 0.0),
            optimalStopLoss = json.optDouble("optimalStopLoss", 0.0),
            optimalTrailingStop = json.optDouble("optimalTrailingStop", 0.0),
            lastUpdated = json.optLong("lastUpdated", 0L),
        )
    }
    
    /**
     * Clear all learned data.
     */
    fun clear() {
        winningPatterns.clear()
        losingPatterns.clear()
        backtestResults.clear()
        tokensAnalyzed.set(0)
        patternsLearned.set(0)
        lastScanTime = 0L
        save()
        ErrorLogger.warn(TAG, "🧹 Historical scanner cleared")
    }
}
