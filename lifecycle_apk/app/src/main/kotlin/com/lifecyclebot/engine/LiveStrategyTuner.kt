package com.lifecyclebot.engine

/**
 * V5.0.4026 — LIVE STRATEGY TUNING.
 *
 * Decision-facing, cached, live-only tuning layer. It reads the same clean
 * StrategyTelemetry live-terminal SELL leaderboard used by the reports and
 * returns bounded multipliers for capital envelope + runner patience.
 *
 * Doctrine:
 *  - LIVE terminal closes only; no paper, shadow, partial, recovered-accounting
 *    rows can authorize live tuning.
 *  - Soft-shape only: never veto, never zero-size, never call LLM/API.
 *  - Press proven live winners by letting them ride and giving more envelope.
 *  - Bleeders become faster/smaller probes while hard SL/catastrophe safety stays
 *    terminal and unconditional.
 */
object LiveStrategyTuner {
    const val VERSION = "V5.0.4030_LIVE_STRATEGY_TUNER"

    data class Adjustment(
        val lane: String,
        val trades: Int,
        val winRatePct: Double,
        val totalSolPnl: Double,
        val pfExpectancyPp: Double,
        val meanPnlPct: Double,
        val sizeMult: Double,
        val tpMult: Double,
        val holdMult: Double,
        val maxWalletMult: Double,
        val liquidityImpactMult: Double,
        val partialTriggerMult: Double,
        val label: String,
    ) {
        val isNeutral: Boolean get() = label == "neutral"
        val compact: String get() =
            "$VERSION lane=$lane label=$label n=$trades wr=${"%.1f".format(winRatePct)} pnl=${"%+.4f".format(totalSolPnl)} pf=${"%+.2f".format(pfExpectancyPp)} mean=${"%+.1f".format(meanPnlPct)} size×=${"%.2f".format(sizeMult)} tp×=${"%.2f".format(tpMult)} hold×=${"%.2f".format(holdMult)} partial×=${"%.2f".format(partialTriggerMult)}"
    }

    private const val CACHE_MS = 7_500L
    private const val MIN_TUNE_TRADES = 5
    private const val MIN_WINNER_TRADES = 8
    private const val LIVE_PARTIAL_PROFIT_FLOOR_PCT = 8.0

    @Volatile private var cacheAtMs: Long = 0L
    @Volatile private var cached: Map<String, Adjustment> = emptyMap()

    fun adjustment(rawLane: String?): Adjustment {
        val lane = canonical(rawLane)
        return try { snapshot()[lane] ?: neutral(lane) } catch (_: Throwable) { neutral(lane) }
    }

    fun sizeMultiplier(rawLane: String?): Double = adjustment(rawLane).sizeMult
    fun tpMultiplier(rawLane: String?): Double = adjustment(rawLane).tpMult
    fun holdMultiplier(rawLane: String?): Double = adjustment(rawLane).holdMult
    fun partialTriggerMultiplier(rawLane: String?): Double = adjustment(rawLane).partialTriggerMult

    fun livePartialProfitFloorPct(): Double = LIVE_PARTIAL_PROFIT_FLOOR_PCT

    fun statusLine(): String = try {
        val tuned = snapshot().values
            .distinctBy { it.lane }
            .filter { !it.isNeutral }
            .sortedWith(compareBy<Adjustment> { it.label }.thenBy { it.lane })
        if (tuned.isEmpty()) {
            "LiveStrategyTuner: neutral (live-terminal only; no lane ≥$MIN_TUNE_TRADES actionable closes)"
        } else {
            "LiveStrategyTuner: " + tuned.joinToString(" · ") {
                "${it.lane}:${it.label} n=${it.trades} WR=${"%.0f".format(it.winRatePct)}% PnL=${"%+.3f".format(it.totalSolPnl)} size×=${"%.2f".format(it.sizeMult)} tp×=${"%.2f".format(it.tpMult)} hold×=${"%.2f".format(it.holdMult)} partial×=${"%.2f".format(it.partialTriggerMult)}"
            }
        }
    } catch (_: Throwable) { "LiveStrategyTuner: unavailable" }

