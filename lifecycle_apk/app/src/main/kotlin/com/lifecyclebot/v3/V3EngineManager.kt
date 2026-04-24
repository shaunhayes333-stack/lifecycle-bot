package com.lifecyclebot.v3

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.v3.bridge.V3Adapter
import com.lifecyclebot.v3.core.BotOrchestrator
import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.ProcessResult
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.decision.OpsMetrics
import com.lifecyclebot.v3.eligibility.CooldownManager
import com.lifecyclebot.v3.eligibility.EligibilityGate
import com.lifecyclebot.v3.eligibility.ExposureGuard
import com.lifecyclebot.v3.learning.LearningEvent
import com.lifecyclebot.v3.learning.LearningStore
import com.lifecyclebot.v3.risk.FatalRiskChecker
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.SmartSizerV3
import com.lifecyclebot.v3.sizing.WalletSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V3 Engine Manager
 *
 * Central manager for the V3 scoring-based trading engine.
 * Handles initialization, configuration, and provides the main entry point
 * for processing trade candidates.
 *
 * Defensive rewrite goals:
 * - Never leave stale orchestrator/config state alive after re-init
 * - Avoid GlobalScope leaks
 * - Keep runtime thread-safe
 * - Fail closed and log clearly
 */
object V3EngineManager {

    private const val TAG = "V3Engine"

    private val stateLock = Any()

    // Engine state
    @Volatile private var initialized = false
    @Volatile private var config: TradingConfigV3? = null
    @Volatile private var context: TradingContext? = null
    @Volatile private var botConfig: BotConfig? = null

    // Core components
    @Volatile private var cooldownManager: CooldownManager? = null
    @Volatile private var exposureGuard: ExposureGuard? = null
    @Volatile private var learningStore: LearningStore? = null
    @Volatile private var shadowTracker: ShadowTracker? = null
    @Volatile private var orchestrator: BotOrchestrator? = null

    // Background scope for non-blocking uploads/tasks
    @Volatile private var backgroundScope: CoroutineScope? = null

    // V3 entry tracking for outcome learning
    private val v3Entries = ConcurrentHashMap<String, V3EntryRecord>()

    // Callbacks
    @Volatile private var onExecuteCallback: ((ExecuteRequest) -> ExecuteResult)? = null
    @Volatile private var onLogCallback: ((String, String) -> Unit)? = null

    // Comparison metrics
    private val v3Executes = AtomicInteger(0)
    private val v3Rejects = AtomicInteger(0)
    private val v3Watches = AtomicInteger(0)
    private val v3Blocks = AtomicInteger(0)
    private val fdgAgrees = AtomicInteger(0)
    private val fdgDisagrees = AtomicInteger(0)
    private val v3WinsFdgWouldBlock = AtomicInteger(0)
    private val v3LossesFdgWouldBlock = AtomicInteger(0)

