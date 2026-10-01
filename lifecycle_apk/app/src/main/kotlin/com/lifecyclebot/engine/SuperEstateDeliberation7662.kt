package com.lifecyclebot.engine

/**
 * V5.0.7662 - estate-aware adaptive deliberation.
 *
 * The base 7646 controller reasons from world/memory/critic state. This refiner
 * then allocates extra or reduced local compute based on the wider intelligence
 * estate's agreement, independent-family breadth and learned brain maturity.
 *
 * It changes reasoning depth only; it owns no trade, sizing or safety authority.
 */
object SuperEstateDeliberation7662 {
    fun refine(
        base: SuperDeliberationController7646.Plan,
        topology: SuperEvidenceTopology7651.Result,
        estate: SuperIntelligenceEstate7654.Snapshot,
    ): SuperDeliberationController7646.Plan {
        val conflict = (1.0 - topology.agreement).coerceIn(0.0, 1.0)
        val breadth = (topology.independentFamilies / 7.0).coerceIn(0.0, 1.0)
        val learnedBreadth = estate.breadthConfidence().coerceIn(0.0, 1.0)
        val decorrelationGap = if (kotlin.math.abs(topology.rawUtility) < 1e-6) 0.0
            else ((kotlin.math.abs(topology.rawUtility - topology.decorrelatedUtility) /
                kotlin.math.abs(topology.rawUtility)).coerceIn(0.0, 1.0))

        var delta = 0
        if (conflict >= 0.65) delta += 2
        else if (conflict >= 0.35) delta += 1

        if (breadth >= 0.55 && decorrelationGap >= 0.30) delta += 1

        // Broad mature agreement is a reason to spend less recursive compute,
        // not more. Never reduce a high-risk base plan below depth 2.
        if (topology.agreement >= 0.88 && learnedBreadth >= 0.60 &&
            base.valueAtRisk < 0.55 && base.depth > 1
        ) delta -= 1

        val depth = (base.depth + delta).coerceIn(1, 5)
        val rollout = when (depth) {
            5 -> 21
            4 -> 17
            3 -> 13
            2 -> 9
            else -> 7
        }

        val estateUncertainty = (
            base.uncertainty * 0.70 +
                conflict * 0.20 +
                decorrelationGap * 0.10
            ).coerceIn(0.0, 1.0)

        val reason = when {
            conflict >= 0.65 -> "estate_high_conflict"
            conflict >= 0.35 -> "estate_mixed_conflict"
            breadth >= 0.55 && decorrelationGap >= 0.30 -> "estate_broad_correlated_evidence"
            topology.agreement >= 0.88 && learnedBreadth >= 0.60 && delta < 0 ->
                "estate_mature_consensus_fastpath"
            else -> base.reason
        }

        return base.copy(
            depth = depth,
            rolloutBudget = rollout,
            uncertainty = estateUncertainty,
            reason = reason,
        )
    }
}
