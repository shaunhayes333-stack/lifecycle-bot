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
            security  = securityGuard,
            sounds    = soundManager,
        )
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
                            }
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
                    onTokenFound = { mint, symbol, name, source, score ->
                        try {
                            val c = ConfigStore.load(applicationContext)
                            val wl = c.watchlist.toMutableList()
                            ErrorLogger.info("BotService", "Token found: $symbol ($mint) from ${source.name} score=$score")
                            
                            if (mint in wl) {
                                ErrorLogger.debug("BotService", "Token $symbol already in watchlist")
                                return@SolanaMarketScanner
                            }
                            
                            if (TokenBlacklist.isBlocked(mint)) {
                                ErrorLogger.debug("BotService", "Token $symbol is blacklisted")
                                return@SolanaMarketScanner
                            }
                            
                            if (wl.size >= c.maxWatchlistSize) {
                                ErrorLogger.debug("BotService", "Watchlist full (${wl.size}/${c.maxWatchlistSize})")
                                return@SolanaMarketScanner
                            }
                            
                            wl.add(mint)
                            ConfigStore.save(applicationContext, c.copy(watchlist = wl))
                            addLog("✅ ADDED: ${symbol} (${source.name}) score=${score.toInt()} | Watchlist now: ${wl.size}", mint)
                            ErrorLogger.info("BotService", "Added $symbol to watchlist. New size: ${wl.size}")
                            soundManager.playNewToken()
                            
                            // Seed candle history immediately
                            scope.launch {
                                try {
                                    val ts = synchronized(status.tokens) {
                                        status.tokens.getOrPut(mint) {
                                            com.lifecyclebot.data.TokenState(
                                                mint=mint, symbol=symbol, name=name,
                                                candleTimeframeMinutes = 1
                                            )
                                        }
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
        // Restore session state from last run (streak, peak wallet)
        val restored = SessionStore.restore(applicationContext)
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
        addLog("📚 ${TradingMemory.getStats()}")
        
        // Initialize BannedTokens for permanent token bans
        BannedTokens.init(applicationContext)
        addLog("🚫 ${BannedTokens.getStats()}")
        
        // Initialize PatternAutoTuner for dynamic pattern weight adjustment
        PatternAutoTuner.init(applicationContext)
        addLog("🎛️ ${PatternAutoTuner.getStatus()}")
        
        // Initialize AdaptiveLearningEngine for feature-weighted scoring
        AdaptiveLearningEngine.init(applicationContext)
        addLog("🧬 ${AdaptiveLearningEngine.getStatus()}")
        
        // Initialize CloudLearningSync for community shared learning
        CloudLearningSync.init(applicationContext)
        addLog("☁️ ${CloudLearningSync.getStatus()}")
        
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
        
        addLog("Bot started — paper=${cfg.paperMode} auto=${cfg.autoTrade} sounds=${cfg.soundEnabled}")
        ErrorLogger.info("BotService", "Bot started successfully")
        
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

            // Currency rate refresh + feed SOL price to bonding curve tracker
            scope.launch {
                try {
                    currencyManager.refreshIfStale()
                    val sol = currencyManager.getSolUsd()
                    if (sol > 0) BondingCurveTracker.updateSolPrice(sol)
                } catch (_: Exception) {}
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
                
                // Log cloud sync status (every ~35 mins = 5x7 loops)
                if (loopCount % 35 == 0) {
                    addLog("☁️ ${CloudLearningSync.getStatus()}")
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
                    
                    // Initialize paper wallet with real balance on first successful refresh
                    val cfg = ConfigStore.load(applicationContext)
                    if (cfg.paperMode && !status.paperWalletInitialized && freshSol > 0) {
                        status.paperWalletSol = freshSol
                        status.paperWalletInitialized = true
                        ErrorLogger.info("PaperWallet", "Initialized with real balance: ${freshSol} SOL")
                        addLog("📝 Paper wallet synced: ${freshSol.fmt(4)} SOL")
                    }

                    // Treasury milestone check — runs every poll cycle
                    val solPx = WalletManager.lastKnownSolPrice
                    TreasuryManager.onWalletUpdate(
                        walletSol    = if (cfg.paperMode) status.paperWalletSol else freshSol,
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
                    // Gather all trades across all tokens for P&L - use synchronized copy
                    val allTrades = synchronized(status.tokens) {
                        status.tokens.values.toList().flatMap { it.trades.toList() }
                    }
                    walletManager.updatePnl(allTrades)
                } catch (_: Exception) {}
            }


            var lastSuccessfulPollMs = System.currentTimeMillis()

            // Process all tokens in parallel — each gets its own coroutine.
            // This reduces per-cycle latency from (N×50ms + pollSeconds) to just pollSeconds.
            val tokenJobs = watchlist.map { mint ->
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
                            )
                        }
                        val ts = status.tokens[mint] ?: return@launch
                        ts.lastPrice        = pair.candle.priceUsd
                        ts.lastMcap         = pair.candle.marketCap
                        ts.lastLiquidityUsd = pair.liquidity
                        ts.lastFdv          = pair.fdv
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
                    // Skip permanently banned tokens immediately
                    // ═══════════════════════════════════════════════════════════════════
                    if (BannedTokens.isBanned(mint)) {
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
                
                // Auto-mode evaluation - must come before strategy.evaluate
                val curveState = BondingCurveTracker.evaluate(ts)
                val whaleState = WhaleDetector.evaluate(mint, ts)
                val trendRank  = try { null } catch (_: Exception) { null } // from CoinGecko cache
                val modeConf   = if (cfg.autoMode) {
                    autoMode.evaluate(ts, whaleState.whaleScore, trendRank, curveState.stage)
                } else null
                
                val modeConfForEval = if (cfg.autoMode) modeConf else null
                val result = strategy.evaluate(ts, modeConfForEval)

                    synchronized(ts) {
                        ts.phase      = result.phase
                        ts.signal     = result.signal
                        ts.entryScore = result.entryScore
                        ts.exitScore  = result.exitScore
                        ts.meta       = result.meta
                    }
                    
                    // Log trading signals for active analysis
                    if (result.signal == "BUY" || result.entryScore >= 35) {
                        ErrorLogger.info("BotService", 
                            "SIGNAL: ${ts.symbol} | phase=${result.phase} signal=${result.signal} " +
                            "entry=${result.entryScore.toInt()} exit=${result.exitScore.toInt()}")
                    }

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
                
                // DEBUG: Log when BUY signal is received
                if (result.signal == "BUY") {
                    ErrorLogger.info("BotService", "🔔 BUY SIGNAL: ${ts.symbol} | balance=$effectiveBalance | paper=${cfg.paperMode} | entry=${result.entryScore.toInt()}")
                }
                
                if (!cbState.isHalted && !cbState.isPaused) {
                    executor.maybeAct(
                        ts                 = ts,
                        signal             = result.signal,
                        entryScore         = result.entryScore,
                        walletSol          = effectiveBalance,
                        wallet             = wallet,
                        lastPollMs         = lastSuccessfulPollMs,
                        openPositionCount  = status.openPositionCount,   // informational only
                        totalExposureSol   = status.totalExposureSol,   // passed to SmartSizer
                        modeConfig         = modeConf,
                        walletTotalTrades  = try {
                            com.lifecyclebot.engine.BotService.walletManager
                                ?.state?.value?.totalTrades ?: 0
                        } catch (_: Exception) { 0 },
                    )
                } else if (cbState.isHalted) {
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
            
            // Wait for all tokens with a maximum timeout of 8 seconds total
            // This prevents the watchlist from hanging on slow API calls
            try {
                kotlinx.coroutines.withTimeout(8000L) {
                    tokenJobs.forEach { it.join() }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                addLog("⏰ Watchlist scan timeout - moving to next cycle")
                ErrorLogger.warn("BotService", "Token processing batch timeout - some tokens skipped")
                // Cancel any still-running jobs
                tokenJobs.forEach { if (it.isActive) it.cancel() }
            }

            // Periodically persist session state - use synchronized copy
            val tradeCount = synchronized(status.tokens) {
                status.tokens.values.toList().sumOf { it.trades.size }
            }
            if (tradeCount % 5 == 0 && status.running) {
                try { SessionStore.save(applicationContext) } catch (_: Exception) {}
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
            
            ErrorLogger.info("BotService", "Watchlist cleanup: removed ${tokensToRemove.size} tokens, ${newWatchlist.size} remaining")
            addLog("🧹 Cleaned ${tokensToRemove.size} | Remaining: ${newWatchlist.size}")
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

    private fun sendTradeNotif(title: String, body: String,
            type: NotificationHistory.NotifEntry.NotifType = NotificationHistory.NotifEntry.NotifType.INFO) {
        notifHistory.add(title, body, type)
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
        // Mirror to Telegram if configured (fire-and-forget, background thread)
        val cfg = try { com.lifecyclebot.data.ConfigStore.load(applicationContext) }
            catch (_: Exception) { return }
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
            .setContentTitle("Lifecycle Bot")
            .setContentText("Running — tap to open")
            .setOngoing(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // Default priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

// Extension function for formatting doubles
private fun Double.fmt(decimals: Int = 4) = "%.${decimals}f".format(this)
