package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DIP HUNTER AI - "BUY THE DIP" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 📉🎯 PROFESSIONAL DIP BUYING 🎯📉
 * 
 * This mode specializes in finding HIGH-QUALITY tokens that have experienced
 * a significant price pullback from recent highs. The key is distinguishing
 * between a "healthy dip" (buying opportunity) and a "death spiral" (falling knife).
 * 
 * TARGET SCENARIO:
 * - Token ran from $10K to $100K mcap
 * - Now pulled back to $60K (40% dip from high)
 * - Still has strong fundamentals, liquidity, holder base
 * - Volume spike indicates accumulation, not panic
 * - This is the perfect buying opportunity!
 * 
 * REQUIREMENTS FOR A "QUALITY DIP":
 * 1. Token must have MADE a significant high (not always been flat)
 * 2. Current price must be 15-50% below recent high (not too little, not too much)
 * 3. Liquidity must STILL be strong (no rug in progress)
 * 4. Buy/sell ratio must be improving (accumulation signs)
 * 5. Token must have reasonable age (established, not brand new)
 * 
 * DANGER SIGNS (AVOID):
 * - Dip > 60% from high (probably rugging)
 * - Liquidity dropping rapidly
 * - Holders count dropping
 * - DEV selling
 * - Volume dropping (no one cares anymore)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object DipHunterAI {
    
    private const val TAG = "DipHunter"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Dip depth requirements (% below high)
    private const val MIN_DIP_PCT = 15.0       // At least 15% dip (otherwise not a dip)
    private const val IDEAL_DIP_PCT = 30.0     // Ideal 30% dip
    private const val MAX_DIP_PCT = 55.0       // Max 55% dip (beyond = probably dying)
    private const val DANGER_DIP_PCT = 60.0    // >60% = falling knife territory
    
    // Market cap requirements
    private const val MIN_MCAP_USD = 50_000.0     // At least $50K mcap
    private const val MAX_MCAP_USD = 5_000_000.0  // Max $5M mcap
    
    // Liquidity requirements
    private const val MIN_LIQUIDITY_USD = 10_000.0  // Strong liquidity required
    private const val MIN_LIQUIDITY_RATIO = 0.10    // Liq must be >10% of mcap
    
    // Age requirements
    private const val MIN_TOKEN_AGE_HOURS = 2.0     // At least 2 hours old
    
    // Position sizing - BOOTSTRAP TIGHT (start conservative, fluid learning will loosen)
    private const val BASE_POSITION_SOL = 0.05      // Conservative base for bootstrap
    private const val MAX_POSITION_SOL = 0.15       // Max 0.15 SOL per dip (reduced from 0.25)
    private const val MAX_CONCURRENT_DIPS = 3       // Only 3 dip positions (reduced from 4)
    
    // Exit targets - FLUID (adapt as bot learns)
    // Bootstrap: Tighter exits (secure small wins while learning)
    // Mature: Let winners run (20-50% recovery targets)
    private const val TARGET_RECOVERY_BOOTSTRAP = 12.0   // 12% recovery target at start
    private const val TARGET_RECOVERY_MATURE = 25.0      // 25% recovery when experienced
    private const val STOP_LOSS_BOOTSTRAP = -12.0        // 12% stop at start (tighter)
    private const val STOP_LOSS_MATURE = -18.0           // 18% stop when experienced (wider, let it breathe)
    private const val MAX_HOLD_HOURS = 6.0               // 6 hour max hold
    
    /** Get fluid take profit for dip recovery */
    fun getFluidRecoveryTarget(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return TARGET_RECOVERY_BOOTSTRAP + (TARGET_RECOVERY_MATURE - TARGET_RECOVERY_BOOTSTRAP) * progress
    }
    
    /** Get fluid stop loss for dips */
    fun getFluidStopLoss(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return STOP_LOSS_BOOTSTRAP + (STOP_LOSS_MATURE - STOP_LOSS_BOOTSTRAP) * progress
    }
    
    // Daily limits - CRITICAL for bootstrap protection
    private const val DAILY_MAX_LOSS_SOL = 0.20     // Max 0.2 SOL daily loss
    private const val DAILY_MAX_HUNTS = 15          // Max 15 dip hunts per day
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile var isPaperMode: Boolean = true
    
    private val dailyPnlSolBps = AtomicLong(0)
    private val dailyHunts = AtomicInteger(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val dipBalanceBps = AtomicLong(100)  // 1 SOL start
    
    private val activeDips = ConcurrentHashMap<String, DipPosition>()
    
    // Track tokens we've already dip-bought
    private val recentDips = ConcurrentHashMap<String, Long>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class DipPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val highPrice: Double,       // Recent high before dip
        val dipDepthPct: Double,     // How deep was the dip at entry
        val entryMcap: Double,
        val entryLiquidity: Double,
        val isPaper: Boolean,
        var recoveryHighPct: Double = 0.0,  // Best recovery so far
    )
    
    data class DipSignal(
        val shouldBuy: Boolean,
        val positionSizeSol: Double,
        val confidence: Int,
        val reason: String,
        val dipQuality: DipQuality,
        val expectedRecoveryPct: Double,
        val dipDepthPct: Double,
    )
    
    enum class DipQuality(val emoji: String, val sizeMult: Double) {
        GOLDEN_DIP("🏆", 1.5),       // Perfect setup
        QUALITY_DIP("✅", 1.2),      // Good setup
        RISKY_DIP("⚠️", 0.8),        // Borderline
        FALLING_KNIFE("🔪", 0.0),   // DON'T TOUCH
        NO_DIP("❌", 0.0),
    }
    
    enum class DipExitSignal {
        RECOVERY_TARGET,
        MAX_RECOVERY,
        STOP_LOSS,
        TIME_EXIT,
        DEATH_SPIRAL,
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
        ErrorLogger.info(TAG, "📉🎯 DIP HUNTER AI initialized (ONE-TIME) | " +
            "mode=${if (paperMode) "PAPER" else "LIVE"} | " +
            "dipRange=${MIN_DIP_PCT.toInt()}-${MAX_DIP_PCT.toInt()}% | " +
            "target=+${TARGET_RECOVERY_PCT.toInt()}%")
    }
    
    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyHunts.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        recentDips.clear()
        ErrorLogger.info(TAG, "📉🎯 DipHunter daily stats reset")
    }
    
    fun isEnabled(): Boolean = true
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        highPrice: Double,           // Recent high (24h or ATH)
        marketCapUsd: Double,
        liquidityUsd: Double,
        buyPressurePct: Double,      // Current buy/sell ratio
        volumeVsAvg: Double,         // Volume relative to average
        tokenAgeHours: Double,
        holderCount: Int,
        holderChange24h: Int,        // Net holder change
        isDevSelling: Boolean,
    ): DipSignal {
        
        // ═══════════════════════════════════════════════════════════════════
        // DAILY LIMITS - CRITICAL FOR BOOTSTRAP PROTECTION
        // ═══════════════════════════════════════════════════════════════════
        
        // Check daily hunt count
        if (dailyHunts.get() >= DAILY_MAX_HUNTS) {
            return noDip("DAILY_LIMIT: ${dailyHunts.get()}/$DAILY_MAX_HUNTS hunts")
        }
        
        // Check daily loss limit
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        if (dailyPnl <= -DAILY_MAX_LOSS_SOL) {
            return noDip("DAILY_LOSS_LIMIT: ${dailyPnl.fmt(3)}◎")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // BASIC FILTERS
        // ═══════════════════════════════════════════════════════════════════
        
        // Max concurrent dips
        if (activeDips.size >= MAX_CONCURRENT_DIPS) {
            return noDip("MAX_DIPS: ${activeDips.size}/$MAX_CONCURRENT_DIPS")
        }
        
        // Already have this dip
        if (activeDips.containsKey(mint)) {
            return noDip("ALREADY_HOLDING")
        }
        
        // Recently dip-bought
        val lastDip = recentDips[mint]
        if (lastDip != null && System.currentTimeMillis() - lastDip < 2 * 60 * 60 * 1000) {
            return noDip("RECENT_DIP: waited < 2h")
        }
        
        // Market cap range
        if (marketCapUsd < MIN_MCAP_USD) {
            return noDip("MCAP_TOO_LOW: \$${(marketCapUsd/1000).toInt()}K")
        }
        if (marketCapUsd > MAX_MCAP_USD) {
            return noDip("MCAP_TOO_HIGH: \$${(marketCapUsd/1_000_000).fmt(1)}M")
        }
        
        // Liquidity check
        if (liquidityUsd < MIN_LIQUIDITY_USD) {
            return noDip("LIQ_TOO_LOW: \$${liquidityUsd.toInt()}")
        }
        
        val liqRatio = liquidityUsd / marketCapUsd
        if (liqRatio < MIN_LIQUIDITY_RATIO) {
            return noDip("LIQ_RATIO_LOW: ${(liqRatio*100).toInt()}%")
        }
        
        // Age check
        if (tokenAgeHours < MIN_TOKEN_AGE_HOURS) {
            return noDip("TOO_YOUNG: ${tokenAgeHours.fmt(1)}h")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // DIP DEPTH ANALYSIS
        // ═══════════════════════════════════════════════════════════════════
        
        // Calculate dip depth
        val dipDepthPct = if (highPrice > 0) {
            ((highPrice - currentPrice) / highPrice) * 100
        } else 0.0
        
        // Check dip is in valid range
        if (dipDepthPct < MIN_DIP_PCT) {
            return noDip("NOT_A_DIP: ${dipDepthPct.fmt(1)}% < ${MIN_DIP_PCT.toInt()}%")
        }
        
        if (dipDepthPct > DANGER_DIP_PCT) {
            return DipSignal(
                shouldBuy = false,
                positionSizeSol = 0.0,
                confidence = 0,
                reason = "FALLING_KNIFE: ${dipDepthPct.fmt(1)}% > ${DANGER_DIP_PCT.toInt()}%",
                dipQuality = DipQuality.FALLING_KNIFE,
                expectedRecoveryPct = 0.0,
                dipDepthPct = dipDepthPct,
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // DANGER SIGN DETECTION
        // ═══════════════════════════════════════════════════════════════════
        
        var dangerScore = 0
        val dangerReasons = mutableListOf<String>()
        
        // Dev selling = major red flag
        if (isDevSelling) {
            dangerScore += 40
            dangerReasons.add("DEV_SELLING")
        }
        
        // Holder exodus
        if (holderChange24h < -10) {
            dangerScore += 20
            dangerReasons.add("HOLDER_EXIT(${holderChange24h})")
        }
        
        // Very low buy pressure during dip
        if (buyPressurePct < 35) {
            dangerScore += 15
            dangerReasons.add("WEAK_BUYS(${buyPressurePct.toInt()}%)")
        }
        
        // Dead volume
        if (volumeVsAvg < 0.3) {
            dangerScore += 10
            dangerReasons.add("LOW_VOL(${(volumeVsAvg*100).toInt()}%)")
        }
        
        // Excessive dip depth
        if (dipDepthPct > MAX_DIP_PCT) {
            dangerScore += 20
            dangerReasons.add("DEEP_DIP(${dipDepthPct.toInt()}%)")
        }
        
        // If too dangerous, reject
        if (dangerScore >= 40) {
            return DipSignal(
                shouldBuy = false,
                positionSizeSol = 0.0,
                confidence = 0,
                reason = "DANGER: ${dangerReasons.joinToString(" ")}",
                dipQuality = DipQuality.FALLING_KNIFE,
                expectedRecoveryPct = 0.0,
                dipDepthPct = dipDepthPct,
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // QUALITY SCORING
        // ═══════════════════════════════════════════════════════════════════
        
        var qualityScore = 50  // Base score
        
        // Dip depth quality (sweet spot is 25-40%)
        qualityScore += when {
            dipDepthPct in 25.0..40.0 -> 20   // Sweet spot
            dipDepthPct in 20.0..45.0 -> 15
            dipDepthPct in 15.0..50.0 -> 10
            else -> 5
        }
        
        // Buy pressure (accumulation signs)
        qualityScore += when {
            buyPressurePct >= 55 -> 15   // Strong accumulation
            buyPressurePct >= 50 -> 10
            buyPressurePct >= 45 -> 5
            buyPressurePct >= 40 -> 0
            else -> -10
        }
        
        // Volume quality (healthy volume during dip)
        qualityScore += when {
            volumeVsAvg >= 2.0 -> 15   // High interest
            volumeVsAvg >= 1.5 -> 10
            volumeVsAvg >= 1.0 -> 5
            volumeVsAvg >= 0.5 -> 0
            else -> -5
        }
        
        // Holder stability
        qualityScore += when {
            holderChange24h >= 10 -> 10   // Growing during dip!
            holderChange24h >= 0 -> 5
            holderChange24h >= -5 -> 0
            else -> -10
        }
        
        // Liquidity ratio bonus
        qualityScore += when {
            liqRatio >= 0.25 -> 10
            liqRatio >= 0.20 -> 7
            liqRatio >= 0.15 -> 5
            else -> 0
        }
        
        // Subtract danger
        qualityScore -= dangerScore
        
        // Calculate confidence
        val confidence = qualityScore.coerceIn(0, 100)
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID THRESHOLD CHECK
        // ═══════════════════════════════════════════════════════════════════
        
        val minConf = getFluidConfidenceThreshold()
        if (confidence < minConf) {
            return noDip("CONFIDENCE_LOW: $confidence% < $minConf%")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // DETERMINE DIP QUALITY
        // ═══════════════════════════════════════════════════════════════════
        
        val dipQuality = when {
            confidence >= 75 && dipDepthPct in 25.0..40.0 && buyPressurePct >= 50 -> DipQuality.GOLDEN_DIP
            confidence >= 60 && dipDepthPct <= 50.0 -> DipQuality.QUALITY_DIP
            confidence >= minConf -> DipQuality.RISKY_DIP
            else -> DipQuality.NO_DIP
        }
        
        if (dipQuality == DipQuality.NO_DIP) {
            return noDip("NO_QUALITY_DIP")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═══════════════════════════════════════════════════════════════════
        
        var positionSol = BASE_POSITION_SOL
        
        // Scale by confidence
        positionSol *= (0.6 + confidence / 100.0 * 0.8)
        
        // Scale by dip quality
        positionSol *= dipQuality.sizeMult
        
        // Cap
        positionSol = positionSol.coerceIn(0.03, MAX_POSITION_SOL)
        
        // Expected recovery
        val expectedRecovery = when (dipQuality) {
            DipQuality.GOLDEN_DIP -> TARGET_RECOVERY_PCT * 1.5
            DipQuality.QUALITY_DIP -> TARGET_RECOVERY_PCT
            DipQuality.RISKY_DIP -> TARGET_RECOVERY_PCT * 0.75
            else -> 0.0
        }
        
        ErrorLogger.info(TAG, "📉🎯 DIP QUALIFIED: $symbol | " +
            "${dipQuality.emoji} ${dipQuality.name} | " +
            "dip=${dipDepthPct.fmt(1)}% conf=$confidence% | " +
            "mcap=\$${(marketCapUsd/1000).toInt()}K | " +
            "size=${positionSol.fmt(4)} SOL | " +
            "target=+${expectedRecovery.toInt()}%")
        
        return DipSignal(
            shouldBuy = true,
            positionSizeSol = positionSol,
            confidence = confidence,
            reason = "${dipQuality.emoji} dip=${dipDepthPct.toInt()}% buy=${buyPressurePct.toInt()}%",
            dipQuality = dipQuality,
            expectedRecoveryPct = expectedRecovery,
            dipDepthPct = dipDepthPct,
        )
    }
    
    private fun noDip(reason: String): DipSignal {
        return DipSignal(
            shouldBuy = false,
            positionSizeSol = 0.0,
            confidence = 0,
            reason = reason,
            dipQuality = DipQuality.NO_DIP,
            expectedRecoveryPct = 0.0,
            dipDepthPct = 0.0,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun openDip(
        mint: String,
        symbol: String,
        entryPrice: Double,
        entrySol: Double,
        highPrice: Double,
        dipDepthPct: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        isPaper: Boolean,
    ) {
        val position = DipPosition(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = entrySol,
            entryTime = System.currentTimeMillis(),
            highPrice = highPrice,
            dipDepthPct = dipDepthPct,
            entryMcap = marketCapUsd,
            entryLiquidity = liquidityUsd,
            isPaper = isPaper,
        )
        
        synchronized(activeDips) {
            activeDips[mint] = position
        }
        dailyHunts.incrementAndGet()
        recentDips[mint] = System.currentTimeMillis()
        
        ErrorLogger.info(TAG, "📉🎯 DIP OPENED: $symbol | " +
            "entry=${entryPrice.fmtPrice()} | " +
            "high=${highPrice.fmtPrice()} | " +
            "dip=${dipDepthPct.fmt(1)}% | " +
            "size=${entrySol.fmt(4)} SOL")
    }
    
    fun checkExit(mint: String, currentPrice: Double, currentLiquidity: Double): DipExitSignal {
        val pos = synchronized(activeDips) { activeDips[mint] } ?: return DipExitSignal.HOLD
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdHours = (System.currentTimeMillis() - pos.entryTime) / (60 * 60 * 1000.0)
        
        // Track best recovery
        if (pnlPct > pos.recoveryHighPct) {
            pos.recoveryHighPct = pnlPct
        }
        
        // ─── EXIT CONDITIONS (FLUID) ───
        val targetRecovery = getFluidRecoveryTarget()
        val stopLoss = getFluidStopLoss()
        
        // 1. MAX RECOVERY (2x the target)
        val maxRecovery = targetRecovery * 2
        if (pnlPct >= maxRecovery) {
            ErrorLogger.info(TAG, "📉🏆 MAX RECOVERY! $mint | +${pnlPct.fmt(1)}% (target was ${targetRecovery.toInt()}%)")
            return DipExitSignal.MAX_RECOVERY
        }
        
        // 2. TARGET RECOVERY - FLUID
        if (pnlPct >= targetRecovery) {
            ErrorLogger.info(TAG, "📉✅ TARGET HIT! $mint | +${pnlPct.fmt(1)}% (fluid target: ${targetRecovery.toInt()}%)")
            return DipExitSignal.RECOVERY_TARGET
        }
        
        // 3. STOP LOSS - FLUID
        if (pnlPct <= stopLoss) {
            ErrorLogger.info(TAG, "📉🛑 STOP LOSS! $mint | ${pnlPct.fmt(1)}% (fluid SL: ${stopLoss.toInt()}%)")
            return DipExitSignal.STOP_LOSS
        }
        
        // 4. DEATH SPIRAL - Liquidity collapsing
        if (currentLiquidity < pos.entryLiquidity * 0.5) {
            ErrorLogger.info(TAG, "📉💀 DEATH SPIRAL! $mint | liq dropped 50%+")
            return DipExitSignal.DEATH_SPIRAL
        }
        
        // 5. TIME EXIT
        if (holdHours >= MAX_HOLD_HOURS) {
            ErrorLogger.info(TAG, "📉⏱ TIME EXIT! $mint | ${pnlPct.fmt(1)}% @ ${holdHours.fmt(1)}h")
            return DipExitSignal.TIME_EXIT
        }
        
        // 6. Protect profits - if recovered 15%+ then dropped back to 5%
        if (pos.recoveryHighPct >= 15 && pnlPct <= 5) {
            ErrorLogger.info(TAG, "📉📉 RECOVERY FADE! $mint | peak=${pos.recoveryHighPct.fmt(1)}% now=${pnlPct.fmt(1)}%")
            return DipExitSignal.RECOVERY_TARGET
        }
        
        return DipExitSignal.HOLD
    }
    
    fun closeDip(mint: String, exitPrice: Double, exitSignal: DipExitSignal) {
        val pos = synchronized(activeDips) { activeDips.remove(mint) } ?: return
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100
        
        // Record P&L
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)
        
        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            dipBalanceBps.addAndGet((pnlSol * 40).toLong())  // 40% compound
        } else {
            dailyLosses.incrementAndGet()
        }
        
        // Record to FluidLearningAI
        try {
            if (pos.isPaper) {
                FluidLearningAI.recordPaperTrade(pnlSol > 0)
            } else {
                FluidLearningAI.recordLiveTrade(pnlSol > 0)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }
        
        ErrorLogger.info(TAG, "📉 DIP CLOSED: ${pos.symbol} | " +
            "${exitSignal.name} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "Daily: ${dailyWins.get()}W/${dailyLosses.get()}L")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.1.2: Lowered bootstrap conf from 65% to 25% + boost system
    private const val DIP_CONF_BOOTSTRAP = 25   // Start lower to allow learning
    private const val DIP_CONF_MATURE = 50      // Build up to 50% as we scale
    private const val DIP_CONF_BOOST_MAX = 10.0 // 10% bootstrap boost (decays as we learn)
    
    /**
     * V4.1.2: Bootstrap confidence boost for DipHunter layer
     * Starts at +10% and decays to 0% as learning progresses
     */
    private fun getBootstrapConfBoost(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return DIP_CONF_BOOST_MAX * (1.0 - progress).coerceIn(0.0, 1.0)
    }
    
    private fun getFluidConfidenceThreshold(): Int {
        val progress = FluidLearningAI.getLearningProgress()
        val baseConf = DIP_CONF_BOOTSTRAP + (DIP_CONF_MATURE - DIP_CONF_BOOTSTRAP) * progress
        val boost = getBootstrapConfBoost()
        return (baseConf + boost).toInt()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getActiveDips(): List<DipPosition> {
        return synchronized(activeDips) { activeDips.values.toList() }
    }
    
    fun hasDip(mint: String): Boolean = activeDips.containsKey(mint)
    
    fun getDailyPnlSol(): Double = dailyPnlSolBps.get() / 100.0
    
    data class DipStats(
        val dailyHunts: Int,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyPnlSol: Double,
        val activeDips: Int,
        val winRate: Double,
    )
    
    fun getStats(): DipStats {
        val totalTrades = dailyWins.get() + dailyLosses.get()
        return DipStats(
            dailyHunts = dailyHunts.get(),
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyPnlSol = dailyPnlSolBps.get() / 100.0,
            activeDips = activeDips.size,
            winRate = if (totalTrades > 0) dailyWins.get().toDouble() / totalTrades * 100 else 0.0,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
    private fun Double.fmtPrice(): String = if (this < 0.01) String.format("%.8f", this) else String.format("%.6f", this)
}
