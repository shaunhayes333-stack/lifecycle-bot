package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.quant.EVCalculator

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
    // AUTO-ADJUSTING LEARNING PHASE
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
    
    // Base confidence thresholds (these are ADAPTED by AdaptiveConfidence)
    var paperConfidenceBase = 0.0          // Paper mode base: NO confidence minimum (learn from all)
    var liveConfidenceBase = 0.0           // ZEROED - let trades flow while brain learns (auto-adjusts)
    
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
        // PAPER MODE: Always return 0% threshold - we want ALL trades for learning
        // The AI learns from losses just as much as wins
        if (isPaperMode) {
            return 0.0
        }
        
        val baseConfidence = liveConfidenceBase  // Live mode only from here
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
        // CALCULATE FINAL ADAPTIVE CONFIDENCE
        // 
        // BOOTSTRAP MODE: When totalSessionTrades < 30, the AI systems 
        // are still learning and their signals are unreliable. We use
        // a MUCH lower minimum threshold to allow trades for learning.
        // 
        // The goal is to get 30+ trades so the AI can calibrate properly.
        // ─────────────────────────────────────────────────────────────────
        val isBootstrap = currentConditions.totalSessionTrades < 30
        
        // During bootstrap, ALSO lower the base confidence to encourage trading
        val effectiveBase = if (isBootstrap && !isPaperMode) {
            baseConfidence * 0.5  // Use 50% of normal base during bootstrap (15% -> 7.5%)
        } else {
            baseConfidence
        }
        
        // During bootstrap, cap total positive adjustment (raising threshold)
        // to prevent blocking trades due to unreliable early data
        val cappedAdjustment = if (isBootstrap && adjustment > 0) {
            adjustment.coerceAtMost(5.0)  // Max +5% during bootstrap (was +10%)
        } else {
            adjustment
        }
        
        val adaptive = (effectiveBase + cappedAdjustment).coerceIn(
            if (isPaperMode) 0.0 else if (isBootstrap) 5.0 else 15.0,  // Min: 0% paper, 5% bootstrap (was 10%), 15% live
            if (isPaperMode) 60.0 else 75.0   // Max: 60% paper, 75% live
        )
        
        return adaptive
    }
    
    /**
     * Get explanation of current adaptive confidence calculation.
     * Useful for logging/debugging.
     */
    fun getAdaptiveConfidenceExplanation(isPaperMode: Boolean): String {
        val base = if (isPaperMode) paperConfidenceBase else liveConfidenceBase
        val isBootstrap = currentConditions.totalSessionTrades < 30
        val effectiveBase = if (isBootstrap && !isPaperMode) base * 0.5 else base
        val adaptive = getAdaptiveConfidence(isPaperMode)
        val diff = adaptive - effectiveBase
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
        
        // Bootstrap mode indicator
        val bootstrapLabel = if (isBootstrap) " [BOOTSTRAP min=5%]" else ""
        
        return buildString {
            append("AdaptiveConf: base=${effectiveBase.toInt()}% ${sign}${diff.toInt()}% = ${adaptive.toInt()}%$bootstrapLabel ")
            append("[vol=${currentConditions.avgVolatility.toInt()}% ")
            append("wr=${currentConditions.recentWinRate.toInt()}% ")
            append("buy=${currentConditions.buyPressureTrend.toInt()}% ")
            append("tier=$tierLabel ")
            append("regime=$regimeLabel ")
            append("trades=${currentConditions.totalSessionTrades}]")
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
    private const val DISTRIBUTION_COOLDOWN_MS_PAPER = 1 * 60 * 1000L  // 1 minute for paper - aggressive learning
    private const val DISTRIBUTION_COOLDOWN_MS_LIVE = 3 * 60 * 1000L   // 3 minutes for live - more cautious
    
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
        
        // ═══════════════════════════════════════════════════════════════════════
        // AUTO-ADJUSTING THRESHOLDS
        // 
        // Get current trade count and win rate from brain for adaptive thresholds
        // FDG automatically tightens as the bot learns from more trades
        // ═══════════════════════════════════════════════════════════════════════
        val tradeCount = brain?.getTradeCount() ?: 0
        val winRate = brain?.getRecentWinRate() ?: 50.0
        val adjusted = getAdjustedThresholds(tradeCount, winRate)
        
        // Log learning phase on first trade evaluation of session
        if (tradeCount == 0 || tradeCount == 11 || tradeCount == 51) {
            ErrorLogger.info("FDG", "📊 Learning phase: ${adjusted.learningPhase} | " +
                "trades=$tradeCount | progress=${(adjusted.progress * 100).toInt()}% | " +
                "conf=${adjusted.confidenceBase.toInt()}% | evGating=${adjusted.evGatingEnabled}")
        }
        
        // Use auto-adjusted thresholds for live mode, very lenient for paper
        val rugcheckThreshold = if (config.paperMode) {
            5  // Paper: very lenient for learning
        } else {
            // Use auto-adjusted OR brain-learned, whichever is available
            (brain?.learnedRugcheckThreshold ?: adjusted.rugcheckMin).coerceIn(5, 25)
        }
        val buyPressureThreshold = if (config.paperMode) 10.0 else adjusted.buyPressureMin
        val topHolderThreshold = if (config.paperMode) 90.0 else adjusted.topHolderMax
        
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
        // GATE 0.5: PROPOSAL DEDUPE (prevent spam)
        // Same token cannot be proposed/approved multiple times in quick succession
        // ─────────────────────────────────────────────────────────────────────
        if (blockReason == null) {
            val (canPropose, dedupeReason) = TradeLifecycle.canPropose(ts.mint)
            if (!canPropose) {
                blockReason = "HARD_BLOCK_$dedupeReason"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("proposal_dedupe", false, dedupeReason))
            } else {
                checks.add(GateCheck("proposal_dedupe", true, null))
            }
        }
        
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
        // LIVE MODE: Handle -1 (API timeout) with fallback safety checks
        //            - If other safety signals are strong, allow the trade
        //            - This prevents API timeouts from blocking ALL live trades
        val rugcheckScore = ts.safety.rugcheckScore
        val rugcheckBlock = when {
            // Paper mode: always allow API errors for learning
            rugcheckScore == -1 && config.paperMode -> false
            
            // Live mode: API timeout (-1) - use fallback safety checks
            rugcheckScore == -1 && !config.paperMode -> {
                // Don't hard-block if we have other strong safety signals
                // Allow trade if: good buy pressure (55%+) AND decent liquidity ($5K+)
                val hasStrongBuyers = ts.meta.pressScore >= 55.0
                val hasGoodLiquidity = ts.lastLiquidityUsd >= 5000.0
                val hasGoodVolume = ts.history.lastOrNull()?.volumeH1 ?: 0.0 >= 1000.0
                
                // Only block if safety signals are weak
                val shouldBlock = !(hasStrongBuyers && hasGoodLiquidity)
                
                if (!shouldBlock) {
                    // Log that we're allowing despite API timeout
                    ErrorLogger.info("FDG", "Rugcheck API timeout for ${ts.symbol}, allowing with safety fallback: " +
                        "buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()}")
                }
                shouldBlock
            }
            
            // Low scores: block
            rugcheckScore <= rugcheckThreshold -> true
            
            // Normal scores: allow
            else -> false
        }
        
        if (blockReason == null && rugcheckBlock) {
            // Provide more informative block reason for API timeouts
            val blockReasonDetail = if (rugcheckScore == -1) {
                "HARD_BLOCK_RUGCHECK_API_TIMEOUT"
            } else {
                "HARD_BLOCK_RUGCHECK_$rugcheckScore"
            }
            blockReason = blockReasonDetail
            blockLevel = BlockLevel.HARD
            
            val checkReason = if (rugcheckScore == -1) {
                "score=-1 (API timeout) + weak safety: buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()}"
            } else {
                "score=$rugcheckScore <= $rugcheckThreshold"
            }
            checks.add(GateCheck("rugcheck", false, checkReason))
            tags.add("low_rugcheck")
        } else if (blockReason == null) {
            // Log detailed pass reason
            val passReason = when {
                rugcheckScore == -1 && config.paperMode -> 
                    "score=-1 (paper: allowed for learning)"
                rugcheckScore == -1 && !config.paperMode -> 
                    "score=-1 (API timeout) BYPASSED: strong safety (buy%=${ts.meta.pressScore.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()})"
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
                // PAPER MODE: ALWAYS bypass edge veto - NO BLOCKS for maximum learning
                // The AI needs to see outcomes from ALL trades, including edge-vetoed ones
                checks.add(GateCheck("edge", true, 
                    "PAPER: edge veto BYPASSED (edge=${candidate.edgeQuality}) - learning from all trades"))
                tags.add("edge_veto_bypassed_paper")
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
                
                // PAPER MODE: Skip narrative penalty entirely - we want maximum learning trades
                // The goal is to get 30+ trades so the AI can learn from real outcomes
                // LIVE MODE: Reduced impact for reasonable protection without over-blocking
                narrativeAdjustment = if (config.paperMode) {
                    0  // DISABLED in paper mode - let trades happen for learning
                } else {
                    (narrativeResult.confidenceAdjustment / 4).coerceIn(-6, 4)  // Max -6 in live (reduced from -10)
                }
                
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
        
        if (blockReason == null && adjustedConfidence < confidenceThreshold) {
            blockReason = "LOW_CONFIDENCE_${adjustedConfidence.toInt()}%$bootstrapTag$narrativeTag$orthoTag"
            blockLevel = BlockLevel.CONFIDENCE
            checks.add(GateCheck("confidence", false, 
                "conf=${candidate.aiConfidence.toInt()}%+nar=$narrativeAdjustment+ortho=$orthogonalBonus=${adjustedConfidence.toInt()}% < ${confidenceThreshold.toInt()}%$bootstrapTag (adaptive)"))
            tags.add("low_confidence")
            tags.add("adaptive_conf:${confidenceThreshold.toInt()}")
            if (isBootstrap) tags.add("bootstrap_phase")
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
