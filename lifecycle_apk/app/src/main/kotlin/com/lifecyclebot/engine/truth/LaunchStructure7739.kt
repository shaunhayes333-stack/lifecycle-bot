package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7739 §TRADE_THE_STRUCTURE_NOT_THE_LAUNCH.
 *
 * Field Manual setup F: "Trigger: defined post-launch structure or confirmed
 * continuation with buyers still present. Avoid a blind purchase based only on
 * launch." §13: chasing a vertical move, and fixed percentage stops everywhere
 * ("use structural invalidation, then size from the distance"). §10: the stop
 * belongs to the thesis; if the expected move does not come within the
 * strategy's horizon, close.
 *
 * 5.0.7737 at 396 s: 44 live closes, 18% wins, average win about +45%, average
 * loss about -27%, PF 0.36. The last candidate in the report reached the gate
 * as "age=0m peakPos=1.00 dd=0% runup=0% bp=50 sp=50": no structure at all.
 * Losses rode to TICK_HARD_FLOOR_-49PCT (11 triggers); 7mNqc6 lost 52% in two
 * and a half minutes; losers were held 162 minutes against winners' 22.
 *
 * ENTRY — the launch pullback-reclaim, read from one-minute bars rebuilt from
 * the token's own price history:
 *   impulse   a move of at least [MIN_IMPULSE_PCT_7739]% off its base;
 *   pullback  a retrace keeping 25%-65% of that move (deeper is a failure);
 *   reclaim   price turned up off the pullback low by [MIN_LIFT_PCT_7739]% and
 *             the current bar is not lower than the last;
 *   room      price still [MIN_ROOM_PCT_7739]% or more under the impulse high
 *             (not chasing), and the measured move (high + impulse) at least
 *             [MIN_R_MULTIPLE_7739]R over a stop just under the pullback low;
 *   tape      not sell-dominant when the tape is known.
 * Without that structure a live meme entry WAITs; the next cycle re-reads it.
 *
 * EXIT — the plan is kept per mint: a close under the invalidation is a
 * structural exit; no retest of the high within [THESIS_HORIZON_MS_7739] is a
 * thesis time exit; any position under water after [UNDERWATER_HORIZON_MS_7739]
 * is a time exit (§10 "capital and attention have opportunity costs").
 */
object LaunchStructure7739 {
    private const val MIN_BARS_7739 = 5
    private const val WINDOW_MS_7739 = 30L * 60_000L
    private const val MIN_IMPULSE_PCT_7739 = 30.0
    private const val MIN_RETRACE_7739 = 0.25
    private const val MAX_RETRACE_7739 = 0.65
    private const val MIN_LIFT_PCT_7739 = 5.0
    private const val MIN_ROOM_PCT_7739 = 5.0
    private const val MIN_R_MULTIPLE_7739 = 2.0
    private const val MAX_STOP_PCT_7739 = 25.0
    private const val INVALIDATION_BUFFER_7739 = 0.98
    private const val THESIS_HORIZON_MS_7739 = 20L * 60_000L
    private const val THESIS_PROGRESS_PCT_7739 = 10.0
    private const val UNDERWATER_HORIZON_MS_7739 = 45L * 60_000L
    private const val PLAN_TTL_MS_7739 = 6L * 60L * 60_000L

    /** Lanes with their own large-cap or reclaim mandates; they are not launch traders. */
    private val EXEMPT_LANES_7739 = setOf("TREASURY", "BLUECHIP", "QUALITY", "DIP_HUNTER", "CASHGEN")

    data class Bar(val startMs: Long, val open: Double, val high: Double, val low: Double, val close: Double)

    data class Trigger(
        val invalidation: Double,
        val impulseHigh: Double,
        val measuredTarget: Double,
        val stopPct: Double,
        val impulsePct: Double,
        val retrace: Double,
    )

    data class Read(val trigger: Trigger?, val why: String)

    data class Plan(val invalidation: Double, val impulseHigh: Double, val measuredTarget: Double, val atMs: Long)

