package com.lifecyclebot.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.BotRuntimeController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val running: Boolean = false,
    val runtime: BotRuntimeController.Snapshot = BotRuntimeController.Snapshot(),
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
    // V5.9.1178 — force UiState emission even when live TokenState object refs
    // mutate in place and Map.equals would otherwise consider snapshots equal.
    val renderEpoch: Long = 0L,
)

class BotViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    
    // Use singleton wallet manager - persists across all activities
    private val walletManager = com.lifecyclebot.engine.WalletManager.getInstance(ctx)

    init {
        // V5.9.1047 — operator V5.9.1046 dump showed BotViewModel.pollLoop
        // hitting 1059ms on Main inflating a VectorDrawable (stack trace
        // shows VectorDrawable.nCreateFullPath under pollLoop). pollLoop
        // was launching on viewModelScope (default Dispatchers.Main.immediate)
        // so the entire body ran on Main, including any indirect resource
        // lookups via DashboardDataProvider / SuperBrainEnhancements /
        // UnifiedModeOrchestrator. Move the loop body off Main entirely —
        // StateFlow.value is thread-safe so UI consumers still observe
        // updates correctly on their own dispatchers.
        // V5.9.1166 — keep dashboard state hot even while wallet reconnect is
        // slow. The old sequence ran autoReconnectWallet() BEFORE pollLoop(); if
        // RPC/wallet connect blocked while the user returned to the app, Main
        // repainted a stale UiState and looked crashed for up to minutes. Poll
        // immediately; reconnect in parallel on IO.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { pollLoop() }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { autoReconnectWallet() }
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

    private suspend fun buildUiStateSnapshot(): UiState {
        // V5.9.696 — ConfigStore.load is a disk read; move it off the main/UI thread.
        val cfg    = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigStore.load(ctx) }
        val status = BotService.status
        val runtime = BotRuntimeController.snapshot()
        val serviceRuntimeActive = try { BotService.isRuntimeActive() } catch (_: Throwable) { runtime.runtimeActive }

        // V5.9.1178 — UI bridge snapshot, not live mutable map.
        // V5.9.953 passed status.tokens directly to UiState to avoid a main-thread
        // toMap() copy. That was the right thread concern but the wrong state
        // contract: MutableStateFlow/data-class equality can retain the same map
        // reference forever, so MainActivity keeps rendering stale/empty token data
        // while BotService/PipelineHealth show hundreds of live watchlist tokens.
        // We are already on Dispatchers.Default, so create a full immutable
        // snapshot here. MainActivity caps row rendering; the count/header must
        // still reflect real BotService state without owning the live CHM.
        val openSnapshot = try { status.openPositions.toList() } catch (_: Throwable) { emptyList() }
        val tokenSnapshot: Map<String, TokenState> = try {
            // V5.9.1202 — snapshot via entries iterator, then repair from values
            // if the live map mutates mid-copy. Runtime snapshots showed Main UI
            // stateTokens=0 while BotService had 500+ live tokens; the old toMap()
            // catch-all converted transient copy failures into an empty dashboard.
            val byEntry = try {
                status.tokens.entries.associate { it.key to it.value }
            } catch (_: Throwable) { emptyMap<String, TokenState>() }
            if (byEntry.isNotEmpty()) byEntry else {
                val vals = try { status.tokens.values.toList() } catch (_: Throwable) { emptyList() }
                vals.associateBy { it.mint.ifBlank { it.symbol } }
            }
        } catch (_: Throwable) { emptyMap() }
        var active = tokenSnapshot[cfg.activeToken]
        if (active == null) {
            active = openSnapshot.maxByOrNull { it.position.entryTime }
        }
        if (active == null && tokenSnapshot.isNotEmpty()) {
            active = tokenSnapshot.values
                .asSequence()
                .filter { it.lastPrice > 0.0 || it.history.isNotEmpty() || it.history5m.isNotEmpty() }
                .maxByOrNull { maxOf(it.lastPriceUpdate, it.addedToWatchlistAt) }
                ?: tokenSnapshot.values.maxByOrNull { it.addedToWatchlistAt }
        }

        // Use singleton wallet manager - always the same instance
        val wm = com.lifecyclebot.engine.WalletManager.getInstance(ctx)
        val sg = try { com.lifecyclebot.engine.BotService.instance
            ?.let { svc ->
                val f = svc.javaClass.getDeclaredField("securityGuard")
                f.isAccessible = true
                f.get(svc) as? com.lifecyclebot.engine.SecurityGuard
            } } catch (_: Exception) { null }
        return UiState(
                running      = runtime.runtimeActive || serviceRuntimeActive,
                runtime      = runtime,
                walletSol    = status.walletSol,
                activeToken  = active,
                // V5.9.1178 — immutable/bounded UI snapshot. Do NOT pass the live
                // status.tokens map; StateFlow needs a fresh value and MainActivity
                // needs stable data to render.
                tokens       = tokenSnapshot,
                logs         = synchronized(status.logs) { status.logs.toList().takeLast(200) },
                config       = cfg,
                walletState    = wm.state.value,
                openPositions  = openSnapshot,
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
                    val entry = ts.position.entryPrice
                    val raw = try { com.lifecyclebot.engine.Executor.getActualPricePublic(ts) } catch (_: Throwable) { ts.ref }
                    val mark = try {
                        val entryMcap = ts.position.entryMcap
                        val currentMcap = ts.lastMcap
                        if (entry > 0.0 && raw > 0.0 && entryMcap > 0.0 && currentMcap > 0.0) {
                            val rawGain = ((raw - entry) / entry) * 100.0
                            val mcapGain = ((currentMcap - entryMcap) / entryMcap) * 100.0
                            if ((kotlin.math.abs(rawGain - mcapGain) >= 250.0 && kotlin.math.abs(rawGain) >= 500.0) || (rawGain > 250.0 && mcapGain < 0.0)) {
                                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("OPEN_POSITION_UI_AGG_BASIS_REBASED_4479") } catch (_: Throwable) {}
                                entry * (currentMcap / entryMcap)
                            } else raw
                        } else raw
                    } catch (_: Throwable) { raw }
                    if (ts.position.isOpen && mark > 0 && entry > 0)
                        ts.position.costSol * ((mark - entry) / entry)
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
                renderEpoch = System.currentTimeMillis(),
        )
    }

    private suspend fun pollLoop() {
        while (true) {
            _ui.value = buildUiStateSnapshot()
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
        // V5.9.1068 — DO NOT pre-mutate BotService.status.running here.
        // The previous premature mutation made onStartCommand see
        // status.running=true and fall through to the "Bot already running"
        // branch, silently dropping the START action. The service must own
        // status.running and set it when startBot() actually fires.
        val intent = Intent(ctx, BotService::class.java).apply {
            action = BotService.ACTION_START
            putExtra(BotService.EXTRA_USER_REQUESTED, true)
        }
        _ui.value = _ui.value.copy(runtime = BotRuntimeController.snapshot(), running = BotRuntimeController.snapshot().runtimeActive)
        ctx.startForegroundService(intent)
    }

    fun stopBot(source: String = "ui_stop_button", uiStopConfirmed: Boolean = false) {
        val intent = Intent(ctx, BotService::class.java).apply {
            action = BotService.ACTION_STOP
            putExtra(BotService.EXTRA_USER_REQUESTED, true)
            putExtra(BotService.EXTRA_STOP_SOURCE, source)
            putExtra(BotService.EXTRA_UI_STOP_CONFIRMED, source != "ui_stop_button" || uiStopConfirmed)
        }
        // V5.9.1169 — reliable stop dispatch. The service is already foreground
        // while running, but startService() can be unreliable during activity churn
        // on newer Android/OEM builds. Use foreground-service dispatch first and
        // fall back to startService. Do not call BotService.stopBot() directly on
        // main; stop still belongs to the service coroutine.
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("UI_STOP_DISPATCHED", "source=$source uiConfirmed=$uiStopConfirmed") } catch (_: Throwable) {}
        try {
            ctx.startForegroundService(intent)
        } catch (_: Throwable) {
            try { ctx.startService(intent) } catch (_: Throwable) {}
        }
    }

    fun toggleBot() {
        // V5.9.1076 — STOP capability removed from the legacy toggle API.
        // Any stale listener, old Activity instance, accessibility replay, or
        // delayed click that still reaches toggleBot() must be harmless. It now
        // behaves as START/RESTART only; explicit stopping is only available via
        // stopBotFromStopButton(), which sets EXTRA_UI_STOP_CONFIRMED=true.
        startBot()
    }

    fun stopBotFromStopButton() {
        stopBot(source = "ui_stop_button", uiStopConfirmed = true)
    }
    
    fun forceRefresh() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                // V5.9.1166 — rebuild from live BotService truth, not a stale
                // copy of the previous UiState. Wallet refresh is optional and
                // must not block the dashboard repaint path.
                _ui.value = buildUiStateSnapshot()
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { com.lifecyclebot.engine.WalletManager.getInstance(ctx).refreshBalance() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    fun saveConfig(cfg: BotConfig, allowRestart: Boolean = true) {
        // V5.9.702 — ANR FIX: saveConfig was called from MainActivity.onPause/onStop
        // (main thread). The previous body did runBlocking { withContext(IO) { load() } }
        // which parked the main thread for the full disk-read duration — observed up to
        // 12 seconds of stall in ANR traces (106 consecutive samples at BotViewModel.saveConfig).
        // ConfigStore.save() is also synchronous disk I/O on the main thread.
        //
        // Fix: move ALL blocking work (load + save) onto a viewModelScope coroutine
        // dispatched to IO. V5.9.1015 adds allowRestart=false for lifecycle autosaves:
        // onPause/onStop must persist UI fields but MUST NOT stop/restart the bot,
        // because restart closes all open positions with reason=bot_shutdown.
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

                // Only restart if this is an explicit settings apply. Lifecycle autosaves
                // (MainActivity.onPause/onStop) pass allowRestart=false so navigating
                // between screens can never trigger ACTION_STOP / bot_shutdown liquidation.
                if (settingsChanged && _ui.value.running && allowRestart) {
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
                    stopBot(source = "config_restart")

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
                } else if (settingsChanged && _ui.value.running && !allowRestart) {
                    com.lifecyclebot.engine.ErrorLogger.info(
                        "BotViewModel",
                        "AUTOSAVE_NO_RESTART: settingsChanged=true but allowRestart=false; bot remains running"
                    )
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
                    } else if (cfg.paperMode) {
                        com.lifecyclebot.engine.TreasuryManager
                            .executeWithdrawal(wResult.approvedSol, solPx, dest)
                        "PAPER: ${wResult.approvedSol.fmtSol()}◎ withdrawn"
                    } else if (wallet == null) {
                        // V5.9.751b — refuse paper fallback in live mode.
                        "BLOCKED: wallet disconnected — refusing paper-fallback withdrawal"
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
