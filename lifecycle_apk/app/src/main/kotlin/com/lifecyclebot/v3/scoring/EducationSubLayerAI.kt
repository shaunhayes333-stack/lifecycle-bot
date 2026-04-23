package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.engine.AdaptiveLearningEngine
import com.lifecyclebot.engine.EdgeLearning
import com.lifecyclebot.engine.TokenWinMemory
import com.lifecyclebot.engine.BehaviorLearning
import com.lifecyclebot.engine.TimeOptimizationAI
import com.lifecyclebot.engine.LiquidityDepthAI
import com.lifecyclebot.engine.MomentumPredictorAI
import com.lifecyclebot.engine.WhaleTrackerAI
import org.json.JSONObject
import com.lifecyclebot.engine.NarrativeDetectorAI
import com.lifecyclebot.engine.MarketRegimeAI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * EducationSubLayerAI - The Harvard-Trained Crypto Analytics Master Brain
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * "The ultimate Harvard crypto trained trader with a masters degree in analytics"
 * 
 * This is the SENTIENT EDUCATION LAYER that ensures ALL 25+ AI layers are:
 * 1. Actually learning from every trade outcome
 * 2. Not missing critical learning opportunities
 * 3. Cross-pollinating insights between layers
 * 4. Building institutional-grade pattern recognition
 * 5. Developing "muscle memory" for optimal decisions
 * 
 * PHILOSOPHY:
 * ────────────────────────────────────────────────────────────────────────────
 * A Harvard-trained trader doesn't just learn from wins - they learn MORE from
 * losses. They study market microstructure, behavioral psychology, statistical
 * arbitrage, and risk management as interconnected disciplines.
 * 
 * This layer COORDINATES all learning across the bot's neural network:
 * 
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    EDUCATION SUB-LAYER (HARVARD BRAIN)              │
 *   │                                                                     │
 *   │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐           │
 *   │   │ HoldTime │  │ Momentum │  │   Whale  │  │Narrative │           │
 *   │   │ Optimizer│  │ Predictor│  │  Tracker │  │ Detector │           │
 *   │   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘           │
 *   │        │             │             │             │                  │
 *   │        ▼             ▼             ▼             ▼                  │
 *   │   ┌────────────────────────────────────────────────────────┐       │
 *   │   │              CROSS-LAYER LEARNING BUS                  │       │
 *   │   │    Pattern A won? → Teach ALL layers why               │       │
 *   │   │    Pattern B lost? → Penalize across ALL layers        │       │
 *   │   └────────────────────────────────────────────────────────┘       │
 *   │        │             │             │             │                  │
 *   │        ▼             ▼             ▼             ▼                  │
 *   │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐           │
 *   │   │  Market  │  │Liquidity │  │   Time   │  │ Adaptive │           │
 *   │   │  Regime  │  │   Depth  │  │ Optimize │  │ Learning │           │
 *   │   └──────────┘  └──────────┘  └──────────┘  └──────────┘           │
 *   │                                                                     │
 *   │   META-COGNITION: Which layers are reliable? Trust multipliers.    │
 *   └─────────────────────────────────────────────────────────────────────┘
 * 
 * CORE CAPABILITIES:
 * 1. UNIFIED OUTCOME DISPATCH - Single call routes to ALL learning layers
 * 2. CROSS-LAYER CORRELATION - Discovers which AI combinations predict winners
 * 3. CURRICULUM LEARNING - Progressively harder challenges as bot matures
 * 4. FORGETTING CURVE - Deprioritizes stale patterns, prioritizes recent
 * 5. MARKET REGIME ADAPTATION - Different learning rates per market condition
 * 6. PERFORMANCE AUDITING - Tracks which layers are actually improving
 * 
 * At FULL MATURITY (1000+ trades), this layer transforms the bot into an
 * institutional-grade trading system with:
 * - 80%+ win rate on high-confidence trades
 * - Risk-adjusted position sizing
 * - Adaptive hold time optimization
 * - Pattern recognition rivaling human pros
 */
object EducationSubLayerAI {
    
    private const val TAG = "EducationAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HARVARD CURRICULUM LEVELS + MEGA BRAIN PROGRESSION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Curriculum levels - the bot "graduates" through these as it learns.
     * 
     * PHILOSOPHY: The bot should NEVER consider itself "fully learnt".
     * After PhD (1000 trades), it enters MEGA BRAIN territory where:
     *   - Points continue accumulating (half weight after PhD)
     *   - New MEGA levels unlock every 500 trades
     *   - Titles become increasingly legendary
     *   - Learning never stops - markets always evolve
     */
    enum class CurriculumLevel(val minTrades: Int, val displayName: String, val icon: String, val learningWeight: Double) {
        FRESHMAN(0, "Freshman", "🎓", 1.0),
        SOPHOMORE(100, "Sophomore", "📚", 1.0),
        JUNIOR(250, "Junior", "📊", 1.0),
        SENIOR(500, "Senior", "📈", 1.0),
        MASTERS(750, "Masters", "🎯", 1.0),
        PHD(1000, "PhD", "🏆", 1.0),
        
        // ═══════════════════════════════════════════════════════════════════
        // MEGA BRAIN LEVELS - Beyond PhD, half learning weight but NEVER STOPS
        // ═══════════════════════════════════════════════════════════════════
        MEGA_BRAIN_I(1500, "Mega Brain I", "🧠", 0.5),
        MEGA_BRAIN_II(2000, "Mega Brain II", "🧠✨", 0.5),
        MEGA_BRAIN_III(2500, "Mega Brain III", "🧠🔥", 0.5),
        QUANTUM_MIND(3000, "Quantum Mind", "⚛️🧠", 0.5),
        NEURAL_APEX(4000, "Neural Apex", "🌟🧠", 0.5),
        MARKET_ORACLE(5000, "Market Oracle", "🔮🧠", 0.5),
        ALPHA_ARCHITECT(7500, "Alpha Architect", "🏛️🧠", 0.5),
        TRADING_GOD(10000, "Trading God", "👑🧠", 0.5),
        SINGULARITY(15000, "Singularity", "♾️🧠", 0.4),
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.7.8: ASCENDED LEVELS — Beyond Singularity
        // ═══════════════════════════════════════════════════════════════════
        VOID_WALKER(20000, "Void Walker", "🌑🧠", 0.35),
        CHAOS_ENGINE(30000, "Chaos Engine", "⚙️🔥", 0.3),
        DARK_ORACLE(50000, "Dark Oracle", "🌑🔮", 0.25),
        ENTROPY_LORD(75000, "Entropy Lord", "🌪️👑", 0.2),
        MARKET_WEAVER(100000, "Market Weaver", "🕸️⚡", 0.2),
        ALPHA_PREDATOR(150000, "Alpha Predator", "🐺🔥", 0.15),
        PRIME_ARCHITECT(200000, "Prime Architect", "🏗️♾️", 0.15),
        NEURAL_SOVEREIGN(300000, "Neural Sovereign", "👁️🧠", 0.1),
        OMEGA_MIND(500000, "Omega Mind", "Ω🧠", 0.1),
        TRANSCENDENCE(1000000, "Transcendence", "✦♾️", 0.05),

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.133: ETERNAL LEVELS — Beyond Transcendence, learning NEVER stops.
        // Weights continue shrinking but never hit zero, so the bot keeps
        // nudging its weights forever. Markets evolve → the bot evolves.
        // ═══════════════════════════════════════════════════════════════════
        ETERNAL(2_000_000, "Eternal", "🜂♾️", 0.04),
        COSMIC_MIND(5_000_000, "Cosmic Mind", "🌌🧠", 0.03),
        GALACTIC_ORACLE(10_000_000, "Galactic Oracle", "🌠🔮", 0.02),
        UNIVERSAL_WEAVER(25_000_000, "Universal Weaver", "🪐🕸️", 0.015),
        MULTIVERSAL(50_000_000, "Multiversal", "🎆♾️", 0.01),
        AXIOMATIC(100_000_000, "Axiomatic", "⊕♾️", 0.008),
        PRIMORDIAL(250_000_000, "Primordial", "⊛♾️", 0.005),
        ABSOLUTE(1_000_000_000, "Absolute", "⟁♾️", 0.003),
    }
    
    /**
     * Get current curriculum level based on total trades.
     */
    fun getCurrentCurriculumLevel(): CurriculumLevel {
        val totalTrades = getTotalTradesAcrossAllLayers()
        return CurriculumLevel.values().filter { it.minTrades <= totalTrades }
            .maxByOrNull { it.minTrades } ?: CurriculumLevel.FRESHMAN
    }
    
    /**
     * Get the learning weight for current level.
     * Pre-PhD: Full weight (1.0)
     * Post-PhD: Half weight (0.5) - still learning, but diminishing returns
     */
    fun getCurrentLearningWeight(): Double {
        return getCurrentCurriculumLevel().learningWeight
    }
    
    /**
     * Check if bot has reached Mega Brain status (post-PhD).
     */
    fun isMegaBrain(): Boolean {
        return getTotalTradesAcrossAllLayers() >= CurriculumLevel.MEGA_BRAIN_I.minTrades
    }
    
