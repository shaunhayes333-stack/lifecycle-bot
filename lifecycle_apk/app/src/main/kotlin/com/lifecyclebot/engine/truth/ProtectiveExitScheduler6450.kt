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
    // V5.0.7067 — deliberate markPx=0 liveness pings, counted apart from noMark.
    private val heartbeats = AtomicLong(0L)
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
            // V5.0.7067 §A_COUNTER_THAT_MERGES_TWO_THINGS_REPORTS_NEITHER.
            //
            // `noMark` read 173,194 on the operator's 5.0.7065 report against
            // eval=184,139 — 94%, which looks exactly like an exit layer that
            // cannot price anything. It is not. BotService:15444 pings EVERY
            // open canonical position with markPx=0.0 once per cycle BY DESIGN,
            // purely to keep this scheduler's heartbeat alive, and 62 positions
            // over 508 cycles is most of that number.
            //
            // I read the merged counter as a fault and started fixing an exit
            // path that was working: armed=10,880 genuine comparisons had in
            // fact run. A counter that merges an intentional no-op with a real
            // failure will mislead whoever reads it next, so they are split.
            // `heartbeats` is the deliberate ping; `noMark` now means only what
            // its name says — a caller that wanted an evaluation and had no
            // usable price.
            if (stopPx <= 0.0 && catastrophePx <= 0.0 && tpPx <= 0.0 && trailPx <= 0.0) {
                heartbeats.incrementAndGet()
            } else {
                noMark.incrementAndGet()
            }
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
            // V5.0.7176 — seed the dispatch clock at the moment of latching.
            //
            // Every path that latches dispatches its own sell immediately after:
            // Executor.riskCheck sells inline on the scan path, and the risk
            // clock dispatches on the latch transition. Seeding here means a
            // latch made by ONE of those paths cannot be instantly re-dispatched
            // by the other — the retry window opens REDISPATCH_INTERVAL_MS
            // later, by which time a successful sell has closed the position and
            // the risk clock has stopped ticking it entirely.
            //
            // Without this seed, a stop latched by riskCheck would be dispatched
            // again by the very next 500ms clock tick, which is a duplicate sell
            // the old one-shot guard happened to prevent.
            lastDispatchMs[positionId] = epoch
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

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7176 §A_LATCH_IS_NOT_AN_EXECUTION.
    //
    // Operator: "the only time it closes trades is when I do an update."
    //
    // That sentence is a complete diagnosis and it points here. `latches` is
    // an in-memory ConcurrentHashMap with no removal path anywhere in this
    // file — no clear, no release, nothing. The risk-clock callback dispatched
    // its sell under `!alreadyLatched6882`, i.e. ONLY on the tick where the
    // latch first appeared. So a position got exactly ONE sell attempt per
    // PROCESS LIFETIME, and requestSell is launched fire-and-forget on
    // Dispatchers.IO with its return value discarded — only a thrown Throwable
    // is logged. Every ordinary refusal that returns normally (cash-starved,
    // BELOW_MIN_NOTIONAL, ORDER_SIZE_BLOCKED, a MissingMarkExitVeto6835
    // deferral, a StalePriceExitGuard hold) vanished silently, and the latch
    // stayed set forever saying the exit had been handled.
    //
    // Installing an update restarts the process, which clears this map, which
    // gives every open position one fresh attempt — a burst of closes, then
    // silence until the next update. That is exactly the reported symptom, and
    // it is why 72 positions accumulated against 3.2 SOL of free cash.
    //
    // This also VIOLATES THIS MODULE'S OWN MANDATE, quoted at the top of the
    // file: "A triggered STOP must NEVER subsequently become FINAL_NO_TRIGGER."
    // A triggered STOP whose single dispatch was refused became precisely that.
    //
    // The fix keeps the trigger latch exactly as it is — monotonic, never
    // untriggered, still the authority on WHETHER the position must exit — and
    // separates it from the question of whether the exit has actually been
    // EXECUTED. The latch means "this position is committed to exiting"; it
    // must not also mean "we already asked once, so never ask again". So the
    // dispatch becomes a spaced retry that continues until the position is no
    // longer open.
    //
    // Re-dispatch is safe because of where it is called from:
    // CanonicalRiskClock6454 ticks ONLY over
    // CanonicalPositionAuthority6441.openPositions(), so the moment a position
    // actually closes the clock stops ticking it and no further attempt can be
    // made. Nothing here can sell a closed position.
    //
    // No threshold, no lane rule and no protective decision changes. This only
    // makes an exit that was already decided keep being attempted.

    /**
     * Minimum spacing between attempts on the same latched position. Long
     * enough that a refusal is not hammered, short enough that a position
     * freed by an exit ahead of it in the queue is retried promptly.
     */
    private const val REDISPATCH_INTERVAL_MS = 30_000L

    /**
     * positionId -> wallclock of the most recent sell dispatch. Seeded by
     * [latchTrigger], because a latch and its first dispatch are simultaneous
     * by construction; this map therefore only ever gates RETRIES.
     */
    private val lastDispatchMs = ConcurrentHashMap<String, Long>()
    private val redispatches = AtomicLong(0L)
    private val pruned = AtomicLong(0L)

    /**
     * Claim the right to dispatch a sell for a latched position, at most once
     * per [REDISPATCH_INTERVAL_MS]. The claim is atomic, so two clock ticks
     * racing on the same position cannot both dispatch.
     *
     * Returns false for a position that is not latched — the trigger decision
     * still belongs entirely to [evaluate].
     */
    fun shouldDispatch7176(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        if (!latches.containsKey(positionId)) return false
        val now = System.currentTimeMillis()
        val prev = lastDispatchMs[positionId]
        if (prev == null) {
            // Defensive only — latchTrigger seeds this for every latch it
            // creates, so a latched position without a dispatch stamp should
            // not exist. Claim it rather than silently never retrying.
            if (lastDispatchMs.putIfAbsent(positionId, now) != null) return false
        } else {
            if (now - prev < REDISPATCH_INTERVAL_MS) return false
            if (!lastDispatchMs.replace(positionId, prev, now)) return false
        }
        redispatches.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "PROTECTIVE_EXIT_REDISPATCH_7176",
                "positionId=${positionId.take(12)} kind=${latches[positionId]?.kind} " +
                    "sinceLastMs=${if (prev == null) -1L else now - prev} " +
                    "note=latched_exit_had_not_executed_retrying_until_position_closes",
            )
            PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_REDISPATCH_7176")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Drop bookkeeping for positions that are no longer open. Called from the
     * risk clock, which already holds the authoritative open set each tick.
     *
     * A positionId is never reused, so a pruned latch cannot resurrect a
     * decision — this bounds two maps that previously grew for the life of the
     * process.
     */
    fun pruneClosed7176(openPositionIds: Set<String>) {
        if (openPositionIds.isEmpty()) return
        var removed = 0
        val it = latches.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (id !in openPositionIds) {
                it.remove()
                lastDispatchMs.remove(id)
                removed++
            }
        }
        val it2 = lastDispatchMs.keys.iterator()
        while (it2.hasNext()) {
            val id = it2.next()
            if (id !in openPositionIds) {
                it2.remove()
                removed++
            }
        }
        if (removed > 0) pruned.addAndGet(removed.toLong())
    }

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
            "eval=${evaluations.get()} heartbeats=${heartbeats.get()} noMark=${noMark.get()} " +
            "noThreshold=${noThreshold.get()} " +
            // V5.0.7176 — dispatch is now reported apart from the latch.
            // dispatched==latched with redispatched=0 means every latched exit
            // executed first time; redispatched climbing means exits are being
            // refused downstream and retried, which is the condition that used
            // to be invisible and permanent.
            "redispatched=${redispatches.get()} awaitingExec=${latches.size} " +
            "prunedClosed=${pruned.get()} " +
            "starvations=${starvations.get()} untriggerDenied=${untriggerAttempts.get()}"
    }
}
