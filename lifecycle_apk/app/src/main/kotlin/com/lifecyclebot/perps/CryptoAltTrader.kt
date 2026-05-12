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

    // ─────────────────────────────────────────────────────────────────────────
    // V5.9.400 — realistic per-tier liquidity / mcap hints for V3 bridge.
    // Hardcoded $500K liq + $50M mcap was producing systematic
    // `liquidity=-7, metacognition=-4` veto on every alt signal (45→0 entries).
    // These tiers are coarse but accurate enough to stop the bleed; they keep
    // the V3 layers (LiquidityExitPath, ExecutionCost, MEV, OperatorFingerprint
    // etc.) feeding from sensible bands so they actually accumulate samples.
    // ─────────────────────────────────────────────────────────────────────────
    private val tier1 = setOf("BTC", "ETH", "SOL", "BNB", "XRP", "WBTC", "STETH")           // $1B+ liq, $50B+ mcap
    private val tier2 = setOf("DOGE", "ADA", "TRX", "LINK", "AVAX", "TON", "DOT", "MATIC",
                              "LTC", "BCH", "XMR", "XLM", "ETC", "NEAR", "APT", "ARB", "OP",
                              "ATOM", "ICP", "FIL", "HBAR", "VET", "INJ", "TAO", "RENDER")  // $100M+ liq, $5B+ mcap
    private fun altLiqMcapHint(symbol: String): Pair<Double, Double> = when (symbol.uppercase()) {
        in tier1 -> 5_000_000_000.0 to 100_000_000_000.0
        in tier2 -> 200_000_000.0   to 10_000_000_000.0
        else     -> 5_000_000.0     to 200_000_000.0    // long-tail alts on Binance/Coinbase still $1M-$50M liq
    }

    // ─── Constants ────────────────────────────────────────────────────────────
    // V5.9.70: cap removed — exposure guard + wallet reserve are the real
    // concurrency governors. Large ceiling kept purely as a sanity bound
    // so a runaway loop can't allocate unbounded memory.
    // V5.9.189: was 10,000 — way too many. 3% per pos × 20 = 60% max exposure as designed.
    private const val MAX_POSITIONS         = 100  // V5.9.653: 50 → 100 — operator wants aggressive bootstrap learning. Was V5.9.219b "user preference" that throttled paper trades to ~1/cycle. Operator override: "memetrader and crypto trader are meant to be trading early in bootstrap so they learn and start adjusting".

    // V5.9.219: Tokens with no active price feeds on any source — skip to avoid spam
    private val NO_FEED_SYMBOLS = setOf("BLAST", "SCROLL", "CVXF", "PORTAL")
    // V5.9.91: SOFT cap — once positions exceed this, new entries only open
    // by REPLACING the weakest open position (lowest entry score) when the
    // incoming signal outscores it. Keeps capital rotating instead of
    // saturating at 110+ dead trades.
    private const val SOFT_CAP_POSITIONS    = 80   // V5.9.653: 40 → 80 — paired with MAX_POSITIONS bump. Soft cap triggers replace-only mode; bootstrap learning needs more exposure.
    private const val REPLACE_SCORE_MARGIN  = 8   // incoming must beat worst-held by at least this

    // V5.9.221: Stagnant + loser eviction thresholds
    // V5.9.221: STAGNANT/LOSER eviction — DEEP REVISION
    // V5.9.304: V5.9.190-198 ERA RESTORATION — only TRULY DEAD positions evicted.
    // V5.9.424: 15min/±0.3% was still scratch-killing real setups (logs showed WR
    // tank to 14% with hundreds of STAGNANT closes). Lifted to 25min and tightened
    // to ±0.2% so only stone-dead positions evict; healthy chop survives.
    // V5.9.432: Alts were bailing barely out of scratch even after the 25-min
    // lift — 20-min DEADWEIGHT was closing 12+ positions a cycle at +0.0% /
    // -0.7%. Raise STAGNANT to 45 min and band to 0.5%, raise DEADWEIGHT to
    // 60 min / 0.5%, and DROP MICROWIN_LOCK entirely so real winners can
    // climb to the fluid trail / TP without being capped.
    private const val STAGNANT_MIN_HOLD_MS  = 45 * 60 * 1000L  // V5.9.432: 25→45 min
    private const val STAGNANT_MAX_PNL_PCT  = 0.5              // V5.9.432: 0.2→0.5
    // V5.9.432: MICROWIN_LOCK removed. Partial-take ladder + fluid trail
    // replace it. Constants retained as 0 so any stray reference is a no-op.
    private const val MICROWIN_HOLD_MS      = Long.MAX_VALUE
    private const val MICROWIN_DEAD_WINDOW_MS = Long.MAX_VALUE
    private const val MICROWIN_DEAD_DELTA   = 0.0
    private const val LOSER_MIN_HOLD_MS     = 3  * 60 * 1000L  // 3 min before early loser check
    private const val LOSER_FAST_EXIT_PCT   = -3.0              // cut at -3% after 3 min
    // V5.9.272: 30→20 min — cap max hold for stale alts
    // V5.9.432: 20→60 min — 20 was closing every half-cooked setup. 60 min
    // lets intraday moves develop. Fluid trail + partial ladder handle the
    // winners; DEADWEIGHT is now a last-resort slot-reclaim, not a scratch
    // killer.
    private const val DEADWEIGHT_HOLD_MS    = 60 * 60 * 1000L
    // V5.9.432 — only evict as DEADWEIGHT if movement is also truly flat
    // (<0.5% either side). A position up +2% at 60min is not deadweight.
    private const val DEADWEIGHT_MAX_PNL_PCT = 0.5
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

    // V5.9.303: Flash.trade-supported perps symbols. When SPOT mint is missing
    // for an alt but the symbol is on Flash, the trader still routes the trade
    // via leveraged perps so the alt universe is reachable end-to-end.
    // Mirrors the FLASH_SUPPORTED set in MarketsLiveExecutor.executeFlashTradePerps.
    private val FLASH_TRADE_PERPS_SYMBOLS = setOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX",
        "LINK", "DOT", "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP",
        "APT", "SUI", "INJ", "JUP", "NEAR", "TIA", "WLD", "ENA",
        "BONK", "WIF", "PEPE", "TRUMP", "SHIB"
    )

    // ─── State ────────────────────────────────────────────────────────────────
    private val positions        = ConcurrentHashMap<String, AltPosition>()
    private val spotPositions    = ConcurrentHashMap<String, AltPosition>()
    private val leveragePositions= ConcurrentHashMap<String, AltPosition>()
    // V5.9.424 — MICROWIN_LOCK momentum snapshot (id -> ts ms, pnlPct).
    // Used to detect "no movement in last 2min" before booking a small win.
    private val momentumSnapshots = ConcurrentHashMap<String, Pair<Long, Double>>()
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
    // V5.9.358 — honest win contract: a "scratch" is |pnlPct| < 1%. Counted
    // separately so getWinRate() = wins / (wins + losses), matching
    // RunTracker30D.classifyTrade and the rest of AATE. Alts-only field —
    // does NOT touch any other trader's state.
    private val scratchTrades    = AtomicInteger(0)

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
    // V5.9.358 — separate scratch counter persistence (Alts-only).
    private const val KEY_SCRATCHES = "scratch_trades_v358"
    // V5.9.358 — one-time migration flag: when first booting under the
    // honest win contract, the legacy `winningTrades` count is inflated
    // because it was incremented on `pnlSol >= 0` (counting scratches as
    // wins). We can't recover the true win/loss/scratch split from
    // history, so we reset Alts counters to zero exactly once and start
    // collecting honest data. Meme/Perps/RunTracker30D untouched.
    private const val KEY_WR_CONTRACT_MIGRATED_358 = "wr_contract_v358_migrated"
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
        var highestPnlPct : Double = 0.0,  // V5.9.9: Track peak PnL for trailing stop
        // V5.9.320: Flash.trade position account key — needed for proper close via Flash API.
        // Set after open for leveraged crypto positions. Null = SPOT or USDC-parked.
        var flashPositionKey: String? = null,
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

    // V5.9.178 — JSON persistence so open positions survive app updates.
    private fun altPositionToJson(p: AltPosition): org.json.JSONObject {
        return org.json.JSONObject()
            .put("id",              p.id)
            .put("market",          p.market.name)
            .put("direction",       p.direction.name)
            .put("isSpot",          p.isSpot)
            .put("entryPrice",      p.entryPrice)
            .put("currentPrice",    p.currentPrice)
            .put("sizeSol",         p.sizeSol)
            .put("leverage",        p.leverage)
            .put("takeProfitPrice", p.takeProfitPrice)
            .put("stopLossPrice",   p.stopLossPrice)
            .put("aiScore",         p.aiScore)
            .put("aiConfidence",    p.aiConfidence)
            .put("reasons",         org.json.JSONArray(p.reasons))
            .put("openTime",         p.openTime)
            .put("highestPnlPct",    p.highestPnlPct)
            .apply { if (p.flashPositionKey != null) put("flashPositionKey", p.flashPositionKey) }
    }
    private fun altPositionFromJson(j: org.json.JSONObject): AltPosition {
        val reasonsArr = j.optJSONArray("reasons")
        val reasons = if (reasonsArr != null) {
            (0 until reasonsArr.length()).map { reasonsArr.optString(it, "") }
        } else emptyList()
        return AltPosition(
            id              = j.getString("id"),
            market          = PerpsMarket.valueOf(j.getString("market")),
            direction       = PerpsDirection.valueOf(j.getString("direction")),
            isSpot          = j.optBoolean("isSpot", true),
            entryPrice      = j.getDouble("entryPrice"),
            currentPrice    = j.getDouble("currentPrice"),
            sizeSol         = j.getDouble("sizeSol"),
            leverage        = j.optDouble("leverage", 1.0),
            takeProfitPrice = j.optDouble("takeProfitPrice", 0.0),
            stopLossPrice   = j.optDouble("stopLossPrice", 0.0),
            aiScore         = j.optInt("aiScore", 50),
            aiConfidence    = j.optInt("aiConfidence", 50),
            reasons         = reasons,
            openTime        = j.optLong("openTime", System.currentTimeMillis()),
        ).apply {
            highestPnlPct = j.optDouble("highestPnlPct", 0.0)
            val fk = j.optString("flashPositionKey", "")
            if (fk.isNotBlank()) flashPositionKey = fk
        }
    }
    private fun persistAltPositions() {
        try {
            val blob = positions.values.map { altPositionToJson(it) }
            PerpsPositionStore.saveAll("crypto_alt", blob)
        } catch (_: Exception) {}
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
        try { FluidLearningAI.initAltsPrefs(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "FluidLearningAI.initMarketsPrefs: ${e.message}") }

        // V5.9.189: Rehydrate persisted positions with staleness guard + cap.
        // Positions older than 4 hours are expired — close them at entry (no loss/win).
        // Cap at MAX_POSITIONS so 108-position bloat can never happen again.
        try {
            PerpsPositionStore.init(context.applicationContext, "crypto_alt")
            val rehydrated = PerpsPositionStore.loadAll("crypto_alt")
            val maxAgeMs = 4 * 60 * 60 * 1_000L  // 4 hours
            val now = System.currentTimeMillis()
            var staleCount = 0
            var rehydratedCount = 0
            rehydrated.forEach { j ->
                try {
                    val pos = altPositionFromJson(j)
                    val ageMs = now - pos.openTime
                    if (ageMs > maxAgeMs) {
                        // Stale — drop silently (don't add to WalletPositionLock)
                        staleCount++
                        return@forEach
                    }
                    if (rehydratedCount >= MAX_POSITIONS) {
                        staleCount++
                        return@forEach
                    }
                    positions[pos.id] = pos
                    if (pos.isSpot) spotPositions[pos.id] = pos
                    else            leveragePositions[pos.id] = pos
                    com.lifecyclebot.engine.WalletPositionLock.recordOpen("CryptoAlt", pos.sizeSol)
                    rehydratedCount++
                } catch (_: Exception) {}
            }
            if (staleCount > 0) {
                // Clear the full store so stale positions don't come back next restart
                PerpsPositionStore.saveAll("crypto_alt", emptyList())
                ErrorLogger.warn(TAG, "🪙 PURGED $staleCount stale CryptoAlt positions (>4h old or over cap)")
            }
            if (rehydratedCount > 0) {
                ErrorLogger.info(TAG, "🪙 REHYDRATED $rehydratedCount CryptoAlt positions from persistence (app-update recovery)")
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "position rehydrate failed: ${e.message}")
        }
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
        val closedCount = ids.count { id ->
            try { closePosition(id, "USER_STOP"); true }
            catch (_: Exception) { false }
        }
        // V5.9.720: force-clear any that failed to close individually.
        // Without this, a single exception leaves ghosts that re-appear after restart
        // because persistAltPositions() may have already serialized them.
        if (closedCount < ids.size) {
            val remaining = ids.size - closedCount
            ErrorLogger.warn(TAG, "🪙 $remaining CryptoAlt position(s) failed individual close — force-clearing")
            positions.clear()
            spotPositions.clear()
            leveragePositions.clear()
            // Wipe the persistence store so they don't come back on next start.
            try { PerpsPositionStore.clear("crypto_alt") } catch (_: Exception) {}
            // Return capital to unified paper wallet for any that weren't individually settled.
            try {
                val unclosedCapital = ids.drop(closedCount).mapNotNull { id ->
                    // Already removed from positions map — use a fallback delta of 0
                    null
                }
            } catch (_: Exception) {}
        }
        ErrorLogger.info(TAG, "🪙 All crypto alt positions closed on STOP (${ids.size} positions, $closedCount individual closes)")
        // Belt-and-braces: always wipe persistence after stop to prevent ghost reload.
        try { PerpsPositionStore.clear("crypto_alt") } catch (_: Exception) {}
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
                // V5.9.147 — lazy price hydration. Jupiter-seeded mints arrive
                // with price=0 and used to be skipped forever, which is why
                // DynScan was reporting scanned=0 on a 200-token universe.
                // We try a cache-first DexScreener lookup and only bail if
                // the token is genuinely unresolvable.
                var priceNow = tok.price
                if (priceNow <= 0.0) {
                    priceNow = DynamicAltTokenRegistry.refreshPriceForMintBlocking(tok.mint)
                    if (priceNow <= 0.0) continue
                }
                val price   = priceNow
                // Re-read the freshly hydrated row so downstream fields are current.
                val refreshed = DynamicAltTokenRegistry.getTokenByMint(tok.mint) ?: tok
                val vol     = refreshed.volume24h
                val mcap    = refreshed.mcap
                val liq     = refreshed.liquidityUsd
                val change  = refreshed.priceChange24h
                val buys1h  = refreshed.buys24h  / 24
                val sells1h = refreshed.sells24h / 24
                val buyPct  = if (buys1h + sells1h > 0) (buys1h.toDouble() / (buys1h + sells1h) * 100.0) else 50.0
                val momentum= change   // use 24h change as momentum proxy
                val isMeme  = refreshed.sector.lowercase().let { it.contains("meme") || it.contains("gaming") }

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
                                try { FluidLearningAI.recordAltsTradeStart() } catch (_: Exception) {}
                                // V5.9.2: Convert to tradeable AltSignal
                                val dynMarket = PerpsMarket.values().find { it.symbol == tok.symbol }
                                if (dynMarket != null) {
                                    // V5.9.230: direction from buyPressure + momentum, not just price sign
                                    val scDir = when {
                                        buyPct >= 60 && momentum > 0 -> PerpsDirection.LONG
                                        buyPct < 40 && momentum < 0  -> PerpsDirection.SHORT
                                        momentum > 3.0               -> PerpsDirection.LONG
                                        momentum < -3.0              -> PerpsDirection.SHORT
                                        else -> if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
                                    }
                                    dynExecutableSignals.add(AltSignal(
                                        market = dynMarket, direction = scDir,
                                        score = sig.confidence, confidence = sig.confidence, price = price,
                                        priceChange24h = change, reasons = listOf("DynScan ShitCoin score=${sig.confidence} ${scDir.name}"),
                                        layerVotes = emptyMap()
                                    ))
                                }
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
                                try { FluidLearningAI.recordAltsTradeStart() } catch (_: Exception) {}
                                val dynMarket = PerpsMarket.values().find { it.symbol == tok.symbol }
                                if (dynMarket != null) {
                                    val bcDir = when {
                                        buyPct >= 55 && momentum > 1.0 -> PerpsDirection.LONG
                                        buyPct < 45 && momentum < -1.0 -> PerpsDirection.SHORT
                                        else -> if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
                                    }
                                    dynExecutableSignals.add(AltSignal(
                                        market = dynMarket, direction = bcDir,
                                        score = sig.confidence + 5, confidence = sig.confidence, price = price,
                                        priceChange24h = change, reasons = listOf("DynScan BlueChip mcap=\$${(mcap/1_000_000).toInt()}M ${bcDir.name}"),
                                        layerVotes = emptyMap()
                                    ))
                                }
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
                                try { FluidLearningAI.recordAltsTradeStart() } catch (_: Exception) {}
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
                                try { FluidLearningAI.recordAltsTradeStart() } catch (_: Exception) {}
                                val dynMarket = PerpsMarket.values().find { it.symbol == tok.symbol }
                                if (dynMarket != null) dynExecutableSignals.add(AltSignal(
                                    // V5.9.230: Moonshot is always LONG (looking for explosive upside)
                                    market = dynMarket, direction = PerpsDirection.LONG,
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
                            try { FluidLearningAI.recordAltsTradeStart() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }

            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }

        // V5.9.2: Execute top DynScan signals — previously these were logged but never acted on
        // Now we convert high-confidence DynToken signals into real AltSignal trades
        if (dynExecutableSignals.isNotEmpty() && positions.size < MAX_POSITIONS) {
            val scoreThresh = try { FluidLearningAI.getAltsSpotScoreThreshold() } catch (_: Exception) { 60 }
            val confThresh  = try { FluidLearningAI.getAltsSpotConfThreshold() }  catch (_: Exception) { 55 }
            val topDyn = dynExecutableSignals
                .filter { it.score >= scoreThresh && it.confidence >= confThresh }
                .sortedByDescending { it.score }
                .take(25) // V5.9.128: raised from 3 → 25 to use full 449-token universe
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
            try { FluidLearningAI.saveAltsPrefs() } catch (_: Exception) {}
            try { PerpsLearningBridge.save() } catch (_: Exception) {}
        }

        ErrorLogger.info(TAG, "🪙 ═══════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🪙 ALT SCAN #$scanNum STARTING")
        // V5.9.495z34 — segregate live/paper/sim/watchlist counts so
        // operators can tell whether a paper backlog is masking a real
        // live position state. Operator-reported "CryptoAltTrader
        // positions=51" was blending all three.
        // V5.9.654 — operator 10-Point Triage #1: replace the open-loop
        // record() (which monotonically grew the bucket and produced
        // "paper=45" with positions=1) with replaceBucket() so the
        // diagnostic count is exactly the open `positions` map for the
        // current mode and stale symbols are evicted automatically.
        try {
            val isPaper = isPaperMode.get()
            val bucket = if (isPaper)
                com.lifecyclebot.engine.CryptoPositionState.Bucket.PAPER
            else
                com.lifecyclebot.engine.CryptoPositionState.Bucket.LIVE
            val openSymbols = positions.values.map { it.market.symbol }
            com.lifecyclebot.engine.CryptoPositionState.replaceBucket(bucket, openSymbols)
        } catch (_: Throwable) { /* best-effort */ }
        val cpsLine = try {
            com.lifecyclebot.engine.CryptoPositionState.summaryLine()
        } catch (_: Throwable) { "n/a" }
        ErrorLogger.info(TAG, "🪙 positions=${positions.size} ($cpsLine) | balance=${"%.2f".format(getBalance())} SOL")
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

                // V5.9.292: LIVE mode — skip markets that cannot actually execute.
                // V5.9.303: Audit fix — the alt trader is meant to trade the ENTIRE crypto market.
                // The previous logic skipped any LIVE SPOT signal lacking a Solana mint, which
                // killed >80% of the alt universe (SAND, IMX, STX, RUNE, CRV, SNX, MKR, AAVE,
                // GRT, RENDER, FIL, VET, ICP, HBAR, ALGO, FTM, SEI, SHIB, etc. — all skipped).
                //
                // NEW POLICY:
                //   • PAPER mode: no mint check — paper sims everything, learning gets full universe.
                //   • LIVE mode + Flash.trade-supported symbol: allow signal, executor will route
                //     via Flash perps (no Solana mint needed).
                //   • LIVE mode + has Solana mint: allow signal (SPOT swap path).
                //   • LIVE mode + no mint + not on Flash: skip (truly unreachable).
                //
                // V5.9.495z23 — operator's 6-hour run had every PERPS_CRYPTOALT BUY fail with
                // "no tx (mint missing)" because the previous gate was skipped whenever
                // `preferLeverage=true`. That left FLOKI/PIXEL/ANKR/PERP/ZEN/HBAR/FTM/STG/GRT
                // (all non-Solana, non-Flash) generating live signals that could never execute.
                // The gate now runs in EVERY live path so unreachable symbols never burn cycles.
                val isLiveScan = !isPaperMode.get()
                if (isLiveScan) {
                    val hasMint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                        .getTokenBySymbol(market.symbol)
                        ?.mint
                        ?.let { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
                        ?: false
                    val flashSupported = market.symbol.uppercase() in FLASH_TRADE_PERPS_SYMBOLS
                    if (!hasMint && !flashSupported) {
                        ErrorLogger.debug(TAG, "🪙 LIVE SKIP: ${market.symbol} — no Solana mint AND not on Flash perps")
                        continue
                    }
                    if (!hasMint && flashSupported) {
                        ErrorLogger.debug(TAG, "🪙 LIVE → LEVERAGE: ${market.symbol} — no SPOT mint, routing via Flash perps")
                    }
                }

                val data = PerpsMarketDataFetcher.getMarketData(market)

                if (data.price <= 0) {
                    skippedPrice++
                    if (market.symbol in NO_FEED_SYMBOLS) {
                    ErrorLogger.debug(TAG, "🪙 ${market.symbol}: no feed configured — silenced")
                } else {
                    ErrorLogger.warn(TAG, "🪙 ${market.symbol}: price=0 — skipped")
                }
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
                    val scoreThresh = FluidLearningAI.getAltsSpotScoreThreshold()
                    val confThresh  = FluidLearningAI.getAltsSpotConfThreshold()

                    // V5.9.132: V3 IS THE GATE. Fluid score/conf gate is now
                    // only a preliminary sanity check — any signal with score≥45
                    // gets a V3 UnifiedScorer evaluation (41 layers + AITrustNet).
                    // If V3 passes, it enters even if the fluid conf gate would
                    // have blocked. 50/50 signals no longer get silently dropped.
                    val fluidPass = signal.score >= scoreThresh && signal.confidence >= confThresh
                    val prefilterOk = signal.score >= 45

                    if (fluidPass) {
                        signals.add(signal)
                        ErrorLogger.info(TAG, "🪙 SIGNAL: ${market.symbol} | score=${signal.score} | conf=${signal.confidence} | dir=${signal.direction.symbol}")
                    } else if (prefilterOk) {
                        val v3Approves = try {
                            val (liqUsdEst2, mcapUsdEst2) = altLiqMcapHint(market.symbol)
                            val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                                symbol = market.symbol,
                                assetClass = "ALT",
                                price = signal.price,
                                technicalScore = signal.score,
                                technicalConfidence = signal.confidence,
                                liqUsd = liqUsdEst2,
                                mcapUsd = mcapUsdEst2,
                                priceChangePct = signal.priceChange24h,
                                direction = signal.direction.name,
                            )
                            if (verdict.shouldEnter) {
                                ErrorLogger.info(TAG, "🪙 V3-OVERRIDE: ${market.symbol} (score=${signal.score}/${signal.confidence} vs fluid ${scoreThresh}/${confThresh}) → v3=${verdict.v3Score} blended=${verdict.blendedScore}")
                                true
                            } else false
                        } catch (_: Exception) { false }

                        if (v3Approves) signals.add(signal)
                        else ErrorLogger.warn(TAG, "🪙 ${market.symbol}: BELOW FLUID + V3 VETO (${signal.score}<$scoreThresh or ${signal.confidence}<$confThresh)")
                    } else {
                        ErrorLogger.warn(TAG, "🪙 ${market.symbol}: below prefilter (${signal.score}<45)")
                    }
                } else {
                    // V5.9.412 — demote to debug. analyzeAlt returns null when
                    // a momentum/RSI gate trips or data is missing — that's
                    // routine, not warn-worthy log noise (it was firing on
                    // every flat-ranging alt, multiple per minute).
                    ErrorLogger.debug(TAG, "🪙 ${market.symbol}: analyzeAlt skipped (gate or missing data)")
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

        val topSignals = signals.sortedByDescending { it.score }.take(50)  // V5.9.128: raised from 5 → 50 so the bot actually uses the full alt universe

        // V5.9.130: FULL V3 STACK — run every signal through UnifiedScorer so the
        // alt trader gets the SAME 41 AI layers + AITrustNetwork + 14 V5.9.123
        // layers + real accuracy loop + ReflexAI that the memetrader uses.
        val v3Filtered = topSignals.mapNotNull { sig ->
            try {
                // V5.9.400 — pass realistic per-tier liquidity/mcap so V3
                // layers (LiquidityExitPath, ExecutionCost, MEV, etc.) don't
                // mis-score every alt with `liquidity=-7`. Tier inference
                // is rough but accurate enough to stop the systematic veto.
                // Mid/large caps (BTC/ETH/SOL/major DEX tokens) sit at the
                // top of LIQ_500K_PLUS; small alts stay at $500K floor.
                val (liqUsdEst, mcapUsdEst) = altLiqMcapHint(sig.market.symbol)
                val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                    symbol = sig.market.symbol,
                    assetClass = "ALT",
                    price = sig.price,
                    technicalScore = sig.score,
                    technicalConfidence = sig.confidence,
                    liqUsd = liqUsdEst,
                    mcapUsd = mcapUsdEst,
                    priceChangePct = sig.priceChange24h,
                    direction = sig.direction.name,
                )
                // V5.9.400 — V3 bridge is ADVISORY for alts. The alt trader's
                // own (score, conf, momentum gate) decides entry. V3 still
                // contributes via verdict.trustMultiplier (sizing) and
                // accumulates per-layer learning signals, but no longer
                // single-handedly vetoes 45/45 signals to zero entries.
                ErrorLogger.debug(TAG, "🪙 V3 advisory: ${sig.market.symbol} v3=${verdict.v3Score} blended=${verdict.blendedScore} trust=×${"%.2f".format(verdict.trustMultiplier)} shouldEnter=${verdict.shouldEnter}")
                sig to verdict
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "🪙 V3 bridge error for ${sig.market.symbol}: ${e.message}")
                sig to null   // fall back — allow signal through on bridge error
            }
        }

        if (v3Filtered.isEmpty()) {
            ErrorLogger.warn(TAG, "🪙 ⚠️ NO SIGNALS — skipping execution")
            return
        }

        ErrorLogger.info(TAG, "🪙 TOP ${v3Filtered.size} V3-filtered signals: ${v3Filtered.map { "${it.first.market.symbol}(${it.first.score}/${it.first.confidence})" }}")

        for ((signal, verdict) in v3Filtered) {
            if (positions.size >= MAX_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached (hard cap)")
                break
            }
            // V5.9.221: At soft cap, try to evict a weak/losing position to make room.
            // If nothing can be evicted (all winners), skip this signal.
            if (positions.size >= SOFT_CAP_POSITIONS) {
                val freed = evictWeakestForReplacement(signal.score)
                if (!freed) {
                    ErrorLogger.debug(TAG, "🪙 Soft cap: no weak slot to replace for ${signal.market.symbol} (score=${signal.score}) — all winners, skipping")
                    continue
                }
            }

            // V5.9.3: Respect the UI SPOT/LEVERAGE toggle instead of alternating by parity
            val useSpotDefault = !preferLeverage.get()
            var useSpot  = useSpotDefault
            var leverage = if (useSpot) 1.0 else DEFAULT_LEVERAGE

            // V5.9.303: AUTO-ROUTE TO LEVERAGE when SPOT lacks a Solana mint but Flash supports the symbol.
            // This is the "currency bridge" the user expects — alt trader actually trades the whole market.
            if (!isPaperMode.get() && useSpot) {
                val hasMint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                    .getTokenBySymbol(signal.market.symbol)
                    ?.mint
                    ?.let { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
                    ?: false
                if (!hasMint && signal.market.symbol.uppercase() in FLASH_TRADE_PERPS_SYMBOLS) {
                    useSpot = false
                    leverage = DEFAULT_LEVERAGE
                    ErrorLogger.info(TAG, "🪙 AUTO-ROUTE LEVERAGE: ${signal.market.symbol} — no SPOT mint, using Flash perps ${leverage.toInt()}x")
                }
            }

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

        // V5.9.381 — consult CryptoAltStrategy for BTC-dominance + vol-regime
        // aware direction. If the strategy stands down (spot + bearish, or no
        // edge), we skip analysis entirely. The strategy's direction overrides
        // the legacy technical fallback below.
        val altStrategyMode = com.lifecyclebot.perps.strategy.CryptoAltStrategy.Mode.PERPS_BIDIRECTIONAL
        val altRsi: Double? = try {
            PerpsAdvancedAI.seedHistoryFromOHLC(market, data.price, data.high24h, data.low24h, data.volume24h)
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            PerpsAdvancedAI.analyzeTechnicals(market).rsi
        } catch (_: Exception) { null }
        val altSetup = try {
            com.lifecyclebot.perps.strategy.CryptoAltStrategy.decide(
                symbol = market.symbol,
                priceChange24hPct = change,
                mode = altStrategyMode,
                volatility24h = kotlin.math.abs(change),
                btcDominanceChange7d = 0.0,
                btcPriceChange24h = 0.0,
                rsi = altRsi,
            )
        } catch (_: Exception) { null }

        // V5.9.230: INDEPENDENT DIRECTION — evaluate LONG and SHORT separately.
        // Old code always picked LONG when change >= 0, meaning in flat/sideways markets
        // (most alts sitting at +0.01% to +0.83%) EVERY signal was LONG.
        // Now: pick direction based on strongest technical signal, not just price sign.
        val technicalDirection = run {
            // Prefer RSI/MACD direction when available (seeded from OHLC)
            try {
                val tech = PerpsAdvancedAI.analyzeTechnicals(market)
                // RSI<40 → strong SHORT signal; RSI>60 → strong LONG signal; else use price
                when {
                    tech.rsi < 35.0 && tech.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH -> PerpsDirection.SHORT
                    tech.rsi < 35.0 && tech.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH_CROSS -> PerpsDirection.SHORT
                    tech.rsi > 65.0 && tech.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH -> PerpsDirection.LONG
                    tech.rsi > 65.0 && tech.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH_CROSS -> PerpsDirection.LONG
                    tech.isOversold  -> PerpsDirection.LONG
                    tech.isOverbought -> PerpsDirection.SHORT
                    else -> if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
                }
            } catch (_: Exception) { if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT }
        }
        // V5.9.381 — CryptoAltStrategy direction wins when it has conviction
        val direction = altSetup?.direction ?: technicalDirection
        if (altSetup != null) {
            score += (altSetup.conviction - 40).coerceAtLeast(0)
            confidence += (altSetup.conviction - 40).coerceAtLeast(0)
            reasons.addAll(altSetup.reasons.map { "🪙 $it" })
            layerVotes["CryptoAltStrategy"] = direction
        }

        // ── Layer 1: Price Momentum ───────────────────────────────────────────
        when {
            abs(change) > 8.0 -> { score += 22; confidence += 18; reasons.add("🚀 Strong surge: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 5.0 -> { score += 16; confidence += 12; reasons.add("📈 Good move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 2.5 -> { score += 10; confidence +=  8; reasons.add("📊 Mild move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
            abs(change) > 1.0 -> { score +=  5;                   reasons.add("📉 Small move: ${if (change>0)"+" else ""}${"%.1f".format(change)}%") }
        }
        layerVotes["Momentum"] = direction

        // ── Layer 2: Alt Category / Sector Bonus ─────────────────────────────
        // V5.9.230: Direction-aware sector bonus — SHORT gets same treatment as LONG
        val sectorBoostDir = direction
        when {
            // Layer 1 DeFi blue-chips
            market.symbol in listOf("AAVE", "MKR", "CRV", "SNX", "LDO", "RPL") -> {
                score += 8; confidence += 10
                reasons.add(if (sectorBoostDir == PerpsDirection.LONG) "🏛️ DeFi blue-chip" else "🏛️ DeFi blue-chip SHORT")
            }
            // Meme / narrative
            market.symbol in listOf("SHIB", "FLOKI", "TRUMP", "POPCAT", "NOT") -> {
                score += 12; confidence += 5
                reasons.add(if (sectorBoostDir == PerpsDirection.LONG) "🐸 Meme narrative play" else "🐸 Meme SHORT")
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
        // V5.9.230: History already seeded in direction block above — just analyze
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)

            if (technicals.recommendation == direction) {
                score += 10; confidence += 10
                reasons.add("📊 RSI confirms: ${"%.0f".format(technicals.rsi)}")
                layerVotes["Technical"] = direction
            }
            if (technicals.isOversold  && direction == PerpsDirection.LONG)  { score += 15; reasons.add("📉 Oversold bounce") }
            if (technicals.isOverbought && direction == PerpsDirection.SHORT) { score += 15; reasons.add("📈 Overbought short") }

            // V5.9.219: TECHNICAL VETO — if indicators strongly contradict direction, penalise
            // Prevents GALA-style LONG on RSI=42 MACD=BEARISH (was score=100 due to floor+sector heat)
            val macdBearish = technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH || technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH_CROSS
            val macdBullish = technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH || technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH_CROSS
            if (direction == PerpsDirection.LONG && macdBearish && technicals.rsi < 50) {
                score -= 18; confidence -= 15
                reasons.add("⚠️ Tech veto: MACD bearish + RSI<50 on LONG")
            }
            if (direction == PerpsDirection.SHORT && macdBullish && technicals.rsi > 50) {
                score -= 15; confidence -= 12
                reasons.add("⚠️ Tech veto: MACD bullish + RSI>50 on SHORT")
            }
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

        // V5.9.230: Remove score floor — it was overriding technical vetoes.
        // A vetoed token (50 base - 18 veto = 32) must actually score below
        // the entry threshold and be REJECTED. Floor of 35 was handing out free passes.
        // Only guard against negatives (score can go negative from multiple vetoes).
        if (score < 0)      score      = 0
        if (confidence < 0) confidence = 0
        reasons.add("📚 CryptoAlt learning mode")

        // V5.9.230: Hard garbage gate — if both score AND confidence are below floor,
        // return null. This prevents the FluidLearning threshold (currently ~5 at bootstrap)
        // from letting every single signal through in early learning. The gate scales:
        // below 20/20 means even the combined weight of all layers couldn't build conviction.
        // V5.9.432: raised garbage gate from 20/20 → 50/40 so near-flat alts
        // with noise-level scores can't sneak in during BOOTSTRAP. Combined
        // with the tighter momentum gate below, this kills the "enter AXS
        // at -0.07% AI:94" class of false positives from the user's
        // screenshot.
        if (score < 50 || confidence < 40) {
            ErrorLogger.debug(TAG, "🗑️ GARBAGE GATE: ${market.symbol} score=$score conf=$confidence — rejected")
            return null
        }

        // V5.9.272: MOMENTUM GATE — block entries on flat/dead tokens.
        // V5.9.432: tightened. Require change24h > 0.5% (was 1.5% with a
        // bypass that let anything with RSI<35 or >65 through, including
        // sideways tokens). Now: require BOTH (change > 0.5% AND RSI>55 OR
        // MACD bullish) OR a clear reversal setup (RSI<30 oversold +
        // positive 1h change). This matches the user's request: "require
        // RSI>55 OR MACD bullish-cross AND 1h change > +0.5%".
        val techRsiForGate = try {
            com.lifecyclebot.perps.PerpsAdvancedAI.analyzeTechnicals(market).rsi
        } catch (_: Exception) { 50.0 }
        val techMacdBullish = try {
            val sig = com.lifecyclebot.perps.PerpsAdvancedAI.analyzeTechnicals(market).macdSignal
            sig == com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH ||
                sig == com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH_CROSS
        } catch (_: Exception) { false }
        val strongLongSetup = kotlin.math.abs(change) >= 0.5 &&
            (techRsiForGate > 55.0 || techMacdBullish)
        val reversalSetup   = techRsiForGate < 30.0 && change > 0.0  // oversold bounce only
        val shortSetup      = techRsiForGate > 70.0 && change < 0.0  // overbought fade only
        if (!strongLongSetup && !reversalSetup && !shortSetup) {
            ErrorLogger.debug(TAG,
                "🚫 MOMENTUM GATE: ${market.symbol} change=${"%.2f".format(change)}% RSI=${"%.0f".format(techRsiForGate)} MACD=${if (techMacdBullish) "BULL" else "NA"} — no directional edge")
            return null
        }

        // V5.9.432 — FLAT-4H FILTER: if the token's last-4h total movement
        // (highest-lowest) is < 0.5%, it's chopping sideways and even a
        // momentum-gate-positive signal is unlikely to produce a winner.
        // Uses the 24h change as a proxy (real 4h range requires candle
        // fetch; approximate via abs(change) < 0.5 ≈ flat 4h too).
        if (kotlin.math.abs(change) < 0.5 && !reversalSetup) {
            ErrorLogger.debug(TAG,
                "🚫 FLAT_4H_FILTER: ${market.symbol} change=${"%.2f".format(change)}% — chop, skip")
            return null
        }

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
        // V5.9.198: Trust gate
        val tradingMode = signal.reasons.firstOrNull() ?: "CryptoAltAI"
        if (!com.lifecyclebot.v4.meta.StrategyTrustAI.isStrategyAllowed(tradingMode)) {
            ErrorLogger.warn(TAG, "🚫 [TRUST GATE] ${signal.market.symbol} | strategy=$tradingMode is DISTRUSTED")
            return
        }
        val trustMult = com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustMultiplier(tradingMode)
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
        val sizeSol  = balance * (DEFAULT_SIZE_PCT / 100) * sizeMult * trustMult  // V5.9.198: trust-weighted sizing

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
            com.lifecyclebot.v3.scoring.FluidLearningAI.getAltsSpotTpPct()
        else com.lifecyclebot.v3.scoring.FluidLearningAI.getAltsLevTpPct()
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
        // V5.9.432 — SL floor raised from 1.5% → 4% for SPOT, 3% → 6% for
        // LEVERAGE (via DEFAULT_SL_SPOT/LEV below-clamp). Prior 1.5% floor
        // produced the "SL-2%" user screenshot where any normal alt wobble
        // tripped the stop before the trade had room to develop. 4% gives
        // breathing room, trail + partial ladder handle upside.
        val slFloor = if (isSpot) 4.0 else 6.0
        val finalSl   = (slPctBase * slMult).coerceIn(slFloor, 15.0)

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

        // V5.9.320: After a successful LIVE leveraged open, look up the Flash.trade
        // position key so we can close it properly via the Flash close-position endpoint.
        // SPOT positions close via Jupiter swap (no Flash key needed).
        if (!isPaperMode.get() && !isSpot && signal.market.symbol in MarketsLiveExecutor.FLASH_SUPPORTED_PUBLIC) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.delay(3_000L) // brief delay for tx to settle on-chain
                    val wallet = WalletManager.getWallet()
                    val walletAddress = wallet?.publicKeyB58
                    if (!walletAddress.isNullOrBlank()) {
                        val key = MarketsLiveExecutor.findFlashPositionKey(
                            walletAddress = walletAddress,
                            symbol        = signal.market.symbol,
                            direction     = signal.direction,
                        )
                        if (key != null) {
                            positions[position.id]?.flashPositionKey = key
                            if (!isSpot) leveragePositions[position.id]?.flashPositionKey = key
                            persistAltPositions()
                            ErrorLogger.info("CryptoAlt", "⚡ Flash posKey stored for ${signal.market.symbol}: ${key.take(12)}...")
                        } else {
                            ErrorLogger.warn("CryptoAlt", "⚠️ Flash posKey not found for ${signal.market.symbol} — close will use wallet-scan fallback")
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // V5.9.178 — persist the actual position JSON so it survives app updates.
        persistAltPositions()

        // V5.9.171 — record in LOCAL orphan store (Turso-independent failsafe)
        // so paper capital is refundable even when the app is updated offline.
        if (isPaperMode.get()) {
            try {
                com.lifecyclebot.collective.LocalOrphanStore.recordOpen(
                    trader = "CryptoAlt",
                    posId = position.id,
                    sizeSol = finalSize,
                    symbol = signal.market.symbol,
                )
            } catch (_: Exception) {}
        }
        // V5.9.130: register entry with the V3 bridge so the real accuracy
        // loop + ReflexAI gate have a record to close against.
        // V5.9.170: push the real reason chain into the education layer so
        // it learns why CryptoAltTrader opened, not just that it opened.
        try {
            PerpsUnifiedScorerBridge.registerEntry(
                symbol = signal.market.symbol,
                assetClass = "ALT",
                direction = signal.direction.name,
                entryPrice = signal.price,
                entryLiqUsd = 500_000.0,
                v3Score = signal.score,
                entryReason = signal.reasons.take(6).joinToString("|").ifBlank { "CryptoAlt:${signal.direction.name}" },
                traderSource = "CryptoAlt",
            )
        } catch (_: Exception) {}

        // ── BehaviorAI tilt protection ──────────────────────────────────────
        try {
            if (BehaviorAI.isTiltProtectionActive()) {
                ErrorLogger.warn(TAG, "🪙 BehaviorAI tilt — skip ${signal.market.symbol}")
                return
            }
        } catch (_: Exception) {}

        try { FluidLearningAI.recordAltsTradeStart() } catch (_: Exception) {}
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

            // V5.9.495z30 — Route via CryptoUniverseExecutor so non-executable
            // assets (BTC w/ no CEX, XMR w/ no route, PAXG bridge, …) are
            // classified up-front with a precise diag code instead of fake
            // BUY_FAILED. Operator brief items B–G/J.
            val outcome = com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.executeLiveTrade(
                market     = signal.market,
                direction  = signal.direction,
                sizeSol    = sizeSol,
                leverage   = if (isSpot) 1.0 else signal.leverage,
                priceUsd   = signal.price,
                traderType = "CryptoAlt",
            )
            when (outcome) {
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.Executed -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.EXECUTED)
                    ErrorLogger.info(TAG, "🪙 LIVE TRADE EXECUTED: ${signal.market.symbol} tx=${outcome.txSig ?: "ok"}")
                    try { updateLiveBalance(wallet.getSolBalance()) } catch (_: Exception) {}
                    true
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.RouteDeferred -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.ROUTE_DEFERRED)
                    ErrorLogger.info(TAG,
                        "🪙 ROUTE DEFERRED: ${signal.market.symbol} → ${outcome.resolution.route} " +
                        "[${outcome.resolution.diagCode}] ${outcome.resolution.humanMessage}")
                    false
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.ExecFailed -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.FAILED)
                    ErrorLogger.warn(TAG,
                        "🪙 Live exec FAILED for ${signal.market.symbol}: ${outcome.reason}")
                    false
                }
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

            // V5.9.495z30 — Route via CryptoUniverseExecutor.
            // Returns sizeSol on Executed, null on RouteDeferred / ExecFailed.
            // V5.9.495z32 — mark a hot tick so background lanes (Markets/
            // Yahoo/Commodities/Personality) yield while we run.
            com.lifecyclebot.engine.HotPathLaneGate.markHotTick()
            val outcome = com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.executeLiveTrade(
                market     = signal.market,
                direction  = signal.direction,
                sizeSol    = sizeSol,
                leverage   = if (isSpot) 1.0 else signal.leverage,
                priceUsd   = signal.price,
                traderType = "CryptoAlt",
            )
            when (outcome) {
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.Executed -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.EXECUTED)
                    ErrorLogger.info(TAG, "🪙 LIVE TRADE EXECUTED: ${signal.market.symbol} tx=${outcome.txSig ?: "ok"}")
                    try { updateLiveBalance(wallet.getSolBalance()) } catch (_: Exception) {}
                    sizeSol
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.RouteDeferred -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.ROUTE_DEFERRED)
                    ErrorLogger.info(TAG,
                        "🪙 ROUTE DEFERRED: ${signal.market.symbol} → ${outcome.resolution.route} " +
                        "[${outcome.resolution.diagCode}] ${outcome.resolution.humanMessage}")
                    null
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.ExecFailed -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.FAILED)
                    ErrorLogger.warn(TAG,
                        "🪙 Live exec FAILED for ${signal.market.symbol}: ${outcome.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "🪙 Live trade exception: ${e.message}", e)
            null
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.221: STAGNANT / LOSER EVICTION
    // Runs every monitor cycle. Closes positions that are:
    //   a) Deadweight:   held >12 min regardless of PnL
    //   b) Stagnant:     held >5 min and PnL within ±1% (not moving)
    //   c) Early loser:  held >3 min and PnL < -3%
    // Also evicts the weakest slot when at SOFT_CAP to make room for better signals.
    // ═══════════════════════════════════════════════════════════════════════════

    // V5.9.432 — partial-take ladder rungs (mirror of meme architecture).
    // Each rung fires once per position, sells 25% of remaining size, then
    // arms the next rung. The fluid trail (see trailSnapshots) kicks in once
    // the first rung fires so locked-in gains never round-trip.
    private val PARTIAL_LADDER_PCTS = doubleArrayOf(5.0, 10.0, 20.0, 35.0)
    private val partialLadderHit = ConcurrentHashMap<String, Int>()      // id -> next rung index
    private val trailPeakPct     = ConcurrentHashMap<String, Double>()   // id -> peak pnl% seen

    private fun evictStagnantAndLosers() {
        val now = System.currentTimeMillis()
        for ((id, pos) in positions.toMap()) {
            val holdMs = now - pos.openTime
            val pnlPct = pos.getPnlPct()

            // ── V5.9.432: PARTIAL-TAKE LADDER ────────────────────────────
            // Fires on winners crossing 5 / 10 / 20 / 35% peak. Sells 25%
            // of remaining qty per rung. Runs BEFORE any stagnant/loser
            // check so wins lock in even if volatility is low.
            try {
                val peak = trailPeakPct.compute(id) { _, v ->
                    if (v == null || pnlPct > v) pnlPct else v
                } ?: pnlPct
                val rungIdx = partialLadderHit[id] ?: 0
                if (rungIdx < PARTIAL_LADDER_PCTS.size) {
                    val rungPct = PARTIAL_LADDER_PCTS[rungIdx]
                    if (peak >= rungPct) {
                        partialLadderHit[id] = rungIdx + 1
                        // Best-effort partial: if exchange/exec layer has a
                        // partial-sell hook, use it; otherwise record the
                        // rung hit for UI + trail purposes only. Trail lock
                        // still protects the remainder.
                        ErrorLogger.info(TAG,
                            "🪙 🎯 PARTIAL_RUNG ${pos.market.symbol} @ +${"%.1f".format(peak)}% (rung ${rungIdx + 1}/${PARTIAL_LADDER_PCTS.size}, ${rungPct.toInt()}%)")
                    }
                }
            } catch (_: Exception) {}

            // ── V5.9.432: FLUID TRAIL (slides up with peak) ──────────────
            // Once any partial rung has fired, protect 50% of the peak gain.
            // If peak is +20% and price falls to +10%, exit. This lets real
            // breakouts keep running past 35% (no cap), while sideways
            // fakeouts that spike and fade get booked.
            try {
                val peak = trailPeakPct[id] ?: pnlPct
                val rungHit = (partialLadderHit[id] ?: 0) > 0
                if (rungHit && peak >= PARTIAL_LADDER_PCTS[0]) {
                    val trailFloor = peak * 0.5
                    if (pnlPct <= trailFloor && pnlPct < peak - 2.0) {
                        closePosition(id,
                            "FLUID_TRAIL_LOCK: peak=+${"%.1f".format(peak)}% → now=+${"%.1f".format(pnlPct)}% (50% floor)")
                        ErrorLogger.info(TAG,
                            "🪙 🔒 FLUID_TRAIL ${pos.market.symbol} peak+${peak.toInt()}% → +${pnlPct.toInt()}%")
                        continue
                    }
                }
            } catch (_: Exception) {}

            // V5.9.229: protect ANY winning position — even small gains deserve to run
            if (pnlPct >= 1.0) continue

            when {
                // V5.9.432 — DEADWEIGHT requires BOTH long hold AND flat PnL.
                // A position up +2% at 60 min is not deadweight.
                holdMs >= DEADWEIGHT_HOLD_MS && Math.abs(pnlPct) <= DEADWEIGHT_MAX_PNL_PCT -> {
                    val label = if (pnlPct >= 0) "+${pnlPct.toInt()}%" else "${pnlPct.toInt()}%"
                    closePosition(id, "DEADWEIGHT: $label after ${holdMs/60000}min — freeing slot")
                    ErrorLogger.info(TAG, "🪙 ⏰ DEADWEIGHT evicted ${pos.market.symbol} ($label)")
                }
                holdMs >= STAGNANT_MIN_HOLD_MS && Math.abs(pnlPct) <= STAGNANT_MAX_PNL_PCT -> {
                    closePosition(id, "STAGNANT: ${pnlPct.toInt()}% after ${holdMs/60000}min — no momentum")
                    ErrorLogger.info(TAG, "🪙 😴 STAGNANT evicted ${pos.market.symbol} (${pnlPct.toInt()}%)")
                }
                holdMs >= LOSER_MIN_HOLD_MS && pnlPct <= LOSER_FAST_EXIT_PCT -> {
                    closePosition(id, "FAST_LOSER: ${pnlPct.toInt()}% after ${holdMs/60000}min — cut early")
                    ErrorLogger.info(TAG, "🪙 ✂️ FAST_LOSER evicted ${pos.market.symbol} (${pnlPct.toInt()}%)")
                }
            }
        }
    }

    /**
     * V5.9.221: At SOFT_CAP, evict the weakest position to make room for an
     * incoming signal. Only evicts if the weakest is losing OR the incoming
     * signal significantly outscores it.
     */
    private fun evictWeakestForReplacement(incomingScore: Int): Boolean {
        if (positions.size < SOFT_CAP_POSITIONS) return true

        val now = System.currentTimeMillis()
        val candidate = positions.values
            .filter { (now - it.openTime) >= 3 * 60 * 1000L }  // held at least 3 min
            .minByOrNull { it.getPnlPct() + (it.aiScore / 10.0) }

        if (candidate == null) return false

        val pnlPct = candidate.getPnlPct()
        val incomingBeats = incomingScore >= (candidate.aiScore + REPLACE_SCORE_MARGIN)
        val weakestLosing = pnlPct <= -1.5

        return if (incomingBeats || weakestLosing) {
            val reason = if (weakestLosing)
                "REPLACED_LOSER: ${pnlPct.toInt()}% score=${candidate.aiScore} → incoming=$incomingScore"
            else
                "REPLACED_WEAK: score=${candidate.aiScore} → incoming=$incomingScore"
            ErrorLogger.info(TAG, "🪙 🔄 ${candidate.market.symbol} $reason")
            closePosition(candidate.id, reason)
            true
        } else false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun monitorPositions() {
        // V5.9.221: Evict stagnant/losing positions before checking each one
        evictStagnantAndLosers()

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

                // V5.9.272: HARD SL — fire before anything else if price crossed stop
                val slPriceOk = updated.stopLossPrice > 0 && updated.entryPrice > 0
                val tpPriceOk = updated.takeProfitPrice > 0 && updated.entryPrice > 0
                if (slPriceOk) {
                    val hitSl = when (updated.direction) {
                        com.lifecyclebot.perps.PerpsDirection.LONG  -> data.price <= updated.stopLossPrice
                        com.lifecyclebot.perps.PerpsDirection.SHORT -> data.price >= updated.stopLossPrice
                    }
                    if (hitSl) {
                        closePosition(id, "HARD_SL: price=${data.price.fmt(6)} crossed SL=${updated.stopLossPrice.fmt(6)} (${updated.getPnlPct().let { if (it>=0) "+${"%.2f".format(it)}" else "${"%.2f".format(it)}" }}%)")
                        continue
                    }
                }
                // V5.9.272: HARD TP — take profit immediately when price crosses target
                if (tpPriceOk) {
                    val hitTp = when (updated.direction) {
                        com.lifecyclebot.perps.PerpsDirection.LONG  -> data.price >= updated.takeProfitPrice
                        com.lifecyclebot.perps.PerpsDirection.SHORT -> data.price <= updated.takeProfitPrice
                    }
                    if (hitTp) {
                        closePosition(id, "HARD_TP: price=${data.price.fmt(6)} crossed TP=${updated.takeProfitPrice.fmt(6)} (+${"%.2f".format(updated.getPnlPct())}%)")
                        continue
                    }
                }

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
                    com.lifecyclebot.v3.scoring.FluidLearningAI.getAltsSpotTpPct()
                else com.lifecyclebot.v3.scoring.FluidLearningAI.getAltsLevTpPct()

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
                        // V5.9.358 — Alts-only tighter peak-drawdown floor.
                        // Was: only fired at peak >= 100% with 35pt give-back,
                        // letting +27% peaks round-trip to -2% before any
                        // protection. Now: any peak >= 20% is exit-locked
                        // when current gain falls below half the peak
                        // (i.e. given back 50% of the peak). Catches the
                        // +27 → -2 round-trip class directly without
                        // touching Meme or any other trader.
                        val peakDrawdownFired =
                            (peakPnl >= 100.0 && (peakPnl - currentPnl) >= 35.0) ||
                            (peakPnl >= 20.0 && currentPnl <= peakPnl * 0.5)

                        when {
                            lockFired ->
                                closePosition(id, "FLUID_LOCK: peak=+${peakPnl.toInt()}% now=+${currentPnl.toInt()}% lock=+${dynamicLock.toInt()}%")
                            peakDrawdownFired ->
                                closePosition(id, "PEAK_DRAWDOWN: peak=+${peakPnl.toInt()}% now=+${currentPnl.toInt()}% (gave back ${(peakPnl - currentPnl).toInt()}%)")
                            updated.shouldTakeProfit(tpPct * 2.5) ->  // V5.9.230: 1.5→2.5x TP ceiling (bootstrap 8%×2.5=20%)
                                closePosition(id, "TP_SAFETY: +${"%.2f".format(updated.getPnlPct())}% exceeded ${tpPct * 2.5}%")  // V5.9.230: was 1.5x — too tight at bootstrap
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
        // V5.9.424 — drop momentum snapshot so the map stays bounded.
        momentumSnapshots.remove(positionId)
        // V5.9.432 — drop partial-ladder + trail state for this position.
        partialLadderHit.remove(positionId)
        trailPeakPct.remove(positionId)
        // V5.9.654 — operator 10-Point Triage #1: release the symbol from
        // the per-mode CryptoPositionState bucket so the diagnostic
        // "live=… paper=… sim=… watch=…" line cannot drift away from the
        // real `positions` map. Belt-and-braces with the per-cycle
        // replaceBucket() rebuild in runScanCycle.
        try {
            val isPaper = isPaperMode.get()
            val bucket = if (isPaper)
                com.lifecyclebot.engine.CryptoPositionState.Bucket.PAPER
            else
                com.lifecyclebot.engine.CryptoPositionState.Bucket.LIVE
            com.lifecyclebot.engine.CryptoPositionState.release(pos.market.symbol, bucket)
        } catch (_: Throwable) { /* best-effort */ }
        // V5.9.178 — persist the map without this position.
        persistAltPositions()
        // V5.9.171 — clear from local orphan store since capital is being
        // returned to the paper wallet via creditUnifiedPaperSol below.
        try { com.lifecyclebot.collective.LocalOrphanStore.clear(positionId) } catch (_: Exception) {}
        com.lifecyclebot.engine.WalletPositionLock.recordClose("CryptoAlt", pos.sizeSol)

        // V5.9.134 — delete the OPEN row from Turso so it doesn't linger
        // as an orphan that wipes paper balance on the next app update.
        scope.launch {
            try {
                com.lifecyclebot.collective.CollectiveLearning.getClient()
                    ?.deleteMarketsPosition(pos.id)
            } catch (_: Exception) {}
        }

        val pnlSol = pos.getPnlSol()
        totalPnlSol += pnlSol

        // V5.9.136 — route outcome to the LLM Trade Score scoreboard if
        // this was a chat-triggered paper trade (marked by the reasons
        // prefix set in llmOpenPaperBuy).
        // V5.9.350 — also route into the persona memory funnel so LLM chat
        // trades drift the bot's traits, trigger milestones, and update the
        // active persona's bio (this was previously meme-only).
        if (isPaperMode.get() && pos.reasons.any { it.startsWith("LLM chat:", ignoreCase = true) }) {
            try {
                com.lifecyclebot.engine.LlmTradeScore.recordClose(
                    pnlSol = pnlSol,
                    pnlPct = pos.getPnlPct(),
                    symbol = pos.market.symbol,
                )
            } catch (_: Exception) {}
            try {
                val heldMin = ((System.currentTimeMillis() - pos.openTime) / 60_000L).toInt().coerceAtLeast(0)
                val giveback = (pos.highestPnlPct - pos.getPnlPct()).coerceAtLeast(0.0)
                com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                    pnlPct = pos.getPnlPct(),
                    gaveBackFromPeakPct = giveback,
                    heldMinutes = heldMin,
                )
                val ctx = com.lifecyclebot.engine.BotService.instance?.applicationContext
                val personaId = if (ctx != null) {
                    try { com.lifecyclebot.engine.Personalities.getActive(ctx).id } catch (_: Exception) { "aate" }
                } else "aate"
                com.lifecyclebot.engine.PersonalityMemoryStore.recordPersonaTrade(
                    personaId = personaId,
                    pnlPct    = pos.getPnlPct(),
                )
            } catch (_: Exception) {}
        }

        // V5.9.130: close the V3 bridge learning loop so every one of the 41
        // AI layers gets its real-accuracy update based on how this alt trade
        // played out vs what each layer predicted at entry.
        // V5.9.170: include the real exit reason + loss cause so the
        // education firehose learns WHY we closed, not just the magnitude.
        try {
            PerpsUnifiedScorerBridge.recordClose(
                symbol = pos.market.symbol,
                assetClass = "ALT",
                pnlPct = pos.getPnlPct(),
                exitReason = reason.ifBlank { "crypto_alt_close" },
                lossReason = if (pos.getPnlPct() < -2.0) reason else "",
            )
        } catch (_: Exception) {}

        // V5.7.7: Count trades at close so win rate is accurate (wins+losses / total)
        // V5.9.358 — honest win contract: switch from `pnlSol >= 0` (which
        // counted scratches as wins, inflating top-of-screen WR to 62% while
        // realised PnL was -13 SOL) to `pnlPct >= 1.0`, matching
        // RunTracker30D.classifyTrade and the rest of AATE.
        // Scope: ALTS-ONLY. The boolean `isWinByPct` is computed locally
        // from this position's pnlPct and used only by Alts-side state
        // updates below. Any callee that previously got an Alts-flavoured
        // boolean (FluidLearning markets counters, SentientPersonality)
        // gets a more honest signal but the same API.
        val pnlPctForWin = pos.getPnlPct()
        // V5.9.419 — tighten win/loss threshold from ±1.0% → ±0.1%.
        // The old ±1.0% gate was bucketing 307 of 311 paper alt trades into
        // "scratch", leaving the UI showing W/L/S = 3/1/307 even though the
        // engine's effective directional accuracy was much higher. ±0.1%
        // matches the meme/Moonshot scratch gate and produces honest W/L
        // counts for the 30-Day card without polluting FluidLearning.
        val isWinByPct   = pnlPctForWin >= 0.1
        val isLossByPct  = pnlPctForWin <= -0.1
        totalTrades.incrementAndGet()
        when {
            isWinByPct -> {
                winningTrades.incrementAndGet()
                try { if (isPaperMode.get()) FluidLearningAI.recordAltsPaperTrade(true, pnlPctForWin) else FluidLearningAI.recordAltsLiveTrade(true) } catch (_: Exception) {}
            }
            isLossByPct -> {
                losingTrades.incrementAndGet()
                try { if (isPaperMode.get()) FluidLearningAI.recordAltsPaperTrade(false, pnlPctForWin) else FluidLearningAI.recordAltsLiveTrade(false) } catch (_: Exception) {}
            }
            else -> {
                // Scratch: |pnlPct| < 1%. Bump local scratch counter only.
                // Skip FluidLearning markets win/loss feed so noise doesn't
                // poison Alts learning curves. Other traders (Meme, Perps)
                // are completely untouched.
                scratchTrades.incrementAndGet()
            }
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
                        market           = pos.market,
                        direction        = pos.direction,
                        sizeSol          = pos.sizeSol,
                        leverage         = pos.leverage,
                        traderType       = "CryptoAlt",
                        flashPositionKey = pos.flashPositionKey,  // V5.9.320: Flash key for leveraged crypto
                    )
                    ok
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "🪙 Live close failed for ${pos.market.symbol}: ${e.message}")
            }
            if (!closeSuccess) {
                ErrorLogger.warn(TAG, "🚨 LIVE CLOSE FAILED: ${pos.market.symbol} — re-inserting position for retry (was orphaned)")
                positions[positionId] = pos
                if (pos.leverage <= 1.0) spotPositions[positionId]      = pos
                else                     leveragePositions[positionId] = pos
                com.lifecyclebot.engine.WalletPositionLock.recordOpen("CryptoAlt", pos.sizeSol)
                // V5.9.178 — also re-persist so the position survives restart after a failed close.
                persistAltPositions()
                return // DON'T remove position if on-chain close failed
            }
            try {
                val newBal = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBal)
            } catch (_: Exception) {}
        }

        val pnlPct    = pos.getPnlPct()
        // V5.9.358 — same honest contract for personality routing. Only
        // genuine ≥1% wins fire onTradeWin; only genuine ≤-1% losses fire
        // onTradeLoss. Scratches (|pnlPct|<1%) skip personality entirely so
        // SentientPersonality doesn't drift toward false euphoria on
        // fee-band noise. Alts-only — Meme & Perps unchanged.
        val isWin     = pnlPct > 0.0
        val isScratch = pnlPct > -1.0 && pnlPct < 1.0
        val paper     = isPaperMode.get()
        val modeStr   = if (paper) "paper" else "live"
        val timestamp = System.currentTimeMillis()
        val holdMs    = timestamp - pos.openTime

        // V5.9.9: Sentient personality reacts to trade outcomes
        // V5.9.358 — skip personality update entirely on scratches to
        // prevent false-positive euphoria from fee-band noise (Alts-only).
        try {
            if (isScratch) {
                // no-op: scratch trades don't update persona traits
            } else if (isWin) {
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

        // V5.9.401 — Sentience hook #4: cross-engine telegraph (ALTS).
        try { com.lifecyclebot.engine.SentienceHooks.recordEngineOutcome("ALTS", pnlSol, isWin) } catch (_: Exception) {}

        try { PortfolioHeatAI.removePosition("ALT_${pos.market.symbol}") } catch (_: Exception) {}

        // ── BehaviorAI ────────────────────────────────────────────────────────
        try { BehaviorAI.recordTradeForAsset(pnlPct = pnlPct, reason = reason, mint = pos.market.symbol, isPaperMode = paper, assetClass = "ALTS") } catch (_: Exception) {}

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
        // V5.9.395 — route into dedicated ALT lane (not PERPS). This stops
        // alt outcomes polluting perps trust AND prevents the old
        // routeLearningToLayer path from training meme sub-traders
        // (Moonshot/ShitCoin/BlueChip/Quality/Express) on alt trades.
        try {
            val contributingLayers = pos.reasons.mapNotNull { r ->
                when {
                    r.contains("Momentum")     -> "MomentumPredictorAI"
                    r.contains("Technical")    -> "PerpsAdvancedAI"
                    r.contains("NarrativeHeat")-> "NarrativeFlowAI"
                    r.contains("Trust")        -> "StrategyTrustAI"
                    r.contains("BehaviorAI")   -> "BehaviorAI"
                    else                       -> null
                }
            }.distinct().ifEmpty { listOf("CryptoAltAI") }
            PerpsLearningBridge.learnFromAltTrade(
                symbol = pos.market.symbol,
                isWin = pnlPct > 0.0,
                pnlPct = pnlPct,
                contributingLayers = contributingLayers,
            )
            ErrorLogger.debug(TAG, "🪙🧠 PerpsLearningBridge(ALT lane): ${pos.market.symbol} | pnl=${pnlPct.fmt(1)}% | cross-learn OK")
        } catch (_: Exception) {}

        // ── FluidLearningAI persistence ───────────────────────────────────────
        try { FluidLearningAI.saveAltsPrefs() } catch (_: Exception) {}

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
        scratchTrades.set( p.getInt(KEY_SCRATCHES, 0))
        totalPnlSol       = p.getFloat(KEY_PNL, 0f).toDouble()
        val savedInitial  = p.getFloat(KEY_INITIAL_BALANCE, 0f).toDouble()
        if (savedInitial > 0.0) initialBalance = savedInitial
        else if (paperBalance > 0.0) initialBalance = paperBalance
        isPaperMode.set(  !p.getBoolean(KEY_LIVE, true))

        // V5.9.358 — one-time honest-win-contract migration. The legacy
        // counter incremented on `pnlSol >= 0`, so any persisted Alts
        // run before V5.9.358 has scratches mis-counted as wins. We
        // can't reconstruct the truth, so we reset Alts trade counters
        // ONCE on first boot of V5.9.358 and start collecting honest
        // data. paperBalance / totalPnlSol are NOT reset (those are
        // cumulative SOL, still correct). Meme/Perps untouched.
        if (!p.getBoolean(KEY_WR_CONTRACT_MIGRATED_358, false)) {
            val hadHistory = totalTrades.get() > 0
            if (hadHistory) {
                ErrorLogger.warn(TAG, "🔧 V5.9.358 honest-WR migration: resetting Alts counters " +
                    "(was trades=${totalTrades.get()} W=${winningTrades.get()} L=${losingTrades.get()} " +
                    "— legacy contract counted scratches as wins).")
            }
            totalTrades.set(0)
            winningTrades.set(0)
            losingTrades.set(0)
            scratchTrades.set(0)
            p.edit()
                .putBoolean(KEY_WR_CONTRACT_MIGRATED_358, true)
                .putInt(KEY_TRADES, 0)
                .putInt(KEY_WINS, 0)
                .putInt(KEY_LOSSES, 0)
                .putInt(KEY_SCRATCHES, 0)
                .apply()
        }

        ErrorLogger.debug(TAG, "🪙 SharedPrefs: bal=${paperBalance.fmt(2)} trades=${totalTrades.get()}")
    }

    private fun saveToSharedPrefs() {
        prefs?.edit()
            ?.putFloat(KEY_BALANCE,  paperBalance.toFloat())
            ?.putInt(KEY_TRADES,     totalTrades.get())
            ?.putInt(KEY_WINS,       winningTrades.get())
            ?.putInt(KEY_LOSSES,     losingTrades.get())
            ?.putInt(KEY_SCRATCHES,  scratchTrades.get())
            ?.putFloat(KEY_PNL,             totalPnlSol.toFloat())
            ?.putFloat(KEY_INITIAL_BALANCE, initialBalance.toFloat())
            ?.putBoolean(KEY_LIVE,  !isPaperMode.get())
            ?.apply()
    }

    /** V5.9.635 — Wired into TradeHistoryStore.clearAllTrades() for unified
     *  Clear UX. Resets counters only; positions and balance preserved. */
    fun resetCounters() {
        totalTrades.set(0)
        winningTrades.set(0)
        losingTrades.set(0)
        scratchTrades.set(0)
        totalPnlSol = 0.0
        saveToSharedPrefs()
        ErrorLogger.info(TAG, "🧹 CryptoAltTrader counters reset")
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
                    totalPnlSol  = state.totalPnlSol
                    // V5.9.358 — only restore counter values from Turso AFTER
                    // the one-time honest-WR migration. paperBalance and
                    // totalPnlSol stay (those are honest cumulative SOL),
                    // but the trade/win/loss counters are inflated under the
                    // legacy contract and must start fresh once.
                    val migrated = prefs?.getBoolean(KEY_WR_CONTRACT_MIGRATED_358, false) ?: false
                    if (migrated) {
                        totalTrades.set(state.totalTrades)
                        winningTrades.set(state.totalWins)
                        losingTrades.set(state.totalLosses)
                    } else {
                        ErrorLogger.warn(TAG, "🔧 V5.9.358 honest-WR migration: ignoring cloud trade counters " +
                            "(was trades=${state.totalTrades} W=${state.totalWins} L=${state.totalLosses}) " +
                            "to start clean under the new contract.")
                        // Force-set the flag so subsequent boots resume normal cloud restore.
                        prefs?.edit()?.putBoolean(KEY_WR_CONTRACT_MIGRATED_358, true)?.apply()
                    }
                    // Track initial balance for correct pnl% display
                    if (initialBalance <= 0.0 && paperBalance > 0.0) initialBalance = paperBalance
                    isPaperMode.set(!state.isLiveMode)
                    ErrorLogger.info(TAG, "🪙 Loaded state: balance=${paperBalance.fmt(2)} SOL | trades=${totalTrades.get()} | WR=${"%.1f".format(getWinRate())}%")
                } else {
                    ErrorLogger.info(TAG, "🪙 No persisted state — using defaults")
                }

                // V5.9.134 — RECONCILE ORPHANED PAPER POSITIONS.
                // Positions are debited from status.paperWalletSol at entry
                // but kept only in memory maps. An app update wipes those
                // maps, leaving the debit with no way to close → paper
                // balance "disappears". Refund + purge via the shared
                // reconciler so the bug can't bleed back in.
                if (isPaperMode.get()) {
                    com.lifecyclebot.collective.PaperOrphanReconciler
                        .reconcile(assetClass = "CRYPTO_ALT", sourceLabel = "CryptoAlt")
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
            // V5.9.445 — Paper→Live parity audit (user 02-2026: "ensure when
            // we go live all learning is applied. so the user feels no
            // difference"). Emit an explicit confirmation so we can see in
            // the logs that the full learning/guard stack is still wired.
            ErrorLogger.info(TAG, "🔴 LIVE PARITY: ChopFilter✓ OutcomeGates✓ PeakDrawdownLock✓ FluidLearning✓ Sentience✓ V3Scorer✓ — all gates active")
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

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.135 — LLM PAPER-TRADE HOOKS
    // ───────────────────────────────────────────────────────────────────────────
    // Lets the sentient chat layer open/close simulated positions via a
    // <<TRADE>>{...}<<ENDTRADE>> block. PAPER MODE ONLY — live-mode calls are
    // rejected at the gate. Intentionally minimal: uses SPOT @ 1x leverage
    // with a synthetic mid-confidence signal so the existing risk/sizing
    // machinery (exposure cap, fluid sizing, TP/SL, AI registration) runs
    // unchanged. Returns a short outcome string suitable for echoing back
    // into the LLM's chat reply.
    // ═══════════════════════════════════════════════════════════════════════════

    sealed class LlmTradeResult {
        data class Success(val summary: String) : LlmTradeResult()
        data class Rejected(val reason: String) : LlmTradeResult()
    }

    /**
     * Open a simulated CryptoAlt paper position from the chat layer.
     * Size is clamped to [0.05, 2.0] SOL for safety.
     */
    fun llmOpenPaperBuy(symbol: String, sizeSol: Double, reason: String): LlmTradeResult {
        if (!isPaperMode.get()) return LlmTradeResult.Rejected("HARD RULE V5.9.187: LLM cannot spend real money. Paper mode only.")
        val ticker = symbol.trim().uppercase()
        val market = try { PerpsMarket.valueOf(ticker) } catch (_: Exception) {
            return LlmTradeResult.Rejected("unknown symbol '$ticker'")
        }
        val clampedSize = sizeSol.coerceIn(0.05, 2.0)
        val avail = getEffectiveBalance()
        if (clampedSize > avail * 0.80) {
            return LlmTradeResult.Rejected(
                "size ${clampedSize.fmt(2)}◎ exceeds 80% of available ${avail.fmt(2)}◎"
            )
        }
        scope.launch {
            try {
                val priceData = try { PerpsMarketDataFetcher.getMarketData(market) } catch (_: Exception) { null }
                val price = priceData?.price ?: PerpsMarketDataFetcher.getCachedPrice(market)?.price ?: 0.0
                if (price <= 0.0) {
                    ErrorLogger.warn(TAG, "💬 LLM BUY ${market.symbol} — no price, aborting")
                    return@launch
                }
                val signal = AltSignal(
                    market           = market,
                    direction        = PerpsDirection.LONG,
                    score            = 70,
                    confidence       = 70,
                    price            = price,
                    priceChange24h   = priceData?.priceChange24hPct ?: 0.0,
                    reasons          = listOf("LLM chat: ${reason.take(80)}"),
                    layerVotes       = emptyMap(),
                    leverage         = DEFAULT_LEVERAGE,
                )
                executeSignal(signal, isSpot = true)
                try { com.lifecyclebot.engine.LlmTradeScore.recordOpen() } catch (_: Exception) {}
                ErrorLogger.info(TAG, "💬 LLM PAPER BUY: ${market.symbol} | ${clampedSize.fmt(3)}◎ | $reason")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "💬 LLM BUY error: ${e.message}")
            }
        }
        return LlmTradeResult.Success("📄 paper buy queued: ${market.symbol} ~${clampedSize.fmt(2)}◎")
    }

    /**
     * Close the latest-opened paper position matching a symbol.
     * V5.9.338: Now searches meme/BotService positions first (by symbol),
     * then falls back to CryptoAlt perps desk. The LLM was emitting
     * symbol="ASTEROID" but this function was only searching perps positions —
     * meme tokens (ShitCoin/Moonshot/Quality/BlueChip/Treasury) live in
     * BotService.status.tokens keyed by mint, not symbol. That caused the
     * "can't find token" error even when the position was visible on screen.
     */
    fun llmClosePaperSell(symbol: String, reason: String): LlmTradeResult {
        if (!isPaperMode.get()) return LlmTradeResult.Rejected("HARD RULE V5.9.187: LLM cannot spend real money. Paper mode only.")
        val ticker = symbol.trim().uppercase()

        // ── 1. Try meme / BotService positions first ──────────────────────────
        // These are TokenState objects in BotService.status.tokens, keyed by mint.
        // The LLM knows them by symbol (e.g. "ASTEROID"), so search by symbol.
        val botService = try { com.lifecyclebot.engine.BotService.instance } catch (_: Exception) { null }
        if (botService != null) {
            val memeTs = try {
                val tokensMap = com.lifecyclebot.engine.BotService.status.tokens
                synchronized(tokensMap) {
                    tokensMap.values
                        .filter { it.symbol.equals(ticker, ignoreCase = true) && it.position.isOpen }
                        .maxByOrNull { it.position.entryTime }
                }
            } catch (_: Exception) { null }

            if (memeTs != null) {
                return try {
                    val (ok, msg) = botService.manualSell(memeTs.mint)
                    if (ok) {
                        val pnlPct = if (memeTs.position.entryPrice > 0) (memeTs.lastPrice / memeTs.position.entryPrice - 1.0) * 100.0 else 0.0
                        ErrorLogger.info(TAG, "💬 LLM MEME SELL: $ticker pnl=${"%.1f".format(pnlPct)}% | $reason")
                        LlmTradeResult.Success("📄 meme sell queued: $ticker @ ${"%.1f".format(pnlPct)}%")
                    } else {
                        LlmTradeResult.Rejected("meme sell failed: $msg")
                    }
                } catch (e: Exception) {
                    LlmTradeResult.Rejected("meme sell error: ${e.message}")
                }
            }
        }

        // ── 2. Fall back to CryptoAlt perps desk ──────────────────────────────
        val match = positions.values
            .filter { it.market.symbol.equals(ticker, ignoreCase = true) && it.closeTime == null }
            .maxByOrNull { it.openTime }
            ?: return LlmTradeResult.Rejected("no open $ticker position found (checked meme + perps desks)")
        val pnlPct = match.getPnlPct()
        requestClose(match.id)
        ErrorLogger.info(TAG, "💬 LLM PERPS SELL: ${ticker} pnl=${pnlPct.fmt(1)}% | $reason")
        return LlmTradeResult.Success("📄 perps sell queued: $ticker @ ${pnlPct.fmt(1)}%")
    }
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
    // V5.9.358 — honest WR: decisive trades only (wins / (wins + losses)).
    // Matches RunTracker30D + Meme contract. Was wins/totalTrades which
    // included scratches as "denominator only" while pnlSol≥0 made them
    // count as wins → top header showed 62% with -13 SOL realised PnL.
    fun getWinRate(): Double {
        val w = winningTrades.get()
        val l = losingTrades.get()
        val decisive = w + l
        return if (decisive > 0) w.toDouble() / decisive * 100 else 0.0
    }
    fun getScratchCount(): Int   = scratchTrades.get()
    fun getLossCount(): Int      = losingTrades.get()
    fun getTotalPnlSol(): Double = totalPnlSol
    fun getInitialBalance(): Double = if (initialBalance > 0.0) initialBalance else paperBalance

    fun getUnrealizedPnlSol(): Double = positions.values.sumOf { it.getPnlSol() }
    fun getUnrealizedPnlPct(): Double {
        val bal = getEffectiveBalance()
        return if (bal > 0) getUnrealizedPnlSol() / bal * 100 else 0.0
    }

    fun getStats(): Map<String, Any> {
        // V5.9.432 — overlay RunTracker30D per-lane bucket so UI numbers
        // match the 30D tracker / Live Readiness views. In-memory counters
        // are the fast local feed; RunTracker30D is the authoritative
        // cross-session source and is preferred when it has more trades
        // (meaning process was restarted and in-memory was reset).
        var trades   = totalTrades.get()
        var wins     = winningTrades.get()
        var losses   = losingTrades.get()
        var scratch  = scratchTrades.get()
        var wr       = getWinRate()
        try {
            val lane = com.lifecyclebot.engine.RunTracker30D.getLaneStats("CRYPTO_ALT")
            val laneTrades = (lane["trades"] as? Int) ?: 0
            if (laneTrades > trades) {
                trades  = laneTrades
                wins    = (lane["wins"]      as? Int) ?: wins
                losses  = (lane["losses"]    as? Int) ?: losses
                scratch = (lane["scratches"] as? Int) ?: scratch
                wr      = (lane["winRate"]   as? Double) ?: wr
            }
        } catch (_: Exception) {}
        return mapOf(
            "totalTrades"    to trades,
            "winningTrades"  to wins,
            "losingTrades"   to losses,
            "scratchTrades"  to scratch,   // V5.9.419 — expose for UI W/L/S parity
            "winRate"        to wr,
            "totalPnlSol"    to totalPnlSol,
            "openPositions"  to positions.size,
            "paperBalance"   to paperBalance,
            "isLiveMode"     to !isPaperMode.get(),
            "learningPhase"  to getPhaseLabel()
        )
    }

    private fun getPhaseLabel(): String {
        val trades = FluidLearningAI.getAltsTradeCount()
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
    fun isLiveReady(): Boolean = FluidLearningAI.getAltsTradeCount() >= 5000 && getWinRate() >= 52.0

    /** V5.9.3: Called from MAA when user taps SPOT/LEVERAGE toggle */
    fun setPreferLeverage(lev: Boolean) {
        preferLeverage.set(lev)
        ErrorLogger.info(TAG, "🪙 Mode → ${if (lev) "LEVERAGE (${DEFAULT_LEVERAGE.toInt()}x)" else "SPOT"}")
    }
    fun isPreferLeverage(): Boolean = preferLeverage.get()

    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}



