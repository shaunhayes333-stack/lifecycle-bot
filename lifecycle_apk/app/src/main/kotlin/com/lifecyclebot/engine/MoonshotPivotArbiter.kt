package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.4151 — MOONSHOT PIVOT ARBITER.
 *
 * Never disables MOONSHOT. It decides whether the current MOONSHOT context may
 * trade normal size, must trade micro/retraining size, should be reclassified,
 * or should be watched until route/reclaim proof appears.
 */
object MoonshotPivotArbiter {
    enum class PivotMode {
        NORMAL_MOONSHOT,
        MOONSHOT_MICRO_RETRAIN,
        SHITCOIN_MICRO_RECLASSIFIED,
        EXPRESS_RECLAIM_ONLY,
        WATCH_PROBATION,
    }

    data class Decision(
        val mode: PivotMode,
        val finalLane: String,
        val finalStyle: String,
        val sizeCapSol: Double?,
        val allowBuy: Boolean,
        val reasons: List<String>,
        val cleanWrPct: Double,
        val cleanPnlSol: Double,
    )

    fun decide(
        ts: TokenState,
        lane: String,
        regime: String,
        score: Double,
        routeProof: Boolean,
        basisTrusted: Boolean,
        rugProof: Boolean,
        holderProof: Boolean,
        liquidityUsd: Double,
        plannedSizeSol: Double,
    ): Decision {
        val canonLane = try { BleederMemoryRouter.canon(lane) } catch (_: Throwable) { lane.uppercase() }
        if (canonLane != "MOONSHOT") return pass(canonLane)

        val metric = try {
            StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 2_500).firstOrNull { it.strategy.equals("MOONSHOT", true) }
        } catch (_: Throwable) { null }
        val perf7801 = try { com.lifecyclebot.engine.truth.SpecialistPerformance7801.stat("MOONSHOT","live") } catch (_: Throwable) { null }
        val cleanWr = (perf7801?.mandateSuccessRate?.times(100.0)) ?: metric?.winRatePct ?: 100.0
        val cleanPnl = perf7801?.totalSolPnl ?: metric?.totalSolPnl ?: 0.0
        val tailHealthy7801 = perf7801?.tailEconomicHealthy ?: ((metric?.trades ?: 0) < 5)
        val scoreBand = try { LosingPatternMemory.scoreBand(score.toInt()) } catch (_: Throwable) { LiveStylePivotRouter.scoreBand(score) }
        val bucket = try { LosingPatternMemory.liveStats("MOONSHOT", score.toInt()) } catch (_: Throwable) { null }
        val lossRate = bucket?.lossRatePct ?: 0.0
        // V5.0.6284 — NET-EV DANGER CHECK. A bucket like MOONSHOT|S41-60
        // (104 losses / 44 wins / meanPnl=+63.87%) is high-loss-rate but
        // AGGREGATE PROFITABLE thanks to memecoin outlier winners. Vetoing
        // it was throwing away the profitable memecoin distribution. Now
        // dangerBucket requires BOTH high loss rate AND negative mean PnL
        // to actually trip — asymmetric distributions with big winners
        // survive the check and route to normal Moonshot execution.
        val bucketMeanPnl = bucket?.meanPnl ?: 0.0
        val dangerBucket = scoreBand == "S41-60" && (bucket?.sample ?: 0) >= 8 && lossRate >= 75.0 && bucketMeanPnl <= -5.0
        val dump = regime.equals("DUMP", true)
        val p = try { LiveProbabilityEngine.forecast("MOONSHOT", score.toInt(), ts.meta.setupQuality, regime) } catch (_: Throwable) { null }
        val pWin = p?.pWin ?: 0.50
        val buyPressure = try { (ts.history.lastOrNull()?.buyRatio ?: 0.0) * 100.0 } catch (_: Throwable) { 0.0 }
        val momentum = try { ts.meta.momScore } catch (_: Throwable) { 0.0 }
        val volume = try { ts.meta.volScore } catch (_: Throwable) { 0.0 }
        val exitCapacityUsd = liquidityUsd
        val launch7403 = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        val movement7403 = try { MovementPatternSignal.from(ts) } catch (_: Throwable) { null }
        val runnerProof7403 = (launch7403 != null && !launch7403.tooLateForSnipe &&
            launch7403.phase in setOf(
                com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION,
                com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING
            ) && launch7403.buySharePct >= 55.0) ||
            movement7403?.pattern == "BREAKOUT_CONTINUATION"
        val reclaimProof = routeProof && basisTrusted && rugProof &&
            movement7403?.pattern == "PULLBACK_RECLAIM" && buyPressure >= 50.0
        val goldenGoose = try { PatternGoldenGoose.edge(ts.name, ts.symbol).verdict == TokenWinMemory.Verdict.GOLD } catch (_: Throwable) { false }

