package com.lifecyclebot.engine

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * AI-driven Entry Timing Intelligence
 * 
 * @deprecated V3 ARCHITECTURE MIGRATION
 * This legacy entry scoring is being replaced by:
 *   - EntryAI (v3/scoring/ScoringModules.kt) - Entry timing scoring
 *   - MomentumAI (v3/scoring/ScoringModules.kt) - Pump detection
 *   - VolumeProfileAI (v3/scoring/ScoringModules.kt) - Volume analysis
 * 
 * MIGRATION STATUS: DEPRECATED - V3 uses modular scoring
 * 
 * Learns optimal entry conditions from trade outcomes and provides
 * intelligent entry timing recommendations.
 */
@Deprecated(
    message = "V3 Architecture Migration: Use v3/scoring/ScoringModules EntryAI instead",
    level = DeprecationLevel.WARNING
)
object EntryIntelligence {
    
    private const val TAG = "EntryAI"
    
    // ═══════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════
    
    data class EntryConditions(
        val buyPressure: Double,        // 0-100
        val volumeScore: Double,        // 0-100
        val priceVsEma: Double,         // % above/below EMA
        val rsi: Double,                // 0-100
        val momentum: Double,           // -100 to +100
        val hourOfDay: Int,             // 0-23 UTC
        val volatility: Double,         // ATR-based
        val liquidityUsd: Double,
        val topHolderPct: Double,
        val isNearSupport: Boolean,
        val isNearResistance: Boolean,
        val candlePattern: String,      // "bullish_engulf", "doji", etc.
    )
    
    data class EntryScore(
        val score: Int,                 // 0-100
        val recommendation: EntryRecommendation,
        val confidence: Double,         // 0-1
        val reasons: List<String>,
        val suggestedWaitMinutes: Int,  // 0 = enter now
        val riskLevel: RiskLevel,
    )
    
    enum class EntryRecommendation {
        STRONG_BUY,     // Excellent conditions, enter aggressively
        BUY,            // Good conditions, normal entry
        WAIT,           // Conditions developing, wait for confirmation
        AVOID,          // Poor conditions, skip this entry
    }
    
    enum class RiskLevel { LOW, MEDIUM, HIGH, EXTREME }
    
    // Learned weights for entry scoring
    data class LearnedWeights(
        var buyPressureWeight: Double = 1.0,
        var volumeWeight: Double = 1.0,
        var momentumWeight: Double = 1.0,
        var rsiWeight: Double = 1.0,
        var volatilityWeight: Double = 1.0,
        var timeOfDayWeight: Double = 1.0,
        var supportResistanceWeight: Double = 1.0,
        var candlePatternWeight: Double = 1.0,
        
        // Time-of-day learned preferences (24 hours)
        var hourlyWinRates: MutableMap<Int, Double> = mutableMapOf(),
        var hourlyTradeCount: MutableMap<Int, Int> = mutableMapOf(),
        
        // Pattern success rates
        var patternWinRates: MutableMap<String, Double> = mutableMapOf(),
        var patternTradeCount: MutableMap<String, Int> = mutableMapOf(),
        
        // Optimal ranges learned from wins
        var optimalBuyPressureMin: Double = 50.0,
        var optimalBuyPressureMax: Double = 75.0,
        var optimalRsiMin: Double = 30.0,
        var optimalRsiMax: Double = 70.0,
        var optimalMomentumMin: Double = 5.0,
        
        var totalTrades: Int = 0,
        var winningTrades: Int = 0,
    )
    
    private var weights = LearnedWeights()
    
    // Track entries for learning
    private val pendingEntries = ConcurrentHashMap<String, EntryConditions>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // ENTRY SCORING
    // ═══════════════════════════════════════════════════════════════════════
    
