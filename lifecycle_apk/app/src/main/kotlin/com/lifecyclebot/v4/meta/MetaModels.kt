package com.lifecyclebot.v4.meta

/**
 * ===============================================================================
 * AATE V4 META-INTELLIGENCE MODELS
 * ===============================================================================
 *
 * Shared data models for the cross-market intelligence bus.
 * Every AI module publishes AATESignals → CrossTalkFusionEngine aggregates →
 * FinalDecisionEngine consumes CrossTalkSnapshot.
 *
 * Architecture layer order:
 *   Market feeds → Base analyzers → Meta control → ModeRouter → CrossTalk →
 *   FinalDecisionEngine → Executor → EducationAI
 *
 * ===============================================================================
 */

// ═══════════════════════════════════════════════════════════════════════════
// ENUMS
// ═══════════════════════════════════════════════════════════════════════════

enum class GlobalRiskMode {
    RISK_ON,        // Broad participation, good liquidity, trend alignment
    RISK_OFF,       // Flight to safety, high vol, BTC weakness
    CHAOTIC,        // No clear direction, whipsaws, fragmented moves
    ROTATIONAL,     // Capital rotating between sectors/markets
    TRENDING,       // Clear directional move across markets
    MEAN_REVERT     // Extended move likely to revert
}

enum class SessionContext {
    ASIA,           // 00:00–08:00 UTC (Tokyo/HK/Singapore)
    LONDON,         // 08:00–13:00 UTC (London/Frankfurt)
    NY,             // 13:00–21:00 UTC (New York)
    ASIA_LONDON,    // Overlap 08:00–09:00 UTC
    LONDON_NY,      // Overlap 13:00–16:00 UTC
    OFF_HOURS       // Low liquidity periods
}

enum class FragilityLevel {
    STABLE,         // Deep liquidity, tight spreads, low wick frequency
    MODERATE,       // Normal conditions, some slippage risk
    FRAGILE,        // Thin books, wide spreads, wick-heavy
    CRITICAL        // Do not trade — liquidity vacuum or cascade risk
}

enum class NarrativePhase {
    EMERGING,       // New theme, low breadth, rising heat
    EXPANDING,      // Confirmed theme, growing breadth, strong persistence
    MATURE,         // High heat, maximum breadth, peak attention
    EXHAUSTING,     // Declining breadth, fading persistence, topping signals
    DEAD            // No heat, no breadth, narrative collapsed
}

enum class TrustLevel {
    UNTESTED,       // Not enough data (< 20 trades)
    DISTRUSTED,     // Poor recent performance, actively suppressed
    NEUTRAL,        // Average performance, no strong signal
    TRUSTED,        // Good recent performance, regime-fit
    ELITE           // Exceptional recent performance, high conviction
}

// ═══════════════════════════════════════════════════════════════════════════
// CORE SIGNAL — Published by every AI module
// ═══════════════════════════════════════════════════════════════════════════

