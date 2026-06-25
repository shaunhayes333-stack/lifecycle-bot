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
        val cleanWr = metric?.winRatePct ?: 100.0
        val cleanPnl = metric?.totalSolPnl ?: 0.0
        val scoreBand = try { LosingPatternMemory.scoreBand(score.toInt()) } catch (_: Throwable) { LiveStylePivotRouter.scoreBand(score) }
        val bucket = try { LosingPatternMemory.liveStats("MOONSHOT", score.toInt()) } catch (_: Throwable) { null }
        val lossRate = bucket?.lossRatePct ?: 0.0
        val dangerBucket = scoreBand == "S41-60" && (bucket?.sample ?: 0) >= 8 && lossRate >= 75.0
        val dump = regime.equals("DUMP", true)
        val p = try { LiveProbabilityEngine.forecast("MOONSHOT", score.toInt(), ts.meta.setupQuality, regime) } catch (_: Throwable) { null }
        val pWin = p?.pWin ?: 0.50
        val buyPressure = try { (ts.history.lastOrNull()?.buyRatio ?: 0.0) * 100.0 } catch (_: Throwable) { 0.0 }
        val momentum = try { ts.meta.momScore } catch (_: Throwable) { 0.0 }
        val volume = try { ts.meta.volScore } catch (_: Throwable) { 0.0 }
        val exitCapacityUsd = liquidityUsd
        val reclaimProof = routeProof && basisTrusted && rugProof && (holderProof || momentum >= 6.0 || volume >= 6.0 || buyPressure >= 55.0)
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

        if (dangerBucket && !(goldenGoose && routeProof && reclaimProof)) {
            emit("MOONSHOT_DANGER_BUCKET_PIVOT")
            emit("MOONSHOT_PIVOT_MICRO")
            reasons += "MOONSHOT_DANGER_BUCKET_PIVOT"
            return Decision(PivotMode.MOONSHOT_MICRO_RETRAIN, "MOONSHOT", "DEFENSIVE_PROBE", microCap(plannedSizeSol), true, reasons, cleanWr, cleanPnl)
        }

        val normalAllowed = cleanWr >= 35.0 && cleanPnl >= 0.0 && pWin >= 0.35 && routeProof && exitCapacityUsd >= 5_000.0 && (!dump || reclaimProof)
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

        if (liquidityUsd < 5_000.0 || buyPressure < 45.0) {
            emit("MOONSHOT_RECLASSIFIED_SHITCOIN")
            reasons += "MOONSHOT_RECLASSIFIED_SHITCOIN"
            return Decision(PivotMode.SHITCOIN_MICRO_RECLASSIFIED, "SHITCOIN", "SHITCOIN_MICRO_RECLASSIFIED", microCap(plannedSizeSol), true, reasons, cleanWr, cleanPnl)
        }

        emit("MOONSHOT_PIVOT_MICRO")
        reasons += "MOONSHOT_MICRO_RETRAIN"
        return Decision(PivotMode.MOONSHOT_MICRO_RETRAIN, "MOONSHOT", "MOONSHOT_MICRO_RETRAIN", microCap(plannedSizeSol), true, reasons, cleanWr, cleanPnl)
    }

    private fun pass(lane: String): Decision = Decision(PivotMode.NORMAL_MOONSHOT, lane, lane, null, true, emptyList(), 100.0, 0.0)

    private fun microCap(plannedSizeSol: Double): Double {
        val walletRisk = try { WalletManager.cachedSolBalance() * 0.005 } catch (_: Throwable) { 0.005 }
        return minOf(plannedSizeSol.takeIf { it > 0.0 } ?: 0.01, 0.010, maxOf(0.005, walletRisk)).coerceIn(0.005, 0.010)
    }
}
