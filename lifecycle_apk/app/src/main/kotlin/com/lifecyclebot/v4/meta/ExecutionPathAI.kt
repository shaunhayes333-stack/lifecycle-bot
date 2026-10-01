package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ===============================================================================
 * EXECUTION PATH AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Underrated and directly monetizable. Choose the best route and execution
 * style, not just whether to trade.
 *
 * Sits between FinalDecisionEngine and Executor.
 * Teaches EducationAI which routes are consistently toxic.
 *
 * ===============================================================================
 */
object ExecutionPathAI {

    private const val TAG = "ExecutionPathAI"

    // Venue performance tracking
    private val venueStats = ConcurrentHashMap<String, VenuePerformance>()

    // Recent execution results
    private val recentExecutions = mutableListOf<ExecutionResult>()

    data class VenuePerformance(
        val venue: String,
        val successRate: Double,
        val avgSlippageBps: Double,
        val avgFillTimeSec: Double,
        val partialFillRate: Double,
        val failureRate: Double,
        val totalExecutions: Int,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    data class ExecutionResult(
        val venue: String,
        val success: Boolean,
        val slippageBps: Double,
        val fillTimeSec: Double,
        val partialFill: Boolean,
        val expectedPrice: Double,
        val actualPrice: Double,
        val sizeSol: Double,
        val congestionLevel: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    fun init() {
        // Seed default venue performance
        listOf("JUPITER_V6", "RAYDIUM", "ORCA", "PHOENIX").forEach { venue ->
            venueStats.putIfAbsent(venue, VenuePerformance(venue, 0.95, 20.0, 2.0, 0.05, 0.02, 0))
        }
        ErrorLogger.info(TAG, "ExecutionPathAI initialized")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECOMMEND — Get best execution path for a trade
    // ═══════════════════════════════════════════════════════════════════════

    fun recommend(
        sizeSol: Double,
        symbol: String,
        urgency: Double = 0.5,          // 0.0 = patient, 1.0 = immediate
        currentCongestion: Double = 0.0, // 0.0 = clear, 1.0 = congested
        routeQuality: Double = 1.0       // Jupiter route quality (0-1)
    ): ExecutionRecommendation {
        val reasons = mutableListOf<String>()

        // 1. Select best venue based on historical performance
        val bestVenue = venueStats.values
            .sortedByDescending { it.successRate - it.avgSlippageBps / 1000.0 }
            .firstOrNull()?.venue ?: "JUPITER_V6"
        reasons.add("Best venue: $bestVenue")

        // 2. Execution style based on size + urgency + congestion
        val executionStyle = when {
            urgency > 0.8 -> "MARKET"
            sizeSol > 2.0 && currentCongestion < 0.3 -> "SPLIT"
            currentCongestion > 0.7 -> "PATIENT"
            sizeSol > 5.0 -> "TWAP"
            else -> "MARKET"
        }
        reasons.add("Style: $executionStyle")

        // 3. Split orders for large sizes
        val splitOrders = when {
            sizeSol > 10.0 -> 5
            sizeSol > 5.0 -> 3
            sizeSol > 2.0 && executionStyle == "SPLIT" -> 2
            else -> 1
        }

        // 4. Cancel/retry policy
        val retryPolicy = when {
            currentCongestion > 0.8 -> "PATIENCE"
            urgency > 0.7 -> "AGGRESSIVE_RETRY"
            routeQuality < 0.5 -> "ABORT"
            else -> "AGGRESSIVE_RETRY"
        }

        // 5. Execution confidence
        val venuePerf = venueStats[bestVenue]
        val executionConfidence = (
            (venuePerf?.successRate ?: 0.9) * 0.4 +
            routeQuality * 0.3 +
            (1.0 - currentCongestion) * 0.2 +
            (1.0 - (venuePerf?.avgSlippageBps ?: 30.0) / 100.0).coerceIn(0.0, 1.0) * 0.1
        ).coerceIn(0.0, 1.0)

        // 6. Estimated slippage
        val baseSlippage = venuePerf?.avgSlippageBps ?: 30.0
        val adjustedSlippage = baseSlippage * (1.0 + currentCongestion * 0.5) * (1.0 + sizeSol / 10.0 * 0.2)

        val recommendation = ExecutionRecommendation(
            preferredVenue = bestVenue,
            executionStyle = executionStyle,
            splitOrders = splitOrders,
            cancelRetryPolicy = retryPolicy,
            executionConfidence = executionConfidence,
            estimatedSlippageBps = adjustedSlippage,
            routeQuality = routeQuality,
            congestionLevel = currentCongestion,
            reasons = reasons
        )

        // Publish to CrossTalk
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = "EXECUTION",
            symbol = symbol,
            confidence = executionConfidence,
            horizonSec = 30,
            executionConfidence = executionConfidence,
            riskFlags = if (executionConfidence < 0.5) listOf("LOW_EXECUTION_CONFIDENCE") else emptyList()
        ))

        return recommendation
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD — Feed execution results for learning
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * V5.0.7667 - canonical receipt bridge. Slippage is NaN unless a real
     * quote-vs-fill measurement exists; unknown must not masquerade as zero.
     */
    fun recordCanonicalReceipt7667(
        venue: String,
        success: Boolean,
        fillTimeSec: Double,
        partialFill: Boolean,
        sizeSol: Double,
    ) {
        recordExecution(
            ExecutionResult(
                venue = venue.ifBlank { "UNKNOWN" }.uppercase(),
                success = success,
                slippageBps = Double.NaN,
                fillTimeSec = fillTimeSec.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN,
                partialFill = partialFill,
                expectedPrice = Double.NaN,
                actualPrice = Double.NaN,
                sizeSol = sizeSol.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                congestionLevel = Double.NaN,
            )
        )
    }

    fun recordEndpointFailure7667(venue: String) {
        recordExecution(
            ExecutionResult(
                venue = venue.ifBlank { "UNKNOWN" }.uppercase(),
                success = false,
                slippageBps = Double.NaN,
                fillTimeSec = Double.NaN,
                partialFill = false,
                expectedPrice = Double.NaN,
                actualPrice = Double.NaN,
                sizeSol = 0.0,
                congestionLevel = Double.NaN,
            )
        )
    }

    fun recordExecution(result: ExecutionResult) {
        synchronized(recentExecutions) {
            recentExecutions.add(result)
            if (recentExecutions.size > 500) recentExecutions.removeAt(0)
        }

        // Update venue stats
        val venueResults = synchronized(recentExecutions) {
            recentExecutions.filter { it.venue == result.venue }.takeLast(50)
        }
        if (venueResults.size >= 5) {
            val old = venueStats[result.venue]
            val slip = venueResults.map { it.slippageBps }.filter { it.isFinite() && it >= 0.0 }
            val fill = venueResults.map { it.fillTimeSec }.filter { it.isFinite() && it >= 0.0 }
            venueStats[result.venue] = VenuePerformance(
                venue = result.venue,
                successRate = venueResults.count { it.success }.toDouble() / venueResults.size,
                avgSlippageBps = if (slip.isNotEmpty()) slip.average() else old?.avgSlippageBps ?: 30.0,
                avgFillTimeSec = if (fill.isNotEmpty()) fill.average() else old?.avgFillTimeSec ?: 2.0,
                partialFillRate = venueResults.count { it.partialFill }.toDouble() / venueResults.size,
                failureRate = venueResults.count { !it.success }.toDouble() / venueResults.size,
                totalExecutions = venueResults.size
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getVenueStats(): Map<String, VenuePerformance> = venueStats.toMap()
    fun getExecutionConfidenceMultiplier(): Double {
        val avgSuccess = venueStats.values.map { it.successRate }.average().takeIf { !it.isNaN() } ?: 0.9
        return avgSuccess.coerceIn(0.5, 1.0)
    }

    fun clear() {
        venueStats.clear()
        synchronized(recentExecutions) { recentExecutions.clear() }
        init()
    }
}
