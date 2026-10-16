package com.lifecyclebot.engine.cortex

/**
 * V5.0.8153 §OBJECTIVE_IS_BANKED_GROWTH — what a strategy, an exit or a Cortex promotion is judged on.
 *
 * The learners compared policies on mean labels and, in places, on hit rate. Hit rate rewards cutting right-tail winners:
 * a take-profit at +20% "wins" on every coin that ever touched +20%, including the 1-in-5 that would have run to +300%,
 * and a book that grades on it walks the bot away from the trades that pay for the losers. The objective here combines,
 * on NET returns (after every cost):
 *   expectancy            mean net return per trade;
 *   SOL growth            mean log growth of equity staking [STAKE_FRACTION_8153] per trade, normalised back to the
 *                         position scale ((e^G - 1) / f) — expectancy after the variance drag a wallet actually feels;
 *   captured runner return net return per trade contributed by closes at or above [RUNNER_PCT_8153] (the right tail);
 *   profit factor         gross wins over gross losses (log, capped), so one huge win cannot hide a bleeding book;
 *   drawdown              the deepest equity drop of the same staked sequence (position scale), as a penalty.
 * Hit rate is reported, never scored. Pure: no Android, no I/O.
 */
object LearningObjective8153 {
    const val RUNNER_PCT_8153 = 50.0
    const val STAKE_FRACTION_8153 = 0.10
    private const val PF_CAP_8153 = 10.0
    private const val PF_FLOOR_8153 = 0.1
    private const val W_RUNNER_8153 = 0.25
    private const val W_PF_8153 = 5.0
    private const val W_DRAWDOWN_8153 = 0.1

    data class Score8153(
        val n: Int,
        val expectancyPct: Double,
        val profitFactor: Double,
        val maxDrawdownPct: Double,
        val runnerPerTradePct: Double,
        val growthPerTradePct: Double,
        val hitRate: Double,
        val score: Double,
    ) {
        fun compact(): String = if (n == 0) "n0" else
            "n$n E=${"%+.1f".format(expectancyPct)} growth=${"%+.1f".format(growthPerTradePct)} runner=${"%+.1f".format(runnerPerTradePct)} " +
                "PF=${"%.2f".format(profitFactor)} DD=${"%.1f".format(maxDrawdownPct)} hit=${"%.0f".format(hitRate * 100)}% score=${"%+.1f".format(score)}"
    }

    /** Pure: the objective of a time-ordered list of net returns in percent (a return below -100% reads as -100%). */
    fun score8153(returnsPct: List<Double>, stakeFraction: Double = STAKE_FRACTION_8153): Score8153 {
        val xs = returnsPct.filter { it.isFinite() }.map { it.coerceAtLeast(-100.0) }
        val n = xs.size
        if (n == 0) return Score8153(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val f = if (stakeFraction.isFinite() && stakeFraction > 0.0 && stakeFraction <= 1.0) stakeFraction else STAKE_FRACTION_8153
        var sum = 0.0; var gains = 0.0; var losses = 0.0; var runner = 0.0; var wins = 0
        var equity = 1.0; var peak = 1.0; var maxDd = 0.0; var logSum = 0.0
        for (x in xs) {
            sum += x
            if (x > 0.0) { gains += x; wins++ } else losses -= x
            if (x >= RUNNER_PCT_8153) runner += x
            val step = 1.0 + f * x / 100.0
            equity *= step
            logSum += kotlin.math.ln(step)
            if (equity > peak) peak = equity
            val dd = (1.0 - equity / peak) * 100.0
            if (dd > maxDd) maxDd = dd
        }
        val pf = when {
            losses <= 0.0 -> if (gains > 0.0) PF_CAP_8153 else 1.0
            else -> (gains / losses).coerceAtMost(PF_CAP_8153)
        }
        val growth = (kotlin.math.exp(logSum / n) - 1.0) * 100.0 / f
        val runnerPerTrade = runner / n
        val ddPosition = maxDd / f
        val score = growth + W_RUNNER_8153 * runnerPerTrade + W_PF_8153 * kotlin.math.ln(pf.coerceIn(PF_FLOOR_8153, PF_CAP_8153)) -
            W_DRAWDOWN_8153 * ddPosition
        return Score8153(n, sum / n, pf, maxDd, runnerPerTrade, growth, wins.toDouble() / n, score)
    }
}

/**
 * V5.0.8153 §WALK_FORWARD_COUNTERFACTUAL — every strategy or exit change is compared out of sample, after costs, on the
 * objective (LearningObjective8153), before it may be adopted.
 *
 * [exitGross8153] replays one recorded price path (gross % from the fill, time-ordered marks) under an exit policy: stop,
 * take-all, trailing give-back once armed, and one partial; every exit lands AT the mark that triggered it (a gap through
 * the stop banks the gap). [counterfactualNet8153] charges the trade's actual round-trip cost plus one extra sell leg per
 * partial. [compareReturns8153] runs a paired walk-forward on time-ordered (baseline, candidate) net returns of the same
 * trades: a fold is tested only when the folds before it already claimed the candidate better (paired mean - SE above the
 * margin); adoption needs the out-of-sample pool to clear the margin, half the tested folds to pass, and the candidate's
 * OBJECTIVE on the out-of-sample trades to beat the baseline's — a higher hit rate that cuts the right tail never adopts.
 * Pure: no Android, no I/O. It changes nothing by itself; callers adopt only on [Result8153.adopt].
 */
object LearningPolicyCompare8153 {
    private const val MIN_TRAIN_8153 = 10
    private const val MIN_OOS_8153 = 10
    const val MARGIN_PCT_8153 = 1.0

