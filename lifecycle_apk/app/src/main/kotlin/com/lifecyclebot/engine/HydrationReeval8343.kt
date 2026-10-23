package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8343 §A_DEFERRAL_ENDS_WHEN_THE_DATA_ARRIVES — a candidate refused LANE_LIQ_UNHYDRATED_DEFER / MCAP_UNHYDRATED_DEFER
 * is re-evaluated as soon as the curve / pool liquidity is read, inside the launch window, not left to the next scanner
 * cycle.
 *
 * 5.0.8341: James (8HPCC…) MOONSHOT peak +2,305 %, birth age 2.7 s, liqUsd = 0.0, every native lane
 * LANE_LIQ_UNHYDRATED_DEFER, handoff = EMITTED:MOONSHOT — then nothing: the curve hydrated seconds later and no one asked
 * the lanes again (DARK +14,114 % the same way). 8330 fixed the twin for a missing TokenState (the arrival resumes the
 * same task); this is the same hook for liquidity.
 *
 * HOW. The native desk remembers each UNHYDRATED_DEFER refusal per mint with its lanes and the candidate version
 * ([noteDeferred8343]). Every hydration write — LiquidityTruth8037.hydrate8037 filling a 0 tick, CurveReserves7951 reading
 * the curve, OnChainDepth8038 answering a vault read — calls [onHydrated8343] / [onCurveRead8343] / [onDepthRead8343]:
 * a remembered deferral inside [DEFER_MEMORY_MS_8343] and inside the launch window is QUEUED ([queue8343], pure) with
 * the ORIGINAL version carried, and the hand-off task waiting on it resumes (HandoffReceiverTasks8153.onHydrated8343).
 * A queued mint rides the next cycle's priority window ([targets8343] joins HandoffPriority8190's targets, like a pending
 * receiver task) for its lanes and the grab lane; the desk's next evaluation of it counts EVALUATED and, when any lane
 * turned eligible, ADMITTED ([onEvaluated8343]). Bounded, no fabricated liquidity: a write of 0 queues nothing.
 */
object HydrationReeval8343 {
    const val DEFER_MEMORY_MS_8343 = 5L * 60_000L
    const val QUEUE_TTL_MS_8343 = 120_000L
    private const val MAX_8343 = 2_048

    data class Deferred8343(val mint: String, val lanes: Set<String>, val reason: String, val version: Long, val atMs: Long)
    data class Queued8343(val mint: String, val lanes: Set<String>, val version: Long, val queuedAtMs: Long, val deadlineMs: Long, val liquidityUsd: Double, val source: String)

    private val deferred = ConcurrentHashMap<String, Deferred8343>()
    private val queued = ConcurrentHashMap<String, Queued8343>()
    private val queuedN = AtomicLong(0)
    private val evaluatedN = AtomicLong(0)
    private val admittedN = AtomicLong(0)
    private val outsideWindow = AtomicLong(0)
    private val expired = AtomicLong(0)

    // ── pure ──

    /** Pure: is [reason] a liquidity / mcap not-yet-read deferral (never a judgement)? */
    fun unhydratedDefer8343(reason: String?): Boolean {
        val r = reason?.uppercase()?.replace(' ', '_').orEmpty()
        return r.contains("UNHYDRATED_DEFER") || r.contains("LIQ_UNHYDRATED") || r.contains("MCAP_UNHYDRATED")
    }

    /**
     * Pure: the queued row a hydration write makes from the remembered deferral [d], or null — no deferral, liquidity still
     * unread, the deferral older than [DEFER_MEMORY_MS_8343], or the coin proven past the launch window ([windowMinutes]).
     * The row carries the deferral's own candidate version.
     */
    fun queue8343(d: Deferred8343?, liquidityUsd: Double, ageMinutes: Double?, windowMinutes: Double, nowMs: Long, source: String = "HYDRATE"): Queued8343? {
        if (d == null || !liquidityUsd.isFinite() || !(liquidityUsd > 0.0)) return null
        if (nowMs - d.atMs !in 0L..DEFER_MEMORY_MS_8343) return null
        if (ageMinutes != null && ageMinutes.isFinite() && windowMinutes.isFinite() && ageMinutes > windowMinutes) return null
        return Queued8343(d.mint, d.lanes, d.version, nowMs, nowMs + QUEUE_TTL_MS_8343, liquidityUsd, source)
    }

