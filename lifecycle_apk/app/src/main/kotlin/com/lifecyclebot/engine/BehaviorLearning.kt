package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BehaviorLearning — Separate learning layers for good vs bad trading behavior.
 * 
 * Unlike other learning systems that just track win/loss rates, this explicitly
 * learns WHAT BEHAVIORS lead to wins vs losses, creating actionable patterns.
 * 
 * Two distinct layers:
 * 1. GOOD BEHAVIOR LAYER - Patterns from winning trades (reinforce)
 * 2. BAD BEHAVIOR LAYER - Patterns from losing trades (penalize)
 * 
 * Each layer tracks:
 * - Entry conditions that led to the outcome
 * - Market conditions at entry
 * - Token characteristics
 * - Timing patterns
 * - Mode effectiveness
 * 
 * DEFENSIVE DESIGN:
 * - Static object, no initialization
 * - All methods wrapped in try/catch
 * - Thread-safe data structures
 */
object BehaviorLearning {
    
    private const val TAG = "BehaviorLearn"
    private const val MAX_PATTERNS_PER_LAYER = 500
    private const val MIN_CONFIDENCE_TO_USE = 0.6  // 60% confidence to apply learnings
    
    // ═══════════════════════════════════════════════════════════════════
    // BEHAVIOR PATTERN DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * A captured behavior pattern from a trade.
     */
    data class BehaviorPattern(
        val patternId: String,
        val timestamp: Long = System.currentTimeMillis(),
        
        // Entry conditions
        val entryScore: Int,
        val entryPhase: String,
        val setupQuality: String,  // A+, A, B, C
        val tradingMode: String,
        
        // Market conditions
        val marketSentiment: String,  // BULL, BEAR, NEUTRAL
        val volatilityLevel: String,  // HIGH, MEDIUM, LOW
        val volumeSignal: String,     // SURGE, INCREASING, NORMAL, etc.
        
        // Token characteristics
        val liquidityBucket: String,  // <5k, 5-20k, 20-50k, 50-100k, 100k+
        val mcapBucket: String,       // <50k, 50-100k, 100-500k, 500k-1m, 1m+
        val holderConcentration: String,  // HIGH, MEDIUM, LOW
        val rugcheckScore: Int,
        
        // Timing
        val hourOfDay: Int,
        val dayOfWeek: Int,
        val holdTimeMinutes: Int,
        
        // Outcome
        val pnlPct: Double,
        val isWin: Boolean,
        val outcomeCategory: String,  // BIG_WIN, SMALL_WIN, SCRATCH, SMALL_LOSS, BIG_LOSS
    ) {
        /**
         * Generate a signature for pattern matching.
         * Patterns with same signature are considered similar.
         */
        fun getSignature(): String {
            return "${entryPhase}_${setupQuality}_${tradingMode}_${liquidityBucket}_${volumeSignal}"
        }
        
        /**
         * Get a broader signature for fuzzy matching.
         */
        fun getBroadSignature(): String {
            return "${setupQuality}_${tradingMode}_${liquidityBucket}"
        }
    }
    
