package com.lifecyclebot.perps

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LiveAttemptStats
import com.lifecyclebot.engine.RunTracker30D
import com.lifecyclebot.engine.ShadowLearningEngine
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.v3.scoring.BehaviorAI
import com.lifecyclebot.v3.scoring.BlueChipTraderAI
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI
import com.lifecyclebot.v3.scoring.MetaCognitionAI
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import com.lifecyclebot.v4.meta.NarrativeFlowAI
import com.lifecyclebot.v3.scoring.QualityTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinExpress
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI
import com.lifecyclebot.v4.meta.StrategyTrustAI
import com.lifecyclebot.v3.scoring.VolatilityRegimeAI
import com.lifecyclebot.v4.meta.*
import com.lifecyclebot.v4.meta.TradeLessonRecorder
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════════════════
// 🪙 CRYPTO ALT TRADER — V1.0.0
// ═══════════════════════════════════════════════════════════════════════════════
//
// Third dedicated trading engine for the full crypto alt market.
// Mirrors the SOL perps (meme) trader architecture exactly:
//   • Paper-first learning (5000-trade maturity arc, FluidLearningAI)
//   • Identical UI readiness phases: BOOTSTRAP → LEARNING → VALIDATING → MATURING → READY → LIVE
//   • Full V3/V4 AI layer stack (PerpsAdvancedAI, CrossTalkFusionEngine, PortfolioHeatAI,
//     CrossAssetLeadLagAI, CrossMarketRegimeAI, LeverageSurvivalAI)
//   • New CryptoAltScannerAI layer for alt-specific signals (BTC correlation, sector rotation,
//     dominance cycles, narrative momentum, L1/L2 spread, DeFi heat)
//   • Live execution via Jupiter DEX swap (SOL → wrapped alt token) using existing wallet
//   • Same SPOT / LEVERAGE toggle, same position card structure, same Hivemind wiring
//
// Markets:
//   All isCrypto && !isSolPerp markets from PerpsMarket (SOL perps engine covers the isSolPerp set)
//   ~68 alts: BTC, ETH, BNB, XRP, SHIB, FLOKI, TRUMP, POPCAT, ADA, SEI, FTM, ALGO,
//             HBAR, ICP, VET, FIL, RENDER, GRT, AAVE, MKR, STX, IMX, SAND, MANA, AXS,
//             ENS, LDO, RPL, MNGO, TRX, TON, BCH, XLM, XMR, ETC, ZEC, XTZ, EOS,
//             CAKE, NOT, and more.
//
// ═══════════════════════════════════════════════════════════════════════════════

object CryptoAltTrader {

    private const val TAG = "🪙CryptoAltTrader"

    // ─── Constants ────────────────────────────────────────────────────────────
    // V5.9.70: cap removed — exposure guard + wallet reserve are the real
    // concurrency governors. Large ceiling kept purely as a sanity bound
    // so a runaway loop can't allocate unbounded memory.
    private const val MAX_POSITIONS         = 10_000
    // V5.9.91: SOFT cap — once positions exceed this, new entries only open
    // by REPLACING the weakest open position (lowest entry score) when the
    // incoming signal outscores it. Keeps capital rotating instead of
    // saturating at 110+ dead trades.
    private const val SOFT_CAP_POSITIONS    = 30
    private const val REPLACE_SCORE_MARGIN  = 8   // incoming must beat worst-held by at least this
    private const val SCAN_INTERVAL_MS      = 12_000L       // 12-second scan cycle
    private const val DYN_SCAN_INTERVAL_MS  = 30_000L       // Dynamic token scan every 30s
    private const val DYN_BATCH_SIZE        = 200           // Tokens per dynamic scan batch
    private const val DEFAULT_SIZE_PCT      = 3.0           // 3% of balance per trade (20 pos max = 60% total exposure)
    private const val DEFAULT_LEVERAGE      = 3.0           // Default leverage (when not SPOT)
    // V5.9.8: DEFAULT_TP_SPOT removed — now dynamic via FluidLearningAI
    private const val DEFAULT_SL_SPOT       = 3.5           // SPOT stop-loss %
    // V5.9.8: DEFAULT_TP_LEV removed — now dynamic via FluidLearningAI
    private const val DEFAULT_SL_LEV        = 5.0           // LEVERAGE stop-loss %

