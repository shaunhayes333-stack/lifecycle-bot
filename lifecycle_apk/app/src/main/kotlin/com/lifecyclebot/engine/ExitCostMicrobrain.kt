package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** V5.0.4275 — terminal SELL exit-cost learner; cached hints only, never blocks exits. */
object ExitCostMicrobrain {
    private const val MAX_SAMPLES_PER_KEY = 250.0
    private const val MIN_HINT_SAMPLES = 12.0

    data class ExitCostHint(
        val urgencyMult: Double = 1.0,
        val confidence: Double = 0.0,
        val samples: Double = 0.0,
        val avgCostDragPct: Double = 0.0,
    )

    private data class Stats(
        var samples: Double = 0.0,
        var totalCostDragPct: Double = 0.0,
        var totalDivergencePct: Double = 0.0,
        var totalFeePct: Double = 0.0,
        var wins: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    fun recordTerminalExit(trade: Trade, liquidityUsd: Double, lane: String, source: String) {
        try {
            if (!trade.side.equals("SELL", true)) return
            val key = keyFor(lane, liquidityUsd, trade.reason)
            val basis = trade.entryCostSol.takeIf { it.isFinite() && it > 0.0 } ?: trade.sol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val feePct = if (basis > 0.0 && trade.feeSol.isFinite()) (trade.feeSol / basis * 100.0).coerceIn(0.0, 50.0) else 0.0
            val divergencePct = abs(trade.quoteDivergencePct.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 250.0)
            val costDragPct = (feePct + divergencePct).coerceIn(0.0, 300.0)
            val s = stats.getOrPut(key) { Stats() }
            synchronized(s) {
                val decay = if (s.samples >= MAX_SAMPLES_PER_KEY) 0.96 else 1.0
                s.samples = (s.samples * decay + 1.0).coerceAtMost(MAX_SAMPLES_PER_KEY)
                s.totalCostDragPct = s.totalCostDragPct * decay + costDragPct
                s.totalDivergencePct = s.totalDivergencePct * decay + divergencePct
                s.totalFeePct = s.totalFeePct * decay + feePct
                s.wins = s.wins * decay + if (trade.pnlPct > 0.0) 1.0 else 0.0
            }
            if (costDragPct >= 8.0) {
                try {
                    PipelineHealthCollector.labelInc("EXIT_COST_MICROBRAIN_DRAG_4275")
                    ForensicLogger.lifecycle("EXIT_COST_MICROBRAIN_DRAG_4275", "lane=$lane source=$source liqBand=${liquidityBand(liquidityUsd)} reason=${trade.reason} costDragPct=${costDragPct.fmt(2)} feePct=${feePct.fmt(2)} divergencePct=${divergencePct.fmt(2)} action=learn_only_no_exit_block")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun exitUrgencyHint(lane: String, liquidityUsd: Double, reason: String): ExitCostHint {
        return try {
            val s = stats[keyFor(lane, liquidityUsd, reason)] ?: return ExitCostHint()
            synchronized(s) {
                if (s.samples < MIN_HINT_SAMPLES) return ExitCostHint(samples = s.samples)
                val avgDrag = (s.totalCostDragPct / s.samples).coerceIn(0.0, 100.0)
                val urgency = when {
                    avgDrag >= 15.0 -> 1.08
                    avgDrag >= 8.0 -> 1.04
                    avgDrag <= 1.5 -> 0.98
                    else -> 1.0
                }
                ExitCostHint(
                    urgencyMult = urgency.coerceIn(0.92, 1.08),
                    confidence = (s.samples / 80.0).coerceIn(0.0, 1.0),
                    samples = s.samples,
                    avgCostDragPct = avgDrag,
                )
            }
        } catch (_: Throwable) { ExitCostHint() }
    }

    fun exportState(): String = try {
        val root = JSONObject()
        stats.forEach { (k, s) ->
            synchronized(s) {
                root.put(k, JSONObject().apply {
                    put("samples", s.samples)
                    put("totalCostDragPct", s.totalCostDragPct)
                    put("totalDivergencePct", s.totalDivergencePct)
                    put("totalFeePct", s.totalFeePct)
                    put("wins", s.wins)
                })
            }
        }
        root.toString()
    } catch (_: Throwable) { JSONObject().toString() }

    fun importState(json: String) {
        try {
            stats.clear()
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                stats[k] = Stats(
                    samples = o.optDouble("samples", 0.0).coerceIn(0.0, MAX_SAMPLES_PER_KEY),
                    totalCostDragPct = o.optDouble("totalCostDragPct", 0.0),
                    totalDivergencePct = o.optDouble("totalDivergencePct", 0.0),
                    totalFeePct = o.optDouble("totalFeePct", 0.0),
                    wins = o.optDouble("wins", 0.0),
                )
            }
        } catch (_: Throwable) {}
    }

    fun reset() { stats.clear() }

    private fun keyFor(lane: String, liquidityUsd: Double, reason: String): String {
        val reasonFamily = when {
            reason.contains("profit", true) || reason.contains("runner", true) || reason.contains("trail", true) -> "PROFIT"
            reason.contains("stop", true) || reason.contains("rug", true) || reason.contains("risk", true) -> "RISK"
            reason.contains("time", true) || reason.contains("stale", true) -> "TIME"
            else -> "OTHER"
        }
        val laneKey = lane.uppercase().ifBlank { "STANDARD" }
        return laneKey + "|" + liquidityBand(liquidityUsd) + "|" + reasonFamily
    }

    private fun liquidityBand(liquidityUsd: Double): String = when {
        liquidityUsd < 5_000.0 -> "MICRO"
        liquidityUsd < 20_000.0 -> "THIN"
        liquidityUsd < 75_000.0 -> "MID"
        else -> "DEEP"
    }

    private fun Double.fmt(digits: Int): String = try { java.lang.String.format(java.util.Locale.US, "% ." + digits + "f", this).replace(" ", "") } catch (_: Throwable) { toString() }
}
