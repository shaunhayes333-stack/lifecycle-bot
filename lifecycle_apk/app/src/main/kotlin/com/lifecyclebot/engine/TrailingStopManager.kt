package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig

/**
 * TrailingStopManager — Adaptive trailing stop calculations.
 * 
 * Manages trailing stops that adapt to:
 * - Current profit level (wider stops for moonshots)
 * - Volatility (wider during high volatility)
 * - Trading mode (aggressive vs defensive)
 * - Time held (tighter as position ages)
 * 
 * DEFENSIVE DESIGN:
 * - All methods are static and stateless (no initialization crashes)
 * - All calculations have safe fallbacks
 * - Try/catch wrapping for ultimate safety
 */
object TrailingStopManager {
    
    private const val TAG = "TrailingStopManager"
    
    /**
     * Trailing stop result with all relevant info.
     */
    data class TrailingStopResult(
        val stopPricePct: Double,         // Stop as % below high (e.g., 15.0 = 15%)
        val stopPriceUsd: Double,         // Actual stop price in USD
        val reason: String,               // Why this stop level was chosen
        val isAdaptive: Boolean = true,   // Whether adaptive logic was applied
    )
    
    /**
     * Calculate adaptive trailing stop based on current conditions.
     * 
     * @param currentPriceUsd Current price
     * @param highPriceUsd Highest price since entry
     * @param entryPriceUsd Entry price
     * @param pnlPct Current PnL percentage
     * @param volatilityPct Recent price volatility
     * @param holdTimeMs Time since entry in milliseconds
     * @param config Bot configuration
     * @param modeMultiplier Mode-specific stop multiplier (higher = tighter)
     * @return TrailingStopResult with calculated stop level
     */
    fun calculateAdaptiveStop(
        currentPriceUsd: Double,
        highPriceUsd: Double,
        entryPriceUsd: Double,
        pnlPct: Double,
        volatilityPct: Double = 0.0,
        holdTimeMs: Long = 0,
        config: BotConfig,
        modeMultiplier: Double = 1.0,
    ): TrailingStopResult {
        return try {
            // Base trailing stop from config
            var baseStopPct = config.trailingStopBasePct
            var reason = "base"
            
            // ═══════════════════════════════════════════════════════════════════
            // PROFIT-BASED ADAPTATION
            // Higher profits = wider stops (let winners run)
            // ═══════════════════════════════════════════════════════════════════
            val profitAdaptation = when {
                pnlPct >= 2000.0 -> {
                    reason = "ultra_moonshot_2000%+"
                    0.30  // V5.9.208: 30% of base (tighter lock on extreme runners — don't give back 70%)
                }
                pnlPct >= 500.0 -> {
                    reason = "moonshot_500%+"
                    0.45  // V5.9.208: 45% of base (was 50% — tighten to lock more profit)
                }
                pnlPct >= 200.0 -> {
                    reason = "runner_200%+"
                    0.6  // 60% of base (wide for strong runners)
                }
                pnlPct >= 100.0 -> {
                    reason = "double_100%+"
                    0.7  // 70% of base
                }
                pnlPct >= 50.0 -> {
                    reason = "solid_50%+"
                    0.85  // 85% of base
                }
                pnlPct >= 20.0 -> {
                    reason = "profitable_20%+"
                    0.95  // 95% of base
                }
                pnlPct > 0.0 -> {
                    reason = "small_gain"
                    1.0   // Full base stop
                }
                else -> {
                    reason = "underwater"
                    1.1   // Slightly tighter when losing
                }
            }
            
            baseStopPct *= profitAdaptation
            
            // ═══════════════════════════════════════════════════════════════════
            // VOLATILITY ADAPTATION
            // Higher volatility = wider stops (avoid getting shaken out)
            // ═══════════════════════════════════════════════════════════════════
            if (volatilityPct > 0) {
                val volAdaptation = when {
                    volatilityPct >= 30.0 -> 0.7   // Very volatile: 70% (much wider)
                    volatilityPct >= 20.0 -> 0.8   // Volatile: 80%
                    volatilityPct >= 10.0 -> 0.9   // Moderate: 90%
                    else -> 1.0                    // Low vol: normal
                }
                baseStopPct *= volAdaptation
                reason += "+vol"
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // TIME-BASED ADAPTATION
            // Older positions get tighter stops (lock in gains)
            // ═══════════════════════════════════════════════════════════════════
            if (holdTimeMs > 0) {
                val holdHours = holdTimeMs / 3_600_000.0
                val timeAdaptation = when {
                    holdHours >= 24.0 -> 1.3   // >24h: tighter (lock gains)
                    holdHours >= 12.0 -> 1.2   // >12h: somewhat tighter
                    holdHours >= 6.0  -> 1.1   // >6h: slightly tighter
                    holdHours >= 1.0  -> 1.0   // 1-6h: normal
                    else -> 0.95              // <1h: slightly wider (let it develop)
                }
                baseStopPct *= timeAdaptation
                reason += "+time"
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // MODE MULTIPLIER
            // Apply trading mode adjustment (higher = tighter stops)
            // ═══════════════════════════════════════════════════════════════════
            baseStopPct *= modeMultiplier
            if (modeMultiplier != 1.0) {
                reason += "+mode"
            }
            
            // Ensure stop is within reasonable bounds
            // V5.9.186: adaptive bounds — tighter on big winners, wider early on
            val minStop = when {
                pnlPct >= 200.0 -> 3.5
                pnlPct >= 100.0 -> 4.5
                pnlPct >= 50.0  -> 5.5
                pnlPct >= 20.0  -> 7.0
                else            -> 8.0
            }
            val maxStop = when {
                pnlPct >= 200.0 -> 12.0
                pnlPct >= 100.0 -> 18.0
                else            -> 35.0
            }
            val finalStopPct = baseStopPct.coerceIn(minStop, maxStop)
            
            // Calculate actual stop price (anchored to the peak/high).
            val trailStopPrice = highPriceUsd * (1.0 - finalStopPct / 100.0)

            // ═══════════════════════════════════════════════════════════════════
            // V5.9.1488 — PEAK-AWARE PROFIT-LOCK RATCHET (fix 55pp MFE give-back)
            // Snapshot 5.0.3494: MOONSHOT avgPeak=+51% but avgRealized=-4.3%
            // (giveBack=55.2pp, the #1 driver of pf=0.57). Root cause: the trail
            // width above keys on CURRENT pnlPct, not PEAK gain. When a position
            // peaks high then pulls back into a low-pnl band, it INHERITS the wide
            // early-stage stop (maxStop up to 35%), so a token that hit +51% is
            // allowed to round-trip to ~-2% before the trail fires. Peak info was
            // ignored entirely.
            //
            // Fix: a monotonic profit-lock floor derived from the PEAK gain that
            // never loosens. Once a position has achieved a peak tier, it may
            // never give back below that tier's locked floor. We take the TIGHTER
            // (higher) of the adaptive trail price and the peak-lock floor price.
            // This directly protects avg_win (doctrine: avg_win*WR must beat
            // avg_loss*(1-WR)). Pure exit-side; no entry/volume impact; the
            // unconditional -15% hard floor and all other gates are untouched.
            val peakGainPct = if (entryPriceUsd > 0.0)
                ((highPriceUsd - entryPriceUsd) / entryPriceUsd) * 100.0
            else 0.0
            // Locked MINIMUM profit (vs entry) the position must retain, by peak tier.
            val lockedFloorGainPct: Double = when {
                peakGainPct >= 200.0 -> peakGainPct * 0.55  // give back at most ~45% of a huge run
                peakGainPct >= 100.0 -> peakGainPct * 0.50
                peakGainPct >= 50.0  -> 15.0                // +50% peak ⇒ keep at least +15%
                peakGainPct >= 30.0  -> 8.0                 // +30% peak ⇒ keep at least +8%
                peakGainPct >= 20.0  -> 2.0                 // +20% peak ⇒ never let it become a loss
                else                 -> Double.NEGATIVE_INFINITY  // no lock below +20% peak
            }
            val lockFloorPrice = if (lockedFloorGainPct > Double.NEGATIVE_INFINITY)
                entryPriceUsd * (1.0 + lockedFloorGainPct / 100.0)
            else 0.0
            // Tighter of the two = higher stop price.
            val stopPrice = kotlin.math.max(trailStopPrice, lockFloorPrice)
            val lockEngaged = lockFloorPrice > trailStopPrice && lockFloorPrice > 0.0
            val finalReason = if (lockEngaged) "$reason+peaklock(${lockedFloorGainPct.toInt()}%@peak${peakGainPct.toInt()}%)" else reason
            // Report the effective stop pct relative to the peak so telemetry stays consistent.
            val effectiveStopPct = if (highPriceUsd > 0.0)
                ((highPriceUsd - stopPrice) / highPriceUsd) * 100.0
            else finalStopPct

            TrailingStopResult(
                stopPricePct = effectiveStopPct,
                stopPriceUsd = stopPrice,
                reason = finalReason,
                isAdaptive = true,
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "calculateAdaptiveStop error: ${e.message}")
            // Safe fallback
            TrailingStopResult(
                stopPricePct = config.trailingStopBasePct,
                stopPriceUsd = highPriceUsd * (1.0 - config.trailingStopBasePct / 100.0),
                reason = "fallback",
                isAdaptive = false,
            )
        }
    }
    
    /**
     * Check if trailing stop has been hit.
     * 
     * @param currentPriceUsd Current price
     * @param stopPriceUsd Calculated stop price
     * @return true if stop has been hit
     */
    fun isStopHit(currentPriceUsd: Double, stopPriceUsd: Double): Boolean {
        return try {
            currentPriceUsd <= stopPriceUsd
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get reason for stop hit with context.
     * 
     * @param currentPriceUsd Current price
     * @param stopPriceUsd Stop price
     * @param highPriceUsd High since entry
     * @param entryPriceUsd Entry price
     * @return Human-readable reason string
     */
    fun getStopHitReason(
        currentPriceUsd: Double,
        stopPriceUsd: Double,
        highPriceUsd: Double,
        entryPriceUsd: Double,
    ): String {
        return try {
            val dropFromHigh = ((highPriceUsd - currentPriceUsd) / highPriceUsd * 100).toInt()
            val pnlPct = (OpenPnlSanity.inspect(entryPriceUsd, currentPriceUsd, context = "TrailingStopManager_6038", emit = true).takeIf { it.ok }?.pnlPct ?: 0.0).toInt()
            
            if (pnlPct >= 0) {
                "Trailing stop hit at +${pnlPct}% gain (dropped ${dropFromHigh}% from high)"
            } else {
                "Stop loss hit at ${pnlPct}% loss"
            }
        } catch (e: Exception) {
            "Stop triggered"
        }
    }
    
    /**
     * Calculate breakeven stop (entry price + fees).
     * 
     * @param entryPriceUsd Entry price
     * @param feePct Total fees percentage (buy + sell)
     * @return Breakeven price
     */
    fun calculateBreakevenStop(
        entryPriceUsd: Double,
        feePct: Double = 1.0,  // Default 1% total fees
    ): Double {
        return try {
            entryPriceUsd * (1.0 + feePct / 100.0)
        } catch (e: Exception) {
            entryPriceUsd
        }
    }
    
    /**
     * Should we move stop to breakeven?
     * 
     * @param pnlPct Current PnL percentage
     * @param holdTimeMs Time held
     * @param breakevenThresholdPct Min profit to move to breakeven
     * @return true if should move to breakeven
     */
    fun shouldMoveToBreakeven(
        pnlPct: Double,
        holdTimeMs: Long,
        breakevenThresholdPct: Double = 15.0,
    ): Boolean {
        return try {
            // Move to breakeven once we're up 15%+ and held for at least 5 mins
            pnlPct >= breakevenThresholdPct && holdTimeMs >= 300_000
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get recommended stop type based on conditions.
     */
    enum class StopType {
        TRAILING,      // Normal trailing stop
        BREAKEVEN,     // Locked at entry
        HARD_STOP,     // Fixed stop loss
        MOONSHOT,      // Extra wide for runners
    }
    
    /**
     * Determine which stop type to use.
     * 
     * @param pnlPct Current PnL percentage
     * @param holdTimeMs Time held
     * @param isRunner Whether token is flagged as a runner
     * @return Recommended stop type
     */
    fun getRecommendedStopType(
        pnlPct: Double,
        holdTimeMs: Long,
        isRunner: Boolean = false,
    ): StopType {
        return try {
            when {
                pnlPct >= 100.0 || isRunner -> StopType.MOONSHOT
                pnlPct >= 15.0 && holdTimeMs >= 300_000 -> StopType.BREAKEVEN
                pnlPct > 0 -> StopType.TRAILING
                else -> StopType.HARD_STOP
            }
        } catch (e: Exception) {
            StopType.TRAILING
        }
    }
}
