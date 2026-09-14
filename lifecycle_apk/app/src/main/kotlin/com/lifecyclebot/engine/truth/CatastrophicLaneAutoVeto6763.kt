package com.lifecyclebot.engine.truth

/**
 * V5.0.6763 §CATASTROPHIC_LANE_AUTO_VETO.
 *
 * Operator directive V5.0.6761 diagnostic:
 *   "Yet it still bought 88 positions in 235 seconds, while the principal
 *    operating lanes were showing 0–7.7% WR. That means the learning
 *    system is mostly shaping size, not shutting off demonstrably toxic
 *    entry distributions. And the snapshot explicitly admits this design:
 *    'No strategy is auto-disabled — operator decides what to retire.'
 *    That's a direct contradiction to the intended behaviour: self-adjust
 *    until the bad behaviour stops."
 *
 * `LaneExpectancyDamper` is deliberately size-only by operator doctrine #86
 * ("help don't hinder"). That doctrine assumed the damper alone would
 * suffice — a heavily-damped lane would still print in +EV sub-slices.
 * The V5.0.6761 evidence disproves that: PROJECT_SNIPER, CORE and EXPRESS
 * were sized down 0.33-0.47× yet still fed 88 entries into a DUMP regime
 * with 4.3% WR. The damper is not enough — after truly catastrophic
 * evidence, a HARD VETO is required.
 *
 * This authority is deliberately conservative — it only triggers on the
 * same statistical wall as `LaneExpectancyDamper.CATASTROPHIC_*`:
 *   • ≥ 20 clean same-mode terminal closes
 *   • WR ≤ 8%
 *   • mean pnl ≤ -20%
 * and self-heals as soon as the sliding-window numbers recover past a
 * safety margin (WR ≥ 15% on the most recent 10 closes).
 *
 * Emitted as a hard-safety veto reason so it stops entries even AFTER
 * FDG_ALLOW / EXEC_INTENT_SEALED. Listed in the PostSealAuthorityInvariants
 * 6760 SAFETY allowlist via the `SAFETY_HARD_VETO_LANE_CATASTROPHIC_6763`
 * prefix.
 */
object CatastrophicLaneAutoVeto6763 {

    private const val MIN_TRADES = 20
    private const val MAX_WR_PCT = 8.0
    private const val MAX_MEAN_PNL_PCT = -20.0

    // Self-heal: recent-10 WR must exceed this to release the veto.
    private const val RECOVERY_MIN_TRADES = 10
    private const val RECOVERY_MIN_WR_PCT = 15.0

    private const val CACHE_MS = 5_000L
    @Volatile private var cacheAtMs = 0L
    @Volatile private var cachedVetoedLanes: Set<String> = emptySet()

    data class VetoDecision(
        val vetoed: Boolean,
        val reason: String,
        val lane: String,
        val wrPct: Double,
        val meanPnlPct: Double,
        val trades: Int,
    )

    /** Immediate veto check for an admission at [lane] in [mode]. */
    fun evaluate(mode: String, lane: String?): VetoDecision {
        if (lane.isNullOrBlank()) {
            return VetoDecision(false, "OK", "", 0.0, 0.0, 0)
        }
        val key = lane.trim().uppercase()
        val vetoed = key in vetoedLaneSet()
        return if (vetoed) {
            VetoDecision(
                vetoed = true,
                reason = "SAFETY_HARD_VETO_LANE_CATASTROPHIC_6763",
                lane = key,
                wrPct = 0.0, meanPnlPct = 0.0, trades = 0,
            )
        } else {
            VetoDecision(false, "OK", key, 0.0, 0.0, 0)
        }
    }

    fun vetoedLaneSet(): Set<String> {
        val now = System.currentTimeMillis()
        val hit = cachedVetoedLanes
        if (now - cacheAtMs < CACHE_MS && hit.isNotEmpty()) return hit
        val fresh = try { computeVetoedLanes() } catch (_: Throwable) { emptySet() }
        cachedVetoedLanes = fresh
        cacheAtMs = now
        if (fresh.isNotEmpty()) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    "CATASTROPHIC_LANE_AUTO_VETO_ACTIVE_6763",
                )
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CATASTROPHIC_LANE_AUTO_VETO_ACTIVE_6763",
                    "lanes=${fresh.joinToString(",")} count=${fresh.size}",
                )
            } catch (_: Throwable) {}
        }
        return fresh
    }

    private fun computeVetoedLanes(): Set<String> {
        val mode = try {
            if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "paper" else "live"
        } catch (_: Throwable) { "paper" }
        val board = try {
            if (mode == "live") com.lifecyclebot.engine.StrategyTelemetry.computeCleanLiveTerminalLeaderboard()
            else com.lifecyclebot.engine.StrategyTelemetry.computeCleanPaperTerminalLeaderboard()
        } catch (_: Throwable) { emptyList() }
        val vetoed = mutableSetOf<String>()
        for (m in board) {
            if (m.trades < MIN_TRADES) continue
            if (m.winRatePct > MAX_WR_PCT) continue
            if (m.meanPnlPct > MAX_MEAN_PNL_PCT) continue
            // Self-heal probe: check most-recent trades for any recovery.
            val recovered = try { hasRecoverySignal(mode, m.strategy) } catch (_: Throwable) { false }
            if (recovered) continue
            vetoed += m.strategy.trim().uppercase()
        }
        return vetoed
    }

    private fun hasRecoverySignal(mode: String, strategy: String): Boolean {
        // Very small self-heal probe: look at the last RECOVERY_MIN_TRADES
        // closes matching the strategy; require WR >= RECOVERY_MIN_WR_PCT.
        // Cheap enough to re-evaluate every CACHE_MS; falls open on error.
        val recent = try {
            com.lifecyclebot.engine.TradeHistoryStore
                .getRecentValidClosedTradesRaw(limit = 400, includePartials = true)
                .asSequence()
                .filter { it.mode.equals(mode, true) && it.side.equals("SELL", true) }
                .filter {
                    val norm = try { com.lifecyclebot.engine.TradeHistoryStore.normalizeTradeModeName(it.tradingMode) } catch (_: Throwable) { it.tradingMode }
                    norm.equals(strategy, true)
                }
                .take(RECOVERY_MIN_TRADES)
                .toList()
        } catch (_: Throwable) { emptyList() }
        if (recent.size < RECOVERY_MIN_TRADES) return false
        val wins = recent.count { (it.netPnlSol.takeIf { s -> s != 0.0 } ?: it.pnlSol) > 0.0 }
        val wr = wins * 100.0 / recent.size
        return wr >= RECOVERY_MIN_WR_PCT
    }
}
