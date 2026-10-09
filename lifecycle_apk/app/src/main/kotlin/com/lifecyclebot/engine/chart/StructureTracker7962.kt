package com.lifecyclebot.engine.chart

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.cortex.CortexLedger7885
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7962 — market structure, the owner's own read.
 *
 * "Buyers dominate, the chart is rising, lows get refilled into new highs" — and
 * he gets out when that breaks. Every price print and tape trade the chart reader
 * already receives (MarkBars7948.note7948 -> ChartReader7950.onPrice, and
 * DataOrchestrator.onTapeTrade7773 -> ChartReader7950.onTrade) is folded here into
 * 15-second and 1-minute bars with the buy/sell SOL split. On each timeframe:
 *
 *  - swing highs / lows (a bar whose high/low beats [PIVOT_K] bars each side),
 *  - the running count of higher highs + higher lows,
 *  - the last swing low, the buy share of recent volume,
 *  - whether the latest low was REFILLED (a close back above the swing high
 *    that preceded it).
 *
 * ENTRY: HL_RECLAIM (a higher low after a higher high, reclaimed, buyers >= 50%)
 * is a LanePlaybook7907 setup: classified, labelled and graded per lane by the
 * playbook/Cortex books, earning authority only from its own record.
 *
 * EXIT: on a held position, the first close below the last higher low after a
 * run (a lower low confirmed) is a STRUCTURE_BREAK signal. It is learned, not
 * fixed: every signal records its counterfactual (the price [HORIZON_MS] later
 * against the exit price) per lane x timeframe, and the signal only sells once
 * that record shows exiting beat holding (n >= [ACT_MIN_N], mean saved above the
 * lane's all-in cost and one SE above zero). Until then it is shadow only.
 */
object StructureTracker7962 {
    private const val TF15 = 15_000L
    private const val TF60 = 60_000L
    private const val KEEP15_MS = 45L * 60_000L
    private const val KEEP60_MS = 120L * 60_000L
    private const val MAX_MINTS = 1_500
    private const val PIVOT_K = 2
    private const val READ_TTL_MS = 2_000L
    private const val HORIZON_MS = 5L * 60_000L
    private const val ACT_MIN_N = 20.0
    private const val MIN_HOLD_MS = 75_000L
    private const val SELL_RETRY_MS = 20_000L
    private const val PERSIST_KEY = "STRUCTURE_BREAK_7962"

    /** One bar: time, OHLC, buy / sell SOL. */
    data class Bar7962(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val buySol: Double = 0.0, val sellSol: Double = 0.0)

    /** One swing point (bar index, price, high or low). */
    data class Swing7962(val idx: Int, val px: Double, val high: Boolean)

    /** A structure read of one timeframe. */
    data class Read7962(
        val bars: Int, val hhHl: Int, val lastLow: Double, val priorHigh: Double, val higherHigh: Boolean,
        val higherLow: Boolean, val refilled: Boolean, val reclaimFresh: Boolean, val buyShare: Double,
        val lastClose: Double, val brokeStructure: Boolean,
    )

    private class Tape {
        // bucket -> [o, h, l, c, buySol, sellSol, lastTs]
        val b15 = TreeMap<Long, DoubleArray>()
        val b60 = TreeMap<Long, DoubleArray>()
        @Volatile var lastMs = 0L
        @Volatile var lastPx = 0.0
    }

    private val tapes = ConcurrentHashMap<String, Tape>()

    // ── feed (ChartReader7950.onPrice / onTrade) ──

    fun onPrice7962(mint: String, priceUsd: Double, atMs: Long) {
        if (mint.isBlank() || !(priceUsd > 0.0) || !priceUsd.isFinite() || atMs <= 0L) return
        val tape = tapes.computeIfAbsent(mint) { Tape() }
        synchronized(tape) {
            fold(tape.b15, atMs / TF15, priceUsd, atMs)
            fold(tape.b60, atMs / TF60, priceUsd, atMs)
            if (atMs >= tape.lastMs) { tape.lastMs = atMs; tape.lastPx = priceUsd }
            val o15 = (tape.lastMs - KEEP15_MS) / TF15
            while (tape.b15.isNotEmpty() && tape.b15.firstKey() < o15) tape.b15.pollFirstEntry()
            val o60 = (tape.lastMs - KEEP60_MS) / TF60
            while (tape.b60.isNotEmpty() && tape.b60.firstKey() < o60) tape.b60.pollFirstEntry()
        }
        if (tapes.size > MAX_MINTS) {
            tapes.entries.filter { atMs - it.value.lastMs > KEEP60_MS }.forEach { tapes.remove(it.key, it.value) }
            if (tapes.size > MAX_MINTS) tapes.entries.sortedBy { it.value.lastMs }.take(tapes.size - MAX_MINTS).forEach { tapes.remove(it.key, it.value) }
        }
    }

    fun onTrade7962(mint: String, solAmount: Double, isBuy: Boolean, atMs: Long) {
        if (!(solAmount > 0.0) || !solAmount.isFinite()) return
        val tape = tapes[mint] ?: return
        synchronized(tape) {
            for (map in listOf(tape.b15 to TF15, tape.b60 to TF60)) {
                val b = map.first[atMs / map.second] ?: map.first.lastEntry()?.value ?: continue
                if (isBuy) b[4] += solAmount else b[5] += solAmount
            }
        }
    }

    private fun fold(map: TreeMap<Long, DoubleArray>, k: Long, px: Double, atMs: Long) {
        val b = map[k]
        if (b == null) {
            val prev = map.lowerEntry(k)?.value?.get(3) ?: px
            map[k] = doubleArrayOf(prev, maxOf(prev, px), minOf(prev, px), px, 0.0, 0.0, atMs.toDouble())
            return
        }
        if (px > b[1]) b[1] = px
        if (px < b[2]) b[2] = px
        if (atMs.toDouble() >= b[6]) { b[3] = px; b[6] = atMs.toDouble() }
    }

    /** Closed bars of one timeframe, oldest first; quiet buckets carry the close forward. */
    private fun closedBars(map: TreeMap<Long, DoubleArray>, tf: Long, nowMs: Long): List<Bar7962> {
        val out = ArrayList<Bar7962>(map.size + 8)
        var prevK = -1L
        var prevC = Double.NaN
        val formingK = nowMs / tf
        for ((k, b) in map) {
            if (k >= formingK) break
            if (prevK >= 0 && prevC.isFinite()) {
                var g = prevK + 1
                while (g < k && out.size < 400) { out += Bar7962(g * tf, prevC, prevC, prevC, prevC); g++ }
            }
            out += Bar7962(k * tf, b[0], b[1], b[2], b[3], b[4], b[5])
            prevK = k; prevC = b[3]
        }
        return if (out.size > 240) out.subList(out.size - 240, out.size) else out
    }

    // ── pure structure maths ──

    /** Pure: alternating swing points of [bars] (a pivot beats [k] bars each side; equal pivots keep the more extreme). */
    fun swings7962(bars: List<Bar7962>, k: Int = PIVOT_K): List<Swing7962> {
        val raw = ArrayList<Swing7962>()
        for (i in k until bars.size - k) {
            val h = bars[i].h
            val l = bars[i].l
            var isH = true
            var isL = true
            for (j in 1..k) {
                if (!(h >= bars[i - j].h && h > bars[i + j].h)) isH = false
                if (!(l <= bars[i - j].l && l < bars[i + j].l)) isL = false
            }
            if (isH) raw += Swing7962(i, h, true)
            if (isL) raw += Swing7962(i, l, false)
        }
        val out = ArrayList<Swing7962>()
        for (s in raw) {
            val last = out.lastOrNull()
            if (last != null && last.high == s.high) {
                val better = if (s.high) s.px >= last.px else s.px <= last.px
                if (better) out[out.size - 1] = s
            } else out += s
        }
        return out
    }

    /** Pure: the structure read of closed [bars] ([lastPx] = the live price), or null when too short. */
    fun read7962(bars: List<Bar7962>, lastPx: Double, buyWindow: Int): Read7962? {
        if (bars.size < 2 * PIVOT_K + 3) return null
        val sw = swings7962(bars)
        var run = 0
        var prevH = Double.NaN
        var prevL = Double.NaN
        for (s in sw) {
            if (s.high) { if (prevH.isFinite()) run = if (s.px > prevH) run + 1 else 0; prevH = s.px }
            else { if (prevL.isFinite()) run = if (s.px > prevL) run + 1 else 0; prevL = s.px }
        }
        val lows = sw.filter { !it.high }
        val lastLowS = lows.lastOrNull()
        val lastLow = lastLowS?.px ?: Double.NaN
        val prevLow = if (lows.size >= 2) lows[lows.size - 2].px else Double.NaN
        val highsBefore = if (lastLowS == null) emptyList() else sw.filter { it.high && it.idx < lastLowS.idx }
        val priorHigh = highsBefore.lastOrNull()?.px ?: Double.NaN
        val highBefore = if (highsBefore.size >= 2) highsBefore[highsBefore.size - 2].px else Double.NaN
        val higherHigh = priorHigh.isFinite() && highBefore.isFinite() && priorHigh > highBefore
        val higherLow = lastLow.isFinite() && prevLow.isFinite() && lastLow > prevLow
        var refilled = false
        var firstAbove = -1
        if (lastLowS != null && priorHigh.isFinite()) {
            for (i in lastLowS.idx + 1 until bars.size) if (bars[i].c > priorHigh) { refilled = true; firstAbove = i; break }
        }
        val px = if (lastPx.isFinite() && lastPx > 0.0) lastPx else bars.last().c
        val reclaimFresh = refilled && px > priorHigh && bars.size - 1 - firstAbove <= 4
        val tail = bars.takeLast(buyWindow.coerceAtLeast(1))
        val buy = tail.sumOf { it.buySol }
        val tot = buy + tail.sumOf { it.sellSol }
        val share = if (tot > 0.0) buy / tot else Double.NaN
        val close = bars.last().c
        // A run of 2+ higher swings whose last higher low is closed under: the first lower low.
        val broke = run >= 2 && higherLow && lastLow.isFinite() && close < lastLow
        return Read7962(bars.size, run, lastLow, priorHigh, higherHigh, higherLow, refilled, reclaimFresh, share, close, broke)
    }

    /** Pure: HL_RECLAIM — a higher low after a higher high, reclaimed now, buyers >= 50%. */
    fun hlReclaimFires7962(r: Read7962?): Boolean =
        r != null && r.higherHigh && r.higherLow && r.reclaimFresh && r.buyShare.isFinite() && r.buyShare >= 0.5

    /** Pure: percent saved by exiting at [exitPx] rather than holding to [laterPx] (positive = exiting won). */
    fun savedPct7962(exitPx: Double, laterPx: Double): Double =
        if (exitPx > 0.0 && laterPx > 0.0 && exitPx.isFinite() && laterPx.isFinite()) (exitPx - laterPx) / exitPx * 100.0 else Double.NaN

    /** Pure: has a lane x timeframe break record earned the right to sell? */
    fun breakActive7962(st: CortexLedger7885.Stat?, costPct: Double): Boolean {
        if (st == null || st.n < ACT_MIN_N) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        val hurdle = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        return st.mean() > hurdle && st.mean() - se > 0.0
    }

    // ── reads ──

    private val readCache = ConcurrentHashMap<String, Pair<Long, Read7962?>>()

    private fun readTf(mint: String, tf: Long, nowMs: Long): Read7962? {
        val ck = "$mint|$tf"
        readCache[ck]?.let { (at, r) -> if (nowMs - at in 0L..READ_TTL_MS) return r }
        val tape = tapes[mint] ?: return null
        val (bars, px) = synchronized(tape) { closedBars(if (tf == TF15) tape.b15 else tape.b60, tf, nowMs) to tape.lastPx }
        val r = read7962(bars, px, if (tf == TF15) 20 else 5)
        if (readCache.size > 6_000) readCache.clear()
        readCache[ck] = nowMs to r
        return r
    }

    /** V5.0.7967 — RunnerGrab7967: the 15-second and 1-minute structure reads of [mint] now (cached 2 s). */
    fun reads7967(mint: String, nowMs: Long = System.currentTimeMillis()): Pair<Read7962?, Read7962?> = try {
        readTf(mint, TF15, nowMs) to readTf(mint, TF60, nowMs)
    } catch (_: Throwable) { null to null }

    private val hlFires = AtomicLong(0)

    /** LanePlaybook7907.features: does HL_RECLAIM fire for [mint] on either timeframe now? */
    fun hlReclaim7962(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ok = try { hlReclaimFires7962(readTf(mint, TF15, nowMs)) || hlReclaimFires7962(readTf(mint, TF60, nowMs)) } catch (_: Throwable) { false }
        if (ok) hlFires.incrementAndGet()
        return ok
    }

    /**
     * CostLedger7962: the market price of [mint] at [atMs] — the close of the last
     * bucket wholly before it (15 s, else 1 min), if that print is under 2 min old.
     */
    fun markAt7962(mint: String, atMs: Long): Double? {
        val tape = tapes[mint] ?: return null
        return synchronized(tape) {
            fun pick(map: TreeMap<Long, DoubleArray>, tf: Long): Double? {
                val b = map.lowerEntry(atMs / tf)?.value ?: return null
                return if (atMs - b[6].toLong() in 0L..120_000L && b[3] > 0.0) b[3] else null
            }
            pick(tape.b15, TF15) ?: pick(tape.b60, TF60)
        }
    }

    // ── held-position exit (SpikeCapture7943.rapidMark) ──

    private class Pending(val mint: String, val key: String, val exitPx: Double, val atMs: Long)

    private val books = HashMap<String, CortexLedger7885.Stat>()     // lane|tf
    private val pending = ConcurrentHashMap<String, Pending>()        // mint|entry|tf
    private val signalled = ConcurrentHashMap<String, Long>()          // mint|entry|tf -> first signal
    private val lastSell = ConcurrentHashMap<String, Long>()
    private val shadow = AtomicLong(0)
    private val acted = AtomicLong(0)
    private val expired = AtomicLong(0)
    @Volatile private var lastResolveMs = 0L
    @Volatile private var loaded = false

    private fun lane(raw: String): String {
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }
        return c.ifBlank { raw.trim().uppercase() }.ifBlank { "UNKNOWN" }
    }

    /** A held position's structure break: recorded always, sold on only once its lane's record says exiting wins. */
    fun heldExit7962(ts: TokenState, px: Double?, nowMs: Long, sell: (TokenState, Double, String) -> Unit) {
        resolveMaybe(nowMs)
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L || nowMs - pos.entryTime < MIN_HOLD_MS) return
        if (px == null || !(px > 0.0)) return
        // The counterfactual is priced on this tape at both ends (one source, one unit).
        val tape = tapes[ts.mint] ?: return
        val mark = tape.lastPx.takeIf { it > 0.0 && nowMs - tape.lastMs <= 60_000L } ?: return
        val l = lane(pos.tradingMode)
        for ((tf, tag) in listOf(TF15 to "15S", TF60 to "1M")) {
            val r = readTf(ts.mint, tf, nowMs) ?: continue
            if (!r.brokeStructure) continue
            val pk = "${ts.mint}|${pos.entryTime}|$tag"
            val bookKey = "$l|$tag"
            if (signalled.putIfAbsent(pk, nowMs) == null) {
                if (signalled.size > 4_000) signalled.entries.removeIf { nowMs - it.value > 6L * 3_600_000L }
                if (pending.size < 2_000) pending[pk] = Pending(ts.mint, bookKey, mark, nowMs)
                try { PipelineHealthCollector.labelInc("STRUCTURE_BREAK_SIGNAL_7962_$tag") } catch (_: Throwable) {}
            }
            ensureLoaded()
            val cost = try { com.lifecyclebot.engine.truth.CostLedger7962.laneCostPct7962(l) } catch (_: Throwable) { 1.5 }
            val active = synchronized(this) { breakActive7962(books[bookKey], cost) }
            if (!active) { shadow.incrementAndGet(); continue }
            val sk = "${ts.mint}|${pos.entryTime}"
            if (nowMs - (lastSell[sk] ?: 0L) < SELL_RETRY_MS) return
            if (lastSell.size > 2_000) lastSell.clear()
            lastSell[sk] = nowMs
            acted.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("STRUCTURE_BREAK_EXIT_7962_$tag")
                ForensicLogger.lifecycle("STRUCTURE_BREAK_EXIT_7962", "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$l tf=$tag close=${r.lastClose} lastLow=${r.lastLow} run=${r.hhHl}")
            } catch (_: Throwable) {}
            sell(ts, 1.0, "STRUCTURE_BREAK_7962_$tag")
            return
        }
    }

    /** Grade every structure-break signal whose horizon has passed (exit price vs the price [HORIZON_MS] later). */
    private fun resolveMaybe(nowMs: Long) {
        if (nowMs - lastResolveMs < 5_000L) return
        lastResolveMs = nowMs
        ensureLoaded()
        var graded = 0
        for ((k, p) in pending.entries.toList()) {
            if (nowMs - p.atMs < HORIZON_MS) continue
            val later = markAt7962(p.mint, p.atMs + HORIZON_MS)
            val saved = if (later != null) savedPct7962(p.exitPx, later) else Double.NaN
            if (saved.isFinite()) {
                synchronized(this) { books.getOrPut(p.key) { CortexLedger7885.Stat() }.add(saved.coerceIn(-100.0, 100.0), false) }
                pending.remove(k)
                graded++
            } else if (nowMs - p.atMs > 3 * HORIZON_MS) {
                pending.remove(k)
                expired.incrementAndGet()
            }
        }
        if (graded > 0) persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                for (k in o.keys()) books[k] = CortexLedger7885.Stat().also { it.decode(o.optString(k)) }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) { org.json.JSONObject().also { o -> books.forEach { (k, v) -> o.put(k, v.encode()) } }.toString() }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    fun statusLine7962(): String {
        ensureLoaded()
        resolveMaybe(System.currentTimeMillis())
        val rec = synchronized(this) {
            books.entries.sortedByDescending { it.value.n }.take(10).joinToString(" ") { (k, st) ->
                val cost = try { com.lifecyclebot.engine.truth.CostLedger7962.laneCostPct7962(k.substringBefore('|')) } catch (_: Throwable) { 1.5 }
                "$k n${st.n.toInt()} saved${"%+.1f".format(st.mean())}%${if (breakActive7962(st, cost)) " ACTIVE" else " shadow"}"
            }.ifBlank { "-" }
        }
        return "tapes=${tapes.size} hlReclaimFires=${hlFires.get()} breakSignals=${signalled.size} pending=${pending.size} shadow=${shadow.get()} acted=${acted.get()} expired=${expired.get()} " +
            "rule=act when lane|tf n>=${ACT_MIN_N.toInt()} & mean saved(5m) > all-in cost & mean-SE>0 · record: $rec"
    }
}
