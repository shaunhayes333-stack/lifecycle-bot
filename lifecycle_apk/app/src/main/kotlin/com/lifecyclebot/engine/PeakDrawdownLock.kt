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
        return drawdownFrac >= DRAWDOWN_TRIGGER_FRAC
    }

    /** Exact pnl% at which shouldLock() would fire for a given peak. */
    fun lockPrice(peakPnlPct: Double): Double =
        peakPnlPct * (1.0 - DRAWDOWN_TRIGGER_FRAC)
}