    data class ExitPolicy8153(
        val stopPct: Double,
        val takePct: Double = Double.POSITIVE_INFINITY,
        val trailFromPeakPct: Double = Double.NaN,
        val trailArmPct: Double = 0.0,
        val partialAtPct: Double = Double.NaN,
        val partialFraction: Double = 0.0,
        val extraLegCostPct: Double = 0.0,
    )

    /** Pure: the gross return (percent of the whole position) [p] banks on [path]; the last mark closes what is left. */
    fun exitGross8153(path: DoubleArray, p: ExitPolicy8153): Double {
        if (path.isEmpty()) return Double.NaN
        var remaining = 1.0
        var banked = 0.0
        var peak = Double.NEGATIVE_INFINITY
        var partialDone = !(p.partialAtPct.isFinite() && p.partialFraction > 0.0 && p.partialFraction < 1.0)
        var last = Double.NaN
        for (m in path) {
            if (!m.isFinite()) continue
            last = m
            if (!partialDone && m >= p.partialAtPct) {
                banked += p.partialFraction * m
                remaining -= p.partialFraction
                partialDone = true
            }
            if (p.stopPct.isFinite() && p.stopPct > 0.0 && m <= -p.stopPct) return banked + remaining * m
            if (m >= p.takePct) return banked + remaining * m
            if (m > peak) peak = m
            if (p.trailFromPeakPct.isFinite() && p.trailFromPeakPct > 0.0 && peak >= p.trailArmPct && m <= peak - p.trailFromPeakPct) {
                return banked + remaining * m
            }
        }
        return if (last.isFinite()) banked + remaining * last else Double.NaN
    }

    /** Pure: how many partial legs [p] sells on [path] (0 or 1). */
    fun partialLegs8153(path: DoubleArray, p: ExitPolicy8153): Int {
        if (!(p.partialAtPct.isFinite() && p.partialFraction > 0.0 && p.partialFraction < 1.0)) return 0
        for (m in path) {
            if (!m.isFinite()) continue
            if (m >= p.partialAtPct) return 1
            if ((p.stopPct.isFinite() && p.stopPct > 0.0 && m <= -p.stopPct) || m >= p.takePct) return 0
        }
        return 0
    }

    /** Pure: the net return of [path] under [p] after [roundTripCostPct] and one extra sell leg per partial. */
    fun counterfactualNet8153(path: DoubleArray, p: ExitPolicy8153, roundTripCostPct: Double): Double {
        val g = exitGross8153(path, p)
        if (!g.isFinite() || !roundTripCostPct.isFinite()) return Double.NaN
        val extra = if (p.extraLegCostPct.isFinite() && p.extraLegCostPct > 0.0) p.extraLegCostPct * partialLegs8153(path, p) else 0.0
        return g - roundTripCostPct - extra
    }

