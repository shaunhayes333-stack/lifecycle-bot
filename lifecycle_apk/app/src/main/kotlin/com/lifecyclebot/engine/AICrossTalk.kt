package com.lifecyclebot.engine

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * AICrossTalk - Inter-Layer Communication Hub
 *
 * Hardened version:
 * - evaluates all cross-talk candidates, then returns the strongest one
 * - preserves API compatibility
 * - safer persistence
 * - safer logger usage
 */
object AICrossTalk {

    data class CrossTalkSignal(
        val signalType: SignalType,
        val entryBoost: Double,
        val exitUrgency: Double,
        val confidenceBoost: Double,
        val sizeMultiplier: Double,
        val reason: String,
        val participatingAIs: List<String>,
        val correlationStrength: Double,
    )

    enum class SignalType {
        SMART_MONEY_PUMP,
        COORDINATED_DUMP,
        NARRATIVE_MOMENTUM,
        GOLDEN_HOUR_SETUP,
        REGIME_AMPLIFIED_BULL,
        REGIME_DAMPENED_BEAR,
        LIQUIDITY_WHALE_ALERT,
        BEHAVIOR_PATTERN_MATCH,
        VOLATILITY_SQUEEZE_BREAKOUT,
        FLOW_DIVERGENCE,
        SMART_MONEY_DIVERGENCE,
        DRY_LIQUIDITY_WARNING,
        RUNNER_SETUP,
        META_COGNITION_BOOST,
        META_COGNITION_WARNING,
        MODE_SWITCH_RECOMMENDED,
        NO_CORRELATION,
    }

    data class ModeSwitchSignal(
        val shouldSwitch: Boolean,
        val currentMode: String,
        val recommendedMode: String,
        val confidence: Double,
        val reason: String,
        val participatingAIs: List<String>,
    )

    private data class CachedCrossTalk(val signal: CrossTalkSignal, val timestamp: Long)

    private data class SignalCandidate(
        val signal: CrossTalkSignal,
        val priority: Int,
        val score: Double,
    )

    private var smartMoneyPumpsDetected = 0
    private var coordinatedDumpsDetected = 0
    private var narrativeMomentumDetected = 0
    private var modeSwitchesRecommended = 0
    private var metaCognitionBoostsDetected = 0
    private var totalCrossTalkAnalyses = 0

    private var smartMoneyBoostWeight = 15.0
    private var coordinatedDumpUrgencyWeight = 25.0
    private var narrativeMomentumWeight = 10.0
    private var regimeAmplificationFactor = 0.3
    private var metaCognitionWeight = 1.2

    private val crossTalkCache = ConcurrentHashMap<String, CachedCrossTalk>()
    private const val CACHE_TTL_MS = 5000L

    fun analyzeCrossTalk(mint: String, symbol: String, isOpenPosition: Boolean): CrossTalkSignal {
        val cacheKey = "${mint}_${isOpenPosition}"
        val now = System.currentTimeMillis()
        val cached = crossTalkCache[cacheKey]

        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.signal
        }

        totalCrossTalkAnalyses++

        // V4 Meta: Feed narrative activity (non-blocking, read-only)
        try { com.lifecyclebot.v4.meta.NarrativeFlowAI.recordActivity(symbol) } catch (_: Exception) {}

        val whaleSignal = try { WhaleTrackerAI.getWhaleSignal(mint, symbol) } catch (_: Exception) { null }
        val momentum = try { MomentumPredictorAI.getPrediction(mint) } catch (_: Exception) { null }
        val liquidity = try { LiquidityDepthAI.getSignal(mint, symbol, isOpenPosition) } catch (_: Exception) { null }
        val narrative = try { NarrativeDetectorAI.detectNarrative(symbol, "") } catch (_: Exception) { null }
        val narrativeBoost = try { NarrativeDetectorAI.getEntryScoreAdjustment(symbol, "") } catch (_: Exception) { 0.0 }
        val regime = try { MarketRegimeAI.getCurrentRegime() } catch (_: Exception) { null }
        val regimeConfidence = try { MarketRegimeAI.getRegimeConfidence() } catch (_: Exception) { 0.0 }
        val isGoldenHour = try { TimeOptimizationAI.isGoldenHour() } catch (_: Exception) { false }

        val candidates = mutableListOf<SignalCandidate>()

