package com.lifecyclebot.engine

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AI-driven Exit Strategy Intelligence
 * 
 * Learns optimal exit conditions from trade outcomes and provides
 * intelligent, dynamic exit recommendations.
 */
object ExitIntelligence {
    
    private const val TAG = "ExitAI"
    
    // ═══════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════
    
    data class PositionState(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val currentPrice: Double,
        val highestPrice: Double,          // Peak since entry
        val lowestPrice: Double,           // Low since entry
        val pnlPercent: Double,
        val holdTimeMinutes: Int,
        val buyPressure: Double,           // Current buy%
        val entryBuyPressure: Double,      // Buy% at entry
        val volume: Double,
        val volatility: Double,            // ATR-based
        val isDistribution: Boolean,
        val rsi: Double,
        val momentum: Double,
        val qualityGrade: String,          // A, B, C
    )
    
    data class ExitDecision(
        val action: ExitAction,
        val urgency: Urgency,
        val stopLossPercent: Double,       // Dynamic stop loss
        val takeProfitPercent: Double,     // Dynamic take profit
        val trailingStopPercent: Double,   // Trailing stop distance
        val partialExitPercent: Int,       // 0, 25, 50, 75, 100
        val reasons: List<String>,
        val confidence: Double,
    )
    
    enum class ExitAction {
        HOLD,               // Keep position, no action
        TIGHTEN_STOP,       // Move stop loss closer
        PARTIAL_EXIT,       // Take partial profits
        FULL_EXIT,          // Close entire position
        EMERGENCY_EXIT,     // Immediate close - danger detected
    }
    
    enum class Urgency { LOW, MEDIUM, HIGH, CRITICAL }
    
    // Learned parameters for exit strategy
    data class LearnedExitParams(
        // Dynamic stop loss parameters
        var baseStopLoss: Double = -8.0,           // Starting stop loss %
        var volatilityStopMultiplier: Double = 1.5, // How much volatility affects stop
        var qualityStopAdjust: Double = 2.0,        // Better quality = tighter stop allowed
        
        // Take profit parameters
        var baseTakeProfit: Double = 15.0,         // Base take profit %
        var greedFactor: Double = 1.0,             // Learned greed (>1 = let winners run)
        
        // Trailing stop parameters
        var trailingStopDistance: Double = 5.0,    // % below high
        var trailingActivationProfit: Double = 8.0, // Activate trailing at this profit %
        
        // Time-based adjustments
        var maxHoldMinutes: Int = 30,              // Max time before forced review
        var optimalHoldMinutes: Int = 10,          // Learned optimal hold time
        
        // Partial exit thresholds
        var partialExit25Threshold: Double = 10.0,  // Take 25% at this profit
        var partialExit50Threshold: Double = 20.0,  // Take 50% at this profit
        
        // Distribution sensitivity
        var distributionExitThreshold: Double = 0.7, // Exit if distribution conf > this
        
        // Learned from outcomes
        var avgWinningHoldTime: Double = 8.0,
        var avgLosingHoldTime: Double = 15.0,
        var avgWinningPnl: Double = 12.0,
        var avgLosingPnl: Double = -10.0,
        var totalExits: Int = 0,
        var profitableExits: Int = 0,
        
        // Pattern-based exit learning
        var exitReasonSuccess: MutableMap<String, Double> = mutableMapOf(),
        var exitReasonCount: MutableMap<String, Int> = mutableMapOf(),
    )
    
    private var params = LearnedExitParams()
    
    // Track positions for learning
    data class PositionTracker(
        val entryPrice: Double,
        val entryBuyPressure: Double,
        val quality: String,
        val entryTime: Long,
        var highestPrice: Double,
        var lowestPrice: Double,
        var partialExitsTaken: Int = 0,
    )
    
    private val activePositions = ConcurrentHashMap<String, PositionTracker>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // EXIT DECISION ENGINE
    // ═══════════════════════════════════════════════════════════════════════
    
