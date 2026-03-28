package com.lifecyclebot.v3.core

import com.lifecyclebot.v3.decision.ConfidenceBreakdown
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
import com.lifecyclebot.v3.scoring.UnifiedScorer
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.SmartSizerV3
import com.lifecyclebot.v3.sizing.WalletSnapshot

/**
 * V3 Bot Orchestrator
 * Main pipeline coordinator
 * 
 * Flow:
 * DISCOVERY → ELIGIBILITY → SCORING → FATAL CHECK → CONFIDENCE → DECISION → LOOPER CHECK → SIZING → EXECUTE
 * 
 * V3 SELECTIVITY: Added C-grade looper detection after DECISION.
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
    /**
     * Process a candidate through the full pipeline
     */
    fun processCandidate(
        candidate: CandidateSnapshot,
        wallet: WalletSnapshot,
        risk: PortfolioRiskState,
        learningMetrics: LearningMetrics,
        opsMetrics: OpsMetrics
    ): ProcessResult {
        // ─── DISCOVERY ───
        lifecycle.mark(candidate.mint, LifecycleState.DISCOVERED)
        logger.stage("DISCOVERY", candidate.symbol, "FOUND",
            "src=${candidate.source} liq=${candidate.liquidityUsd} age=${candidate.ageMinutes}m")
        
        // ─── ELIGIBILITY (Hard gates only) ───
        val eligibility = eligibilityGate.evaluate(candidate)
        if (!eligibility.passed) {
            lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
            logger.stage("ELIGIBILITY", candidate.symbol, "FAIL", eligibility.reason)
            return ProcessResult.Rejected(eligibility.reason)
        }
        lifecycle.mark(candidate.mint, LifecycleState.ELIGIBLE)
        logger.stage("ELIGIBILITY", candidate.symbol, "PASS", "candidate eligible")
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2 PRE-SCORE MEMORY CHECK: Skip scoring for known losers
        // 
        // If TokenWinMemory score is very negative (≤ -10), this token has
        // consistently lost money. Skip scoring entirely → straight to SHADOW.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val memoryScore = com.lifecyclebot.engine.TokenWinMemory.getMemoryScoreForMint(candidate.mint)
            if (memoryScore <= -10) {
                logger.stage("PRE_SCORE_KILL", candidate.symbol, "BLOCKED",
                    "memory=$memoryScore ≤ -10 → SHADOW (skip scoring)")
                lifecycle.mark(candidate.mint, LifecycleState.WATCH)
                shadowTracker.trackEarly(candidate, memoryScore, "MEMORY_VERY_NEGATIVE_$memoryScore")
                return ProcessResult.Watch(0.0, 0.0)
            }
        } catch (e: Exception) {
            // Memory not available - continue to scoring
        }
        
        // ─── SCORING (The unlock - everything is a score) ───
        val scoreCard = unifiedScorer.score(candidate, ctx)
        lifecycle.mark(candidate.mint, LifecycleState.SCORED)
        logger.stage("SCORING", candidate.symbol, "OK",
            "total=${scoreCard.total} :: ${scoreCard.components.joinToString(" | ") { "${it.name}=${it.value}" }}")
        
        // ─── FATAL RISK CHECK (Only truly fatal conditions) ───
        val fatal = fatalRiskChecker.check(candidate, ctx)
        logger.stage("FATAL", candidate.symbol, 
            if (fatal.blocked) "BLOCK" else "PASS", 
            fatal.reason ?: "none")
        
        // ─── CONFIDENCE ───
        val confidence = confidenceEngine.compute(scoreCard, learningMetrics, opsMetrics)
        logger.stage("CONFIDENCE", candidate.symbol, "OK",
            "stat=${confidence.statistical} struct=${confidence.structural} ops=${confidence.operational} eff=${confidence.effective}")
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2 PRE-PROPOSAL KILL: C-GRADE GARBAGE DETECTION
        // 
        // For quality=C setups, if:
        //   - confidence < 35% OR
        //   - memory score <= -8 (negative win history)
        // 
        // Then: Skip CANDIDATE/PROPOSED/SIZING entirely → straight to SHADOW_TRACK
        // 
        // This reduces wasted cycles on obvious garbage that FDG would kill anyway.
        // ═══════════════════════════════════════════════════════════════════
        val earlyQuality = when {
            scoreCard.total >= 55 -> "B"  // B+ grade
            scoreCard.total >= 45 -> "B"  // B grade
            else -> "C"                   // C grade
        }
        val memoryScore = scoreCard.byName("memory")?.value ?: 0
        
        if (earlyQuality == "C") {
            val effConf = confidence.effective
            val shouldKillEarly = (effConf < 35) || (memoryScore <= -8)
            
            if (shouldKillEarly) {
                val reason = when {
                    effConf < 35 && memoryScore <= -8 -> "C_GRADE_LOW_CONF_${effConf.toInt()}_BAD_MEMORY_${memoryScore}"
                    effConf < 35 -> "C_GRADE_CONF_FLOOR_${effConf.toInt()}"
                    else -> "C_GRADE_BAD_MEMORY_${memoryScore}"
                }
                logger.stage("PRE_PROPOSAL_KILL", candidate.symbol, "SHADOW_ONLY",
                    "quality=$earlyQuality conf=${effConf.toInt()}% memory=$memoryScore → SHADOW_TRACK (no CANDIDATE/PROPOSED/SIZING)")
                lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                shadowTracker.track(candidate, scoreCard, effConf.toInt(), reason)
                return ProcessResult.ShadowOnly(scoreCard.total.toDouble(), effConf.toDouble(), reason)
            }
        }
        
        // ─── FINAL DECISION ───
        val decision = finalDecisionEngine.decide(scoreCard, confidence, fatal)
        logger.stage("DECISION", candidate.symbol, decision.band.name,
            "score=${decision.finalScore} conf=${decision.effectiveConfidence}")
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: LIQUIDITY FLOOR CHECK
        // 
        // Execution floors (BEFORE any execute band routing):
        //   - C-grade: $10,000 minimum
        //   - B-grade: $7,500 minimum
        //   - Below = WATCH/SHADOW only
        // ═══════════════════════════════════════════════════════════════════
        val setupQuality = when {
            decision.finalScore >= 55 -> "B"  // B+ grade
            decision.finalScore >= 45 -> "B"  // B grade
            else -> "C"                       // C grade
        }
        
        val liquidityFloor = when (setupQuality) {
            "B" -> 7500.0
            else -> 10000.0  // C-grade needs higher liquidity
        }
        
        if (decision.band in listOf(DecisionBand.EXECUTE_SMALL, DecisionBand.EXECUTE_STANDARD, DecisionBand.EXECUTE_AGGRESSIVE)) {
            if (candidate.liquidityUsd < liquidityFloor) {
                logger.stage("LIQUIDITY_CHECK", candidate.symbol, "BLOCKED",
                    "liq=$${candidate.liquidityUsd.toInt()} < $${liquidityFloor.toInt()} floor for $setupQuality-grade → WATCH ONLY")
                lifecycle.mark(candidate.mint, LifecycleState.WATCH)
                shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), "LOW_LIQUIDITY_${candidate.liquidityUsd.toInt()}")
                return ProcessResult.Watch(decision.finalScore.toDouble(), confidence.effective.toDouble())
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: C-GRADE LOOPER CHECK
        // 
        // Prevents repeated C-grade + low-conf proposals from clogging pipeline.
        // If a token has been proposed 2+ times recently with C-grade + conf < 35,
        // force it to WATCH instead of EXECUTE.
        // ═══════════════════════════════════════════════════════════════════
        
        // Check for C-grade looper before routing to execute
        if (decision.band in listOf(DecisionBand.EXECUTE_SMALL, DecisionBand.EXECUTE_STANDARD, DecisionBand.EXECUTE_AGGRESSIVE)) {
            if (CGradeLooperTracker.shouldBlockCGradeLooper(candidate.mint, setupQuality, decision.effectiveConfidence)) {
                logger.stage("LOOPER_CHECK", candidate.symbol, "BLOCKED",
                    "C-grade looper: quality=$setupQuality conf=${decision.effectiveConfidence} (repeated proposal)")
                lifecycle.mark(candidate.mint, LifecycleState.WATCH)
                shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), "C_GRADE_LOOPER_BLOCKED")
                return ProcessResult.Watch(decision.finalScore.toDouble(), confidence.effective.toDouble())
            }
            // Record this proposal for future looper detection
            CGradeLooperTracker.recordProposal(candidate.mint, setupQuality, decision.effectiveConfidence)
        }
        
        // ─── ROUTE BY BAND ───
        return when (decision.band) {
            DecisionBand.BLOCK_FATAL -> {
                lifecycle.mark(candidate.mint, LifecycleState.BLOCKED_FATAL)
                shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), decision.fatalReason ?: "FATAL")
                lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                
                // V3.2: Open shadow trade for AI learning
                openShadowTradeForLearning(candidate, scoreCard, confidence, decision, "BLOCKED_FATAL")
                
                ProcessResult.BlockFatal(decision.fatalReason ?: "FATAL")
            }
            
            DecisionBand.WATCH -> {
                lifecycle.mark(candidate.mint, LifecycleState.WATCH)
                shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), "WATCH")
                lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                
                // V3.2: Open shadow trade for AI learning
                openShadowTradeForLearning(candidate, scoreCard, confidence, decision, "WATCH")
                
                ProcessResult.Watch(decision.finalScore.toDouble(), confidence.effective.toDouble())
            }
            
            DecisionBand.REJECT -> {
                lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
                // Shadow track near-misses for learning
                if (decision.finalScore >= ctx.config.shadowTrackNearMissMin) {
                    shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), "NEAR_MISS_REJECT")
                    lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                    
                    // V3.2: Near-miss is ESPECIALLY valuable for shadow learning
                    openShadowTradeForLearning(candidate, scoreCard, confidence, decision, "NEAR_MISS")
                }
                ProcessResult.Rejected("SCORE_TOO_LOW")
            }
            
            DecisionBand.EXECUTE_SMALL,
            DecisionBand.EXECUTE_STANDARD,
            DecisionBand.EXECUTE_AGGRESSIVE -> {
                lifecycle.mark(candidate.mint, LifecycleState.EXECUTE_READY)
                
                // ─── SIZING ───
                val size = smartSizer.compute(
                    band = decision.band,
                    wallet = wallet,
                    confidence = confidence.effective,
                    candidate = candidate,
                    risk = risk,
                    mode = ctx.mode
                )
                logger.stage("SIZING", candidate.symbol, "OK", "size=${"%.4f".format(size.sizeSol)} SOL")
                
                // Size zero = can't execute
                if (size.sizeSol <= 0.0) {
                    lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
                    shadowTracker.track(candidate, scoreCard, confidence.effective.toInt(), "SIZE_ZERO")
                    lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                    return ProcessResult.Rejected("SIZE_ZERO")
                }
                
                // ─── EXECUTE ───
                val execResult = tradeExecutor.execute(candidate, size, decision, scoreCard)
                lifecycle.mark(candidate.mint, LifecycleState.EXECUTED)
                
                // Build breakdown string from scoreCard
                val breakdown = scoreCard.components.joinToString(" ") { "${it.name}=${it.value}" }
                
                ProcessResult.Executed(
                    band = decision.band,
                    sizeSol = size.sizeSol,
                    score = decision.finalScore,
                    confidence = confidence.effective.toInt(),
                    txSignature = execResult.txSignature,
                    breakdown = breakdown
                )
            }
        }
    }
}

