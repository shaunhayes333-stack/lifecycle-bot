package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 — EXITS THAT DESTROYED THE EDGE.
 *
 * 5.0.8168 live, 9 minutes: the admitted cohort's forward return read net +58.4% (n876) while the realised book read
 * WR 20% / PF 0.34; forward labels stoppedBeforePeak=750 against stoppedAfterPeak=314; a MOONSHOT buy was sold 9 s
 * later as RAPID_CATASTROPHE_STOP; bank8153 grossOppSol=0.6264 against netBankedSol=-0.0050. Four defects:
 *
 *  1. STOP WIDTH. StopLearner8040 picked 5% for BLUECHIP and QUALITY (the Field Manual's invalidation for those lanes
 *     is 10%), and no stop knew the token's own 1-minute range or the round trip it paid to get in. A stop inside
 *     cost + ordinary noise fires on noise. [floorStopMag8169]: the lane's Field Manual invalidation is the floor of
 *     the lane base (learned or constant), and the position's stop is never inside its round-trip cost plus
 *     [NOISE_MULT_8169] x its measured 1-minute range. Clamped at StopAuthority7887's 25%.
 *  2. BACKSTOPS SHALLOWER THAN THE STOP. The rapid monitor's "catastrophe" read FluidLearningAI's bootstrap -15%, the
 *     same as its -15% hard floor, both under the ordinary stop of every runner lane (15-25%) and far under a
 *     lifecycle hold's -40% floor — and CATASTROPH bypasses every hold (ProtectiveExitClass7807 rank 1). The Executor's
 *     risk check cut fresh memes at -9% for their first five minutes, and LosingPatternMemory tightened MOONSHOT to
 *     -3/-5/-7% from losses those same exits produced. [hardFloorMag8169] / [catastropheMag8169]: the hard floor sits
 *     [HARD_FLOOR_MARGIN_PCT_8169] below the ordinary stop (15..30%), the catastrophe never above the hard floor nor
 *     above a lifecycle hold's own floor; [predictiveSlPct8169]: a learned tightening never goes inside the lane's
 *     Field Manual invalidation. Rug / dev-dump / liquidity-pull / honeypot / freeze exits are other reasons and are
 *     untouched.
 *  3. RUNNER LOCK DEFERRED FOREVER. Under a runner lane's give-back arm peak (MOONSHOT +50%) FieldManual7715's
 *     runnerAwareStop7935 floors a winner at net break-even, but the 500 ms monitor then discarded every POSITIVE stop
 *     of a deferred runner (`!(stop > 0 && runnerDefer)`), so nothing protected it: RUNNER_GIVEBACK_LOCK_DEFERRED=5183.
 *     [runnerProtectFloor8169]: a deferred runner keeps net break-even once its peak cleared it, and half its peak from
 *     [RUNNER_PROTECT_PEAK_PCT_8169] (the from-entry give-back the forward labels grade runner lanes on,
 *     RunnerGrab7967.fromEntryGiveback8104); [fires8169]: that protective floor fires, a sliding lock above it still
 *     waits for the arm peak.
 *  4. STALE REFUSALS — see ForwardReturnLabeler7731.freshCellStatFor8169 / CandidateAdmission8136 (cellLoss8169).
 */
object ExitDoctrine8169 {
    /** A stop must clear this many measured 1-minute ranges (plus the round trip) to stand outside ordinary noise. */
    private const val NOISE_MULT_8169 = 2.0
    /** Candles (1m, real high and low) needed before a range is a measurement. */
    private const val MIN_NOISE_CANDLES_8169 = 3
    private const val NOISE_WINDOW_8169 = 12
    /** StopAuthority7887's ceiling: the ordinary stop never goes deeper. */
    private const val STOP_MAX_PCT_8169 = 25.0
    private const val HARD_FLOOR_BASE_PCT_8169 = 15.0
    private const val HARD_FLOOR_MARGIN_PCT_8169 = 5.0
    private const val HARD_FLOOR_MAX_PCT_8169 = 30.0
    /** The from-entry runner give-back band starts here (RunnerGrab7967.fromEntryGiveback8104: +40..+199%). */
    private const val RUNNER_PROTECT_PEAK_PCT_8169 = 40.0
    private const val RUNNER_KEEP_FRACTION_8169 = 0.5
    /** FieldManual7715.runnerAwareStop7935: net break-even + this margin before a runner is floored at break-even. */
    private const val RUNNER_BREAKEVEN_MARGIN_PCT_8169 = 6.0
    private const val COST_REFRESH_MS_8169 = 60_000L
    private const val MAX_TRACKED_8169 = 1_000

