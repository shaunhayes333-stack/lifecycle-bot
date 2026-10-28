package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.StrategyTelemetry
import com.lifecyclebot.util.pct
import java.math.BigDecimal
import java.math.BigInteger

/**
 * V5.0.8418 §PAPER_FILL_CORE — the pure money arithmetic of the canonical paper fill (Executor.paperBuy) and paper close
 * (Executor.paperSell), moved here unchanged so a JVM test runs exactly what those two book. Executor calls these; it
 * keeps every gate, ledger, journal and lifecycle step. No singleton, clock or I/O is read here: every input is passed.
 */
object PaperFillCore8418 {

    /**
     * The raw paper quantity for [effectiveSol] at [solUsd] and [priceUsd] (USD per token), encoded at [scale].
     * Exactly the paperBuy rule: the exact BigDecimal economics first; when that yields no positive raw but the economics
     * are valid, the V5.0.6550 fallback encodes qty = notional USD / price at the scale and calls [onFallback] (qty, scale)
     * inside the same try, so a failure there still yields ZERO as before. ZERO = no fill.
     */
    fun buyRaw8418(
        effectiveSol: Double, solUsd: Double, priceUsd: Double, scale: Int,
        onFallback: (Double, Int) -> Unit = { _, _ -> },
    ): BigInteger {
        val economic = try {
            CanonicalRawQuantityAuthority6520.paperRawFromEconomics(
                effectiveSol.toString(), solUsd.toString(), priceUsd.toString(), scale,
            )
        } catch (_: Throwable) { BigInteger.ZERO }
        val economicsValid = effectiveSol > 0.0 && priceUsd > 0.0 &&
            solUsd.isFinite() && solUsd > 0.0 && priceUsd.isFinite()
        if (economic > BigInteger.ZERO || !economicsValid) return economic
        return try {
            val qtyTokens = (effectiveSol * solUsd) / priceUsd
            val s = scale.coerceIn(0, 18)
            val bi = BigDecimal.valueOf(qtyTokens).multiply(BigDecimal.TEN.pow(s)).toBigInteger()
            onFallback(qtyTokens, s)
            bi
        } catch (_: Throwable) { BigInteger.ZERO }
    }

    /** The paper buy's one-time cost debit: venue fee % + the app fee fraction on [actualSol], plus the fixed network leg. */
    fun buyFeeSol8418(actualSol: Double, venueFeePct: Double, appFeeFraction: Double): Double =
        actualSol * (venueFeePct / 100.0 + appFeeFraction) + PaperVenueCost7287.FIXED_SOL_PER_SIDE

    /** The paper close of a terminal lot. [maxValueSol] is the liquidity cap offered (NaN = none). */
    data class Close8418(
        val priceDerivedPnlPct: Double,
        val rawValue: Double,
        val maxValueSol: Double,
        val value: Double,
        val grossNoFrictionValue: Double,
        val simulatedFeeSol: Double,
        val pnl: Double,
        val pnlP: Double,
    ) {
        val liquidityCapped: Boolean get() = maxValueSol.isFinite() && rawValue > maxValueSol
    }

    /**
     * Cost-basis paper proceeds, exactly as paperSell books them: the price return from [entryPrice] to the executable
     * [effectivePrice] (already moved by latency and impact), clamped to -100 %..the learnable ceiling, less [feePct] and
     * the fixed leg, capped at [maxValueSol] (paperSell passes half the pool's USD liquidity in SOL when liquidity and
     * SOL/USD are known, NaN otherwise; that conversion stays in Executor, a sanctioned conversion site).
     */
    fun close8418(
        remainingCostSol: Double, entryPrice: Double, effectivePrice: Double, feePct: Double, maxValueSol: Double,
    ): Close8418 {
        val priceDerivedPnlPct = pct(entryPrice, effectivePrice)
            .coerceIn(-100.0, StrategyTelemetry.LEARNABLE_GAIN_CEILING_PCT_7349)
        val rawValue = (remainingCostSol * (1.0 + priceDerivedPnlPct / 100.0) * (1.0 - feePct / 100.0) -
            PaperVenueCost7287.FIXED_SOL_PER_SIDE).coerceAtLeast(0.0)
        val cappedValue = if (maxValueSol.isFinite() && rawValue > maxValueSol) maxValueSol else rawValue
        val value = cappedValue.coerceAtLeast(0.0)
        val grossNoFrictionValue = (remainingCostSol * (1.0 + priceDerivedPnlPct / 100.0)).coerceAtLeast(0.0)
        val simulatedFeeSol = (grossNoFrictionValue - value).coerceAtLeast(0.0)
        val pnl = value - remainingCostSol
        val pnlP = pct(remainingCostSol, value)
        return Close8418(priceDerivedPnlPct, rawValue, maxValueSol, value, grossNoFrictionValue, simulatedFeeSol, pnl, pnlP)
    }
}
