package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6405 §11 — MULTI-HORIZON POSITION MANAGEMENT.
 *
 * Given an open position and current price observation, produce the
 * next EXIT action across three horizons:
 *
 *   • SHORT_TERM  (< 5 min)  — momentum-based partial exits
 *   • MID_TERM    (5-60 min) — trailing stop from peak
 *   • LONG_TERM   (> 60 min) — thesis exit on drawdown from peak
 *
 * Deterministic pure function; caller supplies observations and
 * receives an action code. Same code is fed to executor for both
 * paper and live lanes.
 */
object MultiHorizonHolding6405 {

    enum class Horizon { SHORT_TERM, MID_TERM, LONG_TERM }
    enum class Action { HOLD, PARTIAL_TAKE, TRAILING_STOP, DRAWDOWN_EXIT, STRICT_STOP }

    data class Input(
        val ageMs: Long,
        val entryPriceUsd: Double,
        val currentPriceUsd: Double,
        val peakPriceUsd: Double,
        val strictStopFractionOfEntry: Double,   // e.g. 0.65 = -35% hard stop
        val partialTakeMultiple: Double,         // e.g. 2.0 = +100% take partial
        val trailingStopFractionOfPeak: Double,  // e.g. 0.80 = -20% from peak
        val drawdownExitFractionOfPeak: Double,  // e.g. 0.50 = -50% from peak (long-term)
    )

    fun horizonFor(ageMs: Long): Horizon = when {
        ageMs < 5 * 60_000L -> Horizon.SHORT_TERM
        ageMs < 60 * 60_000L -> Horizon.MID_TERM
        else -> Horizon.LONG_TERM
    }

    fun decide(i: Input): Action {
        if (i.entryPriceUsd <= 0.0 || i.currentPriceUsd <= 0.0) return Action.HOLD

        // Universal strict stop first (protects every horizon).
        if (i.currentPriceUsd <= i.entryPriceUsd * i.strictStopFractionOfEntry) {
            emit("STRICT_STOP", i)
            return Action.STRICT_STOP
        }
        val action = when (horizonFor(i.ageMs)) {
            Horizon.SHORT_TERM -> {
                if (i.currentPriceUsd >= i.entryPriceUsd * i.partialTakeMultiple) Action.PARTIAL_TAKE
                else Action.HOLD
            }
            Horizon.MID_TERM -> {
                if (i.peakPriceUsd > 0.0 &&
                    i.currentPriceUsd <= i.peakPriceUsd * i.trailingStopFractionOfPeak
                ) Action.TRAILING_STOP else Action.HOLD
            }
            Horizon.LONG_TERM -> {
                if (i.peakPriceUsd > 0.0 &&
                    i.currentPriceUsd <= i.peakPriceUsd * i.drawdownExitFractionOfPeak
                ) Action.DRAWDOWN_EXIT else Action.HOLD
            }
        }
        if (action != Action.HOLD) emit(action.name, i)
        return action
    }

    private fun emit(action: String, i: Input) {
        try {
            ForensicLogger.lifecycle(
                "MULTI_HORIZON_ACTION_6405",
                "action=$action ageMs=${i.ageMs} entry=${i.entryPriceUsd} " +
                    "current=${i.currentPriceUsd} peak=${i.peakPriceUsd}",
            )
            PipelineHealthCollector.labelInc("MULTI_HORIZON_${action}_6405")
        } catch (_: Throwable) {}
    }
}
