package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object RuggedContracts {
    private const val PREFS_NAME = "rugged_contracts"
    private var ctx: Context? = null
    private val blacklist = ConcurrentHashMap<String, Double>()  // mint -> loss%

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info("RuggedContracts", "💀 Loaded ${blacklist.size} blacklisted contracts")
    }

    fun add(mint: String, symbol: String, lossPct: Double) {
        blacklist[mint] = lossPct
        save()
        ErrorLogger.info("RuggedContracts", "💀 Blacklisted $symbol ($mint) - lost ${lossPct.toInt()}%")

        // V5.9.357 — every blacklist event is also a peer-loss signal for
        // PeerAlphaVerificationAI: this instance just lost on this mint, so
        // any future scoring of the same mint within 2h gets the -8 veto.
        try { com.lifecyclebot.v3.scoring.PeerAlphaVerificationAI.markPeerLoss(mint) } catch (_: Exception) {}

        // Report to Collective Learning hive mind (async)
        if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
            GlobalScope.launch(AppDispatchers.sideEffect) {
                try {
                    val reason = when {
                        lossPct <= -50 -> "RUG_PULL"
                        lossPct <= -33 -> "SEVERE_LOSS"
                        else -> "LOSS"
                    }
                    val severity = when {
                        lossPct <= -70 -> 5
                        lossPct <= -50 -> 4
                        lossPct <= -33 -> 3
                        else -> 2
                    }
                    com.lifecyclebot.collective.CollectiveLearning.reportBlacklistedToken(
                        mint = mint,
                        symbol = symbol,
                        reason = reason,
                        severity = severity
                    )

                    // Track contribution for analytics dashboard
                    CollectiveAnalytics.recordBlacklistReport()

                    ErrorLogger.info("RuggedContracts", "🌐 Reported $symbol to collective blacklist")
                } catch (e: Exception) {
                    ErrorLogger.debug("RuggedContracts", "Collective report error: ${e.message}")
                }
            }
        }
    }

    fun isBlacklisted(mint: String): Boolean = blacklist.containsKey(mint)

    fun getCount(): Int = blacklist.size

    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = org.json.JSONObject()
            blacklist.forEach { (k, v) -> json.put(k, v) }
            prefs.edit().putString("blacklist", json.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("blacklist", null) ?: return
            val obj = org.json.JSONObject(json)
            obj.keys().forEach { key ->
                blacklist[key] = obj.optDouble(key, 0.0)
            }
        } catch (_: Exception) {}
    }
}
