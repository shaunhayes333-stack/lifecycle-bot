package com.lifecyclebot.v3.core

import com.lifecyclebot.v3.decision.ConfidenceEngine
import com.lifecyclebot.v3.decision.DecisionResult
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.decision.OpsMetrics
import com.lifecyclebot.v3.eligibility.CGradeLooperTracker
import com.lifecyclebot.v3.eligibility.EligibilityGate
import com.lifecyclebot.v3.execution.BotLogger
import com.lifecyclebot.v3.execution.TradeExecutor
import com.lifecyclebot.v3.learning.LearningMetrics
import com.lifecyclebot.v3.risk.FatalRiskChecker
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.v3.scoring.UnifiedScorer
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.SmartSizerV3
import com.lifecyclebot.v3.sizing.WalletSnapshot

/**
 * V3 Bot Orchestrator
 *
 * Main pipeline coordinator.
 *
 * Flow:
 * DISCOVERY -> ELIGIBILITY -> SCORING -> FATAL CHECK -> CONFIDENCE -> DECISION
 * -> LOOPER CHECK -> SIZING -> EXECUTE
 *
 * V3 SELECTIVITY:
 * Added C-grade looper detection after DECISION.
 * Prevents repeated C-grade + low-conf proposals from clogging the pipeline.
 */
