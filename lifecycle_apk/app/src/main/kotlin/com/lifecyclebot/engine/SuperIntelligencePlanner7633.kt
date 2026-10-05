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
        lane: String = "STANDARD",
        world: SuperWorldModel7634.Snapshot? = null,
        critic: SuperAdversarialCritic7635.Review? = null,
        tree: SuperPolicyTree7638.Result? = null,
        arbiter: SuperReasoningArbiter7639.Decision? = null,
    ): Plan {
        val p = pWin.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50
        val e = expectancyPct.takeIf { it.isFinite() } ?: 0.0
        val conf = confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val dis = disagreement.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0
        val policy = policyPWin.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50
        val tailLane7801 = try { com.lifecyclebot.engine.truth.SpecialistObjective7801.isTailLane(lane) } catch (_: Throwable) { false }

        val uncertainty = ((1.0 - conf) * 0.55 + dis * 0.30 + abs(p - policy) * 0.30)
            .coerceIn(0.0, 1.0)

        val scores = Action.entries.map { action ->
            if (action == Action.WAIT) {
                ActionScore(action, 0.0, 0.0, 0.0)
            } else {
                val exposure = action.exposure
                val horizon = when (action) {
                    Action.ENTER_REDUCED -> SuperWorldModel7634.Horizon.IMPULSE
                    Action.ENTER_BASE -> SuperWorldModel7634.Horizon.TACTICAL
                    Action.ENTER_CONVICTION -> SuperWorldModel7634.Horizon.THESIS
                    Action.WAIT -> SuperWorldModel7634.Horizon.TACTICAL
                }
                val hf = world?.forHorizon(horizon)
                val expected = (hf?.expectedPnlPct ?: e) * exposure
                val fallbackFailure7801 = if (tailLane7801) {
                    (((-e)/60.0).coerceIn(0.0,1.0)*0.55 + dis*0.25 + (1.0-conf)*0.20).coerceIn(0.0,1.0)
                } else (1.0-p)
                val downside = (hf?.failureRisk ?: fallbackFailure7801) * 22.0 * exposure
                val worldUncertainty = hf?.uncertainty ?: uncertainty
                val uncertaintyPenalty = worldUncertainty * 14.0 * exposure
                val trajectoryPenalty = if (
                    action == Action.ENTER_CONVICTION &&
                    world != null &&
                    world.trajectorySlopePct < 0.0
                ) 8.0 else 0.0
                val criticWeight7639 = arbiter?.criticWeight ?: 1.0
                val criticPenalty = when {
                    critic == null -> 0.0
                    action == Action.ENTER_CONVICTION -> critic.convictionPenalty * criticWeight7639
                    action == Action.ENTER_BASE -> critic.convictionPenalty * 0.45 * criticWeight7639
                    action == Action.ENTER_REDUCED -> critic.convictionPenalty * 0.15 * criticWeight7639
                    else -> 0.0
                }
                val convictionPenalty = when {
                    action != Action.ENTER_CONVICTION -> 0.0
                    tailLane7801 && e <= 0.0 -> 8.0
                    tailLane7801 && p < 0.05 -> 5.0
                    !tailLane7801 && p < 0.62 -> 8.0
                    else -> 0.0
                }
                val treeWeight7639 = arbiter?.treeWeight ?: 1.0
                val treeBias = when {
                    tree == null -> 0.0
                    tree.rootAction == action -> 4.0 * tree.confidence * treeWeight7639
                    tree.rootAction == Action.WAIT -> -5.0 * tree.confidence * treeWeight7639
                    else -> -1.5 * tree.confidence * treeWeight7639
                }
                ActionScore(
                    action = action,
                    expectedUtility = expected - downside - uncertaintyPenalty - convictionPenalty - trajectoryPenalty - criticPenalty + treeBias,
                    downsidePenalty = downside,
                    uncertaintyPenalty = uncertaintyPenalty + convictionPenalty + trajectoryPenalty + criticPenalty,
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