    fun scoreEntry(conditions: EntryConditions): EntryScore {
        val reasons = mutableListOf<String>()
        var totalScore = 0.0
        var maxScore = 0.0
        
        // 1. Buy Pressure Score (0-25 pts)
        val buyPressureScore = when {
            conditions.buyPressure >= weights.optimalBuyPressureMin && 
            conditions.buyPressure <= weights.optimalBuyPressureMax -> 25.0
            conditions.buyPressure >= 60 -> 20.0
            conditions.buyPressure >= 50 -> 15.0
            conditions.buyPressure >= 45 -> 10.0
            conditions.buyPressure >= 40 -> 5.0
            else -> 0.0
        } * weights.buyPressureWeight
        totalScore += buyPressureScore
        maxScore += 25.0
        if (buyPressureScore >= 20) reasons.add("Strong buy pressure (${conditions.buyPressure.toInt()}%)")
        else if (buyPressureScore < 10) reasons.add("Weak buy pressure")
        
        // 2. Volume Score (0-15 pts)
        val volumeScore = when {
            conditions.volumeScore >= 30 -> 15.0
            conditions.volumeScore >= 20 -> 12.0
            conditions.volumeScore >= 15 -> 8.0
            conditions.volumeScore >= 10 -> 5.0
            else -> 2.0
        } * weights.volumeWeight
        totalScore += volumeScore
        maxScore += 15.0
        if (volumeScore >= 12) reasons.add("High volume activity")
        
        // 3. RSI Score (0-15 pts) - prefer oversold or neutral, not overbought
        val rsiScore = when {
            conditions.rsi in weights.optimalRsiMin..weights.optimalRsiMax -> 15.0
            conditions.rsi < 30 -> 12.0  // Oversold = potential bounce
            conditions.rsi in 30.0..40.0 -> 10.0
            conditions.rsi in 60.0..70.0 -> 8.0
            conditions.rsi > 80 -> 0.0   // Overbought = risky entry
            else -> 5.0
        } * weights.rsiWeight
        totalScore += rsiScore
        maxScore += 15.0
        if (conditions.rsi > 75) reasons.add("RSI overbought (${conditions.rsi.toInt()})")
        else if (conditions.rsi < 35) reasons.add("RSI oversold - potential bounce")
        
        // 4. Momentum Score (0-15 pts)
        val momentumScore = when {
            conditions.momentum >= weights.optimalMomentumMin && conditions.momentum <= 30 -> 15.0
            conditions.momentum in 0.0..5.0 -> 12.0  // Early momentum
            conditions.momentum in 30.0..50.0 -> 8.0 // Strong but maybe late
            conditions.momentum > 50 -> 3.0  // FOMO territory
            conditions.momentum < 0 -> 5.0   // Negative momentum
            else -> 8.0
        } * weights.momentumWeight
        totalScore += momentumScore
        maxScore += 15.0
        if (conditions.momentum > 40) reasons.add("High momentum - may be chasing")
        else if (conditions.momentum in 5.0..25.0) reasons.add("Good momentum")
        
        // 5. Support/Resistance (0-10 pts)
        val srScore = when {
            conditions.isNearSupport && !conditions.isNearResistance -> 10.0
            !conditions.isNearSupport && !conditions.isNearResistance -> 6.0
            conditions.isNearResistance -> 2.0  // Near resistance = risky
            else -> 5.0
        } * weights.supportResistanceWeight
        totalScore += srScore
        maxScore += 10.0
        if (conditions.isNearSupport) reasons.add("Near support level")
        if (conditions.isNearResistance) reasons.add("Near resistance - caution")
        
        // 6. Time of Day Score (0-10 pts)
        val hourWinRate = weights.hourlyWinRates[conditions.hourOfDay] ?: 0.5
        val hourTradeCount = weights.hourlyTradeCount[conditions.hourOfDay] ?: 0
        val timeScore = if (hourTradeCount >= 5) {
            (hourWinRate * 10.0) * weights.timeOfDayWeight
        } else {
            5.0 * weights.timeOfDayWeight // Default neutral if not enough data
        }
        totalScore += timeScore
        maxScore += 10.0
        if (hourTradeCount >= 5 && hourWinRate >= 0.6) {
            reasons.add("Good trading hour (${(hourWinRate * 100).toInt()}% win rate)")
        } else if (hourTradeCount >= 5 && hourWinRate < 0.35) {
            reasons.add("Poor trading hour historically")
        }
        
        // 7. Volatility Score (0-5 pts)
        val volScore = when {
            conditions.volatility in 2.0..8.0 -> 5.0  // Sweet spot
            conditions.volatility < 2.0 -> 3.0       // Low vol = small moves
            conditions.volatility in 8.0..15.0 -> 3.0 // High vol = risky
            else -> 1.0  // Extreme volatility
        } * weights.volatilityWeight
        totalScore += volScore
        maxScore += 5.0
        if (conditions.volatility > 12) reasons.add("High volatility - increased risk")
        
        // 8. Candle Pattern Bonus (0-5 pts)
        val patternWinRate = weights.patternWinRates[conditions.candlePattern] ?: 0.5
        val patternCount = weights.patternTradeCount[conditions.candlePattern] ?: 0
        val patternScore = when {
            conditions.candlePattern in listOf("bullish_engulf", "hammer", "morning_star") -> 5.0
            conditions.candlePattern in listOf("doji") && conditions.isNearSupport -> 4.0
            patternCount >= 3 && patternWinRate >= 0.6 -> 5.0
            patternCount >= 3 && patternWinRate < 0.3 -> 0.0
            else -> 2.0
        } * weights.candlePatternWeight
        totalScore += patternScore
        maxScore += 5.0
        
        // Calculate final score (0-100)
        val rawScore = ((totalScore / maxScore) * 100).toInt().coerceIn(0, 100)

        // V5.9.12: Symbolic pressure on candidate generation
        // Aggressive + confident → nudge score up; defensive/panic → nudge down.
        val symNudge = try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            when {
                sc.emotionalState == "PANIC"    -> -12
                sc.emotionalState == "FEARFUL"  -> -6
                sc.emotionalState == "EUPHORIC" -> +6
                sc.emotionalState == "GREEDY"   -> +3
                sc.shouldBeAggressive()         -> +4
                sc.shouldBeDefensive()          -> -4
                else                             -> 0
            }
        } catch (_: Exception) { 0 }
        val finalScore = (rawScore + symNudge).coerceIn(0, 100)
        if (symNudge != 0) reasons.add("Symbolic: ${if (symNudge > 0) "+" else ""}$symNudge (mood-aware)")