    // ── pure ──

    /** Pure: a 1-minute candle's range in % of its price; NaN when its high / low were never observed. */
    fun candleRangePct8169(priceUsd: Double, highUsd: Double, lowUsd: Double): Double {
        if (!(priceUsd > 0.0) || !(highUsd > 0.0) || !(lowUsd > 0.0) || highUsd < lowUsd) return Double.NaN
        val r = (highUsd - lowUsd) / priceUsd * 100.0
        return if (r.isFinite() && r >= 0.0) r else Double.NaN
    }

    /** Pure: the mean of the newest [NOISE_WINDOW_8169] measured ranges; NaN under [MIN_NOISE_CANDLES_8169]. */
    fun noisePct8169(rangesPct: List<Double>): Double {
        val r = rangesPct.filter { it.isFinite() && it >= 0.0 }.takeLast(NOISE_WINDOW_8169)
        return if (r.size < MIN_NOISE_CANDLES_8169) Double.NaN else r.average()
    }

    /**
     * Pure: the ordinary stop magnitude [stopMag] (positive) raised to the lane's Field Manual invalidation [doctrinePct]
     * (NaN = not applied) and to the round trip [costPct] + [NOISE_MULT_8169] x the measured 1-minute range [noisePct]
     * (NaN = unknown, not applied), never past [STOP_MAX_PCT_8169]. A stop already wider is returned as it is.
     */
    fun floorStopMag8169(stopMag: Double, doctrinePct: Double, noisePct: Double, costPct: Double): Double {
        if (!stopMag.isFinite() || stopMag <= 0.0) return stopMag
        var floor = 0.0
        if (doctrinePct.isFinite() && doctrinePct > 0.0) floor = doctrinePct
        if (noisePct.isFinite() && noisePct > 0.0) {
            val cost = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
            floor = maxOf(floor, cost + NOISE_MULT_8169 * noisePct)
        }
        if (stopMag >= floor) return stopMag
        return maxOf(stopMag, floor.coerceAtMost(STOP_MAX_PCT_8169))
    }

    /** Pure: what raised the stop — "DOCTRINE", "NOISE" or "" (nothing). */
    fun floorSource8169(stopMag: Double, doctrinePct: Double, noisePct: Double, costPct: Double): String {
        val out = floorStopMag8169(stopMag, doctrinePct, noisePct, costPct)
        if (!(out > stopMag)) return ""
        val withDoctrineOnly = floorStopMag8169(stopMag, doctrinePct, Double.NaN, Double.NaN)
        return if (out > withDoctrineOnly) "NOISE" else "DOCTRINE"
    }

    /** Pure: the rapid hard floor (positive): [HARD_FLOOR_MARGIN_PCT_8169] below the ordinary stop, 15..30%. */
    fun hardFloorMag8169(ordinaryStopMag: Double): Double =
        if (!ordinaryStopMag.isFinite() || ordinaryStopMag <= 0.0) HARD_FLOOR_BASE_PCT_8169
        else (ordinaryStopMag + HARD_FLOOR_MARGIN_PCT_8169).coerceIn(HARD_FLOOR_BASE_PCT_8169, HARD_FLOOR_MAX_PCT_8169)

    /**
     * Pure: the catastrophe line (positive): the fluid catastrophe [fluidPct] (negative %), never shallower than the hard
     * floor [hardMag], nor than a lifecycle hold's own floor [lifecycleFloorMag] (NaN = the position is not held).
     */
    fun catastropheMag8169(fluidPct: Double, hardMag: Double, lifecycleFloorMag: Double): Double {
        var mag = if (fluidPct.isFinite() && fluidPct < 0.0) -fluidPct else 25.0
        if (hardMag.isFinite()) mag = maxOf(mag, hardMag)
        if (lifecycleFloorMag.isFinite() && lifecycleFloorMag > 0.0) mag = maxOf(mag, lifecycleFloorMag)
        return mag
    }

    /** Pure: a learned tightening [predictivePct] (negative %) never inside the lane's invalidation [doctrinePct]. */
    fun predictiveSlPct8169(predictivePct: Double?, doctrinePct: Double): Double? {
        if (predictivePct == null || !predictivePct.isFinite()) return predictivePct
        if (!doctrinePct.isFinite() || doctrinePct <= 0.0) return predictivePct
        return minOf(predictivePct, -doctrinePct)
    }

