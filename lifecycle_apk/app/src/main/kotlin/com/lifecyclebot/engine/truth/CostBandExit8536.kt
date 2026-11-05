package com.lifecyclebot.engine.truth

/**
 * V5.0.8536 — no discretionary exit sells inside the cost band.
 *
 * 5.0.8535 live: two of five losses were the round trip and nothing else — QUALITY AjZ8JF rotated out at -3.4 % after
 * 4 minutes (OPPORTUNITY_ROTATION_8098) and CASHGEN 7F8Bkf sold at -4 % after 3 minutes (RAPID_FLUID_STOP), on a 0.032 SOL
 * ticket whose round trip costs 4.95 % (break-even move 5.06 %). A fluid stop tighter than the cost plus normal noise
 * sells the fee; a rotation of a young position still inside that band pays the fee twice for no information.
 *
 *  - [stopFloorPct]: the fluid / dynamic stop is never tighter than -(round trip + [NOISE_PCT]). It only WIDENS a stop
 *    that sits inside the band; a wider stop, the hard floor, the catastrophe stops and rug / safety exits are unchanged.
 *  - [rotationHolds]: a position younger than [YOUNG_MS] whose P&L is still inside the band is not a rotation victim.
 * An unknown cost changes nothing (no invented number). Pure.
 */
object CostBandExit8536 {

    const val NOISE_PCT = 3.0
    const val YOUNG_MS = 10L * 60_000L

    /** The band's lower edge (negative %), or null when the round trip is unknown. */
    fun bandPct(roundTripCostPct: Double): Double? =
        roundTripCostPct.takeIf { it.isFinite() && it > 0.0 }?.let { -(it + NOISE_PCT) }

    /** The stop to use: [stopPct] widened to the band's edge when it sits inside the band (never tightened). */
    fun stopFloorPct(stopPct: Double, roundTripCostPct: Double): Double {
        val band = bandPct(roundTripCostPct) ?: return stopPct
        return if (stopPct.isFinite() && stopPct < 0.0 && stopPct > band) band else stopPct
    }

    /** True when a young position is still inside the cost band: rotating it out would only realise the fee. */
    fun rotationHolds(pnlPct: Double, ageMs: Long, roundTripCostPct: Double): Boolean {
        val band = bandPct(roundTripCostPct) ?: return false
        return ageMs in 0L until YOUNG_MS && pnlPct.isFinite() && pnlPct > band && pnlPct < roundTripCostPct
    }
}
