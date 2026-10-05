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
    // V5.0.7801 — TRUE TAIL LANES ONLY.
    //
    // Runner protection is a thesis, not a synonym for "meme". EXPRESS exists
    // to bank velocity, MANIPULATED exists to escape before distribution,
    // DIP_HUNTER manages a recovery, and CORE is a generalist. Giving those
    // lanes Moonshot's deferred profit locks silently overwrote their native
    // management. PROJECT_SNIPER and SHITCOIN remain tail-capable because their
    // design explicitly retains early winners that transition into adoption.
    private val RUNNER_LANE_KEYS = arrayOf(
        "MOONSHOT", "SHITCOIN", "MEME",
        "PRESALE", "PROJECT_SNIPER", "INSIDER_SHARK", "COPY_TRADE", "WHALE_FOLLOW",
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
     * V5.0.7695 §A_PROFIT_LOCK_THAT_SELLS_AT_MINUS_FIVE_IS_NOT_A_PROFIT_LOCK.
     *
     * V5.0.7386 set this to `false` on every lane — "the sliding profit locks
     * are meant to slide up with positions and lock the profit near peak" — so
     * a runner lane's give-back locks armed from the first tick. The operator's
     * 5.0.7693 live tape, with RUNNER_EARLY_EXIT_7693 now naming each trigger
     * (43 of them in four minutes):
     *
     *   MICRO   MOONSHOT  heldSec=206  pnl=-5.17  peak=4.8
     *           TICK_PROFIT_LOCK_GAPPED_peak4_floor2_now-5
     *           LIVE_STYLE_MIN_HOLD_PEAK_GIVEBACK_BYPASS giveback=10.0
     *           severity=RUNNER_PROTECT action=sell_now
     *   HppB5r  MOONSHOT  sold 21s after the buy at -7.6%, original reason a
     *           profit-type lock (REALIZED_LOSS_AFTER_PROFIT_SIGNAL)
     *   PEAK_CAPTURE_EXIT_REQUESTED_7358: 36
     *
     * getDynamicFluidStop turns a +4.8% peak into a +2 floor (peak gap 2 under
     * +10%); on a fresh pump.fun pair a 5-10% wiggle is the normal tape, so the
     * floor is crossed within minutes and the position is sold — at a loss once
     * the price has gapped through zero. That is a tighter stop than the lane's
     * own -5/-10/-15, wearing a profit lock's name. In the same snapshot:
     * "Lane shadow proof: MOONSHOT[n=7 net=+12343.6% wr=14%]" and live
     * MOONSHOT 0 wins. The lane cannot reach the tail if the first +5% blip
     * arms a lock that the next -5% blip fires.
     *
     * So the deferral is back, at +20% rather than 7277's +50%: under a +20%
     * peak a runner-lane position is protected by its stops (MoonshotTraderAI
     * EARLY_TIGHT_STOP -5 before +8% peak, the -10 tick floor, the -15 lane
     * floor, catastrophe exits) and nothing else; from +20% the sliding lock
     * arms and ratchets toward the peak exactly as 7386 intended, and the
     * moonbag still banks from +50%. Every give-back path reads this one
     * function: the 1Hz tick lock, the 500ms peak-capture and peak-lock
     * breach, the rapid drawdown stop, the settle-window MFE/peak locks, and
     * MoonshotTraderAI's own peak-drawdown / fluid floor.
     */
    const val RUNNER_LOCK_ARM_PEAK_PCT_7695 = 20.0

    fun giveBackArmPct7791(lane:String?):Double=if(lane?.uppercase()?.contains("MOONSHOT")==true)MIN_PEAK_FOR_GIVEBACK_LOCK_PCT else RUNNER_LOCK_ARM_PEAK_PCT_7695

    fun deferGiveBackLock(lane: String?, peakPnlPct: Double): Boolean {
        if (!isRunnerLane(lane)) return false
        val deferred = !peakPnlPct.isFinite() || peakPnlPct < giveBackArmPct7791(lane)
        if (deferred) {
            try { PipelineHealthCollector.labelInc("RUNNER_GIVEBACK_LOCK_DEFERRED_UNDER_MIN_PEAK_7277") } catch (_: Throwable) {}
        }
        return deferred
    }

    /** Give-back arming threshold for [lane]: a runner lane never arms under RUNNER_LOCK_ARM_PEAK_PCT_7695 (V5.0.7695). */
    fun armThresholdPct(lane: String?, defaultPct: Double): Double =
        if (isRunnerLane(lane)) maxOf(defaultPct, giveBackArmPct7791(lane)) else defaultPct

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
