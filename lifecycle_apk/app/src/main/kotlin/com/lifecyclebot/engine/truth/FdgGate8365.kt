package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8365 §ONE_AUTHORITATIVE_CHECK_PER_QUESTION — audit of the live entry gate stack (lifecycle_apk/FDG_GATE_MAP_8365.md).
 *
 * 5.0.8341 / 8344: "the setup has no proof" was asked up to seven times under different names (EVIDENCE_HOLD_8336,
 * LIVE_PROVEN_SETUP_ONLY_8059, PICKED_SETUP_RECORD_NEGATIVE_8094, TIMING_RECORD_NEGATIVE_8094, PLAYBOOK_*, the 8364 fired-
 * setup rule, the 8351 niche). The lane's paper record that unlocks the hold was built by entries on which every one of the
 * label / record doors was ADVISORY (Rehearsal8339, rehearsal), and the 8347 exploration book opens whatever any gate says
 * — so after the hold passed on that proof, live re-imposed doors the proven policy never had (a stale duplicate refusing a
 * candidate after the authoritative check passed). The rule, LIVE only, for an 8114 EVIDENCE door
 * (Rehearsal8339.evidenceDoor8342 — never hard safety, chase, economics, rebuy lockout, realised live losses or data):
 *  1. the hold passed on a proof built WITHOUT those doors (paper ALL closes, or the 8347 exploration book) -> the door is
 *     advisory: the hold is the authoritative answer. (A lane unlocked only by EvidenceShadow8344 — which mirrors the live
 *     decision, doors binding — keeps its doors; so does the 8337 exception.)
 *  2. PLAYBOOK_NO_TRIGGER_* -> advisory: the 8364 fired-setup rule is the one check of "did a named setup fire" (it
 *     refuses LIVE_NO_FIRED_SETUP_8364_<lane> when none did). superseded by V5.0.8370 — owner restored 8114 behaviour
 *     ("No fired-setup rule"): 8364 is telemetry and the NO_TRIGGER door is as at 8114.
 *  3. a "setup losing on labels" door (picked-setup / timing record, PLAYBOOK_SETUP_*) -> advisory when the same setup's
 *     REALISED live closes (LiveTruth8064, the record LIVE_SETUP_LOSING_8083 judges) number 5+ with mean >= 0.
 *  4. the veto audit: the door's own lane record of refused candidates holds 100+ graded refusals proven to win
 *     (Cortex7885.vetoRetiresAt100_8365) -> advisory.
 * Every demotion is counted (FDG8365_DEMOTED_<door>_BY_<basis>) and the final refusal of every 8114 decision is attributed
 * once, by class (status: fdg8365[...]).
 */
object FdgGate8365 {
    const val BASIS_PAPER_ALL_8365 = "PAPER_ALL_8336"
    const val BASIS_EXPLORATION_8365 = "EXPLORATION_8347"
    const val BY_FIRED_SETUP_8365 = "FIRED_SETUP_8364"
    const val BY_REALISED_8365 = "LIVE_SETUP_REALISED_8083"
    const val BY_VETO_AUDIT_8365 = "VETO_AUDIT_N100"
    const val REALISED_MIN_N_8365 = 5
    const val VETO_MIN_N_8365 = 100.0
    const val MEMO_TTL_MS_8365 = 2_000L

    const val CLASS_SAFETY = "SAFETY"
    const val CLASS_ECONOMICS = "ECONOMICS"
    const val CLASS_HOLD = "EVIDENCE-HOLD"
    const val CLASS_LABEL = "LABEL-EVIDENCE"
    const val CLASS_DATA = "DATA"
    const val CLASS_STRUCTURE = "STRUCTURE"

    // ── pure ──

    /** Pure: a "this setup loses on its labels" door (the question LIVE_SETUP_LOSING_8083 answers on realised closes). */
    fun setupLossDoor8365(reason: String?): Boolean {
        val r = reason?.uppercase() ?: return false
        return r.contains("PICKED_SETUP_RECORD_NEGATIVE") || r.contains("TIMING_RECORD_NEGATIVE") ||
            r.contains("PLAYBOOK_SETUP_PROVEN_LOSING") || r.contains("PLAYBOOK_SETUP_EXPECTED_NEGATIVE")
    }