    // Alts that the SOL perps engine already handles — excluded here
    private val SOL_PERPS_SYMBOLS = setOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX", "DOT", "LINK",
        "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP", "APT", "SUI", "INJ", "JUP",
        "PEPE", "WIF", "BONK", "NEAR", "TIA", "PYTH", "RAY", "ORCA", "DRIFT",
        "WLD", "JTO", "W", "STRK", "TAO", "GMX", "DYDX", "ENA", "PENDLE"
    )

    // ─── State ────────────────────────────────────────────────────────────────
    private val positions        = ConcurrentHashMap<String, AltPosition>()
    private val spotPositions    = ConcurrentHashMap<String, AltPosition>()
    private val leveragePositions= ConcurrentHashMap<String, AltPosition>()
    // V5.7.7: Closed positions archive — retained so Positions tab shows real win rate + history
    private val closedPositions  = java.util.concurrent.CopyOnWriteArrayList<AltPosition>()
    private val MAX_CLOSED_HISTORY = 500

    private val isRunning        = AtomicBoolean(false)
    private val isEnabled        = AtomicBoolean(true)
    private val preferLeverage   = AtomicBoolean(false)  // V5.9.3: mirrors UI SPOT/LEVERAGE toggle
    private val isPaperMode      = AtomicBoolean(true)
    private val scanCount        = AtomicInteger(0)
    private val positionCounter  = AtomicInteger(0)
    private val totalTrades      = AtomicInteger(0)
    private val winningTrades    = AtomicInteger(0)
    private val losingTrades     = AtomicInteger(0)

    @Volatile private var paperBalance    = 0.0    // Balance managed by MultiAssetActivity shared pool
    @Volatile private var liveWalletBalance = 0.0
    @Volatile private var totalPnlSol     = 0.0
    @Volatile private var initialBalance  = 0.0  // V5.9.5: balance at session start for correct pnl%

    private var engineJob    : Job? = null
    private var monitorJob   : Job? = null
    private var dynScanJob   : Job? = null
    private var dynBatchIdx  = 0       // rotating batch cursor for dynamic token scan
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Persistent SharedPreferences — fast, always-available learning fallback
    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private const val PREFS_NAME  = "crypto_alt_trader_v2"
    private const val KEY_BALANCE = "paper_balance"
    private const val KEY_TRADES  = "total_trades"
    private const val KEY_WINS    = "winning_trades"
    private const val KEY_LOSSES  = "losing_trades"
    private const val KEY_PNL             = "total_pnl_sol"
    private const val KEY_INITIAL_BALANCE  = "initial_balance"
    // V5.9.53: removed hard 500 SOL cap — legitimate big wins were silently excluded
    private const val KEY_LIVE    = "is_live_mode"

    // ─── Position model ───────────────────────────────────────────────────────
    data class AltPosition(
        val id            : String,
        val market        : PerpsMarket,
        val direction     : PerpsDirection,
        val isSpot        : Boolean,
        val entryPrice    : Double,
        var currentPrice  : Double,
        val sizeSol       : Double,
        val leverage      : Double,
        val takeProfitPrice: Double,
        val stopLossPrice  : Double,
        val aiScore       : Int,
        val aiConfidence  : Int,
        val reasons       : List<String>,
        val openTime      : Long = System.currentTimeMillis(),
        var closeTime     : Long? = null,
        var closePrice    : Double? = null,
        var realizedPnl   : Double? = null,
        var highestPnlPct : Double = 0.0   // V5.9.9: Track peak PnL for trailing stop
    ) {
        val leverageLabel: String get() = if (isSpot) "SPOT" else "${leverage.toInt()}x"

        fun getPnlPct(): Double {
            val diff = currentPrice - entryPrice
            val dir  = if (direction == PerpsDirection.LONG) 1.0 else -1.0
            return (diff / entryPrice) * dir * leverage * 100.0
        }

        fun getPnlSol(): Double = sizeSol * (getPnlPct() / 100.0)

        fun shouldTakeProfit(tpPct: Double): Boolean {
            return getPnlPct() >= tpPct
        }

        fun shouldStopLoss(slPct: Double): Boolean {
            return getPnlPct() <= -slPct
        }
    }

    // ─── Alt signal model ─────────────────────────────────────────────────────
    data class AltSignal(
        val market      : PerpsMarket,
        val direction   : PerpsDirection,
        val score       : Int,
        val confidence  : Int,
        val price       : Double,
        val priceChange24h: Double,
        val reasons     : List<String>,
        val layerVotes  : Map<String, PerpsDirection>,
        val leverage    : Double = DEFAULT_LEVERAGE
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile private var altInited = false
    fun init(context: Context) {
        if (altInited) {
            ErrorLogger.debug(TAG, "🪙 init: already inited — skipping to preserve running state")
            return
        }
        altInited = true
        ctx   = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromSharedPrefs()
        // V5.9.8: Sync paper/live mode from main config (source of truth)
        try {
            val cfg = com.lifecyclebot.data.ConfigStore.load(context.applicationContext)
            isPaperMode.set(cfg.paperMode)
        } catch (_: Exception) {}
        scope.launch { loadPersistedState() }
        try { BehaviorAI.init(context.applicationContext) }        catch (e: Exception) { ErrorLogger.debug(TAG, "BehaviorAI: ${e.message}") }
        try { StrategyTrustAI.init() }                             catch (e: Exception) { ErrorLogger.debug(TAG, "StrategyTrustAI: ${e.message}") }
        try { NarrativeFlowAI.init() }                             catch (e: Exception) { ErrorLogger.debug(TAG, "NarrativeFlowAI: ${e.message}") }
        try { RunTracker30D.init(context.applicationContext) }     catch (e: Exception) { ErrorLogger.debug(TAG, "RunTracker30D: ${e.message}") }
        try { ShadowLearningEngine.init() }                        catch (e: Exception) { ErrorLogger.debug(TAG, "ShadowLearning: ${e.message}") }
        try { TradeHistoryStore.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "TradeHistory: ${e.message}") }
        try { ShitCoinTraderAI.init(isPaperMode.get()) }           catch (e: Exception) { ErrorLogger.debug(TAG, "ShitCoinAI: ${e.message}") }
        try { QualityTraderAI.init(isPaperMode.get()) }            catch (e: Exception) { ErrorLogger.debug(TAG, "QualityAI: ${e.message}") }
        try { BlueChipTraderAI.init(isPaperMode.get()) }           catch (e: Exception) { ErrorLogger.debug(TAG, "BlueChipAI: ${e.message}") }
        try { ShitCoinExpress.init(isPaperMode.get()) }            catch (e: Exception) { ErrorLogger.debug(TAG, "ShitCoinExpress: ${e.message}") }
        try { MoonshotTraderAI.initialize(isPaperMode.get()) }     catch (e: Exception) { ErrorLogger.debug(TAG, "MoonshotAI: ${e.message}") }
        try { ManipulatedTraderAI.init(isPaperMode.get()) }        catch (e: Exception) { ErrorLogger.debug(TAG, "ManipulatedAI: ${e.message}") }
        try { PerpsLearningBridge.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "PerpsLearningBridge: ${e.message}") }
        try { FluidLearningAI.initMarketsPrefs(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "FluidLearningAI.initMarketsPrefs: ${e.message}") }
        // V5.9.1: Eagerly sync real wallet balance on init (live mode)
        if (!isPaperMode.get()) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val sol = WalletManager.getWallet()?.getSolBalance() ?: 0.0
                    if (sol > 0.0) updateLiveBalance(sol)
                } catch (_: Exception) {}
            }
        }
        ErrorLogger.info(TAG, "🪙 CryptoAltTrader INITIALIZED | paper=${isPaperMode.get()} | balance=${"%.2f".format(paperBalance)} SOL | trades=${totalTrades.get()}")
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
            ErrorLogger.info(TAG, "🪙🪙🪙 CryptoAltTrader ENGINE STARTED 🪙🪙🪙")
            ErrorLogger.info(TAG, "🪙 Scanning every ${SCAN_INTERVAL_MS / 1000}s | enabled=${isEnabled.get()}")

            try {
                ErrorLogger.info(TAG, "🪙🪙🪙 Running INITIAL alt scan NOW... 🪙🪙🪙")
                runScanCycle()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { ErrorLogger.error(TAG, "🪙 Initial scan error: ${e.message}", e) }

            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)
                    if (isEnabled.get()) runScanCycle()
                    else ErrorLogger.debug(TAG, "🪙 Alt trading DISABLED — skipping scan")
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e) }
            }
        }

        monitorJob = scope.launch {
            while (isRunning.get()) {
                try { monitorPositions() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ErrorLogger.error(TAG, "Monitor error: ${e.message}", e) }
                delay(5_000)
            }
        }

        // Dynamic token scan — feeds entire DynamicAltTokenRegistry universe into AI learning
        dynScanJob = scope.launch {
            delay(10_000) // stagger start after main engine
            while (isRunning.get()) {
                try {
                    if (isEnabled.get()) runDynamicTokenScan()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { ErrorLogger.error(TAG, "DynScan error: ${e.message}", e) }
                delay(DYN_SCAN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        dynScanJob?.cancel()
        ErrorLogger.info(TAG, "🪙 CryptoAltTrader STOPPED")
    }

    /** Close all open positions immediately (called on STOP). */
    fun closeAllPositions() {
        val ids = positions.keys.toList()
        ids.forEach { id -> try { closePosition(id, "USER_STOP") } catch (_: Exception) {} }
        ErrorLogger.info(TAG, "🪙 All crypto alt positions closed on STOP (${ids.size} positions)")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // DYNAMIC TOKEN SCAN — feeds DynamicAltTokenRegistry universe into sub-AI engines
    // Runs every 30s in rotating batches of 200 tokens so ALL discovered tokens
    // (DexScreener/CoinGecko/Jupiter = thousands) get assessed for learning signals.
    // The sub-AIs record every evaluation for FluidLearningAI cross-layer learning.
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun runDynamicTokenScan() = withContext(Dispatchers.Default) {
        val allTokens = DynamicAltTokenRegistry.getAllTokens(DynamicAltTokenRegistry.SortMode.QUALITY)
        if (allTokens.isEmpty()) return@withContext

        val totalBatches = maxOf(1, (allTokens.size + DYN_BATCH_SIZE - 1) / DYN_BATCH_SIZE)
        val batchIdx     = dynBatchIdx % totalBatches
        val batchStart   = batchIdx * DYN_BATCH_SIZE
        val batchEnd     = minOf(batchStart + DYN_BATCH_SIZE, allTokens.size)
        dynBatchIdx++

        val batch = allTokens.subList(batchStart, batchEnd)
        ErrorLogger.debug(TAG, "🪙⚡ DynScan batch ${batchIdx + 1}/$totalBatches | size=${batch.size} | universe=${allTokens.size}")

        var scanned = 0
        var signals = 0
        val dynExecutableSignals = mutableListOf<AltSignal>()

        for (tok in batch) {
            try {
                if (SOL_PERPS_SYMBOLS.contains(tok.symbol)) continue
                val price   = tok.price.takeIf  { it > 0 }       ?: continue
                val vol     = tok.volume24h
                val mcap    = tok.mcap
                val liq     = tok.liquidityUsd
                val change  = tok.priceChange24h
                val buys1h  = tok.buys24h  / 24
                val sells1h = tok.sells24h / 24
                val buyPct  = if (buys1h + sells1h > 0) (buys1h.toDouble() / (buys1h + sells1h) * 100.0) else 50.0
                val momentum= change   // use 24h change as momentum proxy
                val isMeme  = tok.sector.lowercase().let { it.contains("meme") || it.contains("gaming") }

                scanned++

                // Feed sector intelligence layer
                try {
                    val btcPrice = PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.BTC)?.price ?: 0.0
                    CryptoAltScannerAI.recordPrice(tok.symbol, price, btcPrice)
                    CrossAssetLeadLagAI.recordReturn(tok.symbol, change)
                    CrossMarketRegimeAI.updateMarketState(tok.symbol, price, change, vol)
                } catch (_: Exception) {}

                // ── ShitCoin sub-AI (low-cap / meme tokens) ──────────────────────────
                if (mcap < 50_000_000.0 || isMeme) {
                    if (!ShitCoinTraderAI.hasPosition(tok.symbol)) {
                        try {
                            val sig = ShitCoinTraderAI.evaluate(
                                mint              = tok.symbol,
                                symbol            = tok.symbol,
                                currentPrice      = price,
                                marketCapUsd      = mcap,
                                liquidityUsd      = liq,
                                topHolderPct      = 0.0,
                                buyPressurePct    = buyPct,
                                momentum          = momentum,
                                volatility        = kotlin.math.abs(change),
                                tokenAgeMinutes   = 9999.0,  // established token
                                launchPlatform    = ShitCoinTraderAI.LaunchPlatform.UNKNOWN,
                                isDexBoosted      = tok.isBoosted,
                                dexTrendingRank   = if (tok.isTrending) tok.trendingRank else -1
                            )
                            if (sig.shouldEnter) {
                                signals++
                                ErrorLogger.info(TAG, "🪙💩 DynSig ShitCoin: ${tok.symbol} conf=${sig.confidence}")
                                try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}
                                // V5.9.2: Convert to tradeable AltSignal
                                val dynMarket = PerpsMarket.values().find { it.symbol == tok.symbol }
                                if (dynMarket != null) dynExecutableSignals.add(AltSignal(
                                    market = dynMarket, direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT,
                                    score = sig.confidence, confidence = sig.confidence, price = price,
                                    priceChange24h = change, reasons = listOf("DynScan ShitCoin score=${sig.confidence}"),
                                    layerVotes = emptyMap()
                                ))
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── BlueChip sub-AI (large-cap, liquid) ──────────────────────────────
                if (mcap > 500_000_000.0 && liq > 1_000_000.0) {
                    if (!BlueChipTraderAI.hasPosition(tok.symbol)) {
                        try {
                            val sig = BlueChipTraderAI.evaluate(
                                mint           = tok.symbol,
                                symbol         = tok.symbol,
                                currentPrice   = price,
                                marketCapUsd   = mcap,
                                liquidityUsd   = liq,
                                topHolderPct   = 0.0,
                                buyPressurePct = buyPct,
                                v3Score        = 60,
                                v3Confidence   = 60,
                                momentum       = momentum,
                                volatility     = kotlin.math.abs(change)
                            )
                            if (sig.shouldEnter) {
                                signals++
                                ErrorLogger.info(TAG, "🪙🔵 DynSig BlueChip: ${tok.symbol} conf=${sig.confidence}")
                                try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}
                                val dynMarket = PerpsMarket.values().find { it.symbol == tok.symbol }
                                if (dynMarket != null) dynExecutableSignals.add(AltSignal(
                                    market = dynMarket, direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT,
                                    score = sig.confidence + 5, confidence = sig.confidence, price = price,
                                    priceChange24h = change, reasons = listOf("DynScan BlueChip mcap=\$${(mcap/1_000_000).toInt()}M"),
                                    layerVotes = emptyMap()
                                ))
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── Express sub-AI (high-momentum tokens) ────────────────────────────
                if (kotlin.math.abs(change) > 5.0 && vol > 100_000.0) {
                    if (!ShitCoinExpress.hasRide(tok.symbol)) {
                        try {
                            val sig = ShitCoinExpress.evaluate(
                                mint            = tok.symbol,
                                symbol          = tok.symbol,
                                currentPrice    = price,
                                marketCapUsd    = mcap,
                                liquidityUsd    = liq,
                                momentum        = momentum,
                                buyPressurePct  = buyPct,
                                volumeChange    = if (vol > 0) 1.5 else 1.0,
                                priceChange5Min = change / 288.0,
                                isTrending      = tok.isTrending,
                                isBoosted       = tok.isBoosted,
                                tokenAgeMinutes = 9999.0
                            )
                            if (sig.shouldRide) {
                                signals++
                                ErrorLogger.info(TAG, "🪙⚡ DynSig Express: ${tok.symbol}")
                                try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── Moonshot sub-AI (ultra-low mcap, trending) ───────────────────────
                if (mcap in 100_000.0..50_000_000.0 || tok.isTrending) {
                    if (!MoonshotTraderAI.hasPosition(tok.symbol)) {
                        try {
                            val sig = MoonshotTraderAI.scoreToken(
                                mint           = tok.symbol,
                                symbol         = tok.symbol,
                                marketCapUsd   = mcap,
                                liquidityUsd   = liq,
                                volumeScore    = minOf(100, (vol / 10_000).toInt()),
                                buyPressurePct = buyPct,
                                rugcheckScore  = if (tok.source.contains("Jupiter")) 5 else 3,
                                v3EntryScore   = 60.0,
                                v3Confidence   = 60.0,
                                phase          = "DynamicAlt",
                                isPaper        = isPaperMode.get()
                            )
                            if (sig.eligible) {
                                signals++
                                ErrorLogger.info(TAG, "🪙🌙 DynSig Moonshot: ${tok.symbol}")
                                try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}
                                val dynMarket = PerpsMarket.values().find { it.symbol == tok.symbol }
                                if (dynMarket != null) dynExecutableSignals.add(AltSignal(
                                    market = dynMarket, direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT,
                                    score = sig.score.coerceAtMost(95), confidence = sig.score.coerceAtMost(95), price = price,
                                    priceChange24h = change, reasons = listOf("DynScan Moonshot trending=${tok.isTrending}"),
                                    layerVotes = emptyMap()
                                ))
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── Manipulated sub-AI (pump signals) ────────────────────────────────
                if (!ManipulatedTraderAI.hasPosition(tok.symbol)) {
                    try {
                        val sig = ManipulatedTraderAI.evaluate(
                            mint          = tok.symbol,
                            symbol        = tok.symbol,
                            currentPrice  = price,
                            marketCapUsd  = mcap,
                            liquidityUsd  = liq,
                            momentum      = momentum,
                            buyPressurePct= buyPct,
                            bundlePct     = 0.0,
                            source        = tok.source,
                            ageMinutes    = 9999.0,
                            rugcheckScore = if (tok.source.contains("Jupiter")) 5 else 3,
                            isPaper       = isPaperMode.get()
                        )
                        if (sig.shouldEnter) {
                            signals++
                            ErrorLogger.info(TAG, "🪙🎭 DynSig Manip: ${tok.symbol}")
                            try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }

            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }

        // V5.9.2: Execute top DynScan signals — previously these were logged but never acted on
        // Now we convert high-confidence DynToken signals into real AltSignal trades
        if (dynExecutableSignals.isNotEmpty() && positions.size < MAX_POSITIONS) {
            val scoreThresh = try { FluidLearningAI.getMarketsSpotScoreThreshold() } catch (_: Exception) { 60 }
            val confThresh  = try { FluidLearningAI.getMarketsSpotConfThreshold() }  catch (_: Exception) { 55 }
            val topDyn = dynExecutableSignals
                .filter { it.score >= scoreThresh && it.confidence >= confThresh }
                .sortedByDescending { it.score }
                .take(3) // max 3 new positions per DynScan cycle
            for (sig in topDyn) {
                if (positions.size >= MAX_POSITIONS) break
                if (hasPosition(sig.market)) continue
                // V5.9.3: respect UI toggle for DynScan signals too
                val dynSpot = !preferLeverage.get()
                val dynLev  = if (dynSpot) 1.0 else DEFAULT_LEVERAGE
                ErrorLogger.info(TAG, "🪙⚡ DynScan EXECUTE: ${sig.market.symbol} score=${sig.score} conf=${sig.confidence} ${if (dynSpot) "SPOT" else "${dynLev.toInt()}x"}")
                executeSignal(sig.copy(leverage = dynLev), isSpot = dynSpot)
            }
        }

        if (signals > 0 || scanned % 200 == 0) {
            ErrorLogger.info(TAG, "🪙⚡ DynScan done: scanned=$scanned execSignals=${dynExecutableSignals.size} (universe=${allTokens.size})")
        }
    }

    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()

        // Periodic persistence — save learning state every 10 scan cycles
        if (scanNum % 10 == 0) {
            saveToSharedPrefs()
            savePersistedState()
            try { FluidLearningAI.saveMarketsPrefs() } catch (_: Exception) {}
            try { PerpsLearningBridge.save() } catch (_: Exception) {}
        }

        ErrorLogger.info(TAG, "🪙 ═══════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🪙 ALT SCAN #$scanNum STARTING")
        ErrorLogger.info(TAG, "🪙 positions=${positions.size} | balance=${"%.2f".format(getBalance())} SOL")
        ErrorLogger.info(TAG, "🪙 ═══════════════════════════════════════════════════")

        // All crypto markets that are NOT covered by the SOL perps engine
        val altMarkets = PerpsMarket.values().filter { m ->
            m.isCrypto && !SOL_PERPS_SYMBOLS.contains(m.symbol)
        }

        val signals      = mutableListOf<AltSignal>()
        var analyzed     = 0
        var skippedPos   = 0
        var skippedPrice = 0

        for (market in altMarkets) {
            try {
                if (hasPosition(market)) { skippedPos++; continue }

                val data = PerpsMarketDataFetcher.getMarketData(market)

                if (data.price <= 0) {
                    skippedPrice++
                    ErrorLogger.warn(TAG, "🪙 ${market.symbol}: price=0 — skipped")
                    continue
                }

                // Feed V4 meta layers
                try {
                    CrossAssetLeadLagAI.recordReturn(market.symbol, data.priceChange24hPct)
                    CrossMarketRegimeAI.updateMarketState(market.symbol, data.price, data.priceChange24hPct, data.volume24h)
                } catch (_: Exception) {}

                analyzed++

                val signal = analyzeAlt(market, data)
                if (signal != null) {
                    val scoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                    val confThresh  = FluidLearningAI.getMarketsSpotConfThreshold()

                    if (signal.score >= scoreThresh && signal.confidence >= confThresh) {
                        signals.add(signal)
                        ErrorLogger.info(TAG, "🪙 SIGNAL: ${market.symbol} | score=${signal.score} | conf=${signal.confidence} | dir=${signal.direction.symbol}")
                    } else {
                        ErrorLogger.warn(TAG, "🪙 ${market.symbol}: below threshold (${signal.score}<$scoreThresh or ${signal.confidence}<$confThresh)")
                    }
                } else {
                    ErrorLogger.warn(TAG, "🪙 ${market.symbol}: analyzeAlt returned null")
                }

            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { ErrorLogger.error(TAG, "🪙 ${market.symbol} exception: ${e.message}", e) }
        }

        ErrorLogger.info(TAG, "🪙 Scan stats: analyzed=$analyzed | hasPos=$skippedPos | badPrice=$skippedPrice | signals=${signals.size}")

        // V4 meta scan
        try {
            CrossAssetLeadLagAI.scan()
            CrossMarketRegimeAI.assessRegime()
            LeverageSurvivalAI.assess(
                currentVolatility = signals.map { abs(it.priceChange24h) }.average().takeIf { !it.isNaN() } ?: 0.0,
                fragilityScore    = LiquidityFragilityAI.getFragilityScore("ALT_MARKET")
            )
            CrossTalkFusionEngine.fuse()
        } catch (_: Exception) {}

        val topSignals = signals.sortedByDescending { it.score }.take(5)

        if (topSignals.isEmpty()) {
            ErrorLogger.warn(TAG, "🪙 ⚠️ NO SIGNALS — skipping execution")
            return
        }

        ErrorLogger.info(TAG, "🪙 TOP ${topSignals.size} signals: ${topSignals.map { "${it.market.symbol}(${it.score}/${it.confidence})" }}")

        for (signal in topSignals) {
            if (positions.size >= MAX_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached")
                break
            }
            // V5.9.98: Soft cap + priority-replace removed per user — it was
            // blocking genuine top-5 signals (SHIB 76, MNGO 74, XLM 74,
            // ALPHA 74, AUDIO 74) because the current book of 39 held
            // positions all had score >= 84. Let every qualifying signal
            // open; MAX_POSITIONS (10_000) remains as the hard sanity
            // bound. Fluid position size already shrinks low-conviction
            // entries so over-cap risk is naturally bounded.

            // V5.9.3: Respect the UI SPOT/LEVERAGE toggle instead of alternating by parity
            val useSpotDefault = !preferLeverage.get()
            var useSpot  = useSpotDefault
            var leverage = if (useSpot) 1.0 else DEFAULT_LEVERAGE

            try {
                CrossMarketRegimeAI.updateMarketState(signal.market.symbol, signal.price, signal.price * 0.01)
                val gated = CrossTalkFusionEngine.computeGatedScore(
                    baseScore          = signal.score.toDouble(),
                    strategy           = "CryptoAltAI",
                    market             = "CRYPTO",
                    symbol             = signal.market.symbol,
                    leverageRequested  = leverage
                )
                if (gated.vetoes.any { it.startsWith("LEVERAGE") } && !useSpotDefault) {
                    useSpot  = true
                    leverage = 1.0
                    ErrorLogger.info(TAG, "🪙 V4 META: leverage suppressed for ${signal.market.symbol}")
                }
                PortfolioHeatAI.addPosition(
                    id       = "ALT_${signal.market.symbol}",
                    symbol   = signal.market.symbol,
                    market   = "CRYPTO",
                    sector   = signal.market.emoji,
                    direction= signal.direction.name,
                    sizeSol  = getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100) * fluidSizeMultiplier(signal.score, signal.confidence),
                    leverage = leverage
                )
            } catch (_: Exception) {}

            ErrorLogger.info(TAG, "🪙 EXECUTING: ${signal.market.symbol} ${signal.direction.symbol} @ ${"%.4f".format(signal.price)} | ${if (useSpot) "SPOT" else "${leverage.toInt()}x"}")
            executeSignal(signal.copy(leverage = leverage), isSpot = useSpot)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS — CryptoAltScannerAI embedded
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun analyzeAlt(market: PerpsMarket, data: PerpsMarketData): AltSignal? {
        val reasons    = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score      = 50
        var confidence = 50

        val change    = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT

        // ── Layer 1: Price Momentum ───────────────────────────────────────────
        when {
            abs(change) > 8.0 -> { score += 22; confidence += 18; reasons.add("🚀 Strong surge: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 5.0 -> { score += 16; confidence += 12; reasons.add("📈 Good move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 2.5 -> { score += 10; confidence +=  8; reasons.add("📊 Mild move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 1.0 -> { score +=  5;                   reasons.add("📉 Small move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
        }
        layerVotes["Momentum"] = direction

        // ── Layer 2: Alt Category / Sector Bonus ─────────────────────────────
        when {
            // Layer 1 DeFi blue-chips
            market.symbol in listOf("AAVE", "MKR", "CRV", "SNX", "LDO", "RPL") -> {
                score += 8; confidence += 10; reasons.add("🏛️ DeFi blue-chip")
            }
            // Meme / narrative
            market.symbol in listOf("SHIB", "FLOKI", "TRUMP", "POPCAT", "NOT") -> {
                score += 12; confidence += 5; reasons.add("🐸 Meme narrative play")
            }
            // Gaming / metaverse
            market.symbol in listOf("AXS", "SAND", "MANA", "IMX") -> {
                score += 6; reasons.add("🎮 Gaming sector")
            }
            // Solana ecosystem (not covered by main engine)
            market.symbol in listOf("MNGO", "PYTH", "RAY", "ORCA") -> {
                score += 10; confidence += 8; reasons.add("◎ Solana ecosystem")
            }
            // L1 alts
            market.symbol in listOf("FTM", "ALGO", "HBAR", "ICP", "VET", "NEAR", "EOS") -> {
                score += 7; confidence += 6; reasons.add("⛓️ L1 alt")
            }
            // Storage / compute
            market.symbol in listOf("FIL", "RENDER", "GRT") -> {
                score += 8; confidence += 6; reasons.add("💾 Storage/compute narrative")
            }
            // Layer-2 & cross-chain
            market.symbol in listOf("STX", "ENS", "RUNE") -> {
                score += 7; confidence += 5; reasons.add("🔗 L2 / cross-chain")
            }
            // Exchange tokens
            market.symbol in listOf("BNB", "CRO") -> {
                score += 9; confidence += 10; reasons.add("🏦 Exchange token")
            }
            // OG alts
            market.symbol in listOf("BTC", "ETH", "LTC", "XMR", "ZEC", "ETC") -> {
                score += 8; confidence += 12; reasons.add("🪙 OG alt — deep liquidity")
            }
        }

        // ── Layer 3: BTC Correlation Divergence ───────────────────────────────
        // If BTC is falling but this alt is rising — strength signal
        try {
            val btcData = PerpsMarketDataFetcher.getMarketData(PerpsMarket.BTC)
            val btcChange = btcData.priceChange24hPct
            val divergence = change - btcChange
            when {
                divergence > 5.0 && direction == PerpsDirection.LONG -> {
                    score += 15; confidence += 10
                    reasons.add("⚡ Alt leading BTC by +${"%.1f".format(divergence)}%")
                    layerVotes["BtcDivergence"] = PerpsDirection.LONG
                }
                divergence < -5.0 && direction == PerpsDirection.SHORT -> {
                    score += 12; confidence += 8
                    reasons.add("📉 Alt lagging BTC by ${"%.1f".format(divergence)}%")
                    layerVotes["BtcDivergence"] = PerpsDirection.SHORT
                }
                abs(btcChange) < 1.0 && abs(change) > 3.0 -> {
                    // Alt moving on its own — narrative momentum
                    score += 8; confidence += 6
                    reasons.add("🎯 Narrative move (BTC flat)")
                    layerVotes["NarrativeMomentum"] = direction
                }
            }
        } catch (_: Exception) {}

        // ── Layer 4: Technical Analysis (PerpsAdvancedAI) ────────────────────
        try {
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)

            if (technicals.recommendation == direction) {
                score += 10; confidence += 10
                reasons.add("📊 RSI confirms: ${"%.0f".format(technicals.rsi)}")
                layerVotes["Technical"] = direction
            }
            if (technicals.isOversold  && direction == PerpsDirection.LONG)  { score += 15; reasons.add("📉 Oversold bounce") }
            if (technicals.isOverbought && direction == PerpsDirection.SHORT) { score += 15; reasons.add("📈 Overbought short") }
        } catch (_: Exception) {}

        // ── Layer 5: Volume Spike ─────────────────────────────────────────────
        try {
            val volume = PerpsAdvancedAI.analyzeVolume(market)
            if (volume.isSpike) {
                score += when (volume.spikeStrength) {
                    "EXTREME" -> 20; "STRONG" -> 15; "MILD" -> 8; else -> 0
                }
                reasons.add("📊 Volume ${volume.spikeStrength}")
                layerVotes["Volume"] = direction
            }
        } catch (_: Exception) {}

        // ── Layer 6: Support / Resistance ────────────────────────────────────
        try {
            val sr = PerpsAdvancedAI.analyzeSupportResistance(market, data.price)
            if (sr.nearSupport    && direction == PerpsDirection.LONG)  { score += 10; reasons.add("📍 Near support") }
            if (sr.nearResistance && direction == PerpsDirection.SHORT) { score += 10; reasons.add("📍 Near resistance") }
        } catch (_: Exception) {}

        // ── Layer 7: Fluid Learning ───────────────────────────────────────────
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) { confidence += 5; reasons.add("📚 Learning: ${progress.toInt()}%") }
            val crossBoost = FluidLearningAI.getCrossLearnedConfidence(confidence.toDouble()) - confidence
            if (crossBoost > 0) { confidence += crossBoost.toInt(); reasons.add("🔗 Cross-boost: +${crossBoost.toInt()}") }
        } catch (_: Exception) {}

        // ── Layer 8: Pattern Memory ───────────────────────────────────────────
        try {
            val technicals   = PerpsAdvancedAI.analyzeTechnicals(market)
            val patternConf  = PerpsAdvancedAI.getPatternConfidence(market, direction, technicals)
            if (patternConf > 60) {
                score += 10; confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}

        // ── Layer 9: CryptoAltScannerAI — Sector Heat ────────────────────────
        try {
            val sectorHeat = CryptoAltScannerAI.getSectorHeat(market.symbol)
            if (sectorHeat > 0.6) {
                score += 12; confidence += 8
                reasons.add("🔥 Sector heat: ${"%.0f".format(sectorHeat * 100)}%")
                layerVotes["SectorHeat"] = direction
            } else if (sectorHeat < 0.3 && direction == PerpsDirection.SHORT) {
                score += 8; reasons.add("❄️ Sector cooling — short bias")
            }
        } catch (_: Exception) {}

        // ── Layer 10: CryptoAltScannerAI — Dominance Cycle ───────────────────
        try {
            val dominanceSignal = CryptoAltScannerAI.getDominanceCycleSignal()
            if (dominanceSignal == "ALT_SEASON" && direction == PerpsDirection.LONG) {
                score += 15; confidence += 10
                reasons.add("🌊 Alt season signal")
                layerVotes["DominanceCycle"] = PerpsDirection.LONG
            } else if (dominanceSignal == "BTC_DOMINANCE" && direction == PerpsDirection.SHORT) {
                score += 10; reasons.add("🏦 BTC dominance rising — alt risk-off")
                layerVotes["DominanceCycle"] = PerpsDirection.SHORT
            }
        } catch (_: Exception) {}

        // ── Layer 11: BehaviorAI — session sentiment ────────────────────────
        try {
            val bAdj    = BehaviorAI.getScoreAdjustment()
            val confMod = BehaviorAI.getConfidenceModifier()
            if (bAdj != 0 || confMod != 0) {
                score += bAdj; confidence += confMod
                reasons.add("🧠 BehaviorAI: ${BehaviorAI.getSentimentClassification()} (${if (bAdj >= 0) "+" else ""}$bAdj)")
            }
        } catch (_: Exception) {}

        // ── Layer 12: VolatilityRegimeAI ─────────────────────────────────────
        try {
            when (VolatilityRegimeAI.getRegime(market.symbol).name) {
                "HIGH"    -> { score -= 5; reasons.add("⚡ High vol") }
                "LOW"     -> { confidence += 5; reasons.add("😴 Low vol — squeeze build?") }
                "SQUEEZE" -> { score += 10; confidence += 8; reasons.add("🎯 Vol squeeze!"); layerVotes["VolSqueeze"] = direction }
            }
        } catch (_: Exception) {}

        // ── Layer 13: NarrativeFlowAI ─────────────────────────────────────────
        try {
            val narHeat = NarrativeFlowAI.getNarrativeHeat(market.symbol)
            if (narHeat > 0.6) {
                val boost = ((NarrativeFlowAI.getNarrativeMultiplier(market.symbol) - 1.0) * 10).toInt().coerceIn(0, 15)
                score += boost; confidence += boost / 2
                reasons.add("📣 Narrative: ${"%.0f".format(narHeat * 100)}% (+$boost)")
                layerVotes["NarrativeHeat"] = direction
            }
        } catch (_: Exception) {}

        // ── Layer 14: StrategyTrustAI ─────────────────────────────────────────
        try {
            val trust    = StrategyTrustAI.getTrustScore("CryptoAltAI")
            val trustMod = when {
                trust > 0.7 -> { score += 8; confidence += 6; 8 }
                trust > 0.5 -> { score += 3; 3 }
                trust < 0.3 -> { score -= 8; confidence -= 5; -8 }
                else        -> 0
            }
            if (trustMod != 0) reasons.add("🤝 Trust: ${"%.2f".format(trust)} (${if (trustMod >= 0) "+" else ""}$trustMod)")
        } catch (_: Exception) {}

        // ── Layer 15: CrossAssetLeadLagAI ─────────────────────────────────────
        try {
            CrossAssetLeadLagAI.recordReturn(market.symbol, change)
            val lead = CrossAssetLeadLagAI.getLeadSignalFor(market.symbol)
            if (lead != null && abs(change) > 1.0) {
                score += 6; confidence += 4; reasons.add("🔗 Lead-lag: ${lead.leader}")
            }
        } catch (_: Exception) {}

        // ── Layer 16: Fear & Greed override ───────────────────────────────────
        try {
            val fg = CryptoAltScannerAI.getCryptoFearGreed()
            when {
                fg >= 80 && direction == PerpsDirection.LONG  -> { score -= 10; reasons.add("⚠️ Extreme greed ($fg)") }
                fg <= 20 && direction == PerpsDirection.SHORT -> { score -= 10; reasons.add("⚠️ Extreme fear ($fg)") }
                fg in 35..65 -> confidence += 3
            }
        } catch (_: Exception) {}

        // ── Always-Trade floor (paper learning mode) ─────────────────────────
        if (score < 35)      score      = 35
        if (confidence < 30) confidence = 30
        reasons.add("📚 CryptoAlt ALWAYS_TRADE learning mode")

        return AltSignal(
            market         = market,
            direction      = direction,
            score          = score.coerceIn(0, 100),
            confidence     = confidence.coerceIn(0, 100),
            price          = data.price,
            priceChange24h = change,
            reasons        = reasons,
            layerVotes     = layerVotes
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun executeSignal(signal: AltSignal, isSpot: Boolean) {
        // V5.9.114: UNIFIED paper + live pipeline.
        // User policy: "live mode should behave exactly like paper". All
        // sizing, sanity, exposure, hive, TP/SL, AI registrations, and
        // position bookkeeping now run identically in both modes. The ONLY
        // difference is the capital-move step: paper debits the paper
        // wallet, live fires a Jupiter swap for the SAME finalSize.

        // Paper execution — only when in paper mode
        val balance = getEffectiveBalance()
        // V5.9.88: FLUID SIZING — scale with AI score + confidence instead of
        // the old flat 3% for every trade. Range: 0.4x..2.0x base size.
        // High-conviction (score≥85, conf≥80) rides 2x; low-conviction <55/40 rides 0.4x.
        val sizeMult = fluidSizeMultiplier(signal.score, signal.confidence)
        val sizeSol  = balance * (DEFAULT_SIZE_PCT / 100) * sizeMult

        if (sizeSol < 0.01) {
            ErrorLogger.warn(TAG, "Insufficient balance for ${signal.market.symbol} (${sizeSol} SOL)")
            return

        // V5.9.5 FIX: Sanity-check entry price vs last cached price.
        // Bad data (decimal shift, wrong feed ID, stale fallback) causes fake 1000x PnL.

        // V5.9.5: Dynamic exposure cap — never exceed 80% of balance at risk.
        // Naturally allows more concurrent positions as wallet grows.
        val totalRisk = positions.values.sumOf { it.sizeSol }
        val maxRisk   = balance * 0.80
        if (totalRisk + sizeSol > maxRisk) {
            ErrorLogger.info(TAG, "🛑 Exposure cap: ${"%.2f".format(totalRisk)}◎ at risk / ${"%.2f".format(maxRisk)}◎ max — skipping ${signal.market.symbol}")
            return
        }
        // V5.9.9: Cross-trader wallet exposure check
        if (!isPaperMode.get()) {
            val walletBal = try { WalletManager.getWallet()?.getSolBalance() ?: 0.0 } catch (_: Exception) { 0.0 }
            if (!com.lifecyclebot.engine.WalletPositionLock.canOpen("CryptoAlt", sizeSol, walletBal)) return
        }
        val cachedPriceData = PerpsMarketDataFetcher.getCachedPrice(signal.market)
        if (signal.price <= 0.0) {
            ErrorLogger.warn(TAG, "🪙 PRICE ZERO: ${signal.market.symbol} — REJECTING trade")
            return
        }
        if (cachedPriceData != null && cachedPriceData.price > 0) {
            val priceDiffPct = kotlin.math.abs(signal.price - cachedPriceData.price) / cachedPriceData.price * 100.0
            if (priceDiffPct > 90.0) {
                ErrorLogger.warn(TAG, "🪙 PRICE SANITY FAIL: ${signal.market.symbol} signal=\$${signal.price} cached=\$${cachedPriceData.price} diff=${priceDiffPct.toInt()}% — REJECTING")
                return
            }
        }
        }

        val tpPct = if (isSpot)
            com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
        else com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsLevTpPct()
        val slPctBase  = if (isSpot) DEFAULT_SL_SPOT else DEFAULT_SL_LEV
        val lev    = if (isSpot) 1.0 else signal.leverage

        // V5.9.88: FLUID TP/SL — scale with conviction, stop flat TP+7%/SL-3%
        // on every trade. High-score signals get wider TP + tighter SL;
        // low-score get tighter TP + wider SL (asymmetric conviction curve).
        val (tpMult, slMult) = fluidTpSlMultiplier(signal.score, signal.confidence)

        // Hivemind size / TP modifier
        val (_, hiveSizeMult, hiveTpAdj) = hiveEntryModifier(signal.market.symbol)
        val finalSize = (sizeSol * hiveSizeMult).coerceIn(0.01, balance * 0.25)
        val finalTp   = ((tpPct * tpMult) + hiveTpAdj).coerceAtLeast(1.5)
        val finalSl   = (slPctBase * slMult).coerceIn(1.5, 15.0)

        val (tp, sl) = when (signal.direction) {
            PerpsDirection.LONG  -> signal.price * (1 + finalTp / 100) to signal.price * (1 - finalSl / 100)
            PerpsDirection.SHORT -> signal.price * (1 - finalTp / 100) to signal.price * (1 + finalSl / 100)
        }

        val position = AltPosition(
            id             = "ALT_${positionCounter.incrementAndGet()}",
            market         = signal.market,
            direction      = signal.direction,
            isSpot         = isSpot,
            entryPrice     = signal.price,
            currentPrice   = signal.price,
            sizeSol        = finalSize,
            leverage       = lev,
            takeProfitPrice= tp,
            stopLossPrice  = sl,
            aiScore        = signal.score,
            aiConfidence   = signal.confidence,
            reasons        = signal.reasons
        )

        // Note: totalTrades incremented at CLOSE, not open, for accurate win rate

        // V5.9.114: UNIFIED capital move. Paper debits paper wallet;
        // live fires a Jupiter swap at the EXACT same finalSize so sizing
        // learnt in paper carries 1:1 into live. If the live swap fails
        // we roll back: the position is not created and we return.
        if (isPaperMode.get()) {
            // V5.9.5: Deduct from shared FluidLearning pool
            try { com.lifecyclebot.engine.FluidLearning.recordPaperBuy(signal.market.symbol, finalSize) } catch (_: Exception) {}
            // V5.9.48: Unified paper wallet — debit deployed capital from main.
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = -finalSize,
                source = "CryptoAlt.open[${signal.market.symbol}]"
            )
        } else {
            // LIVE mode — execute Jupiter swap at the exact paper-sized
            // finalSize. If the swap + phantom-verify fail, we do NOT
            // create a bot position (nothing to clean up on-chain either,
            // because MarketsLiveExecutor only returns success after the
            // target mint actually arrived on-chain).
            val liveOk = executeLiveTradeAtSize(signal, isSpot, finalSize)
            if (!liveOk) {
                ErrorLogger.warn(TAG, "🔴 LIVE alt trade failed: ${signal.market.symbol} — position not recorded")
                return
            }
            ErrorLogger.info(TAG, "🪙 LIVE trade success: ${signal.market.symbol} (paper-sized ${finalSize.fmt(4)}◎)")
        }

        positions[position.id]         = position
        if (isSpot) spotPositions[position.id]     = position
        else        leveragePositions[position.id]  = position

        // ── BehaviorAI tilt protection ──────────────────────────────────────
        try {
            if (BehaviorAI.isTiltProtectionActive()) {
                ErrorLogger.warn(TAG, "🪙 BehaviorAI tilt — skip ${signal.market.symbol}")
                return
            }
        } catch (_: Exception) {}

        try { FluidLearningAI.recordMarketsTradeStart() } catch (_: Exception) {}
        com.lifecyclebot.engine.WalletPositionLock.recordOpen("CryptoAlt", sizeSol)

        // ── MetaCognitionAI — entry prediction ───────────────────────────────
        try {
            val metaSig = if (signal.direction == PerpsDirection.LONG)
                MetaCognitionAI.SignalType.BULLISH else MetaCognitionAI.SignalType.BEARISH
            MetaCognitionAI.recordEntryPredictions(
                mint        = signal.market.symbol,
                symbol      = signal.market.symbol,
                predictions = mapOf(
                    MetaCognitionAI.AILayer.AI_CROSSTALK       to Pair(metaSig, signal.confidence.toDouble()),
                    MetaCognitionAI.AILayer.MOMENTUM_PREDICTOR to Pair(metaSig, signal.score.toDouble())
                )
            )
        } catch (_: Exception) {}

        // ── ShadowLearningEngine — track opportunity ─────────────────────────
        try {
            ShadowLearningEngine.onTradeOpportunity(
                mint               = signal.market.symbol,
                symbol             = signal.market.symbol,
                currentPrice       = signal.price,
                liveEntryScore     = signal.score,
                liveEntryThreshold = 55,
                liveSizeSol        = finalSize,
                phase              = "CryptoAlt_${if (position.isSpot) "SPOT" else "LEV"}"
            )
        } catch (_: Exception) {}

        // ── NarrativeFlowAI — record activity ────────────────────────────────
        try {
            NarrativeFlowAI.recordActivity(
                symbol       = signal.market.symbol,
                volumeSpike  = signal.score > 75,
                priceMovePct = signal.priceChange24h
            )
        } catch (_: Exception) {}

        ErrorLogger.info(TAG, "🪙 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ ${"%.4f".format(signal.price)} | " +
            "${position.leverageLabel} | size=${finalSize.fmt(3)}◎ | score=${signal.score} | TP=${"%.4f".format(tp)} SL=${"%.4f".format(sl)}")

        signal.reasons.take(3).forEach { ErrorLogger.debug(TAG, "   → $it") }

        persistPositionToTurso(position)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE EXECUTION — Jupiter DEX Swap
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V5.9.114: execute a LIVE Jupiter swap at a CALLER-provided size.
     * This is the surgical version of [executeLiveTrade] that does NOT
     * recompute sizing — it trusts the paper-mode pipeline's final size
     * so live behaves exactly like paper. Returns true only after the
     * swap succeeded AND the post-swap phantom verification confirmed
     * the target mint arrived on-chain.
     */
    private suspend fun executeLiveTradeAtSize(
        signal: AltSignal,
        isSpot: Boolean,
        sizeSol: Double,
    ): Boolean {
        return try {
            val wallet = WalletManager.getWallet()
                ?: run { ErrorLogger.warn(TAG, "No wallet — cannot execute LIVE alt trade"); return false }

            val balance = try { wallet.getSolBalance() } catch (_: Exception) { 0.0 }
            if (balance > 0) updateLiveBalance(balance)
            val floor = 0.01
            if (balance < floor || sizeSol < floor) {
                ErrorLogger.warn(TAG, "🪙 ⛔ Live floor: bal=${"%.4f".format(balance)} size=${"%.4f".format(sizeSol)} — skip ${signal.market.symbol}")
                LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.FLOOR_SKIPPED)
                return false
            }

            ErrorLogger.info(
                TAG,
                "🪙 ⚡ LIVE ATTEMPT: ${signal.market.symbol} ${signal.direction.symbol} " +
                "| bal=${"%.4f".format(balance)}◎ size=${"%.4f".format(sizeSol)}◎ " +
                "${if (isSpot) "SPOT" else "${signal.leverage.toInt()}x"}"
            )

            val (success, txSig) = MarketsLiveExecutor.executeLiveTrade(
                market      = signal.market,
                direction   = signal.direction,
                sizeSol     = sizeSol,
                leverage    = if (isSpot) 1.0 else signal.leverage,
                priceUsd    = signal.price,
                traderType  = "CryptoAlt"
            )
            LiveAttemptStats.record(
                "CryptoAlt",
                if (success) LiveAttemptStats.Outcome.EXECUTED else LiveAttemptStats.Outcome.FAILED
            )
            if (success) {
                ErrorLogger.info(TAG, "🪙 LIVE TRADE EXECUTED: ${signal.market.symbol} tx=${txSig ?: "ok"}")
                try { updateLiveBalance(wallet.getSolBalance()) } catch (_: Exception) {}
                true
            } else {
                ErrorLogger.warn(TAG, "🪙 Live execution returned failure for ${signal.market.symbol}")
                false
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "🪙 Live trade exception: ${e.message}", e)
            false
        }
    }

    private suspend fun executeLiveTrade(signal: AltSignal, isSpot: Boolean): Double? {
        return try {
            val wallet = WalletManager.getWallet()
                ?: run { ErrorLogger.warn(TAG, "No wallet — cannot execute LIVE alt trade"); return null }

            // V5.9.8: Always read fresh balance from wallet (not stale cache)
            val balance = try { wallet.getSolBalance() } catch (_: Exception) { 0.0 }
            if (balance > 0) updateLiveBalance(balance)

            // V5.9.37: FLUID sizing. 3% of balance is the default target, but when
            // the wallet is small (<1 SOL) 3% falls below Jupiter's ~0.01 SOL
            // economical-swap floor and every trade was bailing silently.
            // Policy:
            //   • Hard-abort only if the wallet itself is below the floor.
            //   • Otherwise clamp size to [FLOOR, balance * 15%] so small wallets
            //     can still participate without YOLO-ing.
            val floor = 0.01
            // V5.9.88: fluid sizing on LIVE path too — scale conviction-based
            val sizeMult = fluidSizeMultiplier(signal.score, signal.confidence)
            val desired = balance * (DEFAULT_SIZE_PCT / 100) * sizeMult
            if (balance < floor) {
                ErrorLogger.warn(TAG, "🪙 ⛔ Live wallet too small: ${"%.4f".format(balance)} SOL < ${floor} floor — cannot live-trade ${signal.market.symbol}")
                LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.FLOOR_SKIPPED)
                return null
            }
            val sizeSol = desired.coerceIn(floor, (balance * 0.15).coerceAtLeast(floor))

            ErrorLogger.info(
                TAG,
                "🪙 ⚡ LIVE ATTEMPT: ${signal.market.symbol} ${signal.direction.symbol} " +
                "| bal=${"%.4f".format(balance)}◎ size=${"%.4f".format(sizeSol)}◎ " +
                "${if (isSpot) "SPOT" else "${signal.leverage.toInt()}x"}"
            )

            // Execute via MarketsLiveExecutor (handles Jupiter swap + signing)
            // Fee collection (0.5% spot / 1% leverage) is handled inside MarketsLiveExecutor.executeLiveTrade
            val (success, txSig) = MarketsLiveExecutor.executeLiveTrade(
                market      = signal.market,
                direction   = signal.direction,
                sizeSol     = sizeSol,
                leverage    = if (isSpot) 1.0 else signal.leverage,
                priceUsd    = signal.price,
                traderType  = "CryptoAlt"
            )

            LiveAttemptStats.record(
                "CryptoAlt",
                if (success) LiveAttemptStats.Outcome.EXECUTED else LiveAttemptStats.Outcome.FAILED
            )

            if (success) {
                ErrorLogger.info(TAG, "🪙 LIVE TRADE EXECUTED: ${signal.market.symbol} tx=${txSig ?: "ok"}")
                // V5.9.8: Read fresh balance from wallet (not stale calculation)
                try { updateLiveBalance(wallet.getSolBalance()) } catch (_: Exception) {}
                sizeSol
            } else {
                ErrorLogger.warn(TAG, "🪙 Live execution returned failure for ${signal.market.symbol}")
                null
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "🪙 Live trade exception: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun monitorPositions() {
        for ((id, position) in positions.toMap()) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(position.market)
                if (data.price <= 0) continue


                // V5.9.5 FIX: Spike guard — reject price if >10x or <0.1x entry price.
                // Prevents false TP/SL triggers from bad API responses.
                val priceRatio = if (position.entryPrice > 0) data.price / position.entryPrice else 1.0
                if (priceRatio > 10.0 || priceRatio < 0.1) {
                    ErrorLogger.warn(TAG, "🪙 SPIKE GUARD: ${position.market.symbol} entry=\$${position.entryPrice} new=\$${data.price} ratio=${"%.2f".format(priceRatio)}x — skipping")
                    continue
                }
                val updated = position.copy(currentPrice = data.price)
                // V5.9.9: Track peak PnL for trailing stop
                val currentPnl = updated.getPnlPct()
                if (currentPnl > updated.highestPnlPct) {
                    updated.highestPnlPct = currentPnl
                }
                positions[id] = updated
                if (updated.isSpot) {
                    spotPositions[id] = updated
                    leveragePositions.remove(id)
                } else {
                    leveragePositions[id] = updated
                    spotPositions.remove(id)
                }

                val tpPct = if (updated.isSpot)
                    com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsSpotTpPct()
                else com.lifecyclebot.v3.scoring.FluidLearningAI.getMarketsLevTpPct()

                // V5.9.9: FULLY AGENTIC EXIT — SymbolicExitReasoner evaluates every cycle
                val holdSec = (System.currentTimeMillis() - updated.openTime) / 1000
                val peakPnl = if (updated.highestPnlPct > updated.getPnlPct()) updated.highestPnlPct else updated.getPnlPct()

                // Calculate price velocity (% change per minute over hold period)
                val priceVelocity = if (holdSec > 30) updated.getPnlPct() / (holdSec / 60.0) else 0.0

                val assessment = com.lifecyclebot.engine.SymbolicExitReasoner.assess(
                    currentPnlPct   = updated.getPnlPct(),
                    peakPnlPct      = peakPnl,
                    entryConfidence = updated.aiConfidence.toDouble(),
                    tradingMode     = updated.reasons.firstOrNull() ?: "CryptoAltAI",
                    holdTimeSec     = holdSec,
                    priceVelocity   = priceVelocity,
                    volumeRatio     = 1.0
                )

                when (assessment.suggestedAction) {
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.EXIT ->
                        closePosition(id, "AI_EXIT: ${assessment.primarySignal} (conv=${"%.2f".format(assessment.conviction)})")
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.PARTIAL ->
                        closePosition(id, "AI_PARTIAL: ${assessment.primarySignal} (conv=${"%.2f".format(assessment.conviction)})")
                    else -> {
                        // V5.9.118: FLUID PROFIT-FLOOR LOCK — same lock semantics
                        // as the main meme trader so alts runners don't give back
                        // huge gains. getDynamicFluidStop returns a POSITIVE
                        // trailing stop level while in profit; exit fires when
                        // currentPnl <= that level. Paired with a peak-drawdown
                        // hard floor (>=35% give-back on peak>=100%).
                        val dynamicLock = try {
                            com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                                modeDefaultStop = 20.0,
                                currentPnlPct = currentPnl,
                                peakPnlPct = peakPnl,
                                holdTimeSeconds = holdSec.toDouble(),
                                volatility = 50.0,
                            )
                        } catch (_: Exception) { Double.NEGATIVE_INFINITY }

                        val lockFired = dynamicLock > 0.0 && currentPnl <= dynamicLock
                        val peakDrawdownFired = peakPnl >= 100.0 && (peakPnl - currentPnl) >= 35.0

                        when {
                            lockFired ->
                                closePosition(id, "FLUID_LOCK: peak=+${peakPnl.toInt()}% now=+${currentPnl.toInt()}% lock=+${dynamicLock.toInt()}%")
                            peakDrawdownFired ->
                                closePosition(id, "PEAK_DRAWDOWN: peak=+${peakPnl.toInt()}% now=+${currentPnl.toInt()}% (gave back ${(peakPnl - currentPnl).toInt()}%)")
                            updated.shouldTakeProfit(tpPct * 1.5) ->
                                closePosition(id, "TP_SAFETY: +${"%.2f".format(updated.getPnlPct())}% exceeded ${tpPct * 1.5}%")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }
    }

    private fun closePosition(positionId: String, reason: String) {
        val pos = positions.remove(positionId) ?: return
        spotPositions.remove(positionId)
        leveragePositions.remove(positionId)
        com.lifecyclebot.engine.WalletPositionLock.recordClose("CryptoAlt", pos.sizeSol)

        val pnlSol = pos.getPnlSol()
        totalPnlSol += pnlSol

        // V5.7.7: Count trades at close so win rate is accurate (wins+losses / total)
        totalTrades.incrementAndGet()
        if (pnlSol >= 0) {
            winningTrades.incrementAndGet()
            try { if (isPaperMode.get()) FluidLearningAI.recordMarketsPaperTrade(true, pos.getPnlPct()) else FluidLearningAI.recordMarketsLiveTrade(true) } catch (_: Exception) {}
        } else {
            losingTrades.incrementAndGet()
            try { if (isPaperMode.get()) FluidLearningAI.recordMarketsPaperTrade(false, pos.getPnlPct()) else FluidLearningAI.recordMarketsLiveTrade(false) } catch (_: Exception) {}
        }

        if (isPaperMode.get()) {
            // V5.9.5: Return funds to shared FluidLearning pool
            try { com.lifecyclebot.engine.FluidLearning.recordPaperSell(pos.market.symbol, pos.sizeSol, pnlSol) } catch (_: Exception) {}
            // Keep local paperBalance in sync for persistence/Turso
            paperBalance = com.lifecyclebot.engine.FluidLearning.getSimulatedBalance()
            // V5.9.48: Unified paper wallet — capital + PnL back to main dashboard.
            com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                delta = pos.sizeSol + pnlSol,
                source = "CryptoAlt.close[${pos.market.symbol}]"
            )
        } else {
            // Live mode: execute on-chain close — MUST wait for result before removing position
            var closeSuccess = false
            try {
                closeSuccess = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val (ok, _) = MarketsLiveExecutor.closeLivePosition(
                        market     = pos.market,
                        direction  = pos.direction,
                        sizeSol    = pos.sizeSol,
                        leverage   = pos.leverage,
                        traderType = "CryptoAlt"
                    )
                    ok
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "🪙 Live close failed for ${pos.market.symbol}: ${e.message}")
            }
            if (!closeSuccess) {
                ErrorLogger.warn(TAG, "🚨 LIVE CLOSE FAILED: ${pos.market.symbol} — position kept open for retry")
                return // DON'T remove position if on-chain close failed
            }
            try {
                val newBal = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBal)
            } catch (_: Exception) {}
        }

        val pnlPct    = pos.getPnlPct()
        val isWin     = pnlSol >= 0
        val paper     = isPaperMode.get()
        val modeStr   = if (paper) "paper" else "live"
        val timestamp = System.currentTimeMillis()
        val holdMs    = timestamp - pos.openTime

        // V5.9.9: Sentient personality reacts to trade outcomes
        try {
            if (isWin) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(
                    pos.market.symbol, pnlPct, pos.reasons.firstOrNull() ?: "CryptoAltAI", holdMs / 1000
                )
            } else {
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(
                    pos.market.symbol, pnlPct, pos.reasons.firstOrNull() ?: "CryptoAltAI", reason
                )
            }
        } catch (_: Exception) {}

        // V5.7.7: Archive closed position for Positions tab history
        val closedPos = pos.copy(
            currentPrice  = pos.currentPrice,
            closeTime     = timestamp,
            closePrice    = pos.currentPrice,
            realizedPnl   = pnlSol
        )
        closedPositions.add(0, closedPos)
        if (closedPositions.size > MAX_CLOSED_HISTORY) {
            closedPositions.subList(MAX_CLOSED_HISTORY, closedPositions.size).clear()
        }
        // Persist counters immediately so win rate survives restarts
        try { saveToSharedPrefs() } catch (_: Exception) {}

        ErrorLogger.info(TAG, "🪙 CLOSED: ${pos.market.symbol} | $reason | pnl=${pnlSol.fmt(3)}◎ | wr=${"%.0f".format(getWinRate())}%")

        try { PortfolioHeatAI.removePosition("ALT_${pos.market.symbol}") } catch (_: Exception) {}

        // ── BehaviorAI ────────────────────────────────────────────────────────
        try { BehaviorAI.recordTrade(pnlPct = pnlPct, reason = reason, mint = pos.market.symbol, isPaperMode = paper) } catch (_: Exception) {}

        // ── TradeHistoryStore — cross-bot shared log ──────────────────────────
        try {
            TradeHistoryStore.recordTrade(Trade(
                side             = "SELL", mode = modeStr,
                sol              = pos.sizeSol, price = pos.currentPrice,
                ts               = timestamp, reason = "ALT:$reason",
                pnlSol           = pnlSol, pnlPct = pnlPct,
                score            = pos.aiScore.toDouble(),
                tradingMode      = "CryptoAlt_${if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x"}",
                tradingModeEmoji = "🪙", mint = pos.market.symbol
            ))
        } catch (_: Exception) {}

        // ── RunTracker30D ─────────────────────────────────────────────────────
        try {
            if (RunTracker30D.isRunActive()) {
                RunTracker30D.recordTrade(
                    symbol = pos.market.symbol, mint = pos.market.symbol,
                    entryPrice = pos.entryPrice, exitPrice = pos.currentPrice,
                    sizeSol = pos.sizeSol, pnlPct = pnlPct,
                    holdTimeSec = holdMs / 1000,
                    mode = "CryptoAlt_${if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x"}",
                    score = pos.aiScore, confidence = pos.aiConfidence, decision = reason
                )
            }
        } catch (_: Exception) {}

        // ── TradeLessonRecorder — StrategyTrustAI cross-learning ─────────────
        try {
            val lessonCtx = TradeLessonRecorder.captureContext(
                strategy = "CryptoAltAI", market = "CRYPTO_ALT",
                symbol = pos.market.symbol, leverageUsed = pos.leverage,
                executionRoute = if (paper) "PAPER" else "LIVE",
                expectedFillPrice = pos.entryPrice
            )
            TradeLessonRecorder.completeLesson(
                context = lessonCtx, outcomePct = pnlPct,
                mfePct = if (isWin) pnlPct else 0.0, maePct = if (!isWin) pnlPct else 0.0,
                holdSec = (holdMs / 1000).toInt().coerceAtLeast(1),
                exitReason = reason, actualFillPrice = pos.currentPrice
            )
        } catch (_: Exception) {}

        // ── MetaCognitionAI ───────────────────────────────────────────────────
        try {
            MetaCognitionAI.recordTradeOutcome(
                mint = pos.market.symbol, symbol = pos.market.symbol,
                pnlPct = pnlPct, holdTimeMs = holdMs, exitReason = reason
            )
        } catch (_: Exception) {}

        // ── ShadowLearningEngine exit ─────────────────────────────────────────
        try {
            ShadowLearningEngine.onLiveTradeExit(
                mint = pos.market.symbol, exitPrice = pos.currentPrice,
                exitReason = reason, livePnlSol = pnlSol, isWin = isWin
            )
        } catch (_: Exception) {}

        // ── PerpsLearningBridge — cross-layer learning from alt trade ─────────
        try {
            val perpsTrade = PerpsTrade(
                id          = pos.id,
                market      = pos.market,
                direction   = pos.direction,
                side        = "CLOSE",
                entryPrice  = pos.entryPrice,
                exitPrice   = pos.currentPrice,
                sizeSol     = pos.sizeSol,
                leverage    = pos.leverage,
                pnlUsd      = pnlSol * WalletManager.lastKnownSolPrice,
                pnlPct      = pnlPct,
                openTime    = pos.openTime,
                closeTime   = timestamp,
                closeReason = reason,
                isPaper     = paper,
                aiScore     = pos.aiScore,
                aiConfidence= pos.aiConfidence,
                riskTier    = if (pos.isSpot) PerpsRiskTier.SNIPER else if (pos.leverage <= 3.0) PerpsRiskTier.TACTICAL else PerpsRiskTier.ASSAULT
            )
            PerpsLearningBridge.learnFromPerpsTrade(
                trade = perpsTrade,
                contributingLayers = pos.reasons.mapNotNull { r ->
                    when {
                        r.contains("ShitCoin")     -> "ShitCoinTraderAI"
                        r.contains("BlueChip")     -> "BlueChipTraderAI"
                        r.contains("Quality")      -> "QualityTraderAI"
                        r.contains("Express")      -> "ShitCoinExpress"
                        r.contains("Moonshot")     -> "MoonshotTraderAI"
                        r.contains("Manip")        -> "ManipulatedTraderAI"
                        r.contains("Momentum")     -> "MomentumPredictorAI"
                        r.contains("Technical")    -> "PerpsAdvancedAI"
                        r.contains("NarrativeHeat")-> "NarrativeFlowAI"
                        r.contains("Trust")        -> "StrategyTrustAI"
                        r.contains("BehaviorAI")   -> "BehaviorAI"
                        else                       -> null
                    }
                }.distinct().ifEmpty { listOf("CryptoAltAI") },
                predictedDirection = pos.direction
            )
            ErrorLogger.debug(TAG, "🪙🧠 PerpsLearningBridge: ${pos.market.symbol} | pnl=${pnlPct.fmt(1)}% | cross-learn OK")
        } catch (_: Exception) {}

        // ── FluidLearningAI persistence ───────────────────────────────────────
        try { FluidLearningAI.saveMarketsPrefs() } catch (_: Exception) {}

        // V5.9.112: feed live PnL into LiveSafetyCircuitBreaker for session drawdown halt.
        if (!paper) {
            try { com.lifecyclebot.engine.LiveSafetyCircuitBreaker.recordTradeResult(pnlSol) } catch (_: Exception) {}
        }

        saveToSharedPrefs()
        savePersistedState()
        persistTradeToTurso(pos, pnlSol, reason)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.88: FLUID SIZING & FLUID TP/SL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Scales base position size based on combined AI score + confidence.
     * Returns a multiplier in [0.4, 2.0] — low-conviction trades ride smaller,
     * high-conviction rides bigger. No more flat 3% on every signal.
     */
    private fun fluidSizeMultiplier(score: Int, confidence: Int): Double {
        val s = score.coerceIn(0, 100)
        val c = confidence.coerceIn(0, 100)
        // Blend score (60%) and confidence (40%) — score is the harder signal.
        val blended = (s * 0.6 + c * 0.4)
        return when {
            blended >= 88 -> 2.00    // "all in" conviction
            blended >= 80 -> 1.65
            blended >= 72 -> 1.35
            blended >= 64 -> 1.10
            blended >= 56 -> 0.90
            blended >= 48 -> 0.70
            else          -> 0.45    // whisper-only, low-risk probe
        }
    }

    /**
     * Scales TP and SL distances based on conviction. Stops the old "every
     * position shows TP+7% SL-3%" pattern — high-conviction trades get more
     * room to run and a tighter stop; low-conviction trades get a tighter
     * TP and a looser stop to absorb noise.
     *
     * Returns (tpMult, slMult) where 1.0 = base pct.
     */
    private fun fluidTpSlMultiplier(score: Int, confidence: Int): Pair<Double, Double> {
        val s = score.coerceIn(0, 100)
        val c = confidence.coerceIn(0, 100)
        val blended = (s * 0.6 + c * 0.4)
        return when {
            blended >= 85 -> 1.75 to 0.75   // wide TP, tight SL — ride the winner
            blended >= 75 -> 1.40 to 0.85
            blended >= 65 -> 1.15 to 0.95
            blended >= 55 -> 1.00 to 1.00   // neutral
            blended >= 45 -> 0.85 to 1.15   // take profit early, wider stop
            else          -> 0.70 to 1.30   // scalp-only probe
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // HIVEMIND ENTRY MODIFIER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hiveEntryModifier(symbol: String): Triple<Boolean, Double, Double> {
        val sizeMult = try {
            (BehaviorAI.getSizingMultiplier().coerceIn(0.5, 2.0) *
             NarrativeFlowAI.getNarrativeMultiplier(symbol).coerceIn(0.8, 1.5)).coerceIn(0.5, 2.0)
        } catch (_: Exception) { 1.0 }
        val tpAdj = try { if (StrategyTrustAI.getTrustMultiplier("CryptoAltAI") > 1.1) 1.5 else 0.0 } catch (_: Exception) { 0.0 }
        return Triple(true, sizeMult, tpAdj)
    }

    private fun loadFromSharedPrefs() {
        val p = prefs ?: return
        paperBalance      = p.getFloat(KEY_BALANCE, 0.0f).toDouble()
        totalTrades.set(   p.getInt(KEY_TRADES,  0))
        winningTrades.set( p.getInt(KEY_WINS,    0))
        losingTrades.set(  p.getInt(KEY_LOSSES,  0))
        totalPnlSol       = p.getFloat(KEY_PNL, 0f).toDouble()
        val savedInitial  = p.getFloat(KEY_INITIAL_BALANCE, 0f).toDouble()
        if (savedInitial > 0.0) initialBalance = savedInitial
        else if (paperBalance > 0.0) initialBalance = paperBalance
        isPaperMode.set(  !p.getBoolean(KEY_LIVE, true))
        ErrorLogger.debug(TAG, "🪙 SharedPrefs: bal=${paperBalance.fmt(2)} trades=${totalTrades.get()}")
    }

    private fun saveToSharedPrefs() {
        prefs?.edit()
            ?.putFloat(KEY_BALANCE,  paperBalance.toFloat())
            ?.putInt(KEY_TRADES,     totalTrades.get())
            ?.putInt(KEY_WINS,       winningTrades.get())
            ?.putInt(KEY_LOSSES,     losingTrades.get())
            ?.putFloat(KEY_PNL,             totalPnlSol.toFloat())
            ?.putFloat(KEY_INITIAL_BALANCE, initialBalance.toFloat())
            ?.putBoolean(KEY_LIVE,  !isPaperMode.get())
            ?.apply()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun loadPersistedState() {
        try {
            val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
            if (tursoClient != null) {
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                // Reuse MarketsState schema — prefix instanceId so it's separate from stocks
                val state = tursoClient.loadMarketsState("ALT_$instanceId")
                if (state != null) {
                    paperBalance = if (state.paperBalanceSol > 1.0) state.paperBalanceSol else 0.0
                    totalTrades.set(state.totalTrades)
                    winningTrades.set(state.totalWins)
                    losingTrades.set(state.totalLosses)
                    totalPnlSol  = state.totalPnlSol
                    // Track initial balance for correct pnl% display
                    if (initialBalance <= 0.0 && paperBalance > 0.0) initialBalance = paperBalance
                    isPaperMode.set(!state.isLiveMode)
                    ErrorLogger.info(TAG, "🪙 Loaded state: balance=${paperBalance.fmt(2)} SOL | trades=${state.totalTrades} | WR=${state.winRate.toInt()}%")
                } else {
                    ErrorLogger.info(TAG, "🪙 No persisted state — using defaults")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Load state error: ${e.message}")
        }
    }

    fun savePersistedState() {
        scope.launch {
            try {
                val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
                if (tursoClient != null) {
                    val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                    val state = com.lifecyclebot.collective.MarketsState(
                        instanceId     = "ALT_$instanceId",
                        paperBalanceSol= paperBalance,
                        totalTrades    = totalTrades.get(),
                        totalWins      = winningTrades.get(),
                        totalLosses    = losingTrades.get(),
                        totalPnlSol    = totalPnlSol,
                        learningPhase  = getPhaseLabel(),
                        isLiveMode     = !isPaperMode.get(),
                        lastUpdated    = System.currentTimeMillis()
                    )
                    tursoClient.saveMarketsState(state)
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Save state error: ${e.message}")
            }
        }
    }

    private fun persistPositionToTurso(pos: AltPosition) {
        scope.launch {
            try {
                val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                // Reuse the Markets position schema
                client.saveMarketsPosition(com.lifecyclebot.collective.MarketsPositionRecord(
                    id               = pos.id,
                    instanceId       = instanceId,
                    assetClass       = "CRYPTO_ALT",
                    market           = pos.market.symbol,
                    direction        = pos.direction.name,
                    tradeType        = if (pos.isSpot) "SPOT" else "LEVERAGE",
                    entryPrice       = pos.entryPrice,
                    currentPrice     = pos.currentPrice,
                    sizeSol          = pos.sizeSol,
                    sizeUsd          = pos.sizeSol * pos.currentPrice,
                    leverage         = pos.leverage,
                    takeProfitPrice  = pos.takeProfitPrice,
                    stopLossPrice    = pos.stopLossPrice,
                    entryTime        = pos.openTime,
                    aiScore          = pos.aiScore,
                    aiConfidence     = pos.aiConfidence,
                    paperMode        = isPaperMode.get(),
                    status           = "OPEN",
                    lastUpdate       = System.currentTimeMillis()
                ))
            } catch (_: Exception) {}
        }
    }

    private fun persistTradeToTurso(pos: AltPosition, pnlSol: Double, reason: String) {
        // Trade history logged — full persistence uses MarketsState (balance + counters) saved on each close
        ErrorLogger.debug(TAG, "🪙 Trade closed: ${pos.market.symbol} | pnl=${pnlSol.fmt(3)}◎ | reason=$reason")
        savePersistedState()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /** Public close — used by UI (CryptoAltActivity) */
    fun requestClose(positionId: String) {
        scope.launch { closePosition(positionId, "USER_REQUEST") }
    }

    fun isRunning(): Boolean = isRunning.get()

    /** Returns true only if running AND engine/monitor coroutines are actually alive. */
    fun isHealthy(): Boolean {
        if (!isRunning.get()) return false
        return (engineJob?.isActive == true) && (monitorJob?.isActive == true)
    }
    fun isEnabled()  : Boolean = isEnabled.get()
    fun isPaperMode(): Boolean = isPaperMode.get()
    fun isLiveMode() : Boolean = !isPaperMode.get()

    // V5.9.5: In paper mode, read from shared FluidLearning pool (same as main AATE)
    fun getBalance()          : Double = getEffectiveBalance()
    fun getEffectiveBalance() : Double = if (isPaperMode.get())
        com.lifecyclebot.engine.BotService.status.paperWalletSol
        else liveWalletBalance

    fun setBalance(bal: Double) {
        paperBalance = bal
        // V5.9.5: No-op — balance is owned by FluidLearning shared pool
    }
    fun setEnabled(enabled: Boolean)   { isEnabled.set(enabled); ErrorLogger.info(TAG, "🪙 Enabled: $enabled") }
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "🪙 Mode switched to ${if (live) "🔴 LIVE" else "📄 PAPER"}")
        if (live) {
            // Sync wallet balance immediately on mode switch
            try {
                val sol = WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (sol > 0.0) updateLiveBalance(sol)
            } catch (_: Exception) {}
        }
    }
    fun updateLiveBalance(sol: Double) { liveWalletBalance = sol }

    /** Sync paper wallet balance from BotService (consistent across all traders) */
    fun setPaperBalance(sol: Double) {
        if (isPaperMode.get() && sol > 0.0) paperBalance = sol
    }

    /**
     * Returns ALL positions (open + closed) so the Positions tab can show win rate, avg hold,
     * and closed trade history. Open positions have closeTime == null; closed have closeTime set.
     */
    fun getAllPositions()      : List<AltPosition> = positions.values.toList() + closedPositions.toList()
    fun getOpenPositions()     : List<AltPosition> = positions.values.toList()
    fun getClosedPositions()   : List<AltPosition> = closedPositions.toList()
    fun getSpotPositions()    : List<AltPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<AltPosition> = leveragePositions.values.toList()
    fun hasPosition(market: PerpsMarket): Boolean = positions.values.any { it.market == market }

    /** V5.9.85: Manual close for Markets UI. */
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

    fun getTotalTrades(): Int    = totalTrades.get()
    fun getWinCount(): Int       = winningTrades.get()
    fun getWinRate(): Double {
        val total = totalTrades.get()
        return if (total > 0) winningTrades.get().toDouble() / total * 100 else 0.0
    }
    fun getTotalPnlSol(): Double = totalPnlSol
    fun getInitialBalance(): Double = if (initialBalance > 0.0) initialBalance else paperBalance

    fun getUnrealizedPnlSol(): Double = positions.values.sumOf { it.getPnlSol() }
    fun getUnrealizedPnlPct(): Double {
        val bal = getEffectiveBalance()
        return if (bal > 0) getUnrealizedPnlSol() / bal * 100 else 0.0
    }

    fun getStats(): Map<String, Any> = mapOf(
        "totalTrades"    to totalTrades.get(),
        "winningTrades"  to winningTrades.get(),
        "losingTrades"   to losingTrades.get(),
        "winRate"        to getWinRate(),
        "totalPnlSol"    to totalPnlSol,
        "openPositions"  to positions.size,
        "paperBalance"   to paperBalance,
        "isLiveMode"     to !isPaperMode.get(),
        "learningPhase"  to getPhaseLabel()
    )

    private fun getPhaseLabel(): String {
        val trades = FluidLearningAI.getMarketsTradeCount()
        val wr     = getWinRate()
        return when {
            trades < 500  -> "📚 BOOTSTRAP"
            trades < 1500 -> "🧠 LEARNING"
            trades < 3000 -> "🔬 VALIDATING"
            trades < 5000 -> "⚡ MATURING"
            wr >= 52.0    -> "✅ READY"
            else          -> "⚡ MATURING"
        }
    }

    /** Whether this trader has met all requirements to go live */
    fun isLiveReady(): Boolean = FluidLearningAI.getMarketsTradeCount() >= 5000 && getWinRate() >= 52.0

    /** V5.9.3: Called from MAA when user taps SPOT/LEVERAGE toggle */
    fun setPreferLeverage(lev: Boolean) {
        preferLeverage.set(lev)
        ErrorLogger.info(TAG, "🪙 Mode → ${if (lev) "LEVERAGE (${DEFAULT_LEVERAGE.toInt()}x)" else "SPOT"}")
    }
    fun isPreferLeverage(): Boolean = preferLeverage.get()

    private fun Double.fmt(d: Int): String = "%.${d}f".format(this)
}



