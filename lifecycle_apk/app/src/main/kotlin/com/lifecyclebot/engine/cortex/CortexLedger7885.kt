package com.lifecyclebot.engine.cortex

/**
 * V5.0.7885 — Cortex §2.7/§2.8: outcome truth and earned authority.
 *
 * Every voter emits a RAW signal on its own scale (a score, a multiplier, a
 * probability, a flag). The ledger does not trust the scale: per
 * (voter, lane) it learns what each raw bin has actually returned, net of
 * cost, on forward labels (admitted AND refused candidates, so learning starts
 * at trade one). A voter's prediction is its bin mean shrunk toward the lane
 * mean by n/(n+K).
 *
 * Authority is earned prequentially: each prediction is scored against the
 * outcome BEFORE that outcome is learned. Skill is the out-of-sample reduction
 * in squared error versus the lane-mean baseline, exponentially decayed so a
 * voter that stops knowing things loses its seat automatically. A voter with
 * no information scores negative (its noisy bins lose to the baseline), so a
 * seat cannot be had by noise.
 *
 * Pure: no Android, no I/O. The runtime object [Cortex7885] owns one instance.
 */
class CortexLedger7885 {
    companion object {
        const val SHRINK_K = 20.0
        const val REGIME_K = 30.0
        const val GLOBAL = "*"
        const val DECAY = 0.998
        const val MIN_SCORED = 60
        const val MIN_SKILL = 0.005
        const val FULL_SKILL = 0.05
        const val Y_MIN = -100.0
        const val Y_MAX = 200.0
        const val RUNNER_GROSS_PCT = 50.0

        /** Pure: bin index of [v] on ascending [edges] (0..edges.size). */
        fun binOf(edges: DoubleArray, v: Double): Int {
            var i = 0
            while (i < edges.size && v >= edges[i]) i++
            return i
        }
    }

    class Stat {
        var n = 0.0; var sum = 0.0; var sumSq = 0.0; var runners = 0.0; var wins = 0.0
        fun mean(): Double = if (n > 0.0) sum / n else 0.0
        fun variance(): Double = if (n > 1.0) ((sumSq / n) - mean() * mean()).coerceAtLeast(0.0) else 0.0
        fun runnerRate(): Double = if (n > 0.0) runners / n else 0.0
        fun winRate(): Double = if (n > 0.0) wins / n else 0.5
        fun add(y: Double, runner: Boolean) { n += 1.0; sum += y; sumSq += y * y; if (runner) runners += 1.0; if (y > 0.0) wins += 1.0 }
        fun scale(k: Double) { n *= k; sum *= k; sumSq *= k; runners *= k; wins *= k }
        /** V5.0.7997 — a label revised on a later checkpoint replaces its earlier value (n unchanged). */
        fun revise(yOld: Double, yNew: Double, runnerOld: Boolean, runnerNew: Boolean) {
            if (n <= 0.0 || !yOld.isFinite() || !yNew.isFinite()) return
            sum += yNew - yOld; sumSq = (sumSq + yNew * yNew - yOld * yOld).coerceAtLeast(0.0)
            if (runnerNew != runnerOld) runners = (runners + if (runnerNew) 1.0 else -1.0).coerceIn(0.0, n)
            val dw = (if (yNew > 0.0) 1.0 else 0.0) - (if (yOld > 0.0) 1.0 else 0.0)
            if (dw != 0.0) wins = (wins + dw).coerceIn(0.0, n)
        }
        fun encode(): String = "$n,$sum,$sumSq,$runners,$wins"
        fun decode(s: String) {
            val f = s.split(','); if (f.size != 4 && f.size != 5) return
            n = f[0].toDoubleOrNull() ?: 0.0; sum = f[1].toDoubleOrNull() ?: 0.0
            sumSq = f[2].toDoubleOrNull() ?: 0.0; runners = f[3].toDoubleOrNull() ?: 0.0
            wins = if (f.size == 5) f[4].toDoubleOrNull() ?: 0.0 else 0.0
        }
    }

