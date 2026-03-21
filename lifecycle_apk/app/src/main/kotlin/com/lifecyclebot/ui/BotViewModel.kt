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
                val cfg = ConfigStore.load(ctx)
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
            val cfg    = ConfigStore.load(ctx)
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
            )
            delay(1500)
        }
    }

    fun startBot() {
        val intent = Intent(ctx, BotService::class.java).apply { action = BotService.ACTION_START }
        ctx.startForegroundService(intent)
    }

    fun stopBot() {
        val intent = Intent(ctx, BotService::class.java).apply { action = BotService.ACTION_STOP }
        ctx.startService(intent)
    }

    fun toggleBot() {
        if (_ui.value.running) stopBot() else startBot()
    }

    fun saveConfig(cfg: BotConfig) {
        ConfigStore.save(ctx, cfg)
        // Restart loop to pick up new config
        if (_ui.value.running) { stopBot(); startBot() }
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
                    val cfg = ConfigStore.load(ctx)
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
        // Clear private key from config
        val cfg = ConfigStore.load(ctx)
        saveConfig(cfg.copy(privateKeyB58 = ""))
    }

    fun manualBuy() {
        viewModelScope.launch {
            val cfg   = ConfigStore.load(ctx)
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
                val cfg      = ConfigStore.load(ctx)
                val solPx    = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                val treasury = com.lifecyclebot.engine.TreasuryManager.treasurySol

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
