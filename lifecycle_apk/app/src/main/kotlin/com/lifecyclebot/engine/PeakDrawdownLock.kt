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
    fun shouldLock(peakPnlPct: Double, currentPnlPct: Double): Boolean {
        if (peakPnlPct < ARM_THRESHOLD_PCT) return false
        if (currentPnlPct >= peakPnlPct) return false  // currently at/above peak
        val drawdownFrac = (peakPnlPct - currentPnlPct) / peakPnlPct
        return drawdownFrac >= triggerFracForPeak(peakPnlPct)
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
    fun triggerFracForPeak(peakPnlPct: Double): Double = when {
        peakPnlPct < 50.0    -> 0.40
        peakPnlPct < 100.0   -> 0.40 + (peakPnlPct - 50.0) / 50.0 * 0.05
        peakPnlPct < 300.0   -> 0.45 + (peakPnlPct - 100.0) / 200.0 * 0.10
        peakPnlPct < 1000.0  -> 0.55 + (peakPnlPct - 300.0) / 700.0 * 0.10
        else                 -> (0.65 + (peakPnlPct - 1000.0) / 2000.0 * 0.05).coerceAtMost(0.70)
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