    /** Pure: the setup a PLAYBOOK_SETUP_* reason names ("..._7907_<lane>_<setup>" / "..._7948_<lane>_<setup>"), else null. */
    fun setupOfReason8365(reason: String, lane: String): String? {
        val l = lane.trim().uppercase()
        if (l.isEmpty()) return null
        for (tag in listOf("_7907_${l}_", "_7948_${l}_")) {
            val i = reason.indexOf(tag)
            if (i >= 0) return reason.substring(i + tag.length).ifBlank { null }
        }
        return null
    }

    /** Pure: the setup's realised live record [n, mean %] contradicts a label "losing" verdict (5+ closes, mean >= 0). */
    fun realisedContradicts8365(realised: DoubleArray?): Boolean =
        realised != null && realised.size >= 2 && realised[0].isFinite() && realised[0] >= REALISED_MIN_N_8365 &&
            realised[1].isFinite() && realised[1] >= 0.0

    /** Pure: the veto audit demotes a door on 100+ graded refusals proven to win. */
    fun vetoDemotes8365(refusedN: Double, provenWinners: Boolean): Boolean =
        refusedN.isFinite() && refusedN >= VETO_MIN_N_8365 && provenWinners

    /**
     * Pure: the authoritative check that already answered [reason]'s question for this candidate (the door is then advisory),
     * or null (the door binds). LIVE only; never for a door that is not an evidence door.
     */
    fun demotedBy8365(reason: String, live: Boolean, holdBasis: String?, realisedForDoor: DoubleArray?, vetoRetired: Boolean): String? {
        if (!live) return null
        if (!Rehearsal8339.evidenceDoor8342(reason)) return null
        val r = reason.uppercase()
        if (holdBasis != null) return "EVIDENCE_HOLD_$holdBasis"
        // superseded by V5.0.8370 — owner restored 8114 behaviour: the 8364 fired-setup rule is telemetry, so PLAYBOOK_NO_TRIGGER_*
        // is no longer its duplicate (was: return BY_FIRED_SETUP_8365). The door is as at 8114: it binds only on a PROVEN losing
        // NO_TRIGGER record, with the 8114 overrides and the hold-basis / realised / veto demotions here.
        if (setupLossDoor8365(r) && realisedContradicts8365(realisedForDoor)) return BY_REALISED_8365
        if (vetoRetired) return BY_VETO_AUDIT_8365
        return null
    }

    /**
     * Pure: the hold's unlock basis that makes the 8114 evidence doors duplicates — the paper ALL-closes proof or the 8347
     * exploration proof (both built without those doors); null for the EvidenceShadow8344 proof (built with them), the
     * 8337 exception, an unproven lane, a hold switched off, or a PAPER runtime.
     */
    fun holdBasisOf8365(holdOn: Boolean, live: Boolean, paperAllProven: Boolean, explorationProven: Boolean): String? = when {
        !holdOn || !live -> null
        paperAllProven -> BASIS_PAPER_ALL_8365
        explorationProven -> BASIS_EXPLORATION_8365
        else -> null
    }

    /**
     * V5.0.8418 §THE_100X_DOOR_IS_ONE_CHECK — the basis of a candidate the evidence hold let through on its own exception
     * (EvidenceHold8336 8337 / 8345: forming run, exit-safe venue, run odds or early-run evidence, lane not shut by realised
     * losses, 1 per lane per 30 min, 3 an hour). That door is the authoritative answer to "may an unproven lane take this
     * coin": the lane's LABEL / RECORD doors (Rehearsal8339.evidenceDoor8342) asking it again are advisory for this
     * candidate (counted FDG8365_DEMOTED_<door>_BY_EVIDENCE_HOLD_EXCEPTION_8418). Hard safety, economics, data, chase, the
     * realised-loss doors and Rehearsal8339.candidateEvidence8393 still bind. superseded by V5.0.8418 — 8365 kept the 8337
     * exception's doors binding; every report since (8344: 0 in 431 min, 8416: 0 in 223 min beside 435 live grab-lane
     * admits) submitted 0 exceptions: the owner's "if it sees a 100x opportunity take it" never reached a buy.
     */
    const val BASIS_EXCEPTION_8418 = "EXCEPTION_8418"

