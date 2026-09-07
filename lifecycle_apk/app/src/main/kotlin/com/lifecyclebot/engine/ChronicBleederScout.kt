package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LlmLabEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * ChronicBleederScout — V5.0.6265
 *
 * Finds chronic bleeder lanes and seeds the LLM Lab with an autopivot strategy.
 *
 * V5.0.6679 — MODE-MATCHED REPROVE AUTHORITY.
 * The original scout was permanently wired to clean LIVE terminal truth even
 * though BotService calls it in PAPER as well. In a paper learning session that
 * made the scout silently return on an empty live board, so catastrophic PAPER
 * lanes never entered the LLM re-prove loop. PAPER now reads clean PAPER truth;
 * LIVE reads clean LIVE truth. Scout dedupe is keyed by environment + lane so a
 * paper seed can never suppress a later live re-prove.
 */
object ChronicBleederScout {

    private const val MIN_TRADES = 15
    private const val MAX_WR = 0.20
    private const val MAX_AVG_PNL = -10.0

    private val lastScoutedAt = ConcurrentHashMap<String, Long>()
    private const val SCOUT_TTL_MS = 30L * 60_000L

    fun tick() {
        try {
            val paper6679 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
            val env6679 = if (paper6679) "PAPER" else "LIVE"
            val laneStats = try {
                if (paper6679) StrategyTelemetry.computeCleanPaperTerminalLeaderboard(limit = 1_500)
                else StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            } catch (_: Throwable) { emptyList() }
            if (laneStats.isEmpty()) return
            val now = System.currentTimeMillis()

            laneStats.forEach { s ->
                val laneU = s.strategy.uppercase()
                if (s.trades < MIN_TRADES) return@forEach
                val wr = s.winRatePct / 100.0
                if (wr > MAX_WR) return@forEach
                if (s.meanPnlPct > MAX_AVG_PNL) return@forEach

                val scoutKey6679 = "$env6679|$laneU"
                val last = lastScoutedAt[scoutKey6679] ?: 0L
                if (now - last < SCOUT_TTL_MS) return@forEach
                lastScoutedAt[scoutKey6679] = now

                val nextTactic = pickReproveTactic(laneU, wr, s.meanPnlPct)
                val scoreBand = pickScoreBand(laneU)
                val reason = "chronic_bleeder_reprove_6679 env=$env6679 n=${s.trades} wr=${"%.0f".format(wr * 100)}% avgPnl=${"%.1f".format(s.meanPnlPct)}%"

                try {
                    LlmLabEngine.seedFromTacticFailure(
                        lane = laneU,
                        scoreBand = scoreBand,
                        failedTactic = "current_lane_tactic",
                        nextTactic = nextTactic,
                        reason = reason,
                    )
                    ForensicLogger.lifecycle(
                        "CHRONIC_BLEEDER_LAB_REPROVE_6679",
                        "env=$env6679 lane=$laneU n=${s.trades} wr=${"%.0f".format(wr * 100)}% avgPnl=${"%.1f".format(s.meanPnlPct)}% nextTactic=$nextTactic",
                    )
                    PipelineHealthCollector.labelInc("CHRONIC_BLEEDER_LAB_REPROVE_6679|mode=$env6679|lane=$laneU")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun pickReproveTactic(lane: String, wr: Double, avgPnl: Double): String {
        return when {
            lane.contains("EXPRESS") -> "BREAKOUT"
            lane.contains("TREASURY") -> "REACCUM"
            lane.contains("MOON") || lane.contains("SHIT") -> "PULLBACK"
            lane.contains("QUALITY") -> "LAB"
            wr < 0.10 -> "BREAKOUT"
            avgPnl < -30.0 -> "REACCUM"
            else -> "PULLBACK"
        }
    }

    private fun pickScoreBand(lane: String): String = when {
        lane.contains("EXPRESS") -> "S0-10"
        lane.contains("TREASURY") -> "S0-10"
        lane.contains("MOON") -> "S26-40"
        lane.contains("QUALITY") -> "S41-60"
        else -> "S26-40"
    }

    fun statusLine(): String =
        "V5.0.6679_CHRONIC_BLEEDER_SCOUT: mode=${try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }} cohortsScoutedThisSession=${lastScoutedAt.size}"
}
