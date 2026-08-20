package com.lifecyclebot.engine.truth

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6472 §P0.1 — CANONICAL INSTANCE IDENTITY.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Create exactly ONE process-scoped canonical PositionAuthority for
 *    PAPER/LIVE. All registry, occupancy, dashboard, slot-health, exit,
 *    replay and learning views must be projections of it — never
 *    independent mutable authorities.
 *
 *    Every component exposes instanceId/runId/epoch in diagnostics.
 *    Acceptance requires all canonical consumers to report the same
 *    authority instance."
 *
 * DESIGN
 * ──────
 *   • `instanceId`  — random UUID string. Same for the entire process
 *                     lifetime. Diagnostic sections stamp their reports
 *                     with this so an operator can prove two report
 *                     panels are looking at the same in-memory object.
 *   • `runId`       — assigned by the operator when a paper session
 *                     starts. Bumps on operator restart.
 *   • `epoch`       — monotonic counter; every canonical reset bumps it.
 *
 * TELEMETRY
 *   Emits `CANONICAL_INSTANCE_ID_STAMP_6472_<component>` when a
 *   component reads the identity — makes it easy to prove multiple
 *   consumer reports quote the same triple.
 */
object CanonicalInstanceIdentity6472 {

    private val instanceIdImpl = UUID.randomUUID().toString()
    private val runIdImpl = java.util.concurrent.atomic.AtomicReference("run-init")
    private val epochImpl = AtomicLong(1L)
    private val stampReads = AtomicLong(0L)

    fun instanceId(): String = instanceIdImpl
    fun runId(): String = runIdImpl.get()
    fun epoch(): Long = epochImpl.get()

    fun setRunId(newRunId: String) {
        if (newRunId.isNotBlank()) runIdImpl.set(newRunId)
    }

    fun bumpEpoch(): Long = epochImpl.incrementAndGet()

    /**
     * Read the identity triple. Callers include their component name
     * so an operator can see which report panels are stamping which
     * identity. Returns a formatted "instanceId=… runId=… epoch=…"
     * that can be appended to any diagnostic line.
     */
    fun stamp(component: String): String {
        stampReads.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                "CANONICAL_INSTANCE_ID_STAMP_6472_${component}".take(60)
            )
        } catch (_: Throwable) {}
        return "instanceId=${instanceIdImpl.take(8)} runId=${runIdImpl.get()} epoch=${epochImpl.get()}"
    }

    fun statusLine(): String =
        "instanceId=${instanceIdImpl.take(8)} runId=${runIdImpl.get()} " +
            "epoch=${epochImpl.get()} stampReads=${stampReads.get()}"

    /**
     * Test-only reset — cycles the epoch and runId. Does NOT change
     * the process instanceId (which cannot change until process restart).
     */
    internal fun resetForTest() {
        runIdImpl.set("run-test")
        epochImpl.set(1L)
        stampReads.set(0L)
    }
}
