
package com.lifecyclebot.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * V5.0.7634 - hierarchical multi-horizon world model.
 *
 * Derived planning ensemble over local learned state only. It performs no
 * provider I/O, no LLM calls, no execution, and owns no safety authority.
 */
object SuperWorldModel7634 {
    enum class Horizon(val seconds: Int) {
        IMPULSE(30),
        TACTICAL(300),
        THESIS(1800),
    }

    enum class LatentState {
        ACCELERATING,
        TRENDING,
        MEAN_REVERTING,
        DISTRIBUTING,
        FRAGILE,
        UNCERTAIN,
    }

    data class HorizonForecast(
        val horizon: Horizon,
        val pWin: Double,
        val expectedPnlPct: Double,
        val failureRisk: Double,
        val rugRisk: Double,
        val dispersionPct: Double,
        val uncertainty: Double,
        val utility: Double,
    )

    data class Snapshot(
        val lane: String,
        val latentState: LatentState,
        val forecasts: List<HorizonForecast>,
        val trajectorySlopePct: Double,
        val tailOpportunity: Double,
        val failureRisk: Double,
        val disagreement: Double,
        val modelBreadth: Int,
        val source: String,
    ) {
        fun forHorizon(h: Horizon): HorizonForecast? =
            forecasts.firstOrNull { it.horizon == h }

        fun contributionTag(): String {
            val h = forecasts.joinToString(",") { f ->
                String.format(
                    java.util.Locale.US,
                    "%s:p=%.2f/E=%+.1f/u=%.2f",
                    f.horizon.name,
                    f.pWin,
                    f.expectedPnlPct,
                    f.uncertainty,
                )
            }
            return String.format(
                java.util.Locale.US,
                "world7634(state=%s,slope=%+.1f,tail=%.2f,fail=%.2f,dis=%.2f,h=[%s])",
                latentState.name,
                trajectorySlopePct,
                tailOpportunity,
                failureRisk,
                disagreement,
                h,
            )
        }
    }

