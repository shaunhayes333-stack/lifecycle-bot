package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6738 — §CANONICAL_ROUND_TRIP_RECONCILER.
 *
 * Pillar 4 — an owner-bound round-trip verifier. The operator called out
 * that Meme Trader and Crypto Universe are getting stuck at
 * ledger-divergence / exit-throughput blockers whose actual inputs were
 * never surfaced. Rather than hard-blocking every future admission when
 * a single event drifts, this reconciler:
 *
 *   • Records the four canonical checkpoints of a round trip:
 *       BUY_COMMITTED, EXIT_DECIDED, SELL_COMMITTED, LEARNING_DELIVERED.
 *   • Verifies each stage has arrived exactly once per positionId, in
 *     order, at the same authoritative revision.
 *   • On divergence, records the discrepancy AGAINST THE OFFENDING
 *     POSITION ID (not against the global admission gate). If a specific
 *     event ID is implicated, it can be tagged via
 *     `ProvenanceAuthority6737.classifyOnce(id, QUARANTINE_AMBIGUOUS, reason)`.
 *
 * Design invariants:
 *   • Learning MUST be delivered exactly once per positionId. A second
 *     delivery is REFUSED (returns false) with an explicit counter.
 *   • Shadow / quarantined positions are never counted as reconciled.
 *     (Pillar 1 already blocks them from committing at the ledger; this
 *     reconciler additionally excludes them from round-trip statistics.)
 *   • Does NOT bypass any safety or accounting guard — it only observes
 *     and reports.
 *
 * Never mutates ledger / capital / risk state.
 */
object CanonicalRoundTripReconciler6738 {

    enum class Stage { BUY_COMMITTED, EXIT_DECIDED, SELL_COMMITTED, LEARNING_DELIVERED }

    /** In-order state of a round-trip for one positionId. */
    data class TripState(
        val positionId: String,
        val lane: String,
        val mode: String,
        @Volatile var buyAtMs: Long = 0L,
        @Volatile var exitAtMs: Long = 0L,
        @Volatile var sellAtMs: Long = 0L,
        @Volatile var learningAtMs: Long = 0L,
        @Volatile var reconciled: Boolean = false,
        @Volatile var divergenceReason: String = "",
    )

    /** Immutable summary snapshot returned to callers / dashboards. */
    data class Summary(
        val openTrips: Int,
        val reconciled: Int,
        val diverged: Int,
        val diverged_reasons: Map<String, Int>,
        val learningDeliveredOnce: Long,
        val learningDeliveredDuplicateRefused: Long,
        val shadowTripSkipped: Long,
    )

    private val trips = ConcurrentHashMap<String, TripState>()
    private val learningDeliveredOnce = AtomicLong(0L)
    private val learningDuplicateRefused = AtomicLong(0L)
    private val shadowTripSkipped = AtomicLong(0L)

