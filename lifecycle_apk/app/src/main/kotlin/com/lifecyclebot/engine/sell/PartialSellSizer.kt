package com.lifecyclebot.engine.sell

import java.math.BigInteger

/**
 * V5.9.495z43 — operator spec item C.
 *
 * Computes the safe partial-sell raw amount given:
 *   • intendedFraction (e.g. 0.25 for "take 25%")
 *   • verifiedRemainingRaw (chain-confirmed wallet token balance)
 *   • dustThresholdRaw   (per-mint dust floor)
 *
 * Always clamps the result to verifiedRemainingRaw. NEVER produces a
 * "sell-all" amount when called for a partial. NEVER returns a value
 * below dust.
 *
 * Returns null when the partial would land at or below dust — caller
 * must skip the sell entirely.
 */
object PartialSellSizer {

    data class Result(
        val rawAmount: BigInteger,
        val intendedFraction: Double,
        val verifiedRemainingRaw: BigInteger,
        val dustThresholdRaw: BigInteger,
        val clampedToVerified: Boolean,
    )

    fun size(
        intendedFraction: Double,
        verifiedRemainingRaw: BigInteger,
        dustThresholdRaw: BigInteger = BigInteger.valueOf(1L),
    ): Result? {
        require(intendedFraction in 0.0..1.0) { "fraction out of range: $intendedFraction" }
        if (verifiedRemainingRaw.signum() <= 0) return null
        if (intendedFraction <= 0.0) return null

        // Floor integer math: requested = remaining * floor(fraction*1e6) / 1e6.
        val fractionScaled = (intendedFraction * 1_000_000).toLong().coerceAtLeast(1L)
        var requested = verifiedRemainingRaw
            .multiply(BigInteger.valueOf(fractionScaled))
            .divide(BigInteger.valueOf(1_000_000L))
        var clamped = false
        if (requested > verifiedRemainingRaw) {       // belt-and-braces — should be impossible
            requested = verifiedRemainingRaw
            clamped = true
        }
        if (requested <= dustThresholdRaw) return null
        return Result(
            rawAmount = requested,
            intendedFraction = intendedFraction,
            verifiedRemainingRaw = verifiedRemainingRaw,
            dustThresholdRaw = dustThresholdRaw,
            clampedToVerified = clamped,
        )
    }
}
