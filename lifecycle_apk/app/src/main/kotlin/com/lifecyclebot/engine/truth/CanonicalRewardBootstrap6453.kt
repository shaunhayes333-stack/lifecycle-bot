package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6453 §P0-#6 — ONE REWARD OWNER (SINGLE SUBSCRIBER BOOTSTRAP).
 *
 * OPERATOR MANDATE:
 *   "Remove reward/streak/learner calls from legacy PositionCloseLedger
 *    .markClosed(). Settlement emits exactly ONE persisted
 *    PositionFinalizedEvent."
 *
 * V5.0.6651: RewardPurity is a named 6464 consumer. That consumer retries
 * only after the exact economicEventId is durable in every required store.
 * The old immediate 6450 subscriber raced the asynchronous journal insert
 * and permanently rejected otherwise valid closes.
 *
 * The bootstrap is triggered by touching `ensureBootstrapped()` from any
 * code path that publishes a finalize (currently `PositionCloseLedger`).
 * The AtomicBoolean guarantees it runs exactly once per JVM.
 */
object CanonicalRewardBootstrap6453 {

    private val bootstrapped = AtomicBoolean(false)
    private val bootstrapErrors = java.util.concurrent.atomic.AtomicLong(0L)

    fun ensureBootstrapped() {
        try { FinalizedFanoutParity6459.ensureInstalled() } catch (_: Throwable) {}
        if (!bootstrapped.compareAndSet(false, true)) return
        try {
            CanonicalFinalizedTradeBus6464.ensureCanonicalConsumers6485()
            try { PipelineHealthCollector.labelInc("CANONICAL_REWARD_BOOTSTRAP_INSTALLED_6453") } catch (_: Throwable) {}
        } catch (t: Throwable) {
            bootstrapErrors.incrementAndGet()
            // On failure, allow retry next call.
            bootstrapped.set(false)
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_REWARD_BOOTSTRAP_INSTALL_FAIL_6453",
                    "err=${t.message?.take(80)}",
                )
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val (w, l, be) = RewardPurityGate6441.canonicalCounts()
        return "installed=${bootstrapped.get()} fanout=CanonicalFinalizedTradeBus6464 " +
            "purityAccepted=${w + l + be} installErrors=${bootstrapErrors.get()}"
    }
}
