package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 §ONE_CANDIDATE_ADMISSION_RECORD.
 *
 * 5.0.8127 top refusals: 729 generic score floor (CANONICAL_V3_SCORE_FLOOR_7243), 230 losing tactic, 192 proven-only,
 * 161 negative timing. Each came from its own gate reading its own slice of the evidence, so a candidate whose OWN
 * record proved it pays (a fired setup on 30+ graded labels with mean - SE above cost) could still be refused by the
 * generic V3 meme score (a prior that scores fresh launches 9-32), and the executor's pre-lease floor refused the
 * same candidate a second time on the same generic number. The reverse also happened: a lane-wide bypass (ladder,
 * lane labels, retired veto population, playbook score) cleared the generic floor for a coin whose selected setup
 * was losing on its own labels.
 *
 * One record per candidate now compares, in one place: lane + setup, regime + lifecycle stage, input freshness,
 * expected executable gross move, round-trip cost (fees, tips, slippage, impact), expected net and downside, the
 * setup / timing / cell / stage evidence with its confidence interval (n, mean, stderr), runner-tail potential and
 * the safety refusals, and returns ONE terminal admission with its evidence trace:
 *
 *   ADMIT       supported candidate-specific edge (specific = true), or the generic prior clears (specific = false)
 *   REFUSE      hard safety, structural selling risk, a candidate-specific loss pattern, adverse timing, bad
 *               execution economics (specific = true) — or no supported edge and the generic prior fails
 *   DEFER_DATA  core inputs unobserved or the price stale: recoverable, re-evaluated within a bounded window
 *               ([deferBudget8136]); never NO_TRIGGER, never a fabricated setup, never a permanent verdict.
 *
 * The generic V3 score is a PRIOR: once this record admits on supported specific edge, a generic score floor does
 * not independently refuse it (FinalDecisionGate.playbookClearsFloor7937 → [floorVerdict8136]). Thin positive
 * evidence (n under [SUPPORT_MIN_N_8136]) never bypasses the generic prior. A runner label never overrides a proven
 * loss pattern, a hostile mint, structural risk or bad economics: those are checked first.
 */
object CandidateAdmission8136 {

    enum class Terminal8136 { ADMIT, REFUSE, DEFER_DATA }

    /** One measured record: [n] graded labels, mean and standard error (%). [netOfCost]: the labels already carry the round trip. */
    data class Evidence8136(
        val kind: String,
        val id: String,
        val n: Int,
        val meanPct: Double,
        val sePct: Double,
        val runnerRate: Double = Double.NaN,
        val netOfCost: Boolean = true,
    ) {
        val lowerPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct - sePct else Double.NaN
        val upperPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct + sePct else Double.NaN
        val compact: String get() = "$kind:$id n$n ${fmt8136(meanPct)}±${fmt8136(sePct)}"
    }

    /** Everything the admission compares for one candidate (mint + lane + selected setup). Pure data. */
    data class Candidate8136(
        val mint: String,
        val lane: String,
        /** The strategy the coin trades as (LanePlaybook7907.selectedSetup8094); null when it could not be classified. */
        val selectedSetup: String?,
        val regime: String = "",
        val stage: String = "",
        /** Age of the last observed price; Long.MAX_VALUE when the coin was never priced. */
        val priceAgeMs: Long = 0L,
        /** Core classifier inputs the coin is missing (LanePlaybook7907 DATA_INCOMPLETE), e.g. "shape:UNKNOWN". */
        val missingInputs: List<String> = emptyList(),
        val expectedGrossPct: Double = Double.NaN,
        val costPct: Double = Double.NaN,
        val downsidePct: Double = Double.NaN,
        /** Every fired setup's own record in this lane (the selected one included). */
        val setups: List<Evidence8136> = emptyList(),
        val cell: Evidence8136? = null,
        /** The selected setup's record at the coin's current timing band. */
        val timing: Evidence8136? = null,
        val stageRecord: Evidence8136? = null,
        /** The tactic the coin would be traded as, when its realised record in this lane is losing. */
        val losingTactic: String? = null,
        /** A fresh launch: its price must be fresher ([HOT_STALE_PRICE_MS_8136]). */
        val hotLaunch: Boolean = false,
        val safetyRefusal: String? = null,
        val structuralRisk: String? = null,
        val genericScore: Double = Double.NaN,
        val genericFloor: Double = Double.NaN,
    )

