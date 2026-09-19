package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6465 §P0-#2 — CONSUMER BRIDGE for CanonicalFinalizedTradeBus6464.
 *
 * V5.0.6697 — learning exclusion is no longer reported as a successful
 * consumer mutation. Learning-ineligible/quarantined envelopes are explicitly
 * marked EXCLUDED on the canonical bus and return false; the bus treats that as
 * terminal exclusion rather than retry/failure. Dashboard remains non-learning
 * and still receives the exact canonical terminal event.
 *
 * V5.0.6699 — exact terminal proof is restart-safe. Persisted finality rows may
 * recover from the durable typed economic sidecar when the volatile 6635 map
 * was lost to process death. Missing proof is logged once per consumer/event;
 * old permanently-unprovable rows are terminally excluded instead of retried
 * forever.
 */
object FinalizedBusConsumerBridge6465 {

    private val delivered = AtomicLong(0L)
    private val refused = AtomicLong(0L)
    private val excluded = AtomicLong(0L)
    private val exactEventPendingLogged6699 = ConcurrentHashMap.newKeySet<String>()
    private const val EXACT_EVENT_GRACE_MS_6699 = 120_000L

    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // V5.0.6713 — canonical terminal publication owns FINALIZE. This is
        // independent of learner eligibility: a position can be economically
        // finalized while deliberately excluded from training.
        try {
            SpecialistCausalFunnel6625.latestUnfinalizedOpenKey6713(env.mint, env.lane)?.let { key ->
                SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FINALIZE, "CANONICAL_TERMINAL_6464")
                PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_FINALIZE_CANONICAL_6713_${env.lane.uppercase().take(24)}")
            }
        } catch (_: Throwable) {}

        // Learning purity metadata applies only to actual learning consumers.
        // Dashboard must still observe the same canonical terminal cohort.
        if (!env.learningEligible && consumer !in NON_LEARNING_CONSUMERS) {
            try {
                CanonicalFinalizedTradeBus6464.exclude(
                    consumer, env.tradeId,
                    "LEARNING_INELIGIBLE:${env.learningEligibilityReason}",
                )
                excluded.incrementAndGet()
                PipelineHealthCollector.labelInc("FINALIZED_LEARNING_INELIGIBLE_EXCLUDED_6697")
                ForensicLogger.lifecycle(
                    "FINALIZED_LEARNING_INELIGIBLE_EXCLUDED_6697",
                    "consumer=$consumer positionId=${env.positionId} mint=${env.mint.take(10)} reason=${env.learningEligibilityReason.take(120)}",
                )
            } catch (_: Throwable) {}
            return false
        }

        // Paper analytics and learning share the same exact committed event
        // cohort. Dashboard is intentionally included: it must not race ahead
        // and display a WR that the learners cannot consume.
        if (env.mode.equals("paper", true) || env.economicEventId.isNotBlank()) {
            val proof6699 = try {
                CanonicalTerminalProof6699.resolve(env.positionId, env.economicEventId)
            } catch (_: Throwable) { null }
            val pendingKey6699 = "$consumer|${env.tradeId}|${env.economicEventId}"
            if (proof6699 == null) {
                val ageMs6699 = (System.currentTimeMillis() - env.atMs).coerceAtLeast(0L)
                if (ageMs6699 > EXACT_EVENT_GRACE_MS_6699) {
                    try {
                        CanonicalFinalizedTradeBus6464.exclude(
                            consumer,
                            env.tradeId,
                            "UNPROVABLE_EXACT_TERMINAL_ECONOMICS_6699",
                        )
                        excluded.incrementAndGet()
                        if (exactEventPendingLogged6699.add(pendingKey6699)) {
                            PipelineHealthCollector.labelInc("FINALIZED_CONSUMER_UNPROVABLE_EXCLUDED_6699")
                            ForensicLogger.lifecycle(
                                "FINALIZED_CONSUMER_UNPROVABLE_EXCLUDED_6699",
                                "consumer=$consumer positionId=${env.positionId} economicEventId=${env.economicEventId.take(40)} ageMs=$ageMs6699 action=terminal_exclusion_no_retry_storm",
                            )
                        }
                    } catch (_: Throwable) {}
                    return false
                }
                if (exactEventPendingLogged6699.add(pendingKey6699)) {
                    try {
                        PipelineHealthCollector.labelInc("FINALIZED_CONSUMER_EXACT_EVENT_PENDING_6651")
                        ForensicLogger.lifecycle(
                            "FINALIZED_CONSUMER_EXACT_EVENT_PENDING_6651",
                            "consumer=$consumer positionId=${env.positionId} economicEventId=${env.economicEventId.take(40)} ageMs=$ageMs6699 action=no_mutation_retry_bounded",
                        )
                    } catch (_: Throwable) {}
                }
                return false
            }
            exactEventPendingLogged6699.remove(pendingKey6699)
        }

        if (consumer !in NON_LEARNING_CONSUMERS &&
            LearningQuarantineGate6470.shouldDropForLearning(positionId = env.positionId, mint = env.mint)
        ) {
            val reason = try {
                LearningQuarantineGate6470.quarantineReason(env.positionId, env.mint) ?: "LEARNING_QUARANTINE"
            } catch (_: Throwable) { "LEARNING_QUARANTINE" }
            try {
                CanonicalFinalizedTradeBus6464.exclude(consumer, env.tradeId, reason)
                excluded.incrementAndGet()
                PipelineHealthCollector.labelInc("LEARNING_QUARANTINE_EXCLUDED_NO_MUTATION_6697")
            } catch (_: Throwable) {}
            return false
        }

        val ok = when (consumer) {
            "RewardPurity"       -> deliverToRewardPurity(env)
            "LearnerRewardBridge" -> deliverToLearnerRewardBridge(env)
            "LosingStreakReflex"  -> deliverToLosingStreakReflex(env)
            "GrowthRewardShaper"  -> deliverToGrowthRewardShaper(env)
            "TacticSwitcher"      -> deliverToTacticSwitcher(env)
            "Governor"            -> deliverToGovernor(env)
            "CapitalCreed"        -> deliverToCapitalCreed(env)
            "EVEstimator"         -> deliverToEvEstimator(env)
            "AatePolicyReward"    -> deliverToAatePolicyReward(env)
            "StrategyHypothesisEngine" -> deliverToStrategyHypothesis(env)
            "MemeCausalLearning6568" -> deliverToMemeCausalLearning6568(env)
            "ForwardOutcomeModel" -> deliverToForwardOutcomeModel6696(env)
            "UnifiedExitPolicyHead" -> deliverToUnifiedExitPolicyHead6696(env)
            "CausalFeedback6715"  -> deliverToCausalFeedback6715(env)
            "Dashboard"           -> deliverToDashboard(env)
            "OperatorFingerprint7074" -> deliverToOperatorFingerprint7074(env)
            else -> false
        }
        if (ok) delivered.incrementAndGet() else refused.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(
                if (ok) "FINALIZED_CONSUMER_DELIVERED_${consumer}_6465"
                else "FINALIZED_CONSUMER_REFUSED_${consumer}_6465"
            )
        } catch (_: Throwable) {}
        return ok
    }

    /** Consumers that are NOT learning targets — quarantine does not gate them. */
    private val NON_LEARNING_CONSUMERS = setOf("Dashboard", "CausalFeedback6715")

    private fun deliverToCausalFeedback6715(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        CausalFeedbackAuthority6715.onTerminal(env)
    } catch (_: Throwable) { false }

    private fun deliverToRewardPurity(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean =
        RewardPurityGate6441.outcomeOf(env.positionId) != null ||
            RewardPurityGate6441.acceptFinalizedClose(
                env.positionId, env.realizedPnlSol, env.economicEventId,
            )

    private fun deliverToLearnerRewardBridge(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.LearnerRewardBridge6440.acceptFinalized6486(
            env.positionId, env.mint, env.lane, env.entryTactic, env.mode,
            env.realizedReturnPct, env.realizedPnlSol, env.holdingTimeMs / 60_000.0,
        )
    } catch (_: Throwable) { false }

    /**
     * V5.0.7074 — THE TRADE-CLOSE HOOK THAT WAS NEVER WRITTEN.
     *
     * OperatorFingerprintAI scores a candidate by its DEPLOYER's win/loss
     * record — in this asset class the single most predictive thing knowable
     * before entry, because a wallet that has rugged repeatedly is telling you
     * what it does for a living.
     *
     * It has never scored anything. Its own source says so:
     *   "Scanner rarely populates candidate.extra[creator] ... which kept this
     *    layer 100% DEAD (z=1530 nz=0)"
     *
     * Dead at BOTH ends, the same shape as the creator blacklist V5.0.7070
     * fixed:
     *   WRITE  recordOutcome() had ZERO callers, so `records` was permanently
     *          empty and every lookup returned NEW_OPERATOR / 0.
     *   READ   score() keys on the first 8 chars of the MINT as an "operator
     *          proxy", which for pump.fun is effectively unique per token — so
     *          even a populated map would have been keyed wrong.
     *
     * DataOrchestrator:166 has said since V5.9.357 that OperatorRegistry exists
     * "so OperatorFingerprintAI's trade-close hook can resolve the creator".
     * That hook is this function. It was never written; the registry has been
     * filled and unread for hundreds of builds.
     *
     * A finalized trade is exactly the right moment: the outcome is settled,
     * the mint is known, and OperatorRegistry already holds the deployer from
     * the pump.fun create event. No IO, no provider call, no new gate — one
     * in-memory map write per closed trade.
     */
    private fun deliverToOperatorFingerprint7074(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val creator7074 = com.lifecyclebot.engine.OperatorRegistry.getDevWallet(env.mint)
        if (creator7074.isNullOrBlank()) {
            try { PipelineHealthCollector.labelInc("OPERATOR_FINGERPRINT_NO_CREATOR_7074") } catch (_: Throwable) {}
        } else {
            com.lifecyclebot.v3.scoring.OperatorFingerprintAI
                .recordOutcome(creator7074, env.realizedPnlSol > 0.0)
            try { PipelineHealthCollector.labelInc("OPERATOR_FINGERPRINT_RECORDED_7074") } catch (_: Throwable) {}
        }
        true
    } catch (_: Throwable) { false }

    private fun deliverToLosingStreakReflex(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.LosingStreakReflex6439.onTradeClosed(env.realizedPnlSol, env.mint, env.mode, env.lane)
        true
    } catch (_: Throwable) { false }

    private fun deliverToGrowthRewardShaper(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.GrowthAlignedRewardShaper6439.shape(
            env.realizedPnlSol, (env.atMs - env.holdingTimeMs).coerceAtLeast(0L), env.atMs, env.mint,
        )
        true
    } catch (_: Throwable) { false }

    private fun deliverToTacticSwitcher(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val band = env.scoreBand.ifBlank { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(env.entryScore) }
        com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486(
            env.lane, band, env.entryTactic, env.realizedReturnPct,
        )
        true
    } catch (_: Throwable) { false }

    private fun deliverToGovernor(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.LiveLaneGovernor.recordBypassOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToCapitalCreed(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.CapitalPreservationCreed6439.recordFinalized6486(env.positionId, env.realizedPnlSol)
    } catch (_: Throwable) { false }

    private fun deliverToEvEstimator(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToAatePolicyReward(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        AateDecisionFabric6512.onFinalized(env)
    } catch (_: Throwable) { false }

    private fun deliverToStrategyHypothesis(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.StrategyHypothesisEngine.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToMemeCausalLearning6568(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val memeLane = CausalFeedbackAuthority6715.isMemeOwnerLane(env.lane) ||
            env.lane.uppercase() in setOf("MEME", "STANDARD")
        if (memeLane) {
            // V5.0.6707 — restore the original V3 close-side attribution
            // consumers at the canonical terminal source. 6485+ moved terminal
            // ownership into this bus, but these four existing learners were
            // left behind in V3JournalRecorder.recordClose(). They therefore
            // stopped seeing ordinary Executor closes even though the code was
            // still present. This is wiring restoration only: no new policy,
            // thresholds, sizing authority or log-derived override.
            val pnlPctLearn6707 = when {
                !env.realizedReturnPct.isFinite() -> 0.0
                env.realizedReturnPct > 5000.0 -> 5000.0
                env.realizedReturnPct < -100.0 -> -100.0
                else -> env.realizedReturnPct
            }
            val holdMinutes6707 = (env.holdingTimeMs / 60_000L).coerceAtLeast(0L)
            val peakPct6707 = when {
                !env.mfePct.isFinite() -> 0.0
                env.mfePct < 0.0 -> 0.0
                env.mfePct > 5000.0 -> 5000.0
                else -> env.mfePct
            }
            try { com.lifecyclebot.engine.ScoreExpectancyTracker.record(env.lane, env.entryScore, pnlPctLearn6707) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.HoldDurationTracker.record(env.lane, holdMinutes6707, pnlPctLearn6707) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ExitReasonTracker.record(env.lane, env.exitReason, pnlPctLearn6707) } catch (_: Throwable) {}
            try {
                com.lifecyclebot.engine.learning.LaneExitTuner.recordClose(
                    lane = env.lane,
                    pnlPct = pnlPctLearn6707,
                    peakPct = peakPct6707,
                    exitReason = env.exitReason,
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("MEME_ORIGINAL_ATTRIBUTION_RESTORED_6707_${env.lane.uppercase().take(24)}") } catch (_: Throwable) {}

            val win = pnlPctLearn6707 > 0.5; val loss = pnlPctLearn6707 < -0.5
            com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(env.lane, env.mode.equals("paper", true), win, loss)
            com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(pnlPctLearn6707)
            val learned6713 = MemeCausalLearning6568.record(env)
            if (learned6713) {
                try {
                    SpecialistCausalFunnel6625.latestFinalizedUnlearnedKey6713(env.mint, env.lane)?.let { key ->
                        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.LEARN, "MEME_CAUSAL_ACK_6568")
                        PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_LEARN_ACK_6713_${env.lane.uppercase().take(24)}")
                    }
                } catch (_: Throwable) {}
            }
            learned6713
        } else true
    } catch (_: Throwable) { false }

    // V5.0.6696 — these two heads previously depended on Executor's
    // synchronous REWARD_PURITY outcome lookup. RewardPurity cannot accept until
    // the exact economic event is committed, so that lookup raced finality and
    // permanently dropped valid samples. 6465 runs only after the exact-event
    // check above succeeds and retries until durability is visible.
    private fun deliverToForwardOutcomeModel6696(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToUnifiedExitPolicyHead6696(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val exitReason = env.exitReason.uppercase()
        val exitWasOptimal = when {
            exitReason.contains("STOP_LOSS") || exitReason.contains("STRICT_SL") || exitReason.contains("STOPLOSS") -> false
            exitReason.contains("TAKE_PROFIT") || exitReason.contains("TRAILING_STOP") || exitReason.contains("TP_") -> true
            env.realizedReturnPct >= 2.0 -> true
            else -> false
        }
        com.lifecyclebot.engine.UnifiedExitPolicyHead.recordOutcome(env.mint, exitWasOptimal)
        try {
            PipelineHealthCollector.labelInc("UNIFIED_EXIT_POLICY_POST_COMMIT_CREDIT_6696")
            ForensicLogger.lifecycle(
                "UNIFIED_EXIT_POLICY_POST_COMMIT_CREDIT_6696",
                "positionId=${env.positionId.take(18)} lane=${env.lane} mint=${env.mint.take(10)} pnl=${env.realizedReturnPct} exit=${env.exitReason.take(80)} optimal=$exitWasOptimal",
            )
        } catch (_: Throwable) {}
        true
    } catch (_: Throwable) { false }

    private fun deliverToDashboard(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.DashboardDataProvider.onCanonicalTradeFinalized6485(env)
        true
    } catch (_: Throwable) { false }

    fun statusLine(): String =
        "delivered=${delivered.get()} refused=${refused.get()} excluded=${excluded.get()}"

    internal fun resetForTest() {
        delivered.set(0L); refused.set(0L); excluded.set(0L)
        exactEventPendingLogged6699.clear()
    }
}