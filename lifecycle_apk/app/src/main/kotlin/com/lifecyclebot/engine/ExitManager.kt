package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig

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
 * - All methods are static and stateless
 * - All decisions have safe fallbacks
 * - Try/catch wrapping for ultimate safety
 */
object ExitManager {
    
    private const val TAG = "ExitManager"
    
    /**
     * Exit priority levels.
     */
    enum class ExitPriority {
        CRITICAL,  // Must exit immediately (stop loss, rug, etc.)
        HIGH,      // Should exit soon (trailing stop, max hold)
        NORMAL,    // Standard exit conditions met
        LOW,       // Optional/optimization exits
        HOLD,      // No exit conditions met
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
        val sellFraction: Double = 1.0,  // 1.0 = full sell, 0.5 = half, etc.
        val urgency: Int = 0,            // Higher = more urgent (for ordering)
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
     * 
     * @param currentPriceUsd Current price
     * @param entryPriceUsd Entry price
     * @param highPriceUsd Highest price since entry
     * @param pnlPct Current PnL percentage
     * @param exitScore Exit score from strategy
     * @param holdTimeMs Time since entry
     * @param liquidityUsd Current liquidity
     * @param isRugDetected Whether rug pull detected
     * @param isDistributionDetected Whether distribution detected
     * @param config Bot configuration
     * @param modeMultipliers Mode-specific multipliers
     * @return ExitDecision with highest priority exit, or HOLD
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
            val decisions = mutableListOf<ExitDecision>()
            
            // ═══════════════════════════════════════════════════════════════════
            // CRITICAL EXITS (always check first, cannot be overridden)
            // ═══════════════════════════════════════════════════════════════════
            
            // Stop loss
            val stopLossPct = config.stopLossPct * modeMultipliers.stopLossMultiplier
            if (pnlPct <= -stopLossPct) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.CRITICAL,
                    reason = ExitReason.STOP_LOSS,
                    details = "Stop loss hit at ${pnlPct.toInt()}% (threshold: -${stopLossPct.toInt()}%)",
                    urgency = 100,
                ))
            }
            
            // Rug detected
            if (isRugDetected) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.CRITICAL,
                    reason = ExitReason.RUG_DETECTED,
                    details = "Rug pull detected - emergency exit",
                    urgency = 99,
                ))
            }
            
            // Liquidity dried up
            if (liquidityUsd < 1000) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.CRITICAL,
                    reason = ExitReason.LIQUIDITY_DRIED,
                    details = "Liquidity too low: $${liquidityUsd.toInt()}",
                    urgency = 98,
                ))
            }
            
            // Distribution detected
            if (isDistributionDetected && pnlPct > 0) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.CRITICAL,
                    reason = ExitReason.DISTRIBUTION_DETECTED,
                    details = "Distribution pattern detected - securing gains",
                    urgency = 97,
                ))
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // HIGH PRIORITY EXITS
            // ═══════════════════════════════════════════════════════════════════
            
            // Trailing stop (calculated separately)
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
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.HIGH,
                    reason = ExitReason.TRAILING_STOP,
                    details = TrailingStopManager.getStopHitReason(
                        currentPriceUsd, trailingResult.stopPriceUsd, highPriceUsd, entryPriceUsd
                    ),
                    urgency = 80,
                ))
            }
            
            // Max hold time
            val maxHoldMs = (config.maxHoldMinsHard * 60_000 * modeMultipliers.maxHoldMultiplier).toLong()
            if (holdTimeMs >= maxHoldMs && pnlPct > -5) {  // Don't force exit at big loss
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.HIGH,
                    reason = ExitReason.MAX_HOLD_TIME,
                    details = "Max hold time reached: ${holdTimeMs / 60_000}min",
                    urgency = 70,
                ))
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // NORMAL EXITS
            // ═══════════════════════════════════════════════════════════════════
            
            // Exit score threshold
            val exitThreshold = 60.0 * modeMultipliers.exitScoreMultiplier
            if (exitScore >= exitThreshold && pnlPct > 0) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.NORMAL,
                    reason = ExitReason.EXIT_SCORE_THRESHOLD,
                    details = "Exit score ${exitScore.toInt()} >= threshold ${exitThreshold.toInt()}",
                    urgency = 50,
                ))
            }
            
            // Profit target (partial sells)
            if (pnlPct >= 100 && config.partialSellEnabled) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.NORMAL,
                    reason = ExitReason.PARTIAL_TAKE_PROFIT,
                    details = "Profit target 100%+ hit - partial sell",
                    sellFraction = config.partialSellFraction,
                    urgency = 40,
                ))
            } else if (pnlPct >= 50 && config.partialSellEnabled) {
                decisions.add(ExitDecision(
                    shouldExit = true,
                    priority = ExitPriority.NORMAL,
                    reason = ExitReason.PARTIAL_TAKE_PROFIT,
                    details = "Profit target 50%+ hit - partial sell",
                    sellFraction = config.partialSellFraction * 0.5,
                    urgency = 35,
                ))
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // RETURN HIGHEST PRIORITY DECISION
            // ═══════════════════════════════════════════════════════════════════
            
            if (decisions.isEmpty()) {
                return ExitDecision.HOLD
            }
            
            // Sort by priority (CRITICAL first) then urgency
            decisions.sortedWith(
                compareBy({ it.priority.ordinal }, { -it.urgency })
            ).first()
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "evaluate error: ${e.message}")
            ExitDecision.HOLD
        }
    }
    
    /**
     * Quick check if any critical exit condition exists.
     * Use this for fast-path checks before full evaluation.
     */
    fun hasCriticalCondition(
        pnlPct: Double,
        stopLossPct: Double,
        isRugDetected: Boolean,
        liquidityUsd: Double,
    ): Boolean {
        return try {
            pnlPct <= -stopLossPct || isRugDetected || liquidityUsd < 1000
        } catch (e: Exception) {
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
            
            // Milestone-based partial sells
            val milestones = listOf(
                50.0 to 0.15,   // At 50%: sell 15%
                100.0 to 0.20, // At 100%: sell 20%
                200.0 to 0.25, // At 200%: sell 25%
                500.0 to 0.30, // At 500%: sell 30%
            )
            
            // Find highest milestone we've reached but haven't sold yet
            val milestone = milestones.getOrNull(partialSellCount)
            
            if (milestone != null && pnlPct >= milestone.first) {
                Pair(true, milestone.second)
            } else {
                Pair(false, 0.0)
            }
        } catch (e: Exception) {
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
}
