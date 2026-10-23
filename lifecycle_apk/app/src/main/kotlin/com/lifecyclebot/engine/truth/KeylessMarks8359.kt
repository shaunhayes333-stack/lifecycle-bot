package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8359 §SHADOW_TICKETS_NEED_MARKS — an open exploration / evidence-shadow ticket needs a mark at least every ~30 s or
 * it closes LOST_MARK. The shadow books name the mints they want marked through [noteWanted8359] (mint, untilMs); every
 * keyless mark lane then carries them in priority right after held positions:
 *
 *  - the DexScreener pair batch lane (DexBatchLane8287, 30 mints a call, shared host budget): a wanted mint reads as
 *    NEAR_DECISION in ProviderLanes8285.prioFor8285, and a wanted mint whose price is older than [STALE_MS_8359] is queued
 *    on the lane (CandidateMarkFresh8094.requestLane8359; Jupiter prices what DexScreener does not list);
 *  - the bonding-curve batch read (ParallelMarkFanout7088, one keyless-first getMultipleAccounts for up to 40 curves):
 *    wanted curve mints ride every held-curve call after the held mints, and get their own pass when none ran recently.
 *    The curve's own reserves are the mark (chain state, CandidateMarkFresh8094.applyMarks8359; never on an open position).
 *
 * A pass runs at most every [TICK_MS_8359], on its own daemon thread, triggered by [noteWanted8359] itself (no new loop).
 * Nothing is inferred for a mint no source answered: it simply has no new mark.
 */
object KeylessMarks8359 {
    private const val TICK_MS_8359 = 10_000L
    private const val STALE_MS_8359 = 20_000L
    private const val MAX_WANTED_8359 = 400
    private const val MAX_HOLD_MS_8359 = 6L * 60L * 60_000L
    private const val SOURCE_CURVE_8359 = "PUMP_CURVE_RPC_KEYLESS_8359"

    private val wanted = ConcurrentHashMap<String, Long>()
    private val lastCurveMarkMs = ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(false)
    @Volatile private var lastTickMs = 0L
    private val noted = AtomicLong(0L)
    private val ticks = AtomicLong(0L)
    private val laneQueued = AtomicLong(0L)
    private val curvePasses = AtomicLong(0L)
    private val curveMarks = AtomicLong(0L)

    /**
     * The shadow books' call: [mint] wants a mark until [untilMs] (wall clock). A later call extends it; at most
     * [MAX_HOLD_MS_8359] ahead. Also starts a coverage pass when one is due.
     */
    fun noteWanted8359(mint: String, untilMs: Long) {
        val m = mint.trim()
        if (m.length !in 32..44) return
        val now = System.currentTimeMillis()
        if (untilMs <= now) return
        val until = minOf(untilMs, now + MAX_HOLD_MS_8359)
        wanted.merge(m, until) { a, b -> maxOf(a, b) }
        noted.incrementAndGet()
        if (wanted.size > MAX_WANTED_8359) prune8359(now)
        maybeTick8359(now)
    }

