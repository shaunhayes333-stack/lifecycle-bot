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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val ticks = AtomicLong(0L)
    private val callbackFailures = AtomicLong(0L)
    private val lastTickAtMs = AtomicLong(0L)
    private val currentJob = AtomicReference<Job?>(null)
    private val riskTickRef = AtomicReference<((positionId: String, mint: String) -> Unit)?>(null)

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
                    val cb = riskTickRef.get()
                    if (cb != null) {
                        val open = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
                        for (p in open) {
                            try { cb(p.positionId, p.mint) } catch (_: Throwable) {
                                callbackFailures.incrementAndGet()
                            }
                        }
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
        return "running=${running.get()} ticks=${ticks.get()} lastTick=$lastAgo cbFail=${callbackFailures.get()}"
    }
}
