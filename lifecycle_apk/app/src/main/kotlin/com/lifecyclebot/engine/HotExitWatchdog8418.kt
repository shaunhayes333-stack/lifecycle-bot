package com.lifecyclebot.engine

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §THE_EXIT_LOOP'S_WATCHDOG_LIVED_IN_THE_LOOP_IT_WATCHED_BESIDE.
 *
 * The held-position mark loop (openPositionTickLoop) runs on its own pool, but the only thing that relaunches it when an
 * iteration hangs (superviseOpenPositionTickLoop7283) was called once per bot-loop cycle. A bot loop slowed by intake
 * (5.0.8416: 4.8 s mean, 12 s max cycles) or stalled outright therefore also stopped the supervision of the exit path.
 * This is a dedicated daemon thread that runs the same supervision every [PERIOD_MS_8418], independent of the bot loop,
 * the coroutine dispatchers and the network. The bot loop keeps its own call; [Gate8418] makes concurrent passes
 * single-flight so the two callers can never relaunch the loop twice.
 */
object HotExitWatchdog8418 {
    private const val PERIOD_MS_8418 = 5_000L

    private val ticks = AtomicLong(0L)
    private val errors = AtomicLong(0L)
    private val skippedBusy = AtomicLong(0L)
    @Volatile private var lastTickAtMs = 0L
    @Volatile private var exec: ScheduledExecutorService? = null

    /** Single-flight gate: one supervision pass at a time, whichever thread asks. */
    class Gate8418 {
        private val busy = AtomicBoolean(false)
        /** Runs [body] unless another pass is in progress; true when it ran. */
        fun runExclusive(body: () -> Unit): Boolean {
            if (!busy.compareAndSet(false, true)) return false
            try { body() } finally { busy.set(false) }
            return true
        }
    }

    @Synchronized
    fun start8418(tick: () -> Unit) {
        if (exec != null) return
        val e = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AATE-HotExitWatchdog-8418").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }
        e.scheduleWithFixedDelay({ runTick8418(tick) }, PERIOD_MS_8418, PERIOD_MS_8418, TimeUnit.MILLISECONDS)
        exec = e
    }

    @Synchronized
    fun stop8418() {
        try { exec?.shutdownNow() } catch (_: Throwable) {}
        exec = null
    }

    /** One watchdog pass; a throwing pass is counted and never ends the schedule. */
    internal fun runTick8418(tick: () -> Unit) {
        try {
            tick()
            ticks.incrementAndGet()
        } catch (_: Throwable) {
            errors.incrementAndGet()
        }
        lastTickAtMs = System.currentTimeMillis()
    }

    fun noteSkippedBusy8418() { skippedBusy.incrementAndGet() }

    fun statusLine8418(nowMs: Long = System.currentTimeMillis()): String =
        "hotExitWatchdog8418[running=${exec != null} ticks=${ticks.get()} errors=${errors.get()} skippedBusy=${skippedBusy.get()} " +
            "lastAgoMs=${if (lastTickAtMs > 0L) nowMs - lastTickAtMs else -1L} periodMs=$PERIOD_MS_8418]"
}
