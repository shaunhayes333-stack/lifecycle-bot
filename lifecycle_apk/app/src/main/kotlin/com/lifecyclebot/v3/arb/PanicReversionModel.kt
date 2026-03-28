package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.AICrossTalk
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.engine.MomentumPredictorAI
import com.lifecyclebot.engine.LiquidityDepthAI
import com.lifecyclebot.engine.DistributionFadeAvoider
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * PanicReversionModel - Detects mean reversion after panic flush
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * LOGIC:
 * Trigger when:
 * - Sharp flush happened (price dropped significantly)
 * - Liquidity survived (not a rug)
 * - No fatal rug evidence
 * - Sell pressure exhausted (buy pressure recovering)
 * - Bounce signal appears
 * 
 * This is a LATER-STAGE arb type that catches oversold bounces.
 * ONLY use ARB_FAST_EXIT_ONLY band - these are quick mean reversion plays.
 * 
 * RISK:
 * - Higher risk than other arb types
 * - Requires strict invalidation (fatal risk = immediate exit)
 * - Short hold time only
 */
object PanicReversionModel {
    
    private const val TAG = "PanicReversion"
    
    // Thresholds
    private const val MIN_LIQUIDITY = 12_000.0       // $12k floor (higher for safety)
    private const val MIN_FLUSH_PCT = -8.0           // Must have dropped at least 8%
    private const val MAX_FLUSH_PCT = -35.0          // Don't catch falling knives beyond 35%
    private const val MIN_BUY_PRESSURE_RECOVERY = 50.0  // Buy pressure must be recovering
    
    // Expected move targets
    private const val EXPECTED_MOVE_PCT = 4.0        // Smaller target for reversion
    private const val TARGET_PROFIT_PCT = 2.5        // Quick exit
    private const val STOP_LOSS_PCT = 4.0            // Tighter stop (panic can resume)
    private const val MAX_HOLD_SECONDS = 60          // Very short hold
    
    // Stats
    @Volatile private var evaluationsRun = 0
    @Volatile private var opportunitiesFound = 0
    
