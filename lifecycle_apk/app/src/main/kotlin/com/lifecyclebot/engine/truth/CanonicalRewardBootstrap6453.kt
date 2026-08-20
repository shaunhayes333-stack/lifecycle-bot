package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6453 §P0-#6 — ONE REWARD OWNER (SINGLE SUBSCRIBER BOOTSTRAP).
 *
 * OPERATOR MANDATE:
 *   "Remove reward/streak/learner calls from legacy PositionCloseLedger
 *    .markClosed(). Settlement emits exactly ONE persisted
 *    PositionFinalizedEvent."
 *
 * DESIGN
 * ──────
 * On classload, subscribes GrowthAlignedRewardShaper6439 +
 * RewardPurityGate6441 to CanonicalTradeFinalizedBus6450 exactly once.
 * From this point forward, `PositionCloseLedger.markClosed` /
 * `markClosedFull` publish to the bus and the shaper + purity gate
 * receive their signal AS BUS SUBSCRIBERS — never via direct call. This
 * eliminates the parallel reward writer paths the operator's dump
 * exposed (RewardPurity 4/77 vs GrowthShaper 13/45/35 vs RewardBridge
 * 22/125 — all now derived from ONE event).
 *
 * The bootstrap is triggered by touching `ensureBootstrapped()` from any
 * code path that publishes a finalize (currently `PositionCloseLedger`).
 * The AtomicBoolean guarantees it runs exactly once per JVM.
 */
object CanonicalRewardBootstrap6453 {

    private val bootstrapped = AtomicBoolean(false)
    private val shaperInvocations = AtomicLong(0L)
    private val purityInvocations = AtomicLong(0L)
    private val bootstrapErrors = AtomicLong(0L)

    fun ensureBootstrapped() {
        try { FinalizedFanoutParity6459.ensureInstalled() } catch (_: Throwable) {}
        if (!bootstrapped.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { event ->
                // Shaper: uses net-realized-sol as the reward signal.
                // Bus published nettedRealizedPnl (not gross) is the
                // learning-appropriate value.
                try {
                    GrowthAlignedRewardShaper6439.shape(
                        realizedSolDelta = event.netRealizedPnlSol,
                        openedAtMs = event.settledAtMs - event.holdingTimeMs.coerceAtLeast(0L),
                        closedAtMs = event.settledAtMs,
                        mint = event.mint,
                    )
                    shaperInvocations.incrementAndGet()
                } catch (t: Throwable) {
                    try {
                        ForensicLogger.lifecycle(
                            "CANONICAL_REWARD_BOOTSTRAP_SHAPER_FAIL_6453",
                            "pid=${event.positionId.take(12)} err=${t.message?.take(80)}",
                        )
                    } catch (_: Throwable) {}
                }
                // Purity gate: authoritative finalized-close ledger.
                try {
                    RewardPurityGate6441.acceptFinalizedClose(event.positionId, event.netRealizedPnlSol)
                    purityInvocations.incrementAndGet()
                } catch (t: Throwable) {
                    try {
                        ForensicLogger.lifecycle(
                            "CANONICAL_REWARD_BOOTSTRAP_PURITY_FAIL_6453",
                            "pid=${event.positionId.take(12)} err=${t.message?.take(80)}",
                        )
                    } catch (_: Throwable) {}
                }
            }
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

    fun statusLine(): String = "installed=${bootstrapped.get()} " +
        "shaperInv=${shaperInvocations.get()} purityInv=${purityInvocations.get()} " +
        "installErrors=${bootstrapErrors.get()}"
}
