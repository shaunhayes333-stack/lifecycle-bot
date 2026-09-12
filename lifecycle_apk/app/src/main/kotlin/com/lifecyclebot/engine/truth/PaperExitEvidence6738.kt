package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.atomic.AtomicReference

/** No network on the settlement path and no entry-price, cached-display or stop-reason fallback. */
object PaperExitEvidence6738 {
    data class Fx(val usdPerSol: Double, val source: String, val timestampMs: Long)
    data class Settlement(val grossSol: Double, val proceedsSol: Double, val frictionSol: Double, val pnlSol: Double)
    private val solUsd = AtomicReference<Fx?>(null)
    private val sources = setOf("COINGECKO_SOL_USD", "JUPITER_SOL_USD", "PYTH_SOL_USD", "DEXSCREENER_SOL_USD")

    fun observeSolUsd(price: Double, source: String, timestampMs: Long,
                      nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!price.isFinite() || price <= 0.0 || source !in sources ||
            timestampMs <= 0L || nowMs - timestampMs !in -5_000L..120_000L) return false
        solUsd.updateAndGet { prior ->
            if (prior != null && prior.timestampMs > timestampMs) prior else Fx(price, source, timestampMs)
        }
        return true
    }
    fun freshFx(nowMs: Long = System.currentTimeMillis()): Fx? = solUsd.get()?.takeIf {
        nowMs - it.timestampMs in -5_000L..120_000L
    }
    fun freshMark(mint: String, nowMs: Long = System.currentTimeMillis()): CanonicalPriceMark6522? =
        CanonicalMarkPurpose6570.values().mapNotNull {
            CanonicalPriceMarkRegistry6522.getFresh6734(mint, it, nowMs)
        }.maxByOrNull { it.timestampMs }

    /** Sold quantity and basis come from the immutable canonical lot, never TokenState. */
    fun settle(quantity: BigDecimal, costSol: Double, priceUsd: Double, fx: Fx,
               slippagePct: Double, feePct: Double, liquidityUsd: Double,
               nowMs: Long = System.currentTimeMillis()): Settlement? {
        if (quantity.signum() <= 0 || !costSol.isFinite() || costSol <= 0.0 ||
            !priceUsd.isFinite() || priceUsd <= 0.0 || !fx.usdPerSol.isFinite() || fx.usdPerSol <= 0.0 ||
            fx.source !in sources || fx.timestampMs <= 0L || nowMs - fx.timestampMs !in -5_000L..120_000L ||
            !slippagePct.isFinite() || slippagePct !in 0.0..99.0 ||
            !feePct.isFinite() || feePct !in 0.0..99.0 || !liquidityUsd.isFinite() || liquidityUsd <= 0.0) return null
        val gross = quantity.multiply(BigDecimal.valueOf(priceUsd))
            .divide(BigDecimal.valueOf(fx.usdPerSol), MathContext.DECIMAL128).toDouble()
        if (!gross.isFinite() || gross <= 0.0) return null
        val net = minOf(gross * (1.0 - slippagePct / 100.0) * (1.0 - feePct / 100.0),
            liquidityUsd * 0.5 / fx.usdPerSol)
        if (!net.isFinite() || net < 0.0) return null
        return Settlement(gross, net, gross - net, net - costSol)
    }
    internal fun resetForTest() { solUsd.set(null) }
}
