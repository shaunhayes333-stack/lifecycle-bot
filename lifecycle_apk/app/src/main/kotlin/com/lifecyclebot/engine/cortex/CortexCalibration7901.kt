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
    }

    private class Reg { var n = 0.0; var sd = 0.0; var sdd = 0.0; var sy = 0.0; var sdy = 0.0 }

    private val lanes = HashMap<String, Reg>()

    fun slope(lane: String): Double {
        val r = lanes[lane] ?: return 1.0
        return centredSlope8169(r.n, r.sd, r.sdd, r.sy, r.sdy)
    }

    /** The calibrated edge for a raw fused edge around [laneMean]. */
    fun calibrate(lane: String, rawEdge: Double, laneMean: Double): Double =
        laneMean + slope(lane) * (rawEdge - laneMean)

    /** Learn from one graded decision: [rawEdge] and [laneMean] as read at decision time. */
    fun learn(lane: String, rawEdge: Double, laneMean: Double, outcome: Double) {
        val d = rawEdge - laneMean
        if (!d.isFinite() || !outcome.isFinite() || kotlin.math.abs(d) < 1e-9) return
        val y = (outcome - laneMean).coerceIn(-RESIDUAL_CLIP_PCT, RESIDUAL_CLIP_PCT)
        if (!y.isFinite()) return
        val r = lanes.getOrPut(lane) { Reg() }
        r.n = r.n * DECAY + 1.0
        r.sd = r.sd * DECAY + d
        r.sdd = r.sdd * DECAY + d * d
        r.sy = r.sy * DECAY + y
        r.sdy = r.sdy * DECAY + d * y
    }

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o -> lanes.forEach { (k, r) -> o.put(k, "${r.n},${r.sd},${r.sdd},${r.sy},${r.sdy}") } }

    fun decode(o: org.json.JSONObject) {
        for (k in o.keys()) {
            val f = o.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
            // V5.0.8169 — only centred rows (5 fields); a 7901 through-origin row (3 fields) re-learns from identity.
            if (f.size == 5) lanes[k] = Reg().also { it.n = f[0]; it.sd = f[1]; it.sdd = f[2]; it.sy = f[3]; it.sdy = f[4] }
        }
    }

    fun line(): String = lanes.entries.filter { it.value.n >= 1.0 }.sortedByDescending { it.value.n }.take(8)
        .joinToString(",") { "${it.key}:${"%.2f".format(slope(it.key))}(n${it.value.n.toInt()})" }.ifBlank { "-" }
}
