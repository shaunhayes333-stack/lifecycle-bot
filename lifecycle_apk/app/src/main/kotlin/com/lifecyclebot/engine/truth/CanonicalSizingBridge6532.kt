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
        candidateVersion: Long = System.currentTimeMillis(),
        source: String = "specialist",
    ): OrderSizeResolver6441.Resolution {
        // V5.0.6542 §ASSET_AWARE_PAPER_MIN — operator: PAPER cross-asset
        // learning must be able to take legitimate smaller probes. Cash
        // is 1.6 SOL, a 2% cross-asset recommendation is only 0.032 SOL —
        // below the meme-shaped 0.05 SOL PAPER floor. Lower the effective
        // minimum for non-Solana asset classes in PAPER mode so the
        // canonical funnel doesn't SIZE_NOT_EXECUTABLE-block them.
        val effectiveMinSol6542 = if (paperMode && assetClass != AssetClass.SOLANA_TOKEN)
            minOf(laneMinExecutableSol, 0.005)
        else
            laneMinExecutableSol
        val res = OrderSizeResolver6441.resolve(
            requestedSol = requestedSol,
            laneName = laneName,
            walletSol = walletSol,
            paperMode = paperMode,
            laneRiskCapSol = laneRiskCapSol,
            laneMinExecutableSol = effectiveMinSol6542,
            applyPaperMemeMinimum = assetClass == AssetClass.SOLANA_TOKEN,
        )
        try {
            PipelineHealthCollector.labelInc(
                "CANONICAL_SIZING_BRIDGE_6532|CLASS=${assetClass.tag}|LANE=$laneName|EXEC=${res.executable}"
            )
        } catch (_: Throwable) {}
        // V5.0.6558 — sizing is advisory input, never a pre-FDG
        // authorization. The actual typed candidate is submitted exactly
        // once by the specialist's FDG path after safety/decision context
        // is complete. This prevents synthetic LONG intents and duplicate
        // pending authority rows from being created here.
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_SIZING_BRIDGE_6532",
                "class=${assetClass.tag} lane=$laneName ${res.trace()}",
            )
        } catch (_: Throwable) {}
        return res
    }
}
