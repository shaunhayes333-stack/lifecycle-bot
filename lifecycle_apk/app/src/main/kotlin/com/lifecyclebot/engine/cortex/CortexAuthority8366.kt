package com.lifecyclebot.engine.cortex

/**
 * V5.0.8366 §ONE_AUTHORITY_TEST — a lane's STRONG-side authority is earned on ONE walk-forward test of its executable labels.
 * V5.0.8372 — [test8372] is that runtime test: the actual frozen STRONG verdicts versus disjoint non-STRONG verdicts,
 * with explicit outcome availability. The 8366 quantile selector described below remains [test8366] for diagnostics only.
 *
 * Before: nine stacked gates (label fraction n / mean - SE bars, the calibration slope floor, realised STRONG closes needed
 * before any authority (CortexRealisedAuthority8136), the STRONG-cohort walk-forward's INSUFFICIENT_DATA / rival /
 * resolved-share verdicts (CortexWalkForward8144), owner-routed realised evidence (8153), inversion shadows ...), most of
 * them mean - SE on fat tails, and the realised gate needed STRONG closes that cannot happen without authority —
 * Cortex7885's own 8200 comment admits the deadlock.
 *
 * Now, per lane, on the graded executable decision records (CortexGradeBook8144.scoredRows8366: the decision-time score and
 * the executable-net lane objective), oldest first, cut into [FOLDS_8366] contiguous time folds. For every fold k >= 1 the
 * TOP / BOTTOM score cut-offs are the [TOP_Q_8366] / [BOTTOM_Q_8366] quantiles of the scores of the folds BEFORE it (known
 * at the time — nothing from fold k's outcomes), and fold k's rows join the out-of-sample (OOS) pool, tagged top / bottom.
 * GRANTED when, on [MIN_OOS_N_8366]+ OOS rows:
 *   - OOS rank IC (Spearman of score vs outcome) > 0;
 *   - OOS top-bucket mean minus bottom-bucket mean > the lane's round-trip cost;
 *   - OOS top-bucket mean > 0 (STRONG reads must make money net, not just less than REFUSE).
 * The authority is graduated by the OOS margin: fraction = (spread - cost) / max(cost, [MIN_FULL_MARGIN_PCT_8366]), in [0, 1].
 * Realised live closes (the account mode's STRONG closes) can only REVOKE, and only on [REVOKE_MIN_N_8366]+ closes with a
 * mean <= 0; they are never required to grant. The old gates are still computed for display.
 */
object CortexAuthority8366 {
    const val FOLDS_8366 = 5
    const val MIN_OOS_N_8366 = 100
    const val MIN_BUCKET_N_8366 = 10
    const val TOP_Q_8366 = 0.8
    const val BOTTOM_Q_8366 = 0.2
    const val MIN_FULL_MARGIN_PCT_8366 = 2.0
    const val REVOKE_MIN_N_8366 = 20

    enum class Verdict8366 { INSUFFICIENT_OOS, NO_RANK, SPREAD_BELOW_COST, TOP_NOT_POSITIVE, GRANTED }

    /**
     * One graded executable decision: its immutable decision-time score/verdict and executable-net objective (%).
     * 8372's runtime path requires the frozen verdict and when this exact outcome (including a runner revision) became
     * available. Defaults keep historical three-field rows readable by the legacy diagnostic test, not current proof.
     */
    data class Row8366(
        val atMs: Long,
        val score: Double,
        val netPct: Double,
        val frozenBucket: Int? = null,
        val outcomeAvailableAtMs: Long = 0L,
    )

    data class Result8366(
        val verdict: Verdict8366,
        val oosN: Int,
        val ic: Double,
        val topMean: Double,
        val bottomMean: Double,
        val topN: Int,
        val bottomN: Int,
        /** Standard error of the OOS top-bucket mean (infinite under two rows). */
        val topSe: Double,
        val costPct: Double,
        val fraction: Double,
        /** The legacy quantile test and current actual-verdict test are explicitly distinguished in telemetry. */
        val cohort: String = "RANK_QUANTILES_8366",
        /** Current rows not usable yet, or lacking valid frozen prediction/outcome provenance. */
        val excludedRows8372: Int = 0,
    ) {
        val spread: Double get() = if (topMean.isFinite() && bottomMean.isFinite()) topMean - bottomMean else Double.NaN
        val granted: Boolean get() = verdict == Verdict8366.GRANTED && fraction > 0.0
        fun compact(): String = "${verdict.name} oos=n$oosN ic=${f(ic)} top=${f(topMean)}(n$topN) bot=${f(bottomMean)}(n$bottomN) " +
            "spread=${f(spread)} cost=${f(costPct)} auth=${(kotlin.math.round(fraction * 100.0) / 100.0)}" +
            if (cohort == "RANK_QUANTILES_8366") "" else " cohort=$cohort excluded=$excludedRows8372"
        private fun f(v: Double) = if (v.isFinite()) (kotlin.math.round(v * 100.0) / 100.0).toString() else "-"
    }

