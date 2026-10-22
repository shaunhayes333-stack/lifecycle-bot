package com.lifecyclebot.engine.cortex

/**
 * V5.0.8325 §RUN_ODDS — the runner-lane ordering the Cortex ranks and buckets on.
 *
 * 5.0.8323 inversion8324: SHITCOIN ic +0.01, top fifth -3.5% / bottom fifth +400.6% (the runners sat in the bottom fifth),
 * while SWEEP_VOLUME_ACCEL / SWEEP_TX_ACCEL / V3M_MCAP / LAUNCH_READ held +70..+80% skill. Why the fused edge could not use
 * them on a runner lane:
 *   - skill is R² on raw returns, so a seat's weight measures how well it fits a few huge labels, not whether it sorts runners;
 *   - every bin is read through predictMean8322 (the runner-aware upper bound): a bin that holds the runners is clipped
 *     toward the bound, so the voters' highest bins read LOWER than their moderate bins and the order comes out flat/inverted;
 *   - the pull is shrunk by 1/(1 + sum of weights) and clamped to ±max(10, 3 SD), then shrunk toward the bounded lane mean;
 *   - the 8324 tail EV moved the lane's runner share only by the fused runner-rate DEVIATION, shrunk the same way, so every
 *     coin read the pond mean: TAIL_EV_REBUCKET_8324_SHITCOIN_NEUTRAL=436, almost no STRONG.
 *
 * The run / no-run outcome is what a runner lane pays on, and every voter bin already counts it (Stat.runners / Stat.n). Here:
 *   - [runSkill8325]: how much of the run indicator's variance a seat's bins explain (between-bin share, shrunk rates, minus
 *     the share pure noise would explain) — a seat that sorts runners into some bins and not others;
 *   - [pRun8325]: the coin's run probability — the lane's runner share moved, in log-odds, by the skill-weighted average of
 *     the run odds of the bins this coin's voters put it in (the most skilled [TOP_K_8325] seats; averaged, not summed, so
 *     correlated voters are not counted twice).
 * The tail EV is then p(run) x E[run] + (1 - p(run)) x E[non-run] on the lane's own pond (CortexTailEv8324). Ordering only:
 * authority, admission and sizing are unchanged.
 *
 * Pure: no Android, no I/O.
 */
object CortexRunOdds8325 {
    /** Shrinkage of a bin's runner share toward the lane's (labels). */
    internal const val K_8325 = 20.0
    /** A seat's bins must hold this many labels before its run skill is read. */
    internal const val MIN_SEAT_N_8325 = 60.0
    /** The most run-skilled seats a coin's run odds are read from. */
    const val TOP_K_8325 = 8
    /** Total run skill under which the coin's odds are pulled back to the lane's share. */
    internal const val SKILL_FLOOR_8325 = 0.01

    /** Pure: a bin's runner share shrunk toward the lane's [laneRate]. */
    internal fun shrunkRate8325(n: Double, runners: Double, laneRate: Double): Double =
        ((if (runners.isFinite()) runners.coerceIn(0.0, maxOf(n, 0.0)) else 0.0) + K_8325 * laneRate) / (maxOf(n, 0.0) + K_8325)

    /**
     * Pure: the run skill of a seat whose bins hold [ns] labels and [rs] runners on a lane whose runner share is [laneRate]:
     * the between-bin share of the run indicator's variance (shrunk rates) minus what (bins - 1) / N noise explains; 0 when
     * thin, degenerate or no better than noise.
     */
    fun runSkill8325(ns: DoubleArray, rs: DoubleArray, laneRate: Double): Double {
        if (!(laneRate > 0.0 && laneRate < 1.0) || ns.size != rs.size) return 0.0
        var total = 0.0; var used = 0
        for (n in ns) if (n.isFinite() && n > 0.0) { total += n; used++ }
        if (total < MIN_SEAT_N_8325 || used < 2) return 0.0
        var between = 0.0
        for (i in ns.indices) {
            val n = ns[i]
            if (!n.isFinite() || n <= 0.0) continue
            val p = shrunkRate8325(n, rs[i], laneRate)
            between += n * (p - laneRate) * (p - laneRate)
        }
        val eta = between / (laneRate * (1.0 - laneRate) * total)
        val noise = (used - 1).toDouble() / total
        return (eta - noise).coerceAtLeast(0.0).takeIf { it.isFinite() } ?: 0.0
    }

