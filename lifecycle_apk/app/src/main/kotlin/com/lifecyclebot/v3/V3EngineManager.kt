package com.lifecyclebot.v3

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.v3.bridge.V3Adapter
import com.lifecyclebot.v3.core.*
import com.lifecyclebot.v3.decision.ConfidenceEngine
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.decision.OpsMetrics
import com.lifecyclebot.v3.eligibility.CooldownManager
import com.lifecyclebot.v3.eligibility.EligibilityGate
import com.lifecyclebot.v3.eligibility.ExposureGuard
import com.lifecyclebot.v3.learning.LearningStore
import com.lifecyclebot.v3.risk.FatalRiskChecker
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scoring.UnifiedScorer
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.SmartSizerV3
import com.lifecyclebot.v3.sizing.WalletSnapshot

/**
 * V3 Engine Manager
 * 
 * Central manager for the V3 scoring-based trading engine.
 * Handles initialization, configuration, and provides the main entry point
 * for processing trade candidates.
 * 
 * The V3 engine replaces hard-gating decisions with a score-modifier system
 * where all signals contribute to a final decision score.
 */
object V3EngineManager {
    
    private const val TAG = "V3Engine"
    
    // Engine state
    @Volatile private var initialized = false
    @Volatile private var config: TradingConfigV3? = null
    @Volatile private var context: TradingContext? = null
    
    // Core components (lazily initialized)
    private var cooldownManager: CooldownManager? = null
    private var exposureGuard: ExposureGuard? = null
    private var learningStore: LearningStore? = null
    private var shadowTracker: ShadowTracker? = null
    private var orchestrator: BotOrchestrator? = null
    
    // Jupiter API for real execution
    private var jupiterApi: JupiterApi? = null
    
    // Callback for trade execution
    private var onExecuteCallback: ((ExecuteRequest) -> ExecuteResult)? = null
    
    // Callback for logging
    private var onLogCallback: ((String, String) -> Unit)? = null
    
    /**
     * Initialize the V3 engine with configuration
     */
    fun initialize(
        botConfig: BotConfig,
        jupiterApiKey: String = "",
        onExecute: ((ExecuteRequest) -> ExecuteResult)? = null,
        onLog: ((String, String) -> Unit)? = null
    ) {
        if (!botConfig.v3EngineEnabled) {
            ErrorLogger.info(TAG, "V3 Engine disabled in config")
            return
        }
        
        ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "   INITIALIZING V3 SCORING ENGINE")
        ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
        
