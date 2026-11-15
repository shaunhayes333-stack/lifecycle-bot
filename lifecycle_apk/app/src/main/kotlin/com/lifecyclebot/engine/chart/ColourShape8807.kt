package com.lifecyclebot.engine.chart

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.ManualRead8806
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * V5.0.8807 §COLOUR_WITH_SHAPE — owner, with twenty of his own charts: "the green and red candles, despite which direction
 * the chart is going, repeat into a consecutive pattern which can then be used against the shape to somewhat determine the
 * chart's next moves. look at the reds and greens as buys and sells" — and "look at the top 50 performing sol coins of the
 * last 12 months", on 1-minute and 5-minute candles.
 *
 * What was already here, and why it never did this (5.0.8802 device report): CandleColors7968 learns the last five candle
 * colours ALONE, scores a sequence by a touch (+2 ATR before -1 ATR), was taught mostly by the bot's own 15-second candles
 * of minutes-old coins, said BUY 116 times in a session, and its setup (COLOUR_SEQUENCE) had zero graded trades in twelve
 * reports. On 611 clean recorded launches the colours alone, chosen on the earlier two thirds, made +1.2 % on the later
 * third against a +1.4 % base: nothing.
 *
 * So this reads what he reads:
 *  - the RUNS: the last three runs of same-coloured candles and how long each is (a green run of 4, a red run of 2, ...),
 *    and the newest candle's own letter (big green / green / flat / red / big red);
 *  - AGAINST THE SHAPE: where the close sits in the last [LOOK] candles' range (low / mid / high), which way that stretch
 *    went (leg up / leg down / flat), and whether the newest run traded on more or less volume than the stretch's usual.
 *  - THE TEXTBOOK (owner's cheat sheets, the same day): the named candles read with their wicks — hammer, shooting star,
 *    engulfing, morning / evening star, three soldiers / crows, harami, piercing line / dark cloud, doji — each against the
 *    same zone and leg ("C:NAME|zone leg"), and the named chart shapes read from swing pivots at the candle that breaks
 *    them — double bottom / top, (inverse) head and shoulders, ascending / descending triangle, falling / rising wedge,
 *    bull / bear flag, cup and handle ("P:NAME"). A bearish name is measured like any other: it is not shorted, it shows
 *    in the table as what a buy there made.
 * No pattern is believed because a poster names it. Every such pattern on every downloaded 1-minute and 5-minute meme chart (ChartSources7955: the top Solana runners of the
 * last year first) is scored by what a TRADE made — the hand trader's scale-out ladder (ManualRead8806.Ladder), net of
 * cost — one outcome per chart per pattern, in two halves of charts that share no coin. A pattern is PROVEN only when BOTH
 * halves have at least [MIN_CHARTS] charts and mean - [Z] SE > 0: it has to hold on charts it was not chosen from.
 *
 * Live: a proven pattern completing on a coin's one-minute tape opens a PAPER ladder; the learner is ARMED (its fire becomes
 * admission evidence for the meme lanes) only when its own forward record passes ManualRead8806.armed. It never answers hard
 * safety, sellability or the economics at size.
 */
object ColourShape8807 {
    const val LOOK = 30
    private const val SHAPE_LOOK = 60
    private const val MAX_FWD = 120
    private const val MIN_FUTURE = 10
    private const val MIN_CHARTS = 12
    private const val Z = 2.0
    private const val MAX_BAR_MS = 5L * 60_000L
    private const val EVIDENCE_TTL_MS = 120_000L
    private const val REFIRE_GAP_MS = 10L * 60_000L
    private const val STALE_OPEN_MS = 3L * 60 * 60_000L
    private const val KEEP_OUTCOMES = 600
    private const val FILE = "colour_shape_8807.json"

    /** One half's record of a pattern: charts, sum and sum of squares of the per-chart mean net %. */
    class Half(var n: Int = 0, var sum: Double = 0.0, var sumSq: Double = 0.0) {
        val mean: Double get() = if (n > 0) sum / n else 0.0
        val se: Double get() = if (n > 1) sqrt(((sumSq - sum * sum / n) / (n - 1)).coerceAtLeast(0.0)) / sqrt(n.toDouble()) else Double.NaN
    }

    // ── the read (pure) ──

    private fun sign(letter: Char): Int = when (letter) { 'G', 'g' -> 1; 'R', 'r' -> -1; else -> 0 }

