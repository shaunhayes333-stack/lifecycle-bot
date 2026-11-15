package com.lifecyclebot.hyperliquid

import kotlin.math.abs
import kotlin.math.floor

/**
 * V5.0.8797 — turns the brain's weights into positions an account can actually hold, and never one it cannot survive.
 *
 *  - Book size: gross notional = the brain's leverage x the equity on the dexes the book trades, never more than the
 *    owner's budget (maxNotionalUsd).
 *  - Each dex margins its own coins (HIP-3 dexes keep their own balance and margin isolated): a dex's positions may use at
 *    most [MARGIN_USE] of that dex's equity as margin; an unfunded dex holds nothing (the report says which).
 *  - Liquidation guard: a position at exchange leverage L is liquidated by an adverse move of about 1/L less the
 *    maintenance margin (half of 1/maxLeverage). Each coin's leverage is capped so that a [LIQ_SIGMAS]-sigma day (its own
 *    30-day volatility) does not reach it; when the margin needed is more than that allows, the dex's book shrinks.
 *  - Orders: the smallest order that moves a position to its target; below [MIN_ORDER_USD] of difference nothing is sent;
 *    a coin the book no longer wants is closed whole, reduce-only.
 */
object HlRisk {
    private const val MARGIN_USE = 0.8
    private const val LIQ_SIGMAS = 6.0
    private const val MIN_ORDER_USD = 11.0

    data class Plan(val coin: String, val dex: String, val targetUsd: Double, val leverage: Int, val isolated: Boolean)

    /** Pure: the highest exchange leverage at which a [LIQ_SIGMAS]-sigma day does not liquidate a coin of daily [vol]. */
    fun guardLeverage(vol: Double, maxLeverage: Int): Int {
        val maxL = maxOf(1, maxLeverage)
        if (!(vol > 0.0)) return 1
        return floor(1.0 / (LIQ_SIGMAS * vol + 0.5 / maxL)).toInt().coerceIn(1, maxL)
    }

    /**
     * Pure: positions for [weights] (coin -> weight, gross <= 1) at book [leverage], with each dex's equity [equityByDex]
     * ("" = Hyperliquid's own), the owner's [budgetUsd], the markets [assets] and each coin's daily [vol].
     */
    fun plan(weights: Map<String, Double>, leverage: Double, equityByDex: Map<String, Double>, budgetUsd: Double,
             assets: Map<String, HlApi.Asset>, vol: Map<String, Double>): List<Plan> {
        val live = weights.filter { it.value != 0.0 && it.value.isFinite() && assets.containsKey(it.key) }
        val dexes = live.keys.map { assets.getValue(it).dex }.toSet()
        val equity = dexes.sumOf { maxOf(0.0, equityByDex[it] ?: 0.0) }
        val gross = minOf(maxOf(0.0, leverage) * equity, maxOf(0.0, budgetUsd))
        if (!(gross > 0.0)) return emptyList()
        val out = ArrayList<Plan>()
        for (dex in dexes) {
            val coins = live.keys.filter { assets.getValue(it).dex == dex }
            val lev = coins.associateWith { c -> guardLeverage(vol[c] ?: Double.NaN, assets.getValue(c).maxLeverage) }
            val margin = coins.sumOf { c -> abs(live.getValue(c)) * gross / lev.getValue(c) }
            val room = MARGIN_USE * maxOf(0.0, equityByDex[dex] ?: 0.0)
            val scale = if (margin > room) (if (margin > 0.0) room / margin else 0.0) else 1.0
            for (c in coins) {
                val a = assets.getValue(c)
                val usd = live.getValue(c) * gross * scale
                if (abs(usd) > 0.0) out.add(Plan(c, dex, usd, lev.getValue(c), a.onlyIsolated))
            }
        }
        return out
    }

    /** Pure: (isBuy, size, reduceOnly) moving a position of [curSz] units toward [targetUsd] at [mark], or null. */
    fun orderFor(curSz: Double, targetUsd: Double, mark: Double, szDecimals: Int): Triple<Boolean, Double, Boolean>? {
        if (!(mark > 0.0)) return null
        val curUsd = curSz * mark
        if (targetUsd == 0.0) {
            if (curSz == 0.0) return null
            return Triple(curSz < 0.0, abs(curSz), true)                     // close it all, reduce-only
        }
        // a flip goes through flat: first close, the next rebalance opens the other side
        if (curSz != 0.0 && kotlin.math.sign(targetUsd) != kotlin.math.sign(curUsd)) return Triple(curSz < 0.0, abs(curSz), true)
        val delta = targetUsd - curUsd
        if (abs(delta) < MIN_ORDER_USD) return null
        val sz = HlWire.roundSz(abs(delta) / mark, szDecimals)
        if (!(sz > 0.0)) return null
        val reduce = curSz != 0.0 && abs(targetUsd) < abs(curUsd)
        return Triple(delta > 0.0, sz, reduce)
    }

    /** Pure: how far through the mark an IOC order may fill: twice the coin's measured slippage, within 0.3 % to 1 %. */
    fun slipLimit(slipSide: Double): Double = (2.0 * (if (slipSide.isFinite()) slipSide else 0.005) + 0.002).coerceIn(0.003, 0.01)
}
