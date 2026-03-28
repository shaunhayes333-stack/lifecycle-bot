package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.BehaviorLearning
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V3.2 Hold Time Optimizer AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Predicts optimal hold duration based on setup quality and market conditions:
 * 
 * HOLD TIME CATEGORIES:
 * - SCALP:    1-5 minutes   → Quick flip, tight stops
 * - SHORT:    5-30 minutes  → Standard short-term trade
 * - MEDIUM:   30-120 min    → Let the trade develop
 * - EXTENDED: 2-8 hours     → Runner potential, loose trailing
 * - SWING:    8+ hours      → Multi-session hold (rare)
 * 
 * FACTORS CONSIDERED:
 * - Setup quality (A+, A, B, C)
 * - Volatility regime
 * - Liquidity depth
 * - Historical pattern performance
 * - Time of day
 * - Market regime
 * 
 * ADAPTIVE LEARNING:
 * - Tracks actual hold times vs predicted
 * - Learns which setups perform best with which hold durations
 * - Adjusts trailing stop tightness based on expected hold
 * 
 * CROSS-TALK INTEGRATION:
 * - VolatilityRegimeAI: High vol = shorter holds, low vol = can extend
 * - BehaviorLearning: Historical hold time vs P&L correlation
 * - TimeOptimizationAI: Golden hours may warrant longer holds
 */
object HoldTimeOptimizerAI {
    
