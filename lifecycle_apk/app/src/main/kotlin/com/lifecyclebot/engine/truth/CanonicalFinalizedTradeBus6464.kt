package com.lifecyclebot.engine.truth

import kotlinx.coroutines.launch

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6464 §P0-#7 — CANONICAL FINALIZED TRADE BUS (single source; parity).
 *
 * OPERATOR MANDATE:
 *   "LearnerRewardBridge queries=373 wins=117 losses=256. LosingStreakReflex
 *    totalTrips=0. GrowthRewardShaper shaped=0. Still disconnected. Create
 *    exactly one canonical FINALIZED_TRADE event bus. Every unique
 *    finalized trade fans out to LearnerRewardBridge, LosingStreakReflex,
 *    GrowthRewardShaper, TacticSwitcher, Governor, CapitalCreed, EV
 *    estimator, dashboard. Add parity: canonicalFinalizedUnique,
 *    learnerUnique, reflexUnique, shaperUnique, tacticUnique,
 *    governorUnique. Report missing tradeIds per consumer. No enabled
 *    consumer may silently remain at zero."
 *
 * DESIGN
 * ──────
 * A dedup'd sink that consumers register with. `publish(tradeId, ...)`
 * increments the canonical counter and fans out to every registered
 * consumer. Each consumer maintains its own dedup so a slow consumer
 * cannot lose events on restart.
 *
 * Parity report exposes `canonicalUnique` + per-consumer unique counts.
 * `missingByConsumer(name)` returns tradeIds seen by the bus but not
 * yet acknowledged by that consumer.
 *
 * V5.0.6485: this is the parity/fanout projection of the single rich
 * `CanonicalTradeFinalizedBus6450` publication. Terminal reducers publish
 * only to 6450; 6450 forwards the identical event identity here once.
 *
 * V5.0.6697: ACK means actual consumer mutation only. A trade that is
 * deliberately excluded from learning is recorded separately as EXCLUDED;
 * it is neither retried forever nor counted as successfully learned.
 */
object CanonicalFinalizedTradeBus6464 {

    data class Envelope(
        val tradeId: String,
        val atMs: Long,
        val realizedPnlSol: Double,
        val realizedReturnPct: Double,
        val mint: String,
        val lane: String,
        val positionId: String = tradeId,
        val mode: String = "unknown",
        val proofState: String = "unknown",
        val holdingTimeMs: Long = 0L,
        val entryScore: Int = 0,
        val entryTactic: String = "",
        // V5.0.7427 — exact immutable strategy identity from entry snapshot.
        val entryTradeType: String = "",
        val entrySetup: String = "",
        val entryStyle: String = "",
        val entryEntryStyle: String = "",
        val entryExitStyle: String = "",
        val entryStrategyVariantId: String = "",
        val entrySource: String = "",
        val marketRegime: String = "",
        val scoreBand: String = "",
        val mfePct: Double = 0.0,
        val maePct: Double = 0.0,
        val terminal: Boolean = true,
        val learningEligible: Boolean = true,
        val learningEligibilityReason: String = "ELIGIBLE",
        val assetClassTag: String = AssetClass.fromLane(lane).tag,
        val economicEventId: String = "",
        val exitReason: String = "",
    )

    private val canonicalSeen = ConcurrentHashMap<String, Envelope>()
    private val consumerAcks = ConcurrentHashMap<String, MutableSet<String>>() // consumer -> actually processed tradeIds
    private val consumerExcluded = ConcurrentHashMap<String, MutableSet<String>>() // consumer -> intentionally not mutated
    private val exclusionReasons = ConcurrentHashMap<String, String>() // consumer|tradeId -> reason
    private val publishes = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    // V5.0.7493 — monotonic revision of UNIQUE canonical bus population.
    private val canonicalRevision7493 = AtomicLong(0L)
    // V5.0.7494 — ACK/exclusion population revision for exact parity caching.
    private val consumerParityRevision7494 = AtomicLong(0L)

    fun canonicalRevision7493(): Long = canonicalRevision7493.get()
    private val retryRunning6486 = AtomicBoolean(false)