    /** Pure: the [q]-quantile (nearest rank, sorted copy) of the finite values; NaN when empty. */
    fun quantile8366(xs: List<Double>, q: Double): Double {
        val s = xs.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return Double.NaN
        val i = (q.coerceIn(0.0, 1.0) * (s.size - 1)).toInt().coerceIn(0, s.size - 1)
        return s[i]
    }

    /** Pure: the single walk-forward test of [rows] (any order) at round-trip cost [costPct]. */
    fun test8366(rows: List<Row8366>, costPct: Double, folds: Int = FOLDS_8366): Result8366 {
        val cost = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        val xs = rows.filter { it.atMs > 0L && it.score.isFinite() && it.netPct.isFinite() }.sortedBy { it.atMs }
        val k = folds.coerceIn(2, 10)
        val oosScore = ArrayList<Double>(); val oosNet = ArrayList<Double>()
        val top = ArrayList<Double>(); val bottom = ArrayList<Double>()
        for (i in 1 until k) {
            val first = (i.toLong() * xs.size / k).toInt()
            val end = ((i + 1).toLong() * xs.size / k).toInt()
            if (first <= 0 || end <= first) continue
            val past = xs.subList(0, first).map { it.score }
            val hi = quantile8366(past, TOP_Q_8366)
            val lo = quantile8366(past, BOTTOM_Q_8366)
            for (r in xs.subList(first, end)) {
                oosScore.add(r.score); oosNet.add(r.netPct)
                if (hi.isFinite() && r.score >= hi) top.add(r.netPct)
                if (lo.isFinite() && r.score <= lo) bottom.add(r.netPct)
            }
        }
        return evaluate8366(oosScore, oosNet, top, bottom, cost)
    }

    /**
     * V5.0.8372 — the ONE runtime authority test, on the actual predictions that exercise that authority.
     *
     * A profitable top score quintile did not prove that the STRONG verdicts were profitable: percentile cutoffs and
     * the displayed per-token bucket could select different coins. Ties could even enter both quantile cohorts.
     * Use immutable STRONG decisions versus the disjoint NEUTRAL/REFUSE baseline instead. Requiring REFUSE alone would
     * wrongly strand runner lanes whose tail protection legitimately maps weak predictions to NEUTRAL.
     *
     * Keep the same chronological five-fold warmup, sample floors, rank requirement, economic comparison and authority
     * graduation. The score and verdict were fixed before the outcome; neither is fitted or relabelled in this test.
     * Only outcomes actually knowable at [asOfMs] may enter. In particular a later four-hour runner label is evaluated
     * once available, never presented as a five-minute result in an earlier replay. Unknown availability is not invented.
     * The legacy [test8366] remains available as a clearly separate diagnostic, not another runtime gate.
     */
    fun test8372(rows: List<Row8366>, costPct: Double, asOfMs: Long, folds: Int = FOLDS_8366): Result8366 {
        val cost = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        val buckets = CortexScoreboard7885.Bucket.values().indices
        val xs = rows.filter {
            it.atMs > 0L && it.atMs <= asOfMs && it.score.isFinite() && it.netPct.isFinite() &&
                it.frozenBucket != null && it.frozenBucket in buckets &&
                it.outcomeAvailableAtMs > it.atMs && it.outcomeAvailableAtMs <= asOfMs
        }.sortedBy { it.atMs }
        val k = folds.coerceIn(2, 10)
        val oosScore = ArrayList<Double>(); val oosNet = ArrayList<Double>()
        val strong = ArrayList<Double>(); val nonStrong = ArrayList<Double>()
        for (i in 1 until k) {
            val first = (i.toLong() * xs.size / k).toInt()
            val end = ((i + 1).toLong() * xs.size / k).toInt()
            if (first <= 0 || end <= first) continue
            for (r in xs.subList(first, end)) {
                oosScore.add(r.score); oosNet.add(r.netPct)
                if (r.frozenBucket == CortexScoreboard7885.Bucket.STRONG.ordinal) strong.add(r.netPct)
                else nonStrong.add(r.netPct)
            }
        }
        return evaluate8366(oosScore, oosNet, strong, nonStrong, cost, "STRONG_VS_NON_STRONG_8372", rows.size - xs.size)
    }

