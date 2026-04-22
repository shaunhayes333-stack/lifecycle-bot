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
 * Journal data now persists indefinitely until manually cleared by user.
 *
 * Win/loss classification:
 * - WIN  = pnlPct >= 0.5
 * - LOSS = pnlPct <= -2.0
 * - Anything between those is SCRATCH / NEUTRAL and is NOT included in win-rate denominator
 */
object TradeHistoryStore {

    private const val PREFS_NAME = "trade_history_store"
    private const val KEY_TRADES = "trades_json"
    private const val KEY_LAST_CLEANUP = "last_cleanup"
    // V5.9.115: Lifetime counters that survive journal clears.
    // These back-feed FluidLearningAI / ML / behavior layers so clearing
    // the visual journal never wipes learned progress.
    private const val KEY_LIFETIME_STATS = "lifetime_stats_json"

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    private var prefs: SharedPreferences? = null
    private val trades = mutableListOf<Trade>()

    // V5.9.115: Persistent lifetime totals. NEVER cleared by clearAllTrades().
    // Only reset by explicit BehaviorActivity "Reset All Learning" action.
    @Volatile private var lifetimeSells: Int = 0
    @Volatile private var lifetimeWins: Int = 0
    @Volatile private var lifetimeLosses: Int = 0
    @Volatile private var lifetimeScratches: Int = 0
    @Volatile private var lifetimeWinPnlSum: Double = 0.0
    @Volatile private var lifetimeRealizedPnlSol: Double = 0.0

    // V5.9.43: Cached "proven edge" flag to avoid re-computing getStats()
    // thousands of times per second on the hot path (promotion gate, hard
    // blocks). Refreshed every STATS_CACHE_MS.
    @Volatile private var cachedHasProvenEdge: Boolean = false
    @Volatile private var cachedProvenWinRate: Double = 0.0
    @Volatile private var cachedProvenTradeCount: Int = 0
    @Volatile private var lastStatsCacheMs: Long = 0L
    private const val STATS_CACHE_MS = 30_000L  // 30s — proven-edge status changes slowly

    data class ProvenEdgeSnapshot(
        val hasProvenEdge: Boolean,
        val winRate: Double,      // 0-100
        val meaningfulTrades: Int,
    )

    /**
     * Cheap cached accessor for the hot path. Recomputes via getStats()
     * at most once every STATS_CACHE_MS. Safe to call from any thread.
     */
    fun getProvenEdgeCached(): ProvenEdgeSnapshot {
        val now = System.currentTimeMillis()
        if (now - lastStatsCacheMs > STATS_CACHE_MS) {
            try {
                val s = getStats()
                val meaningful = s.totalWins + s.totalLosses
                cachedProvenTradeCount = meaningful
                cachedProvenWinRate = s.winRate
                cachedHasProvenEdge = meaningful >= 300 && s.winRate >= 50.0
                lastStatsCacheMs = now
            } catch (_: Exception) {}
        }
        return ProvenEdgeSnapshot(cachedHasProvenEdge, cachedProvenWinRate, cachedProvenTradeCount)
    }

