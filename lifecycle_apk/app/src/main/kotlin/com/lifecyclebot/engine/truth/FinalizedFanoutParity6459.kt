package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6459 §P0 — FINALIZED REWARD FANOUT PARITY REPORT.
 *
 * Operator: LearnerRewardBridge=436 but LosingStreakReflex=0 and
 * GrowthRewardShaper=0. Expose a parity report that names which
 * consumers received which tradeIds so silent zero-consumption is
 * impossible.
 *
 * Consumers register once (idempotent) and increment their per-event
 * counter from CanonicalTradeFinalizedBus6450 subscriber.
 */
object FinalizedFanoutParity6459 {
    private val canonicalPublished = AtomicLong(0L)
    private val consumerCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun ensureInstalled() {
        if (!installed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { _ ->
                canonicalPublished.incrementAndGet()
            }
            try { PipelineHealthCollector.labelInc("FINALIZED_FANOUT_PARITY_INSTALLED_6459") } catch (_: Throwable) {}
        } catch (_: Throwable) { installed.set(false) }
    }

    fun recordConsumer(name: String) {
        consumerCounts.getOrPut(name) { AtomicLong(0L) }.incrementAndGet()
    }

    fun statusLine(): String {
        val consumers = consumerCounts.entries.joinToString(",") { "${it.key}=${it.value.get()}" }
        val missing = consumerCounts.entries.filter { it.value.get() == 0L }.joinToString(",") { it.key }
        return "canonical=${canonicalPublished.get()} consumers[$consumers] silent[$missing]"
    }
}
