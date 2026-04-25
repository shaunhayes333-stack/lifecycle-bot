package com.lifecyclebot.engine

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

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
        HOLD,
        TIGHTEN_STOP,
        PARTIAL_EXIT,
        FULL_EXIT,
        EMERGENCY_EXIT,
    }

    enum class Urgency { LOW, MEDIUM, HIGH, CRITICAL }

    data class LearnedExitParams(
        var baseStopLoss: Double = -8.0,
        var volatilityStopMultiplier: Double = 1.5,
        var qualityStopAdjust: Double = 2.0,

        var baseTakeProfit: Double = 20.0,
        var greedFactor: Double = 1.0,

        var trailingStopDistance: Double = 5.0,
        var trailingActivationProfit: Double = 8.0,

        var maxHoldMinutes: Int = 30,
        var optimalHoldMinutes: Int = 10,

        var partialExit25Threshold: Double = 10.0,
        var partialExit50Threshold: Double = 20.0,

        var distributionExitThreshold: Double = 0.7,

        var avgWinningHoldTime: Double = 8.0,
        var avgLosingHoldTime: Double = 15.0,
        var avgWinningPnl: Double = 12.0,
        var avgLosingPnl: Double = -10.0,
        var totalExits: Int = 0,
        var profitableExits: Int = 0,

        var exitReasonSuccess: MutableMap<String, Double> = mutableMapOf(),
        var exitReasonCount: MutableMap<String, Int> = mutableMapOf(),
    )

    @Volatile
    private var params = LearnedExitParams()

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
        if (state.entryPrice <= 0.0 || state.currentPrice <= 0.0) {
            return ExitDecision(
                action = ExitAction.HOLD,
                urgency = Urgency.LOW,
                stopLossPercent = params.baseStopLoss,
                takeProfitPercent = params.baseTakeProfit,
                trailingStopPercent = 0.0,
                partialExitPercent = 0,
                reasons = listOf("Invalid price data"),
                confidence = 0.1,
            )
        }

        val reasons = mutableListOf<String>()
        var action = ExitAction.HOLD
        var urgency = Urgency.LOW
        var partialExitPct = 0

        val normalizedQuality = normalizeQuality(state.qualityGrade)

        val tracker = activePositions.getOrPut(state.mint) {
            PositionTracker(
                entryPrice = state.entryPrice,
                entryBuyPressure = state.entryBuyPressure,
                quality = normalizedQuality,
                entryTime = System.currentTimeMillis(),
                highestPrice = state.highestPrice.takeIf { it > 0.0 } ?: state.entryPrice,
                lowestPrice = state.lowestPrice.takeIf { it > 0.0 } ?: state.entryPrice,
            )
        }

        if (state.currentPrice > tracker.highestPrice) {
            tracker.highestPrice = state.currentPrice
        }
        if (state.currentPrice < tracker.lowestPrice) {
            tracker.lowestPrice = state.currentPrice
        }

        val drawdownFromPeak = if (tracker.highestPrice > 0.0) {
            ((tracker.highestPrice - state.currentPrice) / tracker.highestPrice) * 100.0
        } else {
            0.0
        }

        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC STOP LOSS
        // ═══════════════════════════════════════════════════════════════════

        val safeVolatility = state.volatility.coerceIn(0.0, 25.0)
        val volatilityAdjust = safeVolatility * params.volatilityStopMultiplier

        val qualityAdjust = when (normalizedQuality) {
            "A" -> params.qualityStopAdjust
            "B" -> params.qualityStopAdjust / 2.0
            else -> 0.0
        }

        val dynamicStopLoss = (params.baseStopLoss - volatilityAdjust + qualityAdjust)
            .coerceIn(-20.0, -6.0)

        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC TAKE PROFIT
        // ═══════════════════════════════════════════════════════════════════

        val momentumBonus = if (state.momentum > 20.0) {
            (state.momentum - 20.0) * 0.2
        } else {
            0.0
        }

        val qualityTpBonus = when (normalizedQuality) {
            "A" -> 5.0
            "B" -> 2.0
            else -> 0.0
        }

        val dynamicTakeProfit = (
            (params.baseTakeProfit + momentumBonus + qualityTpBonus) * params.greedFactor
        ).coerceIn(5.0, 100.0)

        // ═══════════════════════════════════════════════════════════════════
        // TRAILING STOP
        // ═══════════════════════════════════════════════════════════════════

        val trailingDistance = params.trailingStopDistance.coerceIn(2.0, 20.0)
        val trailingActivationProfit = params.trailingActivationProfit.coerceIn(2.0, 50.0)

        val trailingStopActive = state.pnlPercent >= trailingActivationProfit
        val trailingStopPrice = if (trailingStopActive) {
            tracker.highestPrice * (1.0 - trailingDistance / 100.0)
        } else {
            0.0
        }
        val trailingStopHit = trailingStopActive && state.currentPrice <= trailingStopPrice

        // ═══════════════════════════════════════════════════════════════════
        // EXIT CHECKS
        // ═══════════════════════════════════════════════════════════════════

        val holdTimeMs = max(0L, System.currentTimeMillis() - tracker.entryTime)
        val holdTimeSecs = holdTimeMs / 1000L
        val isInEarlyPhase = holdTimeSecs < 60L

        // V5.9.226: Bug #6 — ExitManager had pnlPct > 0 distribution exit that was NEVER reached
        // (ExitManager.evaluate() was dead code). Added here to secure gains on distribution.
        if (!isInEarlyPhase && state.isDistribution && state.buyPressure < 20.0 && state.pnlPercent > 5.0) {
            // Distribution detected while profitable — secure gains before reversal
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Distribution in profit — securing ${state.pnlPercent.toInt()}% gain (buy%=${state.buyPressure.toInt()}%)")
        } else if (!isInEarlyPhase && state.isDistribution && state.buyPressure < 20.0 && state.pnlPercent < -5.0) {
            action = ExitAction.EMERGENCY_EXIT
            urgency = Urgency.CRITICAL
            reasons.add("Distribution detected (buy%=${state.buyPressure.toInt()}, pnl=${state.pnlPercent.toInt()}%)")
        } else if (state.pnlPercent <= -20.0 && holdTimeSecs >= 30L) {
            action = ExitAction.EMERGENCY_EXIT
            urgency = Urgency.CRITICAL
            reasons.add("Severe loss (${state.pnlPercent.toInt()}%)")
        } else if (!isInEarlyPhase && state.pnlPercent <= dynamicStopLoss) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Stop loss hit (${state.pnlPercent.toInt()}% <= ${dynamicStopLoss.toInt()}%)")
        } else if (trailingStopHit) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Trailing stop hit (drawdown=${drawdownFromPeak.toInt()}% from peak)")
        } else if (state.pnlPercent >= dynamicTakeProfit) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.MEDIUM
            reasons.add("Take profit hit (${state.pnlPercent.toInt()}% >= ${dynamicTakeProfit.toInt()}%)")
        } else if (state.pnlPercent >= params.partialExit50Threshold && tracker.partialExitsTaken < 2) {
            action = ExitAction.PARTIAL_EXIT
            partialExitPct = 50
            urgency = Urgency.MEDIUM
            reasons.add("Partial exit at ${state.pnlPercent.toInt()}% profit")
        } else if (state.pnlPercent >= params.partialExit25Threshold && tracker.partialExitsTaken < 1) {
            action = ExitAction.PARTIAL_EXIT
            partialExitPct = 25
            urgency = Urgency.LOW
            reasons.add("Partial exit at ${state.pnlPercent.toInt()}% profit")
        } else if (state.holdTimeMinutes >= max(params.maxHoldMinutes, 60)) {
            if (state.pnlPercent > 0.0) {
                action = ExitAction.TIGHTEN_STOP
                urgency = Urgency.MEDIUM
                reasons.add("Long hold time (${state.holdTimeMinutes}min) - tighten stop")
            }
        } else if ((state.entryBuyPressure - state.buyPressure) >= 30.0 && !isInEarlyPhase) {
            action = ExitAction.TIGHTEN_STOP
            urgency = Urgency.LOW
            reasons.add("Buy pressure dropped ${(state.entryBuyPressure - state.buyPressure).toInt()}%")
        } else if (state.rsi > 80.0 && state.pnlPercent > 5.0) {
            action = ExitAction.TIGHTEN_STOP
            urgency = Urgency.LOW
            reasons.add("RSI overbought (${state.rsi.toInt()}) - tighten stop")
        } else {
            if (state.pnlPercent > 0.0) {
                reasons.add("In profit (${state.pnlPercent.toInt()}%) - holding")
            } else {
                reasons.add("Within stop tolerance - holding")
            }
        }

        val confidence = when {
            params.totalExits >= 50 -> 0.85
            params.totalExits >= 30 -> 0.75
            params.totalExits >= 15 -> 0.60
            params.totalExits >= 5 -> 0.40
            else -> 0.30
        }

        val decision = ExitDecision(
            action = action,
            urgency = urgency,
            stopLossPercent = dynamicStopLoss,
            takeProfitPercent = dynamicTakeProfit,
            trailingStopPercent = if (trailingStopActive) trailingDistance else 0.0,
            partialExitPercent = partialExitPct,
            reasons = reasons,
            confidence = confidence,
        )

        if (action != ExitAction.HOLD) {
            ErrorLogger.info(TAG, "🎯 Exit Decision: $action ($urgency) | ${reasons.firstOrNull()}")
        }

        return decision
    }

    /**
     * IMPORTANT:
     * Call this only AFTER a partial exit order actually fills.
     * This avoids consuming partial exits just from repeated evaluations.
     */
    fun confirmPartialExit(mint: String, soldPercent: Int) {
        val tracker = activePositions[mint] ?: return
        when {
            soldPercent >= 50 -> tracker.partialExitsTaken = max(tracker.partialExitsTaken, 2)
            soldPercent >= 25 -> tracker.partialExitsTaken = max(tracker.partialExitsTaken, 1)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEARNING FROM EXITS
    // ═══════════════════════════════════════════════════════════════════════

    fun learnFromExit(mint: String, exitReason: String, pnlPercent: Double, holdTimeMinutes: Int) {
        activePositions.remove(mint)

        val isProfit = pnlPercent > 0.0

        params.totalExits++
        if (isProfit) params.profitableExits++

        if (isProfit) {
            params.avgWinningHoldTime = (params.avgWinningHoldTime * 0.9) + (holdTimeMinutes * 0.1)
            params.avgWinningPnl = (params.avgWinningPnl * 0.9) + (pnlPercent * 0.1)

            if (pnlPercent > params.avgWinningPnl) {
                params.greedFactor = (params.greedFactor * 1.02).coerceAtMost(1.5)
            }

            params.optimalHoldMinutes = (
                (params.optimalHoldMinutes * 0.8) + (holdTimeMinutes * 0.2)
            ).toInt().coerceIn(5, 60)
        } else {
            params.avgLosingHoldTime = (params.avgLosingHoldTime * 0.9) + (holdTimeMinutes * 0.1)
            params.avgLosingPnl = (params.avgLosingPnl * 0.9) + (pnlPercent * 0.1)

            if (holdTimeMinutes > params.avgWinningHoldTime) {
                params.maxHoldMinutes = ((params.maxHoldMinutes * 0.95).toInt()).coerceIn(15, 60)
            }

            if (pnlPercent < params.avgLosingPnl) {
                params.baseStopLoss = (params.baseStopLoss + 0.5).coerceIn(-15.0, -5.0)
            }
        }

        val reasonKey = exitReason.take(32)
        val currentSuccess = params.exitReasonSuccess[reasonKey] ?: 0.5
        val currentCount = params.exitReasonCount[reasonKey] ?: 0
        val newCount = currentCount + 1
        val newSuccess = ((currentSuccess * currentCount) + if (isProfit) 1.0 else 0.0) / newCount

        params.exitReasonSuccess[reasonKey] = newSuccess
        params.exitReasonCount[reasonKey] = newCount

        if (isProfit && pnlPercent > params.partialExit50Threshold) {
            params.partialExit50Threshold = (params.partialExit50Threshold + 1.0).coerceAtMost(30.0)
            params.partialExit25Threshold = (params.partialExit25Threshold + 0.5).coerceAtMost(15.0)
        }

        val winRate = if (params.totalExits > 0) {
            (params.profitableExits.toDouble() / params.totalExits * 100.0).toInt()
        } else {
            0
        }

        ErrorLogger.info(
            TAG,
            if (isProfit) {
                "✅ WIN exit: ${pnlPercent.toInt()}% in ${holdTimeMinutes}min | reason=$reasonKey"
            } else {
                "❌ LOSS exit: ${pnlPercent.toInt()}% in ${holdTimeMinutes}min | reason=$reasonKey"
            }
        )

        if (params.totalExits % 10 == 0) {
            ErrorLogger.info(TAG, "📈 Exit AI Stats: ${params.totalExits} exits, ${winRate}% profitable")
            ErrorLogger.info(
                TAG,
                "📈 Params: stop=${params.baseStopLoss.toInt()}% tp=${params.baseTakeProfit.toInt()}% greed=${String.format("%.2f", params.greedFactor)}"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUICK CHECKS
    // ═══════════════════════════════════════════════════════════════════════

    fun needsAttention(pnlPercent: Double, holdTimeMinutes: Int, buyPressure: Double): Boolean {
        return pnlPercent <= params.baseStopLoss ||
            pnlPercent >= params.baseTakeProfit ||
            holdTimeMinutes >= params.maxHoldMinutes ||
            buyPressure < 35.0
    }

    fun getCurrentStopLoss(quality: String, volatility: Double): Double {
        val normalizedQuality = normalizeQuality(quality)
        val volatilityAdjust = volatility.coerceIn(0.0, 25.0) * params.volatilityStopMultiplier
        val qualityAdjust = when (normalizedQuality) {
            "A" -> params.qualityStopAdjust
            "B" -> params.qualityStopAdjust / 2.0
            else -> 0.0
        }
        return (params.baseStopLoss - volatilityAdjust + qualityAdjust).coerceIn(-20.0, -6.0)
    }

    fun getCurrentTakeProfit(quality: String, momentum: Double): Double {
        val normalizedQuality = normalizeQuality(quality)
        val momentumBonus = if (momentum > 20.0) (momentum - 20.0) * 0.2 else 0.0
        val qualityBonus = when (normalizedQuality) {
            "A" -> 5.0
            "B" -> 2.0
            else -> 0.0
        }
        return ((params.baseTakeProfit + momentumBonus + qualityBonus) * params.greedFactor)
            .coerceIn(5.0, 100.0)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

    fun saveToPrefs(prefs: SharedPreferences) {
        prefs.edit().apply {
            putFloat("baseStopLoss", params.baseStopLoss.toFloat())
            putFloat("volatilityStopMultiplier", params.volatilityStopMultiplier.toFloat())
            putFloat("qualityStopAdjust", params.qualityStopAdjust.toFloat())

            putFloat("baseTakeProfit", params.baseTakeProfit.toFloat())
            putFloat("greedFactor", params.greedFactor.toFloat())

            putFloat("trailingStopDistance", params.trailingStopDistance.toFloat())
            putFloat("trailingActivationProfit", params.trailingActivationProfit.toFloat())

            putInt("maxHoldMinutes", params.maxHoldMinutes)
            putInt("optimalHoldMinutes", params.optimalHoldMinutes)

            putFloat("partialExit25Threshold", params.partialExit25Threshold.toFloat())
            putFloat("partialExit50Threshold", params.partialExit50Threshold.toFloat())
            putFloat("distributionExitThreshold", params.distributionExitThreshold.toFloat())

            putFloat("avgWinningHoldTime", params.avgWinningHoldTime.toFloat())
            putFloat("avgLosingHoldTime", params.avgLosingHoldTime.toFloat())
            putFloat("avgWinningPnl", params.avgWinningPnl.toFloat())
            putFloat("avgLosingPnl", params.avgLosingPnl.toFloat())

            putInt("totalExits", params.totalExits)
            putInt("profitableExits", params.profitableExits)
            apply()
        }

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
        val persistent = PersistentLearning.loadExitIntelligence()
        val persistentTotalExits = persistent.getIntSafe("totalExits", 0)
        val prefsTotalExits = prefs.getInt("totalExits", 0)

        params = if (persistent != null && persistentTotalExits > prefsTotalExits) {
            LearnedExitParams(
                baseStopLoss = persistent.getDoubleSafe("baseStopLoss", -8.0),
                baseTakeProfit = persistent.getDoubleSafe("baseTakeProfit", 20.0),
                greedFactor = persistent.getDoubleSafe("greedFactor", 1.0),
                trailingStopDistance = persistent.getDoubleSafe("trailingStopDistance", 5.0),
                trailingActivationProfit = persistent.getDoubleSafe("trailingActivationProfit", 8.0),
                maxHoldMinutes = persistent.getIntSafe("maxHoldMinutes", 30),
                optimalHoldMinutes = persistent.getIntSafe("optimalHoldMinutes", 10),
                partialExit25Threshold = persistent.getDoubleSafe("partialExit25Threshold", 10.0),
                partialExit50Threshold = persistent.getDoubleSafe("partialExit50Threshold", 20.0),
                avgWinningHoldTime = persistent.getDoubleSafe("avgWinningHoldTime", 8.0),
                avgLosingHoldTime = persistent.getDoubleSafe("avgLosingHoldTime", 15.0),
                avgWinningPnl = persistent.getDoubleSafe("avgWinningPnl", 12.0),
                avgLosingPnl = persistent.getDoubleSafe("avgLosingPnl", -10.0),
                totalExits = persistentTotalExits,
                profitableExits = persistent.getIntSafe("profitableExits", 0),
            )
        } else {
            LearnedExitParams(
                baseStopLoss = prefs.getFloat("baseStopLoss", -8.0f).toDouble(),
                volatilityStopMultiplier = prefs.getFloat("volatilityStopMultiplier", 1.5f).toDouble(),
                qualityStopAdjust = prefs.getFloat("qualityStopAdjust", 2.0f).toDouble(),

                baseTakeProfit = prefs.getFloat("baseTakeProfit", 20.0f).toDouble(),
                greedFactor = prefs.getFloat("greedFactor", 1.0f).toDouble(),

                trailingStopDistance = prefs.getFloat("trailingStopDistance", 5.0f).toDouble(),
                trailingActivationProfit = prefs.getFloat("trailingActivationProfit", 8.0f).toDouble(),

                maxHoldMinutes = prefs.getInt("maxHoldMinutes", 30),
                optimalHoldMinutes = prefs.getInt("optimalHoldMinutes", 10),

                partialExit25Threshold = prefs.getFloat("partialExit25Threshold", 10.0f).toDouble(),
                partialExit50Threshold = prefs.getFloat("partialExit50Threshold", 20.0f).toDouble(),
                distributionExitThreshold = prefs.getFloat("distributionExitThreshold", 0.7f).toDouble(),

                avgWinningHoldTime = prefs.getFloat("avgWinningHoldTime", 8.0f).toDouble(),
                avgLosingHoldTime = prefs.getFloat("avgLosingHoldTime", 15.0f).toDouble(),
                avgWinningPnl = prefs.getFloat("avgWinningPnl", 12.0f).toDouble(),
                avgLosingPnl = prefs.getFloat("avgLosingPnl", -10.0f).toDouble(),

                totalExits = prefs.getInt("totalExits", 0),
                profitableExits = prefs.getInt("profitableExits", 0),
            )
        }

        val winRate = if (params.totalExits > 0) {
            (params.profitableExits.toDouble() / params.totalExits * 100.0).toInt()
        } else {
            0
        }

        ErrorLogger.info(
            TAG,
            "📂 Exit AI loaded: ${params.totalExits} exits, ${winRate}% profitable, stop=${params.baseStopLoss.toInt()}%"
        )
    }

    fun getStats(): String {
        val winRate = if (params.totalExits > 0) {
            (params.profitableExits.toDouble() / params.totalExits * 100.0).toInt()
        } else {
            0
        }
        return "ExitAI: ${params.totalExits} exits, ${winRate}% win, stop=${params.baseStopLoss.toInt()}% tp=${params.baseTakeProfit.toInt()}%"
    }

    fun resetPosition(mint: String) {
        activePositions.remove(mint)
    }

    fun getAveragePnl(): Double {
        return if (params.totalExits > 0) {
            val winWeight = params.profitableExits.toDouble() / params.totalExits
            val lossWeight = 1.0 - winWeight
            (params.avgWinningPnl * winWeight) + (params.avgLosingPnl * lossWeight)
        } else {
            0.0
        }
    }

    fun getProfitableRate(): Double {
        return if (params.totalExits > 0) {
            params.profitableExits.toDouble() / params.totalExits * 100.0
        } else {
            50.0
        }
    }

    fun getTotalExits(): Int = params.totalExits
    fun getLearnedOptimalHoldMinutes(): Int = params.optimalHoldMinutes
    fun getLearnedMaxHoldMinutes(): Int = params.maxHoldMinutes
    fun getLearnedTrailingStopDistance(): Double = params.trailingStopDistance

    fun clear() {
        params = LearnedExitParams()
        activePositions.clear()
        ErrorLogger.warn(TAG, "🧹 Exit AI cleared - will relearn from scratch")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun normalizeQuality(raw: String): String {
        return raw.trim().uppercase().takeIf { it in setOf("A", "B", "C") } ?: "C"
    }

    private fun Map<String, Any?>?.getDoubleSafe(key: String, default: Double): Double {
        val value = this?.get(key) ?: return default
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun Map<String, Any?>?.getIntSafe(key: String, default: Int): Int {
        val value = this?.get(key) ?: return default
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }
}