package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6632c §P0-E — TRANSACTIONAL_PERPS_HANDOFF_IDEMPOTENCY.
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026):
 *   > "Replace `markOwnedByPerps()` with transactional handoff
 *   >  `perpsReceiver.offer(candidate)`. Ensure candidate isn't
 *   >  marked PERPS unless ACKNOWLEDGED."
 *
 * Historical failure: a candidate was stamped as PERPS-owned by the
 * dispatcher before the perps receiver had actually accepted it.
 * If the receiver rejected (queue full / cooldown / route missing),
 * the candidate was left in a "PERPS-black-hole" state: nobody else
 * touched it (dispatcher had marked it owned), but perps never
 * processed it either.  Result: hundreds of PERPS candidates
 * discovered per session with zero executions.
 *
 * DESIGN
 * ──────
 * Two-phase transactional handoff:
 *   1. `offerToPerps(id, ...)` records an OFFERED state.  Emits
 *      `PERPS_HANDOFF_OFFERED_6632`.  Does NOT mark ownership.
 *   2. `perpsReceiver.acknowledgeReceipt(id, accepted)` confirms
 *      the outcome.  On `accepted=true` the id transitions to
 *      OWNED_BY_PERPS and `PERPS_HANDOFF_ACK_ACCEPTED_6632` fires.
 *      On `accepted=false` the id transitions to REJECTED and
 *      `PERPS_HANDOFF_ACK_REJECTED_6632` fires — the dispatcher
 *      can then re-route the candidate to another lane.
 *   3. `sweepUnacknowledged6632(ttlMs)` periodically reaps offers
 *      that never received an ACK inside the TTL.  Emits
 *      `PERPS_HANDOFF_UNACK_EXPIRED_6632`.  Reaped ids return to
 *      REJECTED state so the candidate is NOT lost — the
 *      dispatcher's next tick can re-route it.
 *
 * Idempotency: repeated `offerToPerps` for an id already OFFERED
 * or OWNED_BY_PERPS returns `Verdict.ALREADY_OFFERED` /
 * `Verdict.ALREADY_OWNED` — the dispatcher MUST not attempt a
 * second-side effect.  Repeated ACKs with the same accept-value
 * are no-ops.
 *
 * This module never mutates position/ledger/journal — it is a pure
 * causal-authority for the handoff state machine.  Callers wire
 * `offerToPerps(...)` in the dispatch site and
 * `acknowledgeReceipt(...)` in the perps receiver.
 */
object PerpsHandoffIdempotency6632 {

    enum class State { UNKNOWN, OFFERED, OWNED_BY_PERPS, REJECTED, EXPIRED }

    enum class Verdict {
        OFFERED_PROCEED,      // First offer for this id → dispatcher proceeds
        ALREADY_OFFERED,      // Offer already in flight → dispatcher bails out
        ALREADY_OWNED,        // Already ACK-accepted → dispatcher bails out
        REJECTED_RETRYABLE,   // Previously rejected/expired → dispatcher may re-route
        ACK_ACCEPTED,
        ACK_REJECTED,
        ACK_STALE,            // No matching offer → alarm
    }

