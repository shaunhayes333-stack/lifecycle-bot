package com.lifecyclebot.perps

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * PerpsPositionStore — V5.9.178
 *
 * REAL persistence for open perps positions across app updates/restarts.
 * Each trader writes its own SharedPrefs file with a JSONArray of open
 * positions, and rehydrates them at init() time. Unlike LocalOrphanStore
 * (which only REFUNDED stranded SOL) this store PRESERVES the actual
 * positions so the bot keeps tracking them after an update instead of
 * silently closing them and wiping the user's entries.
 *
 * USAGE (per trader):
 *
 *   // At init, once:
 *   PerpsPositionStore.init(context, TRADER_KEY)
 *   val saved: List<JSONObject> = PerpsPositionStore.loadAll(TRADER_KEY)
 *   saved.forEach { json -> positions[json.getString("id")] = Position.fromJson(json) }
 *
 *   // After every open / close / update of the maps:
 *   PerpsPositionStore.saveAll(TRADER_KEY, positions.values.map { it.toJson() })
 */
object PerpsPositionStore {

    private const val TAG = "PerpsPositionStore"
    private val prefsByTrader = mutableMapOf<String, SharedPreferences>()

    fun init(context: Context, traderKey: String) {
        if (prefsByTrader.containsKey(traderKey)) return
        prefsByTrader[traderKey] = context.applicationContext
            .getSharedPreferences("perps_positions_$traderKey", Context.MODE_PRIVATE)
    }

    fun saveAll(traderKey: String, positions: List<JSONObject>) {
        val p = prefsByTrader[traderKey] ?: return
        try {
            val arr = JSONArray()
            positions.forEach { arr.put(it) }
            p.edit().putString("positions", arr.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "[$traderKey] save failed: ${e.message}")
        }
    }

    fun loadAll(traderKey: String): List<JSONObject> {
        val p = prefsByTrader[traderKey] ?: return emptyList()
        return try {
            val raw = p.getString("positions", null) ?: return emptyList()
            val arr = JSONArray(raw)
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) {
                ErrorLogger.info(TAG, "[$traderKey] rehydrated ${out.size} open positions from SharedPrefs")
            }
            out
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "[$traderKey] load failed: ${e.message}")
            emptyList()
        }
    }

    fun clear(traderKey: String) {
        prefsByTrader[traderKey]?.edit()?.clear()?.apply()
    }
}
