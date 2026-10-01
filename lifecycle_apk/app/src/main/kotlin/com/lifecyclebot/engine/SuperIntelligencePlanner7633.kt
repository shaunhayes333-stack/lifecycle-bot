package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.7633 — SUPER INTELLIGENCE COUNTERFACTUAL PLANNER.
 *
 * Advisory planner over already-computed local evidence. No provider calls,
 * LLM calls, execution, capital reservation or independent safety decision.
 */
object SuperIntelligencePlanner7633 {
    enum class Action(val exposure: Double) {
        WAIT(0.0),
        ENTER_REDUCED(0.35),
        ENTER_BASE(1.0),
        ENTER_CONVICTION(1.35),
    }

    data class ActionScore(
        val action: Action,
        val expectedUtility: Double,
        val downsidePenalty: Double,
        val uncertaintyPenalty: Double,
    )

    data class Plan(
        val chosen: Action,
        val scores: List<ActionScore>,
        val uncertainty: Double,
        val disagreement: Double,
        val pWin: Double,
        val expectancyPct: Double,
        val policyPWin: Double,
        val rationale: String,
    ) {
        fun contributionTag(): String {
            val top = scores.sortedByDescending { it.expectedUtility }.take(3)
                .joinToString(",") { "${it.action.name}:${"%+.1f".format(it.expectedUtility)}" }
            return "superPlan7633(chosen=${chosen.name},p=${"%.2f".format(pWin)}," +
                "E=${"%+.1f".format(expectancyPct)},unc=${"%.2f".format(uncertainty)}," +
                "dis=${"%.2f".format(disagreement)},policy=${"%.2f".format(policyPWin)},u=[$top])"
        }
    }

    fun plan(
        pWin: Double,
        expectancyPct: Double,
        confidence: Double,
        disagreement: Double,
        policyPWin: Double,
        hardSafetyBlocked: Boolean,
    ): Plan {
        val p = pWin.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50
        val e = expectancyPct.takeIf { it.isFinite() } ?: 0.0
        val conf = confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val dis = disagreement.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0
        val policy = policyPWin.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50

        val uncertainty = ((1.0 - conf) * 0.55 + dis * 0.30 + abs(p - policy) * 0.30)
            .coerceIn(0.0, 1.0)

        val scores = Action.entries.map { action ->
            if (action == Action.WAIT) {
                ActionScore(action, 0.0, 0.0, 0.0)
            } else {
                val exposure = action.exposure
                val expected = e * exposure
                val downside = (1.0 - p) * 22.0 * exposure
                val uncertaintyPenalty = uncertainty * 14.0 * exposure
                val convictionPenalty = if (action == Action.ENTER_CONVICTION && p < 0.62) 8.0 else 0.0
                ActionScore(
                    action = action,
                    expectedUtility = expected - downside - uncertaintyPenalty - convictionPenalty,
                    downsidePenalty = downside,
                    uncertaintyPenalty = uncertaintyPenalty + convictionPenalty,
                )
            }
        }

        val chosen = if (hardSafetyBlocked) {
            Action.WAIT
        } else {
            scores.maxByOrNull { it.expectedUtility }?.action ?: Action.WAIT
        }

        val rationale = when {
            hardSafetyBlocked -> "hard_safety_fact"
            chosen == Action.WAIT -> "risk_adjusted_utility_non_positive"
            uncertainty >= 0.65 -> "high_uncertainty_reduced_action"
            chosen == Action.ENTER_CONVICTION -> "positive_ev_high_coherence"
            else -> "positive_risk_adjusted_utility"
        }

        return Plan(
            chosen = chosen,
            scores = scores,
            uncertainty = uncertainty,
            disagreement = dis,
            pWin = p,
            expectancyPct = e,
            policyPWin = policy,
            rationale = rationale,
        )
    }
}
