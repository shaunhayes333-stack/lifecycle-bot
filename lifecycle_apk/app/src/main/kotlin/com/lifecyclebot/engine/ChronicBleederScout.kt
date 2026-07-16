package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LlmLabEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * ChronicBleederScout — V5.0.6265
 * ════════════════════════════════════════════════════════════════════════════
 * Op-report V5.0.6263 showed persistent bleeders that never recovered:
 *   EXPRESS   n=16 WR= 0.0% EV=-66.67%/trade  ← never won ANYTHING
 *   TREASURY  n=48 WR=12.5% EV=-13.88%/trade
 *   QUALITY   n=178 WR=29.2% (below 30% floor)
 *   BLUECHIP toxic_reclaim_tactic_pivot n=9 WR=13% PnL=-0.009
 *
 * These lanes were locked in a bleed cycle: LiveStrategyTuner damped their
 * size but they kept trading small losses. Nothing was actively rebuilding
 * a WINNING strategy for the lane.
 *
 * This scout finds chronic bleeder lanes (>=MIN_TRADES trades AND
 * WR<=MAX_WR AND avgPnl<=MAX_AVG_PNL) and seeds the LLM Lab with an
 * autopivot strategy for that lane. LabPromotedFeed already auto-
 * reimplements strategies that prove out in the lab (forwardWinRate >=
 * 60%, sufficient sample size), so this closes the loop:
 *
 *   CHRONIC BLEEDER → LLM LAB REPROVE → AUTO-REIMPLEMENT WHEN PROVEN
 *
 * Idempotent — LlmLabEngine.seedFromTacticFailure has its own 20-min
 * per-lane dedupe.
 */
object ChronicBleederScout {

    // Chronic-bleeder criteria.
    private const val MIN_TRADES = 15
    private const val MAX_WR = 0.20            // <= 20% win rate
    private const val MAX_AVG_PNL = -10.0      // <= -10% average pnl

    private val lastScoutedAt = ConcurrentHashMap<String, Long>()
    private const val SCOUT_TTL_MS = 30L * 60_000L   // one seed per lane per 30 min

    /**
     * Called periodically from BotService main loop.
     * Reads per-lane live stats from StrategyTruthLedger; for each chronic
     * bleeder lane emits an LlmLab autopivot seed with a mutated tactic.
     */
    fun tick() {
        try {
            val laneStats = try {
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            } catch (_: Throwable) { emptyList() }
            if (laneStats.isEmpty()) return
            val now = System.currentTimeMillis()

            laneStats.forEach { s ->
                val laneU = s.strategy.uppercase()
                if (s.trades < MIN_TRADES) return@forEach
                val wr = s.winRatePct / 100.0
                if (wr > MAX_WR) return@forEach
                if (s.meanPnlPct > MAX_AVG_PNL) return@forEach

                val last = lastScoutedAt[laneU] ?: 0L
                if (now - last < SCOUT_TTL_MS) return@forEach
                lastScoutedAt[laneU] = now

                val nextTactic = pickReproveTactic(laneU, wr, s.meanPnlPct)
                val scoreBand = pickScoreBand(laneU)
                val reason = "chronic_bleeder_reprove_6265 n=${s.trades} wr=${"%.0f".format(wr * 100)}% avgPnl=${"%.1f".format(s.meanPnlPct)}%"

                try {
                    LlmLabEngine.seedFromTacticFailure(
                        lane = laneU,
                        scoreBand = scoreBand,
                        failedTactic = "current_lane_tactic",
                        nextTactic = nextTactic,
                        reason = reason,
                    )
                    ForensicLogger.lifecycle(
                        "CHRONIC_BLEEDER_LAB_REPROVE_6265",
                        "lane=$laneU n=${s.trades} wr=${"%.0f".format(wr * 100)}% avgPnl=${"%.1f".format(s.meanPnlPct)}% nextTactic=$nextTactic",
                    )
                    PipelineHealthCollector.labelInc("CHRONIC_BLEEDER_LAB_REPROVE_6265")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Pick a reprove tactic that ROTATES between lab strategy families so
     *  the lab explores different edges on the same failing lane. */
    private fun pickReproveTactic(lane: String, wr: Double, avgPnl: Double): String {
        return when {
            lane.contains("EXPRESS") -> "BREAKOUT"      // 0% WR — needs momentum play
            lane.contains("TREASURY") -> "REACCUM"      // slow bleed — needs patience play
            lane.contains("MOON") || lane.contains("SHIT") -> "PULLBACK"  // volatility — needs entry timing
            lane.contains("QUALITY") -> "LAB"           // meta — needs full lab exploration
            wr < 0.10 -> "BREAKOUT"                     // catastrophic — swing hard
            avgPnl < -30.0 -> "REACCUM"                 // deep bleed — reset entry
            else -> "PULLBACK"
        }
    }

    private fun pickScoreBand(lane: String): String {
        // Reprove focus band — bleeders typically live in mid-score range.
        return when {
            lane.contains("EXPRESS") -> "S0-10"
            lane.contains("TREASURY") -> "S0-10"
            lane.contains("MOON") -> "S26-40"
            lane.contains("QUALITY") -> "S41-60"
            else -> "S26-40"
        }
    }

    fun statusLine(): String = "V5.0.6265_CHRONIC_BLEEDER_SCOUT: lanesScoutedThisSession=${lastScoutedAt.size}"
}
