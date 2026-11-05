package com.lifecyclebot.engine

/**
 * V5.0.8539 — owner: "everything is meant to be fluid, adjustable and learnt and unlearnt as the stack adjusts."
 *
 * A lane's liquidity floor was a fixed number (and LIVE never below $500). 5.0.8538's run: five of the six runners it saw
 * (+310 % to +103,094 %) were pump.fun curve launches read at $0-$419 of depth and refused or deferred on that floor. The
 * floor is now a prior the lane's own forward labels can open and close:
 *   - every judged coin's label is also filed under lane x liquidity band at the decision (ForwardReturnLabeler7731 LIQB|),
 *   - a band whose record is proven to pay (30+ labels, mean - SE > 0) OPENS the floor for coins in it, and
 *   - a band proven to lose (30+ labels, mean + SE < 0) CLOSES it, even above the floor.
 * Unread depth is never "low": it still defers. Rug / freeze / mint authority / Token-2022 / sellability are not here.
 */
object LearnedLiquidityFloor8539 {
    const val MIN_N = 30
    private val EDGES_USD = doubleArrayOf(500.0, 2_000.0, 10_000.0, 50_000.0)

    enum class Call { OPEN, CLOSE, NONE }

    /** Pure: the band name of [liqUsd] ("L0" unread, "L1" under $500 ... "L5" $50k and up). */
    fun band(liqUsd: Double): String {
        if (!liqUsd.isFinite() || liqUsd <= 0.0) return "L0"
        for ((i, e) in EDGES_USD.withIndex()) if (liqUsd < e) return "L${i + 1}"
        return "L${EDGES_USD.size + 1}"
    }

    /** Pure: what the band's own forward record says about the floor. */
    fun call(n: Int, meanPct: Double, sePct: Double): Call = when {
        n < MIN_N || !meanPct.isFinite() || !sePct.isFinite() -> Call.NONE
        meanPct - sePct > 0.0 -> Call.OPEN
        meanPct + sePct < 0.0 -> Call.CLOSE
        else -> Call.NONE
    }
}
