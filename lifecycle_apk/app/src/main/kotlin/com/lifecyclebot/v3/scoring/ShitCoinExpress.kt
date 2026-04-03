package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 💩 SHITCOIN EXPRESS - "QUICK RIDE MODE" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 🚂💩💨 ALL ABOARD THE POOP TRAIN! 💨💩🚂
 * 
 * This is the AGGRESSIVE sibling of ShitCoinTraderAI. While ShitCoin layer
 * focuses on careful evaluation and risk management, ShitCoinExpress is
 * designed for ONE thing: catching EXPLOSIVE moves and riding them FAST.
 * 
 * TARGET: +30% minimum profits or GTFO
 * 
 * PHILOSOPHY:
 * - Get in FAST on momentum ignition
 * - Ride the wave up aggressively
 * - Get out IMMEDIATELY when momentum fades
 * - Accept that some trades will be total losses (tight stops)
 * - The winners will be 30-100%+ so they cover the losers
 * 
 * REQUIREMENTS:
 * - Must have EXISTING positive momentum (no "catching knives")
 * - Must have HIGH buy pressure (60%+ buys in last 5 min)
 * - Must have ACCELERATING volume
 * - Token must be "hot" - social buzz, trending, DEX boosted
 * 
 * This mode is NOT for everyone. It's high octane, high risk, but when
 * it hits... 🚀💩🚀
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ShitCoinExpress {
    
    private const val TAG = "💩Express"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - Aggressive for quick rides
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Market cap limits - Even lower than ShitCoinTraderAI
    private const val MAX_MARKET_CAP_USD = 20_000.0   // Only <$30K tokens (V4.1: was $300K)
    private const val MIN_MARKET_CAP_USD = 2_000.0    // At least $5K
    
    // AGGRESSIVE momentum requirements
    private const val MIN_MOMENTUM_PCT = 5.0          // Must be pumping already
    private const val MIN_BUY_PRESSURE_PCT = 60.0     // Strong buy dominance
    
    // Position sizing - SMALL but FAST
    private const val BASE_POSITION_SOL = 0.03        // Tiny base
    private const val MAX_POSITION_SOL = 0.10         // Never exceed 0.1 SOL
    private const val MAX_CONCURRENT_RIDES = 3        // Only 3 rides at once
    
    // AGGRESSIVE take profits
    private const val MIN_TAKE_PROFIT_PCT = 30.0      // Minimum 30% or don't bother
    private const val TARGET_TAKE_PROFIT_PCT = 50.0   // Target 50%
    private const val MOONSHOT_TAKE_PROFIT_PCT = 100.0 // Let moonshots run
    
    // TIGHT stop losses - Cut fast if wrong
    private const val STOP_LOSS_PCT = -8.0            // Very tight 8%
    private const val TRAILING_STOP_PCT = 5.0         // Super tight trailing
    
    // V5.2: Removed max hold time - let runners run!
    private const val IDEAL_HOLD_MINUTES = 8          // V5.2: Increased from 5 to 8 mins
    
    // Daily limits
    private const val DAILY_MAX_LOSS_SOL = 0.25       // Small daily cap
    private const val DAILY_MAX_RIDES = 500           // Max 500 express rides/day
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile var isPaperMode: Boolean = true
    
    private val dailyPnlSolBps = AtomicLong(0)
    private val dailyRides = AtomicInteger(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val expressBalanceBps = AtomicLong(50)  // Start with 0.5 SOL
    
    private val activeRides = ConcurrentHashMap<String, ExpressRide>()
    
    // Track "hot" tokens we've already tried to avoid re-entry
    private val recentRides = ConcurrentHashMap<String, Long>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ExpressRide(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val entryMomentum: Double,
        val entryBuyPressure: Double,
        val isPaper: Boolean,
        var highWaterMark: Double = entryPrice,
        var trailingStop: Double = entryPrice * (1 - abs(STOP_LOSS_PCT) / 100),
        var ridePhase: RidePhase = RidePhase.BOARDING,
    )
    
    enum class RidePhase(val emoji: String) {
        BOARDING("🎫"),      // Just entered
        ACCELERATING("🚀"),  // Gaining momentum
        CRUISING("🚂"),      // Steady gains
        BRAKING("🛑"),       // Momentum fading
        CRASHED("💥"),       // Hit stop loss
    }
    
    data class ExpressSignal(
        val shouldRide: Boolean,
        val positionSizeSol: Double,
        val confidence: Int,
        val reason: String,
        val rideType: RideType,
        val estimatedGainPct: Double,
    )
    
    enum class RideType(val emoji: String, val targetPct: Double) {
        QUICK_FLIP("⚡", 30.0),     // Fast 30% flip
        MOMENTUM_RIDE("🚂", 50.0),  // Solid momentum ride
        MOONSHOT_EXPRESS("🚀", 100.0), // Full send moonshot
        NO_RIDE("❌", 0.0),
    }
    
    enum class ExitSignal {
        TAKE_PROFIT_30,
        TAKE_PROFIT_50,
        TAKE_PROFIT_100,
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
        MOMENTUM_DEATH,
        HOLD,
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.0 CRITICAL: Flag to prevent re-initialization during runtime
    @Volatile
    private var initialized = false
    
    fun init(paperMode: Boolean) {
        // V4.0 CRITICAL: Guard against re-initialization
        if (initialized) {
            ErrorLogger.warn(TAG, "⚠️ init() called again - BLOCKED (already initialized)")
            return
        }
        
        isPaperMode = paperMode
        initialized = true
        ErrorLogger.info(TAG, "💩🚂 SHITCOIN EXPRESS initialized (ONE-TIME) | " +
            "mode=${if (paperMode) "PAPER" else "LIVE"} | " +
            "target=+${MIN_TAKE_PROFIT_PCT.toInt()}% | " +
            "maxHold=UNLIMITED")
    }
    
    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyRides.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        recentRides.clear()
        ErrorLogger.info(TAG, "💩🚂 Express daily stats reset")
    }
    
    fun isEnabled(): Boolean = true
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION - Express ride qualification
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        momentum: Double,           // Current momentum %
        buyPressurePct: Double,     // Buy pressure % (buys vs sells)
        volumeChange: Double,       // Volume change vs avg (1.0 = normal)
        priceChange5Min: Double,    // Price change in last 5 mins
        isTrending: Boolean,
        isBoosted: Boolean,
        tokenAgeMinutes: Double,
    ): ExpressSignal {
        
        // ═══════════════════════════════════════════════════════════════════
        // EXPRESS FILTERS - Only the hottest tokens
        // ═══════════════════════════════════════════════════════════════════
        
        // Check daily limits
        if (dailyRides.get() >= DAILY_MAX_RIDES) {
            return noRide("DAILY_LIMIT: ${dailyRides.get()}/$DAILY_MAX_RIDES rides")
        }
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        if (dailyPnl <= -DAILY_MAX_LOSS_SOL) {
            return noRide("DAILY_LOSS_LIMIT: ${dailyPnl}◎")
        }
        
        // Max concurrent rides
        if (activeRides.size >= MAX_CONCURRENT_RIDES) {
            return noRide("MAX_RIDES: ${activeRides.size}/$MAX_CONCURRENT_RIDES")
        }
        
        // Already on this ride?
        if (activeRides.containsKey(mint)) {
            return noRide("ALREADY_RIDING")
        }
        
        // Recently tried this token?
        val lastRide = recentRides[mint]
        if (lastRide != null && System.currentTimeMillis() - lastRide < 30 * 60 * 1000) {
            return noRide("RECENT_RIDE: waited < 30min")
        }
        
        // Market cap check
        if (marketCapUsd > MAX_MARKET_CAP_USD) {
            return noRide("MCAP_TOO_HIGH: \$${(marketCapUsd/1000).toInt()}K")
        }
        if (marketCapUsd < MIN_MARKET_CAP_USD) {
            return noRide("MCAP_TOO_LOW: \$${marketCapUsd.toInt()}")
        }
        
        // Minimum liquidity
        if (liquidityUsd < 2_000) {
            return noRide("LIQ_TOO_LOW: \$${liquidityUsd.toInt()}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // EXPRESS REQUIREMENTS - Must have MOMENTUM
        // ═══════════════════════════════════════════════════════════════════
        
        // CRITICAL: Must already be pumping
        if (momentum < MIN_MOMENTUM_PCT) {
            return noRide("NO_MOMENTUM: ${momentum.fmt(1)}% < ${MIN_MOMENTUM_PCT}%")
        }
        
        // CRITICAL: Must have strong buy pressure
        if (buyPressurePct < MIN_BUY_PRESSURE_PCT) {
            return noRide("WEAK_BUYS: ${buyPressurePct.toInt()}% < ${MIN_BUY_PRESSURE_PCT.toInt()}%")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // EXPRESS SCORING - How hot is this ride?
        // ═══════════════════════════════════════════════════════════════════
        
        var expressScore = 0
        var estimatedGain = MIN_TAKE_PROFIT_PCT
        
        // Momentum score (0-30)
        val momentumScore = when {
            momentum >= 20 -> 30   // PARABOLIC!
            momentum >= 15 -> 25
            momentum >= 10 -> 20
            momentum >= 7 -> 15
            momentum >= 5 -> 10
            else -> 0
        }
        expressScore += momentumScore
        
        // Buy pressure score (0-25)
        val buyScore = when {
            buyPressurePct >= 80 -> 25  // Insane buying
            buyPressurePct >= 75 -> 22
            buyPressurePct >= 70 -> 18
            buyPressurePct >= 65 -> 14
            buyPressurePct >= 60 -> 10
            else -> 0
        }
        expressScore += buyScore
        
        // Volume surge score (0-20)
        val volumeScore = when {
            volumeChange >= 5.0 -> 20   // 5x volume = FOMO
            volumeChange >= 3.0 -> 16
            volumeChange >= 2.0 -> 12
            volumeChange >= 1.5 -> 8
            volumeChange >= 1.0 -> 5
            else -> 0
        }
        expressScore += volumeScore
        
        // 5-minute price change score (0-15)
        val priceScore = when {
            priceChange5Min >= 15 -> 15  // Already up 15% in 5 min!
            priceChange5Min >= 10 -> 12
            priceChange5Min >= 7 -> 10
            priceChange5Min >= 5 -> 7
            priceChange5Min >= 3 -> 5
            else -> 0
        }
        expressScore += priceScore
        
        // Trending/Boosted bonus (0-10)
        var trendScore = 0
        if (isTrending) trendScore += 5
        if (isBoosted) trendScore += 5
        expressScore += trendScore
        
        // Age bonus - Sweet spot is 15-60 mins (0-10)
        val ageScore = when {
            tokenAgeMinutes < 5 -> 5     // Too fresh, risky
            tokenAgeMinutes < 15 -> 7
            tokenAgeMinutes < 60 -> 10   // Sweet spot
            tokenAgeMinutes < 120 -> 7
            else -> 3
        }
        expressScore += ageScore
        
        // Calculate confidence
        val confidence = ((expressScore / 110.0) * 100).toInt().coerceIn(0, 100)
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID THRESHOLD CHECK
        // ═══════════════════════════════════════════════════════════════════
        
        val minScore = getFluidScoreThreshold()
        if (expressScore < minScore) {
            return noRide("SCORE_TOO_LOW: $expressScore < $minScore")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // DETERMINE RIDE TYPE
        // ═══════════════════════════════════════════════════════════════════
        
        val rideType = when {
            expressScore >= 85 && momentum >= 15 && buyPressurePct >= 75 -> {
                estimatedGain = 100.0
                RideType.MOONSHOT_EXPRESS
            }
            expressScore >= 65 && momentum >= 10 -> {
                estimatedGain = 50.0
                RideType.MOMENTUM_RIDE
            }
            expressScore >= minScore -> {
                estimatedGain = 30.0
                RideType.QUICK_FLIP
            }
            else -> RideType.NO_RIDE
        }
        
        if (rideType == RideType.NO_RIDE) {
            return noRide("NO_QUALIFIED_RIDE")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═══════════════════════════════════════════════════════════════════
        
        var positionSol = BASE_POSITION_SOL
        
        // Scale by confidence
        positionSol *= (0.5 + confidence / 100.0)
        
        // Scale by ride type
        positionSol *= when (rideType) {
            RideType.MOONSHOT_EXPRESS -> 1.5
            RideType.MOMENTUM_RIDE -> 1.2
            RideType.QUICK_FLIP -> 1.0
            else -> 0.0
        }
        
        // Cap at max
        positionSol = positionSol.coerceIn(0.02, MAX_POSITION_SOL)
        
        ErrorLogger.info(TAG, "💩🚂 EXPRESS QUALIFIED: $symbol | " +
            "${rideType.emoji} ${rideType.name} | " +
            "score=$expressScore conf=$confidence% | " +
            "mom=${momentum.fmt(1)}% buy=${buyPressurePct.toInt()}% | " +
            "size=${positionSol.fmt(4)} SOL | " +
            "target=${estimatedGain.toInt()}%")
        
        return ExpressSignal(
            shouldRide = true,
            positionSizeSol = positionSol,
            confidence = confidence,
            reason = "${rideType.emoji} mom=${momentum.toInt()}% buy=${buyPressurePct.toInt()}%",
            rideType = rideType,
            estimatedGainPct = estimatedGain,
        )
    }
    
    private fun noRide(reason: String): ExpressSignal {
        return ExpressSignal(
            shouldRide = false,
            positionSizeSol = 0.0,
            confidence = 0,
            reason = reason,
            rideType = RideType.NO_RIDE,
            estimatedGainPct = 0.0,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RIDE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun boardRide(
        mint: String,
        symbol: String,
        entryPrice: Double,
        entrySol: Double,
        momentum: Double,
        buyPressure: Double,
        isPaper: Boolean,
    ) {
        val ride = ExpressRide(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = entrySol,
            entryTime = System.currentTimeMillis(),
            entryMomentum = momentum,
            entryBuyPressure = buyPressure,
            isPaper = isPaper,
        )
        
        synchronized(activeRides) {
            activeRides[mint] = ride
        }
        dailyRides.incrementAndGet()
        recentRides[mint] = System.currentTimeMillis()
        
        ErrorLogger.info(TAG, "💩🎫 BOARDED: $symbol | " +
            "entry=${entryPrice.fmtPrice()} | " +
            "size=${entrySol.fmt(4)} SOL | " +
            "mom=${momentum.fmt(1)}% buy=${buyPressure.toInt()}%")
    }
    
    fun checkExit(mint: String, currentPrice: Double, currentMomentum: Double): ExitSignal {
        val ride = synchronized(activeRides) { activeRides[mint] } ?: return ExitSignal.HOLD
        
        val pnlPct = (currentPrice - ride.entryPrice) / ride.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - ride.entryTime) / 60_000
        
        // Update high water mark
        if (currentPrice > ride.highWaterMark) {
            ride.highWaterMark = currentPrice
            ride.trailingStop = currentPrice * (1 - TRAILING_STOP_PCT / 100)
            
            // Update ride phase
            ride.ridePhase = when {
                pnlPct >= 50 -> RidePhase.CRUISING
                pnlPct >= 20 -> RidePhase.ACCELERATING
                else -> RidePhase.BOARDING
            }
        }
        
        // ─── EXIT CONDITIONS (Priority order) ───
        
        // 1. MOONSHOT HIT (100%+)
        if (pnlPct >= MOONSHOT_TAKE_PROFIT_PCT) {
            ErrorLogger.info(TAG, "💩🚀 MOONSHOT! $mint | +${pnlPct.fmt(1)}%")
            return ExitSignal.TAKE_PROFIT_100
        }
        
        // 2. STRONG TARGET HIT (50%+) after 3+ mins
        if (pnlPct >= TARGET_TAKE_PROFIT_PCT && holdMinutes >= 3) {
            ErrorLogger.info(TAG, "💩🚂 TARGET HIT! $mint | +${pnlPct.fmt(1)}%")
            return ExitSignal.TAKE_PROFIT_50
        }
        
        // 3. MINIMUM TARGET HIT (30%+) after 2+ mins
        if (pnlPct >= MIN_TAKE_PROFIT_PCT && holdMinutes >= 2) {
            ErrorLogger.info(TAG, "💩⚡ QUICK WIN! $mint | +${pnlPct.fmt(1)}%")
            return ExitSignal.TAKE_PROFIT_30
        }
        
        // 4. STOP LOSS
        if (pnlPct <= STOP_LOSS_PCT) {
            ride.ridePhase = RidePhase.CRASHED
            ErrorLogger.info(TAG, "💩💥 CRASHED! $mint | ${pnlPct.fmt(1)}%")
            return ExitSignal.STOP_LOSS
        }
        
        // 5. TRAILING STOP (if profitable)
        if (pnlPct > 15.0 && currentPrice <= ride.trailingStop) {
            ride.ridePhase = RidePhase.BRAKING
            ErrorLogger.info(TAG, "💩🛑 TRAIL HIT! $mint | +${pnlPct.fmt(1)}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // 6. MOMENTUM DEATH - Momentum collapsed
        if (currentMomentum < 0 && pnlPct > 0) {
            ride.ridePhase = RidePhase.BRAKING
            ErrorLogger.info(TAG, "💩📉 MOM DEATH! $mint | +${pnlPct.fmt(1)}% mom=${currentMomentum.fmt(1)}%")
            return ExitSignal.MOMENTUM_DEATH
        }
        
        // V5.2: REMOVED max hold time - ShitCoins can moon anytime!
        // Only exit on: stop loss, trailing stop, take profit, or momentum death
        
        // 7. QUICK EXIT if losing momentum and not profitable
        if (holdMinutes >= IDEAL_HOLD_MINUTES && pnlPct < 10 && currentMomentum < ride.entryMomentum * 0.5) {
            ErrorLogger.info(TAG, "💩📉 FADING! $mint | ${pnlPct.fmt(1)}%")
            return ExitSignal.MOMENTUM_DEATH
        }
        
        return ExitSignal.HOLD
    }
    
    fun exitRide(mint: String, exitPrice: Double, exitSignal: ExitSignal) {
        val ride = synchronized(activeRides) { activeRides.remove(mint) } ?: return
        
        val pnlPct = (exitPrice - ride.entryPrice) / ride.entryPrice * 100
        val pnlSol = ride.entrySol * pnlPct / 100
        
        // Record P&L
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)
        
        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            // Add to express balance (50% compound)
            expressBalanceBps.addAndGet((pnlSol * 50).toLong())
        } else {
            dailyLosses.incrementAndGet()
        }
        
        // Record to FluidLearningAI
        try {
            if (ride.isPaper) {
                FluidLearningAI.recordPaperTrade(pnlSol > 0)
            } else {
                FluidLearningAI.recordLiveTrade(pnlSol > 0)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }
        
        val emoji = when (exitSignal) {
            ExitSignal.TAKE_PROFIT_100 -> "🚀"
            ExitSignal.TAKE_PROFIT_50 -> "🚂"
            ExitSignal.TAKE_PROFIT_30 -> "⚡"
            ExitSignal.STOP_LOSS -> "💥"
            ExitSignal.TRAILING_STOP -> "🛑"
            ExitSignal.TIME_EXIT -> "⏱"
            ExitSignal.MOMENTUM_DEATH -> "📉"
            else -> "💩"
        }
        
        ErrorLogger.info(TAG, "💩 EXPRESS EXIT: ${ride.symbol} | " +
            "$emoji ${exitSignal.name} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "Daily: ${dailyWins.get()}W/${dailyLosses.get()}L")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.3: FIXED - was inverted (60 bootstrap → 45 mature = HARDER during learning!)
    // Now correctly starts permissive in bootstrap and tightens as bot learns
    private const val EXPRESS_SCORE_BOOTSTRAP = 45  // Permissive during bootstrap (learning phase)
    private const val EXPRESS_SCORE_MATURE = 60     // Stricter when experienced (mature phase)
    
    private fun getFluidScoreThreshold(): Int {
        val progress = FluidLearningAI.getLearningProgress()
        return (EXPRESS_SCORE_BOOTSTRAP + (EXPRESS_SCORE_MATURE - EXPRESS_SCORE_BOOTSTRAP) * progress).toInt()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getActiveRides(): List<ExpressRide> {
        return synchronized(activeRides) { activeRides.values.toList() }
    }
    
    fun hasRide(mint: String): Boolean = activeRides.containsKey(mint)
    
    /**
     * V5.2: Force clear all rides on bot stop
     */
    fun clearAllRides() {
        synchronized(activeRides) {
            val count = activeRides.size
            activeRides.clear()
            ErrorLogger.info(TAG, "💩🚂 CLEARED $count Express rides on shutdown")
        }
    }
    
    fun getDailyPnlSol(): Double = dailyPnlSolBps.get() / 100.0
    
    data class ExpressStats(
        val dailyRides: Int,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyPnlSol: Double,
        val activeRides: Int,
        val winRate: Double,
        val isPaperMode: Boolean,
    )
    
    fun getStats(): ExpressStats {
        val totalTrades = dailyWins.get() + dailyLosses.get()
        return ExpressStats(
            dailyRides = dailyRides.get(),
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyPnlSol = dailyPnlSolBps.get() / 100.0,
            activeRides = activeRides.size,
            winRate = if (totalTrades > 0) dailyWins.get().toDouble() / totalTrades * 100 else 0.0,
            isPaperMode = isPaperMode,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
    private fun Double.fmtPrice(): String = if (this < 0.01) String.format("%.8f", this) else String.format("%.6f", this)
}
