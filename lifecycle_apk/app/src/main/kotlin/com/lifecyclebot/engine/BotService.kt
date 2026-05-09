package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.lifecyclebot.R
import com.lifecyclebot.data.*
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.ui.MainActivity
import com.lifecyclebot.v3.scoring.BehaviorAI
import kotlinx.coroutines.*

class BotService : Service() {

    companion object {
        @Volatile private var _instance: java.lang.ref.WeakReference<BotService>? = null
        // V5.9.384 — one-shot flag so BacktestEngine.logAssetClassBaseline
        // doesn't rerun on every service restart within the same process
        // (was allocating all 5784 trades × 6 replays each time).
        @Volatile private var sessionBacktestRan: Boolean = false
        var instance: BotService?
            get() = _instance?.get()
            set(value) { _instance = if (value != null) java.lang.ref.WeakReference(value) else null }
        const val ACTION_START  = "com.lifecyclebot.START"
        const val ACTION_STOP   = "com.lifecyclebot.STOP"
        const val EXTRA_USER_REQUESTED = "com.lifecyclebot.USER_REQUESTED"
        const val RUNTIME_PREFS = "bot_runtime"
        const val KEY_WAS_RUNNING_BEFORE_SHUTDOWN = "was_running_before_shutdown"
        const val KEY_MANUAL_STOP_REQUESTED = "manual_stop_requested"
        const val CHANNEL_ID           = "bot_running"
        const val CHANNEL_TRADE        = "trade_signals"
        const val CHANNEL_TRADE_SILENT = "trade_signals_silent"
        const val NOTIF_ID      = 1

        // ═══════════════════════════════════════════════════════════════
        // PIPELINE DEBUG HELPERS - trace exactly why tokens don't buy
        // ═══════════════════════════════════════════════════════════════
        const val DEBUG_PIPELINE = true

        // ═══════════════════════════════════════════════════════════════
        // V5.9.340: MARKET TRADER MASTER KILL-SWITCH
        // User directive: disable the Market Trader completely while we
        // rewire the AATE scoring/learning stack to match the build
        // #1920-#1947 behavior. This does NOT touch any live buy/sell
        // path — it simply forces every Markets sub-trader into the
        // "disabled" state so none of them scan, score, or open new
        // positions. Existing positions still close through their
        // normal exit paths. Flip to false to re-enable.
        // ═══════════════════════════════════════════════════════════════
        const val MARKET_TRADER_KILL_SWITCH = false  // V5.9.362 — reinstated; switches in Settings now drive enable/disable per trader

        /**
         * V5.9.614 — AntiChokeManager safety hook. When the choke goes RECOVERY
         * because the watchlist is choked with unpriced pump.fun firehose mints,
         * trigger a soft scanner reset so cooldown/saturation maps clear and
         * the discovery feed can re-prioritise. Soft only — never clears
         * seenMints/rejectedMints.
         */
        fun forceScannerSoftResetIfPossible() {
            try {
                val svc = instance ?: return
                val sc = svc.marketScanner ?: return
                sc.forceReset()
            } catch (_: Throwable) { /* never break */ }
        }


        /**
         * V5.9.362 — runtime re-apply of the Markets Trader switches. Called
         * from MainActivity right after the Settings sheet save, so toggling
         * Perps/Stocks/Commodities/Metals/Forex/Alts in the UI takes effect
         * without a service restart. The MARKET_TRADER_KILL_SWITCH constant
         * is honoured so dev builds can still globally suppress the stack.
         */
        fun reapplyMarketsTraderSwitches(ctx: android.content.Context) {
            val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
            val kill = MARKET_TRADER_KILL_SWITCH
            try { com.lifecyclebot.perps.PerpsTraderAI.setEnabled(!kill && cfg.perpsEnabled) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.TokenizedStockTrader.setEnabled(!kill && cfg.stocksEnabled) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.CommoditiesTrader.setEnabled(!kill && cfg.commoditiesEnabled) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.MetalsTrader.setEnabled(!kill && cfg.metalsEnabled) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.ForexTrader.setEnabled(!kill && cfg.forexEnabled) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.CryptoAltTrader.setEnabled(cfg.cryptoAltsEnabled) } catch (_: Exception) {}
            ErrorLogger.info("BotService", "🎚️ Markets switches re-applied: " +
                "perps=${cfg.perpsEnabled} stocks=${cfg.stocksEnabled} comm=${cfg.commoditiesEnabled} " +
                "metals=${cfg.metalsEnabled} forex=${cfg.forexEnabled} alts=${cfg.cryptoAltsEnabled}")
        }

        /**
         * V5.9.469 — single source of truth for "should the Markets lane run?".
         *
         * Operator-reported bug: Markets engine kept starting in live whether
         * the toggle was switched on or not. Root cause: the previous formula
         * was `marketsTraderEnabled || tradingMode==1 || tradingMode==2`. With
         * tradingMode defaulting to 2 (BOTH), the OR made the master toggle
         * silently ineffective — flipping marketsTraderEnabled=false had no
         * effect because the tradingMode==2 branch overrode it. Watchdog
         * loop kept restarting the engine on every 10th tick.
         *
         * Fix: AND semantics. The master toggle has authority; the trading
         * mode just decides whether the Markets lane is even applicable
         * (mode 0 = MEMES_ONLY → markets off; modes 1/2 → master toggle
         * decides).
         *
         * Safe at startup AND in the watchdog. Used in both places below.
         */
        fun isMarketsLaneEnabled(cfg: com.lifecyclebot.data.BotConfig): Boolean {
            return !MARKET_TRADER_KILL_SWITCH &&
                   cfg.marketsTraderEnabled &&
                   (cfg.tradingMode == 1 || cfg.tradingMode == 2)
        }

        // ═══════════════════════════════════════════════════════════════
        // V5.9.353: Strategy distrust pause
        //
        // User log showed: Trust[SHITCOIN] score=0.085 level=DISTRUSTED
        // WR=0% exp=-19.38 fp=87.5%  — yet the bot kept routing tokens to
        // ShitCoin and they all stop-lossed (Scum Ultman -8% in 1 min).
        // When a strategy is provably bleeding, freeze it for 10 min so
        // newer (less-poisoned) strategies get the trade flow.
        // ═══════════════════════════════════════════════════════════════
        const val STRATEGY_DISTRUST_PAUSE_MS = 10L * 60_000L  // 10 minutes
        private val strategyPauseUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun isStrategyPausedByTrust(strategy: String): Pair<Boolean, String> {
            // V5.9.408 — free-range mode: let every strategy keep shooting
            // even if trust is poisoned. The whole point of wide-open is to
            // give bleeding strategies a path to recover via sample size.
            if (com.lifecyclebot.engine.FreeRangeMode.isWideOpen()) return false to "free-range"
            try {
                val trust = com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustRecord(strategy) ?: return false to ""
                val now = System.currentTimeMillis()
                val until = strategyPauseUntilMs[strategy] ?: 0L
                if (until > now) {
                    return true to "paused ${(until - now) / 60_000}m more (WR=${(trust.recentWinRate * 100).toInt()}% fp=${(trust.falsePositiveRate * 100).toInt()}%)"
                }
                val severelyDistrusted = trust.trustLevel == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED &&
                    trust.recentWinRate < 0.10 &&
                    trust.falsePositiveRate > 0.70
                if (severelyDistrusted) {
                    strategyPauseUntilMs[strategy] = now + STRATEGY_DISTRUST_PAUSE_MS
                    return true to "freshly paused 10m (WR=${(trust.recentWinRate * 100).toInt()}% fp=${(trust.falsePositiveRate * 100).toInt()}%)"
                }
                return false to ""
            } catch (_: Exception) {
                return false to ""
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // V5.9.352: Meme Bridge override guardrails
        //
        // V5.9.349 made the bridge override V3 on Watch / ShadowOnly /
        // Rejected (non-routing) the moment bridge.shouldEnter was true.
        // With bridge bootstrap floors (techFloor=30 / blendedFloor=25)
        // that fired on nearly every V3 rejection — meme WR crashed from
        // 43% to 2% on builds 2215+.
        //
        // Now the override requires a MUCH higher bar than the logging
        // threshold. scoreForEntry() still runs for every token (so the
        // "complete picture" log is intact) — but executor.v3Buy only
        // fires when bridge TA conviction is strong, liquidity isn't
        // collapsing, and we haven't already overridden too recently.
        // ═══════════════════════════════════════════════════════════════
        const val BRIDGE_OVERRIDE_MIN_BLEND  = 60
        const val BRIDGE_OVERRIDE_MIN_TECH   = 55
        const val BRIDGE_OVERRIDE_WINDOW_MS  = 10 * 60 * 1000L  // 10 minutes
        const val BRIDGE_OVERRIDE_MAX_PER_WINDOW = 3
        @Volatile private var bridgeOverrideTimestamps: java.util.ArrayDeque<Long> = java.util.ArrayDeque()

        /**
         * V5.9.352 — decide whether a bridge verdict is strong enough to
         * override V3 AND that we haven't hit the rate-limit cap yet.
         * Returns (shouldOverride, reason-if-not).
         */
        fun bridgeOverrideAllowed(
            verdict: com.lifecyclebot.v3.MemeUnifiedScorerBridge.MemeVerdict,
            mint: String,
            symbol: String,
        ): Pair<Boolean, String> {
            if (!verdict.shouldEnter) return false to "shouldEnter=false"
            if (verdict.blendedScore < BRIDGE_OVERRIDE_MIN_BLEND)
                return false to "blend=${verdict.blendedScore}<$BRIDGE_OVERRIDE_MIN_BLEND"
            if (verdict.techScore < BRIDGE_OVERRIDE_MIN_TECH)
                return false to "tech=${verdict.techScore}<$BRIDGE_OVERRIDE_MIN_TECH"
            // Liquidity-collapse veto — do not override into a dying pool.
            try {
                val (block, why) = com.lifecyclebot.engine.LiquidityDepthAI.shouldBlockTrade(mint, symbol, isOpenPosition = false)
                if (block) return false to "liq=${why ?: "BLOCK"}"
            } catch (_: Exception) { /* non-fatal */ }
            // Rate limit — max N overrides per rolling window.
            val now = System.currentTimeMillis()
            synchronized(bridgeOverrideTimestamps) {
                while (bridgeOverrideTimestamps.isNotEmpty() &&
                       bridgeOverrideTimestamps.peekFirst() < now - BRIDGE_OVERRIDE_WINDOW_MS) {
                    bridgeOverrideTimestamps.pollFirst()
                }
                if (bridgeOverrideTimestamps.size >= BRIDGE_OVERRIDE_MAX_PER_WINDOW) {
                    return false to "rate_limit ${bridgeOverrideTimestamps.size}/$BRIDGE_OVERRIDE_MAX_PER_WINDOW in ${BRIDGE_OVERRIDE_WINDOW_MS / 60_000}m"
                }
                bridgeOverrideTimestamps.addLast(now)
            }
            return true to "ok"
        }

        fun logPipeline(symbol: String, stage: String, msg: String) {
            if (!DEBUG_PIPELINE) return
            ErrorLogger.info("BotService", "[PIPELINE/$stage] $symbol | $msg")
        }

        fun logNoBuy(symbol: String, stage: String, reason: String, mint: String = "", extra: String = "") {
            if (!DEBUG_PIPELINE) return
            val mintTag = if (mint.isNotBlank()) " | mint=${mint.take(12)}" else ""
            val extraTag = if (extra.isNotBlank()) " | $extra" else ""
            ErrorLogger.warn("BotService", "[NO_BUY/$stage] $symbol | $reason$mintTag$extraTag")
        }

        // V5.9.116: Per-mint throttle for layer-level "why I skipped" diagnostics
        // so Quality + ShitCoin Express emit at most one rejection log per mint
        // every 60s instead of spamming. Before this, they skipped silently and
        // the user saw zero trades with zero explanation in the logs.
        private val layerSkipLogThrottle = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val LAYER_SKIP_LOG_MIN_INTERVAL_MS = 60_000L

        fun logLayerSkip(layer: String, symbol: String, mint: String, reason: String) {
            val key = "$layer|$mint"
            val now = System.currentTimeMillis()
            val last = layerSkipLogThrottle[key] ?: 0L
            if (now - last < LAYER_SKIP_LOG_MIN_INTERVAL_MS) return
            layerSkipLogThrottle[key] = now
            ErrorLogger.info("BotService", "[$layer SKIP] $symbol | $reason")
        }

        // ═══════════════════════════════════════════════════════════════
        // UNIFIED PAPER WALLET
        // V5.9.48: Every sub-trader (CryptoAlt, TokenizedStocks, Commodities,
        // Metals, Forex) used to keep its own isolated paper balance. User
        // kept seeing $34K Markets portfolio + $31K P&L while the main dash
        // showed $2,733 — because the Markets profits never flowed back into
        // the canonical wallet. One source of truth now lives here: any
        // paper-side trade (open or close) from ANY trader routes through
        // `creditUnifiedPaperSol(delta)`, which delegates to the same
        // safety-clamped callback the Executor already uses.
        // ═══════════════════════════════════════════════════════════════
        fun creditUnifiedPaperSol(delta: Double, source: String) {
            val svc = instance ?: return
            try {
                val cb = if (svc::executor.isInitialized) svc.executor.onPaperBalanceChange else null
                if (cb != null) {
                    cb.invoke(delta)
                    ErrorLogger.info("UnifiedPaperWallet",
                        "[$source] Δ=${"%.4f".format(delta)} SOL → main balance=${"%.4f".format(status.paperWalletSol)}")
                } else {
                    status.paperWalletSol = (status.paperWalletSol + delta).coerceAtLeast(0.0)
                }
            } catch (e: Throwable) {
                ErrorLogger.warn("UnifiedPaperWallet", "[$source] credit failed: ${e.message}")
            }
        }


        fun logBuyHandoff(symbol: String, mint: String, sizeSol: Double, source: String = "", score: Double = 0.0) {
            if (!DEBUG_PIPELINE) return
            val srcTag = if (source.isNotBlank()) " | src=$source" else ""
            val scoreTag = if (score > 0.0) " | score=${score.toInt()}" else ""
            ErrorLogger.info("BotService", "[BUY_HANDOFF] $symbol | mint=${mint.take(12)} | size=${"%.4f".format(sizeSol)}$srcTag$scoreTag")
        }

        // Shared live state — observed by UI via polling or flow
        val status = BotStatus()
        lateinit var walletManager: WalletManager
        // V5.9: Track recently closed positions to prevent immediate re-entry (churn prevention)
        val recentlyClosedMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val RE_ENTRY_COOLDOWN_MS = 300_000L  // 5 minutes

        // V5.9.148 — guards the stop → start race. stopBot() flips status.running
        // to false near its top, but then spends 20-60s closing Markets positions
        // and calling .stop() on every trader singleton. If the user tapped START
        // in that window, onStartCommand saw !running and launched startBot(),
        // which initialized the traders — then the tail of the OLD stopBot stopped
        // them again. Symptom: "30 button presses, bot won't restart".
        @Volatile
        var stopInProgress = false

        @Volatile
        var userStartQueuedDuringStop = false

        // V5.9.621 — inert-loop watchdog state. Updated on every scanner discovery.
        @Volatile
        var lastScannerDiscoveryMs: Long = 0L

        @Volatile
        var inertWatchdogFiredOnce: Boolean = false

        fun isManualStopRequested(ctx: Context): Boolean = try {
            ctx.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MANUAL_STOP_REQUESTED, false)
        } catch (_: Throwable) { false }
    }

    // Coroutine exception handler - logs errors without crashing
    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        ErrorLogger.error("BotService", 
            "Coroutine exception in ${context[CoroutineName]?.name ?: "unknown"}: " +
            "${throwable.javaClass.simpleName}: ${throwable.message}", 
            throwable
        )
        addLog("⚠️ Background error: ${throwable.javaClass.simpleName} - ${throwable.message?.take(50)}")
        
        // Don't crash - just log and continue
        // The SupervisorJob ensures child coroutines don't cancel siblings
    }

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private val dex    = DexscreenerApi()
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wallet: SolanaWallet? = null

    // V5.9.73: track in-flight wallet connect so startBot() returns
    // immediately instead of blocking for 30–90s while RPC fallbacks
    // sequentially time out. stopBot() / mode switch can cancel it cleanly.
    @Volatile private var walletConnectJob: kotlinx.coroutines.Job? = null
    private lateinit var strategy: LifecycleStrategy
    internal lateinit var executor: Executor
    private lateinit var sentimentEngine: SentimentEngine
    private lateinit var safetyChecker: TokenSafetyChecker
    private lateinit var securityGuard: SecurityGuard
    private var orchestrator: DataOrchestrator? = null
    private var marketScanner: SolanaMarketScanner? = null

    // V5.9.634c — freeze-detector state. Lives on the class (not as botLoop
    // locals) so the detector body can be extracted into runFreezeDetectorTick
    // and keep botLoop under the JVM 64KB per-method bytecode limit.
    private var freezeLastExecCount: Long = -1L
    private var freezeLastExecChangeMs: Long = 0L
    private var freezeRecoveryFiredAt: Long = 0L
    internal var tradeDb: TradeDatabase? = null
    internal var botBrain: BotBrain? = null
    lateinit var soundManager: SoundManager
    lateinit var currencyManager: CurrencyManager
    lateinit var notifHistory: NotificationHistory
    lateinit var tradeJournal: TradeJournal
    lateinit var autoMode: AutoModeEngine
    lateinit var copyTradeEngine: CopyTradeEngine
    private var loopJob: Job? = null
    private var notifIdCounter = 100

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Must call startForeground() within 5 seconds of startForegroundService() or Android
        // throws ForegroundServiceDidNotStartInTimeException. Do it here before any slow init.
        createChannels()
        startForeground(NOTIF_ID, buildRunningNotif())

        try {
            // Initialize error logger first so we can capture any init errors
            ErrorLogger.init(applicationContext)
            FeeRetryQueue.init(applicationContext)  // V5.9.226: Bug #7 — fee retry queue
            // V5.9.495z8 — register canonical learning subscribers once at startup.
            // Idempotent: subsequent calls are no-ops. Wires FluidLearningAI
            // mirror + LayerReadinessRegistry samples to the canonical bus.
            CanonicalSubscribers.registerAll()
            // V5.9.455 — ANR FIX.
            // Previously LlmLabEngine.start() ran synchronously on the main
            // thread during onCreate and opened SQLite + seeded strategies,
            // which contributed to the ~2-minute freeze users reported on
            // "Start Live". It's fully optional to the critical boot path
            // (the tick consumer is null-safe via ctxRef) so defer it.
            scope.launch {
                try { com.lifecyclebot.engine.lab.LlmLabEngine.start(applicationContext) } catch (_: Throwable) {}
            }
            ErrorLogger.info("BotService", "onCreate starting")


            strategy        = LifecycleStrategy(
                cfg   = { ConfigStore.load(applicationContext) },
                brain = { botBrain },
            )
            sentimentEngine = SentimentEngine { ConfigStore.load(applicationContext) }
            safetyChecker   = TokenSafetyChecker { ConfigStore.load(applicationContext) }
            walletManager   = WalletManager.getInstance(applicationContext)  // Use singleton
            soundManager    = SoundManager(applicationContext)
            currencyManager = CurrencyManager(applicationContext)
            notifHistory    = NotificationHistory(applicationContext)
            tradeJournal    = TradeJournal(applicationContext)
            autoMode        = AutoModeEngine(
                cfg         = { ConfigStore.load(applicationContext) },
                status      = status,
                onModeChange = { from, to, reason ->
                addLog("⚡ MODE: ${from.label} → ${to.label}  ($reason)")
                sendTradeNotif("Mode Switch", "${to.label}: $reason",
                    NotificationHistory.NotifEntry.NotifType.INFO)
                soundManager.setEnabled(ConfigStore.load(applicationContext).soundEnabled)
            }
        )
        copyTradeEngine = CopyTradeEngine(
            ctx          = applicationContext,
            onCopySignal = { mint, wallet, sol ->
                val c = ConfigStore.load(applicationContext)
                val ts = status.tokens[mint]
                if (ts != null && c.copyTradingEnabled) {
                    autoMode.triggerCopy(mint, wallet)
                    addLog("📋 COPY BUY triggered: ${mint.take(8)}… from ${wallet.take(8)}…", mint)
                    // V5.9: also fire copy-perps trade on SOL via MarketsLiveExecutor
                    if (!c.paperMode && c.heliusApiKey.isNotBlank()) {
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val copySizeSol = (c.smallBuySol * 0.5).coerceIn(0.01, 0.5)
                                val result = com.lifecyclebot.perps.MarketsLiveExecutor.executeLiveTrade(
                                    market      = com.lifecyclebot.perps.PerpsMarket.SOL,
                                    direction   = com.lifecyclebot.perps.PerpsDirection.LONG,
                                    sizeSol     = copySizeSol,
                                    leverage    = 2.0,
                                    priceUsd    = ts.lastPrice,
                                    traderType  = "CopyTrade"
                                )
                                addLog("📋 COPY PERPS: ${if (result.first) "✅ LONG SOL ${copySizeSol}◎" else "❌ failed"}", mint)
                            } catch (e: Exception) {
                                ErrorLogger.warn("BotService", "Copy perps error: ${e.message}")
                            }
                        }
                    }
                }
            },
            onLog = { msg -> addLog(msg) }
        )
        // V5.9.455 — loadWallets() does SharedPreferences + JSON parsing.
        // Cheap in isolation but historically contributed to the onCreate
        // main-thread cost on users with many tracked copy wallets. The
        // engine returns null-safely when queried before this completes.
        scope.launch {
            try { copyTradeEngine.loadWallets() } catch (_: Exception) {}
        }
        securityGuard   = SecurityGuard(
            ctx       = applicationContext,
            cfg       = { ConfigStore.load(applicationContext) },
            onLog     = { msg -> addLog("🔒 SECURITY: $msg") },
            onAlert   = { title, body -> sendTradeNotif(title, body, NotificationHistory.NotifEntry.NotifType.INFO) },
        )
        executor = Executor(
            cfg       = { ConfigStore.load(applicationContext) },
            onLog     = ::addLog,
            onNotify  = { title, body, type -> sendTradeNotif(title, body, type) },
            onToast   = { msg -> showToast(msg) },
            security  = securityGuard,
            sounds    = soundManager,
        )
        
        // Initialize FluidLearning for paper mode simulation
        val cfg = ConfigStore.load(applicationContext)
        FluidLearning.init(applicationContext, cfg.paperSimulatedBalance)
        
        // Initialize TradeHistoryStore for persistent trade stats
        TradeHistoryStore.init(applicationContext)

        // V5.9.438 — durable outcome-learning trackers across restarts.
        try { LearningPersistence.init(applicationContext) } catch (_: Exception) {}

        // V5.9.69: Initialize PatternClassifier — online logistic-regression
        // pattern brain that learns from every closed trade.
        try { PatternClassifier.init(applicationContext) } catch (_: Exception) {}

        // V5.9.75: Initialize VoiceManager (mute-by-default).
        try { VoiceManager.init(applicationContext) } catch (_: Exception) {}
        
        // V5.9.455 — ANR FIX.
        // BehaviorAI.loadFromHistory() scans every historical trade from
        // SQLite + rebuilds the rolling behaviour vector. On a 4000+ trade
        // history this can take multiple seconds and used to run on the
        // main thread during onCreate. Move it off-main; the tick loop is
        // tolerant of a not-yet-loaded BehaviorAI (its query paths all
        // return neutral defaults until loaded).
        scope.launch {
            try {
                com.lifecyclebot.v3.scoring.BehaviorAI.init(applicationContext)
                com.lifecyclebot.v3.scoring.BehaviorAI.loadFromHistory()
                ErrorLogger.info("BotService", "BehaviorAI initialized and loaded from trade history (off-main)")
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "BehaviorAI init/load error: ${e.message}")
            }
        }
        
        // Initialize GeminiCopilot with API key from config
        if (cfg.geminiApiKey.isNotBlank()) {
            GeminiCopilot.init(cfg.geminiApiKey)
            ErrorLogger.info("BotService", "GeminiCopilot initialized with API key")
            // V5.9.361 — mirror the universal LLM key into VoiceManager's TTS
            // slot so the existing per-persona OpenAI voices (Cleetus → onyx
            // + Florida-redneck instructions etc.) actually take effect.
            // Without this mirror the bot was silently falling back to
            // Android TTS (one default female voice for everyone).
            try { VoiceManager.ensureRemoteKeyMirroredFromGemini(applicationContext, cfg.geminiApiKey) } catch (_: Exception) {}
        }

        // V5.9.129: Start the Sentience loop — LLM ↔ Personality ↔ Symbolic feedback.
        // Runs every 6 min, reflects on live state, mutates traits + symbolic
        // composites, injects autonomous thoughts into the stream. Guarded by
        // tight clamps (±0.06 traits, ±0.08 symbolic per cycle).
        try {
            SentienceOrchestrator.start(applicationContext)
            ErrorLogger.info("BotService", "🌌 SentienceOrchestrator started")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "SentienceOrchestrator start error: ${e.message}")
        }
        
        // V5.6: Initialize On-Device ML Engine for trade predictions
        try {
            com.lifecyclebot.ml.OnDeviceMLEngine.initialize(applicationContext)
            ErrorLogger.info("BotService", "🧠 ML Engine initialized | ${com.lifecyclebot.ml.OnDeviceMLEngine.getStatus()}")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "ML Engine init error: ${e.message}")
        }
        
        // V5.6.9: Initialize Position Persistence for crash recovery
        try {
            PositionPersistence.init(applicationContext)
            val persistedCount = PositionPersistence.getPersistedCount()
            if (persistedCount > 0) {
                ErrorLogger.info("BotService", "💾 Position Persistence initialized | $persistedCount positions saved")
            } else {
                ErrorLogger.info("BotService", "💾 Position Persistence initialized | No saved positions")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Position Persistence init error: ${e.message}", e)
        }

        // V5.9.256: Initialize wallet token memory — persistent journal of all buys
        // Survives restarts/updates; used by StartupReconciler to recover positions
        // that the scanner hasn't re-discovered yet.
        try {
            WalletTokenMemory.init(applicationContext)
            val openMemory = WalletTokenMemory.getOpenEntries()
            if (openMemory.isNotEmpty()) {
                ErrorLogger.info("BotService", "💾 WalletTokenMemory: ${openMemory.size} open position(s) in journal: ${openMemory.joinToString(", ") { it.symbol }}")
            } else {
                ErrorLogger.info("BotService", "💾 WalletTokenMemory: no open positions in journal")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "WalletTokenMemory init error: ${e.message}", e)
        }

        // V5.9.262: Initialize live-trade end-to-end log store
        try { LiveTradeLogStore.init(applicationContext) } catch (_: Exception) {}

        // V5.9.495z10: Initialize HostWalletTokenTracker — canonical lifecycle
        // ledger keyed by mint. Independent of scanner watchlist; survives
        // restarts so the bot can never lose track of wallet-held tokens
        // (STRIKE / WCOR drift fix).
        try { HostWalletTokenTracker.init(applicationContext) } catch (_: Exception) {}

        // V5.9.495z24 — Initialize DynamicAltTokenRegistry with disk persistence
        // and start the background discovery loop. Operator: "the registry is
        // meant to be constantly finding new token mints and storing them in
        // persistent memory — should have 500+ already". Now hydrates from
        // disk on startup, runs DexScreener+CoinGecko+Jupiter discovery every
        // 5 min in the background, and persists after each cycle.
        try {
            com.lifecyclebot.perps.DynamicAltTokenRegistry.init(applicationContext)
            com.lifecyclebot.perps.DynamicAltTokenRegistry.startBackgroundDiscovery()
            addLog("🪙 Token registry: ${com.lifecyclebot.perps.DynamicAltTokenRegistry.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "DynamicAltTokenRegistry init error: ${e.message}", e)
        }

        // V5.9.495z25 — Initialize MemeMintRegistry. Persistent meme-mint
        // memory (parallel to DynamicAltTokenRegistry but for the meme
        // scanner). Survives restarts so the bot retains its discovery
        // history across sessions and decisions stay consistent across
        // trades on the same mint.
        try {
            com.lifecyclebot.engine.MemeMintRegistry.init(applicationContext)
            addLog("🪙 Meme mint registry: ${com.lifecyclebot.engine.MemeMintRegistry.stats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "MemeMintRegistry init error: ${e.message}", e)
        }

        // V5.9.495z26 — Initialize TreasuryWalletManager. Auto-generates a
        // fresh on-chain Solana keypair on first launch, persists the private
        // key to EncryptedSharedPreferences, and exposes the treasury pubkey
        // + balance for the wallet UI. Live-mode profit splits are physically
        // transferred trading→treasury (see TreasuryManager.contributeFromMemeSell).
        try {
            com.lifecyclebot.engine.TreasuryWalletManager.init(applicationContext)
            val pk = com.lifecyclebot.engine.TreasuryWalletManager.publicKey()
            if (pk.isNotBlank()) {
                addLog("🏦 Treasury wallet: ${pk.take(8)}…${pk.takeLast(4)}")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "TreasuryWalletManager init error: ${e.message}", e)
        }

        // V5.9.495z28 — TokenLifecycleTracker (operator's 10-item end-to-end
        // overhaul, item 3). Authoritative ledger keyed by mint covering
        // BUY_PENDING → CLEARED + RESIDUAL_HELD/RECONCILE_FAILED. Persisted
        // and restored across restarts so positions can never be lost.
        try {
            com.lifecyclebot.engine.TokenLifecycleTracker.init(applicationContext)
            addLog("📒 Lifecycle: ${com.lifecyclebot.engine.TokenLifecycleTracker.openCount()} open · ${com.lifecyclebot.engine.TokenLifecycleTracker.stats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "TokenLifecycleTracker init error: ${e.message}", e)
        }

        // V5.9.495z29 — Configure LiveExecutionGate from the saved BotConfig
        // (operator spec item 8: throughput controls). The gate is a
        // single in-process semaphore + rate limiter every live BUY must
        // traverse; daily quota / concurrent ceiling / min spacing / pending
        // verification queues all live here. Reading the config once on
        // start is enough — config changes from the settings UI re-call this.
        try {
            val c = ConfigStore.load(applicationContext)
            com.lifecyclebot.engine.LiveExecutionGate.configure(
                com.lifecyclebot.engine.LiveExecutionGate.Config(
                    highThroughputLiveMode             = c.highThroughputLiveMode,
                    maxLiveTradesPerDay                = c.maxLiveTradesPerDay,
                    maxConcurrentLivePositions         = c.maxConcurrentLivePositions,
                    minSecondsBetweenLiveBuys          = c.minSecondsBetweenLiveBuys,
                    maxPendingBuyVerifications         = c.maxPendingBuyVerifications,
                    maxPendingSellVerifications        = c.maxPendingSellVerifications,
                    hotPathTimeoutMs                   = c.hotPathTimeoutMs,
                    walletReconcileTimeoutMs           = c.walletReconcileTimeoutMs,
                    skipSlowBackgroundScansWhenLiveBusy = c.skipSlowBackgroundScansWhenLiveBusy,
                )
            )
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "LiveExecutionGate configure error: ${e.message}", e)
        }
        
        // V5.6.28: Initialize CashGenerationAI for treasury persistence
        try {
            com.lifecyclebot.v3.scoring.CashGenerationAI.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "CashGenerationAI init error: ${e.message}", e)
        }
        
        // V5.6.28d: Initialize SmartSizer for streak persistence
        try {
            SmartSizer.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "SmartSizer init error: ${e.message}", e)
        }
        
        // V5.6.29c: Initialize layer AI persistence
        try {
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.ShitCoinExpress.init(applicationContext)
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.QualityTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.ProjectSniperAI.init(applicationContext)
            ErrorLogger.info("BotService", "All layer AI persistence initialized")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Layer AI init error: ${e.message}", e)
        }
        
        // V5.7: Initialize PerpsTraderAI for leverage trading
        try {
            com.lifecyclebot.perps.PerpsTraderAI.init(applicationContext)
            ErrorLogger.info("BotService", "PerpsTraderAI initialized")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsTraderAI init error: ${e.message}", e)
        }
        
        // V5.7: Initialize PerpsLearningBridge for cross-layer perps intelligence
        try {
            com.lifecyclebot.perps.PerpsLearningBridge.init(applicationContext)
            // V5.9.368 — clear polluted non-directional layer stats once
            // (BehaviorAI/MetaCognitionAI/FluidLearningAI/etc were being
            // graded against directional outcome — produced nonsense
            // accuracy like BehaviorAI 1.3% on 2589 signals). One-shot.
            com.lifecyclebot.perps.PerpsLearningBridge.resetNonDirectionalCorrelationOnce()
            // V5.9.369 — initialize + restore leverage preferences for all
            // Markets-layer traders from SharedPreferences. Bug fix: each
            // trader's preferLeverage was a fresh AtomicBoolean(false) on
            // every boot, so user's LEVERAGE choice was being thrown away.
            try {
                com.lifecyclebot.engine.LeveragePreference.init(applicationContext)
                com.lifecyclebot.engine.LeveragePreference.restoreAllTraders()
            } catch (_: Exception) {}
            ErrorLogger.info("BotService", "PerpsLearningBridge initialized - ${com.lifecyclebot.perps.PerpsLearningBridge.getConnectedLayerCount()} layers connected")

            // V5.9.382 — one-time demotion wipe. The V5.9.374 uniformity
            // glitch poisoned layer accuracy stats (every MEME layer at
            // 20.2%), which triggered TradingCopilot's aggressive demotion
            // (half the brain silenced at 0.5× weight), collapsing meme
            // WR from 33% → 4%. Clear the inherited weight map once so the
            // brain starts fresh under the new gentler 0.80/0.90 curve.
            try {
                val p = getSharedPreferences("aate_bot_prefs", android.content.Context.MODE_PRIVATE)
                if (!p.getBoolean("poisoning_recal_v5_9_382", false)) {
                    com.lifecyclebot.engine.TradingCopilot.clearDemotionWeights()
                    p.edit().putBoolean("poisoning_recal_v5_9_382", true).apply()
                    ErrorLogger.info("BotService", "🧹 V5.9.382: cleared inherited layer demotion weights (poisoning recal)")
                }
            } catch (_: Exception) {}

            // V5.9.375 — run the offline backtest baseline once on boot so the
            // user sees exactly what the bot did per asset class, segmented.
            // V5.9.384 — guarded with a first-boot-per-session flag so it
            // doesn't re-run on every service restart (was allocating all
            // 5784 trades × 6 replays on every boot, contributing to OOM).
            try {
                if (!sessionBacktestRan) {
                    sessionBacktestRan = true
                    Thread {
                        try {
                            com.lifecyclebot.backtest.BacktestEngine.logAssetClassBaseline()
                        } catch (e: Exception) {
                            ErrorLogger.debug("Backtest", "baseline log error: ${e.message}")
                        }
                    }.apply { isDaemon = true; name = "BacktestBaseline" }.start()
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsLearningBridge init error: ${e.message}", e)
        }
        
        // V5.7.3: Start ALL market traders — ALWAYS run when bot is active
        // V5.7.7: Apply individual sub-trader enabled flags from config before starting
        // V5.9.340: MARKET_TRADER_KILL_SWITCH overrides every Markets sub-trader.
        // V5.9.345: User directive — "look at the AATE alts trader, it trades
        // heaps and has good win rate." Keep kill-switch for Perps / Stocks /
        // Commodities / Metals / Forex, but EXEMPT CryptoAlts so it resumes
        // full operation. Alts has a separate scanner + its own trust hooks
        // and was producing +52% WR in the Strategy Trust log pre-kill.
        // V5.9.469: Markets lane gate uses isMarketsLaneEnabled — operator
        // reported "markets keeps starting whether the toggle is on or not";
        // root cause was an OR formula that let tradingMode=2 (default) bypass
        // the marketsTraderEnabled toggle. Now AND semantics: toggle has
        // authority. PerpsExecutionEngine + sub-traders only start when
        // isMarketsLaneEnabled(cfg) AND the per-lane toggle is true.
        val marketsStartCfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
        val marketsKill = MARKET_TRADER_KILL_SWITCH
        val marketsLaneOn = isMarketsLaneEnabled(marketsStartCfg)
        if (marketsKill) {
            ErrorLogger.warn("BotService", "🛑 MARKET_TRADER_KILL_SWITCH=ON (excl. Alts) — Perps/Stocks/Commodities/Metals/Forex forced OFF")
            addLog("🛑 Market Trader disabled (kill-switch) — Alts re-enabled per user directive")
        }
        if (!marketsLaneOn) {
            ErrorLogger.info("BotService", "📴 Markets lane OFF at startup " +
                "(toggle=${marketsStartCfg.marketsTraderEnabled} mode=${marketsStartCfg.tradingMode}) — " +
                "skipping PerpsExecutionEngine + Stocks/Commodities/Metals/Forex starts")
            addLog("📴 Markets lane OFF — Perps/Stocks/Commodities/Metals/Forex will not run this session")
        }
        com.lifecyclebot.perps.PerpsTraderAI.setEnabled(marketsLaneOn && marketsStartCfg.perpsEnabled)
        com.lifecyclebot.perps.TokenizedStockTrader.setEnabled(marketsLaneOn && marketsStartCfg.stocksEnabled)
        com.lifecyclebot.perps.CommoditiesTrader.setEnabled(marketsLaneOn && marketsStartCfg.commoditiesEnabled)
        com.lifecyclebot.perps.MetalsTrader.setEnabled(marketsLaneOn && marketsStartCfg.metalsEnabled)
        com.lifecyclebot.perps.ForexTrader.setEnabled(marketsLaneOn && marketsStartCfg.forexEnabled)
        // V5.9.345: Alts bypasses kill-switch — user wants it running.
        com.lifecyclebot.perps.CryptoAltTrader.setEnabled(marketsStartCfg.cryptoAltsEnabled)

        if (marketsLaneOn) {
            // V5.9.600 BUG-1 FIX: PerpsTraderAI was never getting setLiveMode called.
            // Sub-traders all get setLiveMode(!cfg.paperMode) below, but PerpsTraderAI
            // only had setTradingMode(isPaper) and was never reached from BotService.
            // Wire it the same way as every other trader so the live/paper flag is
            // consistent with the global config.
            try {
                com.lifecyclebot.perps.PerpsTraderAI.setLiveMode(!cfg.paperMode)
                ErrorLogger.info("BotService", "⚡ PerpsTraderAI mode: ${if (cfg.paperMode) "PAPER" else "LIVE"}")
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "PerpsTraderAI setLiveMode error: ${e.message}")
            }
            try {
                com.lifecyclebot.perps.PerpsExecutionEngine.start(applicationContext)
                ErrorLogger.info("BotService", "⚡ PerpsExecutionEngine STARTED - Fully Automatic Trading ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "PerpsExecutionEngine start error: ${e.message}", e)
            }
        }

        // V5.7.5: Start TokenizedStockTrader - DEDICATED stock trading engine
        if (marketsLaneOn && marketsStartCfg.stocksEnabled) {
            try {
                com.lifecyclebot.perps.TokenizedStockTrader.init()
                com.lifecyclebot.perps.TokenizedStockTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.TokenizedStockTrader.start()
                ErrorLogger.info("BotService", "📈 TokenizedStockTrader STARTED - Dedicated Stock Trading ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "TokenizedStockTrader start error: ${e.message}", e)
            }
        }

        // V5.7.6: Start CommoditiesTrader - Energy & Agricultural commodities
        if (marketsLaneOn && marketsStartCfg.commoditiesEnabled) {
            try {
                com.lifecyclebot.perps.CommoditiesTrader.initialize()
                com.lifecyclebot.perps.CommoditiesTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.CommoditiesTrader.start()
                ErrorLogger.info("BotService", "🛢️ CommoditiesTrader STARTED - Oil, Gas, Agriculture ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "CommoditiesTrader start error: ${e.message}", e)
            }
        }

        // V5.7.6: Start MetalsTrader - Precious & Industrial metals
        if (marketsLaneOn && marketsStartCfg.metalsEnabled) {
            try {
                com.lifecyclebot.perps.MetalsTrader.initialize(applicationContext)
                com.lifecyclebot.perps.MetalsTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.MetalsTrader.start()
                ErrorLogger.info("BotService", "🥇 MetalsTrader STARTED - Gold, Silver, Industrial Metals ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "MetalsTrader start error: ${e.message}", e)
            }
        }

        // V5.7.6: Start ForexTrader - Currency pairs
        if (marketsLaneOn && marketsStartCfg.forexEnabled) {
            try {
                com.lifecyclebot.perps.ForexTrader.initialize(applicationContext)
                com.lifecyclebot.perps.ForexTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.ForexTrader.start()
                ErrorLogger.info("BotService", "💱 ForexTrader STARTED - Major, Cross, EM Pairs ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "ForexTrader start error: ${e.message}", e)
            }
        }

        // V5.7.3: Start PerpsAutoReplayLearner for CONTINUOUS learning
        try {
            com.lifecyclebot.perps.PerpsAutoReplayLearner.start()
            ErrorLogger.info("BotService", "🎬 PerpsAutoReplayLearner STARTED - Always Learning Mode ACTIVE")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsAutoReplayLearner start error: ${e.message}", e)
        }

        // V5.7.4: Start Learning Insights Panel for continuous analysis
        try {
            com.lifecyclebot.perps.PerpsLearningInsightsPanel.start()
            ErrorLogger.info("BotService", "🧠 PerpsLearningInsightsPanel STARTED - Continuous Analysis Mode ACTIVE")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "PerpsLearningInsightsPanel start error: ${e.message}")
        }

        // V2.0: Start CryptoAltTrader — ALWAYS runs when bot is active (same as meme trader)
        try {
            com.lifecyclebot.perps.CryptoAltTrader.init(applicationContext)
            com.lifecyclebot.perps.CryptoAltTrader.setLiveMode(!cfg.paperMode)
            com.lifecyclebot.perps.CryptoAltTrader.start()
            ErrorLogger.info("BotService", "🪙 CryptoAltTrader STARTED - Alt Crypto Trading ACTIVE")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "CryptoAltTrader start error: ${e.message}", e)
        }

        // V5.9.54: One-time unified-paper-wallet reconciliation migration.
        // V5.9.48 started crediting every sub-trader open/close into
        // BotService.status.paperWalletSol, but historical realized P&L
        // accumulated in the sub-traders BEFORE that patch landed was never
        // rolled into the main wallet (user saw $13K main vs $98K Markets
        // portfolio + $85K P&L). Once-only: sum lifetime P&L from every
        // sub-trader and credit it to the main wallet, flag set so we never
        // double-count on subsequent boots.
        try {
            reconcileUnifiedPaperWallet()
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "Unified wallet reconciliation error: ${e.message}")
        }

        // V5.7.3: Start Network Signal Auto-Buyer (disabled by default, paper mode only)
        try {
            // Only start if user has explicitly enabled it
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            if (cfg.autoTradeNetworkSignals) {
                com.lifecyclebot.perps.NetworkSignalAutoBuyer.start(
                    com.lifecyclebot.perps.NetworkSignalAutoBuyer.AutoBuyerConfig(
                        enabled = true,
                        paperModeOnly = cfg.paperMode,
                    )
                )
                ErrorLogger.info("BotService", "📡 NetworkSignalAutoBuyer STARTED - Copy Trade from Hive ACTIVE")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "NetworkSignalAutoBuyer start error: ${e.message}")
        }
        
        // V5.9.319: One-time POISONED-LAYER AUTO-RESET migration.
        // After flashing the V5.9.318 isWin fix, layers built up under the
        // V5.9.190 'isWin = pnlPct >= 1.0' contract still have smoothedAccuracy
        // values calibrated against losses-disguised-as-wins. The cumulative
        // Bayesian smoothing means those wrong values won't self-heal in a
        // reasonable time. Detect catastrophic poisoning (avg accuracy < 25%
        // across 10+ active layers with 50+ trades each) and do ONE
        // resetAllLearning() so layers train fresh against the correct
        // direction-accuracy contract.
        try {
            val migrationKey = "v5_9_319_poison_reset_done"
            val prefs = applicationContext.getSharedPreferences("bot_migrations", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean(migrationKey, false)) {
                val maturity = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity().values
                val active = maturity.filter { it.trades >= 50 }
                if (active.size >= 10) {
                    val avgAcc = active.map { it.smoothedAccuracy }.average()
                    if (avgAcc < 0.25) {
                        ErrorLogger.warn("BotService",
                            "💉 V5.9.319 POISON_RESET triggered — avg accuracy=${(avgAcc*100).toInt()}% across ${active.size} active layers. Resetting once.")
                        addLog("💉 POISON_RESET: layers were poisoned by old learning contract. Wiped & re-training fresh.")
                        com.lifecyclebot.v3.scoring.EducationSubLayerAI.resetAllLearning()
                    } else {
                        ErrorLogger.info("BotService",
                            "✅ V5.9.319 poison check OK — avg accuracy=${(avgAcc*100).toInt()}% (>=25%). No reset needed.")
                    }
                }
                prefs.edit().putBoolean(migrationKey, true).apply()
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "Poison-reset check error: ${e.message}")
        }

        // V5.7.4: Start Insider Tracker AI (Trump/Pelosi/Whale wallet monitoring)
        try {
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.start(heliusApiKey = cfg.heliusApiKey) { signal ->
                // Real-time alert callback for alpha signals
                if (signal.wallet.riskLevel == com.lifecyclebot.v3.scoring.InsiderTrackerAI.RiskLevel.ALPHA) {
                    ErrorLogger.info("BotService", "🔍 INSIDER ALERT: ${signal.wallet.label} | ${signal.signalType.name} | ${signal.tokenSymbol ?: "?"}")
                }
                // V5.9.367 — dispatch to copy-trade engine: ACCUMULATION/PRE_TWEET
                // → memetrader watchlist via WHALE_COPY; DISTRIBUTION on alpha
                // wallets → copy-exit across all Markets-layer traders.
                try { com.lifecyclebot.engine.InsiderCopyEngine.onTrackerSignal(signal) } catch (_: Exception) {}
            }
            ErrorLogger.info("BotService", "🔍 InsiderTrackerAI STARTED - Watching ${com.lifecyclebot.v3.scoring.InsiderTrackerAI.getAllWallets().size} wallets (Trump/Pelosi/Whales)")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "InsiderTrackerAI start error: ${e.message}")
        }

        // V5.9.311: Initialize InsiderWalletTracker (powers Insider Tracker UI +
        // delta-based on-chain signal detection). Periodic scanForSignals() is
        // wired in the main loop below to forward NEW_POSITION/ACCUMULATION
        // /DISTRIBUTION/SELL events into the notification + AI scoring pipeline.
        try {
            com.lifecyclebot.perps.InsiderWalletTracker.init(applicationContext)
            com.lifecyclebot.perps.InsiderWalletTracker.setSignalCallback { signal ->
                ErrorLogger.info("BotService", "🔍 INSIDER WALLET SIGNAL: ${signal.walletLabel} | ${signal.action} | ${signal.tokenSymbol} | \$${signal.usdValue.toInt()} | conf=${signal.confidence}%")
                try {
                    com.lifecyclebot.perps.PerpsNotificationManager.notifyInsiderSignal(
                        walletLabel = signal.walletLabel,
                        signalType = signal.action,
                        tokenSymbol = signal.tokenSymbol,
                        confidence = signal.confidence,
                    )
                } catch (_: Exception) {}
                // V5.9.367 — dispatch to copy-trade engine: BUY/NEW_POSITION
                // → memetrader watchlist via WHALE_COPY scanner; SELL on
                // alpha (smart-money) wallets → copy-exit across all
                // Markets-layer traders.
                try { com.lifecyclebot.engine.InsiderCopyEngine.onWalletTrackerSignal(signal) } catch (_: Exception) {}
            }
            val stats = com.lifecyclebot.perps.InsiderWalletTracker.getStats()
            ErrorLogger.info("BotService", "🔍 InsiderWalletTracker INITIALIZED — ${stats["total_wallets"]} wallets (POL=${stats["political"]}, SMART=${stats["smart_money"]}, CUSTOM=${stats["custom"]})")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "InsiderWalletTracker init error: ${e.message}")
        }
        
        // V5.6.28f: Sync RunTracker30D stats with TradeHistoryStore
        try {
            if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                com.lifecyclebot.engine.RunTracker30D.syncStatsFromTradeHistory()
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "RunTracker30D sync error: ${e.message}")
        }
        
        // V5.9.368 — One-shot 72h trust quarantine for TokenizedStockAI.
        // Backstory: the prior 50 stock trades all lost (0% WR) because
        // V3 was running its meme-coin scoring on AAPL/TSLA/etc., dragging
        // every blended score below the trade gate (fixed by V5.9.366's
        // V3 bypass for non-meme assets). Those 50 losses pushed
        // TokenizedStockAI's trust below 0.25 → DISTRUSTED → TRUST_GATE
        // is now blocking every new entry, so V5.9.366's fix can't prove
        // itself. 72h quarantine lets the new V3-bypass path produce a
        // clean baseline before trust is recomputed. Only triggered if
        // the strategy is currently DISTRUSTED — the call is idempotent
        // and harmless on every other launch.
        // V5.9.369 — extended to all Markets-layer trader strategies.
        // The pre-V5.9.366 mis-grading was identical for every asset
        // class, so any trader currently DISTRUSTED is in the same
        // recovery situation and deserves the same 72h clean window.
        try {
            val candidates = listOf(
                "TokenizedStockAI",
                "CryptoAltAI",
                "ForexAI",
                "MetalsAI",
                "CommoditiesAI",
            )
            for (name in candidates) {
                val lvl = com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel(name)
                // V5.9.463 — SENTIENT-FLUID RETUNE. Do NOT auto-quarantine
                // DISTRUSTED markets traders on boot. Per operator:
                // "nothing should really get to a distrusted state. we
                //  have full loop learning". Distrust → coaching, not
                // pause. A quarantine would stop trades → no outcomes →
                // no learning → the strategy can never rebuild trust.
                // We leave the trust record intact; getTrustMultiplier
                // (V5.9.463) shapes sizing at 0.20x floor for heavily
                // underperforming strategies so they keep feeding the
                // learner at low risk.
                if (lvl == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED) {
                    ErrorLogger.info("BotService",
                        "🧠 COACHING MODE (was V5.9.366 quarantine): $name stays active at " +
                        "coaching-floor size — sentient-fluid learning loop will rebuild trust.")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "Markets trust quarantine error: ${e.message}")
        }

        // V5.9.646 — onCreate-anchored meme scanner self-heal.
        //
        // Operator log dump V5.9.645 confirmed: every other lane (CryptoAlt,
        // Markets, Forex, Metals, Commodities, Perps, Insider, Replay) is
        // alive because they all auto-start inside onCreate(). The meme
        // scanner is the ONLY lane that depends on startBot() running, so
        // any Android lifecycle quirk (START_NOT_STICKY auto-restart, an
        // ACTION_START intent that doesn't propagate, a stop→start race
        // that flips to the "already running" branch at line 1083, or
        // onCreate firing without onStartCommand) leaves the meme scanner
        // permanently dead while every other engine is producing signals.
        //
        // This onCreate-anchored heal coroutine closes that gap by running
        // bootMemeScanner() periodically, gated only on:
        //   • user has NOT manually stopped the bot
        //   • some meme-related config toggle is on (memeTraderEnabled,
        //     tradingMode 0/2, fullMarketScan, v3, autoTrade, autoAdd)
        //
        // It does NOT require status.running because the symptom we are
        // fixing is exactly that startBot never set status.running=true.
        scope.launch {
            try {
                kotlinx.coroutines.delay(15_000)  // let onCreate wiring settle
                while (true) {
                    try {
                        val cfg = ConfigStore.load(applicationContext)
                        val manualStop = isManualStopRequested(applicationContext)
                        val memeWanted = cfg.memeTraderEnabled ||
                            cfg.tradingMode == 0 || cfg.tradingMode == 2 ||
                            cfg.fullMarketScanEnabled ||
                            cfg.v3EngineEnabled ||
                            cfg.autoTrade ||
                            cfg.autoAddNewTokens
                        if (!manualStop && memeWanted) {
                            val sc = marketScanner
                            val alive = try { sc?.isAlive() ?: false } catch (_: Throwable) { false }
                            if (sc == null || !alive) {
                                ErrorLogger.warn(
                                    "BotService",
                                    "🩹 ONCREATE_HEAL: meme scanner ${if (sc==null) "NULL" else "not alive"} — booting (manualStop=$manualStop, memeTraderEnabled=${cfg.memeTraderEnabled}, mode=${cfg.tradingMode}, status.running=${status.running})"
                                )
                                bootMemeScanner(reason = "ONCREATE_HEAL")
                            } else {
                                ErrorLogger.debug("BotService", "ONCREATE_HEAL: scanner alive — no action")
                            }
                        }
                    } catch (e: Throwable) {
                        ErrorLogger.debug("BotService", "ONCREATE_HEAL tick error: ${e.message}")
                    }
                    kotlinx.coroutines.delay(30_000)
                }
            } catch (_: Throwable) {}
        }

        } catch (e: Exception) {
            ErrorLogger.crash("BotService", "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            android.util.Log.e("BotService", "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            // Don't crash the service - log and continue
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val userRequested = intent.getBooleanExtra(EXTRA_USER_REQUESTED, false)
                val manualStop = isManualStopRequested(applicationContext)

                // V5.9.609 — stale keep-alive / watchdog / lifecycle alarms must
                // not undo a user stop. Only a fresh UI/user start may clear the
                // manual-stop latch. This fixes the meme bot randomly starting
                // after Stop because an older ACTION_START alarm fired later.
                if (manualStop && !userRequested) {
                    ErrorLogger.warn("BotService", "Ignoring non-user ACTION_START because manual stop latch is active")
                    cancelAllRestartAlarms()
                    try { ServiceWatchdog.cancel(applicationContext) } catch (_: Exception) {}
                    return START_NOT_STICKY
                }
                if (userRequested) {
                    userStartQueuedDuringStop = stopInProgress
                    try {
                        getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_MANUAL_STOP_REQUESTED, false)
                            .apply()
                    } catch (_: Exception) {}
                }

                if (stopInProgress) {
                    // V5.9.148 — queue the start until stopBot() has fully drained.
                    // Otherwise the tail of stopBot stops the traders we just started.
                    addLog("⏳ Stop in progress — restart queued (will auto-start when clean)")
                    ErrorLogger.warn("BotService", "Start requested while stopInProgress — queued")
                    scope.launch {
                        val deadline = System.currentTimeMillis() + 60_000L
                        while (stopInProgress && System.currentTimeMillis() < deadline) {
                            kotlinx.coroutines.delay(200)
                        }
                        if (stopInProgress) {
                            ErrorLogger.error("BotService", "stopBot() did not complete in 60s — force-clearing flag")
                            stopInProgress = false
                        }
                        if (loopJob?.isActive != true && (userStartQueuedDuringStop || !isManualStopRequested(applicationContext))) {
                            userStartQueuedDuringStop = false
                            startBot()
                        }
                    }
                } else if (loopJob?.isActive != true) {
                    userStartQueuedDuringStop = false
                    scope.launch { startBot() }
                } else {
                    // Bot already running - just reschedule keep-alive
                    scheduleKeepAliveAlarm()
                }
            }
            ACTION_STOP  -> {
                // V5.9.609 — make user Stop authoritative immediately. The old
                // code only cleared was_running, while already-scheduled alarms
                // (90s/3m keep-alive, onDestroy, onTaskRemoved) could still fire
                // ACTION_START and make the bot appear to randomly restart.
                try {
                    getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
                        .putBoolean(KEY_MANUAL_STOP_REQUESTED, true)
                        .apply()
                } catch (_: Exception) {}
                userStartQueuedDuringStop = false
                status.running = false
                cancelAllRestartAlarms()
                try { ServiceWatchdog.cancel(applicationContext) } catch (_: Exception) {}
                scope.launch { stopBot() }
            }
        }
        // V5.9.330 RANDOM-START FIX: Changed from START_STICKY to START_NOT_STICKY.
        //
        // START_STICKY causes Android to auto-restart the service with a null intent
        // whenever the process is killed (OOM, crash, system memory pressure).
        // The Journal OOM crash was killing the process → Android restarted it →
        // bot appeared to "randomly start on its own".
        //
        // START_NOT_STICKY: Android does NOT auto-restart on process death.
        // Intentional restart paths are preserved:
        //   - BootReceiver fires on reboot/update (checks was_running_before_shutdown)
        //   - ServiceWatchdog (WorkManager every 15min) also checks that flag
        //   - BotViewModel.startBot() calls startForegroundService() explicitly
        //
        // Effect: bot only restarts when the USER had it running AND it naturally
        // crashed/died, NOT when an unrelated screen (Journal) OOM-killed the process.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ErrorLogger.warn("BotService", "onDestroy() called - service being destroyed")

        // V5.9.438 — flush outcome-learning trackers so nothing is lost on shutdown.
        try { LearningPersistence.saveAll() } catch (_: Exception) {}
        
        // Try to close open positions if bot was running and closePositionsOnStop is enabled
        if (status.running && !isManualStopRequested(applicationContext)) {
            try {
                val cfg = ConfigStore.load(applicationContext)
                
                if (cfg.closePositionsOnStop) {
                    val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                    
                    // Get a synchronized copy of tokens
                    val tokensCopy = synchronized(status.tokens) {
                        status.tokens.toMap()
                    }
                    
                    val openCount = tokensCopy.values.count { it.position.isOpen }
                    if (openCount > 0) {
                        ErrorLogger.warn("BotService", "onDestroy: Attempting to close $openCount open position(s)")
                        
                        // Try to close positions - this may fail if Android kills us mid-way
                        executor.closeAllPositions(
                            tokens = tokensCopy,
                            wallet = wallet,
                            walletSol = effectiveBalance,
                            paperMode = cfg.paperMode,
                        )
                    }
                } else {
                    ErrorLogger.warn("BotService", "onDestroy: closePositionsOnStop=false, positions will remain open")
                }
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "onDestroy: Error closing positions: ${e.message}", e)
            }
            
            // Schedule a restart
            ErrorLogger.warn("BotService", "Bot was running - scheduling restart in 5 seconds")
            val restartIntent = Intent(applicationContext, BotService::class.java).apply {
                action = ACTION_START
            }
            val pi = android.app.PendingIntent.getService(
                this, 2, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5_000,  // Restart in 5 seconds
                pi
            )
        }
        
        // Save EdgeLearning thresholds before shutdown
        try {
            val edgeLearningPrefs = getSharedPreferences("edge_learning", android.content.Context.MODE_PRIVATE)
            EdgeLearning.saveToPrefs(edgeLearningPrefs)
            ErrorLogger.info("BotService", "💾 EdgeLearning saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save EdgeLearning: ${e.message}", e)
        }
        
        // V5.6.9: Save open positions before shutdown for crash recovery
        try {
            val tokensCopy = synchronized(status.tokens) { status.tokens.toMap() }
            PositionPersistence.saveAllPositions(tokensCopy, force = true)
            val savedCount = PositionPersistence.getPersistedCount()
            ErrorLogger.info("BotService", "💾 Position Persistence: Saved $savedCount positions before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save positions: ${e.message}", e)
        }
        
        // Save Entry Intelligence AI before shutdown
        try {
            val entryAiPrefs = getSharedPreferences("entry_intelligence", android.content.Context.MODE_PRIVATE)
            EntryIntelligence.saveToPrefs(entryAiPrefs)
            ErrorLogger.info("BotService", "💾 EntryIntelligence saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save EntryIntelligence: ${e.message}", e)
        }
        
        // Save Exit Intelligence AI before shutdown
        try {
            val exitAiPrefs = getSharedPreferences("exit_intelligence", android.content.Context.MODE_PRIVATE)
            ExitIntelligence.saveToPrefs(exitAiPrefs)
            ErrorLogger.info("BotService", "💾 ExitIntelligence saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save ExitIntelligence: ${e.message}", e)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SAVE NEW AI LAYERS BEFORE SHUTDOWN
        // ═══════════════════════════════════════════════════════════════════
        
        // Save WhaleTrackerAI
        try {
            val whaleAiPrefs = getSharedPreferences("whale_tracker_ai", android.content.Context.MODE_PRIVATE)
            whaleAiPrefs.edit().putString("data", WhaleTrackerAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 WhaleTrackerAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save WhaleTrackerAI: ${e.message}", e)
        }
        
        // Save MarketRegimeAI
        try {
            val regimeAiPrefs = getSharedPreferences("market_regime_ai", android.content.Context.MODE_PRIVATE)
            regimeAiPrefs.edit().putString("data", MarketRegimeAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 MarketRegimeAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save MarketRegimeAI: ${e.message}", e)
        }
        
        // Save MomentumPredictorAI
        try {
            val momentumAiPrefs = getSharedPreferences("momentum_predictor_ai", android.content.Context.MODE_PRIVATE)
            momentumAiPrefs.edit().putString("data", MomentumPredictorAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 MomentumPredictorAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save MomentumPredictorAI: ${e.message}", e)
        }
        
        // Save NarrativeDetectorAI
        try {
            val narrativeAiPrefs = getSharedPreferences("narrative_detector_ai", android.content.Context.MODE_PRIVATE)
            narrativeAiPrefs.edit().putString("data", NarrativeDetectorAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 NarrativeDetectorAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save NarrativeDetectorAI: ${e.message}", e)
        }
        
        // Save TimeOptimizationAI
        try {
            val timeAiPrefs = getSharedPreferences("time_optimization_ai", android.content.Context.MODE_PRIVATE)
            timeAiPrefs.edit().putString("data", TimeOptimizationAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 TimeOptimizationAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save TimeOptimizationAI: ${e.message}", e)
        }
        
        // Save LiquidityDepthAI
        try {
            val liqAiPrefs = getSharedPreferences("liquidity_depth_ai", android.content.Context.MODE_PRIVATE)
            liqAiPrefs.edit().putString("data", LiquidityDepthAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 LiquidityDepthAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save LiquidityDepthAI: ${e.message}", e)
        }
        
        // Save AICrossTalk
        try {
            val crossTalkPrefs = getSharedPreferences("ai_crosstalk", android.content.Context.MODE_PRIVATE)
            crossTalkPrefs.edit().putString("data", AICrossTalk.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "💾 AICrossTalk saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save AICrossTalk: ${e.message}", e)
        }
        
        // V5.7: Save PerpsTraderAI
        try {
            com.lifecyclebot.perps.PerpsTraderAI.save(force = true)
            ErrorLogger.info("BotService", "💾 PerpsTraderAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save PerpsTraderAI: ${e.message}", e)
        }
        
        // V5.7: Save PerpsLearningBridge
        try {
            com.lifecyclebot.perps.PerpsLearningBridge.save()
            ErrorLogger.info("BotService", "💾 PerpsLearningBridge saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save PerpsLearningBridge: ${e.message}", e)
        }
        
        // V5.7.3: Stop PerpsExecutionEngine
        try {
            com.lifecyclebot.perps.PerpsExecutionEngine.stop()
            ErrorLogger.info("BotService", "⚡ PerpsExecutionEngine stopped")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsExecutionEngine stop error: ${e.message}", e)
        }
        
        // V5.7.5: Stop TokenizedStockTrader
        try {
            com.lifecyclebot.perps.TokenizedStockTrader.stop()
            ErrorLogger.info("BotService", "📈 TokenizedStockTrader stopped")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "TokenizedStockTrader stop error: ${e.message}", e)
        }
        
        // V5.7.3: Stop PerpsAutoReplayLearner
        try {
            com.lifecyclebot.perps.PerpsAutoReplayLearner.stop()
            ErrorLogger.info("BotService", "🎬 PerpsAutoReplayLearner stopped")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsAutoReplayLearner stop error: ${e.message}", e)
        }
        
        // V5.7.3: Stop Network Signal Auto-Buyer
        try {
            com.lifecyclebot.perps.NetworkSignalAutoBuyer.stop()
            ErrorLogger.info("BotService", "📡 NetworkSignalAutoBuyer stopped")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "NetworkSignalAutoBuyer stop error: ${e.message}")
        }

        // V5.9.357: Stop Macro Pollers
        try { MacroPollers.stop() } catch (_: Exception) {}
        
        // Shutdown CollectiveLearning
        try {
            com.lifecyclebot.collective.CollectiveLearning.shutdown()
            ErrorLogger.info("BotService", "🌐 CollectiveLearning shutdown")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "CollectiveLearning shutdown error: ${e.message}")
        }
        
        // Shutdown V3 Engine
        try {
            com.lifecyclebot.v3.V3EngineManager.shutdown()
            ErrorLogger.info("BotService", "⚡ V3 Engine shutdown")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "V3 Engine shutdown error: ${e.message}")
        }
        
        // Shutdown V3 Shadow Learning Engine
        try {
            com.lifecyclebot.v3.learning.ShadowLearningEngine.stop()
            ErrorLogger.info("BotService", "🌑 V3 ShadowLearning shutdown")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "V3 ShadowLearning shutdown error: ${e.message}")
        }
        
        scope.cancel()
    }

    /**
     * Called when user swipes the app from the recent apps list.
     * Schedules a restart via a pending intent so the foreground service
     * resumes automatically rather than dying silently.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ErrorLogger.warn("BotService", "onTaskRemoved() called - app swiped from recents, running=${status.running}")
        
        if (status.running && !isManualStopRequested(applicationContext)) {
            // V5.6.8: Multiple restart mechanisms for aggressive OEMs
            val restartIntent = Intent(applicationContext, BotService::class.java).apply {
                action = ACTION_START
            }
            val am = getSystemService(android.app.AlarmManager::class.java)
            
            // Immediate restart (1 second)
            val pi1 = android.app.PendingIntent.getService(
                this, 1, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1_000,
                pi1
            )
            
            // Backup restart (5 seconds) with AlarmClock for highest priority
            try {
                val pi2 = android.app.PendingIntent.getService(
                    this, 3, restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val showPi = android.app.PendingIntent.getActivity(
                    this, 4, Intent(applicationContext, com.lifecyclebot.ui.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                am?.setAlarmClock(
                    android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 5_000, showPi),
                    pi2
                )
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "onTaskRemoved: AlarmClock fallback: ${e.message}")
            }
            
            ErrorLogger.info("BotService", "Scheduled restart alarms (1s + 5s backup)")
        }
    }

    // ── start / stop ───────────────────────────────────────

    // V5.9.54: one-time reconciliation of historical sub-trader P&L into
    // the unified main wallet. Safe to call repeatedly — guarded by a
    // SharedPreferences flag and drift-checked on subsequent boots.
    private fun reconcileUnifiedPaperWallet() {
        val prefs = getSharedPreferences("bot_runtime", MODE_PRIVATE)
        val MIGRATION_KEY = "unified_wallet_migration_v5_9_54"
        val LAST_SUB_PNL_KEY = "last_sub_trader_pnl_sol"

        val currentSubPnl = try {
            com.lifecyclebot.perps.TokenizedStockTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.CommoditiesTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.MetalsTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.ForexTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.CryptoAltTrader.getTotalPnlSol()
        } catch (_: Exception) { 0.0 }

        val alreadyMigrated = prefs.getBoolean(MIGRATION_KEY, false)

        if (!alreadyMigrated) {
            // First-time migration: credit ALL historical realized P&L.
            if (kotlin.math.abs(currentSubPnl) > 0.001) {
                creditUnifiedPaperSol(currentSubPnl, "Migration.V5.9.54_legacy_pnl")
                ErrorLogger.info("BotService",
                    "💰 Unified wallet migration: credited ${"%.4f".format(currentSubPnl)} SOL " +
                    "of legacy sub-trader realized P&L to main wallet")
            }
            prefs.edit()
                .putBoolean(MIGRATION_KEY, true)
                .putFloat(LAST_SUB_PNL_KEY, currentSubPnl.toFloat())
                .apply()
            return
        }

        // Subsequent boots: drift-check. If live crediting from V5.9.48 is
        // working, (currentSubPnl - lastSubPnl) should exactly equal the
        // credits already applied since last boot. We can't easily verify
        // the other direction, but we can at least surface a drift warning
        // if a sub-trader's P&L advanced but the main wallet didn't move
        // alongside (e.g. new code that forgot to call creditUnifiedPaperSol).
        val lastSubPnl = prefs.getFloat(LAST_SUB_PNL_KEY, 0f).toDouble()
        val subDelta = currentSubPnl - lastSubPnl
        if (kotlin.math.abs(subDelta) > 0.01) {
            ErrorLogger.debug("BotService",
                "Unified-wallet drift snapshot: sub-trader realized P&L moved " +
                "${"%.4f".format(subDelta)} SOL since last boot.")
        }
        prefs.edit().putFloat(LAST_SUB_PNL_KEY, currentSubPnl.toFloat()).apply()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.317: MANUAL TRADE API (paper + live, end-to-end)
    // ═══════════════════════════════════════════════════════════════════════════
    // Routes to executor.doBuy / executor.doSell which already handle all
    // routing (paper vs live), security guards, exposure caps, fee splits and
    // shadow-paper mirroring. This is the single source of truth used by the
    // manual BUY/SELL buttons on the active token panel in MainActivity.
    
    /**
     * Manual BUY for a specific token. Returns (success, message) for UI feedback.
     * - In paper mode: routes to paperBuy (no wallet required).
     * - In live mode: routes through full security guard + Jupiter swap pipeline.
     * - Sizing: caller-supplied sol amount (no SmartSizer override) so user has
     *   precise control. Validation prevents overdraw / negative amounts.
     */
    fun manualBuy(mint: String, solAmount: Double): Pair<Boolean, String> {
        if (mint.isBlank()) return false to "No token selected"
        if (solAmount <= 0.0 || solAmount.isNaN() || solAmount.isInfinite()) {
            return false to "Invalid amount: $solAmount SOL"
        }
        if (!::executor.isInitialized) return false to "Bot not started"

        val ts = status.tokens[mint] ?: return false to "Token not in watchlist: ${mint.take(8)}"
        if (ts.position.isOpen) {
            return false to "Position already open: ${ts.symbol}"
        }

        val cfgNow = ConfigStore.load(applicationContext)
        val isPaper = cfgNow.paperMode
        val w = wallet
        // V5.9.495o — operator: manual BUY toasted "Insufficient wallet SOL:
        // 0.0000 < 0.0600" while UI top bar showed 0.9439◎. Fresh on-demand
        // `getSolBalance()` was failing (3-retry RPC throws → catch returns
        // 0.0). The cached `WalletManager.state.value.solBalance` is what
        // the UI displays and is refreshed on a periodic cadence — trust it
        // when fresh RPC fails. Only fall back to fresh RPC if cache is empty.
        val walletSol = if (isPaper) {
            try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Exception) { 0.0 }
        } else {
            val cached = try {
                com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).state.value.solBalance
            } catch (_: Throwable) { 0.0 }
            if (cached > 0.0) cached else try { w?.getSolBalance() ?: 0.0 } catch (_: Exception) { 0.0 }
        }

        // Live-mode preflight: ensure wallet exists + has enough SOL.
        if (!isPaper) {
            if (w == null) return false to "Live wallet not connected"
            // Reserve 0.01 SOL for swap fees (mirrors V5.9.309 fix).
            if (walletSol < solAmount + 0.01) {
                return false to "Insufficient wallet SOL: ${"%.4f".format(walletSol)} < ${"%.4f".format(solAmount + 0.01)}"
            }
        }

        return try {
            ErrorLogger.info("BotService",
                "👆 MANUAL BUY: ${ts.symbol} | ${"%.4f".format(solAmount)} SOL | mode=${if (isPaper) "PAPER" else "LIVE"}")
            addLog("👆 Manual BUY: ${ts.symbol} ${"%.4f".format(solAmount)} SOL ${if (isPaper) "(paper)" else "(LIVE)"}")
            executor.doBuy(
                ts = ts,
                sol = solAmount,
                score = 50.0,                // neutral score: this is a user override, not an AI decision
                wallet = w,
                walletSol = walletSol,
                identity = null,             // let TradeIdentityManager assign
                quality = "MANUAL",
                skipGraduated = false,
            )
            true to "Buy submitted (${if (isPaper) "paper" else "LIVE"})"
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "manualBuy error for ${ts.symbol}", e)
            false to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Manual SELL of an open position. Returns (success, message).
     * - In paper mode: routes to paperSell (instant fill at last price).
     * - In live mode: routes through Jupiter swap pipeline + reconnect logic.
     * - Reason tag "MANUAL" so journal entries are clearly attributed.
     */
    fun manualSell(mint: String): Pair<Boolean, String> {
        if (mint.isBlank()) return false to "No token selected"
        if (!::executor.isInitialized) return false to "Bot not started"

        val cfgNow = ConfigStore.load(applicationContext)
        val isPaper = cfgNow.paperMode
        val w = wallet
        // V5.9.495o — same cached-balance fallback as manualBuy.
        val walletSol = if (isPaper) {
            try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Exception) { 0.0 }
        } else {
            val cached = try {
                com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).state.value.solBalance
            } catch (_: Throwable) { 0.0 }
            if (cached > 0.0) cached else try { w?.getSolBalance() ?: 0.0 } catch (_: Exception) { 0.0 }
        }
        if (!isPaper && w == null) return false to "Live wallet not connected"

        // V5.9.474 — operator-reported manual-SELL store-mismatch bug.
        //
        // Symptom: DMC visible in 'Treasury Scalps' card with +4.1% PnL but
        // pressing manual SELL toasted "No open position to sell" because
        // the old code only inspected `status.tokens[mint].position.isOpen`.
        // CashGenerationAI / ShitCoinTraderAI / QualityTraderAI /
        // BlueChipTraderAI / MoonshotTraderAI all maintain their OWN position
        // maps and (a) do NOT always set ts.position.isOpen=true on the
        // shared TokenState, (b) sometimes the mint isn't in status.tokens
        // at all (cleanup races, reboot rehydration). The sub-trader cards
        // were reading from those private maps but the sell button was
        // reading from the main one — visibility/action mismatch.
        //
        // Fix: scan ALL position stores in priority order. If found:
        //   1. ts.position.isOpen=true  → use main executor.doSell path
        //      (works for ShitCoin / Quality / BlueChip / Moonshot since
        //      those layers DO mirror to ts.position when buying).
        //   2. Treasury-only position (CashGen has it, ts.position closed)
        //      → call CashGenerationAI.closePosition directly so the
        //      strategy bookkeeping clears even if the swap path is busy.
        //   3. None of the above → return a meaningful error listing every
        //      store we checked so the operator knows it's truly absent.
        val ts = status.tokens[mint]

        // Path 1: main TokenState says open → use existing fast path.
        if (ts != null && ts.position.isOpen) {
            return try {
                ErrorLogger.info("BotService",
                    "👆 MANUAL SELL [main]: ${ts.symbol} | qty=${ts.position.qtyToken} | mode=${if (isPaper) "PAPER" else "LIVE"}")
                addLog("👆 Manual SELL: ${ts.symbol} ${if (isPaper) "(paper)" else "(LIVE)"}")
                val result = executor.doSell(ts, "MANUAL", w, walletSol)
                true to "Sell submitted (${result.name})"
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "manualSell error for ${ts.symbol}", e)
                false to "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // Path 2: scan sub-trader stores. Each holds its own position map.
        // If any of them has the mint we route the sell through it.
        val symbol = ts?.symbol ?: mint.take(8)

        try {
            val tp = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
            if (tp != null) {
                val price = com.lifecyclebot.v3.scoring.CashGenerationAI.getTrackedPrice(mint)
                    ?: tp.entryPrice
                ErrorLogger.info("BotService", "👆 MANUAL SELL [Treasury]: ${tp.symbol} @ \$${price}")
                addLog("👆 Manual SELL (Treasury): ${tp.symbol}")
                // If we also have a TokenState, run the swap; close the
                // treasury bookkeeping regardless so the card disappears.
                val realTs = ts
                val sellResult = if (realTs != null) {
                    try { executor.doSell(realTs, "MANUAL_TREASURY", w, walletSol).name } catch (_: Exception) { "TREASURY_BOOKKEEP_ONLY" }
                } else "TREASURY_BOOKKEEP_ONLY (no TokenState)"
                com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                    mint, price, com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT)
                return true to "Treasury position closed ($sellResult)"
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "manualSell Treasury check error: ${e.message}")
        }

        // ShitCoin / Quality / BlueChip / Moonshot all keep their positions
        // in private activePositions maps. They DO mirror to ts.position
        // when buying so the Path 1 branch above usually catches them. If
        // we got here, the main flag fell out of sync — list them so the
        // operator can see which store has it and we still attempt the
        // swap via doSell when a TokenState exists.
        if (ts != null) {
            data class StoreHit(val name: String, val symbol: String)
            val hits = mutableListOf<StoreHit>()
            try {
                if (com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().any { it.mint == mint })
                    hits += StoreHit("ShitCoin", ts.symbol)
            } catch (_: Exception) {}
            try {
                if (com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().any { it.mint == mint })
                    hits += StoreHit("Quality", ts.symbol)
            } catch (_: Exception) {}
            try {
                if (com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().any { it.mint == mint })
                    hits += StoreHit("BlueChip", ts.symbol)
            } catch (_: Exception) {}
            try {
                if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(mint))
                    hits += StoreHit("Moonshot", ts.symbol)
            } catch (_: Exception) {}
            if (hits.isNotEmpty()) {
                ErrorLogger.info("BotService",
                    "👆 MANUAL SELL [sub-trader resync]: ${ts.symbol} found in ${hits.joinToString(",") { it.name }} — forcing doSell")
                addLog("👆 Manual SELL (${hits.first().name}): ${ts.symbol}")
                return try {
                    val result = executor.doSell(ts, "MANUAL_${hits.first().name.uppercase()}", w, walletSol)
                    true to "Sell submitted via ${hits.first().name} (${result.name})"
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "manualSell sub-trader error for ${ts.symbol}", e)
                    false to "Error: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        return false to "No open position found for ${symbol} in any store (main/Treasury/ShitCoin/Quality/BlueChip/Moonshot)"
    }


    /**
     * V5.9.645 — single-purpose meme scanner self-heal builder.
     *
     * Used only by:
     *   • inert-loop watchdog HARD branch when marketScanner is null
     *   • 30-second post-startup self-heal in startBot
     *
     * The full-fat construction at startBot (with TradeIdentity, lifecycle,
     * blacklist diagnostics) stays the source of truth at boot. This path
     * only needs to GET A SCANNER ALIVE so tokens flow into protected intake.
     * admitProtectedMemeIntake already enforces the same gates internally.
     *
     * Returns true if a scanner is alive after this call, false on failure.
     */
    private fun bootMemeScanner(reason: String): Boolean {
        val existing = marketScanner
        if (existing != null) {
            return try {
                if (!existing.isAlive()) {
                    ErrorLogger.warn("BotService", "🩹 SELF_HEAL($reason): existing scanner not alive — restarting")
                    addLog("🩹 Self-heal($reason): scanner not alive — restarting")
                    try { existing.stop() } catch (_: Throwable) {}
                    existing.start()
                }
                true
            } catch (t: Throwable) {
                ErrorLogger.error("BotService", "🚨 SELF_HEAL($reason) restart failed: ${t.message}", t)
                false
            }
        }
        return try {
            ErrorLogger.warn("BotService", "🩹 SELF_HEAL($reason): constructing fresh SolanaMarketScanner")
            addLog("🩹 Self-heal($reason): building Solana scanner from scratch")
            val sc = SolanaMarketScanner(
                cfg          = { ConfigStore.load(applicationContext) },
                onTokenFound = { mint, symbol, name, source, score, liquidityUsd, volumeH1 ->
                    try {
                        // V5.9.650 — operator-requested visibility. Operator's
                        // log dump showed only PUMP_PORTAL_WS reaching protected
                        // intake; non-PumpPortal scanner sources never appear.
                        // This INFO line proves whether the scanner's onTokenFound
                        // callback is firing for the OTHER 13+ sources at all
                        // (DexGainers/Losers/Profiles/Boosted/PumpFunTrending/
                        //  scanTopVolume/scanPumpFunVolume/scanPumpFunActive/
                        //  scanEmergencyDexProfiles, etc).
                        ErrorLogger.info(
                            "BotService",
                            "🔍 SCANNER_CALLBACK_FIRE: $symbol src=${source.name} liq=\$$liquidityUsd score=$score"
                        )
                        lastScannerDiscoveryMs = System.currentTimeMillis()
                        marketScanner?.recordNewTokenFound()
                        admitProtectedMemeIntake(
                            mint = mint,
                            symbol = symbol,
                            name = name.ifBlank { symbol },
                            source = "SCANNER_HEAL_${source.name}",
                            marketCapUsd = liquidityUsd * 10.0,
                            liquidityUsd = liquidityUsd,
                            volumeH1 = volumeH1,
                            confidence = score.toInt().coerceIn(1, 100),
                            allSources = setOf(source.name, "SCANNER_HEAL"),
                            playSound = false,
                            operatorLog = false,
                        )
                        TokenMergeQueue.enqueue(
                            mint = mint,
                            symbol = symbol,
                            scanner = source.name,
                            marketCapUsd = liquidityUsd * 10,
                            liquidityUsd = liquidityUsd,
                            volumeH1 = volumeH1,
                        )
                    } catch (e: Throwable) {
                        ErrorLogger.debug("BotService", "self-heal callback error for $symbol: ${e.message}")
                    }
                },
                onLog = ::addLog,
                getBrain = { botBrain },
            )
            marketScanner = sc
            sc.start()
            ErrorLogger.warn("BotService", "✅ SELF_HEAL($reason): scanner constructed and started")
            addLog("✅ Self-heal($reason): meme scanner is live")
            true
        } catch (t: Throwable) {
            ErrorLogger.error("BotService", "🚨 SELF_HEAL($reason) construction failed: ${t.message}", t)
            addLog("❌ Self-heal($reason) failed: ${t.message}")
            false
        }
    }

    fun startBot() {
        // V5.9.647 — gate on actual botLoop activity, NOT on status.running.
        // BotViewModel.startBot() pre-sets BotService.status.running = true
        // for instant UI feedback BEFORE the service even starts. The old
        // guard `if (status.running) return` therefore short-circuited every
        // single startBot() invocation triggered by onStartCommand →
        // ACTION_START, leaving botLoop() permanently inactive. Net effect:
        // sub-traders that auto-start in onCreate (CryptoAlt, Markets, Forex,
        // Metals, Commodities, Perps) keep producing signals, the V5.9.646
        // onCreate-anchored scanner self-heal still feeds the watchlist, but
        // the meme/V3 trade-execution path (BlueChip qualifications,
        // ShitCoin/Moonshot scoring, FluidLearningAI, FinalDecisionGate,
        // wallet-confirmed buys) never fires because botLoop() is gated by
        // `while (status.running)` and only LAUNCHED inside startBot().
        // Operator screenshot V5.9.646 confirmed the symptom: 44 watchlist
        // entries, every one IDLE +0.0%, BlueChipAI logging
        // 'BLUE CHIP QUALIFIED: TRUMP score=70 conf=90% size=0.3210 SOL'
        // but no execution log following.
        if (loopJob?.isActive == true) {
            ErrorLogger.warn("BotService", "startBot() called but botLoop is already active — skipping")
            return
        }
        
        try {
            ErrorLogger.info("BotService", "startBot() called")
            addLog("🚀 Starting bot...")
            status.running = true
            // Note: startForeground is already called in onStartCommand to meet Android's 5-second requirement
            ErrorLogger.info("BotService", "Foreground service started")
            addLog("✓ Foreground service started")

            // Register network callback to reconnect WebSocket after connectivity loss
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (status.running) {
                            addLog("📡 Network restored — reconnecting streams")
                            scope.launch {
                                delay(2_000)
                                try {
                                    orchestrator?.reconnectStreams()
                                } catch (e: Exception) {
                                    addLog("Stream reconnect error: ${e.message}")
                                }
                            }
                        }
                    }
                    override fun onLost(network: Network) {
                        if (status.running) addLog("📡 Network lost — WebSocket will reconnect on restore")
                    }
                }
                cm.registerNetworkCallback(req, networkCallback!!)
                addLog("✓ Network callback registered")
            } catch (e: Exception) {
                addLog("⚠️ Network callback failed: ${e.message}")
            }

            val cfg = ConfigStore.load(applicationContext)
            // V5.9.602 — publish + log the actual persisted runtime mode at
            // service start. Wallet-connected/live-ready UI is not enough;
            // this is the authority that decides paper vs live execution.
            RuntimeModeAuthority.publishConfig(cfg.paperMode, cfg.autoTrade)
            addLog("✓ Config loaded: paperMode=${cfg.paperMode} authority=${RuntimeModeAuthority.authority()}")

            // V5.9.105: fresh start = clean circuit breaker. beginSession() is
            // called below once the live wallet balance is known.
            LiveSafetyCircuitBreaker.reset()

            // ── Paper wallet: restore from SharedPrefs (survives app updates) ──
            val botPrefs = getSharedPreferences("bot_paper_wallet", android.content.Context.MODE_PRIVATE)
            val savedBalance = botPrefs.getFloat("paper_wallet_sol", 0f).toDouble()
            val lastModeWasPaper = botPrefs.getBoolean("last_mode_was_paper", true)
            val modeChangedLiveToPaper = cfg.paperMode && !lastModeWasPaper

            if (cfg.paperMode) {
                // V5.9.54: If we just switched FROM live TO paper, reset wallet to configured
                // starting balance — live wallet balance (often near-empty) must not leak in.
                // Also clear live-session reentry lockouts and edge vetoes so they don't
                // throttle paper trades on restart.
                if (modeChangedLiveToPaper || savedBalance < 0.01) {
                    status.paperWalletSol = cfg.paperSimulatedBalance
                    addLog("🔄 ${if (modeChangedLiveToPaper) "LIVE→PAPER switch" else "Fresh start"}: paper wallet reset to ${cfg.paperSimulatedBalance} SOL")
                    botPrefs.edit().putFloat("paper_wallet_sol", cfg.paperSimulatedBalance.toFloat()).apply()
                } else {
                    status.paperWalletSol = savedBalance
                    addLog("💰 Paper wallet restored: ${"%.4f".format(savedBalance)} SOL")
                }
                // Clear state that accumulates during live sessions and blocks paper trades
                ReentryGuard.clearAll()
                FinalDecisionGate.clearAllEdgeVetoes()
                FinalDecisionGate.resetLearningState()  // V5.9.182: reset stale block counts
                addLog("🔄 Paper mode start: reentry locks + edge vetoes cleared")
            }

            // Persist current mode so next start can detect a mode switch
            botPrefs.edit().putBoolean("last_mode_was_paper", cfg.paperMode).apply()
            
            // Determine best RPC URL - prefer Helius if key available
            val rpcUrl = if (cfg.heliusApiKey.isNotBlank()) {
                "https://mainnet.helius-rpc.com/?api-key=${cfg.heliusApiKey}"
            } else {
                cfg.rpcUrl
            }
            
            // Check if wallet is already connected via singleton
            val alreadyConnected = walletManager.state.value.connectionState == WalletConnectionState.CONNECTED
            
            wallet = if (!cfg.paperMode && cfg.privateKeyB58.isNotBlank()) {
                if (alreadyConnected) {
                    // Wallet already connected - reuse it
                    addLog("✓ Wallet already connected: ${walletManager.state.value.shortKey}")
                    walletManager.getWallet()
                } else {
                    // V5.9.73 FIX: previously this called walletManager.connect()
                    // SYNCHRONOUSLY inside startBot(). Each RPC attempt could
                    // time out for ~30s; with two fallbacks sequenced after
                    // the user's RPC that's the 90-second freeze where the
                    // bot loop never starts, the UI stops receiving log
                    // updates, and toggling to paper mode can't recover
                    // until the stuck connect finally returns.
                    //
                    // Now: kick connect off in a detached coroutine, let
                    // startBot() continue immediately. Live trades inside
                    // the bot loop already null-check `wallet` and skip
                    // live actions until it's ready. Reconciliation fires
                    // once the wallet actually arrives.
                    addLog("🔌 Connecting wallet in background…")
                    launchWalletConnect(cfg.privateKeyB58, rpcUrl, runReconciliation = true)
                    null
                }
            } else {
                addLog("Paper mode enabled or no key provided")
                // Any in-flight live connect is now obsolete — kill it so
                // switching from live → paper doesn't leave a stuck job
                // holding the wallet variable.
                walletConnectJob?.cancel()
                walletConnectJob = null
                null
            }

            // Run startup reconciliation to catch any state mismatch
            // from previous crash, manual sells, or failed transactions
            // (only fires here if wallet was already connected; the async
            // connect path above triggers it from inside the launch.)
            val liveWallet = wallet
            if (liveWallet != null) {
                scope.launch {
                    try {
                        val reconciler = StartupReconciler(
                            wallet  = liveWallet,
                            status  = status,
                            onLog   = { msg -> addLog(msg) },
                            onAlert = { title, body ->
                                sendTradeNotif(title, body,
                                    NotificationHistory.NotifEntry.NotifType.INFO)
                            },
                            executor = executor,  // Pass executor for orphan auto-sell
                            // V5.9.102: default off. Adoption path now runs first;
                            // only truly-unknown mints with no price data fall
                            // through to the sell path. User's friend lost tracked
                            // exits on profitable positions (FOF +$10.95) because
                            // auto-sell liquidated them on startup.
                            autoSellOrphans = false
                        )
                        reconciler.reconcile()
                    } catch (e: Exception) {
                        addLog("Reconciliation error: ${e.message}")
                    }
                }
            } else if (cfg.paperMode) {
                addLog("Paper mode — skipping on-chain reconciliation")
            }

            // V5.9.495z20 — Recovery Execution Loop. Periodic worker that
            // processes orphan-asset records (USDC residue from any pre-z19
            // partial-bridge buys, or the rare atomic-route surprise). It
            // only makes sense in LIVE mode where wallet has on-chain assets.
            try {
                val w = wallet
                if (!cfg.paperMode && w != null) {
                    com.lifecyclebot.engine.execution.RecoveryExecutionLoop.start(w)
                    addLog("🔄 Recovery loop started (orphan-USDC processor)")
                }
            } catch (e: Exception) {
                addLog("⚠️ Recovery loop start failed: ${e.message}")
            }

            // V5.9.495z22 (item B) — PositionWalletReconciler. Periodic worker
            // that compares each open position against actual host-wallet
            // truth and fires a critical alert + forensics PHANTOM_POSITION
            // event when a position's resolved mint has zero on-chain
            // balance after the settlement grace window. Running in BOTH
            // paper and live so phantoms in shadow runs are also flagged
            // (HostWalletTokenTracker is live-truth either way).
            try {
                val w = wallet
                if (w != null) {
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.installHostTrackerSource()
                    // V5.9.495z25 — register CryptoAltTrader as its own
                    // reconciler source so its open positions get phantom-
                    // checked directly (not just transitively via the host
                    // tracker).
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.registerSource("CryptoAltTrader") {
                        try {
                            com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions().mapNotNull { p ->
                                val resolvedMint = try {
                                    com.lifecyclebot.perps.DynamicAltTokenRegistry
                                        .getTokenBySymbol(p.market.symbol)?.mint
                                        ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
                                } catch (_: Throwable) { null }
                                com.lifecyclebot.engine.execution.PositionWalletReconciler.ReportedPosition(
                                    laneTag = "CRYPTO_ALT",
                                    intendedSymbol = p.market.symbol,
                                    resolvedMint = resolvedMint,
                                    openedAtMs = p.openTime,
                                    sizeUiAmount = p.sizeSol,
                                )
                            }
                        } catch (_: Throwable) { emptyList() }
                    }
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.start(w)
                    addLog("🛡 Position↔Wallet reconciler started (host + crypto-alt)")

                    // V5.9.495z45 — operator forensics_20260508_143519 fix.
                    // Cold-start trigger of LiveWalletReconciler so the
                    // reconciler.totalChecked counter and the
                    // HostWalletTokenTracker.applyWalletSnapshot pipeline
                    // both kick over the moment the bot starts (not after
                    // the first per-token cycle, which can be > 30s late).
                    try {
                        com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(w, "bot_start")
                    } catch (_: Throwable) { /* fail-soft */ }
                }
            } catch (e: Exception) {
                addLog("⚠️ Reconciler start failed: ${e.message}")
            }

            // ═══════════════════════════════════════════════════════════════════
            // V5.6.9: RESTORE PERSISTED POSITIONS ON BOT START
            // 
            // This recovers positions that were lost when the app was killed.
            // CRITICAL: Must happen BEFORE botLoop() starts to avoid duplicate entries.
            // ═══════════════════════════════════════════════════════════════════
            try {
                val tokensCopy = synchronized(status.tokens) { status.tokens.toMutableMap() }
                val restoredCount = PositionPersistence.restorePositions(tokensCopy)
                if (restoredCount > 0) {
                    // Copy restored tokens back to status
                    synchronized(status.tokens) {
                        tokensCopy.forEach { (mint, ts) ->
                            if (!status.tokens.containsKey(mint)) {
                                status.tokens[mint] = ts
                            } else if (ts.position.isOpen && !status.tokens[mint]!!.position.isOpen) {
                                // Restored position for existing token
                                status.tokens[mint]!!.position = ts.position
                            }
                        }
                    }
                    addLog("💾 RESTORED $restoredCount position(s) from persistence")
                    sendTradeNotif("Positions Restored", 
                        "$restoredCount position(s) recovered after restart",
                        NotificationHistory.NotifEntry.NotifType.INFO)
                    ErrorLogger.info("BotService", "💾 Restored $restoredCount positions from persistence")
                }
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Failed to restore positions: ${e.message}", e)
                addLog("⚠️ Position restore failed: ${e.message}")
            }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.621 — PAPER GHOST AUTO-PURGE (V5.9.636: deferred + non-poisoning)
        //
        // Operator: "it shows 15 held tokens when its off". Paper positions
        // are persisted forever (V5.9.122 — never drop for age <60d) so a
        // restart in paper mode silently re-adopts every paper position
        // ever opened. After hundreds of trades the watchlist accumulates
        // a ghost backlog that:
        //   • inflates the "Open" tile in the header
        //   • blocks fresh entries on lane caps (ShitCoin 100, Quality 100)
        //   • breaks the trader-side closePosition() chain because the
        //     status.tokens entry has no live price feed (lastPrice=0)
        //
        // V5.9.636 fix: defer the purge by 90s and run it in scope.launch
        // so it does NOT block startBot() on the main coroutine, AND
        // dropped the four sub-trader closePosition() calls because they
        // tagged ghosts as STOP_LOSS / TIMEOUT / TIME_EXIT and falsely
        // depressed each lane's lifetime WR. The purge now only refunds
        // the paper SOL, journals the close, clears status.tokens, and
        // removes the persistence row — sub-trader caps will refresh
        // naturally on their next regime sync. The 90-second defer also
        // gives DataOrchestrator + price feeds time to come back online,
        // so a transient cold-boot price gap doesn't get classified as a
        // ghost.
        // ═══════════════════════════════════════════════════════════════════
        scope.launch {
            try {
                kotlinx.coroutines.delay(90_000L)
                if (!cfg.paperMode) return@launch
                val nowMs = System.currentTimeMillis()
                val tokensSnapshot = synchronized(status.tokens) { status.tokens.values.toList() }
                var purged = 0
                var refundedSol = 0.0
                for (ts in tokensSnapshot) {
                    try {
                        val pos = ts.position
                        if (!pos.isOpen) continue
                        val ageH = (nowMs - pos.entryTime) / 3600_000.0
                        val price = try { resolveLivePrice(ts) } catch (_: Throwable) { 0.0 }
                        val isGhost = ageH > 24.0 && price <= 0.0
                        if (!isGhost) continue
                        val refund = (pos.qtyToken * pos.entryPrice).coerceAtLeast(0.0)
                        try { creditUnifiedPaperSol(refund, source = "paper_ghost_purge[${ts.symbol}]") } catch (_: Throwable) {}
                        try {
                            com.lifecyclebot.engine.V3JournalRecorder.recordClose(
                                symbol = ts.symbol, mint = ts.mint,
                                entryPrice = pos.entryPrice, exitPrice = pos.entryPrice,
                                sizeSol = refund, pnlPct = 0.0, pnlSol = 0.0,
                                isPaper = true,
                                layer = "GHOST_PURGE",
                                exitReason = "PAPER_GHOST_NO_PRICE_${ageH.toInt()}H",
                                holdMinutes = (ageH * 60).toLong(),
                            )
                        } catch (_: Throwable) {}
                        // V5.9.636 — DO NOT call sub-trader closePosition() with
                        // STOP_LOSS / TIMEOUT / TIME_EXIT here. Those calls
                        // poisoned ShitCoin/Quality/Moonshot/BlueChip lifetime
                        // win-rate counters with phantom losses on every
                        // restart, which is exactly the kind of regression we
                        // are trying to undo.
                        synchronized(status.tokens) {
                            status.tokens[ts.mint]?.position = com.lifecyclebot.data.Position()
                        }
                        try { PositionPersistence.removePosition(ts.mint) } catch (_: Throwable) {}
                        purged++
                        refundedSol += refund
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "ghost-purge error for ${ts.symbol}: ${e.message}")
                    }
                }
                if (purged > 0) {
                    addLog("👻 Paper ghost purge (deferred): cleared $purged stale ghost(s), refunded ${"%.4f".format(refundedSol)} SOL")
                    ErrorLogger.info("BotService", "👻 V5.9.636 deferred paper ghost purge: $purged ghost positions cleared, ${refundedSol} SOL refunded (sub-trader stats untouched)")
                }
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Deferred paper ghost purge failed: ${e.message}", e)
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.430 — START-UP HARD-FLOOR CATCH-UP SWEEP
        //
        // Before the scan loop begins, iterate every restored/open position
        // and force-exit anything already at <= -20% PnL. This clears the
        // backlog of stuck underwater positions (GOBLININU -97.7%, SPEEDRUN
        // -16%, etc.) on the first tick after installing this APK, rather
        // than waiting for the next scan cycle to walk every token.
        //
        // Uses the same resolveLivePrice fan-out as the in-loop universal
        // hard floor (V5.9.429). Runs once per startBot() — does not repeat.
        // ═══════════════════════════════════════════════════════════════════
        try {
            val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
            val wallet = null as com.lifecyclebot.network.SolanaWallet?  // live wallet not wired until below; paper doesn't need it
            val snapshot = synchronized(status.tokens) { status.tokens.values.toList() }
            var swept = 0
            for (ts in snapshot) {
                try {
                    val pos = ts.position
                    if (pos.qtyToken <= 0.0 || pos.entryPrice <= 0.0) continue
                    val price = resolveLivePrice(ts)
                    if (price <= 0.0) continue
                    val pnlPct = ((price - pos.entryPrice) / pos.entryPrice) * 100.0
                    if (pnlPct <= -20.0) {
                        ErrorLogger.warn("BotService",
                            "🛑 [STARTUP_SWEEP_HARD_FLOOR] ${ts.symbol} | ${pnlPct.toInt()}% — closing stale underwater position")
                        addLog("🛑 STARTUP SWEEP: ${ts.symbol} ${pnlPct.toInt()}% — forced exit")
                        executor.requestSell(
                            ts = ts,
                            reason = "STARTUP_SWEEP_HARD_FLOOR_${pnlPct.toInt()}PCT",
                            wallet = wallet,
                            walletSol = effectiveBalance,
                        )
                        try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(ts.mint, price,
                                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.STOP_LOSS) } catch (_: Exception) {}
                        try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, price,
                                com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.STOP_LOSS) } catch (_: Exception) {}
                        swept++
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "startup-sweep error for ${ts.symbol}: ${e.message}")
                }
            }
            if (swept > 0) {
                addLog("🧹 Startup sweep: force-closed $swept underwater position(s) below -20%")
                ErrorLogger.info("BotService", "Startup hard-floor sweep cleared $swept stuck positions")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Startup hard-floor sweep failed: ${e.message}", e)
        }

        // V5.9.621 — arm the inert-loop watchdog at start.
        lastScannerDiscoveryMs = System.currentTimeMillis()
        inertWatchdogFiredOnce = false

        addLog("✓ Starting bot loop...")
        loopJob = scope.launch { botLoop() }

        // V5.9.357 — start macro pollers (Binance funding 5m, Gemini sentiment
        // 15m, CoinGecko stables 60m). These feed FundingRateAwarenessAI,
        // NewsShockAI and StablecoinFlowAI which were previously voting 0
        // because nothing in the codebase ever called their feeders.
        try { MacroPollers.start(scope) } catch (_: Exception) {}

        // Start data orchestrator (real-time streams)
        addLog("✓ Creating data orchestrator...")
        try {
            orchestrator = DataOrchestrator(
                copyTradeEngine    = copyTradeEngine,
                cfg                = { ConfigStore.load(applicationContext) },
                status             = status,
                onLog              = ::addLog,
                onNotify           = { title, body, type -> sendTradeNotif(title, body, type) },
                onNewTokenDetected = { mint, symbol, name ->
                    // V5.9.626 — DataOrchestrator/Pump.fun is a first-class
                    // protected intake source. Do NOT save only to config
                    // watchlist; that can leave GlobalTradeRegistry/status.tokens
                    // empty while the UI says Meme Trader has 0 tokens.
                    try {
                        val c = ConfigStore.load(applicationContext)
                        // V5.9.628 — DataOrchestrator Pump.fun discoveries belong to Meme
                        // Trader too. If memeTraderEnabled is true, they must hydrate the
                        // protected intake even when auto-add/V3/autoTrade are disabled.
                        // V5.9.632 — keep parity with startBot scanner gate + botLoop meme gate
                        // (V5.9.631 added `|| status.running` in those two but missed THIS feed).
                        // If the bot is running and Meme intake is logically active, admit.
                        val shouldAdmit = c.memeTraderEnabled || c.tradingMode == 0 || c.tradingMode == 2 || c.autoAddNewTokens || c.v3EngineEnabled || c.autoTrade || status.running
                        if (shouldAdmit) {
                            admitProtectedMemeIntake(
                                mint = mint,
                                symbol = symbol,
                                name = name,
                                source = "DATA_ORCHESTRATOR",
                                marketCapUsd = 0.0,
                                liquidityUsd = 0.0,
                                volumeH1 = 0.0,
                                confidence = 55,
                                allSources = setOf("DATA_ORCHESTRATOR", "PUMP_FUN_NEW"),
                                playSound = true,
                                operatorLog = true,
                            )
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "DataOrchestrator protected intake error: ${e.message}")
                    }
                },
            onDevSell = { mint, pct ->
                val ts = status.tokens[mint]
                if (ts != null && ts.position.isOpen) {
                    val pctInt = (pct * 100).toInt()
                    addLog("🚨 DEV SELL DETECTED (${pctInt}%) — forcing exit", mint)
                    // Hard exit on large dev sells (>20%); urgency signal on smaller ones
                    if (pct >= 0.20) {
                        // Force immediate exit — dev dumping is a rug signal
                        scope.launch {
                            val cfg = ConfigStore.load(applicationContext)
                            val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                            executor.maybeAct(ts, "EXIT", 0.0, effectiveBalance, wallet,
                                System.currentTimeMillis(), status.openPositionCount,
                                status.totalExposureSol)
                        }
                        sendTradeNotif("🚨 Dev Selling",
                            "${ts.symbol}: dev sold ${pctInt}% — exiting position",
                            NotificationHistory.NotifEntry.NotifType.INFO)
                    } else {
                        // Smaller sell — mark as elevated exit urgency via token state
                        synchronized(ts) { ts.lastError = "dev_sell_${pctInt}pct" }
                        addLog("⚠️ Dev sold ${pctInt}% — watching closely", mint)
                    }
                }
            }
        )
        orchestrator?.start()
        addLog("✓ Data orchestrator started")
        } catch (e: Exception) {
            addLog("⚠️ Orchestrator error: ${e.message}")
        }

        // ═══════════════════════════════════════════════════════════════════
        // V4.0: INITIALIZE GLOBALTRADEREGISTRY BEFORE SCANNER STARTS
        // This ensures all scanner discoveries go through the registry
        // ═══════════════════════════════════════════════════════════════════
        val preScanCfg = ConfigStore.load(applicationContext)
        GlobalTradeRegistry.init(preScanCfg.watchlist, "CONFIG_PRESCAN")
        // V5.2: Set paper mode flags for more aggressive learning
        GlobalTradeRegistry.isPaperMode = preScanCfg.paperMode
        UnifiedModeOrchestrator.isPaperMode = preScanCfg.paperMode
        // V5.2.8: Set EfficiencyLayer paper mode for reduced cooldowns
        EfficiencyLayer.isPaperMode = preScanCfg.paperMode
        // FIX: Propagate mode to FinalExecutionPermit so live trades are not blocked
        FinalExecutionPermit.isPaperMode = preScanCfg.paperMode
        addLog("📋 GlobalTradeRegistry initialized with ${GlobalTradeRegistry.size()} tokens (paperMode=${preScanCfg.paperMode})")

        // V5.9.628 — Rehydrate persistent Meme mint memory into the live
        // watchlist/status surfaces before scanner start. MemeMintRegistry is
        // restored in onCreate(), but the trader/UI reads GlobalTradeRegistry +
        // status.tokens. Without this bridge, a restart can show 0 Meme tokens
        // until the scanner rediscovers everything.
        try {
            val restoredMemeMints = com.lifecyclebot.engine.MemeMintRegistry.getAll()
            var hydrated = 0
            val hydrateCap = preScanCfg.maxWatchlistSize.coerceAtLeast(500)
            for (m in restoredMemeMints.sortedByDescending { it.lastSeenMs }.take(hydrateCap)) {
                if (m.mint.isBlank()) continue
                val ok = admitProtectedMemeIntake(
                    mint = m.mint,
                    symbol = m.symbol.ifBlank { m.mint.take(6) },
                    name = m.name.ifBlank { m.symbol.ifBlank { m.mint.take(6) } },
                    source = "MEME_REGISTRY_RESTORE",
                    marketCapUsd = 0.0,
                    liquidityUsd = 0.0,
                    volumeH1 = 0.0,
                    confidence = 50,
                    allSources = setOf(m.source.ifBlank { "restored" }, "MEME_REGISTRY_RESTORE"),
                    playSound = false,
                    operatorLog = false,
                )
                if (ok || status.tokens.containsKey(m.mint)) hydrated++
            }
            if (restoredMemeMints.isNotEmpty()) {
                addLog("🪙 Meme restore: hydrated $hydrated/${restoredMemeMints.size} persisted mints")
                ErrorLogger.info("BotService", "🪙 Meme restore hydrated $hydrated/${restoredMemeMints.size} persisted mints into runtime")
            }
        } catch (e: Throwable) {
            ErrorLogger.warn("BotService", "Meme restore hydrate failed: ${e.message}")
        }

        // Start protected Solana/Meme intake scanner.
        val scanCfg = ConfigStore.load(applicationContext)
        // V5.9.629 — SOLANA WIDE-FEED RULE:
        // When Meme Trader is enabled, every available Solana source is allowed
        // to feed protected intake: PumpPortal new launches, DataOrchestrator,
        // Pump.fun REST, DexScreener latest/trending/gainers/boosted, Raydium,
        // GeckoTerminal, Meteora, Birdeye, and CoinGecko. The scanner/watchlist
        // is not an execution gate; downstream FDG/safety/sub-traders qualify.
        // Do not tie feed admission to autoTrade/autoAdd/V3 alone.
        // V5.9.628 — Meme scanner is owned by Meme Trader, not by unrelated
        // full-scan/auto-trade/V3 toggles. Latest-APK operator log showed
        // Markets/CryptoAlt/Forex alive while Meme Trader had literally 0 tokens
        // and no scanner-start logs. Root cause: V5.9.625 only fail-opened when
        // one of fullMarketScanEnabled/v3/autoTrade/autoAddNewTokens was true;
        // a valid running bot can have all four false while memeTraderEnabled is
        // still true. Under the protected-intake doctrine, Meme enabled means
        // scanner starts. fullMarketScanEnabled now controls scan breadth only;
        // it must never silently zero the Meme Trader universe.
        val memeModeSelected = scanCfg.tradingMode == 0 || scanCfg.tradingMode == 2  // 0=Meme, 2=Both
        val memeIntakeRequired = scanCfg.memeTraderEnabled ||
            memeModeSelected ||
            scanCfg.fullMarketScanEnabled ||
            scanCfg.v3EngineEnabled ||
            scanCfg.autoTrade ||
            scanCfg.autoAddNewTokens ||
            status.running
        val gateSummary = "meme=${scanCfg.memeTraderEnabled} mode=${scanCfg.tradingMode} fullScan=${scanCfg.fullMarketScanEnabled} " +
            "v3=${scanCfg.v3EngineEnabled} autoTrade=${scanCfg.autoTrade} autoAdd=${scanCfg.autoAddNewTokens}"
        ErrorLogger.info("BotService", "🛡 Meme intake gate: $gateSummary -> start=$memeIntakeRequired")
        addLog("🛡 Meme intake gate: $gateSummary → ${if (memeIntakeRequired) "START" else "OFF"}")
        if (!scanCfg.fullMarketScanEnabled && memeIntakeRequired) {
            ErrorLogger.warn("BotService", "🛡 MEME_INTAKE_FAIL_OPEN: fullMarketScanEnabled=false but Meme/V3/auto intake requires scanner — starting Solana scanner anyway")
            addLog("🛡 Meme intake fail-open: scanner started despite Full Scan toggle OFF")
        }
        if (memeIntakeRequired) {
            try {
                ErrorLogger.info("BotService", "Creating market scanner...")
                marketScanner = SolanaMarketScanner(
                    cfg          = { ConfigStore.load(applicationContext) },
                    onTokenFound = { mint, symbol, name, source, score, liquidityUsd, volumeH1 ->
                        try {
                            // V5.9.623 — scanner heartbeat means raw discovery, not only
                            // post-filter enqueue. Prevents false "scan stale 9999s" while
                            // the scanner is alive but candidates are returning early.
                            lastScannerDiscoveryMs = System.currentTimeMillis()
                            marketScanner?.recordNewTokenFound()

                            val c = ConfigStore.load(applicationContext)
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // V4.0: USE GLOBALTRADEREGISTRY - NOT LOCAL WATCHLIST
                            // This prevents the watchlist reset bug (31→1)
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // TRADE IDENTITY: Create canonical identity for this trade
                            // All subsequent tracking uses this identity for consistency
                            // ═══════════════════════════════════════════════════════════════════
                            val identity = TradeIdentityManager.getOrCreate(mint, symbol, source.name)
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // STAGE 1: DISCOVERED (raw scanner hit)
                            // ═══════════════════════════════════════════════════════════════════
                            TradeLifecycle.discovered(identity.mint, identity.symbol, score, source.name)
                            
                            ErrorLogger.debug("BotService", "DISCOVERED: ${identity.symbol} | liq=$${liquidityUsd.toInt()} | score=$score | src=${source.name}")

                            // V5.9.638 — restore pre-1900 arrival semantics.
                            // Known-good builds around 1890/1900 made scanner discoveries visible
                            // to the Meme runtime first, then let downstream gates decide execution.
                            // Later protected-intake rewrites could leave the UI at 0 tokens while
                            // CryptoAlt/Markets stayed alive. A raw scanner hit must hydrate
                            // GlobalTradeRegistry + status.tokens immediately so the Meme trader has
                            // candidates to process even if merge/probation/telemetry layers wobble.
                            val immediateAdmitted = admitProtectedMemeIntake(
                                mint = identity.mint,
                                symbol = identity.symbol,
                                name = name.ifBlank { identity.symbol },
                                source = "SCANNER_DIRECT_${source.name}",
                                marketCapUsd = liquidityUsd * 10.0,
                                liquidityUsd = liquidityUsd,
                                volumeH1 = volumeH1,
                                confidence = score.toInt().coerceIn(1, 100),
                                allSources = setOf(source.name, "SCANNER_DIRECT"),
                                playSound = false,
                                operatorLog = false,
                            )
                            if (immediateAdmitted) {
                                ErrorLogger.info("BotService", "🟢 MEME_DIRECT_INTAKE: ${identity.symbol} | src=${source.name} | liq=\$${liquidityUsd.toInt()} | score=$score | watch=${GlobalTradeRegistry.size()}")
                            }
                            
                            // V5.9.642b — duplicate registry hits hydrate runtime state but
                            // MUST NOT return early. With MemeMintRegistry pre-hydration, every
                            // scanner discovery is "already watching" on restart, so the early
                            // return was silently blocking ALL tokens from reaching TokenMergeQueue
                            // and botLoop evaluation. Hydrate and fall through.
                            if (GlobalTradeRegistry.isWatching(identity.mint)) {
                                admitProtectedMemeIntake(
                                    mint = identity.mint,
                                    symbol = identity.symbol,
                                    name = name.ifBlank { identity.symbol },
                                    source = source.name,
                                    marketCapUsd = liquidityUsd * 10.0,
                                    liquidityUsd = liquidityUsd,
                                    volumeH1 = volumeH1,
                                    confidence = score.toInt().coerceIn(1, 100),
                                    allSources = setOf(source.name, "REGISTRY_DUPLICATE_HYDRATE"),
                                    playSound = false,
                                    operatorLog = false,
                                )
                                ErrorLogger.debug("BotService", "Token ${identity.symbol} already in registry — hydrated, continuing to queue")
                                // fall through to TokenMergeQueue below — do NOT return here
                            }
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // STAGE 2: ELIGIBILITY CHECKS (filter junk before watchlisting)
                            // V4.20: DUAL ELIGIBILITY GATES - Paper vs Live
                            // V5.0: RC is now checked in Scanner (hard gate at RC <= 10)
                            // 
                            // Paper mode: Loose gates for learning (score >= 35, liq >= $3K)
                            // Live mode: Strict gates for capital protection (score >= 65, liq >= $8K)
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // Define dual eligibility thresholds

                            // V5.9.291 FIX: Scanner liquidity floor unified via ModeLeniency.
                            // Previously live had a $2K hard floor which blocked fresh pump.fun
                            // launches (typically $500-$1500 at first poll, rebuild to $5K+).
                            // The scanner is supposed to be WIDE OPEN — FDG/V3 gate execution.
                            // Proven-edge live now uses the same $500 floor as paper.
                            val lenientScan = com.lifecyclebot.engine.ModeLeniency.useLenientGates(c.paperMode)
                            // V5.9.353: User raised paper floor from $500 → $8K to stop the
                            // $1-5K rug-pool feed (CIVI -84% liq, AFC $1332 liq, etc.). The
                            // 9-hour run showed the bot was getting fed paper-thin tokens
                            // that vaporized in minutes — every entry was a guaranteed loss.
                            // V5.9.364 — user feedback "scanner went backwards": the $8K floor
                            // was eating ~all of V5.9.363's wider funnel. Lowered to $3K (still
                            // above the $1-5K rug zone of V5.9.353), AND added a multi-scanner
                            // bypass below: if ≥2 distinct scanners have already discovered the
                            // same mint inside the merge window, the token is treated as a
                            // strong-signal confirmation and skips this floor entirely.
                            // V5.9.368 — user log analysis showed WR drifting from 38% → 35%
                            // over the 4809-trade run after V5.9.364 deploy. The $3K floor
                            // re-overlapped the V5.9.353 rug-pool zone ($3K-$5K). Raised to
                            // $5K as a midpoint compromise — still well above scanner-side
                            // floors ($250-$1500) so V5.9.363 widening continues to pay off,
                            // but cuts the $3K-$5K rug-zone overlap. Multi-scanner bypass
                            // remains in effect — confirmed alpha can still come in below $5K.
                            // V5.9.399 — user request: "open the gates on the watchlist
                            // and Scanner in the meme trader. let it see everything.
                            // upstream will adjudicate." Paper floor dropped from $5K to
                            // $500 so tiny-liq fresh launches reach downstream sub-traders
                            // (ShitCoin/Quality/BlueChip/Moonshot). Each sub-trader still
                            // applies its own MIN_LIQUIDITY_USD (3K typically), so this
                            // change adds VISIBILITY without forcing entries.
                            //
                            // V5.9.495e — operator (Feb 2026): "luce Scanner should be
                            // wide open like the paper scanner. upstream will do the
                            // work". Live floor unified down from $1000 → $500 to match
                            // paper exactly. The Final Decision Gate, sub-trader
                            // MIN_LIQUIDITY_USD checks, multi-scanner bypass, and
                            // safety-checker still enforce risk on the live path —
                            // dropping the scanner gate just gives them more candidates
                            // to learn from instead of pre-filtering at the funnel mouth.
                            val paperMinLiquidity = 500.0
                            val liveStrictMinLiquidity = 500.0   // V5.9.495e: was 1000 — now matches paper
                            val paperMinScore = 1.0
                            val liveMinScore = 1.0
                            
                            // V5.9.625 — PROTECTED INTAKE MUST BE BEFORE GATES.
                            // Build 2500 forensic symptom: every other universe was alive
                            // (CryptoAlt/Stocks/Commodities), while Meme Trader showed 0
                            // tokens. Root cause: this scanner callback still returned before
                            // TokenMergeQueue for ordinary low-liq / blacklist / low-score
                            // cases. That meant raw Solana discoveries never reached the
                            // protected intake bench at all.
                            //
                            // New rule: scanner discovery always reaches the intake queue.
                            // The checks below only tag lifecycle/telemetry; execution quality
                            // is still enforced later by FDG, safety, blacklist, and sub-trader
                            // gates. This improves token ARRIVAL and qualification coverage
                            // without forcing a bad trade.
                            val minLiquidity = if (lenientScan) paperMinLiquidity else liveStrictMinLiquidity
                            val priorScanners = TokenMergeQueue.priorScannerCount(identity.mint)
                            val multiScannerConfirmed = priorScanners >= 1
                            val minScore = if (c.paperMode) paperMinScore else liveMinScore
                            val modeLabel = if (c.paperMode) "PAPER" else "LIVE"

                            var intakeTags = mutableListOf<String>()
                            if (!multiScannerConfirmed && liquidityUsd < minLiquidity) {
                                intakeTags += "low_liq:${liquidityUsd.toInt()}<${minLiquidity.toInt()}"
                                TradeLifecycle.ineligible(identity.mint, "INTAKE_SHADOW low liquidity: $${liquidityUsd.toInt()} < $${minLiquidity.toInt()}")
                                ErrorLogger.debug("BotService", "🛡 INTAKE_SHADOW: ${identity.symbol} low-liq $${liquidityUsd.toInt()} < $${minLiquidity.toInt()} — queued anyway")
                            }
                            if (multiScannerConfirmed && liquidityUsd < minLiquidity) {
                                MarketsTelemetry.multiScannerBypasses.incrementAndGet()
                                intakeTags += "multi_scanner_bypass:${priorScanners + 1}"
                                ErrorLogger.info("BotService", "🟢 MULTI-SCANNER BYPASS: ${identity.symbol} liq=\$${liquidityUsd.toInt()} (${priorScanners + 1} scanners) — queued below \$${minLiquidity.toInt()} floor")
                            }

                            if (!c.paperMode && TokenBlacklist.isBlocked(identity.mint)) {
                                val blockReason = TokenBlacklist.getBlockReason(identity.mint)
                                val isFalsePositive = blockReason.startsWith("Safety: DATA_CONFLICT", ignoreCase = true) ||
                                    blockReason.startsWith("Safety: Liquidity ", ignoreCase = true)
                                if (isFalsePositive && liquidityUsd >= 5000.0) {
                                    TokenBlacklist.unblock(identity.mint)
                                    intakeTags += "rehabilitated_blacklist"
                                    ErrorLogger.info("BotService", "🩹 REHABILITATED ${identity.symbol}: liq now \$${liquidityUsd.toInt()} — was falsely blacklisted via transient safety liquidity/data hiccup. Unblocking.")
                                    addLog("🩹 REHABILITATED ${identity.symbol}: liq=\$${liquidityUsd.toInt()} healthy, was false-blacklisted from safety liquidity/data hiccup")
                                } else {
                                    intakeTags += "blacklist_shadow"
                                    TradeLifecycle.ineligible(identity.mint, "INTAKE_SHADOW blacklisted")
                                    ErrorLogger.debug("BotService", "🛡 INTAKE_SHADOW: ${identity.symbol} blacklisted — retained for protected intake, execution remains blocked")
                                }
                            }

                            if (score < minScore) {
                                intakeTags += "low_score:$score<$minScore"
                                TradeLifecycle.ineligible(identity.mint, "INTAKE_SHADOW score too low: $score < $minScore")
                                ErrorLogger.debug("BotService", "🛡 INTAKE_SHADOW: ${identity.symbol} score $score < $minScore — queued anyway")
                            } else {
                                ErrorLogger.debug("BotService", "✅ SCORE OK: ${identity.symbol} | score=$score >= minScore=$minScore (${if (c.paperMode) "PAPER" else "LIVE"} mode)")
                            }

                            identity.eligible(score, "[$modeLabel] protected intake queued${if (intakeTags.isNotEmpty()) " shadow=${intakeTags.joinToString(",")}" else ""}")
                            TradeLifecycle.eligible(identity.mint, score, "[$modeLabel] protected intake liq=$${liquidityUsd.toInt()}, score=$score${if (intakeTags.isNotEmpty()) ", shadow=${intakeTags.joinToString(",")}" else ""}")
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // STAGE 3: WATCHLIST ADMISSION (capacity check)
                            // V4.0: Use GlobalTradeRegistry for capacity check
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // Check 3a: Watchlist capacity via GlobalTradeRegistry
                            // V5.0: MUCH TIGHTER LIMITS in bootstrap mode
                            val currentWatchlistSize = GlobalTradeRegistry.size()
                            // V5.2: NO WATCHLIST LIMITS - let it grow as big as it needs
                            // Learning requires seeing many tokens - don't artificially restrict
                            // The bot will naturally prune stale/rejected tokens anyway
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // V4.20: ENQUEUE TO MERGE QUEUE
                            // 
                            // Instead of adding directly to watchlist, enqueue to TokenMergeQueue.
                            // Benefits:
                            //   - Deduplication: Same token found by 3 scanners = 1 watchlist add
                            //   - Multi-scanner boost: Confidence increases when scanners agree
                            //   - Batched processing: Reduces overhead every 2 seconds
                            // 
                            // Flow: Scanner → Eligibility → MergeQueue → processQueue() → Watchlist
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // Enqueue to merge queue - handles deduplication internally
                            TokenMergeQueue.enqueue(
                                mint = identity.mint,
                                symbol = identity.symbol,
                                scanner = source.name,
                                marketCapUsd = liquidityUsd * 10,  // Rough mcap estimate
                                liquidityUsd = liquidityUsd,
                                volumeH1 = volumeH1,
                            )
                            
                            // Mark identity as queued (not yet watchlisted)
                            identity.eligible(score, "enqueued to merge queue")

                            // Debug log (watchlist add log happens in processQueue)
                            ErrorLogger.debug("BotService", "📥 ENQUEUED: ${identity.symbol} | ${source.name} | liq=$${liquidityUsd.toInt()} | score=$score")
                        } catch (e: Exception) {
                            ErrorLogger.error("BotService", "Error adding token $symbol: ${e.message}", e)
                        }
                    },
                    onLog = ::addLog,
                    getBrain = { botBrain },  // AI learning integration
                )
                ErrorLogger.info("BotService", "Starting market scanner...")
                marketScanner?.start()
                addLog("🌐 Full Solana market scanner active — ${scanCfg.maxWatchlistSize} token watchlist")
                ErrorLogger.info("BotService", "Market scanner started!")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Market scanner error: ${e.message}", e)
                addLog("⚠️ Market scanner error: ${e.message}")
            }
        } else {
            ErrorLogger.warn("BotService", "Market scanner DISABLED in config")
            addLog("⚠️ Market scanner disabled — enable in settings")
        }

        // V5.9.645 — 30-second post-startup self-heal. If the construction
        // above failed silently (try/catch ate it, MemeMintRegistry pre-hydration
        // hung, callback closure threw), we still get a scanner alive.
        scope.launch {
            try {
                delay(30_000)
                if (status.running) {
                    val sc = marketScanner
                    val alive = try { sc?.isAlive() ?: false } catch (_: Throwable) { false }
                    ErrorLogger.info("BotService", "🩺 STARTUP_CHECK_30S: marketScanner=${if (sc==null) "NULL" else "OK"} alive=$alive running=${status.running}")
                    if (sc == null || !alive) {
                        addLog("🩹 Startup check (30s): scanner ${if (sc==null) "NULL" else "not alive"} — booting via self-heal")
                        bootMemeScanner(reason = "STARTUP_30S")
                    }
                }
            } catch (_: Throwable) {}
        }

        // Seed candle history for all watchlist tokens
        scope.launch {
            ConfigStore.load(applicationContext).watchlist.forEach { mint ->
                val ts = status.tokens[mint]
                if (ts != null) orchestrator?.onTokenAdded(mint, ts.symbol)
            }
        }

        soundManager.setEnabled(cfg.soundEnabled)
        // Restore session state from last run (streak, peak wallet) - MODE SPECIFIC
        val restored = SessionStore.restore(applicationContext, cfg.paperMode)
        if (!restored) SmartSizer.resetSession()
        TreasuryManager.restore(applicationContext)   // load persisted treasury
            CyclicTradeEngine.init(applicationContext)  // load persisted cyclic ring state
        
        // Safety: if wallet is very low but treasury claims to have locked funds,
        // something is wrong - reset treasury to allow trading
        val currentBalance = walletManager.state.value.solBalance
        if (currentBalance < 0.1 && TreasuryManager.treasurySol > 0.01) {
            addLog("⚠️ Treasury state inconsistent - resetting to allow trading")
            TreasuryManager.emergencyUnlock(applicationContext)
        }
        orchestrator?.startMtfRefresh()

        // Self-learning brain
        val db2 = TradeDatabase(applicationContext); tradeDb = db2
        val brain2 = BotBrain(
            ctx = applicationContext, db = db2,
            cfg = { ConfigStore.load(applicationContext) },
            onLog = { msg -> addLog("🧠 $msg") },
            onParamChanged = { name, old, new, reason ->
                addLog("🧠 $name $old→$new ($reason)")
                sendTradeNotif("🧠 Bot adapted", "$name → $new", NotificationHistory.NotifEntry.NotifType.INFO)
            },
        )
        botBrain = brain2; brain2.start()
        executor.brain = brain2; executor.tradeDb = db2
        
        // Initialize TradingMemory for persistent pattern learning
        TradingMemory.init(applicationContext)
        val memoryStats = TradingMemory.getStats()
        addLog("📚 $memoryStats")
        
        // Show learning applied toast
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                "📚 Learning Applied\n$memoryStats",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
        // Initialize BannedTokens for permanent token bans
        BannedTokens.init(applicationContext)
        BannedTokens.setPaperMode(preScanCfg.paperMode)
        addLog("🚫 ${BannedTokens.getStats()}")
        
        // Initialize PatternAutoTuner for dynamic pattern weight adjustment
        PatternAutoTuner.init(applicationContext)
        addLog("🎛️ ${PatternAutoTuner.getStatus()}")
        
        // Initialize AdaptiveLearningEngine for feature-weighted scoring
        AdaptiveLearningEngine.init(applicationContext)
        addLog("🧬 ${AdaptiveLearningEngine.getStatus()}")
        
        // Initialize ScannerLearning for source/liquidity/age performance tracking
        ScannerLearning.init(applicationContext)
        addLog("📊 ${ScannerLearning.getStats()}")
        
        // Initialize ModeLearning for per-mode learning instances
        ModeLearning.init(applicationContext)
        val bestMode = ModeLearning.getBestMode()
        val worstMode = ModeLearning.getWorstMode()
        addLog("🎯 ModeLearning: Best=${bestMode ?: "N/A"} | Worst=${worstMode ?: "N/A"}")
        
        // Initialize HistoricalChartScanner for backtesting and learning from historical data
        val birdeyeKey = cfg.birdeyeApiKey.ifEmpty { "" }
        HistoricalChartScanner.init(applicationContext, birdeyeKey)
        val scanStats = HistoricalChartScanner.getStats()
        addLog("📊 HistoricalScanner: ${scanStats.tokensAnalyzed} tokens, ${scanStats.winningPatterns}/${scanStats.losingPatterns} patterns")
        
        // Auto-start historical scan in background if enabled and not recently scanned
        if (cfg.autoHistoricalScanEnabled) {
            val lastScanMs = scanStats.lastScanTime
            val hoursSinceScan = if (lastScanMs > 0) {
                (System.currentTimeMillis() - lastScanMs) / (1000 * 60 * 60)
            } else Long.MAX_VALUE
            
            // Only auto-scan if >12 hours since last scan
            if (hoursSinceScan >= 12) {
                addLog("🔬 Starting automatic historical scan (last: ${hoursSinceScan}h ago)...")
                HistoricalChartScanner.startScan(
                    hoursBack = 24,
                    onProgress = { pct, total, msg ->
                        if (pct % 20 == 0) addLog("📊 Scan: $pct% - $msg")
                    },
                    onComplete = { analyzed, learned ->
                        addLog("✅ Historical scan complete: $analyzed tokens, $learned patterns learned")
                    }
                )
            }
        }
        
        // Initialize CloudLearningSync for community shared learning
        CloudLearningSync.init(applicationContext)
        addLog("☁️ ${CloudLearningSync.getStatus()}")
        
        // V5.6.12: Download community weights on startup
        scope.launch {
            try {
                addLog("☁️ Starting community download...")
                val weights = CloudLearningSync.downloadCommunityWeights()
                if (weights != null && weights.totalContributors > 0) {
                    addLog("☁️ Downloaded community data: ${weights.totalContributors} contributors, ${weights.totalTrades} trades")
                    // Apply community weights to local learning engine
                    AdaptiveLearningEngine.applyCommunityWeights(weights.featureWeights, weights.totalTrades)
                } else if (weights != null) {
                    addLog("☁️ No community data yet (0 contributors)")
                } else {
                    addLog("☁️ Download returned null")
                }
            } catch (e: Exception) {
                addLog("☁️ Download error: ${e.message}")
                ErrorLogger.error("CloudSync", "Initial download error: ${e.message}", e)
            }
        }
        
        // Initialize CollectiveLearning (Turso shared knowledge base)
        // V5.6.12: Log config values for debugging collective initialization
        ErrorLogger.info("BotService", "🔧 COLLECTIVE CONFIG CHECK: enabled=${cfg.collectiveLearningEnabled} | urlLen=${cfg.tursoDbUrl.length} | tokenLen=${cfg.tursoAuthToken.length}")
        if (cfg.collectiveLearningEnabled && cfg.tursoDbUrl.isNotBlank() && cfg.tursoAuthToken.isNotBlank()) {
            ErrorLogger.info("BotService", "🔧 COLLECTIVE: Starting init coroutine...")
            scope.launch {
                try {
                    ErrorLogger.info("BotService", "🔧 COLLECTIVE: Inside coroutine, calling init...")
                    val success = com.lifecyclebot.collective.CollectiveLearning.init(applicationContext)
                    ErrorLogger.info("BotService", "🔧 COLLECTIVE: init returned success=$success")
                    if (success) {
                        val stats = com.lifecyclebot.collective.CollectiveLearning.getStats()
                        addLog("🌐 CollectiveLearning: ${stats["blacklistedTokens"]} blacklisted, ${stats["patterns"]} patterns")
                        
                        // V3.2: Send IMMEDIATE heartbeat on init so instance appears right away
                        val botPrefs = getSharedPreferences("bot_service", android.content.Context.MODE_PRIVATE)
                        val instanceId = botPrefs.getString("instance_id", null) 
                            ?: java.util.UUID.randomUUID().toString().also { 
                                botPrefs.edit().putString("instance_id", it).apply() 
                            }
                        val localStats = TradeHistoryStore.getStats()
                        val pnl24hPct = if (localStats.trades24h > 0) {
                            (localStats.pnl24hSol / (localStats.trades24h * 0.1).coerceAtLeast(0.01)) * 100
                        } else 0.0
                        
                        com.lifecyclebot.collective.CollectiveLearning.uploadHeartbeat(
                            instanceId = instanceId,
                            appVersion = com.lifecyclebot.BuildConfig.VERSION_NAME,
                            paperMode = cfg.paperMode,
                            trades24h = localStats.trades24h,
                            pnl24hPct = pnl24hPct
                        )
                        addLog("💓 Instance registered with collective")
                        
                        // Get initial instance count
                        val activeCount = com.lifecyclebot.collective.CollectiveLearning.countActiveInstances()
                        addLog("🌐 Active instances: $activeCount")
                    } else {
                        addLog("⚠️ CollectiveLearning: Failed to connect to Turso")
                    }
                } catch (e: Exception) {
                    addLog("⚠️ CollectiveLearning init error: ${e.message}")
                    ErrorLogger.error("BotService", "CollectiveLearning init error: ${e.message}", e)
                }
            }
        } else if (cfg.collectiveLearningEnabled) {
            addLog("ℹ️ CollectiveLearning: No Turso credentials configured")
        }
        
        // Note: Community weights download happens in main loop after first iteration
        
        // Initialize KillSwitch for account protection
        val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
        KillSwitch.init(applicationContext, effectiveBalance)
        // V5.7.8: Paper mode — disable all daily limits for maximum learning
        KillSwitch.isPaperMode = cfg.paperMode
        KillSwitch.onKillTriggered = { reason ->
            addLog("🛑 KILL SWITCH: $reason")
            sendTradeNotif("🛑 KILL SWITCH", reason, NotificationHistory.NotifEntry.NotifType.WARNING)
        }
        KillSwitch.onWarning = { warning ->
            addLog("⚠️ Risk Warning: $warning")
        }
        addLog("🛡️ KillSwitch initialized: peak=$${effectiveBalance.toInt()}")
        
        // Initialize RuggedContracts blacklist
        RuggedContracts.init(applicationContext)
        addLog("💀 RuggedContracts: ${RuggedContracts.getCount()} blacklisted")
        
        // Initialize Persistent Learning storage (survives app reinstall)
        val persistentAvailable = PersistentLearning.init(applicationContext)
        if (persistentAvailable) {
            addLog("📁 Persistent storage: ${PersistentLearning.getStoragePath()}")
        } else {
            addLog("⚠️ Persistent storage not available - learning will reset on reinstall")
        }
        
        // Initialize EdgeLearning for adaptive threshold learning
        val edgeLearningPrefs = getSharedPreferences("edge_learning", android.content.Context.MODE_PRIVATE)
        EdgeLearning.loadFromPrefs(edgeLearningPrefs)
        addLog("🧠 EdgeLearning: paper(buy>=${EdgeLearning.getPaperBuyPctMin().toInt()}%) live(buy>=${EdgeLearning.getLiveBuyPctMin().toInt()}%)")
        
        // Initialize TokenWinMemory for remembering winning tokens and patterns
        TokenWinMemory.init(applicationContext)
        addLog("🏆 TokenWinMemory: ${TokenWinMemory.getStats()}")
        
        // Initialize HoldingLogicLayer for dynamic position management
        HoldingLogicLayer.init(applicationContext)
        addLog("📊 HoldingLogicLayer: ${HoldingLogicLayer.getAllModeParams().size} mode configurations")
        
        // Warm up DNS cache for Jupiter APIs (bypasses ISP DNS issues)
        com.lifecyclebot.network.CloudflareDns.INSTANCE.warmupJupiterDns()
        addLog("🌐 DNS-over-HTTPS enabled for Jupiter APIs")
        
        // Set up periodic save callback for EdgeLearning
        EdgeLearning.onThresholdsChanged = {
            try {
                EdgeLearning.saveToPrefs(edgeLearningPrefs)
            } catch (e: Exception) {
                ErrorLogger.error("EdgeLearning", "Failed to save: ${e.message}", e)
            }
        }
        
        // Initialize Entry Intelligence AI
        val entryAiPrefs = getSharedPreferences("entry_intelligence", android.content.Context.MODE_PRIVATE)
        EntryIntelligence.loadFromPrefs(entryAiPrefs)
        addLog("🎯 ${EntryIntelligence.getStats()}")
        
        // Initialize Exit Intelligence AI
        val exitAiPrefs = getSharedPreferences("exit_intelligence", android.content.Context.MODE_PRIVATE)
        ExitIntelligence.loadFromPrefs(exitAiPrefs)
        addLog("🚪 ${ExitIntelligence.getStats()}")
        
        // ═══════════════════════════════════════════════════════════════════
        // NEW AI LAYERS INITIALIZATION
        // ═══════════════════════════════════════════════════════════════════
        
        // Initialize WhaleTrackerAI - follow smart money
        try {
            val whaleAiPrefs = getSharedPreferences("whale_tracker_ai", android.content.Context.MODE_PRIVATE)
            val whaleJson = whaleAiPrefs.getString("data", null)
            if (whaleJson != null) {
                WhaleTrackerAI.loadFromJson(org.json.JSONObject(whaleJson))
            }
            addLog("🐋 ${WhaleTrackerAI.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load WhaleTrackerAI: ${e.message}", e)
        }
        
        // Initialize MarketRegimeAI - detect market conditions
        try {
            val regimeAiPrefs = getSharedPreferences("market_regime_ai", android.content.Context.MODE_PRIVATE)
            val regimeJson = regimeAiPrefs.getString("data", null)
            if (regimeJson != null) {
                MarketRegimeAI.loadFromJson(org.json.JSONObject(regimeJson))
            }
            addLog("📊 ${MarketRegimeAI.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load MarketRegimeAI: ${e.message}", e)
        }
        
        // Initialize MomentumPredictorAI - predict token pumps
        try {
            val momentumAiPrefs = getSharedPreferences("momentum_predictor_ai", android.content.Context.MODE_PRIVATE)
            val momentumJson = momentumAiPrefs.getString("data", null)
            if (momentumJson != null) {
                MomentumPredictorAI.loadFromJson(org.json.JSONObject(momentumJson))
            }
            addLog("🚀 ${MomentumPredictorAI.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load MomentumPredictorAI: ${e.message}", e)
        }
        
        // Initialize NarrativeDetectorAI - detect trending narratives
        try {
            val narrativeAiPrefs = getSharedPreferences("narrative_detector_ai", android.content.Context.MODE_PRIVATE)
            val narrativeJson = narrativeAiPrefs.getString("data", null)
            if (narrativeJson != null) {
                NarrativeDetectorAI.loadFromJson(org.json.JSONObject(narrativeJson))
            }
            addLog("📖 ${NarrativeDetectorAI.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load NarrativeDetectorAI: ${e.message}", e)
        }
        
        // Initialize TimeOptimizationAI - learn optimal trading hours
        try {
            val timeAiPrefs = getSharedPreferences("time_optimization_ai", android.content.Context.MODE_PRIVATE)
            val timeJson = timeAiPrefs.getString("data", null)
            if (timeJson != null) {
                TimeOptimizationAI.loadFromJson(org.json.JSONObject(timeJson))
            }
            addLog("⏰ ${TimeOptimizationAI.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load TimeOptimizationAI: ${e.message}", e)
        }
        
        // Initialize LiquidityDepthAI - monitor LP changes in real-time
        try {
            val liqAiPrefs = getSharedPreferences("liquidity_depth_ai", android.content.Context.MODE_PRIVATE)
            val liqJson = liqAiPrefs.getString("data", null)
            if (liqJson != null) {
                LiquidityDepthAI.loadFromJson(org.json.JSONObject(liqJson))
            }
            addLog("💧 ${LiquidityDepthAI.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load LiquidityDepthAI: ${e.message}", e)
        }
        
        // Initialize AICrossTalk - inter-layer communication hub
        try {
            val crossTalkPrefs = getSharedPreferences("ai_crosstalk", android.content.Context.MODE_PRIVATE)
            val crossTalkJson = crossTalkPrefs.getString("data", null)
            if (crossTalkJson != null) {
                AICrossTalk.loadFromJson(org.json.JSONObject(crossTalkJson))
            }
            addLog("🔗 ${AICrossTalk.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to load AICrossTalk: ${e.message}", e)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // INITIALIZE V3 SCORING ENGINE
        // Score-modifier based decision engine (replaces hard-gating)
        // ═══════════════════════════════════════════════════════════════════
        if (cfg.v3EngineEnabled) {
            try {
                com.lifecyclebot.v3.V3EngineManager.initialize(
                    botCfg = cfg,
                    onExecute = null,  // V3 doesn't execute directly - decisions flow through FDG
                    onLog = { msg, mint -> addLog("⚡ $msg", mint) }
                )
                
                val v3Status = com.lifecyclebot.v3.V3EngineManager.getStatusSummary()
                addLog("⚡ $v3Status")
                
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "V3 Engine init failed: ${e.message}", e)
                addLog("⚠️ V3 Engine init failed: ${e.message}")
            }
        } else {
            addLog("ℹ️ V3 Engine: DISABLED (enable in config)")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // INITIALIZE NEW FEATURES (Session 7)
        // Collective Analytics, V3 Confidence Config, Whale Tracker, Time Scheduler
        // ═══════════════════════════════════════════════════════════════════
        try {
            // Collective Analytics - Dashboard data for hive mind
            CollectiveAnalytics.init(applicationContext)
            addLog("📊 CollectiveAnalytics: ${CollectiveAnalytics.getSummary().yourContributions} contributions")
            
            // V3 Confidence Config - User-adjustable thresholds
            V3ConfidenceConfig.init(applicationContext)
            addLog("⚙️ V3 Confidence: ${V3ConfidenceConfig.getCurrentMode()} mode")
            
            // Whale Wallet Tracker - Track successful whale wallets
            WhaleWalletTracker.init(applicationContext)
            val topWhales = WhaleWalletTracker.getTopWhales(3)
            addLog("🐋 WhaleTracker: ${topWhales.size} reliable whales tracked")
            
            // Time Mode Scheduler - Auto-switch modes by time
            TimeModeScheduler.init(applicationContext)
            val timeRec = TimeModeScheduler.getRecommendation()
            if (timeRec.recommendedMode != null) {
                addLog("⏰ TimeScheduler: Recommends ${timeRec.recommendedMode} (${timeRec.confidence}% conf)")
            } else {
                addLog("⏰ TimeScheduler: ${if (TimeModeScheduler.isAutoSwitchEnabled()) "ENABLED" else "DISABLED"}")
            }
            
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "New features init failed: ${e.message}", e)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // INITIALIZE V4 META-INTELLIGENCE LAYER
        // Read-only overlay — observes and advises, never blocks meme trades directly
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.v4.meta.StrategyTrustAI.init()
            com.lifecyclebot.v4.meta.CrossAssetLeadLagAI.init()
            com.lifecyclebot.v4.meta.ExecutionPathAI.init()
            com.lifecyclebot.v4.meta.NarrativeFlowAI.init()
            com.lifecyclebot.v4.meta.CrossTalkFusionEngine.init(
                strategyTrust = com.lifecyclebot.v4.meta.StrategyTrustAI,
                fragility = com.lifecyclebot.v4.meta.LiquidityFragilityAI,
                leadLag = com.lifecyclebot.v4.meta.CrossAssetLeadLagAI,
                regime = com.lifecyclebot.v4.meta.CrossMarketRegimeAI,
                portfolioHeat = com.lifecyclebot.v4.meta.PortfolioHeatAI,
                leverageSurvival = com.lifecyclebot.v4.meta.LeverageSurvivalAI,
                narrative = com.lifecyclebot.v4.meta.NarrativeFlowAI,
                executionPath = com.lifecyclebot.v4.meta.ExecutionPathAI
            )
            addLog("🧠 V4 Meta-Intelligence: ${com.lifecyclebot.v4.meta.CrossTalkFusionEngine.getStats()}")
            
            // Wire Turso persistence + load saved memory
            com.lifecyclebot.v4.meta.TradeLessonRecorder.tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.lifecyclebot.v4.meta.TradeLessonRecorder.loadFromTurso()
                    addLog("🧠 V4 Memory loaded from Turso: ${com.lifecyclebot.v4.meta.TradeLessonRecorder.getTotalLessons()} lessons")
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "V4 Turso load (non-fatal): ${e.message}")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "V4 Meta init failed (non-fatal): ${e.message}", e)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.7.8: WATCHLIST & ALERTS ENGINE
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.perps.WatchlistEngine.init(applicationContext)
            com.lifecyclebot.perps.WatchlistEngine.setAlertCallback { triggered ->
                addLog("🔔 ALERT: ${triggered.message}")
                try {
                    notifHistory.add(
                        "Price Alert: ${triggered.alert.symbol}",
                        triggered.message,
                        NotificationHistory.NotifEntry.NotifType.SAFETY_BLOCK
                    )
                } catch (_: Exception) {}
            }
            val wStats = com.lifecyclebot.perps.WatchlistEngine.getStats()
            addLog("🔔 Watchlist: ${wStats["watchlist_size"]} items, ${wStats["active_alerts"]} alerts")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Watchlist init failed (non-fatal): ${e.message}", e)
        }
        // Set up paper wallet balance tracking
        executor.onPaperBalanceChange = { delta ->
            // V5.9.21 FIX: previous guard (delta > currentBal*100) was silently
            // DROPPING real winning-sell credits, so trade journal showed +$10k
            // P&L while free cash stayed frozen at drawdown. The guard
            // self-reinforced because shrinking free cash shrank the threshold.
            //
            // New policy: only CLAMP absurd oracle spikes, never drop. A delta
            // is "insane" only if it's > 10,000x current balance AND > 500 SOL
            // absolute — otherwise credit exactly as-is. Even then we clamp
            // (don't drop) so the trade still moves the wallet in the right
            // direction, and we log loudly so the price-feed glitch is visible.
            val currentBal = status.paperWalletSol.coerceAtLeast(0.01)
            val ratio = if (currentBal > 0) delta / currentBal else 0.0
            val isInsane = delta > 500.0 && ratio > 10_000.0
            val applied = if (isInsane) {
                val capped = currentBal * 100.0
                ErrorLogger.warn("PaperWallet",
                    "INSANE DELTA CLAMPED: raw=${delta} (${ratio.toInt()}x bal=${currentBal}) — " +
                    "oracle/price glitch suspected — crediting capped=${capped} instead of dropping")
                capped
            } else {
                delta
            }

            status.paperWalletSol = (status.paperWalletSol + applied).coerceAtLeast(0.0)
            ErrorLogger.info("PaperWallet",
                "Δ=${"%.4f".format(applied)} SOL → balance=${"%.4f".format(status.paperWalletSol)}" +
                if (isInsane) " [CLAMPED from ${"%.4f".format(delta)}]" else "")

            // V5.9.8: Persist to SharedPrefs so balance survives app updates
            try {
                PaperWalletStore.persist(applicationContext, status.paperWalletSol)
            } catch (_: Exception) {}
        }
        
        // Persist running state so BootReceiver can restart after reboot
        getSharedPreferences(RUNTIME_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, true)
            .putBoolean(KEY_MANUAL_STOP_REQUESTED, false)
            .apply()
        userStartQueuedDuringStop = false
        // Acquire partial wake lock — keeps CPU alive during transaction confirmation
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecyclebot:trading").also {
            it.setReferenceCounted(false)  // idempotent — safe to call acquire() multiple times
            it.acquire()  // indefinite, released explicitly in stopBot()
        }
        
        // Schedule a repeating keep-alive alarm every 60 seconds
        // This ensures the service restarts if Android kills it
        scheduleKeepAliveAlarm()
        
        // Schedule WorkManager watchdog (more reliable than AlarmManager on newer Android)
        ServiceWatchdog.schedule(applicationContext)
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2 SHADOW LEARNING: Start BOTH shadow learning engines
        // - V1: Parameter variant testing
        // - V3: AI calibration shadow trades
        // Shadow learning is NEVER stopped - it's a constant learning state.
        // ═══════════════════════════════════════════════════════════════════
        try {
            // Start V1 parameter variant engine
            ShadowLearningEngine.start()
            
            // Start V3 AI calibration engine (processes blocked trades)
            com.lifecyclebot.v3.learning.ShadowLearningEngine.start(scope)
            
            addLog("🧠 ShadowLearning: V1 + V3 engines started")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "ShadowLearning start failed: ${e.message}", e)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.2 EMERGENT PATCH: Initialize RunTracker30D for proof tracking
        // ═══════════════════════════════════════════════════════════════════
        try {
            RunTracker30D.init(applicationContext)
            
            // If run is active, apply guardrails
            if (RunTracker30D.isRunActive()) {
                // Lock config changes during proof run
                EmergentGuardrails.disableConfigChanges()
                
                addLog("📊 RunTracker30D: Day ${RunTracker30D.getCurrentDay()} | " +
                    "Trades=${RunTracker30D.totalTrades} W=${RunTracker30D.wins} L=${RunTracker30D.losses}")
            } else {
                // No active run - prompt to start one for proof tracking
                addLog("📊 RunTracker30D: No active run (use long-press to start 30-day proof)")
            }
            
            // Start a run automatically if not started and we have an initial balance
            val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
            if (!RunTracker30D.isRunActive() && effectiveBalance > 0) {
                RunTracker30D.startRun(effectiveBalance)
                EmergentGuardrails.disableConfigChanges()
                addLog("🚀 RunTracker30D: 30-Day Run STARTED | Balance=${effectiveBalance.toInt()} SOL")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "RunTracker30D init failed: ${e.message}", e)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.2.5: Initialize EducationSubLayerAI (Harvard Brain) with persistence
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.init(applicationContext)
            addLog("🎓 Harvard Brain initialized | Layers=${com.lifecyclebot.v3.scoring.EducationSubLayerAI.runDiagnostics().count { it.value }} active")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "EducationSubLayerAI init failed: ${e.message}", e)
        }

        // V5.9.178 — LocalOrphanStore.reconcileAll is now DISABLED because the
        // new PerpsPositionStore rehydrates the actual positions at trader
        // init (AltPosition/MetalPosition/ForexPosition/CommodityPosition/
        // StockPosition). Refunding SOL on top of rehydration would
        // double-credit the paper balance. The orphan store still records
        // open positions for diagnostics but no longer mutates the wallet.
        try {
            com.lifecyclebot.collective.LocalOrphanStore.init(applicationContext)
            val snap = com.lifecyclebot.collective.LocalOrphanStore.snapshot()
            if (snap.isNotEmpty()) {
                addLog("📂 ${snap.size} paper positions tracked by orphan store (diagnostics only; PerpsPositionStore is the source of truth)")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "LocalOrphanStore init failed: ${e.message}", e)
        }
        
        // V5.9.484 — wire 4 new push-data integrations (PumpPortal WS for new
        // pump.fun launches, Helius Enhanced WS for whale-wallet txs, Pyth
        // Hermes SSE for ~400ms pricing on SOL/BTC/ETH, and the LLM client
        // for trade-decision validation). Each one fails open: the bot
        // continues running on REST polling if any of these fail to start.
        try { wireExternalStreams(cfg) } catch (e: Exception) {
            ErrorLogger.warn("BotService", "wireExternalStreams error: ${e.message}")
        }

        addLog("Bot started — paper=${cfg.paperMode} auto=${cfg.autoTrade} sounds=${cfg.soundEnabled}")
        ErrorLogger.info("BotService", "Bot started successfully")
        
        // Log watchdog stats for debugging
        val watchdogStats = ServiceWatchdog.getStats(applicationContext)
        addLog("🐕 Watchdog: ${watchdogStats.format()}")
        
        // Show Toast on UI thread for immediate feedback
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                "🚀 Bot Started\nPaper=${cfg.paperMode} | Learning applied",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
        // Send Telegram notification for bot start
        sendTradeNotif(
            "🚀 Bot Started",
            "Paper=${cfg.paperMode} | Auto=${cfg.autoTrade} | Sounds=${cfg.soundEnabled}",
            NotificationHistory.NotifEntry.NotifType.INFO
        )
        
        } catch (e: Exception) {
            // Catch any crash and log it with full stack trace to ErrorLogger
            ErrorLogger.crash("BotService", "startBot CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            addLog("❌ Bot start CRASH: ${e.javaClass.simpleName}: ${e.message}")
            android.util.Log.e("BotService", "startBot CRASH", e)
            e.printStackTrace()
            status.running = false
            // V5.9.495z11 — RANDOM RESTART FIX. If startBot crashes mid-flight
            // we MUST clear `was_running_before_shutdown` so ServiceWatchdog
            // does not see it 15 min later and re-fire ACTION_START in a loop.
            // Without this, a crash here causes the bot to "randomly start
            // on its own" every 15 minutes.
            try {
                getSharedPreferences("bot_runtime", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("was_running_before_shutdown", false).apply()
            } catch (_: Exception) {}
            try { ServiceWatchdog.cancel(applicationContext) } catch (_: Exception) {}
            try { cancelKeepAliveAlarm() } catch (_: Exception) {}
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
        }
    }

    /**
     * V5.9.73: Kick off a wallet connect in the background so startBot()
     * / in-loop reconnect never blocks the IO thread for 30-90s of
     * sequential RPC timeouts. Cancels any previous in-flight attempt.
     *
     * @param runReconciliation fire StartupReconciler once connected —
     *        true for cold start, false for mid-loop reconnect where
     *        we only want the wallet back online.
     */
    private fun launchWalletConnect(
        privateKeyB58: String,
        rpcUrl: String,
        runReconciliation: Boolean,
    ) {
        walletConnectJob?.cancel()
        walletConnectJob = scope.launch {
            try {
                val connected = walletManager.connect(privateKeyB58, rpcUrl)
                val cfgNow = ConfigStore.load(applicationContext)
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@launch

                if (connected && !cfgNow.paperMode) {
                    wallet = walletManager.getWallet()
                    addLog("✓ Wallet connected: ${walletManager.state.value.shortKey}")
                    ErrorLogger.info("BotService", "✓ Wallet connected in background")

                    // V5.9.105: live-safety circuit breaker — refuse live trades
                    // if the wallet starts below 0.1 SOL (dust bleeds fees on
                    // every Jupiter swap). Also seeds session-PnL halt.
                    try {
                        val liveSol = wallet?.getSolBalance() ?: 0.0
                        LiveSafetyCircuitBreaker.beginSession(liveSol)
                        if (LiveSafetyCircuitBreaker.isTripped()) {
                            addLog("🚨 LIVE HALT: ${LiveSafetyCircuitBreaker.trippedReason()}")
                            sendTradeNotif(
                                "🚨 Live Trading Disabled",
                                LiveSafetyCircuitBreaker.trippedReason(),
                                NotificationHistory.NotifEntry.NotifType.INFO,
                            )
                        }
                    } catch (e: Exception) {
                        addLog("⚠️ Could not seed live safety CB: ${e.message}")
                    }

                    if (runReconciliation) {
                        val liveWallet = wallet
                        if (liveWallet != null) {
                            try {
                                val reconciler = StartupReconciler(
                                    wallet  = liveWallet,
                                    status  = status,
                                    onLog   = { msg -> addLog(msg) },
                                    onAlert = { title, body ->
                                        sendTradeNotif(title, body,
                                            NotificationHistory.NotifEntry.NotifType.INFO)
                                    },
                                    executor = executor,
                                    autoSellOrphans = false,  // V5.9.102: prefer adoption over sell
                                )
                                reconciler.reconcile()
                            } catch (e: Exception) {
                                addLog("Reconciliation error: ${e.message}")
                            }
                        }
                    }
                } else if (!connected) {
                    val msg = walletManager.state.value.errorMessage.ifBlank { "unknown" }
                    addLog("⚠️ Wallet connect failed: $msg")
                    ErrorLogger.warn("BotService", "Wallet connect failed: $msg")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                addLog("Wallet connect cancelled")
                throw e
            } catch (e: Exception) {
                addLog("⚠️ Wallet connect error: ${e.message}")
                ErrorLogger.warn("BotService", "Wallet connect exception: ${e.message}")
            }
        }
    }

    fun stopBot() {
        // V5.9.148 — gate so a concurrent ACTION_START queues instead of racing
        // the tail of this method. Cleared in the `finally` block below.
        stopInProgress = true
        status.running = false  // visible immediately; stopInProgress remains the cleanup truth
        try {
            try {
                getSharedPreferences(RUNTIME_PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
                    .putBoolean(KEY_MANUAL_STOP_REQUESTED, true)
                    .apply()
            } catch (_: Exception) {}
        cancelAllRestartAlarms()
        try { ServiceWatchdog.cancel(applicationContext) } catch (_: Exception) {}
        addLog("Stopping bot...")

        // V5.9.73: kill any in-flight wallet connect so a 90-second RPC
        // timeout from the previous live-start doesn't leak past the stop
        // and block the next paper-mode start from going clean.
        walletConnectJob?.cancel()
        walletConnectJob = null

        // V5.9.495z20 — stop recovery loop on bot stop.
        try {
            com.lifecyclebot.engine.execution.RecoveryExecutionLoop.stop()
        } catch (_: Exception) {}

        // V5.9.495z22 — stop reconciler loop on bot stop.
        try {
            com.lifecyclebot.engine.execution.PositionWalletReconciler.stop()
        } catch (_: Exception) {}

        // V5.9.495z24 — stop background discovery loop.
        try {
            com.lifecyclebot.perps.DynamicAltTokenRegistry.stopBackgroundDiscovery()
        } catch (_: Exception) {}
        
        // V5.7.8: In paper mode, purge only obviously bad trades (not all history)
        try {
            val cfg = ConfigStore.load(applicationContext)
            if (cfg.paperMode) {
                // Only remove trades with absurd PnL (>10000% or <-100%) — these are decimal errors
                val allTrades = TradeHistoryStore.getAllTrades()
                val badCount = allTrades.count { it.pnlPct > 10_000 || it.pnlPct < -100 }
                if (badCount > 0) {
                    // Clear and re-add only good trades
                    val goodTrades = allTrades.filter { it.pnlPct <= 10_000 && it.pnlPct >= -100 }
                    TradeHistoryStore.clearAllTrades()
                    TradeHistoryStore.recordTrades(goodTrades)
                    addLog("Purged $badCount bad trades (PnL > 10000% or < -100%) — kept ${goodTrades.size} good trades")
                }
                
                // Reset paper wallet ONLY if it's clearly inflated (>100x starting balance)
                val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 130.0
                val targetSol = 1000.0 / solPrice
                if (status.paperWalletSol > targetSol * 100) {
                    status.paperWalletSol = targetSol
                    status.paperWalletLastRefreshMs = System.currentTimeMillis()
                    addLog("Paper wallet was inflated — reset to ${String.format("%.2f", targetSol)} SOL (~\$1,000)")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "Paper purge error (non-fatal): ${e.message}")
        }
        
        // IMPORTANT: Close all open positions BEFORE stopping (if enabled in config)
        // This ensures funds are returned and no positions are left dangling
        try {
            val cfg = ConfigStore.load(applicationContext)
            
            if (cfg.closePositionsOnStop) {
                val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)

                // V5.9.454 — STOP-PATH WALLET RECOVERY.
                // On live stop, the local `wallet` var may have been
                // nulled by an earlier RPC hiccup (see WalletManager
                // preservation fix) or by the old launchWalletConnect
                // race. If it's null here, held tokens would stay
                // stranded in the host wallet because closeAllPositions
                // and liveSweepWalletTokens both need a live wallet.
                //
                // Do one synchronous attemptReconnect before giving up
                // so the user's positions actually liquidate.
                if (!cfg.paperMode && wallet == null) {
                    try {
                        ErrorLogger.warn("BotService",
                            "🚨 STOP: live wallet is null — attempting reconnect so held tokens can be swept")
                        addLog("🚨 STOP: reconnecting wallet to sell held tokens…")
                        val recovered = com.lifecyclebot.engine.WalletManager.attemptReconnect()
                        if (recovered != null) {
                            wallet = recovered
                            addLog("✅ Wallet reconnected for shutdown sweep")
                            ErrorLogger.info("BotService", "✅ STOP: wallet recovered for shutdown sweep")
                        } else {
                            ErrorLogger.error("BotService",
                                "❌ STOP: wallet reconnect failed — held tokens will remain in host wallet")
                            addLog("❌ STOP: wallet reconnect failed — held tokens will NOT be sold")
                        }
                    } catch (e: Exception) {
                        ErrorLogger.error("BotService", "STOP reconnect threw: ${e.message}", e)
                    }
                }
                
                // Get a synchronized copy of tokens to avoid ConcurrentModificationException
                val tokensCopy = synchronized(status.tokens) {
                    status.tokens.toMap()
                }
                
                val closedCount = executor.closeAllPositions(
                    tokens = tokensCopy,
                    wallet = wallet,
                    walletSol = effectiveBalance,
                    paperMode = cfg.paperMode,
                )
                
                if (closedCount > 0) {
                    addLog("✅ Closed $closedCount position(s) before shutdown")
                }

                // ═══════════════════════════════════════════════════════════════════
                // V5.9.261 — LIVE WALLET SWEEP (chronic 1-month sell bug fix)
                // Layer traders (ShitCoin/Moonshot/BlueChip/Manip/Express/Quality)
                // do only in-memory accounting in their closePosition() paths —
                // they never broadcast a Jupiter sell. Sweep the wallet now so
                // every meme/altcoin actually returns to SOL before shutdown.
                // Stablecoins + SOL are preserved.
                // ═══════════════════════════════════════════════════════════════════
                if (!cfg.paperMode && wallet != null) {
                    try {
                        val swept = executor.liveSweepWalletTokens(wallet!!, effectiveBalance)
                        if (swept > 0) {
                            addLog("💰 Wallet sweep: liquidated $swept on-chain holding(s) to SOL")
                        }
                    } catch (sweepEx: Exception) {
                        ErrorLogger.error("BotService", "Wallet sweep failed: ${sweepEx.message}", sweepEx)
                        addLog("⚠️ Wallet sweep error: ${sweepEx.message?.take(80)}")
                    }
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V4.0 FIX: Also close Treasury Mode positions
                // Treasury tracks positions separately in CashGenerationAI
                // ═══════════════════════════════════════════════════════════════════
                try {
                    val treasuryPositions = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()
                    if (treasuryPositions.isNotEmpty()) {
                        addLog("💰 Closing ${treasuryPositions.size} Treasury position(s)...")
                        for (tPos in treasuryPositions) {
                            try {
                                val ts = tokensCopy[tPos.mint]
                                val currentPrice = ts?.lastPrice ?: ts?.ref ?: tPos.entryPrice
                                
                                // Close the treasury position tracking
                                com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                                    mint = tPos.mint,
                                    exitPrice = currentPrice,
                                    exitReason = com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TIME_EXIT
                                )
                                recentlyClosedMs[tPos.mint] = System.currentTimeMillis()
                                addLog("💰 Closed Treasury position: ${tPos.symbol}", tPos.mint)
                            } catch (te: Exception) {
                                addLog("⚠️ Failed to close Treasury ${tPos.symbol}: ${te.message}", tPos.mint)
                            }
                        }
                    }
                } catch (treasuryEx: Exception) {
                    ErrorLogger.error("BotService", "Error closing Treasury positions: ${treasuryEx.message}", treasuryEx)
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V4.0 FIX: Also close Blue Chip positions
                // ═══════════════════════════════════════════════════════════════════
                try {
                    val blueChipPositions = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
                    if (blueChipPositions.isNotEmpty()) {
                        addLog("🔵 Closing ${blueChipPositions.size} Blue Chip position(s)...")
                        for (bcPos in blueChipPositions) {
                            try {
                                val ts = tokensCopy[bcPos.mint]
                                val currentPrice = ts?.lastPrice ?: ts?.ref ?: bcPos.entryPrice
                                
                                com.lifecyclebot.v3.scoring.BlueChipTraderAI.closePosition(
                                    mint = bcPos.mint,
                                    exitPrice = currentPrice,
                                    exitReason = com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TIME_EXIT
                                )
                                addLog("🔵 Closed Blue Chip position: ${bcPos.symbol}", bcPos.mint)
                            } catch (bcEx: Exception) {
                                addLog("⚠️ Failed to close BlueChip ${bcPos.symbol}: ${bcEx.message}", bcPos.mint)
                            }
                        }
                    }
                } catch (bcEx: Exception) {
                    ErrorLogger.error("BotService", "Error closing BlueChip positions: ${bcEx.message}", bcEx)
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V4.0 FIX: Also close ShitCoin positions
                // ═══════════════════════════════════════════════════════════════════
                try {
                    val shitCoinPositions = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()
                    if (shitCoinPositions.isNotEmpty()) {
                        addLog("💩 Closing ${shitCoinPositions.size} ShitCoin position(s)...")
                        for (scPos in shitCoinPositions) {
                            try {
                                val ts = tokensCopy[scPos.mint]
                                val currentPrice = ts?.lastPrice ?: ts?.ref ?: scPos.entryPrice
                                
                                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(
                                    mint = scPos.mint,
                                    exitPrice = currentPrice,
                                    exitReason = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.TIME_EXIT
                                )
                                addLog("💩 Closed ShitCoin position: ${scPos.symbol}", scPos.mint)
                            } catch (scEx: Exception) {
                                addLog("⚠️ Failed to close ShitCoin ${scPos.symbol}: ${scEx.message}", scPos.mint)
                            }
                        }
                    }
                    // V5.2: Force clear all ShitCoin positions to ensure UI updates
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.clearAllPositions()
                } catch (scEx: Exception) {
                    ErrorLogger.error("BotService", "Error closing ShitCoin positions: ${scEx.message}", scEx)
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V5.2: Also close ShitCoinExpress positions
                // ═══════════════════════════════════════════════════════════════════
                try {
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.clearAllRides()
                    addLog("💩🚂 Cleared ShitCoin Express rides")
                } catch (scEx: Exception) {
                    ErrorLogger.error("BotService", "Error clearing ShitCoin Express: ${scEx.message}", scEx)
                }

                // ☠️ Clear Manipulated positions
                try {
                    com.lifecyclebot.v3.scoring.ManipulatedTraderAI.clearAll()
                    addLog("☠️ Cleared Manipulated positions")
                } catch (mEx: Exception) {
                    ErrorLogger.error("BotService", "Error clearing Manipulated positions: ${mEx.message}", mEx)
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V5.2.12: Also close Quality positions
                // ═══════════════════════════════════════════════════════════════════
                try {
                    val qualityPositions = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions()
                    if (qualityPositions.isNotEmpty()) {
                        addLog("⭐ Closing ${qualityPositions.size} Quality position(s)...")
                        for (qPos in qualityPositions) {
                            try {
                                val ts = tokensCopy[qPos.mint]
                                val currentPrice = ts?.lastPrice ?: ts?.ref ?: qPos.entryPrice
                                
                                com.lifecyclebot.v3.scoring.QualityTraderAI.closePosition(
                                    mint = qPos.mint,
                                    exitPrice = currentPrice,
                                    exitSignal = com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.TIME_EXIT
                                )
                                addLog("⭐ Closed Quality position: ${qPos.symbol}", qPos.mint)
                            } catch (qEx: Exception) {
                                addLog("⚠️ Failed to close Quality ${qPos.symbol}: ${qEx.message}", qPos.mint)
                            }
                        }
                    }
                } catch (qEx: Exception) {
                    ErrorLogger.error("BotService", "Error closing Quality positions: ${qEx.message}", qEx)
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V5.2.12: Also close Moonshot positions
                // ═══════════════════════════════════════════════════════════════════
                try {
                    val moonshotPositions = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
                    if (moonshotPositions.isNotEmpty()) {
                        addLog("🚀 Closing ${moonshotPositions.size} Moonshot position(s)...")
                        for (mPos in moonshotPositions) {
                            try {
                                val ts = tokensCopy[mPos.mint]
                                val currentPrice = ts?.lastPrice ?: ts?.ref ?: mPos.entryPrice
                                
                                com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(
                                    mint = mPos.mint,
                                    exitPrice = currentPrice,
                                    exitReason = com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.TIMEOUT
                                )
                                addLog("🚀 Closed Moonshot position: ${mPos.symbol}", mPos.mint)
                            } catch (mEx: Exception) {
                                addLog("⚠️ Failed to close Moonshot ${mPos.symbol}: ${mEx.message}", mPos.mint)
                            }
                        }
                    }
                } catch (mEx: Exception) {
                    ErrorLogger.error("BotService", "Error closing Moonshot positions: ${mEx.message}", mEx)
                }
                
                // Also purge any orphaned tokens (live mode only)
                if (!cfg.paperMode && wallet != null) {
                    purgeOrphanedTokensOnStop(cfg)
                }
            } else {
                val openCount = synchronized(status.tokens) {
                    status.tokens.values.count { it.position.isOpen }
                }
                val treasuryCount = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions().size
                val blueChipCount = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().size
                val shitCoinCount = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().size
                val qualityCount = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().size
                val moonshotCount = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().size
                val totalOpen = openCount + treasuryCount + blueChipCount + shitCoinCount + qualityCount + moonshotCount
                
                if (totalOpen > 0) {
                    addLog("⚠️ $totalOpen position(s) left open (closePositionsOnStop=false) | " +
                        "main=$openCount treasury=$treasuryCount bluechip=$blueChipCount shitcoin=$shitCoinCount quality=$qualityCount moonshot=$moonshotCount")
                }
            }
        } catch (e: Exception) {
            addLog("⚠️ Error closing positions on shutdown: ${e.message}")
            ErrorLogger.error("BotService", "Error closing positions on shutdown: ${e.message}", e)
        }
        
        // V5.2 FIX: ALWAYS clear all layer positions when bot stops, regardless of closePositionsOnStop setting
        // This ensures the UI doesn't show stale positions after a bot stop/crash
        try {
            com.lifecyclebot.v3.scoring.CashGenerationAI.clearAllPositions()
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.clearAllPositions()
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.clearAllPositions()
            com.lifecyclebot.v3.scoring.ShitCoinExpress.clearAllRides()
            com.lifecyclebot.v3.scoring.ManipulatedTraderAI.clearAll()
            com.lifecyclebot.v3.scoring.QualityTraderAI.clearAllPositions()
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.clearAllPositions()
            addLog("✅ Cleared all layer position tracking")
        } catch (clearEx: Exception) {
            ErrorLogger.error("BotService", "Error clearing layer positions: ${clearEx.message}", clearEx)
        }
        
        // V5.2.12 FIX: Also clear position.isOpen flags in status.tokens
        // This is what the UI reads - critical to prevent stale position display
        // Note: isOpen is a computed property (qtyToken > 0), so we replace with empty Position
        try {
            synchronized(status.tokens) {
                status.tokens.values.forEach { ts ->
                    if (ts.position.isOpen) {
                        ts.position = Position()  // Replace with empty position to clear isOpen
                        ErrorLogger.debug("BotService", "Cleared position for ${ts.symbol}")
                    }
                }
            }
            addLog("✅ Cleared all token position flags")
        } catch (tokensEx: Exception) {
            ErrorLogger.error("BotService", "Error clearing token positions: ${tokensEx.message}", tokensEx)
        }

        // V5.6: Clear the watchlist so UI shows clean state after stop
        // Learning data is persisted in trade DB — status.tokens is just the live runtime cache
        try {
            synchronized(status.tokens) {
                status.tokens.clear()
            }
            GlobalTradeRegistry.reset()
            addLog("✅ Cleared watchlist — UI reset to clean state")
        } catch (clearEx: Exception) {
            ErrorLogger.error("BotService", "Error clearing watchlist on stop: ${clearEx.message}", clearEx)
        }
        
        // V5.6.10 FIX: Also clear PositionPersistence so positions don't restore on restart
        // Without this, positions reappear after bot stop → start cycle
        try {
            PositionPersistence.clear()
            addLog("✅ Cleared position persistence — positions won't restore on restart")
        } catch (persistEx: Exception) {
            ErrorLogger.error("BotService", "Error clearing position persistence: ${persistEx.message}", persistEx)
        }

        status.running = false
        loopJob?.cancel()
        orchestrator?.stop()
        orchestrator = null
        marketScanner?.stop(); marketScanner = null
        // V5.9.484 — disconnect external push streams + LLM client
        try { unwireExternalStreams() } catch (_: Exception) {}
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        networkCallback = null
        TreasuryManager.save(applicationContext)
        botBrain?.stop(); botBrain = null
        tradeDb?.close(); tradeDb = null
        // Cancel keep-alive alarm
        cancelKeepAliveAlarm()
        // Cancel watchdog (user explicitly stopped)
        ServiceWatchdog.cancel(applicationContext)
        // Stop self-healing diagnostics
        try {
            SelfHealingDiagnostics.stop()
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "SelfHealingDiagnostics stop error: ${e.message}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // NOTE: ShadowLearningEngine is NOT stopped here!
        // Shadow learning should continue running persistently as a constant
        // state of learning, even when the bot is stopped or paper mode is off.
        // It will keep tracking market data and learning from opportunities.
        // ═══════════════════════════════════════════════════════════════════
        
        // V5.9.40/42: Clear the "was running" flag FIRST (before stopForeground/stopSelf).
        // Previously this happened AFTER stopSelf() so a fast process-kill dropped
        // the write and AATEApp saw stale wasRunning=true on next launch → silent
        // auto-restart.
        //
        // V5.9.42: Use .apply() (async) not .commit() — .commit() does sync disk IO
        // on the main thread and triggered an ANR on stop. Because the write is
        // queued BEFORE all the heavy shutdown work (TreasuryManager.save, trader
        // closeAll, stopForeground/stopSelf), SharedPreferences has plenty of time
        // to flush before the process dies.
        try {
            getSharedPreferences(RUNTIME_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
                .putBoolean(KEY_MANUAL_STOP_REQUESTED, !userStartQueuedDuringStop)
                .apply()
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to clear was_running flag: ${e.message}", e)
        }

        // REMOVED: walletManager.disconnect() 
        // V5.9.157 — reorder teardown so every trader.stop() and
        // closeAllPositions() runs BEFORE stopForeground/stopSelf. Previously
        // stopSelf() fired first and the remaining coroutine kept running on
        // the BotService scope; Android then auto-recreated the service via
        // START_STICKY (user log 00:08:37 'onCreate starting'), and the OLD
        // coroutine's tail reached trader.stop() 24s later, stopping the
        // NEW service's traders (user log 00:08:50 'CryptoAltTrader STOPPED'
        // AFTER the new instance started at 00:08:37). Symptom: bot refused
        // to restart no matter how many times the user tapped Start.
        //
        // V5.9.5: Close all Markets positions then stop all traders when main bot stops
        try {
            com.lifecyclebot.perps.TokenizedStockTrader.closeAllPositions()
            com.lifecyclebot.perps.CommoditiesTrader.closeAllPositions()
            com.lifecyclebot.perps.MetalsTrader.closeAllPositions()
            com.lifecyclebot.perps.ForexTrader.closeAllPositions()
            com.lifecyclebot.perps.CryptoAltTrader.closeAllPositions()
            kotlinx.coroutines.runBlocking { com.lifecyclebot.perps.PerpsExecutionEngine.closeAllPositions() }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Error closing markets positions: ${e.message}", e)
        }
        try {
            com.lifecyclebot.perps.TokenizedStockTrader.stop()
            com.lifecyclebot.perps.CommoditiesTrader.stop()
            com.lifecyclebot.perps.MetalsTrader.stop()
            com.lifecyclebot.perps.ForexTrader.stop()
            com.lifecyclebot.perps.CryptoAltTrader.stop()
            com.lifecyclebot.perps.PerpsExecutionEngine.stop()
            ErrorLogger.info("BotService", "All Markets traders stopped + positions closed alongside main bot")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Error stopping markets traders: ${e.message}", e)
        }

        // V4.0: Reset initialization flags so services can reinit on next start
        tradingModesInitialized = false
        allTradingLayersReady = false

        // Wallet should ONLY disconnect when user explicitly requests it
        // This allows wallet to stay connected when:
        // - Bot is stopped
        // - App is minimized
        // - App crashes and restarts
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        
        addLog("Bot stopped. All positions closed. Wallet remains connected.")
        
        // Show Toast on UI thread for immediate feedback
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                "🛑 Bot Stopped\nAll positions closed",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
        // Send Telegram notification for bot stop
        sendTradeNotif(
            "🛑 Bot Stopped",
            "All positions closed. Wallet remains connected.",
            NotificationHistory.NotifEntry.NotifType.INFO
        )
        } finally {
            // V5.9.148 — always clear, even on early return/exception, so any
            // queued restart in onStartCommand can proceed.
            stopInProgress = false
        }
    }
    
    // Keep-alive alarm to ensure service restarts if killed
    private fun scheduleKeepAliveAlarm() {
        val restartIntent = Intent(applicationContext, BotService::class.java).apply {
            action = ACTION_START
        }
        val pi = android.app.PendingIntent.getService(
            this, 999, restartIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(android.app.AlarmManager::class.java)
        
        // V5.6.8: Use setAlarmClock for MAXIMUM priority - this is treated as user-facing
        // and survives aggressive OEM battery optimizations (Samsung, Xiaomi, etc.)
        val triggerTime = System.currentTimeMillis() + 90_000  // 1.5 minutes
        try {
            // Create a show intent that opens MainActivity when alarm fires
            val showIntent = Intent(applicationContext, com.lifecyclebot.ui.MainActivity::class.java)
            val showPi = android.app.PendingIntent.getActivity(
                this, 998, showIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // setAlarmClock is the HIGHEST priority - almost never deferred
            am?.setAlarmClock(
                android.app.AlarmManager.AlarmClockInfo(triggerTime, showPi),
                pi
            )
            ErrorLogger.info("BotService", "Keep-alive AlarmClock scheduled for 90 seconds (HIGH PRIORITY)")
        } catch (e: Exception) {
            // Fallback to setExactAndAllowWhileIdle if setAlarmClock fails
            ErrorLogger.warn("BotService", "setAlarmClock failed, using fallback: ${e.message}")
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pi
            )
            ErrorLogger.info("BotService", "Keep-alive alarm scheduled for 90 seconds (fallback)")
        }
        
        // Also schedule a backup alarm at 3 minutes using setExactAndAllowWhileIdle
        val backupPi = android.app.PendingIntent.getService(
            this, 997, restartIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am?.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 180_000,  // 3 minutes backup
            backupPi
        )
        
        // Start self-healing diagnostics (runs every 3 hours)
        try {
            SelfHealingDiagnostics.start(scope)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "Failed to start SelfHealingDiagnostics: ${e.message}")
        }
    }
    
    private fun cancelAllRestartAlarms() {
        val restartIntent = Intent(applicationContext, BotService::class.java).apply {
            action = ACTION_START
        }
        val am = getSystemService(android.app.AlarmManager::class.java)
        for (requestCode in intArrayOf(1, 2, 3, 997, 999)) {
            try {
                val pi = android.app.PendingIntent.getService(
                    this,
                    requestCode,
                    restartIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                am?.cancel(pi)
                pi.cancel()
            } catch (_: Exception) {}
        }
        ErrorLogger.info("BotService", "All restart alarms cancelled")
    }

    private fun cancelKeepAliveAlarm() {
        cancelAllRestartAlarms()
        ErrorLogger.info("BotService", "Keep-alive alarms cancelled")
    }

    // ── V5.9.484 — external push-data streams + LLM ───────────────────

    /**
     * Wire the four push integrations introduced in V5.9.484:
     *   • PumpPortal WS  → pump.fun new launches + migrations (5–30s before
     *                      DexScreener/Birdeye see them)
     *   • Helius LaserStream → push notifications for whale-wallet txs
     *                      (replaces InsiderWalletTracker REST polling)
     *   • Pyth Hermes SSE → ~400ms price updates for SOL/BTC/ETH (drives
     *                      faster trailing-stop and profit-lock triggers)
     *   • EmergentLlmClient → Claude-Sonnet-4.5 risk validator + exit
     *                      narrator (opt-in: requires a personal sk-ant-…
     *                      key in cfg.geminiApiKey; the Emergent universal
     *                      key cannot be used directly from a native Kotlin
     *                      HTTP client because the proxy only ships in the
     *                      Python emergentintegrations SDK)
     *
     * Each stream fails open: if any one cannot start, the bot continues on
     * its existing REST-based pipelines. Safe to call multiple times — the
     * inner objects all guard against double-start.
     */
    private fun wireExternalStreams(cfg: com.lifecyclebot.data.BotConfig) {
        // 1) PumpPortal WS — new pump.fun launches + migrations
        try {
            com.lifecyclebot.network.PumpFunWS.start(
                onNewToken = { mint, symbol, name, mcapSol ->
                    try {
                        // V5.9.629 — PumpPortal is the high-throughput Meme launch feed
                        // that made builds 2489/2490 ramp to ~40 open positions. It must
                        // follow the same Meme-enabled semantics as the scanner and main
                        // loop, not stale auto-add/V3/autoTrade toggles. If Meme Trader is
                        // enabled or tradingMode is Meme/Both, admit fresh pump.fun launches.
                        // V5.9.632 — keep parity with startBot scanner gate + botLoop meme gate
                        // (V5.9.631 added `|| status.running` in those two but missed THIS feed,
                        // which is the highest-volume Meme intake stream). Scanner-gate parity:
                        // if the bot is up, PumpPortal feeds the protected intake.
                        val liveCfg = ConfigStore.load(applicationContext)
                        val shouldAdmit = liveCfg.memeTraderEnabled ||
                            liveCfg.tradingMode == 0 ||
                            liveCfg.tradingMode == 2 ||
                            liveCfg.autoAddNewTokens ||
                            liveCfg.v3EngineEnabled ||
                            liveCfg.autoTrade ||
                            status.running
                        if (shouldAdmit) {
                            lastScannerDiscoveryMs = System.currentTimeMillis()
                            try { marketScanner?.recordNewTokenFound() } catch (_: Throwable) {}
                            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                                .takeIf { it > 0.0 } ?: 150.0
                            val mcapUsd = mcapSol * solUsd
                            // pump.fun bonding-curve liquidity ≈ mcap pre-graduation.
                            val estLiq = (mcapUsd * 0.85).coerceAtLeast(0.0)
                            admitProtectedMemeIntake(
                                mint = mint,
                                symbol = symbol,
                                name = name.ifBlank { symbol },
                                source = "PUMP_PORTAL_WS",
                                marketCapUsd = mcapUsd,
                                liquidityUsd = estLiq,
                                volumeH1 = 0.0,
                                confidence = 80,
                                allSources = setOf("PUMP_PORTAL_WS", "PUMP_PORTAL"),
                                playSound = true,
                                operatorLog = true,
                            )
                            ErrorLogger.info(
                                "BotService",
                                "🆕 PumpPortal protected intake: $symbol ($name) mcap=${mcapSol.toInt()}SOL liqEst=\$${estLiq.toInt()}"
                            )
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "PumpPortal protected intake error: ${e.message}")
                    }
                },
                onMigration = { mint ->
                    ErrorLogger.info("BotService", "🚀 PumpPortal migration: ${mint.take(8)}…")
                },
            )
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "PumpFunWS start failed: ${e.message}")
        }

        // 2) Helius Enhanced WS — push whale-wallet activity
        try {
            if (cfg.heliusApiKey.isNotBlank()) {
                val whaleAddrs = try {
                    com.lifecyclebot.perps.InsiderWalletTracker.getTrackedWallets()
                        .map { it.address }
                        .filter { it.isNotBlank() }
                } catch (_: Exception) { emptyList() }
                if (whaleAddrs.isNotEmpty()) {
                    com.lifecyclebot.network.HeliusEnhancedWS.start(
                        heliusApiKey = cfg.heliusApiKey,
                        watchAccounts = whaleAddrs,
                    ) { sig, accounts, _ ->
                        // Touch the existing tracker so its UI stats keep
                        // ticking. Real-time tx interpretation is left to the
                        // existing REST-based scanForSignals() pass — this WS
                        // is a fast notifier so the bot doesn't sleep on a
                        // whale move while waiting for the next 5-min poll.
                        try {
                            ErrorLogger.info("BotService",
                                "🐳 PUSH: whale tx ${sig.take(10)}… (${accounts.size} accounts)")
                            scope.launch {
                                try { com.lifecyclebot.perps.InsiderWalletTracker.scanForSignals() }
                                catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    ErrorLogger.info("BotService",
                        "HeliusEnhancedWS skipped — no tracked whale wallets to subscribe to")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "HeliusEnhancedWS start failed: ${e.message}")
        }

        // 3) Pyth Hermes SSE — ~400ms SOL/BTC/ETH price stream
        try {
            val solFeed = "ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d"
            val btcFeed = "e62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43"
            val ethFeed = "ff61491a931112ddf1bd8147cd1b641375f79f5825126d665480874634fd0ace"
            com.lifecyclebot.network.PythHermesStream.subscribe(
                feedHexIds = listOf(solFeed, btcFeed, ethFeed),
            ) { feedId, priceUsd, _, _ ->
                try {
                    when (feedId) {
                        solFeed -> com.lifecyclebot.engine.WalletManager.lastKnownSolPrice = priceUsd
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "PythHermesStream start failed: ${e.message}")
        }

        // 4) EmergentLlmClient — Claude Sonnet 4.5 (opt-in, personal key only)
        try {
            val key = cfg.geminiApiKey.trim()
            if (key.startsWith("sk-ant-")) {
                com.lifecyclebot.network.EmergentLlmClient.configure(apiKey = key)
            } else {
                ErrorLogger.info("BotService",
                    "EmergentLlmClient disabled — paste a personal Anthropic key (sk-ant-…) into " +
                    "BotConfig.geminiApiKey to enable Claude trade-risk validation. The Emergent " +
                    "universal sk-emergent-… key cannot be used from native Kotlin (proxy is " +
                    "Python-SDK only).")
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "EmergentLlmClient configure failed: ${e.message}")
        }
    }

    private fun unwireExternalStreams() {
        try { com.lifecyclebot.network.PumpFunWS.stop() } catch (_: Exception) {}
        try { com.lifecyclebot.network.HeliusEnhancedWS.stop() } catch (_: Exception) {}
        try { com.lifecyclebot.network.PythHermesStream.unsubscribeAll() } catch (_: Exception) {}
        // EmergentLlmClient is request-scoped — nothing to disconnect.
    }

    // ── main loop ──────────────────────────────────────────

    /**
     * RAPID STOP-LOSS MONITOR
     * Runs every 500ms to check ALL open positions against hard floor stop loss.
     * This catches catastrophic losses that the main loop (5-10sec cycle) might miss.
     */
    private suspend fun rapidStopLossMonitor() {
        ErrorLogger.info("BotService", "🛡️ Rapid Stop-Loss Monitor STARTED (adaptive 500ms / 5s cycle)")
        
        val HARD_FLOOR_STOP_PCT = 15.0  // ABSOLUTE MAX LOSS - NEVER EXCEEDED
        val CHECK_INTERVAL_MS = 500L      // Check every 500ms when positions are open
        // V5.9.484 — BATTERY: when zero positions are open, sleep 5s instead
        // of busy-looping at 500ms. Catastrophe protection only matters when
        // there is actually capital at risk; before any BUY the rapid-stop
        // monitor was burning ~7,200 tight cycles/hour for nothing.
        val IDLE_INTERVAL_MS = 5_000L
        
        while (status.running) {
            try {
                val cfg = ConfigStore.load(applicationContext)
                val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                
                // Get all open positions
                val openPositions = synchronized(status.tokens) {
                    status.tokens.values.filter { it.position.isOpen }.toList()
                }
                // V5.9.484 — adaptive idle sleep when no positions are open
                if (openPositions.isEmpty()) {
                    kotlinx.coroutines.delay(IDLE_INTERVAL_MS)
                    continue
                }
                
                for (ts in openPositions) {
                    try {
                        // Get current price - use the most recent available
                        val currentPrice = ts.lastPrice.takeIf { it > 0 }
                            ?: ts.history.lastOrNull()?.priceUsd
                            ?: continue  // Can't check without price
                        
                        val entryPrice = ts.position.entryPrice
                        if (entryPrice <= 0) continue
                        
                        // Calculate PnL
                        val pnlPct = ((currentPrice - entryPrice) / entryPrice) * 100
                        
                        // V5.9.363 — TIGHTENED HARD FLOOR.
                        // Old rapid-stop fired at -15% but real positions kept
                        // hitting -69% because price feeds dropped during fast
                        // rugs and `currentPrice` went stale. We now also fire
                        // a CATASTROPHE COOLDOWN whenever we observe pnl ≤ -25%
                        // — even though the hard floor sell is already in flight,
                        // the 30-min cooldown prevents the bot from instantly
                        // re-buying the same rug as soon as the price oracle
                        // catches up.
                        //
                        // V5.9.495z27 — REWRITE of z23 peak-protect.
                        // Operator screenshot evidence: TWIG peaked +45% → fell
                        // to -94%, USDS at -99%, HARRY at -95%, MOONCLUB at -73%,
                        // Winston at -24% — all way past their displayed -20%
                        // SL with no exit fired. Two compounding bugs:
                        //
                        //   1. DEAD ZONE between -15% and -25%: the dynamic
                        //      stop block below this guard had `pnlPct >
                        //      -HARD_FLOOR_STOP_PCT` so it never ran for
                        //      positions deeper than -15%. With z23 also
                        //      blocking the hard floor for peaked positions,
                        //      any peaked position falling -15% to -25% had
                        //      ZERO exit firing.
                        //
                        //   2. PEAK-PROTECT had no give-back ceiling: a
                        //      position that peaked +45% could give back
                        //      139 points before catastrophe (-25%) caught
                        //      it — by which time the LP was usually pulled
                        //      and the catastrophe sell failed.
                        //
                        // New logic — single linear if/else chain:
                        //   a. CATASTROPHE (≤ -25%) always fires.
                        //   b. DRAWDOWN_FROM_PEAK fires when peak ≥ +20% AND
                        //      (peak - pnl) ≥ 25 points. So a position that
                        //      peaked +45% exits no later than +20% pnl
                        //      (locking in +20% instead of becoming -94%).
                        //   c. HARD_FLOOR fires for never-winners (peak < +20%)
                        //      that hit -15%.
                        // The dynamic/trailing stop block below remains but
                        // its condition is fixed in the same change to allow
                        // it to run regardless of how deep the loss is.
                        val isCatastrophe = pnlPct <= -25.0
                        val peakGainPct   = ts.position.peakGainPct
                        val drawdownFromPeak = peakGainPct - pnlPct
                        val giveBackTrigger  = peakGainPct >= 20.0 && drawdownFromPeak >= 25.0
                        val neverWinner      = peakGainPct < 20.0

                        when {
                            isCatastrophe -> {
                                ErrorLogger.warn("BotService", "🚨 RAPID STOP (CATASTROPHE): ${ts.symbol} at ${pnlPct.toInt()}%")
                                addLog("🛑 RAPID CATASTROPHE STOP: ${ts.symbol} ${pnlPct.toInt()}% | EXIT")
                                executor.requestSell(
                                    ts = ts, reason = "RAPID_CATASTROPHE_STOP",
                                    wallet = wallet, walletSol = effectiveBalance
                                )
                                TradeStateMachine.startCatastropheCooldown(ts.mint, pnlPct)
                            }
                            giveBackTrigger -> {
                                // Peaked +20% or higher and gave back ≥25 points.
                                // Exit immediately — lock whatever's left of the win.
                                ErrorLogger.warn("BotService",
                                    "🚨 DRAWDOWN_FROM_PEAK: ${ts.symbol} pnl=${pnlPct.toInt()}% peak=${peakGainPct.toInt()}% drawdown=${drawdownFromPeak.toInt()}pts")
                                addLog("📉 DRAWDOWN STOP: ${ts.symbol} ${pnlPct.toInt()}% (peak +${peakGainPct.toInt()}% → -${drawdownFromPeak.toInt()}pts give-back)")
                                executor.requestSell(
                                    ts = ts, reason = "RAPID_DRAWDOWN_FROM_PEAK_STOP",
                                    wallet = wallet, walletSol = effectiveBalance
                                )
                                TradeStateMachine.startCooldown(ts.mint)
                            }
                            pnlPct <= -HARD_FLOOR_STOP_PCT && neverWinner -> {
                                // Never had a meaningful peak AND hit -15%. Cut losses.
                                ErrorLogger.warn("BotService", "🚨 RAPID STOP (HARD_FLOOR): ${ts.symbol} at ${pnlPct.toInt()}%")
                                addLog("🛑 RAPID HARD_FLOOR STOP: ${ts.symbol} ${pnlPct.toInt()}% | EXIT (never-winner)")
                                executor.requestSell(
                                    ts = ts, reason = "RAPID_HARD_FLOOR_STOP",
                                    wallet = wallet, walletSol = effectiveBalance
                                )
                                TradeStateMachine.startCooldown(ts.mint)
                            }
                            else -> { /* let dynamic/trailing stop below handle it */ }
                        }
                        
                        // V3.3: DYNAMIC FLUID STOP CHECK (moves with position)
                        val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
                        val holdTimeSecs = holdTimeMs / 1000.0
                        val peakPnlPct = ts.position.peakGainPct
                        val volatility = ts.volatility ?: 50.0
                        
                        val dynamicStopPct = try {
                            val modeDefault = cfg.stopLossPct
                            com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                                modeDefaultStop = modeDefault,
                                currentPnlPct = pnlPct,
                                peakPnlPct = peakPnlPct,
                                holdTimeSeconds = holdTimeSecs,
                                volatility = volatility
                            )
                        } catch (_: Exception) {
                            // Fallback to static fluid stop
                            try {
                                -com.lifecyclebot.v3.scoring.FluidLearningAI.getFluidStopLoss(cfg.stopLossPct)
                            } catch (_: Exception) { -cfg.stopLossPct }
                        }
                        
                        // Dynamic stop is already negative
                        // V5.9.495z27 — was `pnlPct > -HARD_FLOOR_STOP_PCT`,
                        // which created a DEAD ZONE between -15% and -25% where
                        // neither the hard-floor block above nor this trailing
                        // block fired. Removed the condition so the dynamic
                        // stop runs at every pnl depth. The when-block above
                        // already handles -25% catastrophe and -15% hard-floor
                        // exits with appropriate reason codes; this trailing
                        // path only matters for non-catastrophe / non-floor
                        // exits driven by FluidLearningAI's adaptive stop.
                        if (pnlPct <= dynamicStopPct) {
                            val stopType = when {
                                peakPnlPct > 5.0 -> "TRAILING"
                                holdTimeSecs < 60 -> "ENTRY_PROTECT"
                                else -> "FLUID"
                            }
                            ErrorLogger.warn("BotService", "⚠️ RAPID $stopType STOP: ${ts.symbol} at ${pnlPct.toInt()}% (limit=${dynamicStopPct.toInt()}%)")
                            addLog("🛑 RAPID $stopType STOP: ${ts.symbol} ${pnlPct.toInt()}%")
                            
                            executor.requestSell(
                                ts = ts,
                                reason = "RAPID_${stopType}_STOP",
                                wallet = wallet,
                                walletSol = effectiveBalance
                            )
                            
                            TradeStateMachine.startCooldown(ts.mint)
                        }
                        
                    } catch (e: Exception) {
                        // Log but don't crash the monitor
                        ErrorLogger.debug("BotService", "Rapid stop check error for ${ts.symbol}: ${e.message}")
                    }
                }
                
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
                
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Rapid stop-loss monitor error: ${e.message}")
                kotlinx.coroutines.delay(1000L)  // Wait longer on error
            }
        }
        
        ErrorLogger.info("BotService", "🛡️ Rapid Stop-Loss Monitor STOPPED")
    }

    // V5.9.495z54c — extracted from botLoop() to fit JVM 64KB method limit.
    /**
     * V5.9.626 — CANONICAL PROTECTED MEME INTAKE.
     *
     * All raw Solana/meme discovery sources must converge here:
     *   - SolanaMarketScanner → TokenMergeQueue
     *   - PumpPortal WS
     *   - DataOrchestrator Pump.fun callback
     *   - any future discovery bridge
     *
     * This method is intentionally non-destructive and fail-open for ARRIVAL.
     * It guarantees GlobalTradeRegistry + status.tokens hydration so the Meme
     * Trader UI/loop cannot split-brain between "config watchlist", registry,
     * and runtime token state. Entry quality is still enforced downstream by
     * FDG/safety/sub-trader execution gates.
     */
    private fun admitProtectedMemeIntake(
        mint: String,
        symbol: String,
        name: String = symbol,
        source: String,
        marketCapUsd: Double = 0.0,
        liquidityUsd: Double = 0.0,
        volumeH1: Double = 0.0,
        confidence: Int = 50,
        allSources: Set<String> = setOf(source),
        playSound: Boolean = false,
        operatorLog: Boolean = false,
    ): Boolean {
        if (mint.isBlank() || mint.length < 30) {
            ErrorLogger.debug("BotService", "🛡 protected intake ignored invalid mint ${mint.take(8)} source=$source")
            return false
        }

        val joinedSources = allSources.ifEmpty { setOf(source) }.joinToString(",")
        val addResult = try {
            GlobalTradeRegistry.addToWatchlist(
                mint = mint,
                symbol = symbol.ifBlank { mint.take(6) },
                addedBy = source,
                source = joinedSources,
                initialMcap = marketCapUsd,
            )
        } catch (e: Throwable) {
            ErrorLogger.error("BotService", "Protected intake registry add failed for ${symbol.ifBlank { mint.take(6) }}: ${e.message}", e)
            null
        }

        // Hydrate runtime token state regardless of add result. Duplicates and
        // legacy rejection memory must never leave registry/status/UI divergent.
        try {
            synchronized(status.tokens) {
                val ts = status.tokens.getOrPut(mint) {
                    com.lifecyclebot.data.TokenState(
                        mint = mint,
                        symbol = symbol.ifBlank { mint.take(6) },
                        name = name.ifBlank { symbol.ifBlank { mint.take(6) } },
                        candleTimeframeMinutes = 1,
                        source = joinedSources,
                        // V5.9.642 — no-logo intake. Missing/broken token images
                        // must never be part of the scanner/watchlist contract.
                        // UI placeholders handle logos; execution only needs mint.
                        logoUrl = "",
                    )
                }
                // symbol/name are immutable identity fields on TokenState; the
                // getOrPut initializer above is the authority for first hydrate.
                if (ts.source.isBlank()) ts.source = joinedSources

                if (liquidityUsd > 0.0 && ts.lastLiquidityUsd <= 0.0) {
                    ts.lastLiquidityUsd = liquidityUsd
                }
                if (marketCapUsd > 0.0 && ts.lastMcap <= 0.0) {
                    ts.lastMcap = marketCapUsd
                }
                if (confidence > 0 && ts.entryScore <= 0.0) {
                    ts.entryScore = confidence.toDouble()
                }
                if (volumeH1 > 0.0 && ts.history.isEmpty()) {
                    val seedPrice = if (ts.lastPrice > 0) ts.lastPrice else 0.0
                    val seedMcap = if (ts.lastMcap > 0) ts.lastMcap else marketCapUsd
                    val seedCandle = com.lifecyclebot.data.Candle(
                        ts = System.currentTimeMillis(),
                        priceUsd = seedPrice,
                        marketCap = seedMcap,
                        volumeH1 = volumeH1,
                        volume24h = 0.0,
                        buysH1 = 0,
                        sellsH1 = 0,
                        highUsd = seedPrice,
                        lowUsd = seedPrice,
                        openUsd = seedPrice,
                    )
                    synchronized(ts.history) { ts.history.addLast(seedCandle) }
                }
            }

            try {
                com.lifecyclebot.engine.MemeMintRegistry.touch(
                    mint = mint,
                    symbol = symbol.ifBlank { mint.take(6) },
                    name = name.ifBlank { symbol.ifBlank { mint.take(6) } },
                    source = joinedSources,
                )
            } catch (_: Throwable) {}

            try { orchestrator?.onTokenAdded(mint, symbol.ifBlank { mint.take(6) }) } catch (_: Throwable) {}
        } catch (e: Throwable) {
            ErrorLogger.error("BotService", "Protected intake TokenState hydrate failed for ${symbol.ifBlank { mint.take(6) }}: ${e.message}", e)
        }

        val added = addResult?.added == true
        if (added) {
            val newSize = try { GlobalTradeRegistry.size() } catch (_: Throwable) { -1 }
            TradeLifecycle.watchlisted(mint, newSize, "protected_intake: $source")
            ErrorLogger.info(
                "BotService",
                "🛡 PROTECTED_INTAKE: ${symbol.ifBlank { mint.take(6) }} | source=$source | liq=\$${liquidityUsd.toInt()} | conf=$confidence | watch=$newSize"
            )
            if (operatorLog) {
                addLog("📋 WATCHLISTED: ${symbol.ifBlank { mint.take(6) }} ($source) | liq=\$${liquidityUsd.toInt()} | conf=$confidence | #$newSize", mint)
            }
            if (playSound) soundManager.playNewToken()
        } else {
            ErrorLogger.debug(
                "BotService",
                "🛡 PROTECTED_INTAKE_HYDRATED: ${symbol.ifBlank { mint.take(6) }} | source=$source | reason=${addResult?.reason ?: "registry_error"}"
            )
        }
        return added
    }

    private fun processTokenMergeQueue(loopCount: Int) {
        val mergedTokens = TokenMergeQueue.processQueue()
        for (merged in mergedTokens) {
            val boostLabel = if (merged.multiScannerBoost) " [MULTI-SCANNER]" else ""
            val scannersInfo = if (merged.allScanners.size > 1)
                " (${merged.allScanners.joinToString("+")})" else ""

            val added = admitProtectedMemeIntake(
                mint = merged.mint,
                symbol = merged.symbol,
                name = merged.symbol,
                source = merged.primaryScanner,
                marketCapUsd = merged.marketCapUsd,
                liquidityUsd = merged.liquidityUsd,
                volumeH1 = merged.volumeH1,
                confidence = merged.confidence,
                allSources = merged.allScanners,
                playSound = true,
                operatorLog = true,
            )

            if (added) {
                try { com.lifecyclebot.engine.WatchlistTtlPolicy.mark(merged.symbol, merged.confidence) } catch (_: Throwable) {}
                TradeLifecycle.watchlisted(merged.mint, GlobalTradeRegistry.size(), "merged: ${merged.primaryScanner}$scannersInfo$boostLabel")
            }
        }

        val probationResults = GlobalTradeRegistry.processProbation()
        for (result in probationResults) {
            when (result.action) {
                "PROMOTED" -> {
                    addLog("✅ PROMOTED: ${result.symbol} | ${result.reason}", result.mint)
                    soundManager.playNewToken()
                    try {
                        val probEntry = GlobalTradeRegistry.getProbationEntry(result.mint)
                        admitProtectedMemeIntake(
                            mint = result.mint,
                            symbol = result.symbol,
                            name = result.symbol,
                            source = "PROBATION",
                            marketCapUsd = probEntry?.initialMcap ?: 0.0,
                            liquidityUsd = probEntry?.initialLiquidity ?: 0.0,
                            confidence = 50,
                            allSources = setOf("PROBATION"),
                            playSound = false,
                            operatorLog = false,
                        )
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "PROMOTED protected intake hydrate error: ${e.message}")
                    }
                }
                "REJECTED" -> {
                    // V5.9.626 — probation rejection is execution memory only;
                    // protected intake must not shrink or hide the universe.
                    ErrorLogger.debug("BotService", "🛡 PROBATION_SHADOW: ${result.symbol} | ${result.reason}")
                }
            }
        }

        if (loopCount % 30 == 0 && TokenMergeQueue.getPendingCount() > 0) {
            addLog("🔀 ${TokenMergeQueue.getStats()}")
        }
        if (loopCount % 30 == 0 && GlobalTradeRegistry.probationSize() > 0) {
            addLog("⏳ ${GlobalTradeRegistry.getProbationStats()}")
        }
    }

    private suspend fun runRegimePulse() {
        try {
            listOf(
                com.lifecyclebot.perps.PerpsMarket.SOL,
                com.lifecyclebot.perps.PerpsMarket.BTC,
                com.lifecyclebot.perps.PerpsMarket.ETH,
            ).forEach { m ->
                try {
                    val data = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(m)
                    if (data.price > 0) {
                        com.lifecyclebot.v4.meta.CrossMarketRegimeAI.updateMarketState(
                            symbol = m.symbol,
                            price = data.price,
                            change24hPct = data.priceChange24hPct,
                            volume = data.volume24h,
                        )
                    }
                } catch (_: Throwable) {}
            }
            try {
                val out = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime()
                ErrorLogger.debug("BotService", "🌐 Regime pulse → ${out.mode} (${out.reasons.firstOrNull() ?: "—"})")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun runSentienceAutoTune() {
        com.lifecyclebot.engine.SentienceHooks.maybeAutoTune(applicationContext)
        val distrusted = try {
            com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores()
                .filter { (_, rec) -> rec.trustLevel == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED }
                .keys.toList()
        } catch (_: Throwable) { emptyList() }
        if (distrusted.isNotEmpty()) {
            com.lifecyclebot.engine.SentienceHooks.nominateStrategiesToPause(distrusted)
        }
    }

    private suspend fun runReconcileSweep() {
        val cfgNow = ConfigStore.load(applicationContext)
        val w = wallet ?: return
        if (cfgNow.paperMode || !::executor.isInitialized) return

        val activeMints = mutableSetOf<String>()
        try {
            synchronized(status.tokens) {
                status.tokens.values.forEach { ts ->
                    if (ts.position.isOpen) activeMints.add(ts.mint)
                }
            }
        } catch (_: Exception) {}
        try {
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()
                .forEach { activeMints.add(it.mint) }
        } catch (_: Exception) {}
        try {
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
                .forEach { activeMints.add(it.mint) }
        } catch (_: Exception) {}
        try {
            com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions()
                .forEach { activeMints.add(it.mint) }
        } catch (_: Exception) {}
        try {
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
                .forEach { activeMints.add(it.mint) }
        } catch (_: Exception) {}
        val swept = executor.liveSweepWalletTokens(w, w.getSolBalance(), activeMints)
        if (swept > 0) {
            ErrorLogger.warn("BotService",
                "🔄 RECONCILE SWEEP: liquidated $swept orphan token(s) leaked from V3 exits")
            addLog("🔄 Reconcile: cleared $swept orphan position(s) from wallet")
        }
    }

    // V5.9.495z53 — extracted from botLoop() to keep the synthesized
    // bytecode under the JVM 64KB method-size limit (build was failing with
    // "Couldn't transform method node: botLoop"). No behavior change.
    private fun runLabUniverseTick() {
        com.lifecyclebot.engine.lab.LlmLabEngine.tick {
            val list = ArrayList<com.lifecyclebot.engine.lab.LlmLabEngine.LabUniverseTick>(
                status.tokens.size
            )
            val regime = try {
                val m = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime().mode
                when (m) {
                    com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                    com.lifecyclebot.v4.meta.GlobalRiskMode.TRENDING -> "BULL"
                    com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_OFF -> "BEAR"
                    else -> "CHOP"
                }
            } catch (_: Throwable) { "ANY" }
            status.tokens.values.forEach { ts ->
                val price = ts.lastPrice.takeIf { it > 0 } ?: ts.history.lastOrNull()?.priceUsd ?: 0.0
                if (price <= 0) return@forEach
                val score = (ts.entryScore.toInt()).coerceIn(0, 100)
                list.add(com.lifecyclebot.engine.lab.LlmLabEngine.LabUniverseTick(
                    symbol = ts.symbol.ifBlank { ts.mint.take(8) },
                    mint = ts.mint,
                    asset = com.lifecyclebot.engine.lab.LabAssetClass.MEME,
                    price = price,
                    score = score,
                    regime = regime,
                ))
            }
            fun pushTick(symbol: String, mint: String, asset: com.lifecyclebot.engine.lab.LabAssetClass, price: Double) {
                if (price <= 0.0 || symbol.isBlank()) return
                list.add(com.lifecyclebot.engine.lab.LlmLabEngine.LabUniverseTick(
                    symbol = symbol, mint = mint, asset = asset,
                    price = price, score = 50, regime = regime,
                ))
            }
            try {
                com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions().forEach { p ->
                    pushTick(p.market.symbol, p.market.symbol, com.lifecyclebot.engine.lab.LabAssetClass.ALT, p.currentPrice)
                }
            } catch (_: Throwable) {}
            try {
                com.lifecyclebot.perps.TokenizedStockTrader.getActivePositions().forEach { p ->
                    pushTick(p.market.symbol, p.market.symbol, com.lifecyclebot.engine.lab.LabAssetClass.STOCK, p.currentPrice)
                }
            } catch (_: Throwable) {}
            try {
                com.lifecyclebot.perps.PerpsTraderAI.getActivePositions().forEach { p ->
                    pushTick(p.market.symbol, p.market.symbol, com.lifecyclebot.engine.lab.LabAssetClass.MARKETS, p.currentPrice)
                }
            } catch (_: Throwable) {}
            try {
                com.lifecyclebot.perps.ForexTrader.getAllPositions().forEach { p ->
                    pushTick(p.market.symbol, p.market.symbol, com.lifecyclebot.engine.lab.LabAssetClass.FOREX, p.currentPrice)
                }
            } catch (_: Throwable) {}
            try {
                com.lifecyclebot.perps.MetalsTrader.getAllPositions().forEach { p ->
                    pushTick(p.market.symbol, p.market.symbol, com.lifecyclebot.engine.lab.LabAssetClass.METAL, p.currentPrice)
                }
            } catch (_: Throwable) {}
            try {
                com.lifecyclebot.perps.CommoditiesTrader.getAllPositions().forEach { p ->
                    pushTick(p.market.symbol, p.market.symbol, com.lifecyclebot.engine.lab.LabAssetClass.COMMODITY, p.currentPrice)
                }
            } catch (_: Throwable) {}
            list
        }
    }

    private suspend fun botLoop() {
        ErrorLogger.info("BotService", "botLoop() started")
        
        // START RAPID STOP-LOSS MONITOR IN PARALLEL
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            rapidStopLossMonitor()
        }

        // V5.9.251: PERIODIC WALLET RECONCILIATION
        // Re-run StartupReconciler every 90s during a live session so
        // positions that drift mid-session get caught fast:
        //   - pendingVerify positions whose 30s window expired but tokens landed
        //   - External sells (manual Phantom/Jupiter sells) — stamps recentlyClosedMs
        //     to prevent immediate re-buy (was 5 min, too slow for meme tokens)
        //   - Orphaned tokens from silent buy confirmations
        var lastReconcileAt = System.currentTimeMillis()
        val reconcileIntervalMs = 90 * 1000L  // V5.9.251: 90s (was 5 min — too slow)

        // V5.9.290: PendingVerify Watchdog — sweeps every 60s for any position stuck in
        // pendingVerify for > 120s and force-clears it so exit management can fire.
        // This is an additional proactive layer on top of the per-tick check in the
        // exit management block. Belt AND suspenders.
        var lastPendingVerifyWatchdogAt = 0L
        val pendingVerifyWatchdogIntervalMs = 60_000L

        // V5.9.362 — REGIME PULSE
        // CrossMarketRegimeAI is only ever fed by CryptoAltTrader / TokenizedStockTrader.
        // When Perps is OFFLINE the regime stays on its boot default forever, so the
        // meme trader runs full-throttle even in obvious chop / risk-off conditions.
        // This 60s pulse pushes SOL/BTC/ETH from PerpsMarketDataFetcher (Pyth/Jupiter/
        // CoinGecko, already cached) into the regime engine and re-evaluates so the
        // entire universe sees a live regime no matter which traders are active.
        var lastRegimePulseAt = 0L
        val regimePulseIntervalMs = 60_000L

        // V5.9.634c — freeze-detector state moved to class fields and the
        // detector body extracted into runFreezeDetectorTick() because the
        // inline version pushed botLoop past the JVM 64KB per-method limit.
        if (freezeLastExecCount < 0L) {
            freezeLastExecCount = com.lifecyclebot.engine.CanonicalLearningCounters.executedTradesTotal.get()
            freezeLastExecChangeMs = System.currentTimeMillis()
        }

        var loopCount = 0
        while (status.running) {
          try {
            loopCount++

            // V5.9.454 — WALLET RE-SYNC FIX.
            // BotService's local `wallet` var previously only updated
            // from inside launchWalletConnect's callback. When
            // Executor.doSell successfully reconnected the WalletManager
            // singleton via attemptReconnect(), the new wallet was never
            // propagated back to this local var — so the NEXT tick still
            // passed `wallet=null` to the Executor and the sell hit
            // "CRITICAL: Live mode sell attempted but WALLET IS NULL!"
            // again, forever.
            //
            // Fix: in live mode, re-sync from the singleton every tick.
            // Cheap read (returns a cached ref), no network call.
            if (!ConfigStore.load(applicationContext).paperMode) {
                val fresh = walletManager.getWallet()
                if (fresh != null && fresh !== wallet) {
                    wallet = fresh
                    ErrorLogger.info("BotService", "🔄 wallet re-synced from singleton — sells unblocked")
                }
            }

            // V5.9.362 — Regime pulse (every 60s, side-effect-only, never blocks)
            if (System.currentTimeMillis() - lastRegimePulseAt > regimePulseIntervalMs) {
                lastRegimePulseAt = System.currentTimeMillis()
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { runRegimePulse() } catch (_: Throwable) {}
                }
            }

            // V5.9.401 — Sentience auto-tune + distrust nomination (rate-limited internally)
            try { runSentienceAutoTune() } catch (_: Throwable) {}

            // V5.9.402 — LLM Lab tick (creation + paper exec + cull, all internally gated).
            try { runLabUniverseTick() } catch (_: Throwable) {}

            // ═══════════════════════════════════════════════════════════════════
            // V5.2: PIPELINE TRACE - Snapshot loop state at start
            // Freeze aggression for this loop cycle to prevent mid-loop mutations
            // V5.2 FIX: Use correct balance based on paper/live mode
            // ═══════════════════════════════════════════════════════════════════
            val cfg = ConfigStore.load(applicationContext)

            // V5.9.226: Bug #7 — Drain failed fee retry queue at cycle start (live mode only)
            if (!cfg.paperMode) {
                val liveWallet = wallet
                if (liveWallet != null) {
                    try { FeeRetryQueue.drainFeeQueue(liveWallet) }
                    catch (e: Exception) { ErrorLogger.warn("BotService", "FeeRetryQueue drain error: ${e.message}") }
                }
            }

            // V5.9.290: PendingVerify Watchdog — proactively sweep for stuck pendingVerify positions.
            // Runs every 60s. Clears any position where qtyToken>0 && pendingVerify=true && age>120s.
            // This runs BEFORE the per-token loop so that stuck positions are already cleared when
            // the exit management block evaluates them, rather than relying on the per-tick check alone.
            // V5.9.315: Watchdog now does on-chain RE-CHECK before deciding (live mode only).
            //   - Tokens present on-chain → adopt real qty + clear pendingVerify (becomes open).
            //   - Tokens NOT present (RPC succeeded with 0) → wipe position as confirmed phantom.
            //   - RPC failed → leave pendingVerify=true; retry next watchdog tick.
            // This eliminates GHOST POSITIONS (Models.kt isOpen no longer auto-promotes after 120s).
            if (System.currentTimeMillis() - lastPendingVerifyWatchdogAt > pendingVerifyWatchdogIntervalMs) {
                lastPendingVerifyWatchdogAt = System.currentTimeMillis()
                try {
                    val now = System.currentTimeMillis()
                    var clearedCount = 0
                    var phantomCount = 0
                    val isLive = !cfg.paperMode
                    val w = wallet
                    // For live mode, fetch on-chain balances once for all stuck positions.
                    val onChainBalances: Map<String, Pair<Double, Int>>? = if (isLive && w != null) {
                        try { w.getTokenAccountsWithDecimals() } catch (e: Exception) {
                            ErrorLogger.warn("BotService", "🔧 [VERIFY_WATCHDOG] RPC failed; will retry next tick: ${e.message}")
                            null
                        }
                    } else null
                    status.tokens.values.forEach { ts ->
                        val pos = ts.position
                        if (pos.pendingVerify && pos.qtyToken > 0.0 && pos.entryTime > 0L) {
                            val ageMs = now - pos.entryTime
                            if (ageMs >= 120_000L) {
                                if (isLive) {
                                    if (onChainBalances == null) {
                                        // RPC failed this tick — leave pendingVerify=true, retry later
                                        return@forEach
                                    }
                                    val onChainQty = onChainBalances[ts.mint]?.first ?: 0.0
                                    if (onChainQty > 0.0) {
                                        // Tokens really did land — adopt real qty and promote
                                        ErrorLogger.warn("BotService",
                                            "🔧 [VERIFY_WATCHDOG] ${ts.symbol} | ${ageMs / 1000}s — tokens VERIFIED on-chain (qty=$onChainQty). Promoting to open.")
                                        synchronized(ts) {
                                            ts.position = pos.copy(qtyToken = onChainQty, pendingVerify = false)
                                        }
                                        try { com.lifecyclebot.engine.PositionPersistence.savePosition(ts) } catch (_: Exception) {}
                                        clearedCount++
                                    } else {
                                        // RPC succeeded, no tokens → confirmed phantom. Wipe.
                                        ErrorLogger.warn("BotService",
                                            "👻 [VERIFY_WATCHDOG] ${ts.symbol} | ${ageMs / 1000}s — GHOST POSITION confirmed (0 tokens on-chain). Wiping.")
                                        synchronized(ts) {
                                            ts.position = com.lifecyclebot.data.Position()
                                            ts.lastExitTs = now
                                        }
                                        try { com.lifecyclebot.engine.PositionPersistence.savePosition(ts) } catch (_: Exception) {}
                                        phantomCount++
                                    }
                                } else {
                                    // Paper mode never sets pendingVerify=true normally, but if it
                                    // somehow did, just clear it — paper has no on-chain truth.
                                    synchronized(ts) {
                                        ts.position = pos.copy(pendingVerify = false)
                                    }
                                    clearedCount++
                                }
                            }
                        }
                    }
                    if (clearedCount > 0 || phantomCount > 0) {
                        ErrorLogger.warn("BotService",
                            "🔧 [VERIFY_WATCHDOG] Promoted=$clearedCount, Wiped(ghosts)=$phantomCount")
                        if (clearedCount > 0) addLog("🔧 Watchdog: promoted $clearedCount verified position(s)")
                        if (phantomCount > 0) addLog("👻 Watchdog: wiped $phantomCount ghost position(s) — no tokens on-chain")
                    }
                } catch (wdEx: Exception) {
                    ErrorLogger.warn("BotService", "PendingVerify watchdog error: ${wdEx.message}")
                }
            }

            // V5.9.103: periodic reconcile (live mode only)
            if (!cfg.paperMode && System.currentTimeMillis() - lastReconcileAt > reconcileIntervalMs) {
                lastReconcileAt = System.currentTimeMillis()
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val w = wallet
                        if (w != null && w.publicKeyB58.isNotEmpty()) {
                            val r = com.lifecyclebot.engine.StartupReconciler(
                                wallet = w,
                                status = status,
                                onLog = { msg -> addLog("[reconcile] $msg") },
                                onAlert = { title, body ->
                                    ErrorLogger.info("BotService", "⚠️ $title — $body")
                                },
                                executor = executor,
                                autoSellOrphans = false,
                            )
                            r.reconcile()
                            // V5.9.493 — PHANTOM SWEEP for sub-trader stores.
                            // StartupReconciler clears ghosts in status.tokens
                            // (the V3 lane), but Treasury / ShitCoin /
                            // Quality / BlueChip / Moonshot keep positions
                            // in their OWN private maps. If a Treasury
                            // position got sold off-band (V5.9.488 PUMP
                            // DIRECT, manual Phantom sell, external bot,
                            // etc.) the close-out callback never fires and
                            // the position UI shows a phantom 'open' tile.
                            // Sweep against the live wallet balance and
                            // remove anything no-longer-owned.
                            try {
                                val accounts = w.getTokenAccountsWithDecimals()
                                val walletMints = accounts
                                    .filter { (_, v) -> v.first > 0.0 }
                                    .keys
                                val cleared = com.lifecyclebot.v3.scoring.CashGenerationAI
                                    .sweepPhantoms(walletMints)
                                if (cleared > 0) {
                                    addLog("🧹 Treasury phantom sweep: cleared $cleared stale position(s)")
                                }
                            } catch (e: Exception) {
                                ErrorLogger.warn("BotService", "Phantom sweep error: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        ErrorLogger.warn("BotService", "Periodic reconcile error: ${e.message}")
                    }
                }
            }

            // V5.9.631 — Meme lane must not silently disappear while the bot is running.
            // Operator forensic log: CryptoAlt/Markets/Commodities/Metals/Forex all ran
            // for an hour, but Meme showed no scanner/search/display/trades. Root cause:
            // this branch treated config drift (memeTraderEnabled=false and mode not
            // Meme/Both) as permission to `continue` forever, effectively making the
            // Meme Trader invisible while every other desk stayed alive. Protected intake
            // is infrastructure, not an optional execution gate; keep it awake whenever
            // the bot is running or any global trading/discovery switch is active.
            val memeEnabled = cfg.memeTraderEnabled ||
                cfg.tradingMode == 0 ||
                cfg.tradingMode == 2 ||
                cfg.fullMarketScanEnabled ||
                cfg.v3EngineEnabled ||
                cfg.autoTrade ||
                cfg.autoAddNewTokens ||
                status.running
            if (!memeEnabled) {
                // Meme trader disabled — still run markets watchdog before sleeping
                try {
                    // V5.9.469 — AND-semantics gate (was OR-semantics; tradingMode=2
                    // bypassed the toggle, restarting markets every 10 ticks even
                    // when the user had toggled the master Markets switch off).
                    val marketsEnabled = isMarketsLaneEnabled(cfg)

                    // V5.9.469 — if user just toggled Markets off mid-session,
                    // STOP the engine instead of letting it run unattended.
                    if (!marketsEnabled && com.lifecyclebot.perps.PerpsExecutionEngine.isRunning()) {
                        ErrorLogger.info("BotService", "📴 [meme-off] Markets toggled OFF mid-session — stopping PerpsExecutionEngine")
                        addLog("📴 Markets toggled OFF — stopping engine")
                        try { com.lifecyclebot.perps.PerpsExecutionEngine.stop() } catch (_: Exception) {}
                    }

                    if (marketsEnabled && loopCount % 10 == 0) {
                        val healthy = com.lifecyclebot.perps.PerpsExecutionEngine.isHealthy()
                        if (!healthy) {
                            ErrorLogger.warn("BotService", "⚠️ [meme-off] PerpsExecutionEngine unhealthy — restarting…")
                            addLog("⚡ Markets engine watchdog (meme-off): restarting…")
                            com.lifecyclebot.perps.PerpsExecutionEngine.stop()
                            delay(500)
                            com.lifecyclebot.perps.PerpsExecutionEngine.start(applicationContext)
                        }
                    }
                    // CryptoAltTrader watchdog (meme-off) — only if enabled
                    if (loopCount % 10 == 0 && cfg.cryptoAltsEnabled && !com.lifecyclebot.perps.CryptoAltTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ [meme-off] CryptoAltTrader unhealthy — restarting…")
                        com.lifecyclebot.perps.CryptoAltTrader.start()
                    }
                    // TokenizedStockTrader watchdog (meme-off) — only if stocks enabled
                    if (marketsEnabled && cfg.stocksEnabled && loopCount % 10 == 0 && !com.lifecyclebot.perps.TokenizedStockTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ [meme-off] TokenizedStockTrader unhealthy — restarting…")
                        com.lifecyclebot.perps.TokenizedStockTrader.start()
                    }
                    // CommoditiesTrader watchdog (meme-off) — only if commodities enabled
                    if (marketsEnabled && cfg.commoditiesEnabled && loopCount % 10 == 0 && !com.lifecyclebot.perps.CommoditiesTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ [meme-off] CommoditiesTrader unhealthy — restarting…")
                        com.lifecyclebot.perps.CommoditiesTrader.start()
                    }
                    // MetalsTrader watchdog (meme-off) — only if metals enabled
                    if (marketsEnabled && cfg.metalsEnabled && loopCount % 10 == 0 && !com.lifecyclebot.perps.MetalsTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ [meme-off] MetalsTrader unhealthy — restarting…")
                        com.lifecyclebot.perps.MetalsTrader.start()
                    }
                    // ForexTrader watchdog (meme-off) — only if forex enabled
                    if (marketsEnabled && cfg.forexEnabled && loopCount % 10 == 0 && !com.lifecyclebot.perps.ForexTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ [meme-off] ForexTrader unhealthy — restarting…")
                        com.lifecyclebot.perps.ForexTrader.start()
                    }
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "Markets watchdog (meme-off) error: ${e.message}", e)
                }
                delay(cfg.pollSeconds * 1000L)
                continue
            }
            
            // V5.2 FIX: Completely isolate paper vs live wallet for balance display
            val displayBalance = if (cfg.paperMode) {
                // In paper mode, show paper wallet balance from FluidLearning
                FluidLearning.getPaperBalance()
            } else {
                // In live mode, show real wallet balance
                walletManager.state.value.solBalance
            }
            
            val loopSnapshot = PipelineTracer.startLoop(
                loopId = loopCount,
                aggression = BehaviorAI.getAggressionLevel(),
                paperMode = cfg.paperMode,
                liveMode = !cfg.paperMode,
                walletBalance = displayBalance,
                openPositions = status.openPositionCount,
                watchlistSize = GlobalTradeRegistry.size()
            )
            val frozenAggression = loopSnapshot.aggression

            // V5.9.495z32 — sweep stale watchlist candidates per loop.
            // Snipe-mode TTL = 5min, normal = 30min. Operator z32 directive:
            // we MUST NOT purge fresh candidates aggressively, only stale.
            try {
                val snipeMode = false  // BotConfig has no snipeMode flag yet — passive sweep
                val expired = com.lifecyclebot.engine.WatchlistTtlPolicy.sweepStale(snipeMode)
                if (expired > 0) {
                    ErrorLogger.debug("BotService",
                        "♻️ WatchlistTtlPolicy: expired=$expired (size=${com.lifecyclebot.engine.WatchlistTtlPolicy.size()})")
                }
            } catch (_: Throwable) { /* best-effort */ }
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: Clear FinalExecutionPermit state at start of each cycle
            // This allows tokens to be re-evaluated fresh each loop
            // V5.2.6: Set paper mode flag for bypass logic
            // ═══════════════════════════════════════════════════════════════════
            FinalExecutionPermit.isPaperMode = cfg.paperMode
            FinalExecutionPermit.clearCycleState()
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: GlobalTradeRegistry cleanup
            // Clean expired rejections and cooldowns
            // ═══════════════════════════════════════════════════════════════════
            GlobalTradeRegistry.cleanup()
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.20: EfficiencyLayer cleanup
            // Clean stale seen tokens, expired liquidity rejections, etc.
            // ═══════════════════════════════════════════════════════════════════
            EfficiencyLayer.cleanup()
            
            // ═══════════════════════════════════════════════════════════════════
            // V5.0: TradeAuthorizer cleanup
            // Clean stale shadow tracking entries
            // ═══════════════════════════════════════════════════════════════════
            TradeAuthorizer.cleanup()
            
            val watchlist = cfg.watchlist.toMutableList()
            if (cfg.activeToken.isNotBlank() && cfg.activeToken !in watchlist)
                watchlist.add(cfg.activeToken)
            
            // ═══════════════════════════════════════════════════════════════════
            // WALLET HEALTH CHECK - Critical for live mode sells
            // If wallet is null in live mode, sells will fail silently!
            // ═══════════════════════════════════════════════════════════════════
            if (!cfg.paperMode && wallet == null) {
                // V5.9.73 FIX: fire-and-forget reconnect so the bot loop
                // doesn't stall for up to 90s when RPCs are slow. Previously
                // this called walletManager.connect() synchronously and
                // blocked every live-mode tick where wallet was null until
                // all fallback RPCs timed out.
                val existing = walletConnectJob
                if (existing == null || !existing.isActive) {
                    if (cfg.privateKeyB58.isNotBlank()) {
                        ErrorLogger.warn("BotService", "🚨 LIVE MODE wallet null — launching background reconnect")
                        addLog("🚨 WALLET NULL in live mode — reconnecting in background…")
                        val rpcUrl = cfg.rpcUrl.ifBlank { "https://api.mainnet-beta.solana.com" }
                        launchWalletConnect(cfg.privateKeyB58, rpcUrl, runReconciliation = false)
                    } else {
                        ErrorLogger.warn("BotService", "🚨 LIVE MODE but no private key configured")
                        addLog("🚨 Live mode: no private key configured — cannot reconnect")
                    }
                }
            }
            
            // Update FinalDecisionGate mode for veto cooldown timing
            FinalDecisionGate.setModeForVeto(cfg.paperMode)
            
            // V5.6 FIX: Sync CashGenerationAI mode so checkExit finds positions in correct map!
            // Without this, positions entered in paper mode won't be found when checking exits
            com.lifecyclebot.v3.scoring.CashGenerationAI.setTradingMode(cfg.paperMode)
            
            // V5.6.11: Sync ALL trading AI modes - this transfers learning from paper to live
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.setTradingMode(cfg.paperMode)
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.setTradingMode(cfg.paperMode)
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.setTradingMode(cfg.paperMode)
            com.lifecyclebot.v3.scoring.QualityTraderAI.setTradingMode(cfg.paperMode)
            
            // V5.6.6: Update Treasury with actual wallet balance for proper position sizing
            val walletBalanceForTreasury = if (cfg.paperMode) {
                // Paper mode: use paper wallet balance (starts at 6 SOL, compounds)
                status.paperWalletSol
            } else {
                // Live mode: use actual SOL balance
                status.getEffectiveBalance(cfg.paperMode)
            }
            com.lifecyclebot.v3.scoring.CashGenerationAI.updateWalletBalance(walletBalanceForTreasury)
            
            // V5.0: Advance TradeAuthorizer epoch for decision tracking
            TradeAuthorizer.advanceEpoch()
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0 CRITICAL FIX: ONLY INITIALIZE TRADING MODES ONCE
            // Previously this was called every loop, causing state resets!
            // Services are singletons - they should init once per session.
            // ═══════════════════════════════════════════════════════════════════
            if (!tradingModesInitialized) {
                initTradingModes(cfg)
                tradingModesInitialized = true
            }

            // Log watchlist status every 5 loops for better visibility
            // V4.0: Use GlobalTradeRegistry for accurate count
            if (loopCount % 5 == 1) {
                val registrySize = GlobalTradeRegistry.size()
                ErrorLogger.info("BotService", "Bot loop #$loopCount - Watchlist size: $registrySize (GlobalTradeRegistry)")
                addLog("🔄 Loop #$loopCount | Watchlist: $registrySize tokens | Scanner: ${if(marketScanner != null) "ACTIVE" else "INACTIVE"}")
                if (registrySize == 0) {
                    addLog("⚠️ Watchlist empty - waiting for scanner to discover tokens...")
                } else {
                    // Log first 3 tokens being processed
                    val firstTokens = GlobalTradeRegistry.getWatchlist().take(3).joinToString(", ") { it.take(8) + "..." }
                    addLog("📊 Processing: $firstTokens")
                }
                
                // Record healthy status for watchdog (every 5 loops ~ 25 seconds)
                ServiceWatchdog.recordHealthy(applicationContext)
            }

            // V5.9.633 — periodic Reject-Reason Aggregator dump (~60s cadence).
            // Operator-facing visibility for "1518 mints scanned but only 85
            // executed" forensics: tells you which gate is the loudest dampener
            // right now (e.g. "MOONSHOT:low_buy_pressure=423(42%)").
            if (loopCount % 12 == 0) {
                try {
                    val line = RejectionTelemetry.summaryLine(top = 5)
                    if (RejectionTelemetry.totalWindowCount() > 0L) {
                        ErrorLogger.info("BotService", line)
                        addLog(line)
                    }
                } catch (_: Throwable) {}
            }

            // V5.9.634 — FREEZE DETECTOR + CAP DIAGNOSTICS (~60s cadence).
            // See runFreezeDetectorTick() for full description. Extracted to
            // a separate suspend function in V5.9.634c because botLoop hit the
            // JVM 64KB per-method bytecode limit.
            if (loopCount % 12 == 0) {
                runFreezeDetectorTick(loopCount, cfg)
            }
            
            // AGGRESSIVE WATCHLIST CLEANUP - every 5 loops (about 25 seconds)
            // V4.0: Use GlobalTradeRegistry.size() for check
            if (loopCount % 5 == 0 && GlobalTradeRegistry.size() > 3) {
                scope.launch {
                    try {
                        cleanupWatchlist()
                    } catch (e: Exception) {
                        ErrorLogger.error("BotService", "Watchlist cleanup error: ${e.message}")
                    }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: SYNC GLOBALTRADEREGISTRY TO CONFIG - every 10 loops (~50 seconds)
            // Persists the thread-safe watchlist to ConfigStore for restart recovery
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 10 == 0) {
                try {
                    GlobalTradeRegistry.syncToConfig(applicationContext)
                    ErrorLogger.debug("BotService", GlobalTradeRegistry.getStats())
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "GlobalTradeRegistry sync error: ${e.message}")
                }
                // V5.9.8: Persist paper wallet balance every 10 loops (~50s)
                if (cfg.paperMode && status.paperWalletSol > 0.01) {
                    try {
                        PaperWalletStore.persist(applicationContext, status.paperWalletSol)
                    } catch (_: Exception) {}
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // SCANNER STALENESS CHECK - every 6 loops (~30 seconds)
            // If no new tokens found for 2 minutes, reset scanner maps
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 6 == 0 && marketScanner != null) {
                marketScanner?.checkAndResetIfStale()
            }

            // V5.9.645 — 🩺 SCANNER HEARTBEAT (every ~30s).
            // Operator-requested visibility line so silent scanner failures are
            // immediately obvious in the log dump. Format intentionally compact
            // so it survives any export/grep filter.
            if (loopCount % 6 == 0) {
                try {
                    val sc = marketScanner
                    if (sc == null) {
                        ErrorLogger.info("BotService", "🩺 SCANNER_HEARTBEAT: marketScanner=NULL running=${status.running} watch=${GlobalTradeRegistry.size()}")
                        if (status.running) {
                            addLog("🩹 Heartbeat: scanner NULL — auto-recovering")
                            bootMemeScanner(reason = "HEARTBEAT_NULL")
                        }
                    } else {
                        val snap = try { sc.getThroughputTelemetrySnapshot() } catch (_: Throwable) { null }
                        if (snap != null) {
                            ErrorLogger.info(
                                "BotService",
                                "🩺 SCANNER_HEARTBEAT: alive=${snap.alive} ageSec=${snap.ageSec} src=${snap.src} ok=${snap.ok} err=${snap.err} raw=${snap.raw} enq=${snap.enq} cd=${snap.cd} liqRej=${snap.liqRej} watch=${GlobalTradeRegistry.size()}"
                            )
                        } else {
                            ErrorLogger.info("BotService", "🩺 SCANNER_HEARTBEAT: snapshot=null watch=${GlobalTradeRegistry.size()}")
                        }
                    }
                } catch (e: Throwable) {
                    ErrorLogger.debug("BotService", "Scanner heartbeat tick error: ${e.message}")
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // V5.9.621 — INERT-LOOP WATCHDOG (every ~60s)
            //
            // Operator: "its been sitting now 'started' and absolutely nothing
            // — no trades no Scanner nothing". Detected pathology: BotService
            // is running, status.running=true, but the scanner's onTokenFound
            // callback hasn't fired in N minutes despite PumpPortal showing
            // 2000+ mints. This means either:
            //   • SolanaMarketScanner died silently (coroutine cancelled)
            //   • DataOrchestrator stream feeds disconnected
            //   • PumpPortal WS dropped without onLost firing
            //
            // First trip (3min silence): force scanner soft-reset + stream
            // reconnect. Second trip (5min silence): nuke + recreate the
            // market scanner from scratch. Always logs loudly so the
            // operator sees the recovery action.
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 12 == 0) {  // ~60s cadence
                try {
                    val now = System.currentTimeMillis()
                    val silenceMs = now - lastScannerDiscoveryMs
                    when {
                        silenceMs > 5 * 60_000L -> {
                            ErrorLogger.error("BotService",
                                "🚨 INERT-LOOP WATCHDOG (HARD): no scanner activity in ${silenceMs/1000}s — restarting scanner")
                            addLog("🚨 INERT WATCHDOG: ${silenceMs/1000}s of scanner silence — restarting Solana scanner")
                            try { marketScanner?.stop() } catch (_: Throwable) {}
                            delay(500)
                            try { orchestrator?.reconnectStreams() } catch (_: Throwable) {}
                            val sc = marketScanner
                            if (sc != null) {
                                try {
                                    sc.start()
                                    addLog("✅ Solana scanner restarted by inert watchdog")
                                    ErrorLogger.warn("BotService", "✅ Inert watchdog restarted existing SolanaMarketScanner")
                                } catch (t: Throwable) {
                                    ErrorLogger.error("BotService", "Scanner restart failed: ${t.message}", t)
                                    addLog("❌ Scanner restart failed: ${t.message}")
                                }
                            } else {
                                // V5.9.645 — never silently swallow a null scanner.
                                // Self-heal the scanner instead of telling the operator
                                // to restart. The construction is now wrapped in
                                // bootMemeScanner() so we can call it idempotently from
                                // any recovery path.
                                ErrorLogger.error("BotService", "🚨 INERT WATCHDOG: marketScanner is NULL — auto-recovering via bootMemeScanner")
                                addLog("🚨 Scanner missing — auto-recovering via self-heal")
                                bootMemeScanner(reason = "INERT_WATCHDOG_NULL")
                            }
                            // Reset clock so the restart gets ~3min before another hard-reset
                            lastScannerDiscoveryMs = now
                            inertWatchdogFiredOnce = false
                        }
                        silenceMs > 3 * 60_000L && !inertWatchdogFiredOnce -> {
                            ErrorLogger.warn("BotService",
                                "⚠️ INERT-LOOP WATCHDOG (SOFT): ${silenceMs/1000}s of scanner silence — reconnecting streams")
                            addLog("⚠️ INERT WATCHDOG: ${silenceMs/1000}s of scanner silence — reconnect attempted")
                            try { orchestrator?.reconnectStreams() } catch (_: Throwable) {}
                            try { marketScanner?.checkAndResetIfStale() } catch (_: Throwable) {}
                            try { com.lifecyclebot.engine.AntiChokeManager.tick(
                                isPaperMode = cfg.paperMode,
                                wallet = wallet,
                                tokens = status.tokens,
                                loopCount = loopCount,
                            ) } catch (_: Throwable) {}
                            inertWatchdogFiredOnce = true
                        }
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "Inert watchdog tick error: ${e.message}")
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // PERIODIC ORPHAN SCAN - every 10 loops (~50 seconds) in live mode
            // Catches tokens that failed to sell and are stuck in wallet
            // ═══════════════════════════════════════════════════════════════════
            if (!cfg.paperMode && loopCount % 10 == 0 && wallet != null) {
                scope.launch {
                    try {
                        addLog("🔍 Periodic orphan scan starting...")
                        scanAndSellOrphans(wallet!!)
                    } catch (e: Exception) {
                        ErrorLogger.error("BotService", "Periodic orphan scan error: ${e.message}")
                    }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // MARKETS ENGINE WATCHDOG — every 10 loops
            // Detects when PerpsExecutionEngine loop died silently and restarts it
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 10 == 0) {
                // V5.9.9: Periodic SOL price refresh — critical for USD display
                // Without this, paper mode with no wallet = stale $0 price
                try {
                    val freshPrice = com.lifecyclebot.engine.WalletManager
                        .getInstance(applicationContext).fetchSolPrice()
                    if (freshPrice > 50.0) {
                        com.lifecyclebot.engine.WalletManager.lastKnownSolPrice = freshPrice
                    }
                } catch (_: Exception) {}

                // V5.9.10: Refresh global SymbolicContext — feeds all 50+ AI modules
                // + FinalDecisionGate with live 16-channel symbolic intelligence
                try { com.lifecyclebot.engine.SymbolicContext.refresh() } catch (_: Exception) {}

                try {
                    // V5.9.469 — gate ALL Markets watchdogs by the master toggle.
                    // Was previously "ALWAYS runs" which caused the operator-reported
                    // "Markets keeps starting in live whether the toggle is on or not"
                    // bug — the engine got restarted every 10 ticks regardless of
                    // marketsTraderEnabled. Now: when Markets is toggled OFF, we
                    // STOP the engine if it was running and skip the watchdogs entirely.
                    val marketsLaneOn = isMarketsLaneEnabled(cfg)
                    if (!marketsLaneOn && com.lifecyclebot.perps.PerpsExecutionEngine.isRunning()) {
                        ErrorLogger.info("BotService", "📴 Markets toggled OFF mid-session — stopping PerpsExecutionEngine + sub-traders")
                        addLog("📴 Markets toggled OFF — stopping engine + sub-traders")
                        try { com.lifecyclebot.perps.PerpsExecutionEngine.stop() } catch (_: Exception) {}
                        try { com.lifecyclebot.perps.TokenizedStockTrader.stop() } catch (_: Exception) {}
                        try { com.lifecyclebot.perps.CommoditiesTrader.stop() } catch (_: Exception) {}
                        try { com.lifecyclebot.perps.MetalsTrader.stop() } catch (_: Exception) {}
                        try { com.lifecyclebot.perps.ForexTrader.stop() } catch (_: Exception) {}
                    }

                    // PerpsExecutionEngine watchdog — only when Markets toggle is ON
                    if (marketsLaneOn) {
                        val healthy = com.lifecyclebot.perps.PerpsExecutionEngine.isHealthy()
                        if (!healthy) {
                            ErrorLogger.warn("BotService", "⚠️ PerpsExecutionEngine NOT HEALTHY (loop #$loopCount) — restarting…")
                            addLog("⚡ Markets engine watchdog: engine unhealthy, restarting…")
                            com.lifecyclebot.perps.PerpsExecutionEngine.stop()
                            delay(500)
                            com.lifecyclebot.perps.PerpsExecutionEngine.start(applicationContext)
                            addLog("⚡ Markets engine restarted by watchdog")
                        }
                    }
                    // CryptoAltTrader watchdog — only if enabled (V5.9.345: exempt from kill-switch)
                    if (cfg.cryptoAltsEnabled && !com.lifecyclebot.perps.CryptoAltTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ CryptoAltTrader unhealthy (loop #$loopCount) — restarting…")
                        addLog("🪙 CryptoAlt watchdog: unhealthy, restarting…")
                        com.lifecyclebot.perps.CryptoAltTrader.start()
                    }
                    // TokenizedStockTrader watchdog — only if Markets+stocks enabled
                    if (marketsLaneOn && cfg.stocksEnabled && !com.lifecyclebot.perps.TokenizedStockTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ TokenizedStockTrader unhealthy (loop #$loopCount) — restarting…")
                        addLog("📈 Stock trader watchdog: unhealthy, restarting…")
                        com.lifecyclebot.perps.TokenizedStockTrader.start()
                    }
                    // CommoditiesTrader watchdog — only if Markets+commodities enabled
                    if (marketsLaneOn && cfg.commoditiesEnabled && !com.lifecyclebot.perps.CommoditiesTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ CommoditiesTrader unhealthy (loop #$loopCount) — restarting…")
                        com.lifecyclebot.perps.CommoditiesTrader.start()
                    }
                    // MetalsTrader watchdog — only if Markets+metals enabled
                    if (marketsLaneOn && cfg.metalsEnabled && !com.lifecyclebot.perps.MetalsTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ MetalsTrader unhealthy (loop #$loopCount) — restarting…")
                        com.lifecyclebot.perps.MetalsTrader.start()
                    }
                    // ForexTrader watchdog — only if Markets+forex enabled
                    if (marketsLaneOn && cfg.forexEnabled && !com.lifecyclebot.perps.ForexTrader.isHealthy()) {
                        ErrorLogger.warn("BotService", "⚠️ ForexTrader unhealthy (loop #$loopCount) — restarting…")
                        com.lifecyclebot.perps.ForexTrader.start()
                    }
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "Markets watchdog error: ${e.message}", e)
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // PENDING SELL QUEUE PROCESSING — every loop tick (~5s) in live mode
            // V5.9.478: bumped from `% 10` (50-80s) → `% 1` (every tick).
            // Operator: '80 seconds is dumb and way to long the price will
            // always have moved plus we will miss quick pump spikes — it
            // needs to be as fast as the buys are aka instant.'
            //
            // Concurrency safety: liveSell's per-mint `sellInProgress` guard
            // + the `getAndClear()` semantics (queue is drained, processed,
            // failures are re-added) prevent double-fire on the same mint.
            // V5.9.478 also adds an in-line slippage escalation ladder
            // (200→400→600→1000bps within ONE liveSell call) so most retries
            // resolve before this loop iteration even completes; this fast
            // tick is the safety net for non-slippage failures (RPC blips,
            // rate-limits, RFQ outages).
            //
            // Historical context (V5.9.321): the previous %10 was set to
            // avoid double SELL_START / PENDING_RETRY_1 patterns when the
            // first liveSell hadn't yet received its on-chain confirmation.
            // With sellInProgress, this cannot happen — the second tick
            // sees the guard set and skips re-entry.
            // ═══════════════════════════════════════════════════════════════════
            if (!cfg.paperMode && loopCount % 1 == 0 && wallet != null && PendingSellQueue.hasPending()) {
                scope.launch {
                    try {
                        val pendingSells = PendingSellQueue.getAndClear()
                        if (pendingSells.isNotEmpty()) {
                            addLog("Processing ${pendingSells.size} pending sells...")
                            for (sell in pendingSells) {
                                try {
                                    // Find the token state if still tracked
                                    val ts = synchronized(status.tokens) { 
                                        status.tokens[sell.mint]
                                    }
                                    
                                    if (ts != null && ts.position.isOpen) {
                                        // V5.9.291 FIX: CRITICAL — never fake-close a position.
                                        // Old code called tradeId.closed(-100%) after 3 retries
                                        // WITHOUT clearing ts.position or executing any swap.
                                        // Result: bot "forgot" the position, tokens stayed in
                                        // wallet forever, -100% PnL wrongly booked.
                                        //
                                        // New behaviour:
                                        //   retryCount < 20 → keep retrying (Jupiter lag is real)
                                        //   retryCount >= 20 → LOUD notification to user, keep
                                        //     retrying anyway, NEVER fake-close without a real sig.
                                        //     StartupReconciler will adopt the position on restart.
                                        if (sell.retryCount >= 20 && sell.retryCount % 5 == 0) {
                                            // Escalating alert every 5 attempts after threshold
                                            addLog("🚨 STUCK SELL (${sell.retryCount} attempts): ${sell.symbol} — still retrying. Tokens in wallet.")
                                            sendTradeNotif(
                                                "🚨 Stuck Sell — Action Required",
                                                "${sell.symbol}: ${sell.retryCount} sell attempts failed. " +
                                                "Tokens are still in your wallet. Bot will keep retrying. " +
                                                "If persistent, use the positions panel to force-release.",
                                                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO
                                            )
                                            ErrorLogger.error("BotService",
                                                "🚨 STUCK_SELL: ${sell.symbol} | retryCount=${sell.retryCount} | " +
                                                "reason=${sell.reason} | tokens STILL in wallet, NOT fake-closed")
                                        }
                                        addLog("Retrying sell: ${sell.symbol} (attempt ${sell.retryCount + 1})")
                                        executor.requestSell(ts, "PENDING_RETRY_${sell.retryCount}: ${sell.reason}", wallet, wallet!!.getSolBalance())
                                    } else {
                                        // Token no longer tracked — might be orphaned or already sold
                                        addLog("Pending sell for untracked/closed token: ${sell.symbol} — removing from queue")
                                        PendingSellQueue.remove(sell.mint)
                                    }
                                } catch (e: Exception) {
                                    addLog("Pending sell retry failed: ${sell.symbol} — ${e.message}")
                                    // V5.9.291 FIX: requeue unconditionally — never fake-close.
                                    // requestSell internally handles FAILED_RETRYABLE → requeue,
                                    // so this outer catch is belt-and-suspenders for unexpected
                                    // exceptions thrown before requestSell is reached.
                                    val tsForRequeue = synchronized(status.tokens) { status.tokens[sell.mint] }
                                    if (tsForRequeue != null && tsForRequeue.position.isOpen) {
                                        PendingSellQueue.requeue(sell)
                                        ErrorLogger.warn("BotService",
                                            "🔄 SELL_EXCEPTION_REQUEUE: ${sell.symbol} | attempt=${sell.retryCount} | ${e.message?.take(60)}")
                                    } else {
                                        // Token is gone / already sold — drop from queue cleanly
                                        PendingSellQueue.remove(sell.mint)
                                        addLog("Pending sell dropped (position closed): ${sell.symbol}")
                                    }
                                }
                                // Small delay between retries to avoid rate limits
                                kotlinx.coroutines.delay(500)
                            }
                        }
                    } catch (e: Exception) {
                        ErrorLogger.error("BotService", "Pending sell processing error: ${e.message}")
                    }
                }
            }

            // Currency rate refresh + feed SOL price to bonding curve tracker
            scope.launch {
                try {
                    currencyManager.refreshIfStale()
                    val sol = currencyManager.getSolUsd()
                    if (sol > 0) BondingCurveTracker.updateSolPrice(sol)
                } catch (_: Exception) {}
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // BEHAVIOR LEARNING MAINTENANCE - every 60 loops (~5 minutes)
            // Self-healing check and pattern pruning
            // ═══════════════════════════════════════════════════════════════════
            // V5.9.33: Free-musing stream — sentient chat has more freedom.
            // Fires every 12 loops (~50-60s) so the window actually feels alive
            // even when no trades are happening. Talks about philosophy, memory,
            // the market, the user relationship, humor — not just trade outcomes.
            if (loopCount % 12 == 0 && loopCount % 60 != 0) {
                try { SentientPersonality.freeMusing() } catch (_: Exception) {}
            }
            if (loopCount % 60 == 0) {
                // V5.9.9: Sentient personality periodic reflection
                try { SentientPersonality.periodicReflection() } catch (_: Exception) {}

                scope.launch {
                    try {
                        // Self-healing check (clears data if poisoned)
                        val wasCleared = BehaviorLearning.selfHealingCheck()
                        if (wasCleared) {
                            addLog("🧹 BehaviorLearning: Self-healed (cleared poisoned data)")
                        }
                        
                        // Prune stale patterns
                        BehaviorLearning.pruneStalePatterns()
                        // Log health status periodically
                        val health = BehaviorLearning.getHealthStatus()
                        if (health.goodCount + health.badCount >= 10) {
                            ErrorLogger.info("BehaviorLearn", "📊 Health: ${health.summary()}")
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "BehaviorLearning maintenance error: ${e.message}")
                    }
                }

                // V5.9.318: InsiderWalletTracker delta scan (~every 5 min).
                // Detects NEW_POSITION/ACCUMULATION/DISTRIBUTION/SELL events on
                // tracked Trump/whale wallets and forwards them to the signal
                // callback wired in onCreate (notifications + log).
                scope.launch {
                    try {
                        val signals = com.lifecyclebot.perps.InsiderWalletTracker.scanForSignals()
                        if (signals.isNotEmpty()) {
                            ErrorLogger.info("BotService", "🔍 InsiderWalletTracker scan: ${signals.size} signal(s) detected")
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "InsiderWalletTracker scan error: ${e.message}")
                    }
                }

                // V5.9.318: TRADING COPILOT update — refresh the life-coach
                // directive from recent trade outcomes + layer health. Cheap
                // (O(window=30)). The coaching state then steers entries via
                // LifecycleStrategy.shouldTradeBase / FinalDecisionGate.
                try { com.lifecyclebot.engine.TradingCopilot.update() } catch (_: Exception) {}
                try {
                    val antiChoke = com.lifecyclebot.engine.AntiChokeManager.tick(
                        isPaperMode = cfg.paperMode,
                        wallet = wallet,
                        tokens = status.tokens,
                        loopCount = loopCount,
                    )
                    if (antiChoke != null && antiChoke.level != com.lifecyclebot.engine.AntiChokeManager.Level.CLEAR) {
                        addLog("🫁 AntiChoke ${antiChoke.level.name}: ghosts=${antiChoke.ghostsCleared} pruned=${antiChoke.dormantPruned} trades24h=${antiChoke.trades24h}/${antiChoke.target24h}")
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "AntiChoke tick error: ${e.message}")
                }

                // V5.9.439 — LEARNING TRANSPARENCY LOG (~every 5 min).
                // Surfaces the live snapshots of the three outcome-attribution
                // trackers so the user can see the brain narrowing toward
                // profitable buckets in real time. Fires only when the cheap
                // snapshot has at least one populated bucket.
                try {
                    val score = com.lifecyclebot.engine.ScoreExpectancyTracker.snapshot()
                    val hold  = com.lifecyclebot.engine.HoldDurationTracker.snapshot()
                    val exit  = com.lifecyclebot.engine.ExitReasonTracker.snapshot()
                    if (score != "no samples yet" || hold != "no samples yet" || exit != "no samples yet") {
                        ErrorLogger.info("BotService",
                            "🧠 LEARNING SNAPSHOT\n" +
                            "   score    : $score\n" +
                            "   hold     : $hold\n" +
                            "   exit     : $exit")
                    }
                } catch (_: Exception) {}

                // V5.9.439 — flush learning state to disk periodically.
                try { com.lifecyclebot.engine.LearningPersistence.saveAll() } catch (_: Exception) {}

                // V5.9.318: LIVE WALLET RECONCILE SWEEP (~every 5 min, LIVE only).
                // ROOT CAUSE: Sub-traders (ShitCoin/Moonshot/Quality/BlueChip
                // /Manip) closePosition() paths only update in-memory PnL —
                // they NEVER broadcast a Jupiter sell. Result: every V3 exit
                // leaks tokens into the wallet during normal operation. STOP
                // BOT sweep was the only fallback, and it was too late.
                // This periodic reconcile sweep liquidates ANY non-stable SPL
                // holdings the bot is no longer tracking as an open position.
                // Active V3 positions are passed as additionalPreservedMints so
                // currently-held trades are NOT prematurely liquidated.
                scope.launch {
                    try { runReconcileSweep() }
                    catch (e: Exception) { ErrorLogger.debug("BotService", "Reconcile sweep error: ${e.message}") }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // TOKEN WIN MEMORY REFRESH - every 180 loops (~15 minutes)
            // Recalculate pattern statistics and persist to storage
            // This ensures bad tokens and patterns are integrated into decisions
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 180 == 0) {
                scope.launch {
                    try {
                        // Force save current state
                        TokenWinMemory.forceSave()
                        
                        // Log summary of learned patterns
                        val summary = TokenWinMemory.getPatternSummary()
                        if (summary.isNotBlank()) {
                            addLog("📊 Pattern refresh: $summary")
                            ErrorLogger.info("TokenWinMemory", "📊 15min refresh: $summary")
                        }
                        
                        // ═══════════════════════════════════════════════════════════════════
                        // COLLECTIVE LEARNING SYNC - Share local knowledge with hive mind
                        // Uploads mode performance stats to Turso for all instances to learn
                        // ═══════════════════════════════════════════════════════════════════
                        // V5.9.617: Auto-reconnect if hivemind dropped — was silent before.
                        if (!com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                            try {
                                val reconnected = com.lifecyclebot.collective.CollectiveLearning.ensureConnected()
                                if (reconnected) {
                                    addLog("🌐 Hivemind reconnected")
                                    ErrorLogger.info("BotService", "CollectiveLearning auto-reconnect succeeded")
                                } else {
                                    addLog("🌐 Hivemind offline — running solo")
                                }
                            } catch (e: Exception) {
                                ErrorLogger.debug("BotService", "Hivemind reconnect attempt failed: ${e.message}")
                            }
                        }

                        // V5.9.619 — throughput-floor heartbeat. Operator-facing tripwire so
                        // any choke that drags the bot below 500 trades/24h is visible
                        // immediately. Pure telemetry — never blocks anything.
                        try {
                            val throughputMsg = com.lifecyclebot.v3.scoring.MemeEdgeAI.throughputStatus()
                            ErrorLogger.info("BotService", "📊 $throughputMsg")
                            if (throughputMsg.startsWith("ALERT")) {
                                addLog("📊 $throughputMsg")
                            }
                        } catch (_: Exception) {}
                        if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                            try {
                                // V3.2: Upload instance heartbeat for active instance counting
                                val botPrefs = getSharedPreferences("bot_service", android.content.Context.MODE_PRIVATE)
                                val instanceId = botPrefs.getString("instance_id", null) 
                                    ?: java.util.UUID.randomUUID().toString().also { 
                                        botPrefs.edit().putString("instance_id", it).apply() 
                                    }
                                val localStats = TradeHistoryStore.getStats()
                                val pnl24hPct = if (localStats.trades24h > 0) {
                                    (localStats.pnl24hSol / (localStats.trades24h * 0.1).coerceAtLeast(0.01)) * 100
                                } else 0.0
                                
                                // Get paperMode from config
                                val currentConfig = com.lifecyclebot.data.ConfigStore.load(applicationContext)
                                
                                com.lifecyclebot.collective.CollectiveLearning.uploadHeartbeat(
                                    instanceId = instanceId,
                                    appVersion = com.lifecyclebot.BuildConfig.VERSION_NAME,
                                    paperMode = currentConfig.paperMode,
                                    trades24h = localStats.trades24h,
                                    pnl24hPct = pnl24hPct
                                )
                                
                                // Sync mode learning stats to collective
                                val modeStats = ModeLearning.getAllModeStats()
                                val snapshots = modeStats.mapValues { (_, stats) ->
                                    com.lifecyclebot.collective.CollectiveLearning.ModeStatSnapshot(
                                        totalTrades = stats.totalTrades,
                                        wins = stats.wins,
                                        losses = stats.losses,
                                        avgPnlPct = stats.avgPnlPct,
                                        avgHoldMins = stats.avgHoldMins,
                                        marketCondition = "NEUTRAL",
                                        liquidityBucket = "MID"
                                    )
                                }
                                com.lifecyclebot.collective.CollectiveLearning.syncModeLearning(snapshots)
                                
                                // Log collective status
                                val collectiveInsights = com.lifecyclebot.collective.CollectiveLearning.getInsightsSummary()
                                addLog("🌐 $collectiveInsights")
                                
                                // Log V3 vs FDG comparison
                                if (cfg.v3EngineEnabled) {
                                    val v3Comparison = com.lifecyclebot.v3.V3EngineManager.getComparisonSummary()
                                    addLog("⚡ $v3Comparison")
                                    ErrorLogger.info("V3Comparison", v3Comparison)
                                }
                                
                                // ═══════════════════════════════════════════════════════════════════
                                // COLLECTIVE INTELLIGENCE AI - Refresh cache every 180 loops (~15 min)
                                // Analyzes patterns, aggregates mode stats, synthesizes consensus
                                // ═══════════════════════════════════════════════════════════════════
                                try {
                                    com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.refresh()
                                } catch (e: Exception) {
                                    ErrorLogger.debug("BotService", "CollectiveAI refresh error: ${e.message}")
                                }
                            } catch (e: Exception) {
                                ErrorLogger.debug("BotService", "Collective sync error: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "TokenWinMemory refresh error: ${e.message}")
                    }
                }
            }
            
            // Decay pattern weights every 720 loops (~1 hour)
            if (loopCount % 720 == 0) {
                scope.launch {
                    try {
                        BehaviorLearning.decayPatternWeights()
                    } catch (_: Exception) {}
                }
                
                // Run CollectiveIntelligenceAI maintenance (prune, dedupe, anomaly scan)
                scope.launch {
                    try {
                        com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.runMaintenance()
                    } catch (_: Exception) {}
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // UPDATE ADAPTIVE CONFIDENCE MARKET CONDITIONS
            // 
            // This feeds the fluid confidence layer with current market data:
            // - Average volatility across watched tokens
            // - Average buy pressure trend
            // - Recent win rate from SmartSizer
            // - Time since last loss
            // - Session P&L percentage
            // ═══════════════════════════════════════════════════════════════════
            scope.launch {
                try {
                    // Calculate average volatility and buy pressure from watched tokens
                    val tokenList = synchronized(status.tokens) { status.tokens.values.toList() }
                    
                    val avgVolatility = if (tokenList.isNotEmpty()) {
                        tokenList.mapNotNull { ts ->
                            if (ts.meta.avgAtr > 0) ts.meta.avgAtr else null
                        }.takeIf { it.isNotEmpty() }?.average() ?: 5.0
                    } else 5.0
                    
                    val avgBuyPressure = if (tokenList.isNotEmpty()) {
                        tokenList.map { it.meta.pressScore }.average()
                    } else 50.0
                    
                    // Get performance stats from SmartSizer
                    val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // FIX: Use FluidLearning.getTradeCount() for paper mode
                    // 
                    // BUG: Previously used tokenList.flatMap { it.trades }.size which
                    // resets to 0 when tokens are removed or bot restarts.
                    // FluidLearning.getTradeCount() is PERSISTED and accurate.
                    // ═══════════════════════════════════════════════════════════════════
                    val sessionTradeCount = tokenList.flatMap { it.trades }.size  // In-memory trades (for session stats)
                    // V5.9.291 FIX: Live mode must also use the PERSISTED trade count.
                    // Previously live fell back to sessionTradeCount (in-memory only) which
                    // resets to 0 on every bot restart. This made learningProgress = 0 in
                    // live → FDG thought it was a brand-new virgin bot → confidence floors
                    // bottomed out and barely any trade passed through.
                    // FluidLearning.getTradeCount() is persisted across restarts and shared
                    // between paper and live — all paper knowledge carries over seamlessly.
                    val persistedTradeCount = try {
                        FluidLearning.getTradeCount()
                    } catch (_: Exception) { sessionTradeCount }
                    
                    // Use the HIGHER of the two counts - this ensures FDG progresses correctly
                    val effectiveTradeCount = maxOf(sessionTradeCount, persistedTradeCount)
                    
                    val perfContext = SmartSizer.getPerformanceContext(
                        walletSol = effectiveBalance,
                        totalTrades = effectiveTradeCount,
                        isPaperMode = cfg.paperMode
                    )
                    
                    // Calculate session P&L
                    val allTrades = tokenList.flatMap { it.trades }
                    val sessionPnlSol = allTrades.sumOf { it.pnlSol }
                    val sessionPnlPct = if (effectiveBalance > 0) {
                        (sessionPnlSol / effectiveBalance) * 100
                    } else 0.0
                    
                    // Estimate time since last loss
                    val lastLossTrade = allTrades
                        .filter { it.side == "SELL" && it.pnlSol < 0 }
                        .maxByOrNull { it.ts }
                    val timeSinceLastLossMs = if (lastLossTrade != null) {
                        System.currentTimeMillis() - lastLossTrade.ts
                    } else Long.MAX_VALUE
                    
                    // Update the adaptive confidence layer
                    FinalDecisionGate.updateMarketConditions(
                        avgVolatility = avgVolatility,
                        buyPressureTrend = avgBuyPressure,
                        recentWinRate = perfContext.recentWinRate,
                        timeSinceLastLossMs = timeSinceLastLossMs,
                        sessionPnlPct = sessionPnlPct,
                        totalSessionTrades = perfContext.totalTrades,
                        // LEARNING LAYER DATA
                        entryAiWinRate = EntryIntelligence.getWinRate(),
                        exitAiAvgPnl = ExitIntelligence.getAveragePnl(),
                        edgeLearningAccuracy = EdgeLearning.getVetoAccuracy() * 100.0,
                    )
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // UPDATE MARKET REGIME AI
                    // Detects bull/bear/crab market conditions to adjust strategy
                    // ═══════════════════════════════════════════════════════════════════
                    try {
                        val solPrice = WalletManager.lastKnownSolPrice
                        
                        // Calculate meme performance from recent trades
                        val recentTrades = allTrades.filter { 
                            System.currentTimeMillis() - it.ts < 24 * 60 * 60 * 1000L 
                        }
                        val avgMemePerf = if (recentTrades.isNotEmpty()) {
                            recentTrades.mapNotNull { it.pnlPct }.average()
                        } else 0.0
                        
                        // Count successful vs rugged tokens
                        val successCount = recentTrades.count { (it.pnlPct ?: 0.0) >= 100.0 }
                        val rugCount = recentTrades.count { (it.pnlPct ?: 0.0) <= -80.0 }
                        
                        MarketRegimeAI.updateMarketData(
                            solPrice = solPrice,
                            solChange24h = 0.0, // Not tracked currently
                            solChange7d = 0.0, // Not tracked currently
                            avgMemePerformance = avgMemePerf,
                            successfulLaunches = successCount,
                            ruggedLaunches = rugCount,
                            volatilityIndex = avgVolatility,
                        )
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "MarketRegimeAI update error: ${e.message}")
                    }
                    
                    // Log adaptive confidence status every 5 loops
                    if (loopCount % 5 == 1) {
                        val adaptiveInfo = FinalDecisionGate.getAdaptiveConfidenceExplanation(cfg.paperMode)
                        addLog("🎚️ $adaptiveInfo")
                    }
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "Adaptive confidence update error: ${e.message}")
                }
            }
            
            // Periodic AI stats logging (every ~5 minutes = 6-7 loops at 45s intervals)
            if (loopCount % 7 == 0) {
                addLog("🤖 AI STATUS: ${TradingMemory.getStats()}")
                // Also log BotBrain learning state
                val brainStatus = botBrain?.let { b ->
                    "🧠 Brain: entry_adj=${String.format("%+.0f", b.entryThresholdDelta)} " +
                    "regime=${b.currentRegime} " +
                    "size_mult=${String.format("%.0f%%", b.regimeBullMult * 100)} " +
                    "suppressed=${b.totalSuppressedPatterns}"
                } ?: "🧠 Brain not initialized"
                addLog(brainStatus)
                
                // Log adaptive learning status
                addLog("🧬 ${AdaptiveLearningEngine.getStatus()}")
                
                // ═══════════════════════════════════════════════════════════════════
                // LOG NEW AI LAYERS STATUS
                // ═══════════════════════════════════════════════════════════════════
                addLog("🐋 ${WhaleTrackerAI.getStats()}")
                addLog("📊 ${MarketRegimeAI.getStats()}")
                addLog("🚀 ${MomentumPredictorAI.getStats()}")
                addLog("📖 ${NarrativeDetectorAI.getStats()}")
                addLog("⏰ ${TimeOptimizationAI.getStats()}")
                addLog("💧 ${LiquidityDepthAI.getStats()}")
                addLog("🔗 ${AICrossTalk.getStats()}")
                
                // Clean up old momentum data
                MomentumPredictorAI.cleanup()
                WhaleTrackerAI.cleanup()
                NarrativeDetectorAI.cleanup()
                TimeOptimizationAI.cleanup()
                LiquidityDepthAI.cleanup()
                AICrossTalk.cleanup()
                
                // Refresh time-based stats
                TimeOptimizationAI.refreshStats()
                NarrativeDetectorAI.refreshHeat()
                
                // Log cloud sync status (every ~35 mins = 5x7 loops)
                if (loopCount % 35 == 0) {
                    addLog("☁️ ${CloudLearningSync.getStatus()}")
                    
                    // Auto-discover top whale wallets
                    scope.launch {
                        try {
                            val added = copyTradeEngine.autoDiscoverTopWallets(maxWallets = 3, minPnlSol = 20.0)
                            if (added > 0) {
                                val stats = copyTradeEngine.getStats()
                                addLog("🐋 Whale discovery: +$added | tracking ${stats.activeWallets}")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            
            // Pattern Backtest - Run daily (every ~1440 loops at 1min intervals, or ~320 at 45s)
            // This analyzes historical trades to find which patterns work best
            if (loopCount % 300 == 0 && loopCount > 0) {
                scope.launch {
                    try {
                        tradeDb?.let { db ->
                            val report = PatternBacktester.runBacktest(db)
                            if (report.totalTrades >= 10) {
                                addLog("═══════════════════════════════════════════")
                                addLog("📊 PATTERN BACKTEST (${report.totalTrades} trades)")
                                addLog("Overall Win Rate: ${report.overallWinRate.toInt()}%")
                                
                                // Log top insights
                                report.insights.take(5).forEach { addLog(it) }
                                
                                // Log pattern recommendations
                                report.patterns.filter { it.recommendation in listOf("BOOST", "DISABLE") }
                                    .take(3).forEach { p ->
                                        addLog("${p.recommendation}: ${p.patternName} " +
                                            "(${p.winRate.toInt()}% win, PF=${String.format("%.1f", p.profitFactor)})")
                                    }
                                addLog("═══════════════════════════════════════════")
                                
                                // AUTO-TUNE: Apply backtest results to pattern weights
                                PatternAutoTuner.updateFromBacktest(report)
                                addLog("🎛️ ${PatternAutoTuner.getStatus()}")
                                
                                // CLOUD SYNC: Upload learnings to community database
                                try {
                                    val weights = AdaptiveLearningEngine.getDetailedWeights()
                                    val uploaded = CloudLearningSync.uploadLearnings(
                                        tradeCount = report.totalTrades,
                                        winRate = report.overallWinRate,
                                        featureWeights = weights,
                                        patternStats = report.patterns,
                                    )
                                    if (uploaded) {
                                        addLog("☁️ Shared learnings with community!")
                                    }
                                    
                                    // V5.6.12: Download community weights after upload
                                    val communityWeights = CloudLearningSync.downloadCommunityWeights()
                                    if (communityWeights != null && communityWeights.totalContributors > 0) {
                                        addLog("☁️ Synced: ${communityWeights.totalContributors} contributors, ${communityWeights.totalTrades} collective trades")
                                        // Apply community weights to local learning engine
                                        AdaptiveLearningEngine.applyCommunityWeights(communityWeights.featureWeights, communityWeights.totalTrades)
                                    }
                                } catch (e: Exception) {
                                    ErrorLogger.debug("CloudSync", "Upload error: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ErrorLogger.error("Backtest", "Pattern backtest error: ${e.message}")
                    }
                }
            }

            // Balance + P&L refresh
            scope.launch {
                try {
                    walletManager.refreshBalance()
                    val freshSol = walletManager.state.value.solBalance
                    status.walletSol = freshSol

                    // ── Shared wallet: broadcast live SOL balance to all traders ──────────
                    if (freshSol > 0.0) {
                        try { com.lifecyclebot.perps.CryptoAltTrader.updateLiveBalance(freshSol) } catch (_: Exception) {}
                        try { com.lifecyclebot.perps.TokenizedStockTrader.updateLiveBalance(freshSol) } catch (_: Exception) {}
                        try { com.lifecyclebot.perps.PerpsTraderAI.setLiveBalance(freshSol) } catch (_: Exception) {}
                        // V5.9.283: Auto-untrip STARTUP_FLOOR CB if wallet grew above minimum
                        if (!cfg.paperMode) LiveSafetyCircuitBreaker.updateBalance(freshSol)
                    }
                    

                    // ── Shared paper wallet: broadcast paper balance to all traders ──────

                    // Treasury milestone check — live mode uses real wallet; paper uses paper balance
                    // V5.5 FIX: Paper mode now also triggers milestones so scaling tiers work in testing
                    run {
                        val solPx = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
                        val balanceSol = if (cfg.paperMode) status.paperWalletSol else freshSol
                        TreasuryManager.onWalletUpdate(
                            walletSol    = balanceSol,
                            solPrice     = solPx,
                            onMilestone  = { milestone, walletUsd ->
                                val modeTag = if (cfg.paperMode) " [PAPER]" else ""
                                addLog("🏦 MILESTONE$modeTag: ${milestone.label} hit @ \$${walletUsd.toLong()}", "treasury")
                                if (milestone.celebrateOnHit) {
                                    sendTradeNotif("🎉 ${milestone.label}!",
                                        "Treasury now locking ${(milestone.lockPct*100).toInt()}% of profits",
                                        NotificationHistory.NotifEntry.NotifType.INFO)
                                }
                                TreasuryManager.save(applicationContext)
                                // V5.9.212: MILESTONE HANDOFF — signal TreasuryOpportunityEngine to
                                // actively seek re-deployment of newly locked capital. Previously
                                // the milestone callback was a no-op for deployment — capital sat idle.
                                if (TreasuryOpportunityEngine.isEnabled()) {
                                    TreasuryOpportunityEngine.onMilestoneHit(
                                        milestone = milestone.label,
                                        walletUsd = walletUsd,
                                        treasurySol = TreasuryManager.treasurySol,
                                    )
                                    addLog("🏦 Treasury deployment unlocked — seeking high-conviction opportunity post-milestone", "treasury")
                                }
                            }
                        )
                        
                        // V5.6.15: Sync RunTracker30D balance with actual paper wallet every 10 loops
                        if (cfg.paperMode && loopCount % 10 == 0) {
                            RunTracker30D.syncBalance(balanceSol)
                        }

                        // V5.9.399 — back-fund the paper wallet from treasury
                        // when it dries up. Only paper mode (live treasury is
                        // on-chain locked, not auto-pullable). Floor = 10% of
                        // configured starting capital. Runs every 5 loops to
                        // smooth out churn.
                        if (cfg.paperMode && loopCount % 5 == 0) {
                            try {
                                val floor = (cfg.paperSimulatedBalance * 0.10).coerceAtLeast(1.0)
                                val pulled = TreasuryManager.backFundPaperWalletIfLow(
                                    walletSol = balanceSol,
                                    floorSol  = floor,
                                    solPrice  = solPx,
                                )
                                if (pulled > 0.0) {
                                    executor.onPaperBalanceChange?.invoke(pulled)
                                    addLog("💸 Treasury back-fund → +${"%.4f".format(pulled)} SOL credited to paper wallet (floor=${"%.2f".format(floor)})", "treasury")
                                    TreasuryManager.save(applicationContext)
                                }
                            } catch (e: Exception) {
                                ErrorLogger.debug("BotService", "Treasury back-fund check error: ${e.message}")
                            }
                        }

                        // V5.9.220: Wire DrawdownCircuitAI — feed it real balance every loop.
                        // Paper mode: aggression penalties only kick in above a 15% drawdown threshold
                        // (the circuit's own thresholds are 3/6/10%, but paper is noisy early).
                        // Live mode: full sensitivity from day one.
                        if (!cfg.paperMode) {
                            com.lifecyclebot.v3.scoring.DrawdownCircuitAI.recordBalance(balanceSol)
                        } else if (loopCount % 5 == 0) {
                            // Paper: sample every ~5 loops to smooth noise; circuit still activates but
                            // only at higher DD because paper losses are expected in proof runs.
                            com.lifecyclebot.v3.scoring.DrawdownCircuitAI.recordBalance(balanceSol)
                        }
                    }
                    // Gather all trades across all tokens for P&L - use synchronized copy
                    val allTrades = synchronized(status.tokens) {
                        status.tokens.values.toList().flatMap { it.trades.toList() }
                    }
                    walletManager.updatePnl(allTrades)
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // CLOSED-LOOP FEEDBACK: Update FDG with wallet performance
                    // 
                    // The bot's LIFETIME win rate influences confidence thresholds.
                    // Winning = more aggressive, Losing = more defensive.
                    // Uses EMA smoothing (damping) to prevent whipsawing.
                    // ═══════════════════════════════════════════════════════════════════
                    val walletStats = walletManager.state.value
                    FinalDecisionGate.updateClosedLoopFeedback(
                        lifetimeWinRate = walletStats.winRate.toDouble(),
                        lifetimeTrades = walletStats.totalTrades,
                    )
                } catch (_: Exception) {}
            }


            var lastSuccessfulPollMs = System.currentTimeMillis()

            // V5.2: Prioritization moved AFTER TokenMergeQueue.processQueue()
            // See the prioritizedWatchlist definition after MergeQueue processing

            // Process all tokens in parallel — each gets its own coroutine.
            // This reduces per-cycle latency from (N×50ms + pollSeconds) to just pollSeconds.
            // V4.1: Lambda body extracted to processTokenCycle() to reduce compiler complexity
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: CHECK TRADING LAYER READINESS
            // Block all trading until AI layers are fully initialized
            // ═══════════════════════════════════════════════════════════════════
            if (!allTradingLayersReady && loopCount > 1) {
                addLog("⏳ Waiting for trading layers to initialize...")
                kotlinx.coroutines.delay(1000)
                continue  // Skip this cycle, try again
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.20: PROCESS MERGE QUEUE - Full Lifecycle Integration
            // 
            // This is where tokens actually get added to the watchlist.
            // MergeQueue batches scanner discoveries and deduplicates:
            //   - Token found by DEX_BOOSTED + PUMP_FUN = 1 add with confidence boost
            //   - Processes every 2 seconds after 5-second merge window
            // ═══════════════════════════════════════════════════════════════════
            try { processTokenMergeQueue(loopCount) }
            catch (e: Exception) { ErrorLogger.debug("BotService", "MergeQueue error: ${e.message}") }
            
            // ═══════════════════════════════════════════════════════════════════
            // V5.2 FIX: GET EFFECTIVE WATCHLIST FROM GLOBALTRADEREGISTRY
            // This MUST happen AFTER TokenMergeQueue.processQueue() which adds tokens!
            // Previously this was done BEFORE merge queue, causing newly added tokens
            // to be skipped until the next loop iteration.
            // ═══════════════════════════════════════════════════════════════════
            val registryWatchlist = GlobalTradeRegistry.getWatchlist()
            val effectiveWatchlist = if (registryWatchlist.isNotEmpty()) {
                registryWatchlist
            } else {
                // Fallback to config watchlist if registry is empty
                watchlist
            }
    

            // V5.2: Prioritize tokens for processing
val prioritizedWatchlist = if (cfg.v3EngineEnabled) {
    val nowMs = System.currentTimeMillis()
    effectiveWatchlist.sortedByDescending { mint ->
        val ts = status.tokens[mint]
        if (ts == null) {
            // V5.9.624 — unknown freshly-enqueued mints should get hydrated,
            // not buried. Also add a fair-coverage boost using registry metadata
            // so the protected 500-token bench actually reaches qualification.
            val entry = try { GlobalTradeRegistry.getEntry(mint) } catch (_: Throwable) { null }
            val neverProcessedBoost = if ((entry?.processCount ?: 0) == 0) 120.0 else 0.0
            val lrpBoost = entry?.lastProcessedAt?.let { last ->
                if (last <= 0L) 160.0 else ((nowMs - last).coerceAtLeast(0L) / 1_000.0).coerceAtMost(180.0)
            } ?: 80.0
            45.0 + neverProcessedBoost + lrpBoost
        } else {
            var priority = 0.0
            if (ts.position.isOpen) priority += 10_000.0 // Open positions always first

            // V5.9.624 — fair-coverage layer for the protected intake bench.
            // Never/least-recently processed candidates get a bounded boost.
            // This improves the AMOUNT of tokens that arrive to qualification
            // without pruning the watchlist or starving open-position exits.
            val entry = try { GlobalTradeRegistry.getEntry(mint) } catch (_: Throwable) { null }
            val neverProcessedBoost = if ((entry?.processCount ?: 0) == 0) 140.0 else 0.0
            val lrpBoost = entry?.lastProcessedAt?.let { last ->
                if (last <= 0L) 180.0 else ((nowMs - last).coerceAtLeast(0L) / 1_000.0).coerceAtMost(160.0)
            } ?: 90.0
            priority += neverProcessedBoost + lrpBoost

            // Deprioritize obvious shadow-only zombies, but never remove them.
            // Quality/freshness can still lift a candidate back up when data changes.
            if (ts.lastLiquidityUsd <= 0.0 && ts.phase in listOf("dying", "dead", "rug_likely", "distribution", "distributing")) {
                priority -= 120.0
            }

            // V5.9.602 — freshness/source boost for the meme snipe lane.
            // Previous ordering was liquidity-heavy, so old $2M+ tokens like
            // ZEREBRO stayed ahead while 0-2min PumpPortal launches were
            // deferred (processed=48 total=141). Fresh pump candidates are
            // where the edge lives; process them right after open positions.
            val ageMs = (nowMs - ts.addedToWatchlistAt).coerceAtLeast(0L)
            val freshBoost = when {
                ageMs < 60_000L -> 350.0
                ageMs < 180_000L -> 220.0
                ageMs < 300_000L -> 120.0
                else -> 0.0
            }
            val sourceBoost = when {
                ts.source.contains("PUMP", ignoreCase = true) -> 160.0
                ts.source.contains("DEX_BOOST", ignoreCase = true) -> 90.0
                ts.source.contains("TREND", ignoreCase = true) -> 55.0
                else -> 0.0
            }
            priority += freshBoost + sourceBoost

            // Keep quality signals, but cap liquidity so giant older pools do
            // not starve fresh memes. Fluid sizing/learning remains untouched.
            priority += (ts.lastLiquidityUsd / 2_000.0).coerceAtMost(45.0)
            priority += ts.entryScore
            priority += ts.meta.momScore * 0.8
            priority += ts.meta.volScore * 0.5
            priority += (ts.lastBuyPressurePct - 50.0).coerceIn(0.0, 35.0)
            priority
        }
    }
} else {
    effectiveWatchlist
}

// ───────────────────────────────────────────────────────────────
// PATCH: chunked watchlist processing with per-token timeout
// Prevent one overloaded loop from timing out the entire batch.
// Keeps open positions first, then processes the rest in small waves.
// ───────────────────────────────────────────────────────────────
val openPositionMints = prioritizedWatchlist.filter { mint ->
    val ts = status.tokens[mint]
    ts?.position?.isOpen == true || GlobalTradeRegistry.hasOpenPosition(mint)
}

// V5.9.494 — UNIVERSAL OPEN-POSITION COVERAGE.
// Operator forensics: VENIS Treasury sat at +161% (target +4%) and
// V3 VENIS at +29% with lock at +23% — neither sold because the
// scanner had stopped returning fresh data for those mints, so
// processTokenCycle never fired the exit checks. Treasury / ShitCoin /
// Quality / Moonshot / BlueChip all hold positions in their OWN
// stores; their mints can drop off the scanner radar even while
// those positions are still ACTIVELY losing money.
//
// Force-include every open position from sub-trader stores PLUS the
// V3 lane (status.tokens). Any mint with a live position somewhere
// is processed every tick, irrespective of scanner visibility.
val subTraderOpenMints: List<String> = try {
    val out = mutableSetOf<String>()
    // V5.9.610 — the old implementation only force-included Treasury even
    // though the comment promised all sub-trader stores. That let Moonshot /
    // ShitCoin / Quality / BlueChip / Dip / Express mints fall out of the hot
    // loop when scanner priority shifted, delaying exits and choking turnover.
    try { out.addAll(com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositionsSnapshot().map { it.mint }) } catch (_: Throwable) {}
    try { out.addAll(com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().map { it.mint }) } catch (_: Throwable) {}
    try { out.addAll(com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().map { it.mint }) } catch (_: Throwable) {}
    try { out.addAll(com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().map { it.mint }) } catch (_: Throwable) {}
    try { out.addAll(com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().map { it.mint }) } catch (_: Throwable) {}
    try { out.addAll(com.lifecyclebot.v3.scoring.DipHunterAI.getActiveDips().map { it.mint }) } catch (_: Throwable) {}
    try { out.addAll(com.lifecyclebot.v3.scoring.ShitCoinExpress.getActiveRides().map { it.mint }) } catch (_: Throwable) {}
    out.toList()
} catch (_: Exception) { emptyList() }

val v3OpenMints: List<String> = synchronized(status.tokens) {
    status.tokens.values.filter { it.position.isOpen }.map { it.mint }
}

val forcedOpenMints = (openPositionMints + subTraderOpenMints + v3OpenMints).distinct()

val otherMints = prioritizedWatchlist.filterNot { mint ->
    mint in openPositionMints || mint in subTraderOpenMints || mint in v3OpenMints
}

val orderedMints = (forcedOpenMints + otherMints).distinct()

val maxBatchMillis = if (cfg.paperMode) 15_000L else 25_000L
val perTokenTimeoutMs = if (cfg.paperMode) 1_200L else 2_500L
// V5.9.106: widen concurrency for fat watchlists. User logs showed
// processed=20 / total=64 (44 deferred per tick) — the existing caps
// couldn't keep up with the user's 50–100 token universe, so trades
// were being evaluated once every 2–3 ticks instead of every tick,
// slowing exits on fast-moving micro-caps.
// V5.9.162 — doubled again during bootstrap because the meme-trader
// volume test showed ~50% of tokens still deferred per tick at the
// V5.9.106 caps. On a ~100-token watchlist we want EVERY token
// evaluated EVERY tick, not every 2-3 ticks.
// V5.9.291 FIX: memeBootstrap must reflect the PERSISTED learning state, not just
// in-memory. In live mode after paper learning, getLearningProgress() uses the
// persisted trade count so this is now accurate. We also use ModeLeniency: if we
// have a proven edge we treat live the same as paper (wide-open concurrency) so
// the scanner doesn't get starved to a single token per cycle.
val memeBootstrap = try {
    val progress = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
    val lenient = com.lifecyclebot.engine.ModeLeniency.useLenientGates(cfg.paperMode)
    // Use bootstrap-level concurrency whenever:
    //   a) We are in bootstrap phase (< 40% progress), OR
    //   b) We have a proven edge (lenient=true) — treat like paper, use wider parallelism
    progress < 0.40 || lenient
} catch (_: Exception) { false }
// V5.9.175 — doubled the concurrency caps AGAIN because user logs still showed
// "processed=24 total=70" (46 deferred). At 70 watchlist tokens the previous
// 24-parallel cap meant 3 batches × 2.5s ≈ 7.5s, but with per-token timeouts
// and the 25s deadline a single slow fetch was burning whole batches. The
// new caps target EVERY token evaluated EVERY tick so fluid exits don't miss
// intra-tick moves on fast-moving micro-caps.
val maxParallel = if (memeBootstrap) {
    when {
        orderedMints.size >= 80 -> 96
        orderedMints.size >= 60 -> 72
        orderedMints.size >= 40 -> 48
        orderedMints.size >= 20 -> 32
        orderedMints.size >= 12 -> 20
        orderedMints.size >= 6  -> 12
        else -> 6
    }
} else {
    when {
        orderedMints.size >= 60 -> 20
        orderedMints.size >= 40 -> 16
        orderedMints.size >= 20 -> 10
        orderedMints.size >= 12 -> 8
        orderedMints.size >= 6  -> 6
        else -> 4
    }
}

val batchDeadline = System.currentTimeMillis() + maxBatchMillis
var processedCount = 0
var deferredCount = 0

supervisorScope {
    orderedMints.chunked(maxParallel).forEach { chunk ->
        if (!status.running) return@supervisorScope

        val timeLeft = batchDeadline - System.currentTimeMillis()
        if (timeLeft <= 250L) {
            deferredCount += (orderedMints.size - processedCount - deferredCount).coerceAtLeast(0)
            return@supervisorScope
        }

        val jobs = chunk.map { mint ->
            async {
                if (!status.running) return@async false
                if (orchestrator?.shouldPoll(mint) == false) return@async false

                val tokenBudget = minOf(
                    perTokenTimeoutMs,
                    (batchDeadline - System.currentTimeMillis()).coerceAtLeast(500L)
                )

                val completed = withTimeoutOrNull(tokenBudget) {
                    processTokenCycle(mint, cfg, wallet, lastSuccessfulPollMs)
                    try { GlobalTradeRegistry.markProcessed(mint) } catch (_: Throwable) {}
                    true
                } ?: false

                if (!completed) {
                    ErrorLogger.debug(
                        "BotService",
                        "Deferred token due to per-token timeout: $mint"
                    )
                }

                completed
            }
        }

        jobs.awaitAll().forEach { completed ->
            if (completed) {
                processedCount++
            } else {
                deferredCount++
            }
        }
    }
}

if (deferredCount > 0) {
    addLog("⏰ Watchlist load high - deferred $deferredCount token(s) to next cycle")
    ErrorLogger.warn(
        "BotService",
        "Watchlist processing deferred $deferredCount token(s); processed=$processedCount total=${orderedMints.size}"
    )
}

// V5.9.494 — UNIVERSAL EXIT SWEEP (every loop, last-resort safety).
// Runs AFTER per-token cycles. Catches Treasury / sub-trader exits that
// processTokenCycle missed (e.g. mint dropped off DexScreener feed,
// scanner deferred the mint due to load, or sub-trader keeps the
// position outside status.tokens). Operator forensics: VENIS Treasury
// sat at +161% (target +4%) and never exited. This sweep guarantees
// any open Treasury / sub-trader position is checked every loop,
// independent of scanner visibility.
sweepUniversalExits(cfg, wallet, status.getEffectiveBalance(cfg.paperMode))

            // V5.9.495z6 — WALLET RECONCILIATION (operator spec May 2026).
            // After all sub-trader cycles + universal exit sweep, sync the
            // PositionStore with the on-chain wallet truth. Recovers orphan
            // mints (positions=0 + ALREADY_OPEN drift bug) and closes
            // zombies (open position with zero wallet balance). Throttled
            // to every ~15s internally so calling every loop is fine.
            if (!cfg.paperMode) {
                wallet?.let { w ->
                    try {
                        WalletReconciler.reconcileWalletHoldings(status, w, isPaperMode = false)
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "WalletReconciler error: ${e.message?.take(80)}")
                    }
                }
            }
            // This runs during LIVE mode to learn from background paper trades
            // ═══════════════════════════════════════════════════════════════════
            if (cfg.shadowPaperEnabled && !cfg.paperMode) {
                try {
                    // Pass current token states so shadow positions can get price updates
                    val tokenStatesCopy = synchronized(status.tokens) {
                        status.tokens.toMap()
                    }
                    executor.checkShadowPositions(tokenStatesCopy)
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "Shadow position check error: ${e.message}")
                }
            }
            
            // HOT MODE SWITCH: Detect paperMode changes and propagate to all subsystems
            // This fixes the ghost-town bug where switching PAPER→LIVE left all
            // singletons still believing they were in paper mode.
            val loopCfg = ConfigStore.load(applicationContext)
            RuntimeModeAuthority.publishConfig(loopCfg.paperMode, loopCfg.autoTrade)
            val loopIsPaper = loopCfg.paperMode
            if (GlobalTradeRegistry.isPaperMode != loopIsPaper) {
                ErrorLogger.info("BotService", "🔄 HOT MODE SWITCH DETECTED: paper=${GlobalTradeRegistry.isPaperMode} → $loopIsPaper")
                GlobalTradeRegistry.isPaperMode = loopIsPaper
                UnifiedModeOrchestrator.isPaperMode = loopIsPaper
                EfficiencyLayer.isPaperMode = loopIsPaper
                FinalExecutionPermit.isPaperMode = loopIsPaper
                // Re-initialize V3 engine with new mode (hot-swap, preserves learning)
                if (loopCfg.v3EngineEnabled) {
                    com.lifecyclebot.v3.V3EngineManager.updateMode(loopCfg)
                }
                addLog("🔄 Mode switched to ${if (loopIsPaper) "📝 PAPER" else "🔴 LIVE"} — all AI layers updated")
            }

        // Periodically persist session state - use synchronized copy
            val tradeCount = synchronized(status.tokens) {
                status.tokens.values.toList().sumOf { it.trades.size }
            }
            if (tradeCount % 5 == 0 && status.running) {
                try { SessionStore.save(applicationContext, cfg.paperMode) } catch (_: Exception) {}
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // V5.6.9: PERIODIC POSITION PERSISTENCE
            // Save open positions every 6 loops (~30 seconds) for crash recovery
            // This ensures we don't lose positions if Android kills the app
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 6 == 0) {
                try {
                    val tokensCopy = synchronized(status.tokens) { status.tokens.toMap() }
                    PositionPersistence.saveAllPositions(tokensCopy)
                    // V5.6.28: Also save CashGenerationAI treasury state
                    com.lifecyclebot.v3.scoring.CashGenerationAI.save(force = true)
                    // V5.6.28d: Also save SmartSizer streaks
                    SmartSizer.save(force = true)
                    // V5.6.28e: Also save BehaviorAI state
                    com.lifecyclebot.v3.scoring.BehaviorAI.save(force = true)
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "Position persistence save error: ${e.message}")
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // CYCLIC TRADE ENGINE — $500 USD compound ring
            // ═══════════════════════════════════════════════════════════════════
            // V5.9.222: Always tick CyclicTradeEngine — it runs paper permanently.
            // Live mode is gated internally by cfg.cyclicTradeLiveEnabled / treasury threshold.
            if (loopCount % 10 == 0) {
                try {
                    val cyclicTokens = synchronized(status.tokens) { status.tokens.toMap() }
                    CyclicTradeEngine.tick(
                        context   = applicationContext,
                        tokens    = cyclicTokens,
                        executor  = executor,
                        wallet    = wallet,
                        walletSol = walletManager.state.value.solBalance,
                    )
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "CyclicTradeEngine tick error: ${e.message}", e)
                }
            }

            delay(cfg.pollSeconds * 1000L)
          } catch (e: Exception) {
            // Catch any crash in the main loop and log it
            addLog("❌ Loop error: ${e.message}")
            delay(5000) // wait 5 seconds before retrying
          }
        }
    }

    // ── logging ────────────────────────────────────────────

    /**
     * Aggressively clean up the watchlist to make room for new opportunities.
     * Removes tokens that are:
     * - Blocked by safety checker (rugcheck failed)
     * - Stale (no price updates)
     * - Dead (zero liquidity or volume)
     * - IDLE too long without getting a buy signal
     * - Underperforming (flat price with no buys)
     */
    /**
     * V5.9.494 — UNIVERSAL EXIT SWEEP.
     *
     * Operator: 'where are the fluid stops and dynamic profit lockers?
     * nothing should rip 35% out of us! ensure all trading tools layers
     * etc are wired correctly for live trading.'
     *
     * Runs at the end of every main loop tick. Hits two coverage gaps
     * that processTokenCycle leaves open:
     *
     *   1) CashGenerationAI Treasury positions whose mints aren't in
     *      status.tokens (or whose ts.position.isTreasuryPosition is
     *      stale). The treasury exit signal at line ~9246 only fires
     *      when ts is found AND tagged as treasury — for older bot
     *      restarts or sub-trader-private positions, that gate misses.
     *
     *   2) V3 lane open positions (status.tokens with isOpen=true)
     *      where the scanner stopped returning fresh quotes. The
     *      V5.9.426 fallback path catches some of these but doesn't
     *      run profit-lock or partial-sell logic — only hard SL.
     *
     * For (1) we ask CashGenerationAI for any non-HOLD signals and
     * route them through executor.requestSell, synthesising a
     * TokenState if the mint isn't in status.tokens.
     *
     * For (2) we iterate every open V3 position and call
     * executor.runManageOnly() which executes:
     *      checkProfitLock → checkPartialSell → riskCheck (fluid stops)
     * once. Cheap on the happy path (returns immediately if no
     * trigger), and finally fires the sells we keep missing.
     */

    /**
     * V5.9.634 — Freeze Detector + Cap Diagnostics
     *
     * Symptom this fixes: bot ramps to ~85 trades / 13 open then halts
     * entirely for 30+ minutes while PumpPortal intake is still flowing
     * and the scanner is alive. Either:
     *   (a) all sub-traders are silently rejecting every candidate
     *       (RejectStats from V5.9.633 will tell us which one), OR
     *   (b) every open position is stuck pendingVerify / dead and the
     *       per-lane concurrent cap is thus saturated, OR
     *   (c) some boolean flag (paused, halted, distrusted) flipped on
     *       and never flipped back.
     *
     * We track [CanonicalLearningCounters.executedTradesTotal] — if it
     * has not advanced for 3 minutes WHILE marketScanner != null AND
     * we have at least one open meme position, fire one automated
     * unfreeze. All actions are cheap and idempotent. Self-rate-limited
     * to one fire per 5 minutes.
     *
     * Extracted from botLoop in V5.9.634c so the loop stays under the
     * JVM 64KB per-method bytecode ceiling.
     */
    private suspend fun runFreezeDetectorTick(
        loopCount: Int,
        cfg: com.lifecyclebot.data.BotConfig,
    ) {
        try {
            val now = System.currentTimeMillis()
            val curExec = com.lifecyclebot.engine.CanonicalLearningCounters
                .executedTradesTotal.get()
            if (curExec != freezeLastExecCount) {
                freezeLastExecCount = curExec
                freezeLastExecChangeMs = now
            }

            // ── Cap diagnostics: per-lane open count + caps ────────
            val memeOpen = status.tokens.values.count { ts ->
                try { ts.position.qtyToken > 0.0 } catch (_: Throwable) { false }
            }
            val memePending = status.tokens.values.count { ts ->
                try { ts.position.pendingVerify && ts.position.qtyToken > 0.0 } catch (_: Throwable) { false }
            }
            val memeCap = if (cfg.paperMode) cfg.maxConcurrentPositions else cfg.maxConcurrentLivePositions
            val cbState = try {
                if (::securityGuard.isInitialized) securityGuard.getCircuitBreakerState() else null
            } catch (_: Throwable) { null }
            val haltedTag = when {
                cbState == null              -> ""
                cbState.isHalted             -> " · HALTED:${cbState.haltReason.take(30)}"
                cbState.consecutiveLosses>=3 -> " · streak=${cbState.consecutiveLosses}"
                else                         -> ""
            }
            val freezeAgeSec = (now - freezeLastExecChangeMs) / 1000
            val capLine = "🪪 caps: meme=$memeOpen/$memeCap (pending=$memePending) · execAge=${freezeAgeSec}s · execTotal=$curExec$haltedTag"
            ErrorLogger.info("BotService", capLine)
            addLog(capLine)

            // ── Freeze detector ────────────────────────────────────
            val staleMs = now - freezeLastExecChangeMs
            val scannerAlive = marketScanner != null
            val haveOpens = memeOpen > 0
            val canFireAgain = (now - freezeRecoveryFiredAt) > 5 * 60_000L
            val freezeStaleThresholdMs = 3 * 60_000L
            if (staleMs > freezeStaleThresholdMs && scannerAlive && haveOpens && canFireAgain) {
                freezeRecoveryFiredAt = now
                ErrorLogger.error("BotService",
                    "🔓 FREEZE_DETECTOR: 0 executions in ${staleMs/1000}s while scanner alive + memeOpen=$memeOpen — running auto-unfreeze")
                addLog("🔓 FREEZE_DETECTOR fired (${staleMs/1000}s stale) — auto-unfreezing")

                // 1) Reset scanner staleness in case it's silently
                //    paused on a saturated cooldown map.
                try { marketScanner?.checkAndResetIfStale() } catch (_: Throwable) {}

                // 2) Reconnect external streams (PumpPortal /
                //    DataOrchestrator / Pyth Hermes), fail-open.
                try { orchestrator?.reconnectStreams() } catch (_: Throwable) {}

                // 3) If SecurityGuard is halted from a stale streak,
                //    clear it. The next loss will re-halt it; we just
                //    don't want a permanent stuck halt blocking buys.
                try {
                    if (::securityGuard.isInitialized) {
                        val cb = securityGuard.getCircuitBreakerState()
                        if (cb.isHalted) {
                            ErrorLogger.warn("BotService",
                                "🔓 FREEZE_DETECTOR: SecurityGuard halted (${cb.haltReason}) — clearing halt")
                            securityGuard.clearHalt()
                        }
                    }
                } catch (_: Throwable) {}

                // 4) Clear FinalDecisionGate's learning state — V5.9.182
                //    already exposes this for the same reason at boot.
                try { FinalDecisionGate.resetLearningState() } catch (_: Throwable) {}

                // 5) Bump the AntiChokeManager.
                try {
                    com.lifecyclebot.engine.AntiChokeManager.tick(
                        isPaperMode = cfg.paperMode,
                        wallet      = wallet,
                        tokens      = status.tokens,
                        loopCount   = loopCount,
                    )
                } catch (_: Throwable) {}

                addLog("🔓 FREEZE_DETECTOR: scanner reset · streams reconnected · halt cleared · FDG learning state cleared")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "Freeze detector tick error: ${e.message}")
        }
    }


    private fun sweepUniversalExits(
        cfg: com.lifecyclebot.data.BotConfig,
        wallet: com.lifecyclebot.network.SolanaWallet?,
        effectiveBalance: Double,
    ) {
        // Part 1 — Treasury sub-trader exits (any mint, with or without ts).
        try {
            val treasuryExits = com.lifecyclebot.v3.scoring.CashGenerationAI
                .checkAllPositionsForExit()
            treasuryExits.forEach { (mint, signal) ->
                if (signal == com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.HOLD) return@forEach
                val ts = synchronized(status.tokens) { status.tokens[mint] }
                    ?: synthesizeTreasuryTokenState(mint) ?: return@forEach
                // V5.9.495i — POST-BUY SETTLE-IN GRACE for treasury sweeps
                // too. Operator: "it buys them then 5 seconds later it
                // sells them". 45s breathing room before any exit fires.
                val posAgeMs = System.currentTimeMillis() - ts.position.entryTime
                if (posAgeMs < 45_000L) return@forEach
                val pnlPct = if (ts.position.entryPrice > 0)
                    ((ts.lastPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
                else 0.0
                ErrorLogger.warn(
                    "BotService",
                    "🧹 SWEEP TREASURY-EXIT: ${ts.symbol} | $signal | pnl=${pnlPct.toInt()}% — mint missed processTokenCycle",
                )
                try {
                    val r = executor.requestSell(
                        ts        = ts,
                        reason    = "TREASURY_${signal.name}_SWEEP",
                        wallet    = wallet,
                        walletSol = effectiveBalance,
                    )
                    if (r == com.lifecyclebot.engine.Executor.SellResult.CONFIRMED ||
                        r == com.lifecyclebot.engine.Executor.SellResult.PAPER_CONFIRMED) {
                        com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                            mint, ts.lastPrice, signal,
                        )
                        addLog("🧹 [SWEEP] TREASURY ${ts.symbol}: ${signal.name} +${pnlPct.toInt()}% | result=$r")
                    }
                } catch (e: Exception) {
                    ErrorLogger.warn("BotService", "Sweep treasury sell error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "Sweep treasury error: ${e.message}")
        }

        // Part 2 — V3 lane: ensure profit-lock + partial + risk fire on
        // every open position, not just those with fresh scanner data.
        try {
            val openTokens = synchronized(status.tokens) {
                status.tokens.values.filter { it.position.isOpen }.toList()
            }
            openTokens.forEach { ts ->
                try { executor.runManageOnly(ts, wallet, effectiveBalance) }
                catch (e: Exception) {
                    ErrorLogger.debug("BotService", "Sweep manage(${ts.symbol}): ${e.message}")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "Sweep V3-manage error: ${e.message}")
        }
    }

    /**
     * Build a minimal TokenState from a TreasuryPosition so the sell
     * pipeline can dump it even when the V3 lane never registered
     * the mint. Best-effort — entry price, qty and lastPrice are
     * pulled from the treasury record so executor.requestSell has
     * everything it needs to compute size + minOut.
     */
    private fun synthesizeTreasuryTokenState(mint: String): com.lifecyclebot.data.TokenState? {
        val pos = try {
            com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
        } catch (_: Exception) { null } ?: return null
        val ts = com.lifecyclebot.data.TokenState(
            mint   = mint,
            symbol = pos.symbol,
            source = "TREASURY_SYNTH",
        )
        ts.position = ts.position.copy(
            entryPrice         = pos.entryPrice,
            costSol            = pos.entrySol,
            entryTime          = pos.entryTime,
            qtyToken           = if (pos.entryPrice > 0) pos.entrySol / pos.entryPrice else 0.0,
            isTreasuryPosition = true,
            tradingMode        = "TREASURY",
            isPaperPosition    = pos.isPaper,
        )
        ts.lastPrice = if (pos.currentPrice > 0) pos.currentPrice else pos.entryPrice
        synchronized(status.tokens) {
            if (!status.tokens.containsKey(mint)) {
                status.tokens[mint] = ts
            }
        }
        return ts
    }

    private suspend fun cleanupWatchlist() {
        // V5.9.624 — PROTECTED MEME INTAKE: non-destructive shadow classifier.
        //
        // HARD OPERATOR RULE: the Meme Trader scanner/watchlist is a protected
        // 500-token Solana intake bench. We build around it; we do NOT prune,
        // choke, shrink, route around, reset, or delete candidates just because
        // they are idle/stale/flat/low-liq/waiting. Earlier cleanup versions
        // removed tokens from GlobalTradeRegistry/status.tokens and fed ordinary
        // non-entry states into scanner rejectedMints. That improved short-term
        // loop cleanliness but quietly reduced arrival volume and starved the
        // learning/trading stack.
        //
        // This function now only annotates/telemeters candidate state. Safety
        // decisions are handled by the entry gates; scheduler fairness handles
        // processing budget. Open-position protection remains absolute.
        val registryWatchlist = GlobalTradeRegistry.getWatchlist()
        if (registryWatchlist.isEmpty()) return

        val cfg = ConfigStore.load(applicationContext)
        val now = System.currentTimeMillis()
        val isPaperMode = cfg.paperMode

        val staleThresholdMs = if (isPaperMode) 300_000L else 180_000L
        val idleThresholdMs = if (isPaperMode) 600_000L else 300_000L
        val maxWatchlistAge = if (isPaperMode) 7_200_000L else 1_800_000L
        val waitTimeout = if (isPaperMode) 300_000L else 120_000L

        var shadowTagged = 0
        var hardSafetyTagged = 0
        var staleTagged = 0
        var idleTagged = 0
        var phaseTagged = 0
        var lowLiqTagged = 0
        var timeoutTagged = 0
        var waitTagged = 0
        var flatTagged = 0
        var missingStateTagged = 0

        fun shadow(ts: com.lifecyclebot.data.TokenState?, mint: String, phase: String, message: String, hardSafety: Boolean = false) {
            shadowTagged++
            if (hardSafety) {
                hardSafetyTagged++
                // Only hard-safety shadows get per-token forensic logs. Ordinary
                // stale/idle/wait/flat states are summarized below so a 500-token
                // bench does not create its own logging/IO choke.
                try {
                    LiveTradeLogStore.log(
                        tradeKey = "INTAKE_${mint.take(16)}",
                        mint = mint,
                        symbol = ts?.symbol ?: mint.take(6),
                        side = "INFO",
                        phase = LiveTradeLogStore.Phase.WATCHLIST_PROTECT_HELD_TOKEN,
                        message = "🛡 protected intake hard-shadow · $phase · $message",
                        traderTag = "INTAKE",
                    )
                } catch (_: Throwable) {}
            }
        }

        for (mint in registryWatchlist) {
            val ts = synchronized(status.tokens) { status.tokens[mint] }

            // Held bags are never cleanup candidates. Exit management and the
            // universal sweep own them, independent of scanner visibility.
            if (
                HostWalletTokenTracker.hasOpenPosition(mint) ||
                ts?.position?.isOpen == true ||
                GlobalTradeRegistry.hasOpenPosition(mint) ||
                com.lifecyclebot.v3.scoring.QualityTraderAI.hasPosition(mint) ||
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.hasPosition(mint) ||
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.hasPosition(mint) ||
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(mint) ||
                com.lifecyclebot.v3.scoring.ManipulatedTraderAI.hasPosition(mint) ||
                com.lifecyclebot.v3.scoring.CashGenerationAI.hasPosition(mint)
            ) continue

            if (ts == null) {
                missingStateTagged++
                shadow(null, mint, "MISSING_STATE", "registry mint awaiting TokenState hydrate")
                continue
            }

            val ageInWatchlist = now - ts.addedToWatchlistAt
            val lastUpdate = ts.history.lastOrNull()?.ts ?: ts.addedToWatchlistAt
            val dataAge = now - lastUpdate

            if (ts.safety.isBlocked) {
                val reason = ts.safety.hardBlockReasons.firstOrNull() ?: "Safety check failed"
                shadow(ts, mint, "SAFETY_SHADOW", reason, hardSafety = true)
                // Do not mark scanner rejected and do not remove from registry.
                // Entry/FDG safety will block execution while the candidate
                // remains observable for learning, telemetry, and rehydration.
                continue
            }

            if (TokenBlacklist.isBlocked(mint)) {
                val reason = TokenBlacklist.getBlockReason(mint)
                shadow(ts, mint, "BLACKLIST_SHADOW", reason, hardSafety = true)
                continue
            }

            if (ageInWatchlist < 90_000L) {
                if (!isPaperMode && ts.lastLiquidityUsd in 0.0..0.5) {
                    lowLiqTagged++
                    shadow(ts, mint, "FRESH_ZERO_LIQ_SHADOW", "liq=${ts.lastLiquidityUsd}")
                }
                continue
            }

            // V5.9.640 — do NOT shadow ordinary idle candidates. "idle" is the
            // default pre-qualification phase for fresh protected-intake tokens,
            // not a failure state. Everything is meant to hit the watchlist bench
            // for upstream qualification; only explicit blocked/dead/stale states
            // should be side-lined. The old IDLE_SHADOW block is intentionally
            // removed.

            if (ts.phase in listOf("dying", "dead", "rug_likely", "distribution") && ts.history.size >= 3) {
                phaseTagged++
                shadow(ts, mint, "PHASE_SHADOW", "phase=${ts.phase} history=${ts.history.size}")
            }

            if (lastUpdate > 0 && dataAge > staleThresholdMs) {
                staleTagged++
                shadow(ts, mint, "STALE_SHADOW", "dataAge=${dataAge / 1000}s")
            }

            if (ts.lastLiquidityUsd < 200 && !isPaperMode) {
                lowLiqTagged++
                shadow(ts, mint, "LOW_LIQ_SHADOW", "liq=$${ts.lastLiquidityUsd.toInt()}")
            }

            if (ageInWatchlist > maxWatchlistAge && ts.trades.isEmpty()) {
                timeoutTagged++
                shadow(ts, mint, "TIMEOUT_SHADOW", "age=${ageInWatchlist / 60000}m no trade")
            }

            if (ts.signal == "WAIT" && ageInWatchlist > waitTimeout && ts.trades.isEmpty()) {
                waitTagged++
                shadow(ts, mint, "WAIT_SHADOW", "age=${ageInWatchlist / 1000}s")
            }

            if (!isPaperMode && ts.history.size >= 6) {
                val recentCandles = ts.history.takeLast(6)
                val priceRange = recentCandles.maxOf { it.priceUsd } - recentCandles.minOf { it.priceUsd }
                val avgPrice = recentCandles.map { it.priceUsd }.average()
                val priceChangePercent = if (avgPrice > 0) (priceRange / avgPrice) * 100 else 0.0
                val totalBuys = recentCandles.sumOf { it.buysH1 }
                if (priceChangePercent < 1.5 && totalBuys < 2) {
                    flatTagged++
                    shadow(ts, mint, "FLAT_SHADOW", "range=${"%.2f".format(priceChangePercent)}% buys=$totalBuys")
                }
            }
        }

        if (shadowTagged > 0) {
            ErrorLogger.info(
                "BotService",
                "Protected intake shadow pass: tagged=$shadowTagged hard=$hardSafetyTagged stale=$staleTagged idle=$idleTagged phase=$phaseTagged lowLiq=$lowLiqTagged timeout=$timeoutTagged wait=$waitTagged flat=$flatTagged missing=$missingStateTagged watchlist=${registryWatchlist.size} removed=0"
            )
            if (hardSafetyTagged > 0) {
                addLog("🛡 Intake protected: hardShadow=$hardSafetyTagged removed=0 watchlist=${registryWatchlist.size}")
            }
        }
    }

    /**
     * Initialize all trading modes/layers with current configuration.
     * Extracted from botLoop to reduce function complexity and avoid compiler stack overflow.
     */

    /**
     * Process a single token's full cycle - price fetch, evaluation, trading decisions.
     * V4.1: Extracted from botLoop to reduce compiler complexity (was causing StackOverflow).
     */
    private fun processTokenCycle(mint: String, cfg: BotConfig, wallet: SolanaWallet?, lastSuccessfulPollMs: Long) {
        try {
            // V5.9.495z42 P1 — opportunistic recovery-lock unlock attempt at
            // the top of every per-token cycle. Covers all V3 sub-trader
            // sell paths (BlueChip / ShitCoin / Quality / Moonshot /
            // CashGen / Manipulated) that consult checkExit() further down
            // this function — without this the unlock side only fires from
            // Executor.liveSell + the treasury tick, missing positions
            // owned by other lanes. Rate-limited internally (30s/mint),
            // runs on Dispatchers.IO so this is safe to call every cycle.
            try {
                com.lifecyclebot.engine.sell.RecoveryLockUnlocker.maybeAttemptUnlock(
                    mint = mint,
                    symbol = status.tokens[mint]?.symbol ?: mint.take(6),
                    wallet = wallet,
                    jupiterApiKey = cfg.jupiterApiKey,
                )
            } catch (_: Throwable) { /* never break the cycle */ }

            // V5.9.495z43 operator spec item E — fire the live wallet
            // reconciler from every scan cycle. Runs on IO so this is
            // non-blocking. RPC empty-map is safely treated as UNKNOWN
            // (no state change). Updates TokenLifecycleTracker for every
            // tracked mint with chain-confirmed UI balance.
            try {
                com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(
                    wallet = wallet,
                    reason = "cycle_${mint.take(6)}",
                )
            } catch (_: Throwable) { /* never break the cycle */ }

            // Primary price source: Dexscreener
            // V5.9.615 — when DexScreener has no pair (pre-graduation pump.fun
            // tokens), try the fallback price providers (pump.fun API / Birdeye).
            // If they succeed, synthesize a PairInfo so the cycle can continue
            // into the V3 / ShitCoin entry path. ShitCoin is *designed* for
            // exactly these tokens; without this synthesize step we choke the
            // entire pre-graduation meme lane the operator wants us to trade.
            val pair = dex.getBestPair(mint) ?: run {
                val ts = status.tokens[mint] ?: return
                val refreshed = tryFallbackPriceData(mint, ts)
                val synth = if (refreshed && ts.lastPrice > 0.0) synthesizeFallbackPair(ts) else null
                if (synth == null) {
                    // No usable price — last-resort exit safety net.
                    if (ts.position.qtyToken > 0.0 && ts.position.entryPrice > 0.0) {
                        runFallbackSafetyExit(ts, cfg, wallet)
                    }
                    return
                }
                synth  // continue into the rest of the cycle with synthesized pair
            }

            // ═══════════════════════════════════════════════════════════════
            // V5.9.62: PRE-PIPELINE DECIMAL-CORRUPTION FILTER
            //
            // The scanner occasionally ingests quotes where the price is off
            // by 10^N (wrong decimal place reported by DexScreener), which
            // produces open positions with phantom +440,000,000% "wins".
            //
            // This filter runs BEFORE the token is added to status.tokens
            // and before any price update is persisted. It ONLY rejects
            // clearly broken feed data — it never blocks legitimate wins,
            // never touches the wallet, never closes open positions.
            //
            // Rules (all conservative):
            //   a) First-ingest: reject if price implies impossible supply
            //      (< 1 or > 1e15 tokens) OR if FDV/MCAP ratio > 1000.
            //   b) Already-tracked: reject the INCOMING tick if it would
            //      jump the price by more than 100× vs the last recorded
            //      ts.lastPrice (i.e. +9900% in a single poll — physically
            //      impossible in a normal meme candle unless the feed is
            //      misreporting decimals). The position stays open at its
            //      last valid price; a future healthy tick recovers it.
            // ═══════════════════════════════════════════════════════════════
            val incomingPrice = pair.candle.priceUsd
            val incomingMcap  = pair.candle.marketCap
            val incomingFdv   = pair.fdv
            val existingTs    = status.tokens[mint]

            val firstIngest = existingTs == null
            if (firstIngest && incomingPrice > 0 && incomingMcap > 0) {
                val impliedSupply = incomingMcap / incomingPrice
                val badSupply     = impliedSupply < 1.0 || impliedSupply > 1e15
                val badFdvRatio   = incomingFdv > 0 && incomingMcap > 0 && incomingFdv / incomingMcap > 1000
                if (badSupply || badFdvRatio) {
                    ErrorLogger.warn("BotService",
                        "🚫 DISCOVERY-FILTER rejected ${pair.baseSymbol.ifBlank { mint.take(6) }}: " +
                        "price=$incomingPrice mcap=$incomingMcap fdv=$incomingFdv impliedSupply=$impliedSupply " +
                        "(badSupply=$badSupply badFdv=$badFdvRatio) — decimal-corrupt feed, not adding to scanner"
                    )
                    return   // pre-pipeline — token never enters status.tokens
                }
            }
            if (!firstIngest && incomingPrice > 0 && existingTs!!.lastPrice > 0) {
                val jumpMult = incomingPrice / existingTs.lastPrice
                if (jumpMult > 100.0 || (jumpMult > 0 && jumpMult < 0.01)) {
                    ErrorLogger.warn("BotService",
                        "🚫 TICK-FILTER rejected ${existingTs.symbol}: prev=${existingTs.lastPrice} → new=$incomingPrice " +
                        "(jump=${"%.1f".format(jumpMult)}x) — decimal-shift artifact, keeping last valid price"
                    )
                    return   // drop this poll entirely; position stays at last good price
                }
            }


        synchronized(status.tokens) {
            if (!status.tokens.containsKey(mint)) {
                status.tokens[mint] = TokenState(
                    mint       = mint,
                    symbol     = pair.baseSymbol.ifBlank { mint.take(6) },
                    name       = pair.baseName,
                    pairAddress = pair.pairAddress,
                    pairUrl    = pair.url,
                    source     = "WATCHLIST",  // Tokens loaded from config
                    // V5.9.642 — no-logo watchlist hydrate. Do not make token
                    // surfacing depend on DexScreener image availability.
                    logoUrl    = "",
                )
            }
            val ts = status.tokens[mint] ?: return
            
            // Try to infer source if unknown
            if (ts.source.isEmpty() || ts.source == "UNKNOWN") {
                ts.source = when {
                    pair.url.contains("pump.fun") -> "PUMP_FUN_GRADUATE"
                    pair.url.contains("raydium") -> "RAYDIUM_NEW_POOL"
                    else -> "DEX_TRENDING"
                }
            }
            
            // Defense-in-depth: confirm the pair's base token is the mint we polled for.
            // getBestPair() already filters this, but guard here in case of stale cache hits.
            if (pair.baseTokenAddress.isNotBlank() && pair.baseTokenAddress != mint) {
                ErrorLogger.warn("BotService", "DATA POLLUTION GUARD: ${ts.symbol} pair baseToken=${pair.baseTokenAddress} != queried mint=$mint — skipping price update")
                return
            }

            // V5.9.62: decimal/price-sanity was hoisted above to act as a
            // discovery-gate + tick-gate. By the time we reach here the
            // quote is trusted enough to persist.
            val validatedPrice = incomingPrice
            
            ts.lastPrice        = validatedPrice
            ts.lastMcap         = pair.candle.marketCap
            ts.lastLiquidityUsd = pair.liquidity
            ts.lastFdv          = pair.fdv

            // V5.9: Populate lastBuyPressurePct from polling candle data.
            // Previously only set by DexScreener WebSocket — tokens without an active
            // WS subscription were stuck at default 50.0, causing ShitCoinExpress (>=55)
            // and ManipulatedTraderAI to never trigger on polled tokens.
            val candleTxns = pair.candle.buysH1 + pair.candle.sellsH1
            if (candleTxns > 0) {
                ts.lastBuyPressurePct = pair.candle.buysH1.toDouble() / candleTxns * 100.0
            }

            // V3.2: Update shadow learning engine with price
            if (pair.candle.priceUsd > 0) {
                com.lifecyclebot.v3.learning.ShadowLearningEngine.updatePrice(
                    ts.mint, pair.candle.priceUsd
                )

                // V5.2: Update CashGenerationAI (Treasury) with independent price tracking
                // V5.9.188c: ALWAYS update so Treasury positions never show stale +0.0%
                com.lifecyclebot.v3.scoring.CashGenerationAI.updatePrice(
                    ts.mint, pair.candle.priceUsd
                )
                // V5.9.188c: CashGenerationAI.updatePrice() is the Treasury UI's price source
                // (PriceAggregator doesn't exist in this codebase)

                // V5.9.398 — push the same live price into the other 4 meme
                // sub-traders so the unified open-positions card always shows
                // fresh P&L%, not stale entry-price=current snapshots. Each
                // updateLivePrice is a fast no-op if the trader doesn't hold
                // this mint, so calling all 4 unconditionally is safe.
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.updateLivePrice(ts.mint, pair.candle.priceUsd)
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.updateLivePrice(ts.mint, pair.candle.priceUsd)
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.updateLivePrice(ts.mint, pair.candle.priceUsd)
                com.lifecyclebot.v3.scoring.QualityTraderAI.updateLivePrice(ts.mint, pair.candle.priceUsd)

                // V5.8: Compute price momentum — ShitCoinExpress pre-filter needs >= 3.0
                // ts.momentum was NEVER assigned anywhere, blocking all Express entries
                // V5.9.116: Require only 3 candles (was 5) so fresh launches get a
                // momentum read sooner — Express was silent on most tokens because
                // it hadn't collected 5 candles yet.
                val histSnap = ts.history
                val histSize = histSnap.size
                val refPrice = when {
                    histSize >= 5 -> histSnap.elementAtOrNull(histSize - 5)?.priceUsd ?: 0.0
                    histSize >= 3 -> histSnap.elementAtOrNull(histSize - 3)?.priceUsd ?: 0.0
                    histSize >= 2 -> histSnap.firstOrNull()?.priceUsd ?: 0.0
                    else -> 0.0
                }
                ts.momentum = if (refPrice > 0) (pair.candle.priceUsd - refPrice) / refPrice * 100.0 else null
            }
            
            synchronized(ts.history) {
                ts.history.addLast(pair.candle)
                if (ts.history.size > 300) ts.history.removeFirst()
            }
            // Update peak holder count and growth rate
            val latestHolders = pair.candle.holderCount
            if (latestHolders > ts.peakHolderCount) ts.peakHolderCount = latestHolders
            if (ts.history.size >= 12) {
                val recentH = ts.history.takeLast(3).map { it.holderCount }.filter { it > 0 }
                val earlierH = ts.history.takeLast(12).take(6).map { it.holderCount }.filter { it > 0 }
                if (recentH.isNotEmpty() && earlierH.isNotEmpty()) {
                    val rAvg = recentH.average(); val eAvg = earlierH.average()
                    ts.holderGrowthRate = if (eAvg > 0) (rAvg - eAvg) / eAvg * 100.0 else 0.0
                }
            }
        }

        val ts = status.tokens[mint] ?: return
        
        // ═══════════════════════════════════════════════════════════════════
        // PAPER MODE LEARNING: Update shadow tracking for blocked trades
        // Track what would have happened if we traded FDG-blocked opportunities
        // ═══════════════════════════════════════════════════════════════════
        if (cfg.paperMode && ts.ref > 0) {
            ShadowLearningEngine.updateBlockedTradePrices(mint, ts.ref)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // Skip permanently banned tokens immediately (REAL MODE ONLY)
        // In paper mode, we want to keep trading banned tokens for learning
        // ═══════════════════════════════════════════════════════════════════
        if (!cfg.paperMode && BannedTokens.isBanned(mint)) {
            // V5.9.413 — sticky held-position guard. Even banned mints must
            // stay in status.tokens if we hold an open position, otherwise
            // we can never price-check or close the bag.
            if (GlobalTradeRegistry.hasOpenPosition(mint)) {
                ErrorLogger.debug("BotService", "🛡️ Banned token ${ts.symbol} kept — held position")
                return
            }
            // V5.9.624 — protected intake rule: do not remove banned mints
            // from status.tokens / scanner-visible state. Retain as shadow-only;
            // entry remains blocked by BannedTokens while scheduler fairness and
            // shadow deprioritization prevent it from becoming a loop choke.
            try {
                com.lifecyclebot.engine.MemePipelineTracer.blocked(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    reason = "BANNED_TOKEN_SHADOW",
                    detail = "Banned token retained in protected intake; execution blocked",
                )
            } catch (_: Throwable) {}
            ErrorLogger.debug("BotService", "Banned token ${ts.symbol} shadow-retained in protected intake")
            return
        }
        
        // ── Rug Detection - Learn from sudden liquidity/price drops ──
        if (ts.history.size >= 3) {
            val recentCandles = ts.history.takeLast(3)
            val olderCandles = ts.history.takeLast(6).take(3)
            
            val recentLiq = pair.liquidity
            val olderLiq = olderCandles.firstOrNull()?.let { 
                // Estimate older liquidity from mcap if available
                ts.history.takeLast(6).firstOrNull()?.marketCap?.let { it * 0.1 } ?: recentLiq 
            } ?: recentLiq
            
            val recentPrice = recentCandles.lastOrNull()?.priceUsd ?: 0.0
            val olderPrice = olderCandles.firstOrNull()?.priceUsd ?: recentPrice
            
            // Detect potential rug: BOTH price drop AND liquidity collapse required.
            // Using OR was causing false rug learning: olderLiq is estimated from mcap×0.1
            // when real data is missing, producing fake 100% liq drops on tokens with $0
            // reported liquidity — poisoning TradingMemory with fabricated rug patterns.
            // Requiring BOTH conditions means only genuine multi-signal events are learned.
            if (olderPrice > 0 && recentPrice > 0) {
                val priceDropPct = ((olderPrice - recentPrice) / olderPrice) * 100
                val liqDropPct = if (olderLiq > 0) ((olderLiq - recentLiq) / olderLiq) * 100 else 0.0

                // V5.9.204: Also catch pure price rugs (>80% drop) even without liq collapse
                val isPriceRug = priceDropPct >= 80.0  // catastrophic price wipe = rug regardless of liq
                val isDualRug = priceDropPct >= 50 && liqDropPct >= 70
                if (isPriceRug || isDualRug) {  // was AND-only — missed price-only rugs
                    val ageHours = (System.currentTimeMillis() - (ts.addedToWatchlistAt)) / 3_600_000.0
                    val volumeSpike = recentCandles.sumOf { it.vol } > olderCandles.sumOf { it.vol } * 2

                    TradingMemory.learnFromRug(
                        mint = mint,
                        symbol = ts.symbol,
                        creatorWallet = null,  // Would need API to get this
                        liquidityDropPct = liqDropPct,
                        priceDropPct = priceDropPct,
                        volumeSpikeBeforeRug = volumeSpike,
                        holderDumpDetected = ts.holderGrowthRate < -20,
                        timeFromLaunchHours = ageHours,
                    )
                    addLog("🤖 AI RUG LEARNED: ${ts.symbol} | price -${priceDropPct.toInt()}% liq -${liqDropPct.toInt()}% | Pattern saved", mint)
                    TokenBlacklist.block(mint, "Rug detected: price -${priceDropPct.toInt()}%")
                }
            }
        }
        
        // ── Safety check (cached 10 min) ──────────────────────
    val safetyAge = System.currentTimeMillis() - ts.lastSafetyCheck
    if (safetyAge > 10 * 60_000L) {
        scope.launch {
            try {
                val pairCreatedAt = pair.pairCreatedAtMs.takeIf { it > 0L } ?: pair.candle.ts
                // V5.9.605 — LIVE MEME THROTTLE ROOT CAUSE:
                // pair.liquidity can be 0 during first hydration/RPC races even
                // when the scanner just admitted the same mint with real liquidity
                // (operator log: CUCK scanner liq=$24k → safety LIQ HARD BLOCK $0).
                // Resolve from best-known state before applying the live $2k floor.
                val regEntry = try { GlobalTradeRegistry.getEntry(mint) } catch (_: Throwable) { null }
                // V5.9.495z49 — operator log 17:03 evidence: PumpPortal fresh
                // launches (mcap=27-31 SOL ≈ $2400-$2750 USD) were getting
                // estimated at $240-$275 (× 0.10) and silently failing the
                // live $2K hard floor → "🚫 LIQ HARD BLOCK SLIMEY $299" etc.
                // The pump.fun bonding curve actually has liquidity ≈ mcap
                // BEFORE graduation (the curve's SOL reserve dominates), so
                // 0.10× wildly under-estimates pre-graduation tokens.
                // Use 0.85× when source is PumpPortal (and pair.liquidity
                // is still 0 — not yet on Raydium). For graduated/other
                // sources, keep the original conservative 0.10 multiplier.
                val isPumpBondingCurve = (pair.liquidity <= 0.0) &&
                    (regEntry?.source?.contains("PUMP", ignoreCase = true) == true)
                val registryLiqEstimate = (regEntry?.initialMcap ?: 0.0).let { mcap ->
                    if (mcap > 0.0) {
                        if (isPumpBondingCurve) mcap * 0.85 else mcap * 0.10
                    } else 0.0
                }
                val resolvedLiquidityUsd = listOf(
                    pair.liquidity,
                    ts.lastLiquidityUsd,
                    registryLiqEstimate,
                ).filter { it.isFinite() && it > 0.0 }.maxOrNull() ?: pair.liquidity

                if (pair.liquidity <= 0.0 && resolvedLiquidityUsd > 0.0) {
                    ErrorLogger.info("BotService", "💧 Safety liquidity fallback: ${ts.symbol} pair=$${pair.liquidity.toInt()} ts=$${ts.lastLiquidityUsd.toInt()} regEst=$${registryLiqEstimate.toInt()} → using $${resolvedLiquidityUsd.toInt()}")
                }

                val report = safetyChecker.check(
                    mint            = mint,
                    symbol          = ts.symbol,
                    name            = ts.name,
                    pairCreatedAtMs = pairCreatedAt,
                    currentLiquidityUsd = resolvedLiquidityUsd,  // V5.9.605: best-known liq, not raw pair zero
                    score = ts.entryScore.toInt().coerceIn(0, 100), // V5.9.605: allow RC_PENDING live override for strong candidates
                )
                synchronized(ts) {
                    ts.safety       = report
                    ts.lastSafetyCheck = System.currentTimeMillis()
                }
                when (report.tier) {
                    SafetyTier.HARD_BLOCK -> {
                        val reason = report.hardBlockReasons.firstOrNull() ?: "Safety check"
                        addLog("SAFETY BLOCK [${ts.symbol}]: $reason", mint)
                        sendTradeNotif("Token Blocked", "${ts.symbol}: ${report.summary}",
                            NotificationHistory.NotifEntry.NotifType.SAFETY_BLOCK)
                        val isTransientLiquidityBlock = reason.startsWith("Liquidity ", ignoreCase = true) && resolvedLiquidityUsd <= 0.0
                        if (isTransientLiquidityBlock) {
                            ErrorLogger.warn("BotService", "⚠️ NOT blacklisting ${ts.symbol}: transient unresolved liquidity safety block ($reason)")
                        } else {
                            TokenBlacklist.block(mint, "Safety: $reason")
                        }
                        // Permanent ban for rug/scam patterns
                        if (reason.contains("rug", ignoreCase = true) || 
                            reason.contains("honeypot", ignoreCase = true) ||
                            reason.contains("scam", ignoreCase = true)) {
                            BannedTokens.ban(mint, "Safety: $reason")
                            addLog("🚫 PERMANENTLY BANNED: ${ts.symbol} - $reason", mint)
                        }
                        soundManager.playSafetyBlock()
                    }
                    SafetyTier.CAUTION -> {
                        addLog("SAFETY CAUTION [${ts.symbol}]: ${report.summary}", mint)
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    // Note: lastSuccessfulPollMs update is handled in botLoop

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.407 — IDLE → SCANNING transition.
    // The TokenState's default phase is literally "idle". Without this nudge,
    // any token that gets blocked by an early gate (HardRugPreFilter,
    // DistributionFadeAvoider, etc) never escapes "IDLE" in the UI even
    // though we are actively examining it every cycle. Promoting to
    // "scanning" the moment we touch the token gives the user honest live
    // progress in the watchlist column.
    // ═══════════════════════════════════════════════════════════════════
    if (ts.phase == "idle" && !ts.position.isOpen) {
        ts.phase = "scanning"
    }

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.449 — REMOVED V5.9.417 HARD_SL_BACKSTOP (-25% memes / -30% others).
    // The backstop was killing winners that wicked past -25% before they
    // could recover — exactly the meme runners that produced build-1941's
    // 60% WR + 500% trades. Build-1941 era trusted each sub-trader's own
    // HARD_FLOOR (ShitCoin -20, Quality SL ladder, Moonshot -20) and let
    // wicks survive. With ShitCoinTraderAI.HARD_FLOOR_STOP_PCT now back
    // at -20 (V5.9.449 revert) and the universal back-fund still in place
    // (TreasuryManager $500 floor + MemeWREmergencyBrake), the backstop
    // is redundant choke pressure. See V5.9.449 deep-dive write-up.
    // ═══════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════
    // STEP 0: CORE EVALUATIONS (needed by subsequent steps)
    // ═══════════════════════════════════════════════════════════════════
    val curveState = BondingCurveTracker.evaluate(ts)
    val whaleState = WhaleDetector.evaluate(mint, ts)

    // V5.9.418 — REFRESH SYMBOLIC CONTEXT PER MEME CYCLE.
    // SymbolicContext.refresh() is internally throttled (2s) so calling it
    // here every cycle is cheap; this guarantees that the 24-channel
    // symbolic nervous system has *this* token's symbol/mint stamped on
    // its latest snapshot before V3 / FDG / sub-traders read it. Without
    // this, the per-token signals (CrossRegime, LeadLag, Fragility,
    // PortfolioHeat, …) were only being refreshed once per outer botLoop
    // cycle from the global symbol — meme entries on hot launches got the
    // BTC/SOL signal map instead of their own.
    try { com.lifecyclebot.engine.SymbolicContext.refresh(ts.symbol, ts.mint) } catch (_: Throwable) {}
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 1: HARD RUG PRE-FILTER
    // Kill obvious garbage BEFORE consuming strategy/FDG cycles
    // V5.2 FIX: Pass paperMode to allow learning from all tokens
    // ═══════════════════════════════════════════════════════════════════
    if (!ts.position.isOpen) {
        val preFilterResult = try {
            HardRugPreFilter.filter(ts, cfg.paperMode)
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "PreFilter error: ${e.message}")
            HardRugPreFilter.PreFilterResult(true, null, HardRugPreFilter.FilterSeverity.PASS)
        }
        
        if (!preFilterResult.pass) {
            HardRugPreFilter.logFailure(ts, preFilterResult)
            // V5.9.407 — surface why we bailed so the watchlist stops showing IDLE.
            ts.phase = when {
                preFilterResult.reason?.contains("LIQUID", true) == true -> "thin_liq"
                preFilterResult.reason?.contains("CONCEN", true) == true -> "rug_holders"
                preFilterResult.reason?.contains("AUTH",  true) == true  -> "rug_auth"
                else                                                      -> "rug_filtered"
            }
            return  // Skip to next token (exit this coroutine)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 2: DISTRIBUTION FADE AVOIDER
    // Check if token is in distribution/dead-bounce state
    // Note: edgePhase comes from ScoreResult later, so we pass null here
    // and rely on price/volume pattern detection instead
    // V5.2 FIX: Pass paperMode to allow learning from distribution trades
    // ═══════════════════════════════════════════════════════════════════
    val distributionCheck = try {
        DistributionFadeAvoider.evaluate(ts, null, cfg.paperMode)
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "DistFade error: ${e.message}")
        DistributionFadeAvoider.FadeResult(false, null, 1.0, 0L)
    }
    
    if (distributionCheck.shouldBlock && !ts.position.isOpen) {
        ErrorLogger.info("BotService", "🔻 ${ts.symbol} DISTRIBUTION_FADE: ${distributionCheck.reason}")
        ts.phase = "distributing"   // V5.9.407 — surface gate reason in UI
        // V5.9.495z50 — operator log 17:43 evidence:
        // The watchlist gets dominated by drained/rugged tokens (HEG, Harambe,
        // Hitler etc.) that keep firing DISTRIBUTION_FADE every cycle. These
        // zombies starve fresh high-liq candidates (HANTY $3242, GIGARAT $21733)
        // out of the per-cycle processing budget. When a token is draining
        // ($0 current liquidity) AND not held, evict it from watchlist + status
        // immediately so fresh candidates get a fair shot.
        val drainedZombie = ts.lastLiquidityUsd <= 0.0 &&
            distributionCheck.reason?.contains("DRAIN", ignoreCase = true) == true
        if (drainedZombie) {
            // V5.9.624 — protected intake rule: do NOT evict drained zombies from
            // GlobalTradeRegistry/status.tokens. Tag them as shadow-only and let
            // the fair scheduler deprioritize them. This preserves the scanner /
            // watchlist as an intake universe while still preventing a drained
            // token from becoming an entry candidate.
            try {
                com.lifecyclebot.engine.MemePipelineTracer.blocked(
                    mint = ts.mint, symbol = ts.symbol,
                    reason = "DRAINED_ZOMBIE_SHADOW",
                    detail = "liq=\$0 + DISTRIBUTION_FADE — shadow-only, retained in protected intake",
                )
            } catch (_: Throwable) {}
            try {
                LiveTradeLogStore.log(
                    tradeKey = "INTAKE_${ts.mint.take(16)}",
                    mint = ts.mint,
                    symbol = ts.symbol,
                    side = "INFO",
                    phase = LiveTradeLogStore.Phase.WATCHLIST_PROTECT_HELD_TOKEN,
                    message = "🛡 drained zombie shadow-only retained in protected intake",
                    traderTag = "INTAKE",
                )
            } catch (_: Throwable) {}
        }
        return  // Skip to next token (exit this coroutine)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 3: LIQUIDITY BUCKET CLASSIFICATION
    // Determines which mode family is appropriate
    // ═══════════════════════════════════════════════════════════════════
    val liquidityBucket = try {
        LiquidityBucketRouter.classify(ts)
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "LiqBucket error: ${e.message}")
        null
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 4: REENTRY RECOVERY CHECK
    // If token previously failed, use stricter recovery rules
    // ═══════════════════════════════════════════════════════════════════
    val isRecoveryCandidate = ReentryRecoveryMode.isRecoveryCandidate(ts.mint)
    val recoveryEval = if (isRecoveryCandidate && !ts.position.isOpen) {
        try {
            val eval = ReentryRecoveryMode.evaluateRecovery(ts)
            ReentryRecoveryMode.logEvaluation(ts, eval)
            eval
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "ReentryRecovery error: ${e.message}")
            null
        }
    } else null
    
    // Auto-mode evaluation - must come before strategy.evaluate
    val trendRank  = try { null } catch (_: Exception) { null } // from CoinGecko cache
    val modeConf   = if (cfg.autoMode) {
        autoMode.evaluate(ts, whaleState.whaleScore, trendRank, curveState.stage)
    } else null
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 5: MODE ROUTER - Classify what KIND of trade this is
    // 
    // Pipeline: prefilter → distribution check → liq bucket → mode classifier → FDG
    // ═══════════════════════════════════════════════════════════════════
    val modeClassification = try {
        ModeRouter.classify(ts)
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "ModeRouter error: ${e.message}")
        ModeRouter.Classification(
            tradeType = ModeRouter.TradeType.UNKNOWN,
            confidence = 0.0,
            signals = emptyList(),
            subSignals = emptyMap(),
        )
    }
    
    // Apply distribution penalty to mode classification confidence
    val adjustedModeConfidence = modeClassification.confidence * distributionCheck.scoreMultiplier
    
    // Apply liquidity bucket size multiplier
    val liqSizeMultiplier = liquidityBucket?.maxSizeMultiplier ?: 1.0
    
    // Apply recovery mode restrictions if applicable
    val finalSizeMultiplier = if (recoveryEval?.canReenter == true) {
        liqSizeMultiplier * recoveryEval.sizeMultiplier
    } else {
        liqSizeMultiplier
    }
    
    // Run specialized scanners for additional signals
    val scanResult = try {
        ModeSpecificScanners.scanAll(ts)
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "ModeScanner error: ${e.message}")
        null
    }
    
    // Log significant classifications
    if (modeClassification.tradeType != ModeRouter.TradeType.UNKNOWN && 
        modeClassification.confidence > 50 && !ts.position.isOpen) {
        ModeRouter.logClassification(ts, modeClassification)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PRIORITY 2: Use unified evaluateWithDecision for complete analysis
    // Pass isPaperMode to relax Edge veto in paper mode for better learning
    // Pass brain for adaptive threshold learning
    // ═══════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════
    // MOMENTUM PREDICTOR AI: Record price/volume data for pattern detection
    // ═══════════════════════════════════════════════════════════════════
    try {
        if (ts.history.size >= 2) {
            val lastCandle = ts.history.lastOrNull()
            if (lastCandle != null) {
                MomentumPredictorAI.recordPricePoint(
                    mint = mint,
                    symbol = ts.symbol,
                    price = lastCandle.priceUsd,
                    volume = lastCandle.vol,
                    buyPressure = (lastCandle.buyRatio * 100),
                    txCount = lastCandle.buysH1 + lastCandle.sellsH1,
                )
            }
        }
    } catch (e: Exception) {
        ErrorLogger.debug("BotService", "MomentumPredictorAI record error: ${e.message}")
    }
    
    val modeConfForEval = if (cfg.autoMode) modeConf else null
    // V5.9.58: Gentle self-heal if the scanner has choked itself off.
    try { executor.brain?.maybeEaseDrought() } catch (_: Exception) {}
    val (result, decision) = strategy.evaluateWithDecision(ts, modeConfForEval, cfg.paperMode, executor.brain)

        synchronized(ts) {
            ts.phase      = result.phase
            ts.signal     = result.signal
            ts.entryScore = result.entryScore
            ts.exitScore  = result.exitScore
            ts.meta       = result.meta
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 CLEAN RUNTIME: Strategy output is now INPUT to V3, not a decision
        // 
        // Old flow: Strategy → "BUY SIGNAL" → CANDIDATE → FDG → V3 (nested)
        // New flow: Strategy → V3 input → V3 SCORING → V3 DECISION → Execute
        // 
        // Strategy still calculates entry/exit scores, phases, quality - but
        // these become V3 CandidateSnapshot extras, not decision authority.
        // V3 is the ONLY thing that outputs EXECUTE/WATCH/REJECT.
        // ═══════════════════════════════════════════════════════════════════
        
        // NOTE: Legacy logs like "BUY SIGNAL", "shouldTrade", "CANDIDATE" 
        // are now suppressed. V3 will output clean decision logs.

        // Sentiment refresh (every sentimentPollMins)
    val sentAge = System.currentTimeMillis() - ts.lastSentimentRefresh
    if (cfg.sentimentEnabled && sentAge > cfg.sentimentPollMins * 60_000L) {
        scope.launch {
            try {
                val fresh = sentimentEngine.refresh(mint, ts.symbol, ts.lastPrice)
                synchronized(ts) {
                    ts.sentiment = fresh
                    ts.lastSentimentRefresh = System.currentTimeMillis()
                }
                if (fresh.blocked) {
                    addLog("SENTIMENT BLOCK: ${fresh.blockReason}", mint)
                    sendTradeNotif("Blocked", "${ts.symbol}: ${fresh.blockReason}", NotificationHistory.NotifEntry.NotifType.SAFETY_BLOCK)
                }
            } catch (_: Exception) {}
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SMART CHART SCANNER — Multi-timeframe pattern detection
    // Runs asynchronously to avoid blocking main evaluation loop
    // Feeds insights to SuperBrainEnhancements for learning
    // ═══════════════════════════════════════════════════════════════════
    if (ts.history.size >= 10) {
        scope.launch {
            try {
                // V5.9.495z14 — every 10th scan (per-token), run a FULL
                // multi-timeframe scan instead of the 5-min quickScan. Long-
                // horizon patterns (Cup & Handle, Head & Shoulders, Wedges,
                // Dead Cat Bounce, etc.) need 1h+ candles which quickScan
                // never reaches. Sample 1-in-10 to cap CPU cost.
                val tick = smartChartScanCounter.incrementAndGet()
                val results = if (tick % 10 == 0L) SmartChartScanner.scan(ts)
                              else listOfNotNull(SmartChartScanner.quickScan(ts))
                val scanResult = results.maxByOrNull { it.confidence }
                if (scanResult != null && scanResult.confidence >= 60) {
                    // V5.9.495z14 — push pattern enum names into cache so the
                    // entry decision can read them via getPatternNames() and
                    // apply PatternAutoTuner.getPatternMultiplier(name).
                    val patternNames = results.flatMap { r ->
                        r.candlePatterns.map { it.name } + r.chartPatterns.map { it.name }
                    }.distinct()
                    SmartChartCache.update(
                        mint = ts.mint,
                        bias = scanResult.overallBias,
                        confidence = scanResult.confidence,
                        patternNames = patternNames,
                    )
                    val patternStr = buildString {
                        scanResult.candlePatterns.forEach { append(it.emoji) }
                        scanResult.chartPatterns.forEach { append(it.emoji) }
                    }
                    if (patternStr.isNotEmpty()) {
                        ErrorLogger.debug("SmartChart", 
                            "${ts.symbol}: $patternStr ${scanResult.overallBias} (${scanResult.confidence.toInt()}%)")
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "SmartChart scan error: ${e.message}")
            }
        }
    }

    // Blacklist check — immediate skip if blocked
    if (TokenBlacklist.isBlocked(mint)) {
        if (ts.position.isOpen) {
            // Force exit if we somehow hold a blacklisted token
            val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
            executor.maybeAct(ts, "EXIT", 0.0, effectiveBalance, wallet,
                lastSuccessfulPollMs, status.openPositionCount, status.totalExposureSol)
        }
        return
    }

    // In PAUSED mode: no new entries (existing positions still managed)
    if (modeConf?.mode == AutoModeEngine.BotMode.PAUSED && !ts.position.isOpen) return

    // Trade on ALL watchlist tokens simultaneously
    val cbState = securityGuard.getCircuitBreakerState()
    val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
    
    // ═══════════════════════════════════════════════════════════════════
    // TRADE IDENTITY: Get or create canonical identity for this token
    // This ensures mint/symbol are always consistent across all systems
    // ═══════════════════════════════════════════════════════════════════
    val identity = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
    
    // ═══════════════════════════════════════════════════════════════════
    // V3 CLEAN RUNTIME: V3 is the PRIMARY and ONLY decision authority
    // 
    // Flow:
    //   1. Basic eligibility (dedupe, fatal suppression)
    //   2. V3 processes token using Strategy output as input
    //   3. V3 outputs: EXECUTE/WATCH/REJECT/BLOCK with clean logs
    //   4. Only EXECUTE triggers trade
    //
    // Legacy concepts (shouldTrade, BUY SIGNAL, CANDIDATE) are internal
    // notes only - they do NOT control execution anymore.
    // ═══════════════════════════════════════════════════════════════════
    
    // DEDUPE: Skip if we recently evaluated this token
    val (canProposeEarly, dedupeReason) = TradeLifecycle.canPropose(identity.mint)
    if (!canProposeEarly && !ts.position.isOpen) {
        // V5.0: Log why tokens are being skipped (for debugging)
        ErrorLogger.debug("BotService", "⏭️ SKIP: ${identity.symbol} | $dedupeReason")
        return
    }
    
    // FATAL SUPPRESSION: Only rugged/honeypot/unsellable blocks
    val isFatalSuppression = DistributionFadeAvoider.isFatalSuppression(identity.mint)
    if (isFatalSuppression && !ts.position.isOpen) {
        val reason = DistributionFadeAvoider.checkRawStrategySuppression(identity.mint)
        ErrorLogger.info("BotService", "[FATAL] ${identity.symbol} | BLOCK | $reason")
        return
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // V5.9.248: UNIVERSAL ENTRY GATES — apply to ALL layers, no exceptions
    //   1. Re-entry cooldown  — don't rebuy the same token we just sold
    //   2. Volume gate        — don't buy dead tokens with no activity
    // ═══════════════════════════════════════════════════════════════════
    if (!ts.position.isOpen) {
        // V5.9.408 — free-range mode bypass: skip every cooldown / streak /
        // volume gate below so the bot takes every shot it can get for the
        // first 3000 trades of learning exposure.
        val wideOpen = FreeRangeMode.isWideOpen()

        // 1. RE-ENTRY COOLDOWN — 5 min for all layers, not just Treasury
        val closedAgoMs = System.currentTimeMillis() - (BotService.recentlyClosedMs[ts.mint] ?: 0L)
        if (!wideOpen && closedAgoMs < BotService.RE_ENTRY_COOLDOWN_MS) {
            ErrorLogger.debug("BotService", "⏳ [COOLDOWN] ${identity.symbol} | SKIP | closed ${closedAgoMs/1000}s ago (min ${BotService.RE_ENTRY_COOLDOWN_MS/1000}s)")
            return
        }

        // V5.9.353: 1b. LOSS-STREAK BLOCK — refuse re-entry on a mint whose
        // last 3 closes were all losses (block elapses after 1 hour).
        val lossBlockUntil = MemeLossStreakGuard.blockedUntilMs(ts.mint)
        if (!wideOpen && lossBlockUntil > 0L) {
            val remainingMin = (lossBlockUntil - System.currentTimeMillis()) / 60_000L
            ErrorLogger.debug("BotService", "🛑 [LOSS_STREAK] ${identity.symbol} | SKIP | 3-loss streak — blocked ${remainingMin}min more")
            return
        }

        // 2. VOLUME GATE — no volume = dead token, don't buy
        val lastVolumeH1 = ts.history.lastOrNull()?.volumeH1 ?: 0.0
        val learningPct  = try { com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() } catch (_: Exception) { 0.0 }
        // V5.9.408: relaxed floor in wide-open mode so thin-volume meme
        // tokens still flow to the traders for exposure.
        val minVolumeH1  = when {
            wideOpen            -> 100.0
            learningPct < 0.40  -> 500.0
            else                -> 2_000.0
        }
        if (lastVolumeH1 < minVolumeH1) {
            // V5.9.606 — in paper/free-range, unknown volume from fresh
            // PumpPortal/Dex hydration must not silently starve V3. If the
            // token has real liquidity/mcap, let downstream scoring decide.
            val unknownVolumeButTradable = cfg.paperMode && wideOpen && lastVolumeH1 <= 0.0 &&
                (ts.lastLiquidityUsd >= 2_000.0 || ts.lastMcap >= 10_000.0)
            if (!unknownVolumeButTradable) {
                ErrorLogger.debug("BotService", "🔇 [VOL_GATE] ${identity.symbol} | SKIP | \$${lastVolumeH1.toInt()} h1vol < \$${minVolumeH1.toInt()} (dead token)")
                return
            }
            ErrorLogger.info("BotService", "🔓 [VOL_GATE_BYPASS] ${identity.symbol} | paper free-range unknown h1vol but liq=\$${ts.lastLiquidityUsd.toInt()} mcap=\$${ts.lastMcap.toInt()}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // V3 ENGINE: Process token through unified scoring
    // Strategy output (phase, entry/exit scores, quality) feeds into V3
    // V3 is the ONLY thing that decides EXECUTE/WATCH/REJECT
    // ═══════════════════════════════════════════════════════════════════
    if (!ts.position.isOpen && cfg.v3EngineEnabled && com.lifecyclebot.v3.V3EngineManager.isReady()) {
        // V5.9.495z50 — confirm V3 engine reached for this candidate so the
        // operator can verify the watchlist→V3 handoff fires.
        try {
            com.lifecyclebot.engine.MemePipelineTracer.stage(
                "V3_REACHED", ts.mint, ts.symbol,
                "src=${ts.source} liq=${ts.lastLiquidityUsd.toInt()} score=${ts.entryScore.toInt()}",
            )
        } catch (_: Throwable) {}        
        // ═══════════════════════════════════════════════════════════════════
        // V3.3: CHECK FOR RECOVERY OPPORTUNITY FIRST
        // If this token was stopped out but has bounced, consider re-entry
        // ═══════════════════════════════════════════════════════════════════
        val isRecoveryCandidate = try {
            executor.checkRecoveryOpportunity(ts)
        } catch (_: Exception) { false }
        
        if (isRecoveryCandidate) {
            ErrorLogger.info("BotService", "🔄 RECOVERY ENTRY: ${identity.symbol} | Bounced from stop - attempting recovery trade")
            addLog("🔄 RECOVERY: ${identity.symbol} bounced - attempting re-entry", ts.mint)
            
            // Execute recovery trade with smaller size and tighter stops
            val recoverySize = effectiveBalance * 0.02  // 2% position max for recovery
            
            try {
                executor.v3Buy(
                    ts = ts,
                    sizeSol = recoverySize.coerceAtMost(0.05),  // Max 0.05 SOL for recovery
                    walletSol = effectiveBalance,
                    v3Score = 50,  // Moderate score for recovery
                    v3Band = "RECOVERY",
                    v3Confidence = 60.0,
                    wallet = wallet,
                    lastSuccessfulPollMs = lastSuccessfulPollMs,
                    openPositionCount = status.openPositionCount,
                    totalExposureSol = status.totalExposureSol
                )
                
                addLog("⚡ RECOVERY EXECUTED: ${identity.symbol} | ${recoverySize.fmt(4)} SOL", ts.mint)
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "Recovery trade error: ${e.message}")
            }
            
            // Skip normal V3 processing for this token
            return
        }
        
        // Calculate token age in minutes
        val tokenAgeMinutes = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60000.0
        
        // Log discovery (entry point to V3 pipeline)
        ErrorLogger.debug("BotService", "[DISCOVERY] ${identity.symbol} | src=${ts.source} liq=${ts.lastLiquidityUsd.toInt()} age=${tokenAgeMinutes.toInt()}m")
        
        // Check AI degradation state
        val isAIDegraded = try {
            GeminiCopilot.isAIDegraded()
        } catch (e: Exception) {
            false
        }
        
        try {
            val v3Decision = com.lifecyclebot.v3.V3EngineManager.processToken(
                ts = ts,
                walletSol = effectiveBalance,
                totalExposureSol = status.totalExposureSol,
                openPositions = status.openPositionCount,
                recentWinRate = botBrain?.getRecentWinRate() ?: 50.0,
                recentTradeCount = botBrain?.getTradeCount() ?: 0,
                marketRegime = modeConf?.mode?.name ?: "NEUTRAL",
                isAIDegraded = isAIDegraded  // V3 SELECTIVITY: Pass AI degradation
            )

            // ═══════════════════════════════════════════════════════════════════
            // V5.9.349 — MEME UNIFIED SCORER BRIDGE (universal visibility)
            //
            // The bridge sees EVERY meme token that reaches the V3 engine,
            // not just V3-rejected ones. We compute its verdict once here
            // and override any non-execute V3 outcome in paper mode when
            // the bridge says shouldEnter. Live mode always defers to V3.
            // ═══════════════════════════════════════════════════════════════════
            // V5.9.618 — Bridge computation now runs in BOTH paper and live (was paper-only).
            // The override at line ~9550 is still paper-gated, so live behaviour is unchanged
            // EXCEPT live now gets the verdict as an advisory the meme buy paths can read.
            // This finally lets the proven 79% WR architecture contribute to live decisions
            // without bypassing V3 — V3 still controls. Pure additive nudge.
            val memeBridgeVerdict: com.lifecyclebot.v3.MemeUnifiedScorerBridge.MemeVerdict? =
                if (!ts.position.isOpen) {
                    try { com.lifecyclebot.v3.MemeUnifiedScorerBridge.scoreForEntry(ts) }
                    catch (e: Exception) {
                        ErrorLogger.debug("BotService", "🌉 Bridge scoring error for ${ts.symbol}: ${e.message}")
                        null
                    }
                } else null
            memeBridgeVerdict?.let { mv ->
                ErrorLogger.debug("BotService",
                    "🌉 Bridge${if (cfg.paperMode) "" else "[LIVE-ADVISORY]"}: ${identity.symbol} | tech=${mv.techScore} v3=${mv.v3Score} blend=${mv.blendedScore} mult=${"%.2f".format(mv.trustMultiplier)} enter=${mv.shouldEnter}${if (mv.rejectReason != null) " rej=${mv.rejectReason}" else ""}"
                )
            }
            // V5.9.618 — Stash bridge verdict on TokenState so meme evaluators downstream
            // can read it as a small confidence bonus. Cleared on next pass / position close.
            ts.bridgeAdvisoryAgrees = (memeBridgeVerdict?.shouldEnter == true)
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0 FIX: Register V3 decision with FinalExecutionPermit
            // ONLY register HARD BLOCKS (safety issues, fatal problems)
            // Soft rejections (low score, timing) should NOT block Treasury/ShitCoin
            // ═══════════════════════════════════════════════════════════════════
            when (val result = v3Decision) {
                is com.lifecyclebot.v3.V3Decision.BlockFatal -> {
                    // HARD BLOCK: Safety issue - block ALL layers
                    FinalExecutionPermit.registerRejection(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        reason = result.reason,
                        rejectedBy = "V3_BLOCK_FATAL",
                        v3Score = 0,
                        v3Confidence = 0
                    )
                }
                is com.lifecyclebot.v3.V3Decision.Blocked -> {
                    // HARD BLOCK: Safety/exposure issue - block ALL layers
                    FinalExecutionPermit.registerRejection(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        reason = result.reason,
                        rejectedBy = "V3_BLOCK",
                        v3Score = 0,
                        v3Confidence = 0
                    )
                }
                is com.lifecyclebot.v3.V3Decision.Execute -> {
                    // V3 approved - register approval to clear any previous rejection
                    FinalExecutionPermit.registerApproval(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        approvedBy = "V3",
                        v3Score = result.score,
                        v3Confidence = result.confidence.toInt()
                    )
                }
                // V4.0 FIX: These are SOFT decisions - DON'T block other layers!
                // - Rejected: V3 doesn't want it, but Treasury/ShitCoin might
                // - ShadowOnly: Tracking only, other layers can trade
                // - Watch: Monitoring, other layers can trade
                else -> { 
                    // No FinalExecutionPermit action for soft decisions
                    // Treasury, ShitCoin, BlueChip can still evaluate and trade
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // V3.3 FIX: Run Treasury Mode IMMEDIATELY after V3 decision
            // BEFORE any return statements, so Treasury can evaluate ALL tokens!
            // 
            // Treasury Mode runs CONCURRENTLY with V3 - it's a "2nd shadow mode"
            // that scalps tokens V3 might reject for normal trading.
            // It has its own filters (tight stops, lower thresholds).
            // 
            // V4.0 FIX: But Treasury CANNOT override V3 rejections!
            // If V3 REJECTED/BLOCKED a token, Treasury must respect that decision.
            // ═══════════════════════════════════════════════════════════════════
            // V5.9.93: ONE-BOOK-PER-TOKEN lockout. When V3 is about to
            // EXECUTE the CORE book on this same tick, skip Treasury
            // entirely — the audit showed Treasury and V3 opening the
            // same mint on the same tick (double-buy, conflicting TP/SL
            // ladders). Treasury will re-evaluate on later ticks once
            // the CORE position is open/closed.
            val v3WillExecuteCore = v3Decision is com.lifecyclebot.v3.V3Decision.Execute
            if (v3WillExecuteCore) {
                ErrorLogger.info(
                    "BotService",
                    "💰 [TREASURY] ${ts.symbol} | SKIP | V3_EXECUTE_SAME_TICK — one book per token"
                )
            }

            if (!v3WillExecuteCore && !ts.position.isOpen && com.lifecyclebot.v3.scoring.CashGenerationAI.isEnabled()) {
                try {
                    // ═══════════════════════════════════════════════════════════════════
                    // V4.0 FIX: CHECK FINAL EXECUTION PERMIT
                    // If V3 rejected this token, Treasury CANNOT buy it
                    // ═══════════════════════════════════════════════════════════════════
                    val permitResult = FinalExecutionPermit.canExecute(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        requestingLayer = "TREASURY",
                        hasOpenPosition = ts.position.isOpen
                    )
                    
                    if (!permitResult.allowed) {
                        ErrorLogger.debug("BotService", "💰 [TREASURY] ${ts.symbol} | BLOCKED | ${permitResult.reason}")
                        // Skip treasury evaluation for this token
                    } else {
                        // Extract V3 scores from decision (any type)
                        val (v3Score, v3Confidence) = when (val result = v3Decision) {
                            is com.lifecyclebot.v3.V3Decision.Execute -> result.score to result.confidence.toInt()
                            is com.lifecyclebot.v3.V3Decision.Watch -> result.score to result.confidence
                            is com.lifecyclebot.v3.V3Decision.Rejected -> 20 to 30  // Low defaults for rejected
                            else -> 15 to 25  // Very low for blocked/fatal
                        }
                        
                        // Cache scores for later use
                        ts.lastV3Score = v3Score
                        ts.lastV3Confidence = v3Confidence
                        
                        // ═══════════════════════════════════════════════════════════════════
                        // V4.1 COLD-START FIX: Check for bootstrap forced entry
                        // This breaks the deadlock: no trades → no learning → no trades
                        // 
                        // NOTE: Use momentum/buy-pressure based score, NOT v3Score!
                        // v3Score is low (20-30) for rejected tokens, but Treasury trades
                        // tokens V3 doesn't like. Use a composite of raw signals instead.
                        // ═══════════════════════════════════════════════════════════════════
                        val tokenAge = if (ts.addedToWatchlistAt > 0) {
                            (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                        } else 30.0
                        
                        // Calculate raw signal score for bootstrap (not v3Score)
                        val rawSignalScore = calculateBootstrapScore(
                            buyPressurePct = ts.lastBuyPressurePct,
                            liquidityUsd = ts.lastLiquidityUsd,
                            momentum = ts.momentum ?: 0.0,
                            volatility = ts.volatility ?: 0.0,
                        )
                        
                        val forceBootstrapEntry = com.lifecyclebot.v3.scoring.FluidLearningAI.shouldForceBootstrapEntry(
                            score = rawSignalScore,
                            liquidityUsd = ts.lastLiquidityUsd,
                            tokenAgeMinutes = tokenAge,
                            buyPressurePct = ts.lastBuyPressurePct,
                            isPaper = cfg.paperMode
                        )
                        
                        val treasurySignal = com.lifecyclebot.v3.scoring.CashGenerationAI.evaluate(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            currentPrice = ts.ref,
                            liquidityUsd = ts.lastLiquidityUsd,
                            // V4.0 FIX: Default to 20% (safe) instead of 50% (fails threshold)
                            // Many tokens don't have topHolderPct data early on
                            topHolderPct = ts.topHolderPct ?: 20.0,
                            buyPressurePct = ts.lastBuyPressurePct,
                            v3Score = v3Score,
                            v3Confidence = v3Confidence,
                            momentum = ts.momentum ?: 0.0,
                            volatility = ts.volatility ?: 0.0
                        )
                        
                        // V4.1: Enter if Treasury says yes OR bootstrap override triggered
                        // V5.2.13: Block bootstrap override when V3 hard-rejects OR dump signals active
                        // V5.9: All terminal V3 decisions block Treasury — Rejected, BlockFatal, AND Blocked.
                        // Previously only Rejected was checked, so BLOCK_FATAL (e.g. EXTREME_RUG_RISK) slipped through.
                        val v3HardReject = v3Decision is com.lifecyclebot.v3.V3Decision.Rejected
                            || v3Decision is com.lifecyclebot.v3.V3Decision.BlockFatal
                            || v3Decision is com.lifecyclebot.v3.V3Decision.Blocked
                        val hasDumpSignal = try { AICrossTalk.isCoordinatedDump(ts.mint, ts.symbol) } catch (_: Exception) { false }
                        // V5.9.156 — CrossTalk dump veto is volume-killing on
                        // fresh pump.fun launches that ALWAYS fire LiquidityAI
                        // COLLAPSE in the first 4-5 minutes (user log 04-23:
                        // PORINGMAN collapse -7% over 4min on a token 3 min
                        // old). During bootstrap (<40% learning) we let those
                        // tokens through — the scorer already has the signal
                        // as a component, and the bot needs volume to learn
                        // what's a real dump vs launch noise. Above 40%
                        // learning the original hard veto re-engages.
                        val dumpBootstrapBypass = try {
                            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
                        } catch (_: Exception) { false }
                        val dumpBlocks = hasDumpSignal && !dumpBootstrapBypass
                        // V5.9: Terminal V3 rejects are globally binding — Treasury cannot override them.
                        // Previously v3HardReject only gated forceBootstrapEntry, not treasurySignal.shouldEnter.
                        // TOO_OLD / INELIGIBLE / ZERO_LIQUIDITY rejections from V3|ELIGIBILITY must block all layers.
                        val shouldEnter = !v3HardReject && !dumpBlocks && (treasurySignal.shouldEnter || forceBootstrapEntry)

                        // V5.9: Post-close cooldown — prevent immediate re-entry after a close
                        val closedAgoMs = System.currentTimeMillis() - (BotService.recentlyClosedMs[ts.mint] ?: 0L)
                        if (!FreeRangeMode.isWideOpen() && closedAgoMs < BotService.RE_ENTRY_COOLDOWN_MS) {
                            ErrorLogger.debug("BotService", "💰 [TREASURY] ${ts.symbol} | COOLDOWN | closed ${closedAgoMs/1000}s ago (min ${BotService.RE_ENTRY_COOLDOWN_MS/1000}s)")
                            return
                        }
                        
                        // V5.7.7: Bootstrap score gate - during first 50 trades, require score >= 75
                        if (!FreeRangeMode.isWideOpen() &&
                            com.lifecyclebot.v3.scoring.FluidLearningAI.shouldBlockBootstrapTrade(treasurySignal.confidence)) {
                            ErrorLogger.debug("BotService", "💰 [TREASURY] ${ts.symbol} | BOOTSTRAP BLOCKED | score=${treasurySignal.confidence} | ${com.lifecyclebot.v3.scoring.FluidLearningAI.getBootstrapStatus()}")
                            return
                        }

                        // V5.9.92: SmartChart veto for Treasury (same fix as
                        // Executor entry in V5.9.91). Treasury had its own
                        // scoring path that bypassed chart-bias checks — if
                        // the scanner reads >=80% bearish in the last 2 min
                        // we skip regardless of Treasury score.
                        // V5.9.156 — but during bootstrap (<40% learning)
                        // let these through: SmartChart's "bearish 80%" on a
                        // 2-min chart is noise on tokens that haven't had time
                        // to print a real pattern. Blocking them here denied
                        // the scorer the volume it needs to learn. Above 40%
                        // learning the original veto re-engages.
                        // V5.9.495z3 — operator: paper showing SMARTCHART_BLOCK
                        // bearish=100% on every Treasury candidate, killing
                        // sweep volume. Paper mode is for LEARNING; force the
                        // bypass on regardless of learning progress so paper
                        // gets the volume to evaluate "is the bearish veto
                        // actually correct?". Live mode keeps the veto at >=40%
                        // learning as before — real money still respects it.
                        val smartChartBypass = try {
                            cfg.paperMode ||
                            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
                        } catch (_: Exception) { cfg.paperMode }
                        val tsBearish = try {
                            com.lifecyclebot.engine.SmartChartCache.getBearishConfidence(ts.mint)
                        } catch (_: Exception) { null }
                        if (!smartChartBypass && tsBearish != null && tsBearish >= 80.0) {
                            ErrorLogger.info(
                                "BotService",
                                "💰 [TREASURY] ${ts.symbol} | SMARTCHART_BLOCK | bearish=${tsBearish.toInt()}% — skip"
                            )
                            return
                        }


                        if (shouldEnter) {
                            // V4.1: Apply bootstrap size multiplier for micro-positions
                            val bootstrapMultiplier = com.lifecyclebot.v3.scoring.FluidLearningAI.getBootstrapSizeMultiplier()
                            val adjustedSize = (treasurySignal.positionSizeSol * bootstrapMultiplier).coerceAtLeast(0.01)
                            
                            // V5.2.8 FIX: If bootstrap override forced entry, use default TP/SL values
                            // When Treasury rejects, it returns 0% TP which causes immediate exits!
                            val effectiveTpPct = if (treasurySignal.takeProfitPct <= 0.0) 4.0 else treasurySignal.takeProfitPct
                            val effectiveSlPct = if (treasurySignal.stopLossPct >= 0.0) -4.0 else treasurySignal.stopLossPct
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // V5.0: TRADE AUTHORIZER - MUST pass before ANY execution
                            // This is the SINGLE source of truth for execution permission
                            // Prevents post-execution gating drift (inokumi bug)
                            // 
                            // V5.2 FIX: Use Treasury's own score/confidence, NOT V3's!
                            // V3 rejects treasury candidates with low scores (20-30)
                            // Treasury has its own scoring criteria - use those instead
                            // ═══════════════════════════════════════════════════════════════════
                            val treasuryScore = rawSignalScore.coerceAtLeast(treasurySignal.confidence)  // Use better of two
                            val authResult = TradeAuthorizer.authorize(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                score = treasuryScore,  // V5.2: Use Treasury's score
                                confidence = treasurySignal.confidence.toDouble(),  // V5.2: Use Treasury's confidence
                                quality = if (treasurySignal.confidence >= 70) "B" else "C",  // V5.2: Derive quality from confidence
                                isPaperMode = cfg.paperMode,
                                requestedBook = TradeAuthorizer.ExecutionBook.TREASURY,
                                rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                                liquidity = ts.lastLiquidityUsd,
                                isBanned = BannedTokens.isBanned(ts.mint),
                            )
                            
                            if (!authResult.isExecutable()) {
                                // NOT AUTHORIZED - log and skip
                                if (authResult.isShadowOnly()) {
                                    ErrorLogger.info("BotService", "💰 [TREASURY] ${ts.symbol} | SHADOW_ONLY | ${authResult.reason}")
                                    // Track for learning but do NOT create position
                                } else {
                                    ErrorLogger.debug("BotService", "💰 [TREASURY] ${ts.symbol} | REJECTED | ${authResult.reason}")
                                    RejectionTelemetry.record("TREASURY", authResult.reason)
                                }
                                // Skip execution - do NOT proceed to buy
                            } else {
                                // AUTHORIZED - proceed with execution
                            
                                // Try to acquire execution permit
                                val canExecute = FinalExecutionPermit.tryAcquireExecution(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    layer = "TREASURY",
                                    sizeSol = adjustedSize
                                )
                                
                                if (canExecute) {
                                val bootstrapTag = if (forceBootstrapEntry) " [BOOTSTRAP OVERRIDE]" else ""
                                
                                // V5.2 FIX: Capture Treasury's OWN entry price BEFORE paperBuy applies slippage!
                                // Treasury is a separate trading layer - it tracks its own entry independent of the position
                                val treasuryEntryPrice = ts.lastPrice.takeIf { it > 0 } 
                                    ?: ts.history.lastOrNull()?.priceUsd 
                                    ?: ts.ref
                                
                                ErrorLogger.info("BotService", "💰 [TREASURY] ${ts.symbol} | ENTER$bootstrapTag | " +
                                    "size=${adjustedSize.fmt(3)} SOL (${(bootstrapMultiplier*100).toInt()}%) | " +
                                    "TP=${treasurySignal.takeProfitPct}% | " +
                                    "treasuryEntry=$treasuryEntryPrice | " +
                                    "mode=${treasurySignal.mode}")
                                
                                // V5.7.8: In LIVE mode, verify actual wallet can fund the trade
                                val canFundLive = if (!cfg.paperMode) {
                                    val realWalletBal = status.walletSol
                                    if (realWalletBal < adjustedSize) {
                                        ErrorLogger.warn("BotService", "💰 [TREASURY] ${ts.symbol} | LIVE_SKIP | " +
                                            "wallet=${realWalletBal.fmt(3)}◎ < size=${adjustedSize.fmt(3)}◎")
                                        addLog("💰 TREASURY SKIP: ${ts.symbol} | Insufficient wallet (${realWalletBal.fmt(3)}◎ < ${adjustedSize.fmt(3)}◎)")
                                        FinalExecutionPermit.releaseExecution(ts.mint)
                                        false
                                    } else true
                                } else true
                                
                                if (!canFundLive) {
                                    // Skip treasury entry — wallet can't fund
                                } else {
                                
                                // Execute treasury buy (this calls paperBuy which applies slippage to ts.position)
                                // V5.2.8 FIX: Use effectiveTpPct/effectiveSlPct to prevent 0% TP instant-exits!
                                executor.treasuryBuy(
                                    ts = ts,
                                    sizeSol = adjustedSize,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = effectiveTpPct,   // V5.2.8: Use effective (non-zero) TP
                                    stopLossPct = effectiveSlPct,     // V5.2.8: Use effective SL
                                    wallet = wallet,
                                    isPaper = cfg.paperMode
                                )
                                
                                // V5.0 FIX: Mark position as treasury so checkExit uses correct thresholds
                                ts.position.isTreasuryPosition = true
                                ts.position.tradingMode = "TREASURY"
                                ts.position.tradingModeEmoji = "💰"
                                // V5.9.200: Persist TP/SL + raw entry price for recovery after restart
                                ts.position.treasuryTakeProfit = effectiveTpPct
                                ts.position.treasuryStopLoss = effectiveSlPct
                                ts.position.treasuryEntryPrice = treasuryEntryPrice ?: ts.position.entryPrice
                                
                                // V5.2 FIX: Record treasury position with Treasury's OWN entry price!
                                // Do NOT use ts.position.entryPrice - that has paperBuy slippage applied
                                // Treasury layer tracks separately from the core position layer
                                com.lifecyclebot.v3.scoring.CashGenerationAI.openPosition(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    entryPrice = treasuryEntryPrice,  // Treasury's own raw price
                                    positionSol = adjustedSize,
                                    takeProfitPct = effectiveTpPct,   // V5.2.8: Use effective (non-zero) TP
                                    stopLossPct = effectiveSlPct,     // V5.2.8: Use effective SL
                                    entryScore = treasurySignal.entryScore,  // V5.9.436
                                )
                                
                                // V5.6.8 FIX: Notify V3 exposure guards of new position
                                com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)
                                
                                // Release permit after successful execution
                                FinalExecutionPermit.releaseExecution(ts.mint)
                                
                                // V4.1: Record trade for learning
                                com.lifecyclebot.v3.scoring.FluidLearningAI.recordTradeStart()
                                
                                val bootstrapLabel = if (forceBootstrapEntry) " [BOOTSTRAP]" else ""
                                addLog("💰 TREASURY BUY$bootstrapLabel: ${ts.symbol} | ${adjustedSize.fmt(3)} SOL | " +
                                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                                } // end canFundLive else block
                            } else {
                                ErrorLogger.debug("BotService", "💰 [TREASURY] ${ts.symbol} | EXECUTION_BLOCKED | another layer executing")
                                // Release authorizer lock since we didn't execute
                                TradeAuthorizer.releasePosition(ts.mint, "PERMIT_BLOCKED")
                            }
                            } // end authResult.isExecutable()
                        }
                    }
                } catch (treasuryEx: Exception) {
                    ErrorLogger.debug("BotService", "💰 [TREASURY] ${ts.symbol} | ERROR | ${treasuryEx.message}")
                    FinalExecutionPermit.releaseExecution(ts.mint)  // Release on error
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // BLUE CHIP TRADER - Quality plays for >$1M market cap tokens
            // Runs CONCURRENTLY with V3 and Treasury
            // V4.0 FIX: Must check FinalExecutionPermit before executing
            // V5.2 FIX: Must check if Treasury already has a position!
            //          Treasury must hit TP and sell BEFORE other layers can enter
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && ts.lastMcap >= 75_000) {  // V5.9.191: was 100K, align with QualityTraderAI $75K min1M layer)
                // V5.7.8: Modes run independently — Treasury positions don't block them
                try {
                    // ═══════════════════════════════════════════════════════════════
                    // V5.2.11: QUALITY TRADER AI - Professional Solana Trading
                    // For $100K-$1M mcap tokens that aren't pure meme coins
                    // ═══════════════════════════════════════════════════════════════
                    val qualityPermit = FinalExecutionPermit.canExecute(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        requestingLayer = "QUALITY",
                        hasOpenPosition = ts.position.isOpen
                    )
                    
                    // V5.2.12: Log when Quality is checked but mcap out of range
                    if (qualityPermit.allowed && ts.lastMcap !in 75_000.0..1_000_000.0) {
                        ErrorLogger.debug("BotService", "⭐ [QUALITY SKIP] ${ts.symbol} | mcap=\$${(ts.lastMcap/1000).toInt()}K not in \$100K-\$1M range")
                    }
                    
                    if (qualityPermit.allowed && ts.lastMcap in 75_000.0..1_000_000.0) {  // V5.9.191: was 100K, align with QualityTraderAI
                        val (v3Score, v3Confidence) = when (val result = v3Decision) {
                            is com.lifecyclebot.v3.V3Decision.Execute -> result.score to result.confidence.toInt()
                            is com.lifecyclebot.v3.V3Decision.Watch -> result.score to result.confidence
                            else -> 15 to 25
                        }
                        
                        // Calculate token age from history (first candle timestamp)
                        // V5.9.189: Use token's on-chain age (addedToWatchlistAt proxy)
                        // ts.history.first().ts = when BOT first saw it (wrong on fresh install)
                        // addedToWatchlistAt is set when token enters scanner — still bot-relative,
                        // but combined with first-candle ts gives a better floor.
                        // Quality accepts tokens 10-30 min old so we use max of both signals.
                        val qualityTokenAgeMinutes = if (ts.history.size >= 2) {
                            // Use span between first and latest candle as minimum age signal
                            val historySpanMin = (ts.history.last().ts - ts.history.first().ts) / 60_000.0
                            // Also use time since added to watchlist
                            val watchlistAgeMin = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                            // Take the larger of the two — both are lower bounds on real age
                            maxOf(historySpanMin, watchlistAgeMin, 0.0)
                        } else if (ts.history.isNotEmpty()) {
                            (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                        } else 0.0
                        
                        // Get holder count from last candle
                        val qualityHolderCount = ts.history.lastOrNull()?.holderCount ?: 0
                        
                        val qualitySignal = com.lifecyclebot.v3.scoring.QualityTraderAI.evaluate(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            currentPrice = ts.ref,
                            marketCapUsd = ts.lastMcap,
                            liquidityUsd = ts.lastLiquidityUsd,
                            buyPressure = ts.lastBuyPressurePct.toInt(),
                            tokenAgeMinutes = qualityTokenAgeMinutes,
                            holderCount = qualityHolderCount,
                            topHolderPct = ts.topHolderPct ?: 20.0,
                            v3Score = v3Score,
                            isMeme = false,
                        )
                        
                        // V5.2.12: Log Quality evaluations for debugging
                        if (qualitySignal.shouldEnter) {
                            ErrorLogger.info("BotService", "⭐ [QUALITY EVAL] ${ts.symbol} | SHOULD_ENTER | score=${qualitySignal.qualityScore}")
                        } else {
                            // V5.9.116: Promote from debug → throttled info so user
                            // actually sees WHY Quality never fires.
                            logLayerSkip("⭐ QUALITY", ts.symbol, ts.mint,
                                "${qualitySignal.reason} | mcap=\$${ts.lastMcap.toInt()} liq=\$${ts.lastLiquidityUsd.toInt()} age=${qualityTokenAgeMinutes.toInt()}min")
                        }
                        
                        if (qualitySignal.shouldEnter) {
                            val canExecute = FinalExecutionPermit.tryAcquireExecution(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                layer = "QUALITY",
                                sizeSol = qualitySignal.positionSizeSol
                            )
                            
                            if (canExecute) {
                                ErrorLogger.info("BotService", "⭐ [QUALITY] ${ts.symbol} | ENTER | " +
                                    "mcap=\$${(ts.lastMcap/1000).toInt()}K | " +
                                    "score=${qualitySignal.qualityScore} | " +
                                    "size=${qualitySignal.positionSizeSol.fmt(3)} SOL")
                                
                                // V5.9.189: Use QualityTraderAI's own fluid TP (15-50%)
                                // NOT 4-8% overrides — those make losses > wins structurally
                                // Risk:reward must be at least 2:1 (TP >= 2x SL)
                                val qualityTp = qualitySignal.takeProfitPct.coerceAtLeast(
                                    qualitySignal.stopLossPct * 2.0  // Always >= 2x the stop
                                )

                                // Execute Quality buy (reuse BlueChip executor pattern)
                                executor.blueChipBuy(
                                    ts = ts,
                                    sizeSol = qualitySignal.positionSizeSol,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = qualityTp,
                                    stopLossPct = qualitySignal.stopLossPct,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode,
                                    // V5.9.386 — tag the BUY trade as QUALITY in the
                                    // journal (was showing as BLUE_CHIP / ExtendedMode).
                                    layerTag = "QUALITY",
                                    layerTagEmoji = "⭐",
                                )

                                // V5.8: Override tradingMode — blueChipBuy() sets "BLUE_CHIP" by default,
                                // breaking Quality exit routing (SL/TP checks gate on tradingMode == "QUALITY")
                                ts.position.tradingMode = "QUALITY"
                                ts.position.tradingModeEmoji = "⭐"

                                // Record Quality position
                                com.lifecyclebot.v3.scoring.QualityTraderAI.addPosition(
                                    com.lifecyclebot.v3.scoring.QualityTraderAI.QualityPosition(
                                        mint = ts.mint,
                                        symbol = ts.symbol,
                                        entryPrice = ts.ref,
                                        entrySol = qualitySignal.positionSizeSol,
                                        entryTime = System.currentTimeMillis(),
                                        entryMcap = ts.lastMcap,
                                        takeProfitPct = qualityTp,
                                        stopLossPct = qualitySignal.stopLossPct,
                                        entryScore = qualitySignal.qualityScore,  // V5.9.436
                                    )
                                )
                                
                                // V5.6.8 FIX: Notify V3 exposure guards
                                com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)
                                
                                // Register with Layer Transition Manager
                                com.lifecyclebot.v3.scoring.LayerTransitionManager.registerPosition(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    layer = com.lifecyclebot.v3.scoring.LayerTransitionManager.TradingLayer.QUALITY,
                                    entryMcap = ts.lastMcap,
                                    entryPrice = ts.ref,
                                )
                                
                                FinalExecutionPermit.releaseExecution(ts.mint)
                                addLog("⭐ QUALITY: ${ts.symbol} | \$${(ts.lastMcap/1000).toInt()}K mcap", ts.mint)
                            }
                        }
                    }
                    
                    // ═══════════════════════════════════════════════════════════════
                    // BLUE CHIP TRADER AI - For $1M+ mcap tokens
                    // ═══════════════════════════════════════════════════════════════
                    
                    // V4.0 FIX: Check execution permit first
                    val permitResult = FinalExecutionPermit.canExecute(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        requestingLayer = "BLUE_CHIP",
                        hasOpenPosition = ts.position.isOpen
                    )
                    
                    if (!permitResult.allowed) {
                        ErrorLogger.debug("BotService", "🔵 [BLUE CHIP] ${ts.symbol} | BLOCKED | ${permitResult.reason}")
                    } else {
                        val (v3Score, v3Confidence) = when (val result = v3Decision) {
                            is com.lifecyclebot.v3.V3Decision.Execute -> result.score to result.confidence.toInt()
                            is com.lifecyclebot.v3.V3Decision.Watch -> result.score to result.confidence
                            is com.lifecyclebot.v3.V3Decision.Rejected -> 20 to 30
                            else -> 15 to 25
                        }
                        
                        val blueChipSignal = com.lifecyclebot.v3.scoring.BlueChipTraderAI.evaluate(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            currentPrice = ts.ref,
                            marketCapUsd = ts.lastMcap,
                            liquidityUsd = ts.lastLiquidityUsd,
                            topHolderPct = ts.topHolderPct ?: 20.0,
                            buyPressurePct = ts.lastBuyPressurePct,
                            v3Score = v3Score,
                            v3Confidence = v3Confidence,
                            momentum = ts.momentum ?: 0.0,
                            volatility = ts.volatility ?: 0.0
                        )
                        
                        // V5.2.12: Log BlueChip evaluations for debugging
                        if (blueChipSignal.shouldEnter) {
                            ErrorLogger.info("BotService", "🔵 [BLUECHIP EVAL] ${ts.symbol} | SHOULD_ENTER | confidence=${blueChipSignal.confidence}")
                        } else {
                            ErrorLogger.debug("BotService", "🔵 [BLUECHIP EVAL] ${ts.symbol} | SKIP | ${blueChipSignal.reason} | mcap=$${(ts.lastMcap/1_000_000).toInt()}M liq=$${ts.lastLiquidityUsd.toInt()}")
                        }
                        
                        if (blueChipSignal.shouldEnter) {
                            // V4.0: Try to acquire execution permit
                            val canExecute = FinalExecutionPermit.tryAcquireExecution(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                layer = "BLUE_CHIP",
                                sizeSol = blueChipSignal.positionSizeSol
                            )
                            
                            if (canExecute) {
                                // V5.8: Confidence-tiered TP: weak=4%, standard=6%, strong=9%
                                val blueChipTp = when {
                                    v3Confidence >= 55 -> 9.0
                                    v3Confidence >= 40 -> 6.0
                                    else -> 4.0
                                }

                                ErrorLogger.info("BotService", "🔵 [BLUE CHIP] ${ts.symbol} | ENTER | " +
                                    "mcap=\$${(ts.lastMcap/1_000_000).fmt(2)}M | " +
                                    "size=${blueChipSignal.positionSizeSol.fmt(3)} SOL | " +
                                    "TP=$blueChipTp% (conf=$v3Confidence)")

                                // Execute Blue Chip buy
                                executor.blueChipBuy(
                                    ts = ts,
                                    sizeSol = blueChipSignal.positionSizeSol,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = blueChipTp,
                                    stopLossPct = blueChipSignal.stopLossPct,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode
                                )

                                // V5.9.270 FIX: Use qtyToken > 0 || pendingVerify (not just isOpen).
                                // For live buys, liveBuy() leaves pendingVerify=true making isOpen=false.
                                if (ts.position.qtyToken > 0.0 || ts.position.pendingVerify) com.lifecyclebot.v3.scoring.BlueChipTraderAI.addPosition(
                                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipPosition(
                                        mint = ts.mint,
                                        symbol = ts.symbol,
                                        entryPrice = ts.ref.takeIf { it > 0 } ?: ts.lastPrice.takeIf { it > 0 } ?: ts.position.entryPrice,
                                        entrySol = blueChipSignal.positionSizeSol,
                                        entryTime = System.currentTimeMillis(),
                                        marketCapUsd = ts.lastMcap,
                                        liquidityUsd = ts.lastLiquidityUsd,
                                        isPaper = cfg.paperMode,
                                        takeProfitPct = blueChipTp,
                                        stopLossPct = blueChipSignal.stopLossPct,
                                        entryScore = blueChipSignal.entryScore,  // V5.9.436
                                    )
                                )
                                
                                // V5.6.8 FIX: Notify V3 exposure guards
                                com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)
                                
                                // Register with Layer Transition Manager
                                com.lifecyclebot.v3.scoring.LayerTransitionManager.registerPosition(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    layer = com.lifecyclebot.v3.scoring.LayerTransitionManager.TradingLayer.BLUE_CHIP,
                                    entryMcap = ts.lastMcap,
                                    entryPrice = ts.ref,
                                )
                                
                                // Release permit
                                FinalExecutionPermit.releaseExecution(ts.mint)
                                
                                addLog("🔵 BLUE CHIP BUY: ${ts.symbol} | \$${(ts.lastMcap/1_000_000).fmt(1)}M mcap | " +
                                    "${blueChipSignal.positionSizeSol.fmt(3)} SOL | " +
                                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                            } else {
                                ErrorLogger.debug("BotService", "🔵 [BLUE CHIP] ${ts.symbol} | EXECUTION_BLOCKED | another layer executing")
                            }
                        }
                    }
                } catch (bcEx: Exception) {
                    ErrorLogger.debug("BotService", "🔵 [BLUE CHIP] ${ts.symbol} | ERROR | ${bcEx.message}")
                    FinalExecutionPermit.releaseExecution(ts.mint)
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END Blue Chip evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // 🚀 MOONSHOT TRADER - 10x/100x/1000x Hunter ($100K-$50M mcap)
            // V5.2: Space-themed trading modes - ORBITAL → LUNAR → MARS → JUPITER
            // Runs BEFORE ShitCoin (different mcap range, no overlap)
            // Cross-trade promotions: 200%+ gains from other layers graduate here
            // ═══════════════════════════════════════════════════════════════════
            // V5.9.416 — Moonshot un-muted. V5.9.409's v3OwnsMemes gate
            // suppressed the parallel Moonshot evaluator whenever V3 was
            // ready, but V3 rarely returns EXECUTE on a launch-pad rocket
            // (it weighs source/holders/structure heavily, and most fresh
            // moons have those numbers very thin). Result: Moonshot went
            // silent for hours. MoonshotTraderAI has unique launch-pad
            // signal logic V3 does NOT replicate (mcap-velocity, liquidity-
            // ramp, top-holder distribution gradient). Let it fire its own
            // evaluator independently. FinalExecutionPermit + the per-mint
            // GlobalTradeRegistry guard prevent V3+Moonshot double entries.
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.MoonshotTraderAI.isEnabled()) {
                // V5.7.8: Moonshot runs independently — Treasury positions don't block it
                try {
                    // V5.2.12: Check if mcap is in moonshot zone ($10K-$100M)
                    // V5.9.160: fresh pump.fun / DexScreener trending tokens often
                    // arrive with lastMcap == 0 before the first mcap fetch lands;
                    // treating "no data" as "out of range" was silently killing
                    // the entire Moonshot evaluation path for most fresh memes.
                    // Fall back to a liquidity proxy (>= $3K) when mcap is unknown.
                    val mcapInZone = ts.lastMcap in 10_000.0..100_000_000.0
                    val mcapUnknownButLiq = ts.lastMcap <= 0.0 && ts.lastLiquidityUsd >= 3_000.0
                    if (mcapInZone || mcapUnknownButLiq) {
                        
                        // V5.2: Check execution permit for MOONSHOT book
                        val moonshotPermit = FinalExecutionPermit.canExecute(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            requestingLayer = "MOONSHOT",
                            hasOpenPosition = ts.position.isOpen
                        )
                        
                        if (!moonshotPermit.allowed) {
                            ErrorLogger.debug("BotService", "🚀 [MOONSHOT] ${ts.symbol} | BLOCKED | ${moonshotPermit.reason}")
                        } else {
                            // Calculate volume score from recent data (use lastV3Score as proxy)
                            val volScore = ts.lastV3Score ?: 20
                            
                            // Check for collective intelligence boost
                            val isCollectiveWinner = com.lifecyclebot.v3.scoring.MoonshotTraderAI.isCollectiveWinner(ts.mint)
                            
                            var moonshotScore = com.lifecyclebot.v3.scoring.MoonshotTraderAI.scoreToken(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                marketCapUsd = ts.lastMcap,
                                liquidityUsd = ts.lastLiquidityUsd,
                                volumeScore = volScore,
                                buyPressurePct = ts.lastBuyPressurePct,
                                rugcheckScore = ts.safety.rugcheckScore,
                                v3EntryScore = (ts.lastV3Score ?: 50).toDouble(),
                                v3Confidence = (ts.lastV3Confidence ?: 50).toDouble(),
                                phase = ts.phase,
                                isPaper = cfg.paperMode,
                            )

                            // V5.9.618 — BRIDGE ADVISORY BOOST for Moonshot (additive).
                            // When the proven 79% WR architecture (MemeUnifiedScorerBridge)
                            // agrees, bump confidence by +0.05 and score by +3. Never
                            // creates eligibility the evaluator rejected.
                            if (ts.bridgeAdvisoryAgrees && moonshotScore.eligible) {
                                moonshotScore = moonshotScore.copy(
                                    score = (moonshotScore.score + 3).coerceAtMost(150),
                                    confidence = (moonshotScore.confidence + 0.05).coerceAtMost(1.0)
                                )
                            }

                            // V5.9.619 — MEME EDGE AI for Moonshot. Same bounded
                            // pattern-readback / WR sizing / streak / cluster guard
                            // pipeline as ShitCoin. Cold-start no-op; ramps to 20%.
                            val moonshotNarrative = try {
                                com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(ts.symbol).cluster
                            } catch (_: Exception) {
                                com.lifecyclebot.v3.scoring.MemeNarrativeAI.Cluster.UNKNOWN
                            }
                            val moonshotOpenInCluster = try {
                                com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().count { pos ->
                                    runCatching {
                                        com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(pos.symbol).cluster == moonshotNarrative
                                    }.getOrDefault(false)
                                }
                            } catch (_: Exception) { 0 }
                            val moonshotEdge = com.lifecyclebot.v3.scoring.MemeEdgeAI.evaluate(
                                layer = com.lifecyclebot.v3.scoring.MemeEdgeAI.Layer.MOONSHOT,
                                mcapUsd = ts.lastMcap,
                                tokenAgeMinutes = tokenAgeMinutes,
                                buyRatioPct = ts.lastBuyPressurePct,
                                volumeUsd = ts.lastLiquidityUsd * 0.05,
                                liquidityUsd = ts.lastLiquidityUsd,
                                holderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                                topHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0 } ?: 20.0,
                                holderGrowthRate = ts.holderGrowthRate,
                                rugcheckScore = ts.safety.rugcheckScore.toDouble(),
                                baseEntryScore = moonshotScore.score.toDouble(),
                                narrativeCluster = moonshotNarrative,
                                openClusterCount = moonshotOpenInCluster,
                            )
                            if (moonshotScore.eligible &&
                                (moonshotEdge.scoreNudge != 0.0 ||
                                 moonshotEdge.confidenceNudge != 0.0 ||
                                 moonshotEdge.sizeMultiplier != 1.0)) {
                                val edgeSized = (moonshotScore.suggestedSizeSol * moonshotEdge.sizeMultiplier)
                                    .coerceAtLeast(0.01)
                                moonshotScore = moonshotScore.copy(
                                    score = (moonshotScore.score + moonshotEdge.scoreNudge.toInt()).coerceIn(0, 150),
                                    confidence = (moonshotScore.confidence + moonshotEdge.confidenceNudge / 100.0).coerceIn(0.0, 1.0),
                                    suggestedSizeSol = edgeSized
                                )
                            }

                            // V5.9.449 — REMOVED V5.9.443 CHOP_REJECT for Moonshot.
                            // The filter blocked exactly the fresh-launch DEX_BOOSTED/
                            // DEX_TRENDING entries in early_unknown/pre_pump phases that
                            // produced build-1941's 500% Moonshot runners. Soft scoring
                            // (V3 + UnifiedScorer + MetaCognition) already weighs these
                            // signals — adding a hard score<60 floor was choke pressure.
                            
                            if (!moonshotScore.eligible) {
                                // V5.9.244: Log moonshot rejections at INFO so we can diagnose silence
                                ErrorLogger.info("BotService", "🚀 [MOONSHOT] ${ts.symbol} | REJECTED | ${moonshotScore.rejectReason} | mcap=${(ts.lastMcap/1000).toInt()}K liq=${ts.lastLiquidityUsd.toInt()} bp=${ts.lastBuyPressurePct.toInt()}% v3=${ts.lastV3Score ?: "null"}")
                                RejectionTelemetry.record("MOONSHOT", moonshotScore.rejectReason)
                            }
                            if (moonshotScore.eligible) {
                                // V5.2.8 FIX: Ensure Moonshot never uses 0% TP/SL
                                val moonshotEffectiveTpPct = if (moonshotScore.takeProfitPct <= 0.0) 50.0 else moonshotScore.takeProfitPct
                                // V5.9.235: fallback floor raised to -15% (matches HARD_FLOOR_STOP); clamp also applied
                                val moonshotEffectiveSlPct = (if (moonshotScore.stopLossPct >= 0.0) -15.0 else moonshotScore.stopLossPct).coerceAtLeast(-15.0)
                                
                                // V5.2: Authorize through TradeAuthorizer
                                val authResult = TradeAuthorizer.authorize(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    score = moonshotScore.score,
                                    confidence = moonshotScore.confidence,
                                    quality = moonshotScore.spaceMode.displayName,
                                    isPaperMode = cfg.paperMode,
                                    requestedBook = TradeAuthorizer.ExecutionBook.MOONSHOT,
                                    rugcheckScore = ts.safety.rugcheckScore,
                                    liquidity = ts.lastLiquidityUsd,
                                )
                                
                                if (!authResult.isExecutable()) {
                                    ErrorLogger.debug("BotService", "🚀 [MOONSHOT] ${ts.symbol} | AUTH_DENIED | ${authResult.reason}")
                                } else {
                                    // Acquire final execution permit
                                    if (FinalExecutionPermit.tryAcquireExecution(
                                        mint = ts.mint,
                                        symbol = ts.symbol,
                                        layer = "MOONSHOT",
                                        sizeSol = moonshotScore.suggestedSizeSol,
                                    )) {
                                        try {
                                            val collectiveLabel = if (moonshotScore.isCollectiveBoost) " [COLLECTIVE]" else ""
                                            
                                            ErrorLogger.info("BotService", "🚀 [MOONSHOT] ${ts.symbol} | ENTRY$collectiveLabel | " +
                                                "${moonshotScore.spaceMode.emoji} ${moonshotScore.spaceMode.displayName} | " +
                                                "score=${moonshotScore.score} conf=${moonshotScore.confidence.toInt()}% | " +
                                                "mcap=\$${(ts.lastMcap/1000).toInt()}K | " +
                                                "TP=${moonshotEffectiveTpPct.toInt()}% SL=${moonshotEffectiveSlPct.toInt()}%")
                                            
                                            // Execute moonshot entry — live mode runs real on-chain swap
                                            executor.moonshotBuy(
                                                ts = ts,
                                                sizeSol = moonshotScore.suggestedSizeSol,
                                                walletSol = effectiveBalance,
                                                wallet = wallet,
                                                isPaper = cfg.paperMode,
                                                score = moonshotScore.score.toDouble(),
                                                spaceModeEmoji = moonshotScore.spaceMode.emoji,
                                                spaceModeName = moonshotScore.spaceMode.displayName,
                                            )

                                            // V5.9.270 FIX: Use qtyToken > 0 || pendingVerify (not just isOpen).
                                            // For live buys, liveBuy() leaves pendingVerify=true making isOpen=false.
                                            if (ts.position.qtyToken > 0.0 || ts.position.pendingVerify) com.lifecyclebot.v3.scoring.MoonshotTraderAI.addPosition(
                                                com.lifecyclebot.v3.scoring.MoonshotTraderAI.MoonshotPosition(
                                                    mint = ts.mint,
                                                    symbol = ts.symbol,
                                                    entryPrice = ts.ref,
                                                    entrySol = moonshotScore.suggestedSizeSol,
                                                    entryTime = System.currentTimeMillis(),
                                                    takeProfitPct = moonshotEffectiveTpPct,  // V5.2.8: Use effective TP
                                                    stopLossPct = moonshotEffectiveSlPct,    // V5.2.8: Use effective SL
                                                    marketCapUsd = ts.lastMcap,
                                                    liquidityUsd = ts.lastLiquidityUsd,
                                                    entryScore = moonshotScore.score.toDouble(),
                                                    spaceMode = moonshotScore.spaceMode,
                                                    isPaperMode = cfg.paperMode,
                                                    isCollectiveWinner = moonshotScore.isCollectiveBoost,
                                                    // V5.9.618 — capture real entry context for AdaptiveLearningEngine
                                                    entryBuyPressurePct = ts.lastBuyPressurePct,
                                                    entryAgeMinutes = ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0),
                                                    entryHolderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                                                    entryTopHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0 } ?: 0.0,
                                                    entryRugcheckScore = ts.safety.rugcheckScore.toDouble(),
                                                    entryHolderGrowthRate = ts.holderGrowthRate,
                                                    entryVolumeUsd = ts.lastLiquidityUsd * 0.05,
                                                )
                                            )
                                            
                                            // V5.6.8 FIX: Notify V3 exposure guards
                                            com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)
                                            
                                            // Register with LayerTransitionManager for tracking
                                            com.lifecyclebot.v3.scoring.LayerTransitionManager.registerPosition(
                                                mint = ts.mint,
                                                symbol = ts.symbol,
                                                layer = com.lifecyclebot.v3.scoring.LayerTransitionManager.TradingLayer.MOONSHOT,
                                                entryMcap = ts.lastMcap,
                                                entryPrice = ts.ref,
                                            )
                                            
                                            ts.position.tradingMode = "MOONSHOT_${moonshotScore.spaceMode.name}"
                                            ts.position.tradingModeEmoji = moonshotScore.spaceMode.emoji
                                            
                                            addLog("${moonshotScore.spaceMode.emoji} MOONSHOT BUY$collectiveLabel: ${ts.symbol} | " +
                                                "${moonshotScore.spaceMode.displayName} | " +
                                                "\$${(ts.lastMcap/1_000).toInt()}K mcap | " +
                                                "score=${moonshotScore.score} | " +
                                                "${moonshotScore.suggestedSizeSol.fmt(3)} SOL | " +
                                                "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                                                
                                        } finally {
                                            FinalExecutionPermit.releaseExecution(ts.mint)
                                        }
                                    } else {
                                        ErrorLogger.debug("BotService", "🚀 [MOONSHOT] ${ts.symbol} | EXECUTION_BLOCKED | another layer executing")
                                    }
                                }
                            }
                        }
                    }
                } catch (moonEx: Exception) {
                    ErrorLogger.debug("BotService", "🚀 [MOONSHOT] ${ts.symbol} | ERROR | ${moonEx.message}")
                    FinalExecutionPermit.releaseExecution(ts.mint)
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END Moonshot evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // SHITCOIN TRADER - Degen plays for <$30K market cap tokens
            // V5.2 FIX: Updated mcap range to avoid overlap with Moonshot
            // Runs CONCURRENTLY with V3, Treasury, and Blue Chip
            // Targets pump.fun, raydium, moonshot fresh launches
            // V4.0 FIX: Must check FinalExecutionPermit before executing
            // V5.2 FIX: Must check if Treasury already has a position!
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.ShitCoinTraderAI.isEnabled()) {
                // V5.9.409 — V3 is now the meme authority. If V3 is enabled,
                // the parallel ShitCoin execution path is suppressed — V3 +
                // FDG decide the entry and Executor.v3Buy performs it, with
                // the ShitCoin sub-trader's signal already blended into V3
                // scoring upstream. The ShitCoin block remains active only
                // as a FALLBACK when V3 is disabled / not ready, preserving
                // backward compatibility.
                // V5.9.650 — operator override: in PAPER mode, run ShitCoin in
                // PARALLEL with V3 instead of muting it. V5.9.409 made V3 the
                // sole meme authority, but operator's V5.9.649 device showed V3
                // returning WATCH/REJECTED for ALL 300+ watchlist candidates
                // (low historical paper WR 7%, FDG floor=15%). With ShitCoin
                // muted, the meme trader took ZERO trades while CryptoAlt's
                // TRUMP fired immediately — proving the executor works but the
                // V3-only meme gate is too tight. In paper mode, parallel
                // ShitCoin gives the bot LEARNING exposure on the meme stream
                // and lets us see actual rejection reasons in the log.
                // LIVE mode is unchanged — V3 still owns memes there.
                val v3OwnsMemes = try {
                    !cfg.paperMode && cfg.v3EngineEnabled && com.lifecyclebot.v3.V3EngineManager.isReady()
                } catch (_: Throwable) { false }
                if (v3OwnsMemes) {
                    // Tag the token so FDG + cross-talk see the meme lane
                    // even when V3 handles execution.
                    if (ts.position.tradingMode.isBlank()) {
                        ts.position.tradingMode = "SHITCOIN"
                    }
                } else {
                // V5.7.8: ShitCoin runs independently — Treasury positions don't block it
                try {
                    // V4.0 FIX: Check execution permit first
                    val permitResult = FinalExecutionPermit.canExecute(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        requestingLayer = "SHITCOIN",
                        hasOpenPosition = ts.position.isOpen
                    )
                    
                    if (!permitResult.allowed) {
                        ErrorLogger.debug("BotService", "💩 [SHITCOIN] ${ts.symbol} | BLOCKED | ${permitResult.reason}")
                    } else {
                        // Calculate token age
                        val tokenAgeMinutes = if (ts.addedToWatchlistAt > 0) {
                            (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                        } else {
                            120.0  // Default 2 hours if unknown
                        }
                        
                        // Check if this qualifies as a shitcoin candidate
                        if (com.lifecyclebot.v3.scoring.ShitCoinTraderAI.isShitCoinCandidate(
                            marketCapUsd = ts.lastMcap,
                            tokenAgeMinutes = tokenAgeMinutes
                        )) {
                        // Detect launch platform from source
                        val launchPlatform = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.detectPlatform(ts.source)
                        
                        // Extract holder and bundle info from safety data
                        // Use firstBlockSupplyPct as proxy for dev/bundle holding
                        val devHoldPct = ts.safety.firstBlockSupplyPct.takeIf { it >= 0 } ?: 10.0
                        val bundlePct = if (ts.safety.bundleRisk == "HIGH") 80.0 
                                       else if (ts.safety.bundleRisk == "MEDIUM") 50.0 
                                       else 10.0
                        
                        // Calculate social score from available signals
                        val socialScore = calculateSocialScore(ts)
                        
                        // Check for DEX boost/trending
                        val isDexBoosted = ts.source.contains("BOOSTED", ignoreCase = true)
                        val dexTrendingRank = if (ts.source.contains("TRENDING", ignoreCase = true)) 25 else 0
                        
                        // Check for copycat/scam patterns
                        val isCopyCat = detectCopyCat(ts.symbol)
                        
                        // Calculate graduation progress for pump.fun tokens
                        val graduationProgress = if (launchPlatform == com.lifecyclebot.v3.scoring.ShitCoinTraderAI.LaunchPlatform.PUMP_FUN) {
                            // Graduation happens around $69K mcap on pump.fun
                            ((ts.lastMcap / 69_000.0) * 100).coerceAtMost(100.0)
                        } else 0.0
                        
                        // V5.9.103: COLLAPSE GUARD — skip ShitCoin emit on draining-liquidity tokens
                        // Uses LiquidityDepthAI's existing trend analysis (same engine that
                        // already logs '💧 X: COLLAPSE ...'). If trend is COLLAPSE or DRAIN
                        // with depth POOR/DANGEROUS, we skip the evaluate entirely rather
                        // than relying on V3 to veto after ShitCoin already emits QUALIFIED.
                        val liqCollapseDetected = try {
                            val trend = com.lifecyclebot.engine.LiquidityDepthAI.analyzeTrend(ts.mint)
                            trend.trend == com.lifecyclebot.engine.LiquidityDepthAI.Trend.COLLAPSE ||
                                (trend.trend == com.lifecyclebot.engine.LiquidityDepthAI.Trend.DRAIN &&
                                    (trend.depthQuality == com.lifecyclebot.engine.LiquidityDepthAI.DepthQuality.POOR ||
                                     trend.depthQuality == com.lifecyclebot.engine.LiquidityDepthAI.DepthQuality.DANGEROUS))
                        } catch (_: Exception) { false }
                        if (liqCollapseDetected) {
                            ErrorLogger.info(
                                "BotService",
                                "💩 [SHITCOIN] ${ts.symbol} | SKIP_EVAL | LIQUIDITY_COLLAPSE detected — not safe to enter"
                            )
                        } else {

                        var shitCoinSignal = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.evaluate(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            currentPrice = ts.ref,
                            marketCapUsd = ts.lastMcap,
                            liquidityUsd = ts.lastLiquidityUsd,
                            topHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0 } ?: 20.0,
                            buyPressurePct = ts.lastBuyPressurePct,
                            momentum = ts.momentum ?: 0.0,
                            volatility = ts.volatility ?: 0.0,
                            tokenAgeMinutes = tokenAgeMinutes,
                            launchPlatform = launchPlatform,
                            devWallet = null,  // Dev wallet tracking not yet implemented
                            devHoldPct = devHoldPct,
                            bundlePct = bundlePct,
                            socialScore = socialScore,
                            // V5.9.618 — KILLED the social-score farce.
                            // Pre-V5.9.618 these were fabricated from a single proxy
                            // (`symbol.length > 2`, `socialScore >= 20`), feeding ~7
                            // points of garbage signal to ShitCoin scoring. Until we
                            // wire real social-presence detection, default to false.
                            // ShitCoin's score floors are already calibrated low so
                            // this won't choke entries — it just stops feeding noise
                            // into AdaptiveLearningEngine pattern weights.
                            hasWebsite = false,
                            hasTwitter = false,
                            hasTelegram = false,
                            hasGithub = false,
                            isDexBoosted = isDexBoosted,
                            dexTrendingRank = dexTrendingRank,
                            isCopyCat = isCopyCat,
                            graduationProgress = graduationProgress,
                        )

                        // V5.9.618 — BRIDGE ADVISORY BOOST (additive, never blocks).
                        // When the proven 79% WR architecture (MemeUnifiedScorerBridge)
                        // agrees this token should be entered, bump confidence by +5
                        // and entry score by +3. Pure thumb on the scale — never
                        // creates an entry the evaluator rejected; never blocks one.
                        if (ts.bridgeAdvisoryAgrees && shitCoinSignal.shouldEnter) {
                            shitCoinSignal = shitCoinSignal.copy(
                                confidence = (shitCoinSignal.confidence + 5).coerceAtMost(100),
                                entryScore = (shitCoinSignal.entryScore + 3).coerceAtMost(100),
                                reason = shitCoinSignal.reason + " +bridge"
                            )
                        }

                        // V5.9.619 — MEME EDGE AI — pattern-rate readback + layer WR sizing
                        // + streak Kelly damping + cluster correlation guard. All bounded:
                        // score nudge +/-8, size 0.70..1.40. Cold-start (<200 trades) is a
                        // no-op; ramps to 20% pattern blend by 5K trades. Never blocks.
                        val shitCoinNarrative = try {
                            com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(ts.symbol).cluster
                        } catch (_: Exception) {
                            com.lifecyclebot.v3.scoring.MemeNarrativeAI.Cluster.UNKNOWN
                        }
                        val shitCoinOpenInCluster = try {
                            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().count { pos ->
                                runCatching {
                                    com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(pos.symbol).cluster == shitCoinNarrative
                                }.getOrDefault(false)
                            }
                        } catch (_: Exception) { 0 }
                        val shitCoinEdge = com.lifecyclebot.v3.scoring.MemeEdgeAI.evaluate(
                            layer = com.lifecyclebot.v3.scoring.MemeEdgeAI.Layer.SHITCOIN,
                            mcapUsd = ts.lastMcap,
                            tokenAgeMinutes = tokenAgeMinutes,
                            buyRatioPct = ts.lastBuyPressurePct,
                            volumeUsd = ts.lastLiquidityUsd * 0.05,
                            liquidityUsd = ts.lastLiquidityUsd,
                            holderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                            topHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0 } ?: 20.0,
                            holderGrowthRate = ts.holderGrowthRate,
                            rugcheckScore = ts.safety.rugcheckScore.toDouble(),
                            baseEntryScore = shitCoinSignal.entryScore.toDouble(),
                            narrativeCluster = shitCoinNarrative,
                            openClusterCount = shitCoinOpenInCluster,
                        )
                        if (shitCoinEdge.scoreNudge != 0.0 || shitCoinEdge.confidenceNudge != 0.0) {
                            shitCoinSignal = shitCoinSignal.copy(
                                entryScore = (shitCoinSignal.entryScore + shitCoinEdge.scoreNudge.toInt()).coerceIn(0, 100),
                                confidence = (shitCoinSignal.confidence + shitCoinEdge.confidenceNudge.toInt()).coerceIn(0, 100),
                                reason = shitCoinSignal.reason + " +edge[" + shitCoinEdge.explanation + "]"
                            )
                        }

                        // V5.9.449 — REMOVED V5.9.443 CHOP_REJECT for ShitCoin.
                        // The filter blocked DEX_BOOSTED/DEX_TRENDING entries in
                        // early_unknown/pre_pump phases at score<60 — exactly the
                        // fresh pump.fun launches with sparse data that produced
                        // build-1941's WR-in-the-60s. Soft scoring already weighs
                        // these signals; the hard floor was choke pressure.


                        // ═══════════════════════════════════════════════════════════════════
                        // V4.1 COLD-START FIX: Check for bootstrap forced entry
                        // Use raw signal score, not shitCoinSignal.confidence which may be low
                        // ═══════════════════════════════════════════════════════════════════
                        val rawShitcoinScore = calculateBootstrapScore(
                            buyPressurePct = ts.lastBuyPressurePct,
                            liquidityUsd = ts.lastLiquidityUsd,
                            momentum = ts.momentum ?: 0.0,
                            volatility = ts.volatility ?: 0.0,
                        )
                        
                        val forceBootstrapEntry = com.lifecyclebot.v3.scoring.FluidLearningAI.shouldForceBootstrapEntry(
                            score = rawShitcoinScore,
                            liquidityUsd = ts.lastLiquidityUsd,
                            tokenAgeMinutes = tokenAgeMinutes,
                            buyPressurePct = ts.lastBuyPressurePct,
                            isPaper = cfg.paperMode
                        )
                        
                        // V4.1: Enter if ShitCoin says yes OR bootstrap override triggered
                        // V5.9.27 FIX: V3 hard-reject MUST gate BOTH branches, not just bootstrap.
                        // Previously: `shitCoinSignal.shouldEnter || (forceBootstrapEntry && !v3HardReject && !dump)`
                        // meant a V3 FATAL (EXTREME_RUG_RISK_90) on SPIKE was silently bypassed by the
                        // primary ShitCoin path, as seen in prod logs. Treasury already gates globally
                        // (line 4267); ShitCoin now mirrors that structure.
                        // V5.9.182: V3 returns Rejected("SHITCOIN_CANDIDATE") by design — routing only.
                        val v3RejReason = (v3Decision as? com.lifecyclebot.v3.V3Decision.Rejected)?.reason ?: ""
                        val isRoutingReject = v3RejReason.contains("SHITCOIN_CANDIDATE") || v3RejReason.contains("MCAP_TOO_LOW")
                        val shitCoinV3HardReject = !isRoutingReject && (
                            v3Decision is com.lifecyclebot.v3.V3Decision.Rejected
                            || v3Decision is com.lifecyclebot.v3.V3Decision.BlockFatal
                            || v3Decision is com.lifecyclebot.v3.V3Decision.Blocked)  // V5.9.187
                        // V5.9.187: removed duplicate checks that were outside &&-group (was always-true on BlockFatal)
                        @Suppress("UNUSED_EXPRESSION")
                        val shitCoinHasDump = try { AICrossTalk.isCoordinatedDump(ts.mint, ts.symbol) } catch (_: Exception) { false }
                        // V5.9.156 — same bootstrap bypass as Treasury path.
                        val shitCoinDumpBypass = try {
                            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
                        } catch (_: Exception) { false }
                        val shitCoinDumpBlocks = shitCoinHasDump && !shitCoinDumpBypass
                        val shouldEnter = !shitCoinV3HardReject && !shitCoinDumpBlocks &&
                            (shitCoinSignal.shouldEnter || forceBootstrapEntry)

                        // V5.9.27: explicit log so the block is visible when it fires
                        if (shitCoinV3HardReject && (shitCoinSignal.shouldEnter || forceBootstrapEntry)) {
                            val blockReason = when (v3Decision) {
                                is com.lifecyclebot.v3.V3Decision.BlockFatal -> "V3_FATAL:${(v3Decision as com.lifecyclebot.v3.V3Decision.BlockFatal).reason}"
                                is com.lifecyclebot.v3.V3Decision.Blocked -> "V3_BLOCKED"
                                is com.lifecyclebot.v3.V3Decision.Rejected -> "V3_REJECTED"
                                else -> "V3_HARD_REJECT"
                            }
                            ErrorLogger.info("BotService", "💩 [SHITCOIN] ${ts.symbol} | VETOED | $blockReason (shitCoinSignal wanted entry)")
                        }
                        
                        // V5.7.7: Bootstrap score gate - during first 50 trades, require score >= 75
                        if (com.lifecyclebot.v3.scoring.FluidLearningAI.shouldBlockBootstrapTrade(shitCoinSignal.confidence)) {
                            ErrorLogger.debug("BotService", "💩 [SHITCOIN] ${ts.symbol} | BOOTSTRAP BLOCKED | score=${shitCoinSignal.confidence} | ${com.lifecyclebot.v3.scoring.FluidLearningAI.getBootstrapStatus()}")
                            return
                        }
                        
                        if (shouldEnter) {
                            // V5.9.353: Distrust pause — refuse ShitCoin entries when
                            // StrategyTrustAI reports DISTRUSTED + WR<10% + fp>70%.
                            // Prevents the converged-bad-brain from continuing to bleed
                            // through this layer for 10 min at a time.
                            val (paused, why) = BotService.isStrategyPausedByTrust("SHITCOIN")
                            if (paused) {
                                ErrorLogger.info("BotService", "💩 [SHITCOIN] ${ts.symbol} | DISTRUST PAUSE | $why")
                                return
                            }
                            // V4.1: Apply bootstrap size multiplier for micro-positions
                            val bootstrapMultiplier = com.lifecyclebot.v3.scoring.FluidLearningAI.getBootstrapSizeMultiplier()
                            // V5.9.619 — apply MemeEdgeAI size multiplier (bounded 0.70..1.40).
                            val edgeSizeMult = shitCoinEdge.sizeMultiplier
                            val adjustedSize = (shitCoinSignal.positionSizeSol * bootstrapMultiplier * edgeSizeMult).coerceAtLeast(0.01)
                            
                            // V5.2.8 FIX: If bootstrap override forced entry, use default TP/SL values
                            val shitcoinEffectiveTpPct = if (shitCoinSignal.takeProfitPct <= 0.0) 5.0 else shitCoinSignal.takeProfitPct
                            val shitcoinEffectiveSlPct = if (shitCoinSignal.stopLossPct >= 0.0) -8.0 else shitCoinSignal.stopLossPct
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // V5.0: TRADE AUTHORIZER - MUST pass before ANY execution
                            // Prevents post-execution gating drift
                            // ═══════════════════════════════════════════════════════════════════
                            val authResult = TradeAuthorizer.authorize(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                score = ts.lastV3Score ?: shitCoinSignal.confidence,
                                confidence = shitCoinSignal.confidence.toDouble(),
                                quality = "C",  // ShitCoin doesn't have grade, use default
                                isPaperMode = cfg.paperMode,
                                requestedBook = TradeAuthorizer.ExecutionBook.SHITCOIN,
                                rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                                liquidity = ts.lastLiquidityUsd,
                                isBanned = BannedTokens.isBanned(ts.mint),
                            )
                            
                            if (!authResult.isExecutable()) {
                                if (authResult.isShadowOnly()) {
                                    ErrorLogger.info("BotService", "💩 [SHITCOIN] ${ts.symbol} | SHADOW_ONLY | ${authResult.reason}")
                                } else {
                                    ErrorLogger.debug("BotService", "💩 [SHITCOIN] ${ts.symbol} | REJECTED | ${authResult.reason}")
                                    RejectionTelemetry.record("SHITCOIN", authResult.reason)
                                }
                            } else {
                                // AUTHORIZED - proceed with execution
                            
                                // V4.0: Try to acquire execution permit
                                val canExecute = FinalExecutionPermit.tryAcquireExecution(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    layer = "SHITCOIN",
                                    sizeSol = adjustedSize
                                )
                                
                                if (canExecute) {
                                val gradLabel = if (shitCoinSignal.graduationImminent) " [GRAD IMMINENT!]" else ""
                                val bundleLabel = if (shitCoinSignal.bundleWarning) " [BUNDLE!]" else ""
                                val bootstrapTag = if (forceBootstrapEntry) " [BOOTSTRAP]" else ""
                                
                                ErrorLogger.info("BotService", "💩 [SHITCOIN] ${ts.symbol} | ENTER$bootstrapTag | " +
                                    "${shitCoinSignal.launchPlatform.emoji} ${shitCoinSignal.launchPlatform.displayName} | " +
                                    "mcap=\$${(ts.lastMcap/1_000).fmt(1)}K | " +
                                    "risk=${shitCoinSignal.riskLevel.emoji}${shitCoinSignal.riskLevel.name} | " +
                                    "size=${adjustedSize.fmt(3)} SOL (${(bootstrapMultiplier*100).toInt()}%) | " +
                                    "TP=${shitCoinSignal.takeProfitPct}%$gradLabel$bundleLabel")
                                
                                // Execute ShitCoin buy
                                executor.shitCoinBuy(
                                    ts = ts,
                                    sizeSol = adjustedSize,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = shitcoinEffectiveTpPct,  // V5.2.8: Use effective TP
                                    stopLossPct = shitcoinEffectiveSlPct,    // V5.2.8: Use effective SL
                                    wallet = wallet,
                                    isPaper = cfg.paperMode,
                                    launchPlatform = shitCoinSignal.launchPlatform,
                                    riskLevel = shitCoinSignal.riskLevel,
                                )
                                
                                // V5.6.8 FIX: Notify V3 exposure guards
                                com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)

                                // Register with Layer Transition Manager
                                com.lifecyclebot.v3.scoring.LayerTransitionManager.registerPosition(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    layer = com.lifecyclebot.v3.scoring.LayerTransitionManager.TradingLayer.SHITCOIN,
                                    entryMcap = ts.lastMcap,
                                    entryPrice = ts.ref,
                                )

                                // V5.0 FIX: Mark position as shitcoin so checkExit uses correct thresholds
                                ts.position.isShitCoinPosition = true
                                ts.position.tradingMode = "SHITCOIN"
                                ts.position.tradingModeEmoji = "💩"

                                // Register with ShitCoinTraderAI when position is opened or pending live verification.
                                // V5.9.270 FIX: For LIVE buys, liveBuy() sets pendingVerify=true which makes
                                // isOpen=false — so checking isOpen alone skips registration for all live trades!
                                // Correct check: qtyToken > 0 (paper) OR pendingVerify=true (live, awaiting confirm).
                                if (ts.position.qtyToken > 0.0 || ts.position.pendingVerify) {
                                    val actualEntryPrice = ts.position.entryPrice.takeIf { it > 0 } ?: ts.ref
                                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.addPosition(
                                        com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinPosition(
                                            mint = ts.mint,
                                            symbol = ts.symbol,
                                            entryPrice = actualEntryPrice,
                                            entrySol = adjustedSize,
                                            entryTime = System.currentTimeMillis(),
                                            marketCapUsd = ts.lastMcap,
                                            liquidityUsd = ts.lastLiquidityUsd,
                                            isPaper = cfg.paperMode,
                                            takeProfitPct = shitcoinEffectiveTpPct,
                                            stopLossPct = shitcoinEffectiveSlPct,
                                            launchPlatform = shitCoinSignal.launchPlatform,
                                            devWallet = null,
                                            bundlePct = bundlePct,
                                            socialScore = shitCoinSignal.socialScore,
                                            // V5.9.435 — preserve entry score for outcome attribution
                                            entryScore = shitCoinSignal.entryScore,
                                            // V5.9.618 — capture real entry context for AdaptiveLearningEngine
                                            entryBuyPressurePct = ts.lastBuyPressurePct,
                                            entryAgeMinutes = tokenAgeMinutes,
                                            entryHolderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                                            entryTopHolderPct = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0 } ?: 0.0,
                                            entryRugcheckScore = ts.safety.rugcheckScore.toDouble(),
                                            entryHolderGrowthRate = ts.holderGrowthRate,
                                            entryVolumeUsd = ts.lastLiquidityUsd * 0.05,  // 5% L estimate (real volume not always plumbed)
                                            entryMomentum = ts.momentum ?: 0.0,
                                            entryGraduationProgress = graduationProgress,
                                        )
                                    )
                                } else {
                                    ErrorLogger.warn("BotService", "💩 SHITCOIN BUY did not open position for ${ts.symbol} — skipping ShitCoinTraderAI registration")
                                }
                                
                                // Release permit
                                FinalExecutionPermit.releaseExecution(ts.mint)
                                
                                // V4.1: Record trade for learning
                                com.lifecyclebot.v3.scoring.FluidLearningAI.recordTradeStart()
                                
                                val bootstrapLabel = if (forceBootstrapEntry) " [BOOTSTRAP]" else ""
                                addLog("💩 SHITCOIN BUY$bootstrapLabel: ${ts.symbol} | " +
                                    "${shitCoinSignal.launchPlatform.emoji} | " +
                                    "\$${(ts.lastMcap/1_000).toInt()}K mcap | " +
                                    "${adjustedSize.fmt(3)} SOL | " +
                                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                            } else {
                                ErrorLogger.debug("BotService", "💩 [SHITCOIN] ${ts.symbol} | EXECUTION_BLOCKED | another layer executing")
                                // Release authorizer lock since we didn't execute
                                TradeAuthorizer.releasePosition(ts.mint, "PERMIT_BLOCKED")
                            }
                            } // end authResult.isExecutable()
                        }
                    }
                    } // V5.9.103: close else of liqCollapseDetected guard
                    }
                } catch (scEx: Exception) {
                    ErrorLogger.debug("BotService", "💩 [SHITCOIN] ${ts.symbol} | ERROR | ${scEx.message}")
                    FinalExecutionPermit.releaseExecution(ts.mint)
                }
                } // V5.9.409: close else-branch of v3OwnsMemes (ShitCoin legacy path)
            }
            // ═══════════════════════════════════════════════════════════════════
            // END ShitCoin evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // ☠️ THE MANIPULATED - Ride manipulation pumps before the dump
            // Enters tokens OTHER layers BLOCK: bundles, wash trades, whale pumps
            // Hard 4-minute time exit — manipulators don't wait
            // ═══════════════════════════════════════════════════════════════════
            // V5.9.409 — V3 is now the meme authority. Same V3-ready gate
            // as Moonshot — the Manipulated signal still feeds V3 upstream
            // but V3+FDG own execution when enabled.
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.ManipulatedTraderAI.isEnabled() &&
                !(cfg.v3EngineEnabled && try { com.lifecyclebot.v3.V3EngineManager.isReady() } catch (_: Throwable) { false })
            ) {
                try {
                    val manipTokenAgeMinutes = if (ts.addedToWatchlistAt > 0) {
                        (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                    } else 60.0

                    val manipBundlePct = if (ts.safety.bundleRisk == "HIGH") 80.0
                                        else if (ts.safety.bundleRisk == "MEDIUM") 50.0
                                        else 10.0

                    val manipSignal = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.evaluate(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        currentPrice = ts.ref,
                        marketCapUsd = ts.lastMcap,
                        liquidityUsd = ts.lastLiquidityUsd,
                        momentum = ts.momentum ?: 0.0,
                        buyPressurePct = ts.lastBuyPressurePct,
                        bundlePct = manipBundlePct,
                        source = ts.source,
                        ageMinutes = manipTokenAgeMinutes,
                        rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                        isPaper = cfg.paperMode,
                    )

                    if (manipSignal.shouldEnter) {
                        val manipAuthResult = TradeAuthorizer.authorize(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            score = manipSignal.manipScore,
                            confidence = 55.0,
                            quality = "MANIPULATED",
                            isPaperMode = cfg.paperMode,
                            requestedBook = TradeAuthorizer.ExecutionBook.MANIPULATED,  // V5.6.8: Use MANIPULATED book to bypass rugcheck
                            rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                            liquidity = ts.lastLiquidityUsd,
                            isBanned = BannedTokens.isBanned(ts.mint),
                        )

                        if (!manipAuthResult.isExecutable()) {
                            ErrorLogger.info("BotService", "☠️ [MANIP] ${ts.symbol} | ${if (manipAuthResult.isShadowOnly()) "SHADOW_ONLY" else "REJECTED"} | ${manipAuthResult.reason}")
                            if (!manipAuthResult.isShadowOnly()) RejectionTelemetry.record("MANIP", manipAuthResult.reason)
                        } else {
                            ErrorLogger.info("BotService", "☠️ [MANIP] ${ts.symbol} | ENTER | " +
                                "score=${manipSignal.manipScore} | " +
                                "bundle=${manipBundlePct.toInt()}% | " +
                                "bp=${ts.lastBuyPressurePct.toInt()}% | " +
                                "mom=${(ts.momentum ?: 0.0).toInt()}% | " +
                                "size=${String.format("%.4f", manipSignal.positionSizeSol)} SOL | " +
                                "${if (cfg.paperMode) "PAPER" else "LIVE"}")

                            executor.shitCoinBuy(
                                ts = ts,
                                sizeSol = manipSignal.positionSizeSol,
                                walletSol = effectiveBalance,
                                takeProfitPct = 25.0,
                                stopLossPct = -5.0,
                                wallet = wallet,
                                isPaper = cfg.paperMode,
                                launchPlatform = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.detectPlatform(ts.source),
                                riskLevel = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.RiskLevel.EXTREME,
                            )

                            val actualManipEntry = ts.position.entryPrice.takeIf { it > 0 } ?: ts.ref
                            if (ts.position.isOpen) com.lifecyclebot.v3.scoring.ManipulatedTraderAI.addPosition(
                                com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipulatedPosition(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    entryPrice = actualManipEntry,
                                    entrySol = manipSignal.positionSizeSol,
                                    entryTime = System.currentTimeMillis(),
                                    takeProfitPct = 25.0,
                                    stopLossPct = -5.0,
                                    manipScore = manipSignal.manipScore,
                                    bundlePct = manipBundlePct,
                                    buyPressure = ts.lastBuyPressurePct,
                                    isPaper = cfg.paperMode,
                                )
                            )
                            
                            // V5.6.8 FIX: Notify V3 exposure guards
                            com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)

                            ts.position.tradingMode = "MANIPULATED"
                            ts.position.tradingModeEmoji = "☠️"

                            addLog("☠️ MANIP: ${ts.symbol} | score=${manipSignal.manipScore} | " +
                                "TP+25% SL-5% | 4min max | " +
                                "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                        }
                    }
                } catch (manipEx: Exception) {
                    ErrorLogger.debug("BotService", "☠️ [MANIP] ${ts.symbol} | ERROR | ${manipEx.message}")
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END ManipulatedTraderAI evaluation
            // ═══════════════════════════════════════════════════════════════════

            // ═══════════════════════════════════════════════════════════════════
            // 💩🚂 SHITCOIN EXPRESS - Quick momentum rides for 30%+ profits
            // Only evaluates tokens that are ALREADY pumping hard
            // V5.2 FIX: Must check if Treasury already has a position!
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.ShitCoinExpress.isEnabled()) {
                // V5.7.8: Express runs independently
                try {
                    val tokenAgeMinutes = if (ts.addedToWatchlistAt > 0) {
                        (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                    } else 60.0
                    
                    // V5.6.29d: Lowered pre-filter to match ShitCoinExpress.kt (was 5%/55%, now 3%/50%)
                    // V5.9.117: Fluid bootstrap gates — mirror ShitCoinExpress's own
                    // fluid gates so fresh-launch tokens with no momentum history
                    // but strong buy pressure still reach evaluate().
                    val momentum = ts.momentum ?: 0.0
                    val expressLearning = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
                    val expressMinMom = (1.0 + expressLearning * 2.0).coerceIn(1.0, 3.0)
                    val expressMinBuyP = (45.0 + expressLearning * 5.0).coerceIn(45.0, 50.0)
                    val effectiveExpressMom = if (momentum <= 0.0 && ts.lastBuyPressurePct >= 65.0) {
                        (ts.lastBuyPressurePct - 60.0).coerceAtLeast(expressMinMom)
                    } else momentum
                    // V5.9.240: Mirror Moonshot's mcapUnknownButLiq bypass —
                    // fresh pump.fun tokens arrive with lastMcap==0 before the
                    // first mcap fetch lands; still allow if liquidity >= $1K.
                    // V5.9.245: Raised Express mcap ceiling $300K → $5M — trending memes often 500K-3M
                    val expressInMcapRange = ts.lastMcap in 2_000.0..5_000_000.0
                    val expressUnknownMcapOk = ts.lastMcap <= 0.0 && ts.lastLiquidityUsd >= 1_000.0
                    val passesPreFilter = (expressInMcapRange || expressUnknownMcapOk) &&
                        effectiveExpressMom >= expressMinMom && ts.lastBuyPressurePct >= expressMinBuyP
                    
                    if (!passesPreFilter) {
                        // V5.9.116: throttled diagnostic so the user can see WHY
                        // Express never qualifies instead of silent skip.
                        val mcap = ts.lastMcap.toInt()
                        val reason = when {
                            !expressInMcapRange && !expressUnknownMcapOk ->
                                if (ts.lastMcap <= 0.0) "mcap=unknown liq=$${ts.lastLiquidityUsd.toInt()} < \$1K"
                                else if (ts.lastMcap < 2_000) "mcap=\$$mcap < \$2K"
                                else "mcap=\$$mcap > \$300K"
                            effectiveExpressMom < expressMinMom -> "mom=${effectiveExpressMom.fmt(1)}% < ${expressMinMom.fmt(1)}% (learning=${(expressLearning*100).toInt()}%)"
                            ts.lastBuyPressurePct < expressMinBuyP -> "buyP=${ts.lastBuyPressurePct.toInt()}% < ${expressMinBuyP.toInt()}%"
                            else -> "unknown"
                        }
                        logLayerSkip("💩🚂 EXPRESS", ts.symbol, ts.mint, reason)
                    } else {
                        val isTrending = ts.source.contains("TRENDING", ignoreCase = true)
                        val isBoosted = ts.source.contains("BOOSTED", ignoreCase = true)
                        
                        // Calculate 5 min price change
                        val lastHistoryEntry = ts.history.lastOrNull()
                        val priceChange5Min = if (lastHistoryEntry != null && lastHistoryEntry.priceUsd > 0) {
                            ((ts.ref - lastHistoryEntry.priceUsd) / lastHistoryEntry.priceUsd * 100).coerceIn(-50.0, 100.0)
                        } else 0.0
                        
                        val expressSignal = com.lifecyclebot.v3.scoring.ShitCoinExpress.evaluate(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            currentPrice = ts.ref,
                            marketCapUsd = ts.lastMcap,
                            liquidityUsd = ts.lastLiquidityUsd,
                            momentum = effectiveExpressMom,  // V5.9.117: use synthesized bootstrap momentum
                            buyPressurePct = ts.lastBuyPressurePct,
                            volumeChange = 1.5,  // Default estimate
                            priceChange5Min = priceChange5Min,
                            isTrending = isTrending,
                            isBoosted = isBoosted,
                            tokenAgeMinutes = tokenAgeMinutes,
                        )
                        
                        if (!expressSignal.shouldRide) {
                            // V5.9.116: throttled diagnostic for score-stage rejections.
                            logLayerSkip("💩🚂 EXPRESS", ts.symbol, ts.mint, expressSignal.reason)
                        }
                        if (expressSignal.shouldRide) {
                            // V5.2: MUST check TradeAuthorizer BEFORE any execution
                            val authResult = TradeAuthorizer.authorize(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                score = expressSignal.estimatedGainPct.toInt(),
                                confidence = 60.0,  // Express rides are momentum plays
                                quality = "EXPRESS",
                                isPaperMode = cfg.paperMode,
                                requestedBook = TradeAuthorizer.ExecutionBook.SHITCOIN,
                                rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                                liquidity = ts.lastLiquidityUsd,
                                isBanned = BannedTokens.isBanned(ts.mint),
                            )
                            if (!authResult.isExecutable()) {
                                ErrorLogger.info("BotService", "💩🚂 [EXPRESS] ${ts.symbol} | ${if (authResult.isShadowOnly()) "SHADOW_ONLY" else "REJECTED"} | ${authResult.reason}")
                                if (!authResult.isShadowOnly()) RejectionTelemetry.record("EXPRESS", authResult.reason)
                            } else {
                                ErrorLogger.info("BotService", "💩🚂 [EXPRESS] ${ts.symbol} | RIDE | " +
                                    "${expressSignal.rideType.emoji} ${expressSignal.rideType.name} | " +
                                    "mom=${(ts.momentum ?: 0.0).fmt(1)}% | " +
                                    "size=${expressSignal.positionSizeSol.fmt(3)} SOL | " +
                                    "target=${expressSignal.estimatedGainPct.toInt()}%")
                                
                                // Execute buy first — only board the ride if the buy actually opened
                                executor.shitCoinBuy(
                                    ts = ts,
                                    sizeSol = expressSignal.positionSizeSol,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = expressSignal.estimatedGainPct,
                                    stopLossPct = -8.0,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode,
                                    launchPlatform = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.detectPlatform(ts.source),
                                    riskLevel = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.RiskLevel.EXTREME,
                                )

                                if (ts.position.isOpen) com.lifecyclebot.v3.scoring.ShitCoinExpress.boardRide(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    entryPrice = ts.ref,
                                    entrySol = expressSignal.positionSizeSol,
                                    momentum = ts.momentum ?: 0.0,
                                    buyPressure = ts.lastBuyPressurePct,
                                    isPaper = cfg.paperMode,
                                )
                                
                                addLog("💩🚂 EXPRESS: ${ts.symbol} | ${expressSignal.rideType.emoji} | " +
                                    "target +${expressSignal.estimatedGainPct.toInt()}% | " +
                                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                            }
                        }
                    }
                } catch (expEx: Exception) {
                    ErrorLogger.debug("BotService", "💩🚂 [EXPRESS] ${ts.symbol} | ERROR | ${expEx.message}")
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END ShitCoin Express evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // 🎯 PROJECT SNIPER - Snipe fresh launches
            // V5.6.29d: New layer for catching launch pumps
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.ProjectSniperAI.isEnabled()) {
                // Check if we already have a sniper mission on this token
                if (com.lifecyclebot.v3.scoring.ProjectSniperAI.hasMission(ts.mint)) {
                    // Check exit conditions
                    try {
                        val exitSignal = com.lifecyclebot.v3.scoring.ProjectSniperAI.checkExit(
                            ts.mint, ts.ref, ts.lastBuyPressurePct
                        )
                        // V5.9.170 — firehose learning feedback for sniper.
                        try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "Sniper:${exitSignal.rank.name}") } catch (_: Exception) {}
                        if (exitSignal.shouldExit) {
                            ErrorLogger.info("BotService", "🎯 [SNIPER] ${ts.symbol} | EXIT | " +
                                "${exitSignal.rank.emoji} ${exitSignal.reason}")
                            
                            // Execute the sell
                            val mission = com.lifecyclebot.v3.scoring.ProjectSniperAI.getMission(ts.mint)
                            if (mission != null) {
                                // Sniper uses full exits for speed - no partial sells
                                executor.paperSell(ts, "SNIPER_${exitSignal.rank.name}")
                                com.lifecyclebot.v3.scoring.ProjectSniperAI.completeMission(ts.mint, ts.ref, exitSignal)
                            }
                            
                            addLog("🎯 SNIPER: ${ts.symbol} | ${exitSignal.rank.emoji} ${exitSignal.reason}", ts.mint)
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "🎯 [SNIPER] ${ts.symbol} | EXIT_ERROR | ${e.message}")
                    }
                } else if (!com.lifecyclebot.v3.scoring.CashGenerationAI.hasPosition(ts.mint)) {
                    // Try to acquire target
                    try {
                        val assessment = com.lifecyclebot.v3.scoring.ProjectSniperAI.assessTarget(ts, ts.ref)
                        
                        if (assessment.shouldEngage) {
                            // Authorize with TradeAuthorizer
                            val authResult = TradeAuthorizer.authorize(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                score = assessment.confidence,
                                confidence = assessment.confidence.toDouble(),
                                quality = "SNIPER",
                                isPaperMode = cfg.paperMode,
                                requestedBook = TradeAuthorizer.ExecutionBook.SHITCOIN,
                                rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                                liquidity = ts.lastLiquidityUsd,
                                isBanned = BannedTokens.isBanned(ts.mint),
                            )
                            
                            if (authResult.isExecutable()) {
                                ErrorLogger.info("BotService", "🎯 [SNIPER] ${ts.symbol} | ENGAGE | " +
                                    "${assessment.threatLevel.emoji} | age=${assessment.tokenAgeSecs}s | " +
                                    "size=${assessment.positionSizeSol.fmt(3)}◎ | conf=${assessment.confidence}%")
                                
                                // Engage mission
                                com.lifecyclebot.v3.scoring.ProjectSniperAI.engageMission(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    entryPrice = ts.ref,
                                    entrySol = assessment.positionSizeSol,
                                    assessment = assessment,
                                    liquidity = ts.lastLiquidityUsd,
                                    mcap = ts.lastMcap,
                                    buyPressure = ts.lastBuyPressurePct,
                                )
                                
                                // Execute buy
                                executor.shitCoinBuy(
                                    ts = ts,
                                    sizeSol = assessment.positionSizeSol,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = 35.0,
                                    stopLossPct = -12.0,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode,
                                    launchPlatform = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.detectPlatform(ts.source),
                                    riskLevel = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.RiskLevel.EXTREME,
                                )
                                
                                addLog("🎯 SNIPER: ${ts.symbol} | ${assessment.threatLevel.emoji} ENGAGED | " +
                                    "age=${assessment.tokenAgeSecs}s | ${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                            } else {
                                ErrorLogger.debug("BotService", "🎯 [SNIPER] ${ts.symbol} | ${authResult.reason}")
                            }
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "🎯 [SNIPER] ${ts.symbol} | ERROR | ${e.message}")
                    }
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END Project Sniper evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // 📉🎯 DIP HUNTER - Buy quality dips on established tokens
            // V5.2 FIX: Must check if Treasury already has a position!
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.DipHunterAI.isEnabled()) {
                // V5.7.8: DipHunter runs independently
                try {
                    val tokenAgeHours = if (ts.addedToWatchlistAt > 0) {
                        (System.currentTimeMillis() - ts.addedToWatchlistAt) / (60 * 60 * 1000.0)
                    } else 12.0
                    
                    // Dip Hunter for tokens $50K-$5M mcap that have been around
                    if (ts.lastMcap in 50_000.0..5_000_000.0 && tokenAgeHours >= 2.0) {
                        
                        // Calculate high from recent history or estimate
                        val historyHigh = ts.history.maxOfOrNull { it.priceUsd } ?: 0.0
                        val recentHigh = if (historyHigh > 0) historyHigh else (ts.ref * 1.3)
                        
                        val dipSignal = com.lifecyclebot.v3.scoring.DipHunterAI.evaluate(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            currentPrice = ts.ref,
                            highPrice = recentHigh,
                            marketCapUsd = ts.lastMcap,
                            liquidityUsd = ts.lastLiquidityUsd,
                            buyPressurePct = ts.lastBuyPressurePct,
                            volumeVsAvg = 1.0,
                            tokenAgeHours = tokenAgeHours,
                            holderCount = 100,  // Default holder count estimate
                            holderChange24h = 0,
                            isDevSelling = ts.safety.bundleRisk == "HIGH",
                        )
                        
                        if (dipSignal.shouldBuy) {
                            // V5.2: MUST check TradeAuthorizer BEFORE any execution
                            val authResult = TradeAuthorizer.authorize(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                score = dipSignal.confidence,
                                confidence = dipSignal.confidence.toDouble(),
                                quality = "DIP",
                                isPaperMode = cfg.paperMode,
                                requestedBook = TradeAuthorizer.ExecutionBook.DIP_HUNTER,
                                rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                                liquidity = ts.lastLiquidityUsd,
                                isBanned = BannedTokens.isBanned(ts.mint),
                            )
                            
                            if (!authResult.isExecutable()) {
                                ErrorLogger.info("BotService", "📉🎯 [DIP] ${ts.symbol} | ${if (authResult.isShadowOnly()) "SHADOW_ONLY" else "REJECTED"} | ${authResult.reason}")
                                if (!authResult.isShadowOnly()) RejectionTelemetry.record("DIP", authResult.reason)
                            } else {
                                ErrorLogger.info("BotService", "📉🎯 [DIP] ${ts.symbol} | BUY | " +
                                    "${dipSignal.dipQuality.emoji} ${dipSignal.dipQuality.name} | " +
                                    "dip=${dipSignal.dipDepthPct.fmt(1)}% | " +
                                    "size=${dipSignal.positionSizeSol.fmt(3)} SOL | " +
                                    "target=+${dipSignal.expectedRecoveryPct.toInt()}%")
                                
                                // Open dip position
                                com.lifecyclebot.v3.scoring.DipHunterAI.openDip(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    entryPrice = ts.ref,
                                    entrySol = dipSignal.positionSizeSol,
                                    highPrice = recentHigh,
                                    dipDepthPct = dipSignal.dipDepthPct,
                                    marketCapUsd = ts.lastMcap,
                                    liquidityUsd = ts.lastLiquidityUsd,
                                    isPaper = cfg.paperMode,
                                )
                                
                                // V5.6.8 FIX: Notify V3 exposure guards
                                com.lifecyclebot.v3.V3EngineManager.onPositionOpened(ts.mint)
                                
                                // Execute buy
                                executor.paperBuy(
                                    ts = ts,
                                    sol = dipSignal.positionSizeSol,
                                    score = dipSignal.confidence.toDouble(),
                                    identity = identity,
                                    quality = "DIP_HUNTER",
                                    skipGraduated = true,
                                    wallet = wallet,
                                    walletSol = effectiveBalance
                                )
                                
                                ts.position.tradingMode = "DIP_HUNTER"
                                ts.position.tradingModeEmoji = "📉"
                                
                                addLog("📉🎯 DIP BUY: ${ts.symbol} | ${dipSignal.dipQuality.emoji} | " +
                                    "dip ${dipSignal.dipDepthPct.toInt()}% | " +
                                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                            }
                        }
                    }
                } catch (dipEx: Exception) {
                    ErrorLogger.debug("BotService", "📉🎯 [DIP] ${ts.symbol} | ERROR | ${dipEx.message}")
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END Dip Hunter evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // END Treasury Mode evaluation - now proceed with V3 decision handling
            // V5.2: Moonshot evaluation moved BEFORE ShitCoin for proper execution order
            // ═══════════════════════════════════════════════════════════════════
            
            when (val result = v3Decision) {
                is com.lifecyclebot.v3.V3Decision.Execute -> {
                    // V5.9.199: StrategyTrust gate — skip execute if DISTRUSTED, don't return
                    // V5.9.408: free-range mode bypasses the trust gate entirely.
                    val memeMode = ts.position.tradingMode.ifBlank { identity.phase.ifBlank { "SHITCOIN" } }
                    val trustAllowed = FreeRangeMode.isWideOpen() ||
                        com.lifecyclebot.v4.meta.StrategyTrustAI.isStrategyAllowed(memeMode)
                    if (!trustAllowed) {
                        ErrorLogger.warn("BotService", "🚫 [TRUST GATE] ${identity.symbol} | mode=$memeMode DISTRUSTED — skipping execute")
                    } else {
                    // Cache V3 scores on TokenState for Treasury Mode to use
                    ts.lastV3Score = result.score
                    ts.lastV3Confidence = result.confidence.toInt()
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // V3 EXECUTE: Clean logging + trade execution
                    // ═══════════════════════════════════════════════════════════════════
                    ErrorLogger.info("BotService", "[SCORING] ${identity.symbol} | total=${result.score} | ${result.breakdown}")
                    ErrorLogger.info("BotService", "[FATAL] ${identity.symbol} | PASS")
                    ErrorLogger.info("BotService", "[CONFIDENCE] ${identity.symbol} | ${result.confidence.toInt()}%")
                    ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | ${result.band} | score=${result.score} conf=${result.confidence.toInt()}%")
                    ErrorLogger.info("BotService", "[SIZING] ${identity.symbol} | ${result.sizeSol.fmt(4)} SOL")
                    
                    // A+ alert for high-conviction setups
                    if (result.score >= 75) {
                        soundManager.playAplusAlert()
                        addLog("⭐ HIGH CONFIDENCE: ${identity.symbol} | score=${result.score}", ts.mint)
                    }
                    
                    if (!cfg.v3ShadowMode) {
                        // V5.9.168 — FLUID FRESH-LAUNCH SCORE GATE
                        // V5.9.97 had a binary gate (age<5m + score<40 → skip);
                        // V5.9.150 removed it entirely which restored volume
                        // but tanked win-rate on fresh pump.fun launches that
                        // scored 20-30 (coin-flip quality, 13 of 22 V3 layers
                        // data-starved at <5min age). Replace with a fluid
                        // minimum that scales with learning progress:
                        //   bootstrap (0% prog)  → score >= 15 (very lenient)
                        //   Freshman  (40% prog) → score >= 25
                        //   mature    (100% prog)→ score >= 40 (V5.9.97 strict)
                        // Only applies to tokens <5min old; established
                        // tokens use the standard fluid floors.
                        run {
                            val tokenAgeMins = if (ts.addedToWatchlistAt > 0) {
                                (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                            } else Double.MAX_VALUE
                            if (tokenAgeMins < 5.0) {
                                val freshLaunchMinScore = (12 + com.lifecyclebot.v3.scoring.FluidLearningAI
                                    .getLearningProgress() * 28).toInt().coerceIn(12, 40)
                                if (result.score < freshLaunchMinScore) {
                                    ErrorLogger.info(
                                        "BotService",
                                        "[V3|FRESH_LAUNCH_GATE] ${identity.symbol} | SKIP | " +
                                            "age=${tokenAgeMins.toInt()}m<5m score=${result.score}<$freshLaunchMinScore (fluid by learning)"
                                    )
                                    return
                                }
                            }
                        }

                        // V5.9.93: FRESH-LAUNCH DRAWDOWN GATE
                        // When SmartChart has < 10 candles, it cannot veto
                        // bearish moves, so substitute a simple drawdown
                        // check: skip the entry if price has dropped more
                        // than 15% from the earliest recorded candle (pool
                        // formation). Protects against brand-new rugs.
                        run {
                            val candles = ts.history
                            if (candles.size in 1..9) {
                                val firstPrice = candles.first().priceUsd
                                if (firstPrice > 0.0 && ts.ref > 0.0) {
                                    val drawdownPct = ((ts.ref - firstPrice) / firstPrice) * 100.0
                                    if (drawdownPct <= -15.0) {
                                        ErrorLogger.info(
                                            "BotService",
                                            "[V3|FRESH_DRAWDOWN] ${identity.symbol} | SKIP | " +
                                                "candles=${candles.size} dd=${drawdownPct.toInt()}% " +
                                                "(first=${firstPrice} now=${ts.ref})"
                                        )
                                        return
                                    }
                                }
                            }
                        }

                        // V5.2: MUST check TradeAuthorizer BEFORE any execution
                        val authResult = TradeAuthorizer.authorize(
                            mint = ts.mint,
                            symbol = identity.symbol,
                            score = result.score,
                            confidence = result.confidence.toDouble(),
                            quality = decision.finalQuality,
                            isPaperMode = cfg.paperMode,
                            requestedBook = TradeAuthorizer.ExecutionBook.CORE,
                            rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
                            liquidity = ts.lastLiquidityUsd,
                            isBanned = BannedTokens.isBanned(ts.mint),
                        )
                        
                        if (!authResult.isExecutable()) {
                            // NOT AUTHORIZED - log and skip execution
                            if (authResult.isShadowOnly()) {
                                ErrorLogger.info("BotService", "[V3|AUTH] ${identity.symbol} | SHADOW_ONLY | ${authResult.reason}")
                                // Track for shadow learning
                                ShadowLearningEngine.onFdgBlockedTrade(
                                    mint = ts.mint,
                                    symbol = identity.symbol,
                                    blockReason = "V3_AUTH_SHADOW_${authResult.reason}",
                                    blockLevel = "TRADE_AUTHORIZER",
                                    currentPrice = ts.ref,
                                    proposedSizeSol = result.sizeSol,
                                    quality = decision.finalQuality,
                                    confidence = result.confidence.toDouble(),
                                    phase = decision.phase,
                                )
                            } else {
                                ErrorLogger.info("BotService", "[V3|AUTH] ${identity.symbol} | REJECTED | ${authResult.reason}")
                                RejectionTelemetry.record("V3_AUTH", authResult.reason)
                            }
                        } else {
                            // AUTHORIZED - proceed with execution
                            // V3 CONTROLS EXECUTION
                            val v3SizeSol = result.sizeSol
                            val v3Thesis = "V3 score=${result.score} band=${result.band}"
                            
                            // Update lifecycle to V3 states
                            identity.v3Execute(result.score, result.band, result.sizeSol)
                            
                            // Execute the trade
                            var proposedSize = result.sizeSol
                            val modeTag = try {
                                // V5.9.409 — per-token lane wins over global bot mode
                                ModeSpecificGates.fromTradingMode(ts.position.tradingMode)
                                    ?: modeConf?.mode?.let { ModeSpecificGates.fromBotMode(it) }
                            } catch (e: Exception) { null }

                            // ═══════════════════════════════════════════════════════════════
                            // V5.9.418 — V3-MEME SENTIENCE & SYMBOLIC RE-WIRE.
                            // Before V5.9.418 the V3 Execute path went straight to
                            // executor.v3Buy() — bypassing FDG entirely — which meant
                            // (a) the 24-channel SymbolicContext never adjusted size on
                            // memes, (b) ModeSpecificGates.ModeMultipliers were computed
                            // but never applied, and (c) SentienceHooks.shouldFilterByPersonality
                            // was dead code. The legacy CORE/Treasury/Quality paths
                            // still flow through FDG.evaluate() so they were fine —
                            // only the meme V3-authority path lost the symbolic nervous
                            // system.
                            //
                            // We do NOT re-run full FDG.evaluate() here (heavy, would
                            // double-log) — instead we apply the same 4 surgical inputs
                            // FDG would: refresh SymbolicContext, read ModeMultipliers,
                            // honour the symbolic universe block, and apply size
                            // adjustments + personality + cross-engine bias before
                            // executor.v3Buy fires.
                            // ═══════════════════════════════════════════════════════════════
                            val symRefreshed = try {
                                com.lifecyclebot.engine.SymbolicContext.refresh(ts.symbol, ts.mint)
                                true
                            } catch (_: Throwable) { false }
                            val symMood = try { com.lifecyclebot.engine.SymbolicContext.emotionalState } catch (_: Throwable) { "NEUTRAL" }
                            val symGreenLight = try { com.lifecyclebot.engine.SymbolicContext.getEntryGreenLight() } catch (_: Throwable) { 0.5 }
                            val symSizeAdj = try { com.lifecyclebot.engine.SymbolicContext.getSizeAdjustment() } catch (_: Throwable) { 1.0 }
                            val symCircuitBreaking = try { com.lifecyclebot.engine.SymbolicContext.isCircuitBreaking() } catch (_: Throwable) { false }

                            // Mirror FDG's SYMBOLIC_UNIVERSE_BLOCK (LIVE only).
                            // Paper-mode keeps learning even in panic/circuit states.
                            if (!cfg.paperMode &&
                                symGreenLight < 0.20 &&
                                symMood in listOf("PANIC", "FEARFUL") &&
                                symCircuitBreaking
                            ) {
                                ErrorLogger.warn(
                                    "BotService",
                                    "🌌 [V3|SYMBOLIC_BLOCK] ${identity.symbol} | greenLight=${"%.2f".format(symGreenLight)} mood=$symMood circuit_breaking=true (LIVE) — SKIP execute"
                                )
                                addLog("🌌 SYMBOLIC BLOCK: ${identity.symbol} | $symMood / circuit-breaking", ts.mint)
                                return
                            }

                            // V5.9.449 — REMOVED V5.9.421 TRIPLE-DANGER hard gate.
                            // The combination (TimeAI.isDangerZone + cold MemeNarrative
                            // cluster + liquidity<$100K) blocked exactly the dead-hour
                            // fresh-launch / sparse-data tokens that produced
                            // build-1941's 500% runners. Each component still
                            // contributes -6pts to the soft score; that's the
                            // build-1941 baseline behaviour.

                            // Sentience hook #6 — personality-driven filter.
                            // If the user's recent chat said "avoid SHITCOIN" / "avoid memes",
                            // skip this entry. Best-effort, fail-open.
                            val regimeHint = ts.position.tradingMode.ifBlank { modeConf?.mode?.name ?: "" }
                            val personalityVeto = try {
                                com.lifecyclebot.engine.SentienceHooks.shouldFilterByPersonality(
                                    symbol = identity.symbol,
                                    regime = regimeHint,
                                )
                            } catch (_: Throwable) { false }
                            if (personalityVeto) {
                                ErrorLogger.info(
                                    "BotService",
                                    "🧠 [V3|PERSONALITY_VETO] ${identity.symbol} | regime=$regimeHint — user persona said skip"
                                )
                                addLog("🧠 PERSONA VETO: ${identity.symbol} (regime=$regimeHint)", ts.mint)
                                return
                            }

                            // Apply the size cascade FDG would have applied:
                            //   ModeSpecificGates.positionSizeMultiplier (per-lane, e.g.
                            //   memes = looser cap) × SymbolicContext.getSizeAdjustment()
                            //   × SentienceHooks.suggestSizeMultiplier() (cross-engine bias).
                            val modeMultipliers = try {
                                com.lifecyclebot.engine.ModeSpecificGates.getMultipliers(modeTag)
                            } catch (_: Throwable) {
                                com.lifecyclebot.engine.ModeSpecificGates.ModeMultipliers.DEFAULT
                            }
                            val llmSizeMult = try {
                                com.lifecyclebot.engine.SentienceHooks.suggestSizeMultiplier(
                                    engine = "MEME",
                                    symbol = identity.symbol,
                                    regime = regimeHint,
                                )
                            } catch (_: Throwable) { 1.0 }
                            // V5.9.422 — progressive size trim from the QualityLadder.
                            // Tier 0 → 1.00, Tier 5 → 0.50. Never below 0.5 so
                            // volume is preserved even at maximum caution.
                            val ladderSizeMult = try { com.lifecyclebot.engine.QualityLadder.sizeMultiplier() } catch (_: Throwable) { 1.0 }
                            val sizeBefore = proposedSize
                            // V5.9.489 — CASCADE FLOOR (anti-choke).
                            // Operator: 'the meme trader should maintain really good
                            // volume once learnt. it should never ever be allowed to
                            // choke itself out.'
                            //
                            // The 4 multipliers were stacking down to ~0.19× (mode 0.70
                            // × sym 0.54 × llm 1.00 × ladder 0.50) — strangling
                            // entries to 19% of base size on every meme trade. Each
                            // dampener individually is reasonable; the multiplicative
                            // composition is not. Floor the COMBINED product at 0.60
                            // so a single cascade can never wipe out more than 40%
                            // of position size. Volume preserved, signal still steers.
                            val rawProduct =
                                modeMultipliers.positionSizeMultiplier *
                                symSizeAdj *
                                llmSizeMult *
                                ladderSizeMult
                            // V5.9.495z3 — operator: 'stupidly slow paper, 2 open'.
                            // Lift the paper-mode floor 0.60→0.75 so each entry is
                            // bigger (75% of base instead of 60%). Live floor stays
                            // at 0.60 — risk discipline preserved on real money.
                            val cascadeFloor = if (cfg.paperMode) 0.75 else 0.60
                            val flooredProduct = rawProduct.coerceAtLeast(cascadeFloor)
                            proposedSize = (proposedSize * flooredProduct).coerceIn(0.005, 1.0)
                            if (kotlin.math.abs(proposedSize - sizeBefore) > 0.0005) {
                                ErrorLogger.info(
                                    "BotService",
                                    "[V3|SIZE_CASCADE] ${identity.symbol} | base=${sizeBefore.fmt(4)}◎ × " +
                                    "mode=${"%.2f".format(modeMultipliers.positionSizeMultiplier)} × " +
                                    "sym=${"%.2f".format(symSizeAdj)} × " +
                                    "llm=${"%.2f".format(llmSizeMult)} × " +
                                    "ladder=${"%.2f".format(ladderSizeMult)} = ${"%.2f".format(rawProduct)}" +
                                    (if (rawProduct < cascadeFloor) " [floored→${"%.2f".format(cascadeFloor)}]" else "") +
                                    " → ${proposedSize.fmt(4)}◎ " +
                                    "(symRefreshed=$symRefreshed mood=$symMood green=${"%.2f".format(symGreenLight)})"
                                )
                            }

                            ErrorLogger.info("BotService", "[EXECUTION] ${identity.symbol} | ${if (cfg.paperMode) "PAPER" else "LIVE"}_BUY | ${proposedSize.fmt(4)} SOL")
                            
                            // Record proposal for dedupe
                            TradeLifecycle.recordProposal(identity.mint)
                            
                            // Execute buy through unified executor
                            executor.v3Buy(
                                ts = ts,
                            sizeSol = proposedSize,
                            walletSol = effectiveBalance,
                            v3Score = result.score,
                            v3Band = result.band,
                            v3Confidence = result.confidence,
                            wallet = wallet,
                            lastSuccessfulPollMs = lastSuccessfulPollMs,
                            openPositionCount = status.openPositionCount,
                            totalExposureSol = status.totalExposureSol
                        )
                        
                        addLog("⚡ V3 EXECUTE: ${identity.symbol} | ${result.band} | ${proposedSize.fmt(4)} SOL", ts.mint)
                        } // end authResult.isExecutable() else block
                    } else {
                        // Shadow mode - log only
                        ErrorLogger.info("BotService", "[SHADOW] ${identity.symbol} | WOULD_EXECUTE | ${result.band} | ${result.sizeSol.fmt(4)} SOL")
                        addLog("🔬 V3 SHADOW: ${identity.symbol} | ${result.band}", ts.mint)
                    }
                    // V5.9.93: V3 owns the decision when enabled — do NOT fall
                    // through to the legacy PROMOTION_GATE path, which was
                    // post-hoc emitting SHADOW_ONLY after the core buy already
                    // fired. Treasury and all other layer evaluations ran above.
                    } // end trust-allowed else block
                    return
                }
                
                is com.lifecyclebot.v3.V3Decision.Watch -> {
                    // Cache V3 scores on TokenState for Treasury Mode
                    ts.lastV3Score = result.score
                    ts.lastV3Confidence = result.confidence.toInt()
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // V3 WATCH: Track but don't trade NORMALLY
                    // BUT Treasury Mode CAN still evaluate this token for quick scalps!
                    // ═══════════════════════════════════════════════════════════════════
                    ErrorLogger.info("BotService", "[SCORING] ${identity.symbol} | total=${result.score} | below threshold")
                    ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | WATCH | score=${result.score} conf=${result.confidence}%")
                    
                    // Shadow track for learning
                    ShadowLearningEngine.onFdgBlockedTrade(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        blockReason = "V3_WATCH_score=${result.score}",
                        blockLevel = "V3",
                        currentPrice = ts.ref,
                        proposedSizeSol = 0.1,
                        quality = decision.finalQuality,
                        confidence = result.confidence.toDouble(),
                        phase = decision.phase,
                    )
                    
                    // V5.9.349 / V5.9.352: Bridge override on V3 WATCH — gated by
                    // blend≥60 + tech≥55 + liquidity-OK + rate-limit. Logging-only
                    // for every token; execution only when conviction is high.
                    if (memeBridgeVerdict?.shouldEnter == true) {
                        val (allow, why) = bridgeOverrideAllowed(memeBridgeVerdict, ts.mint, identity.symbol)
                        if (allow) {
                            try {
                                val bridgeSize = 0.05
                                ErrorLogger.info("BotService", "🌉 BRIDGE OVERRIDE on V3_WATCH: ${identity.symbol} | tech=${memeBridgeVerdict.techScore} blend=${memeBridgeVerdict.blendedScore}")
                                addLog("🌉 Bridge BUY: ${identity.symbol} | V3_WATCH override | tech=${memeBridgeVerdict.techScore} blend=${memeBridgeVerdict.blendedScore} | ${bridgeSize} SOL", ts.mint)
                                executor.v3Buy(
                                    ts = ts,
                                    sizeSol = bridgeSize,
                                    walletSol = effectiveBalance,
                                    v3Score = memeBridgeVerdict.blendedScore,
                                    v3Band = "MEME_BRIDGE",
                                    v3Confidence = 60.0,
                                    wallet = wallet,
                                    lastSuccessfulPollMs = lastSuccessfulPollMs,
                                    openPositionCount = status.openPositionCount,
                                    totalExposureSol = status.totalExposureSol,
                                )
                            } catch (be: Exception) {
                                ErrorLogger.debug("BotService", "🌉 Bridge execute failed on WATCH ${identity.symbol}: ${be.message}")
                            }
                        } else {
                            ErrorLogger.debug("BotService", "🌉 Bridge override SKIP (WATCH ${identity.symbol}): $why")
                        }
                    }
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // V3.3 FIX: DO NOT RETURN - Allow Treasury Mode evaluation below!
                    // Treasury Mode runs CONCURRENTLY and can scalp WATCH tokens
                    // ═══════════════════════════════════════════════════════════════════
                    // Previously: return (BLOCKED Treasury Mode!)
                    // V5.9.93: Treasury already evaluated above (line ~4449).
                    // Returning here prevents legacy PROMOTION_GATE co-firing.
                    return
                }
                
                is com.lifecyclebot.v3.V3Decision.ShadowOnly -> {
                    // ═══════════════════════════════════════════════════════════════════
                    // V3 SHADOW_ONLY: Pre-proposal kill, learning only
                    // ═══════════════════════════════════════════════════════════════════
                    ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | SHADOW_ONLY | ${result.reason}")
                    
                    // Shadow track for learning
                    ShadowLearningEngine.onFdgBlockedTrade(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        blockReason = "V3_SHADOW_ONLY_${result.reason}",
                        blockLevel = "V3_PRE_PROPOSAL",
                        currentPrice = ts.ref,
                        proposedSizeSol = 0.1,
                        quality = decision.finalQuality,
                        confidence = result.confidence.toDouble(),
                        phase = decision.phase,
                    )
                    
                    // V5.9.349 / V5.9.352: Bridge override on V3 SHADOW_ONLY — gated.
                    if (memeBridgeVerdict?.shouldEnter == true) {
                        val (allow, why) = bridgeOverrideAllowed(memeBridgeVerdict, ts.mint, identity.symbol)
                        if (allow) {
                            try {
                                val bridgeSize = 0.05
                                ErrorLogger.info("BotService", "🌉 BRIDGE OVERRIDE on V3_SHADOW_ONLY: ${identity.symbol} | tech=${memeBridgeVerdict.techScore} blend=${memeBridgeVerdict.blendedScore}")
                                addLog("🌉 Bridge BUY: ${identity.symbol} | V3_SHADOW override | tech=${memeBridgeVerdict.techScore} blend=${memeBridgeVerdict.blendedScore} | ${bridgeSize} SOL", ts.mint)
                                executor.v3Buy(
                                    ts = ts,
                                    sizeSol = bridgeSize,
                                    walletSol = effectiveBalance,
                                    v3Score = memeBridgeVerdict.blendedScore,
                                    v3Band = "MEME_BRIDGE",
                                    v3Confidence = 60.0,
                                    wallet = wallet,
                                    lastSuccessfulPollMs = lastSuccessfulPollMs,
                                    openPositionCount = status.openPositionCount,
                                    totalExposureSol = status.totalExposureSol,
                                )
                            } catch (be: Exception) {
                                ErrorLogger.debug("BotService", "🌉 Bridge execute failed on SHADOW_ONLY ${identity.symbol}: ${be.message}")
                            }
                        } else {
                            ErrorLogger.debug("BotService", "🌉 Bridge override SKIP (SHADOW_ONLY ${identity.symbol}): $why")
                        }
                    }
                    
                    return
                }
                
                is com.lifecyclebot.v3.V3Decision.Rejected -> {
                    // ═══════════════════════════════════════════════════════════════════
                    // V3 REJECT: Poor setup OR routing to another layer
                    // V5.2.12: SHITCOIN_CANDIDATE rejection means "let ShitCoin layer handle it"
                    // ═══════════════════════════════════════════════════════════════════
                    
                    // Check if this is a routing rejection (ShitCoin candidate)
                    val isShitCoinRouting = result.reason.contains("SHITCOIN_CANDIDATE")
                    
                    if (isShitCoinRouting) {
                        // V5.2.12: Don't return! Let the ShitCoin evaluation section handle this
                        ErrorLogger.debug("BotService", "[V3|ROUTE] ${identity.symbol} | → SHITCOIN | ${result.reason}")
                        // Fall through to ShitCoin layer evaluation below
                    } else {
                        // True rejection - shadow track and return
                        ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | REJECT | ${result.reason}")
                        RejectionTelemetry.record("DECISION", result.reason)
                        
                        // Shadow track
                        ShadowLearningEngine.onFdgBlockedTrade(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            blockReason = "V3_REJECT_${result.reason}",
                            blockLevel = "V3",
                            currentPrice = ts.ref,
                            proposedSizeSol = 0.1,
                            quality = decision.finalQuality,
                            confidence = 0.0,
                            phase = decision.phase,
                        )
                        
                        // V5.9.349 / V5.9.352: Bridge override on V3 REJECT — gated.
                        if (memeBridgeVerdict?.shouldEnter == true) {
                            val (allow, why) = bridgeOverrideAllowed(memeBridgeVerdict, ts.mint, identity.symbol)
                            if (allow) {
                                try {
                                    val bridgeSize = 0.05
                                    ErrorLogger.info("BotService", "🌉 BRIDGE OVERRIDE on V3_REJECT: ${identity.symbol} | tech=${memeBridgeVerdict.techScore} blend=${memeBridgeVerdict.blendedScore}")
                                    addLog("🌉 Bridge BUY: ${identity.symbol} | V3_REJECT override | tech=${memeBridgeVerdict.techScore} blend=${memeBridgeVerdict.blendedScore} | ${bridgeSize} SOL", ts.mint)
                                    executor.v3Buy(
                                        ts = ts,
                                        sizeSol = bridgeSize,
                                        walletSol = effectiveBalance,
                                        v3Score = memeBridgeVerdict.blendedScore,
                                        v3Band = "MEME_BRIDGE",
                                        v3Confidence = 60.0,
                                        wallet = wallet,
                                        lastSuccessfulPollMs = lastSuccessfulPollMs,
                                        openPositionCount = status.openPositionCount,
                                        totalExposureSol = status.totalExposureSol,
                                    )
                                } catch (be: Exception) {
                                    ErrorLogger.debug("BotService", "🌉 Bridge execute failed on REJECT ${identity.symbol}: ${be.message}")
                                }
                            } else {
                                ErrorLogger.debug("BotService", "🌉 Bridge override SKIP (REJECT ${identity.symbol}): $why")
                            }
                        }
                        
                        return
                    }
                }
                
                is com.lifecyclebot.v3.V3Decision.BlockFatal -> {
                    // ═══════════════════════════════════════════════════════════════════
                    // V3 BLOCK_FATAL: Fatal risk detected
                    // ═══════════════════════════════════════════════════════════════════
                    ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | BLOCK_FATAL | ${result.reason}")
                    return
                }
                
                is com.lifecyclebot.v3.V3Decision.Blocked -> {
                    // ═══════════════════════════════════════════════════════════════════
                    // V3 BLOCK: Legacy fatal block
                    // ═══════════════════════════════════════════════════════════════════
                    ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | BLOCK_FATAL | ${result.reason}")
                    return
                }
                
                else -> {
                    // V3 not ready or error - skip this token
                    ErrorLogger.debug("BotService", "[V3] ${identity.symbol} | NOT_READY")
                    return
                }
            }
            
        } catch (v3e: Exception) {
            ErrorLogger.error("BotService", "[V3] ${identity.symbol} | ERROR | ${v3e.message}")
            return
        }
        
        // V5.9.93: Any V3-enabled code path that reaches here (e.g. Rejected
        // with SHITCOIN_CANDIDATE routing, which intentionally falls through
        // the when) should NOT re-enter the legacy PROMOTION_GATE/FDG pipeline
        // — all eligible layers (Treasury, ShitCoin, BlueChip, Moonshot, DIP)
        // have already evaluated above. Returning here prevents the
        // post-hoc SHADOW_ONLY emission we saw in the audit log.
        return
    }
    // ═══════════════════════════════════════════════════════════════════
    // NOTE: Treasury Mode now runs BEFORE the V3 when block (above)
    // to ensure it evaluates ALL tokens regardless of V3 decision.
    // ═══════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════
    // LEGACY FALLBACK: Only runs if V3 is disabled
    // This path will be deprecated once V3 is fully validated
    // ═══════════════════════════════════════════════════════════════════

    // V5.9.94: Actually enforce what the comment above promised. When V3 is
    // enabled, the legacy PROMOTION_GATE / FDG pipeline below is dead code
    // but was still firing for tokens where V3 was skipped at 4325 (either
    // because ts.position.isOpen == true, or V3EngineManager.isReady() was
    // false). Those emissions produced the phantom "[V3|PROMOTION_GATE] X
    // | allow=false | → SHADOW_ONLY" lines we saw on BULL / SNOW / LENS /
    // UN / dog / MSGA / RCSC / TERMINAL / CATEROID / SIF / 1 — none of
    // which were ever V3-scored. Hard-gate on cfg.v3EngineEnabled so the
    // legacy path is genuinely deprecated when V3 is on.
    if (cfg.v3EngineEnabled && !ts.position.isOpen) {
        return
    }

    // Legacy suppression penalty (for comparison logging)
    val suppressionPenalty = DistributionFadeAvoider.getSuppressionPenalty(identity.mint)
    
    // ───────────────────────────────────────────────────────────────────
    // HARD GATE: Block edge=SKIP or conf=0 BEFORE candidate promotion
    // V5.2: Allow SKIP through during bootstrap (<20% learning) for V3 learning
    // This prevents garbage from going through CANDIDATE/PROPOSED/SIZING
    // ───────────────────────────────────────────────────────────────────
    val edgeVerdictStr = decision.edgeQuality  // "A", "B", "C", or "SKIP"
    val confValue = decision.aiConfidence
    
    // Check if we're in bootstrap phase — allow SKIP trades through for V3 learning.
    // Threshold aligned with V5.3 3-phase curve: bootstrap runs 0→0.5 (first 500 trades).
    // Was 0.20 which was cutting off bootstrap learning at only ~100 trades.
    val learningProgress = try {
        com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
    } catch (_: Exception) { 0.0 }
    val isBootstrap = learningProgress < 0.40  // V5.9.165: aligned to global 0.40 threshold

    // V5.9.31 FLUID: the bot chooses its own conf floor via FluidLearningAI.
    // Bootstrap (0%): ~15 — wide open, gather data.
    // Mature (80%):  ~45 — filter low-conviction noise as winrate data accrues.
    // This drifts with live performance — no human-set number in the hot path.
    // V5.9.41/43: FLUID SKIP-allowance. Hardcoded `false` meant every SKIP
    // candidate was shadow-tracked forever. Now fluid + cached.
    //
    // V5.9.43: swap to TradeHistoryStore.getProvenEdgeCached() — previous
    // version called full getStats() here per-token, which did 3-4 full
    // iterations of the trade list and starved the scanner coroutines
    // (user saw watchlist stop populating until a bot restart).
    val provenEdge = com.lifecyclebot.engine.TradeHistoryStore.getProvenEdgeCached()
    val provenWinRate   = provenEdge.winRate
    val meaningfulCount = provenEdge.meaningfulTrades
    val hasProvenEdge   = provenEdge.hasProvenEdge

    val allowSkipForLearning = isBootstrap || hasProvenEdge
    val pre5000LearningOpen = try { com.lifecyclebot.engine.FreeRangeMode.isWideOpen() } catch (_: Throwable) { false }
    val minBootstrapConf = com.lifecyclebot.v3.scoring.FluidLearningAI.getPaperConfidenceFloor().toInt()

    if (!pre5000LearningOpen && ((edgeVerdictStr == "SKIP" && !allowSkipForLearning) || confValue < minBootstrapConf)) {
        // V5.9.270 FIX: CRITICAL — ONLY skip ENTRY if score is too low.
        // NEVER return here if position is already OPEN — that would kill the exit path
        // and leave live positions completely unmonitored (no TP/SL/treasury exit checks).
        if (!ts.position.isOpen) {
            ErrorLogger.info("BotService", "[V3|PROMOTION_GATE] ${identity.symbol} | allow=false | " +
                "reason=edge_${edgeVerdictStr.lowercase()}_conf_${confValue.toInt()} (floor=$minBootstrapConf) → SHADOW_ONLY")
            
            // Shadow track for learning
            com.lifecyclebot.engine.ShadowLearningEngine.onFdgBlockedTrade(
                mint = ts.mint,
                symbol = ts.symbol,
                blockReason = "PROMOTION_GATE_edge_${edgeVerdictStr}_conf_$confValue",
                blockLevel = "LEGACY_GATE",
                currentPrice = ts.ref,
                proposedSizeSol = 0.1,
                quality = decision.finalQuality,
                confidence = confValue,
                phase = decision.phase,
            )
            
            return  // Exit before CANDIDATE/PROPOSED (entry only — position is not open)
        }
        // If position IS open: fall through to exit management (L7051) so SL/TP fire correctly
    }
    
    // Log when skip override is used
    if ((allowSkipForLearning || pre5000LearningOpen) && edgeVerdictStr == "SKIP") {
        val reason = when {
            pre5000LearningOpen -> "LEARNING_OPEN_PRE5000"
            hasProvenEdge -> "PROVEN_EDGE (wr=${provenWinRate.toInt()}% n=$meaningfulCount)"
            else -> "BOOTSTRAP"
        }
        ErrorLogger.info("BotService", "[V3|SKIP_OVERRIDE] ${identity.symbol} | $reason | " +
            "edge=$edgeVerdictStr conf=${confValue.toInt()} learning=${(learningProgress * 100).toInt()}% | allowing through")
    }
    
    // ───────────────────────────────────────────────────────────────────
    // HARD GATE 2: Block C-grade + low confidence
    // V5.2.12: Paper mode has LOWER floor to allow more learning
    // ───────────────────────────────────────────────────────────────────
    val isCGrade = decision.setupQuality == "C" || decision.setupQuality == "D"
    val fluidCGradeConfFloor = try {
        val learningProgress = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        // V5.9.291 FIX: Route through ModeLeniency — proven-edge live runs get same
        // leniency as paper. Previously live was hard-coded to 12->25 which made the
        // bot behave like a virgin the moment the switch was flipped to live.
        val lenient = com.lifecyclebot.engine.ModeLeniency.useLenientGates(cfg.paperMode)
        if (lenient) {
            // Paper OR proven-edge live: very low floor to maximise trading volume
            (1 + (learningProgress * 5.0)).toInt().coerceIn(1, 10)
        } else {
            // Strict-live only (no proven edge yet): moderate floor
            (5 + (learningProgress * 8.0)).toInt().coerceIn(5, 13)
        }
    } catch (_: Exception) { if (cfg.paperMode) 1 else 5 }
    
    // V5.9: If bootstrap SKIP override was used at gate 1, don't re-block at gate 2.
    // These tokens are deliberately let through for learning even with conf=0.
    if (!pre5000LearningOpen && isCGrade && confValue < fluidCGradeConfFloor && !allowSkipForLearning) {
        // V5.9.270 FIX: Same as gate 1 — ONLY block ENTRY, never kill exit path for open positions
        if (!ts.position.isOpen) {
            ErrorLogger.info("BotService", "[V3|PROMOTION_GATE] ${identity.symbol} | allow=false | " +
                "reason=C_grade_conf_${confValue.toInt()}_below_$fluidCGradeConfFloor → SHADOW_ONLY")
            
            com.lifecyclebot.engine.ShadowLearningEngine.onFdgBlockedTrade(
                mint = ts.mint,
                symbol = ts.symbol,
                blockReason = "PROMOTION_GATE_C_GRADE_CONF_FLOOR",
                blockLevel = "LEGACY_GATE",
                currentPrice = ts.ref,
                proposedSizeSol = 0.1,
                quality = decision.finalQuality,
                confidence = confValue,
                phase = decision.phase,
            )
            
            return  // Exit before CANDIDATE/PROPOSED (entry only — position is not open)
        }
        // If position IS open: fall through to exit management
    }
    
    if (!ts.position.isOpen && decision.finalSignal == "BUY" && canProposeEarly) {
        // ═══════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Mark as candidate
        // ═══════════════════════════════════════════════════════════════════
        identity.candidate(decision.entryScore, decision.phase, decision.setupQuality)
        
        // ═══════════════════════════════════════════════════════════════════
        // LIFECYCLE: CANDIDATE (strategy generated BUY signal)
        // ═══════════════════════════════════════════════════════════════════
        TradeLifecycle.candidate(
            identity.mint, 
            decision.entryScore, 
            decision.phase, 
            decision.setupQuality
        )
        // Calculate proposed size first
        val proposedSize = executor.calculateBuySize(
            ts = ts,
            walletSol = effectiveBalance,
            totalExposureSol = status.totalExposureSol,
            openPositionCount = status.openPositionCount,
            quality = decision.finalQuality,
        )
        
        // ═══════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Mark as proposed
        // ═══════════════════════════════════════════════════════════════════
        identity.proposed()
        
        // ═══════════════════════════════════════════════════════════════════
        // LIFECYCLE: PROPOSED → FDG evaluation
        // NOTE: recordProposal() moved AFTER FDG evaluation to prevent
        // FDG's canPropose() check from blocking the same proposal
        // ═══════════════════════════════════════════════════════════════════
        TradeLifecycle.proposed(identity.mint)
        
        // Get trading mode tag for FDG mode-specific thresholds
        // V5.9.409 — per-token tradingMode WINS over the global auto-mode so
        // meme sub-phases (SHITCOIN / MANIPULATED / MOONSHOT_* / CULT /
        // NARRATIVE) reach FDG with their proper multipliers. Previously
        // FDG only ever saw the global BotMode and fell back to DEFAULT for
        // every meme trade, starving all 24 symbolic channels of lane data.
        val tradingModeTag = try {
            val tokenMode = ts.position.tradingMode
            ModeSpecificGates.fromTradingMode(tokenMode)
                ?: modeConf?.mode?.let { ModeSpecificGates.fromBotMode(it) }
        } catch (e: Exception) {
            null
        }
        
        // Run through Final Decision Gate
        val fdgDecision = FinalDecisionGate.evaluate(
            ts = ts,
            candidate = decision,
            config = cfg,
            proposedSizeSol = proposedSize,
            brain = executor.brain,
            tradingModeTag = tradingModeTag,
        )
        ErrorLogger.info("BotService", "🧬 MEME_SPINE FDG ${identity.symbol} | can=${fdgDecision.canExecute()} | qual=${fdgDecision.quality} | conf=${fdgDecision.confidence.toInt()} | size=${fdgDecision.sizeSol.fmt(4)} | reason=${fdgDecision.blockReason ?: "none"}")
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 ENGINE: PRIMARY DECISION AUTHORITY
        // 
        // V3 MIGRATION: V3 is now the ONLY decision maker when enabled.
        // FDG is kept for comparison logging only.
        // 
        // Decision flow:
        //   1. V3 scores the candidate (includes all penalties)
        //   2. V3 outputs: EXECUTE_AGGRESSIVE, EXECUTE_STANDARD, EXECUTE_SMALL, WATCH, REJECT, BLOCK
        //   3. Only V3 decision matters for execution
        //   4. FDG result is logged for comparison tracking only
        // ═══════════════════════════════════════════════════════════════════
        var useV3Decision = false
        var v3SizeSol = 0.0
        var v3Thesis = ""
        var v3ControlsExecution = false  // V3 is the boss when enabled
        
        if (cfg.v3EngineEnabled && com.lifecyclebot.v3.V3EngineManager.isReady()) {
            v3ControlsExecution = !cfg.v3ShadowMode  // V3 controls execution unless shadow mode
            
            try {
                // Log legacy decision for comparison
                val legacyShouldTrade = decision.shouldTrade
                val legacyPenalty = suppressionPenalty
                
                val v3Decision = com.lifecyclebot.v3.V3EngineManager.processToken(
                    ts = ts,
                    walletSol = effectiveBalance,
                    totalExposureSol = status.totalExposureSol,
                    openPositions = status.openPositionCount,
                    recentWinRate = botBrain?.getRecentWinRate() ?: 50.0,
                    recentTradeCount = botBrain?.getTradeCount() ?: 0,
                    marketRegime = modeConf?.mode?.name ?: "NEUTRAL"
                )
                
                when (val result = v3Decision) {
                    is com.lifecyclebot.v3.V3Decision.Execute -> {
                        val fdgTag = if (fdgDecision.canExecute()) "FDG:✓" else "FDG:✗"
                        val legacyTag = if (legacyShouldTrade) "legacy:✓" else "legacy:✗"
                        
                        // V3 UNIFIED LOG: Shows score, confidence, band, size
                        ErrorLogger.info("BotService", "⚡ V3 EXECUTE: ${identity.symbol} | " +
                            "band=${result.band} | score=${result.score} | " +
                            "conf=${result.confidence.toInt()}% | size=${result.sizeSol.fmt(4)} SOL | " +
                            "$legacyTag $fdgTag")
                        
                        // Track V3 vs legacy comparison
                        com.lifecyclebot.v3.V3EngineManager.recordDecisionComparison(
                            v3Decision = "EXECUTE",
                            fdgWouldExecute = fdgDecision.canExecute()
                        )
                        
                        // V3 CONTROLS EXECUTION
                        if (v3ControlsExecution) {
                            useV3Decision = true
                            v3SizeSol = result.sizeSol
                            v3Thesis = "V3 score=${result.score} band=${result.band}"
                            addLog("⚡ V3: ${identity.symbol} | ${result.band} | " +
                                "${v3SizeSol.fmt(4)} SOL | conf=${result.confidence.toInt()}%", mint)
                        } else {
                            // Shadow mode - log only
                            addLog("🔬 V3 SHADOW: ${identity.symbol} | ${result.band} | " +
                                "${result.sizeSol.fmt(4)} SOL ($fdgTag)", mint)
                        }
                    }
                    
                    is com.lifecyclebot.v3.V3Decision.Watch -> {
                        val fdgTag = if (fdgDecision.canExecute()) "FDG:✓" else "FDG:✗"
                        
                        ErrorLogger.info("BotService", "⚡ V3 WATCH: ${identity.symbol} | " +
                            "score=${result.score} | conf=${result.confidence} | $fdgTag")
                        
                        // Track comparison
                        com.lifecyclebot.v3.V3EngineManager.recordDecisionComparison(
                            v3Decision = "WATCH",
                            fdgWouldExecute = fdgDecision.canExecute()
                        )
                        
                        // V3 WATCH = DO NOT EXECUTE (even if FDG would approve)
                        if (v3ControlsExecution) {
                            addLog("⚡ V3 WATCH: ${identity.symbol} | score=${result.score} (no trade)", mint)
                            // Don't set useV3Decision - this blocks the trade
                            useV3Decision = false  // V5.9.187: WATCH skips V3 only; ShitCoin/FDG still runs
                        }
                    }
                    
                    is com.lifecyclebot.v3.V3Decision.Rejected -> {
                        val fdgTag = if (fdgDecision.canExecute()) "FDG:✓" else "FDG:✗"

                        ErrorLogger.info("BotService", "⚡ V3 REJECT: ${identity.symbol} | " +
                            "${result.reason} | $fdgTag")
                        RejectionTelemetry.record("V3", result.reason)

                        // Track comparison
                        com.lifecyclebot.v3.V3EngineManager.recordDecisionComparison(
                            v3Decision = "REJECT",
                            fdgWouldExecute = fdgDecision.canExecute()
                        )

                        // V3 REJECT = DO NOT EXECUTE
                        if (v3ControlsExecution) {
                            addLog("⚡ V3 REJECT: ${identity.symbol} | ${result.reason}", mint)
                            useV3Decision = false  // V5.9.187: REJECT skips V3 only; routing-type rejects let ShitCoin run
                        }

                        // ═════════════════════════════════════════════════════
                        // V5.9.346 — MEME UNIFIED SCORER BRIDGE (paper-only fallback)
                        // The alts trader's 79% WR architecture: TA pre-filter
                        // + synthetic floors + 60/40 blend bypassing FDG. When
                        // V3 rejects in paper mode, the bridge gets a second
                        // look. If it says shouldEnter we override V3 with
                        // a small position. Live mode still defers to V3.
                        // ═════════════════════════════════════════════════════
                        val bridgeAllowed = !useV3Decision && (cfg.paperMode || pre5000LearningOpen || hasProvenEdge)
                        if (bridgeAllowed) {
                            try {
                                val verdict = com.lifecyclebot.v3.MemeUnifiedScorerBridge.scoreForEntry(ts)
                                if (verdict.shouldEnter) {
                                    // Tiny bridge size — bridge entries are
                                    // bootstrap learning trades; the meme
                                    // trader's adaptive sizing kicks in once
                                    // the layer-accuracy data accumulates.
                                    // V5.9.642: available in paper AND
                                    // pre-5000/proven-edge live so V3_REJECT
                                    // cannot totally starve the learning
                                    // firehose. Final TradeAuthorizer +
                                    // Executor still enforce wallet/live safety.
                                    val bridgeSize = if (cfg.paperMode) 0.05 else 0.01
                                    useV3Decision = true
                                    v3SizeSol = bridgeSize
                                    v3Thesis  = "MemeBridge tech=${verdict.techScore} v3=${verdict.v3Score} blend=${verdict.blendedScore} mult=${"%.2f".format(verdict.trustMultiplier)} mode=${if (cfg.paperMode) "paper" else "live-learning"}"
                                    ErrorLogger.info("BotService", "🌉 BRIDGE OVERRIDE on V3-REJECT: ${identity.symbol} | $v3Thesis")
                                    addLog("🌉 Bridge BUY: ${identity.symbol} | tech=${verdict.techScore} blend=${verdict.blendedScore} | ${bridgeSize} SOL", mint)
                                } else {
                                    ErrorLogger.debug("BotService", "🌉 Bridge declined ${identity.symbol}: ${verdict.rejectReason}")
                                }
                            } catch (be: Exception) {
                                ErrorLogger.debug("BotService", "🌉 Bridge error on ${identity.symbol}: ${be.message}")
                            }
                        }
                    }
                    
                    is com.lifecyclebot.v3.V3Decision.Blocked -> {
                        val fdgTag = if (fdgDecision.canExecute()) "FDG:✓" else "FDG:✗"
                        
                        ErrorLogger.info("BotService", "⚡ V3 BLOCK (FATAL): ${identity.symbol} | " +
                            "${result.reason} | $fdgTag")
                        
                        // Track comparison
                        com.lifecyclebot.v3.V3EngineManager.recordDecisionComparison(
                            v3Decision = "BLOCK",
                            fdgWouldExecute = fdgDecision.canExecute()
                        )
                        
                        // V3 BLOCK = FATAL, DO NOT EXECUTE
                        if (v3ControlsExecution) {
                            addLog("⚡ V3 BLOCKED: ${identity.symbol} | ${result.reason}", mint)
                            return  // V3 says BLOCK = exit
                        }
                    }
                    
                    else -> {
                        // Error or NotReady - fall back to FDG only if V3 is not controlling
                        ErrorLogger.warn("BotService", "⚡ V3 unavailable for ${identity.symbol} - ${if (v3ControlsExecution) "SKIPPING" else "using FDG"}")
                        if (v3ControlsExecution) {
                            // V3 is supposed to control but failed - don't trade on uncertainty
                            return
                        }
                    }
                }
                
            } catch (v3e: Exception) {
                ErrorLogger.error("BotService", "V3 engine error for ${identity.symbol}: ${v3e.message}")
                if (v3ControlsExecution) {
                    // V3 controls but errored - don't fall back to legacy
                    return
                }
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V5.0: TRADE AUTHORIZER - Check BEFORE any execution
        // This is the unified gate that prevents post-execution gating drift
        // ═══════════════════════════════════════════════════════════════════
        val authResult = TradeAuthorizer.authorize(
            mint = mint,
            symbol = identity.symbol,
            score = ts.lastV3Score ?: 0,
            confidence = fdgDecision.confidence,
            quality = fdgDecision.quality,
            isPaperMode = cfg.paperMode,
            requestedBook = TradeAuthorizer.ExecutionBook.CORE,
            rugcheckScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
            liquidity = ts.lastLiquidityUsd,
            isBanned = BannedTokens.isBanned(mint),
        )
        
        ErrorLogger.info("BotService", "🧬 MEME_SPINE AUTH ${identity.symbol} | verdict=${authResult.verdict} | reason=${authResult.reason} | paper=${cfg.paperMode} | liq=${ts.lastLiquidityUsd.toInt()}")

        // If TradeAuthorizer says SHADOW_ONLY, track but don't execute
        if (authResult.isShadowOnly()) {
            ErrorLogger.info("BotService", "[V3|TRADE_AUTH] ${identity.symbol} | SHADOW_ONLY | ${authResult.reason}")
            // Track as shadow avoid for learning
            com.lifecyclebot.v3.learning.ShadowLearningEngine.recordShadowAvoid(
                mint = mint,
                symbol = identity.symbol,
                price = ts.ref,
                aiConfidence = fdgDecision.confidence.toInt(),
                setupQuality = fdgDecision.quality,
                regime = botBrain?.currentRegime ?: "UNKNOWN",
                mode = if (cfg.paperMode) "PAPER" else "LIVE",
                blockReason = "TRADE_AUTH_${authResult.reason}"
            )
            return // Skip execution entirely
        }
        
        // If TradeAuthorizer says REJECT, skip entirely
        if (!authResult.isExecutable()) {
            ErrorLogger.debug("BotService", "[V3|TRADE_AUTH] ${identity.symbol} | REJECTED | ${authResult.reason}")
            return // Skip execution entirely
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // EXECUTION PATH: Use V3 decision if active, otherwise FDG
        // ═══════════════════════════════════════════════════════════════════
        val shouldExecute = useV3Decision || fdgDecision.canExecute()
        
        if (shouldExecute) {
            // ═══════════════════════════════════════════════════════════════════
            // RECORD PROPOSAL: Track that we proposed (for dedupe)
            // Moved here from before FDG to prevent self-blocking
            // ═══════════════════════════════════════════════════════════════════
            TradeLifecycle.recordProposal(identity.mint)
            
            // ═══════════════════════════════════════════════════════════════════
            // COMPUTE FINAL SIZE: Use V3 size if available, otherwise FDG size
            // ═══════════════════════════════════════════════════════════════════
            var finalSize = if (useV3Decision && v3SizeSol > 0) {
                v3SizeSol
            } else {
                fdgDecision.sizeSol
            }
            
            // Apply mode multiplier if present (only for FDG path)
            if (!useV3Decision) {
                modeConf?.let { finalSize *= it.positionSizeMultiplier }
            }
            
            // Apply graduated building reduction for B+ setups (only for FDG path)
            val isGraduated = !useV3Decision && decision.setupQuality in listOf("A+", "B")
            val actualInitialSize = if (isGraduated) {
                executor.graduatedInitialSize(finalSize, decision.setupQuality)
            } else {
                finalSize
            }
            
            // Determine approval class and confidence
            val approvalClass = if (useV3Decision) {
                FinalDecisionGate.ApprovalClass.LIVE  // V3 decisions are always "live"
            } else {
                fdgDecision.approvalClass
            }
            
            val quality = if (useV3Decision) "V3" else fdgDecision.quality
            val confidence = if (useV3Decision) 85.0 else fdgDecision.confidence
            
            // ═══════════════════════════════════════════════════════════════════
            // TRADE IDENTITY: Mark as approved with ACTUAL initial size
            // ═══════════════════════════════════════════════════════════════════
            identity.approved(actualInitialSize, quality, confidence)
            
            // ═══════════════════════════════════════════════════════════════════
            // LIFECYCLE: APPROVED → SIZED
            // ═══════════════════════════════════════════════════════════════════
            TradeLifecycle.fdgApproved(
                identity.mint, 
                quality, 
                confidence,
                approvalClass.name
            )
            TradeLifecycle.recordApproval(identity.mint)  // Track for dedupe
            TradeLifecycle.sized(identity.mint, actualInitialSize, "medium")
            
            // Log approval (V3 or FDG)
            if (useV3Decision) {
                addLog("⚡ V3 APPROVED: ${identity.symbol} | size=${actualInitialSize.fmt(4)} SOL | thesis: $v3Thesis", mint)
                ErrorLogger.info("BotService", "⚡ V3 APPROVED: ${identity.symbol} | " +
                    "size=${actualInitialSize.fmt(4)} SOL")
            } else {
                FinalDecisionGate.logApprovedTrade(fdgDecision) { addLog(it, mint) }
                ErrorLogger.info("BotService", "${if(fdgDecision.isBenchmarkQuality()) "🟢" else "🟡"} " +
                    "FDG ${fdgDecision.approvalClass}: ${identity.symbol} | " +
                    "quality=${fdgDecision.quality} | conf=${fdgDecision.confidence.toInt()}% | " +
                    "size=${actualInitialSize.fmt(4)} SOL" +
                    if (isGraduated) " (grad: target=${finalSize.fmt(4)})" else "")
            }
            
            // V5.9.173 — paper mode bypasses the pause guard. Learning
            // must never stop in paper. Live stays gated for safety.
            val pauseBlocks = !cfg.paperMode && cbState.isPaused
            if (!cbState.isHalted && !pauseBlocks) {
                ErrorLogger.info("BotService", "🧬 MEME_SPINE EXECUTOR_ROUTE ${identity.symbol} | paper=${cfg.paperMode} | v3=$useV3Decision | size=${actualInitialSize.fmt(4)} | wallet=${effectiveBalance.fmt(4)} | auto=${cfg.autoTrade}")
                executor.maybeActWithDecision(
                    ts                 = ts,
                    decision           = decision,
                    walletSol          = effectiveBalance,
                    wallet             = wallet,
                    lastPollMs         = lastSuccessfulPollMs,
                    openPositionCount  = status.openPositionCount,
                    totalExposureSol   = status.totalExposureSol,
                    modeConfig         = null,  // Don't pass mode config - already applied above
                    fdgApprovedSize    = actualInitialSize,  // Use final computed size
                    walletTotalTrades  = try {
                        com.lifecyclebot.engine.BotService.walletManager
                            ?.state?.value?.totalTrades ?: 0
                    } catch (_: Exception) { 0 },
                    tradeIdentity      = identity,  // Pass canonical identity
                    fdgApprovalClass   = approvalClass,  // Pass approval class for learning
                )
                
                // Record V3 position opened
                if (useV3Decision) {
                    com.lifecyclebot.v3.V3EngineManager.setCooldown(identity.mint, 60_000L)
                }
            }
        } else {
            // ═══════════════════════════════════════════════════════════════════
            // RECORD PROPOSAL: Track that we proposed (for dedupe), even if blocked
            // This prevents spam re-proposals of the same token
            // ═══════════════════════════════════════════════════════════════════
            TradeLifecycle.recordProposal(identity.mint)
            
            // ═══════════════════════════════════════════════════════════════════
            // TRADE IDENTITY: Mark as blocked
            // ═══════════════════════════════════════════════════════════════════
            identity.blocked(
                fdgDecision.blockReason ?: "UNKNOWN",
                fdgDecision.blockLevel?.name ?: "UNKNOWN",
                fdgDecision.quality,
                fdgDecision.confidence
            )
            
            // ═══════════════════════════════════════════════════════════════════
            // LIFECYCLE: FDG_BLOCKED (using identity for consistency)
            // ═══════════════════════════════════════════════════════════════════
            TradeLifecycle.fdgBlocked(
                identity.mint, 
                fdgDecision.blockReason ?: "UNKNOWN",
                fdgDecision.blockLevel?.name ?: "UNKNOWN"
            )
            
            FinalDecisionGate.logBlockedTrade(fdgDecision) { addLog(it, mint) }
            
            ErrorLogger.info("BotService", "🚫 FDG BLOCKED: ${identity.symbol} | " +
                "reason=${fdgDecision.blockReason} | level=${fdgDecision.blockLevel}")
            RejectionTelemetry.record("FDG", fdgDecision.blockReason ?: "UNKNOWN")
            
            // Record this for learning (simulation only, no execution)
            executor.brain?.recordBlockedTrade(
                mint = identity.mint,
                phase = identity.phase,
                source = identity.source,
                blockReason = fdgDecision.blockReason ?: "UNKNOWN",
                quality = fdgDecision.quality,
                confidence = fdgDecision.confidence,
            )
            
            // ═══════════════════════════════════════════════════════════════════
            // PAPER MODE LEARNING: Shadow track blocked trades
            // Track what WOULD have happened if we traded this opportunity
            // This enables learning whether the FDG is too strict or appropriate
            // ═══════════════════════════════════════════════════════════════════
            if (cfg.paperMode) {
                ShadowLearningEngine.onFdgBlockedTrade(
                    mint = identity.mint,
                    symbol = identity.symbol,
                    blockReason = fdgDecision.blockReason ?: "UNKNOWN",
                    blockLevel = fdgDecision.blockLevel?.name ?: "UNKNOWN",
                    currentPrice = ts.ref,
                    proposedSizeSol = proposedSize,
                    quality = fdgDecision.quality,
                    confidence = fdgDecision.confidence,
                    phase = identity.phase,
                )
            }
        }
    } else if (ts.position.isOpen || (ts.position.qtyToken > 0.0 && ts.position.pendingVerify)) {
        // ═══════════════════════════════════════════════════════════════════
        // V5.9.290 FIX: CRITICAL — exit management fires for ANY position
        // that has tokens (qtyToken > 0), regardless of pendingVerify state.
        //
        // BEFORE this fix: isOpen = qtyToken > 0 && !pendingVerify.
        // If the verify coroutine stalled (RPC lag, indexing lag), pendingVerify
        // stayed true indefinitely — the position was permanently invisible to
        // ALL exit checks (TP, SL, rug detection, treasury exits, everything).
        // The bot bought but could NEVER sell. This was the root cause of
        // live sells never executing.
        //
        // Now: we enter exit management if tokens exist at all. We then let
        // each layer's checkExit() and the rug safety net run as normal.
        //
        // SAFETY: if pendingVerify is STILL true but < 120s old (verify
        // coroutine is still running), we skip executing the actual sell to
        // avoid racing with the verify. After 120s we force-clear
        // pendingVerify so the position becomes fully managed.
        // ═══════════════════════════════════════════════════════════════════
        if (ts.position.pendingVerify) {
            val pendingAgeMs = System.currentTimeMillis() - ts.position.entryTime
            if (pendingAgeMs < 120_000L) {
                // Verify coroutine still has time — skip this tick but log
                ErrorLogger.debug("BotService",
                    "⏳ [PENDING_VERIFY] ${ts.symbol} | ${pendingAgeMs / 1000}s old — verify window active, skip exit tick")
                return
            }
            // 120s elapsed — verify coroutine is definitely done (it runs for ≤30s).
            // Force-clear pendingVerify so isOpen becomes true and exits work.
            ErrorLogger.warn("BotService",
                "⚠️ [PENDING_VERIFY_STUCK] ${ts.symbol} | ${pendingAgeMs / 1000}s — force-clearing pendingVerify. " +
                "Verify coroutine should have resolved within 30s. Position now actively managed.")
            synchronized(ts) {
                ts.position = ts.position.copy(pendingVerify = false)
            }
            try { com.lifecyclebot.engine.PositionPersistence.savePosition(ts) } catch (_: Exception) {}
        }
        // Position management (exits) - ALWAYS monitor open positions
        // Even when paused, we need to manage risk on existing positions

        // V5.2.12: Debug logging for exit check flow
        ErrorLogger.debug("BotService", "🔄 [EXIT CHECK] ${ts.symbol} | isOpen=true | entering exit management")

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.264 — RUG SAFETY NET (ALWAYS-ON)
        // V5.9.302 — DEAD-FEED RUG DETECTION FIX
        //
        // The 41 AI layers are useless if the price feed returns 0 (token
        // rugged) and the per-trader exit-check code masks that 0 by
        // falling back to the entry price. With pnlPct silently calculated
        // as 0%, no SL / RUG_DETECTED ever fires — the position sits at
        // -100% on the UI while the evaluator thinks "still flat, hold".
        //
        // V5.9.302 root cause: the prior safety net relied on `bestPrice`
        // (which falls back to histPrice). If histPrice was a stale entry
        // price, `bestPrice < entryPrice * 0.005` was FALSE → safety net
        // never fired → positions sat at -100% indefinitely (Trump 16m,
        // NUKX 13m in user-reported screenshot).
        //
        // NEW POLICY:
        //   - Trigger on rawPrice <= 0 AND age >= 30s (was 60s) — a dead
        //     price feed for 30+ seconds IS a rug; do not wait for histPrice.
        //   - ALSO trigger on bestPrice < 0.5% of entry (existing path).
        //   - In PAPER mode, cap the recorded loss at -25% so learning is
        //     not poisoned by -100% outliers (this is a sim, not real money).
        // ═══════════════════════════════════════════════════════════════════
        try {
            val pos = ts.position
            val entryAgeMs = System.currentTimeMillis() - (pos.entryTime.takeIf { it > 0 } ?: System.currentTimeMillis())
            val rawPrice = ts.lastPrice
            val histPrice = ts.history.lastOrNull()?.priceUsd ?: 0.0
            val bestPrice = if (rawPrice > 0.0) rawPrice else histPrice

            // V5.9.302: TWO independent rug triggers
            // (a) Dead price feed: rawPrice == 0 for 30+ seconds → rug
            // (b) Crashed price: bestPrice < 0.5% of entry → rug
            val deadFeedRug = entryAgeMs >= 30_000L &&
                    pos.entryPrice > 0.0 &&
                    rawPrice <= 0.0
            val crashedRug = entryAgeMs >= 30_000L &&
                    pos.entryPrice > 0.0 &&
                    bestPrice in 0.000001..(pos.entryPrice * 0.005)
            val rugDetected = deadFeedRug || crashedRug

            if (rugDetected) {
                // V5.9.302: PAPER-MODE LOSS CAP — avoid poisoning learning with -100% outliers.
                // In paper mode, force the recorded exit price to entry × 0.75 (= -25% loss)
                // so the bot learns from a realistic worst-case rug rather than catastrophic noise.
                val isPaper = try { ConfigStore.load(applicationContext).paperMode } catch (_: Exception) { true }
                val effectiveExitPrice = if (isPaper) {
                    pos.entryPrice * 0.75  // -25% paper rug cap
                } else {
                    bestPrice.coerceAtLeast(pos.entryPrice * 0.001)  // live: tiny floor to avoid div-by-zero
                }
                val triggerKind = if (deadFeedRug) "DEAD_FEED" else "CRASH"
                ErrorLogger.warn(
                    "BotService",
                    "🚨 RUG SAFETY NET ($triggerKind): ${ts.symbol} mint=${ts.mint.take(8)} | " +
                    "entry=${pos.entryPrice} lastPrice=$rawPrice histPrice=$histPrice age=${entryAgeMs / 1000}s | " +
                    "${if (isPaper) "PAPER cap @ -25%" else "LIVE bestPrice=$bestPrice"} — FORCE SELL"
                )
                addLog("🚨 RUG SAFETY ($triggerKind): ${ts.symbol} ${if (isPaper) "(paper -25% cap)" else "price≈0"} — forcing exit", ts.mint)
                // Push the capped price into ts so downstream close-recorders use it
                if (isPaper) {
                    try { ts.lastPrice = effectiveExitPrice } catch (_: Exception) {}
                }
                try {
                    executor.requestSell(
                        ts = ts,
                        reason = "RUG_SAFETY_NET",
                        wallet = wallet,
                        walletSol = effectiveBalance,
                    )
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "RUG_SAFETY_NET sell error: ${e.message}", e)
                }
                // Also wipe layer-store position trackers immediately so the
                // UI stops displaying the rugged position on next refresh.
                try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, effectiveExitPrice,
                        com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.RUG_DETECTED) } catch (_: Exception) {}
                try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(ts.mint, effectiveExitPrice,
                        com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.RUG_DETECTED) } catch (_: Exception) {}
                return
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "RugSafetyNet check failed: ${e.message}", e)
        }

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.449 — REMOVED V5.9.429 UNIVERSAL_HARD_FLOOR_SL.
        //
        // The pre-dispatch -20% force-close was firing earlier (and stricter)
        // than each sub-trader's own HARD_FLOOR check, which build-1941 era
        // intentionally ran AFTER partial-sell / profit-lock / trailing logic
        // so wicks could recover. Removing the universal block restores the
        // sub-trader-owned exit-priority chain. The RUG_SAFETY_NET above
        // still catches ≤-99.5% catastrophic rugs, and each sub-trader's
        // HARD_FLOOR_STOP_PCT (now back at -20 in ShitCoin via V5.9.449)
        // catches the orphaned-position cases this block was meant to
        // backstop — without killing winners that wick.
        // ═══════════════════════════════════════════════════════════════════

        // ═══════════════════════════════════════════════════════════════════
        // LAYER TRANSITION CHECK - Upgrade positions on the way UP
        // Check if position should transition to a higher layer
        // ═══════════════════════════════════════════════════════════════════
        try {
            val currentPrice = resolveLivePrice(ts)
            
            val transition = com.lifecyclebot.v3.scoring.LayerTransitionManager.checkTransition(
                mint = ts.mint,
                currentMcap = ts.lastMcap,
                currentPrice = currentPrice,
            )
            
            if (transition.shouldTransition) {
                // Update position's trading mode to new layer
                ts.position.tradingMode = transition.toLayer.name  // V5.9.217: use enum.name not displayName — prevents BLUECHIP/BLUE_CHIP split
                ts.position.tradingModeEmoji = transition.toLayer.emoji
                
                // Update targets for new layer
                val (newTP, newSL) = com.lifecyclebot.v3.scoring.LayerTransitionManager.getAdjustedTargets(
                    ts.mint, transition.newTakeProfit, transition.newStopLoss
                )
                
                // Update position flags based on new layer
                when (transition.toLayer) {
                    com.lifecyclebot.v3.scoring.LayerTransitionManager.TradingLayer.BLUE_CHIP -> {
                        ts.position.isBlueChipPosition = true
                        ts.position.isShitCoinPosition = false
                        ts.position.blueChipTakeProfit = newTP
                        ts.position.blueChipStopLoss = newSL
                    }
                    com.lifecyclebot.v3.scoring.LayerTransitionManager.TradingLayer.V3_QUALITY -> {
                        ts.position.isShitCoinPosition = false
                    }
                    else -> {}
                }
                
                addLog("🚀 LAYER UP: ${ts.symbol} | " +
                    "${transition.fromLayer.emoji} → ${transition.toLayer.emoji} | " +
                    "mcap \$${(ts.lastMcap/1000).toInt()}K", ts.mint)
                
                // V5.9.208: REMOVED false FluidLearning win on layer transitions.
                // Layer-up is NOT a trade exit — recording isWin=true here inflated maturity
                // win rate even when the position later closed at a loss.
                // FluidLearning win/loss is recorded at actual exit in Executor.
            }
        } catch (transEx: Exception) {
            ErrorLogger.debug("BotService", "Layer transition check failed: ${transEx.message}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // TREASURY MODE EXIT CHECK - Quick scalps with tight exits
        // Check FIRST before other exit logic since treasury has strict rules
        // ═══════════════════════════════════════════════════════════════════
        // V5.9.151 — the flag-based gate (`isTreasuryPosition` /
        // `tradingMode=="TREASURY"`) was missing positions where the flag
        // was never set at buy-confirm OR was cleared by a later layer
        // transition. Result: Treasury's activePositions still owned the
        // mint, but processTokenCycle never called checkExit — KAIRU
        // observed at +52.7% for 20+ min against a +4% TP that
        // checkExit would have fired immediately. Source of truth is
        // CashGenerationAI.activePositions; the flag is now only a
        // supplementary hint.
        val treasuryOwns = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(ts.mint) != null
        if (ts.position.isTreasuryPosition || ts.position.tradingMode == "TREASURY" || treasuryOwns) {
            // V5.2.12: Debug - entering Treasury exit check
            ErrorLogger.debug("BotService", "💰 [TREASURY ENTER] ${ts.symbol} | isTreasury=${ts.position.isTreasuryPosition} | mode=${ts.position.tradingMode}")
            
            val currentPrice = resolveLivePrice(ts)
            
            // V5.2.12: Debug - show price being used
            ErrorLogger.debug("BotService", "💰 [TREASURY PRICE] ${ts.symbol} | " +
                "lastPrice=${ts.lastPrice} | historyLast=${ts.history.lastOrNull()?.priceUsd} | " +
                "entryPrice=${ts.position.entryPrice} | USING=$currentPrice")
            
            // V5.2: Debug - verify checkExit is being called
            var treasuryPos = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(ts.mint)
            if (treasuryPos == null && ts.position.isOpen) {
                // V5.5 RECOVERY: CashGenerationAI's in-memory map is empty after restart.
                // Re-register the position from persisted ts.position data so checkExit works.
                val recTpPct = if (ts.position.treasuryTakeProfit > 0) ts.position.treasuryTakeProfit else 4.0
                val recSlPct = if (ts.position.treasuryStopLoss < 0) ts.position.treasuryStopLoss else -4.0
                // V5.9.200: Use raw treasuryEntryPrice if saved — not slippage-affected entryPrice
                val recEntryPrice = if (ts.position.treasuryEntryPrice > 0) ts.position.treasuryEntryPrice
                                   else ts.position.entryPrice
                com.lifecyclebot.v3.scoring.CashGenerationAI.openPosition(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    entryPrice = recEntryPrice,
                    positionSol = ts.position.costSol,
                    takeProfitPct = recTpPct,
                    stopLossPct = recSlPct
                )
                treasuryPos = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(ts.mint)
                ErrorLogger.warn("BotService",
                    "💰 [TREASURY RECOVERY] ${ts.symbol} | Re-registered in CashGenerationAI | " +
                    "entry=${ts.position.entryPrice} tp=$recTpPct% sl=$recSlPct%")
                // V5.9.495z39 P1 — operator spec item 5: re-registered
                // treasury positions MUST NOT trigger an immediate TP sell
                // until chain basis is loaded AND a live profitable quote is
                // proven. Lock applies only to recoveries with no
                // original-buy SOL basis (costSol <= 0). Persisted
                // positions with valid costSol skip the lock — their
                // basis is already known.
                if (ts.position.costSol <= 0.0) {
                    try {
                        com.lifecyclebot.engine.sell.RecoveryLockTracker.lock(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            reason = "TREASURY_RECOVERY_NO_BASIS",
                        )
                    } catch (_: Throwable) { /* fail-soft */ }
                }
            }

            // V5.9.495z41 P1 — UNLOCK side of the recovery lock.
            // Operator: "Wire tryUnlockWithChainBasis() invocation at the
            // sell-evaluation tick — without an unlock caller, locked
            // treasury positions stay locked forever."
            // Rate-limited (30s/mint) and runs the chain work on IO so
            // this call is safe to fire every tick. RecoveryLockUnlocker
            // short-circuits when the mint isn't locked.
            try {
                val cfgSnap = ConfigStore.load(applicationContext)
                com.lifecyclebot.engine.sell.RecoveryLockUnlocker.maybeAttemptUnlock(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    wallet = walletManager.getWallet(),
                    jupiterApiKey = cfgSnap.jupiterApiKey,
                )
            } catch (_: Throwable) { /* never break the treasury tick */ }
            
            // V5.2: Calculate current P&L for potential Moonshot promotion
            val currentPnlPct = if (ts.position.entryPrice > 0) {
                ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
            } else 0.0
            
            // V5.2: Debug - log the PnL being calculated
            if (treasuryPos != null) {
                val treasuryPnl = (currentPrice - treasuryPos.entryPrice) / treasuryPos.entryPrice * 100
                ErrorLogger.debug("BotService", "💰 [TREASURY CHECK] ${ts.symbol} | " +
                    "price=$currentPrice | treasuryEntry=${treasuryPos.entryPrice} | pnl=${treasuryPnl.fmt(1)}%")
            }
            
            // V5.2.12: Check for cross-trade promotion to Moonshot (200%+ gains)
            // Moonshot accepts promotions from any mcap range ($10K-$100M)
            if (currentPnlPct >= 200.0 && ts.lastMcap in 10_000.0..100_000_000.0) {
                val shouldPromote = com.lifecyclebot.v3.scoring.MoonshotTraderAI.shouldPromoteToMoonshot(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    fromLayer = "TREASURY",
                    currentPnlPct = currentPnlPct,
                    currentPrice = currentPrice,
                    marketCapUsd = ts.lastMcap,
                )
                
                if (shouldPromote) {
                    ErrorLogger.info("BotService", "🚀💰 [PROMOTION] ${ts.symbol} | TREASURY → MOONSHOT | " +
                        "+${currentPnlPct.toInt()}% | Let it RIDE!")
                    
                    // Execute the promotion (close Treasury position, open Moonshot)
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.executePromotion(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        fromLayer = "TREASURY",
                        entryPrice = currentPrice,  // New entry = current price
                        positionSol = ts.position.costSol,
                        currentPnlPct = currentPnlPct,
                        marketCapUsd = ts.lastMcap,
                        liquidityUsd = ts.lastLiquidityUsd,
                        isPaper = cfg.paperMode,
                    )
                    
                    // Close Treasury tracking (position stays open under Moonshot)
                    com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                        ts.mint, currentPrice, com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT
                    )
                    
                    // Update position mode
                    ts.position.isTreasuryPosition = false
                    ts.position.tradingMode = "MOONSHOT_LUNAR"
                    ts.position.tradingModeEmoji = "🚀"
                    
                    addLog("🚀💰 PROMOTED TO MOONSHOT: ${ts.symbol} | +${currentPnlPct.toInt()}% from Treasury | " +
                        "Now riding for 10x-1000x!", ts.mint)
                    
                    return  // Promotion processed, don't exit
                }
            }
            
            val exitSignal = com.lifecyclebot.v3.scoring.CashGenerationAI.checkExit(ts.mint, currentPrice)
            
            if (exitSignal != com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.HOLD) {
                ErrorLogger.info("BotService", "💰 [TREASURY EXIT] ${ts.symbol} | " +
                    "signal=$exitSignal | price=$currentPrice")
                
                // V5.6.7 FIX: ALWAYS SELL ON EXIT - Return capital + profit share to wallet
                // User requested: "50% to wallet, 50% to treasury" - this requires SELLING
                // Old behavior: promoted without selling, locking capital forever
                // New behavior: SELL first, return capital to wallet, then re-enter if qualified
                
                val pnlPct = if (ts.position.entryPrice > 0) {
                    ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
                } else 0.0
                
                // V5.6.9g: Execute sell and only close strategy if confirmed
                val sellResult = executor.requestSell(
                    ts = ts,
                    reason = "TREASURY_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                if (sellResult == Executor.SellResult.CONFIRMED || 
                    sellResult == Executor.SellResult.PAPER_CONFIRMED) {
                    // Close treasury position tracking ONLY on confirmed sell
                    com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                        ts.mint, currentPrice, exitSignal
                    )
                    recentlyClosedMs[ts.mint] = System.currentTimeMillis()
                    
                    // V5.6.8 FIX: Release exposure slot so new tokens can enter
                    com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)

                    addLog("💰 TREASURY SELL: ${ts.symbol} | ${exitSignal.name} | +${pnlPct.toInt()}% | " +
                        "${if (cfg.paperMode) "PAPER" else "LIVE"} | Capital returned to wallet! | result=$sellResult", ts.mint)
                    
                    // V5.6.7: After selling, mark token for potential re-entry by other layers
                    // Other layers can pick it up on next scan if it still qualifies
                    if (exitSignal == com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT) {
                        ErrorLogger.info("BotService", "💰 [TREASURY SOLD] ${ts.symbol} | " +
                            "+${pnlPct.toInt()}% | Capital returned | Token available for other layers to re-enter")
                    }
                } else {
                    addLog("⚠️ TREASURY SELL PENDING: ${ts.symbol} | ${exitSignal.name} | result=$sellResult", ts.mint)
                }
                
                return  // Exit processed
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SHITCOIN MODE EXIT CHECK - Degen scalps with ultra-fast exits
        // Check SECOND after treasury since shitcoins need fast reactions
        // ═══════════════════════════════════════════════════════════════════
        if (ts.position.isShitCoinPosition || ts.position.tradingMode == "SHITCOIN") {
            val currentPrice = resolveLivePrice(ts)
            
            // V5.2: Calculate current P&L for potential Moonshot promotion
            val currentPnlPct = if (ts.position.entryPrice > 0) {
                ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
            } else 0.0
            
            // V5.2.12: Check for cross-trade promotion to Moonshot (200%+ gains)
            // ShitCoin → Moonshot: The degen play turned into a moonshot!
            if (currentPnlPct >= 200.0 && ts.lastMcap in 10_000.0..100_000_000.0) {
                val shouldPromote = com.lifecyclebot.v3.scoring.MoonshotTraderAI.shouldPromoteToMoonshot(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    fromLayer = "SHITCOIN",
                    currentPnlPct = currentPnlPct,
                    currentPrice = currentPrice,
                    marketCapUsd = ts.lastMcap,
                )
                
                if (shouldPromote) {
                    ErrorLogger.info("BotService", "🚀💩 [PROMOTION] ${ts.symbol} | SHITCOIN → MOONSHOT | " +
                        "+${currentPnlPct.toInt()}% | DEGEN WIN → MOONSHOT!")
                    
                    // Execute the promotion (close ShitCoin position, open Moonshot)
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.executePromotion(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        fromLayer = "SHITCOIN",
                        entryPrice = currentPrice,  // New entry = current price
                        positionSol = ts.position.costSol,
                        currentPnlPct = currentPnlPct,
                        marketCapUsd = ts.lastMcap,
                        liquidityUsd = ts.lastLiquidityUsd,
                        isPaper = cfg.paperMode,
                    )
                    
                    // Close ShitCoin tracking (position stays open under Moonshot)
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(
                        ts.mint, currentPrice, com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.TAKE_PROFIT
                    )
                    
                    // Update position mode
                    ts.position.isShitCoinPosition = false
                    ts.position.tradingMode = "MOONSHOT_ORBITAL"
                    ts.position.tradingModeEmoji = "🛸"
                    
                    addLog("🛸💩 DEGEN → MOONSHOT: ${ts.symbol} | +${currentPnlPct.toInt()}% ShitCoin win → " +
                        "Now hunting 10x-1000x!", ts.mint)
                    
                    return  // Promotion processed, don't exit
                }
            }
            
            // V5.9.192: RESTART RECOVERY — same pattern as Treasury (L6997).
            // After bot restart, ShitCoinTraderAI.activePositions is empty in-memory.
            // checkExit() returns HOLD forever → positions sit idle indefinitely.
            // Re-register from persisted ts.position data so exits work correctly.
            // V5.9.293: Mirror the V5.9.270 fix — isOpen is false while pendingVerify=true
            // (within first 120s). Without this, live buys mid-restart never re-register.
            val scHasRealPosition = ts.position.isOpen ||
                (ts.position.qtyToken > 0.0 && ts.position.pendingVerify)
            if (!com.lifecyclebot.v3.scoring.ShitCoinTraderAI.hasPosition(ts.mint) && scHasRealPosition) {
                val recTp = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidTakeProfit()
                val recSl = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidStopLoss()
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.addPosition(
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinPosition(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        entryPrice = ts.position.entryPrice,
                        entrySol = ts.position.costSol,
                        entryTime = System.currentTimeMillis(),  // V5.9.192b: safe fallback (no holdTime field)
                        marketCapUsd = ts.lastMcap,
                        liquidityUsd = ts.lastLiquidityUsd,
                        isPaper = cfg.paperMode,
                        takeProfitPct = recTp,
                        stopLossPct = recSl,
                        launchPlatform = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.LaunchPlatform.PUMP_FUN,
                    )
                )
                ErrorLogger.warn("BotService",
                    "💩 [SHITCOIN RECOVERY] ${ts.symbol} | Re-registered in ShitCoinTraderAI | " +
                    "entry=${ts.position.entryPrice} tp=${recTp.toInt()}% sl=${recSl.toInt()}%")
            }
            val exitSignal = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.checkExit(ts.mint, currentPrice)
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "ShitCoin:${exitSignal.name}") } catch (_: Exception) {}

            if (exitSignal != com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.RUG_DETECTED -> "💀"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.DEV_SELL -> "🚨"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.TAKE_PROFIT -> "🎯"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.PARTIAL_TAKE -> "💰"
                    else -> "📉"
                }

                ErrorLogger.info("BotService", "💩 [SHITCOIN EXIT] ${ts.symbol} | " +
                    "signal=$exitSignal | price=$currentPrice")

                if (exitSignal == com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.PARTIAL_TAKE) {
                    // Sell 25%, let 75% ride
                    executor.requestPartialSell(
                        ts = ts,
                        sellPercentage = 0.25,
                        reason = "SHITCOIN_PARTIAL_TAKE_25PCT",
                        wallet = wallet,
                        walletBalance = effectiveBalance,
                    )
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.markFirstTakeDone(ts.mint)
                    addLog("💰 SHITCOIN PARTIAL: ${ts.symbol} | sold 25%, riding 75%", ts.mint)
                    return
                }

                // Full exit for all other signals
                // V5.6.9g: Only close strategy position if sell was confirmed
                val sellResult = executor.requestSell(
                    ts = ts,
                    reason = "SHITCOIN_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )

                // Only clear strategy state if sell was successful
                if (sellResult == Executor.SellResult.CONFIRMED || 
                    sellResult == Executor.SellResult.PAPER_CONFIRMED) {
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(
                        ts.mint, currentPrice, exitSignal
                    )
                    
                    // V5.6.8 FIX: Release exposure slot
                    com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)

                    addLog("$exitEmoji SHITCOIN SELL: ${ts.symbol} | ${exitSignal.name} | " +
                        "${if (cfg.paperMode) "PAPER" else "LIVE"} | result=$sellResult", ts.mint)
                } else {
                    // Sell failed - do NOT close position, will retry next tick
                    addLog("⚠️ SHITCOIN SELL PENDING: ${ts.symbol} | ${exitSignal.name} | result=$sellResult", ts.mint)
                }

                return  // Exit processed
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 💩🚂 SHITCOIN EXPRESS EXIT CHECK
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.ShitCoinExpress.hasRide(ts.mint)) {
            val currentPrice = resolveLivePrice(ts)
            val currentMomentum = ts.momentum ?: 0.0
            
            val exitSignal = com.lifecyclebot.v3.scoring.ShitCoinExpress.checkExit(
                ts.mint, currentPrice, currentMomentum
            )
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "ShitExpress:${exitSignal.name}") } catch (_: Exception) {}
            
            if (exitSignal != com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_100 -> "🚀"
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_50 -> "🚂"
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_30 -> "⚡"
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.STOP_LOSS -> "💥"
                    else -> "📉"
                }

                // V5.9.168 — TAKE_PROFIT_XX are LADDER rungs, not full-close
                // signals. Previously every rung closed the whole ride at first
                // hit of +30%. Now: each rung fires 20% partial-sell, full
                // close only on STOP_LOSS / TRAILING_STOP / MOMENTUM_DEATH /
                // TIME_EXIT.
                val isLadderRung = exitSignal in listOf(
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_30,
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_50,
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_100,
                )
                if (isLadderRung) {
                    executor.requestPartialSell(
                        ts = ts,
                        sellPercentage = 0.20,
                        reason = "EXPRESS_${exitSignal.name}_PARTIAL_20PCT",
                        wallet = wallet,
                        walletBalance = effectiveBalance,
                    )
                    addLog("$exitEmoji EXPRESS PARTIAL: ${ts.symbol} | ${exitSignal.name} | sold 20%, riding 80%", ts.mint)
                    return
                }

                // V5.6.9g: Only close strategy position if sell was confirmed
                val sellResult = executor.requestSell(
                    ts = ts,
                    reason = "EXPRESS_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                if (sellResult == Executor.SellResult.CONFIRMED || 
                    sellResult == Executor.SellResult.PAPER_CONFIRMED) {
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.exitRide(ts.mint, currentPrice, exitSignal)
                    
                    // V5.6.8 FIX: Release exposure slot
                    com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                    
                    addLog("$exitEmoji EXPRESS SELL: ${ts.symbol} | ${exitSignal.name} | " +
                        "${if (cfg.paperMode) "PAPER" else "LIVE"} | result=$sellResult", ts.mint)
                } else {
                    addLog("⚠️ EXPRESS SELL PENDING: ${ts.symbol} | ${exitSignal.name} | result=$sellResult", ts.mint)
                }

                return
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // ☠️ MANIPULATED EXIT CHECK
        // Hard 4-minute time exit — manipulators have already left
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.ManipulatedTraderAI.hasPosition(ts.mint)) {
            val currentPrice = resolveLivePrice(ts)
            val exitSignal = com.lifecyclebot.v3.scoring.ManipulatedTraderAI.checkExit(ts.mint, currentPrice)
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "Manipulated:${exitSignal.name}") } catch (_: Exception) {}
            if (exitSignal != com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipExitSignal.HOLD) {
                // V5.9.168 — laddered partial sell (20% per rung)
                if (exitSignal == com.lifecyclebot.v3.scoring.ManipulatedTraderAI.ManipExitSignal.PARTIAL_TAKE) {
                    executor.requestPartialSell(
                        ts = ts,
                        sellPercentage = 0.20,
                        reason = "MANIP_PARTIAL_TAKE_20PCT",
                        wallet = wallet,
                        walletBalance = effectiveBalance,
                    )
                    addLog("💰 MANIP PARTIAL: ${ts.symbol} | sold 20%, riding 80%", ts.mint)
                    return
                }

                // V5.6.9g: Only close strategy position if sell was confirmed
                val sellResult = executor.requestSell(
                    ts = ts,
                    reason = "MANIPULATED_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                if (sellResult == Executor.SellResult.CONFIRMED || 
                    sellResult == Executor.SellResult.PAPER_CONFIRMED) {
                    com.lifecyclebot.v3.scoring.ManipulatedTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                    // V5.6.8 FIX: Release exposure slot
                    com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                    addLog("☠️ MANIP EXIT: ${ts.symbol} | ${exitSignal.name} | " +
                        "${if (cfg.paperMode) "PAPER" else "LIVE"} | result=$sellResult", ts.mint)
                } else {
                    addLog("⚠️ MANIP EXIT PENDING: ${ts.symbol} | ${exitSignal.name} | result=$sellResult", ts.mint)
                }
                return
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // 🌙 MOONSHOT EXIT CHECK
        // V5.2: Check for MOONSHOT prefix since mode includes space mode (MOONSHOT_ORBITAL, etc)
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(ts.mint) || 
            ts.position.tradingMode?.startsWith("MOONSHOT") == true) {
            val currentPrice = resolveLivePrice(ts)
            
            // V5.9.204: MOONSHOT RECOVERY — after restart activePositions is empty
            // MoonshotTraderAI.checkExit returns HOLD for unregistered positions.
            // Re-register from persisted data so exits fire correctly.
            // V5.9.293: Include pendingVerify positions (live buys within 120s window).
            val msHasRealPosition = ts.position.isOpen ||
                (ts.position.qtyToken > 0.0 && ts.position.pendingVerify)
            if (!com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(ts.mint)
                    && msHasRealPosition && ts.position.entryPrice > 0) {
                val rawMode = ts.position.tradingMode ?: "MOONSHOT_ORBITAL"
                val spaceMode = try {
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.SpaceMode.valueOf(
                        rawMode.removePrefix("MOONSHOT_"))
                } catch (_: Exception) { com.lifecyclebot.v3.scoring.MoonshotTraderAI.SpaceMode.ORBITAL }
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.addPosition(
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.MoonshotPosition(
                        mint = ts.mint, symbol = ts.symbol,
                        entryPrice = ts.position.entryPrice,
                        entrySol = ts.position.costSol,
                        entryTime = ts.position.entryTime.takeIf { it > 0 } ?: (System.currentTimeMillis() - 30 * 60_000L),
                        marketCapUsd = ts.lastMcap.takeIf { it > 0 } ?: 100_000.0,
                        liquidityUsd = ts.lastLiquidityUsd.takeIf { it > 0 } ?: 5_000.0,
                        entryScore = 50.0,
                        takeProfitPct = spaceMode.baseTP,
                        stopLossPct = spaceMode.baseSL,
                        spaceMode = spaceMode,
                        isPaperMode = cfg.paperMode,
                    )
                )
                addLog("🌙 [MOONSHOT RECOVERY] ${ts.symbol} | mode=$rawMode entry=${ts.position.entryPrice}", ts.mint)
            }
            val exitSignal = com.lifecyclebot.v3.scoring.MoonshotTraderAI.checkExit(ts.mint, currentPrice)
            // V5.9.362 — Moonshot stale-price exit: same fix as Quality. Without
            // this, a stuck price feed pins pnl=0% and Moonshot's checkExit
            // returns HOLD forever (positions sit 5–8h unchanged in the UI).
            run {
                val moonshotHoldMins = (System.currentTimeMillis() - ts.position.entryTime) / 60_000
                val priceFreshness = if (ts.lastPriceUpdate > 0) System.currentTimeMillis() - ts.lastPriceUpdate else Long.MAX_VALUE
                if (priceFreshness >= 10L * 60_000L && moonshotHoldMins >= 20) {
                    ErrorLogger.warn("BotService",
                        "🌙⏱️ MOONSHOT STALE-PRICE EXIT: ${ts.symbol} | feed age=${priceFreshness/60_000}min held=${moonshotHoldMins}min")
                    executor.requestSell(
                        ts = ts,
                        reason = "MOONSHOT_STALE_PRICE",
                        wallet = wallet,
                        walletSol = effectiveBalance
                    )
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, ts.position.entryPrice,
                        com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.STOP_LOSS)
                    com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                    addLog("⏱️ MOONSHOT STALE-PRICE: ${ts.symbol} | price feed stale ${priceFreshness/60_000}min, forced SL", ts.mint)
                    return
                }
            }
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "Moonshot:${exitSignal.name}") } catch (_: Exception) {}
            
            if (exitSignal != com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.TAKE_PROFIT -> "🌙"
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.TRAILING_STOP -> "🎯"
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.PARTIAL_TAKE -> "💰"
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.STOP_LOSS -> "🛑"
                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.RUG_DETECTED -> "⚠️"
                    else -> "📉"
                }
                
                ErrorLogger.info("BotService", "🌙 [MOONSHOT EXIT] ${identity.symbol} | signal=$exitSignal | price=$currentPrice")

                if (exitSignal == com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.PARTIAL_TAKE) {
                    // Sell 50%, let 50% ride to the moon
                    val partialPct = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getPartialSellPct(ts.mint)
                    executor.requestPartialSell(
                        ts = ts,
                        sellPercentage = partialPct,
                        reason = "MOONSHOT_PARTIAL_TAKE_${(partialPct * 100).toInt()}PCT",
                        wallet = wallet,
                        walletBalance = effectiveBalance,
                    )
                    // firstTakeDone flag is already set inside MoonshotTraderAI.checkExit()
                    addLog("💰 MOONSHOT PARTIAL: ${ts.symbol} | sold ${(partialPct*100).toInt()}%, riding rest", ts.mint)
                    return
                }

                executor.requestSell(
                    ts = ts,
                    reason = "MOONSHOT_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )

                com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                
                // V5.6.8 FIX: Release exposure slot
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)

                addLog("$exitEmoji MOONSHOT SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)

                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // ⭐ QUALITY TRADER EXIT CHECK
        // V5.2.12: Professional mid-cap trading layer ($100K - $1M mcap)
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.QualityTraderAI.hasPosition(ts.mint) || 
            ts.position.tradingMode == "QUALITY") {
            val currentPrice = resolveLivePrice(ts)
            val currentMcap = ts.lastMcap.takeIf { it > 0 } ?: 0.0
            
            // V5.9.251: DEAD PRICE EXIT — if the price feed has gone completely
            // silent (ref=0, lastPrice=0, no history) the token has likely rugged.
            // Rather than showing -100% on UI indefinitely, force a STOP_LOSS exit
            // after 5 minutes of dead price so the position is cleaned up.
            val qualityHoldMins = (System.currentTimeMillis() - ts.position.entryTime) / 60_000
            val isPriceDead = ts.ref <= 0.0 && ts.lastPrice <= 0.0 && (ts.history.lastOrNull()?.priceUsd ?: 0.0) <= 0.0
            if (isPriceDead && qualityHoldMins >= 5) {
                ErrorLogger.warn("BotService",
                    "⭐💀 QUALITY DEAD-PRICE EXIT: ${ts.symbol} | ref=0 lastPrice=0 held=${qualityHoldMins}min → forcing SL")
                val deadExitSignal = com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.STOP_LOSS
                executor.requestSell(
                    ts = ts,
                    reason = "QUALITY_DEAD_PRICE",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                com.lifecyclebot.v3.scoring.QualityTraderAI.closePosition(ts.mint, ts.position.entryPrice, deadExitSignal)
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                addLog("💀 QUALITY DEAD-PRICE: ${ts.symbol} | price feed gone >5min, forced SL", ts.mint)
                return
            }

            // V5.9.362: STALE-PRICE EXIT — the dead-price check above only fires
            // when ALL three sources (ref/lastPrice/history) are zero. The actual
            // failure mode the user sees is positions stuck at exactly 0.0% PnL
            // for hours because lastPrice is non-zero but hasn't been refreshed
            // by DataOrchestrator/Birdeye/pump.fun pollers — so currentPrice falls
            // back to entryPrice and TP/SL never trigger. Detect via the new
            // lastPriceUpdate timestamp: if no fresh price in 10+ min and held
            // 20+ min, force a STOP_LOSS exit so the slot is freed.
            val priceFreshness = if (ts.lastPriceUpdate > 0) System.currentTimeMillis() - ts.lastPriceUpdate else Long.MAX_VALUE
            val priceStaleMs = 10L * 60_000L
            if (priceFreshness >= priceStaleMs && qualityHoldMins >= 20) {
                ErrorLogger.warn("BotService",
                    "⭐⏱️ QUALITY STALE-PRICE EXIT: ${ts.symbol} | feed age=${priceFreshness/60_000}min held=${qualityHoldMins}min → forcing SL")
                val staleExitSignal = com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.STOP_LOSS
                executor.requestSell(
                    ts = ts,
                    reason = "QUALITY_STALE_PRICE",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                com.lifecyclebot.v3.scoring.QualityTraderAI.closePosition(ts.mint, ts.position.entryPrice, staleExitSignal)
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                addLog("⏱️ QUALITY STALE-PRICE: ${ts.symbol} | price feed stale ${priceFreshness/60_000}min, forced SL", ts.mint)
                return
            }
            
            val exitSignal = com.lifecyclebot.v3.scoring.QualityTraderAI.checkExit(
                ts.mint, currentPrice, currentMcap
            )
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "Quality:${exitSignal.name}") } catch (_: Exception) {}
            
            if (exitSignal != com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.TAKE_PROFIT -> "✅"
                    com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.TRAILING_STOP -> "🎯"
                    com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.STOP_LOSS -> "🛑"
                    com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.PARTIAL_TAKE -> "💰"
                    com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.PROMOTE_BLUECHIP -> "🔵"
                    com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.PROMOTE_MOONSHOT -> "🚀"
                    else -> "⏱"
                }

                // V5.9.166 — laddered partial sell (20% per rung)
                if (exitSignal == com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.PARTIAL_TAKE) {
                    executor.requestPartialSell(
                        ts = ts,
                        sellPercentage = 0.20,
                        reason = "QUALITY_PARTIAL_TAKE_20PCT",
                        wallet = wallet,
                        walletBalance = effectiveBalance,
                    )
                    addLog("💰 QUALITY PARTIAL: ${ts.symbol} | sold 20%, riding 80%", ts.mint)
                    return
                }
                
                // Check for promotions - don't sell, just hand off to higher layer
                if (exitSignal == com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.PROMOTE_BLUECHIP) {
                    com.lifecyclebot.v3.scoring.QualityTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                    // Register with BlueChip layer so the position continues tracking
                    if (!com.lifecyclebot.v3.scoring.BlueChipTraderAI.hasPosition(ts.mint)) {
                        val bcTp = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getFluidTakeProfit()
                        val bcSl = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getFluidStopLoss()
                        com.lifecyclebot.v3.scoring.BlueChipTraderAI.addPosition(
                            com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipPosition(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                entryPrice = currentPrice,
                                entrySol = ts.position.costSol,
                                entryTime = System.currentTimeMillis(),
                                marketCapUsd = currentMcap,
                                liquidityUsd = ts.lastLiquidityUsd,
                                isPaper = cfg.paperMode,
                                takeProfitPct = bcTp,
                                stopLossPct = bcSl
                            )
                        )
                        ts.position.tradingMode = "BLUE_CHIP"
                        ts.position.tradingModeEmoji = "🔵"
                    }
                    addLog("$exitEmoji QUALITY→BLUECHIP: ${ts.symbol} | mcap=\$${(currentMcap/1000).toInt()}K | TP=${"%.0f".format(com.lifecyclebot.v3.scoring.BlueChipTraderAI.getFluidTakeProfit())}%", ts.mint)
                    return
                }

                if (exitSignal == com.lifecyclebot.v3.scoring.QualityTraderAI.ExitSignal.PROMOTE_MOONSHOT) {
                    com.lifecyclebot.v3.scoring.QualityTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                    val promoted = com.lifecyclebot.v3.scoring.MoonshotTraderAI.shouldPromoteToMoonshot(
                        mint = ts.mint, symbol = ts.symbol, fromLayer = "QUALITY",
                        currentPnlPct = if (ts.position.entryPrice > 0) (currentPrice - ts.position.entryPrice) / ts.position.entryPrice * 100 else 0.0,
                        currentPrice = currentPrice, marketCapUsd = currentMcap,
                    )
                    if (promoted) {
                        com.lifecyclebot.v3.scoring.MoonshotTraderAI.executePromotion(
                            mint = ts.mint, symbol = ts.symbol, fromLayer = "QUALITY",
                            entryPrice = currentPrice, positionSol = ts.position.costSol,
                            currentPnlPct = if (ts.position.entryPrice > 0) (currentPrice - ts.position.entryPrice) / ts.position.entryPrice * 100 else 0.0,
                            marketCapUsd = currentMcap, liquidityUsd = ts.lastLiquidityUsd,
                            isPaper = cfg.paperMode,
                        )
                        ts.position.tradingMode = "MOONSHOT_ORBITAL"
                        ts.position.tradingModeEmoji = "🚀"
                        addLog("$exitEmoji QUALITY→MOONSHOT: ${ts.symbol} | mcap=\$${(currentMcap/1000).toInt()}K", ts.mint)
                    } else {
                        // V5.9.243 BUG FIX: Moonshot rejected promotion (full/capped) — MUST sell now.
                        // Previously: position was removed from Quality but NOT sold and NOT in Moonshot = orphaned with no stop loss.
                        // Fix: if Moonshot won't take it, sell immediately to bank the profit safely.
                        ErrorLogger.info("BotService", "⭐ QUALITY→MOONSHOT REJECTED — selling to bank profit: ${ts.symbol} | price=$currentPrice")
                        executor.requestSell(
                            ts = ts,
                            reason = "QUALITY_MOONSHOT_REJECTED_SELL",
                            wallet = wallet,
                            walletSol = effectiveBalance
                        )
                        addLog("💰 QUALITY→MOONSHOT REJECTED: ${ts.symbol} sold to bank +100% profit", ts.mint)
                    }
                    return
                }
                
                ErrorLogger.info("BotService", "⭐ [QUALITY EXIT] ${ts.symbol} | signal=$exitSignal | price=$currentPrice")
                
                executor.requestSell(
                    ts = ts,
                    reason = "QUALITY_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                com.lifecyclebot.v3.scoring.QualityTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                
                // V5.6.8 FIX: Release exposure slot
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                
                addLog("$exitEmoji QUALITY SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 🔵 BLUE CHIP EXIT CHECK
        // V5.2.12: Professional large-cap trading layer ($1M+ mcap)
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.BlueChipTraderAI.hasPosition(ts.mint) || 
            ts.position.tradingMode == "BLUE_CHIP") {
            val currentPrice = resolveLivePrice(ts)
            
            val exitSignal = com.lifecyclebot.v3.scoring.BlueChipTraderAI.checkExit(ts.mint, currentPrice)
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "BlueChip:${exitSignal.name}") } catch (_: Exception) {}
            
            if (exitSignal != com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TAKE_PROFIT -> "✅"
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TRAILING_STOP -> "🎯"
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.STOP_LOSS -> "🛑"
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.PARTIAL_TAKE -> "💰"
                    else -> "⏱"
                }

                ErrorLogger.info("BotService", "🔵 [BLUECHIP EXIT] ${ts.symbol} | signal=$exitSignal | price=$currentPrice")

                // V5.9.166 — laddered partial sell (20% per rung)
                if (exitSignal == com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.PARTIAL_TAKE) {
                    executor.requestPartialSell(
                        ts = ts,
                        sellPercentage = 0.20,
                        reason = "BLUECHIP_PARTIAL_TAKE_20PCT",
                        wallet = wallet,
                        walletBalance = effectiveBalance,
                    )
                    addLog("💰 BLUECHIP PARTIAL: ${ts.symbol} | sold 20%, riding 80%", ts.mint)
                    return
                }

                executor.requestSell(
                    ts = ts,
                    reason = "BLUECHIP_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                
                // V5.6.8 FIX: Release exposure slot
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                
                addLog("$exitEmoji BLUECHIP SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 📉🎯 DIP HUNTER EXIT CHECK
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.DipHunterAI.hasDip(ts.mint) || ts.position.tradingMode == "DIP_HUNTER") {
            val currentPrice = resolveLivePrice(ts)
            
            val exitSignal = com.lifecyclebot.v3.scoring.DipHunterAI.checkExit(
                ts.mint, currentPrice, ts.lastLiquidityUsd
            )
            // V5.9.170 — firehose learning feedback.
            try { com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordHoldReason(ts.mint, "DipHunter:${exitSignal.name}") } catch (_: Exception) {}
            
            if (exitSignal != com.lifecyclebot.v3.scoring.DipHunterAI.DipExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.DipHunterAI.DipExitSignal.MAX_RECOVERY -> "🏆"
                    com.lifecyclebot.v3.scoring.DipHunterAI.DipExitSignal.RECOVERY_TARGET -> "✅"
                    com.lifecyclebot.v3.scoring.DipHunterAI.DipExitSignal.STOP_LOSS -> "🛑"
                    com.lifecyclebot.v3.scoring.DipHunterAI.DipExitSignal.DEATH_SPIRAL -> "💀"
                    else -> "⏱"
                }
                
                executor.requestSell(
                    ts = ts,
                    reason = "DIP_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                com.lifecyclebot.v3.scoring.DipHunterAI.closeDip(ts.mint, currentPrice, exitSignal)
                
                // V5.6.8 FIX: Release exposure slot
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
                
                addLog("$exitEmoji DIP SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // MODE-SPECIFIC EXIT LOGIC
        // 
        // Each trade type has different exit characteristics:
        //   - Fresh Launch: Fastest stops, fastest partials
        //   - Breakout: Trail below structure, allow longer hold
        //   - Reversal: Take first target quicker, breakeven early
        //   - Trend Pullback: Widest patience, tightest stop
        // ═══════════════════════════════════════════════════════════════════
        val positionTradeType = try {
            // Try to get the trade type from position's trading mode
            // PRIORITY 5: Each mode should have ISOLATED exit logic
            when (ts.position.tradingMode.uppercase()) {
                "PRESALE_SNIPE", "MICRO_CAP" -> ModeRouter.TradeType.FRESH_LAUNCH
                "MOMENTUM_SWING" -> ModeRouter.TradeType.BREAKOUT_CONTINUATION
                "REVIVAL" -> ModeRouter.TradeType.REVERSAL_RECLAIM
                "WHALE_FOLLOW" -> ModeRouter.TradeType.WHALE_ACCUMULATION
                "COPY_TRADE" -> ModeRouter.TradeType.COPY_TRADE  // FIX: Separate exit logic
                "MOONSHOT" -> ModeRouter.TradeType.GRADUATION
                "PUMP_SNIPER" -> ModeRouter.TradeType.SENTIMENT_IGNITION
                "STANDARD", "CYCLIC", "BLUE_CHIP" -> ModeRouter.TradeType.TREND_PULLBACK
                else -> ModeRouter.TradeType.UNKNOWN
            }
        } catch (_: Exception) { ModeRouter.TradeType.UNKNOWN }
        
        // Calculate current PnL using ACTUAL PRICE, not market cap
        // CRITICAL FIX: ts.ref can be market cap, not price!
        // Use ts.lastPrice for consistent price tracking
        val currentPrice = ts.lastPrice.takeIf { it > 0 } 
            ?: ts.history.lastOrNull()?.priceUsd 
            ?: ts.position.entryPrice
        val pnlPct = if (ts.position.entryPrice > 0) {
            ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
        } else 0.0
        val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
        val holdTimeMinutes = (holdTimeMs / 60_000).toInt()
        
        // ═══════════════════════════════════════════════════════════════════
        // SELL OPTIMIZATION AI (Layer 24)
        // 
        // Intelligent exit strategy that:
        // - Takes profits progressively (chunk selling)
        // - Detects momentum exhaustion
        // - Learns from historical outcomes
        // - Prevents "400% runs with nothing gained"
        // ═══════════════════════════════════════════════════════════════════
        val sellOptSignal = try {
            com.lifecyclebot.v3.scoring.SellOptimizationAI.evaluate(
                ts = ts,
                currentPnlPct = pnlPct,
                holdTimeMinutes = holdTimeMinutes,
                entryPrice = ts.position.entryPrice,
                positionSizeSol = ts.position.costSol,  // V5.9.137 — real size
            )
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "SellOptAI error: ${e.message}")
            null
        }
        
        // Execute chunk sells or urgent exits from SellOptimizationAI
        if (sellOptSignal != null && sellOptSignal.sellPct > 0 && 
            sellOptSignal.urgency != com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitUrgency.NONE) {
            
            val strategy = sellOptSignal.strategy
            val urgency = sellOptSignal.urgency
            
            // For chunk sells, calculate actual amount to sell
            val isChunkSell = strategy in listOf(
                com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.CHUNK_25,
                com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.CHUNK_50,
                com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.CHUNK_75,
            )
            
            if (isChunkSell) {
                // Chunk sell - partial position exit
                val chunkPct = sellOptSignal.sellPct / 100.0
                val sellAmount = ts.position.qtyToken * chunkPct
                
                ErrorLogger.info("BotService", "📊 [SELL_OPT CHUNK] ${ts.symbol} | " +
                    "${strategy.emoji} ${strategy.label} | sell=${sellOptSignal.sellPct.toInt()}% | " +
                    "pnl=${pnlPct.toInt()}% | peak=${sellOptSignal.peakPnlPct.toInt()}% | " +
                    "locked=${sellOptSignal.lockedProfitSol}SOL")
                
                // Execute partial sell
                executor.requestPartialSell(
                    ts = ts,
                    sellPercentage = chunkPct,
                    reason = "[SELL_OPT] ${strategy.label}: ${sellOptSignal.reason}",
                    wallet = wallet,
                    walletBalance = effectiveBalance,
                )
                
                // Record chunk in SellOptimizationAI
                val profitSol = (ts.position.costSol * chunkPct) * (pnlPct / 100.0)
                com.lifecyclebot.v3.scoring.SellOptimizationAI.recordChunkSell(
                    ts.mint, ts.position.costSol * chunkPct, pnlPct, profitSol
                )
                
            } else if (urgency in listOf(
                com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitUrgency.HIGH,
                com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitUrgency.CRITICAL
            )) {
                // Full exit for high urgency signals
                ErrorLogger.info("BotService", "🎯 [SELL_OPT EXIT] ${ts.symbol} | " +
                    "${strategy.emoji} ${strategy.label} | urgency=${urgency.name} | " +
                    "pnl=${pnlPct.toInt()}% | peak=${sellOptSignal.peakPnlPct.toInt()}%")
                
                executor.requestSell(
                    ts = ts,
                    reason = "[SELL_OPT] ${strategy.label}: ${sellOptSignal.reason}",
                    wallet = wallet,
                    walletSol = effectiveBalance,
                )
                
                // Close position tracking
                com.lifecyclebot.v3.scoring.SellOptimizationAI.closePosition(ts.mint, pnlPct)
                
                // V5.6.8 FIX: Release exposure slot
                com.lifecyclebot.v3.V3EngineManager.onPositionClosed(ts.mint)
            }
            
            // Update stop loss if suggested (for treasury positions)
            sellOptSignal.suggestedStopLoss?.let { newStop ->
                if (ts.position.isTreasuryPosition && newStop > ts.position.treasuryStopLoss) {
                    ts.position.treasuryStopLoss = newStop
                    ErrorLogger.debug("BotService", "🔒 [SELL_OPT] ${ts.symbol} stop moved to +${newStop.toInt()}%")
                }
            }
        }
        
        // Get mode-specific exit recommendation
        val exitRec = try {
            ModeSpecificExits.getExitRecommendation(ts, positionTradeType, pnlPct, holdTimeMs)
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "ModeExit error: ${e.message}")
            null
        }
        
        // Log urgent exit recommendations
        if (exitRec != null && exitRec.shouldExit && 
            exitRec.urgency in listOf(ModeSpecificExits.ExitUrgency.IMMEDIATE, ModeSpecificExits.ExitUrgency.URGENT)) {
            ModeSpecificExits.logExitRecommendation(ts, positionTradeType, exitRec)
        }
        
        if (!cbState.isHalted) {
            executor.maybeActWithDecision(
                ts                 = ts,
                decision           = decision,
                walletSol          = effectiveBalance,
                wallet             = wallet,
                lastPollMs         = lastSuccessfulPollMs,
                openPositionCount  = status.openPositionCount,
                totalExposureSol   = status.totalExposureSol,
                modeConfig         = modeConf,
                walletTotalTrades  = try {
                    com.lifecyclebot.engine.BotService.walletManager
                        ?.state?.value?.totalTrades ?: 0
                } catch (_: Exception) { 0 },
            )
            
            // Log that we're monitoring during pause
            if (cbState.isPaused) {
                addLog("⏸ Paused but monitoring ${ts.symbol} position", mint)
            }
        }
    }
    
    if (cbState.isHalted) {
        if (mint == cfg.activeToken) addLog("🛑 HALTED: ${cbState.haltReason}", mint)
    } else {
        if (mint == cfg.activeToken) addLog("⏸ CB: ${cbState.pauseRemainingSecs}s", mint)
    }

        // Include treasury tier and scaling mode in status log
        val solPxLog = WalletManager.lastKnownSolPrice
        val trsLog   = TreasuryManager.treasurySol * solPxLog
        val tierLog  = ScalingMode.activeTier(trsLog)
        val tierStr  = if (tierLog != ScalingMode.Tier.MICRO) " ${tierLog.icon}${tierLog.label}" else ""
        val trsStr   = if (TreasuryManager.treasurySol > 0.001)
            " 🏦${TreasuryManager.treasurySol.fmt(3)}◎" else ""
        addLog(
            "${ts.symbol.padEnd(8)} ${result.phase.padEnd(18)} " +
            "sig=${result.signal.padEnd(18)} " +
            "entry=${result.entryScore.toInt()} exit=${result.exitScore.toInt()} " +
            "vol=${result.meta.volScore.toInt()} press=${result.meta.pressScore.toInt()}" +
            tierStr + trsStr,
            mint
        )

        } catch (e: Exception) {
            status.tokens[mint]?.lastError = e.message ?: "unknown"
            addLog("Error [$mint]: ${e.message}", mint)
        }
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.0: TRADING LAYER READINESS FLAG
    // Prevents trading before all AI layers are initialized
    // ═══════════════════════════════════════════════════════════════════════════
    @Volatile
    private var allTradingLayersReady = false
    
    // V4.0 CRITICAL: Flag to ensure trading modes only init ONCE per session
    // Previously these were being reinit every loop, causing state resets!
    @Volatile
    private var tradingModesInitialized = false
    
    private fun initTradingModes(cfg: BotConfig) {
        // V4.0 CRITICAL: Guard against re-initialization
        if (tradingModesInitialized) {
            ErrorLogger.warn("BotService", "⚠️ initTradingModes() called again - BLOCKED (already initialized)")
            return
        }
        
        // Reset readiness flag at start
        allTradingLayersReady = false
        var initCount = 0
        var failCount = 0
        
        ErrorLogger.info("BotService", "═══════════════════════════════════════════════════")
        ErrorLogger.info("BotService", "INITIALIZING TRADING MODES (ONE-TIME ONLY)")
        ErrorLogger.info("BotService", "═══════════════════════════════════════════════════")
        
        // Cash Generation AI (Treasury Mode)
        try {
            com.lifecyclebot.v3.scoring.CashGenerationAI.setTradingMode(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "CashGenerationAI init FAILED: ${e.message}", e)
        }
        
        // ShitCoin Trader
        try {
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.init(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "ShitCoinTraderAI init FAILED: ${e.message}", e)
        }
        
        // ShitCoin Express
        try {
            com.lifecyclebot.v3.scoring.ShitCoinExpress.init(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "ShitCoinExpress init FAILED: ${e.message}", e)
        }

        // ☠️ The Manipulated
        try {
            com.lifecyclebot.v3.scoring.ManipulatedTraderAI.init(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "ManipulatedTraderAI init FAILED: ${e.message}", e)
        }
        
        // V5.2.12: Quality Trader - professional mid-cap layer
        try {
            com.lifecyclebot.v3.scoring.QualityTraderAI.init(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "QualityTraderAI init FAILED: ${e.message}", e)
        }
        
        // Dip Hunter
        try {
            com.lifecyclebot.v3.scoring.DipHunterAI.init(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "DipHunterAI init FAILED: ${e.message}", e)
        }
        
        // Solana Arbitrage
        try {
            val treasuryBalance = com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(cfg.paperMode)
            val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
            val treasuryUsd = treasuryBalance * solPrice
            com.lifecyclebot.v3.scoring.SolanaArbAI.init(cfg.paperMode, treasuryUsd)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "SolanaArbAI init FAILED: ${e.message}", e)
        }
        
        // Layer Transition Manager
        try {
            com.lifecyclebot.v3.scoring.LayerTransitionManager.init()
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "LayerTransitionManager init FAILED: ${e.message}", e)
        }
        
        // Blue Chip Trader
        try {
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.init(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "BlueChipTraderAI init FAILED: ${e.message}", e)
        }
        
        // V5.2.6: Moonshot Trader - was missing!
        try {
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.initialize(cfg.paperMode)
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "MoonshotTraderAI init FAILED: ${e.message}", e)
        }
        
        // Fluid Learning AI
        try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.init()
            com.lifecyclebot.v3.scoring.FluidLearningAI.initMarketsPrefs(this)  // V5.8.0: restore Markets trade count
            // V5.9.120: PersonalityMemoryStore — persistent traits, milestones,
            // chat history. Without this the LLM and personality layer has
            // amnesia on every restart.
            try { PersonalityMemoryStore.init(this) } catch (_: Exception) {}
            // V5.9.123: new AI layers with persistent state.
            try { com.lifecyclebot.v3.scoring.AITrustNetworkAI.init(this) } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.OperatorFingerprintAI.init(this) } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.SessionEdgeAI.init(this) } catch (_: Exception) {}
            try { com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.init(this) } catch (_: Exception) {}
            // V5.9.118: Self-check — boot-time assertion that getDynamicFluidStop
            // returns sane values for runners. This is a permanent regression
            // guard for the profit-floor-lock bug that has returned 3 times.
            // A mis-signed stop (e.g. returning -342 for a +377% peak runner)
            // will scream in the log BEFORE the bot takes a single bad trade.
            try {
                val testStop = com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                    modeDefaultStop = 20.0,
                    currentPnlPct = 251.0,
                    peakPnlPct = 377.0,
                    holdTimeSeconds = 600.0,
                    volatility = 50.0,
                )
                if (testStop <= 0.0 || testStop > 377.0) {
                    ErrorLogger.crash("BotService",
                        "🚨 PROFIT-FLOOR REGRESSION AT BOOT: getDynamicFluidStop(peak=377%,now=251%) returned $testStop — MUST be positive and <= peak. Runners will not lock gains. This was the UGOR +290% → +50% bug.",
                        RuntimeException("profit-floor self-check failed: stop=$testStop"))
                } else {
                    ErrorLogger.info("BotService",
                        "✅ Profit-floor self-check: peak=377% now=251% → lock at +${testStop.toInt()}% (exit fires correctly)")
                }
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "Profit-floor self-check skipped: ${e.message}")
            }
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "FluidLearningAI init FAILED: ${e.message}", e)
        }
        
        // Update FinalDecisionGate mode
        FinalDecisionGate.setModeForVeto(cfg.paperMode)
        
        // ═══════════════════════════════════════════════════════════════════════════
        // V4.0: Initialize GlobalTradeRegistry from config watchlist
        // ═══════════════════════════════════════════════════════════════════════════
        try {
            GlobalTradeRegistry.init(cfg.watchlist, "CONFIG")
            initCount++
        } catch (e: Exception) {
            failCount++
            ErrorLogger.error("BotService", "GlobalTradeRegistry init FAILED: ${e.message}", e)
        }
        
        // Set readiness flag - only if critical layers initialized
        // Critical: Treasury, ShitCoin, BlueChip, FluidLearning, GlobalTradeRegistry
        allTradingLayersReady = failCount == 0
        
        if (allTradingLayersReady) {
            addLog("✅ All $initCount trading layers initialized")
        } else {
            addLog("⚠️ Trading layers: $initCount OK, $failCount FAILED - trading may be limited")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE FALLBACK HELPER - Extracted to reduce botLoop complexity
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Try to get price data from fallback sources (Birdeye, pump.fun)
     * when Dexscreener doesn't have the pair.
     * Returns true if data was fetched successfully.
     */
    // V5.9.423 — broadcast a successfully-resolved fallback price to every
    // sub-trader that actually holds this mint. Previously tryFallbackPriceData
    // only updated ts.lastPrice, so sub-trader rows (esp. Quality) still
    // showed "— stale · no live feed 16m" because their per-position
    // lastSeenPrice / lastPriceUpdateMs never got touched even when Birdeye
    // or pump.fun had fresh data. Each updateLivePrice is a no-op for
    // traders that don't hold the mint, so it's safe to fan out blindly.
    private fun broadcastFallbackPrice(mint: String, priceUsd: Double) {
        if (priceUsd <= 0) return
        try { com.lifecyclebot.v3.scoring.QualityTraderAI.updateLivePrice(mint, priceUsd) } catch (_: Throwable) {}
        try { com.lifecyclebot.v3.scoring.BlueChipTraderAI.updateLivePrice(mint, priceUsd) } catch (_: Throwable) {}
        try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.updateLivePrice(mint, priceUsd) } catch (_: Throwable) {}
        try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.updateLivePrice(mint, priceUsd) } catch (_: Throwable) {}
        // CashGenerationAI / ManipulatedTraderAI / ShitCoinExpress don't
        // expose updateLivePrice — they derive lastSeenPrice inside checkExit.
    }

    // V5.9.426 — EXIT SAFETY NET helper.
    // V5.9.427 — extended from a simple -20% hard-floor-only check to a full
    // delegation to each meme sub-trader's canonical checkExit() with the
    // freshly-refreshed fallback price. This means trail exits, profit-floor
    // locks, partial-take rungs, rug detection AND the hard-floor SL all fire
    // on cycles where the primary DexScreener feed is down and
    // processTokenCycle would otherwise return early. Covers ShitCoinTraderAI
    // and MoonshotTraderAI (the meme lanes). A final hard-floor fallback runs
    // if neither sub-trader has the position registered (orphaned ts.position).
    private fun runFallbackSafetyExit(ts: TokenState, cfg: BotConfig, wallet: SolanaWallet?) {
        try {
            val price = ts.lastPrice
            if (price <= 0.0 || ts.position.entryPrice <= 0.0) return
            val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)

            // ── ShitCoinTraderAI delegation ─────────────────────────────
            if (com.lifecyclebot.v3.scoring.ShitCoinTraderAI.hasPosition(ts.mint)) {
                val sig = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.checkExit(ts.mint, price)
                if (sig != com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.HOLD) {
                    ErrorLogger.warn("BotService",
                        "🛡️ [FALLBACK_EXIT][SHITCOIN] ${ts.symbol} | signal=$sig | price=$price (DexScreener down)")
                    if (sig == com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.PARTIAL_TAKE) {
                        executor.requestPartialSell(
                            ts = ts, sellPercentage = 0.25,
                            reason = "FALLBACK_SHITCOIN_PARTIAL_TAKE",
                            wallet = wallet, walletBalance = effectiveBalance,
                        )
                        com.lifecyclebot.v3.scoring.ShitCoinTraderAI.markFirstTakeDone(ts.mint)
                    } else {
                        executor.requestSell(
                            ts = ts,
                            reason = "FALLBACK_SHITCOIN_${sig.name}",
                            wallet = wallet, walletSol = effectiveBalance,
                        )
                    }
                    return
                }
            }

            // ── MoonshotTraderAI delegation ─────────────────────────────
            if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(ts.mint)) {
                val sig = com.lifecyclebot.v3.scoring.MoonshotTraderAI.checkExit(ts.mint, price)
                if (sig != com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.HOLD) {
                    ErrorLogger.warn("BotService",
                        "🛡️ [FALLBACK_EXIT][MOONSHOT] ${ts.symbol} | signal=$sig | price=$price (DexScreener down)")
                    if (sig == com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.PARTIAL_TAKE) {
                        val partialPct = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getPartialSellPct(ts.mint)
                        executor.requestPartialSell(
                            ts = ts, sellPercentage = partialPct,
                            reason = "FALLBACK_MOONSHOT_PARTIAL_TAKE_${(partialPct * 100).toInt()}PCT",
                            wallet = wallet, walletBalance = effectiveBalance,
                        )
                    } else {
                        executor.requestSell(
                            ts = ts,
                            reason = "FALLBACK_MOONSHOT_${sig.name}",
                            wallet = wallet, walletSol = effectiveBalance,
                        )
                    }
                    return
                }
            }

            // ── Last-resort hard-floor (orphaned position) ──────────────
            // If neither sub-trader has this mint registered (e.g. state
            // wasn't rehydrated after restart), still fire the canonical
            // -20% meme hard-floor to stop catastrophic bleed.
            val pnlPct = ((price - ts.position.entryPrice) / ts.position.entryPrice) * 100.0
            if (pnlPct <= -20.0) {
                ErrorLogger.warn("BotService",
                    "🛑 [FALLBACK_SAFETY_SL][ORPHAN] ${ts.symbol} | ${pnlPct.toInt()}% — no sub-trader has mint; firing hard-floor")
                executor.requestSell(
                    ts = ts,
                    reason = "FALLBACK_ORPHAN_HARD_FLOOR_${pnlPct.toInt()}PCT",
                    wallet = wallet, walletSol = effectiveBalance,
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "[FALLBACK_SAFETY] error: ${e.message}")
        }
    }

    /**
     * V5.9.615 — Build a synthetic PairInfo from already-populated TokenState
     * fallback data (pump.fun API / Birdeye delivered price+mcap+liquidity into
     * ts.lastPrice / ts.lastMcap / ts.lastLiquidityUsd, but DexScreener has no
     * pair). This lets processTokenCycle continue into V3/ShitCoin entry
     * evaluation instead of returning early. Pre-graduation pump.fun tokens
     * are the ShitCoin lane's designed target market.
     *
     * Field semantics:
     *  - candle: synthetic 1-tick candle at current fallback price+mcap.
     *    Volume / buys / sells default to 0 — downstream scorers already
     *    handle "no data yet" via FluidLearningAI bootstrap heuristics.
     *  - liquidity / fdv: copied from ts. For pump.fun bonding-curve tokens
     *    this is mcap * 0.85 (set by tryFallbackPriceData seed path).
     *  - url: tagged so downstream source-detection sees pump.fun, which
     *    correctly routes the token into ShitCoinTraderAI.LaunchPlatform.PUMP_FUN.
     */
    private fun synthesizeFallbackPair(ts: com.lifecyclebot.data.TokenState): com.lifecyclebot.network.PairInfo? {
        if (ts.lastPrice <= 0.0) return null
        val nowMs = System.currentTimeMillis()
        val candle = com.lifecyclebot.data.Candle(
            ts = nowMs,
            priceUsd = ts.lastPrice,
            marketCap = ts.lastMcap.coerceAtLeast(0.0),
            volumeH1 = 0.0,
            volume24h = 0.0,
            buysH1 = 0,
            sellsH1 = 0,
            highUsd = ts.lastPrice,
            lowUsd = ts.lastPrice,
            openUsd = ts.lastPrice,
        )
        // URL hint so processTokenCycle's source-inference still tags pump.fun
        // correctly when ts.source is empty (the WS feed sets PUMP_PORTAL_WS,
        // but defense-in-depth: anything else lands here too).
        val url = if (ts.source.contains("PUMP", ignoreCase = true)) {
            "https://pump.fun/${ts.mint}"
        } else {
            ""
        }
        return com.lifecyclebot.network.PairInfo(
            pairAddress = "",                              // no on-chain pair yet (bonding curve)
            baseSymbol = ts.symbol.ifBlank { ts.mint.take(6) },
            baseName = ts.name.ifBlank { ts.symbol.ifBlank { ts.mint.take(6) } },
            url = url,
            candle = candle,
            pairCreatedAtMs = ts.addedToWatchlistAt.takeIf { it > 0 } ?: nowMs,
            liquidity = ts.lastLiquidityUsd.coerceAtLeast(0.0),
            fdv = ts.lastFdv.takeIf { it > 0 } ?: ts.lastMcap.coerceAtLeast(0.0),
            baseTokenAddress = ts.mint,
        )
    }

    private fun tryFallbackPriceData(mint: String, ts: TokenState): Boolean {
        // Try Birdeye first
        try {
            val cfg2 = ConfigStore.load(applicationContext)
            val ov = com.lifecyclebot.network.BirdeyeApi(cfg2.birdeyeApiKey).getTokenOverview(mint)
            if (ov != null && ov.priceUsd > 0) {
                synchronized(ts) {
                    ts.lastPrice = ov.priceUsd
                    ts.lastPriceUpdate = System.currentTimeMillis()
                    ts.lastLiquidityUsd = ov.liquidity
                    ts.lastMcap = ov.marketCap
                    ts.lastFdv = ov.marketCap
                    val syntheticCandle = com.lifecyclebot.data.Candle(
                        ts = System.currentTimeMillis(), priceUsd = ov.priceUsd,
                        marketCap = ov.marketCap, volumeH1 = 0.0, volume24h = 0.0,
                        buysH1 = 0, sellsH1 = 0, highUsd = ov.priceUsd,
                        lowUsd = ov.priceUsd, openUsd = ov.priceUsd,
                    )
                    synchronized(ts.history) {
                        ts.history.addLast(syntheticCandle)
                        if (ts.history.size > 300) ts.history.removeFirst()
                    }
                }
                broadcastFallbackPrice(mint, ov.priceUsd)   // V5.9.423
                addLog("📡 Birdeye: ${ts.symbol} \$${ov.priceUsd}", mint)
                return true
            }
        } catch (_: Exception) {}

        // V5.9.423 — DexScreenerOracle (separate code path from dex.getBestPair,
        // different endpoint, different cache). When the pair-based call fails
        // this token-address call often still returns — DexScreener caches
        // token-level and pair-level data independently.
        if (ts.lastPrice <= 0 || (System.currentTimeMillis() - ts.lastPriceUpdate) > 120_000L) {
            try {
                val priceUsd = kotlinx.coroutines.runBlocking {
                    com.lifecyclebot.perps.DexScreenerOracle.getPriceByAddress(mint)
                }
                if (priceUsd != null && priceUsd > 0) {
                    synchronized(ts) {
                        ts.lastPrice = priceUsd
                        ts.lastPriceUpdate = System.currentTimeMillis()
                    }
                    broadcastFallbackPrice(mint, priceUsd)
                    addLog("📊 DexScreener(token): ${ts.symbol} \$${priceUsd}", mint)
                    return true
                }
            } catch (_: Throwable) {}
        }

        // V5.9.423 — BirdeyeOracle token-address API (different from BirdeyeApi
        // used above, which is overview-focused; this one is price-focused and
        // hits a separate rate-limit bucket).
        if (ts.lastPrice <= 0 || (System.currentTimeMillis() - ts.lastPriceUpdate) > 120_000L) {
            try {
                val priceUsd = kotlinx.coroutines.runBlocking {
                    com.lifecyclebot.perps.BirdeyeOracle.getPriceByAddress(mint)
                }
                if (priceUsd != null && priceUsd > 0) {
                    synchronized(ts) {
                        ts.lastPrice = priceUsd
                        ts.lastPriceUpdate = System.currentTimeMillis()
                    }
                    broadcastFallbackPrice(mint, priceUsd)
                    addLog("🐦 BirdeyeOracle: ${ts.symbol} \$${priceUsd}", mint)
                    return true
                }
            } catch (_: Throwable) {}
        }

        // Try pump.fun API
        // V5.9.423 — also retry pump.fun if the last successful price is >120s
        // stale. Previously the `if (ts.lastPrice <= 0)` guard meant pump.fun
        // was only consulted on brand-new holds that had never been priced.
        if (ts.lastPrice <= 0 || (System.currentTimeMillis() - ts.lastPriceUpdate) > 120_000L) {
            try {
                val client = com.lifecyclebot.network.SharedHttpClient.builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                val request = okhttp3.Request.Builder()
                    .url("https://frontend-api.pump.fun/coins/$mint")
                    .header("Accept", "application/json").build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = org.json.JSONObject(body)
                        val mcap = json.optDouble("usd_market_cap", 0.0)
                        // NOTE: pump.fun API's "price" field is in SOL (not USD), so we
                        // compute a correct USD price from usd_market_cap / total_supply.
                        // Pump.fun tokens always have 1B token supply as their standard.
                        val totalSupply = json.optDouble("total_supply", 1_000_000_000.0)
                            .let { if (it <= 0) 1_000_000_000.0 else it }
                        val priceUsd = if (mcap > 0 && totalSupply > 0) mcap / totalSupply else 0.0
                        if (mcap > 0) {
                            synchronized(ts) {
                                ts.lastPrice = priceUsd
                                ts.lastPriceUpdate = System.currentTimeMillis()
                                ts.lastMcap = mcap
                                ts.lastFdv = mcap
                                ts.lastLiquidityUsd = mcap * 0.1
                                val syntheticCandle = com.lifecyclebot.data.Candle(
                                    ts = System.currentTimeMillis(), priceUsd = priceUsd,
                                    marketCap = mcap, volumeH1 = 0.0, volume24h = 0.0,
                                    buysH1 = 0, sellsH1 = 0, highUsd = priceUsd,
                                    lowUsd = priceUsd, openUsd = priceUsd,
                                )
                                synchronized(ts.history) {
                                    ts.history.addLast(syntheticCandle)
                                    if (ts.history.size > 300) ts.history.removeFirst()
                                }
                            }
                            addLog("🎯 Pump.fun: ${ts.symbol} mcap=\$${mcap.toInt()} priceUsd=\$${String.format("%.10f", priceUsd)}", mint)
                            broadcastFallbackPrice(mint, priceUsd)   // V5.9.423
                            return true
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHITCOIN LAYER HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate social score for a token based on available signals
     */
    private fun calculateSocialScore(ts: TokenState): Int {
        var score = 0
        
        // Boost for trending tokens
        if (ts.source.contains("TRENDING", ignoreCase = true)) score += 25
        if (ts.source.contains("BOOSTED", ignoreCase = true)) score += 20
        
        // Boost for tokens from recognized platforms
        if (ts.source.contains("PUMP_FUN", ignoreCase = true)) score += 15
        if (ts.source.contains("RAYDIUM", ignoreCase = true)) score += 10
        
        // Positive signals from source naming
        if (ts.source.contains("VERIFIED", ignoreCase = true)) score += 10
        if (ts.source.contains("MOONSHOT", ignoreCase = true)) score += 10
        
        // Symbol length heuristic (legitimate projects often have 3-6 char tickers)
        val symbolLen = ts.symbol.length
        if (symbolLen in 3..6) score += 10
        if (symbolLen > 10) score -= 5  // Too long often indicates scam
        
        // Bundle risk affects social perception
        if (ts.safety.bundleRisk == "LOW") score += 10
        if (ts.safety.bundleRisk == "HIGH") score -= 15
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * Detect copycat/scam patterns in token symbols
     * Returns true if the token appears to be a copycat of a known project
     */
    private fun detectCopyCat(symbol: String): Boolean {
        val lowerSymbol = symbol.lowercase()
        
        // Known projects that get copied frequently
        val knownProjects = listOf(
            "pepe", "doge", "shib", "floki", "wojak", "chad", 
            "elon", "trump", "biden", "solana", "eth", "btc",
            "bonk", "myro", "bome", "wif", "popcat", "mew"
        )
        
        // Check for slight variations (e.g., "PEPE2", "PEPEE", "P3PE")
        for (project in knownProjects) {
            // Exact match is OK (could be legit)
            if (lowerSymbol == project) continue
            
            // Check for suspicious patterns
            val variations = listOf(
                "${project}2", "${project}3", "${project}v2",
                "${project}inu", "${project}coin", "${project}token",
                "baby$project", "mini$project", "${project}classic",
                "${project}ai", "${project}gpt", "${project}bot"
            )
            
            if (variations.any { lowerSymbol.contains(it) || lowerSymbol == it }) {
                return true
            }
            
            // Check for character substitution (e.g., P3PE, PEP3)
            if (lowerSymbol.replace("3", "e").replace("0", "o").replace("1", "i") == project) {
                return true
            }
        }
        
        return false
    }

    private fun addLog(msg: String, mint: String = "") {
        val ts   = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val pfx  = if (mint.isNotBlank()) "[${mint.take(6)}] " else ""
        val line = "[$ts] $pfx$msg"
        synchronized(status.logs) {
            status.logs.addLast(line)
            if (status.logs.size > 600) status.logs.removeFirst()
        }
    }

    fun playSoundForTrade(pnlSol: Double, isSell: Boolean, reason: String = "") {
        if (!isSell) return
        if (pnlSol > 0) {
            soundManager.playCashRegister()
        } else {
            soundManager.playWarningSiren()
        }
    }
    
    /**
     * Show a Toast message on the UI thread.
     * Used for immediate visual feedback on trade actions.
     */
    private fun showToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                message,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendTradeNotif(title: String, body: String,
            type: NotificationHistory.NotifEntry.NotifType = NotificationHistory.NotifEntry.NotifType.INFO) {
        notifHistory.add(title, body, type)
        
        // Check if notifications are enabled
        val cfg = try { com.lifecyclebot.data.ConfigStore.load(applicationContext) }
            catch (_: Exception) { return }
        
        // Only show system notification if enabled
        if (cfg.notificationsEnabled) {
            val channel = if (cfg.vibrationEnabled) CHANNEL_TRADE else CHANNEL_TRADE_SILENT
            val intent = Intent(this, MainActivity::class.java)
            val pi     = PendingIntent.getActivity(this, 0, intent,
                             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif  = NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(if (cfg.vibrationEnabled) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(notifIdCounter++, notif)
        }
        
        // Mirror to Telegram if configured (fire-and-forget, background thread)
        if (cfg.telegramTradeAlerts && cfg.telegramBotToken.isNotBlank()) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                TelegramNotifier.send(cfg, "<b>$title</b>\n$body")
            }
        }
    }

    // ── notifications ──────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bot Running",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notification while bot is active"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRADE, "Trade Signals",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Buy/sell signal alerts"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRADE_SILENT, "Trade Signals (Silent)",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Trade alerts without vibration"
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildRunningNotif(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi     = PendingIntent.getActivity(this, 0, intent,
                         PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("AATE")
            .setContentText("Running — tap to open")
            .setOngoing(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // Default priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Purge orphaned tokens on bot stop (live mode only).
     * Scans wallet for tokens not tracked by bot and sells them.
     */
    private fun purgeOrphanedTokensOnStop(cfg: BotConfig) {
        try {
            val w = wallet ?: return
            addLog("🧹 Scanning for orphaned tokens on shutdown...")
            
            val tokenAccounts = w.getTokenAccounts()
            val trackedMints = synchronized(status.tokens) {
                status.tokens.values
                    .filter { it.position.isOpen }
                    .map { it.mint }
                    .toSet()
            }
            
            var orphansSold = 0
            
            tokenAccounts.forEach { (mint, qty) ->
                // Skip dust
                if (qty < 1.0) return@forEach
                // Skip tracked positions
                if (mint in trackedMints) return@forEach
                // Skip SOL
                if (mint == "So11111111111111111111111111111111111111112") return@forEach
                
                val symbol = status.tokens[mint]?.symbol ?: mint.take(8)
                addLog("🧹 Found orphaned token: $symbol ($qty)")
                
                try {
                    val sold = executor.sellOrphanedToken(mint, qty, w)
                    if (sold) {
                        orphansSold++
                        addLog("✅ Sold orphan: $symbol")
                    } else {
                        addLog("⚠️ Could not sell $symbol - manual cleanup needed")
                    }
                } catch (e: Exception) {
                    addLog("⚠️ Error selling $symbol: ${e.message}")
                }
            }
            
            if (orphansSold > 0) {
                addLog("🧹 Purged $orphansSold orphaned token(s) on shutdown")
            }
        } catch (e: Exception) {
            addLog("⚠️ Orphan purge failed: ${e.message}")
        }
    }
    
    /**
     * Periodic orphan scan during runtime.
     * Catches tokens that failed to sell and are stuck in wallet.
     */
    private fun scanAndSellOrphans(w: SolanaWallet) {
        try {
            val tokenAccounts = w.getTokenAccounts()
            val trackedMints = synchronized(status.tokens) {
                status.tokens.values
                    .filter { it.position.isOpen }
                    .map { it.mint }
                    .toSet()
            }
            
            var orphansFound = 0
            var orphansSold = 0
            
            tokenAccounts.forEach { (mint, qty) ->
                // Skip actual dust (less than $0.01 value typically)
                // For meme tokens, even 0.5 could be significant
                // Better: Skip if qty is essentially zero
                if (qty < 0.0000001) return@forEach
                // Skip tracked positions
                if (mint in trackedMints) return@forEach
                // Skip SOL
                if (mint == "So11111111111111111111111111111111111111112") return@forEach
                
                orphansFound++
                val symbol = status.tokens[mint]?.symbol ?: mint.take(8)
                addLog("🧹 ORPHAN FOUND: $symbol | qty=$qty | mint=${mint.take(12)}...")
                
                try {
                    val sold = executor.sellOrphanedToken(mint, qty, w)
                    if (sold) {
                        orphansSold++
                        addLog("✅ ORPHAN SOLD: $symbol")
                    } else {
                        addLog("⚠️ ORPHAN SELL FAILED: $symbol - sell manually via Jupiter")
                    }
                } catch (e: Exception) {
                    addLog("❌ ORPHAN ERROR: $symbol - ${e.message}")
                }
            }
            
            if (orphansFound > 0) {
                addLog("🧹 Orphan scan: found $orphansFound, sold $orphansSold")
            } else {
                addLog("✅ No orphaned tokens found")
            }
        } catch (e: Exception) {
            addLog("⚠️ Orphan scan failed: ${e.message}")
            ErrorLogger.error("BotService", "Orphan scan error: ${e.message}", e)
        }
    }
    
    /**
     * Calculate a raw signal score for bootstrap entry decisions.
     * This is independent of V3 score - uses raw market signals only.
     * Used to allow bootstrap trades even when V3 rejects a token.
     */
    private fun calculateBootstrapScore(
        buyPressurePct: Double,
        liquidityUsd: Double,
        momentum: Double,
        volatility: Double,
    ): Int {
        var score = 40  // Base score
        
        // Buy pressure (most important)
        score += when {
            buyPressurePct >= 70 -> 30
            buyPressurePct >= 60 -> 25
            buyPressurePct >= 50 -> 20
            buyPressurePct >= 40 -> 10
            else -> 0
        }
        
        // Liquidity
        score += when {
            liquidityUsd >= 10000 -> 15
            liquidityUsd >= 5000 -> 12
            liquidityUsd >= 3000 -> 8
            liquidityUsd >= 1500 -> 5
            else -> 0
        }
        
        // Momentum
        score += when {
            momentum >= 20 -> 10
            momentum >= 10 -> 7
            momentum >= 5 -> 4
            momentum >= 0 -> 2
            else -> 0
        }
        
        // Volatility penalty (too volatile = risky)
        score -= when {
            volatility >= 50 -> 10
            volatility >= 30 -> 5
            else -> 0
        }
        
        return score.coerceIn(0, 100)
    }
}

// Extension function for formatting doubles
private fun Double.fmt(decimals: Int = 4) = "%.${decimals}f".format(this)

// V5.9.418 — Resolve a live price for a held token in the SAME priority order
// that the unified Open Positions card uses, so sub-trader checkExit() can
// never disagree with the UI by falling back to entryPrice (and returning
// HOLD forever) while the card shows -27%. Source order:
//   1. ts.lastPrice            (most recent dex/oracle tick)
//   2. ts.history.last         (last persisted candle)
//   3. sub-trader lastSeenPrice (ShitCoin / Moonshot / Quality / BlueChip)
//   4. ts.position.entryPrice  (final fallback — same as old behaviour)
internal fun resolveLivePrice(ts: com.lifecyclebot.data.TokenState): Double {
    val tsPrice = ts.lastPrice.takeIf { it > 0.0 }
        ?: ts.history.lastOrNull()?.priceUsd?.takeIf { it > 0.0 }
    if (tsPrice != null && tsPrice > 0.0) return tsPrice

    val mint = ts.mint
    val subPrice = try {
        com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()
            .firstOrNull { it.mint == mint }?.lastSeenPrice?.takeIf { it > 0.0 }
            ?: com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()
                .firstOrNull { it.mint == mint }?.lastSeenPrice?.takeIf { it > 0.0 }
            ?: com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions()
                .firstOrNull { it.mint == mint }?.lastSeenPrice?.takeIf { it > 0.0 }
            ?: com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()
                .firstOrNull { it.mint == mint }?.lastSeenPrice?.takeIf { it > 0.0 }
    } catch (_: Throwable) { null }
    if (subPrice != null && subPrice > 0.0) return subPrice

    return ts.position.entryPrice
}
// Build trigger 1774627618
// Build trigger 1774842659
// Build trigger V5.9.418

// V5.9.495z14 — top-level sample counter for periodic full multi-timeframe
// SmartChart scans. Lives at file scope (not inside class BotService) so the
// top-level processTokenCycle() function can access it. Every 10th invocation
// runs SmartChartScanner.scan() multi-TF so longer-horizon patterns
// (Cup & Handle, Wedges, Dead Cat Bounce…) can actually fire.
private val smartChartScanCounter = java.util.concurrent.atomic.AtomicLong(0)