data class AATESignal(
    val source: String,                     // Module name: "StrategyTrustAI", "LiquidityFragilityAI"
    val market: String,                     // "MEME", "STOCKS", "PERPS", "FOREX", "METALS", "COMMODITIES"
    val symbol: String? = null,             // Specific symbol if applicable: "SOL", "NVDA"
    val confidence: Double,                 // 0.0 - 1.0
    val direction: String? = null,          // "LONG", "SHORT", null (non-directional)
    val horizonSec: Int,                    // Expected relevance window in seconds
    val regimeTag: String? = null,          // Current regime assessment
    val fragilityScore: Double? = null,     // 0.0 (stable) - 1.0 (critical)
    val trustScore: Double? = null,         // 0.0 (distrusted) - 1.0 (elite)
    val leverageAllowed: Double? = null,    // Max leverage: 0.0 = no leverage, 5.0 = 5x
    val narrativeHeat: Double? = null,      // 0.0 (cold) - 1.0 (max heat)
    val rotationTarget: String? = null,     // Where capital should rotate to
    val executionConfidence: Double? = null, // 0.0 - 1.0 execution quality
    val riskFlags: List<String> = emptyList(), // ["HIGH_CORRELATION", "THIN_LIQUIDITY", "LIQUIDATION_PROXIMITY"]
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// FUSED SNAPSHOT — Output of CrossTalkFusionEngine, consumed by FDE
// ═══════════════════════════════════════════════════════════════════════════

data class CrossTalkSnapshot(
    val globalRiskMode: GlobalRiskMode,
    val sessionContext: SessionContext,
    val marketBias: Map<String, Double>,        // Market → capital bias (-1.0 to 1.0)
    val strategyTrust: Map<String, Double>,     // Strategy → trust multiplier (0.0 to 1.5)
    val narrativeMap: Map<String, Double>,       // Theme/sector → narrative heat (0.0 to 1.0)
    val fragilityMap: Map<String, Double>,        // Market/symbol → fragility (0.0 to 1.0)
    val leadLagLinks: List<LeadLagLink>,         // Active lead-lag relationships
    val leverageCap: Double,                     // Max global leverage allowed (0 = cash only)
    val portfolioHeat: Double,                   // 0.0 (cold) - 1.0 (max correlated exposure)
    val killFlags: List<String>,                 // Hard vetoes: ["NO_LEVERAGE", "NO_MEME", "NO_NEW_ENTRIES"]
    val perMarketCaps: Map<String, MarketCap>,   // Per-market confidence/size/leverage caps
    val timestamp: Long = System.currentTimeMillis()
)

data class LeadLagLink(
    val leader: String,                 // Leading market/symbol
    val lagger: String,                 // Lagging market/symbol
    val correlation: Double,            // Strength of relationship (0.0 to 1.0)
    val expectedDelaySec: Int,          // Expected lag in seconds
    val rotationProbability: Double,    // Probability of rotation occurring (0.0 to 1.0)
    val direction: String               // "SAME" or "INVERSE"
)

data class MarketCap(
    val market: String,
    val confidenceCap: Double,          // Max confidence any signal can have (0.0 to 1.0)
    val sizeCap: Double,                // Max position size multiplier (0.0 to 1.0)
    val leverageCap: Double,            // Max leverage for this market (0 = no leverage)
    val capitalBias: Double             // Capital allocation weight (-1.0 to 1.0)
)

// ═══════════════════════════════════════════════════════════════════════════
// TRADE LESSON — Full causal chain for supervised learning
// ═══════════════════════════════════════════════════════════════════════════

data class TradeLesson(
    val id: String,
    val strategy: String,               // "ShitCoinAI", "DipHunter", "BlueChipAI", etc.
    val market: String,                 // "MEME", "STOCKS", "PERPS"
    val symbol: String,
    // Context at entry
    val entryRegime: GlobalRiskMode,
    val entrySession: SessionContext,
    val trustScore: Double,
    val fragilityScore: Double,
    val narrativeHeat: Double,
    val portfolioHeat: Double,
    val leverageUsed: Double,
    val executionConfidence: Double,
    val leadSource: String?,            // Which asset/market was leading
    val expectedDelaySec: Int?,         // Expected lag from lead-lag AI
    // Outcome
    val outcomePct: Double,             // Net P&L %
    val mfePct: Double,                 // Maximum Favorable Excursion %
    val maePct: Double,                 // Maximum Adverse Excursion %
    val holdSec: Int,                   // Hold duration in seconds
    val exitReason: String,             // "TAKE_PROFIT", "STOP_LOSS", "TIMEOUT", "VETO"
    // Execution quality
    val expectedFillPrice: Double,
    val actualFillPrice: Double,
    val slippagePct: Double,
    val executionRoute: String,         // "JUPITER_V6", "DIRECT_DEX", etc.
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// STRATEGY TRUST RECORD — Per-strategy performance tracking
// ═══════════════════════════════════════════════════════════════════════════

data class StrategyTrustRecord(
    val strategyName: String,
    val recentWinRate: Double,          // Last N trades win rate
    val expectancy: Double,             // Average R per trade
    val drawdownSlope: Double,          // Rate of drawdown increase (negative = recovering)
    val avgMAE: Double,                 // Average max adverse excursion
    val avgMFE: Double,                 // Average max favorable excursion
    val falsePositiveRate: Double,      // Signals that immediately went against
    val regimeFit: Double,              // How well strategy fits current regime (0-1)
    val executionQuality: Double,       // Average execution quality (0-1)
    val slippageDamage: Double,         // Cumulative slippage cost
    val holdTimeEfficiency: Double,     // How well hold time aligns with profitable exits
    val timeOfDayPerformance: Map<String, Double>, // Session → win rate
    val trustLevel: TrustLevel,
    val trustScore: Double,             // 0.0 - 1.0 composite score
    val lastUpdated: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// LIQUIDITY FRAGILITY REPORT
// ═══════════════════════════════════════════════════════════════════════════

data class FragilityReport(
    val market: String,
    val symbol: String?,
    val fragilityScore: Double,         // 0.0 (stable) - 1.0 (critical)
    val fragilityLevel: FragilityLevel,
    val slippageRisk: Double,           // Expected slippage % for standard size
    val liquidationCascadeRisk: Double, // Probability of cascade (0-1)
    val maxSafeSize: Double,            // Max safe position size in SOL
    val safeHoldMinutes: Int,           // Max recommended hold time
    val spreadBps: Double,              // Current spread in basis points
    val depthScore: Double,             // Order book depth score (0-1)
    val wickFrequency: Double,          // Recent wick frequency (0-1)
    val failedBreakoutRate: Double,     // Recent false breakout rate (0-1)
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// LEVERAGE SURVIVAL VERDICT
// ═══════════════════════════════════════════════════════════════════════════

data class LeverageVerdict(
    val allowedLeverage: Double,        // 0 = no leverage, 1 = spot only, 3 = 3x max
    val liquidationDistanceSafety: Double, // How far from liquidation cluster (0-1, 1=safe)
    val maxHoldMinutes: Int,            // Max recommended hold for leveraged position
    val forcedTightRisk: Boolean,       // Force tight stops?
    val noLeverageOverride: Boolean,    // Hard veto — no other module can override
    val reasons: List<String>,          // Why this verdict was reached
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// EXECUTION PATH RECOMMENDATION
// ═══════════════════════════════════════════════════════════════════════════

data class ExecutionRecommendation(
    val preferredVenue: String,         // "JUPITER_V6", "RAYDIUM", "ORCA"
    val executionStyle: String,         // "MARKET", "PATIENT", "SPLIT", "TWAP"
    val splitOrders: Int,               // How many orders to split into (1 = single)
    val cancelRetryPolicy: String,      // "AGGRESSIVE_RETRY", "PATIENCE", "ABORT"
    val executionConfidence: Double,    // 0.0 - 1.0
    val estimatedSlippageBps: Double,   // Expected slippage in basis points
    val routeQuality: Double,           // Jupiter route quality score (0-1)
    val congestionLevel: Double,        // Chain congestion (0-1, 1=congested)
    val reasons: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// NARRATIVE FLOW REPORT
// ═══════════════════════════════════════════════════════════════════════════

data class NarrativeReport(
    val theme: String,                  // "AI_TOKENS", "MEME_DOGS", "RWA", "DEPIN"
    val narrativeHeat: Double,          // 0.0 (cold) - 1.0 (max heat)
    val narrativePersistence: Double,   // How long heat has sustained (0-1)
    val themeBreadth: Double,           // How many symbols participating (0-1)
    val themeExhaustion: Double,        // Signs of topping/exhaustion (0-1)
    val copyTradeRisk: Double,          // Risk of crowded trade (0-1)
    val phase: NarrativePhase,
    val relatedSymbols: List<String>,   // Symbols in this narrative
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// PORTFOLIO HEAT REPORT
// ═══════════════════════════════════════════════════════════════════════════

data class PortfolioHeatReport(
    val portfolioHeat: Double,          // 0.0 (cool) - 1.0 (max correlated exposure)
    val sectorCrowding: Map<String, Double>,  // Sector → crowding level
    val correlationStress: Double,      // How correlated active positions are (0-1)
    val newEntryPenalty: Double,        // Penalty to apply to new entries (0-1)
    val forcedDeRisk: Boolean,          // Should we force de-risk?
    val leverageConcentration: Double,  // How much leverage is clustered (0-1)
    val largestCluster: String,         // Biggest correlated cluster description
    val clusterSize: Int,               // Number of positions in largest cluster
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════════════
// NEW SCORING FORMULA — Multiplicative gating
// ═══════════════════════════════════════════════════════════════════════════

data class GatedScore(
    val baseOpportunityScore: Double,
    val strategyTrustMultiplier: Double,
    val regimeFitMultiplier: Double,
    val executionQualityMultiplier: Double,
    val narrativePersistenceMultiplier: Double,
    val leadLagMultiplier: Double,
    val portfolioSafetyMultiplier: Double,
    val liquiditySafetyMultiplier: Double,
    val vetoes: List<String>                // Hard vetoes that killed the trade
) {
    val finalScore: Double get() {
        if (vetoes.isNotEmpty()) return 0.0
        return baseOpportunityScore *
            strategyTrustMultiplier *
            regimeFitMultiplier *
            executionQualityMultiplier *
            narrativePersistenceMultiplier *
            leadLagMultiplier *
            portfolioSafetyMultiplier *
            liquiditySafetyMultiplier
    }

    val isVetoed: Boolean get() = vetoes.isNotEmpty()
}
