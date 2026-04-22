package com.lifecyclebot.v3.scoring

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — SessionEdgeAI
 *
 * The Neural Personality screen shows "Session: LONDON" but nothing
 * tunes behavior per session. This layer learns the bot's actual W/L
 * rate per session (ASIA / LONDON / NY / OFF_HOURS) and applies a small
 * bonus/penalty during the bot's strongest/weakest windows.
 *
 * Sessions (UTC):
 *   ASIA       00:00–07:59
 *   LONDON     08:00–12:59
 *   NY         13:00–20:59
 *   OFF_HOURS  21:00–23:59
 */
object SessionEdgeAI {

    private const val TAG = "SessionEdge"
    private const val PREFS = "session_edge_v1"

    enum class Session { ASIA, LONDON, NY, OFF_HOURS }

    private val stats = ConcurrentHashMap<Session, IntArray>()  // [wins, losses]
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Session.values().forEach { stats[it] = intArrayOf(0, 0) }
        try {
            val blob = prefs?.getString("sessions_json", null) ?: return
            val root = JSONObject(blob)
            root.keys().forEach { k ->
                val arr = root.getJSONArray(k)
                runCatching { stats[Session.valueOf(k)] = intArrayOf(arr.getInt(0), arr.getInt(1)) }
            }
        } catch (_: Exception) {}
    }

    fun currentSession(): Session {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 0..7   -> Session.ASIA
            in 8..12  -> Session.LONDON
            in 13..20 -> Session.NY
            else      -> Session.OFF_HOURS
        }
    }

    fun recordOutcome(session: Session, won: Boolean) {
        val s = stats.getOrPut(session) { intArrayOf(0, 0) }
        if (won) s[0]++ else s[1]++
        save()
    }

    fun score(@Suppress("UNUSED_PARAMETER") candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val sess = currentSession()
        val s = stats[sess] ?: intArrayOf(0, 0)
        val total = s[0] + s[1]
        if (total < 20) return ScoreComponent("SessionEdgeAI", 0, "🕰️ $sess: too few samples ($total)")

        val wr = s[0].toDouble() / total
        val value = when {
            wr > 0.58 -> +4
            wr > 0.52 -> +2
            wr < 0.42 -> -4
            wr < 0.48 -> -2
            else      -> 0
        }
        return ScoreComponent("SessionEdgeAI", value,
            "🕰️ $sess WR=${"%.0f".format(wr * 100)}%% (${s[0]}/$total)")
    }

    private fun save() {
        val p = prefs ?: return
        try {
            val root = JSONObject()
            stats.forEach { (k, v) -> root.put(k.name, org.json.JSONArray(listOf(v[0], v[1]))) }
            p.edit().putString("sessions_json", root.toString()).apply()
        } catch (_: Exception) {}
    }
}