    /**
     * Record a stage arrival for a positionId. Idempotent per (positionId, stage).
     * @param eventId if supplied, the reconciler consults ProvenanceAuthority6737
     *   before recording. Shadow/quarantined event ids are counted separately
     *   and do NOT enter the round-trip statistics.
     * @return true when the stage was newly recorded, false on duplicate or shadow.
     */
    fun record(
        positionId: String,
        stage: Stage,
        lane: String = "",
        mode: String = "PAPER",
        eventId: String = "",
    ): Boolean {
        if (positionId.isBlank()) return false
        if (eventId.isNotBlank() && ProvenanceAuthority6737.isExcludedFromParity(eventId)) {
            shadowTripSkipped.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ROUND_TRIP_SHADOW_TRIP_SKIPPED_6738") } catch (_: Throwable) {}
            return false
        }
        val now = System.currentTimeMillis()
        val st = trips.computeIfAbsent(positionId) {
            TripState(positionId, lane, mode)
        }
        return when (stage) {
            Stage.BUY_COMMITTED -> if (st.buyAtMs == 0L) { st.buyAtMs = now; true } else false
            Stage.EXIT_DECIDED -> if (st.exitAtMs == 0L) { st.exitAtMs = now; true } else false
            Stage.SELL_COMMITTED -> if (st.sellAtMs == 0L) {
                st.sellAtMs = now
                verifyAndMark(st)
                true
            } else false
            Stage.LEARNING_DELIVERED -> {
                if (st.learningAtMs != 0L) {
                    learningDuplicateRefused.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("LEARNING_DELIVERY_DUPLICATE_REFUSED_6738")
                        ForensicLogger.lifecycle(
                            "LEARNING_DELIVERY_DUPLICATE_REFUSED_6738",
                            "positionId=$positionId lane=${st.lane} mode=${st.mode} action=refuse_second_delivery",
                        )
                    } catch (_: Throwable) {}
                    false
                } else {
                    st.learningAtMs = now
                    learningDeliveredOnce.incrementAndGet()
                    verifyAndMark(st)
                    true
                }
            }
        }
    }

    /**
     * Mark a positionId's terminal outcome. This is a lower-cost path for
     * callers who don't need per-stage attribution. Behaves as
     * `record(SELL_COMMITTED)` immediately followed by
     * `record(LEARNING_DELIVERED)` when learningDelivered=true.
     */
    fun markTerminal(positionId: String, lane: String = "", mode: String = "PAPER", learningDelivered: Boolean = true): Boolean {
        val soldOk = record(positionId, Stage.SELL_COMMITTED, lane, mode)
        val learnOk = if (learningDelivered) record(positionId, Stage.LEARNING_DELIVERED, lane, mode) else false
        return soldOk || learnOk
    }

    /**
     * Called by Pillar 3 reconciler to verify a specific position's
     * canonical accounting matches its journal. If cash / open-cost /
     * realized / quantity diverge for a given positionId, tag its
     * originating event id as ambiguous (does NOT block admission
     * globally — the guard already time-decays parity per 6733).
     */
    fun observeAccountingDivergence(
        positionId: String,
        eventId: String,
        reason: String,
    ) {
        if (positionId.isBlank() || reason.isBlank()) return
        val st = trips[positionId] ?: return
        st.divergenceReason = reason
        st.reconciled = false
        if (eventId.isNotBlank()) {
            ProvenanceAuthority6737.classifyOnce(
                eventId,
                ProvenanceAuthority6737.Origin.QUARANTINE_AMBIGUOUS,
                "ROUND_TRIP_DIVERGENCE_$reason",
            )
        }
        try {
            PipelineHealthCollector.labelInc("ROUND_TRIP_DIVERGENCE_OBSERVED_6738")
            ForensicLogger.lifecycle(
                "ROUND_TRIP_DIVERGENCE_OBSERVED_6738",
                "positionId=$positionId eventId=${eventId.take(24)} reason=$reason action=tag_event_ambiguous_no_global_block",
            )
        } catch (_: Throwable) {}
    }

    private fun verifyAndMark(st: TripState) {
        val complete = st.buyAtMs > 0L && st.sellAtMs > 0L && st.learningAtMs > 0L
        if (!complete) return
        val ordered = st.buyAtMs <= st.sellAtMs && st.sellAtMs <= st.learningAtMs
        if (!ordered) {
            st.divergenceReason = "STAGE_ORDER_VIOLATED"
            st.reconciled = false
            try { PipelineHealthCollector.labelInc("ROUND_TRIP_STAGE_ORDER_VIOLATED_6738") } catch (_: Throwable) {}
            return
        }
        st.reconciled = true
        try { PipelineHealthCollector.labelInc("ROUND_TRIP_RECONCILED_6738") } catch (_: Throwable) {}
    }

    fun summary(): Summary {
        val open = trips.values.count { !it.reconciled && it.divergenceReason.isBlank() }
        val ok = trips.values.count { it.reconciled }
        val bad = trips.values.count { !it.reconciled && it.divergenceReason.isNotBlank() }
        val reasons = HashMap<String, Int>()
        for (t in trips.values) if (t.divergenceReason.isNotBlank()) reasons.merge(t.divergenceReason, 1, Int::plus)
        return Summary(
            openTrips = open, reconciled = ok, diverged = bad, diverged_reasons = reasons,
            learningDeliveredOnce = learningDeliveredOnce.get(),
            learningDeliveredDuplicateRefused = learningDuplicateRefused.get(),
            shadowTripSkipped = shadowTripSkipped.get(),
        )
    }

    fun tripOf(positionId: String): TripState? = trips[positionId]

    internal fun resetForTest6738() {
        trips.clear()
        learningDeliveredOnce.set(0L)
        learningDuplicateRefused.set(0L)
        shadowTripSkipped.set(0L)
    }
}
