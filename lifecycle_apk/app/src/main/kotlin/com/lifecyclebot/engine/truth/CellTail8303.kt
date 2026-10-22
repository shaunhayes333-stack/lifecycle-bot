package com.lifecyclebot.engine.truth

/**
 * V5.0.8303 §WHAT_THE_MEAN_RESTS_ON — a REPORT read of a forward-label cell, never a gate.
 *
 * The books keep running aggregates only (n, sum, sumSq, wins, runners), so a median or a trimmed mean cannot be computed.
 * What the aggregates DO bound:
 *  - wins < n / 2  =>  more than half the labels are <= 0 net: the median label is not positive;
 *  - a non-runner winner has gross < the runner floor (+50 %), so its net is below it: the labels that are not runners
 *    average at most (wins - runners) x floor / (n - runners) ([exRunnerMaxPct]);
 *  - losers add <= 0, so the runners alone must carry at least (mean x n - (wins - runners) x floor) / runners on average
 *    ([runnerMinMeanPct]).
 * A cell is TAIL_DEPENDENT when its median is not positive and everything but its runners averages under a tenth of the
 * mean. 8185's OUTLIER_SUSPECT only fires when the mean is IMPOSSIBLE under the per-label cap; a real runner tail (the
 * pond: 33 % runners, wr 43 %) is not an outlier, and this line says so plainly instead.
 */
object CellTail8303 {
    private const val RUNNER_FLOOR_PCT_8303 = 50.0   // ForwardReturnLabeler7731.RUNNER_PCT_7731

    data class Tail8303(
        val medianNonPositive: Boolean,
        val exRunnerMaxPct: Double,
        val runnerMinMeanPct: Double,
        val tailDependent: Boolean,
    )

    /** Pure: the tail picture of a cell with [meanPct], [winRate] and [runnerRate] (fractions 0..1). */
    fun tail8303(meanPct: Double, winRate: Double, runnerRate: Double, floorPct: Double = RUNNER_FLOOR_PCT_8303): Tail8303 {
        val wr = if (winRate.isFinite()) winRate.coerceIn(0.0, 1.0) else 0.0
        val rr = if (runnerRate.isFinite()) runnerRate.coerceIn(0.0, wr) else 0.0
        val small = (wr - rr).coerceAtLeast(0.0)
        val exRunner = if (rr < 1.0) small * floorPct / (1.0 - rr) else Double.NaN
        val runnerMin = if (rr > 0.0 && meanPct.isFinite()) (meanPct - small * floorPct) / rr else Double.NaN
        val median = wr < 0.5
        val dependent = median && meanPct.isFinite() && meanPct > 0.0 && exRunner.isFinite() && exRunner < meanPct / 10.0
        return Tail8303(median, exRunner, runnerMin, dependent)
    }

    /** "key[medianLE0 exRunner<=+x% runnersAvg>=+y% TAIL_DEPENDENT]" for the report. */
    fun line8303(key: String, meanPct: Double, winRate: Double, runnerRate: Double): String {
        val t = tail8303(meanPct, winRate, runnerRate)
        val f = { v: Double -> if (v.isFinite()) "%+.0f".format(v) else "-" }
        return "$key[${if (t.medianNonPositive) "medianLE0" else "medianPos"} exRunner<=${f(t.exRunnerMaxPct)}% " +
            "runnersAvg>=${f(t.runnerMinMeanPct)}%${if (t.tailDependent) " TAIL_DEPENDENT" else ""}]"
    }
}
