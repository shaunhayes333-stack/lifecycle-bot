/*
 * V5.9.793 — LiquidityClassifier (operator audit Item 5).
 *
 * Operator-stated rule:
 *   "Pump.fun fallback/regEst liquidity is discovery-only unless
 *    Jupiter/Raydium/DexScreener confirms executable pool."
 *
 * This helper classifies the liquidity reading on a TokenState into
 * three operator-named buckets WITHOUT mutating TokenState (so the
 * existing 250-token scan loop stays allocation-stable):
 *
 *   realPoolLiquidityUsd      — confirmed executable pool liquidity.
 *                                Only populated when lastPriceDex /
 *                                lastPriceSource indicates a venue
 *                                where we can actually exit (Raydium,
 *                                Meteora, Orca, Bonk, Jupiter, or a
 *                                DexScreener-sourced quote).
 *   bondingCurveLiquidityEstUsd — pump.fun bonding-curve estimate.
 *                                Discovery-only — never used by FDG
 *                                or the live executor for sizing.
 *   exitCapacityUsd            — the value FDG / Executor must read
 *                                when computing sizing + the
 *                                LIQUIDITY_BELOW_EXECUTION_FLOOR gate.
 *                                Currently == realPoolLiquidityUsd
 *                                (bonding curve never qualifies).
 *
 * Plus a 'bcSimOnly' flag for the canonical-outcome payload so paper
 * trades that closed against a bonding-curve sim are tagged and the
 * production WR aggregator can exclude them (operator audit Item 5
 * acceptance criterion).
 */
package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

object LiquidityClassifier {

    /** Confirmed-pool venues the executor can actually route through. */
    private val REAL_POOL_DEX = setOf(
        "RAYDIUM", "METEORA", "ORCA", "BONK", "JUPITER",
    )

    /** Pricing source tags that imply confirmed pool liquidity. */
    private val REAL_POOL_SOURCES = setOf(
        "DEXSCREENER_WS", "DEXSCREENER_POLL", "DEXSCREENER",
        "RAYDIUM", "RAYDIUM_POOL", "JUPITER", "JUPITER_ULTRA",
        "ORCA", "METEORA", "BONK",
    )

    /** Pricing source tags that imply a pump.fun bonding-curve estimate. */
    private val BONDING_CURVE_SOURCES = setOf(
        "PUMP_FUN_BC", "PUMPFUN_BC", "PUMP_PORTAL_WS", "PUMP_PORTAL",
        "PUMP_PORTAL_SCANNER", "BONDING_CURVE", "PUMP_FUN", "PUMPFUN",
        "PAIR_FALLBACK",
    )

    /**
     * Is the current price reading backed by a venue / source we
     * can actually exit through? Conservative — UNKNOWN dex AND
     * UNKNOWN source returns false even if lastLiquidityUsd > 0.
     */
    fun hasConfirmedExitPool(ts: TokenState): Boolean {
        val dex = ts.lastPriceDex.uppercase()
        val src = ts.lastPriceSource.uppercase()
        return (dex in REAL_POOL_DEX) || (src in REAL_POOL_SOURCES)
    }

    fun isBondingCurveQuote(ts: TokenState): Boolean {
        val dex = ts.lastPriceDex.uppercase()
        val src = ts.lastPriceSource.uppercase()
        // Explicit PUMP_FUN dex label is the strongest signal; otherwise
        // we fall back to the source tag.
        return dex == "PUMP_FUN" || src in BONDING_CURVE_SOURCES
    }

    /** Real (executable) pool liquidity. 0.0 when only a BC estimate exists. */
    fun realPoolLiquidityUsd(ts: TokenState): Double =
        if (hasConfirmedExitPool(ts)) ts.lastLiquidityUsd.coerceAtLeast(0.0) else 0.0

    /** Bonding-curve liquidity estimate. 0.0 when a real pool is confirmed. */
    fun bondingCurveLiquidityEstUsd(ts: TokenState): Double = when {
        hasConfirmedExitPool(ts) -> 0.0
        isBondingCurveQuote(ts) -> ts.lastLiquidityUsd.coerceAtLeast(0.0)
        else -> 0.0
    }

    /**
     * What the FDG / executor MUST consult. Currently identical to
     * realPoolLiquidityUsd because operator policy explicitly forbids
     * pump.fun BC estimates from gating sizing or executability.
     */
    fun exitCapacityUsd(ts: TokenState): Double = realPoolLiquidityUsd(ts)

    /**
     * True when the token has been priced ONLY against a bonding-curve
     * estimate and no confirmed pool quote has ever landed. Tagged on
     * outcomes so the WR aggregator can exclude these from production WR.
     */
    fun isBcSimOnly(ts: TokenState): Boolean {
        return !hasConfirmedExitPool(ts) && isBondingCurveQuote(ts)
    }
}
