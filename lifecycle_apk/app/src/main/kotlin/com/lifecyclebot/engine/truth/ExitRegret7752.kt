package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7752 §DID_THE_EXIT_KEEP_THE_EDGE.
 *
 * 5.0.7749: MOONSHOT's forward labels read +13.8% net at sixty minutes on 219
 * candidates while its twelve live closes averaged -11.3%; the analytics line
 * said losers were held 121.7 min and winners 16.2 min. The labels hold every
 * candidate for an hour; a live position is closed by whichever exit fires
 * first. If the exits are what turns a positive cell into a losing lane, the
 * evidence is the token's own price after we sold.
 *
 * For every live close this records the realised return and the mark at the
 * close, then prices the same token when the position would have turned sixty
 * minutes old (and never sooner than fifteen minutes after the close):
 *
 *   after%  the move from our exit price to that later mark
 *   hold%   what holding from entry to that mark would have returned
 *
 * Grouped by lane and by exit family. A family whose mean after% is strongly
 * positive cuts winners; one whose after% is strongly negative saved money.
 * Field Manual §8: "Cut losers quickly and let winners run" — this is how the
 * bot checks which of its exits do which. Read only; it changes no exit.
 */
object ExitRegret7752 {
    private const val HOLD_MS_7752 = 60L * 60_000L
    private const val MIN_AFTER_CLOSE_MS_7752 = 15L * 60_000L
    private const val LOST_GRACE_MS_7752 = 20L * 60_000L
    private const val FRESH_CLOSE_MS_7752 = 10L * 60_000L
    private const val MARK_MAX_AGE_MS_7752 = 2L * 60_000L
    private const val FETCH_GAP_MS_7752 = 20_000L
    private const val PERSIST_KEY_7752 = "EXIT_REGRET_7752"

    private class Pending(val mint: String, val lane: String, val family: String, val realizedPct: Double, val exitPx: Double, val dueMs: Long,
        val reason: String = "", val holdMin: Int = 0)

    private class Agg {
        var n = 0; var sumRealized = 0.0; var sumHold = 0.0; var sumAfter = 0.0; var holdBeat = 0
        fun add(realized: Double, hold: Double, after: Double) {
            n += 1; sumRealized += realized; sumHold += hold; sumAfter += after; if (hold > realized) holdBeat += 1
        }
        fun line(k: String): String =
            "$k[n=$n realized=${"%+.1f".format(sumRealized / n)}% hold=${"%+.1f".format(sumHold / n)}% after=${"%+.1f".format(sumAfter / n)}% holdBeat=$holdBeat]"
        fun encode(): String = "$n,$sumRealized,$sumHold,$sumAfter,$holdBeat"
        fun decode(s: String) {
            val f = s.split(',')
            if (f.size != 5) return
            n = f[0].toIntOrNull() ?: 0; sumRealized = f[1].toDoubleOrNull() ?: 0.0; sumHold = f[2].toDoubleOrNull() ?: 0.0
            sumAfter = f[3].toDoubleOrNull() ?: 0.0; holdBeat = f[4].toIntOrNull() ?: 0
        }
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val byLane = ConcurrentHashMap<String, Agg>()
    private val byFamily = ConcurrentHashMap<String, Agg>()
    private val offMarks = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val recorded = AtomicLong(0)
    private val noExitMark = AtomicLong(0)
    private val lost = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastFetchMs = 0L
    @Volatile private var loaded = false

    /** Pure: the exit family, the reason's leading upper-case words (TICK_PROFIT_LOCK_peak3_now1 -> TICK_PROFIT_LOCK). */
    fun family(reason: String): String =
        reason.split('_').takeWhile { w -> w.isNotEmpty() && w.all { it.isUpperCase() } }.take(4).joinToString("_").ifBlank { "UNKNOWN" }

    /** Pure: what holding from entry to [laterPx] would have returned, given the realised return at [exitPx]. */
    fun holdPct(realizedPct: Double, exitPx: Double, laterPx: Double): Double =
        ((1.0 + realizedPct / 100.0) * (laterPx / exitPx) - 1.0) * 100.0

    /** Called at the canonical publish of a terminal close. */
    fun onClose(env: CanonicalFinalizedTradeBus6464.Envelope, nowMs: Long = System.currentTimeMillis()) {
        if (!env.mode.equals("live", ignoreCase = true)) return
        if (nowMs - env.atMs !in 0L..FRESH_CLOSE_MS_7752) return
        if (!env.realizedReturnPct.isFinite() || env.mint.isBlank()) return
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[env.mint] } catch (_: Throwable) { null }
        val exitPx = ts?.takeIf { it.lastPrice.isFinite() && it.lastPrice > 0.0 && nowMs - it.lastPriceUpdate <= MARK_MAX_AGE_MS_7752 }?.lastPrice
            ?: try {
                val m = CanonicalPriceMarkRegistry6522.get(env.mint)
                if (m == null || nowMs - m.timestampMs > MARK_MAX_AGE_MS_7752) null else m.priceUsd.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
            } catch (_: Throwable) { null }
        if (exitPx == null) { noExitMark.incrementAndGet(); return }
        val entryMs = env.atMs - env.holdingTimeMs.coerceAtLeast(0L)
        val due = maxOf(entryMs + HOLD_MS_7752, env.atMs + MIN_AFTER_CLOSE_MS_7752)
        pending[env.tradeId] = Pending(env.mint, CanonicalLaneIdentity6506.canonical(env.lane), family(env.exitReason), env.realizedReturnPct, exitPx, due,
            env.exitReason, (env.holdingTimeMs.coerceAtLeast(0L) / 60_000L).toInt())
        try { PipelineHealthCollector.labelInc("EXIT_REGRET_TRACKED_7752") } catch (_: Throwable) {}
    }

