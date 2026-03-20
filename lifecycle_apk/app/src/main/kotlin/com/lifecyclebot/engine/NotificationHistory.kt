package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * NotificationHistory
 *
 * Persists all bot alerts and trade notifications so they're never lost.
 * Stored in SharedPreferences as JSON — survives app restarts.
 * Keeps last 200 entries, oldest pruned automatically.
 */
class NotificationHistory(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("notif_history", Context.MODE_PRIVATE)

    data class NotifEntry(
        val ts: Long,
        val title: String,
        val body: String,
        val type: NotifType,
    ) {
        enum class NotifType {
            TRADE_WIN, TRADE_LOSS, SAFETY_BLOCK, CIRCUIT_BREAKER,
            NEW_TOKEN, WHALE, GRADUATION, DEV_SELL, INFO, HALT
        }
    }

    private val entries = mutableListOf<NotifEntry>()
    private var loaded  = false

    fun add(title: String, body: String, type: NotifEntry.NotifType = NotifEntry.NotifType.INFO) {
        ensureLoaded()
        entries.add(0, NotifEntry(System.currentTimeMillis(), title, body, type))
        if (entries.size > 200) entries.removeAt(entries.size - 1)
        persist()
    }

    fun getAll(): List<NotifEntry> {
        ensureLoaded()
        return entries.toList()
    }

    fun clear() {
        entries.clear()
        prefs.edit().remove("entries").apply()
    }

    private fun ensureLoaded() {
        if (loaded) return
        try {
            val json = prefs.getString("entries", null) ?: run { loaded = true; return }
            val arr  = JSONArray(json)
            entries.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                entries.add(NotifEntry(
                    ts    = o.optLong("ts", 0),
                    title = o.optString("title", ""),
                    body  = o.optString("body",  ""),
                    type  = try {
                        NotifEntry.NotifType.valueOf(o.optString("type", "INFO"))
                    } catch (_: Exception) { NotifEntry.NotifType.INFO }
                ))
            }
        } catch (_: Exception) {}
        loaded = true
    }

    private fun persist() {
        val arr = JSONArray()
        entries.take(200).forEach { e ->
            arr.put(JSONObject().apply {
                put("ts",    e.ts)
                put("title", e.title)
                put("body",  e.body)
                put("type",  e.type.name)
            })
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
