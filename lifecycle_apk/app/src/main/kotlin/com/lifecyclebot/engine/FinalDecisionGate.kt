package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.quant.EVCalculator
import com.lifecyclebot.v3.scoring.FluidLearningAI

@Deprecated(
    message = "V3 Architecture Migration: Use v3/decision/FinalDecisionEngine instead",
    replaceWith = ReplaceWith("FinalDecisionEngine", "com.lifecyclebot.v3.decision.FinalDecisionEngine"),
    level = DeprecationLevel.WARNING
)
object FinalDecisionGate {

    data class FinalDecision(
        val shouldTrade: Boolean,
        val mode: TradeMode,
        val approvalClass: ApprovalClass,
        val quality: String,
        val confidence: Double,
        val edge: EdgeVerdict,
        val blockReason: String?,
        val blockLevel: BlockLevel?,
        val sizeSol: Double,
        val tags: List<String>,
        val mint: String,
        val symbol: String,
        val approvalReason: String,
        val gateChecks: List<GateCheck>,
    ) {
        fun canExecute(): Boolean = shouldTrade && blockReason == null
        fun isBenchmarkQuality(): Boolean = approvalClass in listOf(ApprovalClass.LIVE, ApprovalClass.PAPER_BENCHMARK)
        fun isExploration(): Boolean = approvalClass == ApprovalClass.PAPER_EXPLORATION

        fun summary(): String = buildString {
            append(if (shouldTrade) "✅" else "❌")
            append(" $symbol | $approvalClass | $quality | ${confidence.toInt()}% | ${edge.name}")
            if (blockReason != null) append(" | BLOCKED: $blockReason")
            if (shouldTrade) append(" | ${sizeSol.format(3)} SOL")
        }
    }

    enum class TradeMode {
        PAPER,
        LIVE,
    }

    enum class EdgeVerdict {
        STRONG,
        WEAK,
        SKIP,
    }

    enum class BlockLevel {
        HARD,
        EDGE,
        CONFIDENCE,
        MODE,
        SIZE,
    }

    enum class ApprovalClass {
        LIVE,
        PAPER_BENCHMARK,
        PAPER_EXPLORATION,
        PAPER_PROBE,
        BLOCKED,
    }

    fun FinalDecision.isProbe(): Boolean = approvalClass == ApprovalClass.PAPER_PROBE

    data class GateCheck(
        val name: String,
        val passed: Boolean,
        val reason: String?,
    )

    data class ClosedLoopFeedback(
        val lifetimeWinRate: Double = 50.0,
        val lifetimeTrades: Int = 0,
        val smoothedWinRate: Double = 50.0,
        val lastUpdateTime: Long = 0L,
    )

    @Volatile
    private var closedLoopFeedback = ClosedLoopFeedback()

    private const val FEEDBACK_EMA_ALPHA = 0.15
    private const val FEEDBACK_MIN_TRADES = 10
    private const val FEEDBACK_MAX_ADJUSTMENT = 15.0

    fun updateClosedLoopFeedback(lifetimeWinRate: Double, lifetimeTrades: Int) {
        val oldSmoothed = closedLoopFeedback.smoothedWinRate
        val newSmoothed = if (closedLoopFeedback.lastUpdateTime == 0L) {
            lifetimeWinRate
        } else {
            FEEDBACK_EMA_ALPHA * lifetimeWinRate + (1 - FEEDBACK_EMA_ALPHA) * oldSmoothed
        }

        closedLoopFeedback = ClosedLoopFeedback(
            lifetimeWinRate = lifetimeWinRate,
            lifetimeTrades = lifetimeTrades,
            smoothedWinRate = newSmoothed,
            lastUpdateTime = System.currentTimeMillis(),
        )

        if (kotlin.math.abs(newSmoothed - oldSmoothed) > 2.0) {
            ErrorLogger.debug(
                "FDG",
                "Closed-loop feedback: ${oldSmoothed.toInt()}% → ${newSmoothed.toInt()}% (raw: ${lifetimeWinRate.toInt()}%, ${lifetimeTrades} trades)"
            )
        }
    }

    fun getClosedLoopConfidenceAdjustment(): Double {
        val feedback = closedLoopFeedback
        if (feedback.lifetimeTrades < FEEDBACK_MIN_TRADES) return 0.0

        val smoothedRate = feedback.smoothedWinRate
        val adjustment = when {
            smoothedRate >= 65.0 -> -FEEDBACK_MAX_ADJUSTMENT
            smoothedRate >= 60.0 -> -10.0
            smoothedRate >= 55.0 -> -5.0
            smoothedRate <= 35.0 -> +FEEDBACK_MAX_ADJUSTMENT
            smoothedRate <= 40.0 -> +10.0
            smoothedRate <= 45.0 -> +5.0
            else -> 0.0
        }

        return adjustment.coerceIn(-FEEDBACK_MAX_ADJUSTMENT, FEEDBACK_MAX_ADJUSTMENT)
    }

    fun getClosedLoopState(): String {
        val feedback = closedLoopFeedback
        if (feedback.lifetimeTrades < FEEDBACK_MIN_TRADES) {
            return "INACTIVE (need ${FEEDBACK_MIN_TRADES - feedback.lifetimeTrades} more trades)"
        }
        val adj = getClosedLoopConfidenceAdjustment()
        val mode = when {
            adj < -5 -> "AGGRESSIVE"
            adj > 5 -> "DEFENSIVE"
            else -> "NEUTRAL"
        }
        return "$mode (${feedback.smoothedWinRate.toInt()}% smoothed, ${adj.toInt()}% adj)"
    }

    enum class LearningPhase {
        BOOTSTRAP,
        LEARNING,
        MATURE
    }

    fun getLearningPhase(tradeCount: Int): LearningPhase = when {
        tradeCount <= 50 -> LearningPhase.BOOTSTRAP
        tradeCount <= 500 -> LearningPhase.LEARNING
        else -> LearningPhase.MATURE
    }

    fun getLearningProgress(tradeCount: Int, winRate: Double): Double {
        val tradeProgress = (tradeCount.toDouble() / 500.0).coerceIn(0.0, 1.0)
        val winRateBonus = if (winRate > 50.0) 0.1 else 0.0
        return (tradeProgress + winRateBonus).coerceIn(0.0, 1.0)
    }

    fun lerp(loose: Double, strict: Double, progress: Double): Double {
        return loose + (strict - loose) * progress
    }

    var hardBlockRugcheckMin = 2
    var hardBlockBuyPressureMin = 10.0
    var hardBlockTopHolderMax = 80.0

    data class ModeLossRecord(
        val mode: String,
        val timestamp: Long,
        val pnlPct: Double,
    )

    private val recentModeLosses = mutableListOf<ModeLossRecord>()
    private const val MODE_LOSS_WINDOW_MS = 60 * 60 * 1000L
    private const val MODE_FREEZE_THRESHOLD = 100

    fun recordModeLoss(mode: String, pnlPct: Double) {
        if (pnlPct >= 0) return

        synchronized(recentModeLosses) {
            recentModeLosses.add(ModeLossRecord(mode.uppercase(), System.currentTimeMillis(), pnlPct))
            val cutoff = System.currentTimeMillis() - MODE_LOSS_WINDOW_MS
            recentModeLosses.removeAll { it.timestamp < cutoff }
        }

        ErrorLogger.debug("FDG", "Mode loss recorded: $mode (${pnlPct}%) | Recent: ${getModeRecentLosses(mode)} losses")
    }

    fun getModeRecentLosses(mode: String): Int {
        val cutoff = System.currentTimeMillis() - MODE_LOSS_WINDOW_MS
        val normalizedMode = mode.uppercase()

        return synchronized(recentModeLosses) {
            recentModeLosses.count {
                it.timestamp >= cutoff &&
                    (it.mode.contains(normalizedMode) || normalizedMode.contains(it.mode))
            }
        }
    }

    fun isModeFrozen(mode: String): Boolean {
        return getModeRecentLosses(mode) >= MODE_FREEZE_THRESHOLD
    }

    var earlySnipeEnabled = true
    var earlySnipeMaxAgeMinutes = 15
    var earlySnipeMinScore = 30.0
    var earlySnipeMinLiquidity = 5000.0

    var evGatingEnabled = false
    var minExpectedValue = 1.0
    var maxRugProbability = 0.30
    var useKellySizing = false
    var kellyFraction = 0.5
    var maxKellySize = 0.10

    private const val CONF_FLOOR_BOOTSTRAP = 3.0
    private const val CONF_FLOOR_MATURE = 60.0

    var paperConfidenceBase = 3.0
    var liveConfidenceBase = 0.0

    private var consecutiveBlockCount = 0
    private var lastBlockReason: String? = null
    private var adaptiveRelaxationActive = false
    private const val DANGER_ZONE_BYPASS_THRESHOLD = 8
    private const val MEMORY_BYPASS_THRESHOLD = 10
    private const val MAX_RELAXATION_TRADES = 3
    private var relaxationTradesUsed = 0

    fun recordBlock(reason: String) {
        consecutiveBlockCount++
        lastBlockReason = reason

        if (consecutiveBlockCount >= DANGER_ZONE_BYPASS_THRESHOLD && !adaptiveRelaxationActive) {
            adaptiveRelaxationActive = true
            relaxationTradesUsed = 0
            ErrorLogger.warn("FDG", "🔓 ADAPTIVE RELAXATION ACTIVATED after $consecutiveBlockCount consecutive blocks")
        }
    }

    fun recordTradeExecuted() {
        if (adaptiveRelaxationActive) {
            relaxationTradesUsed++
            if (relaxationTradesUsed >= MAX_RELAXATION_TRADES) {
                adaptiveRelaxationActive = false
                relaxationTradesUsed = 0
                ErrorLogger.info("FDG", "🔒 Adaptive relaxation deactivated (used $MAX_RELAXATION_TRADES relaxed trades)")
            }
        }
        consecutiveBlockCount = 0
        lastBlockReason = null
    }

    fun shouldBypassSoftBlock(blockType: String): Boolean {
        if (!adaptiveRelaxationActive) return false

        return when (blockType) {
            "DANGER_ZONE_TIME" -> consecutiveBlockCount >= DANGER_ZONE_BYPASS_THRESHOLD
            "MEMORY_NEGATIVE_BLOCK" -> consecutiveBlockCount >= MEMORY_BYPASS_THRESHOLD
            else -> false
        }
    }

    fun getAdaptiveFilterStatus(): String {
        return if (adaptiveRelaxationActive) {
            "🔓 RELAXED (blocks=$consecutiveBlockCount, used=$relaxationTradesUsed/$MAX_RELAXATION_TRADES)"
        } else {
            "🔒 STRICT (blocks=$consecutiveBlockCount)"
        }
    }

    data class AdjustedThresholds(
        val learningPhase: LearningPhase,
        val progress: Double,
        val tradeCount: Int,
        val winRate: Double,
        val rugcheckMin: Int,
        val buyPressureMin: Double,
        val topHolderMax: Double,
        val confidenceBase: Double,
        val edgeMinBuyPressure: Double,
        val edgeMinLiquidity: Double,
        val edgeMinScore: Double,
        val evGatingEnabled: Boolean,
        val maxRugProbability: Double,
    )

    fun getAdjustedThresholds(tradeCount: Int, winRate: Double): AdjustedThresholds {
        val phase = getLearningPhase(tradeCount)
        val progress = getLearningProgress(tradeCount, winRate)

        return AdjustedThresholds(
            learningPhase = phase,
            progress = progress,
            tradeCount = tradeCount,
            winRate = winRate,
            rugcheckMin = lerp(2.0, 12.0, progress).toInt(),
            buyPressureMin = lerp(10.0, 20.0, progress),
            topHolderMax = lerp(85.0, 60.0, progress),
            confidenceBase = lerp(0.0, 15.0, progress),
            edgeMinBuyPressure = lerp(38.0, 52.0, progress),
            edgeMinLiquidity = lerp(1500.0, 4000.0, progress),
            edgeMinScore = lerp(20.0, 40.0, progress),
            evGatingEnabled = phase == LearningPhase.MATURE,
            maxRugProbability = lerp(0.35, 0.12, progress),
        )
    }

    data class MarketConditions(
        val avgVolatility: Double = 5.0,
        val buyPressureTrend: Double = 50.0,
        val recentWinRate: Double = 50.0,
        val timeSinceLastLossMs: Long = Long.MAX_VALUE,
        val sessionPnlPct: Double = 0.0,
        val totalSessionTrades: Int = 0,
        val historicalTradeCount: Int = 0,
        val entryAiWinRate: Double = 50.0,
        val exitAiAvgPnl: Double = 0.0,
        val edgeLearningAccuracy: Double = 50.0,
    )

    private var currentConditions = MarketConditions()

