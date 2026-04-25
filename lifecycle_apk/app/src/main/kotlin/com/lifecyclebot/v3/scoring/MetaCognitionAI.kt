package com.lifecyclebot.v3.scoring

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * MetaCognitionAI - Layer 20: Self-Aware Executive / Model Governance Layer
 * ----------------------------------------------------------------------------
 *
 * PURPOSE
 * - Track which AI layers are actually helping
 * - Down-weight layers that are noisy, overconfident, or regime-fragile
 * - Detect high-quality consensus vs dangerous disagreement
 * - Learn reusable consensus signatures from real trade outcomes
 * - Produce meta-confidence: confidence in the system's confidence
 * - Provide veto / score-adjustment hooks for execution gating
 *
 * DESIGN NOTES
 * - Conservative until enough samples exist
 * - Sample-size aware trust weighting
 * - Proper confidence calibration penalties
 * - Thread-safe collections
 * - JSON save/load included for persistence
 */
object MetaCognitionAI {

    private const val TAG = "MetaCognitionAI"

    // -------------------------------------------------------------------------
    // LAYER REGISTRY
    // -------------------------------------------------------------------------

    // V5.9.224 — Expanded to all 41 scoring layers.
    // The 16 V5.9.123 layers + trader-class layers were missing, meaning
    // MetaCognitionAI never tracked their accuracy or adjusted their trust.
    enum class AILayer(val displayName: String, val category: String) {
        // ── V3 Core ──────────────────────────────────────────────────────────
        VOLATILITY_REGIME("VolatilityRegimeAI", "market"),
        ORDER_FLOW_IMBALANCE("OrderFlowImbalanceAI", "flow"),
        SMART_MONEY_DIVERGENCE("SmartMoneyDivergenceAI", "whale"),
        HOLD_TIME_OPTIMIZER("HoldTimeOptimizerAI", "timing"),
        LIQUIDITY_CYCLE("LiquidityCycleAI", "liquidity"),
        MARKET_REGIME("MarketRegimeAI", "market"),
        WHALE_TRACKER("WhaleTrackerAI", "whale"),
        MOMENTUM_PREDICTOR("MomentumPredictorAI", "momentum"),
        NARRATIVE_DETECTOR("NarrativeDetectorAI", "narrative"),
        TIME_OPTIMIZATION("TimeOptimizationAI", "timing"),
        LIQUIDITY_DEPTH("LiquidityDepthAI", "liquidity"),
        ENTRY_INTELLIGENCE("EntryIntelligence", "learning"),
        EXIT_INTELLIGENCE("ExitIntelligence", "learning"),
        EDGE_LEARNING("EdgeLearning", "learning"),
        TOKEN_WIN_MEMORY("TokenWinMemory", "memory"),
        COLLECTIVE_INTELLIGENCE("CollectiveIntelligenceAI", "collective"),
        GEMINI_COPILOT("GeminiCopilot", "llm"),
        GROQ_NARRATIVE("GroqNarrativeAI", "llm"),
        ORTHOGONAL_SIGNALS("OrthogonalSignals", "coordination"),
        AI_CROSSTALK("AICrossTalk", "coordination"),
        // ── V5.9.123 — 16 new layers (previously invisible to MetaCognition) ──
        CORRELATION_HEDGE("CorrelationHedgeAI", "risk"),
        LIQUIDITY_EXIT_PATH("LiquidityExitPathAI", "liquidity"),
        MEV_DETECTION("MEVDetectionAI", "risk"),
        STABLECOIN_FLOW("StablecoinFlowAI", "flow"),
        OPERATOR_FINGERPRINT("OperatorFingerprintAI", "risk"),
        SESSION_EDGE("SessionEdgeAI", "timing"),
        EXECUTION_COST_PREDICTOR("ExecutionCostPredictorAI", "execution"),
        DRAWDOWN_CIRCUIT("DrawdownCircuitAI", "risk"),
        CAPITAL_EFFICIENCY("CapitalEfficiencyAI", "risk"),
        TOKEN_DNA_CLUSTERING("TokenDNAClusteringAI", "pattern"),
        PEER_ALPHA_VERIFICATION("PeerAlphaVerificationAI", "collective"),
        NEWS_SHOCK("NewsShockAI", "narrative"),
        FUNDING_RATE_AWARENESS("FundingRateAwarenessAI", "market"),
        ORDERBOOK_IMBALANCE_PULSE("OrderbookImbalancePulseAI", "flow"),
        AI_TRUST_NETWORK("AITrustNetworkAI", "meta"),
        REFLEX_AI("ReflexAI", "execution"),
        // ── Trader-class ──────────────────────────────────────────────────────
        SHITCOIN_TRADER("ShitCoinTraderAI", "trader"),
        SHITCOIN_EXPRESS("ShitCoinExpress", "trader"),  // V5.9.230
        QUALITY_TRADER("QualityTraderAI", "trader"),
        MOONSHOT_TRADER("MoonshotTraderAI", "trader"),
        BLUECHIP_TRADER("BlueChipTraderAI", "trader"),
        DIP_HUNTER("DipHunterAI", "trader"),
        // ── Behaviour / Meta / Insider ────────────────────────────────────────
        BEHAVIOR_AI("BehaviorAI", "behavior"),
        INSIDER_TRACKER("InsiderTrackerAI", "insider"),
        FEAR_GREED("FearGreedAI", "market"),
        SOCIAL_VELOCITY("SocialVelocityAI", "narrative"),
    }

