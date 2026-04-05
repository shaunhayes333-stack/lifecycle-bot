package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Position
import com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI

/**
 * ModeSpecificExits — Intelligent Exit Logic Per Trade Type
 * 
 * V5.2 UPGRADE: Now integrates with HoldTimeOptimizerAI for ADAPTIVE timeouts!
 * Instead of hardcoded 15/60/120/240 minute timeouts, we now use AI-learned
 * optimal hold times based on setup quality, market regime, and historical data.
 * 
 * Each trade type has different exit characteristics:
 *   - Fresh Launch: Fastest stops, fastest partials, AI-learned short timeout
 *   - Breakout: Partial into strength, trail below structure, medium AI timeout
 *   - Reversal: Take first target quicker, breakeven early
 *   - Whale Follow: Exit when whale behavior changes, patient AI timeout
 *   - Trend Pullback: Widest patience, trail under higher lows, max AI timeout
 * 
 * The AI learns optimal hold times from trade outcomes and dynamically adjusts
 * timeouts based on setup quality (A+, A, B, C), volatility regime, and
 * historical performance patterns.
 */
object ModeSpecificExits {
    
    private const val TAG = "ModeExit"
    
    // ═══════════════════════════════════════════════════════════════════
    // V5.2: AI-POWERED TIMEOUT CONFIGURATION
    // Maps trade types to HoldTimeOptimizerAI setup quality grades
    // ═══════════════════════════════════════════════════════════════════
    
    private fun getSetupQualityForTradeType(tradeType: ModeRouter.TradeType): String {
        return when (tradeType) {
            ModeRouter.TradeType.FRESH_LAUNCH -> "C"       // Scalp/short - lowest quality expectation
            ModeRouter.TradeType.BREAKOUT_CONTINUATION -> "B"  // Medium hold
            ModeRouter.TradeType.REVERSAL_RECLAIM -> "B"       // Medium patience
            ModeRouter.TradeType.WHALE_ACCUMULATION -> "A"     // Patient hold
            ModeRouter.TradeType.COPY_TRADE -> "B"             // Medium patience
            ModeRouter.TradeType.GRADUATION -> "B"             // Event-based
            ModeRouter.TradeType.TREND_PULLBACK -> "A+"        // Maximum patience
            ModeRouter.TradeType.SENTIMENT_IGNITION -> "C"     // Quick in-out
            ModeRouter.TradeType.UNKNOWN -> "C"                // Default to short
        }
    }
    
