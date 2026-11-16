package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * V5.0.8806 §THE_OWNERS_READ — owner: "add my manual read please" (he trades by chart shape, volume and who is winning the
 * tape: buyers dominating, lows refilled into new highs; first pullback, base breakout, staircase, ignition), and "the
 * entry strategy for project sniper, shitcoin, moonshot and core all need to be totally redesigned".
 *
 * What the data said first (3,946 launch paths across four exports; 611 with a clean first hour): buying a launch at an
 * arbitrary moment nets about zero after cost with a -23 % median — the lanes' "launch continuation" entries are that. So
 * the read is encoded as what a hand trader actually waits for, on 10-second bars of every witnessed print of a coin
 * (its whole life, not only the first hour):
 *  - PULLBACK  : it ran, gave back 15-55 %, the low held above the base, price turned back up off that low, buyers lead;
 *  - BASE      : three tight minutes, then a close over the range high on at least twice the trades, buyers lead;
 *  - IGNITION  : a quiet tape goes at least 3x busier with buyers >= 65 %, before the price has already gone;
 *  - STAIRCASE : three rising swing lows and rising highs, bought on the step (near the last higher low), buyers lead.
 * and managed the way profitable hand traders manage (small fixed size, cut what does not move, scale out): stop -20 %,
 * a third off at +50 %, a third at +150 %, the rest trailed 35 % under the peak and never under entry, out after ten flat
 * minutes ([Ladder]).
 *
 * Honest status: on the first-hour export none of the four is PROVEN (each within one standard error of zero, n 61-113).
 * So every fire opens a PAPER ladder here and is graded forward on this device; a pattern is ARMED only when the later
 * half of its closed fires — the half it was not judged on — has n >= [ARM_MIN_N] and mean - SE > 0 with the earlier half
 * positive too. Only an ARMED pattern's fire is admission evidence for the meme lanes (CandidateAdmission8136, as the launch
 * sniper's is) and adds the coin to the watchlist; it never answers hard safety, sellability or the economics at size.
 */
object ManualRead8806 {
    enum class Pattern { PULLBACK, BASE, IGNITION, STAIRCASE }

    private const val BAR_MS = 10_000L
    private const val MAX_BARS = 240
    private const val MAX_MINTS = 800
    private const val COST_PCT = 3.5                 // the measured round trip (5.0.8802 report: lastCost 3.33 %)
    private const val EVIDENCE_TTL_MS = 120_000L
    private const val REFIRE_GAP_MS = 10L * 60_000L
    private const val ARM_MIN_N = 30
    private const val KEEP_OUTCOMES = 600
    private const val FILE = "manual_read_8806.json"

    /** One 10-second bar: time (ms), open, high, low, close, buys, sells. */
    class Bar(val t: Long, val o: Double, var h: Double, var l: Double, var c: Double, var buys: Int, var sells: Int)

    // ── the read (pure) ──

    private fun swingLows(c: DoubleArray, k: Int): List<Int> =
        (k until c.size - k).filter { i -> (i - k..i + k).all { c[i] <= c[it] } }
    private fun swingHighs(c: DoubleArray, k: Int): List<Int> =
        (k until c.size - k).filter { i -> (i - k..i + k).all { c[i] >= c[it] } }

