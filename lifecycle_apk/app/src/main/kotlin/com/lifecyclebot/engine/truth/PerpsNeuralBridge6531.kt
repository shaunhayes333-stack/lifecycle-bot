package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.perps.PerpsPositionSizer

/**
 * V5.0.6531 §PERPS_NEURAL_BRIDGE — feed paper cross-asset outcomes
 * (Forex / Stock / Commodity / Metal / Crypto-alt) into the perps
 * sizing brain now that AssetClass tags every canonical row.
 *
 * Operator source-level audit (Feb 2026):
 *   > "Neural bridge: turn on the cross-lane learning path from paper
 *   >  Forex/Stocks into the perps sizing brain now that assetClass
 *   >  tags every row."
 *
 * Design:
 *   - Called from Executor.recordTrade() on TERMINAL SELL (partial
 *     sells still learn separately via the trader-side loops).
 *   - Only bridges outcomes for AssetClass values that PerpsPositionSizer
 *     can actually size (Forex/Stock/Metal/Commodity/CryptoAlt/Perps).
 *   - SOLANA_TOKEN outcomes stay in the meme learner (Executor →
 *     TokenWinMemory / ScoreExpectancyTracker) — the perps sizer is
 *     Kelly-driven and expects modest sample sizes, so we do not
 *     pollute it with 10 000 meme scratches.
 *
 * Reads the assetClass off the canonical position (set at open by
 * CanonicalPaperTransaction6486.open) — no string parsing, no re-inference.
 */
object PerpsNeuralBridge6531 {

    /**
     * Feed a terminal outcome into the perps sizer if the class is
     * routable. Returns true when the outcome was recorded.
     */
    fun recordTerminalOutcome(
        assetClass: AssetClass,
        symbol: String,
        pnlPct: Double,
    ): Boolean {
        if (!pnlPct.isFinite()) return false
        // Only bridge classes the sizer actually consumes.
        val routable = when (assetClass) {
            AssetClass.FOREX,
            AssetClass.STOCK,
            AssetClass.COMMODITY,
            AssetClass.METAL,
            AssetClass.CRYPTO_ALT,
            AssetClass.PERPS -> true
            AssetClass.SOLANA_TOKEN,
            AssetClass.UNKNOWN -> false
        }
        if (!routable) return false
        val market = CrossAssetMarkRouter6530.resolveMarket(symbol) ?: run {
            emit("UNROUTABLE_SYMBOL", assetClass, symbol, pnlPct, "no PerpsMarket entry matched")
            return false
        }
        return try {
            PerpsPositionSizer.recordTrade(market, pnlPct)
            emit("OK", assetClass, symbol, pnlPct, "market=${market.name}")
            true
        } catch (e: Throwable) {
            emit("SIZER_ERROR", assetClass, symbol, pnlPct, "err=${e.javaClass.simpleName}:${e.message?.take(60)}")
            false
        }
    }

    private fun emit(status: String, assetClass: AssetClass, symbol: String, pnlPct: Double, detail: String) {
        try {
            PipelineHealthCollector.labelInc("PERPS_NEURAL_BRIDGE_6531|STATUS=$status|CLASS=${assetClass.tag}")
        } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "PERPS_NEURAL_BRIDGE_6531",
                "status=$status class=${assetClass.tag} symbol=$symbol pnlPct=${"%+.2f".format(pnlPct)} $detail",
            )
        } catch (_: Throwable) {}
    }
}
