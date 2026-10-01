
package com.lifecyclebot.engine

/**
 * V5.0.7638 - bounded local policy-tree search.
 *
 * Searches multi-step policy sequences using the world model, adversarial
 * critic and retrieved episodic prior. Report/advisory only.
 */
object SuperPolicyTree7638 {
    enum class Policy {
        WAIT_REASSESS,
        REDUCED_THEN_SCALE,
        BASE_TACTICAL_HOLD,
        BASE_TACTICAL_BANK,
        CONVICTION_RUNNER,
    }

    data class Branch(
        val policy: Policy,
        val rootAction: SuperIntelligencePlanner7633.Action,
        val utility: Double,
        val rationale: String,
        val meanImagined: Double = utility,
        val downsideCvar: Double = utility,
        val failureProbability: Double = 0.0,
        val upsideTail: Double = utility,
    )

    data class Result(
        val bestPolicy: Policy,
        val rootAction: SuperIntelligencePlanner7633.Action,
        val utility: Double,
        val confidence: Double,
        val selectionPropensity: Double,
        val goalProfile: String,
        val branches: List<Branch>,
    ) {
        fun contributionTag(): String {
            val top = branches.sortedByDescending { it.utility }.take(3).joinToString(",") {
                it.policy.name + ":" + String.format(
                    java.util.Locale.US,
                    "%+.1f/cv=%+.1f/f=%.2f",
                    it.utility,
                    it.downsideCvar,
                    it.failureProbability,
                )
            }
            return String.format(
                java.util.Locale.US,
                "tree7638(best=%s,root=%s,u=%+.1f,conf=%.2f,prop=%.3f,goal=%s,top=[%s])",
                bestPolicy.name,
                rootAction.name,
                utility,
                confidence,
                selectionPropensity,
                goalProfile,
                top,
            )
        }
    }

