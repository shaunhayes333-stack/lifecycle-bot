package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig

/**
 * PositionSizing — Centralized position sizing calculations.
 * 
 * Extracts position sizing logic from Executor.kt for cleaner code.
 * Uses Kelly Criterion, risk management, and mode-aware adjustments.
 * 
 * DEFENSIVE DESIGN:
 * - All methods are static and stateless (no initialization crashes)
 * - All calculations have safe fallbacks
 * - Try/catch wrapping for ultimate safety
 */
object PositionSizing {
    
    private const val TAG = "PositionSizing"
    
    /**
     * Calculate optimal position size using Kelly Criterion with safety caps.
     * 
     * @param walletSol Available SOL balance
     * @param winRate Historical win rate (0-1)
     * @param avgWinPct Average win percentage
     * @param avgLossPct Average loss percentage
     * @param config Bot configuration
     * @param modeMultiplier Mode-specific position size multiplier
     * @param confidence Trade confidence (0-100)
     * @return Recommended position size in SOL
     */
    fun calculateKellySize(
        walletSol: Double,
        winRate: Double,
        avgWinPct: Double,
        avgLossPct: Double,
        config: BotConfig,
        modeMultiplier: Double = 1.0,
        confidence: Double = 50.0,
    ): Double {
        return try {
            // Safety checks
            if (walletSol <= 0 || avgLossPct <= 0) {
                return config.smallBuySol.coerceAtMost(walletSol * 0.1)
            }
            
            // Kelly Criterion: f* = (bp - q) / b
            // where b = avgWin/avgLoss, p = winRate, q = 1-winRate
            val b = avgWinPct / avgLossPct.coerceAtLeast(1.0)
            val p = winRate.coerceIn(0.0, 1.0)
            val q = 1.0 - p
            
            val kellyFraction = ((b * p) - q) / b
            
            // Half-Kelly for safety (industry standard)
            val halfKelly = (kellyFraction / 2.0).coerceIn(0.01, 0.15)  // V5.9.208: cap 25% → 15% max single position
            
            // Confidence adjustment (higher confidence = closer to full Kelly)
            val confAdjust = 0.5 + (confidence / 200.0)  // 0.5-1.0 range
            
            // Calculate base size
            var size = walletSol * halfKelly * confAdjust
            
            // Apply mode multiplier
            size *= modeMultiplier
            
            // Apply config limits
            size = size.coerceIn(config.smallBuySol, config.maxPositionSol)
            
            // Never risk more than 10% of wallet per trade
            size = size.coerceAtMost(walletSol * 0.10)
            
            size
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "calculateKellySize error: ${e.message}")
            config.smallBuySol.coerceAtMost(walletSol * 0.05)
        }
    }
    
    /**
     * Calculate position size based on fixed risk percentage.
     * 
     * @param walletSol Available SOL balance
     * @param riskPct Maximum risk per trade (0-100)
     * @param stopLossPct Expected stop loss percentage
     * @param config Bot configuration
     * @param modeMultiplier Mode-specific position size multiplier
     * @return Recommended position size in SOL
     */
    fun calculateRiskBasedSize(
        walletSol: Double,
        riskPct: Double,
        stopLossPct: Double,
        config: BotConfig,
        modeMultiplier: Double = 1.0,
    ): Double {
        return try {
            if (walletSol <= 0 || stopLossPct <= 0) {
                return config.smallBuySol.coerceAtMost(walletSol * 0.1)
            }
            
            // Risk-based sizing: position = (wallet * risk%) / stopLoss%
            val riskAmount = walletSol * (riskPct / 100.0)
            var size = riskAmount / (stopLossPct / 100.0)
            
            // Apply mode multiplier
            size *= modeMultiplier
            
            // Apply config limits
            size = size.coerceIn(config.smallBuySol, config.maxPositionSol)
            
            // Never risk more than 10% of wallet per trade
            size = size.coerceAtMost(walletSol * 0.10)
            
            size
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "calculateRiskBasedSize error: ${e.message}")
            config.smallBuySol.coerceAtMost(walletSol * 0.05)
        }
    }
    
    /**
     * Calculate graduated position size for building positions.
     * 
     * Used for A+/B setups where we start smaller and scale in.
     * 
     * @param baseSize Original calculated position size
     * @param setupQuality A+, A, B, C quality rating
     * @param scalingFactor How much to reduce initial size
     * @return Graduated initial position size
     */
    fun calculateGraduatedSize(
        baseSize: Double,
        setupQuality: String,
        scalingFactor: Double = 0.5,
    ): Double {
        return try {
            when (setupQuality) {
                "A+" -> baseSize * 0.4  // Start at 40% for best setups (scale in)
                "A"  -> baseSize * 0.5  // Start at 50%
                "B"  -> baseSize * 0.6  // Start at 60%
                else -> baseSize * scalingFactor.coerceIn(0.3, 1.0)
            }
        } catch (e: Exception) {
            baseSize * 0.5
        }
    }
    
    /**
     * Calculate scaling amount for position top-up.
     * 
     * @param currentSize Current position size in SOL
     * @param maxSize Maximum allowed position size
     * @param pnlPct Current PnL percentage
     * @param scaleTriggerPct Minimum PnL to trigger scaling
     * @return Amount to add to position (0 if no scaling)
     */
    fun calculateScalingAmount(
        currentSize: Double,
        maxSize: Double,
        pnlPct: Double,
        scaleTriggerPct: Double = 20.0,
    ): Double {
        return try {
            // Only scale if profitable
            if (pnlPct < scaleTriggerPct) return 0.0
            
            // Don't exceed max size
            val available = maxSize - currentSize
            if (available <= 0) return 0.0
            
            // Scale amount based on profit (more profit = more confident scaling)
            val scaleFactor = when {
                pnlPct >= 100.0 -> 0.5  // 100%+ profit: scale 50% of available
                pnlPct >= 50.0  -> 0.3  // 50%+ profit: scale 30%
                pnlPct >= 30.0  -> 0.2  // 30%+ profit: scale 20%
                else            -> 0.15 // Default: scale 15%
            }
            
            (available * scaleFactor).coerceAtLeast(0.0)
        } catch (e: Exception) {
            0.0
        }
    }
    
    /**
     * Determine if position should be scaled (top-up).
     * 
     * @param pnlPct Current PnL percentage
     * @param timeSinceBuyMs Time since initial buy
     * @param volatilityPct Recent price volatility
     * @param config Bot configuration
     * @return true if scaling is recommended
     */
    fun shouldScale(
        pnlPct: Double,
        timeSinceBuyMs: Long,
        volatilityPct: Double = 0.0,
        config: BotConfig,
    ): Boolean {
        return try {
            // Basic requirements
            if (pnlPct < 15.0) return false  // Need at least 15% profit
            if (timeSinceBuyMs < 60_000) return false  // Wait at least 1 minute
            
            // Don't scale in high volatility
            if (volatilityPct > 30.0) return false
            
            // Scale in strong uptrends
            pnlPct >= 20.0 && timeSinceBuyMs >= 120_000
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Calculate position size based on liquidity.
     * Ensures we don't take too much of available liquidity.
     * 
     * @param liquidityUsd Available liquidity in USD
     * @param solPrice Current SOL price
     * @param maxLiquidityPct Maximum percentage of liquidity to take
     * @return Maximum safe position size in SOL
     */
    fun calculateLiquidityBasedMax(
        liquidityUsd: Double,
        solPrice: Double,
        maxLiquidityPct: Double = 2.0,
    ): Double {
        return try {
            if (liquidityUsd <= 0 || solPrice <= 0) return 0.0
            
            val maxUsd = liquidityUsd * (maxLiquidityPct / 100.0)
            maxUsd / solPrice
        } catch (e: Exception) {
            0.0
        }
    }
    
    /**
     * Get recommended position size considering all factors.
     * 
     * This is the main entry point for position sizing.
     * 
     * @param walletSol Available SOL balance
     * @param config Bot configuration
     * @param winRate Historical win rate
     * @param confidence Trade confidence (0-100)
     * @param liquidityUsd Available liquidity
     * @param solPrice Current SOL price
     * @param modeMultiplier Mode-specific multiplier
     * @return Final recommended position size in SOL
     */
    fun getRecommendedSize(
        walletSol: Double,
        config: BotConfig,
        winRate: Double = 0.5,
        confidence: Double = 50.0,
        liquidityUsd: Double = Double.MAX_VALUE,
        solPrice: Double = 100.0,
        modeMultiplier: Double = 1.0,
    ): Double {
        return try {
            // Calculate using Kelly
            var size = calculateKellySize(
                walletSol = walletSol,
                winRate = winRate,
                avgWinPct = 30.0,  // Assume 30% avg win
                avgLossPct = 15.0, // Assume 15% avg loss
                config = config,
                modeMultiplier = modeMultiplier,
                confidence = confidence,
            )
            
            // Cap by liquidity
            val liqMax = calculateLiquidityBasedMax(liquidityUsd, solPrice)
            if (liqMax > 0) {
                size = size.coerceAtMost(liqMax)
            }
            
            // Apply config bounds
            size.coerceIn(config.smallBuySol, config.maxPositionSol)
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "getRecommendedSize error: ${e.message}")
            config.smallBuySol.coerceAtMost(walletSol * 0.05)
        }
    }
}
