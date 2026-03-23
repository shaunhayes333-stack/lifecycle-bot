package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TradingMemory — Persistent learning layer for trading patterns
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * Learns from:
 * 1. Bad tokens (features that led to losses)
 * 2. Bad trades (entry conditions that failed)
 * 3. Chart patterns that preceded rugs
 * 4. Winning patterns (for positive reinforcement)
 * 
 * All data persists to SharedPreferences for cross-session learning.
 */
object TradingMemory {

    private const val PREFS_NAME = "trading_memory"
    private const val KEY_BAD_TOKEN_FEATURES = "bad_token_features"
    private const val KEY_BAD_TRADE_PATTERNS = "bad_trade_patterns"
    private const val KEY_RUG_PATTERNS = "rug_patterns"
    private const val KEY_WIN_PATTERNS = "win_patterns"
    private const val KEY_CREATOR_BLACKLIST = "creator_blacklist"
    
    // In-memory caches
    private val badTokenFeatures = ConcurrentHashMap<String, TokenFeatureRecord>()
    private val badTradePatterns = ConcurrentHashMap<String, TradePatternRecord>()
    private val rugPatterns = ConcurrentHashMap<String, RugPatternRecord>()
    private val winPatterns = ConcurrentHashMap<String, WinPatternRecord>()
    private val creatorBlacklist = ConcurrentHashMap<String, CreatorRecord>()
    
    private var ctx: Context? = null
    private var isLoaded = false

    // ═══════════════════════════════════════════════════════════════════
    // Data Classes
    // ═══════════════════════════════════════════════════════════════════

    data class TokenFeatureRecord(
        val mint: String,
        val symbol: String,
        val lossCount: Int,
        val avgLossPct: Double,
        val features: TokenFeatures,
        val lastSeen: Long,
    )
    
    data class TokenFeatures(
        val initialLiquidity: Double,      // Liquidity when first seen
        val initialMcap: Double,           // Mcap when first seen
        val holderConcentration: Double,   // Top 10 holders %
        val devHoldingPct: Double,         // Dev/creator holding %
        val ageHoursAtEntry: Double,       // How old was token at entry
        val hadSocials: Boolean,           // Twitter/Telegram/Website
        val pumpFunToken: Boolean,         // From pump.fun
        val priceVolatility: Double,       // Price swings %
        val volumeToLiqRatio: Double,      // Volume/Liquidity ratio
    )

    data class TradePatternRecord(
        val patternKey: String,            // e.g., "phase=pumping+ema=BEAR_FAN"
        val lossCount: Int,
        val winCount: Int,
        val avgLossPct: Double,
        val avgWinPct: Double,
        val lastUpdated: Long,
    )

    data class RugPatternRecord(
        val patternKey: String,            // Describes the rug pattern
        val occurrences: Int,
        val indicators: RugIndicators,
        val lastSeen: Long,
    )
    
    data class RugIndicators(
        val liquidityDropPct: Double,      // How fast liquidity dropped
        val priceDropPct: Double,          // Price drop in short time
        val volumeSpikeBeforeRug: Boolean, // High volume before rug
        val holderDumpDetected: Boolean,   // Large holder sold
        val timeFromLaunchHours: Double,   // How soon after launch
    )

    data class WinPatternRecord(
        val patternKey: String,
        val winCount: Int,
        val avgWinPct: Double,
        val avgHoldTimeMinutes: Double,
        val lastUpdated: Long,
    )
    
    data class CreatorRecord(
        val creatorWallet: String,
        val rugCount: Int,
        val tokens: List<String>,          // Mints created by this wallet
        val lastSeen: Long,
    )

