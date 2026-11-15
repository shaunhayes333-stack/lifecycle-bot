package com.lifecyclebot.hyperliquid

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * V5.0.8797 — the Hyperliquid desk's intelligence. Pure: it reads an aligned daily [Panel] and decides nothing by hand.
 *
 * Five families of book, each a well-documented edge on liquid futures (the owner's deep dive, Part 2):
 *  - TREND      per coin: short average over long average and close over the long average (Fieberg et al. 2025; the
 *               decade of BTC crossovers). Long-only or long/short. Lookbacks 20 / 40 / 60 / 100 days.
 *  - MOMENTUM   cross-sectional: the coins that rose most (risk-adjusted) over 7 / 14 / 30 / 60 days long, the weakest short.
 *  - CARRY      cross-sectional funding: short the coins whose longs pay the most funding, long those that pay least,
 *               over 3 / 7 / 14 days of realised funding.
 *  - REVERSAL   cross-sectional: fade the last 1 / 3 / 5 days' risk-adjusted move.
 *  - LEARNED    a ridge regression, refitted every [REFIT_DAYS] days on everything before the day it trades (never after),
 *               from ten features: returns over 1 / 5 / 20 / 60 days, the trend signal, funding, premium, volume surge,
 *               open-interest change and the market's own 20-day move; it predicts tomorrow's risk-adjusted return.
 * Every book is weighted by inverse 30-day volatility and scaled to a gross exposure of 1.
 *
 * Graded the bot's honest way: inside each family the variant is chosen on the FIRST half of the history and graded on
 * the SECOND half it never saw. The blend weights each family that earned on the first half by its first-half Sharpe;
 * the blend is graded on the second half. Costs are each coin's own (fee + measured spread and depth, HlCosts) on every
 * unit of weight traded, and every held weight pays or earns that coin's real funding.
 *
 * ARMED (may trade live) when the second half covers at least [MIN_TEST_DAYS] days and its mean daily return less one SE
 * is above zero. Leverage is half the Kelly fraction (mean / variance of daily returns) of the weaker of the two halves,
 * capped by the owner's maximum; it is zero when either half lost.
 */
object HlBrain {
    enum class Family { TREND, MOMENTUM, CARRY, REVERSAL, LEARNED }

    data class Strategy(val family: Family, val p: Int, val longShort: Boolean = true) {
        val id: String get() = when (family) {
            Family.TREND -> "trend$p${if (longShort) "LS" else "L"}"
            Family.LEARNED -> "learned$p"
            else -> family.name.lowercase() + p
        }
    }

    data class Result(val id: String, val days: Int, val meanPct: Double, val sePct: Double, val sharpe: Double,
                      val totalPct: Double, val maxDrawdownPct: Double, val exposure: Double, val turnover: Double)
    data class Pick(val family: Family, val chosen: Strategy, val train: Result, val test: Result?)
    data class Evaluation(val days: Int, val picks: List<Pick>, val blend: Map<Strategy, Double>, val train: Result?,
                          val test: Result?, val armed: Boolean, val kelly: Double, val leverage: Double, val targets: DoubleArray)

    const val MIN_TEST_DAYS = 120
    private const val VOL_DAYS = 30
    private const val REFIT_DAYS = 20
    private const val MIN_FIT_SAMPLES = 300
    private const val FEATURES = 10
    private val RIDGE = doubleArrayOf(0.001, 0.01, 0.1)

    val GRID: Map<Family, List<Strategy>> = linkedMapOf(
        Family.TREND to listOf(20, 40, 60, 100).flatMap { l -> listOf(Strategy(Family.TREND, l, false), Strategy(Family.TREND, l, true)) },
        Family.MOMENTUM to listOf(7, 14, 30, 60).map { Strategy(Family.MOMENTUM, it) },
        Family.CARRY to listOf(3, 7, 14).map { Strategy(Family.CARRY, it) },
        Family.REVERSAL to listOf(1, 3, 5).map { Strategy(Family.REVERSAL, it) },
        Family.LEARNED to RIDGE.indices.map { Strategy(Family.LEARNED, it) },
    )

    /**
     * Aligned daily data: [c][d] for coin c on day firstDay + d. close <= 0 / NaN = not listed or unknown; funding is the
     * day's summed hourly rate (NaN unknown, charged as 0); oiUsd / premium NaN until the desk has recorded them.
     */
    class Panel(val coins: List<String>, val firstDay: Long, val close: Array<DoubleArray>, val volUsd: Array<DoubleArray>,
                val funding: Array<DoubleArray>, val oiUsd: Array<DoubleArray>, val premium: Array<DoubleArray>, val costSide: DoubleArray) {
        val days: Int = close.firstOrNull()?.size ?: 0
        private val sum = Array(coins.size) { c -> DoubleArray(days + 1).also { s -> for (d in 0 until days) s[d + 1] = s[d] + close[c][d].let { if (it > 0.0) it else 0.0 } } }
        private val bad = Array(coins.size) { c -> IntArray(days + 1).also { s -> for (d in 0 until days) s[d + 1] = s[d] + (if (close[c][d] > 0.0) 0 else 1) } }
        /** 30-day volatility of daily returns to day d (NaN short of data). */
        val vol: Array<DoubleArray> = Array(coins.size) { c -> DoubleArray(days) { d -> volAt(c, d) } }

        fun ret(c: Int, d: Int): Double {
            if (d < 1 || d >= days) return Double.NaN
            val a = close[c][d - 1]; val b = close[c][d]
            return if (a > 0.0 && b > 0.0) b / a - 1.0 else Double.NaN
        }
        fun retOver(c: Int, d: Int, p: Int): Double {
            if (d - p < 0 || d >= days) return Double.NaN
            val a = close[c][d - p]; val b = close[c][d]
            return if (a > 0.0 && b > 0.0) b / a - 1.0 else Double.NaN
        }
        /** Mean close over days [from, to]; NaN if any is missing. */
        fun avg(c: Int, from: Int, to: Int): Double {
            if (from < 0 || to >= days || to < from) return Double.NaN
            if (bad[c][to + 1] - bad[c][from] > 0) return Double.NaN
            return (sum[c][to + 1] - sum[c][from]) / (to - from + 1)
        }
        private fun volAt(c: Int, d: Int): Double {
            if (d < VOL_DAYS) return Double.NaN
            var s = 0.0; var s2 = 0.0; var n = 0
            for (i in d - VOL_DAYS + 1..d) { val r = ret(c, i); if (r.isFinite()) { s += r; s2 += r * r; n++ } }
            if (n < VOL_DAYS * 2 / 3) return Double.NaN
            val m = s / n
            return sqrt(maxOf(0.0, s2 / n - m * m)).takeIf { it > 0.0 } ?: Double.NaN
        }
    }

    // ── signals ──

    /** Pure: +1 / -1 / 0 for a trend book of lookback [l] on coin [c] at day [d]. */
    fun trendSignal(p: Panel, c: Int, d: Int, l: Int, longShort: Boolean): Int {
        if (d + 1 < l || d >= p.days) return 0
        val long = p.avg(c, d - l + 1, d)
        val short = p.avg(c, d - maxOf(2, l / 4) + 1, d)
        if (!long.isFinite() || !short.isFinite()) return 0
        val x = p.close[c][d]
        return when {
            short > long && x > long -> 1
            longShort && short < long && x < long -> -1
            else -> 0
        }
    }

    private fun normalised(raw: DoubleArray): DoubleArray {
        val gross = raw.sumOf { abs(it) }
        if (!(gross > 0.0) || !gross.isFinite()) return DoubleArray(raw.size)
        return DoubleArray(raw.size) { raw[it] / gross }
    }

    /** Pure: demeaned cross-sectional scores (NaN = not ranked) as inverse-vol weights; needs three coins. */
    private fun crossSection(p: Panel, d: Int, score: (Int) -> Double): DoubleArray {
        val s = DoubleArray(p.coins.size) { c -> if (p.vol[c][d].isFinite()) score(c) else Double.NaN }
        val ok = s.indices.filter { s[it].isFinite() }
        if (ok.size < 3) return DoubleArray(p.coins.size)
        val m = ok.sumOf { s[it] } / ok.size
        return normalised(DoubleArray(p.coins.size) { c -> if (s[c].isFinite()) (s[c] - m) / p.vol[c][d] else 0.0 })
    }

    private fun meanFunding(p: Panel, c: Int, d: Int, n: Int): Double {
        if (d - n + 1 < 0) return Double.NaN
        var s = 0.0
        for (i in d - n + 1..d) { val f = p.funding[c][i]; if (!f.isFinite()) return Double.NaN; s += f }
        return s / n
    }

    /** Pure: target weights (gross 1, or all zero) of a rule-based book [s] at day [d]. */
    fun weights(p: Panel, s: Strategy, d: Int): DoubleArray = when (s.family) {
        Family.TREND -> normalised(DoubleArray(p.coins.size) { c ->
            val sig = trendSignal(p, c, d, s.p, s.longShort); val v = p.vol[c][d]
            if (sig != 0 && v.isFinite()) sig / v else 0.0
        })
        Family.MOMENTUM -> crossSection(p, d) { c -> p.retOver(c, d, s.p) / (p.vol[c][d] * sqrt(s.p.toDouble())) }
        Family.CARRY -> crossSection(p, d) { c -> -meanFunding(p, c, d, s.p) / p.vol[c][d] }
        Family.REVERSAL -> crossSection(p, d) { c -> -p.retOver(c, d, s.p) / (p.vol[c][d] * sqrt(s.p.toDouble())) }
        Family.LEARNED -> DoubleArray(p.coins.size)        // needs a fitted model: see path()
    }

    // ── the learned model ──

    /** Pure: coin [c]'s ten features at day [d], or null when its history is too short. */
    fun features(p: Panel, c: Int, d: Int, market: Double): DoubleArray? {
        val v = p.vol[c][d]
        if (!v.isFinite()) return null
        val r1 = p.ret(c, d); val r5 = p.retOver(c, d, 5); val r20 = p.retOver(c, d, 20); val r60 = p.retOver(c, d, 60)
        if (!r1.isFinite() || !r5.isFinite() || !r20.isFinite() || !r60.isFinite()) return null
        val f7 = meanFunding(p, c, d, 7)
        val prem = p.premium[c][d]
        var volMean = 0.0; var vn = 0
        if (d >= 29) for (i in d - 29..d) { val x = p.volUsd[c][i]; if (x > 0.0) { volMean += x; vn++ } }
        val surge = if (vn >= 20 && p.volUsd[c][d] > 0.0) ln(p.volUsd[c][d] / (volMean / vn)) else 0.0
        val oiNow = p.oiUsd[c][d]; val oiThen = if (d >= 7) p.oiUsd[c][d - 7] else Double.NaN
        val oiChg = if (oiNow > 0.0 && oiThen > 0.0) ln(oiNow / oiThen) else 0.0
        return doubleArrayOf(r1 / v, r5 / (v * sqrt(5.0)), r20 / (v * sqrt(20.0)), r60 / (v * sqrt(60.0)),
            trendSignal(p, c, d, 40, true).toDouble(), if (f7.isFinite()) f7 / v else 0.0,
            if (prem.isFinite()) prem / v else 0.0, surge, oiChg, if (market.isFinite()) market else 0.0)
    }

    private fun marketMove(p: Panel, d: Int): Double {
        var s = 0.0; var n = 0
        for (c in p.coins.indices) { val r = p.retOver(c, d, 20); val v = p.vol[c][d]; if (r.isFinite() && v.isFinite()) { s += r / (v * sqrt(20.0)); n++ } }
        return if (n > 0) s / n else Double.NaN
    }

    /** Features [d][c] and targets [d][c] (tomorrow's return over today's volatility, clipped to +-5). */
    class Design(val x: Array<Array<DoubleArray?>>, val y: Array<DoubleArray>)

    fun design(p: Panel): Design {
        val x = Array(p.days) { d -> val m = marketMove(p, d); Array(p.coins.size) { c -> features(p, c, d, m) } }
        val y = Array(p.days) { d -> DoubleArray(p.coins.size) { c ->
            val r = p.ret(c, d + 1); val v = p.vol[c][d]
            if (r.isFinite() && v.isFinite()) (r / v).coerceIn(-5.0, 5.0) else Double.NaN
        } }
        return Design(x, y)
    }

    /** A fitted ridge model on standardised features. */
    class Model(val mean: DoubleArray, val sd: DoubleArray, val beta: DoubleArray, val intercept: Double) {
        fun predict(f: DoubleArray): Double {
            var s = intercept
            for (k in beta.indices) s += beta[k] * (f[k] - mean[k]) / sd[k]
            return s
        }
    }

    /** Pure: ridge fit on every sample of days [0, until) (each sample's target is the next day, known by [until]). */
    fun fit(ds: Design, until: Int, lambda: Double): Model? {
        val rows = ArrayList<DoubleArray>(); val ys = ArrayList<Double>()
        for (t in 0 until minOf(until, ds.x.size)) for (c in ds.x[t].indices) {
            val f = ds.x[t][c] ?: continue; val y = ds.y[t][c]
            if (y.isFinite()) { rows.add(f); ys.add(y) }
        }
        if (rows.size < MIN_FIT_SAMPLES) return null
        val n = rows.size
        val mean = DoubleArray(FEATURES) { k -> rows.sumOf { it[k] } / n }
        val sd = DoubleArray(FEATURES) { k -> sqrt(rows.sumOf { (it[k] - mean[k]) * (it[k] - mean[k]) } / n).takeIf { it > 1e-12 } ?: 1.0 }
        val ym = ys.average()
        val a = Array(FEATURES) { DoubleArray(FEATURES) }
        val b = DoubleArray(FEATURES)
        for ((i, r) in rows.withIndex()) {
            val z = DoubleArray(FEATURES) { k -> (r[k] - mean[k]) / sd[k] }
            val yc = ys[i] - ym
            for (j in 0 until FEATURES) { b[j] += z[j] * yc; for (k in 0 until FEATURES) a[j][k] += z[j] * z[k] }
        }
        for (j in 0 until FEATURES) a[j][j] += lambda * n
        val beta = solve(a, b) ?: return null
        return Model(mean, sd, beta, ym)
    }

    /** Pure: Gaussian elimination with partial pivoting; null when singular. */
    fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (col in 0 until n) {
            val piv = (col until n).maxByOrNull { abs(a[it][col]) } ?: return null
            if (abs(a[piv][col]) < 1e-12) return null
            val tr = a[piv]; a[piv] = a[col]; a[col] = tr
            val tb = b[piv]; b[piv] = b[col]; b[col] = tb
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }

    // ── books through time ──

    /** Pure: [s]'s target weights on every day [0, days); the learned book refits on what was known before each day. */
    fun path(p: Panel, s: Strategy, ds: Design?): Array<DoubleArray> {
        if (s.family != Family.LEARNED) return Array(p.days) { d -> weights(p, s, d) }
        val design = ds ?: design(p)
        val lambda = RIDGE[s.p.coerceIn(0, RIDGE.size - 1)]
        var model: Model? = null
        return Array(p.days) { d ->
            if (d % REFIT_DAYS == 0) model = fit(design, d, lambda) ?: model
            val m = model ?: return@Array DoubleArray(p.coins.size)
            normalised(DoubleArray(p.coins.size) { c ->
                val f = design.x[d][c]; val v = p.vol[c][d]
                if (f != null && v.isFinite()) m.predict(f) / v else 0.0
            })
        }
    }

    /** Pure: daily book returns on days [from, to): weights set at day d's close earn day d+1, net of cost and funding. */
    fun returns(p: Panel, w: Array<DoubleArray>, from: Int, to: Int): Pair<DoubleArray, DoubleArray> {
        val end = minOf(to, p.days - 1)
        if (end <= from) return DoubleArray(0) to DoubleArray(0)
        val out = DoubleArray(end - from); val turn = DoubleArray(end - from)
        var prev = DoubleArray(p.coins.size)
        for (d in from until end) {
            val cur = w[d]; var r = 0.0; var t = 0.0
            for (c in p.coins.indices) {
                val dw = abs(cur[c] - prev[c])
                t += dw; r -= p.costSide[c] * dw
                if (cur[c] == 0.0) continue
                val x = p.ret(c, d + 1)
                if (x.isFinite()) r += cur[c] * x
                val f = p.funding[c].getOrNull(d + 1) ?: Double.NaN
                if (f.isFinite()) r -= cur[c] * f
            }
            out[d - from] = r; turn[d - from] = t
            prev = cur
        }
        return out to turn
    }

    /** Pure: the record of daily returns [r] (fractions); null under 20 days. */
    fun grade(id: String, r: DoubleArray, exposure: Double = Double.NaN, turnover: Double = Double.NaN): Result? {
        if (r.size < 20) return null
        val m = r.average()
        val sd = sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1))
        var eq = 1.0; var peak = 1.0; var dd = 0.0
        for (x in r) { eq *= 1.0 + x; if (eq > peak) peak = eq; dd = maxOf(dd, 1.0 - eq / peak) }
        return Result(id, r.size, m * 100.0, sd / sqrt(r.size.toDouble()) * 100.0, if (sd > 0.0) m / sd * sqrt(365.0) else 0.0,
            (eq - 1.0) * 100.0, dd * 100.0, exposure, turnover)
    }

    private fun graded(id: String, p: Panel, w: Array<DoubleArray>, from: Int, to: Int): Result? {
        val (r, t) = returns(p, w, from, to)
        if (r.isEmpty()) return null
        val held = (from until minOf(to, p.days - 1)).count { d -> w[d].any { it != 0.0 } }
        return grade(id, r, held.toDouble() / r.size, t.average())
    }

    /** Pure: half the Kelly fraction of daily returns [r]; zero when they lost. */
    fun halfKelly(r: DoubleArray): Double {
        if (r.size < 20) return 0.0
        val m = r.average()
        val v = r.sumOf { (it - m) * (it - m) } / (r.size - 1)
        return if (m > 0.0 && v > 0.0) 0.5 * m / v else 0.0
    }

    /** Pure (V5.0.8802): a book that cannot short (spot) — each day's negative weights dropped, the rest scaled to gross 1. */
    fun longOnly8802(w: Array<DoubleArray>): Array<DoubleArray> =
        Array(w.size) { d -> normalised(DoubleArray(w[d].size) { c -> maxOf(0.0, w[d][c]) }) }

    /**
     * Pure: choose on the first half, grade on the second, blend, arm, size; targets are the blend's weights today.
     * [longOnly] (V5.0.8802, the xStocks spot desk): every book is graded as it can actually be held — long or flat.
     */
    fun evaluate(p: Panel, maxLeverage: Double, longOnly: Boolean = false): Evaluation {
        val n = p.days
        if (p.coins.isEmpty() || n < 2 * MIN_TEST_DAYS) return Evaluation(n, emptyList(), emptyMap(), null, null, false, 0.0, 0.0, DoubleArray(p.coins.size))
        val half = n / 2
        val ds = design(p)
        val paths = HashMap<Strategy, Array<DoubleArray>>()
        val picks = GRID.mapNotNull { (family, grid) ->
            val scored = grid.mapNotNull { s ->
                val w = path(p, s, ds).let { if (longOnly) longOnly8802(it) else it }
                paths[s] = w; graded(s.id, p, w, 0, half)?.let { s to it }
            }
            val best = scored.maxByOrNull { it.second.sharpe } ?: return@mapNotNull null
            Pick(family, best.first, best.second, graded(best.first.id, p, paths.getValue(best.first), half, n))
        }
        val earners = picks.filter { it.train.sharpe > 0.0 }
        val total = earners.sumOf { it.train.sharpe }
        val blend = if (total > 0.0) earners.associate { it.chosen to it.train.sharpe / total } else emptyMap()
        val blended = Array(n) { d -> DoubleArray(p.coins.size) { c -> blend.entries.sumOf { (s, a) -> a * paths.getValue(s)[d][c] } } }
        val train = if (blend.isEmpty()) null else graded("blend", p, blended, 0, half)
        val test = if (blend.isEmpty()) null else graded("blend", p, blended, half, n)
        val kelly = if (blend.isEmpty()) 0.0 else minOf(halfKelly(returns(p, blended, 0, half).first), halfKelly(returns(p, blended, half, n).first))
        val leverage = kelly.coerceIn(0.0, maxOf(0.0, maxLeverage))
        val armed = test != null && test.days >= MIN_TEST_DAYS && test.meanPct - test.sePct > 0.0 && leverage > 0.0
        return Evaluation(n, picks, blend, train, test, armed, kelly, leverage, blended[n - 1].copyOf())
    }
}
