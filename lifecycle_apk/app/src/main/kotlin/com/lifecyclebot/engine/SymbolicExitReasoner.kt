package com.lifecyclebot.engine

import com.lifecyclebot.v4.meta.StrategyTrustAI
import com.lifecyclebot.v4.meta.CrossMarketRegimeAI
import com.lifecyclebot.v4.meta.LiquidityFragilityAI
import com.lifecyclebot.v4.meta.NarrativeFlowAI
import com.lifecyclebot.v4.meta.PortfolioHeatAI
import com.lifecyclebot.v4.meta.GlobalRiskMode

/**
 * SymbolicExitReasoner — Full-spectrum agentic symbolic reasoning
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Pulls from EVERY AI layer in the system to produce fluid exit decisions.
 * No fixed thresholds. Conviction vs entry confidence determines action.
 *
 * Signal sources (12 weighted channels):
 *   V4 Meta:    StrategyTrustAI, CrossMarketRegimeAI, LiquidityFragilityAI,
 *               NarrativeFlowAI, PortfolioHeatAI
 *   V3 Scoring: BehaviorAI, MetaCognitionAI, EducationSubLayerAI
 *   Engine:     AICrossTalk, ShadowLearningEngine, MarketRegimeAI
 *   Symbolic:   Gain erosion, loss velocity, time pressure, momentum
 */
object SymbolicExitReasoner {

    private const val TAG = "SymExit"

    data class ExitAssessment(
        val conviction: Double,
        val primarySignal: String,
        val shouldExit: Boolean,
        val suggestedAction: Action,
        val signals: Map<String, Double>
    )

    enum class Action { HOLD, TIGHTEN, PARTIAL, EXIT }