    /**
     * Pure: the floor (pnl %) a give-back-deferred runner keeps at peak [peakPct] with round trip [costPct], or NaN: net
     * break-even (cost + 0.5) once the peak cleared it by [RUNNER_BREAKEVEN_MARGIN_PCT_8169], and from
     * [RUNNER_PROTECT_PEAK_PCT_8169] at least [RUNNER_KEEP_FRACTION_8169] of the peak.
     */
    fun runnerProtectFloor8169(peakPct: Double, costPct: Double): Double {
        if (!peakPct.isFinite()) return Double.NaN
        val be = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + 0.5
        if (peakPct >= RUNNER_PROTECT_PEAK_PCT_8169) return maxOf(be, peakPct * RUNNER_KEEP_FRACTION_8169)
        if (peakPct >= be + RUNNER_BREAKEVEN_MARGIN_PCT_8169) return be
        // V5.0.8337 — a +20% peak keeps at least 40% of the peak, whatever the cost model reads
        if (peakPct >= 20.0) return maxOf(be.coerceAtMost(peakPct * 0.5), peakPct * 0.4)
        return Double.NaN
    }

    /**
     * Pure: may the 500 ms monitor's dynamic stop [stopPct] fire? A loss stop always may. A positive stop on a deferred
     * runner may only when it is that runner's protective floor [protectFloorPct] (a sliding lock above it waits).
     */
    fun positiveStopFires8169(stopPct: Double, runnerDeferred: Boolean, protectFloorPct: Double): Boolean {
        if (!(stopPct > 0.0) || !runnerDeferred) return true
        return protectFloorPct.isFinite() && stopPct <= protectFloorPct + 1e-9
    }

    /** Runner lock states of one position, in order of protection. */
    enum class RunnerLock8169 { UNDER_BREAKEVEN, BREAKEVEN_FLOOR, HALF_PEAK_FLOOR, ARMED }

    /** Pure: the lock state of a runner-lane position at [peakPct]. */
    fun runnerLockState8169(deferred: Boolean, peakPct: Double, costPct: Double): RunnerLock8169 {
        if (!deferred) return RunnerLock8169.ARMED
        if (peakPct.isFinite() && peakPct >= RUNNER_PROTECT_PEAK_PCT_8169) return RunnerLock8169.HALF_PEAK_FLOOR
        return if (runnerProtectFloor8169(peakPct, costPct).isFinite()) RunnerLock8169.BREAKEVEN_FLOOR else RunnerLock8169.UNDER_BREAKEVEN
    }

    /** Pure: the lane's Field Manual invalidation (positive %), the floor of any ordinary stop on it. */
    fun doctrinePct8169(lane: String): Double = try {
        com.lifecyclebot.engine.truth.FieldManual7715.mandateFor(lane).invalidationPct
    } catch (_: Throwable) { Double.NaN }

    // ── live adapters ──

    private class PosStop8169(val lane: String, var stop: Double, var noise: Double, var cost: Double, var costAtMs: Long, var raisedBy: String)

    private val positions = ConcurrentHashMap<String, PosStop8169>()
    /** Lane -> "from->to" of the last base the Field Manual floor raised. */
    private val raisedDoctrine = ConcurrentHashMap<String, String>()
    private val raisedNoise = ConcurrentHashMap<String, AtomicLong>()
    private val runnerLocks = ConcurrentHashMap<String, RunnerLock8169>()
    private val protectFloors = ConcurrentHashMap<String, Double>()
    private val runnerFloorFired = AtomicLong(0)
    private val slidingLockHeld = AtomicLong(0)
    /** Positions (mint|entry) whose backstop this build moved, each counted once. */
    private val hardFloorWidened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val catastropheDeepened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val freshMemeFloorWidened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val predictiveClampReads = AtomicLong(0)
    private val stoppedFirst = ConcurrentHashMap<String, AtomicLong>()
    private val stoppedThenRan = ConcurrentHashMap<String, AtomicLong>()
    private val stoppedAfterPeak = ConcurrentHashMap<String, AtomicLong>()
    private val staleCellDecayed = AtomicLong(0)
    private val staleStageDecayed = AtomicLong(0)

