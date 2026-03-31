package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.BehaviorLearning
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.2 Hold Time Optimizer AI - SECONDS PRECISION
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Predicts optimal hold duration in SECONDS for fast crypto trading:
 * 
 * HOLD TIME CATEGORIES (in seconds):
 * - SCALP:    10-60 seconds   → Quick flip, tight stops
 * - SHORT:    60-300 seconds  → Standard short-term trade (1-5 min)
 * - MEDIUM:   300-1800 sec    → Let the trade develop (5-30 min)
 * - EXTENDED: 1800-14400 sec  → Runner potential (30min-4hr)
 * - SWING:    14400+ seconds  → Multi-hour hold (4hr+, rare)
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
 * - Tracks actual hold times vs predicted (in seconds)
 * - Learns which setups perform best with which hold durations
 * - Adjusts trailing stop tightness based on expected hold
 * 
 * V5.2: Changed from minutes to SECONDS for faster, more precise timing!
 */
object HoldTimeOptimizerAI {
    
    private const val TAG = "HoldTimeAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class HoldCategory {
        SCALP,     // 10-60 sec
        SHORT,     // 60-300 sec (1-5 min)
        MEDIUM,    // 300-1800 sec (5-30 min)
        EXTENDED,  // 1800-14400 sec (30min-4hr)
        SWING      // 14400+ sec (4hr+)
    }
    
    data class HoldTimeRecommendation(
        val category: HoldCategory,
        val optimalSeconds: Int,            // Recommended hold duration in SECONDS
        val minSeconds: Int,                // Don't exit before this
        val maxSeconds: Int,                // Consider exit after this
        val trailTightness: Double,         // 0.5 = tight, 2.0 = loose
        val runnerProbability: Double,      // 0-100, chance this runs big
        val confidence: Double,             // 0-100, confidence in prediction
        val entryBoost: Int,                // Score adjustment
        val reason: String
    ) {
        // Legacy compatibility - convert to minutes for old code
        val optimalMinutes: Int get() = (optimalSeconds / 60).coerceAtLeast(1)
        val minMinutes: Int get() = (minSeconds / 60).coerceAtLeast(1)
        val maxMinutes: Int get() = (maxSeconds / 60).coerceAtLeast(1)
    }
    
    // Learned hold time patterns (in seconds now)
    data class HoldPattern(
        var totalTrades: Int = 0,
        var avgHoldSeconds: Double = 0.0,
        var avgPnl: Double = 0.0,
        var bestHoldSeconds: Int = 0,       // Hold time with best avg PnL
        var winRateByHold: MutableMap<HoldCategory, Double> = mutableMapOf()
    )
    
    // Learning storage by setup quality
    private val patternsBySetup = ConcurrentHashMap<String, HoldPattern>()
    
    // Per-position tracking
    private data class PositionHoldData(
        val entryTime: Long,
        val setupQuality: String,
        val predictedCategory: HoldCategory,
        val predictedSeconds: Int
    )
    private val activePositions = ConcurrentHashMap<String, PositionHoldData>()
    
    // Stats
    private var totalPredictions = 0
    private var accuratePredictions = 0  // Within 50% of predicted time
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN PREDICTION (All times in SECONDS)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get optimal hold time recommendation for a trade.
     * All times are in SECONDS for fast crypto trading precision.
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
        
        // Base recommendation from setup quality (returns seconds)
        val baseCategory = getBaseCategory(setupQuality, entryScore)
        val baseTimes = getCategoryTimesInSeconds(baseCategory)
        
        // Adjust for volatility
        val volMultiplier = when (volatilityRegime.uppercase()) {
            "CALM", "LOW" -> 1.5       // Can hold longer in calm markets
            "NORMAL" -> 1.0
            "ELEVATED", "HIGH" -> 0.7  // Shorter holds in volatile markets
            "EXTREME" -> 0.4           // Very short holds
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
        
        // Calculate final times IN SECONDS
        val combinedMultiplier = volMultiplier * liqMultiplier * regimeMultiplier * goldenMultiplier
        val optimalSeconds = (baseTimes.second * combinedMultiplier).toInt().coerceIn(10, 28800)  // 10sec to 8hr
        val minSeconds = (baseTimes.first * combinedMultiplier).toInt().coerceIn(5, optimalSeconds)
        val maxSeconds = (baseTimes.third * combinedMultiplier).toInt().coerceIn(optimalSeconds, 57600)  // max 16hr
        
        // Determine final category
        val finalCategory = categorizeHoldTimeSeconds(optimalSeconds)
        
        // Calculate trailing stop tightness
        val trailTightness = calculateTrailTightness(finalCategory, volatilityRegime, setupQuality)
        
        // Calculate runner probability
        val runnerProbability = calculateRunnerProbability(setupQuality, entryScore, marketRegime, pattern)
        
        // Calculate confidence
        val confidence = calculateConfidence(pattern, setupQuality)
        
        // Entry boost based on expected hold quality
        val entryBoost = calculateEntryBoost(finalCategory, runnerProbability, confidence)
        
        val reason = buildReasonSeconds(finalCategory, optimalSeconds, runnerProbability, setupQuality)
        
        // Track active position
        activePositions[mint] = PositionHoldData(
            entryTime = System.currentTimeMillis(),
            setupQuality = setupQuality,
            predictedCategory = finalCategory,
            predictedSeconds = optimalSeconds
        )
        
        if (runnerProbability > 60) {
            ErrorLogger.info(TAG, "🏃 RUNNER POTENTIAL: $symbol | ${finalCategory.name} ${optimalSeconds}sec | runner=${runnerProbability.toInt()}%")
        }
        
        return HoldTimeRecommendation(
            category = finalCategory,
            optimalSeconds = optimalSeconds,
            minSeconds = minSeconds,
            maxSeconds = maxSeconds,
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
        val heldSeconds = (System.currentTimeMillis() - data.entryTime) / 1000
        return heldSeconds >= data.predictedSeconds * 0.5 && heldSeconds <= data.predictedSeconds * 1.5
    }
    
    /**
     * Check if position has exceeded max hold time.
     */
    fun isOverheld(mint: String): Boolean {
        val data = activePositions[mint] ?: return false
        val heldSeconds = (System.currentTimeMillis() - data.entryTime) / 1000
        return heldSeconds > data.predictedSeconds * 2
    }
    
    /**
     * Get current hold time for a position in SECONDS.
     */
    fun getCurrentHoldSeconds(mint: String): Long {
        val data = activePositions[mint] ?: return 0
        return (System.currentTimeMillis() - data.entryTime) / 1000
    }
    
    /**
     * Legacy: Get current hold time in minutes (for backwards compatibility)
     */
    fun getCurrentHoldMinutes(mint: String): Long {
        return getCurrentHoldSeconds(mint) / 60
    }
    
    /**
     * Record trade outcome for learning. Accepts seconds OR minutes.
     */
    fun recordOutcome(
        mint: String,
        actualHoldMinutes: Int,  // Legacy parameter name, but we convert
        pnlPct: Double,
        setupQuality: String
    ) {
        // Convert minutes to seconds for internal storage
        recordOutcomeSeconds(mint, actualHoldMinutes * 60, pnlPct, setupQuality)
    }
    
    /**
     * Record trade outcome with seconds precision.
     */
    fun recordOutcomeSeconds(
        mint: String,
        actualHoldSeconds: Int,
        pnlPct: Double,
        setupQuality: String
    ) {
        val data = activePositions.remove(mint)
        
        // Update pattern learning
        val pattern = patternsBySetup.getOrPut(setupQuality) { HoldPattern() }
        
        pattern.totalTrades++
        
        // Running average of hold time in seconds
        pattern.avgHoldSeconds = (pattern.avgHoldSeconds * (pattern.totalTrades - 1) + actualHoldSeconds) / pattern.totalTrades
        
        // Running average of P&L
        pattern.avgPnl = (pattern.avgPnl * (pattern.totalTrades - 1) + pnlPct) / pattern.totalTrades
        
        // Track best hold time
        if (pnlPct > pattern.avgPnl) {
            pattern.bestHoldSeconds = actualHoldSeconds
        }
        
        // Track win rate by hold category
        val category = categorizeHoldTimeSeconds(actualHoldSeconds)
        val isWin = pnlPct > 0
        val currentRate = pattern.winRateByHold[category] ?: 50.0
        val trades = pattern.totalTrades
        pattern.winRateByHold[category] = currentRate + (if (isWin) 100.0 - currentRate else -currentRate) / trades
        
        // Track prediction accuracy
        // Check prediction accuracy
        if (data != null) {
            val predictedSeconds = data.predictedSeconds
            if (actualHoldSeconds >= predictedSeconds * 0.5 && actualHoldSeconds <= predictedSeconds * 1.5) {
                accuratePredictions++
            }
        }
        
        ErrorLogger.debug(TAG, "Recorded: $setupQuality ${actualHoldSeconds}sec pnl=${pnlPct.toInt()}% | patterns=${pattern.totalTrades}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATIONS (All times in SECONDS)
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
    
    /**
     * Get category times in SECONDS.
     * Returns (min, optimal, max)
     */
    private fun getCategoryTimesInSeconds(category: HoldCategory): Triple<Int, Int, Int> {
        return when (category) {
            HoldCategory.SCALP -> Triple(10, 30, 60)           // 10-60 sec
            HoldCategory.SHORT -> Triple(60, 180, 300)         // 1-5 min
            HoldCategory.MEDIUM -> Triple(300, 900, 1800)      // 5-30 min
            HoldCategory.EXTENDED -> Triple(1800, 3600, 14400) // 30min-4hr
            HoldCategory.SWING -> Triple(14400, 28800, 57600)  // 4-16 hr
        }
    }
    
    /**
     * Categorize hold time based on seconds.
     */
    private fun categorizeHoldTimeSeconds(seconds: Int): HoldCategory {
        return when {
            seconds <= 60 -> HoldCategory.SCALP       // <=1 min
            seconds <= 300 -> HoldCategory.SHORT      // 1-5 min
            seconds <= 1800 -> HoldCategory.MEDIUM    // 5-30 min
            seconds <= 14400 -> HoldCategory.EXTENDED // 30min-4hr
            else -> HoldCategory.SWING                // 4hr+
        }
    }
    
    // Legacy function for backwards compatibility
    private fun getCategoryTimes(category: HoldCategory): Triple<Int, Int, Int> {
        val secTimes = getCategoryTimesInSeconds(category)
        return Triple(secTimes.first / 60, secTimes.second / 60, secTimes.third / 60)
    }
    
    private fun categorizeHoldTime(minutes: Int): HoldCategory {
        return categorizeHoldTimeSeconds(minutes * 60)
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
    
    /**
     * Build reason string using seconds for display.
     */
    private fun buildReasonSeconds(
        category: HoldCategory,
        optimalSeconds: Int,
        runnerProb: Double,
        setupQuality: String
    ): String {
        val parts = mutableListOf<String>()
        
        // Display in appropriate units
        val timeStr = when {
            optimalSeconds < 60 -> "${optimalSeconds}sec"
            optimalSeconds < 3600 -> "${optimalSeconds / 60}min"
            else -> "${optimalSeconds / 3600}hr"
        }
        
        parts += "${category.name} $timeStr"
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
                    put("avgHoldSeconds", pattern.avgHoldSeconds)
                    put("avgPnl", pattern.avgPnl)
                    put("bestHoldSeconds", pattern.bestHoldSeconds)
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
                    avgHoldSeconds = patternJson.optDouble("avgHoldSeconds", 
                        patternJson.optDouble("avgHoldMinutes", 0.0) * 60),  // Legacy: convert minutes to seconds
                    avgPnl = patternJson.optDouble("avgPnl", 0.0),
                    bestHoldSeconds = patternJson.optInt("bestHoldSeconds",
                        patternJson.optInt("bestHoldMinutes", 0) * 60)  // Legacy: convert minutes to seconds
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
