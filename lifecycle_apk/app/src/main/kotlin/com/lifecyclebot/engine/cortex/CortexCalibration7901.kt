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

        /** V5.0.8366 — graded (score, outcome) pairs kept per lane for the rank calibration. */
        const val ISO_RING_8366 = 256
        /** V5.0.8366 — pairs before the rank fit replaces the slope (and may declare a lane UNCALIBRATED). */
        const val ISO_MIN_N_8366 = 60
        /** V5.0.8366 — the return clip used inside the FIT only (fraction: -99 % .. +300 %). Stored pairs stay raw. */
        const val WINSOR_LO_8366 = -0.99
        const val WINSOR_HI_8366 = 3.0
        /** V5.0.8366 — a fit whose expected log returns span less than this has no discrimination (flat). */
        const val FLAT_EPS_8366 = 0.005
        /** V5.0.8366 — persisted ring rows ride the same JSON under this key prefix (never a 5-field row: old decoders skip it). */
        const val ISO_KEY_PREFIX_8366 = "ISO8366|"

        /** Pure (V5.0.8366): the winsorised log return of an outcome in percent — ln(1 + clip(r, -0.99, 3.0)). NaN when not finite. */
        fun winsorLog8366(outcomePct: Double): Double {
            if (!outcomePct.isFinite()) return Double.NaN
            val r = (outcomePct / 100.0).coerceIn(WINSOR_LO_8366, WINSOR_HI_8366)
            return kotlin.math.ln(1.0 + r)
        }

        /**
         * Pure (V5.0.8366): the isotonic (non-decreasing) fit of winsorised log outcome on score by pool-adjacent-violators,
         * with the Spearman rank correlation of the raw pairs. Each block is (weighted mean score, mean expected log return).
         */
        fun fitIso8366(scores: List<Double>, outcomesPct: List<Double>): IsoFit8366 {
            val idx = scores.indices.filter { it < outcomesPct.size && scores[it].isFinite() && outcomesPct[it].isFinite() }
                .sortedBy { scores[it] }
            val n = idx.size
            if (n == 0) return IsoFit8366(DoubleArray(0), DoubleArray(0), 0, Double.NaN)
            val bx = DoubleArray(n); val by = DoubleArray(n); val bw = DoubleArray(n)
            var m = 0
            for (i in idx) {
                bx[m] = scores[i]; by[m] = winsorLog8366(outcomesPct[i]); bw[m] = 1.0; m++
                while (m >= 2 && by[m - 2] > by[m - 1]) {
                    val w = bw[m - 2] + bw[m - 1]
                    by[m - 2] = (by[m - 2] * bw[m - 2] + by[m - 1] * bw[m - 1]) / w
                    bx[m - 2] = (bx[m - 2] * bw[m - 2] + bx[m - 1] * bw[m - 1]) / w
                    bw[m - 2] = w
                    m--
                }
            }
            val sp = CortexTailEv8324.rankIc8324(idx.map { scores[it] }, idx.map { winsorLog8366(outcomesPct[it]) })
            return IsoFit8366(bx.copyOf(m), by.copyOf(m), n, sp)
        }

        private fun num8366(v: Double): String = if (v.isFinite()) (kotlin.math.round(v * 1e4) / 1e4).toString() else "x"

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

    // ── V5.0.8366 §RANK_CALIBRATION ──
    //
    // The 7901/8169 slope regressed a tiny shrunk vote deviation on raw outcomes clipped at ±200 points: on fat-tailed
    // labels the ratio pinned at the 1.5 clamp on every lane and a slope >= 0.5 passed as "calibrated". Per lane the Cortex
    // now keeps its last [ISO_RING_8366] graded (score, outcome) pairs and fits an ISOTONIC map (pool-adjacent-violators)
    // from the fused score to the expected winsorised log return ln(1 + r), r clipped to [WINSOR_LO_8366, WINSOR_HI_8366]
    // for the FIT only (the pairs, every label and every book stay raw). The bucket reads the calibrated expected value.
    // A lane whose fit is flat or whose score does not rank the outcomes (Spearman <= 0) is UNCALIBRATED: no authority,
    // NEUTRAL buckets. Below [ISO_MIN_N_8366] pairs the stored slope keeps its role (learning is never reset; the rank fit
    // takes over as new grades arrive). The slope is still learned and shown.
    class IsoFit8366(val xs: DoubleArray, val ys: DoubleArray, val n: Int, val spearman: Double) {
        /** No discrimination: the fitted expected log returns span less than [FLAT_EPS_8366]. */
        val flat: Boolean get() = ys.isEmpty() || !(ys[ys.size - 1] - ys[0] >= FLAT_EPS_8366)
        /** Enough pairs for the fit to decide anything. */
        val measured: Boolean get() = n >= ISO_MIN_N_8366
        /** Measured, ranks the outcomes (Spearman > 0) and not flat. */
        val calibrated: Boolean get() = measured && spearman.isFinite() && spearman > 0.0 && !flat

        /** The expected winsorised log return at [score] (linear between block centres, flat beyond the ends; NaN without a fit). */
        fun expectedLog8366(score: Double): Double {
            if (ys.isEmpty() || !score.isFinite()) return Double.NaN
            if (score <= xs[0]) return ys[0]
            val last = xs.size - 1
            if (score >= xs[last]) return ys[last]
            var j = 1
            while (j <= last && xs[j] < score) j++
            val lo = j - 1
            val dx = xs[j] - xs[lo]
            if (!(dx > 0.0)) return ys[j]
            return ys[lo] + (ys[j] - ys[lo]) * ((score - xs[lo]) / dx)
        }

        /** The calibrated expected return (%) at [score]: exp(E[log]) - 1. */
        fun expectedPct8366(score: Double): Double {
            val e = expectedLog8366(score)
            return if (e.isFinite()) (kotlin.math.exp(e) - 1.0) * 100.0 else Double.NaN
        }

        fun compact8366(): String = "n$n sp=${if (spearman.isFinite()) (kotlin.math.round(spearman * 100.0) / 100.0).toString() else "-"} " +
            "blocks=${xs.size}${if (calibrated) "" else if (measured) " UNCALIBRATED" else " thin"}"
    }

    private class Reg { var n = 0.0; var sd = 0.0; var sdd = 0.0; var sy = 0.0; var sdy = 0.0; var logScale8322 = 0.0 }

    private val lanes = HashMap<String, Reg>()
    /** V5.0.8366 — lane -> its last graded [score, outcome %, decay stamp] rows (oldest first), and the cached fit. */
    private val iso8366 = HashMap<String, ArrayDeque<DoubleArray>>()
    private val isoFits8366 = HashMap<String, IsoFit8366>()

    /** V5.0.8366 — [lane]'s rank calibration (null before its first scored grade). Cached until the lane learns again. */
    fun isoFit8366(lane: String): IsoFit8366? {
        val ring = iso8366[lane] ?: return null
        isoFits8366[lane]?.let { return it }
        val f = fitIso8366(ring.map { it[0] }, ring.map { it[1] })
        isoFits8366[lane] = f
        return f
    }

    /** V5.0.8366 — the rank fit is measured and the lane's score does not rank its outcomes (flat / Spearman <= 0). */
    fun uncalibrated8366(lane: String): Boolean {
        val f = isoFit8366(lane) ?: return false
        return f.measured && !f.calibrated
    }

    /**
     * V5.0.8366 — is [lane] suspended for want of calibration? A measured rank fit decides (UNCALIBRATED = suspended);
     * before it is measured the stored slope rule ([suspends8322]) still applies, so nothing learned is thrown away.
     */
    fun suspends8366(lane: String, minSlope: Double): Boolean {
        val f = isoFit8366(lane)
        return if (f != null && f.measured) !f.calibrated else suspends8322(lane, minSlope)
    }

    private fun noteIso8366(lane: String, score: Double, outcomePct: Double, stamp: Double) {
        if (!score.isFinite() || !outcomePct.isFinite()) return
        val ring = iso8366.getOrPut(lane) { ArrayDeque() }
        ring.addLast(doubleArrayOf(score, outcomePct, stamp))
        while (ring.size > ISO_RING_8366) ring.removeFirst()
        isoFits8366.remove(lane)
    }

    private fun reviseIso8366(lane: String, stamp: Double, newOutcomePct: Double) {
        if (!stamp.isFinite() || !newOutcomePct.isFinite()) return
        val ring = iso8366[lane] ?: return
        for (row in ring) if (row[2].isFinite() && kotlin.math.abs(row[2] - stamp) < 1e-12) { row[1] = newOutcomePct; isoFits8366.remove(lane); return }
    }

    /** V5.0.8366 — per lane: the rank fit's pairs, Spearman and blocks (diagnostic). */
    fun isoLine8366(): String = "rank8366[" + iso8366.keys.sorted().take(10).joinToString(",") { k ->
        "$k:${isoFit8366(k)?.compact8366() ?: "-"}"
    }.ifBlank { "-" } + "]"

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
    fun learn(lane: String, rawEdge: Double, laneMean: Double, outcome: Double, score8366: Double = Double.NaN): Double {
        val stamp = learnSlope7901(lane, rawEdge, laneMean, outcome)
        noteIso8366(lane, score8366, outcome, stamp)  // V5.0.8366 — the rank calibration's pair (raw outcome)
        return stamp
    }

    private fun learnSlope7901(lane: String, rawEdge: Double, laneMean: Double, outcome: Double): Double {
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
        reviseIso8366(lane, stamp, newOutcome)  // V5.0.8366 — the rank calibration follows the lane objective too
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

    /**
     * V5.0.8324 §RESTORE_WITHOUT_RESET — take every lane [other] knows from more decayed grades than this one (a stored book
     * the boot lost). Returns the lanes taken.
     */
    fun mergeFrom8324(other: CortexCalibration7901): Int {
        var n = 0
        for ((k, r) in other.lanes) {
            val cur = lanes[k]
            if (cur == null || cur.n < r.n) { lanes[k] = r; n++ }
        }
        // V5.0.8366 — and every rank-calibration ring the other book holds more pairs of.
        for ((k, ring) in other.iso8366) {
            val cur = iso8366[k]
            if (cur == null || cur.size < ring.size) { iso8366[k] = ArrayDeque(ring.map { it.copyOf() }); isoFits8366.remove(k) }
        }
        return n
    }

    /** V5.0.8324 — lanes with a fit (diagnostic). */
    fun laneCount8324(): Int = lanes.size

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o ->
        lanes.forEach { (k, r) -> o.put(k, "${r.n},${r.sd},${r.sdd},${r.sy},${r.sdy}") }
        // V5.0.8366 — the rank-calibration rings: "score:outcome:stamp;..." (no commas: a pre-8366 decoder skips the row).
        iso8366.forEach { (k, ring) -> o.put(ISO_KEY_PREFIX_8366 + k, ring.joinToString(";") { "${num8366(it[0])}:${num8366(it[1])}:${if (it[2].isFinite()) it[2].toString() else "x"}" }) }
    }

    fun decode(o: org.json.JSONObject) {
        for (k in o.keys()) {
            if (k.startsWith(ISO_KEY_PREFIX_8366)) {
                val lane = k.removePrefix(ISO_KEY_PREFIX_8366)
                val ring = ArrayDeque<DoubleArray>()
                for (row in o.optString(k).split(';')) {
                    val f = row.split(':')
                    if (f.size != 3) continue
                    val x = f[0].toDoubleOrNull() ?: continue
                    val y = f[1].toDoubleOrNull() ?: continue
                    ring.addLast(doubleArrayOf(x, y, f[2].toDoubleOrNull() ?: Double.NaN))
                }
                while (ring.size > ISO_RING_8366) ring.removeFirst()
                if (lane.isNotBlank() && ring.isNotEmpty()) { iso8366[lane] = ring; isoFits8366.remove(lane) }
                continue
            }
            val f = o.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
            // V5.0.8169 — only centred rows (5 fields); a 7901 through-origin row (3 fields) re-learns from identity.
            if (f.size == 5) lanes[k] = Reg().also { it.n = f[0]; it.sd = f[1]; it.sdd = f[2]; it.sy = f[3]; it.sdy = f[4] }
        }
    }

    fun line(): String = lanes.entries.filter { it.value.n >= 1.0 }.sortedByDescending { it.value.n }.take(8)
        .joinToString(",") { "${it.key}:${"%.2f".format(slope(it.key))}${if (measured8291(it.key)) "" else "id"}(n${it.value.n.toInt()}${if (suspendable8322(it.key)) "" else ",noSuspend8322"})" }.ifBlank { "-" }
}