        try {
            // Build V3 config from BotConfig
            config = TradingConfigV3(
                minScore = botConfig.v3MinScoreToTrade,
                maxExposurePct = botConfig.v3MaxExposurePct,
                maxPositions = botConfig.maxConcurrentPositions.coerceAtMost(10),
                minLiquidityUsd = 500.0,
                maxSlippagePct = botConfig.slippageBps / 100.0,
                cooldownMs = (botConfig.entryCooldownSec * 1000).toLong(),
                paperMode = botConfig.paperMode,
                conservativeMode = botConfig.v3ConservativeMode
            )
            
            // Determine mode
            val mode = when {
                botConfig.paperMode -> V3BotMode.PAPER
                botConfig.v3ShadowMode -> V3BotMode.LEARNING
                else -> V3BotMode.LIVE
            }
            
            // Create trading context
            context = TradingContext(
                config = config!!,
                mode = mode,
                marketRegime = "NEUTRAL"  // Will be updated dynamically
            )
            
            // Initialize components
            cooldownManager = CooldownManager()
            exposureGuard = ExposureGuard()
            learningStore = LearningStore()
            shadowTracker = ShadowTracker()
            
            // Initialize orchestrator
            orchestrator = BotOrchestrator(
                ctx = context!!,
                eligibilityGate = EligibilityGate(config!!, cooldownManager!!, exposureGuard!!),
                fatalRiskChecker = FatalRiskChecker(config!!),
                finalDecisionEngine = FinalDecisionEngine(config!!),
                smartSizer = SmartSizerV3(config!!)
            )
            
            // Set up Jupiter API for real execution
            if (jupiterApiKey.isNotBlank()) {
                jupiterApi = JupiterApi(jupiterApiKey)
            }
            
            // Set callbacks
            onExecuteCallback = onExecute
            onLogCallback = onLog
            
            initialized = true
            
            val modeTag = when (mode) {
                V3BotMode.PAPER -> "📝 PAPER"
                V3BotMode.LEARNING -> "🔬 SHADOW/LEARNING"
                V3BotMode.LIVE -> "🔥 LIVE"
            }
            
            ErrorLogger.info(TAG, "V3 Engine initialized: $modeTag")
            ErrorLogger.info(TAG, "  - Min score: ${config!!.minScore}")
            ErrorLogger.info(TAG, "  - Max exposure: ${config!!.maxExposurePct}%")
            ErrorLogger.info(TAG, "  - Max positions: ${config!!.maxPositions}")
            ErrorLogger.info(TAG, "  - Conservative: ${config!!.conservativeMode}")
            ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to initialize V3 engine: ${e.message}", e)
            initialized = false
        }
    }
    
    /**
     * Check if V3 engine is ready
     */
    fun isReady(): Boolean = initialized && orchestrator != null
    
    /**
     * Get current V3 mode
     */
    fun getMode(): V3BotMode = context?.mode ?: V3BotMode.PAPER
    
    /**
     * Process a token through the V3 pipeline
     * Returns both the decision and whether to execute
     */
    fun processToken(
        ts: TokenState,
        walletSol: Double,
        totalExposureSol: Double,
        openPositions: Int,
        recentWinRate: Double,
        recentTradeCount: Int,
        marketRegime: String = "NEUTRAL"
    ): V3Decision {
        if (!isReady()) {
            return V3Decision.notReady("V3 Engine not initialized")
        }
        
        try {
            // Update context with current market regime
            context = context!!.copy(marketRegime = marketRegime)
            
            // Update exposure guard
            exposureGuard?.currentExposurePct = (totalExposureSol / walletSol * 100).coerceIn(0.0, 100.0)
            
            // Convert TokenState to V3 CandidateSnapshot
            val candidate = V3Adapter.toCandidate(ts)
            
            // Build wallet snapshot
            val wallet = WalletSnapshot(
                totalSol = walletSol,
                tradeableSol = (walletSol - 0.05).coerceAtLeast(0.0)  // Reserve 0.05 SOL
            )
            
            // Build portfolio risk state
            val risk = PortfolioRiskState(
                recentDrawdownPct = 0.0  // TODO: Calculate from session stats
            )
            
            // Build learning metrics
            val learningMetrics = com.lifecyclebot.v3.learning.LearningMetrics(
                classifiedTrades = recentTradeCount,
                last20WinRatePct = recentWinRate,
                payoffRatio = 1.0  // TODO: Calculate from actual data
            )
            
            // Build ops metrics
            val opsMetrics = OpsMetrics(
                apiHealthy = true,
                feedsHealthy = true,
                walletHealthy = true,
                latencyMs = 100
            )
            
            // Run through orchestrator
            val result = orchestrator!!.processCandidate(
                candidate = candidate,
                wallet = wallet,
                risk = risk,
                learningMetrics = learningMetrics,
                opsMetrics = opsMetrics
            )
            
            // Convert to V3Decision
            return when (result) {
                is ProcessResult.Executed -> {
                    val log = "V3 EXECUTE: ${ts.symbol} | band=${result.band} | size=${result.sizeSol.fmt(4)} SOL | conf=${result.confidence.toInt()}%"
                    onLogCallback?.invoke(log, ts.mint)
                    ErrorLogger.info(TAG, log)
                    
                    // Track in shadow if in learning mode
                    if (context?.mode == V3BotMode.LEARNING) {
                        shadowTracker?.track(candidate, result.sizeSol, result.band.name)
                    }
                    
                    V3Decision.execute(
                        sizeSol = result.sizeSol,
                        band = result.band.name,
                        confidence = result.confidence,
                        thesis = result.thesis
                    )
                }
                
                is ProcessResult.Watch -> {
                    val log = "V3 WATCH: ${ts.symbol} | band=${result.band} | reason=${result.reason}"
                    onLogCallback?.invoke(log, ts.mint)
                    
                    V3Decision.watch(
                        band = result.band.name,
                        reason = result.reason
                    )
                }
                
                is ProcessResult.Rejected -> {
                    val log = "V3 REJECT: ${ts.symbol} | ${result.reason}"
                    onLogCallback?.invoke(log, ts.mint)
                    
                    V3Decision.rejected(result.reason)
                }
                
                is ProcessResult.Blocked -> {
                    val log = "V3 BLOCK: ${ts.symbol} | ${result.reason}"
                    onLogCallback?.invoke(log, ts.mint)
                    
                    V3Decision.blocked(result.reason)
                }
            }
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "V3 processing error for ${ts.symbol}: ${e.message}", e)
            return V3Decision.error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Execute a V3 trade using the real executor
     */
    fun executeV3Trade(
        ts: TokenState,
        sizeSol: Double,
        wallet: SolanaWallet,
        jupiterApiKey: String
    ): V3ExecutionResult {
        if (!isReady()) {
            return V3ExecutionResult.failed("V3 Engine not initialized")
        }
        
        // Check if we're in shadow/paper mode
        if (context?.mode == V3BotMode.PAPER) {
            ErrorLogger.info(TAG, "V3 PAPER TRADE: ${ts.symbol} | ${sizeSol.fmt(4)} SOL")
            return V3ExecutionResult.paper(sizeSol)
        }
        
        if (context?.mode == V3BotMode.LEARNING) {
            ErrorLogger.info(TAG, "V3 SHADOW TRADE: ${ts.symbol} | ${sizeSol.fmt(4)} SOL (logged only)")
            return V3ExecutionResult.shadow(sizeSol)
        }
        
        // LIVE execution
        try {
            // Use callback if provided (delegates to main Executor)
            if (onExecuteCallback != null) {
                val request = ExecuteRequest(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    sizeSol = sizeSol,
                    isBuy = true
                )
                val result = onExecuteCallback!!.invoke(request)
                
                if (result.success) {
                    // Record position opened
                    exposureGuard?.openPosition(ts.mint)
                    
                    return V3ExecutionResult.success(
                        txSignature = result.txSignature,
                        executedSol = result.executedSol,
                        executedPrice = result.executedPrice
                    )
                } else {
                    return V3ExecutionResult.failed(result.error ?: "Execution failed")
                }
            }
            
            // Direct Jupiter execution (fallback)
            val jupiter = jupiterApi ?: JupiterApi(jupiterApiKey)
            
            // Get quote
            val lamports = (sizeSol * 1_000_000_000).toLong()
            val quote = jupiter.getQuoteUltra(
                inputMint = "So11111111111111111111111111111111111111112",  // SOL
                outputMint = ts.mint,
                amount = lamports,
                slippageBps = config?.maxSlippagePct?.times(100)?.toInt() ?: 200
            )
            
            if (quote == null) {
                return V3ExecutionResult.failed("No quote available")
            }
            
            // Execute swap
            val swapResult = jupiter.swapUltra(quote, wallet.getPublicKeyBase58())
            
            if (swapResult == null) {
                return V3ExecutionResult.failed("Swap failed - no result")
            }
            
            // Sign and execute
            val signature = wallet.signAndExecuteUltra(
                swapResult.first,  // txBase64
                swapResult.second, // requestId
                jupiterApiKey
            )
            
            // Record position
            exposureGuard?.openPosition(ts.mint)
            
            return V3ExecutionResult.success(
                txSignature = signature,
                executedSol = sizeSol,
                executedPrice = null  // Will be determined from tx
            )
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "V3 execution error: ${e.message}", e)
            return V3ExecutionResult.failed(e.message ?: "Execution error")
        }
    }
    
    /**
     * Record trade outcome for learning
     */
    fun recordOutcome(
        mint: String,
        symbol: String,
        pnlPct: Double,
        holdTimeMinutes: Double,
        exitReason: String
    ) {
        if (!isReady()) return
        
        try {
            val event = com.lifecyclebot.v3.learning.LearningEvent(
                mint = mint,
                symbol = symbol,
                pnlPct = pnlPct,
                holdTimeMinutes = holdTimeMinutes,
                exitReason = exitReason,
                timestamp = System.currentTimeMillis()
            )
            
            learningStore?.recordOutcome(event)
            
            // Update shadow tracker if tracking this token
            shadowTracker?.recordOutcome(mint, pnlPct > 0)
            
            ErrorLogger.info(TAG, "V3 OUTCOME: $symbol | PnL=${pnlPct.fmt(1)}% | hold=${holdTimeMinutes.toInt()}min | $exitReason")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to record outcome: ${e.message}")
        }
    }
    
    /**
     * Mark position closed
     */
    fun onPositionClosed(mint: String) {
        exposureGuard?.closePosition(mint)
    }
    
    /**
     * Set cooldown for a mint
     */
    fun setCooldown(mint: String, durationMs: Long) {
        cooldownManager?.setCooldown(mint, System.currentTimeMillis() + durationMs)
    }
    
    /**
     * Get V3 engine status summary
     */
    fun getStatusSummary(): String {
        if (!isReady()) return "V3: NOT INITIALIZED"
        
        val mode = when (context?.mode) {
            V3BotMode.PAPER -> "PAPER"
            V3BotMode.LEARNING -> "SHADOW"
            V3BotMode.LIVE -> "LIVE"
            null -> "UNKNOWN"
        }
        
        val exposure = exposureGuard?.currentExposurePct?.toInt() ?: 0
        val openPos = exposureGuard?.getOpenPositionCount() ?: 0
        val shadowCount = shadowTracker?.getTrackedCount() ?: 0
        
        return "V3: $mode | exp=${exposure}% | pos=$openPos | shadow=$shadowCount"
    }
    
    /**
     * Shutdown the V3 engine
     */
    fun shutdown() {
        ErrorLogger.info(TAG, "Shutting down V3 engine")
        initialized = false
        orchestrator = null
        config = null
        context = null
    }
    
    // Helper extension
    private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
}

