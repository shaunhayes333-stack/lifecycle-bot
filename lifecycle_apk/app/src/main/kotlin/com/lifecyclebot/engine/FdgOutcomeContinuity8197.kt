package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506

/**
 * V5.0.8196: FinalDecisionGate.evaluate has several deliberate early exits before its
 * normal rememberFdgVerdict() stamp. They are real FDG refusals, but previously
 * left a qualified named receiver as SCHEDULED_NO_FDG_VERDICT. This adapter
 * does NOT alter an FDG decision, its size, eligibility or its execution seal.
 *
 * A separate small method is mandatory: evaluate itself is near Android ART's
 * verifier budget and changing its locals/branches previously crashed at login.
 */
object FdgOutcomeContinuity8197 {
    /** Only the normal returned verdict has BOTH the immutable candidate generation and lane stamp. */
    internal fun needsEarlyRecord8197(d: FinalDecisionGate.FinalDecision): Boolean =
        d.candidateVersion7835 <= 0L || d.canonicalLane7835.isBlank()

    fun evaluate(
        ts: TokenState,
        candidate: CandidateDecision,
        config: BotConfig,
        proposedSizeSol: Double,
        brain: BotBrain? = null,
        tradingModeTag: ModeSpecificGates.TradingModeTag? = null,
        laneScore: Double = candidate.entryScore,
        specialistLane: String? = null,
        fanoutRole: String = "",
    ): FinalDecisionGate.FinalDecision {
        val lane = CanonicalLaneIdentity6506.canonical(
            specialistLane?.takeIf { it.isNotBlank() } ?: tradingModeTag?.name ?: "CORE")
        // Resolve BEFORE the call. A 30s bucket rollover inside the large FDG
        // must not make an early return's receipt belong to a later candidate.
        val version = try { LaneExecutionCoordinator.candidateVersionFor(ts.mint) } catch (_: Throwable) { 0L }
        val result = FinalDecisionGate.evaluate(ts, candidate, config, proposedSizeSol, brain,
            tradingModeTag, laneScore, specialistLane, fanoutRole)
        shadowObserveAdmittedBlock8233(ts, lane, version, result, proposedSizeSol, laneScore)
        recordEarlyVerdict8397(ts.mint, lane, version, result)
        return result
    }

    /** The actual early-return adapter, shared with production-path continuity tests. Never creates an allow. */
    internal fun recordEarlyVerdict8397(mint: String, lane: String, version: Long, result: FinalDecisionGate.FinalDecision,
                                       nowMs: Long = System.currentTimeMillis()) {
        if (needsEarlyRecord8197(result)) {
            // False results retain DATA_DEFER through the typed receipt adapter; no execution is authorized here.
            val reason = result.blockReason ?: result.approvalReason.ifBlank { "FDG_EARLY_RETURN_UNSPECIFIED_8197" }
            try {
                SpecialistOwnership7951.onFdgVerdict7951(mint, lane, version,
                    false, reason, nowMs)
                PipelineHealthCollector.labelInc(if (FdgReceipt8397.outcome(false, reason) == FdgReceipt8397.Outcome.DEFER_DATA)
                    "FDG_EARLY_DATA_PENDING_8397_$lane" else "FDG_EARLY_TERMINAL_ACCOUNTED_8197_$lane")
            } catch (_: Throwable) {}
        }
    }

    /**
     * V5.0.8233 — the shadow book observes every LIVE candidate the edge authority ADMITTED (AdmissionEdge8180 holds a
     * proof for this generation) that the FDG then blocked downstream. 5.0.8231: 174 admitted, 0 executed, shadow book
     * opens=0 — the only openers sat behind the live Executor entry. The book is how live learns while it cannot trade;
     * it stays observation-only (Executor.runShadowPaperBuy never promotes). Paper mode has its own book: skipped.
     */
    internal fun shadowOpenReason8233(live: Boolean, admitted: Boolean, blockReason: String?): String? {
        if (!live || blockReason.isNullOrBlank()) return null
        // V5.0.8238 — a generic-prior candidate refused live money is exactly what the shadow book is for.
        if (blockReason.contains("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238")) return "generic_prior_shadow:" + blockReason.take(40)
        if (!admitted) return null
        return "admitted_blocked:" + blockReason.take(40)
    }

    private fun shadowObserveAdmittedBlock8233(
        ts: TokenState, lane: String, version: Long, result: FinalDecisionGate.FinalDecision, proposedSizeSol: Double, laneScore: Double,
    ) {
        try {
            if (result.shouldTrade) return
            val live = !RuntimeModeAuthority.isPaper()
            val admitted = com.lifecyclebot.engine.truth.AdmissionEdge8180.proof8198("LIVE", ts.mint, lane, version) != null
            val reason = shadowOpenReason8233(live, admitted, result.blockReason) ?: return
            val executor = BotService.instance?.executor ?: return
            val sol = if (proposedSizeSol.isFinite() && proposedSizeSol > 0.0) proposedSizeSol else 0.0285
            executor.shadowObserve8233(ts, sol, laneScore, lane, reason)
            PipelineHealthCollector.labelInc("SHADOW_OPEN_ON_ADMITTED_BLOCK_8233_$lane")
        } catch (_: Throwable) {}
    }
}
