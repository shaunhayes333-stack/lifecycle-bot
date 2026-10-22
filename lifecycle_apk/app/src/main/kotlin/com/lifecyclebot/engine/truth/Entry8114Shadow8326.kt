package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8326 — the shadow comparison of Entry8114Mode8326. Every live meme coin the edge gate evaluates is decided both
 * ways: the 8114 verdict (authoritative) and the current full verdict (CandidateAdmission8136 and every later door).
 * Each coin is labelled with both ([labelFor8326]). Per lane the report shows, over the coins first seen in the last
 * window:
 *   buys8114 / buysNow / both / onlyOld / onlyNew  (a coin counts as a buy of a regime if that regime admitted it at any
 *   evaluation within [WINDOW_MS_8326] of first sight),
 *   the forward net (observed price [WINDOW_MS_8326] after first sight vs. the first-sight price, minus the round trip
 *   quoted at first sight) mean and the runner rate (observed peak >= +50 % over the first-sight price) of each set,
 *   and the live whole-position closes (CanonicalFinalizedTradeBus6464, LIVE) of coins the 8114 decision admitted.
 * Prices are the bot's own observed marks (BotService.status.tokens); a coin with no observed mark at resolution is
 * counted unresolved, never given a number.
 */
object Entry8114Shadow8326 {
    const val WINDOW_MS_8326 = 60L * 60_000L
    const val RUNNER_MULT_8326 = 1.5
    private const val MAX_ENTRIES_8326 = 5_000
    private const val SWEEP_MS_8326 = 30_000L

    private class Entry8326(
        val mint: String, val lane: String, val firstAtMs: Long, val firstPx: Double, val costPct: Double,
    ) {
        @Volatile var oldAdmit = false
        @Volatile var nowAdmit = false
        @Volatile var lastOld: String? = null
        @Volatile var lastNow: String? = null
        @Volatile var peakPx = firstPx
        @Volatile var resolved = false
        @Volatile var oldAdmitAtMs = 0L
    }

    /** Per lane per set: [n resolved, sum net %, runners, unresolved]. */
    private class Acc8326 { var n = 0; var sum = 0.0; var runners = 0; var unresolved = 0 }

    private val entries = ConcurrentHashMap<String, Entry8326>()
    private val acc = ConcurrentHashMap<String, Acc8326>()        // "$lane|$set"
    private val seen = ConcurrentHashMap<String, IntArray>()       // lane -> [buys8114, buysNow, both, onlyOld, onlyNew] (resolved coins)
    @Volatile private var lastSweepMs = 0L

    /** Pure: the set names a coin belongs to, from whether each regime admitted it. */
    fun setsOf8326(oldAdmit: Boolean, nowAdmit: Boolean): List<String> {
        val out = ArrayList<String>(3)
        if (oldAdmit) out += "buys8114"
        if (nowAdmit) out += "buysNow"
        if (oldAdmit && nowAdmit) out += "both"
        if (oldAdmit && !nowAdmit) out += "onlyOld"
        if (!oldAdmit && nowAdmit) out += "onlyNew"
        return out
    }

    /** Pure: forward net % of a buy at [firstPx] marked at [px], net of a [costPct] round trip; NaN when either price is unknown. */
    fun forwardNetPct8326(firstPx: Double, px: Double, costPct: Double): Double {
        if (!(firstPx.isFinite() && firstPx > 0.0 && px.isFinite() && px > 0.0)) return Double.NaN
        val c = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        return (px / firstPx - 1.0) * 100.0 - c
    }

    fun record8326(ts: TokenState, lane: String, old: String?, now: String?, final: String?, nowMs: Long) {
        val key = "${ts.mint}|$lane"
        val px = ts.lastPrice
        val e = entries[key]?.takeIf { !it.resolved } ?: run {
            if (!(px.isFinite() && px > 0.0)) return
            val cost = try { NetEdge8030.costPct8030(lane, NetEdge8030.DEFAULT_TICKET_SOL, ts.lastLiquidityUsd, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
            val fresh = Entry8326(ts.mint, lane, nowMs, px, cost)
            if (entries.size >= MAX_ENTRIES_8326) sweep8326(nowMs, force = true)
            if (entries.size >= MAX_ENTRIES_8326) return
            entries[key] = fresh
            fresh
        }
        if (nowMs - e.firstAtMs < WINDOW_MS_8326) {
            if (old == null && !e.oldAdmit) { e.oldAdmit = true; e.oldAdmitAtMs = nowMs }
            if (now == null) e.nowAdmit = true
            e.lastOld = old; e.lastNow = now
            if (px.isFinite() && px > e.peakPx) e.peakPx = px
        }
        if ((old == null) != (now == null)) try {
            PipelineHealthCollector.labelInc(if (old == null) "ENTRY8114_ONLY_OLD_ADMITS_8326_$lane" else "ENTRY8114_ONLY_NEW_ADMITS_8326_$lane")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("ENTRY8114_SHADOW_8326", key)) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle("ENTRY8114_SHADOW_8326",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$lane ${labelOf8326(old, now)} final=${final ?: "ADMIT"}")
            }
        } catch (_: Throwable) {}
        if (nowMs - lastSweepMs >= SWEEP_MS_8326) sweep8326(nowMs, force = false)
    }

    /** Pure: the coin's two verdicts as one label. */
    fun labelOf8326(old: String?, now: String?): String =
        "8114:${if (old == null) "ADMIT" else "REFUSE(${old.take(48)})"} now:${if (now == null) "ADMIT" else "REFUSE(${now.take(48)})"}"

    /** The coin's latest pair of verdicts in [lane], or null when it has not been evaluated in the window. */
    fun labelFor8326(mint: String, lane: String): String? =
        entries["$mint|${Entry8114Mode8326.canon8326(lane)}"]?.let { labelOf8326(it.lastOld, it.lastNow) }

    private fun markOf8326(mint: String): Double = try {
        val ts = synchronized(com.lifecyclebot.engine.BotService.status.tokens) { com.lifecyclebot.engine.BotService.status.tokens[mint] }
        ts?.lastPrice ?: Double.NaN
    } catch (_: Throwable) { Double.NaN }

    private fun sweep8326(nowMs: Long, force: Boolean) {
        if (!force && nowMs - lastSweepMs < SWEEP_MS_8326) return
        lastSweepMs = nowMs
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next().value
            val age = nowMs - e.firstAtMs
            if (!e.resolved && age < WINDOW_MS_8326) {
                val px = markOf8326(e.mint)
                if (px.isFinite() && px > e.peakPx) e.peakPx = px
                continue
            }
            if (!e.resolved) resolve8326(e, markOf8326(e.mint))
            if (age >= 6L * WINDOW_MS_8326) iter.remove()
        }
    }

