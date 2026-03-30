package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.AICrossTalk
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * MOONSHOT TRADER AI - "TO THE MOON" v2.0 (Space Theme Edition)
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The 10x, 100x, 1000x HUNTER! This is where BIG WINS live.
 * 
 * SPACE-THEMED TRADING MODES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🛸 ORBITAL   - Early moonshots ($100K-$500K) - Catch them before liftoff
 *   🌙 LUNAR     - Mid moonshots ($500K-$2M) - Building momentum
 *   🔴 MARS      - High conviction ($2M-$5M) - Strong fundamentals + hype
 *   🪐 JUPITER   - Mega plays ($5M-$50M) - Collective winners promoted here
 * 
 * CROSS-TRADING PATHWAY:
 * ─────────────────────────────────────────────────────────────────────────────
 * When a position in another layer (Treasury, ShitCoin, BlueChip) hits +200%+,
 * it can be PROMOTED to Moonshot to let it ride with wider targets!
 * 
 * KEY PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Asymmetric Risk/Reward: Risk small, win HUGE
 * 2. Let Winners Ride: Trailing stops, NO hard TP caps
 * 3. Collective Intelligence: Learn from network's 10x+ trades
 * 4. Fluid Learning: Adapts based on outcomes
 * 5. NO ARTIFICIAL PUMPING: We detect and avoid pump schemes
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object MoonshotTraderAI {
    
    private const val TAG = "MoonshotAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SPACE MODE CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class SpaceMode(
        val emoji: String,
        val displayName: String,
        val minMcap: Double,
        val maxMcap: Double,
        val baseTP: Double,
        val baseSL: Double,
        val maxHold: Int,
        val description: String,
    ) {
        ORBITAL("🛸", "Orbital", 100_000.0, 500_000.0, 100.0, -10.0, 45, "Early launch detection"),
        LUNAR("🌙", "Lunar", 500_000.0, 2_000_000.0, 200.0, -12.0, 60, "Building momentum"),
        MARS("🔴", "Mars", 2_000_000.0, 5_000_000.0, 500.0, -15.0, 120, "High conviction plays"),
        JUPITER("🪐", "Jupiter", 5_000_000.0, 50_000_000.0, 1000.0, -20.0, 240, "Mega collective winners"),
    }
    
    // Market cap boundaries
    private const val MIN_MARKET_CAP_USD = 100_000.0     // $100K minimum
    private const val MAX_MARKET_CAP_USD = 50_000_000.0  // $50M maximum (Jupiter mode)
    
    // Liquidity requirements
    private const val MIN_LIQUIDITY_USD_BOOTSTRAP = 15_000.0
    private const val MIN_LIQUIDITY_USD_MATURE = 10_000.0
    
    // Position sizing - moderate but aggressive
    private const val BASE_POSITION_SOL = 0.08
    private const val MAX_POSITION_SOL = 0.40
    private const val MAX_CONCURRENT_POSITIONS = 6
    
    // Cross-trade promotion threshold
    private const val CROSS_TRADE_PROMOTION_PCT = 200.0  // 200%+ gain = promote to Moonshot
    
    // Trailing stop configuration per mode
    private const val TRAILING_STOP_ORBITAL = 15.0
    private const val TRAILING_STOP_LUNAR = 12.0
    private const val TRAILING_STOP_MARS = 10.0
    private const val TRAILING_STOP_JUPITER = 8.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile var isPaperMode: Boolean = true
    private val isEnabled = AtomicBoolean(true)
    
    // Daily tracking
    private val dailyPnlSolBps = AtomicLong(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val dailyTradeCount = AtomicInteger(0)
    private val dailyTenXCount = AtomicInteger(0)
    private val dailyHundredXCount = AtomicInteger(0)
    
    // Lifetime milestones
    private val lifetimeTenX = AtomicInteger(0)
    private val lifetimeHundredX = AtomicInteger(0)
    private val lifetimeThousandX = AtomicInteger(0)
    
    // Balances
    private val paperBalanceBps = AtomicLong(200_0000)  // 2 SOL paper for moonshots
    private val liveBalanceBps = AtomicLong(0)
    
    // Fluid learning progress (0-100%)
    private var learningProgress = 0.0
    
    // Active positions
    private val activePositions = ConcurrentHashMap<String, MoonshotPosition>()
    private val livePositions = ConcurrentHashMap<String, MoonshotPosition>()
    private val paperPositions = ConcurrentHashMap<String, MoonshotPosition>()
    
    // Cross-trade promotions waiting list
    private val promotionCandidates = ConcurrentHashMap<String, PromotionCandidate>()
    
    // Collective intelligence - tokens that hit 10x+ across the network
    private val collectiveWinners = ConcurrentHashMap<String, CollectiveWinner>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
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
        var spaceMode: SpaceMode,
        val isPaperMode: Boolean,
        var highWaterMark: Double = entryPrice,
        var trailingStop: Double = entryPrice * 0.85,
        var firstTakeDone: Boolean = false,
        var partialSellPct: Double = 0.0,
        var promotedFrom: String? = null,  // Which layer it was promoted from
        var peakPnlPct: Double = 0.0,
        var isCollectiveWinner: Boolean = false,
    )
    
    data class PromotionCandidate(
        val mint: String,
        val symbol: String,
        val fromLayer: String,
        val currentPnlPct: Double,
        val currentPrice: Double,
        val marketCapUsd: Double,
        val timestamp: Long,
    )
    
    data class CollectiveWinner(
        val mint: String,
        val symbol: String,
        val peakGainPct: Double,
        val networkTraders: Int,
        val avgEntryMcap: Double,
        val lastUpdate: Long,
        val confidence: Double,
    )
    
    enum class ExitSignal {
        HOLD,
        STOP_LOSS,
        TAKE_PROFIT,
        TRAILING_STOP,
        PARTIAL_TAKE,
        FLAT_EXIT,
        RUG_DETECTED,
        TIMEOUT,
        MODE_UPGRADE,    // Upgrade to higher space mode
    }
    
    data class MoonshotScore(
        val eligible: Boolean,
        val score: Int,
        val confidence: Double,
        val rejectReason: String = "",
        val suggestedSizeSol: Double = 0.0,
        val takeProfitPct: Double = 0.0,
        val stopLossPct: Double = 0.0,
        val spaceMode: SpaceMode = SpaceMode.ORBITAL,
        val isCollectiveBoost: Boolean = false,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize(isPaper: Boolean = true) {
        isPaperMode = isPaper
        ErrorLogger.info(TAG, "🚀 Moonshot initialized | mode=${if (isPaper) "PAPER" else "LIVE"} | " +
            "Space modes: ${SpaceMode.values().joinToString(" ") { it.emoji }}")
    }
    
    fun isEnabled(): Boolean = isEnabled.get()
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "🚀 Moonshot ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyTradeCount.set(0)
        dailyTenXCount.set(0)
        dailyHundredXCount.set(0)
        ErrorLogger.info(TAG, "🚀 Daily stats reset")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SPACE MODE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun detectSpaceMode(marketCapUsd: Double, isCollectiveWinner: Boolean = false): SpaceMode {
        // Collective winners get JUPITER mode regardless of mcap
        if (isCollectiveWinner && marketCapUsd >= 5_000_000.0) {
            return SpaceMode.JUPITER
        }
        
        return when {
            marketCapUsd >= 5_000_000.0 -> SpaceMode.JUPITER
            marketCapUsd >= 2_000_000.0 -> SpaceMode.MARS
            marketCapUsd >= 500_000.0 -> SpaceMode.LUNAR
            else -> SpaceMode.ORBITAL
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING - The core moonshot detection logic
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
        
        if (!isEnabled.get()) {
            return MoonshotScore(false, 0, 0.0, "moonshot_disabled")
        }
        
        // 1. Market cap filter - Moonshot zone
        if (marketCapUsd < MIN_MARKET_CAP_USD) {
            return MoonshotScore(false, 0, 0.0, "mcap_too_low_${(marketCapUsd/1000).toInt()}K_min_100K")
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
            return MoonshotScore(false, 0, 0.0, "max_${MAX_CONCURRENT_POSITIONS}_positions")
        }
        
        // 4. Already have position
        if (hasPosition(mint)) {
            return MoonshotScore(false, 0, 0.0, "already_have_position")
        }
        
        // 5. Safety check - slightly relaxed for moonshots but still filter rugs
        if (rugcheckScore < 20) {
            return MoonshotScore(false, 0, 0.0, "rugcheck_${rugcheckScore}_dangerous")
        }
        
        // ─── DETECT IF COLLECTIVE WINNER ───
        val isCollective = collectiveWinners.containsKey(mint)
        val collectiveBonus = if (isCollective) {
            val winner = collectiveWinners[mint]!!
            (winner.confidence * 20).toInt()  // Up to +20 bonus from collective
        } else 0
        
        // ─── SCORING ───
        var score = 0
        
        // Market cap bonus - sweet spots by mode
        val mode = detectSpaceMode(marketCapUsd, isCollective)
        val mcapScore = when (mode) {
            SpaceMode.ORBITAL -> if (marketCapUsd in 150_000.0..400_000.0) 25 else 18
            SpaceMode.LUNAR -> if (marketCapUsd in 700_000.0..1_500_000.0) 22 else 15
            SpaceMode.MARS -> if (marketCapUsd in 2_500_000.0..4_000_000.0) 20 else 12
            SpaceMode.JUPITER -> 25  // Always good if collective
        }
        score += mcapScore
        
        // Liquidity bonus - higher is better for moonshots
        val liqScore = when {
            liquidityUsd > 200_000 -> 20
            liquidityUsd > 100_000 -> 16
            liquidityUsd > 50_000 -> 12
            liquidityUsd > 25_000 -> 8
            else -> 5
        }
        score += liqScore
        
        // Volume momentum - CRITICAL for moonshots
        val volScore = when {
            volumeScore > 40 -> 20
            volumeScore > 30 -> 16
            volumeScore > 20 -> 12
            volumeScore > 10 -> 8
            else -> 0
        }
        score += volScore
        
        // Buy pressure - want strong buying
        val buyScore = when {
            buyPressurePct > 75 -> 18
            buyPressurePct > 65 -> 14
            buyPressurePct > 55 -> 10
            buyPressurePct > 50 -> 6
            else -> 0
        }
        score += buyScore
        
        // V3 score integration (use V3's intelligence)
        val v3Score = when {
            v3EntryScore > 90 -> 15
            v3EntryScore > 80 -> 12
            v3EntryScore > 70 -> 8
            v3EntryScore > 60 -> 5
            else -> 0
        }
        score += v3Score
        
        // Phase bonus - accumulation and breakout are best
        val phaseScore = when {
            phase.contains("breakout", ignoreCase = true) -> 15
            phase.contains("accumulation", ignoreCase = true) -> 12
            phase.contains("early", ignoreCase = true) -> 10
            phase.contains("pump", ignoreCase = true) -> 8
            else -> 5
        }
        score += phaseScore
        
        // Collective intelligence bonus
        score += collectiveBonus
        
        // Minimum threshold (lower during bootstrap)
        val minScore = when {
            learningProgress < 0.1 -> 50   // Very permissive at start
            learningProgress < 0.3 -> 55
            learningProgress < 0.5 -> 60
            else -> 65
        }
        
        if (score < minScore) {
            return MoonshotScore(false, score, 0.0, "score_${score}_below_${minScore}")
        }
        
        // Calculate confidence
        val confidence = min(100.0, (score.toDouble() / 120) * 100 + (v3Confidence * 0.2))
        
        // Position sizing based on score and mode
        val baseSize = when (mode) {
            SpaceMode.ORBITAL -> BASE_POSITION_SOL
            SpaceMode.LUNAR -> BASE_POSITION_SOL * 1.25
            SpaceMode.MARS -> BASE_POSITION_SOL * 1.5
            SpaceMode.JUPITER -> BASE_POSITION_SOL * 2.0
        }
        
        val sizeSol = when {
            score > 100 -> min(baseSize * 2.5, MAX_POSITION_SOL)
            score > 85 -> min(baseSize * 2.0, MAX_POSITION_SOL)
            score > 70 -> min(baseSize * 1.5, MAX_POSITION_SOL)
            else -> baseSize
        }
        
        return MoonshotScore(
            eligible = true,
            score = score,
            confidence = confidence,
            suggestedSizeSol = sizeSol,
            takeProfitPct = mode.baseTP,
            stopLossPct = mode.baseSL,
            spaceMode = mode,
            isCollectiveBoost = isCollective,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CROSS-TRADE PROMOTION - The path from other layers to Moonshot
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Called by other layers when a position hits +200%+
     * Returns true if we should promote to Moonshot
     */
    fun shouldPromoteToMoonshot(
        mint: String,
        symbol: String,
        fromLayer: String,
        currentPnlPct: Double,
        currentPrice: Double,
        marketCapUsd: Double,
    ): Boolean {
        // Check if eligible for promotion
        if (currentPnlPct < CROSS_TRADE_PROMOTION_PCT) {
            return false
        }
        
        // Check if within moonshot mcap range
        if (marketCapUsd < MIN_MARKET_CAP_USD || marketCapUsd > MAX_MARKET_CAP_USD) {
            return false
        }
        
        // Check position limits
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            return false
        }
        
        // Register as candidate
        promotionCandidates[mint] = PromotionCandidate(
            mint = mint,
            symbol = symbol,
            fromLayer = fromLayer,
            currentPnlPct = currentPnlPct,
            currentPrice = currentPrice,
            marketCapUsd = marketCapUsd,
            timestamp = System.currentTimeMillis(),
        )
        
        ErrorLogger.info(TAG, "🚀 PROMOTION CANDIDATE: $symbol from $fromLayer | " +
            "+${currentPnlPct.toInt()}% | \$${(marketCapUsd/1000).toInt()}K mcap")
        
        return true
    }
    
    /**
     * Execute the cross-trade promotion
     * Called after the original layer closes its position
     */
    fun executePromotion(
        mint: String,
        symbol: String,
        fromLayer: String,
        entryPrice: Double,
        positionSol: Double,
        currentPnlPct: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        isPaper: Boolean,
    ): Boolean {
        if (!isEnabled.get()) return false
        
        val mode = detectSpaceMode(marketCapUsd)
        
        val position = MoonshotPosition(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = positionSol,
            entryTime = System.currentTimeMillis(),
            takeProfitPct = mode.baseTP,
            stopLossPct = mode.baseSL,
            marketCapUsd = marketCapUsd,
            liquidityUsd = liquidityUsd,
            entryScore = 100.0,  // Already proven winner
            spaceMode = mode,
            isPaperMode = isPaper,
            promotedFrom = fromLayer,
            peakPnlPct = currentPnlPct,
        )
        
        addPosition(position)
        
        // Notify AI CrossTalk about the promotion
        try {
            AICrossTalk.recordOutcome(
                signalType = AICrossTalk.SignalType.MODE_SWITCH_RECOMMENDED,
                pnlPct = currentPnlPct,
                wasProfit = true
            )
        } catch (_: Exception) { }
        
        promotionCandidates.remove(mint)
        
        ErrorLogger.info(TAG, "🚀✨ PROMOTED TO MOONSHOT: $symbol from $fromLayer | " +
            "${mode.emoji} ${mode.displayName} | +${currentPnlPct.toInt()}% at promotion | " +
            "Let it RIDE!")
        
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECTIVE LEARNING INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Called when a 10x+ trade is reported from the collective network
     */
    fun recordCollectiveWinner(
        mint: String,
        symbol: String,
        peakGainPct: Double,
        networkTraders: Int,
        avgEntryMcap: Double,
        confidence: Double,
    ) {
        collectiveWinners[mint] = CollectiveWinner(
            mint = mint,
            symbol = symbol,
            peakGainPct = peakGainPct,
            networkTraders = networkTraders,
            avgEntryMcap = avgEntryMcap,
            lastUpdate = System.currentTimeMillis(),
            confidence = confidence,
        )
        
        ErrorLogger.info(TAG, "🪐 COLLECTIVE WINNER: $symbol | " +
            "+${peakGainPct.toInt()}% peak | $networkTraders traders | " +
            "conf=${(confidence * 100).toInt()}%")
    }
    
    /**
     * Check if a token is a collective winner
     */
    fun isCollectiveWinner(mint: String): Boolean = collectiveWinners.containsKey(mint)
    
    /**
     * Clean old collective winners (older than 24h)
     */
    fun cleanCollectiveWinners() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        collectiveWinners.entries.removeIf { it.value.lastUpdate < cutoff }
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
        
        val promoLabel = if (position.promotedFrom != null) "[PROMOTED from ${position.promotedFrom}] " else ""
        
        ErrorLogger.info(TAG, "🚀 ${position.spaceMode.emoji} MOONSHOT ENTRY: ${position.symbol} | $promoLabel" +
            "${position.spaceMode.displayName} | " +
            "mcap=\$${(position.marketCapUsd/1_000).toInt()}K | " +
            "size=${position.entrySol.fmt(3)} SOL | " +
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
        
        // Check for milestone achievements
        when {
            pnlPct >= 99900 -> {  // 1000x
                lifetimeThousandX.incrementAndGet()
                lifetimeHundredX.incrementAndGet()
                lifetimeTenX.incrementAndGet()
                ErrorLogger.info(TAG, "🌌🌌🌌 1000X MOONSHOT!!! ${pos.symbol} +${pnlPct.toInt()}%")
            }
            pnlPct >= 9900 -> {   // 100x
                lifetimeHundredX.incrementAndGet()
                lifetimeTenX.incrementAndGet()
                dailyHundredXCount.incrementAndGet()
                ErrorLogger.info(TAG, "🪐🪐 100X ACHIEVED! ${pos.symbol} +${pnlPct.toInt()}%")
            }
            pnlPct >= 900 -> {    // 10x
                lifetimeTenX.incrementAndGet()
                dailyTenXCount.incrementAndGet()
                ErrorLogger.info(TAG, "🚀 10X WIN! ${pos.symbol} +${pnlPct.toInt()}%")
            }
        }
        
        // Update balance
        val balanceRef = if (pos.isPaperMode) paperBalanceBps else liveBalanceBps
        balanceRef.addAndGet((pnlSol * 10000).toLong())
        
        // Update learning progress
        updateLearning(pnlPct, isWin)
        
        val emoji = when {
            pnlPct >= 900 -> "🌌"
            pnlPct >= 100 -> "🚀"
            pnlPct >= 50 -> "🌙"
            pnlPct > 0 -> "✨"
            else -> "💫"
        }
        
        ErrorLogger.info(TAG, "$emoji ${pos.spaceMode.emoji} MOONSHOT CLOSED [${if (pos.isPaperMode) "PAPER" else "LIVE"}]: " +
            "${pos.symbol} | P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL " +
            "(${if (pnlPct >= 0) "+" else ""}${pnlPct.fmt(1)}%) | " +
            "reason=$exitReason | Win rate: ${getWinRatePct()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECKING - LET WINNERS RIDE!
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        val pos = synchronized(activePositions) { activePositions[mint] } ?: return ExitSignal.HOLD
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // Update peak P&L
        if (pnlPct > pos.peakPnlPct) {
            pos.peakPnlPct = pnlPct
        }
        
        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            
            // Dynamic trailing based on gains and mode
            val dynamicTrailPct = when {
                pnlPct >= 1000 -> 5.0   // 10x+ → ultra-tight 5% trail
                pnlPct >= 500 -> 6.0    // 5x+ → tight 6% trail
                pnlPct >= 200 -> 8.0    // 3x+ → 8% trail
                pnlPct >= 100 -> 10.0   // 2x+ → 10% trail
                else -> when (pos.spaceMode) {
                    SpaceMode.ORBITAL -> TRAILING_STOP_ORBITAL
                    SpaceMode.LUNAR -> TRAILING_STOP_LUNAR
                    SpaceMode.MARS -> TRAILING_STOP_MARS
                    SpaceMode.JUPITER -> TRAILING_STOP_JUPITER
                }
            }
            pos.trailingStop = currentPrice * (1 - dynamicTrailPct / 100)
        }
        
        // Check for space mode upgrade
        val newMode = detectSpaceMode(pos.marketCapUsd * (1 + pnlPct / 100))
        if (newMode.ordinal > pos.spaceMode.ordinal) {
            ErrorLogger.info(TAG, "🚀⬆️ MODE UPGRADE: ${pos.symbol} | " +
                "${pos.spaceMode.emoji} → ${newMode.emoji} | +${pnlPct.toInt()}%")
            pos.spaceMode = newMode
            pos.takeProfitPct = newMode.baseTP
            pos.stopLossPct = newMode.baseSL
        }
        
        // ─── EXIT CONDITIONS ───
        
        // 1. RUG DETECTED - massive sudden drop
        if (holdMinutes < 15 && pnlPct < -40) {
            ErrorLogger.warn(TAG, "⚠️ RUG DETECTED: ${pos.symbol} | ${pnlPct.toInt()}% in ${holdMinutes}min")
            return ExitSignal.RUG_DETECTED
        }
        
        // 2. STOP LOSS HIT
        if (pnlPct <= pos.stopLossPct) {
            ErrorLogger.info(TAG, "🛑 SL HIT: ${pos.symbol} | ${pnlPct.fmt(1)}%")
            return ExitSignal.STOP_LOSS
        }
        
        // 3. PARTIAL TAKE at first target - then let rest ride!
        if (!pos.firstTakeDone && pnlPct >= min(pos.takeProfitPct / 2, 75.0)) {
            pos.firstTakeDone = true
            ErrorLogger.info(TAG, "💰 PARTIAL TP: ${pos.symbol} | +${pnlPct.fmt(1)}% - LETTING REST RIDE!")
            return ExitSignal.PARTIAL_TAKE
        }
        
        // 4. TRAILING STOP - locks in gains while letting it run
        if (pnlPct > 30.0 && currentPrice <= pos.trailingStop) {
            ErrorLogger.info(TAG, "🎯 TRAIL EXIT: ${pos.symbol} | +${pnlPct.fmt(1)}% | Peak was +${pos.peakPnlPct.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. FLAT EXIT - nothing happening after reasonable time
        val flatExitMins = pos.spaceMode.maxHold / 3
        if (holdMinutes >= flatExitMins && pnlPct > -5.0 && pnlPct < 20.0) {
            ErrorLogger.info(TAG, "😐 FLAT EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.FLAT_EXIT
        }
        
        // 6. TIMEOUT - only if not significantly profitable
        if (holdMinutes >= pos.spaceMode.maxHold && pnlPct < 50.0) {
            ErrorLogger.info(TAG, "⏰ TIMEOUT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.TIMEOUT
        }
        
        // Otherwise, HOLD - let it ride!
        return ExitSignal.HOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateLearning(pnlPct: Double, isWin: Boolean) {
        val increment = when {
            pnlPct >= 900 -> 0.05    // 10x = BIG learning boost
            pnlPct >= 200 -> 0.03    // 3x+ = good learning
            pnlPct >= 100 -> 0.02    // 2x+ = moderate learning
            isWin -> 0.01
            pnlPct < -30 -> 0.02     // Big loss = learning opportunity
            else -> 0.005
        }
        learningProgress = min(1.0, learningProgress + increment)
    }
    
    fun getLearningProgress(): Double = learningProgress
    
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
    fun getDailyTenX(): Int = dailyTenXCount.get()
    fun getDailyHundredX(): Int = dailyHundredXCount.get()
    fun getLifetimeTenX(): Int = lifetimeTenX.get()
    fun getLifetimeHundredX(): Int = lifetimeHundredX.get()
    fun getLifetimeThousandX(): Int = lifetimeThousandX.get()
    
    fun getBalance(isPaper: Boolean): Double {
        return (if (isPaper) paperBalanceBps.get() else liveBalanceBps.get()) / 10000.0
    }
    
    fun getPositionCount(): Int = activePositions.size
    
    fun getCurrentTakeProfit(): Double = SpaceMode.LUNAR.baseTP
    fun getCurrentStopLoss(): Double = SpaceMode.LUNAR.baseSL
    
    fun getSpaceModeStats(): Map<SpaceMode, Int> {
        return activePositions.values.groupingBy { it.spaceMode }.eachCount()
    }
    
    // Helper extension
    private fun Double.fmt(decimals: Int) = String.format("%.${decimals}f", this)
}
