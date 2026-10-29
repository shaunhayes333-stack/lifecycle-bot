package com.lifecyclebot.engine.truth

/**
 * V5.0.8419 §CLASSIFY_DONT_CLAMP — the one rule for the magnitude of a return on the learning path.
 *
 * Standing rule (owner, 2026-10-12, 8072): no win caps on chain-provable data. Reviewers (8418 wave): "+500 % and
 * +600 % are treated identically" — SpecialistMiner7972 booked every label at min(net, +300) (NET_CAP), the shadow
 * books stored min(net, +1000), and CanonicalTradeFinalizedBus6450 quarantined a chain-finalized close above
 * +100,000 % as RETURN_OUTSIDE_TRAINABLE_RANGE. A clamp writes a number that did not happen; a quarantine of a
 * witnessed win teaches the books that runners never happen.
 *
 * Here a return is CLASSIFIED, never reshaped:
 *   - a non-finite value, or one below a total loss (< -100 %), is CORRUPT (a unit / basis break) and refused;
 *   - a value above [PLAUSIBLE_UNWITNESSED_PCT_8419] (RobustLabelRead8185's single-observation ceiling, an 11x) is
 *     refused as UNWITNESSED_EXTREME unless the caller can say the marks were chain-witnessed (a receipt, a curve /
 *     pool account read, a corroborated chain mark: LabelEvidence8386.independent8418), in which case it trains at
 *     its full value;
 *   - everything else trains at exactly the observed value.
 * Pure: no Android, no I/O.
 */
object ReturnMagnitude8419 {
    /** A total loss; below it a percentage return is arithmetic that cannot describe a position. */
    const val TOTAL_LOSS_PCT_8419 = -100.0
    /** The single-observation plausibility ceiling an UNWITNESSED mark may claim (RobustLabelRead8185: an 11x). */
    const val PLAUSIBLE_UNWITNESSED_PCT_8419 = RobustLabelRead8185.CAP_LABEL_PCT_8185
    /**
     * The smallest cost basis (SOL) a real position can have: below it a return above the trainable ceiling is a unit
     * break (a 1e6 decimals slip makes a 0.05 SOL ticket read as a 5e-8 SOL basis), not a trade.
     */
    const val MIN_PLAUSIBLE_BASIS_SOL_8419 = 0.0001

    const val REASON_TRAIN = "TRAIN"
    const val REASON_NON_FINITE = "CORRUPT_NON_FINITE"
    const val REASON_BELOW_TOTAL_LOSS = "CORRUPT_BELOW_TOTAL_LOSS"
    const val REASON_UNWITNESSED_EXTREME = "OBSERVED_ONLY_UNWITNESSED_EXTREME"

    /** The verdict: [train] with [value] exactly as observed, or refused for [reason]. */
    data class Verdict8419(val train: Boolean, val value: Double, val reason: String) {
        val corrupt: Boolean get() = reason.startsWith("CORRUPT_")
    }

    /**
     * Pure: classify a percentage return. [witnessed] = both marks (or the receipt) are chain-provable; an unwitnessed
     * value above [unwitnessedCeilingPct] is observed only (never clamped to the ceiling, never trained).
     */
    fun classify8419(pct: Double, witnessed: Boolean, unwitnessedCeilingPct: Double = PLAUSIBLE_UNWITNESSED_PCT_8419): Verdict8419 {
        if (!pct.isFinite()) return Verdict8419(false, pct, REASON_NON_FINITE)
        if (pct < TOTAL_LOSS_PCT_8419 - 1e-9) return Verdict8419(false, pct, REASON_BELOW_TOTAL_LOSS)
        if (!witnessed && unwitnessedCeilingPct.isFinite() && pct > unwitnessedCeilingPct) return Verdict8419(false, pct, REASON_UNWITNESSED_EXTREME)
        return Verdict8419(true, pct, REASON_TRAIN)
    }

    /**
     * Pure: a label the labeler already classified (LabelEvidence8386: identity, units, decimals, time order, and chain
     * proof for a >= 10x move) is witnessed by construction; only arithmetic that cannot describe a position is refused.
     */
    fun classifyLabel8419(pct: Double): Verdict8419 = classify8419(pct, witnessed = true)

    /**
     * Pure: is a settled close with [netPnlSol] at [returnFraction] a unit break? A return above [ceilingPct] whose implied
     * basis (pnl / fraction) is below [MIN_PLAUSIBLE_BASIS_SOL_8419] cannot be a position: nothing trades dust, so the
     * fraction carries a decimals / basis slip. A return within the ceiling is never judged here.
     */
    fun unitBreak8419(netPnlSol: Double, returnFraction: Double, ceilingPct: Double): Boolean {
        if (!netPnlSol.isFinite() || !returnFraction.isFinite() || !ceilingPct.isFinite()) return false
        if (returnFraction * 100.0 <= ceilingPct || kotlin.math.abs(returnFraction) < 1e-12) return false
        val impliedBasis = netPnlSol / returnFraction
        return !(impliedBasis.isFinite() && impliedBasis >= MIN_PLAUSIBLE_BASIS_SOL_8419)
    }

    /**
     * Pure: the value a shadow close (a modelled exit on observed marks) is booked at — exactly the observed net when it
     * describes a position; a below-total-loss value (a basis break) is booked as a total loss by name, never as a clamped
     * winner. The proof reads keep their own conservative cap.
     */
    fun bookedShadowValue8419(net: Double): Double {
        val v = classify8419(net, witnessed = true)
        return if (v.train) v.value else TOTAL_LOSS_PCT_8419
    }

    /** Pure: the text a profit factor prints — "inf" when there were wins and no losses, "-" when nothing was measured. */
    fun pfText8419(pf: Double): String = when {
        pf.isNaN() -> "-"
        pf.isInfinite() -> "inf"
        else -> "%.2f".format(java.util.Locale.ROOT, pf)
    }

    /**
     * Pure: a profit factor from gross wins and gross losses — the ratio when there were losses, +inf when there were only
     * wins, NaN when nothing was measured. Never a stand-in number (999 / 9.99 / 2.0 were printed and compared as if measured).
     */
    fun profitFactor8419(grossWin: Double, grossLoss: Double): Double = when {
        grossLoss > 0.0 -> grossWin / grossLoss
        grossWin > 0.0 -> Double.POSITIVE_INFINITY
        else -> Double.NaN
    }
}
