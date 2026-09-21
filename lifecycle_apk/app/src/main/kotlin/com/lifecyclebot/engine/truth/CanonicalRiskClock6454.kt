package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6454 §P0 — INDEPENDENT RISK CLOCK.
 *
 * OPERATOR MANDATE:
 *   "Protective exits/universal SL must run from their OWN wall-clock
 *    coroutine. Do not launch them from the end of botLoop.
 *    Scanner/watchdog/UI/provider stalls must have zero effect on risk
 *    cadence.
 *    Acceptance: provider call deliberately hangs 120s => risk
 *    evaluation still runs on cadence and SL executes."
 *
 * DESIGN
 * ──────
 * A dedicated CoroutineScope on Dispatchers.Default runs a 500ms tick
 * that pumps ProtectiveExitScheduler6450 heartbeat + starvation check
 * regardless of any bot-loop stall. The caller supplies a `riskTick`
 * lambda that receives (positionId, mint) for each currently-open
 * canonical position — the caller decides what to do (in practice:
 * fetch fresh mark from an in-memory price cache and call
 * ProtectiveExitScheduler6450.evaluate(...) with real numbers).
 *
 * The clock is single-flight: start() is idempotent, stop() is graceful.
 */
object CanonicalRiskClock6454 {

    private const val TICK_MS = 500L

    /**
     * V5.0.7213 §NEVER_LET_POSITION_WORK_PUSH_OUT_THE_NEXT_TICK.
     *
     * Operator directive #8: "Never let scanner/provider work starve it.
     * Required invariant while runtime active: exitSchedulerHeartbeatAge <
     * 2 x expected cadence."
     *
     * The per-position callback runs INLINE in this loop, so the real cadence
     * is TICK_MS plus however long the whole sweep takes. Every callback is
     * in-memory today (Executor.protectiveExitThresholds6882 reads
     * ts.lastPrice; the sell is launched on Dispatchers.IO and never awaited),
     * but "today" is not a guarantee: one future provider call, one
     * synchronous log flush, or simply a large enough book, and the sweep owns
     * the clock. 2 x TICK_MS = 1000ms is the invariant, so the sweep may
     * consume at most TICK_MS of it.
     *
     * Exceeding the budget does not drop the remaining positions — [cursor]
     * resumes the sweep where it stopped on the next tick, so a large book is
     * served round-robin instead of the tail being starved forever by a plain
     * `break`. Under budget, which is the normal case, every position is still
     * evaluated on every tick exactly as before.
     */
    private const val SWEEP_BUDGET_MS = TICK_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val ticks = AtomicLong(0L)
    private val callbackFailures = AtomicLong(0L)
    private val lastTickAtMs = AtomicLong(0L)
    private val currentJob = AtomicReference<Job?>(null)
    private val riskTickRef = AtomicReference<((positionId: String, mint: String) -> Unit)?>(null)

    // V5.0.7213 — sweep bookkeeping. `openReadFailures` exists because the
    // read used to be `catch { emptyList() }`, which made a broken position
    // authority indistinguishable from a flat book — and a flat book is what
    // silenced the whole exit engine in the 7212 snapshot while this clock
    // reported running=true ticks=280 cbFail=0.
    private val cursor = AtomicInteger(0)
    private val openReadFailures = AtomicLong(0L)
    private val budgetOverruns = AtomicLong(0L)
    private val deferredThisTick = AtomicLong(0L)
    private val evaluatedPositions = AtomicLong(0L)

