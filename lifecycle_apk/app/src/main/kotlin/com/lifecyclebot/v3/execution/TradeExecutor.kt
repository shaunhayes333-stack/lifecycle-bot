package com.lifecyclebot.v3.execution

import com.lifecyclebot.v3.decision.DecisionResult
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.v3.sizing.SizeResult
import com.lifecyclebot.engine.ErrorLogger

/**
 * V3 Trade Executor
 * Executes trades based on decision
 * 
 * WIRED: Delegates to existing Executor for Jupiter API integration
 */
class TradeExecutor {
    
    companion object {
        private const val TAG = "V3TradeExecutor"
        
        // Callback to actual execution (set by V3EngineManager)
        @Volatile
        var executeCallback: ((CandidateSnapshot, SizeResult, DecisionResult, ScoreCard) -> TradeExecutionResult)? = null
    }
    
    /**
     * Execute a trade via Jupiter API
     * Delegates to the existing Executor which handles all Jupiter logic
     */
    fun execute(
        candidate: CandidateSnapshot,
        size: SizeResult,
        decision: DecisionResult,
        scoreCard: ScoreCard
    ): TradeExecutionResult {
        ErrorLogger.info(TAG, 
            "[EXECUTION] ${candidate.symbol} | size=${"%.4f".format(size.sizeSol)} SOL | " +
            "band=${decision.band} | score=${decision.finalScore} | conf=${decision.effectiveConfidence}")
        ErrorLogger.info(TAG, 
            "[THESIS] ${scoreCard.components.joinToString(" | ") { "${it.name}:${it.value}" }}")
        
        // Use callback if wired, otherwise return stub result
        val callback = executeCallback
        if (callback != null) {
            return try {
                callback(candidate, size, decision, scoreCard)
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Execution callback failed: ${e.message}")
                TradeExecutionResult(
                    success = false,
                    txSignature = null,
                    executedSize = 0.0,
                    executedPrice = null,
                    error = e.message
                )
            }
        }
        
        // Stub result when not wired (paper mode or testing)
        ErrorLogger.info(TAG, "[STUB] No execute callback - returning stub result")
        return TradeExecutionResult(
            success = true,
            txSignature = null,
            executedSize = size.sizeSol,
            executedPrice = null,
            error = null
        )
    }
}

/**
 * V3 Trade Execution Result
 */
data class TradeExecutionResult(
    val success: Boolean,
    val txSignature: String?,
    val executedSize: Double,
    val executedPrice: Double?,
    val error: String?
)

/**
 * V3 Bot Logger
 * Uses ErrorLogger for consistent logging
 */
class BotLogger {
    fun stage(stage: String, symbol: String, result: String, detail: String) {
        ErrorLogger.info("V3|$stage", "$symbol | $result | $detail")
    }
    
    fun info(tag: String, message: String) {
        ErrorLogger.info("V3|$tag", message)
    }
    
    fun warn(tag: String, message: String) {
        ErrorLogger.warn("V3|$tag", message)
    }
    
    fun error(tag: String, message: String) {
        ErrorLogger.error("V3|$tag", message)
    }
}