    /**
     * Full agentic assessment — pulls from every AI module.
     */
    fun assess(
        currentPnlPct: Double,
        peakPnlPct: Double,
        entryConfidence: Double,
        tradingMode: String,
        holdTimeSec: Long,
        priceVelocity: Double = 0.0,
        volumeRatio: Double = 1.0,
        symbol: String = "",
        mint: String = ""
    ): ExitAssessment {

        val signals = mutableMapOf<String, Double>()
        var totalConviction = 0.0

        // ════════════════════════════════════════════════════════
        // V4 META INTELLIGENCE SIGNALS
        // ════════════════════════════════════════════════════════

        // 1. Strategy Trust (weight: 0.12)
        val trustNow = try {
            StrategyTrustAI.getAllTrustScores()[tradingMode]?.trustScore ?: 0.5
        } catch (_: Exception) { 0.5 }
        val trustSignal = when {
            trustNow < 0.3 -> 0.9
            trustNow < 0.4 -> 0.5
            trustNow < 0.5 -> 0.2
            else           -> 0.0
        }
        signals["v4_trust"] = trustSignal
        totalConviction += trustSignal * 0.12

        // 2. Cross-Market Regime (weight: 0.08)
        val regime = try { CrossMarketRegimeAI.getCurrentRegime() } catch (_: Exception) { GlobalRiskMode.RISK_ON }
        val regimeSignal = when (regime) {
            GlobalRiskMode.RISK_OFF  -> if (currentPnlPct < 0) 0.8 else 0.3
            GlobalRiskMode.RISK_ON   -> 0.0
            GlobalRiskMode.NEUTRAL   -> if (currentPnlPct < -2.0) 0.3 else 0.0
            else                     -> 0.1
        }
        signals["v4_regime"] = regimeSignal
        totalConviction += regimeSignal * 0.08

        // 3. Liquidity Fragility (weight: 0.08)
        val fragility = try {
            if (symbol.isNotEmpty()) LiquidityFragilityAI.getFragilityScore(symbol) else 0.3
        } catch (_: Exception) { 0.3 }
        val fragilitySignal = when {
            fragility > 0.8 -> 0.9   // Extremely fragile — exit fast
            fragility > 0.6 -> 0.5
            fragility > 0.4 -> 0.2
            else            -> 0.0
        }
        signals["v4_fragility"] = fragilitySignal
        totalConviction += fragilitySignal * 0.08

        // 4. Narrative Flow (weight: 0.06)
        val narrativeHeat = try {
            if (symbol.isNotEmpty()) NarrativeFlowAI.getNarrativeHeat(symbol) else 0.5
        } catch (_: Exception) { 0.5 }
        // Dying narrative + in profit = take it. Hot narrative = let it run.
        val narrativeSignal = when {
            narrativeHeat < 0.2 && currentPnlPct > 1.0  -> 0.6  // Narrative dying, take profit
            narrativeHeat < 0.3 && currentPnlPct < -1.0 -> 0.7  // Narrative dead + losing
            narrativeHeat > 0.7                           -> 0.0  // Hot — hold
            else                                          -> 0.1
        }
        signals["v4_narrative"] = narrativeSignal
        totalConviction += narrativeSignal * 0.06

        // 5. Portfolio Heat (weight: 0.06)
        val portfolioHeat = try { PortfolioHeatAI.getPortfolioHeat() } catch (_: Exception) { 0.3 }
        val heatSignal = when {
            portfolioHeat > 0.85 -> 0.7   // Overexposed — shed positions
            portfolioHeat > 0.7  -> 0.3
            else                 -> 0.0
        }
        signals["v4_portfolioHeat"] = heatSignal
        totalConviction += heatSignal * 0.06

        // ════════════════════════════════════════════════════════
        // V3 + ENGINE AI SIGNALS
        // ════════════════════════════════════════════════════════

        // 6. BehaviorAI — Tilt Protection (weight: 0.06)
        val tiltActive = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive()
        } catch (_: Exception) { false }
        val behaviorAdj = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.getFluidAdjustment()
        } catch (_: Exception) { 0.0 }
        val behaviorSignal = when {
            tiltActive                -> 0.8   // On tilt — exit aggressively
            behaviorAdj < -10.0       -> 0.4   // Negative behavioral pattern
            behaviorAdj > 5.0         -> 0.0   // Positive — hold
            else                      -> 0.1
        }
        signals["v3_behavior"] = behaviorSignal
        totalConviction += behaviorSignal * 0.06

        // 7. AICrossTalk — Exit Urgency (weight: 0.08)
        val exitUrgency = try {
            if (mint.isNotEmpty() && symbol.isNotEmpty())
                AICrossTalk.getExitUrgency(mint, symbol)
            else 0.0
        } catch (_: Exception) { 0.0 }
        val crosstalkSignal = (exitUrgency / 100.0).coerceIn(0.0, 1.0)
        signals["engine_crosstalk"] = crosstalkSignal
        totalConviction += crosstalkSignal * 0.08

        // 8. MarketRegimeAI — Local Regime (weight: 0.06)
        val localRegime = try { MarketRegimeAI.getCurrentRegime() } catch (_: Exception) { null }
        val regimeTrailMult = try { MarketRegimeAI.getTrailMultiplier() } catch (_: Exception) { 1.0 }
        val localRegimeSignal = when {
            localRegime?.name?.contains("CRASH") == true -> 0.9
            localRegime?.name?.contains("BEAR") == true  -> if (currentPnlPct < 0) 0.5 else 0.2
            regimeTrailMult < 0.5                        -> 0.4
            else                                         -> 0.0
        }
        signals["engine_regime"] = localRegimeSignal
        totalConviction += localRegimeSignal * 0.06

        // 9. ShadowLearningEngine — Mode Performance (weight: 0.06)
        val shadowPerf = try {
            val perf = ShadowLearningEngine.getModePerformance()
            val modePerf = perf[tradingMode]
            if (modePerf != null && modePerf.totalTrades > 5) {
                if (modePerf.winRate < 35.0) 0.6 else if (modePerf.winRate < 45.0) 0.2 else 0.0
            } else 0.1
        } catch (_: Exception) { 0.1 }
        signals["engine_shadow"] = shadowPerf
        totalConviction += shadowPerf * 0.06

        // 10. EducationSubLayerAI — Learning Level (weight: 0.04)
        val eduSignal = try {
            val level = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getCurrentCurriculumLevel()
            val weight = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getCurrentLearningWeight()
            when {
                weight < 0.3 -> 0.4   // Still learning — be cautious
                weight < 0.6 -> 0.2   // Intermediate
                else         -> 0.0   // Advanced — trust the decisions
            }
        } catch (_: Exception) { 0.2 }
        signals["v3_education"] = eduSignal
        totalConviction += eduSignal * 0.04

        // 11. MetaCognitionAI — Layer Underperformance (weight: 0.04)
        val metaSignal = try {
            val underperf = com.lifecyclebot.v3.scoring.MetaCognitionAI.getUnderperformingLayers()
            if (underperf.size > 3) 0.4 else if (underperf.size > 1) 0.2 else 0.0
        } catch (_: Exception) { 0.1 }
        signals["v3_metacognition"] = metaSignal
        totalConviction += metaSignal * 0.04

        // ════════════════════════════════════════════════════════
        // RAW SYMBOLIC SIGNALS (price action)
        // ════════════════════════════════════════════════════════

        // 12. Gain Erosion (weight: 0.10)
        val gainErosion = if (peakPnlPct > 1.5) {
            val giveBack = (peakPnlPct - currentPnlPct) / peakPnlPct.coerceAtLeast(0.01)
            when {
                giveBack > 0.8 && currentPnlPct < 0.5 -> 1.0
                giveBack > 0.6                          -> 0.7
                giveBack > 0.4                          -> 0.4
                giveBack > 0.2                          -> 0.15
                else                                    -> 0.0
            }
        } else 0.0
        signals["sym_gainErosion"] = gainErosion
        totalConviction += gainErosion * 0.10

        // 13. Loss Velocity (weight: 0.08)
        val lossVelocity = if (currentPnlPct < -1.0 && holdTimeSec > 30) {
            val lossPerMinute = kotlin.math.abs(currentPnlPct) / (holdTimeSec / 60.0)
            when {
                lossPerMinute > 2.0  -> 1.0
                lossPerMinute > 0.5  -> 0.6
                lossPerMinute > 0.2  -> 0.3
                else                 -> 0.1
            }
        } else 0.0
        signals["sym_lossVelocity"] = lossVelocity
        totalConviction += lossVelocity * 0.08

        // 14. Momentum Shift (weight: 0.06)
        val momentumSignal = when {
            currentPnlPct > 0 && priceVelocity < -0.5  -> 0.7
            currentPnlPct < 0 && priceVelocity < -1.0  -> 0.9
            currentPnlPct > 0 && priceVelocity > 0.5   -> 0.0
            currentPnlPct < 0 && priceVelocity > 0.3   -> 0.0
            else                                         -> 0.1
        }
        signals["sym_momentum"] = momentumSignal
        totalConviction += momentumSignal * 0.06

        // 15. Time Decay (weight: 0.04)
        val timePressure = if (kotlin.math.abs(currentPnlPct) < 2.0 && holdTimeSec > 600) {
            (holdTimeSec / 60.0 / 60.0).coerceAtMost(0.3)
        } else 0.0
        signals["sym_timeDecay"] = timePressure
        totalConviction += timePressure * 0.04

        // 16. Volume Anomaly (weight: 0.04)
        val volumeSignal = when {
            volumeRatio > 5.0 && currentPnlPct < -2.0  -> 0.9
            volumeRatio > 3.0 && currentPnlPct < -1.0  -> 0.5
            volumeRatio > 3.0 && currentPnlPct > 3.0   -> 0.3
            else                                         -> 0.0
        }
        signals["sym_volume"] = volumeSignal
        totalConviction += volumeSignal * 0.04

        // ════════════════════════════════════════════════════════
        // FINAL DECISION — conviction vs entry confidence
        // ════════════════════════════════════════════════════════
        val normalizedConf = (entryConfidence / 100.0).coerceIn(0.1, 0.95)
        val exitThreshold = normalizedConf * 0.55

        val primarySignal = signals.maxByOrNull { it.value }?.key ?: "none"

        val action = when {
            totalConviction >= exitThreshold + 0.3       -> Action.EXIT
            totalConviction >= exitThreshold + 0.15      -> Action.PARTIAL
            totalConviction >= exitThreshold              -> Action.TIGHTEN
            else                                          -> Action.HOLD
        }

        return ExitAssessment(
            conviction      = totalConviction.coerceIn(0.0, 1.0),
            primarySignal   = primarySignal,
            shouldExit      = action == Action.EXIT || action == Action.PARTIAL,
            suggestedAction = action,
            signals         = signals
        )
    }

    /**
     * Get full signal snapshot for the AI Signals UI panel.
     * Returns all 16 channels with their current firing state.
     */
    fun getSignalSnapshot(symbol: String = "", mint: String = ""): Map<String, Double> {
        val snap = mutableMapOf<String, Double>()

        // V4 Meta
        try { snap["StrategyTrust"]    = StrategyTrustAI.getAllTrustScores().values.map { it.trustScore }.average().takeIf { !it.isNaN() } ?: 0.5 } catch (_: Exception) { snap["StrategyTrust"] = 0.5 }
        try { snap["CrossRegime"]      = if (CrossMarketRegimeAI.getCurrentRegime() == GlobalRiskMode.RISK_OFF) 0.9 else 0.1 } catch (_: Exception) { snap["CrossRegime"] = 0.1 }
        try { snap["Fragility"]        = if (symbol.isNotEmpty()) LiquidityFragilityAI.getFragilityScore(symbol) else 0.3 } catch (_: Exception) { snap["Fragility"] = 0.3 }
        try { snap["NarrativeHeat"]    = if (symbol.isNotEmpty()) NarrativeFlowAI.getNarrativeHeat(symbol) else 0.5 } catch (_: Exception) { snap["NarrativeHeat"] = 0.5 }
        try { snap["PortfolioHeat"]    = PortfolioHeatAI.getPortfolioHeat() } catch (_: Exception) { snap["PortfolioHeat"] = 0.3 }

        // V3 + Engine
        try { snap["BehaviorTilt"]     = if (com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive()) 1.0 else 0.0 } catch (_: Exception) { snap["BehaviorTilt"] = 0.0 }
        try { snap["BehaviorAdj"]      = ((com.lifecyclebot.v3.scoring.BehaviorAI.getFluidAdjustment() + 20) / 40.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["BehaviorAdj"] = 0.5 }
        try { snap["CrossTalkExit"]    = if (mint.isNotEmpty() && symbol.isNotEmpty()) AICrossTalk.getExitUrgency(mint, symbol) / 100.0 else 0.0 } catch (_: Exception) { snap["CrossTalkExit"] = 0.0 }
        try { snap["LocalRegime"]      = MarketRegimeAI.getRegimeConfidence() } catch (_: Exception) { snap["LocalRegime"] = 0.5 }
        try { snap["ShadowWR"]         = (ShadowLearningEngine.getModePerformance().values.map { it.winRate }.average().takeIf { !it.isNaN() } ?: 50.0) / 100.0 } catch (_: Exception) { snap["ShadowWR"] = 0.5 }
        try { snap["EducationLevel"]   = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getCurrentLearningWeight() } catch (_: Exception) { snap["EducationLevel"] = 0.5 }
        try { snap["MetaCognition"]    = (1.0 - com.lifecyclebot.v3.scoring.MetaCognitionAI.getUnderperformingLayers().size / 10.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["MetaCognition"] = 0.5 }
        try { snap["FearGreed"]        = (com.lifecyclebot.v3.scoring.FearGreedAI.getTradeCount() / 100.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["FearGreed"] = 0.5 }
        try { snap["InsiderSignals"]   = (com.lifecyclebot.v3.scoring.InsiderTrackerAI.getRecentSignals(10).size / 10.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["InsiderSignals"] = 0.0 }
        try { snap["AdaptiveEdge"]     = MarketRegimeAI.getCurrentRegimeWinRate() / 100.0 } catch (_: Exception) { snap["AdaptiveEdge"] = 0.5 }
        try { snap["MomentumPred"]     = MarketRegimeAI.getRegimeConfidence() } catch (_: Exception) { snap["MomentumPred"] = 0.5 }

        return snap
    }
}
