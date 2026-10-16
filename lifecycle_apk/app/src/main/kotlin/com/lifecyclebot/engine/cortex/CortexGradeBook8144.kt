package com.lifecyclebot.engine.cortex

/**
 * V5.0.8144 §GRADE_RECORDS — one stored record per graded Cortex decision and per Cortex-bound trade.
 *
 * The bucket / setup books (CortexScoreboard7885, jointBook7961) are decayed sums: they cannot be split in time, so no
 * out-of-sample test could be run on them, and they mixed what was observed with what could have been banked. Each record
 * here keeps apart:
 *   PREDICTION   the model's verdict at the decision (bucket) and its calibrated edge — never overwritten by an outcome;
 *   OBSERVED     the forward label as the labeler books it (net and gross, every progressive revision, any horizon);
 *   EXECUTABLE   the same label only for an executable, first-in-episode decision (CortexLabelHygiene8144), revised only
 *                inside the lane's hold horizon, after the unmeasured-latency haircut — NaN otherwise;
 *   REALISED     (trade records) the whole-position close, filed under the strategy that OWNED the trade
 *                (CortexOutcomeRoute8144) — a transaction outcome, never a label.
 * The grade key is exact: lane | setup | regime | entry timing band | execution-cost band | token cohort (cap band).
 * Pure: no Android, no I/O; the caller holds the Cortex lock.
 */
class CortexGradeRecord8144(
    val kind: Char,               // 'D' decision (forward label), 'T' trade (realised close)
    val id: String,               // decision: "mint|LANE|atMs"; trade: positionId
    val episode: String,          // "mint|LANE"
    val atMs: Long,
    val lane: String,
    val setup: String,
    val regime: String,
    val timing: String,
    val costBand: String,
    val cohort: String,
    val bucket: Int,
    val predictedEdgePct: Double,
    val admitted: Boolean,
    val executable: Boolean,
    val repeat: Boolean,
) {
    var observedNetPct: Double = Double.NaN
    var observedGrossPct: Double = Double.NaN
    var executablePct: Double = Double.NaN
    var lost: Boolean = false
    var realisedPct: Double = Double.NaN
    var ownerLane: String = ""
    var ownerSetup: String = ""
    var attribution: String = ""
    /** Trade records: the account mode of the position (PAPER / LIVE); realised books never mix modes. */
    var mode: String = ""
    /**
     * V5.0.8153 — decision records: the label's first price arrived after the fill-latency re-base window, so its entry is
     * the unbuyable decision price (LearningEntryTruth8153). Observed only: never an executable value, unresolved in its cohort.
     */
    var lateEntry8153: Boolean = false

    /** May this decision's label grade authority (executable, first of its episode)? */
    val graded: Boolean get() = kind == 'D' && executable && !repeat

    fun key(): String = "$lane|$setup|$regime|$timing|$costBand|$cohort"
}

class CortexGradeBook8144(private val maxDecisions: Int = 3_000, private val maxTrades: Int = 600) {
    private val decisions = ArrayList<CortexGradeRecord8144>()
    private val byId = HashMap<String, CortexGradeRecord8144>()
    private val lastGraded = HashMap<String, Long>()
    private val trades = LinkedHashMap<String, CortexGradeRecord8144>()
    var revision: Long = 0L
        private set

    fun size(): Int = decisions.size
    fun tradeCount(): Int = trades.size
    fun decision(id: String): CortexGradeRecord8144? = byId[id]
    fun trade(positionId: String): CortexGradeRecord8144? = trades[positionId]

    /** The time of the last graded (executable, first-in-episode, labelled) decision of this coin in this lane. */
    fun lastGradedAt(episode: String): Long? = lastGraded[episode]

    fun addDecision(r: CortexGradeRecord8144): Boolean {
        if (r.kind != 'D' || r.id.isBlank() || byId.containsKey(r.id)) return false
        decisions.add(r); byId[r.id] = r
        noteGraded(r)
        if (decisions.size > maxDecisions) {
            val drop = decisions.size - maxDecisions
            for (i in 0 until drop) byId.remove(decisions[i].id)
            decisions.subList(0, drop).clear()
        }
        if (lastGraded.size > maxDecisions * 2) {
            val cutoff = (decisions.firstOrNull()?.atMs ?: 0L) - CortexLabelHygiene8144.EPISODE_MS_8144
            lastGraded.entries.removeIf { it.value < cutoff }
        }
        revision++
        return true
    }

