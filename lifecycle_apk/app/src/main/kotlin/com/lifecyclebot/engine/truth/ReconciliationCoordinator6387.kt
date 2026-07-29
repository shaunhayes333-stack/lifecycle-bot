package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6387 — SINGLE RECONCILIATION COORDINATOR (Directive A P0).
 *
 * One lifecycle-scoped coordinator, one active job per runtime generation.
 * Every op is idempotent by (wallet snapshot slot, position version).
 */
object ReconciliationCoordinator6387 {

    enum class Phase { FETCH, CLASSIFY, ATTACH_PENDING, RECONCILE, RECOVER_ORPHANS, PROVE_CLOSED, EMIT, COMMIT, PUBLISH, IDLE }

    private data class ActiveJob(val id: String, val startedAtMs: Long, val walletSnapshotSlot: Long)
    private val active = AtomicReference<ActiveJob?>(null)
    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var activeJobs: Int = 0

    fun activeJobsCount(): Int = activeJobs
    fun currentPhase(): Phase = phase

    /**
     * Try to start a new job. Returns false if one is already running for
     * this wallet snapshot (idempotency: reprocessing same slot no-ops).
     */
    fun tryBegin(walletSnapshotSlot: Long): String? {
        val prior = active.get()
        if (prior != null) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RECONCILIATION_DUPLICATE_JOB_REJECTED_6387") } catch (_: Throwable) {}
            return null
        }
        val job = ActiveJob(id = "recon_${walletSnapshotSlot}_${System.nanoTime()}", startedAtMs = System.currentTimeMillis(), walletSnapshotSlot = walletSnapshotSlot)
        if (!active.compareAndSet(null, job)) return null
        activeJobs = 1
        phase = Phase.FETCH
        return job.id
    }

    fun advance(newPhase: Phase) { phase = newPhase }

    fun end(cleanCycle: Boolean, invariantFailReason: String? = null) {
        active.set(null)
        activeJobs = 0
        phase = Phase.IDLE
        if (cleanCycle) {
            CanonicalLedgerParityHold6387.onCleanCycle()
        } else if (invariantFailReason != null) {
            CanonicalLedgerParityHold6387.onInvariantFailure(invariantFailReason)
        }
    }

    internal fun resetForTest() { active.set(null); activeJobs = 0; phase = Phase.IDLE }
}
