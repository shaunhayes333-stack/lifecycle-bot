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
    ) {
        /** The admission's expected GROSS (%) — net plus the round trip it was charged; NaN when either is unknown. */
        val expectedGrossPct: Double get() =
            if (expectedNetPct.isFinite() && costPct.isFinite()) expectedNetPct + costPct else Double.NaN
    }

    private val admitted = ConcurrentHashMap<String, Proof8198>()

    private fun key8198(mode: String, mint: String, lane: String) =
        "${mode.trim().uppercase()}|$mint|${CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }}"

    /**
     * CandidateAdmission8136.noteDecision8144: a specific-edge ADMIT by [lane] in [mode] stamps its own proof; any other
     * terminal from the SAME lane clears that lane's proof. Another lane's terminal never touches it.
     */
    fun note8180(mode: String, mint: String, lane: String, candidateVersion: Long, d: CandidateAdmission8136.Decision8136, nowMs: Long) {
        if (mint.isBlank() || lane.isBlank()) return
        val k = key8198(mode, mint, lane)
        if (!carriesEdge8180(d)) { admitted.remove(k); return }
        admitted[k] = Proof8198(mode.trim().uppercase(), mint, k.substringAfterLast('|'), candidateVersion, d.basis, d.chosenSetup, nowMs,
            d.expectedNetPct, d.costPct, d.costSizeSol)
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

    /** Was this exact candidate (mode, mint, lane, generation) admitted on a specific proven edge inside the TTL? */
    fun admitted8180(mode: String, mint: String, lane: String, candidateVersion: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        proof8198(mode, mint, lane, candidateVersion, nowMs) != null

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
        d.terminal == CandidateAdmission8136.Terminal8136.ADMIT && d.specific && d.reason == "SPECIFIC_EDGE_PROVEN_8136"

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
