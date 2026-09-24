package com.lifecyclebot.engine

/**
 * V5.0.7277 §RUNNER EXITS WERE UPSIDE DOWN.
 *
 * Operator's 5.0.7274 tape: winners locked at "peak14 now11" and "peak19
 * now16" while losers rode to −34% and −100%; MOONSHOT's exit tuner sat at
 * tpMult=0.90 / slMult=0.84, tightening take-profit on the one lane whose
 * thesis is the 10x tail. A 6% win-rate lane needs a 15x average payoff to
 * break even and it was getting 1.6x.
 *
 * For the lanes that exist to catch tails, the shape is inverted here at the
 * three authorities that decide it:
 *
 *   • the give-back locks (PeakDrawdownLock and the tick profit lock) do not
 *     arm until the position has peaked at +50% — under that, a runner lane
 *     is either going to run or going to be cut, and a +14% lock is neither;
 *   • the lane exit tuner may not pull a runner lane's take-profit below
 *     neutral, whatever its recent win rate says;
 *   • a runner-lane position that is −20% inside its first two minutes is
 *     cut on the first strike — that is a launch that did not launch, and
 *     the two-strike grace exists for slow markets, not for these.
 *
 * Protective stops, catastrophe exits and the MFE floors are untouched.
 */
object RunnerExitProfile7277 {
    private val RUNNER_LANE_KEYS = arrayOf(
        "MOONSHOT", "SHITCOIN", "MEME", "EXPRESS", "MANIPULATED", "MANIP",
        "PRESALE", "PROJECT_SNIPER", "DIP_HUNTER", "INSIDER_SHARK", "COPY_TRADE", "WHALE_FOLLOW",
    )

    /** Peak the position must have reached before a give-back lock may arm on a runner lane. */
    const val MIN_PEAK_FOR_GIVEBACK_LOCK_PCT = 50.0

    /** First-strike cut for a runner-lane position this far under water inside the early window. */
    private const val EARLY_CUT_PCT = -20.0
    private const val EARLY_CUT_WINDOW_MS = 120_000L

    /** The exit tuner may not take a runner lane's take-profit multiplier below this. */
    private const val TP_MULT_FLOOR = 1.0

    fun isRunnerLane(lane: String?): Boolean {
        val s = lane?.trim()?.uppercase() ?: return false
        if (s.isBlank()) return false
        for (k in RUNNER_LANE_KEYS) if (s.contains(k)) return true
        return false
    }

    /** True when a give-back lock should wait: runner lane and the peak is under the arming bar. */
    fun deferGiveBackLock(lane: String?, peakPnlPct: Double): Boolean {
        if (!isRunnerLane(lane)) return false
        val deferred = !peakPnlPct.isFinite() || peakPnlPct < MIN_PEAK_FOR_GIVEBACK_LOCK_PCT
        if (deferred) {
            try { PipelineHealthCollector.labelInc("RUNNER_GIVEBACK_LOCK_DEFERRED_UNDER_MIN_PEAK_7277") } catch (_: Throwable) {}
        }
        return deferred
    }

    /** Give-back arming threshold for [lane]: the runner bar, or the caller's default. */
    fun armThresholdPct(lane: String?, defaultPct: Double): Double =
        if (isRunnerLane(lane)) maxOf(defaultPct, MIN_PEAK_FOR_GIVEBACK_LOCK_PCT) else defaultPct

    /** True when a runner-lane position should be cut on the first strike. */
    fun earlyCut(lane: String?, pnlPct: Double, positionAgeMs: Long): Boolean {
        if (!isRunnerLane(lane)) return false
        if (positionAgeMs < 0L || positionAgeMs > EARLY_CUT_WINDOW_MS) return false
        val cut = pnlPct.isFinite() && pnlPct <= EARLY_CUT_PCT
        if (cut) {
            try { PipelineHealthCollector.labelInc("RUNNER_EARLY_CUT_FIRST_STRIKE_7277") } catch (_: Throwable) {}
        }
        return cut
    }

    /** Lower bound the exit tuner may apply to [lane]'s take-profit multiplier. */
    fun tpMultFloor(lane: String?, tunerMin: Double): Double =
        if (isRunnerLane(lane)) maxOf(tunerMin, TP_MULT_FLOOR) else tunerMin
}