    /**
     * Initialize the V3 engine with configuration.
     * Safe to call multiple times; it will rebuild state cleanly.
     */
    fun initialize(
        botCfg: BotConfig,
        onExecute: ((ExecuteRequest) -> ExecuteResult)? = null,
        onLog: ((String, String) -> Unit)? = null
    ) {
        synchronized(stateLock) {
            if (!botCfg.v3EngineEnabled) {
                ErrorLogger.info(TAG, "V3 Engine disabled in config")
                initialized = false
                return
            }

            ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
            ErrorLogger.info(TAG, "   INITIALIZING V3 SCORING ENGINE")
            ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")

            try {
                // Cleanly replace old async scope if reinitializing
                backgroundScope?.cancel()
                backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

                botConfig = botCfg

                config = TradingConfigV3(
                    minLiquidityUsd = 500.0,
                    // V5.9.155 — widen the age gate. 30-min was a pump.fun-
                    // only floor. User 04-23 log: scanner ENQUEUED=412 but
                    // only ~4 tokens fresh (<30m) in a 10s window — pippin,
                    // TROLL, AlienPippin, FROGDOG (CoinGecko trending, days
                    // old) were all rejected TOO_OLD before scoring ever
                    // saw them. The V3 scoring stack already penalises
                    // stale narratives via momentum/volume/fearGreed/etc;
                    // letting the scorer make the call instead of a hard
                    // age gate restores the 1000+/hr regime where most of
                    // the winning signals came from trending older tokens
                    // with fresh buy-pressure, not brand-new launches.
                    // Paper mode opens wide (48h); live stays conservative
                    // at 6h so the bot trades real money only on relatively
                    // fresh opportunities.
                    maxTokenAgeMinutes = if (botCfg.paperMode) 2880.0 else 360.0,
                    watchScoreMin = 5,
                    executeSmallMin = 15,
                    executeStandardMin = botCfg.v3MinScoreToTrade.coerceIn(20, 60),
                    executeAggressiveMin = 45,
                    fatalRugThreshold = 90,
                    candidateTtlMinutes = 20,
                    shadowTrackNearMissMin = 5,
                    reserveSol = 0.05,
                    maxSmallSizePct = 0.04,
                    maxStandardSizePct = 0.07,
                    maxAggressiveSizePct = if (botCfg.v3ConservativeMode) 0.08 else 0.12,
                    paperLearningSizeMult = 0.50
                )

                val mode = when {
                    botCfg.paperMode -> V3BotMode.PAPER
                    botCfg.v3ShadowMode -> V3BotMode.LEARNING
                    else -> V3BotMode.LIVE
                }

                context = TradingContext(
                    config = requireNotNull(config),
                    mode = mode,
                    marketRegime = "NEUTRAL"
                )

                cooldownManager = CooldownManager()
                exposureGuard = ExposureGuard(
                    // V5.9.68: Paper mode is "free money" — give learning
                    // plenty of concurrent room (50 slots) and let it run
                    // up to 95% of total equity. Live mode stays conservative.
                    // V5.9.100: user raised live slot ceiling 10 -> 100.
                    maxOpenPositions = if (botCfg.paperMode) 100 else botCfg.maxConcurrentPositions.coerceAtMost(100),
                    maxExposurePct = if (botCfg.paperMode) 0.95
                                     else (botCfg.v3MaxExposurePct / 100.0).coerceIn(0.0, 1.0)
                )
                learningStore = LearningStore()
                shadowTracker = ShadowTracker()

                recreateOrchestratorLocked()

                onExecuteCallback = onExecute
                onLogCallback = onLog

                wireTradeExecutorCallbackLocked()

                initialized = true

                val modeTag = when (mode) {
                    V3BotMode.PAPER -> "📝 PAPER"
                    V3BotMode.LEARNING -> "🔬 SHADOW/LEARNING"
                    V3BotMode.LIVE -> "🔥 LIVE"
                }

                ErrorLogger.info(TAG, "V3 Engine initialized: $modeTag")
                ErrorLogger.info(TAG, "  - Execute threshold: ${config?.executeStandardMin}")
                ErrorLogger.info(TAG, "  - Max exposure: ${botCfg.v3MaxExposurePct}%")
                ErrorLogger.info(TAG, "  - Conservative: ${botCfg.v3ConservativeMode}")
                ErrorLogger.info(TAG, "═══════════════════════════════════════════════════════")
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Failed to initialize V3 engine: ${e.message}", e)
                initialized = false
            }
        }
    }

    /**
     * Check if V3 engine is ready.
     */
    fun isReady(): Boolean = initialized && orchestrator != null

    /**
     * Get current V3 mode.
     */
    fun getMode(): V3BotMode = context?.mode ?: V3BotMode.PAPER

