package com.lifecyclebot.perps

import com.lifecyclebot.data.Trade

import com.lifecyclebot.engine.TradeHistoryStore

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
 * V5.7.6: METALS TRADER
 * Dedicated trading engine for precious and industrial metals:
 * - Precious: Gold (XAU), Silver (XAG), Platinum (XPT), Palladium (XPD)
 * - Industrial: Copper, Aluminum, Nickel, Titanium, Zinc, Lead, Tin, Iron, Cobalt, Lithium, Uranium
 * 
 * 24/7 trading via Pyth Oracle price feeds
 */
object MetalsTrader {
    private const val TAG = "MetalsTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MAX_POSITIONS = 100   // V5.9.100: user req — each trader can hold 100
    private const val SCAN_INTERVAL_MS = 20_000L  // 20 seconds
    private const val DEFAULT_SIZE_PCT = 5.0  // 5% of balance per trade (matches TokenizedStockTrader)
    // V5.9.8: TP now dynamic via FluidLearningAI (static 5% removed)
    private const val SL_PERCENT = 3.0
    private const val SPOT_TRADING_FEE_PERCENT = 0.005     // 0.5% for spot (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01  // 1.0% for leverage (5x)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spotPositions = ConcurrentHashMap<String, MetalPosition>()      // 1x SPOT
    private val leveragePositions = ConcurrentHashMap<String, MetalPosition>()  // 5x LEVERAGE
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
    private val PREFS_NAME = "metals_trader_v1"
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
    
    data class MetalPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val entryPrice: Double,
        var currentPrice: Double,
        val size: Double,
        val leverage: Double,  // 1.0 for SPOT, 5.0 for LEVERAGE
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
        fun getPnlPercent(): Double {
            val priceDiff = currentPrice - entryPrice
            val dirMultiplier = if (direction == PerpsDirection.LONG) 1 else -1
            return (priceDiff / entryPrice) * 100.0 * dirMultiplier * leverage
        }
        
        fun getPnlSol(): Double = size * (getPnlPercent() / 100.0)
        