    /**
     * Pure: (fine, coarse) keys of the candle at [end] of [bars] (oldest first), or null with fewer than [LOOK] candles
     * before it or fewer than two colour runs. Fine = three runs, the newest letter, zone, leg and volume; coarse = two runs,
     * zone and leg.
     */
    fun keys8807(bars: List<Bar7950>, end: Int): Pair<String, String>? {
        val start = end - LOOK + 1
        if (start < 0 || end >= bars.size) return null
        var ref = 0.0
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        val vols = DoubleArray(LOOK)
        for (i in start..end) {
            val b = bars[i]
            if (!(b.c > 0.0) || !(b.o > 0.0) || !(b.h >= b.l)) return null
            ref += kotlin.math.abs(b.c - b.o)
            if (b.h > hi) hi = b.h
            if (b.l < lo) lo = b.l
            vols[i - start] = b.v.coerceAtLeast(0.0)
        }
        ref /= LOOK
        if (!(hi > lo)) return null
        // runs, newest first: flat candles belong to no run
        val runSign = IntArray(3); val runLen = IntArray(3); val runVol = DoubleArray(3)
        var r = -1
        var i = end
        while (i >= start) {
            val s = sign(CandleColors7968.letter7968(bars[i], ref))
            if (s != 0) {
                if (r < 0 || runSign[r] != s) { if (r == 2) break; r++; runSign[r] = s }
                runLen[r]++; runVol[r] += vols[i - start]
            }
            i--
        }
        if (r < 1) return null
        fun runKey(k: Int) = "${if (runSign[k] > 0) 'g' else 'r'}${runLen[k].coerceAtMost(4)}"
        val last = bars[end]
        val pos = (last.c - lo) / (hi - lo)
        val zone = if (pos < 1.0 / 3) 'L' else if (pos > 2.0 / 3) 'H' else 'M'
        val net = (last.c - bars[start].o) / (hi - lo)
        val leg = if (net > 1.0 / 3) 'U' else if (net < -1.0 / 3) 'D' else 'F'
        val med = vols.sorted()[LOOK / 2]
        val perBar = runVol[0] / runLen[0]
        val vol = if (!(med > 0.0)) '?' else if (perBar >= 1.5 * med) 'V' else if (perBar <= med / 1.5) 'v' else '-'
        val two = runKey(1) + runKey(0)
        val three = if (r >= 2) runKey(2) + two else two
        return "$three:${CandleColors7968.letter7968(last, ref)}|$zone$leg$vol" to "$two|$zone$leg"
    }

    /** Pure: the named candlestick patterns the candle at [end] completes ([ref] = the stretch's mean absolute body). */
    fun candles8807(bars: List<Bar7950>, end: Int, ref: Double): Set<String> {
        if (end < 2 || end >= bars.size || !(ref > 0.0)) return emptySet()
        val a = bars[end - 2]; val b = bars[end - 1]; val c = bars[end]
        fun body(x: Bar7950) = x.c - x.o
        fun size(x: Bar7950) = kotlin.math.abs(body(x))
        fun mid(x: Bar7950) = (x.o + x.c) / 2.0
        val out = HashSet<String>()
        val rng = c.h - c.l
        if (rng > 0.0) {
            val up = c.h - maxOf(c.o, c.c); val dn = minOf(c.o, c.c) - c.l
            if (size(c) < 0.1 * rng) out += "DOJI"
            else {
                if (dn >= 2.0 * size(c) && up <= size(c)) out += "HAMMER"
                if (up >= 2.0 * size(c) && dn <= size(c)) out += "SHOOTING_STAR"
            }
        }
        if (body(b) < 0.0 && body(c) > 0.0 && size(c) > size(b) && c.c > b.o) out += "ENGULF_BULL"
        if (body(b) > 0.0 && body(c) < 0.0 && size(c) > size(b) && c.c < b.o) out += "ENGULF_BEAR"
        if (body(a) < 0.0 && size(a) >= ref && size(b) < 0.5 * ref && body(c) > 0.0 && c.c > mid(a)) out += "MORNING_STAR"
        if (body(a) > 0.0 && size(a) >= ref && size(b) < 0.5 * ref && body(c) < 0.0 && c.c < mid(a)) out += "EVENING_STAR"
        if (body(a) > 0.0 && body(b) > 0.0 && body(c) > 0.0 && minOf(size(a), size(b), size(c)) >= 0.5 * ref && b.c > a.c && c.c > b.c) out += "THREE_SOLDIERS"
        if (body(a) < 0.0 && body(b) < 0.0 && body(c) < 0.0 && minOf(size(a), size(b), size(c)) >= 0.5 * ref && b.c < a.c && c.c < b.c) out += "THREE_CROWS"
        if (body(b) < 0.0 && size(b) >= ref && body(c) > 0.0 && c.c < b.o && c.o > b.c && size(c) < 0.6 * size(b)) out += "HARAMI_BULL"
        if (body(b) > 0.0 && size(b) >= ref && body(c) < 0.0 && c.c > b.o && c.o < b.c && size(c) < 0.6 * size(b)) out += "HARAMI_BEAR"
        if (body(b) < 0.0 && size(b) >= ref && body(c) > 0.0 && c.c > mid(b) && c.c < b.o) out += "PIERCING"
        if (body(b) > 0.0 && size(b) >= ref && body(c) < 0.0 && c.c < mid(b) && c.c > b.o) out += "DARK_CLOUD"
        return out
    }

