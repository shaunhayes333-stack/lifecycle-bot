package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class AateBrainContribution6512(
    val brain: String, val role: String, val weight: Double, val effect: Double,
    val pWin: Double? = null, val expectedPnlPct: Double? = null,
    val moonshotP: Double? = null, val rugP: Double? = null,
    val scoreDelta: Double = 0.0, val sizeMultiplier: Double = 1.0, val tactic: String = "",
)

data class AateStrategyContext6512(
    val candidateId: String, val runtimeGeneration: Long, val mode: String,
    val mint: String, val symbol: String, val candidateVersion: Long,
    val primaryStrategy: String, val source: String, val regime: String,
)

data class AateDecisionEnvelope6512(
    val envelopeId: String, val revision: Long, val context: AateStrategyContext6512,
    val action: String, val pWin: Double, val expectedPnlPct: Double,
    val moonshotP: Double, val rugP: Double, val scoreBase: Double, val scoreFinal: Double,
    val sizeBase: Double, val sizeFinal: Double, val tactic: String,
    val hardSafety: List<String>, val contributors: List<AateBrainContribution6512>,
    val learningState: String, val executionTicket: String = "", val sealed: Boolean = false,
    val positionId: String = "", val createdAtMs: Long = System.currentTimeMillis(),
)

object PolicySynthesizer6512 {
    private val revisions = AtomicLong(0L)
    fun synthesize(
        context: AateStrategyContext6512, proposedAction: String,
        scoreBase: Double, scoreFinal: Double, sizeBase: Double, sizeFinal: Double,
        tactic: String, hardSafety: List<String>, contributors: List<AateBrainContribution6512>,
        learningState: String,
    ): AateDecisionEnvelope6512 {
        val bounded = contributors.map { it.copy(
            weight = it.weight.coerceIn(0.0, 1.0), effect = it.effect.coerceIn(-1.0, 1.0),
            pWin = it.pWin?.coerceIn(0.0, 1.0), moonshotP = it.moonshotP?.coerceIn(0.0, 1.0),
            rugP = it.rugP?.coerceIn(0.0, 1.0), sizeMultiplier = it.sizeMultiplier.coerceIn(0.05, 3.0),
        ) }
        val wp = bounded.mapNotNull { c -> c.pWin?.let { it to c.weight } }
        val pWin = if (wp.isEmpty()) 0.5 else wp.sumOf { it.first * it.second } / wp.sumOf { it.second }.coerceAtLeast(0.0001)
        val we = bounded.mapNotNull { c -> c.expectedPnlPct?.let { it to c.weight } }
        val ev = if (we.isEmpty()) 0.0 else we.sumOf { it.first * it.second } / we.sumOf { it.second }.coerceAtLeast(0.0001)
        val moon = bounded.mapNotNull { c -> c.moonshotP?.let { it to c.weight } }.maxOfOrNull { it.first } ?: 0.0
        val rug = bounded.mapNotNull { c -> c.rugP?.let { it to c.weight } }.maxOfOrNull { it.first } ?: 0.0
        // V5.0.6801 §POLICY_NEGATIVE_EV_HARD_VETO — operator diagnosis Feb 2026:
        //   "Policy action=BUY while EV=-5.0 is happening. Negative EV going to
        //    BUY is a source-level authority failure. The AATE policy stamp
        //    must not authorise BUY when its own contributors report a
        //    materially negative expected value with real evidence."
        //   Preserve BLOCK from hardSafety (existing invariant). If the
        //   proposed action is BUY-like AND the weighted EV surface is
        //   materially negative AND at least one contributor supplied an
        //   EV signal (we.isNotEmpty prevents blocking on the default 0.0
        //   when no contributor scored EV), the synthesiser downgrades the
        //   action to POLICY_NEG_EV_BLOCK_6801. -3% weighted EV is the
        //   authority floor: casual noise stays neutral, but an authority-
        //   confident -3% or worse cannot become an executable BUY.
        val proposedUpper6801 = proposedAction.uppercase()
        val isBuyLike6801 = proposedUpper6801 in setOf("BUY", "PROBE", "PROBE_ONLY", "EXECUTE")
        // V5.0.6812 §NEG_EV_MIN_SAMPLE — operator diagnosis Feb 2026:
        //   "POLICY_NEG_EV_BLOCK_6801 can hard-veto a candidate with high pWin,
        //    rugP=0, many contributors, but only ONE EV contributor producing
        //    weightedEv=-5. That is insufficient evidence for a hard veto."
        //   Require at least MIN_EV_HARD_VETO_SAMPLE_6812 independent
        //   attributable EV contributors before allowing the hard veto.
        //   Otherwise downgrade to POLICY_NEG_EV_ADVISORY_6812 — the decision
        //   remains BUY-like but the negative EV signal is recorded for size
        //   damping and future authority tightening. Hard safety (rug,
        //   liquidity, scam, route) is unaffected and continues to BLOCK
        //   regardless of EV sample count. This is a source-level tightening
        //   of AATE policy synthesis only; no execution/authority path is
        //   changed (the crash-inducing 6811 FDG-consolidation was reverted).
        val MIN_EV_HARD_VETO_SAMPLE_6812 = 3
        val evSampleQualifies6812 = we.size >= MIN_EV_HARD_VETO_SAMPLE_6812
        val evVetoFires6801 = isBuyLike6801 && we.isNotEmpty() && ev <= -3.0 && evSampleQualifies6812
        val evAdvisoryFires6812 = isBuyLike6801 && we.isNotEmpty() && ev <= -3.0 && !evSampleQualifies6812
        val action = when {
            hardSafety.isNotEmpty() -> "BLOCK"
            evVetoFires6801 -> "POLICY_NEG_EV_BLOCK_6801"
            else -> proposedUpper6801
        }
        if (evVetoFires6801) {
            try {
                PipelineHealthCollector.labelInc("AATE_POLICY_NEGATIVE_EV_HARD_VETO_6801")
                ForensicLogger.lifecycle(
                    "AATE_POLICY_NEGATIVE_EV_HARD_VETO_6801",
                    "candidateId=${context.candidateId} mint=${context.mint.take(10)} " +
                        "lane=${context.primaryStrategy} proposedAction=$proposedUpper6801 " +
                        "weightedEv=${"%.2f".format(ev)} pWin=${"%.2f".format(pWin)} rugP=${"%.2f".format(rug)} " +
                        "contributors=${bounded.size} evContributors=${we.size} " +
                        "action=downgrade_to_POLICY_NEG_EV_BLOCK_6801_never_becomes_buy",
                )
            } catch (_: Throwable) {}
        }
        if (evAdvisoryFires6812) {
            try {
                PipelineHealthCollector.labelInc("AATE_POLICY_NEG_EV_ADVISORY_6812")
                ForensicLogger.lifecycle(
                    "AATE_POLICY_NEG_EV_ADVISORY_6812",
                    "candidateId=${context.candidateId} mint=${context.mint.take(10)} " +
                        "lane=${context.primaryStrategy} proposedAction=$proposedUpper6801 " +
                        "weightedEv=${"%.2f".format(ev)} pWin=${"%.2f".format(pWin)} " +
                        "evContributors=${we.size}/${MIN_EV_HARD_VETO_SAMPLE_6812} " +
                        "action=advisory_only_low_sample_no_hard_veto",
                )
            } catch (_: Throwable) {}
        }
        val rev = revisions.incrementAndGet()
        return AateDecisionEnvelope6512(
            envelopeId = "${context.runtimeGeneration}:${context.mode}:${context.mint}:${context.candidateVersion}:$rev",
            revision = rev, context = context, action = action, pWin = pWin,
            expectedPnlPct = ev, moonshotP = moon, rugP = rug,
            scoreBase = scoreBase, scoreFinal = scoreFinal, sizeBase = sizeBase,
            sizeFinal = sizeFinal.coerceAtLeast(0.0), tactic = tactic,
            hardSafety = hardSafety.distinct(), contributors = bounded, learningState = learningState,
        )
    }
}