    private const val TAG = "HoldTimeAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class HoldCategory {
        SCALP,     // 1-5 min
        SHORT,     // 5-30 min
        MEDIUM,    // 30-120 min
        EXTENDED,  // 2-8 hours
        SWING      // 8+ hours
    }
    
    data class HoldTimeRecommendation(
        val category: HoldCategory,
        val optimalMinutes: Int,           // Recommended hold duration
        val minMinutes: Int,               // Don't exit before this
        val maxMinutes: Int,               // Consider exit after this
        val trailTightness: Double,        // 0.5 = tight, 2.0 = loose
        val runnerProbability: Double,     // 0-100, chance this runs big
        val confidence: Double,            // 0-100, confidence in prediction
        val entryBoost: Int,               // Score adjustment
        val reason: String
    )
    
    // Learned hold time patterns
    data class HoldPattern(
        var totalTrades: Int = 0,
        var avgHoldMinutes: Double = 0.0,
        var avgPnl: Double = 0.0,
        var bestHoldMinutes: Int = 0,      // Hold time with best avg PnL
        var winRateByHold: MutableMap<HoldCategory, Double> = mutableMapOf()
    )
    
    // Learning storage by setup quality
    private val patternsBySetup = ConcurrentHashMap<String, HoldPattern>()
    
    // Per-position tracking
    private data class PositionHoldData(
        val entryTime: Long,
        val setupQuality: String,
        val predictedCategory: HoldCategory,
        val predictedMinutes: Int
    )
    private val activePositions = ConcurrentHashMap<String, PositionHoldData>()
    
    // Stats
    private var totalPredictions = 0
    private var accuratePredictions = 0  // Within 50% of predicted time
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN PREDICTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get optimal hold time recommendation for a trade.
     */
    fun predict(
        mint: String,
        symbol: String,
        setupQuality: String,              // A+, A, B, C
        liquidityUsd: Double,
        volatilityRegime: String = "NORMAL",
        marketRegime: String = "NEUTRAL",
        isGoldenHour: Boolean = false,
        entryScore: Int = 50
    ): HoldTimeRecommendation {
        totalPredictions++
        
        // Get learned pattern for this setup quality
        val pattern = patternsBySetup[setupQuality]
        
        // Base recommendation from setup quality
        val baseCategory = getBaseCategory(setupQuality, entryScore)
        val baseTimes = getCategoryTimes(baseCategory)
        
        // Adjust for volatility
        val volMultiplier = when (volatilityRegime.uppercase()) {
            "CALM" -> 1.5       // Can hold longer in calm markets
            "NORMAL" -> 1.0
            "ELEVATED" -> 0.7   // Shorter holds in volatile markets
            "EXTREME" -> 0.4   // Very short holds
            else -> 1.0
        }
        
        // Adjust for liquidity
        val liqMultiplier = when {
            liquidityUsd >= 100_000 -> 1.3  // Deep liquidity = can hold longer
            liquidityUsd >= 50_000 -> 1.1
            liquidityUsd >= 20_000 -> 1.0
            liquidityUsd >= 10_000 -> 0.8
            else -> 0.5                      // Thin liquidity = exit faster
        }
        
        // Adjust for market regime
        val regimeMultiplier = when (marketRegime.uppercase()) {
            "STRONG_BULL", "BULL" -> 1.3    // Bull = let winners run
            "NEUTRAL", "CRAB" -> 1.0
            "BEAR", "STRONG_BEAR" -> 0.6    // Bear = take profits fast
            else -> 1.0
        }
        
        // Adjust for golden hour
        val goldenMultiplier = if (isGoldenHour) 1.2 else 1.0
        
        // Calculate final times
        val combinedMultiplier = volMultiplier * liqMultiplier * regimeMultiplier * goldenMultiplier
        val optimalMinutes = (baseTimes.second * combinedMultiplier).toInt().coerceIn(1, 480)
        val minMinutes = (baseTimes.first * combinedMultiplier).toInt().coerceIn(1, optimalMinutes)
        val maxMinutes = (baseTimes.third * combinedMultiplier).toInt().coerceIn(optimalMinutes, 960)
        
        // Determine final category
        val finalCategory = categorizeHoldTime(optimalMinutes)
        
        // Calculate trailing stop tightness
        val trailTightness = calculateTrailTightness(finalCategory, volatilityRegime, setupQuality)
        
        // Calculate runner probability
        val runnerProbability = calculateRunnerProbability(setupQuality, entryScore, marketRegime, pattern)
        
        // Calculate confidence
        val confidence = calculateConfidence(pattern, setupQuality)
        
        // Entry boost based on expected hold quality
        val entryBoost = calculateEntryBoost(finalCategory, runnerProbability, confidence)
        
        val reason = buildReason(finalCategory, optimalMinutes, runnerProbability, setupQuality)
        
        // Track active position
        activePositions[mint] = PositionHoldData(
            entryTime = System.currentTimeMillis(),
            setupQuality = setupQuality,
            predictedCategory = finalCategory,
            predictedMinutes = optimalMinutes
        )
        
        if (runnerProbability > 60) {
            ErrorLogger.info(TAG, "🏃 RUNNER POTENTIAL: $symbol | ${finalCategory.name} ${optimalMinutes}min | runner=${runnerProbability.toInt()}%")
        }
        
        return HoldTimeRecommendation(
            category = finalCategory,
            optimalMinutes = optimalMinutes,
            minMinutes = minMinutes,
            maxMinutes = maxMinutes,
            trailTightness = trailTightness,
            runnerProbability = runnerProbability,
            confidence = confidence,
            entryBoost = entryBoost,
            reason = reason
        )
    }
    
    /**
     * Check if current hold is within recommended time.
     */
    fun isHoldTimeOptimal(mint: String): Boolean {
        val data = activePositions[mint] ?: return true
        val heldMinutes = (System.currentTimeMillis() - data.entryTime) / 60_000
        return heldMinutes >= data.predictedMinutes * 0.5 && heldMinutes <= data.predictedMinutes * 1.5
    }
    
    /**
     * Check if position has exceeded max hold time.
     */
    fun isOverheld(mint: String): Boolean {
        val data = activePositions[mint] ?: return false
        val heldMinutes = (System.currentTimeMillis() - data.entryTime) / 60_000
        return heldMinutes > data.predictedMinutes * 2
    }
    
    /**
     * Get current hold time for a position.
     */
    fun getCurrentHoldMinutes(mint: String): Long {
        val data = activePositions[mint] ?: return 0
        return (System.currentTimeMillis() - data.entryTime) / 60_000
    }
    
    /**
     * Record trade outcome for learning.
     */
    fun recordOutcome(
        mint: String,
        actualHoldMinutes: Int,
        pnlPct: Double,
        setupQuality: String
    ) {
        val data = activePositions.remove(mint)
        
        // Update pattern learning
        val pattern = patternsBySetup.getOrPut(setupQuality) { HoldPattern() }
        
        pattern.totalTrades++
        
        // Running average of hold time
        pattern.avgHoldMinutes = (pattern.avgHoldMinutes * (pattern.totalTrades - 1) + actualHoldMinutes) / pattern.totalTrades
        
        // Running average of P&L
        pattern.avgPnl = (pattern.avgPnl * (pattern.totalTrades - 1) + pnlPct) / pattern.totalTrades
        
        // Track best hold time
        if (pnlPct > pattern.avgPnl) {
            pattern.bestHoldMinutes = actualHoldMinutes
        }
        
        // Track win rate by hold category
        val category = categorizeHoldTime(actualHoldMinutes)
        val isWin = pnlPct > 0
        val currentRate = pattern.winRateByHold[category] ?: 50.0
        val trades = pattern.totalTrades
        pattern.winRateByHold[category] = currentRate + (if (isWin) 100.0 - currentRate else -currentRate) / trades
        
        // Track prediction accuracy
        if (data != null) {
            val predictedMinutes = data.predictedMinutes
            if (actualHoldMinutes >= predictedMinutes * 0.5 && actualHoldMinutes <= predictedMinutes * 1.5) {
                accuratePredictions++
            }
        }
        
        ErrorLogger.debug(TAG, "Recorded: $setupQuality ${actualHoldMinutes}min pnl=${pnlPct.toInt()}% | patterns=${pattern.totalTrades}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun getBaseCategory(setupQuality: String, entryScore: Int): HoldCategory {
        // Better setups = longer potential holds
        return when (setupQuality.uppercase()) {
            "A+", "A" -> {
                if (entryScore > 70) HoldCategory.EXTENDED else HoldCategory.MEDIUM
            }
            "B" -> HoldCategory.SHORT
            else -> HoldCategory.SCALP
        }
    }
    
    private fun getCategoryTimes(category: HoldCategory): Triple<Int, Int, Int> {
        // Returns (min, optimal, max) in minutes
        return when (category) {
            HoldCategory.SCALP -> Triple(1, 3, 5)
            HoldCategory.SHORT -> Triple(5, 15, 30)
            HoldCategory.MEDIUM -> Triple(30, 60, 120)
            HoldCategory.EXTENDED -> Triple(120, 240, 480)
            HoldCategory.SWING -> Triple(480, 720, 1440)
        }
    }
    
    private fun categorizeHoldTime(minutes: Int): HoldCategory {
        return when {
            minutes <= 5 -> HoldCategory.SCALP
            minutes <= 30 -> HoldCategory.SHORT
            minutes <= 120 -> HoldCategory.MEDIUM
            minutes <= 480 -> HoldCategory.EXTENDED
            else -> HoldCategory.SWING
        }
    }
    
    private fun calculateTrailTightness(
        category: HoldCategory,
        volatilityRegime: String,
        setupQuality: String
    ): Double {
        // Base tightness by category
        val baseTightness = when (category) {
            HoldCategory.SCALP -> 0.6     // Tight trailing
            HoldCategory.SHORT -> 0.8
            HoldCategory.MEDIUM -> 1.0
            HoldCategory.EXTENDED -> 1.3  // Loose trailing
            HoldCategory.SWING -> 1.6     // Very loose
        }
        
        // Adjust for volatility
        val volAdjust = when (volatilityRegime.uppercase()) {
            "CALM" -> 0.8      // Tighter in calm markets
            "NORMAL" -> 1.0
            "ELEVATED" -> 1.2  // Looser in volatile markets
            "EXTREME" -> 1.5
            else -> 1.0
        }
        
        // Adjust for setup quality (better setups = can use looser trails)
        val qualityAdjust = when (setupQuality.uppercase()) {
            "A+", "A" -> 1.1
            "B" -> 1.0
            else -> 0.9
        }
        
        return (baseTightness * volAdjust * qualityAdjust).coerceIn(0.4, 2.0)
    }
    
    private fun calculateRunnerProbability(
        setupQuality: String,
        entryScore: Int,
        marketRegime: String,
        pattern: HoldPattern?
    ): Double {
        var prob = 0.0
        
        // Setup quality contribution
        prob += when (setupQuality.uppercase()) {
            "A+" -> 40.0
            "A" -> 30.0
            "B" -> 15.0
            else -> 5.0
        }
        
        // Entry score contribution
        prob += (entryScore - 50).coerceIn(0, 30).toDouble()
        
        // Market regime contribution
        prob += when (marketRegime.uppercase()) {
            "STRONG_BULL" -> 20.0
            "BULL" -> 10.0
            "NEUTRAL", "CRAB" -> 0.0
            else -> -10.0
        }
        
        // Historical pattern contribution
        if (pattern != null && pattern.totalTrades >= 10) {
            val extendedWinRate = pattern.winRateByHold[HoldCategory.EXTENDED] ?: 50.0
            prob += (extendedWinRate - 50) * 0.3
        }
        
        return prob.coerceIn(0.0, 100.0)
    }
    
    private fun calculateConfidence(pattern: HoldPattern?, setupQuality: String): Double {
        if (pattern == null) return 40.0  // Base confidence without data
        
        // More trades = more confidence
        val tradeConfidence = minOf(pattern.totalTrades / 20.0, 1.0) * 40
        
        // Setup quality confidence
        val qualityConfidence = when (setupQuality.uppercase()) {
            "A+", "A" -> 30.0
            "B" -> 20.0
            else -> 10.0
        }
        
        return (tradeConfidence + qualityConfidence + 20).coerceIn(20.0, 95.0)
    }
    
    private fun calculateEntryBoost(
        category: HoldCategory,
        runnerProbability: Double,
        confidence: Double
    ): Int {
        var boost = 0
        
        // Runner potential boost
        if (runnerProbability > 70) boost += 5
        else if (runnerProbability > 50) boost += 3
        
        // Category boost (medium/extended generally better setups)
        boost += when (category) {
            HoldCategory.SCALP -> -2   // Scalps are risky
            HoldCategory.SHORT -> 0
            HoldCategory.MEDIUM -> 2
            HoldCategory.EXTENDED -> 4
            HoldCategory.SWING -> 3
        }
        
        // Confidence modifier
        boost = (boost * confidence / 100.0).toInt()
        
        return boost.coerceIn(-5, 8)
    }
    
    private fun buildReason(
        category: HoldCategory,
        optimalMinutes: Int,
        runnerProb: Double,
        setupQuality: String
    ): String {
        val parts = mutableListOf<String>()
        
        parts += "${category.name} ${optimalMinutes}min"
        parts += "setup=$setupQuality"
        
        if (runnerProb > 50) {
            parts += "runner=${runnerProb.toInt()}%"
        }
        
        return parts.joinToString(" | ")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING MODULE INTERFACE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val setupQuality = candidate.extraString("setupQuality").ifBlank { "C" }
        val volatilityRegime = candidate.extraString("volatilityRegime").ifBlank { "NORMAL" }
        
        val recommendation = predict(
            mint = candidate.mint,
            symbol = candidate.symbol,
            setupQuality = setupQuality,
            liquidityUsd = candidate.liquidityUsd,
            volatilityRegime = volatilityRegime,
            marketRegime = ctx.marketRegime,
            isGoldenHour = ctx.extraBoolean("isGoldenHour"),
            entryScore = candidate.extraInt("totalEntryScore")
        )
        
        return ScoreComponent(
            name = "holdtime",
            value = recommendation.entryBoost,
            reason = recommendation.reason
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("totalPredictions", totalPredictions)
            put("accuratePredictions", accuratePredictions)
            
            // Save patterns
            val patternsJson = JSONObject()
            patternsBySetup.forEach { (quality, pattern) ->
                patternsJson.put(quality, JSONObject().apply {
                    put("totalTrades", pattern.totalTrades)
                    put("avgHoldMinutes", pattern.avgHoldMinutes)
                    put("avgPnl", pattern.avgPnl)
                    put("bestHoldMinutes", pattern.bestHoldMinutes)
                })
            }
            put("patterns", patternsJson)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            totalPredictions = json.optInt("totalPredictions", 0)
            accuratePredictions = json.optInt("accuratePredictions", 0)
            
            val patternsJson = json.optJSONObject("patterns")
            patternsJson?.keys()?.forEach { quality ->
                val patternJson = patternsJson.getJSONObject(quality)
                patternsBySetup[quality] = HoldPattern(
                    totalTrades = patternJson.optInt("totalTrades", 0),
                    avgHoldMinutes = patternJson.optDouble("avgHoldMinutes", 0.0),
                    avgPnl = patternJson.optDouble("avgPnl", 0.0),
                    bestHoldMinutes = patternJson.optInt("bestHoldMinutes", 0)
                )
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Load error: ${e.message}")
        }
    }
    
    fun getStats(): String {
        val accuracy = if (totalPredictions > 0) (accuratePredictions * 100.0 / totalPredictions).toInt() else 0
        return "HoldTimeAI: $totalPredictions predictions | accuracy=$accuracy% | patterns=${patternsBySetup.size}"
    }
    
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThreshold = 24 * 60 * 60 * 1000L  // 24 hours
        activePositions.entries.removeIf { (_, v) -> now - v.entryTime > staleThreshold }
    }
}
