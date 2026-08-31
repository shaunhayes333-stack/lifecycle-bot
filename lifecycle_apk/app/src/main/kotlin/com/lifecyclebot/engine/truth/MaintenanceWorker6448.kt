package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6448 P0.4 — MAINTENANCE WORKER (bounded, async, single-flight).
 *
 * OPERATOR:
 *   worstPhase=POST_LEARNING_MAINTENANCE worstMs=357578 workerTimeout=206
 *   avgCycle=17532ms maxCycle=279740ms cycles 121s/279s/150s.
 *
 * POST_LEARNING_MAINTENANCE is currently a synchronous ~350 s stall on
 * the bot loop. This module OWNS all discretionary maintenance runs:
 *   • single-flight per taskName (subsequent submissions COALESCE)
 *   • independent dedicated bounded dispatcher + SupervisorJob
 *     so a task hang can NEVER block the bot cycle
 *   • per-task deadline via withTimeoutOrNull (task is cancelled on
 *     deadline; DEFERRED counter increments)
 *   • per-child timing so the operator dump identifies the exact
 *     culprit inside POST_LEARNING_MAINTENANCE, not just the parent
 *
 * Bot loop calls `submit(name, budgetMs) { block }` — returns
 * immediately. If a task with the same name is already in flight, the
 * new submission COALESCES (increments coalescedCount) and returns.
 *
 * This is P0.4 acceptance test #4: normal cycle p95 <10s, no cycle
 * >30s caused by maintenance.
 */
object MaintenanceWorker6448 {

    // V5.0.6489 — real scheduler isolation. Dispatchers.Default is shared by
    // scoring and other compute work; blocking provider/maintenance calls there
    // can starve BOT_LOOP even when callers never await the Job. A dedicated,
    // bounded, low-priority pool makes offload a physical boundary.
    private val threadSeq = AtomicInteger(0)
    private val dispatcher = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "AATE-maint-${threadSeq.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val inFlight = ConcurrentHashMap<String, Job>()
    // V5.0.6615 — atomic owner set closes the submit-before-map race: two
    // simultaneous periodic ticks can no longer launch the same job twice.
    private val runningNames6615 = ConcurrentHashMap.newKeySet<String>()
    private val timings = ConcurrentHashMap<String, TaskStat>()

    private val submitted = AtomicLong(0L)
    private val coalesced = AtomicLong(0L)
    private val completed = AtomicLong(0L)
    private val deferred = AtomicLong(0L)
    private val failed = AtomicLong(0L)

    data class TaskStat(
        val name: String,
        val lastElapsedMs: Long,
        val lastCompletedAtMs: Long,
        val maxElapsedMs: Long,
        val runCount: Long,
    )

    /**
     * Submit a maintenance task. Returns immediately. If a task with the
     * same name is already running, coalesces (task not re-submitted).
     *
     * @param name unique task identifier (also the coalesce key)
     * @param budgetMs hard deadline in ms — task is cancelled if it exceeds
     * @param block the maintenance work
     */
    fun submit(name: String, budgetMs: Long = 8_000L, block: suspend () -> Unit) {
        submitted.incrementAndGet()
        if (!runningNames6615.add(name)) {
            coalesced.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MAINTENANCE_COALESCED_6448") } catch (_: Throwable) {}
            return
        }
        val job = try { scope.launch {
            val t0 = System.currentTimeMillis()
            val ok = try {
                withTimeoutOrNull(budgetMs) {
                    block()
                    true
                } != null
            } catch (t: Throwable) {
                failed.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "MAINTENANCE_FAILED_6448",
                        "name=$name err=${t.message?.take(80)}",
                    )
                } catch (_: Throwable) {}
                false
            }
            val elapsed = System.currentTimeMillis() - t0
            val prev = timings[name]
            val nextStat = TaskStat(
                name = name,
                lastElapsedMs = elapsed,
                lastCompletedAtMs = System.currentTimeMillis(),
                maxElapsedMs = kotlin.math.max(prev?.maxElapsedMs ?: 0L, elapsed),
                runCount = (prev?.runCount ?: 0L) + 1L,
            )
            timings[name] = nextStat
            if (!ok) {
                deferred.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "MAINTENANCE_DEFERRED_6448",
                        "name=$name elapsedMs=$elapsed budgetMs=$budgetMs — task cancelled at deadline",
                    )
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("MAINTENANCE_DEFERRED_6448") } catch (_: Throwable) {}
            } else {
                completed.incrementAndGet()
                if (elapsed > 3_000L) {
                    try {
                        ForensicLogger.lifecycle(
                            "MAINTENANCE_SLOW_6448",
                            "name=$name elapsedMs=$elapsed maxEver=${nextStat.maxElapsedMs}",
                        )
                    } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("MAINTENANCE_SLOW_6448") } catch (_: Throwable) {}
                }
                try { PipelineHealthCollector.labelInc("MAINTENANCE_COMPLETED_6448") } catch (_: Throwable) {}
            }
        } } catch (t: Throwable) {
            runningNames6615.remove(name)
            failed.incrementAndGet()
            return
        }
        inFlight[name] = job
        job.invokeOnCompletion {
            inFlight.remove(name, job)
            runningNames6615.remove(name)
        }
    }

    fun statusLine(): String {
        val top = timings.values
            .sortedByDescending { it.maxElapsedMs }
            .take(3)
            .joinToString(",") { "${it.name}=${it.lastElapsedMs}ms(max=${it.maxElapsedMs}ms,n=${it.runCount})" }
        return "submitted=${submitted.get()} coalesced=${coalesced.get()} completed=${completed.get()} " +
            "deferred=${deferred.get()} failed=${failed.get()} inFlight=${inFlight.size} top3=[$top]"
    }
}
