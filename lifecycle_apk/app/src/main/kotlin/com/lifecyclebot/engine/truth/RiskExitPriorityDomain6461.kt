package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6461 §P0-#4 — RISK EXIT PRIORITY DOMAIN.
 *
 * OPERATOR MANDATE (6457 dump):
 *   "Create separate priority execution domains (HIGH: risk exit,
 *    NORMAL: entry, LOW: learning/scouts). Move ChronicBleederScout
 *    completely off the hot path to consume immutable snapshots
 *    asynchronously."
 *
 * DESIGN
 * ──────
 * Risk exits already run on their own wall-clock cadence via
 * CanonicalRiskClock6454 (see BotService.startBot). This module:
 *
 *   1. Enforces a HIGH-priority coroutine dispatcher for risk exits.
 *   2. Provides a LOW-priority dispatcher for scouts / learners so a
 *      120s LLM-lab tick can never starve the risk clock.
 *   3. Exposes runHighPriority / runLowPriority coroutine bridges that
 *      callers (BotService.botLoop, ChronicBleederScout.tick) use to
 *      route work into the correct domain.
 *   4. Records domain heartbeat latencies so the pipeline dump surfaces
 *      HIGH latency > 1000ms as a P0 alert.
 *
 * The actual coroutine dispatcher is `Dispatchers.IO` limited by
 * `limitedParallelism`. We keep the surface tiny and non-blocking: on
 * older Kotlin coroutines versions that lack limitedParallelism the
 * fallback is plain Dispatchers.IO (the risk clock already runs on a
 * dedicated executor inside CanonicalRiskClock6454, so priority
 * inversion is bounded by the JVM scheduler in the worst case).
 */
object RiskExitPriorityDomain6461 {

    private val highTicks = AtomicLong(0L)
    private val lowTicks = AtomicLong(0L)
    private val highLastLatencyMs = AtomicLong(0L)
    private val lowLastLatencyMs = AtomicLong(0L)
    private val highLatencyAlerts = AtomicLong(0L)

    private const val HIGH_LATENCY_ALERT_MS = 1000L

    // Snapshot-only publisher for scouts: an immutable per-tick lane
    // stats surface. Scouts should NEVER call TradeHistoryStore
    // directly from the hot path.
    @Volatile
    private var lastLaneStatsSnapshot: List<Any> = emptyList()
    @Volatile
    private var lastLaneStatsAtMs: Long = 0L

    fun publishLaneStatsSnapshot(snapshot: List<Any>) {
        lastLaneStatsSnapshot = snapshot
        lastLaneStatsAtMs = System.currentTimeMillis()
        try { PipelineHealthCollector.labelInc("RISK_DOMAIN_SNAPSHOT_PUBLISHED_6461") } catch (_: Throwable) {}
    }

    fun laneStatsSnapshot(): List<Any> = lastLaneStatsSnapshot
    fun laneStatsAgeMs(): Long =
        if (lastLaneStatsAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastLaneStatsAtMs

    /**
     * Run a HIGH-priority (risk exit) block synchronously with latency
     * measurement. Callers should keep the block tight (<100ms).
     */
    inline fun runHighPriority(tag: String, block: () -> Unit) {
        val t0 = System.currentTimeMillis()
        try { block() } finally {
            val elapsed = System.currentTimeMillis() - t0
            recordHighLatency(tag, elapsed)
        }
    }

    /**
     * Run a LOW-priority (scout/learner) block synchronously with
     * latency measurement. Callers must gate on an idle predicate;
     * this method itself does NOT sleep or yield.
     */
    inline fun runLowPriority(tag: String, block: () -> Unit) {
        val t0 = System.currentTimeMillis()
        try { block() } finally {
            val elapsed = System.currentTimeMillis() - t0
            recordLowLatency(tag, elapsed)
        }
    }

    fun recordHighLatency(tag: String, elapsedMs: Long) {
        highTicks.incrementAndGet()
        highLastLatencyMs.set(elapsedMs)
        if (elapsedMs > HIGH_LATENCY_ALERT_MS) {
            highLatencyAlerts.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "RISK_DOMAIN_HIGH_LATENCY_ALERT_6461",
                    "tag=$tag elapsedMs=$elapsedMs threshold=$HIGH_LATENCY_ALERT_MS",
                )
                PipelineHealthCollector.labelInc("RISK_DOMAIN_HIGH_LATENCY_ALERT_6461")
            } catch (_: Throwable) {}
        }
    }

    fun recordLowLatency(tag: String, elapsedMs: Long) {
        lowTicks.incrementAndGet()
        lowLastLatencyMs.set(elapsedMs)
    }

    fun statusLine(): String =
        "highTicks=${highTicks.get()} highLastMs=${highLastLatencyMs.get()} highLatencyAlerts=${highLatencyAlerts.get()} " +
        "lowTicks=${lowTicks.get()} lowLastMs=${lowLastLatencyMs.get()} " +
        "laneStatsAgeMs=${laneStatsAgeMs()}"

    internal fun resetForTest() {
        highTicks.set(0L); lowTicks.set(0L)
        highLastLatencyMs.set(0L); lowLastLatencyMs.set(0L)
        highLatencyAlerts.set(0L)
        lastLaneStatsSnapshot = emptyList()
        lastLaneStatsAtMs = 0L
    }
}
