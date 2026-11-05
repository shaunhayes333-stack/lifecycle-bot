package com.lifecyclebot.engine.truth

/**
 * V5.0.8535 §P1-06 — the scalp lanes' economic gate (NetEdge8030.laneFitRefusal8054) demanded the coin's REALISED
 * signed last-hour move at 3x the round trip (13 % already moved on a 4.4 % trip) for every member of CASHGEN's and
 * TREASURY's menus. That is the right tradability test for a MOMENTUM scalp and the wrong one for a range-low bounce or a
 * reclaim, which act before the hour turns.
 *
 * Realised movement proves TRADABILITY; the forward executable move proves ENTRY VALUE (CandidateAdmission8136's
 * economics at the quoted size). So:
 *   MOMENTUM  the signed realised hour move must clear 3x cost (unchanged)
 *   RANGE / RECLAIM  the coin must have MOVED 3x cost within the hour in either direction (its dense high-low range), and
 *             the strategy's own forward move still clears the admission's cost bar
 * An unknown setup is a momentum scalp (the stricter test). Pure.
 */
object ScalpStyle8535 {

    enum class Style { RANGE, RECLAIM, MOMENTUM }

    private val RANGE_8535 = setOf(
        "RANGE_LOW_BOUNCE", "RANGE_SUPPORT_SWING", "MEAN_REVERSION_OVERSOLD", "CAPITULATION_HIGHER_LOW", "PEAK_RECLAIM_8383",
        "TREND_DIP_8383", "DIP_IN_UPTREND", "SUPPORT_FLIP", "HIGHER_LOW_PULLBACK", "HIGHER_LOW_CONTINUATION", "FIRST_PULLBACK",
        "FIRST_PULLBACK_AFTER_RUN", "TREND_PULLBACK", "TREND_PULLBACK_8383", "MICRO_PULLBACK_TREND", "MOMENTUM_PULLBACK_5M",
        "PLAN_PULLBACK_RECLAIM", "PLAN_SWEEP_RECLAIM", "SWEEP_RECLAIM_FLOW", "RUNUP_HOLD_8383",
    )
    private val RECLAIM_8535 = setOf(
        "RECLAIM_AFTER_WEAKNESS", "POST_EVENT_RECLAIM", "DISTRIBUTION_RECLAIM", "HL_RECLAIM", "SECOND_WAVE", "SECOND_LEG",
        "POST_IGNITION_RETEST",
    )

    fun of8535(setup: String?): Style = when (setup?.trim()?.uppercase()) {
        null, "" -> Style.MOMENTUM
        in RANGE_8535 -> Style.RANGE
        in RECLAIM_8535 -> Style.RECLAIM
        else -> Style.MOMENTUM
    }

    /** Pure: the realised-movement figure the scalp test reads for [style]: the signed move, or the hour's dense range. */
    fun tradabilityPct8535(style: Style, signedMovePct: Double?, denseRangePct: Double?): Double? =
        if (style == Style.MOMENTUM) signedMovePct else denseRangePct
}