object AateDecisionFabric6512 {
    private val byAuthority = ConcurrentHashMap<String, AateDecisionEnvelope6512>()
    private val byAttempt = ConcurrentHashMap<String, AateDecisionEnvelope6512>()
    private val byPosition = ConcurrentHashMap<String, AateDecisionEnvelope6512>()
    private val rewardedPositions = ConcurrentHashMap.newKeySet<String>()
    private val policies = AtomicLong(0L); private val rewards = AtomicLong(0L)

    private fun key(mode: String, mint: String, version: Long, lane: String): String =
        "${BotRuntimeController.currentGeneration()}:${mode.uppercase()}:${mint.trim()}:$version:${lane.uppercase()}"

    fun record(e: AateDecisionEnvelope6512): AateDecisionEnvelope6512 {
        val c = e.context
        byAuthority[key(c.mode, c.mint, c.candidateVersion, c.primaryStrategy)] = e
        policies.incrementAndGet(); emitPolicy(e); return e
    }

    fun get(mode: String, mint: String, version: Long, lane: String): AateDecisionEnvelope6512? =
        byAuthority[key(mode, mint, version, lane)]

    fun sealForExecution(attemptId: String, mode: String, mint: String, version: Long, lane: String): AateDecisionEnvelope6512? {
        if (attemptId.isBlank()) return null
        val prior = get(mode, mint, version, lane) ?: return null
        val sealed = prior.copy(executionTicket = attemptId, sealed = true)
        byAuthority[key(mode, mint, version, lane)] = sealed; byAttempt[attemptId] = sealed
        emitPolicy(sealed); return sealed
    }

