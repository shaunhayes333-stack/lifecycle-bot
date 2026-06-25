package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.4132 — LIVE PAUSE BUTTON (capital redirect to performing lanes)
 * ════════════════════════════════════════════════════════════════════════════
 * Operator: "the pause then needs to move priority into performing lanes
 * until the scanner can return better candidates."
 *
 * When live rolling 30-trade WR drops below the floor, the bot shifts into
 * DEFENSIVE mode:
 *   - GLOBAL: only GOLD/WINNER goose verdict tokens trade
 *   - LANE PRIORITY: capital routes preferentially to the top-2 lanes by
 *     recent WR (no veto on lower lanes — just sizing tilt + admission tilt)
 *
 * Returns from DEFENSIVE only when rolling WR climbs back above the recovery
 * floor (hysteresis prevents flapping).
 */
object LivePauseButton {

    private const val PREFS_NAME = "live_pause_button"
    private const val KEY_PNLS = "recent_pnls"
    private const val KEY_LANES = "recent_lanes"
    private const val WINDOW = 30
    private const val MIN_SAMPLES = 10           // need at least N closes to engage
    // V5.0.4133 — operator-mandated tightening. V5.0.4132 ran 25/35; live data
    // showed WR sitting at 24.4% (just below the entry floor) but the lane-level
    // bleed continued because the global pause was flapping at the boundary.
    // Tighter floors with a wider recovery gap (15 pts) give discipline more
    // headroom to actually engage during DUMP regimes without flap-on-flap-off.
    private const val PAUSE_FLOOR_PCT = 30.0      // engage DEFENSIVE below this
    private const val RECOVER_FLOOR_PCT = 45.0    // exit DEFENSIVE above this (hysteresis)

    enum class Mode { NORMAL, DEFENSIVE }

    data class LaneRank(val lane: String, val n: Int, val wrPct: Double)

    data class Snapshot(
        val mode: Mode,
        val wrPct: Double,
        val n: Int,
        val topLanes: List<LaneRank>,
    )

    private val pnls = ArrayDeque<Double>(WINDOW + 1)
    private val lanes = ArrayDeque<String>(WINDOW + 1)
    @Volatile private var prefs: SharedPreferences? = null
    private val snap = AtomicReference(Snapshot(Mode.NORMAL, 0.0, 0, emptyList()))

    fun init(context: Context) {
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            restore(p)
            recompute()
        } catch (_: Throwable) {}
    }

    private fun restore(p: SharedPreferences) {
        try {
            val rawP = p.getString(KEY_PNLS, null) ?: return
            val rawL = p.getString(KEY_LANES, null) ?: return
            val ps = rawP.split(',').mapNotNull { it.toDoubleOrNull() }
            val ls = rawL.split(',').map { it.trim() }
            val n = minOf(ps.size, ls.size)
            synchronized(this) {
                pnls.clear(); lanes.clear()
                for (i in 0 until n) {
                    if (pnls.size >= WINDOW) { pnls.removeFirst(); lanes.removeFirst() }
                    pnls.addLast(ps[i]); lanes.addLast(ls[i])
                }
            }
        } catch (_: Throwable) {}
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val (ps, ls) = synchronized(this) { pnls.toList() to lanes.toList() }
            p.edit()
                .putString(KEY_PNLS, ps.joinToString(",") { it.toString() })
                .putString(KEY_LANES, ls.joinToString(","))
                .apply()
        } catch (_: Throwable) {}
    }

    /** Called from close path. Records the closed-trade outcome + lane. */
    fun recordOutcome(lane: String, pnlPct: Double) {
        try {
            val sane = when {
                pnlPct.isNaN() || pnlPct.isInfinite() -> return
                pnlPct > 5000.0 -> 5000.0
                pnlPct < -100.0 -> -100.0
                else -> pnlPct
            }
            val cleanLane = lane.ifBlank { "UNKNOWN" }.uppercase().take(24)
            synchronized(this) {
                pnls.addLast(sane); lanes.addLast(cleanLane)
                while (pnls.size > WINDOW) { pnls.removeFirst(); lanes.removeFirst() }
            }
            recompute(); persist()
        } catch (_: Throwable) {}
    }

    private fun recompute() {
        val (pSnap, lSnap) = synchronized(this) { pnls.toList() to lanes.toList() }
        val n = pSnap.size
        val wins = pSnap.count { it > 0.0 }
        val wr = if (n > 0) (wins.toDouble() / n) * 100.0 else 0.0

        val current = snap.get()
        val newMode = when {
            n < MIN_SAMPLES -> Mode.NORMAL
            current.mode == Mode.NORMAL && wr < PAUSE_FLOOR_PCT -> Mode.DEFENSIVE
            current.mode == Mode.DEFENSIVE && wr >= RECOVER_FLOOR_PCT -> Mode.NORMAL
            else -> current.mode
        }

        // Per-lane WR ranking
        val byLane = HashMap<String, IntArray>() // [n, wins]
        for (i in pSnap.indices) {
            val l = lSnap.getOrNull(i) ?: continue
            val arr = byLane.getOrPut(l) { IntArray(2) }
            arr[0] += 1
            if (pSnap[i] > 0.0) arr[1] += 1
        }
        val ranked = byLane.entries
            .filter { it.value[0] >= 3 }
            .map { LaneRank(it.key, it.value[0], (it.value[1].toDouble() / it.value[0]) * 100.0) }
            .sortedByDescending { it.wrPct }
            .take(3)

        snap.set(Snapshot(newMode, wr, n, ranked))
    }

    fun mode(): Mode = snap.get().mode
    fun isDefensive(): Boolean = snap.get().mode == Mode.DEFENSIVE
    fun currentWR(): Double = snap.get().wrPct
    fun sampleCount(): Int = snap.get().n
    fun topLanes(): List<LaneRank> = snap.get().topLanes

    /** True when a lane is currently ranked in the top performers. */
    fun isTopPerformingLane(lane: String): Boolean {
        val key = lane.uppercase()
        return snap.get().topLanes.any { it.lane == key }
    }

    /** Capital tilt: top-1 → 1.30, top-2 → 1.15, top-3 → 1.00, others → 0.70 (defensive only). */
    fun laneSizeTilt(lane: String): Double {
        if (snap.get().mode == Mode.NORMAL) return 1.0
        val key = lane.uppercase()
        val ranked = snap.get().topLanes
        val idx = ranked.indexOfFirst { it.lane == key }
        return when (idx) {
            0    -> 1.30
            1    -> 1.15
            2    -> 1.00
            else -> 0.70
        }
    }

    fun tag(): String {
        val s = snap.get()
        val lanes = s.topLanes.joinToString("|") { "${it.lane}=${"%.0f".format(it.wrPct)}%n${it.n}" }
        return "${s.mode.name}_wr${"%.0f".format(s.wrPct)}_n${s.n}_top:[$lanes]"
    }

    fun reset() {
        synchronized(this) { pnls.clear(); lanes.clear() }
        recompute(); persist()
    }
}
