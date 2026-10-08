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
