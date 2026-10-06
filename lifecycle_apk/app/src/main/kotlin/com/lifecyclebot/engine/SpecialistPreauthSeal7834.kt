package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506

/**
 * V5.0.7834 — specialist seal-before-authorize closure extracted from
 * BotService.processTokenCycle so the Android verifier budget cannot regress.
 * This creates no new decision authority; it only materializes the canonical
 * intent for an FDG BUY that already passed, with the exact lane/version/size.
 */
internal object SpecialistPreauthSeal7834 {
    fun ensure(
        paperMode: Boolean, mint: String, symbol: String, lane: String, candidateVersion: Long,
        fdgCanExecute: Boolean, fdgReason: String?, resolvedSizeSol: Double, rugScore: Int,
        safetyTier: String, liquidityUsd: Double, hardNoReasons: List<String>, entryScore: Int,
        tokenMapRouteStatus: String, tokenMapHydrationComplete: Boolean, tokenMapExpectedOut: Double,
        tokenMapProviderAttempts: Int,
    ): ExecutableOpenGate.ExecutionIntent? {
        val mode = if (paperMode) "PAPER" else "LIVE"
        val canonicalLane = CanonicalLaneIdentity6506.canonical(lane)
        val existing = try {
            ExecutableOpenGate.activeExecutionIntent6519(mode, mint, candidateVersion)
                ?.takeIf { CanonicalLaneIdentity6506.canonical(it.canonicalLane) == canonicalLane }
        } catch (_: Throwable) { null }
        if (existing != null) {
            try { PipelineHealthCollector.labelInc("PRIMARY_SPINE_SEALED_INTENT_REUSED_7467_$canonicalLane") } catch (_: Throwable) {}
            return existing
        }
        if (!fdgCanExecute || !resolvedSizeSol.isFinite() || resolvedSizeSol <= 0.0) {
            try {
                PipelineHealthCollector.labelInc("PRIMARY_SPINE_SEAL_STILL_MISSING_7834_$canonicalLane")
                ForensicLogger.lifecycle("PRIMARY_SPINE_SEAL_STILL_MISSING_7834",
                    "lane=$canonicalLane mint=${mint.take(10)} version=$candidateVersion fdgCan=$fdgCanExecute size=$resolvedSizeSol action=refuse_no_unsealed_execution")
            } catch (_: Throwable) {}
            return null
        }
        val sealed = try {
            ExecutableOpenGate.recordFdgAndGetIntent6533(
                mint = mint, symbol = symbol, lane = canonicalLane, canExecute = true, reason = fdgReason, signal = "BUY",
                rugScore = rugScore, safetyTier = safetyTier, liquidityUsd = liquidityUsd, hardNoReasons = hardNoReasons,
                preFdgVerdict = "BUY", candidateVersion = candidateVersion, entryScore = entryScore,
                tokenMapRouteStatus = tokenMapRouteStatus, tokenMapHydrationComplete = tokenMapHydrationComplete,
                tokenMapExpectedOut = tokenMapExpectedOut, tokenMapProviderAttempts = tokenMapProviderAttempts,
                requiresSolanaTokenMap = true, allowTrunkExecutionHandoff6533 = true, resolvedSizeSol6558 = resolvedSizeSol,
            )?.takeIf {
                CanonicalLaneIdentity6506.canonical(it.canonicalLane) == canonicalLane &&
                    it.fdgAllowed && it.fdgVerdict.equals("BUY", true)
            }
        } catch (_: Throwable) { null }
        try {
            if (sealed != null) {
                PipelineHealthCollector.labelInc("PRIMARY_SPINE_PREAUTH_SEAL_CREATED_7834")
                PipelineHealthCollector.labelInc("PRIMARY_SPINE_PREAUTH_SEAL_CREATED_7834_$canonicalLane")
            } else {
                PipelineHealthCollector.labelInc("PRIMARY_SPINE_SEAL_STILL_MISSING_7834_$canonicalLane")
            }
        } catch (_: Throwable) {}
        return sealed
    }
}