    fun evaluateExit(state: PositionState): ExitDecision {
        val reasons = mutableListOf<String>()
        var action = ExitAction.HOLD
        var urgency = Urgency.LOW
        var partialExitPct = 0
        
        // Track position peaks/lows
        val tracker = activePositions.getOrPut(state.mint) {
            PositionTracker(
                entryPrice = state.entryPrice,
                entryBuyPressure = state.entryBuyPressure,
                quality = state.qualityGrade,
                entryTime = System.currentTimeMillis(),
                highestPrice = state.entryPrice,
                lowestPrice = state.entryPrice,
            )
        }
        
        // Update peaks
        if (state.currentPrice > tracker.highestPrice) {
            tracker.highestPrice = state.currentPrice
        }
        if (state.currentPrice < tracker.lowestPrice) {
            tracker.lowestPrice = state.currentPrice
        }
        
        // Calculate drawdown from peak
        val drawdownFromPeak = if (tracker.highestPrice > 0) {
            ((tracker.highestPrice - state.currentPrice) / tracker.highestPrice) * 100
        } else 0.0
        
        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC STOP LOSS CALCULATION
        // ═══════════════════════════════════════════════════════════════════
        
        // Base stop adjusted by volatility
        val volatilityAdjust = state.volatility * params.volatilityStopMultiplier
        
        // Quality adjustment (A = tighter stop OK, C = wider stop needed)
        val qualityAdjust = when (state.qualityGrade) {
            "A" -> params.qualityStopAdjust
            "B" -> params.qualityStopAdjust / 2
            else -> 0.0
        }
        
        // Dynamic stop loss
        val dynamicStopLoss = (params.baseStopLoss - volatilityAdjust + qualityAdjust)
            .coerceIn(-20.0, -3.0)
        
        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC TAKE PROFIT CALCULATION
        // ═══════════════════════════════════════════════════════════════════
        
        // Base take profit adjusted by momentum
        val momentumBonus = if (state.momentum > 20) (state.momentum - 20) * 0.2 else 0.0
        
        // Quality adjustment (better quality = can aim higher)
        val qualityTpBonus = when (state.qualityGrade) {
            "A" -> 5.0
            "B" -> 2.0
            else -> 0.0
        }
        
        val dynamicTakeProfit = (params.baseTakeProfit + momentumBonus + qualityTpBonus) * params.greedFactor
        
        // ═══════════════════════════════════════════════════════════════════
        // TRAILING STOP
        // ═══════════════════════════════════════════════════════════════════
        
        val trailingStopActive = state.pnlPercent >= params.trailingActivationProfit
        val trailingStopPrice = if (trailingStopActive) {
            tracker.highestPrice * (1.0 - params.trailingStopDistance / 100.0)
        } else 0.0
        
        val trailingStopHit = trailingStopActive && state.currentPrice <= trailingStopPrice
        
        // ═══════════════════════════════════════════════════════════════════
        // EXIT CHECKS (Priority Order)
        // ═══════════════════════════════════════════════════════════════════
        
        // 1. EMERGENCY: Distribution detected while holding
        if (state.isDistribution && state.buyPressure < 40) {
            action = ExitAction.EMERGENCY_EXIT
            urgency = Urgency.CRITICAL
            reasons.add("Distribution detected (buy%=${state.buyPressure.toInt()})")
        }
        
        // 2. EMERGENCY: Severe loss
        else if (state.pnlPercent <= -15) {
            action = ExitAction.EMERGENCY_EXIT
            urgency = Urgency.CRITICAL
            reasons.add("Severe loss (${state.pnlPercent.toInt()}%)")
        }
        
        // 3. Stop loss hit
        else if (state.pnlPercent <= dynamicStopLoss) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Stop loss hit (${state.pnlPercent.toInt()}% <= ${dynamicStopLoss.toInt()}%)")
        }
        
