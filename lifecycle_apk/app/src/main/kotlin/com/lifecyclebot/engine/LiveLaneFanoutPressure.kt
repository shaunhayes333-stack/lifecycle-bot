package com.lifecyclebot.engine

/** V5.0.4521 — cached live lane-fanout pressure detector. */
object LiveLaneFanoutPressure {
    data class Snapshot(val active: Boolean, val ratio: Double, val liveWrPct: Double, val liveN: Int, val reason: String)
    @Volatile private var cached = Snapshot(false, 0.0, 0.0, 0, "bootstrap")
    @Volatile private var cachedAtMs = 0L
    private const val TTL_MS = 5_000L

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (nowMs - cachedAtMs < TTL_MS) return cached
        val s = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val intake = s?.intakeCount ?: 0L
        val lane = s?.phaseCounts?.get("LANE_EVAL") ?: 0L
        val ratio = if (intake > 0L) lane.toDouble() / intake.toDouble() else 0.0
        val wr = try { LiveLayerGateRelaxer.currentLiveWrPct() } catch (_: Throwable) { 0.0 }
        val n = try { LiveLayerGateRelaxer.currentLiveTerminalCount() } catch (_: Throwable) { 0 }
        val active = intake >= 20L && ratio > 8.0 && n >= 40 && wr < 30.0
        cached = Snapshot(active, ratio, wr, n, if (active) "lane_fanout_pressure_low_wr" else "normal")
        cachedAtMs = nowMs
        return cached
    }

    fun isActive(): Boolean = snapshot().active
}
