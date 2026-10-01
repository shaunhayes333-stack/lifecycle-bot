
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
                "tree7638(best=%s,root=%s,u=%+.1f,conf=%.2f,top=[%s])",
                bestPolicy.name,
                rootAction.name,
                utility,
                confidence,
                top,
            )
        }
    }

    fun search(
        world: SuperWorldModel7634.Snapshot,
        critic: SuperAdversarialCritic7635.Review,
        memory: SuperEpisodicRetriever7638.Retrieval,
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

        val rawBranches = listOf(
            Branch(
                Policy.WAIT_REASSESS,
                SuperIntelligencePlanner7633.Action.WAIT,
                0.0 + if (frag >= 0.70) 4.0 else 0.0,
                "preserve_optionality",
            ),
            Branch(
                Policy.REDUCED_THEN_SCALE,
                SuperIntelligencePlanner7633.Action.ENTER_REDUCED,
                iu * 0.35 + tu * 0.55 + hu.coerceAtLeast(0.0) * 0.10 +
                    mem * 0.45 - frag * 4.0,
                "small_initial_risk_then_confirm",
            ),
            Branch(
                Policy.BASE_TACTICAL_HOLD,
                SuperIntelligencePlanner7633.Action.ENTER_BASE,
                tu + hu * 0.25 + mem * 0.65 - criticPen * 0.40,
                "base_entry_with_tactical_thesis",
            ),
            Branch(
                Policy.BASE_TACTICAL_BANK,
                SuperIntelligencePlanner7633.Action.ENTER_BASE,
                iu * 0.30 + tu * 0.80 + mem * 0.55 -
                    world.failureRisk * 5.0 +
                    if (world.latentState == SuperWorldModel7634.LatentState.DISTRIBUTING) 2.0 else 0.0,
                "base_entry_bank_before_thesis_decay",
            ),
            Branch(
                Policy.CONVICTION_RUNNER,
                SuperIntelligencePlanner7633.Action.ENTER_CONVICTION,
                hu * 1.15 + world.tailOpportunity * 10.0 + mem -
                    criticPen -
                    if (world.trajectorySlopePct < 0.0) 8.0 else 0.0,
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
            val imagined = SuperImaginationRollout7643.evaluate(
                world = world,
                critic = critic,
                memory = memory,
                policy = b.policy.name,
                exposure = b.rootAction.exposure,
                baseUtility = b.utility + learnedPrior7644,
            )
            b.copy(
                utility = imagined.robustUtility,
                meanImagined = imagined.meanUtility,
                downsideCvar = imagined.downsideCvar,
                failureProbability = imagined.failureProbability,
                upsideTail = imagined.upsideP90,
            )
        }

        val best = branches.maxByOrNull { it.utility } ?: branches.first()
        val sorted = branches.sortedByDescending { it.utility }
        val margin = if (sorted.size > 1) sorted[0].utility - sorted[1].utility else 0.0
        val confidence = (
            0.35 +
                memory.confidence * 0.25 +
                (1.0 - frag) * 0.25 +
                (margin / 20.0).coerceIn(0.0, 1.0) * 0.15
            ).coerceIn(0.0, 1.0)

        return Result(
            bestPolicy = best.policy,
            rootAction = best.rootAction,
            utility = best.utility,
            confidence = confidence,
            branches = branches,
        )
    }
}
