package com.lifecyclebot.engine.cortex

/**
 * V5.0.8144 §WALK_FORWARD_PROMOTION — the Cortex's STRONG-side authority is promoted only on out-of-sample evidence.
 *
 * 5.0.8142: MOONSHOT STRONG +30.5% forward against REFUSE +213.2%, and MOONSHOT STRONG realised closes -14.4% (n=6).
 * Graduated authority (CortexScoreboard7885.fractionFor7955) read the whole decayed book at once: an edge that lived only
 * in the sample it was fitted on, or that the REFUSE cohort beat, still counted.
 *
 * Walk-forward on the stored executable grade records (CortexGradeBook8144), oldest first, cut into [FOLDS_8144]
 * contiguous time folds. For every fold k >= 1 the folds before it are the training window: when the training window
 * already claimed the edge (mean - SE > margin) the rule "trade STRONG here" would have been live during fold k, so
 * fold k's records join the out-of-sample pool. Promotion needs:
 *   - [MIN_OOS_N_8144]+ out-of-sample records whose mean - SE clears the margin, and at least half the tested folds
 *     positive past the margin (one lucky fold cannot carry it);
 *   - the STRONG cohort resolved (not a survivor statistic: [MIN_RESOLVED_SHARE_8144] of its decisions got a label);
 *   - STRONG beating every resolved rival cohort (NEUTRAL / REFUSE of the same lane) on the same out-of-sample span —
 *     a lane whose STRONG reads trail a rival is INVERTED_OOS and holds no STRONG authority.
 * The cap only lowers authority, composes with CortexRealisedAuthority8136 by minimum, and re-reads on every call.
 */
object CortexWalkForward8144 {
    private const val FOLDS_8144 = 5
    private const val MIN_TRAIN_N_8144 = 15
    private const val MIN_OOS_N_8144 = 20
    /** V5.0.8187 — the executable STRONG records a lane needs before the walk-forward can reach any verdict past INSUFFICIENT_DATA. */
    const val MIN_SERIES_N_8187 = MIN_TRAIN_N_8144 + MIN_OOS_N_8144
    private const val MIN_RIVAL_N_8144 = 6
    private const val FULL_OOS_N_8144 = 60
    const val MARGIN_PCT_8144 = 2.0
    const val MIN_RESOLVED_SHARE_8144 = 0.7

    data class Stat8144(val n: Int, val mean: Double, val se: Double) {
        val lower: Double get() = if (n > 0 && se.isFinite()) mean - se else Double.NEGATIVE_INFINITY
    }

    enum class Verdict8144 { INSUFFICIENT_DATA, UNRESOLVED_COHORT, NO_EDGE, IN_SAMPLE_ONLY, OOS_BELOW_MARGIN, INVERTED_OOS, VALIDATED }

    data class Result8144(
        val verdict: Verdict8144,
        val inSample: Stat8144,
        val oos: Stat8144,
        val foldsTested: Int,
        val foldsPassed: Int,
        val rivalOosMean: Double,
    ) {
        val validated: Boolean get() = verdict == Verdict8144.VALIDATED
        fun compact(): String = "${verdict.name} oos=n${oos.n}/${fmt(oos.mean)}±${fmt(oos.se)} in=n${inSample.n}/${fmt(inSample.mean)} " +
            "folds=$foldsPassed/$foldsTested${if (rivalOosMean.isFinite()) " rival=${fmt(rivalOosMean)}" else ""}"
        private fun fmt(v: Double) = if (v.isFinite()) "%+.1f".format(v) else "-"
    }

    /** Pure: n, mean and standard error (sample variance) of the finite values; SE is infinite under two values. */
    fun stat8144(xs: List<Double>): Stat8144 {
        var n = 0; var sum = 0.0
        for (x in xs) if (x.isFinite()) { n++; sum += x }
        if (n == 0) return Stat8144(0, Double.NaN, Double.POSITIVE_INFINITY)
        val mean = sum / n
        if (n < 2) return Stat8144(1, mean, Double.POSITIVE_INFINITY)
        var ss = 0.0
        for (x in xs) if (x.isFinite()) { val d = x - mean; ss += d * d }
        return Stat8144(n, mean, kotlin.math.sqrt(ss / (n - 1) / n))
    }

