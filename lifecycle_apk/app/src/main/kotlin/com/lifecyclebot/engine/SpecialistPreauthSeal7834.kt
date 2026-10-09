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
        if (refusal(decision, ts.mint, lane, paper) != null) return null
        if (paper != RuntimeModeAuthority.isPaper()) return null
        val canonicalLane = CanonicalLaneIdentity6506.canonical(lane)
        val tokenMap = TokenMapAuthority.ensureDiscoveryTokenMap(ts, ts.source)
        // V5.0.7948 — the seal captures the entry mark with the SAME observed depth
        // the executor's entry snapshot accepts (TokenMapAuthority.observedLiquidityUsd:
        // tick, token map, then curve reserves). `tokenMap.liquidityUsd ?: ts.lastLiquidityUsd`
        // passed 0 for curve/fresh pools, so no executable mark was sealed and the
        // selected QUALITY/MOONSHOT candidate deferred as ENTRY_MARKET_SNAPSHOT_MISSING.
        val sealLiquidity7948 = SpecialistExecution7948.sealLiquidityUsd7948(
            tokenMap.liquidityUsd, ts.lastLiquidityUsd,
            try { TokenMapAuthority.observedLiquidityUsd(ts) } catch (_: Throwable) { 0.0 },
        )
        com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.refreshFromExecutableTokenMap6614(
            mint = ts.mint,
            pairOrPool = tokenMap.poolAddress.ifBlank { tokenMap.pairAddress.ifBlank { ts.lastPricePoolAddr.ifBlank { ts.pairAddress } } },
            quoteMint = tokenMap.quoteMint.ifBlank { "USD" },
            source = ts.lastPriceSource.ifBlank { tokenMap.sourceScanner.ifBlank { ts.source } },
            priceUsd = tokenMap.priceUsd ?: ts.lastPrice,
            liquidityUsd = sealLiquidity7948,
            routeStatus = tokenMap.routeStatus,
            evidenceTimestampMs = if (tokenMap.priceUsd != null) tokenMap.priceObservedAtMs7858 else ts.lastPriceUpdate,
        )
        // V5.0.7853 ONE_AUTHORITATIVE_SIZE: FDG's size already carries live risk
        // policy, depth caps and the current route minimum. A caller figure
        // reshaped after the verdict (AutoMode quiet-hour 0.35x, graduated
        // 0.35-0.5x, V3/bridge sizes) is evidence only; taking the minimum
        // turned legal tickets sub-routable. liveBuy still re-checks bounds.
        val size = decision.sizeSol
        if (maximumSizeSol < size - 1e-9) postFdgRewriteIgnored7853(ts.mint, lane, size, maximumSizeSol)
        ExecutableOpenGate.recordPrimaryLane7835(ts.mint, decision.candidateVersion7835, canonicalLane)
        // V5.0.7872 — reuse only on clean current safety facts; a new hard fact
        // goes through the seal, which revokes the same-version ticket.
        if (ts.safety.hardBlockReasons.isEmpty()) ExecutableOpenGate.reuseSealedIntent7871(
            if (paper) "PAPER" else "LIVE", ts.mint, decision.candidateVersion7835, canonicalLane, size,
        )?.let { return it }
        fun sealOnce7840(): ExecutableOpenGate.ExecutionIntent? =
            ExecutableOpenGate.recordFdgAndGetIntent6533(
                mint = ts.mint, symbol = ts.symbol, lane = canonicalLane,
                canExecute = true, reason = decision.blockReason, signal = "BUY",
                rugScore = ts.safety.rugcheckScore, safetyTier = ts.safety.tier.name,
                liquidityUsd = sealLiquidity7948, hardNoReasons = ts.safety.hardBlockReasons.toList(),
                preFdgVerdict = "BUY", candidateVersion = decision.candidateVersion7835,
                entryScore = decision.effectiveEntryScore7687,
                tokenMapRouteStatus = tokenMap.routeStatus, tokenMapHydrationComplete = tokenMap.hydrationComplete,
                tokenMapExpectedOut = tokenMap.expectedOutAmount, tokenMapProviderAttempts = tokenMap.providerAttempts,
                requiresSolanaTokenMap = true, allowTrunkExecutionHandoff6533 = true,
                resolvedSizeSol6558 = size,
                authoritativeFdgDecision7858 = decision,
            )?.takeIf {
                it.mint == ts.mint && it.mode == (if (paper) "PAPER" else "LIVE")
            }?.takeIf {
                it.candidateVersion == decision.candidateVersion7835 &&
                    CanonicalLaneIdentity6506.canonical(it.canonicalLane) == canonicalLane &&
                    it.fdgAllowed && it.fdgVerdict == "BUY" && it.hardNoReasons.isEmpty() &&
                    // V5.0.7948 — within the FDG verdict (same rule as reuse), not
                    // bit-equal: the first seal of a version caps a later, larger FDG size.
                    SpecialistExecution7948.sealWithinFdgSize7948(it.resolvedSize, size)
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

    internal fun postFdgRewriteIgnored7853(mint: String, lane: String, sealed: Double, proposed: Double) {
        try {
            PipelineHealthCollector.labelInc("POST_FDG_SIZE_REWRITE_IGNORED_7853")
            if (ForensicEmitRateLimiter6356.shouldEmit("POST_FDG_SIZE_REWRITE_IGNORED_7853", mint)) {
                ForensicLogger.lifecycle(
                    "POST_FDG_SIZE_REWRITE_IGNORED_7853",
                    "mint=${mint.take(10)} lane=$lane sealed=${"%.6f".format(sealed)} proposed=${"%.6f".format(proposed)} action=fdg_size_is_authoritative",
                )
            }
        } catch (_: Throwable) {}
    }


    /**
     * V5.0.7871 — why ensure() returned null, named, so TRADE_AUTH_SEAL_FAILED_7835
     * stops being one bucket for different causes (Field Manual L123).
     */
    internal fun failureReason7871(ts: TokenState, decision: FinalDecisionGate.FinalDecision?, lane: String, paper: Boolean): String {
        refusal(decision, ts.mint, lane, paper)?.let { r ->
            return r.uppercase().map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").take(48)
        }
        if (paper != RuntimeModeAuthority.isPaper()) return "RUNTIME_MODE_CHANGED"
        if (ts.safety.hardBlockReasons.isNotEmpty()) return "HARD_NO_AFTER_FDG"
        val d = decision ?: return "FDG_DECISION_MISSING_7835"
        val live = ExecutableOpenGate.activeExecutionIntent6519(if (paper) "PAPER" else "LIVE", ts.mint, d.candidateVersion7835)
        return when {
            live == null -> "NO_LIVE_TICKET_FOR_VERSION"
            CanonicalLaneIdentity6506.canonical(live.canonicalLane) != CanonicalLaneIdentity6506.canonical(lane) -> "TICKET_OWNED_BY_OTHER_LANE"
            live.resolvedSize > d.sizeSol + 1e-9 -> "TICKET_SIZE_ABOVE_FDG"
            else -> "TICKET_NOT_BUY"
        }
    }
}
