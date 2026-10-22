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
    // V5.0.8272 (R10) — the FIRST executable label is the model's immutable five-minute target; later horizons are
    // stored apart with when they became knowable, and never rewrite it (a 4 h gain cannot enter an earlier fold).
    var executableRevisedPct: Double = Double.NaN
    var revisedAvailableAtMs: Long = 0L
    var revisions8272: Int = 0
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
    /** V5.0.8187 — the decision's voters were read after its label started (CortexAudit8169): observed only. */
    var lateRead8187: Boolean = false
    /**
     * V5.0.8322 — the five-minute label was a FLAT mark: no price different from the entry was ever observed (gross exactly
     * 0, no peak, no dip). A frozen / repeated mark carries nothing about the verdict, yet it booked as "lost the round trip"
     * into every voter and cohort. Observed only: no executable value, and it neither resolves nor un-resolves its cohort.
     */
    var flat8322: Boolean = false

    /** May this decision's label grade authority (executable, first of its episode)? */
    val graded: Boolean get() = kind == 'D' && executable && !repeat

    fun key(): String = "$lane|$setup|$regime|$timing|$costBand|$cohort"

    /** V5.0.8187 — the per-label cap of this record's cohort (RobustLabelRead8185: 4x for the $1M+ band, else 11x). */
    fun labelCap8187(): Double = com.lifecyclebot.engine.truth.RobustLabelRead8185.capForKey8185(cohort)

    /** V5.0.8187 — a label of this record read through its cap (the stored value is never changed). Display/diagnostics only. */
    internal fun capped8187(v: Double): Double = com.lifecyclebot.engine.truth.RobustLabelRead8185.clampLabel8187(v, labelCap8187())

    /**
     * V5.0.8322 — an executable value stored by 8187-8321 sits exactly at the cohort's cap while the observed label is above
     * it: the ingestion clamp destroyed the real value and it cannot be recovered (the observed record may already be a
     * later horizon). Counted and shown; such records age out and new labels rebuild the series raw.
     */
    internal val clampedLegacy8322: Boolean get() =
        executablePct.isFinite() && kotlin.math.abs(executablePct - labelCap8187()) < 1e-9 && observedNetPct.isFinite() && observedNetPct > labelCap8187()

    /** V5.0.8187 — may this record carry authority evidence (an executable, first-of-episode decision)? Evicted last. */
    internal val evidence8187: Boolean get() = kind == 'D' && executable && !repeat
}

class CortexGradeBook8144(private val maxDecisions: Int = 3_000, private val maxTrades: Int = 600) {
    private val decisions = ArrayList<CortexGradeRecord8144>()
    private val byId = HashMap<String, CortexGradeRecord8144>()
    private val lastGraded = HashMap<String, Long>()
    private val trades = LinkedHashMap<String, CortexGradeRecord8144>()
    var revision: Long = 0L
        private set

    /**
     * V5.0.8187 §EXECUTABLE_LABELS — per lane, the label-to-bank gap the authority reads subtract (Cortex7885 sets it from
     * the measured cost gap and the realised-vs-label shortfall). 0 by default: a bare book reads its labels as stored.
     */
    @Volatile var execOffset8187: (String) -> Double = { 0.0 }

    /**
     * V5.0.8322 §LANE_OBJECTIVE — which lanes are graded on their runner hold rather than the first five minutes (Cortex7885
     * sets it from the lane's exit doctrine: runner-from-entry lanes and runner lanes). false by default: a bare book grades
     * every lane on its first executable label, exactly as 8272.
     */
    @Volatile var runnerLane8322: (String) -> Boolean = { false }

    private fun runner8322(lane: String): Boolean = try { runnerLane8322(lane) } catch (_: Throwable) { false }

    /**
     * V5.0.8322 — the value a decision is GRADED on (authority, walk-forward, verdict rows, inversion): a runner lane's latest
     * executable revision inside its hold window (the 1 h / 4 h checkpoints the labeler already books, credited on the
     * lane's runner ladder and give-back exit, net of cost), else the immutable first (five-minute) label. RAW: no cap.
     * The first label is never rewritten (8272); the objective only chooses which stored value answers.
     */
    fun objectivePct8322(r: CortexGradeRecord8144): Double =
        if (r.executableRevisedPct.isFinite() && runner8322(r.lane)) r.executableRevisedPct else r.executablePct

