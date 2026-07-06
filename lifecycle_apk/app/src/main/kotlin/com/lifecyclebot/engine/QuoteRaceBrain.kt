package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6135 — QuoteRaceBrain.
 *
 * Central execution-cognition layer for instant green-candle capture. This brain
 * does NOT call network/LLM, does NOT sign/broadcast, and does NOT veto trades.
 * It turns already-local token state + route reliability into an execution
 * posture: urgent tip, pump slip, Jupiter ladder, and telemetry labels.
 *
 * AATE doctrine:
 * - race quote/route posture only; never race broadcasts
 * - one wallet mutex, one signed transaction
 * - missing/neutral data degrades to conservative defaults
 * - failures become learnable execution attribution, not strategy PnL poison
 */
object QuoteRaceBrain {
    data class Posture(
        val enabled: Boolean,
        val reason: String,
        val greenCandlePct: Double,
        val tickAgeMs: Long,
        val priorityFeeSol: Double,
        val urgentTip: Boolean,
        val pumpSlipPct: Int,
        val minBuySlippageBps: Int,
        val maxBuySlippageBps: Int,
    )

    fun evaluate(ts: TokenState, lane: String, basePriorityFeeSol: Double = 0.0001): Posture {
        val now = System.currentTimeMillis()
        val laneUpper = lane.uppercase().ifBlank { ts.position.tradingMode.uppercase().ifBlank { "STANDARD" } }
        val latest = try { ts.history.lastOrNull() } catch (_: Throwable) { null }
        val greenPct = try {
            val open = latest?.openUsd ?: 0.0
            val close = latest?.priceUsd ?: ts.lastPrice
            if (open > 0.0 && close > 0.0) ((close - open) / open) * 100.0 else 0.0
        } catch (_: Throwable) { 0.0 }
        val tickAge = if (ts.lastPriceUpdate > 0L) now - ts.lastPriceUpdate else Long.MAX_VALUE
        val freshTick = tickAge in 0L..2_500L
        val trunkLane = laneUpper == "STANDARD" || laneUpper == "CORE" || laneUpper == "V3"
        val momentum = try { ts.momentum ?: 0.0 } catch (_: Throwable) { 0.0 }
        val buyPressure = try { ts.lastBuyPressurePct } catch (_: Throwable) { 50.0 }
        val routeReliability = try { ExecutionRouteReliabilityMemory.sizeMultiplierForSource(ts.source, ts.mint) } catch (_: Throwable) { 1.0 }
        val strongLocalImpulse = greenPct >= 8.0 || momentum >= 12.0 || buyPressure >= 68.0
        val routeHealthyEnough = routeReliability >= 0.74
        val enabled = trunkLane && freshTick && ts.entryScore >= 65.0 && strongLocalImpulse && routeHealthyEnough
        val reason = when {
            !trunkLane -> "non_trunk_lane"
            !freshTick -> "stale_tick"
            ts.entryScore < 65.0 -> "score_below_65"
            !strongLocalImpulse -> "no_green_impulse"
            !routeHealthyEnough -> "route_reliability_soft_damped"
            greenPct >= 8.0 -> "green_candle"
            momentum >= 12.0 -> "momentum_impulse"
            buyPressure >= 68.0 -> "buy_pressure_impulse"
            else -> "enabled"
        }
        return if (enabled) {
            Posture(
                enabled = true,
                reason = reason,
                greenCandlePct = greenPct,
                tickAgeMs = tickAge,
                priorityFeeSol = maxOf(basePriorityFeeSol, 0.0003),
                urgentTip = true,
                pumpSlipPct = 15,
                minBuySlippageBps = 350,
                maxBuySlippageBps = 750,
            )
        } else {
            Posture(
                enabled = false,
                reason = reason,
                greenCandlePct = greenPct,
                tickAgeMs = tickAge,
                priorityFeeSol = basePriorityFeeSol,
                urgentTip = false,
                pumpSlipPct = 10,
                minBuySlippageBps = 200,
                maxBuySlippageBps = 500,
            )
        }
    }

    fun recordBuyOutcome(route: String, success: Boolean, reason: String, elapsedMs: Long, mint: String = "", symbol: String = "") {
        val routeKey = route.uppercase().ifBlank { "UNKNOWN_ROUTE" }
        try {
            PipelineHealthCollector.labelInc(if (success) "QUOTE_RACE_ROUTE_SUCCESS_6135|$routeKey" else "QUOTE_RACE_ROUTE_FAIL_6135|$routeKey")
        } catch (_: Throwable) {}
        if (!success) {
            try { ExecutionRouteReliabilityMemory.recordFailure(routeKey, reason.ifBlank { "UNKNOWN_ROUTE_FAILURE" }, mint) } catch (_: Throwable) {}
        }
        try {
            ForensicLogger.lifecycle(
                if (success) "QUOTE_RACE_ROUTE_SUCCESS_6135" else "QUOTE_RACE_ROUTE_FAIL_6135",
                "route=$routeKey mint=${mint.take(10)} symbol=$symbol elapsedMs=$elapsedMs reason=${reason.take(100)} success=$success soft_attribution_only=true",
            )
        } catch (_: Throwable) {}
    }
}