    /** One voter's run read on a coin: its seat's run [skill] and the coin's bin ([binN] labels, [binRunners] runners). */
    class RunVote8325(val skill: Double, val binN: Double, val binRunners: Double)

    private fun logit(p: Double): Double { val q = p.coerceIn(1e-4, 1.0 - 1e-4); return kotlin.math.ln(q / (1.0 - q)) }
    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + kotlin.math.exp(-x))

    /**
     * Pure: the coin's run probability on a lane whose runner share is [laneRate], from its voters' run reads [votes]; NaN when
     * the lane share is unknown or no voter has run skill.
     */
    fun pRun8325(laneRate: Double, votes: List<RunVote8325>): Double {
        if (!(laneRate > 0.0 && laneRate < 1.0)) return Double.NaN
        val top = votes.filter { it.skill > 0.0 && it.skill.isFinite() }.sortedByDescending { it.skill }.take(TOP_K_8325)
        if (top.isEmpty()) return Double.NaN
        val base = logit(laneRate)
        var num = 0.0; var den = 0.0
        for (v in top) {
            num += v.skill * (logit(shrunkRate8325(v.binN, v.binRunners, laneRate)) - base)
            den += v.skill
        }
        return sigmoid(base + num / maxOf(den, SKILL_FLOOR_8325))
    }

    /**
     * Pure: a coin's tail EV on [pond] at run probability [pRun]: p x E[run] + (1 - p) x E[non-run] once the pond measures a
     * premium; NaN when [pRun] or the premium is unknown (the caller falls back to CortexTailEv8324.ev8324).
     */
    fun ev8325(pond: CortexTailEv8324.Pond8324?, pRun: Double): Double {
        if (pond == null || !pRun.isFinite() || !pond.premium.isFinite()) return Double.NaN
        val p = pRun.coerceIn(0.0, 1.0)
        return p * pond.eRun + (1.0 - p) * pond.eNon
    }

    /**
     * Pure (V5.0.8325 §REGIME_NEUTRAL_SCALP_ORDER): the calibrated edge of a scalp lane read around the lane's all-regime prior
     * [laneMeanAll]: the voters' deviation from the regime prior ([rawEdge] - [laneMeanRegime]) times the calibration [slope],
     * added to the all-regime prior. Every coin of one regime used to share the regime prior, so a lane with few seated voters
     * ranked coins by the regime's past mean.
     */
    fun regimeNeutral8325(rawEdge: Double, laneMeanRegime: Double, laneMeanAll: Double, slope: Double): Double =
        laneMeanAll + slope * (rawEdge - laneMeanRegime)

    /**
     * Pure (V5.0.8325 §BUCKET_IS_RANK): STRONG must sit in the top fifth of the lane's own recent ordering reads once
     * [CortexTailEv8324.MIN_WINDOW_8324] are known (CORE 8323: STRONG n11 -23% while its top fifth paid +216%).
     */
    fun strongInTopFifth8325(value: Double, recent: List<Double>): Boolean {
        val fin = recent.filter { it.isFinite() }
        if (fin.size < CortexTailEv8324.MIN_WINDOW_8324) return true
        val q = CortexTailEv8324.quantile8324(fin, CortexTailEv8324.STRONG_QUANTILE_8324)
        return !q.isFinite() || value >= q
    }

    /** A lane's measured ordering must be this many recent rows deep before an INVERTED verdict silences its buckets. */
    internal const val GUARD_MIN_ROWS_8325 = 60

    /**
     * Pure (V5.0.8325): the shadow buckets of a lane whose own recent ordering is measured INVERTED (rank IC <= -0.10, top
     * fifth below bottom fifth, [GUARD_MIN_ROWS_8325]+ rows) read NEUTRAL: an inverted order neither STRONGs nor REFUSEs.
     */
    fun guardedBucket8325(b: CortexScoreboard7885.Bucket, verdict: String?, rows: Int): CortexScoreboard7885.Bucket =
        if (verdict == "INVERTED" && rows >= GUARD_MIN_ROWS_8325) CortexScoreboard7885.Bucket.NEUTRAL else b
}
