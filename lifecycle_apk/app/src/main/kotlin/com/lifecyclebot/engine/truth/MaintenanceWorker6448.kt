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
     * V5.0.7101 §THE_BUDGET_IS_ADVISORY_FOR_BLOCKING_WORK.
     *
     * `budgetMs` was documented as "hard deadline — task is cancelled if it
     * exceeds", and it is enforced with `withTimeoutOrNull`. Coroutine
     * cancellation is COOPERATIVE: it takes effect at a suspension point. Every
     * task submitted here is ordinary blocking Kotlin — ledger replays, parity
     * audits, registry rebuilds — with no suspension point anywhere in the
     * body, so the timeout cannot interrupt one. The block runs to completion
     * and only then does withTimeoutOrNull get a chance to observe the clock.
     *
     * The device says this plainly: position_parity_audit_6464 is submitted with
     * budgetMs = 3_000 and 5.0.7091 reports it at 6608ms — 3.6 seconds past a
     * deadline that was supposed to have cancelled it. It was not cancelled; it
     * could not be.
     *
     * This is left as it is, deliberately. Making the deadline real would mean
     * threading cancellation checks through CanonicalPaperReplay6464,
     * PositionRegistryParityAudit6464 and EventStreamReplay6467 — paper
     * accounting and parity code — to gain the right to abandon an audit
     * half-finished, which is worse than a slow one that completes. The
     * important thing is that nobody reads `budgetMs` as a guarantee it does not
     * give, and that an overrun is reported as an OVERRUN rather than
     * disappearing into a generic "slow" bucket. See
     * MAINTENANCE_BUDGET_OVERRUN_UNENFORCED_7101 below.
     *
     * @param name unique task identifier (also the coalesce key)
     * @param budgetMs advisory deadline in ms. Enforced by cancellation ONLY at
     *   a suspension point; a fully blocking block will overrun it and complete.
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
                // V5.0.7101 — a task that COMPLETED past its own deadline did
                // not merely run slowly: it proved the deadline unenforceable on
                // its body (see the header). Name that separately from "slow",
                // because the two call for different answers — one is a task to
                // speed up, the other is a guarantee that does not exist.
                if (elapsed > budgetMs) {
                    try {
                        PipelineHealthCollector.labelInc("MAINTENANCE_BUDGET_OVERRUN_UNENFORCED_7101")
                        ForensicLogger.lifecycle(
                            "MAINTENANCE_BUDGET_OVERRUN_UNENFORCED_7101",
                            "name=$name elapsedMs=$elapsed budgetMs=$budgetMs " +
                                "overrunMs=${elapsed - budgetMs} maxEver=${nextStat.maxElapsedMs} " +
                                "action=block_has_no_suspension_point_deadline_could_not_cancel_it",
                        )
                    } catch (_: Throwable) {}
                }
                // This 3s threshold is absolute and stays absolute: "took a long
                // time on a phone" is a real signal and its name claims nothing
                // about any caller's budget. The budget-relative signal is the
                // 7101 counter above; the two are deliberately separate rather
                // than one counter answering to two names. The task's budget is
                // carried in the line so a reader can compare them.
                if (elapsed > 3_000L) {
                    try {
                        ForensicLogger.lifecycle(
                            "MAINTENANCE_SLOW_6448",
                            "name=$name elapsedMs=$elapsed budgetMs=$budgetMs maxEver=${nextStat.maxElapsedMs}",
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
