package com.lifecyclebot.v3.bridge

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.core.*
import com.lifecyclebot.v3.decision.ConfidenceEngine
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.decision.OpsMetrics
import com.lifecyclebot.v3.eligibility.CooldownManager
import com.lifecyclebot.v3.eligibility.EligibilityGate
import com.lifecyclebot.v3.eligibility.ExposureGuard
import com.lifecyclebot.v3.learning.LearningMetrics
import com.lifecyclebot.v3.learning.LearningStore
import com.lifecyclebot.v3.risk.FatalRiskChecker
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import com.lifecyclebot.v3.scoring.UnifiedScorer
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.SmartSizerV3
import com.lifecyclebot.v3.sizing.WalletSnapshot

/**
 * V3 Adapter
 * 
 * Bridges the existing AATE engine with the V3 scoring-based architecture.
 * Converts TokenState to CandidateSnapshot and coordinates the V3 pipeline.
 */
object V3Adapter {
    
    // Configuration
    private var config = TradingConfigV3()
    
    // Shared components
    private val cooldownManager = CooldownManager()
    private val exposureGuard = ExposureGuard()
    private val learningStore = LearningStore()
    private val shadowTracker = ShadowTracker()
    
    // V3 Pipeline components
    private val eligibilityGate = EligibilityGate(config, cooldownManager, exposureGuard)
    private val unifiedScorer = UnifiedScorer()
    private val fatalRiskChecker = FatalRiskChecker(config)
    private val confidenceEngine = ConfidenceEngine()
    private val finalDecisionEngine = FinalDecisionEngine(config)
    private val smartSizer = SmartSizerV3(config)
    
    // Orchestrator (lazy-initialized with context)
    private var orchestrator: BotOrchestrator? = null
    
    /**
     * Initialize or update V3 configuration
     */
    fun configure(newConfig: TradingConfigV3) {
        config = newConfig
    }
    
    /**
     * Get or create the orchestrator with current context
     */
    fun getOrchestrator(isPaperMode: Boolean, marketRegime: String = "NEUTRAL"): BotOrchestrator {
        val mode = if (isPaperMode) V3BotMode.PAPER else V3BotMode.LIVE
        val ctx = TradingContext(
            config = config,
            mode = mode,
            marketRegime = marketRegime
        )
        
        // Recreate if context changed
        if (orchestrator == null) {
            orchestrator = BotOrchestrator(
                ctx = ctx,
                eligibilityGate = EligibilityGate(config, cooldownManager, exposureGuard),
                fatalRiskChecker = FatalRiskChecker(config),
                finalDecisionEngine = FinalDecisionEngine(config),
                smartSizer = SmartSizerV3(config)
            )
        }
        
        return orchestrator!!
    }
    
    /**
     * Convert TokenState to V3 CandidateSnapshot
     */
    fun toCandidate(ts: TokenState): CandidateSnapshot {
        // Determine source type from TokenState
        val source = parseSourceType(ts.source)
        
        // Build extra signals map from TokenState metadata
        val extra = buildExtraMap(ts)
        
        // Calculate age from addedToWatchlistAt
        val ageMinutes = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
        
        // Get safety data
        val safety = ts.safety
        val topHolderPct = safety.topHolderPct.takeIf { it > 0 }
        
        return CandidateSnapshot(
            mint = ts.mint,
            symbol = ts.symbol,
            source = source,
            discoveredAtMs = ts.addedToWatchlistAt,
            ageMinutes = ageMinutes,
            liquidityUsd = ts.lastLiquidityUsd,
            marketCapUsd = ts.lastMcap,
            buyPressurePct = ts.meta.pressScore,
            volume1mUsd = 0.0, // Not directly tracked in TokenState
            volume5mUsd = 0.0, // Not directly tracked in TokenState
            holders = ts.peakHolderCount.takeIf { it > 0 },
            topHolderPct = topHolderPct,
            bundledPct = safety.firstBlockSupplyPct.takeIf { it > 0 },
            hasIdentitySignals = ts.name.isNotBlank() && ts.name != ts.symbol,
            isSellable = !safety.isBlocked,
            rawRiskScore = safety.entryScorePenalty,
            extra = extra
        )
    }
    
    /**
     * Build wallet snapshot from current balance
     */
    fun toWallet(totalSol: Double, reserveSol: Double = 0.05): WalletSnapshot {
        return WalletSnapshot(
            totalSol = totalSol,
            tradeableSol = (totalSol - reserveSol).coerceAtLeast(0.0)
        )
    }
    
    /**
     * Build learning metrics from BotBrain or defaults
     */
    fun toLearningMetrics(
        classifiedTrades: Int = 0,
        winRate: Double = 50.0,
        payoffRatio: Double = 1.0
    ): LearningMetrics {
        return LearningMetrics(
            classifiedTrades = classifiedTrades,
            last20WinRatePct = winRate,
            payoffRatio = payoffRatio
        )
    }
    
