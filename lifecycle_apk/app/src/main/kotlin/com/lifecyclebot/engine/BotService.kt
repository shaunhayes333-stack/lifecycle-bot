package com.lifecyclebot.engine

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
import kotlinx.coroutines.*

class BotService : Service() {

    companion object {
        @Volatile private var _instance: java.lang.ref.WeakReference<BotService>? = null
        var instance: BotService?
            get() = _instance?.get()
            set(value) { _instance = if (value != null) java.lang.ref.WeakReference(value) else null }
        const val ACTION_START  = "com.lifecyclebot.START"
        const val ACTION_STOP   = "com.lifecyclebot.STOP"
        const val CHANNEL_ID    = "bot_running"
        const val CHANNEL_TRADE = "trade_signals"
        const val NOTIF_ID      = 1

        // Shared live state — observed by UI via polling or flow
        val status = BotStatus()
        lateinit var walletManager: WalletManager
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
    private lateinit var strategy: LifecycleStrategy
    internal lateinit var executor: Executor
    private lateinit var sentimentEngine: SentimentEngine
    private lateinit var safetyChecker: TokenSafetyChecker
    private lateinit var securityGuard: SecurityGuard
    private var orchestrator: DataOrchestrator? = null
    private var marketScanner: SolanaMarketScanner? = null
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
        
        try {
            // Initialize error logger first so we can capture any init errors
            ErrorLogger.init(applicationContext)
            ErrorLogger.info("BotService", "onCreate starting")
            
            createChannels()

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
                }
            },
            onLog = { msg -> addLog(msg) }
        )
        copyTradeEngine.loadWallets()
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
        
        // Initialize GeminiCopilot with API key from config
        if (cfg.geminiApiKey.isNotBlank()) {
            GeminiCopilot.init(cfg.geminiApiKey)
            ErrorLogger.info("BotService", "GeminiCopilot initialized with API key")
        }
        
        } catch (e: Exception) {
            ErrorLogger.crash("BotService", "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            android.util.Log.e("BotService", "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            // Don't crash the service - log and continue
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Call startForeground IMMEDIATELY to avoid ForegroundServiceDidNotStartInTimeException
        // Android gives us only 5 seconds after startForegroundService() is called
        try {
            startForeground(NOTIF_ID, buildRunningNotif())
            ErrorLogger.info("BotService", "Foreground started in onStartCommand")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "startForeground failed in onStartCommand: ${e.message}", e)
        }
        
        when (intent?.action) {
            ACTION_START -> {
                if (!status.running) {
                    startBot()
                } else {
                    // Bot already running - just reschedule keep-alive
                    scheduleKeepAliveAlarm()
                }
            }
            ACTION_STOP  -> stopBot()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ErrorLogger.warn("BotService", "onDestroy() called - service being destroyed")
        
        // Try to close open positions if bot was running and closePositionsOnStop is enabled
        if (status.running) {
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
        
        if (status.running) {
            // Re-schedule start in 2 seconds with exact alarm
            val restartIntent = Intent(applicationContext, BotService::class.java).apply {
                action = ACTION_START
            }
            val pi = android.app.PendingIntent.getService(
                this, 1, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(android.app.AlarmManager::class.java)
            // Use setExactAndAllowWhileIdle for better reliability in Doze mode
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 2_000,
                pi
            )
            ErrorLogger.info("BotService", "Scheduled restart alarm for 2 seconds")
        }
    }

    // ── start / stop ───────────────────────────────────────

    fun startBot() {
        if (status.running) return
        
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
            addLog("✓ Config loaded: paperMode=${cfg.paperMode}")
            
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
                    // Need to connect wallet
                    addLog("Attempting wallet connection to ${rpcUrl.take(40)}...")
                    try {
                        val connected = walletManager.connect(cfg.privateKeyB58, rpcUrl)
                        if (connected) {
                            addLog("✓ Wallet connected: ${walletManager.state.value.shortKey}")
                            walletManager.getWallet()
                        } else {
                            addLog("⚠️ Wallet error: ${walletManager.state.value.errorMessage} — using paper mode")
                            null
                        }
                    } catch (e: Exception) {
                        addLog("⚠️ Wallet connection failed: ${e.message} — using paper mode")
                        null
                    }
                }
            } else {
                addLog("Paper mode enabled or no key provided")
                // DON'T disconnect - just set local wallet to null for paper mode
                // walletManager.disconnect() -- REMOVED: This was disconnecting the wallet!
                null
            }

            // Run startup reconciliation to catch any state mismatch
            // from previous crash, manual sells, or failed transactions
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
                            autoSellOrphans = !cfg.paperMode  // Only auto-sell in live mode
                        )
                        reconciler.reconcile()
                    } catch (e: Exception) {
                        addLog("Reconciliation error: ${e.message}")
                    }
                }
            } else {
                addLog("Paper mode — skipping on-chain reconciliation")
            }

        addLog("✓ Starting bot loop...")
        loopJob = scope.launch { botLoop() }
        
        // Start data orchestrator (real-time streams)
        addLog("✓ Creating data orchestrator...")
        try {
            orchestrator = DataOrchestrator(
                cfg                = { ConfigStore.load(applicationContext) },
                status             = status,
                onLog              = ::addLog,
                onNotify           = { title, body, type -> sendTradeNotif(title, body, type) },
                onNewTokenDetected = { mint, symbol, name ->
                    // Auto-add new Pump.fun launches to watchlist if configured
                    try {
                        val c = ConfigStore.load(applicationContext)
                        if (c.autoAddNewTokens) {
                            val wl = c.watchlist.toMutableList()
                            if (mint !in wl && wl.size < 20) {
                                wl.add(mint)
                                ConfigStore.save(applicationContext, c.copy(watchlist = wl))
                                addLog("Auto-added new token: $symbol ($mint)", mint)
                                soundManager.playNewToken()
                            }
                        }
                    } catch (_: Exception) {}
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

        // Start full Solana market scanner
        val scanCfg = ConfigStore.load(applicationContext)
        ErrorLogger.info("BotService", "fullMarketScanEnabled = ${scanCfg.fullMarketScanEnabled}")
        if (scanCfg.fullMarketScanEnabled) {
            try {
                ErrorLogger.info("BotService", "Creating market scanner...")
                marketScanner = SolanaMarketScanner(
                    cfg          = { ConfigStore.load(applicationContext) },
                    onTokenFound = { mint, symbol, name, source, score, liquidityUsd ->
                        try {
                            val c = ConfigStore.load(applicationContext)
                            val wl = c.watchlist.toMutableList()
                            
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
                            
                            // Already tracked?
                            if (identity.mint in wl) {
                                ErrorLogger.debug("BotService", "Token ${identity.symbol} already in watchlist")
                                return@SolanaMarketScanner
                            }
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // STAGE 2: ELIGIBILITY CHECKS (filter junk before watchlisting)
                            // Minimum prerequisites: liquidity, safety, not banned
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // Check 2a: MINIMUM LIQUIDITY (most important filter!)
                            // Zero-liq tokens are untradeable junk - don't waste watchlist space
                            // V3.2: Raised floors to reduce watchlist clutter
                            //   - Watch/Shadow floor: $2K (was $500 paper, $3K live)
                            //   - Execution floor: $10K (enforced in BotOrchestrator)
                            val minLiquidity = if (c.paperMode) 2000.0 else 2000.0  // $2K unified floor
                            if (liquidityUsd < minLiquidity) {
                                TradeLifecycle.ineligible(identity.mint, "Liquidity too low: $${liquidityUsd.toInt()} < $${minLiquidity.toInt()}")
                                ErrorLogger.debug("BotService", "INELIGIBLE: ${identity.symbol} - liq $${liquidityUsd.toInt()} < $${minLiquidity.toInt()}")
                                return@SolanaMarketScanner
                            }
                            
                            // Check 2b: Blacklist (skip in paper mode - we want to learn)
                            if (!c.paperMode && TokenBlacklist.isBlocked(identity.mint)) {
                                TradeLifecycle.ineligible(identity.mint, "Blacklisted")
                                ErrorLogger.debug("BotService", "INELIGIBLE: ${identity.symbol} - blacklisted")
                                return@SolanaMarketScanner
                            }
                            
                            // Check 2c: Minimum score threshold
                            val minScore = if (c.paperMode) 30.0 else 40.0
                            if (score < minScore) {
                                TradeLifecycle.ineligible(identity.mint, "Score too low: $score < $minScore")
                                ErrorLogger.debug("BotService", "INELIGIBLE: ${identity.symbol} - score $score < $minScore")
                                return@SolanaMarketScanner
                            }
                            
                            // Mark as ELIGIBLE (passed all prereqs)
                            identity.eligible(score, "Passed eligibility checks")
                            TradeLifecycle.eligible(identity.mint, score, "liq=$${liquidityUsd.toInt()}, score=$score")
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // STAGE 3: WATCHLIST ADMISSION (capacity check)
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // Check 3a: Watchlist capacity
                            val effectiveMaxWatchlist = if (c.paperMode) 100 else c.maxWatchlistSize
                            if (wl.size >= effectiveMaxWatchlist) {
                                TradeLifecycle.filtered(identity.mint, "Watchlist full (${wl.size}/${effectiveMaxWatchlist})")
                                ErrorLogger.debug("BotService", "FILTERED: ${identity.symbol} - watchlist full")
                                return@SolanaMarketScanner
                            }
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // ADMITTED TO WATCHLIST
                            // ═══════════════════════════════════════════════════════════════════
                            wl.add(identity.mint)
                            ConfigStore.save(applicationContext, c.copy(watchlist = wl))
                            
                            // Mark as WATCHLISTED (both TradeIdentity and TradeLifecycle)
                            identity.watchlisted("admitted for strategy evaluation")
                            TradeLifecycle.watchlisted(identity.mint, wl.size, "admitted for strategy evaluation")
                            
                            // Record that we found a new token (for staleness detection)
                            marketScanner?.recordNewTokenFound()
                            
                            addLog("📋 WATCHLISTED: ${identity.symbol} (${source.name}) liq=$${liquidityUsd.toInt()} score=${score.toInt()} | now #${wl.size}", identity.mint)
                            ErrorLogger.info("BotService", "WATCHLISTED: ${identity.symbol} | liq=$${liquidityUsd.toInt()} | watchlist now=${wl.size}")
                            soundManager.playNewToken()
                            
                            // Seed candle history immediately
                            scope.launch {
                                try {
                                    val ts = synchronized(status.tokens) {
                                        status.tokens.getOrPut(identity.mint) {
                                            com.lifecyclebot.data.TokenState(
                                                mint=identity.mint, symbol=identity.symbol, name=name,
                                                candleTimeframeMinutes = 1,
                                                source = source.name,  // Track discovery source for learning
                                                logoUrl = "https://dd.dexscreener.com/ds-data/tokens/solana/${identity.mint}.png",
                                            )
                                        }
                                    }
                                    // Also update source if token already existed but had no source
                                    if (ts.source.isEmpty()) {
                                        ts.source = source.name
                                    }
                                    orchestrator?.onTokenAdded(mint, symbol)
                                } catch (_: Exception) {}
                            }
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
        
        // Initialize CollectiveLearning (Turso shared knowledge base)
        if (cfg.collectiveLearningEnabled && cfg.tursoDbUrl.isNotBlank() && cfg.tursoAuthToken.isNotBlank()) {
            scope.launch {
                try {
                    val success = com.lifecyclebot.collective.CollectiveLearning.init(applicationContext)
                    if (success) {
                        val stats = com.lifecyclebot.collective.CollectiveLearning.getStats()
                        addLog("🌐 CollectiveLearning: ${stats["blacklistedTokens"]} blacklisted, ${stats["patterns"]} patterns")
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
        
        // Set up paper wallet balance tracking
        executor.onPaperBalanceChange = { delta ->
            status.paperWalletSol = (status.paperWalletSol + delta).coerceAtLeast(0.0)
            ErrorLogger.info("PaperWallet", "Balance changed by ${delta}: new balance = ${status.paperWalletSol}")
        }
        
        // Persist running state so BootReceiver can restart after reboot
        getSharedPreferences("bot_runtime", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("was_running_before_shutdown", true).apply()
        // Acquire partial wake lock — keeps CPU alive during transaction confirmation
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecyclebot:trading")
            .also { it.acquire(12 * 60 * 60 * 1000L) }  // max 12h, released on stopBot
        
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
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
        }
    }

    fun stopBot() {
        addLog("🛑 Stopping bot...")
        
        // IMPORTANT: Close all open positions BEFORE stopping (if enabled in config)
        // This ensures funds are returned and no positions are left dangling
        try {
            val cfg = ConfigStore.load(applicationContext)
            
            if (cfg.closePositionsOnStop) {
                val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                
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
                
                // Also purge any orphaned tokens (live mode only)
                if (!cfg.paperMode && wallet != null) {
                    purgeOrphanedTokensOnStop(cfg)
                }
            } else {
                val openCount = synchronized(status.tokens) {
                    status.tokens.values.count { it.position.isOpen }
                }
                if (openCount > 0) {
                    addLog("⚠️ $openCount position(s) left open (closePositionsOnStop=false)")
                }
            }
        } catch (e: Exception) {
            addLog("⚠️ Error closing positions on shutdown: ${e.message}")
            ErrorLogger.error("BotService", "Error closing positions on shutdown: ${e.message}", e)
        }
        
        status.running = false
        loopJob?.cancel()
        orchestrator?.stop()
        orchestrator = null
        marketScanner?.stop(); marketScanner = null
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
        
        // REMOVED: walletManager.disconnect() 
        // Wallet should ONLY disconnect when user explicitly requests it
        // This allows wallet to stay connected when:
        // - Bot is stopped
        // - App is minimized
        // - App crashes and restarts
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        getSharedPreferences("bot_runtime", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("was_running_before_shutdown", false).apply()
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
        
        // Use setExactAndAllowWhileIdle for better reliability on all devices
        // This will fire once, then reschedule itself in onStartCommand
        am?.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 120_000,  // 2 minutes
            pi
        )
        ErrorLogger.info("BotService", "Keep-alive alarm scheduled for 2 minutes")
        
        // Start self-healing diagnostics (runs every 3 hours)
        try {
            SelfHealingDiagnostics.start(scope)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "Failed to start SelfHealingDiagnostics: ${e.message}")
        }
    }
    
    private fun cancelKeepAliveAlarm() {
        val restartIntent = Intent(applicationContext, BotService::class.java).apply {
            action = ACTION_START
        }
        val pi = android.app.PendingIntent.getService(
            this, 999, restartIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(android.app.AlarmManager::class.java)
        am?.cancel(pi)
        ErrorLogger.info("BotService", "Keep-alive alarm cancelled")
    }

    // ── main loop ──────────────────────────────────────────

    private suspend fun botLoop() {
        ErrorLogger.info("BotService", "botLoop() started")
        var loopCount = 0
        while (status.running) {
          try {
            loopCount++
            val cfg       = ConfigStore.load(applicationContext)
            val watchlist = cfg.watchlist.toMutableList()
            if (cfg.activeToken.isNotBlank() && cfg.activeToken !in watchlist)
                watchlist.add(cfg.activeToken)
            
            // ═══════════════════════════════════════════════════════════════════
            // WALLET HEALTH CHECK - Critical for live mode sells
            // If wallet is null in live mode, sells will fail silently!
            // ═══════════════════════════════════════════════════════════════════
            if (!cfg.paperMode && wallet == null) {
                ErrorLogger.error("BotService", "🚨 LIVE MODE but wallet is NULL! Attempting reconnect...")
                addLog("🚨 WALLET NULL IN LIVE MODE - Reconnecting...")
                
                // Try to reconnect
                if (cfg.privateKeyB58.isNotBlank()) {
                    try {
                        val rpcUrl = cfg.rpcUrl.ifBlank { "https://api.mainnet-beta.solana.com" }
                        val connected = walletManager.connect(cfg.privateKeyB58, rpcUrl)
                        if (connected) {
                            wallet = walletManager.getWallet()
                            addLog("✅ Wallet reconnected: ${walletManager.state.value.shortKey}")
                            ErrorLogger.info("BotService", "✅ Wallet reconnected successfully")
                        } else {
                            addLog("❌ Wallet reconnect failed: ${walletManager.state.value.errorMessage}")
                            ErrorLogger.error("BotService", "❌ Wallet reconnect failed")
                        }
                    } catch (e: Exception) {
                        addLog("❌ Wallet reconnect error: ${e.message}")
                        ErrorLogger.error("BotService", "❌ Wallet reconnect error: ${e.message}")
                    }
                } else {
                    addLog("❌ Cannot reconnect - no private key configured")
                }
            }
            
            // Update FinalDecisionGate mode for veto cooldown timing
            FinalDecisionGate.setModeForVeto(cfg.paperMode)
            
            // ═══════════════════════════════════════════════════════════════════
            // CASH GENERATION AI (Treasury Mode) - Sync mode with paper/live
            // This ensures treasury balances display correctly based on mode
            // ═══════════════════════════════════════════════════════════════════
            try {
                com.lifecyclebot.v3.scoring.CashGenerationAI.setTradingMode(cfg.paperMode)
            } catch (e: Exception) {
                // Silently ignore - Treasury Mode is supplemental
            }

            // Log watchlist status every 5 loops for better visibility
            if (loopCount % 5 == 1) {
                ErrorLogger.info("BotService", "Bot loop #$loopCount - Watchlist size: ${watchlist.size}")
                addLog("🔄 Loop #$loopCount | Watchlist: ${watchlist.size} tokens | Scanner: ${if(marketScanner != null) "ACTIVE" else "INACTIVE"}")
                if (watchlist.isEmpty()) {
                    addLog("⚠️ Watchlist empty - waiting for scanner to discover tokens...")
                } else {
                    // Log first 3 tokens being processed
                    val firstTokens = watchlist.take(3).joinToString(", ") { it.take(8) + "..." }
                    addLog("📊 Processing: $firstTokens")
                }
                
                // Record healthy status for watchdog (every 5 loops ~ 25 seconds)
                ServiceWatchdog.recordHealthy(applicationContext)
            }
            
            // AGGRESSIVE WATCHLIST CLEANUP - every 5 loops (about 25 seconds)
            // Remove tokens that are blocked, stale, or underperforming
            if (loopCount % 5 == 0 && watchlist.size > 3) {
                scope.launch {
                    try {
                        cleanupWatchlist()
                    } catch (e: Exception) {
                        ErrorLogger.error("BotService", "Watchlist cleanup error: ${e.message}")
                    }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // SCANNER STALENESS CHECK - every 6 loops (~30 seconds)
            // If no new tokens found for 2 minutes, reset scanner maps
            // ═══════════════════════════════════════════════════════════════════
            if (loopCount % 6 == 0 && marketScanner != null) {
                marketScanner?.checkAndResetIfStale()
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
            // PENDING SELL QUEUE PROCESSING - every 5 loops (~25 seconds) in live mode
            // Retries sells that failed due to wallet disconnect or other issues
            // ═══════════════════════════════════════════════════════════════════
            if (!cfg.paperMode && loopCount % 5 == 0 && wallet != null && PendingSellQueue.hasPending()) {
                scope.launch {
                    try {
                        val pendingSells = PendingSellQueue.getAndClear()
                        if (pendingSells.isNotEmpty()) {
                            addLog("🔄 Processing ${pendingSells.size} pending sells...")
                            for (sell in pendingSells) {
                                try {
                                    // Find the token state if still tracked
                                    val ts = synchronized(status.tokens) { 
                                        status.tokens[sell.mint]
                                    }
                                    
                                    if (ts != null && ts.position.isOpen) {
                                        addLog("🔄 Retrying sell: ${sell.symbol} (attempt ${sell.retryCount})")
                                        executor.requestSell(ts, "PENDING_RETRY: ${sell.reason}", wallet, wallet!!.getSolBalance())
                                    } else {
                                        // Token no longer tracked - might be orphaned
                                        addLog("⚠️ Pending sell for untracked token: ${sell.symbol} - checking wallet...")
                                        // The orphan scanner will catch it
                                    }
                                } catch (e: Exception) {
                                    addLog("❌ Pending sell retry failed: ${sell.symbol} - ${e.message}")
                                    PendingSellQueue.requeue(sell)
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
            if (loopCount % 60 == 0) {
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
                    val persistedTradeCount = if (cfg.paperMode) {
                        FluidLearning.getTradeCount()  // Persisted paper trade count
                    } else {
                        // For live mode, use wallet state total trades
                        sessionTradeCount
                    }
                    
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
                    
                    // Initialize paper wallet with ~$500 worth of SOL for realistic testing
                    val cfg = ConfigStore.load(applicationContext)
                    if (cfg.paperMode && !status.paperWalletInitialized) {
                        status.paperWalletSol = 5.6  // ~$500 at $89/SOL
                        status.paperWalletInitialized = true
                        ErrorLogger.info("PaperWallet", "Initialized with 5.6 SOL (~\$500)")
                        addLog("📝 Paper wallet: 5.6 SOL (~\$500)")
                    }

                    // Treasury milestone check — ONLY for LIVE mode
                    // FIX #4: Paper and live accounting completely separate
                    if (!cfg.paperMode) {
                        val solPx = WalletManager.lastKnownSolPrice
                        TreasuryManager.onWalletUpdate(
                            walletSol    = freshSol,
                            solPrice     = solPx,
                            onMilestone  = { milestone, walletUsd ->
                                addLog("🏦 MILESTONE: ${milestone.label} hit @ \$${walletUsd.toLong()}", "treasury")
                                if (milestone.celebrateOnHit) {
                                    sendTradeNotif("🎉 ${milestone.label}!",
                                        "Treasury now locking ${(milestone.lockPct*100).toInt()}% of profits",
                                        NotificationHistory.NotifEntry.NotifType.INFO)
                                }
                                TreasuryManager.save(applicationContext)
                            }
                        )
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

            // ═══════════════════════════════════════════════════════════════════
            // V3.2 WATCHLIST PRIORITIZATION
            // 
            // Sort watchlist so stronger candidates get processed first.
            // This ensures compute goes to highest-quality tokens when
            // processing time is limited.
            //
            // Priority factors:
            //   1. Has open position (highest priority - needs monitoring)
            //   2. Higher liquidity (tradeable)
            //   3. Higher entry score (better setup)
            //   4. Lower fatal risk (safer)
            // ═══════════════════════════════════════════════════════════════════
            val prioritizedWatchlist = if (cfg.v3EngineEnabled) {
                watchlist.sortedByDescending { mint ->
                    val ts = status.tokens[mint]
                    if (ts == null) {
                        0.0  // Unknown tokens get lowest priority
                    } else {
                        var priority = 0.0
                        // Open positions get highest priority (need exit monitoring)
                        if (ts.position.isOpen) priority += 1000.0
                        // Liquidity score (capped at 100k for normalization)
                        priority += (ts.lastLiquidityUsd / 1000.0).coerceAtMost(100.0)
                        // Entry score contribution
                        priority += ts.entryScore
                        // Momentum score boost
                        priority += ts.meta.momScore * 0.5
                        // Volume score boost
                        priority += ts.meta.volScore * 0.3
                        priority
                    }
                }
            } else {
                watchlist  // Legacy: process in order
            }

            // Process all tokens in parallel — each gets its own coroutine.
            // This reduces per-cycle latency from (N×50ms + pollSeconds) to just pollSeconds.
            val tokenJobs = prioritizedWatchlist.map { mint ->
              scope.launch {
                if (!status.running) return@launch
                if (orchestrator?.shouldPoll(mint) == false) return@launch
                try {
                    // Primary price source: Dexscreener
                    // Fallback to Birdeye, then pump.fun API for bonding curve tokens
                    val pair = dex.getBestPair(mint) ?: run {
                        val ts = status.tokens[mint]
                        if (ts != null) {
                            // Try Birdeye first
                            try {
                                val cfg2  = ConfigStore.load(applicationContext)
                                val ov    = com.lifecyclebot.network.BirdeyeApi(cfg2.birdeyeApiKey)
                                    .getTokenOverview(mint)
                                if (ov != null && ov.priceUsd > 0) {
                                    synchronized(ts) {
                                        ts.lastPrice        = ov.priceUsd
                                        ts.lastLiquidityUsd = ov.liquidity
                                        ts.lastMcap         = ov.marketCap
                                        ts.lastFdv          = ov.marketCap
                                        // Create synthetic candle for history
                                        val syntheticCandle = com.lifecyclebot.data.Candle(
                                            ts          = System.currentTimeMillis(),
                                            priceUsd    = ov.priceUsd,
                                            marketCap   = ov.marketCap,
                                            volumeH1    = 0.0,
                                            volume24h   = 0.0,
                                            buysH1      = 0,
                                            sellsH1     = 0,
                                            highUsd     = ov.priceUsd,
                                            lowUsd      = ov.priceUsd,
                                            openUsd     = ov.priceUsd,
                                        )
                                        synchronized(ts.history) {
                                            ts.history.addLast(syntheticCandle)
                                            if (ts.history.size > 300) ts.history.removeFirst()
                                        }
                                    }
                                    addLog("📡 Birdeye data for ${ts.symbol}: \$${ov.priceUsd}", mint)
                                }
                            } catch (_: Exception) {}
                            
                            // Try pump.fun API directly for bonding curve tokens
                            if (ts.lastPrice <= 0) {
                                try {
                                    val pumpUrl = "https://frontend-api.pump.fun/coins/$mint"
                                    val request = okhttp3.Request.Builder()
                                        .url(pumpUrl)
                                        .header("Accept", "application/json")
                                        .build()
                                    val client = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    val response = client.newCall(request).execute()
                                    if (response.isSuccessful) {
                                        val body = response.body?.string()
                                        if (body != null) {
                                            val json = org.json.JSONObject(body)
                                            val mcap = json.optDouble("usd_market_cap", 0.0)
                                            val price = json.optDouble("price", 0.0)
                                            if (mcap > 0 || price > 0) {
                                                synchronized(ts) {
                                                    ts.lastPrice = price
                                                    ts.lastMcap = mcap
                                                    ts.lastFdv = mcap
                                                    ts.lastLiquidityUsd = mcap * 0.1  // Estimate
                                                    // Create synthetic candle
                                                    val syntheticCandle = com.lifecyclebot.data.Candle(
                                                        ts          = System.currentTimeMillis(),
                                                        priceUsd    = price,
                                                        marketCap   = mcap,
                                                        volumeH1    = 0.0,
                                                        volume24h   = 0.0,
                                                        buysH1      = 0,
                                                        sellsH1     = 0,
                                                        highUsd     = price,
                                                        lowUsd      = price,
                                                        openUsd     = price,
                                                    )
                                                    synchronized(ts.history) {
                                                        ts.history.addLast(syntheticCandle)
                                                        if (ts.history.size > 300) ts.history.removeFirst()
                                                    }
                                                }
                                                addLog("🎯 Pump.fun data for ${ts.symbol}: mcap=\$${mcap.toInt()}", mint)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        return@launch   // skip full cycle — but we may have added data above
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
                                logoUrl    = "https://dd.dexscreener.com/ds-data/tokens/solana/$mint.png",
                            )
                        }
                        val ts = status.tokens[mint] ?: return@launch
                        
                        // Try to infer source if unknown
                        if (ts.source.isEmpty() || ts.source == "UNKNOWN") {
                            ts.source = when {
                                pair.url.contains("pump.fun") -> "PUMP_FUN_GRADUATE"
                                pair.url.contains("raydium") -> "RAYDIUM_NEW_POOL"
                                else -> "DEX_TRENDING"
                            }
                        }
                        
                        ts.lastPrice        = pair.candle.priceUsd
                        ts.lastMcap         = pair.candle.marketCap
                        ts.lastLiquidityUsd = pair.liquidity
                        ts.lastFdv          = pair.fdv
                        
                        // V3.2: Update shadow learning engine with price
                        if (pair.candle.priceUsd > 0) {
                            com.lifecyclebot.v3.learning.ShadowLearningEngine.updatePrice(
                                ts.mint, pair.candle.priceUsd
                            )
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

                    val ts = status.tokens[mint] ?: return@launch
                    
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
                        // Remove from watchlist to stop wasting resources
                        synchronized(status.tokens) {
                            status.tokens.remove(mint)
                        }
                        ErrorLogger.debug("BotService", "Removing banned token ${ts.symbol} from watchlist")
                        return@launch
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
                        
                        // Detect potential rug: >50% price drop or liquidity collapse
                        if (olderPrice > 0 && recentPrice > 0) {
                            val priceDropPct = ((olderPrice - recentPrice) / olderPrice) * 100
                            val liqDropPct = if (olderLiq > 0) ((olderLiq - recentLiq) / olderLiq) * 100 else 0.0
                            
                            if (priceDropPct >= 50 || liqDropPct >= 70) {
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
                            val report = safetyChecker.check(
                                mint            = mint,
                                symbol          = ts.symbol,
                                name            = ts.name,
                                pairCreatedAtMs = pairCreatedAt,
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
                                    TokenBlacklist.block(mint, "Safety: $reason")
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

                lastSuccessfulPollMs = System.currentTimeMillis()
                
                // ═══════════════════════════════════════════════════════════════════
                // STEP 0: CORE EVALUATIONS (needed by subsequent steps)
                // ═══════════════════════════════════════════════════════════════════
                val curveState = BondingCurveTracker.evaluate(ts)
                val whaleState = WhaleDetector.evaluate(mint, ts)
                
                // ═══════════════════════════════════════════════════════════════════
                // STEP 1: HARD RUG PRE-FILTER
                // Kill obvious garbage BEFORE consuming strategy/FDG cycles
                // ═══════════════════════════════════════════════════════════════════
                if (!ts.position.isOpen) {
                    val preFilterResult = try {
                        HardRugPreFilter.filter(ts)
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "PreFilter error: ${e.message}")
                        HardRugPreFilter.PreFilterResult(true, null, HardRugPreFilter.FilterSeverity.PASS)
                    }
                    
                    if (!preFilterResult.pass) {
                        HardRugPreFilter.logFailure(ts, preFilterResult)
                        return@launch  // Skip to next token (exit this coroutine)
                    }
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // STEP 2: DISTRIBUTION FADE AVOIDER
                // Check if token is in distribution/dead-bounce state
                // Note: edgePhase comes from ScoreResult later, so we pass null here
                // and rely on price/volume pattern detection instead
                // ═══════════════════════════════════════════════════════════════════
                val distributionCheck = try {
                    DistributionFadeAvoider.evaluate(ts, null)
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "DistFade error: ${e.message}")
                    DistributionFadeAvoider.FadeResult(false, null, 1.0, 0L)
                }
                
                if (distributionCheck.shouldBlock && !ts.position.isOpen) {
                    ErrorLogger.info("BotService", "🔻 ${ts.symbol} DISTRIBUTION_FADE: ${distributionCheck.reason}")
                    return@launch  // Skip to next token (exit this coroutine)
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
                            val scanResult = SmartChartScanner.quickScan(ts)
                            if (scanResult != null && scanResult.confidence >= 60) {
                                // Log significant patterns
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
                    return@launch
                }

                // In PAUSED mode: no new entries (existing positions still managed)
                if (modeConf?.mode == AutoModeEngine.BotMode.PAUSED && !ts.position.isOpen) return@launch

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
                    // Silently skip - no spam logging
                    return@launch
                }
                
                // FATAL SUPPRESSION: Only rugged/honeypot/unsellable blocks
                val isFatalSuppression = DistributionFadeAvoider.isFatalSuppression(identity.mint)
                if (isFatalSuppression && !ts.position.isOpen) {
                    val reason = DistributionFadeAvoider.checkRawStrategySuppression(identity.mint)
                    ErrorLogger.info("BotService", "[FATAL] ${identity.symbol} | BLOCK | $reason")
                    return@launch
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // V3 ENGINE: Process token through unified scoring
                // Strategy output (phase, entry/exit scores, quality) feeds into V3
                // V3 is the ONLY thing that decides EXECUTE/WATCH/REJECT
                // ═══════════════════════════════════════════════════════════════════
                if (!ts.position.isOpen && cfg.v3EngineEnabled && com.lifecyclebot.v3.V3EngineManager.isReady()) {
                    
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
                        
                        when (val result = v3Decision) {
                            is com.lifecyclebot.v3.V3Decision.Execute -> {
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
                                    // V3 CONTROLS EXECUTION
                                    val v3SizeSol = result.sizeSol
                                    val v3Thesis = "V3 score=${result.score} band=${result.band}"
                                    
                                    // Update lifecycle to V3 states
                                    identity.v3Execute(result.score, result.band, result.sizeSol)
                                    
                                    // Execute the trade
                                    val proposedSize = result.sizeSol
                                    val modeTag = try {
                                        modeConf?.mode?.let { ModeSpecificGates.fromBotMode(it) }
                                    } catch (e: Exception) { null }
                                    
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
                                } else {
                                    // Shadow mode - log only
                                    ErrorLogger.info("BotService", "[SHADOW] ${identity.symbol} | WOULD_EXECUTE | ${result.band} | ${result.sizeSol.fmt(4)} SOL")
                                    addLog("🔬 V3 SHADOW: ${identity.symbol} | ${result.band}", ts.mint)
                                }
                            }
                            
                            is com.lifecyclebot.v3.V3Decision.Watch -> {
                                // ═══════════════════════════════════════════════════════════════════
                                // V3 WATCH: Track but don't trade
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
                                
                                // Don't execute, exit cleanly
                                return@launch
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
                                
                                return@launch
                            }
                            
                            is com.lifecyclebot.v3.V3Decision.Rejected -> {
                                // ═══════════════════════════════════════════════════════════════════
                                // V3 REJECT: Poor setup
                                // ═══════════════════════════════════════════════════════════════════
                                ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | REJECT | ${result.reason}")
                                
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
                                
                                return@launch
                            }
                            
                            is com.lifecyclebot.v3.V3Decision.BlockFatal -> {
                                // ═══════════════════════════════════════════════════════════════════
                                // V3 BLOCK_FATAL: Fatal risk detected
                                // ═══════════════════════════════════════════════════════════════════
                                ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | BLOCK_FATAL | ${result.reason}")
                                return@launch
                            }
                            
                            is com.lifecyclebot.v3.V3Decision.Blocked -> {
                                // ═══════════════════════════════════════════════════════════════════
                                // V3 BLOCK: Legacy fatal block
                                // ═══════════════════════════════════════════════════════════════════
                                ErrorLogger.info("BotService", "[DECISION] ${identity.symbol} | BLOCK_FATAL | ${result.reason}")
                                return@launch
                            }
                            
                            else -> {
                                // V3 not ready or error - skip this token
                                ErrorLogger.debug("BotService", "[V3] ${identity.symbol} | NOT_READY")
                                return@launch
                            }
                        }
                        
                    } catch (v3e: Exception) {
                        ErrorLogger.error("BotService", "[V3] ${identity.symbol} | ERROR | ${v3e.message}")
                        return@launch
                    }
                    
                    // V3 handled this token - skip legacy flow
                    return@launch
                }
                
                // ═══════════════════════════════════════════════════════════════════
                // LEGACY FALLBACK: Only runs if V3 is disabled
                // This path will be deprecated once V3 is fully validated
                // ═══════════════════════════════════════════════════════════════════
                
                // Legacy suppression penalty (for comparison logging)
                val suppressionPenalty = DistributionFadeAvoider.getSuppressionPenalty(identity.mint)
                
                // ───────────────────────────────────────────────────────────────────
                // HARD GATE: Block edge=SKIP or conf=0 BEFORE candidate promotion
                // This prevents garbage from going through CANDIDATE/PROPOSED/SIZING
                // ───────────────────────────────────────────────────────────────────
                val edgeVerdictStr = decision.edgeQuality  // "A", "B", "C", or "SKIP"
                val confValue = decision.aiConfidence
                
                if (edgeVerdictStr == "SKIP" || confValue <= 0) {
                    ErrorLogger.info("BotService", "[V3|PROMOTION_GATE] ${identity.symbol} | allow=false | " +
                        "reason=edge_${edgeVerdictStr.lowercase()}_conf_${confValue.toInt()} → SHADOW_ONLY")
                    
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
                    
                    return@launch  // Exit before CANDIDATE/PROPOSED
                }
                
                // ───────────────────────────────────────────────────────────────────
                // HARD GATE 2: Block C-grade + low confidence
                // ───────────────────────────────────────────────────────────────────
                val isCGrade = decision.setupQuality == "C" || decision.setupQuality == "D"
                if (isCGrade && confValue < 35) {
                    ErrorLogger.info("BotService", "[V3|PROMOTION_GATE] ${identity.symbol} | allow=false | " +
                        "reason=C_grade_conf_${confValue.toInt()}_below_35 → SHADOW_ONLY")
                    
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
                    
                    return@launch  // Exit before CANDIDATE/PROPOSED
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
                    val tradingModeTag = try {
                        modeConf?.mode?.let { ModeSpecificGates.fromBotMode(it) }
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
                                        return@launch  // V3 says WATCH = exit without executing
                                    }
                                }
                                
                                is com.lifecyclebot.v3.V3Decision.Rejected -> {
                                    val fdgTag = if (fdgDecision.canExecute()) "FDG:✓" else "FDG:✗"
                                    
                                    ErrorLogger.info("BotService", "⚡ V3 REJECT: ${identity.symbol} | " +
                                        "${result.reason} | $fdgTag")
                                    
                                    // Track comparison
                                    com.lifecyclebot.v3.V3EngineManager.recordDecisionComparison(
                                        v3Decision = "REJECT",
                                        fdgWouldExecute = fdgDecision.canExecute()
                                    )
                                    
                                    // V3 REJECT = DO NOT EXECUTE
                                    if (v3ControlsExecution) {
                                        addLog("⚡ V3 REJECT: ${identity.symbol} | ${result.reason}", mint)
                                        return@launch  // V3 says REJECT = exit
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
                                        return@launch  // V3 says BLOCK = exit
                                    }
                                }
                                
                                else -> {
                                    // Error or NotReady - fall back to FDG only if V3 is not controlling
                                    ErrorLogger.warn("BotService", "⚡ V3 unavailable for ${identity.symbol} - ${if (v3ControlsExecution) "SKIPPING" else "using FDG"}")
                                    if (v3ControlsExecution) {
                                        // V3 is supposed to control but failed - don't trade on uncertainty
                                        return@launch
                                    }
                                }
                            }
                            
                        } catch (v3e: Exception) {
                            ErrorLogger.error("BotService", "V3 engine error for ${identity.symbol}: ${v3e.message}")
                            if (v3ControlsExecution) {
                                // V3 controls but errored - don't fall back to legacy
                                return@launch
                            }
                        }
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
                        
                        if (!cbState.isHalted && !cbState.isPaused) {
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
                } else if (ts.position.isOpen) {
                    // Position management (exits) - ALWAYS monitor open positions
                    // Even when paused, we need to manage risk on existing positions
                    
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
              } // end scope.launch
            } // end map
            
            // Wait for all tokens with a maximum timeout of 15 seconds total
            // Increased from 8s to process more tokens per cycle
            try {
                kotlinx.coroutines.withTimeout(15000L) {
                    tokenJobs.forEach { it.join() }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                addLog("⏰ Watchlist scan timeout - moving to next cycle")
                ErrorLogger.warn("BotService", "Token processing batch timeout - some tokens skipped")
                // Cancel any still-running jobs
                tokenJobs.forEach { if (it.isActive) it.cancel() }
            }

            // ═══════════════════════════════════════════════════════════════════
            // SHADOW PAPER TRADING - Check shadow positions for exits
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
            
            // Periodically persist session state - use synchronized copy
            val tradeCount = synchronized(status.tokens) {
                status.tokens.values.toList().sumOf { it.trades.size }
            }
            if (tradeCount % 5 == 0 && status.running) {
                try { SessionStore.save(applicationContext, cfg.paperMode) } catch (_: Exception) {}
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
    private suspend fun cleanupWatchlist() {
        val cfg = ConfigStore.load(applicationContext)
        val currentWatchlist = cfg.watchlist.toMutableList()
        if (currentWatchlist.size <= 3) return  // Keep at least 3 tokens
        
        val tokensToRemove = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val isPaperMode = cfg.paperMode
        
        // MUCH LESS AGGRESSIVE - give tokens time to develop and generate signals
        val staleThresholdMs = if (isPaperMode) 120_000L else 180_000L   // 2 min in paper, 3 min in real
        val idleThresholdMs = if (isPaperMode) 180_000L else 300_000L    // 3 min idle in paper, 5 min in real
        val maxWatchlistAge = if (isPaperMode) 600_000L else 900_000L    // 10 min max in paper, 15 min in real
        
        for (mint in currentWatchlist) {
            val ts = status.tokens[mint]
            
            // Skip if we have an open position
            if (ts?.position?.isOpen == true) {
                continue
            }
            
            // Remove if blocked by safety checker
            if (ts?.safety?.isBlocked == true) {
                tokensToRemove.add(mint)
                val reason = ts.safety.hardBlockReasons.firstOrNull() ?: "Safety check failed"
                addLog("🚫 BLOCKED: ${ts.symbol} - $reason", mint)
                marketScanner?.markTokenRejected(mint)
                continue
            }
            
            // Remove if explicitly blacklisted
            if (TokenBlacklist.isBlocked(mint)) {
                val reason = TokenBlacklist.getBlockReason(mint)
                tokensToRemove.add(mint)
                addLog("🚫 BLACKLIST: ${ts?.symbol ?: mint.take(8)} - $reason", mint)
                marketScanner?.markTokenRejected(mint)
                continue
            }
            
            // Check token state
            if (ts != null) {
                val lastUpdate = ts.history.lastOrNull()?.ts ?: ts.addedToWatchlistAt
                val age = now - lastUpdate
                val timeInWatchlist = now - ts.addedToWatchlistAt
                
                // Remove "idle" phase tokens ONLY if they've been idle for a while
                // Give them time to transition to a better phase
                if (ts.phase == "idle" && timeInWatchlist > idleThresholdMs && ts.history.size < 5) {
                    tokensToRemove.add(mint)
                    addLog("😴 IDLE PHASE: ${ts.symbol} - no activity", mint)
                    marketScanner?.markTokenRejected(mint)
                    continue
                }
                
                // AGGRESSIVE: Remove "dying", "dead", "rug_likely" phases immediately
                if (ts.phase in listOf("dying", "dead", "rug_likely", "distribution")) {
                    tokensToRemove.add(mint)
                    addLog("💀 BAD PHASE: ${ts.symbol} (${ts.phase})", mint)
                    marketScanner?.markTokenRejected(mint)
                    continue
                }
                
                // Remove if stale (no data for 30 seconds)
                if (lastUpdate > 0 && age > staleThresholdMs) {
                    tokensToRemove.add(mint)
                    addLog("⏰ STALE: ${ts.symbol}", mint)
                    marketScanner?.markTokenRejected(mint)
                    continue
                }
                
                // Remove if dead (very low liquidity) - but keep if we're in paper mode learning
                if (ts.lastLiquidityUsd < 200 && !isPaperMode) {
                    tokensToRemove.add(mint)
                    addLog("💀 NO LIQ: ${ts.symbol}", mint)
                    marketScanner?.markTokenRejected(mint)
                    continue
                }
                
                // Remove any token after max time if no trade executed
                // But be generous - tokens need time to develop
                if (timeInWatchlist > maxWatchlistAge && ts.trades.isEmpty()) {
                    tokensToRemove.add(mint)
                    addLog("⏳ TIMEOUT: ${ts.symbol} - ${(maxWatchlistAge/60000)}min no trade", mint)
                    marketScanner?.markTokenRejected(mint)
                    continue
                }
                
                // Remove if WAIT signal for too long - but give more time in paper mode
                val waitTimeout = if (isPaperMode) 300_000L else 120_000L  // 5 min in paper, 2 min in real
                if (ts.signal == "WAIT" && timeInWatchlist > waitTimeout && ts.trades.isEmpty()) {
                    tokensToRemove.add(mint)
                    addLog("⏳ WAIT TIMEOUT: ${ts.symbol}", mint)
                    marketScanner?.markTokenRejected(mint)
                    continue
                }
                
                // Remove if flat - but only after enough candles and in real mode
                // Paper mode keeps flat tokens to learn from them too
                if (!isPaperMode && ts.history.size >= 6) {
                    val recentCandles = ts.history.takeLast(6)
                    val priceRange = recentCandles.maxOf { it.priceUsd } - recentCandles.minOf { it.priceUsd }
                    val avgPrice = recentCandles.map { it.priceUsd }.average()
                    val priceChangePercent = if (avgPrice > 0) (priceRange / avgPrice) * 100 else 0.0
                    val totalBuys = recentCandles.sumOf { it.buysH1 }
                    
                    // Flat price (<1.5% range) AND no recent buys
                    if (priceChangePercent < 1.5 && totalBuys < 2) {
                        tokensToRemove.add(mint)
                        addLog("📉 FLAT: ${ts.symbol}", mint)
                        marketScanner?.markTokenRejected(mint)
                        continue
                    }
                }
            } else {
                // No token state - remove it
                tokensToRemove.add(mint)
            }
        }
        
        // Apply removals
        if (tokensToRemove.isNotEmpty()) {
            val newWatchlist = currentWatchlist.filter { it !in tokensToRemove }
            ConfigStore.save(applicationContext, cfg.copy(watchlist = newWatchlist))
            
            // Also remove from status.tokens
            tokensToRemove.forEach { mint ->
                synchronized(status.tokens) {
                    status.tokens.remove(mint)
                }
            }
            
            ErrorLogger.info("BotService", "Watchlist cleanup: removed ${tokensToRemove.size} tokens, now ${newWatchlist.size} remaining")
            addLog("🧹 Cleanup: -${tokensToRemove.size} | now ${newWatchlist.size}")
        }
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
            val intent = Intent(this, MainActivity::class.java)
            val pi     = PendingIntent.getActivity(this, 0, intent,
                             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif  = NotificationCompat.Builder(this, CHANNEL_TRADE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
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
}

// Extension function for formatting doubles
private fun Double.fmt(decimals: Int = 4) = "%.${decimals}f".format(this)
// Build trigger 1774627618
