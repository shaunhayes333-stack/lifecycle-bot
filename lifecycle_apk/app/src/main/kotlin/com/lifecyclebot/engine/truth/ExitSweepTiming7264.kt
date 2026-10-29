package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7264 §HOW_LONG_DOES_A_SWEEP_ACTUALLY_TAKE.
 *
 * Operator 5.0.7263, paper, 36 open positions in 17 minutes:
 *
 *   Exit sweep start/done:      5 / 5  (reset=5)
 *   ExitCoordinator stale resets: 5    (should be ~0)
 *   EXIT_COORDINATOR_STALE_HEARTBEAT_REPLACED_7057  40
 *   EXIT_COORDINATOR_REPLACEMENT_AWAITING_DISPATCH_7180  30
 *   PRICE_FALLBACK_ALL_LIVE_SOURCES_FAILED_6914  10809
 *
 * Five full sweeps in seventeen minutes against a 30-second cadence. This
 * subsystem has been repaired eight times (6647, 6897, 7057, 7067, 7121,
 * 7179, 7180, 7213) and every repair reasoned about liveness, relaunching
 * and lease age — never about the one number that would settle it: how long
 * the sweep body takes per position when the price providers are refusing.
 * UNIVERSAL_SL_SWEEP_SUMMARY_6402 writes elapsedMs to the forensic log, which
 * is not on the report. This object holds the last, worst and mean sweep
 * duration, the positions it covered, and the slow-position count, so the
 * next snapshot says whether the coordinator is dead or merely doing 36
 * synchronous provider round-trips on one thread.
 *
 * Read-only telemetry. No gate reads it.
 */
object ExitSweepTiming7264 {
    private val sweeps = AtomicLong(0L)
    private val lastElapsedMs = AtomicLong(0L)
    private val maxElapsedMs = AtomicLong(0L)
    private val totalElapsedMs = AtomicLong(0L)
    private val lastSeen = AtomicLong(0L)
    private val lastEvaluated = AtomicLong(0L)
    private val lastDeferred = AtomicLong(0L)
    private val lastEndedAtMs = AtomicLong(0L)
    private val slowPositions = AtomicLong(0L)
    private val maxPositionMs = AtomicLong(0L)

    fun onSweep(elapsedMs: Long, seen: Int, evaluated: Int, deferred: Int) {
        sweeps.incrementAndGet()
        lastElapsedMs.set(elapsedMs)
        totalElapsedMs.addAndGet(elapsedMs.coerceAtLeast(0L))
        maxElapsedMs.accumulateAndGet(elapsedMs) { a, b -> maxOf(a, b) }
        lastSeen.set(seen.toLong()); lastEvaluated.set(evaluated.toLong()); lastDeferred.set(deferred.toLong())
        lastEndedAtMs.set(System.currentTimeMillis())
    }

    fun onSlowPosition(elapsedMs: Long) {
        slowPositions.incrementAndGet()
        maxPositionMs.accumulateAndGet(elapsedMs) { a, b -> maxOf(a, b) }
    }

    /**
     * V5.0.7270 — the hot-exit tick (openPositionTickLoop) feeds the heartbeat
     * that maybeHealHotExit reads. 7267: five stale resets at LOCK_AGE_>=10s
     * while the universal sweep took 3–22 ms — so the 10 s went missing in
     * this loop, whose fan-out blocks for up to 4 s and whose serial chains
     * run after it. Gap between iteration starts, and the fan-out's own
     * duration, are the two numbers that settle where.
     */
    private const val HOT_TICK_SLOW_MS_7270 = 5_000L
    private val hotTicks = AtomicLong(0L)
    private val lastHotTickStartMs = AtomicLong(0L)
    private val lastHotTickGapMs = AtomicLong(0L)
    private val maxHotTickGapMs = AtomicLong(0L)
    private val slowHotTicks = AtomicLong(0L)
    private val fanouts = AtomicLong(0L)
    private val lastFanoutMs = AtomicLong(0L)
    private val maxFanoutMs = AtomicLong(0L)
    private val totalFanoutMs = AtomicLong(0L)