    private val plans = ConcurrentHashMap<String, Plan>()
    private val admitted = AtomicLong(0)
    private val waited = AtomicLong(0)
    private val structuralExits = AtomicLong(0)
    private val thesisTimeExits = AtomicLong(0)
    private val underwaterExits = AtomicLong(0)
    private val waitReasons = ConcurrentHashMap<String, AtomicLong>()

    fun exemptLane(lane: String): Boolean = lane.trim().uppercase() in EXEMPT_LANES_7739

    /** Rebuilds one-minute bars from a price history that may mix ticks, synthetic and fetched candles. */
    fun barsFrom(ts: TokenState, nowMs: Long): List<Bar> {
        // A history seeded with hour or four-hour candles is not a one-minute tape.
        if (ts.candleTimeframeMinutes > 1) return emptyList()
        val pts = try { synchronized(ts) { ts.history.toList() } } catch (_: Throwable) { emptyList() }
        val byMinute = java.util.TreeMap<Long, DoubleArray>()   // minute -> [open, high, low, close, lastTs]
        for (c in pts.sortedBy { it.ts }) {
            if (c.ts <= 0L || nowMs - c.ts > WINDOW_MS_7739 || c.ts > nowMs + 60_000L) continue
            val p = c.priceUsd
            if (!p.isFinite() || p <= 0.0) continue
            val hi = if (c.highUsd.isFinite() && c.highUsd >= p) c.highUsd else p
            val lo = if (c.lowUsd.isFinite() && c.lowUsd > 0.0 && c.lowUsd <= p) c.lowUsd else p
            val op = if (c.openUsd.isFinite() && c.openUsd > 0.0) c.openUsd else p
            val m = c.ts / 60_000L
            val b = byMinute[m]
            if (b == null) byMinute[m] = doubleArrayOf(op, hi, lo, p, c.ts.toDouble())
            else {
                if (hi > b[1]) b[1] = hi
                if (lo < b[2]) b[2] = lo
                if (c.ts.toDouble() >= b[4]) { b[3] = p; b[4] = c.ts.toDouble() }
            }
        }
        return byMinute.entries.map { (m, b) -> Bar(m * 60_000L, b[0], b[1], b[2], b[3]) }
    }

    /** Pure: the pullback-reclaim read on one-minute bars, latest last. */
    fun analyze(bars: List<Bar>, buyTx: Int, sellTx: Int): Read {
        val n = bars.size
        if (n < MIN_BARS_7739) return Read(null, "TOO_FEW_BARS")
        if (sellTx >= 3 && sellTx > buyTx) return Read(null, "SELL_DOMINANT_TAPE")
        // The impulse high must sit before the current bar.
        var iH = 0
        for (i in 0 until n - 1) if (bars[i].high > bars[iH].high) iH = i
        if (iH < 1 || iH > n - 3) return Read(null, "NO_PULLBACK_YET")
        val h = bars[iH].high
        val base = (0..iH).minOf { bars[it].low }
        if (base <= 0.0) return Read(null, "NO_BASE")
        val impulsePct = (h / base - 1.0) * 100.0
        if (impulsePct < MIN_IMPULSE_PCT_7739) return Read(null, "NO_IMPULSE")
        var iPl = iH + 1
        for (i in iH + 1 until n) if (bars[i].low < bars[iPl].low) iPl = i
        val pl = bars[iPl].low
        val retrace = (h - pl) / (h - base)
        if (retrace < MIN_RETRACE_7739) return Read(null, "NO_PULLBACK_YET")
        if (retrace > MAX_RETRACE_7739) return Read(null, "PULLBACK_TOO_DEEP")
        if (iPl >= n - 1) return Read(null, "STILL_FALLING")
        val cur = bars[n - 1].close
        if (cur < pl * (1.0 + MIN_LIFT_PCT_7739 / 100.0)) return Read(null, "NO_RECLAIM")
        if (cur < bars[n - 2].close) return Read(null, "NOT_TURNING_UP")
        if (cur > h * (1.0 - MIN_ROOM_PCT_7739 / 100.0)) return Read(null, "CHASING_THE_HIGH")
        val inv = pl * INVALIDATION_BUFFER_7739
        val stopPct = (1.0 - inv / cur) * 100.0
        if (stopPct <= 0.0 || stopPct > MAX_STOP_PCT_7739) return Read(null, "STOP_TOO_FAR")
        val target = h + (h - base)
        val rewardPct = (target / cur - 1.0) * 100.0
        if (rewardPct < MIN_R_MULTIPLE_7739 * stopPct) return Read(null, "REWARD_UNDER_2R")
        return Read(Trigger(inv, h, target, stopPct, impulsePct, retrace), "PULLBACK_RECLAIM")
    }