    /** A confirmed swing of the closes: candle [i], close [v], a high or a low. */
    class Pivot8807(val i: Int, val v: Double, val high: Boolean)

    /** Pure: alternating swing highs and lows of the closes in [start]..[end] (a swing is the extreme of [k] candles each side). */
    fun pivots8807(bars: List<Bar7950>, start: Int, end: Int, k: Int = 3): List<Pivot8807> {
        val out = ArrayList<Pivot8807>()
        for (i in start + k..end - k) {
            val c = bars[i].c
            var isH = true; var isL = true
            for (j in i - k..i + k) { if (bars[j].c > c) isH = false; if (bars[j].c < c) isL = false }
            if (isH == isL) continue
            val p = Pivot8807(i, c, isH)
            val l = out.lastOrNull()
            if (l != null && l.high == p.high) { if ((p.high && p.v >= l.v) || (!p.high && p.v <= l.v)) out[out.size - 1] = p } else out += p
        }
        return out
    }

    /**
     * Pure: the named chart shapes the candle at [end] BREAKS (its close crosses the shape's line, or for a flag makes a new
     * five-candle closing extreme), read from the swings of the last [SHAPE_LOOK] candles. Sizes are fractions of that
     * stretch's range, so the same shape is the same shape at any price.
     */
    fun shapes8807(bars: List<Bar7950>, end: Int): Set<String> {
        val start = maxOf(0, end - SHAPE_LOOK + 1)
        if (end >= bars.size || end - start + 1 < LOOK) return emptySet()
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        for (i in start..end) { if (bars[i].h > hi) hi = bars[i].h; if (bars[i].l < lo) lo = bars[i].l }
        val r = hi - lo
        if (!(r > 0.0) || !(lo > 0.0)) return emptySet()
        val c = bars[end].c; val pc = bars[end - 1].c
        fun up(level: Double) = c > level && pc <= level
        fun down(level: Double) = c < level && pc >= level
        val ps = pivots8807(bars, start, end)
        val hs = ps.filter { it.high }; val ls = ps.filter { !it.high }
        fun maxHigh(from: Int, to: Int) = hs.filter { it.i in from..to }.maxOfOrNull { it.v }
        fun minLow(from: Int, to: Int) = ls.filter { it.i in from..to }.minOfOrNull { it.v }
        fun lowOf(from: Int, to: Int): Double { var m = Double.POSITIVE_INFINITY; for (i in from..to) if (bars[i].l < m) m = bars[i].l; return m }
        fun highOf(from: Int, to: Int): Double { var m = Double.NEGATIVE_INFINITY; for (i in from..to) if (bars[i].h > m) m = bars[i].h; return m }
        val out = HashSet<String>()
        if (ls.size >= 2) {
            val a = ls[ls.size - 2]; val b = ls[ls.size - 1]
            val neck = maxHigh(a.i, b.i)
            if (neck != null && kotlin.math.abs(a.v - b.v) <= 0.12 * r && neck - maxOf(a.v, b.v) >= 0.2 * r && up(neck)) out += "DOUBLE_BOTTOM"
        }
        if (hs.size >= 2) {
            val a = hs[hs.size - 2]; val b = hs[hs.size - 1]
            val neck = minLow(a.i, b.i)
            if (neck != null && kotlin.math.abs(a.v - b.v) <= 0.12 * r && minOf(a.v, b.v) - neck >= 0.2 * r && down(neck)) out += "DOUBLE_TOP"
            // cup and handle: two rims level, a deep cup between, a shallow handle after, the close breaks the rim
            val cup = lowOf(a.i, b.i); val depth = minOf(a.v, b.v) - cup
            if (kotlin.math.abs(a.v - b.v) <= 0.12 * r && depth >= 0.3 * r && b.i - a.i >= 10 && b.i < end) {
                val handle = b.v - lowOf(b.i, end)
                if (handle > 0.0 && handle <= depth / 3.0 && up(maxOf(a.v, b.v))) out += "CUP_HANDLE"
            }
        }
        if (ls.size >= 3) {
            val a = ls[ls.size - 3]; val b = ls[ls.size - 2]; val d = ls[ls.size - 1]
            val neck = maxHigh(a.i, d.i)
            if (neck != null && b.v < minOf(a.v, d.v) - 0.1 * r && kotlin.math.abs(a.v - d.v) <= 0.15 * r && up(neck)) out += "INV_HEAD_SHOULDERS"
        }
        if (hs.size >= 3) {
            val a = hs[hs.size - 3]; val b = hs[hs.size - 2]; val d = hs[hs.size - 1]
            val neck = minLow(a.i, d.i)
            if (neck != null && b.v > maxOf(a.v, d.v) + 0.1 * r && kotlin.math.abs(a.v - d.v) <= 0.15 * r && down(neck)) out += "HEAD_SHOULDERS"
        }
        if (hs.size >= 2 && ls.size >= 2) {
            val h1 = hs[hs.size - 2].v; val h2 = hs[hs.size - 1].v; val l1 = ls[ls.size - 2].v; val l2 = ls[ls.size - 1].v
            if (kotlin.math.abs(h1 - h2) <= 0.1 * r && l2 > l1 + 0.08 * r && up(maxOf(h1, h2))) out += "ASC_TRIANGLE"
            if (kotlin.math.abs(l1 - l2) <= 0.1 * r && h2 < h1 - 0.08 * r && down(minOf(l1, l2))) out += "DESC_TRIANGLE"
            if (h2 < h1 - 0.05 * r && l2 < l1 - 0.02 * r && h1 - h2 > l1 - l2 && up(h2)) out += "FALLING_WEDGE"
            if (l2 > l1 + 0.05 * r && h2 > h1 + 0.02 * r && l2 - l1 > h2 - h1 && down(l2)) out += "RISING_WEDGE"
        }
        if (end - start >= 6) {
            var top5 = Double.NEGATIVE_INFINITY; var bot5 = Double.POSITIVE_INFINITY
            for (i in end - 5 until end) { if (bars[i].c > top5) top5 = bars[i].c; if (bars[i].c < bot5) bot5 = bars[i].c }
            hs.lastOrNull()?.let { p ->
                // bull flag: a pole into a swing high near the stretch's top, a pause that gives back under half of it, a new push
                val base = lowOf(maxOf(start, p.i - 15), p.i); val pole = p.v - base
                if (p.v >= lo + 0.8 * r && pole >= 0.5 * r && end - p.i in 4..25 && lowOf(p.i, end - 1) >= p.v - 0.5 * pole && c > top5 && c < p.v + 0.15 * r) out += "BULL_FLAG"
            }
            ls.lastOrNull()?.let { p ->
                val peak = highOf(maxOf(start, p.i - 15), p.i); val pole = peak - p.v
                if (p.v <= lo + 0.2 * r && pole >= 0.5 * r && end - p.i in 4..25 && highOf(p.i, end - 1) <= p.v + 0.5 * pole && c < bot5 && c > p.v - 0.15 * r) out += "BEAR_FLAG"
            }
        }
        return out
    }

