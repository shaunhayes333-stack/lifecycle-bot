package com.lifecyclebot.engine.market

/**
 * V5.0.8031 — the lane hunters pick what the lane's own scorer can buy.
 *
 * 5.0.8029: DIP_HUNTER saw 1 candidate, PROJECT_SNIPER 6, EXPRESS 28 (3 qualified), MANIPULATED 19 (0). Their hunter
 * profiles picked coins their native scorers refuse: any red hour for DIP (native needs a 15-55% pullback on a 2h+
 * coin with $10k+ liquidity), EXPRESS moves above 30% (native rejects CHASE_EXTENDED), and age 0 — which means
 * "unknown" — passed every age window. Pure filters, in the native terms.
 */
object HunterMandates8031 {
    /** Age is known (> 0) and at most [maxHours]. */
    fun knownAgeWithin8031(ageHours: Double, maxHours: Double): Boolean = ageHours > 0.0 && ageHours <= maxHours

    /** DIP_HUNTER: 2h+, liquidity >= max($10k, 10% of cap), a real pullback, not still falling now. */
    fun dipFits8031(ageHours: Double, liqUsd: Double, mcapUsd: Double, h1: Double, h24: Double, m5: Double): Boolean {
        if (ageHours < 2.0 || liqUsd < maxOf(10_000.0, 0.10 * mcapUsd)) return false
        val dayPullback = h24.isFinite() && h24 in -55.0..-15.0
        val hourPullback = h1.isFinite() && h1 in -35.0..-5.0
        val notFalling = !m5.isFinite() || m5 >= -1.0
        return (dayPullback || hourPullback) && notFalling
    }

    /** DIP_HUNTER rank: closest to a ~25% pullback scores highest (0..5). */
    fun dipRank8031(h1: Double, h24: Double): Double {
        val depth = maxOf(if (h24.isFinite()) -h24 else 0.0, if (h1.isFinite()) -h1 else 0.0)
        return (5.0 - kotlin.math.abs(depth - 25.0) / 5.0).coerceAtLeast(0.0)
    }

    /** EXPRESS: known age >= 5 min, 1h continuation 1-30%, 5m (when known) up 0.5-12%. */
    fun expressFits8031(ageHours: Double, h1: Double, m5: Double): Boolean =
        ageHours >= 5.0 / 60.0 && h1 > 1.0 && h1 <= 30.0 && (!m5.isFinite() || m5 in 0.5..12.0)
}
