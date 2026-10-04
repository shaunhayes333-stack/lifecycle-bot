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
            // V5.0.7276 — a lane that is net positive in SOL with a positive mean
            // has edge whether or not it wins 45% of the time; the asymmetric
            // runner lanes this stack exists for win 25–35% and pay for it with
            // +60% average winners. The 45% bar stays as the high-confidence
            // branch; a low-WR lane that is net positive by both measures is
            // read at its mean instead of at zero.
            if (m != null && m.trades >= 8 && m.totalSolPnl > 0.0 &&
                (m.winRatePct >= 45.0 || m.meanPnlPct > 0.0))
                maxOf(m.pfExpectancyPp, m.meanPnlPct, m.avgWinPct * (m.winRatePct / 100.0)).coerceAtMost(60.0)
            else 0.0
        } catch (_: Throwable) { 0.0 }
        val liveRows7403 = try {
            val aliases = aliasesFor(canon)
            TradeHistoryStore.getRecentValidClosedTrades(limit = 1_500, includePartials = false)
                .filter { it.side.equals("SELL", true) }
                // V5.0.7403 — blank/legacy/unknown is not LIVE. The old
                // "!paper" clause admitted unscoped historical rows as live edge.
                .filter { it.mode.equals("live", true) || it.tradingMode.equals("live", true) }
                .filter { aliases.contains(BleederMemoryRouter.canon(it.tradingMode.ifBlank { it.reason })) }
                .take(150)
        } catch (_: Throwable) { emptyList() }
        val liveTerminalEdge = edgeFromRows(liveRows7403, minRows = 5, minWr = 45.0, minNetSol = 0.0, cap = 140.0)

        val paperAdvisoryEdge = try {
            val aliases = aliasesFor(canon)
            val rows = TradeHistoryStore.getRecentValidClosedTrades(limit = 2_000, includePartials = false)
                .filter { it.side.equals("SELL", true) }
                .filter { it.mode.equals("paper", true) }
                .filter { aliases.contains(BleederMemoryRouter.canon(it.tradingMode.ifBlank { it.reason })) }
                .take(250) // V5.0.7346 — newest 250, not oldest (see above)
            edgeFromRows(rows, minRows = 15, minWr = 45.0, minNetSol = 0.0, cap = 55.0)
        } catch (_: Throwable) { 0.0 }
        // V5.0.3972 — LIVE TRUST REBASE.
        // Paper memory can suggest, never dominate. If live has no positive
        // terminal confirmation, only a small score/leaderboard prior survives;
        // if live is positive, paper may add a capped boost. This prevents stale
        // paper/outlier winners from authorizing live entries against a toxic live
        // bucket while preserving the useful winner base.
        return when {
            liveTerminalEdge > 0.0 -> {
                // Once LIVE proves positive edge, paper may contribute only a
                // bounded prior; live remains the authority.
                maxOf(scorePrior, leaderboardEdge, liveTerminalEdge + (paperAdvisoryEdge * 0.20))
                    .coerceIn(0.0, 180.0)
            }
            liveRows7403.isNotEmpty() -> {
                // V5.0.7403 — real-money evidence exists and is not positive.
                // Do not let paper resurrect an edge that LIVE has failed to
                // demonstrate. Score prior remains candidate-local evidence,
                // but historical paper and pooled leaderboard cannot clear cost.
                try { PipelineHealthCollector.labelInc("LIVE_EDGE_PAPER_BOOTSTRAP_ENDED_7403") } catch (_: Throwable) {}
                scorePrior.coerceIn(0.0, 30.0)
            }
            else -> {
                // True cold start only: paper can seed the first live samples.
                try { PipelineHealthCollector.labelInc("LIVE_EDGE_PAPER_COLDSTART_PRIOR_7403") } catch (_: Throwable) {}
                maxOf(scorePrior, minOf(leaderboardEdge, 20.0), minOf(paperAdvisoryEdge, 15.0))
                    .coerceIn(0.0, 45.0)
            }
        }
    }

    private fun aliasesFor(canon: String): Set<String> = when (canon) {
        "LIQUIDITY_DEPTH_QUALITY" -> setOf("BLUECHIP", "PRESALE_SNIPE", "MOONSHOT", "WALLET_RECOVERED", "QUALITY", "TREASURY")
        "PULLBACK_RECLAIM" -> setOf("BLUECHIP", "PRESALE_SNIPE", "MOONSHOT", "STANDARD", "QUALITY", "TREASURY")
        // V5.0.7762 — canon() keeps CASHGEN distinct, so this alias made CASHGEN's live
        // edge read only TREASURY rows and never its own closes. Its own record first,
        // its treasury cousin beside it.
        "CASHGEN" -> setOf("CASHGEN", "TREASURY")
        else -> setOf(canon)
    }

    private fun edgeFromRows(rows: List<Trade>, minRows: Int, minWr: Double, minNetSol: Double, cap: Double): Double {
        if (rows.size < minRows) return 0.0
        val wins = rows.count { it.pnlPct >= 0.5 }
        val wr = wins * 100.0 / rows.size
        val mean = rows.map { it.pnlPct }.average().takeIf { it.isFinite() } ?: 0.0
        val net = rows.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
        return when {
            wr >= minWr && net > minNetSol -> maxOf(mean, wr * 0.8).coerceIn(0.0, cap)
            // V5.0.7276 — asymmetric edge: net positive in SOL and positive mean
            // at a win rate under the bar is still realised edge. Read at the
            // mean only (no WR-derived uplift), so a low-WR lane earns exactly
            // what its closes measured and nothing more.
            net > minNetSol && mean > 0.0 -> {
                try { PipelineHealthCollector.labelInc("EXPECTED_EDGE_ASYMMETRIC_LANE_READ_7276") } catch (_: Throwable) {}
                mean.coerceIn(0.0, cap)
            }
            else -> 0.0
        }
    }

    fun requiredEdgePct(ts: TokenState, lane: String, style: String, buySlippageBps: Int, sizeSol: Double, score: Double): Double {
        // V5.0.7766 — the trip's cost is the one round-trip cost
        // (FieldManual7715.roundTripCostPct7766). The order's slippage TOLERANCE
        // (buySlippageBps) is a ceiling, not a cost, and spread, MEV and a doubled
        // learned slip counted the same impact several times over.
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val roundTripCostPct = com.lifecyclebot.engine.truth.FieldManual7715.roundTripCostPct7766(
            sizeSol, if (solUsd.isFinite() && solUsd > 0.0) sizeSol * solUsd else 0.0, ts.lastLiquidityUsd,
        )
        val givebackBufferPct = when (BleederMemoryRouter.canon(lane)) {
            "SHITCOIN", "EXPRESS", "CYCLIC", "COPYTRADE" -> 5.0
            "MOONSHOT" -> 4.0
            // V5.0.4119 — reduced from 2.5 to 1.5 for STANDARD/default.
            // The exit ladder already protects against downside; the break-even
            // guard was over-conservative, starving mid-score volume.
            else -> 1.5
        }
        val minProfitBufferPct = when {
            lane.contains("BLUECHIP", true) -> 3.0
            lane.contains("PRESALE", true) || lane.contains("SNIPER", true) -> 5.0
            lane.contains("TREASURY", true) || lane.contains("CASHGEN", true) -> 5.0
            lane.contains("MOONSHOT", true) && score >= 61.0 -> 8.0
            lane.contains("SHITCOIN", true) -> 12.0
            lane.contains("EXPRESS", true) || lane.contains("CYCLIC", true) -> 15.0
            lane.contains("WHALE", true) || lane.contains("COPY", true) -> 15.0
            // V5.0.4119 — reduced from 5.0 to 2.0 for STANDARD/default lanes.
            // Opens up score 53+ instead of 58+ for bootstrap-phase entries.
            // Exit ladder and hard SL still protect capital.
            else -> 2.0
        }
        return roundTripCostPct + givebackBufferPct + minProfitBufferPct
    }
}
