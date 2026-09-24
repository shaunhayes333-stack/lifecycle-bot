package com.lifecyclebot.engine

/**
 * V5.9.438 — HARD PEAK-DRAWDOWN LOCK (unconditional)
 *
 * User report (Feb 2026): Kenny moonshot peaked at +326%, UI showed the
 * computed profit lock at +314%, but pnl dropped all the way to +108%
 * with the position still open. The fluid profit-floor check exists in
 * every V3 lane but was somehow bypassed — most likely during the
 * laddered partial-take sequence, where a PARTIAL_TAKE return short-
 * circuits the function before the floor check runs on the next tick,
 * or a pendingVerify state skipped several ticks while price collapsed.
 *
 * This gate is the BACKSTOP: runs FIRST inside every V3 checkExit() and
 * fires unconditionally when peak-to-current drawdown is unacceptable.
 *
 * Rules (tuned conservative so it only catches real catastrophes):
 *   - peak must be ≥ +20% (never caps a nascent bag)
 *   - current drawdown from peak ≥ 30% of peak (linear, not log)
 *       peak +100% → fires when current falls below +70%
 *       peak +326% → fires when current falls below +228%
 *       peak +20%  → fires when current falls below +14%
 *
 * When it fires, caller returns TRAILING_STOP so the Executor sells.
 *
 * No state. Pure function. Cannot mis-behave.
 */
object PeakDrawdownLock {

    /** Peak must exceed this pnl% before the lock arms. */
    const val ARM_THRESHOLD_PCT = 20.0

    /**
     * Fraction of peak that must have been given back before firing.
     * V5.9.441 — loosened from 0.30 to 0.40. A meme peaking +50% and
     * consolidating back to +30% is NORMAL market breathing — 30%
     * drawdown was cutting winners way too early.
     */
    const val DRAWDOWN_TRIGGER_FRAC = 0.40

    /**
     * @return true when the position has given back ≥30% of its peak
     *         pnl and the peak was ≥ +20%.
     */
    fun shouldLock(peakPnlPct: Double, currentPnlPct: Double, lane: String = ""): Boolean {
        // V5.0.7277 — a runner lane's give-back lock arms at the runner bar
        // (+50%), not at the general +20%; see RunnerExitProfile7277.
        if (peakPnlPct < RunnerExitProfile7277.armThresholdPct(lane, ARM_THRESHOLD_PCT)) return false
        if (currentPnlPct >= peakPnlPct) return false  // currently at/above peak
        val drawdownFrac = (peakPnlPct - currentPnlPct) / peakPnlPct
        return drawdownFrac >= triggerFracForPeak(peakPnlPct, lane)
    }

    /**
     * V5.0.7267 — the give-back fraction, scaled by the lane's learned exit
     * band (LaneExitTuner tpMult via FluidLearningAI.exitBandMultiplier7267)
     * and capped so the lock always keeps at least a quarter of the peak. A
     * blank lane, or a lane with no evidence, reads the base curve.
     */
    // V5.0.7282 — was 0.75: a lane multiplier could reopen the band to
    // three quarters of the peak. The lock ratchets now; no learned band may
    // hand back more than 40% of a peak.
    const val TRIGGER_FRAC_CAP_7267 = 0.40

