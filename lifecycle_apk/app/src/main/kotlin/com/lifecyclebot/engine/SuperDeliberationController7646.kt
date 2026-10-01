
package com.lifecyclebot.engine

/**
 * V5.0.7646 - adaptive deliberation controller.
 *
 * Allocates local reasoning depth and deterministic imagination budget
 * according to novelty, uncertainty, downside risk and upside optionality.
 */
object SuperDeliberationController7646 {
    data class Plan(
        val depth: Int,
        val rolloutBudget: Int,
        val novelty: Double,
        val uncertainty: Double,
        val valueAtRisk: Double,
        val opportunity: Double,
        val reason: String,
    ) {
        fun contributionTag(): String {
            return String.format(
                java.util.Locale.US,
                "delib7646(depth=%d,roll=%d,nov=%.2f,unc=%.2f,var=%.2f,opp=%.2f,%s)",
                depth,
                rolloutBudget,
                novelty,
                uncertainty,
                valueAtRisk,
                opportunity,
                reason,
            )
        }
    }

    fun shallowPlan(): Plan {
        return Plan(
            depth = 1,
            rolloutBudget = 7,
            novelty = 0.0,
            uncertainty = 0.0,
            valueAtRisk = 0.0,
            opportunity = 0.0,
            reason = "default_shallow",
        )
    }

    fun plan(
        world: SuperWorldModel7634.Snapshot,
        critic: SuperAdversarialCritic7635.Review,
        memory: SuperEpisodicRetriever7638.Retrieval,
    ): Plan {
        val novelty = (1.0 - memory.confidence).coerceIn(0.0, 1.0)
        val avgUncertainty = world.forecasts
            .map { it.uncertainty }
            .average()
            .takeIf { it.isFinite() }
            ?: world.disagreement
        val uncertainty = (
            avgUncertainty * 0.45 +
                world.disagreement * 0.30 +
                critic.thesisFragility * 0.25
            ).coerceIn(0.0, 1.0)

        val worstFailure = world.forecasts.maxOfOrNull { it.failureRisk } ?: world.failureRisk
        val valueAtRisk = (
            world.failureRisk * 0.55 +
                critic.thesisFragility * 0.30 +
                worstFailure * 0.15
            ).coerceIn(0.0, 1.0)

        val bestUtility = world.forecasts.maxOfOrNull { it.utility.coerceAtLeast(0.0) } ?: 0.0
        val opportunity = (
            world.tailOpportunity * 0.45 +
                (bestUtility / 30.0).coerceIn(0.0, 1.0) * 0.35 +
                (if (world.trajectorySlopePct > 0.0) 0.20 else 0.0)
            ).coerceIn(0.0, 1.0)

        val complexity = (
            novelty * 0.25 +
                uncertainty * 0.35 +
                valueAtRisk * 0.25 +
                opportunity * 0.15
            ).coerceIn(0.0, 1.0)

        val depth = when {
            complexity >= 0.78 -> 5
            complexity >= 0.60 -> 4
            complexity >= 0.42 -> 3
            complexity >= 0.24 -> 2
            else -> 1
        }
        val rolloutBudget = when (depth) {
            5 -> 21
            4 -> 17
            3 -> 13
            2 -> 9
            else -> 7
        }

        val reason = when {
            novelty >= 0.75 && uncertainty >= 0.60 -> "novel_and_uncertain"
            valueAtRisk >= 0.70 -> "high_downside_risk"
            opportunity >= 0.75 -> "large_optional_upside"
            critic.thesisFragility >= 0.65 -> "fragile_thesis_needs_review"
            world.disagreement >= 0.60 -> "model_disagreement"
            depth == 1 -> "familiar_low_conflict"
            else -> "mixed_complexity"
        }

        return Plan(
            depth = depth,
            rolloutBudget = rolloutBudget,
            novelty = novelty,
            uncertainty = uncertainty,
            valueAtRisk = valueAtRisk,
            opportunity = opportunity,
            reason = reason,
        )
    }
}
