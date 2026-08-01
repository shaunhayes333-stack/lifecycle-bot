package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger

/**
 * V5.0.6405 §9 — COMPOUNDING ENGINE.
 *
 * Given realised PnL from a completed position lifetime, produce the
 * next-position sizing recommendation. Deterministic, pure function
 * so paper and live compound identically.
 *
 * Rules
 * ─────
 *   • Base position size is the caller's configured baseLamports.
 *   • On a winning lifetime, next base becomes
 *       base + realisedLamports * compoundFraction
 *     capped at maxLamports.
 *   • On a losing lifetime, next base stays flat (no revenge sizing).
 *   • compoundFraction is bounded to [0.0, 1.0]; a caller passing an
 *     out-of-range value gets clamped, not silently accepted.
 */
object CompoundingEngine6405 {

    data class Input(
        val currentBaseLamports: BigInteger,
        val realisedLamports: BigInteger,
        val compoundFraction: Double,
        val maxLamports: BigInteger,
    )

    fun nextBase(i: Input): BigInteger {
        val frac = i.compoundFraction.coerceIn(0.0, 1.0)
        if (i.realisedLamports.signum() <= 0) return i.currentBaseLamports.min(i.maxLamports)
        val addend = java.math.BigDecimal(i.realisedLamports)
            .multiply(java.math.BigDecimal(frac.toString()))
            .setScale(0, java.math.RoundingMode.DOWN)
            .toBigInteger()
        val proposed = i.currentBaseLamports.add(addend)
        val bounded = if (proposed > i.maxLamports) i.maxLamports else proposed
        try {
            ForensicLogger.lifecycle(
                "COMPOUNDING_STEP_6405",
                "base=${i.currentBaseLamports} realised=${i.realisedLamports} frac=$frac " +
                    "proposed=$proposed capped=$bounded",
            )
            PipelineHealthCollector.labelInc("COMPOUNDING_STEP_6405")
        } catch (_: Throwable) {}
        return bounded
    }
}
