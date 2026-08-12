package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6437 — PRE-SUPERVISOR LEARNING BUDGET GUARD.
 *
 * OPERATOR (V5.0.6436 ANR dump):
 *   Cycles are spiking to 75s with workerTimeout=62. Supervisor is
 *   hard-bounded to 20s, so the overshoot lives in the pre-supervisor
 *   learning fanout — RegimePulse (async, safe), SentienceAutoTune
 *   (SYNC), LabUniverseTick (SYNC — iterates every open position across
 *   6 traders), and ChronicBleederScout.tick (SYNC — DB-backed reprove
 *   scout).
 *
 * PRIOR STATE
 * ────────────
 * V5.0.6421 introduced prevCycleWasSlow6421 → skip learners when prev
 * cycle > 30s. That's REACTIVE: the first slow cycle still happens; the
 * NEXT one is skipped. If the fanout wedges for 50s repeatedly, every
 * OTHER cycle blows the budget.
 *
 * V5.0.6437 GUARD
 * ────────────────
 * Wrap each synchronous learner with runBudgeted(name, budgetMs, block):
 *   • Measures wall-clock elapsed.
 *   • Emits LEARNING_FANOUT_SLOW_6437 forensic + telemetry counter
 *     when a single learner exceeds its per-call budget.
 *   • Global cycle-level fanout budget: if cumulative fanout ms
 *     exceeds CYCLE_FANOUT_BUDGET_MS (5000ms default), skips any
 *     subsequent learner via canRun().
 *   • Tracks worst-offender learner name for the operator dashboard.
 *
 * All budgets are advisory + measurement — we DO NOT hard-cancel the
 * learner mid-call (they're not suspend). The reactive prevCycleWasSlow
 * gate stays as the belt on top of this suspender.
 */
object PreSupervisorBudgetGuard6437 {

    private const val CYCLE_FANOUT_BUDGET_MS = 5_000L
    private const val PER_LEARNER_SLOW_MS = 2_000L

    private val cycleFanoutSpendMs = AtomicLong(0L)
    private val cycleStartMs = AtomicLong(0L)
    private val worstLearnerMs = AtomicLong(0L)
    @Volatile private var worstLearnerName: String = ""
    private val slowCount = AtomicLong(0L)
    private val skippedByCycleBudget = AtomicLong(0L)

    fun beginCycle() {
        cycleFanoutSpendMs.set(0L)
        cycleStartMs.set(System.currentTimeMillis())
    }

    /** True if this learner still has fanout budget remaining in this cycle. */
    fun canRun(): Boolean = cycleFanoutSpendMs.get() < CYCLE_FANOUT_BUDGET_MS

    /**
     * Run a synchronous learner block with wall-clock measurement.
     * Returns true if the block ran; false if the cycle fanout budget
     * was already exhausted (block was skipped).
     */
    inline fun runBudgeted(name: String, block: () -> Unit): Boolean {
        if (!canRun()) {
            noteSkip()
            try { PipelineHealthCollector.labelInc("LEARNING_FANOUT_SKIPPED_6437") } catch (_: Throwable) {}
            return false
        }
        val t0 = System.currentTimeMillis()
        try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - t0
            noteElapsed(name, elapsed)
        }
        return true
    }

    fun noteSkip() {
        skippedByCycleBudget.incrementAndGet()
    }

    fun noteElapsed(name: String, elapsed: Long) {
        val safe = elapsed.coerceAtLeast(0L)
        cycleFanoutSpendMs.addAndGet(safe)
        if (safe > PER_LEARNER_SLOW_MS) {
            slowCount.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LEARNING_FANOUT_SLOW_6437",
                    "name=$name elapsedMs=$safe budgetPerLearnerMs=$PER_LEARNER_SLOW_MS cycleSpendMs=${cycleFanoutSpendMs.get()}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("LEARNING_FANOUT_SLOW_6437") } catch (_: Throwable) {}
        }
        if (safe > worstLearnerMs.get()) {
            worstLearnerMs.set(safe)
            worstLearnerName = name
        }
    }

    fun statusLine(): String {
        val worstMs = worstLearnerMs.get()
        val name = worstLearnerName
        val slow = slowCount.get()
        val skipped = skippedByCycleBudget.get()
        return "worstLearner=$name worstMs=$worstMs slowCount=$slow skipped=$skipped"
    }

    /** For tests only. */
    internal fun clearForTest() {
        cycleFanoutSpendMs.set(0L)
        cycleStartMs.set(0L)
        worstLearnerMs.set(0L)
        worstLearnerName = ""
        slowCount.set(0L)
        skippedByCycleBudget.set(0L)
    }
}