    /** Pure: the patterns the newest bar of [bars] completes (oldest first; needs 9+ bars). */
    fun detect(bars: List<Bar>): Set<Pattern> {
        val n = bars.size
        if (n < 9) return emptySet()
        val i = n - 1
        val t = bars[i].t
        var b = 0; var s = 0; var prior = 0
        for (x in bars) { if (x.t > t - 60_000L) { b += x.buys; s += x.sells } else if (x.t > t - 180_000L) prior += x.buys + x.sells }
        val tx = b + s
        val txPrior = prior / 2.0                       // per minute over the two minutes before
        val share = if (tx > 0) b.toDouble() / tx else 0.0
        val c = bars[i].c
        val out = HashSet<Pattern>()
        // PULLBACK
        var pk = 0
        for (k in 0..i) if (bars[k].h > bars[pk].h) pk = k
        val peak = bars[pk].h
        val base = if (pk > 0) (0 until pk).minOf { bars[it].c } else bars[0].c
        val lowSince = if (pk < i) (pk..i).minOf { bars[it].l } else c
        val run = peak / base - 1.0
        val dd = c / peak - 1.0
        if (run >= 0.6 && dd in -0.55..-0.15 && lowSince > base * 1.1 && c >= lowSince * 1.08 && lowSince < bars[i].l && share >= 0.55 && tx >= 6)
            out += Pattern.PULLBACK
        // BASE
        val k0 = bars.indexOfFirst { it.t >= t - 180_000L }
        if (k0 >= 0 && i - k0 >= 6) {
            val rh = (k0 until i).maxOf { bars[it].h }; val rl = (k0 until i).minOf { bars[it].l }
            if (rl > 0.0 && rh / rl < 1.25 && c > rh && tx >= 2 * maxOf(txPrior, 1.0) && share >= 0.6) out += Pattern.BASE
        }
        // IGNITION
        val k1 = bars.indexOfFirst { it.t >= t - 60_000L }
        val c60 = bars[maxOf(0, k1 - 1)].c
        if (tx >= 3 * maxOf(txPrior, 2.0) && tx >= 12 && share >= 0.65 && c / c60 - 1.0 < 0.4) out += Pattern.IGNITION
        // STAIRCASE
        if (n >= 21) {
            val cs = DoubleArray(n) { bars[it].c }
            val lows = swingLows(cs, 2); val highs = swingHighs(cs, 2)
            if (lows.size >= 3 && highs.size >= 2) {
                val a = cs[lows[lows.size - 3]]; val m = cs[lows[lows.size - 2]]; val z = cs[lows[lows.size - 1]]
                if (a < m && m < z && cs[highs[highs.size - 2]] < cs[highs[highs.size - 1]] && c >= z && c <= z * 1.12 && share >= 0.55)
                    out += Pattern.STAIRCASE
            }
        }
        return out
    }

    /**
     * The hand trader's ladder on one position entered at [entry] ([atMs]): [step] feeds each later price range and
     * returns the trade's net % (of the whole position, after cost) when it is over, else null.
     */
    class Ladder(val entry: Double, val atMs: Long) {
        private var left = 1.0; private var pnl = 0.0; private var peak = entry; private var took = 0
        fun step(t: Long, high: Double, low: Double, close: Double): Double? {
            // V5.0.8816 — a stop or trail fills at the price seen when it is crossed, never better than its line: a candle
            // that gaps through closes at its close (5815972e paper stops: first seen 15 points past their level).
            if (took == 0 && low <= entry * 0.80) return pnl + left * (minOf(0.80, close / entry) - 1.0) * 100.0 - COST_PCT
            if (high > peak) peak = high
            if (took == 0 && high >= entry * 1.5) { pnl += 50.0 / 3.0; left -= 1.0 / 3.0; took = 1 }
            if (took == 1 && high >= entry * 2.5) { pnl += 150.0 / 3.0; left -= 1.0 / 3.0; took = 2 }
            if (took >= 1) { val line = maxOf(entry, peak * 0.65); if (low <= line) return pnl + left * (minOf(line, close) / entry - 1.0) * 100.0 - COST_PCT }
            if (took == 0 && t - atMs >= 600_000L && close < entry * 1.10) return pnl + left * (close / entry - 1.0) * 100.0 - COST_PCT
            return null
        }
        /** Net % if closed at [price] now (a coin that stopped printing). */
        fun closeAt(price: Double): Double = pnl + left * (price / entry - 1.0) * 100.0 - COST_PCT
    }

