package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6394 — EARLY LAUNCH BYPASS.
 *
 * Operator concern: a live score floor of 55 gatekeeps early-launch tokens
 * (the 46K-MC / minutes-old class where 10,000x runners are born). This
 * module lets a very fresh token with HIGH_CONVICTION_EARLY scout verdict
 * enter as a strictly-sized micro-probe even when its live score is 40-54.
 *
 * HARD RULES (from V5.0.6393-K + directive):
 *   * NEVER bypass hard safety checks
 *   * NEVER bypass mint/freeze authority checks
 *   * NEVER bypass LP / rug / denylist / holder proofs
 *   * Score < 40 is still blocked (too speculative even for probes)
 *   * Micro-probe size = 0.25-0.35 of normal calculated size
 *   * Maximum 2 concurrent probes (see ProbationEntryLimiter6388)
 */
object EarlyLaunchBypass6394 {
    /** Absolute floor — score below this remains blocked regardless of scout. */
    const val ABSOLUTE_MIN_SCORE: Double = 40.0
    /** Standard live score floor. */
    const val STANDARD_LIVE_SCORE_FLOOR: Double = 55.0
    /** Micro-probe zone: score band where scout can override the floor. */
    const val PROBE_ZONE_MIN: Double = 40.0
    const val PROBE_ZONE_MAX: Double = 54.999

    data class Decision(val allow: Boolean, val sizeMultiplier: Double, val reason: String)

    /**
     * Returns whether a candidate below the live floor may enter as a micro-probe.
     *
     * @param liveScore the candidate's current live score
     * @param scoutTier the EarlyEntryScout6390 tier verdict
     * @param hardSafetyPassed all hard safety checks (mint/freeze/LP/rug/holder) passed
     * @param mintPairResolved mint and executable pair are fully resolved
     * @param freshLiquidityProof current liquidity proof is fresh
     * @param sellQuoteable a live sell quote is available
     * @param sameMintOpen a position for this mint is already open
     * @param reentryLockout re-entry lockout is active
     */
    fun evaluate(
        liveScore: Double,
        scoutTier: EarlyEntryScout6390.Tier,
        hardSafetyPassed: Boolean,
        mintPairResolved: Boolean,
        freshLiquidityProof: Boolean,
        sellQuoteable: Boolean,
        sameMintOpen: Boolean,
        reentryLockout: Boolean,
    ): Decision {
        // Score at or above floor -> normal path handles it (no bypass needed).
        if (liveScore >= STANDARD_LIVE_SCORE_FLOOR)
            return Decision(true, 1.0, "SCORE_AT_OR_ABOVE_FLOOR")

        // Below absolute floor -> BLOCK.
        if (liveScore < ABSOLUTE_MIN_SCORE)
            return Decision(false, 0.0, "SCORE_BELOW_ABSOLUTE_MIN_${ABSOLUTE_MIN_SCORE}")

        // Score 40-54: bypass ONLY when scout says HIGH_CONVICTION_EARLY.
        if (scoutTier != EarlyEntryScout6390.Tier.HIGH_CONVICTION_EARLY)
            return Decision(false, 0.0, "SCOUT_TIER_${scoutTier}_INSUFFICIENT_FOR_BYPASS")

        // Hard safety and integrity gates are NEVER bypassed.
        if (!hardSafetyPassed) return Decision(false, 0.0, "HARD_SAFETY_FAILED")
        if (!mintPairResolved) return Decision(false, 0.0, "MINT_PAIR_UNRESOLVED")
        if (!freshLiquidityProof) return Decision(false, 0.0, "STALE_LIQUIDITY_PROOF")
        if (!sellQuoteable) return Decision(false, 0.0, "NOT_SELL_QUOTEABLE")
        if (sameMintOpen) return Decision(false, 0.0, "SAME_MINT_ALREADY_OPEN")
        if (reentryLockout) return Decision(false, 0.0, "REENTRY_LOCKOUT_ACTIVE")

        // All gates pass. Size clamped to micro-probe zone (V5.0.6393-K).
        val sizeMult = when {
            liveScore >= 50.0 -> 0.35   // top of probe zone
            liveScore >= 45.0 -> 0.30
            else -> 0.25                 // 40-44
        }
        earlyLaunchProbesAuthorized.incrementAndGet()
        return Decision(true, sizeMult,
            "EARLY_LAUNCH_MICRO_PROBE_SCOUT_HIGH_CONVICTION_SCORE_${liveScore.toInt()}")
    }

    val earlyLaunchProbesAuthorized = AtomicLong(0L)
    internal fun clearForTest() { earlyLaunchProbesAuthorized.set(0L) }
}
