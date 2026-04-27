package com.lifecyclebot.perps

import com.lifecyclebot.data.Trade

import com.lifecyclebot.engine.TradeHistoryStore

import com.lifecyclebot.collective.CollectiveSchema
import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.collective.MarketsTradeRecord
import com.lifecyclebot.collective.MarketsPositionRecord
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📈 TOKENIZED STOCK TRADER - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Dedicated trading engine for tokenized stocks (AAPL, NVDA, TSLA, etc.)
 * Runs independently from the main perps system with its own:
 *   • Scanning loop
 *   • Position management
 *   • Learning integration
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📊 Real-time Pyth Network price feeds
 *   🧠 AI signal generation
 *   📈 Long/Short positions with leverage
 *   🎯 Dynamic TP/SL based on volatility
 *   📚 Pattern learning and memory
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TokenizedStockTrader {
    
    private const val TAG = "📈MarketsTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val SCAN_INTERVAL_MS = 15_000L  // 15 seconds
    private const val MAX_STOCK_POSITIONS = 100   // V5.9.100: user req — 100 per trader
    private const val DEFAULT_LEVERAGE = 3.0
    private const val DEFAULT_SIZE_PCT = 5.0  // 5% of balance per trade
    
    // V5.7.7: Trading fees (consistent across app)
    private const val SPOT_TRADING_FEE_PERCENT = 0.005    // 0.5% for spot (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01 // 1.0% for leverage (3x+)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MARKET HOURS CHECK - V5.7.7
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if US stock market is currently open
     * Regular hours: Mon-Fri 9:30 AM - 4:00 PM ET
     * Extended hours: 4:00 AM - 8:00 PM ET (pre-market + after-hours)
     * 
     * Returns true for extended hours to allow some trading, but with warning
     */
    fun isStockMarketOpen(): Boolean {
        val nyZone = java.util.TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(nyZone)
        
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute
        
        // Weekend check (Saturday = 7, Sunday = 1)
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            return false
        }
        
        // Extended hours: 4:00 AM - 8:00 PM ET (240 - 1200 minutes)
        val extendedOpen = 4 * 60      // 4:00 AM = 240 minutes
        val extendedClose = 20 * 60    // 8:00 PM = 1200 minutes
        
        return timeInMinutes in extendedOpen..extendedClose
    }
    
    /**
     * Check if we're in regular trading hours (most liquid)
     */
    fun isRegularTradingHours(): Boolean {
        val nyZone = java.util.TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(nyZone)
        
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute
        
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            return false
        }
        
        // Regular hours: 9:30 AM - 4:00 PM ET
        val regularOpen = 9 * 60 + 30  // 9:30 AM = 570 minutes
        val regularClose = 16 * 60     // 4:00 PM = 960 minutes
        
        return timeInMinutes in regularOpen..regularClose
    }
    
    /**
     * Get human-readable market status
     */
    fun getMarketStatus(): String {
        return when {
            isRegularTradingHours() -> "OPEN"
            isStockMarketOpen() -> "EXTENDED"
            else -> "CLOSED"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isRunning = AtomicBoolean(false)
    private val isPaperMode = AtomicBoolean(true)  // V5.7.6b: Default to paper, can be switched to LIVE
    private val isEnabled = AtomicBoolean(true)
    private val preferLeverage = AtomicBoolean(false)  // V5.9.3: mirrors UI SPOT/LEVERAGE toggle
    
    // V5.7.6b: Live trading state
    private var liveWalletBalance = 0.0  // Updated from connected wallet
    
    private var engineJob: Job? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.8.0: HIVEMIND WIN-RATE CACHE
    // ═══════════════════════════════════════════════════════════════════════════

    private val hiveWinRates = ConcurrentHashMap<String, Pair<Double, Double>>()
    private var hiveWinRatesLoadedAt = 0L
    private const val HIVE_CACHE_TTL_MS = 30 * 60 * 1000L
    private val hiveHourlyProfiles = ConcurrentHashMap<String, Map<Int, Pair<Double, Int>>>()

    private suspend fun refreshHiveWinRates() {
        try {
            val client = CollectiveLearning.getClient() ?: return
            val rates = client.getAssetWinRates(minTrades = 15)
            if (rates.isNotEmpty()) {
                hiveWinRates.clear()
                hiveWinRates.putAll(rates)
                hiveWinRatesLoadedAt = System.currentTimeMillis()
                ErrorLogger.info(TAG, "🧠 Hive win-rate cache refreshed: ${rates.size} assets")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "🧠 Hive refresh error: ${e.message}")
        }
    }

    private suspend fun getHourlyProfile(symbol: String): Map<Int, Pair<Double, Int>> {
        hiveHourlyProfiles[symbol]?.let { return it }
        return try {
            val client = CollectiveLearning.getClient() ?: return emptyMap()
            val profile = client.getHourlyWinProfile(symbol, minSamples = 5)
            if (profile.isNotEmpty()) hiveHourlyProfiles[symbol] = profile
            profile
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Returns (blocked, sizeMultiplier, tpAdjust)
    private fun hiveEntryModifier(symbol: String): Triple<Boolean, Double, Double> {
        val stats = hiveWinRates[symbol] ?: return Triple(false, 1.0, 0.0)
        val (winRate, avgPnl) = stats
        if (winRate < 35.0) {
            ErrorLogger.info(TAG, "🧠 HIVE BLOCK: $symbol wr=${winRate.toInt()}%")
            return Triple(true, 1.0, 0.0)
        }
        val sizeMult = when {
            winRate >= 65.0 && avgPnl >= 4.0 -> 1.4
            winRate >= 55.0 && avgPnl >= 2.0 -> 1.2
            winRate < 42.0                   -> 0.7
            else                             -> 1.0
        }
        val tpAdj = when {
            avgPnl >= 6.0 -> 2.0
            avgPnl >= 3.0 -> 1.0
            avgPnl < 0.0  -> -1.0
            else          -> 0.0
        }
        return Triple(false, sizeMult, tpAdj)
    }

    private suspend fun hiveTimeOfDayAdjust(symbol: String): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val profile = getHourlyProfile(symbol)
        val (wr, samples) = profile[hour] ?: return 0
        if (samples < 5) return 0
        return when {
            wr >= 65.0 -> +8
            wr >= 55.0 -> +4
            wr < 35.0  -> -8
            wr < 45.0  -> -4
            else       -> 0
        }
    }


    // Positions - V5.7.6b: Separate SPOT and LEVERAGE tracking
    private val positions = ConcurrentHashMap<String, StockPosition>()  // All positions
    private val spotPositions = ConcurrentHashMap<String, StockPosition>()      // SPOT (1x) only
    private val leveragePositions = ConcurrentHashMap<String, StockPosition>()  // LEVERAGE (3x+) only
    private var positionIdCounter = AtomicLong(System.currentTimeMillis())
    
    // Stats
    private val totalTrades = AtomicInteger(0)
    private val winningTrades = AtomicInteger(0)
    private val losingTrades = AtomicInteger(0)
    private val scanCount = AtomicLong(0)
    // V5.9.7: paperBalance now delegates to shared FluidLearning pool
    private var paperBalance: Double
        get() = com.lifecyclebot.engine.BotService.status.paperWalletSol
        set(value) { com.lifecyclebot.engine.FluidLearning.forceSetBalance(value) }
    private var totalPnlSol = 0.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STOCK POSITION MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class StockPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val entryPrice: Double,
        var currentPrice: Double,
        val sizeSol: Double,
        val leverage: Double,
        val entryTime: Long = System.currentTimeMillis(),
        var takeProfitPrice: Double? = null,
        var stopLossPrice: Double? = null,
        val aiScore: Int = 50,
        val aiConfidence: Int = 50,
        val reasons: List<String> = emptyList(),
        var peakPnlPct: Double = 0.0    // V5.9.9: Track peak PnL for symbolic reasoning
    ) {
        // V5.7.6: isSpot property for UI compatibility
        val isSpot: Boolean get() = leverage == 1.0
        
        // V5.7.6b: Aliases for MultiAssetActivity position card compatibility
        val size: Double get() = sizeSol
        val openTime: Long get() = entryTime
        val takeProfit: Double get() = takeProfitPrice ?: (entryPrice * if (direction == PerpsDirection.LONG) 1.05 else 0.95)
        val stopLoss: Double get() = stopLossPrice ?: (entryPrice * if (direction == PerpsDirection.LONG) 0.97 else 1.03)
        
        fun getUnrealizedPnlPct(): Double {
            if (entryPrice <= 0) return 0.0
            val priceDiff = when (direction) {
                PerpsDirection.LONG -> (currentPrice - entryPrice) / entryPrice
                PerpsDirection.SHORT -> (entryPrice - currentPrice) / entryPrice
            }
            return priceDiff * leverage * 100
        }
        
        fun getUnrealizedPnlSol(): Double {
            return sizeSol * (getUnrealizedPnlPct() / 100)
        }
        
        // V5.7.6: Aliases for MultiAssetActivity compatibility
        fun getPnlSol(): Double = getUnrealizedPnlSol()
        fun getPnlPercent(): Double = getUnrealizedPnlPct()
        
        fun shouldTakeProfit(): Boolean {
            takeProfitPrice?.let { tp ->
                return when (direction) {
                    PerpsDirection.LONG -> currentPrice >= tp
                    PerpsDirection.SHORT -> currentPrice <= tp
                }
            }
            return false
        }
        
        fun shouldStopLoss(): Boolean {
            stopLossPrice?.let { sl ->
                return when (direction) {
                    PerpsDirection.LONG -> currentPrice <= sl
                    PerpsDirection.SHORT -> currentPrice >= sl
                }
            }
            return false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STOCK SIGNAL MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class StockSignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val score: Int,
        val confidence: Int,
        val price: Double,
        val priceChange24h: Double,
        val reasons: List<String>,
        val layerVotes: Map<String, PerpsDirection> = emptyMap(),
        val leverage: Double = 3.0  // V5.7.6b: Default leverage, can be overridden
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile private var tstInited = false
    fun init() {
        if (tstInited) {
            ErrorLogger.debug(TAG, "📈 init: already inited — skipping to preserve running state")
            return
        }
        tstInited = true
        // V5.9.8: Sync paper/live mode from main config (source of truth)
        try {
            val ctx = com.lifecyclebot.engine.BotService.instance?.applicationContext
            if (ctx != null) {
                val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
                isPaperMode.set(cfg.paperMode)
            }
        } catch (_: Exception) {}
        // V5.7.7: Load persisted state from Turso
        scope.launch {
            loadPersistedState()
            
            // V5.7.7: Apply cross-learning boost from Meme mode
            applyMemeKnowledge()
        }
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader INITIALIZED | paper=$isPaperMode | balance=${"%.2f".format(paperBalance)} SOL")

        // V5.9.178 — REAL local position persistence so app updates never
        // wipe open stock trades. Complements Turso's remote persistence
        // (loadPersistedState above) and works offline.
        try {
            val ctx2 = com.lifecyclebot.engine.BotService.instance?.applicationContext
            if (ctx2 != null) {
                PerpsPositionStore.init(ctx2, "stocks")
                val rehydrated = PerpsPositionStore.loadAll("stocks")
                rehydrated.forEach { j ->
                    try {
                        val pos = stockPositionFromJson(j)
                        positions[pos.id] = pos
                        if (pos.leverage <= 1.0) spotPositions[pos.id]     = pos
                        else                     leveragePositions[pos.id] = pos
                    } catch (_: Exception) {}
                }
                if (rehydrated.isNotEmpty()) {
                    ErrorLogger.info(TAG, "📈 REHYDRATED ${rehydrated.size} stock positions (app-update recovery)")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "stocks rehydrate failed: ${e.message}")
        }
    }

    private fun stockPositionToJson(p: StockPosition): org.json.JSONObject {
        val j = org.json.JSONObject()
            .put("id", p.id).put("market", p.market.name).put("direction", p.direction.name)
            .put("entryPrice", p.entryPrice).put("currentPrice", p.currentPrice)
            .put("sizeSol", p.sizeSol).put("leverage", p.leverage)
            .put("entryTime", p.entryTime).put("aiScore", p.aiScore)
            .put("aiConfidence", p.aiConfidence).put("reasons", org.json.JSONArray(p.reasons))
            .put("peakPnlPct", p.peakPnlPct)
        p.takeProfitPrice?.let { j.put("takeProfitPrice", it) }
        p.stopLossPrice?.let { j.put("stopLossPrice", it) }
        return j
    }

    private fun stockPositionFromJson(j: org.json.JSONObject): StockPosition {
        val rArr = j.optJSONArray("reasons")
        val reasons = if (rArr != null) (0 until rArr.length()).map { rArr.optString(it, "") } else emptyList()
        return StockPosition(
            id = j.getString("id"), market = PerpsMarket.valueOf(j.getString("market")),
            direction = PerpsDirection.valueOf(j.getString("direction")),
            entryPrice = j.getDouble("entryPrice"), currentPrice = j.getDouble("currentPrice"),
            sizeSol = j.getDouble("sizeSol"), leverage = j.optDouble("leverage", 1.0),
            entryTime = j.optLong("entryTime", System.currentTimeMillis()),
            takeProfitPrice = if (j.has("takeProfitPrice")) j.getDouble("takeProfitPrice") else null,
            stopLossPrice   = if (j.has("stopLossPrice"))   j.getDouble("stopLossPrice")   else null,
            aiScore = j.optInt("aiScore", 50), aiConfidence = j.optInt("aiConfidence", 50),
            reasons = reasons, peakPnlPct = j.optDouble("peakPnlPct", 0.0),
        )
    }

    private fun persistStockPositions() {
        try {
            val all = positions.values.map { stockPositionToJson(it) }
            PerpsPositionStore.saveAll("stocks", all)
        } catch (_: Exception) {}
    }
    
    /**
     * V5.7.7: Apply learnings from Meme mode to bootstrap Markets AI faster
     */
    private fun applyMemeKnowledge() {
        try {
            // Apply Meme → Markets boost
            FluidLearningAI.applyMemeToMarketsBoost()
            
            // Get shared risk insights for TP/SL calibration
            val riskInsights = FluidLearningAI.getSharedRiskInsights()
            if (riskInsights.source == "MEME_CROSS_LEARN") {
                ErrorLogger.info(TAG, "📈 Using Meme risk insights: SL=${riskInsights.suggestedStopLossPct.toInt()}% | TP=${riskInsights.suggestedTakeProfitPct.toInt()}%")
            }
            
            // Get timing insights
            val timingInsights = FluidLearningAI.getSharedTimingInsights()
            if (timingInsights.source == "MEME_CROSS_LEARN") {
                ErrorLogger.info(TAG, "📈 Using Meme timing insights: Best hours=${timingInsights.bestHoursUTC.take(3)}")
            }
            
            // Log cross-learning status
            val status = FluidLearningAI.getCrossLearningStatus()
            ErrorLogger.info(TAG, "🔗 Cross-learning: Meme ${status["memeProgress"]}% → Markets ${status["marketsProgress"]}%")
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Cross-learning error: ${e.message}")
        }
    }
    
    /**
     * V5.7.7: Load persisted Markets state from Turso
     */
    private suspend fun loadPersistedState() {
        try {
            val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
            if (tursoClient != null) {
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                val state = tursoClient.loadMarketsState(instanceId)
                if (state != null) {
                    com.lifecyclebot.engine.FluidLearning.forceSetBalance(state.paperBalanceSol)
                    totalTrades.set(state.totalTrades)
                    winningTrades.set(state.totalWins)
                    losingTrades.set(state.totalLosses)
                    totalPnlSol = state.totalPnlSol
                    isPaperMode.set(!state.isLiveMode)
                    ErrorLogger.info(TAG, "📈 Loaded persisted state: balance=${paperBalance.fmt(2)} SOL | trades=${state.totalTrades} | WR=${state.winRate.toInt()}%")
                } else {
                    ErrorLogger.info(TAG, "📈 No persisted state found - using defaults")
                }

                // V5.9.134 — refund orphaned paper-mode stock positions
                // (see PaperOrphanReconciler for the full root-cause writeup).
                if (isPaperMode.get()) {
                    com.lifecyclebot.collective.PaperOrphanReconciler
                        .reconcile(assetClass = "STOCK", sourceLabel = "StockTrader")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Error loading persisted state: ${e.message}")
        }
    }
    
    /**
     * V5.7.7: Save current state to Turso
     */
    fun savePersistedState() {
        scope.launch {
            try {
                val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
                if (tursoClient != null) {
                    val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                    val state = com.lifecyclebot.collective.MarketsState(
                        instanceId = instanceId,
                        paperBalanceSol = paperBalance,
                        totalTrades = totalTrades.get(),
                        totalWins = winningTrades.get(),
                        totalLosses = losingTrades.get(),
                        totalPnlSol = totalPnlSol,
                        learningPhase = calculateLearningPhase(),
                        isLiveMode = !isPaperMode.get(),
                        lastUpdated = System.currentTimeMillis()
                    )
                    tursoClient.saveMarketsState(state)
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Error saving state: ${e.message}")
            }
        }
    }
    
    /** Public phase label for UI (mirrors meme trader) */
    fun getPhaseLabel(): String = calculateLearningPhase()

    /** Whether this trader has met all requirements to go live */
fun isLiveReady(): Boolean = totalTrades.get() >= 5000 && getWinRate() >= 50.0

    /** V5.9.3: Called from MAA when user taps SPOT/LEVERAGE toggle on Stocks tab */
    fun setPreferLeverage(lev: Boolean) {
        preferLeverage.set(lev)
        ErrorLogger.info(TAG, "📈 Mode → ${if (lev) "LEVERAGE (${DEFAULT_LEVERAGE.toInt()}x)" else "SPOT"}")
    }
    fun isPreferLeverage(): Boolean = preferLeverage.get()

    private fun calculateLearningPhase(): String {
        val trades = totalTrades.get()
        return when {
            trades < 500  -> "📚 BOOTSTRAP"
            trades < 1500 -> "🧠 LEARNING"
            trades < 3000 -> "🔬 VALIDATING"
            trades < 5000 -> "⚡ MATURING"
            else          -> "✅ READY"
        }
    }
    
    fun start() {
        if (isRunning.get()) {
            // Detect silent loop death — check if jobs are actually alive
            val engineAlive  = engineJob?.isActive == true
            val monitorAlive = monitorJob?.isActive == true
            if (engineAlive && monitorAlive) {
                ErrorLogger.debug(TAG, "Already running and jobs alive — skip restart")
                return
            }
            // Jobs died silently — force cleanup and restart
            ErrorLogger.warn(TAG, "⚠️ isRunning=true but jobs dead — force-restarting...")
            engineJob?.cancel()
            monitorJob?.cancel()
            isRunning.set(false)
        }
        isRunning.set(true)
        
        engineJob = scope.launch {
            // V5.7.6: Use error-level logging to ensure visibility
            ErrorLogger.info(TAG, "📈📈📈 TokenizedStockTrader ENGINE STARTED 📈📈📈")
            ErrorLogger.info(TAG, "📈 Scanning every ${SCAN_INTERVAL_MS/1000}s | enabled=${isEnabled.get()}")
            
            // V5.7.6: Run first scan immediately (no initial delay)
            try {
                ErrorLogger.info(TAG, "📈📈📈 Running INITIAL stock scan NOW... 📈📈📈")
                runScanCycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "📈 Initial scan EXCEPTION: ${e.message}", e)
            }

            // Run scan loop
            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)

                    if (isEnabled.get()) {
                        runScanCycle()
                    } else {
                        ErrorLogger.debug(TAG, "📈 Stock trading DISABLED - skipping scan")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e)
                }
            }
        }

        // Start position monitor
        monitorJob = scope.launch {
            while (isRunning.get()) {
                try {
                    monitorPositions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Monitor error: ${e.message}", e)
                }
                delay(5000)  // Check every 5 seconds
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader STOPPED")
    }

    /** Close all open positions immediately (called on STOP). */
    fun closeAllPositions() {
        val ids = positions.keys.toList()
        ids.forEach { id -> try { closePosition(id, "USER_STOP") } catch (_: Exception) {} }
        ErrorLogger.info(TAG, "📈 All stock positions closed on STOP (${ids.size} positions)")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE - Find stock opportunities
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()
        
        // Tokenized stocks are Solana crypto tokens (xStocks) — they trade 24/7 on DEXes.
        // The underlying NYSE/NASDAQ stock market hours do NOT apply here.
        val marketHoursNote = ""
        
        ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "📈 STOCK SCAN #$scanNum STARTING$marketHoursNote")
        ErrorLogger.info(TAG, "📈 positions=${positions.size}/$MAX_STOCK_POSITIONS | balance=${"%.2f".format(getBalance())} SOL")
        ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
        
        // V5.7.7: PRIORITIZE Pyth-supported stocks for reliable price feeds
        val pythSupported = PythOracle.getSupportedSymbols()
        // V5.9.252: In LIVE mode, only scan stocks that have a verified on-chain
        // route (TokenizedAssetRegistry). Paper-only symbols generate SIGNAL logs
        // that can never execute — skip them early to save CPU and avoid confusion.
        val hasRoute: (PerpsMarket) -> Boolean = { m ->
            isPaperMode.get() || TokenizedAssetRegistry.hasRealRoute(m.symbol)
        }
        val pythStocks = PerpsMarket.values().filter { it.isStock && pythSupported.contains(it.symbol) && hasRoute(it) }
        val otherStocks = PerpsMarket.values().filter { it.isStock && !pythSupported.contains(it.symbol) && hasRoute(it) }
        
        // V5.9.91: Crypto is fully owned by CryptoAltTrader now — TST
        // scanning them too produced duplicate low-conviction signals on the
        // same symbols (SAND/MANA/AXS/XTZ/EOS/...). Stocks/Pyth stocks only.
        val stockMarkets = pythStocks + otherStocks.take(10)
        ErrorLogger.info(TAG, "📈 Scanning ${pythStocks.size} Pyth stocks + ${otherStocks.take(10).size} others (crypto handled by CryptoAltTrader)")
        
        // Fetch prices and generate signals
        val signals = mutableListOf<StockSignal>()
        var analyzedCount = 0
        var skippedHasPosition = 0
        var skippedBadPrice = 0
        
        for (market in stockMarkets) {
            try {
                // Skip if we already have a position
                if (hasPosition(market)) {
                    skippedHasPosition++
                    continue
                }
                
                // Get market data
                val data = PerpsMarketDataFetcher.getMarketData(market)
                
                // V5.7.6: Log every price fetch for debugging
                if (analyzedCount < 5) {
                    ErrorLogger.info(TAG, "📈 ${market.symbol}: price=\$${data.price.fmt(2)} | change=${data.priceChange24hPct.fmt(2)}%")
                }
                
                if (data.price <= 0) {
                    skippedBadPrice++
                    ErrorLogger.warn(TAG, "📈 ${market.symbol}: SKIPPED - price is 0 or negative!")
                    continue
                }
                
                // V5.7.8: Feed V4 LeadLag + Regime with every price update
                try {
                    com.lifecyclebot.v4.meta.CrossAssetLeadLagAI.recordReturn(market.symbol, data.priceChange24hPct)
                    com.lifecyclebot.v4.meta.CrossMarketRegimeAI.updateMarketState(market.symbol, data.price, data.priceChange24hPct, data.volume24h)
                } catch (_: Exception) {}
                
                analyzedCount++
                
                // Generate signal using AI layers
                val signal = analyzeStock(market, data)
                
                // V5.7.6: Log every signal attempt
                if (signal != null) {
                    ErrorLogger.info(TAG, "📈 SIGNAL: ${market.symbol} | score=${signal.score} | conf=${signal.confidence} | dir=${signal.direction.symbol}")
                    
                    // V5.9.328: TRUST GATE — if StrategyTrustAI has marked this layer as
                    // DISTRUSTED (WR < threshold after sufficient trades), halt new entries.
                    // This prevents the bot from continuing to open losing trades after the
                    // AI has already concluded the strategy is broken.
                    val trustLevel = try {
                        com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel("TokenizedStockAI")
                    } catch (_: Exception) { null }
                    if (trustLevel == com.lifecyclebot.v4.meta.StrategyTrustAI.TrustLevel.DISTRUSTED) {
                        ErrorLogger.warn(TAG, "📈 ${market.symbol}: TRUST_GATE — TokenizedStockAI DISTRUSTED, skipping entry")
                        continue
                    }
                    
                    // V5.7.6: Use FLUID thresholds from FluidLearningAI
                    val scoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                    val confThresh = FluidLearningAI.getMarketsSpotConfThreshold()

                    // V5.9.132: V3 IS THE GATE. Any signal with score≥45 is sent
                    // to UnifiedScorer (41 layers). If V3 passes, it enters even
                    // if fluid conf gate blocks — the 41-layer AI stack is the
                    // authoritative signal quality gauge, not a 2-point conf drift.
                    val fluidPass = signal.score >= scoreThresh && signal.confidence >= confThresh
                    val prefilterOk = signal.score >= 45  // minimal sanity floor

                    if (fluidPass) {
                        signals.add(signal)
                    } else if (prefilterOk) {
                        // V3 decides.
                        val v3Approves = try {
                            val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                                symbol = market.symbol,
                                assetClass = "STOCK",
                                price = signal.price,
                                technicalScore = signal.score,
                                technicalConfidence = signal.confidence,
                                liqUsd = 10_000_000.0,
                                mcapUsd = 1_000_000_000.0,
                                priceChangePct = signal.priceChange24h,
                                direction = signal.direction.name,
                            )
                            if (verdict.shouldEnter) {
                                ErrorLogger.info(TAG, "📈 V3-OVERRIDE: ${market.symbol} (score=${signal.score}/${signal.confidence} vs fluid ${scoreThresh}/${confThresh}) → v3=${verdict.v3Score} blended=${verdict.blendedScore}")
                                true
                            } else false
                        } catch (_: Exception) { false }

                        if (v3Approves) signals.add(signal)
                        else ErrorLogger.warn(TAG, "📈 ${market.symbol}: BELOW FLUID + V3 VETO (score=${signal.score}<$scoreThresh or conf=${signal.confidence}<$confThresh)")
                    } else {
                        ErrorLogger.warn(TAG, "📈 ${market.symbol}: below prefilter (score=${signal.score}<45)")
                    }
                } else {
                    ErrorLogger.warn(TAG, "📈 ${market.symbol}: analyzeStock returned NULL")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "📈 ${market.symbol}: EXCEPTION: ${e.message}", e)
            }
        }
        
        ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "📈 Scan stats: analyzed=$analyzedCount | hasPos=$skippedHasPosition | badPrice=$skippedBadPrice | signals=${signals.size}")
        
        // V5.7.8: Run V4 LeadLag scan + fuse snapshot after each cycle
        try {
            com.lifecyclebot.v4.meta.CrossAssetLeadLagAI.scan()
            com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime()
            com.lifecyclebot.v4.meta.LeverageSurvivalAI.assess(
                currentVolatility = signals.map { kotlin.math.abs(it.priceChange24h) }.average().takeIf { !it.isNaN() } ?: 0.0,
                fragilityScore = com.lifecyclebot.v4.meta.LiquidityFragilityAI.getFragilityScore("MARKET_AVG")
            )
            com.lifecyclebot.v4.meta.CrossTalkFusionEngine.fuse()
        } catch (_: Exception) {}
        
        // Sort by score and take best signals
        val topSignals = signals.sortedByDescending { it.score }.take(25)  // V5.9.128: raised from 3
        
        if (topSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "📈 TOP ${topSignals.size} stock signals: ${topSignals.map { "${it.market.symbol}(${it.score}/${it.confidence})" }}")
            ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
        } else {
            ErrorLogger.warn(TAG, "📈 ⚠️ NO SIGNALS TO EXECUTE!")
            ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
            return  // Early return if no signals
        }
        
        // Execute top signals
        for (signal in topSignals) {
            if (positions.size >= MAX_STOCK_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached")
                break
            }

            // V5.9.130: full V3 stack gate — same 41 AI layers as memetrader.
            val v3Ok = try {
                val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                    symbol = signal.market.symbol,
                    assetClass = "STOCK",
                    price = signal.price,
                    technicalScore = signal.score,
                    technicalConfidence = signal.confidence,
                    liqUsd = 10_000_000.0,
                    mcapUsd = 1_000_000_000.0,
                    priceChangePct = signal.priceChange24h,
                    direction = signal.direction.name,
                )
                if (!verdict.shouldEnter) {
                    ErrorLogger.debug(TAG, "📈 V3 veto: ${signal.market.symbol} blended=${verdict.blendedScore}")
                    false
                } else {
                    ErrorLogger.info(TAG, "📈 V3 PASS: ${signal.market.symbol} v3=${verdict.v3Score} blended=${verdict.blendedScore}")
                    true
                }
            } catch (_: Exception) { true }
            if (!v3Ok) continue
            
            // V5.9.3: Respect UI SPOT/LEVERAGE toggle; removed parity alternation
            val useSpotDefault = !preferLeverage.get()
            var useSpot = useSpotDefault
            var leverage = if (useSpot) 1.0 else DEFAULT_LEVERAGE
            try {
                // Feed regime data
                com.lifecyclebot.v4.meta.CrossMarketRegimeAI.updateMarketState(
                    signal.market.symbol, signal.price, signal.price * 0.01) // Approximate change
                
                // Check V4 gated score
                val gated = com.lifecyclebot.v4.meta.CrossTalkFusionEngine.computeGatedScore(
                    baseScore = signal.score.toDouble(),
                    strategy = "TokenizedStockAI",
                    market = if (signal.market.isStock) "STOCKS" else "PERPS",
                    symbol = signal.market.symbol,
                    leverageRequested = leverage
                )
                
                // V4 can suppress leverage but NOT block trades entirely in Markets
                if (gated.vetoes.any { it.startsWith("LEVERAGE") } && !useSpotDefault) {
                    useSpot = true
                    leverage = 1.0
                    ErrorLogger.info(TAG, "📈 V4 META: Suppressed leverage for ${signal.market.symbol} → SPOT only")
                }
                
                // Feed to portfolio heat tracker
                com.lifecyclebot.v4.meta.PortfolioHeatAI.addPosition(
                    id = "MARKET_${signal.market.symbol}",
                    symbol = signal.market.symbol,
                    market = if (signal.market.isStock) "STOCKS" else "PERPS",
                    sector = signal.market.emoji,
                    direction = signal.direction.name,
                    sizeSol = getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100),
                    leverage = leverage
                )
            } catch (_: Exception) {}
            
            ErrorLogger.info(TAG, "📈 EXECUTING: ${signal.market.symbol} ${signal.direction.symbol} @ \$${signal.price.fmt(2)} | ${if (useSpot) "SPOT" else "${leverage.toInt()}x"}")
            executeSignal(signal.copy(leverage = leverage), isSpot = useSpot)
        }
    }
    
    // V5.7.6b: Check for existing SPOT position
    private fun hasSpotPosition(market: PerpsMarket): Boolean {
        return spotPositions.values.any { it.market == market }
    }
    
    // V5.7.6b: Check for existing LEVERAGE position
    private fun hasLeveragePosition(market: PerpsMarket): Boolean {
        return leveragePositions.values.any { it.market == market }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI ANALYSIS - Generate signals
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun analyzeStock(market: PerpsMarket, data: PerpsMarketData): StockSignal? {
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        // 1. Price momentum analysis
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        when {
            abs(change) > 5.0 -> {
                score += 20
                confidence += 15
                reasons.add("🚀 Strong move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 2.0 -> {
                score += 10
                confidence += 10
                reasons.add("📈 Good move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 1.0 -> {
                score += 5
                reasons.add("📊 Mild move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Market cap / sector analysis
        when (market) {
            PerpsMarket.NVDA -> {
                score += 10
                confidence += 10
                reasons.add("🔥 AI sector leader")
            }
            PerpsMarket.TSLA -> {
                score += 5
                reasons.add("⚡ High volatility play")
            }
            PerpsMarket.AAPL, PerpsMarket.MSFT, PerpsMarket.GOOGL, PerpsMarket.AMZN -> {
                confidence += 10
                reasons.add("💎 Blue chip stability")
            }
            PerpsMarket.COIN -> {
                score += 5
                reasons.add("🪙 Crypto proxy")
            }
            // V5.7.8: Crypto perps bonuses — these are high-liquidity 24/7 markets
            PerpsMarket.SOL -> {
                score += 15
                confidence += 15
                reasons.add("◎ SOL native — highest liquidity perp")
            }
            PerpsMarket.BTC -> {
                score += 10
                confidence += 15
                reasons.add("₿ BTC king — market leader")
            }
            PerpsMarket.ETH -> {
                score += 10
                confidence += 10
                reasons.add("⟠ ETH — DeFi backbone")
            }
            PerpsMarket.BNB, PerpsMarket.XRP -> {
                score += 5
                confidence += 10
                reasons.add("🔷 Major alt — high volume")
            }
            else -> {}
        }
        
        // 3. Technical analysis via PerpsAdvancedAI
        try {
            // V5.9.172 — seed history from real 24h OHLC so RSI/MACD aren't stuck at 50.
            PerpsAdvancedAI.seedHistoryFromOHLC(market, data.price, data.high24h, data.low24h, data.volume24h)
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            
            if (technicals.recommendation == direction) {
                score += 10
                confidence += 10
                reasons.add("📊 RSI confirms: ${"%.0f".format(technicals.rsi)}")
                layerVotes["Technical"] = direction
            }
            
            if (technicals.isOversold && direction == PerpsDirection.LONG) {
                score += 15
                reasons.add("📉 Oversold bounce")
            }
            if (technicals.isOverbought && direction == PerpsDirection.SHORT) {
                score += 15
                reasons.add("📈 Overbought short")
            }
        } catch (_: Exception) {}
        
        // 4. Volume analysis
        try {
            val volume = PerpsAdvancedAI.analyzeVolume(market)
            if (volume.isSpike) {
                score += when (volume.spikeStrength) {
                    "EXTREME" -> 20
                    "STRONG" -> 15
                    "MILD" -> 10
                    else -> 0
                }
                reasons.add("📊 Volume ${volume.spikeStrength}")
                layerVotes["Volume"] = direction
            }
        } catch (_: Exception) {}
        
        // 5. Support/Resistance
        try {
            val sr = PerpsAdvancedAI.analyzeSupportResistance(market, data.price)
            if (sr.nearSupport && direction == PerpsDirection.LONG) {
                score += 10
                reasons.add("📍 Near support")
            }
            if (sr.nearResistance && direction == PerpsDirection.SHORT) {
                score += 10
                reasons.add("📍 Near resistance")
            }
        } catch (_: Exception) {}
        
        // 6. Fluid Learning confidence boost
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
            
            // V5.7.7: Cross-learning boost from Meme mode
            val crossBoost = FluidLearningAI.getCrossLearnedConfidence(confidence.toDouble()) - confidence
            if (crossBoost > 0) {
                confidence += crossBoost.toInt()
                reasons.add("🔗 Meme boost: +${crossBoost.toInt()}")
            }
        } catch (_: Exception) {}
        
        // 7. Pattern memory check
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            val patternConf = PerpsAdvancedAI.getPatternConfidence(market, direction, technicals)
            if (patternConf > 60) {
                score += 10
                confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}
        
        // V5.9.328: Removed forced score >= 35 floor — it was pushing genuinely bad
        // signals over the prefilter gate (score >= 45 → V3) and injecting noise trades.
        // Real signals that don't meet the threshold should be REJECTED, not inflated.
        // The 41-layer V3 AI will still pass good signals through at lower scores via V3 override.
        reasons.add("📚 Learning: quality-gated mode")
        
        return StockSignal(
            market = market,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            price = data.price,
            priceChange24h = change,
            reasons = reasons,
            layerVotes = layerVotes
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.7.6b: Updated to support SPOT vs LEVERAGE + LIVE mode
    private suspend fun executeSignal(signal: StockSignal, isSpot: Boolean = false) {
        // V5.9.114: UNIFIED paper + live pipeline.
        // Per user policy — live must behave exactly like paper. All
        // sizing, meme-cross-learn TP/SL, hive modifiers, sanity checks
        // run the same way in both modes; only the capital-move step
        // differs (paper wallet debit vs Jupiter swap).

        // PAPER MODE execution — only when in paper mode
        val balance = getEffectiveBalance()
        val sizeSol = balance * (DEFAULT_SIZE_PCT / 100)
        
        if (sizeSol < 0.01) {
            ErrorLogger.warn(TAG, "Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        // V5.7.7: Get risk insights from Meme cross-learning
        val riskInsights = FluidLearningAI.getSharedRiskInsights()
        
        // V5.7.6b: Different TP/SL for SPOT vs LEVERAGE
        // V5.7.7: Use Meme-learned values if available
        val tpPct = if (riskInsights.source == "MEME_CROSS_LEARN") {
            if (isSpot) riskInsights.suggestedTakeProfitPct * 0.6 else riskInsights.suggestedTakeProfitPct
        } else {
            // V5.9.8: Use FluidLearningAI dynamic TP — scales 4→25% with learning, never caps big moves
            if (isSpot) com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
            else com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsLevTpPct()
        }
        val slPct = if (riskInsights.source == "MEME_CROSS_LEARN") {
            if (isSpot) riskInsights.suggestedStopLossPct * 0.6 else riskInsights.suggestedStopLossPct
        } else {
            if (isSpot) 3.0 else 4.0  // Defaults: SPOT 3%, LEV 4%
        }
        val leverage = if (isSpot) 1.0 else signal.leverage
        
        // V5.9.171 — match the other perps traders: apply the same fluid
        // score/confidence based size + TP/SL multipliers so stocks get
        // scaled with signal quality instead of static Hive adjustments only.
        val fluidSizeMult = PerpsFluidSizing.sizeMultiplier(signal.score, signal.confidence)
        val (fluidTpMult, fluidSlMult) = PerpsFluidSizing.tpSlMultiplier(signal.score, signal.confidence)

        // V5.8.0: Apply Hivemind size/TP modifier (stacked with fluid multipliers)
        val (_, hiveSizeMult, hiveTpAdj) = hiveEntryModifier(signal.market.symbol)
        val hiveSizeSol = (sizeSol * hiveSizeMult * fluidSizeMult).coerceIn(0.01, balance * 0.30)
        val hiveTpPct = ((tpPct * fluidTpMult) + hiveTpAdj).coerceAtLeast(1.5)
        val fluidSlPct = (slPct * fluidSlMult).coerceAtLeast(1.0)
        if (hiveSizeMult != 1.0 || hiveTpAdj != 0.0 || fluidSizeMult != 1.0) {
            ErrorLogger.info(TAG, "📈 ${signal.market.symbol}: fluid sz×${"%.2f".format(fluidSizeMult)} tp×${"%.2f".format(fluidTpMult)} sl×${"%.2f".format(fluidSlMult)} | hive sz×${"%.2f".format(hiveSizeMult)} tp+${hiveTpAdj}%")
        }

        val (tp, sl) = when (signal.direction) {
            PerpsDirection.LONG -> {
                signal.price * (1 + hiveTpPct / 100) to signal.price * (1 - fluidSlPct / 100)
            }
            PerpsDirection.SHORT -> {
                signal.price * (1 - hiveTpPct / 100) to signal.price * (1 + fluidSlPct / 100)
            }
        }
        
        val position = StockPosition(
            id = "STOCK_${positionIdCounter.incrementAndGet()}",
            market = signal.market,
            direction = signal.direction,
            entryPrice = signal.price,
            currentPrice = signal.price,
            sizeSol = hiveSizeSol,
            leverage = leverage,
            takeProfitPrice = tp,
            stopLossPrice = sl,
            aiScore = signal.score,
            aiConfidence = signal.confidence,
            reasons = signal.reasons
        )
        
        // V5.7.6b: Add to appropriate map
        positions[position.id] = position
        if (isSpot) {
            spotPositions[position.id] = position
        } else {
            leveragePositions[position.id] = position
        }
        totalTrades.incrementAndGet()
        // V5.9.178 — persist open stock positions locally (app-update recovery).
        persistStockPositions()

        // V5.9.130: register V3 entry for real-accuracy close loop.
        // V5.9.170: feed entry reason chain into the education layer.
        try {
            PerpsUnifiedScorerBridge.registerEntry(
                symbol = signal.market.symbol,
                assetClass = "STOCK",
                direction = signal.direction.name,
                entryPrice = signal.price,
                entryLiqUsd = 10_000_000.0,
                v3Score = signal.score,
                entryReason = signal.reasons.take(6).joinToString("|").ifBlank { "Stocks:${signal.direction.name}" },
                traderSource = "Stocks",
            )
        } catch (_: Exception) {}
        
        // V5.9.114: UNIFIED capital-move. Paper debits paper wallet;
        // live fires a Jupiter swap at the EXACT same hiveSizeSol so
        // sizing learnt in paper carries 1:1 into live. If the live swap
        // fails, we DO NOT keep the position in our map (roll back).
        if (isPaperMode.get()) {
            com.lifecyclebot.engine.FluidLearning.recordPaperBuy("TokenizedStockTrader", hiveSizeSol.coerceAtLeast(0.0))
            // V5.9.48: unified paper wallet — debit the deployed capital so the
            // main dashboard shows the real free cash while the position is open.
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = -hiveSizeSol,
                source = "TokenizedStocks.open[${signal.market.symbol}]"
            )
            // V5.9.171 — local orphan failsafe. Refunds paper capital on next
            // startup if the app is wiped mid-trade, even when Turso is offline.
            try {
                com.lifecyclebot.collective.LocalOrphanStore.recordOpen(
                    trader = "Stocks",
                    posId = position.id,
                    sizeSol = hiveSizeSol,
                    symbol = signal.market.symbol,
                )
            } catch (_: Exception) {}
        } else {
            val liveOk = executeLiveTradeAtSize(signal, isSpot, hiveSizeSol)
            if (!liveOk) {
                // Roll back: remove the position we just inserted.
                positions.remove(position.id)
                spotPositions.remove(position.id)
                leveragePositions.remove(position.id)
                totalTrades.decrementAndGet()
                ErrorLogger.warn(TAG, "🔴 LIVE stock trade failed: ${signal.market.symbol} — position rolled back")
                return
            }
            savePersistedState()
        }
        
        // Notify FluidLearningAI
        // V5.7.6b: Use Markets-specific recording to avoid affecting Meme thresholds
        try {
            FluidLearningAI.recordMarketsTradeStart()
        } catch (_: Exception) {}
        
        val leverageStr = if (isSpot) "1x SPOT" else "${leverage.toInt()}x LEV"
        ErrorLogger.info(TAG, "📈 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | " +
            "$leverageStr | size=${sizeSol.fmt(3)}◎ | score=${signal.score} | TP=\$${tp.fmt(2)} SL=\$${sl.fmt(2)}")
        
        signal.reasons.take(3).forEach { reason ->
            ErrorLogger.debug(TAG, "   → $reason")
        }
        
        // V5.7.6b: Persist position to Turso for recovery
        persistPositionToTurso(position)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun monitorPositions() {
        for ((id, position) in positions) {
            try {
                // Update current price
                val data = PerpsMarketDataFetcher.getMarketData(position.market)
                position.currentPrice = data.price
                
                // V5.9.9: FULLY AGENTIC EXIT — SymbolicExitReasoner every cycle
                val holdSec = (System.currentTimeMillis() - position.entryTime) / 1000
                val pnlPct = position.getUnrealizedPnlPct()
                val peakPnl = position.peakPnlPct.coerceAtLeast(pnlPct)
                position.peakPnlPct = peakPnl

                // V5.9.221: HARD TIME EXITS for stocks — prevents universe saturation.
                // Stock universe is only 51 markets; if positions never die the scanner
                // stalls at analyzed=0 indefinitely.
                //  - 2h hard cap: always exit (take whatever P&L we have)
                //  - 45min + negative: cut losers early, recycle capital
                //  - 30min + <1% gain: stale flat position, flush it
                // V5.9.221 time exits — call closePosition then continue to next position
                var timeExited = false
                when {
                    holdSec > 7200 -> { closePosition(id, "TIME_CAP_2H: pnl=${"%.2f".format(pnlPct)}%"); timeExited = true }
                    holdSec > 2700 && pnlPct < 0.0 -> { closePosition(id, "TIME_CUT_45MIN_LOSS: pnl=${"%.2f".format(pnlPct)}%"); timeExited = true }
                    holdSec > 1800 && pnlPct < 1.0 -> { closePosition(id, "TIME_FLUSH_30MIN_FLAT: pnl=${"%.2f".format(pnlPct)}%"); timeExited = true }
                }
                if (timeExited) continue

                val priceVel = if (holdSec > 30) pnlPct / (holdSec / 60.0) else 0.0
                val assessment = com.lifecyclebot.engine.SymbolicExitReasoner.assess(
                    currentPnlPct   = pnlPct,
                    peakPnlPct      = peakPnl,
                    entryConfidence = position.aiConfidence.toDouble(),
                    tradingMode     = "TokenizedStockAI",
                    holdTimeSec     = holdSec,
                    priceVelocity   = priceVel,
                    symbol          = position.market.symbol
                )

                when (assessment.suggestedAction) {
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.EXIT ->
                        closePosition(id, "AI_EXIT: ${assessment.primarySignal} (${String.format("%.2f", assessment.conviction)})")
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.PARTIAL ->
                        closePosition(id, "AI_PARTIAL: ${assessment.primarySignal} (${String.format("%.2f", assessment.conviction)})")
                    else -> {
                        // Safety net: extreme TP only
                        if (position.shouldTakeProfit()) closePosition(id, "TP_SAFETY")
                        if (position.shouldStopLoss()) closePosition(id, "SL_SAFETY")
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Monitor error for $id: ${e.message}")
            }
        }
    }
    
    private fun closePosition(positionId: String, reason: String) {
        val position = positions.remove(positionId) ?: return
        
        // V5.7.6b: Remove from appropriate map
        spotPositions.remove(positionId)
        leveragePositions.remove(positionId)
        
        // V5.7.6b: Remove from Turso
        removePositionFromTurso(positionId)

        // V5.9.178 — persist the close locally so reopening the app doesn't restore a closed trade.
        persistStockPositions()

        // V5.9.171 — clear local orphan record (paper capital is being
        // returned to the unified wallet at the bottom of this function).
        try { com.lifecyclebot.collective.LocalOrphanStore.clear(positionId) } catch (_: Exception) {}

        // V5.9.130: close V3 learning loop → drives real accuracy update on
        // every one of the 41 AI layers based on how this stock trade resolved
        // vs what each layer predicted.
        // V5.9.170: include real exit reason so the education firehose
        // captures WHY the stock exited, not just the pct.
        try {
            PerpsUnifiedScorerBridge.recordClose(
                symbol = position.market.symbol,
                assetClass = "STOCK",
                pnlPct = position.getUnrealizedPnlPct(),
                exitReason = reason.ifBlank { "stock_close" },
                lossReason = if (position.getUnrealizedPnlPct() < -2.0) reason else "",
            )
        } catch (_: Exception) {}
        
        // V5.7.7: Calculate P&L with fee deduction
        val grossPnlPct = position.getUnrealizedPnlPct()
        val grossPnlSol = position.getUnrealizedPnlSol()
        
        // Deduct trading fees (open + close)
        val feePercent = if (position.isSpot) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val totalFeeSol = position.sizeSol * feePercent * 2  // Fee on open + close
        val netPnlSol = grossPnlSol - totalFeeSol
        val netPnlPct = if (position.sizeSol > 0) (netPnlSol / position.sizeSol) * 100 else 0.0
        
        val isWin = netPnlPct > 0
        
        // Update stats (use net P&L)
        totalTrades.incrementAndGet()
        if (isWin) winningTrades.incrementAndGet() else losingTrades.incrementAndGet()
        totalPnlSol += netPnlSol
        
        // V5.7.7 FIX: Execute live on-chain close — MUST wait for result
        if (!isPaperMode.get()) {
            var closeSuccess = false
            try {
                closeSuccess = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val (ok, _) = MarketsLiveExecutor.closeLivePosition(
                        market = position.market,
                        direction = position.direction,
                        sizeSol = position.sizeSol,
                        leverage = position.leverage,
                        traderType = "TokenizedStocks",
                    )
                    ok
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Live close failed for ${position.market.symbol}: ${e.message}")
            }
            if (!closeSuccess) {
                ErrorLogger.warn(TAG, "🚨 LIVE CLOSE FAILED: ${position.market.symbol} — position kept open")
                return
            }
            try {
                val newBal = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBal)
            } catch (_: Exception) {}
        } else {
            // V5.9.7: balance update handled by FluidLearning.recordPaperSell below
            // V5.9.48: Unified paper wallet — credit capital + PnL back to main dashboard.
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = position.sizeSol + netPnlSol,
                source = "TokenizedStocks.close[${position.market.symbol}]"
            )
        }
        
        // Record to FluidLearningAI
        // V5.7.6b: Use Markets-specific recording to avoid affecting Meme thresholds
        try {
            if (isPaperMode.get()) FluidLearningAI.recordMarketsPaperTrade(isWin, netPnlPct)
            else FluidLearningAI.recordMarketsLiveTrade(isWin, netPnlPct)
        } catch (_: Exception) {}
        // V5.9.6: Sync closed P&L to shared FluidLearning pool so main bot balance updates
        if (isPaperMode.get()) try {
            com.lifecyclebot.engine.FluidLearning.recordPaperSell(
                mint = position.market.symbol,
                originalSol = position.sizeSol,
                pnlSol = netPnlSol
            )
        } catch (_: Exception) {}
        
        // V5.7.6: Record to PerpsLearningBridge for unified tracking
        try {
            PerpsLearningBridge.recordStockTrade(position.market, position.direction, isWin, netPnlPct)
        } catch (_: Exception) {}
        
        // V5.7.8: Record to V4 TradeLessonRecorder (full causal chain)
        try {
            val holdSec = ((System.currentTimeMillis() - position.entryTime) / 1000).toInt()
            com.lifecyclebot.v4.meta.TradeLessonRecorder.completeLesson(
                context = com.lifecyclebot.v4.meta.TradeLessonRecorder.TradeLessonContext(
                    strategy = "TokenizedStockAI",
                    market = if (position.market.isStock) "STOCKS" else "PERPS",
                    symbol = position.market.symbol,
                    entryRegime = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime(),
                    entrySession = com.lifecyclebot.v4.meta.SessionContext.OFF_HOURS,
                    trustScore = com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustScore("TokenizedStockAI"),
                    fragilityScore = com.lifecyclebot.v4.meta.LiquidityFragilityAI.getFragilityScore(position.market.symbol),
                    narrativeHeat = com.lifecyclebot.v4.meta.NarrativeFlowAI.getNarrativeHeat(position.market.symbol),
                    portfolioHeat = com.lifecyclebot.v4.meta.PortfolioHeatAI.getPortfolioHeat(),
                    leverageUsed = position.leverage,
                    executionConfidence = 0.8,
                    leadSource = com.lifecyclebot.v4.meta.CrossAssetLeadLagAI.getLeadSignalFor(position.market.symbol)?.leader,
                    expectedDelaySec = null,
                    executionRoute = "JUPITER_V6",
                    expectedFillPrice = position.entryPrice,
                    captureTime = position.entryTime
                ),
                outcomePct = netPnlPct,
                mfePct = netPnlPct.coerceAtLeast(0.0),
                maePct = netPnlPct.coerceAtMost(0.0),
                holdSec = holdSec,
                exitReason = reason,
                actualFillPrice = position.currentPrice
            )
            // Remove from portfolio heat tracker
            com.lifecyclebot.v4.meta.PortfolioHeatAI.removePosition("MARKET_${position.market.symbol}")
        } catch (_: Exception) {}
        
        // Record pattern for learning
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(position.market)
            PerpsAdvancedAI.recordPattern(
                position.market,
                position.direction,
                technicals,
                isWin,
                netPnlPct
            )
            
            // Record time of day
            val entryHour = java.util.Calendar.getInstance().apply {
                timeInMillis = position.entryTime
            }.get(java.util.Calendar.HOUR_OF_DAY)
            PerpsAdvancedAI.recordHourlyTrade(entryHour, isWin, netPnlPct)
        } catch (_: Exception) {}
        
        val holdMins = (System.currentTimeMillis() - position.entryTime) / 60_000
        val emoji = when {
            netPnlPct >= 20 -> "🚀"
            netPnlPct >= 10 -> "🎯"
            netPnlPct > 0 -> "✅"
            netPnlPct > -5 -> "😐"
            else -> "💀"
        }
        
        val feeStr = "(fee: ${totalFeeSol.fmt(4)}◎)"
        ErrorLogger.info(TAG, "$emoji CLOSED: ${position.market.symbol} | $reason | " +
            "P&L: ${if (netPnlPct >= 0) "+" else ""}${netPnlPct.fmt(1)}% (${if (netPnlSol >= 0) "+" else ""}${netPnlSol.fmt(4)}◎) $feeStr | " +
            "hold=${holdMins}m | WR=${getWinRate().toInt()}%")
        
        // V5.9.109: feed whole-bot 30-day sheet so wins from Stocks
        // aren't wiped on update (previously only Turso-persisted per-trader).
        try {
            if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                com.lifecyclebot.engine.RunTracker30D.recordTrade(
                    symbol      = position.market.symbol,
                    mint        = position.market.symbol,
                    entryPrice  = position.entryPrice,
                    exitPrice   = position.currentPrice,
                    sizeSol     = position.sizeSol,
                    pnlPct      = netPnlPct,
                    holdTimeSec = (System.currentTimeMillis() - position.entryTime) / 1000,
                    mode        = "Stocks_${if (position.isSpot) "SPOT" else "${position.leverage.toInt()}x"}",
                    score       = 50,
                    confidence  = 50,
                    decision    = reason
                )
            }
        } catch (_: Exception) {}

        // V5.9.112: feed live PnL into LiveSafetyCircuitBreaker for session drawdown halt.
        if (!isPaperMode.get()) {
            try { com.lifecyclebot.engine.LiveSafetyCircuitBreaker.recordTradeResult(netPnlSol) } catch (_: Exception) {}
        }

        // V5.9.211: Sentience wiring — all 41 layers + personality memory
        try {
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordSimpleTradeOutcome(
                symbol    = position.market.symbol,
                mint      = position.market.symbol,
                pnlPct    = netPnlPct,
                holdMins  = holdMins.toDouble(),
                traderTag = "STOCKS",
                exitReason = reason,
            )
            com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                pnlPct              = netPnlPct,
                gaveBackFromPeakPct = 0.0,
                heldMinutes         = holdMins.toInt().coerceAtLeast(1),
            )
            if (netPnlPct >= 1.0) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(position.market.symbol, netPnlPct, "STOCKS", holdMins * 60)
            } else {
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(position.market.symbol, netPnlPct, "STOCKS", reason)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Sentience hook error: ${e.message}")
        }


        // V5.9.248: Log stocks trades to shared TradeHistoryStore (live journal)
        try {
            val modeStr248 = if (isPaperMode.get()) "paper" else "live"
            TradeHistoryStore.recordTrade(Trade(
                side             = "SELL",
                mode             = modeStr248,
                sol              = position.sizeSol,
                price            = position.currentPrice,
                ts               = System.currentTimeMillis(),
                reason           = "Stocks:$reason",
                pnlSol           = netPnlSol,
                pnlPct           = netPnlPct,
                score            = 50.0,
                tradingMode      = "Stocks",
                tradingModeEmoji = "📈",
                mint             = position.market.symbol,
            ))
        } catch (_: Exception) {}

        // V5.7.6b: Persist to Turso for learning memory (with net P&L)
        persistTradeToTurso(position, reason, netPnlSol, netPnlPct, isWin, holdMins)
        
        // V5.7.7: Save state after each trade
        savePersistedState()
        
        // V5.7.8: Save V4 strategy trust to Turso after each close
        try {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.lifecyclebot.v4.meta.TradeLessonRecorder.saveAllTrustToTurso()
            }
        } catch (_: Exception) {}
    }
    
    /**
     * V5.7.6b: Persist trade to Turso database for AI learning memory
     */
    private fun persistTradeToTurso(
        position: StockPosition,
        closeReason: String,
        pnlSol: Double,
        pnlPct: Double,
        isWin: Boolean,
        holdMins: Long
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                
                // Get SOL price for USD conversion
                val solPrice = try {
                    PerpsMarketDataFetcher.getSolPrice()
                } catch (_: Exception) { 150.0 }
                
                val tradeRecord = MarketsTradeRecord(
                    tradeHash = "STOCK_${position.id}_${System.currentTimeMillis()}",
                    instanceId = instanceId,
                    assetClass = "STOCK",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = if (position.isSpot) "SPOT" else "LEVERAGE",
                    entryPrice = position.entryPrice,
                    exitPrice = position.currentPrice,
                    sizeSol = position.sizeSol,
                    sizeUsd = position.sizeSol * solPrice,
                    leverage = position.leverage,
                    pnlSol = pnlSol,
                    pnlUsd = pnlSol * solPrice,
                    pnlPct = pnlPct,
                    openTime = position.entryTime,
                    closeTime = System.currentTimeMillis(),
                    closeReason = closeReason,
                    aiScore = position.aiScore,
                    aiConfidence = position.aiConfidence,
                    paperMode = isPaperMode.get(),
                    isWin = isWin,
                    holdMins = holdMins.toDouble()
                )
                
                val saved = client.saveMarketsTradeRecord(tradeRecord)
                if (saved) {
                    ErrorLogger.debug(TAG, "📊 Trade persisted to Turso: ${position.market.symbol}")
                    
                    // Also update asset performance
                    client.updateMarketsAssetPerformance(
                        assetClass = "STOCK",
                        market = position.market.symbol,
                        isSpot = position.isSpot,
                        isWin = isWin,
                        pnlPct = pnlPct,
                        holdMins = holdMins.toDouble()
                    )
                    
                    // Update daily stats
                    client.updateMarketsDailyStats(
                        instanceId = instanceId,
                        assetClass = "STOCK",
                        isWin = isWin,
                        pnlUsd = pnlSol * solPrice
                    )
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Turso persist error: ${e.message}")
            }
        }
    }
    
    /**
     * V5.7.6b: Save current position to Turso (for recovery after restart)
     */
    private fun persistPositionToTurso(position: StockPosition) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                
                val solPrice = try {
                    PerpsMarketDataFetcher.getSolPrice()
                } catch (_: Exception) { 150.0 }
                
                val posRecord = MarketsPositionRecord(
                    id = position.id,
                    instanceId = instanceId,
                    assetClass = "STOCK",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = if (position.isSpot) "SPOT" else "LEVERAGE",
                    entryPrice = position.entryPrice,
                    currentPrice = position.currentPrice,
                    sizeSol = position.sizeSol,
                    sizeUsd = position.sizeSol * solPrice,
                    leverage = position.leverage,
                    takeProfitPrice = position.takeProfit,
                    stopLossPrice = position.stopLoss,
                    entryTime = position.entryTime,
                    aiScore = position.aiScore,
                    aiConfidence = position.aiConfidence,
                    paperMode = isPaperMode.get(),
                    status = "OPEN",
                    lastUpdate = System.currentTimeMillis()
                )
                
                client.saveMarketsPosition(posRecord)
                ErrorLogger.debug(TAG, "📊 Position saved to Turso: ${position.market.symbol}")
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Position save error: ${e.message}")
            }
        }
    }
    
    /**
     * V5.7.6b: Remove closed position from Turso
     */
    private fun removePositionFromTurso(positionId: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                client.deleteMarketsPosition(positionId)
            } catch (_: Exception) {}
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getBalance(): Double = if (isPaperMode.get()) com.lifecyclebot.engine.BotService.status.paperWalletSol else liveWalletBalance
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        com.lifecyclebot.engine.FluidLearning.forceSetBalance(balance)
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader balance set to ${"%.2f".format(balance)} SOL")
    }
    
    fun getActivePositions(): List<StockPosition> = positions.values.toList()
    
    fun hasPosition(market: PerpsMarket): Boolean = positions.values.any { it.market == market }
    
    fun getWinRate(): Double {
        val total = winningTrades.get() + losingTrades.get()
        return if (total > 0) (winningTrades.get().toDouble() / total * 100) else 50.0
    }
    
    fun getTotalTrades(): Int = totalTrades.get()
    fun getWinningTrades(): Int = winningTrades.get()
    
    fun getTotalPnlSol(): Double = totalPnlSol
    
    fun getStats(): Map<String, Any> = mapOf(
        "isRunning" to isRunning.get(),
        "isPaperMode" to isPaperMode.get(),
        "positions" to positions.size,
        "maxPositions" to MAX_STOCK_POSITIONS,
        "balance" to getBalance(),
        "totalTrades" to totalTrades.get(),
        "winRate" to getWinRate(),
        "totalPnlSol" to totalPnlSol,
        "scans" to scanCount.get()
    )
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    fun isEnabled(): Boolean = isEnabled.get()
    
    // V5.7.6: Public running state accessor for UI
    fun isRunning(): Boolean = isRunning.get()

    /** Returns true only if running AND engine/monitor coroutines are actually alive. */
    fun isHealthy(): Boolean {
        if (!isRunning.get()) return false
        return (engineJob?.isActive == true) && (monitorJob?.isActive == true)
    }
    
    // V5.7.6b: SPOT vs LEVERAGE position getters - now use dedicated maps
    fun getSpotPositions(): List<StockPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<StockPosition> = leveragePositions.values.toList()

    /**
     * Add SOL to an existing open position (scale-in / pyramid).
     * Returns true if a matching open position was found and averaged-in.
     */
    fun addToPosition(market: PerpsMarket, additionalSol: Double): Boolean {
        val pos = positions.values.firstOrNull { it.market == market } ?: return false
        val currentPrice = try {
            PerpsMarketDataFetcher.getCachedPrice(market)?.price?.takeIf { it > 0 } ?: pos.currentPrice
        } catch (_: Exception) { pos.currentPrice }
        if (currentPrice <= 0) return false

        val totalCost = pos.sizeSol + additionalSol
        val blendedEntry = (pos.entryPrice * pos.sizeSol + currentPrice * additionalSol) / totalCost

        val updated = pos.copy(
            sizeSol = totalCost,
            entryPrice = blendedEntry,
            currentPrice = currentPrice
        )
        positions[pos.id] = updated
        if (pos.isSpot) spotPositions[pos.id] = updated else leveragePositions[pos.id] = updated

        ErrorLogger.info(TAG, "addToPosition ${market.symbol} +$additionalSol SOL | newEntry=$blendedEntry | newSize=$totalCost")
        return true
    }

    fun getAllPositions(): List<StockPosition> = positions.values.toList()

    /**
     * V5.9.85: Public manual-close for Markets UI. Returns true when the id
     * matches an open position (both SPOT and LEVERAGE tables are checked).
     */
    fun closePositionManual(positionId: String, reason: String = "USER"): Boolean {
        if (positionId.isBlank()) return false
        if (positions[positionId] == null &&
            spotPositions[positionId] == null &&
            leveragePositions[positionId] == null
        ) {
            return false
        }
        closePosition(positionId, reason)
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6b: LIVE TRADING MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Check if trading in LIVE mode */
    fun isLiveMode(): Boolean = !isPaperMode.get()
    
    /** Check if trading in PAPER mode */
    fun isPaperMode(): Boolean = isPaperMode.get()
    
    /** Switch to LIVE mode - REAL MONEY TRADING */
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader mode: ${if (live) "🔴 LIVE" else "📄 PAPER"}")
        if (live) {
            // Sync actual wallet balance so live trading doesn't start at 0
            try {
                val balance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (balance > 0) updateLiveBalance(balance)
                ErrorLogger.info(TAG, "📈 Live wallet balance: ${"%.4f".format(liveWalletBalance)} SOL")
            } catch (_: Exception) {}
        }
    }
    
    /** Sync paper wallet balance — delegates to shared FluidLearning pool */

    /** Update live wallet balance from connected wallet */
    fun updateLiveBalance(balanceSol: Double) {
        liveWalletBalance = balanceSol
        ErrorLogger.info(TAG, "📈 Live wallet balance updated: ${"%.4f".format(balanceSol)} SOL")
    }
    
    /** Get balance based on current mode */
    // V5.9.8: Read from shared BotService pool — same wallet as main AATE and CryptoAlt
    fun getEffectiveBalance(): Double {
        return if (isPaperMode.get())
            com.lifecyclebot.engine.BotService.status.paperWalletSol
        else liveWalletBalance
    }
    
    /** Execute LIVE trade via MarketsLiveExecutor
     * V5.7.6b: Fully wired to on-chain execution via Jupiter API
     */
    /**
     * V5.9.114: LIVE Jupiter swap at a CALLER-provided size so live mirrors
     * paper's exact sizing pipeline. Returns true only after swap + phantom
     * verify both succeeded.
     */
    private suspend fun executeLiveTradeAtSize(
        signal: StockSignal,
        isSpot: Boolean,
        sizeSol: Double,
    ): Boolean {
        val leverage = if (isSpot) 1.0 else signal.leverage
        if (liveWalletBalance <= 0.0) {
            try {
                val fresh = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (fresh > 0) updateLiveBalance(fresh)
            } catch (_: Exception) {}
        }
        val balance = getEffectiveBalance()
        val floor = 0.01
        if (balance < floor || sizeSol < floor) {
            ErrorLogger.warn(TAG, "🔴 LIVE: bal=${balance.fmt(4)}◎ size=${sizeSol.fmt(4)} — cannot trade ${signal.market.symbol}")
            com.lifecyclebot.engine.LiveAttemptStats.record(
                "TokenizedStocks",
                com.lifecyclebot.engine.LiveAttemptStats.Outcome.FLOOR_SKIPPED
            )
            return false
        }
        ErrorLogger.info(TAG, "🔴 LIVE TRADE: ${signal.direction.emoji} ${signal.market.symbol}")
        ErrorLogger.info(TAG, "🔴 Price: \$${signal.price.fmt(2)} | ${if (isSpot) "SPOT" else "${leverage.toInt()}x"}")
        ErrorLogger.info(TAG, "🔴 Size (paper-matched): ${sizeSol.fmt(4)} SOL | Score: ${signal.score}")
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = sizeSol,
            leverage = leverage,
            priceUsd = signal.price,
            traderType = "TokenizedStocks",
        )
        if (success) {
            val txLog = txSignature?.take(16)?.let { "tx=$it..." } ?: "bridge-collateral"
            ErrorLogger.info(TAG, "🔴 LIVE SUCCESS: ${signal.market.symbol} | $txLog")
            com.lifecyclebot.engine.LiveAttemptStats.record(
                "TokenizedStocks",
                com.lifecyclebot.engine.LiveAttemptStats.Outcome.EXECUTED
            )
            try {
                val newBalance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBalance)
            } catch (_: Exception) {}
            return true
        }
        ErrorLogger.warn(TAG, "🔴 LIVE FAILED: ${signal.market.symbol}")
        com.lifecyclebot.engine.LiveAttemptStats.record(
            "TokenizedStocks",
            com.lifecyclebot.engine.LiveAttemptStats.Outcome.FAILED
        )
        return false
    }

    private suspend fun executeLiveTrade(signal: StockSignal, isSpot: Boolean): Double? {
        val leverage = if (isSpot) 1.0 else signal.leverage
        // V5.7.7 FIX: Refresh live wallet balance if uninitialized (0) — prevents all trades being silently blocked
        if (liveWalletBalance <= 0.0) {
            try {
                val fresh = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (fresh > 0) updateLiveBalance(fresh)
            } catch (_: Exception) {}
        }

        // V5.9.37: FLUID sizing. 5% of a small wallet falls below the Jupiter
        // ~0.01 SOL swap floor, silently killing every live attempt. Clamp
        // size to [FLOOR, balance * 20%] so small wallets still participate.
        val balance = getEffectiveBalance()
        val floor = 0.01
        val desired = balance * (DEFAULT_SIZE_PCT / 100)
        if (balance < floor) {
            ErrorLogger.warn(TAG, "🔴 LIVE: wallet ${balance.fmt(4)}◎ < ${floor} floor — cannot trade ${signal.market.symbol}")
            com.lifecyclebot.engine.LiveAttemptStats.record(
                "TokenizedStocks",
                com.lifecyclebot.engine.LiveAttemptStats.Outcome.FLOOR_SKIPPED
            )
            return null
        }
        val sizeSol = desired.coerceIn(floor, (balance * 0.20).coerceAtLeast(floor))
        
        ErrorLogger.info(TAG, "🔴 LIVE TRADE: ${signal.direction.emoji} ${signal.market.symbol}")
        ErrorLogger.info(TAG, "🔴 Price: \$${signal.price.fmt(2)} | ${if (isSpot) "SPOT" else "${leverage.toInt()}x"}")
        ErrorLogger.info(TAG, "🔴 Size: ${sizeSol.fmt(4)} SOL | Score: ${signal.score}")
        
        // Execute via MarketsLiveExecutor
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = sizeSol,
            leverage = leverage,
            priceUsd = signal.price,
            traderType = "TokenizedStocks",
        )
        
        // V5.9.2: success=true is the authority; txSignature can be null for bridge trades
        if (success) {
            val txLog = txSignature?.take(16)?.let { "tx=$it..." } ?: "bridge-collateral"
            ErrorLogger.info(TAG, "🔴 LIVE SUCCESS: ${signal.market.symbol} | $txLog")
            com.lifecyclebot.engine.LiveAttemptStats.record(
                "TokenizedStocks",
                com.lifecyclebot.engine.LiveAttemptStats.Outcome.EXECUTED
            )
            
            // Update live wallet balance
            try {
                val newBalance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBalance)
            } catch (_: Exception) {}
            
            return sizeSol
        } else {
            ErrorLogger.warn(TAG, "🔴 LIVE FAILED: ${signal.market.symbol}")
            com.lifecyclebot.engine.LiveAttemptStats.record(
                "TokenizedStocks",
                com.lifecyclebot.engine.LiveAttemptStats.Outcome.FAILED
            )
            return null
        }
    }
    
    // V5.9.320: Removed private `Double.fmt` — caused overload resolution
    // ambiguity with the public `Double.fmt` in PerpsModels.kt. The public
    // one is functionally identical (uses String.format) and is already
    // imported by sibling perps files.
}


