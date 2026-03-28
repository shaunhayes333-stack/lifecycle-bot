package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger

/**
 * ArbExitEngine - Exit decision logic for arb trades
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * THIS MATTERS MOST.
 * 
 * Arb trades are SHORT-HOLD CASH EXTRACTION trades, not moonbags.
 * Exit logic is aggressive and time-based.
 * 
 * EXIT CONDITIONS BY TYPE:
 * 
 * VENUE_LAG:
 *   - Take profit at 4%+
 *   - Max hold 120 seconds
 *   - Exit if buy pressure drops below 52%
 * 
 * FLOW_IMBALANCE:
 *   - Take profit at 3.5%+
 *   - Max hold 90 seconds
 *   - Exit if liquidity starts draining
 * 
 * PANIC_REVERSION:
 *   - Take profit at 2.5%+
 *   - Max hold 60 seconds
 *   - Exit if buy pressure drops below 50%
 *   - IMMEDIATE exit on fatal risk
 */
object ArbExitEngine {
    
    private const val TAG = "ArbExit"
    
    // Default exit thresholds (can be overridden by ArbEvaluation)
    private const val VENUE_LAG_TP_PCT = 4.0
    private const val VENUE_LAG_SL_PCT = 3.0
    private const val VENUE_LAG_MAX_HOLD = 120
    private const val VENUE_LAG_BP_FLOOR = 52.0
    
    private const val FLOW_IMBALANCE_TP_PCT = 3.5
    private const val FLOW_IMBALANCE_SL_PCT = 2.5
    private const val FLOW_IMBALANCE_MAX_HOLD = 90
    
    private const val PANIC_REVERSION_TP_PCT = 2.5
    private const val PANIC_REVERSION_SL_PCT = 4.0
    private const val PANIC_REVERSION_MAX_HOLD = 60
    private const val PANIC_REVERSION_BP_FLOOR = 50.0
    
    // Trailing stop activation
    private const val TRAILING_STOP_ACTIVATION_PCT = 2.0  // Activate after 2% gain
    private const val TRAILING_STOP_DISTANCE_PCT = 1.5    // Trail by 1.5%
    
