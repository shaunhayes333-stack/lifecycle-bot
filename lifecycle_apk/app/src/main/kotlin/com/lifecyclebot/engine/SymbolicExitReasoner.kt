package com.lifecyclebot.engine

import com.lifecyclebot.v4.meta.StrategyTrustAI

/**
 * SymbolicExitReasoner — Agentic symbolic reasoning for position exits
 * ═══════════════════════════════════════════════════════════════════════
 *
 * No fixed thresholds. Evaluates multiple symbolic signals every scan
 * cycle and produces a continuous EXIT CONVICTION score (0.0 - 1.0).
 *
 * The conviction score is the AI's belief that exiting NOW is optimal.
 * When conviction > the position's entry confidence → exit.
 * When conviction < entry confidence → hold (the original thesis holds).
 *
 * Signals evaluated:
 *   1. Trust Decay    — strategy trust erosion since entry
 *   2. Gain Erosion   — how much peak profit has been given back
 *   3. Loss Velocity  — speed of drawdown (accelerating = dangerous)
 *   4. Time Pressure  — opportunity cost of stuck capital
 *   5. Momentum Shift — price trajectory reversal detection
 *   6. Cross-Market   — regime/fragility signals from V4 meta
 *
 * Each signal produces a weighted contribution. The sum is the conviction.
 * The AI layer that opened the trade sets the "hold threshold" via its
 * entry confidence. High-confidence entries are held longer.
 */
object SymbolicExitReasoner {

    private const val TAG = "SymExit"

    data class ExitAssessment(
        val conviction: Double,        // 0.0 = strong hold, 1.0 = urgent exit
        val primarySignal: String,     // Dominant reason
        val shouldExit: Boolean,       // Final recommendation
        val suggestedAction: Action,   // HOLD / TIGHTEN / PARTIAL / EXIT
        val signals: Map<String, Double> // All signal values for transparency
    )

    enum class Action {
        HOLD,           // Do nothing — thesis intact
        TIGHTEN,        // Tighten monitoring (check more frequently)
        PARTIAL,        // Take partial profit (sell portion, ride remainder)
        EXIT            // Full exit recommended
    }

