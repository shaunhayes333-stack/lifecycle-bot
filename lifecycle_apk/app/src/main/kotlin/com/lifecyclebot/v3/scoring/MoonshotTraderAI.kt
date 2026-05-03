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
 *   🛸 ORBITAL   - Early moonshots ($5K-$100K) - Catch them before liftoff
 *   🌙 LUNAR     - Mid moonshots ($100K-$500k) - Building momentum
 *   🔴 MARS      - High conviction ($500k-$2M) - Strong fundamentals + hype
 *   🪐 JUPITER   - Mega plays ($2M-$50M) - Collective winners promoted here
 * 
 * CROSS-TRADING PATHWAY:
 * ─────────────────────────────────────────────────────────────────────────────
 * When a position in another layer (Treasury, ShitCoin, BlueChip) hits +100%+,
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
        ORBITAL("🛸", "Orbital", 50_000.0, 100_000.0, 50.0, -10.0, 45, "Early launch detection"),
        LUNAR("🌙", "Lunar", 100_000.0, 500_000.0, 200.0, -12.0, 60, "Building momentum"),
        MARS("🔴", "Mars", 500_000.0, 2_000_000.0, 500.0, -15.0, 120, "High conviction plays"),
        JUPITER("🪐", "Jupiter", 2_000_000.0, 50_000_000.0, 1000.0, -20.0, 240, "Mega collective winners"),
    }
    
    // V5.2.12: Moonshot is a CROSS-LAYER promotion for massive gains
    // Any token from any layer can promote to Moonshot when gains hit threshold
    // Market cap boundaries are wide to accept promotions from all layers
    private const val MIN_MARKET_CAP_USD = 10_000.0      // $10K minimum (can come from ShitCoin)
    private const val MAX_MARKET_CAP_USD = 100_000_000.0 // $100M maximum (allow Jupiter plays)
    
    // Liquidity requirements - flexible since these are promoted positions
    // V5.9.159 — bootstrap liquidity floor lowered. At 1% learning a $5K floor
    // was excluding most sub-$50K-mcap fresh pump.fun launches which are
    // exactly the volume we want the scorer to learn from.
    private const val MIN_LIQUIDITY_USD_BOOTSTRAP = 2_000.0    // V5.9.343: walk-back to pre-V5.9.341
    private const val MIN_LIQUIDITY_USD_MATURE = 15_000.0     // Higher when mature
    
    // Position sizing - moderate but aggressive
    private const val BASE_POSITION_SOL = 0.08
    private const val MAX_POSITION_SOL = 0.40
    private const val MAX_CONCURRENT_POSITIONS = 10  // V5.2.12: Raised for paper learning
    // V5.9.316: REVERT V5.9.218 cap reduction. Restored bootstrap cap of 30
    // (build #1941 era) so the trader can fan out across many mcap bands /
    // modes concurrently during bootstrap learning. The 8-slot V5.9.218
    // "quality gate" was strangling the moonshot discovery surface.
    private const val MAX_CONCURRENT_POSITIONS_BOOTSTRAP = 30
    private fun effectiveMaxPositions(): Int = try {
        if (FluidLearningAI.getLearningProgress() < 0.40) MAX_CONCURRENT_POSITIONS_BOOTSTRAP
        else MAX_CONCURRENT_POSITIONS
    } catch (_: Exception) { MAX_CONCURRENT_POSITIONS }
    
    // Cross-trade promotion threshold
    private const val CROSS_TRADE_PROMOTION_PCT = 200.0  // 200%+ gain = promote to Moonshot
    
    // V5.9.316: REMOVED V5.9.235 base44 hard gates. The 55% buy-pressure /
    // 5 volume / tilt-protection rejects were filtering out the very tokens
    // that produced 500%+ trades in build #1941 — early pump.fun launches
    // with sparse buy-pressure data and lopsided volume profiles. Soft
    // scoring still penalises weak signals; FluidLearningAI gates per-trader.
    private const val HARD_FLOOR_STOP = -20.0        // V5.9.316: -15→-20 (build #1941 era)
    private const val EARLY_DEAD_EXIT_MINUTES = 20   // Dead exit window (mirrors ShitCoin's 12min)
    private const val EARLY_DEAD_EXIT_THRESHOLD = -6.0 // Dead at <-6% within early window

    // Trailing stop configuration per mode
    private const val TRAILING_STOP_ORBITAL = 15.0
    private const val TRAILING_STOP_LUNAR = 12.0
    private const val TRAILING_STOP_MARS = 10.0
    private const val TRAILING_STOP_JUPITER = 8.0

    // V5.9.163 — PARTIAL_TAKE_LADDER: each rung fires ONE partial sell when
    // pnlPct crosses it. Matches user ladder "+20 +50 +100 +300 +1000 +3000"
    // (we include +20 and +50 as early de-risk rungs — users explicitly
    // asked for partial sells on the way up starting at the first meaningful
    // gain). The executor (checkPartialSell path) decides the actual sell
    // fraction per rung — Moonshot just signals PARTIAL_TAKE so the chain
    // fires one milestone at a time.
    private val PARTIAL_TAKE_LADDER = doubleArrayOf(
        20.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0
    )
    
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
        // V5.9.163 — which ladder rungs have already taken a partial.
        // Each index corresponds to PARTIAL_TAKE_LADDER (see checkExit).
        // Enables multi-stage partial sells on moonshots instead of
        // one-and-done firstTakeDone behaviour.
        var partialRungsTaken: Int = 0,
        var promotedFrom: String? = null,  // Which layer it was promoted from
        var peakPnlPct: Double = 0.0,
        var isCollectiveWinner: Boolean = false,
        // V5.9.392 — latest price seen by checkExit(). Lets the unified
        // open-positions UI render live P&L for Moonshot holdings that
        // live only in paperPositions (no status.tokens entry).
        var lastSeenPrice: Double = entryPrice,
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
    // PERSISTENCE - V5.6.29c: Save/restore learning state across app restarts
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L  // Max once per 10 seconds
    
    /**
     * Initialize persistence - call from BotService.onCreate()
     */
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("moonshot_trader_ai", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "🚀 MoonshotTraderAI persistence initialized")
    }
    
    /**
     * Restore state from SharedPreferences
     */
    private fun restore() {
        val p = prefs ?: return
        
        // Lifetime milestones
        lifetimeTenX.set(p.getInt("lifetimeTenX", 0))
        lifetimeHundredX.set(p.getInt("lifetimeHundredX", 0))
        lifetimeThousandX.set(p.getInt("lifetimeThousandX", 0))
        
        // Balances
        paperBalanceBps.set(p.getLong("paperBalanceBps", 200_0000))
        liveBalanceBps.set(p.getLong("liveBalanceBps", 0))
        
        // Learning progress
        learningProgress = p.getFloat("learningProgress", 0f).toDouble()
        
        // Daily stats (only restore if same day)
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyPnlSolBps.set(p.getLong("dailyPnlSolBps", 0))
            dailyWins.set(p.getInt("dailyWins", 0))
            dailyLosses.set(p.getInt("dailyLosses", 0))
            dailyTradeCount.set(p.getInt("dailyTradeCount", 0))
            dailyTenXCount.set(p.getInt("dailyTenXCount", 0))
            dailyHundredXCount.set(p.getInt("dailyHundredXCount", 0))
        }
        
        ErrorLogger.info(TAG, "🚀 RESTORED: lifetime10x=${lifetimeTenX.get()} 100x=${lifetimeHundredX.get()} " +
            "1000x=${lifetimeThousandX.get()} | paperBal=${paperBalanceBps.get()/10000.0} SOL | " +
            "learning=${(learningProgress * 100).toInt()}%")
    }
    
    /**
     * Save state to SharedPreferences (throttled to prevent ANR)
     */
    fun save(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        
        // Throttle saves unless forced
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        
        p.edit().apply {
            // Lifetime milestones
            putInt("lifetimeTenX", lifetimeTenX.get())
            putInt("lifetimeHundredX", lifetimeHundredX.get())
            putInt("lifetimeThousandX", lifetimeThousandX.get())
            
            // Balances
            putLong("paperBalanceBps", paperBalanceBps.get())
            putLong("liveBalanceBps", liveBalanceBps.get())
            
            // Learning progress
            putFloat("learningProgress", learningProgress.toFloat())
            
            // Daily stats with day marker
            putLong("savedDay", now / (24 * 60 * 60 * 1000))
            putLong("dailyPnlSolBps", dailyPnlSolBps.get())
            putInt("dailyWins", dailyWins.get())
            putInt("dailyLosses", dailyLosses.get())
            putInt("dailyTradeCount", dailyTradeCount.get())
            putInt("dailyTenXCount", dailyTenXCount.get())
            putInt("dailyHundredXCount", dailyHundredXCount.get())
            
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize(isPaper: Boolean = true) {
        isPaperMode = isPaper
        ErrorLogger.info(TAG, "🚀 Moonshot initialized | mode=${if (isPaper) "PAPER" else "LIVE"} | " +
            "Space modes: ${SpaceMode.values().joinToString(" ") { it.emoji }}")
    }
    
    /**
     * V5.6.11: Set trading mode and transfer learning from paper to live
     */
    fun setTradingMode(isPaper: Boolean) {
        val wasInPaper = isPaperMode
        isPaperMode = isPaper
        
        // V5.9.306: BUG FIX — DO NOT TRANSFER PAPER BALANCE TO LIVE.
        // Phantom paper SOL would inflate the live balance counter without any
        // corresponding on-chain SOL. Live balance must ONLY grow from real trades.
        if (!isPaper && wasInPaper) {
            val paperBal = paperBalanceBps.get() / 10000.0
            ErrorLogger.info(TAG, "🚀 PAPER→LIVE: paper balance ${paperBal.fmt(4)} SOL retained in PAPER only (NOT copied to LIVE)")
        }
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
            marketCapUsd >= 2_000_000.0 -> SpaceMode.JUPITER
            marketCapUsd >= 500_000.0 -> SpaceMode.MARS
            marketCapUsd >= 100_000.0 -> SpaceMode.LUNAR
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
        val maxPos = effectiveMaxPositions()
        if (activePositions.size >= maxPos) {
            return MoonshotScore(false, 0, 0.0, "max_${maxPos}_positions")
        }
        
        // 4. Already have position
        if (hasPosition(mint)) {
            return MoonshotScore(false, 0, 0.0, "already_have_position")
        }
        
        // 5. Safety check - fluid RC threshold
        // V5.9.404 — restored build #1941 era leniency. Was 10/20 (V5.5+).
        // Brand-new pump.fun launches frequently have rugcheckScore=0 in the
        // first minute; the 10-paper floor was rejecting the very tokens
        // that produced 500%+ trades. Soft scoring + on-chain holders/lp
        // checks downstream still filter true rugs.
        val minRcScore = if (isPaper) 5 else 15
        if (rugcheckScore < minRcScore) {
            return MoonshotScore(false, 0, 0.0, "rugcheck_${rugcheckScore}_below_min_${minRcScore}")
        }

        // V5.9.316: REVERTED V5.9.235 base44 HARD ENTRY GATES — they were
        // strangling moonshot entries. Build #1941 era found 500% trades by
        // letting fresh launches with sparse data through to the scorer,
        // which weighs buy pressure / volume as SOFT signals (not hard cuts).
        // Soft-scoring branches downstream still penalise weak setups.
        // ── END HARD GATES (NOW REMOVED) ────────────────────────────────────────
        
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

        // V5.9.404 — SYMBOLIC LAYER: NarrativeAI + CultMomentumAI.
        // Memecoins are linguistic objects — the symbol *is* the alpha.
        // We add a deterministic bonus when the symbol/name maps to a known
        // memetic cluster (frog/dog/cat/political/AI-theme/cult/rocket/etc),
        // and a further bonus when that cluster is "alive" (≥2 fires in the
        // last hour). These are additive and never subtract — the bot leans
        // into running narratives but a non-meme symbol still scores normally.
        val narrative = try { MemeNarrativeAI.detect(symbol = symbol, name = symbol) } catch (_: Throwable) { null }
        val narrativeBonus = narrative?.baseBonus ?: 0
        val cultBonus = try { CultMomentumAI.bonusFor(narrative?.cluster ?: MemeNarrativeAI.Cluster.UNKNOWN) } catch (_: Throwable) { 0 }
        if (narrativeBonus > 0 || cultBonus > 0) {
            ErrorLogger.info(TAG, "${narrative?.cluster?.emoji ?: ""} ${symbol} narrative=${narrativeBonus} cult=${cultBonus} (kw=${narrative?.matchedKeyword})")
        }
        score += narrativeBonus + cultBonus
        // Stash the cluster on the position later via addPosition (read by closePosition)
        
        // Minimum threshold — fluid by learning + paper mode
        // V5.9.404 — restored build #1941-era permissiveness. The bootstrap
        // floor of 30 (V5.9.235) was strangling fresh-launch entries that
        // historically delivered 500%+ runs. Soft scoring still penalises
        // weak setups; FluidLearningAI + Symbiosis still cull bad outcomes.
        val minScore = when {
            learningProgress < 0.1 -> if (isPaper) 12 else 30
            learningProgress < 0.3 -> if (isPaper) 20 else 38
            learningProgress < 0.5 -> if (isPaper) 30 else 48
            else                   -> if (isPaper) 45 else 58
        }
        
        if (score < minScore) {
            return MoonshotScore(false, score, 0.0, "score_${score}_below_${minScore}")
        }

        // V5.9.436 — SCORE-EXPECTANCY SOFT GATE (per-layer).
        // If MOONSHOT trades in this score bucket have been net-losing over
        // the last 25+ closes, skip. Stays exploratory under-sampled.
        if (com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("MOONSHOT", score)) {
            val mean = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean("MOONSHOT", score)
            val n = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketSamples("MOONSHOT", score)
            return MoonshotScore(false, score, 0.0,
                "expectancy_reject_score_${score}_μ_${"%+.1f".format(mean ?: 0.0)}%_n_${n}")
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
        
        // V5.2: Apply FluidLearningAI adjustments to SL/TP
        val fluidTp = FluidLearningAI.getFluidTakeProfit(mode.baseTP, "MOONSHOT_${mode.name}")
        val fluidSl = FluidLearningAI.getFluidStopLoss(kotlin.math.abs(mode.baseSL))
        
        // V5.9.235: Absolute SL floor — FluidLearningAI can widen stops on paper;
        // cap at HARD_FLOOR_STOP so a thin ORBITAL token can't bleed to -20%+
        val clampedSl = maxOf(-fluidSl, HARD_FLOOR_STOP)  // e.g. max(-4, -15) = -4; max(-22, -15) = -15

        return MoonshotScore(
            eligible = true,
            score = score,
            confidence = confidence,
            suggestedSizeSol = sizeSol,
            takeProfitPct = fluidTp,
            stopLossPct = clampedSl,  // V5.9.235: hard floor applied
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
        if (activePositions.size >= effectiveMaxPositions()) {
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
        
        // V5.2: Promoted trades get TIGHTER stop loss since profit is already locked
        // Normal Moonshot uses mode.baseSL, but promotions need protection
        val tightSL = -5.0  // Only allow 5% drawdown from promotion price
        
        val position = MoonshotPosition(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = positionSol,
            entryTime = System.currentTimeMillis(),
            takeProfitPct = mode.baseTP,
            stopLossPct = tightSL.coerceAtLeast(HARD_FLOOR_STOP),  // V5.9.235: floor applied
            marketCapUsd = marketCapUsd,
            liquidityUsd = liquidityUsd,
            entryScore = 100.0,  // Already proven winner
            spaceMode = mode,
            isPaperMode = isPaper,
            promotedFrom = fromLayer,
            peakPnlPct = currentPnlPct,
        )
        
        ErrorLogger.info(TAG, "🚀✨ PROMOTION: $symbol | TIGHT SL=$tightSL% (protecting $fromLayer profits)")
        
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

    /** V5.9.398 — Push a live price (no exit logic). See ShitCoinTraderAI.updateLivePrice. */
    fun updateLivePrice(mint: String, price: Double) {
        if (price <= 0) return
        synchronized(activePositions) { activePositions[mint] }?.lastSeenPrice = price
    }

    // Returns the partial sell % for a PARTIAL_TAKE exit (default 50% — sell half, ride half)
    fun getPartialSellPct(mint: String): Double {
        val pos = synchronized(activePositions) { activePositions[mint] }
        return if (pos != null && pos.partialSellPct > 0) pos.partialSellPct else 0.50
    }
    
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

        // V5.9.404 — telegraph the open into CultMomentumAI so subsequent
        // tokens in the same cluster ride the live narrative bonus.
        try {
            val match = MemeNarrativeAI.detect(symbol = position.symbol, name = position.symbol)
            if (match.cluster != MemeNarrativeAI.Cluster.UNKNOWN) {
                CultMomentumAI.noteOpen(match.cluster)
            }
        } catch (_: Throwable) {}
    }
    
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return
        
        val targetMap = if (pos.isPaperMode) paperPositions else livePositions
        synchronized(targetMap) {
            targetMap.remove(mint)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.2 FIX: RELEASE TRADE AUTHORIZER LOCK
        // This allows the token to be re-entered or promoted to another layer
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.engine.TradeAuthorizer.releasePosition(
                mint = mint,
                reason = "MOONSHOT_${exitReason.name}",
                book = com.lifecyclebot.engine.TradeAuthorizer.ExecutionBook.MOONSHOT
            )
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug(TAG, "Failed to release Moonshot lock: ${e.message}")
        }
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * (pnlPct / 100)
        val isWin = pnlPct > 0.0  // V5.9.408: restored pre-225 win-threshold (was 1.0% → killed WR via scratch count)
        val holdMinutesLong = (System.currentTimeMillis() - pos.entryTime) / 60_000L

        // V5.9.434 — journal every V3 Moonshot close so it shows in Journal
        // V5.9.436 — recorder also feeds outcome-attribution trackers.
        try {
            com.lifecyclebot.engine.V3JournalRecorder.recordClose(
                symbol = pos.symbol, mint = mint,
                entryPrice = pos.entryPrice, exitPrice = exitPrice,
                sizeSol = pos.entrySol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = pos.isPaperMode, layer = "MOONSHOT",
                exitReason = exitReason.name,
                entryScore = pos.entryScore.toInt(),
                holdMinutes = holdMinutesLong,
            )
        } catch (_: Exception) {}
        
        // V5.9.318: Feed outcome into TradingCopilot for life-coach state.
        try { com.lifecyclebot.engine.TradingCopilot.recordTradeForAsset(pnlPct, pos.isPaperMode, assetClass = "MOONSHOT") } catch (_: Exception) {}

        // V5.9.401 — Sentience hook #4: cross-engine telegraph.
        try { com.lifecyclebot.engine.SentienceHooks.recordEngineOutcome("MEME", pnlSol, isWin) } catch (_: Exception) {}

        // V5.9.404 — Symbolic learning: feed cluster outcome back to NarrativeAI
        // so the UI / future prompts can lean on which narratives actually pay.
        try {
            val match = MemeNarrativeAI.detect(symbol = pos.symbol, name = pos.symbol)
            if (match.cluster != MemeNarrativeAI.Cluster.UNKNOWN) {
                MemeNarrativeAI.recordOutcome(match.cluster, pnlPct, isWin)
            }
        } catch (_: Exception) {}
        
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
            // V5.9.8: Sync paper P&L to shared wallet
            if (pos.isPaperMode) {
                com.lifecyclebot.engine.BotService.status.paperWalletSol =
                    (com.lifecyclebot.engine.BotService.status.paperWalletSol + pnlSol).coerceAtLeast(0.0)
            }
        
        // Update local learning progress
        updateLearning(pnlPct, isWin)
        
        // V5.2 FIX: Moonshot trades NOW contribute to FluidLearningAI maturity!
        // This was MISSING before - Moonshot layer was isolated from central learning
        try {
            if (pos.isPaperMode) {
                FluidLearningAI.recordSubTraderTrade(isWin)
                try { com.lifecyclebot.engine.SmartSizer.recordTrade(isWin, isPaperMode = true) } catch (_: Exception) {}
                try { com.lifecyclebot.engine.FluidLearning.recordPaperSell(pos.symbol, pos.entrySol, pnlSol, exitReason.name, "MOONSHOT") } catch (_: Exception) {}
            } else {
                FluidLearningAI.recordSubTraderTrade(isWin)
            }
            ErrorLogger.debug(TAG, "📊 Recorded to FluidLearningAI: ${pos.symbol} ${if (isWin) "WIN" else "LOSS"}")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "FluidLearningAI record failed: ${e.message}")
        }
        
        // V5.9.211: Sentience wiring — all 41 layers + personality memory update
        try {
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordSimpleTradeOutcome(
                symbol    = pos.symbol,
                mint      = pos.mint,
                pnlPct    = pnlPct,
                holdMins  = holdMins,
                traderTag = "MOONSHOT",
                exitReason = exitReason.name,
            )
            com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                pnlPct              = pnlPct,
                gaveBackFromPeakPct = (pos.peakPnlPct - pnlPct).coerceAtLeast(0.0),
                heldMinutes         = holdMins.toInt().coerceAtLeast(1),
            )
            if (isWin) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(pos.symbol, pnlPct, "MOONSHOT", holdMins.toLong() * 60)
            } else if (pnlPct <= -1.0) {
                // V5.9.307: scratch trades (-1%..+1%) are NEUTRAL — don't reset win streak / inflate loss streak
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(pos.symbol, pnlPct, "MOONSHOT", exitReason.name)
            }

            // V5.9.305: WIRE STRATEGY TRUST + ADAPTIVE LEARNING — was missing from closePosition.
            // Without this, 46/47 strategies showed [UNTESTED] because Moonshot/ShitCoin closes
            // never reached TradeLessonRecorder.recordTrade → StrategyTrustAI.recordTrade.
            try {
                val strategyName = "MOONSHOT_${pos.spaceMode.name}"
                val mfePct = if (pos.peakPnlPct > 0.0) pos.peakPnlPct else pnlPct.coerceAtLeast(0.0)
                val maePct = pnlPct.coerceAtMost(0.0)
                val lessonCtx = com.lifecyclebot.v4.meta.TradeLessonRecorder.TradeLessonContext(
                    strategy = strategyName,
                    market = "MEME",
                    symbol = pos.symbol,
                    entryRegime = com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                    entrySession = com.lifecyclebot.v4.meta.SessionContext.OFF_HOURS,
                    trustScore = 0.5, fragilityScore = 0.3,
                    narrativeHeat = 0.5, portfolioHeat = 0.3,
                    leverageUsed = 1.0, executionConfidence = 0.6,
                    leadSource = null, expectedDelaySec = null,
                    expectedFillPrice = pos.entryPrice,
                    executionRoute = "JUPITER_V6",
                    captureTime = pos.entryTime
                )
                com.lifecyclebot.v4.meta.TradeLessonRecorder.completeLesson(
                    context = lessonCtx,
                    outcomePct = pnlPct,
                    mfePct = mfePct,
                    maePct = maePct,
                    holdSec = ((System.currentTimeMillis() - pos.entryTime) / 1000).toInt(),
                    exitReason = exitReason.name,
                    actualFillPrice = exitPrice
                )

                // V5.9.305: Feed AdaptiveLearningEngine for pattern matching (V5.9.301 patterns)
                val label = when {
                    pnlPct >= 100.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.GOOD_RUNNER
                    pnlPct >= 30.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.GOOD_CONTINUATION
                    pnlPct >= 10.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.GOOD_SECOND_LEG
                    pnlPct >= 1.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.MID_SMALL_WIN
                    pnlPct in -0.5..1.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.MID_FLAT_CHOP
                    pnlPct in -5.0..-0.5 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.MID_WEAK_FOLLOW
                    pnlPct in -15.0..-5.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.MID_STOPPED_OUT
                    pnlPct <= -50.0 && exitReason == ExitSignal.RUG_DETECTED -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.BAD_RUG
                    pnlPct <= -30.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.BAD_DUMP
                    pnlPct <= -15.0 -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.BAD_DEAD_CAT
                    else -> com.lifecyclebot.engine.AdaptiveLearningEngine.TradeLabel.MID_CHOP
                }
                val features = com.lifecyclebot.engine.AdaptiveLearningEngine.TradeFeatures(
                    entryMcapUsd = 0.0, tokenAgeMinutes = 0.0,
                    buyRatioPct = 0.0, volumeUsd = 0.0,
                    liquidityUsd = 0.0, holderCount = 0,
                    topHolderPct = 0.0, holderGrowthRate = 0.0,
                    devWalletPct = 0.0, bondingCurveProgress = 0.0,
                    rugcheckScore = 0.0, emaFanState = "",
                    entryScore = 0.0, volumeLiquidityRatio = 0.0,
                    priceFromAth = 0.0,
                    pnlPct = pnlPct,
                    maxGainPct = mfePct,
                    maxDrawdownPct = maePct,
                    timeToPeakMins = 0.0,
                    holdTimeMins = holdMins,
                    exitReason = exitReason.name,
                    outcomeScore = if (isWin) 1 else if (pnlPct <= -1.0) -1 else 0,
                    label = label
                )
                com.lifecyclebot.engine.AdaptiveLearningEngine.learnFromTrade(features)
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Lesson/Trust wiring error: ${e.message}")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Sentience hook error: ${e.message}")
        }

        // V5.6.29c: Persist learning state after trade
        save()
        
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
        // V5.9.392 — stash latest price so the unified open-positions card
        // can render live P&L for moonshot bags not in status.tokens.
        pos.lastSeenPrice = currentPrice
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000

        // V5.9.443 — EARLY-DEATH STOP.
        // V5.9.444 — fluid cutoff from HoldDurationTracker 0-1min bucket.
        val holdSeconds = (System.currentTimeMillis() - pos.entryTime) / 1000
        if (holdSeconds < 60) {
            val cutoff = com.lifecyclebot.engine.ChopFilter.earlyDeathCutoffPct("MOONSHOT")
            if (pnlPct < cutoff) {
                ErrorLogger.info(TAG, "🚀⚡ EARLY-DEATH STOP: ${pos.symbol} | ${pnlPct.fmt(1)}% in ${holdSeconds}s (cutoff=${"%.1f".format(cutoff)}%)")
                return ExitSignal.STOP_LOSS
            }
        }

        // Update peak P&L
        if (pnlPct > pos.peakPnlPct) {
            pos.peakPnlPct = pnlPct
        }

        // V5.9.438 — HARD PEAK-DRAWDOWN LOCK (unconditional backstop).
        // Runs before every other gate. Catches catastrophic cases where
        // the fluid profit floor was somehow bypassed. User reported Kenny
        // peaked +326%, floor +314%, stayed open all the way to +108%.
        if (com.lifecyclebot.engine.PeakDrawdownLock.shouldLock(pos.peakPnlPct, pnlPct)) {
            ErrorLogger.warn(TAG, "🚀🔒🛑 PEAK-DRAWDOWN LOCK: ${pos.symbol} | " +
                "peak +${pos.peakPnlPct.toInt()}% → now +${pnlPct.fmt(1)}% " +
                "(gave back ≥${(com.lifecyclebot.engine.PeakDrawdownLock.DRAWDOWN_TRIGGER_FRAC * 100).toInt()}% of peak)")
            return ExitSignal.TRAILING_STOP
        }

        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice

            // V5.9.169 — continuous fluid trail (smooth log curve).
            val dynamicTrailPct = FluidLearningAI.fluidTrailPct(pnlPct).coerceAtLeast(when (pos.spaceMode) {
                SpaceMode.ORBITAL -> 2.5
                SpaceMode.LUNAR -> 2.5
                SpaceMode.MARS -> 2.5
                SpaceMode.JUPITER -> 2.5
            })
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

        // V5.9.404 — RELAXED early-dead exit. V5.9.235 cut everything at
        // 20min/-6%, but legit moonshots routinely dip 6–10% before
        // launching. Window halved to 12min and threshold widened to -10%
        // so genuine pre-launch noise survives.
        if (holdMinutes <= 12 && pnlPct < -10.0) {
            ErrorLogger.warn(TAG, "💀 MOON DEAD EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% at ${holdMinutes}min — cutting early")
            return ExitSignal.STOP_LOSS
        }

        // 2. STOP LOSS HIT
        if (pnlPct <= pos.stopLossPct) {
            ErrorLogger.info(TAG, "🛑 SL HIT: ${pos.symbol} | ${pnlPct.fmt(1)}%")
            return ExitSignal.STOP_LOSS
        }
        
        // 3. LADDERED PARTIAL TAKES (V5.9.163)
        // User: "not locking profit or partial selling on the way up
        // anymore". The old firstTakeDone flag fired ONE partial and
        // never again — a +3499% moonshot never took a single profit
        // past the initial ~75%. Now we walk a ladder and each rung
        // fires one partial sell (executor handles the actual sell sizing).
        val rungs = PARTIAL_TAKE_LADDER
        if (pos.partialRungsTaken < rungs.size && pnlPct >= rungs[pos.partialRungsTaken]) {
            val hitRung = rungs[pos.partialRungsTaken]
            pos.partialRungsTaken += 1
            pos.firstTakeDone = true  // kept for legacy UI / metrics
            ErrorLogger.info(TAG, "💰 LADDER PARTIAL #${pos.partialRungsTaken}: ${pos.symbol} | " +
                "hit +${hitRung.toInt()}% (now +${pnlPct.fmt(1)}%) — locking a slice, rest rides")
            return ExitSignal.PARTIAL_TAKE
        }

        // V5.9.169 — continuous fluid profit floor (shared engine).
        val profitFloor = FluidLearningAI.fluidProfitFloor(pos.peakPnlPct)
        if (pnlPct < profitFloor) {
            ErrorLogger.info(TAG, "🔒 FLOOR LOCK: ${pos.symbol} | peak +${pos.peakPnlPct.toInt()}% → now +${pnlPct.fmt(1)}% < floor +${profitFloor.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }

        // V5.9.437 — LIVE HOLD-BUCKET GATE. Cut flat stale Moonshot bags
        // whose hold-duration bucket has proven net-losing expectancy.
        if (com.lifecyclebot.engine.OutcomeGates.earlyExitByHoldBucket(
                layer = "MOONSHOT", holdMinutes = holdMinutes, pnlPct = pnlPct)) {
            ErrorLogger.info(TAG, "🧠⏱️ HOLD-BUCKET EARLY EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min — bucket history bleeds")
            return ExitSignal.FLAT_EXIT
        }
        
        // 4. TRAILING STOP - locks in gains while letting it run
        if (pnlPct > 30.0 && currentPrice <= pos.trailingStop) {
            ErrorLogger.info(TAG, "🎯 TRAIL EXIT: ${pos.symbol} | +${pnlPct.fmt(1)}% | Peak was +${pos.peakPnlPct.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. FLAT EXIT — V5.9.397 baseline (V5.9.304 era).
        // hold ≥ maxHold/2, pnl in [-2%, +5%].
        // V5.9.437 — extend window for winners when FLAT_EXIT historically bleeds this lane.
        val flatExitExt = com.lifecyclebot.engine.OutcomeGates.timeExitExtensionMult(
            layer = "MOONSHOT", exitReason = "FLAT_EXIT", pnlPct = pnlPct)
        val flatExitMins = ((pos.spaceMode.maxHold / 2) * flatExitExt).toLong()
        if (holdMinutes >= flatExitMins && pnlPct > -2.0 && pnlPct < 5.0) {
            ErrorLogger.info(TAG, "😐 FLAT EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min (truly flat, half-maxHold)")
            return ExitSignal.FLAT_EXIT
        }
        
        // 6. TIMEOUT - only if not significantly profitable
        // V5.9.437 — extend for winners when TIMEOUT historically bleeds this lane.
        val timeoutExt = com.lifecyclebot.engine.OutcomeGates.timeExitExtensionMult(
            layer = "MOONSHOT", exitReason = "TIMEOUT", pnlPct = pnlPct)
        val timeoutMins = (pos.spaceMode.maxHold * timeoutExt).toLong()
        if (holdMinutes >= timeoutMins && pnlPct < 50.0) {
            ErrorLogger.info(TAG, "⏰ TIMEOUT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.TIMEOUT
        }
        
        // V5.9.404 — DEAD POSITION FLUSH softened. Was 90min/<10% (V5.9.204).
        // Build #1941 era let things ride — 500%+ winners often spent 2–3h
        // flat or slightly underwater before launching. Now we only flush
        // genuinely dead bags (180min, still under +5%, not deeply red).
        if (holdMinutes >= 180 && pnlPct < 5.0 && pnlPct > -50.0) {
            ErrorLogger.warn(TAG, "💀 DEAD POS FLUSH: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.FLAT_EXIT
        }

        // V5.9.401 — Sentience hook #2: LLM exit override (cached, fail-open).
        if (com.lifecyclebot.engine.SentienceHooks.shouldExit(
                symbol = pos.symbol,
                pnlPct = pnlPct,
                holdMinutes = holdMinutes,
                peakPct = pos.peakPnlPct
            )) {
            ErrorLogger.info(TAG, "🤖 LLM EXIT OVERRIDE: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min")
            return ExitSignal.FLAT_EXIT
        }

        // V5.9.402 — Lab Promoted Feed: proven LLM strategies can force-exit memes.
        try {
            if (com.lifecyclebot.engine.lab.LabPromotedFeed.shouldExitByPromotedRule(
                    asset = com.lifecyclebot.engine.lab.LabAssetClass.MEME,
                    pnlPct = pnlPct,
                    holdMinutes = holdMinutes,
                )) {
                ErrorLogger.info(TAG, "🧪 LAB EXIT: ${pos.symbol} matched a promoted strategy's TP/SL/timeout (${pnlPct.fmt(1)}%/${holdMinutes}min)")
                return ExitSignal.FLAT_EXIT
            }
        } catch (_: Throwable) {}

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
    
    /**
     * V5.2: Force clear all positions on bot stop
     * Ensures UI doesn't show stale positions after shutdown
     */
    fun clearAllPositions() {
        synchronized(activePositions) {
            activePositions.clear()
        }
        synchronized(paperPositions) {
            paperPositions.clear()
        }
        synchronized(livePositions) {
            livePositions.clear()
        }
        ErrorLogger.info(TAG, "🚀 MOONSHOT: Cleared all positions")
    }
    
    fun getSpaceModeStats(): Map<SpaceMode, Int> {
        return activePositions.values.groupingBy { it.spaceMode }.eachCount()
    }
    
    /**
     * V5.7: Record learning from perps trades for cross-layer intelligence
     */
    fun recordLearning(isWin: Boolean, pnlPct: Double) {
        // V5.9.208: isWin now passed as pnlPct >= 1.0 from closePosition
        if (isWin) {
            dailyWins.incrementAndGet()
            if (pnlPct >= 100) dailyHundredXCount.incrementAndGet()
            if (pnlPct >= 1000) lifetimeThousandX.incrementAndGet()
        } else {
            dailyLosses.incrementAndGet()
        }
        dailyPnlSolBps.addAndGet((pnlPct * 100).toLong())
        save()
    }
    
    // Helper extension
    private fun Double.fmt(decimals: Int) = String.format("%.${decimals}f", this)
}
