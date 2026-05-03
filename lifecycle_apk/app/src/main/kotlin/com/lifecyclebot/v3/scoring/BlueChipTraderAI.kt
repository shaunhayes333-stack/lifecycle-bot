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
 * 1. Market Cap Filter: MINIMUM $50k (vs Treasury's no minimum)
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
    
    // V5.2.12: BlueChip handles large-cap professional trading
    // Market cap: $1M+ (flows from Quality at $1M)
    private const val MIN_MARKET_CAP_USD = 1_000_000.0  // $1M minimum (flows from Quality max)
    
    // Liquidity requirements - institutional standards
    private const val MIN_LIQUIDITY_USD = 50_000.0      // V5.2.12: $50K minimum for large caps
    
    // Position sizing
    private const val BASE_POSITION_SOL = 0.15         // Larger than Treasury (0.05)
    private const val MAX_POSITION_SOL = 0.5           // Up to 0.5 SOL per trade
    private const val MAX_CONCURRENT_POSITIONS = 15    // V5.9.343: walk-back to V5.9.336
    
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE - V5.6.29c
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("bluechip_trader_ai", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "💎 BlueChipTraderAI persistence initialized")
    }
    
    private fun restore() {
        val p = prefs ?: return
        paperBalanceBps.set(p.getLong("paperBalanceBps", 0))
        liveBalanceBps.set(p.getLong("liveBalanceBps", 0))
        
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyPnlSolBps.set(p.getLong("dailyPnlSolBps", 0))
            dailyWins.set(p.getInt("dailyWins", 0))
            dailyLosses.set(p.getInt("dailyLosses", 0))
            dailyTradeCount.set(p.getInt("dailyTradeCount", 0))
        }
        ErrorLogger.info(TAG, "💎 RESTORED: paperBal=${paperBalanceBps.get()/100.0} | wins=${dailyWins.get()} losses=${dailyLosses.get()}")
    }
    
    fun save(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        
        p.edit().apply {
            putLong("paperBalanceBps", paperBalanceBps.get())
            putLong("liveBalanceBps", liveBalanceBps.get())
            putLong("savedDay", now / (24 * 60 * 60 * 1000))
            putLong("dailyPnlSolBps", dailyPnlSolBps.get())
            putInt("dailyWins", dailyWins.get())
            putInt("dailyLosses", dailyLosses.get())
            putInt("dailyTradeCount", dailyTradeCount.get())
            apply()
        }
    }

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
        val stopLossPct: Double,
        // V5.9.166: shared laddered profit-lock — same engine as Moonshot/ShitCoin.
        var highWaterMark: Double = entryPrice,
        var peakPnlPct: Double = 0.0,
        var partialRungsTaken: Int = 0,
        // V5.9.392 — latest price for unified open-positions card.
        var lastSeenPrice: Double = entryPrice,
        // V5.9.436 — entry score preserved for outcome attribution.
        val entryScore: Int = 0,
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
        val isPaperMode: Boolean,
        // V5.9.436 — entry score so caller can stash it on the Position.
        val entryScore: Int = 0,
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
        PARTIAL_TAKE,  // V5.9.166: laddered partial-sell signal
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
    
    /**
     * V5.6.11: Set trading mode and transfer learning from paper to live
     */
    fun setTradingMode(isPaper: Boolean) {
        val wasInPaper = isPaperMode
        isPaperMode = isPaper
        
        // Transfer paper balance to live when switching modes
        if (!isPaper && wasInPaper) {
            val paperBal = paperBalanceBps.get()
            if (paperBal > liveBalanceBps.get()) {
                liveBalanceBps.set(paperBal)
                ErrorLogger.info(TAG, "🔵 TRANSFER: Balance ${paperBal/100.0} SOL from PAPER to LIVE")
            }
        }
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
        // V5.9.218: Auto-purge zombie positions (held > 2x MAX_HOLD_MINUTES with no monitor)
        val now = System.currentTimeMillis()
        val staleThresholdMs = MAX_HOLD_MINUTES * 2 * 60_000L
        synchronized(activePositions) {
            val stale = activePositions.values.filter { (now - it.entryTime) > staleThresholdMs }
            if (stale.isNotEmpty()) {
                stale.forEach { pos ->
                    activePositions.remove(pos.mint)
                    ErrorLogger.warn(TAG, "🔵🧹 ZOMBIE PURGE: ${pos.symbol} | held ${(now - pos.entryTime)/60000}min, no monitor")
                }
            }
        }
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }
    
    /**
     * V5.2: Force clear all positions on bot stop
     * This ensures UI updates correctly even if individual closes fail
     */
    fun clearAllPositions() {
        synchronized(activePositions) {
            val count = activePositions.size
            activePositions.clear()
            paperPositions.clear()
            livePositions.clear()
            ErrorLogger.info(TAG, "🔵 CLEARED $count BlueChip positions on shutdown")
        }
    }
    
    fun getActivePositionsForMode(isPaper: Boolean): List<BlueChipPosition> {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) {
            positions.values.toList()
        }
    }
    
    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)

    /** V5.9.398 — Push a live price (no exit logic). See ShitCoinTraderAI.updateLivePrice. */
    fun updateLivePrice(mint: String, price: Double) {
        if (price <= 0) return
        synchronized(activePositions) { activePositions[mint] }?.lastSeenPrice = price
    }
    
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
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.2 FIX: RELEASE TRADE AUTHORIZER LOCK
        // This allows the token to be re-entered or graduated to another layer
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.engine.TradeAuthorizer.releasePosition(
                mint = mint,
                reason = "BLUECHIP_${exitReason.name}",
                book = com.lifecyclebot.engine.TradeAuthorizer.ExecutionBook.BLUECHIP
            )
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug(TAG, "Failed to release BlueChip lock: ${e.message}")
        }
        
        // Guard: if entryPrice was zero/near-zero, pnlPct blows up — cap to realistic range
        val pnlPct = if (pos.entryPrice > 0) {
            ((exitPrice - pos.entryPrice) / pos.entryPrice * 100).coerceIn(-100.0, 10_000.0)
        } else 0.0
        val pnlSol = pos.entrySol * pnlPct / 100
        val holdMinutesLong = (System.currentTimeMillis() - pos.entryTime) / 60_000L

        // V5.9.434 — journal every V3 BlueChip close so it shows in Journal
        // V5.9.436 — recorder also feeds outcome-attribution trackers.
        try {
            com.lifecyclebot.engine.V3JournalRecorder.recordClose(
                symbol = pos.symbol, mint = mint,
                entryPrice = pos.entryPrice, exitPrice = exitPrice,
                sizeSol = pos.entrySol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = pos.isPaper, layer = "BLUECHIP",
                exitReason = exitReason.name,
                entryScore = pos.entryScore,
                holdMinutes = holdMinutesLong,
            )
        } catch (_: Exception) {}

        // V5.9.318: Feed outcome into TradingCopilot for life-coach state.
        try { com.lifecyclebot.engine.TradingCopilot.recordTradeForAsset(pnlPct, pos.isPaper, assetClass = "BLUECHIP") } catch (_: Exception) {}

        // V5.9.401 — Sentience hook #4: cross-engine telegraph (MEME).
        try { com.lifecyclebot.engine.SentienceHooks.recordEngineOutcome("MEME", pnlSol, pnlPct >= 1.0) } catch (_: Exception) {}
        
        // Record to daily P&L
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)
        
        // V5.9.328: Use pnlPct>=1.0 for win tracking (unified threshold, was pnlSol>0)
        if (pnlPct >= 1.0) {
            dailyWins.incrementAndGet()
            addToBalance(pnlSol * COMPOUNDING_RATIO, pos.isPaper) // Compound portion
        } else {
            dailyLosses.incrementAndGet()
        }
        
        // V4.0: Blue Chip trades contribute to FluidLearningAI maturity
        try {
            val isWin = pnlPct > 0.0  // V5.9.408: restored pre-225 win-threshold
            if (pos.isPaper) {
                FluidLearningAI.recordSubTraderTrade(isWin)
            } else {
                FluidLearningAI.recordSubTraderTrade(isWin)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }
        
        // V5.6.29c: Persist after trade
        save()
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
        
        ErrorLogger.info(TAG, "🔵 BLUE CHIP CLOSED [$modeLabel]: ${pos.symbol} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "reason=$exitReason | Daily: ${dailyPnl.fmt(4)} SOL | " +
            "Balance: ${getCurrentBalance().fmt(4)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECKING - V5.2.12: Added proper exit logic for BlueChip layer
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        val pos = synchronized(activePositions) { activePositions[mint] } ?: return ExitSignal.HOLD
        pos.lastSeenPrice = currentPrice  // V5.9.392 — unified UI live P&L

        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000

        // V5.9.166: Track peak + high-water for laddered profit-lock.
        if (pnlPct > pos.peakPnlPct) pos.peakPnlPct = pnlPct
        if (currentPrice > pos.highWaterMark) pos.highWaterMark = currentPrice

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.166 — SHARED LADDERED PROFIT-LOCK
        // BlueChip runs up to 5x-ish historically, but nothing stops a
        // quality meme from catching a mega-pump. Give it the same ladder
        // engine as Moonshot/ShitCoin so profits are locked progressively.
        // ═══════════════════════════════════════════════════════════════════
        val rungs = doubleArrayOf(20.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0)
        if (pos.partialRungsTaken < rungs.size && pnlPct >= rungs[pos.partialRungsTaken]) {
            val hitRung = rungs[pos.partialRungsTaken]
            pos.partialRungsTaken += 1
            ErrorLogger.info(TAG, "🔵💰 LADDER PARTIAL #${pos.partialRungsTaken}: ${pos.symbol} | hit +${hitRung.toInt()}% (now +${pnlPct.toInt()}%)")
            return ExitSignal.PARTIAL_TAKE
        }

        // V5.9.169 — continuous fluid profit floor (shared engine).
        val profitFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(pos.peakPnlPct)
        if (pnlPct < profitFloor) {
            ErrorLogger.info(TAG, "🔵🔒 FLOOR LOCK: ${pos.symbol} | peak +${pos.peakPnlPct.toInt()}% → +${pnlPct.toInt()}% < +${profitFloor.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }

        // ═══════════════════════════════════════════════════════════════════
        // STANDARD EXIT CONDITIONS (below the ladder)
        // ═══════════════════════════════════════════════════════════════════

        // 1. TAKE PROFIT - hit target
        if (pnlPct >= pos.takeProfitPct) {
            ErrorLogger.info(TAG, "🔵 TP HIT: ${pos.symbol} | +${pnlPct.toInt()}%")
            return ExitSignal.TAKE_PROFIT
        }

        // 2. STOP LOSS - hit limit
        if (pnlPct <= pos.stopLossPct) {
            ErrorLogger.info(TAG, "🔵 SL HIT: ${pos.symbol} | ${pnlPct.toInt()}%")
            return ExitSignal.STOP_LOSS
        }

        // 3. TIME EXIT - max hold exceeded and not significantly profitable
        if (holdMinutes >= MAX_HOLD_MINUTES && pnlPct < 8.0) {
            ErrorLogger.info(TAG, "🔵 TIME: ${pos.symbol} | ${pnlPct.toInt()}% after ${holdMinutes}min")
            return ExitSignal.TIME_EXIT
        }

        return ExitSignal.HOLD
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
        
        // 3. MAX POSITIONS CHECK — V5.9.193: bypassed during bootstrap for data gathering
        val bcBootstrap = FluidLearningAI.getLearningProgress() < 0.40
        if (!bcBootstrap && activePositions.size >= MAX_CONCURRENT_POSITIONS) {
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
            (if (marketCapUsd > 100_000) 25 else 15) +
            (if (liquidityUsd > 15_000) 25 else 15) +
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

        // V5.9.436 — SCORE-EXPECTANCY SOFT GATE (per-layer).
        if (com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("BLUECHIP", blueChipScore)) {
            val mean = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean("BLUECHIP", blueChipScore)
            val n = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketSamples("BLUECHIP", blueChipScore)
            ErrorLogger.info(TAG, "🔵📉 EXPECTANCY_REJECT: $symbol | score=$blueChipScore | " +
                "bucket μ=${"%+.1f".format(mean ?: 0.0)}% over n=$n trades — skipping")
            return BlueChipSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = blueChipConfidence,
                reason = "EXPECTANCY_REJECT: score=$blueChipScore bucketMean=${"%+.1f".format(mean ?: 0.0)}% (n=$n)",
                mode = mode,
                isPaperMode = isPaperMode,
                entryScore = blueChipScore,
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
            "mcap=\$${(marketCapUsd/50_000).fmt(2)}M | " +
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
            isPaperMode = isPaperMode,
            entryScore = blueChipScore,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS - Blue Chip specific
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.4: Fluid thresholds - much lower at bootstrap to allow paper mode to trade
    // Blue Chip tokens are $1M+ mcap so high liq floor was blocking most paper candidates
    // V5.9.83: Bootstrap thresholds were TOO loose — 1% win rate observed. Tighten.
    // V5.9.300: V5.9.198 ARCHITECTURE — per-trader floors HIGH (global FluidLearningAI is now LOW).
    private const val BC_SCORE_BOOTSTRAP = 20       // V5.9.343: walk-back to V5.9.335
    private const val BC_SCORE_MATURE = 28          // V5.9.343: walk-back to V5.9.335

    // V5.9.83: Conf floor raised so initial entries are higher-conviction.
    private const val BC_CONF_BOOTSTRAP = 20        // V5.9.343: walk-back to V5.9.335
    private const val BC_CONF_MATURE = 35           // V5.9.343: walk-back to V5.9.335
    private const val BC_CONF_BOOST_MAX = 8.0       // Softer bootstrap boost

    // V5.9.83: Liq floor at bootstrap raised to filter out the thinnest rugs.
    private const val BC_LIQ_BOOTSTRAP = 7_000.0    // V5.9.343: walk-back to V5.9.335
    private const val BC_LIQ_MATURE = 15_000.0      // V5.9.343: walk-back to V5.9.335
    
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
            winRate = if (dailyWins.get() + dailyLosses.get() > 0) {
                dailyWins.get().toDouble() / (dailyWins.get() + dailyLosses.get()) * 100
            } else 0.0,
            activePositions = activePositions.size,
            mode = getCurrentMode(),
            balanceSol = getCurrentBalance(),
            isPaperMode = isPaperMode,
            minScoreThreshold = getFluidScoreThreshold(),
            minConfThreshold = getFluidConfidenceThreshold()
        )
    }
    
    /**
     * V5.7: Get win rate for perps learning bridge
     */
    fun getWinRatePct(): Int {
        val total = dailyWins.get() + dailyLosses.get()
        return if (total > 0) ((dailyWins.get().toDouble() / total) * 100).toInt() else 0
    }
    
    /**
     * V5.7.3: Record learning from perps/stock trades
     */
    fun recordPerpsLearning(
        symbol: String,
        isWin: Boolean,
        pnlPct: Double,
        isStock: Boolean,
    ) {
        try {
            // Update win/loss counters
            if (isWin) {
                dailyWins.incrementAndGet()
            } else {
                dailyLosses.incrementAndGet()
            }
            
            // Stock-specific learning
            if (isStock) {
                // Track stock-specific patterns
                val stockKey = "STOCK_$symbol"
                val currentScore = symbolTrustScores[stockKey] ?: 50
                val delta = if (isWin) {
                    (pnlPct / 10).coerceIn(1.0, 10.0).toInt()
                } else {
                    -(pnlPct.absoluteValue / 10).coerceIn(1.0, 5.0).toInt()
                }
                symbolTrustScores[stockKey] = (currentScore + delta).coerceIn(0, 100)
                
                ErrorLogger.info(TAG, "📈 Stock learning: $symbol ${if (isWin) "WIN" else "LOSS"} " +
                    "trust=${symbolTrustScores[stockKey]}")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordPerpsLearning error: ${e.message}")
        }
    }
    
    /**
     * V5.7.3: Get stock trust score
     */
    fun getStockTrustScore(symbol: String): Int {
        return symbolTrustScores["STOCK_$symbol"] ?: 50
    }
    
    // Symbol trust scores for stocks
    private val symbolTrustScores = java.util.concurrent.ConcurrentHashMap<String, Int>()
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
    private val Double.absoluteValue: Double get() = kotlin.math.abs(this)
}
