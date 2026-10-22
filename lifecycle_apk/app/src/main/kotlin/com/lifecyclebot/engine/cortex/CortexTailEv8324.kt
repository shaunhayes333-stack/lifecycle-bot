package com.lifecyclebot.engine.cortex

/**
 * V5.0.8324 §TAIL_EV — the runner-lane expected value the Cortex, the cheat sheets and admission read.
 *
 * Owner, 5.0.8321: "these look inverted again. check all". Cortex STRONG fired n0/n1 on every lane and its REFUSE bucket
 * held the runners (SHITCOIN REFUSE n10 +1351.6% vs neutral n23 -17.6%; CORE REFUSE n19 +693.9% vs neutral n45 -23.0%).
 *
 * The arithmetic (Cortex7885.assess, 8321): calibrated = laneMean + slope x d - offset. The lane prior is the BOUNDED
 * predictMean8322 (the typical coin: -15..-20% on a tail pond), the executable offset takes 3 points, and each voter's pull is
 * shrunk by 1/(1 + sum of weights) and clamped. So the calibrated edge of a coin with no strong voter is about -18 .. -23:
 *   - STRONG (edge > +3) needs d > +21 .. +26 points: unreachable, so STRONG never fires;
 *   - REFUSE (edge < -2) is the default; the runner-lane exemption needs a fused runner rate >= 0.10, an ABSOLUTE bar a pond
 *     whose runners are ~5% of coins (each worth +744..+1342%) can never clear. Which coin lands in REFUSE vs NEUTRAL is then
 *     decided by voters' runner-rate deviations; a single runner decides the cohort mean (n10, one coin at ~+13,000%).
 *
 * On a runner lane the expected value of a coin is a mixture, not a shrunk bounded mean:
 *   EV = p x E[runner] + (1 - p) x E[non-runner]
 * read from the lane's own graded objective records (CortexGradeBook8144.objectivePct8322: the hold-window revision, raw,
 * executable-net). E[runner] is the raw runner average while the tail is physically plausible
 * (CortexLedger7885.tailPlausible8320), else the per-label capped average (no fabricated tail). p is the lane's measured
 * runner share moved by the coin's fused runner-rate deviation from the lane baseline.
 *
 * Pure: no Android, no I/O.
 */
object CortexTailEv8324 {
    /** Graded objective records a lane needs before its tail EV is evidence (refusal or a STRONG bar). */
    const val MIN_RECORDS_8324 = 20
    /** Recent decision-time EVs a lane needs before its STRONG bar follows its own distribution. */
    const val MIN_WINDOW_8324 = 50
    /** The lane-relative STRONG bar: the top fifth of the lane's recent decision-time EVs. */
    const val STRONG_QUANTILE_8324 = 0.80
    const val RUNNER_PCT_8324 = CortexLedger7885.RUNNER_GROSS_PCT

    data class Pond8324(
        val n: Int,
        val runners: Int,
        /** Measured runner share of the graded records. */
        val pBook: Double,
        /** Runner average (raw when plausible, else per-label capped); NaN without runners. */
        val eRun: Double,
        /** Non-runner average; NaN without non-runners. */
        val eNon: Double,
        /** The pond's mixture mean (same adjusted values). */
        val mean: Double,
        /** Standard error of [mean]. */
        val se: Double,
        /** The runner tail is physically plausible (read raw). */
        val plausible: Boolean,
    ) {
        /** Enough records for the EV to be evidence. */
        val evidence: Boolean get() = n >= MIN_RECORDS_8324 && mean.isFinite() && se.isFinite()
        /** E[run] - E[non-run] once [CortexLedger7885.MIN_TAIL_RUNNERS_8322]+ runners carry it; NaN otherwise. */
        val premium: Double get() =
            if (runners.toDouble() >= CortexLedger7885.MIN_TAIL_RUNNERS_8322 && eRun.isFinite() && eNon.isFinite()) eRun - eNon else Double.NaN
        val compact: String get() =
            "n$n p=${"%.3f".format(pBook)} run=${fmt(eRun)}${if (plausible) "" else "~cap"} non=${fmt(eNon)} ev=${fmt(mean)}±${fmt(se)}"
    }

    internal fun fmt(v: Double): String = if (v.isFinite()) "%+.1f%%".format(v) else "-"