        // Determine recommendation
        val recommendation = when {
            finalScore >= 75 -> EntryRecommendation.STRONG_BUY
            finalScore >= 55 -> EntryRecommendation.BUY
            finalScore >= 40 -> EntryRecommendation.WAIT
            else -> EntryRecommendation.AVOID
        }

        // Determine risk level — symbolic PANIC forces one level up
        var riskLevel = when {
            conditions.volatility > 15 || conditions.buyPressure < 35 -> RiskLevel.EXTREME
            conditions.volatility > 10 || conditions.rsi > 80 || conditions.momentum > 50 -> RiskLevel.HIGH
            conditions.volatility > 6 || conditions.rsi > 70 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        try {
            if (com.lifecyclebot.engine.SymbolicContext.emotionalState == "PANIC") {
                riskLevel = when (riskLevel) {
                    RiskLevel.LOW     -> RiskLevel.MEDIUM
                    RiskLevel.MEDIUM  -> RiskLevel.HIGH
                    RiskLevel.HIGH    -> RiskLevel.EXTREME
                    RiskLevel.EXTREME -> RiskLevel.EXTREME
                }
            }
        } catch (_: Exception) {}
        
        // Suggested wait time
        val waitMinutes = when (recommendation) {
            EntryRecommendation.STRONG_BUY -> 0
            EntryRecommendation.BUY -> 0
            EntryRecommendation.WAIT -> if (conditions.momentum > 40) 5 else 2
            EntryRecommendation.AVOID -> 10
        }
        
        // Confidence based on data quality
        val confidence = when {
            weights.totalTrades >= 50 -> 0.8
            weights.totalTrades >= 20 -> 0.6
            weights.totalTrades >= 10 -> 0.4
            else -> 0.3
        }
        
        ErrorLogger.info(TAG, "📊 Entry Score: $finalScore → $recommendation | risk=$riskLevel | ${reasons.take(3).joinToString(", ")}")
        
        return EntryScore(
            score = finalScore,
            recommendation = recommendation,
            confidence = confidence,
            reasons = reasons,
            suggestedWaitMinutes = waitMinutes,
            riskLevel = riskLevel,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // LEARNING FROM OUTCOMES
    // ═══════════════════════════════════════════════════════════════════════
    
    fun recordEntry(mint: String, conditions: EntryConditions) {
        pendingEntries[mint] = conditions
        ErrorLogger.debug(TAG, "📝 Recorded entry conditions for learning: ${mint.take(8)}")
    }
    
    fun learnFromOutcome(mint: String, pnlPercent: Double, holdTimeMinutes: Int) {
        val conditions = pendingEntries.remove(mint) ?: return
        
        val isWin = pnlPercent >= 5.0
        val isLoss = pnlPercent <= -5.0
        
        weights.totalTrades++
        if (isWin) weights.winningTrades++
        
        // Learn time-of-day patterns
        val hour = conditions.hourOfDay
        val currentWinRate = weights.hourlyWinRates[hour] ?: 0.5
        val currentCount = weights.hourlyTradeCount[hour] ?: 0
        val newCount = currentCount + 1
        val newWinRate = ((currentWinRate * currentCount) + if (isWin) 1.0 else 0.0) / newCount
        weights.hourlyWinRates[hour] = newWinRate
        weights.hourlyTradeCount[hour] = newCount
        
        // Learn candle patterns
        val pattern = conditions.candlePattern
        val patternWinRate = weights.patternWinRates[pattern] ?: 0.5
        val patternCount = weights.patternTradeCount[pattern] ?: 0
        val newPatternCount = patternCount + 1
        val newPatternWinRate = ((patternWinRate * patternCount) + if (isWin) 1.0 else 0.0) / newPatternCount
        weights.patternWinRates[pattern] = newPatternWinRate
        weights.patternTradeCount[pattern] = newPatternCount
        
        // Adjust optimal ranges based on outcomes
        if (isWin) {
            // Winning trade - move optimal ranges toward these conditions
            weights.optimalBuyPressureMin = (weights.optimalBuyPressureMin * 0.9 + conditions.buyPressure * 0.1).coerceIn(40.0, 60.0)
            weights.optimalBuyPressureMax = (weights.optimalBuyPressureMax * 0.9 + conditions.buyPressure * 0.1).coerceIn(65.0, 85.0)
            weights.optimalMomentumMin = (weights.optimalMomentumMin * 0.9 + conditions.momentum * 0.1).coerceIn(0.0, 20.0)
            
            ErrorLogger.info(TAG, "✅ WIN learned: hour=$hour pattern=$pattern buy%=${conditions.buyPressure.toInt()}")
        } else if (isLoss) {
            // Losing trade - move away from these conditions
            if (conditions.buyPressure < weights.optimalBuyPressureMin) {
                weights.optimalBuyPressureMin = (weights.optimalBuyPressureMin + 1.0).coerceAtMost(60.0)
            }
            if (conditions.momentum > 40) {
                weights.momentumWeight = (weights.momentumWeight * 1.05).coerceAtMost(1.5)
            }
            if (conditions.rsi > 75) {
                weights.rsiWeight = (weights.rsiWeight * 1.05).coerceAtMost(1.5)
            }
            
            ErrorLogger.info(TAG, "❌ LOSS learned: hour=$hour pattern=$pattern rsi=${conditions.rsi.toInt()}")
        }
        
        // Log learning progress periodically
        if (weights.totalTrades % 10 == 0) {
            val winRate = (weights.winningTrades.toDouble() / weights.totalTrades * 100).toInt()
            ErrorLogger.info(TAG, "📈 Entry AI Stats: ${weights.totalTrades} trades, ${winRate}% win rate")
            ErrorLogger.info(TAG, "📈 Optimal ranges: buy%=${weights.optimalBuyPressureMin.toInt()}-${weights.optimalBuyPressureMax.toInt()}, mom>=${weights.optimalMomentumMin.toInt()}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════
    
    fun saveToPrefs(prefs: SharedPreferences) {
        prefs.edit().apply {
            putFloat("buyPressureWeight", weights.buyPressureWeight.toFloat())
            putFloat("volumeWeight", weights.volumeWeight.toFloat())
            putFloat("momentumWeight", weights.momentumWeight.toFloat())
            putFloat("rsiWeight", weights.rsiWeight.toFloat())
            putFloat("optimalBuyPressureMin", weights.optimalBuyPressureMin.toFloat())
            putFloat("optimalBuyPressureMax", weights.optimalBuyPressureMax.toFloat())
            putFloat("optimalMomentumMin", weights.optimalMomentumMin.toFloat())
            putInt("totalTrades", weights.totalTrades)
            putInt("winningTrades", weights.winningTrades)
            
            // Save hourly win rates
            weights.hourlyWinRates.forEach { (hour, rate) ->
                putFloat("hourWinRate_$hour", rate.toFloat())
                putInt("hourCount_$hour", weights.hourlyTradeCount[hour] ?: 0)
            }
            
            // Save pattern win rates
            weights.patternWinRates.forEach { (pattern, rate) ->
                putFloat("patternWinRate_$pattern", rate.toFloat())
                putInt("patternCount_$pattern", weights.patternTradeCount[pattern] ?: 0)
            }
            
            apply()
        }
        
        // Also save to persistent external storage
        PersistentLearning.saveEntryIntelligence(
            buyPressureWeight = weights.buyPressureWeight,
            volumeWeight = weights.volumeWeight,
            momentumWeight = weights.momentumWeight,
            rsiWeight = weights.rsiWeight,
            optimalBuyPressureMin = weights.optimalBuyPressureMin,
            optimalBuyPressureMax = weights.optimalBuyPressureMax,
            optimalMomentumMin = weights.optimalMomentumMin,
            totalTrades = weights.totalTrades,
            winningTrades = weights.winningTrades,
            hourlyWinRates = weights.hourlyWinRates,
            hourlyTradeCount = weights.hourlyTradeCount,
            patternWinRates = weights.patternWinRates,
            patternTradeCount = weights.patternTradeCount,
        )
        
        ErrorLogger.info(TAG, "💾 Entry AI weights saved")
    }
    
    @Suppress("UNCHECKED_CAST")
    fun loadFromPrefs(prefs: SharedPreferences) {
        // First try to load from persistent external storage (survives reinstall)
        val persistent = PersistentLearning.loadEntryIntelligence()
        
        if (persistent != null && (persistent["totalTrades"] as Int) > prefs.getInt("totalTrades", 0)) {
            // Persistent storage has more data - use it
            weights = LearnedWeights(
                buyPressureWeight = persistent["buyPressureWeight"] as Double,
                volumeWeight = persistent["volumeWeight"] as Double,
                momentumWeight = persistent["momentumWeight"] as Double,
                rsiWeight = persistent["rsiWeight"] as Double,
                optimalBuyPressureMin = persistent["optimalBuyPressureMin"] as Double,
                optimalBuyPressureMax = persistent["optimalBuyPressureMax"] as Double,
                optimalMomentumMin = persistent["optimalMomentumMin"] as Double,
                totalTrades = persistent["totalTrades"] as Int,
                winningTrades = persistent["winningTrades"] as Int,
                hourlyWinRates = (persistent["hourlyWinRates"] as Map<Int, Double>).toMutableMap(),
                hourlyTradeCount = (persistent["hourlyTradeCount"] as Map<Int, Int>).toMutableMap(),
                patternWinRates = (persistent["patternWinRates"] as Map<String, Double>).toMutableMap(),
                patternTradeCount = (persistent["patternTradeCount"] as Map<String, Int>).toMutableMap(),
            )
            
            val winRate = if (weights.totalTrades > 0) (weights.winningTrades.toDouble() / weights.totalTrades * 100).toInt() else 0
            ErrorLogger.info(TAG, "📂 Entry AI loaded from PERSISTENT storage: ${weights.totalTrades} trades, ${winRate}% win rate")
        } else {
            // Use SharedPreferences (normal app storage)
            weights = LearnedWeights(
                buyPressureWeight = prefs.getFloat("buyPressureWeight", 1.0f).toDouble(),
                volumeWeight = prefs.getFloat("volumeWeight", 1.0f).toDouble(),
                momentumWeight = prefs.getFloat("momentumWeight", 1.0f).toDouble(),
                rsiWeight = prefs.getFloat("rsiWeight", 1.0f).toDouble(),
                optimalBuyPressureMin = prefs.getFloat("optimalBuyPressureMin", 50.0f).toDouble(),
                optimalBuyPressureMax = prefs.getFloat("optimalBuyPressureMax", 75.0f).toDouble(),
                optimalMomentumMin = prefs.getFloat("optimalMomentumMin", 5.0f).toDouble(),
                totalTrades = prefs.getInt("totalTrades", 0),
                winningTrades = prefs.getInt("winningTrades", 0),
            )
            
            // Load hourly data
            for (hour in 0..23) {
                val rate = prefs.getFloat("hourWinRate_$hour", -1f)
                val count = prefs.getInt("hourCount_$hour", 0)
                if (rate >= 0 && count > 0) {
                    weights.hourlyWinRates[hour] = rate.toDouble()
                    weights.hourlyTradeCount[hour] = count
                }
            }
            
            // Load pattern data
            listOf("bullish_engulf", "hammer", "doji", "morning_star", "shooting_star", "none").forEach { pattern ->
                val rate = prefs.getFloat("patternWinRate_$pattern", -1f)
                val count = prefs.getInt("patternCount_$pattern", 0)
                if (rate >= 0 && count > 0) {
                    weights.patternWinRates[pattern] = rate.toDouble()
                    weights.patternTradeCount[pattern] = count
                }
            }
            
            val winRate = if (weights.totalTrades > 0) (weights.winningTrades.toDouble() / weights.totalTrades * 100).toInt() else 0
            ErrorLogger.info(TAG, "📂 Entry AI loaded: ${weights.totalTrades} trades, ${winRate}% win rate")
        }
    }
    
    fun getStats(): String {
        val winRate = if (weights.totalTrades > 0) (weights.winningTrades.toDouble() / weights.totalTrades * 100).toInt() else 0
        return "EntryAI: ${weights.totalTrades} trades, ${winRate}% win, buy%=${weights.optimalBuyPressureMin.toInt()}-${weights.optimalBuyPressureMax.toInt()}"
    }
    
    /**
     * Get learned win rate for adaptive confidence integration.
     */
    fun getWinRate(): Double {
        return if (weights.totalTrades > 0) {
            weights.winningTrades.toDouble() / weights.totalTrades * 100.0
        } else 50.0  // Default to 50% if no data
    }
    
    /**
     * Get total trades for learning maturity check.
     */
    fun getTotalTrades(): Int = weights.totalTrades
    
    /**
     * Get trade count alias.
     */
    fun getTradeCount(): Int = weights.totalTrades
    
    /**
     * Clear all learned data - used for self-healing when data is poisoned.
     */
    fun clear() {
        weights = LearnedWeights()
        ErrorLogger.warn(TAG, "🧹 Entry AI cleared - will relearn from scratch")
    }
}