    fun search(
        world: SuperWorldModel7634.Snapshot,
        critic: SuperAdversarialCritic7635.Review,
        memory: SuperEpisodicRetriever7638.Retrieval,
        deliberation: SuperDeliberationController7646.Plan = SuperDeliberationController7646.shallowPlan(),
        existing: ExistingIntelligenceContext7650.Snapshot? = null,
    ): Result {
        val imp = world.forHorizon(SuperWorldModel7634.Horizon.IMPULSE)
        val tac = world.forHorizon(SuperWorldModel7634.Horizon.TACTICAL)
        val the = world.forHorizon(SuperWorldModel7634.Horizon.THESIS)

        val iu = imp?.utility ?: -5.0
        val tu = tac?.utility ?: -5.0
        val hu = the?.utility ?: -5.0
        val mem = memory.priorUtilityDelta
        val frag = critic.thesisFragility
        val criticPen = critic.convictionPenalty

        fun recursiveLookahead7646(policy: Policy, depth: Int): Double {
            if (depth <= 1) return 0.0
            var total = 0.0
            var discount = 0.58
            var d = 2
            while (d <= depth) {
                val continuation = when (policy) {
                    Policy.WAIT_REASSESS ->
                        ((1.0 - world.disagreement) * 2.0 + (1.0 - world.failureRisk) * 2.0)
                    Policy.REDUCED_THEN_SCALE ->
                        maxOf(tu, hu, 0.0) * 0.45 - frag * 2.0
                    Policy.BASE_TACTICAL_HOLD ->
                        hu * 0.42 + world.tailOpportunity * 2.0 - world.failureRisk * 2.0
                    Policy.BASE_TACTICAL_BANK ->
                        tu * 0.32 + maxOf(iu, 0.0) * 0.15 - world.failureRisk
                    Policy.CONVICTION_RUNNER ->
                        hu * 0.50 + world.tailOpportunity * 4.0 - criticPen * 0.25
                }
                total += continuation * discount
                discount *= 0.58
                d += 1
            }
            return total.coerceIn(-12.0, 12.0)
        }

        val rawBranches = listOf(
            Branch(
                Policy.WAIT_REASSESS,
                SuperIntelligencePlanner7633.Action.WAIT,
                0.0 + (if (frag >= 0.70) 4.0 else 0.0) +
                    recursiveLookahead7646(Policy.WAIT_REASSESS, deliberation.depth),
                "preserve_optionality",
            ),
            Branch(
                Policy.REDUCED_THEN_SCALE,
                SuperIntelligencePlanner7633.Action.ENTER_REDUCED,
                iu * 0.35 + tu * 0.55 + hu.coerceAtLeast(0.0) * 0.10 +
                    mem * 0.45 - frag * 4.0 +
                    recursiveLookahead7646(Policy.REDUCED_THEN_SCALE, deliberation.depth),
                "small_initial_risk_then_confirm",
            ),
            Branch(
                Policy.BASE_TACTICAL_HOLD,
                SuperIntelligencePlanner7633.Action.ENTER_BASE,
                tu + hu * 0.25 + mem * 0.65 - criticPen * 0.40 +
                    recursiveLookahead7646(Policy.BASE_TACTICAL_HOLD, deliberation.depth),
                "base_entry_with_tactical_thesis",
            ),
            Branch(
                Policy.BASE_TACTICAL_BANK,
                SuperIntelligencePlanner7633.Action.ENTER_BASE,
                iu * 0.30 + tu * 0.80 + mem * 0.55 -
                    world.failureRisk * 5.0 +
                    (if (world.latentState == SuperWorldModel7634.LatentState.DISTRIBUTING) 2.0 else 0.0) +
                    recursiveLookahead7646(Policy.BASE_TACTICAL_BANK, deliberation.depth),
                "base_entry_bank_before_thesis_decay",
            ),
            Branch(
                Policy.CONVICTION_RUNNER,
                SuperIntelligencePlanner7633.Action.ENTER_CONVICTION,
                hu * 1.15 + world.tailOpportunity * 10.0 + mem -
                    criticPen -
                    (if (world.trajectorySlopePct < 0.0) 8.0 else 0.0) +
                    recursiveLookahead7646(Policy.CONVICTION_RUNNER, deliberation.depth),
                "thesis_runner_if_robust",
            ),
        )

        val branches = rawBranches.map { b ->
            val learnedPrior7644 = try {
                SuperPolicyBandit7644.policyPrior(
                    lane = world.lane,
                    state = world.latentState,
                    policy = b.policy,
                )
            } catch (_: Throwable) { 0.0 }
            val existingPrior7650 = existing?.policyPrior(b.policy, world.latentState) ?: 0.0
            val causalLift7647 = try {
                SuperCausalPolicyEvaluator7647.policyLift(
                    lane = world.lane,
                    state = world.latentState,
                    policy = b.policy,
                )
            } catch (_: Throwable) { 0.0 }
            val transition7648 = try {
                SuperLatentTransitionModel7648.prior(
                    lane = world.lane,
                    state = world.latentState,
                    policy = b.policy,
                )
            } catch (_: Throwable) { null }
            val imagined = SuperImaginationRollout7643.evaluate(
                world = world,
                critic = critic,
                memory = memory,
                policy = b.policy.name,
                exposure = b.rootAction.exposure,
                baseUtility = b.utility + learnedPrior7644 + causalLift7647 + existingPrior7650,
                rolloutBudget = deliberation.rolloutBudget,
                transition = transition7648,
            )
            b.copy(
                utility = imagined.robustUtility,
                meanImagined = imagined.meanUtility,
                downsideCvar = imagined.downsideCvar,
                failureProbability = imagined.failureProbability,
                upsideTail = imagined.upsideP90,
            )
        }

        val goal7649 = SuperGoalConditionedPlanner7649.rank(
            lane = world.lane,
            world = world,
            branches = branches,
        )
        val goalAdjustedBranches7649 = branches.map { b ->
            b.copy(utility = b.utility + goal7649.nudgeFor(b.policy))
        }

        val best = goalAdjustedBranches7649.maxByOrNull { it.utility } ?: goalAdjustedBranches7649.first()
        val sorted = goalAdjustedBranches7649.sortedByDescending { it.utility }
        val margin = if (sorted.size > 1) sorted[0].utility - sorted[1].utility else 0.0
        val confidence = (
            0.35 +
                memory.confidence * 0.25 +
                (1.0 - frag) * 0.25 +
                (margin / 20.0).coerceIn(0.0, 1.0) * 0.15
            ).coerceIn(0.0, 1.0)

        // V5.0.7647 - softmax propensity over the robust utilities. This is
        // NOT used to randomize execution; it records how strongly the policy
        // tree preferred the selected branch so terminal learning can correct
        // selection bias with bounded inverse-propensity weighting.
        val maxU7647 = goalAdjustedBranches7649.maxOfOrNull { it.utility } ?: 0.0
        val temperature7647 = 6.0
        val propWeights7647 = goalAdjustedBranches7649.associate { b ->
            b.policy to kotlin.math.exp(((b.utility - maxU7647) / temperature7647).coerceIn(-12.0, 0.0))
        }
        val propSum7647 = propWeights7647.values.sum().coerceAtLeast(1e-9)
        val selectionPropensity7647 =
            ((propWeights7647[best.policy] ?: 0.0) / propSum7647).coerceIn(0.02, 1.0)

        return Result(
            bestPolicy = best.policy,
            rootAction = best.rootAction,
            utility = best.utility,
            confidence = confidence,
            selectionPropensity = selectionPropensity7647,
            goalProfile = goal7649.profile.name,
            branches = goalAdjustedBranches7649,
        )
    }
}
