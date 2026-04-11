package com.lifecyclebot.perps

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ===============================================================================
 * WATCHLIST & ALERTS ENGINE — V5.7.8
 * ===============================================================================
 *
 * Tracks user-defined watchlist items with configurable price alerts.
 * Uses MarketsScanner for real-time price feeds.
 *
 * FEATURES:
 *   - Add/remove symbols to watchlist
 *   - Set price alerts (above/below target)
 *   - Percentage change alerts
 *   - Volume spike alerts
 *   - Auto-scan using MarketsScanner categories
 *   - Alert history with notification triggers
 *
 * ===============================================================================
 */
object WatchlistEngine {

    private const val TAG = "Watchlist"
    private const val PREFS_NAME = "watchlist_engine"
    private const val MAX_WATCHLIST = 50
    private const val MAX_ALERTS = 100

    private var prefs: SharedPreferences? = null

    // Active watchlist
    private val watchlist = ConcurrentHashMap<String, WatchlistItem>()

    // Active alerts
    private val alerts = ConcurrentHashMap<String, PriceAlert>()

    // Triggered alerts history
    private val triggeredAlerts = mutableListOf<TriggeredAlert>()

    // Alert callback
    private var onAlertTriggered: ((TriggeredAlert) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════════════════

    data class WatchlistItem(
        val symbol: String,
        val addedAt: Long = System.currentTimeMillis(),
        var lastPrice: Double = 0.0,
        var change24hPct: Double = 0.0,
        var volume24h: Double = 0.0,
        var scannerScore: Int = 0,
        var signal: String = "NEUTRAL",
        var lastUpdated: Long = 0
    )

    data class PriceAlert(
        val id: String,
        val symbol: String,
        val type: AlertType,
        val targetValue: Double,
        val createdAt: Long = System.currentTimeMillis(),
        var isActive: Boolean = true,
        var triggeredAt: Long? = null
    )

    enum class AlertType {
        PRICE_ABOVE,        // Trigger when price goes above target
        PRICE_BELOW,        // Trigger when price goes below target
        CHANGE_ABOVE,       // Trigger when 24h change exceeds target %
        CHANGE_BELOW,       // Trigger when 24h change drops below target %
        VOLUME_SPIKE        // Trigger when volume exceeds target
    }

    data class TriggeredAlert(
        val alert: PriceAlert,
        val currentPrice: Double,
        val currentChange: Double,
        val triggeredAt: Long = System.currentTimeMillis(),
        val message: String
    )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadWatchlist()
        loadAlerts()
        ErrorLogger.info(TAG, "WatchlistEngine initialized: ${watchlist.size} items, ${alerts.size} alerts")
    }

    fun setAlertCallback(callback: (TriggeredAlert) -> Unit) {
        onAlertTriggered = callback
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WATCHLIST MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    fun addToWatchlist(symbol: String): Boolean {
        if (watchlist.size >= MAX_WATCHLIST) return false
        if (watchlist.containsKey(symbol)) return false

        watchlist[symbol] = WatchlistItem(symbol = symbol)
        saveWatchlist()
        ErrorLogger.info(TAG, "Added $symbol to watchlist (${watchlist.size} total)")
        return true
    }

    fun removeFromWatchlist(symbol: String) {
        watchlist.remove(symbol)
        // Remove associated alerts
        alerts.entries.removeAll { it.value.symbol == symbol }
        saveWatchlist()
        saveAlerts()
    }

    fun getWatchlist(): List<WatchlistItem> =
        watchlist.values.sortedByDescending { it.lastUpdated }

    fun isOnWatchlist(symbol: String): Boolean = watchlist.containsKey(symbol)

    // ═══════════════════════════════════════════════════════════════════════
    // ALERT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    fun addAlert(symbol: String, type: AlertType, targetValue: Double): String {
        if (alerts.size >= MAX_ALERTS) return "MAX_ALERTS_REACHED"

        val id = "ALERT_${symbol}_${type.name}_${System.currentTimeMillis()}"
        alerts[id] = PriceAlert(
            id = id,
            symbol = symbol,
            type = type,
            targetValue = targetValue
        )
        saveAlerts()

        val typeStr = when (type) {
            AlertType.PRICE_ABOVE -> "price above $${"%.2f".format(targetValue)}"
            AlertType.PRICE_BELOW -> "price below $${"%.2f".format(targetValue)}"
            AlertType.CHANGE_ABOVE -> "change above ${"%.1f".format(targetValue)}%"
            AlertType.CHANGE_BELOW -> "change below ${"%.1f".format(targetValue)}%"
            AlertType.VOLUME_SPIKE -> "volume above $${"%.0f".format(targetValue)}"
        }
        ErrorLogger.info(TAG, "Alert set: $symbol $typeStr")
        return id
    }

    fun removeAlert(id: String) {
        alerts.remove(id)
        saveAlerts()
    }

    fun getActiveAlerts(): List<PriceAlert> =
        alerts.values.filter { it.isActive }.toList()

    fun getAlertsForSymbol(symbol: String): List<PriceAlert> =
        alerts.values.filter { it.symbol == symbol }.toList()

    fun getTriggeredAlerts(): List<TriggeredAlert> =
        synchronized(triggeredAlerts) { triggeredAlerts.toList() }

