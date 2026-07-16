package com.lifecyclebot.engine

/** V5.0.4522 — cached live lane-fanout pressure detector. */
object LiveLaneFanoutPressure {
    data class Snapshot(val active: Boolean, val ratio: Double, val liveWrPct: Double, val liveN: Int, val reason: String)
    @Volatile private var cached = Snapshot(false, 0.0, 0.0, 0, "bootstrap")
    @Volatile private var cachedAtMs = 0L
    private const val TTL_MS = 5_000L

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (nowMs - cachedAtMs < TTL_MS) return cached
        val s = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val intake = s?.phaseCounts?.get("INTAKE") ?: 0L
        val lane = s?.phaseCounts?.get("LANE_EVAL") ?: 0L
        val exec = s?.phaseCounts?.get("EXEC") ?: 0L
        val ratio = if (intake > 0L) lane.toDouble() / intake.toDouble() else 0.0
        val wr = try { LiveLayerGateRelaxer.currentLiveWrPct() } catch (_: Throwable) { 0.0 }
        val n = try { LiveLayerGateRelaxer.currentLiveTerminalCount() } catch (_: Throwable) { 0 }
        // V5.0.6266 — LANE FANOUT PRESSURE ESCAPE VALVE.
        // Op-report V5.0.6265 showed exec=0 with laneEval/intake=14.00: the
        // pressure detector was narrowing rescue eligibility while the bot
        // wasn't even executing any trades — a self-DOS loop where "no exec"
        // → "assume broken lanes" → "narrow rescue" → "still no exec". When
        // exec=0 the pressure suppression is exactly the wrong signal:
        // the pipeline needs MORE fanout breadth, not less. Only fire the
        // pressure gate when the pipeline is actually executing trades and
        // producing a measurably bad live WR — i.e. real bleed, not a
        // paralyzed intake.
        val active = intake >= 20L && ratio > 8.0 && n >= 40 && wr < 30.0 && exec > 0L
        cached = Snapshot(active, ratio, wr, n, if (active) "lane_fanout_pressure_low_wr" else "normal")
        cachedAtMs = nowMs
        return cached
    }

    fun isActive(): Boolean = snapshot().active
}
