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

    private data class LifecyclePending8237(
        val positionId: String, val economicEventId: String, val envelopeMode: String,
        val actualLifecycle: String, val actualMode: String, val remainingRaw: String,
        val firstAtMs: Long, val lastAtMs: Long, val attempts: Long,
    ) {
        fun line(nowMs: Long): String = "pid=$positionId mode=$envelopeMode economicEventId=$economicEventId " +
            "expected=CLOSED actual=$actualLifecycle positionMode=$actualMode remainingRaw=$remainingRaw " +
            "attempts=$attempts ageS=${((nowMs - firstAtMs) / 1000L).coerceAtLeast(0L)}"
    }
    // A retry count alone hides whether one durable envelope or many trades are unresolved. This bounded diagnostic
    // keeps up to eight exact unresolved identities; it does not acknowledge or exclude an outcome.
    private val lifecyclePending8237 = LinkedHashMap<String, LifecyclePending8237>()

    private fun noteLifecyclePending8237(
        positionId: String, economicEventId: String, envelopeMode: String,
        pos: CanonicalPositionAuthority6441.Position?,
    ): LifecyclePending8237 = synchronized(lifecyclePending8237) {
        val nowMs = System.currentTimeMillis()
        val mode = envelopeMode.ifBlank { pos?.mode ?: "unknown" }
        val key = "$mode|$positionId|$economicEventId"
        val previous = lifecyclePending8237[key]
        if (previous == null && lifecyclePending8237.size >= 8) {
            lifecyclePending8237.entries.minByOrNull { it.value.lastAtMs }?.key?.let { lifecyclePending8237.remove(it) }
        }
        LifecyclePending8237(positionId, economicEventId, mode, pos?.lifecycle?.name ?: "MISSING",
            pos?.mode ?: "MISSING", pos?.remainingQtyRaw?.toString() ?: "UNKNOWN",
            previous?.firstAtMs ?: nowMs, nowMs, (previous?.attempts ?: 0L) + 1L,
        ).also { lifecyclePending8237[key] = it }
    }

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
    fun acceptFinalizedClose(
        positionId: String, realizedPnlSol: Double, economicEventId: String = "", envelopeMode: String = "",
    ): Boolean {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
        if (pos == null || pos.lifecycle != CanonicalPositionAuthority6441.Lifecycle.CLOSED) {
            rejectedLifecycle.incrementAndGet()
            val pending = noteLifecyclePending8237(positionId, economicEventId, envelopeMode, pos)
            try {
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_REJECT_LIFECYCLE_6441",
                    "positionId=$positionId lifecycle=${pos?.lifecycle} ${pending.line(System.currentTimeMillis())} action=retry_unacknowledged",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("REWARD_PURITY_REJECT_LIFECYCLE_6441") } catch (_: Throwable) {}
            return false
        }
        synchronized(lifecyclePending8237) {
            lifecyclePending8237.entries.removeIf { it.value.positionId == positionId && it.value.economicEventId == economicEventId }
        }
        // V5.0.7032 — a position carrying a pre-7032 paper partial cannot have
        // its economics substantiated (the partial recorded no exit mark and no
        // SOL/USD rate, so its proceeds are indistinguishable from a genuine
        // runner's), and a later full close inherits the error through a basis
        // the partial already reduced. Blocked here, at the one boundary every
        // learner's reward passes through, rather than in fifteen consumers.
        // See ContaminatedPartialQuarantine7032 for why this is a quarantine
        // and not a restatement.
        val contaminated7032 = try {
            ContaminatedPartialQuarantine7032.isContaminated(positionId)
        } catch (_: Throwable) { false }
        if (contaminated7032) {
            rejectedAccounting.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("REWARD_PURITY_REJECT_CONTAMINATED_7032")
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_REJECT_CONTAMINATED_7032",
                    "positionId=$positionId mint=${pos.mint.take(12)} " +
                        "reason=pre_7032_partial_unreconstructible action=exclude_from_learning",
                )
            } catch (_: Throwable) {}
            return false
        }
        // V5.0.6710 — defence in depth at the canonical reward boundary.
        // EconomicPurityGate6504 is marked by stale/unverified close paths.
        // A CLOSED lifecycle alone is not proof that the exit price was valid.
        val economicExcluded6710 = try {
            EconomicPurityGate6504.shouldExcludeFromAnalytics(pos.mint)
        } catch (_: Throwable) { true }
        if (economicExcluded6710) {
            rejectedAccounting.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("REWARD_PURITY_REJECT_ECONOMIC_6710")
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_REJECT_ECONOMIC_6710",
                    "positionId=$positionId mint=${pos.mint.take(12)} action=exclude_from_canonical_learning",
                )
            } catch (_: Throwable) {}
            return false
        }
        // V5.0.6699 — per-terminal-event proof survives process restart. The
        // current-process 6635 commit remains first authority; persisted finality
        // may recover only from the exact durable typed terminal SELL carrying
        // the same economicEventId/idempotencyKey.
        val terminalProof = try {
            CanonicalTerminalProof6699.resolve(positionId, economicEventId)
        } catch (_: Throwable) { null }
        if (terminalProof == null || terminalProof.qtyRaw <= java.math.BigInteger.ZERO ||
            !terminalProof.realizedPnlSol.isFinite() || !terminalProof.notionalSol.isFinite()) {
            rejectedAccounting.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("REWARD_PURITY_BLOCKED_EXACT_EVENT_PENDING_6651")
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_BLOCKED_EXACT_EVENT_PENDING_6651",
                    "positionId=$positionId economicEventId=${economicEventId.take(40)} action=no_mutation_retry_exact_event",
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
                "positionId=$positionId outcome=$outcome realizedPnlSol=${"%.6f".format(realizedPnlSol)} proof=${terminalProof.source}",
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
        val pending = synchronized(lifecyclePending8237) { lifecyclePending8237.values.toList() }
        val nowMs = System.currentTimeMillis()
        return "finalized=${finalizedIds.size} W=$w L=$l BE=$b " +
            "shadow=${shadowSignals.size} accepted=${accepted.get()} " +
            "rejectLifecycle=${rejectedLifecycle.get()} rejectDup=${rejectedDuplicate.get()} rejectAccounting=${rejectedAccounting.get()} " +
            "lifecyclePendingSamples8237=${pending.size} [${pending.joinToString("; ") { it.line(nowMs) }}]"
    }

    internal fun clearLifecycleDiagnosticsForTest8237() = synchronized(lifecyclePending8237) { lifecyclePending8237.clear() }
}
