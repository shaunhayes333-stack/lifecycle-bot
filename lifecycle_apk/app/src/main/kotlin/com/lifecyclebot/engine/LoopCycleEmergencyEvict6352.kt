package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6352 — LOOP-CYCLE EMERGENCY EVICT.
 *
 * OPERATOR SYMPTOM (V5.0.6349 snapshot)
 *   avg cycle ms = 8480, max cycle ms = 33955, status
 *   "🛑 cycles > 30s indicate watchlist or scanner overload"
 *   BOT_LOOP_CYCLE_OVERRUN was already being logged, but no work was shed,
 *   so cycles kept climbing.
 *
 * FIX PHILOSOPHY (soft-shape, never block)
 *   When a single bot-loop cycle exceeds [EMERGENCY_CYCLE_MS], the loop
 *   is telling us the scanner or watchlist is chewing through too many
 *   candidates. Shed the excess:
 *     • Drop every HYDRATING row from [ScannerHydrationQueues6347] —
 *       hydration is the bucket most likely to accumulate silent stalls.
 *     • Purge REJECTED_WITH_TTL rows whose TTL has elapsed so the router
 *       is not carrying dead weight.
 *     • Emit LOOP_CYCLE_EMERGENCY_EVICT_6352 telemetry so the operator
 *       sees the shed happen in the next Pipeline Health Snapshot.
 *   Never pauses the loop, never disables a lane, never blocks a trade —
 *   same soft-shape philosophy that unlocked V5.0.6341 SAFETY_STALE.
 */
object LoopCycleEmergencyEvict6352 {

    /** Cycle-time floor that triggers an emergency shed. 20s matches the
     *  existing BOT_LOOP_CYCLE_OVERRUN threshold; anything above this is
     *  already labelled overrun. */
    private const val EMERGENCY_CYCLE_MS: Long = 20_000L

    /** Cool-down between two consecutive shed events. Prevents the router
     *  from being drained twice for a single overrun spike. */
    private const val COOLDOWN_MS: Long = 30_000L

    private val lastShedAtMs = AtomicLong(0L)
    private val shedCount = AtomicLong(0L)

    /**
     * Called from the bot loop right after BOT_LOOP_CYCLE_OVERRUN is logged.
     * Returns true if a shed actually happened (used by callers for extra
     * telemetry / assertion coverage).
     */
    fun onCycleOverrun(prevCycleMs: Long): Boolean {
        if (prevCycleMs < EMERGENCY_CYCLE_MS) return false
        val now = System.currentTimeMillis()
        val last = lastShedAtMs.get()
        if (now - last < COOLDOWN_MS) {
            try { PipelineHealthCollector.labelInc("LOOP_CYCLE_EMERGENCY_EVICT_COOLDOWN_6352") } catch (_: Throwable) {}
            return false
        }
        if (!lastShedAtMs.compareAndSet(last, now)) return false

        var hydratingDropped = 0
        try {
            val drained = ScannerHydrationQueues6347.drain(
                bucket = ScannerHydrationQueues6347.Bucket.HYDRATING,
                max = Int.MAX_VALUE,
            )
            hydratingDropped = drained.size
        } catch (_: Throwable) {}

        // Rejected-with-TTL rows whose TTL has already elapsed will be
        // returned by drain() naturally; do the same clean-up here so the
        // router carries fewer dead rows into the next cycle.
        var rejectedDropped = 0
        try {
            val drained = ScannerHydrationQueues6347.drain(
                bucket = ScannerHydrationQueues6347.Bucket.REJECTED_WITH_TTL,
                max = Int.MAX_VALUE,
            )
            rejectedDropped = drained.size
        } catch (_: Throwable) {}

        shedCount.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("LOOP_CYCLE_EMERGENCY_EVICT_6352")
            ForensicLogger.lifecycle(
                "LOOP_CYCLE_EMERGENCY_EVICT_6352",
                "prevCycleMs=$prevCycleMs hydratingDropped=$hydratingDropped " +
                    "rejectedTtlDropped=$rejectedDropped shedCount=${shedCount.get()} " +
                    "action=shed_router_backlog_to_recover_cadence",
            )
        } catch (_: Throwable) {}
        return true
    }

    fun totalShedCount(): Long = shedCount.get()

    internal fun resetForTest() {
        lastShedAtMs.set(0L)
        shedCount.set(0L)
    }
}
