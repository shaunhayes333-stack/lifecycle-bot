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
        val ratio = if (intake > 0L) lane.toDouble() / intake.toDouble() else 0.0
        val wr = try { LiveLayerGateRelaxer.currentLiveWrPct() } catch (_: Throwable) { 0.0 }
        val n = try { LiveLayerGateRelaxer.currentLiveTerminalCount() } catch (_: Throwable) { 0 }
        // V5.0.6101 — trade-1/SSI doctrine: do not wait for 40 live closes
        // before engaging bounded fanout pressure when bootstrap live WR is already
        // bleeding and laneEval/intake is extreme. This is not a lane amputation;
        // BotService consumes active=true to narrow broad rescue fanout to explicit
        // affinity/owner lanes while keeping trunk + primary + proven rescue alive.
        val maturePressure6101 = intake >= 20L && ratio > 8.0 && n >= 40 && wr < 30.0
        val bootstrapSeverePressure6101 = intake >= 20L && ratio > 18.0 && n >= 10 && wr < 25.0
        val active = maturePressure6101 || bootstrapSeverePressure6101
        val reason6101 = when {
            maturePressure6101 -> "lane_fanout_pressure_low_wr"
            bootstrapSeverePressure6101 -> "bootstrap_severe_fanout_pressure_low_wr"
            else -> "normal"
        }
        cached = Snapshot(active, ratio, wr, n, reason6101)
        cachedAtMs = nowMs
        return cached
    }

    fun isActive(): Boolean = snapshot().active
}
