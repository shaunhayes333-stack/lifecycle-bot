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
        // V5.0.6284 — NET-PNL ESCAPE. If live WR is low but aggregate SOL
        // PnL is net-positive across live terminal closes, the "toxic
        // fanout" signal is a false alarm — the memecoin distribution
        // is doing its job with rare big winners. Only pressure the
        // fanout when BOTH WR is bad AND aggregate SOL PnL is negative.
        val liveNetSol = try {
            StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
                .sumOf { it.totalSolPnl }
        } catch (_: Throwable) { 0.0 }
        val active = intake >= 20L && ratio > 8.0 && n >= 40 && wr < 30.0 && exec > 0L && liveNetSol < 0.0
        cached = Snapshot(active, ratio, wr, n, if (active) "lane_fanout_pressure_low_wr" else "normal")
        cachedAtMs = nowMs
        return cached
    }

    fun isActive(): Boolean = snapshot().active
}
