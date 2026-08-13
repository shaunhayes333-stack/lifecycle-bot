package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6441 §11 — STARTUP / RECOVERY INVARIANT GATE.
 *
 * OPERATOR MANDATE §11:
 *   "On restart:
 *      - reconstruct canonical open positions before scanner entry
 *        authority starts.
 *      - rebuild same-mint ownership, quantities, cost basis and slot
 *        counts.
 *      - replay terminal/idempotency state before accepting duplicate
 *        callbacks.
 *      - learner receives no outcome until reconstruction/reconciliation
 *        completes.
 *      - stale registry/cache objects cannot resurrect CLOSED positions."
 *
 * DESIGN
 * ──────
 * A single-flight gate. On startup, the boot sequence calls:
 *   markReconstructing()  → gate CLOSED
 *   [reconstruct canonical state]
 *   markReconstructionComplete() → gate OPEN
 *
 * Any scanner / learner / entry-authority call checks
 * gateOpen() before proceeding. If the gate is CLOSED, the caller
 * must defer.
 *
 * A hard timeout (RECONSTRUCTION_TIMEOUT_MS) auto-opens the gate to
 * prevent a bug from halting the bot forever, but the timeout emits
 * `STARTUP_INVARIANT_TIMEOUT_6441` so the operator knows.
 */
object StartupInvariantGate6441 {

    private const val RECONSTRUCTION_TIMEOUT_MS = 30_000L

    private val gateOpen = AtomicReference<Boolean>(true)  // default OPEN so first boot before wire is unaffected
    private val reconstructingSinceMs = AtomicLong(0L)
    private val timesOpened = AtomicLong(0L)
    private val timesClosed = AtomicLong(0L)
    private val autoOpenTimeouts = AtomicLong(0L)

    fun markReconstructing() {
        gateOpen.set(false)
        reconstructingSinceMs.set(System.currentTimeMillis())
        timesClosed.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "STARTUP_INVARIANT_RECONSTRUCTING_6441",
                "gate=CLOSED — scanner + learner outcome ingestion deferred",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("STARTUP_INVARIANT_RECONSTRUCTING_6441") } catch (_: Throwable) {}
    }

    fun markReconstructionComplete() {
        gateOpen.set(true)
        val took = System.currentTimeMillis() - reconstructingSinceMs.get()
        reconstructingSinceMs.set(0L)
        timesOpened.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "STARTUP_INVARIANT_OPEN_6441",
                "gate=OPEN reconstructionMs=$took",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("STARTUP_INVARIANT_OPEN_6441") } catch (_: Throwable) {}
    }

    /** True if a caller may proceed with scanner / learner ingestion. */
    fun gateOpen(): Boolean {
        val since = reconstructingSinceMs.get()
        if (!gateOpen.get() && since > 0L &&
            (System.currentTimeMillis() - since) > RECONSTRUCTION_TIMEOUT_MS) {
            gateOpen.set(true)
            autoOpenTimeouts.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "STARTUP_INVARIANT_TIMEOUT_6441",
                    "reconstructionExceeded=${RECONSTRUCTION_TIMEOUT_MS}ms — auto-opening gate",
                )
            } catch (_: Throwable) {}
        }
        return gateOpen.get()
    }

    fun statusLine(): String {
        val since = reconstructingSinceMs.get()
        val ageMs = if (since <= 0L) -1L else System.currentTimeMillis() - since
        return "gateOpen=${gateOpen.get()} reconstructingAgeMs=$ageMs " +
            "opens=${timesOpened.get()} closes=${timesClosed.get()} timeouts=${autoOpenTimeouts.get()}"
    }
}
