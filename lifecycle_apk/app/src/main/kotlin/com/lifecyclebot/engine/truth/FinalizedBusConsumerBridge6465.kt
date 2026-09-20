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
        retireIfPermanentlyRefused7169(consumer, env, ok)
        // V5.0.7154 — per-consumer tallies the report can actually print.
        // The operator's snapshot said only "delivered=400 refused=522", which
        // names no consumer and no cause; the per-consumer labels below have
        // existed all along but sit in the unprinted tail of ~1900 counters,
        // so in practice nobody has ever seen which learner is refusing.
        try {
            (if (ok) deliveredBy7154 else refusedBy7154)
                .computeIfAbsent(consumer) { java.util.concurrent.atomic.AtomicLong(0L) }
                .incrementAndGet()
            PipelineHealthCollector.labelInc(
                if (ok) "FINALIZED_CONSUMER_DELIVERED_${consumer}_6465"
                else "FINALIZED_CONSUMER_REFUSED_${consumer}_6465"
            )
        } catch (_: Throwable) {}
        return ok
    }

    /**
     * V5.0.7169 — attribution is a fact about a trade, so it happens once per
     * trade however many times the envelope is redelivered. Bounded: the book
     * holds hundreds of closes, not thousands, and if the cap is ever reached
     * the worst case is one extra attribution pass, never a stuck consumer.
     */
    private val attributionApplied7169 = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun firstAttribution7169(tradeId: String): Boolean {
        if (tradeId.isBlank()) return true
        if (attributionApplied7169.size > 8192) attributionApplied7169.clear()
        return attributionApplied7169.putIfAbsent(tradeId, true) == null
    }

    /**
     * V5.0.7169 §A REFUSAL REPEATED IS A VERDICT, NOT A TRANSIENT.
     *
     * deliverOne6734 only acks on success, and redeliverPending6486 re-walks
     * every unacked envelope against every consumer on every economic commit.
     * That is the right shape for a learner waiting on durability — and the
     * wrong shape for one that has already decided. MemeCausalLearning6568
     * refuses a close whose positionId has no entry snapshot; the snapshot is
     * written once, at entry, so it will never appear. That row is retried
     * for the life of the process.
     *
     * The operator's 5.0.7166 pays for it twice: 521 refusals against 368
     * deliveries, and a redelivery sweep that is 329 envelopes x 16 consumers
     * per commit, on a device already reporting workerTimeout=57 and a
     * 29,590 ms worst cycle.
     *
     * After three identical refusals the bus is told so explicitly. Exclusion
     * is the mechanism the bridge already uses for a row a consumer will
     * never take (learning-ineligible, quarantined), it is reported in the
     * audit's excluded= column rather than hidden, and deliverOne6734 skips
     * excluded rows — so the sweep shrinks to the envelopes still genuinely
     * waiting. The consumer keeps its verdict; it just stops being asked.
     */
    private const val REFUSALS_BEFORE_RETIRE_7169 = 3
    private val refusalStreak7169 =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun retireIfPermanentlyRefused7169(
        consumer: String,
        env: CanonicalFinalizedTradeBus6464.Envelope,
        ok: Boolean,
    ) {
        try {
            val key = "$consumer|${env.tradeId}"
            if (ok) { refusalStreak7169.remove(key); return }
            if (env.tradeId.isBlank()) return
            val n = refusalStreak7169
                .computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }
                .incrementAndGet()
            if (n < REFUSALS_BEFORE_RETIRE_7169) return
            CanonicalFinalizedTradeBus6464.exclude(
                consumer, env.tradeId, "CONSUMER_REFUSED_${n}_TIMES_7169",
            )
            excluded.incrementAndGet()
            refusalStreak7169.remove(key)
            PipelineHealthCollector.labelInc("FINALIZED_CONSUMER_RETIRED_AFTER_REFUSALS_7169")
            PipelineHealthCollector.labelInc("FINALIZED_CONSUMER_RETIRED_7169_$consumer")
            ForensicLogger.lifecycle(
                "FINALIZED_CONSUMER_RETIRED_AFTER_REFUSALS_7169",
                "consumer=$consumer tradeId=${env.tradeId.take(24)} mint=${env.mint.take(10)} " +
                    "lane=${env.lane} refusals=$n action=stop_redelivering",
            )
        } catch (_: Throwable) {}
    }

    private val deliveredBy7154 =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val refusedBy7154 =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /**
     * V5.0.7154 §A THROW AND A DECLINE ARE NOT THE SAME ANSWER.
     *
     * Every one of the fifteen delivery handlers ended `catch (_: Throwable)
     * { false }`. A learner that crashed and a learner that legitimately said
     * "not mine" therefore produced the identical outcome, and both landed in
     * one `refused` total. On the operator's 5.0.7145 device that total is
     * 522 against 400 delivered — more than half of every finalized trade
     * failing to reach a learner, with no way to tell a bug from a policy.
     *
     * Swallowing the throwable also destroyed the one thing that would have
     * identified the handler, since the dispatch is a `when` over a string
     * and the stack was the only evidence of which arm ran. This records the
     * exception type and the first com.lifecyclebot frame — enough to name
     * the handler and the line — and still returns false, so behaviour is
     * unchanged and only the silence is removed.
     */
    private fun threw7154(t: Throwable): Boolean {
        try {
            val frame = t.stackTrace.firstOrNull { it.className.startsWith("com.lifecyclebot") }
            val at = frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
            PipelineHealthCollector.labelInc("FINALIZED_CONSUMER_THREW_7154")
            PipelineHealthCollector.labelInc("FINALIZED_CONSUMER_THREW_7154|$at".take(60))
            ForensicLogger.lifecycle(
                "FINALIZED_CONSUMER_THREW_7154",
                "error=${t.javaClass.simpleName} msg=${t.message?.take(140)} at=$at " +
                    "action=counted_as_refused_but_it_is_a_crash",
            )
        } catch (_: Throwable) {}
        return false
    }

    /** Consumers that are NOT learning targets — quarantine does not gate them. */
    private val NON_LEARNING_CONSUMERS = setOf("Dashboard", "CausalFeedback6715")

    private fun deliverToCausalFeedback6715(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        CausalFeedbackAuthority6715.onTerminal(env)
    } catch (t: Throwable) { threw7154(t) }

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
    } catch (t: Throwable) { threw7154(t) }

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
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToLosingStreakReflex(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.LosingStreakReflex6439.onTradeClosed(env.realizedPnlSol, env.mint, env.mode, env.lane)
        true
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToGrowthRewardShaper(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.GrowthAlignedRewardShaper6439.shape(
            env.realizedPnlSol, (env.atMs - env.holdingTimeMs).coerceAtLeast(0L), env.atMs, env.mint,
        )
        true
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToTacticSwitcher(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val band = env.scoreBand.ifBlank { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(env.entryScore) }
        com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486(
            env.lane, band, env.entryTactic, env.realizedReturnPct,
        )
        true
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToGovernor(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.LiveLaneGovernor.recordBypassOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToCapitalCreed(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.CapitalPreservationCreed6439.recordFinalized6486(env.positionId, env.realizedPnlSol)
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToEvEstimator(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToAatePolicyReward(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        AateDecisionFabric6512.onFinalized(env)
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToStrategyHypothesis(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.StrategyHypothesisEngine.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (t: Throwable) { threw7154(t) }

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
            // V5.0.7169 §A REFUSAL AT THE END OF THIS FUNCTION REPLAYED
            // EVERYTHING AT THE START OF IT.
            //
            // The six learners below are unconditional side effects. The
            // function's RETURN VALUE, forty lines down, is whatever
            // MemeCausalLearning6568.record() says — and that refuses any
            // close whose positionId has no entry snapshot. A false return is
            // not an ack, so deliverOne6734:237 removes the tradeId from the
            // ack set, redeliverPending6486:254 walks every unacked envelope
            // on every economic commit, and requestRetry6486 fires it four
            // more times. The close comes back, and these six run again.
            // Forever.
            //
            // The operator's 5.0.7166, 765 seconds in:
            //
            //   Lane Exit Tuner lifetimes: PRESALE_SNIPE 1542 · STANDARD 1490
            //     · MOONSHOT 688 · CYCLIC 836 · EXPRESS 677   (sum ~5,289)
            //   Lifetime completed trades: 344
            //   consumerBridge: delivered=368 refused=521
            //     refusedBy7154=[MemeCausalLearning6568=521]
            //
            // Five thousand recorded closes from three hundred and forty-four
            // real ones. And the amplification is not uniform — it multiplies
            // exactly the closes that get refused, which are the ones with no
            // entry snapshot: recovered inventory and write-offs, the worst
            // rows in the book. That is why the tuner's window says a lane is
            // bleeding while the strategy table says CYCLIC earns +45.95% and
            // PROJECT_SNIPER +44.22%. Two builds of tuner fixes (7164, 7167)
            // were reading a feed that counts the bad closes fifteen times.
            //
            // It reaches further than the tuner. ColdStreakDamper.noteOutcome
            // is in this same block, so a cold streak is re-declared on every
            // retry, and the sizing stack answers with CRYPTO_LEV x0.23 and
            // MOONSHOT x0.26 — dust orders whose fixed round-trip cost is now
            // 1.09 SOL against 1.71 realised. The fee problem and the tuner
            // problem are the same bug.
            //
            // Attribution is a fact about a trade, so it applies once per
            // trade. The causal learner below still retries on its own terms;
            // only the side effects are made idempotent.
            if (firstAttribution7169(env.tradeId)) {
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
            } else {
                try { PipelineHealthCollector.labelInc("MEME_ATTRIBUTION_REPLAY_SUPPRESSED_7169") } catch (_: Throwable) {}
            }
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
    } catch (t: Throwable) { threw7154(t) }

    // V5.0.6696 — these two heads previously depended on Executor's
    // synchronous REWARD_PURITY outcome lookup. RewardPurity cannot accept until
    // the exact economic event is committed, so that lookup raced finality and
    // permanently dropped valid samples. 6465 runs only after the exact-event
    // check above succeeds and retries until durability is visible.
    private fun deliverToForwardOutcomeModel6696(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (t: Throwable) { threw7154(t) }

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
    } catch (t: Throwable) { threw7154(t) }

    private fun deliverToDashboard(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.DashboardDataProvider.onCanonicalTradeFinalized6485(env)
        true
    } catch (t: Throwable) { threw7154(t) }

    fun statusLine(): String {
        // V5.0.7154 — name the refusers. A bare refused=522 is a number the
        // operator cannot act on; the same 522 attributed to two consumers is
        // a bug report.
        val worst7154 = refusedBy7154.entries
            .sortedByDescending { it.value.get() }
            .take(6)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
            .ifBlank { "none" }
        return "delivered=${delivered.get()} refused=${refused.get()} excluded=${excluded.get()} " +
            "refusedBy7154=[$worst7154]"
    }

    internal fun resetForTest() {
        delivered.set(0L); refused.set(0L); excluded.set(0L)
        exactEventPendingLogged6699.clear()
    }
}