    /** Pure: a remembered deferral merged with a new lane's refusal (the first version and time stand). */
    fun remember8343(prior: Deferred8343?, mint: String, lane: String, reason: String, version: Long, nowMs: Long): Deferred8343 {
        val l = lane.trim().uppercase()
        if (prior == null || nowMs - prior.atMs > DEFER_MEMORY_MS_8343) return Deferred8343(mint, setOf(l), reason, version, nowMs)
        return prior.copy(lanes = prior.lanes + l, version = if (prior.version > 0L) prior.version else version)
    }

    // ── runtime ──

    /** RunnerGrabLane8343.reviewNative8343: [lane] refused [mint] on an unhydrated input at candidate [version]. */
    fun noteDeferred8343(mint: String, lane: String, reason: String, version: Long, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (deferred.size > MAX_8343) deferred.entries.removeIf { nowMs - it.value.atMs > DEFER_MEMORY_MS_8343 }
        deferred.compute(mint) { _, prior -> remember8343(prior, mint, lane, reason, version, nowMs) }
    }

    private fun ageMinutes(ts: TokenState?, nowMs: Long): Double? =
        if (ts == null) null else try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null }

    private fun window(): Double = try { com.lifecyclebot.v3.V3EngineManager.launchAgeWindowMinutes8140() } catch (_: Throwable) { 360.0 }

    // ── V5.0.8345 §EVERY_LIQUIDITY_WRITE_IS_A_HYDRATION — 5.0.8344 audit: 57 tasks deferred for missing data, 51 without
    // TokenState, 0 resumed. Traced from the write sites: only LiquidityTruth8037.write8343, CurveReserves7951.put and
    // OnChainDepth8038.fetch called here, and the hand-off task was resumed ONLY when a native UNHYDRATED deferral had been
    // remembered and queued. The tick writes that actually fill a coin's liquidity in practice were unhooked: the watchlist
    // intake (admitProtectedMemeIntake, both the fresh and the existing TokenState), the DexScreener pair poll
    // (processTokenCycle), the DexScreener WS frame (DataOrchestrator), the Birdeye overview fallback, the Raydium TVL fill
    // (LaneAgeLiquidityTruth8140) and the verified chain depth (OnChainDepth8038.verifyBeforeBuy). They now write through
    // [writeTick8345], and every arrival asks the task directly ([arrival8345]) whether or not the native desk remembered a
    // deferral. Nothing is invented: a 0 / NaN write hydrates nothing.
    private val whyNotQueued8345 = ConcurrentHashMap<String, AtomicLong>()
    private val tickWrites8345 = ConcurrentHashMap<String, AtomicLong>()   // source -> first-fill writes

    private fun whyNot(why: String) {
        if (whyNotQueued8345.size >= 32 && !whyNotQueued8345.containsKey(why)) return
        whyNotQueued8345.computeIfAbsent(why) { AtomicLong(0) }.incrementAndGet()
    }

    /** Pure: is [usd] a liquidity READING (finite, above 0) that fills an unread tick ([before] <= 0)? */
    fun firstFill8345(before: Double, usd: Double): Boolean = !(before > 0.0) && usd.isFinite() && usd > 0.0

    /**
     * THE tick write for every site that sets ts.lastLiquidityUsd outside LiquidityTruth8037: writes [usd] as the caller
     * decided, and when it fills an unread tick, treats it as the hydration it is. Returns true when the field changed.
     */
    fun writeTick8345(ts: TokenState, usd: Double, source: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val before = ts.lastLiquidityUsd
        ts.lastLiquidityUsd = usd
        if (!firstFill8345(before, usd)) return before != usd
        tickWrites8345.computeIfAbsent(source) { AtomicLong(0) }.incrementAndGet()
        try { arrival8345(ts.mint, ts, usd, nowMs, source) } catch (_: Throwable) {}
        return true
    }

    /** BotService.admitProtectedMemeIntake (max-take semantics, V5.9.769): a higher intake figure writes; returns whether it did. */
    fun intakeLiquidity8345(ts: TokenState, liquidityUsd: Double): Boolean =
        if (liquidityUsd.isFinite() && liquidityUsd > ts.lastLiquidityUsd) writeTick8345(ts, liquidityUsd, "INTAKE") else false

    /** BotService.processTokenCycle: the DexScreener pair poll's figure, unless the 7323 curve-keep rule holds the last positive one. */
    fun pollLiquidity8345(ts: TokenState, pairLiquidityUsd: Double, curveKeep7323: Boolean, keptLabel7323: String = "CURVE_LIQUIDITY_KEPT_ON_ZERO_POLL_7323") {
        if (curveKeep7323) { try { PipelineHealthCollector.labelInc(keptLabel7323) } catch (_: Throwable) {}; return }
        writeTick8345(ts, pairLiquidityUsd, "DEX_PAIR_POLL")
    }

    /** LiquidityTruth8037: the tick liquidity of [ts] was just written from 0 to [liquidityUsd]. */
    fun onHydrated8343(ts: TokenState, liquidityUsd: Double, nowMs: Long = System.currentTimeMillis(), source: String = "HYDRATE_8037") {
        arrival8345(ts.mint, ts, liquidityUsd, nowMs, source)
    }

    /** CurveReserves7951: the curve of [mint] was read ([realSol] real SOL). Two map reads when nothing waits on it. */
    fun onCurveRead8343(mint: String, realSol: Double, nowMs: Long = System.currentTimeMillis()) {
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val usd = if (realSol.isFinite() && realSol > 0.0 && solUsd > 0.0) realSol * solUsd else 0.0
        arrival8345(mint, null, usd, nowMs, "CURVE_READ_7951")
    }

    /** OnChainDepth8038: the pool vaults of [mint] answered with [depthUsd]. */
    fun onDepthRead8343(mint: String, depthUsd: Double, nowMs: Long = System.currentTimeMillis()) {
        arrival8345(mint, null, depthUsd, nowMs, "DEPTH_READ_8038")
    }

    /**
     * V5.0.8345 — one arrival path: the remembered native deferral (if any) is queued for re-evaluation, and the hand-off
     * task awaiting hydration on this mint is resumed whether or not a native deferral was remembered (the task can be
     * deferred for liquidity by the rescue claim / a pre-FDG gate, which the native desk never saw).
     */
    private fun arrival8345(mint: String, ts0: TokenState?, liquidityUsd: Double, nowMs: Long, source: String) {
        if (mint.isBlank()) return
        val d = deferred[mint]
        val queuedNow = if (d != null) enqueue(d, ts0 ?: (try { BotService.status.tokens[mint] } catch (_: Throwable) { null }), liquidityUsd, nowMs, source) else false
        if (!queuedNow && liquidityUsd.isFinite() && liquidityUsd > 0.0) {
            try { HandoffReceiverTasks8153.onHydrated8343(mint, nowMs) } catch (_: Throwable) {}
        }
    }

    private fun enqueue(d: Deferred8343, ts: TokenState?, liquidityUsd: Double, nowMs: Long, source: String): Boolean {
        if (queued[d.mint]?.let { nowMs <= it.deadlineMs } == true) { whyNot("ALREADY_QUEUED"); return false }
        val age = ageMinutes(ts, nowMs)
        val row = queue8343(d, liquidityUsd, age, window(), nowMs, source)
        if (row == null) {
            if (age != null && age.isFinite() && age > window()) { outsideWindow.incrementAndGet(); deferred.remove(d.mint); whyNot("OUTSIDE_LAUNCH_WINDOW") }
            else if (!(liquidityUsd.isFinite() && liquidityUsd > 0.0)) whyNot("LIQUIDITY_STILL_UNREAD")
            else whyNot("DEFERRAL_OLDER_THAN_MEMORY")
            return false
        }
        deferred.remove(d.mint)
        if (queued.size > MAX_8343) queued.entries.removeIf { nowMs > it.value.deadlineMs }
        queued[d.mint] = row
        queuedN.incrementAndGet()
        try { HandoffReceiverTasks8153.onHydrated8343(d.mint, nowMs) } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("HYDRATION_REEVAL_QUEUED_8343")
            ForensicLogger.lifecycle("HYDRATION_REEVAL_QUEUED_8343", "mint=${d.mint.take(10)} lanes=${d.lanes.joinToString(",")} version=${d.version} " +
                "liqUsd=${liquidityUsd.toInt()} ageMin=${age?.let { "%.1f".format(it) } ?: "?"} source=$source deferredFor=${d.reason.take(48)} action=reevaluate_next_cycle_priority_window")
        } catch (_: Throwable) {}
        return true
    }

    /** V5.0.8345 — the first-fill tick writes by source and the queue refusals by reason (tests and the status line). */
    fun hydrationCounts8345(): Pair<Map<String, Long>, Map<String, Long>> =
        tickWrites8345.mapValues { it.value.get() } to whyNotQueued8345.mapValues { it.value.get() }

    /** HandoffPriority8190: the queued mints (mint -> deadline) that ride the next cycle's priority window. */
    fun targets8343(nowMs: Long = System.currentTimeMillis()): List<Pair<String, Long>> {
        val live = ArrayList<Pair<String, Long>>()
        for ((m, q) in queued) {
            if (nowMs <= q.deadlineMs) live.add(m to q.deadlineMs)
            else { queued.remove(m, q); expired.incrementAndGet() }
        }
        return live.sortedBy { it.second }.take(HandoffPriority8190.MAX_TARGETS_8190)
    }

    /** The version a queued re-evaluation of [mint] carries, or null when none is queued. */
    fun carriedVersion8343(mint: String, nowMs: Long = System.currentTimeMillis()): Long? =
        queued[mint]?.takeIf { nowMs <= it.deadlineMs }?.version

    /** The desk evaluated [mint] ([anyEligible]: some lane turned eligible): a queued re-evaluation is consumed. */
    fun onEvaluated8343(mint: String, anyEligible: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val q = queued.remove(mint) ?: return
        evaluatedN.incrementAndGet()
        if (anyEligible) admittedN.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (anyEligible) "HYDRATION_REEVAL_ADMITTED_8343" else "HYDRATION_REEVAL_EVALUATED_8343")
            ForensicLogger.lifecycle("HYDRATION_REEVAL_EVALUATED_8343", "mint=${mint.take(10)} version=${q.version} lanes=${q.lanes.joinToString(",")} " +
                "waitedMs=${nowMs - q.queuedAtMs} eligible=$anyEligible source=${q.source}")
        } catch (_: Throwable) {}
    }

    fun statusLine8343(nowMs: Long = System.currentTimeMillis()): String {
        val (writes, notQueued) = hydrationCounts8345()  // V5.0.8345
        return "hydrationReeval8343[queued=${queuedN.get()} evaluated=${evaluatedN.get()} admitted=${admittedN.get()} pending=${queued.count { nowMs <= it.value.deadlineMs }} " +
            "deferredRemembered=${deferred.size} outsideWindow=${outsideWindow.get()} expired=${expired.get()} " +
            "tickWrites8345{${com.lifecyclebot.engine.truth.EvidenceShadow8344.countLine8345(writes)}} whyNotQueued8345{${com.lifecyclebot.engine.truth.EvidenceShadow8344.countLine8345(notQueued)}}]"
    }

    internal fun resetForTests8343() {
        deferred.clear(); queued.clear(); queuedN.set(0); evaluatedN.set(0); admittedN.set(0); outsideWindow.set(0); expired.set(0)
        whyNotQueued8345.clear(); tickWrites8345.clear()
    }
}
