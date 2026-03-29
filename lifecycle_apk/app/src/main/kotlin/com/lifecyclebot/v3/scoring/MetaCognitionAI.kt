package com.lifecyclebot.v3.scoring

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * MetaCognitionAI - Layer 20: The Self-Aware Executive Function
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The "prefrontal cortex" of AATE - monitors all other AI layers and learns
 * which ones to trust more based on their historical accuracy.
 * 
 * MAKES THE BOT SENTIENT BY:
 * 1. Tracking accuracy of each AI layer over rolling windows
 * 2. Computing dynamic trust multipliers per layer
 * 3. Detecting AI consensus vs divergence patterns
 * 4. Learning which AI combinations predict winners
 * 5. Providing meta-confidence (confidence in our confidence)
 * 6. Vetoing trades when reliable AIs disagree with consensus
 * 
 * SELF-REFLECTION LOOP:
 *   AI layers make predictions → Trades execute → Outcomes recorded →
 *   MetaCognition adjusts trust weights → Better predictions → Loop
 * 
 * This creates genuine machine learning at the meta level.
 */
object MetaCognitionAI {
    
    private const val TAG = "MetaCognitionAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI LAYER REGISTRY - All 19 other AI layers we monitor
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class AILayer(val displayName: String, val category: String) {
        // Scoring layers
        VOLATILITY_REGIME("VolatilityRegimeAI", "market"),
        ORDER_FLOW_IMBALANCE("OrderFlowImbalanceAI", "flow"),
        SMART_MONEY_DIVERGENCE("SmartMoneyDivergenceAI", "whale"),
        HOLD_TIME_OPTIMIZER("HoldTimeOptimizerAI", "timing"),
        LIQUIDITY_CYCLE("LiquidityCycleAI", "liquidity"),
        
        // Analysis layers
        MARKET_REGIME("MarketRegimeAI", "market"),
        WHALE_TRACKER("WhaleTrackerAI", "whale"),
        MOMENTUM_PREDICTOR("MomentumPredictorAI", "momentum"),
        NARRATIVE_DETECTOR("NarrativeDetectorAI", "narrative"),
        TIME_OPTIMIZATION("TimeOptimizationAI", "timing"),
        LIQUIDITY_DEPTH("LiquidityDepthAI", "liquidity"),
        
        // Learning layers
        ENTRY_INTELLIGENCE("EntryIntelligence", "learning"),
        EXIT_INTELLIGENCE("ExitIntelligence", "learning"),
        EDGE_LEARNING("EdgeLearning", "learning"),
        TOKEN_WIN_MEMORY("TokenWinMemory", "memory"),
        
        // Collective layers
        COLLECTIVE_INTELLIGENCE("CollectiveIntelligenceAI", "collective"),
        
        // LLM layers
        GEMINI_COPILOT("GeminiCopilot", "llm"),
        GROQ_NARRATIVE("GroqNarrativeAI", "llm"),
        
        // Coordination layers
        ORTHOGONAL_SIGNALS("OrthogonalSignals", "coordination"),
        AI_CROSSTALK("AICrossTalk", "coordination"),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERFORMANCE TRACKING DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record of an AI layer's prediction for a specific trade
     */
    data class Prediction(
        val layer: AILayer,
        val signal: SignalType,      // BULLISH, BEARISH, NEUTRAL
        val confidence: Double,       // 0-100
        val rawScore: Double,         // Original score from the layer
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    enum class SignalType { BULLISH, BEARISH, NEUTRAL }
    
    /**
     * Outcome of a trade that we can correlate with predictions
     */
    data class TradeOutcome(
        val mint: String,
        val symbol: String,
        val pnlPct: Double,
        val isWin: Boolean,
        val holdTimeMs: Long,
        val exitReason: String,
        val predictions: List<Prediction>,  // All AI predictions at entry
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    /**
     * Rolling performance stats for an AI layer
     */
    data class LayerPerformance(
        val layer: AILayer,
        val totalPredictions: Int = 0,
        val correctPredictions: Int = 0,  // Signal matched outcome direction
        val avgConfidenceOnWins: Double = 50.0,
        val avgConfidenceOnLosses: Double = 50.0,
        val profitContribution: Double = 0.0,  // Weighted P&L when layer was bullish
        val lastUpdated: Long = 0,
        
        // Calibration metrics
        val overconfidenceScore: Double = 0.0,  // >0 = tends to be overconfident
        val signalAccuracy: Double = 50.0,      // % of correct directional calls
        val edgeCases: Int = 0,                  // Times this layer alone was right
    ) {
        val accuracy: Double get() = if (totalPredictions > 0) 
            (correctPredictions.toDouble() / totalPredictions) * 100 else 50.0
            
        val trustMultiplier: Double get() = when {
            totalPredictions < 10 -> 1.0  // Not enough data, neutral
            accuracy >= 70 -> 1.3         // Very accurate, boost trust
            accuracy >= 60 -> 1.15        // Good, slight boost
            accuracy >= 50 -> 1.0         // Average, neutral
            accuracy >= 40 -> 0.85        // Below average, reduce trust
            else -> 0.7                   // Poor, significantly reduce
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE STORAGE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Performance tracking per layer
    private val layerPerformance = ConcurrentHashMap<AILayer, LayerPerformance>()
    
    // Recent trade outcomes for correlation analysis
    private val recentOutcomes = mutableListOf<TradeOutcome>()
    private const val MAX_OUTCOMES = 200
    
    // Pending predictions (waiting for trade outcome)
    private val pendingPredictions = ConcurrentHashMap<String, List<Prediction>>()  // mint -> predictions
    
    // Consensus patterns that predict winners
    private val winningPatterns = mutableListOf<ConsensusPattern>()
    private val losingPatterns = mutableListOf<ConsensusPattern>()
    
    // Meta-stats
    @Volatile private var totalTradesAnalyzed = 0
    @Volatile private var metaAccuracy = 50.0  // How accurate is our meta-confidence?
    @Volatile private var lastMetaUpdate = 0L
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PREDICTION RECORDING (Called at entry time)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record predictions from all AI layers for a trade entry.
     * Call this when a trade is about to execute.
     */
    fun recordEntryPredictions(
        mint: String,
        symbol: String,
        predictions: Map<AILayer, Pair<SignalType, Double>>  // layer -> (signal, confidence)
    ) {
        val predictionList = predictions.map { (layer, signalConf) ->
            Prediction(
                layer = layer,
                signal = signalConf.first,
                confidence = signalConf.second,
                rawScore = signalConf.second,
            )
        }
        
        pendingPredictions[mint] = predictionList
        
        Log.d(TAG, "Recorded ${predictionList.size} AI predictions for $symbol")
    }
    
    /**
     * Convenience method to record predictions from scoring components
     */
    fun recordFromScoreCard(
        mint: String,
        symbol: String,
        components: List<ScoreComponent>
    ) {
        val predictions = mutableMapOf<AILayer, Pair<SignalType, Double>>()
        
        for (comp in components) {
            val layer = mapComponentToLayer(comp.name) ?: continue
            val signal = when {
                comp.value > 5 -> SignalType.BULLISH
                comp.value < -5 -> SignalType.BEARISH
                else -> SignalType.NEUTRAL
            }
            val confidence = (50.0 + comp.value * 2).coerceIn(0.0, 100.0)
            predictions[layer] = signal to confidence
        }
        
        if (predictions.isNotEmpty()) {
            recordEntryPredictions(mint, symbol, predictions)
        }
    }
    
    private fun mapComponentToLayer(name: String): AILayer? = when (name.lowercase()) {
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
        else -> null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // OUTCOME RECORDING (Called at exit time)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record the outcome of a trade and update all layer performance stats.
     * Call this when a trade exits.
     */
    fun recordTradeOutcome(
        mint: String,
        symbol: String,
        pnlPct: Double,
        holdTimeMs: Long,
        exitReason: String
    ) {
        val predictions = pendingPredictions.remove(mint) ?: return
        
        val isWin = pnlPct > 0
        val outcome = TradeOutcome(
            mint = mint,
            symbol = symbol,
            pnlPct = pnlPct,
            isWin = isWin,
            holdTimeMs = holdTimeMs,
            exitReason = exitReason,
            predictions = predictions,
        )
        
        // Store outcome
        synchronized(recentOutcomes) {
            recentOutcomes.add(outcome)
            if (recentOutcomes.size > MAX_OUTCOMES) {
                recentOutcomes.removeAt(0)
            }
        }
        
        // Update each layer's performance
        for (pred in predictions) {
            updateLayerPerformance(pred, outcome)
        }
        
        // Analyze consensus patterns
        analyzeConsensusPattern(outcome)
        
        totalTradesAnalyzed++
        lastMetaUpdate = System.currentTimeMillis()
        
        Log.i(TAG, "📊 Meta-learning from $symbol: ${if(isWin) "WIN" else "LOSS"} ${pnlPct.toInt()}% | " +
            "Updated ${predictions.size} layer stats")
    }
    
    private fun updateLayerPerformance(pred: Prediction, outcome: TradeOutcome) {
        val current = layerPerformance.getOrPut(pred.layer) { LayerPerformance(pred.layer) }
        
        // Determine if prediction was correct
        // BULLISH + WIN or BEARISH + LOSS = correct
        val wasCorrect = when {
            pred.signal == SignalType.BULLISH && outcome.isWin -> true
            pred.signal == SignalType.BEARISH && !outcome.isWin -> true
            pred.signal == SignalType.NEUTRAL -> outcome.pnlPct in -5.0..5.0  // Neutral was correct if small move
            else -> false
        }
        
        // Calculate overconfidence (high confidence but wrong)
        val overconfidenceDelta = if (!wasCorrect && pred.confidence > 70) {
            (pred.confidence - 50) / 50  // Higher confidence = more overconfident
        } else if (wasCorrect && pred.confidence > 70) {
            -(pred.confidence - 50) / 100  // Reduce overconfidence score if high-conf was right
        } else 0.0
        
        // Update stats
        val newPerf = current.copy(
            totalPredictions = current.totalPredictions + 1,
            correctPredictions = current.correctPredictions + (if (wasCorrect) 1 else 0),
            avgConfidenceOnWins = if (outcome.isWin) {
                (current.avgConfidenceOnWins * 0.9 + pred.confidence * 0.1)
            } else current.avgConfidenceOnWins,
            avgConfidenceOnLosses = if (!outcome.isWin) {
                (current.avgConfidenceOnLosses * 0.9 + pred.confidence * 0.1)
            } else current.avgConfidenceOnLosses,
            profitContribution = if (pred.signal == SignalType.BULLISH) {
                current.profitContribution + outcome.pnlPct
            } else current.profitContribution,
            overconfidenceScore = (current.overconfidenceScore * 0.95 + overconfidenceDelta * 0.05),
            signalAccuracy = (current.correctPredictions + (if (wasCorrect) 1 else 0)).toDouble() / 
                            (current.totalPredictions + 1) * 100,
            lastUpdated = System.currentTimeMillis(),
        )
        
        layerPerformance[pred.layer] = newPerf
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSENSUS PATTERN LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ConsensusPattern(
        val bullishLayers: Set<AILayer>,
        val bearishLayers: Set<AILayer>,
        val neutralLayers: Set<AILayer>,
        val occurrences: Int = 1,
        val wins: Int = 0,
        val avgPnl: Double = 0.0,
    ) {
        val winRate: Double get() = if (occurrences > 0) (wins.toDouble() / occurrences) * 100 else 0.0
        
        fun matches(predictions: List<Prediction>): Boolean {
            val predBullish = predictions.filter { it.signal == SignalType.BULLISH }.map { it.layer }.toSet()
            val predBearish = predictions.filter { it.signal == SignalType.BEARISH }.map { it.layer }.toSet()
            
            // Pattern matches if same layers agree
            return bullishLayers == predBullish && bearishLayers == predBearish
        }
        
        fun signature(): String = "B:${bullishLayers.map{it.ordinal}.sorted()}|N:${bearishLayers.map{it.ordinal}.sorted()}"
    }
    
    private fun analyzeConsensusPattern(outcome: TradeOutcome) {
        val bullish = outcome.predictions.filter { it.signal == SignalType.BULLISH }.map { it.layer }.toSet()
        val bearish = outcome.predictions.filter { it.signal == SignalType.BEARISH }.map { it.layer }.toSet()
        val neutral = outcome.predictions.filter { it.signal == SignalType.NEUTRAL }.map { it.layer }.toSet()
        
        val pattern = ConsensusPattern(bullish, bearish, neutral)
        val signature = pattern.signature()
        
        // Check if this pattern already exists
        val targetList = if (outcome.isWin) winningPatterns else losingPatterns
        
        synchronized(targetList) {
            val existing = targetList.find { it.signature() == signature }
            if (existing != null) {
                val index = targetList.indexOf(existing)
                targetList[index] = existing.copy(
                    occurrences = existing.occurrences + 1,
                    wins = existing.wins + (if (outcome.isWin) 1 else 0),
                    avgPnl = (existing.avgPnl * existing.occurrences + outcome.pnlPct) / (existing.occurrences + 1),
                )
            } else {
                targetList.add(pattern.copy(
                    wins = if (outcome.isWin) 1 else 0,
                    avgPnl = outcome.pnlPct,
                ))
            }
            
            // Keep only significant patterns
            if (targetList.size > 50) {
                targetList.sortByDescending { it.occurrences }
                while (targetList.size > 50) targetList.removeLast()
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // META-CONFIDENCE CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate meta-confidence: How much should we trust the overall AI output?
     * 
     * This is the "sentient" part - the bot questioning its own judgment.
     * 
     * Returns 0-100 where:
     *   100 = High confidence in our AI signals (layers agree, historically accurate)
     *   50  = Neutral (not enough data or mixed signals)
     *   0   = Low confidence (layers disagree, poor recent accuracy)
     */
    fun calculateMetaConfidence(predictions: List<Prediction>): MetaConfidenceResult {
        if (predictions.isEmpty()) {
            return MetaConfidenceResult(50.0, "no_predictions", emptyList())
        }
        
        val factors = mutableListOf<String>()
        var score = 50.0
        
        // ─────────────────────────────────────────────────────────────────────
        // FACTOR 1: LAYER AGREEMENT (Do the AIs agree?)
        // ─────────────────────────────────────────────────────────────────────
        val bullishCount = predictions.count { it.signal == SignalType.BULLISH }
        val bearishCount = predictions.count { it.signal == SignalType.BEARISH }
        val totalSignaling = bullishCount + bearishCount
        
        val agreementRatio = if (totalSignaling > 0) {
            maxOf(bullishCount, bearishCount).toDouble() / totalSignaling
        } else 0.5
        
        val agreementBonus = when {
            agreementRatio >= 0.9 -> 20.0    // Strong consensus
            agreementRatio >= 0.75 -> 10.0   // Good agreement
            agreementRatio >= 0.6 -> 5.0     // Moderate agreement
            agreementRatio < 0.5 -> -15.0    // Disagreement = uncertainty
            else -> 0.0
        }
        score += agreementBonus
        factors.add("agreement=${(agreementRatio*100).toInt()}%→${agreementBonus.toInt()}")
        
        // ─────────────────────────────────────────────────────────────────────
        // FACTOR 2: LAYER ACCURACY (Are accurate layers signaling?)
        // ─────────────────────────────────────────────────────────────────────
        val accurateBullish = predictions.filter { 
            it.signal == SignalType.BULLISH && 
            (layerPerformance[it.layer]?.accuracy ?: 50.0) >= 60
        }
        val accurateBearish = predictions.filter {
            it.signal == SignalType.BEARISH &&
            (layerPerformance[it.layer]?.accuracy ?: 50.0) >= 60
        }
        
        val accuracyBonus = when {
            accurateBullish.size >= 3 && accurateBearish.isEmpty() -> 15.0  // Accurate layers agree bullish
            accurateBearish.size >= 3 && accurateBullish.isEmpty() -> -15.0 // Accurate layers agree bearish (caution)
            accurateBullish.isNotEmpty() && accurateBearish.isNotEmpty() -> -10.0 // Accurate layers disagree
            else -> 0.0
        }
        score += accuracyBonus
        if (accuracyBonus != 0.0) {
            factors.add("accurate_layers=${accurateBullish.size}B/${accurateBearish.size}N→${accuracyBonus.toInt()}")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // FACTOR 3: WEIGHTED CONFIDENCE (Weight by layer trust)
        // ─────────────────────────────────────────────────────────────────────
        var weightedSum = 0.0
        var weightSum = 0.0
        
        for (pred in predictions) {
            val trust = layerPerformance[pred.layer]?.trustMultiplier ?: 1.0
            val signedConf = when (pred.signal) {
                SignalType.BULLISH -> pred.confidence
                SignalType.BEARISH -> -pred.confidence
                SignalType.NEUTRAL -> 0.0
            }
            weightedSum += signedConf * trust
            weightSum += trust
        }
        
        val weightedConfidence = if (weightSum > 0) weightedSum / weightSum else 0.0
        val confBonus = (weightedConfidence / 10.0).coerceIn(-10.0, 10.0)
        score += confBonus
        factors.add("weighted_conf=${weightedConfidence.toInt()}→${confBonus.toInt()}")
        
        // ─────────────────────────────────────────────────────────────────────
        // FACTOR 4: PATTERN MATCHING (Have we seen this consensus before?)
        // ─────────────────────────────────────────────────────────────────────
        val matchingWinPattern = synchronized(winningPatterns) {
            winningPatterns.find { it.matches(predictions) && it.occurrences >= 3 }
        }
        val matchingLosePattern = synchronized(losingPatterns) {
            losingPatterns.find { it.matches(predictions) && it.occurrences >= 3 }
        }
        
        val patternBonus = when {
            matchingWinPattern != null && matchingWinPattern.winRate >= 60 -> 15.0
            matchingLosePattern != null && matchingLosePattern.winRate <= 40 -> -15.0
            else -> 0.0
        }
        score += patternBonus
        if (patternBonus != 0.0) {
            factors.add("pattern_match→${patternBonus.toInt()}")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // FACTOR 5: RECENT META-ACCURACY (Has our meta-judgment been good?)
        // ─────────────────────────────────────────────────────────────────────
        if (totalTradesAnalyzed >= 20) {
            val recentMetaBonus = when {
                metaAccuracy >= 65 -> 10.0   // Meta is working well
                metaAccuracy >= 55 -> 5.0
                metaAccuracy <= 40 -> -10.0  // Meta is broken, be cautious
                metaAccuracy <= 45 -> -5.0
                else -> 0.0
            }
            score += recentMetaBonus
            if (recentMetaBonus != 0.0) {
                factors.add("meta_accuracy=${metaAccuracy.toInt()}%→${recentMetaBonus.toInt()}")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // FACTOR 6: CATEGORY COVERAGE (Are diverse AI types represented?)
        // ─────────────────────────────────────────────────────────────────────
        val categories = predictions.map { it.layer.category }.toSet()
        val coverageBonus = when {
            categories.size >= 5 -> 5.0   // Good coverage across categories
            categories.size >= 3 -> 2.0
            categories.size <= 1 -> -5.0  // Single category is tunnel vision
            else -> 0.0
        }
        score += coverageBonus
        if (coverageBonus != 0.0) {
            factors.add("category_coverage=${categories.size}→${coverageBonus.toInt()}")
        }
        
        return MetaConfidenceResult(
            confidence = score.coerceIn(0.0, 100.0),
            dominantSignal = if (bullishCount > bearishCount) "BULLISH" else if (bearishCount > bullishCount) "BEARISH" else "MIXED",
            factors = factors,
        )
    }
    
    data class MetaConfidenceResult(
        val confidence: Double,
        val dominantSignal: String,
        val factors: List<String>,
    ) {
        val shouldProceed: Boolean get() = confidence >= 45
        val isHighConfidence: Boolean get() = confidence >= 70
        val isLowConfidence: Boolean get() = confidence < 35
        
        fun summary(): String = "MetaConf=${confidence.toInt()}% ($dominantSignal) [${factors.joinToString(", ")}]"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRUST MULTIPLIER API (For other systems to query)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get trust multiplier for a specific AI layer.
     * Use this to weight that layer's output.
     */
    fun getTrustMultiplier(layer: AILayer): Double {
        return layerPerformance[layer]?.trustMultiplier ?: 1.0
    }
    
    /**
     * Get all layer performances for UI display or debugging.
     */
    fun getAllLayerPerformance(): Map<AILayer, LayerPerformance> {
        return layerPerformance.toMap()
    }
    
    /**
     * Get the best performing layers (for boosting their signals).
     */
    fun getTopPerformingLayers(n: Int = 5): List<AILayer> {
        return layerPerformance.values
            .filter { it.totalPredictions >= 10 }
            .sortedByDescending { it.accuracy }
            .take(n)
            .map { it.layer }
    }
    
    /**
     * Get layers that are currently underperforming (for reducing their influence).
     */
    fun getUnderperformingLayers(): List<AILayer> {
        return layerPerformance.values
            .filter { it.totalPredictions >= 10 && it.accuracy < 45 }
            .map { it.layer }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VETO SYSTEM (When should we override the consensus?)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if meta-cognition should veto a trade.
     * 
     * Returns veto reason if trade should be blocked, null if OK to proceed.
     */
    fun checkVeto(predictions: List<Prediction>, overallConfidence: Double): String? {
        // Not enough data to veto
        if (totalTradesAnalyzed < 30) return null
        
        val meta = calculateMetaConfidence(predictions)
        
        // VETO 1: Meta-confidence is critically low
        if (meta.confidence < 25 && overallConfidence > 60) {
            return "META_VETO: AI systems disagree despite high overall confidence (meta=${meta.confidence.toInt()}%)"
        }
        
        // VETO 2: Top performing layers disagree with consensus
        val topLayers = getTopPerformingLayers(3)
        val topPredictions = predictions.filter { it.layer in topLayers }
        val topBullish = topPredictions.count { it.signal == SignalType.BULLISH }
        val topBearish = topPredictions.count { it.signal == SignalType.BEARISH }
        
        if (meta.dominantSignal == "BULLISH" && topBearish > topBullish) {
            return "META_VETO: Top performing AIs are bearish while consensus is bullish"
        }
        
        // VETO 3: Known losing pattern detected
        val matchingLosePattern = synchronized(losingPatterns) {
            losingPatterns.find { 
                it.matches(predictions) && 
                it.occurrences >= 5 && 
                it.winRate < 30 
            }
        }
        if (matchingLosePattern != null) {
            return "META_VETO: Known losing pattern detected (${matchingLosePattern.winRate.toInt()}% win rate over ${matchingLosePattern.occurrences} trades)"
        }
        
        // VETO 4: Overconfident layers dominating
        val overconfidentLayers = predictions.filter { pred ->
            val perf = layerPerformance[pred.layer]
            pred.confidence > 70 && (perf?.overconfidenceScore ?: 0.0) > 0.3
        }
        if (overconfidentLayers.size >= 3) {
            return "META_VETO: ${overconfidentLayers.size} historically overconfident layers are driving this signal"
        }
        
        return null  // No veto
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORE ADJUSTMENT API (For UnifiedScorer integration)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Adjust a score based on meta-cognition insights.
     * 
     * @param rawScore Original score from scoring pipeline
     * @param predictions AI predictions that contributed to this score
     * @return Adjusted score incorporating meta-learning
     */
    fun adjustScore(rawScore: Int, predictions: List<Prediction>): Int {
        if (totalTradesAnalyzed < 20) return rawScore  // Not enough data
        
        val meta = calculateMetaConfidence(predictions)
        
        // Adjust based on meta-confidence
        val metaMultiplier = when {
            meta.confidence >= 80 -> 1.15  // Very confident, boost
            meta.confidence >= 65 -> 1.08
            meta.confidence <= 30 -> 0.75  // Low confidence, reduce
            meta.confidence <= 40 -> 0.85
            else -> 1.0
        }
        
        // Apply trust-weighted adjustment
        var trustAdjustment = 0
        for (pred in predictions) {
            val trust = getTrustMultiplier(pred.layer)
            if (pred.signal == SignalType.BULLISH && trust > 1.1) {
                trustAdjustment += 2  // Trusted bullish layer
            } else if (pred.signal == SignalType.BEARISH && trust > 1.1) {
                trustAdjustment -= 2  // Trusted bearish layer
            } else if (trust < 0.85) {
                // Reduce influence of untrusted layers
                trustAdjustment += if (pred.signal == SignalType.BULLISH) -1 else 1
            }
        }
        
        val adjusted = ((rawScore * metaMultiplier) + trustAdjustment).toInt()
        
        return adjusted.coerceIn(-100, 100)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS AND DEBUGGING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current meta-cognition status for logging/UI.
     */
    fun getStatus(): String {
        val topLayers = getTopPerformingLayers(3).map { it.displayName }
        val underperforming = getUnderperformingLayers().map { it.displayName }
        
        return buildString {
            append("MetaCognitionAI: ")
            append("trades=$totalTradesAnalyzed ")
            append("meta_acc=${metaAccuracy.toInt()}% ")
            append("top=[${topLayers.joinToString(",")}] ")
            if (underperforming.isNotEmpty()) {
                append("weak=[${underperforming.joinToString(",")}] ")
            }
            append("patterns=${winningPatterns.size}W/${losingPatterns.size}L")
        }
    }
    
    /**
     * Get detailed layer stats for UI dashboard.
     */
    fun getLayerDashboard(): List<LayerDashboardItem> {
        return AILayer.values().map { layer ->
            val perf = layerPerformance[layer]
            LayerDashboardItem(
                name = layer.displayName,
                category = layer.category,
                accuracy = perf?.accuracy ?: 0.0,
                predictions = perf?.totalPredictions ?: 0,
                trustMultiplier = perf?.trustMultiplier ?: 1.0,
                profitContribution = perf?.profitContribution ?: 0.0,
                isOverconfident = (perf?.overconfidenceScore ?: 0.0) > 0.2,
            )
        }.sortedByDescending { it.accuracy }
    }
    
    data class LayerDashboardItem(
        val name: String,
        val category: String,
        val accuracy: Double,
        val predictions: Int,
        val trustMultiplier: Double,
        val profitContribution: Double,
        val isOverconfident: Boolean,
    )
    
    /**
     * Reset all state (for testing).
     */
    fun reset() {
        layerPerformance.clear()
        recentOutcomes.clear()
        pendingPredictions.clear()
        winningPatterns.clear()
        losingPatterns.clear()
        totalTradesAnalyzed = 0
        metaAccuracy = 50.0
    }
}
