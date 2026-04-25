package com.lifecyclebot.perps

import com.lifecyclebot.collective.CollectiveSchema
import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.collective.MarketsTradeRecord
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * V5.7.6: COMMODITIES TRADER
 * Dedicated trading engine for commodities:
 * - Energy: Oil (Brent, WTI), Natural Gas, Gasoline, Heating Oil
 * - Agricultural: Corn, Wheat, Soybeans, Coffee, Sugar, etc.
 * 
 * 24/7 trading via Pyth Oracle price feeds
 */
object CommoditiesTrader {
    private const val TAG = "CommoditiesTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MAX_POSITIONS = 100   // V5.9.100: user req — each trader can hold 100
    private const val SCAN_INTERVAL_MS = 20_000L  // 20 seconds
    private const val DEFAULT_SIZE_PCT = 5.0  // 5% of balance per trade (matches TokenizedStockTrader)
    // V5.9.8: TP now dynamic via FluidLearningAI.getMarketsSpotTpPct() / getMarketsLevTpPct()
    private const val SL_PERCENT_SPOT = 3.0       // Tighter SL for spot
    // (static TP_PERCENT constants removed V5.9.8)
    private const val SL_PERCENT_LEVERAGE = 5.0
    private const val SPOT_TRADING_FEE_PERCENT = 0.005     // 0.5% for spot (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01  // 1.0% for leverage (5x)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spotPositions = ConcurrentHashMap<String, CommodityPosition>()      // SPOT (1x)
    private val leveragePositions = ConcurrentHashMap<String, CommodityPosition>()  // LEVERAGE (5x)
    private val isRunning = AtomicBoolean(false)
    private val isEnabled = AtomicBoolean(true)
    private val isPaperMode = AtomicBoolean(true)
    private val scanCount = AtomicInteger(0)
    
    // V5.9.7: paperBalance now delegates to shared FluidLearning pool
    private var paperBalance: Double
        get() = com.lifecyclebot.engine.BotService.status.paperWalletSol
        set(value) { com.lifecyclebot.engine.FluidLearning.forceSetBalance(value) }
    private val totalTrades   = java.util.concurrent.atomic.AtomicInteger(0)
    private val winningTrades = java.util.concurrent.atomic.AtomicInteger(0)
    private val losingTrades  = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var totalPnlSol = 0.0
    @Volatile private var appCtx: android.content.Context? = null
    private val PREFS_NAME = "commodities_trader_v1"
    private val KEY_BALANCE = "paper_balance"
    private val KEY_TRADES  = "total_trades"
    private val KEY_WINS    = "winning_trades"
    private val KEY_LOSSES  = "losing_trades"
    private val KEY_PNL     = "total_pnl_sol"
    private var engineJob: Job? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class TradeType(val emoji: String, val leverage: Double) {
        SPOT("💰", 1.0),        // No leverage - simple buy/sell
        LEVERAGE("⚡", 5.0)     // 5x leverage
    }
    
