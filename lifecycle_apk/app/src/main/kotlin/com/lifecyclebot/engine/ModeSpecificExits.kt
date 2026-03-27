package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Position

/**
 * ModeSpecificExits — Intelligent Exit Logic Per Trade Type
 * 
 * Each trade type has different exit characteristics:
 *   - Fresh Launch: Fastest stops, fastest partials, short timeout
 *   - Breakout: Partial into strength, trail below structure
 *   - Reversal: Take first target quicker, breakeven early
 *   - Whale Follow: Exit when whale behavior changes
 *   - Trend Pullback: Widest patience, trail under higher lows
 * 
 * This replaces one-size-fits-all exit logic with mode-aware decisions.
 */
object ModeSpecificExits {
    
    private const val TAG = "ModeExit"
    
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
    // Fastest stop, fastest partials, short timeout
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateFreshLaunchExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
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
        
        // TIMEOUT: Fresh launches should be fast
        if (holdTimeMins > 15) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "FRESH_LAUNCH: Timeout ${holdTimeMins.toInt()}min",
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
    // Partial into strength, trail below structure, allow longer hold
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateBreakoutExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
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
        
        // Timeout
        if (holdTimeMins > 120) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "BREAKOUT: Timeout ${holdTimeMins.toInt()}min",
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
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateReversalExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
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
        
        // Timeout (don't overstay reversals)
        if (holdTimeMins > 60) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "REVERSAL: Timeout ${holdTimeMins.toInt()}min",
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
            
            // Whales selling = exit (but only if we're in profit)
            if (!whaleSignal.hasWhaleActivity && !whaleSignal.smartMoneyPresent && pnlPct > 5) {
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
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateCopyTradeExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
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
        
        // TIMEOUT: Medium patience - if the leader is still holding, we should know
        if (holdTimeMins > 120) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "COPY_TRADE: Timeout ${holdTimeMins.toInt()}min",
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
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateTrendPullbackExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        val hist = ts.history.toList()
        
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
        
        // Very patient timeout
        if (holdTimeMins > 240) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "TREND_PULLBACK: Max hold time",
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
    // ═══════════════════════════════════════════════════════════════════
    
    private fun evaluateDefaultExit(
        ts: TokenState,
        pnlPct: Double,
        holdTimeMs: Long,
    ): ExitRecommendation {
        
        val holdTimeMins = holdTimeMs / 60_000.0
        
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
        
        if (holdTimeMins > 60) {
            return ExitRecommendation(
                shouldExit = true,
                exitPct = 100.0,
                reason = "DEFAULT: Timeout",
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
            // PRIORITY 4: Trigger raw strategy suppression on mode invalidation
            // 
            // When a mode exit fires due to invalidation (not profit-taking),
            // suppress raw strategy BUY signals for this token temporarily.
            // This prevents the "BUY spam after stop-loss" problem.
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
                // Suppress raw strategy for 2 minutes after invalidation
                DistributionFadeAvoider.recordModeInvalidation(
                    mint = ts.mint,
                    reason = "${tradeType.name}_INVALIDATION: ${rec.reason}",
                    durationMs = 120_000L
                )
                
                // Also record in ReentryRecoveryMode for stricter re-entry rules
                ReentryRecoveryMode.recordFailure(ts, rec.reason)
            }
        }
    }
}