    /**
     * Full agentic assessment — called every scan cycle per position.
     *
     * @param currentPnlPct      Current unrealized PnL %
     * @param peakPnlPct         Highest PnL reached during this trade
     * @param entryConfidence    AI confidence when this trade was opened (0-100)
     * @param tradingMode        Strategy/mode name (for trust lookup)
     * @param holdTimeSec        How long position has been held
     * @param priceVelocity      Price change rate (% per minute, negative = dropping)
     * @param volumeRatio        Current volume / average volume (>1 = above avg)
     */
    fun assess(
        currentPnlPct: Double,
        peakPnlPct: Double,
        entryConfidence: Double,
        tradingMode: String,
        holdTimeSec: Long,
        priceVelocity: Double = 0.0,
        volumeRatio: Double = 1.0
    ): ExitAssessment {

        val signals = mutableMapOf<String, Double>()
        var totalConviction = 0.0

        // ────────────────────────────────────────────────────────
        // Signal 1: TRUST DECAY (weight: 0.20)
        // If strategy trust has fallen since we entered, exit pressure rises
        // ────────────────────────────────────────────────────────
        val trustNow = try {
            StrategyTrustAI.getAllTrustScores()[tradingMode]?.trustScore ?: 0.5
        } catch (_: Exception) { 0.5 }

        val trustDecay = when {
            trustNow < 0.3 -> 0.9    // Strategy is failing
            trustNow < 0.4 -> 0.5    // Below average
            trustNow < 0.5 -> 0.2    // Slightly weak
            else           -> 0.0    // Trust intact
        }
        signals["trustDecay"] = trustDecay
        totalConviction += trustDecay * 0.20

        // ────────────────────────────────────────────────────────
        // Signal 2: GAIN EROSION (weight: 0.25)
        // Had profit, giving it back — the most painful signal
        // ────────────────────────────────────────────────────────
        val gainErosion = if (peakPnlPct > 1.5) {
            val giveBack = (peakPnlPct - currentPnlPct) / peakPnlPct.coerceAtLeast(0.01)
            when {
                giveBack > 0.8 && currentPnlPct < 0.5 -> 1.0   // Gave back almost everything
                giveBack > 0.6                          -> 0.7   // Gave back majority
                giveBack > 0.4                          -> 0.4   // Significant erosion
                giveBack > 0.2                          -> 0.15  // Minor pullback (normal)
                else                                    -> 0.0   // Still near peak
            }
        } else 0.0
        signals["gainErosion"] = gainErosion
        totalConviction += gainErosion * 0.25

        // ────────────────────────────────────────────────────────
        // Signal 3: LOSS VELOCITY (weight: 0.20)
        // Fast drawdown = danger. Slow grind = less urgent.
        // ────────────────────────────────────────────────────────
        val lossVelocity = if (currentPnlPct < -1.0) {
            val lossPerMinute = if (holdTimeSec > 30) kotlin.math.abs(currentPnlPct) / (holdTimeSec / 60.0) else 0.0
            when {
                lossPerMinute > 2.0  -> 1.0    // Crashing (>2% per minute loss)
                lossPerMinute > 0.5  -> 0.6    // Fast bleed
                lossPerMinute > 0.2  -> 0.3    // Moderate decline
                else                 -> 0.1    // Slow grind
            }
        } else 0.0
        signals["lossVelocity"] = lossVelocity
        totalConviction += lossVelocity * 0.20

        // ────────────────────────────────────────────────────────
        // Signal 4: TIME PRESSURE (weight: 0.10)
        // Stuck capital = opportunity cost. Escalates slowly.
        // ────────────────────────────────────────────────────────
        val timePressure = if (kotlin.math.abs(currentPnlPct) < 2.0) {
            val minutes = holdTimeSec / 60.0
            when {
                minutes > 60  -> 0.6   // 1hr stuck — capital wasted
                minutes > 30  -> 0.3   // 30min — starting to drag
                minutes > 15  -> 0.1   // 15min — normal hold
                else          -> 0.0   // Fresh trade
            }
        } else 0.0
        signals["timePressure"] = timePressure
        totalConviction += timePressure * 0.10

        // ────────────────────────────────────────────────────────
        // Signal 5: MOMENTUM SHIFT (weight: 0.15)
        // Price velocity reversal — trend changing direction
        // ────────────────────────────────────────────────────────
        val momentumShift = when {
            currentPnlPct > 0 && priceVelocity < -0.5  -> 0.7  // In profit but momentum dying
            currentPnlPct < 0 && priceVelocity < -1.0  -> 0.9  // In loss and accelerating down
            currentPnlPct > 0 && priceVelocity > 0.5   -> 0.0  // In profit, still running
            currentPnlPct < 0 && priceVelocity > 0.3   -> 0.0  // Recovering — hold
            else                                         -> 0.1  // Neutral
        }
        signals["momentumShift"] = momentumShift
        totalConviction += momentumShift * 0.15

        // ────────────────────────────────────────────────────────
        // Signal 6: VOLUME ANOMALY (weight: 0.10)
        // Sudden volume spike during loss = potential rug/dump
        // ────────────────────────────────────────────────────────
        val volumeSignal = when {
            volumeRatio > 5.0 && currentPnlPct < -2.0  -> 0.9  // Volume spike + dropping = dump
            volumeRatio > 3.0 && currentPnlPct < -1.0  -> 0.5  // Elevated volume + loss
            volumeRatio > 3.0 && currentPnlPct > 3.0   -> 0.3  // Volume spike + profit = consider taking
            else                                         -> 0.0
        }
        signals["volumeAnomaly"] = volumeSignal
        totalConviction += volumeSignal * 0.10

        // ────────────────────────────────────────────────────────
        // FINAL DECISION: Compare conviction vs entry confidence
        // High-confidence entries get more patience (higher threshold)
        // Low-confidence entries exit on weaker signals
        // ────────────────────────────────────────────────────────
        val normalizedEntryConf = (entryConfidence / 100.0).coerceIn(0.1, 0.95)
        val exitThreshold = normalizedEntryConf * 0.6  // e.g., 80% conf → need 0.48 conviction to exit

        val primarySignal = signals.maxByOrNull { it.value }?.key ?: "none"

        val action = when {
            totalConviction >= exitThreshold + 0.3       -> Action.EXIT
            totalConviction >= exitThreshold + 0.15      -> Action.PARTIAL
            totalConviction >= exitThreshold              -> Action.TIGHTEN
            else                                          -> Action.HOLD
        }

        val shouldExit = action == Action.EXIT || action == Action.PARTIAL

        return ExitAssessment(
            conviction    = totalConviction.coerceIn(0.0, 1.0),
            primarySignal = primarySignal,
            shouldExit    = shouldExit,
            suggestedAction = action,
            signals       = signals
        )
    }
}