    private fun noteGraded(r: CortexGradeRecord8144) {
        if (!r.graded || r.lost || !r.observedNetPct.isFinite()) return
        val prior = lastGraded[r.episode]
        if (prior == null || r.atMs > prior) lastGraded[r.episode] = r.atMs
    }

    /** A label for decision [id] (first read or a later horizon). [executableAllowed] gates the executable value. */
    fun label(id: String, netPct: Double, grossPct: Double, executableValue: Double, executableAllowed: Boolean): Boolean {
        val r = byId[id] ?: return false
        if (!netPct.isFinite()) return false
        r.observedNetPct = netPct; r.observedGrossPct = grossPct; r.lost = false
        if (r.graded && executableAllowed && executableValue.isFinite() && !r.lateEntry8153) r.executablePct = executableValue
        noteGraded(r)
        revision++
        return true
    }

    /** V5.0.8153 — decision [id]'s label started from a late first price: its executable value is withdrawn for good. */
    fun markLateEntry8153(id: String): Boolean {
        val r = byId[id] ?: return false
        if (r.kind != 'D') return false
        r.lateEntry8153 = true; r.executablePct = Double.NaN
        revision++
        return true
    }

    fun markLost(id: String): Boolean {
        val r = byId[id] ?: return false
        if (r.observedNetPct.isFinite() || r.lost) return false
        r.lost = true; revision++
        return true
    }

    fun openTrade(r: CortexGradeRecord8144): Boolean {
        if (r.kind != 'T' || r.id.isBlank() || trades.containsKey(r.id)) return false
        trades[r.id] = r
        if (trades.size > maxTrades) trades.keys.firstOrNull()?.let { trades.remove(it) }
        revision++
        return true
    }

    /** The close of trade [positionId], routed to its owner; null when no trade record was bound to the position. */
    fun closeTrade(positionId: String, owner: CortexOutcomeRoute8144.Owner8144, realisedPct: Double): CortexGradeRecord8144? {
        val r = trades[positionId] ?: return null
        if (r.realisedPct.isFinite() || !realisedPct.isFinite()) return null
        r.realisedPct = realisedPct
        r.ownerLane = owner.lane
        r.ownerSetup = if (owner.trainsStrategy) owner.setup else ""
        r.attribution = owner.attribution.name
        revision++
        return r
    }

    /** Executable values of graded decisions in [lane] (and [setup] / [bucket] when given), oldest first. */
    fun executableSeries(lane: String, setup: String?, bucket: Int?): List<Double> =
        decisions.filter { it.graded && !it.lost && it.lane == lane && (bucket == null || it.bucket == bucket) && (setup == null || it.setup == setup) && it.executablePct.isFinite() }
            .sortedBy { it.atMs }.map { it.executablePct }

    /** Observed values of every labelled decision in [lane] for [bucket], hygiene ignored (the raw cohort). */
    fun observedSeries(lane: String, bucket: Int): List<Double> =
        decisions.filter { it.kind == 'D' && !it.lost && it.lane == lane && it.bucket == bucket && it.observedNetPct.isFinite() }
            .sortedBy { it.atMs }.map { it.observedNetPct }

    /** Labelled / (labelled + lost) over the executable first-of-episode decisions of [lane] x [bucket]; 1 when none. */
    fun resolvedShare(lane: String, bucket: Int, setup: String? = null): Double {
        var ok = 0; var lost = 0
        for (r in decisions) {
            if (r.kind != 'D' || !r.executable || r.repeat || r.lane != lane || r.bucket != bucket || (setup != null && r.setup != setup)) continue
            if (r.lost || r.lateEntry8153) lost++ else if (r.observedNetPct.isFinite()) ok++  // V5.0.8153 — a late-priced label resolves nothing
        }
        return if (ok + lost == 0) 1.0 else ok.toDouble() / (ok + lost)
    }

    /**
     * Realised closes OWNED by [lane] (and [setup] when given — attributed trades only), [bucket] / [mode] when given,
     * oldest first.
     */
    fun realisedSeries(lane: String, setup: String?, bucket: Int?, mode: String? = null): List<Double> =
        trades.values.filter {
            it.realisedPct.isFinite() && it.ownerLane == lane && (setup == null || it.ownerSetup == setup) &&
                (bucket == null || it.bucket == bucket) && (mode == null || it.mode.equals(mode, true))
        }.sortedBy { it.atMs }.map { it.realisedPct }