    /**
     * Pure: the pond of [values] (executable-net objective labels, %) each with its per-label plausibility cap [caps] (same
     * size; a missing cap reads +inf). Null when there is nothing to read.
     */
    fun pond8324(values: List<Double>, caps: List<Double>, runnerPct: Double = RUNNER_PCT_8324): Pond8324? {
        val rows = values.indices.mapNotNull { i ->
            val v = values[i]
            if (!v.isFinite()) null else v to (caps.getOrNull(i)?.takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY)
        }
        if (rows.isEmpty()) return null
        var sum = 0.0; var runSum = 0.0; var runCapSum = 0.0; var runners = 0; var nonSum = 0.0; var non = 0
        var minCap = Double.POSITIVE_INFINITY
        for ((v, cap) in rows) {
            sum += v
            if (v >= runnerPct) {
                runners++; runSum += v; runCapSum += if (cap.isFinite()) minOf(v, cap) else v
                if (cap < minCap) minCap = cap
            } else { non++; nonSum += v }
        }
        val n = rows.size
        // The book's plausibility rule (8320): sum per runner within 3 x the cap of the runners' cohorts.
        val plausible = runners > 0 && CortexLedger7885.tailPlausible8320(sum, runners.toDouble(),
            if (minCap.isFinite()) minCap else CortexLedger7885.LABEL_CAP_8187)
        val eRun = if (runners == 0) Double.NaN else if (plausible) runSum / runners else runCapSum / runners
        val eNon = if (non == 0) Double.NaN else nonSum / non
        // Adjusted values: implausible runner labels enter at their cap (the tail is not fabricated, nor dropped).
        val adj = rows.map { (v, cap) -> if (v >= runnerPct && !plausible && cap.isFinite()) minOf(v, cap) else v }
        val mean = adj.average()
        val se = if (n > 1) kotlin.math.sqrt(adj.sumOf { (it - mean) * (it - mean) } / (n - 1) / n) else Double.POSITIVE_INFINITY
        return Pond8324(n, runners, runners.toDouble() / n, eRun, eNon, mean, se, plausible)
    }

    /**
     * Pure: a coin's tail EV on [pond] — its runner probability is the lane's measured share moved by the coin's fused runner
     * rate [pCoin] relative to the fused baseline [pLane]; with no tail premium measured the pond mean answers.
     */
    fun ev8324(pond: Pond8324?, pCoin: Double, pLane: Double): Double {
        if (pond == null || !pond.mean.isFinite()) return Double.NaN
        val prem = pond.premium
        if (!prem.isFinite()) return pond.mean
        val dev = if (pCoin.isFinite() && pLane.isFinite()) pCoin - pLane else 0.0
        val p = (pond.pBook + dev).coerceIn(0.0, 1.0)
        return p * pond.eRun + (1.0 - p) * pond.eNon
    }

    /** Pure: the [q] quantile of [xs] (finite values; NaN when none). */
    fun quantile8324(xs: List<Double>, q: Double): Double {
        val v = xs.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val pos = (q.coerceIn(0.0, 1.0) * (v.size - 1))
        val lo = kotlin.math.floor(pos).toInt(); val hi = kotlin.math.ceil(pos).toInt()
        return v[lo] + (v[hi] - v[lo]) * (pos - lo)
    }

    /**
     * Pure: the lane's STRONG bar — at least [CortexScoreboard7885.STRONG_EDGE_PCT] and the pond mean plus the proof margin
     * (STRONG must beat the lane, not merely the pond), and, once [MIN_WINDOW_8324] recent EVs are known, the top fifth of
     * them (the bar follows the lane's own distribution; a constant +3 on a pond whose bounded prior is -18 never fired).
     */
    fun strongBar8324(recentEvs: List<Double>, pondMean: Double): Double {
        var bar = CortexScoreboard7885.STRONG_EDGE_PCT
        if (pondMean.isFinite()) bar = maxOf(bar, pondMean + CortexScoreboard7885.PROOF_MARGIN_PCT)
        val fin = recentEvs.filter { it.isFinite() }
        if (fin.size >= MIN_WINDOW_8324) quantile8324(fin, STRONG_QUANTILE_8324).takeIf { it.isFinite() }?.let { bar = maxOf(bar, it) }
        return bar
    }

    /**
     * Pure: a runner lane's bucket. REFUSE only on evidence that the coin's EV after cost is CLEARLY negative (pond evidence,
     * EV + pond SE below [CortexScoreboard7885.REFUSE_EDGE_PCT]) and the fused edge agrees; STRONG when the EV clears the lane's
     * [strongBar]; NEUTRAL otherwise (never REFUSE without evidence).
     */
    fun runnerBucket8324(calibrated: Double, ev: Double, pond: Pond8324?, strongBar: Double): CortexScoreboard7885.Bucket {
        if (pond == null || !pond.evidence || !ev.isFinite()) return CortexScoreboard7885.Bucket.NEUTRAL
        if (ev + pond.se < CortexScoreboard7885.REFUSE_EDGE_PCT && calibrated.isFinite() && calibrated < CortexScoreboard7885.REFUSE_EDGE_PCT)
            return CortexScoreboard7885.Bucket.REFUSE
        if (strongBar.isFinite() && ev > strongBar) return CortexScoreboard7885.Bucket.STRONG
        return CortexScoreboard7885.Bucket.NEUTRAL
    }

