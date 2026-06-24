package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.4126 — MOONSHOT ADAPTIVE GATE
 * ════════════════════════════════════════════════════════════════════════════
 * Operator mandate: "its meant to fluidly pivot bro! not disable. each lane
 * has a brain specifically for that lane use it not disabled the lane!!!"
 *
 * The MOONSHOT lane was bleeding (-0.85 SOL, 24.6% WR over 248 trades) and
 * the previous learned gates (ScoreExpectancyTracker.shouldReject,
 * LosingPatternMemory.recommendedSlPct) are paper-only or per-bucket only —
 * none of them respond to GLOBAL LANE WR trending the wrong way in LIVE.
 *
 * This gate fluidly pivots the lane's quality bar based on its own recent
 * outcomes:
 *   - Tracks the last `WINDOW` closed trades for MOONSHOT.
 *   - Hybrid recency-weighting: most-recent 25 trades count 2.0×, prior 25
 *     count 1.0×, older trades drop out of the rolling window.
 *   - Returns a `scoreFloorBias` (additive nudge to the minimum entry score)
 *     that tightens when recent WR drops below `TARGET_WR_PCT`, loosens when
 *     it climbs above. Bounded; never a veto; never zeroes the lane out.
 *   - Auto-loosens back as WR climbs so the lane self-recovers without
 *     manual intervention.
 *
 * Doctrine #86: bounded, fail-open, never a hard reject.
 * Phase tags also surface in PipelineHealth for operator visibility.
 */
object MoonshotAdaptiveGate {

    private const val TAG = "MoonshotGate"
    private const val PREFS_NAME = "moonshot_adaptive_gate"
    private const val KEY_PNLS = "recent_pnls"

    // Hybrid recency window: 50 most-recent get 2x weight, prior 50 get 1x.
    private const val WINDOW = 100
    private const val RECENT_HALF = 50

    // Target win-rate the gate steers toward. MOONSHOT is asymmetric — a few
    // mega runners pay for many losers — so we accept a relatively low WR.
    private const val TARGET_WR_PCT = 35.0
    // Need at least this many closes before the gate engages — below this we
    // stay neutral so a cold start can't accidentally tighten the lane.
    private const val MIN_SAMPLES_TO_GATE = 20

    // Bias bounds: -5 (lane is winning, breathe out) → +20 (lane is hemorrhaging, tighten).
    private const val MAX_TIGHTEN_BIAS = 20
    private const val MAX_LOOSEN_BIAS = -5

    enum class Phase { COLD_START, AGGRESSIVE, NEUTRAL, DEFENSIVE, EMERGENCY }

    private data class Snapshot(
        val n: Int,
        val wrPct: Double,
        val phase: Phase,
        val floorBias: Int,
    )

