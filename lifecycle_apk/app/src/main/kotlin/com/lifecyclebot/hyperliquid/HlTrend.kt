package com.lifecyclebot.hyperliquid

/**
 * V5.0.8795 — the trend book Hyperliquid trades: the edge the research supports (Part 2 of the owner's deep dive).
 *  - Fieberg et al. (JFQA 2025): coins in an up-trend keep rising over the following weeks, among the largest coins too.
 *  - A decade of BTC moving-average crossovers: a short average over a long one (10 / 40 days) was consistently profitable.
 *
 * Rule, per coin, on completed daily closes: LONG when the short average (lookback / 4) is above the long one and the
 * close is above the long one; SHORT (LONG_SHORT mode only) in the mirror case; otherwise flat. Positions are weighted by
 * inverse 30-day volatility and scaled to a gross exposure of 1 (never leverage). Every day the book pays [COST_SIDE] on
 * each unit of weight it changes and the realised hourly funding of what it holds (longs pay positive funding).
 *
 * Graded the bot's honest way: lookback and mode are chosen on the FIRST half of the history (by Sharpe) and that one
 * rule is graded on the SECOND half it never saw. ARMED (may trade live) only when the second half covers at least
 * [MIN_TEST_DAYS] days and its mean daily return less one SE is above zero. The universe is today's most-traded perps,
 * so the history carries survivorship bias: coins that died are not in it (stated in the report).
 */
object HlTrend {
    enum class Mode { LONG_ONLY, LONG_SHORT }
    data class Rule(val lookback: Int, val mode: Mode) {
        val id: String get() = "trend L$lookback $mode"
    }
    /** One coin's aligned daily history: close (NaN before listing) and the day's summed hourly funding rate. */
    class Series(val coin: String, val close: DoubleArray, val funding: DoubleArray)
    data class Result(val rule: Rule, val days: Int, val meanPct: Double, val sePct: Double, val sharpe: Double,
                      val totalPct: Double, val maxDrawdownPct: Double, val exposure: Double)
    data class Evaluation(val chosen: Rule?, val train: Result?, val test: Result?, val armed: Boolean, val all: List<Result>)

    const val COST_SIDE = 0.001          // 0.045 % taker fee + 0.055 % slippage allowance, per unit of weight traded
    const val MIN_TEST_DAYS = 120
    private const val VOL_DAYS = 30
    val RULES: List<Rule> = listOf(20, 40, 60, 100).flatMap { l -> Mode.values().map { Rule(l, it) } }