    private val CANONICAL_CONSUMERS_6485 = listOf(
        "RewardPurity", "LearnerRewardBridge", "LosingStreakReflex", "GrowthRewardShaper", "TacticSwitcher",
        "Governor", "CapitalCreed", "EVEstimator", "AatePolicyReward", "StrategyHypothesisEngine", "ExactStrategyPerformance7429", "MemeCausalLearning6568",
        "ForwardOutcomeModel", "UnifiedExitPolicyHead", "CausalFeedback6715", "SuperIntelligenceCalibration7636", "QuantMetrics7665", "Dashboard",
        // V5.0.7074 — operator/deployer reputation. See
        // FinalizedBusConsumerBridge6465.deliverToOperatorFingerprint7074.
        "OperatorFingerprint7074", "KillSwitch7835",
    )
    fun ensureCanonicalConsumers6485() { CANONICAL_CONSUMERS_6485.forEach(::registerConsumer) }

    /** Consumers register once at startup. Registration is idempotent. */
    fun registerConsumer(name: String) {
        val beforeAck7494 = consumerAcks[name]?.size ?: -1
        val beforeEx7494 = consumerExcluded[name]?.size ?: -1
        val acks = consumerAcks.computeIfAbsent(name) { java.util.Collections.synchronizedSet(HashSet()) }
        consumerExcluded.computeIfAbsent(name) { java.util.Collections.synchronizedSet(HashSet()) }
            .addAll(CanonicalFinalityPersistence6486.excludedIds6734(name))
        acks.addAll(CanonicalFinalityPersistence6486.ackedIds6486(name).filterNot { isExcluded(name, it) })
        if (beforeAck7494 != acks.size || beforeEx7494 != (consumerExcluded[name]?.size ?: 0)) {
            consumerParityRevision7494.incrementAndGet()
        }
    }

    /** Mark a canonical event as intentionally excluded for one consumer.
     * This is terminal for retry purposes but is NOT an ACK and therefore does
     * not inflate the consumer's learned/processed population. */
    fun exclude(consumer: String, tradeId: String, reason: String) {
        if (consumer.isBlank() || tradeId.isBlank()) return
        registerConsumer(consumer)
        val addedEx7494 = consumerExcluded[consumer]?.add(tradeId) == true
        val removedAck7494 = consumerAcks[consumer]?.remove(tradeId) == true
        exclusionReasons["$consumer|$tradeId"] = reason
        if (addedEx7494 || removedAck7494) consumerParityRevision7494.incrementAndGet()
        CanonicalFinalityPersistence6486.recordExclusion6734(consumer, tradeId, reason)
        try {
            PipelineHealthCollector.labelInc("FINALIZED_BUS_CONSUMER_EXCLUDED_${consumer}_6697".take(60))
            ForensicLogger.lifecycle(
                "FINALIZED_BUS_CONSUMER_EXCLUDED_6697",
                "consumer=$consumer tradeId=${tradeId.take(24)} reason=${reason.take(120)}",
            )
        } catch (_: Throwable) {}
    }

    fun isExcluded(consumer: String, tradeId: String): Boolean =
        consumerExcluded[consumer]?.contains(tradeId) == true

    /**
     * V5.0.7097 — mark one canonical event terminally ineligible for EVERY
     * canonical consumer, in one call, before any delivery is attempted.
     *
     * A terminal that cannot be learned from must still be accounted for:
     * AcceptanceInvariantAudit6441 §4 requires canonical CLOSED to equal the
     * bus population AND to equal processed + excluded. The alternative that
     * producers reached for instead — not publishing at all — makes the first
     * of those two permanently unsatisfiable and loses the trade.
     *
     * The consumer list is private on purpose (one authority over who the
     * canonical consumers are); this is how a producer addresses all of them
     * without keeping a second copy of that list.
     */
    fun excludeForAllCanonicalConsumers7097(tradeId: String, reason: String) {
        if (tradeId.isBlank()) return
        CANONICAL_CONSUMERS_6485.forEach { exclude(it, tradeId, reason) }
    }

