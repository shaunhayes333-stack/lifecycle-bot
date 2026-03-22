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
    
    // Exit signal thresholds
    private const val BUY_PRESSURE_DROP_PCT = 10.0  // If buys drop >10%
    private const val VOLUME_DROP_PCT = 30.0        // Volume drops >30%
    private const val PRICE_FLAT_THRESHOLD = 1.0    // Price change <1%
    
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
        // 1. FAST RUG DETECTION (CRITICAL)
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
        // 2. STOP LOSS CHECK
        // ════════════════════════════════════════════════════════════════
        if (pnlPct <= -stopLossPct) {
            return ExitSignal(
                shouldExit = true,
                reason = "STOP_LOSS",
                urgency = Urgency.HIGH,
                details = "PnL ${pnlPct.toInt()}% hit stop loss -${stopLossPct.toInt()}%"
            )
        }
        
        // Need history for remaining checks
        if (history.size < 3) {
            return ExitSignal(false, "WAIT", Urgency.NONE, "Insufficient data")
        }
        
        val recentCandles = history.takeLast(3)
        val olderCandles = if (history.size >= 6) history.takeLast(6).take(3) else recentCandles
        
        // ════════════════════════════════════════════════════════════════
        // 3. BUY PRESSURE DROP
        // ════════════════════════════════════════════════════════════════
        val recentBuys = recentCandles.sumOf { it.buysH1 }
        val olderBuys = olderCandles.sumOf { it.buysH1 }
        
        val buyDropPct = if (olderBuys > 0) {
            ((olderBuys - recentBuys).toDouble() / olderBuys) * 100
        } else 0.0
        
        if (exitScore > 60 && buyDropPct > BUY_PRESSURE_DROP_PCT) {
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
        
        if (volDropPct > VOLUME_DROP_PCT && priceChangePct < PRICE_FLAT_THRESHOLD) {
            return ExitSignal(
                shouldExit = true,
                reason = "DISTRIBUTION",
                urgency = Urgency.MEDIUM,
                details = "Volume dropped ${volDropPct.toInt()}%, price flat (${priceChangePct.toInt()}%)"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // 5. WHALE DISTRIBUTION (whale activity + price stalls)
        // ════════════════════════════════════════════════════════════════
        val whaleActivity = ts.meta.velocityScore  // Using velocityScore as proxy for whale activity
        val priceStalling = priceChangePct < 2.0 && ts.history.takeLast(5).let { candles ->
            if (candles.size >= 5) {
                val high = candles.maxOf { c -> c.priceUsd }
                val low = candles.minOf { c -> c.priceUsd }
                val range = if (low > 0) ((high - low) / low) * 100 else 0.0
                range < 3.0  // Price stuck in tight range
            } else false
        }
        
        if (whaleActivity > 70 && priceStalling) {
            return ExitSignal(
                shouldExit = true,
                reason = "WHALE_DISTRIBUTION",
                urgency = Urgency.HIGH,
                details = "Whale activity ${whaleActivity.toInt()} + price stalling"
            )
        }
        
        // ════════════════════════════════════════════════════════════════
        // 6. EXIT SCORE THRESHOLD (existing logic)
        // ════════════════════════════════════════════════════════════════
        if (exitScore > 75) {
            return ExitSignal(
                shouldExit = true,
                reason = "EXIT_SCORE",
                urgency = Urgency.MEDIUM,
                details = "Exit score ${exitScore.toInt()} > 75"
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
