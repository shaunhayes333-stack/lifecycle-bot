package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.7663 - leave-one-family-out counterfactual ablation.
 *
 * Computes each evidence family's marginal contribution to the already
 * de-correlated fused utility. No model/provider work is rerun.
 */
object SuperEvidenceAblation7663 {
    private fun fused(familyUtility: Map<SuperEvidenceTopology7651.Family, Double>): Double {
        if (familyUtility.isEmpty()) return 0.0
        val signed = familyUtility.values.sum()
        val mass = familyUtility.values.sumOf { abs(it) }
        val agreement = if (mass <= 1e-9) 1.0 else (abs(signed) / mass).coerceIn(0.0, 1.0)
        val penalty = (0.55 + 0.45 * agreement).coerceIn(0.55, 1.0)
        return (signed * penalty).coerceIn(-6.0, 6.0)
    }

    fun marginals(result: SuperEvidenceTopology7651.Result): Map<SuperEvidenceTopology7651.Family, Double> {
        val full = fused(result.familyUtility)
        if (result.familyUtility.isEmpty()) return emptyMap()
        val out = linkedMapOf<SuperEvidenceTopology7651.Family, Double>()
        result.familyUtility.keys.forEach { family ->
            val reduced = result.familyUtility.filterKeys { it != family }
            out[family] = (full - fused(reduced)).coerceIn(-4.0, 4.0)
        }
        return out
    }
}
