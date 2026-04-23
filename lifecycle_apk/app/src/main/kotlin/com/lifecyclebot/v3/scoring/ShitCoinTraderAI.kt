package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.AutoCompoundEngine
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SHITCOIN TRADER AI - "DEGEN MODE" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * A specialized trading layer for the lowest-cap memecoin market:
 * - Pump.fun launches
 * - Raydium new pools
 * - Moonshot/Bonk-style micro caps
 * - Tokens with <$50K market cap
 * 
 * This layer is ISOLATED from the main V3 engine to protect the portfolio
 * from the high-risk/high-reward nature of shitcoins while still capturing
 * potential 100x+ opportunities.
 * 
 * KEY DIFFERENCES FROM OTHER LAYERS:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Market Cap: MAXIMUM $50K (opposite of Blue Chip's $250k minimum)
 * 2. Execution Speed: ULTRA-FAST (avoid MEV/rugs)
 * 3. Risk Management: TIGHT stops, QUICK exits
 * 4. Evaluation Focus: Dev history, bundle detection, social signals, rug indicators
 * 5. Position Sizing: SMALL (never risk more than we can lose)
 * 
 * SPECIALIZED EVALUATION CRITERIA:
 * ─────────────────────────────────────────────────────────────────────────────
 * - Dev wallet history (has dev rugged before?)
 * - Bundle/holder concentration (80%+ dev hold = danger)
 * - Social signals (Twitter/X, Telegram, website quality)
 * - Graduation threshold (pump.fun → raydium migration)
 * - DEX boost/trending status
 * - Copy/scam detection (is this a copycat token?)
 * - Launch platform icon (pump.fun, raydium, moonshot)
 * 
 * GOALS:
 * - Catch pre-graduation pumps on pump.fun
 * - Quick scalps on fresh launches (+20-100%)
 * - Identify bundled coins that super pump (80% dev hold → millions in 3 candles)
 * - Ultra-fast execution to avoid getting rugged/MEV'd
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ShitCoinTraderAI {
    
    private const val TAG = "ShitCoinAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - Tailored for micro-cap memecoins
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Market cap filter - V5.2.12: Extended to $100K to flow into Quality layer
    // ShitCoin: $0 - $100K (degen memes and early plays)
    // Quality picks up at $100K+
    private const val MAX_MARKET_CAP_USD = 100_000.0   // V5.2.12: Raised from $50K to flow into Quality
    private const val MIN_MARKET_CAP_USD = 1_000.0    // V5.2.12: Lowered from $2K for newer tokens
    
    // Liquidity requirements — V5.5: Hard $5K minimum across all phases
    private const val MIN_LIQUIDITY_USD_BOOTSTRAP = 3_000.0   // V5.2.12: Paper mode can explore lower liq
    private const val MIN_LIQUIDITY_USD_MATURE = 5_000.0      // V5.5: $5K hard floor when mature
    
    // Position sizing - V5.6: DYNAMIC scaling based on wallet balance
    private const val BASE_POSITION_SOL = 0.05        // Very small base (0.05 SOL ~ $7.50)
    private const val MAX_POSITION_SOL = 0.30         // V5.6: Raised from 0.20 - bigger wallet = bigger positions
    private const val MAX_CONCURRENT_POSITIONS = 12   // V5.2.12: Raised from 5 for paper learning
    private const val WALLET_SCALE_FACTOR = 0.02      // V5.6: 2% of wallet per shitcoin position
    
    // V4.20: Removed daily loss limit - ShitCoin is now primary layer
    // Loss prevention is handled by global stop-loss and position sizing
    // private const val DAILY_MAX_LOSS_SOL = 0.5     // REMOVED - let it trade freely
    
    // Take profit / Stop loss - FLUID (adapts as bot learns)
    // Bootstrap: Tighter exits (quick wins, tight stops)
    // Mature: Wider targets (let winners run, learned patterns)
    // V5.9: Achievable TPs scaled to meme token reality.
    // Bootstrap TP=8% → at 25% progress lerp gives ~10% TP vs -5% SL.
    // That's achievable on momentum entries (break-even ~39% win rate, target 45%).
    // Mature TP=20%: known-good patterns run further. Expert (via FluidLearning 1.0) = 20%.
    private const val TAKE_PROFIT_BOOTSTRAP = 8.0     // V5.9: 8% — achievable quick win on meme pump
    private const val TAKE_PROFIT_MATURE = 20.0       // V5.9: 20% — proven patterns can run further
    private const val STOP_LOSS_BOOTSTRAP = -5.0      // Slightly wider for wick noise at bootstrap
    private const val STOP_LOSS_MATURE = -6.0         // V5.9: Tighter SL as entries improve with patterns
    private const val TRAILING_STOP_PCT = 8.0         // Tighter trailing for volatile moves
    // V5.2: REMOVED max hold time - ShitCoins can moon anytime, let them run!
    private const val FLAT_EXIT_MINUTES = 8           // V5.2: Increased to 8 mins (was 5)
    
    // Compounding - Conservative for shitcoins
    private const val COMPOUNDING_ENABLED = true
    private const val COMPOUNDING_RATIO = 0.2         // Only 20% of profits compound (protect gains)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SHITCOIN-SPECIFIC THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Bundle/holder detection - Critical for rug protection
    private const val MAX_DEV_HOLD_PCT = 40.0         // >40% dev hold = likely rug
    private const val BUNDLE_DANGER_PCT = 80.0        // 80%+ in few wallets = bundled (but can moon!)
    
    // Social/legitimacy signals
    private const val MIN_SOCIAL_SCORE = 20           // Minimum social presence (0-100)
    
    // Age requirements - Fresh tokens only
    private const val MAX_TOKEN_AGE_HOURS = 6.0       // Only tokens <6 hours old (mature)
    // V5.9.160: tokenAgeMinutes in BotService is counted from addedToWatchlistAt,
    // i.e. the moment the bot FIRST DISCOVERED the token, not the token's real
    // on-chain creation time. That means once the bot has been running 6h+,
    // every single token discovered in the first hour is silently TOO_OLD'd —
    // a massive hidden volume throttle that grows worse the longer you run.
    // Bootstrap: lift the ceiling to 3 days so the learner gets full flow.
    private const val MAX_TOKEN_AGE_HOURS_BOOTSTRAP = 72.0
    private fun effectiveMaxTokenAgeHours(): Double = try {
        if (FluidLearningAI.getLearningProgress() < 0.40) MAX_TOKEN_AGE_HOURS_BOOTSTRAP
        else MAX_TOKEN_AGE_HOURS
    } catch (_: Exception) { MAX_TOKEN_AGE_HOURS }
    private const val OPTIMAL_AGE_MINUTES = 30.0      // Sweet spot: 30 mins - 2 hours
    
    // Graduation detection (pump.fun specific)
    private const val GRADUATION_MCAP_THRESHOLD = 30_000.0  // Pump.fun graduation at ~$30K
    
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
    private val paperBalanceBps = AtomicLong(100)     // Paper shitcoin balance (1 SOL start)
    private val liveBalanceBps = AtomicLong(0)        // Live shitcoin balance
    
    // Active positions
    private val livePositions = ConcurrentHashMap<String, ShitCoinPosition>()
    private val paperPositions = ConcurrentHashMap<String, ShitCoinPosition>()
    
    // Dev wallet tracking (learn from rugs)
    private val ruggedDevs = ConcurrentHashMap<String, RugRecord>()
    private val trustedDevs = ConcurrentHashMap<String, Int>()  // dev -> successful launches
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE - V5.6.29c
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("shitcoin_trader_ai", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "💩 ShitCoinTraderAI persistence initialized")
    }
    
    private fun restore() {
        val p = prefs ?: return
        paperBalanceBps.set(p.getLong("paperBalanceBps", 100))
        liveBalanceBps.set(p.getLong("liveBalanceBps", 0))
        
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyPnlSolBps.set(p.getLong("dailyPnlSolBps", 0))
            dailyWins.set(p.getInt("dailyWins", 0))
            dailyLosses.set(p.getInt("dailyLosses", 0))
            dailyTradeCount.set(p.getInt("dailyTradeCount", 0))
        }
        ErrorLogger.info(TAG, "💩 RESTORED: paperBal=${paperBalanceBps.get()/100.0} | wins=${dailyWins.get()} losses=${dailyLosses.get()}")
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ShitCoinPosition(
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
        val launchPlatform: LaunchPlatform,
        val devWallet: String? = null,
        val bundlePct: Double = 0.0,
        val socialScore: Int = 0,
        var highWaterMark: Double = entryPrice,
        var trailingStop: Double = entryPrice * (1 - abs(stopLossPct) / 100),
        var firstTakeDone: Boolean = false,  // V4.1.3: Track if first partial take was done
        var partialRungsTaken: Int = 0,      // V5.9.163: laddered partial-take index
        var peakPnlPct: Double = 0.0,        // V5.9.163: track peak for floor-lock
    )
    
    data class ShitCoinSignal(
        val shouldEnter: Boolean,
        val positionSizeSol: Double,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val confidence: Int,
        val reason: String,
        val mode: ShitCoinMode,
        val isPaperMode: Boolean,
        val launchPlatform: LaunchPlatform,
        val riskLevel: RiskLevel,
        val socialScore: Int = 0,
        val bundleWarning: Boolean = false,
        val graduationImminent: Boolean = false,
    )
    
    data class RugRecord(
        val devWallet: String,
        val mint: String,
        val symbol: String,
        val rugTime: Long,
        val lossPercent: Double,
    )
    
    enum class LaunchPlatform(val emoji: String, val displayName: String) {
        PUMP_FUN("🎰", "Pump.fun"),
        RAYDIUM("🌊", "Raydium"),
        MOONSHOT("🌙", "Moonshot"),
        BONK_BOT("🐕", "BonkBot"),
        UNKNOWN("❓", "Unknown"),
    }
    
    enum class ShitCoinMode {
        HUNTING,      // Actively scanning for plays
        POSITIONED,   // Have positions, managing them
        CAUTIOUS,     // Near daily loss limit
        PAUSED,       // Hit daily loss limit
        GRADUATION,   // Watching for pump.fun graduations
    }
    
    enum class RiskLevel(val emoji: String, val maxPosition: Double) {
        LOW("🟢", 0.15),      // Good signals, reasonable risk
        MEDIUM("🟡", 0.10),   // Mixed signals
        HIGH("🟠", 0.07),     // Risky but potential
        EXTREME("🔴", 0.05),  // Very risky (bundle detected, etc.)
        MOON_OR_ZERO("🚀", 0.05), // Bundle pump - either 100x or 0
    }
    
    enum class ExitSignal {
        TAKE_PROFIT,
        PARTIAL_TAKE,    // V4.1.3: Take 25%, let rest ride
        STOP_LOSS,
        TRAILING_STOP,
        TIME_EXIT,
        RUG_DETECTED,
        DEV_SELL,
        HOLD,
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.0 CRITICAL: Flag to prevent re-initialization during runtime
    @Volatile
    private var initialized = false
    
    fun init(paperMode: Boolean, startingBalanceSol: Double = 1.0) {
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
        ErrorLogger.info(TAG, "💩 ShitCoin Trader initialized (ONE-TIME) | " +
            "mode=${if (paperMode) "PAPER" else "LIVE"} | " +
            "balance=${getBalance(paperMode).fmt(4)} SOL | " +
            "maxMcap=\$${(MAX_MARKET_CAP_USD/1_000).toInt()}K")
    }
    
    fun resetDaily() {
        dailyPnlSolBps.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyTradeCount.set(0)
        ErrorLogger.info(TAG, "💩 ShitCoin daily stats reset")
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
                ErrorLogger.info(TAG, "💩 TRANSFER: Balance ${paperBal/100.0} SOL from PAPER to LIVE")
            }
        }
    }
    
    fun isEnabled(): Boolean = true
    
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
        ErrorLogger.info(TAG, "💩 SHITCOIN +${profitSol.fmt(4)} SOL | " +
            "Total ${if (isPaper) "PAPER" else "LIVE"}: ${getBalance(isPaper).fmt(4)} SOL")
    }
    
    fun getDailyPnlSol(): Double = dailyPnlSolBps.get() / 100.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val activePositions: ConcurrentHashMap<String, ShitCoinPosition>
        get() = if (isPaperMode) paperPositions else livePositions
    
    fun getActivePositions(): List<ShitCoinPosition> {
        return synchronized(activePositions) {
            activePositions.values.toList()
        }
    }
    
    fun getActivePositionsForMode(isPaper: Boolean): List<ShitCoinPosition> {
        val positions = if (isPaper) paperPositions else livePositions
        return synchronized(positions) {
            positions.values.toList()
        }
    }
    
    fun hasPosition(mint: String): Boolean = activePositions.containsKey(mint)

    // Called by BotService after executing a PARTIAL_TAKE sell to confirm the flag is set
    fun markFirstTakeDone(mint: String) {
        synchronized(activePositions) { activePositions[mint] }?.firstTakeDone = true
    }

    /**
     * V5.2: Force clear all positions on bot stop
     * This ensures UI updates correctly even if individual closes fail
     */
    fun clearAllPositions() {
        synchronized(activePositions) {
            val count = activePositions.size
            activePositions.clear()
            ErrorLogger.info(TAG, "💩 CLEARED $count ShitCoin positions on shutdown")
        }
    }
    
    fun addPosition(position: ShitCoinPosition) {
        synchronized(activePositions) {
            activePositions[position.mint] = position
        }
        dailyTradeCount.incrementAndGet()
        
        // V4.1.3: Start ultra-fast rug monitoring
        UltraFastRugDetectorAI.startMonitoring(
            mint = position.mint,
            symbol = position.symbol,
            entryPrice = position.entryPrice,
            entryLiquidity = position.liquidityUsd,
            entryHolders = 0,  // Will be updated by scanner
        )
        
        ErrorLogger.info(TAG, "💩 SHITCOIN ENTRY: ${position.symbol} | " +
            "${position.launchPlatform.emoji} ${position.launchPlatform.displayName} | " +
            "mcap=\$${(position.marketCapUsd/1_000).fmt(1)}K | " +
            "liq=\$${(position.liquidityUsd/1000).fmt(0)}K | " +
            "size=${position.entrySol.fmt(4)} SOL | " +
            "TP=${position.takeProfitPct.fmt(0)}% SL=${position.stopLossPct.fmt(0)}%")
    }
    
    fun closePosition(mint: String, exitPrice: Double, exitReason: ExitSignal) {
        val pos = synchronized(activePositions) { activePositions.remove(mint) } ?: return
        
        // V4.1.3: Stop rug monitoring
        UltraFastRugDetectorAI.stopMonitoring(mint)
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.2 FIX: RELEASE TRADE AUTHORIZER LOCK
        // This allows the token to be re-entered or promoted to another layer
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.engine.TradeAuthorizer.releasePosition(
                mint = mint,
                reason = "SHITCOIN_${exitReason.name}",
                book = com.lifecyclebot.engine.TradeAuthorizer.ExecutionBook.SHITCOIN
            )
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.debug(TAG, "Failed to release ShitCoin lock: ${e.message}")
        }
        
        val pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100
        val pnlSol = pos.entrySol * pnlPct / 100
        
        // Record to daily P&L
        val pnlBps = (pnlSol * 100).toLong()
        dailyPnlSolBps.addAndGet(pnlBps)
        
        if (pnlSol > 0) {
            dailyWins.incrementAndGet()
            addToBalance(pnlSol * COMPOUNDING_RATIO, pos.isPaper) // Compound portion
            
            // Track successful dev if we know them
            pos.devWallet?.let { dev ->
                trustedDevs[dev] = (trustedDevs[dev] ?: 0) + 1
            }
        } else {
            dailyLosses.incrementAndGet()
            
            // Track rugged dev if massive loss
            if (pnlPct <= -50 && pos.devWallet != null) {
                ruggedDevs[pos.devWallet!!] = RugRecord(
                    devWallet = pos.devWallet!!,
                    mint = pos.mint,
                    symbol = pos.symbol,
                    rugTime = System.currentTimeMillis(),
                    lossPercent = pnlPct
                )
                ErrorLogger.warn(TAG, "💀 DEV RUGGED: ${pos.devWallet} | ${pos.symbol} | ${pnlPct.toInt()}%")
            }
        }
        
        // Record to FluidLearningAI for maturity
        try {
            val isWin = pnlPct > 0
            if (pos.isPaper) {
                FluidLearningAI.recordPaperTrade(isWin)
            } else {
                FluidLearningAI.recordLiveTrade(isWin)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }
        
        // V5.6.29c: Persist after trade
        save()
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        val modeLabel = if (pos.isPaper) "PAPER" else "LIVE"
        
        ErrorLogger.info(TAG, "💩 SHITCOIN CLOSED [$modeLabel]: ${pos.symbol} | " +
            "${pos.launchPlatform.emoji} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%) | " +
            "reason=$exitReason | Daily: ${dailyPnl.fmt(4)} SOL | " +
            "Balance: ${getCurrentBalance().fmt(4)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION - ShitCoin specific scoring
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun evaluate(
        mint: String,
        symbol: String,
        currentPrice: Double,
        marketCapUsd: Double,
        liquidityUsd: Double,
        topHolderPct: Double,
        buyPressurePct: Double,
        momentum: Double,
        volatility: Double,
        tokenAgeMinutes: Double,
        launchPlatform: LaunchPlatform,
        // Shitcoin-specific metrics
        devWallet: String? = null,
        devHoldPct: Double = 0.0,
        bundlePct: Double = 0.0,
        socialScore: Int = 0,
        hasWebsite: Boolean = false,
        hasTwitter: Boolean = false,
        hasTelegram: Boolean = false,
        hasGithub: Boolean = false,
        isDexBoosted: Boolean = false,
        dexTrendingRank: Int = 0,
        isCopyCat: Boolean = false,
        graduationProgress: Double = 0.0,  // 0-100% for pump.fun
    ): ShitCoinSignal {
        
        // Check current mode
        val mode = getCurrentMode()
        
        // If paused (hit daily loss), reject everything
        if (mode == ShitCoinMode.PAUSED) {
            return ShitCoinSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = 0,
                reason = "PAUSED: Daily loss limit reached",
                mode = mode,
                isPaperMode = isPaperMode,
                launchPlatform = launchPlatform,
                riskLevel = RiskLevel.EXTREME,
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SHITCOIN FILTERS - Quality gates
        // ═══════════════════════════════════════════════════════════════════
        
        // 1. MARKET CAP FILTER - Must be in the shitcoin zone
        // V5.9.160: treat marketCapUsd == 0 as UNKNOWN, not "below floor".
        // Fresh pump.fun / DexScreener trending entries often arrive without
        // a populated mcap number and the MCAP_TOO_LOW reject was silently
        // eating the entire ShitCoin signal for the fresh-meme flow.
        if (marketCapUsd > MAX_MARKET_CAP_USD) {
            return rejectSignal("MCAP_TOO_HIGH: \$${(marketCapUsd/1000).toInt()}K > \$${(MAX_MARKET_CAP_USD/1000).toInt()}K", mode, launchPlatform)
        }

        if (marketCapUsd > 0.0 && marketCapUsd < MIN_MARKET_CAP_USD) {
            return rejectSignal("MCAP_TOO_LOW: \$${marketCapUsd.toInt()} < \$${MIN_MARKET_CAP_USD.toInt()}", mode, launchPlatform)
        }
        
        // 2. LIQUIDITY FILTER - Must have minimum liquidity
        val minLiq = getFluidMinLiquidity()
        if (liquidityUsd < minLiq) {
            return rejectSignal("LIQ_TOO_LOW: \$${liquidityUsd.toInt()} < \$${minLiq.toInt()}", mode, launchPlatform)
        }
        
        // 3. MAX POSITIONS CHECK
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            return rejectSignal("MAX_POSITIONS: ${activePositions.size}/$MAX_CONCURRENT_POSITIONS", mode, launchPlatform)
        }
        
        // 4. ALREADY HAVE POSITION
        if (hasPosition(mint)) {
            return rejectSignal("ALREADY_POSITIONED", mode, launchPlatform)
        }
        
        // 5. KNOWN RUGGER CHECK
        if (devWallet != null && ruggedDevs.containsKey(devWallet)) {
            return rejectSignal("KNOWN_RUGGER: ${devWallet.take(8)}...", mode, launchPlatform)
        }
        
        // 6. COPYCAT CHECK
        if (isCopyCat) {
            return rejectSignal("COPYCAT_SCAM_DETECTED", mode, launchPlatform)
        }
        
        // 7. TOKEN AGE CHECK - Only fresh tokens (fluid during bootstrap)
        val maxAgeHours = effectiveMaxTokenAgeHours()
        if (tokenAgeMinutes > maxAgeHours * 60) {
            return rejectSignal("TOO_OLD: ${(tokenAgeMinutes/60).toInt()}h > ${maxAgeHours.toInt()}h", mode, launchPlatform)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SHITCOIN SCORING - Degen-focused
        // ═══════════════════════════════════════════════════════════════════
        
        var shitScore = 0
        var shitConfidence = 0
        val scoreReasons = mutableListOf<String>()
        var riskLevel = RiskLevel.MEDIUM
        var bundleWarning = false
        var graduationImminent = false
        
        // 1. LIQUIDITY SCORE (0-15 pts)
        val liqScore = when {
            liquidityUsd >= 20_000 -> 15
            liquidityUsd >= 10_000 -> 12
            liquidityUsd >= 5_000 -> 10
            liquidityUsd >= 3_000 -> 7
            else -> 3
        }
        shitScore += liqScore
        scoreReasons.add("liq+$liqScore")
        
        // 2. BUY PRESSURE SCORE (0-20 pts) - Critical for memecoins
        val buyScore = when {
            buyPressurePct >= 75 -> 20
            buyPressurePct >= 65 -> 17
            buyPressurePct >= 55 -> 13
            buyPressurePct >= 50 -> 10
            buyPressurePct >= 45 -> 7
            else -> 3
        }
        shitScore += buyScore
        scoreReasons.add("buy+$buyScore")
        
        // 3. MOMENTUM SCORE (-15 to +20 pts)
        val momentumScore = when {
            momentum >= 20 -> 20   // Parabolic!
            momentum >= 10 -> 15
            momentum >= 5 -> 10
            momentum >= 2 -> 7
            momentum >= 0 -> 3
            momentum >= -5 -> 0
            else -> -15
        }
        shitScore += momentumScore
        if (momentumScore != 0) scoreReasons.add("mom${if(momentumScore>0)"+" else ""}$momentumScore")
        
        // 4. TOKEN AGE SCORE (0-15 pts) - Sweet spot is 30 mins - 2 hours
        val ageScore = when {
            tokenAgeMinutes < 5 -> 5       // Too fresh - risky
            tokenAgeMinutes < 30 -> 10     // Early - good
            tokenAgeMinutes < 120 -> 15    // Sweet spot
            tokenAgeMinutes < 240 -> 10    // Still ok
            else -> 5                       // Getting old
        }
        shitScore += ageScore
        scoreReasons.add("age+$ageScore")
        
        // 5. PLATFORM BONUS (0-10 pts)
        val platformScore = when (launchPlatform) {
            LaunchPlatform.PUMP_FUN -> 10    // Pump.fun has most action
            LaunchPlatform.RAYDIUM -> 8
            LaunchPlatform.MOONSHOT -> 7
            LaunchPlatform.BONK_BOT -> 5
            LaunchPlatform.UNKNOWN -> 2
        }
        shitScore += platformScore
        scoreReasons.add("plat+$platformScore")
        
        // 6. SOCIAL SIGNALS (0-15 pts)
        var socialBonus = 0
        if (hasTwitter) socialBonus += 5
        if (hasWebsite) socialBonus += 4
        if (hasTelegram) socialBonus += 3
        if (hasGithub) socialBonus += 3
        socialBonus = socialBonus.coerceAtMost(15)
        shitScore += socialBonus
        if (socialBonus > 0) scoreReasons.add("social+$socialBonus")
        
        // 7. DEX BOOST/TRENDING BONUS (0-10 pts)
        var dexBonus = 0
        if (isDexBoosted) dexBonus += 5
        if (dexTrendingRank in 1..10) dexBonus += 10
        else if (dexTrendingRank in 11..50) dexBonus += 5
        dexBonus = dexBonus.coerceAtMost(10)
        shitScore += dexBonus
        if (dexBonus > 0) scoreReasons.add("dex+$dexBonus")
        
        // 8. GRADUATION BONUS (pump.fun specific) (0-15 pts)
        if (launchPlatform == LaunchPlatform.PUMP_FUN && graduationProgress > 0) {
            val gradScore = when {
                graduationProgress >= 90 -> {
                    graduationImminent = true
                    15  // About to graduate = big pump incoming
                }
                graduationProgress >= 70 -> 12
                graduationProgress >= 50 -> 8
                graduationProgress >= 30 -> 5
                else -> 2
            }
            shitScore += gradScore
            scoreReasons.add("grad+$gradScore")
        }
        
        // 9. HOLDER CONCENTRATION PENALTY/BONUS (-20 to +10 pts)
        // This is nuanced: high concentration is risky BUT bundled coins can moon
        val holderScore = when {
            bundlePct >= BUNDLE_DANGER_PCT -> {
                bundleWarning = true
                riskLevel = RiskLevel.MOON_OR_ZERO
                // Bundled coins: Either 100x or 0 - high risk high reward
                if (momentum >= 10 && buyPressurePct >= 60) {
                    5  // Bundle is pumping = potential moonshot
                } else {
                    -10  // Bundle not pumping = likely rug
                }
            }
            devHoldPct > MAX_DEV_HOLD_PCT -> {
                riskLevel = RiskLevel.HIGH
                -15  // Too much dev control
            }
            topHolderPct > 30 -> -10  // Whale risk
            topHolderPct > 20 -> -5
            topHolderPct <= 10 -> 10   // Well distributed
            else -> 0
        }
        shitScore += holderScore
        if (holderScore != 0) scoreReasons.add("hold${if(holderScore>0)"+" else ""}$holderScore")
        
        // 10. TRUSTED/KNOWN DEV BONUS (0-10 pts)
        if (devWallet != null) {
            val devTrustLevel = trustedDevs[devWallet] ?: 0
            val devBonus = when {
                devTrustLevel >= 3 -> 10   // 3+ successful launches
                devTrustLevel >= 2 -> 7
                devTrustLevel >= 1 -> 4
                else -> 0
            }
            if (devBonus > 0) {
                shitScore += devBonus
                scoreReasons.add("dev+$devBonus")
            }
        }
        
        // Calculate overall confidence
        shitConfidence = (
            (if (liquidityUsd > 5_000) 20 else 10) +
            (if (buyPressurePct > 50) 20 else 10) +
            (if (momentum > 0) 20 else 10) +
            (if (topHolderPct < 30) 20 else 10) +
            (if (socialBonus > 5) 20 else 10)
        ).coerceIn(0, 100)
        
        // Adjust risk level based on total score
        if (riskLevel == RiskLevel.MEDIUM) {
            riskLevel = when {
                shitScore >= 70 && shitConfidence >= 60 -> RiskLevel.LOW
                shitScore >= 50 && shitConfidence >= 50 -> RiskLevel.MEDIUM
                shitScore >= 30 -> RiskLevel.HIGH
                else -> RiskLevel.EXTREME
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID THRESHOLDS - Bootstrap to Mature
        // ═══════════════════════════════════════════════════════════════════
        
        val learningProgress = FluidLearningAI.getLearningProgress()
        
        // Shitcoin thresholds - stricter due to high risk
        val minScore = getFluidScoreThreshold()
        val minConf = getFluidConfidenceThreshold()
        
        // Check thresholds
        val passesScore = shitScore >= minScore
        val passesConf = shitConfidence >= minConf
        
        if (!passesScore || !passesConf) {
            return ShitCoinSignal(
                shouldEnter = false,
                positionSizeSol = 0.0,
                takeProfitPct = 0.0,
                stopLossPct = 0.0,
                confidence = shitConfidence,
                reason = "THRESHOLD_FAIL: score=$shitScore<$minScore conf=$shitConfidence<$minConf",
                mode = mode,
                isPaperMode = isPaperMode,
                launchPlatform = launchPlatform,
                riskLevel = riskLevel,
                socialScore = socialBonus,
                bundleWarning = bundleWarning,
                graduationImminent = graduationImminent,
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING - V5.6: DYNAMIC scaling with wallet balance
        // 
        // Problem: User had 12 SOL but shitcoin was taking tiny 0.05 SOL entries
        // Solution: Scale position with balance
        //   1 SOL wallet  → 0.05 SOL base
        //   5 SOL wallet  → 0.10 SOL (2% = 0.10)
        //   10 SOL wallet → 0.20 SOL (2% = 0.20)
        //   20 SOL wallet → 0.30 SOL (capped)
        // ═══════════════════════════════════════════════════════════════════
        
        val currentBalance = getCurrentBalance()
        val walletBasedSize = currentBalance * WALLET_SCALE_FACTOR
        var positionSol = maxOf(BASE_POSITION_SOL, walletBasedSize)
        
        // Scale by confidence
        val confScale = shitConfidence / 100.0
        positionSol *= (0.6 + confScale * 0.8)  // 60-140% of base
        
        // Risk level caps (V5.6: scale caps with wallet too)
        val riskCap = riskLevel.maxPosition * (1 + currentBalance * 0.01).coerceIn(1.0, 2.0)
        positionSol = positionSol.coerceAtMost(riskCap)
        
        // Compounding bonus (conservative for shitcoins)
        if (COMPOUNDING_ENABLED) {
            val balance = getCurrentBalance()
            if (balance > 0) {
                val compoundBonus = balance * COMPOUNDING_RATIO * confScale
                positionSol += compoundBonus.coerceAtMost(0.05)  // Max 0.05 SOL from compounding
            }
        }
        
        // Cap at max
        positionSol = positionSol.coerceIn(0.02, MAX_POSITION_SOL)
        
        // V4.20: Apply AutoCompoundEngine global size multiplier
        // This multiplier grows as the compound pool accumulates from winning trades
        val globalMultiplier = AutoCompoundEngine.getSizeMultiplier()
        if (globalMultiplier > 1.0) {
            positionSol *= globalMultiplier
            positionSol = positionSol.coerceAtMost(MAX_POSITION_SOL * 1.5) // Cap overshoot
            ErrorLogger.debug(TAG, "💩 GLOBAL COMPOUND BOOST: ${globalMultiplier.fmt(2)}x → pos=${positionSol.fmt(4)}SOL")
        }
        
        // Get fluid take profit and stop loss
        val takeProfitPct = getFluidTakeProfit()
        val baseStopLoss = getFluidStopLoss()
        
        // Bundle trades get tighter stops (70% of normal)
        val effectiveStopLoss = if (bundleWarning) {
            baseStopLoss * 0.7  // Tighter stop for bundles
        } else {
            baseStopLoss
        }
        
        ErrorLogger.info(TAG, "💩 SHITCOIN QUALIFIED: $symbol | " +
            "${launchPlatform.emoji} ${launchPlatform.displayName} | " +
            "score=$shitScore conf=$shitConfidence% | " +
            "risk=${riskLevel.emoji}${riskLevel.name} | " +
            "mcap=\$${(marketCapUsd/1_000).fmt(1)}K | " +
            "size=${positionSol.fmt(4)} SOL | " +
            "TP=${takeProfitPct.fmt(0)}% SL=${effectiveStopLoss.toInt()}%")
        
        return ShitCoinSignal(
            shouldEnter = true,
            positionSizeSol = positionSol,
            takeProfitPct = takeProfitPct,
            stopLossPct = effectiveStopLoss,
            confidence = shitConfidence,
            reason = "QUALIFIED: ${scoreReasons.joinToString(" ")}",
            mode = mode,
            isPaperMode = isPaperMode,
            launchPlatform = launchPlatform,
            riskLevel = riskLevel,
            socialScore = socialBonus,
            bundleWarning = bundleWarning,
            graduationImminent = graduationImminent,
        )
    }
    
    private fun rejectSignal(reason: String, mode: ShitCoinMode, platform: LaunchPlatform): ShitCoinSignal {
        return ShitCoinSignal(
            shouldEnter = false,
            positionSizeSol = 0.0,
            takeProfitPct = 0.0,
            stopLossPct = 0.0,
            confidence = 0,
            reason = reason,
            mode = mode,
            isPaperMode = isPaperMode,
            launchPlatform = platform,
            riskLevel = RiskLevel.EXTREME,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(mint: String, currentPrice: Double): ExitSignal {
        val pos = synchronized(activePositions) { activePositions[mint] } ?: return ExitSignal.HOLD
        
        val pnlPct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val holdMinutes = (System.currentTimeMillis() - pos.entryTime) / 60000
        
        // Update high water mark and trailing stop
        if (currentPrice > pos.highWaterMark) {
            pos.highWaterMark = currentPrice
            // V5.9.169 — continuous fluid trail (smooth log curve).
            val dynamicTrailPct = FluidLearningAI.fluidTrailPct(pnlPct)
            pos.trailingStop = currentPrice * (1 - dynamicTrailPct / 100)
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // V4.1.3: MOONSHOT EXIT STRATEGY + ULTRA-FAST RUG DETECTION
        // 
        // NO HARD CAPS! Let runners run with trailing stops and partial takes.
        // 
        // Strategy: Take 25% at each milestone, let 75% ride with trailing stop
        //   - First TP at fluid target (25-100%): PARTIAL TAKE 25%
        //   - Then let it ride with trailing stop
        //   - Trailing stop tightens as profits grow (lock in gains)
        //   - NO MAX TP - can ride to 1000x, 10000x, or beyond!
        // 
        // V4.1.3: ULTRA-FAST RUG DETECTION
        //   - Uses UltraFastRugDetectorAI for real-time monitoring
        //   - EMA crosses, RSI, support/resistance analysis
        //   - Instant exit on rug signals
        // ═══════════════════════════════════════════════════════════════════════════
        
        // ─── EXIT CONDITIONS (Priority order) ───
        
        // 0. ULTRA-FAST RUG CHECK (runs first, every update)
        val rugSignal = UltraFastRugDetectorAI.checkForRug(
            mint = mint,
            currentPrice = currentPrice,
            currentLiquidity = pos.liquidityUsd,  // Use last known
            currentHolders = 0,  // Will be updated by scanner
            recentCandles = null,
        )
        
        if (rugSignal.shouldExit && rugSignal.urgency == UltraFastRugDetectorAI.ExitUrgency.INSTANT) {
            ErrorLogger.warn(TAG, "💀💨 ULTRA-FAST RUG EXIT: ${pos.symbol} | ${rugSignal.reason}")
            return ExitSignal.RUG_DETECTED
        }
        
        // Log technical signals if any
        if (rugSignal.technicalSignals.isNotEmpty()) {
            val techSummary = rugSignal.technicalSignals.joinToString(" | ") { 
                "${it.type}: ${it.interpretation}" 
            }
            ErrorLogger.debug(TAG, "📊 TECHNICALS ${pos.symbol}: $techSummary")
        }
        
        // V5.2 FIX: HARD FLOOR STOP - ABSOLUTE MAXIMUM LOSS
        // This should NEVER be exceeded, regardless of other conditions
        val HARD_FLOOR_STOP_PCT = -20.0  // Absolute max loss for ShitCoin layer
        if (pnlPct <= HARD_FLOOR_STOP_PCT) {
            ErrorLogger.warn(TAG, "💩🛑 HARD FLOOR: ${pos.symbol} | ${pnlPct.toInt()}% - EMERGENCY EXIT!")
            return ExitSignal.STOP_LOSS
        }
        
        // 1. RUG DETECTED - Price dropped >50% in <5 mins (backup check)
        if (holdMinutes < 5 && pnlPct < -50) {
            ErrorLogger.warn(TAG, "💩💀 RUG DETECTED: ${pos.symbol} | ${pnlPct.toInt()}% in ${holdMinutes}min")
            return ExitSignal.RUG_DETECTED
        }
        
        // 2. HIT STOP LOSS
        val effectiveStop = pos.stopLossPct
        if (pnlPct <= effectiveStop) {
            ErrorLogger.info(TAG, "💩🛑 SL HIT: ${pos.symbol} | ${pnlPct.fmt(1)}%")
            return ExitSignal.STOP_LOSS
        }
        
        // V5.9.163 — LADDERED PARTIAL TAKES (same engine as Moonshot).
        // Update peak + walk the ladder. Each rung fires ONE partial sell.
        if (pnlPct > pos.peakPnlPct) pos.peakPnlPct = pnlPct

        val rungs = doubleArrayOf(20.0, 50.0, 100.0, 300.0, 1000.0, 3000.0, 10000.0)
        if (pos.partialRungsTaken < rungs.size && pnlPct >= rungs[pos.partialRungsTaken]) {
            val hitRung = rungs[pos.partialRungsTaken]
            pos.partialRungsTaken += 1
            pos.firstTakeDone = true
            ErrorLogger.info(TAG, "💩💰 LADDER PARTIAL #${pos.partialRungsTaken}: ${pos.symbol} | " +
                "hit +${hitRung.toInt()}% (now +${pnlPct.fmt(1)}%) — locking a slice, rest rides")
            return ExitSignal.PARTIAL_TAKE
        }

        // V5.9.169 — continuous fluid profit floor (shared engine).
        val profitFloor = FluidLearningAI.fluidProfitFloor(pos.peakPnlPct)
        if (pnlPct < profitFloor) {
            ErrorLogger.info(TAG, "💩🔒 FLOOR LOCK: ${pos.symbol} | peak +${pos.peakPnlPct.toInt()}% → now +${pnlPct.fmt(1)}% < floor +${profitFloor.toInt()}%")
            return ExitSignal.TRAILING_STOP
        }

        // 4. TRAILING STOP - The moonshot catcher!
        // V5.5: Activate from entry (not after 15% profit) — stop trails from open price.
        // Prevents giving back gains that were never locked. Fires any time price
        // falls below the trailing level, which starts at the SL and rises with HWM.
        if (currentPrice <= pos.trailingStop) {
            val fromPeak = ((pos.highWaterMark - currentPrice) / pos.highWaterMark * 100)
            val totalGain = pnlPct
            ErrorLogger.info(TAG, "💩🚀 TRAIL EXIT: ${pos.symbol} | +${totalGain.fmt(1)}% (peak was +${((pos.highWaterMark - pos.entryPrice) / pos.entryPrice * 100).fmt(1)}%)")
            return ExitSignal.TRAILING_STOP
        }
        
        // 5. V5.2: FLAT EXIT - If nothing happening after 8 mins, get out
        if (holdMinutes >= FLAT_EXIT_MINUTES && pnlPct > -5.0 && pnlPct < 10.0) {
            // Token is just sitting flat - wasting time and opportunity cost
            ErrorLogger.info(TAG, "💩😴 FLAT EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min (stagnant)")
            return ExitSignal.TIME_EXIT
        }
        // V5.9: DEAD TOKEN EXIT - Holding underwater for > 20 mins = token is dead, cut it.
        // Meme tokens either pump within 15-20 mins or they don't pump at all.
        if (holdMinutes >= 20 && pnlPct < -1.0) {
            ErrorLogger.info(TAG, "💩⏱️ DEAD EXIT: ${pos.symbol} | ${pnlPct.fmt(1)}% after ${holdMinutes}min (no pump)")
            return ExitSignal.TIME_EXIT
        }
        
        // V5.2: REMOVED max hold time check - ShitCoins can moon anytime!
        // Old code forced exits at 10 mins even for promising plays
        // Now only exit on: stop loss, trailing stop, take profit, flat, or rug
        
        // 6. Early exit if profitable after 5 mins (but not if running hot)
        if (holdMinutes >= 5 && pnlPct >= 20.0 && pnlPct < 50.0) {
            // V5.2: Quick 20%+ profit after 5 mins - take it (was 15% after 3 mins)
            ErrorLogger.info(TAG, "💩💰 EARLY TP: ${pos.symbol} | +${pnlPct.fmt(1)}% @ ${holdMinutes}min")
            return ExitSignal.TAKE_PROFIT
        }
        
        // 7. HOLD - Let it ride! No time caps.
        return ExitSignal.HOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS - ShitCoin specific
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.2 FIX: Lowered bootstrap thresholds - was strangling all trades!
    // Bootstrap needs MORE trades for learning, not fewer
    // Bootstrap: score >= 20, conf >= 20% (was 35/25+10 = too high!)
    // Mature: score >= 40, conf >= 50%
    private const val SC_SCORE_BOOTSTRAP = 15         // V5.9.159: lowered 20→15 — meme volume unlock
    private const val SC_SCORE_MATURE = 40            // Higher bar when mature
    
    // V5.2 FIX: Lower confidence required in bootstrap
    private const val SC_CONF_BOOTSTRAP = 10          // V5.9.159: lowered 15→10 — meme volume unlock
    private const val SC_CONF_MATURE = 50             // Solid confidence when mature
    private const val SC_CONF_BOOST_MAX = 15.0        // Boost back to +15 for bootstrap
    
    private fun lerp(bootstrap: Double, mature: Double): Double {
        val progress = FluidLearningAI.getLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * V4.1.2: Bootstrap confidence boost for ShitCoin layer
     * Starts at +10% and decays to 0% as learning progresses
     */
    private fun getBootstrapConfBoost(): Double {
        val progress = FluidLearningAI.getLearningProgress()
        // Boost decays from 10% to 0% as we learn
        return SC_CONF_BOOST_MAX * (1.0 - progress).coerceIn(0.0, 1.0)
    }
    
    fun getFluidScoreThreshold(): Int = lerp(SC_SCORE_BOOTSTRAP.toDouble(), SC_SCORE_MATURE.toDouble()).toInt()
    
    fun getFluidConfidenceThreshold(): Int {
        val baseConf = lerp(SC_CONF_BOOTSTRAP.toDouble(), SC_CONF_MATURE.toDouble())
        val boost = getBootstrapConfBoost()
        // During bootstrap: 25% base + 10% boost = 35% effective
        // At maturity: 50% base + 0% boost = 50% effective
        return (baseConf + boost).toInt()
    }
    fun getFluidMinLiquidity(): Double = lerp(MIN_LIQUIDITY_USD_BOOTSTRAP, MIN_LIQUIDITY_USD_MATURE)
    fun getFluidTakeProfit(): Double = lerp(TAKE_PROFIT_BOOTSTRAP, TAKE_PROFIT_MATURE)
    fun getFluidStopLoss(): Double = lerp(STOP_LOSS_BOOTSTRAP, STOP_LOSS_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getCurrentMode(): ShitCoinMode {
        // V4.20: Removed daily loss limit - always hunting unless positioned
        val positionCount = activePositions.size
        
        return when {
            positionCount > 0 -> ShitCoinMode.POSITIONED
            else -> ShitCoinMode.HUNTING
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ShitCoinStats(
        val dailyPnlSol: Double,
        val dailyMaxLossSol: Double,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyTradeCount: Int,
        val winRate: Double,
        val activePositions: Int,
        val mode: ShitCoinMode,
        val balanceSol: Double,
        val isPaperMode: Boolean,
        val minScoreThreshold: Int,
        val minConfThreshold: Int,
        val ruggedDevsCount: Int,
        val trustedDevsCount: Int,
    ) {
        fun summary(): String = buildString {
            val modeEmoji = when (mode) {
                ShitCoinMode.HUNTING -> "🎯"
                ShitCoinMode.POSITIONED -> "📊"
                ShitCoinMode.CAUTIOUS -> "⚠️"
                ShitCoinMode.PAUSED -> "⏸️"
                ShitCoinMode.GRADUATION -> "🎓"
            }
            append("$modeEmoji ${mode.name} | ")
            append("${if (dailyPnlSol >= 0) "+" else ""}${dailyPnlSol.fmt(3)}◎ | ")
            append("$dailyWins W / $dailyLosses L | ")
            append("Balance: ${balanceSol.fmt(3)}◎ ")
            append("[${if (isPaperMode) "PAPER" else "LIVE"}]")
        }
    }
    
    fun getStats(): ShitCoinStats {
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        
        return ShitCoinStats(
            dailyPnlSol = dailyPnl,
            dailyMaxLossSol = 0.0,  // V4.20: No daily loss limit
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyTradeCount = dailyTradeCount.get(),
            winRate = if (dailyTradeCount.get() > 0) {
                dailyWins.get().toDouble() / dailyTradeCount.get() * 100
            } else 0.0,
            activePositions = activePositions.size,
            mode = getCurrentMode(),
            balanceSol = getCurrentBalance(),
            isPaperMode = isPaperMode,
            minScoreThreshold = getFluidScoreThreshold(),
            minConfThreshold = getFluidConfidenceThreshold(),
            ruggedDevsCount = ruggedDevs.size,
            trustedDevsCount = trustedDevs.size,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Detect launch platform from token source string
     */
    fun detectPlatform(source: String): LaunchPlatform {
        return when {
            source.contains("pump", ignoreCase = true) ||
            source.contains("PUMP_FUN", ignoreCase = true) -> LaunchPlatform.PUMP_FUN
            source.contains("raydium", ignoreCase = true) -> LaunchPlatform.RAYDIUM
            source.contains("moonshot", ignoreCase = true) -> LaunchPlatform.MOONSHOT
            source.contains("bonk", ignoreCase = true) -> LaunchPlatform.BONK_BOT
            else -> LaunchPlatform.UNKNOWN
        }
    }
    
    /**
     * Check if this token qualifies as a "shitcoin" (low mcap, fresh)
     */
    fun isShitCoinCandidate(marketCapUsd: Double, tokenAgeMinutes: Double): Boolean {
        // V5.9.160: fresh pump.fun / DexScreener trending tokens often arrive with
        // marketCapUsd == 0 before the first mcap fetch lands. Treating "no data"
        // as "not a candidate" was silently killing 40-60% of fresh meme flow.
        // Allow mcap == 0 through as a candidate; the downstream scorer has its
        // own MCAP_TOO_HIGH / MCAP_TOO_LOW checks that handle it properly once
        // the real number arrives.
        // Also allow age UP TO the full MAX_TOKEN_AGE_HOURS window (strictly <).
        val mcapOk = marketCapUsd <= 0.0 ||
                     marketCapUsd in MIN_MARKET_CAP_USD..MAX_MARKET_CAP_USD
        return mcapOk && tokenAgeMinutes < effectiveMaxTokenAgeHours() * 60
    }
    
    /**
     * V5.7: Get win rate for perps learning bridge
     */
    fun getWinRatePct(): Int {
        val total = dailyWins.get() + dailyLosses.get()
        return if (total > 0) ((dailyWins.get().toDouble() / total) * 100).toInt() else 0
    }
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