    /**
     * HOT MODE SWITCH: Update V3 mode without full re-init.
     * Called when user toggles PAPER ↔ LIVE without restarting the bot.
     * Preserves all learned state, cooldowns, and exposure tracking.
     * Only changes the execution behaviour (paper sim vs real tx).
     */
    fun updateMode(newBotConfig: BotConfig) {
        synchronized(stateLock) {
            if (!initialized) {
                // Not running yet — just do a full init
                initialize(newBotConfig)
                return
            }

            val newMode = when {
                newBotConfig.paperMode -> V3BotMode.PAPER
                newBotConfig.v3ShadowMode -> V3BotMode.LEARNING
                else -> V3BotMode.LIVE
            }

            val currentMode = context?.mode ?: V3BotMode.PAPER
            if (currentMode == newMode) {
                ErrorLogger.debug(TAG, "updateMode: already in $newMode — no change")
                return
            }

            ErrorLogger.info(TAG, "═══ V3 HOT MODE SWITCH: $currentMode → $newMode ═══")

            // Update context mode in-place (preserves marketRegime, history, etc.)
            context = context?.copy(mode = newMode)

            // Update botConfig reference for threshold decisions
            botConfig = newBotConfig

            // Resize exposure guard slot limit for new mode
            exposureGuard = ExposureGuard(
                maxOpenPositions = if (newBotConfig.paperMode) 100 else newBotConfig.maxConcurrentPositions.coerceAtMost(100),
                maxExposurePct = if (newBotConfig.paperMode) 0.95
                                 else (newBotConfig.v3MaxExposurePct / 100.0).coerceIn(0.0, 1.0)
            )

            val modeTag = when (newMode) {
                V3BotMode.PAPER -> "📝 PAPER"
                V3BotMode.LEARNING -> "🔬 SHADOW/LEARNING"
                V3BotMode.LIVE -> "🔥 LIVE"
            }
            ErrorLogger.info(TAG, "V3 mode updated to $modeTag — learning state preserved")
        }
    }

