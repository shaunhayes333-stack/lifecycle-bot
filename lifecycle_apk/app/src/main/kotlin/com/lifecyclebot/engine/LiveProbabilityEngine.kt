package com.lifecyclebot.engine

/**
 * V5.0.4027 — LIVE PROBABILITY ENGINE FACADE.
 *
 * The bot already had probability brains, but they were scattered:
 * ForwardOutcomeModel, UnifiedPolicyHead, StrategyTelemetry lane priors, and
 * live strategy tuning each exposed a different shape. This facade gives sizing,
 * FDG/reporting, and future tuner work one probability object:
 *
 *   pWin / pRug / E[pnl] / uncertainty / samples / soft size multiplier
 *
 * Doctrine:
 *  - Live-terminal learning only for lane priors.
 *  - No external calls, no LLM, no synchronous network.
 *  - Soft-shape only: never veto, never zero size.
 *  - Bootstrap-safe: neutral until evidence exists.
 */
object LiveProbabilityEngine {
    const val VERSION = "V5.0.4027_LIVE_PROBABILITY_ENGINE"

    data class Edge(
        val lane: String,
        val pWin: Double,
        val pRug: Double,
        val expectedPnlPct: Double,
        val uncertaintyPct: Double,
        val samples: Long,
        val source: String,
        val sizeMult: Double,
        val note: String,
    ) {
        val compact: String get() =
            "$VERSION lane=$lane pWin=${"%.0f".format(pWin * 100)}% pRug=${"%.0f".format(pRug * 100)}% E=${"%+.1f".format(expectedPnlPct)}% ±${uncertaintyPct.toInt()} n=$samples src=$source size×=${"%.2f".format(sizeMult)} $note"
    }

    fun forecast(
        rawLane: String?,
        score: Int,
        quality: String,
        regime: String,
        edgePhase: String = "UNKNOWN",
        candidateConfidence: Double = 0.50,
        mlEntryConfidence: Double = 0.50,
        evRatio: Double = 0.50,
        metaConviction: Double = 0.50,
    ): Edge {
        val lane = canonical(rawLane)
        return try {
            val fwd = ForwardOutcomeModel.forecast(
                lane,
                score.coerceIn(0, 100),
                quality.ifBlank { "U" }.take(3),
                regime.ifBlank { "NORMAL" },
                edgePhase.ifBlank { "UNKNOWN" },
            )
            val laneMetric = StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 1_500)
                .firstOrNull { canonical(it.strategy).equals(lane, ignoreCase = true) }

            val laneSamples = laneMetric?.trades?.toLong() ?: 0L
            val lanePWin = if (laneMetric != null && (laneMetric.wins + laneMetric.losses) > 0) {
                laneMetric.winRatePct.coerceIn(0.0, 100.0) / 100.0
            } else 0.5
            val laneE = laneMetric?.meanPnlPct ?: 0.0
            val laneSol = laneMetric?.totalSolPnl ?: 0.0

            val fwdWeight = when {
                fwd.samples >= 60L -> 0.70
                fwd.samples >= 25L -> 0.55
                fwd.samples >= 10L -> 0.40
                else -> 0.0
            }
            val laneWeight = when {
                laneSamples >= 80L -> 0.35
                laneSamples >= 25L -> 0.25
                laneSamples >= 8L -> 0.15
                else -> 0.0
            }
            val totalW = (fwdWeight + laneWeight).coerceAtLeast(0.0001)
            val pBase = if (fwdWeight + laneWeight > 0.0) {
                ((fwd.pWin * fwdWeight) + (lanePWin * laneWeight)) / totalW
            } else 0.5
            val eBase = if (fwdWeight + laneWeight > 0.0) {
                ((fwd.expectedPnl * fwdWeight) + (laneE * laneWeight)) / totalW
            } else 0.0

            val signals = UnifiedPolicyHead.Signals(
                mlEntryConf = mlEntryConfidence.coerceIn(0.0, 1.0),
                symGreenLight = pBase.coerceIn(0.0, 1.0),
                evRatio = evRatio.coerceIn(0.0, 1.0),
                metaConviction = metaConviction.coerceIn(0.0, 1.0),
                fwdPWin = fwd.pWin.coerceIn(0.0, 1.0),
                candConf = candidateConfidence.coerceIn(0.0, 1.0),
            )
            val policyP = UnifiedPolicyHead.predictWinProb(signals).coerceIn(0.0, 1.0)
            val policyW = if (UnifiedPolicyHead.formatForPipelineDump().contains("bootstrap")) 0.0 else 0.20
            val pWin = ((pBase * (1.0 - policyW)) + (policyP * policyW)).coerceIn(0.02, 0.98)

            val probabilityEdge = (pWin - 0.50) * 1.35
            val pnlEdge = (eBase / 120.0).coerceIn(-0.35, 0.45)
            val solEdge = (laneSol / 0.40).coerceIn(-0.30, 0.35)
            val rugPenalty = fwd.pRug.coerceIn(0.0, 0.80) * 0.75
            val uncertaintyPenalty = (fwd.dispersion / 180.0).coerceIn(0.0, 0.22)
            val mult = (1.0 + probabilityEdge + pnlEdge + solEdge - rugPenalty - uncertaintyPenalty)
                .coerceIn(0.55, 1.60)

            val src = listOfNotNull(
                if (fwdWeight > 0.0) "fwd:${fwd.source}" else null,
                if (laneWeight > 0.0) "lane" else null,
                if (policyW > 0.0) "policy" else null,
            ).joinToString("+").ifBlank { "bootstrap" }
            Edge(lane, pWin, fwd.pRug, eBase, fwd.dispersion, maxOf(fwd.samples, laneSamples), src, mult, if (laneSol > 0.0) "netSOL=${"%+.3f".format(laneSol)}" else "")
        } catch (_: Throwable) {
            Edge(lane, 0.5, 0.0, 0.0, 0.0, 0L, "failopen", 1.0, "")
        }
    }

    fun statusLine(): String = try {
        val rows = StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 1_500)
            .filter { it.trades >= 5 }
            .take(6)
            .map { forecast(it.strategy, 50, "U", "NORMAL") }
        if (rows.isEmpty()) "LiveProbabilityEngine: bootstrap/no mature live lanes"
        else "LiveProbabilityEngine: " + rows.joinToString(" · ") { "${it.lane}:pWin=${"%.0f".format(it.pWin * 100)}% E=${"%+.1f".format(it.expectedPnlPct)}% size×=${"%.2f".format(it.sizeMult)} n=${it.samples}" }
    } catch (_: Throwable) { "LiveProbabilityEngine: unavailable" }

    private fun canonical(raw: String?): String {
        val r = raw?.trim()?.takeIf { it.isNotBlank() } ?: return "STANDARD"
        return try { LiveGrowthDoctrine.canonicalLane(r) } catch (_: Throwable) { r.uppercase() }
    }
}
