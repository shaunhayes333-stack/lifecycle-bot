package com.lifecyclebot.engine.cortex

/**
 * 8269 — a REFUSE verdict may block real money only if the exact executable,
 * on-time, first-in-episode forward labels prove that refusing works. The old
 * decayed bucket aggregate includes pre-8146 repetitions and cannot identify
 * independent out-of-sample events. It remains useful as shadow context, not a
 * source of live blocking authority.
 *
 * This read is independent of the STRONG-side sizing/overrule power. It never
 * lifts safety, route, quote or actual-cost protections.
 */
object CortexRefusalTruth8269 {
    private const val MIN_RESOLVED = CortexWalkForward8144.MIN_RESOLVED_SHARE_8144
    private const val ABSOLUTE_MARGIN_PCT = CortexScoreboard7885.PROOF_MARGIN_PCT

    /**
     * Chronological training + holdout proof; the holdout starts after the
     * training decisions. No look-ahead and no redisplayed in-sample legacy sum.
     * Rival decisions must occur in the same calendar holdout before claiming
     * relative superiority.
     */
    fun proven8269(
        refused: List<CortexWalkForward8144.Timed8144>,
        rivals: List<CortexWalkForward8144.Timed8144>,
        resolvedShare: Double,
        minN: Int,
        runnerLane: Boolean = false,
        runnerRate: Double = 0.0,
        relative: Boolean = false,
    ): Boolean {
        if (!resolvedShare.isFinite() || resolvedShare < MIN_RESOLVED) return false
        if (runnerLane && runnerRate >= CortexScoreboard7885.TAIL_RUNNER_RATE) return false
        val xs = refused.filter { it.atMs > 0L && it.netPct.isFinite() }.sortedBy { it.atMs }
        if (xs.size < minN || minN <= 0) return false
        val minTrain = if (relative) 6 else 15
        val minHoldout = if (relative) 4 else 8
        val cut = maxOf(minTrain, (xs.size * 0.6).toInt())
        if (xs.size - cut < minHoldout) return false
        // V5.0.8322 — the training window sees a runner lane's hold-window revision only once it was booked before the
        // holdout starts; before that, its first label (identical to 8269 for every record without a revision).
        val holdoutStart8322 = xs[cut].atMs
        val training = CortexWalkForward8144.stat8144(xs.take(cut).map { if (it.availableAtMs <= holdoutStart8322) it.netPct else it.firstPct })
        val heldOut = CortexWalkForward8144.stat8144(xs.drop(cut).map { it.netPct })
        if (!training.se.isFinite() || !heldOut.se.isFinite() ||
            training.mean + training.se >= -ABSOLUTE_MARGIN_PCT ||
            heldOut.mean + heldOut.se >= -ABSOLUTE_MARGIN_PCT) return false

        // Only compare to NEUTRAL / STRONG labels that were actually knowable
        // during the REFUSE holdout, not to a different market regime.
        val start = xs[cut].atMs
        val end = xs.last().atMs
        val sameWindow = rivals.filter { it.netPct.isFinite() && it.atMs in start..end }
        if (relative && sameWindow.size < 6) return false
        if (sameWindow.size >= 6) {
            val rest = CortexWalkForward8144.stat8144(sameWindow.map { it.netPct })
            if (!rest.se.isFinite() ||
                !(heldOut.mean + heldOut.se < rest.mean - rest.se - ABSOLUTE_MARGIN_PCT)) return false
        }
        return true
    }
}