        // 4. Trailing stop hit
        else if (trailingStopHit) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Trailing stop hit (drawdown=${drawdownFromPeak.toInt()}% from peak)")
        }
        
        // 5. Take profit hit
        else if (state.pnlPercent >= dynamicTakeProfit) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.MEDIUM
            reasons.add("Take profit hit (${state.pnlPercent.toInt()}% >= ${dynamicTakeProfit.toInt()}%)")
        }
        
        // 6. Partial exits
        else if (state.pnlPercent >= params.partialExit50Threshold && tracker.partialExitsTaken < 2) {
            action = ExitAction.PARTIAL_EXIT
            partialExitPct = 50
            urgency = Urgency.MEDIUM
            reasons.add("Partial exit at ${state.pnlPercent.toInt()}% profit")
            tracker.partialExitsTaken++
        }
        else if (state.pnlPercent >= params.partialExit25Threshold && tracker.partialExitsTaken < 1) {
            action = ExitAction.PARTIAL_EXIT
            partialExitPct = 25
            urgency = Urgency.LOW
            reasons.add("Partial exit at ${state.pnlPercent.toInt()}% profit")
            tracker.partialExitsTaken++
        }
        
        // 7. Time-based review
        else if (state.holdTimeMinutes >= params.maxHoldMinutes) {
            if (state.pnlPercent > 0) {
                action = ExitAction.TIGHTEN_STOP
                urgency = Urgency.MEDIUM
                reasons.add("Long hold time (${state.holdTimeMinutes}min) - tighten stop")
            } else {
                action = ExitAction.FULL_EXIT
                urgency = Urgency.MEDIUM
                reasons.add("Max hold time reached with loss")
            }
        }
        
        // 8. Buy pressure collapse
        else if (state.entryBuyPressure - state.buyPressure >= 20) {
            action = ExitAction.TIGHTEN_STOP
            urgency = Urgency.MEDIUM
            reasons.add("Buy pressure dropped ${(state.entryBuyPressure - state.buyPressure).toInt()}%")
        }
        
        // 9. RSI overbought while in profit
        else if (state.rsi > 80 && state.pnlPercent > 5) {
            action = ExitAction.TIGHTEN_STOP
            urgency = Urgency.LOW
            reasons.add("RSI overbought (${state.rsi.toInt()}) - tighten stop")
        }
        
        // Default: HOLD
        else {
            action = ExitAction.HOLD
            urgency = Urgency.LOW
            if (state.pnlPercent > 0) {
                reasons.add("In profit (${state.pnlPercent.toInt()}%) - holding")
            } else {
                reasons.add("Within stop tolerance - holding")
            }
        }
        
        // Calculate confidence based on data
        val confidence = when {
            params.totalExits >= 30 -> 0.8
            params.totalExits >= 15 -> 0.6
            params.totalExits >= 5 -> 0.4
            else -> 0.3
        }
        
        val decision = ExitDecision(
            action = action,
            urgency = urgency,
            stopLossPercent = dynamicStopLoss,
            takeProfitPercent = dynamicTakeProfit,
            trailingStopPercent = if (trailingStopActive) params.trailingStopDistance else 0.0,
            partialExitPercent = partialExitPct,
            reasons = reasons,
            confidence = confidence,
        )
        
        if (action != ExitAction.HOLD) {
            ErrorLogger.info(TAG, "🎯 Exit Decision: $action ($urgency) | ${reasons.firstOrNull()}")
        }
        
        return decision
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // LEARNING FROM EXITS
    // ═══════════════════════════════════════════════════════════════════════
    
    fun learnFromExit(mint: String, exitReason: String, pnlPercent: Double, holdTimeMinutes: Int) {
        val tracker = activePositions.remove(mint) ?: return
        
        val isProfit = pnlPercent > 0
        
        params.totalExits++
        if (isProfit) params.profitableExits++
        
        // Update average hold times
        if (isProfit) {
            params.avgWinningHoldTime = (params.avgWinningHoldTime * 0.9 + holdTimeMinutes * 0.1)
            params.avgWinningPnl = (params.avgWinningPnl * 0.9 + pnlPercent * 0.1)
            
            // Learn to let winners run longer if this was a good exit
            if (pnlPercent > params.avgWinningPnl) {
                params.greedFactor = (params.greedFactor * 1.02).coerceAtMost(1.5)
            }
            
            // Adjust optimal hold time toward winning trades
            params.optimalHoldMinutes = ((params.optimalHoldMinutes * 0.8 + holdTimeMinutes * 0.2).toInt())
                .coerceIn(5, 60)
        } else {
            params.avgLosingHoldTime = (params.avgLosingHoldTime * 0.9 + holdTimeMinutes * 0.1)
            params.avgLosingPnl = (params.avgLosingPnl * 0.9 + pnlPercent * 0.1)
            
            // Learn to cut losses faster
            if (holdTimeMinutes > params.avgWinningHoldTime) {
                params.maxHoldMinutes = ((params.maxHoldMinutes * 0.95).toInt()).coerceIn(15, 60)
            }
            
            // Tighten stop loss if we're losing too much
            if (pnlPercent < params.avgLosingPnl) {
                params.baseStopLoss = (params.baseStopLoss + 0.5).coerceIn(-15.0, -5.0)
            }
        }
        
        // Learn from exit reasons
        val reasonKey = exitReason.take(20)
        val currentSuccess = params.exitReasonSuccess[reasonKey] ?: 0.5
        val currentCount = params.exitReasonCount[reasonKey] ?: 0
        val newCount = currentCount + 1
        val newSuccess = ((currentSuccess * currentCount) + if (isProfit) 1.0 else 0.0) / newCount
        params.exitReasonSuccess[reasonKey] = newSuccess
        params.exitReasonCount[reasonKey] = newCount
        
        // Adjust partial exit thresholds based on outcomes
        if (isProfit && pnlPercent > params.partialExit50Threshold) {
            // We exited too early on a big winner - raise thresholds
            params.partialExit50Threshold = (params.partialExit50Threshold + 1.0).coerceAtMost(30.0)
            params.partialExit25Threshold = (params.partialExit25Threshold + 0.5).coerceAtMost(15.0)
        }
        
        val winRate = (params.profitableExits.toDouble() / params.totalExits * 100).toInt()
        
        ErrorLogger.info(TAG, 
            if (isProfit) "✅ WIN exit: ${pnlPercent.toInt()}% in ${holdTimeMinutes}min | reason=$reasonKey"
            else "❌ LOSS exit: ${pnlPercent.toInt()}% in ${holdTimeMinutes}min | reason=$reasonKey"
        )
        
        // Log learning progress periodically
        if (params.totalExits % 10 == 0) {
            ErrorLogger.info(TAG, "📈 Exit AI Stats: ${params.totalExits} exits, ${winRate}% profitable")
            ErrorLogger.info(TAG, "📈 Params: stop=${params.baseStopLoss.toInt()}% tp=${params.baseTakeProfit.toInt()}% greed=${String.format("%.2f", params.greedFactor)}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // QUICK CHECKS (for use in monitoring loops)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Quick check if position needs attention
     */
    fun needsAttention(pnlPercent: Double, holdTimeMinutes: Int, buyPressure: Double): Boolean {
        return pnlPercent <= params.baseStopLoss ||      // Near stop
               pnlPercent >= params.baseTakeProfit ||    // Near target
               holdTimeMinutes >= params.maxHoldMinutes || // Long hold
               buyPressure < 35                            // Pressure collapse
    }
    
    /**
     * Get current stop loss for a position
     */
    fun getCurrentStopLoss(quality: String, volatility: Double): Double {
        val volatilityAdjust = volatility * params.volatilityStopMultiplier
        val qualityAdjust = when (quality) {
            "A" -> params.qualityStopAdjust
            "B" -> params.qualityStopAdjust / 2
            else -> 0.0
        }
        return (params.baseStopLoss - volatilityAdjust + qualityAdjust).coerceIn(-20.0, -3.0)
    }
    
    /**
     * Get current take profit for a position
     */
    fun getCurrentTakeProfit(quality: String, momentum: Double): Double {
        val momentumBonus = if (momentum > 20) (momentum - 20) * 0.2 else 0.0
        val qualityBonus = when (quality) { "A" -> 5.0; "B" -> 2.0; else -> 0.0 }
        return (params.baseTakeProfit + momentumBonus + qualityBonus) * params.greedFactor
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════
    
    fun saveToPrefs(prefs: SharedPreferences) {
        prefs.edit().apply {
            putFloat("baseStopLoss", params.baseStopLoss.toFloat())
            putFloat("baseTakeProfit", params.baseTakeProfit.toFloat())
            putFloat("greedFactor", params.greedFactor.toFloat())
            putFloat("trailingStopDistance", params.trailingStopDistance.toFloat())
            putFloat("trailingActivationProfit", params.trailingActivationProfit.toFloat())
            putInt("maxHoldMinutes", params.maxHoldMinutes)
            putInt("optimalHoldMinutes", params.optimalHoldMinutes)
            putFloat("partialExit25Threshold", params.partialExit25Threshold.toFloat())
            putFloat("partialExit50Threshold", params.partialExit50Threshold.toFloat())
            putFloat("avgWinningHoldTime", params.avgWinningHoldTime.toFloat())
            putFloat("avgLosingHoldTime", params.avgLosingHoldTime.toFloat())
            putFloat("avgWinningPnl", params.avgWinningPnl.toFloat())
            putFloat("avgLosingPnl", params.avgLosingPnl.toFloat())
            putInt("totalExits", params.totalExits)
            putInt("profitableExits", params.profitableExits)
            apply()
        }
        
        // Also save to persistent external storage
        PersistentLearning.saveExitIntelligence(
            baseStopLoss = params.baseStopLoss,
            baseTakeProfit = params.baseTakeProfit,
            greedFactor = params.greedFactor,
            trailingStopDistance = params.trailingStopDistance,
            trailingActivationProfit = params.trailingActivationProfit,
            maxHoldMinutes = params.maxHoldMinutes,
            optimalHoldMinutes = params.optimalHoldMinutes,
            partialExit25Threshold = params.partialExit25Threshold,
            partialExit50Threshold = params.partialExit50Threshold,
            avgWinningHoldTime = params.avgWinningHoldTime,
            avgLosingHoldTime = params.avgLosingHoldTime,
            avgWinningPnl = params.avgWinningPnl,
            avgLosingPnl = params.avgLosingPnl,
            totalExits = params.totalExits,
            profitableExits = params.profitableExits,
        )
        
        ErrorLogger.info(TAG, "💾 Exit AI params saved")
    }
    
    fun loadFromPrefs(prefs: SharedPreferences) {
        // First try to load from persistent external storage (survives reinstall)
        val persistent = PersistentLearning.loadExitIntelligence()
        
        if (persistent != null && (persistent["totalExits"] as Int) > prefs.getInt("totalExits", 0)) {
            // Persistent storage has more data - use it
            params = LearnedExitParams(
                baseStopLoss = persistent["baseStopLoss"] as Double,
                baseTakeProfit = persistent["baseTakeProfit"] as Double,
                greedFactor = persistent["greedFactor"] as Double,
                trailingStopDistance = persistent["trailingStopDistance"] as Double,
                trailingActivationProfit = persistent["trailingActivationProfit"] as Double,
                maxHoldMinutes = persistent["maxHoldMinutes"] as Int,
                optimalHoldMinutes = persistent["optimalHoldMinutes"] as Int,
                partialExit25Threshold = persistent["partialExit25Threshold"] as Double,
                partialExit50Threshold = persistent["partialExit50Threshold"] as Double,
                avgWinningHoldTime = persistent["avgWinningHoldTime"] as Double,
                avgLosingHoldTime = persistent["avgLosingHoldTime"] as Double,
                avgWinningPnl = persistent["avgWinningPnl"] as Double,
                avgLosingPnl = persistent["avgLosingPnl"] as Double,
                totalExits = persistent["totalExits"] as Int,
                profitableExits = persistent["profitableExits"] as Int,
            )
            
            val winRate = if (params.totalExits > 0) (params.profitableExits.toDouble() / params.totalExits * 100).toInt() else 0
            ErrorLogger.info(TAG, "📂 Exit AI loaded from PERSISTENT storage: ${params.totalExits} exits, ${winRate}% profitable")
        } else {
            // Use SharedPreferences (normal app storage)
            params = LearnedExitParams(
                baseStopLoss = prefs.getFloat("baseStopLoss", -8.0f).toDouble(),
                baseTakeProfit = prefs.getFloat("baseTakeProfit", 15.0f).toDouble(),
                greedFactor = prefs.getFloat("greedFactor", 1.0f).toDouble(),
                trailingStopDistance = prefs.getFloat("trailingStopDistance", 5.0f).toDouble(),
                trailingActivationProfit = prefs.getFloat("trailingActivationProfit", 8.0f).toDouble(),
                maxHoldMinutes = prefs.getInt("maxHoldMinutes", 30),
                optimalHoldMinutes = prefs.getInt("optimalHoldMinutes", 10),
                partialExit25Threshold = prefs.getFloat("partialExit25Threshold", 10.0f).toDouble(),
                partialExit50Threshold = prefs.getFloat("partialExit50Threshold", 20.0f).toDouble(),
                avgWinningHoldTime = prefs.getFloat("avgWinningHoldTime", 8.0f).toDouble(),
                avgLosingHoldTime = prefs.getFloat("avgLosingHoldTime", 15.0f).toDouble(),
                avgWinningPnl = prefs.getFloat("avgWinningPnl", 12.0f).toDouble(),
                avgLosingPnl = prefs.getFloat("avgLosingPnl", -10.0f).toDouble(),
                totalExits = prefs.getInt("totalExits", 0),
                profitableExits = prefs.getInt("profitableExits", 0),
            )
            
            val winRate = if (params.totalExits > 0) (params.profitableExits.toDouble() / params.totalExits * 100).toInt() else 0
            ErrorLogger.info(TAG, "📂 Exit AI loaded: ${params.totalExits} exits, ${winRate}% profitable, stop=${params.baseStopLoss.toInt()}%")
        }
    }
    
    fun getStats(): String {
        val winRate = if (params.totalExits > 0) (params.profitableExits.toDouble() / params.totalExits * 100).toInt() else 0
        return "ExitAI: ${params.totalExits} exits, ${winRate}% win, stop=${params.baseStopLoss.toInt()}% tp=${params.baseTakeProfit.toInt()}%"
    }
    
    fun resetPosition(mint: String) {
        activePositions.remove(mint)
    }
}