    /** One (voter, lane) seat. */
    class Seat(binCount: Int) {
        val bins = Array(binCount) { Stat() }
        var scored = 0
        var sseModel = 0.0
        var sseBase = 0.0
        val above = Stat()   // outcomes when this voter predicted above the lane mean
        val below = Stat()   // ... and below it
        // V5.0.7910 — v1 §2.8 win-probability scoring: decayed Brier and log loss
        // of the voter's P(net > 0) against the lane base rate, prequential.
        var brierModel = 0.0
        var brierBase = 0.0
        var logLossModel = 0.0
        var logLossBase = 0.0

        fun skill(): Double = if (scored >= MIN_SCORED && sseBase > 0.0) 1.0 - sseModel / sseBase else 0.0

        /** Brier skill score of the voter's win probability (0 until MIN_SCORED). */
        fun brierSkill(): Double = if (scored >= MIN_SCORED && brierBase > 0.0) 1.0 - brierModel / brierBase else 0.0

        /** Log-loss skill of the voter's win probability (0 until MIN_SCORED). */
        fun logLossSkill(): Double = if (scored >= MIN_SCORED && logLossBase > 0.0) 1.0 - logLossModel / logLossBase else 0.0

        /** 0..1. Zero until MIN_SCORED out-of-sample scores show positive skill. */
        fun authority(): Double {
            val s = skill()
            // V5.0.7899 — the bar falls with evidence: a no-information voter's
            // prequential skill is noise of order 1/n, so a flat 0.5% seated ~3% of
            // pure-noise voters at n=400 (simulated). 0.5% + 4/n seats ~0.2%.
            if (scored < MIN_SCORED || s <= MIN_SKILL + 4.0 / scored) return 0.0
            return (s / FULL_SKILL).coerceAtMost(1.0) * (scored / (scored + 100.0))
        }

        fun encode(): String = buildString {
            append(scored).append(';').append(sseModel).append(';').append(sseBase).append(';')
            append(above.encode()).append(';').append(below.encode())
            for (b in bins) append(';').append(b.encode())
            append(';').append(brierModel).append(';').append(brierBase).append(';').append(logLossModel).append(';').append(logLossBase)
        }

        fun decode(s: String) {
            val f = s.split(';')
            if (f.size < 5 + bins.size) return
            scored = f[0].toIntOrNull() ?: 0
            sseModel = f[1].toDoubleOrNull() ?: 0.0
            sseBase = f[2].toDoubleOrNull() ?: 0.0
            above.decode(f[3]); below.decode(f[4])
            for (i in bins.indices) bins[i].decode(f[5 + i])
            val x = 5 + bins.size
            if (f.size >= x + 4) {
                brierModel = f[x].toDoubleOrNull() ?: 0.0; brierBase = f[x + 1].toDoubleOrNull() ?: 0.0
                logLossModel = f[x + 2].toDoubleOrNull() ?: 0.0; logLossBase = f[x + 3].toDoubleOrNull() ?: 0.0
            }
        }
    }

    val lanes = HashMap<String, Stat>()
    val seats = HashMap<String, Seat>()      // voterId|lane

    fun lane(lane: String): Stat = lanes.getOrPut(lane) { Stat() }

    fun seat(voterId: String, lane: String, binCount: Int): Seat =
        seats.getOrPut("$voterId|$lane") { Seat(binCount) }.let { if (it.bins.size == binCount) it else Seat(binCount).also { s -> seats["$voterId|$lane"] = s } }

    /**
     * V5.0.7898 — Cortex v4: regime-conditioned calibration (v1 §2.8 cells
     * voter x lane x regime, hierarchically shrunk). The lane prior is the
     * lane's mean IN THIS REGIME shrunk to its overall mean; each voter's bin is
     * read at lane level and then refined by its regime-level bin. A blank
     * regime reproduces the lane-only Cortex exactly.
     */
    private fun laneMean(lane: String, regime: String): Pair<Double, Double> {
        val l = lanes[lane]
        val m = l?.mean() ?: 0.0
        val lr = l?.runnerRate() ?: 0.0
        if (regime.isBlank()) return m to lr
        val r = lanes["$lane@$regime"] ?: return m to lr
        return ((r.sum + REGIME_K * m) / (r.n + REGIME_K)) to ((r.runners + REGIME_K * lr) / (r.n + REGIME_K))
    }

