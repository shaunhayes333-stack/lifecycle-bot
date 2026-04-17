package com.lifecyclebot.engine

import com.lifecyclebot.v4.meta.StrategyTrustAI

/**
 * SmartExitOptimizer — Dynamic exit logic using existing AI signals
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Uses StrategyTrustAI trust scores, FluidLearningAI accuracy, and
 * real-time PnL to dynamically adjust TP/SL for each trade.
 *
 * Goals:
 *   - Lock in gains earlier via trailing stop at +3%
 *   - Tighten SL for low-trust strategies
 *   - Reduce scratch trades by requiring higher confidence when accuracy is low
 *   - All decisions based on existing AI data — no new models needed
 */
object SmartExitOptimizer {

    private const val TAG = "SmartExit"

    // Trailing stop kicks in at this PnL % — locks in gains
    private const val TRAILING_ACTIVATION_PCT = 3.0

    // Once trailing is active, exit if price drops this % from peak
    private const val TRAILING_DISTANCE_PCT = 1.5

    // Base stop-loss range
    private const val BASE_SL_TIGHT = 2.5    // Low trust strategies
    private const val BASE_SL_NORMAL = 4.0   // Normal trust
    private const val BASE_SL_WIDE = 6.0     // High trust strategies (let them breathe)

    /**
     * Get dynamic stop-loss percentage based on strategy trust.
     *
     * High trust (>0.7) → wider SL (give it room)
     * Normal trust (0.4-0.7) → standard SL
     * Low trust (<0.4) → tight SL (cut losses fast)
     */
    fun getDynamicSlPct(tradingMode: String): Double {
        val trust = try {
            StrategyTrustAI.getAllTrustScores()[tradingMode]?.trustScore ?: 0.5
        } catch (_: Exception) { 0.5 }

        return when {
            trust >= 0.7 -> BASE_SL_WIDE
            trust >= 0.4 -> BASE_SL_NORMAL
            else         -> BASE_SL_TIGHT
        }
    }

    /**
     * Check if trailing stop should trigger.
     *
     * @param currentPnlPct  Current PnL percentage
     * @param peakPnlPct     Highest PnL reached during this trade
     * @return Pair(shouldExit, reason) — true + reason if trailing stop hit
     */
    fun checkTrailingStop(currentPnlPct: Double, peakPnlPct: Double): Pair<Boolean, String> {
        // Only activate trailing after reaching activation threshold
        if (peakPnlPct < TRAILING_ACTIVATION_PCT) {
            return Pair(false, "")
        }

        // If price has dropped TRAILING_DISTANCE_PCT from peak, exit
        val dropFromPeak = peakPnlPct - currentPnlPct
        if (dropFromPeak >= TRAILING_DISTANCE_PCT && currentPnlPct > 0.5) {
            return Pair(true, "SMART_TRAIL: peak=${String.format("%.1f", peakPnlPct)}% → now=${String.format("%.1f", currentPnlPct)}% (drop=${String.format("%.1f", dropFromPeak)}%)")
        }

        return Pair(false, "")
    }

    /**
     * Get minimum AI confidence required to open a trade.
     * When accuracy is low, require higher confidence to reduce bad entries.
     *
     * @param currentAccuracy  Current win rate / accuracy (0-100)
     * @return Minimum score threshold (0-100)
     */
    fun getMinConfidenceThreshold(currentAccuracy: Double): Double {
        return when {
            currentAccuracy >= 55.0 -> 40.0   // Good accuracy — standard threshold
            currentAccuracy >= 45.0 -> 50.0   // Moderate — slightly higher bar
            currentAccuracy >= 35.0 -> 60.0   // Below average — be selective
            else                    -> 70.0   // Poor accuracy — only high-conviction trades
        }
    }

    /**
     * Full exit check — combines dynamic SL + trailing stop.
     *
     * @return Pair(shouldExit, reason) — reason is empty string if no exit
     */
    fun shouldExit(
        currentPnlPct: Double,
        peakPnlPct: Double,
        tradingMode: String,
        holdTimeSec: Long
    ): Pair<Boolean, String> {
        // 1. Check trailing stop (locks in gains)
        val (trailExit, trailReason) = checkTrailingStop(currentPnlPct, peakPnlPct)
        if (trailExit) return Pair(true, trailReason)

        // 2. Check dynamic stop-loss (cuts losses based on trust)
        val slPct = getDynamicSlPct(tradingMode)
        if (currentPnlPct <= -slPct) {
            return Pair(true, "SMART_SL: ${String.format("%.1f", currentPnlPct)}% <= -${String.format("%.1f", slPct)}% (trust-adjusted for $tradingMode)")
        }

        // 3. Time-based exit for stuck trades (>30 min, negative PnL, low trust)
        if (holdTimeSec > 1800 && currentPnlPct < -1.0) {
            val trust = try {
                StrategyTrustAI.getAllTrustScores()[tradingMode]?.trustScore ?: 0.5
            } catch (_: Exception) { 0.5 }
            if (trust < 0.4) {
                return Pair(true, "SMART_TIMEOUT: ${holdTimeSec}s held at ${String.format("%.1f", currentPnlPct)}% (low trust ${String.format("%.2f", trust)})")
            }
        }

        return Pair(false, "")
    }
}