    private fun key(ts: TokenState) = "${ts.mint}|${ts.position.entryTime}"
    private fun mark(set: MutableSet<String>, k: String) { if (set.size >= MAX_TRACKED_8169) set.clear(); set.add(k) }
    private fun bump(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size < 64 || m.containsKey(k)) m.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
    }
    private fun canon(lane: String): String = try {
        com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase()
    } catch (_: Throwable) { "" }.ifBlank { lane.trim().uppercase() }

    /** The token's measured 1-minute range (%), NaN when not yet measured. */
    private fun measuredNoisePct8169(ts: TokenState): Double = try {
        val candles = synchronized(ts.history) { ts.history.toList() }
        noisePct8169(candles.takeLast(NOISE_WINDOW_8169).map { candleRangePct8169(it.priceUsd, it.highUsd, it.lowUsd) })
    } catch (_: Throwable) { Double.NaN }

    /** StopAuthority7887 (no plan): the lane base never inside the lane's Field Manual invalidation. */
    fun doctrineFloored8169(lane: String, stopMag: Double): Double {
        val out = floorStopMag8169(stopMag, doctrinePct8169(lane), Double.NaN, Double.NaN)
        if (out > stopMag) {
            val l = canon(lane)
            if (raisedDoctrine.size < 64 || raisedDoctrine.containsKey(l)) raisedDoctrine[l] = "%.0f->%.0f".format(stopMag, out)
        }
        return out
    }

    /** StopAuthority7887.stopMagFor: the position's stop never inside its round trip + measured noise. Recorded per position. */
    fun noiseCostFloored8169(ts: TokenState, lane: String, stopMag: Double): Double {
        if (!stopMag.isFinite() || stopMag <= 0.0) return stopMag
        val now = System.currentTimeMillis()
        val k = key(ts)
        val prev = positions[k]
        val noise = measuredNoisePct8169(ts)
        // The round trip is priced once a minute per position (the same model + measured cost the sell door uses).
        val reuse = prev != null && now - prev.costAtMs in 0L..COST_REFRESH_MS_8169
        val cost = if (reuse && prev != null) prev.cost else try {
            com.lifecyclebot.engine.truth.NetEdge8030.costPct8030(lane.ifBlank { "UNKNOWN" }, ts.position.costSol,
                com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts), ts.mint, ts.lastMcap)
        } catch (_: Throwable) { Double.NaN }
        val out = floorStopMag8169(stopMag, Double.NaN, noise, cost)
        val l = canon(lane)
        if (prev == null && positions.size >= MAX_TRACKED_8169) positions.clear()
        val rec = prev ?: PosStop8169(l, out, noise, cost, now, "").also { positions[k] = it }
        // Counted once per position, the first time noise + cost raise its stop.
        if (out > stopMag && rec.raisedBy != "NOISE") bump(raisedNoise, l)
        rec.stop = out; rec.noise = noise; rec.cost = cost
        if (!reuse) rec.costAtMs = now
        if (out > stopMag) rec.raisedBy = "NOISE"
        return out
    }

    private fun ordinaryStopMag(ts: TokenState): Double = try {
        com.lifecyclebot.engine.cortex.StopAuthority7887.stopMagFor(ts)
    } catch (_: Throwable) { Double.NaN }

    private fun lifecycleFloor(ts: TokenState): Double =
        if (try { LifecycleHold8065.holding8065(ts) } catch (_: Throwable) { false }) LifecycleHold8065.FLOOR_PCT else Double.NaN

    /** BotService.evaluateRapidMonitorExit: the hard floor (positive) for [ts], 15..30%, below its ordinary stop. */
    fun rapidHardFloorMag8169(ts: TokenState): Double {
        val ord = ordinaryStopMag(ts)
        // A lifecycle hold's 40% floor is its own exit; its rapid hard floor is the cap (held by LifecycleHold8065).
        // V5.0.8298 — the widened floor only on the lane's earned realised record; else 8114's 15% (RegressionRestore8298).
        val mag = restored8298(ts, hardFloorMag8169(ord), HARD_FLOOR_BASE_PCT_8169, "RAPID_HARD_FLOOR")
        if (mag > HARD_FLOOR_BASE_PCT_8169) mark(hardFloorWidened, key(ts))
        return mag
    }

    /** BotService.evaluateRapidMonitorExit: the catastrophe line (negative %) for [ts], never above its hard floor. */
    fun rapidCatastrophePct8169(ts: TokenState, fluidPct: Double): Double {
        val hard8298 = restored8298(ts, hardFloorMag8169(ordinaryStopMag(ts)), HARD_FLOOR_BASE_PCT_8169, "RAPID_CATASTROPHE")
        val mag = catastropheMag8169(fluidPct, hard8298, lifecycleFloor(ts))
        if (fluidPct.isFinite() && mag > -fluidPct + 1e-9) mark(catastropheDeepened, key(ts))
        return -mag
    }

    /** Executor.riskCheck: the hard floor (positive) — a fresh meme keeps its ordinary stop, not a flat 9%. */
    fun riskHardFloorMag8169(ts: TokenState, freshMeme: Boolean): Double {
        val ord = ordinaryStopMag(ts)
        // V5.0.8298 — 8114's 9% (fresh meme) / 15% backstops bind until the lane earned the wider ones (RegressionRestore8298).
        if (!freshMeme) return restored8298(ts, hardFloorMag8169(ord), HARD_FLOOR_BASE_PCT_8169, "RISK_HARD_FLOOR")
        val widened = if (ord.isFinite() && ord > 0.0) ord.coerceIn(9.0, HARD_FLOOR_MAX_PCT_8169) else 9.0
        val mag = restored8298(ts, widened, com.lifecyclebot.engine.truth.RegressionRestore8298.FRESH_MEME_BACKSTOP_PCT_8298, "FRESH_MEME_BACKSTOP")
        if (mag > 9.0) mark(freshMemeFloorWidened, key(ts))
        return mag
    }

    /** V5.0.8298 — [widened] if the position's lane earned it on realised closes, else the 8114 [base8114] (never wider). */
    private fun restored8298(ts: TokenState, widened: Double, base8114: Double, door: String): Double = try {
        com.lifecyclebot.engine.truth.RegressionRestore8298.backstopFor8298(ts.position.tradingMode, widened, base8114, door)
    } catch (_: Throwable) { minOf(widened, base8114) }

    /** LosingPatternMemory.recommendedSlPct, never inside the lane's Field Manual invalidation. */
    fun recommendedSlPct8169(tradingMode: String, v3Score: Int): Double? {
        val raw = LosingPatternMemory.recommendedSlPct(tradingMode = tradingMode, v3Score = v3Score)
        val out = predictiveSlPct8169(raw, doctrinePct8169(tradingMode))
        if (raw != null && out != null && out < raw) predictiveClampReads.incrementAndGet()
        return out
    }

    /** MoonshotTraderAI.stopFor: the first-12-minute stop (positive) — the position's ordinary stop, 15..25%. */
    fun earlyRunnerStopMag8169(mint: String): Double {
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null }
        val ord = if (ts != null && ts.position.isOpen) ordinaryStopMag(ts) else Double.NaN
        val widened = if (ord.isFinite() && ord > 0.0) ord.coerceIn(15.0, STOP_MAX_PCT_8169) else 15.0
        // V5.0.8298 — 8114 (8030) held MOONSHOT's first 12 minutes at 15%; wider only on the lane's earned realised record.
        return if (ts == null) widened else restored8298(ts, widened, com.lifecyclebot.engine.truth.RegressionRestore8298.BACKSTOP_PCT_8298, "EARLY_RUNNER_STOP")
    }

    /**
     * BotService.fluidStop7928: FieldManual7715.runnerAwareStop7935, plus the deferred runner's protective floor
     * ([runnerProtectFloor8169]); records the position's runner lock state.
     */
    fun runnerAwareStop8169(ts: TokenState, rawStop: Double, peakPnlPct: Double, costPct: Double, runnerDeferred: Boolean,
                            preTrail: () -> Double): Double {
        val base = com.lifecyclebot.engine.truth.FieldManual7715.runnerAwareStop7935(rawStop, peakPnlPct, costPct, runnerDeferred, preTrail)
        val runnerLane = try { RunnerExitProfile7277.isRunnerLane(ts.position.tradingMode) } catch (_: Throwable) { false }
        if (runnerLane || runnerDeferred) {
            if (runnerLocks.size >= MAX_TRACKED_8169) runnerLocks.clear()
            runnerLocks[key(ts)] = runnerLockState8169(runnerDeferred, peakPnlPct, costPct)
        }
        val k = key(ts)
        val floor = if (runnerDeferred) runnerProtectFloor8169(peakPnlPct, costPct) else Double.NaN
        if (floor.isFinite()) {
            if (protectFloors.size >= MAX_TRACKED_8169) protectFloors.clear()
            protectFloors[k] = floor
        } else protectFloors.remove(k)
        if (!runnerDeferred) return base
        return if (floor.isFinite() && base.isFinite()) maxOf(base, floor) else base
    }

    /** BotService.rapidStopLossMonitor: may this dynamic stop fire on [ts] ([positiveStopFires8169])? */
    fun fires8169(ts: TokenState, stopPct: Double, runnerDeferred: Boolean): Boolean {
        if (!(stopPct > 0.0) || !runnerDeferred) return true
        // The protective floor fluidStop7928 priced for this position on this tick (runnerAwareStop8169).
        val fires = positiveStopFires8169(stopPct, true, protectFloors[key(ts)] ?: Double.NaN)
        if (fires) runnerFloorFired.incrementAndGet() else slidingLockHeld.incrementAndGet()
        return fires
    }

    /** ForwardReturnLabeler7731.captured7945: one label stopped by its lane stop [stopMag], per lane. */
    fun noteGraded8169(lane: String, stopMag: Double, peakPct: Double, dipFirstPct: Double) {
        if (!(stopMag > 0.0) || !dipFirstPct.isFinite()) return
        val l = canon(lane)
        if (dipFirstPct <= -stopMag) {
            bump(stoppedFirst, l)
            // The move it was bought for came after the stop: a shakeout, not a loser.
            if (peakPct.isFinite() && peakPct >= stopMag) bump(stoppedThenRan, l)
        } else bump(stoppedAfterPeak, l)
    }

    /** CandidateAdmission8136: a refusal whose loss record predates the grading epoch decayed to neutral. */
    fun noteStaleDecay8169(cell: Boolean) { if (cell) staleCellDecayed.incrementAndGet() else staleStageDecayed.incrementAndGet() }

    fun statusLine8169(): String {
        val byLane = positions.values.groupBy { it.lane }.entries.sortedBy { it.key }.joinToString(" · ") { (l, ps) ->
            val stops = ps.map { it.stop }.filter { it.isFinite() }
            val noises = ps.map { it.noise }.filter { it.isFinite() }
            val s = if (stops.isEmpty()) "-" else "%.1f%%".format(stops.average())
            val n = if (noises.isEmpty()) "-" else "%.1f%%".format(noises.average())
            val x = if (stops.isNotEmpty() && noises.isNotEmpty() && noises.average() > 0.0) "x%.1f".format(stops.average() / noises.average()) else "x-"
            "$l n${ps.size} stop=$s noise1m=$n $x"
        }.ifBlank { "-" }
        fun m(x: ConcurrentHashMap<String, AtomicLong>) = x.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val lanes = (stoppedFirst.keys + stoppedAfterPeak.keys).toSortedSet().joinToString(",") { l ->
            "$l=${stoppedFirst[l]?.get() ?: 0}/${stoppedThenRan[l]?.get() ?: 0}/${stoppedAfterPeak[l]?.get() ?: 0}"
        }.ifBlank { "-" }
        val locks = RunnerLock8169.values().joinToString(",") { s -> "${s.name}=${runnerLocks.values.count { it == s }}" }
        return "stops8169[$byLane] raised{doctrine=${raisedDoctrine.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}%" }.ifBlank { "-" }} noisePositions=${m(raisedNoise)}} " +
            "labelStops(beforePeak/thenRan/afterPeak)[$lanes] " +
            "backstops(positions)[hardFloorWidened=${hardFloorWidened.size} catastropheDeepened=${catastropheDeepened.size} freshMemeFloorWidened=${freshMemeFloorWidened.size}] predictiveClampReads=${predictiveClampReads.get()} " +
            "runnerLock[$locks floorFired=${runnerFloorFired.get()} slidingHeld=${slidingLockHeld.get()}] " +
            "staleRefusalsDecayed[cell=${staleCellDecayed.get()} stage=${staleStageDecayed.get()}] " +
            (try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.freshStatus8169() } catch (_: Throwable) { "fresh8169=unavailable" }) +
            " rule=stop>=max(laneDoctrine,cost+${NOISE_MULT_8169.toInt()}x1mRange)<=${STOP_MAX_PCT_8169.toInt()}% hardFloor=stop+${HARD_FLOOR_MARGIN_PCT_8169.toInt()}(15..${HARD_FLOOR_MAX_PCT_8169.toInt()}) catastrophe>=hardFloor,lifecycleFloor"
    }
}
