package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.AICrossTalk
import com.lifecyclebot.engine.SuperBrainEnhancements
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.engine.BehaviorLearning
import com.lifecyclebot.engine.DistributionFadeAvoider
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.core.TradingContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ArbScannerAI - Main interface for arbitrage opportunity detection
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * This is the top-level evaluator that:
 * 1. Receives candidates from the scanner
 * 2. Records source timing data
 * 3. Runs all arb models via ArbCoordinator
 * 4. Applies safety rules
 * 5. Returns actionable ArbEvaluation
 * 
 * INTEGRATION POINTS:
 * - Scanner: Calls recordSourceSeen() on discovery
 * - V3 Pipeline: Calls evaluate() alongside normal scoring
 * - Executor: Uses result for arb-specific execution
 * - CrossTalk: Shares signals with other AI layers
 * - SuperBrain: Reports arb insights for dashboard
 */
object ArbScannerAI {
    
    private const val TAG = "ArbScanner"
    
    // Stats tracking
    private val evaluationCount = AtomicInteger(0)
    private val opportunityCount = AtomicInteger(0)
    private val rejectedCount = AtomicInteger(0)
    
    // Recent evaluations cache (for deduplication)
    private data class CachedEval(val eval: ArbEvaluation?, val timestamp: Long)
    private val evalCache = ConcurrentHashMap<String, CachedEval>()
    private const val CACHE_TTL_MS = 10_000L  // 10 second cache
    
    // Rejection tracking (avoid repeated evaluations of bad candidates)
    private val recentRejections = ConcurrentHashMap<String, Long>()
    private const val REJECTION_COOLDOWN_MS = 5_000L  // 5 second cooldown after rejection
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE TIMING INTEGRATION
    // Called by scanner when a token is discovered on a source
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record that a token was seen on a source.
     * This powers venue lag detection.
     */
    fun recordSourceSeen(
        mint: String,
        source: String,
        price: Double?,
        liquidityUsd: Double,
        buyPressurePct: Double?
    ) {
        SourceTimingRegistry.record(mint, source, price, liquidityUsd, buyPressurePct)
    }
    
