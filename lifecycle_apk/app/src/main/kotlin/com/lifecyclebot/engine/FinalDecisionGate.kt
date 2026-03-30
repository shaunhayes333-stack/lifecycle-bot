package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.quant.EVCalculator
import com.lifecyclebot.v3.scoring.FluidLearningAI

/**
 * FinalDecisionGate (FDG)
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * @deprecated V3 ARCHITECTURE MIGRATION
 * This legacy gate is being replaced by the V3 scoring-based system:
 *   - FinalDecisionEngine (v3/decision/) - Score-based band selection
 *   - UnifiedScorer (v3/scoring/) - Modular scoring components
 *   - FatalRiskChecker (v3/risk/) - Only truly fatal blocks
 * 
 * The FDG currently runs IN PARALLEL with V3 for validation.
 * Once V3 is proven in production, this file will be removed.
 * 
 * MIGRATION STATUS: DEPRECATED - V3 is the future
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The SINGLE AUTHORITATIVE CHECKPOINT that ALL trades must pass before execution.
 * 
 * Flow:
 *   Scanner → Strategy → Edge → Safety → Learning → SmartSizer → ✅ FDG → Executor
 * 
 * HARD HIERARCHY (non-negotiable, order matters):
 *   1. HARD BLOCKS (rugcheck, extreme sell pressure, zero liq) → instant ❌
 *   2. EDGE veto (quality = SKIP) → ❌
 *   3. CONFIDENCE threshold → ❌ if below minimum
 *   4. MODE rules (paper vs live strictness)
 *   5. SIZING validation
 * 
 * ABSOLUTE RULE:
 *   if (blockReason != null) → shouldTrade = false
 *   NO EXCEPTIONS. Not even in paper mode. Not even for "learning".
 * 
 * If you want to learn from blocked trades:
 *   - Log them
 *   - Simulate them
 *   - DO NOT execute them
 */
@Deprecated(
    message = "V3 Architecture Migration: Use v3/decision/FinalDecisionEngine instead",
    replaceWith = ReplaceWith("FinalDecisionEngine", "com.lifecyclebot.v3.decision.FinalDecisionEngine"),
    level = DeprecationLevel.WARNING
)
object FinalDecisionGate {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FINAL DECISION - The canonical output all modules feed into
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class FinalDecision(
        val shouldTrade: Boolean,
        val mode: TradeMode,
        val approvalClass: ApprovalClass,  // LIVE, PAPER_BENCHMARK, PAPER_EXPLORATION, BLOCKED
        val quality: String,           // "A+", "A", "B", "C"
        val confidence: Double,        // 0-100%
        val edge: EdgeVerdict,
        val blockReason: String?,      // null = no block, string = blocked
        val blockLevel: BlockLevel?,   // How severe the block is
        val sizeSol: Double,
        val tags: List<String>,        // ["distribution", "early", "pump_fun", etc]
        val mint: String,
        val symbol: String,
        
        // Why this approval class was assigned
        val approvalReason: String,
        
        // Audit trail
        val gateChecks: List<GateCheck>,  // What checks were run
    ) {
        /**
         * ABSOLUTE RULE: Cannot trade if blocked.
         * This is redundant with shouldTrade but makes intent crystal clear.
         */
        fun canExecute(): Boolean = shouldTrade && blockReason == null
        
        /**
         * Is this a benchmark-quality trade that would pass live rules?
         */
        fun isBenchmarkQuality(): Boolean = approvalClass in listOf(ApprovalClass.LIVE, ApprovalClass.PAPER_BENCHMARK)
        
        /**
         * Is this an exploration trade for learning?
         */
        fun isExploration(): Boolean = approvalClass == ApprovalClass.PAPER_EXPLORATION
        
        fun summary(): String = buildString {
            append(if (shouldTrade) "✅" else "❌")
            append(" $symbol | $approvalClass | $quality | ${confidence.toInt()}% | ${edge.name}")
            if (blockReason != null) append(" | BLOCKED: $blockReason")
            if (shouldTrade) append(" | ${sizeSol.format(3)} SOL")
        }
    }
    
    enum class TradeMode {
        PAPER,      // Paper trading - executes FDG-approved trades
        LIVE,       // Live trading - strictest rules
    }
    
    enum class EdgeVerdict {
        STRONG,     // High quality setup
        WEAK,       // Acceptable but not great
        SKIP,       // Do not trade
    }
    
    enum class BlockLevel {
        HARD,       // Absolute block - rugcheck, zero liq, etc. NEVER bypass.
        EDGE,       // Edge optimizer says skip
        CONFIDENCE, // Below confidence threshold
        MODE,       // Mode-specific restriction
        SIZE,       // Position sizing issue
    }
    
    /**
     * APPROVAL CLASS - Explicit categorization for analytics separation
     * 
     * This enables clean reporting:
     *   - Benchmark stats: "How good is strategy under strict rules?"
     *   - Exploration stats: "What did we learn from weaker setups?"
     *   - Probe stats: "Controlled exploration despite soft warnings"
     */
    enum class ApprovalClass {
        LIVE,              // Live mode approval - strictest rules, real money
        PAPER_BENCHMARK,   // Paper mode, would PASS live rules - benchmark quality
        PAPER_EXPLORATION, // Paper mode, relaxed rules - learning from weaker setups
        PAPER_PROBE,       // Paper mode, soft warnings overridden for bootstrap learning
        BLOCKED,           // Not approved
    }
    
    /**
     * Check if this is a probe trade (controlled exploration despite warnings)
     */
    fun FinalDecision.isProbe(): Boolean = approvalClass == ApprovalClass.PAPER_PROBE
    
