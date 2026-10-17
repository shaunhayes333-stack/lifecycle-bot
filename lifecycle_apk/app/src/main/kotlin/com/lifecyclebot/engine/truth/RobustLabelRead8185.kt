package com.lifecyclebot.engine.truth

/**
 * V5.0.8185 §ROBUST_LABEL_READ — stored outlier labels no longer prove a cell or retire a veto.
 *
 * 5.0.8175: the veto audit printed NATIVE_MCAP_TOO_HIGH n141 / +802.9% REFUSING_WINNERS and retired the rule 868 times
 * (VETO_RETIRED_7953_NATIVE_MCAP_TOO_HIGH), and lanes60 read DIP_HUNTER +688.5% / TREASURY +957.5%. Both rested on
 * BAND|*|MC_GT1M forward labels at +1,500..+3,500% means with a 13-20% win rate: a handful of $1M+ coins "labelled" at
 * 100x+ inside five minutes — the stale registry-mark basis error 5.0.8180 stopped booking. The books keep only running
 * aggregates (n, sum, sumSq, wins), so the bad labels cannot be picked out and removed, and learning is never reset.
 * What changes is how the aggregates are READ:
 *
 *  - Every label is read through a plausibility cap (the cells' labels are revised from 5 minutes up to 7 days, so the cap
 *    is an 11x for one label, a 4x for a coin already in the $1M+ cap band — where the poisoned labels sat).
 *    The winsorised mean (each label clamped at the cap) is bounded from the aggregates alone:
 *      upper = min(sum, wins * cap) / n     (no positive label can contribute more than the cap; non-positive add <= 0)
 *      lower = (sum - sumSq / (4 * cap)) / n (for every y: y - cap <= y^2 / (4 cap))
 *  - A record whose raw mean exceeds [upper] is IMPOSSIBLE under the cap (its winners average more than a cap's worth):
 *    it is OUTLIER_SUSPECT. It reads at [upper] with a standard error spanning the whole winsorised range
 *    (upper - lower, finite: several consumers read an infinite error as zero), so mean - se is the pessimistic bound and
 *    mean + se stays above zero — no proven edge from the outliers and no manufactured loss.
 *  - A veto retires only when the PESSIMISTIC winsorised mean minus a capped standard error clears +2% net, on top of the
 *    rule's original test. That only makes retirement stricter: a refusal stays until its winners are real.
 *
 * Data within the cap reads exactly as before ([upper] equals the raw mean when no label exceeds the cap).
 */
object RobustLabelRead8185 {
    /** An 11x: the plausible ceiling of one label (read at 5 minutes, revised up to 7 days while the coin lives). */
    const val CAP_LABEL_PCT_8185 = 1_000.0
    /** A 4x for a coin already above a $1M cap (5.0.8175's poisoned BAND|*|MC_GT1M labels read +1,500..+3,500% means). */
    const val CAP_LARGE_CAP_PCT_8185 = 300.0
    /** The veto book revises inside each lane's hold; a refused coin's plausible single-label ceiling. */
    const val CAP_VETO_PCT_8185 = 1_000.0
    private const val LOSS_FLOOR_PCT_8185 = -100.0

    /** Pure: the per-label cap for a labeler key (the $1M+ cap band reads at the large-cap ceiling). */
    fun capForKey8185(key: String): Double =
        if (key.contains("MC_GT1M")) CAP_LARGE_CAP_PCT_8185 else CAP_LABEL_PCT_8185

    /** Pure: the largest winsorised mean the aggregates allow (equals the raw mean when no label exceeds the cap). */
    fun upperMean8185(n: Double, sum: Double, wins: Double, cap: Double): Double {
        if (!(n > 0.0) || !sum.isFinite() || !(cap > 0.0)) return 0.0
        return minOf(sum, wins.coerceIn(0.0, n) * cap) / n
    }

    /** Pure: the smallest winsorised mean the aggregates allow (never below a total loss). */
    fun lowerMean8185(n: Double, sum: Double, sumSq: Double, cap: Double): Double {
        if (!(n > 0.0) || !sum.isFinite() || !sumSq.isFinite() || !(cap > 0.0)) return LOSS_FLOOR_PCT_8185
        return ((sum - sumSq.coerceAtLeast(0.0) / (4.0 * cap)) / n).coerceAtLeast(LOSS_FLOOR_PCT_8185)
    }

    /** Pure: the raw mean is impossible under the cap — the record's winners average more than the cap. */
    fun outlierSuspect8185(n: Double, sum: Double, wins: Double, cap: Double): Boolean {
        if (!(n > 0.0) || !sum.isFinite()) return false
        return sum / n > upperMean8185(n, sum, wins, cap) + 1e-9
    }

    /** Pure: the standard error of a mean of labels confined to [-100, cap] (Popoviciu bound on the variance). */
    fun cappedStderr8185(n: Double, variance: Double, cap: Double): Double {
        if (!(n > 1.0)) return Double.POSITIVE_INFINITY
        val bound = (cap - LOSS_FLOOR_PCT_8185) * (cap - LOSS_FLOOR_PCT_8185) / 4.0
        val v = if (variance.isFinite()) minOf(variance.coerceAtLeast(0.0), bound) else bound
        return kotlin.math.sqrt(v / n)
    }

    /**
     * Pure: refused candidates proven to win on a robust read — 40+ graded, the pessimistic winsorised mean minus the
     * capped standard error above [marginPct], and the record not outlier-suspect.
     */
    fun robustWinnersProven8185(n: Double, sum: Double, sumSq: Double, wins: Double, variance: Double,
                                cap: Double = CAP_VETO_PCT_8185, marginPct: Double = 2.0): Boolean {
        if (!(n >= 40.0)) return false
        if (outlierSuspect8185(n, sum, wins, cap)) return false
        return lowerMean8185(n, sum, sumSq, cap) - cappedStderr8185(n, variance, cap) > marginPct
    }

    /** One read of a labeler record: the mean to use and its standard error (the winsorised range when suspect). */
    data class Read8185(val mean: Double, val stderr: Double, val suspect: Boolean)

    /** Pure: the robust read of a labeler aggregate. Non-suspect records keep their raw mean and standard error. */
    fun read8185(key: String, n: Int, sum: Double, sumSq: Double, wins: Int): Read8185 {
        if (n <= 0) return Read8185(0.0, Double.POSITIVE_INFINITY, false)
        val nd = n.toDouble()
        val mean = sum / nd
        val variance = if (n > 1) ((sumSq / nd) - mean * mean).coerceAtLeast(0.0) else 0.0
        val se = if (n > 1) kotlin.math.sqrt(variance / nd) else Double.POSITIVE_INFINITY
        val cap = capForKey8185(key)
        if (!outlierSuspect8185(nd, sum, wins.toDouble(), cap)) return Read8185(mean, se, false)
        suspectCount8185.incrementAndGet()
        val hi = upperMean8185(nd, sum, wins.toDouble(), cap)
        val lo = lowerMean8185(nd, sum, sumSq, cap)
        val seCapped = cappedStderr8185(nd, variance, cap)
        return Read8185(hi, maxOf(if (seCapped.isFinite()) seCapped else 0.0, hi - lo), true)
    }

    private val suspectCount8185 = java.util.concurrent.atomic.AtomicLong(0)
    fun suspectReads8185(): Long = suspectCount8185.get()
}
