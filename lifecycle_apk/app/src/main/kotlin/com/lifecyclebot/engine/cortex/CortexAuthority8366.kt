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
    // V5.0.8399 §EDGE_ADAPTIVE_RAMP — operator: "its meant to be 15, edge adaptive and intelligent". From 15 OOS graded
    // decisions (5 per bucket) a lane/setup earns a PARTIAL authority that grows with its sample and shrinks with its
    // uncertainty: the top bucket's lower bound (mean - k*SE, k 2.0 at 15 -> 1.0 at 100) must stay positive, the spread must
    // still clear cost, the rank IC must still be positive. Full authority is still the 100/10 bar. 8096 granted partial
    // authority from 15 STRONG grades; 8366-8398 made 100 the first rung, so every lane sat at 0 while learning accumulated.
    const val MIN_RAMP_OOS_N_8399 = 15
    const val MIN_RAMP_BUCKET_N_8399 = 5
    const val RAMP_FLOOR_WEIGHT_8399 = 0.15
    // V5.0.8399 — missing outcomes scale authority down (never disappear, never impute): fraction x coverage/0.7 while at
    // least [MIN_COVERAGE_FOR_ANY_8399] of matured decisions are known; below that there is too little truth to grant.
    const val MIN_COVERAGE_FOR_ANY_8399 = 0.30
    const val TOP_Q_8366 = 0.8
    const val BOTTOM_Q_8366 = 0.2
    const val MIN_FULL_MARGIN_PCT_8366 = 2.0
    const val REVOKE_MIN_N_8366 = 20

    enum class Verdict8366 { INSUFFICIENT_OOS, NO_RANK, SPREAD_BELOW_COST, TOP_NOT_POSITIVE, INCOMPLETE_OUTCOMES, GRANTED, RAMP_8399 }

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
        val dueAtMs8392: Long = 0L,
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
        val matured8392: Int = 0,
        val missing8392: Int = 0,
        val pending8392: Int = 0,
        val strongCoverage8392: Double = 1.0,
        val restCoverage8392: Double = 1.0,
    ) {
        val spread: Double get() = if (topMean.isFinite() && bottomMean.isFinite()) topMean - bottomMean else Double.NaN
        val granted: Boolean get() = (verdict == Verdict8366.GRANTED || verdict == Verdict8366.RAMP_8399) && fraction > 0.0   // V5.0.8399 — a ramp grant is a grant
        fun compact(): String = "${verdict.name} oos=n$oosN ic=${f(ic)} top=${f(topMean)}(n$topN) bot=${f(bottomMean)}(n$bottomN) " +
            "spread=${f(spread)} cost=${f(costPct)} auth=${(kotlin.math.round(fraction * 100.0) / 100.0)}" +
            if (cohort == "RANK_QUANTILES_8366") "" else " cohort=$cohort excluded=$excludedRows8372 matured=$matured8392 missing=$missing8392 pending=$pending8392 coverageS=${f(strongCoverage8392)} coverageRest=${f(restCoverage8392)}"
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
        // Coverage population: every frozen, in-time decision whose outcome is known, still pending, or LOST (netPct NaN).
        // Corrupt rows (infinite outcome, no bucket) are excluded here exactly as above; they are neither missing nor pending.
        val population = rows.filter { it.atMs > 0L && it.atMs <= asOfMs && it.score.isFinite() && it.frozenBucket != null &&
            it.frozenBucket in buckets && !(it.netPct.isInfinite()) }
        val matured = population.filter { (it.dueAtMs8392 > 0L && it.dueAtMs8392 <= asOfMs) ||
            (it.outcomeAvailableAtMs > it.atMs && it.outcomeAvailableAtMs <= asOfMs) }
        fun available(r: Row8366) = r.netPct.isFinite() && r.outcomeAvailableAtMs > r.atMs && r.outcomeAvailableAtMs <= asOfMs
        fun coverage(strongSide: Boolean): Double {
            val group = matured.filter { (it.frozenBucket == CortexScoreboard7885.Bucket.STRONG.ordinal) == strongSide }
            return if (group.isEmpty()) 1.0 else group.count { available(it) }.toDouble() / group.size
        }
        val missing = matured.count { it.netPct.isNaN() }
        val result = evaluate8366(oosScore, oosNet, strong, nonStrong, cost, "STRONG_VS_NON_STRONG_8372", rows.size - xs.size)
            .copy(matured8392=matured.size, missing8392=missing, pending8392=population.size-matured.size,
                strongCoverage8392=coverage(true), restCoverage8392=coverage(false))
        // Same proof, truthful denominator. Never impute missing outcomes as gains or losses.
        // V5.0.8399 — missing outcomes scale the authority down in proportion (coverage/0.7) instead of zeroing it at a
        // 70% cliff: 5.0.8398 sat at ~3% STRONG coverage after capture drops and every lane read authority 0 while 1,042
        // seasoned voters were retained. Under 30% known there is still nothing to grant.
        if (missing == 0) return result
        val minCov = minOf(result.strongCoverage8392, result.restCoverage8392)
        val scale = coverageScale8399(minCov)
        return when {
            scale >= 1.0 -> result
            scale <= 0.0 -> result.copy(verdict=Verdict8366.INCOMPLETE_OUTCOMES, fraction=0.0)
            else -> result.copy(fraction=(result.fraction * scale).coerceIn(0.0, 1.0))
        }
    }

    /** Same maturity rule for either positive or negative predictive authority. */
    fun cohortCovered8392(rows: List<Row8366>, bucket: Int, asOfMs: Long): Boolean {
        val eligible=rows.filter { it.atMs>0 && it.atMs<=asOfMs && it.score.isFinite() && it.frozenBucket != null &&
            ((it.dueAtMs8392>0 && it.dueAtMs8392<=asOfMs) || (it.outcomeAvailableAtMs>it.atMs && it.outcomeAvailableAtMs<=asOfMs)) }
        return listOf(true,false).all { side ->
            val group=eligible.filter { (it.frozenBucket==bucket)==side }
            group.isEmpty() || group.count { it.netPct.isFinite() && it.outcomeAvailableAtMs>it.atMs && it.outcomeAvailableAtMs<=asOfMs }.toDouble()/group.size >= CortexWalkForward8144.MIN_RESOLVED_SHARE_8144
        }
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
        if (n < MIN_RAMP_OOS_N_8399 || top.size < MIN_RAMP_BUCKET_N_8399 || bottom.size < MIN_RAMP_BUCKET_N_8399) return out(Verdict8366.INSUFFICIENT_OOS, 0.0)
        val ramp = n < MIN_OOS_N_8366 || top.size < MIN_BUCKET_N_8366 || bottom.size < MIN_BUCKET_N_8366
        if (!ic.isFinite() || ic <= 0.0) return out(Verdict8366.NO_RANK, 0.0)
        val spread = tm - bm
        if (!(spread > cost)) return out(Verdict8366.SPREAD_BELOW_COST, 0.0)
        if (!(tm > 0.0)) return out(Verdict8366.TOP_NOT_POSITIVE, 0.0)
        if (!ramp) return out(Verdict8366.GRANTED, graduated8366(spread, cost))
        // V5.0.8399 — the ramp: a small sample must show its edge beyond its own noise (lower bound positive), then earns
        // the graduated fraction weighted by how far along the sample is.
        val k = rampK8399(n)
        if (!(tm - k * tse > 0.0)) return out(Verdict8366.TOP_NOT_POSITIVE, 0.0)
        return out(Verdict8366.RAMP_8399, graduated8366(spread, cost) * rampWeight8399(n))
    }

    /** Pure (V5.0.8399): the SE multiple a ramp sample's top bucket must clear: 2.0 at 15 OOS, 1.0 at 100. */
    fun rampK8399(n: Int): Double {
        val t = ((n - MIN_RAMP_OOS_N_8399).toDouble() / (MIN_OOS_N_8366 - MIN_RAMP_OOS_N_8399)).coerceIn(0.0, 1.0)
        return 2.0 - t
    }

    /** Pure (V5.0.8399): the share of the graduated fraction a ramp sample earns: sqrt progress 15 -> 100, floor 0.15, 1.0 at 100. */
    fun rampWeight8399(n: Int): Double {
        if (n >= MIN_OOS_N_8366) return 1.0
        val t = ((n - MIN_RAMP_OOS_N_8399).toDouble() / (MIN_OOS_N_8366 - MIN_RAMP_OOS_N_8399)).coerceIn(0.0, 1.0)
        return (RAMP_FLOOR_WEIGHT_8399 + (1.0 - RAMP_FLOOR_WEIGHT_8399) * kotlin.math.sqrt(t)).coerceIn(RAMP_FLOOR_WEIGHT_8399, 1.0)
    }

    /** Pure (V5.0.8399): how missing matured outcomes scale a fraction: 1 at >= 0.7 coverage, linear to 0.3, nothing below. */
    fun coverageScale8399(minCoverage: Double): Double = when {
        !minCoverage.isFinite() -> 0.0
        minCoverage >= CortexWalkForward8144.MIN_RESOLVED_SHARE_8144 -> 1.0
        minCoverage < MIN_COVERAGE_FOR_ANY_8399 -> 0.0
        else -> (minCoverage / CortexWalkForward8144.MIN_RESOLVED_SHARE_8144).coerceIn(0.0, 1.0)
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
