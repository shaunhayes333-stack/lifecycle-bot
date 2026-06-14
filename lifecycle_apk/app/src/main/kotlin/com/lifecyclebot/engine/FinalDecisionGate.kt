package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.quant.EVCalculator
import com.lifecyclebot.engine.sell.LiveBuyAdmissionGate
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
        // V5.9.1368 — PROBE_ONLY is an APPROVED dust-size buy, NOT a veto. The lane
        // wait-override path (BotService ~7616/7640) deliberately returns
        // shouldTrade=true with blockReason="PROBE_ONLY" + qualityPenalty=DUST size to
        // execute a tiny learning probe on liq-OK-but-weak tokens. The old
        // `blockReason == null` rule treated that tag as a hard veto, so canExecute()
        // returned false and the probe NEVER fired — 14,096 probe-buys killed in one
        // session (top FDG block reason), collapsing execs/day to 139 vs the 500-1000
        // floor. PROBE_ONLY is the single biggest volume choke. Treat it (and only it)
        // as executable; every other blockReason still vetoes. Size is already dusted
        // upstream via qualityPenalty=LANE_DUST_PROBE_SIZE_MULT, so this respects the
        // P0.7 "no normal-size zero-signal buy" rule — it only frees the TINY probe.
        fun canExecute(): Boolean = shouldTrade && (blockReason == null || blockReason == "PROBE_ONLY")
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

    // V5.9.616 — ALIGN WITH FluidLearningAI 5000-TRADE MATURITY LADDER.
    // Operator: "learning trade totals are 5000 to maturity". The old 500-trade
    // FDG ladder made the bot register 53% maturity at trade #265 and full
    // mature at trade #500, which (combined with V5.9.616 readiness rules)
    // slammed AI_DEGRADED 32% confidence floors on every entry from trade
    // #50+. Re-baselined to bootstrap=0-1000, learning=1000-3000, mature=3000+
    // matching FluidLearningAI.BOOTSTRAP_PHASE_END / MATURE_PHASE_END /
    // EXPERT_PHASE_END so the two systems agree on phase boundaries.
    // V5.9.987 — aligned to Performance Doctrine #4: bootstrap is <5000
    // lifetime trades, mature is >5000. Old boundaries (BOOTSTRAP_END=1000,
    // MATURE@3000) flipped the bot into MATURE-phase gating (evGating ON,
    // confidence floor 60%, rugcheck min 12, edge floors at mature peak)
    // at 3000 lifetime trades — 2000 trades INSIDE the doctrine bootstrap
    // band. That activated steep choke points during the doctrine-permitted
    // learning phase, capping volume below the 500-1000/day target.
    private const val FDG_BOOTSTRAP_END = 2000
    private const val FDG_LEARNING_END = 5000
    private const val FDG_EXPERT_END = 8000

    fun getLearningPhase(tradeCount: Int): LearningPhase = when {
        tradeCount <= FDG_BOOTSTRAP_END -> LearningPhase.BOOTSTRAP
        tradeCount <= FDG_LEARNING_END -> LearningPhase.LEARNING
        else -> LearningPhase.MATURE
    }

    fun getLearningProgress(tradeCount: Int, winRate: Double): Double {
        // V5.9.616 — 4-phase curve to 5000 trades.
        // 0-1000:    0.0 → 0.50  (bootstrap)
        // 1000-3000: 0.50 → 0.80 (learning)
        // 3000-5000: 0.80 → 1.0  (expert)
        // Win-rate bonus removed: a 27% WR bot doesn't deserve a +10% maturity
        // gift just because it had a lucky streak. Maturity is purely volume.
        val baseProgress = when {
            tradeCount <= FDG_BOOTSTRAP_END ->
                (tradeCount.toDouble() / FDG_BOOTSTRAP_END) * 0.50
            tradeCount <= FDG_LEARNING_END ->
                0.50 + ((tradeCount - FDG_BOOTSTRAP_END).toDouble() /
                        (FDG_LEARNING_END - FDG_BOOTSTRAP_END)) * 0.30
            tradeCount <= FDG_EXPERT_END ->
                0.80 + ((tradeCount - FDG_LEARNING_END).toDouble() /
                        (FDG_EXPERT_END - FDG_LEARNING_END)) * 0.20
            else -> 1.0
        }
        return baseProgress.coerceIn(0.0, 1.0)
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

    private const val CONF_FLOOR_BOOTSTRAP = 8.0   // V5.9.266: moderate (was 6 at V5.9.263)
    private const val CONF_FLOOR_MATURE = 60.0

    var paperConfidenceBase = 8.0  // V5.9.184: was 3.0 — raises bootstrap WR floor toward 25-50% target
    var liveConfidenceBase = 0.0

    private var consecutiveBlockCount = 0
    private var lastBlockReason: String? = null
    private var adaptiveRelaxationActive = false
    private const val DANGER_ZONE_BYPASS_THRESHOLD = 20 // V5.9.213: raised from 8 — stops junk entering after losing streak
    private const val MEMORY_BYPASS_THRESHOLD = 25 // V5.9.213: raised from 10
    private const val MAX_RELAXATION_TRADES = 15  // V5.9.643: raised 3→15 (3 trades burned out too fast when AntiChoke not actively holding forceAdaptiveRelaxation; starvation recovered in 3 trades then floors snapped back)
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
            // V5.9.616 — do NOT consume relaxation budget while AntiChoke is
            // forcing it on. The 3-trade cap was meant for in-FDG soft-block
            // recovery, not for the bot-wide starvation immune system. While
            // AntiChoke says SOFTEN/RECOVERY, relaxation persists until the
            // operator's trade-rate target is recovered.
            val antiChokeForcing = try {
                com.lifecyclebot.engine.AntiChokeManager.isSoftening()
            } catch (_: Throwable) { false }
            if (!antiChokeForcing) {
                relaxationTradesUsed++
                if (relaxationTradesUsed >= MAX_RELAXATION_TRADES) {
                    adaptiveRelaxationActive = false
                    relaxationTradesUsed = 0
                    ErrorLogger.info("FDG", "🔒 Adaptive relaxation deactivated (used $MAX_RELAXATION_TRADES relaxed trades)")
                }
            }
        }
        consecutiveBlockCount = 0
        lastBlockReason = null
    }

    /**
     * V5.9.616 — AntiChoke → FDG bridge. AntiChokeManager calls this when
     * it transitions to SOFTEN or RECOVERY so confidence floors relax
     * immediately, without waiting for 20 consecutive blocks. The operator
     * rule is: "the choke manager isnt doing its job. its meant to be able
     * to drop scoring if need be unchoke trading the scanner and watchlist
     * instantly". This is the lever AntiChoke pulls.
     */
    /** V5.9.620 — public read for UI / status panels. */
    fun isAdaptiveRelaxationActive(): Boolean = adaptiveRelaxationActive

    fun forceAdaptiveRelaxation(reason: String) {
        if (adaptiveRelaxationActive) return
        adaptiveRelaxationActive = true
        relaxationTradesUsed = 0
        ErrorLogger.warn("FDG", "🔓 ADAPTIVE RELAXATION FORCED by $reason — confidence floors dropped")
    }

    /**
     * V5.9.616 — explicit clear (for AntiChoke when it returns to CLEAR).
     */
    fun clearAdaptiveRelaxation(reason: String) {
        if (!adaptiveRelaxationActive) return
        adaptiveRelaxationActive = false
        relaxationTradesUsed = 0
        consecutiveBlockCount = 0
        ErrorLogger.info("FDG", "🔒 Adaptive relaxation cleared by $reason")
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
        // V5.9.620 — ANTI-CHOKE TEETH. When adaptive relaxation is active
        // (driven by AntiChokeManager SOFTEN/RECOVERY), pin the lerp
        // progress to 0.0. This drops EVERY downstream floor — rugcheck,
        // buy-pressure, top-holder, confidence, edge gates — back to
        // bootstrap levels regardless of trade count. Without this,
        // forceAdaptiveRelaxation() only halved the confidence floor
        // (line 443) and bypassed two specific soft-blocks; the
        // 5000-trade ladder thresholds kept blocking entries during a
        // starvation event. Operator: "the choke manager isnt doing
        // its job — its meant to be able to drop scoring if need be
        // and unchoke trading instantly."
        val rawProgress = getLearningProgress(tradeCount, winRate)
        val progress = if (adaptiveRelaxationActive) 0.0 else rawProgress
        // EV gating must also drop while relaxed — it's the steepest
        // late-phase choke point.
        val evGating = !adaptiveRelaxationActive && phase == LearningPhase.MATURE

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
            evGatingEnabled = evGating,
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
        
        // V5.9.356 — Raise the MATURE-phase floor from 36% (60×0.6) to 45%
        // (60×0.75). User report at 794 trades / 30% layer accuracy showed
        // 81% scratch rate — bot was taking too many coin-flip setups, the
        // layers couldn't learn from fee-band outcomes. A tighter mature
        // floor blocks low-conviction trades and gives the brain decisive
        // (win/loss) outcomes to learn from.
        val floor = lerp(paperConfidenceBase, CONF_FLOOR_MATURE * 0.75, learningProgress)
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

    /** V5.9.182: Reset stale in-memory block counts after DB wipe / fresh session. */
    fun resetLearningState() {
        consecutiveBlockCount = 0
        adaptiveRelaxationActive = false
        relaxationTradesUsed = 0
        edgeVetoes.clear()
        distributionCooldowns.clear()
        ErrorLogger.info("FDG", "🔄 Learning state reset (fresh session)")
    }

    /** V5.9.766 — EMERGENT priority 3: upstream SafetyReady gate per-mint
     *  dedupe map. One forensic emit per mint per 60 s window so the
     *  scanner cycling cannot flood dumps when a token has no safety
     *  report yet. Mirrors the executor-level dedupe added in V5.9.765. */
    private val fdgSafetyReadyDedup = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FDG_SAFETY_READY_COOLDOWN_MS = 60_000L

    private fun shouldEmitSafetyReadyBlock(mint: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = fdgSafetyReadyDedup[mint]
        if (prev != null && (now - prev) < FDG_SAFETY_READY_COOLDOWN_MS) return false
        fdgSafetyReadyDedup[mint] = now
        if (fdgSafetyReadyDedup.size > 256) {
            val cutoff = now - FDG_SAFETY_READY_COOLDOWN_MS
            val it = fdgSafetyReadyDedup.entries.iterator()
            while (it.hasNext()) if (it.next().value < cutoff) it.remove()
        }
        return true
    }


    fun evaluate(
        ts: TokenState,
        candidate: CandidateDecision,
        config: BotConfig,
        proposedSizeSol: Double,
        brain: BotBrain? = null,
        tradingModeTag: ModeSpecificGates.TradingModeTag? = null,
        // V5.9.1296 — the lane's REAL signal score (0-100) for LEARNING-CONTEXT
        // bucketing only. Defaults to candidate.entryScore so non-lane callers are
        // unchanged. Lane callers pass their true score (qualityScore/manipScore/
        // confidence×100) so meta-policy / forward-model stop collapsing every
        // specialist context into S00. Does NOT touch any entry gate.
        laneScore: Double = candidate.entryScore,
    ): FinalDecision {
        val checks = mutableListOf<GateCheck>()
        var blockReason: String? = null
        var blockLevel: BlockLevel? = null
        val tags = mutableListOf<String>()

        // V5.9.1567 — FDG MODE CONTAMINATION FIX. The `config` BotConfig passed
        // into FDG can carry a stale `paperMode` flag (same regression that was
        // previously patched only inside ToxicMode circuit breaker @ V5.9.1119).
        // Result: live mode trades got evaluated under paper-mode branches
        // throughout this function (~40 `config.paperMode` reads). Apply the
        // RuntimeModeAuthority override ONCE at the top so every downstream
        // `config.paperMode` read inside this function inherits the
        // authoritative mode. Authority wins; config flag is fallback only.
        val authoritativePaperMode: Boolean = try {
            RuntimeModeAuthority.isPaper()
        } catch (_: Throwable) {
            config.paperMode
        }
        val configIn: BotConfig = config
        @Suppress("NAME_SHADOWING")
        val config: BotConfig =
            if (authoritativePaperMode == configIn.paperMode) configIn
            else configIn.copy(paperMode = authoritativePaperMode)
        val mode = if (config.paperMode) TradeMode.PAPER else TradeMode.LIVE
        val laneName = tradingModeTag?.name ?: "STANDARD"
        // V5.9.1299 — single source of truth for the LEARNING-CONTEXT score.
        // laneScore is the lane's REAL 0-100 signal (1296/1297); candidate.entryScore
        // is the shared base V3 score (~7 for memes). All learning lookups
        // (LosingPatternMemory danger buckets, AutonomousMetaPolicy, ForwardOutcomeModel)
        // must key on the lane score so contexts bucket by true quality instead of
        // collapsing into S00. Entry GATES still use candidate.entryScore (unchanged).
        val laneScoreBanded = laneScore.coerceIn(0.0, 100.0).toInt()
        val canonicalLearning = try { TradeHistoryStore.getStatsCached() } catch (_: Throwable) { null }
        val canonicalDecisive = (canonicalLearning?.totalWins ?: 0) + (canonicalLearning?.totalLosses ?: 0)
        val canonicalWr = if (canonicalDecisive > 0)
            (canonicalLearning?.totalWins ?: 0).toDouble() * 100.0 / canonicalDecisive.toDouble()
        else 50.0
        val canonicalRollingWr = try { TradeHistoryStore.rollingWinRatePct(50) } catch (_: Throwable) { -1.0 }
        val canonicalTargetWr = try { FreeRangeMode.phaseTargetWr(canonicalDecisive) } catch (_: Throwable) { 0.0 }
        val deepLearningDeficit = canonicalDecisive >= 50 && canonicalTargetWr > 0.0 && canonicalWr < (canonicalTargetWr * 0.85)
        val moderateLearningDeficit = canonicalDecisive >= 50 && canonicalTargetWr > 0.0 && canonicalWr < canonicalTargetWr

        // V5.9.1136 — cheap non-executable signal guard. 3102 showed 3k+ FDG/Signal
        // blocks, meaning WAIT candidates were still walking the full expensive FDG
        // stack before being rejected. This does NOT prune scanner intake or lane eval;
        // it just local-blocks non-BUY candidates before ML/BCG/social/EV work.
        if (candidate.blockReason.startsWith("Signal is ") && candidate.blockReason.endsWith(", not BUY")) {
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = EdgeVerdict.SKIP,
                blockReason = candidate.blockReason,
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("fdg_local_wait_fast_block", "lane:$laneName"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "FDG_FAST_BLOCK: ${candidate.blockReason}",
                gateChecks = listOf(GateCheck("candidate_base_signal", false, "fast_block lane=$laneName ${candidate.blockReason}")),
            )
        }

        val overlayLane = laneName
        if (overlayLane != "STANDARD" && RuntimeConfigOverlay.isLaneDisabled(overlayLane)) {
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "RUNTIME_OVERLAY_LANE_DISABLED_$overlayLane",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("runtime_overlay", "lane_disabled:$overlayLane"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "runtime overlay disabled lane $overlayLane",
                gateChecks = listOf(GateCheck("RUNTIME_OVERLAY", false, "lane_disabled=$overlayLane")),
            )
        }

        // V5.9.805 — operator audit Fix (β): record this candidate's V3
        // score in the WrRecoveryPartial rolling distribution. We do this
        // at the top of FDG (after candidate construction) because every
        // candidate that reaches FDG has already been scored by V3 — so
        // we're sampling the live regime distribution without pre-score
        // zeros. The auto-fit logic in WrRecoveryPartial.minScoreFloor()
        // then drops the floor by ~25 in thin regimes (median<15) so
        // sub-traders / FDG can collect samples instead of starving.
        try {
            val v3Score = candidate.entryScore.toInt()
            if (v3Score >= 0) com.lifecyclebot.engine.WrRecoveryPartial.recordV3Score(v3Score)
        } catch (_: Throwable) {}

        // V5.9.973 — z DGradeLooperTracker revival (was 0-caller).
        // TELEMETRY ONLY (no veto, no soft-shape yet). Records every
        // candidate so operator can see if D-grade looper churn is a
        // real volume/WR drag. Tag added when a D-grade mint has been
        // re-proposed >MAX times in the 2-minute window; FDG continues
        // to evaluate normally — block decision deferred until operator
        // confirms the signal is worth acting on.
        try {
            val q = candidate.setupQuality
            val cf = candidate.aiConfidence.toInt()
            com.lifecyclebot.v3.eligibility.DGradeLooperTracker.recordProposal(ts.mint, q, cf)
            if (com.lifecyclebot.v3.eligibility.DGradeLooperTracker
                    .shouldBlockDGradeLooper(ts.mint, q, cf)) {
                tags.add("d_grade_looper")
            }
        } catch (_: Throwable) {}

        // V5.9.766 — EMERGENT priority 3: upstream SafetyReady gate.
        // Operator forensics_20260515_161017 showed 17 BUY_FAILED
        // LIVE_BUY_BLOCKED_RISK[liveBuy.main] SAFETY_DATA_MISSING events
        // in a 1.28 s burst — every one fired by the executor's
        // LiveBuyAdmissionGate AFTER FDG had already approved the trade.
        // V5.9.765 added a 60 s per-mint dedupe at the executor gate.
        // V5.9.766 moves the safety-readiness check UPSTREAM so the
        // candidate is never dispatched to the executor at all when
        // the safety report is missing or stale — the cleanest fix
        // per the deferred ticket.
        //
        // PAPER mode is intentionally exempt — paper trades treat the
        // safety report as a scoring input, not a hard gate, so the
        // bot can keep learning even when rugcheck is slow / down.
        if (mode == TradeMode.LIVE) {
            val safetyAgeMs = System.currentTimeMillis() - ts.lastSafetyCheck
            val safetyMissing = ts.lastSafetyCheck == 0L
            val safetyStale = !safetyMissing && safetyAgeMs > LiveBuyAdmissionGate.SAFETY_STALE_MS
            // V5.9.776 — SAFETY_READ canonical-key trace.
            // Operator forensic spec V5.9.776 §1: every reader of the
            // safety report must log canonical key + found + ageMs +
            // reader so a key-mismatch regression is impossible to
            // hide. Throttled by shouldEmitSafetyReadyBlock dedupe so
            // we don't spam the forensic store at every tick.
            if ((safetyMissing || safetyStale) && shouldEmitSafetyReadyBlock(ts.mint)) {
                try {
                    val canonicalKey = com.lifecyclebot.data.CanonicalMint.normalize(ts.mint)
                    ForensicLogger.lifecycle(
                        "SAFETY_READ",
                        "key=${canonicalKey.take(10)} symbol=${ts.symbol} found=${!safetyMissing} ageMs=$safetyAgeMs reader=FDG verdict=${if (safetyMissing) "MISSING" else "STALE"}",
                    )
                } catch (_: Throwable) {}
            }
            if (safetyMissing || safetyStale) {
                val reason = if (safetyMissing) "SAFETY_NOT_READY_MISSING" else "SAFETY_NOT_READY_STALE"
                if (shouldEmitSafetyReadyBlock(ts.mint)) {
                    try {
                        ForensicLogger.lifecycle(
                            "FDG_BLOCKED_SAFETY_NOT_READY",
                            "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason ageSec=${safetyAgeMs / 1000} mode=LIVE",
                        )
                    } catch (_: Throwable) {}
                    ErrorLogger.info(
                        "FDG",
                        "🛡 UPSTREAM_SAFETY_GATE: ${ts.symbol} | $reason — candidate held back from executor",
                    )
                }
                return FinalDecision(
                    shouldTrade = false,
                    mode = mode,
                    approvalClass = ApprovalClass.BLOCKED,
                    quality = candidate.setupQuality,
                    confidence = candidate.aiConfidence,
                    edge = EdgeVerdict.SKIP,
                    blockReason = reason,
                    blockLevel = BlockLevel.HARD,
                    sizeSol = 0.0,
                    tags = listOf("upstream_safety_gate", reason.lowercase()),
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "FDG upstream safety gate: $reason (live-mode hard block before executor)",
                    gateChecks = listOf(GateCheck("safety_ready_upstream", false, reason)),
                )
            }
        }

        val modeMultipliers = try {
            ModeSpecificGates.getMultipliers(tradingModeTag)
        } catch (e: Exception) {
            ErrorLogger.warn("FDG", "ModeSpecificGates error: ${e.message}")
            ModeSpecificGates.ModeMultipliers.DEFAULT
        }

        if (tradingModeTag != null && tradingModeTag != ModeSpecificGates.TradingModeTag.STANDARD) {
            tags.add("mode:${tradingModeTag.name}")
        }

        // V5.9.212: Symbolic Context — 24-channel symbolic nervous system
        // Expanded from 16 channels. getEntryGreenLight() is the new composite gate.
        val symCtx = try {
            SymbolicContext.refresh(ts.symbol, ts.mint)
            SymbolicContext
        } catch (_: Exception) { null }
        val symEntryAdj  = try { symCtx?.getEntryAdjustment() ?: 1.0 } catch (_: Exception) { 1.0 }
        val symSizeAdj   = try { symCtx?.getSizeAdjustment()  ?: 1.0 } catch (_: Exception) { 1.0 }
        val symMood      = try { symCtx?.emotionalState ?: "NEUTRAL" } catch (_: Exception) { "NEUTRAL" }
        val symGreenLight = try { symCtx?.getEntryGreenLight() ?: 0.5 } catch (_: Exception) { 0.5 }
        val symCircuitBreaking = try { symCtx?.isCircuitBreaking() ?: false } catch (_: Exception) { false }
        val symRegimeTrans = try { symCtx?.isRegimeTransitioning() ?: false } catch (_: Exception) { false }
        val symLeadLagWarn = try { symCtx?.isLeadLagWarning() ?: false } catch (_: Exception) { false }
        if (symCtx != null) {
            tags.add("sym:$symMood")
            tags.add("sym_edge:${"%.2f".format(symCtx.edgeStrength)}")
            tags.add("sym_green:${"%.2f".format(symGreenLight)}")
            if (symCircuitBreaking) tags.add("sym_circuit_breaking")
            if (symRegimeTrans) tags.add("sym_regime_trans")
            if (symLeadLagWarn) tags.add("sym_leadlag_warn")
        }
        // V5.9.213: Symbolic universe block — LIVE only hard-block.
        // In paper/bootstrap mode we only log + tag (no block) so the bot can keep
        // learning even during a losing streak. The score penalty from SymbolicContext
        // still applies (-8 symNudge), and DrawdownCircuitAI still score-penalises (-20).
        // Hard block ONLY fires in live-money mode where real losses must be protected.
        if (symGreenLight < 0.20 && symMood in listOf("PANIC", "FEARFUL") && symCircuitBreaking) {
            ErrorLogger.info("FDG", "🌌 SYMBOLIC_WARN: ${ts.symbol} | greenLight=${"%.2f".format(symGreenLight)} mood=$symMood circuit_breaking=true | mode=$mode")
            if (mode == TradeMode.LIVE) {
                // LIVE: Hard block — don't risk real money in panic+circuit-tripped state
                return FinalDecision(
                    shouldTrade = false,
                    mode = mode,
                    approvalClass = ApprovalClass.BLOCKED,
                    quality = candidate.setupQuality,
                    confidence = candidate.aiConfidence,
                    edge = EdgeVerdict.SKIP,
                    blockReason = "SYMBOLIC_UNIVERSE_BLOCK",
                    blockLevel = BlockLevel.CONFIDENCE,
                    sizeSol = 0.0,
                    tags = tags + listOf("symbolic_block", "panic_mode"),
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "SYMBOLIC_BLOCK: greenLight<0.20 + PANIC/FEARFUL + circuit_breaking (LIVE)",
                    gateChecks = listOf(GateCheck("symbolic_universe", false, "greenLight=${"%.2f".format(symGreenLight)} mood=$symMood"))
                )
            }
            // PAPER: Tag it but allow through so learning continues. 
            // EntryIntelligence symNudge + DrawdownCircuit score penalty already apply.
            tags.add("sym_panic_paper_warn")
        }

        if (candidate.aiConfidence <= 0.0) {
            // V5.9.311: Zero-confidence hard block now LIVE-only.
            // In paper mode, EdgeOptimizer.calculateConfidence floors at 8.0,
            // so a true 0.0 here means a malformed/null candidate — still skip
            // it but no longer cripple the entire learning loop.
            if (mode == TradeMode.LIVE) {
                ErrorLogger.info("FDG", "🚫 ZERO_CONF_BLOCK (LIVE): ${ts.symbol} | quality=${candidate.setupQuality} edge=${candidate.edgeQuality} conf=0% → SHADOW ONLY")

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
            // PAPER: tag and continue — let downstream sizing/penalty decide.
            // This restores V5.9.198-era learning volume (1000+ trades/day).
            ErrorLogger.info("FDG", "ℹ️ ZERO_CONF_PASSTHRU (PAPER): ${ts.symbol} | quality=${candidate.setupQuality} edge=${candidate.edgeQuality} → continue with min-size for learning")
            tags.add("zero_conf_paper_learn")
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

        // V5.9.616 — earlyAIDegraded gated by sample count.
        // Before, ANY 27% session win rate flipped this true at trade #20,
        // slamming the 32% confidence floor on a baby bot. Now requires the
        // bot to have at least 500 settled trades (10% of the 5000-trade
        // maturity target) AND an entry-AI win rate < 25% OR catastrophic
        // exit PnL. Below 500 trades the bot is allowed to be unprofitable
        // while it gathers signal — that's literally what bootstrap is for.
        val totalSettled = currentConditions.totalSessionTrades + currentConditions.historicalTradeCount
        val earlyAIDegraded = try {
            totalSettled >= 500 && (
                currentConditions.entryAiWinRate < 25.0 ||
                currentConditions.exitAiAvgPnl < -15.0
            )
        } catch (_: Exception) {
            false
        }

        val tradingModeStr = tradingModeTag?.name ?: ""

        // V5.9.1055 — COPY_TRADE re-enabled. Copy trade follows smart money wallet signals;
        // it is a valid learning strategy. Allow in paper mode always; live mode requires
        // confidence >= 50 (same as WHALE_FOLLOW). No permanent ban.
        if (tradingModeStr.uppercase().contains("COPY")) {
            if (mode == TradeMode.LIVE && candidate.aiConfidence < 50.0) {
                ErrorLogger.info("FDG", "⚠️ COPY_TRADE live blocked: confidence=${candidate.aiConfidence.toInt()}% < 50%")
                return FinalDecision(
                    shouldTrade = false, mode = mode,
                    approvalClass = ApprovalClass.BLOCKED,
                    quality = candidate.setupQuality, confidence = candidate.aiConfidence,
                    edge = EdgeVerdict.SKIP, blockReason = "COPY_TRADE_LIVE_LOW_CONFIDENCE",
                    blockLevel = BlockLevel.HARD, sizeSol = 0.0,
                    tags = listOf("copy_trade_live_conf"), mint = ts.mint, symbol = ts.symbol,
                    approvalReason = "COPY_TRADE_LOW_CONF_LIVE",
                    gateChecks = listOf(GateCheck("copy_conf", false, "conf=${candidate.aiConfidence.toInt()}% < 50%"))
                )
            }
            // Paper always allowed — copy trade must learn
            ErrorLogger.info("FDG", "✅ COPY_TRADE: ${ts.symbol} | mode=$tradingModeStr | allowed for learning")
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
        // V5.9.341 — CLASSIC-MODE BYPASS (Phase A).
        // When UnifiedScorer.classicMode=true the user wants the bot to behave
        // like builds #1920-#1947: isBootstrapPhase < 0.25 (not 0.40) so the
        // bootstrap bypass window lines up with golden learning velocity.
        val classicMode = try { com.lifecyclebot.v3.scoring.UnifiedScorer.classicMode } catch (_: Exception) { true }
        val isBootstrapPhase = if (classicMode) learningProgress < 0.25 else learningProgress < 0.40  // V5.9.341 classic / V5.9.165 modern
        val isPaperMode = mode == TradeMode.PAPER
        // V5.9.616 — UNCHOKE BRIDGE.
        // The confidence-floor bypass must STAY TRUE in any of these cases:
        //   1. We're still inside bootstrap (learningProgress < 0.40) — the
        //      bot can't learn out of a 27% WR if every entry is rejected.
        //   2. Total settled trades < 1000 — full bootstrap volume target,
        //      regardless of how the FluidLearningAI maturity curve maps.
        //      Operator rule: "the bot should never choke. its meant to go
        //      out of learning at maturity which is 5000 trades."
        //   3. AntiChokeManager is currently in SOFTEN or RECOVERY — the
        //      anti-choke immune system has detected starvation and is
        //      explicitly asking gates to relax. FDG must obey.
        //   4. FDG.adaptiveRelaxationActive — the in-FDG soft-block bypass.
        val totalTradesForBypass = currentConditions.totalSessionTrades +
            currentConditions.historicalTradeCount
        val antiChokeRelaxing = try {
            com.lifecyclebot.engine.AntiChokeManager.isSoftening()
        } catch (_: Throwable) { false }
        // V5.9.721-FIX: Low-WR bypass — when system WR < 30% the confidence floors
        // are counterproductive. The bot must be allowed to trade through bad streaks
        // to collect data and escape the death spiral. Hard floors at 22% add extra
        // blocking on top of already-bad scoring, reducing volume and making
        // the WR problem worse (less volume = slower learning recovery).
        //
        // V5.9.809 — operator mandate revokes this clause. 'No more wide-open
        // mode' — bypassing because WR is low is the WORST moment to wide-open:
        // it lets the death spiral compound on itself. Replaced with soft
        // penalties at each individual gate (see WR_RECOVERY_SOFT_PENALTY etc.).
        // Variable retained as telemetry-only for the audit dump below.
        val systemWrForBypass = try {
            val ls = com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats()
            if (ls.totalSells > 0) ls.totalWins.toDouble() / ls.totalSells else 1.0
        } catch (_: Throwable) { 1.0 }
        val lowWrBypass = false  // V5.9.809: revoked (was: systemWrForBypass < 0.30)

        val canBypassConfidenceFloors = isBootstrapPhase ||
            totalTradesForBypass < 500 ||  // V5.9.809: 3000→500 (operator mandate: short cold-start, not wide-open through learning)
            antiChokeRelaxing ||
            adaptiveRelaxationActive ||
            lowWrBypass
        // V5.9.683-FIX + V5.9.721: surface bypass state so operator can audit 22%-floor trips
        ErrorLogger.debug("FDG", "FDG_BYPASS=${canBypassConfidenceFloors}: bypass=$totalTradesForBypass/500 bootstrap=$isBootstrapPhase antiChoke=$antiChokeRelaxing adaptive=$adaptiveRelaxationActive lowWR=${(systemWrForBypass*100).toInt()}%(revoked)")

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
        // V5.9.809 — operator mandate: 'AI learning and soft adjustment from
        // trade one.' Previously FluidLearningAI.getAdjustedConfidence only
        // ran when canBypassConfidenceFloors was TRUE (wide-open path) — i.e.
        // the soft-learning layer was OFF for the normal path. That's the
        // exact opposite of the mandate. Always run the fluid learning
        // adjustment now; it's the soft mechanism that nudges confidence
        // based on accumulated outcomes from trade 1 onward.
        val confidence = FluidLearningAI.getAdjustedConfidence(rawConfidence, isPaperMode)

        // V5.9.798 — operator audit: Smart Entry Gate during WR recovery.
        // Operator mandate: 'the bot overall should be working harder and
        // smarter to ensure the recovery doesn't really need to fire.'
        // While WR is in MODERATE/AGGRESSIVE recovery bands the bot should
        // stop piling on low-EV C/D-grade entries and only take the
        // highest-quality A / A+ setups. Skipping this gate in the FLUID
        // band keeps discovery flowing when WR is barely under target.
        //
        // V5.9.809 — operator mandate: NO MORE WIDE-OPEN BYPASSES.
        // Convert this gate from HARD BLOCK to SOFT PENALTY. Non-A/A+
        // entries during recovery are no longer rejected outright; they
        // continue down the pipeline with reduced confidence + size.
        // The existing confidence floors then naturally filter the truly
        // weak setups, while volume is preserved and AI learning
        // (FluidLearningAI below) accumulates samples from trade 1
        // across all quality grades.
        var wrRecoveryQualityPenaltyMult = 1.0
        try {
            val wrState = com.lifecyclebot.engine.WrRecoveryPartial.stateNow()
            val isHighRecovery = wrState.band == com.lifecyclebot.engine.WrRecoveryPartial.Band.MODERATE ||
                                 wrState.band == com.lifecyclebot.engine.WrRecoveryPartial.Band.AGGRESSIVE
            val isAGrade = candidate.setupQuality == "A" || candidate.setupQuality == "A+"
            // V5.9.1223 — collapse is not lane-disable mode. Operator rule:
            // keep learning; do not disable lanes. When roll50 is catastrophic,
            // let non-A setups continue as micro-probes with harsher size
            // shaping instead of hard-blocking them.
            if (wrState.rollingCollapse && !isAGrade) {
                val collapseQualityMult = when (candidate.setupQuality) {
                    "B"  -> 0.35
                    "C"  -> 0.22
                    "D"  -> 0.12
                    else -> 0.20
                }
                wrRecoveryQualityPenaltyMult = minOf(wrRecoveryQualityPenaltyMult, collapseQualityMult)
                tags.add("wr_roll50_collapse_probe")
                checks.add(GateCheck("wr_roll50_collapse_probe", true, "roll50=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% quality=${candidate.setupQuality} size×${"%.2f".format(wrRecoveryQualityPenaltyMult)}"))
                ErrorLogger.info(
                    "FDG",
                    "🛑 WR_ROLL50_COLLAPSE_PROBE: ${ts.symbol} | roll50=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% quality=${candidate.setupQuality} size×${"%.2f".format(wrRecoveryQualityPenaltyMult)}"
                )
            } else if (isHighRecovery && !isAGrade) {
                // Soft penalty graduated by quality + band severity. Same
                // shape as the old hard-block: more punishment in
                // AGGRESSIVE band and for lower-grade setups.
                val bandMult = if (wrState.band == com.lifecyclebot.engine.WrRecoveryPartial.Band.AGGRESSIVE) 0.65 else 0.80
                val qualityMult = when (candidate.setupQuality) {
                    "B"  -> 0.95   // mild discount
                    "C"  -> 0.85   // bigger discount
                    "D"  -> 0.70   // largest discount
                    else -> 0.85
                }
                wrRecoveryQualityPenaltyMult = bandMult * qualityMult
                tags.add("wr_recovery_softened")
                tags.add("band_${wrState.band.name.lowercase()}")
                ErrorLogger.info(
                    "FDG",
                    "🚑 WR_RECOVERY_SOFT_PENALTY: ${ts.symbol} | band=${wrState.band.name} wr=${"%.1f".format(wrState.currentWr)}% roll=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% | quality=${candidate.setupQuality} | conf×size = ${"%.2f".format(wrRecoveryQualityPenaltyMult)}"
                )
            }
        } catch (_: Throwable) { /* recovery gate is best-effort; never block on internal error */ }

        // V5.9.343 — CLASSIC uses 1.0 floor (even lower than golden 3.0) so the
        // bot trades from first start per user directive. Modern keeps 8.0.
        val BOOTSTRAP_MIN_CONFIDENCE = if (classicMode) 1.0 else 8.0
        if (confidence < BOOTSTRAP_MIN_CONFIDENCE) {
            // V5.9.693 — Paper-mode bypass. In paper mode the bot MUST trade
            // to accumulate learning volume. A sub-1% confidence on a paper
            // entry is a nuisance filter, not a safety gate — real safety
            // (rug detection, liquidity collapse, ML rug probability) fires
            // downstream. Blocking here in paper mode starved Moonshot /
            // Manip / Express of entries while FDG allow=0 showed in the
            // funnel. LIVE mode keeps the hard floor.
            if (isPaperMode) {
                ErrorLogger.debug("FDG", "ℹ️ BOOTSTRAP_FLOOR_PAPER_BYPASS: ${ts.symbol} | conf=${confidence.toInt()}% < ${BOOTSTRAP_MIN_CONFIDENCE.toInt()}% → paper learn")
                tags.add("bootstrap_floor_paper_bypass")
                // fall through to normal scoring
            } else {
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
        // V5.9.616 — graduated floor:
        //   - <1000 trades: floor never engages (canBypassConfidenceFloors above
        //     handles this). Defensive belt-and-braces here.
        //   - 1000-3000 trades (learning phase): 22% floor — still loose.
        //   - 3000+ trades: 32% floor — original rule.
        val aiDegradedFloor = if (totalSettled < 3000) 22.0 else 32.0
        if (confidence < aiDegradedFloor && earlyAIDegraded && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% + AI_DEGRADED | floor=${aiDegradedFloor.toInt()}% | DEGRADED_AI_CONFIDENCE_FLOOR_VIOLATED")

            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "AI_DEGRADED_CONFIDENCE_FLOOR_${aiDegradedFloor.toInt()}%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("ai_degraded_confidence_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "AI_DEGRADED + conf < ${aiDegradedFloor.toInt()}% = REJECT",
                gateChecks = listOf(GateCheck("ai_degraded_conf_floor", false, "Degraded AI requires conf >= ${aiDegradedFloor.toInt()}%"))
            )
        }

        val toxicPatternFlags = mutableListOf<String>()
        if (isCGrade) toxicPatternFlags.add("quality_C")
        // V5.9.343 — CLASSIC: conf<20 (walk-back for trading-from-start).
        // MODERN: conf<28 (V5.9.266).
        val toxicConfThreshold = if (classicMode) 20.0 else 28.0
        if (confidence < toxicConfThreshold) toxicPatternFlags.add("conf<${toxicConfThreshold.toInt()}")
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
        // V5.9.696 — Per-trader execution floor override.
        // FluidLearningAI.getExecutionFloor() lerps $800→$10000 as learning progresses.
        // At ~35% learning it reaches ~$4480, which hard-blocks 94% of fresh pump.fun/
        // Raydium tokens (typical liq $2000-$4400) on EVERY trader — ShitCoin, Moonshot,
        // Treasury, all of them. This is catastrophic: EXEC=0, no new trades at all.
        //
        // Root cause: the lerp floor was designed for "mature" filtering but applies
        // identically to early-stage meme traders whose own scorers ALREADY gate on liq.
        // ShitCoin (V5.9.615) gates at $300 liq. Moonshot gates at $2000 (bootstrap).
        // FDG double-gating at $4480 above their own floors makes the whole system sterile.
        //
        // V5.9.722: Tighten FDG liquidity floor for meme traders.
        // Previous $1,500 flat floor was below the ShitCoin own-scorer's $2,000 mature floor,
        // meaning FDG never actually filtered anything above the baseline and let in
        // pump.fun bonding-curve tokens ($2,267-$3,086 liq) with zero real exit depth.
        //
        // ShitCoin: lerp $2,000 (bootstrap) → $5,000 (mature, 3000+ trades).
        //   Own scorer already gates $300→$2,000 — FDG sits ABOVE it as a quality gate.
        //   At 0 trades: FDG = $2,000 (same as ShitCoin's own mature floor, no regression).
        //   At 3000 trades: FDG = $5,000 (forces post-bonding-curve quality setups).
        // Moonshot: $3,000 flat.
        //   Own scorer gates $2,000 (bootstrap) / $15,000 (mature).
        //   FDG at $3,000 adds a middle-tier quality bar without sterility.
        val EXECUTION_FLOOR = when (tradingModeTag) {
            ModeSpecificGates.TradingModeTag.SHITCOIN ->
                // V5.9.788 — operator dump (build 2726): lerp(1500,4000) was
                // blocking 98.8% of all FDG decisions (4883/4940 blocks) because
                // the dominant pump.fun bonding-curve intake stream consistently
                // arrives in the $2,100-$2,200 liquidity band. With learningProgress
                // anywhere above ~25%, the floor lifted above $2,125 and rejected
                // every single fresh meme candidate. The original V5.9.727 tighten
                // was designed for the SHITCOIN's own scorer ($2K bootstrap floor),
                // but FDG was set ABOVE that — defeating the meme trader by design.
                //
                // V5.9.802 — operator audit (build 5.0.2742 snapshot): 97% of FDG
                // blocks are still LIQUIDITY_BELOW_EXECUTION_FLOOR even on
                // V5.9.801. Root cause: V5.9.793 swapped lastLiquidityUsd for
                // LiquidityClassifier.exitCapacityUsd which returns 0.0 for any
                // pump.fun bonding-curve token (no confirmed Raydium/Jupiter
                // pool). Combined with lerp(1000, 2500) — even floor=$1000 is
                // unreachable when exitCapacityUsd=0. Lower the floor band to
                // lerp(500, 1500) so the SHITCOIN lane can take learning
                // samples on the universe it's actually scanning. Live safety
                // still enforced via WATCHLIST_FLOOR + rugcheck + safety gates.
                lerp(500.0, 1_500.0, learningProgress)
            ModeSpecificGates.TradingModeTag.MOONSHOT ->
                3_000.0                                      // own scorer already gates $2k/$15k; add middle bar
            else -> FluidLearningAI.getExecutionFloor()     // Lerp floor for proven-token traders
        }

        // V5.9.793 — operator audit Item 5: FDG / live executor MUST gate
        // on exitCapacityUsd (the confirmed-pool subset), not on the raw
        // lastLiquidityUsd which conflates pump.fun BC estimates with
        // executable liquidity. A token priced solely off a pump.fun
        // bonding-curve quote with no Jupiter/Raydium/DexScreener pool
        // returns 0.0 here — so it can NEVER pass the execution floor
        // until a real pool is observed. Watchlist floor still uses
        // lastLiquidityUsd because discovery rules are deliberately
        // looser than execution rules.
        //
        // V5.9.802 — operator audit Fix (a): the V5.9.793 strict BC-only
        // exclusion is killing 97% of FDG decisions because the SHITCOIN
        // lane's entire universe is pump.fun bonding-curve tokens. For
        // SHITCOIN bootstrap (learningProgress < 0.5) fall back to
        // lastLiquidityUsd so the lane can collect samples.
        //
        // V5.9.803 — operator dump (build 5.0.2743 / V5.9.802 installed):
        // FDG block tally still shows 639 LIQUIDITY_BELOW_EXECUTION_FLOOR
        // blocks (vs 389 WR_RECOVERY_QUALITY_FLOOR). Forensic on FDG path
        // tags showed the rejected tokens are being routed via BLUECHIP
        // and TREASURY paths first, NOT SHITCOIN — so the V5.9.802
        // SHITCOIN-only bootstrap fallback never fires for them. Operator
        // mandate: "the fdg needs to start working from trade 1".
        // Resolution: extend the BC-only bootstrap fallback to ALL FDG
        // paths during the learning bootstrap window. The canonical bus
        // still tags every outcome with bcSimOnly so WR analytics stay
        // separable. Live mode still enforces strict exitCapacityUsd
        // via the live executor's own pre-flight pool check, which is
        // separate from FDG.
        val rawExitCap = try {
            com.lifecyclebot.engine.LiquidityClassifier.exitCapacityUsd(ts)
        } catch (_: Throwable) { ts.lastLiquidityUsd }
        // V5.9.1066 — operator directive: lift bootstrap-only restriction
        // on BC-fallback. Pump.fun streams flood the intake with bonding-
        // curve-only tokens (no Raydium/Jupiter pool yet) and after
        // `learningProgress >= 0.5` they were ALL hitting
        // LIQUIDITY_BELOW_EXECUTION_FLOOR (369/395 blocks in the V5.9.1065
        // snapshot = 93%). Operator accepts the risk that some buys will
        // be on BC-only tokens — the bot's learning will self-adjust per
        // lane. Live mode still has its own pre-flight pool check in the
        // executor that's separate from FDG.
        val exitCapacityUsd = if (rawExitCap <= 0.0) {
            ts.lastLiquidityUsd
        } else {
            rawExitCap
        }
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

        if (exitCapacityUsd < EXECUTION_FLOOR) {
            ErrorLogger.info("FDG", "👁️ LIQ_FLOOR: ${ts.symbol} | exitCap=\$${exitCapacityUsd.toInt()} (raw=\$${ts.lastLiquidityUsd.toInt()}) < \$${EXECUTION_FLOOR.toInt()} | WATCH_ONLY (no execution)")

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
                approvalReason = "Exit capacity \$${exitCapacityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} execution floor - WATCH ONLY",
                gateChecks = listOf(
                    GateCheck(
                        "liq_exec_floor",
                        false,
                        "exitCap \$${exitCapacityUsd.toInt()} (BC-only? ${try { com.lifecyclebot.engine.LiquidityClassifier.isBcSimOnly(ts) } catch (_: Throwable) { false }}) < \$${EXECUTION_FLOOR.toInt()} = shadow track only"
                    )
                )
            )
        }

        if (tradingModeStr.isNotBlank()) {
            // V5.9.1119 — use runtime authority, not config-only paper flag.
            // 3085 showed UI/runtime current=PAPER while circuit still emitted
            // LIQUIDITY_BELOW_FLOOR_3000. That means config.paperMode can be stale
            // on this FDG path; RuntimeModeAuthority is the source of truth.
            val circuitPaperMode = config.paperMode || try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
            val circuitBlockReason = ToxicModeCircuitBreaker.checkEntryAllowed(
                mode = tradingModeStr,
                source = ts.source,
                liquidityUsd = ts.lastLiquidityUsd,
                phase = ts.phase,
                memoryScore = earlyMemoryScore,
                isAIDegraded = earlyAIDegraded,
                confidence = confidence.toInt(),
                isPaperMode = circuitPaperMode
            )

            if (circuitBlockReason != null) {
                ErrorLogger.warn("FDG", "🚫 CIRCUIT_LOCAL_BLOCK: ${ts.symbol} | mode=$tradingModeStr | $circuitBlockReason")
                // V5.9.1133 — local FDG/ToxicMode blocks must not masquerade as
                // global execution state. 3100 showed one SHITCOIN low-liq token
                // rendering the whole snapshot as CIRCUIT_BREAKER even though
                // RuntimeDoctor no longer disables lanes and ToxicMode no longer
                // global-pauses liquidity floors. Only ToxicModeCircuitBreaker's
                // active EntryPause path may emit EXECUTION_STATE_BLOCKED.
                val globalPause = try { ToxicModeCircuitBreaker.currentEntryPause() } catch (_: Throwable) { null }
                if (globalPause?.active == true) {
                    try { ToxicModeCircuitBreaker.emitExecutionStateBlockedIfDue(ts.symbol, "FinalDecisionGate") } catch (_: Throwable) {}
                } else {
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "FDG_LOCAL_BLOCK",
                            "mode=$tradingModeStr symbol=${ts.symbol} reason=$circuitBlockReason"
                        )
                    } catch (_: Throwable) {}
                }

                // V5.9.1564 — PAPER bootstrap must not hard-veto ToxicMode local
                // blocks (low-liq/phase/source/memory). The snapshot showed 6h
                // runtime with FDG/CIRCUIT_BREAKER dominating while current=PAPER.
                // In paper, local ToxicMode risk is labelled telemetry so the bot
                // keeps producing samples; only true global emergency pause blocks.
                val liveLocalModeFreeze = !circuitPaperMode && globalPause?.active != true &&
                    !circuitBlockReason.contains("EMERGENCY_STOP", ignoreCase = true) &&
                    (circuitBlockReason.contains("MODE_FROZEN", ignoreCase = true) ||
                        circuitBlockReason.contains("LOCAL", ignoreCase = true))
                if ((circuitPaperMode || liveLocalModeFreeze) && globalPause?.active != true && !circuitBlockReason.contains("EMERGENCY_STOP", ignoreCase = true)) {
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            if (circuitPaperMode) "PAPER_CIRCUIT_SOFT_ALLOW" else "LIVE_CIRCUIT_SOFT_ALLOW",
                            "mode=$tradingModeStr symbol=${ts.symbol} reason=$circuitBlockReason"
                        )
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (circuitPaperMode) "PAPER_CIRCUIT_SOFT_ALLOW" else "LIVE_CIRCUIT_SOFT_ALLOW")
                    } catch (_: Throwable) {}
                } else {
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
        }

        val brainTrades = brain?.getTradeCount() ?: 0
        val sessionTrades = currentConditions.totalSessionTrades
        val fluidTrades = try {
            FluidLearning.getTradeCount()
        } catch (_: Exception) {
            0
        }

        // V5.9.1476 (spec item 6) — canonical learning must be sourced from CLOSED
        // trade outcomes only, never from raw executed/attempted counts. learning_uses
        // (effectiveTradeCount) drives the learning-phase ladder; if it is allowed to
        // exceed the number of CLOSED canonical outcomes, the phase advances on trades
        // that have not actually produced a labelled win/loss yet (blocked/deferred/
        // probe/observation events were inflating brain/fluid). Cap learning_uses at
        // the canonical closed count whenever canonical data exists, so the ladder can
        // never run ahead of real settled outcomes. Before any canonical settles we
        // fall back to the learner counters so the bootstrap phase still progresses.
        val rawLearnerMax = maxOf(brainTrades, fluidTrades, canonicalDecisive)
        val effectiveTradeCount = if (canonicalDecisive > 0)
            minOf(rawLearnerMax, canonicalDecisive)
        else
            rawLearnerMax
        val rawExecutedCount = sessionTrades
        // V5.9.1136 — use canonical settled WR for FDG learning pressure.
        // BotBrain/Fluid counters can lag or represent only one learner; the journal
        // stats are what the operator sees and what actually proves/denies edge.
        val winRate = canonicalWr.takeIf { canonicalDecisive > 0 } ?: (brain?.getRecentWinRate() ?: 50.0)
        val adjusted = getAdjustedThresholds(effectiveTradeCount, winRate)

        val countersMatch = brainTrades == fluidTrades
        val executionMismatch = rawExecutedCount > effectiveTradeCount + 2
        val isPhaseTransition = effectiveTradeCount == 0 || effectiveTradeCount == 10 || effectiveTradeCount == 30 || effectiveTradeCount == 51

        if (isPhaseTransition || !countersMatch || executionMismatch) {
            // V5.9.1476 (spec item 6) — counters measure DISTINCT stages; a numeric
            // difference is EXPECTED, not a bug. Annotate each so the difference is
            // self-explaining instead of a bare LEARNING_MISMATCH alarm:
            //   executed   = raw buy attempts this session (incl. ones later closed)
            //   classified = BotBrain labelled outcomes (lags executed until close)
            //   fluid      = FluidLearning trade count (its own close cadence)
            //   canonical  = CLOSED journal decisive trades (the ground truth)
            //   learning_uses = phase-ladder input, now CAPPED at canonical closed
            // Only flag when learning_uses would EXCEED canonical closed outcomes —
            // that is the one combination that actually corrupts the ladder. With the
            // cap above that can no longer happen, so this becomes an explanatory
            // breakdown rather than a recurring warning.
            val counterDetail = "executed=$rawExecutedCount(attempts) | classified=$brainTrades(brain) | fluid=$fluidTrades(fluid) | canonical=$canonicalDecisive(closed) | learning_uses=$effectiveTradeCount(<=closed)"
            val learningOverrun = effectiveTradeCount > canonicalDecisive && canonicalDecisive > 0
            val mismatchNote = when {
                learningOverrun -> " ⚠️ LEARNING_OVERRUN ($counterDetail)"  // should be unreachable post-cap
                executionMismatch -> " ℹ️ EXECUTION_AHEAD: attempts outpace settled closes — normal in bootstrap ($counterDetail)"
                !countersMatch -> " ℹ️ COUNTER_BREAKDOWN ($counterDetail)"
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
            // V5.9.1214 — paper must learn the ugly low-RC moonshots too.
            // Operator example: SANDBOX rode 15k → 4M with RC score 6. If paper
            // hard-blocks 2..10, it never sees that edge and cannot transition
            // intelligently to live. TokenSafetyChecker already applies soft
            // penalties for low RC; in PAPER only confirmed score 0 is fatal.
            0
        } else {
            // V5.9.495n — operator: "live gate needs to come down to rc 1
            // and $2000". Lower bound dropped from 3 → 0 so the FDG
            // rugcheck gate matches the new TradeAuthorizer floor (only
            // RC=0 confirmed-rug blocks). Upper cap unchanged at 15.
            val baseThreshold = (brain?.learnedRugcheckThreshold ?: 1).coerceIn(0, 10)
            (baseThreshold * modeMultipliers.rugcheckMultiplier).toInt().coerceIn(0, 15)
        }

        // V5.9.175 — paper floor lowered from 30 to 10 because the user
        // directive is "admit everything above D+" — 30% buy-pressure was
        // blocking fresh launches that often start at 20-28% before their
        // first wave of buyers arrives. The fluid size multiplier + FDG
        // confidence floor handle weak setups downstream, so this gate no
        // longer needs to be the hard cliff.
        // V5.9.718 — floor raised from 10.0 → 30.0 in lenient/paper mode.
        // Previously, bootstrap paper trades could enter on 10% buy pressure (90% sell).
        // That's a token in free-fall. 30% is the minimum signal of actual buying interest.
        // This is the clearest single predictor of meme trade outcome: is anyone buying?
        val buyPressureThreshold = if (lenient) {
            (adjusted.buyPressureMin * modeMultipliers.entryScoreMultiplier * 0.8).coerceAtLeast(30.0)
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
            // V5.9.689 — score=1 is the RC_PENDING sentinel value returned by the
            // rugcheck API before verification completes. SafetyChecker sets
            // rugcheckStatus="CONFIRMED" when the API responds (even score=1),
            // so FDG was treating it as a confirmed low score and hard-blocking.
            // V5.9.1585 — score=1 is RC_PENDING, not confirmed danger. Live must
            // treat it as a penalty/probe-size condition, not HARD_BLOCK_RUGCHECK_1.
            // Confirmed rug score 0 remains fatal; confirmed low scores 2..threshold
            // remain strict in LIVE. This mirrors FatalRiskChecker/ExecutableOpenGate.
            config.paperMode && rugcheckScore < 0 -> {
                tags.add("missing_rc_paper_learn")
                false
            }
            rugcheckStatus == "CONFIRMED" && config.paperMode && rugcheckScore == 0 -> true
            rugcheckStatus == "CONFIRMED" && config.paperMode && rugcheckScore in 1..10 -> {
                tags.add("low_rc_paper_learn")
                false
            }
            rugcheckStatus == "CONFIRMED" && rugcheckScore == 1 -> {
                tags.add(if (config.paperMode) "rc_pending_paper_learn" else "rc_pending_live_probe")
                false
            }
            rugcheckStatus == "CONFIRMED" && rugcheckScore == 0 -> true
            rugcheckStatus == "CONFIRMED" && rugcheckScore in 2..rugcheckThreshold -> true
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
            if (rugcheckStatus == "CONFIRMED" && rugcheckScore == 1) {
                tags.add("rugcheck_pending_size_cap")
                checks.add(GateCheck("rugcheck_pending_penalty", true, "RC_PENDING score=1 → penalty/probe size"))
            }
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

        // SAFETY BLOCK: Freeze authority = dev can freeze your tokens and drain your position.
        // This is a genuine rug vector. ALWAYS hard block in live mode regardless of edge.
        // V5.9.291: restored to unconditional live hard block.
        if (blockReason == null && !config.paperMode && ts.safety.freezeAuthorityDisabled == false) {
            blockReason = "HARD_BLOCK_FREEZE_AUTHORITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("freeze_auth", false, "freezeAuth=enabled (live mode — rug vector)"))
            tags.add("freeze_auth")
        } else if (blockReason == null) {
            checks.add(GateCheck("freeze_auth", true, null))
        }

        var baseSignalMismatchIgnoredForLane = false
        if (blockReason == null && candidate.blockReason.isNotEmpty()) {
            val laneNameForBaseSignal = tradingModeTag?.name
            val staleBaseSignalBlock = candidate.blockReason.startsWith("Signal is ") &&
                candidate.blockReason.endsWith(", not BUY") &&
                laneNameForBaseSignal != null &&
                laneNameForBaseSignal != "STANDARD"
            if (staleBaseSignalBlock && laneNameForBaseSignal != null) {
                // V5.9.1124 — 3090 showed FDG_BASE_SIGNAL_BLOCK_IGNORED=43k.
                // A WAIT/SELL base signal is not executable. Specialist lanes may
                // keep telemetry elsewhere, but FDG must not authorize or preAuth.
                blockReason = candidate.blockReason
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("candidate_base_signal", false, "hard_block_for_lane=$laneNameForBaseSignal: ${candidate.blockReason}"))
                tags.add("base_signal_hard_block:$laneNameForBaseSignal")
            } else {
                blockReason = candidate.blockReason
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("candidate_block", false, candidate.blockReason))
            }
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
        val rsiLenient = ModeLeniency.useLenientGates(config.paperMode)
        if (blockReason == null && currentRsi > 90.0) {
            if (!config.paperMode && !rsiLenient) {
                // STRICT LIVE ONLY: Hard block RSI > 90
                // V5.9.291: Proven-edge live uses paper behaviour (penalty + tiny size, not block)
                blockReason = "RSI_OVERBOUGHT_${currentRsi.toInt()}"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("rsi_overbought", false, "RSI=${currentRsi.toInt()} > 90 (extreme overbought, strict-live block)"))
                tags.add("rsi_overbought_block")
                ErrorLogger.warn("FDG", "🚫 RSI HARD BLOCK: ${ts.symbol} | RSI=${currentRsi.toInt()} > 90 | EXTREME OVERBOUGHT → BLOCK")
            } else {
                // PAPER or PROVEN-EDGE LIVE: Allow with severe penalty and tiny size for learning
                checks.add(GateCheck("rsi_overbought", true, "RSI=${currentRsi.toInt()} > 90 → LEARNING with penalty"))
                tags.add("rsi_overbought_learn")
                ErrorLogger.info("FDG", "🎓 RSI LEARN: ${ts.symbol} | RSI=${currentRsi.toInt()} > 90 | severe penalty for learning")
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
        if (rugcheckStatus == "CONFIRMED" && rugcheckScore == 1) {
            softPenaltyScore += if (config.paperMode) 5 else 12
            sizeMultiplier *= if (config.paperMode) 0.70 else 0.35
        }
        var isProbeCandidate = false
        var behaviorPenalty = 0
        var behaviorSizeMultiplier = 1.0
        var behaviorProbe = false

        // V5.9.970 — z V5.9.911 TokenSocialScorer revival (was file-dead).
        // Composes a soft-shape trust multiplier in [0.85, 1.05] from
        // DexScreener-seeded socials/websites cached in BirdeyeMetaDataProvider.
        // Asymmetric: missing socials = mild penalty, rich socials = mild boost.
        // Fail-open (1.0) when no meta cached for the mint.
        try {
            val socialTrust = com.lifecyclebot.engine.TokenSocialScorer.getTrustForMint(ts.mint)
            if (socialTrust != 1.0) {
                sizeMultiplier *= socialTrust
                tags.add("social_trust_${"%.2f".format(socialTrust)}")
                if (socialTrust < 0.95) softPenaltyScore += 5
            }
        } catch (_: Throwable) { /* fail-open */ }

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

                // V5.9.874 — RICH-TIER OBSERVATION (no behavioral influence).
                // The legacy fine/exact/broad shouldHardBlock above queries
                // underscore-delimited keys that have ZERO writes (legacy path
                // gated off since V5.9.790). The canonical bus writes pipe-
                // delimited rich keys that nobody queries (V5.9.873 finding).
                //
                // This block calls lookupRichTier() with the same TokenState
                // and the prior FDG verdict (if any) from SymbolicVerdictRegistry,
                // and logs the result for telemetry. Score-influence stays OFF
                // until V5.9.875 confirms the rich tier finds real samples.
                try {
                    val priorVerdict = try {
                        com.lifecyclebot.engine.SymbolicVerdictRegistry.peek(ts.mint) ?: ""
                    } catch (_: Throwable) { "" }
                    val obsSource = when (tradingModeTag?.name) {
                        "SHITCOIN" -> com.lifecyclebot.engine.TradeSource.SHITCOIN
                        "MOONSHOT" -> com.lifecyclebot.engine.TradeSource.MOONSHOT
                        "MANIP" -> com.lifecyclebot.engine.TradeSource.MANIP
                        "EXPRESS" -> com.lifecyclebot.engine.TradeSource.EXPRESS
                        "CYCLIC" -> com.lifecyclebot.engine.TradeSource.CYCLIC
                        "COPY_TRADE", "COPYTRADE" -> com.lifecyclebot.engine.TradeSource.COPYTRADE
                        "TREASURY" -> com.lifecyclebot.engine.TradeSource.TREASURY
                        "BLUECHIP" -> com.lifecyclebot.engine.TradeSource.BLUECHIP
                        "ALTTRADER", "MARKETS" -> com.lifecyclebot.engine.TradeSource.MARKETS
                        else -> com.lifecyclebot.engine.TradeSource.V3
                    }
                    val obsMode = try {
                        com.lifecyclebot.engine.TradeMode.valueOf(tradingModeTag?.name ?: "STANDARD")
                    } catch (_: Throwable) {
                        com.lifecyclebot.engine.TradeMode.STANDARD
                    }
                    val obsEnv = if (config.paperMode)
                        com.lifecyclebot.engine.TradeEnvironment.PAPER
                    else
                        com.lifecyclebot.engine.TradeEnvironment.LIVE
                    val richObs = BehaviorLearning.lookupRichTier(
                        ts = ts,
                        mode = obsMode,
                        source = obsSource,
                        env = obsEnv,
                        symbolicVerdict = priorVerdict,
                    )
                    if (richObs.sampleSize > 0) {
                        ErrorLogger.info(
                            "FDG",
                            "🔬 RICH-OBS ${ts.symbol}: n=${richObs.sampleSize} " +
                            "wr=${richObs.winRate.toInt()}% adj=${richObs.scoreAdjustment} " +
                            "conf=${(richObs.confidence * 100).toInt()}% " +
                            "legacy=${if (behaviorBlock != null) "BLOCK" else "OK"} " +
                            "verdict=${priorVerdict.take(20)}"
                        )
                    } else {
                        // log misses sparsely (1 in 50) to confirm path is firing
                        if ((System.currentTimeMillis() % 50L) == 0L) {
                            ErrorLogger.debug("FDG", "RICH-OBS ${ts.symbol}: no rich sample yet (sample<3)")
                        }
                    }
                } catch (t: Throwable) {
                    ErrorLogger.debug("FDG", "rich-obs error: ${t.message?.take(80)}")
                }


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

        // V5.9.721-FIX: DANGER_ZONE penalty-only extended through LEARNING phase.
        // isBootstrapPhase = progress < 0.25 — this is too narrow. At progress=0.47
        // (1780 trades, 22% WR) the bot is well inside LEARNING phase but past bootstrap,
        // so DANGER_ZONE_TIME was hard-blocking instead of penalising. With a self-poisoning
        // loop (22% WR → every hour flagged danger → no entries → slower learning recovery),
        // the hard block is counterproductive. Extend penalty-only behavior until
        // expert phase (>3000 settled trades AND progress >= 0.60) so the bot can
        // still enter, at reduced size, during hours the time-AI thinks are bad —
        // letting it either disprove the heuristic or learn to avoid them naturally.
        val isEarlyLearningPhase = isBootstrapPhase || learningProgress < 0.60 || totalTradesForBypass < 3000

        var dangerZonePenalty = 0
        if (blockReason == null) {
            try {
                val isDanger = TimeOptimizationAI.isDangerZone()
                if (isDanger) {
                    if (isEarlyLearningPhase) {
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
                // V5.9.291: Route unknown-phase gate through ModeLeniency so proven-edge
                // live behaves like paper (allowed with tag, no hard block).
                val phaseLenient = ModeLeniency.useLenientGates(config.paperMode)
                if (phaseLenient) {
                    checks.add(GateCheck("phase_filter", true, "LENIENT: unknown phase allowed [${currentAdjusted.learningPhase}]"))
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
        // V5.0.3676 — operator TUNING patch (recovery). Groq is treated as
        // OPTIONAL enrichment only. Spec: "If Groq sr < 50%, set narrative
        // score to neutral and continue." The Groq lockout was rate-limiting
        // FDG into an entry stall (groq sr=0%, 944ms p95 → workers blow past
        // the 8s lease wall). Skip the network call entirely when Groq is
        // either in ApiBackoff lockout or its rolling success rate < 50%.
        // Other lanes (DexScreener/Jupiter/Helius) are unaffected — only this
        // narrative branch short-circuits, and it stays a soft enrichment.
        val groqHealthy: Boolean = try {
            if (com.lifecyclebot.engine.ApiBackoff.isLockedOut("groq")) false
            else {
                val sr = try { com.lifecyclebot.engine.ApiHealthMonitor.successRate("groq") } catch (_: Throwable) { 1.0 }
                sr >= 0.50
            }
        } catch (_: Throwable) { true }
        if (blockReason == null && config.groqApiKey.isNotBlank() && groqHealthy) {
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
            // V5.0.3676 — distinguish neutral-skip reasons in forensics so an
            // operator can immediately see whether narrative was skipped for
            // no key, or because the Groq lockout / SR<50% guard kicked in.
            val skipReason = when {
                config.groqApiKey.isBlank() -> "no key"
                !groqHealthy -> "groq health-gated (lockout or sr<50%) — neutral"
                else -> "skipped"
            }
            checks.add(GateCheck("narrative", true, skipReason))
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
                    UnifiedNarrativeAI.analyze(ts.symbol, ts.name, "", ts.mint, config.groqApiKey)
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
        val adjustedConfidence = ((confidence + narrativeAdjustment + orthogonalBonus) * wrRecoveryQualityPenaltyMult).coerceIn(0.0, 100.0)
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
                // V5.0.3676 — operator TUNING patch (recovery). LOW_CONFIDENCE in
                // PAPER mode is now a SIZE/SCORE PENALTY (dust probe), not a
                // hard veto. The previous hard block was a top FDG choke reason
                // even though the operator spec is explicit:
                //   "LOW_CONFIDENCE_0% should become a size/score penalty in
                //    paper mode, not a hard block, unless route/liquidity is
                //    impossible."
                // Route/liquidity safety is unchanged (TokenSafetyChecker still
                // owns the hard floor + UNKNOWN block). In LIVE mode the
                // confidence floor remains a hard block as before.
                if (mode == TradeMode.PAPER) {
                    val dustMult = when {
                        adjustedConfidence < 5  -> 0.20
                        adjustedConfidence < 12 -> 0.30
                        else                    -> 0.45
                    }
                    sizeMultiplier *= dustMult
                    checks.add(
                        GateCheck(
                            "confidence",
                            true,
                            "PAPER DUST PROBE: conf=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}% → size×${dustMult.format(2)} (no hard block)"
                        )
                    )
                    tags.add("paper_low_conf_dust_probe")
                    if (isBootstrap) tags.add("bootstrap_phase")
                    ErrorLogger.info("FDG", "🔬 PAPER LOW-CONF DUST PROBE: ${ts.symbol} | conf=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}% → size×${dustMult.format(2)}")
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

        var finalSize = proposedSizeSol * wrRecoveryQualityPenaltyMult  // V5.9.809/1223: soft WR-recovery/collapse probe size penalty

        // V5.9.1136 — make learning bite without choking scanner/lane volume.
        // When canonical WR is under phase target, learned losing buckets reduce size;
        // only deep-deficit + weak evidence in a proven danger bucket blocks execution.
        try {
            val bucket = LosingPatternMemory.stats(laneName, laneScoreBanded)  // V5.9.1299 lane score
            if (bucket.sample > 0) {
                val lossRate = bucket.lossRatePct
                val genericPressure = when {
                    bucket.isDangerous && deepLearningDeficit -> 0.35
                    bucket.isDangerous && moderateLearningDeficit -> 0.55
                    bucket.isDangerous -> 0.70
                    bucket.losses >= 5 && lossRate >= 65.0 && moderateLearningDeficit -> 0.75
                    else -> 1.0
                }
                // V5.9.1571 — tuning-only WR recovery. The dashboard shows matured
                // negative danger buckets (TREASURY|S0-10, SHITCOIN|S61+) still taking
                // meaningful losses. LosingPatternMemory already computes the loss-count
                // scaled multiplier (down to ×0.02), but FDG was only applying the weak
                // generic ×0.35/0.55/0.70 pressure. Compose both and take the stricter
                // soft-shape. No veto, no lane disable, no live/paper plumbing change.
                val learnedBucketMult = try { LosingPatternMemory.recommendedSizeMult(laneName, laneScoreBanded) } catch (_: Throwable) { 1.0 }
                val learnedPressure = minOf(genericPressure, learnedBucketMult)
                if (learnedPressure < 1.0) {
                    val originalSize = finalSize
                    finalSize = (finalSize * learnedPressure).coerceAtLeast(0.01)
                    tags.add("learning_recovery_shaped")
                    tags.add("danger_bucket:${LosingPatternMemory.bucketKey(laneName, laneScoreBanded)}")
                    checks.add(GateCheck("learned_bucket", true, "bucket n=${bucket.sample} loss=${lossRate.toInt()}% mean=${bucket.meanPnl.format(1)}% wr=${canonicalWr.format(1)} target=${canonicalTargetWr.format(1)} mult=${learnedPressure.format(2)} size ${originalSize.format(3)}→${finalSize.format(3)}"))
                    ErrorLogger.info("FDG", "🧠 LEARNING_RECOVERY_SHAPED ${ts.symbol} lane=$laneName bucket=${LosingPatternMemory.bucketKey(laneName, laneScoreBanded)} n=${bucket.sample} loss=${lossRate.toInt()}% mean=${bucket.meanPnl.format(1)}% wr=${canonicalWr.format(1)}/${canonicalTargetWr.format(1)} size×${learnedPressure.format(2)}")
                }
                val weakEvidence = candidate.setupQuality !in listOf("A+", "A", "B") && candidate.aiConfidence < 35.0 && candidate.entryScore < 25.0
                if (blockReason == null && bucket.isDangerous && deepLearningDeficit && weakEvidence) {
                    // V5.9.1321 — Train-First Learning Policy correction (Base44 directive).
                    // TRAINABILITY ≠ EXECUTABILITY. Danger buckets ROUTE to training;
                    // they do not hard-block. Only invalid/unsafe data hard-blocks.
                    val scoreBand = com.lifecyclebot.engine.LosingPatternMemory.bucketKey(laneName, laneScoreBanded).substringAfter('|', "")
                    val routed = com.lifecyclebot.engine.learning.FdgRouteVerdict.routeLearnedDangerBucket(
                        lane = laneName,
                        scoreBand = scoreBand,
                        evidenceLabel = "n=${bucket.sample} loss=${lossRate.toInt()}% wr=${canonicalWr.format(1)}/${canonicalTargetWr.format(1)}",
                    )
                    com.lifecyclebot.engine.learning.LanePolicy.noteRetrainingSample(laneName, scoreBand)
                    if (routed.proceedToOpen) {
                        val originalSize2 = finalSize
                        finalSize = (finalSize * routed.sizeMultiplier).coerceAtLeast(0.01)
                        tags.add("learning_evidence_required")
                        tags.add("route:${routed.verdict.tag}")
                        checks.add(GateCheck("learned_bucket_evidence", true, "${routed.routeReasonForLog} | size ${originalSize2.format(3)}→${finalSize.format(3)}"))
                    } else {
                        // V5.9.1325 — TRAIN-FIRST INVARIANT.
                        // Operator: "FDG is final authority. never stop trading.
                        // 1000+ quality trades/day. learn the right way."
                        // Even when the verdict says non-executable, demote to
                        // a micro paper probe (size 0.01) instead of blocking.
                        // Hard-safety failures are blocked by other gates above.
                        val originalSize2 = finalSize
                        finalSize = 0.01
                        tags.add("learning_evidence_required")
                        tags.add("route:${routed.verdict.tag}")
                        tags.add("train_first_micro_probe")
                        checks.add(GateCheck("learned_bucket_evidence", true, "${routed.routeReasonForLog} | TRAIN_FIRST_MICRO ${originalSize2.format(3)}→${finalSize.format(3)}"))
                        // V5.9.1322 — Build B: record this as a NoTradeObservation
                        // so SHADOW_TRACK / TRAIN_ONLY routes still feed the model.
                        try {
                            com.lifecyclebot.engine.learning.NoTradeObservationStore.recordBlock(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                lane = laneName,
                                scoreBand = scoreBand,
                                score = candidate.entryScore.toInt(),
                                confidence = candidate.aiConfidence.toInt(),
                                entryLiqUsd = ts.lastLiquidityUsd,
                                entryMcapUsd = ts.lastMcap,
                                entryPrice = ts.lastPrice,
                                source = ts.source.ifEmpty { "UNKNOWN" },
                                blockReason = routed.routeReasonForLog,
                                verdictTag = routed.verdict.tag,
                            )
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) { /* fail-open: learned shaping must not starve execution */ }

        // V5.9.1358 — DAMAGE-CONTROL REMOVED (operator mandate: never throttle,
        // never cap-to-dust, never disable a lane). The old P0.6 gate slammed
        // size to a flat 0.02 micro on a 24-of-25 cadence whenever the meme
        // window was red — that is stealth-disabling a lane and stomping on the
        // brain's own predictive sizing. The correct mechanism is already live
        // below: UnifiedPolicyHead.conviction() scales size by the LEARNED
        // win-probability for THIS exact context, so capital concentrates on the
        // sweet spots and stays small (but never zero) in weak pockets — every
        // lane keeps trading and learning from trade one. We only OBSERVE the
        // window now (telemetry), shaping is the brain's job.
        try {
            if (com.lifecyclebot.engine.runtime.DamageControlGate.isActive()) {
                tags.add("dc_window_red_observed")
            }
        } catch (_: Throwable) { /* observe-only, never shapes */ }

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
            AICrossTalk.analyzeCrossTalk(ts, isOpenPosition = false)
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

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.931 — COLLECTIVE BRAIN soft-shape (the biggest dropped-AGI gap).
        //
        // Operator deep-audit V5.9.929: CollectiveLearning UPLOADS every
        // pattern outcome to Turso and DOWNLOADS them back into cachedPatterns
        // every 15 min — but ONLY isBlacklisted (binary veto) was ever
        // consulted. Every other AATE instance in the swarm has been pooling
        // win/loss patterns for months, this bot pulled them down, never
        // read from the cache. Pure write-mostly intelligence.
        //
        // Wire-up: at the same composition point as AICrossTalk's shape,
        // ask the collective whether this candidate's pattern signature
        // (entryPhase_tradingMode × discoverySource × liquidityBucket ×
        // emaTrend) has won or lost across the swarm. Only act on RELIABLE
        // patterns (≥10 swarm trades, CollectivePattern.isReliable).
        //
        // The signature tuple is SYMMETRIC with what V3EngineManager.
        // recordOutcome uploads (V3EngineManager.kt:681), so the swarm
        // wisdom we read back is in the same shape we contribute.
        //
        // Shape (per doctrine #86 — soft-shape only, no veto):
        //   wr ≥ 65 (strong winner pattern) → size × 1.20
        //   wr ≥ 55                         → size × 1.10
        //   wr ≤ 35 (losing pattern)        → size × 0.80
        //   wr ≤ 25 (strong loser)          → size × 0.60
        //   else (35..55)                   → 1.00 (no opinion)
        //
        // Bounded floor 0.01 SOL; never blocks. Fail-open per FDG doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                val tradingMode = ts.position.tradingMode.ifBlank { "STANDARD" }
                val entryPhase = ts.phase.ifBlank { "idle" }.uppercase()
                val patternType = "${entryPhase}_${tradingMode}"
                val discoverySource = ts.source.ifBlank { "UNKNOWN" }
                val liquidityBucket = when {
                    ts.lastLiquidityUsd < 5_000 -> "MICRO"
                    ts.lastLiquidityUsd < 25_000 -> "SMALL"
                    ts.lastLiquidityUsd < 100_000 -> "MID"
                    else -> "LARGE"
                }
                val emaTrend = ts.meta.emafanAlignment.ifBlank { "NEUTRAL" }

                val pattern = com.lifecyclebot.collective.CollectiveLearning.getPatternStats(
                    patternType = patternType,
                    discoverySource = discoverySource,
                    liquidityBucket = liquidityBucket,
                    emaTrend = emaTrend,
                )

                if (pattern != null && pattern.isReliable) {
                    val wr = pattern.winRate
                    val collectiveMult = when {
                        wr >= 65.0 -> 1.20
                        wr >= 55.0 -> 1.10
                        wr <= 25.0 -> 0.60
                        wr <= 35.0 -> 0.80
                        else        -> 1.00
                    }
                    if (collectiveMult != 1.00) {
                        val originalSize = finalSize
                        finalSize = (finalSize * collectiveMult).coerceIn(0.01, 1.0)
                        val direction = if (collectiveMult > 1.0) "boosted" else "reduced"
                        tags.add("size_${direction}_collective")
                        checks.add(
                            GateCheck(
                                "collective_brain",
                                true,
                                "Swarm $direction ${originalSize.format(3)} → ${finalSize.format(3)} " +
                                "(wr=${wr.format(0)}% n=${pattern.totalTrades} sig=$patternType/$discoverySource/$liquidityBucket/$emaTrend)"
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) { /* fail-open — collective is soft-shape only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.934 — AI STARTUP COORDINATOR soft-shape (4th dormant subsystem).
        //
        // Operator V5.9.929 audit (audit_v5.9.928_dormant_intelligence.md):
        // AIStartupCoordinator.isTradingAllowed() has ZERO callers anywhere
        // in the codebase. Pre-934 the flag flipped, the bot never read it.
        // Pure dormant safety subsystem.
        //
        // Per doctrine #86: not a hard veto (AI degradation ≠ rug/freeze/
        // BlockFatal/SIZE_ZERO/-15% floor). Treat as soft-shape:
        //   isTradingAllowed = false (AI degraded/critical-failed) → size × 0.5
        //   isTradingAllowed = true  → 1.00 (no opinion)
        //
        // Paper mode bypass — per doctrine #87.1 (dropped signal = dropped
        // AGI sample), paper continues at full size so learning never stops
        // even when AI subsystems are degraded. Live mode honors the dim.
        //
        // Bounded floor 0.01; fail-open per FDG doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            if (mode == TradeMode.LIVE) {
                val aiReady = com.lifecyclebot.v3.core.AIStartupCoordinator.isTradingAllowed()
                if (!aiReady) {
                    val originalSize = finalSize
                    finalSize = (finalSize * 0.5).coerceAtLeast(0.01)
                    tags.add("size_reduced_ai_degraded")
                    checks.add(
                        GateCheck(
                            "ai_startup",
                            true,
                            "AI degraded — size reduced ${originalSize.format(3)} → ${finalSize.format(3)} (×0.5 soft-shape)"
                        )
                    )
                }
            }
        } catch (_: Throwable) { /* fail-open — startup coordinator is soft-shape only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.1378 (P0 #7) — DATA_DEGRADED SOFT-SHAPE.
        // Entry scores are only as trustworthy as the price/liquidity feeds.
        // When PRICE-CRITICAL providers (dexscreener/geckoterminal/birdeye) are
        // degraded, mcap/liq/score can be stale → entry is a blind bet. Per
        // doctrine #86 SOFT-SHAPE not hard veto; per #87.1 PAPER full-size so
        // learning never stops. LIVE size dampened ∝ #feeds down. Floor 0.01.
        // ═══════════════════════════════════════════════════════════════════
        try {
            if (mode == TradeMode.LIVE) {
                val snap = com.lifecyclebot.engine.ApiHealthMonitor.snapshot()
                val priceCritical = listOf("dexscreener", "geckoterminal", "birdeye")
                var degraded = 0
                val degradedNames = mutableListOf<String>()
                for (host in priceCritical) {
                    val hs = snap[host] ?: continue
                    val samples = hs.successes.get() + hs.failures4xx.get() + hs.failures5xx.get() + hs.networkErrors.get()
                    if (samples >= 8 && hs.successRate() < 0.50) { degraded++; degradedNames.add(host) }
                }
                if (degraded > 0) {
                    val dataMult = when (degraded) { 1 -> 0.75; 2 -> 0.50; else -> 0.35 }
                    val originalSize = finalSize
                    finalSize = (finalSize * dataMult).coerceAtLeast(0.01)
                    tags.add("size_reduced_data_degraded_${degraded}")
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FDG_DATA_DEGRADED_SOFTSHAPE|feeds=${degraded}") } catch (_: Throwable) {}
                    checks.add(GateCheck("data_degraded", true,
                        "Price feeds degraded (${degradedNames.joinToString(",")}) — size ${originalSize.format(3)} → ${finalSize.format(3)} (×$dataMult)"))
                }
            }
        } catch (_: Throwable) { /* fail-open — data-health is soft-shape only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.939 — TIER-MODULATED SOFT-SHAPE HELPER.
        //
        // The 5 Birdeye soft-shapes (V5.9.937-938) below all use a baseMult
        // computed from raw signal data. Pre-V5.9.939 the baseMult was
        // applied identically across tiers — a volatility-INSANE shape of
        // 0.60 hit a $500 micro the same as a $500M chip. That's wrong:
        //
        //   On a MICRO ($500-$50K): high volatility is EXPECTED. Damp the
        //   penalty. No social? Memes don't need socials. Damp the penalty.
        //
        //   On a SCALED+ ($50M+): high volatility is CATASTROPHE. Amplify
        //   the penalty. No social? Massive red flag. Amplify the penalty.
        //
        // tierShape(baseMult, mcapUsd):
        //   - For penalty shapes (baseMult < 1.0):
        //       MICRO     → softer penalty   (mult moves toward 1.0)
        //       STANDARD  → 0.85× of penalty distance
        //       GROWTH    → exact baseMult (neutral)
        //       SCALED    → 1.15× of penalty distance
        //       BLUECHIP  → 1.30× of penalty distance (mult further from 1.0)
        //
        //   - For bonus shapes (baseMult > 1.0):
        //       MICRO     → mild dampening  (rapid-pump bonuses are dime-a-dozen)
        //       BLUECHIP  → amplified       (rare high-quality survivors deserve more)
        //
        // Floor 0.40, ceiling 1.30 to keep composed product safe under
        // multiple stacked penalties on the same token.
        // ═══════════════════════════════════════════════════════════════════
        val candidateMcap = ts.lastMcap
        fun tierShape(baseMult: Double, mcap: Double): Double {
            if (mcap <= 0.0) return baseMult            // unknown, no opinion
            val tierBias = when {
                mcap <      50_000.0 -> 0.55            // MICRO   dampen 45%
                mcap <     500_000.0 -> 0.85            // STANDARD dampen 15%
                mcap <   5_000_000.0 -> 1.00            // GROWTH  neutral
                mcap <  50_000_000.0 -> 1.15            // SCALED  amplify 15%
                else                 -> 1.30            // BLUE    amplify 30%
            }
            // distance from 1.0 (penalty if neg, bonus if pos)
            val delta = baseMult - 1.0
            val scaled = delta * tierBias
            return (1.0 + scaled).coerceIn(0.40, 1.30)
        }

                // ═══════════════════════════════════════════════════════════════════
        // V5.9.937 — BIRDEYE SECURITY TRUST soft-shape (5th dormant subsystem).
        //
        // Operator upgraded to Birdeye Starter ($99/mo) 2026-05-19, which
        // unlocks /defi/token_security. BirdeyeSecurityProvider has existed
        // since V5.9.910 with FULL scoring logic (top10HolderPct, freeze
        // authority, transferFeeEnable, mutableMetadata, fakeToken, creator
        // concentration, LP lock, jupStrict) — but ZERO callers because the
        // free tier 401'd every request. Pure dormant safety subsystem.
        //
        // Trust score is in [0.5, 1.0]:
        //   1.00 = clean (no flags, jupStrict, low concentration)
        //   0.50 = floor (multiple flags, e.g. freeze+honeypot+top10>80%)
        //
        // Shape (per doctrine #86 — soft-shape only, no veto):
        //   trust >= 0.95 (clean)                → size × 1.10
        //   trust >= 0.80 (low risk)             → 1.00 (no opinion)
        //   trust >= 0.65 (medium risk)          → size × 0.80
        //   trust <  0.65 (high risk)            → size × 0.60
        //
        // Bounded floor 0.01 SOL. Never blocks (rug/freeze/honeypot are
        // already hard-vetoed by SecurityGuard upstream — this is incremental
        // shaping on the survivors). Fail-open per FDG doctrine.
        //
        // Paper mode INCLUDED: per doctrine #87.1 we want paper to learn
        // from security signals too. The fetch is cached 30min so each
        // unique mint costs ≤1 Birdeye CU per half-hour (well under budget).
        // ═══════════════════════════════════════════════════════════════════
        try {
            // peekCached avoids triggering a network fetch from inside FDG
            // (FDG is sync and hot — fetches happen async upstream during
            // scanner/V3 intake). If not cached, neutral 1.0.
            val secSnapshot = com.lifecyclebot.engine.BirdeyeSecurityProvider.peekCached(ts.mint)
            if (secSnapshot != null) {
                val trust = secSnapshot.trust
                val secMult = when {
                    trust >= 0.95 -> 1.10
                    trust >= 0.80 -> 1.00
                    trust >= 0.65 -> 0.80
                    else          -> 0.60
                }
                val tieredSecMult = tierShape(secMult, candidateMcap)
                if (tieredSecMult != 1.00) {
                    val originalSize = finalSize
                    finalSize = (finalSize * tieredSecMult).coerceIn(0.01, 1.0)
                    val direction = if (tieredSecMult > 1.0) "boosted" else "reduced"
                    tags.add("size_${direction}_birdeye_security")
                    val flags = buildList {
                        if (secSnapshot.freezeAuth) add("freeze")
                        if (secSnapshot.mutableMeta) add("mutable_meta")
                        if (secSnapshot.honeypot) add("honeypot")
                        if (secSnapshot.top10Pct > 0.40) add("top10=${(secSnapshot.top10Pct*100).toInt()}%")
                        if (secSnapshot.jupStrict) add("jup_strict")
                    }.joinToString(",").ifBlank { "none" }
                    checks.add(
                        GateCheck(
                            "birdeye_security",
                            true,
                            "Trust ${"%.2f".format(trust)} (flags=$flags) — size $direction ${originalSize.format(3)} → ${finalSize.format(3)}"
                        )
                    )
                }
            }
        } catch (_: Throwable) { /* fail-open — security is soft-shape only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.937 — BIRDEYE TRADE-DATA flow-imbalance soft-shape.
        //
        // Birdeye Starter unlocks /defi/v3/token/trade-data/single which
        // returns 5m/30m/1h/2h/4h/8h/24h buy/sell counts + volume splits
        // in ONE call. This is high-value flow data that we previously
        // synthesised from DexScreener 1h aggregates (much coarser).
        //
        // Signal: 30-minute buy/sell imbalance ratio.
        //   buyRatio = buys30m / (buys30m + sells30m)
        //   buyRatio >= 0.70 → strong accumulation → size × 1.15
        //   buyRatio >= 0.60 → mild accumulation   → size × 1.05
        //   buyRatio in 0.40..0.60 → balanced      → 1.00
        //   buyRatio <  0.40 → distribution        → size × 0.85
        //   buyRatio <  0.30 → heavy distribution  → size × 0.70
        //
        // Requires min volume floor (10 trades / $500 USD in 30m) to avoid
        // shaping on noise. Cached via BirdeyeTradeDataProvider (similar to
        // security — 30s cache TTL because trade data is fresher than sec).
        //
        // Bounded floor 0.01; fail-open per doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val tradeSnapshot = com.lifecyclebot.engine.BirdeyeTradeDataProvider.peekCached(ts.mint)
            if (tradeSnapshot != null) {
                val totalTrades30m = tradeSnapshot.buys30m + tradeSnapshot.sells30m
                if (totalTrades30m >= 10 && tradeSnapshot.volume30m >= 500.0) {
                    val buyRatio = tradeSnapshot.buys30m.toDouble() / totalTrades30m
                    val flowMult = when {
                        buyRatio >= 0.70 -> 1.15
                        buyRatio >= 0.60 -> 1.05
                        buyRatio <= 0.30 -> 0.70
                        buyRatio <= 0.40 -> 0.85
                        else             -> 1.00
                    }
                    val tieredFlowMult = tierShape(flowMult, candidateMcap)
                    if (tieredFlowMult != 1.00) {
                        val originalSize = finalSize
                        finalSize = (finalSize * tieredFlowMult).coerceIn(0.01, 1.0)
                        val direction = if (tieredFlowMult > 1.0) "boosted" else "reduced"
                        tags.add("size_${direction}_flow_imbalance")
                        checks.add(
                            GateCheck(
                                "flow_imbalance_30m",
                                true,
                                "buyRatio=${"%.0f".format(buyRatio*100)}% (b=${tradeSnapshot.buys30m}/s=${tradeSnapshot.sells30m} vol=\$${tradeSnapshot.volume30m.toInt()}) — size $direction ${originalSize.format(3)} → ${finalSize.format(3)}"
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) { /* fail-open — flow shape is advisory only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.938 — BIRDEYE VOLATILITY REGIME soft-shape (size-by-volatility).
        //
        // /defi/v3/price/stats/single returns 30m/1h/24h min/max/stddev.
        // Stddev as a % of price classifies the regime. Calm tokens with
        // tight ranges deserve larger size; insane tokens need smaller.
        //
        //   CALM      < 1%  → × 1.10
        //   NORMAL    1-3%  → × 1.00
        //   CHOPPY    3-6%  → × 0.85
        //   VOLATILE  6-12% → × 0.70
        //   INSANE    > 12% → × 0.60
        //
        // Cached 30min by BirdeyePriceStatsProvider. Paper included
        // (doctrine #87.1). Fail-open per FDG doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val statsSnap = com.lifecyclebot.engine.BirdeyePriceStatsProvider.peekCached(ts.mint)
            if (statsSnap != null) {
                val (regime, volMult) = statsSnap.volatilityRegime()
                val tieredVolMult = tierShape(volMult, candidateMcap)
                if (tieredVolMult != 1.00 && regime != "UNKNOWN") {
                    val originalSize = finalSize
                    finalSize = (finalSize * tieredVolMult).coerceIn(0.01, 1.0)
                    val direction = if (tieredVolMult > 1.0) "boosted" else "reduced"
                    tags.add("size_${direction}_volatility_${regime.lowercase()}")
                    checks.add(
                        GateCheck(
                            "volatility_regime",
                            true,
                            "Regime=$regime — size $direction ${originalSize.format(3)} → ${finalSize.format(3)} (×${"%.2f".format(volMult)})"
                        )
                    )
                }
            }
        } catch (_: Throwable) { /* fail-open — volatility shape is advisory only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.938 — FRESH DEPLOY soft-shape (age-based size discipline).
        //
        // /defi/token_creation_info returns the actual deploy timestamp.
        // Tokens < 1h old are highly volatile and prone to honeypot/dump.
        // Tokens > 24h old that survived are more mature.
        //
        //   age <  1h  → × 0.75 (fresh deploy, smaller size)
        //   age <  6h  → × 0.90
        //   age <  24h → × 1.00 (no opinion)
        //   age >= 24h → × 1.05 (mild boost for survivors)
        //   age >= 72h → × 1.10
        //
        // Note: this complements (does not replace) the existing
        // addedToWatchlistAt-based age logic. addedToWatchlistAt is when
        // WE saw it; createdAtMs is when it was DEPLOYED. Big difference
        // for tokens we discover late.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val creationInfo = com.lifecyclebot.engine.BirdeyeCreationInfoProvider.peekCached(ts.mint)
            if (creationInfo != null) {
                val ageH = creationInfo.ageHours()
                if (ageH >= 0.0) {
                    val ageMult = when {
                        ageH < 1.0  -> 0.75
                        ageH < 6.0  -> 0.90
                        ageH < 24.0 -> 1.00
                        ageH < 72.0 -> 1.05
                        else        -> 1.10
                    }
                    val tieredAgeMult = tierShape(ageMult, candidateMcap)
                    if (tieredAgeMult != 1.00) {
                        val originalSize = finalSize
                        finalSize = (finalSize * tieredAgeMult).coerceIn(0.01, 1.0)
                        val direction = if (tieredAgeMult > 1.0) "boosted" else "reduced"
                        tags.add("size_${direction}_age_${ageH.toInt()}h")
                        checks.add(
                            GateCheck(
                                "deploy_age",
                                true,
                                "Age=${"%.1f".format(ageH)}h — size $direction ${originalSize.format(3)} → ${finalSize.format(3)} (×${"%.2f".format(ageMult)})"
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) { /* fail-open — age shape is advisory only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.938 — TOKEN SOCIAL DEPTH soft-shape.
        //
        // /defi/v3/token/meta-data/single returns twitter/telegram/discord/
        // website + coingeckoId. Token social channel count is a weak but
        // real quality proxy — devs willing to put their face on multiple
        // channels are less likely to rug.
        //
        //   coingecko_id present (listed)   → × 1.10 (high-quality survivor)
        //   3+ social channels populated    → × 1.05
        //   2 channels                      → × 1.00
        //   1 channel                       → × 0.95
        //   0 channels (no socials at all)  → × 0.85
        // ═══════════════════════════════════════════════════════════════════
        try {
            val meta = com.lifecyclebot.engine.BirdeyeMetaDataProvider.peekCached(ts.mint)
            if (meta != null) {
                val sc = meta.socialChannelCount()
                val socialMult = when {
                    meta.isListed() -> 1.10
                    sc >= 3         -> 1.05
                    sc == 2         -> 1.00
                    sc == 1         -> 0.95
                    else            -> 0.85
                }
                val tieredSocialMult = tierShape(socialMult, candidateMcap)
                if (tieredSocialMult != 1.00) {
                    val originalSize = finalSize
                    finalSize = (finalSize * tieredSocialMult).coerceIn(0.01, 1.0)
                    val direction = if (tieredSocialMult > 1.0) "boosted" else "reduced"
                    val tagFlag = if (meta.isListed()) "listed" else "${sc}ch"
                    tags.add("size_${direction}_social_$tagFlag")
                    checks.add(
                        GateCheck(
                            "social_depth",
                            true,
                            "Socials=$sc${if (meta.isListed()) " +CG" else ""} — size $direction ${originalSize.format(3)} → ${finalSize.format(3)} (×${"%.2f".format(socialMult)})"
                        )
                    )
                }
            }
        } catch (_: Throwable) { /* fail-open — social shape is advisory only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.939 PHASE 5 — TIER-AWARE SAFETY SOFT-SHAPE.
        //
        // ScalingMode.Tier defines per-tier safety requirements:
        //   MICRO         rugcheck 70, no LP-lock req, no CG-listed req
        //   STANDARD      rugcheck 72, no LP-lock req, no CG-listed req
        //   GROWTH        rugcheck 80, LP-locked required, top-holder<30%
        //   SCALED        rugcheck 85, LP-locked + CG-listed required
        //   INSTITUTIONAL rugcheck 90, LP-locked + CG-listed required
        //
        // Pre-V5.9.939 these per-tier requirements were INERT — defined in
        // ScalingMode but never enforced anywhere. Hard-vetoing them now
        // would violate doctrine #86 ("help, don't hinder"). Instead we
        // apply them as size-dampening SOFT-SHAPES — a token that should
        // be tier GROWTH but lacks LP-lock gets a smaller position, not
        // a veto. This matches existing FDG soft-shape pattern.
        //
        // Fail-open per FDG doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val tier = com.lifecyclebot.engine.ScalingMode.tierForToken(
                liquidityUsd = ts.lastLiquidityUsd,
                mcapUsd = candidateMcap,
            )
            val tierSafetyPenalties = mutableListOf<String>()
            var tierSafetyMult = 1.0

            // LP-lock requirement (GROWTH+)
            if (tier.requireLpLock90) {
                val lpOk = try {
                    val lpPct = com.lifecyclebot.engine.TokenSafetyChecker.peekLpLockPct(ts.mint)
                    lpPct >= 90.0
                } catch (_: Throwable) { true /* fail-open */ }
                if (!lpOk) {
                    tierSafetyMult *= 0.75
                    tierSafetyPenalties.add("LP_NOT_LOCKED")
                }
            }

            // CoinGecko-listed requirement (SCALED+)
            if (tier.requireCoinGeckoListed) {
                val cgListed = try {
                    com.lifecyclebot.engine.BirdeyeMetaDataProvider.peekCached(ts.mint)?.isListed() == true
                } catch (_: Throwable) { true /* fail-open */ }
                if (!cgListed) {
                    tierSafetyMult *= 0.70
                    tierSafetyPenalties.add("NOT_CG_LISTED")
                }
            }

            // Top-holder concentration (GROWTH+)
            if (tier.requireTopHolder30) {
                val topConcOk = try {
                    val sec = com.lifecyclebot.engine.BirdeyeSecurityProvider.peekCached(ts.mint)
                    sec == null || sec.top10Pct <= 0.30
                } catch (_: Throwable) { true /* fail-open */ }
                if (!topConcOk) {
                    tierSafetyMult *= 0.80
                    tierSafetyPenalties.add("TOP_HOLDERS_>30%")
                }
            }

            if (tierSafetyMult < 1.0 && tierSafetyPenalties.isNotEmpty()) {
                val originalSize = finalSize
                finalSize = (finalSize * tierSafetyMult).coerceIn(0.01, 1.0)
                tags.add("size_reduced_tier_safety_${tier.label.lowercase()}")
                checks.add(
                    GateCheck(
                        "tier_safety",
                        true,
                        "Tier=${tier.label} (${tier.icon}) penalties=${tierSafetyPenalties.joinToString(",")} — size reduced ${originalSize.format(3)} → ${finalSize.format(3)} (×${"%.2f".format(tierSafetyMult)})"
                    )
                )
            }
        } catch (_: Throwable) { /* fail-open — tier-safety shape is advisory only */ }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.940 PHASE 6 — TIER-AWARE SCAN-SOURCE MATCH SOFT-SHAPE.
        //
        // ScalingMode.Tier defines a scanSources whitelist per tier:
        //   MICRO         PUMP_FUN_NEW, PUMP_FUN_GRADUATE, DEX_TRENDING, DEX_GAINERS, RAYDIUM_NEW_POOL
        //   STANDARD      PUMP_FUN_GRADUATE, DEX_TRENDING, DEX_GAINERS, BIRDEYE_TRENDING
        //   GROWTH        DEX_TRENDING, BIRDEYE_TRENDING, COINGECKO_TRENDING, NARRATIVE_SCAN
        //   SCALED        COINGECKO_TRENDING, BIRDEYE_TRENDING, DEX_TRENDING
        //   INSTITUTIONAL COINGECKO_TRENDING, BIRDEYE_TRENDING
        //
        // These whitelists existed in ScalingMode but were INERT — no
        // caller checked source-vs-tier compatibility. This means a
        // PUMP_FUN_NEW intake at \$50M mcap (theoretically possible if
        // a $50M token re-launches on pump.fun) was treated the same
        // as a COINGECKO_TRENDING intake at \$50M.
        //
        // Per doctrine #86 we soft-shape, not veto. A source-tier
        // mismatch reduces size by 0.85× — strong enough to register
        // as a signal, weak enough not to choke learning.
        //
        // Fail-open: unknown source or empty whitelist → no shape.
        // ═══════════════════════════════════════════════════════════════════
        try {
            if (ts.source.isNotBlank()) {
                val tier = com.lifecyclebot.engine.ScalingMode.tierForToken(
                    liquidityUsd = ts.lastLiquidityUsd,
                    mcapUsd = candidateMcap,
                )
                // Normalise source — scanner tags use various casings/aliases
                val srcNormalised = ts.source.uppercase()
                    .replace(".", "_")
                    .replace("-", "_")
                val whitelist = tier.scanSources
                val isMatch = whitelist.any { allowed ->
                    srcNormalised.contains(allowed) || allowed.contains(srcNormalised)
                }
                if (!isMatch && whitelist.isNotEmpty()) {
                    val mismatchMult = 0.85
                    val originalSize = finalSize
                    finalSize = (finalSize * mismatchMult).coerceIn(0.01, 1.0)
                    tags.add("size_reduced_source_tier_mismatch")
                    checks.add(
                        GateCheck(
                            "source_tier_match",
                            true,
                            "Source=${ts.source} not in ${tier.label} tier whitelist (${whitelist.joinToString(",")}) — size ${originalSize.format(3)} → ${finalSize.format(3)} (×${"%.2f".format(mismatchMult)})"
                        )
                    )
                }
            }
        } catch (_: Throwable) { /* fail-open — source-tier shape is advisory only */ }

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

        // V5.9.1486 — PROBE_ONLY IS AN APPROVED DUST BUY, NOT A BLOCK (matches the
        // canExecute() contract at the top of this file). Snapshot 5.0.3492 showed
        // PROBE_ONLY as the #1 reject reason (471) AND logged as SHITCOIN_FDG_HARD_VETO
        // — a direct contract violation. Cause: this recompute forced shouldTrade=false
        // whenever blockReason!=null, but PROBE_ONLY candidates legitimately carry a
        // non-null blockReason WITH shouldTrade=true (dust-size approved buy). The
        // SHITCOIN/MOONSHOT/etc lanes then saw !canExecute() and HARD-VETOED the exact
        // probes meant to flow at tiny size to gather bootstrap data. Treat PROBE_ONLY
        // as non-blocking here, identically to canExecute(). Any OTHER non-null
        // blockReason still forces shouldTrade=false; -15% floor + hardNo + genuine FDG
        // veto untouched.
        val shouldTrade = (blockReason == null || blockReason == "PROBE_ONLY") && (candidate.shouldTrade || baseSignalMismatchIgnoredForLane)

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

        // V5.9.806 — P0 Brain Consensus Gate hook.
        // Runs ONLY when shouldTrade is already TRUE and blockReason is null
        // (i.e., the existing logic would have allowed the trade). Can
        // therefore only DOWNGRADE an allow → block; can never upgrade
        // a block → allow. Wrapped in try-catch so any consensus-layer
        // bug cannot regress the existing decision path.
        var shouldTradeFinal = shouldTrade
        var blockReasonFinal = blockReason
        var blockLevelFinal = blockLevel
        try {
            if (shouldTrade && blockReason == null) {
                val modeTag = tradingModeTag?.name ?: "STANDARD"
                val report = BrainConsensusGate.evaluate(ts, candidate, modeTag)
                BrainConsensusGate.recordOutcome(report.verdict)
                tags.add("bcg:${report.verdict.name}")
                when (report.verdict) {
                    BrainConsensusGate.Verdict.HARD_BLOCK -> {
                        shouldTradeFinal = false
                        blockReasonFinal = "BRAIN_CONSENSUS_VETO:${report.objections.joinToString("+").take(120)}"
                        blockLevelFinal = BlockLevel.HARD
                    }
                    BrainConsensusGate.Verdict.SOFT_BLOCK -> {
                        // V5.9.1136 — no longer pure telemetry. Soft objections now
                        // reduce size during WR deficit so learning changes behaviour
                        // without disabling the lane. Only a danger-bucket objection in
                        // a deep deficit with weak evidence becomes binding.
                        tags.add("bcg_objections:${report.objections.size}")
                        val hasDanger = report.objections.any { it.contains("LOSING_PATTERN_DANGER_ZONE") }
                        val originalSize = finalSize
                        val damp = when {
                            deepLearningDeficit && hasDanger -> 0.50
                            moderateLearningDeficit -> 0.75
                            else -> 0.90
                        }
                        if (damp < 1.0) {
                            finalSize = (finalSize * damp).coerceAtLeast(0.01)
                            tags.add("bcg_size_damped")
                            checks.add(GateCheck("brain_consensus", true, "SOFT objections=${report.objections.size}; size ${originalSize.format(3)}→${finalSize.format(3)}"))
                        }
                        // V5.9.1355 P1 — PROVEN-DEAD TRAINABLE VETO binding. A
                        // statistically dead context blocks NORMAL-size entry but
                        // never permanently disables: off-cadence ticks shrink to a
                        // micro probe (0.02); the 1-in-25 cadence tick is allowed
                        // through at probe size so the bucket keeps learning.
                        // V5.9.1358 — PROVEN-DEAD no longer caps to dust on a
                        // cadence (operator mandate: never throttle/disable). The
                        // bucket being statistically weak is exactly what
                        // UnifiedPolicyHead already encodes via fwdPWin/meta
                        // conviction → it sizes this context DOWN on its own,
                        // smoothly, while still trading every tick so the bucket
                        // keeps learning where its sweet spot is. We only tag it
                        // for telemetry; the learned head owns the size.
                        // V5.9.1360 P0.3 — PROVEN-DEAD = NORMAL-SIZE VETO (operator
                        // directive, reconciled with the never-disable mandate). A
                        // statistically dead context (losses>=20, wins<=1, mean<0)
                        // must STOP causing normal-size damage — but it is NEVER
                        // disabled: it keeps trading at probe size so the bucket
                        // keeps learning and can heal. normalEntryBlocked → shrink
                        // to <=0.02; the 1-in-25 probeAllowed tick also rides at
                        // 0.02. Size-shape only — minimum 0.01, never zero. This is
                        // shaping, not a route-kill: the trade still opens.
                        if (report.provenDead) {
                            val beforeP = finalSize
                            if (report.normalEntryBlocked) {
                                finalSize = minOf(finalSize, 0.02).coerceAtLeast(0.01)
                                tags.add("bcg_proven_dead_normal_veto")
                                checks.add(GateCheck("brain_consensus_proven_dead", true, "PROVEN_DEAD normal-size vetoed → probe ${beforeP.format(3)}→${finalSize.format(3)} (learning stays open)"))
                            } else if (report.probeAllowed) {
                                finalSize = minOf(finalSize, 0.02).coerceAtLeast(0.01)
                                tags.add("bcg_proven_dead_probe")
                                checks.add(GateCheck("brain_consensus_proven_dead", true, "PROVEN_DEAD 1-in-25 learning probe @ ${finalSize.format(3)}"))
                            } else {
                                tags.add("bcg_proven_dead_observed")
                            }
                        }
                        val weakEvidence = candidate.setupQuality !in listOf("A+", "A", "B") && adjustedConfidence < 35.0 && candidate.entryScore < 25.0
                        if (hasDanger && deepLearningDeficit && weakEvidence) {
                            // V5.9.1321 — Train-First Learning Policy correction (Base44 directive).
                            // BrainConsensusGate "learned danger" is statistical evidence,
                            // not a hard safety failure. Route through FdgRouteVerdict.
                            val laneForRouting2 = (ts.position.tradingMode.ifBlank { "STANDARD" })
                            val scoreBand2 = try {
                                com.lifecyclebot.engine.LosingPatternMemory.bucketKey(
                                    laneForRouting2,
                                    candidate.entryScore.toInt()
                                ).substringAfter('|', "")
                            } catch (_: Throwable) { "" }
                            val routed2 = com.lifecyclebot.engine.learning.FdgRouteVerdict.routeLearnedDangerBucket(
                                lane = laneForRouting2,
                                scoreBand = scoreBand2,
                                evidenceLabel = "brain: ${report.objections.joinToString("+").take(80)}",
                            )
                            com.lifecyclebot.engine.learning.LanePolicy.noteRetrainingSample(laneForRouting2, scoreBand2)
                            if (!routed2.proceedToOpen) {
                                // V5.9.1325 — TRAIN-FIRST INVARIANT (BCG path).
                                // Operator: V3/FDG is final authority; never stop
                                // trading. BCG SOFT_BLOCK with learned danger is
                                // statistical, not safety — demote to micro paper
                                // probe (size 0.01) instead of vetoing the trade.
                                val originalSize3 = finalSize
                                finalSize = 0.01
                                tags.add("bcg_train_first_micro_probe")
                                tags.add("route:${routed2.verdict.tag}")
                                checks.add(GateCheck("brain_consensus_micro", true, "${routed2.routeReasonForLog} | TRAIN_FIRST_MICRO ${originalSize3.format(3)}→${finalSize.format(3)}"))
                                // V5.9.1322 — Build B: record as NoTradeObservation.
                                try {
                                    com.lifecyclebot.engine.learning.NoTradeObservationStore.recordBlock(
                                        mint = ts.mint,
                                        symbol = ts.symbol,
                                        lane = laneForRouting2,
                                        scoreBand = scoreBand2,
                                        score = candidate.entryScore.toInt(),
                                        confidence = adjustedConfidence.toInt(),
                                        entryLiqUsd = ts.lastLiquidityUsd,
                                        entryMcapUsd = ts.lastMcap,
                                        entryPrice = ts.lastPrice,
                                        source = ts.source.ifEmpty { "UNKNOWN" },
                                        blockReason = routed2.routeReasonForLog,
                                        verdictTag = routed2.verdict.tag,
                                    )
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                    BrainConsensusGate.Verdict.ALLOW -> { /* normal path */ }
                }

                // V5.9.1260 — AUTONOMOUS META-POLICY (deliberative layer).
                // Above the rule-based consensus gate sits a Thompson-sampling
                // policy that LEARNS the true win-prob of each decision-context
                // (lane × score-band × regime) from settled PnL and decides
                // explore-vs-exploit autonomously. It returns a CONVICTION
                // multiplier (soft-shape only, [0.55,1.45], never a veto, never
                // zero → volume never starved). Bootstrap-safe: neutral 1.0 until
                // a context has enough samples. The decision is stamped so the
                // settled outcome is credited back to the SAME context (closed
                // loop). Fail-open.
                if (shouldTradeFinal && blockReasonFinal == null) {
                    try {
                        val mpRegime = try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "NORMAL" }
                        val mpLane = tradingModeTag?.name ?: "STANDARD"
                        // V5.9.1296 — bucket the learning context by the lane's REAL score,
                        // not the shared base V3 score (~7 for memes) that collapsed every
                        // context into S00. laneScore defaults to candidate.entryScore so
                        // non-lane callers are identical.
                        val mpScore = laneScoreBanded  // V5.9.1299 reuse hoisted banded lane score
                        val conv = AutonomousMetaPolicy.conviction(mpLane, mpScore, mpRegime)
                        AutonomousMetaPolicy.stampDecision(ts.mint, mpLane, mpScore, mpRegime)
                        if (conv != 1.0) {
                            val before = finalSize
                            finalSize = (finalSize * conv).coerceAtLeast(0.01)
                            tags.add("metapolicy:${"%.2f".format(conv)}")
                            checks.add(GateCheck("autonomous_meta_policy", true, "conviction=${"%.2f".format(conv)} size ${before.format(3)}→${finalSize.format(3)} ctx=$mpLane/S$mpScore/$mpRegime"))
                        }

                        // V5.9.1289 — CATASTROPHIC-CONTEXT STARVE. conviction()'s
                        // 0.55 floor deliberately never starves volume — right for
                        // exploration, wrong for a MATURE proven-dead pocket (e.g.
                        // SHITCOIN|S00|NORMAL learned at winP=0% / -28% avg). When a
                        // context is statistically dead (n>=20, winP<12%, avg<-18%),
                        // starve size to dust so a known grave can't drain the wallet.
                        // NOT a veto — candidate still flows; pool & FDG fail-open intact.
                        val starve = AutonomousMetaPolicy.starveFactor(mpLane, mpScore, mpRegime)
                        if (starve < 1.0) {
                            val beforeS = finalSize
                            finalSize = (finalSize * starve).coerceAtLeast(0.001)
                            tags.add("starve:${"%.2f".format(starve)}")
                            checks.add(GateCheck("catastrophic_starve", true, "proven-dead ctx ×${"%.2f".format(starve)} size ${beforeS.format(3)}→${finalSize.format(3)} ctx=$mpLane/S${candidate.entryScore.toInt()}/$mpRegime"))
                        }

                        // V5.9.1261 — FORWARD OUTCOME MODEL (counterfactual planning).
                        // Predict the outcome distribution for this exact setup BEFORE
                        // entry (pWin / E[pnl] / pRug / dispersion), learned online from
                        // settled trades keyed by lane×band×quality×regime×edgePhase.
                        // The nudge plans against the predicted distribution. Soft-shape,
                        // stamped for closed-loop credit. Fail-open.
                        val fwd = ForwardOutcomeModel.forecast(mpLane, mpScore, candidate.setupQuality, mpRegime, candidate.edgePhase)
                        ForwardOutcomeModel.stamp(ts.mint, mpLane, mpScore, candidate.setupQuality, mpRegime, candidate.edgePhase)
                        // V5.9.1271 — grade the predictor: stamp pWin+E[pnl] so the close can score accuracy.
                        try { com.lifecyclebot.engine.SignalQualityTracker.stamp(ts.mint, mpLane, fwd.pWin, fwd.expectedPnl) } catch (_: Throwable) {}

                        // V5.9.1358 — DUAL-BRAIN VETO → SIZE-SHAPE (operator mandate:
                        // never refuse/disable a context, learn the right way to trade it
                        // from trade one). When Meta-Policy AND Forward Model both condemn
                        // a context with real evidence, we no longer STOP the trade — we
                        // shrink it hard (the learned heads already do this, this is the
                        // floor-deepener for the gravest pockets) so the bot keeps getting
                        // real outcomes there and can find the sweet spot / heal. Never
                        // zero: a small live position is how the brain learns the bucket.
                        // -15% hard SL + 500-token pool + FDG fail-open all untouched.
                        if (AutonomousMetaPolicy.shouldVeto(mpLane, mpScore, mpRegime, fwd.pWin, fwd.expectedPnl, fwd.samples)) {
                            val beforePd = finalSize
                            finalSize = (finalSize * 0.15).coerceAtLeast(0.01)
                            tags.add("proven_dead_size_shaped")
                            checks.add(GateCheck("proven_dead_shape", true, "dual-brain weak ctx=$mpLane/S${candidate.entryScore.toInt()}/$mpRegime → small learn-size ${beforePd.format(3)}→${finalSize.format(3)} fwd[pWin=${(fwd.pWin*100).toInt()}% E=${"%+.1f".format(fwd.expectedPnl)}% n=${fwd.samples}]"))
                        }
                        if (fwd.convictionNudge != 1.0 && fwd.source != "bootstrap") {
                            val before = finalSize
                            finalSize = (finalSize * fwd.convictionNudge).coerceAtLeast(0.01)
                            tags.add("fwdsim:${"%.2f".format(fwd.convictionNudge)}")
                            checks.add(GateCheck("forward_outcome_model", true, "pWin=${(fwd.pWin*100).toInt()}% E[pnl]=${"%+.1f".format(fwd.expectedPnl)}% pRug=${(fwd.pRug*100).toInt()}% ±${fwd.dispersion.toInt()} n=${fwd.samples}(${fwd.source}) size ${before.format(3)}→${finalSize.format(3)}"))
                        }

                        // V5.9.1262 — UNIFIED POLICY HEAD (one learned decider).
                        // A single online-logistic head over ALL committee signals
                        // (ML conf, symbolic green-light, EV, meta conviction, fwd pWin,
                        // candidate confidence). Learns from real outcomes how to weigh
                        // its own sub-brains into one coherent win-probability → soft
                        // size multiplier. Stamped for closed-loop training. Fail-open.
                        val uphSignals = UnifiedPolicyHead.Signals(
                            mlEntryConf  = try { (mlPrediction?.entryConfidence?.toDouble()) ?: 0.5 } catch (_: Throwable) { 0.5 },
                            symGreenLight = symGreenLight,
                            evRatio      = try { (((evResult?.expectedValue ?: 1.0) - 0.8) / 0.8).coerceIn(0.0, 1.0) } catch (_: Throwable) { 0.5 },
                            metaConviction = ((conv - 0.55) / 0.9).coerceIn(0.0, 1.0),
                            fwdPWin      = fwd.pWin,
                            candConf     = (adjustedConfidence / 100.0).coerceIn(0.0, 1.0),
                        )
                        val uph = UnifiedPolicyHead.conviction(uphSignals)
                        UnifiedPolicyHead.stamp(ts.mint, uphSignals)
                        if (uph != 1.0) {
                            val before = finalSize
                            finalSize = (finalSize * uph).coerceAtLeast(0.01)
                            tags.add("policyhead:${"%.2f".format(uph)}")
                            checks.add(GateCheck("unified_policy_head", true, "pWin=${(UnifiedPolicyHead.predictWinProb(uphSignals)*100).toInt()}% mult=${"%.2f".format(uph)} size ${before.format(3)}→${finalSize.format(3)}"))
                        }

                        // V5.9.1263 — STRATEGY HYPOTHESIS ENGINE (self-directed A/B).
                        // The bot proposes its OWN size-bias mutations per context, splits
                        // trades control vs variant by mint, measures real PnL per arm, and
                        // promotes winners / retires losers with a Welch t-stat. Bounded
                        // soft nudge [0.85,1.20]; never touches the -15% hard floor. Stamped.
                        val hypoBias = StrategyHypothesisEngine.getSizeBias(mpLane, candidate.entryScore.toInt(), mpRegime, ts.mint)
                        if (hypoBias != 1.0) {
                            val before = finalSize
                            finalSize = (finalSize * hypoBias).coerceAtLeast(0.01)
                            tags.add("hypo:${"%.2f".format(hypoBias)}")
                            checks.add(GateCheck("strategy_hypothesis", true, "sizeBias=${"%.2f".format(hypoBias)} size ${before.format(3)}→${finalSize.format(3)}"))
                        }
                    } catch (_: Throwable) { /* meta-policy must never break entry */ }
                }
            }
        } catch (_: Throwable) {
            // Brain layer failure must never break the entry pipeline.
        }

        // V5.9.1330 — LANE-POLICY EXECUTION WEIGHT (the dead-wiring bug, backtest-proven).
        // LanePolicy already encodes the EXACT verdicts the honest backtest replay reached:
        //   UNKNOWN     -> SHADOW_TRACK_ONLY  (execWeight 0.00)  == backtest "STOP TRADING"
        //   SHITCOIN    -> PAPER_MICRO        (execWeight 0.10)  (87 trades, sharpe -4.7 at full size)
        //   MANIPULATED -> PAPER_MICRO        (execWeight 0.10)
        //   MOONSHOT    -> REDUCED_SIZE       (execWeight 0.60)
        //   BLUECHIP/QUALITY/TREASURY -> NORMAL (1.00)  -- the proven winners stay full size.
        // But effectiveExecutionWeight() was only consulted inside the narrow
        // danger-bucket+deepDeficit+weakEvidence branch — a plain UNKNOWN/unclassified
        // token that wasn't in a "dangerous bucket" skipped the router entirely and
        // executed at FULL size. That is why the live log shows UNKNOWN entries (the
        // single biggest proven bleeder, n=87) still trading. Apply the lane-policy
        // weight to EVERY entry here as the final authoritative size word.
        // TRAIN-FIRST COMPLIANT: this SHAPES size (and routes SHADOW/TRAIN to ~0); it does
        // NOT hard-veto. Only State.INVALID_UNTRADEABLE zeroes size (unsafe data), which is
        // already in the original veto whitelist. Winners (NORMAL=1.00) are untouched.
        try {
            val lpScoreBand = com.lifecyclebot.engine.LosingPatternMemory.scoreBand(candidate.entryScore.toInt())
            val lpState = com.lifecyclebot.engine.learning.LanePolicy.effectiveState(laneName, lpScoreBand)
            val lpWeight = com.lifecyclebot.engine.learning.LanePolicy.effectiveExecutionWeight(laneName, lpScoreBand)
            // NORMAL_EXECUTION (weight 1.0) is a no-op; only damp when policy says so.
            if (lpState != com.lifecyclebot.engine.learning.LanePolicy.State.NORMAL_EXECUTION && lpWeight < 1.0) {
                val beforeLp = finalSize
                // V5.9.1358 — NEVER ZERO A LANE ON STRATEGY GROUNDS (operator mandate:
                // never disable, never shadow-only; learn the correct way to trade every
                // bucket from trade one). A weak/“dead” bucket trades SMALL, not never —
                // so the brain keeps getting real outcomes and can find the sweet spot
                // and trade back through it. Only genuine INVALID_UNTRADEABLE (unsafe /
                // unsellable data — already in the original veto whitelist) may zero out.
                val isTrueUntradeable = lpState == com.lifecyclebot.engine.learning.LanePolicy.State.INVALID_UNTRADEABLE
                val liveRuntime = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { !config.paperMode }
                val liveParityMicro = liveRuntime && lpState == com.lifecyclebot.engine.learning.LanePolicy.State.PAPER_MICRO_EXECUTION
                // V5.9.1559 — PAPER_MICRO_EXECUTION is a paper/probe state name. In LIVE
                // it must NOT choke real execution down to the 0.01 SOL floor forever.
                // Treat it as reduced-size live execution: still cautious, but mirrors
                // paper decision volume/win-rate shape instead of becoming a live no-op.
                val shapeW = when {
                    isTrueUntradeable -> lpWeight
                    liveParityMicro   -> lpWeight.coerceAtLeast(0.35)
                    else              -> lpWeight.coerceAtLeast(0.05)
                }
                finalSize = (finalSize * shapeW).coerceAtLeast(if (isTrueUntradeable) 0.0 else if (liveParityMicro) 0.025 else 0.01)
                val lpLabel = if (liveParityMicro) "LIVE_REDUCED_FROM_${lpState.name}" else lpState.name
                tags.add("lane_policy:${lpLabel}×${"%.2f".format(shapeW)}")
                checks.add(GateCheck("lane_policy_weight", true,
                    "lane=$laneName band=$lpScoreBand state=${lpLabel} execW=${"%.2f".format(shapeW)} size ${beforeLp.format(3)}→${finalSize.format(3)}"))
                ErrorLogger.info("FDG", "🛞 LANE_POLICY_EXEC_WEIGHT ${ts.symbol} lane=$laneName band=$lpScoreBand ${lpLabel} ×${"%.2f".format(shapeW)} size ${beforeLp.format(3)}→${finalSize.format(3)}")
                // Train-First telemetry note for weak buckets — but we DO open the trade.
                com.lifecyclebot.engine.learning.LanePolicy.noteRetrainingSample(laneName, lpScoreBand)
                // ONLY genuinely untradeable (unsafe data) is routed no-open. Strategy
                // weakness NEVER stops the trade — the small size is the expression.
                if (isTrueUntradeable && shapeW <= 0.0) {
                    blockReasonFinal = blockReasonFinal ?: "LANE_POLICY_INVALID_UNTRADEABLE"
                    shouldTradeFinal = false
                }
            }
        } catch (_: Throwable) { /* lane policy must never break the entry pipeline */ }

        // V5.9.1333 — PERSONALITY TUNE → sizing multiplier (bounded ±15%).
        // Six-trait personality vector (paranoia/euphoria/discipline/aggression
        // /patience/loyalty) now shapes entry size. Fade FOMO when bot is
        // euphoric, size up when disciplined/aggressive. All bounded, fail-open.
        try {
            val personalityMult = com.lifecyclebot.engine.PersonalityTraitMultipliers.sizingMultiplier()
            if (kotlin.math.abs(personalityMult - 1.0) > 0.005) {
                val beforeP = finalSize
                finalSize = (finalSize * personalityMult).coerceAtLeast(0.0)
                tags.add("personality:×${"%.2f".format(personalityMult)}")
                checks.add(GateCheck("personality_tune", true,
                    "sizeMult=${"%.2f".format(personalityMult)} size ${beforeP.format(3)}→${finalSize.format(3)}"))
            }
        } catch (_: Throwable) { /* personality tune must never break entry */ }

        // V5.9.810 — SymbolicVerdict capture (the missing Push 6 wiring).
        // CandidateSymbolicContextBuilder.buildFor() has existed since V5.9.784
        // but had ZERO callers — CandidateFeatures.symbolicVerdict has stayed
        // hardcoded to "" since then. Build a per-token verdict here using the
        // data already in scope (TokenState safety slice + global SymbolicContext
        // mood) and record it into SymbolicVerdictRegistry. The Executor's
        // rich-publish site reads it at close time and stamps it into
        // CanonicalFeatures.symbolicVerdict so BehaviorLearning can finally
        // calibrate predicted-vs-actual failure mode.
        //
        // Fail-soft: full try/catch, no influence on shouldTradeFinal or blockReason.
        // This is OBSERVATION ONLY. Never a gate, never a veto.
        try {
            val symCtx = com.lifecyclebot.engine.CandidateSymbolicContextBuilder.buildFor(
                mint = ts.mint,
                symbol = ts.symbol,
                safetyTier = ts.safety.tier.name,
                rugRiskScore = run {
                    val rc = ts.safety.rugcheckScore
                    if (rc < 0) 0.0 else (rc.toDouble() / 100.0).coerceIn(0.0, 1.0)
                },
                holderConcentration = run {
                    val pct = ts.safety.topHolderPct
                    when {
                        pct < 0 -> ""
                        pct >= 50 -> "CONC_RUG"
                        pct >= 30 -> "CONC_HIGH"
                        pct >= 15 -> "CONC_MED"
                        else      -> "CONC_LOW"
                    }
                },
                mintAuthority = when (ts.safety.mintAuthorityDisabled) {
                    true  -> "RENOUNCED"
                    false -> "RETAINED"
                    null  -> "UNKNOWN"
                },
                freezeAuthority = when (ts.safety.freezeAuthorityDisabled) {
                    true  -> "RENOUNCED"
                    false -> "RETAINED"
                    null  -> "UNKNOWN"
                },
                walletAlreadyHolding = try {
                    com.lifecyclebot.engine.HostWalletTokenTracker.hasOpenPosition(ts.mint)
                } catch (_: Throwable) { false },
                walletOpenCount = try {
                    com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount()
                } catch (_: Throwable) { 0 },
            )
            com.lifecyclebot.engine.SymbolicVerdictRegistry.record(
                mint = ts.mint,
                verdict = symCtx.symbolicVerdictString(),
                vote = symCtx.verdict.vote.name,
                confidence = symCtx.verdict.confidence,
                expectedFailureMode = symCtx.verdict.expectedFailureMode,
            )
        } catch (_: Throwable) {
            // Symbolic verdict is pure telemetry — never affect the decision.
        }

        return FinalDecision(
            shouldTrade = shouldTradeFinal,
            mode = mode,
            approvalClass = approvalClass,
            quality = candidate.finalQuality,
            confidence = adjustedConfidence,
            edge = edgeVerdict,
            blockReason = blockReasonFinal,
            blockLevel = blockLevelFinal,
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