package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DataPipeline — Structured Multi-Layer Data Flow
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * Layer 1 — Discovery:     Dexscreener, Pump.fun
 * Layer 2 — Validation:    RugCheck, Solscan holders
 * Layer 3 — Live Signal:   Birdeye, Helius
 * Layer 4 — AI Features:   Computed alpha features
 * 
 * HIGH-ALPHA FEATURES:
 * - Holder acceleration → how fast new wallets are entering
 * - Repeat wallet detection → same wallets buying multiple tokens
 * - Buy clustering → multiple buys in same block = coordinated bots
 * - Volume vs price divergence → volume up, price flat = distribution
 */
object DataPipeline {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    
    // Cache to avoid hammering APIs
    private val cache = ConcurrentHashMap<String, CachedData>()
    private data class CachedData(val data: Any, val timestamp: Long)
    private const val CACHE_TTL_MS = 30_000L  // 30 seconds
    
    // Track holder counts over time for acceleration detection
    private val holderHistory = ConcurrentHashMap<String, MutableList<HolderSnapshot>>()
    data class HolderSnapshot(val count: Int, val timestamp: Long)
    
    // Track wallets across tokens for repeat detection
    private val walletTokenMap = ConcurrentHashMap<String, MutableSet<String>>()
    
    // ═══════════════════════════════════════════════════════════════════
    // COMPREHENSIVE TOKEN ANALYSIS
    // ═══════════════════════════════════════════════════════════════════
    
    data class AlphaSignals(
        // Core metrics
        val buyPressure: Double,          // buys / (buys + sells)
        val volumeH1: Double,
        val liquidityUsd: Double,
        val mcapUsd: Double,
        val pairAgeMinutes: Int,
        
        // High-alpha features
        val holderAcceleration: Double,   // Rate of new wallets entering
        val whaleRatio: Double,           // top10 holdings / total supply
        val repeatWalletScore: Double,    // Coordinated bot farm detection
        val buyClusteringScore: Double,   // Multiple buys in same block
        val volumePriceDivergence: Double,// Volume up + price flat = distribution
        
        // Risk indicators
        val rugScore: Int,                // 0-100 from rugcheck
        val topHolderPct: Double,         // Top holder concentration
        val mintAuthorityDisabled: Boolean,
        val freezeAuthorityDisabled: Boolean,
        
        // Momentum
        val txVelocity: Double,           // Transactions per minute
        val priceChange5m: Double,
        val priceChange1h: Double,
        
        // Quality grade
        val overallGrade: String,         // A, B, C, D, F
        val confidence: Double,           // 0-100 data quality confidence
    )
    
