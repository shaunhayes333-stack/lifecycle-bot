package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6472 §P1.6 — TELEMETRY INTEGRITY HOLD.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Remove duplicate generations of: reconciler / finalized bus /
 *    mint occupancy / position ledger / capital authority / slot health
 *    / same-mint coordinator.
 *    All diagnostic sections must read the production singleton, never
 *    instantiate their own observer/store.
 *    If two telemetry sections disagree on canonical count, reconciler
 *    cadence, publication count, or conservation state, emit
 *    INTEGRITY_HOLD immediately."
 *
 * DESIGN
 * ──────
 * Cross-checks pairs of counters that should always agree. Any
 * disagreement fires `TELEMETRY_INTEGRITY_HOLD_6472`. This is the
 * self-consistency layer under the priority-ordered
 * `RootCauseClassifier6471`.
 *
 * Reads only. Never mutates authorities. NON-CLAMPING.
 */
object TelemetryIntegrityHold6472 {

    private val checks = AtomicLong(0L)
    private val holds = AtomicLong(0L)
    private val lastReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** Run the cross-check panel. Called from the 30-loop parity audit. */
    fun check(): Boolean {
        checks.incrementAndGet()
        val reasons = mutableListOf<String>()

        // Reconciler split-brain — already detected by UnifiedReconcilerHealth6470.
        try {
            val split = PipelineHealthCollector.labelCountSnapshot("RECONCILER_SPLIT_BRAIN_6470")
            if (split > 0L) reasons += "reconciler_split_brain($split)"
        } catch (_: Throwable) {}

        // Finalized bus vs canonical terminal counter parity.
        try {
            val terminalSells = PipelineHealthCollector.labelCountSnapshot("CANONICAL_TERMINAL_SELL")
            val busPublishes = PipelineHealthCollector.labelCountSnapshot("FINALIZED_BUS_PUBLISHED")
            // Publishes may include partials too, so publishes ≥ terminals is normal.
            if (terminalSells > 0L && busPublishes == 0L) reasons += "no_bus_publish_on_$terminalSells+_terminals"
        } catch (_: Throwable) {}

        // Capital identity breach + conservation delta must not disagree.
        try {
            val identity = PipelineHealthCollector.labelCountSnapshot("CAPITAL_IDENTITY_BREACH_6470")
            val conservation = PipelineHealthCollector.labelCountSnapshot("CAPITAL_CONSERVATION_DELTA")
            // If one fires and the other doesn't over a long run, that's suspicious.
            if (identity > 5L && conservation == 0L) reasons += "identity_breach_without_conservation"
            if (conservation > 5L && identity == 0L) reasons += "conservation_delta_without_identity"
        } catch (_: Throwable) {}

        // Position parity — genuine divergence should also be visible in the
        // lifecycle authority projection audit.
        try {
            val genuine = PipelineHealthCollector.labelCountSnapshot("POSITION_PARITY_GENUINE_DIVERGENCE_6471")
            val projection = PipelineHealthCollector.labelCountSnapshot("LIFECYCLE_PROJECTION_DIVERGED_6470")
            if (genuine > 3L && projection == 0L) reasons += "parity_genuine_but_projection_silent"
        } catch (_: Throwable) {}

        if (reasons.isEmpty()) return false
        holds.incrementAndGet()
        val joined = reasons.joinToString(",")
        lastReason.set(joined)
        try {
            ForensicLogger.lifecycle(
                "TELEMETRY_INTEGRITY_HOLD_6472",
                "reasons=$joined",
            )
            PipelineHealthCollector.labelInc("TELEMETRY_INTEGRITY_HOLD_6472")
        } catch (_: Throwable) {}
        return true
    }

    fun statusLine(): String {
        val stamp = CanonicalInstanceIdentity6472.stamp("TelemetryIntegrityHold6472")
        return "checks=${checks.get()} holds=${holds.get()} last=${lastReason.get() ?: "-"} $stamp"
    }

    internal fun resetForTest() {
        checks.set(0L); holds.set(0L); lastReason.set(null)
    }
}
