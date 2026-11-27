package com.lifecyclebot.engine.cortex

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Coalesces canonical-close durability on the existing Cortex persistence worker.
 * A request only schedules work; it is never evidence that a close is durable.
 * The callbacks retain the store's atomic state/receipt barrier and writer lock.
 *
 * At most one drain is queued or running. A close arriving during a capture asks
 * for a following drain, while pacing and failed writes schedule their own retry
 * without sleeping on the worker or relying on a later market/bus tick.
 */
internal class CortexCloseCheckpoint9045(
    private val executor: ScheduledExecutorService,
    private val hasPending: () -> Boolean,
    private val remainingPaceMs: () -> Long,
    private val commit: () -> Boolean,
    private val onFailure: (Throwable) -> Unit = {},
    private val retryMinMs: Long = 1_000L,
    private val retryMaxMs: Long = 30_000L,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init {
        require(retryMinMs > 0L)
        require(retryMaxMs >= retryMinMs)
    }

    private val gate = Any()
    private var queued: ScheduledFuture<*>? = null
    private var queuedAtNanos = 0L
    private var timerToken = 0L
    private var running = false
    private var requested = false
    // Only the single active drain changes this value.
    private var nextRetryMs = retryMinMs
    private var retryAtNanos = 0L
    private var retryActive = false

    /** No store access, encoder work, or wait for the persistence lock occurs here. */
    fun request(): Boolean = synchronized(gate) {
        requested = true
        if (running) return@synchronized true
        val timer = queued ?: return@synchronized scheduleLocked(0L)
        val now = clockNanos()
        val remainingNanos = queuedAtNanos - now
        if (remainingNanos <= 0L) return@synchronized true
        val delayMs = try {
            maxOf(remainingPaceMs().coerceAtLeast(0L), retryRemainingMs(now))
        } catch (failure: Throwable) {
            report(failure)
            return@synchronized true // keep the existing wake; do not guess eligibility
        }
        // A force flush can make a new close eligible before an older pace timer.
        // Ignore sub-millisecond rounding differences so hot requests do not churn
        // a timer whose effective millisecond deadline has not changed.
        if (remainingNanos - TimeUnit.MILLISECONDS.toNanos(delayMs) < 1_000_000L)
            return@synchronized true
        ++timerToken // invalidate even a canceled task that already entered its callback
        queued = null
        timer.cancel(false)
        scheduleLocked(delayMs)
    }

    private fun scheduleLocked(delayMs: Long): Boolean {
        val token = ++timerToken
        val delay = delayMs.coerceAtLeast(0L)
        queuedAtNanos = clockNanos() + TimeUnit.MILLISECONDS.toNanos(delay)
        return try {
            queued = executor.schedule({ drain(token) }, delay, TimeUnit.MILLISECONDS)
            true
        } catch (failure: Throwable) {
            queued = null
            requested = true
            // Keep the request outstanding. The canonical journal is still unacked;
            // a later request can retry a rejected schedule, never run it inline.
            report(failure)
            false
        }
    }

    private fun retryRemainingMs(nowNanos: Long = clockNanos()): Long {
        if (!retryActive) return 0L
        val nanos = retryAtNanos - nowNanos
        return if (nanos <= 0L) 0L else 1L + (nanos - 1L) / 1_000_000L
    }

    private fun drain(token: Long) {
        synchronized(gate) {
            if (token != timerToken || queued == null) return
            queued = null
            running = true
            requested = false
        }
        var delayMs = 0L
        var failed = false
        try {
            if (hasPending()) {
                val paceMs = maxOf(remainingPaceMs().coerceAtLeast(0L), retryRemainingMs())
                if (paceMs > 0L) delayMs = paceMs
                else if (commit()) { nextRetryMs = retryMinMs; retryActive = false }
                else failed = true
            }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            report(failure)
            failed = true
        }
        if (failed) {
            retryAtNanos = clockNanos() + TimeUnit.MILLISECONDS.toNanos(nextRetryMs)
            retryActive = true
            delayMs = maxOf(delayMs, nextRetryMs)
            nextRetryMs = if (nextRetryMs >= retryMaxMs / 2L) retryMaxMs
                else minOf(retryMaxMs, nextRetryMs * 2L)
        }
        val pending = try {
            hasPending()
        } catch (failure: Throwable) {
            report(failure)
            delayMs = maxOf(delayMs, retryMinMs)
            true
        }
        if (pending) {
            delayMs = maxOf(delayMs, retryRemainingMs(), try {
                remainingPaceMs().coerceAtLeast(0L)
            } catch (failure: Throwable) {
                report(failure)
                retryMinMs
            })
        } else { nextRetryMs = retryMinMs; retryActive = false }
        synchronized(gate) {
            running = false
            if (pending || requested) scheduleLocked(delayMs)
        }
    }

    private fun report(failure: Throwable) {
        try { onFailure(failure) } catch (_: Throwable) {}
    }
}