    /** Pure: the Wilson score lower bound of a win rate ([wins] of [n]) at [z] standard errors; 0 when n is 0. */
    fun wilsonLower8144(wins: Int, n: Int, z: Double = 1.0): Double {
        if (n <= 0 || wins < 0 || !z.isFinite()) return 0.0
        val p = wins.coerceAtMost(n).toDouble() / n
        val z2 = z * z
        val centre = p + z2 / (2.0 * n)
        val spread = z * kotlin.math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n))
        return ((centre - spread) / (1.0 + z2 / n)).coerceIn(0.0, 1.0)
    }

    private fun foldStart(i: Int, n: Int, k: Int): Int = (i.toLong() * n / k).toInt()

    /**
     * Pure: walk-forward promotion of a time-ordered STRONG series [strong] (oldest first). [rivals] are the lane's other
     * cohorts (time-ordered values, resolved share); a rival under [MIN_RESOLVED_SHARE_8144] is a survivor statistic and
     * is not compared (it neither blocks nor helps).
     */
    fun promote8144(
        strong: List<Double>,
        rivals: List<Pair<List<Double>, Double>> = emptyList(),
        strongResolvedShare: Double = 1.0,
        folds: Int = FOLDS_8144,
        marginPct: Double = MARGIN_PCT_8144,
    ): Result8144 {
        val xs = strong.filter { it.isFinite() }
        val ins = stat8144(xs)
        val none = Stat8144(0, Double.NaN, Double.POSITIVE_INFINITY)
        if (xs.size < MIN_TRAIN_N_8144 + MIN_OOS_N_8144) return Result8144(Verdict8144.INSUFFICIENT_DATA, ins, none, 0, 0, Double.NaN)
        if (!strongResolvedShare.isFinite() || strongResolvedShare < MIN_RESOLVED_SHARE_8144) {
            return Result8144(Verdict8144.UNRESOLVED_COHORT, ins, none, 0, 0, Double.NaN)
        }
        val k = folds.coerceIn(2, 10)
        val pool = ArrayList<Double>()
        var tested = 0; var passed = 0
        for (i in 1 until k) {
            val s = foldStart(i, xs.size, k); val e = foldStart(i + 1, xs.size, k)
            if (s < MIN_TRAIN_N_8144 || e <= s) continue
            if (!(stat8144(xs.subList(0, s)).lower > marginPct)) continue
            val test = xs.subList(s, e)
            tested++
            if (stat8144(test).mean > marginPct) passed++
            pool.addAll(test)
        }
        val oos = stat8144(pool)
        val inSampleClaims = ins.lower > marginPct
        if (tested == 0 || oos.n < MIN_OOS_N_8144) {
            return Result8144(if (inSampleClaims) Verdict8144.IN_SAMPLE_ONLY else Verdict8144.NO_EDGE, ins, oos, tested, passed, Double.NaN)
        }
        if (!(oos.lower > marginPct) || passed * 2 < tested) {
            return Result8144(if (inSampleClaims) Verdict8144.IN_SAMPLE_ONLY else Verdict8144.OOS_BELOW_MARGIN, ins, oos, tested, passed, Double.NaN)
        }
        // The rivals on the same out-of-sample span (everything after their first fold).
        val rivalMean = rivals.mapNotNull { (values, share) ->
            val f = values.filter { it.isFinite() }
            if (!share.isFinite() || share < MIN_RESOLVED_SHARE_8144 || f.size < MIN_RIVAL_N_8144) null
            else stat8144(f.subList(foldStart(1, f.size, k), f.size)).takeIf { it.n >= MIN_RIVAL_N_8144 }?.mean
        }.maxOrNull() ?: Double.NaN
        if (rivalMean.isFinite() && oos.mean <= rivalMean) return Result8144(Verdict8144.INVERTED_OOS, ins, oos, tested, passed, rivalMean)
        if (rivalMean.isFinite() && !(oos.lower > maxOf(rivalMean, 0.0) + marginPct)) {
            return Result8144(Verdict8144.OOS_BELOW_MARGIN, ins, oos, tested, passed, rivalMean)
        }
        return Result8144(Verdict8144.VALIDATED, ins, oos, tested, passed, rivalMean)
    }

    /** Pure: the authority ceiling a walk-forward result allows: 0 unvalidated, partial below [FULL_OOS_N_8144], else 1. */
    fun authorityCap8144(r: Result8144?): Double = when {
        r == null || !r.validated -> 0.0
        r.oos.n >= FULL_OOS_N_8144 -> 1.0
        else -> CortexScoreboard7885.GRAD_PARTIAL_CAP_7955
    }

    /**
     * Pure: the STRONG-side authority after every gate — the label fraction, its realised cap (CortexRealisedAuthority8136,
     * already <= the label fraction) and the walk-forward cap. Each is a necessary condition, so they compose by minimum:
     * neither can raise what another denies.
     */
    fun compose8144(labelFraction: Double, realisedCapped: Double, walkForwardCap: Double): Double {
        if (!labelFraction.isFinite() || !realisedCapped.isFinite() || !walkForwardCap.isFinite()) return 0.0
        return minOf(labelFraction, realisedCapped, walkForwardCap).coerceIn(0.0, 1.0)
    }
}