    fun forecast(
        lane: String,
        score: Int,
        quality: String,
        regime: String,
        edgePhase: String,
        basePWin: Double,
        baseExpectancyPct: Double,
        baseConfidence: Double,
        disagreement: Double,
    ): Snapshot {
        val laneKey = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val pBase = basePWin.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50
        val eBase = baseExpectancyPct.takeIf { it.isFinite() } ?: 0.0
        val conf = baseConfidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val dis = disagreement.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0

        val fwd = try {
            ForwardOutcomeModel.forecast(
                laneKey,
                score.coerceIn(0, 100),
                quality.ifBlank { "U" }.take(3),
                regime.ifBlank { "NORMAL" },
                edgePhase.ifBlank { "UNKNOWN" },
            )
        } catch (_: Throwable) { null }

        val live = try {
            LiveProbabilityEngine.forecast(
                rawLane = laneKey,
                score = score.coerceIn(0, 100),
                quality = quality.ifBlank { "U" },
                regime = regime.ifBlank { "NORMAL" },
                edgePhase = edgePhase.ifBlank { "UNKNOWN" },
                candidateConfidence = pBase,
            )
        } catch (_: Throwable) { null }

        val pInputs = mutableListOf(pBase)
        if (fwd?.pWin?.isFinite() == true) pInputs += fwd.pWin
        if (live?.pWin?.isFinite() == true) pInputs += live.pWin
        val learnedP = pInputs.average().coerceIn(0.0, 1.0)

        val eInputs = mutableListOf(eBase)
        if (fwd?.expectedPnl?.isFinite() == true) eInputs += fwd.expectedPnl
        if (live?.expectedPnlPct?.isFinite() == true) eInputs += live.expectedPnlPct
        val learnedE = eInputs.average()

        val rug = max(
            fwd?.pRug?.takeIf { it.isFinite() } ?: 0.0,
            live?.pRug?.takeIf { it.isFinite() } ?: 0.0,
        ).coerceIn(0.0, 1.0)

        val dInputs = mutableListOf<Double>()
        fwd?.dispersion?.takeIf { it.isFinite() && it >= 0.0 }?.let { dInputs += it }
        live?.uncertaintyPct?.takeIf { it.isFinite() && it >= 0.0 }?.let { dInputs += it }
        val dispersion = if (dInputs.isEmpty()) 50.0 else dInputs.average()

        val runner = try { RunnerExitProfile7277.isRunnerLane(laneKey) } catch (_: Throwable) { false }
        val r = regime.trim().uppercase()
        val dump = r.contains("DUMP") || r.contains("RISK_OFF") || r.contains("HOSTILE")
        val chop = r.contains("CHOP") || r.contains("RANGE")
        val pump = r.contains("PUMP") || r.contains("BULL") || r.contains("RISK_ON")

        data class Shape(val p: Double, val e: Double, val risk: Double, val disp: Double)

        fun shape(h: Horizon): Shape {
            val horizonP = when (h) {
                Horizon.IMPULSE -> learnedP + if (pump) 0.05 else if (dump) -0.06 else 0.0
                Horizon.TACTICAL -> learnedP + if (chop) -0.03 else if (pump) 0.03 else if (dump) -0.08 else 0.0
                Horizon.THESIS -> learnedP + when {
                    runner && pump -> 0.07
                    runner && !dump -> 0.03
                    !runner && chop -> -0.08
                    !runner -> -0.05
                    else -> -0.10
                }
            }.coerceIn(0.02, 0.98)

            val horizonE = learnedE * when (h) {
                Horizon.IMPULSE -> if (runner) 0.45 else 0.75
                Horizon.TACTICAL -> 1.0
                Horizon.THESIS -> if (runner) 1.35 else 0.65
            } * when {
                dump -> 0.55
                chop && h == Horizon.THESIS -> 0.70
                pump -> 1.10
                else -> 1.0
            }

            val horizonRisk = (
                (1.0 - horizonP) * 0.55 +
                    rug * 0.30 +
                    dis * 0.20
                ).coerceIn(0.0, 1.0)

            val horizonDisp = dispersion * when (h) {
                Horizon.IMPULSE -> 0.75
                Horizon.TACTICAL -> 1.0
                Horizon.THESIS -> if (runner) 1.25 else 1.10
            }

            return Shape(horizonP, horizonE, horizonRisk, horizonDisp)
        }

        val forecasts = Horizon.entries.map { h ->
            val sh = shape(h)
            val baseUncertainty = (
                (1.0 - conf) * 0.40 +
                    dis * 0.35 +
                    (sh.disp / 100.0).coerceIn(0.0, 1.0) * 0.25
                ).coerceIn(0.0, 1.0)
            val reliability7637 = try {
                SuperIntelligenceCalibration7636.horizonReliability(h)
            } catch (_: Throwable) { 1.0 }
            val uncertainty = (baseUncertainty / reliability7637.coerceAtLeast(0.60))
                .coerceIn(0.0, 1.0)
            val utility = sh.e - sh.risk * 20.0 - uncertainty * 12.0
            HorizonForecast(
                horizon = h,
                pWin = sh.p,
                expectedPnlPct = sh.e,
                failureRisk = sh.risk,
                rugRisk = rug,
                dispersionPct = sh.disp,
                uncertainty = uncertainty,
                utility = utility,
            )
        }

        val impulse = forecasts.first { it.horizon == Horizon.IMPULSE }
        val thesis = forecasts.first { it.horizon == Horizon.THESIS }
        val slope = thesis.expectedPnlPct - impulse.expectedPnlPct
        val tail = (
            (if (runner) 0.35 else 0.0) +
                (thesis.pWin - 0.50).coerceAtLeast(0.0) * 0.8 +
                (thesis.expectedPnlPct / 100.0).coerceIn(0.0, 0.6)
            ).coerceIn(0.0, 1.0)
        val failure = forecasts.maxOf { it.failureRisk }

        val latent = when {
            rug >= 0.25 || failure >= 0.72 -> LatentState.FRAGILE
            dis >= 0.70 -> LatentState.UNCERTAIN
            impulse.utility > 0.0 && slope > 8.0 -> LatentState.ACCELERATING
            thesis.utility > 0.0 && slope >= -5.0 -> LatentState.TRENDING
            impulse.utility > 0.0 && thesis.utility < 0.0 -> LatentState.DISTRIBUTING
            chop && abs(slope) < 8.0 -> LatentState.MEAN_REVERTING
            else -> LatentState.UNCERTAIN
        }

        return Snapshot(
            lane = laneKey,
            latentState = latent,
            forecasts = forecasts,
            trajectorySlopePct = slope,
            tailOpportunity = tail,
            failureRisk = failure,
            disagreement = dis,
            modelBreadth = 1 + (if (fwd != null) 1 else 0) + (if (live != null) 1 else 0),
            source = "derived_ensemble:fwd+live+oracle",
        )
    }
}