    /** V5.0.8322 — when [objectivePct8322] became knowable (the revision's time, else five minutes after the decision). */
    fun objectiveAvailableAt8322(r: CortexGradeRecord8144): Long =
        if (r.executableRevisedPct.isFinite() && runner8322(r.lane) && r.revisedAvailableAtMs > 0L) r.revisedAvailableAtMs
        else r.atMs + FIRST_LABEL_MS_8322

    /** V5.0.8322 — records whose executable value the 8187 ingestion clamp destroyed (diagnostic). */
    fun clampedLegacy8322(): Int = decisions.count { it.clampedLegacy8322 }

    /** V5.0.8322 — decisions whose five-minute label was a flat mark (diagnostic). */
    fun flat8322(): Int = decisions.count { it.flat8322 }

    private fun offset8187(lane: String): Double = try { execOffset8187(lane).takeIf { it.isFinite() && it > 0.0 } ?: 0.0 } catch (_: Throwable) { 0.0 }

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
        if (decisions.size > maxDecisions) evict8187()
        if (lastGraded.size > maxDecisions * 2) {
            val cutoff = (decisions.firstOrNull()?.atMs ?: 0L) - CortexLabelHygiene8144.EPISODE_MS_8144
            lastGraded.entries.removeIf { it.value < cutoff }
        }
        revision++
        return true
    }

    /**
     * V5.0.8187 §EVIDENCE_RETENTION — a full book drops what carries no authority evidence first (repeats and unexecutable
     * refusals, oldest first), then the oldest evidence record of its most crowded lane x verdict cohort. 8175: ~6 captures a
     * second filled the 3,000-record book in minutes, so no lane's STRONG cohort ever held the 35 executable records the
     * walk-forward needs (promotion8144 INSUFFICIENT_DATA everywhere). Every cohort keeps its records in time order, lost and
     * late ones included, so the resolved share is not made a survivor statistic by the eviction.
     */
    private fun evict8187() {
        var drop = decisions.size - maxDecisions
        if (drop <= 0) return
        val iter = decisions.iterator()
        while (drop > 0 && iter.hasNext()) {
            val r = iter.next()
            if (!r.evidence8187) { iter.remove(); byId.remove(r.id); drop-- }
        }
        while (drop > 0 && decisions.isNotEmpty()) {
            val crowded = decisions.groupingBy { "${it.lane}#${it.bucket}" }.eachCount().maxByOrNull { e -> e.value }?.key
            val idx = if (crowded == null) 0 else decisions.indexOfFirst { "${it.lane}#${it.bucket}" == crowded }.coerceAtLeast(0)
            byId.remove(decisions.removeAt(idx).id)
            drop--
        }
        evicted8187++
    }

    /** V5.0.8187 — eviction passes run (diag). */
    var evicted8187: Long = 0L
        private set

    private fun noteGraded(r: CortexGradeRecord8144) {
        // V5.0.8187 — a label kept out of the books (late first price / late voter read) does not open the coin's episode:
        // the next decision of that coin is the first one graded, not a REPEAT of an excluded label.
        if (!r.graded || r.lost || r.lateEntry8153 || r.lateRead8187 || r.flat8322 || !r.observedNetPct.isFinite()) return
        val prior = lastGraded[r.episode]
        if (prior == null || r.atMs > prior) lastGraded[r.episode] = r.atMs
    }

    /** A label for decision [id] (first read or a later horizon). [executableAllowed] gates the executable value. */
    fun label(id: String, netPct: Double, grossPct: Double, executableValue: Double, executableAllowed: Boolean,
              availableAtMs: Long = System.currentTimeMillis()): Boolean {
        val r = byId[id] ?: return false
        if (!netPct.isFinite()) return false
        r.observedNetPct = netPct; r.observedGrossPct = grossPct; r.lost = false
        // V5.0.8322 — the executable (authority) value is stored RAW (8187 clamped it to the cohort's cap at ingestion,
        // destroying the runner tail; owner directive 8072: no win caps). The first stays immutable (8272); later horizons
        // are stored apart with when they became knowable.
        if (r.graded && executableAllowed && executableValue.isFinite() && !r.lateEntry8153 && !r.lateRead8187 && !r.flat8322) {
            if (!r.executablePct.isFinite()) r.executablePct = executableValue        // the immutable first target
            else { r.executableRevisedPct = executableValue; r.revisedAvailableAtMs = availableAtMs; r.revisions8272++ }
        }
        noteGraded(r)
        revision++
        return true
    }

    /** V5.0.8153 — decision [id]'s label started from a late first price: its executable value is withdrawn for good. */
    fun markLateEntry8153(id: String): Boolean {
        val r = byId[id] ?: return false
        if (r.kind != 'D') return false
        r.lateEntry8153 = true; r.executablePct = Double.NaN
        reopenEpisode8187(r)
        revision++
        return true
    }

    /**
     * V5.0.8187 — record [r] was taken out of the books after its label opened the coin's episode: the episode's last graded
     * time falls back to the newest other graded (on-time, labelled) decision of the same coin and lane, or is cleared.
     */
    internal fun reopenEpisode8187(r: CortexGradeRecord8144) {
        if (lastGraded[r.episode] != r.atMs) return
        val prior = decisions.filter { it !== r && it.episode == r.episode && it.graded && !it.lost && !it.lateEntry8153 && !it.lateRead8187 && !it.flat8322 && it.observedNetPct.isFinite() }
            .maxOfOrNull { it.atMs }
        if (prior == null) lastGraded.remove(r.episode) else lastGraded[r.episode] = prior
    }

    /** V5.0.8322 — decision [id]'s five-minute label was a flat mark: observed only, its executable value withdrawn, episode open. */
    fun markFlat8322(id: String): Boolean {
        val r = byId[id] ?: return false
        if (r.kind != 'D') return false
        r.flat8322 = true; r.executablePct = Double.NaN; r.executableRevisedPct = Double.NaN
        reopenEpisode8187(r)
        revision++
        return true
    }

    /** V5.0.8187 — the voters of decision [id] were read after its label started: observed only, the episode stays open. */
    fun markLateRead8187(id: String): Boolean {
        val r = byId[id] ?: return false
        if (r.kind != 'D') return false
        r.lateRead8187 = true; r.executablePct = Double.NaN
        reopenEpisode8187(r)
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
        // V5.0.8169 — a full book keeps the NEWEST trades by time. It evicted by insertion order, so the boot replay of every
        // historical close (8168: 1,345 unbound filings in 9 minutes into a 600-trade book) pushed out this session's
        // entry-bound trade records — the realised STRONG closes the authority caps read — and refiled old ones each boot.
        if (trades.size >= maxTrades) {
            val oldest = trades.values.minByOrNull { it.atMs } ?: return false
            if (!evictsOlder8169(oldest.atMs, r.atMs)) return false
            trades.remove(oldest.id)
        }
        trades[r.id] = r
        revision++
        return true
    }

    /** Pure (V5.0.8169): may a record at [newAtMs] take the place of a full book's oldest record at [oldestAtMs]? */
    fun evictsOlder8169(oldestAtMs: Long, newAtMs: Long): Boolean = newAtMs >= oldestAtMs

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
            .sortedBy { it.atMs }.map { objectivePct8322(it) - offset8187(lane) }  // V5.0.8322 — lane objective, raw, executable-net

    /**
     * 8269 — decision-time AATE TAKE vs REFUSE, distinct from Cortex's
     * STRONG/NEUTRAL/REFUSE prediction. Both are retained for the SAME
     * executable, on-time, first-in-episode population, so there is no
     * apples-to-oranges performance comparison or relabelling after a win.
     * "TAKE" is a recorded proposal, not proof that a live fill occurred.
     */
    fun executableDecisionSeries8269(lane: String, admitted: Boolean): List<Double> =
        decisions.filter {
            it.graded && !it.lost && it.lane == lane && it.admitted == admitted &&
                it.executablePct.isFinite()
        }.sortedBy { it.atMs }.map { objectivePct8322(it) - offset8187(lane) }  // V5.0.8322 — lane objective, raw

    fun executableDecisionGrossSeries8269(lane: String, admitted: Boolean): List<Double> =
        decisions.filter {
            it.graded && !it.lost && it.lane == lane && it.admitted == admitted &&
                it.executablePct.isFinite() && it.observedGrossPct.isFinite()
        }.sortedBy { it.atMs }.map { it.observedGrossPct }  // V5.0.8322 — raw

    /**
     * 8198 — the GROSS forward move (price only, before any cost, haircut or executable offset) of the same tradeable
     * records [executableSeries] grades, oldest first: what the coin actually did after the verdict.
     */
    fun executableGrossSeries8198(lane: String, setup: String?, bucket: Int?): List<Double> =
        decisions.filter { it.graded && !it.lost && it.lane == lane && (bucket == null || it.bucket == bucket) && (setup == null || it.setup == setup) && it.executablePct.isFinite() && it.observedGrossPct.isFinite() }
            .sortedBy { it.atMs }.map { it.observedGrossPct }  // V5.0.8322 — raw

    /** 8196: timestamps are essential to compare competing cohorts on the same OOS market span. */
    fun executableTimed8196(lane: String, setup: String?, bucket: Int?): List<CortexWalkForward8144.Timed8144> =
        decisions.filter { it.graded && !it.lost && it.lane == lane &&
            (bucket == null || it.bucket == bucket) && (setup == null || it.setup == setup) &&
            it.executablePct.isFinite() }
            .sortedBy { it.atMs }
            .map { CortexWalkForward8144.Timed8144(it.atMs, objectivePct8322(it) - offset8187(lane), objectiveAvailableAt8322(it), it.executablePct - offset8187(lane)) }  // V5.0.8322

    /** Observed values of every labelled decision in [lane] for [bucket], hygiene ignored (the raw cohort). */
    fun observedSeries(lane: String, bucket: Int): List<Double> =
        decisions.filter { it.kind == 'D' && !it.lost && it.lane == lane && it.bucket == bucket && it.observedNetPct.isFinite() }
            .sortedBy { it.atMs }.map { it.capped8187(it.observedNetPct) }  // V5.0.8187

    /**
     * V5.0.8187 — the realised-vs-label shortfall of [lane] in [mode]: every closed trade paired with the admitted decision
     * that opened it (same coin and lane, decided at most [maxBeforeMs] before the open), label (capped) minus the realised
     * whole-position return. Returns [pairs, mean shortfall %]; a positive shortfall means the labels promised more than
     * the trades banked (entry chase, fills, costs at the ticket, the exits actually run).
     */
    fun pairedShortfall8187(lane: String, mode: String, maxBeforeMs: Long = 15L * 60_000L): DoubleArray {
        var n = 0; var sum = 0.0
        for (t in trades.values) {
            if (!t.realisedPct.isFinite() || !t.mode.equals(mode, true) || (t.ownerLane.ifBlank { t.lane }) != lane) continue
            val d = decisions.asReversed().firstOrNull {
                it.kind == 'D' && it.admitted && it.episode == t.episode && t.atMs - it.atMs in 0L..maxBeforeMs && it.observedNetPct.isFinite()
            } ?: continue
            n++; sum += d.capped8187(d.observedNetPct) - t.realisedPct
        }
        return doubleArrayOf(n.toDouble(), if (n > 0) sum / n else 0.0)
    }

    /** V5.0.8187 — every lane with a decision or trade record (the executable offsets are refreshed for these). */
    fun lanes8187(): Set<String> = (decisions.map { it.lane } + trades.values.map { it.ownerLane.ifBlank { it.lane } }).filter { it.isNotBlank() }.toSet()

    /** V5.0.8187 — (setup -> [closes, mean realised %]) of [lane]'s attributed realised closes in [mode], each capped. */
    fun realisedBySetup8187(lane: String, mode: String): Map<String, DoubleArray> =
        trades.values.filter { it.realisedPct.isFinite() && it.ownerLane == lane && it.ownerSetup.isNotBlank() && it.mode.equals(mode, true) }
            .groupBy { it.ownerSetup }
            .mapValues { (_, rs) -> doubleArrayOf(rs.size.toDouble(), rs.sumOf { com.lifecyclebot.engine.truth.RobustLabelRead8185.clampLabel8187(it.realisedPct) } / rs.size) }

    /** V5.0.8187 — the executable series as stored (pre-8187 records kept their uncapped values; the screen shows both). */
    fun executableSeriesRaw8187(lane: String, bucket: Int): List<Double> =
        decisions.filter { it.graded && !it.lost && it.lane == lane && it.bucket == bucket && it.executablePct.isFinite() }
            .sortedBy { it.atMs }.map { it.executablePct }

    /** V5.0.8187 — the same observed series read raw (the screen shows raw beside robust where they differ). */
    fun observedSeriesRaw8187(lane: String, bucket: Int): List<Double> =
        decisions.filter { it.kind == 'D' && !it.lost && it.lane == lane && it.bucket == bucket && it.observedNetPct.isFinite() }
            .sortedBy { it.atMs }.map { it.observedNetPct }

    /**
     * V5.0.8187 — the composition of [lane] x [bucket]'s executable first-of-episode decisions: [labelled on time, lost
     * (no label), late first price, late voter read]. The walk-forward reads the first; the resolved share all four.
     */
    fun cohortCounts8187(lane: String, bucket: Int): IntArray {
        val out = IntArray(4)
        for (r in decisions) {
            if (!r.evidence8187 || r.lane != lane || r.bucket != bucket) continue
            when {
                r.lost -> out[1]++
                r.lateEntry8153 -> out[2]++
                r.lateRead8187 -> out[3]++
                r.observedNetPct.isFinite() -> out[0]++
            }
        }
        return out
    }

    /**
     * V5.0.8187 — an admitted decision of [mint] in [lane] made at most [maxBeforeMs] before [openedAtMs] (and not after it), in
     * [mode] when the record knows its mode: the entry verdict a close can be bound to when no entry read was bound.
     */
    fun admittedDecision8187(mint: String, lane: String, mode: String, openedAtMs: Long, maxBeforeMs: Long): CortexGradeRecord8144? =
        decisions.asReversed().firstOrNull {
            it.kind == 'D' && it.admitted && it.episode == "$mint|$lane" && openedAtMs - it.atMs in 0L..maxBeforeMs &&
                (it.mode.isBlank() || it.mode.equals(mode, true))
        }

    /** Labelled / (labelled + lost) over the executable first-of-episode decisions of [lane] x [bucket]; 1 when none. */
    fun resolvedShare(lane: String, bucket: Int, setup: String? = null): Double {
        var ok = 0; var lost = 0
        for (r in decisions) {
            if (r.kind != 'D' || !r.executable || r.repeat || r.lane != lane || r.bucket != bucket || (setup != null && r.setup != setup)) continue
            // V5.0.8187 — a late voter read is neither resolved nor lost: its label came, its verdict was read too late to
            // count (the lag is queue timing, not outcome), so it leaves the cohort instead of dragging the share down.
            if (r.lateRead8187 && !r.lost && !r.lateEntry8153) continue
            if (r.flat8322 && !r.lost && !r.lateEntry8153) continue  // V5.0.8322 — a flat mark resolves nothing and loses nothing
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
                    val s = CortexWalkForward8144.stat8144(rs.map { objectivePct8322(it) - offset8187(it.lane) })  // V5.0.8322 — objective, raw
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
        if (r.lateRead8187) "1" else "0",
        // V5.0.8322 — the later-horizon executable value and when it became knowable survive a restart; the flat-mark flag.
        num(r.executableRevisedPct), r.revisedAvailableAtMs.toString(), if (r.flat8322) "1" else "0",
    ).joinToString(FS.toString())

    fun encode(): String = (decisions + trades.values).joinToString(RS.toString()) { encodeOne(it) }

    /** Restores an encoded book; unreadable rows are skipped. Replaces the current contents. */
    fun decode(s: String?) {
        if (s.isNullOrBlank()) return
        decisions.clear(); byId.clear(); lastGraded.clear(); trades.clear()
        for (row in s.split(RS)) {
            val f = row.split(FS)
            // V5.0.8153 — 25 fields (late-entry flag); a 24-field row from 8146 reads as on time.
            // V5.0.8187 — 26 fields (late voter read); a 25-field row reads as on time.
            // V5.0.8322 — 29 fields (revised executable, its availability, flat mark); 24..26-field rows read as before.
            if (f.size !in 24..29 || f[0].length != 1 || f[1].isBlank()) continue
            val at = f[3].toLongOrNull() ?: continue
            val r = CortexGradeRecord8144(f[0][0], f[1], f[2], at, f[4], f[5], f[6], f[7], f[8], f[9],
                f[10].toIntOrNull() ?: 1, dbl(f[11]), f[12] == "1", f[13] == "1", f[14] == "1")
            r.observedNetPct = dbl(f[15]); r.observedGrossPct = dbl(f[16]); r.executablePct = dbl(f[17]); r.lost = f[18] == "1"
            r.realisedPct = dbl(f[19]); r.ownerLane = f[20]; r.ownerSetup = f[21]; r.attribution = f[22]; r.mode = f[23]
            r.lateEntry8153 = f.getOrNull(24) == "1"
            r.lateRead8187 = f.getOrNull(25) == "1"
            r.executableRevisedPct = dbl(f.getOrNull(26)); r.revisedAvailableAtMs = f.getOrNull(27)?.toLongOrNull() ?: 0L
            r.flat8322 = f.getOrNull(28) == "1"
            if (r.lateEntry8153 || r.lateRead8187 || r.flat8322) { r.executablePct = Double.NaN; r.executableRevisedPct = Double.NaN }
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
        /** V5.0.8322 — the first executable label is booked five minutes after the decision. */
        const val FIRST_LABEL_MS_8322 = 5L * 60_000L
    }
}
