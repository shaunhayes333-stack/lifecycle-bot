package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
private const val MIN_AGE_MINUTES = 5
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
 * 1. Market Cap: $50K - $250k (quality range, below BlueChip)
 * 2. NOT meme-focused: Looks for utility, real projects, good fundamentals
 * 3. Hold Times: 15-60 minutes (professional swing trading)
 * 4. Profit Targets: 15-50% (quality setups deserve patience)
 * 5. Risk: Moderate stops, quality over quantity
 * 
 * FILTERS (Distinguishes from ShitCoin):
 * - Higher liquidity requirements ($10K+)
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
    
    // V5.2.12: Quality layer handles professional mid-cap trading
    // Market cap: $100K - $1M (flows from ShitCoin at $100K, promotes to BlueChip at $1M)
    // V5.9.189: Lowered to $75K — $100K was too restrictive on Solana, many quality tokens sit 75-100K
    private const val MIN_MARKET_CAP_USD = 75_000.0    // V5.9.343: walk-back to pre-V5.9.341
    private const val MAX_MARKET_CAP_USD = 1_000_000.0 // $1M max (above this = BlueChip)
    
    // Liquidity requirements - higher standard than ShitCoin
    private const val MIN_LIQUIDITY_USD = 3_000.0      // V5.9.343: walk-back to V5.9.335
    
    // Token age - prefer established tokens
    // V5.2.12: Made fluid - lower during learning to gather data
    private const val MIN_AGE_MINUTES_BOOTSTRAP = 5      // V5.9.191: 5 mins (was 10) — catch early movers
    private const val MIN_AGE_MINUTES_MATURE = 20        // V5.9.191: 20 mins (was 30)
    private const val IDEAL_AGE_MINUTES = 60             // V5.9.191: 1+ hour ideal (was 2h — too selective)
    
    // Position sizing
    private const val BASE_POSITION_SOL = 0.08          // Between Treasury (0.01) and BlueChip (0.15)
    private const val MAX_POSITION_SOL = 0.25           // Up to 0.25 SOL per trade
    private const val MAX_CONCURRENT_POSITIONS = 20     // V5.9.343: walk-back to V5.9.336
    
    // Take profit / Stop loss - FLUID (adapts as bot learns)
    private const val TAKE_PROFIT_BOOTSTRAP = 15.0      // 15% at start
    private const val TAKE_PROFIT_MATURE = 50.0         // 50% when experienced
    // V5.9.189: Tighter SL for better risk:reward
    // TP is 15-50%, SL must be < half of TP. -6% bootstrap → -8% mature (not -12%!)
    // Losses MUST be smaller than wins. -12% SL with 15% TP = terrible R:R
    private const val STOP_LOSS_BOOTSTRAP = -8.0         // V5.9.316: REVERT V5.9.218 -6→-8 (build #1941)
    private const val STOP_LOSS_MATURE = -12.0           // V5.9.316: REVERT V5.9.218 -8→-12 (build #1941)
    private const val MAX_HOLD_MINUTES = 60             // Up to 1 hour hold
    
    // Quality filters
    private const val MIN_HOLDER_COUNT = 50             // At least 50 holders
    private const val MAX_TOP_HOLDER_PCT = 30.0         // No single holder > 30%
    private const val MIN_BUY_PRESSURE = 40             // At least 40% buy pressure
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE - V5.2.12: Added paper/live mode separation
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Mode tracking
    @Volatile private var isPaperMode: Boolean = true
    
    // Separate position tracking for paper and live
    private val paperPositions = ConcurrentHashMap<String, QualityPosition>()
    private val livePositions = ConcurrentHashMap<String, QualityPosition>()
    
    // Get active positions based on current mode
    private val activePositions: ConcurrentHashMap<String, QualityPosition>
        get() = if (isPaperMode) paperPositions else livePositions
    
    // Stats tracking
    private var dailyPnlSol = 0.0
    private var totalTrades = 0
    private var wins = 0
    private var losses = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE - V5.6.29c
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("quality_trader_ai", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "⭐ QualityTraderAI persistence initialized")
    }
    
    private fun restore() {
        val p = prefs ?: return
        totalTrades = p.getInt("totalTrades", 0)
        wins = p.getInt("wins", 0)
        losses = p.getInt("losses", 0)
        
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyPnlSol = p.getFloat("dailyPnlSol", 0f).toDouble()
        }
        ErrorLogger.info(TAG, "⭐ RESTORED: totalTrades=$totalTrades | wins=$wins losses=$losses")
    }
    
    fun save(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        
        p.edit().apply {
            putInt("totalTrades", totalTrades)
            putInt("wins", wins)
            putInt("losses", losses)
            putLong("savedDay", now / (24 * 60 * 60 * 1000))
            putFloat("dailyPnlSol", dailyPnlSol.toFloat())
            apply()
        }
    }

    /**
     * V5.2.12: Initialize with paper/live mode (legacy - use init(context) instead)
     */
    fun init(paperMode: Boolean) {
        isPaperMode = paperMode
        ErrorLogger.info(TAG, "⭐ Quality Trader initialized | mode=${if (paperMode) "PAPER" else "LIVE"}")
    }
    
    /**
     * V5.6.11: Set trading mode and transfer learning from paper to live
     */
    fun setTradingMode(isPaper: Boolean) {
        val wasInPaper = isPaperMode
        isPaperMode = isPaper
        
        // Transfer wins/losses counts for learning continuity
        if (!isPaper && wasInPaper) {
            ErrorLogger.info(TAG, "⭐ TRANSFER: Stats W=$wins L=$losses from PAPER continue in LIVE")
        }
    }

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
        // V5.9.166: shared laddered profit-lock
        var peakPnlPct: Double = 0.0,
        var partialRungsTaken: Int = 0,
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
        PARTIAL_TAKE,       // V5.9.166: laddered partial-sell signal
        PROMOTE_BLUECHIP,   // Promote to BlueChip if mcap crosses $250k
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
        
        // Check max positions — V5.9.193: bypassed during bootstrap for data gathering
        val qtBootstrap = FluidLearningAI.getLearningProgress() < 0.40
        if (!qtBootstrap && activePositions.size >= MAX_CONCURRENT_POSITIONS) {
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
        
        // Liquidity filter — FLUID: lower during bootstrap for data gathering
        // V5.4: was hard $20K floor; now $5K bootstrap → $20K mature
        val learningProgress = FluidLearningAI.getLearningProgress()
        val minLiqUsd = (3_000 + learningProgress * 7_000).coerceIn(3_000.0, MIN_LIQUIDITY_USD)  // V5.9.335: loosened
        if (liquidityUsd < minLiqUsd) {
            return QualitySignal(false, reason = "Liquidity too low: $${liquidityUsd.toInt()} < $${minLiqUsd.toInt()} (learning=${(learningProgress*100).toInt()}%)")
        }

        // Age filter - FLUID: Lower during learning to gather data
        val minAgeRequired = if (learningProgress < 0.5) MIN_AGE_MINUTES_BOOTSTRAP else MIN_AGE_MINUTES_MATURE
        
        if (tokenAgeMinutes < minAgeRequired) {
            return QualitySignal(false, reason = "Too new: ${tokenAgeMinutes.toInt()}min < ${minAgeRequired.toInt()}min (learning=${(learningProgress*100).toInt()}%)")
        }
        
        // Buy pressure filter — FLUID: lower during bootstrap so paper mode gets data
        // V5.4: was hard 40%, now 25% bootstrap → 40% mature
        val minBuyPressure = (20 + learningProgress * 15).toInt().coerceIn(20, 35)  // V5.9.335: loosened (was 25..40)
        if (buyPressure < minBuyPressure) {
            return QualitySignal(false, reason = "Buy pressure low: $buyPressure% < $minBuyPressure% (learning=${(learningProgress*100).toInt()}%)")
        }

        // Holder distribution (if available) — FLUID: lower during bootstrap
        // V5.4: was hard 50, now 10 bootstrap → 50 mature
        val minHolderCount = (10 + learningProgress * 40).toInt().coerceIn(10, 50)
        if (holderCount > 0 && holderCount < minHolderCount) {
            return QualitySignal(false, reason = "Too few holders: $holderCount < $minHolderCount (learning=${(learningProgress*100).toInt()}%)")
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
        
        // V5.9.189: Market cap scoring fixed — sweet spot now within actual entry range ($100K-$1M)
        // Old code gave 20pts for $50-100K which is BELOW the $100K minimum entry — unreachable
        val mcapScore = when {
            marketCapUsd in 100_000.0..350_000.0 -> 25  // Sweet spot: liquid micro-mid cap
            marketCapUsd in 350_000.0..600_000.0 -> 20  // Good range
            marketCapUsd in 600_000.0..1_000_000.0 -> 15 // Upper range — approaching BlueChip
            else -> 5  // Shouldn't happen given entry filters, but non-zero
        }
        qualityScore += mcapScore
        
        // Liquidity score
        val liqScore = when {
            liquidityUsd >= 50_000 -> 20
            liquidityUsd >= 25_000 -> 15
            liquidityUsd >= 10_000 -> 10
            else -> 5
        }
        qualityScore += liqScore
        
        // Age score (prefer established)

        require(MIN_AGE_MINUTES > 0) { "Invalid MIN_AGE_MINUTES" }
        
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
            else -> 0  // V5.9.218: was 5 — no free points for weak buy pressure (hard gate already blocks <50%)
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
        
        // V5.9.189: Lower floor to 20 at bootstrap so fresh install actually trades
        // Score breakdown: mcap(max 25) + liq(max 20) + age(max 15) + buy(max 20) + V3(0-20) = 100
        // At bootstrap: minScore=20 means liq+buy alone can get us in. At mature: 40 requires 3+ signals
        // V5.9.191: bootstrap 20→15, mature 40→35 — easier entry during learning phase
        val minScore = (10 + learningProgress * 15).toInt().coerceIn(10, 25)  // V5.9.335: loosened (was 15+20 → 15..35)

        // V5.9.316: REMOVED V5.9.218 HARD_GATE for buy pressure < 50%.
        // Build #1941 era used soft scoring — qualityScore already includes
        // buyPressure as a factor. The hard gate was over-filtering valid
        // entries with sparse/lagging buy-pressure data.

        if (qualityScore < minScore) {
            return QualitySignal(false, reason = "Quality score too low: $qualityScore < $minScore (learning=${(learningProgress*100).toInt()}%)", qualityScore = qualityScore)
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

        // V5.9.166 — SHARED LADDERED PROFIT-LOCK
        if (pnlPct > pos.peakPnlPct) pos.peakPnlPct = pnlPct

        val rungs = doubleArrayOf(20.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0)
        if (pos.partialRungsTaken < rungs.size && pnlPct >= rungs[pos.partialRungsTaken]) {
            val hitRung = rungs[pos.partialRungsTaken]
            pos.partialRungsTaken += 1
            ErrorLogger.info(TAG, "💎💰 LADDER PARTIAL #${pos.partialRungsTaken}: ${pos.symbol} | hit +${hitRung.toInt()}% (now +${pnlPct.toInt()}%)")
            return ExitSignal.PARTIAL_TAKE
        }

        // V5.9.169 — continuous fluid profit floor (shared engine).
        val profitFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(pos.peakPnlPct)
        if (pnlPct < profitFloor) {
            ErrorLogger.info(TAG, "💎🔒 FLOOR LOCK: ${pos.symbol} | peak +${pos.peakPnlPct.toInt()}% → +${pnlPct.toInt()}% < +${profitFloor.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // PROMOTION CHECKS - Quality → BlueChip/Moonshot
        // ═══════════════════════════════════════════════════════════════════
        
        // V5.8: Require substantial gain before promoting to BlueChip.
        // Previously promoted at ANY profit once mcap crossed $1M — tokens entering near $800K
        // could cross $1M at just +3% profit, then QualityTraderAI removes the position and
        // BlueChip doesn't have it, so SL never fires. Now requires pnlPct >= 15.
        if (currentMcap > 1_000_000 && pnlPct >= 15) {
            ErrorLogger.info(TAG, "🔵 QUALITY → BLUECHIP: ${pos.symbol} | mcap=$${(currentMcap/1000).toInt()}K pnl=+${pnlPct.toInt()}%")
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
        
        // V5.9.246: QUALITY DEAD EXIT — tiered, no gap, no 53-min zombies
        // If it hasn't moved positive by these checkpoints, the thesis is wrong.
        //   < -2% at 15min → early failure, cut
        //   < -4% at 25min → not recovering, cut
        //   < -5% at 30min → confirmed dead thesis, cut
        //   < 5%  at 60min → didn't deliver, cut (existing max-hold)
        if (holdMinutes >= 15 && pnlPct < -2.0) {
            ErrorLogger.info(TAG, "⏰ QUALITY DEAD[-2%@15m]: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes.toInt()}min")
            return ExitSignal.TIME_EXIT
        }
        if (holdMinutes >= 25 && pnlPct < -4.0) {
            ErrorLogger.info(TAG, "⏰ QUALITY DEAD[-4%@25m]: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes.toInt()}min")
            return ExitSignal.TIME_EXIT
        }
        if (holdMinutes >= 30 && pnlPct < -5.0) {
            ErrorLogger.info(TAG, "⏰ QUALITY DEAD[-5%@30m]: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes.toInt()}min (thesis failed)")
            return ExitSignal.TIME_EXIT
        }

        // V5.9.275: hard 40min cap if still losing — raised from 30min; require -1% not 0% (fee noise)
        if (holdMinutes >= 40 && pnlPct < -1.0) {
            ErrorLogger.info(TAG, "⏰ QUALITY MAXHOLD[-40m]: ${pos.symbol} | ${pnlPct.toInt()}% after ${holdMinutes.toInt()}min")
            return ExitSignal.TIME_EXIT
        }
        // Max hold time - only exit if not profitable
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 5) {
            ErrorLogger.info(TAG, "⏰ QUALITY TIME: ${pos.symbol} | ${pnlPct.toInt()}% after ${holdMinutes.toInt()}min")
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
        val isWin = pnlPct >= 1.0  // V5.9.225: unified 1% threshold

        // V5.9.318: Feed outcome into TradingCopilot for life-coach state.
        try { com.lifecyclebot.engine.TradingCopilot.recordTrade(pnlPct, isPaperMode) } catch (_: Exception) {}
        
        dailyPnlSol += pnlSol
        totalTrades++
        if (isWin) wins++ else losses++
        
        // V5.2.12: Record to FluidLearningAI for central maturity tracking
        // This ensures Quality trades contribute to system-wide learning
        try {
            if (isPaperMode) {
                FluidLearningAI.recordPaperTrade(isWin)
            } else {
                FluidLearningAI.recordLiveTrade(isWin)
            }
            ErrorLogger.debug(TAG, "📊 Recorded to FluidLearningAI [${if (isPaperMode) "PAPER" else "LIVE"}]: ${pos.symbol} ${if (isWin) "WIN" else "LOSS"}")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "FluidLearningAI record failed: ${e.message}")
        }
        
        // V5.6.29c: Persist after trade
        save()
        
        ErrorLogger.info(TAG, "📊 QUALITY CLOSED: ${pos.symbol} | " +
            "${if (pnlPct >= 0) "+" else ""}${pnlPct.toInt()}% | reason=$exitSignal | " +
            "Daily: ${if (dailyPnlSol >= 0) "+" else ""}${String.format("%.4f", dailyPnlSol)} SOL")
    }
    
    fun getActivePositions(): List<QualityPosition> = activePositions.values.toList()
    
    /**
     * V5.2.12: Get positions for specific mode
     */
    fun getActivePositionsForMode(isPaper: Boolean): List<QualityPosition> {
        return if (isPaper) paperPositions.values.toList() else livePositions.values.toList()
    }
    
    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)
    
    /**
     * V5.2.12: Clear all positions (both paper and live)
     */
    fun clearAllPositions() {
        synchronized(paperPositions) { paperPositions.clear() }
        synchronized(livePositions) { livePositions.clear() }
        ErrorLogger.info(TAG, "⭐ QUALITY: Cleared all positions")
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
    
    /**
     * V5.7: Get win rate as Int for perps learning bridge
     */
    fun getWinRatePct(): Int = getWinRate().toInt()
    
    /**
     * V5.7.3: Record learning from perps trades
     */
    fun recordPerpsLearning(
        symbol: String,
        isWin: Boolean,
        pnlPct: Double,
        leverage: Double,
    ) {
        try {
            // Update global counters
            if (isWin) wins++ else losses++
            
            // Learn optimal leverage for this symbol
            val leverageKey = "LEV_$symbol"
            val currentOptimal = leveragePreferences[leverageKey] ?: 3.0
            
            if (isWin && pnlPct > 10) {
                // This leverage worked well - bias toward it
                val newOptimal = (currentOptimal * 0.9 + leverage * 0.1).coerceIn(1.0, 10.0)
                leveragePreferences[leverageKey] = newOptimal
            } else if (!isWin) {
                // Loss - bias away from high leverage
                val newOptimal = (currentOptimal * 0.95 + 2.0 * 0.05).coerceIn(1.0, 10.0)
                leveragePreferences[leverageKey] = newOptimal
            }
            
            ErrorLogger.debug(TAG, "Perps learning: $symbol leverage pref=${leveragePreferences[leverageKey]?.fmt(1)}")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordPerpsLearning error: ${e.message}")
        }
    }
    
    /**
     * V5.7.3: Get recommended leverage for a symbol
     */
    fun getRecommendedLeverage(symbol: String): Double {
        return leveragePreferences["LEV_$symbol"] ?: 3.0
    }
    
    // Leverage preferences learned over time
    private val leveragePreferences = java.util.concurrent.ConcurrentHashMap<String, Double>()
    
    fun getDailyPnl(): Double = dailyPnlSol
    
    fun resetDaily() {
        dailyPnlSol = 0.0
    }
    
    fun getStats(): String {
        val wr = getWinRate()
        return "Quality: ${activePositions.size} open | W/L: $wins/$losses | WR: ${wr.toInt()}% | Daily: ${String.format("%+.4f", dailyPnlSol)} SOL"
    }
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
