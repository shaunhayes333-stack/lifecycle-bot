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
        // 5.0.8366's 256-pair ring expires in ~80 minutes on hot lanes; their 4h runner outcome cannot revise rank.
        // 2048 covers > 6h at the observed per-lane label rate, without retaining every 300-voter trace.
        const val ISO_RING_8366 = 2048
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

        /** V5.0.8372 — a flat net-return fit is diagnostic, not a different prediction target or a veto. */
        const val FLAT_NET_PCT_8372 = 0.5
        const val TARGET_FORWARD_NET_8372 = "FORWARD_NET"
        const val TARGET_SHADOW_NET_8372 = "EXECUTABLE_SHADOW"

        /**
         * V5.0.8372 — fit the arithmetic expected NET return in percentage points. The persisted pairs already contain
         * net outcomes: neither a logarithm, a win cap nor another round-trip fee belongs here. The 8366 geometric
         * utility fit remains available separately, but exp(E[clipped log return]) is not expected trading profit.
         *
         * Equal predictor scores are pooled BEFORE PAVA. A deterministic outcome order within each tie also makes
         * the floating-point fit independent of the order in which the labels arrived. One score must have one
         * prediction; letting equal-x rows form separate blocks made the answer depend on which label arrived first.
         */
        fun fitNet8372(scores: List<Double>, netOutcomesPct: List<Double>): NetFit8372 {
            val idx = scores.indices.filter { it < netOutcomesPct.size && scores[it].isFinite() && netOutcomesPct[it].isFinite() }
                .sortedWith(compareBy<Int> { scores[it] }.thenBy { netOutcomesPct[it] })
            val n = idx.size
            if (n == 0) return NetFit8372(DoubleArray(0), DoubleArray(0), 0, Double.NaN)
            val bx = DoubleArray(n); val by = DoubleArray(n); val bw = DoubleArray(n)
            var m = 0
            var at = 0
            while (at < n) {
                val x = scores[idx[at]]
                var y = netOutcomesPct[idx[at]]
                var w = 1.0
                at++
                while (at < n && scores[idx[at]] == x) {
                    y = weightedMean8372(y, w, netOutcomesPct[idx[at]], 1.0)
                    w++
                    at++
                }
                bx[m] = x; by[m] = y; bw[m] = w; m++
                while (m >= 2 && by[m - 2] > by[m - 1]) {
                    val leftW = bw[m - 2]; val rightW = bw[m - 1]
                    by[m - 2] = weightedMean8372(by[m - 2], leftW, by[m - 1], rightW)
                    bx[m - 2] = weightedMean8372(bx[m - 2], leftW, bx[m - 1], rightW)
                    bw[m - 2] = leftW + rightW
                    m--
                }
            }
            val sp = CortexTailEv8324.rankIc8324(idx.map { scores[it] }, idx.map { netOutcomesPct[it] })
            return NetFit8372(bx.copyOf(m), by.copyOf(m), n, sp)
        }

        // Weight before adding rather than summing raw returns: finite large labels stay finite without a win cap.
        private fun weightedMean8372(a: Double, aw: Double, b: Double, bw: Double): Double {
            val total = aw + bw
            return a * (aw / total) + b * (bw / total)
        }

        // Keep the existing wire format, but do not round away the raw score/outcome used when rebuilding either fit.
        private fun num8366(v: Double): String = if (v.isFinite()) v.toString() else "x"

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
    // for the FIT only (the pairs, every label and every book stay raw). V5.0.8372 retains this as a log-utility diagnostic;
    // the per-token profit forecast uses the arithmetic [NetFit8372] from the SAME learned pairs instead.
    // 8366 used a flat/non-ranking log fit to force NEUTRAL buckets. Its historical status helpers remain for callers
    // and diagnostics; 8372 separates a token's expected-net forecast from permission to exercise live authority.
    // Below [ISO_MIN_N_8366] pairs the stored slope remains available. The slope is still learned and shown.
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

        /** Geometric utility (%) at [score]: exp(E[clipped log return]) - 1, NOT arithmetic expected net profit. */
        fun expectedPct8366(score: Double): Double {
            val e = expectedLog8366(score)
            return if (e.isFinite()) (kotlin.math.exp(e) - 1.0) * 100.0 else Double.NaN
        }

        fun compact8366(): String = "n$n sp=${if (spearman.isFinite()) (kotlin.math.round(spearman * 100.0) / 100.0).toString() else "-"} " +
            "blocks=${xs.size}${if (calibrated) "" else if (measured) " UNCALIBRATED" else " thin"}"
    }

    /**
     * V5.0.8372 — a per-token arithmetic net-return map. [valid] is numerical validity, [measured] is sample maturity,
     * and [flat]/[spearman] describe discrimination. These are deliberately separate: a constant positive/negative
     * cohort still has an expected return, although it does not prove that the score ranks individual tokens.
     */
    class NetFit8372(val xs: DoubleArray, val ys: DoubleArray, val n: Int, val spearman: Double) {
        val valid: Boolean = n > 0 && xs.isNotEmpty() && xs.size == ys.size && xs.all { it.isFinite() } &&
            ys.all { it.isFinite() } && (1 until xs.size).all { xs[it] > xs[it - 1] && ys[it] >= ys[it - 1] }
        val measured: Boolean get() = n >= ISO_MIN_N_8366
        val flat: Boolean get() = !valid || !(ys.last() - ys.first() >= FLAT_NET_PCT_8372)
        /** Rank evidence for the EXISTING authority check; it must not rewrite an individual token's verdict. */
        val calibrated: Boolean get() = valid && measured && spearman.isFinite() && spearman > 0.0 && !flat

        /** Expected NET return (%) at [score], interpolated between block centres and constant outside their range. */
        fun expectedNetPct8372(score: Double): Double {
            if (!valid || !score.isFinite()) return Double.NaN
            if (score <= xs[0]) return ys[0]
            val last = xs.size - 1
            if (score >= xs[last]) return ys[last]
            var j = 1
            while (j <= last && xs[j] < score) j++
            val lo = j - 1
            // Normalise only the interpolation coordinates if their difference overflows; labels remain raw.
            val dx = xs[j] - xs[lo]
            val w = if (dx.isFinite()) (score - xs[lo]) / dx else {
                val scale = maxOf(kotlin.math.abs(xs[lo]), kotlin.math.abs(xs[j]))
                (score / scale - xs[lo] / scale) / (xs[j] / scale - xs[lo] / scale)
            }
            return ys[lo] * (1.0 - w) + ys[j] * w
        }

        fun compact8372(): String = "n$n netPct blocks=${xs.size}${if (!valid) " INVALID" else if (!measured) " thin" else if (flat) " flat" else ""}"
    }

    private class Reg { var n = 0.0; var sd = 0.0; var sdd = 0.0; var sy = 0.0; var sdy = 0.0; var logScale8322 = 0.0 }

    private val lanes = HashMap<String, Reg>()
    /** Lane -> raw [score, outcome %, decay stamp, shadow-executable flag] rows, oldest first. */
    private val iso8366 = HashMap<String, ArrayDeque<DoubleArray>>()
    private val isoFits8366 = HashMap<String, IsoFit8366>()
    private val netFits8372 = HashMap<String, Pair<Double, NetFit8372>>()

    /** V5.0.8366 — [lane]'s rank calibration (null before its first scored grade). Cached until the lane learns again. */
    fun isoFit8366(lane: String): IsoFit8366? {
        val ring = iso8366[lane] ?: return null
        isoFits8366[lane]?.let { return it }
        val f = fitIso8366(ring.map { it[0] }, ring.map { it[1] })
        isoFits8366[lane] = f
        return f
    }

    /**
     * Lazily rebuild the fully executable NET forecast from preserved raw outcomes. Forward labels still owe the
     * measured execution gap; a selected simulated exit has already paid it. Charge that gap once, at this read,
     * without adding fictional profit to a losing shadow result or changing its win label anywhere else.
     * The caller must not subtract [executionOffset] again from this fit's prediction.
     */
    fun netFit8372(lane: String, executionOffset: Double = 0.0): NetFit8372? {
        val ring = iso8366[lane] ?: return null
        val offset = executionOffset.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        netFits8372[lane]?.let { (cachedOffset, fit) -> if (cachedOffset == offset) return fit }
        return fitNet8372(ring.map { it[0] }, ring.map { row ->
            row[1] - if (row.getOrElse(3) { 0.0 } == 1.0) 0.0 else offset
        }).also { netFits8372[lane] = offset to it }
    }

    /** V5.0.8372 — the existing authority consistency check, now against the same NET target as token predictions. */
    fun uncalibrated8372(lane: String, executionOffset: Double = 0.0): Boolean {
        val f = netFit8372(lane, executionOffset) ?: return false
        return f.measured && !f.calibrated
    }

    /** V5.0.8372 — identical maturity/slope fallback to [suspends8366], without using log utility as profit evidence. */
    fun suspends8372(lane: String, minSlope: Double, executionOffset: Double = 0.0): Boolean {
        val f = netFit8372(lane, executionOffset)
        return if (f != null && f.measured) !f.calibrated else suspends8322(lane, minSlope)
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

    private fun noteIso8366(lane: String, score: Double, outcomePct: Double, stamp: Double, targetBasis8372: String) {
        if (!score.isFinite() || !outcomePct.isFinite()) return
        val ring = iso8366.getOrPut(lane) { ArrayDeque() }
        ring.addLast(doubleArrayOf(score, outcomePct, stamp, if (targetBasis8372 == TARGET_SHADOW_NET_8372) 1.0 else 0.0))
        while (ring.size > ISO_RING_8366) ring.removeFirst()
        isoFits8366.remove(lane)
        netFits8372.remove(lane)
    }

    private fun reviseIso8366(lane: String, stamp: Double, newOutcomePct: Double, targetBasis8372: String?): Boolean {
        if (!stamp.isFinite() || !newOutcomePct.isFinite()) return false
        val ring = iso8366[lane] ?: return false
        for (row in ring) if (row[2].isFinite() && kotlin.math.abs(row[2] - stamp) < 1e-12) {
            row[1] = newOutcomePct
            if (targetBasis8372 != null) row[3] = if (targetBasis8372 == TARGET_SHADOW_NET_8372) 1.0 else 0.0
            isoFits8366.remove(lane)
            netFits8372.remove(lane)
            return true
        }
        return false
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
    fun learn(lane: String, rawEdge: Double, laneMean: Double, outcome: Double, score8366: Double = Double.NaN,
              targetBasis8372: String = TARGET_FORWARD_NET_8372): Double {
        if (targetBasis8372 != TARGET_FORWARD_NET_8372 && targetBasis8372 != TARGET_SHADOW_NET_8372) return Double.NaN
        val stamp = learnSlope7901(lane, rawEdge, laneMean, outcome)
        noteIso8366(lane, score8366, outcome, stamp, targetBasis8372)  // raw outcome and its cost basis stay separate
        return stamp
    }

    private fun learnSlope7901(lane: String, rawEdge: Double, laneMean: Double, outcome: Double): Double {
        val d = rawEdge - laneMean
        // V5.0.8371: a zero-deviation cold-start prediction is still a real graded decision.
        // It must earn a revision stamp so its early mark can be replaced by the later runner outcome.
        // A zero deviation does not manufacture slope covariance (d*d and d*y both stay zero).
        if (!d.isFinite() || !outcome.isFinite()) return Double.NaN
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
    fun revise8322(lane: String, rawEdge: Double, laneMean: Double, oldOutcome: Double, newOutcome: Double, stamp: Double,
                   targetBasis8372: String? = null): Boolean {
        if (targetBasis8372 != null && targetBasis8372 != TARGET_FORWARD_NET_8372 && targetBasis8372 != TARGET_SHADOW_NET_8372) return false
        val isoRevised8371 = reviseIso8366(lane, stamp, newOutcome, targetBasis8372)
        val r = lanes[lane] ?: return isoRevised8371
        val d = rawEdge - laneMean
        // V5.0.8372: zero deviation means the d*y update is zero, NOT that the centred fit is unchanged.
        // The grade still contributes to sum(y), so its later outcome must revise sy as well as the raw pair.
        if (!d.isFinite() || !oldOutcome.isFinite() || !newOutcome.isFinite() || !stamp.isFinite()) return isoRevised8371
        if (r.logScale8322 > stamp + 1e-9) return isoRevised8371
        val w = kotlin.math.exp(r.logScale8322 - stamp).coerceIn(0.0, 1.0)
        if (!(w > 0.0)) return isoRevised8371
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
            if (cur == null || cur.size < ring.size) {
                iso8366[k] = ArrayDeque(ring.map { it.copyOf() })
                isoFits8366.remove(k)
                netFits8372.remove(k)
            }
        }
        return n
    }

    /** V5.0.8324 — lanes with a fit (diagnostic). */
    fun laneCount8324(): Int = lanes.size

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o ->
        // V5.0.8371: the sixth field preserves the decay-coordinate used by
        // revise8322. Without it every in-flight runner revision after restart
        // has a stamp older than the restored (zero) coordinate and is discarded.
        lanes.forEach { (k, r) -> o.put(k, "${r.n},${r.sd},${r.sdd},${r.sy},${r.sdy},${r.logScale8322}") }
        // Existing forward rows keep their exact three-field format. A selected exit appends an explicit cost basis;
        // its raw return is never translated to a different sign to accommodate the legacy forward-label offset.
        iso8366.forEach { (k, ring) -> o.put(ISO_KEY_PREFIX_8366 + k, ring.joinToString(";") {
            "${num8366(it[0])}:${num8366(it[1])}:${if (it[2].isFinite()) it[2].toString() else "x"}" +
                if (it.getOrElse(3) { 0.0 } == 1.0) ":$TARGET_SHADOW_NET_8372" else ""
        }) }
    }

    fun decode(o: org.json.JSONObject) {
        for (k in o.keys()) {
            if (k.startsWith(ISO_KEY_PREFIX_8366)) {
                val lane = k.removePrefix(ISO_KEY_PREFIX_8366)
                val ring = ArrayDeque<DoubleArray>()
                for (row in o.optString(k).split(';')) {
                    val f = row.split(':')
                    if (f.size !in 3..4) continue
                    val basis = f.getOrElse(3) { TARGET_FORWARD_NET_8372 }
                    if (basis != TARGET_FORWARD_NET_8372 && basis != TARGET_SHADOW_NET_8372) continue
                    val x = f[0].toDoubleOrNull() ?: continue
                    val y = f[1].toDoubleOrNull() ?: continue
                    ring.addLast(doubleArrayOf(x, y, f[2].toDoubleOrNull() ?: Double.NaN, if (basis == TARGET_SHADOW_NET_8372) 1.0 else 0.0))
                }
                while (ring.size > ISO_RING_8366) ring.removeFirst()
                if (lane.isNotBlank() && ring.isNotEmpty()) {
                    iso8366[lane] = ring
                    isoFits8366.remove(lane)
                    netFits8372.remove(lane)
                }
                continue
            }
            val f = o.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
            // V5.0.8371: six-field rows preserve the old grade's decay
            // coordinate. Historical centred five-field rows still load intact;
            // their pre-upgrade stamps cannot be reconstructed retroactively.
            if (f.size == 5 || f.size == 6) lanes[k] = Reg().also {
                it.n = f[0]; it.sd = f[1]; it.sdd = f[2]; it.sy = f[3]; it.sdy = f[4]
                it.logScale8322 = f.getOrNull(5) ?: 0.0
            }
        }
    }

    fun line(): String = lanes.entries.filter { it.value.n >= 1.0 }.sortedByDescending { it.value.n }.take(8)
        .joinToString(",") { "${it.key}:${"%.2f".format(slope(it.key))}${if (measured8291(it.key)) "" else "id"}(n${it.value.n.toInt()}${if (suspendable8322(it.key)) "" else ",noSuspend8322"})" }.ifBlank { "-" }
}
