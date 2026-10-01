package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.7664 - fixed epistemic budget for the expanding intelligence estate.
 *
 * Adding more brains must not increase total conviction simply by headcount.
 * This transformer can only preserve or reduce family magnitude.
 */
object SuperEpistemicBudget7664 {
    data class Result(
        val familyUtility: Map<SuperEvidenceTopology7651.Family, Double>,
        val utility: Double,
        val l1Before: Double,
        val l1After: Double,
        val maxFamilyShare: Double,
    )

    fun apply(topology: SuperEvidenceTopology7651.Result): Result {
        if (topology.familyUtility.isEmpty()) {
            return Result(emptyMap(), 0.0, 0.0, 0.0, 0.0)
        }

        // Per-family cap first: no single family gets more than 2.5 utility.
        val capped = topology.familyUtility.mapValues { (_, v) -> v.coerceIn(-2.5, 2.5) }
        val l1Before = topology.familyUtility.values.sumOf { abs(it) }
        var family = capped
        var l1 = family.values.sumOf { abs(it) }

        // Fixed total evidence budget.
        if (l1 > 6.0 && l1 > 0.0) {
            val scale = 6.0 / l1
            family = family.mapValues { (_, v) -> v * scale }
            l1 = 6.0
        }

        // Concentration guard: with >=3 independent families, one family may
        // not consume >55% of the total L1 budget. Excess is removed, not
        // redistributed, so headcount can never create extra conviction.
        if (family.size >= 3 && l1 > 0.0) {
            val cap = l1 * 0.55
            family = family.mapValues { (_, v) ->
                val m = abs(v)
                if (m <= cap) v else if (v >= 0.0) cap else -cap
            }
            l1 = family.values.sumOf { abs(it) }
        }

        val signed = family.values.sum()
        val mass = family.values.sumOf { abs(it) }
        val agreement = if (mass <= 1e-9) 1.0 else (abs(signed) / mass).coerceIn(0.0, 1.0)
        val penalty = (0.55 + 0.45 * agreement).coerceIn(0.55, 1.0)
        var utility = (signed * penalty).coerceIn(-6.0, 6.0)

        // Budgeting is attenuative only relative to the already-decorrelated
        // topology. Never manufacture a larger same-direction opinion.
        val original = topology.decorrelatedUtility
        if (utility * original >= 0.0 && abs(utility) > abs(original)) {
            utility = original
        }

        val maxShare = if (mass <= 1e-9) 0.0
            else family.values.maxOf { abs(it) } / mass

        return Result(
            familyUtility = family,
            utility = utility,
            l1Before = l1Before,
            l1After = l1,
            maxFamilyShare = maxShare,
        )
    }
}