        fun shouldTakeProfit(): Boolean = getPnlPercent() >= com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
        fun shouldStopLoss(): Boolean = getPnlPercent() <= -SL_PERCENT
    }
    
    data class MetalSignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val score: Int,
        val confidence: Int,
        val price: Double,
        val priceChange24h: Double,
        val reasons: List<String>,
        val leverage: Double = 1.0  // V5.7.6: 1.0 = SPOT, 5.0 = LEVERAGE
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENGINE CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize(context: android.content.Context? = null) {
        ErrorLogger.info(TAG, "🥇 MetalsTrader INITIALIZED")
        // V5.9.178 — rehydrate open positions across app updates so users
        // never lose a trade on install. Metals didn't persist before;
        // now we save to SharedPrefs on every open/close and restore here.
        if (context != null) {
            try {
                PerpsPositionStore.init(context.applicationContext, "metals")
                val rehydrated = PerpsPositionStore.loadAll("metals")
                rehydrated.forEach { j ->
                    try {
                        val pos = metalPositionFromJson(j)
                        if (pos.leverage <= 1.0) spotPositions[pos.id]     = pos
                        else                     leveragePositions[pos.id] = pos
                    } catch (_: Exception) {}
                }
                if (rehydrated.isNotEmpty()) {
                    ErrorLogger.info(TAG, "🥇 REHYDRATED ${rehydrated.size} metals positions (app-update recovery)")
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "metals rehydrate failed: ${e.message}")
            }
        }
    }

    // V5.9.178 — JSON persistence helpers (symmetric with CryptoAltTrader).
    private fun metalPositionToJson(p: MetalPosition): org.json.JSONObject =
        org.json.JSONObject()
            .put("id",           p.id)
            .put("market",       p.market.name)
            .put("direction",    p.direction.name)
            .put("entryPrice",   p.entryPrice)
            .put("currentPrice", p.currentPrice)
            .put("size",         p.size)
            .put("leverage",     p.leverage)
            .put("takeProfit",   p.takeProfit)
            .put("stopLoss",     p.stopLoss)
            .put("openTime",     p.openTime)
            .put("reasons",      org.json.JSONArray(p.reasons))
            .put("aiConfidence", p.aiConfidence)
            .put("peakPnlPct",   p.peakPnlPct)

    private fun metalPositionFromJson(j: org.json.JSONObject): MetalPosition {
        val rArr = j.optJSONArray("reasons")
        val reasons = if (rArr != null) (0 until rArr.length()).map { rArr.optString(it, "") } else emptyList()
        return MetalPosition(
            id           = j.getString("id"),
            market       = PerpsMarket.valueOf(j.getString("market")),
            direction    = PerpsDirection.valueOf(j.getString("direction")),
            entryPrice   = j.getDouble("entryPrice"),
            currentPrice = j.getDouble("currentPrice"),
            size         = j.getDouble("size"),
            leverage     = j.optDouble("leverage", 1.0),
            takeProfit   = j.getDouble("takeProfit"),
            stopLoss     = j.getDouble("stopLoss"),
            openTime     = j.optLong("openTime", System.currentTimeMillis()),
            reasons      = reasons,
            aiConfidence = j.optInt("aiConfidence", 50),
        ).apply { peakPnlPct = j.optDouble("peakPnlPct", 0.0) }
    }

    private fun persistMetalPositions() {
        try {
            val all = (spotPositions.values + leveragePositions.values).map { metalPositionToJson(it) }
            PerpsPositionStore.saveAll("metals", all)
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
            ErrorLogger.info(TAG, "🥇🥇🥇 MetalsTrader ENGINE STARTED 🥇🥇🥇")
            
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
            ErrorLogger.info(TAG, "🥇 initContext: state loaded for first time")
        } else {
            ErrorLogger.debug(TAG, "🥇 initContext: already inited — skipping loadState to preserve running state")
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        ErrorLogger.info(TAG, "🥇 MetalsTrader STOPPED")
    }

    /** Close all open positions immediately (called on STOP). */
    fun closeAllPositions() {
        val all = spotPositions.values.toList() + leveragePositions.values.toList()
        all.forEach { pos ->
            try { val map = if (pos.leverage == 1.0) spotPositions else leveragePositions; closePosition(pos, map, "STOP — all positions closed") } catch (_: Exception) {}
        }
        ErrorLogger.info(TAG, "🥇 All metals positions closed on STOP (${all.size} positions)")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE - V5.7.6: Now generates BOTH SPOT and LEVERAGE signals
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()

        // V5.7.7: Check if weekend (metals markets closed on weekends) - use NY timezone
        val nyZone = java.util.TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(nyZone)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            ErrorLogger.info(TAG, "🥇 SCAN #$scanNum SKIPPED - Metals markets CLOSED (Weekend)")
            return
        }

        
        val totalPositions = spotPositions.size + leveragePositions.size
        ErrorLogger.info(TAG, "🥇 ═══════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🥇 METALS SCAN #$scanNum | spot=${spotPositions.size} | lev=${leveragePositions.size} | total=$totalPositions/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all metal markets — in live mode, only those with a real on-chain route
        // (TokenizedAssetRegistry). Base/industrial metals have no tokenized Solana mint
        // and will NEVER execute live. Filter them out rather than spamming "LIVE skipped".
        val metalMarkets = PerpsMarket.values().filter {
            it.isMetal && (isPaperMode.get() || TokenizedAssetRegistry.hasRealRoute(it.symbol))
        }
        ErrorLogger.info(TAG, "🥇 Found ${metalMarkets.size} metals: ${metalMarkets.map { it.symbol }}")
        
        val spotSignals = mutableListOf<MetalSignal>()
        val leverageSignals = mutableListOf<MetalSignal>()
        
        for (market in metalMarkets) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) {
                    ErrorLogger.warn(TAG, "🥇 ${market.symbol}: SKIPPED - price=0")
                    continue
                }
                
                // V5.7.6: Use FLUID thresholds from FluidLearningAI
                val spotScoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                val spotConfThresh = FluidLearningAI.getMarketsSpotConfThreshold()
                val levScoreThresh = FluidLearningAI.getMarketsLeverageScoreThreshold()
                
                val signal = analyzeMarket(market, data)
                // V5.9.328: Trust gate — halt new entries when MetalsAI is DISTRUSTED
                val metalsTrust = try { com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel("MetalsAI") } catch (_: Exception) { null }
                if (metalsTrust == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED) {
                    ErrorLogger.warn(TAG, "🥇 ${market.symbol}: TRUST_GATE — MetalsAI DISTRUSTED, skipping entry")
                    continue
                }
                if (signal != null && signal.score >= spotScoreThresh && signal.confidence >= spotConfThresh) {
                    // SPOT signal if no spot position
                    if (!spotPositions.values.any { it.market == market }) {
                        spotSignals.add(signal.copy(leverage = 1.0))
                    }
                    // LEVERAGE signal - uses fluid threshold (only if UI toggle allows)
                    if (preferLeverage.get() && signal.score >= levScoreThresh && !leveragePositions.values.any { it.market == market }) {
                        leverageSignals.add(signal.copy(leverage = 5.0))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "🥇 ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        // Execute top SPOT signals
        val topSpotSignals = spotSignals.sortedByDescending { it.score }.take(25)  // V5.9.128: raised from 4
        if (topSpotSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "🥇 TOP SPOT: ${topSpotSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        // Execute top LEVERAGE signals
        val topLeverageSignals = leverageSignals.sortedByDescending { it.score }.take(10)  // V5.9.128: raised from 2
        if (topLeverageSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "🥇 TOP LEVERAGE: ${topLeverageSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        ErrorLogger.info(TAG, "🥇 ═══════════════════════════════════════════════")
        
        for (signal in topSpotSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal, spotPositions, "💰 SPOT")
        }
        if (preferLeverage.get()) {
            for (signal in topLeverageSignals) {
                if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
                executeSignal(signal, leveragePositions, "⚡ 5x")
            }
        }
    }
    
    private suspend fun analyzeMarket(market: PerpsMarket, data: PerpsMarketData): MetalSignal? {
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        // 1. Momentum analysis
        when {
            abs(change) > 2.0 -> {
                score += 20
                confidence += 15
                reasons.add("🔥 Strong move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 1.0 -> {
                score += 10
                confidence += 10
                reasons.add("📈 Good move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Metal-specific boosts
        when {
            market.isPreciousMetal -> {
                score += 10
                confidence += 10
                reasons.add("💎 Precious metal - safe haven")
            }
            market.isIndustrialMetal -> {
                score += 5
                reasons.add("🔩 Industrial metal")
            }
        }
        
        // 3. Special metals
        when (market) {
            PerpsMarket.XAU -> {
                score += 5
                reasons.add("🥇 GOLD - King of metals")
            }
            PerpsMarket.XAG -> {
                score += 5
                reasons.add("🥈 SILVER - Industrial + store of value")
            }
            PerpsMarket.LITHIUM -> {
                score += 10
                reasons.add("🔋 LITHIUM - EV battery demand")
            }
            PerpsMarket.URANIUM -> {
                score += 10
                reasons.add("☢️ URANIUM - Nuclear renaissance")
            }
            else -> {}
        }
        
        // 4. Technical analysis via PerpsAdvancedAI (FULL AI INTEGRATION)
        try {
            // V5.9.172 — seed history from real 24h OHLC so RSI/MACD aren't stuck at 50.
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
        
        // 5. Volume analysis
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
        
        // 6. Support/Resistance
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
        
        // 7. Fluid Learning progress
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
        } catch (_: Exception) {}
        
        // 8. Pattern memory check
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            val patternConf = PerpsAdvancedAI.getPatternConfidence(market, direction, technicals)
            if (patternConf > 60) {
                score += 10
                confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}
        
        // V5.9.328: Removed score/confidence floors — inflating scores past fluid thresholds
        // was injecting blind signals with RSI=50/MACD=NEUTRAL into live trading.
        reasons.add("📚 Learning: quality-gated mode")
        
        return MetalSignal(
            market = market,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            price = data.price,
            priceChange24h = change,
            reasons = reasons
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION - V5.7.6b: Supports SPOT and LEVERAGE + LIVE mode
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: MetalSignal, positionMap: ConcurrentHashMap<String, MetalPosition>, typeLabel: String) {
        // V5.9.113: prevent duplicate positions for the same market across
        // spot+leverage maps. closeLivePosition sells the ENTIRE on-chain
        // balance of the target mint, so two concurrent positions in the
        // same symbol would orphan one when the other closes. Safer to
        // one-position-per-symbol like CryptoAlt / TokenizedStocks already do.
        val symbol = signal.market.symbol
        if (spotPositions.values.any { it.market.symbol == symbol } ||
            leveragePositions.values.any { it.market.symbol == symbol }) {
            ErrorLogger.info(TAG, "🥇 Skipping $symbol — already have an open position")
            return
        }

        // V5.9.130: full V3 stack gate — 41 AI layers.
        try {
            val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                symbol = symbol,
                assetClass = "METAL",
                price = signal.price,
                technicalScore = signal.score,
                technicalConfidence = signal.confidence,
                liqUsd = 50_000_000.0,
                mcapUsd = 1_000_000_000.0,
                priceChangePct = signal.priceChange24h,
                direction = signal.direction.name,
            )
            if (!verdict.shouldEnter) {
                ErrorLogger.debug(TAG, "🥇 V3 veto: $symbol blended=${verdict.blendedScore}")
                return
            }
            ErrorLogger.info(TAG, "🥇 V3 PASS: $symbol v3=${verdict.v3Score} blended=${verdict.blendedScore}")
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
            ErrorLogger.warn(TAG, "🥇 Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        // V5.9.114: REMOVED the V5.9.110 early-return live branch so live
        // runs the same sizing/TP/SL pipeline as paper. The live swap
        // fires below where paper debits the paper wallet (unified path).
        
        // V5.9.93: fluid TP/SL — stretch TP and tighten SL on high-conviction
        val (tpMult, slMult) = PerpsFluidSizing.tpSlMultiplier(signal.score, signal.confidence)
        val baseTpPct = com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
        val tpPct = baseTpPct * tpMult
        val slPct = SL_PERCENT * slMult

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
        
        val position = MetalPosition(
            id = "${if (signal.leverage == 1.0) "SPOT" else "LEV"}_${signal.market.symbol}_${System.currentTimeMillis()}",
            market = signal.market,
            direction = signal.direction,
            entryPrice = signal.price,
            currentPrice = signal.price,
            size = positionSizeSol,
            leverage = signal.leverage,
            takeProfit = tp,
            stopLoss = sl,
            reasons = signal.reasons
        )
        
        positionMap[position.id] = position
        // V5.9.178 — persist open positions so app updates don't wipe them.
        persistMetalPositions()
        // fires Jupiter swap at the same positionSizeSol so sizing learnt
        // in paper carries into live 1:1. Live failure rolls back the position.
        if (isPaperMode.get()) {
            com.lifecyclebot.engine.FluidLearning.recordPaperBuy("MetalsTrader", positionSizeSol.coerceAtLeast(0.0))
            // V5.9.48: unified paper wallet — debit deployed capital from main.
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = -positionSizeSol,
                source = "Metals.open[${signal.market.symbol}]"
            )
            // V5.9.171 — local orphan failsafe (Turso-independent). Refunds
            // paper capital on next startup if the app is wiped mid-trade.
            try {
                com.lifecyclebot.collective.LocalOrphanStore.recordOpen(
                    trader = "Metals",
                    posId = position.id,
                    sizeSol = positionSizeSol,
                    symbol = signal.market.symbol,
                )
            } catch (_: Exception) {}
        } else {
            val liveOk = executeLiveTradeAtSize(signal, typeLabel, positionSizeSol)
            if (!liveOk) {
                positionMap.remove(position.id)
                persistMetalPositions()
                ErrorLogger.warn(TAG, "🔴 LIVE metal trade failed: ${signal.market.symbol} — rolled back")
                return
            }
        }
        
        ErrorLogger.info(TAG, "🥇 OPENED: $typeLabel ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | size=${positionSizeSol}◎ | score=${signal.score}")

        // V5.9.130: register V3 entry for real-accuracy close loop.
        // V5.9.170: feed the real entry reason chain into the education layer.
        try {
            PerpsUnifiedScorerBridge.registerEntry(
                symbol = signal.market.symbol,
                assetClass = "METAL",
                direction = signal.direction.name,
                entryPrice = signal.price,
                entryLiqUsd = 50_000_000.0,
                v3Score = signal.score,
                entryReason = signal.reasons.take(6).joinToString("|").ifBlank { "Metals:${signal.direction.name}" },
                traderSource = "Metals",
            )
        } catch (_: Exception) {}
        
        // V5.7.6b: Record trade start for Markets learning counter
        try {
            FluidLearningAI.recordMarketsTradeStart()
        } catch (_: Exception) {}
    }
    
    /** V5.9.114: LIVE swap at caller-supplied size (paper-matched). */
    private suspend fun executeLiveTradeAtSize(signal: MetalSignal, typeLabel: String, sizeSol: Double): Boolean {
        ErrorLogger.info(TAG, "🔴 LIVE METAL TRADE: ${signal.direction.emoji} ${signal.market.symbol} size=${sizeSol.fmt(4)}◎")
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = sizeSol.coerceAtLeast(0.01),
            leverage = signal.leverage,
            priceUsd = signal.price,
            traderType = "Metals",
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

    /** V5.7.6b: Execute LIVE trade via MarketsLiveExecutor */
    private suspend fun executeLiveTrade(signal: MetalSignal, typeLabel: String): Boolean {
        ErrorLogger.info(TAG, "🔴 LIVE METAL TRADE: ${signal.direction.emoji} ${signal.market.symbol}")
        ErrorLogger.info(TAG, "🔴 Price: \$${signal.price.fmt(2)} | $typeLabel")
        
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = (getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100.0)).coerceAtLeast(0.01),
            leverage = signal.leverage,
            priceUsd = signal.price,
            traderType = "Metals",
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
    
    private suspend fun monitorSinglePosition(position: MetalPosition, positionMap: ConcurrentHashMap<String, MetalPosition>) {
        try {
            val data = PerpsMarketDataFetcher.getMarketData(position.market)
            if (data.price > 0) {
                position.currentPrice = data.price

                val holdSec = (System.currentTimeMillis() - position.openTime) / 1000
                val pnlPct = position.getPnlPercent()
                val peakPnl = position.peakPnlPct.coerceAtLeast(pnlPct)
                position.peakPnlPct = peakPnl
                val priceVel = if (holdSec > 30) pnlPct / (holdSec / 60.0) else 0.0

                val assessment = com.lifecyclebot.engine.SymbolicExitReasoner.assess(
                    currentPnlPct = pnlPct, peakPnlPct = peakPnl,
                    entryConfidence = position.aiConfidence.toDouble(),
                    tradingMode = "MetalsAI", holdTimeSec = holdSec,
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
    
    private fun closePosition(position: MetalPosition, positionMap: ConcurrentHashMap<String, MetalPosition>, reason: String) {
        val grossPnl = position.getPnlSol()
        val feePercent = if (position.leverage == 1.0) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val totalFeeSol = position.size * feePercent * 2  // fee on open + close
        val pnl = grossPnl - totalFeeSol
        val pnlPct = position.getPnlPercent() - (totalFeeSol / position.size * 100)
        val isWin = pnl >= 0

        // V5.9.171 — clear local orphan record (paper capital being returned).
        try { com.lifecyclebot.collective.LocalOrphanStore.clear(position.id) } catch (_: Exception) {}

        // V5.9.130: close V3 learning loop → real accuracy on 41 layers.
        // V5.9.170: carry the real exit reason into the education firehose.
        try {
            PerpsUnifiedScorerBridge.recordClose(
                symbol = position.market.symbol,
                assetClass = "METAL",
                pnlPct = pnlPct,
                exitReason = reason.ifBlank { "metals_close" },
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
                        traderType = "Metals",
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
        // V5.9.178 — persist the close so reopening app doesn't restore a closed trade.
        persistMetalPositions()

        val typeLabel = if (position.leverage == 1.0) "💰" else "⚡"
        val emoji = if (isWin) "✅" else "❌"
        ErrorLogger.info(TAG, "🥇 CLOSED: $typeLabel $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ | $reason")
        
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
                source = "Metals.close[${position.market.symbol}]"
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
            PerpsLearningBridge.recordMetalTrade(position.market, position.direction, isWin, pnlPct)
        } catch (_: Exception) {}
        
        // V5.9.109: feed whole-bot 30-day sheet so Metals wins aren't wiped
        // on app update (previously only Turso-persisted per-trader).
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
                    mode        = "Metals_${if (position.leverage == 1.0) "SPOT" else "${position.leverage.toInt()}x"}",
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
                traderTag = "METALS",
                exitReason = reason,
            )
            com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                pnlPct              = pnlPct,
                gaveBackFromPeakPct = 0.0,
                heldMinutes         = sentiHoldMins.toInt().coerceAtLeast(1),
            )
            if (sentiIsWin) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(position.market.symbol, pnlPct, "METALS", sentiHoldMins.toLong() * 60)
            } else {
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(position.market.symbol, pnlPct, "METALS", reason)
            }
        } catch (e: Exception) {
            android.util.Log.d("METALSTrader", "Sentience hook error: ${e.message}")
        }


        // V5.9.248: Log ALL universe trades to shared TradeHistoryStore so they appear in Live/Paper journal
        try {
            val modeStr248 = if (isPaperMode.get()) "paper" else "live"
            TradeHistoryStore.recordTrade(Trade(
                side             = "SELL",
                mode             = modeStr248,
                sol              = position.size,
                price            = position.currentPrice,
                ts               = System.currentTimeMillis(),
                reason           = "Metals:$reason",
                pnlSol           = pnl,
                pnlPct           = pnlPct,
                score            = 50.0,
                tradingMode      = "Metals",
                tradingModeEmoji = "🥇",
                mint             = position.market.symbol,
            ))
        } catch (_: Exception) {}

                persistTradeToTurso(position, reason, pnl, pnlPct, isWin)
    }
    
    /**
     * V5.7.6b: Persist trade to Turso for AI learning memory
     */
    private fun persistTradeToTurso(position: MetalPosition, reason: String, pnlSol: Double, pnlPct: Double, isWin: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                val solPrice = try { PerpsMarketDataFetcher.getSolPrice() } catch (_: Exception) { 150.0 }
                val holdMins = (System.currentTimeMillis() - position.openTime) / 60_000.0
                
                val tradeRecord = MarketsTradeRecord(
                    tradeHash = "METAL_${position.id}_${System.currentTimeMillis()}",
                    instanceId = instanceId,
                    assetClass = "METAL",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = if (position.leverage == 1.0) "SPOT" else "LEVERAGE",
                    entryPrice = position.entryPrice,
                    exitPrice = position.currentPrice,
                    sizeSol = position.size,
                    sizeUsd = position.size * solPrice,
                    leverage = position.leverage,
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
                client.updateMarketsAssetPerformance("METAL", position.market.symbol, position.leverage == 1.0, isWin, pnlPct, holdMins)
                client.updateMarketsDailyStats(instanceId, "METAL", isWin, pnlSol * solPrice)
                ErrorLogger.debug(TAG, "📊 Trade persisted to Turso: ${position.market.symbol}")
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Turso persist error: ${e.message}")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getSpotPositions(): List<MetalPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<MetalPosition> = leveragePositions.values.toList()
    fun getAllPositions(): List<MetalPosition> = spotPositions.values.toList() + leveragePositions.values.toList()

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
        ErrorLogger.info(TAG, "[MetalsTrader] Loaded: bal=${"%.2f".format(paperBalance)} trades=${totalTrades.get()} wr=${"%.0f".format(getWinRate())}%")
    }
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        com.lifecyclebot.engine.FluidLearning.forceSetBalance(balance)
        ErrorLogger.info(TAG, "🥇 MetalsTrader balance set to ${"%.2f".format(balance)} SOL")
    }
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "🥇 Metals Trader enabled: $enabled")
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
        ErrorLogger.info(TAG, "🥇 MetalsTrader mode: ${if (live) "🔴 LIVE" else "📄 PAPER"}")
        if (live) {
            // Fetch actual wallet balance so live trading doesn't start at 0
            try {
                val balance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (balance > 0) updateLiveBalance(balance)
                ErrorLogger.info(TAG, "🥇 Live wallet balance: ${"%.4f".format(liveWalletBalance)} SOL")
            } catch (_: Exception) {}
        }
    }

    /** V5.9.86: Wipe stale in-memory paper positions. See ForexTrader.purgeAllPositions. */
    fun purgeAllPositions(reason: String = "MODE_FLIP") {
        val total = spotPositions.size + leveragePositions.size
        if (total == 0) return
        spotPositions.clear()
        leveragePositions.clear()
        ErrorLogger.info(TAG, "🧹 MetalsTrader: purged $total stale positions ($reason)")
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
        val pos = allPos.firstOrNull { p: MetalPosition -> p.market == market } ?: return false
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
        if (pos.leverage == 1.0) spotPositions[pos.id] = updated else leveragePositions[pos.id] = updated

        ErrorLogger.info(TAG, "addToPosition ${market.symbol} +$additionalSol SOL | blendedEntry=$blendedEntry")
        return true
    }

}



