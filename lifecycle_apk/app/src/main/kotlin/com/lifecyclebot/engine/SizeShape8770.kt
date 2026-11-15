package com.lifecyclebot.engine

/**
 * V5.0.8770 §LEARNED_SIZE_IS_ONE_BOUNDED_SHAPE — code audit (learning 3): a lane's learned soft-size multipliers were
 * multiplied in sequence and floored (ShitCoinTraderAI: danger bucket 0.3 x score-expectancy 0.35 x ... then the same
 * score bucket again as calibrationSizeMult 0.25), so 0.3 x 0.35 x 0.25 ~ 0.026 hit the 0.01 / 0.02 SOL floor and the floor
 * WAS the size: every learner's opinion was erased. The learned product is now one shape, bounded in log space the way
 * FinalDecisionGate's canonical notional (6552) bounds its own: [MIN_8770, MAX_8770] of the lane's base size.
 */
object SizeShape8770 {
    const val MIN_8770 = 0.35
    const val MAX_8770 = 1.50

    /** Pure: the bounded shape of a learned multiplier [product] (1.0 when it is not a finite positive number). */
    fun bounded8770(product: Double): Double =
        if (!product.isFinite() || product <= 0.0) 1.0
        else kotlin.math.exp(kotlin.math.ln(product).coerceIn(kotlin.math.ln(MIN_8770), kotlin.math.ln(MAX_8770)))
}
