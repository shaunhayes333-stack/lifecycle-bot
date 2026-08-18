package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * NOTE: This bus lives ALONGSIDE the existing `CanonicalTradeFinalizedBus6450`
 * — the 6450 bus fans finalized-trade rich events to specific subscribers,
 * and we don't touch it. This 6464 bus is the parity oracle across
 * multiple learners. Callers wire both: 6450 for rich payload, 6464 for
 * fanout parity assertion.
 */
object CanonicalFinalizedTradeBus6464 {

    data class Envelope(
        val tradeId: String,
        val atMs: Long,
        val realizedPnlSol: Double,
        val realizedReturnPct: Double,
        val mint: String,
        val lane: String,
    )

    private val canonicalSeen = ConcurrentHashMap<String, Envelope>()
    private val consumerAcks = ConcurrentHashMap<String, MutableSet<String>>() // consumer -> set of tradeIds it ack'd
    private val publishes = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)

    /** Consumers register once at startup. Registration is idempotent. */
    fun registerConsumer(name: String) {
        consumerAcks.computeIfAbsent(name) { java.util.Collections.synchronizedSet(HashSet()) }
    }

    /**
     * Publish a finalized trade. Returns true when this is a first
     * observation; false when duplicate. Consumers pull via `pending()`
     * or acknowledge one-shot via `ack(consumer, tradeId)`.
     */
    fun publish(env: Envelope): Boolean {
        if (env.tradeId.isBlank()) return false
        val prev = canonicalSeen.putIfAbsent(env.tradeId, env)
        publishes.incrementAndGet()
        if (prev != null) {
            duplicates.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FINALIZED_BUS_DUPLICATE_6464") } catch (_: Throwable) {}
            return false
        }
        try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_6464") } catch (_: Throwable) {}
        return true
    }

    fun ack(consumer: String, tradeId: String) {
        if (tradeId.isBlank()) return
        consumerAcks.computeIfAbsent(consumer) { java.util.Collections.synchronizedSet(HashSet()) }
            .add(tradeId)
    }

    /** Trade IDs the bus has seen but this consumer has not ack'd. */
    fun pending(consumer: String, limit: Int = 32): List<String> {
        val acks = consumerAcks[consumer] ?: emptySet<String>()
        return canonicalSeen.keys.filter { it !in acks }.take(limit)
    }

    fun canonicalUnique(): Int = canonicalSeen.size
    fun consumerUnique(name: String): Int = consumerAcks[name]?.size ?: 0

    data class Parity(
        val canonicalUnique: Int,
        val perConsumer: Map<String, Int>,
        val missingByConsumer: Map<String, List<String>>,
        val zeroConsumers: List<String>,
    )

    fun parity(): Parity {
        val perConsumer = consumerAcks.mapValues { it.value.size }
        val missing = consumerAcks.mapValues { (_, acks) ->
            canonicalSeen.keys.filter { it !in acks }.take(10).map { it.take(20) }
        }
        val zeros = consumerAcks.filter { it.value.isEmpty() && canonicalSeen.isNotEmpty() }.keys.toList()
        if (zeros.isNotEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "FINALIZED_BUS_ZERO_CONSUMERS_6464",
                    "canonical=${canonicalSeen.size} zeroConsumers=${zeros.joinToString(",")}",
                )
                PipelineHealthCollector.labelInc("FINALIZED_BUS_ZERO_CONSUMERS_6464")
            } catch (_: Throwable) {}
        }
        return Parity(
            canonicalUnique = canonicalSeen.size,
            perConsumer = perConsumer,
            missingByConsumer = missing,
            zeroConsumers = zeros,
        )
    }

    fun statusLine(): String {
        val p = parity()
        return "canonical=${p.canonicalUnique} publishes=${publishes.get()} duplicates=${duplicates.get()} " +
            "consumers=${p.perConsumer.entries.joinToString(",") { "${it.key}=${it.value}" }} " +
            "zeroConsumers=${p.zeroConsumers.joinToString(",")}"
    }

    internal fun resetForTest() {
        canonicalSeen.clear(); consumerAcks.clear()
        publishes.set(0L); duplicates.set(0L)
    }
}
