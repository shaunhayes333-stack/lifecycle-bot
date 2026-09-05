package com.lifecyclebot.perps

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.CanonicalJournalProjectionRepair6677
import com.lifecyclebot.engine.truth.MarketDataProvenance6471
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

            // V5.0.6677 — never rehydrate a CryptoAlt presentation row whose
            // ENTRY basis is one of the canonical sentinel fingerprints. These
            // are not legitimate low-priced assets: the exact values are owned
            // by MarketDataProvenance6471 as known placeholder fingerprints.
            // Local JSON sanitisation is cheap and synchronous; canonical refund
            // + journal repair is scheduled off-thread so app/bootstrap UI cannot
            // regress into the old main-thread ANR/smoke-test failure.
            val sanitized = if (traderKey.equals("crypto_alt", ignoreCase = true)) {
                try { CanonicalJournalProjectionRepair6677.scheduleRepair6677() } catch (_: Throwable) {}
                val valid = out.filterNot { row ->
                    MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(
                        row.optDouble("entryPrice", Double.NaN)
                    )
                }
                val removed = out.size - valid.size
                if (removed > 0) {
                    saveAll(traderKey, valid)
                    try { PipelineHealthCollector.labelInc("CRYPTO_SENTINEL_PERSISTED_ROWS_PURGED_6677") } catch (_: Throwable) {}
                    ErrorLogger.warn(TAG, "[crypto_alt] purged $removed persisted sentinel-entry row(s); canonical neutral repair scheduled")
                }
                valid
            } else {
                out
            }

            if (sanitized.isNotEmpty()) {
                ErrorLogger.info(TAG, "[$traderKey] rehydrated ${sanitized.size} open positions from SharedPrefs")
            }
            sanitized
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "[$traderKey] load failed: ${e.message}")
            emptyList()
        }
    }

    fun clear(traderKey: String) {
        prefsByTrader[traderKey]?.edit()?.clear()?.apply()
    }
}
