package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * BLUE CHIP TRADER AI - "QUALITY MODE" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * A quality-focused trading layer for established tokens with >$1M market cap.
 * Separate from Treasury (micro-cap scalping) and V3 (general trading).
 * 
 * KEY DIFFERENCES FROM TREASURY:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Market Cap Filter: MINIMUM $1M (vs Treasury's no minimum)
 * 2. Hold Times: LONGER (up to 30 mins vs Treasury's 8 mins)
 * 3. Profit Targets: HIGHER (10-25% vs Treasury's 5-10%)
 * 4. Trade Frequency: LOWER but HIGHER QUALITY
 * 5. Risk Tolerance: MODERATE (not ultra-conservative like Treasury)
 * 
 * PHILOSOPHY:
 * - Quality over quantity
 * - Larger, more established tokens have less rug risk
 * - Willing to hold longer for bigger moves
 * - Uses V3 scoring as input but applies its own filters
 * 
 * GOALS:
 * - Target: 10-25% gains per trade
 * - Win rate target: 60%+ (higher quality, fewer trades)
 * - Max loss per trade: -5% (wider stop than Treasury)
 * - Daily max loss: 1 SOL (~$150)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object BlueChipTraderAI {
    
    private const val TAG = "BlueChipAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Market cap filter - MINIMUM $1M
    private const val MIN_MARKET_CAP_USD = 1_000_000.0
    
    // Liquidity requirements - higher than Treasury
    private const val MIN_LIQUIDITY_USD = 50_000.0
    
    // Position sizing
    private const val BASE_POSITION_SOL = 0.15         // Larger than Treasury (0.05)
    private const val MAX_POSITION_SOL = 0.5           // Up to 0.5 SOL per trade
    private const val MAX_CONCURRENT_POSITIONS = 3     // Max 3 Blue Chip positions at once
    
    // Daily limits
    private const val DAILY_MAX_LOSS_SOL = 1.0         // ~$150 daily loss limit
    
    // Take profit / Stop loss - FLUID (adapts as bot learns)
    // Bootstrap: Tighter exits (secure wins while learning)
    // Mature: Wider targets (let quality plays develop)
    private const val TAKE_PROFIT_BOOTSTRAP = 10.0     // 10% at start
    private const val TAKE_PROFIT_MATURE = 25.0        // 25% when experienced
    private const val STOP_LOSS_BOOTSTRAP = -4.0       // 4% stop at start (tight)
    private const val STOP_LOSS_MATURE = -7.0          // 7% stop when mature (learned volatility)
    private const val MAX_HOLD_MINUTES = 30            // Longer hold time
    
    // Compounding
    private const val COMPOUNDING_ENABLED = true
    private const val COMPOUNDING_RATIO = 0.3          // 30% of profits compound
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Mode tracking
    @Volatile var isPaperMode: Boolean = true
    
    // Daily P&L tracking (in basis points for precision)
    private val dailyPnlSolBps = AtomicLong(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val dailyTradeCount = AtomicInteger(0)
    
    // Separate balances for paper and live
    private val paperBalanceBps = AtomicLong(0)        // Paper blue chip balance
    private val liveBalanceBps = AtomicLong(0)         // Live blue chip balance
    
    // Active positions
    private val livePositions = ConcurrentHashMap<String, BlueChipPosition>()
    private val paperPositions = ConcurrentHashMap<String, BlueChipPosition>()
    
    // Position data
    data class BlueChipPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val marketCapUsd: Double,
        val liquidityUsd: Double,
        val isPaper: Boolean,
        val takeProfitPct: Double,
        val stopLossPct: Double
    )
    
    // Evaluation result
    data class BlueChipSignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val confidence: Int,
        val reason: String,
        val mode: BlueChipMode,
        val isPaperMode: Boolean
    )
    
    enum class BlueChipMode {
        HUNTING,      // Looking for quality setups
        POSITIONED,   // Have positions, managing them
        CAUTIOUS,     // Near daily loss limit
        PAUSED        // Hit daily loss limit
    }
    
    enum class ExitSignal {
        TAKE_PROFIT,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
        HOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.0 CRITICAL: Flag to prevent re-initialization during runtime
    @Volatile
    private var initialized = false
    
    fun init(paperMode: Boolean, startingBalanceSol: Double = 0.5) {
        // V4.0 CRITICAL: Guard against re-initialization
        if (initialized) {
            ErrorLogger.warn(TAG, "⚠️ init() called again - BLOCKED (already initialized)")
            return
        }
        
        isPaperMode = paperMode
        
        // Initialize balance if needed
        if (paperMode && paperBalanceBps.get() == 0L) {
            paperBalanceBps.set((startingBalanceSol * 100).toLong())
        }
        
        initialized = true
        ErrorLogger.info(TAG, "🔵 Blue Chip Trader initialized (ONE-TIME) | " +
            "mode=${if (paperMode) "PAPER" else "LIVE"} | " +
            "balance=${getBalance(paperMode).fmt(4)} SOL | " +
            "minMcap=\$${(MIN_MARKET_CAP_USD/1_000_000).fmt(1)}M")
    }
    
    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyTradeCount.set(0)
        ErrorLogger.info(TAG, "🔵 Blue Chip daily stats reset")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BALANCE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getBalance(isPaper: Boolean): Double {
        return if (isPaper) paperBalanceBps.get() / 100.0 else liveBalanceBps.get() / 100.0
    }
    
    fun getCurrentBalance(): Double = getBalance(isPaperMode)
    
    fun addToBalance(profitSol: Double, isPaper: Boolean) {
        if (profitSol <= 0) return
        val bps = (profitSol * 100).toLong()
        if (isPaper) {
            paperBalanceBps.addAndGet(bps)
        } else {
            liveBalanceBps.addAndGet(bps)
        }
        ErrorLogger.info(TAG, "🔵 BLUE CHIP +${profitSol.fmt(4)} SOL | " +
            "Total ${if (isPaper) "PAPER" else "LIVE"}: ${getBalance(isPaper).fmt(4)} SOL")
    }
    
    fun getDailyPnlSol(): Double = dailyPnlSolBps.get() / 100.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val activePositions: ConcurrentHashMap<String, BlueChipPosition>
        get() = if (isPaperMode) paperPositions else livePositions
    
    fun getActivePositions(): List<BlueChipPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }
    
    fun getActivePositionsForMode(isPaper: Boolean): List<BlueChipPosition> {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) {
            positions.values.toList()
        }
    }
    
    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)
    
    fun addPosition(position: BlueChipPosition) {
        synchronized(activePositions) {
            activePositions[position.mint] = position
        }
        dailyTradeCount.incrementAndGet()
        
        ErrorLogger.info(TAG, "🔵 BLUE CHIP ENTRY: ${position.symbol} | " +
            "mcap=\$${(position.marketCapUsd/1_000_000).fmt(2)}M | " +
            "liq=\$${(position.liquidityUsd/1000).fmt(0)}K | " +
            "size=${position.entrySol.fmt(4)} SOL | " +
            "TP=${position.takeProfitPct.fmt(0)}% SL=${position.stopLossPct.fmt(0)}%")
    }
    
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100
        
        // Record to daily P&L
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)
        
        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            addToBalance(pnlSol * COMPOUNDING_RATIO, pos.isPaper) // Compound portion
        } else {
            dailyLosses.incrementAndGet()
        }
        
        // V4.0: Blue Chip trades contribute to FluidLearningAI maturity
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
        val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
        
        ErrorLogger.info(TAG, "🔵 BLUE CHIP CLOSED [$modeLabel]: ${pos.symbol} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "reason=$exitReason | Daily: ${dailyPnl.fmt(4)} SOL | " +
            "Balance: ${getCurrentBalance().fmt(4)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION - Blue Chip specific scoring
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        topHolderPct: Double,
        buyPressurePct: Double,
        v3Score: Int,
        v3Confidence: Int,
        momentum: Double,
        volatility: Double
    ): BlueChipSignal {
        
        // Check current mode
        val mode = getCurrentMode()
        
        // If paused (hit daily loss), reject everything
        if (mode == BlueChipMode.PAUSED) {
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "PAUSED: Daily loss limit reached",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // BLUE CHIP FILTERS - Quality gates
        // ═══════════════════════════════════════════════════════════════════
        
        // 1. MARKET CAP FILTER - Must be >$1M
        if (marketCapUsd < MIN_MARKET_CAP_USD) {
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "MCAP_TOO_LOW: \$${(marketCapUsd/1000).toInt()}K < \$1M",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // 2. LIQUIDITY FILTER - Must have real liquidity
        val minLiq = getFluidMinLiquidity()
        if (liquidityUsd < minLiq) {
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "LIQ_TOO_LOW: \$${liquidityUsd.toInt()} < \$${minLiq.toInt()}",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // 3. MAX POSITIONS CHECK
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "MAX_POSITIONS: ${activePositions.size}/$MAX_CONCURRENT_POSITIONS",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // 4. ALREADY HAVE POSITION
        if (hasPosition(mint)) {
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "ALREADY_POSITIONED",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // BLUE CHIP SCORING - Quality-focused
        // ═══════════════════════════════════════════════════════════════════
        
        var blueChipScore = 0
        var blueChipConfidence = 0
        val scoreReasons = mutableListOf<String>()
        
        // 1. V3 SCORE CONTRIBUTION (0-30 pts)
        // Blue Chip uses V3 as input, weighted by confidence
        val v3Contribution = when {
            v3Score >= 80 && v3Confidence >= 70 -> 30
            v3Score >= 70 && v3Confidence >= 60 -> 25
            v3Score >= 60 && v3Confidence >= 50 -> 20
            v3Score >= 50 && v3Confidence >= 40 -> 15
            v3Score >= 40 -> 10
            else -> 5
        }
        blueChipScore += v3Contribution
        scoreReasons.add("v3+$v3Contribution")
        
        // 2. MARKET CAP SCORE (0-20 pts) - Prefer larger caps
        val mcapScore = when {
            marketCapUsd >= 10_000_000 -> 20   // >$10M
            marketCapUsd >= 5_000_000 -> 18    // >$5M
            marketCapUsd >= 3_000_000 -> 15    // >$3M
            marketCapUsd >= 2_000_000 -> 12    // >$2M
            marketCapUsd >= 1_000_000 -> 10    // >$1M (minimum)
            else -> 0
        }
        blueChipScore += mcapScore
        scoreReasons.add("mcap+$mcapScore")
        
        // 3. LIQUIDITY SCORE (0-20 pts)
        val liqScore = when {
            liquidityUsd >= 200_000 -> 20
            liquidityUsd >= 100_000 -> 18
            liquidityUsd >= 75_000 -> 15
            liquidityUsd >= 50_000 -> 12
            else -> 8
        }
        blueChipScore += liqScore
        scoreReasons.add("liq+$liqScore")
        
        // 4. BUY PRESSURE SCORE (0-15 pts)
        val buyScore = when {
            buyPressurePct >= 65 -> 15
            buyPressurePct >= 55 -> 12
            buyPressurePct >= 50 -> 10
            buyPressurePct >= 45 -> 8
            else -> 5
        }
        blueChipScore += buyScore
        scoreReasons.add("buy+$buyScore")
        
        // 5. MOMENTUM SCORE (-10 to +15 pts)
        val momentumScore = when {
            momentum >= 8 -> 15
            momentum >= 5 -> 12
            momentum >= 2 -> 8
            momentum >= 0 -> 5
            momentum >= -3 -> 0
            else -> -10
        }
        blueChipScore += momentumScore
        if (momentumScore != 0) scoreReasons.add("mom${if(momentumScore>0)"+" else ""}$momentumScore")
        
        // 6. TOP HOLDER CHECK (-15 to 0 pts)
        val holderPenalty = when {
            topHolderPct <= 15 -> 0
            topHolderPct <= 25 -> -5
            topHolderPct <= 35 -> -10
            else -> -15
        }
        blueChipScore += holderPenalty
        if (holderPenalty < 0) scoreReasons.add("holder$holderPenalty")
        
        // Calculate confidence
        blueChipConfidence = (
            (if (marketCapUsd > 2_000_000) 25 else 15) +
            (if (liquidityUsd > 75_000) 25 else 15) +
            (if (buyPressurePct > 50) 25 else 15) +
            (if (v3Confidence > 50) 25 else 15)
        ).coerceIn(0, 100)
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID THRESHOLDS - Bootstrap to Mature
        // ═══════════════════════════════════════════════════════════════════
        
        val learningProgress = FluidLearningAI.getLearningProgress()
        
        // Blue Chip thresholds - stricter than Treasury
        val minScore = getFluidScoreThreshold()
        val minConf = getFluidConfidenceThreshold()
        
        // Check thresholds
        val passesScore = blueChipScore >= minScore
        val passesConf = blueChipConfidence >= minConf
        
        if (!passesScore || !passesConf) {
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = blueChipConfidence,
                reason = "THRESHOLD_FAIL: score=$blueChipScore<$minScore conf=$blueChipConfidence<$minConf",
                mode = mode,
                isPaperMode = isPaperMode
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═══════════════════════════════════════════════════════════════════
        
        var positionSol = BASE_POSITION_SOL
        
        // Scale by confidence
        val confScale = blueChipConfidence / 100.0
        positionSol *= (0.7 + confScale * 0.6)  // 70-130% of base
        
        // Compounding bonus
        if (COMPOUNDING_ENABLED) {
            val balance = getCurrentBalance()
            if (balance > 0) {
                val compoundBonus = balance * COMPOUNDING_RATIO * confScale
                positionSol += compoundBonus
            }
        }
        
        // Cap at max
        positionSol = positionSol.coerceIn(0.05, MAX_POSITION_SOL)
        
        // Get fluid take profit
        val takeProfitPct = getFluidTakeProfit()
        
        // Get fluid exits
        val stopLossPct = getFluidStopLoss()
        
        ErrorLogger.info(TAG, "🔵 BLUE CHIP QUALIFIED: $symbol | " +
            "score=$blueChipScore conf=$blueChipConfidence% | " +
            "mcap=\$${(marketCapUsd/1_000_000).fmt(2)}M | " +
            "size=${positionSol.fmt(4)} SOL | " +
            "TP=${takeProfitPct.fmt(0)}% SL=${stopLossPct.toInt()}%")
        
        return BlueChipSignal(
            shouldEnter = true,
            positionSizeSol = positionSol,
            takeProfitPct = takeProfitPct,
            stopLossPct = stopLossPct,
            confidence = blueChipConfidence,
            reason = "QUALIFIED: ${scoreReasons.joinToString(" ")}",
            mode = mode,
            isPaperMode = isPaperMode
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS - Blue Chip specific
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.20: BlueChip gets slightly higher thresholds than other layers
    // but still lowered by 5 points (not 8) to allow quality paper trades
    // Bootstrap thresholds - STRICT (Blue Chip is quality-focused)
    private const val BC_SCORE_BOOTSTRAP = 40       // Lowered from 50 to find quality trades
    private const val BC_SCORE_MATURE = 30          // Loosen as we learn
    
    // V4.20: Lowered bootstrap conf from 25% to 20% (only 5 points)
    // BlueChip should be pickier but still able to learn
    private const val BC_CONF_BOOTSTRAP = 20        // Lowered by 5 points
    private const val BC_CONF_MATURE = 45           // Lowered from 50%
    private const val BC_CONF_BOOST_MAX = 12.0      // 12% bootstrap boost
    
    private const val BC_LIQ_BOOTSTRAP = 75_000.0   // Higher than Treasury
    private const val BC_LIQ_MATURE = 50_000.0      // Can take lower liq when experienced
    
    private fun lerp(bootstrap: Double, mature: Double): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * V4.1.2: Bootstrap confidence boost for BlueChip layer
     * Starts at +10% and decays to 0% as learning progresses
     */
    private fun getBootstrapConfBoost(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        // Boost decays from 10% to 0% as we learn
        return BC_CONF_BOOST_MAX * (1.0 - progress).coerceIn(0.0, 1.0)
    }
    
    fun getFluidScoreThreshold(): Int = lerp(BC_SCORE_BOOTSTRAP.toDouble(), BC_SCORE_MATURE.toDouble()).toInt()
    
    fun getFluidConfidenceThreshold(): Int {
        val baseConf = lerp(BC_CONF_BOOTSTRAP.toDouble(), BC_CONF_MATURE.toDouble())
        val boost = getBootstrapConfBoost()
        // During bootstrap: 25% base + 10% boost = 35% effective
        // At maturity: 50% base + 0% boost = 50% effective
        return (baseConf + boost).toInt()
    }
    
    fun getFluidMinLiquidity(): Double = lerp(BC_LIQ_BOOTSTRAP, BC_LIQ_MATURE)
    fun getFluidTakeProfit(): Double = lerp(TAKE_PROFIT_BOOTSTRAP, TAKE_PROFIT_MATURE)
    fun getFluidStopLoss(): Double = lerp(STOP_LOSS_BOOTSTRAP, STOP_LOSS_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getCurrentMode(): BlueChipMode {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val positionCount = activePositions.size
        
        return when {
            dailyPnl <= -DAILY_MAX_LOSS_SOL -> BlueChipMode.PAUSED
            dailyPnl <= -DAILY_MAX_LOSS_SOL * 0.7 -> BlueChipMode.CAUTIOUS
            positionCount > 0 -> BlueChipMode.POSITIONED
            else -> BlueChipMode.HUNTING
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class BlueChipStats(
        val dailyPnlSol: Double,
        val dailyMaxLossSol: Double,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyTradeCount: Int,
        val winRate: Double,
        val activePositions: Int,
        val mode: BlueChipMode,
        val balanceSol: Double,
        val isPaperMode: Boolean,
        val minScoreThreshold: Int,
        val minConfThreshold: Int
    ) {
        fun summary(): String = buildString {
            val modeEmoji = when (mode) {
                BlueChipMode.HUNTING -> "🎯"
                BlueChipMode.POSITIONED -> "📊"
                BlueChipMode.CAUTIOUS -> "⚠️"
                BlueChipMode.PAUSED -> "⏸️"
            }
            append("$modeEmoji ${mode.name} | ")
            append("${if (dailyPnlSol >= 0) "+" else ""}${dailyPnlSol.fmt(3)}◎ | ")
            append("$dailyWins W / $dailyLosses L | ")
            append("Balance: ${balanceSol.fmt(3)}◎ ")
            append("[${if (isPaperMode) "PAPER" else "LIVE"}]")
        }
    }
    
    fun getStats(): BlueChipStats {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        
        return BlueChipStats(
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
            balanceSol = getCurrentBalance(),
            isPaperMode = isPaperMode,
            minScoreThreshold = getFluidScoreThreshold(),
            minConfThreshold = getFluidConfidenceThreshold()
        )
    }
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