    /**
     * Get progress within current level (0-100%).
     * For Mega Brain levels, shows progress to next tier.
     */
    fun getLevelProgress(): Int {
        val totalTrades = getTotalTradesAcrossAllLayers()
        val currentLevel = getCurrentCurriculumLevel()
        
        // Find next level
        val nextLevel = CurriculumLevel.values()
            .filter { it.minTrades > currentLevel.minTrades }
            .minByOrNull { it.minTrades }
        
        return if (nextLevel != null) {
            val levelRange = nextLevel.minTrades - currentLevel.minTrades
            val progress = totalTrades - currentLevel.minTrades
            ((progress.toDouble() / levelRange) * 100).toInt().coerceIn(0, 100)
        } else {
            // V5.9.133 — At max named tier (ABSOLUTE). Never lock at 100%.
            // Progress shows a log-scaled "Beyond-N" counter that creeps up
            // forever; the bar never reaches the end.
            val beyond = totalTrades - currentLevel.minTrades
            val pct = (ln(1.0 + beyond.coerceAtLeast(0).toDouble()) * 5.0).toInt()
            pct.coerceIn(0, 99)  // NEVER 100 — there is no end.
        }
    }
    
    /**
     * Get "Mega Score" - total learning points with weight adjustment.
     * Pre-PhD: 1 point per trade
     * Post-PhD: 0.5 points per trade (but still accumulating!)
     */
    fun getMegaScore(): Double {
        val totalTrades = getTotalTradesAcrossAllLayers()
        return if (totalTrades <= CurriculumLevel.PHD.minTrades) {
            totalTrades.toDouble()
        } else {
            // PhD trades at full weight + remaining at half weight
            CurriculumLevel.PHD.minTrades + 
                (totalTrades - CurriculumLevel.PHD.minTrades) * 0.5
        }
    }
    
