package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.Trade
import org.json.JSONArray
import org.json.JSONObject

/**
 * TradeHistoryStore
 * 
 * Persists trade history across bot restarts so that:
 * - 24h Trades stat persists
 * - Win Rate stat persists  
 * - Open positions stat persists
 * - AI Conf stat persists
 * 
 * Stores the last 7 days of trades (auto-cleanup of older trades)
 */
object TradeHistoryStore {
    
    private const val PREFS_NAME = "trade_history_store"
    private const val KEY_TRADES = "trades_json"
    private const val KEY_LAST_CLEANUP = "last_cleanup"
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days
    
    private var prefs: SharedPreferences? = null
    private val trades = mutableListOf<Trade>()
    
    /**
     * Initialize with context
     */
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadTrades()
        cleanupOldTrades()
        ErrorLogger.info("TradeHistoryStore", "📊 Loaded ${trades.size} trades from storage")
    }
    
    /**
     * Record a new trade
     */
    fun recordTrade(trade: Trade) {
        trades.add(trade)
        saveTrades()
        ErrorLogger.debug("TradeHistoryStore", "💾 Saved trade: ${trade.side} ${trade.sol.fmt(4)} SOL")
    }
    
    /**
     * Record multiple trades (e.g., from a token's trade list)
     */
    fun recordTrades(newTrades: List<Trade>) {
        // Only add trades we don't already have (dedup by timestamp + mint)
        val existingKeys = trades.map { "${it.ts}_${it.mint}" }.toSet()
        val toAdd = newTrades.filter { "${it.ts}_${it.mint}" !in existingKeys }
        
        if (toAdd.isNotEmpty()) {
            trades.addAll(toAdd)
            saveTrades()
            ErrorLogger.debug("TradeHistoryStore", "💾 Added ${toAdd.size} new trades")
        }
    }
    
    /**
     * Get all trades
     */
    fun getAllTrades(): List<Trade> = trades.toList()
    
    /**
     * MANUAL CLEAR - Only callable by user from Journal UI.
     * Clears all trade history and resets stats.
     */
    fun clearAllTrades() {
        trades.clear()
        saveTrades()
        ErrorLogger.info("TradeHistoryStore", "🗑️ MANUAL CLEAR: All trade history cleared by user")
    }
    
    /**
     * Get total trade count (for display)
     */
    fun getTotalTradeCount(): Int = trades.size
    
    /**
     * Record a partial profit from chunk selling (SellOptimizationAI).
     * This doesn't close the position, just records the partial exit.
     */
    fun recordPartialProfit(mint: String, profitSol: Double, pnlPct: Double) {
        val partialTrade = Trade(
            side = "PARTIAL_SELL",
            mode = "paper",  // Partial sells are tracked for learning
            sol = profitSol,
            price = 0.0,
            ts = System.currentTimeMillis(),
            pnlSol = profitSol,
            pnlPct = pnlPct,
            reason = "CHUNK_SELL",
            mint = mint,
        )
        trades.add(partialTrade)
        saveTrades()
        ErrorLogger.debug("TradeHistoryStore", "📊 PARTIAL PROFIT: ${profitSol.fmt(4)} SOL @ ${pnlPct.toInt()}%")
    }
    
    /**
     * Get trades from last 24 hours
     */
    fun getTrades24h(): List<Trade> {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        return trades.filter { it.ts >= cutoff }
    }
    
    /**
     * Get sell trades from last 24 hours (for win rate calculation)
     */
    fun getSells24h(): List<Trade> {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        return trades.filter { it.side == "SELL" && it.ts >= cutoff }
    }
    
    /**
     * Calculate 24h win rate
     */
    fun getWinRate24h(): Int {
        val sells = getSells24h()
        if (sells.isEmpty()) return 0
        
        val wins = sells.count { it.pnlPct > 2.0 }
        val losses = sells.count { it.pnlPct < -2.0 }
        val total = wins + losses
        
        return if (total > 0) (wins * 100 / total) else 0
    }
    
    /**
     * Get total trades in last 24h
     */
    fun getTradeCount24h(): Int = getTrades24h().size
    
    /**
     * Get total P&L in last 24h (SOL)
     */
    fun getPnl24hSol(): Double {
        return getSells24h().sumOf { it.pnlSol }
    }
    
    /**
     * Get summary stats
     */
    fun getStats(): StatsSnapshot {
        val sells24h = getSells24h()
        val wins = sells24h.count { it.pnlPct > 2.0 }
        val losses = sells24h.count { it.pnlPct < -2.0 }
        val total = wins + losses
        
        // Calculate lifetime stats for FluidLearningAI
        val allSells = trades.filter { it.side == "SELL" }
        val totalWins = allSells.count { it.pnlPct > 0 }
        val totalLosses = allSells.count { it.pnlPct < 0 }
        val totalCompleted = totalWins + totalLosses
        val lifetimeWinRate = if (totalCompleted > 0) {
            totalWins.toDouble() / totalCompleted * 100
        } else 50.0
        
        // Average win % and hold time
        val winningTrades = allSells.filter { it.pnlPct > 0 }
        val avgWinPct = if (winningTrades.isNotEmpty()) {
            winningTrades.map { it.pnlPct }.average()
        } else 10.0
        
        // Estimate avg hold time from trade timestamps (if we have buy/sell pairs)
        val avgHoldMinutes = 10  // Default, could be calculated from trade pairs
        
        return StatsSnapshot(
            trades24h = getTrades24h().size,
            winRate24h = if (total > 0) (wins * 100 / total) else 0,
            pnl24hSol = sells24h.sumOf { it.pnlSol },
            totalStoredTrades = trades.size,
            totalTrades = totalCompleted,
            winRate = lifetimeWinRate,
            avgWinPct = avgWinPct,
            avgHoldTimeMinutes = avgHoldMinutes,
        )
    }
    
    data class StatsSnapshot(
        val trades24h: Int,
        val winRate24h: Int,
        val pnl24hSol: Double,
        val totalStoredTrades: Int,
        // Added for FluidLearningAI
        val totalTrades: Int = 0,
        val winRate: Double = 50.0,
        val avgWinPct: Double = 10.0,
        val avgHoldTimeMinutes: Int = 10,
    )
    
    /**
     * Get the most frequently used trading mode (for UI display)
     */
    fun getTopMode(): String? {
        if (trades.isEmpty()) return null
        return trades
            .filter { it.tradingMode.isNotBlank() }
            .groupBy { it.tradingMode }
            .maxByOrNull { it.value.size }
            ?.key
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════
    
    private fun saveTrades() {
        try {
            val arr = JSONArray()
            trades.forEach { t ->
                arr.put(JSONObject().apply {
                    put("ts", t.ts)
                    put("side", t.side)
                    put("sol", t.sol)
                    put("price", t.price)
                    put("pnlSol", t.pnlSol)
                    put("pnlPct", t.pnlPct)
                    put("reason", t.reason)
                    put("score", t.score)
                    put("mode", t.mode)
                    put("mint", t.mint)
                    put("tradingMode", t.tradingMode)
                    put("tradingModeEmoji", t.tradingModeEmoji)
                    put("feeSol", t.feeSol)
                    put("netPnlSol", t.netPnlSol)
                })
            }
            // Use commit() for IMMEDIATE persistence (not apply() which is async)
            prefs?.edit()?.putString(KEY_TRADES, arr.toString())?.commit()
            ErrorLogger.debug("TradeHistoryStore", "💾 Persisted ${trades.size} trades to storage")
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to save trades: ${e.message}")
        }
    }
    
    private fun loadTrades() {
        try {
            val json = prefs?.getString(KEY_TRADES, null) ?: return
            val arr = JSONArray(json)
            trades.clear()
            
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                trades.add(Trade(
                    ts = obj.getLong("ts"),
                    side = obj.getString("side"),
                    sol = obj.getDouble("sol"),
                    price = obj.getDouble("price"),
                    pnlSol = obj.optDouble("pnlSol", 0.0),
                    pnlPct = obj.optDouble("pnlPct", 0.0),
                    reason = obj.optString("reason", ""),
                    score = obj.optDouble("score", 0.0),
                    mode = obj.optString("mode", "paper"),
                    mint = obj.optString("mint", ""),
                    tradingMode = obj.optString("tradingMode", ""),
                    tradingModeEmoji = obj.optString("tradingModeEmoji", ""),
                    feeSol = obj.optDouble("feeSol", 0.0),
                    netPnlSol = obj.optDouble("netPnlSol", 0.0)
                ))
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to load trades: ${e.message}")
        }
    }
    
    private fun cleanupOldTrades() {
        // REMOVED AUTO-CLEANUP
        // Journal data should persist indefinitely until manually cleared by user.
        // The win rate and stats need ALL historical trades to be accurate.
        // 
        // If storage becomes an issue, user can manually clear from Journal screen.
        ErrorLogger.debug("TradeHistoryStore", "📊 Journal has ${trades.size} total trades (no auto-cleanup)")
    }
    
    private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
}