    data class Cell8144(val key: String, val bucket: Int, val n: Int, val mean: Double, val se: Double, val realisedN: Int, val realisedMean: Double)

    /** Executable stats per exact grade key x bucket (n >= [minN]), with the realised closes of the same key. */
    fun cells(minN: Int): List<Cell8144> {
        val realised = trades.values.filter { it.realisedPct.isFinite() && it.ownerSetup.isNotBlank() }
            .groupBy { "${it.ownerLane}|${it.ownerSetup}|${it.regime}|${it.timing}|${it.costBand}|${it.cohort}#${it.bucket}" }
        return decisions.filter { it.graded && !it.lost && it.executablePct.isFinite() }.groupBy { it.key() + "#" + it.bucket }
            .mapNotNull { (k, rs) ->
                if (rs.size < minN) null else {
                    val s = CortexWalkForward8144.stat8144(rs.map { it.executablePct })
                    val rr = CortexWalkForward8144.stat8144(realised[k].orEmpty().map { it.realisedPct })
                    Cell8144(k.substringBefore('#'), rs.first().bucket, s.n, s.mean, s.se, rr.n, rr.mean)
                }
            }
    }

    // ── encoding (control separators never occur in cleaned text) ──
    private fun clean(s: String): String = s.filterNot { it == FS || it == RS }
    private fun num(v: Double): String = if (v.isFinite()) "%.4f".format(java.util.Locale.US, v) else ""
    private fun dbl(s: String?): Double = s?.toDoubleOrNull() ?: Double.NaN

    private fun encodeOne(r: CortexGradeRecord8144): String = listOf(
        r.kind.toString(), clean(r.id), clean(r.episode), r.atMs.toString(), clean(r.lane), clean(r.setup), clean(r.regime),
        clean(r.timing), clean(r.costBand), clean(r.cohort), r.bucket.toString(), num(r.predictedEdgePct),
        if (r.admitted) "1" else "0", if (r.executable) "1" else "0", if (r.repeat) "1" else "0",
        num(r.observedNetPct), num(r.observedGrossPct), num(r.executablePct), if (r.lost) "1" else "0",
        num(r.realisedPct), clean(r.ownerLane), clean(r.ownerSetup), clean(r.attribution), clean(r.mode),
        if (r.lateEntry8153) "1" else "0",
    ).joinToString(FS.toString())

    fun encode(): String = (decisions + trades.values).joinToString(RS.toString()) { encodeOne(it) }

    /** Restores an encoded book; unreadable rows are skipped. Replaces the current contents. */
    fun decode(s: String?) {
        if (s.isNullOrBlank()) return
        decisions.clear(); byId.clear(); lastGraded.clear(); trades.clear()
        for (row in s.split(RS)) {
            val f = row.split(FS)
            // V5.0.8153 — 25 fields (late-entry flag); a 24-field row from 8146 reads as on time.
            if ((f.size != 24 && f.size != 25) || f[0].length != 1 || f[1].isBlank()) continue
            val at = f[3].toLongOrNull() ?: continue
            val r = CortexGradeRecord8144(f[0][0], f[1], f[2], at, f[4], f[5], f[6], f[7], f[8], f[9],
                f[10].toIntOrNull() ?: 1, dbl(f[11]), f[12] == "1", f[13] == "1", f[14] == "1")
            r.observedNetPct = dbl(f[15]); r.observedGrossPct = dbl(f[16]); r.executablePct = dbl(f[17]); r.lost = f[18] == "1"
            r.realisedPct = dbl(f[19]); r.ownerLane = f[20]; r.ownerSetup = f[21]; r.attribution = f[22]; r.mode = f[23]
            r.lateEntry8153 = f.getOrNull(24) == "1"
            if (r.lateEntry8153) r.executablePct = Double.NaN
            when (r.kind) {
                'D' -> if (!byId.containsKey(r.id)) { decisions.add(r); byId[r.id] = r; noteGraded(r) }
                'T' -> trades[r.id] = r
            }
        }
        while (decisions.size > maxDecisions) byId.remove(decisions.removeAt(0).id)
        revision++
    }

    private companion object {
        const val FS = '\u001F'
        const val RS = '\u001E'
    }
}