/**
 * V3 Process Result
 * Outcome of processing a candidate
 * 
 * V3.2 UNIFIED DECISION LABELS:
 * - EXECUTE_*: Will trade (MICRO, STANDARD, AGGRESSIVE)
 * - WATCH: Tracking only, insufficient quality
 * - SHADOW_ONLY: Pre-proposal kill, tracking in shadow
 * - BLOCK_FATAL: Fatal risk detected, hard block
 * - REJECTED: Poor setup, don't track
 */
sealed class ProcessResult {
    data class Executed(
        val band: DecisionBand,
        val sizeSol: Double,
        val score: Int,
        val confidence: Int,
        val txSignature: String?,
        val breakdown: String = ""  // Score breakdown for logging
    ) : ProcessResult()
    
    data class Watch(
        val score: Double,
        val confidence: Double
    ) : ProcessResult()
    
    /** Pre-proposal kill - garbage killed early, tracked in shadow for learning */
    data class ShadowOnly(
        val score: Double,
        val confidence: Double,
        val reason: String
    ) : ProcessResult()
    
    data class Rejected(val reason: String) : ProcessResult()
    
    /** Fatal block - hard risk detected (rug, scam, etc.) */
    data class BlockFatal(val reason: String) : ProcessResult()
    
    /** Legacy alias for BlockFatal - to be deprecated */
    data class Blocked(val reason: String) : ProcessResult()
}