    fun updateMarketConditions(
        avgVolatility: Double? = null,
        buyPressureTrend: Double? = null,
        recentWinRate: Double? = null,
        timeSinceLastLossMs: Long? = null,
        sessionPnlPct: Double? = null,
        totalSessionTrades: Int? = null,
        historicalTradeCount: Int? = null,
        entryAiWinRate: Double? = null,
        exitAiAvgPnl: Double? = null,
        edgeLearningAccuracy: Double? = null,
    ) {
        currentConditions = currentConditions.copy(
            avgVolatility = avgVolatility ?: currentConditions.avgVolatility,
            buyPressureTrend = buyPressureTrend ?: currentConditions.buyPressureTrend,
            recentWinRate = recentWinRate ?: currentConditions.recentWinRate,
            timeSinceLastLossMs = timeSinceLastLossMs ?: currentConditions.timeSinceLastLossMs,
            sessionPnlPct = sessionPnlPct ?: currentConditions.sessionPnlPct,
            totalSessionTrades = totalSessionTrades ?: currentConditions.totalSessionTrades,
            historicalTradeCount = historicalTradeCount ?: currentConditions.historicalTradeCount,
            entryAiWinRate = entryAiWinRate ?: currentConditions.entryAiWinRate,
            exitAiAvgPnl = exitAiAvgPnl ?: currentConditions.exitAiAvgPnl,
            edgeLearningAccuracy = edgeLearningAccuracy ?: currentConditions.edgeLearningAccuracy,
        )
    }

    fun getAdaptiveConfidence(isPaperMode: Boolean, ts: TokenState? = null): Double {
        val learningProgress = getLearningProgress(
            currentConditions.totalSessionTrades + currentConditions.historicalTradeCount,
            currentConditions.recentWinRate
        )

        val fluidBase = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE, learningProgress)

        // ═══════════════════════════════════════════════════════════════════════════
        // V5.6.28f: UNIFIED LEARNING - LIVE uses PAPER's learned confidence
        // When switching from PAPER to LIVE, the bot should operate identically.
        // All learning from PAPER mode carries over seamlessly.
        // The only difference is tighter stop-losses applied elsewhere.
        // ═══════════════════════════════════════════════════════════════════════════
        
        val floor = lerp(paperConfidenceBase, CONF_FLOOR_MATURE * 0.6, learningProgress)
        val adaptiveFloor = if (adaptiveRelaxationActive) {
            (floor * 0.5).coerceAtLeast(5.0)
        } else {
            floor
        }

        val modeLabel = if (isPaperMode) "PAPER" else "LIVE"
        val fdgState = EfficiencyLayer.createFdgState(
            mode = modeLabel,
            base = paperConfidenceBase.toInt(),
            adj = 0,
            final = adaptiveFloor.toInt(),
            learning = (learningProgress * 100).toInt(),
            bootstrap = false  // V5.6.28f: Never bootstrap - learning carries over
        )
        if (EfficiencyLayer.shouldLogFdgState(fdgState)) {
            ErrorLogger.info(
                "FDG",
                "📊 FLUID CONF ($modeLabel): floor=${adaptiveFloor.toInt()}% | learning=${(learningProgress * 100).toInt()}% | UNIFIED"
            )
        }