    // ═══════════════════════════════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        ctx = context.applicationContext
        if (!isLoaded) {
            load()
            isLoaded = true
            ErrorLogger.info("TradingMemory", "Loaded: ${badTokenFeatures.size} bad tokens, " +
                "${badTradePatterns.size} trade patterns, ${rugPatterns.size} rug patterns, " +
                "${creatorBlacklist.size} blacklisted creators")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Learning Functions
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Learn from a bad trade - record the token features and trade pattern
     */
    fun learnFromBadTrade(
        mint: String,
        symbol: String,
        lossPct: Double,
        phase: String,
        emaFan: String,
        source: String,
        liquidity: Double,
        mcap: Double,
        holderConcentration: Double = 0.0,
        devHoldingPct: Double = 0.0,
        ageHours: Double = 0.0,
        hadSocials: Boolean = false,
        isPumpFun: Boolean = false,
        priceVolatility: Double = 0.0,
        volumeToLiqRatio: Double = 0.0,
    ) {
        // SAFETY: Only learn from MEANINGFUL losses (> 2% loss)
        // Scratch trades (-2% to +2%) are noise, not signal
        if (lossPct > -2.0) {
            ErrorLogger.debug("TradingMemory", "Skipped scratch trade: $symbol loss=${lossPct.toInt()}%")
            return
        }
        
        val now = System.currentTimeMillis()
        
        // 1. Record bad token features
        val existing = badTokenFeatures[mint]
        val features = TokenFeatures(
            initialLiquidity = liquidity,
            initialMcap = mcap,
            holderConcentration = holderConcentration,
            devHoldingPct = devHoldingPct,
            ageHoursAtEntry = ageHours,
            hadSocials = hadSocials,
            pumpFunToken = isPumpFun,
            priceVolatility = priceVolatility,
            volumeToLiqRatio = volumeToLiqRatio,
        )
        
        val newLossCount = (existing?.lossCount ?: 0) + 1
        val newAvgLoss = if (existing != null) {
            (existing.avgLossPct * existing.lossCount + lossPct) / newLossCount
        } else lossPct
        
        badTokenFeatures[mint] = TokenFeatureRecord(
            mint = mint,
            symbol = symbol,
            lossCount = newLossCount,
            avgLossPct = newAvgLoss,
            features = features,
            lastSeen = now,
        )
        
        // 2. Record bad trade pattern
        val patternKey = "phase=$phase+ema=$emaFan+src=$source"
        val existingPattern = badTradePatterns[patternKey]
        val newPatternLosses = (existingPattern?.lossCount ?: 0) + 1
        val newPatternAvgLoss = if (existingPattern != null) {
            (existingPattern.avgLossPct * existingPattern.lossCount + lossPct) / newPatternLosses
        } else lossPct
        
        badTradePatterns[patternKey] = TradePatternRecord(
            patternKey = patternKey,
            lossCount = newPatternLosses,
            winCount = existingPattern?.winCount ?: 0,
            avgLossPct = newPatternAvgLoss,
            avgWinPct = existingPattern?.avgWinPct ?: 0.0,
            lastUpdated = now,
        )
        
        // 3. Learn feature correlations for future avoidance
        learnBadFeatureCorrelations(features, lossPct)
        
        save()
        ErrorLogger.info("TradingMemory", "Learned bad trade: $symbol loss=${lossPct.toInt()}% pattern=$patternKey")
    }

    /**
     * Learn from a winning trade - reinforce good patterns
     */
    fun learnFromWinningTrade(
        mint: String,
        symbol: String,
        winPct: Double,
        phase: String,
        emaFan: String,
        source: String,
        holdTimeMinutes: Double,
    ) {
        val now = System.currentTimeMillis()
        val patternKey = "phase=$phase+ema=$emaFan+src=$source"
        
        // Update trade pattern with win
        val existingPattern = badTradePatterns[patternKey]
        if (existingPattern != null) {
            badTradePatterns[patternKey] = existingPattern.copy(
                winCount = existingPattern.winCount + 1,
                avgWinPct = (existingPattern.avgWinPct * existingPattern.winCount + winPct) / (existingPattern.winCount + 1),
                lastUpdated = now,
            )
        }
        
        // Record win pattern
        val existingWin = winPatterns[patternKey]
        val newWinCount = (existingWin?.winCount ?: 0) + 1
        val newAvgWin = if (existingWin != null) {
            (existingWin.avgWinPct * existingWin.winCount + winPct) / newWinCount
        } else winPct
        val newAvgHold = if (existingWin != null) {
            (existingWin.avgHoldTimeMinutes * existingWin.winCount + holdTimeMinutes) / newWinCount
        } else holdTimeMinutes
        
        winPatterns[patternKey] = WinPatternRecord(
            patternKey = patternKey,
            winCount = newWinCount,
            avgWinPct = newAvgWin,
            avgHoldTimeMinutes = newAvgHold,
            lastUpdated = now,
        )
        
        save()
        ErrorLogger.info("TradingMemory", "Learned win: $symbol +${winPct.toInt()}% pattern=$patternKey")
    }

    /**
     * Learn from a detected rug pull
     */
    fun learnFromRug(
        mint: String,
        symbol: String,
        creatorWallet: String?,
        liquidityDropPct: Double,
        priceDropPct: Double,
        volumeSpikeBeforeRug: Boolean,
        holderDumpDetected: Boolean,
        timeFromLaunchHours: Double,
    ) {
        val now = System.currentTimeMillis()
        
        // Build pattern key from indicators
        val patternKey = buildRugPatternKey(liquidityDropPct, priceDropPct, volumeSpikeBeforeRug, timeFromLaunchHours)
        
        val existing = rugPatterns[patternKey]
        val indicators = RugIndicators(
            liquidityDropPct = liquidityDropPct,
            priceDropPct = priceDropPct,
            volumeSpikeBeforeRug = volumeSpikeBeforeRug,
            holderDumpDetected = holderDumpDetected,
            timeFromLaunchHours = timeFromLaunchHours,
        )
        
        rugPatterns[patternKey] = RugPatternRecord(
            patternKey = patternKey,
            occurrences = (existing?.occurrences ?: 0) + 1,
            indicators = indicators,
            lastSeen = now,
        )
        
        // Blacklist creator if known
        if (!creatorWallet.isNullOrBlank()) {
            val existingCreator = creatorBlacklist[creatorWallet]
            val tokens = (existingCreator?.tokens ?: emptyList()).toMutableList()
            if (mint !in tokens) tokens.add(mint)
            
            creatorBlacklist[creatorWallet] = CreatorRecord(
                creatorWallet = creatorWallet,
                rugCount = (existingCreator?.rugCount ?: 0) + 1,
                tokens = tokens,
                lastSeen = now,
            )
            ErrorLogger.warn("TradingMemory", "Creator blacklisted: ${creatorWallet.take(12)}... rugCount=${creatorBlacklist[creatorWallet]?.rugCount}")
        }
        
        save()
        ErrorLogger.warn("TradingMemory", "Learned rug pattern: $symbol drop=${priceDropPct.toInt()}% pattern=$patternKey")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Query Functions - Use learned data for decisions
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if a token has bad features similar to past losers
     * Returns a risk score 0-100 (higher = more risky)
     */
    fun getTokenRiskScore(
        liquidity: Double,
        mcap: Double,
        holderConcentration: Double,
        devHoldingPct: Double,
        ageHours: Double,
        hadSocials: Boolean,
        isPumpFun: Boolean,
        volumeToLiqRatio: Double,
    ): Int {
        var riskScore = 0
        var matchCount = 0
        
        // Compare against bad token features
        for (record in badTokenFeatures.values) {
            if (record.lossCount < 2) continue  // Need multiple losses to be significant
            
            val f = record.features
            var similarity = 0
            
            // Similar liquidity range (within 50%)
            if (liquidity > 0 && f.initialLiquidity > 0) {
                val ratio = liquidity / f.initialLiquidity
                if (ratio in 0.5..2.0) similarity += 15
            }
            
            // Similar mcap range
            if (mcap > 0 && f.initialMcap > 0) {
                val ratio = mcap / f.initialMcap
                if (ratio in 0.5..2.0) similarity += 15
            }
            
            // High holder concentration (like bad token)
            if (holderConcentration > 50 && f.holderConcentration > 50) similarity += 20
            
            // High dev holding (like bad token)
            if (devHoldingPct > 20 && f.devHoldingPct > 20) similarity += 20
            
            // Similar age profile
            if (ageHours < 2 && f.ageHoursAtEntry < 2) similarity += 10
            
            // No socials like bad token
            if (!hadSocials && !f.hadSocials) similarity += 10
            
            // Similar volume/liq ratio
            if (volumeToLiqRatio > 5 && f.volumeToLiqRatio > 5) similarity += 10
            
            if (similarity >= 40) {
                riskScore += similarity
                matchCount++
            }
        }
        
        // Average across matches, cap at 100
        return if (matchCount > 0) minOf(riskScore / matchCount, 100) else 0
    }

    /**
     * Check if a trade pattern has historically been bad
     * Returns penalty to apply to entry score (0-50)
     */
    fun getPatternPenalty(phase: String, emaFan: String, source: String): Int {
        val patternKey = "phase=$phase+ema=$emaFan+src=$source"
        val record = badTradePatterns[patternKey] ?: return 0
        
        // Need significant sample size
        val totalTrades = record.lossCount + record.winCount
        if (totalTrades < 3) return 0
        
        val lossRate = record.lossCount.toDouble() / totalTrades
        
        // High loss rate = high penalty
        return when {
            lossRate >= 0.8 && record.lossCount >= 5 -> 50  // Very bad pattern
            lossRate >= 0.7 && record.lossCount >= 4 -> 40
            lossRate >= 0.6 && record.lossCount >= 3 -> 30
            lossRate >= 0.5 && record.lossCount >= 3 -> 20
            else -> 0
        }
    }

    /**
     * Check if a pattern matches known rug indicators
     * Returns true if token looks like it might rug
     */
    fun matchesRugPattern(
        liquidityDropPct: Double,
        priceDropPct: Double,
        volumeSpike: Boolean,
        timeFromLaunchHours: Double,
    ): Boolean {
        // Check against learned rug patterns
        for (record in rugPatterns.values) {
            if (record.occurrences < 2) continue  // Need multiple occurrences
            
            val ind = record.indicators
            var matches = 0
            
            if (liquidityDropPct >= ind.liquidityDropPct * 0.7) matches++
            if (priceDropPct >= ind.priceDropPct * 0.7) matches++
            if (volumeSpike == ind.volumeSpikeBeforeRug) matches++
            if (timeFromLaunchHours <= ind.timeFromLaunchHours * 1.5) matches++
            
            // 3+ matches = likely rug pattern
            if (matches >= 3) {
                ErrorLogger.warn("TradingMemory", "Rug pattern match: ${record.patternKey} (${record.occurrences} occurrences)")
                return true
            }
        }
        return false
    }

    /**
     * Check if creator is blacklisted
     */
    fun isCreatorBlacklisted(creatorWallet: String): Boolean {
        val record = creatorBlacklist[creatorWallet] ?: return false
        return record.rugCount >= 1
    }

    /**
     * Get creator rug count
     */
    fun getCreatorRugCount(creatorWallet: String): Int {
        return creatorBlacklist[creatorWallet]?.rugCount ?: 0
    }

    /**
     * Check if this exact token has been a loser before
     */
    fun getTokenLossHistory(mint: String): TokenFeatureRecord? {
        return badTokenFeatures[mint]
    }

    /**
     * Get win rate for a pattern
     */
    fun getPatternWinRate(phase: String, emaFan: String, source: String): Double {
        val patternKey = "phase=$phase+ema=$emaFan+src=$source"
        val record = badTradePatterns[patternKey] ?: return 0.5  // Default 50%
        
        val total = record.lossCount + record.winCount
        return if (total > 0) record.winCount.toDouble() / total else 0.5
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helper Functions
    // ═══════════════════════════════════════════════════════════════════

    private fun buildRugPatternKey(
        liquidityDropPct: Double,
        priceDropPct: Double,
        volumeSpike: Boolean,
        timeFromLaunchHours: Double,
    ): String {
        val liqBucket = when {
            liquidityDropPct >= 90 -> "liq90+"
            liquidityDropPct >= 70 -> "liq70-90"
            liquidityDropPct >= 50 -> "liq50-70"
            else -> "liq<50"
        }
        val priceBucket = when {
            priceDropPct >= 90 -> "price90+"
            priceDropPct >= 70 -> "price70-90"
            priceDropPct >= 50 -> "price50-70"
            else -> "price<50"
        }
        val timeBucket = when {
            timeFromLaunchHours < 1 -> "time<1h"
            timeFromLaunchHours < 6 -> "time1-6h"
            timeFromLaunchHours < 24 -> "time6-24h"
            else -> "time24h+"
        }
        val vol = if (volumeSpike) "volSpike" else "noSpike"
        
        return "$liqBucket+$priceBucket+$timeBucket+$vol"
    }

    private fun learnBadFeatureCorrelations(features: TokenFeatures, lossPct: Double) {
        // This could be expanded to use ML, but for now we just track in the records
        // The getTokenRiskScore function uses these correlations
    }

    // ═══════════════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════════════

    private fun save() {
        val context = ctx ?: return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_BAD_TOKEN_FEATURES, serializeBadTokens())
                putString(KEY_BAD_TRADE_PATTERNS, serializeTradePatterns())
                putString(KEY_RUG_PATTERNS, serializeRugPatterns())
                putString(KEY_WIN_PATTERNS, serializeWinPatterns())
                putString(KEY_CREATOR_BLACKLIST, serializeCreatorBlacklist())
                apply()
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "Save error: ${e.message}")
        }
    }

