package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6405 §13 — ENTRY TIMING GATE.
 *
 * Deterministic classifier that turns a candidate observation into
 * one of three timings:
 *   • EARLY      — accepted; buy immediately
 *   • WAIT       — signal is fine but liquidity/mcap/age is off;
 *                  come back on the next tick with fresh data
 *   • REJECTED   — the candidate should not be traded from this
 *                  cycle; a hard reject reason is emitted
 *
 * Inputs are pre-computed by upstream scorers so this gate stays
 * pure and unit-testable.
 */
object EntryTimingGate6405 {

    enum class Timing { EARLY, WAIT, REJECTED }

    data class Signal(
        val scoreZeroToOne: Double,       // 0..1 aggregated score
        val liquidityUsd: Double,
        val marketCapUsd: Double,
        val ageMs: Long,
        val minLiquidityUsd: Double,
        val minMarketCapUsd: Double,
        val maxAgeMs: Long,
        val earlyScoreThreshold: Double,  // e.g. 0.75
        val waitScoreThreshold: Double,   // e.g. 0.55
    )

    fun classify(s: Signal): Pair<Timing, String> {
        if (!s.scoreZeroToOne.isFinite() || s.scoreZeroToOne < 0.0 || s.scoreZeroToOne > 1.0) {
            emit("SCORE_OUT_OF_RANGE")
            return Timing.REJECTED to "SCORE_OUT_OF_RANGE"
        }
        if (s.liquidityUsd < s.minLiquidityUsd) {
            emit("LIQUIDITY_BELOW_MIN")
            return Timing.REJECTED to "LIQUIDITY_BELOW_MIN"
        }
        if (s.marketCapUsd < s.minMarketCapUsd) {
            emit("MARKETCAP_BELOW_MIN")
            return Timing.REJECTED to "MARKETCAP_BELOW_MIN"
        }
        if (s.ageMs > s.maxAgeMs) {
            emit("AGE_ABOVE_MAX")
            return Timing.REJECTED to "AGE_ABOVE_MAX"
        }
        if (s.scoreZeroToOne >= s.earlyScoreThreshold) {
            emit("EARLY")
            return Timing.EARLY to "OK"
        }
        if (s.scoreZeroToOne >= s.waitScoreThreshold) {
            emit("WAIT")
            return Timing.WAIT to "WAIT_SCORE_MARGINAL"
        }
        emit("SCORE_BELOW_WAIT")
        return Timing.REJECTED to "SCORE_BELOW_WAIT"
    }

    private fun emit(tag: String) {
        try {
            ForensicLogger.lifecycle("ENTRY_TIMING_${tag}_6405", "reason=$tag")
            PipelineHealthCollector.labelInc("ENTRY_TIMING_${tag}_6405")
        } catch (_: Throwable) {}
    }
}