    /** The one authoritative terminal admission and the evidence trace behind it. */
    data class Decision8136(
        val terminal: Terminal8136,
        val reason: String,
        /** The terminal rests on candidate-specific evidence (true) or on the generic prior / missing data (false). */
        val specific: Boolean,
        val chosenSetup: String?,
        val expectedNetPct: Double,
        val trace: List<String>,
    ) {
        val admitted: Boolean get() = terminal == Terminal8136.ADMIT
        val compact: String get() = "${terminal.name}:$reason${chosenSetup?.let { " setup=$it" } ?: ""} [${trace.joinToString("; ")}]"
    }

    internal const val SUPPORT_MIN_N_8136 = 30
    internal const val LOSING_MIN_N_8136 = 12
    internal const val PROVEN_LOSING_MIN_N_8136 = 30
    internal const val PROVEN_LOSING_PCT_8136 = -2.0
    internal const val TAIL_MIN_N_8136 = 100
    internal const val TAIL_MIN_RATE_8136 = 0.10
    internal const val STALE_PRICE_MS_8136 = 5L * 60_000L
    internal const val HOT_STALE_PRICE_MS_8136 = 60_000L

    internal fun fmt8136(v: Double): String = if (v.isFinite()) "%+.1f".format(v) else "-"

    // ── pure evidence tests ──

    /**
     * Pure: a record supports admission — [SUPPORT_MIN_N_8136]+ labels and mean - SE above the bar (0 for labels already
     * net of cost, else [costPct]); or, on [TAIL_MIN_N_8136]+ labels, a positive point estimate over the bar with
     * [TAIL_MIN_RATE_8136]+ of its labels running (the runner-tail test, LiveEdgeGate7877.runnerVerdict).
     */
    fun supported8136(e: Evidence8136?, costPct: Double): Boolean {
        if (e == null || e.n < SUPPORT_MIN_N_8136 || !e.lowerPct.isFinite()) return false
        val bar = if (e.netOfCost) 0.0 else if (costPct.isFinite() && costPct > 0.0) costPct else return false
        if (e.lowerPct > bar) return true
        return e.n >= TAIL_MIN_N_8136 && e.meanPct > bar && e.runnerRate.isFinite() && e.runnerRate >= TAIL_MIN_RATE_8136
    }

    /** Pure: losing on its own evidence — [LOSING_MIN_N_8136]+ labels and mean + SE below zero (LanePlaybook7907.setupRecordNegative8094). */
    fun losing8136(e: Evidence8136?): Boolean =
        e != null && e.n >= LOSING_MIN_N_8136 && e.upperPct.isFinite() && e.upperPct < 0.0

    /** Pure: proven losing — [PROVEN_LOSING_MIN_N_8136]+ labels and mean + SE below [PROVEN_LOSING_PCT_8136]. */
    internal fun provenLosing8136(e: Evidence8136?): Boolean =
        e != null && e.n >= PROVEN_LOSING_MIN_N_8136 && e.upperPct.isFinite() && e.upperPct < PROVEN_LOSING_PCT_8136

    /** Pure: positive but thin — under [SUPPORT_MIN_N_8136] labels; it never bypasses the generic prior. */
    fun thinPositive8136(e: Evidence8136?): Boolean =
        e != null && e.n in 1 until SUPPORT_MIN_N_8136 && e.meanPct.isFinite() && e.meanPct > 0.0

    /** Pure: is the price too old to trade on? A hot launch moves faster, so its bar is tighter. */
    fun priceStale8136(priceAgeMs: Long, hotLaunch: Boolean): Boolean =
        priceAgeMs < 0L || priceAgeMs > (if (hotLaunch) HOT_STALE_PRICE_MS_8136 else STALE_PRICE_MS_8136)

    // ── the decision ──

