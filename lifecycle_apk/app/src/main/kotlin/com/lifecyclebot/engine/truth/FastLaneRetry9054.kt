package com.lifecyclebot.engine.truth

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/** Bounded suspended wait for the existing two-part evaluation lease. No admission authority. */
internal class FastLaneRetry9054(private val chainSlots: Semaphore) {
    private val allWait = Semaphore(24)
    private val normalWait = Semaphore(16)
    private val chainWait = Semaphore(8)
    private val queued = AtomicLong()
    private val recovered = AtomicLong()
    private val expired = AtomicLong()
    private val full = AtomicLong()
    private fun chain(origin: String) = origin == "CHAIN_ANSWER_8957"
    private fun launch(origin: String) = origin == "LAUNCH_RULE_8949" || origin == "BASE_BREAKOUT_8995"
    private fun retryable(origin: String) = launch(origin) || chain(origin) ||
        origin == "EVIDENCE_WAIT_8948" || origin == "ONCHAIN_FLOW_8506" || origin == "COPY_SIGNAL" ||
        origin.startsWith("LAUNCH_PRINTS_8942") || origin == "PUMP_PORTAL_WS" || origin == "PUMP_BAND_ENTRY_8118"

    private fun attempt(origin: String, slots: Semaphore, deadlineNs: Long, valid: () -> Boolean): Boolean {
        if (deadlineNs - System.nanoTime() <= 0L || !valid()) return false
        val c = chain(origin)
        if (c && !chainSlots.tryAcquire()) return false
        if (!slots.tryAcquire()) { if (c) chainSlots.release(); return false }
        var keep = false
        try {
            keep = deadlineNs - System.nanoTime() > 0L && valid()
            return keep
        } finally { if (!keep) release(origin, slots) }
    }

    suspend fun acquire(origin: String, slots: Semaphore, deadlineNs: Long, valid: () -> Boolean): Boolean {
        if (attempt(origin, slots, deadlineNs, valid)) return true
        if (!retryable(origin)) return false
        if (deadlineNs - System.nanoTime() <= 0L || !valid()) { expired.incrementAndGet(); return false }
        if (!allWait.tryAcquire()) { full.incrementAndGet(); return false }
        var normal = false
        var c = false
        try {
            if (!launch(origin)) {
                if (!normalWait.tryAcquire()) { full.incrementAndGet(); return false }
                normal = true
            }
            if (chain(origin)) {
                if (!chainWait.tryAcquire()) { full.incrementAndGet(); return false }
                c = true
            }
            queued.incrementAndGet()
            while (deadlineNs - System.nanoTime() > 0L && valid()) {
                delay(75L)
                if (attempt(origin, slots, deadlineNs, valid)) { recovered.incrementAndGet(); return true }
            }
            expired.incrementAndGet()
            return false
        } finally {
            if (c) chainWait.release()
            if (normal) normalWait.release()
            allWait.release()
        }
    }

    fun release(origin: String, slots: Semaphore) {
        slots.release()
        if (chain(origin)) chainSlots.release()
    }

    fun line(): String = "retry9054{waiting=${24-allWait.availablePermits()} queued=${queued.get()} recovered=${recovered.get()} expired=${expired.get()} full=${full.get()}}"
}