    /** Shrunk prediction of the net return for [raw] on [edges] in [lane] (and [regime]). */
    fun predict(voterId: String, lane: String, edges: DoubleArray, raw: Double, regime: String = ""): Prediction {
        val (m, lr) = laneMean(lane, regime)
        val st = seats["$voterId|$lane"]
        val bin = binOf(edges, raw)
        val b = st?.bins?.getOrNull(bin)
        val n = b?.n ?: 0.0
        // V5.0.7905 — Cortex v10: hierarchical prior (cell -> lane -> global). A
        // thin lane borrows the voter's cross-lane bin deviation, so what a voter
        // learned on every lane informs a new one from its first decisions.
        val prior = m + globalDeviation(voterId, bin, b, lanes[lane])
        var pred = ((b?.sum ?: 0.0) + SHRINK_K * prior) / (n + SHRINK_K)
        var runner = ((b?.runners ?: 0.0) + SHRINK_K * lr) / (n + SHRINK_K)
        if (regime.isNotBlank()) {
            val rb = seats["$voterId|$lane@$regime"]?.bins?.getOrNull(bin)
            if (rb != null && rb.n > 0.0) {
                pred = (rb.sum + REGIME_K * pred) / (rb.n + REGIME_K)
                runner = (rb.runners + REGIME_K * runner) / (rb.n + REGIME_K)
            }
        }
        val laneWin = lanes[lane]?.winRate() ?: 0.5
        val pWin = ((b?.wins ?: 0.0) + SHRINK_K * laneWin) / (n + SHRINK_K)
        return Prediction(pred, runner, st?.authority() ?: 0.0, n, pWin)
    }

    /**
     * Shrunk deviation of [voterId]'s [bin] from the mean of the OTHER lanes
     * (this lane's own cell is subtracted out, so its evidence is not counted twice).
     */
    private fun globalDeviation(voterId: String, bin: Int, laneBin: Stat?, laneStat: Stat?): Double {
        val g = lanes[GLOBAL] ?: return 0.0
        val gb = seats["$voterId|$GLOBAL"]?.bins?.getOrNull(bin) ?: return 0.0
        val n = gb.n - (laneBin?.n ?: 0.0)
        if (n <= 0.5) return 0.0
        val sum = gb.sum - (laneBin?.sum ?: 0.0)
        val gn = g.n - (laneStat?.n ?: 0.0)
        if (gn <= 0.5) return 0.0
        val gMean = (g.sum - (laneStat?.sum ?: 0.0)) / gn
        return (sum - n * gMean) / (n + SHRINK_K)
    }

    data class Prediction(val netPct: Double, val runnerRate: Double, val authority: Double, val binN: Double, val pWin: Double = 0.5)

