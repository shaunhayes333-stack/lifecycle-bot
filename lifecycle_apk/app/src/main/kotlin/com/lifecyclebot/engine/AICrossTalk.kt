package com.lifecyclebot.engine

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AICrossTalk - Inter-Layer Communication Hub
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Enables AI layers to communicate and amplify correlated signals:
 * 
 * CROSS-TALK PATTERNS:
 * 
 * 1. SMART MONEY PUMP DETECTION
 *    Whale accumulation + Momentum building + Growing liquidity = STRONG signal
 *    Individual: +10, +8, +8 = +26
 *    Correlated: +26 + (correlation bonus 15) = +41
 * 
 * 2. COORDINATED DUMP DETECTION
 *    Whale selling + Distribution pattern + Draining liquidity = EXIT NOW
 *    Triggers emergency exit even if individual signals are moderate
 * 
 * 3. REGIME-AWARE ADJUSTMENTS
 *    Bull market → All AIs more aggressive (thresholds lowered)
 *    Bear market → All AIs more cautious (thresholds raised)
 * 
 * 4. NARRATIVE + TIME SYNERGY
 *    Hot narrative during golden hour = amplified entry boost
 * 
 * 5. LIQUIDITY HEALTH CHECK
 *    Liquidity collapse → Trigger whale activity check
 *    Whale dump detected → Check liquidity drain rate
 */
object AICrossTalk {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class CrossTalkSignal(
        val signalType: SignalType,
        val entryBoost: Double,         // Additional entry score boost
        val exitUrgency: Double,        // Additional exit urgency
        val confidenceBoost: Double,    // Boost to AI confidence
        val sizeMultiplier: Double,     // Position size adjustment
        val reason: String,
        val participatingAIs: List<String>,
        val correlationStrength: Double, // 0-100%
    )
    
    enum class SignalType {
        SMART_MONEY_PUMP,       // Whale + Momentum + Liquidity aligned bullish
        COORDINATED_DUMP,       // Whale + Momentum + Liquidity aligned bearish
        NARRATIVE_MOMENTUM,     // Hot narrative + Strong momentum
        GOLDEN_HOUR_SETUP,      // Golden hour + Good narrative + Momentum
        REGIME_AMPLIFIED_BULL,  // Bull regime amplifying bullish signals
        REGIME_DAMPENED_BEAR,   // Bear regime dampening bullish signals
        LIQUIDITY_WHALE_ALERT,  // Liquidity collapse + Whale activity
        BEHAVIOR_PATTERN_MATCH, // Learned good/bad behavior pattern
        // V3.2 NEW CROSS-TALK PATTERNS
        VOLATILITY_SQUEEZE_BREAKOUT,  // Squeeze + momentum = explosive potential
        FLOW_DIVERGENCE,              // Order flow vs price divergence
        SMART_MONEY_DIVERGENCE,       // Whale behavior vs price divergence
        DRY_LIQUIDITY_WARNING,        // Market-wide liquidity crisis
        RUNNER_SETUP,                 // High-probability runner setup
        META_COGNITION_BOOST,         // MetaCognition detects high-confidence pattern
        META_COGNITION_WARNING,       // MetaCognition detects known losing pattern
        MODE_SWITCH_RECOMMENDED,      // V4.20: Token should switch trading mode
        NO_CORRELATION,               // Signals not correlated
    }
    
    // V4.20: Mode Switch Recommendation
    data class ModeSwitchSignal(
        val shouldSwitch: Boolean,
        val currentMode: String,
        val recommendedMode: String,
        val confidence: Double,
        val reason: String,
        val participatingAIs: List<String>,
    )
    
    // Stats tracking
    private var smartMoneyPumpsDetected = 0
    private var coordinatedDumpsDetected = 0
    private var narrativeMomentumDetected = 0
    private var modeSwitchesRecommended = 0
    private var metaCognitionBoostsDetected = 0
    private var totalCrossTalkAnalyses = 0
    
    // Learned correlation weights (adjusted based on outcomes)
    private var smartMoneyBoostWeight = 15.0
    private var coordinatedDumpUrgencyWeight = 25.0
    private var narrativeMomentumWeight = 10.0
    private var regimeAmplificationFactor = 0.3  // 30% amplification in strong regimes
    private var metaCognitionWeight = 1.2  // MetaCognition trust multiplier
    
    // Signal cache to prevent duplicate analyses (5 second TTL)
    private data class CachedCrossTalk(val signal: CrossTalkSignal, val timestamp: Long)
    private val crossTalkCache = ConcurrentHashMap<String, CachedCrossTalk>()
    private const val CACHE_TTL_MS = 5000L  // 5 seconds
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN CROSS-TALK ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze all AI signals for correlations and cross-talk opportunities.
     * Call this after individual AIs have computed their signals.
     * Results are cached for 5 seconds to prevent duplicate calculations.
     */
    fun analyzeCrossTalk(mint: String, symbol: String, isOpenPosition: Boolean): CrossTalkSignal {
        // Check cache first
        val cacheKey = "${mint}_${isOpenPosition}"
        val cached = crossTalkCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.signal  // Return cached result
        }
        
