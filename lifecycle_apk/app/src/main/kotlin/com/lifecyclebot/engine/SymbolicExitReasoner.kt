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

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.1254 — SELF-REVISING SYMBOLIC RULES.
    // The R1..R7 rules below are no longer static hand-authored constants:
    // each rule earns a TRUST score from its own track record. When a rule
    // fires on a mint we stash the PnL at fire time; on the next assess() for
    // that mint we grade it — did the position improve after we escalated
    // (rule was RIGHT, the danger was real) or did it keep rising (rule was a
    // false alarm that would have cut a winner)? Trust = helpful / (helpful +
    // harmful), Laplace-smoothed. Rules below MIN_TRUST stop HARD-escalating
    // and become advisory-only (still logged, still counted toward convergent
    // danger, but they no longer force an action). This is the jump from an
    // expert system to a symbolic LEARNER that prunes its own bad rules.
    //
    // Self-contained: no Context, no external feedback hook, no cross-file
    // plumbing. Grades itself purely from the assess() call stream it already
    // sees. Bounded memory (pending map capped, LRU-evicted).
    // ═══════════════════════════════════════════════════════════════════
    private object RuleLearning {
        private const val MIN_TRUST = 0.40          // below this → advisory only
        private const val MIN_SAMPLES_TO_GATE = 8   // don't gate a rule until it has evidence
        private const val IMPROVE_EPS = 0.3         // pnl must move >0.3% to count as a verdict
        private const val MAX_PENDING = 400

        // ruleId -> [helpful, harmful]
        private val helpful = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val harmful = java.util.concurrent.ConcurrentHashMap<String, Int>()
        // mint -> list of (ruleId, pnlAtFire) awaiting a grade
        private val pending = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, Double>>>()

        /** Trust for a rule: Laplace-smoothed helpful rate. New rules start trusted (1.0). */
        fun trust(ruleId: String): Double {
            val h = helpful[ruleId] ?: 0
            val b = harmful[ruleId] ?: 0
            if (h + b < MIN_SAMPLES_TO_GATE) return 1.0   // ungated until enough evidence
            return (h + 1.0) / (h + b + 2.0)
        }

        /** A rule with enough evidence and trust below floor may only advise, not force. */
        fun canHardEscalate(ruleId: String): Boolean = trust(ruleId) >= MIN_TRUST

        /** Record that ruleId fired on this mint at the given pnl (to be graded later). */
        fun noteFire(mint: String, ruleId: String, pnlNow: Double) {
            if (mint.isBlank()) return
            val list = pending.getOrPut(mint) { java.util.Collections.synchronizedList(mutableListOf()) }
            synchronized(list) {
                // keep only the most recent fire per ruleId for this mint
                list.removeAll { it.first == ruleId }
                list.add(ruleId to pnlNow)
            }
            if (pending.size > MAX_PENDING) {
                // crude LRU: drop an arbitrary oldest-ish key
                pending.keys.firstOrNull()?.let { pending.remove(it) }
            }
        }

        /**
         * Grade every rule that previously fired on this mint, using the pnl
         * delta since it fired. An EXIT/PARTIAL/TIGHTEN rule is "helpful" if the
         * position FELL after it fired (the danger was real) and "harmful" if it
         * ROSE materially (we'd have cut a winner). Clears graded entries.
         */
        fun gradeOnUpdate(mint: String, pnlNow: Double) {
            if (mint.isBlank()) return
            val list = pending.remove(mint) ?: return
            synchronized(list) {
                for ((ruleId, pnlAtFire) in list) {
                    val delta = pnlNow - pnlAtFire
                    if (delta < -IMPROVE_EPS) {
                        helpful.merge(ruleId, 1, Int::plus)        // dropped after we warned → right call
                    } else if (delta > IMPROVE_EPS) {
                        harmful.merge(ruleId, 1, Int::plus)        // rose after we warned → false alarm
                    } // else: flat, no verdict
                }
            }
        }

        fun snapshot(): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            (helpful.keys + harmful.keys).toSet().forEach { out[it] = trust(it) }
            return out
        }
    }

    /** Public read for UI/telemetry: current learned trust per rule. */
    fun ruleTrustSnapshot(): Map<String, Double> = try { RuleLearning.snapshot() } catch (_: Throwable) { emptyMap() }

    private const val TAG = "SymExit"

    data class ExitAssessment(
        val conviction: Double,
        val primarySignal: String,
        val shouldExit: Boolean,
        val suggestedAction: Action,
        val signals: Map<String, Double>,
        // V5.9.1253 — symbolic reasoning trace. firedRules holds the human-readable
        // inference chain ("fragility>0.8 ∧ pnl<0 ⟹ EXIT"). symbolicOverride is true
        // when a hard rule escalated the action above what weighted-sum produced.
        val firedRules: List<String> = emptyList(),
        val symbolicOverride: Boolean = false
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
        val maxHoldSec = when {
            tradingMode == "LAUNCH_SNIPE" -> 900L
            tradingMode == "RANGE_TRADE"  -> 3600L
            tradingMode == "MOONSHOT"     -> 7200L
            // V5.9.229: CryptoAlt trades need 60 min window — alts move slower than memes
            tradingMode.startsWith("DynScan") || tradingMode.startsWith("CryptoAlt") ||
                tradingMode.contains("learning mode") -> 3600L
            else -> 1800L
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
        // DECISION — weighted-evidence baseline
        // ════════════════════════════════════════════════════════

        val entryBar = (entryConfidence / 100.0).coerceIn(0.3, 0.9)
        val exitBar = 0.55 - (entryBar * 0.15)

        // Find the loudest signal
        val loudestSignal = signals.maxByOrNull { it.value }
        var primarySignal = loudestSignal?.key ?: "unknown"

        var suggestedAction = when {
            totalConviction >= exitBar + 0.25 -> Action.EXIT
            totalConviction >= exitBar + 0.10 -> Action.PARTIAL
            totalConviction >= exitBar - 0.05 -> Action.TIGHTEN
            else                              -> Action.HOLD
        }

        // ════════════════════════════════════════════════════════
        // V5.9.1253 — SYMBOLIC FORWARD-CHAINING INFERENCE LAYER
        //
        // The weighted-sum above is FUZZY: a single decisive danger signal
        // (e.g. v4_fragility=0.9 = liquidity collapse imminent) only adds
        // 0.9*0.07=0.063 to conviction and gets averaged into silence. That
        // is the structural gap between "weighted scorer" and "symbolic
        // reasoner": no single premise can force a conclusion.
        //
        // This layer fixes that. Each rule is an explicit logical implication
        // over the already-computed signal map + raw trade state. When a rule's
        // premises hold, it ESCALATES the action (never relaxes it — a rule can
        // only push toward safety, never override a stronger exit into a hold)
        // and records a human-readable inference chain. This is genuine forward
        // chaining: premises → fire → conclusion, with explanation.
        //
        // SAFETY: rules can only escalate (HOLD<TIGHTEN<PARTIAL<EXIT). They
        // never downgrade. They never touch the -15% hard floor (that lives in
        // the executor and fires unconditionally regardless of this layer).
        // ════════════════════════════════════════════════════════
        val firedRules = mutableListOf<String>()
        fun sig(k: String): Double = signals[k] ?: 0.0
        val rank = mapOf(Action.HOLD to 0, Action.TIGHTEN to 1, Action.PARTIAL to 2, Action.EXIT to 3)

        // V5.9.1254 — first, GRADE any rules that fired on this mint last time:
        // did the position deteriorate (rule was right) or rise (false alarm)?
        try { RuleLearning.gradeOnUpdate(mint, currentPnlPct) } catch (_: Throwable) {}

        // escalate(): a rule fires. If the rule has EARNED trust (or is still
        // ungated) it HARD-escalates the action. If its track record is poor it
        // degrades to ADVISORY — logged + counted, but it no longer forces a
        // decision. Every fire is noted for later self-grading.
        fun escalate(ruleId: String, to: Action, why: String) {
            val trusted = try { RuleLearning.canHardEscalate(ruleId) } catch (_: Throwable) { true }
            if (trusted && (rank[to] ?: 0) > (rank[suggestedAction] ?: 0)) {
                suggestedAction = to
                primarySignal = "RULE:$ruleId"
            }
            val trustTag = if (trusted) "" else " [advisory:lowTrust]"
            firedRules.add("$why$trustTag")
            try { RuleLearning.noteFire(mint, ruleId, currentPnlPct) } catch (_: Throwable) {}
        }

        // R1 — LIQUIDITY DEATH: fragility high ∧ losing ⟹ EXIT NOW.
        // A collapsing pool while underwater is unrecoverable; do not average it away.
        if (sig("v4_fragility") >= 0.8 && currentPnlPct < 0.0) {
            escalate("R1", Action.EXIT, "fragility>=0.8 ∧ pnl<0 ⟹ EXIT (liquidity death)")
        }

        // R2 — REGIME ROUT: global RISK_OFF/CHAOTIC ∧ lead asset rotating away ⟹ EXIT.
        if (sig("v4_regime") >= 0.7 && sig("v4_leadLag") >= 0.7) {
            escalate("R2", Action.EXIT, "regime_risk_off ∧ leadLag_warning ⟹ EXIT (correlated rout)")
        }

        // R3 — PROFIT EVAPORATION: large peak gain given back hard ⟹ at least PARTIAL,
        // and EXIT if momentum has also rolled over (lock the win before it's gone).
        if (sig("sym_gainErosion") >= 0.7) {
            if (sig("sym_momentum") >= 0.6) {
                escalate("R3a", Action.EXIT, "gainErosion>=0.7 ∧ momentum_down ⟹ EXIT (lock the runner)")
            } else {
                escalate("R3b", Action.PARTIAL, "gainErosion>=0.7 ⟹ PARTIAL (bank the spike)")
            }
        }

        // R4 — BLEED ACCELERATION: fast loss velocity ∧ weak strategy trust ⟹ EXIT.
        // A position bleeding fast in a mode the bot no longer trusts is a cut, not a hold.
        if (sig("sym_lossVelocity") >= 0.6 && sig("v4_trust") >= 0.5) {
            escalate("R4", Action.EXIT, "lossVelocity>=0.6 ∧ low_trust ⟹ EXIT (untrusted bleed)")
        }

        // R5 — EXECUTION DEGRADED ∧ FRAGILE: if exits may not fill AND pool is thin,
        // start exiting early while a fill is still possible.
        if (sig("v4_execution") >= 0.6 && sig("v4_fragility") >= 0.5) {
            escalate("R5", Action.PARTIAL, "exec_degraded ∧ fragility_rising ⟹ PARTIAL (exit while fillable)")
        }

        // R6 — META-DOUBT: many layers overconfident/low-trust ∧ already losing ⟹ TIGHTEN.
        // The system distrusts its own read; reduce risk rather than ride conviction.
        if (sig("v3_metacognition") >= 0.5 && currentPnlPct < -0.5) {
            escalate("R6", Action.TIGHTEN, "meta_doubt ∧ pnl<-0.5 ⟹ TIGHTEN (distrust own signal)")
        }

        // R7 — CONVERGENT DANGER: 3+ independent danger signals firing strong, even if
        // each is individually small — the AND of many weak warnings is a strong one.
        // This is the explicit symbolic capture of correlated tail risk that pure
        // averaging structurally underweights.
        val strongDangerCount = signals.values.count { it >= 0.6 }
        if (strongDangerCount >= 3 && suggestedAction != Action.EXIT) {
            escalate("R7", Action.PARTIAL, "$strongDangerCount danger signals>=0.6 ⟹ PARTIAL (convergent tail risk)")
        }

        val symbolicOverride = firedRules.any {
            (it.contains("⟹ EXIT") || it.contains("⟹ PARTIAL")) && !it.contains("[advisory:lowTrust]")
        }

        val shouldExit = suggestedAction == Action.EXIT

        return ExitAssessment(
            conviction = totalConviction,
            primarySignal = primarySignal,
            shouldExit = shouldExit,
            suggestedAction = suggestedAction,
            signals = signals,
            firedRules = firedRules,
            symbolicOverride = symbolicOverride
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
