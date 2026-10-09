package com.lifecyclebot.engine.chart

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * V5.0.7950 — a chart, read the way the owner reads it.
 *
 * The owner's edge: "nearly all sol coins and pretty much all crypto end up with
 * the same buy and sell patterns, the same chart structures and shapes, the same
 * candles, they just vary in colour and shape but it's the same exact things
 * hidden in every chart" — trading bots, volume bots and bundles push every
 * chart into the same rhythms. So a shape is described SCALE-FREE: every candle
 * relative to the window's own volatility (ATR) and volume (median), so a $15k
 * pump.fun launch, a $300k meme, a BSC token and BTC that draw the same shape
 * produce the same fingerprint. What happened AFTER that fingerprint, across
 * thousands of charts, is the read.
 *
 * Pure functions only (unit-tested).
 */
data class Bar7950(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
    /** Buy-side volume (taker buy / buy SOL), NaN when the source has no split. */
    val buyV: Double = Double.NaN,
)

/** What a window was followed by, over [ChartMotif7950.HORIZON] bars. */
data class MotifOutcome7950(
    /** Reached +2 ATR before -1 ATR. */
    val hitUpFirst: Boolean,
    val maxUpPct: Float,
    val maxDnPct: Float,
    val endPct: Float,
)

/** The neighbours' verdict on a live window. */
data class MotifRead7950(
    val n: Int,
    /** Share of the nearest motifs that hit the win bar before the loss bar. */
    val pUp: Double,
    /** pUp minus the library's base rate. */
    val lift: Double,
    val meanUpPct: Double,
    val meanDnPct: Double,
    val meanEndPct: Double,
    val meanDist: Double,
)

object ChartMotif7950 {
    /** Bars in a fingerprint. */
    const val WINDOW = 20
    /** Bars an outcome looks ahead. */
    const val HORIZON = 20
    private const val WIN_ATR = 2.0
    private const val LOSS_ATR = 1.0
    private const val ATR_N = 14
    private const val PER_BAR = 6
    /** Fingerprint length: 6 per bar + 3 window-level. */
    const val DIM = WINDOW * PER_BAR + 3

    /** Average true range over the [ATR_N] bars ending at [end]. */
    private fun atr(bars: List<Bar7950>, end: Int): Double {
        val from = max(1, end - ATR_N + 1)
        if (end < 1 || from > end) return Double.NaN
        var sum = 0.0
        var n = 0
        for (i in from..end) {
            val b = bars[i]
            val pc = bars[i - 1].c
            sum += max(b.h - b.l, max(abs(b.h - pc), abs(b.l - pc)))
            n++
        }
        return if (n > 0) sum / n else Double.NaN
    }

    private fun clip(x: Double, lim: Double): Float = (if (x.isFinite()) x.coerceIn(-lim, lim) else 0.0).toFloat()

    /**
     * Pure: the scale-free fingerprint of the [WINDOW] bars ending at [end], or
     * null when the window is short or degenerate (no volatility, bad prices).
     * Per bar: close path relative to the last close (ATR units), the bar's
     * return, body, upper wick, lower wick, log volume vs the window median.
     * Window: where the close sits in the window range, the share of higher
     * lows, the net move (ATR units).
     */
    fun encode(bars: List<Bar7950>, end: Int): FloatArray? {
        val start = end - WINDOW + 1
        if (start < 1 || end >= bars.size) return null
        val a = atr(bars, end)
        val last = bars[end].c
        if (!a.isFinite() || a <= 0.0 || !last.isFinite() || last <= 0.0) return null
        if (a / last < 1e-5) return null
        val vols = DoubleArray(WINDOW) { bars[start + it].v.coerceAtLeast(0.0) }
        val medV = vols.sorted()[WINDOW / 2]
        val out = FloatArray(DIM)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var higherLows = 0
        for (j in 0 until WINDOW) {
            val b = bars[start + j]
            val prev = bars[start + j - 1]
            if (!(b.c > 0.0) || !(b.h >= b.l)) return null
            val range = (b.h - b.l).coerceAtLeast(a * 1e-3)
            val k = j * PER_BAR
            out[k] = clip((b.c - last) / a, 15.0) / 3f
            out[k + 1] = clip((b.c - prev.c) / a, 6.0)
            out[k + 2] = clip((b.c - b.o) / range, 1.0)
            out[k + 3] = clip((b.h - max(b.o, b.c)) / range, 1.0)
            out[k + 4] = clip((min(b.o, b.c) - b.l) / range, 1.0)
            out[k + 5] = clip(ln((b.v.coerceAtLeast(0.0) + 1e-12) / (medV + 1e-12)), 4.0) / 2f
            hi = max(hi, b.h)
            lo = min(lo, b.l)
            if (j > 0 && b.l > prev.l) higherLows++
        }
        val k = WINDOW * PER_BAR
        out[k] = clip(if (hi > lo) (last - lo) / (hi - lo) * 2.0 - 1.0 else 0.0, 1.0)
        out[k + 1] = (higherLows.toFloat() / (WINDOW - 1)) * 2f - 1f
        out[k + 2] = clip((last - bars[start].o) / a, 20.0) / 5f
        return out
    }

    /** Pure: what followed the window ending at [end], or null when the future is not there yet. */
    fun outcome(bars: List<Bar7950>, end: Int, horizon: Int = HORIZON): MotifOutcome7950? {
        if (end + horizon >= bars.size) return null
        val a = atr(bars, end)
        val c = bars[end].c
        if (!a.isFinite() || a <= 0.0 || !(c > 0.0)) return null
        val up = c + WIN_ATR * a
        val dn = c - LOSS_ATR * a
        var hit: Boolean? = null
        var maxH = c
        var minL = c
        for (i in end + 1..end + horizon) {
            val b = bars[i]
            if (hit == null) {
                val touchDn = b.l <= dn
                val touchUp = b.h >= up
                if (touchDn) hit = false else if (touchUp) hit = true
            }
            maxH = max(maxH, b.h)
            minL = min(minL, b.l)
        }
        return MotifOutcome7950(
            hitUpFirst = hit == true,
            maxUpPct = ((maxH / c - 1.0) * 100.0).toFloat(),
            maxDnPct = ((minL / c - 1.0) * 100.0).toFloat(),
            endPct = ((bars[end + horizon].c / c - 1.0) * 100.0).toFloat(),
        )
    }

    /** Pure: buy share of the last [n] bars' volume, or NaN when no bar has a split. */
    fun buyShare(bars: List<Bar7950>, end: Int, n: Int = 5): Double {
        var buy = 0.0
        var tot = 0.0
        for (i in max(0, end - n + 1)..end) {
            val b = bars[i]
            if (b.buyV.isFinite() && b.v > 0.0) { buy += b.buyV; tot += b.v }
        }
        return if (tot > 0.0) buy / tot else Double.NaN
    }

    /** Pure: squared distance between two fingerprints. */
    fun dist2(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return s
    }
}