    /**
     * Evaluate a candidate for panic reversion opportunity.
     * Requires strict safety checks before entry.
     */
    fun evaluate(candidate: CandidateSnapshot): ArbEvaluation? {
        evaluationsRun++
        
        try {
            val mint = candidate.mint
            val symbol = candidate.symbol
            
            // ═══════════════════════════════════════════════════════════════════
            // SAFETY FIRST - CHECK FOR FATAL RISK
            // ═══════════════════════════════════════════════════════════════════
            val fatalRisk = try {
                candidate.extraBoolean("fatalRisk") ||
                candidate.extraBoolean("rugPull") ||
                DistributionFadeAvoider.isFatalSuppression(mint)
            } catch (_: Exception) { false }
            
            if (fatalRisk) {
                ErrorLogger.debug(TAG, "[ARB] $symbol has fatal risk - skip panic reversion")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // BASIC FILTERS
            // ═══════════════════════════════════════════════════════════════════
            
            // Check liquidity - must be substantial for reversion play
            if (candidate.liquidityUsd < MIN_LIQUIDITY) return null
            
            // ═══════════════════════════════════════════════════════════════════
            // FLUSH DETECTION
            // Need to identify a recent sharp price drop
            // ═══════════════════════════════════════════════════════════════════
            
            // Extract flush percentage from various sources
            val flushPct = candidate.extraDouble("flushPct")
                .takeIf { it != 0.0 }
                ?: candidate.extraDouble("priceChange5m")
                    .takeIf { it < 0 }
                ?: candidate.extraDouble("priceChange1h")
                    .takeIf { it < 0 }
                ?: 0.0
            
            // Must have a significant flush
            if (flushPct > MIN_FLUSH_PCT) {
                // Not enough of a flush
                return null
            }
            
            // Don't catch extreme falling knives
            if (flushPct < MAX_FLUSH_PCT) {
                ErrorLogger.debug(TAG, "[ARB] $symbol flush too extreme (${flushPct.toInt()}%) - skip")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // BOUNCE SIGNAL DETECTION
            // Look for signs that selling is exhausting and buyers returning
            // ═══════════════════════════════════════════════════════════════════
            
            // Buy pressure must be recovering (not still collapsing)
            if (candidate.buyPressurePct < MIN_BUY_PRESSURE_RECOVERY) {
                return null
            }
            
            // Check for bounce signals from extra data
            val bounceSignal = candidate.extraBoolean("bounceSignal") ||
                               candidate.extraBoolean("reversalDetected") ||
                               (candidate.buyPressurePct >= 55 && flushPct <= -10)
            
            if (!bounceSignal) {
                ErrorLogger.debug(TAG, "[ARB] $symbol no bounce signal detected")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // LIQUIDITY STABILITY CHECK
            // Ensure liquidity survived the flush (not a rug pull)
            // ═══════════════════════════════════════════════════════════════════
            
            val liquiditySignal = try {
                LiquidityDepthAI.getSignal(mint, symbol, isOpenPosition = false)
            } catch (_: Exception) { null }
            
            val isLiquidityCollapsing = liquiditySignal?.signal in listOf(
                LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE
            )
            
            val liquidityStable = !isLiquidityCollapsing &&
                                  candidate.liquidityUsd >= MIN_LIQUIDITY
            
            if (!liquidityStable) {
                ErrorLogger.debug(TAG, "[ARB] $symbol liquidity not stable after flush")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // AI DEGRADATION CHECK
            // For panic reversion, be extra cautious with degraded AI
            // ═══════════════════════════════════════════════════════════════════
            val isAIDegraded = try { GeminiCopilot.isAIDegraded() } catch (_: Exception) { false }
            if (isAIDegraded) {
                ErrorLogger.debug(TAG, "[ARB] $symbol AI degraded - skip panic reversion (requires high confidence)")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // CROSSTALK CHECK
            // Don't enter if coordinated dump still active
            // ═══════════════════════════════════════════════════════════════════
            val crossTalk = try {
                AICrossTalk.analyzeCrossTalk(mint, symbol, isOpenPosition = false)
            } catch (_: Exception) { null }
            
            if (crossTalk?.signalType == AICrossTalk.SignalType.COORDINATED_DUMP) {
                ErrorLogger.debug(TAG, "[ARB] $symbol coordinated dump still active - skip")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // MOMENTUM CHECK
            // Must not be in active distribution
            // ═══════════════════════════════════════════════════════════════════
            val momentum = try { 
                MomentumPredictorAI.getPrediction(mint) 
            } catch (_: Exception) { null }
            
            if (momentum == MomentumPredictorAI.MomentumPrediction.DISTRIBUTION) {
                ErrorLogger.debug(TAG, "[ARB] $symbol still in distribution - skip")
                return null
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // SCORE CALCULATION
            // More conservative scoring for panic reversion
            // ═══════════════════════════════════════════════════════════════════
            var score = 50  // Lower base than other arb types
            
            // Flush depth bonus (deeper flush = bigger reversion potential)
            score += ((-flushPct - 8) / 2).toInt().coerceIn(0, 10)
            
            // Liquidity survival bonus
            if (candidate.liquidityUsd >= 20_000) score += 6
            if (candidate.liquidityUsd >= 30_000) score += 4
            
            // Buy pressure recovery bonus
            if (candidate.buyPressurePct >= 55) score += 5
            if (candidate.buyPressurePct >= 60) score += 5
            
            // Volume (active trading = better reversion)
            if (candidate.volume1mUsd >= 5_000) score += 4
            
            // Momentum turning bonus
            if (momentum in listOf(
                MomentumPredictorAI.MomentumPrediction.ACCUMULATION,
                MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING
            )) {
                score += 8
            }
            
            // Cap score
            score = score.coerceAtMost(75)  // Lower cap for panic reversion
            
            // ═══════════════════════════════════════════════════════════════════
            // CONFIDENCE CALCULATION
            // Conservative for panic plays
            // ═══════════════════════════════════════════════════════════════════
            var confidence = 38  // Lower base confidence
            
            // Liquidity confidence
            if (liquidityStable) confidence += 5
            if (candidate.liquidityUsd >= 25_000) confidence += 5
            
            // Buy pressure recovery confidence
            confidence += ((candidate.buyPressurePct - 50) / 3).toInt().coerceIn(0, 8)
            
            // Bounce signal confidence
            if (bounceSignal) confidence += 5
            
            // Cap confidence
            confidence = confidence.coerceIn(0, 60)  // Lower cap
            
            // ═══════════════════════════════════════════════════════════════════
            // BAND DETERMINATION
            // Panic reversion ONLY uses ARB_FAST_EXIT_ONLY
            // This is a quick mean reversion play, not a hold
            // ═══════════════════════════════════════════════════════════════════
            val band = when {
                score >= 55 && confidence >= ArbThresholds.ARB_FAST_EXIT_MIN_CONF -> ArbDecisionBand.ARB_FAST_EXIT_ONLY
                score >= 50 -> ArbDecisionBand.ARB_WATCH
                else -> ArbDecisionBand.ARB_REJECT
            }
            
            if (band == ArbDecisionBand.ARB_FAST_EXIT_ONLY) {
                opportunitiesFound++
            }
            
            ErrorLogger.info(TAG, "[ARB] PANIC_REVERSION $symbol | score=$score conf=$confidence | " +
                "flush=${flushPct.toInt()}% bp=${candidate.buyPressurePct.toInt()}% liq=${candidate.liquidityUsd.toInt()} | band=$band")
            
            return ArbEvaluation(
                arbType = ArbType.PANIC_REVERSION,
                score = score,
                confidence = confidence,
                band = band,
                expectedMovePct = EXPECTED_MOVE_PCT,
                maxHoldSeconds = MAX_HOLD_SECONDS,
                reason = "Flush overshot while liquidity remained intact (flush=${flushPct.toInt()}%, liq=\$${candidate.liquidityUsd.toInt()})",
                targetProfitPct = TARGET_PROFIT_PCT,
                stopLossPct = STOP_LOSS_PCT,
                notes = listOf(
                    "flushPct=${flushPct.toInt()}%",
                    "buyPressure=${candidate.buyPressurePct.toInt()}%",
                    "liquidityStable=$liquidityStable",
                    "momentum=$momentum"
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
        return "PanicReversion: $evaluationsRun evaluated | $opportunitiesFound opportunities"
    }
}
