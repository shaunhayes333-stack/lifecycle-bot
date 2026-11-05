package com.lifecyclebot.v3.scoring

/**
 * V5.0.8537 — the fresh-launch timing BONUS is scaled by the pool it would trade into.
 *
 * 8535 live: V3 gave a +35 IGNITION bonus to a coin with $167 of liquidity, and lane scores built on stacked bonuses like
 * that qualified 98-100 % of candidates. A first-minute impulse in a pool too thin to exit is not an edge: the round trip
 * eats it. The penalty side (POST_PUMP_FADE) is never scaled; only a positive bonus shrinks with thin or unread liquidity.
 */
object LaunchBonusLiquidity8537 {
    const val THIN_USD = 1_000.0
    const val SMALL_USD = 3_000.0
    const val FULL_USD = 10_000.0

    /** Pure: the share of a positive launch bonus a pool of [liquidityUsd] keeps (an unread pool keeps half). */
    fun scale8537(liquidityUsd: Double): Double = when {
        !liquidityUsd.isFinite() || liquidityUsd <= 0.0 -> 0.5
        liquidityUsd < THIN_USD -> 0.0
        liquidityUsd < SMALL_USD -> 1.0 / 3.0
        liquidityUsd < FULL_USD -> 2.0 / 3.0
        else -> 1.0
    }

    /** Pure: [c] with a positive value scaled by [scale8537]; a penalty or null is returned unchanged. */
    fun scaled8537(c: ScoreComponent?, liquidityUsd: Double): ScoreComponent? {
        if (c == null || c.value <= 0) return c
        val k = scale8537(liquidityUsd)
        if (k >= 1.0) return c
        val v = Math.round(c.value * k).toInt()
        return c.copy(value = v, reason = c.reason + " ×liq${"%.2f".format(java.util.Locale.US, k)}(\$${liquidityUsd.toLong()})")
    }
}
