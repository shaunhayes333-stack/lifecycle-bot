package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506

/** The elected specialist carries its own FDG decision into the canonical seal. */
internal object SpecialistPreauthSeal7834 {
    internal fun refusal(
        decision: FinalDecisionGate.FinalDecision?, mint: String, lane: String, paper: Boolean,
    ): String? = when {
        decision == null -> "FDG_DECISION_MISSING_7835"
        !decision.canExecute() -> decision.blockReason ?: decision.approvalReason.ifBlank { "FDG_NOT_EXECUTABLE_7835" }
        decision.mint != mint -> "FDG_MINT_MISMATCH_7835"
        (decision.mode == FinalDecisionGate.TradeMode.PAPER) != paper -> "FDG_MODE_MISMATCH_7835"
        decision.candidateVersion7835 <= 0L -> "FDG_CANDIDATE_VERSION_MISSING_7835"
        CanonicalLaneIdentity6506.canonical(decision.canonicalLane7835) != CanonicalLaneIdentity6506.canonical(lane) -> "FDG_LANE_MISMATCH_7835"
        !decision.sizeSol.isFinite() || decision.sizeSol <= 0.0 -> "FDG_SIZE_NOT_EXECUTABLE_7835"
        else -> null
    }

    fun ensure(
        ts: TokenState, decision: FinalDecisionGate.FinalDecision, lane: String, maximumSizeSol: Double,
    ): ExecutableOpenGate.ExecutionIntent? {
        val paper = decision.mode == FinalDecisionGate.TradeMode.PAPER
        if (refusal(decision, ts.mint, lane, paper) != null || !maximumSizeSol.isFinite() || maximumSizeSol <= 0.0) return null
        if (paper != RuntimeModeAuthority.isPaper()) return null
        val canonicalLane = CanonicalLaneIdentity6506.canonical(lane)
        val tokenMap = TokenMapAuthority.ensureDiscoveryTokenMap(ts, ts.source)
        com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.refreshFromExecutableTokenMap6614(
            mint = ts.mint,
            pairOrPool = tokenMap.poolAddress.ifBlank { tokenMap.pairAddress.ifBlank { ts.lastPricePoolAddr.ifBlank { ts.pairAddress } } },
            quoteMint = tokenMap.quoteMint.ifBlank { "USD" },
            source = ts.lastPriceSource.ifBlank { tokenMap.sourceScanner.ifBlank { ts.source } },
            priceUsd = tokenMap.priceUsd ?: ts.lastPrice,
            liquidityUsd = tokenMap.liquidityUsd ?: ts.lastLiquidityUsd,
            routeStatus = tokenMap.routeStatus,
            evidenceTimestampMs = if (tokenMap.priceUsd != null) tokenMap.updatedAtMs.takeIf { it > 0L } ?: ts.lastPriceUpdate else ts.lastPriceUpdate,
        )
        val size = minOf(decision.sizeSol, maximumSizeSol)
        ExecutableOpenGate.recordPrimaryLane7835(ts.mint, decision.candidateVersion7835, canonicalLane)
        fun sealOnce7840(): ExecutableOpenGate.ExecutionIntent? =
            ExecutableOpenGate.recordFdgAndGetIntent6533(
                mint = ts.mint, symbol = ts.symbol, lane = canonicalLane,
                canExecute = true, reason = decision.blockReason, signal = "BUY",
                rugScore = ts.safety.rugcheckScore, safetyTier = ts.safety.tier.name,
                liquidityUsd = ts.lastLiquidityUsd, hardNoReasons = ts.safety.hardBlockReasons.toList(),
                preFdgVerdict = "BUY", candidateVersion = decision.candidateVersion7835,
                entryScore = decision.effectiveEntryScore7687,
                tokenMapRouteStatus = tokenMap.routeStatus, tokenMapHydrationComplete = tokenMap.hydrationComplete,
                tokenMapExpectedOut = tokenMap.expectedOutAmount, tokenMapProviderAttempts = tokenMap.providerAttempts,
                requiresSolanaTokenMap = true, allowTrunkExecutionHandoff6533 = true,
                resolvedSizeSol6558 = size,
            )?.takeIf {
                it.mint == ts.mint && it.mode == (if (paper) "PAPER" else "LIVE")
            }?.takeIf {
                it.candidateVersion == decision.candidateVersion7835 &&
                    CanonicalLaneIdentity6506.canonical(it.canonicalLane) == canonicalLane &&
                    it.fdgAllowed && it.fdgVerdict == "BUY" && it.hardNoReasons.isEmpty() &&
                    it.resolvedSize > 0.0 && it.resolvedSize <= size + 1e-9
            }

        val first7840 = sealOnce7840()
        if (first7840 != null) return first7840

        // V5.0.7840 — a same-decision retry closes the measured seal race
        // without borrowing another lane/version and without fabricating an
        // intent after a hard safety revoke. recordFdgAndGetIntent6533 remains
        // the authority on both attempts; if the facts now hard-block, both
        // attempts return null.
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_FDG_SEAL_RETRY_7840")
            ForensicLogger.lifecycle(
                "SPECIALIST_FDG_SEAL_RETRY_7840",
                "mint=${ts.mint.take(10)} lane=$canonicalLane version=${decision.candidateVersion7835} action=same_decision_idempotent_retry",
            )
        } catch (_: Throwable) {}
        val retry7840 = sealOnce7840()
        try {
            PipelineHealthCollector.labelInc(
                if (retry7840 != null) "SPECIALIST_FDG_SEAL_RETRY_HEALED_7840"
                else "SPECIALIST_FDG_SEAL_RETRY_FAILED_7840"
            )
        } catch (_: Throwable) {}
        return retry7840
    }
}
