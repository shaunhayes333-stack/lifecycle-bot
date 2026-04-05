package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import kotlin.math.max

/**
 * ExitManager — Priority-based exit decision engine.
 *
 * Centralizes exit logic with clear priority ordering:
 * 1. CRITICAL: Stop loss, rug detected, liquidity gone
 * 2. HIGH: Trailing stop hit, max hold time
 * 3. NORMAL: Exit score threshold, profit targets
 * 4. LOW: Optimization exits, rebalancing
 *
 * DEFENSIVE DESIGN:
 * - Stateless
 * - Safe fallbacks
 * - Guards against bad inputs
 */
object ExitManager {

    private const val TAG = "ExitManager"
    private const val MIN_SAFE_LIQUIDITY_USD = 1_000.0

    /**
     * Exit priority levels.
     */
    enum class ExitPriority {
        CRITICAL,
        HIGH,
        NORMAL,
        LOW,
        HOLD,
    }

    /**
     * Exit reason categories.
     */
    enum class ExitReason {
        // Critical
        STOP_LOSS,
        RUG_DETECTED,
        LIQUIDITY_DRIED,
        DISTRIBUTION_DETECTED,

        // High
        TRAILING_STOP,
        MAX_HOLD_TIME,
        MOMENTUM_COLLAPSE,

        // Normal
        EXIT_SCORE_THRESHOLD,
        PROFIT_TARGET,
        PARTIAL_TAKE_PROFIT,

        // Low
        REBALANCE,
        OPPORTUNITY_COST,

        // Hold
        NONE,
    }

    /**
     * Exit decision result.
     */
    data class ExitDecision(
        val shouldExit: Boolean,
        val priority: ExitPriority,
        val reason: ExitReason,
        val details: String,
        val sellFraction: Double = 1.0, // 1.0 = full sell, 0.5 = half, etc.
        val urgency: Int = 0,           // Higher = more urgent
    ) {
        companion object {
            val HOLD = ExitDecision(
                shouldExit = false,
                priority = ExitPriority.HOLD,
                reason = ExitReason.NONE,
                details = "No exit conditions met",
                sellFraction = 0.0,
                urgency = 0,
            )
        }
    }

