package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8180 §STOP_MISSING_RUNNERS — admission carries its own evidence downstream.
 *
 * STAGE: CandidateAdmission8136's 8173 carry let a fired setup outrank a lane-wide stage loss only when that setup was
 * SUPPORTED (30+ labels, mean - SE > 0). Live lane books are thin (5.0.8175 QUALITY / BLUECHIP setups mostly n < 30), so
 * bestSetup8153 was null on nearly every candidate and CANDIDATE_ADMISSION_8136_STAGE_PROVEN_LOSING refused 228 — the
 * lane-wide prior decided every coin. The coin's own evidence at StrategyRank8153's cold-start margin (VALID: 12+ labels,
 * mean - 1.5 SE above the bar), on a fired setup or on the coin's own cell, now carries a stage loss too. Carrying is not
 * admitting: economics, data and the edge test still decide, and a VALID carry is not a specific edge (no SUPPORTED
 * record), so the generic prior decides it after the stage.
 *
 * EDGE: an ADMIT on SPECIFIC_EDGE_PROVEN_8136 was then refused by the sizers on their own coarser read
 * (SMART_SIZER_V3_SUB_ROUTABLE_REFUSED_7831=308, ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION_6909=183: "no edge" /
 * "collapsed conviction" for the minimum routable order). [AdmissionEdge8180] records that admission so the sizers may
 * promote a sub-minimum request to the routable minimum for it; LiveRiskPolicy7807 still re-proves the hard loss budget
 * and the concentration cap still applies.
 */
object StageCarry8180 {
    /** Pure: StrategyRank8153's VALID margin for a record (12+, mean - 1.5 SE above 0 for net labels, else above cost). */
    fun valid8180(e: CandidateAdmission8136.Evidence8136?, costPct: Double): Boolean {
        if (e == null || e.n < StrategyRank8153.VALID_MIN_N_8153 || !e.meanPct.isFinite() || !e.sePct.isFinite()) return false
        val bar = if (e.netOfCost) 0.0 else if (costPct.isFinite() && costPct > 0.0) costPct else return false
        return e.meanPct - StrategyRank8153.VALID_SE_MULT_8153 * e.sePct > bar
    }

    /**
     * Pure: the coin's own evidence that carries a lane-wide stage loss when no SUPPORTED setup does — the best VALID
     * fired setup not losing at the coin's timing band, else the coin's own cell at the VALID margin. Null: the stage
     * loss stands.
     */
    fun carry8180(c: CandidateAdmission8136.Candidate8136): CandidateAdmission8136.Evidence8136? {
        val setup = c.setups.filter { e ->
            valid8180(e, c.costPct) && !CandidateAdmission8136.losing8136(c.setupTimings[e.id])
        }.maxByOrNull { it.meanPct - StrategyRank8153.VALID_SE_MULT_8153 * it.sePct }
        if (setup != null) return setup
        // V5.0.8200 — else the Cortex's STRONG read on this candidate, at the same VALID margin on its executable-net record.
        return (if (c.lossEpochAware8169) c.cellLoss8169 else c.cell)?.takeIf { valid8180(it, c.costPct) }
            ?: c.cortexStrong8200?.takeIf { valid8180(it, c.costPct) }
    }
}

/**
 * V5.0.8180 — the candidates CandidateAdmission8136 just admitted on a candidate-specific proven edge (read by the sizers).
 *
 * V5.0.8198 §ONE_PROOF_PER_CANDIDATE — the record was keyed by mint alone and cleared by ANY other terminal for that mint:
 * SHITCOIN's specific ADMIT was erased when MOONSHOT refused the same coin a moment later (or was granted to MOONSHOT by
 * SHITCOIN's admit), and a proof from an earlier candidate generation authorised the next one. The proof is now bound to
 * (runtime mode, mint, canonical lane, candidate version) and carries the admission identity (basis, chosen setup); only
 * the same lane's own later terminal replaces or clears it, and a newer candidate version never reads an older proof.
 */
object AdmissionEdge8180 {
    const val TTL_MS_8180 = 120_000L
    private const val MAX_8180 = 2_000