    /** Pure: the one terminal admission for [c]. Order: safety, structure, loss patterns, timing, economics, data, edge, prior. */
    fun decide8136(c: Candidate8136): Decision8136 {
        val trace = ArrayList<String>(10)
        trace += "lane=${c.lane} setup=${c.selectedSetup ?: "-"} regime=${c.regime.ifBlank { "-" }} stage=${c.stage.ifBlank { "-" }}${if (c.hotLaunch) " hot" else ""}"
        val net = if (c.expectedGrossPct.isFinite() && c.costPct.isFinite()) c.expectedGrossPct - c.costPct else Double.NaN
        trace += "gross=${fmt8136(c.expectedGrossPct)} cost=${fmt8136(c.costPct)} net=${fmt8136(net)} downside=${fmt8136(c.downsidePct)}"
        fun done(t: Terminal8136, reason: String, specific: Boolean, setup: String?): Decision8136 =
            Decision8136(t, reason, specific, setup, net, trace.toList())

        // 1. hard safety and structural selling risk: nothing overrides them.
        c.safetyRefusal?.let { trace += "safety=$it"; return done(Terminal8136.REFUSE, "SAFETY_REFUSED_8136", true, c.selectedSetup) }
        c.structuralRisk?.let { trace += "structure=$it"; return done(Terminal8136.REFUSE, "STRUCTURAL_RISK_8136", true, c.selectedSetup) }

        // 2. candidate-specific loss patterns. The coin's own cell proven to pay, or another fired setup supported on its
        //    own record, is the strategy instead (LiveEdgeGate7877: cellOrKindProven8094 / provenSetup8056 multi-strategy).
        val cellSupported = supported8136(c.cell, c.costPct)
        val bestSetup = c.setups.filter { !losing8136(it) && supported8136(it, c.costPct) }.maxByOrNull { it.lowerPct }
        val selectedRec = c.setups.firstOrNull { it.id == c.selectedSetup }
        var chosen = c.selectedSetup
        if (losing8136(selectedRec)) {
            if (bestSetup == null && !cellSupported) {
                trace += "setupLosing=${selectedRec?.compact}"
                return done(Terminal8136.REFUSE, "SETUP_RECORD_LOSING_8136", true, c.selectedSetup)
            }
            if (bestSetup != null) {
                trace += "altSetup=${bestSetup.compact} over ${selectedRec?.compact}"
                chosen = bestSetup.id
            } else {
                trace += "setupLosing=${selectedRec?.compact} carried by cell ${c.cell?.compact}"
            }
        }
        if (provenLosing8136(c.cell)) { trace += "cellLosing=${c.cell?.compact}"; return done(Terminal8136.REFUSE, "CELL_PROVEN_LOSING_8136", true, chosen) }
        if (provenLosing8136(c.stageRecord) && !cellSupported) {
            trace += "stageLosing=${c.stageRecord?.compact}"
            return done(Terminal8136.REFUSE, "STAGE_PROVEN_LOSING_8136", true, chosen)
        }
        if (c.losingTactic != null && bestSetup == null && !cellSupported) {
            trace += "tacticLosing=${c.losingTactic}"
            return done(Terminal8136.REFUSE, "TACTIC_RECORD_LOSING_8136", true, chosen)
        }
        // 3. timing: the selected setup's record at this point of the move. A token whose own cell pays has already paid
        //    for its timing (its labels start at the fill latency).
        // V5.0.8144 — the default (selected) setup's adverse timing no longer vetoes before the token's own proven
        //    alternatives are weighed: another fired setup supported on its own record is the strategy instead (the
        //    timing book belongs to the selected setup, not to the alternative).
        if (chosen == c.selectedSetup && losing8136(c.timing) && !cellSupported && bestSetup != null && bestSetup.id != c.selectedSetup) {
            trace += "altSetup=${bestSetup.compact} over timing ${c.timing?.compact}"
            chosen = bestSetup.id
        }
        if (chosen == c.selectedSetup && losing8136(c.timing) && !cellSupported) {
            trace += "timing=${c.timing?.compact}"
            return done(Terminal8136.REFUSE, "TIMING_ADVERSE_8136", true, chosen)
        }

        // 4. execution economics: the expected executable move must clear NetEdge8030.EDGE_MULT x the round trip.
        val econKnown = c.expectedGrossPct.isFinite() && c.costPct.isFinite()
        if (econKnown && !NetEdge8030.entryClears8030(c.expectedGrossPct, c.costPct)) {
            return done(Terminal8136.REFUSE, "EXECUTION_ECONOMICS_8136", true, chosen)
        }

        // 5. data: a stale price or unknown economics defers every candidate; missing classifier inputs defer a candidate
        //    with no supported evidence of its own (its setup cannot be judged yet).
        val chosenRec = c.setups.firstOrNull { it.id == chosen }
        val edge = listOfNotNull(bestSetup ?: chosenRec?.takeIf { supported8136(it, c.costPct) }, c.cell?.takeIf { cellSupported })
            .maxByOrNull { it.lowerPct }
        val stale = priceStale8136(c.priceAgeMs, c.hotLaunch)
        if (stale || !econKnown || (c.missingInputs.isNotEmpty() && edge == null)) {
            val missing = c.missingInputs + (if (stale) listOf("price") else emptyList()) + (if (!econKnown) listOf("economics") else emptyList())
            trace += "missing=${missing.joinToString(",")}"
            return done(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, chosen)
        }

        // 6. supported candidate-specific edge: the generic score is only a prior.
        if (edge != null) {
            trace += "edge=${edge.compact}${if (edge.runnerRate.isFinite()) " run=${(edge.runnerRate * 100).toInt()}%" else ""}"
            return done(Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, if (edge.kind == KIND_SETUP) edge.id else chosen)
        }

        // 7. no supported edge: thin positives are noted, never admitted; the generic prior decides.
        listOfNotNull(chosenRec, c.cell).filter { thinPositive8136(it) }
            .forEach { trace += "thinPositive=${it.compact} needs n>=$SUPPORT_MIN_N_8136" }
        if (c.genericScore.isFinite() && c.genericFloor.isFinite()) {
            trace += "generic=${fmt8136(c.genericScore)} floor=${fmt8136(c.genericFloor)}"
            return if (c.genericScore >= c.genericFloor) done(Terminal8136.ADMIT, "GENERIC_PRIOR_CLEARS_8136", false, chosen)
            else done(Terminal8136.REFUSE, "GENERIC_SCORE_FLOOR_8136", false, chosen)
        }
        return done(Terminal8136.REFUSE, "NO_SUPPORTED_EDGE_8136", false, chosen)
    }