/**
 * V3.2: Open a shadow trade in the ShadowLearningEngine for AI calibration.
 * This is called whenever a trade is BLOCKED/WATCH/REJECTED so we can
 * track what would have happened and feed outcomes to MetaCognitionAI.
 */
private fun openShadowTradeForLearning(
    candidate: com.lifecyclebot.v3.scanner.CandidateSnapshot,
    scoreCard: com.lifecyclebot.v3.scoring.ScoreCard,
    confidence: com.lifecyclebot.v3.decision.ConfidenceBreakdown,
    decision: com.lifecyclebot.v3.decision.DecisionResult,
    reason: String
) {
    try {
        val entryPrice = candidate.extraDouble("price").takeIf { it > 0 }
            ?: candidate.extraDouble("priceUsd")
            ?: 0.0
        
        if (entryPrice <= 0) return  // Can't track without price
        
        // Build AI predictions map from score components
        val aiPredictions = scoreCard.components.associate { 
            it.name to it.value 
        }
        
        // Determine regime from candidate extras or default
        val regime = candidate.extraString("regime")
            ?: candidate.extraString("marketType")
            ?: "UNKNOWN"
        
        val mode = candidate.extraString("tradingMode")
            ?: candidate.extraString("mode")
            ?: decision.band.name
        
        // Derive setupQuality from score and confidence (DecisionResult has no setupQuality)
        val setupQuality = when {
            scoreCard.total >= 75 && confidence.effective >= 60 -> "A+"
            scoreCard.total >= 65 && confidence.effective >= 50 -> "A"
            scoreCard.total >= 55 && confidence.effective >= 40 -> "B"
            else -> "C"
        }
        
        com.lifecyclebot.v3.learning.ShadowLearningEngine.openShadowLong(
            mint = candidate.mint,
            symbol = candidate.symbol,
            entryPrice = entryPrice,
            aiConfidence = confidence.effective.toInt(),
            setupQuality = setupQuality,
            regime = regime,
            mode = mode,
            aiPredictions = aiPredictions
        )
    } catch (e: Exception) {
        // Don't let shadow tracking failures break the main flow
        com.lifecyclebot.engine.ErrorLogger.debug("BotOrchestrator", 
            "Shadow trade open failed: ${e.message}")
    }
}
