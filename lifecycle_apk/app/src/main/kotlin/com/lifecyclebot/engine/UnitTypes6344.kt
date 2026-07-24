package com.lifecyclebot.engine

/**
 * V5.0.6344 — STRONG UNIT TYPES (operator P0-3 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpt):
 *   "Strong Unit Types.
 *    SolAmount / UsdAmount / TokenQuantity / PriceSolPerToken /
 *    PriceUsdPerToken. Any '(exitPriceUsd - entryPriceUsd) * tokenQty'
 *    computation across units must fail to compile. Cross-unit arithmetic
 *    is only legal when explicit conversion is applied."
 *
 * IMPLEMENTATION NOTES
 *   • Inline value classes (Kotlin `@JvmInline`) give zero-runtime-cost
 *     nominal typing on the JVM — the underlying primitive is a Double,
 *     but the compiler treats every unit as a distinct type.
 *   • Because the whole codebase was written against raw `Double` values
 *     for years, we DO NOT retrofit every call site in a single push.
 *     Instead we introduce the types here plus a small set of safe,
 *     explicit converters. New surfaces (FillLotLedger6344,
 *     RealizedPnlConduit6344, PreEntryDecisionRecord6346) consume the
 *     strong types from day one; legacy call sites are migrated in
 *     future patches.
 *   • The `unwrap()` helper is deliberately verbose so migration diffs
 *     are obvious and can be code-reviewed row by row.
 *
 * INVARIANTS
 *   • No unit type accepts NaN / -Infinity / +Infinity — those are
 *     replaced with 0.0 and a health counter is incremented so the
 *     data-integrity fault is visible without blowing up the pipeline.
 *   • Zero and negative amounts are legal (they represent fees, losses,
 *     or dust). Only non-finite values are sanitised.
 *   • No unit-to-unit implicit arithmetic. If you want dollars from SOL
 *     you must call [SolAmount.toUsd] with an explicit SOL/USD price.
 */

@JvmInline
value class SolAmount(val lamportsAsSol: Double) {
    fun unwrap(): Double = lamportsAsSol
    operator fun plus(other: SolAmount): SolAmount = SolAmount(lamportsAsSol + other.lamportsAsSol)
    operator fun minus(other: SolAmount): SolAmount = SolAmount(lamportsAsSol - other.lamportsAsSol)
    fun toUsd(solPriceUsd: Double): UsdAmount =
        if (!solPriceUsd.isFinite() || solPriceUsd <= 0.0) UsdAmount(0.0)
        else UsdAmount(lamportsAsSol * solPriceUsd)

    companion object {
        val ZERO = SolAmount(0.0)
        fun of(value: Double): SolAmount =
            if (!value.isFinite()) {
                try { PipelineHealthCollector.labelInc("UNIT_SOL_NON_FINITE_6344") } catch (_: Throwable) {}
                ZERO
            } else SolAmount(value)
    }
}

@JvmInline
value class UsdAmount(val dollars: Double) {
    fun unwrap(): Double = dollars
    operator fun plus(other: UsdAmount): UsdAmount = UsdAmount(dollars + other.dollars)
    operator fun minus(other: UsdAmount): UsdAmount = UsdAmount(dollars - other.dollars)
    fun toSol(solPriceUsd: Double): SolAmount =
        if (!solPriceUsd.isFinite() || solPriceUsd <= 0.0) SolAmount.ZERO
        else SolAmount(dollars / solPriceUsd)

    companion object {
        val ZERO = UsdAmount(0.0)
        fun of(value: Double): UsdAmount =
            if (!value.isFinite()) {
                try { PipelineHealthCollector.labelInc("UNIT_USD_NON_FINITE_6344") } catch (_: Throwable) {}
                ZERO
            } else UsdAmount(value)
    }
}

@JvmInline
value class TokenQuantity(val amount: Double) {
    fun unwrap(): Double = amount
    operator fun plus(other: TokenQuantity): TokenQuantity = TokenQuantity(amount + other.amount)
    operator fun minus(other: TokenQuantity): TokenQuantity = TokenQuantity(amount - other.amount)

    companion object {
        val ZERO = TokenQuantity(0.0)
        fun of(value: Double): TokenQuantity =
            if (!value.isFinite()) {
                try { PipelineHealthCollector.labelInc("UNIT_TOKENQTY_NON_FINITE_6344") } catch (_: Throwable) {}
                ZERO
            } else TokenQuantity(value)
    }
}

@JvmInline
value class PriceSolPerToken(val value: Double) {
    fun unwrap(): Double = value
    /** SOL cost of a given token quantity at this price. Explicit conversion. */
    fun costOf(qty: TokenQuantity): SolAmount = SolAmount(value * qty.amount)
    /** Convert to USD/token using an explicit SOL/USD price. */
    fun toUsdPrice(solPriceUsd: Double): PriceUsdPerToken =
        if (!solPriceUsd.isFinite() || solPriceUsd <= 0.0) PriceUsdPerToken.ZERO
        else PriceUsdPerToken(value * solPriceUsd)

    companion object {
        val ZERO = PriceSolPerToken(0.0)
        fun of(value: Double): PriceSolPerToken =
            if (!value.isFinite()) {
                try { PipelineHealthCollector.labelInc("UNIT_PX_SOL_NON_FINITE_6344") } catch (_: Throwable) {}
                ZERO
            } else PriceSolPerToken(value)
        /** Derive from cost / qty; enforces the parity invariant used by 6343. */
        fun derive(cost: SolAmount, qty: TokenQuantity): PriceSolPerToken =
            if (qty.amount > 0.0) PriceSolPerToken(cost.lamportsAsSol / qty.amount)
            else ZERO
    }
}

@JvmInline
value class PriceUsdPerToken(val value: Double) {
    fun unwrap(): Double = value
    fun toSolPrice(solPriceUsd: Double): PriceSolPerToken =
        if (!solPriceUsd.isFinite() || solPriceUsd <= 0.0) PriceSolPerToken.ZERO
        else PriceSolPerToken(value / solPriceUsd)

    companion object {
        val ZERO = PriceUsdPerToken(0.0)
        fun of(value: Double): PriceUsdPerToken =
            if (!value.isFinite()) {
                try { PipelineHealthCollector.labelInc("UNIT_PX_USD_NON_FINITE_6344") } catch (_: Throwable) {}
                ZERO
            } else PriceUsdPerToken(value)
    }
}