    /**
     * Process a token through the V3 pipeline.
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
        isAIDegraded: Boolean = false
    ): V3Decision {
        if (!isReady()) {
            return V3Decision.notReady("V3 Engine not initialized")
        }

        return try {
            val currentContext = context ?: return V3Decision.notReady("V3 context missing")
            val currentBotConfig = botConfig ?: return V3Decision.notReady("V3 config missing")

            // V4.0 layer routing
            val tokenAgeMinutes = if (ts.addedToWatchlistAt > 0L) {
                (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
            } else {
                120.0
            }
            val tokenAgeHours = tokenAgeMinutes / 60.0

            val isShitCoinCandidate =
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.isShitCoinCandidate(
                    marketCapUsd = ts.lastMcap,
                    tokenAgeMinutes = tokenAgeMinutes
                )

            val isQualityLowCap =
                com.lifecyclebot.v3.scoring.LayerTransitionManager.isV3QualityCandidate(
                    marketCapUsd = ts.lastMcap,
                    tokenAgeHours = tokenAgeHours,
                    liquidityUsd = ts.lastLiquidityUsd,
                    buyPressurePct = ts.lastBuyPressurePct,
                    holderConcentration = ts.topHolderPct ?: 20.0
                )

            if (isShitCoinCandidate && !isQualityLowCap) {
                return V3Decision.rejected(
                    "SHITCOIN_CANDIDATE: mcap=\$${(ts.lastMcap / 1_000).toInt()}K - handled by ShitCoin layer"
                )
            }

            val minMcap = com.lifecyclebot.v3.scoring.LayerTransitionManager.V3_MIN_MCAP
            if (ts.lastMcap < minMcap && !isQualityLowCap) {
                return V3Decision.rejected(
                    "MCAP_TOO_LOW: \$${(ts.lastMcap / 1_000).toInt()}K < \$${(minMcap / 1_000).toInt()}K"
                )
            }

            ensureRuntimeContext(
                mode = currentContext.mode,
                marketRegime = marketRegime
            )

            val guard = exposureGuard
            // V5.9.68 FIX: exposure % must be against TOTAL equity (free + at-risk),
            // not just the free cash that remains after buys. Dividing by `walletSol`
            // alone caused `exposure / freeCash` to blow past 100% in paper whenever
            // more than half the starting capital was deployed, which globally
            // locked out all new entries even though the portfolio was healthy.
            // V5.9.99 CRITICAL LIVE FIX: when totalEquity is zero (live wallet
            // hasn't finished loading, or brand-new tiny account with stale
            // status values) DO NOT claim 100% exposure — that was silently
            // firing GLOBAL_EXPOSURE_MAX on every V3 eligibility check for
            // a fresh live account, blocking all entries despite zero open
            // positions. Default to 0% so the position-count leg remains
            // the actual guard until a real balance is known.
            val totalEquity = walletSol + totalExposureSol
            val exposurePct = if (totalEquity > 0.0) {
                (totalExposureSol / totalEquity).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            guard?.currentExposurePct = exposurePct

            val candidate = V3Adapter.toCandidate(ts)

            val reserveSol = config?.reserveSol ?: 0.05
            val wallet = WalletSnapshot(
                totalSol = walletSol,
                tradeableSol = (walletSol - reserveSol).coerceAtLeast(0.0)
            )

            val risk = PortfolioRiskState(recentDrawdownPct = 0.0)

            val learningMetrics = com.lifecyclebot.v3.learning.LearningMetrics(
                classifiedTrades = recentTradeCount,
                last20WinRatePct = recentWinRate,
                payoffRatio = run {  // V5.9.187: real payoff from lifetime stats
                    val ls = try { com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats() } catch (_: Exception) { null }
                    val w = ls?.avgWinPct ?: 0.0; val lo = ls?.avgLossPct ?: 0.0
                    if (w > 0.0 && lo > 0.0) (w / lo).coerceIn(0.5, 5.0) else 1.0
                },
            )

            val opsMetrics = OpsMetrics(
                apiHealthy = !isAIDegraded,
                feedsHealthy = true,
                walletHealthy = walletSol > 0.0 || currentBotConfig.paperMode,
                latencyMs = 100
            )

            val localOrchestrator = orchestrator ?: return V3Decision.notReady("V3 orchestrator missing")

            val result = localOrchestrator.processCandidate(
                candidate = candidate,
                wallet = wallet,
                risk = risk,
                learningMetrics = learningMetrics,
                opsMetrics = opsMetrics
            )

            when (result) {
                is ProcessResult.Executed -> {
                    val log = "V3 EXECUTE: ${ts.symbol} | band=${result.band} | size=${result.sizeSol.fmt(4)} SOL | conf=${result.confidence}%"
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
                    onLogCallback?.invoke("V3 BLOCK: ${ts.symbol} | ${result.reason}", ts.mint)
                    V3Decision.blocked(result.reason)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "V3 processing error for ${ts.symbol}: ${e.message}", e)
            V3Decision.error(e.message ?: "Unknown error")
        }
    }

    /**
     * Execute a V3 trade.
     */
    fun executeV3Trade(
        ts: TokenState,
        sizeSol: Double,
        wallet: SolanaWallet
    ): V3ExecutionResult {
        if (!isReady()) {
            return V3ExecutionResult.failed("V3 Engine not initialized")
        }

        val currentMode = context?.mode ?: V3BotMode.PAPER

        if (currentMode == V3BotMode.PAPER) {
            ErrorLogger.info(TAG, "V3 PAPER TRADE: ${ts.symbol} | ${sizeSol.fmt(4)} SOL")
            return V3ExecutionResult.paper(sizeSol)
        }

        if (currentMode == V3BotMode.LEARNING) {
            ErrorLogger.info(TAG, "V3 SHADOW TRADE: ${ts.symbol} | ${sizeSol.fmt(4)} SOL (logged only)")
            return V3ExecutionResult.shadow(sizeSol)
        }

        return try {
            val callback = onExecuteCallback
                ?: return V3ExecutionResult.failed("No execution callback configured")

            val request = ExecuteRequest(
                mint = ts.mint,
                symbol = ts.symbol,
                sizeSol = sizeSol,
                isBuy = true
            )
            val result = callback.invoke(request)

            if (result.success) {
                exposureGuard?.openPosition(ts.mint)
                V3ExecutionResult.success(
                    txSignature = result.txSignature,
                    executedSol = result.executedSol,
                    executedPrice = result.executedPrice
                )
            } else {
                V3ExecutionResult.failed(result.error ?: "Execution failed")
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "V3 execution error: ${e.message}", e)
            V3ExecutionResult.failed(e.message ?: "Execution error")
        }
    }