    const val KIND_SETUP = "SETUP"
    const val KIND_CELL = "CELL"
    const val KIND_TIMING = "TIMING"
    internal const val KIND_STAGE = "STAGE"

    /**
     * Pure: what the record means for a generic score floor — true clears it (supported specific edge), false holds it
     * with no lane-wide bypass (a specific refusal), null leaves the floor's own bypasses to decide.
     */
    fun floorVerdict8136(d: Decision8136?): Boolean? = when {
        d == null || !d.specific -> null
        d.terminal == Terminal8136.ADMIT -> true
        d.terminal == Terminal8136.REFUSE -> false
        else -> null
    }

    // ── bounded DATA_INCOMPLETE re-evaluation ──

    /** One mint's deferral window: first deferral and how many re-evaluations it has had. */
    data class Defer8136(val firstMs: Long, val count: Int)

    const val DEFER_WINDOW_MS_8136 = 10L * 60_000L
    const val DEFER_MAX_8136 = 40
    const val DEFER_RESET_MS_8136 = 30L * 60_000L

    /** Pure: the next deferral state. A window older than [DEFER_RESET_MS_8136] starts over, so no deferral is permanent. */
    fun nextDefer8136(prev: Defer8136?, nowMs: Long): Defer8136 =
        if (prev == null || nowMs < prev.firstMs || nowMs - prev.firstMs >= DEFER_RESET_MS_8136) Defer8136(nowMs, 1)
        else prev.copy(count = prev.count + 1)

    /** Pure: may this candidate still wait for its data ([DEFER_MAX_8136] re-evaluations inside [DEFER_WINDOW_MS_8136])? */
    fun deferOpen8136(s: Defer8136, nowMs: Long): Boolean =
        s.count <= DEFER_MAX_8136 && nowMs - s.firstMs in 0L..DEFER_WINDOW_MS_8136

    private val defers8136 = ConcurrentHashMap<String, Defer8136>()