    /**
     * Pure (loss tests on runner lanes): the points a record of [n] labels with runner share [runnerRate] may be missing because
     * its sample has not yet met the runners the lane pays on. The record's plausible runner share is at most the Laplace
     * upper estimate (runners + 1) / (n + 1), never above the lane's own share [laneRate]; the gap below it is worth [premium]
     * points per unit share. 0 without a measured premium (fewer than 3 plausible runners in the lane) or when the record
     * already carries its runners. 12 labels with no runner on a 5% / +760-point lane: 0.05 x 760 = +38 points (a loss test
     * there was a coin flip: P(0 runners in 12) = 54%); 100 such labels: 0.0099 x 760 = +7.5 points.
     */
    fun missedRunnerPts8324(n: Double, runnerRate: Double, laneRate: Double, premium: Double): Double {
        if (!(n >= 1.0) || !laneRate.isFinite() || laneRate <= 0.0 || !premium.isFinite() || premium <= 0.0) return 0.0
        val r = if (runnerRate.isFinite()) runnerRate.coerceIn(0.0, 1.0) else 0.0
        if (r >= laneRate) return 0.0
        val pUp = ((r * n + 1.0) / (n + 1.0)).coerceAtMost(laneRate)
        return ((pUp - r).coerceAtLeast(0.0) * premium).coerceAtLeast(0.0)
    }

    /** Pure: average ranks (1-based, ties averaged) of [xs]. */
    private fun ranks(xs: List<Double>): DoubleArray {
        val idx = xs.indices.sortedBy { xs[it] }
        val out = DoubleArray(xs.size)
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && xs[idx[j + 1]] == xs[idx[i]]) j++
            val r = (i + j) / 2.0 + 1.0
            for (k in i..j) out[idx[k]] = r
            i = j + 1
        }
        return out
    }

    /** Pure: Spearman rank correlation of [pred] and [y] (finite pairs; NaN under 5 pairs or no spread). */
    fun rankIc8324(pred: List<Double>, y: List<Double>): Double {
        val pairs = pred.indices.filter { it < y.size && pred[it].isFinite() && y[it].isFinite() }
        if (pairs.size < 5) return Double.NaN
        val a = ranks(pairs.map { pred[it] }); val b = ranks(pairs.map { y[it] })
        val ma = a.average(); val mb = b.average()
        var cov = 0.0; var va = 0.0; var vb = 0.0
        for (i in a.indices) { cov += (a[i] - ma) * (b[i] - mb); va += (a[i] - ma) * (a[i] - ma); vb += (b[i] - mb) * (b[i] - mb) }
        return if (va > 0.0 && vb > 0.0) cov / kotlin.math.sqrt(va * vb) else Double.NaN
    }

    /** Pure: [top quintile mean y, bottom quintile mean y] ranked by [pred] (NaN under 10 pairs). */
    fun quintiles8324(pred: List<Double>, y: List<Double>): DoubleArray {
        val pairs = pred.indices.filter { it < y.size && pred[it].isFinite() && y[it].isFinite() }.sortedBy { pred[it] }
        if (pairs.size < 10) return doubleArrayOf(Double.NaN, Double.NaN)
        val q = maxOf(1, pairs.size / 5)
        return doubleArrayOf(pairs.takeLast(q).map { y[it] }.average(), pairs.take(q).map { y[it] }.average())
    }

    /** Pure: the ranking verdict of a lane from its rank IC and quintile spread. */
    fun verdict8324(ic: Double, top: Double, bottom: Double): String = when {
        !ic.isFinite() -> "THIN"
        ic <= -0.10 && top.isFinite() && bottom.isFinite() && top < bottom -> "INVERTED"
        ic >= 0.10 && (!top.isFinite() || !bottom.isFinite() || top > bottom) -> "RANKS"
        else -> "FLAT"
    }

    /**
     * Pure (V5.0.8324): a seat's runner direction on a runner lane — the share of runners among the decisions it placed ABOVE
     * the lane mean against those it placed BELOW (two-proportion z). A voter that measurably puts the runners below (z < -1,
     * 15+ decisions each side, 3+ runners) is anti-runner: 0 (demoted); 0 > z >= -1: half; otherwise 1. Thin sides are not judged.
     */
    fun runnerDirection8324(aboveN: Double, aboveRunners: Double, belowN: Double, belowRunners: Double): Double {
        if (!(aboveN >= 15.0) || !(belowN >= 15.0)) return 1.0
        val ra = aboveRunners.coerceIn(0.0, aboveN); val rb = belowRunners.coerceIn(0.0, belowN)
        if (ra + rb < CortexLedger7885.MIN_TAIL_RUNNERS_8322) return 1.0
        val p = (ra + rb) / (aboveN + belowN)
        val se = kotlin.math.sqrt(p * (1.0 - p) * (1.0 / aboveN + 1.0 / belowN))
        if (!(se > 0.0)) return 1.0
        val z = (ra / aboveN - rb / belowN) / se
        return when { z < -1.0 -> 0.0; z < 0.0 -> 0.5; else -> 1.0 }
    }
}
