package com.lifecyclebot.engine

/**
 * V5.9.852 — Multi-lane canonical publish helper.
 *
 * Before this push: only Executor.recordTrade (the meme path) emitted a
 * CanonicalTradeOutcome to the bus. Non-meme traders (Moonshot, ShitCoin,
 * BlueChip, Manipulated, CryptoAlt) all did their own bookkeeping in
 * closePosition() but bypassed CanonicalOutcomeBus entirely — which is
 * why Layer Readiness shows every layer DEGRADED_BAD_EV with rich/incomplete
 * ≈ 50/50. Non-meme outcomes never reached AdaptiveLearningEngine /
 * BehaviorLearning / MetaCognitionAI's rich-feature path.
 *
 * This helper accepts the raw close-time scalars each trader already has and
 * builds a CanonicalTradeOutcome with `featuresIncomplete=true` (the closes
 * don't carry the same TokenState depth that the meme executor does — that's
 * fine; learners can still pattern-match on assetClass/source/mode/pnl/holdSec).
 *
 * DOCTRINE
 * - Pure additive. Does NOT touch any existing local bookkeeping
 *   (V3JournalRecorder, TradingCopilot, BehaviorLearning, etc).
 * - Errors swallowed — never breaks the trader's own close path.
 * - Hard-coded `featuresIncomplete=true` so consumers (e.g. UnifiedScorer
 *   layer health) report these as the legitimate "lite" sample they are.
 */
object CanonicalPublishHelper {

    /**
     * @param tradeIdSeed unique per-trade identifier — use "{mint}_{exitTimeMs}"
     * @param source which trader (Moonshot/ShitCoin/BlueChip/Manip/CryptoAlt/...)
     * @param isPaper paper-mode flag
     * @param entryTimeMs original open timestamp (millis)
     * @param exitTimeMs close timestamp (millis)
     * @param entryPrice price at open
     * @param exitPrice  price at close
     * @param entrySol   SOL committed at open
     * @param exitSol    SOL returned at close (gross)
     * @param realizedPnlSol net SOL P&L (after fees)
     * @param realizedPnlPct % P&L (computed against entryPrice)
     * @param maxGainPct optional — highest-water mark P&L %
     * @param maxDrawdownPct optional — worst drawdown P&L %
     * @param closeReason short reason string (e.g. "MOONSHOT_FLAT_EXIT")
     * @param assetClass MEME / BLUECHIP / etc (default MEME)
     * @param entryScore optional — score at open if available
     */
    fun publishExit(
        tradeIdSeed: String,
        mint: String,
        symbol: String,
        source: TradeSource,
        isPaper: Boolean,
        entryTimeMs: Long,
        exitTimeMs: Long,
        entryPrice: Double,
        exitPrice: Double,
        entrySol: Double,
        exitSol: Double,
        realizedPnlSol: Double,
        realizedPnlPct: Double,
        maxGainPct: Double? = null,
        maxDrawdownPct: Double? = null,
        closeReason: String? = null,
        assetClass: AssetClass = AssetClass.MEME,
        entryScore: Double = 0.0,
    ) {
        try {
            val tradeId = tradeIdSeed
            // Idempotency — if Executor.recordTrade somehow also published this
            // tradeId, don't double-fan.
            if (CanonicalOutcomeBus.isRichPublished(tradeId)) return

            val resultEnum = when {
                realizedPnlPct >= 1.0 -> TradeResult.WIN
                realizedPnlPct <= -1.0 -> TradeResult.LOSS
                else -> TradeResult.BREAKEVEN
            }
            val executionEnum = ExecutionResult.EXECUTED
            val envEnum = if (isPaper) TradeEnvironment.PAPER else TradeEnvironment.LIVE
            val modeEnum = TradeMode.UNKNOWN
            val holdSec = if (entryTimeMs > 0) (exitTimeMs - entryTimeMs) / 1000 else null

            val features = mapOf(
                "entryScore"      to entryScore,
                "entryConfidence" to entryScore,
                "tradeSize"       to entrySol,
                "holdSec"         to (holdSec?.toDouble() ?: 0.0),
            )

            val rich = CanonicalTradeOutcome(
                tradeId          = tradeId,
                mint             = mint,
                symbol           = symbol,
                assetClass       = assetClass,
                mode             = modeEnum,
                source           = source,
                environment      = envEnum,
                entryTimeMs      = entryTimeMs,
                exitTimeMs       = exitTimeMs,
                entryPrice       = entryPrice,
                exitPrice        = exitPrice,
                entrySol         = entrySol.takeIf { it > 0.0 },
                exitSol          = exitSol,
                realizedPnlSol   = realizedPnlSol,
                realizedPnlPct   = realizedPnlPct,
                maxGainPct       = maxGainPct,
                maxDrawdownPct   = maxDrawdownPct,
                holdSeconds      = holdSec,
                result           = resultEnum,
                executionResult  = executionEnum,
                closeReason      = closeReason?.ifBlank { null },
                featuresAtEntry  = features,
                candidate        = null,        // no TokenState in these paths
                featuresIncomplete = true,      // lite sample — honest reporting
                bcSimOnly        = false,
            )
            CanonicalOutcomeBus.markRichPublished(tradeId)
            CanonicalOutcomeBus.publishUnchecked(rich)
        } catch (e: Exception) {
            ErrorLogger.debug("CanonicalPublishHelper", "publish failed: ${e.message?.take(80)}")
        }
    }
}
