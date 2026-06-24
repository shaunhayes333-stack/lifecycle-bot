package com.lifecyclebot.engine.diagnostics

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4109 — LockDiagnosticsTracker.
 *
 * Lightweight, allocation-cheap telemetry wrapper around critical mutex / lock
 * acquire-release boundaries. The bot was reporting 1000+ ANRs in 6 hours with
 * `jdk.internal.misc.Unsafe.park` stack frames — the classic kotlinx coroutine
 * Mutex / runBlocking deadlock pattern.
 *
 * Operator forensics could not pinpoint which lock was being held / by which
 * label / for how long, so this object is intentionally cheap:
 *
 *  - track(label) { ... }      — wrap a critical section; reports hold > WARN_MS
 *  - acquired(label) ... released(label)  — for non-bracketed acquisitions
 *  - dump()                    — diagnostic snapshot for /health endpoints
 *
 * We do NOT alter execution flow. If a hold exceeds [WARN_HOLD_MS] we emit a
 * forensic line `LOCK_LONG_HOLD` (rate-limited per label) so the next operator
 * dump shows the exact offending site. If it exceeds [ALERT_HOLD_MS] we also
 * bump a PipelineHealthCollector counter for at-a-glance health.
 *
 * Always-on, no init, no allocations per fast path beyond two map lookups.
 */
object LockDiagnosticsTracker {

    /** Warn after 2 s — covers normal-network-tail RPCs but flags real choke. */
    const val WARN_HOLD_MS: Long = 2_000L

    /** Alert after 10 s — almost certainly indicates a deadlock / hung route. */
    const val ALERT_HOLD_MS: Long = 10_000L

    /** Suppress duplicate forensic emissions per label inside this window. */
    private const val EMIT_THROTTLE_MS: Long = 5_000L

    private data class Stats(
        val owners: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        val totalAcquires: AtomicLong = AtomicLong(0L),
        val longHolds: AtomicLong = AtomicLong(0L),
        val alertHolds: AtomicLong = AtomicLong(0L),
        val maxHoldMs: AtomicLong = AtomicLong(0L),
        val lastEmitMs: AtomicLong = AtomicLong(0L),
        val inFlight: AtomicInteger = AtomicInteger(0),
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    private fun statsFor(label: String): Stats =
        stats.getOrPut(label) { Stats() }

    /**
     * Wrap a critical section with diagnostics. Returns the block's result.
     * Cheap path: 2 map lookups + 2 atomic ops + threadId tag.
     */
    inline fun <T> track(label: String, ownerTag: String = Thread.currentThread().name, block: () -> T): T {
        val ack = acquired(label, ownerTag)
        return try {
            block()
        } finally {
            released(label, ack)
        }
    }

    /** Mark acquired; returns the acquire timestamp (passed to released()). */
    fun acquired(label: String, ownerTag: String = Thread.currentThread().name): Long {
        val now = System.currentTimeMillis()
        val s = statsFor(label)
        s.owners[ownerTag] = now
        s.inFlight.incrementAndGet()
        s.totalAcquires.incrementAndGet()
        return now
    }

    /** Mark released and emit telemetry if the hold exceeded thresholds. */
    fun released(label: String, acquiredAtMs: Long, ownerTag: String = Thread.currentThread().name) {
        val now = System.currentTimeMillis()
        val s = stats[label] ?: return
        s.owners.remove(ownerTag)
        s.inFlight.decrementAndGet()
        val held = now - acquiredAtMs
        // record max
        while (true) {
            val cur = s.maxHoldMs.get()
            if (held <= cur) break
            if (s.maxHoldMs.compareAndSet(cur, held)) break
        }
        if (held >= ALERT_HOLD_MS) {
            s.alertHolds.incrementAndGet()
            maybeEmit(label, ownerTag, held, alert = true, s = s)
            try { PipelineHealthCollector.labelInc("LOCK_ALERT_HOLD_${label}") } catch (_: Throwable) {}
        } else if (held >= WARN_HOLD_MS) {
            s.longHolds.incrementAndGet()
            maybeEmit(label, ownerTag, held, alert = false, s = s)
            try { PipelineHealthCollector.labelInc("LOCK_LONG_HOLD_${label}") } catch (_: Throwable) {}
        }
    }

    private fun maybeEmit(label: String, owner: String, heldMs: Long, alert: Boolean, s: Stats) {
        val now = System.currentTimeMillis()
        val last = s.lastEmitMs.get()
        if (now - last < EMIT_THROTTLE_MS) return
        if (!s.lastEmitMs.compareAndSet(last, now)) return
        val tag = if (alert) "LOCK_ALERT_HOLD" else "LOCK_LONG_HOLD"
        try {
            ForensicLogger.lifecycle(
                tag,
                "label=$label owner=$owner heldMs=$heldMs inFlight=${s.inFlight.get()} " +
                    "longHolds=${s.longHolds.get()} alertHolds=${s.alertHolds.get()} maxHoldMs=${s.maxHoldMs.get()}",
            )
        } catch (_: Throwable) {}
        if (alert) {
            try {
                ErrorLogger.warn(
                    "LockDiagnostics",
                    "$label held ${heldMs}ms by $owner (potential deadlock / hung route)",
                )
            } catch (_: Throwable) {}
        }
    }

    /** Diagnostic snapshot, safe to call from any thread. */
    fun dump(): String {
        if (stats.isEmpty()) return "LOCK_DIAGNOSTICS: idle"
        val sb = StringBuilder("LOCK_DIAGNOSTICS:\n")
        stats.entries.sortedBy { it.key }.forEach { (label, s) ->
            sb.append("  $label inFlight=${s.inFlight.get()} ")
                .append("acquires=${s.totalAcquires.get()} ")
                .append("warn=${s.longHolds.get()} alert=${s.alertHolds.get()} ")
                .append("maxMs=${s.maxHoldMs.get()}\n")
            s.owners.entries.take(5).forEach { (owner, since) ->
                val age = System.currentTimeMillis() - since
                sb.append("    holder=$owner ageMs=$age\n")
            }
        }
        return sb.toString()
    }

    /** Count of acquisitions currently in-flight across a label (or 0). */
    fun inFlight(label: String): Int = stats[label]?.inFlight?.get() ?: 0
}
