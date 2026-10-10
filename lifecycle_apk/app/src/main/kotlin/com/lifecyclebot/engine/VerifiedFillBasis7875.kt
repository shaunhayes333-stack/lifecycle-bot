package com.lifecyclebot.engine

/**
 * V5.0.7875 — pure: the USD/token price a verified fill proves (SOL spent per
 * token received, at the SOL/USD rate), or null when any input is unusable.
 * The SOL/USD band matches the other sanity bands in the tree.
 */
internal fun verifiedFillPriceUsd7875(actualCostSol: Double, qtyUi: Double, solUsd: Double): Double? {
    if (!actualCostSol.isFinite() || actualCostSol <= 0.0) return null
    if (!qtyUi.isFinite() || qtyUi <= 0.0) return null
    if (!solUsd.isFinite() || solUsd <= 20.0 || solUsd >= 5_000.0) return null
    val px = actualCostSol / qtyUi * solUsd
    return px.takeIf { it.isFinite() && it > 0.0 }
}

/** Pure. V5.0.7988 — the verified fill replaces the decision mark when it is 25%..20x above it (a chased fill, not a unit error). */
internal fun fillOverridesMark7988(markUsd: Double, fillUsd: Double): Boolean {
    if (!markUsd.isFinite() || markUsd <= 0.0 || !fillUsd.isFinite() || fillUsd <= 0.0) return false
    val r = fillUsd / markUsd
    return r >= 1.25 && r <= 20.0
}
