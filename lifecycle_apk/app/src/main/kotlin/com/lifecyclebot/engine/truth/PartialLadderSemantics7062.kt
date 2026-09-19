package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7062 — DIRECTIVE §5: A LADDER THAT ADDED FRACTIONS OF DIFFERENT WHOLES.
 *
 * THE DEFECT, which is one line repeated three times:
 *
 *     Executor:8845   newSoldPct = soldPct + sellFraction * 100.0
 *     Executor:21324  newSoldPct = pos.partialSoldPct + (pct * 100.0)
 *     Executor:21510  newSoldPct = pos.partialSoldPct + (pct * 100)
 *
 * `sellFraction` is a fraction OF WHAT REMAINS. `partialSoldPct` is a
 * percentage OF THE ORIGINAL POSITION. Adding one to the other adds two
 * fractions of different wholes, which is not an operation — and it always
 * overstates, because every rung after the first contributes a slice of a
 * shrinking base as though it were a slice of the full one.
 *
 * WORKED AGAINST THE OPERATOR'S F9CBDp LADDER, four rungs at ~22% of
 * remaining each:
 *
 *     rung  remainingOfOriginal   TRUE soldPct    LABEL IT PRINTED
 *      1          0.780              22.0 %         partial_22pct
 *      2          0.608              39.2 %         partial_45pct
 *      3          0.475              52.5 %         partial_67pct
 *      4          0.370              63.0 %         partial_87pct
 *
 * The book believed 87% of the position was gone when 37% of it was still
 * held. The next rung crossed 99.9 and the remainder was dumped as
 * FULL_EXIT_100PCT — which is why that final row carries a quantity an order
 * of magnitude different from the rungs above it, and why an exit labelled
 * 100% could leave real inventory behind.
 *
 * THE CORRECTION IS EXACT, NOT APPROXIMATE
 * ========================================
 * If f is a fraction of remaining, the remaining share of the ORIGINAL after
 * the rung is the share before it times (1 - f). Sold percentage is one minus
 * that share:
 *
 *     remainingShareBefore = 1 - soldPctBefore/100
 *     remainingShareAfter  = remainingShareBefore * (1 - f)
 *     soldPctAfter         = 100 * (1 - remainingShareAfter)
 *
 * Composition, not addition. It needs no canonical lookup, so it is correct on
 * the live path as well as the paper one, and it reduces to the old expression
 * exactly when soldPctBefore is 0 — which is why the FIRST rung of every
 * ladder always looked right and the defect only showed from the second on.
 *
 * §5's FULL-EXIT INVARIANT is the other half: an exit may only be CALLED
 * FULL_EXIT_100PCT if the position is actually empty afterwards. [verifyFullExit7062]
 * checks that against the canonical remainder and emits
 * PARTIAL_FULL_EXIT_INVARIANT_FAILURE when a 100% label is left holding
 * inventory. It reports; it does not mutate, because a label being wrong is
 * not a reason to force a sale the strategy did not ask for.
 */
object PartialLadderSemantics7062 {

    /**
     * Remaining quantity at or below this share of the original counts as dust
     * for the purpose of §5's full-exit invariant. One basis point: small
     * enough that a genuine remainder is caught, loose enough that integer
     * rounding at the quantity scale is not reported as a failure.
     */
    private const val DUST_SHARE_OF_ORIGINAL = 0.0001

    private val composed = AtomicLong(0L)
    private val fullExitChecked = AtomicLong(0L)
    private val fullExitFailed = AtomicLong(0L)
    private val worstOverstatePct = AtomicLong(0L)

    /**
     * §5 — compose a cumulative sold percentage of the ORIGINAL position from
     * the previous cumulative percentage and a fraction OF WHAT REMAINS.
     *
     * [soldPctBefore] is 0..100. [sellFractionOfRemaining] is 0..1. Returns
     * 0..100. Degenerate inputs fall back to the caller's own arithmetic
     * rather than inventing a number.
     */
    fun compose7062(soldPctBefore: Double, sellFractionOfRemaining: Double): Double {
        val before = if (soldPctBefore.isFinite()) soldPctBefore.coerceIn(0.0, 100.0) else 0.0
        val f = sellFractionOfRemaining
        if (!f.isFinite() || f <= 0.0) return before
        val fClamped = f.coerceAtMost(1.0)
        val remainingShareBefore = 1.0 - (before / 100.0)
        if (remainingShareBefore <= 0.0) return 100.0
        val remainingShareAfter = remainingShareBefore * (1.0 - fClamped)
        val after = (100.0 * (1.0 - remainingShareAfter)).coerceIn(0.0, 100.0)
        if (!after.isFinite()) return before
        composed.incrementAndGet()
        // The old expression, kept only to size what it was overstating by.
        val legacy = (before + fClamped * 100.0).coerceAtMost(100.0)
        val overstate = legacy - after
        if (overstate > 0.5) {
            try { PipelineHealthCollector.labelInc("LADDER_SOLD_PCT_OVERSTATED_7062") } catch (_: Throwable) {}
            val milli = (overstate * 1000.0).toLong()
            while (true) {
                val prev = worstOverstatePct.get()
                if (milli <= prev || worstOverstatePct.compareAndSet(prev, milli)) break
            }
        }
        return after
    }

    /**
     * §5 — a rung labelled FULL_EXIT_100PCT must leave the position empty.
     *
     * Returns true when the invariant holds (or cannot be evaluated, which is
     * not treated as a failure). Reports only; see the class note for why it
     * does not force a follow-up sale.
     */
    fun verifyFullExit7062(
        positionId: String,
        mint: String,
        symbol: String,
        reasonLabel: String,
    ): Boolean {
        if (!reasonLabel.equals("FULL_EXIT_100PCT", true)) return true
        fullExitChecked.incrementAndGet()
        val pos = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
            ?: return true
        val original = pos.originalQtyRaw
        val remaining = pos.remainingQtyRaw
        if (original <= BigInteger.ZERO) return true
        if (remaining <= BigInteger.ZERO) return true
        val remainingShare = try {
            java.math.BigDecimal(remaining)
                .divide(java.math.BigDecimal(original), java.math.MathContext.DECIMAL64)
                .toDouble()
        } catch (_: Throwable) { return true }
        if (!remainingShare.isFinite() || remainingShare <= DUST_SHARE_OF_ORIGINAL) return true
        fullExitFailed.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PARTIAL_FULL_EXIT_INVARIANT_FAILURE")
            ForensicLogger.lifecycle(
                "PARTIAL_FULL_EXIT_INVARIANT_FAILURE",
                "positionId=$positionId mint=${mint.take(10)} sym=$symbol " +
                    "originalRaw=$original remainingRaw=$remaining " +
                    "remainingShare=${"%.6f".format(remainingShare)} " +
                    "dustShare=$DUST_SHARE_OF_ORIGINAL " +
                    "action=label_says_full_exit_but_inventory_remains",
            )
        } catch (_: Throwable) {}
        return false
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String =
        "composed=${composed.get()} worstOverstate=${"%.2f".format(worstOverstatePct.get() / 1000.0)}pp " +
            "fullExitChecked=${fullExitChecked.get()} fullExitFailed=${fullExitFailed.get()}"
}
