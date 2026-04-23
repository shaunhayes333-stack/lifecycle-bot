package com.lifecyclebot.collective

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalOrphanStore — V5.9.171
 *
 * Local SharedPreferences failsafe for paper-position orphan recovery. The
 * existing [PaperOrphanReconciler] only works when Turso is reachable — if
 * the app is updated while offline the paper capital that was debited from
 * the unified wallet stays permanently stuck and the user's paper balance
 * looks corrupted. This store mirrors every OPEN paper position locally so
 * we can refund it at next startup regardless of network.
 *
 * USAGE — every trader that opens a paper position must call:
 *
 *     LocalOrphanStore.recordOpen(trader = "Metals", posId = position.id,
 *                                 sizeSol = position.size, symbol = "GOLD")
 *
 * On close:
 *
 *     LocalOrphanStore.clear(posId)
 *
 * On startup (once, from BotService):
 *
 *     LocalOrphanStore.init(context)
 *     LocalOrphanStore.reconcileAll()
 *
 * Paper mode only. Live positions are on-chain and recover themselves.
 */
object LocalOrphanStore {

    private const val TAG = "LocalOrphan"
    private const val PREFS_NAME = "local_orphan_store"
    private const val KEY_ENTRIES = "entries_json"

    private var prefs: SharedPreferences? = null
    private val openEntries = ConcurrentHashMap<String, Entry>()

    data class Entry(
        val posId: String,
        val trader: String,
        val sizeSol: Double,
        val symbol: String,
        val openedAtMs: Long,
    )

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    fun recordOpen(trader: String, posId: String, sizeSol: Double, symbol: String = "") {
        if (posId.isBlank() || sizeSol <= 0.0) return
        openEntries[posId] = Entry(
            posId = posId,
            trader = trader,
            sizeSol = sizeSol,
            symbol = symbol,
            openedAtMs = System.currentTimeMillis(),
        )
        persist()
    }

    fun clear(posId: String) {
        if (openEntries.remove(posId) != null) persist()
    }

    /**
     * Refund every local orphaned paper position back to the unified paper
     * wallet and purge the store. Safe to call at startup after the cloud
     * reconciler has run — the set it operates on is strictly the positions
     * that were never closed properly.
     */
    fun reconcileAll(): Double {
        if (openEntries.isEmpty()) return 0.0
        val entries = openEntries.values.toList()
        var refunded = 0.0
        entries.forEach { e ->
            refunded += e.sizeSol
            try {
                BotService.creditUnifiedPaperSol(
                    delta = e.sizeSol,
                    source = "${e.trader}.localReconcile[${e.symbol}]",
                )
            } catch (_: Exception) {}
        }
        openEntries.clear()
        persist()
        ErrorLogger.info(
            TAG,
            "♻️ LocalOrphanStore reconciled ${entries.size} stuck paper positions → " +
                "refunded ${"%.3f".format(refunded)} SOL (local failsafe; Turso-independent)",
        )
        return refunded
    }

    /**
     * Expose a snapshot for diagnostics / UI. Never mutate the returned list.
     */
    fun snapshot(): List<Entry> = openEntries.values.toList()

    private fun persist() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            openEntries.values.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("posId", e.posId)
                        .put("trader", e.trader)
                        .put("sizeSol", e.sizeSol)
                        .put("symbol", e.symbol)
                        .put("openedAtMs", e.openedAtMs)
                )
            }
            p.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "persist error: ${e.message}")
        }
    }

    private fun load() {
        val p = prefs ?: return
        try {
            val raw = p.getString(KEY_ENTRIES, null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val posId = o.optString("posId")
                if (posId.isBlank()) continue
                openEntries[posId] = Entry(
                    posId = posId,
                    trader = o.optString("trader", "unknown"),
                    sizeSol = o.optDouble("sizeSol", 0.0),
                    symbol = o.optString("symbol", ""),
                    openedAtMs = o.optLong("openedAtMs", System.currentTimeMillis()),
                )
            }
            if (openEntries.isNotEmpty()) {
                ErrorLogger.info(TAG, "📂 Loaded ${openEntries.size} pending paper positions from local store")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "load error: ${e.message}")
        }
    }
}
