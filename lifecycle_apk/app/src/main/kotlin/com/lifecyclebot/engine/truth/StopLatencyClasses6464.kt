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

    // V5.0.7807 — B1: trigger -> broadcast buckets, beside the intent -> confirm
    // buckets above. Emergency-class samples are held to the operator's 3s SLA
    // (HotExitSupervisorContract6387.UNIVERSAL_STOP_P95_TRIGGER_TO_BROADCAST_MS).
    // Field Manual L248.
    private const val TRIGGER_TO_BROADCAST_SLA_MS_7807 = 3_000L
    private val broadcastBuckets7807 = Class.values().associateWith { Bucket() }.toMutableMap()
    private val broadcastSlaBreaches7807 = AtomicLong(0L)

    fun recordTriggerToBroadcast7807(cls: Class, elapsedMs: Long, emergency: Boolean) {
        if (elapsedMs < 0L) return
        val bucket = broadcastBuckets7807[cls] ?: return
        synchronized(bucket) {
            bucket.count++
            bucket.sumMs += elapsedMs
            if (elapsedMs < bucket.minMs) bucket.minMs = elapsedMs
            if (elapsedMs > bucket.maxMs) bucket.maxMs = elapsedMs
        }
        try { PipelineHealthCollector.labelInc("STOP_TRIGGER_TO_BROADCAST_${cls.name}_7807") } catch (_: Throwable) {}
        if (emergency && elapsedMs > TRIGGER_TO_BROADCAST_SLA_MS_7807) {
            broadcastSlaBreaches7807.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EMERGENCY_TRIGGER_TO_BROADCAST_SLA_BREACH_7807",
                    "class=${cls.name} elapsedMs=$elapsedMs slaMs=$TRIGGER_TO_BROADCAST_SLA_MS_7807",
                )
                PipelineHealthCollector.labelInc("EMERGENCY_TRIGGER_TO_BROADCAST_SLA_BREACH_7807")
            } catch (_: Throwable) {}
        }
    }

    /** V5.0.7807 — (count, avgMs, maxMs) per class for trigger -> broadcast. */
    fun broadcastSnapshot7807(): Map<Class, Triple<Long, Long, Long>> = broadcastBuckets7807.mapValues { (_, b) ->
        synchronized(b) {
            val avg = if (b.count > 0) b.sumMs / b.count else 0L
            Triple(b.count, avg, b.maxMs)
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
        // V5.0.7807 — trigger -> broadcast, same layout.
        val bParts7807 = broadcastBuckets7807.entries.joinToString(" ") { (cls, b) ->
            val avg = if (b.count > 0) b.sumMs / b.count else 0L
            "${cls.name}(n=${b.count} avg=${avg}ms max=${b.maxMs}ms)"
        }
        return "$parts catastrophicAlerts=${alerts.get()} | trigger->broadcast: $bParts7807 slaBreaches=${broadcastSlaBreaches7807.get()}"
    }

    internal fun resetForTest() {
        for ((_, b) in buckets) synchronized(b) {
            b.count = 0L; b.sumMs = 0L; b.minMs = Long.MAX_VALUE; b.maxMs = 0L
        }
        alerts.set(0L)
        for ((_, b) in broadcastBuckets7807) synchronized(b) {
            b.count = 0L; b.sumMs = 0L; b.minMs = Long.MAX_VALUE; b.maxMs = 0L
        }
        broadcastSlaBreaches7807.set(0L)
    }
}
