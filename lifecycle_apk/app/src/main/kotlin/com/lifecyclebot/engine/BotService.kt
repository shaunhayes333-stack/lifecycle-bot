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

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        createChannels()

        strategy        = LifecycleStrategy(
            cfg   = { ConfigStore.load(applicationContext) },
            brain = { botBrain },
        )
        sentimentEngine = SentimentEngine { ConfigStore.load(applicationContext) }
        safetyChecker   = TokenSafetyChecker { ConfigStore.load(applicationContext) }
        walletManager   = WalletManager(applicationContext)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBot()
            ACTION_STOP  -> stopBot()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Called when user swipes the app from the recent apps list.
     * Schedules a restart via a pending intent so the foreground service
     * resumes automatically rather than dying silently.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (status.running) {
            // Re-schedule start in 2 seconds
            val restartIntent = Intent(applicationContext, BotService::class.java).apply {
                action = ACTION_START
            }
            val pi = android.app.PendingIntent.getService(
                this, 1, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.set(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 2_000,
                pi
            )
        }
    }

    // ── start / stop ───────────────────────────────────────

    fun startBot() {
        if (status.running) return
        
        try {
            addLog("🚀 Starting bot...")
            status.running = true
            startForeground(NOTIF_ID, buildRunningNotif())
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
            
            wallet = if (!cfg.paperMode && cfg.privateKeyB58.isNotBlank()) {
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
            } else {
                addLog("Paper mode enabled or no key provided")
                walletManager.disconnect()
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
                            executor.maybeAct(ts, "EXIT", 0.0, status.walletSol, wallet,
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
        if (scanCfg.fullMarketScanEnabled) {
            try {
                marketScanner = SolanaMarketScanner(
                    cfg          = { ConfigStore.load(applicationContext) },
                    onTokenFound = { mint, symbol, name, source, score ->
                        try {
                            val wl = cfg.watchlist.toMutableList()
                            if (mint !in wl && !TokenBlacklist.isBlocked(mint) && wl.size < cfg.maxWatchlistSize) {
                                wl.add(mint)
                                ConfigStore.save(applicationContext, cfg.copy(watchlist = wl))
                                addLog("📡 ${source.name}: auto-added ${symbol} (score ${score.toInt()})", mint)
                                soundManager.playNewToken()
                                // Seed candle history immediately
                                scope.launch {
                                    try {
                                        val ts = status.tokens.getOrPut(mint) {
                                            com.lifecyclebot.data.TokenState(
                                                mint=mint, symbol=symbol, name=name,
                                                candleTimeframeMinutes = 1
                                            )
                                        }
                                        orchestrator?.onTokenAdded(mint, symbol)
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    },
                    onLog = ::addLog,
                )
                marketScanner?.start()
                addLog("🌐 Full Solana market scanner active — ${scanCfg.maxWatchlistSize} token watchlist")
            } catch (e: Exception) {
                addLog("⚠️ Market scanner error: ${e.message}")
            }
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
        // Persist running state so BootReceiver can restart after reboot
        getSharedPreferences("bot_runtime", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("was_running_before_shutdown", true).apply()
        // Acquire partial wake lock — keeps CPU alive during transaction confirmation
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecyclebot:trading")
            .also { it.acquire(12 * 60 * 60 * 1000L) }  // max 12h, released on stopBot
        addLog("Bot started — paper=${cfg.paperMode} auto=${cfg.autoTrade} sounds=${cfg.soundEnabled}")
        
        } catch (e: Exception) {
            // Catch any crash and log it, don't let the service crash
            addLog("❌ Bot start error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            status.running = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
        }
    }

    fun stopBot() {
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
        walletManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        getSharedPreferences("bot_runtime", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("was_running_before_shutdown", false).apply()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        addLog("Bot stopped.")
    }

    // ── main loop ──────────────────────────────────────────

    private suspend fun botLoop() {
        while (status.running) {
          try {
            val cfg       = ConfigStore.load(applicationContext)
            val watchlist = cfg.watchlist.toMutableList()
            if (cfg.activeToken.isNotBlank() && cfg.activeToken !in watchlist)
                watchlist.add(cfg.activeToken)

            // Currency rate refresh + feed SOL price to bonding curve tracker
            scope.launch {
                try {
                    currencyManager.refreshIfStale()
                    val sol = currencyManager.getSolUsd()
                    if (sol > 0) BondingCurveTracker.updateSolPrice(sol)
                } catch (_: Exception) {}
            }

            // Balance + P&L refresh
            scope.launch {
                try {
                    walletManager.refreshBalance()
                    val freshSol = walletManager.state.value.solBalance
                    status.walletSol = freshSol

                    // Treasury milestone check — runs every poll cycle
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
                    // Gather all trades across all tokens for P&L
                    val allTrades = status.tokens.values.flatMap { it.trades }
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
                    // Fallback to Birdeye if Dex returns null (rate-limit / outage)
                    val pair = dex.getBestPair(mint) ?: run {
                        val ts = status.tokens[mint]
                        if (ts != null) {
                            try {
                                val cfg2  = ConfigStore.load(applicationContext)
                                val ov    = com.lifecyclebot.network.BirdeyeApi(cfg2.birdeyeApiKey)
                                    .getTokenOverview(mint)
                                if (ov != null && ov.priceUsd > 0) {
                                    synchronized(ts) {
                                        ts.lastPrice        = ov.priceUsd
                                        ts.lastLiquidityUsd = ov.liquidity
                                        ts.lastMcap         = ov.marketCap
                                    }
                                    addLog("📡 Birdeye fallback price for ${ts.symbol}: \$${ov.priceUsd}", mint)
                                }
                            } catch (_: Exception) {}
                        }
                        return@launch   // still skip full cycle — no candle data
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
                        executor.maybeAct(ts, "EXIT", 0.0, status.walletSol, wallet,
                            lastSuccessfulPollMs, status.openPositionCount, status.totalExposureSol)
                    }
                    return@launch
                }

                // In PAUSED mode: no new entries (existing positions still managed)
                if (modeConf?.mode == AutoModeEngine.BotMode.PAUSED && !ts.position.isOpen) return@launch

                // Trade on ALL watchlist tokens simultaneously
                val cbState = securityGuard.getCircuitBreakerState()
                if (!cbState.isHalted && !cbState.isPaused) {
                    executor.maybeAct(
                        ts                 = ts,
                        signal             = result.signal,
                        entryScore         = result.entryScore,
                        walletSol          = status.walletSol,
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
            tokenJobs.forEach { it.join() }  // wait for all tokens this cycle

            // Periodically persist session state
            if (status.tokens.values.sumOf { it.trades.size } % 5 == 0 && status.running) {
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
            .build()
    }
}

// Extension function for formatting doubles
private fun Double.fmt(decimals: Int = 4) = "%.${decimals}f".format(this)
