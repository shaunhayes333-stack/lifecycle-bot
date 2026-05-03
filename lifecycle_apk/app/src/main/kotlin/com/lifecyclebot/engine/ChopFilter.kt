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

    /** Score bump required when (source, phase) combo is chop-prone. */
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
}