    // V5.0.7283 §THE LOOP THAT TICKED FORTY-THREE TIMES IN TWENTY MINUTES.
    //
    // 5.0.7281 at 1192 s: hotTicks7270=43 lastGapMs=1003 maxGapMs=5001
    // slowTicks=3, 39 held, 33 with no fresh mark. Read together: every
    // iteration that FINISHED finished inside five seconds, and the loop
    // last started an iteration around the fiftieth second of the run. The
    // forty-third iteration never ended. Nothing recorded that, because this
    // object gauged the gap between starts and nothing at the end; an
    // iteration that does not return leaves no gap. 7271 (48 ticks in 352 s)
    // was the same shape, read then as a starved thread pool. Other runs
    // reached 1568 ticks, so it is intermittent and the block point is not
    // known from here. The iteration now announces its phase as it goes, the
    // thread it started on is remembered, and the in-flight time is on the
    // report, so the next snapshot names the phase and the frame it stopped
    // in instead of leaving a count to be read as a cadence.
    @Volatile private var hotTickPhase7283: String = "-"
    @Volatile private var hotTickThread7283: Thread? = null
    private val hotTickEndedAtMs7283 = AtomicLong(0L)
    // V5.0.7288 — iterations are matched by sequence number, not by clock.
    // 7283 compared end time >= start time, and an iteration that starts in
    // the same millisecond the previous one ended reads as already ended: on
    // 5.0.7287 the fifth iteration hung for 27 minutes with inFlightMs=0 and
    // the supervisor never saw it.
    private val hotTickStartSeq7288 = AtomicLong(0L)
    private val hotTickEndSeq7288 = AtomicLong(0L)
    private val hotTickStalls7283 = AtomicLong(0L)
    @Volatile private var lastStall7283: String = "-"

    fun onHotTickPhase7283(phase: String) { hotTickPhase7283 = phase }

    fun onHotTickEnd7283(nowMs: Long) {
        hotTickEndedAtMs7283.set(nowMs)
        hotTickEndSeq7288.set(hotTickStartSeq7288.get())
        hotTickPhase7283 = "idle"
    }

    /** Milliseconds the current iteration has been running; 0 when none is. */
    fun hotTickInFlightMs7283(nowMs: Long): Long {
        val start = lastHotTickStartMs.get()
        if (start <= 0L) return 0L
        if (hotTickEndSeq7288.get() >= hotTickStartSeq7288.get()) return 0L
        return (nowMs - start).coerceAtLeast(0L)
    }

    fun hotTickPhase7283(): String = hotTickPhase7283

    /**
     * The top frames of the thread the running iteration started on. `onLoop`
     * says whether that thread is still inside the loop (blocked there) or has
     * been handed other work, which means the coroutine is parked in a suspend
     * call that never resumed — two different defects, told apart here.
     */
    fun hotTickThreadTop7283(frames: Int): String {
        val t = hotTickThread7283 ?: return "thread=none"
        val trace = try { t.stackTrace } catch (_: Throwable) { return "thread=${t.name} trace=unavailable" }
        val onLoop = trace.any { it.methodName.contains("openPositionTickLoop") }
        val head = trace.take(frames.coerceAtLeast(1)).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        return "thread=${t.name} state=${t.state} onLoop=$onLoop top=[$head]"
    }

    fun onHotTickStall7283(state: String, phase: String, inFlightMs: Long, top: String) {
        hotTickStalls7283.incrementAndGet()
        lastStall7283 = "$state phase=$phase inFlightMs=$inFlightMs $top"
    }

    // V5.0.8418 §THE_GAP_WAS_A_STOPPED_BOT_AND_THE_SLOW_TICKS_WERE_IDLE_ONES.
    //
    // 5.0.8416 (3.7 h live): maxGapMs=6,505,712 and slowTicks=1,201 of 1,202. Read together with the same report:
    // BOT_STOP_REQUESTED=1 / BOT_START_REQUESTED=2 and Awake tickGapMax=6502 s — the bot loop's own gap, identical to
    // the second. The owner stopped the bot for ~108 minutes; the loop ended (`while (status.running)`), and the first
    // iteration after the restart measured its gap from the last iteration before the stop. Not one held position went
    // unchecked in that window: there was no runtime. And with no open positions the loop sleeps IDLE_MS (5 s) between
    // iterations by design, so every idle gap (~5.4 s) crossed the 5 s "slow" bar. Both numbers described the stop and
    // the idle cadence, not a starved exit path. Now: a runtime start re-bases the gauge ([onRuntimeStart8418]); an
    // iteration that had nothing to price says so ([onHotTickIdle8418]) and the gap after it is held to the idle cadence;
    // the gap that matters — after an iteration that priced held positions — is reported on its own (maxHeldGapMs8418).
    private const val HOT_TICK_IDLE_SLOW_MS_8418 = 10_000L
    @Volatile private var lastTickIdle8418 = false
    @Volatile private var currentTickIdle8418 = false
    private val idleTicks8418 = AtomicLong(0L)
    private val maxHeldGapMs8418 = AtomicLong(0L)
    private val runtimeStarts8418 = AtomicLong(0L)

    /** Pure: is a gap of [gapMs] after an iteration that was [prevIdle] (no open positions) a slow one? */
    fun slowHotTickGap8418(gapMs: Long, prevIdle: Boolean): Boolean {
        // V5.0.8418 — threshold in its own val (house rule: no `x >= (if …)` comparison).
        val slowMs8418 = if (prevIdle) HOT_TICK_IDLE_SLOW_MS_8418 else HOT_TICK_SLOW_MS_7270
        return gapMs >= slowMs8418
    }