    data class CommodityPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val tradeType: TradeType,
        val entryPrice: Double,
        var currentPrice: Double,
        val size: Double,
        val takeProfit: Double,
        val stopLoss: Double,
        val openTime: Long = System.currentTimeMillis(),
        var closeTime: Long? = null,
        var closePrice: Double? = null,
        var realizedPnl: Double? = null,
        val reasons: List<String> = emptyList(),
        val aiConfidence: Int = 50,
        var peakPnlPct: Double = 0.0
    ) {
        val leverage: Double get() = tradeType.leverage
        val isSpot: Boolean get() = tradeType == TradeType.SPOT
        
        fun getPnlPercent(): Double {
            val priceDiff = currentPrice - entryPrice
            val dir = if (direction == PerpsDirection.LONG) 1 else -1
            return (priceDiff / entryPrice) * 100.0 * dir * leverage
        }
        
        fun getPnlSol(): Double = size * (getPnlPercent() / 100.0)
        
        fun shouldTakeProfit(): Boolean {
            val tp = if (isSpot)
                com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
            else com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsLevTpPct()
            return getPnlPercent() >= tp
        }
        
        fun shouldStopLoss(): Boolean {
            val sl = if (isSpot) SL_PERCENT_SPOT else SL_PERCENT_LEVERAGE
            return getPnlPercent() <= -sl
        }
    }
    
    data class CommoditySignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val tradeType: TradeType,
        val score: Int,
        val confidence: Int,
        val price: Double,
        val priceChange24h: Double,
        val reasons: List<String>
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENGINE CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize(context: android.content.Context? = null) {
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader INITIALIZED")
        if (context != null) {
            try {
                PerpsPositionStore.init(context.applicationContext, "commodities")
                val rehydrated = PerpsPositionStore.loadAll("commodities")
                rehydrated.forEach { j ->
                    try {
                        val pos = commodityPositionFromJson(j)
                        if (pos.isSpot) spotPositions[pos.id]     = pos
                        else            leveragePositions[pos.id] = pos
                    } catch (_: Exception) {}
                }
                if (rehydrated.isNotEmpty()) {
                    ErrorLogger.info(TAG, "🛢️ REHYDRATED ${rehydrated.size} commodities positions (app-update recovery)")
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "commodities rehydrate failed: ${e.message}")
            }
        }
    }

    private fun commodityPositionToJson(p: CommodityPosition): org.json.JSONObject =
        org.json.JSONObject()
            .put("id", p.id).put("market", p.market.name).put("direction", p.direction.name)
            .put("tradeType", p.tradeType.name).put("entryPrice", p.entryPrice)
            .put("currentPrice", p.currentPrice).put("size", p.size)
            .put("takeProfit", p.takeProfit).put("stopLoss", p.stopLoss)
            .put("openTime", p.openTime).put("aiConfidence", p.aiConfidence)
            .put("reasons", org.json.JSONArray(p.reasons)).put("peakPnlPct", p.peakPnlPct)

    private fun commodityPositionFromJson(j: org.json.JSONObject): CommodityPosition {
        val rArr = j.optJSONArray("reasons")
        val reasons = if (rArr != null) (0 until rArr.length()).map { rArr.optString(it, "") } else emptyList()
        return CommodityPosition(
            id = j.getString("id"), market = PerpsMarket.valueOf(j.getString("market")),
            direction = PerpsDirection.valueOf(j.getString("direction")),
            tradeType = TradeType.valueOf(j.getString("tradeType")),
            entryPrice = j.getDouble("entryPrice"), currentPrice = j.getDouble("currentPrice"),
            size = j.getDouble("size"), takeProfit = j.getDouble("takeProfit"),
            stopLoss = j.getDouble("stopLoss"),
            openTime = j.optLong("openTime", System.currentTimeMillis()),
            reasons = reasons, aiConfidence = j.optInt("aiConfidence", 50),
        ).apply { peakPnlPct = j.optDouble("peakPnlPct", 0.0) }
    }

    private fun persistCommodityPositions() {
        try {
            val all = (spotPositions.values + leveragePositions.values).map { commodityPositionToJson(it) }
            PerpsPositionStore.saveAll("commodities", all)
        } catch (_: Exception) {}
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
            ErrorLogger.info(TAG, "🛢️🛢️🛢️ CommoditiesTrader ENGINE STARTED 🛢️🛢️🛢️")
            
            // Initial scan
            try {
                runScanCycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Initial scan error: ${e.message}", e)
            }

            // Main loop
            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)
                    if (isEnabled.get()) {
                        runScanCycle()
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
                    delay(5000)
                    monitorPositions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Monitor error: ${e.message}", e)
                }
            }
        }
    }

    // V5.9.5: Call this from Activity/Service to provide context for persistence
    @Volatile private var ctxInited = false
    fun initContext(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
        if (!ctxInited) {
            ctxInited = true
            loadState()
            ErrorLogger.info(TAG, "🛢️ initContext: state loaded for first time")
        } else {
            ErrorLogger.debug(TAG, "🛢️ initContext: already inited — skipping loadState to preserve running state")
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader STOPPED")
    }

    /** Close all open positions immediately (called on STOP). */
    fun closeAllPositions() {
        val all = spotPositions.values.toList() + leveragePositions.values.toList()
        all.forEach { pos ->
            try { val map = if (pos.tradeType == TradeType.SPOT) spotPositions else leveragePositions; closePosition(pos, map, "STOP — all positions closed") } catch (_: Exception) {}
        }
        ErrorLogger.info(TAG, "🛢️ All commodity positions closed on STOP (${all.size} positions)")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()

        // V5.7.7: Check if weekend (commodities closed on weekends) - use NY timezone
        val nyZone = java.util.TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(nyZone)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            ErrorLogger.info(TAG, "🛢️ SCAN #$scanNum SKIPPED - Commodity markets CLOSED (Weekend)")
            return
        }

        
        val totalPositions = spotPositions.size + leveragePositions.size
        ErrorLogger.info(TAG, "🛢️ ═══════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🛢️ COMMODITY SCAN #$scanNum | spot=${spotPositions.size} | leverage=${leveragePositions.size} | total=$totalPositions/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all commodity markets
        val commodityMarkets = PerpsMarket.values().filter { it.isCommodity }
        ErrorLogger.info(TAG, "🛢️ Found ${commodityMarkets.size} commodities: ${commodityMarkets.map { it.symbol }}")
        
        val spotSignals = mutableListOf<CommoditySignal>()
        val leverageSignals = mutableListOf<CommoditySignal>()
        
        for (market in commodityMarkets) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) {
                    ErrorLogger.warn(TAG, "🛢️ ${market.symbol}: SKIPPED - price=0")
                    continue
                }
                
                // V5.7.6: Use FLUID thresholds from FluidLearningAI
                val spotScoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                val spotConfThresh = FluidLearningAI.getMarketsSpotConfThreshold()
                val levScoreThresh = FluidLearningAI.getMarketsLeverageScoreThreshold()
                val levConfThresh = FluidLearningAI.getMarketsLeverageConfThreshold()
                
                // Generate SPOT signal if no spot position
                if (!hasSpotPosition(market)) {
                    val spotSignal = analyzeMarket(market, data, TradeType.SPOT)
                    if (spotSignal != null && spotSignal.score >= spotScoreThresh && spotSignal.confidence >= spotConfThresh) {
                        spotSignals.add(spotSignal)
                    }
                }
                
                // Generate LEVERAGE signal if no leverage position
                if (!hasLeveragePosition(market)) {
                    val leverageSignal = analyzeMarket(market, data, TradeType.LEVERAGE)
                    if (leverageSignal != null && leverageSignal.score >= levScoreThresh && leverageSignal.confidence >= levConfThresh) {
                        leverageSignals.add(leverageSignal)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "🛢️ ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        ErrorLogger.info(TAG, "🛢️ Generated ${spotSignals.size} SPOT signals, ${leverageSignals.size} LEVERAGE signals")
        
        // Execute top SPOT signals (lower risk, more positions)
        val topSpotSignals = spotSignals.sortedByDescending { it.score }.take(25)  // V5.9.128: raised from 4
        if (topSpotSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "🛢️ TOP SPOT: ${topSpotSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        // Execute top LEVERAGE signals (higher risk, fewer positions)
        val topLeverageSignals = leverageSignals.sortedByDescending { it.score }.take(10)  // V5.9.128: raised from 2
        if (topLeverageSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "🛢️ TOP LEVERAGE: ${topLeverageSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        ErrorLogger.info(TAG, "🛢️ ═══════════════════════════════════════════════")
        
        // Execute signals
        for (signal in topSpotSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal)
        }
        for (signal in topLeverageSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal)
        }
    }
    
    private suspend fun analyzeMarket(market: PerpsMarket, data: PerpsMarketData, tradeType: TradeType): CommoditySignal? {
        // V5.9.199: Dead-feed guard — pruned/stale feeds have price=0 or change=0.0
        // Every dead commodity would score 55 (base 50 + sector +5), flooding signals with noise.
        if (data.price <= 0) return null
        if (kotlin.math.abs(data.priceChange24hPct) < 0.05) {
            ErrorLogger.debug(TAG, "🛢️ ${market.symbol}: SKIPPED stale feed (Δ=${data.priceChange24hPct}%)")
            return null
        }
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        // Add trade type indicator
        reasons.add("${tradeType.emoji} ${tradeType.name}")
        
        // 1. Momentum analysis
        when {
            abs(change) > 3.0 -> {
                score += 20
                confidence += 15
                reasons.add("🔥 Strong move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 1.5 -> {
                score += 10
                confidence += 10
                reasons.add("📈 Good move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Sector-specific boosts
        when {
            market.isEnergyCommodity -> {
                score += 5
                reasons.add("⛽ Energy sector")
            }
            market.isAgriCommodity -> {
                score += 5
                reasons.add("🌾 Agricultural")
            }
        }
        
        // 3. Technical analysis via PerpsAdvancedAI (FULL AI INTEGRATION)
        try {
            // V5.9.172 — seed history from real 24h OHLC so RSI/MACD aren't
            // stuck at 50 for 14 scans after every app restart.
            PerpsAdvancedAI.seedHistoryFromOHLC(market, data.price, data.high24h, data.low24h, data.volume24h)
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            
            if (technicals.recommendation == direction) {
                score += 10
                confidence += 10
                reasons.add("📊 RSI: ${"%.0f".format(technicals.rsi)}")
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
        
        // 6. Fluid Learning progress
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
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
        
        // V5.9.199: Raised floor 35/30 → 50/40 (matches CryptoAlt fix)
        if (score < 50) score = 50
        if (confidence < 40) confidence = 40
        reasons.add("📚 ALWAYS_TRADE mode")
        
        return CommoditySignal(
            market = market,
            direction = direction,
            tradeType = tradeType,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            price = data.price,
            priceChange24h = change,
            reasons = reasons
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: CommoditySignal) {
        // V5.9.113: enforce one-position-per-symbol across spot+leverage maps.
        // The close path sells the ENTIRE on-chain balance of the target mint,
        // which would orphan a twin position if both types exist for the same symbol.
        val symbol = signal.market.symbol
        if (spotPositions.values.any { it.market.symbol == symbol } ||
            leveragePositions.values.any { it.market.symbol == symbol }) {
            ErrorLogger.info(TAG, "🛢️ Skipping $symbol — already have an open position")
            return
        }

        // V5.9.130: full V3 stack gate — 41 AI layers.
        try {
            val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                symbol = symbol,
                assetClass = "COMMODITY",
                price = signal.price,
                technicalScore = signal.score,
                technicalConfidence = signal.confidence,
                liqUsd = 30_000_000.0,
                mcapUsd = 500_000_000.0,
                priceChangePct = signal.priceChange24h,
                direction = signal.direction.name,
            )
            if (!verdict.shouldEnter) {
                ErrorLogger.debug(TAG, "🛢️ V3 veto: $symbol blended=${verdict.blendedScore}")
                return
            }
            ErrorLogger.info(TAG, "🛢️ V3 PASS: $symbol v3=${verdict.v3Score} blended=${verdict.blendedScore}")
        } catch (_: Exception) {}
        // V5.7.7 FIX: Refresh live wallet balance if uninitialized (0) — prevents all trades being silently blocked
        if (!isPaperMode.get() && liveWalletBalance <= 0.0) {
            try {
                val fresh = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (fresh > 0) updateLiveBalance(fresh)
            } catch (_: Exception) {}
        }
        val balance = getEffectiveBalance()
        // V5.9.93: fluid sizing — scale base 5% by conviction (0.45x..2.00x)
        val sizeMult = PerpsFluidSizing.sizeMultiplier(signal.score, signal.confidence)
        val positionSizeSol = (balance * DEFAULT_SIZE_PCT / 100.0 * sizeMult).coerceAtLeast(0.01)
        if (balance < positionSizeSol) {
            ErrorLogger.warn(TAG, "🛢️ Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        // V5.9.114: REMOVED the V5.9.110 early-return live branch so live
        // uses the same sizing/TP/SL pipeline as paper. Live swap fires
        // below at the capital-move branch (paper-matched sizing).
        
        // V5.9.8: Dynamic TP — 4→25% as learning matures, never caps legitimate runs
        // V5.9.93: fluid TP/SL — stretch TP and tighten SL on high-conviction
        val (tpMult, slMult) = PerpsFluidSizing.tpSlMultiplier(signal.score, signal.confidence)
        val baseTpPct = if (signal.tradeType == TradeType.SPOT)
            com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
        else com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsLevTpPct()
        val baseSlPct = if (signal.tradeType == TradeType.SPOT) SL_PERCENT_SPOT else SL_PERCENT_LEVERAGE
        val tpPct = baseTpPct * tpMult
        val slPct = baseSlPct * slMult
        
        val tp = if (signal.direction == PerpsDirection.LONG) {
            signal.price * (1 + tpPct / 100)
        } else {
            signal.price * (1 - tpPct / 100)
        }
        
        val sl = if (signal.direction == PerpsDirection.LONG) {
            signal.price * (1 - slPct / 100)
        } else {
            signal.price * (1 + slPct / 100)
        }
        
        val position = CommodityPosition(
            id = "${signal.tradeType.name}_${signal.market.symbol}_${System.currentTimeMillis()}",
            market = signal.market,
            direction = signal.direction,
            tradeType = signal.tradeType,
            entryPrice = signal.price,
            currentPrice = signal.price,
            size = positionSizeSol,
            takeProfit = tp,
            stopLoss = sl,
            reasons = signal.reasons
        )
        
        // Add to appropriate map
        if (signal.tradeType == TradeType.SPOT) {
            spotPositions[position.id] = position
        } else {
            leveragePositions[position.id] = position
        }
        // V5.9.178 — persist open commodity positions for app-update recovery.
        persistCommodityPositions()
        
        // V5.9.114: UNIFIED capital move. Paper debits paper; live fires
        // Jupiter swap at same positionSizeSol. Live failure rolls back.
        if (isPaperMode.get()) {
            com.lifecyclebot.engine.FluidLearning.recordPaperBuy("CommoditiesTrader", positionSizeSol.coerceAtLeast(0.0))
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = -positionSizeSol,
                source = "Commodities.open[${signal.market.symbol}]"
            )
            // V5.9.171 — local orphan failsafe.
            try {
                com.lifecyclebot.collective.LocalOrphanStore.recordOpen(
                    trader = "Commodities",
                    posId = position.id,
                    sizeSol = positionSizeSol,
                    symbol = signal.market.symbol,
                )
            } catch (_: Exception) {}
        } else {
            val liveOk = executeLiveTradeAtSize(signal, positionSizeSol)
            if (!liveOk) {
                if (signal.tradeType == TradeType.SPOT) spotPositions.remove(position.id)
                else leveragePositions.remove(position.id)
                persistCommodityPositions()
                ErrorLogger.warn(TAG, "🔴 LIVE commodity trade failed: ${signal.market.symbol} — rolled back")
                return
            }
        }
        
        val leverageStr = if (signal.tradeType == TradeType.SPOT) "1x SPOT" else "${signal.tradeType.leverage.toInt()}x LEV"
        ErrorLogger.info(TAG, "🛢️ OPENED: ${signal.tradeType.emoji} ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | $leverageStr | size=${positionSizeSol}◎ | score=${signal.score}")

        // V5.9.130: register V3 entry for real-accuracy close loop.
        try {
            PerpsUnifiedScorerBridge.registerEntry(
                symbol = signal.market.symbol,
                assetClass = "COMMODITY",
                direction = signal.direction.name,
                entryPrice = signal.price,
                entryLiqUsd = 30_000_000.0,
                v3Score = signal.score,
            )
        } catch (_: Exception) {}
        
        // V5.7.6b: Record trade start for Markets learning counter
        try {
            FluidLearningAI.recordMarketsTradeStart()
        } catch (_: Exception) {}
    }
    
    /** V5.7.6b: Execute LIVE trade via MarketsLiveExecutor */
    /** V5.9.114: LIVE swap at caller-supplied size (paper-matched). */
    private suspend fun executeLiveTradeAtSize(signal: CommoditySignal, sizeSol: Double): Boolean {
        ErrorLogger.info(TAG, "🔴 LIVE COMMODITY TRADE: ${signal.direction.emoji} ${signal.market.symbol} size=${sizeSol.fmt(4)}◎")
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = sizeSol.coerceAtLeast(0.01),
            leverage = if (signal.tradeType == TradeType.SPOT) 1.0 else signal.tradeType.leverage,
            priceUsd = signal.price,
            traderType = "Commodities",
        )
        if (success) {
            ErrorLogger.info(TAG, "🔴 LIVE SUCCESS: ${signal.market.symbol} | tx=${txSignature?.take(16) ?: "bridge"}")
            try {
                val newBalance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBalance)
            } catch (_: Exception) {}
            return true
        }
        return false
    }

    private suspend fun executeLiveTrade(signal: CommoditySignal): Boolean {
        val sizeSol = (getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100.0)).coerceAtLeast(0.01)
        
        ErrorLogger.info(TAG, "🔴 LIVE COMMODITY TRADE: ${signal.direction.emoji} ${signal.market.symbol}")
        ErrorLogger.info(TAG, "🔴 Price: \$${signal.price.fmt(2)} | ${signal.tradeType.name}")
        
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = sizeSol,
            leverage = signal.tradeType.leverage,
            priceUsd = signal.price,
            traderType = "Commodities",
        )
        
        // V5.9.2: success is authoritative — txSignature null = bridge trade (no swap needed)
        if (success) {
            ErrorLogger.info(TAG, "🔴 LIVE SUCCESS: ${signal.market.symbol} | tx=${txSignature?.take(16) ?: "bridge"}")
            
            // Update live wallet balance
            try {
                val newBalance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBalance)
            } catch (_: Exception) {}
            
            return true
        }
        return false
    }
    
    private suspend fun monitorPositions() {
        // Monitor SPOT positions
        spotPositions.values.toList().forEach { position ->
            monitorSinglePosition(position, spotPositions)
        }
        // Monitor LEVERAGE positions
        leveragePositions.values.toList().forEach { position ->
            monitorSinglePosition(position, leveragePositions)
        }
    }
    
    private suspend fun monitorSinglePosition(position: CommodityPosition, positionMap: ConcurrentHashMap<String, CommodityPosition>) {
        try {
            val data = PerpsMarketDataFetcher.getMarketData(position.market)
            if (data.price > 0) {
                position.currentPrice = data.price
                
                // V5.9.9: FULLY AGENTIC EXIT — SymbolicExitReasoner
                val holdSec = (System.currentTimeMillis() - position.openTime) / 1000
                val pnlPct = position.getPnlPercent()
                val peakPnl = position.peakPnlPct.coerceAtLeast(pnlPct)
                position.peakPnlPct = peakPnl
                val priceVel = if (holdSec > 30) pnlPct / (holdSec / 60.0) else 0.0

                val assessment = com.lifecyclebot.engine.SymbolicExitReasoner.assess(
                    currentPnlPct = pnlPct, peakPnlPct = peakPnl,
                    entryConfidence = position.aiConfidence.toDouble(),
                    tradingMode = "CommoditiesAI", holdTimeSec = holdSec,
                    priceVelocity = priceVel, symbol = position.market.symbol
                )
                when (assessment.suggestedAction) {
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.EXIT ->
                        closePosition(position, positionMap, "AI_EXIT: ${assessment.primarySignal}")
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.PARTIAL ->
                        closePosition(position, positionMap, "AI_PARTIAL: ${assessment.primarySignal}")
                    else -> {
                        if (position.shouldTakeProfit()) closePosition(position, positionMap, "TP_SAFETY")
                    }
                }
            }
        } catch (_: Exception) {}
    }
    
    private fun closePosition(position: CommodityPosition, positionMap: ConcurrentHashMap<String, CommodityPosition>, reason: String) {
        val grossPnl = position.getPnlSol()
        val feePercent = if (position.isSpot) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val totalFeeSol = position.size * feePercent * 2  // fee on open + close
        val pnl = grossPnl - totalFeeSol
        val pnlPct = position.getPnlPercent() - (totalFeeSol / position.size * 100)
        val isWin = pnl >= 0

        // V5.9.171 — clear local orphan record (paper capital being returned).
        try { com.lifecyclebot.collective.LocalOrphanStore.clear(position.id) } catch (_: Exception) {}

        // V5.9.130: close V3 learning loop → real accuracy on 41 layers.
        // V5.9.170: carry real exit reason into education firehose.
        try {
            PerpsUnifiedScorerBridge.recordClose(
                symbol = position.market.symbol,
                assetClass = "COMMODITY",
                pnlPct = pnlPct,
                exitReason = reason.ifBlank { "commodity_close" },
                lossReason = if (pnlPct < -2.0) reason else "",
            )
        } catch (_: Exception) {}

        // V5.7.7 FIX: Execute live on-chain close — MUST wait for result
        if (!isPaperMode.get()) {
            var closeSuccess = false
            try {
                closeSuccess = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val (ok, _) = MarketsLiveExecutor.closeLivePosition(
                        market = position.market,
                        direction = position.direction,
                        sizeSol = position.size,
                        leverage = position.leverage,
                        traderType = "Commodities",
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
            totalPnlSol  += pnl
            totalTrades.incrementAndGet()
            if (isWin) winningTrades.incrementAndGet() else losingTrades.incrementAndGet()
            saveState()
        }
        positionMap.remove(position.id)
        // V5.9.178 — persist close.
        persistCommodityPositions()

        val emoji = if (isWin) "✅" else "❌"
        val typeEmoji = position.tradeType.emoji
        ErrorLogger.info(TAG, "🛢️ CLOSED: $typeEmoji $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ (${position.tradeType.name}) | $reason")
        
        // Record to FluidLearningAI for unified learning
        // V5.7.6b: Use Markets-specific recording to avoid affecting Meme thresholds
        try {
            if (isPaperMode.get()) FluidLearningAI.recordMarketsPaperTrade(isWin, pnlPct)
            else FluidLearningAI.recordMarketsLiveTrade(isWin, pnlPct)
        } catch (_: Exception) {}
        // V5.9.6: Sync closed P&L to shared FluidLearning pool so main bot balance updates
        if (isPaperMode.get()) {
            try {
                com.lifecyclebot.engine.FluidLearning.recordPaperSell(
                    mint = position.market.symbol,
                    originalSol = position.size,
                    pnlSol = pnl
                )
            } catch (_: Exception) {}
            // V5.9.48: Unified paper wallet — capital + PnL back to main dashboard.
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = position.size + pnl,
                source = "Commodities.close[${position.market.symbol}]"
            )
        }
        
        // Record pattern for AI memory
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(position.market)
            PerpsAdvancedAI.recordPattern(
                position.market,
                position.direction,
                technicals,
                isWin,
                pnlPct
            )
        } catch (_: Exception) {}
        
        // Record hourly performance
        try {
            val entryHour = java.util.Calendar.getInstance().apply { 
                timeInMillis = position.openTime 
            }.get(java.util.Calendar.HOUR_OF_DAY)
            PerpsAdvancedAI.recordHourlyTrade(entryHour, isWin, pnlPct)
        } catch (_: Exception) {}
        
        // Notify PerpsLearningBridge
        try {
            PerpsLearningBridge.recordCommodityTrade(position.market, position.direction, isWin, pnlPct)
        } catch (_: Exception) {}
        
        // V5.9.109: feed whole-bot 30-day sheet so Commodities wins aren't
        // wiped on app update (previously only Turso-persisted per-trader).
        try {
            if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                com.lifecyclebot.engine.RunTracker30D.recordTrade(
                    symbol      = position.market.symbol,
                    mint        = position.market.symbol,
                    entryPrice  = position.entryPrice,
                    exitPrice   = position.currentPrice,
                    sizeSol     = position.size,
                    pnlPct      = pnlPct,
                    holdTimeSec = (System.currentTimeMillis() - position.openTime) / 1000,
                    mode        = "Commod_${position.tradeType.name}",
                    score       = 50,
                    confidence  = 50,
                    decision    = reason
                )
            }
        } catch (_: Exception) {}

        // V5.9.112: feed live PnL into LiveSafetyCircuitBreaker for session drawdown halt.
        if (!isPaperMode.get()) {
            try { com.lifecyclebot.engine.LiveSafetyCircuitBreaker.recordTradeResult(pnl) } catch (_: Exception) {}
        }

        // V5.7.6b: Persist trade to Turso
        // V5.9.211: Sentience wiring — all 41 layers + personality memory
        try {
            val sentiHoldMins = (System.currentTimeMillis() - position.openTime) / 60_000.0
            val sentiIsWin = pnlPct >= 1.0
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordSimpleTradeOutcome(
                symbol    = position.market.symbol,
                mint      = position.market.symbol,
                pnlPct    = pnlPct,
                holdMins  = sentiHoldMins,
                traderTag = "COMMODITIES",
                exitReason = reason,
            )
            com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                pnlPct              = pnlPct,
                gaveBackFromPeakPct = 0.0,
                heldMinutes         = sentiHoldMins.toInt().coerceAtLeast(1),
            )
            if (sentiIsWin) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(position.market.symbol, pnlPct, "COMMODITIES", sentiHoldMins.toLong() * 60)
            } else {
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(position.market.symbol, pnlPct, "COMMODITIES", reason)
            }
        } catch (e: Exception) {
            android.util.Log.d("COMMODITIESTrader", "Sentience hook error: ${e.message}")
        }

        persistTradeToTurso(position, reason, pnl, pnlPct, isWin)
    }
    
    /**
     * V5.7.6b: Persist trade to Turso for AI learning memory
     */
    private fun persistTradeToTurso(position: CommodityPosition, reason: String, pnlSol: Double, pnlPct: Double, isWin: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                val solPrice = try { PerpsMarketDataFetcher.getSolPrice() } catch (_: Exception) { 150.0 }
                val holdMins = (System.currentTimeMillis() - position.openTime) / 60_000.0
                
                val tradeRecord = MarketsTradeRecord(
                    tradeHash = "COMMODITY_${position.id}_${System.currentTimeMillis()}",
                    instanceId = instanceId,
                    assetClass = "COMMODITY",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = position.tradeType.name,
                    entryPrice = position.entryPrice,
                    exitPrice = position.currentPrice,
                    sizeSol = position.size,
                    sizeUsd = position.size * solPrice,
                    leverage = position.tradeType.leverage,
                    pnlSol = pnlSol,
                    pnlUsd = pnlSol * solPrice,
                    pnlPct = pnlPct,
                    openTime = position.openTime,
                    closeTime = System.currentTimeMillis(),
                    closeReason = reason,
                    aiScore = 50,
                    aiConfidence = 50,
                    paperMode = isPaperMode.get(),
                    isWin = isWin,
                    holdMins = holdMins
                )
                
                client.saveMarketsTradeRecord(tradeRecord)
                client.updateMarketsAssetPerformance("COMMODITY", position.market.symbol, position.isSpot, isWin, pnlPct, holdMins)
                client.updateMarketsDailyStats(instanceId, "COMMODITY", isWin, pnlSol * solPrice)
                ErrorLogger.debug(TAG, "📊 Trade persisted to Turso: ${position.market.symbol}")
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Turso persist error: ${e.message}")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun hasSpotPosition(market: PerpsMarket): Boolean = spotPositions.values.any { it.market == market }
    private fun hasLeveragePosition(market: PerpsMarket): Boolean = leveragePositions.values.any { it.market == market }
    
    fun getSpotPositions(): List<CommodityPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<CommodityPosition> = leveragePositions.values.toList()
    fun getAllPositions(): List<CommodityPosition> = spotPositions.values.toList() + leveragePositions.values.toList()

    /** V5.9.85: Manual close for Markets UI. Returns true when position found. */
    fun closePositionManual(positionId: String, reason: String = "USER"): Boolean {
        if (positionId.isBlank()) return false
        val (pos, map) = spotPositions[positionId]?.let { it to spotPositions }
            ?: leveragePositions[positionId]?.let { it to leveragePositions }
            ?: return false
        closePosition(pos, map, reason)
        return true
    }
    fun getBalance(): Double = if (isPaperMode.get()) com.lifecyclebot.engine.BotService.status.paperWalletSol else liveWalletBalance
    fun getTotalTrades(): Int = totalTrades.get()
    fun getTotalPnlSol(): Double = totalPnlSol
    fun getWinningTrades(): Int = winningTrades.get()
    fun getWinRate(): Double {
        val t = winningTrades.get() + losingTrades.get()
        return if (t > 0) winningTrades.get().toDouble() / t * 100.0 else 0.0
    }

    // V5.9.5: Local persistence — survives app restarts without needing Turso
    private fun prefs() = appCtx?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    fun saveState() {
        val p = prefs() ?: return
        p.edit()
            .putFloat(KEY_BALANCE, paperBalance.toFloat())
            .putInt(KEY_TRADES,    totalTrades.get())
            .putInt(KEY_WINS,      winningTrades.get())
            .putInt(KEY_LOSSES,    losingTrades.get())
            .putFloat(KEY_PNL,     totalPnlSol.toFloat())
            .apply()
    }

    fun loadState() {
        val p = prefs() ?: return
        val savedBal = p.getFloat(KEY_BALANCE, 0f).toDouble()
        if (savedBal > 0.0) paperBalance = savedBal
        totalTrades.set(  p.getInt(KEY_TRADES,  0))
        winningTrades.set(p.getInt(KEY_WINS,    0))
        losingTrades.set( p.getInt(KEY_LOSSES,  0))
        totalPnlSol = p.getFloat(KEY_PNL, 0f).toDouble()
        ErrorLogger.info(TAG, "[CommoditiesTrader] Loaded: bal=${"%.2f".format(paperBalance)} trades=${totalTrades.get()} wr=${"%.0f".format(getWinRate())}%")
    }
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        com.lifecyclebot.engine.FluidLearning.forceSetBalance(balance)
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader balance set to ${"%.2f".format(balance)} SOL")
    }
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "🛢️ Commodities Trader enabled: $enabled")
    }
    fun isEnabled(): Boolean = isEnabled.get()

    fun isRunning(): Boolean = isRunning.get()

    /** V5.9.3: Receive paper balance broadcast from BotService */

    /** V5.9.3: UI toggle compatibility — Commod/Metals/Forex open both spot+lev automatically */
    private val preferLeverage = AtomicBoolean(false)
    fun setPreferLeverage(lev: Boolean) { preferLeverage.set(lev) }
    fun isPreferLeverage(): Boolean = preferLeverage.get()

    /** Returns true only if running AND engine/monitor coroutines are actually alive. */
    fun isHealthy(): Boolean {
        if (!isRunning.get()) return false
        return (engineJob?.isActive == true) && (monitorJob?.isActive == true)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6b: LIVE TRADING MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var liveWalletBalance = 0.0
    
    fun isLiveMode(): Boolean = !isPaperMode.get()
    fun isPaperMode(): Boolean = isPaperMode.get()
    
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader mode: ${if (live) "🔴 LIVE" else "📄 PAPER"}")
        if (live) {
            // Fetch actual wallet balance so live trading doesn't start at 0
            try {
                val balance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (balance > 0) updateLiveBalance(balance)
                ErrorLogger.info(TAG, "🛢️ Live wallet balance: ${"%.4f".format(liveWalletBalance)} SOL")
            } catch (_: Exception) {}
        }
    }

    /** V5.9.86: Wipe stale in-memory paper positions. See ForexTrader.purgeAllPositions. */
    fun purgeAllPositions(reason: String = "MODE_FLIP") {
        val total = spotPositions.size + leveragePositions.size
        if (total == 0) return
        spotPositions.clear()
        leveragePositions.clear()
        ErrorLogger.info(TAG, "🧹 CommoditiesTrader: purged $total stale positions ($reason)")
    }
    
    fun updateLiveBalance(balanceSol: Double) {
        liveWalletBalance = balanceSol
    }
    
    fun getEffectiveBalance(): Double = if (isPaperMode.get()) com.lifecyclebot.engine.BotService.status.paperWalletSol else liveWalletBalance

    /**
     * Add SOL to an existing open position (scale-in / pyramid).
     * Returns true if a position was found and the add-on was recorded.
     */
    fun addToPosition(market: PerpsMarket, additionalSol: Double): Boolean {
        val allPos = spotPositions.values.toList() + leveragePositions.values.toList()
        val pos = allPos.firstOrNull { p: CommodityPosition -> p.market == market } ?: return false
        val currentPrice = try {
            PerpsMarketDataFetcher.getCachedPrice(market)?.price?.takeIf { price -> price > 0 } ?: pos.currentPrice
        } catch (_: Exception) { pos.currentPrice }
        if (currentPrice <= 0) return false

        val totalCost = pos.size + additionalSol
        val blendedEntry = (pos.entryPrice * pos.size + currentPrice * additionalSol) / totalCost

        val updated = pos.copy(
            size = totalCost,
            entryPrice = blendedEntry,
            currentPrice = currentPrice
        )
        if (pos.isSpot) spotPositions[pos.id] = updated else leveragePositions[pos.id] = updated

        ErrorLogger.info(TAG, "addToPosition ${market.symbol} +$additionalSol SOL | blendedEntry=$blendedEntry")
        return true
    }

}



