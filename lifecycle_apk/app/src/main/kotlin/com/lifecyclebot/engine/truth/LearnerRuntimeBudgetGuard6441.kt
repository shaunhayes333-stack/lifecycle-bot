package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6441 §7 — LEARNER / MAINTENANCE RUNTIME BUDGET GUARD.
 *
 * OPERATOR MANDATE §7:
 *   "POST_LEARNING_MAINTENANCE and LabUniverseTick must operate under
 *    strict bounded work budgets. They may checkpoint/defer work but
 *    may NEVER extend a trading cycle indefinitely. No learner/
 *    maintenance work may own scanner, entry, exit or state locks
 *    while performing heavy computation. Expensive scans become
 *    resumable bounded slices."
 *
 * Extends V5.0.6437's PreSupervisorBudgetGuard. The 6437 guard focused
 * on learners running BEFORE the supervisor phase. This §7 guard is a
 * generic per-slice budget with resumption tracking:
 *
 *   beginSlice(name, budgetMs)
 *     -> caller does work, checkpointing progress
 *   noteProgress(itemsCompleted, itemsRemaining)
 *   endSlice() -> Slice report with elapsedMs, budgetHit, deferredItems
 *
 * ONE SIMULTANEOUS SLICE OWNED per name. Concurrent begin() with the
 * same name is rejected as INVARIANT_VIOLATION.
 */
object LearnerRuntimeBudgetGuard6441 {

    data class Slice(
        val name: String,
        val budgetMs: Long,
        val startMs: Long,
        val completedItems: Long,
        val remainingItems: Long,
        val elapsedMs: Long,
        val budgetHit: Boolean,
    )

    private const val DEFAULT_BUDGET_MS = 5_000L

    private val activeSliceStart = AtomicReference<Long>(0L)
    @Volatile private var activeSliceName: String = ""
    @Volatile private var activeSliceBudget: Long = DEFAULT_BUDGET_MS
    @Volatile private var activeSliceCompleted: Long = 0L
    @Volatile private var activeSliceRemaining: Long = 0L

    private val totalSlices = AtomicLong(0L)
    private val budgetHitCount = AtomicLong(0L)
    private val totalDeferred = AtomicLong(0L)
    private val totalCompleted = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)

    fun beginSlice(name: String, budgetMs: Long = DEFAULT_BUDGET_MS): Boolean {
        if (activeSliceStart.get() > 0L && activeSliceName != name) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LEARNER_BUDGET_CONCURRENT_SLICE_6441",
                    "existing=$activeSliceName requested=$name — REJECTED",
                )
            } catch (_: Throwable) {}
            return false
        }
        activeSliceStart.set(System.currentTimeMillis())
        activeSliceName = name
        activeSliceBudget = budgetMs
        activeSliceCompleted = 0L
        activeSliceRemaining = 0L
        return true
    }

    fun noteProgress(itemsCompleted: Long, itemsRemaining: Long) {
        activeSliceCompleted = itemsCompleted
        activeSliceRemaining = itemsRemaining
    }

    /** True if the caller should stop early because the budget is hit. */
    fun shouldStop(): Boolean {
        val start = activeSliceStart.get()
        if (start <= 0L) return false
        return (System.currentTimeMillis() - start) >= activeSliceBudget
    }

    fun endSlice(): Slice {
        val start = activeSliceStart.getAndSet(0L)
        val name = activeSliceName
        val elapsed = if (start > 0L) System.currentTimeMillis() - start else 0L
        val hit = elapsed >= activeSliceBudget
        totalSlices.incrementAndGet()
        totalCompleted.addAndGet(activeSliceCompleted)
        totalDeferred.addAndGet(activeSliceRemaining)
        if (hit) budgetHitCount.incrementAndGet()
        val slice = Slice(
            name = name,
            budgetMs = activeSliceBudget,
            startMs = start,
            completedItems = activeSliceCompleted,
            remainingItems = activeSliceRemaining,
            elapsedMs = elapsed,
            budgetHit = hit,
        )
        try {
            ForensicLogger.lifecycle(
                "LEARNER_BUDGET_SLICE_6441",
                "name=$name budgetMs=${slice.budgetMs} elapsedMs=$elapsed completed=${slice.completedItems} " +
                    "remaining=${slice.remainingItems} budgetHit=$hit",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("LEARNER_BUDGET_SLICE_6441") } catch (_: Throwable) {}
        activeSliceName = ""
        return slice
    }

    fun statusLine(): String =
        "slices=${totalSlices.get()} budgetHits=${budgetHitCount.get()} " +
            "completed=${totalCompleted.get()} deferred=${totalDeferred.get()} " +
            "invViolations=${invariantViolations.get()}"
}