    enum class SignalType {
        BULLISH,
        BEARISH,
        NEUTRAL
    }

    data class Prediction(
        val layer: AILayer,
        val signal: SignalType,
        val confidence: Double,      // 0..100
        val rawScore: Double,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        val confidence01: Double
            get() = (confidence / 100.0).coerceIn(0.0, 1.0)
    }

    data class TradeOutcome(
        val mint: String,
        val symbol: String,
        val pnlPct: Double,
        val isWin: Boolean,
        val holdTimeMs: Long,
        val exitReason: String,
        val predictions: List<Prediction>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * Per-layer rolling performance.
     *
     * calibrationError:
     *   Lower is better. Rough Brier-like calibration penalty.
     *
     * directionalEdge:
     *   Net usefulness of bullish vs bearish calls via realized pnl.
     *
     * recentAccuracyEwma:
     *   Faster-reacting rolling accuracy.
     */
    data class LayerPerformance(
        val layer: AILayer,
        val totalPredictions: Int = 0,
        val bullishCalls: Int = 0,
        val bearishCalls: Int = 0,
        val neutralCalls: Int = 0,
        val correctPredictions: Int = 0,
        val incorrectPredictions: Int = 0,
        val avgConfidenceOnWins: Double = 50.0,
        val avgConfidenceOnLosses: Double = 50.0,
        val recentAccuracyEwma: Double = 50.0,
        val calibrationError: Double = 0.25,   // lower = better
        val overconfidenceScore: Double = 0.0, // >0 worse, <0 disciplined
        val directionalEdge: Double = 0.0,
        val profitContribution: Double = 0.0,
        val edgeCases: Int = 0,
        val lastUpdated: Long = 0L,
    ) {
        val accuracy: Double
            get() = if (totalPredictions > 0) {
                correctPredictions.toDouble() / totalPredictions * 100.0
            } else {
                50.0
            }

        val sampleConfidence: Double
            get() = when {
                totalPredictions >= 100 -> 1.00
                totalPredictions >= 60 -> 0.90
                totalPredictions >= 30 -> 0.75
                totalPredictions >= 15 -> 0.55
                totalPredictions >= 8 -> 0.35
                else -> 0.15
            }

        /**
         * Final trust multiplier:
         * - sample-size aware
         * - rewards real accuracy
         * - penalizes calibration failure / overconfidence
         * - clipped for safety
         */
        val trustMultiplier: Double
            get() {
                val accComponent = when {
                    accuracy >= 72.0 -> 1.22
                    accuracy >= 65.0 -> 1.14
                    accuracy >= 58.0 -> 1.07
                    accuracy >= 50.0 -> 1.00
                    accuracy >= 44.0 -> 0.92
                    else -> 0.82
                }

                val ewmaComponent = when {
                    recentAccuracyEwma >= 70.0 -> 1.07
                    recentAccuracyEwma >= 60.0 -> 1.03
                    recentAccuracyEwma <= 40.0 -> 0.93
                    recentAccuracyEwma <= 32.0 -> 0.88
                    else -> 1.0
                }

                val calibrationComponent = when {
                    calibrationError <= 0.10 -> 1.06
                    calibrationError <= 0.16 -> 1.03
                    calibrationError >= 0.32 -> 0.92
                    calibrationError >= 0.25 -> 0.96
                    else -> 1.0
                }

                val overconfidenceComponent = when {
                    overconfidenceScore >= 0.35 -> 0.88
                    overconfidenceScore >= 0.20 -> 0.94
                    overconfidenceScore <= -0.10 -> 1.03
                    else -> 1.0
                }

                val raw = accComponent *
                    ewmaComponent *
                    calibrationComponent *
                    overconfidenceComponent

                val blended = 1.0 + ((raw - 1.0) * sampleConfidence)
                return blended.coerceIn(0.65, 1.35)
            }
    }

    data class ConsensusPattern(
        val signature: String,
        val bullishLayers: Set<AILayer>,
        val bearishLayers: Set<AILayer>,
        val neutralLayers: Set<AILayer>,
        val occurrences: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val avgPnl: Double = 0.0,
        val lastSeen: Long = System.currentTimeMillis(),
    ) {
        val winRate: Double
            get() = if (occurrences > 0) wins.toDouble() / occurrences * 100.0 else 0.0

        fun matches(predictions: List<Prediction>): Boolean {
            return buildSignature(predictions) == signature
        }

        companion object {
            fun buildSignature(predictions: List<Prediction>): String {
                val b = predictions.filter { it.signal == SignalType.BULLISH }
                    .map { it.layer.name }
                    .sorted()
                    .joinToString(",")

                val s = predictions.filter { it.signal == SignalType.BEARISH }
                    .map { it.layer.name }
                    .sorted()
                    .joinToString(",")

                val n = predictions.filter { it.signal == SignalType.NEUTRAL }
                    .map { it.layer.name }
                    .sorted()
                    .joinToString(",")

                return "B[$b]|S[$s]|N[$n]"
            }
        }
    }

    data class MetaConfidenceResult(
        val confidence: Double,
        val dominantSignal: String,
        val factors: List<String>,
        val agreementRatio: Double,
        val weightedBias: Double,  // -100..100
    ) {
        val shouldProceed: Boolean get() = confidence >= 45.0
        val isHighConfidence: Boolean get() = confidence >= 70.0
        val isLowConfidence: Boolean get() = confidence < 35.0

        fun summary(): String {
            return "Meta=${confidence.toInt()}% sig=$dominantSignal agr=${(agreementRatio * 100).toInt()}% bias=${weightedBias.toInt()} [${factors.joinToString(", ")}]"
        }
    }

    data class LayerDashboardItem(
        val name: String,
        val category: String,
        val accuracy: Double,
        val predictions: Int,
        val trustMultiplier: Double,
        val recentAccuracy: Double,
        val calibrationError: Double,
        val profitContribution: Double,
        val isOverconfident: Boolean,
    )

    // -------------------------------------------------------------------------
    // STATE
    // -------------------------------------------------------------------------

    private const val MAX_OUTCOMES = 300
    private const val MAX_PATTERNS = 100
    private const val MIN_TRADES_FOR_META = 20
    private const val MIN_TRADES_FOR_VETO = 30
    private const val MIN_PATTERN_OCCURRENCES = 3

    private val layerPerformance = ConcurrentHashMap<AILayer, LayerPerformance>()
    private val pendingPredictions = ConcurrentHashMap<String, List<Prediction>>()

    private val recentOutcomes = CopyOnWriteArrayList<TradeOutcome>()
    private val winningPatterns = ConcurrentHashMap<String, ConsensusPattern>()
    private val losingPatterns = ConcurrentHashMap<String, ConsensusPattern>()

    @Volatile
    private var totalTradesAnalyzed: Int = 0

    @Volatile
    private var metaAccuracy: Double = 50.0

    @Volatile
    private var metaCalibrationError: Double = 0.25

    @Volatile
    private var lastMetaUpdate: Long = 0L

    // -------------------------------------------------------------------------
    // RECORD ENTRY
    // -------------------------------------------------------------------------

    fun recordEntryPredictions(
        mint: String,
        symbol: String,
        predictions: Map<AILayer, Pair<SignalType, Double>>,
    ) {
        if (mint.isBlank() || predictions.isEmpty()) return

        val normalized = predictions.map { (layer, pair) ->
            Prediction(
                layer = layer,
                signal = pair.first,
                confidence = pair.second.coerceIn(0.0, 100.0),
                rawScore = pair.second,
            )
        }

        pendingPredictions[mint] = normalized
        Log.d(TAG, "Recorded ${normalized.size} meta predictions for $symbol")
    }

    /**
     * Convenience adapter from scorecard components.
     * Kept compatible with your existing caller.
     */
    fun recordFromScoreCard(
        mint: String,
        symbol: String,
        components: List<ScoreComponent>,
    ) {
        val mapped = mutableMapOf<AILayer, Pair<SignalType, Double>>()

        for (comp in components) {
            val layer = mapComponentToLayer(comp.name) ?: continue
            val signal = when {
                comp.value > 4.0 -> SignalType.BULLISH
                comp.value < -4.0 -> SignalType.BEARISH
                else -> SignalType.NEUTRAL
            }
            val confidence = (50.0 + comp.value * 2.0).coerceIn(0.0, 100.0)
            mapped[layer] = signal to confidence
        }

        if (mapped.isNotEmpty()) {
            recordEntryPredictions(mint, symbol, mapped)
        }
    }

    private fun mapComponentToLayer(name: String): AILayer? {
        return when (name.lowercase()) {
            "volatility", "volatility_regime" -> AILayer.VOLATILITY_REGIME
            "orderflow", "order_flow", "flow_imbalance" -> AILayer.ORDER_FLOW_IMBALANCE
            "smartmoney", "smart_money" -> AILayer.SMART_MONEY_DIVERGENCE
            "holdtime", "hold_time" -> AILayer.HOLD_TIME_OPTIMIZER
            "liquidity_cycle", "liq_cycle" -> AILayer.LIQUIDITY_CYCLE
            "regime", "market_regime" -> AILayer.MARKET_REGIME
            "whale", "whale_tracker" -> AILayer.WHALE_TRACKER
            "momentum" -> AILayer.MOMENTUM_PREDICTOR
            "narrative" -> AILayer.NARRATIVE_DETECTOR
            "time", "time_opt" -> AILayer.TIME_OPTIMIZATION
            "liquidity", "liq_depth" -> AILayer.LIQUIDITY_DEPTH
            "entry" -> AILayer.ENTRY_INTELLIGENCE
            "exit" -> AILayer.EXIT_INTELLIGENCE
            "edge" -> AILayer.EDGE_LEARNING
            "memory", "win_memory" -> AILayer.TOKEN_WIN_MEMORY
            "collective" -> AILayer.COLLECTIVE_INTELLIGENCE
            "gemini" -> AILayer.GEMINI_COPILOT
            "groq", "groq_narrative" -> AILayer.GROQ_NARRATIVE
            "orthogonal" -> AILayer.ORTHOGONAL_SIGNALS
            "crosstalk", "cross_talk" -> AILayer.AI_CROSSTALK
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // RECORD OUTCOME
    // -------------------------------------------------------------------------

    fun recordTradeOutcome(
        mint: String,
        symbol: String,
        pnlPct: Double,
        holdTimeMs: Long,
        exitReason: String,
    ) {
        val preds = pendingPredictions.remove(mint) ?: return

        val outcome = TradeOutcome(
            mint = mint,
            symbol = symbol,
            pnlPct = pnlPct,
            isWin = pnlPct >= 1.0,  // V5.9.208: unified 1% threshold (was > 0.0 — counted any profit as win regardless of fees)
            holdTimeMs = holdTimeMs,
            exitReason = exitReason,
            predictions = preds,
        )

        recentOutcomes.add(outcome)
        while (recentOutcomes.size > MAX_OUTCOMES) {
            recentOutcomes.removeAt(0)
        }

        for (pred in preds) {
            updateLayerPerformance(pred, outcome)
        }

        updateConsensusPatterns(outcome)
        updateMetaCalibration(outcome)

        totalTradesAnalyzed++
        lastMetaUpdate = System.currentTimeMillis()

        Log.i(
            TAG,
            "Meta learned $symbol ${if (outcome.isWin) "WIN" else "LOSS"} ${pnlPct.toInt()}% from ${preds.size} layers"
        )
    }

    private fun updateLayerPerformance(pred: Prediction, outcome: TradeOutcome) {
        val current = layerPerformance[pred.layer] ?: LayerPerformance(layer = pred.layer)

        // V5.9.212: Unified 1% isWin threshold — prevents fee-drag counting as win
        val wasCorrect = when (pred.signal) {
            SignalType.BULLISH -> outcome.pnlPct >= 1.0
            SignalType.BEARISH -> outcome.pnlPct <= -1.0
            SignalType.NEUTRAL -> outcome.pnlPct in -1.0..1.0
        }

        val realizedProbability = when {
            outcome.pnlPct >= 1.0  -> 1.0
            outcome.pnlPct <= -1.0 -> 0.0
            else -> 0.5
        }

        val predictedProbability = when (pred.signal) {
            SignalType.BULLISH -> pred.confidence01
            SignalType.BEARISH -> 1.0 - pred.confidence01
            SignalType.NEUTRAL -> 0.5
        }

        val brier = (predictedProbability - realizedProbability) *
            (predictedProbability - realizedProbability)

        val overconfidenceDelta = when {
            !wasCorrect && pred.confidence >= 75.0 -> ((pred.confidence - 50.0) / 50.0)
            wasCorrect && pred.confidence >= 75.0 -> -((pred.confidence - 50.0) / 120.0)
            else -> 0.0
        }

        val bullishCalls = current.bullishCalls + if (pred.signal == SignalType.BULLISH) 1 else 0
        val bearishCalls = current.bearishCalls + if (pred.signal == SignalType.BEARISH) 1 else 0
        val neutralCalls = current.neutralCalls + if (pred.signal == SignalType.NEUTRAL) 1 else 0

        val newCorrect = current.correctPredictions + if (wasCorrect) 1 else 0
        val newIncorrect = current.incorrectPredictions + if (!wasCorrect) 1 else 0
        val newTotal = current.totalPredictions + 1

        val pnlContribution = when (pred.signal) {
            SignalType.BULLISH -> outcome.pnlPct
            SignalType.BEARISH -> -outcome.pnlPct
            SignalType.NEUTRAL -> -abs(outcome.pnlPct) * 0.10
        }

        val newPerf = current.copy(
            totalPredictions = newTotal,
            bullishCalls = bullishCalls,
            bearishCalls = bearishCalls,
            neutralCalls = neutralCalls,
            correctPredictions = newCorrect,
            incorrectPredictions = newIncorrect,
            avgConfidenceOnWins = if (outcome.isWin) {
                ewma(current.avgConfidenceOnWins, pred.confidence, 0.12)
            } else {
                current.avgConfidenceOnWins
            },
            avgConfidenceOnLosses = if (!outcome.isWin) {
                ewma(current.avgConfidenceOnLosses, pred.confidence, 0.12)
            } else {
                current.avgConfidenceOnLosses
            },
            recentAccuracyEwma = ewma(
                current.recentAccuracyEwma,
                if (wasCorrect) 100.0 else 0.0,
                0.10
            ),
            calibrationError = ewma(current.calibrationError, brier, 0.08),
            overconfidenceScore = ewma(current.overconfidenceScore, overconfidenceDelta, 0.08),
            directionalEdge = current.directionalEdge + pnlContribution,
            profitContribution = current.profitContribution + pnlContribution,
            edgeCases = current.edgeCases + if (wasCorrect && wasLayerAloneRight(pred, outcome.predictions)) 1 else 0,
            lastUpdated = System.currentTimeMillis(),
        )

        layerPerformance[pred.layer] = newPerf
    }

    private fun wasLayerAloneRight(pred: Prediction, allPredictions: List<Prediction>): Boolean {
        val sameDirectionCorrect = when (pred.signal) {
            SignalType.BULLISH -> allPredictions.count { it.signal == SignalType.BULLISH }
            SignalType.BEARISH -> allPredictions.count { it.signal == SignalType.BEARISH }
            SignalType.NEUTRAL -> allPredictions.count { it.signal == SignalType.NEUTRAL }
        }
        return sameDirectionCorrect == 1
    }

    // -------------------------------------------------------------------------
    // CONSENSUS LEARNING
    // -------------------------------------------------------------------------

    private fun updateConsensusPatterns(outcome: TradeOutcome) {
        val signature = ConsensusPattern.buildSignature(outcome.predictions)

        if (outcome.isWin) {
            val existing = winningPatterns[signature]
            winningPatterns[signature] = if (existing == null) {
                buildPattern(signature, outcome, wins = 1, losses = 0, occurrences = 1)
            } else {
                existing.copy(
                    occurrences = existing.occurrences + 1,
                    wins = existing.wins + 1,
                    avgPnl = runningAverage(existing.avgPnl, existing.occurrences, outcome.pnlPct),
                    lastSeen = System.currentTimeMillis(),
                )
            }
        } else {
            val existing = losingPatterns[signature]
            losingPatterns[signature] = if (existing == null) {
                buildPattern(signature, outcome, wins = 0, losses = 1, occurrences = 1)
            } else {
                existing.copy(
                    occurrences = existing.occurrences + 1,
                    losses = existing.losses + 1,
                    avgPnl = runningAverage(existing.avgPnl, existing.occurrences, outcome.pnlPct),
                    lastSeen = System.currentTimeMillis(),
                )
            }
        }

        trimPatternMaps()
    }

    private fun buildPattern(
        signature: String,
        outcome: TradeOutcome,
        wins: Int,
        losses: Int,
        occurrences: Int,
    ): ConsensusPattern {
        return ConsensusPattern(
            signature = signature,
            bullishLayers = outcome.predictions.filter { it.signal == SignalType.BULLISH }.map { it.layer }.toSet(),
            bearishLayers = outcome.predictions.filter { it.signal == SignalType.BEARISH }.map { it.layer }.toSet(),
            neutralLayers = outcome.predictions.filter { it.signal == SignalType.NEUTRAL }.map { it.layer }.toSet(),
            occurrences = occurrences,
            wins = wins,
            losses = losses,
            avgPnl = outcome.pnlPct,
            lastSeen = System.currentTimeMillis(),
        )
    }

    private fun trimPatternMaps() {
        if (winningPatterns.size > MAX_PATTERNS) {
            val keep = winningPatterns.values
                .sortedByDescending { it.occurrences }
                .take(MAX_PATTERNS)
                .associateBy { it.signature }
            winningPatterns.clear()
            winningPatterns.putAll(keep)
        }

        if (losingPatterns.size > MAX_PATTERNS) {
            val keep = losingPatterns.values
                .sortedByDescending { it.occurrences }
                .take(MAX_PATTERNS)
                .associateBy { it.signature }
            losingPatterns.clear()
            losingPatterns.putAll(keep)
        }
    }

    // -------------------------------------------------------------------------
    // META CALIBRATION
    // -------------------------------------------------------------------------

    private fun updateMetaCalibration(outcome: TradeOutcome) {
        val meta = calculateMetaConfidence(outcome.predictions)

        val predicted = meta.confidence / 100.0
        val actual = if (outcome.isWin) 1.0 else 0.0
        val err = (predicted - actual) * (predicted - actual)

        metaCalibrationError = ewma(metaCalibrationError, err, 0.08)
        metaAccuracy = ewma(metaAccuracy, if (outcome.isWin == (meta.dominantSignal == "BULLISH")) 100.0 else 0.0, 0.08)
    }

    // -------------------------------------------------------------------------
    // META CONFIDENCE
    // -------------------------------------------------------------------------

    fun calculateMetaConfidence(predictions: List<Prediction>): MetaConfidenceResult {
        if (predictions.isEmpty()) {
            return MetaConfidenceResult(
                confidence = 50.0,
                dominantSignal = "NONE",
                factors = listOf("no_predictions"),
                agreementRatio = 0.0,
                weightedBias = 0.0,
            )
        }

        val factors = mutableListOf<String>()
        var score = 50.0

        val bullish = predictions.filter { it.signal == SignalType.BULLISH }
        val bearish = predictions.filter { it.signal == SignalType.BEARISH }
        val neutral = predictions.filter { it.signal == SignalType.NEUTRAL }

        val directionalCount = bullish.size + bearish.size
        val agreementRatio = if (directionalCount > 0) {
            max(bullish.size, bearish.size).toDouble() / directionalCount.toDouble()
        } else {
            0.5
        }

        val dominantSignal = when {
            bullish.size > bearish.size -> "BULLISH"
            bearish.size > bullish.size -> "BEARISH"
            else -> "MIXED"
        }

        // 1) AGREEMENT
        val agreementAdj = when {
            agreementRatio >= 0.90 -> 18.0
            agreementRatio >= 0.75 -> 10.0
            agreementRatio >= 0.60 -> 4.0
            agreementRatio < 0.45 -> -12.0
            else -> 0.0
        }
        score += agreementAdj
        factors.add("agr=${(agreementRatio * 100).toInt()}→${agreementAdj.toInt()}")

        // 2) TRUST-WEIGHTED BIAS
        var weightedDirectional = 0.0
        var totalWeight = 0.0
        for (pred in predictions) {
            val trust = getTrustMultiplier(pred.layer)
            val signed = when (pred.signal) {
                SignalType.BULLISH -> pred.confidence
                SignalType.BEARISH -> -pred.confidence
                SignalType.NEUTRAL -> 0.0
            }
            weightedDirectional += signed * trust
            totalWeight += trust
        }

        val weightedBias = if (totalWeight > 0.0) {
            (weightedDirectional / totalWeight).coerceIn(-100.0, 100.0)
        } else {
            0.0
        }

        val biasAdj = (abs(weightedBias) / 14.0).coerceAtMost(8.0)
        score += biasAdj
        factors.add("bias=${weightedBias.toInt()}→${biasAdj.toInt()}")

        // 3) ACCURATE LAYER PARTICIPATION
        val strongBullishLayers = bullish.count { (layerPerformance[it.layer]?.accuracy ?: 50.0) >= 60.0 }
        val strongBearishLayers = bearish.count { (layerPerformance[it.layer]?.accuracy ?: 50.0) >= 60.0 }

        val accurateAdj = when {
            strongBullishLayers >= 3 && strongBearishLayers == 0 -> 10.0
            strongBearishLayers >= 3 && strongBullishLayers == 0 -> 10.0
            strongBullishLayers > 0 && strongBearishLayers > 0 -> -8.0
            else -> 0.0
        }
        score += accurateAdj
        if (accurateAdj != 0.0) {
            factors.add("trusted=${strongBullishLayers}B/${strongBearishLayers}S→${accurateAdj.toInt()}")
        }

        // 4) CATEGORY COVERAGE
        val categories = predictions.map { it.layer.category }.toSet()
        val coverageAdj = when {
            categories.size >= 5 -> 5.0
            categories.size >= 3 -> 2.0
            categories.size <= 1 -> -5.0
            else -> 0.0
        }
        score += coverageAdj
        factors.add("cat=${categories.size}→${coverageAdj.toInt()}")

        // 5) KNOWN PATTERN MATCH
        val signature = ConsensusPattern.buildSignature(predictions)
        val winPattern = winningPatterns[signature]
        val lossPattern = losingPatterns[signature]

        val patternAdj = when {
            winPattern != null && winPattern.occurrences >= MIN_PATTERN_OCCURRENCES && winPattern.winRate >= 66.0 -> 12.0
            lossPattern != null && lossPattern.occurrences >= MIN_PATTERN_OCCURRENCES && lossPattern.winRate <= 34.0 -> -12.0
            else -> 0.0
        }
        score += patternAdj
        if (patternAdj != 0.0) {
            factors.add("pattern→${patternAdj.toInt()}")
        }

        // 6) META HEALTH
        if (totalTradesAnalyzed >= MIN_TRADES_FOR_META) {
            val metaHealthAdj = when {
                metaAccuracy >= 62.0 && metaCalibrationError <= 0.18 -> 8.0
                metaAccuracy >= 56.0 -> 4.0
                metaAccuracy <= 42.0 || metaCalibrationError >= 0.30 -> -8.0
                metaAccuracy <= 47.0 -> -4.0
                else -> 0.0
            }
            score += metaHealthAdj
            if (metaHealthAdj != 0.0) {
                factors.add("meta=${metaAccuracy.toInt()} cal=${fmt(metaCalibrationError, 2)}→${metaHealthAdj.toInt()}")
            }
        }

        // 7) NEUTRAL HEAVINESS = uncertainty
        if (predictions.size >= 4) {
            val neutralRatio = neutral.size.toDouble() / predictions.size.toDouble()
            val neutralAdj = when {
                neutralRatio >= 0.60 -> -8.0
                neutralRatio >= 0.40 -> -4.0
                else -> 0.0
            }
            score += neutralAdj
            if (neutralAdj != 0.0) {
                factors.add("neutral=${(neutralRatio * 100).toInt()}→${neutralAdj.toInt()}")
            }
        }

        return MetaConfidenceResult(
            confidence = score.coerceIn(0.0, 100.0),
            dominantSignal = dominantSignal,
            factors = factors,
            agreementRatio = agreementRatio,
            weightedBias = weightedBias,
        )
    }

    // -------------------------------------------------------------------------
    // TRUST / QUERY API
    // -------------------------------------------------------------------------

    fun getTrustMultiplier(layer: AILayer): Double {
        return layerPerformance[layer]?.trustMultiplier ?: 1.0
    }

    fun getAllLayerPerformance(): Map<AILayer, LayerPerformance> {
        return layerPerformance.toMap()
    }

    // V5.9.224 — exposed for SentienceOrchestrator harvest
    fun getTotalTradesAnalyzed(): Int = totalTradesAnalyzed

    fun getTopPerformingLayers(n: Int = 5): List<AILayer> {
        return layerPerformance.values
            .filter { it.totalPredictions >= 10 }
            .sortedWith(
                compareByDescending<LayerPerformance> { it.accuracy }
                    .thenByDescending { it.recentAccuracyEwma }
                    .thenBy { it.calibrationError }
            )
            .take(n)
            .map { it.layer }
    }

    fun getUnderperformingLayers(): List<AILayer> {
        return layerPerformance.values
            .filter {
                it.totalPredictions >= 10 &&
                    (it.accuracy < 45.0 || it.recentAccuracyEwma < 42.0 || it.calibrationError > 0.28)
            }
            .map { it.layer }
    }

    fun getLayerDashboard(): List<LayerDashboardItem> {
        return AILayer.values().map { layer ->
            val perf = layerPerformance[layer]
            LayerDashboardItem(
                name = layer.displayName,
                category = layer.category,
                accuracy = perf?.accuracy ?: 50.0,
                predictions = perf?.totalPredictions ?: 0,
                trustMultiplier = perf?.trustMultiplier ?: 1.0,
                recentAccuracy = perf?.recentAccuracyEwma ?: 50.0,
                calibrationError = perf?.calibrationError ?: 0.25,
                profitContribution = perf?.profitContribution ?: 0.0,
                isOverconfident = (perf?.overconfidenceScore ?: 0.0) >= 0.20,
            )
        }.sortedWith(
            compareByDescending<LayerDashboardItem> { it.accuracy }
                .thenByDescending { it.predictions }
        )
    }

    // -------------------------------------------------------------------------
    // VETO / SCORE ADJUSTMENT
    // -------------------------------------------------------------------------

    fun checkVeto(
        predictions: List<Prediction>,
        overallConfidence: Double,
    ): String? {
        if (predictions.isEmpty()) return null
        if (totalTradesAnalyzed < MIN_TRADES_FOR_VETO) return null

        val meta = calculateMetaConfidence(predictions)

        // 1) Critically low meta-confidence with high trade confidence
        if (meta.confidence < 25.0 && overallConfidence >= 60.0) {
            return "META_VETO_LOW_CONFIDENCE"
        }

        // 2) Best layers disagree with dominant direction
        val topLayers = getTopPerformingLayers(4).toSet()
        if (topLayers.isNotEmpty()) {
            val topPreds = predictions.filter { it.layer in topLayers }
            val topBull = topPreds.count { it.signal == SignalType.BULLISH }
            val topBear = topPreds.count { it.signal == SignalType.BEARISH }

            if (meta.dominantSignal == "BULLISH" && topBear > topBull) {
                return "META_VETO_TOP_LAYERS_BEARISH"
            }
            if (meta.dominantSignal == "BEARISH" && topBull > topBear) {
                return "META_VETO_TOP_LAYERS_BULLISH"
            }
        }

        // 3) Known losing pattern
        val sig = ConsensusPattern.buildSignature(predictions)
        val losing = losingPatterns[sig]
        if (losing != null && losing.occurrences >= 5 && losing.winRate <= 25.0) {
            return "META_VETO_KNOWN_LOSER"
        }

        // 4) Too many overconfident weak layers involved
        val offenders = predictions.count { p ->
            val perf = layerPerformance[p.layer]
            p.confidence >= 75.0 &&
                perf != null &&
                perf.totalPredictions >= 8 &&
                (perf.overconfidenceScore >= 0.25 || perf.accuracy < 45.0)
        }
        if (offenders >= 3) {
            return "META_VETO_OVRCONF_WEAK_LAYERS"
        }

        // V5.9.13: Symbolic veto — PANIC mood + high overall risk + weak edge
        try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            if (sc.emotionalState == "PANIC" &&
                sc.overallRisk > 0.75 &&
                sc.edgeStrength < 0.35 &&
                overallConfidence < 75.0) {
                return "META_VETO_SYMBOLIC_PANIC"
            }
        } catch (_: Exception) {}

        return null
    }

    fun adjustScore(rawScore: Int, predictions: List<Prediction>): Int {
        if (predictions.isEmpty()) return rawScore
        if (totalTradesAnalyzed < MIN_TRADES_FOR_META) return rawScore

        val meta = calculateMetaConfidence(predictions)

        val multiplier = when {
            meta.confidence >= 80.0 -> 1.15
            meta.confidence >= 70.0 -> 1.09
            meta.confidence >= 60.0 -> 1.04
            meta.confidence <= 25.0 -> 0.72
            meta.confidence <= 35.0 -> 0.82
            meta.confidence <= 45.0 -> 0.90
            else -> 1.0
        }

        var trustAdjustment = 0.0
        for (pred in predictions) {
            val trust = getTrustMultiplier(pred.layer)
            when (pred.signal) {
                SignalType.BULLISH -> trustAdjustment += (trust - 1.0) * 3.0
                SignalType.BEARISH -> trustAdjustment -= (trust - 1.0) * 3.0
                SignalType.NEUTRAL -> {
                    // no-op
                }
            }
        }

        val adjusted = rawScore * multiplier + trustAdjustment

        // V5.9.13: Symbolic mood nudge on meta-adjusted score
        val symNudge = try {
            val sc = com.lifecyclebot.engine.SymbolicContext
            when (sc.emotionalState) {
                "PANIC"    -> -10.0
                "FEARFUL"  -> -5.0
                "EUPHORIC" -> +5.0
                "GREEDY"   -> +2.5
                else       -> 0.0
            }
        } catch (_: Exception) { 0.0 }

        return (adjusted + symNudge).toInt().coerceIn(-100, 100)
    }

    // -------------------------------------------------------------------------
    // STATUS
    // -------------------------------------------------------------------------

    fun getStatus(): String {
        val top = getTopPerformingLayers(3).joinToString(",") { it.displayName }
        val weak = getUnderperformingLayers().joinToString(",") { it.displayName }

        return buildString {
            append("MetaCognitionAI ")
            append("trades=$totalTradesAnalyzed ")
            append("metaAcc=${metaAccuracy.toInt()} ")
            append("metaCal=${fmt(metaCalibrationError, 2)} ")
            append("top=[$top] ")
            if (weak.isNotBlank()) {
                append("weak=[$weak] ")
            }
            append("patterns=${winningPatterns.size}W/${losingPatterns.size}L")
        }
    }

    // -------------------------------------------------------------------------
    // JSON PERSISTENCE
    // -------------------------------------------------------------------------

    fun saveToJson(): JSONObject {
        val root = JSONObject()

        root.put("totalTradesAnalyzed", totalTradesAnalyzed)
        root.put("metaAccuracy", metaAccuracy)
        root.put("metaCalibrationError", metaCalibrationError)
        root.put("lastMetaUpdate", lastMetaUpdate)

        val perfObj = JSONObject()
        for ((layer, perf) in layerPerformance) {
            perfObj.put(layer.name, JSONObject().apply {
                put("totalPredictions", perf.totalPredictions)
                put("bullishCalls", perf.bullishCalls)
                put("bearishCalls", perf.bearishCalls)
                put("neutralCalls", perf.neutralCalls)
                put("correctPredictions", perf.correctPredictions)
                put("incorrectPredictions", perf.incorrectPredictions)
                put("avgConfidenceOnWins", perf.avgConfidenceOnWins)
                put("avgConfidenceOnLosses", perf.avgConfidenceOnLosses)
                put("recentAccuracyEwma", perf.recentAccuracyEwma)
                put("calibrationError", perf.calibrationError)
                put("overconfidenceScore", perf.overconfidenceScore)
                put("directionalEdge", perf.directionalEdge)
                put("profitContribution", perf.profitContribution)
                put("edgeCases", perf.edgeCases)
                put("lastUpdated", perf.lastUpdated)
            })
        }
        root.put("layerPerformance", perfObj)

        fun patternMapToJson(map: ConcurrentHashMap<String, ConsensusPattern>): JSONArray {
            val arr = JSONArray()
            map.values.sortedByDescending { it.occurrences }.forEach { p ->
                arr.put(JSONObject().apply {
                    put("signature", p.signature)
                    put("bullishLayers", JSONArray(p.bullishLayers.map { it.name }))
                    put("bearishLayers", JSONArray(p.bearishLayers.map { it.name }))
                    put("neutralLayers", JSONArray(p.neutralLayers.map { it.name }))
                    put("occurrences", p.occurrences)
                    put("wins", p.wins)
                    put("losses", p.losses)
                    put("avgPnl", p.avgPnl)
                    put("lastSeen", p.lastSeen)
                })
            }
            return arr
        }

        root.put("winningPatterns", patternMapToJson(winningPatterns))
        root.put("losingPatterns", patternMapToJson(losingPatterns))

        return root
    }

    fun loadFromJson(json: JSONObject) {
        try {
            totalTradesAnalyzed = json.optInt("totalTradesAnalyzed", 0)
            metaAccuracy = json.optDouble("metaAccuracy", 50.0)
            metaCalibrationError = json.optDouble("metaCalibrationError", 0.25)
            lastMetaUpdate = json.optLong("lastMetaUpdate", 0L)

            layerPerformance.clear()
            val perfObj = json.optJSONObject("layerPerformance")
            if (perfObj != null) {
                for (layer in AILayer.values()) {
                    val j = perfObj.optJSONObject(layer.name) ?: continue
                    layerPerformance[layer] = LayerPerformance(
                        layer = layer,
                        totalPredictions = j.optInt("totalPredictions", 0),
                        bullishCalls = j.optInt("bullishCalls", 0),
                        bearishCalls = j.optInt("bearishCalls", 0),
                        neutralCalls = j.optInt("neutralCalls", 0),
                        correctPredictions = j.optInt("correctPredictions", 0),
                        incorrectPredictions = j.optInt("incorrectPredictions", 0),
                        avgConfidenceOnWins = j.optDouble("avgConfidenceOnWins", 50.0),
                        avgConfidenceOnLosses = j.optDouble("avgConfidenceOnLosses", 50.0),
                        recentAccuracyEwma = j.optDouble("recentAccuracyEwma", 50.0),
                        calibrationError = j.optDouble("calibrationError", 0.25),
                        overconfidenceScore = j.optDouble("overconfidenceScore", 0.0),
                        directionalEdge = j.optDouble("directionalEdge", 0.0),
                        profitContribution = j.optDouble("profitContribution", 0.0),
                        edgeCases = j.optInt("edgeCases", 0),
                        lastUpdated = j.optLong("lastUpdated", 0L),
                    )
                }
            }

            winningPatterns.clear()
            losingPatterns.clear()

            fun loadPatterns(arr: JSONArray?, target: ConcurrentHashMap<String, ConsensusPattern>) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    val signature = j.optString("signature", "")
                    if (signature.isBlank()) continue

                    target[signature] = ConsensusPattern(
                        signature = signature,
                        bullishLayers = jsonArrayToLayerSet(j.optJSONArray("bullishLayers")),
                        bearishLayers = jsonArrayToLayerSet(j.optJSONArray("bearishLayers")),
                        neutralLayers = jsonArrayToLayerSet(j.optJSONArray("neutralLayers")),
                        occurrences = j.optInt("occurrences", 0),
                        wins = j.optInt("wins", 0),
                        losses = j.optInt("losses", 0),
                        avgPnl = j.optDouble("avgPnl", 0.0),
                        lastSeen = j.optLong("lastSeen", 0L),
                    )
                }
            }

            loadPatterns(json.optJSONArray("winningPatterns"), winningPatterns)
            loadPatterns(json.optJSONArray("losingPatterns"), losingPatterns)

            Log.i(TAG, "Loaded MetaCognitionAI state: trades=$totalTradesAnalyzed layers=${layerPerformance.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load MetaCognitionAI: ${t.message}")
        }
    }

    private fun jsonArrayToLayerSet(arr: JSONArray?): Set<AILayer> {
        if (arr == null) return emptySet()
        val out = mutableSetOf<AILayer>()
        for (i in 0 until arr.length()) {
            val name = arr.optString(i, "")
            try {
                if (name.isNotBlank()) out.add(AILayer.valueOf(name))
            } catch (_: Throwable) {
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // RESET
    // -------------------------------------------------------------------------

    fun reset() {
        layerPerformance.clear()
        pendingPredictions.clear()
        recentOutcomes.clear()
        winningPatterns.clear()
        losingPatterns.clear()
        totalTradesAnalyzed = 0
        metaAccuracy = 50.0
        metaCalibrationError = 0.25
        lastMetaUpdate = 0L
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private fun ewma(current: Double, value: Double, alpha: Double): Double {
        return current * (1.0 - alpha) + value * alpha
    }

    private fun runningAverage(currentAvg: Double, currentCount: Int, newValue: Double): Double {
        return ((currentAvg * currentCount) + newValue) / (currentCount + 1).toDouble()
    }

    private fun fmt(v: Double, decimals: Int): String {
        return "%.${decimals}f".format(v)
    }
}