    /**
     * Record from CandidateSnapshot (convenience method).
     */
    fun recordSourceSeen(candidate: CandidateSnapshot) {
        val price = candidate.extra["price"] as? Double
            ?: candidate.extra["priceUsd"] as? Double
        
        recordSourceSeen(
            mint = candidate.mint,
            source = candidate.source.name,
            price = price,
            liquidityUsd = candidate.liquidityUsd,
            buyPressurePct = candidate.buyPressurePct
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate a candidate for arb opportunities.
     * Returns ArbEvaluation if opportunity found, null otherwise.
     */
    fun evaluate(candidate: CandidateSnapshot, ctx: TradingContext? = null): ArbEvaluation? {
        evaluationCount.incrementAndGet()
        val mint = candidate.mint
        val symbol = candidate.symbol
        
        try {
            // ═══════════════════════════════════════════════════════════════════
            // CACHE CHECK
            // ═══════════════════════════════════════════════════════════════════
            val now = System.currentTimeMillis()
            val cached = evalCache[mint]
            if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
                return cached.eval
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // REJECTION COOLDOWN CHECK
            // ═══════════════════════════════════════════════════════════════════
            val lastRejection = recentRejections[mint]
            if (lastRejection != null && (now - lastRejection) < REJECTION_COOLDOWN_MS) {
                return null  // Still in cooldown
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // SAFETY RULES - PRE-EVALUATION
            // ═══════════════════════════════════════════════════════════════════
            val safetyResult = applySafetyRules(candidate, ctx)
            if (safetyResult != null) {
                rejectedCount.incrementAndGet()
                recentRejections[mint] = now
                ErrorLogger.debug(TAG, "[ARB] $symbol rejected: $safetyResult")
                evalCache[mint] = CachedEval(null, now)
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // RUN ARB COORDINATOR
            // ═══════════════════════════════════════════════════════════════════
            val evaluation = ArbCoordinator.evaluate(candidate)
            
            // Cache result
            evalCache[mint] = CachedEval(evaluation, now)
            
            // Track stats
            if (evaluation != null && evaluation.band !in listOf(
                ArbDecisionBand.ARB_REJECT, 
                ArbDecisionBand.ARB_WATCH
            )) {
                opportunityCount.incrementAndGet()
                
                // Report to SuperBrain
                reportToSuperBrain(candidate, evaluation)
            }
            
            return evaluation
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "evaluate error for $symbol: ${e.message}")
            return null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SAFETY RULES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Apply safety rules before arb evaluation.
     * Returns rejection reason or null if safe.
     */
    private fun applySafetyRules(candidate: CandidateSnapshot, ctx: TradingContext?): String? {
        val mint = candidate.mint
        val symbol = candidate.symbol
        
        // 1. AI Degradation check (block marginal setups when AI unreliable)
        val isAIDegraded = try { GeminiCopilot.isAIDegraded() } catch (_: Exception) { false }
        if (isAIDegraded && candidate.buyPressurePct < 70) {
            return "AI_DEGRADED_MARGINAL"
        }
        
        // 2. Liquidity floor check (absolute minimum)
        if (candidate.liquidityUsd < 2_000) {
            return "LIQUIDITY_BELOW_FLOOR"
        }
        
        // 3. Fatal risk check
        val fatalRisk = try {
            candidate.extraBoolean("fatalRisk") ||
            DistributionFadeAvoider.isFatalSuppression(mint)
        } catch (_: Exception) { false }
        
        if (fatalRisk) {
            return "FATAL_RISK"
        }
        
        // 4. Token banned check (from context)
        if (ctx?.isBanned(mint) == true) {
            return "TOKEN_BANNED"
        }
        
        // 5. Buy pressure collapsing check
        if (candidate.buyPressurePct < 40) {
            return "BP_COLLAPSING"
        }
        
        // 6. Multiple recent losses check
        val recentLosses = try {
            ArbLearning.getRecentLossCount(mint)
        } catch (_: Exception) { 0 }
        
        if (recentLosses >= 2) {
            return "MULTIPLE_RECENT_LOSSES"
        }
        
        // 7. Confidence minimum check (will be checked per-model, but early filter)
        // Skip if context says arb disabled
        if (ctx?.isArbEnabled() == false) {
            return "ARB_DISABLED"
        }
        
        return null  // Safe to proceed
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SUPERBRAIN INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Report arb opportunity to SuperBrain for dashboard display.
     */
    private fun reportToSuperBrain(candidate: CandidateSnapshot, evaluation: ArbEvaluation) {
        try {
            SuperBrainEnhancements.recordSignal(
                mint = candidate.mint,
                symbol = candidate.symbol,
                source = "ArbScanner_${evaluation.arbType.name}",
                signalType = "BULLISH"  // Arb opportunities are by definition bullish entry signals
            )
            
            SuperBrainEnhancements.recordChartInsight(
                mint = candidate.mint,
                symbol = candidate.symbol,
                pattern = "ARB_${evaluation.arbType.name}",
                timeframe = "instant",
                confidence = evaluation.confidence.toDouble(),
                priceAtDetection = candidate.extra["price"] as? Double ?: 0.0
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "SuperBrain report error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS AND UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get stats string for logging.
     */
    fun getStats(): String {
        return "ArbScanner: ${evaluationCount.get()} evals | " +
               "${opportunityCount.get()} opportunities | " +
               "${rejectedCount.get()} rejected | " +
               "${ArbCoordinator.getStats()}"
    }
    
    /**
     * Get detailed stats.
     */
    fun getDetailedStats(): String {
        return buildString {
            appendLine("=== ARB SCANNER AI STATS ===")
            appendLine("Evaluations: ${evaluationCount.get()}")
            appendLine("Opportunities: ${opportunityCount.get()}")
            appendLine("Rejected: ${rejectedCount.get()}")
            appendLine()
            append(ArbCoordinator.getDetailedStats())
        }
    }
    
    /**
     * Cleanup old cache entries.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        
        // Clean eval cache
        evalCache.entries.removeIf { (_, cached) ->
            (now - cached.timestamp) > CACHE_TTL_MS * 2
        }
        
        // Clean rejection cooldowns
        recentRejections.entries.removeIf { (_, timestamp) ->
            (now - timestamp) > REJECTION_COOLDOWN_MS * 2
        }
        
        // Clean source timing
        SourceTimingRegistry.cleanup()
    }
    
    /**
     * Save state to JSON.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("evaluations", evaluationCount.get())
            put("opportunities", opportunityCount.get())
            put("rejected", rejectedCount.get())
            put("cacheSize", evalCache.size)
        }
    }
    
    /**
     * Reset all stats.
     */
    fun reset() {
        evaluationCount.set(0)
        opportunityCount.set(0)
        rejectedCount.set(0)
        evalCache.clear()
        recentRejections.clear()
        ArbCoordinator.resetStats()
        SourceTimingRegistry.clear()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EXTENSION: TradingContext helpers
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Check if arb trading is enabled in context.
 */
fun TradingContext.isArbEnabled(): Boolean {
    // Arb is enabled by default - can add config flag later
    return true
}

/**
 * Check if a token is banned (stub - integrate with actual ban system).
 */
fun TradingContext.isBanned(mint: String): Boolean {
    // TODO: Integrate with actual token ban system if exists
    return false
}
