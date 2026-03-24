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
 *   - Session peak wallet balance (SEPARATE for paper and live)
 *   - Win streak / loss streak (SEPARATE for paper and live)
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

    fun save(ctx: Context, isPaperMode: Boolean = true) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now   = System.currentTimeMillis()

        // Recent trades as JSON array of booleans (mode-specific)
        val perf  = SmartSizer.getPerformanceContext(0.0, 0, isPaperMode)
        val modePrefix = if (isPaperMode) "paper" else "live"
        
        val tradesJson = JSONObject().apply {
            put("saved_at",     now)
            put("${modePrefix}_session_peak", SmartSizer.getSessionPeak(isPaperMode))
            put("${modePrefix}_win_streak",   perf.winStreak)
            put("${modePrefix}_loss_streak",  perf.lossStreak)
            put("${modePrefix}_win_rate",     perf.recentWinRate)
            put("mode", modePrefix)
        }

        prefs.edit()
            .putString("session_$modePrefix", tradesJson.toString())
            .apply()
    }

    fun restore(ctx: Context, isPaperMode: Boolean = true): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modePrefix = if (isPaperMode) "paper" else "live"
        val json  = prefs.getString("session_$modePrefix", null) ?: return false

        return try {
            val obj      = JSONObject(json)
            val savedAt  = obj.optLong("saved_at", 0L)
            val age      = System.currentTimeMillis() - savedAt

            // Don't restore stale state
            if (age > MAX_AGE) {
                clear(ctx, isPaperMode)
                return false
            }

            val peak      = obj.optDouble("${modePrefix}_session_peak", 0.0)
            val winStreak = obj.optInt("${modePrefix}_win_streak", 0)
            val lossStreak= obj.optInt("${modePrefix}_loss_streak", 0)

            if (peak > 0) SmartSizer.updateSessionPeak(peak, isPaperMode)

            // Restore streaks directly — don't use recordTrade() as it
            // would cross-zero the streak counts during replay
            SmartSizer.restoreStreaks(winStreak, lossStreak, isPaperMode)

            true
        } catch (_: Exception) { false }
    }

    fun clear(ctx: Context, isPaperMode: Boolean? = null) {
        val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (isPaperMode) {
            true -> editor.remove("session_paper")
            false -> editor.remove("session_live")
            null -> editor.clear()  // Clear all
        }
        editor.apply()
    }
}
