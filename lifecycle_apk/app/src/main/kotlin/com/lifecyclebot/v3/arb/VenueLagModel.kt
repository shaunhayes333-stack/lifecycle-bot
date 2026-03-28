package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.AICrossTalk
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * VenueLagModel - Detects cross-source visibility expansion opportunities
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * LOGIC:
 * Trigger when:
 * - Token seen on one source first
 * - Second/third source appears shortly after
 * - Visibility expanded  
 * - Price has not already run too far
 * - Liquidity still healthy
 * - Buy pressure still decent
 * 
 * FLOW:
 * Discovery on Source A → Price X
 * Short delay (5-120s)
 * Discovery on Source B → Price still ~X
 * Visibility expansion = more buyers incoming = price should move
 * 
 * This is the CLEANEST arb type - pure information asymmetry.
 */
object VenueLagModel {
    
    private const val TAG = "VenueLag"
    
    // Thresholds
    private const val MIN_LAG_MS = 5_000L          // 5 seconds minimum
    private const val MAX_LAG_MS = 120_000L        // 2 minutes maximum
    private const val MIN_LIQUIDITY = 10_000.0     // $10k floor
    private const val MIN_BUY_PRESSURE = 58.0      // 58% buy pressure
    private const val MAX_PRICE_MOVE_PCT = 18.0    // Don't chase if >18% already moved
    private const val MIN_SOURCES = 2              // Need at least 2 sources
    
    // Expected move targets
    private const val EXPECTED_MOVE_PCT = 6.0
    private const val TARGET_PROFIT_PCT = 4.0
    private const val STOP_LOSS_PCT = 3.0
    private const val MAX_HOLD_SECONDS = 120
    
    // Stats
    @Volatile private var evaluationsRun = 0
    @Volatile private var opportunitiesFound = 0
    
    /**
     * Evaluate a candidate for venue lag opportunity.
     * Uses SourceTimingRegistry to detect cross-source timing.
     */
    fun evaluate(candidate: CandidateSnapshot): ArbEvaluation? {
        evaluationsRun++
        
        try {
            val mint = candidate.mint
            val records = SourceTimingRegistry.getRecords(mint)
            
            // Need at least 2 sources
            if (records.size < MIN_SOURCES) return null
            
            val first = records.minByOrNull { it.seenAtMs } ?: return null
            val latest = records.maxByOrNull { it.seenAtMs } ?: return null
            
            // Check timing lag
            val lagMs = latest.seenAtMs - first.seenAtMs
            if (lagMs !in MIN_LAG_MS..MAX_LAG_MS) return null
            
            // Check liquidity
            if (candidate.liquidityUsd < MIN_LIQUIDITY) return null
            
            // Check buy pressure
            if (candidate.buyPressurePct < MIN_BUY_PRESSURE) return null
            
            // Check price hasn't moved too far
            val firstPrice = first.price ?: return null
            val currentPrice = candidate.extra["price"] as? Double 
                ?: candidate.extra["priceUsd"] as? Double
                ?: return null
            
            if (firstPrice <= 0 || currentPrice <= 0) return null
            
            val movePct = ((currentPrice - firstPrice) / firstPrice) * 100.0
            if (movePct > MAX_PRICE_MOVE_PCT) {
                ErrorLogger.debug(TAG, "[ARB] ${candidate.symbol} already moved ${movePct.toInt()}% - too late")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // AI DEGRADATION CHECK
            // Don't enter arb trades when AI analysis is unreliable
            // ═══════════════════════════════════════════════════════════════════
            val isAIDegraded = try { GeminiCopilot.isAIDegraded() } catch (_: Exception) { false }
            if (isAIDegraded) {
                ErrorLogger.debug(TAG, "[ARB] ${candidate.symbol} skipped - AI degraded")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // SCORE CALCULATION
            // Base 40 + bonuses for strong signals
            // ═══════════════════════════════════════════════════════════════════
            var score = 40
            
            // Buy pressure bonus
            if (candidate.buyPressurePct >= 65) score += 10
            if (candidate.buyPressurePct >= 72) score += 5
            
            // Liquidity bonus
            if (candidate.liquidityUsd >= 20_000) score += 10
            if (candidate.liquidityUsd >= 35_000) score += 5
            
            // Multiple sources bonus
            if (records.size >= 3) score += 8
            if (records.size >= 4) score += 5
            
            // Fresh lag bonus (more recent = better)
            if (lagMs <= 30_000) score += 5  // Within 30s
            
            // Volume bonus
            if (candidate.volume1mUsd >= 5_000) score += 5
            
            // CrossTalk integration
            val crossTalk = try {
                AICrossTalk.analyzeCrossTalk(mint, candidate.symbol, isOpenPosition = false)
            } catch (_: Exception) { null }
            
            if (crossTalk != null && crossTalk.entryBoost > 0) {
                score += (crossTalk.entryBoost * 0.3).toInt().coerceAtMost(10)
            }
            
            // Cap score
            score = score.coerceAtMost(85)
            
            // ═══════════════════════════════════════════════════════════════════
            // CONFIDENCE CALCULATION
            // Based on signal quality and consistency
            // ═══════════════════════════════════════════════════════════════════
            var confidence = 40
            
            // Higher buy pressure = higher confidence
            confidence += ((candidate.buyPressurePct - 58) / 2).toInt().coerceIn(0, 15)
            
            // More sources = higher confidence
            confidence += (records.size - 2) * 5
            
            // Good liquidity = higher confidence
            if (candidate.liquidityUsd >= 25_000) confidence += 5
            
            // Cap confidence
            confidence = confidence.coerceAtMost(70)
            
            // ═══════════════════════════════════════════════════════════════════
            // BAND DETERMINATION
            // ═══════════════════════════════════════════════════════════════════
            val band = when {
                score >= 60 && confidence >= ArbThresholds.ARB_STANDARD_MIN_CONF -> ArbDecisionBand.ARB_STANDARD
                score >= 50 && confidence >= ArbThresholds.ARB_MICRO_MIN_CONF -> ArbDecisionBand.ARB_MICRO
                score >= 45 -> ArbDecisionBand.ARB_WATCH
                else -> ArbDecisionBand.ARB_REJECT
            }
            
            if (band != ArbDecisionBand.ARB_REJECT && band != ArbDecisionBand.ARB_WATCH) {
                opportunitiesFound++
            }
            
            val sourceList = records.map { it.source }.distinct().joinToString(",")
            
            ErrorLogger.info(TAG, "[ARB] VENUE_LAG ${candidate.symbol} | score=$score conf=$confidence | " +
                "lag=${lagMs}ms sources=$sourceList move=${movePct.toInt()}% | band=$band")
            
            return ArbEvaluation(
                arbType = ArbType.VENUE_LAG,
                score = score,
                confidence = confidence,
                band = band,
                expectedMovePct = EXPECTED_MOVE_PCT,
                maxHoldSeconds = MAX_HOLD_SECONDS,
                reason = "Venue visibility expanded before full repricing (${records.size} sources, ${lagMs}ms lag)",
                targetProfitPct = TARGET_PROFIT_PCT,
                stopLossPct = STOP_LOSS_PCT,
                notes = listOf(
                    "sources=$sourceList",
                    "lag=${lagMs}ms",
                    "moveSoFar=${movePct.toInt()}%"
                )
            )
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "evaluate error: ${e.message}")
            return null
        }
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        return "VenueLag: $evaluationsRun evaluated | $opportunitiesFound opportunities"
    }
}
