package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6532 §CANONICAL_SIZING_BRIDGE — thin façade so the six
 * specialized traders (Forex / TokenizedStock / Commodities / Metals /
 * CryptoAlt / PerpsTraderAI) route their sizing through the canonical
 * OrderSizeResolver6441 instead of computing positionSizeSol privately.
 *
 * Operator source-level audit (Feb 2026):
 *   > "Put Markets/Crypto through the canonical sizing/execution
 *   >  contract. Your acceptance audit already catches the problem:
 *   >  six executions exist while OrderSizeResolver resolves=0 and the
 *   >  external idempotency rows are zero. Specialized traders should
 *   >  not create economic positions behind the canonical executor's
 *   >  back."
 *
 * Behaviour:
 *   - Delegates to OrderSizeResolver6441.resolve so
 *     OrderSizeResolver6441.statusLine() (`resolves=N exec=X …`)
 *     finally reflects specialized-trader activity.
 *   - Stamps `CANONICAL_SIZING_BRIDGE_6532|CLASS=<class>|LANE=<lane>|
 *     EXEC=<bool>` so the operator can grep the funnel for the
 *     per-trader distribution.
 *   - Returns Resolution unchanged so the caller can honour risk /
 *     ladder / cash / lane clamps + the minimum-executable gate.
 *
 * The specialized traders are expected to size against `finalSizeSol`
 * when `executable == true`, and skip the entry otherwise (with the
 * caller's own SIGNAL_SKIPPED_BELOW_MINIMUM telemetry).
 */
object CanonicalSizingBridge6532 {

    fun resolve(
        requestedSol: Double,
        assetClass: AssetClass,
        laneName: String,
        walletSol: Double,
        paperMode: Boolean,
        laneRiskCapSol: Double = 1.0,
        laneMinExecutableSol: Double = 0.001,
        canonicalAssetId: String = "",
        symbol: String = laneName,
        price: Double = 1.0,
        candidateVersion: Long = -1L,
        source: String = "specialist",
        causalEventId: String = "",
    ): OrderSizeResolver6441.Resolution {
        // V5.0.6620 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §9 —
        //   candidateVersion authority MUST be
        //   LaneExecutionCoordinator.candidateVersionFor(mint). The
        //   previous default `System.currentTimeMillis()` created a
        //   second authority (raw wall-clock ms) that never matched
        //   the executor's bucket-based check → EXEC_TICKET_RESTORED_
        //   IMMUTABLE reported ticket versions in the trillions while
        //   the executor saw the bucket in the tens of millions,
        //   guaranteeing mismatch and the "NO_EXECUTION_INTENT" bug.
        //   Sentinel -1L means the caller didn't provide a version; we
        //   derive it from the canonical authority. Sentinel > 0L is
        //   respected verbatim (caller has sealed a version).
        val resolvedCandidateVersion6620 = if (candidateVersion > 0L) candidateVersion
            else try {
                com.lifecyclebot.engine.LaneExecutionCoordinator
                    .candidateVersionFor(canonicalAssetId.ifBlank { symbol })
            } catch (_: Throwable) { 0L }
        try {
            if (candidateVersion <= 0L) {
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("CANDIDATE_VERSION_WALLCLOCK_ELIMINATED_6620")
            }
        } catch (_: Throwable) {}

        // V5.0.6674 §SPECIALIST_CAUSAL_SIZING_CONTINUITY — source repair.
        val resolvedCausalEventId6674 = causalEventId.ifBlank {
            if (assetClass == AssetClass.SOLANA_TOKEN && canonicalAssetId.isNotBlank() && resolvedCandidateVersion6620 > 0L) {
                val mode6674 = if (paperMode) "PAPER" else "LIVE"
                val activeAttempt6674 = try {
                    com.lifecyclebot.engine.ExecutableOpenGate
                        .activeExecutionIntent6519(mode6674, canonicalAssetId, resolvedCandidateVersion6620)
                        ?.attemptId
                } catch (_: Throwable) { null }
                activeAttempt6674?.takeIf { it.isNotBlank() } ?: run {
                    val generation6674 = try { com.lifecyclebot.engine.BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }
                    "$generation6674:$mode6674:$canonicalAssetId:BUY:${laneName.uppercase()}:$resolvedCandidateVersion6620:SIZE"
                }
            } else ""
        }

        // V5.0.6689 §SHARED_CAPITAL_COMPOUNDING — paper callers are not
        // permitted to drive the compounding ladder from a lane-local/status
        // wallet mirror. OrderSizeResolver already hard-caps affordability
        // against PaperCapitalAuthority6577; bind its walletSol/ladder input to
        // that SAME cash authority here so realized proceeds immediately become
        // the sizing bankroll for the next trade. LIVE keeps the observed wallet
        // passed by the wallet/finality path.
        val effectiveWalletSol6689 = if (paperMode) {
            try {
                val sharedCash6689 = PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0)
                try {
                    PipelineHealthCollector.labelInc("PAPER_SIZING_SHARED_CASH_BOUND_6689")
                    if (walletSol.isFinite() && kotlin.math.abs(walletSol - sharedCash6689) > 0.001) {
                        PipelineHealthCollector.labelInc("PAPER_SIZING_CALLER_CASH_DIVERGENCE_6689")
                        ForensicLogger.lifecycle(
                            "PAPER_SIZING_CALLER_CASH_DIVERGENCE_6689",
                            "lane=$laneName caller=${"%.6f".format(walletSol)} shared=${"%.6f".format(sharedCash6689)} " +
                                "action=shared_cash_wins_for_compounding",
                        )
                    }
                } catch (_: Throwable) {}
                sharedCash6689
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelInc("PAPER_SIZING_SHARED_CASH_UNAVAILABLE_6689") } catch (_: Throwable) {}
                0.0
            }
        } else walletSol.coerceAtLeast(0.0)

        // V5.0.6542 §ASSET_AWARE_PAPER_MIN — operator: PAPER cross-asset
        // learning must be able to take legitimate smaller probes.
        val effectiveMinSol6542 = if (paperMode && assetClass != AssetClass.SOLANA_TOKEN)
            minOf(laneMinExecutableSol, 0.005)
        else
            laneMinExecutableSol
        val res = OrderSizeResolver6441.resolve(
            requestedSol = requestedSol,
            laneName = laneName,
            walletSol = effectiveWalletSol6689,
            paperMode = paperMode,
            laneRiskCapSol = laneRiskCapSol,
            laneMinExecutableSol = effectiveMinSol6542,
            applyPaperMemeMinimum = assetClass == AssetClass.SOLANA_TOKEN,
            causalEventId = resolvedCausalEventId6674,
        )
        try {
            PipelineHealthCollector.labelInc(
                "CANONICAL_SIZING_BRIDGE_6532|CLASS=${assetClass.tag}|LANE=$laneName|EXEC=${res.executable}"
            )
            if (resolvedCausalEventId6674.isNotBlank()) {
                PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_SIZING_ID_PROPAGATED_6674")
            }
        } catch (_: Throwable) {}
        // V5.0.6558 — sizing is advisory input, never a pre-FDG authorization.
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_SIZING_BRIDGE_6532",
                "class=${assetClass.tag} lane=$laneName candidateVersion6620=$resolvedCandidateVersion6620 " +
                    "cash6689=${"%.5f".format(effectiveWalletSol6689)} causal=${resolvedCausalEventId6674.take(48)} ${res.trace()}",
            )
        } catch (_: Throwable) {}
        return res
    }
}
