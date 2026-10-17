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
        if (needsEarlyRecord8197(result)) {
            // Early returns are refusals; never manufacture an allow, finality,
            // size or sealed intent to "unstick" a specialist.
            val reason = result.blockReason ?: result.approvalReason.ifBlank { "FDG_EARLY_RETURN_UNSPECIFIED_8197" }
            try {
                SpecialistOwnership7951.onFdgVerdict7951(ts.mint, lane, version,
                    false, reason)
                PipelineHealthCollector.labelInc("FDG_EARLY_TERMINAL_ACCOUNTED_8197_$lane")
            } catch (_: Throwable) {}
        }
        return result
    }
}
