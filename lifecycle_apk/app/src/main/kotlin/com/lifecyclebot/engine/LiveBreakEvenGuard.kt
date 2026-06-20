package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade

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
        val scorePrior = ((score - 40.0) * 1.25).coerceIn(0.0, 45.0)
        val leaderboardEdge = try {
            val m = StrategyTelemetry.computeLiveTerminalLeaderboard().firstOrNull { it.strategy.equals(canon, true) }
            // StrategyTelemetry is useful context but may include partial/paper-heavy
            // rows. Cap its authority so it cannot override live terminal bleed.
            if (m != null && m.trades >= 8 && m.totalSolPnl > 0.0 && m.winRatePct >= 45.0)
                maxOf(m.pfExpectancyPp, m.meanPnlPct, m.avgWinPct * (m.winRatePct / 100.0)).coerceAtMost(60.0)
            else 0.0
        } catch (_: Throwable) { 0.0 }
        val liveTerminalEdge = try {
            val aliases = aliasesFor(canon)
            val rows = TradeHistoryStore.getRecentValidClosedTrades(limit = 1_500, includePartials = false)
                .filter { it.side.equals("SELL", true) }
                .filter { it.mode.equals("live", true) || it.tradingMode.equals("live", true) || !it.mode.equals("paper", true) }
                .filter { aliases.contains(BleederMemoryRouter.canon(it.tradingMode.ifBlank { it.reason })) }
                .takeLast(150)
            edgeFromRows(rows, minRows = 5, minWr = 45.0, minNetSol = 0.0, cap = 140.0)
        } catch (_: Throwable) { 0.0 }
        val paperAdvisoryEdge = try {
            val aliases = aliasesFor(canon)
            val rows = TradeHistoryStore.getRecentValidClosedTrades(limit = 2_000, includePartials = false)
                .filter { it.side.equals("SELL", true) }
                .filter { it.mode.equals("paper", true) }
                .filter { aliases.contains(BleederMemoryRouter.canon(it.tradingMode.ifBlank { it.reason })) }
                .takeLast(250)
            edgeFromRows(rows, minRows = 25, minWr = 52.0, minNetSol = 0.0, cap = 35.0)
        } catch (_: Throwable) { 0.0 }
        // V5.0.3972 — LIVE TRUST REBASE.
        // Paper memory can suggest, never dominate. If live has no positive
        // terminal confirmation, only a small score/leaderboard prior survives;
        // if live is positive, paper may add a capped boost. This prevents stale
        // paper/outlier winners from authorizing live entries against a toxic live
        // bucket while preserving the useful winner base.
        return if (liveTerminalEdge > 0.0) {
            maxOf(scorePrior, leaderboardEdge, liveTerminalEdge + (paperAdvisoryEdge * 0.35)).coerceIn(0.0, 180.0)
        } else {
            maxOf(scorePrior, minOf(leaderboardEdge, 25.0), minOf(paperAdvisoryEdge, 15.0)).coerceIn(0.0, 45.0)
        }
    }

    private fun aliasesFor(canon: String): Set<String> = when (canon) {
        "LIQUIDITY_DEPTH_QUALITY" -> setOf("BLUECHIP", "PRESALE_SNIPE", "MOONSHOT", "WALLET_RECOVERED", "QUALITY", "TREASURY")
        "PULLBACK_RECLAIM" -> setOf("BLUECHIP", "PRESALE_SNIPE", "MOONSHOT", "STANDARD", "QUALITY", "TREASURY")
        "CASHGEN" -> setOf("TREASURY")
        else -> setOf(canon)
    }

    private fun edgeFromRows(rows: List<Trade>, minRows: Int, minWr: Double, minNetSol: Double, cap: Double): Double {
        if (rows.size < minRows) return 0.0
        val wins = rows.count { it.pnlPct >= 0.5 }
        val wr = wins * 100.0 / rows.size
        val mean = rows.map { it.pnlPct }.average().takeIf { it.isFinite() } ?: 0.0
        val net = rows.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
        return if (wr >= minWr && net > minNetSol) maxOf(mean, wr * 0.8).coerceIn(0.0, cap) else 0.0
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
            lane.contains("TREASURY", true) || lane.contains("CASHGEN", true) -> 5.0
            lane.contains("MOONSHOT", true) && score >= 61.0 -> 8.0
            lane.contains("SHITCOIN", true) -> 12.0
            lane.contains("EXPRESS", true) || lane.contains("CYCLIC", true) -> 15.0
            lane.contains("WHALE", true) || lane.contains("COPY", true) -> 15.0
            else -> 5.0
        }
        return buySlippagePct + expectedSellSlippagePct + priorityFeePct + platformFeePct + spreadPct + mevBufferPct + givebackBufferPct + minProfitBufferPct
    }
}