    /**
     * Check if an arb position should exit.
     * Returns exit reason or null if should hold.
     */
    fun shouldExit(
        arbType: ArbType,
        holdSeconds: Int,
        pnlPct: Double,
        buyPressurePct: Double,
        liquidityDraining: Boolean,
        fatalRisk: Boolean = false,
        highWaterMark: Double = 0.0,
        currentPrice: Double = 0.0,
        entryPrice: Double = 0.0
    ): String? {
        
        // ═══════════════════════════════════════════════════════════════════
        // UNIVERSAL EXIT: Fatal risk = immediate exit
        // ═══════════════════════════════════════════════════════════════════
        if (fatalRisk) {
            ErrorLogger.warn(TAG, "[ARB_EXIT] Fatal risk detected - IMMEDIATE EXIT")
            return "FATAL_RISK"
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // TYPE-SPECIFIC EXIT LOGIC
        // ═══════════════════════════════════════════════════════════════════
        return when (arbType) {
            ArbType.VENUE_LAG -> checkVenueLagExit(
                holdSeconds, pnlPct, buyPressurePct,
                highWaterMark, currentPrice, entryPrice
            )
            
            ArbType.FLOW_IMBALANCE -> checkFlowImbalanceExit(
                holdSeconds, pnlPct, liquidityDraining,
                highWaterMark, currentPrice, entryPrice
            )
            
            ArbType.PANIC_REVERSION -> checkPanicReversionExit(
                holdSeconds, pnlPct, buyPressurePct,
                highWaterMark, currentPrice, entryPrice
            )
        }
    }
    
    /**
     * Check exit conditions for VENUE_LAG position.
     */
    private fun checkVenueLagExit(
        holdSeconds: Int,
        pnlPct: Double,
        buyPressurePct: Double,
        highWaterMark: Double,
        currentPrice: Double,
        entryPrice: Double
    ): String? {
        // Take profit
        if (pnlPct >= VENUE_LAG_TP_PCT) {
            return "TP_HIT_${pnlPct.toInt()}%"
        }
        
        // Stop loss
        if (pnlPct <= -VENUE_LAG_SL_PCT) {
            return "SL_HIT_${pnlPct.toInt()}%"
        }
        
        // Time limit
        if (holdSeconds >= VENUE_LAG_MAX_HOLD) {
            return "TIME_LIMIT_${holdSeconds}s"
        }
        
        // Buy pressure floor
        if (buyPressurePct < VENUE_LAG_BP_FLOOR) {
            return "BP_FLOOR_${buyPressurePct.toInt()}%"
        }
        
        // Trailing stop check
        checkTrailingStop(pnlPct, highWaterMark, currentPrice, entryPrice)?.let {
            return it
        }
        
        return null  // Hold
    }
    
    /**
     * Check exit conditions for FLOW_IMBALANCE position.
     */
    private fun checkFlowImbalanceExit(
        holdSeconds: Int,
        pnlPct: Double,
        liquidityDraining: Boolean,
        highWaterMark: Double,
        currentPrice: Double,
        entryPrice: Double
    ): String? {
        // Take profit
        if (pnlPct >= FLOW_IMBALANCE_TP_PCT) {
            return "TP_HIT_${pnlPct.toInt()}%"
        }
        
        // Stop loss
        if (pnlPct <= -FLOW_IMBALANCE_SL_PCT) {
            return "SL_HIT_${pnlPct.toInt()}%"
        }
        
        // Time limit
        if (holdSeconds >= FLOW_IMBALANCE_MAX_HOLD) {
            return "TIME_LIMIT_${holdSeconds}s"
        }
        
        // Liquidity draining
        if (liquidityDraining) {
            return "LIQUIDITY_DRAIN"
        }
        
        // Trailing stop check
        checkTrailingStop(pnlPct, highWaterMark, currentPrice, entryPrice)?.let {
            return it
        }
        
        return null  // Hold
    }
    
    /**
     * Check exit conditions for PANIC_REVERSION position.
     */
    private fun checkPanicReversionExit(
        holdSeconds: Int,
        pnlPct: Double,
        buyPressurePct: Double,
        highWaterMark: Double,
        currentPrice: Double,
        entryPrice: Double
    ): String? {
        // Take profit (quicker target for reversion)
        if (pnlPct >= PANIC_REVERSION_TP_PCT) {
            return "TP_HIT_${pnlPct.toInt()}%"
        }
        
        // Stop loss
        if (pnlPct <= -PANIC_REVERSION_SL_PCT) {
            return "SL_HIT_${pnlPct.toInt()}%"
        }
        
        // Time limit (shortest of all types)
        if (holdSeconds >= PANIC_REVERSION_MAX_HOLD) {
            return "TIME_LIMIT_${holdSeconds}s"
        }
        
        // Buy pressure floor
        if (buyPressurePct < PANIC_REVERSION_BP_FLOOR) {
            return "BP_FLOOR_${buyPressurePct.toInt()}%"
        }
        
        // Trailing stop check (tighter for panic reversion)
        checkTrailingStop(pnlPct, highWaterMark, currentPrice, entryPrice, trailPct = 1.0)?.let {
            return it
        }
        
        return null  // Hold
    }
    
    /**
     * Check trailing stop condition.
     */
    private fun checkTrailingStop(
        pnlPct: Double,
        highWaterMark: Double,
        currentPrice: Double,
        entryPrice: Double,
        activationPct: Double = TRAILING_STOP_ACTIVATION_PCT,
        trailPct: Double = TRAILING_STOP_DISTANCE_PCT
    ): String? {
        // Only activate if we've had gains
        if (highWaterMark <= entryPrice) return null
        
        // Calculate high water mark P&L
        val hwmPnl = if (entryPrice > 0) ((highWaterMark - entryPrice) / entryPrice) * 100 else 0.0
        
        // Only activate trailing stop if we've hit activation threshold
        if (hwmPnl < activationPct) return null
        
        // Check if we've dropped below trail distance from high
        val drawdown = hwmPnl - pnlPct
        if (drawdown >= trailPct) {
            return "TRAIL_STOP_${pnlPct.toInt()}%_from_${hwmPnl.toInt()}%"
        }
        
        return null
    }
    
    /**
     * Get recommended exit thresholds for an arb type.
     */
    fun getExitThresholds(arbType: ArbType): ExitThresholds {
        return when (arbType) {
            ArbType.VENUE_LAG -> ExitThresholds(
                takeProfitPct = VENUE_LAG_TP_PCT,
                stopLossPct = VENUE_LAG_SL_PCT,
                maxHoldSeconds = VENUE_LAG_MAX_HOLD,
                buyPressureFloor = VENUE_LAG_BP_FLOOR
            )
            ArbType.FLOW_IMBALANCE -> ExitThresholds(
                takeProfitPct = FLOW_IMBALANCE_TP_PCT,
                stopLossPct = FLOW_IMBALANCE_SL_PCT,
                maxHoldSeconds = FLOW_IMBALANCE_MAX_HOLD,
                buyPressureFloor = null
            )
            ArbType.PANIC_REVERSION -> ExitThresholds(
                takeProfitPct = PANIC_REVERSION_TP_PCT,
                stopLossPct = PANIC_REVERSION_SL_PCT,
                maxHoldSeconds = PANIC_REVERSION_MAX_HOLD,
                buyPressureFloor = PANIC_REVERSION_BP_FLOOR
            )
        }
    }
    
    /**
     * Exit thresholds data class.
     */
    data class ExitThresholds(
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val maxHoldSeconds: Int,
        val buyPressureFloor: Double?
    )
    
    /**
     * Format exit reason for logging.
     */
    fun formatExitLog(
        mint: String,
        symbol: String,
        arbType: ArbType,
        reason: String,
        pnlPct: Double,
        holdSeconds: Int
    ): String {
        return "[ARB_EXIT] $symbol | ${arbType.name} | pnl=${String.format("%.1f", pnlPct)}% | " +
               "hold=${holdSeconds}s | reason=$reason"
    }
}
