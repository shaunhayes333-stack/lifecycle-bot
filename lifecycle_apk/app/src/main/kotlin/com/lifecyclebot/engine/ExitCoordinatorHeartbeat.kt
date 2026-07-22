package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6324 — EXIT COORDINATOR HEARTBEAT + GENERATION MODEL
 * (operator hotfix §10).
 *
 * Root-fixes 17 stale resets in 12.5 minutes: an active exit coordinator
 * with a fresh heartbeat and live workers can no longer be reset merely
 * because one external API call is slow.
 *
 * Ownership model:
 *   * unique [sweepId]
 *   * owner token (opaque string)
 *   * monotonic start time
 *   * heartbeat timestamp (refreshed by workers)
 *   * explicit phase
 *   * active worker count
 *   * cancellation state
 *   * generation number (older generations cannot complete newer state)
 */
object ExitCoordinatorHeartbeat {

    enum class Phase {
        WALLET_REFRESH,
        PRICE_REFRESH,
        POSITION_EVALUATION,
        SELL_PREPARATION,
        TX_BROADCAST,
        FINALITY_WAIT,
        RECONCILIATION,
        IDLE,
    }

    /** Per-phase deadlines. Broad "one big timeout" is what caused the
     *  17 stale resets — each phase has its own realistic budget. */
    private val PHASE_DEADLINE_MS: Map<Phase, Long> = mapOf(
        Phase.WALLET_REFRESH to 8_000L,
        Phase.PRICE_REFRESH to 5_000L,
        Phase.POSITION_EVALUATION to 4_000L,
        Phase.SELL_PREPARATION to 12_000L,
        Phase.TX_BROADCAST to 30_000L,
        Phase.FINALITY_WAIT to 60_000L,
        Phase.RECONCILIATION to 45_000L,
        Phase.IDLE to 300_000L,
    )
    private const val HEARTBEAT_STALE_MS: Long = 20_000L

    data class State(
        val sweepId: String,
        val owner: String,
        val startedAtMs: Long,
        val heartbeatAtMs: Long,
        val phase: Phase,
        val activeWorkers: Int,
        val cancelled: Boolean,
        val generation: Int,
    )

    private val generationCounter = AtomicInteger(0)
    private val staleResets = AtomicLong(0L)
    private val justifiedResets = AtomicLong(0L)
    private val falseResetsPrevented = AtomicLong(0L)
    private val duplicateSweepsSuppressed = AtomicLong(0L)
    private val states = ConcurrentHashMap<String, State>()

    /** Start a sweep for a mint. If one is already active and healthy,
     *  the duplicate start is suppressed and the existing owner is
     *  returned. */
    fun startSweep(mint: String, owner: String, phase: Phase = Phase.POSITION_EVALUATION): State {
        val now = System.currentTimeMillis()
        val existing = states[mint]
        if (existing != null && !existing.cancelled) {
            val elapsed = now - existing.heartbeatAtMs
            val phaseDeadline = PHASE_DEADLINE_MS[existing.phase] ?: 30_000L
            if (elapsed < HEARTBEAT_STALE_MS && elapsed < phaseDeadline && existing.activeWorkers > 0) {
                duplicateSweepsSuppressed.incrementAndGet()
                try { PipelineHealthCollector.labelInc("EXIT_COORDINATOR_DUPLICATE_START_SUPPRESSED_6324") } catch (_: Throwable) {}
                return existing
            }
        }
        val gen = generationCounter.incrementAndGet()
        val s = State(
            sweepId = "sweep-$gen-$now",
            owner = owner,
            startedAtMs = now,
            heartbeatAtMs = now,
            phase = phase,
            activeWorkers = 1,
            cancelled = false,
            generation = gen,
        )
        states[mint] = s
        try {
            ForensicLogger.lifecycle(
                "EXIT_COORDINATOR_HEARTBEAT_6324",
                "event=START mint=${mint.take(10)} sweepId=${s.sweepId} owner=$owner phase=$phase generation=$gen activeWorkers=1",
            )
            PipelineHealthCollector.labelInc("EXIT_COORDINATOR_STARTED_6324")
        } catch (_: Throwable) {}
        return s
    }

