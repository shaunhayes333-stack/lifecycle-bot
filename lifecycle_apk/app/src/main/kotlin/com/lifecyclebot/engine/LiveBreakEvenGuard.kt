package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/** V5.0.3965 — live entry round-trip break-even authority. */
object LiveBreakEvenGuard {
    data class Result(
        val expectedEdgePct: Double,
        val requiredEdgePct: Double,
        val pass: Boolean,
        val reason: String,
    )

    fun check(
        ts: TokenState,
        lane: String,
        style: String,
        score: Double,
        buySlippageBps: Int,
        sizeSol: Double,
    ): Result {
        val canonLane = BleederMemoryRouter.canon(lane.ifBlank { style })
        val expectedEdge = expectedEdgePct(canonLane, score)
        val required = requiredEdgePct(ts, canonLane, style, buySlippageBps, sizeSol, score)
        return Result(expectedEdge, required, expectedEdge > required, if (expectedEdge > required) "EDGE_CLEARS_COST" else "EDGE_BELOW_ROUNDTRIP_COST")
    }

    fun expectedEdgePct(lane: String, score: Double): Double {
        val canon = BleederMemoryRouter.canon(lane)
        return try {
            val m = StrategyTelemetry.computeLeaderboard().firstOrNull { it.strategy.equals(canon, true) }
            if (m != null && m.trades >= 5) maxOf(m.pfExpectancyPp, m.meanPnlPct, m.avgWinPct * (m.winRatePct / 100.0))
            else ((score - 40.0) * 1.25).coerceIn(0.0, 60.0)
        } catch (_: Throwable) { ((score - 40.0) * 1.25).coerceIn(0.0, 60.0) }
    }

    fun requiredEdgePct(ts: TokenState, lane: String, style: String, buySlippageBps: Int, sizeSol: Double, score: Double): Double {
        val buySlippagePct = (buySlippageBps.coerceAtLeast(0) / 100.0).coerceIn(0.0, 20.0)
        val expectedSellSlippagePct = try {
            com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.expectedExtraSlipPct(ts.lastLiquidityUsd)
        } catch (_: Throwable) { when {
            ts.lastLiquidityUsd < 5_000.0 -> 8.0
            ts.lastLiquidityUsd < 20_000.0 -> 5.0
            else -> 2.0
        } }.coerceIn(0.0, 15.0)
        val priorityFeePct = if (sizeSol > 0.0) (0.0008 / sizeSol * 100.0).coerceIn(0.0, 6.0) else 6.0
        val platformFeePct = 1.0 // 0.5% buy + 0.5% sell
        val spreadPct = when {
            ts.lastLiquidityUsd < 5_000.0 -> 4.0
            ts.lastLiquidityUsd < 20_000.0 -> 2.0
            else -> 1.0
        }
        val mevBufferPct = 1.5
        val givebackBufferPct = when (BleederMemoryRouter.canon(lane)) {
            "SHITCOIN", "EXPRESS", "CYCLIC", "COPYTRADE" -> 5.0
            "MOONSHOT" -> 4.0
            else -> 2.5
        }
        val minProfitBufferPct = when {
            lane.contains("BLUECHIP", true) -> 3.0
            lane.contains("PRESALE", true) || lane.contains("SNIPER", true) -> 5.0
            lane.contains("MOONSHOT", true) && score >= 61.0 -> 8.0
            lane.contains("SHITCOIN", true) -> 12.0
            lane.contains("EXPRESS", true) || lane.contains("CYCLIC", true) -> 15.0
            lane.contains("WHALE", true) || lane.contains("COPY", true) -> 15.0
            else -> 8.0
        }
        return buySlippagePct + expectedSellSlippagePct + priorityFeePct + platformFeePct + spreadPct + mevBufferPct + givebackBufferPct + minProfitBufferPct
    }
}