    /**
     * Get comprehensive alpha signals for a token.
     * Combines data from multiple sources in a structured pipeline.
     */
    suspend fun getAlphaSignals(
        mint: String,
        cfg: BotConfig,
        onLog: (String) -> Unit = {},
    ): AlphaSignals? = withContext(Dispatchers.IO) {
        try {
            // Parallel fetch from multiple sources
            val dexDeferred = async { fetchDexscreener(mint) }
            val rugDeferred = async { fetchRugcheck(mint) }
            val holdersDeferred = async { fetchSolscanHolders(mint, cfg) }
            
            val dexData = dexDeferred.await()
            val rugData = rugDeferred.await()
            val holdersData = holdersDeferred.await()
            
            if (dexData == null) {
                onLog("⚠️ No Dexscreener data for ${mint.take(8)}")
                return@withContext null
            }
            
            // Parse Dexscreener data
            val pair = dexData.optJSONArray("pairs")?.optJSONObject(0)
            if (pair == null) return@withContext null
            
            val priceUsd = pair.optDouble("priceUsd", 0.0)
            val liquidityUsd = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
            val volumeH1 = pair.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0
            val mcapUsd = pair.optDouble("fdv", pair.optDouble("marketCap", 0.0))
            val buysH1 = pair.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
            val sellsH1 = pair.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
            val priceChange5m = pair.optJSONObject("priceChange")?.optDouble("m5", 0.0) ?: 0.0
            val priceChange1h = pair.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0
            val pairCreatedAt = pair.optLong("pairCreatedAt", System.currentTimeMillis())
            val pairAgeMinutes = ((System.currentTimeMillis() - pairCreatedAt) / 60_000).toInt()
            
            // Calculate buy pressure
            val totalTxns = buysH1 + sellsH1
            val buyPressure = if (totalTxns > 0) buysH1.toDouble() / totalTxns else 0.5
            
            // Transaction velocity (txns per minute over last hour)
            val txVelocity = totalTxns.toDouble() / 60.0
            
            // Parse RugCheck data
            val rugScore = rugData?.optInt("score_normalised", 50) ?: 50
            val mintAuthority = rugData?.optBoolean("mintAuthorityDisabled", false) ?: false
            val freezeAuthority = rugData?.optBoolean("freezeAuthorityDisabled", false) ?: false
            
            // Parse holder data
            val holderCount = holdersData?.optInt("total", 0) ?: 0
            val topHolders = holdersData?.optJSONArray("data")
            val topHolderPct = calculateTopHolderPct(topHolders)
            val whaleRatio = calculateWhaleRatio(topHolders)
            
            // HIGH-ALPHA FEATURES
            
            // 1. Holder acceleration
            val holderAcceleration = calculateHolderAcceleration(mint, holderCount)
            
            // 2. Repeat wallet detection
            val repeatWalletScore = calculateRepeatWalletScore(mint, topHolders)
            
            // 3. Buy clustering (from recent transactions)
            val buyClusteringScore = calculateBuyClusteringScore(buysH1, sellsH1, txVelocity)
            
            // 4. Volume vs Price divergence
            val volumePriceDivergence = calculateVolumePriceDivergence(
                volumeH1, liquidityUsd, priceChange5m, priceChange1h
            )
            
            // Calculate overall grade
            val (grade, confidence) = calculateGrade(
                buyPressure, holderAcceleration, whaleRatio, repeatWalletScore,
                volumePriceDivergence, rugScore, topHolderPct, txVelocity
            )
            
            AlphaSignals(
                buyPressure = buyPressure,
                volumeH1 = volumeH1,
                liquidityUsd = liquidityUsd,
                mcapUsd = mcapUsd,
                pairAgeMinutes = pairAgeMinutes,
                holderAcceleration = holderAcceleration,
                whaleRatio = whaleRatio,
                repeatWalletScore = repeatWalletScore,
                buyClusteringScore = buyClusteringScore,
                volumePriceDivergence = volumePriceDivergence,
                rugScore = rugScore,
                topHolderPct = topHolderPct,
                mintAuthorityDisabled = mintAuthority,
                freezeAuthorityDisabled = freezeAuthority,
                txVelocity = txVelocity,
                priceChange5m = priceChange5m,
                priceChange1h = priceChange1h,
                overallGrade = grade,
                confidence = confidence,
            )
        } catch (e: Exception) {
            onLog("⚠️ DataPipeline error: ${e.message}")
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LAYER 1: DISCOVERY SOURCES
    // ═══════════════════════════════════════════════════════════════════
    
    private fun fetchDexscreener(mint: String): JSONObject? {
        val cacheKey = "dex_$mint"
        cache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.timestamp < CACHE_TTL_MS) {
                return it.data as? JSONObject
            }
        }
        
        return try {
            val request = Request.Builder()
                .url("https://api.dexscreener.com/latest/dex/tokens/$mint")
                .header("Accept", "application/json")
                .build()
            
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            
            cache[cacheKey] = CachedData(json, System.currentTimeMillis())
            json
        } catch (e: Exception) { null }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LAYER 2: VALIDATION SOURCES
    // ═══════════════════════════════════════════════════════════════════
    
    private fun fetchRugcheck(mint: String): JSONObject? {
        val cacheKey = "rug_$mint"
        cache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.timestamp < CACHE_TTL_MS * 2) {
                return it.data as? JSONObject
            }
        }
        
