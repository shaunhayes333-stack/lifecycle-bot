package com.lifecyclebot.v3.arb

/**
 * ArbScannerAI - Data Models
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Compact data structures for arb opportunity tracking and evaluation.
 */

/**
 * Snapshot of an arb opportunity at a point in time.
 * Contains all data needed for arb decision-making.
 */
data class ArbSnapshot(
    val mint: String,
    val symbol: String,
    val arbType: ArbType,
    val firstSeenSource: String,
    val firstSeenAtMs: Long,
    val currentSource: String,
    val currentAtMs: Long,
    val liquidityUsd: Double,
    val buyPressurePct: Double,
    val volume1mUsd: Double,
    val priceNow: Double,
    val priceAtFirstSeen: Double?,
    val priceChangePct: Double?,
    val confidence: Int,
    val expectedMovePct: Double,
    val notes: List<String> = emptyList()
)

/**
 * Result of arb evaluation from any arb model.
 * Provides actionable decision data.
 */
data class ArbEvaluation(
    val arbType: ArbType,
    val score: Int,              // 0-100 score
    val confidence: Int,         // 0-100 confidence
    val band: ArbDecisionBand,   // Decision band
    val expectedMovePct: Double, // Expected price move %
    val maxHoldSeconds: Int,     // Maximum hold time
    val reason: String,          // Human-readable reason
    val targetProfitPct: Double = 0.0,  // Target TP %
    val stopLossPct: Double = 0.0,      // Stop loss %
    val notes: List<String> = emptyList()
)

/**
 * Record of when/where a token was first seen.
 * Used for venue lag detection.
 */
data class SourceSeenRecord(
    val mint: String,
    val source: String,
    val seenAtMs: Long,
    val price: Double?,
    val liquidityUsd: Double,
    val buyPressurePct: Double?
)

/**
 * Active arb position tracking.
 * Separate from normal position tracking for different exit rules.
 */
data class ArbPosition(
    val mint: String,
    val symbol: String,
    val arbType: ArbType,
    val entryTimeMs: Long,
    val entryPrice: Double,
    val quantity: Double,
    val sizeSol: Double,
    val maxHoldSeconds: Int,
    val targetProfitPct: Double,
    val stopLossPct: Double,
    val band: ArbDecisionBand,
    val evaluation: ArbEvaluation,
    // Tracking
    var highWaterMark: Double = 0.0,
    var currentPrice: Double = 0.0,
    var currentPnlPct: Double = 0.0,
    var holdTimeSeconds: Int = 0,
    var exitReason: String? = null,
    var exitTimeMs: Long? = null,
    var exitPrice: Double? = null,
    var finalPnlPct: Double? = null
) {
    val isExpired: Boolean
        get() = holdTimeSeconds >= maxHoldSeconds
    
    val shouldTakeProfit: Boolean
        get() = currentPnlPct >= targetProfitPct
    
    val shouldStopLoss: Boolean
        get() = currentPnlPct <= -stopLossPct
    
    fun updatePrice(newPrice: Double) {
        currentPrice = newPrice
        if (newPrice > highWaterMark) highWaterMark = newPrice
        currentPnlPct = if (entryPrice > 0) ((newPrice - entryPrice) / entryPrice) * 100.0 else 0.0
        holdTimeSeconds = ((System.currentTimeMillis() - entryTimeMs) / 1000).toInt()
    }
}

/**
 * Arb trade outcome for learning.
 */
data class ArbOutcome(
    val mint: String,
    val symbol: String,
    val arbType: ArbType,
    val band: ArbDecisionBand,
    val entryScore: Int,
    val entryConfidence: Int,
    val holdSeconds: Int,
    val pnlPct: Double,
    val isWin: Boolean,
    val exitReason: String,
    val timestampMs: Long
)
