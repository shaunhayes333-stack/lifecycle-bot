package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8326 §ENTRY_8114_MODE — owner (explicit): "Make 8114 live now."
 *
 * Builds 8113/8114 (361727241 / 379ad2fa0) are the last ones the owner saw make money (about 40 % wins, cash doubled).
 * Their trading source is identical. Since then CandidateAdmission8136 was put in front of the 8114 entry path and many
 * later doors were added (8188 MinPromotion, 8290 LiveAdmissionProof, 8295/8296 governors, 8298 restore, 8310 selection
 * doors, 8323 cheat sheet, 8243/8259/8279 rules). With this mode ON (default ON, persisted setting with a Settings
 * switch) the LIVE entry decision of the meme lanes in [LANES_8326] is the 8114 decision (Entry8114Gate8326):
 *  - gates added after 8114 do not refuse (each refusal they would have made is counted ENTRY8114_BYPASSED_<gate>);
 *  - gates that changed since 8114 run on their 8114 semantics inside [inLegacy8326] (the evidence reads they use
 *    answer the 8114 way while the flag is set: raw label cells, raw setup records, no realised-shortfall offset);
 *  - gates unchanged since 8114 are unchanged.
 * Always binding whatever the mode ([hardSafety8326]): hard rug / authority / freeze / Token-2022 / sellability /
 * honeypot refusals, Mayhem, blacklist, liquidity exit-safety floors, identity / price-basis proofs, route minimum and
 * wallet / balance proofs, duplicate-order protection, kill switch / drawdown HALT, and the economic refusal (NetEdge at
 * the owner's 1.5x round trip). Exits are untouched: only the ENTRY decision reverts.
 *
 * Every evaluated coin is decided BOTH ways (the 8114 verdict is authoritative, the current full verdict is the shadow);
 * Entry8114Shadow8326 labels the coin with both and reports per lane what each set did 60 minutes later.
 */
object Entry8114Mode8326 {
    const val SETTING_KEY_8326 = "entry_8114_mode_enabled_8326"
    const val DEFAULT_ON_8326 = true
    const val EVAL_FAILED_8326 = "ENTRY8114_EVAL_FAILED_8326"

    /** LIVE meme lanes the mode covers (canonical names). */
    val LANES_8326: Set<String> = setOf(
        "SHITCOIN", "SHITCOIN_EXPRESS", "MOONSHOT", "EXPRESS", "MANIPULATED", "PROJECT_SNIPER", "QUALITY", "BLUECHIP",
        "DIP_HUNTER", "TREASURY", "CASHGEN", "CYCLIC", "CORE",
    )

    @Volatile private var enabled8326: Boolean = DEFAULT_ON_8326

    /** ConfigStore load / save publish the persisted setting here. */
    fun noteConfig8326(on: Boolean) {
        if (enabled8326 != on) try { PipelineHealthCollector.labelInc(if (on) "ENTRY8114_MODE_ON_8326" else "ENTRY8114_MODE_OFF_8326") } catch (_: Throwable) {}
        enabled8326 = on
    }

    fun enabled8326(): Boolean = enabled8326

    fun canon8326(lane: String?): String =
        (try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { "" }).uppercase().ifBlank { lane.orEmpty().trim().uppercase() }

    private fun laneCovered8326(lane: String?): Boolean = canon8326(lane) in LANES_8326

    /** Pure over the setting: is the 8114 entry decision authoritative for a [paper] = false entry in [lane]? */
    // V5.0.8336 — while EvidenceHold8336 rehearses, PAPER meme entries are decided the same way as LIVE ones, so the paper
    // record the hold reads is a record of the live entry policy (before 8336 paper skipped the live doors entirely).
    fun liveActive8326(lane: String?, paper: Boolean): Boolean =
        enabled8326 && laneCovered8326(lane) && (!paper || (EvidenceHold8336.enabled8336 && EvidenceHold8336.rehearsal8336))

    fun liveActiveNow8326(lane: String?): Boolean =
        liveActive8326(lane, try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true })

    // ── scopes ──

    private val legacy8326 = ThreadLocal<Boolean>()
    private val current8326 = ThreadLocal<Boolean>()

    /** True while the 8114 verdict is being computed on this thread: changed evidence reads answer the 8114 way. */
    fun legacyScope8326(): Boolean = legacy8326.get() == true

    /** True while the CURRENT (shadow) verdict is being computed on this thread. */
    private fun computingCurrent8326(): Boolean = current8326.get() == true

    fun <T> inLegacy8326(block: () -> T): T {
        val prev = legacy8326.get()
        legacy8326.set(true)
        try { return block() } finally { if (prev == null) legacy8326.remove() else legacy8326.set(prev) }
    }

    fun <T> inCurrent8326(block: () -> T): T {
        val prev = current8326.get()
        current8326.set(true)
        try { return block() } finally { if (prev == null) current8326.remove() else current8326.set(prev) }
    }

    /** LiveEdgeGate7877.liveRefusal hands a live meme entry here (once: not while the shadow or 8114 verdict is computed). */
    fun routes8326(lane: String, paper: Boolean): Boolean =
        liveActive8326(lane, paper) && !computingCurrent8326() && !legacyScope8326()

    /** In mode the FDG helpers read no CandidateAdmission8136 decision (8114 had none). Otherwise [admission] unchanged. */
    fun admission8326(lane: String?, paper: Boolean, admission: CandidateAdmission8136.Decision8136?): CandidateAdmission8136.Decision8136? {
        if (admission == null || !liveActive8326(lane, paper)) return admission
        noteFdg8326("ADMISSION_NOT_CONSULTED", lane)
        return null
    }

    // ── the decision ──

    private val admits8326 = ConcurrentHashMap<String, Long>()   // mint|lane -> last 8114 ADMIT
    private const val ADMIT_TTL_MS_8326 = 120_000L

    /**
     * The live entry verdict for [ts] in [lane] while the mode is ON: the current full verdict is computed first (shadow,
     * it files its own assessment), then the 8114 verdict, which is returned unless the current verdict is a hard-safety
     * / economic refusal ([hardSafety8326]). The final assessment is filed last, so FDG / plan / sizing read it.
     */
    fun decide8326(
        ts: TokenState, lane: String, nowMs: Long, candidateVersion: Long,
        candidateAdmission: CandidateAdmission8136.Decision8136?,
    ): String? {
        val l = canon8326(lane)
        // V5.0.8339 — the shadow is computed for the runtime's own book: in a PAPER runtime (8336 rehearsal) paper = true,
        // as the paper runtime decided before 8336; the live chain / wallet doors do not run or bind for a paper entry.
        val paper8336 = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val now = try {
            inCurrent8326 { LiveEdgeGate7877.liveRefusal(ts, lane, Rehearsal8339.shadowPaper8339(paper8336), nowMs, candidateVersion, candidateAdmission) }
        } catch (_: Throwable) { null }
        // V5.0.8342 — in PAPER under rehearsal the gate's label / record doors are advisory (Rehearsal8339.doorBindsNow8342): the
        // doors it passed on advice are collected here and recorded on the paper entry (liveWouldRefuse) for its close.
        val (old, advisories8342) = try { Rehearsal8339.collecting8342 { inLegacy8326 { Entry8114Gate8326.liveVerdict8114(ts, l, nowMs) } } } catch (_: Throwable) {
            Entry8114Gate8326.Verdict8326(EVAL_FAILED_8326, LiveEdgeGate7877.Verdict8083.REFUSE, null) to emptyList<String>()
        }
        // V5.0.8329 — the lane's OWN live closes (3+, mean < 0) are realised money, not a label: they bind in mode.
        // V5.0.8336 — a lane with no paper proof does not trade live (EvidenceHold8336; null in PAPER).
        // In PAPER (rehearsal) the live-losing shut does not bind: a lane shut by its live closes re-earns live by its
        // paper record, so paper must keep trading it (8329's shut could otherwise never reopen).
        // V5.0.8337 — the hold is consulted with the candidate: an exceptional runner opportunity (EvidenceHold8336.exceptional8336,
        // every leg provable now) is taken live through the hold; hard safety / economics / sizing still bind downstream.
        val realised = (if (paper8336) null else try { EntryEconomics8329.laneRealisedRefusal8329(l, nowMs) } catch (_: Throwable) { null })
            ?: try { EvidenceHold8336.refusal8336(ts, l, paper8336, nowMs) } catch (_: Throwable) { null }
        val final = finalVerdict8326(old.reason, now, realised)
        if (now != null && final == null) noteBypass8326(gateKey8326(now), l)
        if (old.reason != null && final == now && now != null && now != old.reason) try { PipelineHealthCollector.labelInc("ENTRY8114_CURRENT_HARD_REFUSAL_BINDS_8326_$l") } catch (_: Throwable) {}
        fileFinal8326(ts, l, old, final, nowMs)
        if (paper8336 && final == null) try { Rehearsal8339.notePaperAdmit8342(ts, l, advisories8342, nowMs) } catch (_: Throwable) {}   // V5.0.8342
        try { Entry8114Shadow8326.record8326(ts, l, old.reason, now, final, nowMs) } catch (_: Throwable) {}
        return final
    }

    /**
     * Pure: the 8114 verdict [old] decides, unless the current verdict [now] is a hard-safety / economic refusal, or
     * (V5.0.8329) the lane's realised closes refuse ([realised]: EntryEconomics8329.laneRealisedRefusal8329 — provable, not a label).
     */
    fun finalVerdict8326(old: String?, now: String?, realised: String? = null): String? =
        if (now != null && hardSafety8326(now)) now else (realised ?: old)

    private fun fileFinal8326(ts: TokenState, lane: String, old: Entry8114Gate8326.Verdict8326, final: String?, nowMs: Long) {
        try {
            val key = "${ts.mint}|$lane"
            if (final == null) {
                val factor = try { LiveTruth8064.lanePrior8083(lane, nowMs).sizeFactor } catch (_: Throwable) { 1.0 }
                LiveEdgeGate7877.recordAssessment8083(ts, lane, old.setup, LiveEdgeGate7877.Verdict8083.ADMIT, "ADMIT_8114_8326", factor, nowMs)
                if (!(try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }))  // V5.0.8336 — rehearsal admits are not live admits
                    try { LiveTruth8064.noteLiveAdmit8083(ts.mint, lane, old.setup, nowMs) } catch (_: Throwable) {}
                try { if (com.lifecyclebot.engine.RunnerGrab7967.noteAdmit7970(ts, nowMs)) LiveEdgeGate7877.notePositive7975(ts.mint, nowMs) } catch (_: Throwable) {}
                admits8326[key] = nowMs
                if (admits8326.size > 3_000) admits8326.entries.removeIf { nowMs - it.value > ADMIT_TTL_MS_8326 }
                PipelineHealthCollector.labelInc("ENTRY8114_ADMIT_8326_$lane")
            } else {
                val kind = if (final == old.reason) old.kind else LiveEdgeGate7877.Verdict8083.REFUSE
                LiveEdgeGate7877.recordAssessment8083(ts, lane, old.setup, kind, final, 1.0, nowMs)
                admits8326.remove(key)
                PipelineHealthCollector.labelInc("ENTRY8114_REFUSE_8326_$lane")
            }
        } catch (_: Throwable) {}
    }

    /** Was [mint] admitted by the 8114 decision in [lane] within the assessment window (sizing: route-minimum promotion)? */
    fun admittedNow8326(mint: String, lane: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || !liveActiveNow8326(lane)) return false
        val at = admits8326["$mint|${canon8326(lane)}"] ?: return false
        return nowMs - at in 0L..ADMIT_TTL_MS_8326
    }

    /**
     * Route-minimum promotion (8114: an admitted candidate was promoted to the route minimum). For an 8114-admitted
     * [mint] the post-8114 MinPromotion8188 refusals (loss budget at the minimum 8188/8236, unmeasured edge 8316,
     * defensive drawdown 8259) do not refuse; a MEASURED negative edge at the promoted size (economics) still does.
     * Not admitted, or the mode off: [refusal] unchanged.
     */
    fun promotionFilter8326(mint: String, lane: String?, refusal: String?): String? {
        if (refusal == null) return null
        if (refusal == MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188) return refusal
        if (!admittedNow8326(mint, lane)) return refusal
        noteBypass8326(gateKey8326(refusal), lane)
        return null
    }

    /** [promotionFilter8326] for a sizer that already knows whether the 8114 decision [admitted] the candidate. */
    fun promotionInputFilter8326(admitted: Boolean, lane: String?, refusal: String?): String? {
        if (refusal == null || !admitted || refusal == MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188) return refusal
        noteBypass8326(gateKey8326(refusal), lane)
        return null
    }

    // ── sizing (8114 size path for 8114-admitted live trades) ──

    /**
     * The 8114 quarter-Kelly share for an 8114-admitted [mint] (computed by [compute] inside the legacy scope: best proven
     * setup / cell / kind on the raw records, no 8188 realised / walk-forward cap, no 8296 recent-loss revoke), or null
     * when the candidate is not 8114-admitted (the caller's current read stands).
     */
    fun kelly8114Or8326(mint: String, lane: String, compute: (TokenState) -> Double): Double? {
        if (!admittedNow8326(mint, lane)) return null
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return null
        val f = try { inLegacy8326 { compute(ts) } } catch (_: Throwable) { 0.0 }
        return if (f.isFinite() && f >= 0.0) f else 0.0
    }

    /** 8114: the live expectancy multiplier without the 8296 recency shrink, for an 8114-admitted candidate. */
    fun sizeExpMult8326(admitted: Boolean, expectancyMult: Double, recent: LiveConfidence8296.Recent8296?): Double =
        if (admitted) expectancyMult else LiveConfidence8296.recencyAdjusted8296(expectancyMult, recent)

    private val sizes8326 = ConcurrentHashMap<String, DoubleArray>()   // lane -> [opens, sumSol, lastSol, lastKelly, refused]

    fun noteSize8326(lane: String, open: Boolean, sizeSol: Double, kellyFrac: Double) {
        val row = sizes8326.computeIfAbsent(canon8326(lane)) { DoubleArray(5) }
        synchronized(row) {
            if (open && sizeSol.isFinite() && sizeSol > 0.0) { row[0] += 1.0; row[1] += sizeSol; row[2] = sizeSol } else row[4] += 1.0
            row[3] = if (kellyFrac.isFinite()) kellyFrac else 0.0
        }
    }

    fun sizeLine8326(): String {
        val lanes = sizes8326.entries.sortedBy { it.key }.joinToString(",") { (l, r) ->
            synchronized(r) {
                val avg = if (r[0] > 0.0) r[1] / r[0] else 0.0
                "$l n${r[0].toInt()} avg${"%.4f".format(avg)} last${"%.4f".format(r[2])}SOL kelly${"%.3f".format(r[3])} refused${r[4].toInt()}"
            }
        }.ifBlank { "-" }
        val compound = try { LiveConfidence8296.liveCompoundMultiplier8296() } catch (_: Throwable) { Double.NaN }
        val day = try { LiveRiskPolicy7807.dailyScoreLine8057() } catch (_: Throwable) { "day=-" }
        return "size8114[$lanes] compound=${"%.2f".format(compound)}x $day"
    }

    // ── what always binds ──

    private val HARD_MARKERS_8326 = listOf(
        "SAFETY_REFUSED", "STRUCTURAL_RISK", "RUG", "FREEZE", "MINT_AUTH", "AUTHORITY", "TOKEN_2022", "TOKEN2022", "T22_",
        "HOSTILE", "HONEYPOT", "SELLAB", "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQ_TOO_LOW", "LIQUIDITY_FLOOR",
        "LIQUIDITY_COLLAPSE", "LANE_LIQ", "EXIT_SAFE", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "WALLET_BLIND", "BALANCE",
        "LEDGER", "DUPLICATE", "SINGLE_FLIGHT", "KILL_SWITCH", "HALT", "AT_LAUNCH_CAP", "DEAD_LAUNCH",
        "ECONOMICS", "NET_EDGE_BELOW", "COST_REQUOTE", "ROUTE_MIN", EVAL_FAILED_8326,
    )

    /** Pure: a refusal that binds whatever the mode (safety / correctness / economics, not selection). */
    fun hardSafety8326(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        return HARD_MARKERS_8326.any { r.contains(it) }
    }

    /** Pure: the gate a refusal belongs to, for ENTRY8114_BYPASSED_<gate>. */
    fun gateKey8326(reason: String): String {
        val r = reason.uppercase().trim()
        val body = if (r.startsWith("CANDIDATE_ADMISSION_8136_")) "ADMISSION8136_" + r.removePrefix("CANDIDATE_ADMISSION_8136_") else r
        val m = Regex("^(.*?_\\d{4})(?:_|$)").find(body)
        val head = m?.groupValues?.getOrNull(1) ?: body
        val key = if (body.startsWith("ADMISSION8136_")) {
            Regex("^ADMISSION8136_(.*?_\\d{4})(?:_|$)").find(body)?.groupValues?.getOrNull(1)?.let { "ADMISSION8136_$it" } ?: head
        } else head
        return key.replace(Regex("[^A-Z0-9_]"), "_").take(56)
    }

    // ── counters ──

    private val bypassed8326 = ConcurrentHashMap<String, AtomicLong>()
    private val fdgNotes8326 = ConcurrentHashMap<String, AtomicLong>()

    fun noteBypass8326(gate: String, lane: String?) {
        bypassed8326.computeIfAbsent(gate) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ENTRY8114_BYPASSED_$gate")
            PipelineHealthCollector.labelInc("ENTRY8114_BYPASSED_${gate}_${canon8326(lane)}")
        } catch (_: Throwable) {}
    }

    fun noteFdg8326(what: String, lane: String?) {
        fdgNotes8326.computeIfAbsent(what) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ENTRY8114_FDG_${what}_8326_${canon8326(lane)}") } catch (_: Throwable) {}
    }

    fun bypassCount8326(gate: String): Long = bypassed8326[gate]?.get() ?: 0L

    fun statusLine8326(): String =
        "entry8114[on=$enabled8326 bypassed=" +
            bypassed8326.entries.sortedByDescending { it.value.get() }.take(12).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            " fdg=" + fdgNotes8326.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            " " + (try { sizeLine8326() } catch (_: Throwable) { "size8114[-]" }) +
            " " + (try { Entry8114Shadow8326.statusLine8326() } catch (_: Throwable) { "shadow[-]" }) +
            " " + (try { EntryEconomics8329.statusLine8329() } catch (_: Throwable) { "chase8329[-] promo8329[-] laneShut8329[-]" }) + "]"  // V5.0.8329
}
