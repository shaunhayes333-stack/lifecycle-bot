package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P1 — STOP LATENCY CLASSES.
 *
 * OPERATOR MANDATE:
 *   "6463 improved avgStopMs to 4903ms. Preserve this improvement.
 *    Split telemetry: NORMAL_STOP / TRAILING_STOP / HARD_STOP /
 *    CATASTROPHIC_EXIT. Catastrophic conditions (-50%/-90%) receive
 *    highest scheduling priority. Catastrophic decision-to-executor
 *    target <1000ms."
 *
 * DESIGN
 * ──────
 * Per-class latency buckets with min/max/avg/count. `record(class, ms)`
 * is O(1). A separate alert fires when CATASTROPHIC_EXIT latency
 * exceeds 1000ms.
 */
object StopLatencyClasses6464 {

    enum class Class { NORMAL_STOP, TRAILING_STOP, HARD_STOP, CATASTROPHIC_EXIT }

    private const val CATASTROPHIC_TARGET_MS = 1000L

    private data class Bucket(
        var count: Long = 0L,
        var sumMs: Long = 0L,
        var minMs: Long = Long.MAX_VALUE,
        var maxMs: Long = 0L,
    )

    private val buckets = Class.values().associateWith { Bucket() }.toMutableMap()
    private val alerts = AtomicLong(0L)

    fun record(cls: Class, elapsedMs: Long) {
        if (elapsedMs < 0L) return
        val bucket = buckets[cls] ?: return
        synchronized(bucket) {
            bucket.count++
            bucket.sumMs += elapsedMs
            if (elapsedMs < bucket.minMs) bucket.minMs = elapsedMs
            if (elapsedMs > bucket.maxMs) bucket.maxMs = elapsedMs
        }
        try {
            PipelineHealthCollector.labelInc("STOP_LATENCY_${cls.name}_6464")
        } catch (_: Throwable) {}
        if (cls == Class.CATASTROPHIC_EXIT && elapsedMs > CATASTROPHIC_TARGET_MS) {
            alerts.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CATASTROPHIC_EXIT_LATENCY_ALERT_6464",
                    "elapsedMs=$elapsedMs target=$CATASTROPHIC_TARGET_MS",
                )
                PipelineHealthCollector.labelInc("CATASTROPHIC_EXIT_LATENCY_ALERT_6464")
            } catch (_: Throwable) {}
        }
    }

    fun snapshot(): Map<Class, Triple<Long, Long, Long>> = buckets.mapValues { (_, b) ->
        val avg = if (b.count > 0) b.sumMs / b.count else 0L
        Triple(b.count, avg, b.maxMs)
    }

    fun statusLine(): String {
        val parts = buckets.entries.joinToString(" ") { (cls, b) ->
            val avg = if (b.count > 0) b.sumMs / b.count else 0L
            "${cls.name}(n=${b.count} avg=${avg}ms max=${b.maxMs}ms)"
        }
        return "$parts catastrophicAlerts=${alerts.get()}"
    }

    internal fun resetForTest() {
        for ((_, b) in buckets) synchronized(b) {
            b.count = 0L; b.sumMs = 0L; b.minMs = Long.MAX_VALUE; b.maxMs = 0L
        }
        alerts.set(0L)
    }
}
