package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState

/**
 * PrecisionExitLogic — V8 Smart Exit Decisions
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * Combines multiple signals for exit:
 * 1. Stop loss (5% default, configurable)
 * 2. Fast rug detection (>8% in <10 sec = instant exit)
 * 3. Buy pressure drop (buys dropping > 10%)
 * 4. Volume + price divergence (volume drops, price flat = distribution)
 * 5. Whale distribution detection (whale spikes + price stalls)
 */
object PrecisionExitLogic {
    
    // Stop loss thresholds
    private const val DEFAULT_STOP_LOSS_PCT = 5.0
    private const val FAST_RUG_DROP_PCT = 8.0
    private const val FAST_RUG_TIME_MS = 10_000L
    
    // V5.2 FIX: LOOSENED exit thresholds - was causing 6% win rate
    // Previous values were too aggressive for meme coin volatility
    private const val BUY_PRESSURE_DROP_PCT = 40.0  // Was 10% - way too sensitive
    private const val VOLUME_DROP_PCT = 60.0        // Was 30% - normal for memecoins
    private const val PRICE_FLAT_THRESHOLD = 0.5    // Was 1% - tighter flatness check
    
    data class ExitSignal(
        val shouldExit: Boolean,
        val reason: String,
        val urgency: Urgency,
        val details: String = "",
    )
    
    enum class Urgency {
        NONE,       // No exit needed
        LOW,        // Can wait for better exit
        MEDIUM,     // Should exit soon
        HIGH,       // Exit now
        CRITICAL,   // INSTANT exit (rug detected)
    }
    
    // V5.2 FIX: MINIMUM HOLD TIME PROTECTION
    // Prevents instant scratching of trades - gives HoldTimeAI time to learn
    // Only FAST_RUG can bypass this (truly catastrophic - >8% in <10s)
    private const val MIN_HOLD_TIME_MS = 30_000L  // 30 seconds minimum
    private const val MIN_HOLD_TIME_FOR_STOP_LOSS_MS = 60_000L  // 60s before stop loss active
    
