package com.lifecyclebot.engine

import com.lifecyclebot.v4.meta.StrategyTrustAI
import com.lifecyclebot.v4.meta.CrossMarketRegimeAI
import com.lifecyclebot.v4.meta.LiquidityFragilityAI
import com.lifecyclebot.v4.meta.NarrativeFlowAI
import com.lifecyclebot.v4.meta.PortfolioHeatAI
import com.lifecyclebot.v4.meta.GlobalRiskMode
import com.lifecyclebot.v4.meta.CrossAssetLeadLagAI
import com.lifecyclebot.v4.meta.LeverageSurvivalAI
import com.lifecyclebot.v4.meta.ExecutionPathAI
import com.lifecyclebot.v4.meta.TradeLessonRecorder

/**
 * SymbolicExitReasoner — Full-spectrum agentic symbolic reasoning
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Pulls from EVERY AI layer in the system to produce fluid exit decisions.
 * No fixed thresholds. Conviction vs entry confidence determines action.
 *
 * V5.9.212 — UNIVERSE EXPANSION: 24 signal channels (was 16).
 * Added: CrossAssetLeadLag, LeverageSurvival, ExecutionPath, TradeLessons,
 *        CollectiveIntelligence, AITrustNetwork, RegimeTransition,
 *        DrawdownCircuit, FundingRate, SessionEdge.
 * Signal snapshot now exports all 24 named channels to SymbolicContext.
 *
 * Signal sources (24 weighted channels):
 *   V4 Meta:    StrategyTrustAI, CrossMarketRegimeAI, LiquidityFragilityAI,
 *               NarrativeFlowAI, PortfolioHeatAI, CrossAssetLeadLagAI,
 *               LeverageSurvivalAI, ExecutionPathAI, TradeLessonRecorder
 *   V3 Scoring: BehaviorAI, MetaCognitionAI, EducationSubLayerAI,
 *               CollectiveIntelligenceAI, AITrustNetworkAI, RegimeTransitionAI,
 *               DrawdownCircuitAI, FundingRateAwarenessAI, SessionEdgeAI,
 *               InsiderTrackerAI
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

        // 1. Strategy Trust (weight: 0.10)
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
        totalConviction += trustSignal * 0.10

        // 2. Cross-Market Regime (weight: 0.08)
        val regime = try { CrossMarketRegimeAI.getCurrentRegime() } catch (_: Exception) { GlobalRiskMode.RISK_ON }
        val regimeSignal = when (regime) {
            GlobalRiskMode.RISK_OFF     -> if (currentPnlPct < 0) 0.8 else 0.3
            GlobalRiskMode.RISK_ON      -> 0.0
            GlobalRiskMode.CHAOTIC      -> if (currentPnlPct < -2.0) 0.4 else 0.1
            GlobalRiskMode.MEAN_REVERT  -> if (currentPnlPct < -2.0) 0.3 else 0.0
            GlobalRiskMode.ROTATIONAL   -> 0.15
            GlobalRiskMode.TRENDING     -> 0.0
        }
        signals["v4_regime"] = regimeSignal
        totalConviction += regimeSignal * 0.08

        // 3. Liquidity Fragility (weight: 0.07)
        val fragility = try {
            if (symbol.isNotEmpty()) LiquidityFragilityAI.getFragilityScore(symbol) else 0.3
        } catch (_: Exception) { 0.3 }
        val fragilitySignal = when {
            fragility > 0.8 -> 0.9
            fragility > 0.6 -> 0.5
            fragility > 0.4 -> 0.2
            else            -> 0.0
        }
        signals["v4_fragility"] = fragilitySignal
        totalConviction += fragilitySignal * 0.07

        // 4. Narrative Flow (weight: 0.05)
        val narrativeHeat = try {
            if (symbol.isNotEmpty()) NarrativeFlowAI.getNarrativeHeat(symbol) else 0.5
        } catch (_: Exception) { 0.5 }
        val narrativeSignal = when {
            narrativeHeat < 0.2 && currentPnlPct > 1.0  -> 0.6
            narrativeHeat < 0.3 && currentPnlPct < -1.0 -> 0.7
            narrativeHeat > 0.7                           -> 0.0
            else                                          -> 0.1
        }
        signals["v4_narrative"] = narrativeSignal
        totalConviction += narrativeSignal * 0.05

        // 5. Portfolio Heat (weight: 0.05)
        val portfolioHeat = try { PortfolioHeatAI.getPortfolioHeat() } catch (_: Exception) { 0.3 }
        val heatSignal = when {
            portfolioHeat > 0.85 -> 0.7
            portfolioHeat > 0.7  -> 0.3
            else                 -> 0.0
        }
        signals["v4_portfolioHeat"] = heatSignal
        totalConviction += heatSignal * 0.05

        // 6. Cross-Asset Lead-Lag (weight: 0.06) — NEW V5.9.212
        // If a leading correlated asset is already rotating away, exit before the laggard drops.
        val leadLagMult = try {
            if (symbol.isNotEmpty()) CrossAssetLeadLagAI.getLeadLagMultiplier(symbol) else 1.0
        } catch (_: Exception) { 1.0 }
        val leadLagSignal = when {
            leadLagMult < 0.7  -> 0.8   // Strong lead-lag warning — follow the leader
            leadLagMult < 0.85 -> 0.4
            leadLagMult > 1.15 -> 0.0   // Positive rotation — stay in
            else               -> 0.1
        }
        signals["v4_leadLag"] = leadLagSignal
        totalConviction += leadLagSignal * 0.06

        // 7. Leverage Survival (weight: 0.05) — NEW V5.9.212
        // Even in spot mode this catches systemic over-exposure signals.
        val levVerdict = try { LeverageSurvivalAI.getVerdict() } catch (_: Exception) { null }
        val levSignal = when {
            levVerdict?.noLeverageOverride == true        -> 0.7   // Hard veto state — de-risk
            levVerdict?.forcedTightRisk == true           -> 0.4   // Tight risk mode
            levVerdict?.liquidationDistanceSafety != null &&
                levVerdict.liquidationDistanceSafety < 0.2 -> 0.6  // Very close to cluster
            else                                          -> 0.0
        }
        signals["v4_leverage"] = levSignal
        totalConviction += levSignal * 0.05

        // 8. Execution Path (weight: 0.04) — NEW V5.9.212
        // Degraded execution confidence = exits may not fill cleanly = exit sooner.
        val execConf = try { ExecutionPathAI.getExecutionConfidenceMultiplier() } catch (_: Exception) { 1.0 }
        val execSignal = when {
            execConf < 0.5 -> 0.6   // Execution badly degraded
            execConf < 0.75 -> 0.3
            else            -> 0.0
        }
        signals["v4_execution"] = execSignal
        totalConviction += execSignal * 0.04

        // 9. Trade Lessons (weight: 0.04) — NEW V5.9.212
        // Historical lessons in this strategy + regime = memory-weighted exit pressure.
        val lessonWinRate = try {
            val lessons = TradeLessonRecorder.getStrategyLessons(tradingMode)
            if (lessons.isNotEmpty()) {
                lessons.count { it.outcomePct > 0 }.toDouble() / lessons.size
            } else 0.5  // No lessons = neutral (0.5 = 50%)
        } catch (_: Exception) { 0.5 }
        val lessonSignal = when {
            lessonWinRate < 0.30 -> 0.5   // Terrible historical record in this mode
            lessonWinRate < 0.40 -> 0.25
            else                 -> 0.0
        }
        signals["v4_lessons"] = lessonSignal
        totalConviction += lessonSignal * 0.04

        // ════════════════════════════════════════════════════════
        // V3 + ENGINE AI SIGNALS
        // ════════════════════════════════════════════════════════

        // 10. BehaviorAI — Tilt Protection (weight: 0.05)
        val tiltActive = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive()
        } catch (_: Exception) { false }
        val behaviorAdj = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.getFluidAdjustment()
        } catch (_: Exception) { 0.0 }
        val behaviorSignal = when {
            tiltActive          -> 0.8
            behaviorAdj < -10.0 -> 0.4
            behaviorAdj > 5.0   -> 0.0
            else                -> 0.1
        }
        signals["v3_behavior"] = behaviorSignal
        totalConviction += behaviorSignal * 0.05

        // 11. AICrossTalk — Exit Urgency (weight: 0.07)
        val exitUrgency = try {
            if (mint.isNotEmpty() && symbol.isNotEmpty())
                AICrossTalk.getExitUrgency(mint, symbol)
            else 0.0
        } catch (_: Exception) { 0.0 }
        val crosstalkSignal = (exitUrgency / 100.0).coerceIn(0.0, 1.0)
        signals["engine_crosstalk"] = crosstalkSignal
        totalConviction += crosstalkSignal * 0.07

        // 12. MarketRegimeAI — Local Regime (weight: 0.05)
        val localRegime = try { MarketRegimeAI.getCurrentRegime() } catch (_: Exception) { null }
        val regimeTrailMult = try { MarketRegimeAI.getTrailMultiplier() } catch (_: Exception) { 1.0 }
        val localRegimeSignal = when {
            localRegime?.name?.contains("CRASH") == true -> 0.9
            localRegime?.name?.contains("BEAR") == true  -> if (currentPnlPct < 0) 0.5 else 0.2
            regimeTrailMult < 0.5                        -> 0.4
            else                                         -> 0.0
        }
        signals["engine_regime"] = localRegimeSignal
        totalConviction += localRegimeSignal * 0.05

        // 13. ShadowLearningEngine — Mode Performance (weight: 0.05)
        val shadowPerf = try {
            val perf = ShadowLearningEngine.getModePerformance()
            val modePerf = perf[tradingMode]
            if (modePerf != null && modePerf.trades > 5) {
                if (modePerf.winRate < 35.0) 0.6 else if (modePerf.winRate < 45.0) 0.2 else 0.0
            } else 0.1
        } catch (_: Exception) { 0.1 }
        signals["engine_shadow"] = shadowPerf
        totalConviction += shadowPerf * 0.05

        // 14. EducationSubLayerAI — Learning Level (weight: 0.03)
        val eduSignal = try {
            val weight = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getCurrentLearningWeight()
            when {
                weight < 0.3 -> 0.4
                weight < 0.6 -> 0.2
                else         -> 0.0
            }
        } catch (_: Exception) { 0.2 }
        signals["v3_education"] = eduSignal
        totalConviction += eduSignal * 0.03

        // 15. MetaCognitionAI — Layer Trust Health (weight: 0.06, was 0.03 weak noise)
        // V5.9.224: Now uses trust multiplier health + calibration, not just underperforming count
        val metaSignal = try {
            val underperf = com.lifecyclebot.v3.scoring.MetaCognitionAI.getUnderperformingLayers()
            val allPerf   = com.lifecyclebot.v3.scoring.MetaCognitionAI.getAllLayerPerformance()
            val overconf  = allPerf.values.count { it.overconfidenceScore > 0.3 && it.totalPredictions >= 15 }
            val lowTrust  = allPerf.values.count { it.trustMultiplier <= 0.85 && it.totalPredictions >= 15 }
            // Exit conviction increases when many layers are overconfident/low-trust
            val underperfSignal = when {
                underperf.size > 5 -> 0.5
                underperf.size > 3 -> 0.35
                underperf.size > 1 -> 0.2
                else               -> 0.0
            }
            val overconfSignal = (overconf.toDouble() / allPerf.size.coerceAtLeast(1) * 0.5).coerceIn(0.0, 0.5)
            val lowTrustSignal = (lowTrust.toDouble()  / allPerf.size.coerceAtLeast(1) * 0.4).coerceIn(0.0, 0.4)
            ((underperfSignal + overconfSignal + lowTrustSignal) / 1.4).coerceIn(0.0, 0.6)
        } catch (_: Exception) { 0.1 }
        signals["v3_metacognition"] = metaSignal
        totalConviction += metaSignal * 0.06

        // 16. CollectiveIntelligenceAI — Network Consensus (weight: 0.04) — NEW V5.9.212
        val collectiveSignal = try {
            if (mint.isNotEmpty()) {
                when {
                    com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.shouldAvoid(mint) -> 0.7
                    !com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.hasConsensus(mint) &&
                        currentPnlPct < 0 -> 0.3
                    com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.hasConsensus(mint) -> 0.0
                    else -> 0.1
                }
            } else 0.1
        } catch (_: Exception) { 0.1 }
        signals["v3_collective"] = collectiveSignal
        totalConviction += collectiveSignal * 0.04

        // 17. AITrustNetworkAI — Layer Trust Degradation (weight: 0.03) — NEW V5.9.212
        val trustNetSignal = try {
            val top = com.lifecyclebot.v3.scoring.AITrustNetworkAI.topWeights(5)
            val avgTrust = if (top.isNotEmpty()) top.map { it.second }.average() else 1.0
            when {
                avgTrust < 0.5 -> 0.5   // Top layers untrustworthy
                avgTrust < 0.7 -> 0.2
                else           -> 0.0
            }
        } catch (_: Exception) { 0.0 }
        signals["v3_trustNet"] = trustNetSignal
        totalConviction += trustNetSignal * 0.03

        // 18. RegimeTransitionAI — Transition Pressure (weight: 0.04) — NEW V5.9.212
        val transitionSignal = try {
            val active = com.lifecyclebot.v3.scoring.RegimeTransitionAI.getActiveTransitions()
            val highConf = active.count { it.second.confidence > 70.0 }
            when {
                highConf >= 2 -> 0.7   // Multiple regime transitions in flight — de-risk
                highConf == 1 -> 0.35
                else          -> 0.0
            }
        } catch (_: Exception) { 0.0 }
        signals["v3_regimeTrans"] = transitionSignal
        totalConviction += transitionSignal * 0.04

        // 19. DrawdownCircuitAI — Aggression Collapse (weight: 0.03) — NEW V5.9.212
        val drawdownSignal = try {
            val agg = com.lifecyclebot.v3.scoring.DrawdownCircuitAI.getAggression()
            when {
                agg < 0.3 -> 0.8   // Circuit has pulled aggression way down — exit fast
                agg < 0.6 -> 0.4
                agg < 0.8 -> 0.1
                else      -> 0.0
            }
        } catch (_: Exception) { 0.0 }
        signals["v3_drawdownCircuit"] = drawdownSignal
        totalConviction += drawdownSignal * 0.03

        // 20. FundingRateAwarenessAI — Aggression from Funding (weight: 0.02) — NEW V5.9.212
        val fundingSignal = try {
            val agg = com.lifecyclebot.v3.scoring.FundingRateAwarenessAI.getAggression()
            // Very low aggression = funding rates unfavourable = lean to exit
            when {
                agg < 0.5 -> 0.4
                agg < 0.7 -> 0.15
                else      -> 0.0
            }
        } catch (_: Exception) { 0.0 }
        signals["v3_funding"] = fundingSignal
        totalConviction += fundingSignal * 0.02

        // ════════════════════════════════════════════════════════
        // RAW SYMBOLIC SIGNALS (price action)
        // ════════════════════════════════════════════════════════

        // 21. Gain Erosion (weight: 0.09)
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
        totalConviction += gainErosion * 0.09

        // 22. Loss Velocity (weight: 0.07)
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
        totalConviction += lossVelocity * 0.07

        // 23. Momentum Shift (weight: 0.05)
        val momentumSignal = when {
            priceVelocity < -3.0                 -> 0.9
            priceVelocity < -1.5                 -> 0.6
            priceVelocity < -0.5 && volumeRatio > 1.5 -> 0.4
            priceVelocity > 2.0                  -> 0.0
            else                                 -> 0.1
        }
        signals["sym_momentum"] = momentumSignal
        totalConviction += momentumSignal * 0.05

        // 24. Time Pressure (weight: 0.04) — mode-aware hold window
        val maxHoldSec = when (tradingMode) {
            "LAUNCH_SNIPE" -> 900L
            "RANGE_TRADE"  -> 3600L
            "MOONSHOT"     -> 7200L
            else           -> 1800L
        }
        val timePressure = when {
            holdTimeSec > maxHoldSec * 1.5 -> 0.7
            holdTimeSec > maxHoldSec        -> 0.4
            holdTimeSec > maxHoldSec * 0.8  -> 0.15
            else                            -> 0.0
        }
        signals["sym_timePressure"] = timePressure
        totalConviction += timePressure * 0.04

        // ════════════════════════════════════════════════════════
        // DECISION
        // ════════════════════════════════════════════════════════

        val entryBar = (entryConfidence / 100.0).coerceIn(0.3, 0.9)
        val exitBar = 0.55 - (entryBar * 0.15)

        // Find the loudest signal
        val loudestSignal = signals.maxByOrNull { it.value }
        val primarySignal = loudestSignal?.key ?: "unknown"

        val suggestedAction = when {
            totalConviction >= exitBar + 0.25 -> Action.EXIT
            totalConviction >= exitBar + 0.10 -> Action.PARTIAL
            totalConviction >= exitBar - 0.05 -> Action.TIGHTEN
            else                              -> Action.HOLD
        }

        val shouldExit = suggestedAction == Action.EXIT

        return ExitAssessment(
            conviction = totalConviction,
            primarySignal = primarySignal,
            shouldExit = shouldExit,
            suggestedAction = suggestedAction,
            signals = signals
        )
    }

    /**
     * V5.9.212 EXPANDED — Get full signal snapshot for SymbolicContext.
     * Now exports 24 named channels (was 16).
     * Each new V4 meta engine + V3 module wired here.
     */
    fun getSignalSnapshot(symbol: String = "", mint: String = ""): Map<String, Double> {
        val snap = mutableMapOf<String, Double>()

        // V4 Meta — original 5
        try { snap["StrategyTrust"]    = StrategyTrustAI.getAllTrustScores().values.map { it.trustScore }.average().takeIf { !it.isNaN() } ?: 0.5 } catch (_: Exception) { snap["StrategyTrust"] = 0.5 }
        try { snap["CrossRegime"]      = if (CrossMarketRegimeAI.getCurrentRegime() == GlobalRiskMode.RISK_OFF) 0.9 else 0.1 } catch (_: Exception) { snap["CrossRegime"] = 0.1 }
        try { snap["Fragility"]        = if (symbol.isNotEmpty()) LiquidityFragilityAI.getFragilityScore(symbol) else 0.3 } catch (_: Exception) { snap["Fragility"] = 0.3 }
        try { snap["NarrativeHeat"]    = if (symbol.isNotEmpty()) NarrativeFlowAI.getNarrativeHeat(symbol) else 0.5 } catch (_: Exception) { snap["NarrativeHeat"] = 0.5 }
        try { snap["PortfolioHeat"]    = PortfolioHeatAI.getPortfolioHeat() } catch (_: Exception) { snap["PortfolioHeat"] = 0.3 }

        // V4 Meta — NEW V5.9.212
        try { snap["LeadLagMult"]      = CrossAssetLeadLagAI.getLeadLagMultiplier(symbol.ifEmpty { "SOL" }) } catch (_: Exception) { snap["LeadLagMult"] = 1.0 }
        try {
            val levV = LeverageSurvivalAI.getVerdict()
            snap["LevSurvival"] = if (levV.noLeverageOverride) 0.0 else levV.liquidationDistanceSafety
        } catch (_: Exception) { snap["LevSurvival"] = 0.7 }
        try { snap["ExecConfidence"]   = ExecutionPathAI.getExecutionConfidenceMultiplier().coerceIn(0.0, 2.0) / 2.0 } catch (_: Exception) { snap["ExecConfidence"] = 0.5 }
        try {
            val totalL = TradeLessonRecorder.getTotalLessons()
            snap["LessonWinRate"] = if (totalL > 0) (totalL.coerceAtMost(100).toDouble() / 100.0) else 0.5
        } catch (_: Exception) { snap["LessonWinRate"] = 0.5 }

        // V3 + Engine — original
        try { snap["BehaviorTilt"]     = if (com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive()) 1.0 else 0.0 } catch (_: Exception) { snap["BehaviorTilt"] = 0.0 }
        try { snap["BehaviorAdj"]      = ((com.lifecyclebot.v3.scoring.BehaviorAI.getFluidAdjustment() + 20) / 40.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["BehaviorAdj"] = 0.5 }
        try { snap["CrossTalkExit"]    = if (mint.isNotEmpty() && symbol.isNotEmpty()) AICrossTalk.getExitUrgency(mint, symbol) / 100.0 else 0.0 } catch (_: Exception) { snap["CrossTalkExit"] = 0.0 }
        try { snap["LocalRegime"]      = MarketRegimeAI.getRegimeConfidence() } catch (_: Exception) { snap["LocalRegime"] = 0.5 }
        try { snap["ShadowWR"]         = (ShadowLearningEngine.getModePerformance().values.map { it.winRate }.average().takeIf { !it.isNaN() } ?: 50.0) / 100.0 } catch (_: Exception) { snap["ShadowWR"] = 0.5 }
        try { snap["EducationLevel"]   = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getCurrentLearningWeight() } catch (_: Exception) { snap["EducationLevel"] = 0.5 }
        try {
            // V5.9.224: Rich MetaCognitionAI → SymbolicSnapshot signal.
            // Old signal: (1 - underperforming.size/10) → almost always ~0.8, no signal.
            // New signal: weighted composite of trust health + calibration quality + top-layer boost.
            val metaPerf = com.lifecyclebot.v3.scoring.MetaCognitionAI.getAllLayerPerformance()
            val wellCalibrated = metaPerf.values.count { it.calibrationError < 0.18 && it.totalPredictions >= 15 }
            val overconfident  = metaPerf.values.count { it.overconfidenceScore > 0.3 }
            val highTrust      = metaPerf.values.count { it.trustMultiplier >= 1.10 }
            val lowTrust       = metaPerf.values.count { it.trustMultiplier <= 0.85 }
            val total          = metaPerf.size.coerceAtLeast(1)
            // calibration ratio, trust health, overconfidence drag
            val calibRatio     = (wellCalibrated.toDouble() / total).coerceIn(0.0, 1.0)
            val trustHealth    = ((highTrust - lowTrust).toDouble() / total + 0.5).coerceIn(0.0, 1.0)
            val confPenalty    = (overconfident.toDouble() / total * 0.4).coerceIn(0.0, 0.4)
            snap["MetaCognition"] = (calibRatio * 0.45 + trustHealth * 0.40 - confPenalty + 0.15).coerceIn(0.0, 1.0)
            // Also expose raw trust health as separate key for SymbolicContext weighting
            snap["MetaTrustHealth"] = trustHealth
        } catch (_: Exception) { snap["MetaCognition"] = 0.5; snap["MetaTrustHealth"] = 0.5 }
        try { snap["FearGreed"]        = (com.lifecyclebot.v3.scoring.InsiderTrackerAI.getRecentSignals(50).size / 50.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["FearGreed"] = 0.5 }
        try { snap["InsiderSignals"]   = (com.lifecyclebot.v3.scoring.InsiderTrackerAI.getRecentSignals(10).size / 10.0).coerceIn(0.0, 1.0) } catch (_: Exception) { snap["InsiderSignals"] = 0.0 }
        try { snap["AdaptiveEdge"]     = MarketRegimeAI.getCurrentRegimeWinRate() / 100.0 } catch (_: Exception) { snap["AdaptiveEdge"] = 0.5 }
        try { snap["MomentumPred"]     = MarketRegimeAI.getRegimeConfidence() } catch (_: Exception) { snap["MomentumPred"] = 0.5 }

        // V3 — NEW V5.9.212
        try {
            val shouldAvoid = if (mint.isNotEmpty()) com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.shouldAvoid(mint) else false
            val hasConsensus = if (mint.isNotEmpty()) com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.hasConsensus(mint) else true
            snap["CollectiveConsensus"] = when {
                shouldAvoid    -> 0.0
                hasConsensus   -> 1.0
                else           -> 0.5
            }
        } catch (_: Exception) { snap["CollectiveConsensus"] = 0.5 }

        try {
            val top = com.lifecyclebot.v3.scoring.AITrustNetworkAI.topWeights(5)
            snap["TrustNetAvg"] = if (top.isNotEmpty()) top.map { it.second }.average().coerceIn(0.0, 2.0) / 2.0 else 0.5
        } catch (_: Exception) { snap["TrustNetAvg"] = 0.5 }

        try {
            val transitions = com.lifecyclebot.v3.scoring.RegimeTransitionAI.getActiveTransitions()
            snap["RegimeTransitionPressure"] = (transitions.count { it.second.confidence > 70.0 } / 3.0).coerceIn(0.0, 1.0)
        } catch (_: Exception) { snap["RegimeTransitionPressure"] = 0.0 }

        try {
            val agg = com.lifecyclebot.v3.scoring.DrawdownCircuitAI.getAggression()
            snap["DrawdownCircuitAgg"] = agg.coerceIn(0.0, 1.0)
        } catch (_: Exception) { snap["DrawdownCircuitAgg"] = 1.0 }

        try {
            val agg = com.lifecyclebot.v3.scoring.FundingRateAwarenessAI.getAggression()
            snap["FundingRateAgg"] = agg.coerceIn(0.0, 1.0)
        } catch (_: Exception) { snap["FundingRateAgg"] = 1.0 }

        return snap
    }
}
