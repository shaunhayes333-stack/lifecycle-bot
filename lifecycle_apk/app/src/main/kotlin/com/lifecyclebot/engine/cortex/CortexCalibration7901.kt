package com.lifecyclebot.engine.cortex

/**
 * V5.0.7901 — Cortex v7: calibration (v1 §2.8 "calibration slope").
 *
 * The fused edge is the lane prior plus a pooled deviation d. Per lane this
 * keeps a decayed regression of the realised outcome's deviation from the
 * prior on d: slope = sum(d·(y−m)) / sum(d²). An over-confident Cortex (its
 * deviations twice what happens) gets slope 0.5 and its buckets shrink to
 * what it actually earns; an under-confident one is stretched (≤ 1.5x).
 * Before [MIN_N] graded decisions with a non-zero deviation, slope = 1.
 */
class CortexCalibration7901 {
    companion object {
        const val MIN_N = 100.0
        const val DECAY = 0.995
        const val MAX_SLOPE = 1.5
    }

    private class Reg { var n = 0.0; var sdd = 0.0; var sdy = 0.0 }

    private val lanes = HashMap<String, Reg>()

    fun slope(lane: String): Double {
        val r = lanes[lane] ?: return 1.0
        if (r.n < MIN_N || r.sdd <= 0.0) return 1.0
        return (r.sdy / r.sdd).coerceIn(0.0, MAX_SLOPE)
    }

    /** The calibrated edge for a raw fused edge around [laneMean]. */
    fun calibrate(lane: String, rawEdge: Double, laneMean: Double): Double =
        laneMean + slope(lane) * (rawEdge - laneMean)

    /** Learn from one graded decision: [rawEdge] and [laneMean] as read at decision time. */
    fun learn(lane: String, rawEdge: Double, laneMean: Double, outcome: Double) {
        val d = rawEdge - laneMean
        if (!d.isFinite() || !outcome.isFinite() || kotlin.math.abs(d) < 1e-9) return
        val r = lanes.getOrPut(lane) { Reg() }
        r.n = r.n * DECAY + 1.0
        r.sdd = r.sdd * DECAY + d * d
        r.sdy = r.sdy * DECAY + d * (outcome - laneMean)
    }

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o -> lanes.forEach { (k, r) -> o.put(k, "${r.n},${r.sdd},${r.sdy}") } }

    fun decode(o: org.json.JSONObject) {
        for (k in o.keys()) {
            val f = o.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
            if (f.size == 3) lanes[k] = Reg().also { it.n = f[0]; it.sdd = f[1]; it.sdy = f[2] }
        }
    }

    fun line(): String = lanes.entries.filter { it.value.n >= 1.0 }.sortedByDescending { it.value.n }.take(8)
        .joinToString(",") { "${it.key}:${"%.2f".format(slope(it.key))}(n${it.value.n.toInt()})" }.ifBlank { "-" }
}