        val isWhaleAccumulating = whaleSignal?.recommendation in listOf("STRONG_BUY", "BUY", "LEAN_BUY")
        val isWhaleSelling = whaleSignal?.recommendation in listOf("STRONG_SELL", "SELL", "LEAN_SELL")

        val isMomentumBullish = momentum in listOf(
            MomentumPredictorAI.MomentumPrediction.STRONG_PUMP,
            MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING
        )
        val isMomentumBearish = momentum == MomentumPredictorAI.MomentumPrediction.DISTRIBUTION

        val isLiquidityGrowing = liquidity?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE,
            LiquidityDepthAI.SignalType.LIQUIDITY_GROWING
        )
        val isLiquidityDraining = liquidity?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
            LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
        )

        val isHotNarrative = narrative != null && narrativeBoost > 5.0

        // 1) SMART MONEY PUMP
        if (isWhaleAccumulating && isMomentumBullish && isLiquidityGrowing && !isOpenPosition) {
            smartMoneyPumpsDetected++

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
            val bonus = smartMoneyBoostWeight * (avgStrength / 100.0)
            val regimeMult = getRegimeMultiplier(regime, regimeConfidence, isBullish = true)
            val finalBoost = bonus * regimeMult

            candidates += candidate(
                CrossTalkSignal(
                    signalType = SignalType.SMART_MONEY_PUMP,
                    entryBoost = finalBoost,
                    exitUrgency = 0.0,
                    confidenceBoost = finalBoost * 0.5,
                    sizeMultiplier = (1.0 + (avgStrength / 500.0)).coerceIn(0.5, 1.2),
                    reason = "Whale+Momentum+Liquidity aligned bullish",
                    participatingAIs = listOf("WhaleTracker", "Momentum", "Liquidity"),
                    correlationStrength = avgStrength,
                ),
                priority = 95
            )
        }

        // 2) COORDINATED DUMP
        val bearishSignalCount = listOf(isWhaleSelling, isMomentumBearish, isLiquidityDraining).count { it }
        if (bearishSignalCount >= 2) {
            coordinatedDumpsDetected++

            val participants = buildList {
                if (isWhaleSelling) add("WhaleTracker")
                if (isMomentumBearish) add("Momentum")
                if (isLiquidityDraining) add("Liquidity")
            }

            val correlationStrength = (bearishSignalCount / 3.0) * 100.0
            val urgency = coordinatedDumpUrgencyWeight * (correlationStrength / 100.0)
            val regimeMult = getRegimeMultiplier(regime, regimeConfidence, isBullish = false)
            val finalUrgency = urgency * regimeMult

            candidates += candidate(
                CrossTalkSignal(
                    signalType = SignalType.COORDINATED_DUMP,
                    entryBoost = -20.0,
                    exitUrgency = finalUrgency,
                    confidenceBoost = -15.0,
                    sizeMultiplier = 0.5,
                    reason = "${participants.joinToString("+")} aligned bearish",
                    participatingAIs = participants,
                    correlationStrength = correlationStrength,
                ),
                priority = if (isOpenPosition) 100 else 90
            )
        }

        // 3) NARRATIVE + MOMENTUM
        if (isHotNarrative && isMomentumBullish && !isOpenPosition) {
            narrativeMomentumDetected++

            val correlationBonus = narrativeMomentumWeight * (narrativeBoost / 15.0)

            candidates += candidate(
                CrossTalkSignal(
                    signalType = SignalType.NARRATIVE_MOMENTUM,
                    entryBoost = correlationBonus,
                    exitUrgency = 0.0,
                    confidenceBoost = correlationBonus * 0.3,
                    sizeMultiplier = 1.1,
                    reason = "Hot narrative + Strong momentum",
                    participatingAIs = listOf("Narrative", "Momentum"),
                    correlationStrength = 60.0,
                ),
                priority = 70
            )
        }

        // 4) GOLDEN HOUR
        if (isGoldenHour && (isMomentumBullish || isHotNarrative) && !isOpenPosition) {
            candidates += candidate(
                CrossTalkSignal(
                    signalType = SignalType.GOLDEN_HOUR_SETUP,
                    entryBoost = 8.0,
                    exitUrgency = 0.0,
                    confidenceBoost = 5.0,
                    sizeMultiplier = 1.05,
                    reason = "Golden hour + ${if (isMomentumBullish) "Momentum" else "Narrative"}",
                    participatingAIs = listOf("Time", if (isMomentumBullish) "Momentum" else "Narrative"),
                    correlationStrength = 50.0,
                ),
                priority = 55
            )
        }

        // 5) LIQUIDITY + WHALE ALERT
        if (isOpenPosition &&
            liquidity?.signal == LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE &&
            isWhaleSelling
        ) {
            candidates += candidate(
                CrossTalkSignal(
                    signalType = SignalType.LIQUIDITY_WHALE_ALERT,
                    entryBoost = -30.0,
                    exitUrgency = 40.0,
                    confidenceBoost = -20.0,
                    sizeMultiplier = 0.3,
                    reason = "Liquidity collapse confirmed by whale selling",
                    participatingAIs = listOf("Liquidity", "WhaleTracker"),
                    correlationStrength = 90.0,
                ),
                priority = 110
            )
        }

        // 6) REGIME
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
                candidates += candidate(
                    CrossTalkSignal(
                        signalType = SignalType.REGIME_AMPLIFIED_BULL,
                        entryBoost = 5.0 * amplification,
                        exitUrgency = 0.0,
                        confidenceBoost = 3.0 * amplification,
                        sizeMultiplier = (1.0 + (0.1 * amplification)).coerceAtMost(1.1),
                        reason = "Bull regime (${regimeConfidence.toInt()}% confidence)",
                        participatingAIs = listOf("MarketRegime"),
                        correlationStrength = regimeConfidence,
                    ),
                    priority = 30
                )
            }

            if (isBearRegime) {
                val dampening = regimeAmplificationFactor * (regimeConfidence / 100.0)
                candidates += candidate(
                    CrossTalkSignal(
                        signalType = SignalType.REGIME_DAMPENED_BEAR,
                        entryBoost = -5.0 * dampening,
                        exitUrgency = if (isOpenPosition) 5.0 * dampening else 0.0,
                        confidenceBoost = -3.0 * dampening,
                        sizeMultiplier = (1.0 - (0.1 * dampening)).coerceAtLeast(0.9),
                        reason = "Bear regime (${regimeConfidence.toInt()}% confidence)",
                        participatingAIs = listOf("MarketRegime"),
                        correlationStrength = regimeConfidence,
                    ),
                    priority = 35
                )
            }
        }

        // 7) BEHAVIOR LEARNING
        if (!isOpenPosition) {
            try {
                val behaviorHealth = BehaviorLearning.getHealthStatus()
                val totalPatterns = behaviorHealth.goodCount + behaviorHealth.badCount
                val isBootstrap = totalPatterns < 50
                val minPatterns = if (isBootstrap) 30 else 20
                val minBadSamples = if (isBootstrap) 5 else 3

                if (totalPatterns >= minPatterns) {
                    val insights = BehaviorLearning.getInsights()
                    val topGood = insights.topGoodPatterns.firstOrNull()
                    val topBad = insights.topBadPatterns.firstOrNull()

                    if (topGood != null && topGood.winRate >= 70.0 && topGood.confidence >= 0.7) {
                        val boost = (topGood.winRate - 50.0) * 0.4
                        candidates += candidate(
                            CrossTalkSignal(
                                signalType = SignalType.BEHAVIOR_PATTERN_MATCH,
                                entryBoost = boost,
                                exitUrgency = 0.0,
                                confidenceBoost = boost * 0.5,
                                sizeMultiplier = (1.0 + (topGood.winRate - 50.0) / 200.0).coerceAtMost(1.25),
                                reason = "Learned pattern: ${topGood.signature.take(30)} (${topGood.winRate.toInt()}% win)",
                                participatingAIs = listOf("BehaviorLearning"),
                                correlationStrength = topGood.confidence * 100.0,
                            ),
                            priority = 45
                        )
                    }

                    if (topBad != null &&
                        (100.0 - topBad.winRate) >= 70.0 &&
                        topBad.confidence >= 0.7 &&
                        topBad.occurrences >= minBadSamples
                    ) {
                        val lossRate = 100.0 - topBad.winRate
                        val penaltyMult = if (isBootstrap) 0.5 else 1.0
                        val penalty = (lossRate - 50.0) * 0.3 * penaltyMult

                        candidates += candidate(
                            CrossTalkSignal(
                                signalType = SignalType.BEHAVIOR_PATTERN_MATCH,
                                entryBoost = -penalty,
                                exitUrgency = penalty * 0.5,
                                confidenceBoost = -penalty * 0.5,
                                sizeMultiplier = (1.0 - (lossRate - 50.0) / 300.0).coerceAtLeast(0.75),
                                reason = "Bad pattern: ${topBad.signature.take(30)} (${lossRate.toInt()}% loss, n=${topBad.occurrences})",
                                participatingAIs = listOf("BehaviorLearning"),
                                correlationStrength = topBad.confidence * 100.0,
                            ),
                            priority = 50
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "BehaviorLearning check error: ${e.message}")
            }
        }

        // 8) VOLATILITY SQUEEZE
        if (!isOpenPosition) {
            try {
                val inSqueeze = com.lifecyclebot.v3.scoring.VolatilityRegimeAI.isInSqueeze(mint)
                if (inSqueeze && isMomentumBullish) {
                    candidates += candidate(
                        CrossTalkSignal(
                            signalType = SignalType.VOLATILITY_SQUEEZE_BREAKOUT,
                            entryBoost = 12.0,
                            exitUrgency = 0.0,
                            confidenceBoost = 10.0,
                            sizeMultiplier = 1.15,
                            reason = "Volatility squeeze + momentum building",
                            participatingAIs = listOf("VolatilityRegime", "Momentum"),
                            correlationStrength = 75.0,
                        ),
                        priority = 75
                    )
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "VolatilityRegime check error: ${e.message}")
            }
        }

        // 9) FLOW + SMART MONEY DIVERGENCE
        if (!isOpenPosition) {
            try {
                val flowState = com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.getFlowState(mint)
                val smartMoneyDiv = com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.getDivergence(mint)

                val flowBullish =
                    flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.STRONG_BUY_PRESSURE ||
                    flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.BUY_PRESSURE
                val divBullish =
                    smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.BULLISH ||
                    smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.STRONG_BULLISH

                if (flowBullish && divBullish) {
                    candidates += candidate(
                        CrossTalkSignal(
                            signalType = SignalType.SMART_MONEY_DIVERGENCE,
                            entryBoost = 10.0,
                            exitUrgency = 0.0,
                            confidenceBoost = 8.0,
                            sizeMultiplier = 1.1,
                            reason = "Order flow + smart money both bullish",
                            participatingAIs = listOf("OrderFlow", "SmartMoney"),
                            correlationStrength = 70.0,
                        ),
                        priority = 72
                    )
                }

                val flowBearish =
                    flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.STRONG_SELL_PRESSURE ||
                    flowState == com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.FlowState.SELL_PRESSURE
                val divBearish =
                    smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.BEARISH ||
                    smartMoneyDiv == com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.STRONG_BEARISH

                if (flowBearish && divBearish) {
                    candidates += candidate(
                        CrossTalkSignal(
                            signalType = SignalType.FLOW_DIVERGENCE,
                            entryBoost = -15.0,
                            exitUrgency = 35.0,
                            confidenceBoost = -10.0,
                            sizeMultiplier = 0.6,
                            reason = "Order flow + smart money both bearish",
                            participatingAIs = listOf("OrderFlow", "SmartMoney"),
                            correlationStrength = 80.0,
                        ),
                        priority = 88
                    )
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "Flow/SmartMoney check error: ${e.message}")
            }
        }

        // 10) DRY LIQUIDITY WARNING
        try {
            val liqCycle = com.lifecyclebot.v3.scoring.LiquidityCycleAI.getCurrentState()
            if (liqCycle.riskLevel >= 4 ||
                liqCycle.phase == com.lifecyclebot.v3.scoring.LiquidityCycleAI.LiquidityPhase.DRY
            ) {
                candidates += candidate(
                    CrossTalkSignal(
                        signalType = SignalType.DRY_LIQUIDITY_WARNING,
                        entryBoost = -12.0,
                        exitUrgency = if (isOpenPosition) 25.0 else 0.0,
                        confidenceBoost = -8.0,
                        sizeMultiplier = 0.5,
                        reason = "Market-wide liquidity crisis (risk=${liqCycle.riskLevel})",
                        participatingAIs = listOf("LiquidityCycle"),
                        correlationStrength = liqCycle.healthScore,
                    ),
                    priority = if (isOpenPosition) 85 else 40
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug("CrossTalk", "LiquidityCycle check error: ${e.message}")
        }

        val best = candidates
            .sortedWith(compareByDescending<SignalCandidate> { it.priority }.thenByDescending { it.score })
            .firstOrNull()
            ?.signal
            ?: CrossTalkSignal(
                signalType = SignalType.NO_CORRELATION,
                entryBoost = 0.0,
                exitUrgency = 0.0,
                confidenceBoost = 0.0,
                sizeMultiplier = 1.0,
                reason = "No cross-talk pattern detected",
                participatingAIs = emptyList(),
                correlationStrength = 0.0,
            )

        when (best.signalType) {
            SignalType.SMART_MONEY_PUMP ->
                ErrorLogger.info("CrossTalk", "🔗 SMART MONEY PUMP: $symbol | +${best.entryBoost.toInt()} pts")
            SignalType.COORDINATED_DUMP ->
                ErrorLogger.warn("CrossTalk", "🔗 COORDINATED DUMP: $symbol | +${best.exitUrgency.toInt()} exit urgency")
            SignalType.NARRATIVE_MOMENTUM ->
                ErrorLogger.info("CrossTalk", "🔗 NARRATIVE+MOMENTUM: $symbol | +${best.entryBoost.toInt()} pts")
            SignalType.LIQUIDITY_WHALE_ALERT ->
                ErrorLogger.warn("CrossTalk", "🔗 LIQUIDITY-WHALE ALERT: $symbol | emergency")
            else -> {}
        }

        return cacheAndReturn(cacheKey, now, best)
    }

    private fun candidate(signal: CrossTalkSignal, priority: Int): SignalCandidate {
        val score =
            abs(signal.entryBoost) +
            signal.exitUrgency +
            abs(signal.confidenceBoost) +
            signal.correlationStrength +
            (abs(signal.sizeMultiplier - 1.0) * 25.0)

        return SignalCandidate(signal = signal, priority = priority, score = score)
    }

    private fun cacheAndReturn(cacheKey: String, timestamp: Long, signal: CrossTalkSignal): CrossTalkSignal {
        crossTalkCache[cacheKey] = CachedCrossTalk(signal, timestamp)
        return signal
    }

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

        val confidenceScale = confidence / 100.0
        return 1.0 + ((baseMultiplier - 1.0) * confidenceScale)
    }

    fun getEntryBoost(mint: String, symbol: String): Double {
        return analyzeCrossTalk(mint, symbol, isOpenPosition = false).entryBoost
    }

    fun getExitUrgency(mint: String, symbol: String): Double {
        return analyzeCrossTalk(mint, symbol, isOpenPosition = true).exitUrgency
    }

    fun getConfidenceBoost(mint: String, symbol: String, isOpenPosition: Boolean): Double {
        return analyzeCrossTalk(mint, symbol, isOpenPosition).confidenceBoost
    }

    fun getSizeMultiplier(mint: String, symbol: String): Double {
        return analyzeCrossTalk(mint, symbol, isOpenPosition = false).sizeMultiplier
    }

    fun isCoordinatedDump(mint: String, symbol: String): Boolean {
        val signal = analyzeCrossTalk(mint, symbol, isOpenPosition = true)
        return signal.signalType == SignalType.COORDINATED_DUMP ||
            signal.signalType == SignalType.LIQUIDITY_WHALE_ALERT
    }

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

        val whaleSignal = try { WhaleTrackerAI.getWhaleSignal(mint, symbol) } catch (_: Exception) { null }
        val momentum = try { MomentumPredictorAI.getPrediction(mint) } catch (_: Exception) { null }
        val liquiditySignal = try { LiquidityDepthAI.getSignal(mint, symbol, true) } catch (_: Exception) { null }
        val regime = try { MarketRegimeAI.getCurrentRegime() } catch (_: Exception) { null }

        val isWhaleAccumulating = whaleSignal?.recommendation in listOf("STRONG_BUY", "BUY", "LEAN_BUY")
        val isWhaleSelling = whaleSignal?.recommendation in listOf("STRONG_SELL", "SELL", "LEAN_SELL")
        val isMomentumBullish = momentum in listOf(
            MomentumPredictorAI.MomentumPrediction.STRONG_PUMP,
            MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING
        )
        val isLiquidityGrowing = liquiditySignal?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE,
            LiquidityDepthAI.SignalType.LIQUIDITY_GROWING
        )
        val isLiquidityDraining = liquiditySignal?.signal in listOf(
            LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
            LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
        )

        if (currentMode in listOf("SHITCOIN", "MICRO_CAP", "PUMP_SNIPER")) {
            if (mcap > 1_000_000 && liquidity > 100_000) {
                participatingAIs += listOf("MarketCap", "Liquidity")

                when {
                    isWhaleAccumulating && isMomentumBullish -> {
                        bestMode = "MOONSHOT"
                        highestConfidence = 85.0
                        reasons += "Graduated to $${(mcap / 1000).toInt()}k mcap with whale accumulation"
                    }
                    isLiquidityGrowing && isMomentumBullish -> {
                        bestMode = "MOMENTUM_SWING"
                        highestConfidence = 78.0
                        reasons += "Graduated with growing liquidity + momentum"
                    }
                    else -> {
                        bestMode = "LONG_HOLD"
                        highestConfidence = 72.0
                        reasons += "Graduated to established mcap ($${(mcap / 1000).toInt()}k)"
                    }
                }
            } else if (currentPnlPct > 100.0 && isMomentumBullish) {
                participatingAIs += "Momentum"
                bestMode = "MOONSHOT"
                highestConfidence = 80.0
                reasons += "100x potential - switch to trailing mode"
            }
        }

        if (currentMode !in listOf("BLUE_CHIP", "LONG_HOLD") && bestMode == currentMode) {
            if (mcap > 5_000_000 && liquidity > 500_000 && ageMinutes > 1440) {
                participatingAIs += listOf("MarketCap", "Liquidity", "Age")
                bestMode = "BLUE_CHIP"
                highestConfidence = 88.0
                reasons += "Token established: mcap=$${(mcap / 1_000_000).toInt()}M, liq=$${(liquidity / 1000).toInt()}k"
            }
        }

        if (currentMode != "MOONSHOT" && bestMode == currentMode) {
            val bigGains = currentPnlPct > 80.0
            val stillBullish = isMomentumBullish && isWhaleAccumulating
            val liquidityHealthy = liquidity > 30_000 && !isLiquidityDraining

            if (bigGains && stillBullish && liquidityHealthy) {
                participatingAIs += listOf("WhaleTracker", "Momentum", "Liquidity")
                bestMode = "MOONSHOT"
                highestConfidence = 82.0
                reasons += "Winner running: +${currentPnlPct.toInt()}% with whale support"
            }
        }

        if (currentPnlPct < -15.0 && currentPnlPct > -40.0 && bestMode == currentMode) {
            val recoverySignals = isMomentumBullish || isWhaleAccumulating
            if (recoverySignals && !isLiquidityDraining) {
                participatingAIs += if (isMomentumBullish) "Momentum" else "WhaleTracker"
                bestMode = "DIP_HUNTER"
                highestConfidence = 70.0
                reasons += "Recovery signals while underwater (${currentPnlPct.toInt()}%)"
            }
        }

        if (isLiquidityDraining && isWhaleSelling && bestMode == currentMode) {
            participatingAIs += listOf("Liquidity", "WhaleTracker")
            bestMode = "DEFENSIVE"
            highestConfidence = 90.0
            reasons += "Liquidity draining + whale selling - go defensive"
        }

        if (bestMode == currentMode) {
            val isBullRegime = regime in listOf(MarketRegimeAI.Regime.STRONG_BULL, MarketRegimeAI.Regime.BULL)
            val isBearRegime = regime in listOf(MarketRegimeAI.Regime.STRONG_BEAR, MarketRegimeAI.Regime.BEAR)

            if (isBullRegime && currentMode == "STANDARD" && isMomentumBullish) {
                participatingAIs += listOf("MarketRegime", "Momentum")
                bestMode = "MOMENTUM_SWING"
                highestConfidence = 72.0
                reasons += "Bull regime + momentum - switch to aggressive"
            }

            if (isBearRegime && currentMode != "DEFENSIVE" && isLiquidityDraining) {
                participatingAIs += listOf("MarketRegime", "Liquidity")
                bestMode = "DEFENSIVE"
                highestConfidence = 75.0
                reasons += "Bear regime + weak liquidity - go defensive"
            }
        }

        val shouldSwitch = bestMode != currentMode && highestConfidence >= 65.0

        if (shouldSwitch) {
            modeSwitchesRecommended++
            ErrorLogger.info(
                "CrossTalk",
                "🔄 MODE SWITCH: $symbol | $currentMode → $bestMode | conf=${highestConfidence.toInt()}% | ${reasons.joinToString("; ")}"
            )
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

    fun shouldCheckModeSwitch(
        mint: String,
        mcapChange: Double,
        liquidityChange: Double,
        pnlPct: Double,
    ): Boolean {
        return when {
            abs(mcapChange) > 100.0 -> true
            abs(liquidityChange) > 50.0 -> true
            pnlPct > 80.0 || pnlPct < -20.0 -> true
            else -> false
        }
    }

    fun recordOutcome(signalType: SignalType, pnlPct: Double, wasProfit: Boolean) {
        // V4 Meta: Feed to CrossMarketRegimeAI (observe market conditions)
        try { com.lifecyclebot.v4.meta.CrossTalkFusionEngine.fuse() } catch (_: Exception) {}

        when (signalType) {
            SignalType.SMART_MONEY_PUMP -> {
                if (wasProfit && pnlPct > 10.0) {
                    smartMoneyBoostWeight = (smartMoneyBoostWeight + 1.0).coerceAtMost(25.0)
                } else if (!wasProfit && pnlPct < -10.0) {
                    smartMoneyBoostWeight = (smartMoneyBoostWeight - 1.0).coerceAtLeast(5.0)
                }
            }
            SignalType.COORDINATED_DUMP -> {
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
            else -> {}
        }
    }

    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("smartMoneyBoostWeight", smartMoneyBoostWeight)
            put("coordinatedDumpUrgencyWeight", coordinatedDumpUrgencyWeight)
            put("narrativeMomentumWeight", narrativeMomentumWeight)
            put("regimeAmplificationFactor", regimeAmplificationFactor)
            put("metaCognitionWeight", metaCognitionWeight)
            put("smartMoneyPumpsDetected", smartMoneyPumpsDetected)
            put("coordinatedDumpsDetected", coordinatedDumpsDetected)
            put("narrativeMomentumDetected", narrativeMomentumDetected)
            put("modeSwitchesRecommended", modeSwitchesRecommended)
            put("metaCognitionBoostsDetected", metaCognitionBoostsDetected)
            put("totalCrossTalkAnalyses", totalCrossTalkAnalyses)
        }
    }

    fun loadFromJson(json: JSONObject) {
        try {
            smartMoneyBoostWeight = json.optDouble("smartMoneyBoostWeight", 15.0)
            coordinatedDumpUrgencyWeight = json.optDouble("coordinatedDumpUrgencyWeight", 25.0)
            narrativeMomentumWeight = json.optDouble("narrativeMomentumWeight", 10.0)
            regimeAmplificationFactor = json.optDouble("regimeAmplificationFactor", 0.3)
            metaCognitionWeight = json.optDouble("metaCognitionWeight", 1.2)
            smartMoneyPumpsDetected = json.optInt("smartMoneyPumpsDetected", 0)
            coordinatedDumpsDetected = json.optInt("coordinatedDumpsDetected", 0)
            narrativeMomentumDetected = json.optInt("narrativeMomentumDetected", 0)
            modeSwitchesRecommended = json.optInt("modeSwitchesRecommended", 0)
            metaCognitionBoostsDetected = json.optInt("metaCognitionBoostsDetected", 0)
            totalCrossTalkAnalyses = json.optInt("totalCrossTalkAnalyses", 0)
        } catch (e: Exception) {
            ErrorLogger.warn("CrossTalk", "Failed to load: ${e.message}")
        }
    }

    fun getStats(): String {
        return "CrossTalk: $totalCrossTalkAnalyses analyses | " +
            "pumps=$smartMoneyPumpsDetected dumps=$coordinatedDumpsDetected narrative=$narrativeMomentumDetected " +
            "switches=$modeSwitchesRecommended meta=$metaCognitionBoostsDetected | " +
            "weights: pump=${smartMoneyBoostWeight.toInt()} dump=${coordinatedDumpUrgencyWeight.toInt()} narr=${narrativeMomentumWeight.toInt()} meta=${"%.2f".format(metaCognitionWeight)}"
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        crossTalkCache.entries.removeIf { (_, cached) ->
            (now - cached.timestamp) > CACHE_TTL_MS * 2
        }
    }
}