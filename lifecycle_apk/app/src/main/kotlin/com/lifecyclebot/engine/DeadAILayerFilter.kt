package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z31 — Dead AI layer filter.
 *
 * Operator-reported FDG corruption: layers like CapitalEfficiencyAI,
 * CorrelationHedgeAI, ExecutionCostPredictorAI, FundingRateAwarenessAI,
 * NewsShockAI, OperatorFingerprintAI, OrderbookImbalancePulseAI report
 * 95–100% zero contributions — that's not "dead", that's
 * not-applicable to the asset / not-yet-fed.
 *
 * Policy:
 *   - Layers in `notApplicableSet` are tagged DISABLED_NOT_APPLICABLE
 *     and excluded from FDG normalisation.
 *   - Layers in `repairList` are flagged for repair — caller (FDG)
 *     should not include them in score until they produce non-zero
 *     contributions or the operator reviews them.
 *   - Per-layer contribution is logged so the FDG decision becomes
 *     explainable.
 */
object DeadAILayerFilter {

    enum class LayerHealth { HEALTHY, ZERO_STARVED, DISABLED_NOT_APPLICABLE }

    /** Layers that simply don't apply to a given asset class — e.g.
     *  FundingRateAwarenessAI is irrelevant to spot Solana memes. */
    private val notApplicable = mutableSetOf<String>()

    private val contributionsByLayer = ConcurrentHashMap<String, ContributionStat>()

    data class ContributionStat(
        val layer: String,
        val totalSamples: Int,
        val zeroSamples: Int,
    ) {
        val zeroRatio: Double get() = if (totalSamples > 0) zeroSamples.toDouble() / totalSamples else 0.0
        val isStarved: Boolean get() = totalSamples >= 25 && zeroRatio >= 0.90
    }

    fun markNotApplicable(layer: String) {
        notApplicable.add(layer)
    }

    fun isNotApplicable(layer: String): Boolean = layer in notApplicable

    fun recordContribution(layer: String, value: Double) {
        contributionsByLayer.compute(layer) { _, prev ->
            val p = prev ?: ContributionStat(layer, 0, 0)
            ContributionStat(
                layer = layer,
                totalSamples = p.totalSamples + 1,
                zeroSamples = p.zeroSamples + (if (value == 0.0) 1 else 0),
            )
        }
    }

    fun health(layer: String): LayerHealth {
        if (isNotApplicable(layer)) return LayerHealth.DISABLED_NOT_APPLICABLE
        val s = contributionsByLayer[layer] ?: return LayerHealth.HEALTHY
        return if (s.isStarved) LayerHealth.ZERO_STARVED else LayerHealth.HEALTHY
    }

    fun repairList(): List<String> =
        contributionsByLayer.values.filter { it.isStarved }.map { it.layer }

    fun snapshot(): Map<String, LayerHealth> =
        contributionsByLayer.mapValues { health(it.key) }
            .toMutableMap().apply {
                for (n in notApplicable) put(n, LayerHealth.DISABLED_NOT_APPLICABLE)
            }

    fun reset() {
        contributionsByLayer.clear()
    }
}
