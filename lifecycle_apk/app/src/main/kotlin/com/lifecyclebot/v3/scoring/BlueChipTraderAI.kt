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
    private const val MAX_CONCURRENT_POSITIONS = 80    // V5.9.613: remove hidden lane choke
    
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
    
    /**
     * V5.9.1498 — GHOST EVICTION. Pure map removal from BOTH live and paper maps
     * for a mint already closed elsewhere (close ledger CLOSED). No PnL / no
     * learning — the real close already recorded the outcome. Stops the ghost
     * being re-emitted into forcedOpen and permanently parking entry admission.
     */
    fun evictGhost(mint: String): Boolean {
        val a = synchronized(livePositions) { livePositions.remove(mint) != null }
        val b = synchronized(paperPositions) { paperPositions.remove(mint) != null }
        return a || b
    }

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
     * V5.9.705 — Reduce sub-trader tracked entrySol after a confirmed partial sell.
     */
    fun onPartialSell(mint: String, soldFraction: Double) {
        val frac = soldFraction.coerceIn(0.0, 1.0)
        if (frac <= 0.0) return
        val pos = activePositions[mint] ?: return
        val updated = pos.copy(entrySol = pos.entrySol * (1.0 - frac))
        activePositions[mint] = updated
        ErrorLogger.debug(TAG, "💎🔪 onPartialSell ${pos.symbol}: entrySol ${pos.entrySol} → ${updated.entrySol} (sold ${(frac*100).toInt()}%)")
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
    
    fun hasPosition(mint: String): Boolean =
        // V5.9.457 — mode-orphan fix: check BOTH maps.
        paperPositions.containsKey(mint) || livePositions.containsKey(mint)

    /** V5.9.398 — Push a live price (no exit logic). See ShitCoinTraderAI.updateLivePrice. */
    fun updateLivePrice(mint: String, price: Double) {
        if (price <= 0) return
        // V5.9.457 — stamp on BOTH maps so orphans still show fresh pnl.
        synchronized(paperPositions) { paperPositions[mint]?.lastSeenPrice = price }
        synchronized(livePositions) { livePositions[mint]?.lastSeenPrice = price }
    }
    
    fun addPosition(position: BlueChipPosition) {
        synchronized(activePositions) {
            activePositions[position.mint] = position
        }
        dailyTradeCount.incrementAndGet()
        try { com.lifecyclebot.engine.UltimateEdgeEngine.enqueueRefresh(position.mint, position.symbol, "BLUECHIP", "BLUECHIP_OPEN", position.entryScore.coerceIn(0, 100), "open_size_${position.entrySol.fmt(4)}") } catch (_: Throwable) {}
        
        ErrorLogger.info(TAG, "🔵 BLUE CHIP ENTRY: ${position.symbol} | " +
            "mcap=\$${(position.marketCapUsd/1_000_000).fmt(2)}M | " +
            "liq=\$${(position.liquidityUsd/1000).fmt(0)}K | " +
            "size=${position.entrySol.fmt(4)} SOL | " +
            "TP=${position.takeProfitPct.fmt(0)}% SL=${position.stopLossPct.fmt(0)}%")
    }

    fun restorePosition(position: BlueChipPosition, isPaper: Boolean) {
        val target = if (isPaper) paperPositions else livePositions
        synchronized(target) { target[position.mint] = position }
        ErrorLogger.warn(TAG, "🔵 BLUE CHIP RESTORED: ${position.symbol} | mode=${if (isPaper) "PAPER" else "LIVE"} | entry=${position.entryPrice}")
    }
    
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        // V5.9.457 — mode-orphan fix: if mint isn't in the current-mode
        // map, fall back to the OTHER map so the actual close happens
        // even when cfg was toggled between paper/live after entry.
        var pos = synchronized(activePositions) { activePositions.remove(mint) }
        if (pos == null) {
            val otherMap = if (isPaperMode) livePositions else paperPositions
            pos = synchronized(otherMap) { otherMap.remove(mint) }
            if (pos != null) {
                ErrorLogger.warn(TAG, "🔵⚠ BLUECHIP CLOSE MODE MISMATCH: ${pos.symbol} " +
                    "removed from ${if (isPaperMode) "LIVE" else "PAPER"} map (cfg.paperMode=$isPaperMode)")
            }
        }
        if (pos == null) return
        
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
        try { com.lifecyclebot.engine.UltimateEdgeEngine.enqueueRefresh(pos.mint, pos.symbol, "BLUECHIP", "BLUECHIP_CLOSE", pnlPct.toInt().coerceIn(-100, 100), "exit_${exitReason.name}_pnl_${pnlPct.fmt(2)}") } catch (_: Throwable) {}
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
        // V5.0.4160 — feed shared ScratchStreakRegistry (butterfly sweep).
        try { com.lifecyclebot.engine.ScratchStreakRegistry.recordOutcome("BLUECHIP", pnlPct) } catch (_: Throwable) {}
        // V5.9.1437 — ROUTE LANE CLOSES INTO BehaviorAI. V3 sub-traders
        // bypass Executor.recordTrade (V5.9.434), so the BehaviorAI fanout
        // at Executor:2465 NEVER fired for lane trades → Neural Personality
        // panel (Streak/Tilt/Discipline/Session Stats/FLUID LEARNING IMPACT)
        // sat dead at 0 across hundreds of trades. Feed it directly here.
        try { com.lifecyclebot.v3.scoring.BehaviorAI.recordTradeForAsset(pnlPct = pnlPct, reason = exitReason.name, mint = pos.mint, isPaperMode = pos.isPaper, assetClass = "BLUECHIP") } catch (_: Exception) {}

        // V5.9.852 — non-meme close → CanonicalOutcomeBus (Layer Readiness fix).
        val bluechipExitTs = System.currentTimeMillis()
        com.lifecyclebot.engine.CanonicalPublishHelper.publishExit(
            tradeIdSeed   = "${mint}_$bluechipExitTs",
            mint          = mint,
            symbol        = pos.symbol,
            source        = com.lifecyclebot.engine.TradeSource.BLUECHIP,
            isPaper       = pos.isPaper,
            entryTimeMs   = pos.entryTime,
            exitTimeMs    = bluechipExitTs,
            entryPrice    = pos.entryPrice,
            exitPrice     = exitPrice,
            entrySol      = pos.entrySol,
            exitSol       = pos.entrySol + pnlSol,
            realizedPnlSol = pnlSol,
            realizedPnlPct = pnlPct,
            maxGainPct    = if (pos.entryPrice > 0 && pos.highWaterMark > pos.entryPrice)
                                ((pos.highWaterMark - pos.entryPrice) / pos.entryPrice) * 100.0 else null,
            closeReason   = "BLUECHIP_${exitReason.name}",
            assetClass    = com.lifecyclebot.engine.AssetClass.BLUECHIP,
            entryScore    = pos.entryScore.toDouble(),
            // V5.9.896 — promote from lite→rich so BehaviorLearning stops
            // dropping every BlueChip sample at line 880.
            // V5.9.897 — add real liq/mcap buckets so AdaptiveLearningEngine
            // pattern keys partition BlueChip samples by actual size band.
            entryPattern  = "BLUECHIP_ENTRY",
            liqBucket     = com.lifecyclebot.engine.CanonicalPublishHelper.liqBucketFromUsd(pos.liquidityUsd),
            mcapBucket    = com.lifecyclebot.engine.CanonicalPublishHelper.mcapBucketFromUsd(pos.marketCapUsd),
        )

        // V5.9.401 — Sentience hook #4: cross-engine telegraph (MEME).
        try { com.lifecyclebot.engine.SentienceHooks.recordEngineOutcome("MEME", pnlSol, pnlPct >= 1.0) } catch (_: Exception) {}

        // V5.9.495z17 — wire operator-mandated 70/30 profit split.
        if (pnlSol > 0.0) {
            try {
                com.lifecyclebot.engine.TreasuryManager.contributeFromMemeSell(
                    pnlSol,
                    com.lifecyclebot.engine.WalletManager.lastKnownSolPrice,
                )
            } catch (_: Exception) {}
        }

        
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
        // V5.9.457 — mode-orphan fix: search both maps so TP/SL still fire
        // on positions opened in the opposite mode.
        var pos = synchronized(activePositions) { activePositions[mint] }
        if (pos == null) {
            val otherMap = if (isPaperMode) livePositions else paperPositions
            pos = synchronized(otherMap) { otherMap[mint] }
            if (pos != null) {
                ErrorLogger.warn(TAG, "🔵⚠ BLUECHIP MODE MISMATCH: ${pos.symbol} found in " +
                    "${if (isPaperMode) "LIVE" else "PAPER"} map — evaluating exit anyway")
            }
        }
        if (pos == null) return ExitSignal.HOLD
        pos.lastSeenPrice = currentPrice  // V5.9.392 — unified UI live P&L

        val pnlPct = com.lifecyclebot.engine.OpenPnlSanity.inspect(pos.entryPrice, currentPrice, context = "BlueChipTraderAI_6038/${mint.take(8)}", emit = true).takeIf { it.ok }?.pnlPct ?: 0.0
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000

        // V5.9.166: Track peak + high-water for laddered profit-lock.
        if (pnlPct > pos.peakPnlPct) pos.peakPnlPct = pnlPct
        if (currentPrice > pos.highWaterMark) pos.highWaterMark = currentPrice

        // V5.9.438 — HARD PEAK-DRAWDOWN LOCK (unconditional backstop).
        if (com.lifecyclebot.engine.PeakDrawdownLock.shouldLock(pos.peakPnlPct, pnlPct)) {
            ErrorLogger.warn(TAG, "🔵🔒🛑 PEAK-DRAWDOWN LOCK: ${pos.symbol} | " +
                "peak +${pos.peakPnlPct.toInt()}% → now +${pnlPct.toInt()}% " +
                "(gave back ≥${(com.lifecyclebot.engine.PeakDrawdownLock.DRAWDOWN_TRIGGER_FRAC * 100).toInt()}% of peak)")
            return ExitSignal.TRAILING_STOP
        }

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
        val _holdSec = (System.currentTimeMillis() - pos.entryTime) / 1000.0  // V5.9.835
        val profitFloor = com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(pos.peakPnlPct, holdSeconds = _holdSec)
        if (pnlPct < profitFloor) {
            ErrorLogger.info(TAG, "🔵🔒 FLOOR LOCK: ${pos.symbol} | peak +${pos.peakPnlPct.toInt()}% → +${pnlPct.toInt()}% < +${profitFloor.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }

        // V5.9.437 — LIVE HOLD-BUCKET GATE. Cut flat stale BlueChip bags
        // whose hold-duration bucket has proven net-losing expectancy.
        // V5.0.4160 — scratch-trap suppression now lives inside OutcomeGates
        // itself (centralised across all lanes), so per-caller checks removed.
        if (com.lifecyclebot.engine.OutcomeGates.earlyExitByHoldBucket(
                layer = "BLUECHIP", holdMinutes = holdMinutes, pnlPct = pnlPct)) {
            ErrorLogger.info(TAG, "🔵🧠 HOLD-BUCKET EARLY EXIT: ${pos.symbol} | ${pnlPct.toInt()}% after ${holdMinutes}min — history bleeds")
            return ExitSignal.TIME_EXIT
        }

        // ═══════════════════════════════════════════════════════════════════
        // STANDARD EXIT CONDITIONS (below the ladder)
        // ═══════════════════════════════════════════════════════════════════

        // 1. TAKE PROFIT - hit target
        // V5.9.899: skip hard-TP once the partial ladder has started — ladder
        // + trailing/profit-floor manage exits and let runners run past TP.
        if (pos.partialRungsTaken == 0 && pnlPct >= pos.takeProfitPct) {
            ErrorLogger.info(TAG, "🔵 TP HIT: ${pos.symbol} | +${pnlPct.toInt()}%")
            return ExitSignal.TAKE_PROFIT
        }

        // 2. STOP LOSS - hit limit
        if (pnlPct <= pos.stopLossPct) {
            ErrorLogger.info(TAG, "🔵 SL HIT: ${pos.symbol} | ${pnlPct.toInt()}%")
            return ExitSignal.STOP_LOSS
        }

        // 3. TIME EXIT - max hold exceeded and not significantly profitable
        // V5.9.437 — extend window for winners when TIME_EXIT historically bleeds this lane.
        val timeExitExt = com.lifecyclebot.engine.OutcomeGates.timeExitExtensionMult(
            layer = "BLUECHIP", exitReason = "TIME_EXIT", pnlPct = pnlPct)
        val effectiveMaxHold = (MAX_HOLD_MINUTES * timeExitExt).toLong()
        if (holdMinutes >= effectiveMaxHold && pnlPct < 8.0) {
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
        
        // V5.0.4224 — lane-local PAUSED is recovery-probe sizing, not a
        // hard entry amputation. Catastrophic drawdown remains governed by the
        // global SecurityGuard/KillSwitch; BlueChip keeps tiny quality samples.
        val dailyLossRecoveryProbe = mode == BlueChipMode.PAUSED
        if (dailyLossRecoveryProbe) {
            ErrorLogger.warn(TAG, "🔵 BLUECHIP_DAILY_LOSS_RECOVERY_PROBE_4224 — local loss cap hit; size×0.35")
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
        // V5.9.1467 — MCAP SANITY GUARD (spec item 6). A meme-sized fake pool can
        // masquerade as bluechip when a noisy feed reports a huge mcap on near-zero
        // liquidity (e.g. $10M mcap / $2k liq = a 5000:1 ratio that no real bluechip
        // ever has). Real large-caps carry liquidity roughly proportional to mcap.
        // If the claimed mcap is implausible for the observed liquidity, the mcap
        // signal is untrusted → ZERO the mcap points so bad metadata can't inflate a
        // token into the bluechip lane. Liquidity/V3 still score it on real data.
        val mcapLiqRatio = if (liquidityUsd > 0.0) marketCapUsd / liquidityUsd else Double.MAX_VALUE
        val mcapTrusted = liquidityUsd >= 25_000.0 && mcapLiqRatio <= 250.0
        val rawMcapScore = when {
            marketCapUsd >= 10_000_000 -> 20   // >$10M
            marketCapUsd >= 5_000_000 -> 18    // >$5M
            marketCapUsd >= 3_000_000 -> 15    // >$3M
            marketCapUsd >= 2_000_000 -> 12    // >$2M
            marketCapUsd >= 1_000_000 -> 10    // >$1M (minimum)
            else -> 0
        }
        val mcapScore = if (mcapTrusted) rawMcapScore else 0
        blueChipScore += mcapScore
        if (!mcapTrusted && rawMcapScore > 0) {
            scoreReasons.add("mcap+0(UNTRUSTED:ratio=${mcapLiqRatio.toInt()}:1,liq=\$${liquidityUsd.toInt()})")
        } else {
            scoreReasons.add("mcap+$mcapScore")
        }
        
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

        // ── V5.9.839 — VOLATILITY CHECK (-10 to +5 pts) ──
        // volatility was a function parameter silently dropped since the
        // engine shipped. BlueChip is THE quality lane — its whole thesis
        // is that >$1M mcap tokens should provide steadier rides. A blue-
        // chip showing >70 volatility is the worst-of-both-worlds setup:
        // big cap (limited upside) WITH meme-coin chaos (full downside).
        // Conversely, a calm blue-chip (vol 20-45) is exactly the
        // risk-adjusted profile this lane is supposed to capture.
        val volScore = when {
            volatility >= 80 -> -10  // chaos zone — wrong lane for chaos
            volatility >= 65 -> -5
            volatility >= 50 -> 0    // typical — neutral
            volatility >= 30 -> 5    // calm — bluechip sweet spot
            volatility >= 15 -> 3    // very calm — likely real demand floor
            else            -> 0     // dead-flat — possibly stale book, neutral
        }
        blueChipScore += volScore
        if (volScore != 0) scoreReasons.add("vol${if (volScore > 0) "+" else ""}$volScore")

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.933 — HARVARD BRAIN PATTERN MEMORY (Pass 3: BlueChip lane).
        //
        // BlueChip is the bot's steady-quality lane. Past-pattern memory of
        // which (v3, mcap, liq, buy, momentum, volatility) signatures
        // delivered vs disappointed is exactly the prior we want for a
        // 'minimize drawdown, capture steady upside' thesis. Bounded
        // [-4,+10]; fail-open per FDG doctrine.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val harvardSig = mapOf(
                "BLUECHIP_TRADER" to blueChipScore.coerceIn(0, 100),
                "V3_SCORE"        to v3Contribution,
                "MCAP"            to mcapScore,
                "LIQUIDITY"       to liqScore,
                "BUY_PRESSURE"    to buyScore,
                "MOMENTUM"        to momentumScore.coerceAtLeast(0),
                "VOLATILITY"      to volScore.coerceAtLeast(0),
            ).filterValues { it > 0 }
            val (harvardNudge, harvardReason) = EducationSubLayerAI.approvalBoostFor(harvardSig)
            if (harvardNudge != 0) {
                blueChipScore = (blueChipScore + harvardNudge).coerceAtLeast(0)
                scoreReasons.add("harvard${if (harvardNudge >= 0) "+" else ""}$harvardNudge")
                ErrorLogger.debug(TAG, "🎓 BLUECHIP HARVARD: nudge=${if (harvardNudge >= 0) "+" else ""}$harvardNudge | $harvardReason → score=$blueChipScore")
            }
            val harvardComponents = harvardSig.map { (k, v) ->
                ScoreComponent(name = k, value = v, reason = "bluechip_harvard")
            }
            EducationSubLayerAI.recordEntryScores(mint, harvardComponents)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

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
        val baseMinScore = getFluidScoreThreshold()
        val minConf = getFluidConfidenceThreshold()

        // V5.9.1328 — BLUECHIP REGIME GUARD (operator strategy tune; biggest absolute
        // bleeder in the snapshot: -0.5712 SOL, WR 6.7% n=30). BLUECHIP had NO regime
        // awareness and kept buying large/slow positions straight through a market-wide
        // DUMP (RegimeDetector reported regime=DUMP, market WR 3.9%). A bluechip-style
        // lane should stand down when the whole market is bleeding — not average down into
        // it. Soft-shape, not a veto: in DUMP we RAISE the entry bar (+18 to the score floor)
        // and shrink size hard; CHOP raises it modestly. The lane still trades the rare
        // strong setup, but stops paying for low-quality entries in a hostile tape.
        val regimeScorePenalty = try {
            when (com.lifecyclebot.engine.RegimeDetector.currentRegime()) {
                com.lifecyclebot.engine.RegimeDetector.Regime.DUMP -> 18
                com.lifecyclebot.engine.RegimeDetector.Regime.DEAD -> 18
                com.lifecyclebot.engine.RegimeDetector.Regime.CHOP -> 8
                else -> 0
            }
        } catch (_: Throwable) { 0 }
        val minScore = baseMinScore + regimeScorePenalty

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

        // V5.0.4315 — ScoreExpectancy is learned strategy memory, not hard
        // safety.  It may shrink BlueChip hard, but it must not zero-size the
        // lane before FDG/executor attribution. Catastrophic safety remains in
        // the pre-trade safety stack; this is bounded recovery-probe shaping.
        var expectancySoftSize4315 = 1.0
        if (com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("BLUECHIP", blueChipScore)) {
            val mean = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean("BLUECHIP", blueChipScore)
            val n = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketSamples("BLUECHIP", blueChipScore)
            expectancySoftSize4315 = 0.25
            ErrorLogger.info(TAG, "🔵📉 BLUECHIP_EXPECTANCY_RECOVERY_PROBE_4315: $symbol | score=$blueChipScore | " +
                "bucket μ=${"%+.1f".format(mean ?: 0.0)}% over n=$n trades — size×0.25, no hard reject")
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BLUECHIP_EXPECTANCY_RECOVERY_PROBE_4315") } catch (_: Throwable) {}
        }

        // ── V5.9.1348 — LOSING-PATTERN-MEMORY SOFT-SHAPE (shared-layer parity) ──
        // BlueChip fed LosingPatternMemory via the journal but never read it back.
        // Same soft-shape as ShitCoin/Moonshot/Quality: net-negative danger bucket
        // → size ×0.3 (no veto; FDG is final authority). Positive-mean danger
        // buckets stay full-size (doctrine: avg_win*WR > avg_loss*(1-WR)).
        var dangerSoftSize = 1.0
        run {
            val d = try { com.lifecyclebot.engine.LosingPatternMemory.stats("BLUECHIP", blueChipScore) } catch (_: Throwable) { null }
            if (d != null && d.isDangerous && d.meanPnl < 0.0) {
                val band = try { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(blueChipScore) } catch (_: Throwable) { "" }
                ErrorLogger.info(TAG, "🔵🧯 BLUECHIP_DANGER_BUCKET_SOFT: $symbol | band=$band score=$blueChipScore losses=${d.losses} wins=${d.wins} mean=${"%+.1f".format(d.meanPnl)}% — size×0.3, routing via FDG")
                dangerSoftSize = 0.3
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═══════════════════════════════════════════════════════════════════
        
        var positionSol = BASE_POSITION_SOL
        // V5.9.1328 — shrink BLUECHIP size in a hostile regime (pairs with the score-floor
        // raise above). DUMP/DEAD → x0.4, CHOP → x0.7. Normal/Bull untouched.
        try {
            val regimeSizeMult = when (com.lifecyclebot.engine.RegimeDetector.currentRegime()) {
                com.lifecyclebot.engine.RegimeDetector.Regime.DUMP,
                com.lifecyclebot.engine.RegimeDetector.Regime.DEAD -> 0.40
                com.lifecyclebot.engine.RegimeDetector.Regime.CHOP -> 0.70
                else -> 1.0
            }
            if (regimeSizeMult < 1.0) {
                positionSol *= regimeSizeMult
                ErrorLogger.info(TAG, "🔵🌧️ BLUECHIP REGIME_SIZE_DAMP $symbol | size×$regimeSizeMult (hostile regime)")
            }
        } catch (_: Throwable) { /* fail-open */ }
        
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

        // ── V5.9.879 — BehaviorAI sizing wire-up for BLUECHIP lane ──
        // Sibling commit to V5.9.878 (Quality) and V5.9.817 (Meme path).
        // BlueChip lane was sizing through confScale + compounding bonus
        // but never consulting BehaviorAI's adaptive aggression level.
        // After a loss streak, the global BehaviorAI auto-deescalates
        // aggression 5→2 (V5.9.816), but BlueChip kept sizing at full
        // confScale*compounding output — exactly the lane to protect
        // first during tilt because BlueChip uses LARGER positions
        // (BASE 0.15 vs Quality 0.10 vs Meme ~0.05).
        //
        // Composition order: confScale → compounding → BehaviorAI → cap
        // The cap (coerceIn 0.05..MAX_POSITION_SOL below) is the final
        // safety. Behavior multiplier sits BETWEEN compounding (which
        // can blow the size up) and the cap (which clamps it down).
        //
        // Per doctrine #86: bounded soft-shape, fail-open.
        var behaviorSizeMult = 1.0
        var behaviorGradeMult = 1.0
        try {
            val rawSize = com.lifecyclebot.v3.scoring.BehaviorAI.getSizingMultiplier()
            behaviorSizeMult = rawSize.coerceIn(0.5, 1.5)

            // Infer setup quality from blueChipScore (0-100 BlueChip scale).
            // Higher bar than Quality because BlueChip is bigger size + LIVE-eligible:
            //   85+ = A, 70+ = B, 55+ = C, else D.
            val inferredGrade = when {
                blueChipScore >= 85 -> "A"
                blueChipScore >= 70 -> "B"
                blueChipScore >= 55 -> "C"
                else -> "D"
            }
            val minGrade = com.lifecyclebot.v3.scoring.BehaviorAI.getMinQualityGrade()
            val gradeOrder = mapOf("A" to 5, "B" to 4, "C" to 3, "D" to 2, "F" to 1)
            val candidateRank = gradeOrder[inferredGrade] ?: 3
            val minRank = gradeOrder[minGrade.uppercase()] ?: 3
            if (candidateRank < minRank) {
                behaviorGradeMult = 0.7
            }
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        positionSol *= (behaviorSizeMult * behaviorGradeMult)

        // V5.9.926 — GLOBAL COMPOUND MULTIPLIER (Pass A fix).
        try {
            val globalMultiplier = com.lifecyclebot.engine.AutoCompoundEngine.getSizeMultiplier()
            if (globalMultiplier.isFinite() && globalMultiplier > 1.0) {
                positionSol *= globalMultiplier
                // BlueChip is the biggest-size lane — keep the *1.5 overshoot cap consistent
                positionSol = positionSol.coerceAtMost(MAX_POSITION_SOL * 1.5)
            }
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // V5.9.1305 — calibration-aware shrink for net-negative score bands.
        // BLUECHIP is a -0.23 SOL bleeder. It already hard-rejects via
        // shouldReject() but never SOFT-SHAPED on its own band-level mean PnL.
        // Wire the same 1257 calibration shrink the other lanes use so a band
        // proven net-negative (but not yet reject-territory) gets a smaller
        // position instead of a full-size one. Soft-shape, never a veto,
        // keyed on BLUECHIP's own learnt outcomes. Fail-open.
        try {
            val calMult = com.lifecyclebot.engine.ScoreExpectancyTracker.calibrationSizeMult("BLUECHIP", blueChipScore)
            if (calMult < 1.0) {
                positionSol *= calMult
                ErrorLogger.info(TAG, "🔵✨ BLUECHIP CALIBRATION_SHRINK $symbol | band=S$blueChipScore size×$calMult (net-negative band)")
            }
        } catch (_: Throwable) { /* fail-open */ }

        if (dailyLossRecoveryProbe) positionSol *= 0.35

        // V5.0.4328 — cache-only UltimateEdgeEngine readback for BlueChip.
        // Position open/close warms cards; entry only consumes cached state.
        try {
            val edgeCard4328 = com.lifecyclebot.engine.UltimateEdgeEngine.cached(mint, "BLUECHIP")
            if (edgeCard4328 != null) {
                val edgeBias4328 = edgeCard4328.scoreBias.coerceIn(0, 5)
                if (edgeBias4328 > 0) blueChipScore = (blueChipScore + edgeBias4328).coerceAtLeast(0)
                val edgeSize4328 = edgeCard4328.sizeMult.coerceIn(0.90, 1.08)
                positionSol *= edgeSize4328
                if (edgeSize4328 != 1.0) {
                    ErrorLogger.debug(TAG, "🔵🧠 ULTIMATE_EDGE_BLUECHIP_CACHE_SHAPE_4328: $symbol score+$edgeBias4328 size×${edgeSize4328.fmt(3)} ${edgeCard4328.semanticReason.take(90)}")
                }
            }
        } catch (_: Throwable) { /* fail-open cache read */ }

        // Cap at max
        positionSol *= expectancySoftSize4315
        positionSol *= dangerSoftSize  // V5.9.1348 danger soft-shape
        positionSol = positionSol.coerceIn(0.02, MAX_POSITION_SOL)
        
        // Get fluid take profit
        val takeProfitPct = getFluidTakeProfit()
        
        // Get fluid exits
        // V5.9.1286 — EVOLVED STOP WIDTH. BLUECHIP is the worst net bleeder
        // (WR 18% / net -0.630 SOL) precisely because its -4/-7% stop cuts
        // would-be runners on a lottery asset. StrategyHypothesisEngine A/B-tests
        // a stop-width multiplier on real settled PnL and promotes the winner.
        // Soft, bounded, fail-open — if the engine has no opinion the mult is 1.0.
        val _stopBias = try {
            com.lifecyclebot.engine.StrategyHypothesisEngine.getStopBias("BLUECHIP", blueChipScore, "ALL", mint)
        } catch (_: Throwable) { 1.0 }
        val stopLossPct = getFluidStopLoss() * _stopBias
        
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
        val raw = (baseConf + boost).toInt()
        // V5.9.966 — apply z38 LiveLayerGateRelaxer (was file-dead).
        return try {
            com.lifecyclebot.engine.LiveLayerGateRelaxer.relaxFloor(raw, "BLUECHIP", !isPaperMode)
        } catch (_: Throwable) { raw }
    }
    
    fun getFluidMinLiquidity(): Double = lerp(BC_LIQ_BOOTSTRAP, BC_LIQ_MATURE)
    fun getFluidTakeProfit(): Double {
        val base = lerp(TAKE_PROFIT_BOOTSTRAP, TAKE_PROFIT_MATURE)
        // V5.9.1287 — blend in the BACKTESTED optimal TP for this lane when the
        // HistoricalChartScanner has real winning-pattern data. Previously the
        // scanner ran at startup and computed getOptimalExitParams but NOTHING
        // consumed it — pure dormant edge. 70/30 blend keeps the fluid base in
        // charge while letting replay evidence pull the target toward what
        // actually banked. Fail-open: no data → base unchanged.
        val blended = try {
            val opt = com.lifecyclebot.engine.HistoricalChartScanner.getOptimalExitParams("BLUECHIP")
            if (opt.hasData) (base * 0.7 + opt.takeProfitPct * 0.3) else base
        } catch (_: Throwable) { base }
        // V5.9.1380 — closed-loop tuner overlay
        return blended * com.lifecyclebot.engine.learning.LaneExitTuner.getTpMult("BLUECHIP")
    }
    fun getFluidStopLoss(): Double {
        val base = lerp(STOP_LOSS_BOOTSTRAP, STOP_LOSS_MATURE)  // negative
        val tuned = base * com.lifecyclebot.engine.learning.LaneExitTuner.getSlMult("BLUECHIP")
        return maxOf(tuned, -15.0)  // hard floor sacred
    }
    
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
