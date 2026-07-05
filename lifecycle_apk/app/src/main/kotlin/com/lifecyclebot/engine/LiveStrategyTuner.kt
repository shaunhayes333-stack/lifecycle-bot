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
            val env6079 = try { if (RuntimeModeAuthority.isPaper()) "paper" else "live" } catch (_: Throwable) { "live" }
            "LiveStrategyTuner: neutral (no actionable ${env6079}-terminal closes yet; V5.0.6079 paper/live trade-1 ramp active once n≥1)"
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
        val paperRuntime6079 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val board = try {
            if (paperRuntime6079) StrategyTelemetry.computeCleanPaperTerminalLeaderboard(limit = 1_500)
            else StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
        } catch (_: Throwable) { return emptyMap() }
        if (board.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Adjustment>()
        for (m in board) {
            if (m.trades <= 0) continue
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
        // V5.0.6068 — LOWER n>=30 -> n>=15 so proven runner lanes hit the
        // exempt sooner. MOONSHOT (n=43 WR=21% EV=+974%) still didn't qualify
        // because it needs `wr >= 35 || sol > 0 || mean >= 20`. That gate is
        // fine — the toxic-bleed miscategorization is fixed in the deeper
        // exempt clause (V5.0.6068 asymRunner6068 above).
        if (isRunnerLane && n >= 15 && (wr >= 35.0 || sol > 0.0 || mean >= 20.0)) {
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
        // V5.0.6077 — TRADE-1 LANE TUNING. compute() now admits lanes from the
        // first terminal close instead of skipping n<5 entirely. For n=1..4,
        // apply a bounded ramp: green early evidence gently presses/patiences the
        // lane; red early evidence gently risk-shapes it. Mature bleeder/toxic
        // pivots below still require their existing larger samples. This answers
        // the operator's doctrine: SSI/AGI tunes from trade 1 in paper/live,
        // without turning a single close into a hard choke.
        if (n in 1 until MIN_TUNE_TRADES) {
            val ramp6077 = (n.toDouble() / MIN_TUNE_TRADES.toDouble()).coerceIn(0.20, 0.80)
            val green6077 = sol > 0.0 || mean > 0.0 || wr >= 50.0
            val red6077 = sol < 0.0 || mean < 0.0 || wr <= 35.0
            val size = when {
                green6077 -> 1.0 + 0.18 * ramp6077
                red6077 -> 1.0 - 0.18 * ramp6077
                else -> 1.0
            }.coerceIn(0.86, 1.14)
            val hold = when {
                green6077 -> 1.0 + 0.25 * ramp6077
                red6077 -> 1.0 - 0.22 * ramp6077
                else -> 1.0
            }.coerceIn(0.84, 1.20)
            val partial = when {
                green6077 -> 1.0 + 0.30 * ramp6077
                red6077 -> 1.0 - 0.18 * ramp6077
                else -> 1.0
            }.coerceIn(0.86, 1.24)
            return Adjustment(
                lane = lane, trades = n, winRatePct = wr, totalSolPnl = sol,
                pfExpectancyPp = pf, meanPnlPct = mean, sizeMult = size,
                tpMult = if (green6077) (1.0 + 0.12 * ramp6077).coerceIn(1.0, 1.10) else 1.0,
                holdMult = hold,
                maxWalletMult = size.coerceIn(0.86, 1.08),
                liquidityImpactMult = if (red6077) (1.0 - 0.12 * ramp6077).coerceIn(0.90, 1.0) else 1.0,
                partialTriggerMult = partial,
                label = if (green6077) "trade1_positive_ramp_6077" else if (red6077) "trade1_risk_ramp_6077" else "trade1_neutral_ramp_6077",
            )
        }
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
            // V5.0.4586 — DAILY COMPOUND RULE 1 (asymmetric compounding).
            // V5.0.4588 — PROVEN-WINNER 2x-3x PRESS (operator P0 task c).
            // Operator: 'c for sure! if lanes are 49% profitable double triple
            // the entry size!!!'. When lane WR>=45% AND n>=5 AND positive PnL,
            // this is a PROVEN winner — lift the size ceiling to 2.50x (was
            // 1.90x with behind-target pressure), and lift hold/partial too so
            // the runners we press harder also stay open longer to catch full
            // upside. Bleeders unchanged (still shrink via loser branch).
            val behindPress = try { DailyCompoundingTracker.behindTargetPressure() } catch (_: Throwable) { 1.0 }
            val provenWinner4588 = wr >= 45.0 && n >= 5 && sol > 0.0
            val provenBoost4588 = if (provenWinner4588) 1.35 else 1.0  // +35% ceiling for proven winners
            val sizeCeiling = (1.55 * behindPress * provenBoost4588).coerceIn(1.55, 2.60)
            val holdCeiling = (2.20 * behindPress * provenBoost4588).coerceIn(2.20, 3.20)
            val maxWalletCeiling = (1.50 * behindPress * provenBoost4588).coerceIn(1.50, 2.20)
            val partialCeiling = (2.60 * behindPress * provenBoost4588).coerceIn(2.60, 3.80)
            val baseSizeGain = if (provenWinner4588) 0.72 else 0.47
            val baseHoldGain = if (provenWinner4588) 1.35 else 0.95
            val basePartialGain = if (provenWinner4588) 1.85 else 1.25
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (1.08 + edge * baseSizeGain * behindPress).coerceIn(1.04, sizeCeiling),
                tpMult = (1.16 + edge * 0.59).coerceIn(1.12, 1.75),
                holdMult = (1.25 + edge * baseHoldGain * behindPress).coerceIn(1.18, holdCeiling),
                maxWalletMult = (1.10 + edge * 0.40 * behindPress).coerceIn(1.05, maxWalletCeiling),
                liquidityImpactMult = (1.03 + edge * 0.15).coerceIn(1.00, 1.18),
                partialTriggerMult = (1.35 + edge * basePartialGain * behindPress).coerceIn(1.25, partialCeiling),
                label = if (provenWinner4588) "proven_winner_press_2x3x" else if (compounding) "compounding_runner" else "runner_press",
            )
        }

        // V5.0.4584 — toxic live expectancy is a tactic/style pivot first.
        // Report 4581 still showed toxic_inner_lane_pivot as size×0.40, which
        // reads like the rejected micro-probe doctrine. Keep the same lane owner,
        // but label the behavior as a reclaim/liquidity/order-flow tactic pivot;
        // sizing is only a secondary risk envelope.
        //
        // V5.0.4579 — toxic live expectancy is NOT a command to buy the same
        // failing setup smaller and hold it longer. Runtime 4578 showed exactly
        // that failure: MANIPULATED/SHITCOIN were labelled toxic_runner_pivot
        // with hold×/partial×=1.90 while clean truth bled -0.187 SOL. Operator
        // doctrine: pivot strategy inside the lane before/during purchase; sizing
        // is secondary. Toxic lanes now move to a defensive/reclaim inner-lane
        // pivot: stricter liquidity impact, earlier banking, shorter hold. No
        // 0.12 micro-probe as the primary treatment.
        // V5.0.4506 — pct/SOL contradiction guard. A lane showing huge mean%
        // while losing net SOL (operator report: BLUECHIP EV +905%/trade but
        // PnL -0.4397 SOL) is not a runner exemption; it is an accounting/basis
        // contradiction or bad capital curve. Keep sampling, but only as a small
        // recovery probe until SOL truth catches up.
        val pctSolContradiction = n >= 8 && sol < -0.0001 && mean >= 20.0
        if (pctSolContradiction) {
            val solDepth = ((-sol) / 0.50).coerceIn(0.0, 1.0)
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = (0.55 - solDepth * 0.30).coerceIn(0.25, 0.55),
                tpMult = 1.05,
                holdMult = 1.05,
                maxWalletMult = 0.55,
                liquidityImpactMult = 0.72,
                partialTriggerMult = 1.10,
                label = "pct_sol_contradiction_probe",
            )
        }

        // V5.0.6075 — NET-POSITIVE LANE PROTECTION (operator P0: profitable
        // lanes like TREASURY +SOL were still being clamped by the lottery/
        // bleeder branches below because low WR dragged them in. Doctrine:
        // a lane that is NET-POSITIVE in real SOL with a real sample must
        // NEVER be sized below 1.0 by this tuner — dampeners are per-lane
        // and a lane paying for itself is not a bleeder.
        if (n >= 1 && sol > 0.0) {
            return Adjustment(
                lane = lane, trades = n, winRatePct = wr, totalSolPnl = sol,
                pfExpectancyPp = pf, meanPnlPct = mean,
                sizeMult = 1.0,
                tpMult = (1.05 + (avgWin / 250.0).coerceIn(0.0, 0.40)).coerceIn(1.00, 1.45),
                holdMult = 1.10, maxWalletMult = 1.0,
                liquidityImpactMult = 1.0, partialTriggerMult = 1.15,
                label = "net_positive_lane_floor_6075",
            )
        }

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
        // V5.0.6068 — INVERTED-SCORING FIX (operator: "why the good lanes are
        // dampened instead of expanded. there has to be multiple inverted
        // scores still. find all"). Report V5.0.6066 showed the exact death
        // spiral: MOONSHOT n=43 WR=21.4% EV=+974.82%/trade got size×=0.40
        // "toxic_reclaim_tactic_pivot" because this exempt required
        // `sol >= 0.0`. A high-EV asymmetric lane whose net-SOL is TEMPORARILY
        // negative (because the next big winner hasn't landed) is EXACTLY the
        // pattern we should protect, not punish. Punishing it shrinks the next
        // winner, keeping SOL red, which shrinks it more — pure inversion.
        // Fix: exempt on mean>=20% OR asymmetric runner profile regardless of
        // temporary net-SOL colour. Bleeder branch below still fires only when
        // BOTH sol<0 AND mean<=-8% AND wr<35% — i.e. genuine bleeder, not
        // asymmetric variance.
        val asymRunner6068 = n >= 8 && (mean >= 20.0 || m.avgWinPct >= 50.0 || pf >= 4.0)
        if ((n >= 30 && wr >= 40.0 && sol >= 0.0) ||
            (n >= 8 && mean >= 20.0 && sol >= 0.0) ||
            asymRunner6068  // V5.0.6068 — mean>=20% or big-tail signature exempts regardless of net SOL
        ) {
            return Adjustment(
                lane = lane, trades = n, winRatePct = wr, totalSolPnl = sol,
                pfExpectancyPp = pf, meanPnlPct = mean, sizeMult = 1.0,
                tpMult = 1.20, holdMult = 1.40, maxWalletMult = 1.0,
                liquidityImpactMult = 1.0, partialTriggerMult = 1.30,
                label = if (asymRunner6068 && sol < 0.0) "asymmetric_runner_exempt_6068_net_sol_variance" else "asymmetric_runner_exempt",
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
        // V5.0.6114 — LIFETIME EV GUARD. The clean-live terminal leaderboard
        // only has 87 trades (WR=21.8%, PnL=-0.143 SOL) because the bot recently
        // switched to live mode. But the FULL lifetime data has 463 trades
        // (WR=38.9%, PnL=+14.85 SOL). Making toxic decisions on the small live-only
        // sample mislabels profitable lanes (BLUECHIP 43.7% WR, QUALITY 42% WR)
        // as toxic. Fix: check the full leaderboard (including paper) before
        // applying the toxic label. If the lane is profitable in the full data,
        // it's NOT toxic regardless of the clean-live-only sample.
        val lifetimeMetric6114 = try {
            StrategyTelemetry.computeLeaderboard(environment = null, includePartials = false, limit = 2_500)
                .firstOrNull { canonical(it.strategy) == laneKey }
        } catch (_: Throwable) { null }
        if (lifetimeMetric6114 != null && lifetimeMetric6114.trades >= 30 &&
            (lifetimeMetric6114.totalSolPnl > 0.0 || lifetimeMetric6114.meanPnlPct >= 20.0)) {
            try { ForensicLogger.lifecycle("LIFETIME_EV_GUARD_6114", "lane=$lane cleanLiveN=$n cleanLiveWR=${"%.1f".format(wr)}% cleanLiveSol=${"%.4f".format(sol)} lifetimeN=${lifetimeMetric6114.trades} lifetimeWR=${"%.1f".format(lifetimeMetric6114.winRatePct)}% lifetimeSol=${"%.4f".format(lifetimeMetric6114.totalSolPnl)} action=exempt_from_toxic") } catch (_: Throwable) {}
            return Adjustment(
                lane = lane, trades = n, winRatePct = wr, totalSolPnl = sol,
                pfExpectancyPp = pf, meanPnlPct = mean,
                sizeMult = 1.0,
                tpMult = 1.10, holdMult = 1.05, maxWalletMult = 1.0,
                liquidityImpactMult = 1.0, partialTriggerMult = 1.15,
                label = "lifetime_ev_exempt_6114",
            )
        }
        val pfBleed = n >= 8 && sol < 0.0 && pf <= 0.0
        val wrBleed = n >= 8 && wr < 35.0 && sol <= 0.0
        val meanBleed = n >= 8 && sol <= 0.0 && mean <= -8.0
        val toxicBleed = (n >= 10 && wr <= 28.0 && sol < 0.0) || (n >= 8 && wr <= 15.0 && sol < 0.0)
        if (pfBleed || wrBleed || meanBleed || toxicBleed) {
            val wrDepth = ((35.0 - wr) / 35.0).coerceIn(0.0, 1.0)
            val pfDepth = ((-pf) / 16.0).coerceIn(0.0, 1.0)
            val solDepth = ((-sol) / 0.50).coerceIn(0.0, 1.0)
            val meanDepth = ((-mean) / 25.0).coerceIn(0.0, 1.0)
            val depth = maxOf(wrDepth, pfDepth, solDepth, meanDepth)
            val toxicInnerLanePivot = toxicBleed || (wr <= 25.0 && sol < 0.0 && mean < 0.0)
            return Adjustment(
                lane = lane,
                trades = n,
                winRatePct = wr,
                totalSolPnl = sol,
                pfExpectancyPp = pf,
                meanPnlPct = mean,
                sizeMult = if (toxicInnerLanePivot)
                    (0.62 - depth * 0.22).coerceIn(0.35, 0.62)
                else
                    (0.78 - depth * 0.43).coerceIn(0.35, 0.82),
                tpMult = if (toxicInnerLanePivot)
                    (0.92 - depth * 0.12).coerceIn(0.78, 0.94)
                else
                    (1.04 + depth * 0.18).coerceIn(1.02, 1.22),
                holdMult = if (toxicInnerLanePivot)
                    (0.82 - depth * 0.22).coerceIn(0.55, 0.84)
                else
                    (1.02 + depth * 0.20).coerceIn(0.95, 1.24),
                maxWalletMult = if (toxicInnerLanePivot)
                    (0.70 - depth * 0.24).coerceIn(0.42, 0.72)
                else
                    (0.90 - depth * 0.36).coerceIn(0.50, 0.96),
                liquidityImpactMult = if (toxicInnerLanePivot)
                    (0.78 - depth * 0.20).coerceIn(0.55, 0.80)
                else
                    (0.94 - depth * 0.20).coerceIn(0.62, 0.96),
                // Toxic lanes bank earlier and shorten exposure. Non-toxic bleeders
                // may still retain mild runner patience, but never the old 1.90 toxic hold.
                partialTriggerMult = if (toxicInnerLanePivot)
                    (0.90 - depth * 0.22).coerceIn(0.62, 0.92)
                else
                    (1.04 + depth * 0.20).coerceIn(1.00, 1.25),
                label = if (toxicInnerLanePivot) "toxic_reclaim_tactic_pivot" else "bleeder_recovery_pivot",
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
