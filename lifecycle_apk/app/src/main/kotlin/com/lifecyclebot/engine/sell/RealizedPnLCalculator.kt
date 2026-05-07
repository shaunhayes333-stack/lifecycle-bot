package com.lifecyclebot.engine.sell

import java.math.BigInteger
import java.math.RoundingMode

/**
 * V5.9.495z39 — Realized PnL calculator.
 *
 * Operator spec item 4: PnL math must use chain-confirmed accounting only.
 *
 *   proportionalCostBasis = entrySolSpent * (actualConsumedRaw / entryTokenRaw)
 *   realizedPnlSol        = sellSolReceived - proportionalCostBasis - fees
 *   realizedPnlPct        = realizedPnlSol / proportionalCostBasis * 100
 *
 * No cached UI price, no DexScreener stale price, no PumpPortal
 * guessed price, no market-cap-derived value, no wallet SOL delta
 * alone, no re-registered treasury entry.
 *
 * Forensic spec item 6: a sell returning less SOL than the
 * proportional cost basis cannot be logged as profit.
 */
object RealizedPnLCalculator {

    data class Result(
        val proportionalCostBasisSol: Double,
        val realizedPnlSol: Double,
        val realizedPnlPct: Double,
        val isProfit: Boolean,
        val degenerate: Boolean,    // true if inputs were missing / zero — caller should not log
    )

    fun calculate(
        entrySolSpent: Double,
        entryTokenRaw: BigInteger,
        actualConsumedRaw: BigInteger,
        sellSolReceived: Double,
        feesSol: Double = 0.0,
    ): Result {
        if (entrySolSpent <= 0.0 || entryTokenRaw.signum() <= 0 || actualConsumedRaw.signum() <= 0) {
            return Result(0.0, 0.0, 0.0, isProfit = false, degenerate = true)
        }
        val ratio = actualConsumedRaw.toBigDecimal()
            .divide(entryTokenRaw.toBigDecimal(), 18, RoundingMode.HALF_UP)
            .toDouble()
        val proportionalCostBasis = entrySolSpent * ratio
        val realizedPnl = sellSolReceived - proportionalCostBasis - feesSol
        val realizedPct = if (proportionalCostBasis > 0.0)
            realizedPnl / proportionalCostBasis * 100.0 else 0.0
        return Result(
            proportionalCostBasisSol = proportionalCostBasis,
            realizedPnlSol = realizedPnl,
            realizedPnlPct = realizedPct,
            isProfit = realizedPnl > 0.0,
            degenerate = false,
        )
    }
}
