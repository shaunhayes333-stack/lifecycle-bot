package com.lifecyclebot.engine

import java.math.BigDecimal
import java.math.BigInteger

/**
 * V5.0.6324 — INTEGER TOKEN ACCOUNTING (operator hotfix §2).
 *
 * Stores token quantities as raw integer units + decimals, per the
 * SPL Token model. Every display conversion goes through this value
 * class so downstream code never reconstructs a raw amount by
 * multiplying an already-rounded display quantity.
 *
 * Invariants (validated by helper):
 *   • rawAmount >= 0
 *   • decimals >= 0
 *   • displayQuantity = rawAmount / 10^decimals
 *   • decimals are stable per mint (enforced by CanonicalPositionRegistry)
 */
@JvmInline
value class RawTokenAmount(val packed: String) {
    // packed format: "rawAmount|decimals" so the value class stays JVM-inline-safe.
    companion object {
        fun of(raw: BigInteger, decimals: Int): RawTokenAmount {
            require(raw.signum() >= 0) { "raw amount must be non-negative: $raw" }
            require(decimals >= 0) { "decimals must be non-negative: $decimals" }
            return RawTokenAmount("$raw|$decimals")
        }
        fun ofDisplaySafe(display: Double, decimals: Int): RawTokenAmount {
            require(display >= 0.0 && display.isFinite()) { "display must be finite non-negative: $display" }
            require(decimals >= 0) { "decimals must be non-negative: $decimals" }
            val raw = BigDecimal(display).movePointRight(decimals).toBigInteger()
            return of(raw, decimals)
        }
        fun zero(decimals: Int): RawTokenAmount = of(BigInteger.ZERO, decimals)
    }

    val raw: BigInteger get() = BigInteger(packed.substringBefore('|'))
    val decimals: Int get() = packed.substringAfter('|').toInt()

    fun displayQuantity(): Double =
        BigDecimal(raw).movePointLeft(decimals).toDouble()

    fun plus(other: RawTokenAmount): RawTokenAmount {
        require(decimals == other.decimals) { "decimal mismatch: $decimals vs ${other.decimals}" }
        return of(raw.add(other.raw), decimals)
    }
    fun minus(other: RawTokenAmount): RawTokenAmount {
        require(decimals == other.decimals) { "decimal mismatch: $decimals vs ${other.decimals}" }
        val diff = raw.subtract(other.raw)
        require(diff.signum() >= 0) { "underflow: $raw - ${other.raw}" }
        return of(diff, decimals)
    }
    fun isZero(): Boolean = raw.signum() == 0
    fun isPositive(): Boolean = raw.signum() > 0
}