    /** Is [mint] wanted by a shadow book now? */
    fun isWanted8359(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = (wanted[mint] ?: 0L) > nowMs

    /** The wanted mints now, the longest-unmarked first. */
    fun wantedMints8359(nowMs: Long = System.currentTimeMillis()): List<String> =
        wanted.entries.filter { it.value > nowMs }.map { it.key }.sortedBy { lastCurveMarkMs[it] ?: 0L }

    /** Pure: held mints first (their order), then wanted mints not already held, de-duplicated, at most [cap]. */
    fun priorityOrder8359(held: List<String>, wantedMints: List<String>, cap: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (m in held) { if (out.size >= cap) break; if (m.isNotBlank()) out.add(m) }
        for (m in wantedMints) { if (out.size >= cap) break; if (m.isNotBlank()) out.add(m) }
        return out.toList()
    }

    /** Pure: a mint the curve reader can derive a curve for (remembered key or pump suffix) and not graduated. */
    fun curveLike8359(mint: String, hasKey: Boolean, graduated: Boolean): Boolean =
        !graduated && (hasKey || mint.endsWith("pump", ignoreCase = true))

    private fun wantedCurves8359(nowMs: Long): List<String> = wantedMints8359(nowMs).filter { m ->
        val keys = com.lifecyclebot.network.PumpCurveKeys7269
        curveLike8359(m, keys.keyFor(m) != null, keys.isGraduated7392(m))
    }

    /** ParallelMarkFanout7088's curve call: the caller's mints, then the wanted curve mints, up to [cap]. */
    fun withWantedCurves8359(mints: List<String>, cap: Int): List<String> {
        val w = wantedCurves8359(System.currentTimeMillis())
        if (w.isEmpty()) return mints
        return priorityOrder8359(mints, w, maxOf(cap, mints.size))
    }

    /** A curve pass answered: wanted mints among [prices] get the curve's own price as their mark. */
    fun onCurveMarks8359(prices: Map<String, Double>) {
        if (prices.isEmpty() || wanted.isEmpty()) return
        val now = System.currentTimeMillis()
        val mine = prices.filterKeys { isWanted8359(it, now) }.filterValues { it.isFinite() && it > 0.0 }
        if (mine.isEmpty()) return
        for (m in mine.keys) lastCurveMarkMs[m] = now
        curveMarks.addAndGet(mine.size.toLong())
        try { CandidateMarkFresh8094.applyMarks8359(mine, SOURCE_CURVE_8359) } catch (_: Throwable) {}
    }

    /** Pure: is a pass due? */
    fun tickDue8359(nowMs: Long, lastTickMs: Long): Boolean = nowMs - lastTickMs >= TICK_MS_8359

    private fun maybeTick8359(nowMs: Long) {
        if (!tickDue8359(nowMs, lastTickMs) || !running.compareAndSet(false, true)) return
        lastTickMs = nowMs
        try {
            Thread({
                try { tick8359(System.currentTimeMillis()) } catch (_: Throwable) {} finally { running.set(false) }
            }, "keyless-marks-8359").apply { isDaemon = true }.start()
        } catch (_: Throwable) { running.set(false) }
    }

    private fun tick8359(nowMs: Long) {
        ticks.incrementAndGet()
        prune8359(nowMs)
        val want = wantedMints8359(nowMs)
        if (want.isEmpty()) return
        val tokens = try { com.lifecyclebot.engine.BotService.status.tokens } catch (_: Throwable) { null } ?: return
        val stale = want.filter { m ->
            val ts = try { tokens[m] } catch (_: Throwable) { null }
            ts == null || ts.lastPriceUpdate <= 0L || nowMs - ts.lastPriceUpdate > STALE_MS_8359
        }
        if (stale.isNotEmpty()) laneQueued.addAndGet(CandidateMarkFresh8094.requestLane8359(stale, nowMs).toLong())
        val curveDue = wantedCurves8359(nowMs).any { nowMs - (lastCurveMarkMs[it] ?: 0L) > STALE_MS_8359 && it in stale }
        if (curveDue) {
            curvePasses.incrementAndGet()
            try { com.lifecyclebot.network.ParallelMarkFanout7088.wantedCurvePass8359() } catch (_: Throwable) {}
        }
    }

    private fun prune8359(nowMs: Long) {
        wanted.entries.removeIf { it.value <= nowMs }
        if (wanted.size > MAX_WANTED_8359) {
            wanted.entries.sortedBy { it.value }.take(wanted.size - MAX_WANTED_8359).forEach { wanted.remove(it.key) }
        }
        lastCurveMarkMs.keys.removeIf { !wanted.containsKey(it) }
    }

    /** keylessMarks8359[wanted=.. noted=.. ticks=.. laneQueued=.. curvePasses=.. curveMarks=..] */
    fun statusLine8359(nowMs: Long = System.currentTimeMillis()): String =
        "keylessMarks8359[wanted=${wanted.count { it.value > nowMs }} noted=${noted.get()} ticks=${ticks.get()} " +
            "laneQueued=${laneQueued.get()} curvePasses=${curvePasses.get()} curveMarks=${curveMarks.get()}]"

    internal fun resetForTests8359() {
        wanted.clear(); lastCurveMarkMs.clear(); lastTickMs = 0L; running.set(false)
        noted.set(0L); ticks.set(0L); laneQueued.set(0L); curvePasses.set(0L); curveMarks.set(0L)
    }

    /** Test seam: register a wanted mint without starting a pass. */
    internal fun putWantedForTests8359(mint: String, untilMs: Long) { wanted[mint] = untilMs }
}
