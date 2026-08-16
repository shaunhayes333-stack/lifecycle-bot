package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — PROTECTIVE EXIT SCHEDULER.
 *
 * OPERATOR MANDATE:
 *   avgStopMs=689425 (0.05 SOL trades realizing ~0.044-0.048 SOL losses
 *   because stop scheduling latency is minutes, not milliseconds).
 *
 *   "Exit management MUST NOT depend on:
 *     - scanner completion, lane evaluation, FDG workload, learning,
 *       reporting, UI rendering, POST_LEARNING_MAINTENANCE, completion
 *       of the main bot cycle.
 *    Create independent high-priority position-management scheduling.
 *    Once a protective exit triggers, latch it against:
 *      PositionId + exitEpoch + triggerType.
 *    A triggered STOP must NEVER subsequently become FINAL_NO_TRIGGER."
 *
 * DESIGN
 * ──────
 * This module owns the trigger-latching contract and its own heartbeat.
 * Actual exit execution stays in Executor (paperSell / liveSell), but the
 * TRIGGER decision is authoritative here.
 *
 * Contract:
 *   1. Caller emits `evaluate(mint, positionId, mark, stopPx, catastrophePx, tpPx, trailPx)`.
 *   2. If any threshold breaches, `latchTrigger(positionId, kind, mark)` is called.
 *   3. Once latched, `isTriggered(positionId)` returns true forever for that
 *      exitEpoch. Later re-evaluations CANNOT untrigger.
 *   4. Heartbeat is bumped on every evaluate call. Watchdog fires
 *      SCHEDULER_STARVATION_6450 if no heartbeat for > STARVATION_MS.
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

    private val latches = ConcurrentHashMap<String, Latch>() // positionId -> latch
    private val lastHeartbeatMs = AtomicLong(0L)
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
        evaluations.incrementAndGet()
        if (positionId.isBlank()) return null
        // V5.0.6452 §P0-#9 — markPx=0 is a heartbeat-only ping (caller has
        // no fresh mark). Bump heartbeat above but skip trigger logic —
        // NEVER latch on a zero/placeholder price.
        if (markPx <= 0.0) return null
        if (latches.containsKey(positionId)) return latches[positionId]?.kind
        val kind: TriggerKind? = when {
            catastrophePx > 0.0 && markPx <= catastrophePx -> TriggerKind.CATASTROPHE
            stopPx > 0.0 && markPx <= stopPx -> TriggerKind.STOP_LOSS
            trailPx > 0.0 && markPx <= trailPx -> TriggerKind.TRAILING_STOP
            tpPx > 0.0 && markPx >= tpPx -> TriggerKind.TAKE_PROFIT
            else -> null
        }
        if (kind != null) {
            latchTrigger(positionId, kind, markPx, quoteAgeMs)
        }
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

    /**
     * Attempt to untrigger — always denied. Records the attempt as a red-
     * flag so operator can see if any code path is trying to reverse a
     * latched STOP.
     */
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
        if (heartbeatAgeMs() > STARVATION_MS && lastHeartbeatMs.get() > 0L) {
            starvations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "PROTECTIVE_EXIT_SCHEDULER_STARVATION_6450",
                    "ageMs=${heartbeatAgeMs()} threshold=$STARVATION_MS",
                )
                PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_SCHEDULER_STARVATION_6450")
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val hb = if (lastHeartbeatMs.get() == 0L) "never" else "${heartbeatAgeMs()}ms ago"
        return "hb=$hb eval=${evaluations.get()} SL=${stopsTriggered.get()} CATA=${catastrophesTriggered.get()} " +
            "TP=${tpTriggered.get()} TRAIL=${trailingsTriggered.get()} latched=${latches.size} " +
            "starvations=${starvations.get()} untriggerDenied=${untriggerAttempts.get()}"
    }
}