    fun triggerFracForPeak(peakPnlPct: Double, lane: String): Double {
        val base = triggerFracForPeak(peakPnlPct)
        if (lane.isBlank()) return base
        val m = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.exitBandMultiplier7267(lane)
        } catch (_: Throwable) { 1.0 }
        return (base * m).coerceIn(0.0, TRIGGER_FRAC_CAP_7267)
    }

    /**
     * V5.9.1326 — RUNNER-CAPTURE FIX. A flat 40% give-back lock cut extreme
     * runners far too early: a +1000% memecoin routinely swings 40%+ off peak
     * as normal breathing, so the lock fired and realized a fraction of the move
     * (live snapshot: avgPeak +1483% → realized +60%, 4% MFE capture). Scale the
     * allowed give-back with peak size — small peaks stay tight to protect base
     * hits; mega-runners get a wide band so volatility can't shake them out.
     * The unconditional -15% hard floor (Executor) remains the real risk backstop.
     *   peak <  +50%   → 0.40  (protect scalps/base hits)
     *   peak ~ +100%   → 0.45
     *   peak ~ +300%   → 0.55
     *   peak ~ +1000%  → 0.65
     *   peak >= +3000% → 0.70 cap
     */
    // V5.0.7282 §THE LOCK SLIDES UP WITH THE PEAK.
    //
    // Operator, on a runner reading Peak +1408% · lock +478%: "the profit
    // lock should slide up to close to peak! giving back 800% is retarded."
    // The V5.9.1326 curve above did the opposite of a ratchet: the give-back
    // fraction GREW with the peak (0.40 → 0.70), so the more a position made,
    // the larger the share of it the lock was willing to return — 66% of a
    // +1408% peak. Every mechanism that reads this curve (this lock, the 1 Hz
    // tick lock through fluidProfitFloor, the give-back stop) inherited it.
    //
    // The fraction now SHRINKS as the peak grows. Small peaks keep the 40%
    // breathing room V5.9.441 gave base hits; past +100% the lock closes in,
    // and past +1000% it holds within ~10% of the peak:
    //   peak <  +50%   → 0.40  (unchanged: a +40% pop may breathe to +24%)
    //   peak  +100%    → 0.30  (lock +70%)
    //   peak  +300%    → 0.18  (lock +246%)
    //   peak +1000%    → 0.12  (lock +880%)
    //   peak +1408%    → 0.11  (lock +1251%, was +478%)
    //   peak >= +3000% → 0.08 floor
    // 1326's fear was a single wick shaking a 10x out; the operator has
    // decided which failure is cheaper, and a 10% wick off a 15x is a smaller
    // loss than an 800-point give-back.
    fun triggerFracForPeak(peakPnlPct: Double): Double = when {
        peakPnlPct < 50.0    -> 0.40
        peakPnlPct < 100.0   -> 0.40 - (peakPnlPct - 50.0) / 50.0 * 0.10
        peakPnlPct < 300.0   -> 0.30 - (peakPnlPct - 100.0) / 200.0 * 0.12
        peakPnlPct < 1000.0  -> 0.18 - (peakPnlPct - 300.0) / 700.0 * 0.06
        else                 -> (0.12 - (peakPnlPct - 1000.0) / 2000.0 * 0.04).coerceAtLeast(0.08)
    }

    /** Exact pnl% at which shouldLock() would fire for a given peak. */
    fun lockPrice(peakPnlPct: Double): Double =
        peakPnlPct * (1.0 - triggerFracForPeak(peakPnlPct))

    // ──────────────────────────────────────────────────────────────────────
    // V5.9.1433 — ABSOLUTE MFE-RATCHETED PROFIT FLOOR (runner protection).
    // Operator: "stop wasting MOONSHOT runners ... never realize red after
    // MFE > +75%." The give-back lock above scales with peak size but has NO
    // absolute positive floor, so a play that popped to +80% then bled could
    // still be realized near breakeven / red (esp. with paper slippage). This
    // adds a hard, milestone-ratcheted MINIMUM realized PnL once a position has
    // earned it. It NEVER widens risk (it only forces an EARLIER profit-taking
    // exit) and is fully consistent with the -15% hard floor. Pure function,
    // no state.
    //   MFE >= +250%  → floor +60%  (lock most of a mega-runner, trail the rest)
    //   MFE >= +150%  → floor +60%
    //   MFE >=  +75%  → floor +25%  (operator: never red after +75%)
    //   MFE >=  +35%  → floor   0%  (never give back a +35% pop into red)
    //   below +35%    → no floor (let base hits breathe; give-back lock handles it)
    fun mfeProfitFloorPct(peakPnlPct: Double): Double? = when {
        peakPnlPct >= 150.0 -> 60.0
        peakPnlPct >= 75.0  -> 25.0
        peakPnlPct >= 35.0  -> 0.0
        else                -> null
    }

    /**
     * @return true when current PnL has fallen to/under the MFE-ratcheted
     *         absolute profit floor — caller should realize now to protect
     *         the banked gain. Only arms once MFE >= +35%.
     */
    fun shouldFloorLock(peakPnlPct: Double, currentPnlPct: Double): Boolean {
        val floor = mfeProfitFloorPct(peakPnlPct) ?: return false
        return currentPnlPct <= floor
    }
}