/**
 * V3 Decision result
 */
sealed class V3Decision {
    data class Execute(
        val sizeSol: Double,
        val band: String,
        val confidence: Double,
        val thesis: String
    ) : V3Decision()
    
    data class Watch(
        val band: String,
        val reason: String
    ) : V3Decision()
    
    data class Rejected(val reason: String) : V3Decision()
    data class Blocked(val reason: String) : V3Decision()
    data class Error(val message: String) : V3Decision()
    data class NotReady(val reason: String) : V3Decision()
    
    fun shouldExecute(): Boolean = this is Execute
    fun isActionable(): Boolean = this is Execute || this is Watch
    
    companion object {
        fun execute(sizeSol: Double, band: String, confidence: Double, thesis: String) = 
            Execute(sizeSol, band, confidence, thesis)
        fun watch(band: String, reason: String) = Watch(band, reason)
        fun rejected(reason: String) = Rejected(reason)
        fun blocked(reason: String) = Blocked(reason)
        fun error(message: String) = Error(message)
        fun notReady(reason: String) = NotReady(reason)
    }
}

/**
 * V3 Execution Result
 */
sealed class V3ExecutionResult {
    data class Success(
        val txSignature: String?,
        val executedSol: Double,
        val executedPrice: Double?
    ) : V3ExecutionResult()
    
    data class Paper(val sizeSol: Double) : V3ExecutionResult()
    data class Shadow(val sizeSol: Double) : V3ExecutionResult()
    data class Failed(val error: String) : V3ExecutionResult()
    
    fun isSuccess(): Boolean = this is Success || this is Paper || this is Shadow
    
    companion object {
        fun success(txSignature: String?, executedSol: Double, executedPrice: Double?) =
            Success(txSignature, executedSol, executedPrice)
        fun paper(sizeSol: Double) = Paper(sizeSol)
        fun shadow(sizeSol: Double) = Shadow(sizeSol)
        fun failed(error: String) = Failed(error)
    }
}

/**
 * Execute request for callback
 */
data class ExecuteRequest(
    val mint: String,
    val symbol: String,
    val sizeSol: Double,
    val isBuy: Boolean
)

/**
 * Execute result from callback
 */
data class ExecuteResult(
    val success: Boolean,
    val txSignature: String? = null,
    val executedSol: Double = 0.0,
    val executedPrice: Double? = null,
    val error: String? = null
)