        return adaptiveFloor
    }

    fun getAdaptiveConfidenceExplanation(isPaperMode: Boolean): String {
        val totalTrades = currentConditions.totalSessionTrades + currentConditions.historicalTradeCount
        val learningProgress = getLearningProgress(totalTrades, currentConditions.recentWinRate)
        val fluidBase = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE, learningProgress)
        val adaptive = getAdaptiveConfidence(isPaperMode)
        val diff = adaptive - fluidBase
        val sign = if (diff >= 0) "+" else ""
        val isBootstrap = learningProgress < 0.40  // V5.9.165: aligned to global 0.40 threshold

        val tierLabel = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            tier.label
        } catch (_: Exception) {
            "?"
        }

        val regimeLabel = try {
            MarketRegimeAI.getCurrentRegime().label
        } catch (_: Exception) {
            "?"
        }

        return buildString {
            append("FluidConf: base=${fluidBase.toInt()}% ${sign}${diff.toInt()}% = ${adaptive.toInt()}% ")
            append("[learning=${(learningProgress * 100).toInt()}%${if (isBootstrap) " BOOTSTRAP" else ""} ")
            append("vol=${currentConditions.avgVolatility.toInt()}% ")
            append("wr=${currentConditions.recentWinRate.toInt()}% ")
            append("buy=${currentConditions.buyPressureTrend.toInt()}% ")
            append("tier=$tierLabel ")
            append("regime=$regimeLabel ")
            append("loop=${getClosedLoopState()} ")
            append("trades=$totalTrades]")
        }
    }

    fun getFluidConfidenceInfo(): FluidConfidenceState {
        val totalTrades = currentConditions.totalSessionTrades + currentConditions.historicalTradeCount
        val learningProgress = getLearningProgress(totalTrades, currentConditions.recentWinRate)
        val paperConf = getAdaptiveConfidence(isPaperMode = true)
        val liveConf = getAdaptiveConfidence(isPaperMode = false)

        return FluidConfidenceState(
            learningProgressPct = (learningProgress * 100).toInt(),
            totalTradesLearned = totalTrades,
            paperConfThreshold = paperConf.toInt(),
            liveConfThreshold = liveConf.toInt(),
            isBootstrap = learningProgress < 0.40,  // V5.9.165: aligned
            fluidBase = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE, learningProgress).toInt()
        )
    }

    data class FluidConfidenceState(
        val learningProgressPct: Int,
        val totalTradesLearned: Int,
        val paperConfThreshold: Int,
        val liveConfThreshold: Int,
        val isBootstrap: Boolean,
        val fluidBase: Int,
    ) {
        fun summary(): String = buildString {
            append("🧠 AI Learning: ${learningProgressPct}% ")
            append("($totalTradesLearned trades) | ")
            append("Conf: Paper≥${paperConfThreshold}% Live≥${liveConfThreshold}% ")
            if (isBootstrap) append("[BOOTSTRAP]")
        }
    }

    var allowEdgeOverrideInPaper = false

    private val distributionCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val DISTRIBUTION_COOLDOWN_MS_PAPER = 20 * 1000L
    private const val DISTRIBUTION_COOLDOWN_MS_LIVE = 60 * 1000L

    fun recordDistributionExit(mint: String) {
        distributionCooldowns[mint] = System.currentTimeMillis()
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000L)
        distributionCooldowns.entries.removeIf { it.value < twoHoursAgo }
    }

    fun isInDistributionCooldown(mint: String): Boolean {
        val exitTime = distributionCooldowns[mint] ?: return false
        val elapsed = System.currentTimeMillis() - exitTime
        return elapsed < DISTRIBUTION_COOLDOWN_MS_PAPER
    }

    fun getRemainingCooldownMinutes(mint: String): Int {
        val exitTime = distributionCooldowns[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - exitTime
        val remaining = DISTRIBUTION_COOLDOWN_MS_PAPER - elapsed
        return if (remaining > 0) (remaining / 60000).toInt() else 0
    }

    data class EdgeVeto(
        val timestamp: Long,
        val reason: String,
        val quality: String,
    )

    private val edgeVetoes = java.util.concurrent.ConcurrentHashMap<String, EdgeVeto>()
    // V5.9.46: Unified Edge-veto cooldown paper/live. Previous 6x stricter
    // live cooldown (30s vs 5s) kept vetoed tokens off the scanner long
    // after the reason faded, making the live scanner feel dead.
    private const val EDGE_VETO_COOLDOWN_PAPER_MS = 5 * 1000L
    private const val EDGE_VETO_COOLDOWN_LIVE_MS = 5 * 1000L

    @Volatile
    private var _isPaperModeForVeto = true

    fun setModeForVeto(isPaper: Boolean) {
        _isPaperModeForVeto = isPaper
    }

    private fun getVetoCooldownMs(): Long =
        if (_isPaperModeForVeto) EDGE_VETO_COOLDOWN_PAPER_MS else EDGE_VETO_COOLDOWN_LIVE_MS

    fun recordEdgeVeto(mint: String, reason: String, quality: String) {
        edgeVetoes[mint] = EdgeVeto(
            timestamp = System.currentTimeMillis(),
            reason = reason,
            quality = quality,
        )
        val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000L)
        edgeVetoes.entries.removeIf { it.value.timestamp < thirtyMinutesAgo }
    }

    fun hasActiveEdgeVeto(mint: String): EdgeVeto? {
        val veto = edgeVetoes[mint] ?: return null
        val elapsed = System.currentTimeMillis() - veto.timestamp
        return if (elapsed < getVetoCooldownMs()) veto else null
    }

    fun getVetoRemainingSeconds(mint: String): Int {
        val veto = edgeVetoes[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - veto.timestamp
        val remaining = getVetoCooldownMs() - elapsed
        return if (remaining > 0) (remaining / 1000).toInt() else 0
    }

    fun clearEdgeVeto(mint: String) {
        edgeVetoes.remove(mint)
    }

    // V5.9.54: called on mode switch so live-session vetoes don't block paper trades
    fun clearAllEdgeVetoes() {
        edgeVetoes.clear()
    }

    fun evaluate(
        ts: TokenState,
        candidate: CandidateDecision,
        config: BotConfig,
        proposedSizeSol: Double,
        brain: BotBrain? = null,
        tradingModeTag: ModeSpecificGates.TradingModeTag? = null,
    ): FinalDecision {
        val checks = mutableListOf<GateCheck>()
        var blockReason: String? = null
        var blockLevel: BlockLevel? = null
        val tags = mutableListOf<String>()

        val mode = if (config.paperMode) TradeMode.PAPER else TradeMode.LIVE

        val modeMultipliers = try {
            ModeSpecificGates.getMultipliers(tradingModeTag)
        } catch (e: Exception) {
            ErrorLogger.warn("FDG", "ModeSpecificGates error: ${e.message}")
            ModeSpecificGates.ModeMultipliers.DEFAULT
        }

        if (tradingModeTag != null && tradingModeTag != ModeSpecificGates.TradingModeTag.STANDARD) {
            tags.add("mode:${tradingModeTag.name}")
        }

        // V5.9.10: Symbolic Context — pull live 16-channel symbolic intelligence
        // Every AI module can tap into this. FDG uses it to bias sizing + log mood.
        val symCtx = try {
            SymbolicContext.refresh(ts.symbol, ts.mint)
            SymbolicContext
        } catch (_: Exception) { null }
        val symEntryAdj = try { symCtx?.getEntryAdjustment() ?: 1.0 } catch (_: Exception) { 1.0 }
        val symSizeAdj  = try { symCtx?.getSizeAdjustment()  ?: 1.0 } catch (_: Exception) { 1.0 }
        val symMood     = try { symCtx?.emotionalState ?: "NEUTRAL" } catch (_: Exception) { "NEUTRAL" }
        if (symCtx != null) {
            tags.add("sym:$symMood")
            tags.add("sym_edge:${"%.2f".format(symCtx.edgeStrength)}")
        }

        if (candidate.aiConfidence <= 0.0) {
            ErrorLogger.info("FDG", "🚫 ZERO_CONF_BLOCK: ${ts.symbol} | quality=${candidate.setupQuality} edge=${candidate.edgeQuality} conf=0% → SHADOW ONLY")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = 0.0,
                edge = EdgeVerdict.SKIP,
                blockReason = "LOW_CONFIDENCE_0%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("zero_confidence", "shadow_only"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "ZERO_CONFIDENCE_BLOCK",
                gateChecks = listOf(GateCheck("confidence", false, "conf=0% → SHADOW ONLY"))
            )
        }

        val earlyMemoryScore = try {
            val memMult = TokenWinMemory.getConfidenceMultiplier(
                ts.mint,
                ts.symbol,
                ts.name,
                ts.lastMcap,
                ts.lastLiquidityUsd,
                50.0,
                ts.phase,
                ts.source
            )
            when {
                memMult < 0.5 -> -15
                memMult < 0.7 -> -10
                memMult < 0.85 -> -5
                memMult > 1.3 -> 10
                memMult > 1.15 -> 5
                else -> 0
            }
        } catch (_: Exception) {
            0
        }

        val earlyAIDegraded = try {
            currentConditions.entryAiWinRate < 30.0 || currentConditions.exitAiAvgPnl < -10.0
        } catch (_: Exception) {
            false
        }

        val tradingModeStr = tradingModeTag?.name ?: ""

        if (tradingModeStr.uppercase().contains("COPY")) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | mode=$tradingModeStr | COPY_TRADE DISABLED → NO EXECUTION")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "COPY_TRADE_MODE_DISABLED",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("copy_trade_disabled", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "COPY_TRADE_HARD_DISABLED_AFTER_CATASTROPHIC_LOSSES",
                gateChecks = listOf(GateCheck("copy_trade_kill", false, "COPY_TRADE mode completely banned"))
            )
        }

        if (tradingModeStr.uppercase().contains("WHALE")) {
            ErrorLogger.warn("FDG", "⚠️ WHALE_FOLLOW: ${ts.symbol} | mode=$tradingModeStr | MICRO_SIZE_ONLY → Restricted after repeated losses")

            return FinalDecision(
                shouldTrade = mode == TradeMode.PAPER,
                mode = mode,
                approvalClass = if (mode == TradeMode.PAPER) ApprovalClass.PAPER_EXPLORATION else ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = when (candidate.edgeQuality) {
                    "A" -> EdgeVerdict.STRONG
                    "B" -> EdgeVerdict.WEAK
                    "C" -> EdgeVerdict.WEAK
                    else -> EdgeVerdict.SKIP
                },
                blockReason = if (mode == TradeMode.LIVE) "WHALE_FOLLOW_LIVE_DISABLED" else null,
                blockLevel = if (mode == TradeMode.LIVE) BlockLevel.HARD else null,
                sizeSol = config.smallBuySol * 0.5,
                tags = listOf("whale_follow_restricted", "micro_size_only"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "WHALE_FOLLOW restricted to PAPER + MICRO after repeated losses",
                gateChecks = listOf(GateCheck("whale_follow_restriction", mode == TradeMode.PAPER, "WHALE_FOLLOW restricted"))
            )
        }

        val learningProgress = FluidLearningAI.getLearningProgress()
        // V5.6.28f: UNIFIED LEARNING - No separate bootstrap for LIVE
        // LIVE inherits all PAPER learning. Both modes use same thresholds.
        val isBootstrapPhase = learningProgress < 0.40  // V5.9.165: aligned
        val isPaperMode = mode == TradeMode.PAPER
        // V5.6.28f: Allow confidence floor bypass for BOTH modes during learning
        val canBypassConfidenceFloors = isBootstrapPhase

        // ══════════════════════════════════════════════════════════════════════
        // V5.6: ML Engine Prediction Check
        // Uses on-device TensorFlow Lite to predict rug probability
        // Only blocks if ML has enough training data AND high rug confidence
        // ══════════════════════════════════════════════════════════════════════
        val mlPrediction = try {
            com.lifecyclebot.ml.OnDeviceMLEngine.predict(
                recentCandles = ts.history.takeLast(30),
                liquidityUsd = ts.lastLiquidityUsd,
                mcap = ts.lastMcap,
                holderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                holderGrowthPct = run {
                    // V5.9: estimate from token age vs holder count
                    val hc = (ts.history.lastOrNull()?.holderCount ?: 0).toDouble().coerceAtLeast(1.0)
                    val ageH = ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 3_600_000.0).coerceAtLeast(0.01)
                    ((hc / ageH) / 100.0).coerceIn(0.0, 100.0)
                },
                rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 50,
                mintRevoked = ts.safety.mintAuthorityDisabled ?: false,
                freezeRevoked = ts.safety.freezeAuthorityDisabled ?: false,
                topHolderPct = ts.safety.topHolderPct.takeIf { it >= 0 } ?: (ts.topHolderPct ?: 0.0),
                rsi = ts.meta.rsi,
                emaAlignment = ts.meta.emafanAlignment,
                tokenAgeMinutes = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60000L,
            )
        } catch (_: Exception) {
            null
        }
        
        // Only use ML veto if we have enough training data (confidence > 50%)
        if (mlPrediction != null && mlPrediction.confidence > 0.5) {
            // High rug probability = block the trade
            if (mlPrediction.rugProbability > 0.75) {
                ErrorLogger.warn("FDG", "🧠 ML_RUG_BLOCK: ${ts.symbol} | rug=${(mlPrediction.rugProbability * 100).toInt()}% | " +
                    "trajectory=${mlPrediction.trajectoryClass} | dataPoints=${mlPrediction.dataPoints}")
                
                return FinalDecision(
                    shouldTrade = false,
                    mode = mode,
                    approvalClass = ApprovalClass.BLOCKED,
                    quality = candidate.setupQuality,
                    confidence = candidate.aiConfidence,
                    edge = EdgeVerdict.SKIP,
                    blockReason = "ML_RUG_PROBABILITY_${(mlPrediction.rugProbability * 100).toInt()}%",
                    blockLevel = BlockLevel.HARD,
                    sizeSol = 0.0,
                    tags = listOf("ml_rug_block", "trajectory:${mlPrediction.trajectoryClass}"),
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "ML Engine predicts ${(mlPrediction.rugProbability * 100).toInt()}% rug probability",
                    gateChecks = listOf(GateCheck("ml_rug_check", false, "rug=${(mlPrediction.rugProbability * 100).toInt()}% > 75% threshold"))
                )
            }
            
            // ML entry confidence can boost or reduce overall confidence
            val mlConfidenceAdjust = when {
                mlPrediction.entryConfidence > 0.75 -> 1.15  // Boost 15%
                mlPrediction.entryConfidence > 0.6 -> 1.05   // Boost 5%
                mlPrediction.entryConfidence < 0.3 -> 0.85   // Reduce 15%
                mlPrediction.entryConfidence < 0.4 -> 0.95   // Reduce 5%
                else -> 1.0
            }
            
            if (mlConfidenceAdjust != 1.0) {
                tags.add("ml_adj:${((mlConfidenceAdjust - 1) * 100).toInt()}%")
            }
            
            // Log ML insights
            if (mlPrediction.trajectoryClass == "MOON") {
                tags.add("ml:MOON")
                ErrorLogger.info("FDG", "🧠 ML_INSIGHT: ${ts.symbol} | MOON trajectory | entry=${(mlPrediction.entryConfidence * 100).toInt()}%")
            } else if (mlPrediction.trajectoryClass == "DUMP") {
                tags.add("ml:DUMP")
                ErrorLogger.info("FDG", "🧠 ML_INSIGHT: ${ts.symbol} | DUMP trajectory | rug=${(mlPrediction.rugProbability * 100).toInt()}%")
            }
        }

        val isCGrade = candidate.setupQuality == "C" || candidate.setupQuality == "D"
        val rawConfidence = candidate.aiConfidence
        val confidence = if (canBypassConfidenceFloors) {
            FluidLearningAI.getAdjustedConfidence(rawConfidence, isPaperMode)
        } else {
            rawConfidence
        }

        val BOOTSTRAP_MIN_CONFIDENCE = 3.0
        if (confidence < BOOTSTRAP_MIN_CONFIDENCE) {
            ErrorLogger.debug("FDG", "🚫 BOOTSTRAP_FLOOR: ${ts.symbol} | conf=${confidence.toInt()}% < 3% | TOO_LOW_EVEN_FOR_LEARNING")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "BOOTSTRAP_MIN_CONFIDENCE_3%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("bootstrap_floor_3", "too_low_to_learn"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "BOOTSTRAP_MIN: conf=${confidence.toInt()}% < 3% (even learning has standards)",
                gateChecks = listOf(GateCheck("bootstrap_min_conf", false, "conf < 3% is garbage even for learning"))
            )
        }

        if (canBypassConfidenceFloors && confidence < 22.0) {
            ErrorLogger.info("FDG", "🎓 BOOTSTRAP_OVERRIDE: ${ts.symbol} | conf=${confidence.toInt()}% | Bypassing confidence floor for learning (progress=${(learningProgress * 100).toInt()}%)")
            tags.add("bootstrap_learning")
        } else if (confidence < 22.0) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% < 22% | CONFIDENCE_FLOOR_VIOLATED")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "CONFIDENCE_FLOOR_22%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("confidence_floor_22", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "HARD_CONFIDENCE_FLOOR: conf=${confidence.toInt()}% < 22%",
                gateChecks = listOf(GateCheck("confidence_floor_22", false, "conf < 22% is garbage"))
            )
        }

        if (confidence < 27.0 && isCGrade && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% + quality=${candidate.setupQuality} | C_GRADE_CONFIDENCE_FLOOR_VIOLATED")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "C_GRADE_CONFIDENCE_FLOOR_27%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("c_grade_confidence_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "C-GRADE + conf < 27% = REJECT",
                gateChecks = listOf(GateCheck("c_grade_conf_floor", false, "C-grade requires conf >= 27%"))
            )
        }

        // V5.8: In paper mode, bypass AI_DEGRADED confidence floor.
        // Degraded AI means we need MORE learning data, not less. Paper trades with low
        // confidence when AI is degraded are precisely the training signal needed to recover.
        // V5.6.28f: Apply same rule to LIVE - unified learning
        if (confidence < 32.0 && earlyAIDegraded && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% + AI_DEGRADED | DEGRADED_AI_CONFIDENCE_FLOOR_VIOLATED")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "AI_DEGRADED_CONFIDENCE_FLOOR_32%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("ai_degraded_confidence_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "AI_DEGRADED + conf < 32% = REJECT",
                gateChecks = listOf(GateCheck("ai_degraded_conf_floor", false, "Degraded AI requires conf >= 32%"))
            )
        }

        val toxicPatternFlags = mutableListOf<String>()
        if (isCGrade) toxicPatternFlags.add("quality_C")
        if (confidence < 35.0) toxicPatternFlags.add("conf<35")
        // V5.9.63: was memory<=-8 — combined with quality_C + AI_degraded
        // (which is the default state in paper for any fresh meme) this
        // formed a 3-flag HARD_KILL too easily. Tightened to <=-14 so
        // only genuinely burned tokens contribute this flag.
        if (earlyMemoryScore <= -14) toxicPatternFlags.add("memory<=-14")
        if (earlyAIDegraded) toxicPatternFlags.add("AI_degraded")

        // V5.9.63: require 4 of 4 flags to HARD_KILL (was 3 of 4).
        // With 3 you were killing every C-grade fresh launch with any
        // mildly-negative memory — choking volume exactly the user
        // complained about. At 4/4 it's genuinely toxic: C-grade AND
        // low confidence AND deeply negative memory AND AI degraded.
        if (toxicPatternFlags.size >= 4 && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL TOXIC PATTERN: ${ts.symbol} | flags=${toxicPatternFlags.joinToString(",")} | KRIS_RULE → REJECT")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "TOXIC_PATTERN_KRIS_RULE",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("toxic_pattern", "kris_rule", "hard_kill") + toxicPatternFlags,
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "TOXIC_PATTERN: ${toxicPatternFlags.joinToString(" + ")} → REJECT",
                gateChecks = listOf(
                    GateCheck(
                        "toxic_pattern",
                        false,
                        "Kris rule: 3+ toxic flags (${toxicPatternFlags.joinToString(",")}) = HARD REJECT"
                    )
                )
            )
        } else if (toxicPatternFlags.size >= 4 && canBypassConfidenceFloors) {
            ErrorLogger.info("FDG", "🎓 BOOTSTRAP_OVERRIDE: ${ts.symbol} | Bypassing toxic pattern check for learning (flags=${toxicPatternFlags.joinToString(",")})")
            tags.add("bootstrap_toxic_bypass")
        }

        val WATCHLIST_FLOOR = FluidLearningAI.getWatchlistFloor()
        val EXECUTION_FLOOR = FluidLearningAI.getExecutionFloor()

        if (ts.lastLiquidityUsd < WATCHLIST_FLOOR) {
            ErrorLogger.debug("FDG", "🚫 LIQ_FLOOR: ${ts.symbol} | liq=\$${ts.lastLiquidityUsd.toInt()} < \$${WATCHLIST_FLOOR.toInt()} | TOO_LOW_FOR_WATCHLIST")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "LIQUIDITY_BELOW_WATCHLIST_FLOOR",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("liq_below_watch_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "Liquidity \$${ts.lastLiquidityUsd.toInt()} < \$${WATCHLIST_FLOOR.toInt()} watchlist floor",
                gateChecks = listOf(GateCheck("liq_watch_floor", false, "liq < \$${WATCHLIST_FLOOR.toInt()} = not worth watching"))
            )
        }

        if (ts.lastLiquidityUsd < EXECUTION_FLOOR) {
            ErrorLogger.info("FDG", "👁️ LIQ_FLOOR: ${ts.symbol} | liq=\$${ts.lastLiquidityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} | WATCH_ONLY (no execution)")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = if (candidate.setupQuality in listOf("A+", "A", "B")) EdgeVerdict.WEAK else EdgeVerdict.SKIP,
                blockReason = "LIQUIDITY_BELOW_EXECUTION_FLOOR",
                blockLevel = BlockLevel.MODE,
                sizeSol = 0.0,
                tags = listOf("liq_below_exec_floor", "shadow_track", "watch_only"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "Liquidity \$${ts.lastLiquidityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} execution floor - WATCH ONLY",
                gateChecks = listOf(
                    GateCheck(
                        "liq_exec_floor",
                        false,
                        "liq \$${ts.lastLiquidityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} = shadow track only"
                    )
                )
            )
        }

        if (tradingModeStr.isNotBlank()) {
            val circuitBlockReason = ToxicModeCircuitBreaker.checkEntryAllowed(
                mode = tradingModeStr,
                source = ts.source,
                liquidityUsd = ts.lastLiquidityUsd,
                phase = ts.phase,
                memoryScore = earlyMemoryScore,
                isAIDegraded = earlyAIDegraded,
                confidence = confidence.toInt(),
                isPaperMode = config.paperMode
            )

            if (circuitBlockReason != null) {
                ErrorLogger.warn("FDG", "🚫 CIRCUIT_BREAKER: ${ts.symbol} | mode=$tradingModeStr | $circuitBlockReason")

                return FinalDecision(
                    shouldTrade = false,
                    mode = mode,
                    approvalClass = ApprovalClass.BLOCKED,
                    quality = candidate.setupQuality,
                    confidence = confidence,
                    edge = EdgeVerdict.SKIP,
                    blockReason = "CIRCUIT_BREAKER: $circuitBlockReason",
                    blockLevel = BlockLevel.HARD,
                    sizeSol = 0.0,
                    tags = listOf("circuit_breaker", "mode_blocked"),
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "ToxicModeCircuitBreaker: $circuitBlockReason",
                    gateChecks = listOf(GateCheck("circuit_breaker", false, circuitBlockReason))
                )
            }
        }

        val brainTrades = brain?.getTradeCount() ?: 0
        val sessionTrades = currentConditions.totalSessionTrades
        val fluidTrades = try {
            FluidLearning.getTradeCount()
        } catch (_: Exception) {
            0
        }

        val effectiveTradeCount = maxOf(brainTrades, fluidTrades)
        val rawExecutedCount = sessionTrades
        val winRate = brain?.getRecentWinRate() ?: 50.0
        val adjusted = getAdjustedThresholds(effectiveTradeCount, winRate)

        val countersMatch = brainTrades == fluidTrades
        val executionMismatch = rawExecutedCount > effectiveTradeCount + 2
        val isPhaseTransition = effectiveTradeCount == 0 || effectiveTradeCount == 10 || effectiveTradeCount == 30 || effectiveTradeCount == 51

        if (isPhaseTransition || !countersMatch || executionMismatch) {
            val counterDetail = "executed=$rawExecutedCount | classified=$brainTrades | fluid=$fluidTrades | learning_uses=$effectiveTradeCount"
            val mismatchNote = when {
                executionMismatch -> " ⚠️ EXECUTION_AHEAD (trades executing faster than learning)"
                !countersMatch -> " ⚠️ LEARNING_MISMATCH ($counterDetail)"
                else -> ""
            }
            ErrorLogger.info(
                "FDG",
                "📊 Learning phase: ${adjusted.learningPhase} | learning_trades=$effectiveTradeCount | progress=${(adjusted.progress * 100).toInt()}% | conf=${adjusted.confidenceBase.toInt()}%" +
                    (if (tradingModeTag != null) " | mode=${tradingModeTag.name}" else "") +
                    mismatchNote
            )
        }

        // V5.9.47: all three thresholds now route through ModeLeniency so
        // proven-edge live runs inherit paper leniency.
        val lenient = ModeLeniency.useLenientGates(config.paperMode)

        val rugcheckThreshold = if (lenient) {
            // V5.6.8 FIX: Paper mode MUST learn with rugcheck enabled!
            // Using threshold 0 means bot never learns which tokens are dangerous.
            // When switching to live, it has no idea what to avoid.
            // Use SAME threshold as live (or slightly lower for more learning data)
            val baseThreshold = (brain?.learnedRugcheckThreshold ?: 3).coerceIn(2, 10)
            (baseThreshold * modeMultipliers.rugcheckMultiplier * 0.8).toInt().coerceIn(2, 10)  // 20% looser for more data
        } else {
            val baseThreshold = (brain?.learnedRugcheckThreshold ?: 5).coerceIn(3, 10)
            (baseThreshold * modeMultipliers.rugcheckMultiplier).toInt().coerceIn(3, 15)
        }

        // V5.9.175 — paper floor lowered from 30 to 10 because the user
        // directive is "admit everything above D+" — 30% buy-pressure was
        // blocking fresh launches that often start at 20-28% before their
        // first wave of buyers arrives. The fluid size multiplier + FDG
        // confidence floor handle weak setups downstream, so this gate no
        // longer needs to be the hard cliff.
        val buyPressureThreshold = if (lenient) {
            (adjusted.buyPressureMin * modeMultipliers.entryScoreMultiplier * 0.8).coerceAtLeast(10.0)
        } else {
            adjusted.buyPressureMin * modeMultipliers.entryScoreMultiplier
        }

        val topHolderThreshold = if (lenient) {
            // V5.9.175 — paper cap raised from 55 → 75 so we don't block dev
            // wallets that are about to distribute. Live stays strict.
            (adjusted.topHolderMax / modeMultipliers.rugcheckMultiplier * 1.1).coerceAtMost(75.0)
        } else {
            adjusted.topHolderMax / modeMultipliers.rugcheckMultiplier
        }

        val currentAdjusted = adjusted

        if (ReentryGuard.isBlocked(ts.mint)) {
            val lockoutInfo = ReentryGuard.formatLockout(ts.mint)
            blockReason = "HARD_BLOCK_REENTRY_GUARD: $lockoutInfo"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("reentry_guard", false, lockoutInfo))
        } else {
            checks.add(GateCheck("reentry_guard", true, null))
        }

        checks.add(GateCheck("proposal_dedupe", true, "checked early in BotService"))

        if (ts.lastLiquidityUsd <= 0) {
            blockReason = "HARD_BLOCK_ZERO_LIQUIDITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("liquidity", false, "liq=${ts.lastLiquidityUsd}"))
        } else {
            checks.add(GateCheck("liquidity", true, null))
        }

        val rugcheckScore = ts.safety.rugcheckScore
        val rugcheckStatus = ts.safety.rugcheckStatus
        val rugcheckTimeoutPenalty = ts.safety.rugcheckTimeoutPenalty

        val rugcheckBlock = when {
            rugcheckStatus == "CONFIRMED" && rugcheckScore <= rugcheckThreshold -> true
            rugcheckStatus == "CONFIRMED" && rugcheckScore > rugcheckThreshold -> false
            rugcheckStatus == "TIMEOUT" && config.paperMode -> {
                tags.add("rugcheck_timeout")
                false
            }
            (rugcheckStatus == "TIMEOUT" || rugcheckStatus == "PENDING_REVIEW") && !config.paperMode -> {
                val hasStrongBuyers = ts.meta.pressScore >= 60.0
                val hasGoodLiquidity = ts.lastLiquidityUsd >= 8000.0
                val hasGoodVolume = (ts.history.lastOrNull()?.volumeH1 ?: 0.0) >= 2000.0
                val shouldBlock = !(hasStrongBuyers && hasGoodLiquidity && hasGoodVolume)

                if (!shouldBlock) {
                    ErrorLogger.info(
                        "FDG",
                        "Rugcheck $rugcheckStatus for ${ts.symbol}, allowing with STRICT safety fallback: buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()} vol=\$${(ts.history.lastOrNull()?.volumeH1 ?: 0.0).toInt()}"
                    )
                    tags.add("rugcheck_timeout_fallback")
                } else {
                    ErrorLogger.warn(
                        "FDG",
                        "Rugcheck $rugcheckStatus BLOCKED for ${ts.symbol}: weak fallback signals (buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()} - need 60%+ buy, \$8K+ liq, \$2K+ vol)"
                    )
                }
                shouldBlock
            }
            else -> {
                tags.add("rugcheck_unknown")
                rugcheckScore <= rugcheckThreshold
            }
        }

        if (blockReason == null && rugcheckBlock) {
            val blockReasonDetail = when (rugcheckStatus) {
                "TIMEOUT", "PENDING_REVIEW" -> "HARD_BLOCK_RUGCHECK_${rugcheckStatus}_WEAK_FALLBACK"
                else -> "HARD_BLOCK_RUGCHECK_$rugcheckScore"
            }
            blockReason = blockReasonDetail
            blockLevel = BlockLevel.HARD

            val checkReason = when (rugcheckStatus) {
                "TIMEOUT", "PENDING_REVIEW" ->
                    "status=$rugcheckStatus, weak fallback: buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()}"
                else ->
                    "score=$rugcheckScore <= $rugcheckThreshold"
            }
            checks.add(GateCheck("rugcheck", false, checkReason))
            tags.add("low_rugcheck")
        } else if (blockReason == null) {
            val passReason = when (rugcheckStatus) {
                "TIMEOUT" ->
                    "status=TIMEOUT (paper: penalty=$rugcheckTimeoutPenalty applied, allowed for learning)"
                "PENDING_REVIEW" ->
                    "status=PENDING_REVIEW, strong fallback (buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()})"
                "CONFIRMED" ->
                    "score=$rugcheckScore > $rugcheckThreshold"
                else -> null
            }
            checks.add(GateCheck("rugcheck", true, passReason))
        }

        if (blockReason == null && ts.meta.pressScore < buyPressureThreshold) {
            blockReason = "HARD_BLOCK_SELL_PRESSURE_${ts.meta.pressScore.toInt()}%"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("buy_pressure", false, "buy%=${ts.meta.pressScore.toInt()} < $buyPressureThreshold"))
            tags.add("sell_pressure")
        } else if (blockReason == null) {
            checks.add(GateCheck("buy_pressure", true, null))
        }

        if (blockReason == null && ts.safety.topHolderPct > topHolderThreshold) {
            blockReason = "HARD_BLOCK_TOP_HOLDER_${ts.safety.topHolderPct.toInt()}%"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("top_holder", false, "holder=${ts.safety.topHolderPct.toInt()}% > $topHolderThreshold"))
            tags.add("whale_control")
        } else if (blockReason == null) {
            checks.add(GateCheck("top_holder", true, null))
        }

        if (blockReason == null && ts.safety.hardBlockReasons.isNotEmpty()) {
            blockReason = "HARD_BLOCK_SAFETY_${ts.safety.hardBlockReasons.first().take(30)}"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("safety_block", false, ts.safety.hardBlockReasons.joinToString(", ")))
            tags.add("safety_blocked")
        } else if (blockReason == null) {
            checks.add(GateCheck("safety_block", true, null))
        }

        if (blockReason == null && !config.paperMode && ts.safety.freezeAuthorityDisabled == false) {
            blockReason = "HARD_BLOCK_FREEZE_AUTHORITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("freeze_auth", false, "freezeAuth=enabled (live mode)"))
            tags.add("freeze_auth")
        } else if (blockReason == null) {
            checks.add(GateCheck("freeze_auth", true, null))
        }

        if (blockReason == null && candidate.blockReason.isNotEmpty()) {
            blockReason = candidate.blockReason
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("candidate_block", false, candidate.blockReason))
        } else if (blockReason == null) {
            checks.add(GateCheck("candidate_block", true, null))
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // V5.6.9 FIX: RSI OVERBOUGHT HARD REJECTION
        // 
        // PROBLEM: During bootstrap phase, the bot was trading recklessly on tokens
        // with RSI 90-100 (extreme overbought territory). These almost always dump.
        // 
        // NEW BEHAVIOR:
        //   RSI > 90: HARD BLOCK in live mode, size reduction + penalty in paper
        //   RSI > 85: Significant penalty in both modes
        //   RSI > 80: Moderate penalty (already captured in EntryIntelligence)
        // 
        // This prevents chasing pumps at the very top of their run.
        // ═══════════════════════════════════════════════════════════════════════════
        val currentRsi = ts.meta.rsi
        if (blockReason == null && currentRsi > 90.0) {
            if (!config.paperMode) {
                // LIVE MODE: Hard block RSI > 90 (extreme overbought = almost certain dump)
                blockReason = "RSI_OVERBOUGHT_${currentRsi.toInt()}"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("rsi_overbought", false, "RSI=${currentRsi.toInt()} > 90 (extreme overbought, near-certain dump)"))
                tags.add("rsi_overbought_block")
                ErrorLogger.warn("FDG", "🚫 RSI HARD BLOCK: ${ts.symbol} | RSI=${currentRsi.toInt()} > 90 | EXTREME OVERBOUGHT → BLOCK")
            } else {
                // PAPER MODE: Allow but with severe penalty and tiny size for learning
                // This lets the bot learn that RSI > 90 entries are bad
                checks.add(GateCheck("rsi_overbought", true, "RSI=${currentRsi.toInt()} > 90 → PAPER LEARNING with penalty"))
                tags.add("rsi_overbought_learn")
                ErrorLogger.info("FDG", "🎓 RSI PAPER LEARN: ${ts.symbol} | RSI=${currentRsi.toInt()} > 90 | severe penalty for learning")
            }
        } else if (blockReason == null && currentRsi > 85.0) {
            // RSI 85-90: Heavy penalty in both modes
            checks.add(GateCheck("rsi_overbought", true, "RSI=${currentRsi.toInt()} > 85 → penalty applied"))
            tags.add("rsi_high_caution")
            ErrorLogger.debug("FDG", "⚠️ RSI HIGH: ${ts.symbol} | RSI=${currentRsi.toInt()} | applying penalty")
        } else if (blockReason == null) {
            checks.add(GateCheck("rsi_overbought", true, null))
        }

        var softPenaltyScore = 0
        var sizeMultiplier = 1.0
        var isProbeCandidate = false
        var behaviorPenalty = 0
        var behaviorSizeMultiplier = 1.0
        var behaviorProbe = false

        // V5.6.9 FIX: Apply RSI penalties to soft score and size
        val rsiPenalty = when {
            currentRsi > 90.0 && config.paperMode -> {
                sizeMultiplier *= 0.15  // Only 15% size for extreme overbought paper trades
                35  // Heavy penalty
            }
            currentRsi > 85.0 -> {
                sizeMultiplier *= 0.5  // 50% size for high RSI
                20  // Significant penalty
            }
            currentRsi > 80.0 -> {
                sizeMultiplier *= 0.7  // 70% size for overbought
                10  // Moderate penalty
            }
            else -> 0
        }
        if (rsiPenalty > 0) {
            softPenaltyScore += rsiPenalty
            tags.add("rsi_penalty_$rsiPenalty")
        }

        if (blockReason == null) {
            try {
                val volScore = ts.meta.volScore
                val volumeSignal = when {
                    volScore > 80 -> "SURGE"
                    volScore > 60 -> "INCREASING"
                    volScore > 40 -> "NORMAL"
                    volScore > 20 -> "DECREASING"
                    else -> "LOW"
                }

                val setupQuality = when {
                    candidate.entryScore >= 90 -> "A+"
                    candidate.entryScore >= 80 -> "A"
                    candidate.entryScore >= 50 -> "B"
                    else -> "C"
                }

                val behaviorBlock = BehaviorLearning.shouldHardBlock(
                    entryPhase = candidate.phase,
                    setupQuality = setupQuality,
                    tradingMode = tradingModeTag?.name ?: "STANDARD",
                    liquidityUsd = ts.lastLiquidityUsd,
                    volumeSignal = volumeSignal,
                )

                if (behaviorBlock != null) {
                    val is100PctLoss = behaviorBlock.contains("100%")
                    val sampleCountMatch = Regex("\\((\\d+) trades?\\)").find(behaviorBlock)
                    val sampleCount = sampleCountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val isReliable100PctLoss = is100PctLoss && sampleCount >= 5

                    if (config.paperMode) {
                        behaviorPenalty = 0
                        behaviorSizeMultiplier = 1.0
                        behaviorProbe = false
                        checks.add(
                            GateCheck(
                                "behavior_learning",
                                true,
                                "PAPER BYPASS (V5.2): $behaviorBlock (n=$sampleCount) → NO PENALTY, full size"
                            )
                        )
                        tags.add("behavior_paper_full_bypass")
                    } else if (isReliable100PctLoss && !isBootstrapPhase) {
                        blockReason = "BEHAVIOR_BLOCK_100PCT_LOSS"
                        blockLevel = BlockLevel.HARD
                        checks.add(GateCheck("behavior_learning", false, "$behaviorBlock (reliable: $sampleCount samples)"))
                        tags.add("behavior_100pct_loss_blocked")
                    } else if (is100PctLoss && isBootstrapPhase) {
                        behaviorPenalty = if (sampleCount >= 3) 15 else 8
                        behaviorSizeMultiplier = 0.3
                        behaviorProbe = true
                        checks.add(
                            GateCheck(
                                "behavior_learning",
                                true,
                                "BEHAVIOR 100% LOSS → PENALTY (bootstrap: -${behaviorPenalty}pts, n=$sampleCount)"
                            )
                        )
                        tags.add("behavior_penalized")
                    } else {
                        blockReason = behaviorBlock
                        blockLevel = BlockLevel.HARD
                        checks.add(GateCheck("behavior_learning", false, behaviorBlock))
                        tags.add("behavior_blocked")
                    }
                } else {
                    if (config.paperMode) {
                        checks.add(GateCheck("behavior_learning", true, "PAPER MODE: No behavior adjustments applied"))
                    } else {
                        val behaviorAdj = BehaviorLearning.getScoreAdjustment(
                            entryPhase = candidate.phase,
                            setupQuality = setupQuality,
                            tradingMode = tradingModeTag?.name ?: "STANDARD",
                            liquidityUsd = ts.lastLiquidityUsd,
                            volumeSignal = volumeSignal,
                        )

                        if (behaviorAdj != 0) {
                            checks.add(GateCheck("behavior_learning", true, "Score adj: ${if (behaviorAdj > 0) "+" else ""}$behaviorAdj"))
                            if (behaviorAdj < -20) tags.add("behavior_warning")
                            if (behaviorAdj > 15) tags.add("behavior_boost")
                        } else {
                            checks.add(GateCheck("behavior_learning", true, null))
                        }
                    }
                }
            } catch (e: Exception) {
                checks.add(GateCheck("behavior_learning", true, "error: ${e.message}"))
            }
        }

        var dangerZonePenalty = 0
        if (blockReason == null) {
            try {
                val isDanger = TimeOptimizationAI.isDangerZone()
                if (isDanger) {
                    if (isBootstrapPhase) {
                        // V5.6.9 FIX: Increased DANGER ZONE penalty during bootstrap
                        // Old value (5 pts) was too weak — bot was still making bad trades
                        // during historically losing time periods.
                        // 
                        // NEW: 15 pts penalty + 25% size multiplier (was 5 pts + 40% size)
                        // This makes danger zone entries much less attractive during bootstrap,
                        // forcing the bot to learn that these time periods are risky.
                        dangerZonePenalty = 15  // V5.6.9: Increased from 5 to 15
                        sizeMultiplier *= 0.25  // V5.6.9: Reduced from 0.4 to 0.25
                        softPenaltyScore += dangerZonePenalty
                        isProbeCandidate = true
                        checks.add(GateCheck("time_danger", true, "DANGER_ZONE → PENALTY (bootstrap: -${dangerZonePenalty}pts, size×0.25)"))
                        tags.add("time_danger_penalized")
                        tags.add("bootstrap_probe")
                        ErrorLogger.info("FDG", "⚠️ DANGER ZONE PENALTY: ${ts.symbol} | -${dangerZonePenalty}pts, size×0.25 (bootstrap learning)")
                    } else {
                        val shouldBypass = shouldBypassSoftBlock("DANGER_ZONE_TIME")
                        if (shouldBypass) {
                            checks.add(GateCheck("time_danger", true, "DANGER ZONE BYPASSED (adaptive: ${getAdaptiveFilterStatus()})"))
                            tags.add("time_danger_bypassed_adaptive")
                        } else {
                            blockReason = "DANGER_ZONE_TIME"
                            blockLevel = BlockLevel.MODE
                            checks.add(GateCheck("time_danger", false, "TimeAI DANGER ZONE"))
                            tags.add("time_danger_blocked")
                        }
                    }
                } else {
                    val timeAdj = TimeOptimizationAI.getEntryScoreAdjustment()
                    checks.add(GateCheck("time_danger", true, "Time adj: ${timeAdj.toInt()}"))
                }
            } catch (e: Exception) {
                checks.add(GateCheck("time_danger", true, "error: ${e.message}"))
            }
        }

        var memoryPenalty = 0
        if (blockReason == null) {
            try {
                val latestBuyPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
                val memoryMult = TokenWinMemory.getConfidenceMultiplier(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    name = ts.name,
                    mcap = ts.lastMcap,
                    liquidity = ts.lastLiquidityUsd,
                    buyPercent = latestBuyPct,
                    phase = ts.phase,
                    source = ts.source,
                )

                if (memoryMult <= 0.82) {
                    if (isBootstrapPhase) {
                        memoryPenalty = 10
                        sizeMultiplier *= 0.5
                        softPenaltyScore += memoryPenalty
                        isProbeCandidate = true
                        checks.add(GateCheck("memory_negative", true, "MEMORY_NEG → PENALTY (bootstrap: -${memoryPenalty}pts, size×0.5, mult=$memoryMult)"))
                        tags.add("memory_penalized")
                    } else {
                        val shouldBypass = shouldBypassSoftBlock("MEMORY_NEGATIVE_BLOCK")
                        if (shouldBypass) {
                            checks.add(GateCheck("memory_negative", true, "MEMORY BLOCK BYPASSED (adaptive: ${getAdaptiveFilterStatus()})"))
                            tags.add("memory_bypassed_adaptive")
                        } else {
                            blockReason = "MEMORY_NEGATIVE_BLOCK"
                            blockLevel = BlockLevel.MODE
                            checks.add(GateCheck("memory_negative", false, "Memory strongly negative (mult=$memoryMult)"))
                            tags.add("memory_blocked")
                        }
                    }
                } else if (memoryMult < 0.90) {
                    memoryPenalty = 5
                    softPenaltyScore += memoryPenalty
                    checks.add(GateCheck("memory_negative", true, "Memory warning (mult=$memoryMult, -${memoryPenalty}pts)"))
                    tags.add("memory_warning")
                } else {
                    checks.add(GateCheck("memory_negative", true, null))
                }
            } catch (e: Exception) {
                checks.add(GateCheck("memory_negative", true, "error: ${e.message}"))
            }
        }

        if (blockReason == null) {
            val inCooldown = isInDistributionCooldown(ts.mint)
            val cooldownMinutes = if (inCooldown) getRemainingCooldownMinutes(ts.mint) else 0

            if (config.paperMode) {
                if (inCooldown) {
                    checks.add(GateCheck("distribution", true, "PAPER: cooldown bypassed for learning"))
                    tags.add("distribution_cooldown_bypassed")
                }
            } else {
                val bypassCooldownForLive = ts.meta.pressScore >= 65.0
                if (inCooldown && !bypassCooldownForLive) {
                    blockReason = "DISTRIBUTION_COOLDOWN_${cooldownMinutes}min"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("distribution", false, "Recently exited distribution, cooldown=${cooldownMinutes}min remaining"))
                    tags.add("distribution_cooldown")
                } else if (inCooldown && bypassCooldownForLive) {
                    checks.add(GateCheck("distribution", true, "LIVE: cooldown bypass (buy%=${ts.meta.pressScore.toInt()}%)"))
                    tags.add("distribution_cooldown_bypassed")
                }
            }

            if (blockReason == null && !config.paperMode) {
                val isDistributionPhase = candidate.edgePhase.uppercase() == "DISTRIBUTION"
                val hasDistributionTag = ts.phase.lowercase().contains("distribution")

                val distSignal = if (ts.history.size >= 5) {
                    DistributionDetector.detect(
                        mint = ts.mint,
                        ts = ts,
                        currentExitScore = candidate.exitScore,
                        history = ts.history
                    )
                } else null

                val distLenient = ModeLeniency.useLenientGates(config.paperMode)
                val distThreshold = if (distLenient) 70 else 50
                val isDistributorConfident = distSignal?.isDistributing == true && distSignal.confidence >= distThreshold
                val bypassForPaperLearning = distLenient &&
                    ts.meta.pressScore >= 55.0 &&
                    !isDistributionPhase

                if ((isDistributionPhase || hasDistributionTag || isDistributorConfident) && !bypassForPaperLearning) {
                    val reason = when {
                        isDistributionPhase -> "edgePhase=DISTRIBUTION"
                        hasDistributionTag -> "tsPhase=${ts.phase}"
                        isDistributorConfident -> "detector=${distSignal?.confidence}% (${distSignal?.details})"
                        else -> "unknown"
                    }
                    blockReason = "HARD_BLOCK_DISTRIBUTION"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("distribution", false, reason))
                    tags.add("distribution_block")
                } else {
                    val detectorInfo = if (distSignal != null) " detector=${distSignal.confidence}%" else ""
                    checks.add(GateCheck("distribution", true, "edgePhase=${candidate.edgePhase}$detectorInfo"))
                }
            }
        }

        if (blockReason == null && earlySnipeEnabled && !config.paperMode) {
            val tokenAgeMinutes = if (ts.history.isNotEmpty()) {
                val firstCandleTime = ts.history.minOfOrNull { it.ts } ?: System.currentTimeMillis()
                (System.currentTimeMillis() - firstCandleTime) / 60_000.0
            } else 0.0

            val initialScore = candidate.entryScore
            val liquidity = ts.lastLiquidityUsd
            val buyPressure = ts.meta.pressScore

            val isYoungToken = tokenAgeMinutes <= earlySnipeMaxAgeMinutes
            val hasHighScore = initialScore >= earlySnipeMinScore
            val hasMinLiquidity = liquidity >= earlySnipeMinLiquidity
            val hasPositiveBuyPressure = buyPressure >= 50.0
            val qualifiesForSnipe = isYoungToken && hasHighScore && hasMinLiquidity && hasPositiveBuyPressure

            if (qualifiesForSnipe) {
                checks.add(
                    GateCheck(
                        "early_snipe",
                        true,
                        "SNIPE: age=${tokenAgeMinutes.toInt()}min score=${initialScore.toInt()} liq=\$${liquidity.toInt()} buy%=${buyPressure.toInt()}"
                    )
                )
                tags.add("early_snipe")

                ErrorLogger.info(
                    "FDG",
                    "🎯 EARLY_SNIPE: ${ts.symbol} | age=${tokenAgeMinutes.toInt()}min score=${initialScore.toInt()} liq=\$${liquidity.toInt()} buy%=${buyPressure.toInt()}% → FAST APPROVE"
                )

                return FinalDecision(
                    shouldTrade = true,
                    mode = mode,
                    approvalClass = ApprovalClass.LIVE,
                    quality = "SNIPE",
                    confidence = 50.0,
                    edge = EdgeVerdict.STRONG,
                    blockReason = null,
                    blockLevel = null,
                    sizeSol = (proposedSizeSol * 0.5).coerceAtLeast(0.003),
                    tags = tags + "fast_track",
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "EARLY_SNIPE: age=${tokenAgeMinutes.toInt()}min score=${initialScore.toInt()} liq=\$${liquidity.toInt()}",
                    gateChecks = checks
                )
            } else if (isYoungToken && initialScore >= 50.0) {
                val reasons = mutableListOf<String>()
                if (!hasHighScore) reasons.add("score=${initialScore.toInt()}<$earlySnipeMinScore")
                if (!hasMinLiquidity) reasons.add("liq=\$${liquidity.toInt()}<\$${earlySnipeMinLiquidity.toInt()}")
                if (!hasPositiveBuyPressure) reasons.add("buy%=${buyPressure.toInt()}<50")
                checks.add(GateCheck("early_snipe", false, "SNIPE_MISS: age=${tokenAgeMinutes.toInt()}min ${reasons.joinToString(" ")}"))
            }
        }

        if (blockReason == null) {
            val activeVeto = hasActiveEdgeVeto(ts.mint)
            if (activeVeto != null) {
                val remainingSec = getVetoRemainingSeconds(ts.mint)

                if (config.paperMode) {
                    checks.add(GateCheck("edge_veto_sticky", true, "PAPER: veto bypassed for learning (original: ${activeVeto.reason})"))
                    tags.add("edge_veto_bypassed")
                } else {
                    val canBypassInLive = ts.meta.pressScore >= 60.0
                    if (canBypassInLive) {
                        checks.add(GateCheck("edge_veto_sticky", true, "LIVE: veto bypass (buy%=${ts.meta.pressScore.toInt()})"))
                        tags.add("edge_veto_bypassed")
                    } else {
                        blockReason = "EDGE_VETO_ACTIVE"
                        blockLevel = BlockLevel.EDGE
                        checks.add(
                            GateCheck(
                                "edge_veto_sticky",
                                false,
                                "Vetoed ${remainingSec}s ago: ${activeVeto.reason} (quality=${activeVeto.quality})"
                            )
                        )
                        tags.add("edge_veto_sticky")
                    }
                }
            } else {
                checks.add(GateCheck("edge_veto_sticky", true, "No active veto"))
            }
        }

        if (config.paperMode && blockReason == null) {
            checks.add(GateCheck("paper_quality", true, "PAPER: quality filter skipped for max learning"))
        }

        if (blockReason == null && candidate.phase.lowercase().contains("unknown")) {
            if (config.paperMode) {
                checks.add(GateCheck("phase_filter", true, "PAPER: unknown phase allowed for learning"))
                tags.add("phase_unknown_allowed")
            } else {
                val minScore = lerp(20.0, 45.0, currentAdjusted.progress)
                val minBuyPressure = lerp(35.0, 52.0, currentAdjusted.progress)
                val isHighScore = candidate.entryScore >= minScore
                val isHighBuyPressure = ts.meta.pressScore >= minBuyPressure

                if (!isHighScore && !isHighBuyPressure) {
                    blockReason = "UNKNOWN_PHASE_LOW_CONVICTION"
                    blockLevel = BlockLevel.CONFIDENCE
                    checks.add(
                        GateCheck(
                            "phase_filter",
                            false,
                            "phase=${candidate.phase} score=${candidate.entryScore.toInt()}<${minScore.toInt()} AND buy%=${ts.meta.pressScore.toInt()}<${minBuyPressure.toInt()} [phase:${currentAdjusted.learningPhase}]"
                        )
                    )
                    tags.add("phase_unknown_weak")
                } else {
                    checks.add(
                        GateCheck(
                            "phase_filter",
                            true,
                            "unknown phase OK (score=${candidate.entryScore.toInt()} OR buy%=${ts.meta.pressScore.toInt()}) [${currentAdjusted.learningPhase}]"
                        )
                    )
                }
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("phase_filter", true, "phase=${candidate.phase}"))
        }

        val edgeVerdict = when (candidate.edgeQuality.uppercase()) {
            "STRONG", "A", "A+" -> EdgeVerdict.STRONG
            "WEAK", "B", "OK" -> EdgeVerdict.WEAK
            else -> EdgeVerdict.SKIP
        }

        if (blockReason == null && edgeVerdict == EdgeVerdict.SKIP) {
            if (config.paperMode) {
                val edgePhaseStr = candidate.edgeQuality.uppercase()
                val isDistribution = edgePhaseStr.contains("DIST") || edgePhaseStr.contains("SKIP")

                if (isDistribution && !isBootstrapPhase) {
                    blockReason = "EDGE_DISTRIBUTION_PAPER"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, "PAPER: DISTRIBUTION detected (edge=${candidate.edgeQuality}) - not learning from dumps"))
                    tags.add("edge_distribution_blocked")
                } else if (isDistribution && isBootstrapPhase) {
                    softPenaltyScore += 15
                    sizeMultiplier *= 0.25
                    isProbeCandidate = true
                    checks.add(GateCheck("edge", true, "BOOTSTRAP PROBE: DISTRIBUTION (edge=${candidate.edgeQuality}) → -15pts, size×0.25"))
                    tags.add("edge_distribution_probe")
                } else {
                    softPenaltyScore += 5
                    checks.add(GateCheck("edge", true, "PAPER: edge veto soft-bypassed (edge=${candidate.edgeQuality}) → -5pts"))
                    tags.add("edge_veto_softened_paper")
                }
            } else {
                val liveMinBuyPressure = currentAdjusted.edgeMinBuyPressure
                val liveMinLiquidity = currentAdjusted.edgeMinLiquidity
                val liveMinEntryScore = currentAdjusted.edgeMinScore

                val hasStrongBuyers = ts.meta.pressScore >= liveMinBuyPressure
                val hasGoodLiquidity = ts.lastLiquidityUsd >= liveMinLiquidity
                val hasDecentScore = candidate.entryScore >= liveMinEntryScore

                if (hasStrongBuyers || hasGoodLiquidity || hasDecentScore) {
                    val reason = when {
                        hasStrongBuyers -> "buy%=${ts.meta.pressScore.toInt()}>=${liveMinBuyPressure.toInt()}"
                        hasDecentScore -> "score=${candidate.entryScore.toInt()}>=${liveMinEntryScore.toInt()}"
                        else -> "liq=$${ts.lastLiquidityUsd.toInt()}>=${liveMinLiquidity.toInt()}"
                    }
                    checks.add(GateCheck("edge", true, "LIVE: edge override ($reason) [${currentAdjusted.learningPhase}]"))
                    tags.add("live_edge_override")
                } else {
                    val missingReasons = mutableListOf<String>()
                    if (!hasStrongBuyers) missingReasons.add("buy%=${ts.meta.pressScore.toInt()}<${liveMinBuyPressure.toInt()}")
                    if (!hasGoodLiquidity) missingReasons.add("liq=$${ts.lastLiquidityUsd.toInt()}<${liveMinLiquidity.toInt()}")
                    if (!hasDecentScore) missingReasons.add("score=${candidate.entryScore.toInt()}<${liveMinEntryScore.toInt()}")

                    blockReason = "EDGE_VETO_${candidate.edgeQuality}"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, "edge=${candidate.edgeQuality} | no override: ${missingReasons.joinToString(", ")} [${currentAdjusted.learningPhase}]"))
                    tags.add("edge_skip")
                }
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("edge", true, null))
        }

        var narrativeAdjustment = 0
        if (blockReason == null && config.groqApiKey.isNotBlank()) {
            try {
                val narrativeResult = NarrativeDetector.analyze(
                    symbol = ts.symbol,
                    name = ts.name,
                    mintAddress = ts.mint,
                    description = "",
                    socialMentions = emptyList(),
                    groqApiKey = config.groqApiKey,
                )

                narrativeAdjustment = if (config.paperMode) {
                    if (narrativeResult.riskLevel == "HIGH" || narrativeResult.riskLevel == "CRITICAL") -10 else 0
                } else {
                    (narrativeResult.confidenceAdjustment / 4).coerceIn(-6, 4)
                }

                val isHighRiskContent = narrativeResult.riskLevel == "HIGH" && (
                    narrativeResult.reasoning.contains("impersonate", ignoreCase = true) ||
                        narrativeResult.reasoning.contains("offensive", ignoreCase = true) ||
                        narrativeResult.reasoning.contains("hate", ignoreCase = true) ||
                        narrativeResult.reasoning.contains("racist", ignoreCase = true) ||
                        narrativeResult.reasoning.contains("historical", ignoreCase = true)
                    )

                if (narrativeResult.shouldBlock && !config.paperMode) {
                    val isObviousScam = narrativeResult.riskLevel == "CRITICAL" &&
                        (narrativeResult.blockReason.contains("honey", ignoreCase = true) ||
                            narrativeResult.blockReason.contains("rug confirmed", ignoreCase = true))

                    if (isObviousScam) {
                        blockReason = "NARRATIVE_SCAM: ${narrativeResult.blockReason.take(50)}"
                        blockLevel = BlockLevel.HARD
                        checks.add(GateCheck("narrative", false, "CRITICAL SCAM: ${narrativeResult.scamIndicators.take(3).joinToString(", ")}"))
                        tags.add("narrative_scam")
                    } else {
                        checks.add(GateCheck("narrative", true, "⚠️ risk=${narrativeResult.riskLevel} (proceeding anyway for learning)"))
                        tags.add("narrative_warning")
                    }
                } else if (isHighRiskContent && config.paperMode) {
                    blockReason = "NARRATIVE_HIGH_RISK: ${narrativeResult.reasoning.take(50)}"
                    blockLevel = BlockLevel.MODE
                    checks.add(GateCheck("narrative", false, "HIGH RISK content blocked: ${narrativeResult.reasoning.take(80)}"))
                    tags.add("narrative_high_risk_blocked")
                } else if (narrativeResult.riskLevel == "CRITICAL" || narrativeResult.riskLevel == "HIGH") {
                    checks.add(GateCheck("narrative", true, "risk=${narrativeResult.riskLevel} adj=$narrativeAdjustment | ${narrativeResult.reasoning.take(60)}"))
                    tags.add("narrative_${narrativeResult.riskLevel.lowercase()}")
                } else {
                    checks.add(GateCheck("narrative", true, "risk=${narrativeResult.riskLevel} adj=$narrativeAdjustment"))
                }
            } catch (_: Exception) {
                checks.add(GateCheck("narrative", true, "skipped (error)"))
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("narrative", true, "skipped (no key)"))
        }

        var geminiRiskScore = 50.0
        var geminiRecommendation = "WATCH"
        if (blockReason == null && !config.paperMode && config.geminiEnabled) {
            try {
                val quickCheck = GeminiCopilot.quickScamCheck(ts.symbol, ts.name)
                if (quickCheck == true) {
                    blockReason = "GEMINI_QUICK_SCAM: Pattern detected in ${ts.symbol}"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("gemini_quick", false, "Quick scam pattern detected"))
                    tags.add("gemini_scam")
                } else {
                    val analysis = GeminiCopilot.analyzeNarrative(
                        symbol = ts.symbol,
                        name = ts.name,
                        description = "",
                        socialMentions = emptyList(),
                    )

                    if (analysis != null) {
                        geminiRiskScore = 100.0 - analysis.scamConfidence
                        geminiRecommendation = analysis.recommendation

                        if (analysis.isScam && analysis.scamConfidence >= 80) {
                            blockReason = "GEMINI_SCAM: ${analysis.scamType} (${analysis.scamConfidence.toInt()}% conf)"
                            blockLevel = BlockLevel.HARD
                            checks.add(GateCheck("gemini_narrative", false, "SCAM: ${analysis.reasoning.take(60)}"))
                            tags.add("gemini_blocked")
                        } else if (analysis.recommendation == "AVOID" && analysis.scamConfidence >= 60) {
                            narrativeAdjustment -= 5
                            checks.add(GateCheck("gemini_narrative", true, "⚠️ AVOID rec | scam=${analysis.scamConfidence.toInt()}% | ${analysis.narrativeType}"))
                            tags.add("gemini_warning")
                        } else if (analysis.recommendation == "BUY" && analysis.viralPotential >= 70) {
                            narrativeAdjustment += 3
                            checks.add(GateCheck("gemini_narrative", true, "✨ viral=${analysis.viralPotential.toInt()}% | ${analysis.narrativeType} | ${analysis.greenFlags.take(2).joinToString(", ")}"))
                            tags.add("gemini_bullish")
                        } else {
                            checks.add(GateCheck("gemini_narrative", true, "${analysis.recommendation} | viral=${analysis.viralPotential.toInt()}% | ${analysis.narrativeType}"))
                        }
                    } else {
                        checks.add(GateCheck("gemini_narrative", true, "skipped (API timeout)"))
                    }
                }
            } catch (e: Exception) {
                checks.add(GateCheck("gemini_narrative", true, "skipped (error: ${e.message?.take(30)})"))
            }
        } else if (config.paperMode) {
            checks.add(GateCheck("gemini_narrative", true, "skipped (paper mode)"))
        }

        var orthogonalBonus = 0
        if (blockReason == null) {
            try {
                val unifiedNarrative = try {
                    UnifiedNarrativeAI.analyze(ts.symbol, ts.name, "")
                } catch (_: Exception) {
                    null
                }

                val geminiStatus = GeminiCopilot.getRateLimitStatus()
                if (geminiStatus != "OK" && unifiedNarrative?.source == "default") {
                    ErrorLogger.info("FDG", "⚠️ AI DEGRADED: Gemini $geminiStatus - using neutral narrative for ${ts.symbol}")
                }

                val orthogonalAssessment = OrthogonalSignals.collectSignals(
                    ts = ts,
                    momentumScore = candidate.aiConfidence,
                    liquidityScore = if (ts.lastLiquidityUsd > 5000) 70.0 else if (ts.lastLiquidityUsd > 1000) 50.0 else 30.0,
                    whaleSignal = null,
                    timeScore = null,
                    narrativeScore = unifiedNarrative?.score ?: (50.0 + narrativeAdjustment * 5).coerceIn(0.0, 100.0),
                    patternMatchScore = null,
                    marketRegimeScore = null,
                    topHolderPcts = emptyList(),
                    tokenReturns = ts.history.takeLast(10).zipWithNext { a, b ->
                        if (a.priceUsd > 0) ((b.priceUsd - a.priceUsd) / a.priceUsd) * 100 else 0.0
                    },
                    marketReturns = emptyList(),
                )

                val compositeScore = orthogonalAssessment.compositeScore
                val agreementRatio = orthogonalAssessment.agreementRatio

                orthogonalBonus = when {
                    agreementRatio >= 0.8 && compositeScore > 20 -> 5
                    agreementRatio >= 0.8 && compositeScore < -20 -> -5
                    agreementRatio < 0.5 -> -2
                    else -> 0
                }

                val presentSignals = orthogonalAssessment.signals.size
                checks.add(
                    GateCheck(
                        "orthogonal",
                        true,
                        "score=${compositeScore.toInt()} agree=${(agreementRatio * 100).toInt()}% signals=$presentSignals/8 bonus=$orthogonalBonus"
                    )
                )

                if (orthogonalBonus != 0) tags.add("orthogonal:$orthogonalBonus")
                if (agreementRatio >= 0.8) tags.add("signal_consensus")
            } catch (e: Exception) {
                checks.add(GateCheck("orthogonal", true, "skipped (error: ${e.message?.take(30)})"))
            }
        }

        // V5.9.47: confidence threshold + bootstrap probe both route through
        // ModeLeniency so proven-edge live runs get the same leniency.
        val fdgLenient = ModeLeniency.useLenientGates(config.paperMode)
        val confidenceThreshold = getAdaptiveConfidence(fdgLenient, ts)
        val isBootstrap = currentConditions.totalSessionTrades < 30
        val bootstrapTag = if (isBootstrap) " [BOOTSTRAP]" else ""
        val adjustedConfidence = (confidence + narrativeAdjustment + orthogonalBonus).coerceIn(0.0, 100.0)
        val narrativeTag = if (narrativeAdjustment != 0) " [NAR:$narrativeAdjustment]" else ""
        val orthoTag = if (orthogonalBonus != 0) " [ORTHO:$orthogonalBonus]" else ""

        var confidenceProbe = false
        var confidenceProbeSizeMultiplier = 1.0

        if (blockReason == null && adjustedConfidence < confidenceThreshold) {
            val hasPositiveMemory = try {
                val memMult = TokenWinMemory.getConfidenceMultiplier(
                    ts.mint,
                    ts.symbol,
                    ts.name,
                    ts.lastMcap,
                    ts.lastLiquidityUsd,
                    50.0,
                    ts.phase,
                    ts.source
                )
                memMult >= 1.0
            } catch (_: Exception) {
                false
            }

            val isRepeatWinner = try {
                TokenWinMemory.isKnownWinner(ts.mint)
            } catch (_: Exception) {
                false
            }
            val hasNoHardBlocks = blockReason == null
            val hasMinLiquidity = ts.lastLiquidityUsd >= 3000.0

            if (isBootstrap && fdgLenient && hasNoHardBlocks && hasMinLiquidity && (isRepeatWinner || hasPositiveMemory)) {
                confidenceProbe = true
                isProbeCandidate = true

                val confidenceGap = confidenceThreshold - adjustedConfidence
                confidenceProbeSizeMultiplier = when {
                    isRepeatWinner -> 0.4
                    confidenceGap < 10 -> 0.35
                    else -> 0.25
                }
                sizeMultiplier *= confidenceProbeSizeMultiplier

                val probeReason = if (isRepeatWinner) "REPEAT_WINNER" else "POSITIVE_MEMORY"
                checks.add(
                    GateCheck(
                        "confidence",
                        true,
                        "BOOTSTRAP PROBE: conf=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}% BUT $probeReason → size×${confidenceProbeSizeMultiplier.format(2)}"
                    )
                )
                tags.add("bootstrap_confidence_probe")
                tags.add("probe_reason:$probeReason")

                ErrorLogger.info("FDG", "🔬 BOOTSTRAP PROBE: ${ts.symbol} | conf=${adjustedConfidence.toInt()}% | $probeReason | size×${confidenceProbeSizeMultiplier.format(2)}")
            } else {
                blockReason = "LOW_CONFIDENCE_${adjustedConfidence.toInt()}%$bootstrapTag$narrativeTag$orthoTag"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(
                    GateCheck(
                        "confidence",
                        false,
                        "conf=${confidence.toInt()}%+nar=$narrativeAdjustment+ortho=$orthogonalBonus=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}%$bootstrapTag (adaptive)"
                    )
                )
                tags.add("low_confidence")
                tags.add("adaptive_conf:${confidenceThreshold.toInt()}")
                if (isBootstrap) tags.add("bootstrap_phase")
            }
        } else if (blockReason == null) {
            checks.add(
                GateCheck(
                    "confidence",
                    true,
                    "conf=${confidence.toInt()}%+nar=$narrativeAdjustment+ortho=$orthogonalBonus=${adjustedConfidence.toInt()}% >= ${confidenceThreshold.toInt()}%$bootstrapTag (adaptive)"
                )
            )
            if (isBootstrap) tags.add("bootstrap_phase")
        }

        if (blockReason == null && !config.paperMode) {
            if (!config.autoTrade) {
                blockReason = "LIVE_AUTO_TRADE_DISABLED"
                blockLevel = BlockLevel.MODE
                checks.add(GateCheck("auto_trade", false, "autoTrade=false"))
            }

            if (blockReason == null && candidate.setupQuality !in listOf("A+", "A", "B", "C")) {
                blockReason = "LIVE_QUALITY_TOO_LOW"
                blockLevel = BlockLevel.MODE
                checks.add(GateCheck("live_quality", false, "quality=${candidate.setupQuality} (live requires C+)"))
            }
        }

        if (blockReason == null) {
            checks.add(GateCheck("mode_rules", true, null))
        }

        if (blockReason == null) {
            val liqSignal = LiquidityDepthAI.getSignal(ts.mint, ts.symbol, isOpenPosition = false)
            val isLearningPhase = currentAdjusted.learningPhase != LearningPhase.MATURE
            val isSevereCollapse = liqSignal.reason?.contains("-30%") == true ||
                liqSignal.reason?.contains("-40%") == true ||
                liqSignal.reason?.contains("-50%") == true

            if (liqSignal.shouldBlock && !config.paperMode && (!isLearningPhase || isSevereCollapse)) {
                blockReason = liqSignal.blockReason ?: "LIQUIDITY_COLLAPSE"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("liquidity_ai", false, "COLLAPSE: ${liqSignal.reason}"))
                tags.add("liquidity_collapse")
            } else if (liqSignal.shouldBlock && !config.paperMode && isLearningPhase) {
                checks.add(GateCheck("liquidity_ai", true, "LEARNING: collapse warning (${liqSignal.reason}) - allowed for learning [${currentAdjusted.learningPhase}]"))
                tags.add("liquidity_collapse_learning")
            } else if (liqSignal.shouldBlock && config.paperMode) {
                checks.add(GateCheck("liquidity_ai", true, "PAPER: collapse warning (${liqSignal.reason}) - allowed for learning"))
                tags.add("liquidity_collapse_paper")
            } else {
                val depthLabel = liqSignal.depthQuality.name.lowercase()
                checks.add(GateCheck("liquidity_ai", true, "trend=${liqSignal.trend.name.lowercase()} depth=$depthLabel adj=${liqSignal.entryAdjustment.toInt()}"))

                when (liqSignal.signal) {
                    LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE -> tags.add("liquidity_spike")
                    LiquidityDepthAI.SignalType.LIQUIDITY_GROWING -> tags.add("liquidity_growing")
                    LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING -> tags.add("liquidity_draining")
                    else -> {}
                }
            }
        }

        var evResult: EVCalculator.EVResult? = null
        val useEvGating = currentAdjusted.evGatingEnabled && !config.paperMode

        if (blockReason == null && useEvGating) {
            val marketRegimeStr = when {
                currentConditions.recentWinRate > 60 -> "BULL"
                currentConditions.recentWinRate < 40 -> "BEAR"
                else -> "NEUTRAL"
            }
            val historicalWinRateValue = currentConditions.recentWinRate / 100.0

            evResult = EVCalculator.calculate(
                ts = ts,
                entryScore = candidate.entryScore,
                quality = candidate.finalQuality,
                marketRegime = marketRegimeStr,
                historicalWinRate = historicalWinRateValue
            )

            checks.add(
                GateCheck(
                    "ev_analysis",
                    evResult.isPositiveEV,
                    "EV=${String.format("%+.1f", evResult.expectedPnlPct)}% Win=${String.format("%.0f", evResult.winProbability * 100)}% Rug=${String.format("%.0f", evResult.rugProbability * 100)}% Kelly=${String.format("%.1f", evResult.kellyFraction * 100)}%"
                )
            )

            if (evResult.expectedValue < minExpectedValue) {
                blockReason = "NEGATIVE_EV_${String.format("%.0f", evResult.expectedPnlPct)}%"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("ev_threshold", false, "EV ${String.format("%.2f", evResult.expectedValue)} < min ${String.format("%.2f", minExpectedValue)}"))
                tags.add("blocked_negative_ev")
            } else if (evResult.rugProbability > currentAdjusted.maxRugProbability) {
                blockReason = "HIGH_RUG_PROB_${String.format("%.0f", evResult.rugProbability * 100)}%"
                blockLevel = BlockLevel.HARD
                checks.add(
                    GateCheck(
                        "rug_probability",
                        false,
                        "Rug prob ${String.format("%.0f", evResult.rugProbability * 100)}% > max ${String.format("%.0f", currentAdjusted.maxRugProbability * 100)}% [${currentAdjusted.learningPhase}]"
                    )
                )
                tags.add("blocked_high_rug_prob")
            } else {
                tags.add("positive_ev")
                if (evResult.expectedPnlPct > 20) tags.add("high_ev")
                if (evResult.kellyFraction > 0.05) tags.add("kelly_favorable")
            }

            ErrorLogger.info("EV", "📊 ${ts.symbol}: ${evResult.summary()}")
        }

        var finalSize = proposedSizeSol

        val winMemoryMultiplier = try {
            val latestBuyPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
            TokenWinMemory.getConfidenceMultiplier(
                mint = ts.mint,
                symbol = ts.symbol,
                name = ts.name,
                mcap = ts.lastMcap,
                liquidity = ts.lastLiquidityUsd,
                buyPercent = latestBuyPct,
                phase = ts.phase,
                source = ts.source,
            )
        } catch (_: Exception) {
            1.0
        }

        if (winMemoryMultiplier != 1.0) {
            val originalSize = finalSize
            finalSize = (finalSize * winMemoryMultiplier).coerceIn(0.01, 1.0)
            if (winMemoryMultiplier > 1.0) {
                tags.add("size_boosted_win_memory")
                checks.add(GateCheck("win_memory", true, "Size boosted ${originalSize.format(3)} → ${finalSize.format(3)} (memory=${winMemoryMultiplier.format(2)}x)"))

                if (TokenWinMemory.isKnownWinner(ts.mint)) {
                    val past = TokenWinMemory.getWinnerStats(ts.mint)
                    tags.add("REPEAT_WINNER")
                    checks.add(
                        GateCheck(
                            "repeat_winner",
                            true,
                            "🔥 REPEAT WINNER: ${past?.timesTraded ?: 0} trades, +${past?.totalPnl?.toInt() ?: 0}% total"
                        )
                    )
                }
            } else {
                tags.add("size_reduced_win_memory")
                checks.add(GateCheck("win_memory", true, "Size reduced ${originalSize.format(3)} → ${finalSize.format(3)} (dissimilar to winners)"))
            }
        }

        val liqSizeMultiplier = LiquidityDepthAI.getSizeMultiplier(ts.mint, ts.symbol)
        if (liqSizeMultiplier < 1.0) {
            val originalSize = finalSize
            finalSize = (finalSize * liqSizeMultiplier).coerceAtLeast(0.01)
            if (finalSize < originalSize) {
                tags.add("size_reduced_liq_depth")
                checks.add(GateCheck("liquidity_size", true, "Size adjusted ${originalSize.format(3)} → ${finalSize.format(3)} (depth=${liqSizeMultiplier.format(2)}x)"))
            }
        }

        val collectiveAdj = try {
            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                val marketSentiment = when {
                    ts.meta.emafanAlignment.contains("BULL") -> "BULL"
                    ts.meta.emafanAlignment.contains("BEAR") -> "BEAR"
                    else -> "NEUTRAL"
                }
                com.lifecyclebot.collective.CollectiveLearning.getPatternScoreAdjustment(
                    entryPhase = ts.phase.ifEmpty { "UNKNOWN" },
                    tradingMode = tradingModeTag?.name ?: "STANDARD",
                    discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                    liquidityUsd = ts.lastLiquidityUsd,
                    emaTrend = marketSentiment
                )
            } else 0
        } catch (_: Exception) {
            0
        }

        if (collectiveAdj != 0) {
            val direction = if (collectiveAdj > 0) "boost" else "penalty"
            val multiplier = 1.0 + (collectiveAdj.toDouble() / 100.0)
            val originalSize = finalSize
            finalSize = (finalSize * multiplier).coerceIn(0.01, 1.0)

            tags.add("collective_${direction}")
            checks.add(GateCheck("collective_learning", collectiveAdj > 0, "🌐 Collective $direction: ${originalSize.format(3)} → ${finalSize.format(3)} (adj=$collectiveAdj)"))

            if (kotlin.math.abs(collectiveAdj) >= 20) {
                ErrorLogger.info("FDG", "🌐 COLLECTIVE: ${ts.symbol} | adj=$collectiveAdj | ${if (collectiveAdj > 0) "PROVEN_WINNER" else "KNOWN_LOSER"}")
            }
        }

        val crossTalkSignal = try {
            AICrossTalk.analyzeCrossTalk(ts.mint, ts.symbol, isOpenPosition = false)
        } catch (_: Exception) {
            null
        }

        if (crossTalkSignal != null && crossTalkSignal.sizeMultiplier != 1.0) {
            val originalSize = finalSize
            finalSize = (finalSize * crossTalkSignal.sizeMultiplier).coerceIn(0.01, 1.0)
            if (finalSize != originalSize) {
                val direction = if (crossTalkSignal.sizeMultiplier > 1.0) "boosted" else "reduced"
                tags.add("size_${direction}_crosstalk")
                checks.add(
                    GateCheck(
                        "crosstalk_size",
                        true,
                        "Size $direction ${originalSize.format(3)} → ${finalSize.format(3)} (${crossTalkSignal.signalType.name})"
                    )
                )
            }
        }

        if (blockReason == null && useKellySizing && evResult != null && !config.paperMode) {
            val kellyRecommendedSize = evResult.kellyFraction * kellyFraction

            if (kellyRecommendedSize > 0 && kellyRecommendedSize < finalSize) {
                val originalSize = finalSize
                finalSize = kellyRecommendedSize.coerceIn(0.003, maxKellySize)

                checks.add(
                    GateCheck(
                        "kelly_sizing",
                        true,
                        "Kelly: ${originalSize.format(4)} → ${finalSize.format(4)} (Kelly=${String.format("%.1f", evResult.kellyFraction * 100)}% × $kellyFraction)"
                    )
                )
                tags.add("kelly_sized")
            } else if (kellyRecommendedSize > finalSize * 1.5 && evResult.isPositiveEV) {
                val originalSize = finalSize
                finalSize = (finalSize * 1.25).coerceAtMost(maxKellySize)

                checks.add(GateCheck("kelly_boost", true, "Kelly boost: ${originalSize.format(4)} → ${finalSize.format(4)} (high +EV)"))
                tags.add("kelly_boosted")
            }
        }

        if (blockReason == null) {
            val minSize = if (config.paperMode) 0.001 else 0.01
            if (finalSize < minSize) {
                blockReason = "SIZE_TOO_SMALL"
                blockLevel = BlockLevel.SIZE
                checks.add(GateCheck("min_size", false, "size=$finalSize < $minSize"))
            } else {
                checks.add(GateCheck("min_size", true, null))
            }

            val maxSize = if (config.paperMode) 1.0 else 0.5
            if (finalSize > maxSize) {
                finalSize = maxSize
                checks.add(GateCheck("max_size", true, "capped from $proposedSizeSol to $maxSize"))
                tags.add("size_capped")
            }
        }

        if (ts.source.isNotBlank()) tags.add("src:${ts.source}")
        if (ts.phase.isNotBlank()) tags.add("phase:${ts.phase}")

        val totalSoftPenalty = softPenaltyScore + behaviorPenalty
        val combinedSizeMultiplier = sizeMultiplier * behaviorSizeMultiplier
        val isAnyProbe = isProbeCandidate || behaviorProbe

        if (combinedSizeMultiplier < 1.0 && blockReason == null) {
            val originalSize = finalSize
            finalSize = (finalSize * combinedSizeMultiplier).coerceAtLeast(0.02)
            checks.add(GateCheck("bootstrap_size_cut", true, "Size cut for probe: ${originalSize.format(4)} × ${combinedSizeMultiplier.format(2)} = ${finalSize.format(4)}"))
            tags.add("bootstrap_size_reduced")
        }

        // V5.9.10: Symbolic size bias — emotional state + edge strength shape sizing
        if (blockReason == null && kotlin.math.abs(symSizeAdj - 1.0) > 0.05) {
            val originalSize = finalSize
            finalSize = (finalSize * symSizeAdj).coerceAtLeast(0.003)
            checks.add(GateCheck("symbolic_size_bias", true,
                "Symbolic size ${originalSize.format(4)} × ${symSizeAdj.format(2)} = ${finalSize.format(4)} (mood=$symMood)"))
            tags.add("symbolic_sized")
        }
        // V5.9.10: Symbolic entry-bar bias — apply to quality-required confidence bar
        if (blockReason == null && kotlin.math.abs(symEntryAdj - 1.0) > 0.05) {
            tags.add("sym_entry:${"%.2f".format(symEntryAdj)}")
        }

        if (totalSoftPenalty > 0 && blockReason == null) {
            checks.add(GateCheck("bootstrap_penalty", true, "Total soft penalty: -${totalSoftPenalty}pts (applied to confidence eval)"))
            tags.add("bootstrap_penalized")
        }

        val shouldTrade = blockReason == null && candidate.shouldTrade

        if (!shouldTrade && blockReason != null) {
            recordBlock(blockReason)
        } else if (shouldTrade) {
            recordTradeExecuted()
        }

        if (adaptiveRelaxationActive) {
            tags.add("adaptive_relaxed")
        }

        val liveAdaptiveConf = getAdaptiveConfidence(isPaperMode = false, ts)

        val (approvalClass, approvalReason) = when {
            !shouldTrade -> ApprovalClass.BLOCKED to "blocked: ${blockReason ?: "candidate_shouldTrade_false"}"
            !config.paperMode -> ApprovalClass.LIVE to "live mode approval (adaptive conf: ${liveAdaptiveConf.toInt()}%)"
            else -> {
                val wouldPassLiveEdge = edgeVerdict != EdgeVerdict.SKIP
                val wouldPassLiveQuality = candidate.setupQuality in listOf("A+", "A", "B")
                val wouldPassLiveConfidence = adjustedConfidence >= liveAdaptiveConf

                when {
                    isAnyProbe -> {
                        val probeReasons = mutableListOf<String>()
                        if (dangerZonePenalty > 0) probeReasons.add("time_danger")
                        if (memoryPenalty > 0) probeReasons.add("memory_neg")
                        if (behaviorPenalty > 0) probeReasons.add("behavior_100pct")
                        if (confidenceProbe) probeReasons.add("confidence_override")
                        ApprovalClass.PAPER_PROBE to "probe: soft blocks→penalties (${probeReasons.joinToString(",")}), size×${combinedSizeMultiplier.format(2)}"
                    }
                    wouldPassLiveEdge && wouldPassLiveQuality && wouldPassLiveConfidence -> {
                        ApprovalClass.PAPER_BENCHMARK to "benchmark: passes live rules (edge=$wouldPassLiveEdge quality=${candidate.setupQuality} conf=${adjustedConfidence.toInt()}%>=${liveAdaptiveConf.toInt()}%)"
                    }
                    else -> {
                        val relaxedReasons = mutableListOf<String>()
                        if (!wouldPassLiveEdge) relaxedReasons.add("edge=${edgeVerdict.name}")
                        if (!wouldPassLiveQuality) relaxedReasons.add("quality=${candidate.setupQuality}")
                        if (!wouldPassLiveConfidence) relaxedReasons.add("conf=${adjustedConfidence.toInt()}%<${liveAdaptiveConf.toInt()}%")
                        ApprovalClass.PAPER_EXPLORATION to "exploration: relaxed ${relaxedReasons.joinToString(", ")}"
                    }
                }
            }
        }

        tags.add("class:${approvalClass.name}")

        return FinalDecision(
            shouldTrade = shouldTrade,
            mode = mode,
            approvalClass = approvalClass,
            quality = candidate.finalQuality,
            confidence = adjustedConfidence,
            edge = edgeVerdict,
            blockReason = blockReason,
            blockLevel = blockLevel,
            sizeSol = finalSize,
            tags = tags,
            mint = ts.mint,
            symbol = ts.symbol,
            approvalReason = approvalReason,
            gateChecks = checks,
        )
    }

    fun logBlockedTrade(decision: FinalDecision, onLog: (String) -> Unit) {
        onLog("🚫 FDG BLOCKED: ${decision.symbol} | ${decision.blockReason} | quality=${decision.quality} conf=${decision.confidence.toInt()}% edge=${decision.edge.name}")
        val failedChecks = decision.gateChecks.filter { !it.passed }
        if (failedChecks.isNotEmpty()) {
            onLog("   Failed checks: ${failedChecks.joinToString { "${it.name}(${it.reason})" }}")
        }
    }

    fun logApprovedTrade(decision: FinalDecision, onLog: (String) -> Unit) {
        val classIcon = when (decision.approvalClass) {
            ApprovalClass.LIVE -> "🔴"
            ApprovalClass.PAPER_BENCHMARK -> "🟢"
            ApprovalClass.PAPER_EXPLORATION -> "🟡"
            ApprovalClass.PAPER_PROBE -> "🔵"
            ApprovalClass.BLOCKED -> "⬛"
        }
        onLog("$classIcon FDG ${decision.approvalClass}: ${decision.symbol} | ${decision.quality} | ${decision.confidence.toInt()}% | ${decision.sizeSol.format(3)} SOL")
    }

    fun updateThresholds(
        rugcheckMin: Int? = null,
        buyPressureMin: Double? = null,
        topHolderMax: Double? = null,
    ) {
        rugcheckMin?.let { hardBlockRugcheckMin = it }
        buyPressureMin?.let { hardBlockBuyPressureMin = it }
        topHolderMax?.let { hardBlockTopHolderMax = it }
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}