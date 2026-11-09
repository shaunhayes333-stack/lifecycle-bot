package com.lifecyclebot.engine.truth

/**
 * V5.0.8546 §MEME_MARKET_REGIME — the master switch. 8542: every lane and every discovery source lost over 5 minutes
 * (-7.6 % .. -19.4 %), refused coins included; in a cold meme market the right number of new live entries is near zero.
 *
 * Breadth is read from the forward labels the bot already books (every candidate it saw, admitted or refused, priced 5
 * minutes later, executable net of cost): over the last [WINDOW_MS_8546], the mean net and the share that rose at all.
 *   COLD    — enough labels, mean < [COLD_MEAN_PCT_8546] and fewer than [COLD_RISING_8546] rising;
 *   HOT     — mean > [HOT_MEAN_PCT_8546] and more than [HOT_RISING_8546] rising;
 *   NEUTRAL — otherwise, or too few labels to say (never COLD on thin evidence).
 * LIVE meme entries are refused in COLD unless a strategy family proven in the paper book carries the coin
 * (CandidateAdmission8136, a demotable door). Paper and the paper books keep trading every regime: that is how a family
 * proves itself in a cold market.
 */
object MemeRegime8546 {
    enum class Regime8546 { COLD, NEUTRAL, HOT }

    private const val WINDOW_MS_8546 = 45L * 60_000L
    const val MIN_N_8546 = 40
    private const val COLD_MEAN_PCT_8546 = -6.0
    private const val COLD_RISING_8546 = 0.25
    private const val HOT_MEAN_PCT_8546 = 2.0
    private const val HOT_RISING_8546 = 0.45
    private const val MAX_ROWS_8546 = 4_000

    private val rows = ArrayDeque<Triple<Long, Double, Double>>()   // (atMs, net, gross); guarded by this

    /**
     * V5.0.8655: regime breadth is a *fillable five-minute* price experiment, not a mix of
     * 5m and 15m target slots or an unfillable runner whose paper mark jumped 20x.
     * The original book(o, 60, ...) uses 60 as its TALLY SLOT, not a 60-minute or
     * necessarily five-minute duration. Missing real fill evidence never means a win.
     */
    fun eligibleBreadth8655(targetHorizonMs: Long, fillable: Boolean): Boolean =
        fillable && targetHorizonMs == 5L * 60_000L

    /** ForwardReturnLabeler7731.book: a 5-minute label of a Solana meme lane was booked. */
    fun note8546(atMs: Long, netPct: Double, grossPct: Double) {
        if (!netPct.isFinite() || !grossPct.isFinite() || atMs <= 0L) return
        synchronized(this) {
            rows.addLast(Triple(atMs, netPct, grossPct))
            while (rows.size > MAX_ROWS_8546) rows.removeFirst()
            while (rows.isNotEmpty() && atMs - rows.first().first > WINDOW_MS_8546) rows.removeFirst()
        }
    }

    /** Pure: the regime of [nets] / [grosses] (same length) read together. */
    fun regimeOf8546(nets: List<Double>, grosses: List<Double>): Regime8546 {
        val n = minOf(nets.size, grosses.size)
        if (n < MIN_N_8546) return Regime8546.NEUTRAL
        val mean = nets.take(n).average()
        val rising = grosses.take(n).count { it > 0.0 }.toDouble() / n
        return when {
            mean < COLD_MEAN_PCT_8546 && rising < COLD_RISING_8546 -> Regime8546.COLD
            mean > HOT_MEAN_PCT_8546 && rising > HOT_RISING_8546 -> Regime8546.HOT
            else -> Regime8546.NEUTRAL
        }
    }

    private fun window(nowMs: Long): List<Triple<Long, Double, Double>> =
        synchronized(this) { rows.filter { nowMs - it.first in 0L..WINDOW_MS_8546 } }

    fun regime8546(nowMs: Long = System.currentTimeMillis()): Regime8546 {
        val w = window(nowMs)
        return regimeOf8546(w.map { it.second }, w.map { it.third })
    }

    fun statusLine8546(nowMs: Long = System.currentTimeMillis()): String {
        val w = window(nowMs)
        val mean = if (w.isEmpty()) Double.NaN else w.map { it.second }.average()
        val rising = if (w.isEmpty()) Double.NaN else w.count { it.third > 0.0 }.toDouble() / w.size
        return "memeRegime8546[${regimeOf8546(w.map { it.second }, w.map { it.third })} n${w.size} " +
            "mean=${if (mean.isFinite()) "%+.1f".format(mean) else "-"}% rising=${if (rising.isFinite()) "%.0f".format(rising * 100) else "-"}% " +
            "window=${WINDOW_MS_8546 / 60_000L}m]"
    }
}
