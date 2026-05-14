package com.lifecyclebot.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.BotService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val running: Boolean = false,
    val walletSol: Double = 0.0,
    val activeToken: TokenState? = null,
    val tokens: Map<String, TokenState> = emptyMap(),
    val logs: List<String> = emptyList(),
    val config: BotConfig = BotConfig(),
    val walletState: com.lifecyclebot.engine.WalletState = com.lifecyclebot.engine.WalletState(),
    val circuitBreaker: com.lifecyclebot.engine.CircuitBreakerState = com.lifecyclebot.engine.CircuitBreakerState(),
    val auditLog: List<com.lifecyclebot.engine.AuditEntry> = emptyList(),
    // Multi-position summary
    val openPositions: List<TokenState> = emptyList(),
    val totalExposureSol: Double = 0.0,
    val totalUnrealisedPnlSol: Double = 0.0,
    val currentMode: com.lifecyclebot.engine.AutoModeEngine.BotMode =
        com.lifecyclebot.engine.AutoModeEngine.BotMode.RANGE,
    val modeReason: String = "",
    val blacklistedCount: Int = 0,
    val copyWallets: List<com.lifecyclebot.engine.CopyTradeEngine.CopyWallet> = emptyList(),
    // ═══════════════════════════════════════════════════════════════════
    // DASHBOARD DATA — Intelligence metrics for UI display
    // ═══════════════════════════════════════════════════════════════════
    val dashboardData: com.lifecyclebot.engine.DashboardDataProvider.QuickStats? = null,
    val marketSentiment: String = "UNKNOWN",
    val activeTradingModes: Int = 0,
    val totalInsights: Int = 0,
)

class BotViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    
    // Use singleton wallet manager - persists across all activities
    private val walletManager = com.lifecyclebot.engine.WalletManager.getInstance(ctx)

    init {
        viewModelScope.launch { 
            // Auto-reconnect wallet if credentials are saved
            autoReconnectWallet()
            pollLoop() 
        }
    }
    
    private suspend fun autoReconnectWallet() {
        try {
            val wm = com.lifecyclebot.engine.WalletManager.getInstance(ctx)
            // Only reconnect if not already connected
            if (wm.state.value.connectionState != com.lifecyclebot.engine.WalletConnectionState.CONNECTED) {
                val cfg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigStore.load(ctx) }
                if (cfg.privateKeyB58.isNotBlank()) {
                    com.lifecyclebot.engine.ErrorLogger.info("BotViewModel", "Auto-reconnecting wallet...")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        wm.connect(cfg.privateKeyB58, cfg.rpcUrl)
                    }
                }
            }
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.error("BotViewModel", "Auto-reconnect failed: ${e.message}", e)
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            // V5.9.696 — ConfigStore.load is a disk read; move it off the main/UI thread.
            val cfg    = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigStore.load(ctx) }
            val status = BotService.status
            
            // Auto-select token: prioritize configured activeToken, then any open position, then first watchlist token
            var active = status.tokens[cfg.activeToken]
            if (active == null) {
                // Try to find a token with an open position
                active = status.openPositions.firstOrNull()
                if (active == null && status.tokens.isNotEmpty()) {
                    // Fall back to first token in watchlist
                    active = status.tokens.values.firstOrNull()
                }
            }
            
            // Use singleton wallet manager - always the same instance
            val wm = com.lifecyclebot.engine.WalletManager.getInstance(ctx)
            val sg = try { com.lifecyclebot.engine.BotService.instance
                ?.let { svc ->
                    val f = svc.javaClass.getDeclaredField("securityGuard")
                    f.isAccessible = true
                    f.get(svc) as? com.lifecyclebot.engine.SecurityGuard
                } } catch (_: Exception) { null }
            _ui.value  = UiState(
                running      = status.running,
                walletSol    = status.walletSol,
                activeToken  = active,
                tokens       = status.tokens.toMap(),
                logs         = synchronized(status.logs) { status.logs.toList().takeLast(200) },
                config       = cfg,
                walletState    = wm.state.value,
                openPositions  = status.openPositions.toList(),
                currentMode    = try { com.lifecyclebot.engine.BotService.instance?.autoMode?.currentMode
                    ?: com.lifecyclebot.engine.AutoModeEngine.BotMode.RANGE } catch (_: Exception) {
                    com.lifecyclebot.engine.AutoModeEngine.BotMode.RANGE },
                modeReason     = try { com.lifecyclebot.engine.BotService.instance?.autoMode
                    ?.modeHistory?.firstOrNull()?.third ?: "" } catch (_: Exception) { "" },
                blacklistedCount = com.lifecyclebot.engine.TokenBlacklist.count,
                copyWallets    = try { com.lifecyclebot.engine.BotService.instance
                    ?.copyTradeEngine?.getWallets() ?: emptyList() } catch (_: Exception) { emptyList() },
                totalExposureSol = status.totalExposureSol,
                totalUnrealisedPnlSol = status.openPositions.sumOf { ts ->
                    val ref = ts.ref
                    if (ts.position.isOpen && ref > 0 && ts.position.entryPrice > 0)
                        ts.position.costSol * ((ref - ts.position.entryPrice) / ts.position.entryPrice)
                    else 0.0
                },
                circuitBreaker = sg?.getCircuitBreakerState() ?: com.lifecyclebot.engine.CircuitBreakerState(),
                auditLog       = sg?.getAuditLog()?.takeLast(50) ?: emptyList(),
                // Dashboard data from intelligence systems
                dashboardData = try { com.lifecyclebot.engine.DashboardDataProvider.getQuickStats() } catch (_: Exception) { null },
                marketSentiment = try { com.lifecyclebot.engine.SuperBrainEnhancements.getCurrentSentiment() } catch (_: Exception) { "UNKNOWN" },
                activeTradingModes = try { 
                    com.lifecyclebot.engine.UnifiedModeOrchestrator.ensureInitialized()
                    com.lifecyclebot.engine.UnifiedModeOrchestrator.getAllStatsSorted().count { it.isActive }
                } catch (_: Exception) { 0 },
                totalInsights = try { com.lifecyclebot.engine.SuperBrainEnhancements.getDashboardData().totalInsights } catch (_: Exception) { 0 },
            )
            // V5.9.663b — operator: 'the anr error persists'. UI poll
            // was 1500ms which, combined with watchlist=2386+ tokens,
            // 5 lane renderers each doing removeAllViews + recreate of
            // LinearLayout/ImageView per tick, and a status.tokens.toMap()
            // copy of the full ConcurrentHashMap on every tick, was
            // routinely tripping ANR on real devices (emulator already
            // logged 'Choreographer Skipped 44 frames'). Cadence raised
            // to 2500ms — halves UI thread pressure with at most a
            // 1-second lag on number refresh, no functional loss.
            delay(2500)
        }
    }

    fun startBot() {
        val intent = Intent(ctx, BotService::class.java).apply {
            action = BotService.ACTION_START
            putExtra(BotService.EXTRA_USER_REQUESTED, true)
        }
        BotService.status.running = true
        _ui.value = _ui.value.copy(running = true)
        ctx.startForegroundService(intent)
    }

    fun stopBot() {
        val intent = Intent(ctx, BotService::class.java).apply {
            action = BotService.ACTION_STOP
            putExtra(BotService.EXTRA_USER_REQUESTED, true)
        }
        BotService.status.running = false
        _ui.value = _ui.value.copy(running = false)
        ctx.startService(intent)
    }

    fun toggleBot() {
        val liveRunning = BotService.status.running
        if (liveRunning) stopBot() else startBot()
    }
    
    fun forceRefresh() {
        viewModelScope.launch {
            try {
                // Refresh wallet balance
                com.lifecyclebot.engine.WalletManager.getInstance(ctx).refreshBalance()
                // Trigger UI update by re-assigning current value (forces collection)
                _ui.value = _ui.value.copy()
            } catch (_: Exception) {}
        }
    }

    fun saveConfig(cfg: BotConfig) {
        // V5.9.702 — ANR FIX: saveConfig was called from MainActivity.onPause/onStop
        // (main thread). The previous body did runBlocking { withContext(IO) { load() } }
        // which parked the main thread for the full disk-read duration — observed up to
        // 12 seconds of stall in ANR traces (106 consecutive samples at BotViewModel.saveConfig).
        // ConfigStore.save() is also synchronous disk I/O on the main thread.
        //
        // Fix: move ALL blocking work (load + save + restart decision) onto a
        // viewModelScope coroutine dispatched to IO. The caller (onPause/onStop) returns
        // immediately. The BotService runs independently in a foreground service and
        // outlives the Activity, so the async write + conditional restart is safe to
        // fire-and-forget from a lifecycle callback.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Load previous config on IO thread — no longer blocks main thread.
                val currentCfg = ConfigStore.load(ctx)

                // Compare settings that REQUIRE a restart (use trim to avoid whitespace issues)
                val settingsChanged = cfg.paperMode != currentCfg.paperMode ||
                    cfg.autoTrade != currentCfg.autoTrade ||
                    kotlin.math.abs(cfg.smallBuySol - currentCfg.smallBuySol) > 0.0001 ||
                    kotlin.math.abs(cfg.largeBuySol - currentCfg.largeBuySol) > 0.0001 ||
                    cfg.heliusApiKey.trim() != currentCfg.heliusApiKey.trim() ||
                    cfg.birdeyeApiKey.trim() != currentCfg.birdeyeApiKey.trim() ||
                    cfg.groqApiKey.trim() != currentCfg.groqApiKey.trim() ||
                    // V5.9.77: Gemini key change restarts so GeminiCopilot.init() picks it up.
                    cfg.geminiApiKey.trim() != currentCfg.geminiApiKey.trim()
                    // NOTE: Telegram settings and sound do NOT require a restart.

                // Always save the config on IO thread (no longer blocks main thread).
                ConfigStore.save(ctx, cfg)

                // Only restart if important settings changed (not just watchlist).
                if (settingsChanged && _ui.value.running) {
                    com.lifecyclebot.engine.ErrorLogger.info("BotViewModel",
                        "RESTART TRIGGERED: paperMode=${cfg.paperMode != currentCfg.paperMode} " +
                        "autoTrade=${cfg.autoTrade != currentCfg.autoTrade} " +
                        "helius=${cfg.heliusApiKey.trim() != currentCfg.heliusApiKey.trim()}")

                    // V5.9.735 — RESTART SEQUENCING FIX.
                    // Previous code:
                    //   stopBot()  // sets BotService.status.running=false locally THEN fires ACTION_STOP
                    //   while (status.running && deadline) { delay }
                    //   startBot()
                    //
                    // The local mutation made the while-loop exit on the FIRST check (running already
                    // false) so the wait was zero-duration. ACTION_START then beat ACTION_STOP to the
                    // service. BotService.onStartCommand saw stopInProgress=false (stopBot() hadn't
                    // been entered yet) and loopJob.isActive=true → fell into the "Bot already
                    // running" branch → did nothing. Then ACTION_STOP arrived, latched
                    // KEY_MANUAL_STOP_REQUESTED=true, ran the full teardown. No one restarted because
                    // the START had already been "handled" as a no-op and the manual-stop latch was
                    // now set. Operator symptom: "tried to go live, it just shuts down".
                    //
                    // Fix: drive the wait off the actual service-side latches (stopInProgress and the
                    // loopJob), not the locally-mutated status flag. We send ACTION_STOP, then wait
                    // for the service to ENTER stopInProgress=true (proves ACTION_STOP was received)
                    // and then EXIT stopInProgress=false (proves teardown completed). Only then do we
                    // fire startBot() so ACTION_START arrives into a clean state.
                    stopBot()

                    // Phase 1: wait up to 2s for the service to enter stopInProgress=true.
                    // This proves ACTION_STOP was delivered and accepted.
                    val enterDeadline = System.currentTimeMillis() + 2_000L
                    while (!com.lifecyclebot.engine.BotService.stopInProgress &&
                           System.currentTimeMillis() < enterDeadline) {
                        delay(50)
                    }

                    // Phase 2: wait up to 30s for stopInProgress to clear (= teardown finished).
                    // Matches the 30s window BotService itself uses internally (V5.9.720).
                    val drainDeadline = System.currentTimeMillis() + 30_000L
                    while (com.lifecyclebot.engine.BotService.stopInProgress &&
                           System.currentTimeMillis() < drainDeadline) {
                        delay(150)
                    }

                    if (com.lifecyclebot.engine.BotService.stopInProgress) {
                        com.lifecyclebot.engine.ErrorLogger.warn("BotViewModel",
                            "RESTART: stopBot() did not drain in 30s — starting anyway " +
                            "(service-side guard will re-queue if needed)")
                    }

                    startBot()
                }
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.error("BotViewModel", "saveConfig async failed: ${e.message}", e)
            }
        }
    }

    fun connectWallet(privateKeyB58: String, rpcUrl: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.lifecyclebot.engine.ErrorLogger.info("BotViewModel", "connectWallet called with RPC: ${rpcUrl.take(40)}...")
                
                // Use singleton wallet manager - same instance everywhere
                val wm = com.lifecyclebot.engine.WalletManager.getInstance(ctx)
                
                com.lifecyclebot.engine.ErrorLogger.debug("BotViewModel", "Got singleton walletManager, calling connect...")
                
                val success = wm.connect(privateKeyB58, rpcUrl)
                
                if (success) {
                    com.lifecyclebot.engine.ErrorLogger.info("BotViewModel", "Wallet connected successfully!")
                    
                    // SAVE credentials to config for auto-reconnect
                    val cfg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigStore.load(ctx) }
                    ConfigStore.save(ctx, cfg.copy(
                        privateKeyB58 = privateKeyB58,
                        rpcUrl = rpcUrl,
                        walletAddress = wm.state.value.publicKey
                    ))
                    com.lifecyclebot.engine.ErrorLogger.info("BotViewModel", "Wallet credentials saved to config")
                    
                    // Balance already fetched during connect(), no need to call refreshBalance()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "Wallet connected!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val error = wm.state.value.errorMessage
                    com.lifecyclebot.engine.ErrorLogger.warn("BotViewModel", "Wallet connection failed: $error")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "Wallet error: $error", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.error("BotViewModel", "connectWallet exception: ${e.message}", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "Connection error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun disconnectWallet() {
        try {
            // Use singleton wallet manager
            com.lifecyclebot.engine.WalletManager.getInstance(ctx).disconnect()
        } catch (_: Exception) {}
        // V5.9.702 — Clear private key async; saveConfig is now fully async so
        // this naturally dispatches off the main thread too.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val cfg = ConfigStore.load(ctx)
                // saveConfig is already async (viewModelScope.launch) — call directly.
                // We pass the cfg here; saveConfig will re-load inside its own launch,
                // so just fire a direct save of the scrubbed config to avoid a double-load.
                ConfigStore.save(ctx, cfg.copy(privateKeyB58 = ""))
            } catch (_: Exception) {}
        }
    }

    fun manualBuy() {
        viewModelScope.launch {
            val cfg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigStore.load(ctx) }
            val ts    = BotService.status.tokens[cfg.activeToken] ?: return@launch
            BotService.instance?.let { svc ->
                // Access executor via the service
            }
        }
    }

    /**
     * Withdraw from the treasury.
     *
     * @param pct         Fraction 0.0–1.0 of treasury to send.
     * @param destination Target wallet address. If blank, uses the bot wallet (self-send).
     * @param onResult    Called on main thread with a result string for the UI toast.
     */
    fun withdrawFromTreasury(
        pct: Double,
        destination: String,
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val svc      = BotService.instance
                val executor = svc?.executor
                val wallet   = try { BotService.walletManager.getWallet() } catch (_: Exception) { null }
                val cfg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigStore.load(ctx) }
                val solPx    = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                // V5.9.495g — withdraw uses LIVE-capped treasury so user
                // never tries to withdraw SOL the wallet doesn't hold.
                val walletSolNow = try {
                    com.lifecyclebot.engine.BotService.status.walletSol.takeIf { it > 0.0 } ?: 0.0
                } catch (_: Exception) { 0.0 }
                val treasury = com.lifecyclebot.engine.TreasuryManager
                    .effectiveLockedSol(walletSolNow, cfg.paperMode)

                if (treasury <= 0.001) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult("Treasury is empty")
                    }
                    return@launch
                }

                // Determine destination: blank = self (bot wallet)
                val dest = destination.trim().ifBlank {
                    wallet?.publicKeyB58 ?: cfg.walletAddress
                }
                if (dest.isBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult("No destination address — connect wallet first")
                    }
                    return@launch
                }

                val amountSol = (treasury * pct.coerceIn(0.0, 1.0))
                val result    = if (executor != null) {
                    executor.executeTreasuryWithdrawal(
                        requestedSol       = amountSol,
                        destinationAddress = dest,
                        wallet             = wallet,
                        walletSol          = BotService.status.walletSol,
                    )
                } else {
                    // Bot not running — process withdrawal directly
                    val wResult = com.lifecyclebot.engine.TreasuryManager
                        .requestWithdrawalAmount(amountSol, solPx)
                    if (!wResult.approved) {
                        wResult.message
                    } else if (cfg.paperMode || wallet == null) {
                        com.lifecyclebot.engine.TreasuryManager
                            .executeWithdrawal(wResult.approvedSol, solPx, dest)
                        "PAPER: ${wResult.approvedSol.fmtSol()}◎ withdrawn"
                    } else {
                        try {
                            val sig = wallet.sendSol(dest, wResult.approvedSol)
                            com.lifecyclebot.engine.TreasuryManager
                                .executeWithdrawal(wResult.approvedSol, solPx, dest)
                            "OK: sent ${wResult.approvedSol.fmtSol()}◎ | ${sig.take(16)}…"
                        } catch (e: Exception) {
                            "FAILED: ${e.message?.take(80)}"
                        }
                    }
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(result)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult("Error: ${e.message?.take(80)}")
                }
            }
        }
    }

    private fun Double.fmtSol() = "%.4f".format(this)
}