    /**
     * Record trade outcome for learning.
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
            val entry = v3Entries.remove(mint)

            val resolvedBand = entry?.v3Band?.toDecisionBandOrNull() ?: DecisionBand.EXECUTE_STANDARD
            val resolvedScore = entry?.v3Score ?: 50
            val resolvedConfidence = (entry?.v3Confidence ?: 50.0).toInt()
            val resolvedSource = entry?.source ?: discoverySource
            val resolvedLiquidityUsd = if (entry?.liquidityUsd ?: 0.0 > 0.0) {
                entry!!.liquidityUsd
            } else {
                liquidityUsd
            }
            val resolvedHoldSec = (holdTimeMinutes * 60).coerceAtLeast(0)

            val event = LearningEvent(
                mint = mint,
                symbol = symbol,
                decisionBand = resolvedBand,
                finalScore = resolvedScore,
                confidence = resolvedConfidence,
                outcomeLabel = if (pnlPct > 0) "WIN" else "LOSS",
                pnlPct = pnlPct,
                holdingTimeSec = resolvedHoldSec
            )

            learningStore?.record(event)

            ErrorLogger.info(
                TAG,
                "V3 OUTCOME: $symbol | PnL=${pnlPct.fmt(1)}% | hold=${holdTimeMinutes}min | $exitReason"
            )

            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                backgroundScope?.launch {
                    try {
                        val liquidityBucket = when {
                            resolvedLiquidityUsd < 5_000 -> "MICRO"
                            resolvedLiquidityUsd < 25_000 -> "SMALL"
                            resolvedLiquidityUsd < 100_000 -> "MID"
                            else -> "LARGE"
                        }

                        com.lifecyclebot.collective.CollectiveLearning.uploadPatternOutcome(
                            patternType = "${entryPhase}_${tradingMode}",
                            discoverySource = resolvedSource,
                            liquidityBucket = liquidityBucket,
                            emaTrend = emaTrend,
                            isWin = pnlPct > 5.0,
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
            ErrorLogger.error(TAG, "Failed to record outcome: ${e.message}", e)
        }
    }

    /**
     * V5.6.8: Mark position opened - notify ExposureGuard of new position
     * Called by BotService when Treasury/other layers open positions
     */
    fun onPositionOpened(mint: String) {
        exposureGuard?.openPosition(mint)
    }

    /**
     * Mark position closed.
     */
    fun onPositionClosed(mint: String) {
        exposureGuard?.closePosition(mint)
    }

    /**
     * Set cooldown for a mint.
     */
    fun setCooldown(mint: String, durationMs: Long) {
        cooldownManager?.setCooldown(mint, System.currentTimeMillis() + durationMs)
    }

    /**
     * Get V3 engine status summary.
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

    /**
     * Record a V3 decision for comparison tracking.
     */
    fun recordDecisionComparison(
        v3Decision: String,
        fdgWouldExecute: Boolean
    ) {
        when (v3Decision) {
            "EXECUTE" -> v3Executes.incrementAndGet()
            "WATCH" -> v3Watches.incrementAndGet()
            "REJECT" -> v3Rejects.incrementAndGet()
            "BLOCK", "BLOCK_FATAL" -> v3Blocks.incrementAndGet()
        }

        val v3WouldExecute = v3Decision == "EXECUTE"
        if (v3WouldExecute == fdgWouldExecute) {
            fdgAgrees.incrementAndGet()
        } else {
            fdgDisagrees.incrementAndGet()
        }
    }

    /**
     * Record entry for learning.
     * Called immediately after V3 executes a buy.
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
        ErrorLogger.info(
            TAG,
            "[ENTRY] $symbol | price=${"%.8f".format(entryPrice)} | size=${"%.4f".format(sizeSol)} SOL | score=$v3Score | band=$v3Band | conf=${v3Confidence.toInt()}% | ${if (isPaper) "PAPER" else "LIVE"}"
        )

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
     * Record outcome of V3 decision for learning against FDG.
     */
    fun recordV3OutcomeVsFdg(
        v3Executed: Boolean,
        fdgWouldExecute: Boolean,
        pnlPct: Double
    ) {
        if (v3Executed && !fdgWouldExecute) {
            if (pnlPct > 5.0) {
                v3WinsFdgWouldBlock.incrementAndGet()
                ErrorLogger.info(TAG, "🎯 V3 WIN | FDG would block | PnL: +${pnlPct.toInt()}%")
            } else if (pnlPct < -5.0) {
                v3LossesFdgWouldBlock.incrementAndGet()
                ErrorLogger.warn(TAG, "⚠️ V3 LOSS | FDG would block | PnL: ${pnlPct.toInt()}%")
            }
        }
    }