    private fun load() {
        val context = ctx ?: return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            deserializeBadTokens(prefs.getString(KEY_BAD_TOKEN_FEATURES, null))
            deserializeTradePatterns(prefs.getString(KEY_BAD_TRADE_PATTERNS, null))
            deserializeRugPatterns(prefs.getString(KEY_RUG_PATTERNS, null))
            deserializeWinPatterns(prefs.getString(KEY_WIN_PATTERNS, null))
            deserializeCreatorBlacklist(prefs.getString(KEY_CREATOR_BLACKLIST, null))
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "Load error: ${e.message}")
        }
    }

    // Serialization helpers
    private fun serializeBadTokens(): String {
        val arr = JSONArray()
        for (record in badTokenFeatures.values) {
            val obj = JSONObject().apply {
                put("mint", record.mint)
                put("symbol", record.symbol)
                put("lossCount", record.lossCount)
                put("avgLossPct", record.avgLossPct)
                put("lastSeen", record.lastSeen)
                put("features", JSONObject().apply {
                    put("initialLiquidity", record.features.initialLiquidity)
                    put("initialMcap", record.features.initialMcap)
                    put("holderConcentration", record.features.holderConcentration)
                    put("devHoldingPct", record.features.devHoldingPct)
                    put("ageHoursAtEntry", record.features.ageHoursAtEntry)
                    put("hadSocials", record.features.hadSocials)
                    put("pumpFunToken", record.features.pumpFunToken)
                    put("priceVolatility", record.features.priceVolatility)
                    put("volumeToLiqRatio", record.features.volumeToLiqRatio)
                })
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeBadTokens(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val featuresObj = obj.getJSONObject("features")
                val features = TokenFeatures(
                    initialLiquidity = featuresObj.optDouble("initialLiquidity", 0.0),
                    initialMcap = featuresObj.optDouble("initialMcap", 0.0),
                    holderConcentration = featuresObj.optDouble("holderConcentration", 0.0),
                    devHoldingPct = featuresObj.optDouble("devHoldingPct", 0.0),
                    ageHoursAtEntry = featuresObj.optDouble("ageHoursAtEntry", 0.0),
                    hadSocials = featuresObj.optBoolean("hadSocials", false),
                    pumpFunToken = featuresObj.optBoolean("pumpFunToken", false),
                    priceVolatility = featuresObj.optDouble("priceVolatility", 0.0),
                    volumeToLiqRatio = featuresObj.optDouble("volumeToLiqRatio", 0.0),
                )
                val record = TokenFeatureRecord(
                    mint = obj.getString("mint"),
                    symbol = obj.optString("symbol", ""),
                    lossCount = obj.getInt("lossCount"),
                    avgLossPct = obj.getDouble("avgLossPct"),
                    features = features,
                    lastSeen = obj.getLong("lastSeen"),
                )
                badTokenFeatures[record.mint] = record
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "deserializeBadTokens error: ${e.message}")
        }
    }

    private fun serializeTradePatterns(): String {
        val arr = JSONArray()
        for (record in badTradePatterns.values) {
            arr.put(JSONObject().apply {
                put("patternKey", record.patternKey)
                put("lossCount", record.lossCount)
                put("winCount", record.winCount)
                put("avgLossPct", record.avgLossPct)
                put("avgWinPct", record.avgWinPct)
                put("lastUpdated", record.lastUpdated)
            })
        }
        return arr.toString()
    }

    private fun deserializeTradePatterns(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val record = TradePatternRecord(
                    patternKey = obj.getString("patternKey"),
                    lossCount = obj.getInt("lossCount"),
                    winCount = obj.optInt("winCount", 0),
                    avgLossPct = obj.getDouble("avgLossPct"),
                    avgWinPct = obj.optDouble("avgWinPct", 0.0),
                    lastUpdated = obj.getLong("lastUpdated"),
                )
                badTradePatterns[record.patternKey] = record
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "deserializeTradePatterns error: ${e.message}")
        }
    }

    private fun serializeRugPatterns(): String {
        val arr = JSONArray()
        for (record in rugPatterns.values) {
            arr.put(JSONObject().apply {
                put("patternKey", record.patternKey)
                put("occurrences", record.occurrences)
                put("lastSeen", record.lastSeen)
                put("indicators", JSONObject().apply {
                    put("liquidityDropPct", record.indicators.liquidityDropPct)
                    put("priceDropPct", record.indicators.priceDropPct)
                    put("volumeSpikeBeforeRug", record.indicators.volumeSpikeBeforeRug)
                    put("holderDumpDetected", record.indicators.holderDumpDetected)
                    put("timeFromLaunchHours", record.indicators.timeFromLaunchHours)
                })
            })
        }
        return arr.toString()
    }

    private fun deserializeRugPatterns(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val indObj = obj.getJSONObject("indicators")
                val indicators = RugIndicators(
                    liquidityDropPct = indObj.getDouble("liquidityDropPct"),
                    priceDropPct = indObj.getDouble("priceDropPct"),
                    volumeSpikeBeforeRug = indObj.getBoolean("volumeSpikeBeforeRug"),
                    holderDumpDetected = indObj.getBoolean("holderDumpDetected"),
                    timeFromLaunchHours = indObj.getDouble("timeFromLaunchHours"),
                )
                val record = RugPatternRecord(
                    patternKey = obj.getString("patternKey"),
                    occurrences = obj.getInt("occurrences"),
                    indicators = indicators,
                    lastSeen = obj.getLong("lastSeen"),
                )
                rugPatterns[record.patternKey] = record
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "deserializeRugPatterns error: ${e.message}")
        }
    }

    private fun serializeWinPatterns(): String {
        val arr = JSONArray()
        for (record in winPatterns.values) {
            arr.put(JSONObject().apply {
                put("patternKey", record.patternKey)
                put("winCount", record.winCount)
                put("avgWinPct", record.avgWinPct)
                put("avgHoldTimeMinutes", record.avgHoldTimeMinutes)
                put("lastUpdated", record.lastUpdated)
            })
        }
        return arr.toString()
    }

    private fun deserializeWinPatterns(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val record = WinPatternRecord(
                    patternKey = obj.getString("patternKey"),
                    winCount = obj.getInt("winCount"),
                    avgWinPct = obj.getDouble("avgWinPct"),
                    avgHoldTimeMinutes = obj.optDouble("avgHoldTimeMinutes", 0.0),
                    lastUpdated = obj.getLong("lastUpdated"),
                )
                winPatterns[record.patternKey] = record
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "deserializeWinPatterns error: ${e.message}")
        }
    }

    private fun serializeCreatorBlacklist(): String {
        val arr = JSONArray()
        for (record in creatorBlacklist.values) {
            arr.put(JSONObject().apply {
                put("creatorWallet", record.creatorWallet)
                put("rugCount", record.rugCount)
                put("tokens", JSONArray(record.tokens))
                put("lastSeen", record.lastSeen)
            })
        }
        return arr.toString()
    }

    private fun deserializeCreatorBlacklist(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tokensArr = obj.getJSONArray("tokens")
                val tokens = (0 until tokensArr.length()).map { tokensArr.getString(it) }
                val record = CreatorRecord(
                    creatorWallet = obj.getString("creatorWallet"),
                    rugCount = obj.getInt("rugCount"),
                    tokens = tokens,
                    lastSeen = obj.getLong("lastSeen"),
                )
                creatorBlacklist[record.creatorWallet] = record
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradingMemory", "deserializeCreatorBlacklist error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stats & Debug
    // ═══════════════════════════════════════════════════════════════════

    fun getStats(): String {
        return "TradingMemory: ${badTokenFeatures.size} bad tokens, " +
               "${badTradePatterns.size} patterns (${badTradePatterns.values.sumOf { it.lossCount }} losses, " +
               "${badTradePatterns.values.sumOf { it.winCount }} wins), " +
               "${rugPatterns.size} rug patterns, ${creatorBlacklist.size} blacklisted creators"
    }

    fun clearAll() {
        badTokenFeatures.clear()
        badTradePatterns.clear()
        rugPatterns.clear()
        winPatterns.clear()
        creatorBlacklist.clear()
        save()
        ErrorLogger.info("TradingMemory", "All memory cleared")
    }
}
