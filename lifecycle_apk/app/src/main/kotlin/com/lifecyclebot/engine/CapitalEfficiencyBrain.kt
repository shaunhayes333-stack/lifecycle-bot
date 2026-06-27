package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** V5.0.4281 — pnlSol per SOL-minute learner. Bounded sizing preference only; runner-safe. */
object CapitalEfficiencyBrain {
    data class LaneStat(var trades: Int = 0, var wins: Int = 0, var score: Double = 0.0)
    private const val MAX_LANES = 64
    private val stats = ConcurrentHashMap<String, LaneStat>()

    fun recordTerminalTrade(trade: Trade, lane: String, source: String) {
        if (!trade.side.equals("SELL", ignoreCase = true)) return
        val sol = trade.sol.takeIf { it.isFinite() && it > 0.0 } ?: return
        val holdMin = ((trade.ts - trade.entryTsMs).coerceAtLeast(60_000L)).toDouble() / 60_000.0
        val pnl = (trade.netPnlSol.takeIf { it.isFinite() && it != 0.0 } ?: trade.pnlSol).takeIf { it.isFinite() } ?: return
        val eff = (pnl / (sol * holdMin.coerceAtLeast(1.0))).coerceIn(-0.08, 0.08)
        val runner = trade.pnlPct >= 50.0 || trade.reason.contains("RUNNER", ignoreCase = true) || trade.reason.contains("MOON", ignoreCase = true)
        val key = key(lane, source)
        val s = stats.getOrPut(key) { LaneStat() }
        synchronized(s) {
            s.trades = (s.trades + 1).coerceAtMost(10_000)
            if (pnl > 0.0) s.wins = (s.wins + 1).coerceAtMost(10_000)
            val alpha = if (runner) 0.08 else 0.14
            s.score = (s.score * (1.0 - alpha) + eff * alpha).coerceIn(-0.08, 0.08)
        }
        if (stats.size > MAX_LANES) stats.keys.take(stats.size - MAX_LANES).forEach { stats.remove(it) }
        try { PipelineHealthCollector.labelInc("CAPITAL_EFFICIENCY_LEARNED_4281") } catch (_: Throwable) {}
    }

    fun sizeMultiplier(lane: String, source: String, isRunnerCandidate: Boolean = false): Double {
        val s = stats[key(lane, source)] ?: stats[key(lane, "*")] ?: return 1.0
        if (s.trades < 5) return 1.0
        val raw = when {
            s.score >= 0.010 -> 1.08
            s.score >= 0.004 -> 1.04
            s.score <= -0.010 -> if (isRunnerCandidate) 0.98 else 0.92
            s.score <= -0.004 -> if (isRunnerCandidate) 1.0 else 0.96
            else -> 1.0
        }
        return raw.coerceIn(0.92, 1.08)
    }

    fun exportState(): String = JSONArray().also { a ->
        stats.entries.take(MAX_LANES).forEach { (k, v) -> synchronized(v) { a.put(JSONObject().put("k", k).put("t", v.trades).put("w", v.wins).put("s", v.score)) } }
    }.toString()

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val a = JSONArray(raw); stats.clear()
            for (i in 0 until a.length().coerceAtMost(MAX_LANES)) {
                val o = a.optJSONObject(i) ?: continue
                val k = o.optString("k")
                if (k.isNotBlank()) stats[k] = LaneStat(o.optInt("t"), o.optInt("w"), o.optDouble("s", 0.0).coerceIn(-0.08, 0.08))
            }
        } catch (_: Throwable) {}
    }
    fun reset() { stats.clear() }
    private fun key(lane: String, source: String) = "${lane.uppercase().take(32)}|${source.uppercase().ifBlank { "*" }.take(48)}"
}
