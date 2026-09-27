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

    /**
     * V5.0.7386 — no hold-back. Operator: "the sliding profit locks are meant to
     * slide up with positions and lock the profit near peak." The sliding lock
     * (PeakDrawdownLock / fluid trail, V5.0.7282) arms early and ratchets toward
     * the peak on every lane; runner lanes keep their upside through the moonbag
     * (MoonbagRunner7322 banks 60% and lets 40% ride from a +50% peak), not by
     * running unlocked. Kept as a function so every caller reads one decision.
     */
    fun deferGiveBackLock(lane: String?, peakPnlPct: Double): Boolean = false

    /** Give-back arming threshold for [lane]: the caller's default on every lane (V5.0.7386). */
    fun armThresholdPct(lane: String?, defaultPct: Double): Double = defaultPct

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

    /**
     * V5.0.7369 — true when a held position may not be re-laned from [fromLane]
     * to [toLane]: a runner-lane entry keeps runner exits until it closes. 5.0.7368
     * FBdrdZ was bought by PROJECT_SNIPER and sold as STANDARD at -15% by a fluid
     * stop the runner profile would have held through.
     */
    fun refusesLaneChange(fromLane: String?, toLane: String?): Boolean {
        val refused = isRunnerLane(fromLane) && !isRunnerLane(toLane)
        if (refused) {
            try { PipelineHealthCollector.labelInc("RUNNER_LANE_IDENTITY_KEPT_7369") } catch (_: Throwable) {}
        }
        return refused
    }

    /** Lower bound the exit tuner may apply to [lane]'s take-profit multiplier. */
    fun tpMultFloor(lane: String?, tunerMin: Double): Double =
        if (isRunnerLane(lane)) maxOf(tunerMin, TP_MULT_FLOOR) else tunerMin
}
