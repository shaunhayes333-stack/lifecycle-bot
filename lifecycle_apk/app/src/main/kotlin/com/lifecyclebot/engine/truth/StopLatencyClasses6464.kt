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

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7809 — REGRESSION GATE: clean p50/p95 for CURRENT-SESSION LIVE
    // triggers only. The 7807 buckets above keep their all-time avg/max (a
    // single 9-minute outlier from a deferred/recovered stamp dominated them);
    // these rings hold the last [GATE_RING_7809] samples whose trigger was
    // first actionable in this process, and only live sells broadcast, so a
    // build can be compared against the previous one on p50/p95.
    //   trigger -> broadcast  per class (condition first actionable -> SELL_BROADCAST)
    //   broadcast -> finality one ring (SELL_BROADCAST -> canonical terminal sell)
    // Field Manual L248.
    // ─────────────────────────────────────────────────────────────────────
    private const val GATE_RING_7809 = 256

    private class Ring7809 {
        val values = LongArray(GATE_RING_7809)
        var count = 0L
        var next = 0
        fun add(v: Long) {
            values[next] = v
            next = (next + 1) % GATE_RING_7809
            count++
        }
        fun samples(): LongArray = values.copyOf(minOf(count, GATE_RING_7809.toLong()).toInt())
    }

    private val gateT2B7809 = Class.values().associateWith { Ring7809() }
    private val gateB2F7809 = Ring7809()

    fun recordGateTriggerToBroadcast7809(cls: Class, elapsedMs: Long) {
        if (elapsedMs < 0L) return
        val r = gateT2B7809[cls] ?: return
        synchronized(r) { r.add(elapsedMs) }
    }

    fun recordGateBroadcastToFinality7809(elapsedMs: Long) {
        if (elapsedMs < 0L) return
        synchronized(gateB2F7809) { gateB2F7809.add(elapsedMs) }
    }

    /** Nearest-rank percentile of [values] (unsorted), 0 when empty. */
    internal fun percentile7809(values: LongArray, p: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sortedArray()
        val rank = kotlin.math.ceil(p.coerceIn(0.0, 1.0) * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** (samples, p50, p95) per class for current-session live trigger -> broadcast. */
    fun gateSnapshot7809(): Map<Class, Triple<Int, Long, Long>> = gateT2B7809.mapValues { (_, r) ->
        val s = synchronized(r) { r.samples() }
        Triple(s.size, percentile7809(s, 0.50), percentile7809(s, 0.95))
    }

    fun gateLine7809(): String {
        val t2b = gateSnapshot7809().entries.joinToString(" ") { (cls, v) ->
            "${cls.name}(n=${v.first} p50=${v.second}ms p95=${v.third}ms)"
        }
        val f = synchronized(gateB2F7809) { gateB2F7809.samples() }
        return "STOP_LATENCY_GATE_7809 sessionLive trigger->broadcast: $t2b | broadcast->finality(n=${f.size} " +
            "p50=${percentile7809(f, 0.50)}ms p95=${percentile7809(f, 0.95)}ms)"
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
        return "$parts catastrophicAlerts=${alerts.get()} | trigger->broadcast: $bParts7807 slaBreaches=${broadcastSlaBreaches7807.get()}" +
            " | " + gateLine7809() + " | stages7876: " + ExitStageTiming7876.statusLine()
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
        for ((_, r) in gateT2B7809) synchronized(r) { r.count = 0L; r.next = 0 }
        synchronized(gateB2F7809) { gateB2F7809.count = 0L; gateB2F7809.next = 0 }
    }
}
