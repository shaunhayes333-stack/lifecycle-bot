package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ADVANCED EXIT MANAGER - Universal Exit Strategies v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This module provides sophisticated exit strategies that can be used by ANY
 * trading mode. It implements:
 *
 * 1. PROGRESSIVE TRAILING STOPS - Gets tighter as profit increases
 * 2. TIME-BASED EXIT SCALING - Adjusts targets based on hold time
 * 3. MOMENTUM-AWARE EXITS - Considers current market conditions
 * 4. PARTIAL PROFIT TAKING - Chunk sells at milestones
 * 5. LOSS CUTTING - Smart stop loss management
 *
 * KEY PRINCIPLE: "Let winners run, cut losers fast"
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object AdvancedExitManager {

    private const val TAG = "ExitMgr"

    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT STRATEGY PROFILES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Predefined exit profiles for different trading modes
     */
    enum class ExitProfile(
        val baseTakeProfitPct: Double,
        val baseStopLossPct: Double,
        val baseTrailingPct: Double,
        val maxHoldMinutes: Int,
        val chunkSellEnabled: Boolean,
        val progressiveTrailing: Boolean,
    ) {
        SHITCOIN(
            baseTakeProfitPct = 25.0,
            baseStopLossPct = 10.0,
            baseTrailingPct = 8.0,
            maxHoldMinutes = 15,
            chunkSellEnabled = false,
            progressiveTrailing = true,
        ),

        EXPRESS(
            baseTakeProfitPct = 30.0,
            baseStopLossPct = 8.0,
            baseTrailingPct = 5.0,
            maxHoldMinutes = 10,
            chunkSellEnabled = false,
            progressiveTrailing = true,
        ),

        BLUE_CHIP(
            baseTakeProfitPct = 40.0,
            baseStopLossPct = 15.0,
            baseTrailingPct = 10.0,
            maxHoldMinutes = 120,
            chunkSellEnabled = true,
            progressiveTrailing = true,
        ),

        DIP_HUNTER(
            baseTakeProfitPct = 20.0,
            baseStopLossPct = 15.0,
            baseTrailingPct = 12.0,
            maxHoldMinutes = 360,
            chunkSellEnabled = true,
            progressiveTrailing = false,
        ),

        TREASURY(
            baseTakeProfitPct = 15.0,
            baseStopLossPct = 8.0,
            baseTrailingPct = 6.0,
            maxHoldMinutes = 30,
            chunkSellEnabled = false,
            progressiveTrailing = true,
        ),

        V3_STANDARD(
            baseTakeProfitPct = 35.0,
            baseStopLossPct = 12.0,
            baseTrailingPct = 8.0,
            maxHoldMinutes = 60,
            chunkSellEnabled = true,
            progressiveTrailing = true,
        ),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════

    data class ExitTargets(
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val trailingStopPct: Double,
        val chunk1Pct: Double,
        val chunk1SellPct: Int,
        val chunk2Pct: Double,
        val chunk2SellPct: Int,
        val timeExitMinutes: Int,
    )

    /**
     * Calculate fluid exit targets based on profile and market conditions
     */
    fun calculateExitTargets(
        profile: ExitProfile,
        entryScore: Int,
        momentum: Double,
        volatility: Double,
        marketRegime: String,
    ): ExitTargets {

        val learningProgress = FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)

        var takeProfitPct = profile.baseTakeProfitPct
        var stopLossPct = profile.baseStopLossPct
        var trailingPct = profile.baseTrailingPct
        var maxHold = profile.maxHoldMinutes

        // As learning matures, aim a bit bigger and tighten discipline
        val fluidMultiplier = 0.8 + learningProgress * 0.4 // 0.8 → 1.2
        takeProfitPct *= fluidMultiplier
        stopLossPct *= (1.2 - learningProgress * 0.3) // 1.2 → 0.9

        // Entry quality adjustments
        if (entryScore >= 80) {
            stopLossPct *= 1.2
            takeProfitPct *= 1.3
            maxHold = (maxHold * 1.5).toInt()
        } else if (entryScore <= 50) {
            stopLossPct *= 0.8
            takeProfitPct *= 0.8
            maxHold = (maxHold * 0.7).toInt()
        }

        // Momentum adjustments
        if (momentum > 10) {
            takeProfitPct *= 1.3
            trailingPct *= 1.2
        } else if (momentum < -5) {
            stopLossPct *= 0.8
            takeProfitPct *= 0.7
            trailingPct *= 0.7
        }

        // Volatility adjustments
        if (volatility > 50) {
            stopLossPct *= 1.3
            trailingPct *= 1.4
        } else if (volatility < 10) {
            stopLossPct *= 0.8
            trailingPct *= 0.8
        }

        // Market regime adjustments
        when (marketRegime.uppercase()) {
            "BULL", "TRENDING_UP" -> {
                takeProfitPct *= 1.2
                maxHold = (maxHold * 1.3).toInt()
            }
            "BEAR", "TRENDING_DOWN" -> {
                takeProfitPct *= 0.7
                stopLossPct *= 0.8
                maxHold = (maxHold * 0.6).toInt()
            }
            "RANGE", "CHOPPY" -> {
                takeProfitPct *= 0.8
                trailingPct *= 1.2
            }
        }

        // V5.9.13: Symbolic mood modulation — tighten on risk, loosen on confidence
        try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            when (sc.emotionalState) {
                "PANIC" -> {
                    stopLossPct *= 0.7   // tighter stop
                    trailingPct *= 0.6   // tighter trail
                    maxHold = (maxHold * 0.6).toInt()
                }
                "FEARFUL" -> {
                    stopLossPct *= 0.85
                    trailingPct *= 0.8
                    maxHold = (maxHold * 0.8).toInt()
                }
                "EUPHORIC" -> {
                    trailingPct *= 1.25
                    takeProfitPct *= 1.15
                    maxHold = (maxHold * 1.2).toInt()
                }
                "GREEDY" -> {
                    trailingPct *= 1.1
                    takeProfitPct *= 1.05
                }
                else -> Unit
            }
        } catch (_: Exception) {}

        // Hard limits first so all downstream levels match final values
        takeProfitPct = takeProfitPct.coerceIn(10.0, 200.0)
        stopLossPct = stopLossPct.coerceIn(5.0, 25.0)
        trailingPct = trailingPct.coerceIn(3.0, 20.0)
        maxHold = maxHold.coerceIn(5, 480)

        // Chunk targets derived from final clamped TP
        val chunk1Pct = if (profile.chunkSellEnabled) takeProfitPct * 0.5 else 0.0
        val chunk1Sell = if (profile.chunkSellEnabled) 25 else 0
        val chunk2Pct = if (profile.chunkSellEnabled) takeProfitPct * 0.75 else 0.0
        val chunk2Sell = if (profile.chunkSellEnabled) 25 else 0

        return ExitTargets(
            takeProfitPct = takeProfitPct,
            stopLossPct = stopLossPct,
            trailingStopPct = trailingPct,
            chunk1Pct = chunk1Pct,
            chunk1SellPct = chunk1Sell,
            chunk2Pct = chunk2Pct,
            chunk2SellPct = chunk2Sell,
            timeExitMinutes = maxHold,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROGRESSIVE TRAILING STOP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a trailing STOP PRICE, not a percentage.
     */
    fun calculateProgressiveTrailingStop(
        currentPnlPct: Double,
        baseTrailingPct: Double,
        highWaterMarkPrice: Double,
    ): Double {

        if (currentPnlPct <= 0 || highWaterMarkPrice <= 0.0) {
            return 0.0
        }

        val effectiveTrailingPct = when {
            currentPnlPct >= 100 -> baseTrailingPct * 0.4
            currentPnlPct >= 75 -> baseTrailingPct * 0.5
            currentPnlPct >= 50 -> baseTrailingPct * 0.6
            currentPnlPct >= 30 -> baseTrailingPct * 0.75
            currentPnlPct >= 20 -> baseTrailingPct * 0.85
            currentPnlPct >= 10 -> baseTrailingPct * 0.95
            else -> baseTrailingPct
        }.coerceIn(2.0, 20.0)

        return highWaterMarkPrice * (1 - effectiveTrailingPct / 100.0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIME-BASED EXIT PRESSURE
    // ═══════════════════════════════════════════════════════════════════════════

    fun calculateTimePressure(
        holdMinutes: Int,
        maxHoldMinutes: Int,
        currentPnlPct: Double,
    ): TimePressure {

        val safeMaxHold = maxHoldMinutes.coerceAtLeast(1)
        val holdRatio = holdMinutes.toDouble() / safeMaxHold.toDouble()

        return when {
            holdRatio >= 0.9 && currentPnlPct < 0 -> TimePressure.URGENT_EXIT
            holdRatio >= 0.9 && currentPnlPct > 0 -> TimePressure.TAKE_PROFIT
            holdRatio >= 0.75 -> TimePressure.HIGH_PRESSURE
            holdRatio >= 0.5 -> TimePressure.MODERATE_PRESSURE
            else -> TimePressure.NO_PRESSURE
        }
    }

    enum class TimePressure(val takeProfitMultiplier: Double, val stopMultiplier: Double) {
        NO_PRESSURE(1.0, 1.0),
        MODERATE_PRESSURE(0.9, 0.9),
        HIGH_PRESSURE(0.75, 0.8),
        TAKE_PROFIT(0.5, 0.7),
        URGENT_EXIT(0.0, 0.5),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MASTER EXIT CHECK
    // ═══════════════════════════════════════════════════════════════════════════

    data class ExitDecision(
        val shouldExit: Boolean,
        val sellPct: Int,
        val exitReason: ExitReason,
        val urgency: ExitUrgency,
    )

    enum class ExitReason {
        HOLD,
        TAKE_PROFIT_FULL,
        TAKE_PROFIT_CHUNK,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
        MOMENTUM_EXIT,
        LIQUIDITY_EXIT,
        INVALID_INPUT,
    }

    enum class ExitUrgency {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL,
    }

    /**
     * Master exit evaluation - use this from any trading mode
     */
    fun evaluateExit(
        profile: ExitProfile,
        entryPrice: Double,
        currentPrice: Double,
        highWaterMarkPrice: Double,
        holdMinutes: Int,
        entryScore: Int,
        currentMomentum: Double,
        currentLiquidity: Double,
        entryLiquidity: Double,
        marketRegime: String,
        volatility: Double,
        alreadySoldPct: Int,
    ): ExitDecision {

        if (entryPrice <= 0.0 || currentPrice <= 0.0) {
            ErrorLogger.warn(TAG, "Invalid price input: entry=$entryPrice current=$currentPrice")
            return ExitDecision(true, 100, ExitReason.INVALID_INPUT, ExitUrgency.CRITICAL)
        }

        val pnlPct = (currentPrice - entryPrice) / entryPrice * 100.0
        val targets = calculateExitTargets(profile, entryScore, currentMomentum, volatility, marketRegime)
        val timePressure = calculateTimePressure(holdMinutes, targets.timeExitMinutes, pnlPct)

        val effectiveTakeProfit = targets.takeProfitPct * timePressure.takeProfitMultiplier
        val effectiveStopLoss = targets.stopLossPct * timePressure.stopMultiplier

        // 1. Liquidity collapse
        if (entryLiquidity > 0.0 && currentLiquidity < entryLiquidity * 0.5) {
            return ExitDecision(true, 100, ExitReason.LIQUIDITY_EXIT, ExitUrgency.CRITICAL)
        }

        // 2. Hard stop loss
        if (pnlPct <= -effectiveStopLoss) {
            return ExitDecision(true, 100, ExitReason.STOP_LOSS, ExitUrgency.HIGH)
        }

        // 3. Full take profit
        if (effectiveTakeProfit > 0.0 && pnlPct >= effectiveTakeProfit) {
            return ExitDecision(true, 100, ExitReason.TAKE_PROFIT_FULL, ExitUrgency.MEDIUM)
        }

        // 4. Progressive trailing stop
        if (profile.progressiveTrailing && pnlPct > 10.0) {
            val trailingStopPrice = calculateProgressiveTrailingStop(
                currentPnlPct = pnlPct,
                baseTrailingPct = targets.trailingStopPct,
                highWaterMarkPrice = highWaterMarkPrice
            )
            if (trailingStopPrice > 0.0 && currentPrice <= trailingStopPrice) {
                return ExitDecision(true, 100, ExitReason.TRAILING_STOP, ExitUrgency.MEDIUM)
            }
        }

        // 5. Chunk sells
        if (profile.chunkSellEnabled) {
            if (alreadySoldPct < targets.chunk1SellPct && pnlPct >= targets.chunk1Pct) {
                return ExitDecision(true, targets.chunk1SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW)
            }

            if (
                alreadySoldPct < (targets.chunk1SellPct + targets.chunk2SellPct) &&
                pnlPct >= targets.chunk2Pct
            ) {
                return ExitDecision(true, targets.chunk2SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW)
            }
        }

        // 6. Time pressure exits
        if (timePressure == TimePressure.URGENT_EXIT) {
            return ExitDecision(true, 100, ExitReason.TIME_EXIT, ExitUrgency.HIGH)
        }

        if (timePressure == TimePressure.TAKE_PROFIT && pnlPct > 0.0) {
            return ExitDecision(true, 100, ExitReason.TIME_EXIT, ExitUrgency.MEDIUM)
        }

        // 7. Momentum death
        if (pnlPct > 5.0 && currentMomentum < -10.0) {
            return ExitDecision(true, 100, ExitReason.MOMENTUM_EXIT, ExitUrgency.MEDIUM)
        }

        return ExitDecision(false, 0, ExitReason.HOLD, ExitUrgency.NONE)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUICK HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Quick check if we should cut loss.
     * V5.2.11: Looser in first few minutes to survive meme-coin wicks.
     */
    fun shouldCutLoss(
        pnlPct: Double,
        baseStopPct: Double,
        momentum: Double,
        holdMinutes: Int,
    ): Boolean {
        val timeMultiplier = when {
            holdMinutes < 2 -> 1.4
            holdMinutes < 5 -> 1.2
            holdMinutes < 10 -> 1.0
            else -> 0.9
        }

        val momentumMultiplier = when {
            momentum < -15 -> 0.7
            momentum < -5 -> 0.85
            momentum > 10 -> 1.2
            else -> 1.0
        }

        val effectiveStop = baseStopPct * timeMultiplier * momentumMultiplier
        return pnlPct <= -effectiveStop
    }
}