    /**
     * Pure: every key the candle at [end] carries — the colour runs against the shape (fine, coarse), each named candle
     * against the zone and leg, each named chart shape it breaks. Empty when the stretch is too short to read.
     */
    fun allKeys8807(bars: List<Bar7950>, end: Int): List<String> {
        val out = ArrayList<String>(6)
        val runs = keys8807(bars, end)
        if (runs != null) { out += runs.first; out += runs.second }
        val start = end - LOOK + 1
        if (start < 0 || end >= bars.size) return out
        var ref = 0.0; var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        for (i in start..end) { val b = bars[i]; if (!(b.c > 0.0) || !(b.o > 0.0) || !(b.h >= b.l)) return out; ref += kotlin.math.abs(b.c - b.o); if (b.h > hi) hi = b.h; if (b.l < lo) lo = b.l }
        ref /= LOOK
        if (!(hi > lo)) return out
        val pos = (bars[end].c - lo) / (hi - lo)
        val zone = if (pos < 1.0 / 3) 'L' else if (pos > 2.0 / 3) 'H' else 'M'
        val net = (bars[end].c - bars[start].o) / (hi - lo)
        val leg = if (net > 1.0 / 3) 'U' else if (net < -1.0 / 3) 'D' else 'F'
        for (n in candles8807(bars, end, ref)) out += "C:$n|$zone$leg"
        for (n in shapes8807(bars, end)) out += "P:$n"
        return out
    }