    private fun resolve8326(e: Entry8326, px: Double) {
        e.resolved = true
        if (px.isFinite() && px > e.peakPx) e.peakPx = px
        val sets = setsOf8326(e.oldAdmit, e.nowAdmit)
        val counts = seen.computeIfAbsent(e.lane) { IntArray(5) }
        synchronized(counts) {
            if (e.oldAdmit) counts[0]++
            if (e.nowAdmit) counts[1]++
            if (e.oldAdmit && e.nowAdmit) counts[2]++
            if (e.oldAdmit && !e.nowAdmit) counts[3]++
            if (!e.oldAdmit && e.nowAdmit) counts[4]++
        }
        val net = forwardNetPct8326(e.firstPx, px, e.costPct)
        val runner = e.firstPx > 0.0 && e.peakPx >= RUNNER_MULT_8326 * e.firstPx
        for (set in sets) {
            val a = acc.computeIfAbsent("${e.lane}|$set") { Acc8326() }
            synchronized(a) {
                if (net.isFinite()) { a.n++; a.sum += net; if (runner) a.runners++ } else a.unresolved++
            }
        }
    }

    /** Live whole-position closes of coins the 8114 decision admitted: lane -> [closes, wins, net SOL]. */
    private fun liveCloses8326(): Map<String, DoubleArray> {
        val admitted = HashMap<String, Long>()
        for (e in entries.values) if (e.oldAdmit) admitted["${e.mint}|${e.lane}"] = e.oldAdmitAtMs
        if (admitted.isEmpty()) return emptyMap()
        val out = HashMap<String, DoubleArray>()
        val closes = try { CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live") } catch (_: Throwable) { emptyList() }
        for (c in closes) {
            val lane = Entry8114Mode8326.canon8326(c.lane)
            val at = admitted["${c.mint}|$lane"] ?: continue
            if (c.atMs < at) continue
            val row = out.getOrPut(lane) { DoubleArray(3) }
            row[0] += 1.0
            if (c.realizedPnlSol > 0.0) row[1] += 1.0
            row[2] += c.realizedPnlSol
        }
        return out
    }

    fun statusLine8326(): String {
        try { sweep8326(System.currentTimeMillis(), force = false) } catch (_: Throwable) {}
        val lanes = (seen.keys + entries.values.map { it.lane }).toSortedSet()
        if (lanes.isEmpty()) return "shadow8114[no live coins evaluated]"
        val live = try { liveCloses8326() } catch (_: Throwable) { emptyMap() }
        val pending = entries.values.count { !it.resolved }
        return "shadow8114[pending=$pending " + lanes.joinToString(" · ") { lane ->
            val c = seen[lane]?.let { synchronized(it) { it.copyOf() } } ?: IntArray(5)
            fun set(name: String): String {
                val a = acc["$lane|$name"] ?: return "$name -"
                return synchronized(a) {
                    if (a.n == 0) "$name n0 u${a.unresolved}"
                    else "$name n${a.n} fwd60${"%+.1f".format(a.sum / a.n)}% run${"%.0f".format(100.0 * a.runners / a.n)}%" + (if (a.unresolved > 0) " u${a.unresolved}" else "")
                }
            }
            val lc = live[lane]
            "entry8114[$lane: buys8114 ${c[0]} / buysNow ${c[1]} / both ${c[2]} / onlyOld ${c[3]} / onlyNew ${c[4]}; " +
                listOf("buys8114", "buysNow", "both", "onlyOld", "onlyNew").joinToString(", ") { set(it) } +
                "; live8114 " + (lc?.let { "n${it[0].toInt()} w${it[1].toInt()} ${"%+.4f".format(it[2])}SOL" } ?: "n0") + "]"
        } + "]"
    }
}
