package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * V5.0.7213 — how long inventory may be open with no price/threshold
     * comparison before the exit engine is reported as not live. Held at the
     * starvation bar deliberately: this is the bar the old merged counter was
     * measured against, so a 7213 snapshot stays comparable to a 7212 one.
     */
    private const val ARMED_STALL_MS = STARVATION_MS

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

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7213 §THE_STARVATION_COUNTER_WAS_COUNTING_AN_EMPTY_WAREHOUSE.
    //
    // Operator directive #8 quotes "heartbeat age ≈251s, threshold=15s,
    // starvation count=486 while bot loop remains healthy" and asks for the
    // exit scheduler to be given independent high-priority cadence. The
    // 5.0.7212 snapshot says something different, and it says it precisely:
    //
    //   Exit scheduler: hb=118369ms ago armed=40 ... eval=43 heartbeats=3
    //                   prunedClosed=0 starvations=213
    //   Risk clock (§6454): running=true ticks=280 lastTick=56ms ago cbFail=0
    //
    // The clock ticked 280 times, on cadence, with zero callback failures.
    // The scheduler received 43 evaluate calls IN TOTAL and none for the last
    // 118 seconds. 280 ticks cannot produce 43 evaluations unless the clock
    // was calling the callback for nobody — and `prunedClosed=0` proves it:
    // pruneClosed7176 early-returns on an empty id set, so it never ran, so
    // CanonicalPositionAuthority6441.openPositions() was EMPTY. Confirming it
    // from the other side, not one RISK_CLOCK_BLOCKED_7001_* or
    // RISK_CLOCK_HEARTBEAT_NO_FRESH_MARK_6882 label appears anywhere in the
    // snapshot: the callback body never executed at all.
    //
    // So the scheduler was not starved of CPU. It was starved of INVENTORY,
    // and the heartbeat could not tell the difference because it was only ever
    // stamped from inside evaluate() — i.e. the liveness signal for the exit
    // engine was "at least one position got evaluated". Those are two
    // different questions and one counter answered both:
    //
    //   - no open positions            -> hb ages -> "STARVATION"   (benign)
    //   - clock wedged, positions open -> hb ages -> "STARVATION"   (critical)
    //
    // The second is the one that can lose real money, and it was hidden inside
    // 213 increments of the first. RunnerLedgerHealthGate6450:43 reads the same
    // age and reports `scheduler_starved` whenever the book is flat, which is
    // exactly when there is nothing to be starved of.
    //
    // It is also a lying number in its own right: checkStarvation runs every
    // 500ms tick and increments on every one, so 213 is ~106 seconds of ONE
    // condition, not 213 events. An earlier snapshot in the same session read
    // starvations=872 with hb=447ms — a healthy scheduler carrying the debris
    // of a seven-minute flat spell.
    //
    // Split, so each counter answers the question it is named for:
    //   lastServiceMs  — the clock serviced this module. Stamped by
    //                    serviceTick7213 on EVERY tick, before any
    //                    per-position work, independently of inventory,
    //                    provider latency and the callback. This is what
    //                    heartbeatAgeMs() now reports, and it is the
    //                    directive's invariant surface:
    //                      serviceAge < 2 x expected cadence.
    //   lastArmedMs    — a real threshold comparison last happened. Stale
    //                    WHILE INVENTORY IS OPEN is the genuine fault, and it
    //                    never had a name before: PROTECTIVE_EXIT_ARMED_STALL.
    //   openServiced   — how many positions the last serviced tick had. -1
    //                    means the clock could not read the open set at all,
    //                    which must never be mistaken for "flat".
    //
    // Nothing about thresholds, latching, dispatch spacing or exit decisions
    // changes here. This build only makes the exit engine able to say which of
    // the two conditions it is in.
    private val lastServiceMs = AtomicLong(0L)
    private val serviceTicks = AtomicLong(0L)
    private val lastArmedMs = AtomicLong(0L)
    private val openServiced = AtomicLong(-1L)
    private val expectedCadenceMs = AtomicLong(0L)
    private val emptyInventoryTicks7213 = AtomicLong(0L)
    private val indeterminateInventoryTicks7213 = AtomicLong(0L)
    private val armedStallEpisodes7213 = AtomicLong(0L)
    private val starvationEpisodes7213 = AtomicLong(0L)
    private val cadenceBreaches7213 = AtomicLong(0L)
    private val worstServiceGapMs7213 = AtomicLong(0L)
    private val inStarvation7213 = AtomicBoolean(false)
    private val inArmedStall7213 = AtomicBoolean(false)
    private val inEmptyInventory7213 = AtomicBoolean(false)

    /**
     * V5.0.7213 — the exit scheduler was serviced by its independent clock.
     *
     * MUST be called once per clock tick BEFORE any per-position work, so that
     * a slow or oversized position sweep can never make the scheduler look
     * starved. [openPositionCount] is the size of the authoritative open set,
     * or -1 when the clock could not read it.
     */
    fun serviceTick7213(openPositionCount: Int, cadenceMs: Long) {
        val now = System.currentTimeMillis()
        val prev = lastServiceMs.getAndSet(now)
        serviceTicks.incrementAndGet()
        openServiced.set(openPositionCount.toLong())
        if (cadenceMs > 0L) expectedCadenceMs.set(cadenceMs)
        if (prev > 0L) {
            val gap = now - prev
            if (gap > worstServiceGapMs7213.get()) worstServiceGapMs7213.set(gap)
            // The directive's invariant, measured at its own source rather
            // than inferred from a stale heartbeat by a downstream reader.
            val budget = if (cadenceMs > 0L) cadenceMs * 2L else STARVATION_MS
            if (gap > budget) {
                cadenceBreaches7213.incrementAndGet()
                try { PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_CADENCE_BREACH_7213") } catch (_: Throwable) {}
            }
        }
        when {
            openPositionCount < 0 -> {
                indeterminateInventoryTicks7213.incrementAndGet()
                try { PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_OPEN_SET_INDETERMINATE_7213") } catch (_: Throwable) {}
            }
            openPositionCount == 0 -> {
                emptyInventoryTicks7213.incrementAndGet()
                // Edge-triggered only. The whole reason `starvations` reached
                // 213 and 872 is that its predecessor logged once per 500ms
                // tick for the duration of a single condition.
                if (inEmptyInventory7213.compareAndSet(false, true)) {
                    try {
                        ForensicLogger.lifecycle(
                            "PROTECTIVE_EXIT_INVENTORY_EMPTY_7213",
                            "note=scheduler_serviced_with_no_canonical_open_positions_nothing_to_evaluate",
                        )
                        PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_INVENTORY_EMPTY_EPISODE_7213")
                    } catch (_: Throwable) {}
                }
            }
            else -> inEmptyInventory7213.set(false)
        }
    }

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
        // V5.0.7213 — an evaluation is also proof of servicing, so producers
        // other than the risk clock (Executor.riskCheck on the scan path) keep
        // the service clock alive too. serviceTick7213 is not called from here:
        // it carries the inventory count, which only the clock holds.
        lastServiceMs.set(System.currentTimeMillis())
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
        // V5.0.7213 — the moment a price was actually compared to a threshold.
        // Staleness of THIS clock while inventory is open is the real fault the
        // old merged starvation counter was hiding.
        lastArmedMs.set(System.currentTimeMillis())
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
        // V5.0.7213 — the `if (openPositionIds.isEmpty()) return` guard that
        // used to stand here was a fail-safe against pruning everything on a
        // bad read, but the read failure and a genuinely flat book arrive as
        // the same empty set (CanonicalRiskClock6454 caught the throw and
        // substituted emptyList()), so it also meant that when the last
        // position closed, its latch and dispatch stamp were never retired —
        // both maps kept the closed position forever. `prunedClosed=0` in the
        // 7212 snapshot is that guard, and it is what proved the open set was
        // empty. The caller now distinguishes a failed read from a flat book
        // and only calls this on a successful one, so an empty set here is
        // legitimate and must prune.
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

    /**
     * V5.0.7213 — age of the SERVICE clock: how long since anything pumped
     * this module. This is the number that answers "is the exit engine being
     * run on cadence", and it is what RunnerLedgerHealthGate6450 and
     * [checkStarvation] both actually want. It no longer goes stale merely
     * because the book is flat.
     */
    fun heartbeatAgeMs(): Long {
        val svc = lastServiceMs.get()
        return if (svc == 0L) Long.MAX_VALUE else System.currentTimeMillis() - svc
    }

    /**
     * V5.0.7213 — age of the last evaluate() call, whatever its outcome.
     * Private: this module's own [statusLine] is the only consumer, and
     * [heartbeatAgeMs] stays the single cross-module liveness surface so a
     * second caller cannot start judging exit health by a different clock.
     */
    private fun evalAgeMs7213(): Long {
        val hb = lastHeartbeatMs.get()
        return if (hb == 0L) Long.MAX_VALUE else System.currentTimeMillis() - hb
    }

    /** V5.0.7213 — age of the last real price-vs-threshold comparison. */
    private fun armedAgeMs7213(): Long {
        val la = lastArmedMs.get()
        return if (la == 0L) Long.MAX_VALUE else System.currentTimeMillis() - la
    }

    /**
     * V5.0.7213 — the operator directive's invariant, answered here rather
     * than re-derived by every reader: the scheduler is being serviced inside
     * twice its expected cadence.
     *
     * Deliberately NOT exposed to RunnerLedgerHealthGate6450. That gate asks
     * "is the exit engine functioning" and the 15s starvation bar is the right
     * one for it; a 1000ms bar would flip its advisory reason on every minor
     * scheduling hiccup, which is how a status line stops being read. The
     * invariant is ENFORCED at its source instead, by the cadence-gap check in
     * [serviceTick7213] and PROTECTIVE_EXIT_CADENCE_BREACH_7213, and READ OUT
     * as svcInvariant in [statusLine].
     */
    private fun serviceInvariantOk7213(): Boolean {
        val svc = lastServiceMs.get()
        if (svc == 0L) return false
        val cadence = expectedCadenceMs.get()
        val budget = if (cadence > 0L) cadence * 2L else STARVATION_MS
        return heartbeatAgeMs() <= budget
    }

    fun checkStarvation() {
        val now = System.currentTimeMillis()
        val svc = lastServiceMs.get()
        if (svc <= 0L) return
        val svcAge = now - svc

        // ── REAL starvation: nothing is servicing this module. Under 7213 the
        // clock stamps serviceTick7213 every tick before touching a single
        // position, so this can now only mean the clock itself is gone, wedged
        // or was never started — which is the condition 6450's mandate was
        // written for and the only one worth waking the operator over.
        if (svcAge > STARVATION_MS) {
            starvations.incrementAndGet()
            if (inStarvation7213.compareAndSet(false, true)) {
                starvationEpisodes7213.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "PROTECTIVE_EXIT_SCHEDULER_STARVATION_6450",
                        "ageMs=$svcAge threshold=$STARVATION_MS cadenceMs=${expectedCadenceMs.get()} " +
                            "openServiced=${openServiced.get()} " +
                            "note=7213_service_clock_not_inventory_this_is_the_real_fault",
                    )
                    PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_SCHEDULER_STARVATION_6450")
                } catch (_: Throwable) {}
            }
            return
        }
        inStarvation7213.set(false)

        // ── ARMED STALL: serviced on cadence, inventory IS open, and yet no
        // price has been compared to a threshold for longer than the starvation
        // bar. Positions are held with no live protective coverage. This is the
        // condition that used to be indistinguishable from a flat book.
        val open = openServiced.get()
        if (open > 0L && armedAgeMs7213() > ARMED_STALL_MS) {
            if (inArmedStall7213.compareAndSet(false, true)) {
                armedStallEpisodes7213.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "PROTECTIVE_EXIT_ARMED_STALL_7213",
                        "openPositions=$open armedAgeMs=${armedAgeMs7213()} threshold=$ARMED_STALL_MS " +
                            "serviceAgeMs=$svcAge " +
                            "note=positions_open_but_no_threshold_comparison_protective_exits_are_not_live",
                    )
                    PipelineHealthCollector.labelInc("PROTECTIVE_EXIT_ARMED_STALL_7213")
                } catch (_: Throwable) {}
            }
        } else {
            inArmedStall7213.set(false)
        }
    }

    /** V5.0.7027 — how many calls actually compared a price to a threshold. */
    fun armedCount7027(): Long = armed.get()

    /** V5.0.7027 — how many calls arrived with no usable mark. */
    fun noMarkCount7027(): Long = noMark.get()

    fun statusLine(): String {
        // V5.0.7213 — `hb` keeps its position and its name so the line stays
        // diffable against every older snapshot, but it now reports the SERVICE
        // clock. The two numbers it used to conflate follow it explicitly, and
        // `openServiced` is printed beside them because without it neither age
        // can be interpreted: armed=0 with openServiced=0 is a flat book, and
        // armed=0 with openServiced=12 is twelve unprotected positions.
        val hb = if (lastServiceMs.get() == 0L) "never" else "${heartbeatAgeMs()}ms ago"
        val evalAge7213 = if (lastHeartbeatMs.get() == 0L) "never" else "${evalAgeMs7213()}ms"
        val armedAge7213 = if (lastArmedMs.get() == 0L) "never" else "${armedAgeMs7213()}ms"
        val openSvc7213 = openServiced.get().let { if (it < 0L) "indeterminate" else it.toString() }
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
            "starvations=${starvations.get()} untriggerDenied=${untriggerAttempts.get()} " +
            // V5.0.7213 — the three questions the single `starvations` figure
            // could not answer. starvations is still a raw per-tick tally for
            // continuity; starvEpisodes is how many times the condition
            // actually began.
            "| svcTicks=${serviceTicks.get()} openServiced=$openSvc7213 " +
            "evalAge=$evalAge7213 armedAge=$armedAge7213 " +
            "cadenceMs=${expectedCadenceMs.get()} svcInvariant=${if (serviceInvariantOk7213()) "OK" else "BREACH"} " +
            "cadenceBreaches=${cadenceBreaches7213.get()} worstSvcGapMs=${worstServiceGapMs7213.get()} " +
            "starvEpisodes=${starvationEpisodes7213.get()} " +
            "armedStalls=${armedStallEpisodes7213.get()} " +
            "emptyInvTicks=${emptyInventoryTicks7213.get()} " +
            "indetInvTicks=${indeterminateInventoryTicks7213.get()}"
    }
}
