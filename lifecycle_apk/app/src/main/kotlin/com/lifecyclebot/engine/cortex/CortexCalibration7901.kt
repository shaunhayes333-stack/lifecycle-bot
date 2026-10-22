package com.lifecyclebot.engine.cortex

/**
 * V5.0.7901 — Cortex v7: calibration (v1 §2.8 "calibration slope").
 *
 * The fused edge is the lane prior plus a pooled deviation d. Per lane this
 * keeps a decayed regression of the realised outcome's deviation from the
 * prior on d. An over-confident Cortex (its deviations twice what happens)
 * gets slope 0.5 and its buckets shrink to what it actually earns; an
 * under-confident one is stretched (≤ 1.5x). Before [MIN_N] graded decisions
 * with a non-zero deviation, slope = 1.
 *
 * V5.0.8169 — the slope is the CENTRED least-squares slope cov(d, y−m) / var(d), and each outcome's deviation enters the
 * estimator clipped to ±[RESIDUAL_CLIP_PCT]. 5.0.8168 read slope=1.50 (the clamp) on all eight lanes. The 7901 estimator
 * was the through-origin ratio sum(d·(y−m)) / sum(d²): when the lane prior m trails the outcomes (decayed / regime-shrunk
 * prior) and the fused deviations lean one way, the offset E[y−m]·E[d] is read as slope, and one mis-based label (8166:
 * a single +185,800% five-minute label on five lanes) owns the sums for hundreds of grades. A slope pinned at 1.5 amplified
 * every deviation by half again — more REFUSE and more STRONG reads than the record supports. The clip applies only inside
 * this slope estimator (no label, book or outcome is capped). Rows saved by the old estimator (3 fields) are not read:
 * their sums cannot be centred, so a lane re-learns from slope 1 (identity) for its next [MIN_N] grades.
 */
class CortexCalibration7901 {
    companion object {
        const val MIN_N = 100.0
        const val DECAY = 0.995
        const val MAX_SLOPE = 1.5
        /** V5.0.8169 — the largest outcome deviation (percentage points) one grade may bring to the slope estimate. */
        const val RESIDUAL_CLIP_PCT = 200.0

        /**
         * Pure (V5.0.8169): the centred slope of decayed sums (n, Σd, Σd², Σy, Σd·y), clamped to [0, MAX_SLOPE];
         * 1.0 (identity) below [MIN_N] or with no spread in d.
         */
        fun centredSlope8169(n: Double, sd: Double, sdd: Double, sy: Double, sdy: Double): Double {
            if (!(n >= MIN_N) || !sd.isFinite() || !sdd.isFinite() || !sy.isFinite() || !sdy.isFinite()) return 1.0
            val varD = sdd - sd * sd / n
            if (!(varD > 1e-9 * kotlin.math.max(1.0, sdd))) return 1.0
            val cov = sdy - sd * sy / n
            val s = cov / varD
            return if (s.isFinite()) s.coerceIn(0.0, MAX_SLOPE) else 1.0
        }

        /**
         * V5.0.8291 — 5.0.8286 read slope=1.00 on every lane at n=26..84: calibration WAS learning, but [centredSlope8169]
         * returns identity below [MIN_N] (100 decayed grades; the 0.995 decay caps a lane near 200), so an over-confident
         * Cortex was read at face value for its first hundred grades — and 1.00 passed every "slope >= 0.5" consistency check.
         * From [EARLY_MIN_N_8291] grades the measured slope is phased in linearly to full weight at [MIN_N], and before
         * [MIN_N] it may only SHRINK (an early stretch above 1 is not trusted). Unchanged at and above [MIN_N].
         */
        const val EARLY_MIN_N_8291 = 20.0

        /** V5.0.8322 — decayed grades below which no slope may suspend a lane (8291 could from ~60). */
        const val SUSPEND_MIN_N_8322 = 100.0
        /** V5.0.8322 — the smallest standard deviation (points) of the fused deviations a suspending fit must have. */
        const val MIN_D_SD_PCT_8322 = 0.5

        /** Pure (V5.0.8322): enough grades and enough spread in the deviations for a slope to switch a lane off. */
        fun suspendable8322(n: Double, sd: Double, sdd: Double): Boolean {
            if (!n.isFinite() || n < SUSPEND_MIN_N_8322 || !sd.isFinite() || !sdd.isFinite()) return false
            val varD = sdd / n - (sd / n) * (sd / n)
            return varD.isFinite() && varD >= MIN_D_SD_PCT_8322 * MIN_D_SD_PCT_8322
        }

        /** Pure (V5.0.8291): the slope of decayed sums with the early, shrink-only phase-in. */
        fun earlySlope8291(n: Double, sd: Double, sdd: Double, sy: Double, sdy: Double): Double {
            if (!n.isFinite() || n < EARLY_MIN_N_8291) return 1.0
            if (n >= MIN_N) return centredSlope8169(n, sd, sdd, sy, sdy)
            if (!sd.isFinite() || !sdd.isFinite() || !sy.isFinite() || !sdy.isFinite()) return 1.0
            val varD = sdd - sd * sd / n
            if (!(varD > 1e-9 * kotlin.math.max(1.0, sdd))) return 1.0
            val raw = (sdy - sd * sy / n) / varD
            if (!raw.isFinite()) return 1.0
            val w = ((n - EARLY_MIN_N_8291) / (MIN_N - EARLY_MIN_N_8291)).coerceIn(0.0, 1.0)
            return (1.0 + w * (raw.coerceIn(0.0, 1.0) - 1.0)).coerceIn(0.0, 1.0)
        }
    }

