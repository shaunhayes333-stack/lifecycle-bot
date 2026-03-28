package com.lifecyclebot.v3

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.v3.bridge.V3Adapter
import com.lifecyclebot.v3.core.*
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.decision.OpsMetrics
import com.lifecyclebot.v3.eligibility.CooldownManager
import com.lifecyclebot.v3.eligibility.EligibilityGate
import com.lifecyclebot.v3.eligibility.ExposureGuard
import com.lifecyclebot.v3.learning.LearningStore
import com.lifecyclebot.v3.learning.LearningEvent
import com.lifecyclebot.v3.risk.FatalRiskChecker
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.SmartSizerV3
import com.lifecyclebot.v3.sizing.WalletSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * V3 Engine Manager
 * 
 * Central manager for the V3 scoring-based trading engine.
 * Handles initialization, configuration, and provides the main entry point
 * for processing trade candidates.
 */
object V3EngineManager {
    
    private const val TAG = "V3Engine"
    
    // Engine state
    @Volatile private var initialized = false
    @Volatile private var config: TradingConfigV3? = null
    @Volatile private var context: TradingContext? = null
    @Volatile private var botConfig: BotConfig? = null
    
    // Core components
    private var cooldownManager: CooldownManager? = null
    private var exposureGuard: ExposureGuard? = null
    private var learningStore: LearningStore? = null
    private var shadowTracker: ShadowTracker? = null
    private var orchestrator: BotOrchestrator? = null
    
    // V3 entry tracking for outcome learning
    private val v3Entries = java.util.concurrent.ConcurrentHashMap<String, V3EntryRecord>()
    
    // Callbacks
    private var onExecuteCallback: ((ExecuteRequest) -> ExecuteResult)? = null
    private var onLogCallback: ((String, String) -> Unit)? = null

    /**
     * Initialize the V3 engine with configuration
     */
    fun initialize(
        botCfg: BotConfig,
        onExecute: ((ExecuteRequest) -> ExecuteResult)? = null,
        onLog: ((String, String) -> Unit)? = null
    ) {
        if (!botCfg.v3EngineEnabled) {
            ErrorLogger.info(TAG, "V3 Engine disabled in config")
            return
        }
        
        ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "   INITIALIZING V3 SCORING ENGINE")
        ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
        
