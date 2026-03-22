package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.Trade
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

// ── Wallet state ──────────────────────────────────────────────────────────────

enum class WalletConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class WalletState(
    val connectionState: WalletConnectionState = WalletConnectionState.DISCONNECTED,
    val publicKey: String = "",
    val solBalance: Double = 0.0,
    val balanceUsd: Double = 0.0,       // SOL balance × SOL/USD price
    val solPriceUsd: Double = 0.0,
    val errorMessage: String = "",
    // P&L tracking
    val totalTrades: Int = 0,
    val winningTrades: Int = 0,
    val losingTrades: Int = 0,
    val totalPnlSol: Double = 0.0,
    val totalPnlPct: Double = 0.0,
    val bestTradePnl: Double = 0.0,
    val worstTradePnl: Double = 0.0,
    val pnlHistory: List<PnlPoint> = emptyList(),  // for chart
    val lastRefreshed: Long = 0L,
    val treasurySol: Double = 0.0,
    val treasuryUsd: Double = 0.0,
    val highestMilestoneName: String = "",
    val nextMilestoneUsd: Double = 0.0,
) {
    val isConnected get() = connectionState == WalletConnectionState.CONNECTED
    val shortKey get() = if (publicKey.length >= 12)
        "${publicKey.take(6)}…${publicKey.takeLast(4)}" else publicKey
    val winRate get() = if (totalTrades > 0)
        (winningTrades.toDouble() / totalTrades * 100).toInt() else 0
}

data class PnlPoint(
    val tradeIndex: Int,
    val cumulativePnlSol: Double,
    val ts: Long,
    val isBuy: Boolean,
    val isWin: Boolean,
)

// ── Manager ───────────────────────────────────────────────────────────────────

class WalletManager private constructor(private val ctx: Context) {

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state

    private var wallet: SolanaWallet? = null
    private var currentRpcUrl: String = ""
    
