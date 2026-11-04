package com.lifecyclebot.perps.strategy

/**
 * V5.0.8535 §C15 — the specialist plan's TP/SL becomes the executed exit contract for METALS and COMMODITIES.
 *
 * `MetalsStrategy` / `CommoditiesStrategy` calibrate TP/SL by class and conviction, but the traders priced every exit
 * from the generic FluidLearning / SmartExitOptimiser blend and never read `setup.tpPct` / `setup.slPct`: the entry
 * thesis and the exit contract were two different strategies. The plan now owns the exit, the learned fluid
 * multipliers still shape it, and a swap-cost floor bounds it: these lanes trade tokenised spot through Jupiter swaps,
 * where a round trip costs roughly 0.3–0.6 % in fees and slippage, so a stop inside that band is a fee bleed and a
 * target inside it cannot net out.
 */
object SpecialistPlanExit8535 {
    /** Round-trip swap cost band for tokenised spot (fees + slippage, both legs). */
    const val SWAP_ROUND_TRIP_PCT = 0.6
    private const val TP_FLOOR_PCT = SWAP_ROUND_TRIP_PCT * 1.6   // the target must net out after both legs
    private const val SL_FLOOR_PCT = SWAP_ROUND_TRIP_PCT          // a stop inside the cost band is noise + fees

    fun tpPct(planTpPct: Double, learnedMult: Double): Double =
        (planTpPct * learnedMult.takeIf { it.isFinite() && it > 0.0 }.let { it ?: 1.0 }).coerceAtLeast(TP_FLOOR_PCT)

    fun slPct(planSlPct: Double, learnedMult: Double): Double =
        (planSlPct * learnedMult.takeIf { it.isFinite() && it > 0.0 }.let { it ?: 1.0 }).coerceAtLeast(SL_FLOOR_PCT)
}