    private fun snapshot(): Map<String, Adjustment> {
        val now = System.currentTimeMillis()
        val c = cached
        if (now - cacheAtMs < CACHE_MS && c.isNotEmpty()) return c
        val fresh = compute()
        cached = fresh
        cacheAtMs = now
        return fresh
    }

    private fun compute(): Map<String, Adjustment> {
        val board = try { StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 1_500) } catch (_: Throwable) { return emptyMap() }
        if (board.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Adjustment>()
        for (m in board) {
            if (m.trades < MIN_TUNE_TRADES) continue
            val lane = canonical(m.strategy)
            val adj = buildAdjustment(lane, m)
            out[lane] = adj
            val raw = m.strategy.trim().uppercase()
            if (raw.isNotBlank()) out[raw] = adj
        }
        return out
    }

    private fun buildAdjustment(lane: String, m: StrategyTelemetry.StrategyMetric): Adjustment {
        val wr = m.winRatePct.coerceIn(0.0, 100.0)
        val pf = m.pfExpectancyPp
        val sol = m.totalSolPnl
        val mean = m.meanPnlPct
        val n = m.trades

        // V5.0.4086 — HARD RUNNER-LANE EXEMPTION (operator P0: "the meme trader
        // should maintain really good volume once learnt. it should never ever
        // be allowed to choke itself out."). Asymmetric-runner lanes have their
        // own per-lane tuners (LaneExitTuner) plus the lane-specific entry size
        // controls; this global bleeder/runner pivot was originally written for
        // STANDARD/BLUECHIP-style mean-stable strategies and consistently
        // mis-classifies runner profiles as bleeders. Once a runner lane has
        // earned its sample (n>=30), it stays at full sizing — variance is the
        // cost of the upside, not a defect. Failing exits/SL are governed by
        // LaneExitTuner + StrictSL + ExitCoordinator, NOT by sizing.
        val laneKey = lane.uppercase()
        val isRunnerLane = laneKey.contains("MOONSHOT") || laneKey.contains("SHITCOIN") ||
            laneKey.contains("MEME") || laneKey.contains("EXPRESS") ||
            laneKey.contains("MANIP") || laneKey.contains("PRESALE") ||
            laneKey.contains("PROJECT_SNIPER") || laneKey.contains("DIP_HUNTER")
        // V5.0.4123 — DATA-DRIVEN GATE: runner_lane_exempt was firing
        // unconditionally for runner lanes with n>=30, regardless of WR or
        // PnL. Operator report 5.0.4122 shows MOONSHOT n=166 WR=22% PnL=-0.691
        // SOL getting sizeMult=1.0 because this exempt short-circuits BEFORE
        // the toxicBleed logic. A 22% WR lane bleeding -0.69 SOL must NOT be
        // exempt from bleeder penalties.
        // Gate: only exempt if the lane is actually profitable or has decent WR.
        if (isRunnerLane && n >= 30 && (wr >= 35.0 || sol > 0.0 || mean >= 20.0)) {
            return Adjustment(
                lane = lane, trades = n, winRatePct = wr, totalSolPnl = sol,
                pfExpectancyPp = pf, meanPnlPct = mean, sizeMult = 1.0,
                tpMult = 1.20, holdMult = 1.40, maxWalletMult = 1.0,
                liquidityImpactMult = 1.0, partialTriggerMult = 1.30,
                label = "runner_lane_exempt",
            )
        }

        // V5.0.4030 — hit-rate gated net-SOL doctrine.
        // Positive net SOL is still the north star, but a lane with <35% live WR
        // cannot be called a capital winner from outliers alone. It may keep tiny
        // asymmetric probes/runner patience; it may not receive >1× entry size.
        val avgWin = m.avgWinPct.coerceAtLeast(0.0)
        val asymmetric = avgWin >= 50.0 || mean >= 20.0 || pf >= 8.0
        val hitRateHealthy = wr >= 45.0 || (wr >= 35.0 && pf > 0.0 && sol > 0.0)
        val winner = n >= MIN_WINNER_TRADES && sol > 0.0 && hitRateHealthy &&
            (pf > 0.0 || asymmetric || sol >= 0.05)
        if (winner) {
            val wrEdge = ((wr - 28.0) / 42.0).coerceIn(0.0, 1.0)
            val pfEdge = (pf / 35.0).coerceIn(0.0, 1.0)
            val solEdge = (sol / 0.45).coerceIn(0.0, 1.0)
            val avgWinEdge = (avgWin / 180.0).coerceIn(0.0, 1.0)
            val edge = (wrEdge * 0.18 + pfEdge * 0.26 + solEdge * 0.34 + avgWinEdge * 0.22).coerceIn(0.0, 1.0)
            val compounding = sol >= 0.20 || mean >= 45.0 || avgWin >= 120.0
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (1.08 + edge * 0.47).coerceIn(1.04, 1.55),
                tpMult = (1.16 + edge * 0.59).coerceIn(1.12, 1.75),
                holdMult = (1.25 + edge * 0.95).coerceIn(1.18, 2.20),
                maxWalletMult = (1.10 + edge * 0.40).coerceIn(1.05, 1.50),
                liquidityImpactMult = (1.03 + edge * 0.15).coerceIn(1.00, 1.18),
                partialTriggerMult = (1.35 + edge * 1.25).coerceIn(1.25, 2.60),
                label = if (compounding) "compounding_runner" else "runner_press",
            )
        }