    /**
     * Pure: what a trade entered at the close of candle [end] made (net %, the scale-out ladder; ten flat candles is the
     * stale exit on any timeframe), or null with fewer than [MIN_FUTURE] candles after it.
     */
    fun outcome8807(bars: List<Bar7950>, end: Int): Double? {
        if (end + MIN_FUTURE >= bars.size) return null
        val entry = bars[end].c
        if (!(entry > 0.0)) return null
        val ladder = ManualRead8806.Ladder(entry, 0L)
        val last = minOf(end + MAX_FWD, bars.size - 1)
        for (j in end + 1..last) {
            val b = bars[j]
            ladder.step((j - end) * 60_000L, b.h, b.l, b.c)?.let { return it.coerceIn(-100.0, 500.0) }
        }
        return ladder.closeAt(bars[last].c).coerceIn(-100.0, 500.0)
    }

    /** Pure: PROVEN = both halves of charts hold [MIN_CHARTS]+ and mean - [Z] SE > 0. */
    fun proven8807(a: Half, b: Half): Boolean =
        a.n >= MIN_CHARTS && b.n >= MIN_CHARTS && a.se.isFinite() && b.se.isFinite() && a.mean - Z * a.se > 0.0 && b.mean - Z * b.se > 0.0

    /** Pure: the candle spacing of [bars] (ms, the median gap), 0 when unknown. */
    fun spacingMs8807(bars: List<Bar7950>): Long {
        if (bars.size < 3) return 0L
        val gaps = LongArray(bars.size - 1) { bars[it + 1].t - bars[it].t }
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /**
     * Pure: CoinGecko /coins/markets rows (category solana-meme-coins, price_change_percentage=1y) -> the [n] names with the
     * largest one-year gain, best first.
     */
    fun topByYear8807(arr: JSONArray, n: Int): List<String> {
        val rows = ArrayList<Pair<String, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val chg = o.optDouble("price_change_percentage_1y_in_currency", Double.NaN)
            if (name.isNotBlank() && chg.isFinite()) rows += name to chg
        }
        return rows.sortedByDescending { it.second }.take(n).map { it.first }
    }

    // ── learning from downloaded charts ──

    private val lock = Any()
    private val table = HashMap<String, Array<Half>>()
    private val charts = AtomicLong(); private val windows = AtomicLong(); private val skippedCoarse = AtomicLong()
    @Volatile private var loaded = false
    @Volatile private var savedAt = 0L

    private fun halves(key: String): Array<Half> = table.getOrPut(key) { arrayOf(Half(), Half()) }

    /**
     * ChartSources7955.runJob: one downloaded meme chart ([coin] names the pool, shared by its timeframes so both land in
     * the same half). Charts coarser than five-minute candles are not read. Returns the windows scored.
     */
    fun learnSeries8807(coin: String, bars: List<Bar7950>): Int {
        try {
            if (bars.size < LOOK + MIN_FUTURE + 1) return 0
            val gap = spacingMs8807(bars)
            if (gap <= 0L || gap > MAX_BAR_MS) { skippedCoarse.incrementAndGet(); return 0 }
            ensureLoaded()
            val per = HashMap<String, DoubleArray>()          // key -> [sum, count] within this chart
            var scored = 0
            for (end in LOOK - 1 until bars.size - MIN_FUTURE) {
                val keys = allKeys8807(bars, end)
                if (keys.isEmpty()) continue
                val net = outcome8807(bars, end) ?: continue
                for (k in keys) { val a = per.getOrPut(k) { DoubleArray(2) }; a[0] += net; a[1] += 1.0 }
                scored++
            }
            if (scored == 0) return 0
            val half = (coin.hashCode() and 1)
            synchronized(lock) {
                for ((k, a) in per) { val m = a[0] / a[1]; val h = halves(k)[half]; h.n++; h.sum += m; h.sumSq += m * m }
            }
            windows.addAndGet(scored.toLong())
            if (charts.incrementAndGet() % 20L == 0L) save()
            return scored
        } catch (_: Throwable) { return 0 }
    }