    /** Live-entry verdict for a meme lane: null admits (and records the plan). */
    fun liveBlockReason(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (paper || exemptLane(lane)) return null
        val lp = try { LaunchPhaseAuthority7401.snapshot(ts, nowMs) } catch (_: Throwable) { null }
        val read = analyze(barsFrom(ts, nowMs), lp?.buyTx60s ?: 0, lp?.sellTx60s ?: 0)
        val t = read.trigger
        if (t == null) {
            waited.incrementAndGet()
            waitReasons.computeIfAbsent(read.why) { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("NO_STRUCTURE_WAIT_7739_${read.why}") } catch (_: Throwable) {}
            return "NO_STRUCTURE_WAIT_7739:${read.why}"
        }
        plans[ts.mint] = Plan(t.invalidation, t.impulseHigh, t.measuredTarget, nowMs)
        if (plans.size > 2_000) plans.entries.removeIf { nowMs - it.value.atMs > PLAN_TTL_MS_7739 }
        admitted.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("STRUCTURE_ENTRY_ADMITTED_7739")
            ForensicLogger.lifecycle(
                "STRUCTURE_ENTRY_ADMITTED_7739",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane impulse=${"%.0f".format(t.impulsePct)}% retrace=${"%.2f".format(t.retrace)} " +
                    "stop=${"%.1f".format(t.stopPct)}% inv=${t.invalidation} high=${t.impulseHigh} target=${t.measuredTarget}",
            )
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Pure: the exit a position owes now, or null. [plan] may be null (no
     * structural read at entry); then only the underwater time exit applies.
     */
    fun exitReason(plan: Plan?, entryPrice: Double, price: Double, holdMs: Long, pnlPct: Double, peakPct: Double): String? {
        if (plan != null && entryPrice > 0.0) {
            val ratio = plan.invalidation / entryPrice
            // The plan and the position must share a basis: the stop sits under the entry, within 50%.
            if (ratio in 0.5..1.0) {
                if (price <= plan.invalidation) return "STRUCTURE_STOP_7739_${pnlPct.toInt()}PCT"
                if (holdMs >= THESIS_HORIZON_MS_7739 && peakPct < THESIS_PROGRESS_PCT_7739 && pnlPct < THESIS_PROGRESS_PCT_7739) {
                    return "THESIS_TIME_EXIT_7739_${pnlPct.toInt()}PCT"
                }
            }
        }
        if (holdMs >= UNDERWATER_HORIZON_MS_7739 && pnlPct < 0.0) return "UNDERWATER_TIME_EXIT_7739_${pnlPct.toInt()}PCT"
        return null
    }

    /** The plan recorded at the gate for [mint], when it predates the position by under ten minutes. */
    fun planFor(mint: String, entryTimeMs: Long): Plan? {
        val p = plans[mint] ?: return null
        if (entryTimeMs <= 0L) return null
        return if (entryTimeMs >= p.atMs && entryTimeMs - p.atMs <= 10L * 60_000L) p else null
    }

    fun onExit(reason: String) {
        when {
            reason.startsWith("STRUCTURE_STOP_7739") -> structuralExits.incrementAndGet()
            reason.startsWith("THESIS_TIME_EXIT_7739") -> thesisTimeExits.incrementAndGet()
            reason.startsWith("UNDERWATER_TIME_EXIT_7739") -> underwaterExits.incrementAndGet()
        }
        try { PipelineHealthCollector.labelInc(reason.substringBefore("_7739") + "_7739") } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "admitted=${admitted.get()} waited=${waited.get()} plans=${plans.size} exits[structure=${structuralExits.get()} thesisTime=${thesisTimeExits.get()} underwater=${underwaterExits.get()}] " +
            "waitWhy=${waitReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}"
}
