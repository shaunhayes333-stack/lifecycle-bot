package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.AutoCompoundEngine
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * CASH GENERATION AI - "TREASURY MODE" v2.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * A conservative, profit-focused trading mode designed for consistent daily cash flow.
 * User-configured for ACTIVE scalping with ULTRA-CONSERVATIVE loss limits.
 * 
 * USER REQUIREMENTS (Dec 2024):
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Daily Loss Limit: $50 (ULTRA-CONSERVATIVE) - pause after hitting
 * 2. Position Sizing: DYNAMIC - shrinks as daily target approaches
 * 3. Trade Frequency: ACTIVE (10+ trades/day, quick scalps)
 * 4. Profit Taking: QUICK SCALPS (5-10% profit, fast exit)
 * 5. SEPARATE Paper/Live Treasury Balances - switch display with mode
 * 
 * PHILOSOPHY:
 * - Many small wins (5-10%) through quick scalping
 * - Cut losses IMMEDIATELY (max -2% per trade, $50/day total)
 * - Only A-grade confidence (80%+) setups
 * - Feed the treasury, never drain it
 * - "2nd shadow mode" that runs CONCURRENTLY with other trading
 * 
 * GOALS:
 * - Target: $500-$1000 daily profit
 * - Max drawdown: $50/day (ULTRA-CONSERVATIVE - then pause)
 * - Win rate target: 70%+ (many small wins)
 * - Trade count: 10+ per day (active scalping)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CashGenerationAI {
    
    private const val TAG = "CashGenAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION (User-specified Dec 2024 - UPDATED V4.0: No daily limits)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.0 UPDATE: REMOVED daily profit targets - Treasury runs unlimited!
    // Old caps were: $500-$1000/day. Now: UNLIMITED - let it make as much as possible!
    private const val DAILY_MAX_LOSS_SOL = 0.33       // ~$50 ULTRA-CONSERVATIVE (user choice 1a) - KEEP THIS
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS - Now centralized in FluidLearningAI (Layer 23)
    // 
    // CashGenerationAI queries FluidLearningAI for all adaptive thresholds.
    // This ensures consistency across all components.
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Position sizing (DYNAMIC - shrinks as target approaches - user choice 2b)
    private const val BASE_POSITION_SOL = 0.10        // Small base for active scalping
    private const val MAX_POSITION_SOL = 0.25         // Never exceed this
    private const val MIN_POSITION_SOL = 0.03         // Minimum viable trade
    private const val POSITION_SCALE_FACTOR = 1.15    // Scale up slightly on A+ setups
    
    // Exit strategy (AGGRESSIVE SCALPING - V4.20)
    // Chip away at profits constantly - no hold time limit
    // Can cycle same coin repeatedly if green
    private const val TAKE_PROFIT_PCT_PAPER = 3.5     // V5.2: Paper same as live (was 0.5%)
    private const val TAKE_PROFIT_PCT_LIVE = 2.5      // Live: 2.5% minimum (covers fees + profit)
    private const val TAKE_PROFIT_MIN_PCT = 2.5       // V5.0: Quick 2.5% for DEFENSIVE mode
    private const val TAKE_PROFIT_PCT = 3.5           // Standard TP for CRUISE mode
    private const val TAKE_PROFIT_MAX_PCT = 4.0       // V5.0: Cap at 4% for AGGRESSIVE (was 8%)
    private const val STOP_LOSS_PCT = -4.0            // V5.2 FIX: Raised from -2% - give trades room to breathe
    private const val TRAILING_STOP_PCT = 1.5         // Tighter 1.5% trail after profit
    private const val MAX_HOLD_MINUTES = 30           // Extended to 30min - let positions breathe
    private const val REENTRY_COOLDOWN_MS = 5000L     // 5 second cooldown between same-token trades
    
    // Live mode fee coverage
    // Jupiter: ~0.5% per swap × 2 = 1%
    // Slippage: ~0.5% × 2 = 1%  
    // Total round-trip: ~1.5-2%
    private const val MIN_PROFIT_FOR_LIVE = 2.5       // Must clear 2.5% to cover fees + profit
    
    // Trade frequency (ACTIVE - user choice 3c: 10+ trades/day)
    private const val MIN_TRADES_PER_DAY = 10
    private const val MAX_CONCURRENT_POSITIONS = 4   // Allow more concurrent for active scalping
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COMPOUNDING & IMMEDIATE TRADING
    // 
    // Treasury Mode uses compounding: profits are reinvested for scaling buys.
    // Immediate trading: No warmup period - starts evaluating on first loop.
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val COMPOUNDING_ENABLED = true        // Reinvest profits into larger positions
    private const val COMPOUNDING_RATIO = 0.5           // Use 50% of treasury for compounding
    private const val IMMEDIATE_TRADING = true          // No warmup delay
    private const val MIN_WARMUP_TOKENS = 0             // Start trading immediately (was 5)
    
    @Volatile private var isWarmedUp = true             // Always warmed up for immediate trading
    @Volatile private var tokensSeenSinceStart = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING (Separate Paper vs Live)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // PAPER MODE stats (reset at midnight or manually)
    private val paperDailyPnlSolBps = AtomicLong(0)  // Basis points (0.01 SOL = 1)
    private val paperDailyWins = AtomicInteger(0)
    private val paperDailyLosses = AtomicInteger(0)
    private val paperDailyTradeCount = AtomicInteger(0)
    // V3.3: Paper Treasury starts with $500 (~6 SOL at $83/SOL) = 600 basis points
    private val paperTreasuryBalanceBps = AtomicLong(600)  // Starting balance: 6.0 SOL (~$500)
    
    // LIVE MODE stats (separate tracking)
    private val liveDailyPnlSolBps = AtomicLong(0)
    private val liveDailyWins = AtomicInteger(0)
    private val liveDailyLosses = AtomicInteger(0)
    private val liveDailyTradeCount = AtomicInteger(0)
    private val liveTreasuryBalanceBps = AtomicLong(0)  // Live treasury starts at 0 (from actual trading profits)
    
    // Current mode flag (set by BotService when mode changes)
    @Volatile private var isPaperMode: Boolean = true
    private var lastResetDay = 0
    
    // Active treasury positions (mint -> TreasuryPosition) - separate for paper/live
    private val paperPositions = mutableMapOf<String, TreasuryPosition>()
    private val livePositions = mutableMapOf<String, TreasuryPosition>()
    
    // V4.20: Track recent exits for quick re-entry cycling
    // Key = mint, Value = exit timestamp
    private val recentExits = mutableMapOf<String, Long>()
    
    // Convenience: get active positions for current mode
    private val activePositions: MutableMap<String, TreasuryPosition>
        get() = if (isPaperMode) paperPositions else livePositions
    
    // Convenience: get stats for current mode
    private val dailyPnlSolBps: AtomicLong
        get() = if (isPaperMode) paperDailyPnlSolBps else liveDailyPnlSolBps
    private val dailyWins: AtomicInteger
        get() = if (isPaperMode) paperDailyWins else liveDailyWins
    private val dailyLosses: AtomicInteger
        get() = if (isPaperMode) paperDailyLosses else liveDailyLosses
    private val dailyTradeCount: AtomicInteger
        get() = if (isPaperMode) paperDailyTradeCount else liveDailyTradeCount
    private val treasuryBalanceBps: AtomicLong
        get() = if (isPaperMode) paperTreasuryBalanceBps else liveTreasuryBalanceBps
    
    // V5.2: Treasury's own price tracking - independent of other layers
    private val currentPrices = ConcurrentHashMap<String, Double>()
    private val lastPriceUpdate = ConcurrentHashMap<String, Long>()
    
    data class TreasuryPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val targetPrice: Double,    // Take profit at this price
        val stopPrice: Double,      // Cut loss at this price
        var highWaterMark: Double,  // For trailing stop
        var trailingStop: Double,   // Dynamic trailing stop price
        val isPaper: Boolean,       // Track which mode this position belongs to
    )
    
    data class TreasurySignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val confidence: Int,
        val reason: String,
        val mode: TreasuryMode,
        val isPaperMode: Boolean,   // Which mode generated this signal
    )
    
    enum class TreasuryMode {
        HUNT,       // Normal - hunting for setups (< 50% of daily target)
        CRUISE,     // Good progress - standard approach (50-80% of target)
        DEFENSIVE,  // Hit target - protect gains (> 80% of target)
        PAUSED,     // Hit max loss - wait for tomorrow
        AGGRESSIVE, // Behind schedule - slightly more risk (negative P&L)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE MANAGEMENT (Paper vs Live)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Set the current trading mode (paper or live).
     * Called by BotService when mode changes.
     * Treasury display will switch to show appropriate balance.
     */
    fun setTradingMode(isPaper: Boolean) {
        if (isPaperMode != isPaper) {
            ErrorLogger.info(TAG, "💰 TREASURY MODE SWITCH: ${if (isPaper) "PAPER" else "LIVE"} | " +
                "Balance: ${getTreasuryBalance(isPaper).fmt(4)} SOL")
        }
        isPaperMode = isPaper
    }
    
    /**
     * Get treasury balance for specific mode (for UI display).
     */
    fun getTreasuryBalance(isPaper: Boolean): Double {
        return if (isPaper) {
            paperTreasuryBalanceBps.get() / 100.0
        } else {
            liveTreasuryBalanceBps.get() / 100.0
        }
    }
    
    /**
     * Get current mode's treasury balance.
     */
    fun getCurrentTreasuryBalance(): Double = treasuryBalanceBps.get() / 100.0
    
    /**
     * Add realized profit to treasury (called when trade closes profitably).
     */
    fun addToTreasury(profitSol: Double, isPaper: Boolean) {
        if (profitSol <= 0) return
        val bps = (profitSol * 100).toLong()
        if (isPaper) {
            paperTreasuryBalanceBps.addAndGet(bps)
        } else {
            liveTreasuryBalanceBps.addAndGet(bps)
        }
        ErrorLogger.info(TAG, "💰 TREASURY +${profitSol.fmt(4)} SOL | " +
            "Total ${if (isPaper) "PAPER" else "LIVE"}: ${getTreasuryBalance(isPaper).fmt(4)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID LEARNING - Confidence scales with AI maturity
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current fluid confidence threshold (delegates to FluidLearningAI).
     */
    fun getCurrentConfidenceThreshold(): Int = FluidLearningAI.getTreasuryConfidenceThreshold()
    
    /**
     * Get current fluid score threshold (delegates to FluidLearningAI).
     */
    fun getCurrentScoreThreshold(): Int = FluidLearningAI.getMinScoreThreshold()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V4.0: Treasury evaluates tokens INDEPENDENTLY from V3.
     * 
     * Treasury is a WEALTH GENERATOR that operates on its own criteria:
     * - Liquidity (can I get in/out fast?)
     * - Buy Pressure (is buying momentum strong?)
     * - Momentum (is price moving up?)
     * - Volatility (is there opportunity for quick scalps?)
     * 
     * It does NOT use V3 scores - Treasury has its own scoring system!
     * The v3Score/v3Confidence parameters are IGNORED (kept for API compat).
     */
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        liquidityUsd: Double,
        topHolderPct: Double,
        buyPressurePct: Double,
        v3Score: Int,        // IGNORED in V4.0 - kept for API compatibility
        v3Confidence: Int,   // IGNORED in V4.0 - kept for API compatibility
        momentum: Double,
        volatility: Double,
    ): TreasurySignal {
        
        // Check current mode based on daily progress
        val mode = getCurrentMode()
        
        // If paused (hit max loss $50), reject everything
        if (mode == TreasuryMode.PAUSED) {
            return TreasurySignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "PAUSED: Daily loss limit (\$50) reached - waiting for reset",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID CONFIDENCE THRESHOLDS
        // 
        // Base threshold scales with learning (30% → 80% as AI matures)
        // Mode adjustments apply ON TOP of the learned base
        // ═══════════════════════════════════════════════════════════════════
        
        // ═══════════════════════════════════════════════════════════════════
        // V4.0: INDEPENDENT TREASURY SCORING
        // 
        // Treasury calculates its OWN score based on scalping criteria.
        // This is completely separate from V3 trading logic!
        // ═══════════════════════════════════════════════════════════════════
        
        var treasuryScore = 0
        var treasuryConfidence = 0
        val scoreReasons = mutableListOf<String>()
        
        // 1. LIQUIDITY SCORE (0-25 pts)
        val liqScore = when {
            liquidityUsd >= 50000 -> 25
            liquidityUsd >= 20000 -> 20
            liquidityUsd >= 10000 -> 15
            liquidityUsd >= 5000 -> 10
            liquidityUsd >= 2000 -> 5
            else -> 0
        }
        treasuryScore += liqScore
        if (liqScore > 0) scoreReasons.add("liq+$liqScore")
        
        // 2. BUY PRESSURE SCORE (0-30 pts)
        val buyScore = when {
            buyPressurePct >= 70 -> 30
            buyPressurePct >= 60 -> 25
            buyPressurePct >= 55 -> 20
            buyPressurePct >= 50 -> 15
            buyPressurePct >= 45 -> 10
            buyPressurePct >= 40 -> 5
            else -> 0
        }
        treasuryScore += buyScore
        if (buyScore > 0) scoreReasons.add("buy+$buyScore")
        
        // 3. MOMENTUM SCORE (-10 to +20 pts)
        val momentumScore = when {
            momentum >= 10 -> 20
            momentum >= 5 -> 15
            momentum >= 2 -> 10
            momentum >= 0 -> 5
            momentum >= -3 -> 0
            momentum >= -5 -> -5
            else -> -10
        }
        treasuryScore += momentumScore
        if (momentumScore != 0) scoreReasons.add("mom${if(momentumScore>0)"+" else ""}$momentumScore")
        
        // 4. VOLATILITY SCORE (0-15 pts)
        val volScore = when {
            volatility in 20.0..60.0 -> 15
            volatility in 10.0..70.0 -> 10
            volatility in 5.0..80.0 -> 5
            else -> 0
        }
        treasuryScore += volScore
        if (volScore > 0) scoreReasons.add("vol+$volScore")
        
        // 5. TOP HOLDER PENALTY (-20 to 0 pts)
        val holderPenalty = when {
            topHolderPct <= 10 -> 0
            topHolderPct <= 20 -> -2
            topHolderPct <= 30 -> -5
            topHolderPct <= 40 -> -10
            else -> -20
        }
        treasuryScore += holderPenalty
        if (holderPenalty < 0) scoreReasons.add("holder$holderPenalty")
        
        // 6. MODE BONUS
        val modeBonus = when (mode) {
            TreasuryMode.AGGRESSIVE -> 10
            TreasuryMode.HUNT -> 5
            TreasuryMode.CRUISE -> 0
            TreasuryMode.DEFENSIVE -> -5
            else -> 0
        }
        treasuryScore += modeBonus
        if (modeBonus != 0) scoreReasons.add("mode${if(modeBonus>0)"+" else ""}$modeBonus")
        
        // Calculate confidence - V5.0: Tighter thresholds
        treasuryConfidence = (
            (if (liquidityUsd > 10000) 25 else if (liquidityUsd > 5000) 15 else 5) +
            (if (buyPressurePct > 55) 25 else if (buyPressurePct > 45) 15 else 5) +
            (if (momentum > 2) 25 else if (momentum > -2) 15 else 5) +
            (if (topHolderPct < 20) 25 else if (topHolderPct < 30) 15 else 5)
        ).coerceIn(0, 100)
        
        // ═══════════════════════════════════════════════════════════════════
        // THRESHOLDS (Using FluidLearningAI for consistency)
        // ═══════════════════════════════════════════════════════════════════
        val learningProgress = FluidLearningAI.getLearningProgress()
        
        // Treasury thresholds - LOWER than V3 because tight stops protect us
        val minTreasuryScore = FluidLearningAI.getTreasuryScoreThreshold()
        val minTreasuryConf = FluidLearningAI.getTreasuryConfidenceThreshold()
        val minLiquidity = FluidLearningAI.getTreasuryMinLiquidity()
        val maxTopHolder = FluidLearningAI.getTreasuryMaxTopHolder()
        val minBuyPressure = FluidLearningAI.getTreasuryMinBuyPressure()
        
        ErrorLogger.debug(TAG, "💰 TREASURY SCORE: $symbol | " +
            "score=$treasuryScore (need≥$minTreasuryScore) | " +
            "conf=$treasuryConfidence% (need≥$minTreasuryConf%) | " +
            "${scoreReasons.joinToString(",")}")
        
        // ─── FILTER CHECKS ───
        val rejectionReasons = mutableListOf<String>()
        
        if (treasuryScore < minTreasuryScore) {
            rejectionReasons.add("score=$treasuryScore<$minTreasuryScore")
        }
        if (treasuryConfidence < minTreasuryConf) {
            rejectionReasons.add("conf=$treasuryConfidence<$minTreasuryConf")
        }
        if (liquidityUsd < minLiquidity) {
            rejectionReasons.add("liq=$${liquidityUsd.toInt()}<$${minLiquidity.toInt()}")
        }
        if (topHolderPct > maxTopHolder) {
            rejectionReasons.add("holder=${topHolderPct.toInt()}%>${maxTopHolder.toInt()}%")
        }
        if (buyPressurePct < minBuyPressure) {
            rejectionReasons.add("buy=${buyPressurePct.toInt()}%<${minBuyPressure.toInt()}%")
        }
        if (momentum < -5) {
            rejectionReasons.add("momentum=${"%.1f".format(momentum)}<-5")
        }
        
        // Already have position in this token?
        if (activePositions.containsKey(mint)) {
            rejectionReasons.add("already_in_position")
        }
        
        // V4.20: Check re-entry cooldown for cycling same token
        val lastExitTime = recentExits[mint] ?: 0L
        val timeSinceExit = System.currentTimeMillis() - lastExitTime
        if (lastExitTime > 0 && timeSinceExit < REENTRY_COOLDOWN_MS) {
            val remaining = (REENTRY_COOLDOWN_MS - timeSinceExit) / 1000
            rejectionReasons.add("reentry_cooldown (${remaining}s)")
        }
        
        // Too many active treasury positions? (Increased for active scalping)
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            rejectionReasons.add("max_positions_reached (${MAX_CONCURRENT_POSITIONS})")
        }
        
        if (rejectionReasons.isNotEmpty()) {
            // Log rejection for debugging (only first few to avoid spam)
            if (rejectionReasons.size <= 2) {
                ErrorLogger.debug(TAG, "💰 TREASURY SKIP: $symbol | ${rejectionReasons.joinToString(", ")}")
            }
            return TreasurySignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = treasuryConfidence,
                reason = "REJECTED: ${rejectionReasons.joinToString(", ")}",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // ─── DYNAMIC POSITION SIZING ───
        // V4.0 UPDATE: No daily target cap - Treasury runs unlimited!
        // Position size now scales with COMPOUNDING only, not progress-based shrinking
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        
        // V4.0: No more progress-based shrinking - let it compound freely!
        var positionSol = BASE_POSITION_SOL
        
        // ─── COMPOUNDING: Add treasury profits to position size ───
        if (COMPOUNDING_ENABLED) {
            val treasuryBalance = getTreasuryBalance(isPaperMode)
            if (treasuryBalance > 0) {
                // Use COMPOUNDING_RATIO (50%) of treasury for scaling buys
                val compoundingBonus = treasuryBalance * COMPOUNDING_RATIO
                // Scale bonus by confidence (higher confidence = use more compound)
                val confidenceScale = treasuryConfidence / 100.0
                positionSol += compoundingBonus * confidenceScale
                
                ErrorLogger.debug(TAG, "💰 COMPOUNDING: +${compoundingBonus.fmt(3)}SOL from treasury " +
                    "(balance=${treasuryBalance.fmt(3)}SOL, conf=${treasuryConfidence}%)")
            }
            
            // V4.0: Also compound from daily profits (unlimited growth!)
            if (dailyPnl > 0) {
                val dailyCompound = dailyPnl * COMPOUNDING_RATIO * (treasuryConfidence / 100.0)
                positionSol += dailyCompound
                ErrorLogger.debug(TAG, "💰 DAILY COMPOUND: +${dailyCompound.fmt(3)}SOL from today's profits")
            }
        }
        
        // Scale up slightly for A+ setups (high treasury score)
        if (treasuryConfidence >= 75 && treasuryScore >= 50) {
            positionSol *= POSITION_SCALE_FACTOR
        }
        
        // Mode-based adjustments
        positionSol *= when (mode) {
            TreasuryMode.DEFENSIVE -> 0.4  // Very small when protecting gains
            TreasuryMode.CRUISE -> 0.7     // Moderate when cruising
            TreasuryMode.AGGRESSIVE -> 1.15 // Slightly larger when behind
            TreasuryMode.HUNT -> 1.0       // Normal
            TreasuryMode.PAUSED -> 0.0     // Should never reach here
        }
        
        // Enforce min/max bounds (compounding can push above normal max)
        val maxWithCompounding = MAX_POSITION_SOL * (1 + COMPOUNDING_RATIO)
        positionSol = positionSol.coerceIn(MIN_POSITION_SOL, maxWithCompounding)
        
        // V4.20: Apply AutoCompoundEngine global size multiplier
        // This multiplier grows as the compound pool accumulates from winning trades
        val globalMultiplier = AutoCompoundEngine.getSizeMultiplier()
        if (globalMultiplier > 1.0) {
            positionSol *= globalMultiplier
            positionSol = positionSol.coerceAtMost(maxWithCompounding * 1.5) // Allow slight overshoot
            ErrorLogger.debug(TAG, "💰 GLOBAL COMPOUND BOOST: ${globalMultiplier.fmt(2)}x → pos=${positionSol.fmt(3)}SOL")
        }
        
        // ─── DETERMINE EXIT LEVELS (Quick scalps 3-7% for Treasury Mode) ───
        val takeProfitPct = when (mode) {
            TreasuryMode.DEFENSIVE -> TAKE_PROFIT_MIN_PCT  // Take quick 3% when protecting
            TreasuryMode.CRUISE -> TAKE_PROFIT_PCT         // Standard 3.5%
            TreasuryMode.AGGRESSIVE -> TAKE_PROFIT_MAX_PCT // Let run to 7% when behind
            else -> TAKE_PROFIT_PCT
        }
        val stopLossPct = STOP_LOSS_PCT  // V5.2: -4% stop loss (raised from -2%)
        
        ErrorLogger.info(TAG, "💰 TREASURY ENTRY: $symbol | " +
            "score=$treasuryScore conf=${treasuryConfidence}% | " +
            "size=${positionSol.fmt(3)} SOL (dailyPnl=${dailyPnl.fmt(2)}◎) | " +
            "TP=${takeProfitPct}% SL=${stopLossPct}% | mode=$mode | ${if (isPaperMode) "PAPER" else "LIVE"}")
        
        return TreasurySignal(
            shouldEnter = true,
            positionSizeSol = positionSol,
            takeProfitPct = takeProfitPct,
            stopLossPct = stopLossPct,
            confidence = treasuryConfidence,
            reason = "TREASURY_ENTRY: score=$treasuryScore (${scoreReasons.joinToString(",")})",
            mode = mode,
            isPaperMode = isPaperMode
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Open a treasury position (mode-aware)
     */
    fun openPosition(
        mint: String,
        symbol: String,
        entryPrice: Double,
        positionSol: Double,
        takeProfitPct: Double,
        stopLossPct: Double,
    ) {
        val targetPrice = entryPrice * (1 + takeProfitPct / 100)
        val stopPrice = entryPrice * (1 + stopLossPct / 100)
        
        val position = TreasuryPosition(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = positionSol,
            entryTime = System.currentTimeMillis(),
            targetPrice = targetPrice,
            stopPrice = stopPrice,
            highWaterMark = entryPrice,
            trailingStop = stopPrice,
            isPaper = isPaperMode,
        )
        
        synchronized(activePositions) {
            activePositions[mint] = position
            ErrorLogger.info(TAG, "💰 TREASURY MAP: Added ${symbol} | activePositions.size=${activePositions.size} | mints=${activePositions.keys.map { it.take(8) }}")
        }
        
        dailyTradeCount.incrementAndGet()
        
        ErrorLogger.info(TAG, "💰 TREASURY OPENED: $symbol | " +
            "entry=$entryPrice | TP=$targetPrice | SL=$stopPrice | " +
            "size=$positionSol SOL | ${if (isPaperMode) "PAPER" else "LIVE"}")
    }
    
    /**
     * V5.2: Get active position for debugging
     */
    fun getActivePosition(mint: String): TreasuryPosition? {
        return synchronized(activePositions) { activePositions[mint] }
    }
    
    /**
     * V5.2: Update price for a treasury position - independent price tracking
     */
    fun updatePrice(mint: String, price: Double) {
        if (price > 0) {
            currentPrices[mint] = price
            lastPriceUpdate[mint] = System.currentTimeMillis()
        }
    }
    
    /**
     * V5.2: Get tracked price for a position
     */
    fun getTrackedPrice(mint: String): Double? {
        return currentPrices[mint]
    }
    
    /**
     * V5.2: Check ALL treasury positions for exits using tracked prices
     * Returns list of (mint, exitSignal) pairs that need to be acted on
     */
    fun checkAllPositionsForExit(): List<Pair<String, ExitSignal>> {
        val exits = mutableListOf<Pair<String, ExitSignal>>()
        
        synchronized(activePositions) {
            for ((mint, pos) in activePositions) {
                val currentPrice = currentPrices[mint] ?: continue
                val signal = checkExitInternal(pos, currentPrice)
                if (signal != ExitSignal.HOLD) {
                    exits.add(mint to signal)
                    ErrorLogger.info(TAG, "💰 TREASURY EXIT SIGNAL: ${pos.symbol} | $signal | " +
                        "price=$currentPrice entry=${pos.entryPrice} pnl=${((currentPrice - pos.entryPrice) / pos.entryPrice * 100).toInt()}%")
                }
            }
        }
        
        return exits
    }
    
    /**
     * Internal exit check logic
     */
    private fun checkExitInternal(pos: TreasuryPosition, currentPrice: Double): ExitSignal {
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            pos.trailingStop = currentPrice * (1 - TRAILING_STOP_PCT / 100)
        }
        
        // 1. V5.2 FIX: HIT TARGET PRICE - Use the POSITION'S target, not hardcoded value!
        if (currentPrice >= pos.targetPrice) {
            val holdSeconds = (System.currentTimeMillis() - pos.entryTime) / 1000
            ErrorLogger.info(TAG, "💰 TREASURY TP HIT: ${pos.symbol} | +${pnlPct.toInt()}% in ${holdSeconds}s | SELLING!")
            return ExitSignal.TAKE_PROFIT
        }
        
        // 2. BACKUP: HIT MAX TAKE PROFIT (4%)
        if (pnlPct >= TAKE_PROFIT_MAX_PCT) {
            return ExitSignal.TAKE_PROFIT
        }
        
        // 3. HIT STOP LOSS
        if (currentPrice <= pos.stopPrice) {
            return ExitSignal.STOP_LOSS
        }
        
        // 4. HIT TRAILING STOP (if in profit > 2%)
        if (pnlPct > 2.0 && currentPrice <= pos.trailingStop) {
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. MAX HOLD TIME (30 mins)
        if (holdMinutes >= MAX_HOLD_MINUTES) {
            return ExitSignal.TIME_EXIT
        }
        
        // 6. Cut loss if underwater after 10 min
        if (holdMinutes >= 10 && pnlPct < -1.5) {
            return ExitSignal.STOP_LOSS
        }
        
        return ExitSignal.HOLD
    }
    
    /**
     * Check if position should exit (called on each price update).
     * V4.20: Aggressive scalping - exit as soon as profitable (covers fees in live)
     */
    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        // V5.2: Also update our tracked price
        updatePrice(mint, currentPrice)
        
        val pos = synchronized(activePositions) { activePositions[mint] }
        
        // V5.2 DEBUG: Log if position not found - this would explain why sells aren't happening!
        if (pos == null) {
            ErrorLogger.warn(TAG, "💰 TREASURY CHECK: Position NOT FOUND for ${mint.take(8)}... | activePositions.size=${activePositions.size}")
            return ExitSignal.HOLD
        }
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // V5.2 DEBUG: Always log the check
        ErrorLogger.debug(TAG, "💰 TREASURY CHECK: ${pos.symbol} | price=$currentPrice | entry=${pos.entryPrice} | " +
            "target=${pos.targetPrice} | pnl=${pnlPct.fmt(1)}%")
        
        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            pos.trailingStop = currentPrice * (1 - TRAILING_STOP_PCT / 100)
        }
        
        // ─── EXIT CONDITIONS (Priority order) ───
        
        // 1. V5.2: HIT OR PASSED TARGET PRICE - SELL and promote to appropriate layer!
        // Treasury takes quick profits, then hands off to Moonshot/ShitCoin for continued gains
        if (currentPrice >= pos.targetPrice) {
            ErrorLogger.info(TAG, "💰 TREASURY TP HIT: ${pos.symbol} | +${pnlPct.fmt(1)}% | " +
                "price=$currentPrice >= target=${pos.targetPrice} | SELLING & PROMOTING!")
            return ExitSignal.TAKE_PROFIT  // BotService will handle promotion to next layer
        }
        
        // 2. HIT MAX TAKE PROFIT (hard cap)
        if (pnlPct >= TAKE_PROFIT_MAX_PCT) {
            ErrorLogger.info(TAG, "💰 TREASURY MAX TP: ${pos.symbol} | +${pnlPct.fmt(1)}% (hit ${TAKE_PROFIT_MAX_PCT}% cap)")
            return ExitSignal.TAKE_PROFIT
        }
        
        // 3. HIT STOP LOSS
        if (currentPrice <= pos.stopPrice) {
            ErrorLogger.info(TAG, "💰 TREASURY SL HIT: ${pos.symbol} | ${pnlPct.fmt(1)}% | price=$currentPrice <= stop=${pos.stopPrice}")
            return ExitSignal.STOP_LOSS
        }
        
        // 4. HIT TRAILING STOP (if in decent profit > 2%)
        if (pnlPct > 2.0 && currentPrice <= pos.trailingStop) {
            ErrorLogger.info(TAG, "💰 TREASURY TRAIL HIT: ${pos.symbol} | +${pnlPct.fmt(1)}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. MAX HOLD TIME (30 mins) - only exit if not profitable
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 2.0) {
            ErrorLogger.info(TAG, "💰 TREASURY TIME EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.TIME_EXIT
        }
        
        // 6. Cut loss if underwater after 10min
        if (holdMinutes >= 10 && pnlPct < -1.5) {
            ErrorLogger.info(TAG, "💰 TREASURY CUT LOSS: ${pos.symbol} | ${pnlPct.fmt(1)}% - not recovering")
            return ExitSignal.STOP_LOSS
        }
        
        return ExitSignal.HOLD
    }
    
    enum class ExitSignal {
        HOLD,
        TAKE_PROFIT,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
    }
    
    /**
     * Close a treasury position and record P&L.
     * Profits are added to the mode-specific treasury balance.
     * V4.0: Treasury trades now contribute to FluidLearningAI maturity!
     */
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100
        
        // V4.20: Record exit time for re-entry cycling
        // If profitable exit, allow quick re-entry. If loss, longer cooldown.
        val cooldownMs = if (pnlPct > 0) REENTRY_COOLDOWN_MS else REENTRY_COOLDOWN_MS * 3
        recentExits[mint] = System.currentTimeMillis()
        
        // Clean up old exit records (older than 1 minute)
        val oneMinuteAgo = System.currentTimeMillis() - 60_000
        recentExits.entries.removeIf { it.value < oneMinuteAgo }
        
        // Record to daily P&L (using mode-specific counters)
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)
        
        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            // Add profit to treasury balance for this mode
            addToTreasury(pnlSol, pos.isPaper)
        } else {
            dailyLosses.incrementAndGet()
        }
        
        // V4.0: Treasury trades contribute to FluidLearningAI maturity
        // Paper Treasury = 10% weight, Live Treasury = 50% weight (same as regular trades)
        try {
            val isWin = pnlPct > 0
            if (pos.isPaper) {
                FluidLearningAI.recordPaperTrade(isWin)
            } else {
                FluidLearningAI.recordLiveTrade(isWin)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val winRate = if (dailyTradeCount.get() > 0) {
            dailyWins.get().toDouble() / dailyTradeCount.get() * 100
        } else 0.0
        
        val cycleLabel = if (pnlPct > 0) " [CYCLE OK in ${REENTRY_COOLDOWN_MS/1000}s]" else ""
        val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
        ErrorLogger.info(TAG, "💰 TREASURY CLOSED [$modeLabel]: ${pos.symbol} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "reason=$exitReason$cycleLabel | " +
            "Daily: ${dailyPnl.fmt(4)} SOL | " +
            "Win rate: ${winRate.fmt(0)}% | " +
            "Treasury: ${getCurrentTreasuryBalance().fmt(4)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE DETERMINATION (Based on daily progress)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current treasury mode based on daily P&L.
     * 
     * V4.0 UPDATE: No more daily target caps - Treasury runs UNLIMITED!
     * PAUSED: Hit $50 loss limit → stop trading (ONLY hard limit)
     * AGGRESSIVE: Behind (negative P&L) → slightly more risk to recover
     * HUNT: Normal mode → active scalping
     * CRUISE: Positive P&L → steady approach (used to be progress-based)
     * DEFENSIVE: Removed concept (no target = no need to defend)
     */
    fun getCurrentMode(): TreasuryMode {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        
        return when {
            // Hit max loss ($50) → PAUSE until reset
            dailyPnl <= -DAILY_MAX_LOSS_SOL -> TreasuryMode.PAUSED
            
            // V4.0: Simplified modes - no target-based switching
            // Behind (negative) → AGGRESSIVE (slightly more risk)
            dailyPnl < 0 -> TreasuryMode.AGGRESSIVE
            
            // Making profit → CRUISE (steady compound growth)
            dailyPnl > 0.5 -> TreasuryMode.CRUISE  // > 0.5 SOL profit
            
            // Normal → HUNT
            else -> TreasuryMode.HUNT
        }
    }
    
    /**
     * Get current stats for UI display (mode-aware)
     * V4.0: No daily target limit - Treasury runs unlimited!
     */
    fun getStats(): TreasuryStats {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val learningProgress = FluidLearningAI.getLearningProgress()
        
        return TreasuryStats(
            dailyPnlSol = dailyPnl,
            dailyMaxLossSol = DAILY_MAX_LOSS_SOL,
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyTradeCount = dailyTradeCount.get(),
            winRate = if (dailyTradeCount.get() > 0) {
                dailyWins.get().toDouble() / dailyTradeCount.get() * 100
            } else 0.0,
            activePositions = activePositions.size,
            mode = getCurrentMode(),
            treasuryBalanceSol = getCurrentTreasuryBalance(),
            isPaperMode = isPaperMode,
            learningProgressPct = (learningProgress * 100).toInt(),
            currentConfThreshold = getCurrentConfidenceThreshold(),
            currentScoreThreshold = getCurrentScoreThreshold(),
        )
    }
    
    data class TreasuryStats(
        val dailyPnlSol: Double,
        val dailyMaxLossSol: Double,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyTradeCount: Int,
        val winRate: Double,
        val activePositions: Int,
        val mode: TreasuryMode,
        val treasuryBalanceSol: Double,
        val isPaperMode: Boolean,
        val learningProgressPct: Int = 0,        // How mature the AI is (0-100%)
        val currentConfThreshold: Int = 30,      // Current fluid confidence threshold
        val currentScoreThreshold: Int = 15,     // Current fluid score threshold
    ) {
        fun summary(): String = buildString {
            val modeEmoji = when (mode) {
                TreasuryMode.HUNT -> "🎯"
                TreasuryMode.CRUISE -> "🚢"
                TreasuryMode.DEFENSIVE -> "🛡️"
                TreasuryMode.PAUSED -> "⏸️"
                TreasuryMode.AGGRESSIVE -> "⚡"
            }
            append("$modeEmoji ${mode.name} | ")
            append("${if (dailyPnlSol >= 0) "+" else ""}${dailyPnlSol.fmt(3)}◎ ")
            append("(UNLIMITED) | ")
            append("$dailyWins W / $dailyLosses L | ")
            append("Treasury: ${treasuryBalanceSol.fmt(3)}◎ ")
            append("[${if (isPaperMode) "PAPER" else "LIVE"}] | ")
            append("AI: ${learningProgressPct}% → conf≥$currentConfThreshold")
        }
    }
    
    /**
     * Reset daily stats (call at midnight or manually)
     */
    fun resetDaily() {
        // Reset current mode's daily stats
        dailyPnlSolBps.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyTradeCount.set(0)
        ErrorLogger.info(TAG, "💰 TREASURY: Daily stats reset for ${if (isPaperMode) "PAPER" else "LIVE"} mode")
    }
    
    /**
     * Reset ALL stats (both paper and live)
     */
    fun resetAll() {
        paperDailyPnlSolBps.set(0)
        paperDailyWins.set(0)
        paperDailyLosses.set(0)
        paperDailyTradeCount.set(0)
        liveDailyPnlSolBps.set(0)
        liveDailyWins.set(0)
        liveDailyLosses.set(0)
        liveDailyTradeCount.set(0)
        // Note: Treasury balances are NOT reset (accumulated profits persist)
        ErrorLogger.info(TAG, "💰 TREASURY: All daily stats reset (treasury balances preserved)")
    }
    
    /**
     * Check if Treasury Mode should be active
     */
    fun isEnabled(): Boolean {
        // Could be controlled by config
        return true
    }
    
    /**
     * Get active treasury positions for monitoring
     */
    fun getActivePositions(): List<TreasuryPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }
    
    /**
     * Get all positions for specific mode (for UI)
     */
    fun getPositionsForMode(isPaper: Boolean): List<TreasuryPosition> {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) {
            positions.values.toList()
        }
    }
    
    /**
     * Get daily P&L in SOL for UI display
     */
    fun getDailyPnlSol(): Double {
        return dailyPnlSolBps.get() / 100.0
    }
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