    /**
     * Build ops metrics from system health
     */
    fun toOpsMetrics(
        apiHealthy: Boolean = true,
        feedsHealthy: Boolean = true,
        walletHealthy: Boolean = true,
        latencyMs: Long = 100
    ): OpsMetrics {
        return OpsMetrics(
            apiHealthy = apiHealthy,
            feedsHealthy = feedsHealthy,
            walletHealthy = walletHealthy,
            latencyMs = latencyMs
        )
    }
    
    /**
     * Build portfolio risk state from session stats
     */
    fun toRiskState(recentDrawdownPct: Double = 0.0): PortfolioRiskState {
        return PortfolioRiskState(recentDrawdownPct = recentDrawdownPct)
    }
    
    /**
     * Process a token through the V3 pipeline
     */
    fun processV3(
        ts: TokenState,
        walletSol: Double,
        isPaperMode: Boolean,
        marketRegime: String = "NEUTRAL",
        classifiedTrades: Int = 0,
        winRate: Double = 50.0,
        drawdownPct: Double = 0.0,
        apiHealthy: Boolean = true
    ): ProcessResult {
        val orch = getOrchestrator(isPaperMode, marketRegime)
        
        return orch.processCandidate(
            candidate = toCandidate(ts),
            wallet = toWallet(walletSol),
            risk = toRiskState(drawdownPct),
            learningMetrics = toLearningMetrics(classifiedTrades, winRate),
            opsMetrics = toOpsMetrics(apiHealthy = apiHealthy)
        )
    }
    
    /**
     * Mark position opened (for exposure tracking)
     */
    fun onPositionOpened(mint: String) {
        exposureGuard.openPosition(mint)
    }
    
    /**
     * Mark position closed (for exposure tracking)
     */
    fun onPositionClosed(mint: String) {
        exposureGuard.closePosition(mint)
    }
    
    /**
     * Set cooldown for a mint
     */
    fun setCooldown(mint: String, durationMs: Long) {
        cooldownManager.setCooldown(mint, System.currentTimeMillis() + durationMs)
    }
    
    /**
     * Update exposure percentage
     */
    fun updateExposure(exposurePct: Double) {
        exposureGuard.currentExposurePct = exposurePct
    }
    
    /**
     * Get shadow tracker for learning
     */
    fun getShadowTracker(): ShadowTracker = shadowTracker
    
    /**
     * Get learning store for metrics
     */
    fun getLearningStore(): LearningStore = learningStore
    
    // ════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════════════
    
    private fun parseSourceType(source: String): SourceType {
        return when {
            source.contains("BOOSTED", ignoreCase = true) -> SourceType.DEX_BOOSTED
            source.contains("RAYDIUM", ignoreCase = true) -> SourceType.RAYDIUM_NEW_POOL
            source.contains("PUMP", ignoreCase = true) -> SourceType.PUMP_FUN_GRADUATE
            source.contains("TRENDING", ignoreCase = true) -> SourceType.DEX_TRENDING
            source.contains("GRAD", ignoreCase = true) -> SourceType.PUMP_FUN_GRADUATE
            else -> SourceType.DEX_TRENDING
        }
    }
    
    private fun buildExtraMap(ts: TokenState): Map<String, Any?> {
        val meta = ts.meta
        val safety = ts.safety
        val extras = mutableMapOf<String, Any?>()
        
        // Technical signals (approximate from available data)
        extras["rsiOversold"] = meta.rsi < 30
        extras["momentumUp"] = meta.momScore > 55
        extras["momentumWeak"] = meta.momScore < 35
        extras["higherLows"] = !meta.lowerHighs
        extras["pumpBuilding"] = meta.pressScore > 65 && meta.momScore > 60
        
        // Liquidity signals (inferred from breakdown)
        extras["liquidityDraining"] = meta.breakdown
        extras["volumeExpanding"] = meta.volScore > 60
        
        // Volume signals
        extras["accumulationAtVal"] = meta.curveStage.contains("ACCUM", ignoreCase = true)
        extras["sellCluster"] = meta.pressScore < 30
        
        // Phase and lifecycle
        extras["phase"] = ts.phase
        extras["price"] = ts.lastPrice
        
        // Memory/pattern signals (from TradingMemory if available)
        extras["memoryScore"] = 0 // TODO: Integrate with TradingMemory
        
        // Copy-trade signals
        extras["copyTradeStale"] = false
        extras["copyTradeCrowded"] = false
        
        // Risk signals
        extras["zeroHolders"] = ts.peakHolderCount <= 0
        extras["pureSellPressure"] = meta.pressScore < 20
        extras["unsellableSignal"] = safety.isBlocked
        
        // Name/identity signals
        extras["suspiciousName"] = ts.symbol.length <= 1 || 
            ts.symbol.all { it.isDigit() }
        
        return extras
    }
}