    /** Pure (V5.0.8418): [BASIS_EXCEPTION_8418] when a LIVE held, unproven lane's hold passed on [exceptionProof]; else null. */
    fun exceptionBasisOf8418(live: Boolean, holdOn: Boolean, heldLane: Boolean, holdPassed: Boolean, laneProven: Boolean,
                             exceptionProof: String?): String? =
        if (live && holdOn && heldLane && holdPassed && !laneProven && !exceptionProof.isNullOrBlank()) BASIS_EXCEPTION_8418 else null

    /**
     * Pure: does the 8114 verdict [old] yield the attribution to the structural checks (8364 fired setup, 8351 niche)? Only
     * when it is a label / record door and neither the current hard verdict nor the realised / hold refusal decided.
     */
    fun oldYields8365(old: String?, finalSoFar: String?, realised: String?): Boolean =
        old != null && finalSoFar == old && realised == null && !FdgGate8365.hardSafety8392(old) && Rehearsal8339.evidenceDoor8342(old)

    /** Pure: the class a refusal is attributed to. */
    fun class8365(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val r = reason.uppercase()
        return when {
            r.contains("EVIDENCE_HOLD_8336") || r.contains("LANE_REALISED_LOSING") || r.contains("LIVE_SETUP_LOSING_8083") -> CLASS_HOLD
            r.contains("DATA_") || r.contains("UNHYDRATED") || r.contains("_DEFER") || r.contains("EVAL_FAILED") -> CLASS_DATA
            r.contains("NET_EDGE") || r.contains("ECONOMIC") || r.contains("SIZE_NOT_EXECUTABLE") || r.contains("COST_") ||
                r.contains("ROUTE_MIN") -> CLASS_ECONOMICS
            FdgGate8365.hardSafety8392(r) || r.contains("CURVE_NOBODY_BOUGHT") -> CLASS_SAFETY
            Rehearsal8339.evidenceDoor8342(r) || r.contains("EDGE_7877") -> CLASS_LABEL
            else -> CLASS_STRUCTURE
        }
    }

    /** Pure: a memoised 8114 decision is reused only inside [MEMO_TTL_MS_8365]. */
    fun memoFresh8365(atMs: Long, nowMs: Long): Boolean = nowMs - atMs in 0L..MEMO_TTL_MS_8365

    // ── the decision context (set by LiveEdgeGate7877.liveRefusal around the 8114 verdict) ──

    private class Ctx8365(val ts: TokenState, val lane: String, val holdBasis: String?,
                         val paper8393: Boolean = false, val shadowOnly8393: Boolean = false)
    private val ctx8365 = ThreadLocal<Ctx8365>()

    internal fun paperContext8393(): Boolean? = ctx8365.get()?.paper8393
    internal fun shadowOnly8393(): Boolean = ctx8365.get()?.shadowOnly8393 == true
    internal fun policyKey8393(): String = ctx8365.get()?.let {
        "paper=${it.paper8393}|shadow=${it.shadowOnly8393}|basis=${it.holdBasis ?: "NONE"}"
    } ?: "OUTSIDE"

