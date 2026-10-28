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
 *
 * V5.0.8418 §EVERY_LABEL_GETS_ITS_MARK — 5.0.8416 (3.7 h): Forward labels observed=8349 booked60=1532 lostMark=6669; the
 * off-watch batch priced 2,700 mints in 3.7 h while the DexScreener pair lane ran at ~20 of its 120 calls a minute. The
 * labeler's unpriced observations inside (or 30 s before) a horizon window now register here through [noteLabelDue8418]:
 * they ride the same pair batch lane at HANDOFF priority (after held positions and the shadow books' NEAR_DECISION mints)
 * and the same keyless curve read (after held and shadow curves). A lane answer for a label mint is handed to the labeler as
 * its off-watch mark ([ForwardReturnLabeler7731.noteLaneMark8418], the provider's own price, stamped when it arrived); the
 * curve read writes the curve's reserves (CurveReserves7951), which the labeler reads as its CURVE mark. Nothing is
 * inferred: a mint no source answered stays unpriced and its label closes unavailable at its deadline.
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

    // V5.0.8418 — the forward labeler's due observations (mint -> end of the window it must be priced in), kept apart from
    // the shadow books' set so the shadow tickets keep their priority.
    private const val MAX_LABEL_WANTED_8418 = 1_500
    private const val MAX_LABEL_LANE_PER_TICK_8418 = 300
    private val labelWanted8418 = ConcurrentHashMap<String, Long>()
    private val labelLaneAskedAt8418 = ConcurrentHashMap<String, Long>()
    private val labelCurveMarkMs8418 = ConcurrentHashMap<String, Long>()
    private val labelNoted8418 = AtomicLong(0L)
    private val labelLaneQueued8418 = AtomicLong(0L)
    private val labelLaneMarks8418 = AtomicLong(0L)
    private val labelCurveMarks8418 = AtomicLong(0L)
    private val labelCurvePasses8418 = AtomicLong(0L)

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

    /**
     * V5.0.8418 — the forward labeler's call: an unpriced observation of [mint] must be priced before [windowEndMs] (wall
     * clock, the end of its horizon's grace). A later call extends it (at most [MAX_HOLD_MS_8359] ahead). Starts a pass when due.
     */
    fun noteLabelDue8418(mint: String, windowEndMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val m = mint.trim()
        if (m.length !in 32..44 || windowEndMs <= nowMs) return
        val until = minOf(windowEndMs, nowMs + MAX_HOLD_MS_8359)
        val prior = labelWanted8418.put(m, maxOf(until, labelWanted8418[m] ?: 0L))
        if (prior == null || prior <= nowMs) labelNoted8418.incrementAndGet()
        if (labelWanted8418.size > MAX_LABEL_WANTED_8418) pruneLabel8418(nowMs)
        maybeTick8359(nowMs)
    }

    /** V5.0.8418 — is [mint] a forward-label observation due for a mark now? */
    fun isLabelWanted8418(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = (labelWanted8418[mint] ?: 0L) > nowMs

    /** V5.0.8418 — the label mints now, the window closing soonest first (a mint the shadow books want keeps its own place). */
    fun labelMints8418(nowMs: Long = System.currentTimeMillis()): List<String> =
        labelWanted8418.entries.filter { it.value > nowMs }.sortedBy { it.value }.map { it.key }

    /**
     * V5.0.8418 — a label mint the pair lane was asked for within [STALE_MS_8359]: the off-watch batch leaves its DexScreener
     * rung to the lane (one ask per mint, not two on the same host).
     */
    fun laneCovered8418(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        isLabelWanted8418(mint, nowMs) && (labelLaneAskedAt8418[mint]?.let { nowMs - it in 0L..STALE_MS_8359 } ?: false)

    /** Pure (V5.0.8418): held first, then the shadow books' mints, then the labeler's, de-duplicated, at most [cap]. */
    fun priorityOrder8418(held: List<String>, shadow: List<String>, label: List<String>, cap: Int): List<String> =
        priorityOrder8359(priorityOrder8359(held, shadow, Int.MAX_VALUE), label, cap)

    /**
     * Pure (V5.0.8418): the label mints to queue on the pair lane now — not wanted by a shadow book (the shadow pass asks for
     * those), no observed mark fresher than [staleMs], not asked within [staleMs]; in the given (most urgent first) order, at
     * most [cap].
     */
    fun labelLaneAsk8418(label: List<String>, shadowWanted: Set<String>, markAtMs: (String) -> Long, askedAtMs: (String) -> Long,
                         nowMs: Long, staleMs: Long, cap: Int): List<String> {
        val out = ArrayList<String>()
        for (m in label) {
            if (out.size >= cap) break
            if (m in shadowWanted) continue
            val markAt = markAtMs(m)
            if (markAt > 0L && nowMs - markAt in 0L..staleMs) continue
            val askedAt = askedAtMs(m)
            if (askedAt > 0L && nowMs - askedAt in 0L..staleMs) continue
            out.add(m)
        }
        return out
    }

    /**
     * V5.0.8418 — DexBatchLane8287: one answered batch. Label mints it priced become the labeler's off-watch marks (the
     * provider's price, stamped at arrival); a mint it did not list simply has no new mark.
     */
    fun onLaneAnswer8418(batch: List<String>, prices: Map<String, Double>) {
        if (batch.isEmpty() || labelWanted8418.isEmpty()) return
        val now = System.currentTimeMillis()
        var n = 0L
        for (m in batch) {
            if (!isLabelWanted8418(m, now)) continue
            val px = prices[m] ?: continue
            if (!px.isFinite() || px <= 0.0) continue
            try { if (ForwardReturnLabeler7731.noteLaneMark8418(m, px, now)) n++ } catch (_: Throwable) {}
        }
        if (n > 0L) labelLaneMarks8418.addAndGet(n)
    }

    /** V5.0.8418 — (noted, laneQueued, laneMarks, curvePasses, curveMarks) for the labeler's labelMarks8418 line. */
    fun labelCounts8418(): LongArray = longArrayOf(labelNoted8418.get(), labelLaneQueued8418.get(), labelLaneMarks8418.get(),
        labelCurvePasses8418.get(), labelCurveMarks8418.get())

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

    private fun curveMints8418(mints: List<String>): List<String> = mints.filter { m ->
        val keys = com.lifecyclebot.network.PumpCurveKeys7269
        curveLike8359(m, keys.keyFor(m) != null, keys.isGraduated7392(m))
    }

    private fun wantedCurves8359(nowMs: Long): List<String> = curveMints8418(wantedMints8359(nowMs))

    /**
     * ParallelMarkFanout7088's curve call: the caller's mints, then the wanted curve mints, up to [cap].
     * V5.0.8418 — then the forward labeler's due curve mints (after held and shadow).
     */
    fun withWantedCurves8359(mints: List<String>, cap: Int): List<String> {
        val now = System.currentTimeMillis()
        val w = wantedCurves8359(now)
        val l = if (labelWanted8418.isEmpty()) emptyList() else curveMints8418(labelMints8418(now))
        if (w.isEmpty() && l.isEmpty()) return mints
        return priorityOrder8418(mints, w, l, maxOf(cap, mints.size))
    }

    /** A curve pass answered: wanted mints among [prices] get the curve's own price as their mark. */
    fun onCurveMarks8359(prices: Map<String, Double>) {
        if (prices.isEmpty()) return
        val now = System.currentTimeMillis()
        // V5.0.8418 — a label mint's curve read already wrote the reserves the labeler reads as its CURVE mark; counted here.
        if (labelWanted8418.isNotEmpty()) {
            val lab = prices.keys.filter { isLabelWanted8418(it, now) && (prices[it] ?: 0.0).let { p -> p.isFinite() && p > 0.0 } }
            for (m in lab) labelCurveMarkMs8418[m] = now
            if (lab.isNotEmpty()) labelCurveMarks8418.addAndGet(lab.size.toLong())
        }
        if (wanted.isEmpty()) return
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
        pruneLabel8418(nowMs)
        val labelCurveDue8418 = try { tickLabel8418(nowMs) } catch (_: Throwable) { false }
        // V5.0.8418 — one curve pass serves both sets (held, then shadow, then label curves: withWantedCurves8359).
        val shadowCurveRan8418 = shadowCurvePass8359(nowMs)
        if (labelCurveDue8418 && !shadowCurveRan8418) {
            labelCurvePasses8418.incrementAndGet()
            try { com.lifecyclebot.network.ParallelMarkFanout7088.wantedCurvePass8359() } catch (_: Throwable) {}
        }
    }

    /** The shadow books' pass: stale wanted mints to the pair lane, a curve pass when a stale wanted curve is due. True when it ran one. */
    private fun shadowCurvePass8359(nowMs: Long): Boolean {
        val want = wantedMints8359(nowMs)
        if (want.isEmpty()) return false
        val tokens = try { com.lifecyclebot.engine.BotService.status.tokens } catch (_: Throwable) { null } ?: return false
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
        return curveDue
    }

    /**
     * V5.0.8418 — the labeler's due mints: stale ones join the pair lane (HANDOFF priority, after held and shadow mints;
     * ProviderLanes8285.prioFor8285). True when a due curve mint has no recent curve read (the caller runs one curve pass).
     */
    private fun tickLabel8418(nowMs: Long): Boolean {
        val label = labelMints8418(nowMs)
        if (label.isEmpty()) return false
        val tokens = try { com.lifecyclebot.engine.BotService.status.tokens } catch (_: Throwable) { null }
        val markAt: (String) -> Long = { m ->
            val obs = try { ForwardReturnLabeler7731.observedMark8372(m, nowMs)?.second } catch (_: Throwable) { null } ?: 0L
            val ts = try { tokens?.get(m)?.lastPriceUpdate } catch (_: Throwable) { null } ?: 0L
            maxOf(obs, ts)
        }
        val ask = labelLaneAsk8418(label, wanted.keys.filter { (wanted[it] ?: 0L) > nowMs }.toHashSet(), markAt,
            { m -> labelLaneAskedAt8418[m] ?: 0L }, nowMs, STALE_MS_8359, MAX_LABEL_LANE_PER_TICK_8418)
        var queued = 0L
        for (m in ask) {
            labelLaneAskedAt8418[m] = nowMs
            try { if (com.lifecyclebot.engine.DexBatchLane8287.enqueue8287(m)) queued++ } catch (_: Throwable) {}
        }
        if (queued > 0L) labelLaneQueued8418.addAndGet(queued)
        return curveMints8418(label).any { m ->
            nowMs - (labelCurveMarkMs8418[m] ?: 0L) > STALE_MS_8359 && markAt(m).let { a -> a <= 0L || nowMs - a > STALE_MS_8359 }
        }
    }

    private fun pruneLabel8418(nowMs: Long) {
        labelWanted8418.entries.removeIf { it.value <= nowMs }
        if (labelWanted8418.size > MAX_LABEL_WANTED_8418) {
            // the windows that close last leave first: the soonest-closing are the ones a mark can still save
            labelWanted8418.entries.sortedByDescending { it.value }.take(labelWanted8418.size - MAX_LABEL_WANTED_8418)
                .forEach { labelWanted8418.remove(it.key) }
        }
        labelLaneAskedAt8418.keys.removeIf { !labelWanted8418.containsKey(it) }
        labelCurveMarkMs8418.keys.removeIf { !labelWanted8418.containsKey(it) }
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
        labelWanted8418.clear(); labelLaneAskedAt8418.clear(); labelCurveMarkMs8418.clear()
        labelNoted8418.set(0L); labelLaneQueued8418.set(0L); labelLaneMarks8418.set(0L); labelCurveMarks8418.set(0L); labelCurvePasses8418.set(0L)
    }

    /** V5.0.8418 test seam: register a label mint without starting a pass. */
    internal fun putLabelWantedForTests8418(mint: String, untilMs: Long) { labelWanted8418[mint] = untilMs }

    /** Test seam: register a wanted mint without starting a pass. */
    internal fun putWantedForTests8359(mint: String, untilMs: Long) { wanted[mint] = untilMs }
}
