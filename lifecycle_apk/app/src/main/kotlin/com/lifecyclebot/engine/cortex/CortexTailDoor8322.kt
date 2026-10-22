package com.lifecyclebot.engine.cortex

/**
 * V5.0.8322 §TAIL_AWARE_STRONG_DOOR — the statistic a runner lane's STRONG cohort is admitted on.
 *
 * 8238 opened the Cortex STRONG door only on a positive executable MEDIAN. The paying pond is TAIL_DEPENDENT (8303:
 * median <= 0, non-runners <= +8%, runners averaging +744..+1342%): on such a cohort the median is <= 0 by construction, so
 * the door could never open on the lanes whose edge is the tail, however large the expectancy. The tail-aware read here
 * keeps 8238's purpose (no door carried by one coin) without demanding that the typical coin wins:
 *   - expectancy: the mean net executable value is positive;
 *   - not one coin: the mean with the single largest value removed is still positive (a lone outlier X moves the mean by
 *     X/n, and removing it takes that away);
 *   - a real tail: at least [MIN_RUNNERS_8322] values at or above the runner bar (LearningObjective8153.RUNNER_PCT_8153).
 * Non-runner lanes keep the median door. Pure: no Android, no I/O.
 */
object CortexTailDoor8322 {
    const val MIN_RUNNERS_8322 = 3

    data class TailRead8322(val n: Int, val mean: Double, val leaveTopOutMean: Double, val runners: Int, val open: Boolean, val why: String)

    /** Pure: the tail-aware read of a cohort's net executable values (non-finite values ignored). */
    fun read8322(values: List<Double>, runnerPct: Double = LearningObjective8153.RUNNER_PCT_8153): TailRead8322 {
        val xs = values.filter { it.isFinite() }
        val n = xs.size
        if (n == 0) return TailRead8322(0, Double.NaN, Double.NaN, 0, false, "EMPTY")
        val sum = xs.sum()
        val mean = sum / n
        val top = xs.maxOrNull() ?: 0.0
        val loo = if (n > 1) (sum - top) / (n - 1) else Double.NaN
        val runners = xs.count { it >= runnerPct }
        val why = when {
            !(mean > 0.0) -> "MEAN_NOT_POSITIVE"
            !(loo > 0.0) -> "ONE_COIN"
            runners < MIN_RUNNERS_8322 -> "FEW_RUNNERS"
            else -> "OPEN"
        }
        return TailRead8322(n, mean, loo, runners, why == "OPEN", why)
    }
}
