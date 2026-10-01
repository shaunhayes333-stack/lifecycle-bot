
package com.lifecyclebot.engine

/**
 * V5.0.7650 - adapter over intelligence AATE already had.
 *
 * This object creates no new market opinion. It reads existing cached/local
 * authorities and translates them into bounded planner priors while explicitly
 * avoiding re-running specialist brains or provider work.
 */
object ExistingIntelligenceContext7650 {
    data class Snapshot(
        val lane: String,
        val specialistEligible: Boolean?,
        val specialistScore: Int?,
        val specialistConfidence: Int?,
        val ultimateScoreBias: Int,
        val ultimateSizeMult: Double,
        val legacyConsensusMult: Double,
        val hypothesisSizeBias: Double,
        val reviewedLabBias: Double,
        val mctsPolicy: CounterfactualReplayEngine.AlternativeKind?,
        val mctsExpectedDeltaPct: Double,
        val mctsConfidence: Double,
        val sources: Set<String>,
    ) {
        fun policyPrior(policy: SuperPolicyTree7638.Policy): Double {
            var u = 0.0

            // Native specialist opinion is already the lane's own brain output.
            if (specialistEligible == true) {
                val scoreTerm = (((specialistScore ?: 50) - 50) / 50.0) * 2.5
                val confTerm = (((specialistConfidence ?: 50) - 50) / 50.0) * 1.5
                u += scoreTerm + confTerm
            } else if (specialistEligible == false) {
                u -= 1.5
            }

            // UltimateEdge is itself a cache over existing semantic/source/route
            // intelligence, so keep it small to avoid double counting.
            u += (ultimateScoreBias * 0.30).coerceIn(-1.5, 1.5)
            u += ((ultimateSizeMult - 1.0) * 6.0).coerceIn(-1.0, 1.0)

            // Old consensus is a cross-check, not a duplicate full vote.
            u += ((legacyConsensusMult - 1.0) * 3.0).coerceIn(-1.5, 0.3)

            // Existing hypothesis/lab stack already contains reviewed strategy
            // experiments; expose it as a small policy prior.
            val strategyBias = (
                (hypothesisSizeBias - 1.0) * 5.0 +
                    (reviewedLabBias - 1.0) * 4.0
                ).coerceIn(-3.0, 3.0)
            u += when (policy) {
                SuperPolicyTree7638.Policy.WAIT_REASSESS -> -strategyBias * 0.30
                SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE -> strategyBias * 0.45
                SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD -> strategyBias * 0.70
                SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK -> strategyBias * 0.60
                SuperPolicyTree7638.Policy.CONVICTION_RUNNER -> strategyBias
            }

            // Reuse the already-built counterfactual replay/MCTS exit learner.
            val mcts = mctsPolicy
            if (mcts != null && mctsConfidence > 0.0) {
                val magnitude = (mctsExpectedDeltaPct / 25.0)
                    .coerceIn(-3.0, 3.0) * mctsConfidence
                u += when (mcts) {
                    CounterfactualReplayEngine.AlternativeKind.BANK_25,
                    CounterfactualReplayEngine.AlternativeKind.BANK_50 ->
                        if (policy == SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK) magnitude else 0.0
                    CounterfactualReplayEngine.AlternativeKind.TRAIL_RUNNER,
                    CounterfactualReplayEngine.AlternativeKind.HOLD_TO_PEAK_HALF ->
                        if (policy == SuperPolicyTree7638.Policy.CONVICTION_RUNNER ||
                            policy == SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD) magnitude else 0.0
                    CounterfactualReplayEngine.AlternativeKind.HARD_STOP_15 ->
                        if (policy == SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE ||
                            policy == SuperPolicyTree7638.Policy.WAIT_REASSESS) magnitude else -kotlin.math.abs(magnitude) * 0.35
                }
            }

            return u.coerceIn(-6.0, 6.0)
        }

        fun contributionTag(): String {
            return String.format(
                java.util.Locale.US,
                "existing7650(spec=%s/%s/%s,edge=%+d/%.2f,cons=%.2f,hyp=%.2f,lab=%.2f,mcts=%s:%+.1f@%.2f,src=%s)",
                specialistEligible?.toString() ?: "na",
                specialistScore?.toString() ?: "na",
                specialistConfidence?.toString() ?: "na",
                ultimateScoreBias,
                ultimateSizeMult,
                legacyConsensusMult,
                hypothesisSizeBias,
                reviewedLabBias,
                mctsPolicy?.name ?: "none",
                mctsExpectedDeltaPct,
                mctsConfidence,
                sources.joinToString("+").take(80),
            )
        }
    }

    fun read(
        mint: String,
        symbol: String,
        lane: String,
        source: String,
        score: Int,
        regime: String,
        strategyIdentity: String,
    ): Snapshot {
        val laneKey = lane.trim().uppercase()

        // Read the specialist cache only. Do NOT call evaluate() here.
        val specialist = try {
            SpecialistBrainBridge7542.cachedSnapshot7650(mint)
                ?.opinions
                ?.get(laneKey)
        } catch (_: Throwable) { null }

        val edge = try { UltimateEdgeEngine.cached(mint, laneKey) } catch (_: Throwable) { null }

        val consensus = try {
            BrainConsensusBridge6329.consult(mint, symbol, laneKey, source)
        } catch (_: Throwable) { null }

        val hypothesis = try {
            StrategyHypothesisEngine.peekSizeBias(
                lane = laneKey,
                score = score,
                regime = regime,
                mint = mint,
                strategyIdentity = strategyIdentity,
            )
        } catch (_: Throwable) { 1.0 }

        val lab = try {
            AsyncStrategyLab.reviewedSizeBias(laneKey, score, regime)
        } catch (_: Throwable) { 1.0 }

        val mcts = try {
            CounterfactualReplayEngine.mctsExitPolicyHint(
                lane = laneKey,
                maxCases = 40,
                rollouts = 96,
            )
        } catch (_: Throwable) { null }

        val src = linkedSetOf<String>()
        if (specialist != null) src += "SpecialistBrainBridge7542"
        if (edge != null) src += "UltimateEdgeEngine"
        if (consensus != null) src += "BrainConsensusBridge6329"
        if (hypothesis != 1.0) src += "StrategyHypothesisEngine"
        if (lab != 1.0) src += "AsyncStrategyLab"
        if (mcts != null) src += "CounterfactualReplayEngine"

        return Snapshot(
            lane = laneKey,
            specialistEligible = specialist?.eligible,
            specialistScore = specialist?.score,
            specialistConfidence = specialist?.confidence,
            ultimateScoreBias = edge?.scoreBias ?: 0,
            ultimateSizeMult = edge?.sizeMult ?: 1.0,
            legacyConsensusMult = consensus?.multiplier ?: 1.0,
            hypothesisSizeBias = hypothesis,
            reviewedLabBias = lab,
            mctsPolicy = mcts?.policy,
            mctsExpectedDeltaPct = mcts?.expectedDeltaPct ?: 0.0,
            mctsConfidence = mcts?.confidence ?: 0.0,
            sources = src,
        )
    }
}