    /**
     * Evaluate all exit conditions and return highest priority decision.
     */
    fun evaluate(
        currentPriceUsd: Double,
        entryPriceUsd: Double,
        highPriceUsd: Double,
        pnlPct: Double,
        exitScore: Double,
        holdTimeMs: Long,
        liquidityUsd: Double,
        isRugDetected: Boolean = false,
        isDistributionDetected: Boolean = false,
        config: BotConfig,
        modeMultipliers: ModeSpecificGates.ModeMultipliers = ModeSpecificGates.ModeMultipliers.DEFAULT,
    ): ExitDecision {
        return try {
            if (entryPriceUsd <= 0.0 || currentPriceUsd <= 0.0 || highPriceUsd <= 0.0) {
                ErrorLogger.warn(
                    TAG,
                    "Invalid price input | entry=$entryPriceUsd current=$currentPriceUsd high=$highPriceUsd"
                )
                return ExitDecision.HOLD
            }

            val decisions = mutableListOf<ExitDecision>()

            // ═══════════════════════════════════════════════════════════════════
            // CRITICAL EXITS
            // ═══════════════════════════════════════════════════════════════════

            val stopLossPct = (config.stopLossPct * modeMultipliers.stopLossMultiplier).coerceAtLeast(0.1)
            if (pnlPct <= -stopLossPct) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.CRITICAL,
                        reason = ExitReason.STOP_LOSS,
                        details = "Stop loss hit at ${pnlPct.toInt()}% (threshold: -${stopLossPct.toInt()}%)",
                        sellFraction = 1.0,
                        urgency = 100,
                    )
                )
            }

            if (isRugDetected) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.CRITICAL,
                        reason = ExitReason.RUG_DETECTED,
                        details = "Rug pull detected - emergency exit",
                        sellFraction = 1.0,
                        urgency = 99,
                    )
                )
            }

            if (liquidityUsd in 0.0..<MIN_SAFE_LIQUIDITY_USD) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.CRITICAL,
                        reason = ExitReason.LIQUIDITY_DRIED,
                        details = "Liquidity too low: $${liquidityUsd.toInt()}",
                        sellFraction = 1.0,
                        urgency = 98,
                    )
                )
            }

            if (isDistributionDetected && pnlPct > 0.0) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.CRITICAL,
                        reason = ExitReason.DISTRIBUTION_DETECTED,
                        details = "Distribution pattern detected - securing gains",
                        sellFraction = 1.0,
                        urgency = 97,
                    )
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // HIGH PRIORITY EXITS
            // ═══════════════════════════════════════════════════════════════════

            val trailingResult = TrailingStopManager.calculateAdaptiveStop(
                currentPriceUsd = currentPriceUsd,
                highPriceUsd = highPriceUsd,
                entryPriceUsd = entryPriceUsd,
                pnlPct = pnlPct,
                holdTimeMs = holdTimeMs,
                config = config,
                modeMultiplier = modeMultipliers.trailingStopMultiplier,
            )

            if (TrailingStopManager.isStopHit(currentPriceUsd, trailingResult.stopPriceUsd)) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.HIGH,
                        reason = ExitReason.TRAILING_STOP,
                        details = TrailingStopManager.getStopHitReason(
                            currentPriceUsd = currentPriceUsd,
                            stopPriceUsd = trailingResult.stopPriceUsd,
                            highPriceUsd = highPriceUsd,
                            entryPriceUsd = entryPriceUsd,
                        ),
                        sellFraction = 1.0,
                        urgency = 80,
                    )
                )
            }

            val maxHoldMs = max(
                60_000L,
                (config.maxHoldMinsHard * 60_000L * modeMultipliers.maxHoldMultiplier).toLong()
            )
            if (holdTimeMs >= maxHoldMs && pnlPct > -5.0) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.HIGH,
                        reason = ExitReason.MAX_HOLD_TIME,
                        details = "Max hold time reached: ${holdTimeMs / 60_000}min",
                        sellFraction = 1.0,
                        urgency = 70,
                    )
                )
            }

            if (pnlPct > 5.0 && exitScore >= 40.0 && liquidityUsd < MIN_SAFE_LIQUIDITY_USD * 2.0) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.HIGH,
                        reason = ExitReason.MOMENTUM_COLLAPSE,
                        details = "Momentum/liquidity deterioration while in profit",
                        sellFraction = 1.0,
                        urgency = 65,
                    )
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // NORMAL EXITS
            // ═══════════════════════════════════════════════════════════════════

            val exitThreshold = 60.0 * modeMultipliers.exitScoreMultiplier
            if (exitScore >= exitThreshold && pnlPct > 0.0) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.NORMAL,
                        reason = ExitReason.EXIT_SCORE_THRESHOLD,
                        details = "Exit score ${exitScore.toInt()} >= threshold ${exitThreshold.toInt()}",
                        sellFraction = 1.0,
                        urgency = 50,
                    )
                )
            }

            if (config.partialSellEnabled) {
                val safePartialFraction = config.partialSellFraction.coerceIn(0.05, 1.0)

                if (pnlPct >= 100.0) {
                    decisions.add(
                        ExitDecision(
                            shouldExit = true,
                            priority = ExitPriority.NORMAL,
                            reason = ExitReason.PARTIAL_TAKE_PROFIT,
                            details = "Profit target 100%+ hit - partial sell",
                            sellFraction = safePartialFraction,
                            urgency = 40,
                        )
                    )
                } else if (pnlPct >= 50.0) {
                    decisions.add(
                        ExitDecision(
                            shouldExit = true,
                            priority = ExitPriority.NORMAL,
                            reason = ExitReason.PARTIAL_TAKE_PROFIT,
                            details = "Profit target 50%+ hit - partial sell",
                            sellFraction = (safePartialFraction * 0.5).coerceIn(0.05, 1.0),
                            urgency = 35,
                        )
                    )
                }
            } else if (pnlPct >= 100.0) {
                decisions.add(
                    ExitDecision(
                        shouldExit = true,
                        priority = ExitPriority.NORMAL,
                        reason = ExitReason.PROFIT_TARGET,
                        details = "Profit target 100%+ hit - full exit",
                        sellFraction = 1.0,
                        urgency = 38,
                    )
                )
            }

            if (decisions.isEmpty()) {
                ExitDecision.HOLD
            } else {
                decisions
                    .map { it.copy(sellFraction = it.sellFraction.coerceIn(0.0, 1.0)) }
                    .sortedWith(
                        compareBy<ExitDecision> { priorityRank(it.priority) }
                            .thenByDescending { it.urgency }
                    )
                    .first()
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "evaluate error: ${e.message}")
            ExitDecision.HOLD
        }
    }

    /**
     * Quick check if any critical exit condition exists.
     */
    fun hasCriticalCondition(
        pnlPct: Double,
        stopLossPct: Double,
        isRugDetected: Boolean,
        liquidityUsd: Double,
    ): Boolean {
        return try {
            pnlPct <= -stopLossPct.coerceAtLeast(0.1) ||
                isRugDetected ||
                liquidityUsd < MIN_SAFE_LIQUIDITY_USD
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if position should be partially sold.
     *
     * @param pnlPct Current PnL percentage
     * @param partialSellCount How many partial sells already done
     * @param config Bot configuration
     * @return Pair of (shouldPartialSell, sellFraction)
     */
    fun shouldPartialSell(
        pnlPct: Double,
        partialSellCount: Int,
        config: BotConfig,
    ): Pair<Boolean, Double> {
        return try {
            if (!config.partialSellEnabled) {
                return Pair(false, 0.0)
            }

            val milestones = listOf(
                50.0 to 0.15,
                100.0 to 0.20,
                200.0 to 0.25,
                500.0 to 0.30,
            )

            val milestone = milestones.getOrNull(partialSellCount)
            if (milestone != null && pnlPct >= milestone.first) {
                Pair(true, milestone.second.coerceIn(0.05, 1.0))
            } else {
                Pair(false, 0.0)
            }
        } catch (_: Exception) {
            Pair(false, 0.0)
        }
    }

    /**
     * Get exit reason as emoji + text for display.
     */
    fun getExitReasonDisplay(reason: ExitReason): String {
        return when (reason) {
            ExitReason.STOP_LOSS -> "🛑 Stop Loss"
            ExitReason.RUG_DETECTED -> "🚨 Rug Detected"
            ExitReason.LIQUIDITY_DRIED -> "💧 No Liquidity"
            ExitReason.DISTRIBUTION_DETECTED -> "📉 Distribution"
            ExitReason.TRAILING_STOP -> "📊 Trailing Stop"
            ExitReason.MAX_HOLD_TIME -> "⏰ Max Hold"
            ExitReason.MOMENTUM_COLLAPSE -> "📉 Momentum Lost"
            ExitReason.EXIT_SCORE_THRESHOLD -> "🎯 Exit Signal"
            ExitReason.PROFIT_TARGET -> "💰 Profit Target"
            ExitReason.PARTIAL_TAKE_PROFIT -> "💵 Partial Profit"
            ExitReason.REBALANCE -> "⚖️ Rebalance"
            ExitReason.OPPORTUNITY_COST -> "🔄 Opportunity"
            ExitReason.NONE -> "✅ Holding"
        }
    }

    private fun priorityRank(priority: ExitPriority): Int {
        return when (priority) {
            ExitPriority.CRITICAL -> 0
            ExitPriority.HIGH -> 1
            ExitPriority.NORMAL -> 2
            ExitPriority.LOW -> 3
            ExitPriority.HOLD -> 4
        }
    }
}