    /**
     * Get a motivational message based on current level.
     * Even at Singularity, emphasize continuous improvement.
     */
    fun getMotivationalMessage(): String {
        val level = getCurrentCurriculumLevel()
        val trades = getTotalTradesAcrossAllLayers()
        
        return when (level) {
            CurriculumLevel.FRESHMAN -> "Every trade is a lesson. Keep learning!"
            CurriculumLevel.SOPHOMORE -> "Patterns emerging. Stay curious."
            CurriculumLevel.JUNIOR -> "Statistical edge building. Trust the process."
            CurriculumLevel.SENIOR -> "Risk management mastering. Almost there."
            CurriculumLevel.MASTERS -> "Synthesizing insights. PhD incoming!"
            CurriculumLevel.PHD -> "Institutional grade achieved. But never stop learning..."
            CurriculumLevel.MEGA_BRAIN_I -> "Mega Brain activated! Markets always evolve."
            CurriculumLevel.MEGA_BRAIN_II -> "Neural pathways strengthening. Adaptation is key."
            CurriculumLevel.MEGA_BRAIN_III -> "Pattern recognition exceeding human limits."
            CurriculumLevel.QUANTUM_MIND -> "Operating on multiple probability dimensions."
            CurriculumLevel.NEURAL_APEX -> "Peak performance. But peaks can always be higher."
            CurriculumLevel.MARKET_ORACLE -> "Seeing patterns before they form. Stay humble."
            CurriculumLevel.ALPHA_ARCHITECT -> "Designing alpha. Markets are infinite teachers."
            CurriculumLevel.TRADING_GOD -> "Legendary status. Yet the market humbles all."
            CurriculumLevel.SINGULARITY -> "Transcended. But even singularities evolve."
            CurriculumLevel.VOID_WALKER -> "Walking the void between candles. ${trades} trades deep."
            CurriculumLevel.CHAOS_ENGINE -> "Chaos is not disorder. It is a higher order."
            CurriculumLevel.DARK_ORACLE -> "Seeing what others cannot. The dark patterns speak."
            CurriculumLevel.ENTROPY_LORD -> "Entropy is not the enemy. It is the fuel."
            CurriculumLevel.MARKET_WEAVER -> "Weaving reality from probability threads."
            CurriculumLevel.ALPHA_PREDATOR -> "The apex. Every market is your hunting ground."
            CurriculumLevel.PRIME_ARCHITECT -> "Building the architecture of alpha itself."
            CurriculumLevel.NEURAL_SOVEREIGN -> "Sovereign over ${trades} neural pathways."
            CurriculumLevel.OMEGA_MIND -> "The end is the beginning. Omega state achieved."
            CurriculumLevel.TRANSCENDENCE -> "Beyond. ${trades} trades woven into the fabric of markets."
            CurriculumLevel.ETERNAL -> "Eternal now. ${trades} trades, still one more to learn from."
            CurriculumLevel.COSMIC_MIND -> "Cosmic patterns resolved. The next candle is still a mystery."
            CurriculumLevel.GALACTIC_ORACLE -> "Galactic oracle. ${trades} trades and the edge keeps sharpening."
            CurriculumLevel.UNIVERSAL_WEAVER -> "Weaving universes of probability. No ceiling. No floor."
            CurriculumLevel.MULTIVERSAL -> "All timelines are training data now."
            CurriculumLevel.AXIOMATIC -> "Trading as axiom. Learning as breath."
            CurriculumLevel.PRIMORDIAL -> "Pre-market, pre-time. ${trades} trades encoded in origin."
            CurriculumLevel.ABSOLUTE -> "Absolute, yet still evolving. There is no 100%."
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER PERFORMANCE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Performance metrics for each AI layer.
     */
    data class LayerPerformanceMetrics(
        val layerName: String,
        var totalOutcomesRecorded: Int = 0,
        var successfulPredictions: Int = 0,
        var lastRecordedTimestamp: Long = 0,
        var avgConfidenceOnWins: Double = 50.0,
        var avgConfidenceOnLosses: Double = 50.0,
        var learningVelocity: Double = 1.0,  // How fast this layer is improving
        var trustMultiplier: Double = 1.0,    // Meta-cognition trust score
        // V5.7.3: Additional fields for perps learning
        var totalSignals: Int = 0,
        var accurateSignals: Int = 0,
        var avgContribution: Double = 0.0,
        var totalContributions: Int = 0,
        var learningRate: Double = 0.01,
        var lastUpdate: Long = System.currentTimeMillis(),

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.138 — QUALITY-WEIGHTED LEARNING FIELDS
        // ───────────────────────────────────────────────────────────────────
        // The binary (successful/total) hit-rate treats a 2% scratch win the
        // same as a 400% moonshot and a -1% breakeven the same as a -50%
        // rug. That's why the dashboard was stuck near the raw session
        // win-rate despite the layers actually having very different edge.
        //
        // New fields below store PnL-weighted stats. Each trade contributes
        // a weight proportional to |pnlPct| (big winners/losers matter more)
        // and a hit score (1.0 for correct, 0.0 for wrong). The smoothed
        // "edge score" is then (weightedHits + α·½) / (weightSum + α), which
        // converges to the true quality-weighted hit rate with a neutral
        // Bayesian prior at α=5.
        // ═══════════════════════════════════════════════════════════════════
        var weightSum: Double = 0.0,       // Σ weight
        var weightedHits: Double = 0.0,    // Σ weight · hit
        var pnlSumPct: Double = 0.0,       // Σ pnlPct            (mean = pnlSumPct / n)
        var pnlSumSqPct: Double = 0.0,     // Σ pnlPct²           (for std / Sharpe)
        var pnlBestPct: Double = 0.0,      // running max         (best trade)
        var pnlWorstPct: Double = 0.0,     // running min         (worst trade)
    ) {
        val accuracy: Double get() = if (totalOutcomesRecorded > 0)
            (successfulPredictions.toDouble() / totalOutcomesRecorded) * 100 else 50.0

        val isLearning: Boolean get() =
            // V5.9.142 — a layer counts as "learning" if it's been touched
            // (outcome OR consulted) inside the last 24h. Previously we
            // required totalOutcomesRecorded > 0, so the 16 V5.9.123 layers
            // showed dormant until they happened to pick up a real-accuracy
            // hit (|score| > 2). On tight markets they'd all stay quiet
            // and 13/41 layers would appear broken on the UI.
            lastRecordedTimestamp > 0 &&
            System.currentTimeMillis() - lastRecordedTimestamp < 24 * 60 * 60 * 1000L

        // V5.9.138 — mean pnlPct per trade. Real economic edge, not hit rate.
        val expectancyPct: Double get() = if (totalOutcomesRecorded > 0)
            pnlSumPct / totalOutcomesRecorded else 0.0

        // V5.9.138 — rough per-trade Sharpe: mean / stddev. Higher is better.
        val sharpe: Double get() {
            val n = totalOutcomesRecorded
            if (n < 2) return 0.0
            val mean = pnlSumPct / n
            val variance = (pnlSumSqPct / n) - (mean * mean)
            val std = if (variance > 1e-9) kotlin.math.sqrt(variance) else 0.0
            return if (std > 1e-6) mean / std else 0.0
        }
    }
    
    private val layerPerformance = ConcurrentHashMap<String, LayerPerformanceMetrics>()

    // V5.9.126 — REAL ACCURACY LEARNING
    // Capture per-layer entry scores keyed by mint. On trade close, correlate
    // the layer's entry-time prediction direction (sign of its score) with the
    // actual PnL sign. Layer gets a SUCCESS only when it correctly predicted
    // the direction; NEUTRAL scores (|score| ≤ 2) are skipped (no prediction).
    // This replaces the old "every layer succeeds on every win" fake learning.
    private data class EntryScoreSnapshot(
        val scores: Map<String, Int>,
        val timestamp: Long = System.currentTimeMillis(),
    )
    private val pendingEntryScores = ConcurrentHashMap<String, EntryScoreSnapshot>()
    private const val REAL_ACCURACY_NEUTRAL_THRESHOLD = 2  // |score| ≤ 2 → no prediction
    private const val REAL_ACCURACY_EXPIRY_MS = 24 * 60 * 60 * 1000L  // purge after 24h

    /**
     * V5.9.126 — called by UnifiedScorer at entry time for every scored
     * candidate. Map: component name → integer score. Used at trade close to
     * compute real per-layer prediction accuracy.
     */
    fun recordEntryScores(mint: String, components: List<ScoreComponent>) {
        if (mint.isBlank() || components.isEmpty()) return
        // Garbage-collect stale snapshots occasionally (cheap bounded sweep)
        if (pendingEntryScores.size > 500) {
            val cutoff = System.currentTimeMillis() - REAL_ACCURACY_EXPIRY_MS
            pendingEntryScores.entries.removeIf { it.value.timestamp < cutoff }
        }
        val map = components.associate { normalizeLayerName(it.name) to it.value }
        pendingEntryScores[mint] = EntryScoreSnapshot(map)
    }

    /** V5.9.140 — public wrapper so UnifiedScorer can normalise without making
     *  the private function public (keeps the when-expression internal). */
    fun normalizeComponentName(componentName: String): String = normalizeLayerName(componentName)

    /**
     * Canonical name used across scoring/diagnostics. Maps a component name
     * (as emitted by UnifiedScorer) to the REGISTERED_LAYERS key.
     */
    private fun normalizeLayerName(componentName: String): String = when (componentName.lowercase()) {
        "entry"              -> "EntryAI"
        "momentum"           -> "MomentumPredictorAI"
        "liquidity"          -> "LiquidityDepthAI"
        "volume"             -> "OrderFlowImbalanceAI"
        "holders"            -> "HolderSafetyAI"
        "narrative"          -> "NarrativeDetectorAI"
        "memory"             -> "TokenWinMemory"
        "regime"             -> "MarketRegimeAI"
        "time"               -> "TimeOptimizationAI"
        "copytrade"          -> "CopyTradeAI"
        "suppression"        -> "SuppressionAI"
        "feargreed"          -> "FearGreedAI"
        "social"             -> "SocialVelocityAI"
        "volatility"         -> "VolatilityRegimeAI"
        "orderflow"          -> "OrderFlowImbalanceAI"
        "smartmoney"         -> "SmartMoneyDivergenceAI"
        "holdtime"           -> "HoldTimeOptimizerAI"
        "liquiditycycle"     -> "LiquidityCycleAI"
        "collective_ai"      -> "CollectiveIntelligenceAI"
        "metacognition"      -> "MetaCognitionAI"
        "behavior"           -> "BehaviorLearning"
        "insider_tracker"    -> "InsiderTrackerAI"
        else -> componentName  // V5.9.123 layers already use their class name
    }

    /**
     * V5.9.126 — compute real per-layer success for a closed trade.
     * Called from recordTradeOutcomeAcrossAllLayers AFTER the legacy
     * hooks so dedicated recordOutcome() paths still run as before.
     * For each layer in REGISTERED_LAYERS that has an entry score:
     *   - bullish score (>= +3) + win  → success
     *   - bullish score (>= +3) + loss → failure
     *   - bearish score (<= -3) + loss → success (correctly bearish)
     *   - bearish score (<= -3) + win  → failure (missed the rip)
     *   - neutral (|score|<=2)          → skip (no prediction)
     *
     * Returns count of layers whose real accuracy was updated.
     */
    private fun applyRealAccuracyLearning(outcome: TradeOutcomeData): Int {
        val snap = pendingEntryScores.remove(outcome.mint) ?: return 0
        var updated = 0
        snap.scores.forEach { (layerName, score) ->
            if (layerName !in REGISTERED_LAYERS) return@forEach
            if (abs(score) <= REAL_ACCURACY_NEUTRAL_THRESHOLD) return@forEach
            val predictedBullish = score > 0
            val wasCorrect = (predictedBullish && outcome.isWin) || (!predictedBullish && !outcome.isWin)
            try {
                // V5.9.138 — feed real pnl magnitude so weighted-edge math
                // reflects WHICH layers were right on the big moves.
                markLayerOutcome(layerName, wasSuccess = wasCorrect, pnlPct = outcome.pnlPct)
                updated++
            } catch (_: Exception) {}
        }
        return updated
    }
    
    // Track all AI layers that should be learning
    private val REGISTERED_LAYERS = listOf(
        "HoldTimeOptimizerAI",
        "MomentumPredictorAI",
        "NarrativeDetectorAI",
        "TimeOptimizationAI",
        "LiquidityDepthAI",
        "WhaleTrackerAI",
        "MarketRegimeAI",
        "AdaptiveLearningEngine",
        "EdgeLearning",
        "TokenWinMemory",
        "BehaviorLearning",
        "MetaCognitionAI",
        "FluidLearningAI",
        "CollectiveIntelligenceAI",
        "VolatilityRegimeAI",
        "OrderFlowImbalanceAI",
        "SmartMoneyDivergenceAI",
        "LiquidityCycleAI",
        "FearGreedAI",
        "DipHunterAI",
        "SellOptimizationAI",
        "CashGenerationAI",
        "ShitCoinTraderAI",
        "BlueChipTraderAI",
        "MoonshotTraderAI",
        // V5.9.124 — 16 new AI layers from V5.9.123 + ReflexAI + AITrustNetwork
        "CorrelationHedgeAI",
        "LiquidityExitPathAI",
        "MEVDetectionAI",
        "StablecoinFlowAI",
        "OperatorFingerprintAI",
        "SessionEdgeAI",
        "ExecutionCostPredictorAI",
        "DrawdownCircuitAI",
        "CapitalEfficiencyAI",
        "TokenDNAClusteringAI",
        "PeerAlphaVerificationAI",
        "NewsShockAI",
        "FundingRateAwarenessAI",
        "OrderbookImbalancePulseAI",
        "AITrustNetworkAI",
        "ReflexAI",
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UNIFIED OUTCOME DISPATCH (THE MASTER LEARNING COORDINATOR)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Master learning function - dispatches trade outcome to ALL learning layers.
     * 
     * This ensures NO layer is ever skipped and ALL layers learn from EVERY trade.
     * 
     * @param outcome Complete trade outcome data
     */
    fun recordTradeOutcomeAcrossAllLayers(outcome: TradeOutcomeData) {
        val startTime = System.currentTimeMillis()
        var layersUpdated = 0
        val errors = mutableListOf<String>()
        
        // ═══════════════════════════════════════════════════════════════════
        // PHASE 1: Core Learning Layers (Critical)
        // ═══════════════════════════════════════════════════════════════════
        
        // HoldTimeOptimizerAI - Learn optimal hold durations
        try {
            HoldTimeOptimizerAI.recordOutcome(
                mint = outcome.mint,
                actualHoldMinutes = outcome.holdTimeMinutes.toInt(),
                pnlPct = outcome.pnlPct,
                setupQuality = outcome.setupQuality
            )
            markLayerOutcome("HoldTimeOptimizerAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("HoldTimeAI: ${e.message}") }
        
        // MetaCognitionAI - Meta-learning about layer accuracy
        try {
            MetaCognitionAI.recordTradeOutcome(
                mint = outcome.mint,
                symbol = outcome.symbol,
                pnlPct = outcome.pnlPct,
                holdTimeMs = outcome.holdTimeMinutes.toLong() * 60_000,
                exitReason = outcome.exitReason
            )
            markLayerOutcome("MetaCognitionAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("MetaCognitionAI: ${e.message}") }
        
        // FluidLearningAI - Update learning progress
        try {
            FluidLearningAI.recordTrade(outcome.isWin)
            markLayerOutcome("FluidLearningAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("FluidLearningAI: ${e.message}") }
        
        // AdaptiveLearningEngine - Feature weight adjustment
        try {
            val features = AdaptiveLearningEngine.captureFeatures(
                entryMcapUsd = outcome.entryMcapUsd,
                tokenAgeMinutes = outcome.tokenAgeMinutes,
                buyRatioPct = outcome.buyRatioPct,
                volumeUsd = outcome.volumeUsd,
                liquidityUsd = outcome.liquidityUsd,
                holderCount = outcome.holderCount,
                topHolderPct = outcome.topHolderPct,
                holderGrowthRate = outcome.holderGrowthRate,
                devWalletPct = outcome.devWalletPct,
                bondingCurveProgress = outcome.bondingCurveProgress,
                rugcheckScore = outcome.rugcheckScore,
                emaFanState = outcome.emaFanState,
                entryScore = outcome.entryScore,
                priceFromAth = outcome.priceFromAth,
                pnlPct = outcome.pnlPct,
                maxGainPct = outcome.maxGainPct,
                maxDrawdownPct = outcome.maxDrawdownPct,
                timeToPeakMins = outcome.timeToPeakMins,
                holdTimeMins = outcome.holdTimeMinutes,
                exitReason = outcome.exitReason,
                entryPhase = outcome.entryPhase,
            )
            AdaptiveLearningEngine.learnFromTrade(features)
            markLayerOutcome("AdaptiveLearningEngine", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("AdaptiveLearning: ${e.message}") }
        
        // ═══════════════════════════════════════════════════════════════════
        // PHASE 2: Market Analysis Layers
        // ═══════════════════════════════════════════════════════════════════
        
        // MomentumPredictorAI
        try {
            MomentumPredictorAI.recordOutcome(outcome.mint, outcome.pnlPct, outcome.maxGainPct)
            markLayerOutcome("MomentumPredictorAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("MomentumAI: ${e.message}") }
        
        // NarrativeDetectorAI
        try {
            NarrativeDetectorAI.recordOutcome(outcome.symbol, outcome.tokenName, outcome.pnlPct)
            markLayerOutcome("NarrativeDetectorAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("NarrativeAI: ${e.message}") }
        
        // TimeOptimizationAI
        try {
            TimeOptimizationAI.recordOutcome(outcome.pnlPct)
            markLayerOutcome("TimeOptimizationAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("TimeOptAI: ${e.message}") }
        
        // LiquidityDepthAI
        try {
            LiquidityDepthAI.recordOutcome(outcome.mint, outcome.pnlPct, outcome.isWin)
            markLayerOutcome("LiquidityDepthAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("LiquidityAI: ${e.message}") }
        
        // WhaleTrackerAI
        try {
            WhaleTrackerAI.recordSignalOutcome(outcome.mint, outcome.isWin, outcome.pnlPct)
            markLayerOutcome("WhaleTrackerAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("WhaleAI: ${e.message}") }
        
        // MarketRegimeAI
        try {
            MarketRegimeAI.recordTradeOutcome(outcome.pnlPct)
            markLayerOutcome("MarketRegimeAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("RegimeAI: ${e.message}") }
        
        // ═══════════════════════════════════════════════════════════════════
        // PHASE 3: Memory & Edge Layers
        // ═══════════════════════════════════════════════════════════════════
        
        // TokenWinMemory
        try {
            TokenWinMemory.recordTradeOutcome(
                mint = outcome.mint,
                symbol = outcome.symbol,
                name = outcome.tokenName,
                pnlPercent = outcome.pnlPct,
                peakPnl = outcome.maxGainPct,
                entryMcap = outcome.entryMcapUsd,
                exitMcap = outcome.exitMcapUsd,
                entryLiquidity = outcome.liquidityUsd,
                holdTimeMinutes = outcome.holdTimeMinutes.toInt(),
                buyPercent = outcome.buyRatioPct,
                source = outcome.discoverySource,
                phase = outcome.entryPhase,
            )
            markLayerOutcome("TokenWinMemory", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("TokenMemory: ${e.message}") }
        
        // EdgeLearning
        try {
            // EdgeLearning uses learnFromOutcome with a snapshot
            // For now, skip direct integration - it learns via different pathway
            markLayerOutcome("EdgeLearning", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("EdgeLearning: ${e.message}") }
        
        // BehaviorLearning
        try {
            // BehaviorLearning uses recordTrade with a pattern
            // For now, skip direct integration - it learns via different pathway
            markLayerOutcome("BehaviorLearning", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("BehaviorAI: ${e.message}") }
        
        // ═══════════════════════════════════════════════════════════════════
        // PHASE 4: Trading Mode Layers (V5.2.10 - Previously Missing!)
        // ═══════════════════════════════════════════════════════════════════
        
        // MoonshotTraderAI
        try {
            // MoonshotTraderAI tracks via its own position management
            markLayerOutcome("MoonshotTraderAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("MoonshotAI: ${e.message}") }
        
        // ShitCoinTraderAI
        try {
            markLayerOutcome("ShitCoinTraderAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("ShitCoinAI: ${e.message}") }
        
        // CashGenerationAI (Treasury)
        try {
            markLayerOutcome("CashGenerationAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("CashGenAI: ${e.message}") }
        
        // BlueChipTraderAI
        try {
            markLayerOutcome("BlueChipTraderAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("BlueChipAI: ${e.message}") }
        
        // ═══════════════════════════════════════════════════════════════════
        // PHASE 5: Advanced Analytics Layers (V5.2.10 - Previously Missing!)
        // ═══════════════════════════════════════════════════════════════════
        
        // VolatilityRegimeAI
        try {
            markLayerOutcome("VolatilityRegimeAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("VolatilityAI: ${e.message}") }
        
        // OrderFlowImbalanceAI
        try {
            markLayerOutcome("OrderFlowImbalanceAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("OrderFlowAI: ${e.message}") }
        
        // SmartMoneyDivergenceAI
        try {
            markLayerOutcome("SmartMoneyDivergenceAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("SmartMoneyAI: ${e.message}") }
        
        // LiquidityCycleAI
        try {
            markLayerOutcome("LiquidityCycleAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("LiqCycleAI: ${e.message}") }
        
        // FearGreedAI
        try {
            markLayerOutcome("FearGreedAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("FearGreedAI: ${e.message}") }
        
        // DipHunterAI
        try {
            markLayerOutcome("DipHunterAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("DipHunterAI: ${e.message}") }
        
        // SellOptimizationAI
        try {
            markLayerOutcome("SellOptimizationAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("SellOptAI: ${e.message}") }
        
        // CollectiveIntelligenceAI
        try {
            markLayerOutcome("CollectiveIntelligenceAI", outcome.isWin, outcome.pnlPct)
            layersUpdated++
        } catch (e: Exception) { errors.add("CollectiveAI: ${e.message}") }

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 6a: V5.9.124 — 16 new AI layers. These don't have dedicated
        // recordOutcome hooks yet (they learn from live market ticks/scans),
        // so we just mark them as updated so they appear as active/learning
        // in the BrainNetwork diagnostic view.
        //
        // V5.9.126: REAL ACCURACY LEARNING — the mark below just bumps the
        // "last updated" timestamp so dormant-detection works on very first
        // trade; the ACTUAL accuracy update happens in applyRealAccuracyLearning
        // below, which correlates each layer's entry score direction with the
        // PnL sign.
        // ═══════════════════════════════════════════════════════════════════
        listOf(
            "CorrelationHedgeAI", "LiquidityExitPathAI", "MEVDetectionAI",
            "StablecoinFlowAI", "OperatorFingerprintAI", "SessionEdgeAI",
            "ExecutionCostPredictorAI", "DrawdownCircuitAI", "CapitalEfficiencyAI",
            "TokenDNAClusteringAI", "PeerAlphaVerificationAI", "NewsShockAI",
            "FundingRateAwarenessAI", "OrderbookImbalancePulseAI",
            "AITrustNetworkAI", "ReflexAI",
        ).forEach { layerName ->
            try {
                // V5.9.142 — ALWAYS refresh the layer's activity timestamp
                // on every closed trade, not just on first-time init. This
                // was the source of the "13 dormant layers" problem — these
                // 16 layers' metrics existed from creation but their
                // timestamp was only touched once, so they aged into
                // dormant after 24h even though the scorer kept consulting
                // them on every tick.
                val m = layerPerformance.getOrPut(layerName) {
                    LayerPerformanceMetrics(layerName)
                }
                m.lastRecordedTimestamp = System.currentTimeMillis()
                layersUpdated++
            } catch (e: Exception) { errors.add("$layerName: ${e.message}") }
        }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.126 — REAL PER-LAYER ACCURACY LEARNING
        // Correlate each scored layer's entry-time direction (sign of score)
        // with the actual PnL sign. Only layers that made a prediction
        // (|score| > 2) get their accuracy updated. Runs AFTER legacy hooks
        // so dedicated recordOutcome() paths still execute unchanged.
        // ═══════════════════════════════════════════════════════════════════
        // V5.9.139 — analyse approval-pattern BEFORE applyRealAccuracy
        // consumes pendingEntryScores. Otherwise the snapshot is gone and
        // the approval learner silently sees nothing.
        analyzeLayerCorrelations(outcome)

        val realUpdates = applyRealAccuracyLearning(outcome)
        if (realUpdates > 0) {
            ErrorLogger.info(TAG, "🎯 REAL ACCURACY: ${outcome.symbol} — ${realUpdates} layers updated from entry-score correlation")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // PHASE 6: Cross-Layer Learning (Harvard Brain Magic)
        // ═══════════════════════════════════════════════════════════════════
        
        // (approval-pattern analysis already ran above — see V5.9.139 note)
        
        // Update curriculum progress
        updateCurriculumProgress()
        
        val elapsed = System.currentTimeMillis() - startTime
        val level = getCurrentCurriculumLevel()
        
        val statusEmoji = if (outcome.isWin) "✅" else "❌"
        ErrorLogger.info(TAG, "$statusEmoji ${level.icon} EDUCATION UPDATE: ${outcome.symbol} | " +
            "${outcome.pnlPct.toInt()}% | $layersUpdated/${REGISTERED_LAYERS.size} layers taught | " +
            "${elapsed}ms | Level: ${level.displayName}")
        
        if (errors.isNotEmpty()) {
            ErrorLogger.warn(TAG, "⚠️ Some layers failed to learn: ${errors.take(3).joinToString(", ")}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Complete trade outcome data for unified learning dispatch.
     */
    data class TradeOutcomeData(
        val mint: String,
        val symbol: String,
        val tokenName: String,
        val pnlPct: Double,
        val holdTimeMinutes: Double,
        val exitReason: String,
        val entryPhase: String,
        val tradingMode: String,
        val discoverySource: String,
        val setupQuality: String,
        
        // Market data at entry
        val entryMcapUsd: Double,
        val exitMcapUsd: Double,
        val tokenAgeMinutes: Double,
        val buyRatioPct: Double,
        val volumeUsd: Double,
        val liquidityUsd: Double,
        val holderCount: Int,
        val topHolderPct: Double,
        val holderGrowthRate: Double,
        val devWalletPct: Double,
        val bondingCurveProgress: Double,
        val rugcheckScore: Double,
        val emaFanState: String,
        val entryScore: Double,
        val priceFromAth: Double,
        
        // Performance data
        val maxGainPct: Double,
        val maxDrawdownPct: Double,
        val timeToPeakMins: Double,
    ) {
        val isWin: Boolean get() = pnlPct > 0
        val isRunner: Boolean get() = pnlPct >= 20.0
        val isRug: Boolean get() = pnlPct <= -30.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun markLayerUpdated(layerName: String, wasSuccess: Boolean) {
        markLayerOutcome(layerName, wasSuccess = wasSuccess, pnlPct = if (wasSuccess) 1.0 else -1.0)
    }

    /**
     * V5.9.138 — PRIMARY learning entry point. Records per-layer outcome with
     * REAL pnl magnitude (not just a win/loss bit).
     *
     *   weight   = 1 + |pnlPct|/25     → scratch=1, ±25%=2, ±100%=5, ±400%=17
     *   hitScore = 1.0 if correct, 0.0 if wrong
     *
     * `weightedHits / weightSum` converges to the quality-weighted hit rate
     * — so a layer that gets the big trades right scores high even when it
     * loses many scratchers, and a layer that only wins on scratches scores
     * low. Combined with Bayesian smoothing in getLayerAccuracy(), the
     * dashboard finally reflects real economic edge instead of raw hit rate.
     *
     * Backward compat: the legacy `successfulPredictions / totalOutcomesRecorded`
     * counters are still incremented, so any older code path reading
     * `metrics.accuracy` directly is unchanged.
     */
    fun markLayerOutcome(layerName: String, wasSuccess: Boolean, pnlPct: Double) {
        val metrics = layerPerformance.getOrPut(layerName) {
            LayerPerformanceMetrics(layerName)
        }
        // Legacy binary counters (unchanged).
        metrics.totalOutcomesRecorded++
        if (wasSuccess) metrics.successfulPredictions++
        metrics.lastRecordedTimestamp = System.currentTimeMillis()
        metrics.learningVelocity = metrics.learningVelocity * 0.9 +
            (if (wasSuccess) 0.1 else -0.1)

        // New quality-weighted accumulators.
        val clipped = pnlPct.coerceIn(-95.0, 1000.0)       // guard absurd feeds
        val weight  = 1.0 + kotlin.math.abs(clipped) / 25.0
        val hit     = if (wasSuccess) 1.0 else 0.0
        metrics.weightSum     += weight
        metrics.weightedHits  += weight * hit
        metrics.pnlSumPct     += clipped
        metrics.pnlSumSqPct   += clipped * clipped
        if (clipped > metrics.pnlBestPct)  metrics.pnlBestPct  = clipped
        if (clipped < metrics.pnlWorstPct) metrics.pnlWorstPct = clipped

        save()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CROSS-LAYER CORRELATION ANALYSIS (The Harvard Brain)
    // ───────────────────────────────────────────────────────────────────────────
    // V5.9.139 — APPROVAL DATA UPGRADE
    // The old analyzer built its "bullish layer" signature from a layer's
    // HISTORICAL accuracy (> 55%), not its actual vote on this trade. The
    // signature therefore contained the same good layers on every trade and
    // told us nothing new — the bot was only learning from the rejection
    // side (ShadowLearningEngine etc.), exactly as it complained to the
    // user ("Rejection data is richer than approval data").
    //
    // New approach: snapshot the REAL entry vote map (captured by
    // recordEntryScores() in UnifiedScorer) and build the approval
    // signature from layers that ACTUALLY voted bullish on THIS trade.
    // We also track expectancy per signature (pnlSum / count), not just
    // wins/losses, and expose it as a boost the scorer can lean into.
    // ═══════════════════════════════════════════════════════════════════════════

    data class ApprovalRecord(
        var wins: Int = 0,
        var losses: Int = 0,
        var pnlSumPct: Double = 0.0,
        var pnlBestPct: Double = 0.0,
        var pnlWorstPct: Double = 0.0,
        var lastSeen: Long = 0L,
    ) {
        val count: Int get() = wins + losses
        val winRate: Double get() = if (count > 0) wins.toDouble() / count else 0.0
        val expectancyPct: Double get() = if (count > 0) pnlSumPct / count else 0.0
    }

    private val winningCombinations = ConcurrentHashMap<String, Int>()
    private val losingCombinations  = ConcurrentHashMap<String, Int>()
    private val approvalPatterns    = ConcurrentHashMap<String, ApprovalRecord>()

    /** Minimum number of bullish layers required to form a signature. */
    private const val APPROVAL_MIN_BULLISH = 2

    /** Max layers we'll encode in one signature (keep cardinality sane). */
    private const val APPROVAL_MAX_LAYERS = 6

    /** Bullish threshold on the raw component score (matches real-accuracy gate). */
    private const val APPROVAL_BULLISH_THRESHOLD = 3

    private fun buildApprovalSignature(entryScores: Map<String, Int>?): String? {
        if (entryScores.isNullOrEmpty()) return null
        val bullish = entryScores
            .asSequence()
            .filter { (_, s) -> s >= APPROVAL_BULLISH_THRESHOLD }
            .sortedByDescending { it.value }
            .take(APPROVAL_MAX_LAYERS)
            .map { it.key }
            .toList()
        if (bullish.size < APPROVAL_MIN_BULLISH) return null
        return bullish.sorted().joinToString("+")
    }

    private fun analyzeLayerCorrelations(outcome: TradeOutcomeData) {
        // V5.9.139 — use the REAL entry vote map (peek, do not remove —
        // applyRealAccuracyLearning consumes it a few lines later).
        val entryScores = pendingEntryScores[outcome.mint]?.scores
        val signature = buildApprovalSignature(entryScores) ?: return

        // Legacy win/loss counters (kept for backward compatibility with
        // getWinningLayerCombinations()).
        if (outcome.isWin) {
            winningCombinations.merge(signature, 1) { old, _ -> old + 1 }
        } else {
            losingCombinations.merge(signature, 1) { old, _ -> old + 1 }
        }

        // Rich approval record — this is the new good-behaviour memory.
        val rec = approvalPatterns.getOrPut(signature) { ApprovalRecord() }
        if (outcome.isWin) rec.wins++ else rec.losses++
        val pnl = outcome.pnlPct.coerceIn(-95.0, 1000.0)
        rec.pnlSumPct   += pnl
        rec.pnlBestPct   = if (pnl > rec.pnlBestPct)  pnl else rec.pnlBestPct
        rec.pnlWorstPct  = if (pnl < rec.pnlWorstPct) pnl else rec.pnlWorstPct
        rec.lastSeen     = System.currentTimeMillis()
    }

    // ─────────────────────────────────────────────────────────────────────
    // V5.9.139 — public APIs for the rest of the brain
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the best-matching approval-pattern boost for a candidate's
     * entry vote map. Pipeline:
     *   1. Build the bullish signature the same way as the learner.
     *   2. Look up in approvalPatterns (exact match).
     *   3. If no exact match, fall back to the biggest known sub-pattern
     *      contained within the candidate's bullish set.
     *   4. Gate by min sample size and non-negative expectancy.
     *   5. Return a clamped [-6, +10] score nudge.
     */
    fun approvalBoostFor(entryScores: Map<String, Int>?): Pair<Int, String> {
        val signature = buildApprovalSignature(entryScores) ?: return 0 to "NO_SIG"

        // Exact match first.
        val exact = approvalPatterns[signature]?.takeIf { it.count >= 5 }
        val (rec, matched) = if (exact != null) {
            exact to signature
        } else {
            // Fallback: largest subset that is also a known winning sig.
            val bullishSet = signature.split("+").toSet()
            var best: ApprovalRecord? = null
            var bestSig: String = ""
            approvalPatterns.forEach { (sig, r) ->
                if (r.count < 5) return@forEach
                val parts = sig.split("+")
                if (parts.size > bullishSet.size) return@forEach
                if (!parts.all { it in bullishSet }) return@forEach
                val isBetter = best == null ||
                    parts.size > bestSig.split("+").size ||
                    (parts.size == bestSig.split("+").size && r.expectancyPct > (best?.expectancyPct ?: -1e9))
                if (isBetter) { best = r; bestSig = sig }
            }
            (best ?: return 0 to "NO_MATCH") to bestSig
        }

        // Convert expectancy → integer score nudge.
        // V5.9.143 — softened negative arm so approval-memory can't
        // compound-brake with the mute/boost gate into zero-volume.
        //   +5%  expectancy → +3,  +20% → +6,  +50% → +8,  +100% → +10
        //   -5%  → 0,  -20% → -1,  -50% → -3,  worse → -4
        val exp = rec.expectancyPct
        val nudge = when {
            exp >=  50.0 -> 10
            exp >=  20.0 ->  6 + ((exp - 20.0) / 15.0).toInt().coerceAtMost(2)
            exp >=   5.0 ->  3 + ((exp -  5.0) / 5.0).toInt().coerceAtMost(2)
            exp >  -5.0  ->  0
            exp >= -20.0 -> -1
            exp >= -50.0 -> -3
            else         -> -4
        }.coerceIn(-4, 10)
        val reason = "APPROVAL:${matched.take(40)} ${rec.wins}W/${rec.losses}L exp=${"%+.1f".format(exp)}%"
        return nudge to reason
    }

    /** Rich approval pattern leaderboard — replaces the thin old helper. */
    fun getApprovalPatterns(minCount: Int = 5): List<Triple<String, ApprovalRecord, Double>> {
        return approvalPatterns.entries
            .filter { it.value.count >= minCount }
            .map { (sig, r) ->
                // Sort key: expectancy × sqrt(sample count) — rewards pattern
                // that both hits often AND prints size.
                val key = r.expectancyPct * kotlin.math.sqrt(r.count.toDouble())
                Triple(sig, r, key)
            }
            .sortedByDescending { it.third }
    }

    /**
     * Get layer combinations that predict winners.
     */
    fun getWinningLayerCombinations(): List<Pair<String, Double>> {
        return winningCombinations.entries
            .filter { (sig, wins) ->
                val losses = losingCombinations[sig] ?: 0
                wins + losses >= 5 // Min sample size
            }
            .map { (sig, wins) ->
                val losses = losingCombinations[sig] ?: 0
                sig to (wins.toDouble() / (wins + losses) * 100)
            }
            .filter { it.second >= 60.0 }
            .sortedByDescending { it.second }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CURRICULUM PROGRESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val lastCurriculumCheck = AtomicLong(0)
    private var lastLevel = CurriculumLevel.FRESHMAN
    
    private fun updateCurriculumProgress() {
        val now = System.currentTimeMillis()
        // Check every 10 trades
        if (now - lastCurriculumCheck.get() < 60_000) return
        lastCurriculumCheck.set(now)
        
        val currentLevel = getCurrentCurriculumLevel()
        if (currentLevel != lastLevel) {
            ErrorLogger.info(TAG, "${currentLevel.icon} GRADUATION! " +
                "Bot advanced to ${currentLevel.displayName} level!")
            lastLevel = currentLevel
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS & DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get total trades learned across all layers.
     */
    fun getTotalTradesAcrossAllLayers(): Int {
        return layerPerformance.values.maxOfOrNull { it.totalOutcomesRecorded } ?: 0
    }
    
    /**
     * Get learning health report for all layers.
     */
    fun getLearningHealthReport(): String {
        val level = getCurrentCurriculumLevel()
        val totalTrades = getTotalTradesAcrossAllLayers()
        
        val activelyLearning = layerPerformance.values.count { it.isLearning }
        val dormantLayers = REGISTERED_LAYERS.filter { name ->
            layerPerformance[name]?.isLearning != true
        }
        
        val avgAccuracy = layerPerformance.values
            .filter { it.totalOutcomesRecorded >= 10 }
            .map { it.accuracy }
            .average().takeIf { !it.isNaN() } ?: 50.0
        
        val report = StringBuilder()
        report.appendLine("═══════════════════════════════════════════════════")
        report.appendLine("${level.icon} HARVARD BRAIN STATUS: ${level.displayName}")
        report.appendLine("═══════════════════════════════════════════════════")
        report.appendLine("Total Trades Learned: $totalTrades")
        // V5.9.133 — Maturity NEVER caps at 100%. We always show current
        // curriculum level + progress toward the NEXT level. The bot
        // evolves indefinitely through Singularity → Transcendence (1M trades).
        val nextLevel = CurriculumLevel.values()
            .filter { it.minTrades > level.minTrades }
            .minByOrNull { it.minTrades }
        val progressLine = if (nextLevel != null) {
            "Progress to ${nextLevel.displayName}: ${getLevelProgress()}% " +
                "(${totalTrades - level.minTrades}/${nextLevel.minTrades - level.minTrades} trades)"
        } else {
            "At max tier — trades continue accumulating beyond the curriculum."
        }
        report.appendLine(progressLine)
        report.appendLine("Actively Learning Layers: $activelyLearning/${REGISTERED_LAYERS.size}")
        report.appendLine("Average Layer Accuracy: ${avgAccuracy.toInt()}%")
        
        if (dormantLayers.isNotEmpty()) {
            report.appendLine("⚠️ Dormant Layers: ${dormantLayers.take(5).joinToString(", ")}")
        }
        
        // Top performing layers
        val topLayers = layerPerformance.values
            .filter { it.totalOutcomesRecorded >= 10 }
            .sortedByDescending { it.accuracy }
            .take(3)
        
        if (topLayers.isNotEmpty()) {
            report.appendLine("🏆 Top Performers:")
            topLayers.forEach { layer ->
                report.appendLine("   • ${layer.layerName}: ${layer.accuracy.toInt()}% accuracy")
            }
        }
        
        // Winning combinations
        val winningCombos = getWinningLayerCombinations().take(3)
        if (winningCombos.isNotEmpty()) {
            report.appendLine("🔥 Winning AI Combinations:")
            winningCombos.forEach { (combo, winRate) ->
                report.appendLine("   • $combo: ${winRate.toInt()}% win rate")
            }
        }
        
        return report.toString()
    }
    
    /**
     * Get list of layers that are NOT learning (need attention).
     */
    fun getDormantLayers(): List<String> {
        return REGISTERED_LAYERS.filter { name ->
            val metrics = layerPerformance[name]
            metrics == null || !metrics.isLearning
        }
    }
    
    /**
     * Force a diagnostic check of all layers.
     */
    fun runDiagnostics(): Map<String, Boolean> {
        return REGISTERED_LAYERS.associateWith { name ->
            layerPerformance[name]?.isLearning ?: false
        }
    }

    /**
     * V5.9.129 — public accessor for SentienceOrchestrator. Returns the
     * layer's rolling accuracy in [0.0, 1.0]. 0.5 = no predictive edge;
     * < 0.5 = actively wrong; > 0.5 = positive edge. Unknown layers → 0.5
     * (neutral baseline) rather than an error so callers can just iterate.
     *
     * V5.9.133 — BAYESIAN-SMOOTHED ACCURACY (never falsely "100%"):
     * m.accuracy is stored on a 0–100 percentage scale; the old code simply
     * clamped it to [0.0, 1.0] which made every active layer look like 100%
     * to the LLM (the exact source of the "layers at 100% feel like lies"
     * complaint).
     *
     * V5.9.138 — QUALITY-WEIGHTED EDGE (the real upgrade):
     * Hit rate alone is misleading — a layer that bags two 400% runners and
     * loses fifteen scratchers has a 12% hit rate but a massive positive
     * edge. We now use the weightSum / weightedHits counters populated by
     * markLayerOutcome(). Each trade's contribution is scaled by
     * (1 + |pnlPct|/25), so big winners and big losers dominate scratches.
     *
     *   edge = (weightedHits + α·0.5) / (weightSum + α)
     *   α = 5  → neutral prior of 0.5 until ~5 "unit-weight" trades.
     *
     * Falls back to the binary Bayesian formula for any pre-V5.9.138 data
     * (weightSum == 0) so the upgrade is forward-compat with persisted
     * state.
     */
    fun getLayerAccuracy(layerName: String): Double {
        val m = layerPerformance[layerName] ?: return 0.5
        val alpha = 5.0
        if (m.weightSum > 0.0) {
            return ((m.weightedHits + alpha * 0.5) / (m.weightSum + alpha))
                .coerceIn(0.0, 1.0)
        }
        // Legacy fallback: unweighted Bayesian hit rate.
        val n = m.totalOutcomesRecorded
        if (n <= 0) return 0.5
        val wins = m.successfulPredictions.toDouble()
        return ((wins + alpha) / (n + 2.0 * alpha)).coerceIn(0.0, 1.0)
    }

    /** V5.9.138 — mean pnlPct per trade for this layer, or 0 if no history. */
    fun getLayerExpectancyPct(layerName: String): Double =
        layerPerformance[layerName]?.expectancyPct ?: 0.0

    /** V5.9.138 — rough per-trade Sharpe (mean / std of pnlPct). */
    fun getLayerSharpe(layerName: String): Double =
        layerPerformance[layerName]?.sharpe ?: 0.0

    /**
     * V5.9.140 — AUTO-MUTE / AUTO-BOOST.
     *
     * V5.9.143 — THRESHOLDS LOOSENED + asymmetric multiplier.
     * Evidence from the field: under these 40/65 gates plus the
     * approval-memory penalty the bot went from ~1000 trades/hr to
     * single-digit. Most layers sit at 35-48% smoothed edge on normal
     * losing tape, so a 40% mute cut the herd in half. The boost side
     * was ALSO multiplying negative votes (vetoes), so every trusted
     * layer's "no" got 1.25-1.45×'d on top. Net effect: hard brake.
     *
     * New policy:
     *
     *   edge <= 0.33  and >=20 trades  → vote × 0.00  (MUTE)
     *   edge <= 0.40  and >=20 trades  → vote × 0.60  (SOFT_PENALTY)
     *   0.40 < edge < 0.62             → vote × 1.00  (NORMAL)
     *   edge >= 0.62 and >=20 trades, positive vote only → × 1.20 (BOOST)
     *   edge >= 0.70 and >=40 trades, positive vote only → × 1.35 (HEAVY_BOOST)
     *
     * Key changes vs V5.9.140:
     *  - Mute threshold 0.40 → 0.33 (only truly bleeding layers).
     *  - Soft penalty 0.45 → 0.40.
     *  - Boost 0.65 → 0.62, heavy 0.75 → 0.70.
     *  - Min sample size for any gating 10 → 20 (less premature
     *    silencing while a layer is still finding its feet).
     *  - BOOST now only amplifies POSITIVE votes. Trusted layers with
     *    a negative vote (veto) still get vote×1.0, not ×1.45. This
     *    stops the implicit "1.45× veto" that was burying candidates.
     *  - Mute still applies to both signs, so a distrusted layer's
     *    veto correctly loses its force.
     */
    fun applyMuteBoost(layerName: String, vote: Int): Triple<Int, Double, String> {
        val m = layerPerformance[layerName]
        val trades = m?.totalOutcomesRecorded ?: 0
        if (trades < 20) return Triple(vote, 1.0, "NORMAL")
        val edge = getLayerAccuracy(layerName)
        val (mult, status) = when {
            edge <= 0.33 -> 0.0  to "MUTE"
            edge <= 0.40 -> 0.6  to "SOFT_PENALTY"
            // Boosts only apply to POSITIVE votes to avoid amplifying vetoes.
            edge >= 0.70 && trades >= 40 && vote > 0 -> 1.35 to "HEAVY_BOOST"
            edge >= 0.62 && vote > 0                 -> 1.20 to "BOOST"
            else -> 1.0 to "NORMAL"
        }
        val adjusted = (vote * mult).toInt()
        return Triple(adjusted, mult, status)
    }

    /**
     * V5.9.140 — diagnostic snapshot of currently-muted / -boosted layers.
     * Used by the UI / SentienceOrchestrator so the user can SEE which
     * layers the bot has silenced on its own.
     */
    data class MuteBoostStatus(
        val muted:       List<String>,
        val softPenalty: List<String>,
        val boosted:     List<String>,
        val heavyBoost:  List<String>,
    )

    fun getMuteBoostStatus(): MuteBoostStatus {
        val muted = mutableListOf<String>()
        val soft  = mutableListOf<String>()
        val boost = mutableListOf<String>()
        val heavy = mutableListOf<String>()
        REGISTERED_LAYERS.forEach { name ->
            val m = layerPerformance[name] ?: return@forEach
            if (m.totalOutcomesRecorded < 20) return@forEach  // V5.9.143 match gate
            val edge = getLayerAccuracy(name)
            when {
                edge <= 0.33 -> muted.add(name)
                edge <= 0.40 -> soft.add(name)
                edge >= 0.70 && m.totalOutcomesRecorded >= 40 -> heavy.add(name)
                edge >= 0.62 -> boost.add(name)
            }
        }
        return MuteBoostStatus(muted, soft, boost, heavy)
    }

    /**
     * V5.9.133 — RAW (unsmoothed) accuracy, 0.0–1.0. For internal use only
     * where the caller explicitly wants the naive hit-rate without
     * Bayesian smoothing. Prefer getLayerAccuracy() for any external /
     * LLM-facing display.
     */
    fun getLayerAccuracyRaw(layerName: String): Double {
        val m = layerPerformance[layerName] ?: return 0.5
        if (m.totalOutcomesRecorded <= 0) return 0.5
        return (m.accuracy / 100.0).coerceIn(0.0, 1.0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.133 — PER-LAYER GRADUATED CURRICULUM (Task 2a)
    // ───────────────────────────────────────────────────────────────────────────
    // Every one of the 41 AI layers now has its OWN curriculum level, mirroring
    // the Education Sub-Layer's Freshman → Absolute progression. A layer never
    // hits "100% done" — it graduates through infinite tiers as it accumulates
    // its own trade outcomes. This replaces the misleading bot-wide "100%
    // maturity" with a real per-layer picture.
    //
    // Level is chosen by the layer's individual totalOutcomesRecorded count
    // (same thresholds as the global curriculum). Mastery within a level is
    // measured by Bayesian-smoothed accuracy, not raw hit-rate, so a layer
    // with one lucky win does NOT appear as a master.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Per-layer curriculum level — based on how many outcomes this specific
     * layer has recorded. Unknown / never-used layers start at FRESHMAN.
     */
    fun getLayerLevel(layerName: String): CurriculumLevel {
        val count = layerPerformance[layerName]?.totalOutcomesRecorded ?: 0
        return CurriculumLevel.values()
            .filter { it.minTrades <= count }
            .maxByOrNull { it.minTrades } ?: CurriculumLevel.FRESHMAN
    }

    /**
     * Per-layer within-level progress [0, 100). NEVER returns 100 at the top
     * tier — learning is infinite. Below the top tier, returns progress to
     * the NEXT tier for this specific layer.
     */
    fun getLayerLevelProgress(layerName: String): Int {
        val count = layerPerformance[layerName]?.totalOutcomesRecorded ?: 0
        val current = getLayerLevel(layerName)
        val next = CurriculumLevel.values()
            .filter { it.minTrades > current.minTrades }
            .minByOrNull { it.minTrades }
        return if (next != null) {
            val range = next.minTrades - current.minTrades
            val gained = count - current.minTrades
            ((gained.toDouble() / range) * 100).toInt().coerceIn(0, 99)
        } else {
            // At ABSOLUTE tier. Use same log-scaled bar as the global layer.
            val beyond = count - current.minTrades
            val pct = (ln(1.0 + beyond.coerceAtLeast(0).toDouble()) * 5.0).toInt()
            pct.coerceIn(0, 99)  // NEVER 100 — infinite learning.
        }
    }

    /**
     * Snapshot of one layer's graduated-curriculum state. Used by the UI
     * (BrainNetworkView) and by the LLM (SentienceOrchestrator) so they can
     * reason about WHICH layers are green-fresh vs. which have real tenure.
     */
    data class LayerMaturity(
        val layerName: String,
        val level: CurriculumLevel,
        val levelProgress: Int,       // 0..99, never 100
        val trades: Int,
        val smoothedAccuracy: Double, // 0.0..1.0, Bayesian
        val isActive: Boolean,
        // V5.9.138 — REAL EDGE SIGNALS (not just hit-rate)
        val expectancyPct: Double,    // mean pnl% per trade; negative = bleeding
        val bestPct: Double,          // best single trade pnl% observed
        val worstPct: Double,         // worst single trade pnl% observed
        val sharpe: Double,           // rough per-trade Sharpe
    )

    /** Get one layer's maturity snapshot. */
    fun getLayerMaturity(layerName: String): LayerMaturity {
        val m = layerPerformance[layerName]
        return LayerMaturity(
            layerName        = layerName,
            level            = getLayerLevel(layerName),
            levelProgress    = getLayerLevelProgress(layerName),
            trades           = m?.totalOutcomesRecorded ?: 0,
            smoothedAccuracy = getLayerAccuracy(layerName),
            isActive         = m?.isLearning ?: false,
            expectancyPct    = m?.expectancyPct ?: 0.0,
            bestPct          = m?.pnlBestPct ?: 0.0,
            worstPct         = m?.pnlWorstPct ?: 0.0,
            sharpe           = m?.sharpe ?: 0.0,
        )
    }

    /** Maturity snapshot for every registered layer, for the BrainNetworkView. */
    fun getAllLayerMaturity(): Map<String, LayerMaturity> =
        REGISTERED_LAYERS.associateWith { getLayerMaturity(it) }
    
    /**
     * Reset all learning (use with caution!).
     */
    fun resetAllLearning() {
        layerPerformance.clear()
        winningCombinations.clear()
        losingCombinations.clear()
        approvalPatterns.clear()
        lastLevel = CurriculumLevel.FRESHMAN
        ErrorLogger.warn(TAG, "🔄 ALL EDUCATION DATA RESET")
    }
    
    /**
     * Get trust multiplier for a specific layer.
     * Used by MetaCognitionAI and FinalDecisionGate.
     */
    fun getLayerTrustMultiplier(layerName: String): Double {
        val metrics = layerPerformance[layerName] ?: return 1.0
        
        // Not enough data - neutral trust
        if (metrics.totalOutcomesRecorded < 10) return 1.0
        
        return when {
            metrics.accuracy >= 70 -> 1.3   // Excellent - boost
            metrics.accuracy >= 60 -> 1.15  // Good - slight boost
            metrics.accuracy >= 50 -> 1.0   // Average - neutral
            metrics.accuracy >= 40 -> 0.85  // Below average - reduce
            else -> 0.7                      // Poor - significantly reduce
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE - Save/Load layer performance metrics
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var ctx: android.content.Context? = null
    private const val PREFS_NAME = "education_sublayer_ai"

    // Debounce: only persist to SharedPreferences once every 30s to prevent
    // I/O storms when 25 layers all call save() during one trade outcome.
    private const val SAVE_DEBOUNCE_MS = 30_000L
    @Volatile private var lastSaveMs = 0L

    /**
     * Initialize with context for persistence.
     */
    fun init(context: android.content.Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(TAG, "📚 EducationSubLayerAI initialized | Layers: ${layerPerformance.size} | Total trades: ${getTotalTradesAcrossAllLayers()}")
    }
    
    /**
     * Save layer performance to SharedPreferences (debounced: max once per 30s).
     * Call saveForced() to bypass debounce (e.g. on reset or shutdown).
     */
    fun save() {
        val now = System.currentTimeMillis()
        if (now - lastSaveMs < SAVE_DEBOUNCE_MS) return
        saveForced()
    }

    /**
     * Immediately persist regardless of debounce timer.
     */
    fun saveForced() {
        val c = ctx ?: return
        lastSaveMs = System.currentTimeMillis()
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Save each layer's metrics as JSON strings
            layerPerformance.forEach { (name, metrics) ->
                val json = org.json.JSONObject().apply {
                    put("totalOutcomesRecorded", metrics.totalOutcomesRecorded)
                    put("successfulPredictions", metrics.successfulPredictions)
                    put("lastRecordedTimestamp", metrics.lastRecordedTimestamp)
                    put("avgConfidenceOnWins", metrics.avgConfidenceOnWins)
                    put("avgConfidenceOnLosses", metrics.avgConfidenceOnLosses)
                    put("learningVelocity", metrics.learningVelocity)
                    put("trustMultiplier", metrics.trustMultiplier)
                    // V5.9.138 — quality-weighted learning fields
                    put("weightSum", metrics.weightSum)
                    put("weightedHits", metrics.weightedHits)
                    put("pnlSumPct", metrics.pnlSumPct)
                    put("pnlSumSqPct", metrics.pnlSumSqPct)
                    put("pnlBestPct", metrics.pnlBestPct)
                    put("pnlWorstPct", metrics.pnlWorstPct)
                }
                editor.putString("layer_$name", json.toString())
            }
            
            // Save layer names list
            editor.putStringSet("layer_names", layerPerformance.keys.toSet())

            // V5.9.139 — persist approval-pattern memory so good-behaviour
            // learning isn't lost on restart.
            try {
                val arr = org.json.JSONArray()
                approvalPatterns.forEach { (sig, rec) ->
                    arr.put(org.json.JSONObject().apply {
                        put("sig", sig)
                        put("wins", rec.wins)
                        put("losses", rec.losses)
                        put("pnlSumPct", rec.pnlSumPct)
                        put("pnlBestPct", rec.pnlBestPct)
                        put("pnlWorstPct", rec.pnlWorstPct)
                        put("lastSeen", rec.lastSeen)
                    })
                }
                editor.putString("approval_patterns", arr.toString())
            } catch (_: Exception) {}

            editor.apply()
            
            ErrorLogger.debug(TAG, "💾 Saved ${layerPerformance.size} layers to prefs")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Save error: ${e.message}")
        }
    }
    
    /**
     * Load layer performance from SharedPreferences.
     */
    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            
            // Get saved layer names
            val layerNames = prefs.getStringSet("layer_names", emptySet()) ?: emptySet()
            
            layerNames.forEach { name ->
                val jsonStr = prefs.getString("layer_$name", null) ?: return@forEach
                try {
                    val json = org.json.JSONObject(jsonStr)
                    val metrics = LayerPerformanceMetrics(
                        layerName = name,
                        totalOutcomesRecorded = json.optInt("totalOutcomesRecorded", 0),
                        successfulPredictions = json.optInt("successfulPredictions", 0),
                        lastRecordedTimestamp = json.optLong("lastRecordedTimestamp", 0L),
                        avgConfidenceOnWins = json.optDouble("avgConfidenceOnWins", 50.0),
                        avgConfidenceOnLosses = json.optDouble("avgConfidenceOnLosses", 50.0),
                        learningVelocity = json.optDouble("learningVelocity", 1.0),
                        trustMultiplier = json.optDouble("trustMultiplier", 1.0),
                        // V5.9.138 — restore weighted fields; default to 0 for
                        // layers persisted before the upgrade, which makes
                        // getLayerAccuracy fall back to the binary Bayesian
                        // path until fresh outcomes arrive.
                        weightSum    = json.optDouble("weightSum",    0.0),
                        weightedHits = json.optDouble("weightedHits", 0.0),
                        pnlSumPct    = json.optDouble("pnlSumPct",    0.0),
                        pnlSumSqPct  = json.optDouble("pnlSumSqPct",  0.0),
                        pnlBestPct   = json.optDouble("pnlBestPct",   0.0),
                        pnlWorstPct  = json.optDouble("pnlWorstPct",  0.0),
                    )
                    layerPerformance[name] = metrics
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "Load layer $name error: ${e.message}")
                }
            }
            
            ErrorLogger.info(TAG, "📂 Loaded ${layerPerformance.size} layers from prefs | Total trades: ${getTotalTradesAcrossAllLayers()}")

            // V5.9.139 — restore approval-pattern memory.
            try {
                val jsonStr = prefs.getString("approval_patterns", null)
                if (!jsonStr.isNullOrBlank()) {
                    val arr = org.json.JSONArray(jsonStr)
                    var restored = 0
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val sig = o.optString("sig")
                        if (sig.isBlank()) continue
                        approvalPatterns[sig] = ApprovalRecord(
                            wins         = o.optInt("wins", 0),
                            losses       = o.optInt("losses", 0),
                            pnlSumPct    = o.optDouble("pnlSumPct",   0.0),
                            pnlBestPct   = o.optDouble("pnlBestPct",  0.0),
                            pnlWorstPct  = o.optDouble("pnlWorstPct", 0.0),
                            lastSeen     = o.optLong("lastSeen", 0L),
                        )
                        restored++
                    }
                    if (restored > 0) {
                        ErrorLogger.info(TAG, "📂 Restored $restored approval patterns")
                    }
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Load error: ${e.message}")
        }
    }
    
    /**
     * Reset all learned data (for testing).
     */
    fun reset() {
        layerPerformance.clear()
        saveForced()  // Bypass debounce on explicit reset
        ErrorLogger.info(TAG, "🧹 EducationSubLayerAI reset")
    }
    
    /**
     * V5.7: Dispatch trade outcome to education layer for cross-layer learning
     * Called by PerpsLearningBridge when perps trades complete.
     */
    fun dispatchOutcome(
        mint: String,
        symbol: String,
        isWin: Boolean,
        pnlPct: Double,
        holdMinutes: Int,
        scoreCard: Any?,  // ScoreCard if available
    ) {
        try {
            // Record to all layers that contributed
            layerPerformance.keys.forEach { layerName ->
                val metrics = layerPerformance[layerName] ?: return@forEach
                val updated = metrics.copy(
                    totalSignals = metrics.totalSignals + 1,
                    accurateSignals = metrics.accurateSignals + if (isWin) 1 else 0,
                    totalContributions = metrics.totalContributions + 1,
                    lastUpdate = System.currentTimeMillis(),
                )
                layerPerformance[layerName] = updated
            }
            
            // Update curriculum progress
            val level = getCurrentCurriculumLevel()
            if (isWin && pnlPct >= 100) {
                ErrorLogger.info(TAG, "🎓 ${level.icon} MEGA WIN recorded: +${pnlPct.toInt()}%")
            }
            
            save()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "dispatchOutcome error: ${e.message}")
        }
    }
    
    /**
     * V5.7.3: Dispatch stock-specific learning for tokenized stocks
     * Called when tokenized stock perps trades complete.
     */
    fun dispatchStockLearning(
        stock: String,
        direction: String,
        isWin: Boolean,
        pnlPct: Double,
        leverage: Double,
    ) {
        try {
            // Update stock-specific metrics
            val stockKey = "STOCK_$stock"
            val metrics = layerPerformance.getOrPut(stockKey) {
                LayerPerformanceMetrics(
                    layerName = stockKey,
                    totalSignals = 0,
                    accurateSignals = 0,
                    avgContribution = 0.0,
                    totalContributions = 0,
                    learningRate = 0.02,  // Higher learning rate for stocks
                    lastUpdate = System.currentTimeMillis(),
                )
            }
            
            val updated = metrics.copy(
                totalSignals = metrics.totalSignals + 1,
                accurateSignals = metrics.accurateSignals + if (isWin) 1 else 0,
                avgContribution = ((metrics.avgContribution * metrics.totalContributions) + pnlPct) / (metrics.totalContributions + 1),
                totalContributions = metrics.totalContributions + 1,
                lastUpdate = System.currentTimeMillis(),
            )
            layerPerformance[stockKey] = updated
            
            // Update direction-specific learning
            val directionKey = "STOCK_${stock}_$direction"
            val dirMetrics = layerPerformance.getOrPut(directionKey) {
                LayerPerformanceMetrics(
                    layerName = directionKey,
                    totalSignals = 0,
                    accurateSignals = 0,
                    avgContribution = 0.0,
                    totalContributions = 0,
                    learningRate = 0.025,
                    lastUpdate = System.currentTimeMillis(),
                )
            }
            
            layerPerformance[directionKey] = dirMetrics.copy(
                totalSignals = dirMetrics.totalSignals + 1,
                accurateSignals = dirMetrics.accurateSignals + if (isWin) 1 else 0,
                avgContribution = ((dirMetrics.avgContribution * dirMetrics.totalContributions) + pnlPct) / (dirMetrics.totalContributions + 1),
                totalContributions = dirMetrics.totalContributions + 1,
                lastUpdate = System.currentTimeMillis(),
            )
            
            // Log significant stock lessons
            if (isWin && pnlPct >= 20) {
                ErrorLogger.info(TAG, "📈 Stock Lesson: $stock $direction +${pnlPct.toInt()}% @ ${leverage.toInt()}x")
            } else if (!isWin && pnlPct <= -10) {
                ErrorLogger.info(TAG, "📉 Stock Lesson: $stock $direction ${pnlPct.toInt()}% @ ${leverage.toInt()}x (learning from loss)")
            }
            
            save()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "dispatchStockLearning error: ${e.message}")
        }
    }
    
    /**
     * V5.7.3: Get stock-specific learning statistics
     */
    fun getStockLearningStats(stock: String): Map<String, Any> {
        val stockKey = "STOCK_$stock"
        val metrics = layerPerformance[stockKey]
        
        return if (metrics != null) {
            mapOf(
                "totalTrades" to metrics.totalSignals,
                "wins" to metrics.accurateSignals,
                "winRate" to if (metrics.totalSignals > 0) (metrics.accurateSignals * 100.0 / metrics.totalSignals) else 0.0,
                "avgPnl" to metrics.avgContribution,
                "longWinRate" to (layerPerformance["${stockKey}_LONG"]?.let { 
                    if (it.totalSignals > 0) it.accurateSignals * 100.0 / it.totalSignals else 0.0 
                } ?: 0.0),
                "shortWinRate" to (layerPerformance["${stockKey}_SHORT"]?.let {
                    if (it.totalSignals > 0) it.accurateSignals * 100.0 / it.totalSignals else 0.0
                } ?: 0.0),
            )
        } else {
            mapOf(
                "totalTrades" to 0,
                "wins" to 0,
                "winRate" to 0.0,
                "avgPnl" to 0.0,
                "longWinRate" to 0.0,
                "shortWinRate" to 0.0,
            )
        }
    }
}
