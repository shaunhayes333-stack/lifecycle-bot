package com.lifecyclebot.perps.crypto.brain

import com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic

/**
 * V5.0.8535 §C11 — the crypto tactic shapes with the buyer reading's provenance as an input.
 *
 * `scoreDynamicCrypto7244` received `buyPressurePct` as a bare double: a provider row with zero buy and sell counts
 * arrived as the neutral default 50.0, and `MEAN_REVERT` (`bp >= 50`) accepted it as a confirmed buyer reversal whenever
 * the 20 s desk tape showed a bounce. The caller tested `cryptoInputsUnobserved8236` only on the non-actionable branch, so
 * the synthetic 50 was never caught before `actionableLong`. Here every buyer gate requires OBSERVED buyers; with none, a
 * price shape may still fire as an explicitly labelled PRICE-ONLY tactic that the caller grades down
 * ([PRICE_ONLY_CONF_PENALTY]) and names in the decision reason. MOMENTUM is a buyer tactic and has no price-only form.
 */
object CryptoTacticEvidence8535 {

    /** Confidence taken from a decision whose tactic fired on price structure alone (buyers unobserved). */
    const val PRICE_ONLY_CONF_PENALTY = 6

    data class Shape(
        val momentum: Boolean,
        val pullback: Boolean,
        val breakout: Boolean,
        val meanRevert: Boolean,
        val pullbackPct: Double,
        val reboundPct: Double,
        /** True when a shape fired without an observed buyer reading. */
        val priceOnly: Boolean,
    ) {
        fun forTactic(t: Tactic): Boolean = when (t) {
            Tactic.MOMENTUM -> momentum
            Tactic.PULLBACK -> pullback
            Tactic.BREAKOUT -> breakout
            Tactic.MEAN_REVERT -> meanRevert
            Tactic.LAB_PROPOSED -> pullback || breakout || meanRevert
        }

        /** The predicate that actually fired, for outcome attribution (§C09) when the cell holds LAB_PROPOSED. */
        fun fired(): Tactic? = when {
            pullback -> Tactic.PULLBACK
            breakout -> Tactic.BREAKOUT
            meanRevert -> Tactic.MEAN_REVERT
            momentum -> Tactic.MOMENTUM
            else -> null
        }
    }

    /**
     * Pure. [prices] is the recent desk tape (oldest first); [fallbackPrice] stands in when the tape is empty.
     * [buyersObserved] is false when the provider row carried no buy/sell counts, whatever [buyPressurePct] says.
     */
    fun evaluate(
        prices: List<Double>,
        fallbackPrice: Double,
        buyPressurePct: Double,
        buyersObserved: Boolean,
        change24hPct: Double,
        isTrending: Boolean,
    ): Shape {
        val px = prices.filter { it.isFinite() && it > 0.0 }.takeLast(8)
        val cur = px.lastOrNull() ?: fallbackPrice.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val prior = px.dropLast(1)
        val priorHigh = prior.maxOrNull() ?: cur
        val priorLow = prior.minOrNull() ?: cur
        val pullbackPct = if (priorHigh > 0.0 && cur > 0.0) ((priorHigh - cur) / priorHigh * 100.0).coerceAtLeast(0.0) else 0.0
        val reboundPct = if (priorLow > 0.0 && cur > 0.0) ((cur - priorLow) / priorLow * 100.0).coerceAtLeast(0.0) else 0.0
        val notExtended = change24hPct <= 20.0 && change24hPct >= -8.0
        fun buyers(min: Double) = buyersObserved && buyPressurePct >= min

        val momentum = notExtended && (
            buyers(56.0) ||
                (change24hPct in 0.5..8.0 && buyers(52.0)) ||
                (isTrending && buyers(58.0))
            )
        val pullbackPrice = px.size >= 4 && pullbackPct in 2.0..12.0 && reboundPct >= 1.0 && notExtended
        val breakoutPrice = px.size >= 4 && priorHigh > 0.0 && cur >= priorHigh * 1.005 && notExtended
        val meanRevertPrice = px.size >= 4 && pullbackPct in 4.0..18.0 && reboundPct >= 2.0 && change24hPct < 12.0
        val pullback = pullbackPrice && (buyers(52.0) || !buyersObserved)
        val breakout = breakoutPrice && (buyers(54.0) || !buyersObserved)
        val meanRevert = meanRevertPrice && (buyers(50.0) || !buyersObserved)
        val priceOnly = !buyersObserved && (pullback || breakout || meanRevert)
        return Shape(momentum, pullback, breakout, meanRevert, pullbackPct, reboundPct, priceOnly)
    }
}