    /** Called from the loop with its fresh-price closure. */
    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        ensureLoaded()
        if (pending.isEmpty()) return
        val unpriced = ArrayList<String>()
        for ((id, p) in pending.entries.toList()) {
            if (nowMs < p.dueMs) continue
            val px = (try { priceFor(p.mint) } catch (_: Throwable) { null })?.takeIf { it.isFinite() && it > 0.0 }
                ?: offMarks[p.mint]?.takeIf { nowMs - it.second <= MARK_MAX_AGE_MS_7752 }?.first
            if (px == null) {
                if (nowMs - p.dueMs > LOST_GRACE_MS_7752) {
                    pending.remove(id); lost.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("EXIT_REGRET_LOST_MARK_7752") } catch (_: Throwable) {}
                } else unpriced.add(p.mint)
                continue
            }
            if (ForwardReturnLabeler7731.basisSuspect7738(p.exitPx, px)) { pending.remove(id); continue }
            val after = (px / p.exitPx - 1.0) * 100.0
            val hold = holdPct(p.realizedPct, p.exitPx, px)
            synchronized(this) {
                byLane.getOrPut(p.lane) { Agg() }.add(p.realizedPct, hold, after)
                byFamily.getOrPut(p.family) { Agg() }.add(p.realizedPct, hold, after)
            }
            pending.remove(id)
            recorded.incrementAndGet()
            // V5.0.7884 — the exit-strategy learner gets the measured after-exit truth.
            try {
                com.lifecyclebot.v3.scoring.SellOptimizationAI.recordExitOutcome(
                    strategy = com.lifecyclebot.v3.scoring.SellOptimizationAI.strategyForReason7884(p.reason),
                    exitPnlPct = p.realizedPct, wouldHaveBeen = hold,
                    tokenType = p.lane.ifBlank { "STANDARD" }, holdTimeMinutes = p.holdMin,
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("EXIT_REGRET_BOOKED_7752") } catch (_: Throwable) {}
            persist()
        }
        if (unpriced.isNotEmpty()) fetch(unpriced, nowMs)
    }

    private fun fetch(mints: List<String>, nowMs: Long) {
        if (nowMs - lastFetchMs < FETCH_GAP_MS_7752 || !inFlight.compareAndSet(false, true)) return
        lastFetchMs = nowMs
        try {
            Thread({
                try {
                    val got = com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(mints.distinct().take(50))
                    val at = System.currentTimeMillis()
                    for ((m, px) in got) offMarks[m] = px to at
                    offMarks.entries.removeIf { at - it.value.second > MARK_MAX_AGE_MS_7752 }
                } catch (_: Throwable) {
                } finally { inFlight.set(false) }
            }, "exit-regret-7752").apply { isDaemon = true }.start()
        } catch (_: Throwable) { inFlight.set(false) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY_7752) ?: return)
                o.optJSONObject("lane")?.let { j -> for (k in j.keys()) byLane.getOrPut(k) { Agg() }.decode(j.optString(k)) }
                o.optJSONObject("family")?.let { j -> for (k in j.keys()) byFamily.getOrPut(k) { Agg() }.decode(j.optString(k)) }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        try {
            val o = org.json.JSONObject()
            synchronized(this) {
                o.put("lane", org.json.JSONObject().also { j -> byLane.forEach { (k, a) -> j.put(k, a.encode()) } })
                o.put("family", org.json.JSONObject().also { j -> byFamily.forEach { (k, a) -> j.put(k, a.encode()) } })
            }
            LearningPersistence.save(PERSIST_KEY_7752, o.toString())
        } catch (_: Throwable) {}
    }

    // ── V5.0.7877 §THE_EXITS_LEARN_FROM_WHAT_HAPPENED_AFTER ──
    //
    // Operator: "ensure we are capturing that 60% post sell runs the bot is
    // missing ... the bot is meant to see these things and automatically change
    // itself in real time." 5.0.7876: HARD_STOP n=48 realised -5.2% while
    // holding to sixty minutes would have returned +21.2% (price +24.9% after
    // our exit, hold beat the stop 21/48); MEME n=51 realised -9.2% vs hold
    // +16.2%. This file measured that and changed nothing. It now steers:
    //   * stopMultFor(lane): the stop widens when the stops that fired on this
    //     lane (or, while the lane is thin, stop-family exits overall) were
    //     followed by the price running; it tightens slightly when they saved
    //     money. Bounded [STOP_MULT_MIN, STOP_MULT_MAX]; every lane hard floor
    //     and catastrophe exit still applies downstream.
    //   * underwaterHoldMs(lane): the 45-minute underwater time stop waits out
    //     the hour when underwater exits were followed by recovery.
    // Both re-read the live aggregates on every call, so each booked label
    // moves them; nothing is fixed at build time.
    private const val REGRET_MIN_N_7877 = 10
    const val STOP_MULT_MIN_7877 = 0.90
    const val STOP_MULT_MAX_7877 = 1.50
    private const val UNDERWATER_EXTENDED_MS_7877 = 120L * 60_000L

    data class Read7877(val n: Int, val meanRealized: Double, val meanHold: Double, val meanAfter: Double, val holdBeatShare: Double)

    private fun read(a: Agg?): Read7877? =
        if (a == null || a.n <= 0) null
        else Read7877(a.n, a.sumRealized / a.n, a.sumHold / a.n, a.sumAfter / a.n, a.holdBeat.toDouble() / a.n)

    /** Pure: the stop multiplier a regret read justifies (1.0 = no evidence either way). */
    fun stopMult7877(r: Read7877?): Double {
        if (r == null || r.n < REGRET_MIN_N_7877) return 1.0
        if (r.holdBeatShare >= 0.40 && r.meanAfter >= 5.0 && r.meanHold > r.meanRealized) {
            return (1.0 + r.meanAfter / 50.0).coerceIn(1.0, STOP_MULT_MAX_7877)
        }
        if (r.meanAfter <= -5.0 && r.holdBeatShare < 0.30) return STOP_MULT_MIN_7877
        return 1.0
    }

    /** Pure: true when exits of this kind were followed by the position recovering. */
    fun holdingPaid7877(r: Read7877?): Boolean =
        r != null && r.n >= REGRET_MIN_N_7877 && r.holdBeatShare >= 0.40 && r.meanHold > r.meanRealized && r.meanAfter > 0.0

    private fun laneRead(lane: String): Read7877? {
        ensureLoaded()
        val l = CanonicalLaneIdentity6506.canonical(lane)
        return synchronized(this) { read(byLane[l]) }
    }

    private fun familyRead(vararg families: String): Read7877? {
        ensureLoaded()
        return synchronized(this) {
            val parts = families.mapNotNull { byFamily[it] }.filter { it.n > 0 }
            if (parts.isEmpty()) null else {
                val m = Agg()
                for (p in parts) { m.n += p.n; m.sumRealized += p.sumRealized; m.sumHold += p.sumHold; m.sumAfter += p.sumAfter; m.holdBeat += p.holdBeat }
                read(m)
            }
        }
    }

    /** LIVE stop multiplier for [lane]: the lane's own regret when it has enough closes, else stop-family regret. */
    fun stopMultFor(lane: String): Double = try {
        val own = laneRead(lane)
        val r = if (own != null && own.n >= REGRET_MIN_N_7877) own
            else familyRead("HARD_STOP", "STRICT_SL", "RAPID_CATASTROPHE_STOP", "STOP_LOSS", "STRUCTURE_STOP")
        val m = stopMult7877(r)
        if (m != 1.0) {
            try { PipelineHealthCollector.labelInc(if (m > 1.0) "EXIT_REGRET_STOP_WIDENED_7877" else "EXIT_REGRET_STOP_TIGHTENED_7877") } catch (_: Throwable) {}
        }
        m
    } catch (_: Throwable) { 1.0 }

    /** The underwater time-stop horizon for [lane]: extended when underwater exits were followed by recovery. */
    fun underwaterHoldMs(lane: String, defaultMs: Long): Long = try {
        val own = laneRead(lane)
        val r = if (own != null && own.n >= REGRET_MIN_N_7877) own else familyRead("UNDERWATER_TIME_STOP")
        if (holdingPaid7877(r)) {
            try { PipelineHealthCollector.labelInc("EXIT_REGRET_UNDERWATER_EXTENDED_7877") } catch (_: Throwable) {}
            maxOf(defaultMs, UNDERWATER_EXTENDED_MS_7877)
        } else defaultMs
    } catch (_: Throwable) { defaultMs }

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            "tracked=${pending.size} booked=${recorded.get()} noExitMark=${noExitMark.get()} lost=${lost.get()}\n" +
                "      byExit: ${byFamily.entries.sortedByDescending { it.value.n }.take(8).joinToString(" · ") { it.value.line(it.key) }.ifBlank { "-" }}\n" +
                "      byLane: ${byLane.entries.sortedByDescending { it.value.n }.take(8).joinToString(" · ") { it.value.line(it.key) }.ifBlank { "-" }}\n" +
                "      steering7877: stopMult[HARD_STOP-family]=${"%.2f".format(stopMult7877(familyRead("HARD_STOP", "STRICT_SL", "RAPID_CATASTROPHE_STOP", "STOP_LOSS", "STRUCTURE_STOP")))} underwaterExtended=${holdingPaid7877(familyRead("UNDERWATER_TIME_STOP"))}\n" +
                "      read: after>0 = the price kept rising after we sold (exit cut a winner); hold>realized = holding to 60m would have paid more"
        }
    }
}
