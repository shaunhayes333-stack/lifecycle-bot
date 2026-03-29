package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
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
    // CONFIGURATION (User-specified Dec 2024)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Daily targets (in SOL, assuming ~$150/SOL → $500 = 3.33 SOL, $1000 = 6.67 SOL)
    private const val DAILY_TARGET_MIN_SOL = 3.33     // ~$500 @ $150/SOL
    private const val DAILY_TARGET_MAX_SOL = 6.67     // ~$1000 @ $150/SOL
    private const val DAILY_MAX_LOSS_SOL = 0.33       // ~$50 ULTRA-CONSERVATIVE (user choice 1a)
    
    // Entry criteria (STRICT - A-grade only)
    private const val MIN_CONFIDENCE_FOR_ENTRY = 80   // Only A-grade (80%+)
    private const val MIN_SCORE_FOR_ENTRY = 30        // Decent score required
    private const val MIN_LIQUIDITY_USD = 15000.0     // Higher liq = safer quick exits
    private const val MAX_TOP_HOLDER_PCT = 12.0       // Strict rug avoidance
    private const val MIN_BUY_PRESSURE_PCT = 58.0     // Strong buying momentum required
    
    // Position sizing (DYNAMIC - shrinks as target approaches - user choice 2b)
    private const val BASE_POSITION_SOL = 0.10        // Small base for active scalping
    private const val MAX_POSITION_SOL = 0.25         // Never exceed this
    private const val MIN_POSITION_SOL = 0.03         // Minimum viable trade
    private const val POSITION_SCALE_FACTOR = 1.15    // Scale up slightly on A+ setups
    
    // Exit strategy (QUICK SCALPS 5-10% - user choice 4a)
    private const val TAKE_PROFIT_PCT = 7.0           // Quick 7% scalp target (middle of 5-10%)
    private const val TAKE_PROFIT_MIN_PCT = 5.0       // Minimum acceptable profit
    private const val TAKE_PROFIT_MAX_PCT = 10.0      // Exit by 10% no matter what
    private const val STOP_LOSS_PCT = -2.0            // Cut at -2%, no exceptions
    private const val TRAILING_STOP_PCT = 2.0         // Tight 2% trail after profit
    private const val MAX_HOLD_MINUTES = 8            // Quick scalps - don't hold long
    
    // Trade frequency (ACTIVE - user choice 3c: 10+ trades/day)
    private const val MIN_TRADES_PER_DAY = 10
    private const val MAX_CONCURRENT_POSITIONS = 4   // Allow more concurrent for active scalping
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING (Separate Paper vs Live)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // PAPER MODE stats (reset at midnight or manually)
    private val paperDailyPnlSolBps = AtomicLong(0)  // Basis points (0.01 SOL = 1)
    private val paperDailyWins = AtomicInteger(0)
    private val paperDailyLosses = AtomicInteger(0)
    private val paperDailyTradeCount = AtomicInteger(0)
    private val paperTreasuryBalanceBps = AtomicLong(0)  // Total treasury accumulated (paper)
    
    // LIVE MODE stats (separate tracking)
    private val liveDailyPnlSolBps = AtomicLong(0)
    private val liveDailyWins = AtomicInteger(0)
    private val liveDailyLosses = AtomicInteger(0)
    private val liveDailyTradeCount = AtomicInteger(0)
    private val liveTreasuryBalanceBps = AtomicLong(0)  // Total treasury accumulated (live)
    
    // Current mode flag (set by BotService when mode changes)
    @Volatile private var isPaperMode: Boolean = true
    private var lastResetDay = 0
    
    // Active treasury positions (mint -> TreasuryPosition) - separate for paper/live
    private val paperPositions = mutableMapOf<String, TreasuryPosition>()
    private val livePositions = mutableMapOf<String, TreasuryPosition>()
    
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
    // CORE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate a token for Treasury Mode entry.
     * Returns a signal with entry decision and DYNAMIC position sizing.
     * 
     * DYNAMIC SIZING (User choice 2b):
     * - Size shrinks as we approach daily target
     * - Size grows slightly when behind (but never recklessly)
     * - A+ setups get slight boost
     */
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        liquidityUsd: Double,
        topHolderPct: Double,
        buyPressurePct: Double,
        v3Score: Int,
        v3Confidence: Int,
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
        
        // Adjust thresholds based on mode
        val confThreshold = when (mode) {
            TreasuryMode.DEFENSIVE -> 88  // Very strict when protecting gains
            TreasuryMode.CRUISE -> 82     // Slightly relaxed when cruising
            TreasuryMode.AGGRESSIVE -> 78 // A bit looser when behind (but still A-grade)
            else -> MIN_CONFIDENCE_FOR_ENTRY // 80% default
        }
        val scoreThreshold = when (mode) {
            TreasuryMode.DEFENSIVE -> 40
            TreasuryMode.CRUISE -> 32
            TreasuryMode.AGGRESSIVE -> 28
            else -> MIN_SCORE_FOR_ENTRY
        }
        
        // ─── FILTER CHECKS ───
        val rejectionReasons = mutableListOf<String>()
        
        if (v3Confidence < confThreshold) {
            rejectionReasons.add("conf=$v3Confidence<$confThreshold")
        }
        if (v3Score < scoreThreshold) {
            rejectionReasons.add("score=$v3Score<$scoreThreshold")
        }
        if (liquidityUsd < MIN_LIQUIDITY_USD) {
            rejectionReasons.add("liq=$${liquidityUsd.toInt()}<$${MIN_LIQUIDITY_USD.toInt()}")
        }
        if (topHolderPct > MAX_TOP_HOLDER_PCT) {
            rejectionReasons.add("topHolder=${topHolderPct.toInt()}%>${MAX_TOP_HOLDER_PCT.toInt()}%")
        }
        if (buyPressurePct < MIN_BUY_PRESSURE_PCT) {
            rejectionReasons.add("buyPressure=${buyPressurePct.toInt()}%<${MIN_BUY_PRESSURE_PCT.toInt()}%")
        }
        if (momentum < 0) {
            rejectionReasons.add("momentum=$momentum<0")
        }
        if (volatility > 50) {
            rejectionReasons.add("volatility=$volatility>50 (too risky)")
        }
        
        // Already have position in this token?
        if (activePositions.containsKey(mint)) {
            rejectionReasons.add("already_in_position")
        }
        
        // Too many active treasury positions? (Increased for active scalping)
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            rejectionReasons.add("max_positions_reached (${MAX_CONCURRENT_POSITIONS})")
        }
        
        if (rejectionReasons.isNotEmpty()) {
            return TreasurySignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = v3Confidence,
                reason = "REJECTED: ${rejectionReasons.joinToString(", ")}",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // ─── DYNAMIC POSITION SIZING (User choice 2b) ───
        // Size shrinks as we approach daily target
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val progressToTarget = (dailyPnl / DAILY_TARGET_MIN_SOL).coerceIn(0.0, 1.0)
        
        // Base size decreases as we approach target (100% → 40%)
        val progressMultiplier = 1.0 - (progressToTarget * 0.6)
        
        var positionSol = BASE_POSITION_SOL * progressMultiplier
        
        // Scale up slightly for A+ setups
        if (v3Confidence >= 88 && v3Score >= 40) {
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
        
        // Enforce min/max bounds
        positionSol = positionSol.coerceIn(MIN_POSITION_SOL, MAX_POSITION_SOL)
        
        // ─── DETERMINE EXIT LEVELS (Quick scalps 5-10%) ───
        val takeProfitPct = when (mode) {
            TreasuryMode.DEFENSIVE -> TAKE_PROFIT_MIN_PCT  // Take quick 5% when protecting
            TreasuryMode.CRUISE -> TAKE_PROFIT_PCT         // Standard 7%
            TreasuryMode.AGGRESSIVE -> TAKE_PROFIT_MAX_PCT // Let run to 10% when behind
            else -> TAKE_PROFIT_PCT
        }
        val stopLossPct = STOP_LOSS_PCT  // Never compromise on stop loss (-2%)
        
        ErrorLogger.info(TAG, "💰 TREASURY ENTRY: $symbol | " +
            "conf=$v3Confidence score=$v3Score | " +
            "size=${positionSol.fmt(3)} SOL (progress=${(progressToTarget*100).toInt()}%) | " +
            "TP=${takeProfitPct}% SL=${stopLossPct}% | mode=$mode | ${if (isPaperMode) "PAPER" else "LIVE"}")
        
        return TreasurySignal(
            shouldEnter = true,
            positionSizeSol = positionSol,
            takeProfitPct = takeProfitPct,
            stopLossPct = stopLossPct,
            confidence = v3Confidence,
            reason = "TREASURY_ENTRY: A-grade scalp setup",
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
        }
        
        dailyTradeCount.incrementAndGet()
        
        ErrorLogger.info(TAG, "💰 TREASURY OPENED: $symbol | " +
            "entry=$entryPrice | TP=$targetPrice | SL=$stopPrice | " +
            "size=$positionSol SOL | ${if (isPaperMode) "PAPER" else "LIVE"}")
    }
    
    /**
     * Check if position should exit (called on each price update).
     * Quick scalps with tight exits.
     */
    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        val pos = synchronized(activePositions) { activePositions[mint] } ?: return ExitSignal.HOLD
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            // Tight 2% trailing stop for quick scalps
            pos.trailingStop = currentPrice * (1 - TRAILING_STOP_PCT / 100)
        }
        
        // ─── EXIT CONDITIONS (Priority order) ───
        
        // 1. HIT MAX TAKE PROFIT (10%) - always exit
        if (pnlPct >= TAKE_PROFIT_MAX_PCT) {
            ErrorLogger.info(TAG, "💰 TREASURY MAX TP: ${pos.symbol} | +${pnlPct.fmt(1)}% (capped at ${TAKE_PROFIT_MAX_PCT}%)")
            return ExitSignal.TAKE_PROFIT
        }
        
        // 2. HIT STOP LOSS (HARD -2%)
        if (currentPrice <= pos.stopPrice) {
            ErrorLogger.info(TAG, "💰 TREASURY SL HIT: ${pos.symbol} | ${pnlPct.fmt(1)}%")
            return ExitSignal.STOP_LOSS
        }
        
        // 3. HIT TARGET TAKE PROFIT
        if (currentPrice >= pos.targetPrice) {
            ErrorLogger.info(TAG, "💰 TREASURY TP HIT: ${pos.symbol} | +${pnlPct.fmt(1)}%")
            return ExitSignal.TAKE_PROFIT
        }
        
        // 4. HIT TRAILING STOP (only if in profit > 3%)
        if (pnlPct > 3.0 && currentPrice <= pos.trailingStop) {
            ErrorLogger.info(TAG, "💰 TREASURY TRAIL HIT: ${pos.symbol} | +${pnlPct.fmt(1)}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. MAX HOLD TIME (8 mins for quick scalps)
        if (holdMinutes >= MAX_HOLD_MINUTES) {
            ErrorLogger.info(TAG, "💰 TREASURY TIME EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.TIME_EXIT
        }
        
        // 6. EARLY EXIT if profitable enough after 4 mins
        if (holdMinutes >= 4 && pnlPct >= TAKE_PROFIT_MIN_PCT) {
            ErrorLogger.info(TAG, "💰 TREASURY EARLY TP: ${pos.symbol} | +${pnlPct.fmt(1)}% @ ${holdMinutes}min")
            return ExitSignal.TAKE_PROFIT
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
     */
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100
        
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
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val winRate = if (dailyTradeCount.get() > 0) {
            dailyWins.get().toDouble() / dailyTradeCount.get() * 100
        } else 0.0
        
        val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
        ErrorLogger.info(TAG, "💰 TREASURY CLOSED [$modeLabel]: ${pos.symbol} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "reason=$exitReason | " +
            "Daily: ${dailyPnl.fmt(4)} SOL | " +
            "Win rate: ${winRate.fmt(0)}% | " +
            "Treasury: ${getCurrentTreasuryBalance().fmt(4)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE DETERMINATION (Based on daily progress)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current treasury mode based on daily P&L progress.
     * 
     * PAUSED: Hit $50 loss limit → stop trading
     * AGGRESSIVE: Behind (negative P&L) → slightly more risk
     * HUNT: Early progress (< 50% target) → normal trading
     * CRUISE: Good progress (50-80% target) → standard approach
     * DEFENSIVE: Near/hit target (> 80%) → protect gains
     */
    fun getCurrentMode(): TreasuryMode {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val progressPct = dailyPnl / DAILY_TARGET_MIN_SOL * 100
        
        return when {
            // Hit max loss ($50) → PAUSE until reset
            dailyPnl <= -DAILY_MAX_LOSS_SOL -> TreasuryMode.PAUSED
            
            // Hit or exceeded target → DEFENSIVE (protect gains)
            progressPct >= 80 -> TreasuryMode.DEFENSIVE
            
            // Good progress (50-80%) → CRUISE (steady)
            progressPct >= 50 -> TreasuryMode.CRUISE
            
            // Behind (negative) → AGGRESSIVE (slightly more risk)
            dailyPnl < 0 -> TreasuryMode.AGGRESSIVE
            
            // Normal progress (0-50%) → HUNT
            else -> TreasuryMode.HUNT
        }
    }
    
    /**
     * Get current stats for UI display (mode-aware)
     */
    fun getStats(): TreasuryStats {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val progressPct = (dailyPnl / DAILY_TARGET_MIN_SOL * 100).coerceIn(-100.0, 200.0)
        
        return TreasuryStats(
            dailyPnlSol = dailyPnl,
            dailyTargetSol = DAILY_TARGET_MIN_SOL,
            dailyMaxLossSol = DAILY_MAX_LOSS_SOL,
            progressPct = progressPct,
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
        )
    }
    
    data class TreasuryStats(
        val dailyPnlSol: Double,
        val dailyTargetSol: Double,
        val dailyMaxLossSol: Double,
        val progressPct: Double,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyTradeCount: Int,
        val winRate: Double,
        val activePositions: Int,
        val mode: TreasuryMode,
        val treasuryBalanceSol: Double,
        val isPaperMode: Boolean,
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
            append("(${progressPct.fmt(0)}% of \$500) | ")
            append("$dailyWins W / $dailyLosses L | ")
            append("Treasury: ${treasuryBalanceSol.fmt(3)}◎ ")
            append("[${if (isPaperMode) "PAPER" else "LIVE"}]")
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
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
