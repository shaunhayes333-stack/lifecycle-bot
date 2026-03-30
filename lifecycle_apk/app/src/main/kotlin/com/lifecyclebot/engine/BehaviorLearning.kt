package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.FluidLearningAI
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
                scoreAdjust -= lossRate * 0.06  // Up to -6 for 100% loss rate (reduced from -40)
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
        lastHealthCheck = 0L
        consecutiveBadPeriods = 0
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
    
    // ═══════════════════════════════════════════════════════════════════
    // SELF-HEALING SYSTEM
    // ═══════════════════════════════════════════════════════════════════
    
    private const val HEALTH_CHECK_INTERVAL_MS = 30 * 60 * 1000L  // 30 minutes
    private const val MIN_TRADES_FOR_HEALTH = 10
    private const val CRITICAL_BAD_RATIO = 3.0  // 3x more bad than good = poisoned
    private const val WARNING_BAD_RATIO = 2.0   // 2x more bad = concerning
    
    @Volatile private var lastHealthCheck = 0L
    @Volatile private var consecutiveBadPeriods = 0
    
    /**
     * Self-healing health check. Call periodically (e.g., every loop cycle).
     * Returns true if data was poisoned and cleared.
     */
    fun selfHealingCheck(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHealthCheck < HEALTH_CHECK_INTERVAL_MS) return false
        lastHealthCheck = now
        
        return try {
            val goodCount = totalGoodRecorded.get()
            val badCount = totalBadRecorded.get()
            val total = goodCount + badCount
            
            // Not enough data to judge
            if (total < MIN_TRADES_FOR_HEALTH) return false
            
            // Calculate bad-to-good ratio (higher = more poisoned)
            val badRatio = if (goodCount > 0) badCount.toDouble() / goodCount else badCount.toDouble()
            
            // Calculate effective win rate from recent patterns
            val recentGoodWinRate = goodStats.values
                .filter { it.isReliable }
                .map { it.winRate }
                .average()
                .takeIf { !it.isNaN() } ?: 50.0
            
            val recentBadLossRate = badStats.values
                .filter { it.isReliable }
                .map { 100.0 - it.winRate }
                .average()
                .takeIf { !it.isNaN() } ?: 50.0
            
            // CRITICAL: More bad patterns than good AND bad patterns are accurate
            val isCritical = badRatio >= CRITICAL_BAD_RATIO && recentBadLossRate >= 60.0
            val isWarning = badRatio >= WARNING_BAD_RATIO && recentBadLossRate >= 50.0
            
            if (isCritical) {
                consecutiveBadPeriods++
                
                if (consecutiveBadPeriods >= 2) {
                    // Two consecutive bad periods = clear the data
                    ErrorLogger.warn(TAG, "🚨 SELF-HEALING: Critical bad ratio (${"%.1f".format(badRatio)}x), " +
                        "clearing behavior data after $consecutiveBadPeriods bad periods")
                    clear()
                    return true
                } else {
                    ErrorLogger.warn(TAG, "⚠️ HEALTH WARNING: Bad ratio ${"%.1f".format(badRatio)}x " +
                        "(bad=$badCount good=$goodCount) - period $consecutiveBadPeriods/2")
                }
            } else if (isWarning) {
                ErrorLogger.info(TAG, "📊 Health check: Elevated bad ratio ${"%.1f".format(badRatio)}x - monitoring")
                // Don't increment consecutiveBadPeriods for warnings
            } else {
                // Healthy - reset counter
                if (consecutiveBadPeriods > 0) {
                    ErrorLogger.info(TAG, "✅ Health recovered: Bad ratio ${"%.1f".format(badRatio)}x (was critical)")
                }
                consecutiveBadPeriods = 0
            }
            
            false
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "selfHealingCheck error: ${e.message}")
            false
        }
    }
    
    /**
     * Get health status for diagnostics display.
     */
    fun getHealthStatus(): HealthStatus {
        val goodCount = totalGoodRecorded.get()
        val badCount = totalBadRecorded.get()
        val total = goodCount + badCount
        
        val badRatio = if (goodCount > 0) badCount.toDouble() / goodCount else badCount.toDouble()
        val winRate = if (total > 0) goodCount.toDouble() / total * 100 else 0.0
        
        val status = when {
            total < MIN_TRADES_FOR_HEALTH -> "BOOTSTRAP"
            badRatio >= CRITICAL_BAD_RATIO -> "CRITICAL"
            badRatio >= WARNING_BAD_RATIO -> "WARNING"
            winRate >= 60.0 -> "EXCELLENT"
            winRate >= 45.0 -> "HEALTHY"
            else -> "LEARNING"
        }
        
        return HealthStatus(
            status = status,
            goodCount = goodCount,
            badCount = badCount,
            badRatio = badRatio,
            effectiveWinRate = winRate,
            consecutiveBadPeriods = consecutiveBadPeriods,
            goodPatternsCount = goodStats.size,
            badPatternsCount = badStats.size,
        )
    }
    
    data class HealthStatus(
        val status: String,
        val goodCount: Int,
        val badCount: Int,
        val badRatio: Double,
        val effectiveWinRate: Double,
        val consecutiveBadPeriods: Int,
        val goodPatternsCount: Int,
        val badPatternsCount: Int,
    ) {
        fun summary(): String = "$status | Win:$goodCount Loss:$badCount | Patterns: ✅$goodPatternsCount ❌$badPatternsCount"
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // FDG INTEGRATION - Hard veto for dangerous patterns
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Check if this setup should be HARD BLOCKED based on learned bad behavior.
     * Used by FinalDecisionGate for veto decisions.
     * 
     * Returns block reason if should block, null if OK.
     */
    fun shouldHardBlock(
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        liquidityUsd: Double,
        volumeSignal: String,
    ): String? {
        return try {
            val signature = "${entryPhase}_${setupQuality}_${tradingMode}_${getLiquidityBucket(liquidityUsd)}_$volumeSignal"
            val broadSignature = "${setupQuality}_${tradingMode}_${getLiquidityBucket(liquidityUsd)}"
            
            // Check for known bad patterns with HIGH confidence
            val badMatch = badStats[signature] ?: badStats[broadSignature]
            
            if (badMatch != null && badMatch.isReliable && badMatch.confidence >= 0.8) {
                val lossRate = 100.0 - badMatch.winRate
                
                // HARD BLOCK: 80%+ loss rate with 80%+ confidence
                if (lossRate >= 80.0) {
                    return "BEHAVIOR_HARD_BLOCK: Pattern has ${lossRate.toInt()}% loss rate (${badMatch.occurrences} trades)"
                }
                
                // STRONG WARNING: 70%+ loss rate
                if (lossRate >= 70.0) {
                    ErrorLogger.warn(TAG, "⚠️ High-loss pattern detected: $signature (${lossRate.toInt()}% loss)")
                }
            }
            
            null
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "shouldHardBlock error: ${e.message}")
            null
        }
    }
    
    /**
     * Get confidence-weighted score adjustment for FDG.
     * Returns adjustment in range -6 to +30.
     */
    fun getScoreAdjustment(
        entryPhase: String,
        setupQuality: String,
        tradingMode: String,
        liquidityUsd: Double,
        volumeSignal: String,
    ): Int {
        return try {
            val eval = evaluate(entryPhase, setupQuality, tradingMode, liquidityUsd, volumeSignal)
            
            // Weight by confidence - fluid penalty: -6 bootstrap → -15 mature
            val maxPenalty = -6 - (9 * FluidLearningAI.getLearningProgress()).toInt()  // -6 to -15
            (eval.scoreAdjustment * eval.confidence).toInt().coerceIn(maxPenalty, 30)
        } catch (e: Exception) {
            0
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // INTELLIGENT PATTERN PRUNING
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Prune old or unreliable patterns. Call periodically.
     */
    fun pruneStalePatterns() {
        try {
            val now = System.currentTimeMillis()
            val staleThreshold = 24 * 60 * 60 * 1000L  // 24 hours
            
            // Prune good stats
            val staleGood = goodStats.filter { now - it.value.lastSeen > staleThreshold && !it.value.isReliable }
            staleGood.keys.forEach { goodStats.remove(it) }
            
            // Prune bad stats
            val staleBad = badStats.filter { now - it.value.lastSeen > staleThreshold && !it.value.isReliable }
            staleBad.keys.forEach { badStats.remove(it) }
            
            if (staleGood.isNotEmpty() || staleBad.isNotEmpty()) {
                ErrorLogger.info(TAG, "🧹 Pruned ${staleGood.size} stale good + ${staleBad.size} stale bad patterns")
            }
            
            // Enforce max patterns limit
            while (goodStats.size > MAX_PATTERNS_PER_LAYER) {
                val oldest = goodStats.minByOrNull { it.value.lastSeen }?.key
                if (oldest != null) goodStats.remove(oldest)
            }
            while (badStats.size > MAX_PATTERNS_PER_LAYER) {
                val oldest = badStats.minByOrNull { it.value.lastSeen }?.key
                if (oldest != null) badStats.remove(oldest)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "pruneStalePatterns error: ${e.message}")
        }
    }
    
    /**
     * Decay old pattern weights to favor recent data.
     * Call once per hour.
     */
    fun decayPatternWeights() {
        try {
            val decayFactor = 0.95  // Lose 5% weight per decay cycle
            
            goodStats.values.forEach { stat ->
                if (stat.occurrences > 2) {
                    stat.occurrences = (stat.occurrences * decayFactor).toInt().coerceAtLeast(2)
                    stat.wins = (stat.wins * decayFactor).toInt()
                }
            }
            
            badStats.values.forEach { stat ->
                if (stat.occurrences > 2) {
                    stat.occurrences = (stat.occurrences * decayFactor).toInt().coerceAtLeast(2)
                    stat.losses = (stat.losses * decayFactor).toInt()
                }
            }
            
            ErrorLogger.debug(TAG, "📉 Decayed pattern weights (factor=${decayFactor})")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "decayPatternWeights error: ${e.message}")
        }
    }
    
    /**
     * Get trade count for learning phase determination.
     */
    fun getTradeCount(): Int = totalGoodRecorded.get() + totalBadRecorded.get()
    
    /**
     * Get win rate for health monitoring.
     */
    fun getWinRate(): Double {
        val total = getTradeCount()
        return if (total > 0) totalGoodRecorded.get().toDouble() / total * 100 else 0.0
    }
}
