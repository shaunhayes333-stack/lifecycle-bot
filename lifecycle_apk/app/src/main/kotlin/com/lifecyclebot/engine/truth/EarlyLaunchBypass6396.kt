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
     * V5.0.6396 live-buy entry point. Derives scout tier from
     * SmartMoneyFeed6394 (≥2 whale buys in 60s == HIGH_CONVICTION_EARLY).
     */
    fun evaluateForLiveBuy(
        mint: String,
        liveScore: Double,
        liquidityUsd: Double,
        sameMintAlreadyOpen: Boolean,
        reentryLockout: Boolean,
    ): Decision {
        if (liveScore < ABSOLUTE_MIN_SCORE)
            return Decision(false, 0.0, "BELOW_ABSOLUTE_MIN_6396")
        if (liveScore >= STANDARD_LIVE_SCORE_FLOOR)
            return Decision(false, 1.0, "SCORE_AT_OR_ABOVE_FLOOR_6396")   // caller uses normal path
        if (sameMintAlreadyOpen)
            return Decision(false, 0.0, "SAME_MINT_ALREADY_OPEN_6396")
        if (reentryLockout)
            return Decision(false, 0.0, "REENTRY_LOCKOUT_6396")
        // Score in [ABSOLUTE_MIN, BASELINE) — check smart money.
        val whaleBuys = try { SmartMoneyFeed6394.smartMoneyBuysLast60s(mint) } catch (_: Throwable) { 0 }
        if (whaleBuys < 2)
            return Decision(false, 0.0, "INSUFFICIENT_WHALE_ACTIVITY_6396")
        if (liquidityUsd < 3_000.0)
            return Decision(false, 0.0, "LIQ_BELOW_EXECUTABLE_6396")
        earlyLaunchProbesAuthorized.incrementAndGet()
        return Decision(true, PROBE_SIZE_MULTIPLIER,
            "EARLY_LAUNCH_MICRO_PROBE_6396 whales=$whaleBuys liq=${liquidityUsd.toInt()}")
    }

    internal fun clearForTest() { earlyLaunchProbesAuthorized.set(0L) }
}
