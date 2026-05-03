package com.lifecyclebot.engine

/**
 * V5.9.437 — OUTCOME GATES (Phase B)
 *
 * Turns the *data* collected by HoldDurationTracker + ExitReasonTracker
 * (V5.9.436 Phase A) into *live decisions* inside every V3 checkExit().
 *
 * Two gentle, fail-open biases — both additive, never loss-crystallising:
 *
 *   1. earlyExitByHoldBucket(layer, holdMinutes, pnlPct)
 *      If this lane's current hold-bucket has proven net-losing
 *      expectancy AND the position is currently flat (-3% < pnl < +3%),
 *      exit NOW. "History says this doesn't work — take what we have."
 *      Never fires outside the flat zone so SL / TP still handle winners
 *      and real losers.
 *
 *   2. timeExitExtensionMult(layer, exitReason, pnlPct)
 *      If this lane+exit-reason has proven net-losing AND the position
 *      is currently in profit, *extend* the time-exit deadline by 50%
 *      so winners get more runway. Only extends positive-pnl trades —
 *      never postpones a losing bag.
 *
 * Both helpers query V5.9.436 trackers; if either has <25 samples in
 * the relevant bucket, the helper is a no-op (returns false / 1.0).
 *
 * Thread-safe, fail-open, pure function. Zero state of its own.
 */
object OutcomeGates {

    /** pnl% below which SL logic already handles the bag. */
    private const val FLAT_LO = -3.0

    /** pnl% above which TP logic already handles the bag. */
    private const val FLAT_HI = 3.0

    /** Bucket mean must be below this to classify as "provably losing". */
    private const val LOSING_BUCKET_PNL = -2.0

    /** Extend time-exit deadline by this factor when biased on a winner. */
    private const val WINNER_EXTENSION_MULT = 1.5

    /**
     * Return true when the caller should cut the bag right now because:
     *   - we're in the flat zone (SL/TP not firing on their own)
     *   - AND this (layer, hold-bucket) has proven net-losing expectancy
     *     with ≥25 closed samples.
     */
    fun earlyExitByHoldBucket(
        layer: String,
        holdMinutes: Long,
        pnlPct: Double,
    ): Boolean {
        if (pnlPct <= FLAT_LO || pnlPct >= FLAT_HI) return false
        val mean = HoldDurationTracker.bucketMean(layer, holdMinutes) ?: return false
        return mean < LOSING_BUCKET_PNL
    }

    /**
     * Return a multiplier for a time-based exit's firing deadline.
     *   - 1.0 (default)     : no change, fire at normal threshold
     *   - 1.5 (extended)    : give winners more runway when this exit
     *                         reason has proven bad on this lane.
     *
     * Only ever extends positive-pnl positions. Losers are untouched.
     */
    fun timeExitExtensionMult(
        layer: String,
        exitReason: String,
        pnlPct: Double,
    ): Double {
        if (pnlPct <= 0.0) return 1.0
        val mean = ExitReasonTracker.reasonMean(layer, exitReason) ?: return 1.0
        return if (mean < LOSING_BUCKET_PNL) WINNER_EXTENSION_MULT else 1.0
    }
}
