package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.8339 §REHEARSAL_HAS_NO_WALLET — 8336 rehearsal decides PAPER meme entries with the LIVE (8114) decision so the
 * paper record measures the live entry policy. Two of the doors that decision runs are not policy but LIVE ACCOUNT
 * proofs, and in a PAPER runtime they refused every paper entry (5.0.8336 runtime smoke: paperBuyTickets=0):
 *   - the former entry replay runs LiveBootRecovery8074.blind8074 — "the wallet cannot be read, refuse the live
 *     buy". A paper book has no wallet; in the smoke JVM / a paper device the wallet is never readable, so the door
 *     refused paper forever — and with it the evidence the hold needs before any lane can go live (a deadlock of the
 *     owner's own 8336 rule).
 *   - LiveEdgeGate7877.liveRefusal computed the shadow verdict with paper = false in a paper runtime, which runs the live
 *     chain / wallet doors (OnChainDepth live requests, CandidateAdmission live prefetch, WALLET_BLIND) and binds their
 *     hard markers; before 8336 the paper runtime never ran them.
 * The rule: the live wallet door binds a LIVE runtime only; the shadow is computed for the runtime's own book. Policy
 * doors (proven setup, timing / tactic records, 8329 realised closes, hard token safety) are untouched: rehearsal stays
 * on, and a paper admit is still never a live admit (canonical verdict publication, 8336).
 */
object Rehearsal8339 {
    const val WALLET_DOOR_SKIPPED_8339 = "ENTRY8114_REHEARSAL_WALLET_DOOR_SKIPPED_8339"

    /** Pure: does the live wallet door (LiveBootRecovery8074.blind8074) bind an entry decided in a runtime that is [live]? */
    fun walletDoorBinds8339(live: Boolean): Boolean = live

    /** [walletDoorBinds8339] for the current runtime; a skipped door is counted. Unknown mode binds (safe side). */
    fun walletDoorBindsNow8339(): Boolean {
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { true }
        val binds = walletDoorBinds8339(live)
        if (!binds) try { PipelineHealthCollector.labelInc(WALLET_DOOR_SKIPPED_8339) } catch (_: Throwable) {}
        return binds
    }

    /** Pure: the book the shadow (current full) verdict is computed for — the runtime's own ([paperRuntime]). */
    fun shadowPaper8339(paperRuntime: Boolean): Boolean = paperRuntime

    // ── V5.0.8342 §EVIDENCE_DOORS_ARE_ADVISORY_IN_PAPER ──
    //
    // 5.0.8341 runtime (PAPER, the owner's "run in PAPER until the evidence hold shows lanes LIVE"): FDG allow=0 block=4325,
    // zero meme paper buys in 23 minutes; LIVE_PROVEN_SETUP_ONLY_8059=1340, PICKED_SETUP_RECORD_NEGATIVE_8094=304,
    // WATCH_FIRST_7994_CELL_NEGATIVE=301, TIMING_RECORD_NEGATIVE_8094=224 ... The hold needs 30 paper closes per meme lane
    // and had none: rehearsal routes PAPER entries through the full live decision, whose LABEL / RECORD doors refuse an
    // entry until the evidence proves — and paper is where that evidence is built (LanePlaybook7907: "paper never
    // refused"). Live could never resume.
    //
    // The rule: in PAPER under rehearsal an EVIDENCE door (a refusal that rests on graded labels / setup, timing or tactic
    // records / lane-label ponds / the cheat sheet / the repeat-loser book) is ADVISORY — the live verdict it would have
    // made is recorded on the paper entry (REHEARSAL_EVIDENCE_DOOR_ADVISORY_8342_<door>, liveWouldRefuse=<door>) and the
    // decision continues through every later door. BINDING in paper as in live: hard safety / structural risk
    // (FdgGate8365.hardSafety8392), the chase rules (CHASING_SPIKE_8065, SWING_LANE_ALREADY_RAN_8054), economics
    // (NET_EDGE_*, NON_ECONOMIC_APPROVAL_7835, SIZE_NOT_EXECUTABLE_7835, EXECUTION_ECONOMICS_8136, CURVE_NOBODY_BOUGHT_8046,
    // the 8320 curve rule), the rebuy lockout, Mayhem, cost priors and the shape / cell edge verdicts: the paper record
    // is then a record of realistic entries. LIVE is untouched: every door binds. Paper results still never size or admit
    // live (8336): the hold's bar is read on ALL paper closes of the lane; the liveAdmit record is shown beside it.
    const val ADVISORY_LABEL_8342 = "REHEARSAL_EVIDENCE_DOOR_ADVISORY_8342"
    const val CLOSE_LABEL_8342 = "REHEARSAL_PAPER_CLOSE_8342"
    const val LIVE_ADMIT_8342 = "LIVE_ADMIT"
    const val LIVE_REFUSE_8342 = "LIVE_REFUSE"
    const val UNTAGGED_8342 = "UNTAGGED"
    private const val PAPER_SOURCE_8342 = "PAPER_REHEARSAL"
    private const val LIVE_SOURCE_8342 = "LIVE"

    /** Evidence (label / record) doors: advisory in PAPER under rehearsal. Matched on the upper-cased refusal. */
    private val EVIDENCE_DOORS_8342: List<String> = listOf(
        "LIVE_PROVEN_SETUP_ONLY_8059", "PICKED_SETUP_RECORD_NEGATIVE_8094", "TIMING_RECORD_NEGATIVE_8094",
        "TACTIC_RECORD_LOSING_8094", "WATCH_FIRST_", "C6_SETUP_DEAD_8082", "REPEAT_LOSER_8022", "PLAYBOOK_",
        "SHEET_AVOID_8323", "CELL_PROVEN_LOSING", "STAGE_PROVEN_LOSING", "SETUP_PROVEN_LOSING",
        "LIVE_LANE_LABELS_NOT_POSITIVE_8325",
    )

    /** Always binding, whatever the book (checked before the evidence list, so a mixed reason binds). */
    private val BINDING_MARKERS_8342: List<String> = listOf(
        "CHASING_SPIKE_8065", "ALREADY_RAN_8054", "NET_EDGE", "NON_ECONOMIC", "SIZE_NOT_EXECUTABLE", "EXECUTION_ECONOMICS",
        "CURVE_NOBODY_BOUGHT", "8320", "REBUY_LOCKOUT", "FALLING_KNIFE", "BIG_LOSER", "MAYHEM", "COST_", "EDGE_7877",
        "LIVE_SETUP_LOSING_8083", "EVIDENCE_HOLD_8336", "DATA_", "WALLET",
    )

    /** Pure: is [reason] an evidence (label / record) door — never a hard-safety, chase or economic refusal? */
    fun evidenceDoor8342(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase().trim()
        if (FdgGate8365.hardSafety8392(r)) return false
        if (BINDING_MARKERS_8342.any { r.contains(it) }) return false
        return EVIDENCE_DOORS_8342.any { r.contains(it) }
    }

    /** Pure: does [reason] bind an entry decided for a [paper] book while [rehearsal] is on? LIVE: always. */
    fun evidenceDoorBinds8342(paper: Boolean, rehearsal: Boolean, reason: String?): Boolean =
        !(paper && rehearsal && evidenceDoor8342(reason))

    private val advisories8342 = ThreadLocal<ArrayList<String>>()

    /** LiveEdgeGate7877.liveRefusal: runs [block] collecting the doors made advisory on this thread. */
    fun <T> collecting8342(block: () -> T): Pair<T, List<String>> {
        val prev = advisories8342.get()
        val mine = ArrayList<String>()
        advisories8342.set(mine)
        try { return block() to mine.toList() } finally { if (prev == null) advisories8342.remove() else advisories8342.set(prev) }
    }

    /**
     * retired compatibility gate door sites: true when [reason] binds now (LIVE runtime, rehearsal off, or a binding door); false
     * when it is advisory in this PAPER runtime — then it is recorded on the collecting decision and counted, and the
     * caller continues to the next door. Unknown mode binds (safe side).
     */
    fun doorBindsNow8342(reason: String, lane: String): Boolean {
        val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val rehearsal = EvidenceHold8336.enabled8336 && EvidenceHold8336.rehearsal8336
        // V5.0.8365 — LIVE: an evidence door whose question an authoritative check already answered is advisory (FdgGate8365).
        if (evidenceDoorBinds8342(paper, rehearsal, reason)) return !(try { FdgGate8365.demoteNow8365(reason, lane) } catch (_: Throwable) { false })
        advisories8342.get()?.add(reason)
        try {
            PipelineHealthCollector.labelInc("${ADVISORY_LABEL_8342}_${FdgGate8365.gateKey8392(reason)}")
            PipelineHealthCollector.labelInc("${ADVISORY_LABEL_8342}_${FdgGate8365.gateKey8392(reason)}_${CanonicalLaneIdentity6506.desk8392(lane)}")
        } catch (_: Throwable) {}
        return false
    }

    /** Pure: the admission-ledger source a paper assessment is filed under — never "LIVE" (8336: paper is not live evidence). */
    fun assessmentSource8342(paper: Boolean): String = if (paper) PAPER_SOURCE_8342 else LIVE_SOURCE_8342

    // ── the paper entry's live verdict, kept for its close ──

    data class EntryTag8342(val atMs: Long, val liveWouldRefuse: String?)

    private const val PREFS_8342 = "aate_rehearsal_8342"
    private const val KEY_TAGS_8342 = "tags"
    private const val MAX_TAGS_8342 = 2_000
    private const val TAG_TTL_MS_8342 = 7 * 24 * 3_600_000L
    private val tags8342 = java.util.concurrent.ConcurrentHashMap<String, EntryTag8342>()   // mint|lane -> latest paper admit
    private val seenCloses8342 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var prefs8342: android.content.SharedPreferences? = null

    private fun key8342(mint: String, lane: String) = "$mint|${CanonicalLaneIdentity6506.desk8392(lane)}"

    /** BotService: the tags survive a restart (a paper close can come hours after its entry). */
    fun init8342(ctx: android.content.Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS_8342, android.content.Context.MODE_PRIVATE)
            prefs8342 = p
            val now = System.currentTimeMillis()
            p.getString(KEY_TAGS_8342, null)?.split('\n')?.forEach { row ->
                val d = decodeTag8342(row)
                if (d != null && now - d.second.atMs in 0L..TAG_TTL_MS_8342) tags8342[d.first] = d.second
            }
        } catch (_: Throwable) {}
    }

    /** Pure: one persisted row `mint|lane\tatMs\tdoor` (door "-" = live would admit). */
    fun encodeTag8342(key: String, t: EntryTag8342): String = "$key\t${t.atMs}\t${t.liveWouldRefuse?.ifBlank { null } ?: "-"}"

    fun decodeTag8342(row: String): Pair<String, EntryTag8342>? {
        val f = row.split('\t')
        if (f.size < 3 || f[0].isBlank()) return null
        val at = f[1].toLongOrNull() ?: return null
        return f[0] to EntryTag8342(at, f[2].takeIf { it.isNotBlank() && it != "-" })
    }

    private fun persist8342() {
        val p = prefs8342 ?: return
        try {
            val rows = tags8342.entries.sortedByDescending { it.value.atMs }.take(MAX_TAGS_8342).joinToString("\n") { encodeTag8342(it.key, it.value) }
            p.edit().putString(KEY_TAGS_8342, rows).apply()
        } catch (_: Throwable) {}
    }

    /**
     * LiveEdgeGate7877.liveRefusal: a PAPER entry admitted under rehearsal; [advisories] are the evidence doors the live
     * policy would have refused it on (empty: the live policy would have admitted it too).
     */
    fun notePaperAdmit8342(ts: TokenState, lane: String, advisories: List<String>, nowMs: Long) {
        val door = advisories.firstOrNull()
        val k = key8342(ts.mint, lane)
        tags8342[k] = EntryTag8342(nowMs, door)
        if (tags8342.size > MAX_TAGS_8342) tags8342.entries.removeIf { nowMs - it.value.atMs > TAG_TTL_MS_8342 }
        if (tags8342.size > MAX_TAGS_8342) tags8342.entries.sortedBy { it.value.atMs }.take(tags8342.size - MAX_TAGS_8342).forEach { tags8342.remove(it.key) }
        persist8342()
        try {
            val l = CanonicalLaneIdentity6506.desk8392(lane)
            PipelineHealthCollector.labelInc(if (door == null) "REHEARSAL_PAPER_ADMIT_LIVE_WOULD_ADMIT_8342_$l" else "REHEARSAL_PAPER_ADMIT_LIVE_WOULD_REFUSE_8342_$l")
            if (door != null) com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.noteAdmission8144(ts.mint, l, "ADMIT", "ADMIT_8114_8326|liveWouldRefuse=$door")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("REHEARSAL_ADVISORY_8342", k)) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "REHEARSAL_PAPER_ADMIT_8342",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$l liveWouldRefuse=${door ?: "-"} advisories=${advisories.joinToString(",").take(160)}",
                )
            }
        } catch (_: Throwable) {}
    }

    /** The paper entry's tag for [mint] in [lane] made at or before [closeMs]; null when the entry predates 8342 / was not decided here. */
    fun entryTag8342(mint: String, lane: String, closeMs: Long): EntryTag8342? =
        tags8342[key8342(mint, lane)]?.takeIf { it.atMs <= closeMs }   // V5.0.8343 — a close before the entry is not that entry's close

    /** Pure: the close tag — LIVE_ADMIT / LIVE_REFUSE / UNTAGGED. */
    fun closeTag8342(tag: EntryTag8342?): String = when {
        tag == null -> UNTAGGED_8342
        tag.liveWouldRefuse == null -> LIVE_ADMIT_8342
        else -> LIVE_REFUSE_8342
    }

    /** EvidenceHold8336: a paper close of [lane] read for the first time is tagged (counted once per row). */
    fun tagPaperClose8342(rowKey: String, mint: String, lane: String, closeMs: Long): String {
        val tag = closeTag8342(entryTag8342(mint, lane, closeMs))
        if (seenCloses8342.add(rowKey)) {
            if (seenCloses8342.size > 20_000) seenCloses8342.clear()
            try { PipelineHealthCollector.labelInc("${CLOSE_LABEL_8342}_${tag}_${CanonicalLaneIdentity6506.desk8392(lane)}") } catch (_: Throwable) {}
        }
        return tag
    }

    internal fun resetForTests8342() { tags8342.clear(); seenCloses8342.clear(); advisories8342.remove() }
}
