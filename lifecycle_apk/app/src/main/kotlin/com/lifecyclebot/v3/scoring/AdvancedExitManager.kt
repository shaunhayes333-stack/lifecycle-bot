package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        // ShitCoin - Tight stops, quick exits
        SHITCOIN(
            baseTakeProfitPct = 25.0,
            baseStopLossPct = 10.0,
            baseTrailingPct = 8.0,
            maxHoldMinutes = 15,
            chunkSellEnabled = false,
            progressiveTrailing = true,
        ),
        
        // Express - Ultra tight for momentum trades
        EXPRESS(
            baseTakeProfitPct = 30.0,
            baseStopLossPct = 8.0,
            baseTrailingPct = 5.0,
            maxHoldMinutes = 10,
            chunkSellEnabled = false,
            progressiveTrailing = true,
        ),
        
        // Blue Chip - Wider stops, longer holds
        BLUE_CHIP(
            baseTakeProfitPct = 40.0,
            baseStopLossPct = 15.0,
            baseTrailingPct = 10.0,
            maxHoldMinutes = 120,
            chunkSellEnabled = true,
            progressiveTrailing = true,
        ),
        
        // Dip Hunter - Recovery focused
        DIP_HUNTER(
            baseTakeProfitPct = 20.0,
            baseStopLossPct = 15.0,
            baseTrailingPct = 12.0,
            maxHoldMinutes = 360,
            chunkSellEnabled = true,
            progressiveTrailing = false,
        ),
        
        // Treasury - Quick scalps
        TREASURY(
            baseTakeProfitPct = 15.0,
            baseStopLossPct = 8.0,
            baseTrailingPct = 6.0,
            maxHoldMinutes = 30,
            chunkSellEnabled = false,
            progressiveTrailing = true,
        ),
        
        // V3 Standard - Balanced
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
        val chunk1Pct: Double,      // First partial sell target (if enabled)
        val chunk1SellPct: Int,     // % of position to sell at chunk1
        val chunk2Pct: Double,      // Second partial sell target
        val chunk2SellPct: Int,
        val timeExitMinutes: Int,
    )
    
    /**
     * Calculate fluid exit targets based on profile and market conditions
     */
    fun calculateExitTargets(
        profile: ExitProfile,
        entryScore: Int,          // How confident was the entry (0-100)
        momentum: Double,         // Current momentum
        volatility: Double,       // Current volatility
        marketRegime: String,     // BULL, BEAR, RANGE
    ): ExitTargets {
        
        // Get learning progress for fluid adjustments
        val learningProgress = FluidLearningAI.getLearningProgress()
        
        // Base targets from profile
        var takeProfitPct = profile.baseTakeProfitPct
        var stopLossPct = profile.baseStopLossPct
        var trailingPct = profile.baseTrailingPct
        var maxHold = profile.maxHoldMinutes
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID ADJUSTMENTS BASED ON LEARNING
        // ═══════════════════════════════════════════════════════════════════
        
        // As we mature, we can take more risk but also expect more reward
        val fluidMultiplier = 0.8 + learningProgress * 0.4  // 0.8 to 1.2
        
        takeProfitPct *= fluidMultiplier
        // Stop loss gets TIGHTER as we mature (more discipline)
        stopLossPct *= (1.2 - learningProgress * 0.3)  // 1.2 to 0.9
        
        // ═══════════════════════════════════════════════════════════════════
        // ENTRY SCORE ADJUSTMENTS
        // ═══════════════════════════════════════════════════════════════════
        
        // High confidence entries get wider stops (more room to work)
        if (entryScore >= 80) {
            stopLossPct *= 1.2
            takeProfitPct *= 1.3
            maxHold = (maxHold * 1.5).toInt()
        } else if (entryScore <= 50) {
            // Low confidence = tight stops
            stopLossPct *= 0.8
            takeProfitPct *= 0.8
            maxHold = (maxHold * 0.7).toInt()
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // MOMENTUM ADJUSTMENTS
        // ═══════════════════════════════════════════════════════════════════
        
        if (momentum > 10) {
            // Strong momentum = let winners run
            takeProfitPct *= 1.3
            trailingPct *= 1.2  // Wider trailing to not get stopped out
        } else if (momentum < -5) {
            // Negative momentum = tighten everything
            stopLossPct *= 0.8
            takeProfitPct *= 0.7
            trailingPct *= 0.7
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // VOLATILITY ADJUSTMENTS
        // ═══════════════════════════════════════════════════════════════════
        
        if (volatility > 50) {
            // High volatility = wider stops to avoid noise
            stopLossPct *= 1.3
            trailingPct *= 1.4
        } else if (volatility < 10) {
            // Low volatility = tighter stops
            stopLossPct *= 0.8
            trailingPct *= 0.8
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // MARKET REGIME ADJUSTMENTS
        // ═══════════════════════════════════════════════════════════════════
        
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
        
        // ═══════════════════════════════════════════════════════════════════
        // CHUNK SELL TARGETS
        // ═══════════════════════════════════════════════════════════════════
        
        val chunk1Pct = if (profile.chunkSellEnabled) takeProfitPct * 0.5 else 0.0
        val chunk1Sell = if (profile.chunkSellEnabled) 25 else 0
        val chunk2Pct = if (profile.chunkSellEnabled) takeProfitPct * 0.75 else 0.0
        val chunk2Sell = if (profile.chunkSellEnabled) 25 else 0
        
        // ═══════════════════════════════════════════════════════════════════
        // APPLY HARD LIMITS
        // ═══════════════════════════════════════════════════════════════════
        
        takeProfitPct = takeProfitPct.coerceIn(10.0, 200.0)
        stopLossPct = stopLossPct.coerceIn(5.0, 25.0)
        trailingPct = trailingPct.coerceIn(3.0, 20.0)
        maxHold = maxHold.coerceIn(5, 480)
        
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
     * Calculate trailing stop that gets TIGHTER as profit increases
     * 
     * The idea: When you're up 10%, you can afford a 10% trailing.
     * When you're up 50%, you want to protect more - use 5% trailing.
     * When you're up 100%, use very tight 3% trailing.
     */
    fun calculateProgressiveTrailingStop(
        currentPnlPct: Double,
        baseTrailingPct: Double,
        highWaterMarkPrice: Double,
    ): Double {
        
        if (currentPnlPct <= 0) {
            // Not profitable, no trailing (use regular stop loss)
            return 0.0
        }
        
        // Progressive trailing: tighter as profit grows
        val effectiveTrailing = when {
            currentPnlPct >= 100 -> baseTrailingPct * 0.4   // 40% of base when up 100%+
            currentPnlPct >= 75 -> baseTrailingPct * 0.5
            currentPnlPct >= 50 -> baseTrailingPct * 0.6
            currentPnlPct >= 30 -> baseTrailingPct * 0.75
            currentPnlPct >= 20 -> baseTrailingPct * 0.85
            currentPnlPct >= 10 -> baseTrailingPct * 0.95
            else -> baseTrailingPct
        }.coerceIn(2.0, 20.0)
        
        // Calculate stop price
        val trailingStopPrice = highWaterMarkPrice * (1 - effectiveTrailing / 100)
        
        return trailingStopPrice
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIME-BASED EXIT PRESSURE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate time pressure - as hold time increases, exits get more aggressive
     */
    fun calculateTimePressure(
        holdMinutes: Int,
        maxHoldMinutes: Int,
        currentPnlPct: Double,
    ): TimePressure {
        
        val holdRatio = holdMinutes.toDouble() / maxHoldMinutes
        
        return when {
            // Near time limit and losing - URGENT exit
            holdRatio >= 0.9 && currentPnlPct < 0 -> TimePressure.URGENT_EXIT
            
            // Near time limit and profitable - Take what we have
            holdRatio >= 0.9 && currentPnlPct > 0 -> TimePressure.TAKE_PROFIT
            
            // Past 75% time, pressure builds
            holdRatio >= 0.75 -> TimePressure.HIGH_PRESSURE
            
            // Past 50% time, moderate pressure
            holdRatio >= 0.5 -> TimePressure.MODERATE_PRESSURE
            
            // Still have time
            else -> TimePressure.NO_PRESSURE
        }
    }
    
    enum class TimePressure(val takeProfitMultiplier: Double, val stopMultiplier: Double) {
        NO_PRESSURE(1.0, 1.0),
        MODERATE_PRESSURE(0.9, 0.9),
        HIGH_PRESSURE(0.75, 0.8),
        TAKE_PROFIT(0.5, 0.7),
        URGENT_EXIT(0.0, 0.5),  // Exit at any profit, tightest stop
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MASTER EXIT CHECK
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ExitDecision(
        val shouldExit: Boolean,
        val sellPct: Int,           // How much to sell (0-100)
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
        
        val pnlPct = (currentPrice - entryPrice) / entryPrice * 100
        
        // Get fluid exit targets
        val targets = calculateExitTargets(profile, entryScore, currentMomentum, volatility, marketRegime)
        
        // Get time pressure
        val timePressure = calculateTimePressure(holdMinutes, targets.timeExitMinutes, pnlPct)
        
        // Adjust targets for time pressure
        val effectiveTakeProfit = targets.takeProfitPct * timePressure.takeProfitMultiplier
        val effectiveStopLoss = targets.stopLossPct * timePressure.stopMultiplier
        
        // ─── EXIT CHECKS (Priority Order) ───
        
        // 1. LIQUIDITY COLLAPSE - Emergency exit
        if (currentLiquidity < entryLiquidity * 0.5) {
            return ExitDecision(true, 100, ExitReason.LIQUIDITY_EXIT, ExitUrgency.CRITICAL)
        }
        
        // 2. HARD STOP LOSS
        if (pnlPct <= -effectiveStopLoss) {
            return ExitDecision(true, 100, ExitReason.STOP_LOSS, ExitUrgency.HIGH)
        }
        
        // 3. FULL TAKE PROFIT
        if (pnlPct >= effectiveTakeProfit) {
            return ExitDecision(true, 100, ExitReason.TAKE_PROFIT_FULL, ExitUrgency.MEDIUM)
        }
        
        // 4. PROGRESSIVE TRAILING STOP (if in profit)
        if (profile.progressiveTrailing && pnlPct > 10) {
            val trailingStopPrice = calculateProgressiveTrailingStop(
                pnlPct, targets.trailingStopPct, highWaterMarkPrice
            )
            if (currentPrice <= trailingStopPrice) {
                return ExitDecision(true, 100, ExitReason.TRAILING_STOP, ExitUrgency.MEDIUM)
            }
        }
        
        // 5. CHUNK SELLS
        if (profile.chunkSellEnabled) {
            // First chunk
            if (alreadySoldPct < targets.chunk1SellPct && pnlPct >= targets.chunk1Pct) {
                return ExitDecision(true, targets.chunk1SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW)
            }
            // Second chunk
            if (alreadySoldPct < targets.chunk1SellPct + targets.chunk2SellPct && pnlPct >= targets.chunk2Pct) {
                return ExitDecision(true, targets.chunk2SellPct, ExitReason.TAKE_PROFIT_CHUNK, ExitUrgency.LOW)
            }
        }
        
        // 6. TIME PRESSURE EXIT
        if (timePressure == TimePressure.URGENT_EXIT) {
            return ExitDecision(true, 100, ExitReason.TIME_EXIT, ExitUrgency.HIGH)
        }
        if (timePressure == TimePressure.TAKE_PROFIT && pnlPct > 0) {
            return ExitDecision(true, 100, ExitReason.TIME_EXIT, ExitUrgency.MEDIUM)
        }
        
        // 7. MOMENTUM DEATH (if profitable)
        if (pnlPct > 5 && currentMomentum < -10) {
            return ExitDecision(true, 100, ExitReason.MOMENTUM_EXIT, ExitUrgency.MEDIUM)
        }
        
        // 8. HOLD
        return ExitDecision(false, 0, ExitReason.HOLD, ExitUrgency.NONE)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUICK HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Quick check if we should cut loss (for rapid stop loss monitoring)
     */
    fun shouldCutLoss(
        pnlPct: Double,
        baseStopPct: Double,
        momentum: Double,
        holdMinutes: Int,
    ): Boolean {
        // Dynamic stop based on time held
        val timeMultiplier = when {
            holdMinutes < 2 -> 0.7    // Very tight in first 2 mins
            holdMinutes < 5 -> 0.85
            holdMinutes < 10 -> 1.0
            else -> 1.1               // Slightly wider after 10 mins
        }
        
        // Momentum adjustment
        val momentumMultiplier = when {
            momentum < -15 -> 0.6     // Crashing - cut fast
            momentum < -5 -> 0.8
            momentum > 10 -> 1.2      // Strong momentum - give room
            else -> 1.0
        }
        
        val effectiveStop = baseStopPct * timeMultiplier * momentumMultiplier
        
        return pnlPct <= -effectiveStop
    }
}
