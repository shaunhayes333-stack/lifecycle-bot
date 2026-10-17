package com.lifecyclebot.engine

/** Execution consumes the exact authorized order; changing its size requires a new decision. */
internal object SealedExecutionSize7835 {
    fun refusal(
        intent: ExecutableOpenGate.ExecutionIntent?, mint: String, mode: String,
        lane: String, requestedSol: Double,
    ): String? = when {
        intent == null -> "EXECUTION_TICKET_MISSING_7835"
        intent.mint != mint || !intent.mode.equals(mode, true) -> "EXECUTION_TICKET_IDENTITY_MISMATCH_7835"
        LaneAlias.normalize(intent.canonicalLane) != LaneAlias.normalize(lane) -> "EXECUTION_TICKET_LANE_MISMATCH_7835"
        !intent.fdgAllowed || intent.fdgVerdict != "BUY" || intent.hardNoReasons.isNotEmpty() -> "EXECUTION_TICKET_NOT_BUY_7835"
        !intent.resolvedSize.isFinite() || intent.resolvedSize <= 0.0 -> "EXECUTION_TICKET_INVALID_SIZE_7835"
        !requestedSol.isFinite() || kotlin.math.abs(requestedSol - intent.resolvedSize) > 1e-9 -> "EXECUTION_TICKET_SIZE_MISMATCH_7835"
        else -> null
    }

    /** A still-valid FDG approval owns its score, not a later mutable lane prior.
     * This is not route, wallet, finality or safety permission; those remain downstream.
     */
    fun strategyScore8198(
        intent: ExecutableOpenGate.ExecutionIntent?, attemptId: String, mint: String,
        lane: String, nowMs: Long = System.currentTimeMillis(),
    ): Double? {
        if (intent == null || attemptId.isBlank() || intent.attemptId != attemptId) return null
        if (refusal(intent, mint, "LIVE", lane, intent.resolvedSize) != null) return null
        if (intent.candidateVersion <= 0L || intent.createdAt > nowMs ||
            intent.expiresAtMs6613 <= nowMs || intent.effectiveEntryScore7256 !in 0..100) return null
        if (intent.finalDecision6613 != ExecutableOpenGate.CanonicalFinalDecision6613.BUY ||
            intent.fdgDecisionId6613.isBlank()) return null
        return intent.effectiveEntryScore7256.toDouble()
    }

    fun boundsRefusal(size: Double, cap: Double, minimum: Double): String? = when {
        !size.isFinite() || size <= 0.0 -> "EXECUTION_SIZE_INVALID_7835"
        !cap.isFinite() || cap < size - 1e-9 -> "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835"
        !minimum.isFinite() || size < minimum - 1e-9 -> "SEALED_SIZE_BELOW_CURRENT_MINIMUM_7835"
        else -> null
    }
}