    /** The running iteration found no open position and will sleep the idle cadence. */
    fun onHotTickIdle8418() {
        if (!currentTickIdle8418) idleTicks8418.incrementAndGet()
        currentTickIdle8418 = true
    }

    /** A bot start: the next iteration's gap is not measured from the last iteration of the previous run. */
    fun onRuntimeStart8418() {
        runtimeStarts8418.incrementAndGet()
        lastHotTickStartMs.set(0L)
        lastTickIdle8418 = false
        currentTickIdle8418 = false
    }

    fun maxHeldGapMs8418(): Long = maxHeldGapMs8418.get()

    fun onHotTickStart(nowMs: Long) {
        hotTickStartSeq7288.set(hotTicks.incrementAndGet())
        hotTickThread7283 = Thread.currentThread()
        hotTickPhase7283 = "start"
        lastTickIdle8418 = currentTickIdle8418
        currentTickIdle8418 = false
        val prev = lastHotTickStartMs.getAndSet(nowMs)
        if (prev <= 0L) return
        val gap = (nowMs - prev).coerceAtLeast(0L)
        lastHotTickGapMs.set(gap)
        maxHotTickGapMs.accumulateAndGet(gap) { a, b -> maxOf(a, b) }
        if (!lastTickIdle8418) maxHeldGapMs8418.accumulateAndGet(gap) { a, b -> maxOf(a, b) }
        if (slowHotTickGap8418(gap, lastTickIdle8418)) {
            slowHotTicks.incrementAndGet()
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("OPEN_POS_TICK_GAP_SLOW_7270")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "OPEN_POS_TICK_GAP_SLOW_7270",
                    "gapMs=$gap thresholdMs=${if (lastTickIdle8418) HOT_TICK_IDLE_SLOW_MS_8418 else HOT_TICK_SLOW_MS_7270} prevIdle8418=$lastTickIdle8418 lastFanoutMs=${lastFanoutMs.get()} " +
                        "action=hot_exit_heartbeat_starved_by_this_iteration",
                )
            } catch (_: Throwable) {}
        }
    }

    fun onFanout(elapsedMs: Long) {
        fanouts.incrementAndGet()
        lastFanoutMs.set(elapsedMs)
        totalFanoutMs.addAndGet(elapsedMs.coerceAtLeast(0L))
        maxFanoutMs.accumulateAndGet(elapsedMs) { a, b -> maxOf(a, b) }
    }

    fun statusLine(): String {
        val n = sweeps.get()
        val mean = if (n == 0L) 0L else totalElapsedMs.get() / n
        val ago = if (lastEndedAtMs.get() == 0L) -1L else System.currentTimeMillis() - lastEndedAtMs.get()
        val perPos = if (lastEvaluated.get() == 0L) 0L else lastElapsedMs.get() / lastEvaluated.get()
        val f = fanouts.get()
        val fanMean = if (f == 0L) 0L else totalFanoutMs.get() / f
        return "sweeps=$n lastMs=${lastElapsedMs.get()} meanMs=$mean maxMs=${maxElapsedMs.get()} " +
            "lastSeen=${lastSeen.get()} lastEvaluated=${lastEvaluated.get()} lastDeferred=${lastDeferred.get()} " +
            "perPositionMs=$perPos slowPositions=${slowPositions.get()} worstPositionMs=${maxPositionMs.get()} lastEndedAgoMs=$ago " +
            "| hotTicks7270=${hotTicks.get()} lastGapMs=${lastHotTickGapMs.get()} maxGapMs=${maxHotTickGapMs.get()} slowTicks=${slowHotTicks.get()} " +
            "maxHeldGapMs8418=${maxHeldGapMs8418.get()} idleTicks8418=${idleTicks8418.get()} runtimeStarts8418=${runtimeStarts8418.get()} " +
            "fanouts=$f fanLastMs=${lastFanoutMs.get()} fanMeanMs=$fanMean fanMaxMs=${maxFanoutMs.get()} " +
            "| inFlightMs7283=${hotTickInFlightMs7283(System.currentTimeMillis())} phase=$hotTickPhase7283 " +
            "stalls=${hotTickStalls7283.get()} lastStall=$lastStall7283 " +
            (try { com.lifecyclebot.engine.HotExitWatchdog8418.statusLine8418() } catch (_: Throwable) { "hotExitWatchdog8418 -" }) +
            " " + (try { com.lifecyclebot.engine.RunnerTrail8419.statusLine8419() } catch (_: Throwable) { "runnerTrail8419 -" })   // V5.0.8419
    }
}