    enum class Verdict8153 { INSUFFICIENT_DATA, NO_EDGE, OOS_BELOW_MARGIN, OBJECTIVE_WORSE, ADOPT }

    data class Result8153(
        val verdict: Verdict8153,
        val inSample: CortexWalkForward8144.Stat8144,
        val oos: CortexWalkForward8144.Stat8144,
        val foldsTested: Int,
        val foldsPassed: Int,
        val baselineOos: LearningObjective8153.Score8153?,
        val candidateOos: LearningObjective8153.Score8153?,
    ) {
        val adopt: Boolean get() = verdict == Verdict8153.ADOPT
    }

    private fun foldStart(i: Int, n: Int, k: Int): Int = (i.toLong() * n / k).toInt()

    /** Pure: paired walk-forward of [candidate] against [baseline] (net returns of the same trades, oldest first). */
    fun compareReturns8153(
        baseline: List<Double>,
        candidate: List<Double>,
        folds: Int = 5,
        marginPct: Double = MARGIN_PCT_8153,
    ): Result8153 {
        val idx = (0 until minOf(baseline.size, candidate.size)).filter { baseline[it].isFinite() && candidate[it].isFinite() }
        val base = idx.map { baseline[it] }
        val cand = idx.map { candidate[it] }
        val diffs = idx.indices.map { cand[it] - base[it] }
        val ins = CortexWalkForward8144.stat8144(diffs)
        val none = CortexWalkForward8144.Stat8144(0, Double.NaN, Double.POSITIVE_INFINITY)
        if (diffs.size < MIN_TRAIN_8153 + MIN_OOS_8153) return Result8153(Verdict8153.INSUFFICIENT_DATA, ins, none, 0, 0, null, null)
        val k = folds.coerceIn(2, 10)
        val oosIdx = ArrayList<Int>()
        var tested = 0; var passed = 0
        for (i in 1 until k) {
            val s = foldStart(i, diffs.size, k); val e = foldStart(i + 1, diffs.size, k)
            if (s < MIN_TRAIN_8153 || e <= s) continue
            if (!(CortexWalkForward8144.stat8144(diffs.subList(0, s)).lower > marginPct)) continue
            tested++
            if (CortexWalkForward8144.stat8144(diffs.subList(s, e)).mean > marginPct) passed++
            for (j in s until e) oosIdx.add(j)
        }
        val oos = CortexWalkForward8144.stat8144(oosIdx.map { diffs[it] })
        if (tested == 0 || oos.n < MIN_OOS_8153) return Result8153(Verdict8153.NO_EDGE, ins, oos, tested, passed, null, null)
        val baseOos = LearningObjective8153.score8153(oosIdx.map { base[it] })
        val candOos = LearningObjective8153.score8153(oosIdx.map { cand[it] })
        if (!(oos.lower > marginPct) || passed * 2 < tested) return Result8153(Verdict8153.OOS_BELOW_MARGIN, ins, oos, tested, passed, baseOos, candOos)
        if (!(candOos.score > baseOos.score)) return Result8153(Verdict8153.OBJECTIVE_WORSE, ins, oos, tested, passed, baseOos, candOos)
        return Result8153(Verdict8153.ADOPT, ins, oos, tested, passed, baseOos, candOos)
    }

    /** Pure: the exit-policy change [candidate] vs [baseline] replayed on recorded [paths] with each trade's actual cost. */
    fun compareExits8153(
        paths: List<DoubleArray>,
        costsPct: List<Double>,
        baseline: ExitPolicy8153,
        candidate: ExitPolicy8153,
        folds: Int = 5,
        marginPct: Double = MARGIN_PCT_8153,
    ): Result8153 {
        val n = minOf(paths.size, costsPct.size)
        val b = (0 until n).map { counterfactualNet8153(paths[it], baseline, costsPct[it]) }
        val c = (0 until n).map { counterfactualNet8153(paths[it], candidate, costsPct[it]) }
        return compareReturns8153(b, c, folds, marginPct)
    }
}
