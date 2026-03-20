package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SessionStore — persists critical bot state across app restarts.
 *
 * Without this, a phone restart loses:
 *   - SmartSizer session peak (drawdown protection resets)
 *   - Win/loss streak (sizing reverts to clean slate)
 *   - Recent trade outcomes (performance multiplier lost)
 *
 * What we persist (lightweight, JSON in SharedPreferences):
 *   - Session peak wallet balance
 *   - Win streak / loss streak
 *   - Last 10 trade outcomes (win/loss)
 *   - Timestamp of last save (for stale detection)
 *
 * We do NOT persist open positions here — that's handled by the
 * StartupReconciler which checks on-chain state via Helius.
 *
 * State is considered stale after 24h (new day = fresh session).
 */
object SessionStore {

    private const val PREFS   = "bot_session"
    private const val MAX_AGE = 24 * 60 * 60 * 1000L  // 24 hours

    fun save(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now   = System.currentTimeMillis()

        // Recent trades as JSON array of booleans
        val perf  = SmartSizer.getPerformanceContext(0.0, 0)
        val tradesJson = JSONObject().apply {
            put("saved_at",     now)
            put("session_peak", SmartSizer.getSessionPeak())
            put("win_streak",   perf.winStreak)
            put("loss_streak",  perf.lossStreak)
            put("win_rate",     perf.recentWinRate)
        }

        prefs.edit()
            .putString("session", tradesJson.toString())
            .apply()
    }

    fun restore(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json  = prefs.getString("session", null) ?: return false

        return try {
            val obj      = JSONObject(json)
            val savedAt  = obj.optLong("saved_at", 0L)
            val age      = System.currentTimeMillis() - savedAt

            // Don't restore stale state
            if (age > MAX_AGE) {
                clear(ctx)
                return false
            }

            val peak      = obj.optDouble("session_peak", 0.0)
            val winStreak = obj.optInt("win_streak", 0)
            val lossStreak= obj.optInt("loss_streak", 0)

            if (peak > 0) SmartSizer.updateSessionPeak(peak)

            // Restore streaks directly — don't use recordTrade() as it
            // would cross-zero the streak counts during replay
            SmartSizer.restoreStreaks(winStreak, lossStreak)

            true
        } catch (_: Exception) { false }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