    /**
     * Grade one decision. [raws] holds each voter's raw value (NaN = abstained)
     * in [voterIds] order with matching [edges]. Scores every voter on the
     * outcome before learning it, then learns it.
     */
    fun grade(lane: String, voterIds: List<String>, edges: List<DoubleArray>, raws: DoubleArray, netPct: Double, grossPct: Double, regime: String = ""): GradeTrace8006? {
        if (!netPct.isFinite()) return null
        val y = netPct.coerceIn(Y_MIN, Y_MAX)
        val runner = grossPct.isFinite() && grossPct >= RUNNER_GROSS_PCT
        val l = lane(lane)
        val base = laneMean(lane, regime).first
        val baseWin8006 = l.winRate()
        val preds8006 = DoubleArray(voterIds.size) { Double.NaN }
        val pWins8006 = DoubleArray(voterIds.size) { Double.NaN }
        val residuals = ArrayList<Pair<String, Double>>()
        for (i in voterIds.indices) {
            val raw = raws.getOrNull(i) ?: continue
            if (!raw.isFinite()) continue
            val st = seat(voterIds[i], lane, edges[i].size + 1)
            val pr = predict(voterIds[i], lane, edges[i], raw, regime)
            val p = pr.netPct
            preds8006[i] = p; pWins8006[i] = pr.pWin
            val won = if (y > 0.0) 1.0 else 0.0
            val baseWin = l.winRate()
            val pw = pr.pWin.coerceIn(0.01, 0.99)
            val bw = baseWin.coerceIn(0.01, 0.99)
            st.brierModel = st.brierModel * DECAY + (won - pw) * (won - pw)
            st.brierBase = st.brierBase * DECAY + (won - bw) * (won - bw)
            st.logLossModel = st.logLossModel * DECAY - (won * kotlin.math.ln(pw) + (1 - won) * kotlin.math.ln(1 - pw))
            st.logLossBase = st.logLossBase * DECAY - (won * kotlin.math.ln(bw) + (1 - won) * kotlin.math.ln(1 - bw))
            // Error co-movement is tracked among seated voters only (fusion only
            // discounts seated voters; ~140 voters would otherwise mean ~10k pairs).
            if (st.authority() > 0.0) residuals.add(voterIds[i] to (y - p))
            st.sseModel = st.sseModel * DECAY + (y - p) * (y - p)
            st.sseBase = st.sseBase * DECAY + (y - base) * (y - base)
            st.scored++
            if (p >= base) st.above.add(y, runner) else st.below.add(y, runner)
            st.bins[binOf(edges[i], raw)].add(y, runner)
            // Regime cells only for seasoned voters (bounds the store).
            if (regime.isNotBlank() && st.scored >= MIN_SCORED / 2) {
                seat(voterIds[i], "$lane@$regime", edges[i].size + 1).bins[binOf(edges[i], raw)].add(y, runner)
            }
        }
        l.add(y, runner)
        if (regime.isNotBlank()) lane("$lane@$regime").add(y, runner)
        // V5.0.7905 — the all-lane cell every voter's bins also feed.
        // V5.0.7931 — cross-asset lanes (crypto universe, Markets) learn in their own
        // lanes only: their outcomes must not move the memecoin all-lane prior.
        if (lane != GLOBAL && !CrossAssetCortex7931.isCrossAssetLane(lane)) {
            for (i in voterIds.indices) {
                val raw = raws.getOrNull(i) ?: continue
                if (!raw.isFinite()) continue
                seat(voterIds[i], GLOBAL, edges[i].size + 1).bins[binOf(edges[i], raw)].add(y, runner)
            }
            lane(GLOBAL).add(y, runner)
        }
        // Error co-movement between seasoned voters (v1 §2.4): voters that are
        // wrong together are one opinion, not two.
        for (a in residuals.indices) for (b in a + 1 until residuals.size) {
            val (ia, ra) = residuals[a]; val (ib, rb) = residuals[b]
            val k = if (ia < ib) "$ia|$ib" else "$ib|$ia"
            val c = pairs.getOrPut(k) { DoubleArray(3) }
            c[0] = c[0] * DECAY + ra * rb; c[1] = c[1] * DECAY + ra * ra; c[2] = c[2] * DECAY + rb * rb
        }
        return GradeTrace8006(preds8006, pWins8006, base, baseWin8006)
    }

    /** V5.0.8006 — what a grade predicted, kept so a later checkpoint can revise the label. */
    class GradeTrace8006(val preds: DoubleArray, val pWins: DoubleArray, val base: Double, val baseWin: Double)

