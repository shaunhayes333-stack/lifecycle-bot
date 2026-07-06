package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6136 — ExecutionCostBrain.
 *
 * Buy-side bridge for existing ExecutionCostPredictorAI. No network calls, no
 * LLM calls, no hard veto. It converts learned/expected route cost into bounded
 * live execution posture: size drag, minimum buy slippage, max ladder, and urgent
 * tip preference. This closes the gap where cost learning existed but mostly hit
 * reports/score/exit stops instead of the actual buy route.
 */
object ExecutionCostBrain {
    data class BuyPosture(
        val expectedSlipPct: Double,
        val sizeMultiplier: Double,
        val priorityFeeSol: Double,
        val urgentTip: Boolean,
        val pumpSlipPct: Int,
        val minBuySlippageBps: Int,
        val maxBuySlippageBps: Int,
        val reason: String,
    )

    fun buyPosture(ts: TokenState, requestedSol: Double, basePriorityFeeSol: Double = 0.0001): BuyPosture {
        val expectedSlip = try {
            com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.expectedExtraSlipPct(ts.lastLiquidityUsd)
        } catch (_: Throwable) { 0.0 }.coerceIn(0.0, 50.0)
        val lowLiq = ts.lastLiquidityUsd in 1.0..5_000.0
        val veryLowLiq = ts.lastLiquidityUsd in 1.0..1_500.0
        val costDrag = when {
            expectedSlip >= 18.0 -> 0.55
            expectedSlip >= 12.0 -> 0.68
            expectedSlip >= 8.0 -> 0.78
            expectedSlip >= 4.0 -> 0.88
            else -> 1.0
        }
        val liqDrag = when {
            veryLowLiq -> 0.82
            lowLiq -> 0.92
            else -> 1.0
        }
        val sizeMult = (costDrag * liqDrag).coerceIn(0.45, 1.0)
        val urgent = expectedSlip >= 6.0 || lowLiq || requestedSol >= 0.10
        val minSlip = when {
            expectedSlip >= 12.0 -> 500
            expectedSlip >= 6.0 -> 350
            else -> 200
        }
        val maxSlip = when {
            expectedSlip >= 18.0 -> 900
            expectedSlip >= 10.0 -> 750
            else -> 500
        }
        val pumpSlip = when {
            expectedSlip >= 12.0 || veryLowLiq -> 18
            expectedSlip >= 6.0 || lowLiq -> 15
            else -> 10
        }
        val fee = when {
            urgent && expectedSlip >= 12.0 -> maxOf(basePriorityFeeSol, 0.0004)
            urgent -> maxOf(basePriorityFeeSol, 0.00025)
            else -> basePriorityFeeSol
        }
        val reason = when {
            expectedSlip >= 18.0 -> "high_expected_slip_soft_size"
            expectedSlip >= 8.0 -> "moderate_expected_slip_cost_drag"
            veryLowLiq -> "very_low_liq_cost_drag"
            lowLiq -> "low_liq_cost_drag"
            else -> "normal_cost"
        }
        return BuyPosture(expectedSlip, sizeMult, fee, urgent, pumpSlip, minSlip, maxSlip, reason)
    }
}
