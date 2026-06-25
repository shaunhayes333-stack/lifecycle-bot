package com.lifecyclebot.perps.crypto.brain

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4151 — CRYPTO PER-LANE TIMEOUT GATE (ISOLATED)
 * ════════════════════════════════════════════════════════════════════════════
 * Mirrors `engine.LaneTimeoutGate` but persists to its own SharedPreferences
 * file (`crypto_lane_timeout_gate`). Identical algorithm: 30-trade rolling
 * window per lane, 25% timeout floor / 45% recover floor with hysteresis.
 */
object CryptoLaneTimeoutGate {

    private const val PREFS_NAME = "crypto_lane_timeout_gate"
    private const val WINDOW = 30
    private const val MIN_SAMPLES = 12
    private const val TIMEOUT_FLOOR_PCT = 25.0
    private const val RECOVER_FLOOR_PCT = 45.0

    enum class Status { COLD_START, NORMAL, TIMEOUT }

    private data class LaneState(
        val recent: ArrayDeque<Double> = ArrayDeque(WINDOW + 1),
        @Volatile var status: Status = Status.COLD_START,
        @Volatile var wrPct: Double = 0.0,
        @Volatile var n: Int = 0,
    )

    private val byLane = ConcurrentHashMap<String, LaneState>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            restoreAll(p)
        } catch (_: Throwable) {}
    }

    private fun restoreAll(p: SharedPreferences) {
        try {
            for ((k, v) in p.all) {
                if (v !is String) continue
                val lane = k.uppercase()
                val state = byLane.getOrPut(lane) { LaneState() }
                synchronized(state) {
                    state.recent.clear()
                    v.split(',').mapNotNull { it.toDoubleOrNull() }.forEach { pnl ->
                        if (state.recent.size >= WINDOW) state.recent.removeFirst()
                        state.recent.addLast(pnl)
                    }
                }
                recomputeOne(state)
            }
        } catch (_: Throwable) {}
    }

    private fun persist(lane: String, state: LaneState) {
        val p = prefs ?: return
        try {
            val snap = synchronized(state) { state.recent.toList() }
            p.edit().putString(lane, snap.joinToString(",") { it.toString() }).apply()
        } catch (_: Throwable) {}
    }

    fun recordOutcome(lane: String, pnlPct: Double) {
        try {
            if (pnlPct.isNaN() || pnlPct.isInfinite()) return
            val sane = pnlPct.coerceIn(-100.0, 5000.0)
            val key = lane.ifBlank { return }.uppercase()
            val state = byLane.getOrPut(key) { LaneState() }
            synchronized(state) {
                state.recent.addLast(sane)
                while (state.recent.size > WINDOW) state.recent.removeFirst()
            }
            recomputeOne(state)
            persist(key, state)
        } catch (_: Throwable) {}
    }

    private fun recomputeOne(state: LaneState) {
        val snap = synchronized(state) { state.recent.toList() }
        val n = snap.size
        val wins = snap.count { it > 0.0 }
        val wr = if (n > 0) (wins.toDouble() / n) * 100.0 else 0.0
        state.n = n; state.wrPct = wr
        state.status = when {
            n < MIN_SAMPLES -> Status.COLD_START
            state.status == Status.NORMAL && wr < TIMEOUT_FLOOR_PCT -> Status.TIMEOUT
            state.status == Status.TIMEOUT && wr >= RECOVER_FLOOR_PCT -> Status.NORMAL
            state.status == Status.COLD_START -> Status.NORMAL
            else -> state.status
        }
    }

    fun status(lane: String): Status = byLane[lane.uppercase()]?.status ?: Status.COLD_START
    fun isTimedOut(lane: String): Boolean = status(lane) == Status.TIMEOUT
    fun tag(lane: String): String {
        val s = byLane[lane.uppercase()] ?: return "${lane.uppercase()}_NO_DATA"
        return "${lane.uppercase()}_${s.status.name}_wr${"%.0f".format(s.wrPct)}_n${s.n}"
    }
}
