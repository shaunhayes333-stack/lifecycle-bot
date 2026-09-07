package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — PROTECTIVE EXIT SCHEDULER.
 *
 * Trigger decisions are independent from scanner/FDG/UI load and are latched
 * monotonically for the lifetime of the position exit epoch.
 *
 * V5.0.6681 — starvation telemetry is runtime-aware. A stopped bot is not a
 * starved exit scheduler; no heartbeat is expected while canonical runtime
 * authority is inactive. A new runtime job also receives a fresh heartbeat
 * grace window so stale time from the previous run cannot poison Start-again.
 */
object ProtectiveExitScheduler6450 {

    enum class TriggerKind { STOP_LOSS, CATASTROPHE, TAKE_PROFIT, TRAILING_STOP }

    data class Latch(
        val positionId: String,
        val exitEpoch: Long,
        val kind: TriggerKind,
        val triggerPrice: Double,
        val triggerTimestamp: Long,
        val quoteAge: Long,
    )

    private const val STARVATION_MS = 15_000L

    private val latches = ConcurrentHashMap<String, Latch>()
    private val lastHeartbeatMs = AtomicLong(0L)
    private val lastRuntimeJobId = AtomicLong(Long.MIN_VALUE)
    private val evaluations = AtomicLong(0L)
    private val stopsTriggered = AtomicLong(0L)
    private val catastrophesTriggered = AtomicLong(0L)
    private val tpTriggered = AtomicLong(0L)
    private val trailingsTriggered = AtomicLong(0L)
    private val starvations = AtomicLong(0L)
    private val untriggerAttempts = AtomicLong(0L)

    fun evaluate(
        positionId: String,
        mint: String,
        markPx: Double,
        stopPx: Double,
        catastrophePx: Double,
        tpPx: Double,
        trailPx: Double,
        quoteAgeMs: Long,
    ): TriggerKind? {
        lastHeartbeatMs.set(System.currentTimeMillis())
        try { lastRuntimeJobId.set(BackgroundTradingAuthority6469.currentJobId()) } catch (_: Throwable) {}
        evaluations.incrementAndGet()
        if (positionId.isBlank()) return null
        // markPx=0 is a heartbeat-only ping. Never latch on placeholder price.
        if (markPx <= 0.0) return null
        if (latches.containsKey(positionId)) return latches[positionId]?.kind
        val kind: TriggerKind? = when {
            catastrophePx > 0.0 && markPx <= catastrophePx -> TriggerKind.CATASTROPHE
            stopPx > 0.0 && markPx <= stopPx -> TriggerKind.STOP_LOSS
            trailPx > 0.0 && markPx <= trailPx -> TriggerKind.TRAILING_STOP
            tpPx > 0.0 && markPx >= tpPx -> TriggerKind.TAKE_PROFIT
            else -> null
        }
        if (kind != null) latchTrigger(positionId, kind, markPx, quoteAgeMs)
        return kind
    }

    fun latchTrigger(positionId: String, kind: TriggerKind, mark: Double, quoteAgeMs: Long = 0L): Latch {
        val epoch = System.currentTimeMillis()
        val latch = Latch(positionId, epoch, kind, mark, epoch, quoteAgeMs)
        val prior = latches.putIfAbsent(positionId, latch)
        if (prior == null) {
            when (kind) {
                TriggerKind.STOP_LOSS -> stopsTriggered.incrementAndGet()
                TriggerKind.CATASTROPHE -> catastrophesTriggered.incrementAndGet()
                TriggerKind.TAKE_PROFIT -> tpTriggered.incrementAndGet()
                TriggerKind.TRAILING_STOP -> trailingsTriggered.incrementAndGet()
            }
            try {
                ForensicLogger.lifecycle(
                    "PROTECTIVE_EXIT_LATCHED_6450",
                    "positionId=${positionId.take(12)} kind=$kind triggerPx=${"%.8f".format(mark)} quoteAgeMs=$quoteAgeMs",
                )
                PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_LATCHED_6450_$kind")
            } catch (_: Throwable) {}
        }
        return prior ?: latch
    }

    fun isTriggered(positionId: String): Boolean = latches.containsKey(positionId)

    fun latch(positionId: String): Latch? = latches[positionId]

    fun attemptUntrigger(positionId: String, reason: String): Boolean {
        untriggerAttempts.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "PROTECTIVE_EXIT_UNTRIGGER_DENIED_6450",
                "positionId=${positionId.take(12)} reason=${reason.take(40)}",
            )
            PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_UNTRIGGER_DENIED_6450")
        } catch (_: Throwable) {}
        return false
    }

    fun heartbeatAgeMs(): Long {
        val hb = lastHeartbeatMs.get()
        return if (hb == 0L) Long.MAX_VALUE else System.currentTimeMillis() - hb
    }

    fun checkStarvation() {
        // V5.0.6681 — STOP is intentional silence, not starvation.
        val runtimeActive = try { BackgroundTradingAuthority6469.isRuntimeActive() } catch (_: Throwable) { false }
        if (!runtimeActive) return

        // A fresh Start gets a fresh watchdog epoch. Without this, a stopped
        // interval longer than STARVATION_MS immediately produced red alarms
        // before the first mark evaluation of the new runtime.
        val runtimeJobId = try { BackgroundTradingAuthority6469.currentJobId() } catch (_: Throwable) { Long.MIN_VALUE }
        val previousJobId = lastRuntimeJobId.getAndSet(runtimeJobId)
        if (previousJobId != runtimeJobId) {
            lastHeartbeatMs.set(System.currentTimeMillis())
            return
        }

        val age = heartbeatAgeMs()
        if (age > STARVATION_MS && lastHeartbeatMs.get() > 0L) {
            starvations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "PROTECTIVE_EXIT_SCHEDULER_STARVATION_6450",
                    "ageMs=$age threshold=$STARVATION_MS runtimeJobId=$runtimeJobId",
                )
                PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_SCHEDULER_STARVATION_6450")
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val active = try { BackgroundTradingAuthority6469.isRuntimeActive() } catch (_: Throwable) { false }
        val hb = when {
            !active -> "stopped"
            lastHeartbeatMs.get() == 0L -> "never"
            else -> "${heartbeatAgeMs()}ms ago"
        }
        return "hb=$hb eval=${evaluations.get()} SL=${stopsTriggered.get()} CATA=${catastrophesTriggered.get()} " +
            "TP=${tpTriggered.get()} TRAIL=${trailingsTriggered.get()} latched=${latches.size} " +
            "starvations=${starvations.get()} untriggerDenied=${untriggerAttempts.get()}"
    }
}
