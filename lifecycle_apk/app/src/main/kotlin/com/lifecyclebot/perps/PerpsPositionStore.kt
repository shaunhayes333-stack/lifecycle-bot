package com.lifecyclebot.perps

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.CanonicalSentinelEntryRepair6677
import com.lifecyclebot.engine.truth.MarketDataProvenance6471
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

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
 * V5.0.6678 — persistence reads may sanitize their own presentation rows, but
 * they must not invoke a global typed-event -> journal projection repair. The
 * only asynchronous repair retained here is the narrow CryptoAlt sentinel
 * refund, which mutates through CanonicalPaperTransaction6486 and is guarded
 * so repeated UI/bootstrap reads cannot stack repair workers.
 */
object PerpsPositionStore {

    private const val TAG = "PerpsPositionStore"
    private val prefsByTrader = mutableMapOf<String, SharedPreferences>()
    private val sentinelRepairRunning6678 = AtomicBoolean(false)

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

    private fun scheduleSentinelRepair6678() {
        if (!sentinelRepairRunning6678.compareAndSet(false, true)) return
        Thread({
            try {
                CanonicalSentinelEntryRepair6677.repairOpenPaperCryptoAltSentinels()
            } catch (t: Throwable) {
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_SENTINEL_REPAIR_FAILED_6678")
                    ErrorLogger.warn(TAG, "[crypto_alt] sentinel repair failed: ${t.message}")
                } catch (_: Throwable) {}
            } finally {
                sentinelRepairRunning6678.set(false)
            }
        }, "aate-crypto-sentinel-repair-6678").apply {
            isDaemon = true
            start()
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

            // Never rehydrate a CryptoAlt presentation row whose ENTRY basis is
            // a known canonical sentinel fingerprint. Local JSON cleanup stays
            // synchronous; the narrow canonical refund runs once off-thread.
            val sanitized = if (traderKey.equals("crypto_alt", ignoreCase = true)) {
                val valid = out.filterNot { row ->
                    MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(
                        row.optDouble("entryPrice", Double.NaN)
                    )
                }
                val removed = out.size - valid.size
                if (removed > 0) {
                    saveAll(traderKey, valid)
                    scheduleSentinelRepair6678()
                    try { PipelineHealthCollector.labelInc("CRYPTO_SENTINEL_PERSISTED_ROWS_PURGED_6677") } catch (_: Throwable) {}
                    ErrorLogger.warn(TAG, "[crypto_alt] purged $removed persisted sentinel-entry row(s); narrow canonical refund scheduled")
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
