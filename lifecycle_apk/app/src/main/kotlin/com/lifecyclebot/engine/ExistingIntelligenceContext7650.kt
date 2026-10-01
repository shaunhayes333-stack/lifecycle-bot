
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
        val estate7654: SuperIntelligenceEstate7654.Snapshot,
    ) {
        fun evidenceTopology7651(
            policy: SuperPolicyTree7638.Policy,
            state: SuperWorldModel7634.LatentState? = null,
        ): SuperEvidenceTopology7651.Result {
            val observations = ArrayList<SuperEvidenceTopology7651.Observation>(10)
            fun trust(family: SuperEvidenceTopology7651.Family): Double {
                val laneTrust = SuperEvidenceReliability7652.trust(lane, family)
                val stateTrust = if (state == null) 1.0
                    else SuperEvidenceContextTrust7656.trust(lane, state, family)
                return (laneTrust * stateTrust).coerceIn(0.60, 1.20)
            }

            // Native specialist opinion is an independent lane-native family.
            val specialistUtility = when (specialistEligible) {
                true -> {
                    val scoreTerm = (((specialistScore ?: 50) - 50) / 50.0) * 2.5
                    val confTerm = (((specialistConfidence ?: 50) - 50) / 50.0) * 1.5
                    scoreTerm + confTerm
                }
                false -> -1.5
                null -> 0.0
            }
            if (specialistUtility != 0.0) observations += SuperEvidenceTopology7651.Observation(
                "native_specialist", SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST, specialistUtility,
                trust(SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST),
            )

            // These are both aggregate/cross-check surfaces over lower-level brains.
            // Keeping them in one ancestry family prevents MetaCog/SuperBrain/semantic
            // evidence from receiving multiple independent votes merely via adapters.
            val edgeScoreUtility = (ultimateScoreBias * 0.30).coerceIn(-1.5, 1.5)
            val edgeSizeUtility = ((ultimateSizeMult - 1.0) * 6.0).coerceIn(-1.0, 1.0)
            val consensusUtility = ((legacyConsensusMult - 1.0) * 3.0).coerceIn(-1.5, 0.3)
            if (edgeScoreUtility != 0.0) observations += SuperEvidenceTopology7651.Observation(
                "ultimate_edge_score", SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK, edgeScoreUtility,
                trust(SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK),
            )
            if (edgeSizeUtility != 0.0) observations += SuperEvidenceTopology7651.Observation(
                "ultimate_edge_size", SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK, edgeSizeUtility,
                trust(SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK),
            )
            if (consensusUtility != 0.0) observations += SuperEvidenceTopology7651.Observation(
                "legacy_consensus", SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK, consensusUtility,
                trust(SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK),
            )

            // HypothesisEngine and AsyncStrategyLab are descendants of the same
            // learned-strategy evidence family, so fuse them before cross-family sum.
            val hypothesisBias = ((hypothesisSizeBias - 1.0) * 5.0).coerceIn(-2.0, 2.0)
            val labBias = ((reviewedLabBias - 1.0) * 4.0).coerceIn(-2.0, 2.0)
            fun strategyPolicyUtility(bias: Double): Double = when (policy) {
                SuperPolicyTree7638.Policy.WAIT_REASSESS -> -bias * 0.30
                SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE -> bias * 0.45
                SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD -> bias * 0.70
                SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK -> bias * 0.60
                SuperPolicyTree7638.Policy.CONVICTION_RUNNER -> bias
            }
            if (hypothesisBias != 0.0) observations += SuperEvidenceTopology7651.Observation(
                "strategy_hypothesis", SuperEvidenceTopology7651.Family.STRATEGY_LEARNING,
                strategyPolicyUtility(hypothesisBias),
                trust(SuperEvidenceTopology7651.Family.STRATEGY_LEARNING),
            )
            if (labBias != 0.0) observations += SuperEvidenceTopology7651.Observation(
                "reviewed_lab", SuperEvidenceTopology7651.Family.STRATEGY_LEARNING,
                strategyPolicyUtility(labBias),
                trust(SuperEvidenceTopology7651.Family.STRATEGY_LEARNING),
            )

            // Wider intelligence estate: read-only/cached families that were not
            // explicitly represented in the 7650 integration.
            val crossUtility7654 = estate7654.crossTalkUtility(policy)
            if (kotlin.math.abs(crossUtility7654) >= 0.02) observations += SuperEvidenceTopology7651.Observation(
                "ai_crosstalk", SuperEvidenceTopology7651.Family.AI_CROSSTALK,
                crossUtility7654, 0.80,
            )
            val llmUtility7654 = estate7654.llmUtility(policy)
            if (kotlin.math.abs(llmUtility7654) >= 0.02) observations += SuperEvidenceTopology7651.Observation(
                "llm_council_cache", SuperEvidenceTopology7651.Family.LLM_COUNCIL,
                llmUtility7654, 0.65,
            )
            val scannerUtility7654 = estate7654.scannerUtility(policy)
            if (kotlin.math.abs(scannerUtility7654) >= 0.02) observations += SuperEvidenceTopology7651.Observation(
                "scanner_ensemble_cache", SuperEvidenceTopology7651.Family.SCANNER_ENSEMBLE,
                scannerUtility7654, 0.75,
            )
            val sourceLearning7658 = estate7654.sourceLearningUtility(policy)
            if (kotlin.math.abs(sourceLearning7658) >= 0.02) observations += SuperEvidenceTopology7651.Observation(
                "scanner_source_learning", SuperEvidenceTopology7651.Family.SCANNER_SOURCE_LEARNING,
                sourceLearning7658, 0.60,
            )

            // Counterfactual replay is a separate empirical ancestry family.
            val mcts = mctsPolicy
            if (mcts != null && mctsConfidence > 0.0) {
                val magnitude = (mctsExpectedDeltaPct / 25.0)
                    .coerceIn(-3.0, 3.0) * mctsConfidence
                val mctsUtility = when (mcts) {
                    CounterfactualReplayEngine.AlternativeKind.BANK_25,
                    CounterfactualReplayEngine.AlternativeKind.BANK_50 ->
                        if (policy == SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK) magnitude else 0.0
                    CounterfactualReplayEngine.AlternativeKind.TRAIL_RUNNER,
                    CounterfactualReplayEngine.AlternativeKind.HOLD_TO_PEAK_HALF ->
                        if (policy == SuperPolicyTree7638.Policy.CONVICTION_RUNNER ||
                            policy == SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD) magnitude else 0.0
                    CounterfactualReplayEngine.AlternativeKind.HARD_STOP_15 ->
                        if (policy == SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE ||
                            policy == SuperPolicyTree7638.Policy.WAIT_REASSESS) magnitude
                        else -kotlin.math.abs(magnitude) * 0.35
                }
                if (mctsUtility != 0.0) observations += SuperEvidenceTopology7651.Observation(
                    "counterfactual_mcts", SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY,
                    mctsUtility,
                    (mctsConfidence.coerceIn(0.0, 1.0) *
                        trust(SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY)).coerceIn(0.0, 1.15),
                )
            }

            return SuperEvidenceTopology7651.fuse(observations)
        }

        fun policyPrior(
            policy: SuperPolicyTree7638.Policy,
            state: SuperWorldModel7634.LatentState? = null,
        ): Double {
            val topology = evidenceTopology7651(policy, state)
            val interaction = SuperEvidenceInteraction7653.adjustment(lane, topology.familyUtility)
            return (topology.decorrelatedUtility + interaction).coerceIn(-6.0, 6.0)
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
            ) + " " + estate7654.tag()
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

        val estate7654 = try {
            SuperIntelligenceEstate7654.read(mint, symbol, laneKey, source)
        } catch (_: Throwable) {
            SuperIntelligenceEstate7654.read("", "", laneKey, source)
        }

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
            estate7654 = estate7654,
        )
    }
}
