package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Unit conversions only. No ledger, cached price, fallback fill, or learning state. */
object PaperFillMath6737 {
    private val precision = MathContext.DECIMAL128

    fun tokens(raw: BigInteger, scale: Int): BigDecimal? =
        if (raw.signum() < 0 || scale !in 0..18) null else raw.toBigDecimal().movePointLeft(scale)

    /** Quantize DOWN: a paper order cannot acquire more tokens than its paid notional. */
    fun quantityFromCost(costSol: Double, entryPriceUsd: Double, solPriceUsd: Double, scale: Int): BigInteger? {
        if (!positive(costSol) || !positive(entryPriceUsd) || !positive(solPriceUsd) || scale !in 0..18) return null
        return try {
            BigDecimal.valueOf(costSol).multiply(BigDecimal.valueOf(solPriceUsd), precision)
                .divide(BigDecimal.valueOf(entryPriceUsd), precision).movePointRight(scale)
                .setScale(0, RoundingMode.DOWN).toBigIntegerExact().takeIf { it.signum() > 0 }
        } catch (_: ArithmeticException) { null }
    }

    /** SOL/token is derived from an executed principal and its exact filled quantity. */
    fun priceSol(costSol: Double, raw: BigInteger, scale: Int): Double? {
        val quantity = tokens(raw, scale)?.takeIf { it.signum() > 0 } ?: return null
        if (!positive(costSol)) return null
        return finitePositive(BigDecimal.valueOf(costSol).divide(quantity, precision).toDouble())
    }

    /** The exit quote is USD/token; SOL/USD is captured once with that fill. */
    fun grossProceeds(raw: BigInteger, scale: Int, exitPriceUsd: Double, solPriceUsd: Double): Double? {
        val quantity = tokens(raw, scale)?.takeIf { it.signum() > 0 } ?: return null
        if (!positive(exitPriceUsd) || !positive(solPriceUsd)) return null
        return finitePositive(quantity.multiply(BigDecimal.valueOf(exitPriceUsd), precision)
            .divide(BigDecimal.valueOf(solPriceUsd), precision).toDouble())
    }

    /** Caps round down; minimums round up. The minimum never creates a risk budget. */
    fun boundedNotional(riskSol: Double, cashAfterFeeReserveSol: Double, laneCapSol: Double, minimumSol: Double): Double {
        if (!positive(riskSol) || !positive(cashAfterFeeReserveSol) || !positive(laneCapSol) || !positive(minimumSol)) return 0.0
        val cap = listOf(riskSol, cashAfterFeeReserveSol, laneCapSol).minOf {
            BigDecimal.valueOf(it).movePointRight(9).setScale(0, RoundingMode.DOWN)
        }
        val minimum = BigDecimal.valueOf(minimumSol).movePointRight(9).setScale(0, RoundingMode.UP)
        return if (cap >= minimum) cap.movePointLeft(9).toDouble() else 0.0
    }

    private fun positive(value: Double) = value.isFinite() && value > 0.0
    private fun finitePositive(value: Double): Double? = value.takeIf { positive(it) }
}
