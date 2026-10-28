package com.lifecyclebot.engine

/**
 * V5.0.8418 §THE_TIMEOUTS_WERE_WORKERS_WAITING_FOR_A_THREAD.
 *
 * 5.0.8416 (3.7 h live): workerTimeout=908, SUPERVISOR_LEASE_FORCE_RELEASED=807, expiredLeases=113. The supervisor
 * admitted up to 48 leases a cycle (SUPERVISOR_BASE_MAX_WORKERS) onto AATE-Entry-6647, a fixed pool of SIXTEEN threads
 * that the entry-hydration cascade (ParallelMarkFanout7088 + the provider chain, seconds of blocking IO each) also ran
 * on. Up to 32 admitted workers therefore sat in the pool's queue, while their 15.75 s safety-net watchdog and their
 * 18 s lease TTL were both counted from ADMISSION. A worker that waited ~10 s for a thread had ~5 s to do a 3-5 s
 * processTokenCycle; the rest were cancelled and counted as "worker timeouts" (most of them never ran a line), the lease
 * prune expired the ones that had not reported progress, and the per-mint 90 s timeout cooldown benched healthy mints.
 *
 * Three rules, all pure here:
 *  1. [admits8418] — a lease is admitted only when a pool thread can run it: under the adaptive cap AND under the pool
 *     size, with no backlog already queued (threads still wedged in non-interruptible IO after a force release hold a
 *     thread without a lease; the busy+queued count sees them).
 *  2. [watchdogWaitMoreMs8418] — the worker's budget runs from the moment it got a thread; a worker that never got one
 *     within [MAX_QUEUE_WAIT_MS_8418] is released as a QUEUE expiry, not a timeout (no cooldown, no throttle debt).
 *  3. [leaseAgeMs8418] — the lease TTL prune measures age from the worker's start once it has started.
 * No timeout value, cap or cooldown changes; work that genuinely overruns its 15 s still times out exactly as before.
 */
object SupervisorWorkerBudget8418 {
    /** AATE-Entry-6647's size (BotService.supervisorExecutor6647). */
    const val ENTRY_POOL_THREADS_8418 = 16
    /** A queued worker that has not started within this long is released (queue expiry). */
    const val MAX_QUEUE_WAIT_MS_8418 = 15_000L

    /**
     * Pure: may another supervisor lease be admitted? [liveLeases] held now, [effectiveCap] the adaptive cap,
     * [poolThreads] the entry pool's size, [busyThreads] threads running a task, [queued] tasks waiting for a thread.
     */
    fun admits8418(liveLeases: Int, effectiveCap: Int, poolThreads: Int, busyThreads: Int, queued: Int): Boolean {
        val threads = if (poolThreads > 0) poolThreads else ENTRY_POOL_THREADS_8418
        if (liveLeases >= minOf(effectiveCap, threads)) return false
        return busyThreads.coerceAtLeast(0) + queued.coerceAtLeast(0) < threads
    }

    /**
     * Pure: how much longer the safety-net watchdog must wait before it may cancel the worker. [workerStartedAtMs] is 0
     * while the worker is still queued. 0 = due now.
     */
    fun watchdogWaitMoreMs8418(nowMs: Long, spawnedAtMs: Long, workerStartedAtMs: Long, budgetMs: Long, maxQueueWaitMs: Long): Long =
        if (workerStartedAtMs > 0L) (workerStartedAtMs + budgetMs - nowMs).coerceAtLeast(0L)
        else (spawnedAtMs + maxQueueWaitMs - nowMs).coerceAtLeast(0L)

    /** Pure: a lease's age for the TTL prune — from the worker's start once it has one, else from admission. */
    fun leaseAgeMs8418(nowMs: Long, admittedAtMs: Long, workerStartedAtMs: Long): Long =
        (nowMs - (if (workerStartedAtMs > 0L) workerStartedAtMs else admittedAtMs)).coerceAtLeast(0L)

    /** Pure: a watchdog release of a worker that never started is a queue expiry, not a worker timeout. */
    fun isQueueExpiry8418(workerStartedAtMs: Long): Boolean = workerStartedAtMs <= 0L
}
