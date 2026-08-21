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
        val terminal: Boolean = true,
    )

    private val canonicalSeen = ConcurrentHashMap<String, Envelope>()
    private val consumerAcks = ConcurrentHashMap<String, MutableSet<String>>() // consumer -> set of tradeIds it ack'd
    private val publishes = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val retryRunning6486 = AtomicBoolean(false)

    private val CANONICAL_CONSUMERS_6485 = listOf(
        "LearnerRewardBridge", "LosingStreakReflex", "GrowthRewardShaper", "TacticSwitcher",
        "Governor", "CapitalCreed", "EVEstimator", "Dashboard",
    )
    fun ensureCanonicalConsumers6485() { CANONICAL_CONSUMERS_6485.forEach(::registerConsumer) }

    /** Consumers register once at startup. Registration is idempotent. */
    fun registerConsumer(name: String) {
        val acks = consumerAcks.computeIfAbsent(name) { java.util.Collections.synchronizedSet(HashSet()) }
        acks.addAll(CanonicalFinalityPersistence6486.ackedIds6486(name))
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
            return false
        }
        try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_6464") } catch (_: Throwable) {}
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
     * a `false` return means the consumer refused the delivery and its
     * ack is REMOVED so the parity report re-surfaces the miss. This
     * keeps the parity oracle honest: only consumers that actually
     * processed the trade stay ack'd.
     */
    fun deliverToConsumers(env: Envelope, deliver: (String, Envelope) -> Boolean) {
        if (env.tradeId.isBlank()) return
        for ((name, acks) in consumerAcks) {
            if (env.tradeId in acks || CanonicalFinalityPersistence6486.hasAck6486(name, env.tradeId)) {
                acks.add(env.tradeId)
                continue
            }
            val ok = try { deliver(name, env) } catch (_: Throwable) { false }
            if (ok) {
                acks.add(env.tradeId)
                CanonicalFinalityPersistence6486.recordAck6486(name, env.tradeId)
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_CONSUMER_ACKED_${name}_6475") } catch (_: Throwable) {}
            } else {
                acks.remove(env.tradeId)
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_CONSUMER_DELIVERY_FAILED_${name}_6465") } catch (_: Throwable) {}
            }
        }
    }

    fun redeliverPending6486() {
        for ((name, acks) in consumerAcks) {
            for ((tradeId, env) in canonicalSeen) {
                if (tradeId in acks || CanonicalFinalityPersistence6486.hasAck6486(name, tradeId)) {
                    acks.add(tradeId)
                    continue
                }
                val ok = try { FinalizedBusConsumerBridge6465.deliver(name, env) } catch (_: Throwable) { false }
                if (ok) {
                    acks.add(tradeId)
                    CanonicalFinalityPersistence6486.recordAck6486(name, tradeId)
                }
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
        consumerAcks.computeIfAbsent(consumer) { java.util.Collections.synchronizedSet(HashSet()) }
            .add(tradeId)
        CanonicalFinalityPersistence6486.recordAck6486(consumer, tradeId)
    }

    /** Trade IDs the bus has seen but this consumer has not ack'd. */
    fun pending(consumer: String, limit: Int = 32): List<String> {
        val acks = consumerAcks[consumer] ?: emptySet<String>()
        return canonicalSeen.keys.filter { it !in acks && !CanonicalFinalityPersistence6486.hasAck6486(consumer, it) }.take(limit)
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
        publishes.set(0L); duplicates.set(0L); retryRunning6486.set(false)
    }
}