    /**
     * Initialize with context
     */
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadTrades()
        loadLifetimeStats()
        // V5.9.115: Back-fill lifetime stats from existing trade history the
        // first time this counter is introduced so users who already have
        // trade history don't drop to 0 lifetime on upgrade.
        if (lifetimeSells == 0 && trades.any { it.side == "SELL" }) {
            backfillLifetimeFromTrades()
        }
        cleanupOldTrades()
        ErrorLogger.info("TradeHistoryStore", "📊 Loaded ${trades.size} trades from storage (lifetime sells=$lifetimeSells)")
    }

    /**
     * Record a new trade
     */
    fun recordTrade(trade: Trade) {
        trades.add(trade)
        bumpLifetimeFor(trade)
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
            toAdd.forEach { bumpLifetimeFor(it) }
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
     *
     * V5.9.115: This ONLY clears the visible trade history (rows/stats used
     * by the Journal tab). Lifetime learning counters, BehaviorAI state,
     * FluidLearningAI progress, ML engine weights, and ALL other AI memory
     * are explicitly preserved. The user must use the Behavior tab's
     * "Reset All Learning" button to wipe learned intelligence.
     */
    fun clearAllTrades() {
        trades.clear()
        saveTrades()
        // DO NOT touch lifetime counters — those back the learned AI progress.
        ErrorLogger.info(
            "TradeHistoryStore",
            "🗑️ MANUAL CLEAR: Journal rows cleared (lifetime=${lifetimeSells} sells preserved — learned AI intact)"
        )
    }

    /**
     * V5.9.115: FULL reset — wipe journal AND lifetime counters.
     * ONLY called from BehaviorActivity "Reset All Learning" / FluidLearningAI.resetAllLearning().
     * This is the ONE place that is allowed to wipe learned progress.
     */
    fun fullResetIncludingLifetime() {
        trades.clear()
        lifetimeSells = 0
        lifetimeWins = 0
        lifetimeLosses = 0
        lifetimeScratches = 0
        lifetimeWinPnlSum = 0.0
        lifetimeRealizedPnlSol = 0.0
        saveTrades()
        saveLifetimeStats()
        ErrorLogger.warn(
            "TradeHistoryStore",
            "🔄 FULL RESET: Trades + lifetime counters wiped (learned AI reset)"
        )
    }

    /**
     * V5.9.115: Lifetime snapshot that persists through journal clears.
     * Consumed by FluidLearningAI / BehaviorAI / ML layers as the true
     * basis for learning progress.
     */
    data class LifetimeSnapshot(
        val totalSells: Int,
        val totalWins: Int,
        val totalLosses: Int,
        val totalScratches: Int,
        val winRate: Double,           // 0-100, among decisive trades
        val avgWinPct: Double,
        val realizedPnlSol: Double,
    )

    fun getLifetimeStats(): LifetimeSnapshot {
        val decisive = lifetimeWins + lifetimeLosses
        val winRate = if (decisive > 0) {
            lifetimeWins.toDouble() * 100.0 / decisive.toDouble()
        } else 50.0
        val avgWin = if (lifetimeWins > 0) {
            lifetimeWinPnlSum / lifetimeWins.toDouble()
        } else 10.0
        return LifetimeSnapshot(
            totalSells = lifetimeSells,
            totalWins = lifetimeWins,
            totalLosses = lifetimeLosses,
            totalScratches = lifetimeScratches,
            winRate = winRate,
            avgWinPct = avgWin,
            realizedPnlSol = lifetimeRealizedPnlSol,
        )
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
            mode = "paper",
            sol = profitSol,
            price = 0.0,
            ts = System.currentTimeMillis(),
            pnlSol = profitSol,
            pnlPct = pnlPct,
            reason = "CHUNK_SELL",
            mint = mint,
        )
        trades.add(partialTrade)
        // Partial profits count toward lifetime realized P&L but NOT toward
        // win/loss decisive counts (they aren't full exits).
        lifetimeRealizedPnlSol += profitSol
        saveLifetimeStats()
        saveTrades()
        ErrorLogger.debug(
            "TradeHistoryStore",
            "📊 PARTIAL PROFIT: ${profitSol.fmt(4)} SOL @ ${pnlPct.toInt()}%"
        )
    }

    /**
     * V5.6: Record completed trade for ML training.
     * Called when a position is fully closed (SELL side).
     * Feeds the OnDeviceMLEngine with training data.
     */
    fun recordTradeForML(
        trade: Trade,
        candlesAtEntry: List<com.lifecyclebot.data.Candle>,
        candlesAtExit: List<com.lifecyclebot.data.Candle>,
        liquidityAtEntry: Double,
        liquidityAtExit: Double,
        holdersAtEntry: Int,
        holdersAtExit: Int,
        rugcheckScore: Int,
        mintRevoked: Boolean,
        freezeRevoked: Boolean,
        topHolderPct: Double,
        rsi: Double,
        emaAlignment: String,
        wasRug: Boolean,
    ) {
        try {
            com.lifecyclebot.ml.OnDeviceMLEngine.recordTrade(
                trade = trade,
                candlesAtEntry = candlesAtEntry,
                candlesAtExit = candlesAtExit,
                liquidityAtEntry = liquidityAtEntry,
                liquidityAtExit = liquidityAtExit,
                holdersAtEntry = holdersAtEntry,
                holdersAtExit = holdersAtExit,
                rugcheckScore = rugcheckScore,
                mintRevoked = mintRevoked,
                freezeRevoked = freezeRevoked,
                topHolderPct = topHolderPct,
                rsi = rsi,
                emaAlignment = emaAlignment,
                wasRug = wasRug,
            )
            ErrorLogger.debug(
                "TradeHistoryStore",
                "🧠 ML TRAINING: Recorded trade for ${trade.mint.take(8)}... | pnl=${trade.pnlPct.toInt()}%"
            )
        } catch (e: Exception) {
            ErrorLogger.debug("TradeHistoryStore", "ML record error: ${e.message}")
        }
    }

    /**
     * Get trades from TODAY (since midnight local time)
     * V5.6.29d: Changed from rolling 24h to midnight-based
     * Count only goes UP during the day, resets at midnight
     */
    fun getTrades24h(): List<Trade> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val midnightToday = calendar.timeInMillis
        return trades.filter { it.ts >= midnightToday }
    }

    /**
     * Get fully closed SELL trades from TODAY (since midnight)
     * V5.6.29d: Changed from rolling 24h to midnight-based
     */
    fun getSells24h(): List<Trade> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val midnightToday = calendar.timeInMillis
        return trades.filter { it.side == "SELL" && it.ts >= midnightToday }
    }

    /** V5.9.56: Snapshot of all SELL trades for consumers that need to
     *  recompute lifetime realized P&L (e.g. RunTracker30D). Returns a
     *  defensive copy so concurrent mutation doesn't corrupt iteration. */
    fun getAllSells(): List<Trade> = trades.filter { it.side == "SELL" }.toList()


    /**
     * Calculate 24h win rate using decisive trades only:
     * winRate = wins / (wins + losses)
     */
    fun getWinRate24h(): Int {
        val sells = getSells24h()
        if (sells.isEmpty()) return 0

        val wins = sells.count { isWin(it) }
        val losses = sells.count { isLoss(it) }
        val decisiveTrades = wins + losses

        return if (decisiveTrades > 0) {
            ((wins.toDouble() * 100.0) / decisiveTrades).toInt()
        } else {
            0
        }
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
        val wins24h = sells24h.count { isWin(it) }
        val losses24h = sells24h.count { isLoss(it) }
        val decisive24h = wins24h + losses24h
        val scratches24h = sells24h.size - decisive24h

        val allSells = trades.filter { it.side == "SELL" }
        val totalWins = allSells.count { isWin(it) }
        val totalLosses = allSells.count { isLoss(it) }
        val totalCompleted = totalWins + totalLosses
        val totalScratches = allSells.size - totalCompleted

        val lifetimeWinRate = if (totalCompleted > 0) {
            totalWins.toDouble() * 100.0 / totalCompleted.toDouble()
        } else {
            50.0
        }

        val winRate24h = if (decisive24h > 0) {
            ((wins24h.toDouble() * 100.0) / decisive24h.toDouble()).toInt()
        } else {
            0
        }

        val winningTrades = allSells.filter { isWin(it) }
        val avgWinPct = if (winningTrades.isNotEmpty()) {
            winningTrades.map { it.pnlPct }.average()
        } else {
            10.0
        }

        // Estimate avg hold time from trade timestamps (if we have buy/sell pairs)
        val avgHoldMinutes = 10

        return StatsSnapshot(
            trades24h = getTrades24h().size,
            winRate24h = winRate24h,
            pnl24hSol = sells24h.sumOf { it.pnlSol },
            totalStoredTrades = trades.size,
            totalTrades = totalCompleted,
            winRate = lifetimeWinRate,
            avgWinPct = avgWinPct,
            avgHoldTimeMinutes = avgHoldMinutes,
            totalWins = totalWins,
            totalLosses = totalLosses,
            totalScratches = totalScratches,
            wins24h = wins24h,
            losses24h = losses24h,
            scratches24h = scratches24h,
        )
    }

    data class StatsSnapshot(
        val trades24h: Int,
        val winRate24h: Int,
        val pnl24hSol: Double,
        val totalStoredTrades: Int,
        val totalTrades: Int = 0,
        val winRate: Double = 50.0,
        val avgWinPct: Double = 10.0,
        val avgHoldTimeMinutes: Int = 10,
        val totalWins: Int = 0,
        val totalLosses: Int = 0,
        val totalScratches: Int = 0,
        val wins24h: Int = 0,
        val losses24h: Int = 0,
        val scratches24h: Int = 0,
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
                trades.add(
                    Trade(
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
                    )
                )
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to load trades: ${e.message}")
        }
    }

    private fun cleanupOldTrades() {
        // No auto-cleanup.
        // Journal data persists until manually cleared by the user.
        ErrorLogger.debug("TradeHistoryStore", "📊 Journal has ${trades.size} total trades (no auto-cleanup)")
    }

    private fun isWin(trade: Trade): Boolean = trade.pnlPct >= WIN_THRESHOLD_PCT

    private fun isLoss(trade: Trade): Boolean = trade.pnlPct <= LOSS_THRESHOLD_PCT

    private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.115: Lifetime counters (survive clearAllTrades)
    // ═══════════════════════════════════════════════════════════════════

    private fun bumpLifetimeFor(trade: Trade) {
        if (trade.side != "SELL") return
        lifetimeSells++
        when {
            isWin(trade) -> {
                lifetimeWins++
                lifetimeWinPnlSum += trade.pnlPct
            }
            isLoss(trade) -> lifetimeLosses++
            else -> lifetimeScratches++
        }
        lifetimeRealizedPnlSol += trade.pnlSol
        saveLifetimeStats()
    }

    private fun saveLifetimeStats() {
        try {
            val obj = JSONObject().apply {
                put("sells", lifetimeSells)
                put("wins", lifetimeWins)
                put("losses", lifetimeLosses)
                put("scratches", lifetimeScratches)
                put("winPnlSum", lifetimeWinPnlSum)
                put("realizedPnlSol", lifetimeRealizedPnlSol)
            }
            prefs?.edit()?.putString(KEY_LIFETIME_STATS, obj.toString())?.apply()
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to save lifetime stats: ${e.message}")
        }
    }

    private fun loadLifetimeStats() {
        try {
            val json = prefs?.getString(KEY_LIFETIME_STATS, null) ?: return
            val obj = JSONObject(json)
            lifetimeSells = obj.optInt("sells", 0)
            lifetimeWins = obj.optInt("wins", 0)
            lifetimeLosses = obj.optInt("losses", 0)
            lifetimeScratches = obj.optInt("scratches", 0)
            lifetimeWinPnlSum = obj.optDouble("winPnlSum", 0.0)
            lifetimeRealizedPnlSol = obj.optDouble("realizedPnlSol", 0.0)
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to load lifetime stats: ${e.message}")
        }
    }

    /**
     * One-time migration: when the lifetime counter is first introduced,
     * seed it from the existing trade history so users don't drop to 0.
     */
    private fun backfillLifetimeFromTrades() {
        val sells = trades.filter { it.side == "SELL" }
        if (sells.isEmpty()) return
        lifetimeSells = sells.size
        lifetimeWins = sells.count { isWin(it) }
        lifetimeLosses = sells.count { isLoss(it) }
        lifetimeScratches = sells.size - lifetimeWins - lifetimeLosses
        lifetimeWinPnlSum = sells.filter { isWin(it) }.sumOf { it.pnlPct }
        lifetimeRealizedPnlSol = sells.sumOf { it.pnlSol }
        saveLifetimeStats()
        ErrorLogger.info(
            "TradeHistoryStore",
            "📊 Back-filled lifetime stats from ${sells.size} existing SELL trades (wins=$lifetimeWins, losses=$lifetimeLosses)"
        )
    }
}