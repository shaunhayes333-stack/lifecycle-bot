package com.lifecyclebot.engine.truth

/**
 * V5.0.7277 §A VOTE NEEDS CLOSES BEHIND IT.
 *
 * Operator: "too many brains on too few closes ... learners need hundreds of
 * closes per cell before their vote should move size."
 *
 * On 5.0.7274 the size-moving learners read their evidence at these bars:
 * LaneExpectancyDamper non-neutral from ONE close (n/(n+3)), the regime's
 * own-performance haircut at n/(n+10), UnifiedPolicyHead AUTHORITATIVE at
 * 25 closes with per-lane heads authoritative at 26, AutonomousMetaPolicy
 * ramping to full authority at 5 samples and printing "conv≈0.82" on n=3.
 * Twenty learners retuning on fifteen to forty closes is noise fitting
 * noise, and it is why "fluid" has read as oscillation in every snapshot.
 *
 * One number governs how much a learner may believe its own sample. The
 * curve is unchanged (n / (n + k)); k is what moves, and it moves here for
 * every consumer at once so the layers cannot disagree about what "enough"
 * means. Nothing is disabled: a young learner still votes, at the weight its
 * closes have earned.
 */
object EvidenceMaturity7277 {
    // V5.0.8589 — UNLOCK AT A SMALLER, FLUID BAR. The 7277 bars (30/100/40/10) were raised to stop thin-evidence
    // oscillation, but with the grader producing ~0 graded closes/h (frozen before 8588's 15-min horizon) a head needed
    // 100 closes it could NEVER reach, so the whole adaptive stack sat permanently ADVISORY — dead, not fluid. The unlock
    // is a much smaller target, kept fluid/adaptive by the machinery that already exists: the n/(n+k) weight below (a young
    // head votes at the weight its closes have earned, not all-or-nothing), the Brier-calibration demotion (a head that
    // stops predicting well is pulled back), and the degenerate-input cap (7389, holds a head at ADVISORY while its features
    // are constant/duplicate). Authority is now earned fast AND lost fast if it does not hold — which is what "fluid" means.
    /** Closes a lane needs before its own expectancy is treated as an opinion (half weight). */
    const val LANE_OPINION_CLOSES = 12

    /** Closes a per-cell head needs before it may override the stack (authoritative tier). */
    const val AUTHORITATIVE_CLOSES = 30L

    /** Closes a per-cell head needs before it is more than advisory. */
    const val LEARNED_CLOSES = 15L

    /** Closes a per-cell head needs before it says anything at all. */
    const val ADVISORY_CLOSES = 5L

    /** n / (n + k): 0 at no evidence, half at k closes, asymptotically 1. */
    fun weight(n: Double, k: Double = LANE_OPINION_CLOSES.toDouble()): Double {
        if (!n.isFinite() || n <= 0.0) return 0.0
        val kk = if (k.isFinite() && k > 0.0) k else LANE_OPINION_CLOSES.toDouble()
        return (n / (n + kk)).coerceIn(0.0, 1.0)
    }

    fun weight(n: Int, k: Double = LANE_OPINION_CLOSES.toDouble()): Double = weight(n.toDouble(), k)
}
