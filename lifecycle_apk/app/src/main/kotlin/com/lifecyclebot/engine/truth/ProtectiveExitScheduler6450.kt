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

    // V5.0.7027 §eval_WAS_NOT_A_COUNT_OF_EVALUATIONS.
    //
    // "Exit scheduler eval=40000 SL=0 CATA=0 TP=0 TRAIL=0" is the single most
    // quoted line in the last six diagnoses of this bot — mine, the operator's
    // and two independent reads of the same snapshot. Every one of them read
    // it as "forty thousand price checks and not one breach", which points at
    // thresholds, at the mark basis, or at the market being flat.
    //
    // It does not say that. `evaluations` is bumped at the TOP of evaluate(),
    // before the markPx<=0 early return, so a caller that has no price at all
    // and pings with zeros to keep the starvation watchdog quiet increments it
    // exactly as much as a caller performing a real threshold comparison. At
    // 100 positions on a 500ms clock the heartbeat alone produces 200 of these
    // a second, which is essentially the whole count.
    //
    // So the number that is supposed to prove the exit engine is working is
    // also the number it produces when it is doing nothing, and the two are
    // indistinguishable. That is the house defect in its purest form: the app
    // treating its own refusal to act as evidence about the outside world.
    //
    // Split, so the status line answers the question it is asked:
    //   armed   = markPx>0 AND at least one threshold was non-zero, i.e. a
    //             comparison actually happened. armed=0 SL=0 means NOTHING WAS
    //             EVER TESTED. armed=40000 SL=0 means tested and never breached.
    //   noMark  = pinged with no usable price.
    //   noThres = priced, but every threshold was zero, so there was nothing to
    //             breach. This is the shape the wall clock had before 6882 and
    //             it must never be silent again.
    private val armed = AtomicLong(0L)
    private val noMark = AtomicLong(0L)
    private val noThreshold = AtomicLong(0L)
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
        if (markPx <= 0.0) {
            noMark.incrementAndGet()
            return null
        }
        if (latches.containsKey(positionId)) return latches[positionId]?.kind
        // V5.0.7027 — a priced ping with four zero thresholds is not an
        // evaluation either; it cannot breach anything. Counted apart from a
        // real comparison so "armed" means what its name says.
        if (catastrophePx <= 0.0 && stopPx <= 0.0 && trailPx <= 0.0 && tpPx <= 0.0) {
            noThreshold.incrementAndGet()
            return null
        }
        armed.incrementAndGet()
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

    /** V5.0.7027 — how many calls actually compared a price to a threshold. */
    fun armedCount7027(): Long = armed.get()

    /** V5.0.7027 — how many calls arrived with no usable mark. */
    fun noMarkCount7027(): Long = noMark.get()

    fun statusLine(): String {
        val hb = if (lastHeartbeatMs.get() == 0L) "never" else "${heartbeatAgeMs()}ms ago"
        // V5.0.7027 — `armed` leads, because it is the number that decides how
        // to read SL/CATA/TP/TRAIL. eval is kept for continuity with older
        // snapshots but it is the total including heartbeats, not the work.
        return "hb=$hb armed=${armed.get()} SL=${stopsTriggered.get()} CATA=${catastrophesTriggered.get()} " +
            "TP=${tpTriggered.get()} TRAIL=${trailingsTriggered.get()} latched=${latches.size} " +
            "eval=${evaluations.get()} noMark=${noMark.get()} noThreshold=${noThreshold.get()} " +
            "starvations=${starvations.get()} untriggerDenied=${untriggerAttempts.get()}"
    }
}
