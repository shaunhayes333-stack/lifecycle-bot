package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.Trade
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

class WalletManager(private val ctx: Context) {

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state

    private var wallet: SolanaWallet? = null

    // ── connect / disconnect ──────────────────────────────────────────

    fun connect(privateKeyB58: String, rpcUrl: String): Boolean {
        _state.value = _state.value.copy(
            connectionState = WalletConnectionState.CONNECTING,
            errorMessage    = "",
        )
        return try {
            wallet = SolanaWallet(privateKeyB58, rpcUrl)
            val pubkey = wallet!!.publicKeyB58
            _state.value = _state.value.copy(
                connectionState = WalletConnectionState.CONNECTED,
                publicKey       = pubkey,
            )
            refreshBalance()
            true
        } catch (e: Exception) {
            wallet = null
            _state.value = _state.value.copy(
                connectionState = WalletConnectionState.ERROR,
                errorMessage    = e.message ?: "Invalid private key",
            )
            false
        }
    }

    fun disconnect() {
        wallet = null
        _state.value = WalletState(connectionState = WalletConnectionState.DISCONNECTED)
    }

    fun getWallet(): SolanaWallet? = wallet

    // ── balance refresh ───────────────────────────────────────────────

    fun refreshBalance() {
        val w = wallet ?: return
        try {
            val solBal    = w.getSolBalance()
            val solPrice  = fetchSolPrice()
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
        } catch (_: Exception) {}
    }

    // ── SOL price (free, no key) ──────────────────────────────────────
    // Uses CoinGecko simple price endpoint — free, no auth, ~1 req/min ok

    companion object {
        /** Live SOL/USD price — updated every wallet refresh. Default 130 until first fetch. */
        @Volatile var lastKnownSolPrice: Double = 130.0
    }

    private fun fetchSolPrice(): Double {
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
            val body = resp.body?.string() ?: return _state.value.solPriceUsd
            val json = org.json.JSONObject(body)
            json.optJSONObject("solana")?.optDouble("usd", 0.0) ?: 0.0
        } catch (_: Exception) {
            _state.value.solPriceUsd   // keep last known price on failure
        }
    }

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
