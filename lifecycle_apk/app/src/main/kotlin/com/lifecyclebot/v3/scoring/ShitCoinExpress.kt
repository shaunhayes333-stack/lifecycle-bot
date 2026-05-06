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
    
    // Market cap limits - Match BotService gate ($300K)
    private const val MAX_MARKET_CAP_USD = 300_000.0  // V5.5: Was $20K — too restrictive, now matches BotService gate
    private const val MIN_MARKET_CAP_USD = 2_000.0    // At least $2K

    // V5.6.8: Lowered momentum requirements to actually trigger during bootstrap
    private const val MIN_MOMENTUM_PCT = 3.0          // V5.9.343: walk-back to pre-V5.9.194
    private const val MIN_BUY_PRESSURE_PCT = 50.0     // V5.9.343: walk-back to pre-V5.9.194
    
    // Position sizing - SMALL but FAST
    private const val BASE_POSITION_SOL = 0.05        // Tiny base
    private const val MAX_POSITION_SOL = 3.0          // Never exceed 0.1 SOL
    private const val MAX_CONCURRENT_RIDES = 50       // V5.9.495z12: 20→50 — never choke trader volume
    
    // AGGRESSIVE take profits
    private const val MIN_TAKE_PROFIT_PCT = 30.0      // Minimum 30% or don't bother
    private const val TARGET_TAKE_PROFIT_PCT = 50.0   // Target 50%
    private const val MOONSHOT_TAKE_PROFIT_PCT = 100.0 // Let moonshots run
    
    // TIGHT stop losses - Cut fast if wrong
    private const val STOP_LOSS_PCT = -8.0            // Very tight 8%
    private const val TRAILING_STOP_PCT = 5.0         // Super tight trailing
    
    // V5.2: Removed max hold time - let runners run!
    private const val IDEAL_HOLD_MINUTES = 20          // V5.2: Increased from 5 to 20 mins
    
    // Daily limits
    private const val DAILY_MAX_LOSS_SOL = 2.0       // Small daily cap
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
    // PERSISTENCE - V5.6.29c
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("shitcoin_express_ai", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "🚂 ShitCoinExpress persistence initialized")
    }
    
    private fun restore() {
        val p = prefs ?: return
        expressBalanceBps.set(p.getLong("expressBalanceBps", 50))
        
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyPnlSolBps.set(p.getLong("dailyPnlSolBps", 0))
            dailyRides.set(p.getInt("dailyRides", 0))
            dailyWins.set(p.getInt("dailyWins", 0))
            dailyLosses.set(p.getInt("dailyLosses", 0))
        }
        ErrorLogger.info(TAG, "🚂 RESTORED: balance=${expressBalanceBps.get()/100.0} | wins=${dailyWins.get()} losses=${dailyLosses.get()}")
    }
    
    fun save(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        
        p.edit().apply {
            putLong("expressBalanceBps", expressBalanceBps.get())
            putLong("savedDay", now / (24 * 60 * 60 * 1000))
            putLong("dailyPnlSolBps", dailyPnlSolBps.get())
            putInt("dailyRides", dailyRides.get())
            putInt("dailyWins", dailyWins.get())
            putInt("dailyLosses", dailyLosses.get())
            apply()
        }
    }
    
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
        // V5.9.168 — shared laddered profit-lock
        var peakPnlPct: Double = 0.0,
        var partialRungsTaken: Int = 0,
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
        if (liquidityUsd < 1_000) {
            return noRide("LIQ_TOO_LOW: \$${liquidityUsd.toInt()}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // EXPRESS REQUIREMENTS - Must have MOMENTUM
        // ═══════════════════════════════════════════════════════════════════
        //
        // V5.9.117: Fluid bootstrap gates. Fresh-launch tokens (age<3 min) have
        // zero history-based momentum yet, so the old hard 3% gate blocked every
        // Express ride during learning. During bootstrap (learning<0.5) we
        // accept 1.0% momentum OR a strong buy-pressure proxy; mature phase
        // keeps the original 3% / 50% discipline.
        val learningProgress = FluidLearningAI.getLearningProgress()
        val fluidMinMomentum = (1.0 + learningProgress * 2.0).coerceIn(1.0, MIN_MOMENTUM_PCT)
        val fluidMinBuyPressure = (45.0 + learningProgress * 5.0).coerceIn(45.0, MIN_BUY_PRESSURE_PCT)
        // If fresh launch with no usable momentum history yet, let strong buys
        // stand in for momentum (buyPressure >= 65% is itself a pump signal).
        val effectiveMomentum = if (momentum <= 0.0 && buyPressurePct >= 65.0) {
            (buyPressurePct - 60.0).coerceAtLeast(fluidMinMomentum)
        } else momentum

        // CRITICAL: Must already be pumping (fluid gate)
        if (effectiveMomentum < fluidMinMomentum) {
            return noRide("NO_MOMENTUM: ${effectiveMomentum.fmt(1)}% < ${fluidMinMomentum.fmt(1)}% (learning=${(learningProgress*100).toInt()}%)")
        }
        
        // CRITICAL: Must have strong buy pressure (fluid gate)
        if (buyPressurePct < fluidMinBuyPressure) {
            return noRide("WEAK_BUYS: ${buyPressurePct.toInt()}% < ${fluidMinBuyPressure.toInt()}% (learning=${(learningProgress*100).toInt()}%)")
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
        
        // Age bonus - Sweet spot is 5-60 mins (0-60)
        val ageScore = when {
            tokenAgeMinutes < 5 -> 5     // Too fresh, risky
            tokenAgeMinutes < 15 -> 7
            tokenAgeMinutes < 60 -> 10   // Sweet spot
            tokenAgeMinutes < 120 -> 7
            else -> 3
        }
        expressScore += ageScore
        
        // V5.9.236 build-fix: compute metaTrustMult BEFORE using it in confidence
        val metaTrustMult: Double = try {
            val metaPerf = com.lifecyclebot.v3.scoring.MetaCognitionAI.getAllLayerPerformance()
            val layer = metaPerf[com.lifecyclebot.v3.scoring.MetaCognitionAI.AILayer.SHITCOIN_EXPRESS]
            (layer?.trustMultiplier ?: 1.0).coerceIn(0.75, 1.30)
        } catch (_: Exception) { 1.0 }
        
        // Calculate confidence
        val confidence = ((expressScore / 110.0) * 100 * metaTrustMult).toInt().coerceIn(0, 100)
        
        // ═══════════════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════════
        // V5.9.230 — INTELLIGENCE GATE: MetaCognition + Education + BehaviorAI
        // ═══════════════════════════════════════════════════════════════════

        // 1. BehaviorAI tilt protection
        try {
            if (com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive()) {
                return noRide("TILT_BLOCK: BehaviorAI tilt protection active")
            }
        } catch (_: Exception) {}

        // 2. SymbolicExitReasoner — high exit urgency = don't board
        try {
            val symSnap = com.lifecyclebot.engine.SymbolicExitReasoner.getSignalSnapshot(symbol, mint)
            val urgencySignals = listOfNotNull(
                symSnap["BehaviorTilt"], symSnap["CrossRegime"],
                symSnap["Fragility"], symSnap["PortfolioHeat"],
            )
            val avgUrgency = if (urgencySignals.isNotEmpty()) urgencySignals.average() else 0.0
            if (avgUrgency > 0.80) {
                return noRide("SYMBOLIC_VETO: exit urgency %.2f > 0.80 — market not ready".format(avgUrgency))
            }
        } catch (_: Exception) {}

        // 3. MetaCognitionAI — trust multiplier already applied to confidence above (V5.9.236)

        // 4. EducationSubLayerAI — mute/boost on score
        try {
            val (educScore, educMult, educStatus) = com.lifecyclebot.v3.scoring.EducationSubLayerAI.applyMuteBoost("SHITCOIN_EXPRESS", expressScore)
            if (educStatus == "MUTE" || educStatus == "SOFT_PENALTY") {
                return noRide("EDU_MUTED: EXPRESS layer muted ($educStatus x${"%.2f".format(educMult)})")
            }
            expressScore = educScore.coerceAtLeast(0)
        } catch (_: Exception) {}

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
            expressScore >= 25 && momentum >= 15 && buyPressurePct >= 70 -> {
                estimatedGain = 100.0
                RideType.MOONSHOT_EXPRESS
            }
            expressScore >= 15 && momentum >= 10 -> {
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

        // V5.9.447 — UNIVERSAL JOURNAL COVERAGE. Express was previously a
        // silent execution lane that never wrote to TradeHistoryStore. Now
        // every boardRide writes a BUY row so the user's Journal reflects
        // every Express ride taken.
        try {
            com.lifecyclebot.engine.V3JournalRecorder.recordOpen(
                symbol = symbol, mint = mint,
                entryPrice = entryPrice, sizeSol = entrySol,
                isPaper = isPaper, layer = "EXPRESS",
                entryReason = "BOARDED",
            )
        } catch (_: Exception) {}

        ErrorLogger.info(TAG, "💩🎫 BOARDED: $symbol | " +
            "entry=${entryPrice.fmtPrice()} | " +
            "size=${entrySol.fmt(4)} SOL | " +
            "mom=${momentum.fmt(1)}% buy=${buyPressure.toInt()}%")
    }
    
    fun checkExit(mint: String, currentPrice: Double, currentMomentum: Double): ExitSignal {
        val ride = synchronized(activeRides) { activeRides[mint] } ?: return ExitSignal.HOLD

        val pnlPct = (currentPrice - ride.entryPrice) / ride.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - ride.entryTime) / 60_000

        // Update high water mark + peak
        if (pnlPct > ride.peakPnlPct) ride.peakPnlPct = pnlPct
        if (currentPrice > ride.highWaterMark) {
            ride.highWaterMark = currentPrice
            // V5.9.169 — continuous fluid trail (shared engine).
            val dynamicTrailPct = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidTrailPct(pnlPct)
            ride.trailingStop = currentPrice * (1 - dynamicTrailPct / 100)

            // Update ride phase
            ride.ridePhase = when {
                pnlPct >= 50 -> RidePhase.CRUISING
                pnlPct >= 20 -> RidePhase.ACCELERATING
                else -> RidePhase.BOARDING
            }
        }

        // ─── EXIT CONDITIONS (Priority order) ───

        // V5.9.168 — profit-floor ladder. Fires a trailing-stop exit if
        // the runner gives back below its locked-in tier. Biggest wins.
        // V5.9.169 — continuous fluid profit floor (shared engine).
        val profitFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(ride.peakPnlPct)
        if (pnlPct < profitFloor) {
            ErrorLogger.info(TAG, "💩🔒 FLOOR LOCK: $mint | peak +${ride.peakPnlPct.toInt()}% → +${pnlPct.toInt()}% < +${profitFloor.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }

        // V5.9.168 — first partial take at +30% fires ONCE, then we ride
        // the ladder. Previously +100% closed the WHOLE position; now
        // +100% is just a milestone on the ride up. Real exit only via
        // trailing / stop-loss / momentum death / floor-lock.
        val rungs = doubleArrayOf(30.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0)
        if (ride.partialRungsTaken < rungs.size && pnlPct >= rungs[ride.partialRungsTaken]) {
            val hit = rungs[ride.partialRungsTaken]
            ride.partialRungsTaken += 1
            ErrorLogger.info(TAG, "💩💰 LADDER #${ride.partialRungsTaken}: $mint | hit +${hit.toInt()}% (now +${pnlPct.fmt(1)}%)")
            return when (ride.partialRungsTaken) {
                1 -> ExitSignal.TAKE_PROFIT_30
                2 -> ExitSignal.TAKE_PROFIT_50
                else -> ExitSignal.TAKE_PROFIT_100
            }
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

        // V5.9.447 — UNIVERSAL JOURNAL COVERAGE. Every Express exit now
        // writes a SELL row so the user can see all Express closes with
        // PnL in the Journal. Was previously a silent lane.
        try {
            val holdMins = (System.currentTimeMillis() - ride.entryTime) / 60_000L
            com.lifecyclebot.engine.V3JournalRecorder.recordClose(
                symbol = ride.symbol, mint = ride.mint,
                entryPrice = ride.entryPrice, exitPrice = exitPrice,
                sizeSol = ride.entrySol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = ride.isPaper, layer = "EXPRESS",
                exitReason = exitSignal.name,
                holdMinutes = holdMins,
            )
        } catch (_: Exception) {}

        // Record P&L
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)

        // V5.9.495z17 — operator-mandated 70/30 profit split + missing
        // sentience hook (Express was 1 of 4/8 not feeding sentience).
        if (pnlSol > 0.0) {
            try {
                com.lifecyclebot.engine.TreasuryManager.contributeFromMemeSell(
                    pnlSol,
                    com.lifecyclebot.engine.WalletManager.lastKnownSolPrice,
                )
            } catch (_: Exception) {}
        }
        try {
            com.lifecyclebot.engine.SentienceHooks.recordEngineOutcome("MEME", pnlSol, pnlSol > 0.0)
        } catch (_: Exception) {}

        
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
                FluidLearningAI.recordSubTraderTrade(pnlSol > 0)
            } else {
                FluidLearningAI.recordSubTraderTrade(pnlSol > 0)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }

        // V5.9.230 — Sentience wiring: Education + PersonalityMemory + SentientPersonality
        try {
            val isWin = pnlPct > 0.0  // V5.9.408: restored pre-225 threshold
            val holdMins = (System.currentTimeMillis() - ride.entryTime) / 60_000.0
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordSimpleTradeOutcome(
                symbol     = ride.symbol,
                mint       = ride.mint,
                pnlPct     = pnlPct,
                holdMins   = holdMins,
                traderTag  = "EXPRESS",
                exitReason = exitSignal.name,
            )
            com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                pnlPct              = pnlPct,
                gaveBackFromPeakPct = (ride.peakPnlPct - pnlPct).coerceAtLeast(0.0),
                heldMinutes         = holdMins.toInt().coerceAtLeast(1),
            )
            if (isWin) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(ride.symbol, pnlPct, "EXPRESS", holdMins.toLong() * 60)
            } else {
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(ride.symbol, pnlPct, "EXPRESS", exitSignal.name)
            }
        } catch (_: Exception) {}
        
        // V5.6.29c: Persist after trade
        save()
        
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
    // V5.6.8: LOWERED for bootstrap learning - need to see EXPRESS actually trade!
    private const val EXPRESS_SCORE_BOOTSTRAP = 5   // V5.9.343: walk-back to pre-V5.9.194 for trade-from-start
    private const val EXPRESS_SCORE_MATURE = 20     // V5.9.442: 25→20 — user reported Express rarely firing
    
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
