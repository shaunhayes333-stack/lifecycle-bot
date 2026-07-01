package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4588 — LANE AUTO-PAUSE GUARD (operator P0 tasks a + b).
 *
 * Operator directive on 2026-02:
 *   • "EXPRESS needs to find the right strategy and tokens to trade!
 *      theres real opportunity for profit its just dumb at the source."
 *   • "MANIPULATED can be paused until llm lab proves a strategy thats
 *      profitable and is introduced then unpaused"
 *
 * The lanes literally have 0/19 (EXPRESS) and 5/33 with -50% EV
 * (MANIPULATED) as of build 5.0.4587 — no size shaping short of
 * multiplying-by-zero recovers from that. Rather than force zero-size
 * (which hides the lane from telemetry and pattern learning), this
 * guard blocks admission at the FDG level and marks the lane
 * "quarantined — awaiting LLM Lab strategy".
 *
 * A paused lane can still be run in the LLM Lab sandbox / shadow paper
 * — the guard only blocks the LIVE admission path.
 *
 * Rules (auto-pause fires when ALL true for the lane):
 *   • Sample floor:  n >= 15 trainable closes
 *   • Verdict:       (wins == 0)  OR  (WR < 20% AND EV <= -40%)
 *   • Not manually resumed since the last auto-pause event
 *
 * Doctrine:
 *   • Never touches paper / sandbox / lab paths — only LIVE admission.
 *   • Fail-open: any exception returns false (no block).
 *   • Persists across restarts via LearningPersistence so a device
 *     reboot doesn't silently reopen a proven-toxic lane.
 *   • Manual resume via LaneAutoPauseGuard.manualResume(lane) once
 *     an LLM Lab shadow proof shows >30% WR (or operator override).
 */
object LaneAutoPauseGuard {
    const val VERSION = "V5.0.4588_LANE_AUTO_PAUSE"
    private const val PERSIST_KEY = "LANE_AUTO_PAUSE_STATE"

    // Triggers
    private const val MIN_SAMPLE = 15
    private const val ZERO_WIN_MIN_SAMPLE = 15
    private const val TOXIC_WR_PCT = 20.0
    private const val TOXIC_EV_PCT = -40.0
    private const val TOXIC_MIN_SAMPLE = 20

    data class PauseState(
        val lane: String,
        val pausedAt: Long,
        val reason: String,
        val sample: Int,
        val wins: Int,
        val wrPct: Double,
        val evPct: Double,
    )

    private val paused = ConcurrentHashMap<String, PauseState>()
    @Volatile private var loaded = false
    private val lastEvalMs = AtomicLong(0L)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val blob = LearningPersistence.load(PERSIST_KEY) ?: return
                val arr = org.json.JSONArray(blob)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lane = o.optString("lane", "").uppercase()
                    if (lane.isEmpty()) continue
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = o.optLong("pausedAt", System.currentTimeMillis()),
                        reason = o.optString("reason", "restored"),
                        sample = o.optInt("sample", 0),
                        wins = o.optInt("wins", 0),
                        wrPct = o.optDouble("wrPct", 0.0),
                        evPct = o.optDouble("evPct", 0.0),
                    )
                }
            } catch (_: Throwable) {}
        }
    }

    private fun persistAsync() {
        try {
            val arr = org.json.JSONArray()
            paused.values.forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("lane", s.lane)
                        .put("pausedAt", s.pausedAt)
                        .put("reason", s.reason)
                        .put("sample", s.sample)
                        .put("wins", s.wins)
                        .put("wrPct", s.wrPct)
                        .put("evPct", s.evPct),
                )
            }
            LearningPersistence.save(PERSIST_KEY, arr.toString())
        } catch (_: Throwable) {}
    }

    /**
     * Cheap read used by FDG on every admission. Fail-open on any
     * exception. Returns null if lane is not paused; returns the pause
     * state if it is.
     */
    fun statusFor(lane: String?): PauseState? {
        if (lane.isNullOrBlank()) return null
        ensureLoaded()
        return paused[lane.uppercase()]
    }

    /** True if the lane is currently auto-paused. */
    fun isPaused(lane: String?): Boolean = statusFor(lane) != null

    /**
     * Snapshot all trainable lane telemetry from LiveProbabilityEngine
     * (which is already the clean-truth source) and mark toxic lanes.
     * Cheap; safe to call once per bot-loop cycle (rate-limited to 30s).
     */
    fun evaluateLive() {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < 30_000L) return
        if (!lastEvalMs.compareAndSet(last, now)) return
        ensureLoaded()
        try {
            val snapshots = LiveProbabilityEngine.laneSnapshots()
            var mutated = false
            for (snap in snapshots) {
                val lane = snap.lane.uppercase()
                if (paused.containsKey(lane)) continue
                val zeroWin = snap.sample >= ZERO_WIN_MIN_SAMPLE && snap.wins == 0
                val toxic = snap.sample >= TOXIC_MIN_SAMPLE &&
                    snap.wrPct < TOXIC_WR_PCT &&
                    snap.evPct <= TOXIC_EV_PCT
                if (zeroWin || toxic) {
                    val reason = if (zeroWin) "zero_win_n${snap.sample}" else "toxic_wr${"%.0f".format(snap.wrPct)}_ev${"%.0f".format(snap.evPct)}"
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = now,
                        reason = reason,
                        sample = snap.sample,
                        wins = snap.wins,
                        wrPct = snap.wrPct,
                        evPct = snap.evPct,
                    )
                    mutated = true
                    try {
                        ErrorLogger.warn(
                            "LaneAutoPauseGuard",
                            "🛑 LANE_AUTO_PAUSED lane=$lane reason=$reason n=${snap.sample} wr=${"%.1f".format(snap.wrPct)}% ev=${"%.1f".format(snap.evPct)}% — awaiting LLM Lab shadow-proof to resume",
                        )
                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_$lane")
                    } catch (_: Throwable) {}
                }
            }
            if (mutated) persistAsync()
        } catch (_: Throwable) {}
    }

    /** Manual resume — for LLM Lab success or operator override. */
    fun manualResume(lane: String, note: String = "manual") {
        ensureLoaded()
        val removed = paused.remove(lane.uppercase())
        if (removed != null) {
            try {
                ErrorLogger.info(
                    "LaneAutoPauseGuard",
                    "✅ LANE_MANUAL_RESUMED lane=${removed.lane} originalReason=${removed.reason} pausedFor=${(System.currentTimeMillis() - removed.pausedAt) / 60_000L}m note=$note",
                )
                PipelineHealthCollector.labelInc("LANE_MANUAL_RESUMED_${removed.lane}")
            } catch (_: Throwable) {}
            persistAsync()
        }
    }

    /** Diagnostic status line for reports. */
    fun statusLine(): String {
        ensureLoaded()
        if (paused.isEmpty()) return "$VERSION: no lanes paused"
        val s = paused.values.joinToString(" | ") { p ->
            "${p.lane}(n=${p.sample} wr=${"%.0f".format(p.wrPct)}% ev=${"%.0f".format(p.evPct)}% reason=${p.reason})"
        }
        return "$VERSION: ${paused.size} paused → $s"
    }

    fun pausedLanes(): Set<String> {
        ensureLoaded()
        return paused.keys.toSet()
    }
}