    /**
     * V5.2: Get AI-learned optimal timeout for a trade type.
     * This replaces hardcoded timeout values with adaptive learning.
     */
    private fun getAIOptimalTimeout(
        ts: TokenState,
        tradeType: ModeRouter.TradeType,
        fallbackMinutes: Int = 60
    ): Int {
        return try {
            val setupQuality = getSetupQualityForTradeType(tradeType)
            val recommendation = HoldTimeOptimizerAI.predict(
                mint = ts.mint,
                symbol = ts.symbol,
                setupQuality = setupQuality,
                liquidityUsd = ts.lastLiquidityUsd,
                volatilityRegime = when {
                    ts.meta.avgAtr > 15 -> "HIGH"
                    ts.meta.avgAtr > 8 -> "NORMAL"
                    else -> "LOW"
                },
                marketRegime = ts.meta.emafanAlignment.let { ema ->
                    when {
                        ema.contains("BULL") -> "BULL"
                        ema.contains("BEAR") -> "BEAR"
                        else -> "NEUTRAL"
                    }
                },
                isGoldenHour = false,  // Can be enhanced later
                entryScore = ts.position.entryScore.toInt()
            )
            
            // Use maxMinutes for timeout (the "exit by" time)
            recommendation.maxMinutes.coerceIn(5, 480)
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "HoldTimeAI fallback: ${e.message}")
            fallbackMinutes
        }
    }
    
    /**
     * V5.2: Check if position is overheld according to AI.
     */
    private fun isAIOverheld(mint: String, holdTimeMins: Double): Boolean {
        return try {
            HoldTimeOptimizerAI.isOverheld(mint) || 
                holdTimeMins > HoldTimeOptimizerAI.getCurrentHoldMinutes(mint) * 2
        } catch (_: Exception) {
            false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // EXIT RECOMMENDATION
    // ═══════════════════════════════════════════════════════════════════
    
    data class ExitRecommendation(
        val shouldExit: Boolean,
        val exitPct: Double,           // 0-100 (what % of position to sell)
        val reason: String,
        val urgency: ExitUrgency,
        val adjustedStop: Double?,     // New stop price if trailing
        val adjustedTarget: Double?,   // New target if adjusting
    )
    
    enum class ExitUrgency {
        IMMEDIATE,    // Exit now, no waiting
        URGENT,       // Exit within next candle
        NORMAL,       // Exit when convenient
        PATIENCE,     // Hold for better exit
        TRAIL,        // Don't exit, just trail stop
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN EXIT LOGIC
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get exit recommendation based on trade type and current state.
     */
    fun getExitRecommendation(
        ts: TokenState,
        tradeType: ModeRouter.TradeType,
        currentPnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        return when (tradeType) {
            ModeRouter.TradeType.FRESH_LAUNCH -> evaluateFreshLaunchExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.BREAKOUT_CONTINUATION -> evaluateBreakoutExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.REVERSAL_RECLAIM -> evaluateReversalExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.WHALE_ACCUMULATION -> evaluateWhaleFollowExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.COPY_TRADE -> evaluateCopyTradeExit(ts, currentPnlPct, holdTimeMs)  // PRIORITY 5: Isolated exit
            ModeRouter.TradeType.GRADUATION -> evaluateGraduationExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.TREND_PULLBACK -> evaluateTrendPullbackExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.SENTIMENT_IGNITION -> evaluateSentimentExit(ts, currentPnlPct, holdTimeMs)
            ModeRouter.TradeType.UNKNOWN -> evaluateDefaultExit(ts, currentPnlPct, holdTimeMs)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // FRESH LAUNCH EXIT
    // Fastest stop, fastest partials, AI-learned short timeout
    // V5.2: Now uses HoldTimeOptimizerAI for adaptive timeout
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateFreshLaunchExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
        // V5.2: Get AI-learned timeout (fallback to 15 min for fresh launches)
        val aiTimeout = getAIOptimalTimeout(ts, ModeRouter.TradeType.FRESH_LAUNCH, fallbackMinutes = 15)
        
        // IMMEDIATE EXIT: Stop loss hit
        if (pnlPct < -25) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "FRESH_LAUNCH: Stop loss -25%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // IMMEDIATE EXIT: Liquidity drain detected
        if (ts.lastLiquidityUsd < 1500) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "FRESH_LAUNCH: Liquidity draining",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // V5.2: AI-ADAPTIVE TIMEOUT - Fresh launches use dynamic learned timeout
        if (holdTimeMins > aiTimeout || isAIOverheld(ts.mint, holdTimeMins)) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "FRESH_LAUNCH: AI Timeout ${holdTimeMins.toInt()}min > ${aiTimeout}min optimal",
                urgency = ExitUrgency.URGENT,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // PARTIAL at 50%+ gain
        if (pnlPct > 50) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 50.0,  // Take half
                reason = "FRESH_LAUNCH: Quick profit +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // PARTIAL at 30%+ with momentum fading
        val lastCandle = hist.lastOrNull()
        if (pnlPct > 30 && lastCandle != null && lastCandle.buyRatio < 0.45) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 60.0,
                reason = "FRESH_LAUNCH: Profit +${pnlPct.toInt()}% + momentum fading",
                urgency = ExitUrgency.URGENT,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Hold with trailing stop
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "FRESH_LAUNCH: Hold (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.TRAIL,
            adjustedStop = ts.position.entryPrice * 0.80,  // Trail at -20%
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // BREAKOUT EXIT
    // Partial into strength, trail below structure, AI-learned timeout
    // V5.2: Now uses HoldTimeOptimizerAI for adaptive timeout
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateBreakoutExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
        // V5.2: Get AI-learned timeout (fallback to 120 min for breakouts)
        val aiTimeout = getAIOptimalTimeout(ts, ModeRouter.TradeType.BREAKOUT_CONTINUATION, fallbackMinutes = 120)
        
        // Stop loss (tighter for breakouts - structure should hold)
        if (pnlPct < -12) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "BREAKOUT: Structure failed -12%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // V5.2: AI-ADAPTIVE TIMEOUT
        if (holdTimeMins > aiTimeout || isAIOverheld(ts.mint, holdTimeMins)) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "BREAKOUT: AI Timeout ${holdTimeMins.toInt()}min > ${aiTimeout}min optimal",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Scale out into strength
        if (pnlPct > 40) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 30.0,  // Small partial, let it run
                reason = "BREAKOUT: Scaling out +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = ts.position.entryPrice * 1.05,  // Move stop to profit
                adjustedTarget = null,
            )
        }
        
        // Trail stop below structure (higher lows)
        if (pnlPct > 15 && hist.size >= 3) {
            val prices = hist.takeLast(5).map { it.priceUsd }
            val recentLow = prices.minOrNull() ?: ts.position.entryPrice
            val trailStop = recentLow * 0.95
            
            return ExitRecommendation(
                shouldExit = false,
                exitPct = 0.0,
                reason = "BREAKOUT: Trailing below structure",
                urgency = ExitUrgency.TRAIL,
                adjustedStop = trailStop,
                adjustedTarget = null,
            )
        }
        
        // Hold
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "BREAKOUT: Hold (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.PATIENCE,
            adjustedStop = null,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // REVERSAL EXIT
    // Take first target quicker, move stop to breakeven early
    // V5.2: Now uses HoldTimeOptimizerAI for adaptive timeout
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateReversalExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
        // V5.2: Get AI-learned timeout (fallback to 60 min for reversals)
        val aiTimeout = getAIOptimalTimeout(ts, ModeRouter.TradeType.REVERSAL_RECLAIM, fallbackMinutes = 60)
        
        // Stop loss
        if (pnlPct < -15) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "REVERSAL: Stop -15%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Reversal failing (making new lows)
        if (hist.size >= 4) {
            val prices = hist.takeLast(4).map { it.priceUsd }
            val isNewLow = prices.last() < prices.dropLast(1).minOrNull() ?: Double.MAX_VALUE
            if (isNewLow && pnlPct < 5) {
                return ExitRecommendation(
                    shouldExit = true,
                    exitPct = 100.0,
                    reason = "REVERSAL: New low, reclaim failed",
                    urgency = ExitUrgency.URGENT,
                    adjustedStop = null,
                    adjustedTarget = null,
                )
            }
        }
        
        // V5.2: AI-ADAPTIVE TIMEOUT (don't overstay reversals)
        if (holdTimeMins > aiTimeout || isAIOverheld(ts.mint, holdTimeMins)) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "REVERSAL: AI Timeout ${holdTimeMins.toInt()}min > ${aiTimeout}min optimal",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Take first target quicker
        if (pnlPct > 30) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 50.0,
                reason = "REVERSAL: First target +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = ts.position.entryPrice * 1.02,  // Breakeven+
                adjustedTarget = null,
            )
        }
        
        // Move to breakeven early
        if (pnlPct > 12) {
            return ExitRecommendation(
                shouldExit = false,
                exitPct = 0.0,
                reason = "REVERSAL: Move to breakeven",
                urgency = ExitUrgency.TRAIL,
                adjustedStop = ts.position.entryPrice * 1.01,
                adjustedTarget = null,
            )
        }
        
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "REVERSAL: Hold (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.NORMAL,
            adjustedStop = null,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // WHALE FOLLOW EXIT
    // Exit when whale behavior changes OR accumulation band breaks
    // PRIORITY 2 FIX: Use actual band calculation, not hardcoded -18%
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateWhaleFollowExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        val prices = hist.map { it.priceUsd }
        val currentPrice = prices.lastOrNull() ?: 0.0
        
        // ═══════════════════════════════════════════════════════════════════
        // PRIORITY 2: ACTUAL ACCUMULATION BAND CALCULATION
        //
        // Instead of hardcoded -18%, calculate the actual accumulation floor
        // from entry-time price structure. Exit when price breaks BELOW the
        // established band, not just on arbitrary percentage.
        // ═══════════════════════════════════════════════════════════════════
        
        // Calculate accumulation band (same logic as entry)
        val accumulationFloor = if (prices.size >= 5) {
            prices.takeLast(8).sorted().take(3).average()
        } else {
            ts.position.entryPrice * 0.82  // Fallback to -18%
        }
        
        // BAND BREAK: Exit if price closes below accumulation floor
        val bandBreakPct = if (accumulationFloor > 0 && ts.position.entryPrice > 0) {
            ((accumulationFloor - ts.position.entryPrice) / ts.position.entryPrice) * 100
        } else -18.0
        
        // Allow some buffer before declaring band failure (2% below floor)
        val bandBreakThreshold = accumulationFloor * 0.98
        
        if (currentPrice < bandBreakThreshold && holdTimeMins > 2) {
            // Confirm band break (not just a wick)
            val last2BelowBand = prices.takeLast(2).all { it < bandBreakThreshold }
            
            if (last2BelowBand) {
                return ExitRecommendation(
                    shouldExit = true,
                    exitPct = 100.0,
                    reason = "WHALE_FOLLOW: Accumulation band failed (floor=${accumulationFloor.toBigDecimal().setScale(6, java.math.RoundingMode.HALF_UP)})",
                    urgency = ExitUrgency.IMMEDIATE,
                    adjustedStop = null,
                    adjustedTarget = null,
                )
            }
        }
        
        // HARD STOP: Exit on severe loss regardless of band
        if (pnlPct < -25) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "WHALE_FOLLOW: Hard stop loss ${pnlPct.toInt()}%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Check if whale behavior changed (selling instead of accumulating)
        try {
            val whaleSignal = WhaleDetector.evaluate(ts.mint, ts)
            
            // V5.9: Whales selling = exit, but give 3 min before firing.
            // Whales can temporarily pause without meaning they're exiting.
            // VDOR was exiting after 1 minute due to whale pause — too early.
            if (!whaleSignal.hasWhaleActivity && !whaleSignal.smartMoneyPresent && pnlPct > 5 && holdTimeMins >= 3.0) {
                return ExitRecommendation(
                    shouldExit = true,
                    exitPct = 70.0,
                    reason = "WHALE_FOLLOW: Whale activity stopped",
                    urgency = ExitUrgency.URGENT,
                    adjustedStop = null,
                    adjustedTarget = null,
                )
            }
        } catch (_: Exception) { }
        
        // Timeout (patient but not forever)
        if (holdTimeMins > 180) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "WHALE_FOLLOW: Timeout ${holdTimeMins.toInt()}min",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Partial on expansion
        if (pnlPct > 40) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 40.0,
                reason = "WHALE_FOLLOW: Partial on expansion +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = ts.position.entryPrice * 1.10,
                adjustedTarget = null,
            )
        }
        
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "WHALE_FOLLOW: Following whales (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.PATIENCE,
            adjustedStop = accumulationFloor,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // COPY TRADE EXIT
    // PRIORITY 5: Isolated exit logic for copy-trade positions
    // Different from WHALE_FOLLOW - we're following a wallet, not accumulation
    // V5.2: Now uses HoldTimeOptimizerAI for adaptive timeout
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateCopyTradeExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
        // V5.2: Get AI-learned timeout (fallback to 120 min for copy trades)
        val aiTimeout = getAIOptimalTimeout(ts, ModeRouter.TradeType.COPY_TRADE, fallbackMinutes = 120)
        
        // STOP LOSS: Tighter than whale-follow since we're following, not predicting
        if (pnlPct < -15) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "COPY_TRADE: Stop loss ${pnlPct.toInt()}%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // V5.2: AI-ADAPTIVE TIMEOUT - Medium patience
        if (holdTimeMins > aiTimeout || isAIOverheld(ts.mint, holdTimeMins)) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "COPY_TRADE: AI Timeout ${holdTimeMins.toInt()}min > ${aiTimeout}min optimal",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // MOMENTUM FADE: If buy pressure drops significantly, leader may be exiting
        val lastCandle = hist.lastOrNull()
        if (pnlPct > 10 && lastCandle != null && lastCandle.buyRatio < 0.35) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 60.0,
                reason = "COPY_TRADE: Leader likely exiting (buy% dropping)",
                urgency = ExitUrgency.URGENT,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // TAKE PROFIT: When we hit target, take partial
        if (pnlPct > 30) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 50.0,
                reason = "COPY_TRADE: Partial profit +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = ts.position.entryPrice * 1.10,  // Trail at breakeven+
                adjustedTarget = null,
            )
        }
        
        // HOLD: Keep following the leader
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "COPY_TRADE: Following leader (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.PATIENCE,
            adjustedStop = ts.position.entryPrice * 0.85,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // GRADUATION EXIT
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateGraduationExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        
        // Stop loss (wider for event volatility)
        if (pnlPct < -20) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "GRADUATION: Stop -20%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Post-graduation collapse
        if (ts.lastLiquidityUsd < 3000) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "GRADUATION: Post-grad liquidity collapse",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Timeout
        if (holdTimeMins > 90) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "GRADUATION: Timeout",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Take profit
        if (pnlPct > 35) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 50.0,
                reason = "GRADUATION: Take profit +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = ts.position.entryPrice * 1.05,
                adjustedTarget = null,
            )
        }
        
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "GRADUATION: Hold (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.NORMAL,
            adjustedStop = null,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // TREND PULLBACK EXIT
    // Widest patience, tightest stop relative to structure
    // V5.2: Now uses HoldTimeOptimizerAI for adaptive timeout (A+ setups!)
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateTrendPullbackExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
        // V5.2: Get AI-learned timeout (fallback to 240 min for trend pullbacks - widest patience)
        val aiTimeout = getAIOptimalTimeout(ts, ModeRouter.TradeType.TREND_PULLBACK, fallbackMinutes = 240)
        
        // Tightest stop (trend structure should hold)
        if (pnlPct < -10) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "TREND_PULLBACK: Trend broken -10%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Trend reversal (lower low)
        if (hist.size >= 6) {
            val prices = hist.map { it.priceUsd }
            val recentLows = prices.chunked(3).map { it.minOrNull() ?: 0.0 }
            val lowerLow = recentLows.size >= 2 && recentLows.last() < recentLows.dropLast(1).last() * 0.95
            
            if (lowerLow) {
                return ExitRecommendation(
                    shouldExit = true,
                    exitPct = 100.0,
                    reason = "TREND_PULLBACK: Trend reversal (lower low)",
                    urgency = ExitUrgency.URGENT,
                    adjustedStop = null,
                    adjustedTarget = null,
                )
            }
        }
        
        // V5.2: AI-ADAPTIVE TIMEOUT - Very patient but AI-learned limits
        if (holdTimeMins > aiTimeout || isAIOverheld(ts.mint, holdTimeMins)) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "TREND_PULLBACK: AI Max hold ${holdTimeMins.toInt()}min > ${aiTimeout}min optimal",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Scale out into strength
        if (pnlPct > 25) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 25.0,  // Small partial, trends can run far
                reason = "TREND_PULLBACK: Scaling +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Trail under higher lows
        if (pnlPct > 8 && hist.size >= 4) {
            val prices = hist.takeLast(6).map { it.priceUsd }
            val recentLow = prices.minOrNull() ?: ts.position.entryPrice
            
            return ExitRecommendation(
                shouldExit = false,
                exitPct = 0.0,
                reason = "TREND_PULLBACK: Trail under higher lows",
                urgency = ExitUrgency.TRAIL,
                adjustedStop = recentLow * 0.97,
                adjustedTarget = null,
            )
        }
        
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "TREND_PULLBACK: Hold trend (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.PATIENCE,
            adjustedStop = null,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SENTIMENT EXIT
    // Narrative fades fast
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateSentimentExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
        // Stop loss
        if (pnlPct < -20) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "SENTIMENT: Stop -20%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Sentiment fading (buy ratio dropping)
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.buyRatio < 0.40 && pnlPct > 0) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 70.0,
                reason = "SENTIMENT: Narrative fading",
                urgency = ExitUrgency.URGENT,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Short timeout (narratives die fast)
        if (holdTimeMins > 45) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "SENTIMENT: Timeout",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // Take profit on spike
        if (pnlPct > 40) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 60.0,
                reason = "SENTIMENT: Profit on spike +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = ts.position.entryPrice * 1.08,
                adjustedTarget = null,
            )
        }
        
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "SENTIMENT: Riding narrative (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.NORMAL,
            adjustedStop = null,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // DEFAULT EXIT
    // V5.2: Now uses HoldTimeOptimizerAI for adaptive timeout
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateDefaultExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        
        // V5.2: Get AI-learned timeout (fallback to 60 min for unknown types)
        val aiTimeout = getAIOptimalTimeout(ts, ModeRouter.TradeType.UNKNOWN, fallbackMinutes = 60)
        
        if (pnlPct < -15) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "DEFAULT: Stop -15%",
                urgency = ExitUrgency.IMMEDIATE,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        // V5.2: AI-ADAPTIVE TIMEOUT
        if (holdTimeMins > aiTimeout || isAIOverheld(ts.mint, holdTimeMins)) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "DEFAULT: AI Timeout ${holdTimeMins.toInt()}min > ${aiTimeout}min optimal",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        if (pnlPct > 25) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 50.0,
                reason = "DEFAULT: Take profit +${pnlPct.toInt()}%",
                urgency = ExitUrgency.NORMAL,
                adjustedStop = null,
                adjustedTarget = null,
            )
        }
        
        return ExitRecommendation(
            shouldExit = false,
            exitPct = 0.0,
            reason = "DEFAULT: Hold (${pnlPct.toInt()}%)",
            urgency = ExitUrgency.NORMAL,
            adjustedStop = null,
            adjustedTarget = null,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LOGGING
    // ═══════════════════════════════════════════════════════════════════
    
    fun logExitRecommendation(ts: TokenState, tradeType: ModeRouter.TradeType, rec: ExitRecommendation) {
        if (rec.shouldExit) {
            ErrorLogger.info(TAG, "${tradeType.emoji} ${ts.symbol}: ${rec.urgency} EXIT ${rec.exitPct.toInt()}% | ${rec.reason}")
            
            // ═══════════════════════════════════════════════════════════════════
            // V3 MIGRATION: Mode invalidation is now a PENALTY SIGNAL, not a kill switch
            // 
            // Old behavior: recordModeInvalidation() → RAW_SUPPRESSED → return@launch
            // New behavior: recordModeInvalidation() → penalty points → V3 scorer evaluates
            // 
            // The invalidation still gets recorded (for tracking), but V3 will
            // convert it to a penalty instead of a hard block. See:
            //   - DistributionFadeAvoider.getSuppressionPenalty()
            //   - BotService.kt V3 processing section
            // ═══════════════════════════════════════════════════════════════════
            val invalidationReasons = listOf(
                "Accumulation band failed",
                "Stop loss",
                "Liquidity draining",
                "reclaim failed",
                "Distribution detected",
                "Whale activity stopped",
            )
            
            val isInvalidation = invalidationReasons.any { reason -> 
                rec.reason.contains(reason, ignoreCase = true) 
            }
            
            if (isInvalidation && rec.urgency in listOf(ExitUrgency.IMMEDIATE, ExitUrgency.URGENT)) {
                // Record invalidation - V3 will use getSuppressionPenalty() to convert to score penalty
                // Reduced cooldown from 2 minutes to 1 minute since V3 uses penalties not blocks
                DistributionFadeAvoider.recordModeInvalidation(
                    mint = ts.mint,
                    reason = "${tradeType.name}_INVALIDATION: ${rec.reason}",
                    durationMs = 60_000L  // Reduced: V3 handles via penalty, not hard block
                )
                
                // Log for V3 comparison tracking
                ErrorLogger.info(TAG, "📊 V3: ${ts.symbol} invalidation recorded → penalty signal")
                
                // Also record in ReentryRecoveryMode for stricter re-entry rules
                ReentryRecoveryMode.recordFailure(ts, rec.reason)
            }
        }
    }
}
