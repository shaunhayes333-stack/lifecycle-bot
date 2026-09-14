package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6396 — EARLY LAUNCH BYPASS (rescaled to the canonical 0..30 scale).
 *
 * Successor to EarlyLaunchBypass6394 (which was calibrated to the obsolete
 * 0..100 anchor). On the current effective-score scale a token scoring
 * 12..14 sits below BASELINE=15 but above ABSOLUTE_MIN=12; when
 * SmartMoneyFeed6394 has observed ≥2 whale buys on the mint in the last 60s
 * the trade enters as a 0.30× micro-probe.
 *
 * All hard safety gates (mint/freeze auth, LP, rug, holders) must have
 * already passed upstream — this is score-only bypass.
 */
object EarlyLaunchBypass6396 {

    /** Absolute floor — must equal the authority ABSOLUTE_MIN so no
     *  bypass ever crosses the canonical minimum. */
    const val ABSOLUTE_MIN_SCORE: Double = LiveEntryThresholdAuthority6396.ABSOLUTE_MIN.toDouble()
    /** Standard live score floor for probe-zone gating. */
    const val STANDARD_LIVE_SCORE_FLOOR: Double = LiveEntryThresholdAuthority6396.BASELINE.toDouble()
    /** Micro-probe zone: score band where scout can override the floor. */
    const val PROBE_ZONE_MIN: Double = ABSOLUTE_MIN_SCORE
    const val PROBE_ZONE_MAX: Double = STANDARD_LIVE_SCORE_FLOOR - 0.001

    /** Micro-probe size multiplier — mirrors the 6394 setting. */
    const val PROBE_SIZE_MULTIPLIER: Double = 0.30

    val earlyLaunchProbesAuthorized = AtomicLong(0L)

    data class Decision(val allow: Boolean, val sizeMultiplier: Double, val reason: String)

    /**
     * V5.0.6784 §AUTHORITY_CONSOLIDATION — score-floor bypass is retired.
     *
     * Prior doctrine let a below-floor candidate enter as a 0.30× micro-
     * probe when SmartMoneyFeed6394 saw ≥2 whale buys in 60s. Directive §2
     * ("Lane identity is evidence. Lane identity is not superior to learned
     * outcome truth.") and §7 ("Learning must change the decision, not just
     * size") forbid this pattern: a specialist scout tier cannot override
     * the learned score floor by clamping size to 0.30×.
     *
     * Smart-money observation continues to inform score/features upstream
     * and to fund shadow/counterfactual learners downstream. Canonical
     * capital does NOT execute below the learned floor.
     */
    fun evaluateForLiveBuy(
        mint: String,
        liveScore: Double,
        liquidityUsd: Double,
        sameMintAlreadyOpen: Boolean,
        reentryLockout: Boolean,
    ): Decision {
        if (liveScore >= STANDARD_LIVE_SCORE_FLOOR)
            return Decision(false, 1.0, "SCORE_AT_OR_ABOVE_FLOOR_6396")   // caller uses normal path
        return Decision(false, 0.0, "EARLY_LAUNCH_BYPASS_RETIRED_6784")
    }

    internal fun clearForTest() { earlyLaunchProbesAuthorized.set(0L) }
}