    private class Reg { var n = 0.0; var sd = 0.0; var sdd = 0.0; var sy = 0.0; var sdy = 0.0; var logScale8322 = 0.0 }

    private val lanes = HashMap<String, Reg>()

    fun slope(lane: String): Double {
        val r = lanes[lane] ?: return 1.0
        return earlySlope8291(r.n, r.sd, r.sdd, r.sy, r.sdy)
    }

    /** V5.0.8291 — "id" while a lane reads identity for want of grades, so 1.00 is never mistaken for a measured slope. */
    fun measured8291(lane: String): Boolean = (lanes[lane]?.n ?: 0.0) >= EARLY_MIN_N_8291

    /** The calibrated edge for a raw fused edge around [laneMean]. */
    fun calibrate(lane: String, rawEdge: Double, laneMean: Double): Double =
        laneMean + slope(lane) * (rawEdge - laneMean)

    /**
     * Learn from one graded decision: [rawEdge] and [laneMean] as read at decision time. V5.0.8322 — returns the lane's decay
     * stamp at the time of learning (NaN when nothing was learned), so a later horizon can revise exactly this grade.
     */
    fun learn(lane: String, rawEdge: Double, laneMean: Double, outcome: Double): Double {
        val d = rawEdge - laneMean
        if (!d.isFinite() || !outcome.isFinite() || kotlin.math.abs(d) < 1e-9) return Double.NaN
        val y = (outcome - laneMean).coerceIn(-RESIDUAL_CLIP_PCT, RESIDUAL_CLIP_PCT)
        if (!y.isFinite()) return Double.NaN
        val r = lanes.getOrPut(lane) { Reg() }
        r.n = r.n * DECAY + 1.0
        r.sd = r.sd * DECAY + d
        r.sdd = r.sdd * DECAY + d * d
        r.sy = r.sy * DECAY + y
        r.sdy = r.sdy * DECAY + d * y
        r.logScale8322 += kotlin.math.ln(DECAY)
        return r.logScale8322
    }

    /**
     * V5.0.8322 §LANE_OBJECTIVE — a runner lane is calibrated on the horizon its exits hold to, not on the first five
     * minutes. The grade learned at [stamp] (from [learn]) has its outcome replaced by [newOutcome] at the weight that grade
     * still owns under the decay (n and the edge sums are unchanged: a decision is never counted twice). 8291's MOONSHOT
     * slope 0.00 was fitted on five-minute reads of a lane whose runners pay at 15-60 minutes and beyond.
     */
    fun revise8322(lane: String, rawEdge: Double, laneMean: Double, oldOutcome: Double, newOutcome: Double, stamp: Double): Boolean {
        val r = lanes[lane] ?: return false
        val d = rawEdge - laneMean
        if (!d.isFinite() || kotlin.math.abs(d) < 1e-9 || !oldOutcome.isFinite() || !newOutcome.isFinite() || !stamp.isFinite()) return false
        if (r.logScale8322 > stamp + 1e-9) return false
        val w = kotlin.math.exp(r.logScale8322 - stamp).coerceIn(0.0, 1.0)
        if (!(w > 0.0)) return false
        val yo = (oldOutcome - laneMean).coerceIn(-RESIDUAL_CLIP_PCT, RESIDUAL_CLIP_PCT)
        val yn = (newOutcome - laneMean).coerceIn(-RESIDUAL_CLIP_PCT, RESIDUAL_CLIP_PCT)
        if (!yo.isFinite() || !yn.isFinite()) return false
        r.sy += w * (yn - yo)
        r.sdy += w * d * (yn - yo)
        return true
    }

    /**
     * V5.0.8322 §NO_SUSPENSION_ON_NOISE — may this lane's slope SUSPEND it (refuse power, overrule, size-up, STRONG door)?
     * Only on [SUSPEND_MIN_N_8322]+ decayed grades AND a real spread in the Cortex's deviations (their standard deviation at
     * least [MIN_D_SD_PCT_8322] points): 8291's early shrink-only phase-in could read a slope below 0.5 from ~60 grades, and
     * a fit over near-identical deviations is a ratio of two noises. Below the floor the slope still SHRINKS the calibrated
     * edge ([calibrate]); it just cannot switch the lane off.
     */
    fun suspendable8322(lane: String): Boolean {
        val r = lanes[lane] ?: return false
        return suspendable8322(r.n, r.sd, r.sdd)
    }

    /** V5.0.8322 — is [lane] suspended: slope below [minSlope] on a fit [suspendable8322] allows to switch the lane off. */
    fun suspends8322(lane: String, minSlope: Double): Boolean = slope(lane) < minSlope && suspendable8322(lane)

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o -> lanes.forEach { (k, r) -> o.put(k, "${r.n},${r.sd},${r.sdd},${r.sy},${r.sdy}") } }

    fun decode(o: org.json.JSONObject) {
        for (k in o.keys()) {
            val f = o.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
            // V5.0.8169 — only centred rows (5 fields); a 7901 through-origin row (3 fields) re-learns from identity.
            if (f.size == 5) lanes[k] = Reg().also { it.n = f[0]; it.sd = f[1]; it.sdd = f[2]; it.sy = f[3]; it.sdy = f[4] }
        }
    }

    fun line(): String = lanes.entries.filter { it.value.n >= 1.0 }.sortedByDescending { it.value.n }.take(8)
        .joinToString(",") { "${it.key}:${"%.2f".format(slope(it.key))}${if (measured8291(it.key)) "" else "id"}(n${it.value.n.toInt()}${if (suspendable8322(it.key)) "" else ",noSuspend8322"})" }.ifBlank { "-" }
}
