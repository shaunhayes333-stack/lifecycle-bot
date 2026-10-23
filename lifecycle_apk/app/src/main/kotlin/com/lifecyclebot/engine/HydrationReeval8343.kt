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

    /** LiquidityTruth8037: the tick liquidity of [ts] was just written from 0 to [liquidityUsd]. */
    fun onHydrated8343(ts: TokenState, liquidityUsd: Double, nowMs: Long = System.currentTimeMillis(), source: String = "HYDRATE_8037") {
        val d = deferred[ts.mint] ?: return
        enqueue(d, ts, liquidityUsd, nowMs, source)
    }

    /** CurveReserves7951: the curve of [mint] was read ([realSol] real SOL). O(1) when nothing was deferred. */
    fun onCurveRead8343(mint: String, realSol: Double, nowMs: Long = System.currentTimeMillis()) {
        val d = deferred[mint] ?: return
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val usd = if (realSol.isFinite() && realSol > 0.0 && solUsd > 0.0) realSol * solUsd else 0.0
        enqueue(d, try { BotService.status.tokens[mint] } catch (_: Throwable) { null }, usd, nowMs, "CURVE_READ_7951")
    }

    /** OnChainDepth8038: the pool vaults of [mint] answered with [depthUsd]. */
    fun onDepthRead8343(mint: String, depthUsd: Double, nowMs: Long = System.currentTimeMillis()) {
        val d = deferred[mint] ?: return
        enqueue(d, try { BotService.status.tokens[mint] } catch (_: Throwable) { null }, depthUsd, nowMs, "DEPTH_READ_8038")
    }

    private fun enqueue(d: Deferred8343, ts: TokenState?, liquidityUsd: Double, nowMs: Long, source: String) {
        if (queued[d.mint]?.let { nowMs <= it.deadlineMs } == true) return
        val age = ageMinutes(ts, nowMs)
        val row = queue8343(d, liquidityUsd, age, window(), nowMs, source)
        if (row == null) {
            if (age != null && age.isFinite() && age > window()) { outsideWindow.incrementAndGet(); deferred.remove(d.mint) }
            return
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
    }

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

    fun statusLine8343(nowMs: Long = System.currentTimeMillis()): String =
        "hydrationReeval8343[queued=${queuedN.get()} evaluated=${evaluatedN.get()} admitted=${admittedN.get()} pending=${queued.count { nowMs <= it.value.deadlineMs }} " +
            "deferredRemembered=${deferred.size} outsideWindow=${outsideWindow.get()} expired=${expired.get()}]"

    internal fun resetForTests8343() {
        deferred.clear(); queued.clear(); queuedN.set(0); evaluatedN.set(0); admittedN.set(0); outsideWindow.set(0); expired.set(0)
    }
}