    // ── V5.0.8810 §THE_BOTS_OWN_CHARTS_TEACH_IT ──
    // 5.0.8809 on the device, 20 minutes: charts=0. Every downloaded meme chart comes from GeckoTerminal, whose slot the live
    // candle feed owns (chart sources: geckoterminal calls=32 ok=6 skips=110 queue=561) — at that pace the table would take
    // days. The bot already draws its own one-minute charts of every coin it watches (ChartReader7950: 258 tapes, two hours
    // each). When a tape is retired (quiet for two hours, or trimmed for room) its whole chart, future included, is scored
    // here once, as one chart of its coin, on one background thread; nothing is downloaded.
    private const val TAPE_QUEUE_8810 = 64
    private val tapeQueue8810 = java.util.concurrent.ArrayBlockingQueue<Pair<String, List<Bar7950>>>(TAPE_QUEUE_8810)
    private val tapeWorker8810 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val tapesOffered8810 = AtomicLong(); private val tapesLearned8810 = AtomicLong(); private val tapesDropped8810 = AtomicLong()

    /** ChartReader7950: [mint]'s one-minute tape is being retired; its chart is scored once, off the caller's thread. */
    fun learnTape8810(mint: String, bars: List<Bar7950>) {
        if (mint.isBlank() || bars.size < LOOK + MIN_FUTURE + 1) return
        tapesOffered8810.incrementAndGet()
        if (!tapeQueue8810.offer(mint to bars)) { tapesDropped8810.incrementAndGet(); return }
        // the worker runs in the app; a unit test drains the queue itself (drainTapesForTest8810)
        if ((try { com.lifecyclebot.AATEApp.appContextOrNull() } catch (_: Throwable) { null }) == null) return
        if (!tapeWorker8810.compareAndSet(false, true)) return
        try {
            Thread({
                // drain, release, and take the queue again if a tape arrived between the last poll and the release
                do {
                    try {
                        while (true) {
                            val (m, b) = tapeQueue8810.poll() ?: break
                            if (learnSeries8807("LIVE|$m", b) > 0) tapesLearned8810.incrementAndGet()
                        }
                    } catch (_: Throwable) {} finally { tapeWorker8810.set(false) }
                } while (tapeQueue8810.isNotEmpty() && tapeWorker8810.compareAndSet(false, true))
            }, "colour-shape-tapes-8810").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) { tapeWorker8810.set(false) }
    }

    /** Test seam (V5.0.8810): score the queued tapes on the caller's thread. */
    internal fun drainTapesForTest8810(): Int {
        var n = 0
        while (true) { val (m, b) = tapeQueue8810.poll() ?: break; if (learnSeries8807("LIVE|$m", b) > 0) { tapesLearned8810.incrementAndGet(); n++ } }
        return n
    }

    /** The proven key among [keys] whose weaker half pays most, with its halves; null when none is proven. */
    private fun lookup(keys: List<String>): Triple<String, Half, Half>? = synchronized(lock) {
        var best: Triple<String, Half, Half>? = null
        for (k in keys) {
            val h = table[k] ?: continue
            if (!proven8807(h[0], h[1])) continue
            val b = best
            if (b == null || minOf(h[0].mean, h[1].mean) > minOf(b.second.mean, b.third.mean)) best = Triple(k, h[0], h[1])
        }
        best
    }

    // ── live: a proven pattern completing on a coin's one-minute tape ──

    private class Open(val key: String, val ladder: ManualRead8806.Ladder, @Volatile var lastPx: Double, val atMs: Long)
    private class Fire(val key: String, val atMs: Long, val n: Int, val mean: Double, val se: Double, val runners: Double)

    private val open = ConcurrentHashMap<String, Open>()
    private val lastBar = ConcurrentHashMap<String, Long>()
    private val lastFire = ConcurrentHashMap<String, Long>()
    private val fires = ConcurrentHashMap<String, Fire>()
    private val live = ArrayDeque<Double>()
    private val liveReads = AtomicLong(); private val shadowFires = AtomicLong(); private val armedFires = AtomicLong()
    private val served = AtomicLong(); private val closed = AtomicLong()

