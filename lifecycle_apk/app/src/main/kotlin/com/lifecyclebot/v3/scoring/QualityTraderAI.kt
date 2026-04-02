package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * QUALITY TRADER AI - "PROFESSIONAL SOLANA TRADING" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * A quality-focused trading layer for established Solana tokens that aren't meme coins.
 * This is the MISSING LAYER between ShitCoin (degen) and BlueChip/Moonshot.
 * 
 * LAYER HIERARCHY:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Treasury (CashGenerationAI) - Scalping cash generator, self-funding
 * 2. ShitCoin (ShitCoinTraderAI) - Meme/degen plays  
 * 3. QUALITY (this layer) - Professional Solana trading, NOT meme-specific
 * 4. BlueChip/Moonshot - Large cap ($1M+) or moon shots (200%+)
 * 
 * KEY CHARACTERISTICS:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Market Cap: $100K - $1M (quality range, below BlueChip)
 * 2. NOT meme-focused: Looks for utility, real projects, good fundamentals
 * 3. Hold Times: 15-60 minutes (professional swing trading)
 * 4. Profit Targets: 15-50% (quality setups deserve patience)
 * 5. Risk: Moderate stops, quality over quantity
 * 
 * FILTERS (Distinguishes from ShitCoin):
 * - Higher liquidity requirements ($20K+)
 * - Token age preferences (not just fresh launches)
 * - Better holder distribution
 * - Lower volatility tolerance
 * - Avoids pure meme narratives
 * 
 * GOALS:
 * - Target: 15-50% gains per trade
 * - Win rate target: 50%+ (quality setups)
 * - Max loss per trade: -8%
 * - Professional, market-maker style trading
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object QualityTraderAI {
    
    private const val TAG = "QualityAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Market cap filter - Quality range (between ShitCoin and BlueChip)
    private const val MIN_MARKET_CAP_USD = 100_000.0    // $100K minimum
    private const val MAX_MARKET_CAP_USD = 1_000_000.0  // $1M max (above this = BlueChip)
    
    // Liquidity requirements - higher than ShitCoin
    private const val MIN_LIQUIDITY_USD = 20_000.0
    
    // Token age - prefer established tokens
    private const val MIN_AGE_MINUTES = 30              // At least 30 mins old
    private const val IDEAL_AGE_MINUTES = 120           // 2+ hours is ideal
    
    // Position sizing
    private const val BASE_POSITION_SOL = 0.08          // Between Treasury (0.01) and BlueChip (0.15)
    private const val MAX_POSITION_SOL = 0.25           // Up to 0.25 SOL per trade
    private const val MAX_CONCURRENT_POSITIONS = 4      // Max 4 Quality positions at once
    
    // Take profit / Stop loss - FLUID (adapts as bot learns)
    private const val TAKE_PROFIT_BOOTSTRAP = 15.0      // 15% at start
    private const val TAKE_PROFIT_MATURE = 50.0         // 50% when experienced
    private const val STOP_LOSS_BOOTSTRAP = -8.0        // 8% stop at start
    private const val STOP_LOSS_MATURE = -12.0          // 12% stop when mature
    private const val MAX_HOLD_MINUTES = 60             // Up to 1 hour hold
    
    // Quality filters
    private const val MIN_HOLDER_COUNT = 50             // At least 50 holders
    private const val MAX_TOP_HOLDER_PCT = 30.0         // No single holder > 30%
    private const val MIN_BUY_PRESSURE = 40             // At least 40% buy pressure
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val activePositions = ConcurrentHashMap<String, QualityPosition>()
    private var dailyPnlSol = 0.0
    private var totalTrades = 0
    private var wins = 0
    private var losses = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class QualityPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entryTime: Long,
        val entrySol: Double,
        val entryMcap: Double,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        var highWaterMark: Double = entryPrice,
        var trailingStop: Double = entryPrice * (1 + stopLossPct / 100),
    )
    
    data class QualitySignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double = 0.0,
        val takeProfitPct: Double = TAKE_PROFIT_BOOTSTRAP,
        val stopLossPct: Double = STOP_LOSS_BOOTSTRAP,
        val reason: String = "",
        val qualityScore: Int = 0,
    )
    
    enum class ExitSignal {
        HOLD,
        TAKE_PROFIT,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
        PROMOTE_BLUECHIP,   // Promote to BlueChip if mcap crosses $1M
        PROMOTE_MOONSHOT,   // Promote to Moonshot if gains > 100%
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        buyPressure: Int,
        tokenAgeMinutes: Double,
        holderCount: Int = 0,
        topHolderPct: Double = 0.0,
        v3Score: Int = 0,
        isMeme: Boolean = false,
    ): QualitySignal {
        
        // Check if already have position
        if (activePositions.containsKey(mint)) {
            return QualitySignal(false, reason = "Already have position")
        }
        
        // Check max positions
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            return QualitySignal(false, reason = "Max positions reached (${MAX_CONCURRENT_POSITIONS})")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // QUALITY FILTERS - This is NOT a meme coin layer
        // ═══════════════════════════════════════════════════════════════════
        
        // Market cap filter - Quality range
        if (marketCapUsd < MIN_MARKET_CAP_USD) {
            return QualitySignal(false, reason = "MCAP too low: $${marketCapUsd.toInt()} < $${MIN_MARKET_CAP_USD.toInt()}")
        }
        if (marketCapUsd > MAX_MARKET_CAP_USD) {
            return QualitySignal(false, reason = "MCAP too high for Quality (use BlueChip): $${marketCapUsd.toInt()}")
        }
        
        // Liquidity filter
        if (liquidityUsd < MIN_LIQUIDITY_USD) {
            return QualitySignal(false, reason = "Liquidity too low: $${liquidityUsd.toInt()} < $${MIN_LIQUIDITY_USD.toInt()}")
        }
        
        // Age filter - prefer established tokens
        if (tokenAgeMinutes < MIN_AGE_MINUTES) {
            return QualitySignal(false, reason = "Too new: ${tokenAgeMinutes.toInt()}min < ${MIN_AGE_MINUTES}min")
        }
        
        // Buy pressure filter
        if (buyPressure < MIN_BUY_PRESSURE) {
            return QualitySignal(false, reason = "Buy pressure low: $buyPressure% < $MIN_BUY_PRESSURE%")
        }
        
        // Holder distribution (if available)
        if (holderCount > 0 && holderCount < MIN_HOLDER_COUNT) {
            return QualitySignal(false, reason = "Too few holders: $holderCount < $MIN_HOLDER_COUNT")
        }
        if (topHolderPct > MAX_TOP_HOLDER_PCT) {
            return QualitySignal(false, reason = "Top holder too dominant: ${topHolderPct.toInt()}% > ${MAX_TOP_HOLDER_PCT.toInt()}%")
        }
        
        // Skip pure meme coins - this layer is for quality
        if (isMeme) {
            ErrorLogger.debug(TAG, "⚠️ Skipping $symbol - meme coin (use ShitCoin layer)")
            return QualitySignal(false, reason = "Meme coin - use ShitCoin layer")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // QUALITY SCORING
        // ═══════════════════════════════════════════════════════════════════
        
        var qualityScore = 0
        
        // Market cap in sweet spot
        val mcapScore = when {
            marketCapUsd in 200_000.0..500_000.0 -> 20  // Sweet spot
            marketCapUsd in 100_000.0..200_000.0 -> 10
            marketCapUsd in 500_000.0..1_000_000.0 -> 15
            else -> 0
        }
        qualityScore += mcapScore
        
        // Liquidity score
        val liqScore = when {
            liquidityUsd >= 100_000 -> 20
            liquidityUsd >= 50_000 -> 15
            liquidityUsd >= 30_000 -> 10
            else -> 5
        }
        qualityScore += liqScore
        
        // Age score (prefer established)
        val ageScore = when {
            tokenAgeMinutes >= IDEAL_AGE_MINUTES -> 15
            tokenAgeMinutes >= 60 -> 10
            tokenAgeMinutes >= MIN_AGE_MINUTES -> 5
            else -> 0
        }
        qualityScore += ageScore
        
        // Buy pressure score
        val buyScore = when {
            buyPressure >= 70 -> 20
            buyPressure >= 60 -> 15
            buyPressure >= 50 -> 10
            else -> 5
        }
        qualityScore += buyScore
        
        // V3 engine score bonus
        if (v3Score > 20) qualityScore += 10
        if (v3Score > 40) qualityScore += 10
        
        // Holder distribution bonus
        if (holderCount >= 100) qualityScore += 10
        if (topHolderPct < 15) qualityScore += 10
        
        // ═══════════════════════════════════════════════════════════════════
        // DECISION
        // ═══════════════════════════════════════════════════════════════════
        
        val minScore = 40  // Require 40+ quality score
        
        if (qualityScore < minScore) {
            return QualitySignal(false, reason = "Quality score too low: $qualityScore < $minScore", qualityScore = qualityScore)
        }
        
        // Calculate position size based on quality
        val sizeMultiplier = when {
            qualityScore >= 80 -> 1.5
            qualityScore >= 60 -> 1.2
            else -> 1.0
        }
        val positionSize = min(BASE_POSITION_SOL * sizeMultiplier, MAX_POSITION_SOL)
        
        // Get fluid TP/SL
        val tp = getFluidTakeProfit()
        val sl = getFluidStopLoss()
        
        ErrorLogger.info(TAG, "✅ QUALITY ENTRY: $symbol | mcap=$${marketCapUsd.toInt()} | " +
            "liq=$${liquidityUsd.toInt()} | score=$qualityScore | size=${positionSize} SOL | TP=$tp% SL=$sl%")
        
        return QualitySignal(
            shouldEnter = true,
            positionSizeSol = positionSize,
            takeProfitPct = tp,
            stopLossPct = sl,
            reason = "Quality setup: score=$qualityScore",
            qualityScore = qualityScore,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT LOGIC
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(
        mint: String,
        currentPrice: Double,
        currentMcap: Double = 0.0,
    ): ExitSignal {
        val pos = activePositions[mint] ?: return ExitSignal.HOLD
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // Update high water mark
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            // Trailing stop at 60% of gains (if in profit > 10%)
            if (pnlPct > 10) {
                pos.trailingStop = currentPrice * 0.94  // 6% trail from peak
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // PROMOTION CHECKS - Quality → BlueChip/Moonshot
        // ═══════════════════════════════════════════════════════════════════
        
        // If mcap crossed $1M, promote to BlueChip
        if (currentMcap > 1_000_000 && pnlPct > 0) {
            ErrorLogger.info(TAG, "🔵 QUALITY → BLUECHIP: ${pos.symbol} | mcap crossed $1M!")
            return ExitSignal.PROMOTE_BLUECHIP
        }
        
        // If gains > 100%, promote to Moonshot
        if (pnlPct >= 100) {
            ErrorLogger.info(TAG, "🚀 QUALITY → MOONSHOT: ${pos.symbol} | +${pnlPct.toInt()}% gains!")
            return ExitSignal.PROMOTE_MOONSHOT
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // STANDARD EXIT CHECKS
        // ═══════════════════════════════════════════════════════════════════
        
        // Take profit
        if (pnlPct >= pos.takeProfitPct) {
            ErrorLogger.info(TAG, "✅ QUALITY TP: ${pos.symbol} | +${pnlPct.toInt()}%")
            return ExitSignal.TAKE_PROFIT
        }
        
        // Stop loss
        if (pnlPct <= pos.stopLossPct) {
            ErrorLogger.info(TAG, "🛑 QUALITY SL: ${pos.symbol} | ${pnlPct.toInt()}%")
            return ExitSignal.STOP_LOSS
        }
        
        // Trailing stop (if in profit and hit trail)
        if (pnlPct > 10 && currentPrice <= pos.trailingStop) {
            ErrorLogger.info(TAG, "📉 QUALITY TRAIL: ${pos.symbol} | +${pnlPct.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // Max hold time - only exit if not profitable
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 5) {
            ErrorLogger.info(TAG, "⏰ QUALITY TIME: ${pos.symbol} | ${pnlPct.toInt()}% after ${holdMinutes}min")
            return ExitSignal.TIME_EXIT
        }
        
        return ExitSignal.HOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun addPosition(position: QualityPosition) {
        activePositions[position.mint] = position
        ErrorLogger.info(TAG, "📊 QUALITY OPENED: ${position.symbol} | " +
            "entry=${position.entryPrice} | TP=${position.takeProfitPct}% SL=${position.stopLossPct}%")
    }
    
    fun closePosition(mint: String, exitPrice: Double, exitSignal: ExitSignal) {
        val pos = activePositions.remove(mint) ?: return
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100
        val isWin = pnlPct > 0
        
        dailyPnlSol += pnlSol
        totalTrades++
        if (isWin) wins++ else losses++
        
        // V5.2.12: Record to FluidLearningAI for central maturity tracking
        // This ensures Quality trades contribute to system-wide learning
        try {
            FluidLearningAI.recordPaperTrade(isWin)  // Quality trades in paper mode for now
            ErrorLogger.debug(TAG, "📊 Recorded to FluidLearningAI: ${pos.symbol} ${if (isWin) "WIN" else "LOSS"}")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "FluidLearningAI record failed: ${e.message}")
        }
        
        ErrorLogger.info(TAG, "📊 QUALITY CLOSED: ${pos.symbol} | " +
            "${if (pnlPct >= 0) "+" else ""}${pnlPct.toInt()}% | reason=$exitSignal | " +
            "Daily: ${if (dailyPnlSol >= 0) "+" else ""}${String.format("%.4f", dailyPnlSol)} SOL")
    }
    
    fun getActivePositions(): List<QualityPosition> = activePositions.values.toList()
    
    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)
    
    fun clearAllPositions() {
        activePositions.clear()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getFluidTakeProfit(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return TAKE_PROFIT_BOOTSTRAP + (TAKE_PROFIT_MATURE - TAKE_PROFIT_BOOTSTRAP) * progress
    }
    
    fun getFluidStopLoss(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return STOP_LOSS_BOOTSTRAP + (STOP_LOSS_MATURE - STOP_LOSS_BOOTSTRAP) * progress
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getWinRate(): Double = if (totalTrades > 0) wins.toDouble() / totalTrades * 100 else 0.0
    
    fun getDailyPnl(): Double = dailyPnlSol
    
    fun resetDaily() {
        dailyPnlSol = 0.0
    }
    
    fun getStats(): String {
        val wr = getWinRate()
        return "Quality: ${activePositions.size} open | W/L: $wins/$losses | WR: ${wr.toInt()}% | Daily: ${String.format("%+.4f", dailyPnlSol)} SOL"
    }
}