    /** One admission's proof: immutable identity the sizers may rely on. V5.0.8224 — and its economic basis. */
    data class Proof8198(
        val mode: String, val mint: String, val lane: String, val candidateVersion: Long,
        val basis: String, val chosenSetup: String?, val atMs: Long,
        /** The admitted setup's expected net (%), the round trip it was costed at (%) and the ticket that cost priced (SOL). */
        val expectedNetPct: Double = Double.NaN, val costPct: Double = Double.NaN, val costSizeSol: Double = Double.NaN,
        /** V5.0.8235 — ADMIT on a candidate-specific proven edge (route-minimum promotion rights); false = generic prior clears. */
        val specificEdge: Boolean = true,
        val reason: String = "",
        val evaluatedStage: String = "", val evaluatedEntryWait: String? = null,
    ) {
        /** The admission's expected GROSS (%) — net plus the round trip it was charged; NaN when either is unknown. */
        val expectedGrossPct: Double get() =
            if (expectedNetPct.isFinite() && costPct.isFinite()) expectedNetPct + costPct else Double.NaN
    }

    private val admitted = ConcurrentHashMap<String, Proof8198>()

    private fun key8198(mode: String, mint: String, lane: String) =
        "${mode.trim().uppercase()}|$mint|${CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }}"

    /**
     * CandidateAdmission8136.noteDecision8144: an ADMIT by [lane] in [mode] stamps its own proof; any other terminal from
     * the SAME lane clears that lane's proof. Another lane's terminal never touches it.
     * V5.0.8235 §ONE_ECONOMIC_BASIS_FOR_EVERY_ADMIT — 8232 gave the sizer the admission's gross only for a SPECIFIC-edge
     * ADMIT; a GENERIC_PRIOR_CLEARS_8136 admit (the same decisionGross that cleared NetEdge8030 at the admission size)
     * left no proof, so LiveRiskPolicy7807 re-read the lane's classified record and refused the admitted coin as
     * NET_EV_NONPOSITIVE_AT_SIZE_8153 (8233 forensic: 50 of 144 sized). Every ADMIT now carries its basis; only a
     * specific-edge ADMIT carries promotion rights ([admitted8180]).
     */
    fun note8180(mode: String, mint: String, lane: String, candidateVersion: Long, d: CandidateAdmission8136.Decision8136, nowMs: Long) {
        if (mint.isBlank() || lane.isBlank()) return
        val k = key8198(mode, mint, lane)
        if (d.terminal != CandidateAdmission8136.Terminal8136.ADMIT) { admitted.remove(k); return }
        admitted[k] = Proof8198(mode.trim().uppercase(), mint, k.substringAfterLast('|'), candidateVersion, d.basis, d.chosenSetup, nowMs,
            d.expectedNetPct, d.costPct, d.costSizeSol, specificEdge = carriesEdge8180(d), reason = d.reason,
            evaluatedStage = d.evaluatedStage, evaluatedEntryWait = d.evaluatedEntryWait)
        if (admitted.size > MAX_8180) admitted.entries.removeIf { nowMs - it.value.atMs > TTL_MS_8180 }
    }

    /**
     * The proof [lane] holds for [mint] in [mode] inside [TTL_MS_8180] for candidate generation [candidateVersion]
     * (a proof stamped on an older generation authorises nothing), else null.
     */
    fun proof8198(mode: String, mint: String, lane: String, candidateVersion: Long, nowMs: Long = System.currentTimeMillis()): Proof8198? {
        if (mint.isBlank() || lane.isBlank()) return null
        val p = admitted[key8198(mode, mint, lane)] ?: return null
        if (nowMs - p.atMs !in 0L..TTL_MS_8180) return null
        if (p.candidateVersion != candidateVersion) return null
        return p
    }

