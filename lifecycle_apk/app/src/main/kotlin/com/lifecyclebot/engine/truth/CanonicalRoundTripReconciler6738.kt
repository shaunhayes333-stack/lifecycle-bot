package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6743 — owner-bound four-stage round-trip verifier.
 *
 * A reconciled terminal trade requires, in order:
 * BUY_COMMITTED -> EXIT_DECIDED -> SELL_COMMITTED -> LEARNING_DELIVERED.
 * Transitions are serialized per positionId. Accounting/stage divergence is
 * sticky: later callbacks may complete telemetry but can never turn a known
 * divergent trip back into a reconciled one.
 *
 * Observational only: never mutates capital, lots, risk, or execution state.
 */
object CanonicalRoundTripReconciler6738 {
    enum class Stage { BUY_COMMITTED, EXIT_DECIDED, SELL_COMMITTED, LEARNING_DELIVERED }

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
        val st = trips.computeIfAbsent(positionId) { TripState(positionId, lane, mode) }
        val now = System.currentTimeMillis()
        synchronized(st) {
            fun diverge(reason: String) {
                if (st.divergenceReason.isBlank()) st.divergenceReason = reason
                st.reconciled = false
                try {
                    PipelineHealthCollector.labelInc("ROUND_TRIP_STAGE_DIVERGENCE_6743")
                    ForensicLogger.lifecycle(
                        "ROUND_TRIP_STAGE_DIVERGENCE_6743",
                        "positionId=$positionId stage=$stage lane=${st.lane} mode=${st.mode} reason=${st.divergenceReason}",
                    )
                } catch (_: Throwable) {}
            }

            when (stage) {
                Stage.BUY_COMMITTED -> {
                    if (st.buyAtMs != 0L) return false
                    st.buyAtMs = now
                    if (st.exitAtMs != 0L || st.sellAtMs != 0L || st.learningAtMs != 0L) diverge("BUY_AFTER_LATER_STAGE")
                }
                Stage.EXIT_DECIDED -> {
                    if (st.exitAtMs != 0L) return false
                    st.exitAtMs = now
                    if (st.buyAtMs == 0L) diverge("EXIT_WITHOUT_BUY")
                    if (st.sellAtMs != 0L || st.learningAtMs != 0L) diverge("EXIT_AFTER_TERMINAL_STAGE")
                }
                Stage.SELL_COMMITTED -> {
                    if (st.sellAtMs != 0L) return false
                    st.sellAtMs = now
                    if (st.buyAtMs == 0L) diverge("SELL_WITHOUT_BUY")
                    else if (st.exitAtMs == 0L) diverge("SELL_WITHOUT_EXIT_DECISION")
                    if (st.learningAtMs != 0L) diverge("SELL_AFTER_LEARNING")
                }
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
                        return false
                    }
                    st.learningAtMs = now
                    learningDeliveredOnce.incrementAndGet()
                    if (st.buyAtMs == 0L) diverge("LEARNING_WITHOUT_BUY")
                    else if (st.exitAtMs == 0L) diverge("LEARNING_WITHOUT_EXIT_DECISION")
                    else if (st.sellAtMs == 0L) diverge("LEARNING_WITHOUT_SELL")
                }
            }
            verifyAndMarkLocked6743(st)
            return true
        }
    }

    fun markTerminal(
        positionId: String,
        lane: String = "",
        mode: String = "PAPER",
        learningDelivered: Boolean = true,
    ): Boolean {
        val exitOk = record(positionId, Stage.EXIT_DECIDED, lane, mode)
        val soldOk = record(positionId, Stage.SELL_COMMITTED, lane, mode)
        val learnOk = if (learningDelivered) record(positionId, Stage.LEARNING_DELIVERED, lane, mode) else false
        return exitOk || soldOk || learnOk
    }

    fun observeAccountingDivergence(positionId: String, eventId: String, reason: String) {
        if (positionId.isBlank() || reason.isBlank()) return
        val st = trips[positionId] ?: return
        synchronized(st) {
            if (st.divergenceReason.isBlank()) st.divergenceReason = reason
            st.reconciled = false
        }
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
                "positionId=$positionId eventId=${eventId.take(24)} reason=$reason action=sticky_divergence_tag_event_ambiguous",
            )
        } catch (_: Throwable) {}
    }

    private fun verifyAndMarkLocked6743(st: TripState) {
        // Known divergence is sticky. A later callback cannot erase it.
        if (st.divergenceReason.isNotBlank()) {
            st.reconciled = false
            return
        }
        val complete = st.buyAtMs > 0L && st.exitAtMs > 0L && st.sellAtMs > 0L && st.learningAtMs > 0L
        if (!complete) return
        val ordered = st.buyAtMs <= st.exitAtMs && st.exitAtMs <= st.sellAtMs && st.sellAtMs <= st.learningAtMs
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
            openTrips = open,
            reconciled = ok,
            diverged = bad,
            diverged_reasons = reasons,
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