    /**
     * V5.0.8006 — progressive revision. A decision graded at 5 minutes is re-graded when the
     * forward labeler revises its label at 15 m … 24 h (the coin kept running, or died). The
     * revised value REPLACES the booked one everywhere it went — every voter's bin (lane,
     * regime and all-lane cells), the above/below split, the lane prior — and each seat's
     * out-of-sample error is re-scored against the prediction it actually made then. A
     * decision is never counted twice; a voter that called a runner at minute zero now earns
     * the authority it deserved instead of being scored on the first five minutes.
     */
    fun revise8006(
        lane: String, voterIds: List<String>, edges: List<DoubleArray>, raws: DoubleArray, trace: GradeTrace8006,
        oldNet: Double, newNet: Double, oldGross: Double, newGross: Double, regime: String = "",
    ) {
        if (!oldNet.isFinite() || !newNet.isFinite()) return
        val yo = oldNet.coerceIn(Y_MIN, Y_MAX); val yn = newNet.coerceIn(Y_MIN, Y_MAX)
        val ro = oldGross.isFinite() && oldGross >= RUNNER_GROSS_PCT
        val rn = newGross.isFinite() && newGross >= RUNNER_GROSS_PCT
        if (yo == yn && ro == rn) return
        val wo = if (yo > 0.0) 1.0 else 0.0; val wn = if (yn > 0.0) 1.0 else 0.0
        val bw = trace.baseWin.coerceIn(0.01, 0.99)
        val crossAsset = CrossAssetCortex7931.isCrossAssetLane(lane)
        for (i in voterIds.indices) {
            val raw = raws.getOrNull(i) ?: continue
            if (!raw.isFinite()) continue
            val p = trace.preds.getOrNull(i) ?: continue
            if (!p.isFinite()) continue
            val st = seats["${voterIds[i]}|$lane"] ?: continue
            val bin = binOf(edges[i], raw)
            st.bins.getOrNull(bin)?.revise(yo, yn, ro, rn)
            (if (p >= trace.base) st.above else st.below).revise(yo, yn, ro, rn)
            st.sseModel = (st.sseModel + (yn - p) * (yn - p) - (yo - p) * (yo - p)).coerceAtLeast(0.0)
            st.sseBase = (st.sseBase + (yn - trace.base) * (yn - trace.base) - (yo - trace.base) * (yo - trace.base)).coerceAtLeast(0.0)
            if (wn != wo) {
                val pw = (trace.pWins.getOrNull(i) ?: Double.NaN).takeIf { it.isFinite() }?.coerceIn(0.01, 0.99)
                if (pw != null) {
                    st.brierModel = (st.brierModel + (wn - pw) * (wn - pw) - (wo - pw) * (wo - pw)).coerceAtLeast(0.0)
                    st.logLossModel = (st.logLossModel - (wn * kotlin.math.ln(pw) + (1 - wn) * kotlin.math.ln(1 - pw)) +
                        (wo * kotlin.math.ln(pw) + (1 - wo) * kotlin.math.ln(1 - pw))).coerceAtLeast(0.0)
                }
                st.brierBase = (st.brierBase + (wn - bw) * (wn - bw) - (wo - bw) * (wo - bw)).coerceAtLeast(0.0)
                st.logLossBase = (st.logLossBase - (wn * kotlin.math.ln(bw) + (1 - wn) * kotlin.math.ln(1 - bw)) +
                    (wo * kotlin.math.ln(bw) + (1 - wo) * kotlin.math.ln(1 - bw))).coerceAtLeast(0.0)
            }
            if (regime.isNotBlank()) seats["${voterIds[i]}|$lane@$regime"]?.bins?.getOrNull(bin)?.revise(yo, yn, ro, rn)
            if (lane != GLOBAL && !crossAsset) seats["${voterIds[i]}|$GLOBAL"]?.bins?.getOrNull(bin)?.revise(yo, yn, ro, rn)
        }
        lanes[lane]?.revise(yo, yn, ro, rn)
        if (regime.isNotBlank()) lanes["$lane@$regime"]?.revise(yo, yn, ro, rn)
        if (lane != GLOBAL && !crossAsset) lanes[GLOBAL]?.revise(yo, yn, ro, rn)
    }

    /** voterA|voterB (sorted) -> decayed sum(ra*rb), sum(ra^2), sum(rb^2). */
    val pairs = HashMap<String, DoubleArray>()

    /** Pure read: error correlation of two voters, 0 when unknown. */
    private fun errorCorr(a: String, b: String): Double {
        val c = pairs[if (a < b) "$a|$b" else "$b|$a"] ?: return 0.0
        val d = kotlin.math.sqrt(c[1] * c[2])
        return if (d > 0.0) (c[0] / d).coerceIn(-1.0, 1.0) else 0.0
    }

    /** One voter's input to a fusion. */
    data class Vote(val voterId: String, val edges: DoubleArray, val raw: Double, val evidence: Set<String>)