    /**
     * Get comparison summary for logging.
     */
    fun getComparisonSummary(): String {
        val exec = v3Executes.get()
        val rej = v3Rejects.get()
        val watch = v3Watches.get()
        val block = v3Blocks.get()
        val agree = fdgAgrees.get()
        val disagree = fdgDisagrees.get()
        val wins = v3WinsFdgWouldBlock.get()
        val losses = v3LossesFdgWouldBlock.get()

        val total = exec + rej + watch + block
        if (total == 0) return "V3 COMPARISON: No decisions yet"

        val agreePct = if (agree + disagree > 0) {
            (agree * 100) / (agree + disagree)
        } else {
            0
        }

        return buildString {
            append("V3 vs FDG: ")
            append("agree=${agreePct}% | ")
            append("v3_exec=$exec v3_rej=$rej v3_watch=$watch v3_block=$block")
            if (wins + losses > 0) {
                append(" | v3_edge: +${wins}W -${losses}L")
            }
        }
    }

    /**
     * Shutdown the V3 engine.
     */
    fun shutdown() {
        synchronized(stateLock) {
            ErrorLogger.info(TAG, "Shutting down V3 engine")

            initialized = false
            orchestrator = null
            config = null
            context = null
            botConfig = null

            cooldownManager = null
            exposureGuard = null
            learningStore = null
            shadowTracker = null

            onExecuteCallback = null
            onLogCallback = null

            v3Entries.clear()

            backgroundScope?.cancel()
            backgroundScope = null
        }
    }

    private fun recreateOrchestratorLocked() {
        val localConfig = requireNotNull(config) { "V3 config missing" }
        val localContext = requireNotNull(context) { "V3 context missing" }

        val localCooldown = cooldownManager ?: CooldownManager().also { cooldownManager = it }
        val localExposure = exposureGuard ?: ExposureGuard().also { exposureGuard = it }

        orchestrator = BotOrchestrator(
            ctx = localContext,
            eligibilityGate = EligibilityGate(localConfig, localCooldown, localExposure),
            fatalRiskChecker = FatalRiskChecker(localConfig),
            finalDecisionEngine = FinalDecisionEngine(localConfig),
            smartSizer = SmartSizerV3(localConfig)
        )
    }

    /**
     * Rebuild runtime context/orchestrator when mode or market regime changes.
     */
    private fun ensureRuntimeContext(
        mode: V3BotMode,
        marketRegime: String
    ) {
        synchronized(stateLock) {
            val cfg = config ?: return
            val current = context

            val needsRebuild = current == null ||
                current.mode != mode ||
                current.marketRegime != marketRegime ||
                orchestrator == null

            if (!needsRebuild) return

            context = TradingContext(
                config = cfg,
                mode = mode,
                marketRegime = marketRegime
            )

            recreateOrchestratorLocked()
        }
    }

    private fun wireTradeExecutorCallbackLocked() {
        com.lifecyclebot.v3.execution.TradeExecutor.executeCallback = { candidate, size, _, _ ->
            val callback = onExecuteCallback

            val request = ExecuteRequest(
                mint = candidate.mint,
                symbol = candidate.symbol,
                sizeSol = size.sizeSol,
                isBuy = true
            )

            val result = try {
                callback?.invoke(request)
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Execution callback error: ${e.message}", e)
                null
            }

            com.lifecyclebot.v3.execution.TradeExecutionResult(
                success = result?.success ?: false,
                txSignature = result?.txSignature,
                executedSize = result?.executedSol ?: 0.0,
                executedPrice = result?.executedPrice,
                error = result?.error ?: if (callback == null) "No execution callback configured" else "Execution failed"
            )
        }
    }

    private fun String.toDecisionBandOrNull(): DecisionBand? {
        return try {
            DecisionBand.valueOf(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun Double.fmt(d: Int = 4): String = "%.${d}f".format(this)
}

/**
 * V3 Decision result - matches ProcessResult structure.
 */
sealed class V3Decision {
    data class Execute(
        val sizeSol: Double,
        val band: String,
        val confidence: Double,
        val score: Int,
        val breakdown: String = ""
    ) : V3Decision()

    data class Watch(
        val score: Int,
        val confidence: Int
    ) : V3Decision()

    data class ShadowOnly(
        val score: Int,
        val confidence: Int,
        val reason: String
    ) : V3Decision()

    data class Rejected(val reason: String) : V3Decision()
    data class BlockFatal(val reason: String) : V3Decision()
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
