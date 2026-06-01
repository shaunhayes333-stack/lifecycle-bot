package com.lifecyclebot.engine

/**
 * V5.9.1273 — LaneExpectancyDamper
 *
 * DOCTRINE-CLEAN bleeder control. Reads the LIVE per-lane expectancy from
 * StrategyTelemetry (the same data shown in the pipeline "Strategy expectancy"
 * block) and returns a SIZE multiplier only — it NEVER vetoes a candidate.
 * Per operator doctrine #86 ("help don't hinder") and the PERFORMANCE_DOCTRINE
 * soft-shape rule, only the original veto whitelist may kill a candidate; this
 * organ may only shrink size on a PROVEN, statistically-meaningful bleeder.
 *
 * Why this exists:
 *   The 3240 snapshot showed two pure capital incinerators that survive purely
 *   because nothing sizes them down:
 *     TREASURY     n=32  WR 6.3%  meanPnl -22.3%  PnL -2.39 SOL
 *     MANIPULATED  n=21  WR 0.0%  meanPnl -12.9%  PnL -0.05 SOL
 *   Meanwhile their +EV sub-contexts (e.g. MANIPULATED|DUMP) still print — so we
 *   shrink the LANE's average exposure rather than blocking it, letting the
 *   learning loop (fixed in 1272) keep sampling and the good sub-slices survive.
 *
 * SELF-HEALING: the multiplier is recomputed from live telemetry every call. The
 * moment a lane's mean expectancy climbs back above the floor (because 1272's
 * clean labels let the scorer actually learn), the haircut releases automatically.
 * No persisted state, no manual re-enable.
 */
object LaneExpectancyDamper {

    // Only act on lanes with enough closed trades to be real signal, not noise.
    private const val MIN_TRADES = 20

    // A lane is a "bleeder" once its mean PnL% is this negative (below the -15%
    // hard-floor magnitude is deep red; -12% is a confirmed structural loss).
    private const val BLEEDER_MEAN_PCT = -12.0

    // Damper never sizes below this (keep a probe alive so the lane can recover
    // and the learning loop keeps getting samples — throughput-before-cleverness).
    private const val MIN_MULT = 0.50

    // Worst-case mean PnL% that maps to MIN_MULT. Between BLEEDER_MEAN_PCT and
    // this, the haircut scales linearly.
    private const val FLOOR_MEAN_PCT = -30.0

    // Cheap cache so we don't recompute the leaderboard on every single entry in
    // a hot scan burst. Refresh window keeps it live without thrashing.
    private const val CACHE_MS = 5_000L
    @Volatile private var cacheAtMs = 0L
    @Volatile private var cached: Map<String, Double> = emptyMap()

    /**
     * Returns a size multiplier in [MIN_MULT .. 1.0] for the given lane.
     * 1.0 = no change (lane is fine, unknown, or too few samples).
     * Fail-open: any error → 1.0.
     */
    fun sizeMultiplier(lane: String?): Double {
        if (lane.isNullOrBlank()) return 1.0
        return try {
            val key = lane.trim().uppercase()
            val map = snapshot()
            map[key] ?: 1.0
        } catch (_: Throwable) {
            1.0
        }
    }

    /** Human-readable line for the pipeline dump (operator visibility). */
    fun statusLine(): String = try {
        val map = snapshot()
        if (map.isEmpty()) "LaneExpectancyDamper: no bleeders (all lanes ≥ ${BLEEDER_MEAN_PCT}% or < $MIN_TRADES trades)"
        else "LaneExpectancyDamper: " + map.entries.sortedBy { it.value }
            .joinToString(" · ") { "${it.key}×${"%.2f".format(it.value)}" }
    } catch (_: Throwable) {
        "LaneExpectancyDamper: unavailable"
    }

    private fun snapshot(): Map<String, Double> {
        val now = System.currentTimeMillis()
        val c = cached
        if (now - cacheAtMs < CACHE_MS && c.isNotEmpty()) return c
        val fresh = compute()
        cached = fresh
        cacheAtMs = now
        return fresh
    }

    private fun compute(): Map<String, Double> {
        val board = try {
            StrategyTelemetry.computeLeaderboard()
        } catch (_: Throwable) {
            return emptyMap()
        }
        val out = HashMap<String, Double>()
        for (m in board) {
            if (m.trades < MIN_TRADES) continue
            if (m.meanPnlPct >= BLEEDER_MEAN_PCT) continue
            // Linear haircut: BLEEDER_MEAN_PCT → 1.0, FLOOR_MEAN_PCT → MIN_MULT.
            val span = (BLEEDER_MEAN_PCT - FLOOR_MEAN_PCT).coerceAtLeast(1.0)
            val depth = (BLEEDER_MEAN_PCT - m.meanPnlPct).coerceIn(0.0, span)
            val frac = depth / span                      // 0..1
            val mult = (1.0 - frac * (1.0 - MIN_MULT)).coerceIn(MIN_MULT, 1.0)
            out[m.strategy.trim().uppercase()] = mult
        }
        return out
    }
}
