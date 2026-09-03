package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6441 §6 — REWARD PURITY GATE.
 *
 * OPERATOR MANDATE §6:
 *   "Learners may consume only canonical finalized position outcomes.
 *    Partial exits update realized tranche accounting but DO NOT
 *    increment final trade W/L. Final close emits exactly ONE
 *    WIN / LOSS / BREAKEVEN. Shadow, hypothetical, tactic-projection,
 *    synthetic mark, stale-history and partial-tranche outcomes MUST
 *    use separate namespaces. They can inform models but can NEVER
 *    contaminate canonical W/L/PF/EV."
 *
 * DESIGN
 * ──────
 * • acceptFinalizedClose(positionId, outcome) is the ONLY entry point
 *   through which a learner may register a canonical W/L/BE.
 * • Idempotency by positionId — one finalized close per position, ever.
 * • Shadow / hypothetical / projection outcomes must call
 *   acceptShadowSignal(namespace, ...) which routes to a separate
 *   telemetry bucket and MUST NOT touch the canonical bus.
 * • Every call is validated against CanonicalPositionAuthority6441 —
 *   the position MUST be in CLOSED lifecycle before its final outcome
 *   is accepted.
 */
object RewardPurityGate6441 {

    enum class Outcome { WIN, LOSS, BREAKEVEN }

    private val finalizedIds = ConcurrentHashMap<String, Outcome>()
    private val shadowSignals = ConcurrentHashMap<String, Long>()

    private val accepted = AtomicLong(0L)
    private val rejectedLifecycle = AtomicLong(0L)
    private val rejectedDuplicate = AtomicLong(0L)
    private val rejectedAccounting = AtomicLong(0L)
    private val shadowAccepted = AtomicLong(0L)

    /**
     * Register the canonical finalized outcome for a closed position.
     * Returns true if this call is authoritative (first finalization);
     * false if it was rejected (duplicate or position not CLOSED).
     *
     * V5.0.6576 §P0-3 — classification is delegated to the single
     * CanonicalOutcomeClassifier6576 so RewardPurity agrees with every
     * other terminal learner (previously ">0 WIN" → contradicted
     * PerformanceAnalytics and LearnerRewardBridge on the same closes).
     */
    fun acceptFinalizedClose(positionId: String, realizedPnlSol: Double): Boolean {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
        if (pos == null || pos.lifecycle != CanonicalPositionAuthority6441.Lifecycle.CLOSED) {
            rejectedLifecycle.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_REJECT_LIFECYCLE_6441",
                    "positionId=$positionId lifecycle=${pos?.lifecycle}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("REWARD_PURITY_REJECT_LIFECYCLE_6441") } catch (_: Throwable) {}
            return false
        }
        // A finalized close is still not learnable economic truth until the
        // ledger, journal, event stream and lot reducer reconcile. Likewise,
        // fallback/stale marks anywhere in the account make reward and
        // compounding inputs non-authoritative. Leave the id unconsumed so the
        // canonical bus can retry after reconciliation recovers.
        try { ForensicReconciliation6635.reconcile6635() } catch (_: Throwable) {}
        val reconciliationOk = try { ForensicReconciliation6635.deltas6647().reconciled } catch (_: Throwable) { false }
        val marks = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        if (!reconciliationOk || marks == null || marks.fallbackMarkMints > 0 || marks.staleMarkMints > 0) {
            rejectedAccounting.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("REWARD_PURITY_BLOCKED_UNRECONCILED_OR_UNPRICED_6647")
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_BLOCKED_UNRECONCILED_OR_UNPRICED_6647",
                    "positionId=$positionId reconciled=$reconciliationOk fallback=${marks?.fallbackMarkMints ?: -1} stale=${marks?.staleMarkMints ?: -1}",
                )
            } catch (_: Throwable) {}
            return false
        }
        val costBasis6576 = try {
            val c = pos.entryCostSol
            if (c > 0.0 && c.isFinite()) c else 0.0
        } catch (_: Throwable) { 0.0 }
        val canonical6576 = CanonicalOutcomeClassifier6576.classifyPnl(realizedPnlSol, costBasis6576)
        val outcome = when (canonical6576) {
            CanonicalOutcomeClassifier6576.Class.WIN -> Outcome.WIN
            CanonicalOutcomeClassifier6576.Class.LOSS -> Outcome.LOSS
            CanonicalOutcomeClassifier6576.Class.BREAKEVEN -> Outcome.BREAKEVEN
        }
        val prev = finalizedIds.putIfAbsent(positionId, outcome)
        if (prev != null) {
            rejectedDuplicate.incrementAndGet()
            try { PipelineHealthCollector.labelInc("REWARD_PURITY_REJECT_DUP_6441") } catch (_: Throwable) {}
            return false
        }
        accepted.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "REWARD_PURITY_FINAL_6441",
                "positionId=$positionId outcome=$outcome realizedPnlSol=${"%.6f".format(realizedPnlSol)}",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("REWARD_PURITY_FINAL_6441") } catch (_: Throwable) {}
        return true
    }

    /**
     * Register a shadow / projection / hypothetical outcome. Never flows
     * into the canonical W/L bus. Only used by learners that model
     * counterfactual paths.
     */
    fun acceptShadowSignal(namespace: String, tag: String): Boolean {
        val key = "$namespace|$tag"
        shadowSignals[key] = System.currentTimeMillis()
        shadowAccepted.incrementAndGet()
        try { PipelineHealthCollector.labelInc("REWARD_SHADOW_6441") } catch (_: Throwable) {}
        return true
    }

    fun outcomeOf(positionId: String): Outcome? = finalizedIds[positionId]

    fun canonicalCounts(): Triple<Long, Long, Long> {
        var w = 0L; var l = 0L; var b = 0L
        for (o in finalizedIds.values) when (o) {
            Outcome.WIN -> w++
            Outcome.LOSS -> l++
            Outcome.BREAKEVEN -> b++
        }
        return Triple(w, l, b)
    }

    fun statusLine(): String {
        val (w, l, b) = canonicalCounts()
        return "finalized=${finalizedIds.size} W=$w L=$l BE=$b " +
            "shadow=${shadowSignals.size} accepted=${accepted.get()} " +
            "rejectLifecycle=${rejectedLifecycle.get()} rejectDup=${rejectedDuplicate.get()} rejectAccounting=${rejectedAccounting.get()}"
    }
}
