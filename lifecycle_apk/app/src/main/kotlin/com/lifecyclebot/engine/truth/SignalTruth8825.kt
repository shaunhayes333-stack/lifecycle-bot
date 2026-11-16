package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8825 §WHICH_WAY_EACH_SIGNAL_POINTS — owner: "that exposes a deeper issue. signals are totally wrong".
 *
 * Clean-epoch labels (16 Nov 08:30-20:15 UTC, 1,734 coins, first decision of each coin, 15-minute target, net of cost):
 * every buying-activity signal the hand-written playbook setups trigger on points the wrong way, in both halves of the
 * window. Spearman with the outcome (half A / half B), and the top fifth's dump share (net <= -50 %) against the bottom's:
 *   newBuyers15s -0.25 / -0.26  dumps 33 % vs 13 %      buyTx15s   -0.26 / -0.15  24 % vs 5 %
 *   txVel        -0.18 / -0.21  21 % vs 3 %             tapeNetSol -0.20 / -0.11  26 % vs 15 %
 *   buyersPerMin -0.20 / -0.14  31 % vs 12 %            runupFromLow -0.25 / -0.18  45 % vs 24 %
 *   dd (the dip) -0.23 / -0.21  29 % vs 8 %
 * and the survivors' facts point the right way: age +0.36 / +0.22, secsSincePrint +0.35 / +0.26, pxPeak (near its high)
 * +0.23 / +0.20. Inside each age band the activity signs hold (under one minute buyTx15s -0.13, 1-10 min -0.23): a burst
 * of buying is the top of that burst, and a coin bought into it is the exit. ACCELERATING_TAPE, FAST_CROWD,
 * NET_INFLOW_SURGE, FIRST_DIP_BOUGHT, ... each require one of those readings above a hand threshold.
 *
 * This measures the same thing on the phone, continuously, from every label the edge model learns (and the clean dataset
 * replayed at boot): per signal and per age band, the coin's first observation in the band is kept (a bounded ring), and
 * the rank correlation with the booked outcome is read separately on the older and the newer half. A direction is called
 * only when both halves agree past their own 95 % noise band (1.96 / sqrt(n - 1)); otherwise it is noise. The report
 * prints the table, and the strategy lab's prompt receives the agreed directions, so a proposed strategy is built on what
 * the labels say rather than on what a setup assumed.
 */
object SignalTruth8825 {
    /** The signals measured: the playbook's activity triggers, their dips and runs, and the survival facts beside them. */
    val SIGNALS: List<String> = listOf(
        "buyTx15s", "newBuyers15s8666", "txVel", "buyersPerMin", "tapeNetSol", "tapeCrowd", "repeatBuyers60s", "bp",
        "chg5m", "chg1h", "runup", "runupFromLow", "dd", "pxPeak", "fromPeak", "liq", "mcap", "top", "holders", "secsSincePrint",
    )
    /** Age bands (minutes): under 1, 1-10, 10-60, 1-24 h, older. */
    private val BAND_EDGES_MIN = doubleArrayOf(1.0, 10.0, 60.0, 1_440.0)
    val BANDS: List<String> = listOf("<1m", "1-10m", "10-60m", "1-24h", ">1d")
    internal const val RING = 200
    internal const val MIN_N = 100

    private class Ring {
        val v = FloatArray(RING); val y = FloatArray(RING)
        var n = 0; var next = 0
        fun add(value: Double, net: Double) { v[next] = value.toFloat(); y[next] = net.toFloat(); next = (next + 1) % RING; if (n < RING) n++ }
        /** Oldest first. */
        fun ordered(): Pair<DoubleArray, DoubleArray> {
            val start = if (n < RING) 0 else next
            return DoubleArray(n) { v[(start + it) % RING].toDouble() } to DoubleArray(n) { y[(start + it) % RING].toDouble() }
        }
    }
    private val rings = Array(SIGNALS.size * BANDS.size) { Ring() }
    // one observation per coin per band: a lossy fixed table of hashes (a collision forgets an old coin, never adds one twice)
    private val seen = LongArray(1 shl 14)
    private val noted = AtomicLong(0L)
    private val repeats = AtomicLong(0L)

    /** Pure: the age band of [ageMin], or -1 when unknown. */
    fun band8825(ageMin: Double): Int {
        if (!ageMin.isFinite() || ageMin < 0.0) return -1
        for (i in BAND_EDGES_MIN.indices) if (ageMin < BAND_EDGES_MIN[i]) return i
        return BAND_EDGES_MIN.size
    }