    /** Pure: (n, mean, standard error) of [xs]. */
    fun record(xs: List<Double>): Triple<Int, Double, Double> {
        if (xs.isEmpty()) return Triple(0, 0.0, 0.0)
        val m = xs.average()
        val se = if (xs.size > 1) sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)) / sqrt(xs.size.toDouble()) else Double.NaN
        return Triple(xs.size, m, se)
    }

    /** Pure: ARMED when the later half of [outcomes] (time order) has n >= [ARM_MIN_N] and mean - SE > 0, the earlier half > 0. */
    fun armed(outcomes: List<Double>): Boolean {
        val half = outcomes.size / 2
        val early = record(outcomes.take(half)); val late = record(outcomes.drop(half))
        return late.first >= ARM_MIN_N && late.third.isFinite() && late.second - late.third > 0.0 && early.second > 0.0
    }

    // ── live ──

    private class St {
        val bars = ArrayDeque<Bar>()
        val open = HashMap<Pattern, Ladder>()
        val lastFire = HashMap<Pattern, Long>()
        var lastSeen = 0L
    }
    private class Fire(val pattern: Pattern, val atMs: Long, val n: Int, val mean: Double, val se: Double, val runners: Double)

    private val st = ConcurrentHashMap<String, St>()
    private val outcomes = ConcurrentHashMap<Pattern, ArrayDeque<Double>>()
    private val fires = ConcurrentHashMap<String, Fire>()
    private val shadowFires = AtomicLong(); private val armedFires = AtomicLong(); private val served = AtomicLong(); private val closed = AtomicLong()
    @Volatile private var loaded = false
    @Volatile private var savedAt = 0L

    private fun outcomesOf(p: Pattern): List<Double> = outcomes[p]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    private fun book(p: Pattern, net: Double, nowMs: Long) {
        val q = outcomes.getOrPut(p) { ArrayDeque() }
        synchronized(q) { q.addLast(net.coerceIn(-100.0, 500.0)); while (q.size > KEEP_OUTCOMES) q.removeFirst() }
        closed.incrementAndGet()
        if (nowMs - savedAt > 60_000L) { savedAt = nowMs; save() }
    }

    /** TradeTape8100.launchEvidence8750: one witnessed print of [mint]. Cheap; never throws. */
    fun onPrint8806(mint: String, eventMs: Long, priceSol: Double, isBuy: Boolean, recvMs: Long) {
        try {
            if (!(priceSol > 0.0) || !priceSol.isFinite() || eventMs <= 0L) return
            if (!loaded) { loaded = true; restore() }
            if (st.size >= MAX_MINTS && !st.containsKey(mint)) {
                // a coin that stopped printing: its open paper ladders close at its last price, then it is forgotten
                for (e in st.values) if (recvMs - e.lastSeen > 30L * 60_000L) synchronized(e) {
                    val px = e.bars.lastOrNull()?.c
                    if (px != null) for ((p, l) in e.open) book(p, l.closeAt(px), recvMs)
                    e.open.clear()
                }
                st.entries.removeIf { it.value.open.isEmpty() && recvMs - it.value.lastSeen > 5L * 60_000L }
                if (st.size >= MAX_MINTS) return
            }
            val s = st.computeIfAbsent(mint) { St() }
            var newBar = false
            val done = ArrayList<Pair<Pattern, Double>>(1)
            var fired: Set<Pattern> = emptySet()
            synchronized(s) {
                s.lastSeen = recvMs
                val slot = eventMs / BAR_MS * BAR_MS
                val last = s.bars.lastOrNull()
                if (last != null && slot < last.t) return                       // a late print never rewrites a closed bar
                if (last == null || slot > last.t) {
                    s.bars.addLast(Bar(slot, priceSol, priceSol, priceSol, priceSol, if (isBuy) 1 else 0, if (isBuy) 0 else 1))
                    while (s.bars.size > MAX_BARS) s.bars.removeFirst()
                    newBar = last != null
                } else {
                    if (priceSol > last.h) last.h = priceSol
                    if (priceSol < last.l) last.l = priceSol
                    last.c = priceSol
                    if (isBuy) last.buys++ else last.sells++
                }
                // every open paper ladder sees this print
                val iter = s.open.entries.iterator()
                while (iter.hasNext()) {
                    val e = iter.next()
                    val net = e.value.step(eventMs, priceSol, priceSol, priceSol)
                    if (net != null) { done.add(e.key to net); iter.remove() }
                }
                // a bar just closed: read the tape as of that bar (live prints only: a replayed print fires nothing)
                if (newBar && recvMs - eventMs in -5_000L..15_000L && s.bars.size >= 10) {
                    val closedBars = s.bars.toList().dropLast(1)
                    fired = detect(closedBars).filter { p -> p !in s.open && eventMs - (s.lastFire[p] ?: 0L) >= REFIRE_GAP_MS }.toSet()
                    for (p in fired) { s.lastFire[p] = eventMs; s.open[p] = Ladder(priceSol, eventMs) }   // entered at the next print
                }
            }
            for ((p, net) in done) book(p, net, recvMs)
            for (p in fired) onFire(mint, p, recvMs, priceSol)
        } catch (_: Throwable) {}
    }

    private fun onFire(mint: String, p: Pattern, nowMs: Long, priceSol: Double) {
        val xs = outcomesOf(p)
        if (!armed(xs)) { shadowFires.incrementAndGet(); try { PipelineHealthCollector.labelInc("MANUAL_READ_SHADOW_FIRE_8806_${p.name}") } catch (_: Throwable) {}; return }
        val late = record(xs.drop(xs.size / 2))
        armedFires.incrementAndGet()
        if (fires.size > 2_000) fires.entries.removeIf { nowMs - it.value.atMs > EVIDENCE_TTL_MS }
        fires[mint] = Fire(p, nowMs, late.first, late.second, late.third, xs.count { it >= 100.0 }.toDouble() / xs.size)
        try { PipelineHealthCollector.labelInc("MANUAL_READ_ARMED_FIRE_8806_${p.name}") } catch (_: Throwable) {}
        try {
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val supply = MintSupply8418.wholeOrZero8418(mint).takeIf { it > 0.0 } ?: 1.0e9
            com.lifecyclebot.engine.GlobalTradeRegistry.addToWatchlist(mint, mint.take(6), "MANUAL_READ_8806", initialMcap = priceSol * supply * solUsd)
        } catch (_: Throwable) {}
    }

    /** CandidateAdmission8136: an ARMED pattern's fresh fire on [mint] as market-cell evidence, or null. */
    fun evidence8806(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val f = fires[mint]?.takeIf { nowMs - it.atMs in 0L..EVIDENCE_TTL_MS } ?: return null
        served.incrementAndGet()
        return CandidateAdmission8136.Evidence8136("MANUAL_READ_8806", f.pattern.name, f.n, f.mean, f.se, runnerRate = f.runners, netOfCost = true)
    }

    // ── persistence ──

    private fun file(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE) } } catch (_: Throwable) { null }

    private fun save() {
        try {
            val f = file() ?: return
            val o = JSONObject()
            for (p in Pattern.values()) o.put(p.name, JSONArray().also { a -> outcomesOf(p).forEach { a.put(it) } })
            val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    private fun restore() {
        try {
            val f = file()?.takeIf { it.exists() } ?: return
            val o = JSONObject(f.readText())
            for (p in Pattern.values()) o.optJSONArray(p.name)?.let { a ->
                val q = outcomes.getOrPut(p) { ArrayDeque() }
                synchronized(q) { for (i in 0 until a.length()) q.addLast(a.getDouble(i)) }
            }
        } catch (_: Throwable) {}
    }

    fun statusLine8806(): String {
        val rows = Pattern.values().joinToString(" ") { p ->
            val xs = outcomesOf(p); val half = xs.size / 2
            val e = record(xs.take(half)); val l = record(xs.drop(half))
            fun r(t: Triple<Int, Double, Double>) = "n${t.first}/${"%+.1f".format(t.second)}%±${if (t.third.isFinite()) "%.1f".format(t.third) else "-"}"
            "${p.name}[${if (armed(xs)) "ARMED" else "SHADOW"} early=${r(e)} late=${r(l)} wr=${if (xs.isEmpty()) "-" else "%.0f%%".format(xs.count { it > 0.0 } * 100.0 / xs.size)} " +
                "run>=100%=${xs.count { it >= 100.0 }}]"
        }
        return "manualRead8806[coins=${st.size} open=${st.values.sumOf { it.open.size }} closed=${closed.get()} shadowFires=${shadowFires.get()} armedFires=${armedFires.get()} " +
            "evidenceServed=${served.get()} $rows rule=the owner's read on 10 s bars of witnessed prints; paper ladder -20% stop, 1/3 at +50%, 1/3 at +150%, " +
            "rest trailed 35% (never under entry), out after 10 flat minutes; net of ${COST_PCT}% cost; armed = later half n>=$ARM_MIN_N and mean-SE>0, earlier half > 0]"
    }
}
