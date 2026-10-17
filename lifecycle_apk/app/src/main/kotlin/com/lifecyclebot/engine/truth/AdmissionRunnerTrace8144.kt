package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8144 §EVERY_MISSED_RUNNER_CARRIES_ITS_DECISION_TRACE.
 *
 * 5.0.8142 detected four 4x+ runners (+1,315%, +1,070%, +918%, +591%) and captured none, and the runner report
 * (RunnerCapture8017) could say only the last refusal label. Whether each miss was a correct refusal on what the bot
 * knew at the time, or a refusal the bot's own evidence contradicted, cannot be measured in the repo — the labels live
 * on the device. This is the tooling that measures it there:
 *
 *  (a) Recorder. While the bot judges a mint it keeps a compact early record (first native read of every lane in
 *      scope, birth evidence and liquidity at that read, the setup it would trade as, the first admission and FDG
 *      verdicts, the sniper hand-off state, and the first executable window — priced, with depth and route evidence,
 *      and the round trip charged there, measured or modelled). When the forward labeler reports a mint passing the
 *      4x runner bar, that record is frozen into a [Trace8144], listed on the runner-capture status line and written to
 *      an exportable JSONL file ([FILE_8144] in the app's files dir).
 *  (b) Replay. [classify8144] is pure: given a trace it says whether the miss was CORRECT_AT_DECISION (hard safety, a
 *      real refusal on a real reading, a proven negative edge, no qualified lane), WRONGLY_REFUSED (the decision-time
 *      evidence contradicts the refusing gate: a known birth refused as hydrating, an unread pool refused as low, a
 *      hand-off nobody evaluated while a lane qualified, a generic prior over a qualified specialist, an admitted buy
 *      that never executed), DATA_GAP (the defer was right on missing data; the data path failed), NOT_EVALUATED, or
 *      CAPTURED — always naming the refusing gate.
 * Nothing here changes a decision; it records and classifies.
 */
object AdmissionRunnerTrace8144 {

    enum class Miss8144 { CAPTURED, CORRECT_AT_DECISION, WRONGLY_REFUSED, DATA_GAP, NOT_EVALUATED }

    data class Verdict8144(val miss: Miss8144, val gate: String)

    /** One runner's compact decision trace. Times epoch ms (0 = not observed); "" = not observed; NaN = unknown. */
    data class Trace8144(
        val mint: String,
        val symbol: String,
        val lane: String,
        val peakPct: Double,
        val detectedAtMs: Long,
        val firstSeenMs: Long,
        val source: String,
        /** Birth at the first decision: KNOWN / PROVEN_OLDER_THAN / UNKNOWN, its source, and the (min) age then. */
        val birthKind: String,
        val birthSource: String,
        val birthAgeMs: Long,
        /** Liquidity read at the first decision; 0 = unread. */
        val liquidityUsd: Double,
        val nativeAtMs: Long,
        /** "LANE:Y|N:score:reason" per lane in scope at the first native read. */
        val natives: String,
        /** Lanes natively qualified at the first read (comma separated). */
        val qualified: String,
        val setup: String,
        val admission: String,
        val fdgLane: String,
        val fdgVerdict: String,
        val handoff: String,
        val refusedBy: String,
        val downstream: String,
        val markPx: Double,
        val windowAtMs: Long,
        val windowCostPct: Double,
        val windowCostMeasured: Boolean,
        /** Route evidence at the window: CURVE (live pump curve key) / POOL (real pool address) / "" none observed. */
        val route: String,
        val held: Boolean,
        /** V5.0.8153 — the LAST binding refusal (live edge gate or FDG) as the gate itself named it; "" = last verdict admitted. */
        val binding: String = "",
        /** V5.0.8153 — the decision-time evidence at that refusal: lane, liquidity read, strategy identity / tier, move basis. */
        val bindingEvidence: String = "",
        /** V5.0.8153 — the strategy identity CandidateAdmission8136 last decided, with its evidence basis. */
        val identity: String = "",
    )

    // ── (b) replay: pure ──

    private val SAFETY_8144 = listOf("RUG", "FREEZE", "HONEYPOT", "MINT_AUTH", "HARD_BLOCK", "SAFETY", "TOXIC", "BLACKLIST",
        "SELLAB", "SCAM", "HOSTILE")
    private val PROVEN_NEGATIVE_8144 = listOf("SETUP_RECORD_LOSING", "PROVEN_LOSING", "TACTIC_RECORD_LOSING", "TIMING_ADVERSE",
        "EXECUTION_ECONOMICS", "NET_EDGE", "LOSING")
    private val GENERIC_PRIOR_8144 = listOf("GENERIC_SCORE_FLOOR", "SCORE_FLOOR", "NO_SUPPORTED_EDGE", "LOW_CONFIDENCE")

    private fun has8144(s: String, keys: List<String>): String? = keys.firstOrNull { s.uppercase().contains(it) }

    /**
     * Pure: the gate that stopped the runner — the binding refusal recorded at decision time (V5.0.8153), else the labeler's
     * refusal, else the FDG refusal, else the admission refusal.
     */
    internal fun refusingGate8144(t: Trace8144): String = when {
        t.binding.isNotBlank() -> t.binding
        t.refusedBy.isNotBlank() -> t.refusedBy
        t.fdgVerdict.isNotBlank() && !t.fdgVerdict.startsWith("ALLOW") -> t.fdgVerdict
        t.admission.isNotBlank() && !t.admission.startsWith("ADMIT") -> t.admission
        else -> ""
    }

    /** Pure: was this miss right on what the bot knew at decision time, and which gate decided it. */
    fun classify8144(t: Trace8144): Verdict8144 {
        if (t.held) return Verdict8144(Miss8144.CAPTURED, "")
        val gate = refusingGate8144(t)
        val all = listOf(gate, t.fdgVerdict, t.admission, t.downstream).joinToString(" ")
        has8144(all, SAFETY_8144)?.let { return Verdict8144(Miss8144.CORRECT_AT_DECISION, "SAFETY:$it") }
        if (t.nativeAtMs <= 0L && t.fdgVerdict.isBlank() && t.admission.isBlank() && gate.isBlank()) {
            return Verdict8144(Miss8144.NOT_EVALUATED, "NEVER_EVALUATED")
        }
        val g = gate.uppercase()
        val qualified = t.qualified.isNotBlank()
        if (g.contains("HANDOFF") || g.contains("TOO_OLD")) {
            if (!t.handoff.startsWith(AdmissionHandoff8144.State8144.ARRIVED_EVALUATED.name)) {
                return if (qualified) Verdict8144(Miss8144.WRONGLY_REFUSED, "HANDOFF_NOT_EVALUATED:${t.handoff.ifBlank { "NONE" }.take(60)}")
                else Verdict8144(Miss8144.CORRECT_AT_DECISION, "HANDOFF_NO_QUALIFIED_RECEIVER:$gate")
            }
            // Arrived: the receiver's own verdict is the refusing gate; judge that instead (an FDG allow is an admit).
            val receiverVerdict = t.handoff.substringAfter(':').substringAfter(':').ifBlank { "RECEIVER_REFUSED" }
            return if (receiverVerdict.startsWith("FDG_ALLOW")) classify8144(t.copy(refusedBy = "", fdgVerdict = "ALLOW", admission = "", handoff = "", binding = ""))
            else classify8144(t.copy(refusedBy = receiverVerdict, handoff = "", binding = ""))
        }
        if (g.contains("BIRTH") || g.contains("AGE_UNKNOWN")) {
            return if (t.birthKind == AdmissionBirth8144.Kind8144.KNOWN.name) Verdict8144(Miss8144.WRONGLY_REFUSED, "BIRTH_KNOWN_BUT_REFUSED:$gate")
            else Verdict8144(Miss8144.DATA_GAP, "BIRTH_UNKNOWN:$gate")
        }
        if (g.contains("LIQ")) {
            return when {
                g.contains("UNHYDRATED") || g.contains("DEFER") || g.contains("UNKNOWN") -> Verdict8144(Miss8144.DATA_GAP, "LIQUIDITY_UNREAD:$gate")
                !(t.liquidityUsd > 0.0) || evidence8153(t, "liq") == "UNKNOWN" -> Verdict8144(Miss8144.WRONGLY_REFUSED, "LIQ_UNREAD_REFUSED_AS_LOW:$gate")
                else -> Verdict8144(Miss8144.CORRECT_AT_DECISION, "LIQ_REAL_BELOW_FLOOR:$gate")
            }
        }
        if (g.contains("DATA_INCOMPLETE") || g.startsWith("DEFER")) return Verdict8144(Miss8144.DATA_GAP, "DATA_INCOMPLETE:$gate")
        // V5.0.8153 — "too slow" judged on a sparse range (two candles of a fresh launch) was not a measurement of the move.
        if (g.contains("COIN_TOO_SLOW") && evidence8153(t, "move").startsWith("RANGE_SPARSE")) {
            return Verdict8144(Miss8144.WRONGLY_REFUSED, "TOO_SLOW_ON_SPARSE_RANGE:$gate")
        }
        has8144(g, PROVEN_NEGATIVE_8144)?.let { return Verdict8144(Miss8144.CORRECT_AT_DECISION, "PROVEN_NEGATIVE:$gate") }
        has8144(g, GENERIC_PRIOR_8144)?.let {
            return if (qualified) Verdict8144(Miss8144.WRONGLY_REFUSED, "GENERIC_PRIOR_OVER_QUALIFIED:$gate")
            else Verdict8144(Miss8144.CORRECT_AT_DECISION, "GENERIC_PRIOR_NO_QUALIFIED_LANE:$gate")
        }
        if (gate.isBlank()) {
            val d = t.downstream.uppercase()
            return when {
                d.contains("CAPITAL") || d.contains("BALANCE") -> Verdict8144(Miss8144.CORRECT_AT_DECISION, "ADMITTED>NO_CAPITAL:${t.downstream}")
                t.downstream.isNotBlank() -> Verdict8144(Miss8144.WRONGLY_REFUSED, "ADMITTED>${t.downstream}")
                else -> Verdict8144(Miss8144.WRONGLY_REFUSED, "ADMITTED>NO_BUY_ATTEMPT")
            }
        }
        // V5.0.8153 — a thin cell refused while the coin's own strategy was evidenced on its own record (StrategyWatch8153).
        if (g.contains("WATCH_FIRST") && g.contains("THIN_CELL") && StrategyRank8153.evidenced8153(
                StrategyRank8153.Tier8153.values().firstOrNull { it.name == evidence8153(t, "tier") })) {
            return Verdict8144(Miss8144.WRONGLY_REFUSED, "WATCH_FIRST_OVER_SPECIFIC_EVIDENCE:$gate")
        }
        if (g.contains("NOT_PRIMARY") || g.contains("SHADOW")) {
            return if (qualified) Verdict8144(Miss8144.WRONGLY_REFUSED, "ELECTION_SHADOWED:$gate")
            else Verdict8144(Miss8144.CORRECT_AT_DECISION, "SHADOW_NO_QUALIFIED_LANE:$gate")
        }
        return if (qualified) Verdict8144(Miss8144.WRONGLY_REFUSED, "QUALIFIED_LANE_STOPPED:$gate")
        else Verdict8144(Miss8144.CORRECT_AT_DECISION, "NO_LANE_QUALIFIED:$gate")
    }

    /** Pure (V5.0.8153): one "key=value" field of a trace's binding evidence ("" when absent). */
    internal fun evidence8153(t: Trace8144, key: String): String =
        t.bindingEvidence.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()

    /** Pure (V5.0.8153): the binding evidence line from its parts (space separated key=value, no spaces in values). */
    fun evidenceLine8153(lane: String, liquidityUsd: Double, identity: String, tier: String, move: String): String =
        listOf("lane=" + lane.ifBlank { "-" },
            "liq=" + (if (StrategyMove8153.liquidityRead8153(liquidityUsd) == "KNOWN") "%.0f".format(liquidityUsd) else "UNKNOWN"),
            "setup=" + identity.ifBlank { "-" }, "tier=" + tier.ifBlank { "-" }, "move=" + move.ifBlank { "-" })
            .joinToString(" ") { it.replace(' ', '_') }

    /** Pure: one summary line over classified traces — counts per class and per refusing gate stem. */
    fun summary8144(traces: List<Trace8144>): String {
        if (traces.isEmpty()) return "runners=0"
        val v = traces.map { classify8144(it) }
        val by = Miss8144.values().joinToString(" ") { m -> "${if (m == Miss8144.CAPTURED) "held_entry" else m.name.lowercase()}=${v.count { it.miss == m }}" }
        val wrong = v.filter { it.miss == Miss8144.WRONGLY_REFUSED }.groupingBy { it.gate.substringBefore(':') }.eachCount()
            .entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        return "runners=${traces.size} $by wrongBy{$wrong}"
    }

    // ── JSONL export / import (the device file is the replay input) ──

    fun toJson8144(t: Trace8144): JSONObject = JSONObject()
        .put("mint", t.mint).put("symbol", t.symbol).put("lane", t.lane).put("peakPct", fin8144(t.peakPct))
        .put("detectedAtMs", t.detectedAtMs).put("firstSeenMs", t.firstSeenMs).put("source", t.source)
        .put("birthKind", t.birthKind).put("birthSource", t.birthSource).put("birthAgeMs", t.birthAgeMs)
        .put("liquidityUsd", fin8144(t.liquidityUsd)).put("nativeAtMs", t.nativeAtMs).put("natives", t.natives)
        .put("qualified", t.qualified).put("setup", t.setup).put("admission", t.admission).put("fdgLane", t.fdgLane)
        .put("fdgVerdict", t.fdgVerdict).put("handoff", t.handoff).put("refusedBy", t.refusedBy).put("downstream", t.downstream)
        .put("markPx", fin8144(t.markPx)).put("windowAtMs", t.windowAtMs).put("windowCostPct", fin8144(t.windowCostPct))
        .put("windowCostMeasured", t.windowCostMeasured).put("route", t.route).put("held", t.held)
        .put("binding", t.binding).put("bindingEvidence", t.bindingEvidence).put("identity", t.identity)

    private fun fin8144(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

    internal fun fromJson8144(o: JSONObject): Trace8144 = Trace8144(
        mint = o.optString("mint", ""), symbol = o.optString("symbol", ""), lane = o.optString("lane", ""),
        peakPct = o.optDouble("peakPct", Double.NaN), detectedAtMs = o.optLong("detectedAtMs", 0L),
        firstSeenMs = o.optLong("firstSeenMs", 0L), source = o.optString("source", ""),
        birthKind = o.optString("birthKind", ""), birthSource = o.optString("birthSource", ""), birthAgeMs = o.optLong("birthAgeMs", 0L),
        liquidityUsd = o.optDouble("liquidityUsd", 0.0), nativeAtMs = o.optLong("nativeAtMs", 0L), natives = o.optString("natives", ""),
        qualified = o.optString("qualified", ""), setup = o.optString("setup", ""), admission = o.optString("admission", ""),
        fdgLane = o.optString("fdgLane", ""), fdgVerdict = o.optString("fdgVerdict", ""), handoff = o.optString("handoff", ""),
        refusedBy = o.optString("refusedBy", ""), downstream = o.optString("downstream", ""), markPx = o.optDouble("markPx", Double.NaN),
        windowAtMs = o.optLong("windowAtMs", 0L), windowCostPct = o.optDouble("windowCostPct", Double.NaN),
        windowCostMeasured = o.optBoolean("windowCostMeasured", false), route = o.optString("route", ""), held = o.optBoolean("held", false),
        binding = o.optString("binding", ""), bindingEvidence = o.optString("bindingEvidence", ""), identity = o.optString("identity", ""),
    )

    /** Pure: an exported JSONL file's traces (malformed lines skipped). */
    fun parseJsonl8144(text: String): List<Trace8144> = text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
        .mapNotNull { line -> try { fromJson8144(JSONObject(line)) } catch (_: Throwable) { null } }.toList()

    // ── (a) recorder: runtime ──

    internal const val FILE_8144 = "admission_runner_traces_8144.jsonl"
    private const val MAX_EARLY_8144 = 4_000
    private const val MAX_TRACES_8144 = 200

    private class Early8144(val firstAtMs: Long) {
        @Volatile var nativeAtMs = 0L
        @Volatile var natives = ""
        @Volatile var qualified = ""
        @Volatile var birthKind = ""
        @Volatile var birthSource = ""
        @Volatile var birthAgeMs = 0L
        @Volatile var liquidityUsd = 0.0
        @Volatile var setup = ""
        @Volatile var admission = ""
        @Volatile var fdgLane = ""
        @Volatile var fdgVerdict = ""
        @Volatile var handoff = ""
        @Volatile var windowAtMs = 0L
        @Volatile var windowCostPct = Double.NaN
        @Volatile var windowCostMeasured = false
        @Volatile var route = ""
        @Volatile var binding = ""
        @Volatile var bindingEvidence = ""
        @Volatile var identity = ""
    }

    private val early = ConcurrentHashMap<String, Early8144>()
    private val traces = ConcurrentHashMap<String, Trace8144>()

    private fun earlyFor8144(mint: String, nowMs: Long): Early8144? {
        if (mint.isBlank()) return null
        early[mint]?.let { return it }
        if (early.size >= MAX_EARLY_8144) {
            try { early.entries.sortedBy { it.value.firstAtMs }.take(MAX_EARLY_8144 / 10).forEach { early.remove(it.key) } } catch (_: Throwable) {}
        }
        return early.getOrPut(mint) { Early8144(nowMs) }
    }

    private fun tokenOf8144(mint: String): com.lifecyclebot.data.TokenState? =
        try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null }

    /** AdmissionHandoff8144.onNativeSnapshot8144: every lane's native read; the first read is the decision-time record. */
    fun noteNative8144(mint: String, atMs: Long, reads: Collection<AdmissionHandoff8144.LaneRead8144>) {
        val e = earlyFor8144(mint, atMs) ?: return
        val ts = tokenOf8144(mint)
        if (e.nativeAtMs == 0L && reads.isNotEmpty()) {
            e.nativeAtMs = atMs
            e.natives = reads.sortedByDescending { it.score }.take(8)
                .joinToString(";") { "${it.lane}:${if (it.eligible) "Y" else "N"}:${it.score}:${it.reason.take(28)}" }
            e.qualified = reads.filter { it.eligible && it.lane.uppercase() != "CORE" }.joinToString(",") { it.lane }
            if (ts != null) {
                e.liquidityUsd = ts.lastLiquidityUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                val canon = try { CanonicalTokenBirthTime7440.resolve(mint, atMs) } catch (_: Throwable) { null }
                if (canon != null) {
                    e.birthKind = AdmissionBirth8144.Kind8144.KNOWN.name; e.birthSource = canon.source.name
                    e.birthAgeMs = (atMs - canon.birthMs).coerceAtLeast(0L)
                } else {
                    val r = AdmissionBirth8144.readFor8144(ts, atMs)
                    e.birthKind = r.kind.name; e.birthSource = r.source; e.birthAgeMs = r.minAgeMs
                }
                val lane = reads.firstOrNull { it.eligible && it.lane.uppercase() != "CORE" }?.lane ?: reads.first().lane
                e.setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, lane, atMs).orEmpty() } catch (_: Throwable) { "" }
            }
        }
        if (e.windowAtMs == 0L && ts != null) noteWindow8144(e, ts, reads, atMs)
    }

    /** The first executable window: a price, observed depth and route evidence, with the round trip charged there. */
    private fun noteWindow8144(e: Early8144, ts: com.lifecyclebot.data.TokenState, reads: Collection<AdmissionHandoff8144.LaneRead8144>, atMs: Long) {
        // V5.0.8153 — a live pump curve is depth and route evidence of its own: an unread pool figure does not hide its window.
        if (!(ts.lastPrice > 0.0)) return
        val m = ts.mint
        val graduated = (try { ts.tokenMap.migratedOrGraduated } catch (_: Throwable) { false }) ||
            (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(m) } catch (_: Throwable) { false })
        val curve = !graduated && (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(m) != null } catch (_: Throwable) { false })
        val pool = try {
            ts.tokenMap.pairAddress.ifBlank { ts.tokenMap.poolAddress }.let { it.isNotBlank() && !it.contains("MINT_ROUTE", ignoreCase = true) }
        } catch (_: Throwable) { false }
        val route = when { curve -> "CURVE"; pool -> "POOL"; else -> return }
        if (route != "CURVE" && !(ts.lastLiquidityUsd > 0.0)) return
        val lane = reads.firstOrNull { it.eligible && it.lane.uppercase() != "CORE" }?.lane ?: "SHITCOIN"
        e.windowAtMs = atMs
        e.route = route
        e.windowCostPct = try { NetEdge8030.entryCostPct8136(ts, lane) } catch (_: Throwable) { Double.NaN }
        e.windowCostMeasured = try { CostLedger7962.measuredCostPct7962(lane, NetEdge8030.DEFAULT_TICKET_SOL) != null } catch (_: Throwable) { false }
    }

    /** CandidateAdmission8136.assess8136: the first terminal admission (a later one replaces only a data deferral). */
    fun noteAdmission8144(mint: String, lane: String, terminal: String, reason: String) {
        val e = earlyFor8144(mint, System.currentTimeMillis()) ?: return
        if (e.admission.isBlank() || e.admission.startsWith(CandidateAdmission8136.Terminal8136.DEFER_DATA.name)) {
            e.admission = "$terminal:$reason@$lane".take(90)
        }
    }

    /** AdmissionHandoff8144.onFdgVerdict8144: the first FinalDecisionGate verdict for this mint. */
    fun noteFdg8144(mint: String, lane: String, allow: Boolean, reason: String?, atMs: Long) {
        val e = earlyFor8144(mint, atMs) ?: return
        // V5.0.8153 — the FDG verdict is the last word of a cycle: it is the binding gate (or clears it on an allow).
        noteBindingOf8153(e, tokenOf8144(mint), lane, if (allow) null else reason.orEmpty().ifBlank { "FDG_REFUSED" }, atMs)
        if (e.fdgVerdict.isNotBlank()) return
        e.fdgLane = lane
        e.fdgVerdict = if (allow) "ALLOW" else "REFUSED:${reason.orEmpty().take(60)}"
    }

    /** V5.0.8153 — LiveEdgeGate7877.observeTail7996: the live edge gate's verdict for [ts] (null [gate] = admitted). */
    fun noteBinding8153(ts: com.lifecyclebot.data.TokenState, lane: String, gate: String?, atMs: Long) {
        val e = earlyFor8144(ts.mint, atMs) ?: return
        noteBindingOf8153(e, ts, lane, gate, atMs)
    }

    private fun noteBindingOf8153(e: Early8144, ts: com.lifecyclebot.data.TokenState?, lane: String, gate: String?, atMs: Long) {
        if (gate.isNullOrBlank()) { e.binding = ""; e.bindingEvidence = ""; return }
        e.binding = gate.take(90)
        e.bindingEvidence = if (ts == null) "lane=${lane.ifBlank { "-" }}" else evidenceLine8153(
            lane, ts.lastLiquidityUsd, StrategyIdentity8153.basisFor8153(ts.mint, CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }, atMs,
                if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE",
                com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(ts.mint)),
            (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedTier8153(ts, lane, atMs)?.name } catch (_: Throwable) { null }).orEmpty(),
            NetEdge8030.moveBasis8153(ts.mint),
        ).take(240)
    }

    /** V5.0.8153 — CandidateAdmission8136: the strategy identity it decided for [mint] and the evidence it rests on. */
    fun noteIdentity8153(mint: String, setup: String?, basis: String) {
        val e = earlyFor8144(mint, System.currentTimeMillis()) ?: return
        e.identity = "${setup ?: "-"}(${basis.take(60)})"
    }

    /** AdmissionHandoff8144: the sniper hand-off's latest state. */
    fun noteHandoff8144(mint: String, state: String) {
        val e = earlyFor8144(mint, System.currentTimeMillis()) ?: return
        e.handoff = state.take(90)
    }

    // ── V5.0.8169 §A_RUNNER_RECORD_SURVIVES_A_RESTART ──
    // 5.0.8168 (9 min uptime): "NO_RUNNER_TRACES_CAPTURED" and runners(4x+,24h)=0. The traces were written to [FILE_8144]
    // but never read back, and the 24-hour runner list (RunnerCapture8017) lived only in memory, so every restart (the
    // owner's logs are minutes of uptime) erased the runners — caught and missed alike — and the first new capture
    // rewrote the file with only itself. The file is now restored once, before anything reads or rewrites it, and the
    // runner list is rebuilt from it; a frozen decision-time record is never overwritten by a later one.
    private val restoreTried8169 = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var restored8169 = 0

    /** Pure: the traces worth restoring from an exported file — real mints, plausible detection times, newest per mint. */
    fun restorable8169(list: List<Trace8144>, nowMs: Long, max: Int = MAX_TRACES_8144): List<Trace8144> =
        list.filter { it.mint.isNotBlank() && it.detectedAtMs > 0L && it.detectedAtMs <= nowMs + 60_000L }
            .sortedByDescending { it.detectedAtMs }.distinctBy { it.mint }.take(max)

    /** Once per process, as soon as the app context exists: the exported traces back into memory and the runner list. */
    internal fun restore8169(nowMs: Long = System.currentTimeMillis()) {
        if (restoreTried8169.get()) return
        val ctx = try { com.lifecyclebot.AATEApp.appContextOrNull() } catch (_: Throwable) { null } ?: return
        if (!restoreTried8169.compareAndSet(false, true)) return
        try {
            val f = File(ctx.filesDir, FILE_8144)
            if (!f.exists()) return
            val list = restorable8169(parseJsonl8144(f.readText()), nowMs)
            var n = 0
            for (t in list) if (traces.putIfAbsent(t.mint, t) == null) n++
            restored8169 = n
            RunnerCapture8017.restore8169(list, nowMs)
            try { PipelineHealthCollector.labelInc("RUNNER_TRACE_RESTORED_8169") } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /** RunnerCapture8017: [mint] passed the 4x runner bar — freeze its decision trace and export it. */
    fun capture8144(mint: String, symbol: String, lane: String, startPx: Double, peakPct: Double, refusedBy: String,
                    downstream: String?, held: Boolean, nowMs: Long = System.currentTimeMillis()) {
        restore8169(nowMs)  // V5.0.8169 — before the export below rewrites the file
        if (mint.isBlank() || traces.containsKey(mint)) return
        val e = early[mint]
        val ts = tokenOf8144(mint)
        val hand = AdmissionHandoff8144.handoffOf8144(mint, nowMs)
        val t = Trace8144(
            mint = mint, symbol = symbol, lane = lane, peakPct = peakPct, detectedAtMs = nowMs,
            firstSeenMs = ts?.addedToWatchlistAt ?: 0L, source = ts?.source.orEmpty(),
            birthKind = e?.birthKind.orEmpty().ifBlank { AdmissionBirth8144.Kind8144.UNKNOWN.name }, birthSource = e?.birthSource.orEmpty(),
            birthAgeMs = e?.birthAgeMs ?: 0L, liquidityUsd = e?.liquidityUsd ?: 0.0, nativeAtMs = e?.nativeAtMs ?: 0L,
            natives = e?.natives.orEmpty(), qualified = e?.qualified.orEmpty(), setup = e?.setup.orEmpty(),
            admission = e?.admission.orEmpty(), fdgLane = e?.fdgLane.orEmpty(), fdgVerdict = e?.fdgVerdict.orEmpty(),
            handoff = hand?.let { "${it.state.name}:${it.receiver ?: it.named ?: "-"}:${it.verdict.take(50)}" } ?: e?.handoff.orEmpty(),
            refusedBy = refusedBy, downstream = downstream.orEmpty(), markPx = startPx,
            windowAtMs = e?.windowAtMs ?: 0L, windowCostPct = e?.windowCostPct ?: Double.NaN,
            windowCostMeasured = e?.windowCostMeasured ?: false, route = e?.route.orEmpty(), held = held,
            binding = e?.binding.orEmpty(), bindingEvidence = e?.bindingEvidence.orEmpty(), identity = e?.identity.orEmpty(),
        )
        if (traces.size >= MAX_TRACES_8144) traces.entries.minByOrNull { it.value.detectedAtMs }?.key?.let { traces.remove(it) }
        traces[mint] = t
        try { PipelineHealthCollector.labelInc("RUNNER_TRACE_8144_${classify8144(t).miss.name}") } catch (_: Throwable) {}
        export8144()
    }

    /** RunnerCapture8017: a later label named a better refusal, a downstream stop, or the bot now holds it. */
    fun update8144(mint: String, refusedBy: String, downstream: String?, held: Boolean) {
        restore8169()  // V5.0.8169
        val t = traces[mint] ?: return
        val next = t.copy(refusedBy = refusedBy, downstream = downstream.orEmpty(), held = t.held || held)
        if (next == t) return
        traces[mint] = next
        export8144()
    }

    /** The traces held now, newest first. */
    internal fun traces8144(): List<Trace8144> {
        restore8169()  // V5.0.8169 — the report reads the restored record too
        return traces.values.sortedByDescending { it.detectedAtMs }
    }

    /** Rewrites the exportable JSONL file (one trace per line) and returns it, or null when the app context is absent. */
    internal fun export8144(): File? = try {
        val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
        if (ctx == null) null else {
            val f = File(ctx.filesDir, FILE_8144)
            f.writeText(traces8144().joinToString("\n") { toJson8144(it).toString() } + "\n")
            f
        }
    } catch (_: Throwable) { null }

    fun statusLine8144(): String {
        val list = traces8144()
        val rows = list.take(6).joinToString(" · ") { t ->
            val v = classify8144(t)
            "${t.symbol.take(10)} observedPeak=+${t.peakPct.toInt()}% ${if (v.miss == Miss8144.CAPTURED) "HELD_ENTRY" else v.miss.name}${if (v.gate.isNotBlank()) "(${v.gate.take(48)})" else ""}"
        }
        return "trace8144[${summary8144(list)} file=$FILE_8144 restored8169=$restored8169]${if (rows.isNotBlank()) " | $rows" else ""} | " +
            "${AdmissionHandoff8144.statusLine8144()} | ${AdmissionBirth8144.statusLine8144()}"
    }
}
