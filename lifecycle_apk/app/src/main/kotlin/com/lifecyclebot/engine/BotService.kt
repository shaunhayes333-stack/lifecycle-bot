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
        
        // Initialize BehaviorAI from trade history
        try {
            com.lifecyclebot.v3.scoring.BehaviorAI.loadFromHistory()
            ErrorLogger.info("BotService", "BehaviorAI loaded from trade history")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "BehaviorAI load error: ${e.message}")
        }
        
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

        // ═══════════════════════════════════════════════════════════════════
        // V4.0: INITIALIZE GLOBALTRADEREGISTRY BEFORE SCANNER STARTS
        // This ensures all scanner discoveries go through the registry
        // ═══════════════════════════════════════════════════════════════════
        val preScanCfg = ConfigStore.load(applicationContext)
        GlobalTradeRegistry.init(preScanCfg.watchlist, "CONFIG_PRESCAN")
        addLog("📋 GlobalTradeRegistry initialized with ${GlobalTradeRegistry.size()} tokens")

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
                            
                            // V4.0: Check GlobalTradeRegistry instead of local watchlist
                            if (GlobalTradeRegistry.isWatching(identity.mint)) {
                                ErrorLogger.debug("BotService", "Token ${identity.symbol} already in GlobalTradeRegistry")
                                return@SolanaMarketScanner
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
                            val paperMinLiquidity = 3000.0   // $3K for paper exploration
                            val liveMinLiquidity = 8000.0    // $8K for live capital protection
                            val paperMinScore = 35.0          // Lower bar for learning
                            val liveMinScore = 65.0           // Higher bar for live execution
                            
                            // Check 2a: MINIMUM LIQUIDITY (mode-dependent)
                            val minLiquidity = if (c.paperMode) paperMinLiquidity else liveMinLiquidity
                            if (liquidityUsd < minLiquidity) {
                                TradeLifecycle.ineligible(identity.mint, "Liquidity too low: $${liquidityUsd.toInt()} < $${minLiquidity.toInt()}")
                                ErrorLogger.debug("BotService", "INELIGIBLE: ${identity.symbol} - liq $${liquidityUsd.toInt()} < $${minLiquidity.toInt()}")
                                return@SolanaMarketScanner
                            }
                            
                            // Check 2b: Blacklist (always check in live, optional in paper)
                            if (!c.paperMode && TokenBlacklist.isBlocked(identity.mint)) {
                                TradeLifecycle.ineligible(identity.mint, "Blacklisted")
                                ErrorLogger.debug("BotService", "INELIGIBLE: ${identity.symbol} - blacklisted")
                                return@SolanaMarketScanner
                            }
                            
                            // Check 2c: Minimum score threshold (mode-dependent)
                            val minScore = if (c.paperMode) paperMinScore else liveMinScore
                            if (score < minScore) {
                                TradeLifecycle.ineligible(identity.mint, "Score too low: $score < $minScore")
                                ErrorLogger.debug("BotService", "INELIGIBLE: ${identity.symbol} - score $score < $minScore")
                                return@SolanaMarketScanner
                            }
                            
                            // V4.20: Additional live-mode strictness
                            // In live mode, also require stronger fundamentals
                            if (!c.paperMode) {
                                // Reject very low scores even if above threshold but marginal
                                if (score < 70.0 && liquidityUsd < 12000.0) {
                                    TradeLifecycle.ineligible(identity.mint, "Live mode: marginal quality (score=$score, liq=$${liquidityUsd.toInt()})")
                                    ErrorLogger.debug("BotService", "INELIGIBLE (LIVE): ${identity.symbol} - marginal quality")
                                    return@SolanaMarketScanner
                                }
                            }
                            
                            // Mark as ELIGIBLE (passed all prereqs)
                            val modeLabel = if (c.paperMode) "PAPER" else "LIVE"
                            identity.eligible(score, "Passed $modeLabel eligibility")
                            TradeLifecycle.eligible(identity.mint, score, "[$modeLabel] liq=$${liquidityUsd.toInt()}, score=$score")
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // STAGE 3: WATCHLIST ADMISSION (capacity check)
                            // V4.0: Use GlobalTradeRegistry for capacity check
                            // ═══════════════════════════════════════════════════════════════════
                            
                            // Check 3a: Watchlist capacity via GlobalTradeRegistry
                            // V5.0: MUCH TIGHTER LIMITS in bootstrap mode
                            val currentWatchlistSize = GlobalTradeRegistry.size()
                            val learningProgress = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
                            val isBootstrap = learningProgress < 0.3  // First 30% of learning
                            
                            // Bootstrap: max 10 | Normal paper: max 30 | Live: user setting
                            val effectiveMaxWatchlist = when {
                                isBootstrap && c.paperMode -> 10  // V5.0: Tight budget during bootstrap
                                c.paperMode -> 30                  // Normal paper mode
                                else -> c.maxWatchlistSize         // Live mode uses user setting
                            }
                            
                            if (currentWatchlistSize >= effectiveMaxWatchlist) {
                                TradeLifecycle.filtered(identity.mint, "Watchlist full (${currentWatchlistSize}/${effectiveMaxWatchlist})")
                                ErrorLogger.debug("BotService", "FILTERED: ${identity.symbol} - watchlist full (bootstrap=$isBootstrap)")
                                return@SolanaMarketScanner
                            }
                            
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
                            )
                            
                            // Mark identity as queued (not yet watchlisted)
                            identity.eligible(score, "enqueued to merge queue")
                            
                            // Record scanner found a token (for staleness detection)
                            marketScanner?.recordNewTokenFound()
                            
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
                } catch (scEx: Exception) {
                    ErrorLogger.error("BotService", "Error closing ShitCoin positions: ${scEx.message}", scEx)
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
                val totalOpen = openCount + treasuryCount + blueChipCount + shitCoinCount
                
                if (totalOpen > 0) {
                    addLog("⚠️ $totalOpen position(s) left open (closePositionsOnStop=false) | " +
                        "main=$openCount treasury=$treasuryCount bluechip=$blueChipCount shitcoin=$shitCoinCount")
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
        
        // V4.0: Reset initialization flags so services can reinit on next start
        tradingModesInitialized = false
        allTradingLayersReady = false
        
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

    /**
     * RAPID STOP-LOSS MONITOR
     * Runs every 500ms to check ALL open positions against hard floor stop loss.
     * This catches catastrophic losses that the main loop (5-10sec cycle) might miss.
     */
    private suspend fun rapidStopLossMonitor() {
        ErrorLogger.info("BotService", "🛡️ Rapid Stop-Loss Monitor STARTED (500ms cycle)")
        
        val HARD_FLOOR_STOP_PCT = 15.0  // ABSOLUTE MAX LOSS - NEVER EXCEEDED
        val CHECK_INTERVAL_MS = 500L    // Check every 500ms
        
        while (status.running) {
            try {
                val cfg = ConfigStore.load(applicationContext)
                val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                
                // Get all open positions
                val openPositions = synchronized(status.tokens) {
                    status.tokens.values.filter { it.position.isOpen }.toList()
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
                        
                        // HARD FLOOR CHECK - IMMEDIATE EXIT
                        if (pnlPct <= -HARD_FLOOR_STOP_PCT) {
                            ErrorLogger.warn("BotService", "🚨 RAPID STOP: ${ts.symbol} at ${pnlPct.toInt()}% - HARD FLOOR HIT")
                            addLog("🛑 RAPID STOP: ${ts.symbol} ${pnlPct.toInt()}% | HARD FLOOR EXIT")
                            
                            // Execute immediate sell
                            executor.requestSell(
                                ts = ts,
                                reason = "RAPID_HARD_FLOOR_STOP",
                                wallet = wallet,
                                walletSol = effectiveBalance
                            )
                            
                            // Force cooldown
                            TradeStateMachine.startCooldown(ts.mint)
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
                        if (pnlPct <= dynamicStopPct && pnlPct > -HARD_FLOOR_STOP_PCT) {
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

    private suspend fun botLoop() {
        ErrorLogger.info("BotService", "botLoop() started")
        
        // START RAPID STOP-LOSS MONITOR IN PARALLEL
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            rapidStopLossMonitor()
        }
        
        var loopCount = 0
        while (status.running) {
          try {
            loopCount++
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: Clear FinalExecutionPermit state at start of each cycle
            // This allows tokens to be re-evaluated fresh each loop
            // ═══════════════════════════════════════════════════════════════════
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
            try {
                val mergedTokens = TokenMergeQueue.processQueue()
                for (merged in mergedTokens) {
                    // Check watchlist capacity
                    val currentSize = GlobalTradeRegistry.size()
                    val maxSize = if (cfg.paperMode) 100 else cfg.maxWatchlistSize
                    if (currentSize >= maxSize) {
                        ErrorLogger.debug("BotService", "MergeQueue: ${merged.symbol} skipped - watchlist full ($currentSize/$maxSize)")
                        continue
                    }
                    
                    // V5.0: Use addWithProbation for smarter routing
                    // Low confidence or single-source tokens go to probation first
                    val addResult = GlobalTradeRegistry.addWithProbation(
                        mint = merged.mint,
                        symbol = merged.symbol,
                        addedBy = merged.primaryScanner,
                        source = merged.allScanners.joinToString(","),
                        initialMcap = merged.marketCapUsd,
                        liquidityUsd = merged.liquidityUsd,
                        confidence = merged.confidence,
                        isMultiSource = merged.multiScannerBoost,
                        isEstimatedLiquidity = false,  // TODO: Track this in MergeQueue
                        price = 0.0,  // Will be updated when token data is fetched
                    )
                    
                    if (addResult.added) {
                        val newSize = GlobalTradeRegistry.size()
                        val boostLabel = if (merged.multiScannerBoost) " [MULTI-SCANNER]" else ""
                        val scannersInfo = if (merged.allScanners.size > 1) 
                            " (${merged.allScanners.joinToString("+")})" else ""
                        
                        // Track in TradeLifecycle
                        TradeLifecycle.watchlisted(merged.mint, newSize, "merged: ${merged.primaryScanner}$scannersInfo")
                        
                        // Log to UI
                        addLog("📋 WATCHLISTED: ${merged.symbol} (${merged.primaryScanner})$boostLabel | liq=$${merged.liquidityUsd.toInt()} | conf=${merged.confidence} | #$newSize", merged.mint)
                        ErrorLogger.info("BotService", "WATCHLISTED: ${merged.symbol} | scanners=${merged.allScanners.size} | conf=${merged.confidence}")
                        
                        // Play sound for new token
                        soundManager.playNewToken()
                        
                        // Seed TokenState for the merged token
                        scope.launch {
                            try {
                                synchronized(status.tokens) {
                                    status.tokens.getOrPut(merged.mint) {
                                        com.lifecyclebot.data.TokenState(
                                            mint = merged.mint,
                                            symbol = merged.symbol,
                                            name = merged.symbol,
                                            candleTimeframeMinutes = 1,
                                            source = merged.allScanners.joinToString(","),
                                            logoUrl = "https://dd.dexscreener.com/ds-data/tokens/solana/${merged.mint}.png",
                                        )
                                    }
                                }
                                orchestrator?.onTokenAdded(merged.mint, merged.symbol)
                            } catch (_: Exception) {}
                        }
                    } else if (addResult.probation) {
                        // V5.0: Token went to probation
                        addLog("⏳ PROBATION: ${merged.symbol} | ${addResult.reason}", merged.mint)
                    }
                }
                
                // V5.0: Process probation tier - check for promotions/rejections
                val probationResults = GlobalTradeRegistry.processProbation()
                for (result in probationResults) {
                    when (result.action) {
                        "PROMOTED" -> {
                            addLog("✅ PROMOTED: ${result.symbol} | ${result.reason}", result.mint)
                            soundManager.playNewToken()
                            
                            // Seed TokenState for promoted token
                            scope.launch {
                                try {
                                    synchronized(status.tokens) {
                                        status.tokens.getOrPut(result.mint) {
                                            com.lifecyclebot.data.TokenState(
                                                mint = result.mint,
                                                symbol = result.symbol,
                                                name = result.symbol,
                                                candleTimeframeMinutes = 1,
                                                source = "PROBATION",
                                                logoUrl = "https://dd.dexscreener.com/ds-data/tokens/solana/${result.mint}.png",
                                            )
                                        }
                                    }
                                    orchestrator?.onTokenAdded(result.mint, result.symbol)
                                } catch (_: Exception) {}
                            }
                        }
                        "REJECTED" -> {
                            addLog("❌ PROBATION_REJECT: ${result.symbol} | ${result.reason}", result.mint)
                        }
                    }
                }
                
                // Log merge queue stats every 30 cycles
                if (loopCount % 30 == 0 && TokenMergeQueue.getPendingCount() > 0) {
                    addLog("🔀 ${TokenMergeQueue.getStats()}")
                }
                
                // Log probation stats every 30 cycles
                if (loopCount % 30 == 0 && GlobalTradeRegistry.probationSize() > 0) {
                    addLog("⏳ ${GlobalTradeRegistry.getProbationStats()}")
                }
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "MergeQueue error: ${e.message}")
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: USE GLOBALTRADEREGISTRY WATCHLIST
            // Instead of cfg.watchlist which can reset, use the thread-safe registry
            // ═══════════════════════════════════════════════════════════════════
            val registryWatchlist = GlobalTradeRegistry.getWatchlist()
            val effectiveWatchlist = if (registryWatchlist.isNotEmpty()) {
                registryWatchlist
            } else {
                // Fallback to config watchlist if registry is empty
                watchlist
            }
            
            val tokenJobs = prioritizedWatchlist.map { mint ->
              scope.launch {
                if (!status.running) return@launch
                if (orchestrator?.shouldPoll(mint) == false) return@launch
                processTokenCycle(mint, cfg, wallet, lastSuccessfulPollMs)
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
        // V4.0: Use GlobalTradeRegistry as the source of truth
        val registryWatchlist = GlobalTradeRegistry.getWatchlist()
        if (registryWatchlist.size <= 3) return  // Keep at least 3 tokens
        
        val cfg = ConfigStore.load(applicationContext)
        val tokensToRemove = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val isPaperMode = cfg.paperMode
        
        // MUCH LESS AGGRESSIVE - give tokens time to develop and generate signals
        val staleThresholdMs = if (isPaperMode) 120_000L else 180_000L   // 2 min in paper, 3 min in real
        val idleThresholdMs = if (isPaperMode) 180_000L else 300_000L    // 3 min idle in paper, 5 min in real
        val maxWatchlistAge = if (isPaperMode) 600_000L else 900_000L    // 10 min max in paper, 15 min in real
        
        for (mint in registryWatchlist) {
            val ts = status.tokens[mint]
            
            // Skip if we have an open position (check both status.tokens AND GlobalTradeRegistry)
            if (ts?.position?.isOpen == true || GlobalTradeRegistry.hasOpenPosition(mint)) {
                continue
            }
            
            // Remove if blocked by safety checker
            if (ts?.safety?.isBlocked == true) {
                tokensToRemove.add(mint)
                val reason = ts.safety.hardBlockReasons.firstOrNull() ?: "Safety check failed"
                addLog("🚫 BLOCKED: ${ts.symbol} - $reason", mint)
                marketScanner?.markTokenRejected(mint)
                // V4.0: Register rejection in GlobalTradeRegistry
                GlobalTradeRegistry.registerRejection(mint, ts.symbol, reason, "SAFETY_CHECK")
                continue
            }
            
            // Remove if explicitly blacklisted
            if (TokenBlacklist.isBlocked(mint)) {
                val reason = TokenBlacklist.getBlockReason(mint)
                tokensToRemove.add(mint)
                addLog("🚫 BLACKLIST: ${ts?.symbol ?: mint.take(8)} - $reason", mint)
                marketScanner?.markTokenRejected(mint)
                GlobalTradeRegistry.registerRejection(mint, ts?.symbol ?: mint.take(8), reason, "BLACKLIST")
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
                    GlobalTradeRegistry.registerRejection(mint, ts.symbol, "idle_phase", "CLEANUP")
                    continue
                }
                
                // AGGRESSIVE: Remove "dying", "dead", "rug_likely" phases immediately
                if (ts.phase in listOf("dying", "dead", "rug_likely", "distribution")) {
                    tokensToRemove.add(mint)
                    addLog("💀 BAD PHASE: ${ts.symbol} (${ts.phase})", mint)
                    marketScanner?.markTokenRejected(mint)
                    GlobalTradeRegistry.registerRejection(mint, ts.symbol, ts.phase, "CLEANUP")
                    continue
                }
                
                // Remove if stale (no data for 30 seconds)
                if (lastUpdate > 0 && age > staleThresholdMs) {
                    tokensToRemove.add(mint)
                    addLog("⏰ STALE: ${ts.symbol}", mint)
                    marketScanner?.markTokenRejected(mint)
                    GlobalTradeRegistry.registerRejection(mint, ts.symbol, "stale", "CLEANUP")
                    continue
                }
                
                // Remove if dead (very low liquidity) - but keep if we're in paper mode learning
                if (ts.lastLiquidityUsd < 200 && !isPaperMode) {
                    tokensToRemove.add(mint)
                    addLog("💀 NO LIQ: ${ts.symbol}", mint)
                    marketScanner?.markTokenRejected(mint)
                    GlobalTradeRegistry.registerRejection(mint, ts.symbol, "no_liquidity", "CLEANUP")
                    continue
                }
                
                // Remove any token after max time if no trade executed
                // But be generous - tokens need time to develop
                if (timeInWatchlist > maxWatchlistAge && ts.trades.isEmpty()) {
                    tokensToRemove.add(mint)
                    addLog("⏳ TIMEOUT: ${ts.symbol} - ${(maxWatchlistAge/60000)}min no trade", mint)
                    marketScanner?.markTokenRejected(mint)
                    GlobalTradeRegistry.registerRejection(mint, ts.symbol, "timeout", "CLEANUP")
                    continue
                }
                
                // Remove if WAIT signal for too long - but give more time in paper mode
                val waitTimeout = if (isPaperMode) 300_000L else 120_000L  // 5 min in paper, 2 min in real
                if (ts.signal == "WAIT" && timeInWatchlist > waitTimeout && ts.trades.isEmpty()) {
                    tokensToRemove.add(mint)
                    addLog("⏳ WAIT TIMEOUT: ${ts.symbol}", mint)
                    marketScanner?.markTokenRejected(mint)
                    GlobalTradeRegistry.registerRejection(mint, ts.symbol, "wait_timeout", "CLEANUP")
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
                        GlobalTradeRegistry.registerRejection(mint, ts.symbol, "flat", "CLEANUP")
                        continue
                    }
                }
            } else {
                // No token state - remove it
                tokensToRemove.add(mint)
            }
        }
        
        // V4.0: Apply removals via GlobalTradeRegistry (NOT ConfigStore directly)
        if (tokensToRemove.isNotEmpty()) {
            for (mint in tokensToRemove) {
                GlobalTradeRegistry.removeFromWatchlist(mint, "CLEANUP")
            }
            
            // Also remove from status.tokens
            tokensToRemove.forEach { mint ->
                synchronized(status.tokens) {
                    status.tokens.remove(mint)
                }
            }
            
            val newSize = GlobalTradeRegistry.size()
            ErrorLogger.info("BotService", "Watchlist cleanup: removed ${tokensToRemove.size} tokens, now $newSize remaining (GlobalTradeRegistry)")
            addLog("🧹 Cleanup: -${tokensToRemove.size} | now $newSize")
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
            // Primary price source: Dexscreener
            val pair = dex.getBestPair(mint) ?: run {
                val ts = status.tokens[mint]
                if (ts != null) tryFallbackPriceData(mint, ts)
                return  // skip full cycle — but we may have added fallback data
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
            val ts = status.tokens[mint] ?: return
            
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
            // Remove from watchlist to stop wasting resources
            synchronized(status.tokens) {
                status.tokens.remove(mint)
            }
            ErrorLogger.debug("BotService", "Removing banned token ${ts.symbol} from watchlist")
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

    // Note: lastSuccessfulPollMs update is handled in botLoop
    
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
            return  // Skip to next token (exit this coroutine)
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
    // V3 ENGINE: Process token through unified scoring
    // Strategy output (phase, entry/exit scores, quality) feeds into V3
    // V3 is the ONLY thing that decides EXECUTE/WATCH/REJECT
    // ═══════════════════════════════════════════════════════════════════
    if (!ts.position.isOpen && cfg.v3EngineEnabled && com.lifecyclebot.v3.V3EngineManager.isReady()) {
        
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
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.CashGenerationAI.isEnabled()) {
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
                        val shouldEnter = treasurySignal.shouldEnter || forceBootstrapEntry
                        
                        if (shouldEnter) {
                            // V4.1: Apply bootstrap size multiplier for micro-positions
                            val bootstrapMultiplier = com.lifecyclebot.v3.scoring.FluidLearningAI.getBootstrapSizeMultiplier()
                            val adjustedSize = (treasurySignal.positionSizeSol * bootstrapMultiplier).coerceAtLeast(0.01)
                            
                            // ═══════════════════════════════════════════════════════════════════
                            // V5.0: TRADE AUTHORIZER - MUST pass before ANY execution
                            // This is the SINGLE source of truth for execution permission
                            // Prevents post-execution gating drift (inokumi bug)
                            // ═══════════════════════════════════════════════════════════════════
                            val authResult = TradeAuthorizer.authorize(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                score = ts.lastV3Score ?: 0,
                                confidence = (ts.lastV3Confidence ?: 0).toDouble(),
                                quality = "C",  // Treasury doesn't have grade, use default
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
                                ErrorLogger.info("BotService", "💰 [TREASURY] ${ts.symbol} | ENTER$bootstrapTag | " +
                                    "size=${adjustedSize.fmt(3)} SOL (${(bootstrapMultiplier*100).toInt()}%) | " +
                                    "TP=${treasurySignal.takeProfitPct}% | " +
                                    "mode=${treasurySignal.mode}")
                                
                                // Execute treasury buy
                                executor.treasuryBuy(
                                    ts = ts,
                                    sizeSol = adjustedSize,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = treasurySignal.takeProfitPct,
                                    stopLossPct = treasurySignal.stopLossPct,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode
                                )
                                
                                // V5.0 FIX: Mark position as treasury so checkExit uses correct thresholds
                                ts.position.isTreasuryPosition = true
                                ts.position.tradingMode = "TREASURY"
                                ts.position.tradingModeEmoji = "💰"
                                
                                // Record treasury position
                                com.lifecyclebot.v3.scoring.CashGenerationAI.openPosition(
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    entryPrice = ts.ref,
                                    positionSol = adjustedSize,
                                    takeProfitPct = treasurySignal.takeProfitPct,
                                    stopLossPct = treasurySignal.stopLossPct
                                )
                                
                                // Release permit after successful execution
                                FinalExecutionPermit.releaseExecution(ts.mint)
                                
                                // V4.1: Record trade for learning
                                com.lifecyclebot.v3.scoring.FluidLearningAI.recordTradeStart()
                                
                                val bootstrapLabel = if (forceBootstrapEntry) " [BOOTSTRAP]" else ""
                                addLog("💰 TREASURY BUY$bootstrapLabel: ${ts.symbol} | ${adjustedSize.fmt(3)} SOL | " +
                                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
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
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && ts.lastMcap >= 1_000_000) {
                try {
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
                        
                        if (blueChipSignal.shouldEnter) {
                            // V4.0: Try to acquire execution permit
                            val canExecute = FinalExecutionPermit.tryAcquireExecution(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                layer = "BLUE_CHIP",
                                sizeSol = blueChipSignal.positionSizeSol
                            )
                            
                            if (canExecute) {
                                ErrorLogger.info("BotService", "🔵 [BLUE CHIP] ${ts.symbol} | ENTER | " +
                                    "mcap=\$${(ts.lastMcap/1_000_000).fmt(2)}M | " +
                                    "size=${blueChipSignal.positionSizeSol.fmt(3)} SOL | " +
                                    "TP=${blueChipSignal.takeProfitPct}%")
                                
                                // Execute Blue Chip buy
                                executor.blueChipBuy(
                                    ts = ts,
                                    sizeSol = blueChipSignal.positionSizeSol,
                                    walletSol = effectiveBalance,
                                    takeProfitPct = blueChipSignal.takeProfitPct,
                                    stopLossPct = blueChipSignal.stopLossPct,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode
                                )
                                
                                // Record Blue Chip position
                                com.lifecyclebot.v3.scoring.BlueChipTraderAI.addPosition(
                                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.BlueChipPosition(
                                        mint = ts.mint,
                                        symbol = ts.symbol,
                                        entryPrice = ts.ref,
                                        entrySol = blueChipSignal.positionSizeSol,
                                        entryTime = System.currentTimeMillis(),
                                        marketCapUsd = ts.lastMcap,
                                        liquidityUsd = ts.lastLiquidityUsd,
                                        isPaper = cfg.paperMode,
                                        takeProfitPct = blueChipSignal.takeProfitPct,
                                        stopLossPct = blueChipSignal.stopLossPct
                                    )
                                )
                                
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
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.MoonshotTraderAI.isEnabled()) {
                try {
                    // Check if mcap is in moonshot zone ($100K-$50M)
                    if (ts.lastMcap in 100_000.0..50_000_000.0) {
                        
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
                            
                            val moonshotScore = com.lifecyclebot.v3.scoring.MoonshotTraderAI.scoreToken(
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
                            
                            if (moonshotScore.eligible) {
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
                                                "TP=${moonshotScore.takeProfitPct.toInt()}% SL=${moonshotScore.stopLossPct.toInt()}%")
                                            
                                            // Execute moonshot entry
                                            executor.paperBuy(
                                                ts = ts,
                                                sol = moonshotScore.suggestedSizeSol,
                                                score = moonshotScore.score.toDouble(),
                                                identity = identity,
                                                quality = moonshotScore.spaceMode.displayName,
                                                skipGraduated = true,
                                                wallet = wallet,
                                                walletSol = effectiveBalance
                                            )
                                            
                                            // Register with MoonshotTraderAI
                                            com.lifecyclebot.v3.scoring.MoonshotTraderAI.addPosition(
                                                com.lifecyclebot.v3.scoring.MoonshotTraderAI.MoonshotPosition(
                                                    mint = ts.mint,
                                                    symbol = ts.symbol,
                                                    entryPrice = ts.ref,
                                                    entrySol = moonshotScore.suggestedSizeSol,
                                                    entryTime = System.currentTimeMillis(),
                                                    takeProfitPct = moonshotScore.takeProfitPct,
                                                    stopLossPct = moonshotScore.stopLossPct,
                                                    marketCapUsd = ts.lastMcap,
                                                    liquidityUsd = ts.lastLiquidityUsd,
                                                    entryScore = moonshotScore.score.toDouble(),
                                                    spaceMode = moonshotScore.spaceMode,
                                                    isPaperMode = cfg.paperMode,
                                                    isCollectiveWinner = moonshotScore.isCollectiveBoost,
                                                )
                                            )
                                            
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
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.ShitCoinTraderAI.isEnabled()) {
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
                        
                        val shitCoinSignal = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.evaluate(
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
                            hasWebsite = ts.symbol.length > 2,  // Heuristic: longer symbols often have more presence
                            hasTwitter = socialScore >= 20,      // Infer from social score
                            hasTelegram = socialScore >= 35,     // Infer from social score
                            hasGithub = false,  // Not tracked yet
                            isDexBoosted = isDexBoosted,
                            dexTrendingRank = dexTrendingRank,
                            isCopyCat = isCopyCat,
                            graduationProgress = graduationProgress,
                        )
                        
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
                        val shouldEnter = shitCoinSignal.shouldEnter || forceBootstrapEntry
                        
                        if (shouldEnter) {
                            // V4.1: Apply bootstrap size multiplier for micro-positions
                            val bootstrapMultiplier = com.lifecyclebot.v3.scoring.FluidLearningAI.getBootstrapSizeMultiplier()
                            val adjustedSize = (shitCoinSignal.positionSizeSol * bootstrapMultiplier).coerceAtLeast(0.01)
                            
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
                                    takeProfitPct = shitCoinSignal.takeProfitPct,
                                    stopLossPct = shitCoinSignal.stopLossPct,
                                    wallet = wallet,
                                    isPaper = cfg.paperMode,
                                    launchPlatform = shitCoinSignal.launchPlatform,
                                    riskLevel = shitCoinSignal.riskLevel,
                                )
                                
                                // Record ShitCoin position
                                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.addPosition(
                                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinPosition(
                                        mint = ts.mint,
                                        symbol = ts.symbol,
                                        entryPrice = ts.ref,
                                        entrySol = adjustedSize,
                                        entryTime = System.currentTimeMillis(),
                                        marketCapUsd = ts.lastMcap,
                                        liquidityUsd = ts.lastLiquidityUsd,
                                        isPaper = cfg.paperMode,
                                        takeProfitPct = shitCoinSignal.takeProfitPct,
                                        stopLossPct = shitCoinSignal.stopLossPct,
                                        launchPlatform = shitCoinSignal.launchPlatform,
                                        devWallet = null,  // Dev wallet tracking not yet implemented
                                        bundlePct = bundlePct,
                                        socialScore = shitCoinSignal.socialScore,
                                    )
                                )
                                
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
                    }
                } catch (scEx: Exception) {
                    ErrorLogger.debug("BotService", "💩 [SHITCOIN] ${ts.symbol} | ERROR | ${scEx.message}")
                    FinalExecutionPermit.releaseExecution(ts.mint)
                }
            }
            // ═══════════════════════════════════════════════════════════════════
            // END ShitCoin evaluation
            // ═══════════════════════════════════════════════════════════════════
            
            // ═══════════════════════════════════════════════════════════════════
            // 💩🚂 SHITCOIN EXPRESS - Quick momentum rides for 30%+ profits
            // Only evaluates tokens that are ALREADY pumping hard
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.ShitCoinExpress.isEnabled()) {
                try {
                    val tokenAgeMinutes = if (ts.addedToWatchlistAt > 0) {
                        (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
                    } else 60.0
                    
                    // Express only for micro caps <$300K that are pumping
                    if (ts.lastMcap <= 300_000 && ts.lastMcap >= 5_000 &&
                        (ts.momentum ?: 0.0) >= 5.0 && ts.lastBuyPressurePct >= 60) {
                        
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
                            momentum = ts.momentum ?: 0.0,
                            buyPressurePct = ts.lastBuyPressurePct,
                            volumeChange = 1.5,  // Default estimate
                            priceChange5Min = priceChange5Min,
                            isTrending = isTrending,
                            isBoosted = isBoosted,
                            tokenAgeMinutes = tokenAgeMinutes,
                        )
                        
                        if (expressSignal.shouldRide) {
                            ErrorLogger.info("BotService", "💩🚂 [EXPRESS] ${ts.symbol} | RIDE | " +
                                "${expressSignal.rideType.emoji} ${expressSignal.rideType.name} | " +
                                "mom=${(ts.momentum ?: 0.0).fmt(1)}% | " +
                                "size=${expressSignal.positionSizeSol.fmt(3)} SOL | " +
                                "target=${expressSignal.estimatedGainPct.toInt()}%")
                            
                            // Board the express ride
                            com.lifecyclebot.v3.scoring.ShitCoinExpress.boardRide(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                entryPrice = ts.ref,
                                entrySol = expressSignal.positionSizeSol,
                                momentum = ts.momentum ?: 0.0,
                                buyPressure = ts.lastBuyPressurePct,
                                isPaper = cfg.paperMode,
                            )
                            
                            // Execute buy via Executor
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
                            
                            addLog("💩🚂 EXPRESS: ${ts.symbol} | ${expressSignal.rideType.emoji} | " +
                                "target +${expressSignal.estimatedGainPct.toInt()}% | " +
                                "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
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
            // 📉🎯 DIP HUNTER - Buy quality dips on established tokens
            // ═══════════════════════════════════════════════════════════════════
            if (!ts.position.isOpen && com.lifecyclebot.v3.scoring.DipHunterAI.isEnabled()) {
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
                    
                    // ═══════════════════════════════════════════════════════════════════
                    // V3.3 FIX: DO NOT RETURN - Allow Treasury Mode evaluation below!
                    // Treasury Mode runs CONCURRENTLY and can scalp WATCH tokens
                    // ═══════════════════════════════════════════════════════════════════
                    // Previously: return (BLOCKED Treasury Mode!)
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
                    
                    return
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
                    
                    return
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
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.3 FIX: DO NOT RETURN HERE!
        // Treasury Mode runs CONCURRENTLY and must evaluate ALL tokens,
        // including those that V3 marked as WATCH/Execute.
        // Previously: return (this KILLED Treasury Mode entirely!)
        // ═══════════════════════════════════════════════════════════════════
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // NOTE: Treasury Mode now runs BEFORE the V3 when block (above)
    // to ensure it evaluates ALL tokens regardless of V3 decision.
    // ═══════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════
    // LEGACY FALLBACK: Only runs if V3 is disabled
    // This path will be deprecated once V3 is fully validated
    // ═══════════════════════════════════════════════════════════════════
    
    // Legacy suppression penalty (for comparison logging)
    val suppressionPenalty = DistributionFadeAvoider.getSuppressionPenalty(identity.mint)
    
    // ───────────────────────────────────────────────────────────────────
    // HARD GATE: Block edge=SKIP or conf=0 BEFORE candidate promotion
    // V5.2: Allow SKIP through during bootstrap (<20% learning) for V3 learning
    // This prevents garbage from going through CANDIDATE/PROPOSED/SIZING
    // ───────────────────────────────────────────────────────────────────
    val edgeVerdictStr = decision.edgeQuality  // "A", "B", "C", or "SKIP"
    val confValue = decision.aiConfidence
    
    // V5.2: Check if we're in bootstrap mode - allow SKIP trades for learning
    val learningProgress = try {
        com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
    } catch (_: Exception) { 0.0 }
    val isBootstrap = learningProgress < 0.20  // Under 20% = bootstrap mode
    
    // During bootstrap: Allow SKIP trades through for V3 learning (paper mode only)
    val allowSkipForLearning = isBootstrap && cfg.paperMode && edgeVerdictStr == "SKIP"
    
    if ((edgeVerdictStr == "SKIP" || confValue <= 0) && !allowSkipForLearning) {
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
        
        return  // Exit before CANDIDATE/PROPOSED
    }
    
    // Log when bootstrap override is used
    if (allowSkipForLearning) {
        ErrorLogger.info("BotService", "[V3|BOOTSTRAP] ${identity.symbol} | SKIP override | " +
            "learning=${(learningProgress * 100).toInt()}% | Allowing for V3 learning")
    }
    
    // ───────────────────────────────────────────────────────────────────
    // HARD GATE 2: Block C-grade + low confidence
    // V4.20: Lowered all floors by 8 points
    // V4.0: Use FLUID threshold instead of hardcoded 35%
    // At 12% learning, floor should be ~10% not 18%
    // ───────────────────────────────────────────────────────────────────
    val isCGrade = decision.setupQuality == "C" || decision.setupQuality == "D"
    val fluidCGradeConfFloor = try {
        val learningProgress = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        // V5.1: 7% at bootstrap → 20% at mature (lowered to allow more C-grade trades)
        (7 + (learningProgress * 13)).toInt().coerceIn(7, 20)
    } catch (_: Exception) { 10 }
    
    if (isCGrade && confValue < fluidCGradeConfFloor) {
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
        
        return  // Exit before CANDIDATE/PROPOSED
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
                            return  // V3 says WATCH = exit without executing
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
                            return  // V3 says REJECT = exit
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
        // LAYER TRANSITION CHECK - Upgrade positions on the way UP
        // Check if position should transition to a higher layer
        // ═══════════════════════════════════════════════════════════════════
        try {
            val currentPrice = ts.lastPrice.takeIf { it > 0 } 
                ?: ts.history.lastOrNull()?.priceUsd 
                ?: ts.position.entryPrice
            
            val transition = com.lifecyclebot.v3.scoring.LayerTransitionManager.checkTransition(
                mint = ts.mint,
                currentMcap = ts.lastMcap,
                currentPrice = currentPrice,
            )
            
            if (transition.shouldTransition) {
                // Update position's trading mode to new layer
                ts.position.tradingMode = transition.toLayer.displayName.uppercase().replace(" ", "_")
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
                
                // Record to FluidLearning as a positive signal
                try {
                    if (cfg.paperMode) {
                        com.lifecyclebot.v3.scoring.FluidLearningAI.recordPaperTrade(true)
                    } else {
                        com.lifecyclebot.v3.scoring.FluidLearningAI.recordLiveTrade(true)
                    }
                } catch (e: Exception) {}
            }
        } catch (transEx: Exception) {
            ErrorLogger.debug("BotService", "Layer transition check failed: ${transEx.message}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // TREASURY MODE EXIT CHECK - Quick scalps with tight exits
        // Check FIRST before other exit logic since treasury has strict rules
        // ═══════════════════════════════════════════════════════════════════
        if (ts.position.isTreasuryPosition || ts.position.tradingMode == "TREASURY") {
            val currentPrice = ts.lastPrice.takeIf { it > 0 } 
                ?: ts.history.lastOrNull()?.priceUsd 
                ?: ts.position.entryPrice
            
            // V5.2: Calculate current P&L for potential Moonshot promotion
            val currentPnlPct = if (ts.position.entryPrice > 0) {
                ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
            } else 0.0
            
            // V5.2: Check for cross-trade promotion to Moonshot (200%+ gains)
            if (currentPnlPct >= 200.0 && ts.lastMcap in 100_000.0..50_000_000.0) {
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
                
                // Execute treasury sell
                executor.requestSell(
                    ts = ts,
                    reason = "TREASURY_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                // Close treasury position tracking
                com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                    ts.mint, currentPrice, exitSignal
                )
                
                addLog("💰 TREASURY SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return  // Exit processed
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SHITCOIN MODE EXIT CHECK - Degen scalps with ultra-fast exits
        // Check SECOND after treasury since shitcoins need fast reactions
        // ═══════════════════════════════════════════════════════════════════
        if (ts.position.isShitCoinPosition || ts.position.tradingMode == "SHITCOIN") {
            val currentPrice = ts.lastPrice.takeIf { it > 0 } 
                ?: ts.history.lastOrNull()?.priceUsd 
                ?: ts.position.entryPrice
            
            // V5.2: Calculate current P&L for potential Moonshot promotion
            val currentPnlPct = if (ts.position.entryPrice > 0) {
                ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
            } else 0.0
            
            // V5.2: Check for cross-trade promotion to Moonshot (200%+ gains)
            // ShitCoin → Moonshot: The degen play turned into a moonshot!
            if (currentPnlPct >= 200.0 && ts.lastMcap in 100_000.0..50_000_000.0) {
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
            
            val exitSignal = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.checkExit(ts.mint, currentPrice)
            
            if (exitSignal != com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.RUG_DETECTED -> "💀"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.DEV_SELL -> "🚨"
                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.TAKE_PROFIT -> "🎯"
                    else -> "📉"
                }
                
                ErrorLogger.info("BotService", "💩 [SHITCOIN EXIT] ${ts.symbol} | " +
                    "signal=$exitSignal | price=$currentPrice")
                
                // Execute shitcoin sell
                executor.requestSell(
                    ts = ts,
                    reason = "SHITCOIN_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                // Close shitcoin position tracking
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(
                    ts.mint, currentPrice, exitSignal
                )
                
                addLog("$exitEmoji SHITCOIN SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return  // Exit processed
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 💩🚂 SHITCOIN EXPRESS EXIT CHECK
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.ShitCoinExpress.hasRide(ts.mint)) {
            val currentPrice = ts.lastPrice.takeIf { it > 0 } 
                ?: ts.history.lastOrNull()?.priceUsd 
                ?: ts.position.entryPrice
            val currentMomentum = ts.momentum ?: 0.0
            
            val exitSignal = com.lifecyclebot.v3.scoring.ShitCoinExpress.checkExit(
                ts.mint, currentPrice, currentMomentum
            )
            
            if (exitSignal != com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.HOLD) {
                val exitEmoji = when (exitSignal) {
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_100 -> "🚀"
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_50 -> "🚂"
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.TAKE_PROFIT_30 -> "⚡"
                    com.lifecyclebot.v3.scoring.ShitCoinExpress.ExitSignal.STOP_LOSS -> "💥"
                    else -> "📉"
                }
                
                executor.requestSell(
                    ts = ts,
                    reason = "EXPRESS_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                com.lifecyclebot.v3.scoring.ShitCoinExpress.exitRide(ts.mint, currentPrice, exitSignal)
                
                addLog("$exitEmoji EXPRESS SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 🌙 MOONSHOT EXIT CHECK
        // V5.2: Check for MOONSHOT prefix since mode includes space mode (MOONSHOT_ORBITAL, etc)
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(ts.mint) || 
            ts.position.tradingMode?.startsWith("MOONSHOT") == true) {
            val currentPrice = ts.lastPrice.takeIf { it > 0 } 
                ?: ts.history.lastOrNull()?.priceUsd 
                ?: ts.position.entryPrice
            
            val exitSignal = com.lifecyclebot.v3.scoring.MoonshotTraderAI.checkExit(ts.mint, currentPrice)
            
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
                
                executor.requestSell(
                    ts = ts,
                    reason = "MOONSHOT_${exitSignal.name}",
                    wallet = wallet,
                    walletSol = effectiveBalance
                )
                
                com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, currentPrice, exitSignal)
                
                addLog("$exitEmoji MOONSHOT SELL: ${ts.symbol} | ${exitSignal.name} | " +
                    "${if (cfg.paperMode) "PAPER" else "LIVE"}", ts.mint)
                
                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 📉🎯 DIP HUNTER EXIT CHECK
        // ═══════════════════════════════════════════════════════════════════
        if (com.lifecyclebot.v3.scoring.DipHunterAI.hasDip(ts.mint) || ts.position.tradingMode == "DIP_HUNTER") {
            val currentPrice = ts.lastPrice.takeIf { it > 0 } 
                ?: ts.history.lastOrNull()?.priceUsd 
                ?: ts.position.entryPrice
            
            val exitSignal = com.lifecyclebot.v3.scoring.DipHunterAI.checkExit(
                ts.mint, currentPrice, ts.lastLiquidityUsd
            )
            
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
        
        // Fluid Learning AI
        try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.init()
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
    private fun tryFallbackPriceData(mint: String, ts: TokenState): Boolean {
        // Try Birdeye first
        try {
            val cfg2 = ConfigStore.load(applicationContext)
            val ov = com.lifecyclebot.network.BirdeyeApi(cfg2.birdeyeApiKey).getTokenOverview(mint)
            if (ov != null && ov.priceUsd > 0) {
                synchronized(ts) {
                    ts.lastPrice = ov.priceUsd
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
                addLog("📡 Birdeye: ${ts.symbol} \$${ov.priceUsd}", mint)
                return true
            }
        } catch (_: Exception) {}
        
        // Try pump.fun API
        if (ts.lastPrice <= 0) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
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
                        val price = json.optDouble("price", 0.0)
                        if (mcap > 0 || price > 0) {
                            synchronized(ts) {
                                ts.lastPrice = price
                                ts.lastMcap = mcap
                                ts.lastFdv = mcap
                                ts.lastLiquidityUsd = mcap * 0.1
                                val syntheticCandle = com.lifecyclebot.data.Candle(
                                    ts = System.currentTimeMillis(), priceUsd = price,
                                    marketCap = mcap, volumeH1 = 0.0, volume24h = 0.0,
                                    buysH1 = 0, sellsH1 = 0, highUsd = price,
                                    lowUsd = price, openUsd = price,
                                )
                                synchronized(ts.history) {
                                    ts.history.addLast(syntheticCandle)
                                    if (ts.history.size > 300) ts.history.removeFirst()
                                }
                            }
                            addLog("🎯 Pump.fun: ${ts.symbol} mcap=\$${mcap.toInt()}", mint)
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
// Build trigger 1774627618
// Build trigger 1774842659
