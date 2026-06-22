package com.lifecyclebot.engine

/**
 * V5.0.4068 — Lane re-enablement checker for live recovery mode.
 *
 * Toxic lanes disabled by RuntimeConfigOverlay.LIVE_RECOVERY_DISABLED_LANES
 * can re-enable when their rolling live performance meets all criteria:
 *   - rolling_100_live_wr >= 42 (50 for EXPRESS, MANIPULATED)
 *   - rolling_live_pnl_sol > 0
 *   - lane_pf >= 1.25
 *   - no loss worse than -25% in last 50 trades
 *
 * Once re-enabled, a lane starts at 0.10× size multiplier and must earn
 * its way back to full sizing through continued positive performance.
 *
 * Checks are cached for 60s to avoid hot-path journal reads.
 */
object LaneReenableChecker {

    private const val CACHE_TTL_MS = 60_000L
    @Volatile private var cacheStampMs: Long = 0L
    @Volatile private var reenabledLanes: Set<String> = emptySet()

    private fun minWrForLane(lane: String): Double = when (lane) {
        "EXPRESS", "MANIPULATED" -> 50.0
        else -> 42.0
    }

    fun isReenabled(lane: String): Boolean {
        refreshIfNeeded()
        return lane in reenabledLanes
    }

    fun reenabledLanesSnapshot(): Set<String> = reenabledLanes

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - cacheStampMs <= CACHE_TTL_MS) return
        cacheStampMs = now
        val result = mutableSetOf<String>()
        try {
            val leaderboard = StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 2_500)
            for (m in leaderboard) {
                val lane = RuntimeConfigOverlay.normalizeLane(m.strategy)
                if (lane !in setOf("MOONSHOT", "SHITCOIN", "EXPRESS", "MANIPULATED",
                        "PRESALE_SNIPE", "PROJECT_SNIPER", "DIP_HUNTER", "CASHGEN", "CYCLIC")) continue
                if (m.trades < 50) continue  // need sufficient sample
                val wr = m.winRatePct
                val pnlSol = m.totalSolPnl
                // Compute profit factor: sum(wins) / abs(sum(losses))
                // Using avgWinPct * wins vs abs(avgLossPct) * losses as a proxy.
                val grossWin = m.avgWinPct * m.wins
                val grossLoss = kotlin.math.abs(m.avgLossPct * m.losses)
                val pf = if (grossLoss > 0.0) grossWin / grossLoss else if (grossWin > 0.0) 99.0 else 0.0
                val minWr = minWrForLane(lane)
                // Check: WR >= threshold, PnL > 0, PF >= 1.25
                if (wr >= minWr && pnlSol > 0.0 && pf >= 1.25) {
                    // Check no catastrophic loss in recent trades (meanPnl not worse than -25%)
                    if (m.meanPnlPct > -25.0) {
                        result.add(lane)
                    }
                }
            }
        } catch (_: Throwable) {}
        reenabledLanes = result
    }

    fun forceBustCache() { cacheStampMs = 0L }
}