    /** Same evidence test for both cohort selectors; 8372 replaces the cohort semantics, not the proof bar. */
    private fun evaluate8366(
        oosScore: List<Double>, oosNet: List<Double>, top: List<Double>, bottom: List<Double>, cost: Double,
        cohort: String = "RANK_QUANTILES_8366", excludedRows8372: Int = 0,
    ): Result8366 {
        val n = oosNet.size
        val ic = CortexTailEv8324.rankIc8324(oosScore, oosNet)
        val tm = if (top.isEmpty()) Double.NaN else top.average()
        val bm = if (bottom.isEmpty()) Double.NaN else bottom.average()
        val tse = if (top.size < 2) Double.POSITIVE_INFINITY else
            kotlin.math.sqrt(top.sumOf { (it - tm) * (it - tm) } / (top.size - 1) / top.size)
        fun out(v: Verdict8366, fr: Double) = Result8366(v, n, ic, tm, bm, top.size, bottom.size, tse, cost, fr, cohort, excludedRows8372)
        if (n < MIN_OOS_N_8366 || top.size < MIN_BUCKET_N_8366 || bottom.size < MIN_BUCKET_N_8366) return out(Verdict8366.INSUFFICIENT_OOS, 0.0)
        if (!ic.isFinite() || ic <= 0.0) return out(Verdict8366.NO_RANK, 0.0)
        val spread = tm - bm
        if (!(spread > cost)) return out(Verdict8366.SPREAD_BELOW_COST, 0.0)
        if (!(tm > 0.0)) return out(Verdict8366.TOP_NOT_POSITIVE, 0.0)
        return out(Verdict8366.GRANTED, graduated8366(spread, cost))
    }

    /** Pure: the authority fraction a granted OOS spread earns over [costPct]: (spread - cost) / max(cost, 2 points), in [0, 1]. */
    fun graduated8366(spreadPct: Double, costPct: Double): Double {
        if (!spreadPct.isFinite()) return 0.0
        val c = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        val m = spreadPct - c
        if (!(m > 0.0)) return 0.0
        return (m / maxOf(c, MIN_FULL_MARGIN_PCT_8366)).coerceIn(0.0, 1.0)
    }

    /** Pure: do [n] realised live closes with mean [meanPct] revoke the lane's authority? Only on 20+ closes with mean <= 0. */
    fun revokes8366(n: Double, meanPct: Double): Boolean =
        n.isFinite() && n >= REVOKE_MIN_N_8366 && meanPct.isFinite() && meanPct <= 0.0

    /**
     * Pure: the lane's STRONG-side authority — the granted walk-forward fraction, 0 when the lane is UNCALIBRATED
     * ([uncalibrated]) or its realised closes revoke it. Realised closes never raise it, and their absence never lowers it.
     */
    fun authority8366(r: Result8366?, uncalibrated: Boolean, realisedN: Double, realisedMeanPct: Double): Double {
        if (r == null || !r.granted || uncalibrated) return 0.0
        if (revokes8366(realisedN, realisedMeanPct)) return 0.0
        return r.fraction.coerceIn(0.0, 1.0)
    }

    /** Pure: the stable code of what keeps a lane's authority at 0 (or OPEN). */
    fun code8366(r: Result8366?, uncalibrated: Boolean, realisedN: Double, realisedMeanPct: Double): String = when {
        r == null -> "NO_RECORDS"
        uncalibrated -> "UNCALIBRATED"
        !r.granted -> r.verdict.name
        revokes8366(realisedN, realisedMeanPct) -> "REVOKED_REALISED"
        else -> "OPEN"
    }
}
