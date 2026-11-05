package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.Candle

/**
 * V5.0.8535 §P1-08 — PROJECT_SNIPER's launch price-change basis was `ts.history.firstOrNull()`: first IN THE LIST, not
 * earliest in time, on a history the feeds merge out of insertion order (LanePlaybook7907 documents it), including
 * synthetic rows; and an empty history became a 0 % surrogate that passed the DUMPING / ALREADY_PUMPED checks.
 *
 * The basis is the earliest DATED, non-synthetic, positive print. With none the basis is UNKNOWN by name and the
 * sniper does not engage on a number nobody observed. Pure.
 */
object SniperPriceBasis8535 {

    const val UNKNOWN_8535 = "PRICE_BASIS_UNKNOWN_8535"

    /** Pure: the earliest real print's price, or null when no dated non-synthetic positive print exists. */
    fun firstTradablePrice8535(history: List<Candle>): Double? =
        history.asSequence()
            .filter { !it.synthetic && it.ts > 0L && it.priceUsd.isFinite() && it.priceUsd > 0.0 }
            .minByOrNull { it.ts }
            ?.priceUsd

    /** Pure: the signed change (%) from the first tradable price to [currentPrice]; null when the basis is unknown. */
    fun changeFromFirstPct8535(history: List<Candle>, currentPrice: Double): Double? {
        val first = firstTradablePrice8535(history) ?: return null
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return null
        return (currentPrice - first) / first * 100.0
    }
}
