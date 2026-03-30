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
 * MOONSHOT TRADER AI - "ASYMMETRIC BETS" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * A hybrid trading layer combining the best of ShitCoin, BlueChip, and V3:
 * - ShitCoin's moonshot potential (let runners run, no cap on gains)
 * - BlueChip's quality filters (established tokens, higher liquidity)
 * - V3's fluid learning (adaptive TP/SL based on outcomes)
 * 
 * TARGET ZONE: $100K - $5M market cap ("emerging moonshots")
 * Above ShitCoin's micro-cap zone, below BlueChip's $1M+ established zone.
 * These are tokens that survived the initial launch phase and are building.
 * 
 * KEY PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Asymmetric Risk/Reward: Risk small, win big
 * 2. Let Winners Ride: Trailing stops, no hard TP caps
 * 3. Quality + Momentum: V3 scoring + volume/buyer analysis
 * 4. Fluid Learning: Adapts TP/SL/hold times based on outcomes
 * 
 * GOALS:
 * - Catch tokens transitioning from micro-cap to mid-cap
 * - Target 50-500%+ gains on successful moonshots
 * - Tight stops (-8 to -12%) to protect against failures
 * - 25-35% win rate acceptable due to asymmetric payoff
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object MoonshotTraderAI {
    
    private const val TAG = "MoonshotAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - The "Goldilocks Zone" for moonshots
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Market cap filter - Between ShitCoin (<$500K) and BlueChip (>$1M)
    private const val MIN_MARKET_CAP_USD = 100_000.0     // $100K minimum
    private const val MAX_MARKET_CAP_USD = 5_000_000.0   // $5M maximum
    
    // Liquidity requirements - moderate (survived the micro-cap phase)
    private const val MIN_LIQUIDITY_USD_BOOTSTRAP = 15_000.0   // $15K at start
    private const val MIN_LIQUIDITY_USD_MATURE = 10_000.0      // $10K once learned
    
    // Position sizing - moderate risk
    private const val BASE_POSITION_SOL = 0.08        // 0.08 SOL base (~$12)
    private const val MAX_POSITION_SOL = 0.30         // Up to 0.3 SOL per moonshot
    private const val MAX_CONCURRENT_POSITIONS = 4    // Max 4 moonshot positions
    
    // Take profit / Stop loss - FLUID (wider than BlueChip, learns from outcomes)
    // Bootstrap: Moderate exits (balance learning with protection)
    // Mature: Wide targets (let moonshots moon!)
    private const val TAKE_PROFIT_BOOTSTRAP = 50.0     // 50% at start (still good)
    private const val TAKE_PROFIT_MATURE = 200.0       // 200%+ when experienced (let it ride!)
    private const val STOP_LOSS_BOOTSTRAP = -8.0       // 8% stop at start
    private const val STOP_LOSS_MATURE = -12.0         // 12% stop when mature
    private const val TRAILING_STOP_PCT = 12.0         // Wider trailing for bigger swings
    private const val MAX_HOLD_MINUTES = 45            // Longer holds for moonshots
    private const val FLAT_EXIT_MINUTES = 15           // Exit if flat after 15 mins
    
    // Compounding
    private const val COMPOUNDING_ENABLED = true
    private const val COMPOUNDING_RATIO = 0.25         // 25% of profits compound
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MOONSHOT-SPECIFIC THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Volume requirements - needs momentum
    private const val MIN_VOLUME_SCORE = 15            // Moderate volume threshold
    private const val MIN_BUY_PRESSURE = 55            // >55% buy pressure preferred
    
    // Quality requirements
    private const val MIN_RUGCHECK_SCORE = 25          // Higher than ShitCoin
    private const val MIN_ENTRY_SCORE = 65             // V3 score threshold
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile var isPaperMode: Boolean = true
    
    // Daily tracking
    private val dailyPnlSolBps = AtomicLong(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val dailyTradeCount = AtomicInteger(0)
    
    // Balances
    private val paperBalanceBps = AtomicLong(100_0000)  // 1 SOL paper
    private val liveBalanceBps = AtomicLong(0)
    
    // Fluid learning progress (0-100%)
    private var learningProgress = 0.0
    
    // Active positions
    private val activePositions = ConcurrentHashMap<String, MoonshotPosition>()
    private val livePositions = ConcurrentHashMap<String, MoonshotPosition>()
    private val paperPositions = ConcurrentHashMap<String, MoonshotPosition>()
    
    // Position data
    data class MoonshotPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        var takeProfitPct: Double,
        var stopLossPct: Double,
        val marketCapUsd: Double,
        val liquidityUsd: Double,
        val entryScore: Double,
        val mode: String,
        val isPaperMode: Boolean,
        var highWaterMark: Double = entryPrice,
        var trailingStop: Double = entryPrice * (1 - TRAILING_STOP_PCT / 100),
        var firstTakeDone: Boolean = false,
        var partialSellPct: Double = 0.0,
    )
    
    // Exit signals
    enum class ExitSignal {
        HOLD,
        STOP_LOSS,
        TAKE_PROFIT,
        TRAILING_STOP,
        PARTIAL_TAKE,
        FLAT_EXIT,
        RUG_DETECTED,
        TIMEOUT,
    }
    
    // Scoring result
    data class MoonshotScore(
        val eligible: Boolean,
        val score: Int,
        val confidence: Double,
        val rejectReason: String = "",
        val suggestedSizeSol: Double = 0.0,
        val takeProfitPct: Double = 0.0,
        val stopLossPct: Double = 0.0,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize(isPaper: Boolean = true) {
        isPaperMode = isPaper
        ErrorLogger.info(TAG, "Moonshot initialized | mode=${if (isPaper) "PAPER" else "LIVE"}")
    }
    
    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyTradeCount.set(0)
        ErrorLogger.info(TAG, "Daily stats reset")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING - Combines ShitCoin's moonshot detection + BlueChip's quality
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun scoreToken(
        mint: String,
        symbol: String,
        marketCapUsd: Double,
        liquidityUsd: Double,
        volumeScore: Int,
        buyPressurePct: Double,
        rugcheckScore: Int,
        v3EntryScore: Double,
        v3Confidence: Double,
        phase: String,
        isPaper: Boolean,
    ): MoonshotScore {
        
        // 1. Market cap filter
        if (marketCapUsd < MIN_MARKET_CAP_USD) {
            return MoonshotScore(false, 0, 0.0, "mcap_too_low_${(marketCapUsd/1000).toInt()}K")
        }
        if (marketCapUsd > MAX_MARKET_CAP_USD) {
            return MoonshotScore(false, 0, 0.0, "mcap_too_high_${(marketCapUsd/1_000_000).toInt()}M")
        }
        
        // 2. Liquidity filter
        val minLiq = if (learningProgress < 0.5) MIN_LIQUIDITY_USD_BOOTSTRAP else MIN_LIQUIDITY_USD_MATURE
        if (liquidityUsd < minLiq) {
            return MoonshotScore(false, 0, 0.0, "liq_too_low_${(liquidityUsd/1000).toInt()}K")
        }
        
        // 3. Position limit
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            return MoonshotScore(false, 0, 0.0, "max_positions_reached")
        }
        
        // 4. Already have position
        if (hasPosition(mint)) {
            return MoonshotScore(false, 0, 0.0, "already_have_position")
        }
        
        // 5. Quality checks
        if (rugcheckScore < MIN_RUGCHECK_SCORE) {
            return MoonshotScore(false, 0, 0.0, "rugcheck_${rugcheckScore}_low")
        }
        
        // ─── SCORING ───
        var score = 0
        
        // Market cap bonus (middle of range is best)
        val mcapScore = when {
            marketCapUsd in 200_000.0..2_000_000.0 -> 20  // Sweet spot
            marketCapUsd in 100_000.0..200_000.0 -> 15   // Early moonshot
            else -> 10  // $2M-$5M range
        }
        score += mcapScore
        
        // Liquidity bonus
        val liqScore = when {
            liquidityUsd > 100_000 -> 15
            liquidityUsd > 50_000 -> 12
            liquidityUsd > 25_000 -> 10
            else -> 5
        }
        score += liqScore
        
        // Volume momentum
        val volScore = when {
            volumeScore > 30 -> 15
            volumeScore > 20 -> 12
            volumeScore > MIN_VOLUME_SCORE -> 10
            else -> 0
        }
        score += volScore
        
        // Buy pressure
        val buyScore = when {
            buyPressurePct > 70 -> 15
            buyPressurePct > 60 -> 12
            buyPressurePct > MIN_BUY_PRESSURE -> 10
            else -> 5
        }
        score += buyScore
        
        // V3 score integration
        val v3Score = when {
            v3EntryScore > 85 -> 20
            v3EntryScore > 75 -> 15
            v3EntryScore > MIN_ENTRY_SCORE -> 10
            else -> 0
        }
        score += v3Score
        
        // Rugcheck safety
        val safetyScore = when {
            rugcheckScore > 60 -> 15
            rugcheckScore > 40 -> 10
            else -> 5
        }
        score += safetyScore
        
        // Phase bonus
        val phaseScore = when {
            phase.contains("accumulation", ignoreCase = true) -> 10
            phase.contains("early", ignoreCase = true) -> 8
            phase.contains("breakout", ignoreCase = true) -> 12
            else -> 5
        }
        score += phaseScore
        
        // Minimum threshold
        val minScore = if (learningProgress < 0.2) 60 else 55
        if (score < minScore) {
            return MoonshotScore(false, score, 0.0, "score_${score}_below_${minScore}")
        }
        
        // Calculate confidence and sizing
        val confidence = min(100.0, (score.toDouble() / 100) * 100 + v3Confidence * 0.3)
        
        // Position sizing based on score
        val sizeSol = when {
            score > 85 -> MAX_POSITION_SOL
            score > 75 -> BASE_POSITION_SOL * 2
            score > 65 -> BASE_POSITION_SOL * 1.5
            else -> BASE_POSITION_SOL
        }
        
        // Fluid TP/SL based on learning progress
        val tp = lerp(TAKE_PROFIT_BOOTSTRAP, TAKE_PROFIT_MATURE)
        val sl = lerp(STOP_LOSS_BOOTSTRAP, STOP_LOSS_MATURE)
        
        return MoonshotScore(
            eligible = true,
            score = score,
            confidence = confidence,
            suggestedSizeSol = min(sizeSol, MAX_POSITION_SOL),
            takeProfitPct = tp,
            stopLossPct = sl,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)
    
    fun getActivePositions(): List<MoonshotPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }
    
    fun getActivePositionsForMode(isPaper: Boolean): List<MoonshotPosition> {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) {
            positions.values.toList()
        }
    }
    
    fun addPosition(position: MoonshotPosition) {
        synchronized(activePositions) {
            activePositions[position.mint] = position
        }
        
        val targetMap = if (position.isPaperMode) paperPositions else livePositions
        synchronized(targetMap) {
            targetMap[position.mint] = position
        }
        
        dailyTradeCount.incrementAndGet()
        
        ErrorLogger.info(TAG, "MOONSHOT ENTRY: ${position.symbol} | " +
            "mcap=\$${(position.marketCapUsd/1_000).toInt()}K | " +
            "liq=\$${(position.liquidityUsd/1000).toInt()}K | " +
            "size=${position.entrySol} SOL | " +
            "TP=${position.takeProfitPct.toInt()}% SL=${position.stopLossPct.toInt()}%")
    }
    
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return
        
        val targetMap = if (pos.isPaperMode) paperPositions else livePositions
        synchronized(targetMap) {
            targetMap.remove(mint)
        }
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * (pnlPct / 100)
        val isWin = pnlPct > 0
        
        // Update daily stats
        dailyPnlSolBps.addAndGet((pnlSol * 10000).toLong())
        if (isWin) dailyWins.incrementAndGet() else dailyLosses.incrementAndGet()
        
        // Update balance
        val balanceRef = if (pos.isPaperMode) paperBalanceBps else liveBalanceBps
        balanceRef.addAndGet((pnlSol * 10000).toLong())
        
        // Update learning progress
        updateLearning(pnlPct, isWin)
        
        val emoji = if (isWin) "" else ""
        ErrorLogger.info(TAG, "$emoji MOONSHOT CLOSED [${if (pos.isPaperMode) "PAPER" else "LIVE"}]: " +
            "${pos.symbol} | P&L: ${if (pnlSol >= 0) "+" else ""}${String.format("%.4f", pnlSol)} SOL " +
            "(${if (pnlPct >= 0) "+" else ""}${String.format("%.1f", pnlPct)}%) | " +
            "reason=$exitReason | " +
            "Win rate: ${getWinRatePct()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECKING - Moonshot-style (let runners run!)
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        val pos = synchronized(activePositions) { activePositions[mint] } ?: return ExitSignal.HOLD
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            // Dynamic trailing: tighter as profits grow
            val dynamicTrailPct = when {
                pnlPct >= 500 -> 8.0    // 5x+ → tight 8% trail
                pnlPct >= 200 -> 10.0   // 3x+ → 10% trail
                pnlPct >= 100 -> 12.0   // 2x+ → 12% trail
                else -> TRAILING_STOP_PCT
            }
            pos.trailingStop = currentPrice * (1 - dynamicTrailPct / 100)
        }
        
        // ─── EXIT CONDITIONS ───
        
        // 1. RUG DETECTED - massive drop
        if (holdMinutes < 10 && pnlPct < -40) {
            ErrorLogger.warn(TAG, "RUG DETECTED: ${pos.symbol} | ${pnlPct.toInt()}% in ${holdMinutes}min")
            return ExitSignal.RUG_DETECTED
        }
        
        // 2. STOP LOSS HIT
        if (pnlPct <= pos.stopLossPct) {
            ErrorLogger.info(TAG, "SL HIT: ${pos.symbol} | ${String.format("%.1f", pnlPct)}%")
            return ExitSignal.STOP_LOSS
        }
        
        // 3. PARTIAL TAKE at first target (let rest ride with trailing)
        if (!pos.firstTakeDone && pnlPct >= pos.takeProfitPct) {
            pos.firstTakeDone = true
            ErrorLogger.info(TAG, "PARTIAL TP: ${pos.symbol} | +${String.format("%.1f", pnlPct)}% - LETTING REST RIDE!")
            return ExitSignal.PARTIAL_TAKE
        }
        
        // 4. TRAILING STOP - locks in gains while letting it run
        if (pnlPct > 20.0 && currentPrice <= pos.trailingStop) {
            val totalGain = pnlPct
            ErrorLogger.info(TAG, "TRAIL EXIT: ${pos.symbol} | +${String.format("%.1f", totalGain)}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. FLAT EXIT - nothing happening
        if (holdMinutes >= FLAT_EXIT_MINUTES && pnlPct > -5.0 && pnlPct < 15.0) {
            ErrorLogger.info(TAG, "FLAT EXIT: ${pos.symbol} | ${String.format("%.1f", pnlPct)}% after ${holdMinutes}min")
            return ExitSignal.FLAT_EXIT
        }
        
        // 6. TIMEOUT - max hold time reached
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 30.0) {
            ErrorLogger.info(TAG, "TIMEOUT: ${pos.symbol} | ${String.format("%.1f", pnlPct)}% after ${holdMinutes}min")
            return ExitSignal.TIMEOUT
        }
        
        return ExitSignal.HOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateLearning(pnlPct: Double, isWin: Boolean) {
        // Increment learning progress based on trade outcomes
        val increment = when {
            pnlPct > 100 -> 0.03   // Big win = faster learning
            pnlPct > 50 -> 0.02
            isWin -> 0.01
            pnlPct < -20 -> 0.015  // Big loss = learning opportunity
            else -> 0.005
        }
        learningProgress = min(1.0, learningProgress + increment)
    }
    
    fun getLearningProgress(): Double = learningProgress
    
    private fun lerp(bootstrap: Double, mature: Double): Double {
        return bootstrap + (mature - bootstrap) * learningProgress
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getWinRatePct(): Int {
        val total = dailyWins.get() + dailyLosses.get()
        return if (total > 0) ((dailyWins.get().toDouble() / total) * 100).toInt() else 0
    }
    
    fun getDailyPnlSol(): Double = dailyPnlSolBps.get() / 10000.0
    
    fun getDailyWins(): Int = dailyWins.get()
    
    fun getDailyLosses(): Int = dailyLosses.get()
    
    fun getBalance(isPaper: Boolean): Double {
        return (if (isPaper) paperBalanceBps.get() else liveBalanceBps.get()) / 10000.0
    }
    
    fun getPositionCount(): Int = activePositions.size
    
    // Get current TP/SL based on fluid learning
    fun getCurrentTakeProfit(): Double = lerp(TAKE_PROFIT_BOOTSTRAP, TAKE_PROFIT_MATURE)
    fun getCurrentStopLoss(): Double = lerp(STOP_LOSS_BOOTSTRAP, STOP_LOSS_MATURE)
    
    private fun Double.fmt(decimals: Int) = String.format("%.${decimals}f", this)
}