    /** After admission a fresh danger or changed wait still binds. Only the exact evaluated soft wait was settled. */
    fun remainingLiveWait(mint: String, lane: String, version: Long, stage: String, wait: String?, nowMs: Long,
                          admittedProof: Proof8198? = proof8198("LIVE", mint, lane, version, nowMs)): String? {
        if (wait == null || version <= 0L) return wait
        when (wait) {
            "NO_PHASE_TRIGGER", "NO_RECLAIM", "NO_BUY_VOLUME", "NO_IGNITION",
            "FLOW_UNOBSERVED", "STAGE_UNRESOLVED" -> Unit
            else -> return wait
        }
        val proof = admittedProof ?: return wait
        if (proof.mode != "LIVE" || proof.mint != mint || proof.candidateVersion != version ||
            key8198(proof.mode, mint, lane) != key8198(proof.mode, mint, proof.lane) ||
            nowMs - proof.atMs !in 0L..TTL_MS_8180) return wait
        // V5.0.8607 §A_SOFT_WAIT_IS_SETTLED_BY_THE_ADMISSION — superseded: the wait cleared only when the stage AND the wait
        // read at the send matched the admission's exactly. Since 8604 the admission decides like the paper book (these
        // waits are its learned doors, off), and a launch changes stage between admission and send, so the executor
        // re-imposed them: 21:22 dump, 56 of 124 LIVE_BUY_ENTRY deferred ENTRY_NO_PHASE_TRIGGER_8506 et al. A current LIVE
        // proof for this candidate settles every soft wait above; any other (danger) wait still returns, and so does a soft
        // wait on a coin that has since turned to a danger stage (DUMPING / RUG_PRONE / PEAK_EXHAUSTION).
        if (stage in DANGER_STAGES_8607) return wait
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_SOFT_WAIT_SETTLED_BY_ADMISSION_8607_$wait") } catch (_: Throwable) {}
        return null
    }

    private val DANGER_STAGES_8607 = setOf("DUMPING", "RUG_PRONE", "PEAK_EXHAUSTION")

    /** Was this exact candidate (mode, mint, lane, generation) admitted on a specific proven edge inside the TTL? */
    fun admitted8180(mode: String, mint: String, lane: String, candidateVersion: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        proof8198(mode, mint, lane, candidateVersion, nowMs)?.specificEdge == true

    /**
     * Runtime read for the sizers: the current mode's proof for [mint] in [lane] at the coordinator's CURRENT candidate
     * generation for the mint (LaneExecutionCoordinator.candidateVersionFor). Side-effect free.
     */
    fun admittedNow8198(paperMode: Boolean, mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || lane.isBlank()) return false
        val version = try { com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(mint) } catch (_: Throwable) { 0L }
        return admitted8180(if (paperMode) "PAPER" else "LIVE", mint, lane, version, nowMs)
    }

    /** Pure: a decision that carries its edge downstream — ADMIT on candidate-specific evidence with a SUPPORTED record. */
    fun carriesEdge8180(d: CandidateAdmission8136.Decision8136): Boolean =
        d.terminal == CandidateAdmission8136.Terminal8136.ADMIT && d.specific &&
            (d.reason == "SPECIFIC_EDGE_PROVEN_8136" || d.reason == CandidateAdmission8136.EDGE_MODEL_PROVEN_8537)

    /**
     * Pure: the size a live sizer may use for an admitted edge: a sub-routable [riskSized] request is promoted to the
     * routable minimum [routableMin] when [admitted] and the minimum fits under [cap] (concentration / lane cap); else
     * [riskSized] unchanged (the sizer's own refusal stands).
     */
    fun promote8180(admitted: Boolean, riskSized: Double, routableMin: Double, cap: Double): Double =
        if (admitted && riskSized > 0.0 && routableMin.isFinite() && routableMin > riskSized && cap.isFinite() && routableMin <= cap) routableMin
        else riskSized

    internal fun resetForTests8180() = admitted.clear()
}

/**
 * V5.0.8180 — the vanished-mark path books on the same basis rule as every other mark. 5.0.8175's best cells were
 * BAND|MOONSHOT|MC_GT1M +1,688% (wr 13%, runners 1%), TREASURY +1,584%, DIP_HUNTER +1,525% — a few labels thousands of
 * percent above a $1M+ entry with almost no runners, while basisSuspect7738=0. ForwardReturnLabeler7731.resolveVanished7944
 * took the canonical registry's mark as the horizon price with no basis check (the main loop drops a >20x move unless
 * corroborated from launch scale, and never above a $1M entry). Such a mark now leaves the label LOST (GAP), unbooked.
 */
object LabelBasis8180 {
    /** Pure: may a vanished observation be booked at [markPx] against [entryPx]? (same rule as the main mark loop) */
    fun bookable8180(entryPx: Double, markPx: Double, entryMcap: Double, nowMcap: Double, solUsd: Double): Boolean =
        !ForwardReturnLabeler7731.basisSuspect7738(entryPx, markPx) ||
            ForwardReturnLabeler7731.runCorroborated7769(entryPx, markPx, entryMcap, nowMcap, solUsd)
}