    private fun liveOutcomes(): List<Double> = synchronized(live) { live.toList() }

    private fun book(net: Double, nowMs: Long) {
        synchronized(live) { live.addLast(net.coerceIn(-100.0, 500.0)); while (live.size > KEEP_OUTCOMES) live.removeFirst() }
        closed.incrementAndGet()
        if (nowMs - savedAt > 60_000L) save()
    }

    /** ChartReader7950.onPrice: every observed price steps the coin's open paper ladder. Cheap when none is open. */
    fun onPrice8807(mint: String, price: Double, atMs: Long) {
        if (open.isEmpty()) return
        val o = open[mint] ?: return
        try {
            o.lastPx = price
            val net = synchronized(o) { o.ladder.step(atMs, price, price, price) } ?: return
            if (open.remove(mint, o)) book(net, atMs)
        } catch (_: Throwable) {}
    }

    /** ChartReader7950.read: the coin's one-minute candles (oldest first; the newest may still be forming). */
    fun onBars8807(mint: String, bars: List<Bar7950>, nowMs: Long) {
        try {
            if (bars.size < LOOK + 1) return
            ensureLoaded()
            val end = if (bars[bars.size - 1].t + 60_000L > nowMs) bars.size - 2 else bars.size - 1
            val t = bars[end].t
            if (lastBar.put(mint, t) == t) return                          // this closed candle was already read
            if (lastBar.size > 4_000) lastBar.entries.removeIf { nowMs - it.value > STALE_OPEN_MS }
            // a paper ladder on a coin that stopped printing closes at its last price
            if (open.size > 200) for ((m, o) in open) if (nowMs - o.atMs > STALE_OPEN_MS && open.remove(m, o)) book(o.ladder.closeAt(o.lastPx), nowMs)
            val keys = allKeys8807(bars, end)
            if (keys.isEmpty()) return
            liveReads.incrementAndGet()
            val hit = lookup(keys) ?: return
            if (open.containsKey(mint) || nowMs - (lastFire[mint] ?: 0L) < REFIRE_GAP_MS) return
            val px = bars[bars.size - 1].c
            if (!(px > 0.0)) return
            lastFire[mint] = nowMs
            open[mint] = Open(hit.first, ManualRead8806.Ladder(px, nowMs), px, nowMs)
            val xs = liveOutcomes()
            if (!ManualRead8806.armed(xs)) { shadowFires.incrementAndGet(); try { PipelineHealthCollector.labelInc("COLOUR_SHAPE_SHADOW_FIRE_8807") } catch (_: Throwable) {}; return }
            val late = ManualRead8806.record(xs.drop(xs.size / 2))
            armedFires.incrementAndGet()
            if (fires.size > 2_000) fires.entries.removeIf { nowMs - it.value.atMs > EVIDENCE_TTL_MS }
            fires[mint] = Fire(hit.first, nowMs, late.first, late.second, late.third, xs.count { it >= 100.0 }.toDouble() / xs.size)
            try { PipelineHealthCollector.labelInc("COLOUR_SHAPE_ARMED_FIRE_8807") } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /** CandidateAdmission8136: the ARMED learner's fresh fire on [mint] as market-cell evidence, or null. */
    fun evidence8807(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val f = fires[mint]?.takeIf { nowMs - it.atMs in 0L..EVIDENCE_TTL_MS } ?: return null
        served.incrementAndGet()
        return CandidateAdmission8136.Evidence8136("COLOUR_SHAPE_8807", f.key, f.n, f.mean, f.se, runnerRate = f.runners, netOfCost = true)
    }

    // ── persistence ──

    private fun file(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE) } } catch (_: Throwable) { null }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val f = file()?.takeIf { it.exists() } ?: return
                val o = JSONObject(f.readText())
                charts.set(o.optLong("charts")); windows.set(o.optLong("windows"))
                o.optJSONObject("keys")?.let { ks ->
                    for (k in ks.keys()) {
                        val a = ks.optJSONArray(k) ?: continue
                        if (a.length() < 6) continue
                        table[k] = arrayOf(Half(a.getInt(0), a.getDouble(1), a.getDouble(2)), Half(a.getInt(3), a.getDouble(4), a.getDouble(5)))
                    }
                }
                o.optJSONArray("live")?.let { a -> synchronized(live) { for (i in 0 until a.length()) live.addLast(a.getDouble(i)) } }
            } catch (_: Throwable) {}
        }
    }

    private fun save() {
        try {
            val f = file() ?: return
            savedAt = System.currentTimeMillis()
            val ks = JSONObject()
            synchronized(lock) {
                for ((k, h) in table) ks.put(k, JSONArray().put(h[0].n).put(h[0].sum).put(h[0].sumSq).put(h[1].n).put(h[1].sum).put(h[1].sumSq))
            }
            val o = JSONObject().put("charts", charts.get()).put("windows", windows.get()).put("keys", ks)
                .put("live", JSONArray().also { a -> liveOutcomes().forEach { a.put(it) } })
            val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    // ── the table, for the report ──

    private fun row(k: String, h: Array<Half>): String {
        fun r(x: Half) = "n${x.n}/${"%+.1f".format(x.mean)}%±${if (x.se.isFinite()) "%.1f".format(x.se) else "-"}"
        return "$k[A ${r(h[0])} B ${r(h[1])}]"
    }

    fun statusLine8807(): String {
        ensureLoaded()
        val (keys, provenRows, best, worst) = synchronized(lock) {
            val both = table.entries.filter { it.value[0].n >= MIN_CHARTS && it.value[1].n >= MIN_CHARTS }
            val worse = { e: Map.Entry<String, Array<Half>> -> minOf(e.value[0].mean, e.value[1].mean) }
            val better = { e: Map.Entry<String, Array<Half>> -> maxOf(e.value[0].mean, e.value[1].mean) }
            listOf(
                "${table.size} measured(both halves n>=$MIN_CHARTS)=${both.size}",
                both.filter { proven8807(it.value[0], it.value[1]) }.sortedByDescending(worse).take(8).joinToString(" ") { row(it.key, it.value) }.ifBlank { "-" },
                both.sortedByDescending(worse).take(6).joinToString(" ") { row(it.key, it.value) }.ifBlank { "-" },
                both.sortedBy(better).take(6).joinToString(" ") { row(it.key, it.value) }.ifBlank { "-" },
            )
        }
        val textbook = synchronized(lock) {
            table.entries.filter { it.key.startsWith("P:") && it.value[0].n + it.value[1].n >= MIN_CHARTS }.sortedByDescending { it.value[0].n + it.value[1].n }
                .joinToString(" ") { row(it.key, it.value) }.ifBlank { "-" }
        }
        val xs = liveOutcomes(); val half = xs.size / 2
        val e = ManualRead8806.record(xs.take(half)); val l = ManualRead8806.record(xs.drop(half))
        fun r(t: Triple<Int, Double, Double>) = "n${t.first}/${"%+.1f".format(t.second)}%±${if (t.third.isFinite()) "%.1f".format(t.third) else "-"}"
        return "colourShape8807[charts=${charts.get()} windows=${windows.get()} coarserThan5m=${skippedCoarse.get()} " +
            "ownTapes8810{offered=${tapesOffered8810.get()} learned=${tapesLearned8810.get()} dropped=${tapesDropped8810.get()}} keys=$keys proven[$provenRows] " +
            "bestInBothHalves[$best] worstInBothHalves[$worst] chartShapes[$textbook] | live ${if (ManualRead8806.armed(xs)) "ARMED" else "SHADOW"} reads=${liveReads.get()} " +
            "open=${open.size} closed=${closed.get()} shadowFires=${shadowFires.get()} armedFires=${armedFires.get()} evidenceServed=${served.get()} " +
            "early=${r(e)} late=${r(l)} rule=key = last three colour runs with lengths:newest candle|zone in the last $LOOK candles (L/M/H), leg (U/D/F), " +
            "run volume (V high, v low); C:name|zone leg = a named candle; P:name = a named chart shape at its break; scored by the scale-out ladder net of cost, one outcome per chart, two coin-disjoint halves; " +
            "proven = both halves n>=$MIN_CHARTS and mean-${Z.toInt()}SE>0; armed = ManualRead8806.armed on its own paper fires]"
    }

    internal fun resetForTest8807() {
        synchronized(lock) { table.clear(); loaded = true }
        synchronized(live) { live.clear() }
        tapeQueue8810.clear(); tapesOffered8810.set(0); tapesLearned8810.set(0); tapesDropped8810.set(0)
        open.clear(); lastBar.clear(); lastFire.clear(); fires.clear()
        charts.set(0); windows.set(0); skippedCoarse.set(0); liveReads.set(0); shadowFires.set(0); armedFires.set(0); served.set(0); closed.set(0)
    }
}
