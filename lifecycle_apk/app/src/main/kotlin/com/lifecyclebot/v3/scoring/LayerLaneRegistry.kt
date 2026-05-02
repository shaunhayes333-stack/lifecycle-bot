package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.LaneTag

/**
 * V5.9.414 — LAYER LANE REGISTRY
 *
 * Each AI layer in EducationSubLayerAI.REGISTERED_LAYERS declares which
 * lanes it wants to learn from.  The outcome dispatcher (RealTimeAccuracy +
 * recordSimpleTradeOutcome) consults this registry and SKIPS layers whose
 * lane set doesn't intersect the trade's lane.
 *
 * Why this exists:
 *   The user's neural-network screen showed a healthy inner ring (24-ish
 *   green-pulsing dots that learn from every meme trade) and a dead outer
 *   ring of single-purpose dots (REFLEX, EXEC_COST, FUNDING, NEWS, MEV,
 *   OP_FINGERPRINT, SESSION, OB_PULSE, PEER, DNA, FUND etc.) which were
 *   getting "neutral touch" updates on every trade but no real lane data
 *   they were designed for.  Lane-blind fan-out was both:
 *     • polluting meme-tuned layers with stock outcomes, and
 *     • starving stock/perp/forex-tuned layers of any signal at all
 *       because the bot's volume is 95 % memes.
 *
 * Multi-lane / GENERIC layers (the inner ring of cross-asset learners —
 * BehaviorLearning, MetaCognition, FluidLearning, EdgeLearning,
 * MarketRegime, AdaptiveLearning, etc.) keep seeing every trade.
 *
 * Single-purpose layers see ONLY their lane's trades.  A layer that's not
 * registered defaults to GENERIC, preserving prior behavior.
 */
object LayerLaneRegistry {

    private val ALL: Set<LaneTag.Lane> = setOf(
        LaneTag.Lane.MEME, LaneTag.Lane.BLUECHIP, LaneTag.Lane.ALT,
        LaneTag.Lane.STOCK, LaneTag.Lane.PERP, LaneTag.Lane.FOREX,
        LaneTag.Lane.METAL, LaneTag.Lane.COMMODITY, LaneTag.Lane.CORE,
        LaneTag.Lane.UNKNOWN,
    )
    private val MEME: Set<LaneTag.Lane>     = setOf(LaneTag.Lane.MEME)
    private val STOCK: Set<LaneTag.Lane>    = setOf(LaneTag.Lane.STOCK)
    private val PERP: Set<LaneTag.Lane>     = setOf(LaneTag.Lane.PERP)
    private val ALT: Set<LaneTag.Lane>      = setOf(LaneTag.Lane.ALT)
    private val MEME_ALT: Set<LaneTag.Lane> = setOf(LaneTag.Lane.MEME, LaneTag.Lane.ALT)
    private val STOCK_PERP_FOREX: Set<LaneTag.Lane> =
        setOf(LaneTag.Lane.STOCK, LaneTag.Lane.PERP, LaneTag.Lane.FOREX,
              LaneTag.Lane.METAL, LaneTag.Lane.COMMODITY)
    private val ORDERBOOK_LANES: Set<LaneTag.Lane> =
        setOf(LaneTag.Lane.STOCK, LaneTag.Lane.PERP, LaneTag.Lane.FOREX, LaneTag.Lane.METAL)

    /**
     * Lane affinity per registered layer name.  Layers not in this map
     * default to ALL (GENERIC) so unfamiliar names keep behaving as
     * before.  Entries here strictly NARROW the dispatch.
     */
    private val LAYER_LANES: Map<String, Set<LaneTag.Lane>> = mapOf(
        // ── Meme-only sub-traders ───────────────────────────────────────
        "ShitCoinTraderAI"            to MEME,
        "MoonshotTraderAI"            to MEME,
        "NarrativeDetectorAI"         to MEME,         // pump-narratives are a meme thing
        "TokenDNAClusteringAI"        to MEME,         // meme DNA only
        "OperatorFingerprintAI"       to MEME,         // pump.fun deployer fingerprint
        "MEVDetectionAI"              to MEME,         // Solana DEX MEV scanning
        "DipHunterAI"                 to MEME_ALT,     // micro-cap dips (memes + alts)
        "CashGenerationAI"            to MEME,         // Treasury runs only on memes
        "BlueChipTraderAI"            to MEME,         // BlueChip = quality memes here

        // ── Stocks / perps / forex / metals lane ────────────────────────
        "CorrelationHedgeAI"          to STOCK_PERP_FOREX,
        "FundingRateAwarenessAI"      to PERP,         // funding rate is perp-only
        "LiquidityExitPathAI"         to STOCK_PERP_FOREX,
        "ExecutionCostPredictorAI"    to STOCK_PERP_FOREX,
        "OrderbookImbalancePulseAI"   to ORDERBOOK_LANES,
        "SessionEdgeAI"               to STOCK_PERP_FOREX,
        "NewsShockAI"                 to STOCK_PERP_FOREX,
        "StablecoinFlowAI"            to setOf(LaneTag.Lane.ALT, LaneTag.Lane.PERP),
        "PeerAlphaVerificationAI"     to ALT,

        // ── GENERIC cross-asset learners (kept multi-lane) ──────────────
        // These are the inner-ring "always learning" layers and are listed
        // here only for explicitness; absence would default to ALL anyway.
        "HoldTimeOptimizerAI"         to ALL,
        "MomentumPredictorAI"         to ALL,
        "TimeOptimizationAI"          to ALL,
        "LiquidityDepthAI"            to ALL,
        "WhaleTrackerAI"              to ALL,
        "MarketRegimeAI"              to ALL,
        "AdaptiveLearningEngine"     to ALL,
        "EdgeLearning"                to ALL,
        "TokenWinMemory"              to ALL,
        "BehaviorLearning"            to ALL,
        "MetaCognitionAI"             to ALL,
        "FluidLearningAI"             to ALL,
        "CollectiveIntelligenceAI"    to ALL,
        "VolatilityRegimeAI"          to ALL,
        "OrderFlowImbalanceAI"        to ALL,
        "SmartMoneyDivergenceAI"      to ALL,
        "LiquidityCycleAI"            to ALL,
        "FearGreedAI"                 to ALL,
        "SellOptimizationAI"          to ALL,
        "DrawdownCircuitAI"           to ALL,
        "CapitalEfficiencyAI"         to ALL,
        "AITrustNetworkAI"            to ALL,
        "ReflexAI"                    to ALL,
    )

    /**
     * Should the layer learn from a trade in this lane?
     * Returns true if the layer is GENERIC (no entry in map / has the
     * full ALL set) OR the layer's affinity contains the trade's lane.
     */
    fun shouldLearn(layerName: String, lane: LaneTag.Lane): Boolean {
        val affinity = LAYER_LANES[layerName] ?: return true   // unknown → GENERIC
        if (affinity === ALL) return true
        // UNKNOWN lane (no tradingMode) is allowed through to every layer
        // so we don't accidentally orphan trades that lost their tag.
        if (lane == LaneTag.Lane.UNKNOWN) return true
        return lane in affinity
    }

    /** Convenience for callers that only have the trading-mode string. */
    fun shouldLearn(layerName: String, tradingMode: String?): Boolean =
        shouldLearn(layerName, LaneTag.fromTradingMode(tradingMode))

    /** Number of layers gated to a single non-GENERIC lane (for telemetry). */
    fun singleLaneLayerCount(): Int =
        LAYER_LANES.values.count { it.size == 1 || (it !== ALL && it.size <= 5) }
}