    fun heartbeat(mint: String, owner: String, phase: Phase, activeWorkers: Int = 1) {
        val cur = states[mint] ?: return
        if (cur.owner != owner) {
            // Older-generation heartbeat — reject.
            try {
                ForensicLogger.lifecycle(
                    "EXIT_COORDINATOR_GENERATION_REJECT_6324",
                    "mint=${mint.take(10)} incomingOwner=$owner currentOwner=${cur.owner} action=IGNORE_HEARTBEAT",
                )
                PipelineHealthCollector.labelInc("EXIT_COORDINATOR_GENERATION_REJECT_6324")
            } catch (_: Throwable) {}
            return
        }
        states[mint] = cur.copy(
            heartbeatAtMs = System.currentTimeMillis(),
            phase = phase,
            activeWorkers = activeWorkers,
        )
    }

    /**
     * Ask whether the coordinator for [mint] should be stale-reset. The
     * answer is NEVER yes when a live worker with fresh heartbeat is
     * still inside its per-phase deadline. Returns true only when:
     *   • no heartbeat within HEARTBEAT_STALE_MS AND
     *   • activeWorkers == 0 AND
     *   • phase deadline exceeded
     */
    fun shouldStaleReset(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cur = states[mint] ?: return false
        val heartbeatAge = nowMs - cur.heartbeatAtMs
        val phaseDeadline = PHASE_DEADLINE_MS[cur.phase] ?: 30_000L
        val phaseAge = nowMs - cur.startedAtMs
        val stale = heartbeatAge > HEARTBEAT_STALE_MS && cur.activeWorkers <= 0 && phaseAge > phaseDeadline
        if (!stale) {
            falseResetsPrevented.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXIT_COORDINATOR_STALE_DIAG_6324",
                    "mint=${mint.take(10)} sweepId=${cur.sweepId} phase=${cur.phase} heartbeatAgeMs=$heartbeatAge activeWorkers=${cur.activeWorkers} phaseAgeMs=$phaseAge phaseDeadlineMs=$phaseDeadline action=NO_RESET_STILL_HEALTHY",
                )
                PipelineHealthCollector.labelInc("EXIT_COORDINATOR_FALSE_RESET_PREVENTED_6324")
            } catch (_: Throwable) {}
        }
        return stale
    }

    /** Perform the stale reset. Uses the generation counter so any
     *  outstanding worker from the prior generation is fenced off. */
    fun staleReset(mint: String, reason: String): State? {
        val prev = states.remove(mint) ?: return null
        staleResets.incrementAndGet()
        justifiedResets.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "EXIT_COORDINATOR_STALE_RESET_6324",
                "mint=${mint.take(10)} sweepId=${prev.sweepId} phase=${prev.phase} owner=${prev.owner} generation=${prev.generation} reason=$reason",
            )
            PipelineHealthCollector.labelInc("EXIT_COORDINATOR_STALE_RESET_6324")
        } catch (_: Throwable) {}
        return prev
    }

    fun complete(mint: String, owner: String, reason: String = "COMPLETE") {
        val cur = states[mint] ?: return
        if (cur.owner != owner) {
            try {
                ForensicLogger.lifecycle(
                    "EXIT_COORDINATOR_GENERATION_REJECT_6324",
                    "mint=${mint.take(10)} incomingOwner=$owner currentOwner=${cur.owner} action=IGNORE_COMPLETE reason=$reason",
                )
                PipelineHealthCollector.labelInc("EXIT_COORDINATOR_GENERATION_REJECT_6324")
            } catch (_: Throwable) {}
            return
        }
        states.remove(mint)
        try {
            ForensicLogger.lifecycle(
                "EXIT_COORDINATOR_HEARTBEAT_6324",
                "event=COMPLETE mint=${mint.take(10)} sweepId=${cur.sweepId} phase=${cur.phase} reason=$reason",
            )
            PipelineHealthCollector.labelInc("EXIT_COORDINATOR_COMPLETED_6324")
        } catch (_: Throwable) {}
    }

    fun snapshot(): Map<String, State> = states.toMap()
    fun staleResetCount(): Long = staleResets.get()
    fun justifiedResetCount(): Long = justifiedResets.get()
    fun falseResetsPreventedCount(): Long = falseResetsPrevented.get()
    fun duplicateSweepsSuppressedCount(): Long = duplicateSweepsSuppressed.get()
}
