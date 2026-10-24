package com.lifecyclebot.engine.truth

/**
 * V5.0.8366 §HONEST_LABELS — a forward label replays the exit the lane would really have taken.
 *
 * ForwardReturnLabeler7731.captured7945 graded a stopped path at EXACTLY -stop (LaneParticipation8029.playGross8029) and
 * credited the ladder on a peak held for a fixed 20 s window, so labels read +68 % while live closed -5 %: a live stop
 * fires on the first mark at or below the stop — usually a gap below it — and fills after the sell latency while the
 * price keeps moving.
 *   STOP   graded at the FIRST observed mark at or below the stop (the path's own new lows, in order), then the exit
 *          slippage over the measured sell latency (PaperExitLatency8223.adverseDriftPct8223: the velocity into that mark
 *          projected over the latency; adverse only, bounded) — never at exactly -stop. Not below -100 %.
 *   PEAK   the ladder credits only a level the price HELD for at least the measured sell latency
 *          ([sellLatencyMs8366], default [DEFAULT_SELL_LATENCY_MS_8366]): every mark over that window at or above it.
 * The stored label book format is unchanged; new labels carry the new value. An observation restored from before this
 * build has no recorded lows / latency-held peak and keeps the previous grading (never a fabricated path).
 */
object HonestExit8366 {
    /** The sell latency a held peak must cover before the live sell latency is measured. */
    const val DEFAULT_SELL_LATENCY_MS_8366 = 10_000L
    /** New lows kept per observation (oldest first); past it the deepest stop falls back to the lowest mark seen. */
    const val MAX_LOWS_8366 = 64

    /** The measured live sell latency (normal exits) once measured, else [DEFAULT_SELL_LATENCY_MS_8366]. */
    fun sellLatencyMs8366(): Long = try {
        if (PaperExitLatency8223.measured8223(false)) PaperExitLatency8223.latencyMs8223(false) else DEFAULT_SELL_LATENCY_MS_8366
    } catch (_: Throwable) { DEFAULT_SELL_LATENCY_MS_8366 }

    /**
     * Pure: record one new low. [lows] rows are [atMs, gross, prevAtMs, prevGross] — the new-low mark and the mark seen just
     * before it (the velocity into the low). Returns false once [MAX_LOWS_8366] rows are held (the row is not kept).
     */
    fun noteLow8366(lows: MutableList<DoubleArray>, atMs: Long, gross: Double, prevAtMs: Long, prevGross: Double): Boolean {
        if (!gross.isFinite()) return true
        if (lows.size >= MAX_LOWS_8366) return false
        lows.add(doubleArrayOf(atMs.toDouble(), gross, prevAtMs.toDouble(), if (prevGross.isFinite()) prevGross else 0.0))
        return true
    }

    /**
     * Pure: the gross a stop at [stopMag] (positive %) fills at on this path — the first recorded low at or below -stop,
     * minus the adverse drift over [sellLatencyMs]. NaN when no recorded low reached the stop (the caller keeps its grading).
     */
    fun stopFill8366(lows: List<DoubleArray>, stopMag: Double, sellLatencyMs: Long): Double {
        if (!(stopMag > 0.0)) return Double.NaN
        val hit = lows.firstOrNull { it.size >= 4 && it[1].isFinite() && it[1] <= -stopMag } ?: return Double.NaN
        val mark = hit[1]
        val horizon = (hit[0] - hit[2]).toLong()
        val drift = try {
            PaperExitLatency8223.adverseDriftPct8223(1.0 + mark / 100.0, 1.0 + hit[3] / 100.0, horizon, sellLatencyMs)
        } catch (_: Throwable) { 0.0 }
        // The drift is a % move of the price at the mark: applied to the position's value there.
        val fill = ((1.0 + mark / 100.0) * (1.0 + drift.coerceAtMost(0.0) / 100.0) - 1.0) * 100.0
        return if (fill.isFinite()) fill.coerceAtLeast(-100.0) else Double.NaN
    }

    /**
     * Pure: LaneParticipation8029.playGross8029 with the stop graded at [stopFillPct] (from [stopFill8366]) instead of
     * exactly -stop. A non-finite [stopFillPct] keeps -stop (no recorded path). The ladder and the order (stop before the
     * peak = stopped trade) are unchanged.
     */
    fun playGross8366(stopMag: Double, peakPct: Double, dipBeforePeakPct: Double, minPct: Double, grossNow: Double,
                      stopFillPct: Double, ladder: (Double, Double) -> Double): Double {
        if (!(stopMag > 0.0) || !grossNow.isFinite()) return ladder(peakPct, grossNow)
        val stopped = if (stopFillPct.isFinite()) minOf(stopFillPct, -stopMag) else -stopMag
        val dipFirst = if (dipBeforePeakPct.isFinite()) dipBeforePeakPct else minPct
        if (dipFirst.isFinite() && dipFirst <= -stopMag) return stopped
        val rest = if (minPct.isFinite() && minPct <= -stopMag) stopped else grossNow
        return ladder(peakPct, rest)
    }

    /**
     * Pure: one mark into the latency-held peak tracker (ForwardReturnLabeler7731.heldPeakStep8071 with the hold set to
     * [holdMs] instead of a fixed 20 s). [window] keeps one mark at or before (now - hold) and everything after it; once it
     * spans the hold, the lowest gross in it is a level held for the hold, and the held peak is the best such level.
     */
    fun heldPeakStep8366(window: ArrayDeque<DoubleArray>, nowMs: Long, gross: Double, heldPeak: Double, holdMs: Long): Double {
        if (!gross.isFinite()) return heldPeak
        val hold = holdMs.coerceAtLeast(1_000L)
        window.addLast(doubleArrayOf(nowMs.toDouble(), gross))
        while (window.size >= 2 && nowMs - window.elementAt(1)[0].toLong() >= hold) window.removeFirst()
        if (nowMs - window.first()[0].toLong() < hold) return heldPeak
        return maxOf(heldPeak, window.minOf { it[1] })
    }
}
