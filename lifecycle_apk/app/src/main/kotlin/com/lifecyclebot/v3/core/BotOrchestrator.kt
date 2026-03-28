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
                    "liq=\$${candidate.liquidityUsd.toInt()} < \$${liquidityFloor.toInt()} floor for $setupQuality-grade → WATCH ONLY")
                lifecycle.mark(candidate.mint, LifecycleState.WATCH)
                shadowTracker.track(candidate, scoreCard, confidence.effective, "LOW_LIQUIDITY_\${candidate.liquidityUsd.toInt()}")
                return ProcessResult.Watch(decision.finalScore, confidence.effective)
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
                shadowTracker.track(candidate, scoreCard, confidence.effective, "C_GRADE_LOOPER_BLOCKED")
                return ProcessResult.Watch(decision.finalScore, confidence.effective)
            }
            // Record this proposal for future looper detection
            CGradeLooperTracker.recordProposal(candidate.mint, setupQuality, decision.effectiveConfidence)
        }
        
        // ─── ROUTE BY BAND ───
        return when (decision.band) {
            DecisionBand.BLOCK_FATAL -> {
                lifecycle.mark(candidate.mint, LifecycleState.BLOCKED_FATAL)
                shadowTracker.track(candidate, scoreCard, confidence.effective, decision.fatalReason ?: "FATAL")
                lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                ProcessResult.Blocked(decision.fatalReason ?: "FATAL")
            }
            
            DecisionBand.WATCH -> {
                lifecycle.mark(candidate.mint, LifecycleState.WATCH)
                shadowTracker.track(candidate, scoreCard, confidence.effective, "WATCH")
                lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
                ProcessResult.Watch(decision.finalScore, confidence.effective)
            }
            
            DecisionBand.REJECT -> {
                lifecycle.mark(candidate.mint, LifecycleState.REJECTED)
                // Shadow track near-misses for learning
                if (decision.finalScore >= ctx.config.shadowTrackNearMissMin) {
                    shadowTracker.track(candidate, scoreCard, confidence.effective, "NEAR_MISS_REJECT")
                    lifecycle.mark(candidate.mint, LifecycleState.SHADOW_TRACKED)
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
                    shadowTracker.track(candidate, scoreCard, confidence.effective, "SIZE_ZERO")
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
                    confidence = confidence.effective,
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
        val score: Int,
        val confidence: Int
    ) : ProcessResult()
    
    data class Rejected(val reason: String) : ProcessResult()
    
    data class Blocked(val reason: String) : ProcessResult()
}