        totalCrossTalkAnalyses++
        
        // Gather signals from all AIs
        val whaleSignal = try { WhaleTrackerAI.getWhaleSignal(mint, symbol) } catch (_: Exception) { null }
        val momentum = try { MomentumPredictorAI.getPrediction(mint) } catch (_: Exception) { null }
        val liquidity = try { LiquidityDepthAI.getSignal(mint, symbol, isOpenPosition) } catch (_: Exception) { null }
        val narrative = try { NarrativeDetectorAI.detectNarrative(symbol, "") } catch (_: Exception) { null }
        val regime = try { MarketRegimeAI.getCurrentRegime() } catch (_: Exception) { null }
        val regimeConfidence = try { MarketRegimeAI.getRegimeConfidence() } catch (_: Exception) { 0.0 }
        val isGoldenHour = try { TimeOptimizationAI.isGoldenHour() } catch (_: Exception) { false }
        val isDangerZone = try { TimeOptimizationAI.isDangerZone() } catch (_: Exception) { false }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 1: SMART MONEY PUMP DETECTION
        // Whale accumulation + Momentum building + Growing liquidity
        // ─────────────────────────────────────────────────────────────────────
        val isWhaleAccumulating = whaleSignal?.recommendation in listOf("STRONG_BUY", "BUY", "LEAN_BUY")
        val isMomentumBullish = momentum in listOf(
            MomentumPredictorAI.MomentumPrediction.STRONG_PUMP,
            MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING
        )
        val isLiquidityGrowing = liquidity?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE,
            LiquidityDepthAI.SignalType.LIQUIDITY_GROWING
        )
        
        if (isWhaleAccumulating && isMomentumBullish && isLiquidityGrowing && !isOpenPosition) {
            smartMoneyPumpsDetected++
            val participatingAIs = mutableListOf("WhaleTracker", "Momentum", "Liquidity")
            
            // Calculate correlation strength based on signal intensity
            val whaleStrength = when (whaleSignal?.recommendation) {
                "STRONG_BUY" -> 100.0
                "BUY" -> 70.0
                "LEAN_BUY" -> 40.0
                else -> 0.0
            }
            val momentumStrength = when (momentum) {
                MomentumPredictorAI.MomentumPrediction.STRONG_PUMP -> 100.0
                MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING -> 60.0
                else -> 0.0
            }
            val liquidityStrength = when (liquidity?.signal) {
                LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE -> 100.0
                LiquidityDepthAI.SignalType.LIQUIDITY_GROWING -> 50.0
                else -> 0.0
            }
            
            val avgStrength = (whaleStrength + momentumStrength + liquidityStrength) / 3.0
            val correlationBonus = smartMoneyBoostWeight * (avgStrength / 100.0)
            
            // Regime amplification
            val regimeMultiplier = getRegimeMultiplier(regime, regimeConfidence, isBullish = true)
            val finalBoost = correlationBonus * regimeMultiplier
            
            ErrorLogger.info("CrossTalk", "🔗 SMART MONEY PUMP: $symbol | whale=${whaleSignal?.recommendation} momentum=$momentum liquidity=${liquidity?.signal} | +${finalBoost.toInt()} pts")
            
            return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                signalType = SignalType.SMART_MONEY_PUMP,
                entryBoost = finalBoost,
                exitUrgency = 0.0,
                confidenceBoost = finalBoost * 0.5,
                sizeMultiplier = 1.0 + (avgStrength / 500.0),  // Up to 1.2x size
                reason = "Whale+Momentum+Liquidity aligned bullish",
                participatingAIs = participatingAIs,
                correlationStrength = avgStrength,
            ))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 2: COORDINATED DUMP DETECTION
        // Whale selling + Distribution + Draining liquidity
        // ─────────────────────────────────────────────────────────────────────
        val isWhaleSelling = whaleSignal?.recommendation in listOf("STRONG_SELL", "SELL", "LEAN_SELL")
        val isMomentumBearish = momentum == MomentumPredictorAI.MomentumPrediction.DISTRIBUTION
        val isLiquidityDraining = liquidity?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
            LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
        )
        
        // Need at least 2 bearish signals for coordinated dump
        val bearishSignalCount = listOf(isWhaleSelling, isMomentumBearish, isLiquidityDraining).count { it }
        
        if (bearishSignalCount >= 2) {
            coordinatedDumpsDetected++
            val participatingAIs = mutableListOf<String>()
            if (isWhaleSelling) participatingAIs.add("WhaleTracker")
            if (isMomentumBearish) participatingAIs.add("Momentum")
            if (isLiquidityDraining) participatingAIs.add("Liquidity")
            
            val correlationStrength = (bearishSignalCount / 3.0) * 100.0
            val exitUrgency = coordinatedDumpUrgencyWeight * (correlationStrength / 100.0)
            
            // Regime dampening (bear market = even more urgent)
            val regimeMultiplier = getRegimeMultiplier(regime, regimeConfidence, isBullish = false)
            val finalUrgency = exitUrgency * regimeMultiplier
            
            ErrorLogger.warn("CrossTalk", "🔗 COORDINATED DUMP: $symbol | whale=${whaleSignal?.recommendation} momentum=$momentum liquidity=${liquidity?.signal} | +${finalUrgency.toInt()} exit urgency")
            
            return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                signalType = SignalType.COORDINATED_DUMP,
                entryBoost = -20.0,  // Strong entry penalty
                exitUrgency = finalUrgency,
                confidenceBoost = -15.0,
                sizeMultiplier = 0.5,  // Reduce size if entering anyway
                reason = "${participatingAIs.joinToString("+")} aligned bearish",
                participatingAIs = participatingAIs,
                correlationStrength = correlationStrength,
            ))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 3: NARRATIVE + MOMENTUM SYNERGY
        // Hot narrative + Strong momentum = amplified pump potential
        // ─────────────────────────────────────────────────────────────────────
        val isHotNarrative = narrative != null && 
            NarrativeDetectorAI.getEntryScoreAdjustment(symbol, "") > 5.0
        
        if (isHotNarrative && isMomentumBullish && !isOpenPosition) {
            narrativeMomentumDetected++
            val narrativeBoost = NarrativeDetectorAI.getEntryScoreAdjustment(symbol, "")
            val correlationBonus = narrativeMomentumWeight * (narrativeBoost / 15.0)
            
            ErrorLogger.info("CrossTalk", "🔗 NARRATIVE+MOMENTUM: $symbol | narrative=${narrative?.label} momentum=$momentum | +${correlationBonus.toInt()} pts")
            
            return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                signalType = SignalType.NARRATIVE_MOMENTUM,
                entryBoost = correlationBonus,
                exitUrgency = 0.0,
                confidenceBoost = correlationBonus * 0.3,
                sizeMultiplier = 1.1,
                reason = "Hot narrative + Strong momentum",
                participatingAIs = listOf("Narrative", "Momentum"),
                correlationStrength = 60.0,
            ))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 4: GOLDEN HOUR SETUP
        // Golden hour + Good signals = time-amplified opportunity
        // ─────────────────────────────────────────────────────────────────────
        if (isGoldenHour && (isMomentumBullish || isHotNarrative) && !isOpenPosition) {
            val timeBoost = 8.0
            
            ErrorLogger.debug("CrossTalk", "🔗 GOLDEN HOUR SETUP: $symbol | +${timeBoost.toInt()} pts")
            
            return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                signalType = SignalType.GOLDEN_HOUR_SETUP,
                entryBoost = timeBoost,
                exitUrgency = 0.0,
                confidenceBoost = 5.0,
                sizeMultiplier = 1.05,
                reason = "Golden hour + ${if (isMomentumBullish) "Momentum" else "Narrative"}",
                participatingAIs = listOf("Time", if (isMomentumBullish) "Momentum" else "Narrative"),
                correlationStrength = 50.0,
            ))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 5: LIQUIDITY-WHALE ALERT
        // Liquidity collapse triggers whale check
        // ─────────────────────────────────────────────────────────────────────
        if (liquidity?.signal == LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE && isOpenPosition) {
            // Check if whales are involved
            if (isWhaleSelling) {
                ErrorLogger.warn("CrossTalk", "🔗 LIQUIDITY-WHALE ALERT: $symbol | Collapse + Whale selling = EMERGENCY")
                
                return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                    signalType = SignalType.LIQUIDITY_WHALE_ALERT,
                    entryBoost = -30.0,
                    exitUrgency = 40.0,  // Very high urgency
                    confidenceBoost = -20.0,
                    sizeMultiplier = 0.3,
                    reason = "Liquidity collapse confirmed by whale selling",
                    participatingAIs = listOf("Liquidity", "WhaleTracker"),
                    correlationStrength = 90.0,
                ))
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 6: REGIME AMPLIFICATION (applies to all trades)
        // ─────────────────────────────────────────────────────────────────────
        if (regime != null && regimeConfidence >= 50.0) {
            val isBullRegime = regime in listOf(
                MarketRegimeAI.Regime.STRONG_BULL,
                MarketRegimeAI.Regime.BULL
            )
            val isBearRegime = regime in listOf(
                MarketRegimeAI.Regime.STRONG_BEAR,
                MarketRegimeAI.Regime.BEAR
            )
            
            if (isBullRegime && !isOpenPosition) {
                val amplification = regimeAmplificationFactor * (regimeConfidence / 100.0)
                return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                    signalType = SignalType.REGIME_AMPLIFIED_BULL,
                    entryBoost = 5.0 * amplification,
                    exitUrgency = 0.0,
                    confidenceBoost = 3.0 * amplification,
                    sizeMultiplier = 1.0 + (0.1 * amplification),
                    reason = "Bull regime (${regimeConfidence.toInt()}% confidence)",
                    participatingAIs = listOf("MarketRegime"),
                    correlationStrength = regimeConfidence,
                ))
            }
            
            if (isBearRegime && !isOpenPosition) {
                val dampening = regimeAmplificationFactor * (regimeConfidence / 100.0)
                return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                    signalType = SignalType.REGIME_DAMPENED_BEAR,
                    entryBoost = -5.0 * dampening,
                    exitUrgency = 5.0 * dampening,
                    confidenceBoost = -3.0 * dampening,
                    sizeMultiplier = 1.0 - (0.1 * dampening),
                    reason = "Bear regime (${regimeConfidence.toInt()}% confidence)",
                    participatingAIs = listOf("MarketRegime"),
                    correlationStrength = regimeConfidence,
                ))
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // PATTERN 6: BEHAVIOR LEARNING CROSS-TALK
        // Check if BehaviorLearning has strong insights for this pattern
        // 
        // BOOTSTRAP MODE AWARENESS: During bootstrap (< 50 trades), we require
        // higher confidence and sample size before penalizing. The bot needs
        // to EXPLORE, not get stuck on small-sample memories.
        // ─────────────────────────────────────────────────────────────────────
        if (!isOpenPosition) {
            try {
                val behaviorHealth = BehaviorLearning.getHealthStatus()
                val totalPatterns = behaviorHealth.goodCount + behaviorHealth.badCount
                val isBootstrap = totalPatterns < 50
                
                // Minimum patterns before using behavior learning
                // Bootstrap: need more data before trusting patterns
                val minPatterns = if (isBootstrap) 30 else 20
                
                if (totalPatterns >= minPatterns) {
                    val insights = BehaviorLearning.getInsights()
                    
                    // Check if we have strong pattern matches
                    val topGood = insights.topGoodPatterns.firstOrNull()
                    val topBad = insights.topBadPatterns.firstOrNull()
                    
                    // Minimum sample requirement for bad patterns
                    // Bootstrap: require 5+ samples, Mature: require 3+ samples
                    val minBadSamples = if (isBootstrap) 5 else 3
                    
                    // Strong good pattern with 70%+ win rate
                    if (topGood != null && topGood.winRate >= 70.0 && topGood.confidence >= 0.7) {
                        val boost = (topGood.winRate - 50) * 0.4  // Up to +20 for 100% win rate
                        return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                            signalType = SignalType.BEHAVIOR_PATTERN_MATCH,
                            entryBoost = boost,
                            exitUrgency = 0.0,
                            confidenceBoost = boost * 0.5,
                            sizeMultiplier = 1.0 + (topGood.winRate - 50) / 200.0,
                            reason = "Learned pattern: ${topGood.signature.take(30)} (${topGood.winRate.toInt()}% win)",
                            participatingAIs = listOf("BehaviorLearning"),
                            correlationStrength = topGood.confidence * 100,
                        ))
                    }
                    
                    // Strong bad pattern with 70%+ loss rate (warn but don't block in crosstalk)
                    // FIX: Check sample size to avoid small-sample poisoning
                    if (topBad != null && (100 - topBad.winRate) >= 70.0 && topBad.confidence >= 0.7) {
                        // Check if pattern has enough samples
                        val hasEnoughSamples = topBad.occurrences >= minBadSamples
                        
                        if (hasEnoughSamples) {
                            val lossRate = 100 - topBad.winRate
                            // Reduce penalty in bootstrap mode
                            val penaltyMult = if (isBootstrap) 0.5 else 1.0
                            val penalty = (lossRate - 50) * 0.3 * penaltyMult
                            ErrorLogger.warn("CrossTalk", "⚠️ BEHAVIOR WARNING: ${symbol} matches bad pattern (${lossRate.toInt()}% loss, n=${topBad.occurrences})")
                            return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                                signalType = SignalType.BEHAVIOR_PATTERN_MATCH,
                                entryBoost = -penalty,
                                exitUrgency = penalty * 0.5,
                                confidenceBoost = -penalty * 0.5,
                                sizeMultiplier = 1.0 - (lossRate - 50) / 300.0,
                                reason = "Bad pattern: ${topBad.signature.take(30)} (${lossRate.toInt()}% loss, n=${topBad.occurrences})",
                                participatingAIs = listOf("BehaviorLearning"),
                                correlationStrength = topBad.confidence * 100,
                            ))
                        } else {
                            // Small sample - just log warning, don't penalize
                            ErrorLogger.debug("CrossTalk", "Low-sample bad pattern for ${symbol}: ${topBad.occurrences} < ${minBadSamples} required")
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "BehaviorLearning check error: ${e.message}")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // V3.2 PATTERN 7: VOLATILITY SQUEEZE BREAKOUT
        // Squeeze + momentum building = explosive potential
        // ─────────────────────────────────────────────────────────────────────
        if (!isOpenPosition) {
            try {
                val volRegime = com.lifecyclebot.v3.scoring.VolatilityRegimeAI.getRegime(mint)
                val inSqueeze = com.lifecyclebot.v3.scoring.VolatilityRegimeAI.isInSqueeze(mint)
                
                if (inSqueeze && isMomentumBullish) {
                    ErrorLogger.info("CrossTalk", "🔥 SQUEEZE+MOMENTUM: $symbol | explosive potential")
                    return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                        signalType = SignalType.VOLATILITY_SQUEEZE_BREAKOUT,
                        entryBoost = 12.0,
                        exitUrgency = 0.0,
                        confidenceBoost = 10.0,
                        sizeMultiplier = 1.15,
                        reason = "Volatility squeeze + momentum building",
                        participatingAIs = listOf("VolatilityRegime", "Momentum"),
                        correlationStrength = 75.0,
                    ))
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "VolatilityRegime check error: ${e.message}")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // V3.2 PATTERN 8: FLOW + SMART MONEY DIVERGENCE
        // Order flow divergence confirmed by whale behavior
        // ─────────────────────────────────────────────────────────────────────
        if (!isOpenPosition) {
            try {
                val flowState = com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.getFlowState(mint)
                val smartMoneyDiv = com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.getDivergence(mint)
                
                // Bullish flow + bullish divergence = strong buy
                val flowBullish = flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.STRONG_BUY_PRESSURE ||
                                  flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.BUY_PRESSURE
                val divBullish = smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.BULLISH ||
                                 smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.STRONG_BULLISH
                
                if (flowBullish && divBullish) {
                    ErrorLogger.info("CrossTalk", "🐋 FLOW+DIVERGENCE: $symbol | whales accumulating under cover")
                    return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                        signalType = SignalType.SMART_MONEY_DIVERGENCE,
                        entryBoost = 10.0,
                        exitUrgency = 0.0,
                        confidenceBoost = 8.0,
                        sizeMultiplier = 1.1,
                        reason = "Order flow + smart money both bullish",
                        participatingAIs = listOf("OrderFlow", "SmartMoney"),
                        correlationStrength = 70.0,
                    ))
                }
                
                // Bearish flow + bearish divergence = strong warning
                val flowBearish = flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.STRONG_SELL_PRESSURE ||
                                  flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.SELL_PRESSURE
                val divBearish = smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.BEARISH ||
                                 smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.STRONG_BEARISH
                
                if (flowBearish && divBearish) {
                    ErrorLogger.warn("CrossTalk", "⚠️ FLOW+DIVERGENCE WARNING: $symbol | distribution in progress")
                    return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                        signalType = SignalType.FLOW_DIVERGENCE,
                        entryBoost = -15.0,
                        exitUrgency = 35.0,
                        confidenceBoost = -10.0,
                        sizeMultiplier = 0.6,
                        reason = "Order flow + smart money both bearish",
                        participatingAIs = listOf("OrderFlow", "SmartMoney"),
                        correlationStrength = 80.0,
                    ))
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "Flow/SmartMoney check error: ${e.message}")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // V3.2 PATTERN 9: DRY LIQUIDITY WARNING
        // Market-wide liquidity crisis
        // ─────────────────────────────────────────────────────────────────────
        try {
            val liqCycle = com.lifecyclebot.v3.scoring.LiquidityCycleAI.getCurrentState()
            
            if (liqCycle.riskLevel >= 4 || liqCycle.phase == com.lifecyclebot.v3.scoring.LiquidityCycleAI.LiquidityPhase.DRY) {
                ErrorLogger.warn("CrossTalk", "⚠️ DRY LIQUIDITY: Market-wide risk=${liqCycle.riskLevel}")
                return cacheAndReturn(cacheKey, now, CrossTalkSignal(
                    signalType = SignalType.DRY_LIQUIDITY_WARNING,
                    entryBoost = -12.0,
                    exitUrgency = if (isOpenPosition) 25.0 else 0.0,
                    confidenceBoost = -8.0,
                    sizeMultiplier = 0.5,
                    reason = "Market-wide liquidity crisis (risk=${liqCycle.riskLevel})",
                    participatingAIs = listOf("LiquidityCycle"),
                    correlationStrength = liqCycle.healthScore,
                ))
            }
        } catch (e: Exception) {
            ErrorLogger.debug("CrossTalk", "LiquidityCycle check error: ${e.message}")
        }
        
        // No significant correlation detected
        return cacheAndReturn(cacheKey, now, CrossTalkSignal(
            signalType = SignalType.NO_CORRELATION,
            entryBoost = 0.0,
            exitUrgency = 0.0,
            confidenceBoost = 0.0,
            sizeMultiplier = 1.0,
            reason = "No cross-talk pattern detected",
            participatingAIs = emptyList(),
            correlationStrength = 0.0,
        ))
    }
    
    /**
     * Cache helper function to store and return signal.
     */
    private fun cacheAndReturn(cacheKey: String, timestamp: Long, signal: CrossTalkSignal): CrossTalkSignal {
        crossTalkCache[cacheKey] = CachedCrossTalk(signal, timestamp)
        return signal
    }
    
    /**
     * Get regime multiplier for amplifying/dampening signals.
     */
    private fun getRegimeMultiplier(
        regime: MarketRegimeAI.Regime?, 
        confidence: Double,
        isBullish: Boolean
    ): Double {
        if (regime == null || confidence < 40.0) return 1.0
        
        val baseMultiplier = when (regime) {
            MarketRegimeAI.Regime.STRONG_BULL -> if (isBullish) 1.3 else 0.7
            MarketRegimeAI.Regime.BULL -> if (isBullish) 1.15 else 0.85
            MarketRegimeAI.Regime.NEUTRAL -> 1.0
            MarketRegimeAI.Regime.CRAB -> 0.9
            MarketRegimeAI.Regime.BEAR -> if (isBullish) 0.8 else 1.2
            MarketRegimeAI.Regime.STRONG_BEAR -> if (isBullish) 0.6 else 1.4
            MarketRegimeAI.Regime.HIGH_VOLATILITY -> 0.85
        }
        
        // Scale by confidence
        val confidenceScale = confidence / 100.0
        return 1.0 + ((baseMultiplier - 1.0) * confidenceScale)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUICK ACCESS METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get entry score adjustment from cross-talk analysis.
     */
    fun getEntryBoost(mint: String, symbol: String): Double {
        val signal = analyzeCrossTalk(mint, symbol, isOpenPosition = false)
        return signal.entryBoost
    }
    
    /**
     * Get exit urgency from cross-talk analysis.
     */
    fun getExitUrgency(mint: String, symbol: String): Double {
        val signal = analyzeCrossTalk(mint, symbol, isOpenPosition = true)
        return signal.exitUrgency
    }
    
    /**
     * Get confidence boost from cross-talk analysis.
     */
    fun getConfidenceBoost(mint: String, symbol: String, isOpenPosition: Boolean): Double {
        val signal = analyzeCrossTalk(mint, symbol, isOpenPosition)
        return signal.confidenceBoost
    }
    
    /**
     * Get size multiplier from cross-talk analysis.
     */
    fun getSizeMultiplier(mint: String, symbol: String): Double {
        val signal = analyzeCrossTalk(mint, symbol, isOpenPosition = false)
        return signal.sizeMultiplier
    }
    
    /**
     * Check if coordinated dump is detected (for emergency exits).
     */
    fun isCoordinatedDump(mint: String, symbol: String): Boolean {
        val signal = analyzeCrossTalk(mint, symbol, isOpenPosition = true)
        return signal.signalType == SignalType.COORDINATED_DUMP ||
               signal.signalType == SignalType.LIQUIDITY_WHALE_ALERT
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.20: CROSS-TALK MODE SWITCH EVALUATION
    // 
    // Analyzes ALL AI signals to recommend the best trading mode for a token.
    // Called when token characteristics change (mcap, liquidity, momentum, etc.)
    // 
    // Mode Categories:
    //   SHITCOIN → mcap <$500K, age <6h, high volatility
    //   MOONSHOT → strong momentum, whale accumulation, growing liquidity
    //   BLUE_CHIP → mcap >$1M, liquidity >$200K, stable
    //   DIP_HUNTER → underwater but recovery signals
    //   MOMENTUM_SWING → stable trend with good momentum
    //   LONG_HOLD → mature fundamentals, conviction hold
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate if a token should switch trading modes based on all AI signals.
     * Call this periodically for held positions or when token characteristics change.
     */
    fun evaluateModeSwitchCrossTalk(
        mint: String,
        symbol: String,
        currentMode: String,
        mcap: Double,
        liquidity: Double,
        ageMs: Long,
        currentPnlPct: Double,
        holdTimeMs: Long,
    ): ModeSwitchSignal {
        val participatingAIs = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var bestMode = currentMode
        var highestConfidence = 0.0
        
        val ageMinutes = ageMs / (60 * 1000)
        val holdMinutes = holdTimeMs / (60 * 1000)
        
        // ─────────────────────────────────────────────────────────────────
        // Gather signals from all AI layers
        // ─────────────────────────────────────────────────────────────────
        val whaleSignal = try { WhaleTrackerAI.getWhaleSignal(mint, symbol) } catch (_: Exception) { null }
        val momentum = try { MomentumPredictorAI.getPrediction(mint) } catch (_: Exception) { null }
        val liquidity_signal = try { LiquidityDepthAI.getSignal(mint, symbol, true) } catch (_: Exception) { null }
        val regime = try { MarketRegimeAI.getCurrentRegime() } catch (_: Exception) { null }
        
        val isWhaleAccumulating = whaleSignal?.recommendation in listOf("STRONG_BUY", "BUY", "LEAN_BUY")
        val isWhaleSelling = whaleSignal?.recommendation in listOf("STRONG_SELL", "SELL", "LEAN_SELL")
        val isMomentumBullish = momentum in listOf(
            MomentumPredictorAI.MomentumPrediction.STRONG_PUMP,
            MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING
        )
        val isLiquidityGrowing = liquidity_signal?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE,
            LiquidityDepthAI.SignalType.LIQUIDITY_GROWING
        )
        val isLiquidityDraining = liquidity_signal?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
            LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
        )
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 1: ShitCoin → Better Model (graduated)
        // Token has matured out of shitcoin range
        // ─────────────────────────────────────────────────────────────────
        if (currentMode in listOf("SHITCOIN", "MICRO_CAP", "PUMP_SNIPER")) {
            // Graduated to mid-cap territory
            if (mcap > 1_000_000 && liquidity > 100_000) {
                participatingAIs.add("MarketCap")
                participatingAIs.add("Liquidity")
                
                // Determine best mode based on momentum
                if (isWhaleAccumulating && isMomentumBullish) {
                    bestMode = "MOONSHOT"
                    highestConfidence = 85.0
                    reasons.add("Graduated to $${(mcap/1000).toInt()}k mcap with whale accumulation")
                } else if (isLiquidityGrowing && isMomentumBullish) {
                    bestMode = "MOMENTUM_SWING"
                    highestConfidence = 78.0
                    reasons.add("Graduated with growing liquidity + momentum")
                } else {
                    bestMode = "LONG_HOLD"
                    highestConfidence = 72.0
                    reasons.add("Graduated to established mcap ($${(mcap/1000).toInt()}k)")
                }
            }
            // Strong gains - ride the wave
            else if (currentPnlPct > 100.0 && isMomentumBullish) {
                participatingAIs.add("Momentum")
                bestMode = "MOONSHOT"
                highestConfidence = 80.0
                reasons.add("100x potential - switch to trailing mode")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 2: Any Mode → BLUE_CHIP (token became established)
        // ─────────────────────────────────────────────────────────────────
        if (currentMode !in listOf("BLUE_CHIP", "LONG_HOLD") && bestMode == currentMode) {
            if (mcap > 5_000_000 && liquidity > 500_000 && ageMinutes > 60 * 24) {
                participatingAIs.add("MarketCap")
                participatingAIs.add("Liquidity")
                participatingAIs.add("Age")
                bestMode = "BLUE_CHIP"
                highestConfidence = 88.0
                reasons.add("Token established: mcap=$${(mcap/1_000_000).toInt()}M, liq=$${(liquidity/1000).toInt()}k")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 3: Profit → MOONSHOT (let winners run)
        // Token showing moonshot characteristics
        // ─────────────────────────────────────────────────────────────────
        if (currentMode !in listOf("MOONSHOT") && bestMode == currentMode) {
            val bigGains = currentPnlPct > 80.0
            val stillBullish = isMomentumBullish && isWhaleAccumulating
            val liquidityHealthy = liquidity > 30_000 && !isLiquidityDraining
            
            if (bigGains && stillBullish && liquidityHealthy) {
                participatingAIs.add("WhaleTracker")
                participatingAIs.add("Momentum")
                participatingAIs.add("Liquidity")
                bestMode = "MOONSHOT"
                highestConfidence = 82.0
                reasons.add("Winner running: +${currentPnlPct.toInt()}% with whale support")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 4: Underwater → DIP_HUNTER (recovery signals)
        // ─────────────────────────────────────────────────────────────────
        if (currentPnlPct < -15.0 && currentPnlPct > -40.0 && bestMode == currentMode) {
            val recoverySignals = isMomentumBullish || isWhaleAccumulating
            val liquidityStable = !isLiquidityDraining
            
            if (recoverySignals && liquidityStable) {
                participatingAIs.add(if (isMomentumBullish) "Momentum" else "WhaleTracker")
                bestMode = "DIP_HUNTER"
                highestConfidence = 70.0
                reasons.add("Recovery signals while underwater (${currentPnlPct.toInt()}%)")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 5: Declining Liquidity → Defensive Mode
        // ─────────────────────────────────────────────────────────────────
        if (isLiquidityDraining && isWhaleSelling && bestMode == currentMode) {
            // Don't switch mode but signal urgency
            participatingAIs.add("Liquidity")
            participatingAIs.add("WhaleTracker")
            bestMode = "DEFENSIVE"
            highestConfidence = 90.0
            reasons.add("Liquidity draining + whale selling - go defensive")
        }
        
        // ─────────────────────────────────────────────────────────────────
        // CHECK 6: Regime-based Mode Adjustment
        // ─────────────────────────────────────────────────────────────────
        if (bestMode == currentMode) {
            val isBullRegime = regime in listOf(
                MarketRegimeAI.Regime.STRONG_BULL,
                MarketRegimeAI.Regime.BULL
            )
            val isBearRegime = regime in listOf(
                MarketRegimeAI.Regime.STRONG_BEAR,
                MarketRegimeAI.Regime.BEAR
            )
            
            // In strong bull, be more aggressive
            if (isBullRegime && currentMode == "STANDARD" && isMomentumBullish) {
                participatingAIs.add("MarketRegime")
                participatingAIs.add("Momentum")
                bestMode = "MOMENTUM_SWING"
                highestConfidence = 72.0
                reasons.add("Bull regime + momentum - switch to aggressive")
            }
            
            // In bear, be more defensive
            if (isBearRegime && currentMode != "DEFENSIVE" && isLiquidityDraining) {
                participatingAIs.add("MarketRegime")
                participatingAIs.add("Liquidity")
                bestMode = "DEFENSIVE"
                highestConfidence = 75.0
                reasons.add("Bear regime + weak liquidity - go defensive")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // BUILD RESULT
        // ─────────────────────────────────────────────────────────────────
        val shouldSwitch = bestMode != currentMode && highestConfidence >= 65.0
        
        if (shouldSwitch) {
            modeSwitchesRecommended++
            ErrorLogger.info("CrossTalk", "🔄 MODE SWITCH: $symbol | $currentMode → $bestMode | conf=${highestConfidence.toInt()}% | ${reasons.joinToString("; ")}")
        }
        
        return ModeSwitchSignal(
            shouldSwitch = shouldSwitch,
            currentMode = currentMode,
            recommendedMode = bestMode,
            confidence = highestConfidence,
            reason = if (reasons.isNotEmpty()) reasons.joinToString("; ") else "No switch needed",
            participatingAIs = participatingAIs.distinct(),
        )
    }
    
    /**
     * Quick check if mode switch should be evaluated (call frequently).
     * Returns true if significant characteristic changes detected.
     */
    fun shouldCheckModeSwitch(
        mint: String,
        mcapChange: Double,      // % change in mcap since entry
        liquidityChange: Double, // % change in liquidity
        pnlPct: Double,
    ): Boolean {
        // Check if characteristics changed significantly
        return when {
            // Major mcap change (graduated or crashed)
            kotlin.math.abs(mcapChange) > 100.0 -> true
            // Major liquidity shift
            kotlin.math.abs(liquidityChange) > 50.0 -> true
            // Major P&L swing
            pnlPct > 80.0 || pnlPct < -20.0 -> true
            // Otherwise check periodically via caller
            else -> false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record outcome for learning correlation weights.
     */
    fun recordOutcome(signalType: SignalType, pnlPct: Double, wasProfit: Boolean) {
        when (signalType) {
            SignalType.SMART_MONEY_PUMP -> {
                // If smart money pump signals are profitable, increase weight
                if (wasProfit && pnlPct > 10.0) {
                    smartMoneyBoostWeight = (smartMoneyBoostWeight + 1.0).coerceAtMost(25.0)
                } else if (!wasProfit && pnlPct < -10.0) {
                    smartMoneyBoostWeight = (smartMoneyBoostWeight - 1.0).coerceAtLeast(5.0)
                }
            }
            SignalType.COORDINATED_DUMP -> {
                // If coordinated dump correctly predicted losses, increase urgency
                if (!wasProfit) {
                    coordinatedDumpUrgencyWeight = (coordinatedDumpUrgencyWeight + 2.0).coerceAtMost(40.0)
                }
            }
            SignalType.NARRATIVE_MOMENTUM -> {
                if (wasProfit && pnlPct > 5.0) {
                    narrativeMomentumWeight = (narrativeMomentumWeight + 0.5).coerceAtMost(20.0)
                } else if (!wasProfit) {
                    narrativeMomentumWeight = (narrativeMomentumWeight - 0.5).coerceAtLeast(3.0)
                }
            }
            else -> { /* No learning for other signals yet */ }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("smartMoneyBoostWeight", smartMoneyBoostWeight)
            put("coordinatedDumpUrgencyWeight", coordinatedDumpUrgencyWeight)
            put("narrativeMomentumWeight", narrativeMomentumWeight)
            put("regimeAmplificationFactor", regimeAmplificationFactor)
            put("smartMoneyPumpsDetected", smartMoneyPumpsDetected)
            put("coordinatedDumpsDetected", coordinatedDumpsDetected)
            put("narrativeMomentumDetected", narrativeMomentumDetected)
            put("modeSwitchesRecommended", modeSwitchesRecommended)
            put("totalCrossTalkAnalyses", totalCrossTalkAnalyses)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            smartMoneyBoostWeight = json.optDouble("smartMoneyBoostWeight", 15.0)
            coordinatedDumpUrgencyWeight = json.optDouble("coordinatedDumpUrgencyWeight", 25.0)
            narrativeMomentumWeight = json.optDouble("narrativeMomentumWeight", 10.0)
            regimeAmplificationFactor = json.optDouble("regimeAmplificationFactor", 0.3)
            smartMoneyPumpsDetected = json.optInt("smartMoneyPumpsDetected", 0)
            coordinatedDumpsDetected = json.optInt("coordinatedDumpsDetected", 0)
            narrativeMomentumDetected = json.optInt("narrativeMomentumDetected", 0)
            modeSwitchesRecommended = json.optInt("modeSwitchesRecommended", 0)
            totalCrossTalkAnalyses = json.optInt("totalCrossTalkAnalyses", 0)
        } catch (e: Exception) {
            ErrorLogger.error("CrossTalk", "Failed to load: ${e.message}")
        }
    }
    
    fun getStats(): String {
        return "CrossTalk: $totalCrossTalkAnalyses analyses | " +
               "pumps=$smartMoneyPumpsDetected dumps=$coordinatedDumpsDetected narrative=$narrativeMomentumDetected switches=$modeSwitchesRecommended | " +
               "weights: pump=${smartMoneyBoostWeight.toInt()} dump=${coordinatedDumpUrgencyWeight.toInt()} narr=${narrativeMomentumWeight.toInt()}"
    }
    
    fun cleanup() {
        // Clean up expired cache entries
        val now = System.currentTimeMillis()
        crossTalkCache.entries.removeIf { (_, cached) ->
            (now - cached.timestamp) > CACHE_TTL_MS * 2
        }
    }
}