        val reasons = mutableListOf<String>()
        fun emit(label: String) { try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {} }
        try {
            PipelineHealthCollector.labelInc("MOONSHOT_CLEAN_WR")
            PipelineHealthCollector.labelInc("MOONSHOT_CLEAN_PNL")
        } catch (_: Throwable) {}

        if (!routeProof || !basisTrusted || !rugProof) {
            emit("MOONSHOT_DUMP_REJECTED_TO_WATCH")
            reasons += "MOONSHOT_ROUTE_OR_BASIS_PROOF_REQUIRED"
            return Decision(PivotMode.WATCH_PROBATION, "MOONSHOT", "WATCH_PROBATION", null, false, reasons, cleanWr, cleanPnl)
        }

        if (dangerBucket && !(goldenGoose && routeProof && (runnerProof7403 || reclaimProof))) {
            emit("MOONSHOT_DANGER_BUCKET_WATCH_7403")
            reasons += "MOONSHOT_DANGER_BUCKET_REQUIRES_RUNNER_OR_RECLAIM_PROOF_7403"
            return Decision(PivotMode.WATCH_PROBATION, "MOONSHOT", "WATCH_PROBATION", null, false, reasons, cleanWr, cleanPnl)
        }

        val probabilityEconomicOk7801 = p == null || p.samples < 5L ||
            p.expectedPnlPct >= 0.0 || tailHealthy7801
        val normalAllowed = tailHealthy7801 && probabilityEconomicOk7801 &&
            routeProof && exitCapacityUsd >= 5_000.0 && runnerProof7403 && (!dump || reclaimProof)
        if (normalAllowed) {
            emit("MOONSHOT_PIVOT_NORMAL")
            reasons += "MOONSHOT_PIVOT_NORMAL"
            return Decision(PivotMode.NORMAL_MOONSHOT, "MOONSHOT", "MOONSHOT", null, true, reasons, cleanWr, cleanPnl)
        }

        if (dump && !reclaimProof) {
            emit("MOONSHOT_DUMP_REJECTED_TO_WATCH")
            reasons += "MOONSHOT_DUMP_REJECTED_TO_WATCH"
            return Decision(PivotMode.WATCH_PROBATION, "MOONSHOT", "WATCH_PROBATION", null, false, reasons, cleanWr, cleanPnl)
        }

        if (liquidityUsd < 5_000.0 || buyPressure < 45.0 || !runnerProof7403) {
            emit("MOONSHOT_WEAK_CONTEXT_WATCH_7403")
            reasons += "MOONSHOT_FAILED_THESIS_STAYS_MOONSHOT_7403"
            return Decision(PivotMode.WATCH_PROBATION, "MOONSHOT", "WATCH_PROBATION", null, false, reasons, cleanWr, cleanPnl)
        }

        if (runnerProof7403 || reclaimProof) {
            emit("MOONSHOT_PIVOT_MICRO")
            reasons += "MOONSHOT_MICRO_RETRAIN_WITH_LIVE_STRUCTURE_7403"
            return Decision(PivotMode.MOONSHOT_MICRO_RETRAIN, "MOONSHOT", "MOONSHOT_MICRO_RETRAIN", microCap(plannedSizeSol), true, reasons, cleanWr, cleanPnl)
        }
        emit("MOONSHOT_WATCH_NO_STRUCTURE_7403")
        reasons += "MOONSHOT_AWAIT_RUNNER_STRUCTURE_7403"
        return Decision(PivotMode.WATCH_PROBATION, "MOONSHOT", "WATCH_PROBATION", null, false, reasons, cleanWr, cleanPnl)
    }

    private fun pass(lane: String): Decision = Decision(PivotMode.NORMAL_MOONSHOT, lane, lane, null, true, emptyList(), 100.0, 0.0)

    private fun microCap(plannedSizeSol: Double): Double {
        val walletRisk = try { WalletManager.cachedSolBalance() * 0.005 } catch (_: Throwable) { 0.005 }
        return minOf(plannedSizeSol.takeIf { it > 0.0 } ?: 0.01, 0.010, maxOf(0.005, walletRisk)).coerceIn(0.005, 0.010)
    }
}