    fun attachPosition(positionId: String, mode: String, mint: String, lane: String): Boolean {
        if (positionId.isBlank() || mint.isBlank() || lane.isBlank()) return false

        // V5.0.6681 §CAUSAL_POLICY_POSITION_BINDING — the canonical open itself
        // is sufficient authority to freeze the owner-lane entry sample. Do this
        // BEFORE the AATE envelope lookup: envelope attribution can be missing
        // (specialistLearningMissing), but that must not poison/skip the primary
        // entry learner for an otherwise valid canonical position.
        var policyBound6681 = try { UnifiedPolicyHead.bindPosition6681(positionId, mint, lane) } catch (_: Throwable) { false }
        try {
            PipelineHealthCollector.labelInc(if (policyBound6681) "AATE_POLICY_POSITION_BOUND_6681" else "AATE_POLICY_POSITION_BIND_MISSING_6681")
        } catch (_: Throwable) {}

        val e = byAuthority.values.asSequence()
            .filter { it.context.runtimeGeneration == BotRuntimeController.currentGeneration() }
            .filter { it.context.mode.equals(mode, true) && it.context.mint == mint && it.context.primaryStrategy.equals(lane, true) }
            .maxByOrNull { it.revision }
        if (e == null) {
            try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_MISSING_6681") } catch (_: Throwable) {}
            return false
        }
        if (!policyBound6681) {
            val weight6713 = e.contributors.sumOf { it.weight }.coerceAtLeast(0.0001)
            val effect6713 = e.contributors.sumOf {
                ((it.effect + 1.0) * 0.5).coerceIn(0.0, 1.0) * it.weight
            } / weight6713
            policyBound6681 = try {
                UnifiedPolicyHead.bindDecisionFallback6713(
                    positionId = positionId,
                    mint = mint,
                    ownerLane = lane,
                    scoreFinal = e.scoreFinal,
                    pWin = e.pWin,
                    expectedPnlPct = e.expectedPnlPct,
                    rugP = e.rugP,
                    contributorEffect01 = effect6713,
                )
            } catch (_: Throwable) { false }
        }
        byPosition[positionId] = e.copy(positionId = positionId)
        try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_LINKED_6512") } catch (_: Throwable) {}
        return true
    }

    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (rewardedPositions.contains(env.positionId)) return true
        val e = byPosition[env.positionId] ?: byAuthority.values.asSequence()
            .filter { it.context.mint == env.mint && it.context.primaryStrategy.equals(env.lane, true) }.maxByOrNull { it.revision }
        try { ToolkitSignalSheet.recordDeskStage(env.lane, "FINALIZED", env.positionId) } catch (_: Throwable) {}
        if (e == null && env.lane.uppercase() in setOf("QUALITY","BLUECHIP","BLUE_CHIP","SHITCOIN","CYCLIC","EXPRESS","CORE","MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN")) {
            try { ToolkitSignalSheet.recordCausalIssue6600("specialistLearningMissing", env.lane, "positionId=${env.positionId.take(18)}") } catch (_: Throwable) {}
            // V5.0.6747 §CAUSAL_OWNER_ATTRIBUTION_REPAIR — operator
            // directive: "if a loser cannot reliably be attributed
            // back to the exact lane + tactic + score band + regime
            // + entry policy then the learner can identify 'something
            // lost' without reliably learning what decision caused it".
            // Repair: even when the AATE envelope is missing, consult
            // LaneAttributionLedger6427 for the definitive owner-lane
            // stamp (recorded at BUY-pending registration) and drive a
            // fallback policy bind so the specialist head still
            // trains against the real owner. Never fabricates score/
            // pWin/rug; those stay null so the fallback bind path
            // uses safe defaults.
            try {
                val ledgerLane6747 = LaneAttributionLedger6427.getEntryLane(env.positionId)
                if (!ledgerLane6747.isNullOrBlank()) {
                    // V5.0.6752 §CAUSAL_OWNER_LEDGER_TRUTH — operator
                    // 6750 diagnostic: UNRESOLVED_OWNER_6741=85 and
                    // Strategy Expectancy / Math Edge disagree on the
                    // same cohort (+689% mean vs -28.8% avg). Even
                    // when env.lane disagrees with the ledger stamp,
                    // LaneAttributionLedger6427 is the authoritative
                    // owner recorded at BUY-pending registration — a
                    // bind against the ledger owner is more correct
                    // than a bind against the potentially-inferred
                    // env.lane. Prefer the ledger owner UNCONDITIONALLY
                    // when it exists; env.lane match is no longer a
                    // gate, only a diagnostic.
                    val laneAgree6752 = ledgerLane6747.equals(env.lane, true)
                    val bound6747 = try {
                        UnifiedPolicyHead.bindDecisionFallback6713(
                            positionId = env.positionId, mint = env.mint,
                            ownerLane = ledgerLane6747,
                            scoreFinal = 0.0, pWin = 0.0,
                            expectedPnlPct = 0.0, rugP = 0.0,
                            contributorEffect01 = 0.5,
                        )
                    } catch (_: Throwable) { false }
                    if (bound6747) {
                        PipelineHealthCollector.labelInc("CAUSAL_OWNER_ATTRIBUTION_LEDGER_FALLBACK_6747")
                        PipelineHealthCollector.labelInc("CAUSAL_OWNER_ATTRIBUTION_LEDGER_FALLBACK_6747|${ledgerLane6747.uppercase()}")
                        if (!laneAgree6752) {
                            PipelineHealthCollector.labelInc("CAUSAL_OWNER_LEDGER_TRUTH_OVERRIDE_ENV_LANE_6752")
                        }
                        ForensicLogger.lifecycle(
                            if (laneAgree6752) "CAUSAL_OWNER_ATTRIBUTION_LEDGER_FALLBACK_6747"
                                else "CAUSAL_OWNER_LEDGER_TRUTH_OVERRIDE_ENV_LANE_6752",
                            "positionId=${env.positionId.take(18)} envLane=${env.lane} ledgerLane=$ledgerLane6747 " +
                                "laneAgree=$laneAgree6752 action=bind_specialist_to_ledger_owner",
                        )
                    }
                }
            } catch (_: Throwable) {}
        }
        val contributors = e?.contributors.orEmpty(); val updated = mutableListOf<String>()
        val uphBefore = UnifiedPolicyHead.trainedCount()
        // V5.0.6792 §LEARNING_PURITY — do NOT train lane heads from an
        // unresolved-owner close. Directive: "Attribute every reward to
        // immutable entry provenance." Positions opened prior to 6789
        // provenance stamping have no verifiable owner; training against
        // an inferred owner poisons the specialist head. Downstream
        // mint-scoped learners (AutonomousMetaPolicy / StrategyHypothesis /
        // etc.) do not carry owner attribution and still run below.
        val hasProvenance6792 = try {
            LaneAttributionLedger6427.hasFullProvenance6789(env.positionId)
        } catch (_: Throwable) { false }
        if (!hasProvenance6792) {
            try {
                PipelineHealthCollector.labelInc("LEARNING_PURITY_SKIP_UNRESOLVED_OWNER_6792")
                PipelineHealthCollector.labelInc("LEARNING_PURITY_SKIP_UNRESOLVED_OWNER_6792_${env.lane.uppercase()}")
                ForensicLogger.lifecycle(
                    "LEARNING_PURITY_SKIP_UNRESOLVED_OWNER_6792",
                    "positionId=${env.positionId.take(18)} envLane=${env.lane} mint=${env.mint.take(10)} realizedPct=${env.realizedReturnPct} action=skip_lane_head_training_downstream_still_runs",
                )
            } catch (_: Throwable) {}
        }
        // V5.0.6713 — exact owner-bound policy mutation is required before this
        // consumer ACKs the canonical event. Failed/missing binds retry instead
        // of permanently recording a false successful reward delivery.
        //
        // V5.0.6717 §CAUSAL_LOOP_UNSEVERANCE — the V5.0.6713 hard-return-false
        // was cascading with UnifiedPolicyHead.recordOutcome6681's binding-
        // miss=false to keep every meme-owner position stuck in Causal
        // FeedbackAuthority6715.pendingLearning FOREVER (markLearned was gated
        // on policyAck6713). That blocked every future admit for the lane/band
        // with TERMINAL_FEEDBACK_NOT_LEARNED_6715 — trade-one freeze, 8% WR.
        //
        // Repaired semantics: attempt the causal training, but the ACK to the
        // canonical bus is independent of whether we had a per-position training
        // sample for THIS trade. Downstream learners (LanePolicy /
        // RetrainingDecay / ExplorationBudget / AutonomousMetaPolicy /
        // StrategyHypothesisEngine) all learn from mint/lane paths that don't
        // require the UnifiedPolicyHead per-position observation, so they MUST
        // still run. rewardedPositions still enforces one-time delivery.
        val policyAck6713 = if (hasProvenance6792) {
            try {
                UnifiedPolicyHead.recordOutcome6681(env.positionId, env.mint, env.lane, env.realizedReturnPct)
            } catch (_: Throwable) { false }
        } else {
            false
        }
        val memeOwner6713 = env.lane.uppercase() in setOf(
            "QUALITY","BLUECHIP","BLUE_CHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN",
        )
        if (memeOwner6713 && !policyAck6713) {
            try {
                PipelineHealthCollector.labelInc("AATE_POLICY_REWARD_SOFT_MISS_6717")
                ForensicLogger.lifecycle(
                    "AATE_POLICY_REWARD_SOFT_MISS_6717",
                    "positionId=${env.positionId.take(18)} lane=${env.lane} mint=${env.mint.take(10)} action=ack_and_continue_downstream_learners_still_run",
                )
            } catch (_: Throwable) {}
            // NOTE: previously returned false here (V5.0.6713). Now we continue
            // so markLearned + downstream learners fire. See §CAUSAL_LOOP_UNSEVERANCE.
        }
        if (!rewardedPositions.add(env.positionId)) return true
        // V5.0.6717 §CAUSAL_LOOP_UNSEVERANCE — ACK the causal feedback
        // authority regardless of whether per-position policy training
        // bound, so the admission gate does not stay stuck in
        // pendingLearning.
        // V5.0.6798 §LEARNING_ACK_PURITY — but ONLY when we actually have
        // immutable owner provenance. Prior code emitted CAUSAL_OWNER_
        // LEARN_ACK_EARLY_6715 for every unresolved-owner close, giving
        // the causal state a false "learned" signal for 50 out of 50
        // trades in the operator's runtime dump. Without provenance we
        // did not train any lane head and downstream learners cannot
        // attribute the reward correctly either — a spurious ACK would
        // mask real learning gaps. Skip the causal ACK; let the position
        // stay pending until either provenance arrives or a maintenance
        // sweep expires the pending entry.
        if (hasProvenance6792) {
            try { CausalFeedbackAuthority6715.markLearned(env.positionId) } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("CAUSAL_ACK_SKIPPED_UNRESOLVED_OWNER_6798") } catch (_: Throwable) {}
        }
        if (policyAck6713 && UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
        val metaBefore = AutonomousMetaPolicy.totalUpdateCount6512()
        try { AutonomousMetaPolicy.recordOutcome(env.mint, env.realizedReturnPct) } catch (_: Throwable) {}
        if (AutonomousMetaPolicy.totalUpdateCount6512() > metaBefore) updated += "AutonomousMetaPolicy"
        val hypoBefore = StrategyHypothesisEngine.outcomeUpdateCount6512()
        try { StrategyHypothesisEngine.recordOutcome(env.mint, env.realizedReturnPct) } catch (_: Throwable) {}
        if (StrategyHypothesisEngine.outcomeUpdateCount6512() > hypoBefore) updated += "StrategyHypothesisEngine"

        // V5.0.6707 — SOURCE REPAIR, NOT A NEW POLICY LAYER.
        // Executor owns the canonical terminal close now, so the old assumption
        // below that V3JournalRecorder always trained the primary lane is false.
        // Reconnect that exact pre-existing learner trio at canonical finality.
        // rewardedPositions above is the one-position idempotency boundary.
        val ownerLane6707 = env.lane.uppercase()
        val memeOwner6707 = ownerLane6707 in setOf(
            "QUALITY","BLUECHIP","BLUE_CHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN",
        )

        // V5.0.6610 §LEARNING_FANOUT_TO_OWNER — canonical finality liveness.
        // This is an observation/stage signal, not permission to mutate a learner.
        // Keep it outside PaperLearningEligibility so every finalized configured
        // MemeTrader owner is visible in learningN; the actual LanePolicy/
        // RetrainingDecay/ExplorationBudget mutation remains gated below.
        val ownerLane6610 = ownerLane6707
        if (memeOwner6707) {
            try {
                ToolkitSignalSheet.recordDeskStage(ownerLane6610, "LEARNING", env.positionId)
                PipelineHealthCollector.labelInc("SPECIALIST_LEARNING_OWNER_FANOUT_6610_$ownerLane6610")
            } catch (_: Throwable) {}
        }

        val invalidOwnerStrategy6707 = listOf(
            "STALE", "RESTORED", "REPLAY", "DECIMAL", "ORPHAN", "PHANTOM",
            "UNRESOLVED_BASIS", "ADMINISTRATIVE", "SYNTHETIC_CLOSE",
        ).any { env.exitReason.uppercase().contains(it) }
        val ownerEligible6707 = memeOwner6707 && !invalidOwnerStrategy6707 && try {
            PaperLearningEligibility6519.decision(null, env.mint).eligible
        } catch (_: Throwable) { false }
        if (ownerEligible6707) {
            val ownerBand6707 = env.scoreBand.ifBlank {
                try { LosingPatternMemory.scoreBand(env.entryScore.toInt()) } catch (_: Throwable) { "UNKNOWN" }
            }
            val ownerOutcome6707 = CanonicalOutcomeClassifier6576.classifyReadonly(env.realizedReturnPct)
            val ownerWin6707 = ownerOutcome6707 == CanonicalOutcomeClassifier6576.Class.WIN
            val ownerLoss6707 = ownerOutcome6707 == CanonicalOutcomeClassifier6576.Class.LOSS
            try {
                com.lifecyclebot.engine.learning.LanePolicy.recordOutcome(ownerLane6707, ownerBand6707, ownerWin6707, ownerLoss6707)
                com.lifecyclebot.engine.learning.RetrainingDecay.noteOutcome(ownerLane6707, ownerBand6707, ownerWin6707, ownerLoss6707, env.realizedReturnPct)
                com.lifecyclebot.engine.learning.ExplorationBudget.onLaneOutcome(ownerLane6707, env.realizedReturnPct)
                PipelineHealthCollector.labelInc("SPECIALIST_LEARNING_OWNER_CANONICAL_6707_$ownerLane6707")
                if (ownerWin6707 || ownerLoss6707) updated += "LanePolicy"
            } catch (_: Throwable) {}
        } else if (memeOwner6707) {
            try { PipelineHealthCollector.labelInc("SPECIALIST_LEARNING_OWNER_QUARANTINED_6707_$ownerLane6707") } catch (_: Throwable) {}
        }

        contributors.filter { it.role == "MEME_SPECIALIST_DESK" && it.brain.startsWith("MemeDesk:") }.forEach { c ->
            val lane = c.brain.substringAfter("MemeDesk:").substringBefore(':').uppercase()
            val scoreBand = try { LosingPatternMemory.scoreBand(e?.scoreFinal?.toInt() ?: 0) } catch (_: Throwable) { "UNKNOWN" }
            val outcome = CanonicalOutcomeClassifier6576.classifyReadonly(env.realizedReturnPct)
            try {
                // V5.0.6707 — the actual execution owner was trained above from
                // canonical finality. Secondary desks still receive causal credit
                // without double-training the owner.
                if (!lane.equals(env.lane, true)) {
                    com.lifecyclebot.engine.learning.LanePolicy.recordOutcome(
                        lane, scoreBand,
                        outcome == CanonicalOutcomeClassifier6576.Class.WIN,
                        outcome == CanonicalOutcomeClassifier6576.Class.LOSS,
                    )
                }
                ToolkitSignalSheet.recordDeskStage(lane, "LEARNING", env.positionId)
            } catch (_: Throwable) {}
        }
        val graphBefore = SemanticPatternGraph.nodeCount6512()
        val graphId = try { SemanticPatternGraph.recordOutcome(
            lane = env.lane, source = e?.context?.source ?: "CANONICAL_FINALITY",
            setup = contributors.joinToString("|") { "${it.brain}:${"%.3f".format(it.weight)}:${"%.3f".format(it.effect)}" }.ifBlank { "lane=${env.lane}|tactic=${env.entryTactic}" },
            exitReason = env.proofState, pnlPct = env.realizedReturnPct,
        ) } catch (_: Throwable) { "" }
        if (graphId.isNotBlank() && SemanticPatternGraph.nodeCount6512() > graphBefore) updated += "SemanticPatternGraph"
        rewards.incrementAndGet()
        // V5.0.6617 §POSITION_LIFECYCLE_FORMALIZATION — learner consumed finality.
        try { PositionLifecycleFormalization6617.markLearned(env.positionId) } catch (_: Throwable) {}
        val credit = contributors.joinToString(",") { c ->
            val v = if (env.realizedPnlSol >= 0.0) c.weight * c.effect else -c.weight * c.effect
            "${c.brain}:${"%.4f".format(v)}"
        }
        try {
            ForensicLogger.lifecycle("AATE_REWARD", "tradeId=${env.tradeId} pnl=${env.realizedPnlSol} MFE=${env.mfePct} MAE=${env.maePct} hold=${env.holdingTimeMs} contributors=${contributors.joinToString(",") { it.brain }} creditAssigned=[$credit] learnersUpdated=[${updated.joinToString(",")}] hypothesesUpdated=${updated.contains("StrategyHypothesisEngine")} metaPolicyUpdated=${updated.contains("AutonomousMetaPolicy")} tacticUpdated=true patternGraphUpdated=${graphId.isNotBlank()}")
            PipelineHealthCollector.labelInc("AATE_REWARD_6512")
        } catch (_: Throwable) {}
        return true
    }

    private fun emitPolicy(e: AateDecisionEnvelope6512) {
        val cs = e.contributors.joinToString(",") { "${it.brain}:${"%.3f".format(it.weight)}:${"%.3f".format(it.effect)}" }
        try {
            ForensicLogger.lifecycle("AATE_POLICY", "candidateId=${e.context.candidateId} revision=${e.revision} action=${e.action} pWin=${e.pWin} EV=${e.expectedPnlPct} moonshotP=${e.moonshotP} rugP=${e.rugP} primaryStrategy=${e.context.primaryStrategy} contributors=[$cs] scoreBase=${e.scoreBase} scoreFinal=${e.scoreFinal} sizeBase=${e.sizeBase} sizeFinal=${e.sizeFinal} tactic=${e.tactic} hardSafety=[${e.hardSafety.joinToString(",")}] learningState=${e.learningState} executionTicket=${e.executionTicket}")
            PipelineHealthCollector.labelInc("AATE_POLICY_6512")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = "policies=${policies.get()} sealed=${byAttempt.size} attributed=${byPosition.size} rewards=${rewards.get()}"
    internal fun resetForTest() { byAuthority.clear(); byAttempt.clear(); byPosition.clear(); rewardedPositions.clear(); policies.set(0L); rewards.set(0L) }
}