    /**
     * Publish a finalized trade. Returns true when this is a first
     * observation; false when duplicate. Consumers pull via `pending()`
     * or acknowledge one-shot via `ack(consumer, tradeId)`.
     */
    fun publish(env: Envelope): Boolean {
        if (env.tradeId.isBlank() || env.positionId.isBlank() || !env.terminal) return false
        val prev = canonicalSeen.putIfAbsent(env.tradeId, env)
        publishes.incrementAndGet()
        if (prev != null) {
            duplicates.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FINALIZED_BUS_DUPLICATE_6464") } catch (_: Throwable) {}
            // V5.0.7232 §SELL_OK_TRUTH — a duplicate publish is a
            //   redispatch, not a unique successful sell. Feed the
            //   observation into SellFinalityUniqueCounter7231 so the
            //   operator's SELL_FINALITY_UNIQUE_7231 counter separates
            //   re-entrant traffic from real terminal events (the
            //   diagnosis was 920 SELL_OK vs 492 canonical closes).
            try {
                com.lifecyclebot.engine.truth.SellFinalityUniqueCounter7231.recordFinality(
                    transactionSignature = env.economicEventId,
                    canonicalCloseId = env.tradeId,
                )
            } catch (_: Throwable) {}
            return false
        }
        canonicalRevision7493.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_6464") } catch (_: Throwable) {}
        // V5.0.7752 — the token's price after a live close (did the exit keep the edge).
        try { ExitRegret7752.onClose(env) } catch (_: Throwable) {}
        // V5.0.7232 §SELL_OK_TRUTH — record the unique finality at the
        //   canonical publish point. Redispatch handled in the prev != null
        //   branch above.
        try {
            com.lifecyclebot.engine.truth.SellFinalityUniqueCounter7231.recordFinality(
                transactionSignature = env.economicEventId,
                canonicalCloseId = env.tradeId,
            )
        } catch (_: Throwable) {}
        // V5.0.6831 §EXPRESS_EXIT_PRICE_INTEGRITY — if the EXPRESS exit
        //   integrity gate stamped this position non-trainable (bad quote,
        //   epsilon fill, price discontinuity, unresolved decimals, etc.)
        //   override learningEligible so the finalized envelope does not
        //   poison EXPRESS expectancy / WR / LaneExpectancyDamper /
        //   UnifiedPolicyHead. Reward-purity contract for item #6 of the
        //   6828 diagnosis and the operator's Feb 2026 EXPRESS repair
        //   directive.
        val expressIntegrityNonTrainable6831 = try {
            ExpressExitPriceIntegrity6831.isNonTrainable(env.positionId)
        } catch (_: Throwable) { false }
        val finalLearningEligible6831 = env.learningEligible && !expressIntegrityNonTrainable6831
        if (expressIntegrityNonTrainable6831) {
            try {
                PipelineHealthCollector.labelInc("FINALIZED_LEARNING_EXCLUDED_EXPRESS_EXIT_INTEGRITY_6831")
                val verdict = ExpressExitPriceIntegrity6831.verdictFor(env.positionId)
                if (verdict != null) {
                    PipelineHealthCollector.labelInc(
                        "FINALIZED_LEARNING_EXCLUDED_EXPRESS_EXIT_INTEGRITY_6831_${verdict.name}"
                    )
                }
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "FINALIZED_LEARNING_EXCLUDED_EXPRESS_EXIT_INTEGRITY_6831",
                    "positionId=${env.positionId.take(24)} mint=${env.mint.take(10)} " +
                        "lane=${env.lane} exitReason=${env.exitReason.take(40)} " +
                        "action=excluded_from_all_learners"
                )
            } catch (_: Throwable) {}
        }
        // V5.0.6829 §SELECTION_QUALITY — feed the rolling WR authority on
        //   every clean terminal publish so intake score-floor deltas
        //   track actual lane performance. Only train from
        //   learningEligible closes to avoid poisoning WR with stale-mark
        //   scratches (V5.0.6829 §STALE_MARK_RUNNER_PROTECTION contract).
        //   V5.0.6831 additionally gates on EXPRESS exit-price integrity.
        //   Fail-silent.
        try {
            if (finalLearningEligible6831 && env.lane.isNotBlank()) {
                val won = env.realizedPnlSol > 0.0
                SelectionQualityAuthority6829.recordTerminal(env.mode, env.lane, won)
            }
        } catch (_: Throwable) {}
        // V5.0.6829 — release CausalDedupGate6829 claim on terminal.
        try {
            if (env.lane.isNotBlank() && env.mint.isNotBlank()) {
                CausalDedupGate6829.releaseIntent(env.mode, env.mint, env.lane, env.tradeId)
            }
        } catch (_: Throwable) {}
        // V5.0.6475 — never ACK at publish time. An ACK means the named
        // consumer actually accepted/processed this envelope. Delivery is
        // responsible for adding it; missing/unwired consumers must remain
        // visible in parity instead of reporting a false zero-free bus.
        try { PipelineHealthCollector.labelInc("FINALIZED_BUS_AWAITING_CONSUMER_ACK_6475") } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.6465 §P0-#2 — publish + drive per-consumer work.
     *
     * `deliver(consumer, env)` is called for each registered consumer;
     * a `false` return means the consumer refused the delivery unless it
     * explicitly marked the trade EXCLUDED. Only TRUE creates an ACK.
     */
    private val deliveryInFlight6734 = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    private val retryAfter7863 = ConcurrentHashMap<Pair<String, String>, Long>()

