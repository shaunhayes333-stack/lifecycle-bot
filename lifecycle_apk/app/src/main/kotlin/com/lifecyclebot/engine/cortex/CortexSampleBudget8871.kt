package com.lifecyclebot.engine.cortex

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Worker admission uses elapsed time; an old decision or wall-clock correction cannot reopen its budget. */
internal class CortexSampleBudget8871(
    intervalMs: Long,
    private val clockNanos: () -> Long = { System.nanoTime() },
) {
    enum class Claim { GRANTED, WAIT, CONTENDED }

    private val intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs)
    private val lastGrant = AtomicReference<Long?>(null)

    init { require(intervalMs > 0L) }

    fun claim(): Claim {
        val now = clockNanos()
        repeat(8) {
            val before = lastGrant.get()
            // nanoTime's origin may be negative and may wrap; only its elapsed difference has meaning.
            // A regressing injected clock cannot rewind the last accepted grant either.
            if (before != null && now - before < intervalNanos) return Claim.WAIT
            if (lastGrant.compareAndSet(before, now)) return Claim.GRANTED
        }
        return Claim.CONTENDED
    }
}