class BotOrchestrator(
    private val ctx: TradingContext,
    private val lifecycle: LifecycleManager = LifecycleManager(),
    private val logger: BotLogger = BotLogger(),
    private val eligibilityGate: EligibilityGate,
    private val unifiedScorer: UnifiedScorer = UnifiedScorer(),
    private val fatalRiskChecker: FatalRiskChecker,
    private val confidenceEngine: ConfidenceEngine = ConfidenceEngine(),
    private val finalDecisionEngine: FinalDecisionEngine,
    private val smartSizer: SmartSizerV3,
    private val tradeExecutor: TradeExecutor = TradeExecutor(),
    private val shadowTracker: ShadowTracker = ShadowTracker()
) {

    fun processCandidate(
        candidate: CandidateSnapshot,
        wallet: WalletSnapshot,
        risk: PortfolioRiskState,
        learningMetrics: LearningMetrics,
        opsMetrics: OpsMetrics
    ): ProcessResult {
        lifecycle.mark(candidate.mint, LifecycleState.DISCOVERED)
        logger.stage(
            "DISCOVERY",
            candidate.symbol,
            "FOUND",
            "src=${candidate.source} liq=${candidate.liquidityUsd} age=${candidate.ageMinutes}m"
        )

        val eligibility = eligibilityGate.evaluate(candidate)
        if (!eligibility.passed) {
            lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
            logger.stage("ELIGIBILITY", candidate.symbol, "FAIL", eligibility.reason)
            return ProcessResult.Rejected(eligibility.reason)
        }

        lifecycle.mark(candidate.mint, LifecycleState.ELIGIBLE)
        logger.stage("ELIGIBILITY", candidate.symbol, "PASS", "candidate eligible")
        com.lifecyclebot.engine.MemePipelineTracer.stage(
            "V3_ELIGIBLE", candidate.mint, candidate.symbol,
            "src=${candidate.source} liq=${candidate.liquidityUsd} age=${candidate.ageMinutes}m",
        )

        val preScoreMemoryKill = checkPreScoreMemoryKill(candidate)
        if (preScoreMemoryKill != null) {
            lifecycle.mark(candidate.mint, LifecycleState.WATCH)
            shadowTracker.trackEarly(candidate, preScoreMemoryKill.memoryScore, preScoreMemoryKill.reason)
            com.lifecyclebot.engine.MemePipelineTracer.blocked(
                candidate.mint, candidate.symbol,
                reason = "PRE_SCORE_MEMORY_KILL", detail = preScoreMemoryKill.reason,
            )
            return ProcessResult.Watch(0.0, 0.0)
        }

        val scoreCard = unifiedScorer.score(candidate, ctx)
        lifecycle.mark(candidate.mint, LifecycleState.SCORED)
        // V5.9.91: health summary so it's visible at a glance which layers are
        // actually contributing. If active/total stays <8/22 across many
        // tokens, the scoring system is starved of inputs — not the quality
        // grader.
        val active = scoreCard.components.count { it.value != 0 }
        val positive = scoreCard.components.count { it.value > 0 }
        val total = scoreCard.components.size
        logger.stage(
            "SCORING",
            candidate.symbol,
            "OK",
            "total=${scoreCard.total} layers=$active/$total active ($positive pos) :: ${
                scoreCard.components.joinToString(" | ") { c ->
                    // V5.9.96: inline zero-reason for every silent layer so
                    // we can see exactly WHY each of the 13 consistently-dead
                    // layers (momentum/volume/memory/copytrade/suppression/
                    // feargreed/volatility/orderflow/smartmoney/holdtime/
                    // liquiditycycle/insider_tracker/metacognition) returns
                    // 0 — without waiting for the 20-eval health summary.
                    if (c.value == 0) {
                        val hint = c.reason.trim().takeIf { it.isNotBlank() }
                            ?.take(40)?.replace("|", "¦")
                        if (hint.isNullOrBlank()) "${c.name}=0"
                        else "${c.name}=0(${hint})"
                    } else {
                        "${c.name}=${c.value}"
                    }
                }
            }"
        )

        val fatal = fatalRiskChecker.check(candidate, ctx)
        logger.stage(
            "FATAL",
            candidate.symbol,
            if (fatal.blocked) "BLOCK" else "PASS",
            fatal.reason ?: "none"
        )

        val confidence = confidenceEngine.compute(scoreCard, learningMetrics, opsMetrics)
        logger.stage(
            "CONFIDENCE",
            candidate.symbol,
            "OK",
            "stat=${confidence.statistical} struct=${confidence.structural} ops=${confidence.operational} eff=${confidence.effective}"
        )

        val earlyKill = checkPreProposalKill(candidate, scoreCard, confidence.effective)
        if (earlyKill != null) {
            logger.stage(
                "PRE_PROPOSAL_KILL",
                candidate.symbol,
                "SHADOW_ONLY",
                "quality=${earlyKill.setupQuality} conf=${confidence.effective.toInt()}% memory=${earlyKill.memoryScore} -> SHADOW_TRACK"
            )
            lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
            shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), earlyKill.reason)
            com.lifecyclebot.engine.MemePipelineTracer.blocked(
                candidate.mint, candidate.symbol,
                reason = "PRE_PROPOSAL_KILL", detail = earlyKill.reason,
            )
            return ProcessResult.ShadowOnly(
                score = scoreCard.total.toDouble(),
                confidence = confidence.effective.toDouble(),
                reason = earlyKill.reason
            )
        }

        val isPaperMode = com.lifecyclebot.engine.GlobalTradeRegistry.isPaperMode

        val decision = finalDecisionEngine.decide(
            scoreCard = scoreCard,
            confidence = confidence,
            fatal = fatal,
            isPaperMode = isPaperMode
        )

        logger.stage(
            "DECISION",
            candidate.symbol,
            decision.band.name,
            "score=${decision.finalScore} conf=${decision.effectiveConfidence}"
        )

        val setupQuality = deriveSetupQuality(decision.finalScore)

        val liqResult = checkLiquidityFloor(
            candidate = candidate,
            scoreCard = scoreCard,
            band = decision.band,
            setupQuality = setupQuality,
            isPaperMode = isPaperMode,
            effectiveConfidence = confidence.effective
        )
        if (liqResult != null) {
            return liqResult
        }

        val learningProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Exception) {
            0.0
        }
        val isBootstrap = learningProgress < 0.40  // V5.9.165: aligned to global 0.40 threshold

        val looperResult = checkLooper(
            candidate = candidate,
            decision = decision,
            setupQuality = setupQuality,
            confidence = confidence.effective,
            isBootstrap = isBootstrap,
            scoreCard = scoreCard
        )
        if (looperResult != null) {
            return looperResult
        }

        return when (decision.band) {
            DecisionBand.BLOCK_FATAL -> handleBlockFatal(candidate, scoreCard, confidence.effective, decision)
            DecisionBand.WATCH -> handleWatch(candidate, scoreCard, confidence.effective, decision)
            DecisionBand.REJECT -> handleReject(candidate, scoreCard, confidence.effective, decision)
            DecisionBand.EXECUTE_SMALL,
            DecisionBand.EXECUTE_STANDARD,
            DecisionBand.EXECUTE_AGGRESSIVE -> handleExecute(
                candidate = candidate,
                wallet = wallet,
                risk = risk,
                confidenceEffective = confidence.effective,
                decision = decision,
                scoreCard = scoreCard
            )
        }
    }

    private data class PreScoreKill(
        val memoryScore: Int,
        val reason: String
    )

    private fun checkPreScoreMemoryKill(candidate: CandidateSnapshot): PreScoreKill? {
        return try {
            val memoryScore = com.lifecyclebot.engine.TokenWinMemory.getMemoryScoreForMint(candidate.mint)
            // V5.9.63: User reported volume choked by shadow-kills.
            // Previous -10 threshold blocked tokens after just ONE
            // -31% paper loss (memory = -15 fires a single trade deep).
            // In paper we're supposed to be LEARNING — push the floor
            // to -25 so only tokens with multiple bad prints get skipped.
            // Live keeps the tighter -12 so we don't chase proven losers
            // with real SOL.
            val isPaper = try {
                com.lifecyclebot.engine.BotService.instance?.let {
                    com.lifecyclebot.data.ConfigStore.load(it.applicationContext).paperMode
                } ?: true
            } catch (_: Exception) { true }
            val floor = if (isPaper) -25 else -12
            if (memoryScore <= floor) {
                logger.stage(
                    "PRE_SCORE_KILL",
                    candidate.symbol,
                    "BLOCKED",
                    "memory=$memoryScore <= $floor -> SHADOW (skip scoring)"
                )
                PreScoreKill(
                    memoryScore = memoryScore,
                    reason = "MEMORY_VERY_NEGATIVE_$memoryScore"
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class PreProposalKill(
        val setupQuality: String,
        val memoryScore: Int,
        val reason: String
    )

    private fun checkPreProposalKill(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        effectiveConfidence: Int
    ): PreProposalKill? {
        val earlyQuality = deriveSetupQuality(scoreCard.total)
        val memoryScore = scoreCard.byName("memory")?.value ?: 0

        if (earlyQuality != "C") return null

        // V5.7: Fixed — minimum floor was 15% even at 1% learning (blocked bootstrap trades)
        // At 1% learning: floor=10. At 75% learning: floor=29. At 100% learning: floor=40.
        val fluidKillFloor = try {
            val p = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
            (10 + (p * 30)).toInt().coerceIn(10, 40)
        } catch (_: Exception) { 35 }

        // V5.9.63: also loosened — paper -25, live -12 (was -10 both)
        val memoryFloorPre = try {
            val isPaper = com.lifecyclebot.engine.BotService.instance?.let {
                com.lifecyclebot.data.ConfigStore.load(it.applicationContext).paperMode
            } ?: true
            if (isPaper) -25 else -12
        } catch (_: Exception) { -12 }
        val shouldKillEarly = effectiveConfidence < fluidKillFloor || memoryScore <= memoryFloorPre
        if (!shouldKillEarly) return null

        val reason = when {
            effectiveConfidence < fluidKillFloor && memoryScore <= memoryFloorPre ->
                "C_GRADE_LOW_CONF_${effectiveConfidence}_BAD_MEMORY_$memoryScore"
            effectiveConfidence < fluidKillFloor ->
                "C_GRADE_CONF_FLOOR_$effectiveConfidence"
            else ->
                "C_GRADE_BAD_MEMORY_$memoryScore"
        }

        return PreProposalKill(
            setupQuality = earlyQuality,
            memoryScore = memoryScore,
            reason = reason
        )
    }

    // V5.4 FLUID: B-grade threshold matches DecisionEngine's fluid min score
    // Bootstrap: B at score >= 20 (not 45) so tokens aren't all C-graded during learning
    private fun deriveSetupQuality(finalScore: Int): String {
        val fluidBThreshold = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getMinScoreThreshold()  // 20 bootstrap → 30 mature
        } catch (_: Exception) { 45 }
        return when {
            finalScore >= (fluidBThreshold * 2) -> "B"  // 40 bootstrap → 60 mature
            finalScore >= fluidBThreshold        -> "B"  // 20 bootstrap → 30 mature
            else -> "C"
        }
    }

    private fun checkLiquidityFloor(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        band: DecisionBand,
        setupQuality: String,
        isPaperMode: Boolean,
        effectiveConfidence: Int
    ): ProcessResult? {
        val executionBands = setOf(
            DecisionBand.EXECUTE_SMALL,
            DecisionBand.EXECUTE_STANDARD,
            DecisionBand.EXECUTE_AGGRESSIVE
        )

        if (band !in executionBands) return null

        val liquidityFloor = when {
            isPaperMode -> 3000.0
            setupQuality == "B" -> 7500.0
            else -> 10000.0
        }

        if (candidate.liquidityUsd >= liquidityFloor) return null

        if (isPaperMode && candidate.liquidityUsd >= 3000.0) {
            logger.stage(
                "LIQUIDITY_CHECK",
                candidate.symbol,
                "PAPER_BYPASS",
                "liq=$${candidate.liquidityUsd.toInt()} < $${liquidityFloor.toInt()} -> PAPER MODE: proceeding anyway"
            )
            return null
        }

        logger.stage(
            "LIQUIDITY_CHECK",
            candidate.symbol,
            "BLOCKED",
            "liq=$${candidate.liquidityUsd.toInt()} < $${liquidityFloor.toInt()} floor for $setupQuality-grade -> WATCH ONLY"
        )
        lifecycle.mark(candidate.mint, LifecycleState.WATCH)
        shadowTracker.track(
            candidate,
            scoreCard,
            effectiveConfidence,
            "LOW_LIQUIDITY_${candidate.liquidityUsd.toInt()}"
        )
        return ProcessResult.Watch(
            score = 0.0,
            confidence = effectiveConfidence.toDouble()
        )
    }

    private fun checkLooper(
        candidate: CandidateSnapshot,
        decision: DecisionResult,
        setupQuality: String,
        confidence: Int,
        isBootstrap: Boolean,
        scoreCard: ScoreCard
    ): ProcessResult? {
        val executionBands = setOf(
            DecisionBand.EXECUTE_SMALL,
            DecisionBand.EXECUTE_STANDARD,
            DecisionBand.EXECUTE_AGGRESSIVE
        )

        if (isBootstrap || decision.band !in executionBands) return null

        if (CGradeLooperTracker.shouldBlockCGradeLooper(candidate.mint, setupQuality, decision.effectiveConfidence)) {
            logger.stage(
                "LOOPER_CHECK",
                candidate.symbol,
                "BLOCKED",
                "C-grade looper: quality=$setupQuality conf=${decision.effectiveConfidence} (repeated proposal)"
            )
            lifecycle.mark(candidate.mint, LifecycleState.WATCH)
            shadowTracker.track(candidate, scoreCard, confidence, "C_GRADE_LOOPER_BLOCKED")
            com.lifecyclebot.engine.MemePipelineTracer.blocked(
                candidate.mint, candidate.symbol,
                reason = "C_GRADE_LOOPER_BLOCKED",
                detail = "quality=$setupQuality conf=${decision.effectiveConfidence}",
            )
            return ProcessResult.Watch(
                score = decision.finalScore.toDouble(),
                confidence = confidence.toDouble()
            )
        }

        CGradeLooperTracker.recordProposal(candidate.mint, setupQuality, decision.effectiveConfidence)
        return null
    }

    private fun handleBlockFatal(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        effectiveConfidence: Int,
        decision: DecisionResult
    ): ProcessResult {
        lifecycle.mark(candidate.mint, LifecycleState.BLOCKED_FATAL)
        shadowTracker.track(candidate, scoreCard, effectiveConfidence, decision.fatalReason ?: "FATAL")
        lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
        com.lifecyclebot.engine.MemePipelineTracer.blocked(
            candidate.mint, candidate.symbol,
            reason = "BLOCK_FATAL", detail = decision.fatalReason ?: "FATAL",
        )

        openShadowTradeForLearning(
            candidate = candidate,
            scoreCard = scoreCard,
            effectiveConfidence = effectiveConfidence,
            decision = decision,
            reason = "BLOCKED_FATAL"
        )

        return ProcessResult.BlockFatal(decision.fatalReason ?: "FATAL")
    }

    private fun handleWatch(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        effectiveConfidence: Int,
        decision: DecisionResult
    ): ProcessResult {
        lifecycle.mark(candidate.mint, LifecycleState.WATCH)
        shadowTracker.track(candidate, scoreCard, effectiveConfidence, "WATCH")
        lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
        com.lifecyclebot.engine.MemePipelineTracer.blocked(
            candidate.mint, candidate.symbol,
            reason = "DECISION_WATCH",
            detail = "score=${decision.finalScore} conf=${effectiveConfidence} band=${decision.band}",
        )

        openShadowTradeForLearning(
            candidate = candidate,
            scoreCard = scoreCard,
            effectiveConfidence = effectiveConfidence,
            decision = decision,
            reason = "WATCH"
        )

        return ProcessResult.Watch(
            score = decision.finalScore.toDouble(),
            confidence = effectiveConfidence.toDouble()
        )
    }

    private fun handleReject(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        effectiveConfidence: Int,
        decision: DecisionResult
    ): ProcessResult {
        lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
        com.lifecyclebot.engine.MemePipelineTracer.blocked(
            candidate.mint, candidate.symbol,
            reason = "DECISION_REJECT",
            detail = "score=${decision.finalScore} conf=${effectiveConfidence} band=${decision.band}",
        )

        if (decision.finalScore >= ctx.config.shadowTrackNearMissMin) {
            shadowTracker.track(candidate, scoreCard, effectiveConfidence, "NEAR_MISS_REJECT")
            lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)

            openShadowTradeForLearning(
                candidate = candidate,
                scoreCard = scoreCard,
                effectiveConfidence = effectiveConfidence,
                decision = decision,
                reason = "NEAR_MISS"
            )
        }

        return ProcessResult.Rejected("SCORE_TOO_LOW")
    }

    private fun handleExecute(
        candidate: CandidateSnapshot,
        wallet: WalletSnapshot,
        risk: PortfolioRiskState,
        confidenceEffective: Int,
        decision: DecisionResult,
        scoreCard: ScoreCard
    ): ProcessResult {
        lifecycle.mark(candidate.mint, LifecycleState.EXECUTE_READY)

        // V5.9.495z49 — operator P0: meme-pipeline tracing.
        com.lifecyclebot.engine.MemePipelineTracer.stage(
            "V3_EXECUTE_READY", candidate.mint, candidate.symbol,
            "band=${decision.band} score=${decision.finalScore} conf=${confidenceEffective}",
        )

        val size = smartSizer.compute(
            band = decision.band,
            wallet = wallet,
            confidence = confidenceEffective,
            candidate = candidate,
            risk = risk,
            mode = ctx.mode
        )

        logger.stage("SIZING", candidate.symbol, "OK", "size=${"%.4f".format(size.sizeSol)} SOL")

        if (size.sizeSol <= 0.0) {
            lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
            shadowTracker.track(candidate, scoreCard, confidenceEffective, "SIZE_ZERO")
            lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
            com.lifecyclebot.engine.MemePipelineTracer.blocked(
                candidate.mint, candidate.symbol, reason = "SIZE_ZERO",
                detail = "smartSizer returned 0 — band=${decision.band} conf=${confidenceEffective}",
            )
            return ProcessResult.Rejected("SIZE_ZERO")
        }

        com.lifecyclebot.engine.MemePipelineTracer.stage(
            "EXECUTOR_START", candidate.mint, candidate.symbol,
            "size=${"%.4f".format(size.sizeSol)} SOL",
        )
        val execResult = tradeExecutor.execute(candidate, size, decision, scoreCard)
        lifecycle.mark(candidate.mint, LifecycleState.EXECUTED)

        if (execResult.success) {
            com.lifecyclebot.engine.MemePipelineTracer.stage(
                "EXECUTOR_DONE", candidate.mint, candidate.symbol,
                "sig=${execResult.txSignature?.take(16) ?: "<paper/stub>"} size=${"%.4f".format(execResult.executedSize)} SOL",
            )
        } else {
            com.lifecyclebot.engine.MemePipelineTracer.blocked(
                candidate.mint, candidate.symbol,
                reason = "EXECUTOR_FAILED",
                detail = "err=${execResult.error?.take(80) ?: "?"}",
            )
        }

        val breakdown = scoreCard.components.joinToString(" ") { "${it.name}=${it.value}" }

        return ProcessResult.Executed(
            band = decision.band,
            sizeSol = size.sizeSol,
            score = decision.finalScore,
            confidence = confidenceEffective,
            txSignature = execResult.txSignature,
            breakdown = breakdown
        )
    }
}

sealed class ProcessResult {
    data class Executed(
        val band: DecisionBand,
        val sizeSol: Double,
        val score: Int,
        val confidence: Int,
        val txSignature: String?,
        val breakdown: String = ""
    ) : ProcessResult()

    data class Watch(
        val score: Double,
        val confidence: Double
    ) : ProcessResult()

    data class ShadowOnly(
        val score: Double,
        val confidence: Double,
        val reason: String
    ) : ProcessResult()

    data class Rejected(val reason: String) : ProcessResult()

    data class BlockFatal(val reason: String) : ProcessResult()

    data class Blocked(val reason: String) : ProcessResult()
}

/**
 * Opens a shadow trade in ShadowLearningEngine for AI calibration.
 * Called whenever a trade is BLOCKED / WATCH / near-miss REJECT so we can
 * track what would have happened and feed outcomes to downstream learning.
 */
private fun openShadowTradeForLearning(
    candidate: CandidateSnapshot,
    scoreCard: ScoreCard,
    effectiveConfidence: Int,
    decision: DecisionResult,
    reason: String
) {
    try {
        val entryPrice = candidate.extraDouble("price").takeIf { it > 0.0 }
            ?: candidate.extraDouble("priceUsd").takeIf { it > 0.0 }
            ?: 0.0

        if (entryPrice <= 0.0) return

        val aiPredictions = scoreCard.components.associate { it.name to it.value }

        val regime = candidate.extraString("regime")
            ?: candidate.extraString("marketType")
            ?: "UNKNOWN"

        val mode = candidate.extraString("tradingMode")
            ?: candidate.extraString("mode")
            ?: decision.band.name

        val setupQuality = when {
            scoreCard.total >= 75 && effectiveConfidence >= 60 -> "A+"
            scoreCard.total >= 65 && effectiveConfidence >= 50 -> "A"
            scoreCard.total >= 55 && effectiveConfidence >= 40 -> "B"
            else -> "C"
        }

        com.lifecyclebot.v3.learning.ShadowLearningEngine.openShadowLong(
            mint = candidate.mint,
            symbol = candidate.symbol,
            entryPrice = entryPrice,
            aiConfidence = effectiveConfidence,
            setupQuality = setupQuality,
            regime = regime,
            mode = mode,
            aiPredictions = aiPredictions
        )
    } catch (e: Exception) {
        com.lifecyclebot.engine.ErrorLogger.debug(
            "BotOrchestrator",
            "Shadow trade open failed: ${e.message}"
        )
    }
}