    private data class Entry(
        val id: String,
        val symbol: String,
        val offeredAtMs: Long,
        @Volatile var state: State,
        @Volatile var ackAtMs: Long = 0L,
        @Volatile var rejectReason: String = "",
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val CAP = 4096

    private val offered = AtomicLong(0L)
    private val ackAccepted = AtomicLong(0L)
    private val ackRejected = AtomicLong(0L)
    private val ackStale = AtomicLong(0L)
    private val expiredReaped = AtomicLong(0L)

    const val DEFAULT_ACK_TTL_MS: Long = 15_000L

    /**
     * Step 1: dispatcher offers a candidate to the perps receiver.
     * Returns `OFFERED_PROCEED` on the first observation; the caller
     * then invokes the receiver's `offer(candidate)` API.
     */
    fun offerToPerps(id: String, symbol: String, callSite: String): Verdict {
        if (id.isBlank()) return Verdict.ACK_STALE
        val existing = entries[id]
        if (existing != null) {
            return when (existing.state) {
                State.OFFERED -> {
                    try { PipelineHealthCollector.labelInc("PERPS_HANDOFF_ALREADY_OFFERED_6632") } catch (_: Throwable) {}
                    Verdict.ALREADY_OFFERED
                }
                State.OWNED_BY_PERPS -> {
                    try { PipelineHealthCollector.labelInc("PERPS_HANDOFF_ALREADY_OWNED_6632") } catch (_: Throwable) {}
                    Verdict.ALREADY_OWNED
                }
                State.REJECTED, State.EXPIRED -> {
                    // Rejected / expired candidates are re-offerable;
                    // create a fresh entry.
                    entries[id] = Entry(id, symbol, System.currentTimeMillis(), State.OFFERED)
                    offered.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("PERPS_HANDOFF_OFFERED_6632") } catch (_: Throwable) {}
                    Verdict.OFFERED_PROCEED
                }
                State.UNKNOWN -> Verdict.OFFERED_PROCEED
            }
        }
        entries[id] = Entry(id, symbol, System.currentTimeMillis(), State.OFFERED)
        offered.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PERPS_HANDOFF_OFFERED_6632")
            ForensicLogger.lifecycle(
                "PERPS_HANDOFF_OFFERED_6632",
                "id=${id.take(24)} symbol=${symbol.take(12)} callSite=$callSite",
            )
        } catch (_: Throwable) {}
        maybeEvictOldest()
        return Verdict.OFFERED_PROCEED
    }

    /**
     * Step 2: perps receiver acknowledges receipt of the offer.
     * `accepted=true` transitions the entry to OWNED_BY_PERPS;
     * `accepted=false` transitions it to REJECTED (dispatcher may re-route).
     * An ACK for an id with no matching offer emits
     * `PERPS_HANDOFF_ACK_STALE_6632` — indicates a bug in the dispatcher.
     */
    fun acknowledgeReceipt(
        id: String,
        accepted: Boolean,
        reason: String = "",
    ): Verdict {
        if (id.isBlank()) return Verdict.ACK_STALE
        val cur = entries[id]
        if (cur == null || cur.state != State.OFFERED) {
            ackStale.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PERPS_HANDOFF_ACK_STALE_6632")
                ForensicLogger.lifecycle(
                    "PERPS_HANDOFF_ACK_STALE_6632",
                    "id=${id.take(24)} accepted=$accepted priorState=${cur?.state ?: State.UNKNOWN} " +
                        "action=receiver_acked_without_matching_offer",
                )
            } catch (_: Throwable) {}
            return Verdict.ACK_STALE
        }
        cur.ackAtMs = System.currentTimeMillis()
        if (accepted) {
            cur.state = State.OWNED_BY_PERPS
            ackAccepted.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PERPS_HANDOFF_ACK_ACCEPTED_6632") } catch (_: Throwable) {}
            return Verdict.ACK_ACCEPTED
        }
        cur.state = State.REJECTED
        cur.rejectReason = reason.take(80)
        ackRejected.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PERPS_HANDOFF_ACK_REJECTED_6632")
            ForensicLogger.lifecycle(
                "PERPS_HANDOFF_ACK_REJECTED_6632",
                "id=${id.take(24)} reason=${cur.rejectReason} action=dispatcher_may_reroute",
            )
        } catch (_: Throwable) {}
        return Verdict.ACK_REJECTED
    }

    /**
     * Sweep OFFERED entries whose ACK never arrived.  Reaped entries
     * transition to EXPIRED so the dispatcher's next tick can re-route
     * the candidate (offer semantics on an EXPIRED entry allow a fresh
     * OFFERED_PROCEED).
     */
    fun sweepUnacknowledged6632(ttlMs: Long = DEFAULT_ACK_TTL_MS) {
        val now = System.currentTimeMillis()
        for ((_, e) in entries) {
            if (e.state != State.OFFERED) continue
            if (now - e.offeredAtMs < ttlMs) continue
            e.state = State.EXPIRED
            expiredReaped.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PERPS_HANDOFF_UNACK_EXPIRED_6632")
                ForensicLogger.lifecycle(
                    "PERPS_HANDOFF_UNACK_EXPIRED_6632",
                    "id=${e.id.take(24)} symbol=${e.symbol.take(12)} " +
                        "ageMs=${now - e.offeredAtMs} action=dispatcher_may_reroute_candidate",
                )
            } catch (_: Throwable) {}
        }
    }

    private fun maybeEvictOldest() {
        if (entries.size <= CAP) return
        val oldest = entries.entries.minByOrNull { it.value.offeredAtMs }?.key ?: return
        entries.remove(oldest)
    }

    fun stateOf(id: String): State = entries[id]?.state ?: State.UNKNOWN

    fun statusLine6632(): String =
        "entries=${entries.size} offered=${offered.get()} " +
            "accepted=${ackAccepted.get()} rejected=${ackRejected.get()} " +
            "ackStale=${ackStale.get()} expired=${expiredReaped.get()}"

    internal fun resetForTest() {
        entries.clear()
        offered.set(0L); ackAccepted.set(0L); ackRejected.set(0L)
        ackStale.set(0L); expiredReaped.set(0L)
    }
}