    fun start(riskTick: (positionId: String, mint: String) -> Unit) {
        riskTickRef.set(riskTick)
        if (!running.compareAndSet(false, true)) return
        val job = scope.launch {
            try {
                ForensicLogger.lifecycle("CANONICAL_RISK_CLOCK_STARTED_6454", "tickMs=$TICK_MS")
                PipelineHealthCollector.labelInc("CANONICAL_RISK_CLOCK_STARTED_6454")
            } catch (_: Throwable) {}
            while (isActive && running.get()) {
                try {
                    ticks.incrementAndGet()
                    lastTickAtMs.set(System.currentTimeMillis())

                    // V5.0.7213 — read the open set FIRST and keep the failure
                    // distinguishable from emptiness. `null` means the position
                    // authority could not be read; it must never be treated as
                    // "the book is flat", because flat suppresses the exit
                    // engine's entire alarm surface.
                    val open7213 = try {
                        CanonicalPositionAuthority6441.openPositions()
                    } catch (_: Throwable) {
                        openReadFailures.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("RISK_CLOCK_OPEN_READ_FAILED_7213") } catch (_: Throwable) {}
                        null
                    }

                    // V5.0.7213 — service the exit scheduler BEFORE any
                    // per-position work. The heartbeat was previously stamped
                    // only from inside evaluate(), i.e. only if at least one
                    // position was evaluated, so an empty book or a slow sweep
                    // both read as SCHEDULER_STARVATION. This is the
                    // independent high-priority cadence the mandate asks for:
                    // nothing between the tick and this call can delay it.
                    try {
                        ProtectiveExitScheduler6450.serviceTick7213(
                            openPositionCount = open7213?.size ?: -1,
                            cadenceMs = TICK_MS,
                        )
                    } catch (_: Throwable) {}

                    val cb = riskTickRef.get()
                    if (cb != null && open7213 != null) {
                        // Stable order, so the round-robin cursor addresses the
                        // same position from one tick to the next.
                        // openPositions() is built from a ConcurrentHashMap and
                        // has no defined iteration order of its own.
                        val ordered7213 = open7213.sortedBy { it.positionId }
                        val n7213 = ordered7213.size
                        if (n7213 > 0) {
                            val deadline7213 = System.currentTimeMillis() + SWEEP_BUDGET_MS
                            var i7213 = cursor.get().let { if (it in 0 until n7213) it else 0 }
                            var served7213 = 0
                            while (served7213 < n7213) {
                                val p = ordered7213[i7213]
                                try { cb(p.positionId, p.mint) } catch (_: Throwable) {
                                    callbackFailures.incrementAndGet()
                                }
                                served7213++
                                i7213 = (i7213 + 1) % n7213
                                if (served7213 < n7213 && System.currentTimeMillis() >= deadline7213) {
                                    budgetOverruns.incrementAndGet()
                                    deferredThisTick.set((n7213 - served7213).toLong())
                                    try {
                                        ForensicLogger.lifecycle(
                                            "RISK_CLOCK_SWEEP_BUDGET_EXCEEDED_7213",
                                            "open=$n7213 served=$served7213 deferred=${n7213 - served7213} " +
                                                "budgetMs=$SWEEP_BUDGET_MS " +
                                                "note=resuming_from_cursor_next_tick_no_position_is_skipped",
                                        )
                                        PipelineHealthCollector.labelInc("RISK_CLOCK_SWEEP_BUDGET_EXCEEDED_7213")
                                    } catch (_: Throwable) {}
                                    break
                                }
                            }
                            if (served7213 >= n7213) deferredThisTick.set(0L)
                            evaluatedPositions.addAndGet(served7213.toLong())
                            cursor.set(i7213)
                        } else {
                            cursor.set(0)
                            deferredThisTick.set(0L)
                        }
                    }
                    // V5.0.7176 — this loop holds the authoritative open
                    // set, so it is the honest place to retire scheduler
                    // bookkeeping for positions that have actually closed.
                    // Both of those maps previously grew for the whole life
                    // of the process because nothing ever removed from them.
                    //
                    // V5.0.7213 — pruning now also runs on a successful read of
                    // an EMPTY set, which is what retires the last position's
                    // latch when the book goes flat. It is still skipped when
                    // the read FAILED, which is the case the old
                    // `isEmpty() -> return` guard inside pruneClosed7176 was
                    // really protecting against.
                    if (open7213 != null) {
                        try {
                            ProtectiveExitScheduler6450.pruneClosed7176(
                                open7213.mapTo(HashSet(open7213.size)) { it.positionId },
                            )
                        } catch (_: Throwable) {}
                    }
                    try { ProtectiveExitScheduler6450.checkStarvation() } catch (_: Throwable) {}
                } catch (_: Throwable) {}
                delay(TICK_MS)
            }
        }
        currentJob.set(job)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { currentJob.get()?.cancel() } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("CANONICAL_RISK_CLOCK_STOPPED_6454") } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val lastAgo = if (lastTickAtMs.get() == 0L) "never" else "${System.currentTimeMillis() - lastTickAtMs.get()}ms ago"
        // V5.0.7213 — `running=true ticks=280 lastTick=56ms cbFail=0` was a
        // perfectly healthy-looking line printed beside an exit scheduler that
        // had not evaluated anything for 118 seconds. The missing number was
        // how many positions the sweep actually visited, so it is here now.
        return "running=${running.get()} ticks=${ticks.get()} lastTick=$lastAgo cbFail=${callbackFailures.get()} " +
            "posEvals=${evaluatedPositions.get()} openReadFail=${openReadFailures.get()} " +
            "budgetOverruns=${budgetOverruns.get()} deferred=${deferredThisTick.get()} cursor=${cursor.get()}"
    }
}