        // Negative live expectancy is NOT a command to churn faster. Runtime
        // 5.0.4059 showed the bot losing from scratch/flat/fast exits while the
        // rare winners paid 100-287% when held. Failure pivots playbook: keep
        // entry size tiny, but extend hold/TP/partial patience so asymmetric
        // runners can pay for the loser distribution. LaneExpectancyDamper may
        // apply a deeper capital haircut at the executor, still non-zero.
        val lowWrPositiveSolLottery = n >= 20 && wr < 35.0 && sol > 0.0
        if (lowWrPositiveSolLottery) {
            val wrDepth = ((35.0 - wr) / 35.0).coerceIn(0.0, 1.0)
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (0.78 - wrDepth * 0.28).coerceIn(0.45, 0.78),
                tpMult = (1.05 + (avgWin / 250.0).coerceIn(0.0, 0.45)).coerceIn(1.00, 1.50),
                holdMult = (1.02 + (avgWin / 300.0).coerceIn(0.0, 0.45)).coerceIn(0.95, 1.45),
                maxWalletMult = 0.72,
                liquidityImpactMult = 0.86,
                partialTriggerMult = 1.15,
                label = "low_wr_asymmetric_probe",
            )
        }

        // V5.0.4078 — BOOTSTRAP SAMPLE GUARD (operator P0: "we win when we
        // hold ... must fix bleed immediately and push for profits"). The
        // legacy pfBleed and meanBleed fired with n=1 — a single losing
        // trade was enough to dampen the entire lane to size×=0.35 for the
        // remaining rebuild window, killing volume during exactly the phase
        // when the bot needs to earn samples. Match the wrBleed floor:
        // require n >= 8 closed live trades before any bleeder pivot fires.
        // Below n=8 we stay neutral so meme lanes can probe at full size
        // and find their runners. Once n >= 8, the original logic resumes.
        //
        // V5.0.4084 — ASYMMETRIC-RUNNER EXEMPTION (operator P0 "entry
        // sizing is too small to make big enough gains"). A lane with high
        // mean-PnL (asymmetric runner profile) is the same signature this
        // tuner already labels positively as low_wr_asymmetric_probe — yet
        // the bleeder pivot below would then size it down to 0.35x because
        // its total SOL pnl is near-zero or slightly negative on the back
        // of frequent small losses. Mirror the LaneExpectancyDamper fix:
        // if mean-PnL >= +20% over MIN_TRADES, exempt entirely. The big-tail
        // wins are what pay; variance is the cost, not a defect.
        // V5.0.4085 — WR-based RUNNER exemption (operator P0: snapshot showed
        // MOONSHOT n=141 wr=36% getting damped because tuner reads NET-realized
        // mean (-0.018%/trade), not gross EV (+80%/trade). Switch to WR + sample
        // gate which actually reflects the lane's profitability profile.
        if ((n >= 30 && wr >= 40.0) || (n >= 8 && mean >= 20.0)) {
            return Adjustment(
                lane = lane, trades = n, winRatePct = wr, totalSolPnl = sol,
                pfExpectancyPp = pf, meanPnlPct = mean, sizeMult = 1.0,
                tpMult = 1.20, holdMult = 1.40, maxWalletMult = 1.0,
                liquidityImpactMult = 1.0, partialTriggerMult = 1.30,
                label = "asymmetric_runner_exempt",
            )
        }
        // V5.0.4178 — L5 STRATEGY PIVOT ACCELERATION (operator directive).
        // toxicBleed threshold n>=20 → n>=10. wrBleed threshold n>=8 stays.
        // Operator: "lane brains need to switch strategies faster". 4172/4176
        // logs showed STANDARD lane at n=20 wr=6% PnL=-0.075 still on
        // bleeder_runner_pivot (sizeFloor 0.35) — should have pivoted to
        // toxic_runner_pivot (sizeFloor 0.12) at n=10 or even paused. Faster
        // toxic gate lets the bot abandon proven-bad lanes within 10 closes
        // instead of 20.
        val pfBleed = n >= 8 && sol < 0.0 && pf <= 0.0
        val wrBleed = n >= 8 && wr < 35.0 && sol <= 0.0
        val meanBleed = n >= 8 && sol <= 0.0 && mean <= -8.0
        val toxicBleed = n >= 10 && wr <= 28.0 && sol < 0.0
        if (pfBleed || wrBleed || meanBleed || toxicBleed) {
            val wrDepth = ((35.0 - wr) / 35.0).coerceIn(0.0, 1.0)
            val pfDepth = ((-pf) / 16.0).coerceIn(0.0, 1.0)
            val solDepth = ((-sol) / 0.50).coerceIn(0.0, 1.0)
            val meanDepth = ((-mean) / 25.0).coerceIn(0.0, 1.0)
            val depth = maxOf(wrDepth, pfDepth, solDepth, meanDepth)
            val sizeFloor = if (toxicBleed) 0.12 else 0.35
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (0.78 - depth * 0.66).coerceIn(sizeFloor, 0.82),
                tpMult = (1.06 + depth * 0.34).coerceIn(1.04, 1.42),
                holdMult = (1.18 + depth * 0.72).coerceIn(1.12, 1.90),
                maxWalletMult = (0.90 - depth * 0.44).coerceIn(0.42, 0.96),
                liquidityImpactMult = (0.94 - depth * 0.24).coerceIn(0.58, 0.96),
                // Bleeders do not bank earlier anymore; they size down and wait
                // for asymmetric runner proof. Values >1 raise learned partial rungs.
                partialTriggerMult = (1.18 + depth * 0.72).coerceIn(1.12, 1.90),
                label = if (toxicBleed) "toxic_runner_pivot" else "bleeder_runner_pivot",
            )
        }

        return neutral(lane, n, wr, sol, pf, mean)
    }

    private fun canonical(raw: String?): String {
        val r = raw?.trim()?.takeIf { it.isNotBlank() } ?: return "STANDARD"
        return try { LiveGrowthDoctrine.canonicalLane(r) } catch (_: Throwable) { r.uppercase() }
    }

    private fun neutral(
        lane: String,
        trades: Int = 0,
        wr: Double = 0.0,
        sol: Double = 0.0,
        pf: Double = 0.0,
        mean: Double = 0.0,
    ): Adjustment = Adjustment(
        lane = lane,
        trades = trades,
        winRatePct = wr,
        totalSolPnl = sol,
        pfExpectancyPp = pf,
        meanPnlPct = mean,
        sizeMult = 1.0,
        tpMult = 1.0,
        holdMult = 1.0,
        maxWalletMult = 1.0,
        liquidityImpactMult = 1.0,
        partialTriggerMult = 1.0,
        label = "neutral",
    )
}
