package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6504 §11 — UNIVERSAL SL SENTINEL.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "P2 — EXIT SWEEP SENTINEL
 *    • Universal SL start must always terminate done/timeout/reset.
 *    • Never leave start=1 done=0 indefinitely."
 *
 * DESIGN
 * ──────
 * `noteStart(id)` — record a start with wall-clock timestamp.
 * `noteDone(id)` — clear the entry with reason=done.
 * `noteReset(id)` — clear with reason=reset.
 * `sweep(nowMs)` — force-terminate any entries older than TTL_MS,
 * emitting `UNIVERSAL_SL_TIMEOUT_SENTINEL_6504` per timed-out entry.
 * Call `sweep()` from the exit coordinator each tick — cheap, ~O(open).
 */
object UniversalSlSentinel6504 {

    private const val TTL_MS = 10_000L // hard cap on any SL start->done window

    private val inFlight = ConcurrentHashMap<String, Long>()
    private val startCount = AtomicLong(0L)
    private val doneCount = AtomicLong(0L)
    private val resetCount = AtomicLong(0L)
    private val timeoutCount = AtomicLong(0L)

    fun noteStart(id: String) {
        if (id.isBlank()) return
        inFlight[id] = System.currentTimeMillis()
        startCount.incrementAndGet()
    }

    fun noteDone(id: String) {
        if (id.isBlank()) return
        if (inFlight.remove(id) != null) doneCount.incrementAndGet()
    }

    fun noteReset(id: String) {
        if (id.isBlank()) return
        if (inFlight.remove(id) != null) resetCount.incrementAndGet()
    }

    /**
     * Force-timeout any entries older than TTL. Callers pass a "cleanup"
     * lambda that they use to actually release the associated resource
     * (lease, slot, sell lock). Sentinel does NOT touch shared state
     * directly — it only signals timeout so the caller can clean up.
     * Returns the count of timed-out entries reaped this sweep.
     */
    fun sweep(onTimeout: (id: String, ageMs: Long) -> Unit = { _, _ -> }): Int {
        val now = System.currentTimeMillis()
        var reaped = 0
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val age = now - e.value
            if (age >= TTL_MS) {
                val id = e.key
                it.remove()
                timeoutCount.incrementAndGet()
                reaped++
                try {
                    ForensicLogger.lifecycle(
                        "UNIVERSAL_SL_TIMEOUT_SENTINEL_6504",
                        "id=${id.take(40)} ageMs=$age ttlMs=$TTL_MS action=forced_reap",
                    )
                    PipelineHealthCollector.labelInc("UNIVERSAL_SL_TIMEOUT_SENTINEL_6504")
                } catch (_: Throwable) {}
                try { onTimeout(id, age) } catch (_: Throwable) {}
            }
        }
        return reaped
    }

    fun size(): Int = inFlight.size

    fun statusLine(): String =
        "inFlight=${inFlight.size} start=${startCount.get()} done=${doneCount.get()} " +
            "reset=${resetCount.get()} timeout=${timeoutCount.get()}"

    internal fun clearForTest() {
        inFlight.clear()
        startCount.set(0L); doneCount.set(0L); resetCount.set(0L); timeoutCount.set(0L)
    }
}