    /** Pure: average ranks (ties share their mean rank). */
    internal fun ranks8825(x: DoubleArray): DoubleArray {
        val idx = x.indices.sortedBy { x[it] }
        val r = DoubleArray(x.size)
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && x[idx[j + 1]] == x[idx[i]]) j++
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) r[idx[k]] = avg
            i = j + 1
        }
        return r
    }

    /** Pure: Spearman's rank correlation of [x] and [y] (NaN when fewer than 3 or no spread). */
    fun spearman8825(x: DoubleArray, y: DoubleArray): Double {
        if (x.size != y.size || x.size < 3) return Double.NaN
        val rx = ranks8825(x); val ry = ranks8825(y)
        val mx = rx.average(); val my = ry.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in rx.indices) { val a = rx[i] - mx; val b = ry[i] - my; sxy += a * b; sxx += a * a; syy += b * b }
        return if (sxx <= 0.0 || syy <= 0.0) Double.NaN else sxy / kotlin.math.sqrt(sxx * syy)
    }

    enum class Way { HIGHER_BETTER, HIGHER_WORSE, NOISE, THIN }
    data class Read(val signal: String, val band: String, val n: Int, val rhoOld: Double, val rhoNew: Double, val way: Way)

    /** Pure: the direction both halves of [x] / [y] (oldest first) agree on past their own noise band. */
    fun way8825(x: DoubleArray, y: DoubleArray): Triple<Double, Double, Way> {
        if (x.size < MIN_N) return Triple(Double.NaN, Double.NaN, Way.THIN)
        val h = x.size / 2
        val a = spearman8825(x.copyOfRange(0, h), y.copyOfRange(0, h))
        val b = spearman8825(x.copyOfRange(h, x.size), y.copyOfRange(h, y.size))
        if (!a.isFinite() || !b.isFinite()) return Triple(a, b, Way.NOISE)
        val barA = 1.96 / kotlin.math.sqrt((h - 1).toDouble()); val barB = 1.96 / kotlin.math.sqrt((x.size - h - 1).toDouble())
        val way = when {
            a > barA && b > barB -> Way.HIGHER_BETTER
            a < -barA && b < -barB -> Way.HIGHER_WORSE
            else -> Way.NOISE
        }
        return Triple(a, b, way)
    }

    /**
     * One labelled decision of [mint] at age [ageMin] (minutes), outcome [netPct]; [get] reads a signal by its dataset
     * column name (NaN = unobserved, never measured). EdgeModel8537.learn (live) and its boot replay (the clean dataset).
     */
    fun note8825(mint: String, ageMin: Double, netPct: Double, get: (String) -> Double) {
        if (mint.isBlank() || !netPct.isFinite()) return
        val b = band8825(ageMin); if (b < 0) return
        val h = com.lifecyclebot.engine.chart.OwnBars8816.hash8816("$mint|$b").let { if (it == 0L) 1L else it }
        val slot = (h xor (h ushr 29)).toInt() and (seen.size - 1)
        synchronized(rings) {
            if (seen[slot] == h) { repeats.incrementAndGet(); return }
            seen[slot] = h
            for (s in SIGNALS.indices) {
                val v = try { get(SIGNALS[s]) } catch (_: Throwable) { Double.NaN }
                if (v.isFinite()) rings[s * BANDS.size + b].add(v, netPct)
            }
        }
        noted.incrementAndGet()
    }

    /** Every (signal, band) read, the agreed directions first, strongest first. */
    fun reads8825(): List<Read> {
        val out = ArrayList<Read>()
        val snaps = synchronized(rings) { rings.map { it.ordered() } }
        for (s in SIGNALS.indices) for (b in BANDS.indices) {
            val (x, y) = snaps[s * BANDS.size + b]
            val (a, bb, w) = way8825(x, y)
            out.add(Read(SIGNALS[s], BANDS[b], x.size, a, bb, w))
        }
        return out.sortedWith(compareBy<Read> { it.way == Way.NOISE || it.way == Way.THIN }
            .thenByDescending { if (it.rhoOld.isFinite() && it.rhoNew.isFinite()) minOf(kotlin.math.abs(it.rhoOld), kotlin.math.abs(it.rhoNew)) else -1.0 })
    }

    private fun fmt(r: Read) = "${r.signal}@${r.band} ${"%+.2f".format(r.rhoOld)}/${"%+.2f".format(r.rhoNew)} n${r.n}"

    /** StrategyLab prompt: the directions both halves agree on (empty until measured). */
    fun promptLine8825(): String {
        val agreed = reads8825().filter { it.way == Way.HIGHER_WORSE || it.way == Way.HIGHER_BETTER }
        if (agreed.isEmpty()) return ""
        val worse = agreed.filter { it.way == Way.HIGHER_WORSE }.take(12).joinToString(", ") { fmt(it) }
        val better = agreed.filter { it.way == Way.HIGHER_BETTER }.take(12).joinToString(", ") { fmt(it) }
        return "MEASURED SIGNAL DIRECTIONS (this phone's labels, signal@age band, rank correlation older/newer half; both halves agree):\n" +
            "  HIGHER IS WORSE: ${worse.ifBlank { "-" }}\n  HIGHER IS BETTER: ${better.ifBlank { "-" }}"
    }

    fun statusLine8825(): String {
        val r = reads8825()
        val worse = r.filter { it.way == Way.HIGHER_WORSE }; val better = r.filter { it.way == Way.HIGHER_BETTER }
        val measured = r.count { it.way != Way.THIN }
        return "signalTruth8825[noted=${noted.get()} repeatsSkipped=${repeats.get()} cellsMeasured=$measured/${r.size} " +
            "higherWorse=${worse.size}{${worse.take(10).joinToString(" | ") { fmt(it) }.ifBlank { "-" }}} " +
            "higherBetter=${better.size}{${better.take(10).joinToString(" | ") { fmt(it) }.ifBlank { "-" }}} " +
            "rule=first observation per coin per age band, rank correlation with the booked outcome on each half, a direction only when both halves pass 1.96/sqrt(n-1)]"
    }

    internal fun resetForTest8825() { synchronized(rings) { for (i in rings.indices) rings[i] = Ring(); seen.fill(0L) }; noted.set(0L); repeats.set(0L) }
}