    // Fallback public RPC endpoints (free, no API key needed)
    companion object {
        @Volatile var lastKnownSolPrice: Double = 0.0
        
        val FALLBACK_RPCS = listOf(
            "https://api.mainnet-beta.solana.com",              // Official Solana
            "https://solana-mainnet.rpc.extrnode.com",          // Extrnode  
            "https://rpc.ankr.com/solana",                      // Ankr
            "https://solana.public-rpc.com",                    // Public RPC
            "https://mainnet.rpcpool.com",                      // RPC Pool
        )
        
        // SINGLETON: One wallet manager for the entire app
        @Volatile private var INSTANCE: WalletManager? = null
        
        fun getInstance(ctx: Context): WalletManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalletManager(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ── connect / disconnect ──────────────────────────────────────────

    fun connect(privateKeyB58: String, rpcUrl: String): Boolean {
        ErrorLogger.info("Wallet", "connect() called with RPC: ${rpcUrl.take(30)}...")
        _state.value = _state.value.copy(
            connectionState = WalletConnectionState.CONNECTING,
            errorMessage    = "",
        )
        
        // Validate input first
        if (privateKeyB58.isBlank()) {
            ErrorLogger.warn("Wallet", "Private key is empty")
            _state.value = _state.value.copy(
                connectionState = WalletConnectionState.ERROR,
                errorMessage    = "Private key is empty",
            )
            return false
        }
        
        // Build list of RPCs to try: user's RPC first, then fallbacks
        val rpcsToTry = mutableListOf<String>()
        if (rpcUrl.isNotBlank()) {
            rpcsToTry.add(rpcUrl)
        }
        rpcsToTry.addAll(FALLBACK_RPCS)
        
        // Try each RPC until one works
        var lastError: String = "Unknown error"
        for (tryRpc in rpcsToTry) {
            ErrorLogger.info("Wallet", "Trying RPC: ${tryRpc.take(50)}...")
            try {
                ErrorLogger.debug("Wallet", "Creating SolanaWallet with key length: ${privateKeyB58.length}")
                wallet = SolanaWallet(privateKeyB58, tryRpc)
                val pubkey = wallet!!.publicKeyB58
                ErrorLogger.debug("Wallet", "Wallet created, pubkey: ${pubkey.take(12)}...")
                
                // Test the connection by getting balance
                ErrorLogger.debug("Wallet", "Testing connection with getBalance...")
                val testBalance = wallet!!.getSolBalance()
                
                ErrorLogger.info("Wallet", "SUCCESS! Connected via ${tryRpc.take(35)}! Balance: $testBalance SOL")
                currentRpcUrl = tryRpc
                
                // Also fetch SOL price for USD conversion
                val solPrice = fetchSolPrice()
                if (solPrice > 0) WalletManager.lastKnownSolPrice = solPrice
                
                _state.value = _state.value.copy(
                    connectionState = WalletConnectionState.CONNECTED,
                    publicKey       = pubkey,
                    solBalance      = testBalance,
                    balanceUsd      = testBalance * solPrice,
                    solPriceUsd     = solPrice,
                    lastRefreshed   = System.currentTimeMillis(),
                )
                return true
            } catch (e: IllegalArgumentException) {
                // Invalid key format - don't try other RPCs
                ErrorLogger.error("Wallet", "INVALID KEY FORMAT: ${e.message}", e)
                wallet = null
                _state.value = _state.value.copy(
                    connectionState = WalletConnectionState.ERROR,
                    errorMessage    = "Invalid private key format",
                )
                return false
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown"
                ErrorLogger.warn("Wallet", "RPC FAILED [${tryRpc.take(35)}]: $lastError")
                wallet = null
                // Continue to next RPC
            }
        }
        
        // All RPCs failed
        ErrorLogger.error("Wallet", "ALL ${rpcsToTry.size} RPCs FAILED. Last error: $lastError")
        _state.value = _state.value.copy(
            connectionState = WalletConnectionState.ERROR,
            errorMessage    = "All RPC endpoints failed: $lastError",
        )
        return false
    }

    fun disconnect() {
        wallet = null
        currentRpcUrl = ""
        _state.value = WalletState(connectionState = WalletConnectionState.DISCONNECTED)
    }

    fun getWallet(): SolanaWallet? = wallet
    
    fun getCurrentRpcUrl(): String = currentRpcUrl

    // ── balance refresh ───────────────────────────────────────────────

    suspend fun refreshBalance() {
        var w = wallet
        
        // If wallet is null, try to reconnect using saved credentials
        if (w == null) {
            ErrorLogger.warn("Wallet", "refreshBalance: wallet is null, checking for saved credentials...")
            // Note: We can't access ConfigStore here directly, so just log and return
            // The auto-reconnect in BotViewModel should handle this
            return
        }
        
        try {
            ErrorLogger.debug("Wallet", "Refreshing balance...")
            
            // Use NonCancellable to ensure balance refresh completes even if parent job is cancelled
            val solBal = kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { 
                w.getSolBalance() 
            }
            ErrorLogger.info("Wallet", "SOL Balance: $solBal")
            
            val solPrice = kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { 
                fetchSolPrice() 
            }
            ErrorLogger.debug("Wallet", "SOL Price: $$solPrice")
            
            if (solPrice > 0) WalletManager.lastKnownSolPrice = solPrice
            _state.value = _state.value.copy(
                solBalance    = solBal,
                balanceUsd    = solBal * solPrice,
                solPriceUsd   = solPrice,
                lastRefreshed = System.currentTimeMillis(),
                treasurySol   = TreasuryManager.treasurySol,
                treasuryUsd   = TreasuryManager.treasurySol * solPrice,
                highestMilestoneName = TreasuryManager.MILESTONES
                    .getOrNull(TreasuryManager.highestMilestoneHit)?.label ?: "—",
                nextMilestoneUsd = TreasuryManager.MILESTONES
                    .getOrNull(TreasuryManager.highestMilestoneHit + 1)
                    ?.thresholdUsd ?: 0.0,
            )
            ErrorLogger.info("Wallet", "Balance refresh complete: $solBal SOL ($${"%.2f".format(solBal * solPrice)})")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Silently ignore cancellation - this is expected during bot stop
            ErrorLogger.debug("Wallet", "refreshBalance cancelled (expected during shutdown)")
        } catch (e: Exception) {
            ErrorLogger.error("Wallet", "refreshBalance FAILED: ${e.message}", e)
            // Update state with error
            _state.value = _state.value.copy(
                errorMessage = "Balance refresh failed: ${e.message}"
            )
        }
    }

    // ── SOL price (free, no key) ──────────────────────────────────────
    // Multiple fallback sources for reliability

    private fun fetchSolPrice(): Double {
        // Try CoinGecko first (most reliable)
        val coinGeckoPrice = tryCoinGecko()
        if (coinGeckoPrice > 50.0) {  // Sanity check: SOL should be > $50
            lastKnownSolPrice = coinGeckoPrice
            return coinGeckoPrice
        }
        
        // Try Binance as second source
        val binancePrice = tryBinance()
        if (binancePrice > 50.0) {
            lastKnownSolPrice = binancePrice
            return binancePrice
        }
        
        // Try Jupiter price API as third source
        val jupiterPrice = tryJupiter()
        if (jupiterPrice > 50.0) {
            lastKnownSolPrice = jupiterPrice
            return jupiterPrice
        }
        
        // Last resort: use cached price or hardcoded fallback
        ErrorLogger.warn("Wallet", "All SOL price sources failed! Using fallback: $${lastKnownSolPrice.takeIf { it > 50 } ?: 140.0}")
        return lastKnownSolPrice.takeIf { it > 50.0 } ?: 140.0
    }
    
    private fun tryCoinGecko(): Double {
        return try {
            val http = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8,  java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req  = okhttp3.Request.Builder()
                .url("https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd")
                .header("Accept", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return 0.0
            val json = org.json.JSONObject(body)
            val price = json.optJSONObject("solana")?.optDouble("usd", 0.0) ?: 0.0
            if (price > 50.0) {
                ErrorLogger.info("Wallet", "SOL price from CoinGecko: $${String.format("%.2f", price)}")
            }
            price
        } catch (e: Exception) {
            ErrorLogger.debug("Wallet", "CoinGecko SOL price error: ${e.message}")
            0.0
        }
    }
    
    private fun tryBinance(): Double {
        return try {
            val http = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8,  java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder()
                .url("https://api.binance.com/api/v3/ticker/price?symbol=SOLUSDT")
                .header("Accept", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return 0.0
            val json = org.json.JSONObject(body)
            val price = json.optString("price", "0").toDoubleOrNull() ?: 0.0
            if (price > 50.0) {
                ErrorLogger.info("Wallet", "SOL price from Binance: $${String.format("%.2f", price)}")
            }
            price
        } catch (e: Exception) {
            ErrorLogger.debug("Wallet", "Binance SOL price error: ${e.message}")
            0.0
        }
    }
    
    private fun tryJupiter(): Double {
        return try {
            val http = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8,  java.util.concurrent.TimeUnit.SECONDS)
                .build()
            // Jupiter price API - SOL mint
            val req = okhttp3.Request.Builder()
                .url("https://price.jup.ag/v6/price?ids=So11111111111111111111111111111111111111112")
                .header("Accept", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return 0.0
            val json = org.json.JSONObject(body)
            val data = json.optJSONObject("data")
            val solData = data?.optJSONObject("So11111111111111111111111111111111111111112")
            val price = solData?.optDouble("price", 0.0) ?: 0.0
            if (price > 50.0) {
                ErrorLogger.info("Wallet", "SOL price from Jupiter: $${String.format("%.2f", price)}")
            }
            price
        } catch (e: Exception) {
            ErrorLogger.debug("Wallet", "Jupiter SOL price error: ${e.message}")
            0.0
        }
    }
    
    // Legacy fallback removed - now using multiple sources above

    // ── P&L calculation from trade history ───────────────────────────

    fun updatePnl(allTrades: List<Trade>) {
        val sells = allTrades.filter { it.side == "SELL" }
        if (sells.isEmpty()) {
            _state.value = _state.value.copy(
                totalTrades   = 0,
                winningTrades = 0,
                losingTrades  = 0,
                totalPnlSol   = 0.0,
                pnlHistory    = emptyList(),
            )
            return
        }

        val wins      = sells.count { it.pnlSol > 0 }
        val losses    = sells.count { it.pnlSol < 0 }
        val totalPnl  = sells.sumOf { it.pnlSol }
        val best      = sells.maxOfOrNull { it.pnlSol } ?: 0.0
        val worst     = sells.minOfOrNull { it.pnlSol } ?: 0.0
        val startSol  = allTrades.firstOrNull()?.sol ?: 1.0

        // Build cumulative P&L history for chart
        var cumulative = 0.0
        val history   = mutableListOf<PnlPoint>()
        var idx       = 0

        for (trade in allTrades.sortedBy { it.ts }) {
            if (trade.side == "SELL") {
                cumulative += trade.pnlSol
                history.add(PnlPoint(
                    tradeIndex       = idx++,
                    cumulativePnlSol = cumulative,
                    ts               = trade.ts,
                    isBuy            = false,
                    isWin            = trade.pnlSol > 0,
                ))
            } else {
                history.add(PnlPoint(
                    tradeIndex       = idx++,
                    cumulativePnlSol = cumulative,
                    ts               = trade.ts,
                    isBuy            = true,
                    isWin            = false,
                ))
            }
        }

        val totalPct = if (startSol > 0) (totalPnl / startSol) * 100.0 else 0.0

        _state.value = _state.value.copy(
            totalTrades   = sells.size,
            winningTrades = wins,
            losingTrades  = losses,
            totalPnlSol   = totalPnl,
            totalPnlPct   = totalPct,
            bestTradePnl  = best,
            worstTradePnl = worst,
            pnlHistory    = history,
        )
    }
}
