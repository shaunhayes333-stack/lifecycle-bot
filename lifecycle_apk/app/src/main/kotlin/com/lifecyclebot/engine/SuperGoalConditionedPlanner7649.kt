
package com.lifecyclebot.engine

import kotlin.math.tanh

/**
 * V5.0.7649 - multi-objective goal-conditioned policy planner.
 *
 * The shared intelligence core keeps one canonical execution authority, but
 * different lanes optimize different economic objectives. This layer builds a
 * Pareto frontier across return, downside, failure risk, upside tail, capital
 * velocity and robustness, then applies a bounded lane-goal nudge.
 */
object SuperGoalConditionedPlanner7649 {
    data class Profile(
        val name: String,
        val returnW: Double,
        val downsideW: Double,
        val failureW: Double,
        val tailW: Double,
        val velocityW: Double,
        val robustnessW: Double,
    )

    data class Point(
        val policy: SuperPolicyTree7638.Policy,
        val returnScore: Double,
        val downsideScore: Double,
        val failureScore: Double,
        val tailScore: Double,
        val velocityScore: Double,
        val robustnessScore: Double,
    )

    data class Decision(
        val profile: Profile,
        val frontier: Set<SuperPolicyTree7638.Policy>,
        val nudges: Map<SuperPolicyTree7638.Policy, Double>,
    ) {
        fun nudgeFor(policy: SuperPolicyTree7638.Policy): Double {
            return nudges[policy] ?: 0.0
        }
    }

    fun profileFor(lane: String): Profile {
        val l = lane.trim().uppercase()
        return when {
            l.contains("CASHGEN") || l.contains("TREASURY") ->
                Profile("CAPITAL_PRESERVATION", 0.20, 0.25, 0.25, 0.05, 0.15, 0.10)
            l.contains("EXPRESS") ->
                Profile("FAST_TURNOVER", 0.22, 0.20, 0.18, 0.08, 0.24, 0.08)
            l.contains("PROJECT_SNIPER") || l.contains("SNIPER") ->
                Profile("EARLY_ASYMMETRY", 0.23, 0.16, 0.14, 0.25, 0.12, 0.10)
            l.contains("MOONSHOT") ->
                Profile("TAIL_CAPTURE", 0.24, 0.16, 0.12, 0.32, 0.04, 0.12)
            l.contains("DIP") ->
                Profile("RECOVERY_EDGE", 0.22, 0.23, 0.20, 0.10, 0.12, 0.13)
            l.contains("BLUECHIP") || l.contains("QUALITY") ->
                Profile("QUALITY_COMPOUND", 0.24, 0.20, 0.18, 0.12, 0.10, 0.16)
            l.contains("SHITCOIN") || l.contains("MANIP") ->
                Profile("ASYMMETRIC_MOMENTUM", 0.24, 0.18, 0.15, 0.25, 0.08, 0.10)
            else ->
                Profile("BALANCED_EDGE", 0.23, 0.21, 0.19, 0.14, 0.10, 0.13)
        }
    }

    fun rank(
        lane: String,
        world: SuperWorldModel7634.Snapshot,
        branches: List<SuperPolicyTree7638.Branch>,
    ): Decision {
        val profile = profileFor(lane)
        val points = branches.associate { b ->
            val transition = try {
                SuperLatentTransitionModel7648.prior(lane, world.latentState, b.policy)
            } catch (_: Throwable) { null }

            val returnScore = tanh(b.meanImagined / 30.0).coerceIn(-1.0, 1.0)
            val downsideScore = tanh(b.downsideCvar / 30.0).coerceIn(-1.0, 1.0)
            val failureScore = (1.0 - 2.0 * b.failureProbability).coerceIn(-1.0, 1.0)
            val tailScore = tanh(b.upsideTail / 45.0).coerceIn(-1.0, 1.0)

            val velocityScore = transition?.let {
                val holdMin = (it.meanHoldMs / 60_000.0).coerceAtLeast(0.0)
                (2.0 / (1.0 + holdMin / 10.0) - 1.0).coerceIn(-1.0, 1.0)
            } ?: 0.0

            val tailSpread = (b.meanImagined - b.downsideCvar).coerceAtLeast(0.0)
            val robustnessScore = (
                1.0 - (tailSpread / 50.0).coerceIn(0.0, 1.0) * 2.0
            ).coerceIn(-1.0, 1.0)

            b.policy to Point(
                policy = b.policy,
                returnScore = returnScore,
                downsideScore = downsideScore,
                failureScore = failureScore,
                tailScore = tailScore,
                velocityScore = velocityScore,
                robustnessScore = robustnessScore,
            )
        }

        fun dominates(a: Point, b: Point): Boolean {
            val av = listOf(
                a.returnScore, a.downsideScore, a.failureScore,
                a.tailScore, a.velocityScore, a.robustnessScore,
            )
            val bv = listOf(
                b.returnScore, b.downsideScore, b.failureScore,
                b.tailScore, b.velocityScore, b.robustnessScore,
            )
            val allAtLeast = av.indices.all { av[it] >= bv[it] - 1e-9 }
            val anyBetter = av.indices.any { av[it] > bv[it] + 1e-9 }
            return allAtLeast && anyBetter
        }

        val frontier = points.values.filter { candidate ->
            points.values.none { other ->
                other.policy != candidate.policy && dominates(other, candidate)
            }
        }.map { it.policy }.toSet()

        val nudges = points.mapValues { (policy, p) ->
            val weighted =
                p.returnScore * profile.returnW +
                    p.downsideScore * profile.downsideW +
                    p.failureScore * profile.failureW +
                    p.tailScore * profile.tailW +
                    p.velocityScore * profile.velocityW +
                    p.robustnessScore * profile.robustnessW

            val paretoBonus = if (policy in frontier) 1.0 else -0.75
            (weighted * 6.0 + paretoBonus).coerceIn(-7.0, 7.0)
        }

        return Decision(
            profile = profile,
            frontier = frontier,
            nudges = nudges,
        )
    }
}