    private fun deliverOne6734(name: String, env: Envelope, deliver: (String, Envelope) -> Boolean) {
        val key = name to env.tradeId
        if ((retryAfter7863[key] ?: 0L) > System.currentTimeMillis()) return
        if (!deliveryInFlight6734.add(key)) return
        try {
            val acks = consumerAcks[name] ?: return
            if (isExcluded(name, env.tradeId)) return
            if (env.tradeId in acks || CanonicalFinalityPersistence6486.hasAck6486(name, env.tradeId)) {
                acks.add(env.tradeId)
                return
            }
            val ok = try { deliver(name, env) } catch (_: Throwable) { false }
            when {
                isExcluded(name, env.tradeId) -> { retryAfter7863.remove(key); acks.remove(env.tradeId) }
                ok -> {
                    retryAfter7863.remove(key)
                    acks.add(env.tradeId)
                    CanonicalFinalityPersistence6486.recordAck6486(name, env.tradeId)
                    try { PipelineHealthCollector.labelInc("FINALIZED_BUS_CONSUMER_ACKED_${name}_6475") } catch (_: Throwable) {}
                }
                else -> {
                    retryAfter7863[key] = System.currentTimeMillis() + 30_000L
                    acks.remove(env.tradeId)
                    try { PipelineHealthCollector.labelInc("FINALIZED_BUS_CONSUMER_DELIVERY_FAILED_${name}_6465") } catch (_: Throwable) {}
                }
            }
        } finally {
            deliveryInFlight6734.remove(key)
        }
    }

    fun deliverToConsumers(env: Envelope, deliver: (String, Envelope) -> Boolean) {
        if (env.tradeId.isBlank()) return
        val canonical = canonicalSeen[env.tradeId] ?: return
        // A retry must deliver the exact first-published immutable envelope.
        for (name in consumerAcks.keys) deliverOne6734(name, canonical, deliver)
    }

    fun redeliverPending6486() {
        for (env in canonicalSeen.values) {
            for (name in consumerAcks.keys) {
                deliverOne6734(name, env, FinalizedBusConsumerBridge6465::deliver)
            }
        }
    }