    /**
     * Aggregated pattern statistics.
     */
    data class PatternStats(
        val signature: String,
        var occurrences: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnlPct: Double = 0.0,
        var avgHoldMinutes: Double = 0.0,
        var lastSeen: Long = 0,
    ) {
        val winRate: Double get() = if (occurrences > 0) wins.toDouble() / occurrences * 100 else 0.0
        val avgPnl: Double get() = if (occurrences > 0) totalPnlPct / occurrences else 0.0
        val confidence: Double get() = minOf(occurrences / 10.0, 1.0)  // Max confidence at 10 samples
        val isReliable: Boolean get() = occurrences >= 5
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LEARNING LAYERS
    // ═══════════════════════════════════════════════════════════════════
    
    // Good behavior layer - patterns that led to wins
    private val goodPatterns = ConcurrentHashMap<String, MutableList<BehaviorPattern>>()
    private val goodStats = ConcurrentHashMap<String, PatternStats>()
    
    // Bad behavior layer - patterns that led to losses
    private val badPatterns = ConcurrentHashMap<String, MutableList<BehaviorPattern>>()
    private val badStats = ConcurrentHashMap<String, PatternStats>()
    
    // Counters
    private val totalGoodRecorded = AtomicInteger(0)
    private val totalBadRecorded = AtomicInteger(0)
    
    // ═══════════════════════════════════════════════════════════════════
    // RECORDING BEHAVIOR
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Record a completed trade's behavior pattern.
     * Automatically routes to good or bad layer based on outcome.
     */
    fun recordTrade(
        entryScore: Int,
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        marketSentiment: String,
        volatilityLevel: String,
        volumeSignal: String,
        liquidityUsd: Double,
        mcapUsd: Double,
        holderTopPct: Double,
        rugcheckScore: Int,
        hourOfDay: Int,
        dayOfWeek: Int,
        holdTimeMinutes: Int,
        pnlPct: Double,
    ) {
        try {
            val isWin = pnlPct > 5.0  // Win threshold
            val isBigWin = pnlPct > 30.0
            val isBigLoss = pnlPct < -15.0
            
            val outcomeCategory = when {
                pnlPct > 30.0 -> "BIG_WIN"
                pnlPct > 5.0 -> "SMALL_WIN"
                pnlPct > -5.0 -> "SCRATCH"
                pnlPct > -15.0 -> "SMALL_LOSS"
                else -> "BIG_LOSS"
            }
            
            val pattern = BehaviorPattern(
                patternId = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
                entryScore = entryScore,
                entryPhase = entryPhase,
                setupQuality = setupQuality,
                tradingMode = tradingMode,
                marketSentiment = marketSentiment,
                volatilityLevel = volatilityLevel,
                volumeSignal = volumeSignal,
                liquidityBucket = getLiquidityBucket(liquidityUsd),
                mcapBucket = getMcapBucket(mcapUsd),
                holderConcentration = getHolderConcentration(holderTopPct),
                rugcheckScore = rugcheckScore,
                hourOfDay = hourOfDay,
                dayOfWeek = dayOfWeek,
                holdTimeMinutes = holdTimeMinutes,
                pnlPct = pnlPct,
                isWin = isWin,
                outcomeCategory = outcomeCategory,
            )
            
            val signature = pattern.getSignature()
            
            // Route to appropriate layer
            if (isWin) {
                recordGoodPattern(pattern, signature)
                totalGoodRecorded.incrementAndGet()
                
                if (isBigWin) {
                    ErrorLogger.info(TAG, "✅ BIG WIN pattern recorded: $signature | +${pnlPct.toInt()}%")
                }
            } else if (pnlPct < -5.0) {  // Only record actual losses, not scratches
                recordBadPattern(pattern, signature)
                totalBadRecorded.incrementAndGet()
                
                if (isBigLoss) {
                    ErrorLogger.info(TAG, "❌ BIG LOSS pattern recorded: $signature | ${pnlPct.toInt()}%")
                }
            }
            // Scratches (-5% to +5%) are not recorded - they're noise
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordTrade error: ${e.message}")
        }
    }
    
    private fun recordGoodPattern(pattern: BehaviorPattern, signature: String) {
        // Add to patterns list
        val list = goodPatterns.getOrPut(signature) { mutableListOf() }
        synchronized(list) {
            list.add(pattern)
            // Keep only recent patterns
            while (list.size > 50) list.removeAt(0)
        }
        
        // Update stats
        val stats = goodStats.getOrPut(signature) { PatternStats(signature) }
        stats.occurrences++
        stats.wins++
        stats.totalPnlPct += pattern.pnlPct
        stats.avgHoldMinutes = ((stats.avgHoldMinutes * (stats.occurrences - 1)) + pattern.holdTimeMinutes) / stats.occurrences
        stats.lastSeen = pattern.timestamp
    }
    
    private fun recordBadPattern(pattern: BehaviorPattern, signature: String) {
        // Add to patterns list
        val list = badPatterns.getOrPut(signature) { mutableListOf() }
        synchronized(list) {
            list.add(pattern)
            // Keep only recent patterns
            while (list.size > 50) list.removeAt(0)
        }
        
        // Update stats
        val stats = badStats.getOrPut(signature) { PatternStats(signature) }
        stats.occurrences++
        stats.losses++
        stats.totalPnlPct += pattern.pnlPct
        stats.avgHoldMinutes = ((stats.avgHoldMinutes * (stats.occurrences - 1)) + pattern.holdTimeMinutes) / stats.occurrences
        stats.lastSeen = pattern.timestamp
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // EVALUATING NEW TRADES
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate a potential trade against learned behaviors.
     * Returns a score adjustment and recommendation.
     */
    fun evaluate(
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        liquidityUsd: Double,
        volumeSignal: String,
    ): BehaviorEvaluation {
        return try {
            val signature = "${entryPhase}_${setupQuality}_${tradingMode}_${getLiquidityBucket(liquidityUsd)}_$volumeSignal"
            val broadSignature = "${setupQuality}_${tradingMode}_${getLiquidityBucket(liquidityUsd)}"
            
            // Check exact match first
            var goodMatch = goodStats[signature]
            var badMatch = badStats[signature]
            
            // Fall back to broad match
            if (goodMatch == null && badMatch == null) {
                goodMatch = goodStats[broadSignature]
                badMatch = badStats[broadSignature]
            }
            
            // Calculate score adjustment
            var scoreAdjust = 0.0
            var confidence = 0.0
            val reasons = mutableListOf<String>()
            
            // Good pattern match
            if (goodMatch != null && goodMatch.isReliable) {
                scoreAdjust += goodMatch.winRate * 0.3  // Up to +30 for 100% win rate
                confidence = goodMatch.confidence
                reasons.add("✅ Matches winning pattern (${goodMatch.winRate.toInt()}% win, ${goodMatch.occurrences} trades)")
            }
            
            // Bad pattern match
            if (badMatch != null && badMatch.isReliable) {
                val lossRate = 100.0 - badMatch.winRate
                scoreAdjust -= lossRate * 0.4  // Up to -40 for 100% loss rate
                confidence = maxOf(confidence, badMatch.confidence)
                reasons.add("❌ Matches losing pattern (${lossRate.toInt()}% loss, ${badMatch.occurrences} trades)")
            }
            
            // Determine recommendation
            val recommendation = when {
                scoreAdjust > 15 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.STRONG_BUY
                scoreAdjust > 5 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.BUY
                scoreAdjust < -20 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.STRONG_AVOID
                scoreAdjust < -10 && confidence >= MIN_CONFIDENCE_TO_USE -> BehaviorRecommendation.AVOID
                else -> BehaviorRecommendation.NEUTRAL
            }
            
            BehaviorEvaluation(
                scoreAdjustment = scoreAdjust.toInt(),
                confidence = confidence,
                recommendation = recommendation,
                reasons = reasons,
                goodPatternMatch = goodMatch,
                badPatternMatch = badMatch,
            )
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "evaluate error: ${e.message}")
            BehaviorEvaluation()
        }
    }
    
    data class BehaviorEvaluation(
        val scoreAdjustment: Int = 0,
        val confidence: Double = 0.0,
        val recommendation: BehaviorRecommendation = BehaviorRecommendation.NEUTRAL,
        val reasons: List<String> = emptyList(),
        val goodPatternMatch: PatternStats? = null,
        val badPatternMatch: PatternStats? = null,
    )
    
    enum class BehaviorRecommendation {
        STRONG_BUY,   // Matches highly successful patterns
        BUY,          // Matches winning patterns
        NEUTRAL,      // No strong signal either way
        AVOID,        // Matches losing patterns
        STRONG_AVOID, // Matches highly unsuccessful patterns
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STATISTICS & INSIGHTS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get top performing patterns (good behaviors to reinforce).
     */
    fun getTopGoodPatterns(limit: Int = 10): List<PatternStats> {
        return try {
            goodStats.values
                .filter { it.isReliable }
                .sortedByDescending { it.avgPnl }
                .take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get worst performing patterns (bad behaviors to avoid).
     */
    fun getTopBadPatterns(limit: Int = 10): List<PatternStats> {
        return try {
            badStats.values
                .filter { it.isReliable }
                .sortedBy { it.avgPnl }
                .take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get insights about what's working and what's not.
     */
    fun getInsights(): BehaviorInsights {
        return try {
            val topGood = getTopGoodPatterns(5)
            val topBad = getTopBadPatterns(5)
            
            // Find best trading mode
            val modeWins = goodStats.values.groupBy { it.signature.split("_").getOrNull(2) ?: "UNKNOWN" }
                .mapValues { it.value.sumOf { s -> s.wins } }
            val bestMode = modeWins.maxByOrNull { it.value }?.key ?: "UNKNOWN"
            
            // Find worst trading mode
            val modeLosses = badStats.values.groupBy { it.signature.split("_").getOrNull(2) ?: "UNKNOWN" }
                .mapValues { it.value.sumOf { s -> s.losses } }
            val worstMode = modeLosses.maxByOrNull { it.value }?.key ?: "UNKNOWN"
            
            // Find best liquidity bucket
            val liqWins = goodStats.values.groupBy { it.signature.split("_").getOrNull(3) ?: "UNKNOWN" }
                .mapValues { it.value.sumOf { s -> s.wins } }
            val bestLiquidity = liqWins.maxByOrNull { it.value }?.key ?: "UNKNOWN"
            
            BehaviorInsights(
                totalGoodPatterns = totalGoodRecorded.get(),
                totalBadPatterns = totalBadRecorded.get(),
                topGoodPatterns = topGood,
                topBadPatterns = topBad,
                bestTradingMode = bestMode,
                worstTradingMode = worstMode,
                bestLiquidityBucket = bestLiquidity,
            )
        } catch (e: Exception) {
            BehaviorInsights()
        }
    }
    
    data class BehaviorInsights(
        val totalGoodPatterns: Int = 0,
        val totalBadPatterns: Int = 0,
        val topGoodPatterns: List<PatternStats> = emptyList(),
        val topBadPatterns: List<PatternStats> = emptyList(),
        val bestTradingMode: String = "UNKNOWN",
        val worstTradingMode: String = "UNKNOWN",
        val bestLiquidityBucket: String = "UNKNOWN",
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════
    
    private fun getLiquidityBucket(liquidityUsd: Double): String {
        return when {
            liquidityUsd < 5000 -> "LIQ_TINY"
            liquidityUsd < 20000 -> "LIQ_LOW"
            liquidityUsd < 50000 -> "LIQ_MED"
            liquidityUsd < 100000 -> "LIQ_GOOD"
            else -> "LIQ_DEEP"
        }
    }
    
    private fun getMcapBucket(mcapUsd: Double): String {
        return when {
            mcapUsd < 50000 -> "MCAP_MICRO"
            mcapUsd < 100000 -> "MCAP_TINY"
            mcapUsd < 500000 -> "MCAP_SMALL"
            mcapUsd < 1000000 -> "MCAP_MED"
            else -> "MCAP_LARGE"
        }
    }
    
    private fun getHolderConcentration(topHolderPct: Double): String {
        return when {
            topHolderPct > 50 -> "CONC_HIGH"
            topHolderPct > 30 -> "CONC_MED"
            else -> "CONC_LOW"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Export state to JSON.
     */
    fun toJson(): JSONObject {
        return try {
            JSONObject().apply {
                put("totalGood", totalGoodRecorded.get())
                put("totalBad", totalBadRecorded.get())
                
                // Good stats
                val goodArray = JSONArray()
                goodStats.values.filter { it.isReliable }.forEach { stat ->
                    goodArray.put(JSONObject().apply {
                        put("sig", stat.signature)
                        put("n", stat.occurrences)
                        put("w", stat.wins)
                        put("pnl", stat.totalPnlPct)
                    })
                }
                put("goodStats", goodArray)
                
                // Bad stats
                val badArray = JSONArray()
                badStats.values.filter { it.isReliable }.forEach { stat ->
                    badArray.put(JSONObject().apply {
                        put("sig", stat.signature)
                        put("n", stat.occurrences)
                        put("l", stat.losses)
                        put("pnl", stat.totalPnlPct)
                    })
                }
                put("badStats", badArray)
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
    
    /**
     * Restore state from JSON.
     */
    fun fromJson(json: JSONObject) {
        try {
            totalGoodRecorded.set(json.optInt("totalGood", 0))
            totalBadRecorded.set(json.optInt("totalBad", 0))
            
            // Restore good stats
            val goodArray = json.optJSONArray("goodStats")
            if (goodArray != null) {
                for (i in 0 until goodArray.length()) {
                    val obj = goodArray.optJSONObject(i) ?: continue
                    val sig = obj.optString("sig", "")
                    if (sig.isNotEmpty()) {
                        goodStats[sig] = PatternStats(
                            signature = sig,
                            occurrences = obj.optInt("n", 0),
                            wins = obj.optInt("w", 0),
                            totalPnlPct = obj.optDouble("pnl", 0.0),
                        )
                    }
                }
            }
            
            // Restore bad stats
            val badArray = json.optJSONArray("badStats")
            if (badArray != null) {
                for (i in 0 until badArray.length()) {
                    val obj = badArray.optJSONObject(i) ?: continue
                    val sig = obj.optString("sig", "")
                    if (sig.isNotEmpty()) {
                        badStats[sig] = PatternStats(
                            signature = sig,
                            occurrences = obj.optInt("n", 0),
                            losses = obj.optInt("l", 0),
                            totalPnlPct = obj.optDouble("pnl", 0.0),
                        )
                    }
                }
            }
            
            ErrorLogger.info(TAG, "📂 Loaded: ${goodStats.size} good patterns, ${badStats.size} bad patterns")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "fromJson error: ${e.message}")
        }
    }
    
    /**
     * Clear all learned data.
     */
    fun clear() {
        goodPatterns.clear()
        goodStats.clear()
        badPatterns.clear()
        badStats.clear()
        totalGoodRecorded.set(0)
        totalBadRecorded.set(0)
        ErrorLogger.warn(TAG, "🧹 Behavior learning cleared")
    }
    
    /**
     * Get summary for display.
     */
    fun getSummary(): String {
        val goodCount = totalGoodRecorded.get()
        val badCount = totalBadRecorded.get()
        val ratio = if (badCount > 0) goodCount.toDouble() / badCount else goodCount.toDouble()
        
        return "Good: $goodCount | Bad: $badCount | Ratio: ${"%.1f".format(ratio)}"
    }
}
