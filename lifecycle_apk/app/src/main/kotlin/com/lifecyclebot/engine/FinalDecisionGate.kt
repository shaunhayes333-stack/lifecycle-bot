package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState

/**
 * FinalDecisionGate (FDG)
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
     */
    enum class ApprovalClass {
        LIVE,              // Live mode approval - strictest rules, real money
        PAPER_BENCHMARK,   // Paper mode, would PASS live rules - benchmark quality
        PAPER_EXPLORATION, // Paper mode, relaxed rules - learning from weaker setups
        BLOCKED,           // Not approved
    }
    
    data class GateCheck(
        val name: String,
        val passed: Boolean,
        val reason: String?,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLDS - Can be learned/adjusted by BotBrain
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Hard block thresholds (non-negotiable - these are DANGEROUS)
    var hardBlockRugcheckMin = 10          // Block if rugcheck score <= this
    var hardBlockBuyPressureMin = 15.0     // Block if buy pressure < this %
    var hardBlockTopHolderMax = 70.0       // Block if top holder > this %
    
    // Base confidence thresholds (these are ADAPTED by AdaptiveConfidence)
    var paperConfidenceBase = 0.0          // Paper mode base: NO confidence minimum (learn from all)
    var liveConfidenceBase = 30.0          // Live base: LOWERED from 40% to allow more trades
    
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
        val baseConfidence = if (isPaperMode) paperConfidenceBase else liveConfidenceBase
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
        // ─────────────────────────────────────────────────────────────────
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
        // CALCULATE FINAL ADAPTIVE CONFIDENCE
        // Clamp to reasonable bounds
        // ─────────────────────────────────────────────────────────────────
        val adaptive = (baseConfidence + adjustment).coerceIn(
            if (isPaperMode) 0.0 else 15.0,   // Min: 0% paper, 15% live (was 20%)
            if (isPaperMode) 60.0 else 75.0   // Max: 60% paper, 75% live (was 80%)
        )
        
        return adaptive
    }
    
    /**
     * Get explanation of current adaptive confidence calculation.
     * Useful for logging/debugging.
     */
    fun getAdaptiveConfidenceExplanation(isPaperMode: Boolean): String {
        val base = if (isPaperMode) paperConfidenceBase else liveConfidenceBase
        val adaptive = getAdaptiveConfidence(isPaperMode)
        val diff = adaptive - base
        val sign = if (diff >= 0) "+" else ""
        
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
            append("AdaptiveConf: base=${base.toInt()}% ${sign}${diff.toInt()}% = ${adaptive.toInt()}% ")
            append("[vol=${currentConditions.avgVolatility.toInt()}% ")
            append("wr=${currentConditions.recentWinRate.toInt()}% ")
            append("buy=${currentConditions.buyPressureTrend.toInt()}% ")
            append("tier=$tierLabel ")
            append("regime=$regimeLabel]")
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
    private const val DISTRIBUTION_COOLDOWN_MS = 2 * 60 * 1000L  // 2 minutes - aggressive paper learning
    
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
     */
    fun isInDistributionCooldown(mint: String): Boolean {
        val exitTime = distributionCooldowns[mint] ?: return false
        val elapsed = System.currentTimeMillis() - exitTime
        return elapsed < DISTRIBUTION_COOLDOWN_MS
    }
    
    /**
     * Get remaining cooldown time in minutes.
     */
    fun getRemainingCooldownMinutes(mint: String): Int {
        val exitTime = distributionCooldowns[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - exitTime
        val remaining = DISTRIBUTION_COOLDOWN_MS - elapsed
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
    // Paper mode: 90 seconds (fast learning)
    // Live mode: 60 seconds (reduced from 90s to allow more opportunities)
    private const val EDGE_VETO_COOLDOWN_PAPER_MS = 90 * 1000L  // 90 seconds for paper
    private const val EDGE_VETO_COOLDOWN_LIVE_MS = 60 * 1000L   // 60 seconds for live (REDUCED)
    
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
    ): FinalDecision {
        val checks = mutableListOf<GateCheck>()
        var blockReason: String? = null
        var blockLevel: BlockLevel? = null
        val tags = mutableListOf<String>()
        
        val mode = if (config.paperMode) TradeMode.PAPER else TradeMode.LIVE
        
        // Use learned thresholds if available
        val rugcheckThreshold = brain?.learnedRugcheckThreshold ?: hardBlockRugcheckMin
        val buyPressureThreshold = brain?.learnedMinBuyPressure ?: hardBlockBuyPressureMin
        val topHolderThreshold = brain?.learnedMaxTopHolder ?: hardBlockTopHolderMax
        
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
        // PAPER MODE: Allow -1 (API error/no data) to enable learning
        // LIVE MODE: Block both -1 and low scores for safety
        val rugcheckBlock = when {
            ts.safety.rugcheckScore == -1 && config.paperMode -> false  // Paper: allow API errors
            ts.safety.rugcheckScore <= rugcheckThreshold -> true         // Block low scores
            else -> false
        }
        if (blockReason == null && rugcheckBlock) {
            blockReason = "HARD_BLOCK_RUGCHECK_${ts.safety.rugcheckScore}"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("rugcheck", false, "score=${ts.safety.rugcheckScore} <= $rugcheckThreshold"))
            tags.add("low_rugcheck")
        } else if (blockReason == null) {
            // Log if paper mode allowed -1
            if (ts.safety.rugcheckScore == -1 && config.paperMode) {
                checks.add(GateCheck("rugcheck", true, "score=-1 (paper: allowed for learning)"))
            } else {
                checks.add(GateCheck("rugcheck", true, null))
            }
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
            
            if (inCooldown) {
                blockReason = "DISTRIBUTION_COOLDOWN_${cooldownMinutes}min"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("distribution", false, "Recently exited distribution, cooldown=${cooldownMinutes}min remaining"))
                tags.add("distribution_cooldown")
            } else {
                // Check other distribution signals
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
                
                val isDistributorConfident = distSignal?.isDistributing == true && distSignal.confidence >= 50
                
                if (isDistributionPhase || hasDistributionTag || isDistributorConfident) {
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
        // GATE 1i: STICKY EDGE VETO (AUTHORITATIVE - applies to ALL modes)
        // 
        // When Edge says "VETOED", that decision is AUTHORITATIVE.
        // The veto is STICKY for 5 minutes to prevent:
        //   1. Edge vetoes at T+0
        //   2. Strategy says BUY at T+10 seconds 
        //   3. FDG approves because it only sees the latest signal
        // 
        // Rule: Once vetoed by Edge, stay blocked for EDGE_VETO_COOLDOWN_MS
        // This ensures Edge's "do not enter" is respected, not overridden.
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null) {
            val activeVeto = hasActiveEdgeVeto(ts.mint)
            if (activeVeto != null) {
                val remainingSec = getVetoRemainingSeconds(ts.mint)
                blockReason = "EDGE_VETO_ACTIVE"
                blockLevel = BlockLevel.EDGE
                checks.add(GateCheck("edge_veto_sticky", false, 
                    "Vetoed ${remainingSec}s ago: ${activeVeto.reason} (quality=${activeVeto.quality})"))
                tags.add("edge_veto_sticky")
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
        
        if (blockReason == null && config.paperMode) {
            val minBuyPressurePaper = 40.0  // LOWERED: 40% - aggressive learning
            val minLiquidityPaper = 1500.0  // $1500 minimum - more trades
            
            val hasBuyerInterest = ts.meta.pressScore >= minBuyPressurePaper
            val hasLiquidity = ts.lastLiquidityUsd >= minLiquidityPaper
            
            if (!hasBuyerInterest) {
                blockReason = "PAPER_NEAR_DISTRIBUTION_${ts.meta.pressScore.toInt()}%"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("paper_quality", false, "buy%=${ts.meta.pressScore.toInt()} < $minBuyPressurePaper (too close to distribution zone)"))
                tags.add("near_distribution")
            } else if (!hasLiquidity) {
                blockReason = "PAPER_LOW_LIQUIDITY_$${ts.lastLiquidityUsd.toInt()}"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("paper_quality", false, "liq=$${ts.lastLiquidityUsd.toInt()} < $minLiquidityPaper"))
                tags.add("paper_illiquid")
            } else {
                checks.add(GateCheck("paper_quality", true, "buy%=${ts.meta.pressScore.toInt()} liq=$${ts.lastLiquidityUsd.toInt()}"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1j: PHASE FILTER
        // 
        // LIVE MODE: Strict - unknown phases need high conviction
        // PAPER MODE: Relaxed - unknown phases allowed if buy% >= 52
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null && candidate.phase.lowercase().contains("unknown")) {
            if (config.paperMode) {
                // PAPER MODE: Allow unknown phases with decent buy pressure
                val minBuyPressureUnknown = 48.0  // LOWERED from 52%
                if (ts.meta.pressScore >= minBuyPressureUnknown) {
                    checks.add(GateCheck("phase_filter", true, "PAPER: unknown phase OK (buy%=${ts.meta.pressScore.toInt()} >= $minBuyPressureUnknown)"))
                    tags.add("phase_unknown_allowed")
                } else {
                    blockReason = "UNKNOWN_PHASE_WEAK_BUYERS"
                    blockLevel = BlockLevel.CONFIDENCE
                    checks.add(GateCheck("phase_filter", false, "unknown phase + weak buyers (buy%=${ts.meta.pressScore.toInt()} < $minBuyPressureUnknown)"))
                    tags.add("phase_unknown_garbage")
                }
            } else {
                // LIVE MODE: Strict filtering for real money
                val minScore = 80.0
                val minBuyPressure = 65.0
                val isHighScore = candidate.entryScore >= minScore
                val isHighBuyPressure = ts.meta.pressScore >= minBuyPressure
                
                if (!isHighScore || !isHighBuyPressure) {
                    blockReason = "UNKNOWN_PHASE_LOW_CONVICTION"
                    blockLevel = BlockLevel.CONFIDENCE
                    checks.add(GateCheck("phase_filter", false, 
                        "phase=${candidate.phase} score=${candidate.entryScore.toInt()}<${minScore.toInt()} OR buy%=${ts.meta.pressScore.toInt()}<${minBuyPressure.toInt()}"))
                    tags.add("phase_unknown_weak")
                } else {
                    checks.add(GateCheck("phase_filter", true, "unknown phase OK (score=${candidate.entryScore.toInt()} buy%=${ts.meta.pressScore.toInt()})"))
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
                // PAPER MODE: Very lenient edge override for maximum learning
                // Allow trades with either decent buyers OR any liquidity above minimum
                val minBuyPressureOverride = 45.0  // LOWERED from 48% - just above distribution
                val minLiquidityOverride = 2000.0  // LOWERED from 2500
                
                val hasStrongBuyers = ts.meta.pressScore >= minBuyPressureOverride
                val hasGoodLiquidity = ts.lastLiquidityUsd >= minLiquidityOverride
                
                // Also allow if setup quality is B+ regardless of edge
                val hasGoodQuality = candidate.setupQuality in listOf("A+", "A", "B")
                
                if (hasStrongBuyers || hasGoodLiquidity || hasGoodQuality) {
                    // Market confirmation - override edge veto for learning
                    checks.add(GateCheck("edge", true, 
                        "PAPER: edge override (buy%=${ts.meta.pressScore.toInt()}>=$minBuyPressureOverride OR liq=$${ts.lastLiquidityUsd.toInt()}>=$minLiquidityOverride OR quality=${candidate.setupQuality})"))
                    tags.add("edge_override_confirmed")
                } else {
                    // No confirmation - respect edge veto
                    blockReason = "EDGE_VETO_NO_CONFIRMATION"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, "edge=${candidate.edgeQuality} no confirm: buy%=${ts.meta.pressScore.toInt()}<$minBuyPressureOverride AND liq<$minLiquidityOverride AND quality=${candidate.setupQuality}"))
                    tags.add("edge_skip_unconfirmed")
                }
            } else {
                // ═══════════════════════════════════════════════════════════════════
                // LIVE MODE: Edge veto with MARKET CONFIRMATION OVERRIDE
                // 
                // Edge can be WRONG. If the market shows strong interest, we allow
                // the trade even if Edge says SKIP. This prevents missing good setups
                // just because the phase is "unknown".
                // 
                // Override conditions (must meet ANY ONE):
                //   - Buy pressure >= 52% (decent buyer interest)
                //   - Liquidity >= $3000 AND entry score >= 25
                //   - Entry score >= 40 (good quality setup)
                // ═══════════════════════════════════════════════════════════════════
                val liveMinBuyPressure = 52.0   // LOWERED from 55%
                val liveMinLiquidity = 3000.0   // LOWERED from $5k
                val liveMinEntryScore = 25.0    // LOWERED from 30
                val liveGoodEntryScore = 40.0   // Good quality override
                
                val hasStrongBuyers = ts.meta.pressScore >= liveMinBuyPressure
                val hasGoodLiquidity = ts.lastLiquidityUsd >= liveMinLiquidity
                val hasDecentScore = candidate.entryScore >= liveMinEntryScore
                val hasGoodScore = candidate.entryScore >= liveGoodEntryScore
                
                // Override if ANY of these conditions met (was requiring ALL)
                if (hasStrongBuyers || (hasGoodLiquidity && hasDecentScore) || hasGoodScore) {
                    // Market confirmation in LIVE mode - override Edge veto
                    val reason = when {
                        hasStrongBuyers -> "buy%=${ts.meta.pressScore.toInt()}>=$liveMinBuyPressure"
                        hasGoodScore -> "score=${candidate.entryScore.toInt()}>=$liveGoodEntryScore"
                        else -> "liq=$${ts.lastLiquidityUsd.toInt()}>=$liveMinLiquidity+score>=$liveMinEntryScore"
                    }
                    checks.add(GateCheck("edge", true, "LIVE: edge override ($reason)"))
                    tags.add("live_edge_override")
                } else {
                    // No market confirmation - respect Edge veto
                    val missingReasons = mutableListOf<String>()
                    if (!hasStrongBuyers) missingReasons.add("buy%=${ts.meta.pressScore.toInt()}<$liveMinBuyPressure")
                    if (!hasGoodLiquidity) missingReasons.add("liq=$${ts.lastLiquidityUsd.toInt()}<$liveMinLiquidity")
                    if (!hasDecentScore) missingReasons.add("score=${candidate.entryScore.toInt()}<$liveMinEntryScore")
                    
                    blockReason = "EDGE_VETO_${candidate.edgeQuality}"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, "edge=${candidate.edgeQuality} | no override: ${missingReasons.joinToString(", ")}"))
                    tags.add("edge_skip")
                }
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("edge", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 3: ADAPTIVE CONFIDENCE THRESHOLD
        // 
        // Uses the fluid confidence layer that adapts to market conditions.
        // The threshold adjusts based on volatility, win rate, buy pressure,
        // and session performance - making the bot smarter in both modes.
        // ─────────────────────────────────────────────────────────────────────
        
        val confidenceThreshold = getAdaptiveConfidence(config.paperMode, ts)
        
        if (blockReason == null && candidate.aiConfidence < confidenceThreshold) {
            blockReason = "LOW_CONFIDENCE_${candidate.aiConfidence.toInt()}%"
            blockLevel = BlockLevel.CONFIDENCE
            checks.add(GateCheck("confidence", false, 
                "conf=${candidate.aiConfidence.toInt()}% < ${confidenceThreshold.toInt()}% (adaptive)"))
            tags.add("low_confidence")
            tags.add("adaptive_conf:${confidenceThreshold.toInt()}")
        } else if (blockReason == null) {
            checks.add(GateCheck("confidence", true, 
                "conf=${candidate.aiConfidence.toInt()}% >= ${confidenceThreshold.toInt()}% (adaptive)"))
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
        // GATE 5: SIZING VALIDATION
        // ─────────────────────────────────────────────────────────────────────
        
        var finalSize = proposedSizeSol
        
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
        
        val shouldTrade = blockReason == null && candidate.shouldTrade
        
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
            
            // Paper mode - determine if benchmark or exploration
            else -> {
                // Would this trade pass LIVE mode rules?
                val wouldPassLiveEdge = edgeVerdict != EdgeVerdict.SKIP
                val wouldPassLiveQuality = candidate.setupQuality in listOf("A+", "A", "B")
                val wouldPassLiveConfidence = candidate.aiConfidence >= liveAdaptiveConf
                
                if (wouldPassLiveEdge && wouldPassLiveQuality && wouldPassLiveConfidence) {
                    // This is benchmark quality - would pass in live mode
                    ApprovalClass.PAPER_BENCHMARK to "benchmark: passes live rules (edge=$wouldPassLiveEdge quality=${candidate.setupQuality} conf=${candidate.aiConfidence.toInt()}%>=${liveAdaptiveConf.toInt()}%)"
                } else {
                    // This is exploration - relaxed for learning
                    val relaxedReasons = mutableListOf<String>()
                    if (!wouldPassLiveEdge) relaxedReasons.add("edge=${edgeVerdict.name}")
                    if (!wouldPassLiveQuality) relaxedReasons.add("quality=${candidate.setupQuality}")
                    if (!wouldPassLiveConfidence) relaxedReasons.add("conf=${candidate.aiConfidence.toInt()}%<${liveAdaptiveConf.toInt()}%")
                    ApprovalClass.PAPER_EXPLORATION to "exploration: relaxed ${relaxedReasons.joinToString(", ")}"
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