    // ═══════════════════════════════════════════════════════════════════════
    // SCAN — Update watchlist prices and check alerts
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun scan() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        for ((symbol, item) in watchlist) {
            try {
                // Find matching PerpsMarket
                val market = PerpsMarket.values().firstOrNull { it.symbol == symbol } ?: continue
                val data = PerpsMarketDataFetcher.getMarketData(market)

                // Update watchlist item
                item.lastPrice = data.price
                item.change24hPct = data.priceChange24hPct
                item.volume24h = data.volume24h
                item.lastUpdated = now

                // Check alerts for this symbol
                checkAlerts(symbol, data.price, data.priceChange24hPct, data.volume24h)
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Scan error for $symbol: ${e.message}")
            }
        }
    }

    private fun checkAlerts(symbol: String, price: Double, change: Double, volume: Double) {
        alerts.values.filter { it.symbol == symbol && it.isActive }.forEach { alert ->
            val triggered = when (alert.type) {
                AlertType.PRICE_ABOVE -> price >= alert.targetValue
                AlertType.PRICE_BELOW -> price <= alert.targetValue
                AlertType.CHANGE_ABOVE -> change >= alert.targetValue
                AlertType.CHANGE_BELOW -> change <= alert.targetValue
                AlertType.VOLUME_SPIKE -> volume >= alert.targetValue
            }

            if (triggered) {
                alert.isActive = false
                alert.triggeredAt = System.currentTimeMillis()

                val message = when (alert.type) {
                    AlertType.PRICE_ABOVE -> "$symbol hit $${"%.2f".format(price)} (target: $${"%.2f".format(alert.targetValue)})"
                    AlertType.PRICE_BELOW -> "$symbol dropped to $${"%.2f".format(price)} (target: $${"%.2f".format(alert.targetValue)})"
                    AlertType.CHANGE_ABOVE -> "$symbol up ${"%.1f".format(change)}% (target: ${"%.1f".format(alert.targetValue)}%)"
                    AlertType.CHANGE_BELOW -> "$symbol down ${"%.1f".format(change)}% (target: ${"%.1f".format(alert.targetValue)}%)"
                    AlertType.VOLUME_SPIKE -> "$symbol volume spike: $${"%.0f".format(volume)} (target: $${"%.0f".format(alert.targetValue)})"
                }

                val triggeredAlert = TriggeredAlert(alert, price, change, message = message)
                synchronized(triggeredAlerts) {
                    triggeredAlerts.add(0, triggeredAlert)
                    if (triggeredAlerts.size > 50) triggeredAlerts.removeAt(triggeredAlerts.size - 1)
                }

                ErrorLogger.info(TAG, "ALERT TRIGGERED: $message")
                onAlertTriggered?.invoke(triggeredAlert)
                saveAlerts()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUICK WATCHLIST BUILDERS
    // ═══════════════════════════════════════════════════════════════════════

    fun addTopGainersToWatchlist(limit: Int = 5) {
        val topGainers = MarketsScanner.scanResults[MarketsScanner.ScanCategory.TOP_GAINERS]
        topGainers?.take(limit)?.forEach { result ->
            addToWatchlist(result.market.symbol)
        }
    }

    fun addCategoryToWatchlist(category: MarketsScanner.ScanCategory, limit: Int = 10) {
        MarketsScanner.getMarketsForCategory(category).take(limit).forEach { market ->
            addToWatchlist(market.symbol)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

    private fun saveWatchlist() {
        prefs?.edit()?.apply {
            val arr = JSONArray()
            watchlist.values.forEach { item ->
                arr.put(JSONObject().apply {
                    put("symbol", item.symbol)
                    put("addedAt", item.addedAt)
                })
            }
            putString("watchlist", arr.toString())
            apply()
        }
    }

    private fun loadWatchlist() {
        val json = prefs?.getString("watchlist", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val symbol = obj.getString("symbol")
                watchlist[symbol] = WatchlistItem(
                    symbol = symbol,
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load watchlist: ${e.message}")
        }
    }

    private fun saveAlerts() {
        prefs?.edit()?.apply {
            val arr = JSONArray()
            alerts.values.forEach { alert ->
                arr.put(JSONObject().apply {
                    put("id", alert.id)
                    put("symbol", alert.symbol)
                    put("type", alert.type.name)
                    put("target", alert.targetValue)
                    put("active", alert.isActive)
                    put("created", alert.createdAt)
                })
            }
            putString("alerts", arr.toString())
            apply()
        }
    }

    private fun loadAlerts() {
        val json = prefs?.getString("alerts", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val alert = PriceAlert(
                    id = obj.getString("id"),
                    symbol = obj.getString("symbol"),
                    type = AlertType.valueOf(obj.getString("type")),
                    targetValue = obj.getDouble("target"),
                    isActive = obj.optBoolean("active", true),
                    createdAt = obj.optLong("created", System.currentTimeMillis())
                )
                alerts[alert.id] = alert
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load alerts: ${e.message}")
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "watchlist_size" to watchlist.size,
        "active_alerts" to alerts.values.count { it.isActive },
        "triggered_alerts" to triggeredAlerts.size,
        "total_alerts" to alerts.size
    )

    fun clear() {
        watchlist.clear()
        alerts.clear()
        synchronized(triggeredAlerts) { triggeredAlerts.clear() }
        saveWatchlist()
        saveAlerts()
    }
}