    private val window = ArrayDeque<Double>(WINDOW + 1)
    @Volatile private var prefs: SharedPreferences? = null
    private val snap = AtomicReference(Snapshot(0, 0.0, Phase.COLD_START, 0))

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
            val raw = p.getString(KEY_PNLS, null) ?: return
            if (raw.isBlank()) return
            synchronized(window) {
                window.clear()
                raw.split(',').forEach { tok ->
                    val v = tok.toDoubleOrNull() ?: return@forEach
                    if (window.size >= WINDOW) window.removeFirst()
                    window.addLast(v)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val snapshot = synchronized(window) { window.toList() }
            val raw = snapshot.joinToString(",") { it.toString() }
            p.edit().putString(KEY_PNLS, raw).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Record a closed MOONSHOT trade outcome. Called from MoonshotTraderAI.closePosition.
     * NaN/Inf dropped; values clamped to [-100, 5000] so a feed glitch can't poison the gate.
     */
    fun recordOutcome(pnlPct: Double) {
        try {
            val sane = when {
                pnlPct.isNaN() || pnlPct.isInfinite() -> return
                pnlPct > 5000.0 -> 5000.0
                pnlPct < -100.0 -> -100.0
                else -> pnlPct
            }
            synchronized(window) {
                window.addLast(sane)
                while (window.size > WINDOW) window.removeFirst()
            }
            recompute()
            persist()
        } catch (_: Throwable) {}
    }

    /**
     * Hybrid recency-weighted win-rate over the rolling window.
     * Last 50 trades count 2.0×, prior 50 count 1.0×.
     * A "win" is pnlPct > 0 (no fee floor — gate is for quality steering, not P&L accounting).
     */
    private fun computeWeightedWR(): Pair<Int, Double> {
        val snapshot: List<Double> = synchronized(window) { window.toList() }
        val n = snapshot.size
        if (n == 0) return 0 to 0.0
        var wins = 0.0
        var total = 0.0
        // Newest entries are at the tail of the deque; assign higher weight to the tail.
        snapshot.forEachIndexed { idx, pnl ->
            val ageFromNewest = (n - 1) - idx
            val weight = if (ageFromNewest < RECENT_HALF) 2.0 else 1.0
            total += weight
            if (pnl > 0.0) wins += weight
        }
        val wr = if (total > 0.0) (wins / total) * 100.0 else 0.0
        return n to wr
    }

    private fun phaseFor(n: Int, wr: Double): Phase {
        if (n < MIN_SAMPLES_TO_GATE) return Phase.COLD_START
        return when {
            wr < (TARGET_WR_PCT - 20.0) -> Phase.EMERGENCY   // < 15%
            wr < (TARGET_WR_PCT - 10.0) -> Phase.DEFENSIVE   // 15–25%
            wr <  TARGET_WR_PCT          -> Phase.NEUTRAL    // 25–35% (mild tighten)
            wr < (TARGET_WR_PCT + 15.0) -> Phase.NEUTRAL    // 35–50% (target band)
            else                          -> Phase.AGGRESSIVE // >= 50%
        }
    }

    private fun biasFor(phase: Phase, wr: Double): Int {
        return when (phase) {
            Phase.COLD_START -> 0
            Phase.EMERGENCY  -> MAX_TIGHTEN_BIAS                 // +20
            Phase.DEFENSIVE  -> 12
            Phase.NEUTRAL    -> if (wr < TARGET_WR_PCT) 6 else 0 // mild tighten if below target
            Phase.AGGRESSIVE -> MAX_LOOSEN_BIAS                  // -5 (let it breathe)
        }
    }

    private fun recompute() {
        val (n, wr) = computeWeightedWR()
        val phase = phaseFor(n, wr)
        val bias = biasFor(phase, wr).coerceIn(MAX_LOOSEN_BIAS, MAX_TIGHTEN_BIAS)
        snap.set(Snapshot(n, wr, phase, bias))
    }

    /**
     * Returns an additive score-floor bias to be ADDED to the lane's
     * minimum-entry-score threshold.  Bounded in [-5, +20].
     *   - Negative → the lane is doing well; let more trades through.
     *   - Positive → the lane is bleeding; demand stronger setups.
     */
    fun scoreFloorBias(): Int = snap.get().floorBias

    /** Recency-weighted win-rate percentage over the rolling window. */
    fun currentWR(): Double = snap.get().wrPct

    /** Number of trades currently in the gate's rolling window. */
    fun sampleCount(): Int = snap.get().n

    /** Current pivot phase — useful for telemetry / log tags / rejection reasons. */
    fun phase(): Phase = snap.get().phase

    /** Short tag for log lines / rejection messages. */
    fun phaseTag(): String {
        val s = snap.get()
        return "${s.phase.name}_wr${"%.0f".format(s.wrPct)}_n${s.n}_bias${if (s.floorBias >= 0) "+" else ""}${s.floorBias}"
    }

    /** Operator hook to wipe history (e.g. after a major code/strategy reset). */
    fun reset() {
        synchronized(window) { window.clear() }
        recompute()
        persist()
    }
}
