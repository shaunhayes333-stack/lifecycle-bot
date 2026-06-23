package com.lifecyclebot.engine

/**
 * RunnerProtection — V5.0.4106 (Wave F of P0 sell-failure patch)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0 patch §12: 'Do not full close good tokens too early. Once a
 * token has been +25% or more, prevent full close on weak/soft signals.
 * Use trailing stop instead.'
 *
 * Live evidence (V5.0.4105 screenshots): USAIDS +99.8M%, FRAUDSTERS +99.6M%,
 * CITY +26M% — bot is holding pump.fun runners but the partial ladder
 * needs to bank the gains progressively while leaving moonbag.
 *
 * Doctrine:
 *   • Pure helper — never touches the live broadcast layer. Sell paths call
 *     in to ask 'should I full-close?' and 'what partial fraction now?'.
 *   • Soft-shape only — emergency exits (rug/dev-dump/liquidity-removed/
 *     manual) ALWAYS bypass these guards via emergency-override list.
 *   • Stateless — caller passes peakPnlPct + currentPnlPct + reason.
 *   • Operator partial schedule: +35→sell 20%, +75→sell 20%, +150→sell 25%,
 *     +300→sell 25%. Keep ≥25% moonbag. Trail-from-peak 25-30%.
 */
object RunnerProtection {

    const val RUNNER_THRESHOLD_PCT: Double = 25.0
    const val TRAIL_FROM_PEAK_PCT: Double = 28.0          // mid 25-30% spec
    const val TIGHTER_TRAIL_AFTER_PCT: Double = 200.0     // tighten past +200%
    const val TIGHTER_TRAIL_PCT: Double = 18.0            // tightened trail

    enum class PartialTier(val triggerPct: Double, val sellFraction: Double, val tag: String) {
        TIER_1(35.0, 0.20, "RUNNER_PARTIAL_T1_35PCT"),
        TIER_2(75.0, 0.20, "RUNNER_PARTIAL_T2_75PCT"),
        TIER_3(150.0, 0.25, "RUNNER_PARTIAL_T3_150PCT"),
        TIER_4(300.0, 0.25, "RUNNER_PARTIAL_T4_300PCT"),
    }

    data class PartialPlan(
        val tier: PartialTier,
        val fractionToSell: Double,
        val reason: String,
    )

    /** True once the position has ever printed +25% peak. */
    fun isRunner(peakPnlPct: Double): Boolean = peakPnlPct >= RUNNER_THRESHOLD_PCT

    /** True if a full-close attempt should be DOWNGRADED to a partial because
     *  the position is a runner and the reason isn't an emergency override. */
    fun shouldBlockFullExit(peakPnlPct: Double, reason: String): Boolean {
        if (!isRunner(peakPnlPct)) return false
        return !isEmergencyOverride(reason)
    }

    /** Emergency reasons that ALWAYS bypass runner protection (operator §12). */
    fun isEmergencyOverride(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("RUG") ||
               r.contains("HONEYPOT") ||
               r.contains("DEV_DUMP") ||
               r.contains("DEV_SELL") ||
               r.contains("LIQUIDITY_REMOVED") ||
               r.contains("MANUAL") ||
               r.contains("SHUTDOWN") ||
               r.contains("WALLET_DRAIN") ||
               r.contains("RAPID_CATASTROPHE") ||
               // Trail-stop-hit IS an emergency-class "real" exit when the
               // peak has already retraced ≥ TRAIL_FROM_PEAK_PCT — caller is
               // expected to verify trail-distance before flagging.
               r.contains("TRAIL_STOP_CONFIRMED")
    }

    /** Determine the highest-tier partial that should be banked given the
     *  current peak. `lastBankedTier` is the tier already executed (0 = none). */
    fun nextPartialTier(currentPeakPnlPct: Double, lastBankedTierOrdinal: Int): PartialPlan? {
        for (tier in PartialTier.values().reversed()) {
            if (tier.ordinal <= lastBankedTierOrdinal) continue
            if (currentPeakPnlPct >= tier.triggerPct) {
                return PartialPlan(
                    tier = tier,
                    fractionToSell = tier.sellFraction,
                    reason = tier.tag,
                )
            }
        }
        return null
    }

    /** Trailing-stop distance from peak — tightens past +200% to lock in. */
    fun trailDistancePct(peakPnlPct: Double): Double {
        return if (peakPnlPct >= TIGHTER_TRAIL_AFTER_PCT) TIGHTER_TRAIL_PCT
               else                                       TRAIL_FROM_PEAK_PCT
    }

    /** True when current PnL has retraced ≥ trailDistance from peak. */
    fun isTrailStopHit(peakPnlPct: Double, currentPnlPct: Double): Boolean {
        if (peakPnlPct < RUNNER_THRESHOLD_PCT) return false
        val drawdownFromPeak = peakPnlPct - currentPnlPct
        return drawdownFromPeak >= trailDistancePct(peakPnlPct)
    }
}
