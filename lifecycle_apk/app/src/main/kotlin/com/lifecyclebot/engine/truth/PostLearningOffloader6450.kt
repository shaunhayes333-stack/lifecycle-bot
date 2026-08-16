package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — POST-LEARNING OFFLOADER.
 *
 * OPERATOR MANDATE:
 *   POST_LEARNING_MAINTENANCE = 255754 ms (worst phase).
 *   "Move all non-critical learning/aggregation/report construction off
 *    the trading scheduler.
 *    Execution priorities:
 *      1. protective exits
 *      2. canonical settlement/reconciliation
 *      3. price/position updates
 *      4. executable candidate intake
 *      5. FDG/execution
 *      6. scanner discovery
 *      7. learning
 *      8. reporting/UI"
 *
 * DESIGN
 * ──────
 * Thin façade over MaintenanceWorker6448.submit that (a) tags each unit
 * of offloaded work with its priority band and (b) records duration so
 * the operator can see which offloaded phases dominate. Callers migrate
 * their inline learning/aggregation/reporting blocks by wrapping them in
 * `offload("phaseName", priority) { ... }`.
 */
object PostLearningOffloader6450 {

    enum class Priority { PROTECTIVE_EXIT, CANONICAL_SETTLEMENT, POSITION_UPDATE, INTAKE, EXECUTION, SCANNER, LEARNING, REPORTING }

    private val submitted = AtomicLong(0L)
    private val completed = AtomicLong(0L)
    private val skippedSaturated = AtomicLong(0L)
    private val totalDurationMs = AtomicLong(0L)
    private val maxDurationMs = AtomicLong(0L)

    fun offload(phase: String, priority: Priority = Priority.LEARNING, deadlineMs: Long = 6_000L, block: () -> Unit): Boolean {
        submitted.incrementAndGet()
        return try {
            MaintenanceWorker6448.submit(name = phase.take(40), budgetMs = deadlineMs) {
                val t0 = System.currentTimeMillis()
                try {
                    block()
                    completed.incrementAndGet()
                } finally {
                    val dur = System.currentTimeMillis() - t0
                    totalDurationMs.addAndGet(dur)
                    if (dur > maxDurationMs.get()) maxDurationMs.set(dur)
                    if (dur > deadlineMs) {
                        try {
                            ForensicLogger.lifecycle(
                                "POST_LEARNING_OFFLOAD_DEADLINE_MISS_6450",
                                "phase=$phase priority=$priority durMs=$dur deadlineMs=$deadlineMs",
                            )
                            PipelineHealthCollector.labelInc("POST_LEARNING_OFFLOAD_DEADLINE_MISS_6450")
                        } catch (_: Throwable) {}
                    }
                }
            }
            true
        } catch (_: Throwable) {
            skippedSaturated.incrementAndGet()
            try { PipelineHealthCollector.labelInc("POST_LEARNING_OFFLOAD_SKIPPED_SATURATED_6450") } catch (_: Throwable) {}
            false
        }
    }

    fun statusLine(): String = "submitted=${submitted.get()} completed=${completed.get()} " +
        "skipped=${skippedSaturated.get()} maxMs=${maxDurationMs.get()} totalMs=${totalDurationMs.get()}"
}
