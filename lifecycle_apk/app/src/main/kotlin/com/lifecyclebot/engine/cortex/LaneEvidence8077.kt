package com.lifecyclebot.engine.cortex

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Selection and evidence arithmetic only. Never grants a BUY or changes a risk limit. */
object LaneEvidence8077 {
    data class Setup(val id: String, val expected: Double, val n: Double, val mean: Double, val variance: Double) {
        val se: Double get() = if (n > 1.0 && variance.isFinite() && variance >= 0.0) sqrt(variance / n) else Double.POSITIVE_INFINITY
        val proven: Boolean get() = n >= 30.0 && mean.isFinite() && mean - se > 0.0
        val rank: Double get() = if (n >= 10.0 && se.isFinite()) expected - se * n / (n + 20.0) else expected
    }
    /** Inputs are only setups already fired in this lane; no cross-lane or invented trigger. */
    fun choose(candidates: List<Setup>): Setup? {
        val valid = candidates.filter { it.id.isNotBlank() && it.expected.isFinite() && it.n.isFinite() && it.n >= 0.0 &&
            (it.n == 0.0 || (it.mean.isFinite() && it.variance.isFinite() && it.variance >= 0.0)) }
        val proven = valid.filter { it.proven }
        return (if (proven.isNotEmpty()) proven else valid).maxWithOrNull(
            compareBy<Setup> { it.rank }.thenBy { it.n }.thenBy { it.expected }.thenBy { it.id }
        )
    }
    fun lowWinRateNeedsPivot(trades: Int, lossRate: Double, meanNetPct: Double): Boolean =
        trades >= 50 && lossRate.isFinite() && lossRate > 0.70 && meanNetPct.isFinite() && meanNetPct <= 0.0
    fun skill(scored: Int, model: Double, baseline: Double): Double {
        if (scored < CortexLedger7885.MIN_SCORED || !model.isFinite() || !baseline.isFinite() || model < 0.0 || baseline <= 0.0) return 0.0
        return (1.0 - model / baseline).takeIf { it.isFinite() } ?: 0.0
    }
    /** Validate moments without clipping legitimate large runner returns. */
    fun coherent(n: Double, sum: Double, sumSq: Double, wins: Double, runners: Double): Boolean {
        if (!n.isFinite() || !sum.isFinite() || !sumSq.isFinite() || !wins.isFinite() || !runners.isFinite() || n < 0.0 || sumSq < 0.0) return false
        val tol = 1e-8 * max(1.0, n)
        if (wins < -tol || wins > n + tol || runners < -tol || runners > n + tol) return false
        if (n == 0.0) return sum == 0.0 && sumSq == 0.0 && abs(wins) <= tol && abs(runners) <= tol
        val lower = (sum / n) * sum
        return lower.isFinite() && sumSq + 1e-8 * max(1.0, abs(lower)) >= lower
    }
}
