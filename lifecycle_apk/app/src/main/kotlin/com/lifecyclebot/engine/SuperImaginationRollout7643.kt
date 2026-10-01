
package com.lifecyclebot.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * V5.0.7643 - distributional imagination rollout engine.
 *
 * Generates a small deterministic ensemble of imagined policy outcomes from
 * the learned world distribution. No randomness, provider I/O, LLM, execution,
 * or capital authority. The purpose is risk-sensitive policy comparison.
 */
object SuperImaginationRollout7643 {
    data class Distribution(
        val policy: String,
        val meanUtility: Double,
        val medianUtility: Double,
        val downsideCvar: Double,
        val downsideP10: Double,
        val upsideP90: Double,
        val failureProbability: Double,
        val robustUtility: Double,
        val rollouts: Int,
    )

    private fun shocksForBudget7646(rawBudget: Int): DoubleArray {
        val n = rawBudget.coerceIn(5, 21)
        val lo = -1.60
        val hi = 1.55
        val step = (hi - lo) / (n - 1).toDouble()
        return DoubleArray(n) { i -> lo + step * i.toDouble() }
    }

    fun evaluate(
        world: SuperWorldModel7634.Snapshot,
        critic: SuperAdversarialCritic7635.Review,
        memory: SuperEpisodicRetriever7638.Retrieval,
        policy: String,
        exposure: Double,
        baseUtility: Double,
        rolloutBudget: Int = 11,
    ): Distribution {
        if (exposure <= 0.0 || policy.contains("WAIT")) {
            return Distribution(
                policy = policy,
                meanUtility = baseUtility,
                medianUtility = baseUtility,
                downsideCvar = baseUtility,
                downsideP10 = baseUtility,
                upsideP90 = baseUtility,
                failureProbability = 0.0,
                robustUtility = baseUtility,
                rollouts = 1,
            )
        }

        val h = when {
            policy.contains("CONVICTION") -> world.forHorizon(SuperWorldModel7634.Horizon.THESIS)
            policy.contains("TACTICAL") -> world.forHorizon(SuperWorldModel7634.Horizon.TACTICAL)
            else -> world.forHorizon(SuperWorldModel7634.Horizon.IMPULSE)
        }

        val uncertainty = h?.uncertainty ?: world.disagreement
        val dispersion = (h?.dispersionPct ?: 40.0).coerceAtLeast(1.0)
        val failureRisk = max(h?.failureRisk ?: world.failureRisk, world.failureRisk)
        val memoryPrior = memory.priorUtilityDelta * memory.confidence
        val criticDrag = critic.thesisFragility * critic.convictionPenalty
        val tailBoost = if (policy.contains("CONVICTION")) world.tailOpportunity * 8.0 else 0.0

        val scenarioScale = (
            dispersion * 0.18 +
                uncertainty * 14.0 +
                world.disagreement * 10.0 +
                failureRisk * 12.0
            ).coerceIn(3.0, 45.0)

        val shocks7646 = shocksForBudget7646(rolloutBudget)
        val values = shocks7646.mapIndexed { idx, shock ->
            val asymmetry = when {
                shock < 0.0 -> 1.0 + failureRisk * 0.75 + critic.thesisFragility * 0.35
                else -> 1.0 + world.tailOpportunity * 0.45
            }
            val pathMemory = memoryPrior * when {
                idx < (shocks7646.size * 0.27).toInt().coerceAtLeast(1) -> 0.35
                idx >= (shocks7646.size * 0.73).toInt().coerceAtMost(shocks7646.lastIndex) -> 1.0
                else -> 0.65
            }
            val criticTerm = if (shock < 0.0) criticDrag * 0.45 else criticDrag * 0.12
            val shockTerm = shock * scenarioScale * asymmetry * exposure
            baseUtility + shockTerm + pathMemory + tailBoost * max(shock, 0.0) - criticTerm
        }.sorted()

        val n = values.size
        val mean = values.average()
        val median = values[n / 2]
        val p10 = values[(n * 0.10).toInt().coerceIn(0, n - 1)]
        val p90 = values[(n * 0.90).toInt().coerceIn(0, n - 1)]
        val cvarCount = max(2, (n * 0.25).toInt())
        val cvar = values.take(cvarCount).average()
        val failureProb = values.count { it < 0.0 }.toDouble() / n.toDouble()

        // Robust objective: reward mean, but insist the lower tail matters.
        // Conviction cannot hide catastrophic scenarios behind a strong average.
        val robust = (
            mean * 0.55 +
                median * 0.15 +
                cvar * 0.30 -
                failureProb * 6.0
            )

        return Distribution(
            policy = policy,
            meanUtility = mean,
            medianUtility = median,
            downsideCvar = cvar,
            downsideP10 = p10,
            upsideP90 = p90,
            failureProbability = failureProb,
            robustUtility = robust,
            rollouts = n,
        )
    }
}