        try {
            botConfig = botCfg
            
            // Build V3 config using actual TradingConfigV3 fields
            config = TradingConfigV3(
                minLiquidityUsd = 500.0,
                maxTokenAgeMinutes = 30.0,
                watchScoreMin = 5,   // V3.2: Lowered from 20 to let more through
                executeSmallMin = 15, // V3.2: Lowered from 35 (scores are typically 5-20)
                executeStandardMin = botCfg.v3MinScoreToTrade.coerceIn(20, 60), // V3.2: Lowered floor from 35 to 20
                executeAggressiveMin = 45, // V3.2: Lowered from 65
                fatalRugThreshold = 90,
                candidateTtlMinutes = 20,
                shadowTrackNearMissMin = 5, // V3.2: Lowered from 15
                reserveSol = 0.05,
                maxSmallSizePct = 0.04,
                maxStandardSizePct = 0.07,
                maxAggressiveSizePct = if (botCfg.v3ConservativeMode) 0.08 else 0.12,
                paperLearningSizeMult = 0.50
            )
            
            // Determine mode
            val mode = when {
                botCfg.paperMode -> V3BotMode.PAPER
                botCfg.v3ShadowMode -> V3BotMode.LEARNING
                else -> V3BotMode.LIVE
            }
            
            // Create trading context
            context = TradingContext(
                config = config!!,
                mode = mode,
                marketRegime = "NEUTRAL"
            )
            
            // Initialize components with proper parameters
            cooldownManager = CooldownManager()
            exposureGuard = ExposureGuard(
                maxOpenPositions = botCfg.maxConcurrentPositions.coerceAtMost(10),
                maxExposurePct = botCfg.v3MaxExposurePct / 100.0
            )
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
            
            // Set callbacks
            onExecuteCallback = onExecute
            onLogCallback = onLog
            
            // Wire TradeExecutor callback for Jupiter API integration
            com.lifecyclebot.v3.execution.TradeExecutor.executeCallback = { candidate, size, decision, _ ->
                // Delegate to legacy Executor for actual Jupiter swap
                val request = ExecuteRequest(
                    mint = candidate.mint,
                    symbol = candidate.symbol,
                    sizeSol = size.sizeSol,
                    isBuy = true  // V3 only handles buys currently
                )
                
                val result = onExecuteCallback?.invoke(request)
                
                com.lifecyclebot.v3.execution.TradeExecutionResult(
                    success = result?.success ?: false,
                    txSignature = result?.txSignature,
                    executedSize = result?.executedSol ?: 0.0,
                    executedPrice = result?.executedPrice,
                    error = result?.error
                )
            }
            
            initialized = true
            
            val modeTag = when (mode) {
                V3BotMode.PAPER -> "📝 PAPER"
                V3BotMode.LEARNING -> "🔬 SHADOW/LEARNING"
                V3BotMode.LIVE -> "🔥 LIVE"
            }
            
            ErrorLogger.info(TAG, "V3 Engine initialized: $modeTag")
            ErrorLogger.info(TAG, "  - Execute threshold: ${config!!.executeStandardMin}")
            ErrorLogger.info(TAG, "  - Max exposure: ${botCfg.v3MaxExposurePct}%")
            ErrorLogger.info(TAG, "  - Conservative: ${botCfg.v3ConservativeMode}")
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
     * 
     * V3 SELECTIVITY: Added isAIDegraded parameter for AI health tracking
     */
    fun processToken(
        ts: TokenState,
        walletSol: Double,
        totalExposureSol: Double,
        openPositions: Int,
        recentWinRate: Double,
        recentTradeCount: Int,
        marketRegime: String = "NEUTRAL",
        isAIDegraded: Boolean = false  // V3 SELECTIVITY: AI degradation flag
    ): V3Decision {
        if (!isReady()) {
            return V3Decision.notReady("V3 Engine not initialized")
        }
        
        try {
            // Update context with current market regime
            context = context!!.copy(marketRegime = marketRegime)
            
            // Update exposure guard
            exposureGuard?.currentExposurePct = (totalExposureSol / walletSol).coerceIn(0.0, 1.0)
            
            // Convert TokenState to V3 CandidateSnapshot
            val candidate = V3Adapter.toCandidate(ts)
            
            // Build wallet snapshot
            val wallet = WalletSnapshot(
                totalSol = walletSol,
                tradeableSol = (walletSol - 0.05).coerceAtLeast(0.0)
            )
            
            // Build portfolio risk state
            val risk = PortfolioRiskState(recentDrawdownPct = 0.0)
            
            // Build learning metrics
            val learningMetrics = com.lifecyclebot.v3.learning.LearningMetrics(
                classifiedTrades = recentTradeCount,
                last20WinRatePct = recentWinRate,
                payoffRatio = 1.0
            )
            
            // Build ops metrics
            // V3 SELECTIVITY: Pass AI degradation state
            val opsMetrics = OpsMetrics(
                apiHealthy = !isAIDegraded,  // AI degraded = not healthy
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
            
            // Convert ProcessResult to V3Decision
            return when (result) {
                is ProcessResult.Executed -> {
                    val log = "V3 EXECUTE: ${ts.symbol} | band=${result.band} | " +
                        "size=${result.sizeSol.fmt(4)} SOL | conf=${result.confidence}%"
                    onLogCallback?.invoke(log, ts.mint)
                    ErrorLogger.info(TAG, log)
                    
                    V3Decision.execute(
                        sizeSol = result.sizeSol,
                        band = result.band.name,
                        confidence = result.confidence.toDouble(),
                        score = result.score,
                        breakdown = result.breakdown
                    )
                }
                
                is ProcessResult.Watch -> {
                    val log = "V3 WATCH: ${ts.symbol} | score=${result.score} | conf=${result.confidence}"
                    onLogCallback?.invoke(log, ts.mint)
                    
                    V3Decision.watch(
                        score = result.score.toInt(),
                        confidence = result.confidence.toInt()
                    )
                }
                
                is ProcessResult.ShadowOnly -> {
                    val log = "V3 SHADOW_ONLY: ${ts.symbol} | score=${result.score} | ${result.reason}"
                    onLogCallback?.invoke(log, ts.mint)
                    
                    V3Decision.shadowOnly(
                        score = result.score.toInt(),
                        confidence = result.confidence.toInt(),
                        reason = result.reason
                    )
                }
                
                is ProcessResult.Rejected -> {
                    onLogCallback?.invoke("V3 REJECT: ${ts.symbol} | ${result.reason}", ts.mint)
                    V3Decision.rejected(result.reason)
                }
                
                is ProcessResult.BlockFatal -> {
                    onLogCallback?.invoke("V3 BLOCK_FATAL: ${ts.symbol} | ${result.reason}", ts.mint)
                    V3Decision.blockFatal(result.reason)
                }
                
                is ProcessResult.Blocked -> {
                    // Legacy path
                    onLogCallback?.invoke("V3 BLOCK: ${ts.symbol} | ${result.reason}", ts.mint)
                    V3Decision.blocked(result.reason)
                }
            }
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "V3 processing error for ${ts.symbol}: ${e.message}", e)
            return V3Decision.error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Execute a V3 trade
     */
    fun executeV3Trade(
        ts: TokenState,
        sizeSol: Double,
        wallet: SolanaWallet
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
        
        // LIVE execution - delegate to callback
        try {
            if (onExecuteCallback != null) {
                val request = ExecuteRequest(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    sizeSol = sizeSol,
                    isBuy = true
                )
                val result = onExecuteCallback!!.invoke(request)
                
                if (result.success) {
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
            
            return V3ExecutionResult.failed("No execution callback configured")
            
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
        holdTimeMinutes: Int,
        exitReason: String,
        entryPhase: String = "UNKNOWN",
        tradingMode: String = "STANDARD",
        discoverySource: String = "UNKNOWN",
        liquidityUsd: Double = 0.0,
        emaTrend: String = "NEUTRAL"
    ) {
        if (!isReady()) return
        
        try {
            // Create learning event with correct fields
            val event = LearningEvent(
                mint = mint,
                symbol = symbol,
                decisionBand = DecisionBand.EXECUTE_STANDARD, // Default
                finalScore = 50,
                confidence = 50,
                outcomeLabel = if (pnlPct > 0) "WIN" else "LOSS",
                pnlPct = pnlPct,
                holdingTimeSec = holdTimeMinutes * 60  // Convert minutes to seconds
            )
            
            learningStore?.record(event)
            
            ErrorLogger.info(TAG, "V3 OUTCOME: $symbol | PnL=${pnlPct.fmt(1)}% | " +
                "hold=${holdTimeMinutes.toInt()}min | $exitReason")
            
            // Upload to Collective Learning (hive mind)
            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val liquidityBucket = when {
                            liquidityUsd < 5_000 -> "MICRO"
                            liquidityUsd < 25_000 -> "SMALL"
                            liquidityUsd < 100_000 -> "MID"
                            else -> "LARGE"
                        }
                        
                        com.lifecyclebot.collective.CollectiveLearning.uploadPatternOutcome(
                            patternType = "${entryPhase}_${tradingMode}",
                            discoverySource = discoverySource,
                            liquidityBucket = liquidityBucket,
                            emaTrend = emaTrend,
                            isWin = pnlPct > 5.0,  // WIN if >5%
                            pnlPct = pnlPct,
                            holdMins = holdTimeMinutes.toDouble()
                        )
                        ErrorLogger.debug(TAG, "📤 V3 outcome uploaded to collective: $symbol")
                    } catch (e: Exception) {
                        ErrorLogger.debug(TAG, "V3 collective upload error: ${e.message}")
                    }
                }
            }
            
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
        
        val exposure = ((exposureGuard?.currentExposurePct ?: 0.0) * 100).toInt()
        val openPos = exposureGuard?.openCount() ?: 0
        val tracked = shadowTracker?.allTracked()?.size ?: 0
        
        return "V3: $mode | exp=${exposure}% | pos=$openPos | tracked=$tracked"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V3 vs LEGACY COMPARISON TRACKING
    // Track decisions to compare V3 accuracy against legacy FDG
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var v3Executes = 0
    private var v3Rejects = 0
    private var v3Watches = 0
    private var v3Blocks = 0
    private var fdgAgrees = 0
    private var fdgDisagrees = 0
    private var v3WinsFdgWouldBlock = 0  // V3 executed, FDG would block, trade was WIN
    private var v3LossesFdgWouldBlock = 0  // V3 executed, FDG would block, trade was LOSS
    
    /**
     * Record a V3 decision for comparison tracking
     */
    fun recordDecisionComparison(
        v3Decision: String,  // EXECUTE, WATCH, REJECT, BLOCK
        fdgWouldExecute: Boolean
    ) {
        when (v3Decision) {
            "EXECUTE" -> v3Executes++
            "WATCH" -> v3Watches++
            "REJECT" -> v3Rejects++
            "BLOCK" -> v3Blocks++
        }
        
        val v3WouldExecute = v3Decision == "EXECUTE"
        if (v3WouldExecute == fdgWouldExecute) {
            fdgAgrees++
        } else {
            fdgDisagrees++
        }
    }
    
    /**
     * V3 CLEAN RUNTIME: Record entry for learning
     * Called immediately after V3 executes a buy
     */
    fun recordEntry(
        mint: String,
        symbol: String,
        entryPrice: Double,
        sizeSol: Double,
        v3Score: Int,
        v3Band: String,
        v3Confidence: Double,
        source: String,
        liquidityUsd: Double,
        isPaper: Boolean
    ) {
        ErrorLogger.info(TAG, "[ENTRY] $symbol | price=${String.format("%.8f", entryPrice)} | " +
            "size=${String.format("%.4f", sizeSol)} SOL | score=$v3Score | band=$v3Band | " +
            "conf=${v3Confidence.toInt()}% | ${if (isPaper) "PAPER" else "LIVE"}")
        
        // Track entry for outcome learning
        v3Entries[mint] = V3EntryRecord(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            sizeSol = sizeSol,
            v3Score = v3Score,
            v3Band = v3Band,
            v3Confidence = v3Confidence,
            source = source,
            liquidityUsd = liquidityUsd,
            isPaper = isPaper,
            entryTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Record outcome of V3 decision for learning
     */
    fun recordV3OutcomeVsFdg(
        v3Executed: Boolean,
        fdgWouldExecute: Boolean,
        pnlPct: Double
    ) {
        // Track cases where V3 and FDG disagreed
        if (v3Executed && !fdgWouldExecute) {
            if (pnlPct > 5.0) {
                v3WinsFdgWouldBlock++
                ErrorLogger.info(TAG, "🎯 V3 WIN | FDG would block | PnL: +${pnlPct.toInt()}%")
            } else if (pnlPct < -5.0) {
                v3LossesFdgWouldBlock++
                ErrorLogger.warn(TAG, "⚠️ V3 LOSS | FDG would block | PnL: ${pnlPct.toInt()}%")
            }
        }
    }
    
    /**
     * Get comparison summary for logging
     */
    fun getComparisonSummary(): String {
        val total = v3Executes + v3Rejects + v3Watches + v3Blocks
        if (total == 0) return "V3 COMPARISON: No decisions yet"
        
        val agreePct = if (fdgAgrees + fdgDisagrees > 0) {
            (fdgAgrees * 100) / (fdgAgrees + fdgDisagrees)
        } else 0
        
        return buildString {
            append("V3 vs FDG: ")
            append("agree=${agreePct}% | ")
            append("v3_exec=$v3Executes v3_rej=$v3Rejects | ")
            if (v3WinsFdgWouldBlock + v3LossesFdgWouldBlock > 0) {
                append("v3_edge: +${v3WinsFdgWouldBlock}W -${v3LossesFdgWouldBlock}L")
            }
        }
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
    
    private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
}

/**
 * V3 Decision result - matches ProcessResult structure
 * 
 * V3.2 UNIFIED DECISION LABELS:
 * - Execute: Will trade
 * - Watch: Tracking only
 * - ShadowOnly: Pre-proposal kill, learning only
 * - BlockFatal: Fatal risk, hard block
 * - Rejected: Poor setup
 */
sealed class V3Decision {
    data class Execute(
        val sizeSol: Double,
        val band: String,
        val confidence: Double,
        val score: Int,
        val breakdown: String = ""  // Score breakdown for logging
    ) : V3Decision()
    
    data class Watch(
        val score: Int,
        val confidence: Int
    ) : V3Decision()
    
    /** Pre-proposal kill - shadow tracking only */
    data class ShadowOnly(
        val score: Int,
        val confidence: Int,
        val reason: String
    ) : V3Decision()
    
    data class Rejected(val reason: String) : V3Decision()
    
    /** Fatal block - hard risk detected */
    data class BlockFatal(val reason: String) : V3Decision()
    
    /** Legacy alias - to be deprecated */
    data class Blocked(val reason: String) : V3Decision()
    
    data class Error(val message: String) : V3Decision()
    data class NotReady(val reason: String) : V3Decision()
    
    fun shouldExecute(): Boolean = this is Execute
    
    companion object {
        fun execute(sizeSol: Double, band: String, confidence: Double, score: Int, breakdown: String = "") = 
            Execute(sizeSol, band, confidence, score, breakdown)
        fun watch(score: Int, confidence: Int) = Watch(score, confidence)
        fun shadowOnly(score: Int, confidence: Int, reason: String) = ShadowOnly(score, confidence, reason)
        fun rejected(reason: String) = Rejected(reason)
        fun blockFatal(reason: String) = BlockFatal(reason)
        fun blocked(reason: String) = Blocked(reason)  // Legacy
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

/**
 * V3 Entry Record for outcome tracking
 */
data class V3EntryRecord(
    val mint: String,
    val symbol: String,
    val entryPrice: Double,
    val sizeSol: Double,
    val v3Score: Int,
    val v3Band: String,
    val v3Confidence: Double,
    val source: String,
    val liquidityUsd: Double,
    val isPaper: Boolean,
    val entryTime: Long
)