    /** Counts one DATA_INCOMPLETE re-evaluation of [mint]; false once its bounded window is spent (the ordinary refusal then stands). */
    fun deferBudget8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val s = defers8136.compute(mint) { _, p -> nextDefer8136(p, nowMs) } ?: return false
        if (defers8136.size > 4_000) defers8136.entries.removeIf { nowMs - it.value.firstMs >= DEFER_RESET_MS_8136 }
        val open = deferOpen8136(s, nowMs)
        if (!open) try { PipelineHealthCollector.labelInc("ADMISSION_DEFER_WINDOW_SPENT_8136") } catch (_: Throwable) {}
        return open
    }

    // ── V5.0.8144 §COALESCED_MISSING_DATA_REEVALUATION ──
    //
    // 5.0.8142: 458 DATA_INCOMPLETE FDG deferrals. Every cycle re-ran the whole admission record (playbook classification,
    // forward-label cells, timing / stage books, cost model) for a candidate whose missing input had not had time to
    // arrive, and every re-run spent one of its 40 bounded re-evaluations. A deferral is now scheduled per mint + missing
    // feature with a backoff ([REEVAL_BASE_MS_8144] doubling to [REEVAL_MAX_MS_8144]): until one of its features is due,
    // the last DEFER_DATA decision is returned as it was and no budget is spent; when one is due, the record is decided
    // again in full. The deferral window, its budget and its reset are unchanged, and nothing but DEFER_DATA is cached.

    /** One mint + missing feature: when it is next due and how many times it has been re-scheduled. */
    data class Reeval8144(val dueMs: Long, val attempts: Int)

    const val REEVAL_BASE_MS_8144 = 5_000L
    const val REEVAL_MAX_MS_8144 = 60_000L

    /** Pure: the next schedule after a re-evaluation at [nowMs] — the backoff doubles per attempt up to the cap. */
    fun nextReeval8144(prev: Reeval8144?, nowMs: Long): Reeval8144 {
        val attempts = (prev?.attempts ?: 0).coerceAtMost(16)
        val wait = (REEVAL_BASE_MS_8144 shl attempts.coerceAtMost(10)).coerceAtMost(REEVAL_MAX_MS_8144)
        return Reeval8144(nowMs + wait, attempts + 1)
    }

    /** Pure: is this feature due (never scheduled, its time reached, or a schedule from a clock that moved back)? */
    fun reevalDue8144(s: Reeval8144?, nowMs: Long): Boolean =
        s == null || nowMs >= s.dueMs || s.dueMs - nowMs > 2 * REEVAL_MAX_MS_8144

    /** Pure: the features a DEFER_DATA decision is waiting for (its "missing=" trace entry). */
    fun missingOf8144(d: Decision8136): List<String> =
        d.trace.firstOrNull { it.startsWith("missing=") }?.removePrefix("missing=")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

    private class Held8144(val decision: Decision8136, val features: List<String>, val atMs: Long)

    private val reevals8144 = ConcurrentHashMap<String, Reeval8144>()
    private val held8144 = ConcurrentHashMap<String, Held8144>()
    private val coalesced8144 = AtomicLong(0)

    private fun features8144(missing: List<String>): List<String> = missing.ifEmpty { listOf("core:UNKNOWN") }

    /** Schedules every due feature of [mint]; true when at least one was due (the caller re-evaluates now). */
    private fun scheduleDue8144(mint: String, features: List<String>, nowMs: Long): Boolean {
        var due = false
        for (f in features) {
            val key = "$mint|$f"
            val prev = reevals8144[key]
            if (reevalDue8144(prev, nowMs)) { reevals8144[key] = nextReeval8144(prev, nowMs); due = true }
        }
        if (reevals8144.size > 8_000) reevals8144.entries.removeIf { nowMs - it.value.dueMs > DEFER_RESET_MS_8136 }
        return due
    }

    /** The held DEFER_DATA decision for [mint] in [laneRaw] while none of its missing features is due yet, else null. */
    private fun coalescedDefer8144(mint: String, laneRaw: String, nowMs: Long): Decision8136? {
        val h = held8144["$mint|$laneRaw"] ?: return null
        if (nowMs - h.atMs !in 0L..REEVAL_MAX_MS_8144) return null
        if (h.features.any { reevalDue8144(reevals8144["$mint|$it"], nowMs) }) return null
        coalesced8144.incrementAndGet()
        return h.decision
    }

    /** Holds a DEFER_DATA decision (scheduling its features) and files the decision in the runner trace. */
    private fun noteDecision8144(mint: String, laneRaw: String, d: Decision8136, nowMs: Long) {
        if (mint.isBlank()) return
        val key = "$mint|$laneRaw"
        if (d.terminal == Terminal8136.DEFER_DATA) {
            val f = features8144(missingOf8144(d))
            scheduleDue8144(mint, f, nowMs)
            if (held8144.size > 4_000) held8144.entries.removeIf { nowMs - it.value.atMs > REEVAL_MAX_MS_8144 }
            held8144[key] = Held8144(d, f, nowMs)
        } else {
            held8144.remove(key)
        }
        try { AdmissionRunnerTrace8144.noteAdmission8144(mint, laneRaw, d.terminal.name, d.reason) } catch (_: Throwable) {}
    }

    /**
     * LiveEdgeGate7877.dataDefer8136: [deferBudget8136] coalesced per mint + missing feature. A call while none of
     * [missing] is due keeps the open window without spending a re-evaluation; a due feature re-evaluates (counted).
     */
    fun deferBudgetFor8144(mint: String, missing: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        if (scheduleDue8144(mint, features8144(missing), nowMs)) return deferBudget8136(mint, nowMs)
        val s = defers8136[mint] ?: return deferBudget8136(mint, nowMs)
        coalesced8144.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ADMISSION_REEVAL_COALESCED_8144") } catch (_: Throwable) {}
        return deferOpen8136(s, nowMs)
    }

    // ── the ledger: counts per terminal admission reason ──

    private val counts8136 = ConcurrentHashMap<String, AtomicLong>()
    private const val MAX_KEYS_8136 = 64

    /** Pure: a data-gap reason (recoverable) rather than a verdict on the token. */
    internal fun dataReason8136(reason: String): Boolean =
        reason.startsWith("DATA_INCOMPLETE") || reason.contains("TOO_FEW_BARS")

    /** Pure: an existing gate's verdict (LiveEdgeGate7877.Verdict8083 name / plan wait) as a terminal admission. */
    fun terminalOf8136(verdict: String, reason: String): Terminal8136 = when {
        verdict == Terminal8136.ADMIT.name -> Terminal8136.ADMIT
        dataReason8136(reason) -> Terminal8136.DEFER_DATA
        else -> Terminal8136.REFUSE
    }

    /** Pure: the bounded ledger key — source, terminal and the reason without its lane / numeric tail. */
    fun ledgerKey8136(source: String, terminal: Terminal8136, reason: String): String {
        val r = reason.substringBefore(':').substringBefore(' ').replace(Regex("_-?\\d+(\\.\\d+)?PCT.*\$"), "")
        // Cut after the first version tag that ends a named reason (drops the lane / setup tail), e.g.
        // PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_X -> PLAYBOOK_SETUP_PROVEN_LOSING_7907.
        val m = Regex("_\\d{4}").findAll(r).firstOrNull { it.range.last >= 11 }
        val stem = if (m != null) r.substring(0, m.range.last + 1) else r
        return "$source|${terminal.name}:${stem.take(44)}"
    }

    fun note8136(source: String, terminal: Terminal8136, reason: String) {
        try {
            val key = ledgerKey8136(source, terminal, reason)
            val k = if (counts8136.size < MAX_KEYS_8136 || counts8136.containsKey(key)) key else "$source|${terminal.name}:OTHER"
            counts8136.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
            PipelineHealthCollector.labelInc("CANDIDATE_ADMISSION_8136_${source}_${terminal.name}")
        } catch (_: Throwable) {}
    }

    /** LiveEdgeGate7877.recordAssessment8083 / TradePlan7739: an existing gate's terminal, filed in this ledger. */
    fun noteVerdict8136(source: String, verdict: String, reason: String) = note8136(source, terminalOf8136(verdict, reason), reason)

    fun statusLine8136(): String {
        val byTerminal = Terminal8136.values().joinToString(",") { t ->
            "${t.name}=${counts8136.entries.filter { it.key.contains("|${t.name}:") }.sumOf { it.value.get() }}"
        }
        val top = counts8136.entries.sortedByDescending { it.value.get() }.take(12)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        return "admission8136[$byTerminal reasons{$top}] coalescedReevals8144=${coalesced8144.get()}"
    }

    // ── live adapter: the record from the bot's own stores (read-only peeks; no fetch, no synthetic data) ──

    private fun stdErr8136(variance: Double, n: Double): Double =
        if (n > 1.0 && variance.isFinite() && variance >= 0.0) kotlin.math.sqrt(variance / n) else Double.POSITIVE_INFINITY

    /** The admission record for [ts] in [laneRaw] now, decided. Null only on an internal failure. */
    fun assess8136(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Decision8136? = try {
        // V5.0.8144 — a data deferral is re-decided when one of its missing features is due (coalesced, not every cycle).
        coalescedDefer8144(ts.mint, laneRaw, nowMs) ?: decide8136(candidateFor8136(ts, laneRaw, nowMs)).also { noteDecision8144(ts.mint, laneRaw, it, nowMs) }
    } catch (_: Throwable) { null }

    /** Builds the candidate record from the playbook, forward labels, net-edge cost model and safety report. */
    internal fun candidateFor8136(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Candidate8136 {
        val lane = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
        val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907
        val selected = try { pb.selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        val setups = try {
            pb.firedSetupRecords8136(ts, lane, nowMs).map { (id, r) -> Evidence8136(KIND_SETUP, id, r[0].toInt(), r[1], r[2], r[3]) }
        } catch (_: Throwable) { emptyList() }
        val cellStat = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        // A cell whose observations mostly lost their mark is not a measurement of its coins (LiveEdgeGate7877.mostlyUnpriced8022).
        val cell = cellStat?.takeIf { it.resolvedShare >= LiveEdgeGate7877.MIN_RESOLVED_SHARE_7944 }
            ?.let { Evidence8136(KIND_CELL, it.key.take(40), it.n60, it.meanNet60Pct, it.stderr60Pct, it.runnerRate60) }
        val timing = try {
            pb.timingStat8094(ts, lane, nowMs)?.let { a ->
                Evidence8136(KIND_TIMING, "${selected ?: "-"}@${pb.timingBandOf8094(ts)}", a[2].toInt(), a[0], stdErr8136(a[1], a[2]))
            }
        } catch (_: Throwable) { null }
        val fit = try { com.lifecyclebot.engine.TokenMetricStageRouter.laneFit(ts, lane) } catch (_: Throwable) { null }
        val stageRecord = try {
            if (fit == null) null else ForwardReturnLabeler7731.stageStatFor7928(fit.lane, fit.stage.name)
                ?.let { st -> Evidence8136(KIND_STAGE, fit.stage.name, st.n60, st.meanNet60Pct, st.stderr60Pct, st.runnerRate60) }
        } catch (_: Throwable) { null }
        val launch = try {
            FreshLaunchSelector7737.launchRead7742(ts, FieldManual7715.allInCostPct(10.0, ts.lastLiquidityUsd), nowMs)
        } catch (_: Throwable) { null }
        val safety = if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK)
            "HARD_BLOCK:${ts.safety.hardBlockReasons.firstOrNull().orEmpty().take(40)}" else null
        val priceAge = if (ts.lastPrice > 0.0 && ts.lastPriceUpdate > 0L) nowMs - ts.lastPriceUpdate else Long.MAX_VALUE
        return Candidate8136(
            mint = ts.mint,
            lane = lane,
            selectedSetup = selected,
            regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" },
            stage = fit?.stage?.name.orEmpty(),
            priceAgeMs = priceAge,
            missingInputs = try { pb.dataIncomplete8136(ts, lane, nowMs).orEmpty() } catch (_: Throwable) { emptyList() },
            expectedGrossPct = try { NetEdge8030.expectedGrossPct8136(ts, lane) } catch (_: Throwable) { Double.NaN },
            costPct = try { NetEdge8030.entryCostPct8136(ts, lane) } catch (_: Throwable) { Double.NaN },
            downsidePct = try { com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029(lane) } catch (_: Throwable) { Double.NaN },
            setups = setups,
            cell = cell,
            timing = timing,
            stageRecord = stageRecord,
            losingTactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, lane) } catch (_: Throwable) { null },
            hotLaunch = launch != null && launch.verdict != FreshLaunchSelector7737.LaunchVerdict.NOT_FRESH,
            safetyRefusal = safety,
            structuralRisk = if (fit?.stage == com.lifecyclebot.engine.TokenMetricStageRouter.Stage.RUG_PRONE) "STAGE_RUG_PRONE"
                else launch?.takeIf { it.verdict == FreshLaunchSelector7737.LaunchVerdict.REFUSED }?.why,
        )
    }
}