    data class Fused(
        val edgePct: Double,
        val runnerRate: Double,
        val laneMean: Double,
        val laneN: Double,
        val totalWeight: Double,
        val effectiveVoters: Double,
        val dissentPct: Double,
        val top: List<String>,
        val pWin: Double = 0.5,
    )

    /**
     * v1 §2.4 fusion. Each seated voter pulls the lane mean toward its own
     * calibrated prediction with weight authority x independence, where
     * independence divides by the seated voters sharing its evidence and by
     * the error correlation it has with them. The lane mean itself carries
     * weight 1, so with no earned authority the fused edge IS the lane prior.
     */
    fun fuse(lane: String, votes: List<Vote>, regime: String = ""): Fused {
        val l = lanes[lane]
        val (m, lr) = laneMean(lane, regime)
        class W(val id: String, val ev: Set<String>, val a: Double, val d: Double, val rr: Double, val dw: Double = 0.0)
        val seated = ArrayList<W>()
        for (v in votes) {
            if (!v.raw.isFinite()) continue
            val p = predict(v.voterId, lane, v.edges, v.raw, regime)
            if (p.authority <= 0.0) continue
            seated.add(W(v.voterId, v.evidence, p.authority, p.netPct - m, p.runnerRate - lr, p.pWin - (l?.winRate() ?: 0.5)))
        }
        var sw = 0.0; var swd = 0.0; var swr = 0.0; var sw2 = 0.0; var swp = 0.0
        val weights = ArrayList<Pair<W, Double>>()
        for (w in seated) {
            var overlap = 0.0; var corr = 0.0
            for (o in seated) {
                if (o === w) continue
                if (o.ev.any { it in w.ev }) overlap += 1.0
                corr += kotlin.math.max(0.0, errorCorr(w.id, o.id))
            }
            val weight = w.a / ((1.0 + overlap) * (1.0 + corr))
            weights.add(w to weight)
            sw += weight; swd += weight * w.d; swr += weight * w.rr; sw2 += weight * weight; swp += weight * w.dw
        }
        val edge = m + swd / (1.0 + sw)
        val runner = (lr + swr / (1.0 + sw)).coerceIn(0.0, 1.0)
        val meanD = if (sw > 0.0) swd / sw else 0.0
        val dissent = if (sw > 0.0) kotlin.math.sqrt(weights.sumOf { (w, x) -> x * (w.d - meanD) * (w.d - meanD) } / sw) else 0.0
        val top = weights.sortedByDescending { kotlin.math.abs(it.second * it.first.d) }.take(3)
            .map { (w, x) -> "${w.id}${if (w.d >= 0) "+" else ""}${"%.1f".format(w.d)}@${"%.2f".format(x)}" }
        val pWin = ((l?.winRate() ?: 0.5) + swp / (1.0 + sw)).coerceIn(0.0, 1.0)
        return Fused(edge, runner, m, l?.n ?: 0.0, sw, if (sw2 > 0.0) sw * sw / sw2 else 0.0, dissent, top, pWin)
    }

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o ->
        o.put("lanes", org.json.JSONObject().also { j -> lanes.forEach { (k, v) -> j.put(k, v.encode()) } })
        o.put("seats", org.json.JSONObject().also { j -> seats.forEach { (k, v) -> j.put(k, "${v.bins.size}#${v.encode()}") } })
        o.put("pairs", org.json.JSONObject().also { j -> pairs.forEach { (k, v) -> j.put(k, "${v[0]},${v[1]},${v[2]}") } })
    }

    fun decode(o: org.json.JSONObject) {
        o.optJSONObject("lanes")?.let { j -> for (k in j.keys()) lanes[k] = Stat().also { it.decode(j.optString(k)) } }
        o.optJSONObject("seats")?.let { j ->
            for (k in j.keys()) {
                val s = j.optString(k)
                val bc = s.substringBefore('#').toIntOrNull() ?: continue
                seats[k] = Seat(bc).also { it.decode(s.substringAfter('#')) }
            }
        }
        o.optJSONObject("pairs")?.let { j ->
            for (k in j.keys()) {
                val f = j.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
                if (f.size == 3) pairs[k] = doubleArrayOf(f[0], f[1], f[2])
            }
        }
    }
}