        return try {
            val request = Request.Builder()
                .url("https://api.rugcheck.xyz/v1/tokens/$mint/report/summary")
                .header("Accept", "application/json")
                .build()
            
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            
            cache[cacheKey] = CachedData(json, System.currentTimeMillis())
            json
        } catch (e: Exception) { null }
    }
    
    private fun fetchSolscanHolders(mint: String, cfg: BotConfig): JSONObject? {
        val cacheKey = "holders_$mint"
        cache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.timestamp < CACHE_TTL_MS * 3) {
                return it.data as? JSONObject
            }
        }
        
        return try {
            val request = Request.Builder()
                .url("https://public-api.solscan.io/token/holders?tokenAddress=$mint&limit=20")
                .header("Accept", "application/json")
                .build()
            
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            
            cache[cacheKey] = CachedData(json, System.currentTimeMillis())
            json
        } catch (e: Exception) { null }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // HIGH-ALPHA FEATURE CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Holder Acceleration — How fast new wallets are entering.
     * Positive = wallets joining fast = bullish
     * Negative = wallets leaving = bearish
     */
    private fun calculateHolderAcceleration(mint: String, currentCount: Int): Double {
        val history = holderHistory.getOrPut(mint) { mutableListOf() }
        val now = System.currentTimeMillis()
        
        // Add current snapshot
        history.add(HolderSnapshot(currentCount, now))
        
        // Keep only last 10 minutes of data
        history.removeIf { now - it.timestamp > 600_000 }
        
        if (history.size < 2) return 0.0
        
        // Calculate acceleration (change in holder count per minute)
        val oldest = history.first()
        val newest = history.last()
        val timeDeltaMinutes = (newest.timestamp - oldest.timestamp) / 60_000.0
        
        if (timeDeltaMinutes < 1) return 0.0
        
        val holderDelta = newest.count - oldest.count
        return (holderDelta / timeDeltaMinutes).coerceIn(-100.0, 100.0)
    }
    
    /**
     * Repeat Wallet Detection — Same wallets buying multiple tokens = bot farms.
     * High score = suspicious coordinated buying
     */
    private fun calculateRepeatWalletScore(mint: String, topHolders: JSONArray?): Double {
        if (topHolders == null || topHolders.length() == 0) return 0.0
        
        var repeatCount = 0
        val newWallets = mutableSetOf<String>()
        
        for (i in 0 until minOf(topHolders.length(), 10)) {
            val holder = topHolders.optJSONObject(i) ?: continue
            val wallet = holder.optString("owner", holder.optString("address", ""))
            if (wallet.isEmpty()) continue
            
            newWallets.add(wallet)
            
            // Check if this wallet has been seen in other tokens
            val tokensHeld = walletTokenMap.getOrPut(wallet) { mutableSetOf() }
            if (tokensHeld.size > 0 && !tokensHeld.contains(mint)) {
                repeatCount++
            }
            tokensHeld.add(mint)
        }
        
        // Score: % of top holders that are repeat buyers across tokens
        return (repeatCount.toDouble() / maxOf(newWallets.size, 1) * 100).coerceIn(0.0, 100.0)
    }
    
    /**
     * Buy Clustering — Multiple buys in same timeframe = coordinated bots.
     * High tx velocity with high buy ratio = possible wash trading or bot activity.
     */
    private fun calculateBuyClusteringScore(buys: Int, sells: Int, txVelocity: Double): Double {
        if (buys + sells < 10) return 0.0
        
        val buyRatio = buys.toDouble() / (buys + sells)
        
        // Suspicious: very high tx velocity with very high buy ratio
        // Natural trading has more variance
        val suspiciousVelocity = txVelocity > 5.0  // More than 5 tx/min
        val suspiciousBuyRatio = buyRatio > 0.85   // More than 85% buys
        
        return when {
            suspiciousVelocity && suspiciousBuyRatio -> 80.0  // Likely coordinated
            suspiciousVelocity -> 40.0
            suspiciousBuyRatio -> 30.0
            else -> 0.0
        }
    }
    
    /**
     * Volume vs Price Divergence — Volume up + price flat = DISTRIBUTION.
     * This is a critical warning signal.
     */
    private fun calculateVolumePriceDivergence(
        volumeH1: Double,
        liquidityUsd: Double,
        priceChange5m: Double,
        priceChange1h: Double,
    ): Double {
        if (liquidityUsd <= 0) return 0.0
        
        // Volume to liquidity ratio (high = lots of trading relative to pool)
        val volLiqRatio = volumeH1 / liquidityUsd
        
        // Price is flat if change is small
        val priceIsFlat = kotlin.math.abs(priceChange5m) < 3 && kotlin.math.abs(priceChange1h) < 5
        
        // High volume + flat price = distribution
        return when {
            volLiqRatio > 0.5 && priceIsFlat -> 80.0  // Strong distribution signal
            volLiqRatio > 0.3 && priceIsFlat -> 50.0  // Moderate signal
            volLiqRatio > 0.5 && priceChange1h < -5 -> 70.0  // Selling off
            else -> 0.0
        }
    }
    
    /**
     * Calculate top holder concentration percentage.
     */
    private fun calculateTopHolderPct(topHolders: JSONArray?): Double {
        if (topHolders == null || topHolders.length() == 0) return 0.0
        
        var totalPct = 0.0
        for (i in 0 until minOf(topHolders.length(), 10)) {
            val holder = topHolders.optJSONObject(i) ?: continue
            totalPct += holder.optDouble("percentage", holder.optDouble("pct", 0.0))
        }
        
        return totalPct.coerceIn(0.0, 100.0)
    }
    
    /**
     * Calculate whale ratio (top 10 vs rest).
     */
    private fun calculateWhaleRatio(topHolders: JSONArray?): Double {
        return calculateTopHolderPct(topHolders) / 100.0
    }
    
    /**
     * Calculate overall grade and confidence.
     */
    private fun calculateGrade(
        buyPressure: Double,
        holderAcceleration: Double,
        whaleRatio: Double,
        repeatWalletScore: Double,
        volumePriceDivergence: Double,
        rugScore: Int,
        topHolderPct: Double,
        txVelocity: Double,
    ): Pair<String, Double> {
        var score = 50.0
        var dataPoints = 0
        
        // Positive factors
        if (buyPressure > 0.55) { score += 15; dataPoints++ }
        if (buyPressure > 0.60) { score += 10; dataPoints++ }
        if (holderAcceleration > 5) { score += 10; dataPoints++ }
        if (rugScore > 70) { score += 10; dataPoints++ }
        if (txVelocity > 1.0) { score += 5; dataPoints++ }
        
        // Negative factors
        if (buyPressure < 0.45) { score -= 20; dataPoints++ }
        if (whaleRatio > 0.5) { score -= 15; dataPoints++ }
        if (repeatWalletScore > 50) { score -= 20; dataPoints++ }  // Bot farm warning
        if (volumePriceDivergence > 50) { score -= 25; dataPoints++ }  // Distribution
        if (rugScore < 40) { score -= 25; dataPoints++ }
        if (topHolderPct > 50) { score -= 15; dataPoints++ }
        if (holderAcceleration < -5) { score -= 15; dataPoints++ }  // Wallets leaving
        
        score = score.coerceIn(0.0, 100.0)
        
        val grade = when {
            score >= 80 -> "A"
            score >= 65 -> "B"
            score >= 50 -> "C"
            score >= 35 -> "D"
            else -> "F"
        }
        
        // Confidence based on data points available
        val confidence = (dataPoints.toDouble() / 12.0 * 100).coerceIn(20.0, 100.0)
        
        return grade to confidence
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // UTILITY: Format for logging
    // ═══════════════════════════════════════════════════════════════════
    
    fun formatAlphaSignals(mint: String, signals: AlphaSignals): String {
        val gradeEmoji = when (signals.overallGrade) {
            "A" -> "🅰️"
            "B" -> "🅱️"
            "C" -> "©️"
            "D" -> "🔸"
            else -> "❌"
        }
        
        val warnings = mutableListOf<String>()
        if (signals.volumePriceDivergence > 50) warnings.add("DISTRIB")
        if (signals.repeatWalletScore > 50) warnings.add("BOTS")
        if (signals.whaleRatio > 0.5) warnings.add("WHALES")
        if (signals.holderAcceleration < -3) warnings.add("EXODUS")
        
        return buildString {
            append("$gradeEmoji ${mint.take(6)}... | ")
            append("Buy:${(signals.buyPressure*100).toInt()}% ")
            append("Acc:${signals.holderAcceleration.toInt()}/min ")
            append("RC:${signals.rugScore} ")
            append("Vol/Div:${signals.volumePriceDivergence.toInt()}")
            if (warnings.isNotEmpty()) {
                append(" ⚠️ ${warnings.joinToString(",")}")
            }
        }
    }
    
    /**
     * Clear old cache entries and wallet tracking data.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.timestamp > CACHE_TTL_MS * 10 }
        holderHistory.entries.removeIf { it.value.isEmpty() }
        
        // Limit wallet tracking to prevent memory bloat
        if (walletTokenMap.size > 10000) {
            val toRemove = walletTokenMap.keys.take(5000)
            toRemove.forEach { walletTokenMap.remove(it) }
        }
    }
}
