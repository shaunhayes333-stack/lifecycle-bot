package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6402 §C — UNIVERSAL SL LEASE REGISTRY (start/done invariant).
 *
 * OBSERVED FAILURE (6401 snapshot)
 * ─────────────────────────────────
 * `Universal SL start/done: 7/6` — one orphaned start with no
 * matching done. Acceptance test N.1 requires start==done after
 * every sweep completes; N.2 requires zero leases older than 10s.
 *
 * DESIGN
 * ──────
 * Every Universal SL sweep must:
 *   1. Call [acquire] BEFORE emitting the START event, receiving
 *      an opaque sweepId + timestamp.
 *   2. Emit `EXIT_COORDINATOR_UNIVERSAL_START` inside try{...}.
 *   3. Emit `EXIT_COORDINATOR_UNIVERSAL_DONE` inside finally{...}.
 *   4. Call [release] in the same finally block.
 *
 * Any lease older than [STALE_LEASE_TTL_MS] is reaped by
 * [reapStaleLeases] and emits `UNIVERSAL_SL_STALE_LEASE_RESET_6402`.
 * The reap only releases the ORCHESTRATION lease; it does not
 * manufacture sell finality or alter position balances.
 */
object UniversalSlLeaseRegistry6402 {

    /** Directive N.2: any lease older than 10s is stale. */
    const val STALE_LEASE_TTL_MS: Long = 10_000L

    private val nextSweepId = AtomicLong(0L)
    /** sweepId → startedMonoMs */
    private val activeLeases = ConcurrentHashMap<Long, Long>()

    /** Acquire a fresh lease. Caller MUST release in finally. */
    fun acquire(nowMs: Long = System.currentTimeMillis()): Long {
        val id = nextSweepId.incrementAndGet()
        activeLeases[id] = nowMs
        return id
    }

    /**
     * Release a lease. Idempotent — releasing an already-released
     * or unknown id is a no-op (does not throw). Returns true iff a
     * live lease was released.
     */
    fun release(sweepId: Long): Boolean {
        return activeLeases.remove(sweepId) != null
    }

    /**
     * Reap leases older than [STALE_LEASE_TTL_MS]. Emits
     * `UNIVERSAL_SL_STALE_LEASE_RESET_6402` for each reaped lease.
     * Returns the number of leases reaped.
     */
    fun reapStaleLeases(nowMs: Long = System.currentTimeMillis()): Int {
        var reaped = 0
        val iter = activeLeases.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val ageMs = nowMs - e.value
            if (ageMs >= STALE_LEASE_TTL_MS) {
                iter.remove()
                reaped++
                try {
                    PipelineHealthCollector.labelInc("UNIVERSAL_SL_STALE_LEASE_RESET_6402")
                    ForensicLogger.lifecycle(
                        "UNIVERSAL_SL_STALE_LEASE_RESET_6402",
                        "sweepId=${e.key} ageMs=$ageMs",
                    )
                } catch (_: Throwable) {}
            }
        }
        return reaped
    }

    /** Observability — number of currently active leases. */
    fun activeLeaseCount(): Int = activeLeases.size

    /** Observability — age (ms) of the oldest active lease, or -1 if none. */
    fun oldestLeaseAgeMs(nowMs: Long = System.currentTimeMillis()): Long {
        var oldest = -1L
        for (started in activeLeases.values) {
            val age = nowMs - started
            if (age > oldest) oldest = age
        }
        return oldest
    }

    internal fun clearAllForTest() {
        activeLeases.clear()
        nextSweepId.set(0L)
    }
}