    private fun liveNow8365(): Boolean = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }

    /** The hold's basis for [lane] now (LIVE runtime only). */
    fun holdBasis8365(lane: String, nowMs: Long = System.currentTimeMillis()): String? {
        val live = liveNow8365()
        if (!live || !EvidenceHold8336.enabled8336) return null
        val paperAll = try { EvidenceHold8336.proofFor8336(lane, nowMs).proven } catch (_: Throwable) { false }
        val explore = !paperAll && (try { EvidenceHold8336.explorationProven8347(lane) } catch (_: Throwable) { false })
        // superseded by V5.0.8418 — a proven EvidenceShadow8344 advisory book was a hold basis ("SHADOW_ADVISORY_8393") that made
        // every LIVE evidence door advisory: a shadow result admitting live entries. It is now counted only; the doors bind.
        val basis = holdBasisOf8365(true, live, paperAll, explore)
        if (basis == null && (try { EvidenceShadow8344.advisoryProof8393(CanonicalLaneIdentity6506.desk8392(lane), EvidenceHold8336.startMs8336).proven } catch (_: Throwable) { false }))
            try { PipelineHealthCollector.labelInc("SHADOW_ADVISORY_PROOF_NOT_A_LIVE_BASIS_8418_${CanonicalLaneIdentity6506.desk8392(lane)}") } catch (_: Throwable) {}
        return basis
    }

    fun <T> inContext8365(ts: TokenState, lane: String, holdBasis: String?, paper8393: Boolean = false,
                         shadowOnly8393: Boolean = false, block: () -> T): T {
        val prev = ctx8365.get()
        ctx8365.set(Ctx8365(ts, lane, holdBasis, paper8393, shadowOnly8393))
        try { return block() } finally { if (prev == null) ctx8365.remove() else ctx8365.set(prev) }
    }

    /**
     * Rehearsal8339.doorBindsNow8342, for a door that binds: true when an authoritative check already answered it for the
     * candidate in context (then counted and advisory). Outside a decide8326 context, or in PAPER: false (nothing changes).
     */
    fun demoteNow8365(reason: String, lane: String): Boolean {
        val c = ctx8365.get() ?: return false
        if (!liveNow8365() || !Rehearsal8339.evidenceDoor8342(reason)) return false
        val l = CanonicalLaneIdentity6506.desk8392(lane.ifBlank { c.lane })
        val realised = if (c.holdBasis == null && setupLossDoor8365(reason)) {
            val setup = setupOfReason8365(reason, l)
                ?: (if (reason.uppercase().contains("TIMING_RECORD_NEGATIVE") || reason.uppercase().contains("PICKED_SETUP_RECORD_NEGATIVE"))
                    (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(c.ts, l) } catch (_: Throwable) { null }) else null)
            try { LiveTruth8064.setupRealised8365(l, setup) } catch (_: Throwable) { null }
        } else null
        val veto = c.holdBasis == null && !realisedContradicts8365(realised) &&  // V5.0.8370 — NO_TRIGGER takes the veto audit like every door
            ((try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRetiresAt100_8365(reason, l) } catch (_: Throwable) { false }) ||
                // V5.0.8418 — the door's own refused-candidate book (GateOutcome8418): the Cortex veto book only ever saw the
                // first (native) reason of a mint, so an FDG door never reached its 100-refusal audit.
                (try { GateOutcome8418.retiresAt100_8418(reason, l) } catch (_: Throwable) { false }))
        val by = demotedBy8365(reason, true, c.holdBasis, realised, veto) ?: return false
        noteDemoted8365(reason, by, l)
        return true
    }

    // ── one decision per candidate per FDG pass (FDG's live edge block and TradePlan7739's chokepoint both asked) ──

    private val memo8365 = ConcurrentHashMap<String, Pair<Long, String?>>()
    private val memoHits8365 = AtomicLong(0)

    fun memoKey8365(mint: String, lane: String, candidateVersion: Long, paper: Boolean): String = "$mint|$lane|$candidateVersion|$paper"

    /** The memoised verdict for [key] (wrapped: Pair.second null = admit), or null when absent / stale. */
    fun memoGet8365(key: String, nowMs: Long): Pair<Long, String?>? {
        val m = memo8365[key] ?: return null
        if (!memoFresh8365(m.first, nowMs)) { memo8365.remove(key); return null }
        memoHits8365.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FDG8365_DECISION_REUSED") } catch (_: Throwable) {}
        return m
    }

    fun memoPut8365(key: String, verdict: String?, nowMs: Long) {
        memo8365[key] = nowMs to verdict
        if (memo8365.size > 2_000) memo8365.entries.removeIf { !memoFresh8365(it.value.first, nowMs) }
    }

    // ── attribution ──

    private val refused8365 = ConcurrentHashMap<String, AtomicLong>()   // class=gate -> n
    private val demoted8365 = ConcurrentHashMap<String, AtomicLong>()   // gate>basis -> n
    private val admits8365 = AtomicLong(0)

    fun noteDemoted8365(reason: String, by: String, lane: String) {
        val gate = FdgGate8365.gateKey8392(reason)
        demoted8365.computeIfAbsent("$gate>$by") { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("FDG8365_DEMOTED_${gate}_BY_$by")
            PipelineHealthCollector.labelInc("FDG8365_DEMOTED_${gate}_${CanonicalLaneIdentity6506.desk8392(lane)}")
        } catch (_: Throwable) {}
    }

    /** LiveEdgeGate7877.liveRefusal: the one final verdict of the decision, attributed once. */
    fun noteFinal8365(final: String?, lane: String) {
        if (final == null) { admits8365.incrementAndGet(); return }
        val cls = class8365(final) ?: return
        val gate = FdgGate8365.gateKey8392(final)
        refused8365.computeIfAbsent("$cls=$gate") { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FDG8365_REFUSED_${cls.replace('-', '_')}_${CanonicalLaneIdentity6506.desk8392(lane)}") } catch (_: Throwable) {}
    }

    private val exception8418 = ConcurrentHashMap<String, AtomicLong>()   // gate (or ADMITTED) -> n

    /** V5.0.8418 — the final verdict of a candidate the hold passed on its exception (ADMITTED or the refusing gate). */
    fun noteException8418(final: String?, lane: String) {
        val gate = if (final == null) "ADMITTED" else FdgGate8365.gateKey8392(final)
        if (exception8418.size < 64 || exception8418.containsKey(gate)) exception8418.computeIfAbsent(gate) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXCEPTION_FINAL_8418_${gate}_${CanonicalLaneIdentity6506.desk8392(lane)}") } catch (_: Throwable) {}
    }

    /** V5.0.8418 — PipelineHealthCollector's evidence-hold line followed by the buy-path gate line (attribution + forward record). */
    fun evidenceAndGates8418(): String =
        EvidenceHold8336.statusLine8336() + "\n  Buy-path gates (§8418):      " +
            (try { statusLine8365() } catch (_: Throwable) { "fdg8365[?]" }) + " " +
            (try { GateOutcome8418.statusLine8418() } catch (_: Throwable) { "gateOutcome8418[?]" })

    fun statusLine8365(): String {
        val book = if (liveNow8365()) "LIVE" else "PAPER"
        val refused = refused8365.entries.sortedByDescending { it.value.get() }.take(14)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val demoted = demoted8365.entries.sortedByDescending { it.value.get() }.take(12)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        val exceptions = exception8418.entries.sortedByDescending { it.value.get() }.take(10)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "fdg8365[book=$book admitted=${admits8365.get()} refused{$refused} reused=${memoHits8365.get()}] duplicatesDemoted=[$demoted] " +
            "exceptionFinal8418{$exceptions}"
    }
    private val HARD_MARKERS_8392 = listOf(
        "SAFETY_REFUSED", "STRUCTURAL_RISK", "RUG", "FREEZE", "MINT_AUTH", "AUTHORITY", "TOKEN_2022", "TOKEN2022", "T22_",
        "HOSTILE", "HONEYPOT", "SELLAB", "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQ_TOO_LOW", "LIQUIDITY_FLOOR",
        "LIQUIDITY_COLLAPSE", "LANE_LIQ", "EXIT_SAFE", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "WALLET_BLIND", "BALANCE",
        "LEDGER", "DUPLICATE", "SINGLE_FLIGHT", "KILL_SWITCH", "HALT", "AT_LAUNCH_CAP", "DEAD_LAUNCH",
        "ECONOMICS", "NET_EDGE_BELOW", "COST_REQUOTE", "ROUTE_MIN", "CANONICAL_ENTRY_EVAL_FAILED_8392",
    )

    /** Pure: a refusal that binds whatever the mode (safety / correctness / economics, not selection). */
    fun hardSafety8392(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        return HARD_MARKERS_8392.any { r.contains(it) }
    }

    /** Pure: the gate a refusal belongs to, for ENTRY8114_BYPASSED_<gate>. */
    fun gateKey8392(reason: String): String {
        val r = reason.uppercase().trim()
        val body = if (r.startsWith("CANDIDATE_ADMISSION_8136_")) "ADMISSION8136_" + r.removePrefix("CANDIDATE_ADMISSION_8136_") else r
        val m = Regex("^(.*?_\\d{4})(?:_|$)").find(body)
        val head = m?.groupValues?.getOrNull(1) ?: body
        val key = if (body.startsWith("ADMISSION8136_")) {
            Regex("^ADMISSION8136_(.*?_\\d{4})(?:_|$)").find(body)?.groupValues?.getOrNull(1)?.let { "ADMISSION8136_$it" } ?: head
        } else head
        return key.replace(Regex("[^A-Z0-9_]"), "_").take(56)
    }

}
