package com.lifecyclebot.engine

/**
 * V5.9.443 — CHOP FILTER
 *
 * User report (Feb 2026): log dump showed 82% of trades were
 * chop/stopout patterns —
 *   MID_STOPPED_OUT  n=127 avg -2.8% in 1.37min
 *   MID_FLAT_CHOP    n=122 avg -1.7% in 38min
 *   BAD_FAKE_PRESSURE n=23 avg -22%
 *   BAD_RUG           n=14 avg -12%
 * Blended WR was 9%, Sharpe -10.8. Most entries were DEX_BOOSTED
 * tokens in the early_unknown / pre_pump phase getting knifed to
 * -2% within 1 min.
 *
 * This filter raises the entry-score bar for that exact combination.
 * When applied to ShitCoin + Moonshot evaluate() callsites it adds
 * a +10 score requirement, keeping the bot out of the chop pool
 * without blocking higher-quality opportunities from the same source.
 *
 * Other lanes (Quality, Manipulated, CashGen, Express, Sniper) are
 * intentionally NOT chop-filtered — they're quiet right now and
 * the user wants them exploring more.
 */
object ChopFilter {

    private const val TAG = "ChopFilter"

    /** Sources that statistically produce chop entries. */
    private val CHOP_SOURCES = setOf(
        "DEX_BOOSTED",
        "DEX_TRENDING",
    )

    /** Phases where price direction is still unresolved. */
    private val CHOP_PHASES = setOf(
        "early_unknown",
        "pre_pump",
        "unknown",
        "scanning",
        "idle",
    )

    /**
     * V5.9.444 — FLUID CHOP PENALTY.
     *
     * Scales the extra score required for entering a (chop-source, chop-phase)
     * candidate based on:
     *   1. FluidLearningAI.learningProgress — a mature brain has tighter
     *      discipline; a bootstrapping one keeps exploring.
     *   2. Global meme win rate (via FluidLearningAI).
     *
     * Range: 5 (bootstrap, low WR) .. 15 (mature, good WR)
     * Default fallback: 10.
     */
    fun chopPenalty(): Int {
        return try {
            val progress = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
            val base = 5.0 + progress * 10.0
            base.toInt().coerceIn(5, 15)
        } catch (_: Exception) {
            10
        }
    }

    /** Static default kept for callers that don't yet query the fluid version. */
    const val CHOP_SCORE_PENALTY = 10

    /**
     * @return true when the (source, phase) combo is chop-prone and the
     *         candidate score is below `minScore + CHOP_SCORE_PENALTY`.
     *         Fail-open if source or phase is null/empty.
     */
    fun shouldRejectAsChop(
        source: String?,
        phase: String?,
        score: Int,
        minScore: Int,
    ): Boolean {
        if (source.isNullOrBlank() || phase.isNullOrBlank()) return false
        if (source.uppercase() !in CHOP_SOURCES) return false
        if (phase.lowercase() !in CHOP_PHASES) return false
        return score < minScore + CHOP_SCORE_PENALTY
    }

    /**
     * V5.9.444 — FLUID EARLY-DEATH STOP threshold.
     *
     * Returns the pnl% cutoff under which a position should be killed
     * inside the first 60 seconds. Reads live per-lane evidence from
     * HoldDurationTracker — if the lane's 0-1min bucket has a proven
     * negative mean with ≥100 samples, tighten the cutoff toward that
     * mean (half-way) so we catch the knife even earlier. Otherwise
     * fall back to -1.5% (matches V5.9.443 static behaviour).
     *
     * Returned value is always in [-3.0, -0.5]. Never cuts winners.
     */
    fun earlyDeathCutoffPct(layer: String): Double {
        val fallback = -1.5
        return try {
            val mean = HoldDurationTracker.bucketMean(layer, 0L)  // 0..1 min bucket
            if (mean == null) fallback
            else {
                val tuned = (fallback + mean) / 2.0
                tuned.coerceIn(-3.0, -0.5)
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
