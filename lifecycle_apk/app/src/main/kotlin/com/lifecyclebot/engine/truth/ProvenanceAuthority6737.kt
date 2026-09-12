package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6737 — §PROVENANCE_AUTHORITY.
 *
 * ONE canonical registry answering: "is this economic event GENUINE or
 * SHADOW-REPLAY?" — used by three enforcement surfaces:
 *
 *   1) MUTATION BOUNDARY (Pillar 1 — shadow-only replay)
 *      `guardMutation(origin)` refuses any REPLAY_SHADOW attempt to hit
 *      the authoritative ledger / wallet / executor. Preserves
 *      REPLAY_RESTORE (genuine event replay to rebuild positions after
 *      restart — same owner, same lane, same mode).
 *
 *   2) CONTAMINATION ISOLATION (Pillar 2)
 *      `classify(eventId, tags)` is the SINGLE decision surface. A
 *      migration walks historic events, calls classify, and stamps
 *      the origin. Idempotent by immutable event ID.
 *
 *   3) ACCOUNTING RECONCILIATION (Pillar 3)
 *      `isExcludedFromParity(eventId)` returns true for anything
 *      quarantined as ambiguous or shadow. The reconciler must skip
 *      these when computing cash/openCost/realized deltas.
 *
 * Design invariants:
 *   • Genuine positions and losses SURVIVE — REPLAY_RESTORE is not synthetic.
 *   • Ambiguous events QUARANTINED with explicit reason — never deleted, relabelled, or forced.
 *   • Missing data is not a decision — such an event stays QUARANTINE_AMBIGUOUS.
 *   • Blank origin defaults to GENUINE_PAPER (legacy-safe until explicit).
 */
object ProvenanceAuthority6737 {

    enum class Origin {
        /** Real paper trade fill originated inside this app instance. */
        GENUINE_PAPER,
        /** Real live trade fill; not exercised in this build but reserved. */
        GENUINE_LIVE,
        /** Replay of a real economic event to rebuild positions after restart.
         *  Preserves original owner lane and mode. Legitimate mutation. */
        REPLAY_RESTORE,
        /** Simulated event for shadow inventory / dashboard preview / what-if.
         *  MUST NOT touch authoritative ledger, wallet, executor, P&L, WR, risk, or rewards. */
        REPLAY_SHADOW,
        /** Could not be classified with confidence. Quarantined for later
         *  operator review. Excluded from parity + learning until re-tagged. */
        QUARANTINE_AMBIGUOUS,
    }

    data class Verdict(val allow: Boolean, val reason: String, val origin: Origin)

    /** Stored provenance per immutable event id. Never mutated after first set. */
    private val originByEventId = ConcurrentHashMap<String, Origin>()
    /** Explicit quarantine reasons, kept for operator visibility. */
    private val quarantineReasons = ConcurrentHashMap<String, String>()
    private val guardCalls = AtomicLong(0L)
    private val guardBlocks = AtomicLong(0L)

    /**
     * Refuse REPLAY_SHADOW mutations at any authoritative surface. Called by
     * `PaperAccountLedger6430.onBuy/onSell`, `CanonicalPositionAuthority6441.openPosition/partialSell`,
     * `ExecutorCanonicalMirror6442.mirrorBuy/mirrorSell`, `WalletManager` debit paths.
     *
     * Legacy callers pass `Origin.GENUINE_PAPER` (default) — no regression.
     * A caller identifying itself as REPLAY_SHADOW is HARD-refused.
     * REPLAY_RESTORE is permitted (with a distinct counter so operators see it).
     */
    fun guardMutation(origin: Origin, callSite: String): Verdict {
        guardCalls.incrementAndGet()
        return when (origin) {
            Origin.REPLAY_SHADOW -> {
                guardBlocks.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("PROVENANCE_MUTATION_BLOCKED_REPLAY_SHADOW_6737")
                    ForensicLogger.lifecycle(
                        "PROVENANCE_MUTATION_BLOCKED_REPLAY_SHADOW_6737",
                        "callSite=$callSite action=refuse_shadow_replay_on_authoritative_surface",
                    )
                } catch (_: Throwable) {}
                Verdict(false, "REPLAY_SHADOW_REFUSED", origin)
            }
            Origin.QUARANTINE_AMBIGUOUS -> {
                guardBlocks.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("PROVENANCE_MUTATION_BLOCKED_QUARANTINE_6737")
                } catch (_: Throwable) {}
                Verdict(false, "QUARANTINE_AMBIGUOUS_REFUSED", origin)
            }
            Origin.REPLAY_RESTORE -> {
                try { PipelineHealthCollector.labelInc("PROVENANCE_MUTATION_REPLAY_RESTORE_ALLOWED_6737") } catch (_: Throwable) {}
                Verdict(true, "GENUINE_REPLAY_RESTORE", origin)
            }
            Origin.GENUINE_PAPER, Origin.GENUINE_LIVE ->
                Verdict(true, "GENUINE", origin)
        }
    }

    /**
     * Idempotent classification/tagging. First call for an eventId is
     * authoritative; subsequent calls with a different verdict are IGNORED
     * so migrations can be re-run safely.
     *
     * @return the origin actually persisted for this eventId.
     */
    fun classifyOnce(eventId: String, proposed: Origin, quarantineReason: String = ""): Origin {
        if (eventId.isBlank()) return proposed
        val prior = originByEventId.putIfAbsent(eventId, proposed)
        if (prior != null) return prior
        if (proposed == Origin.QUARANTINE_AMBIGUOUS && quarantineReason.isNotBlank()) {
            quarantineReasons[eventId] = quarantineReason
        }
        try {
            PipelineHealthCollector.labelInc("PROVENANCE_CLASSIFY_${proposed.name}_6737")
        } catch (_: Throwable) {}
        return proposed
    }

    fun originOf(eventId: String): Origin? = originByEventId[eventId]
    fun quarantineReasonOf(eventId: String): String? = quarantineReasons[eventId]

    /**
     * Pillar 3 — the reconciler MUST skip these events when computing
     * cash/openCost/realized deltas against the ledger. Legitimate
     * REPLAY_RESTORE is NOT excluded (it produces real mutations).
     */
    fun isExcludedFromParity(eventId: String): Boolean {
        val o = originByEventId[eventId] ?: return false
        return o == Origin.REPLAY_SHADOW || o == Origin.QUARANTINE_AMBIGUOUS
    }

    /** Convenience: pass genuine event ID list, get the subset the reconciler must exclude. */
    fun excludedEventIds(candidateIds: Collection<String>): Set<String> =
        candidateIds.asSequence().filter { isExcludedFromParity(it) }.toSet()

    fun statusLine(): String {
        val counts = Origin.values().associateWith { o ->
            originByEventId.values.count { it == o }
        }
        return "Provenance6737 guardCalls=${guardCalls.get()} guardBlocks=${guardBlocks.get()} " +
            counts.entries.joinToString(" ") { "${it.key.name}=${it.value}" }
    }

    internal fun resetForTest6737() {
        originByEventId.clear(); quarantineReasons.clear()
        guardCalls.set(0L); guardBlocks.set(0L)
    }
}
