package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** Compares each fill with its own contemporaneous reference in SOL. Market movement is not friction. */
object TradeCostMeasurement8090 {
    data class Sale(val raw: BigInteger, val netSol: Double, val markUsd: Double, val solUsd: Double)
    data class Measurement(val costPct: Double, val buyPct: Double)

    fun measure(spentSol: Double, raw: BigInteger, decimals: Int, entryMarkUsd: Double,
                entrySolUsd: Double, sales: List<Sale>): Measurement? {
        fun valid(x: Double) = x.isFinite() && x > 0.0
        if (!valid(spentSol) || raw.signum() <= 0 || decimals !in 0..255 ||
            !valid(entryMarkUsd) || !valid(entrySolUsd) || sales.isEmpty()) return null
        if (sales.fold(BigInteger.ZERO) { a, s -> a + s.raw } != raw) return null
        val mc = MathContext.DECIMAL128
        fun marketSol(q: BigInteger, usd: Double, solUsd: Double): BigDecimal =
            BigDecimal(q, decimals).multiply(BigDecimal.valueOf(usd), mc).divide(BigDecimal.valueOf(solUsd), mc)
        val spent = BigDecimal.valueOf(spentSol)
        val entryValue = marketSol(raw, entryMarkUsd, entrySolUsd)
        val entryFriction = spent.subtract(entryValue)
        var totalFriction = entryFriction
        for (sale in sales) {
            if (sale.raw.signum() <= 0 || !valid(sale.markUsd) || !valid(sale.solUsd) ||
                !sale.netSol.isFinite() || sale.netSol < 0.0) return null
            val exitFriction = marketSol(sale.raw, sale.markUsd, sale.solUsd).subtract(BigDecimal.valueOf(sale.netSol))
            totalFriction = totalFriction.add(exitFriction)
        }
        val cost = totalFriction.divide(spent, mc).multiply(BigDecimal(100)).toDouble()
        val buy = entryFriction.divide(entryValue, mc).multiply(BigDecimal(100)).toDouble()
        return if (cost.isFinite() && buy.isFinite() && cost in -60.0..60.0 && buy in -60.0..60.0)
            Measurement(cost, buy) else null
    }
}