    private fun avg(x: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from..to) { val v = x[i]; if (!(v > 0.0)) return Double.NaN; s += v }
        return s / (to - from + 1)
    }

    /** Pure: +1 / -1 / 0 for [rule] on [close] at day [d] (closes up to and including d). */
    fun signal(close: DoubleArray, d: Int, rule: Rule): Int {
        val l = rule.lookback
        if (d + 1 < l || d >= close.size) return 0
        val smaL = avg(close, d - l + 1, d)
        val smaS = avg(close, d - maxOf(2, l / 4) + 1, d)
        if (!smaL.isFinite() || !smaS.isFinite()) return 0
        val c = close[d]
        return when {
            smaS > smaL && c > smaL -> 1
            rule.mode == Mode.LONG_SHORT && smaS < smaL && c < smaL -> -1
            else -> 0
        }
    }

    /** Pure: the standard deviation of daily returns over the [VOL_DAYS] days to [d]; NaN when short of data. */
    fun vol(close: DoubleArray, d: Int): Double {
        if (d < VOL_DAYS) return Double.NaN
        var s = 0.0; var s2 = 0.0
        for (i in d - VOL_DAYS + 1..d) {
            val a = close[i - 1]; val b = close[i]
            if (!(a > 0.0) || !(b > 0.0)) return Double.NaN
            val r = b / a - 1.0
            s += r; s2 += r * r
        }
        val m = s / VOL_DAYS
        return kotlin.math.sqrt(maxOf(0.0, s2 / VOL_DAYS - m * m))
    }

    /** Pure: target weights (gross 1, or all zero) for every coin at day [d]. */
    fun weights(series: List<Series>, d: Int, rule: Rule): DoubleArray {
        val raw = DoubleArray(series.size)
        for ((i, s) in series.withIndex()) {
            val sig = signal(s.close, d, rule)
            if (sig == 0) continue
            val v = vol(s.close, d)
            if (!(v > 0.0)) continue
            raw[i] = sig / v
        }
        val gross = raw.sumOf { kotlin.math.abs(it) }
        if (!(gross > 0.0)) return DoubleArray(series.size)
        return DoubleArray(series.size) { raw[it] / gross }
    }

    /** Pure: daily portfolio returns of [rule] on days [from, to): weights set at day d's close, earning day d+1. */
    fun run(series: List<Series>, rule: Rule, from: Int, to: Int): DoubleArray {
        val n = series.firstOrNull()?.close?.size ?: return DoubleArray(0)
        val end = minOf(to, n - 1)
        if (end <= from) return DoubleArray(0)
        val out = DoubleArray(end - from)
        var prev = DoubleArray(series.size)
        for (d in from until end) {
            val w = weights(series, d, rule)
            var r = 0.0
            for ((i, s) in series.withIndex()) {
                r -= COST_SIDE * kotlin.math.abs(w[i] - prev[i])
                if (w[i] == 0.0) continue
                val a = s.close[d]; val b = s.close[d + 1]
                if (a > 0.0 && b > 0.0) r += w[i] * (b / a - 1.0)
                val f = s.funding.getOrNull(d + 1) ?: 0.0
                if (f.isFinite()) r -= w[i] * f
            }
            out[d - from] = r
            prev = w
        }
        return out
    }

    /** Pure: the record of daily returns [r] (in fractions) for [rule]; null under 20 days. */
    fun grade(rule: Rule, r: DoubleArray, exposure: Double = Double.NaN): Result? {
        if (r.size < 20) return null
        val m = r.average()
        val sd = kotlin.math.sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1))
        var eq = 1.0; var peak = 1.0; var dd = 0.0
        for (x in r) { eq *= 1.0 + x; if (eq > peak) peak = eq; dd = maxOf(dd, 1.0 - eq / peak) }
        val sharpe = if (sd > 0.0) m / sd * kotlin.math.sqrt(365.0) else 0.0
        return Result(rule, r.size, m * 100.0, sd / kotlin.math.sqrt(r.size.toDouble()) * 100.0, sharpe, (eq - 1.0) * 100.0, dd * 100.0, exposure)
    }

    private fun exposure(series: List<Series>, rule: Rule, from: Int, to: Int): Double {
        var held = 0; var days = 0
        for (d in from until minOf(to, (series.firstOrNull()?.close?.size ?: 0) - 1)) {
            days++
            if (weights(series, d, rule).any { it != 0.0 }) held++
        }
        return if (days > 0) held.toDouble() / days else 0.0
    }

    /** Pure: choose on the first half, grade on the second; armed only on the second half's own record. */
    fun evaluate(series: List<Series>): Evaluation {
        val n = series.firstOrNull()?.close?.size ?: 0
        if (series.isEmpty() || n < 2 * MIN_TEST_DAYS) return Evaluation(null, null, null, false, emptyList())
        val half = n / 2
        val train = RULES.mapNotNull { rule -> grade(rule, run(series, rule, 0, half), exposure(series, rule, 0, half)) }
        val best = train.maxByOrNull { it.sharpe } ?: return Evaluation(null, null, null, false, train)
        val test = grade(best.rule, run(series, best.rule, half, n), exposure(series, best.rule, half, n))
        val armed = test != null && test.days >= MIN_TEST_DAYS && test.meanPct - test.sePct > 0.0
        return Evaluation(best.rule, best, test, armed, train)
    }
}