    /**
     * Evaluate all exit conditions and return recommendation
     */
    fun evaluate(
        ts: TokenState,
        currentPrice: Double,
        entryPrice: Double,
        history: List<Candle>,
        exitScore: Double,
        stopLossPct: Double = DEFAULT_STOP_LOSS_PCT,
    ): ExitSignal {
        
        val pnlPct = if (entryPrice > 0) ((currentPrice - entryPrice) / entryPrice) * 100 else 0.0
        
        // ════════════════════════════════════════════════════════════════
        // V5.2 FIX: MINIMUM HOLD TIME - Calculate early for all checks
        // ════════════════════════════════════════════════════════════════
        val holdTimeMs = if (ts.position.entryTime > 0) {
            System.currentTimeMillis() - ts.position.entryTime
        } else {
            0L
        }
        val isInMinHoldPeriod = holdTimeMs < MIN_HOLD_TIME_MS
        val isInStopLossProtectionPeriod = holdTimeMs < MIN_HOLD_TIME_FOR_STOP_LOSS_MS
        
        // ════════════════════════════════════════════════════════════════
        // 1. FAST RUG DETECTION (CRITICAL) - ONLY exit that bypasses hold time
        // This is a true emergency - token is rugging hard and fast
        // ════════════════════════════════════════════════════════════════
        if (TradeStateMachine.checkFastRug(ts.mint, currentPrice)) {
            return ExitSignal(
                shouldExit = true,
                reason = "FAST_RUG",
                urgency = Urgency.CRITICAL,
                details = "Price dropped >8% in <10 seconds"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // V5.2 FIX: MINIMUM HOLD TIME PROTECTION
        // Don't exit within first 30s unless it's a rug (handled above)
        // This gives HoldTimeAI time to actually test strategies
        // ════════════════════════════════════════════════════════════════
        if (isInMinHoldPeriod) {
            // During min hold period, only exit for CATASTROPHIC losses (>10%)
            if (pnlPct <= -10.0) {
                return ExitSignal(
                    shouldExit = true,
                    reason = "CATASTROPHIC_LOSS",
                    urgency = Urgency.CRITICAL,
                    details = "PnL ${pnlPct.toInt()}% - emergency exit (min hold bypass)"
                )
            }
            // Otherwise, HOLD during minimum period - give trade time to breathe
            return ExitSignal(false, "MIN_HOLD", Urgency.NONE, "Hold period: ${holdTimeMs/1000}s/${MIN_HOLD_TIME_MS/1000}s")
        }
        
        // ════════════════════════════════════════════════════════════════
        // 2. STOP LOSS CHECK (with protection period)
        // V5.2 FIX: Require 60s before stop loss activates
        // Meme coins often wick down -3% to -5% then recover
        // ════════════════════════════════════════════════════════════════
        if (pnlPct <= -stopLossPct) {
            // Before 60s, only trigger stop loss on severe losses (>8%)
            if (isInStopLossProtectionPeriod && pnlPct > -8.0) {
                // Skip regular stop loss - still in protection period
                // Will continue to other checks, but likely return HOLD
            } else {
                return ExitSignal(
                    shouldExit = true,
                    reason = "STOP_LOSS",
                    urgency = Urgency.HIGH,
                    details = "PnL ${pnlPct.toInt()}% hit stop loss -${stopLossPct.toInt()}%"
                )
            }
        }
        
        // Need history for remaining checks
        if (history.size < 3) {
            return ExitSignal(false, "WAIT", Urgency.NONE, "Insufficient data")
        }
        
        // ════════════════════════════════════════════════════════════════
        // 2.5 DISTRIBUTION DETECTION (THE MONEY SIGNAL)
        // ════════════════════════════════════════════════════════════════
        val distribution = DistributionDetector.detect(
            mint = ts.mint,
            ts = ts,
            currentExitScore = exitScore,
            history = history,
        )
        
        // LOOSENED: Require higher confidence (was 70, now 80)
        // Also require position to be at least breakeven or losing to exit
        if (distribution.isDistributing && distribution.confidence >= 80 && pnlPct < 5.0) {
            return ExitSignal(
                shouldExit = true,
                reason = "DISTRIBUTION",
                urgency = Urgency.HIGH,
                details = "conf=${distribution.confidence}% | ${distribution.details}"
            )
        }
        
        val recentCandles = history.takeLast(3)
        val olderCandles = if (history.size >= 6) history.takeLast(6).take(3) else recentCandles
        
        // ════════════════════════════════════════════════════════════════
        // 3. BUY PRESSURE DROP
        // V5.2 FIX: Raised exitScore threshold from 60 to 80 - was too aggressive
        // ════════════════════════════════════════════════════════════════
        val recentBuys = recentCandles.sumOf { it.buysH1 }
        val olderBuys = olderCandles.sumOf { it.buysH1 }
        
        val buyDropPct = if (olderBuys > 0) {
            ((olderBuys - recentBuys).toDouble() / olderBuys) * 100
        } else 0.0
        
        // V5.2: Require exitScore > 80 AND buyDrop > 40% (was 60/10)
        if (exitScore > 80 && buyDropPct > BUY_PRESSURE_DROP_PCT) {
            return ExitSignal(
                shouldExit = true,
                reason = "BUY_PRESSURE_DROP",
                urgency = Urgency.HIGH,
                details = "Exit score ${exitScore.toInt()} + buys dropped ${buyDropPct.toInt()}%"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // 4. VOLUME DROP + PRICE FLAT (Distribution)
        // ════════════════════════════════════════════════════════════════
        val recentVol = recentCandles.sumOf { it.vol }
        val olderVol = olderCandles.sumOf { it.vol }
        
        val volDropPct = if (olderVol > 0) {
            ((olderVol - recentVol) / olderVol) * 100
        } else 0.0
        
        val recentPriceAvg = recentCandles.map { it.priceUsd }.average()
        val olderPriceAvg = olderCandles.map { it.priceUsd }.average()
        val priceChangePct = if (olderPriceAvg > 0) {
            kotlin.math.abs((recentPriceAvg - olderPriceAvg) / olderPriceAvg) * 100
        } else 0.0
        
        // LOOSENED: Also require position to be losing to exit on volume drop
        if (volDropPct > VOLUME_DROP_PCT && priceChangePct < PRICE_FLAT_THRESHOLD && pnlPct < 0.0) {
            return ExitSignal(
                shouldExit = true,
                reason = "DISTRIBUTION",
                urgency = Urgency.MEDIUM,
                details = "Volume dropped ${volDropPct.toInt()}%, price flat (${priceChangePct.toInt()}%), pnl=${pnlPct.toInt()}%"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // 5. WHALE DISTRIBUTION (whale activity + price stalls)
        // LOOSENED: Require much higher thresholds to avoid premature exits
        // ════════════════════════════════════════════════════════════════
        val whaleActivity = ts.meta.velocityScore  // Using velocityScore as proxy for whale activity
        val priceStalling = priceChangePct < 1.0 && ts.history.takeLast(7).let { candles ->
            if (candles.size >= 7) {
                val high = candles.maxOf { c -> c.priceUsd }
                val low = candles.minOf { c -> c.priceUsd }
                val range = if (low > 0) ((high - low) / low) * 100 else 0.0
                range < 1.0  // V5.2: Even tighter - 1% range required (was 2%)
            } else false
        }
        
        // V5.2 FIX: WHALE_DISTRIBUTION was causing too many premature exits
        // Raised thresholds significantly - meme coins naturally have whale activity
        // - Raised whaleActivity from 85 to 95 (near-certain distribution only)
        // - Lowered pnlPct trigger from -2% to -5% (only exit if already down big)
        if (whaleActivity > 95 && priceStalling && pnlPct < -5.0) {
            return ExitSignal(
                shouldExit = true,
                reason = "WHALE_DISTRIBUTION",
                urgency = Urgency.HIGH,
                details = "Whale activity ${whaleActivity.toInt()} + price stalling + pnl=${pnlPct.toInt()}%"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // 6. EXIT SCORE THRESHOLD (existing logic)
        // V5.2: Raised from 75 to 90 - was too aggressive
        // ════════════════════════════════════════════════════════════════
        if (exitScore > 90) {
            return ExitSignal(
                shouldExit = true,
                reason = "EXIT_SCORE",
                urgency = Urgency.MEDIUM,
                details = "Exit score ${exitScore.toInt()} > 90"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // 7. PROFIT PROTECTION (trailing stop concept)
        // ════════════════════════════════════════════════════════════════
        if (pnlPct > 20) {
            // If we're up 20%+, tighten stop to lock in profits
            val highSinceEntry = ts.history
                .filter { it.ts > ts.position.entryTime }
                .maxOfOrNull { it.priceUsd } ?: currentPrice
            
            val dropFromHigh = if (highSinceEntry > 0) {
                ((highSinceEntry - currentPrice) / highSinceEntry) * 100
            } else 0.0
            
            // If dropped >10% from high while we're in profit, exit
            if (dropFromHigh > 10.0) {
                return ExitSignal(
                    shouldExit = true,
                    reason = "PROFIT_PROTECTION",
                    urgency = Urgency.MEDIUM,
                    details = "Up ${pnlPct.toInt()}% but dropped ${dropFromHigh.toInt()}% from high"
                )
            }
        }
        
        // No exit signal
        return ExitSignal(false, "HOLD", Urgency.NONE)
    }
    
    /**
     * Quick check for instant exit conditions only
     * Called more frequently than full evaluate()
     */
    fun quickCheck(
        mint: String,
        currentPrice: Double,
        entryPrice: Double,
        stopLossPct: Double = DEFAULT_STOP_LOSS_PCT,
    ): ExitSignal? {
        
        // Fast rug check
        if (TradeStateMachine.checkFastRug(mint, currentPrice)) {
            return ExitSignal(true, "FAST_RUG", Urgency.CRITICAL, ">8% drop in <10s")
        }
        
        // Stop loss
        val pnlPct = if (entryPrice > 0) ((currentPrice - entryPrice) / entryPrice) * 100 else 0.0
        if (pnlPct <= -stopLossPct) {
            return ExitSignal(true, "STOP_LOSS", Urgency.HIGH, "PnL ${pnlPct.toInt()}%")
        }
        
        return null  // No urgent exit needed
    }
}