    data class GateCheck(
        val name: String,
        val passed: Boolean,
        val reason: String?,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLOSED-LOOP FEEDBACK SYSTEM (P2)
    // 
    // Self-representing agent: Real-time wallet performance influences AI confidence.
    // This creates a feedback loop where the bot "knows" when it's doing well or poorly.
    // 
    // Key constraints (user-specified architecture):
    //   1. DAMPING: Win rate changes influence confidence SLOWLY (EMA smoothing)
    //   2. LAGGING: Uses historical data, not real-time (prevents whipsawing)
    //   3. CONFIDENCE GOVERNOR: Caps max influence to ±15% (prevents runaway)
    //   4. MINIMUM TRADES: Requires 10+ trades before applying (prevents noise)
    // 
    // When winning (winRate > 55%): Confidence threshold DECREASES (more aggressive)
    // When losing (winRate < 45%): Confidence threshold INCREASES (more defensive)
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ClosedLoopFeedback(
        val lifetimeWinRate: Double = 50.0,    // From WalletState (not session)
        val lifetimeTrades: Int = 0,           // Total trades ever
        val smoothedWinRate: Double = 50.0,    // EMA-smoothed for damping
        val lastUpdateTime: Long = 0L,
    )
    
    // Current feedback state (updated by BotService when wallet refreshes)
    @Volatile private var closedLoopFeedback = ClosedLoopFeedback()
    
    // EMA smoothing factor: 0.1 = very slow adaptation (lag), 0.3 = faster
    private const val FEEDBACK_EMA_ALPHA = 0.15
    
    // Minimum trades before feedback applies
    private const val FEEDBACK_MIN_TRADES = 10
    
    // Maximum confidence adjustment from feedback (±15%)
    private const val FEEDBACK_MAX_ADJUSTMENT = 15.0
    
    /**
     * Update closed-loop feedback from wallet state.
     * Called by BotService when wallet stats refresh.
     * 
     * @param lifetimeWinRate Win rate from WalletState (0-100)
     * @param lifetimeTrades Total trades from WalletState
     */
    fun updateClosedLoopFeedback(lifetimeWinRate: Double, lifetimeTrades: Int) {
        val oldSmoothed = closedLoopFeedback.smoothedWinRate
        
        // Apply EMA smoothing for damping effect
        // New smoothed = alpha * new + (1-alpha) * old
        val newSmoothed = if (closedLoopFeedback.lastUpdateTime == 0L) {
            // First update: initialize to current value
            lifetimeWinRate
        } else {
            FEEDBACK_EMA_ALPHA * lifetimeWinRate + (1 - FEEDBACK_EMA_ALPHA) * oldSmoothed
        }
        
        closedLoopFeedback = ClosedLoopFeedback(
            lifetimeWinRate = lifetimeWinRate,
            lifetimeTrades = lifetimeTrades,
            smoothedWinRate = newSmoothed,
            lastUpdateTime = System.currentTimeMillis(),
        )
        
        // Log significant changes (more than 2% shift in smoothed rate)
        if (kotlin.math.abs(newSmoothed - oldSmoothed) > 2.0) {
            ErrorLogger.debug("FDG", "Closed-loop feedback: ${oldSmoothed.toInt()}% → ${newSmoothed.toInt()}% (raw: ${lifetimeWinRate.toInt()}%, ${lifetimeTrades} trades)")
        }
    }
    
    /**
     * Get confidence adjustment from closed-loop feedback.
     * Returns a value in range [-FEEDBACK_MAX_ADJUSTMENT, +FEEDBACK_MAX_ADJUSTMENT]
     * 
     * Positive = require MORE confidence (defensive)
     * Negative = require LESS confidence (aggressive)
     */
    fun getClosedLoopConfidenceAdjustment(): Double {
        val feedback = closedLoopFeedback
        
        // Don't apply until we have enough trades (prevents noise)
        if (feedback.lifetimeTrades < FEEDBACK_MIN_TRADES) {
            return 0.0
        }
        
        // Use smoothed win rate (damped, lagging)
        val smoothedRate = feedback.smoothedWinRate
        
        // Neutral zone: 45-55% = no adjustment
        // Below 45%: require more confidence (losing)
        // Above 55%: allow less confidence (winning)
        val adjustment = when {
            smoothedRate >= 65.0 -> -FEEDBACK_MAX_ADJUSTMENT      // Hot streak: -15%
            smoothedRate >= 60.0 -> -10.0                          // Very good: -10%
            smoothedRate >= 55.0 -> -5.0                           // Good: -5%
            smoothedRate <= 35.0 -> +FEEDBACK_MAX_ADJUSTMENT       // Cold streak: +15%
            smoothedRate <= 40.0 -> +10.0                          // Struggling: +10%
            smoothedRate <= 45.0 -> +5.0                           // Below average: +5%
            else -> 0.0                                             // Neutral zone: no adj
        }
        
        return adjustment.coerceIn(-FEEDBACK_MAX_ADJUSTMENT, FEEDBACK_MAX_ADJUSTMENT)
    }
    
    /**
     * Get feedback state for UI display
     */
    fun getClosedLoopState(): String {
        val feedback = closedLoopFeedback
        if (feedback.lifetimeTrades < FEEDBACK_MIN_TRADES) {
            return "INACTIVE (need ${FEEDBACK_MIN_TRADES - feedback.lifetimeTrades} more trades)"
        }
        val adj = getClosedLoopConfidenceAdjustment()
        val mode = when {
            adj < -5 -> "AGGRESSIVE"
            adj > 5 -> "DEFENSIVE"
            else -> "NEUTRAL"
        }
        return "$mode (${feedback.smoothedWinRate.toInt()}% smoothed, ${adj.toInt()}% adj)"
    }
    // 
    // FDG starts LOOSE and automatically tightens as the brain learns.
    // Thresholds scale based on:
    //   - Trade count (more trades = stricter)
    //   - Win rate (higher win rate = can be stricter)
    //   - Learning phase (bootstrap → learning → mature)
    // 
    // BOOTSTRAP (0-10 trades):   Very loose, maximum exploration
    // LEARNING (11-50 trades):   Gradually tightening
    // MATURE (50+ trades):       Full strictness based on learned patterns
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class LearningPhase {
        BOOTSTRAP,  // 0-10 trades - very loose
        LEARNING,   // 11-50 trades - gradually tightening  
        MATURE      // 50+ trades - use learned thresholds
    }
    
    fun getLearningPhase(tradeCount: Int): LearningPhase = when {
        tradeCount <= 50 -> LearningPhase.BOOTSTRAP   // 0-50: Very loose
        tradeCount <= 500 -> LearningPhase.LEARNING   // 51-500: Gradually tightening
        else -> LearningPhase.MATURE                   // 500+: Full strictness
    }
    
    /**
     * Calculate learning progress as 0.0 to 1.0
     * 0.0 = brand new (use loosest settings)
     * 1.0 = fully learned (use strictest settings)
     */
    fun getLearningProgress(tradeCount: Int, winRate: Double): Double {
        val tradeProgress = (tradeCount.toDouble() / 500.0).coerceIn(0.0, 1.0)
        val winRateBonus = if (winRate > 50.0) 0.1 else 0.0
        return (tradeProgress + winRateBonus).coerceIn(0.0, 1.0)
    }
    
    /**
     * Interpolate between loose (start) and strict (target) values based on learning progress
     */
    fun lerp(loose: Double, strict: Double, progress: Double): Double {
        return loose + (strict - loose) * progress
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLDS - Auto-adjust based on learning phase
    // 
    // These are the LOOSEST values (used during bootstrap)
    // They tighten automatically as the brain learns
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Hard block thresholds - LOOSE defaults (tighten as we learn)
    var hardBlockRugcheckMin = 5            // Bootstrap: 5, Mature: 10
    var hardBlockBuyPressureMin = 10.0      // Bootstrap: 10%, Mature: 18%
    var hardBlockTopHolderMax = 85.0        // Bootstrap: 85%, Mature: 65%
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PER-MODE KILL SWITCH (NEW)
    // 
    // Tracks recent losses by mode. If a mode accumulates 3+ losses within
    // the past hour, freeze it temporarily. Prevents bleeding from modes
    // that are currently not finding edge.
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ModeLossRecord(
        val mode: String,
        val timestamp: Long,
        val pnlPct: Double,
    )
    
    private val recentModeLosses = mutableListOf<ModeLossRecord>()
    private const val MODE_LOSS_WINDOW_MS = 60 * 60 * 1000L  // 1 hour
    private const val MODE_FREEZE_THRESHOLD = 3  // 3 losses = freeze
    
    /**
     * Record a loss for a specific mode (called by TradeHistoryStore on loss)
     */
    fun recordModeLoss(mode: String, pnlPct: Double) {
        if (pnlPct >= 0) return  // Only track losses
        
        synchronized(recentModeLosses) {
            recentModeLosses.add(ModeLossRecord(mode.uppercase(), System.currentTimeMillis(), pnlPct))
            
            // Prune old entries
            val cutoff = System.currentTimeMillis() - MODE_LOSS_WINDOW_MS
            recentModeLosses.removeAll { it.timestamp < cutoff }
        }
        
        ErrorLogger.debug("FDG", "Mode loss recorded: $mode (${pnlPct}%) | Recent: ${getModeRecentLosses(mode)} losses")
    }
    
    /**
     * Get count of recent losses for a mode (within last hour)
     */
    fun getModeRecentLosses(mode: String): Int {
        val cutoff = System.currentTimeMillis() - MODE_LOSS_WINDOW_MS
        val normalizedMode = mode.uppercase()
        
        return synchronized(recentModeLosses) {
            recentModeLosses.count { 
                it.timestamp >= cutoff && 
                (it.mode.contains(normalizedMode) || normalizedMode.contains(it.mode))
            }
        }
    }
    
    /**
     * Check if a mode is currently frozen
     */
    fun isModeFrozen(mode: String): Boolean {
        return getModeRecentLosses(mode) >= MODE_FREEZE_THRESHOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EARLY SNIPE MODE (NEW)
    // 
    // For newly discovered tokens with high scores, bypass most checks and 
    // enter FAST. Meme coins pump in minutes - by the time all checks pass,
    // the opportunity is gone.
    //
    // Criteria for EARLY SNIPE:
    //   - Token age < 15 min (fresh discovery)
    //   - Initial score >= 60 (quality filter pass)
    //   - Liquidity >= $2000 (minimum tradeable)
    //   - NOT in banned list / rugcheck pass
    //
    // When EARLY SNIPE triggers:
    //   - Skip Edge veto
    //   - Skip confidence threshold (use hard minimum only)
    //   - Use small position size (risk management)
    // ═══════════════════════════════════════════════════════════════════════════
    var earlySnipeEnabled = true           // Enable aggressive early entries
    var earlySnipeMaxAgeMinutes = 20       // INCREASED - wider time window
    var earlySnipeMinScore = 45.0          // LOWERED from 60 - more aggressive
    var earlySnipeMinLiquidity = 1500.0    // LOWERED from $2000 - more aggressive
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXPECTED VALUE (EV) GATING
    // 
    // Only execute trades with positive expected value.
    // EV = Σ (P_i × Outcome_i) for probability-weighted scenarios.
    // 
    // This is the mathematical foundation for profitable trading:
    //   - Calculate probability of each outcome (moon, win, loss, rug)
    //   - Weight by expected return for each scenario
    //   - Only trade if EV > 1.0 (positive expectancy)
    //   - Use Kelly Criterion for optimal position sizing
    // ═══════════════════════════════════════════════════════════════════════════
    var evGatingEnabled = false            // DISABLED until brain has more data
    var minExpectedValue = 1.0             // LOWERED - any positive EV
    var maxRugProbability = 0.30           // RAISED from 15% to 30% - more tolerance
    var useKellySizing = false             // DISABLED - use simple sizing for now
    var kellyFraction = 0.5                // Fraction of Kelly to use (half-Kelly)
    var maxKellySize = 0.10                // Maximum Kelly-suggested size (10%)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID CONFIDENCE SCALING (All Trading Modes)
    // 
    // PHILOSOPHY: Start LOOSE to maximize learning exposure on first install.
    // ALL modes (paper, live, all trading strategies) use the SAME floor on
    // day 1, then scale up as the AI matures through trade experience.
    //
    // Bootstrap (0 trades):   30% confidence floor (FDG hard minimum)
    // Mature (500+ trades):   75% confidence (strict filtering)
    //
    // This allows the bot to learn from a wide variety of trades initially,
    // then naturally becomes more selective as it understands what works.
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val CONF_FLOOR_BOOTSTRAP = 30.0    // Starting floor (same as hard minimum)
    private const val CONF_FLOOR_MATURE = 75.0       // Target floor after learning
    
    // Base confidence thresholds (LEGACY - now uses fluid scaling)
    var paperConfidenceBase = 15.0          // Paper mode: 15% minimum (don't learn from garbage)
    var liveConfidenceBase = 0.0           // ZEROED - let trades flow while brain learns (auto-adjusts)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ADAPTIVE FILTERING (Anti-Freeze Protection)
    // 
    // If too many trades get blocked consecutively, we risk:
    //   1. Missing good opportunities (over-filtering)
    //   2. Not learning from any trades (stalled brain)
    //   3. Complete trading freeze (useless bot)
    //
    // Solution: After N consecutive blocks, temporarily loosen soft restrictions:
    //   - DANGER_ZONE_TIME: Bypass after 8 consecutive blocks
    //   - MEMORY_NEGATIVE: Bypass after 10 consecutive blocks
    //   - 100% Loss patterns: NEVER bypass (truly toxic)
    //   - HARD blocks (rugcheck, etc): NEVER bypass
    //
    // The relaxation is TEMPORARY - resets after a trade executes.
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var consecutiveBlockCount = 0
    private var lastBlockReason: String? = null
    private var adaptiveRelaxationActive = false
    
    // Thresholds for adaptive relaxation
    private const val DANGER_ZONE_BYPASS_THRESHOLD = 8    // Blocks before bypassing time filter
    private const val MEMORY_BYPASS_THRESHOLD = 10        // Blocks before bypassing memory filter
    private const val MAX_RELAXATION_TRADES = 3           // Max trades with relaxed filters before reset
    private var relaxationTradesUsed = 0
    
    /**
     * Record a blocked trade for adaptive filtering.
     * Call this when FDG blocks a trade.
     */
    fun recordBlock(reason: String) {
        consecutiveBlockCount++
        lastBlockReason = reason
        
        // Check if we should activate adaptive relaxation
        if (consecutiveBlockCount >= DANGER_ZONE_BYPASS_THRESHOLD && !adaptiveRelaxationActive) {
            adaptiveRelaxationActive = true
            relaxationTradesUsed = 0
            ErrorLogger.warn("FDG", "🔓 ADAPTIVE RELAXATION ACTIVATED after $consecutiveBlockCount consecutive blocks")
        }
    }
    
    /**
     * Record a successful trade (not blocked).
     * Resets the consecutive block counter.
     */
    fun recordTradeExecuted() {
        if (adaptiveRelaxationActive) {
            relaxationTradesUsed++
            if (relaxationTradesUsed >= MAX_RELAXATION_TRADES) {
                // Deactivate relaxation after max trades
                adaptiveRelaxationActive = false
                relaxationTradesUsed = 0
                ErrorLogger.info("FDG", "🔒 Adaptive relaxation deactivated (used $MAX_RELAXATION_TRADES relaxed trades)")
            }
        }
        consecutiveBlockCount = 0
        lastBlockReason = null
    }
    
    /**
     * Check if a specific soft block should be bypassed due to adaptive filtering.
     */
    fun shouldBypassSoftBlock(blockType: String): Boolean {
        if (!adaptiveRelaxationActive) return false
        
        return when (blockType) {
            "DANGER_ZONE_TIME" -> consecutiveBlockCount >= DANGER_ZONE_BYPASS_THRESHOLD
            "MEMORY_NEGATIVE_BLOCK" -> consecutiveBlockCount >= MEMORY_BYPASS_THRESHOLD
            // NEVER bypass these:
            // - 100% loss patterns (BEHAVIOR_BLOCK_100PCT_LOSS)
            // - Hard blocks (rugcheck, zero liq, sell pressure, etc)
            else -> false
        }
    }
    
    /**
     * Get adaptive filtering status for logging.
     */
    fun getAdaptiveFilterStatus(): String {
        return if (adaptiveRelaxationActive) {
            "🔓 RELAXED (blocks=$consecutiveBlockCount, used=$relaxationTradesUsed/$MAX_RELAXATION_TRADES)"
        } else {
            "🔒 STRICT (blocks=$consecutiveBlockCount)"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-ADJUSTED THRESHOLDS
    // 
    // These are calculated dynamically based on learning progress.
    // Call getAdjustedThresholds() to get current values.
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class AdjustedThresholds(
        val learningPhase: LearningPhase,
        val progress: Double,              // 0.0 to 1.0
        val tradeCount: Int,
        val winRate: Double,
        // Adjusted values
        val rugcheckMin: Int,
        val buyPressureMin: Double,
        val topHolderMax: Double,
        val confidenceBase: Double,
        val edgeMinBuyPressure: Double,
        val edgeMinLiquidity: Double,
        val edgeMinScore: Double,
        val evGatingEnabled: Boolean,
        val maxRugProbability: Double,
    )
    
    /**
     * Get auto-adjusted thresholds based on learning progress.
     * 
     * Bootstrap (0-10 trades): Very loose - maximum exploration
     * Learning (11-50 trades): Gradually tightening
     * Mature (50+ trades): Strict - use learned optimal values
     */
    fun getAdjustedThresholds(tradeCount: Int, winRate: Double): AdjustedThresholds {
        val phase = getLearningPhase(tradeCount)
        val progress = getLearningProgress(tradeCount, winRate)
        
        return AdjustedThresholds(
            learningPhase = phase,
            progress = progress,
            tradeCount = tradeCount,
            winRate = winRate,
            // Hard blocks: loose → strict
            rugcheckMin = lerp(5.0, 12.0, progress).toInt(),
            buyPressureMin = lerp(10.0, 20.0, progress),
            topHolderMax = lerp(85.0, 60.0, progress),
            // Confidence: 0% → 15%
            confidenceBase = lerp(0.0, 15.0, progress),
            // Edge veto: very loose → strict
            edgeMinBuyPressure = lerp(38.0, 52.0, progress),
            edgeMinLiquidity = lerp(1500.0, 4000.0, progress),
            edgeMinScore = lerp(20.0, 40.0, progress),
            // EV gating: disabled until mature
            evGatingEnabled = phase == LearningPhase.MATURE,
            maxRugProbability = lerp(0.35, 0.12, progress),
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ADAPTIVE CONFIDENCE LAYER
    // 
    // Fluid confidence threshold that adapts to market conditions.
    // Instead of static thresholds, confidence requirements adjust based on:
    //   1. Market Volatility (higher volatility = require more confidence)
    //   2. Recent Win Rate (hot streak = can accept lower confidence)
    //   3. Buy Pressure Trend (strong buyers = can accept lower confidence)
    //   4. Time Since Last Loss (recent loss = require more confidence)
    //   5. Session Performance (profitable session = can be more aggressive)
    // 
    // This applies to BOTH paper and live modes, making the bot smarter overall.
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class MarketConditions(
        val avgVolatility: Double = 5.0,      // Average price swing % (ATR-like)
        val buyPressureTrend: Double = 50.0,  // Rolling average buy pressure
        val recentWinRate: Double = 50.0,     // Win rate over last N trades
        val timeSinceLastLossMs: Long = Long.MAX_VALUE,
        val sessionPnlPct: Double = 0.0,      // Session P&L percentage
        val totalSessionTrades: Int = 0,
        val historicalTradeCount: Int = 0,    // LIFETIME trades (for learning progress)
        // LEARNING LAYER DATA
        val entryAiWinRate: Double = 50.0,    // EntryIntelligence learned win rate
        val exitAiAvgPnl: Double = 0.0,       // ExitIntelligence average P&L
        val edgeLearningAccuracy: Double = 50.0, // EdgeLearning veto accuracy
    )
    
    // Current market conditions (updated by BotService)
    private var currentConditions = MarketConditions()
    
    /**
     * Update market conditions - called periodically by BotService
     */
    fun updateMarketConditions(
        avgVolatility: Double? = null,
        buyPressureTrend: Double? = null,
        recentWinRate: Double? = null,
        timeSinceLastLossMs: Long? = null,
        sessionPnlPct: Double? = null,
        totalSessionTrades: Int? = null,
        historicalTradeCount: Int? = null,
        entryAiWinRate: Double? = null,
        exitAiAvgPnl: Double? = null,
        edgeLearningAccuracy: Double? = null,
    ) {
        currentConditions = currentConditions.copy(
            avgVolatility = avgVolatility ?: currentConditions.avgVolatility,
            buyPressureTrend = buyPressureTrend ?: currentConditions.buyPressureTrend,
            recentWinRate = recentWinRate ?: currentConditions.recentWinRate,
            timeSinceLastLossMs = timeSinceLastLossMs ?: currentConditions.timeSinceLastLossMs,
            sessionPnlPct = sessionPnlPct ?: currentConditions.sessionPnlPct,
            totalSessionTrades = totalSessionTrades ?: currentConditions.totalSessionTrades,
            historicalTradeCount = historicalTradeCount ?: currentConditions.historicalTradeCount,
            entryAiWinRate = entryAiWinRate ?: currentConditions.entryAiWinRate,
            exitAiAvgPnl = exitAiAvgPnl ?: currentConditions.exitAiAvgPnl,
            edgeLearningAccuracy = edgeLearningAccuracy ?: currentConditions.edgeLearningAccuracy,
        )
    }
    
    /**
     * Calculate adaptive confidence threshold based on market conditions.
     * Returns the EFFECTIVE minimum confidence required for a trade.
     * 
     * This is a FLUID layer that makes the bot smarter:
     *   - When conditions are favorable: lower threshold = more trades
     *   - When conditions are risky: higher threshold = fewer, safer trades
     */
    fun getAdaptiveConfidence(isPaperMode: Boolean, ts: TokenState? = null): Double {
        // ═══════════════════════════════════════════════════════════════════
        // FLUID CONFIDENCE: ALL modes start at CONF_FLOOR_BOOTSTRAP (30%)
        // and scale up to CONF_FLOOR_MATURE (75%) as the AI learns.
        //
        // This ensures maximum learning exposure on first install while
        // naturally tightening as confidence in the system grows.
        // ═══════════════════════════════════════════════════════════════════
        
        val learningProgress = getLearningProgress(
            currentConditions.totalSessionTrades + currentConditions.historicalTradeCount,
            currentConditions.recentWinRate
        )
        
        // Fluid base confidence: 30% (bootstrap) → 75% (mature)
        val fluidBase = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE, learningProgress)
        
        // PAPER MODE: Use even lower floor to maximize learning
        // Paper can go as low as 15% (paperConfidenceBase) during relaxation
        if (isPaperMode) {
            val floor = lerp(paperConfidenceBase, CONF_FLOOR_MATURE * 0.6, learningProgress) // 15% → 45%
            
            // Adaptive relaxation: If many blocks, lower the floor slightly
            val adaptiveFloor = if (adaptiveRelaxationActive) {
                (floor * 0.5).coerceAtLeast(5.0)  // Minimum during relaxation
            } else {
                floor
            }
            
            ErrorLogger.debug("FDG", "📊 FLUID CONF (PAPER): floor=${adaptiveFloor.toInt()}% | " +
                "learning=${(learningProgress*100).toInt()}%")
            
            return adaptiveFloor
        }
        
        // LIVE MODE: Start at same floor as paper, scale with learning
        val baseConfidence = fluidBase
        var adjustment = 0.0
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 1: VOLATILITY ADJUSTMENT
        // High volatility = need more confidence (riskier environment)
        // Low volatility = can accept lower confidence (stable market)
        // ─────────────────────────────────────────────────────────────────
        val volatilityAdj = when {
            currentConditions.avgVolatility >= 15.0 -> +10.0  // Very volatile: require +10% confidence
            currentConditions.avgVolatility >= 10.0 -> +5.0   // Volatile: +5%
            currentConditions.avgVolatility >= 5.0  -> 0.0    // Normal
            currentConditions.avgVolatility >= 2.0  -> -5.0   // Calm: accept -5% confidence
            else -> -8.0                                       // Very calm: -8%
        }
        adjustment += volatilityAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 2: WIN RATE MOMENTUM
        // Hot streak = can be more aggressive
        // Cold streak = need more confidence
        // ─────────────────────────────────────────────────────────────────
        val winRateAdj = if (currentConditions.totalSessionTrades >= 5) {
            when {
                currentConditions.recentWinRate >= 70.0 -> -10.0  // Hot streak: -10%
                currentConditions.recentWinRate >= 60.0 -> -5.0   // Winning: -5%
                currentConditions.recentWinRate >= 50.0 -> 0.0    // Average
                currentConditions.recentWinRate >= 40.0 -> +5.0   // Losing: +5%
                else -> +10.0                                      // Cold streak: +10%
            }
        } else 0.0  // Not enough data yet
        adjustment += winRateAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 3: BUY PRESSURE TREND
        // Strong buyers overall = favorable market
        // Weak buyers = need more conviction per trade
        // ─────────────────────────────────────────────────────────────────
        val buyPressureAdj = when {
            currentConditions.buyPressureTrend >= 65.0 -> -5.0   // Strong buyers: -5%
            currentConditions.buyPressureTrend >= 55.0 -> -2.0   // Good buyers: -2%
            currentConditions.buyPressureTrend >= 45.0 -> 0.0    // Neutral
            currentConditions.buyPressureTrend >= 35.0 -> +5.0   // Weak buyers: +5%
            else -> +10.0                                         // Sellers dominate: +10%
        }
        adjustment += buyPressureAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 4: RECENCY OF LAST LOSS
        // Just lost = be more careful
        // Long time since loss = can be more aggressive
        // ─────────────────────────────────────────────────────────────────
        val timeSinceLossMinutes = currentConditions.timeSinceLastLossMs / 60_000
        val lossRecencyAdj = when {
            timeSinceLossMinutes < 5   -> +8.0    // Just lost (< 5 min): +8%
            timeSinceLossMinutes < 15  -> +4.0    // Recent loss (< 15 min): +4%
            timeSinceLossMinutes < 60  -> +2.0    // Loss this hour: +2%
            timeSinceLossMinutes < 240 -> 0.0     // Normal
            else -> -3.0                           // Long win period: -3%
        }
        adjustment += lossRecencyAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 5: SESSION PERFORMANCE
        // Profitable session = can push a bit more
        // Losing session = protect capital
        // ─────────────────────────────────────────────────────────────────
        val sessionPnlAdj = if (currentConditions.totalSessionTrades >= 3) {
            when {
                currentConditions.sessionPnlPct >= 20.0 -> -5.0   // Very profitable: -5%
                currentConditions.sessionPnlPct >= 10.0 -> -3.0   // Profitable: -3%
                currentConditions.sessionPnlPct >= 0.0  -> 0.0    // Breakeven+
                currentConditions.sessionPnlPct >= -10.0 -> +5.0  // Small loss: +5%
                else -> +10.0                                      // Big loss: +10%
            }
        } else 0.0
        adjustment += sessionPnlAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 6: TOKEN-SPECIFIC (if provided)
        // Adjust based on the specific token's characteristics
        // ─────────────────────────────────────────────────────────────────
        val tokenAdj = if (ts != null) {
            var adj = 0.0
            // High liquidity = safer, can accept lower confidence
            if (ts.lastLiquidityUsd >= 100_000) adj -= 3.0
            else if (ts.lastLiquidityUsd < 10_000) adj += 5.0
            
            // Strong current buy pressure on this token
            if (ts.meta.pressScore >= 65) adj -= 3.0
            else if (ts.meta.pressScore < 45) adj += 5.0
            
            adj
        } else 0.0
        adjustment += tokenAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 7: TREASURY & SCALING TIER ADJUSTMENT
        // 
        // Higher treasury = profits are protected = can be more aggressive
        // Higher scaling tier = larger positions = need more confidence
        // ─────────────────────────────────────────────────────────────────
        val treasuryAdj = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val scalingTier = ScalingMode.activeTier(treasuryUsd)
            
            when (scalingTier) {
                // Higher tiers have profits locked = can afford to be more aggressive
                ScalingMode.Tier.INSTITUTIONAL -> -8.0  // $100K+ treasury: much more aggressive
                ScalingMode.Tier.SCALED        -> -5.0  // $25K+ treasury: more aggressive
                ScalingMode.Tier.GROWTH        -> -3.0  // $5K+ treasury: slightly aggressive
                ScalingMode.Tier.STANDARD      -> 0.0   // $500+ treasury: neutral
                ScalingMode.Tier.MICRO         -> +3.0  // <$500: be careful, small stack
            }
        } catch (_: Exception) { 0.0 }
        adjustment += treasuryAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 8: LEARNING LAYER INFLUENCE
        // 
        // The AI learning systems (Entry/Exit/Edge) provide feedback:
        // - High EntryAI win rate = entries are good = can be more aggressive
        // - High ExitAI avg P&L = exits are optimized = more confident in holds
        // - High Edge accuracy = vetoes are correct = trust the system more
        // 
        // CRITICAL: During "bootstrap" phase (< 30 trades), AI layers are 
        // UNRELIABLE. Their low win rate is from learning, not from bad 
        // strategy. We CAP negative adjustments during bootstrap.
        // ─────────────────────────────────────────────────────────────────
        val isBootstrapPhase = currentConditions.totalSessionTrades < 30  // Need 30+ trades before trusting AI
        
        val learningAdj = if (currentConditions.totalSessionTrades >= 10) {
            var adj = 0.0
            
            // EntryAI learned win rate influence
            when {
                currentConditions.entryAiWinRate >= 60.0 -> adj -= 5.0  // Great entries → more aggressive
                currentConditions.entryAiWinRate >= 50.0 -> adj -= 2.0  // Good entries
                currentConditions.entryAiWinRate >= 40.0 -> adj += 0.0  // Average
                currentConditions.entryAiWinRate >= 30.0 -> adj += 3.0  // Below average
                else -> adj += 6.0                                       // Poor entries → be cautious
            }
            
            // ExitAI average P&L influence
            when {
                currentConditions.exitAiAvgPnl >= 20.0 -> adj -= 4.0   // Exits capturing 20%+ avg → aggressive
                currentConditions.exitAiAvgPnl >= 10.0 -> adj -= 2.0   // Good exits
                currentConditions.exitAiAvgPnl >= 0.0  -> adj += 0.0   // Breakeven+
                else -> adj += 4.0                                      // Negative avg → cautious
            }
            
            // Edge learning accuracy influence
            when {
                currentConditions.edgeLearningAccuracy >= 70.0 -> adj -= 3.0  // Edge vetoes are very accurate
                currentConditions.edgeLearningAccuracy >= 55.0 -> adj -= 1.0  // Good accuracy
                currentConditions.edgeLearningAccuracy >= 45.0 -> adj += 0.0  // Neutral
                else -> adj += 3.0                                             // Edge needs more learning
            }
            
            // BOOTSTRAP PROTECTION: Cap positive adjustments (raising threshold)
            // when AI is still learning. This prevents blocking trades due to 
            // unreliable early learning data.
            if (isBootstrapPhase && adj > 0) {
                adj = (adj * 0.3).coerceAtMost(3.0)  // Max +3% during bootstrap
            }
            
            adj
        } else 0.0  // Not enough trades for learning influence
        adjustment += learningAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 9: MARKET REGIME AI INFLUENCE
        // 
        // MarketRegimeAI detects bull/bear/crab market conditions.
        // Adjust confidence based on overall market state:
        //   - Bull market: can be more aggressive (lower confidence needed)
        //   - Bear market: need more confidence (riskier environment)
        //   - High volatility: need more confidence
        // ─────────────────────────────────────────────────────────────────
        val regimeAdj = try {
            val regime = MarketRegimeAI.getCurrentRegime()
            val regimeConfidence = MarketRegimeAI.getRegimeConfidence()
            
            // Only apply if regime detection has reasonable confidence
            if (regimeConfidence >= 40.0) {
                when (regime) {
                    MarketRegimeAI.Regime.STRONG_BULL -> -8.0   // Very bullish: more aggressive
                    MarketRegimeAI.Regime.BULL -> -4.0          // Bullish: slightly aggressive
                    MarketRegimeAI.Regime.NEUTRAL -> 0.0        // Neutral
                    MarketRegimeAI.Regime.CRAB -> +3.0          // Choppy: slightly cautious
                    MarketRegimeAI.Regime.BEAR -> +6.0          // Bearish: cautious
                    MarketRegimeAI.Regime.STRONG_BEAR -> +10.0  // Very bearish: very cautious
                    MarketRegimeAI.Regime.HIGH_VOLATILITY -> +5.0 // Volatile: cautious
                }
            } else 0.0
        } catch (_: Exception) { 0.0 }
        adjustment += regimeAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 10: WHALE TRACKER AI INFLUENCE (Token-specific)
        // 
        // If whales are accumulating a token = bullish signal
        // If whales are distributing = bearish signal
        // This is checked per-token in the evaluate function
        // ─────────────────────────────────────────────────────────────────
        val whaleAdj = if (ts != null) {
            try {
                val whaleSignal = WhaleTrackerAI.getWhaleSignal(ts.mint, ts.symbol)
                when (whaleSignal.recommendation) {
                    "STRONG_BUY" -> -6.0    // Whales accumulating: aggressive
                    "BUY" -> -3.0           // Whales buying: slightly aggressive
                    "LEAN_BUY" -> -1.0      // Single whale buying
                    "NEUTRAL" -> 0.0
                    "LEAN_SELL" -> +2.0     // Single whale selling: cautious
                    "SELL" -> +5.0          // Whales selling: more cautious
                    "STRONG_SELL" -> +10.0  // Whales dumping: very cautious
                    else -> 0.0
                }
            } catch (_: Exception) { 0.0 }
        } else 0.0
        adjustment += whaleAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 11: MOMENTUM PREDICTOR AI INFLUENCE (Token-specific)
        // 
        // If momentum AI predicts a pump = bullish signal
        // If momentum AI predicts distribution = bearish signal
        // ─────────────────────────────────────────────────────────────────
        val momentumAdj = if (ts != null) {
            try {
                val prediction = MomentumPredictorAI.getPrediction(ts.mint)
                when (prediction) {
                    MomentumPredictorAI.MomentumPrediction.STRONG_PUMP -> -5.0    // Pump predicted: aggressive
                    MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING -> -2.0  // Building momentum
                    MomentumPredictorAI.MomentumPrediction.NEUTRAL -> 0.0
                    MomentumPredictorAI.MomentumPrediction.WEAK -> +3.0           // Weak momentum: cautious
                    MomentumPredictorAI.MomentumPrediction.DISTRIBUTION -> +8.0   // Distribution: very cautious
                }
            } catch (_: Exception) { 0.0 }
        } else 0.0
        adjustment += momentumAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 12: NARRATIVE DETECTOR AI INFLUENCE (Token-specific)
        // 
        // Hot narratives (AI, political, etc.) = more aggressive
        // Cold narratives = more cautious
        // ─────────────────────────────────────────────────────────────────
        val narrativeAdj = if (ts != null) {
            try {
                val adj = NarrativeDetectorAI.getEntryScoreAdjustment(ts.symbol, ts.name)
                // Convert entry score adj to confidence adj (opposite direction)
                // Hot narrative = lower confidence needed (negative adj)
                (-adj * 0.5).coerceIn(-6.0, 6.0)
            } catch (_: Exception) { 0.0 }
        } else 0.0
        adjustment += narrativeAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 13: TIME OPTIMIZATION AI INFLUENCE
        // 
        // Golden hours = more aggressive (historically profitable)
        // Danger zones = more cautious (historically unprofitable)
        // ─────────────────────────────────────────────────────────────────
        val timeAdj = try {
            val adj = TimeOptimizationAI.getEntryScoreAdjustment()
            // Convert entry score adj to confidence adj (opposite direction)
            (-adj * 0.4).coerceIn(-5.0, 5.0)
        } catch (_: Exception) { 0.0 }
        adjustment += timeAdj
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 14: LIQUIDITY DEPTH AI INFLUENCE
        // 
        // Growing liquidity = more aggressive (project is healthy)
        // Draining liquidity = more cautious (potential rug)
        // Note: Uses generic adjustment since we don't have specific token info here
        // ─────────────────────────────────────────────────────────────────
        // (Liquidity AI is integrated at token-level in shouldApprove method)
        
        // ─────────────────────────────────────────────────────────────────
        // FACTOR 15: CLOSED-LOOP FEEDBACK (P2 - Self-Representing Agent)
        // 
        // The wallet's LIFETIME performance influences confidence:
        // - Winning consistently = can be more aggressive
        // - Losing consistently = need to be more defensive
        // 
        // Uses EMA smoothing (damping) and historical data (lagging)
        // to prevent whipsawing. Capped at ±15% (governor).
        // ─────────────────────────────────────────────────────────────────
        val closedLoopAdj = getClosedLoopConfidenceAdjustment()
        adjustment += closedLoopAdj
        
        // ─────────────────────────────────────────────────────────────────
        // CALCULATE FINAL ADAPTIVE CONFIDENCE
        // 
        // FLUID SCALING: The base confidence (30% → 75%) already incorporates
        // learning progress. During early bootstrap, adjustments are capped
        // to prevent blocking trades from unreliable early data.
        //
        // NEW APPROACH: ALL modes start at the same low floor (~30%) for
        // maximum learning exposure, then naturally tighten as AI matures.
        // ─────────────────────────────────────────────────────────────────
        val isBootstrap = learningProgress < 0.1  // < 10% learned = bootstrap
        
        // During bootstrap, cap positive adjustments (raising threshold)
        // to prevent blocking trades due to unreliable early data
        val cappedAdjustment = if (isBootstrap && adjustment > 0) {
            adjustment.coerceAtMost(5.0)  // Max +5% during bootstrap
        } else {
            adjustment
        }
        
        // Fluid minimum: scales with learning progress
        val fluidMinimum = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE * 0.5, learningProgress)
            .coerceIn(CONF_FLOOR_BOOTSTRAP, 50.0)  // 30% → 37.5% as AI matures
        
        val adaptive = (baseConfidence + cappedAdjustment).coerceIn(
            fluidMinimum,   // Fluid minimum based on learning
            CONF_FLOOR_MATURE + 5.0   // Max 80% 
        )
        
        ErrorLogger.debug("FDG", "📊 FLUID CONF (LIVE): base=${baseConfidence.toInt()}% adj=${cappedAdjustment.toInt()}% " +
            "final=${adaptive.toInt()}% | learning=${(learningProgress*100).toInt()}% | bootstrap=$isBootstrap")
        
        return adaptive
    }
    
    /**
     * Get explanation of current adaptive confidence calculation.
     * Useful for logging/debugging.
     */
    fun getAdaptiveConfidenceExplanation(isPaperMode: Boolean): String {
        val totalTrades = currentConditions.totalSessionTrades + currentConditions.historicalTradeCount
        val learningProgress = getLearningProgress(totalTrades, currentConditions.recentWinRate)
        val fluidBase = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE, learningProgress)
        val adaptive = getAdaptiveConfidence(isPaperMode)
        val diff = adaptive - fluidBase
        val sign = if (diff >= 0) "+" else ""
        val isBootstrap = learningProgress < 0.1
        
        // Get treasury tier for logging
        val tierLabel = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            tier.label
        } catch (_: Exception) { "?" }
        
        // Get market regime for logging
        val regimeLabel = try {
            MarketRegimeAI.getCurrentRegime().label
        } catch (_: Exception) { "?" }
        
        return buildString {
            append("FluidConf: base=${fluidBase.toInt()}% ${sign}${diff.toInt()}% = ${adaptive.toInt()}% ")
            append("[learning=${(learningProgress*100).toInt()}%${if (isBootstrap) " BOOTSTRAP" else ""} ")
            append("vol=${currentConditions.avgVolatility.toInt()}% ")
            append("wr=${currentConditions.recentWinRate.toInt()}% ")
            append("buy=${currentConditions.buyPressureTrend.toInt()}% ")
            append("tier=$tierLabel ")
            append("regime=$regimeLabel ")
            append("loop=${getClosedLoopState()} ")
            append("trades=$totalTrades]")
        }
    }
    
    /**
     * Get current fluid confidence state for UI display.
     * Shows learning progress and current thresholds.
     */
    fun getFluidConfidenceInfo(): FluidConfidenceState {
        val totalTrades = currentConditions.totalSessionTrades + currentConditions.historicalTradeCount
        val learningProgress = getLearningProgress(totalTrades, currentConditions.recentWinRate)
        val paperConf = getAdaptiveConfidence(isPaperMode = true)
        val liveConf = getAdaptiveConfidence(isPaperMode = false)
        
        return FluidConfidenceState(
            learningProgressPct = (learningProgress * 100).toInt(),
            totalTradesLearned = totalTrades,
            paperConfThreshold = paperConf.toInt(),
            liveConfThreshold = liveConf.toInt(),
            isBootstrap = learningProgress < 0.1,
            fluidBase = lerp(CONF_FLOOR_BOOTSTRAP, CONF_FLOOR_MATURE, learningProgress).toInt()
        )
    }
    
    data class FluidConfidenceState(
        val learningProgressPct: Int,       // 0-100% AI maturity
        val totalTradesLearned: Int,        // Lifetime trade count
        val paperConfThreshold: Int,        // Current paper mode threshold
        val liveConfThreshold: Int,         // Current live mode threshold
        val isBootstrap: Boolean,           // True if < 10% learned
        val fluidBase: Int,                 // Current base before adjustments
    ) {
        fun summary(): String = buildString {
            append("🧠 AI Learning: ${learningProgressPct}% ")
            append("($totalTradesLearned trades) | ")
            append("Conf: Paper≥${paperConfThreshold}% Live≥${liveConfThreshold}% ")
            if (isBootstrap) append("[BOOTSTRAP]")
        }
    }
    
    // PAPER MODE LEARNING: Allow edge overrides so bot can learn from trades
    var allowEdgeOverrideInPaper = false   // NO MORE EDGE OVERRIDE - causes garbage data
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DISTRIBUTION COOLDOWN TRACKER
    // 
    // When a token exits due to distribution, block it from being bought again
    // for a cooldown period. This prevents the buy→dump→buy→dump loop.
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val distributionCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val DISTRIBUTION_COOLDOWN_MS_PAPER = 20 * 1000L  // 20 seconds for paper - fast learning
    private const val DISTRIBUTION_COOLDOWN_MS_LIVE = 60 * 1000L   // 60 seconds for live - cautious but not excessive
    
    /**
     * Record that a token was closed due to distribution.
     * Call this from Executor when a trade exits with distribution reason.
     */
    fun recordDistributionExit(mint: String) {
        distributionCooldowns[mint] = System.currentTimeMillis()
        // Clean up old entries (older than 2 hours)
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000L)
        distributionCooldowns.entries.removeIf { it.value < twoHoursAgo }
    }
    
    /**
     * Check if a token is in distribution cooldown.
     * Uses the shorter (paper) cooldown by default since we can't easily access config here.
     */
    fun isInDistributionCooldown(mint: String): Boolean {
        val exitTime = distributionCooldowns[mint] ?: return false
        val elapsed = System.currentTimeMillis() - exitTime
        return elapsed < DISTRIBUTION_COOLDOWN_MS_PAPER  // Use shorter cooldown
    }
    
    /**
     * Get remaining cooldown time in minutes.
     */
    fun getRemainingCooldownMinutes(mint: String): Int {
        val exitTime = distributionCooldowns[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - exitTime
        val remaining = DISTRIBUTION_COOLDOWN_MS_PAPER - elapsed
        return if (remaining > 0) (remaining / 60000).toInt() else 0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE VETO TRACKER (STICKY VETOES)
    // 
    // When Edge says "VETOED", that decision is AUTHORITATIVE for a cooldown period.
    // This prevents the bug where:
    //   1. Edge vetoes at T+0
    //   2. Strategy says BUY at T+10 seconds
    //   3. FDG approves because it only sees the latest signal
    // 
    // Rule: Once vetoed, stay vetoed for EDGE_VETO_COOLDOWN_MS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class EdgeVeto(
        val timestamp: Long,
        val reason: String,
        val quality: String,  // The edge quality when vetoed (SKIP, etc.)
    )
    
    private val edgeVetoes = java.util.concurrent.ConcurrentHashMap<String, EdgeVeto>()
    
    // Edge veto cooldown - MODE AWARE
    // Paper mode: 20 seconds (fast learning)
    // Live mode: 30 seconds (reduced for more opportunities)
    private const val EDGE_VETO_COOLDOWN_PAPER_MS = 20 * 1000L  // 20 seconds for paper
    private const val EDGE_VETO_COOLDOWN_LIVE_MS = 30 * 1000L   // 30 seconds for live
    
    // Track current mode for veto cooldown
    @Volatile private var _isPaperModeForVeto = true
    fun setModeForVeto(isPaper: Boolean) { _isPaperModeForVeto = isPaper }
    
    private fun getVetoCooldownMs(): Long = 
        if (_isPaperModeForVeto) EDGE_VETO_COOLDOWN_PAPER_MS else EDGE_VETO_COOLDOWN_LIVE_MS
    
    /**
     * Record an Edge veto for a token.
     * Called from BotService when Edge returns a veto.
     */
    fun recordEdgeVeto(mint: String, reason: String, quality: String) {
        edgeVetoes[mint] = EdgeVeto(
            timestamp = System.currentTimeMillis(),
            reason = reason,
            quality = quality,
        )
        // Clean up old entries (older than 30 minutes)
        val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000L)
        edgeVetoes.entries.removeIf { it.value.timestamp < thirtyMinutesAgo }
    }
    
    /**
     * Check if a token has an active Edge veto.
     */
    fun hasActiveEdgeVeto(mint: String): EdgeVeto? {
        val veto = edgeVetoes[mint] ?: return null
        val elapsed = System.currentTimeMillis() - veto.timestamp
        return if (elapsed < getVetoCooldownMs()) veto else null
    }
    
    /**
     * Get remaining veto time in seconds.
     */
    fun getVetoRemainingSeconds(mint: String): Int {
        val veto = edgeVetoes[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - veto.timestamp
        val remaining = getVetoCooldownMs() - elapsed
        return if (remaining > 0) (remaining / 1000).toInt() else 0
    }
    
    /**
     * Clear a veto (e.g., when conditions significantly improve)
     */
    fun clearEdgeVeto(mint: String) {
        edgeVetoes.remove(mint)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN GATE FUNCTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate a trade candidate through the Final Decision Gate.
     * 
     * @param ts TokenState with all market data
     * @param candidate CandidateDecision from strategy evaluation
     * @param config Current bot configuration
     * @param proposedSizeSol Size from SmartSizer
     * @param brain Optional BotBrain for learned thresholds
     * 
     * @return FinalDecision - the ONLY object Executor should look at
     */
    fun evaluate(
        ts: TokenState,
        candidate: CandidateDecision,
        config: BotConfig,
        proposedSizeSol: Double,
        brain: BotBrain? = null,
        tradingModeTag: ModeSpecificGates.TradingModeTag? = null,
    ): FinalDecision {
        val checks = mutableListOf<GateCheck>()
        var blockReason: String? = null
        var blockLevel: BlockLevel? = null
        val tags = mutableListOf<String>()
        
        val mode = if (config.paperMode) TradeMode.PAPER else TradeMode.LIVE
        
        // ═══════════════════════════════════════════════════════════════════════
        // MODE-SPECIFIC THRESHOLD MULTIPLIERS
        // 
        // Get multipliers based on current trading mode (e.g., MOONSHOT, DEFENSIVE)
        // These adjust thresholds to match the mode's risk tolerance
        // ═══════════════════════════════════════════════════════════════════════
        val modeMultipliers = try {
            ModeSpecificGates.getMultipliers(tradingModeTag)
        } catch (e: Exception) {
            ErrorLogger.warn("FDG", "ModeSpecificGates error: ${e.message}")
            ModeSpecificGates.ModeMultipliers.DEFAULT
        }
        
        // Log mode if not standard
        if (tradingModeTag != null && tradingModeTag != ModeSpecificGates.TradingModeTag.STANDARD) {
            tags.add("mode:${tradingModeTag.name}")
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 0: ZERO CONFIDENCE HARD BLOCK
        // 
        // If confidence is 0%, this is NOT a trade candidate.
        // Don't waste cycles on sizing, edge checks, or memory checks.
        // Shadow track only - no proposal, no FDG evaluation.
        //
        // This fixes the contradictory state where:
        //   edge=VETOED + edge=SKIP + conf=0% + shouldTrade=true
        //
        // Zero confidence = NO TRADE. Period.
        // ═══════════════════════════════════════════════════════════════════════
        if (candidate.aiConfidence <= 0.0) {
            ErrorLogger.info("FDG", "🚫 ZERO_CONF_BLOCK: ${ts.symbol} | " +
                "quality=${candidate.setupQuality} edge=${candidate.edgeQuality} conf=0% → SHADOW ONLY")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = 0.0,
                edge = EdgeVerdict.SKIP,
                blockReason = "LOW_CONFIDENCE_0%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("zero_confidence", "shadow_only"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "ZERO_CONFIDENCE_BLOCK",
                gateChecks = listOf(GateCheck("confidence", false, "conf=0% → SHADOW ONLY"))
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 0.1: TOXIC MODE CIRCUIT BREAKER (HARD KILL)
        // 
        // CRITICAL: This gate runs FIRST before any other checks.
        // After catastrophic losses (GROKCHAIN -92%), these patterns are BANNED.
        // 
        // HARD KILLS:
        // 1. COPY_TRADE mode - completely disabled as execution mode
        // 2. Hard confidence floors (conf < 30 = no execution EVER)
        // 3. C-grade + low conf + negative memory + AI degraded = REJECT
        // 4. Liquidity floors: Watch=$2k, Execute=$10k+
        // ═══════════════════════════════════════════════════════════════════════
        
        // Extract memory score early for toxic pattern check
        val earlyMemoryScore = try {
            val memMult = TokenWinMemory.getConfidenceMultiplier(
                ts.mint, ts.symbol, ts.name, ts.lastMcap, 
                ts.lastLiquidityUsd, 50.0, ts.phase, ts.source
            )
            when {
                memMult < 0.5 -> -15  // Very negative memory
                memMult < 0.7 -> -10
                memMult < 0.85 -> -5
                memMult > 1.3 -> 10   // Positive memory
                memMult > 1.15 -> 5
                else -> 0
            }
        } catch (_: Exception) { 0 }
        
        // Check if AI is degraded (from candidate or market conditions)
        val earlyAIDegraded = try {
            currentConditions.entryAiWinRate < 30.0 || currentConditions.exitAiAvgPnl < -10.0
        } catch (_: Exception) { false }
        
        // Get trading mode from tag
        val tradingModeStr = tradingModeTag?.name ?: ""
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 1: COPY_TRADE MODE COMPLETELY DISABLED
        // After -92% GROKCHAIN loss, COPY_TRADE is banned as an execution mode.
        // Keep as signal for scoring, but NO BUYING through this path.
        // ─────────────────────────────────────────────────────────────────────
        if (tradingModeStr.uppercase().contains("COPY")) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | mode=$tradingModeStr | " +
                "COPY_TRADE DISABLED → NO EXECUTION")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "COPY_TRADE_MODE_DISABLED",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("copy_trade_disabled", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "COPY_TRADE_HARD_DISABLED_AFTER_CATASTROPHIC_LOSSES",
                gateChecks = listOf(GateCheck("copy_trade_kill", false, "COPY_TRADE mode completely banned"))
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 1.5: WHALE_FOLLOW MICRO-SIZE ONLY
        // After repeated bleeding from this mode, restrict to minimal size.
        // Still track in shadow, but don't risk real capital.
        // ─────────────────────────────────────────────────────────────────────
        if (tradingModeStr.uppercase().contains("WHALE")) {
            ErrorLogger.warn("FDG", "⚠️ WHALE_FOLLOW: ${ts.symbol} | mode=$tradingModeStr | " +
                "MICRO_SIZE_ONLY → Restricted after repeated losses")
            
            // Allow but force micro size (will be handled by sizing layer)
            // Add a tag so sizing knows to reduce
            return FinalDecision(
                shouldTrade = mode == TradeMode.PAPER,  // Only in paper mode
                mode = mode,
                approvalClass = if (mode == TradeMode.PAPER) ApprovalClass.PAPER_EXPLORATION else ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = when (candidate.edgeQuality) {
                    "A" -> EdgeVerdict.STRONG
                    "B" -> EdgeVerdict.WEAK  // Use WEAK instead of non-existent PROCEED
                    "C" -> EdgeVerdict.WEAK
                    else -> EdgeVerdict.SKIP
                },
                blockReason = if (mode == TradeMode.LIVE) "WHALE_FOLLOW_LIVE_DISABLED" else null,
                blockLevel = if (mode == TradeMode.LIVE) BlockLevel.HARD else null,
                sizeSol = config.smallBuySol * 0.5,  // Micro size only (use smallBuySol)
                tags = listOf("whale_follow_restricted", "micro_size_only"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "WHALE_FOLLOW restricted to PAPER + MICRO after repeated losses",
                gateChecks = listOf(GateCheck("whale_follow_restriction", mode == TradeMode.PAPER, "WHALE_FOLLOW restricted"))
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 1.6: PER-MODE KILL SWITCH
        // If a mode has 3+ recent losses (within last hour), freeze it.
        // Prevents bleeding from modes that are currently not working.
        // ─────────────────────────────────────────────────────────────────────
        val modeRecentLosses = getModeRecentLosses(tradingModeStr)
        if (modeRecentLosses >= 3) {
            ErrorLogger.warn("FDG", "🚫 MODE_FROZEN: ${ts.symbol} | mode=$tradingModeStr | " +
                "$modeRecentLosses recent losses → FROZEN FOR 30 MINS")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "MODE_FROZEN_${modeRecentLosses}_LOSSES",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("mode_frozen", "repeated_losses"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "Mode $tradingModeStr frozen after $modeRecentLosses recent losses",
                gateChecks = listOf(GateCheck("mode_freeze_check", false, "$modeRecentLosses losses in last hour"))
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 2: ABSOLUTE CONFIDENCE FLOORS (NON-NEGOTIABLE IN LIVE MODE)
        // These are HARD limits for live trading. Paper can bypass during bootstrap.
        // 
        // conf < 30 → NO EXECUTION (garbage)
        // conf < 35 AND quality C → NO EXECUTION (Kris pattern)
        // conf < 40 AND AI degraded → NO EXECUTION (blind trading)
        // 
        // V4.1.1 BOOTSTRAP OVERRIDE:
        // During bootstrap (< 25% learning progress) in paper mode,
        // we SKIP confidence floors to allow the bot to learn. This is critical
        // because confidence is low when we have no learned signals!
        // ─────────────────────────────────────────────────────────────────────
        
        // V4.1.1: Check if we're in bootstrap learning mode (MUST be declared first)
        val learningProgress = FluidLearningAI.getLearningProgress()
        val isBootstrapPhase = learningProgress < 0.25  // First 25% of learning (~250 trades)
        val isPaperMode = mode == TradeMode.PAPER
        val canBypassConfidenceFloors = isBootstrapPhase && isPaperMode
        
        val isCGrade = candidate.setupQuality == "C" || candidate.setupQuality == "D"
        val rawConfidence = candidate.aiConfidence
        
        // V4.1.2: Apply bootstrap confidence boost so floors use adjusted value
        val confidence = if (canBypassConfidenceFloors) {
            FluidLearningAI.getAdjustedConfidence(rawConfidence, isPaperMode)
        } else {
            rawConfidence
        }
        
        // V4.1.2: Even bootstrap has a minimum floor - don't learn from complete garbage
        val BOOTSTRAP_MIN_CONFIDENCE = 15.0
        if (confidence < BOOTSTRAP_MIN_CONFIDENCE) {
            ErrorLogger.debug("FDG", "🚫 BOOTSTRAP_FLOOR: ${ts.symbol} | conf=${confidence.toInt()}% < 15% | " +
                "TOO_LOW_EVEN_FOR_LEARNING")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "BOOTSTRAP_MIN_CONFIDENCE_15%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("bootstrap_floor_15", "too_low_to_learn"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "BOOTSTRAP_MIN: conf=${confidence.toInt()}% < 15% (even learning has standards)",
                gateChecks = listOf(GateCheck("bootstrap_min_conf", false, "conf < 15% is garbage even for learning"))
            )
        }
        
        if (canBypassConfidenceFloors && confidence < 30.0) {
            // Bootstrap override: Allow learning even with low confidence
            ErrorLogger.info("FDG", "🎓 BOOTSTRAP_OVERRIDE: ${ts.symbol} | conf=${confidence.toInt()}% | " +
                "Bypassing confidence floor for learning (progress=${(learningProgress*100).toInt()}%)")
            tags.add("bootstrap_learning")
            // Don't return - continue to other checks
        } else if (confidence < 30.0) {
            // Rule 1: conf < 30 is garbage - never trade in live mode
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% < 30% | " +
                "CONFIDENCE_FLOOR_VIOLATED")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "CONFIDENCE_FLOOR_30%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("confidence_floor_30", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "HARD_CONFIDENCE_FLOOR: conf=${confidence.toInt()}% < 30%",
                gateChecks = listOf(GateCheck("confidence_floor_30", false, "conf < 30% is garbage"))
            )
        }
        
        // Rule 2: conf < 35 AND C-grade - marginal + low quality = reject (unless bootstrap)
        if (confidence < 35.0 && isCGrade && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% + quality=${candidate.setupQuality} | " +
                "C_GRADE_CONFIDENCE_FLOOR_VIOLATED")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "C_GRADE_CONFIDENCE_FLOOR_35%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("c_grade_confidence_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "C-GRADE + conf < 35% = REJECT",
                gateChecks = listOf(GateCheck("c_grade_conf_floor", false, "C-grade requires conf >= 35%"))
            )
        }
        
        // Rule 3: conf < 40 AND AI degraded - blind trading = reject (unless bootstrap)
        if (confidence < 40.0 && earlyAIDegraded && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL: ${ts.symbol} | conf=${confidence.toInt()}% + AI_DEGRADED | " +
                "DEGRADED_AI_CONFIDENCE_FLOOR_VIOLATED")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "AI_DEGRADED_CONFIDENCE_FLOOR_40%",
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = listOf("ai_degraded_confidence_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "AI_DEGRADED + conf < 40% = REJECT",
                gateChecks = listOf(GateCheck("ai_degraded_conf_floor", false, "Degraded AI requires conf >= 40%"))
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 3: TOXIC PATTERN COMBO (THE "KRIS" RULE)
        // 
        // If ALL or MOST of these are true → HARD REJECT:
        //   - quality = C
        //   - conf < 35
        //   - memory <= -8
        //   - AI degraded
        // 
        // This is exactly the pattern that let "Kris" through.
        // No PAPER_PROBE. No sizing. No entry. REJECT.
        // ─────────────────────────────────────────────────────────────────────
        val toxicPatternFlags = mutableListOf<String>()
        
        if (isCGrade) toxicPatternFlags.add("quality_C")
        if (confidence < 35.0) toxicPatternFlags.add("conf<35")
        if (earlyMemoryScore <= -8) toxicPatternFlags.add("memory<=-8")
        if (earlyAIDegraded) toxicPatternFlags.add("AI_degraded")
        
        // If 3+ toxic flags → HARD REJECT (covers "Kris" case)
        // V4.1.1: Skip toxic pattern detection during bootstrap learning
        if (toxicPatternFlags.size >= 3 && !canBypassConfidenceFloors) {
            ErrorLogger.warn("FDG", "🚫 HARD_KILL TOXIC PATTERN: ${ts.symbol} | " +
                "flags=${toxicPatternFlags.joinToString(",")} | KRIS_RULE → REJECT")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "TOXIC_PATTERN_KRIS_RULE",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("toxic_pattern", "kris_rule", "hard_kill") + toxicPatternFlags,
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "TOXIC_PATTERN: ${toxicPatternFlags.joinToString(" + ")} → REJECT",
                gateChecks = listOf(GateCheck("toxic_pattern", false, 
                    "Kris rule: 3+ toxic flags (${toxicPatternFlags.joinToString(",")}) = HARD REJECT"))
            )
        } else if (toxicPatternFlags.size >= 3 && canBypassConfidenceFloors) {
            // Log that we're bypassing toxic pattern for learning
            ErrorLogger.info("FDG", "🎓 BOOTSTRAP_OVERRIDE: ${ts.symbol} | " +
                "Bypassing toxic pattern check for learning (flags=${toxicPatternFlags.joinToString(",")})")
            tags.add("bootstrap_toxic_bypass")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 4: LIQUIDITY FLOORS FOR EXECUTION
        // 
        // Split watchlist floor from execution floor cleanly:
        //   - Watch/Shadow: $2,000 minimum (for learning)
        //   - Execution: $10,000 minimum (for actual trades)
        // 
        // FLUID LIQUIDITY FLOORS (from FluidLearningAI - Layer 23)
        // 
        // Bootstrap: watchlist=$500, exec=$1500
        // Mature:    watchlist=$2000, exec=$10000
        // ─────────────────────────────────────────────────────────────────────
        val WATCHLIST_FLOOR = com.lifecyclebot.v3.scoring.FluidLearningAI.getWatchlistFloor()
        val EXECUTION_FLOOR = com.lifecyclebot.v3.scoring.FluidLearningAI.getExecutionFloor()
        
        // Below watchlist floor - don't even watch, too much noise
        if (ts.lastLiquidityUsd < WATCHLIST_FLOOR) {
            ErrorLogger.debug("FDG", "🚫 LIQ_FLOOR: ${ts.symbol} | liq=\$${ts.lastLiquidityUsd.toInt()} < \$${WATCHLIST_FLOOR.toInt()} | " +
                "TOO_LOW_FOR_WATCHLIST")
            
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = EdgeVerdict.SKIP,
                blockReason = "LIQUIDITY_BELOW_WATCHLIST_FLOOR",
                blockLevel = BlockLevel.HARD,
                sizeSol = 0.0,
                tags = listOf("liq_below_watch_floor", "hard_kill"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "Liquidity \$${ts.lastLiquidityUsd.toInt()} < \$${WATCHLIST_FLOOR.toInt()} watchlist floor",
                gateChecks = listOf(GateCheck("liq_watch_floor", false, "liq < \$${WATCHLIST_FLOOR.toInt()} = not worth watching"))
            )
        }
        
        // Between watchlist and execution floor - watch only, no execution (shadow track for learning)
        if (ts.lastLiquidityUsd < EXECUTION_FLOOR) {
            ErrorLogger.info("FDG", "👁️ LIQ_FLOOR: ${ts.symbol} | liq=\$${ts.lastLiquidityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} | " +
                "WATCH_ONLY (no execution)")
            
            // Mark as blocked but add tags for shadow tracking
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = confidence,
                edge = if (candidate.setupQuality in listOf("A+", "A", "B")) EdgeVerdict.WEAK else EdgeVerdict.SKIP,
                blockReason = "LIQUIDITY_BELOW_EXECUTION_FLOOR",
                blockLevel = BlockLevel.MODE,
                sizeSol = 0.0,
                tags = listOf("liq_below_exec_floor", "shadow_track", "watch_only"),
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = "Liquidity \$${ts.lastLiquidityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} execution floor - WATCH ONLY",
                gateChecks = listOf(GateCheck("liq_exec_floor", false, 
                    "liq \$${ts.lastLiquidityUsd.toInt()} < \$${EXECUTION_FLOOR.toInt()} = shadow track only"))
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // HARD KILL 5: CALL TOXIC MODE CIRCUIT BREAKER
        // 
        // Check ToxicModeCircuitBreaker for any mode-specific blocks.
        // This catches frozen modes, banned source+mode combos, etc.
        // ─────────────────────────────────────────────────────────────────────
        if (tradingModeStr.isNotBlank()) {
            val circuitBlockReason = ToxicModeCircuitBreaker.checkEntryAllowed(
                mode = tradingModeStr,
                source = ts.source,
                liquidityUsd = ts.lastLiquidityUsd,
                phase = ts.phase,
                memoryScore = earlyMemoryScore,
                isAIDegraded = earlyAIDegraded,
                confidence = confidence.toInt()
            )
            
            if (circuitBlockReason != null) {
                ErrorLogger.warn("FDG", "🚫 CIRCUIT_BREAKER: ${ts.symbol} | mode=$tradingModeStr | " +
                    "$circuitBlockReason")
                
                return FinalDecision(
                    shouldTrade = false,
                    mode = mode,
                    approvalClass = ApprovalClass.BLOCKED,
                    quality = candidate.setupQuality,
                    confidence = confidence,
                    edge = EdgeVerdict.SKIP,
                    blockReason = "CIRCUIT_BREAKER: $circuitBlockReason",
                    blockLevel = BlockLevel.HARD,
                    sizeSol = 0.0,
                    tags = listOf("circuit_breaker", "mode_blocked"),
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "ToxicModeCircuitBreaker: $circuitBlockReason",
                    gateChecks = listOf(GateCheck("circuit_breaker", false, circuitBlockReason))
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // PASSED ALL HARD KILLS - Continue with normal FDG evaluation
        // ═══════════════════════════════════════════════════════════════════════
        
        // ═══════════════════════════════════════════════════════════════════════
        // AUTO-ADJUSTING THRESHOLDS
        // 
        // Get current trade count and win rate from brain for adaptive thresholds
        // FDG automatically tightens as the bot learns from more trades
        // ═══════════════════════════════════════════════════════════════════════
        
        // ═══════════════════════════════════════════════════════════════════
        // PRIORITY 3: FDG COUNTER FIX - USE CLASSIFIED TRADES FOR PROGRESSION
        // 
        // BUG: FDG was using maxOf(executed, classified, fluid) which advanced
        // bootstrap based on raw execution count, not meaningful outcomes.
        // 
        // User insight: "FDG is probably advancing on activity, not on 
        // meaningful completed outcomes. That can distort bootstrap badly."
        //
        // FIX: Use CLASSIFIED count (trades Brain has learned from) as the
        // primary progression counter. Executed count is only for logging.
        //
        // Counter definitions:
        //   brainTrades:   BotBrain.recentMemory.size (classified/learned trades)
        //   sessionTrades: totalSessionTrades (raw execution count - for logging only)
        //   fluidTrades:   FluidLearning.getTradeCount() (persisted learned trades)
        //
        // Progression uses: maxOf(brainTrades, fluidTrades) - both represent LEARNED outcomes
        // ═══════════════════════════════════════════════════════════════════
        val brainTrades = brain?.getTradeCount() ?: 0
        val sessionTrades = currentConditions.totalSessionTrades
        val fluidTrades = try { FluidLearning.getTradeCount() } catch (_: Exception) { 0 }
        
        // CANONICAL COUNTER: Use CLASSIFIED/LEARNED count, NOT raw executed count
        // This ensures bootstrap progresses based on meaningful outcomes
        val effectiveTradeCount = maxOf(brainTrades, fluidTrades)
        val rawExecutedCount = sessionTrades  // For logging/debugging only
        
        val winRate = brain?.getRecentWinRate() ?: 50.0
        val adjusted = getAdjustedThresholds(effectiveTradeCount, winRate)
        
        // Log learning phase periodically or when counters are mismatched
        val countersMatch = brainTrades == fluidTrades  // Compare learning counters only
        val executionMismatch = rawExecutedCount > effectiveTradeCount + 2  // Execution ahead of learning by >2
        val isPhaseTransition = effectiveTradeCount == 0 || effectiveTradeCount == 10 || effectiveTradeCount == 30 || effectiveTradeCount == 51
        
        if (isPhaseTransition || !countersMatch || executionMismatch) {
            val counterDetail = "executed=$rawExecutedCount | classified=$brainTrades | fluid=$fluidTrades | learning_uses=$effectiveTradeCount"
            val mismatchNote = when {
                executionMismatch -> " ⚠️ EXECUTION_AHEAD (trades executing faster than learning)"
                !countersMatch -> " ⚠️ LEARNING_MISMATCH ($counterDetail)"
                else -> ""
            }
            ErrorLogger.info("FDG", "📊 Learning phase: ${adjusted.learningPhase} | " +
                "learning_trades=$effectiveTradeCount | progress=${(adjusted.progress * 100).toInt()}% | conf=${adjusted.confidenceBase.toInt()}%" +
                (if (tradingModeTag != null) " | mode=${tradingModeTag.name}" else "") +
                mismatchNote)
        }
        
        // Use auto-adjusted thresholds for live mode
        // FIX: Paper mode was too lenient (rugcheck=5, buyPressure=10)
        // This caused 82 trades with only 8% win rate - learning garbage
        // Paper mode should still have reasonable thresholds to learn from quality trades
        // Apply mode multipliers to thresholds
        val rugcheckThreshold = if (config.paperMode) {
            15  // Paper: Still require decent rugcheck (was 5 - way too lenient!)
        } else {
            // Use auto-adjusted OR brain-learned, apply mode multiplier
            val baseThreshold = (brain?.learnedRugcheckThreshold ?: adjusted.rugcheckMin).coerceIn(5, 25)
            (baseThreshold * modeMultipliers.rugcheckMultiplier).toInt().coerceIn(3, 30)
        }
        val buyPressureThreshold = if (config.paperMode) 25.0 else adjusted.buyPressureMin * modeMultipliers.entryScoreMultiplier  // was 10.0
        val topHolderThreshold = if (config.paperMode) 70.0 else adjusted.topHolderMax / modeMultipliers.rugcheckMultiplier  // was 90.0
        
        // Store adjusted thresholds for use in later gates
        val currentAdjusted = adjusted
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 0: REENTRY GUARD (hard block, checked FIRST)
        // Tokens with collapse, distribution, stop-loss, or bad memory
        // are completely blocked from re-entry - no score override possible
        // ─────────────────────────────────────────────────────────────────────
        if (ReentryGuard.isBlocked(ts.mint)) {
            val lockoutInfo = ReentryGuard.formatLockout(ts.mint)
            blockReason = "HARD_BLOCK_REENTRY_GUARD: $lockoutInfo"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("reentry_guard", false, lockoutInfo))
        } else {
            checks.add(GateCheck("reentry_guard", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 0.5: PROPOSAL DEDUPE — REMOVED
        // This check was moved to BotService (early dedupe check before CANDIDATE)
        // The FDG check was redundant and caused self-blocking because it would
        // see the PROPOSED state that was just set moments before FDG.evaluate()
        // ─────────────────────────────────────────────────────────────────────
        checks.add(GateCheck("proposal_dedupe", true, "checked early in BotService"))
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 1: HARD BLOCKS (non-negotiable, checked first)
        // ─────────────────────────────────────────────────────────────────────
        
        // 1a. Zero liquidity - impossible to trade
        if (ts.lastLiquidityUsd <= 0) {
            blockReason = "HARD_BLOCK_ZERO_LIQUIDITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("liquidity", false, "liq=${ts.lastLiquidityUsd}"))
        } else {
            checks.add(GateCheck("liquidity", true, null))
        }
        
        // 1b. Rugcheck score critically low
        // ═══════════════════════════════════════════════════════════════════════════
        // V4.1 RUGCHECK TIMEOUT POLICY (P1 FIX)
        // 
        // OLD: Timeout = soft allow (too permissive, allowed junk trades)
        // NEW: Use rugcheckStatus and rugcheckTimeoutPenalty from SafetyReport
        //   - CONFIRMED: Score is real, use normal thresholds
        //   - TIMEOUT: Penalty already applied in SafetyChecker, add extra caution
        //   - PENDING_REVIEW: Live mode timeout, require stronger fallback signals
        // 
        // The penalty is now applied at SafetyChecker level, FDG just respects it.
        // ═══════════════════════════════════════════════════════════════════════════
        val rugcheckScore = ts.safety.rugcheckScore
        val rugcheckStatus = ts.safety.rugcheckStatus
        val rugcheckTimeoutPenalty = ts.safety.rugcheckTimeoutPenalty
        
        val rugcheckBlock = when {
            // CONFIRMED status: use normal score-based blocking
            rugcheckStatus == "CONFIRMED" && rugcheckScore <= rugcheckThreshold -> true
            rugcheckStatus == "CONFIRMED" && rugcheckScore > rugcheckThreshold -> false
            
            // TIMEOUT in paper mode: Penalty already applied, allow for learning
            // but add a tag so we can track these trades separately
            rugcheckStatus == "TIMEOUT" && config.paperMode -> {
                tags.add("rugcheck_timeout")
                false  // Allow but with penalty already applied
            }
            
            // TIMEOUT/PENDING_REVIEW in live mode: Require stronger fallback signals
            (rugcheckStatus == "TIMEOUT" || rugcheckStatus == "PENDING_REVIEW") && !config.paperMode -> {
                // V4.1: Stricter fallback requirements
                // Only allow if we have STRONG safety signals (higher bar than before)
                val hasStrongBuyers = ts.meta.pressScore >= 60.0  // Raised from 55%
                val hasGoodLiquidity = ts.lastLiquidityUsd >= 8000.0  // Raised from $5K
                val hasGoodVolume = ts.history.lastOrNull()?.volumeH1 ?: 0.0 >= 2000.0  // Added volume check
                
                val shouldBlock = !(hasStrongBuyers && hasGoodLiquidity && hasGoodVolume)
                
                if (!shouldBlock) {
                    ErrorLogger.info("FDG", "Rugcheck $rugcheckStatus for ${ts.symbol}, " +
                        "allowing with STRICT safety fallback: buy%=${ts.meta.pressScore.toInt()} " +
                        "liq=\$${ts.lastLiquidityUsd.toInt()} vol=\$${(ts.history.lastOrNull()?.volumeH1 ?: 0.0).toInt()}")
                    tags.add("rugcheck_timeout_fallback")
                } else {
                    // Log why we're blocking
                    ErrorLogger.warn("FDG", "Rugcheck $rugcheckStatus BLOCKED for ${ts.symbol}: " +
                        "weak fallback signals (buy%=${ts.meta.pressScore.toInt()} " +
                        "liq=\$${ts.lastLiquidityUsd.toInt()} - need 60%+ buy, \$8K+ liq, \$2K+ vol)")
                }
                shouldBlock
            }
            
            // Unknown status: treat as timeout with caution
            else -> {
                tags.add("rugcheck_unknown")
                rugcheckScore <= rugcheckThreshold
            }
        }
        
        if (blockReason == null && rugcheckBlock) {
            // Provide more informative block reason for API timeouts
            val blockReasonDetail = when (rugcheckStatus) {
                "TIMEOUT", "PENDING_REVIEW" -> "HARD_BLOCK_RUGCHECK_${rugcheckStatus}_WEAK_FALLBACK"
                else -> "HARD_BLOCK_RUGCHECK_$rugcheckScore"
            }
            blockReason = blockReasonDetail
            blockLevel = BlockLevel.HARD
            
            val checkReason = when (rugcheckStatus) {
                "TIMEOUT", "PENDING_REVIEW" -> 
                    "status=$rugcheckStatus, weak fallback: buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()}"
                else -> 
                    "score=$rugcheckScore <= $rugcheckThreshold"
            }
            checks.add(GateCheck("rugcheck", false, checkReason))
            tags.add("low_rugcheck")
        } else if (blockReason == null) {
            // Log detailed pass reason
            val passReason = when (rugcheckStatus) {
                "TIMEOUT" -> 
                    "status=TIMEOUT (paper: penalty=$rugcheckTimeoutPenalty applied, allowed for learning)"
                "PENDING_REVIEW" -> 
                    "status=PENDING_REVIEW, strong fallback (buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()})"
                "CONFIRMED" ->
                    "score=$rugcheckScore > $rugcheckThreshold"
                else -> null
            }
            checks.add(GateCheck("rugcheck", true, passReason))
        }
        
        // 1c. Extreme sell pressure (mass dumping)
        if (blockReason == null && ts.meta.pressScore < buyPressureThreshold) {
            blockReason = "HARD_BLOCK_SELL_PRESSURE_${ts.meta.pressScore.toInt()}%"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("buy_pressure", false, "buy%=${ts.meta.pressScore.toInt()} < $buyPressureThreshold"))
            tags.add("sell_pressure")
        } else if (blockReason == null) {
            checks.add(GateCheck("buy_pressure", true, null))
        }
        
        // 1d. Single whale controls supply
        if (blockReason == null && ts.safety.topHolderPct > topHolderThreshold) {
            blockReason = "HARD_BLOCK_TOP_HOLDER_${ts.safety.topHolderPct.toInt()}%"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("top_holder", false, "holder=${ts.safety.topHolderPct.toInt()}% > $topHolderThreshold"))
            tags.add("whale_control")
        } else if (blockReason == null) {
            checks.add(GateCheck("top_holder", true, null))
        }
        
        // 1e. Check hard block reasons from safety checker
        if (blockReason == null && ts.safety.hardBlockReasons.isNotEmpty()) {
            blockReason = "HARD_BLOCK_SAFETY_${ts.safety.hardBlockReasons.first().take(30)}"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("safety_block", false, ts.safety.hardBlockReasons.joinToString(", ")))
            tags.add("safety_blocked")
        } else if (blockReason == null) {
            checks.add(GateCheck("safety_block", true, null))
        }
        
        // 1f. Freeze authority in LIVE mode (honeypot risk)
        // freezeAuthorityDisabled == false means freeze IS enabled (bad)
        if (blockReason == null && !config.paperMode && ts.safety.freezeAuthorityDisabled == false) {
            blockReason = "HARD_BLOCK_FREEZE_AUTHORITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("freeze_auth", false, "freezeAuth=enabled (live mode)"))
            tags.add("freeze_auth")
        } else if (blockReason == null) {
            checks.add(GateCheck("freeze_auth", true, null))
        }
        
        // 1g. Check candidate's own block reason
        if (blockReason == null && candidate.blockReason.isNotEmpty()) {
            blockReason = candidate.blockReason
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("candidate_block", false, candidate.blockReason))
        } else if (blockReason == null) {
            checks.add(GateCheck("candidate_block", true, null))
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1g.5: BEHAVIOR LEARNING HARD BLOCK
        // 
        // If BehaviorLearning has learned that this pattern has 80%+ loss rate
        // with high confidence, BLOCK it. This is learned behavior, not hardcoded.
        // 
        // BOOTSTRAP MODE: Apply penalty instead of hard block for low-sample patterns
        // MATURE MODE: Hard block reliable 100% loss patterns
        // ═══════════════════════════════════════════════════════════════════════
        
        // Use isBootstrapPhase already defined above (line ~1433)
        // val isBootstrapPhase = currentConditions.totalSessionTrades < 50  // REMOVED - duplicate
        var softPenaltyScore = 0  // Accumulated soft penalties instead of blocks
        var sizeMultiplier = 1.0  // Size reduction for risky but allowed trades
        var isProbeCandidate = false  // Flag for PROBE approval class
        
        // Behavior-specific tracking
        var behaviorPenalty = 0
        var behaviorSizeMultiplier = 1.0
        var behaviorProbe = false
        
        if (blockReason == null) {
            try {
                // Determine volume signal
                val volScore = ts.meta.volScore
                val volumeSignal = when {
                    volScore > 80 -> "SURGE"
                    volScore > 60 -> "INCREASING"
                    volScore > 40 -> "NORMAL"
                    volScore > 20 -> "DECREASING"
                    else -> "LOW"
                }
                
                val setupQuality = when {
                    candidate.entryScore >= 90 -> "A+"
                    candidate.entryScore >= 80 -> "A"
                    candidate.entryScore >= 70 -> "B"
                    else -> "C"
                }
                
                val behaviorBlock = BehaviorLearning.shouldHardBlock(
                    entryPhase = candidate.phase,
                    setupQuality = setupQuality,
                    tradingMode = tradingModeTag?.name ?: "STANDARD",
                    liquidityUsd = ts.lastLiquidityUsd,
                    volumeSignal = volumeSignal,
                )
                
                if (behaviorBlock != null) {
                    // Check if this is a true 100% loss pattern with SUFFICIENT SAMPLES
                    // A "100% loss" from 1-2 trades is NOT strong evidence
                    val is100PctLoss = behaviorBlock.contains("100%")
                    
                    // Extract sample count from pattern like "Pattern has 100% loss rate (3 trades)"
                    val sampleCountMatch = Regex("\\((\\d+) trades?\\)").find(behaviorBlock)
                    val sampleCount = sampleCountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    
                    // Only hard-block 100% loss if we have ENOUGH evidence
                    // Minimum 5 samples for 100% loss to be considered reliable
                    val isReliable100PctLoss = is100PctLoss && sampleCount >= 5
                    
                    if (isReliable100PctLoss && !isBootstrapPhase) {
                        // MATURE + reliable 100% loss: Hard block
                        blockReason = "BEHAVIOR_BLOCK_100PCT_LOSS"
                        blockLevel = BlockLevel.HARD
                        checks.add(GateCheck("behavior_learning", false, 
                            "$behaviorBlock (reliable: $sampleCount samples)"))
                        tags.add("behavior_100pct_loss_blocked")
                    } else if (is100PctLoss && isBootstrapPhase) {
                        // BOOTSTRAP + 100% loss (even low sample): Penalty, not block
                        behaviorPenalty = if (sampleCount >= 3) 15 else 8
                        behaviorSizeMultiplier = 0.3  // Heavy size cut for these
                        behaviorProbe = true
                        checks.add(GateCheck("behavior_learning", true, 
                            "BEHAVIOR 100% LOSS → PENALTY (bootstrap: -${behaviorPenalty}pts, n=$sampleCount)"))
                        tags.add("behavior_penalized")
                    } else if (config.paperMode) {
                        // Paper mode with <100% loss: Log but don't block
                        checks.add(GateCheck("behavior_learning", true, 
                            "PAPER: $behaviorBlock (bypassed for learning)"))
                        tags.add("behavior_warning_bypassed")
                    } else {
                        // Live mode: Respect the learned behavior block
                        blockReason = behaviorBlock
                        blockLevel = BlockLevel.HARD
                        checks.add(GateCheck("behavior_learning", false, behaviorBlock))
                        tags.add("behavior_blocked")
                    }
                } else {
                    // Get score adjustment from behavior learning
                    val behaviorAdj = BehaviorLearning.getScoreAdjustment(
                        entryPhase = candidate.phase,
                        setupQuality = setupQuality,
                        tradingMode = tradingModeTag?.name ?: "STANDARD",
                        liquidityUsd = ts.lastLiquidityUsd,
                        volumeSignal = volumeSignal,
                    )
                    
                    if (behaviorAdj != 0) {
                        checks.add(GateCheck("behavior_learning", true, 
                            "Score adj: ${if (behaviorAdj > 0) "+" else ""}$behaviorAdj"))
                        if (behaviorAdj < -20) tags.add("behavior_warning")
                        if (behaviorAdj > 15) tags.add("behavior_boost")
                    } else {
                        checks.add(GateCheck("behavior_learning", true, null))
                    }
                }
            } catch (e: Exception) {
                checks.add(GateCheck("behavior_learning", true, "error: ${e.message}"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1g.6: DANGER ZONE (TIME-BASED) - SOFT IN BOOTSTRAP
        // 
        // If TimeAI says DANGER ZONE, this historically performs poorly.
        // 
        // BOOTSTRAP MODE: Apply penalty + size cut, don't hard block
        // MATURE MODE: Can hard block if not bypassed
        // ═══════════════════════════════════════════════════════════════════════
        
        var dangerZonePenalty = 0
        if (blockReason == null) {
            try {
                val isDanger = TimeOptimizationAI.isDangerZone()
                if (isDanger) {
                    if (isBootstrapPhase) {
                        // BOOTSTRAP: Penalty + size cut, NOT hard block
                        dangerZonePenalty = 12  // Reduce confidence by 12%
                        sizeMultiplier *= 0.4   // Cut size by 60%
                        softPenaltyScore += dangerZonePenalty
                        isProbeCandidate = true
                        checks.add(GateCheck("time_danger", true, 
                            "DANGER_ZONE → PENALTY (bootstrap: -${dangerZonePenalty}pts, size×0.4)"))
                        tags.add("time_danger_penalized")
                        tags.add("bootstrap_probe")
                    } else {
                        // MATURE MODE: Check adaptive bypass, else block
                        val shouldBypass = shouldBypassSoftBlock("DANGER_ZONE_TIME")
                        if (shouldBypass) {
                            checks.add(GateCheck("time_danger", true, 
                                "DANGER ZONE BYPASSED (adaptive: ${getAdaptiveFilterStatus()})"))
                            tags.add("time_danger_bypassed_adaptive")
                        } else {
                            blockReason = "DANGER_ZONE_TIME"
                            blockLevel = BlockLevel.MODE
                            checks.add(GateCheck("time_danger", false, "TimeAI DANGER ZONE"))
                            tags.add("time_danger_blocked")
                        }
                    }
                } else {
                    val timeAdj = TimeOptimizationAI.getEntryScoreAdjustment()
                    checks.add(GateCheck("time_danger", true, "Time adj: ${timeAdj.toInt()}"))
                }
            } catch (e: Exception) {
                checks.add(GateCheck("time_danger", true, "error: ${e.message}"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1g.7: NEGATIVE MEMORY - SOFT IN BOOTSTRAP
        // 
        // If TokenWinMemory has severely negative score for this token pattern,
        // apply penalty. Only hard-block if NOT in bootstrap.
        // ═══════════════════════════════════════════════════════════════════════
        
        var memoryPenalty = 0
        if (blockReason == null) {
            try {
                val latestBuyPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
                val memoryMult = TokenWinMemory.getConfidenceMultiplier(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    name = ts.name,
                    mcap = ts.lastMcap,
                    liquidity = ts.lastLiquidityUsd,
                    buyPercent = latestBuyPct,
                    phase = ts.phase,
                    source = ts.source,
                )
                
                if (memoryMult <= 0.82) {
                    if (isBootstrapPhase) {
                        // BOOTSTRAP: Penalty + size cut, NOT hard block
                        memoryPenalty = 10
                        sizeMultiplier *= 0.5
                        softPenaltyScore += memoryPenalty
                        isProbeCandidate = true
                        checks.add(GateCheck("memory_negative", true, 
                            "MEMORY_NEG → PENALTY (bootstrap: -${memoryPenalty}pts, size×0.5, mult=$memoryMult)"))
                        tags.add("memory_penalized")
                    } else {
                        // MATURE MODE: Check adaptive bypass, else block
                        val shouldBypass = shouldBypassSoftBlock("MEMORY_NEGATIVE_BLOCK")
                        if (shouldBypass) {
                            checks.add(GateCheck("memory_negative", true, 
                                "MEMORY BLOCK BYPASSED (adaptive: ${getAdaptiveFilterStatus()})"))
                            tags.add("memory_bypassed_adaptive")
                        } else {
                            blockReason = "MEMORY_NEGATIVE_BLOCK"
                            blockLevel = BlockLevel.MODE
                            checks.add(GateCheck("memory_negative", false, 
                                "Memory strongly negative (mult=${memoryMult})"))
                            tags.add("memory_blocked")
                        }
                    }
                } else if (memoryMult < 0.90) {
                    // Warning but don't block or heavily penalize
                    memoryPenalty = 5
                    softPenaltyScore += memoryPenalty
                    checks.add(GateCheck("memory_negative", true, 
                        "Memory warning (mult=${memoryMult}, -${memoryPenalty}pts)"))
                    tags.add("memory_warning")
                } else {
                    checks.add(GateCheck("memory_negative", true, null))
                }
            } catch (e: Exception) {
                checks.add(GateCheck("memory_negative", true, "error: ${e.message}"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1h: DISTRIBUTION HARD BLOCK (CRITICAL - applies to ALL modes)
        // 
        // NEVER buy during distribution phase. This is when whales/bots are
        // actively DUMPING. The outcome is predictable: you will lose.
        // 
        // We check MULTIPLE signals:
        //   1. Distribution COOLDOWN (token recently exited due to distribution)
        //   2. EdgeOptimizer phase detection (edgePhase == "DISTRIBUTION")
        //   3. TokenState phase tag (ts.phase contains "distribution")
        //   4. DistributionDetector direct check (confidence >= 50%)
        // 
        // Even paper mode should NOT take these trades.
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null) {
            // First check: Is this token in distribution cooldown?
            val inCooldown = isInDistributionCooldown(ts.mint)
            val cooldownMinutes = if (inCooldown) getRemainingCooldownMinutes(ts.mint) else 0
            
            // PAPER MODE: Skip ALL distribution cooldowns - we want maximum learning
            // AI needs to see what happens when we trade during distribution
            if (config.paperMode) {
                if (inCooldown) {
                    checks.add(GateCheck("distribution", true, "PAPER: cooldown bypassed for learning"))
                    tags.add("distribution_cooldown_bypassed")
                }
                // Continue to other checks but don't block
            } else {
                // LIVE MODE: Bypass cooldown only with very strong buy pressure (65%+)
                // If buyers have decisively returned, the distribution might be over
                val bypassCooldownForLive = ts.meta.pressScore >= 65.0
                
                if (inCooldown && !bypassCooldownForLive) {
                    blockReason = "DISTRIBUTION_COOLDOWN_${cooldownMinutes}min"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("distribution", false, "Recently exited distribution, cooldown=${cooldownMinutes}min remaining"))
                    tags.add("distribution_cooldown")
                } else if (inCooldown && bypassCooldownForLive) {
                    checks.add(GateCheck("distribution", true, "LIVE: cooldown bypass (buy%=${ts.meta.pressScore.toInt()}%)"))
                    tags.add("distribution_cooldown_bypassed")
                }
            }
            
            // Check other distribution signals (LIVE mode only for blocking)
            if (blockReason == null && !config.paperMode) {
                val isDistributionPhase = candidate.edgePhase.uppercase() == "DISTRIBUTION"
                val hasDistributionTag = ts.phase.lowercase().contains("distribution")
                
                // Also run DistributionDetector directly for more accurate detection
                val distSignal = if (ts.history.size >= 5) {
                    DistributionDetector.detect(
                        mint = ts.mint,
                        ts = ts,
                        currentExitScore = candidate.exitScore,
                        history = ts.history
                    )
                } else null
                
                // Mode-aware distribution threshold
                // Paper mode: Higher threshold (70%) - allow more learning from edge cases
                // Live mode: Lower threshold (50%) - be more cautious with real money
                val distThreshold = if (config.paperMode) 70 else 50
                val isDistributorConfident = distSignal?.isDistributing == true && distSignal.confidence >= distThreshold
                
                // In paper mode, skip distribution block if buy pressure is high
                // This allows learning from "almost distribution" scenarios
                val bypassForPaperLearning = config.paperMode && 
                    ts.meta.pressScore >= 55.0 && 
                    !isDistributionPhase
                
                if ((isDistributionPhase || hasDistributionTag || isDistributorConfident) && !bypassForPaperLearning) {
                    val reason = when {
                        isDistributionPhase -> "edgePhase=DISTRIBUTION"
                        hasDistributionTag -> "tsPhase=${ts.phase}"
                        isDistributorConfident -> "detector=${distSignal?.confidence}% (${distSignal?.details})"
                        else -> "unknown"
                    }
                    blockReason = "HARD_BLOCK_DISTRIBUTION"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("distribution", false, reason))
                    tags.add("distribution_block")
                } else {
                    val detectorInfo = if (distSignal != null) " detector=${distSignal.confidence}%" else ""
                    checks.add(GateCheck("distribution", true, "edgePhase=${candidate.edgePhase}$detectorInfo"))
                }
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1.5: EARLY SNIPE MODE (FAST TRACK)
        // 
        // For NEWLY DISCOVERED tokens with HIGH SCORES, skip most checks and
        // enter FAST. Meme coins pump in 5-10 minutes - by the time all checks
        // pass, the opportunity is gone (see KOMAKI case: discovered at 40K,
        // bought at 160K due to delayed entries).
        // 
        // EARLY SNIPE CRITERIA:
        //   - Token recently discovered (first candle < 10 min ago)
        //   - High initial score (>= 70) from Scanner
        //   - Decent liquidity (>= $3000)
        //   - Passed all hard blocks above (rugcheck, freeze, etc.)
        //   - Buy pressure positive (>= 50%)
        // 
        // WHEN TRIGGERED:
        //   - APPROVE immediately with quality "SNIPE"
        //   - Use SMALL position (risk management)
        //   - Skip Edge veto, confidence threshold, etc.
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null && earlySnipeEnabled && !config.paperMode) {
            // Calculate token age from first candle
            val tokenAgeMinutes = if (ts.history.isNotEmpty()) {
                val firstCandleTime = ts.history.minOfOrNull { it.ts } ?: System.currentTimeMillis()
                (System.currentTimeMillis() - firstCandleTime) / 60_000.0
            } else 0.0
            
            // Get initial score (from Scanner filter) - use entryScore
            val initialScore = candidate.entryScore
            val liquidity = ts.lastLiquidityUsd
            val buyPressure = ts.meta.pressScore
            
            // Check if qualifies for early snipe
            val isYoungToken = tokenAgeMinutes <= earlySnipeMaxAgeMinutes
            val hasHighScore = initialScore >= earlySnipeMinScore
            val hasMinLiquidity = liquidity >= earlySnipeMinLiquidity
            val hasPositiveBuyPressure = buyPressure >= 50.0
            
            val qualifiesForSnipe = isYoungToken && hasHighScore && hasMinLiquidity && hasPositiveBuyPressure
            
            if (qualifiesForSnipe) {
                // EARLY SNIPE APPROVED - fast track this entry!
                checks.add(GateCheck("early_snipe", true, 
                    "SNIPE: age=${tokenAgeMinutes.toInt()}min score=${initialScore.toInt()} liq=\$${liquidity.toInt()} buy%=${buyPressure.toInt()}"))
                tags.add("early_snipe")
                
                ErrorLogger.info("FDG", "🎯 EARLY_SNIPE: ${ts.symbol} | " +
                    "age=${tokenAgeMinutes.toInt()}min score=${initialScore.toInt()} " +
                    "liq=\$${liquidity.toInt()} buy%=${buyPressure.toInt()}% → FAST APPROVE")
                
                // Return immediately with SNIPE quality - skip all other gates
                return FinalDecision(
                    shouldTrade = true,
                    mode = mode,
                    approvalClass = ApprovalClass.LIVE,
                    quality = "SNIPE",  // Special quality marker
                    confidence = 50.0,  // Neutral confidence
                    edge = EdgeVerdict.STRONG,  // Use STRONG for snipe entries
                    blockReason = null,
                    blockLevel = null,
                    sizeSol = (proposedSizeSol * 0.5).coerceAtLeast(0.003), // Half size for risk management
                    tags = tags + "fast_track",
                    mint = ts.mint,
                    symbol = ts.symbol,
                    approvalReason = "EARLY_SNIPE: age=${tokenAgeMinutes.toInt()}min score=${initialScore.toInt()} liq=\$${liquidity.toInt()}",
                    gateChecks = checks
                )
            } else if (isYoungToken && initialScore >= 60.0) {
                // Almost qualified - log for debugging
                val reasons = mutableListOf<String>()
                if (!hasHighScore) reasons.add("score=${initialScore.toInt()}<$earlySnipeMinScore")
                if (!hasMinLiquidity) reasons.add("liq=\$${liquidity.toInt()}<\$${earlySnipeMinLiquidity.toInt()}")
                if (!hasPositiveBuyPressure) reasons.add("buy%=${buyPressure.toInt()}<50")
                
                checks.add(GateCheck("early_snipe", false, 
                    "SNIPE_MISS: age=${tokenAgeMinutes.toInt()}min ${reasons.joinToString(" ")}"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1i: STICKY EDGE VETO
        // 
        // When Edge says "VETOED", that decision is respected for a cooldown period.
        // 
        // PAPER MODE: More lenient - bypass veto if:
        //   - Buy pressure recovered (>= 55%)
        //   - OR Quality is good (A/A+/B)
        //   - OR Confidence is decent (>= 40%)
        // 
        // LIVE MODE: Strict - respect all vetoes
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null) {
            val activeVeto = hasActiveEdgeVeto(ts.mint)
            if (activeVeto != null) {
                val remainingSec = getVetoRemainingSeconds(ts.mint)
                
                // PAPER MODE: Always bypass vetoes - we want maximum learning
                if (config.paperMode) {
                    checks.add(GateCheck("edge_veto_sticky", true, 
                        "PAPER: veto bypassed for learning (original: ${activeVeto.reason})"))
                    tags.add("edge_veto_bypassed")
                } else {
                    // LIVE MODE: Allow bypass only with strong buy pressure (>= 60%)
                    // If buyers have returned strongly, the veto might be stale
                    val canBypassInLive = ts.meta.pressScore >= 60.0
                    
                    if (canBypassInLive) {
                        checks.add(GateCheck("edge_veto_sticky", true, 
                            "LIVE: veto bypass (buy%=${ts.meta.pressScore.toInt()})"))
                        tags.add("edge_veto_bypassed")
                    } else {
                        blockReason = "EDGE_VETO_ACTIVE"
                        blockLevel = BlockLevel.EDGE
                        checks.add(GateCheck("edge_veto_sticky", false, 
                            "Vetoed ${remainingSec}s ago: ${activeVeto.reason} (quality=${activeVeto.quality})"))
                        tags.add("edge_veto_sticky")
                    }
                }
            } else {
                checks.add(GateCheck("edge_veto_sticky", true, "No active veto"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1j: PAPER MODE QUALITY FILTER
        // 
        // For QUALITY training data, paper mode still needs SOME filters.
        // We want trades with real market signals, not pure garbage.
        // 
        // MINIMUM QUALITY THRESHOLDS (Paper Mode):
        //   - Buy pressure >= 50% (just above distribution zone)
        //   - Liquidity >= $3000 (actually tradeable)
        // 
        // Distribution triggers at ~48%. 50% gives 2% buffer - tight but workable.
        // ═══════════════════════════════════════════════════════════════════════
        
        // PAPER MODE: Skip quality filter - we want MAXIMUM trades for learning
        // The AI needs diverse data including low-quality trades to learn patterns
        if (config.paperMode && blockReason == null) {
            checks.add(GateCheck("paper_quality", true, "PAPER: quality filter skipped for max learning"))
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1j: PHASE FILTER
        // 
        // LIVE MODE: Strict - unknown phases need high conviction
        // PAPER MODE: Relaxed - unknown phases allowed if buy% >= 52
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null && candidate.phase.lowercase().contains("unknown")) {
            if (config.paperMode) {
                // PAPER MODE: Allow ALL unknown phases for learning
                // We want maximum trades - AI learns from outcomes
                checks.add(GateCheck("phase_filter", true, "PAPER: unknown phase allowed for learning"))
                tags.add("phase_unknown_allowed")
            } else {
                // LIVE MODE: Auto-adjusted thresholds based on learning progress
                // Starts loose, tightens as brain learns
                val minScore = lerp(20.0, 45.0, currentAdjusted.progress)
                val minBuyPressure = lerp(35.0, 52.0, currentAdjusted.progress)
                val isHighScore = candidate.entryScore >= minScore
                val isHighBuyPressure = ts.meta.pressScore >= minBuyPressure
                
                // Allow if EITHER condition is met (not both)
                if (!isHighScore && !isHighBuyPressure) {
                    blockReason = "UNKNOWN_PHASE_LOW_CONVICTION"
                    blockLevel = BlockLevel.CONFIDENCE
                    checks.add(GateCheck("phase_filter", false, 
                        "phase=${candidate.phase} score=${candidate.entryScore.toInt()}<${minScore.toInt()} AND buy%=${ts.meta.pressScore.toInt()}<${minBuyPressure.toInt()} [phase:${currentAdjusted.learningPhase}]"))
                    tags.add("phase_unknown_weak")
                } else {
                    checks.add(GateCheck("phase_filter", true, "unknown phase OK (score=${candidate.entryScore.toInt()} OR buy%=${ts.meta.pressScore.toInt()}) [${currentAdjusted.learningPhase}]"))
                }
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("phase_filter", true, "phase=${candidate.phase}"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 2: EDGE VETO
        // 
        // LIVE MODE: Edge veto ENFORCED strictly
        // PAPER MODE: Edge veto OVERRIDABLE with market confirmation
        //             
        // Edge says "SKIP" based on pattern analysis, but it could be wrong.
        // In paper mode, we override IF the market shows decent interest:
        //   - Buy pressure >= 54% (lowered to allow more learning)
        //   - Liquidity >= $5000 (real money in the pool)
        // 
        // 54% gives 6% buffer above distribution zone (48%).
        // ─────────────────────────────────────────────────────────────────────
        
        val edgeVerdict = when (candidate.edgeQuality.uppercase()) {
            "STRONG", "A", "A+" -> EdgeVerdict.STRONG
            "WEAK", "B", "OK" -> EdgeVerdict.WEAK
            else -> EdgeVerdict.SKIP
        }
        
        if (blockReason == null && edgeVerdict == EdgeVerdict.SKIP) {
            if (config.paperMode) {
                // ═══════════════════════════════════════════════════════════════════
                // PAPER MODE: Intelligent edge veto handling
                // 
                // Don't completely bypass edge veto - DISTRIBUTION is still dangerous.
                // But allow PROBE trades for learning if token has positive signals.
                // ═══════════════════════════════════════════════════════════════════
                
                val edgePhaseStr = candidate.edgeQuality.uppercase()
                val isDistribution = edgePhaseStr.contains("DIST") || edgePhaseStr.contains("SKIP")
                
                if (isDistribution && !isBootstrapPhase) {
                    // DISTRIBUTION in mature paper mode: Block to protect learning
                    blockReason = "EDGE_DISTRIBUTION_PAPER"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, 
                        "PAPER: DISTRIBUTION detected (edge=${candidate.edgeQuality}) - not learning from dumps"))
                    tags.add("edge_distribution_blocked")
                } else if (isDistribution && isBootstrapPhase) {
                    // DISTRIBUTION in bootstrap: Convert to PROBE with heavy penalty
                    softPenaltyScore += 15
                    sizeMultiplier *= 0.25  // 75% size reduction
                    isProbeCandidate = true
                    checks.add(GateCheck("edge", true, 
                        "BOOTSTRAP PROBE: DISTRIBUTION (edge=${candidate.edgeQuality}) → -15pts, size×0.25"))
                    tags.add("edge_distribution_probe")
                } else {
                    // Non-distribution edge skip: Allow with mild penalty
                    softPenaltyScore += 5
                    checks.add(GateCheck("edge", true, 
                        "PAPER: edge veto soft-bypassed (edge=${candidate.edgeQuality}) → -5pts"))
                    tags.add("edge_veto_softened_paper")
                }
            } else {
                // ═══════════════════════════════════════════════════════════════════
                // LIVE MODE: Auto-adjusted edge veto based on learning progress
                // 
                // Starts VERY LOOSE (bootstrap) and tightens as brain learns.
                // Override thresholds scale with learning progress.
                // ═══════════════════════════════════════════════════════════════════
                val liveMinBuyPressure = currentAdjusted.edgeMinBuyPressure
                val liveMinLiquidity = currentAdjusted.edgeMinLiquidity
                val liveMinEntryScore = currentAdjusted.edgeMinScore
                
                val hasStrongBuyers = ts.meta.pressScore >= liveMinBuyPressure
                val hasGoodLiquidity = ts.lastLiquidityUsd >= liveMinLiquidity
                val hasDecentScore = candidate.entryScore >= liveMinEntryScore
                
                // Override if ANY of these conditions met
                if (hasStrongBuyers || hasGoodLiquidity || hasDecentScore) {
                    // Market confirmation in LIVE mode - override Edge veto
                    val reason = when {
                        hasStrongBuyers -> "buy%=${ts.meta.pressScore.toInt()}>=${liveMinBuyPressure.toInt()}"
                        hasDecentScore -> "score=${candidate.entryScore.toInt()}>=${liveMinEntryScore.toInt()}"
                        else -> "liq=$${ts.lastLiquidityUsd.toInt()}>=${liveMinLiquidity.toInt()}"
                    }
                    checks.add(GateCheck("edge", true, "LIVE: edge override ($reason) [${currentAdjusted.learningPhase}]"))
                    tags.add("live_edge_override")
                } else {
                    // No market confirmation - respect Edge veto
                    val missingReasons = mutableListOf<String>()
                    if (!hasStrongBuyers) missingReasons.add("buy%=${ts.meta.pressScore.toInt()}<${liveMinBuyPressure.toInt()}")
                    if (!hasGoodLiquidity) missingReasons.add("liq=$${ts.lastLiquidityUsd.toInt()}<${liveMinLiquidity.toInt()}")
                    if (!hasDecentScore) missingReasons.add("score=${candidate.entryScore.toInt()}<${liveMinEntryScore.toInt()}")
                    
                    blockReason = "EDGE_VETO_${candidate.edgeQuality}"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, "edge=${candidate.edgeQuality} | no override: ${missingReasons.joinToString(", ")} [${currentAdjusted.learningPhase}]"))
                    tags.add("edge_skip")
                }
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("edge", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 2b: NARRATIVE/SCAM DETECTION (Groq LLM)
        // 
        // Uses AI to detect scam patterns in token name, metadata, and social.
        // Adjusts confidence by -30 to +10 based on analysis.
        // Can hard-block obvious scams.
        // ─────────────────────────────────────────────────────────────────────
        
        var narrativeAdjustment = 0
        if (blockReason == null && config.groqApiKey.isNotBlank()) {
            try {
                val tokenSymbol = ts.symbol
                val tokenName = ts.name
                val tokenMint = ts.mint
                
                val narrativeResult = NarrativeDetector.analyze(
                    symbol = tokenSymbol,
                    name = tokenName,
                    mintAddress = tokenMint,
                    description = "",
                    socialMentions = emptyList(),
                    groqApiKey = config.groqApiKey,
                )
                
                // PAPER MODE: Reduced impact but still protect against obvious scams
                // FIX: "Adolph Ratler" type tokens should be blocked even in paper mode
                // LIVE MODE: Reduced impact for reasonable protection without over-blocking
                narrativeAdjustment = if (config.paperMode) {
                    // Paper mode: Still apply SOME narrative penalty for HIGH risk
                    if (narrativeResult.riskLevel == "HIGH" || narrativeResult.riskLevel == "CRITICAL") {
                        -10  // Apply penalty to push below confidence threshold
                    } else {
                        0  // Low/medium risk: let trades happen for learning
                    }
                } else {
                    (narrativeResult.confidenceAdjustment / 4).coerceIn(-6, 4)  // Max -6 in live (reduced from -10)
                }
                
                // FIX: Block HIGH RISK tokens even in paper mode
                // Examples: impersonation, hate symbols, scam patterns
                val isHighRiskContent = narrativeResult.riskLevel == "HIGH" && (
                    narrativeResult.reasoning.contains("impersonate", ignoreCase = true) ||
                    narrativeResult.reasoning.contains("offensive", ignoreCase = true) ||
                    narrativeResult.reasoning.contains("hate", ignoreCase = true) ||
                    narrativeResult.reasoning.contains("racist", ignoreCase = true) ||
                    narrativeResult.reasoning.contains("historical", ignoreCase = true)
                )
                
                if (narrativeResult.shouldBlock && !config.paperMode) {
                    // SOFTENED: Only hard-block VERY obvious scams with specific patterns
                    // Allow trades that might look scammy but could still be profitable
                    val isObviousScam = narrativeResult.riskLevel == "CRITICAL" &&
                        (narrativeResult.blockReason.contains("honey", ignoreCase = true) ||
                         narrativeResult.blockReason.contains("rug confirmed", ignoreCase = true))
                    
                    if (isObviousScam) {
                        blockReason = "NARRATIVE_SCAM: ${narrativeResult.blockReason.take(50)}"
                        blockLevel = BlockLevel.HARD
                        checks.add(GateCheck("narrative", false, 
                            "CRITICAL SCAM: ${narrativeResult.scamIndicators.take(3).joinToString(", ")}"))
                        tags.add("narrative_scam")
                    } else {
                        // Just log warning, don't block
                        checks.add(GateCheck("narrative", true, 
                            "⚠️ risk=${narrativeResult.riskLevel} (proceeding anyway for learning)"))
                        tags.add("narrative_warning")
                    }
                } else if (isHighRiskContent && config.paperMode) {
                    // FIX: Block HIGH RISK content even in paper mode
                    // "Adolph Ratler" type tokens are not worth learning from
                    blockReason = "NARRATIVE_HIGH_RISK: ${narrativeResult.reasoning.take(50)}"
                    blockLevel = BlockLevel.MODE
                    checks.add(GateCheck("narrative", false, 
                        "HIGH RISK content blocked: ${narrativeResult.reasoning.take(80)}"))
                    tags.add("narrative_high_risk_blocked")
                } else if (narrativeResult.riskLevel == "CRITICAL" || narrativeResult.riskLevel == "HIGH") {
                    checks.add(GateCheck("narrative", true, 
                        "risk=${narrativeResult.riskLevel} adj=$narrativeAdjustment | ${narrativeResult.reasoning.take(60)}"))
                    tags.add("narrative_${narrativeResult.riskLevel.lowercase()}")
                } else {
                    checks.add(GateCheck("narrative", true, "risk=${narrativeResult.riskLevel} adj=$narrativeAdjustment"))
                }
            } catch (e: Exception) {
                checks.add(GateCheck("narrative", true, "skipped (error)"))
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("narrative", true, "skipped (no key)"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 2c: GEMINI AI CO-PILOT (Advanced Analysis)
        // 
        // Multi-function AI layer using Gemini 2.0 Flash:
        // - Deep narrative/scam detection with reasoning
        // - Viral potential assessment
        // - Risk scoring
        // Only runs in live mode to conserve API quota.
        // ─────────────────────────────────────────────────────────────────────
        
        var geminiRiskScore = 50.0
        var geminiRecommendation = "WATCH"
        if (blockReason == null && !config.paperMode && config.geminiEnabled) {
            try {
                // Quick scam check first (no API call)
                val quickCheck = GeminiCopilot.quickScamCheck(ts.symbol, ts.name)
                if (quickCheck == true) {
                    blockReason = "GEMINI_QUICK_SCAM: Pattern detected in ${ts.symbol}"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("gemini_quick", false, "Quick scam pattern detected"))
                    tags.add("gemini_scam")
                } else {
                    // Full narrative analysis
                    val analysis = GeminiCopilot.analyzeNarrative(
                        symbol = ts.symbol,
                        name = ts.name,
                        description = "",
                        socialMentions = emptyList(),
                    )
                    
                    if (analysis != null) {
                        geminiRiskScore = 100.0 - analysis.scamConfidence
                        geminiRecommendation = analysis.recommendation
                        
                        // Block high-confidence scams
                        if (analysis.isScam && analysis.scamConfidence >= 80) {
                            blockReason = "GEMINI_SCAM: ${analysis.scamType} (${analysis.scamConfidence.toInt()}% conf)"
                            blockLevel = BlockLevel.HARD
                            checks.add(GateCheck("gemini_narrative", false, 
                                "SCAM: ${analysis.reasoning.take(60)}"))
                            tags.add("gemini_blocked")
                        } else if (analysis.recommendation == "AVOID" && analysis.scamConfidence >= 60) {
                            // Soft warning - reduce confidence but don't block
                            narrativeAdjustment -= 5
                            checks.add(GateCheck("gemini_narrative", true, 
                                "⚠️ AVOID rec | scam=${analysis.scamConfidence.toInt()}% | ${analysis.narrativeType}"))
                            tags.add("gemini_warning")
                        } else if (analysis.recommendation == "BUY" && analysis.viralPotential >= 70) {
                            // Boost confidence for high viral potential
                            narrativeAdjustment += 3
                            checks.add(GateCheck("gemini_narrative", true, 
                                "✨ viral=${analysis.viralPotential.toInt()}% | ${analysis.narrativeType} | ${analysis.greenFlags.take(2).joinToString(", ")}"))
                            tags.add("gemini_bullish")
                        } else {
                            checks.add(GateCheck("gemini_narrative", true, 
                                "${analysis.recommendation} | viral=${analysis.viralPotential.toInt()}% | ${analysis.narrativeType}"))
                        }
                    } else {
                        checks.add(GateCheck("gemini_narrative", true, "skipped (API timeout)"))
                    }
                }
            } catch (e: Exception) {
                checks.add(GateCheck("gemini_narrative", true, "skipped (error: ${e.message?.take(30)})"))
            }
        } else if (config.paperMode) {
            checks.add(GateCheck("gemini_narrative", true, "skipped (paper mode)"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 2d: ORTHOGONAL SIGNAL AGGREGATION
        // 
        // Collects ALL AI signals and ensures they are INDEPENDENT.
        // Correlated signals are down-weighted to avoid double-counting.
        // 
        // 8 ORTHOGONAL CATEGORIES:
        // 1. PRICE_ACTION    - Momentum (ONE signal)
        // 2. VOLUME_FLOW     - Volume anomaly (ONE signal)
        // 3. LIQUIDITY       - Pool depth (ONE signal)
        // 4. ON_CHAIN        - Whale + holder entropy (ONE signal)
        // 5. TEMPORAL        - Time patterns (ONE signal)
        // 6. NARRATIVE       - Unified LLM (ONE signal - Groq+Gemini merged)
        // 7. PATTERN_MEMORY  - Historical similarity (ONE signal)
        // 8. MARKET_CONTEXT  - Regime + correlation (ONE signal)
        // ─────────────────────────────────────────────────────────────────────
        
        var orthogonalBonus = 0
        if (blockReason == null) {
            try {
                // Collect orthogonal signals from existing AI layer results
                // Use UnifiedNarrativeAI for merged Groq+Gemini signal
                val unifiedNarrative = try {
                    UnifiedNarrativeAI.analyze(ts.symbol, ts.name, "")
                } catch (_: Exception) { null }
                
                // ════════════════════════════════════════════════════════════════
                // LOG 429 FALLBACK STATUS
                // When Gemini is rate-limited, log clearly so the user knows AI is
                // operating in degraded mode but decisions are still being made.
                // ════════════════════════════════════════════════════════════════
                val geminiStatus = GeminiCopilot.getRateLimitStatus()
                if (geminiStatus != "OK" && unifiedNarrative?.source == "default") {
                    ErrorLogger.info("FDG", "⚠️ AI DEGRADED: Gemini $geminiStatus - using neutral narrative for ${ts.symbol}")
                }
                
                val orthogonalAssessment = OrthogonalSignals.collectSignals(
                    ts = ts,
                    momentumScore = candidate.aiConfidence,  // Use AI confidence as momentum proxy
                    liquidityScore = if (ts.lastLiquidityUsd > 5000) 70.0 else if (ts.lastLiquidityUsd > 1000) 50.0 else 30.0,
                    whaleSignal = null,  // WhaleTrackerAI result would go here if available
                    timeScore = null,    // TimeOptimizationAI result would go here if available
                    narrativeScore = unifiedNarrative?.score ?: (50.0 + narrativeAdjustment * 5).coerceIn(0.0, 100.0),
                    patternMatchScore = null,  // TokenWinMemory result would go here if available
                    marketRegimeScore = null,  // MarketRegimeAI result would go here if available
                    topHolderPcts = emptyList(),  // Would come from rugcheck data
                    tokenReturns = ts.history.takeLast(10).zipWithNext { a, b -> 
                        if (a.priceUsd > 0) ((b.priceUsd - a.priceUsd) / a.priceUsd) * 100 else 0.0 
                    },
                    marketReturns = emptyList(),  // Would need SOL price history
                )
                
                // Apply orthogonal assessment
                val compositeScore = orthogonalAssessment.compositeScore
                val agreementRatio = orthogonalAssessment.agreementRatio
                
                // Bonus/penalty based on signal agreement
                orthogonalBonus = when {
                    agreementRatio >= 0.8 && compositeScore > 20 -> 5   // Strong bullish consensus
                    agreementRatio >= 0.8 && compositeScore < -20 -> -5 // Strong bearish consensus
                    agreementRatio < 0.5 -> -2  // Signals disagree = uncertainty
                    else -> 0
                }
                
                // Log orthogonal diagnosis for debugging
                val presentSignals = orthogonalAssessment.signals.size
                val missingCount = orthogonalAssessment.missingCategories.size
                
                checks.add(GateCheck("orthogonal", true, 
                    "score=${compositeScore.toInt()} agree=${(agreementRatio*100).toInt()}% signals=$presentSignals/8 bonus=$orthogonalBonus"))
                
                if (orthogonalBonus != 0) {
                    tags.add("orthogonal:$orthogonalBonus")
                }
                if (agreementRatio >= 0.8) {
                    tags.add("signal_consensus")
                }
                
            } catch (e: Exception) {
                checks.add(GateCheck("orthogonal", true, "skipped (error: ${e.message?.take(30)})"))
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 3: ADAPTIVE CONFIDENCE THRESHOLD
        // 
        // Uses the fluid confidence layer that adapts to market conditions.
        // The threshold adjusts based on volatility, win rate, buy pressure,
        // and session performance - making the bot smarter in both modes.
        // ─────────────────────────────────────────────────────────────────────
        
        val confidenceThreshold = getAdaptiveConfidence(config.paperMode, ts)
        val isBootstrap = currentConditions.totalSessionTrades < 30
        val bootstrapTag = if (isBootstrap) " [BOOTSTRAP]" else ""
        
        // Apply narrative adjustment AND orthogonal bonus to confidence
        val adjustedConfidence = (candidate.aiConfidence + narrativeAdjustment + orthogonalBonus).coerceIn(0.0, 100.0)
        val narrativeTag = if (narrativeAdjustment != 0) " [NAR:$narrativeAdjustment]" else ""
        val orthoTag = if (orthogonalBonus != 0) " [ORTHO:$orthogonalBonus]" else ""
        
        // ─────────────────────────────────────────────────────────────────────
        // BOOTSTRAP CONFIDENCE OVERRIDE
        // 
        // In bootstrap mode, low confidence should NOT hard block if:
        //   - Token has positive memory (repeat winner, good past performance)
        //   - No hard safety flags active
        //   - Liquidity above minimum threshold
        // 
        // Instead: Allow as PROBE with reduced size (25-40%)
        // ─────────────────────────────────────────────────────────────────────
        
        var confidenceProbe = false
        var confidenceProbeSizeMultiplier = 1.0
        
        if (blockReason == null && adjustedConfidence < confidenceThreshold) {
            // Check if we should allow as PROBE in bootstrap
            val hasPositiveMemory = try {
                val memMult = TokenWinMemory.getConfidenceMultiplier(
                    ts.mint, ts.symbol, ts.name, ts.lastMcap, 
                    ts.lastLiquidityUsd, 50.0, ts.phase, ts.source
                )
                memMult >= 1.0  // Memory is neutral or positive
            } catch (_: Exception) { false }
            
            val isRepeatWinner = try { TokenWinMemory.isKnownWinner(ts.mint) } catch (_: Exception) { false }
            val hasNoHardBlocks = blockReason == null
            val hasMinLiquidity = ts.lastLiquidityUsd >= 3000
            
            // BOOTSTRAP PROBE: Allow low confidence with positive signals
            if (isBootstrap && config.paperMode && hasNoHardBlocks && hasMinLiquidity && 
                (isRepeatWinner || hasPositiveMemory)) {
                // Convert to PROBE instead of blocking
                confidenceProbe = true
                isProbeCandidate = true
                
                // Size reduction based on how far below threshold
                val confidenceGap = confidenceThreshold - adjustedConfidence
                confidenceProbeSizeMultiplier = when {
                    isRepeatWinner -> 0.4  // 40% size for repeat winners
                    confidenceGap < 10 -> 0.35  // 35% if close to threshold
                    else -> 0.25  // 25% if far below
                }
                sizeMultiplier *= confidenceProbeSizeMultiplier
                
                val probeReason = if (isRepeatWinner) "REPEAT_WINNER" else "POSITIVE_MEMORY"
                checks.add(GateCheck("confidence", true, 
                    "BOOTSTRAP PROBE: conf=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}% BUT $probeReason → size×${confidenceProbeSizeMultiplier}"))
                tags.add("bootstrap_confidence_probe")
                tags.add("probe_reason:$probeReason")
                
                ErrorLogger.info("FDG", "🔬 BOOTSTRAP PROBE: ${ts.symbol} | conf=${adjustedConfidence.toInt()}% | $probeReason | size×${confidenceProbeSizeMultiplier}")
            } else {
                // Normal confidence block
                blockReason = "LOW_CONFIDENCE_${adjustedConfidence.toInt()}%$bootstrapTag$narrativeTag$orthoTag"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("confidence", false, 
                    "conf=${candidate.aiConfidence.toInt()}%+nar=$narrativeAdjustment+ortho=$orthogonalBonus=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}%$bootstrapTag (adaptive)"))
                tags.add("low_confidence")
                tags.add("adaptive_conf:${confidenceThreshold.toInt()}")
                if (isBootstrap) tags.add("bootstrap_phase")
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("confidence", true, 
                "conf=${candidate.aiConfidence.toInt()}%+nar=$narrativeAdjustment+ortho=$orthogonalBonus=${adjustedConfidence.toInt()}% >= ${confidenceThreshold.toInt()}%$bootstrapTag (adaptive)"))
            if (isBootstrap) tags.add("bootstrap_phase")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 4: MODE-SPECIFIC RULES
        // ─────────────────────────────────────────────────────────────────────
        
        if (blockReason == null && !config.paperMode) {
            // LIVE MODE additional restrictions
            
            // Must have autoTrade enabled
            if (!config.autoTrade) {
                blockReason = "LIVE_AUTO_TRADE_DISABLED"
                blockLevel = BlockLevel.MODE
                checks.add(GateCheck("auto_trade", false, "autoTrade=false"))
            }
            
            // Quality check in live - only block truly garbage setups
            // C quality is okay if other signals are good
            // Only block "D" or worse quality
            if (blockReason == null && candidate.setupQuality !in listOf("A+", "A", "B", "C")) {
                blockReason = "LIVE_QUALITY_TOO_LOW"
                blockLevel = BlockLevel.MODE
                checks.add(GateCheck("live_quality", false, "quality=${candidate.setupQuality} (live requires C+)"))
            }
        }
        
        if (blockReason == null) {
            checks.add(GateCheck("mode_rules", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 4.5: LIQUIDITY DEPTH AI CHECK
        // 
        // Monitor real-time liquidity changes:
        // - COLLAPSE (>30% drop) = HARD BLOCK (rug likely)
        // - DRAINING = Warning, reduce size
        // - GROWING = Boost (project health)
        // ─────────────────────────────────────────────────────────────────────
        
        if (blockReason == null) {
            val liqSignal = LiquidityDepthAI.getSignal(ts.mint, ts.symbol, isOpenPosition = false)
            
            // Hard block on liquidity collapse - BUT only for severe collapses during learning
            // During BOOTSTRAP/LEARNING phases, allow moderate collapses for learning
            val isLearningPhase = currentAdjusted.learningPhase != LearningPhase.MATURE
            val isSevereCollapse = liqSignal.reason?.contains("-30%") == true || 
                                   liqSignal.reason?.contains("-40%") == true ||
                                   liqSignal.reason?.contains("-50%") == true
            
            if (liqSignal.shouldBlock && !config.paperMode && (!isLearningPhase || isSevereCollapse)) {
                blockReason = liqSignal.blockReason ?: "LIQUIDITY_COLLAPSE"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("liquidity_ai", false, 
                    "COLLAPSE: ${liqSignal.reason}"))
                tags.add("liquidity_collapse")
            } else if (liqSignal.shouldBlock && !config.paperMode && isLearningPhase) {
                // Learning phase: Log warning but allow trade for learning
                checks.add(GateCheck("liquidity_ai", true, 
                    "LEARNING: collapse warning (${liqSignal.reason}) - allowed for learning [${currentAdjusted.learningPhase}]"))
                tags.add("liquidity_collapse_learning")
            } else if (liqSignal.shouldBlock && config.paperMode) {
                // Paper mode: Log warning but allow trade for learning
                checks.add(GateCheck("liquidity_ai", true, 
                    "PAPER: collapse warning (${liqSignal.reason}) - allowed for learning"))
                tags.add("liquidity_collapse_paper")
            } else {
                // Log liquidity trend for debugging
                val depthLabel = liqSignal.depthQuality.name.lowercase()
                checks.add(GateCheck("liquidity_ai", true, 
                    "trend=${liqSignal.trend.name.lowercase()} depth=$depthLabel adj=${liqSignal.entryAdjustment.toInt()}"))
                
                // Tag significant trends
                when (liqSignal.signal) {
                    LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE -> tags.add("liquidity_spike")
                    LiquidityDepthAI.SignalType.LIQUIDITY_GROWING -> tags.add("liquidity_growing")
                    LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING -> tags.add("liquidity_draining")
                    else -> { /* no tag */ }
                }
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 4.75: EXPECTED VALUE (EV) VALIDATION
        // 
        // Only execute trades with positive expected value.
        // EV = Σ (P_i × Outcome_i) for probability-weighted scenarios.
        // ─────────────────────────────────────────────────────────────────────
        
        var evResult: EVCalculator.EVResult? = null
        
        // EV gating: auto-enabled only when brain reaches MATURE phase
        val useEvGating = currentAdjusted.evGatingEnabled && !config.paperMode
        
        if (blockReason == null && useEvGating) {
            // Use currentConditions for market regime estimation
            val marketRegimeStr = when {
                currentConditions.recentWinRate > 60 -> "BULL"
                currentConditions.recentWinRate < 40 -> "BEAR"
                else -> "NEUTRAL"
            }
            val historicalWinRateValue = currentConditions.recentWinRate / 100.0
            
            evResult = EVCalculator.calculate(
                ts = ts,
                entryScore = candidate.entryScore,
                quality = candidate.finalQuality,
                marketRegime = marketRegimeStr,
                historicalWinRate = historicalWinRateValue
            )
            
            checks.add(GateCheck("ev_analysis", evResult.isPositiveEV,
                "EV=${String.format("%+.1f", evResult.expectedPnlPct)}% " +
                "Win=${String.format("%.0f", evResult.winProbability * 100)}% " +
                "Rug=${String.format("%.0f", evResult.rugProbability * 100)}% " +
                "Kelly=${String.format("%.1f", evResult.kellyFraction * 100)}%"))
            
            // Block if EV is below threshold
            if (evResult.expectedValue < minExpectedValue) {
                blockReason = "NEGATIVE_EV_${String.format("%.0f", evResult.expectedPnlPct)}%"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("ev_threshold", false,
                    "EV ${String.format("%.2f", evResult.expectedValue)} < min ${String.format("%.2f", minExpectedValue)}"))
                tags.add("blocked_negative_ev")
            }
            // Block if rug probability is too high (using auto-adjusted threshold)
            else if (evResult.rugProbability > currentAdjusted.maxRugProbability) {
                blockReason = "HIGH_RUG_PROB_${String.format("%.0f", evResult.rugProbability * 100)}%"
                blockLevel = BlockLevel.HARD  // High rug probability is a hard block
                checks.add(GateCheck("rug_probability", false,
                    "Rug prob ${String.format("%.0f", evResult.rugProbability * 100)}% > max ${String.format("%.0f", currentAdjusted.maxRugProbability * 100)}% [${currentAdjusted.learningPhase}]"))
                tags.add("blocked_high_rug_prob")
            }
            else {
                tags.add("positive_ev")
                if (evResult.expectedPnlPct > 20) tags.add("high_ev")
                if (evResult.kellyFraction > 0.05) tags.add("kelly_favorable")
            }
            
            ErrorLogger.info("EV", "📊 ${ts.symbol}: ${evResult.summary()}")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 5: SIZING VALIDATION
        // ─────────────────────────────────────────────────────────────────────
        
        var finalSize = proposedSizeSol
        
        // ═══════════════════════════════════════════════════════════════════
        // TOKEN WIN MEMORY: Boost size for tokens similar to past winners
        // ═══════════════════════════════════════════════════════════════════
        val winMemoryMultiplier = try {
            // Get buy pressure from history
            val latestBuyPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
            
            TokenWinMemory.getConfidenceMultiplier(
                mint = ts.mint,
                symbol = ts.symbol,
                name = ts.name,
                mcap = ts.lastMcap,
                liquidity = ts.lastLiquidityUsd,
                buyPercent = latestBuyPct,
                phase = ts.phase,
                source = ts.source,
            )
        } catch (_: Exception) { 1.0 }
        
        if (winMemoryMultiplier != 1.0) {
            val originalSize = finalSize
            finalSize = (finalSize * winMemoryMultiplier).coerceIn(0.01, 1.0)
            if (winMemoryMultiplier > 1.0) {
                tags.add("size_boosted_win_memory")
                checks.add(GateCheck("win_memory", true,
                    "Size boosted ${originalSize.format(3)} → ${finalSize.format(3)} (memory=${winMemoryMultiplier.format(2)}x)"))
                
                // Check if this is a known winner
                if (TokenWinMemory.isKnownWinner(ts.mint)) {
                    val past = TokenWinMemory.getWinnerStats(ts.mint)
                    tags.add("REPEAT_WINNER")
                    checks.add(GateCheck("repeat_winner", true,
                        "🔥 REPEAT WINNER: ${past?.timesTraded ?: 0} trades, +${past?.totalPnl?.toInt() ?: 0}% total"))
                }
            } else {
                tags.add("size_reduced_win_memory")
                checks.add(GateCheck("win_memory", true,
                    "Size reduced ${originalSize.format(3)} → ${finalSize.format(3)} (dissimilar to winners)"))
            }
        }
        
        // Apply liquidity depth size multiplier
        val liqSizeMultiplier = LiquidityDepthAI.getSizeMultiplier(ts.mint, ts.symbol)
        if (liqSizeMultiplier < 1.0) {
            val originalSize = finalSize
            finalSize = (finalSize * liqSizeMultiplier).coerceAtLeast(0.01)
            if (finalSize < originalSize) {
                tags.add("size_reduced_liq_depth")
                checks.add(GateCheck("liquidity_size", true, 
                    "Size adjusted ${originalSize.format(3)} → ${finalSize.format(3)} (depth=${liqSizeMultiplier.format(2)}x)"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // COLLECTIVE LEARNING: Adjust based on hive mind pattern data
        // Uses aggregated learnings from all AATE instances worldwide
        // ═══════════════════════════════════════════════════════════════════
        val collectiveAdj = try {
            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                val marketSentiment = when {
                    ts.meta.emafanAlignment.contains("BULL") -> "BULL"
                    ts.meta.emafanAlignment.contains("BEAR") -> "BEAR"
                    else -> "NEUTRAL"
                }
                com.lifecyclebot.collective.CollectiveLearning.getPatternScoreAdjustment(
                    entryPhase = ts.phase.ifEmpty { "UNKNOWN" },
                    tradingMode = tradingModeTag?.name ?: "STANDARD",
                    discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                    liquidityUsd = ts.lastLiquidityUsd,
                    emaTrend = marketSentiment
                )
            } else 0
        } catch (_: Exception) { 0 }
        
        if (collectiveAdj != 0) {
            val direction = if (collectiveAdj > 0) "boost" else "penalty"
            val multiplier = if (collectiveAdj > 0) {
                1.0 + (collectiveAdj.toDouble() / 100.0)  // +30 = 1.3x
            } else {
                1.0 + (collectiveAdj.toDouble() / 100.0)  // -30 = 0.7x
            }
            val originalSize = finalSize
            finalSize = (finalSize * multiplier).coerceIn(0.01, 1.0)
            
            tags.add("collective_${direction}")
            checks.add(GateCheck("collective_learning", collectiveAdj > 0,
                "🌐 Collective $direction: ${originalSize.format(3)} → ${finalSize.format(3)} (adj=$collectiveAdj)"))
            
            // Log significant adjustments
            if (kotlin.math.abs(collectiveAdj) >= 20) {
                ErrorLogger.info("FDG", "🌐 COLLECTIVE: ${ts.symbol} | " +
                    "adj=$collectiveAdj | ${if(collectiveAdj > 0) "PROVEN_WINNER" else "KNOWN_LOSER"}")
            }
        }
        
        // Apply AI CrossTalk size multiplier (correlated signals can boost/reduce size)
        val crossTalkSignal = try {
            AICrossTalk.analyzeCrossTalk(ts.mint, ts.symbol, isOpenPosition = false)
        } catch (_: Exception) { null }
        
        if (crossTalkSignal != null && crossTalkSignal.sizeMultiplier != 1.0) {
            val originalSize = finalSize
            finalSize = (finalSize * crossTalkSignal.sizeMultiplier).coerceIn(0.01, 1.0)
            if (finalSize != originalSize) {
                val direction = if (crossTalkSignal.sizeMultiplier > 1.0) "boosted" else "reduced"
                tags.add("size_${direction}_crosstalk")
                checks.add(GateCheck("crosstalk_size", true,
                    "Size $direction ${originalSize.format(3)} → ${finalSize.format(3)} (${crossTalkSignal.signalType.name})"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // KELLY CRITERION SIZING (EV-based optimal position sizing)
        // ═══════════════════════════════════════════════════════════════════
        if (blockReason == null && useKellySizing && evResult != null && !config.paperMode) {
            val kellyRecommendedSize = evResult.kellyFraction * kellyFraction  // Use fractional Kelly
            
            if (kellyRecommendedSize > 0 && kellyRecommendedSize < finalSize) {
                val originalSize = finalSize
                // Kelly suggests smaller size - use it (risk management)
                finalSize = kellyRecommendedSize.coerceIn(0.003, maxKellySize)
                
                checks.add(GateCheck("kelly_sizing", true,
                    "Kelly: ${originalSize.format(4)} → ${finalSize.format(4)} " +
                    "(Kelly=${String.format("%.1f", evResult.kellyFraction * 100)}% × $kellyFraction)"))
                tags.add("kelly_sized")
            } else if (kellyRecommendedSize > finalSize * 1.5 && evResult.isPositiveEV) {
                // Kelly suggests larger size and EV is very positive - modest boost
                val originalSize = finalSize
                finalSize = (finalSize * 1.25).coerceAtMost(maxKellySize)
                
                checks.add(GateCheck("kelly_boost", true,
                    "Kelly boost: ${originalSize.format(4)} → ${finalSize.format(4)} (high +EV)"))
                tags.add("kelly_boosted")
            }
        }
        
        if (blockReason == null) {
            // Minimum size check
            val minSize = if (config.paperMode) 0.001 else 0.01
            if (finalSize < minSize) {
                blockReason = "SIZE_TOO_SMALL"
                blockLevel = BlockLevel.SIZE
                checks.add(GateCheck("min_size", false, "size=$finalSize < $minSize"))
            } else {
                checks.add(GateCheck("min_size", true, null))
            }
            
            // Maximum size cap (safety)
            val maxSize = if (config.paperMode) 1.0 else 0.5
            if (finalSize > maxSize) {
                finalSize = maxSize
                checks.add(GateCheck("max_size", true, "capped from $proposedSizeSol to $maxSize"))
                tags.add("size_capped")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // BUILD FINAL DECISION
        // ─────────────────────────────────────────────────────────────────────
        
        // Add source/phase tags
        if (ts.source.isNotBlank()) tags.add("src:${ts.source}")
        if (ts.phase.isNotBlank()) tags.add("phase:${ts.phase}")
        
        // ─────────────────────────────────────────────────────────────────────
        // APPLY BOOTSTRAP SOFT PENALTIES
        // In bootstrap mode, soft gates add penalties instead of blocking.
        // Apply accumulated penalties to size and track probe status.
        // ─────────────────────────────────────────────────────────────────────
        
        // Combine all penalty sources
        val totalSoftPenalty = softPenaltyScore + behaviorPenalty
        val combinedSizeMultiplier = sizeMultiplier * behaviorSizeMultiplier
        val isAnyProbe = isProbeCandidate || behaviorProbe
        
        // Apply size reduction from soft penalties
        if (combinedSizeMultiplier < 1.0 && blockReason == null) {
            val originalSize = finalSize
            finalSize = (finalSize * combinedSizeMultiplier).coerceAtLeast(0.02)
            checks.add(GateCheck("bootstrap_size_cut", true, 
                "Size cut for probe: ${originalSize.format(4)} × ${combinedSizeMultiplier.format(2)} = ${finalSize.format(4)}"))
            tags.add("bootstrap_size_reduced")
        }
        
        // Log total penalty if any
        if (totalSoftPenalty > 0 && blockReason == null) {
            checks.add(GateCheck("bootstrap_penalty", true, 
                "Total soft penalty: -${totalSoftPenalty}pts (applied to confidence eval)"))
            tags.add("bootstrap_penalized")
        }
        
        val shouldTrade = blockReason == null && candidate.shouldTrade
        
        // ─────────────────────────────────────────────────────────────────────
        // ADAPTIVE FILTERING TRACKING
        // Record blocks and executions for anti-freeze protection
        // ─────────────────────────────────────────────────────────────────────
        
        if (!shouldTrade && blockReason != null) {
            recordBlock(blockReason)
        } else if (shouldTrade) {
            recordTradeExecuted()
        }
        
        // Add adaptive status to tags if relaxation is active
        if (adaptiveRelaxationActive) {
            tags.add("adaptive_relaxed")
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // DETERMINE APPROVAL CLASS
        // This enables clean analytics separation between:
        //   - Benchmark: Strategy quality under strict rules
        //   - Exploration: Learning from weaker setups
        // ─────────────────────────────────────────────────────────────────────
        
        // Get the live adaptive confidence for benchmark comparison
        val liveAdaptiveConf = getAdaptiveConfidence(isPaperMode = false, ts)
        
        val (approvalClass, approvalReason) = when {
            // Blocked trades
            !shouldTrade -> ApprovalClass.BLOCKED to "blocked: ${blockReason ?: "unknown"}"
            
            // Live mode - all approvals are strict
            !config.paperMode -> ApprovalClass.LIVE to "live mode approval (adaptive conf: ${liveAdaptiveConf.toInt()}%)"
            
            // Paper mode - determine if benchmark, probe, or exploration
            else -> {
                // Would this trade pass LIVE mode rules?
                val wouldPassLiveEdge = edgeVerdict != EdgeVerdict.SKIP
                val wouldPassLiveQuality = candidate.setupQuality in listOf("A+", "A", "B")
                val wouldPassLiveConfidence = candidate.aiConfidence >= liveAdaptiveConf
                
                when {
                    // PROBE: Soft warnings were overridden for bootstrap learning
                    isAnyProbe -> {
                        val probeReasons = mutableListOf<String>()
                        if (dangerZonePenalty > 0) probeReasons.add("time_danger")
                        if (memoryPenalty > 0) probeReasons.add("memory_neg")
                        if (behaviorPenalty > 0) probeReasons.add("behavior_100pct")
                        if (confidenceProbe) probeReasons.add("confidence_override")
                        ApprovalClass.PAPER_PROBE to "probe: soft blocks→penalties (${probeReasons.joinToString(",")}), size×${combinedSizeMultiplier.format(2)}"
                    }
                    
                    // BENCHMARK: Would pass live rules
                    wouldPassLiveEdge && wouldPassLiveQuality && wouldPassLiveConfidence -> {
                        ApprovalClass.PAPER_BENCHMARK to "benchmark: passes live rules (edge=$wouldPassLiveEdge quality=${candidate.setupQuality} conf=${candidate.aiConfidence.toInt()}%>=${liveAdaptiveConf.toInt()}%)"
                    }
                    
                    // EXPLORATION: Relaxed for learning
                    else -> {
                        val relaxedReasons = mutableListOf<String>()
                        if (!wouldPassLiveEdge) relaxedReasons.add("edge=${edgeVerdict.name}")
                        if (!wouldPassLiveQuality) relaxedReasons.add("quality=${candidate.setupQuality}")
                        if (!wouldPassLiveConfidence) relaxedReasons.add("conf=${candidate.aiConfidence.toInt()}%<${liveAdaptiveConf.toInt()}%")
                        ApprovalClass.PAPER_EXPLORATION to "exploration: relaxed ${relaxedReasons.joinToString(", ")}"
                    }
                }
            }
        }
        
        // Add approval class to tags for easy filtering
        tags.add("class:${approvalClass.name}")
        
        return FinalDecision(
            shouldTrade = shouldTrade,
            mode = mode,
            approvalClass = approvalClass,
            quality = candidate.finalQuality,
            confidence = candidate.aiConfidence,
            edge = edgeVerdict,
            blockReason = blockReason,
            blockLevel = blockLevel,
            sizeSol = finalSize,
            tags = tags,
            mint = ts.mint,
            symbol = ts.symbol,
            approvalReason = approvalReason,
            gateChecks = checks,
        )
    }
    
    /**
     * Log a blocked trade for learning purposes.
     * This is how we "learn" from blocked trades without executing them.
     */
    fun logBlockedTrade(decision: FinalDecision, onLog: (String) -> Unit) {
        onLog("🚫 FDG BLOCKED: ${decision.symbol} | ${decision.blockReason} | " +
              "quality=${decision.quality} conf=${decision.confidence.toInt()}% " +
              "edge=${decision.edge.name}")
        
        // Log the gate checks for debugging
        val failedChecks = decision.gateChecks.filter { !it.passed }
        if (failedChecks.isNotEmpty()) {
            onLog("   Failed checks: ${failedChecks.joinToString { "${it.name}(${it.reason})" }}")
        }
    }
    
    /**
     * Log an approved trade with explicit approval class.
     */
    fun logApprovedTrade(decision: FinalDecision, onLog: (String) -> Unit) {
        val classIcon = when (decision.approvalClass) {
            ApprovalClass.LIVE -> "🔴"
            ApprovalClass.PAPER_BENCHMARK -> "🟢"
            ApprovalClass.PAPER_EXPLORATION -> "🟡"
            ApprovalClass.PAPER_PROBE -> "🔵"  // Blue for probe trades
            ApprovalClass.BLOCKED -> "⬛"
        }
        onLog("$classIcon FDG ${decision.approvalClass}: ${decision.symbol} | ${decision.quality} | " +
              "${decision.confidence.toInt()}% | ${decision.sizeSol.format(3)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLD LEARNING (called by BotBrain after trades)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Update thresholds based on learned values from BotBrain.
     */
    fun updateThresholds(
        rugcheckMin: Int? = null,
        buyPressureMin: Double? = null,
        topHolderMax: Double? = null,
    ) {
        rugcheckMin?.let { hardBlockRugcheckMin = it }
        buyPressureMin?.let { hardBlockBuyPressureMin = it }
        topHolderMax?.let { hardBlockTopHolderMax = it }
    }
    
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