    fun requestRetry6486() {
        if (!retryRunning6486.compareAndSet(false, true)) return
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                repeat(4) {
                    kotlinx.coroutines.delay(2_000L * (it + 1))
                    redeliverPending6486()
                }
            } finally { retryRunning6486.set(false) }
        }
    }

    fun ack(consumer: String, tradeId: String) {
        if (tradeId.isBlank()) return
        registerConsumer(consumer)
        val removedEx7494 = consumerExcluded[consumer]?.remove(tradeId) == true
        exclusionReasons.remove("$consumer|$tradeId")
        val addedAck7494 = consumerAcks[consumer]?.add(tradeId) == true
        if (removedEx7494 || addedAck7494) consumerParityRevision7494.incrementAndGet()
        CanonicalFinalityPersistence6486.recordAck6486(consumer, tradeId)
    }

    /** Trade IDs the bus has seen but this consumer has neither processed nor explicitly excluded. */
    fun pending(consumer: String, limit: Int = 32): List<String> {
        val acks = consumerAcks[consumer] ?: emptySet<String>()
        val excluded = consumerExcluded[consumer] ?: emptySet<String>()
        return canonicalSeen.keys.filter {
            it !in acks && it !in excluded && !CanonicalFinalityPersistence6486.hasAck6486(consumer, it)
        }.take(limit)
    }

    fun canonicalUnique(): Int = canonicalSeen.size

    /** Unique completed positions, never predicate-read or replay counters. */
    fun terminalEnvelopes7863(mode: String, sinceMs: Long = 0L): List<Envelope> =
        canonicalSeen.values.asSequence().filter {
            it.terminal && it.mode.equals(mode, true) && it.atMs >= sinceMs &&
                it.realizedPnlSol.isFinite() && it.realizedReturnPct.isFinite()
        }.sortedByDescending { it.atMs }.distinctBy { it.positionId.ifBlank { it.tradeId } }.toList()


    /**
     * V5.0.7018 — the positionIds this bus has actually seen.
     *
     * AcceptanceInvariantAudit6441 compares closedPositions().size against
     * canonicalUnique() and, when they differ, reports "closed=174, bus=173".
     * Twenty-three audit runs in the operator's 5.0.7012 snapshot, twenty-three
     * failures, and not one of them says WHICH closed position never reached
     * the bus — so the same one trade has been un-findable for the whole
     * session. Envelope has carried positionId since 6464; nothing exposed it.
     */
    private data class CanonicalProjectionCache7497(
        val revision: Long,
        val positionIds: Set<String>,
        val earliestAtMs: Long?,
    )
    private val canonicalProjectionCache7497 =
        java.util.concurrent.atomic.AtomicReference<CanonicalProjectionCache7497?>(null)

    private fun canonicalProjection7497(): CanonicalProjectionCache7497 {
        val revision7497 = canonicalRevision7493.get()
        canonicalProjectionCache7497.get()?.let { c ->
            if (c.revision == revision7497) {
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_CANONICAL_PROJECTION_REUSED_7497") } catch (_: Throwable) {}
                return c
            }
        }
        val values7497 = canonicalSeen.values.toList()
        val built7497 = CanonicalProjectionCache7497(
            revision = revision7497,
            positionIds = values7497.mapNotNullTo(HashSet()) { it.positionId.ifBlank { null } },
            earliestAtMs = values7497.asSequence().map { it.atMs }.filter { it > 0L }.minOrNull(),
        )
        if (canonicalRevision7493.get() == revision7497) canonicalProjectionCache7497.set(built7497)
        return built7497
    }

    fun canonicalPositionIds7018(): Set<String> = canonicalProjection7497().positionIds

    /** V5.0.7433 — lower bound of surviving finalized-bus history. */
    fun earliestCanonicalAtMs7433(): Long? = canonicalProjection7497().earliestAtMs
    fun consumerUnique(name: String): Int = canonicalSeen.keys.count {
        consumerAcks[name]?.contains(it) == true && !isExcluded(name, it)
    }
    fun consumerExcludedUnique(name: String): Int = canonicalSeen.keys.count { isExcluded(name, it) }

    /** V5.0.7809 — per-POSITION parity inside an explicit position scope. */
    data class ScopedParity7809(val busPositions: Int, val processed: Int, val excluded: Int)

    /**
     * V5.0.7809 — AcceptanceInvariantAudit6441 §4 compares canonical CLOSED
     * positions to the bus. The bus is keyed by tradeId and can also hold
     * envelopes whose canonical row no longer exists (paper rebuild, aborted
     * entry), so whole-bus counts are not comparable to a position population.
     * Count distinct positionIds inside [scope] instead; a position is
     * processed when some envelope for it is ACKed (not excluded) by
     * [consumer], excluded when it is explicitly EXCLUDED.
     */
    fun scopedParity7809(consumer: String, scope: Set<String>): ScopedParity7809 {
        val bus = HashSet<String>(); val processed = HashSet<String>(); val excluded = HashSet<String>()
        for ((tradeId, env) in canonicalSeen) {
            val pid = env.positionId
            if (pid !in scope) continue
            bus.add(pid)
            if (isExcluded(consumer, tradeId)) excluded.add(pid)
            else if (consumerAcks[consumer]?.contains(tradeId) == true) processed.add(pid)
        }
        processed.removeAll(excluded)
        return ScopedParity7809(bus.size, processed.size, excluded.size)
    }

    data class Parity(
        val canonicalUnique: Int,
        val perConsumer: Map<String, Int>,
        val excludedByConsumer: Map<String, Int>,
        val missingByConsumer: Map<String, List<String>>,
        val zeroConsumers: List<String>,
    )

    private data class ParityCache7494(val canonicalRevision: Long, val consumerRevision: Long, val value: Parity)
    private val parityCache7494 = java.util.concurrent.atomic.AtomicReference<ParityCache7494?>(null)

    fun parity(): Parity {
        val canonicalRev7494 = canonicalRevision7493.get()
        val consumerRev7494 = consumerParityRevision7494.get()
        parityCache7494.get()?.let { c ->
            if (c.canonicalRevision == canonicalRev7494 && c.consumerRevision == consumerRev7494) {
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PARITY_REUSED_7494") } catch (_: Throwable) {}
                return c.value
            }
        }
        val perConsumer = consumerAcks.keys.associateWith(::consumerUnique)
        val excludedCounts = consumerExcluded.keys.associateWith(::consumerExcludedUnique)
        val missing = consumerAcks.mapValues { (name, acks) ->
            val excluded = consumerExcluded[name] ?: emptySet<String>()
            canonicalSeen.keys.filter { it !in acks && it !in excluded }.take(10).map { it.take(20) }
        }
        val zeros = consumerAcks.filter { it.value.isEmpty() && canonicalSeen.isNotEmpty() }.keys.toList()
        if (zeros.isNotEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "FINALIZED_BUS_ZERO_CONSUMERS_6464",
                    "canonical=${canonicalSeen.size} zeroConsumers=${zeros.joinToString(",")} excluded=${excludedCounts.entries.joinToString(",") { "${it.key}:${it.value}" }}",
                )
                PipelineHealthCollector.labelInc("FINALIZED_BUS_ZERO_CONSUMERS_6464")
            } catch (_: Throwable) {}
        }
        val built7494 = Parity(
            canonicalUnique = canonicalSeen.size,
            perConsumer = perConsumer,
            excludedByConsumer = excludedCounts,
            missingByConsumer = missing,
            zeroConsumers = zeros,
        )
        if (canonicalRevision7493.get() == canonicalRev7494 &&
            consumerParityRevision7494.get() == consumerRev7494
        ) parityCache7494.set(ParityCache7494(canonicalRev7494, consumerRev7494, built7494))
        return built7494
    }

    fun statusLine(): String {
        val p = parity()
        return "canonical=${p.canonicalUnique} publishes=${publishes.get()} duplicates=${duplicates.get()} " +
            "processed=${p.perConsumer.entries.joinToString(",") { "${it.key}=${it.value}" }} " +
            "excluded=${p.excludedByConsumer.entries.joinToString(",") { "${it.key}=${it.value}" }} " +
            "zeroConsumers=${p.zeroConsumers.joinToString(",")}"
    }

    internal fun resetForTest() {
        retryAfter7863.clear()
        canonicalSeen.clear(); consumerAcks.clear(); consumerExcluded.clear(); exclusionReasons.clear()
        publishes.set(0L); duplicates.set(0L); canonicalRevision7493.set(0L)
        consumerParityRevision7494.set(0L); parityCache7494.set(null); canonicalProjectionCache7497.set(null)
        retryRunning6486.set(false); deliveryInFlight6734.clear()
    }
}
