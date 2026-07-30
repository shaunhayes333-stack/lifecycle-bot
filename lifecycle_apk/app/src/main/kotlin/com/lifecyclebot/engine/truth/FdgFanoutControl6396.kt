package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6396 — FDG FANOUT CONTROL.
 *
 * Exactly one active primary lane may produce an executable FDG decision
 * for a (mint, metricEpoch). Shadow / read-only lanes may calculate and
 * journal their opinions but MUST NOT:
 *   - invoke FDG on the same mint
 *   - create execution tickets
 *   - acquire leases
 *   - create BUY_FAILED events
 *   - independently enqueue the same mint
 *
 * Deduplicate FDG by:
 *   mint + metricEpoch + primaryLane + decisionClass
 *
 * A repeated identical decision must reuse the existing decisionId.
 *
 * Hard diagnostic targets:
 *   FDG decisions / intake                     <= 1.75 steady state
 *   EXEC invocations / unique executable mint  <= 1.25
 *   score-floor executor failures              == 0
 *   unchanged score-floor retries per mint     == 0
 */
object FdgFanoutControl6396 {

    const val FDG_PER_INTAKE_TARGET: Double = 1.75
    const val EXEC_PER_UNIQUE_MINT_TARGET: Double = 1.25

    data class FdgKey(val mint: String, val metricEpoch: Long,
                      val primaryLane: String, val decisionClass: String)

    data class FdgDecision(val key: FdgKey, val decisionId: String, val createdAtMs: Long)

    private val active = ConcurrentHashMap<FdgKey, FdgDecision>()

    val decisionsIssued = AtomicLong(0L)
    val decisionsReused = AtomicLong(0L)
    val shadowLaneAttemptsBlocked = AtomicLong(0L)
    val intakeSampled = AtomicLong(0L)
    val execInvocations = AtomicLong(0L)

    /**
     * Returns the canonical decisionId. If the same (mint, epoch, primary
     * lane, class) has already produced a decision, the existing one is
     * reused — no fresh decisionId is minted (§"DETERMINISTIC ... FDG").
     */
    @Synchronized
    fun issueOrReuse(key: FdgKey, freshDecisionIdFactory: () -> String): FdgDecision {
        val existing = active[key]
        if (existing != null) {
            decisionsReused.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FDG_DECISION_REUSED_6396") } catch (_: Throwable) {}
            return existing
        }
        val newDecision = FdgDecision(key, freshDecisionIdFactory(), System.currentTimeMillis())
        active[key] = newDecision
        decisionsIssued.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FDG_DECISION_ISSUED_6396") } catch (_: Throwable) {}
        return newDecision
    }

    /**
     * Shadow/read-only lanes MUST call this before attempting anything that
     * would create side effects for the same mint. Returns false so the
     * caller can journal-only and bail. Increments shadow-blocked counter.
     */
    fun canShadowLaneExecute(mint: String): Boolean {
        shadowLaneAttemptsBlocked.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FDG_SHADOW_LANE_ATTEMPT_BLOCKED_6396") } catch (_: Throwable) {}
        return false
    }

    fun recordIntakeSample() { intakeSampled.incrementAndGet() }
    fun recordExecInvocation() { execInvocations.incrementAndGet() }

    fun fanoutRatioFdgPerIntake(): Double {
        val i = intakeSampled.get()
        return if (i == 0L) 0.0 else decisionsIssued.get().toDouble() / i.toDouble()
    }

    /**
     * Local dedupe / queue compaction pressure: caller can consult this
     * to trigger cycle-budget protection WITHOUT globally holding trading
     * (§"FDG FANOUT CONTROL" — no global HOLD purely on fanout).
     */
    fun fanoutOverBudget(): Boolean = fanoutRatioFdgPerIntake() > FDG_PER_INTAKE_TARGET

    /** Release a decision when its position closes (cleanup for the map). */
    fun release(key: FdgKey) { active.remove(key) }

    internal fun clearAllForTest() {
        active.clear()
        decisionsIssued.set(0L); decisionsReused.set(0L)
        shadowLaneAttemptsBlocked.set(0L)
        intakeSampled.set(0L); execInvocations.set(0L)
    }
}
