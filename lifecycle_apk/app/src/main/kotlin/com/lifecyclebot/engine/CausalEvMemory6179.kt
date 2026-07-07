package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6179 — causal EV memory graph.
 *
 * Builds cached EV edges by lane+tactic+source+venue+route from clean terminal
 * journal rows. Updates happen at journal-record time; sizing reads are cached
 * only, so scanner/FDG/executor hot paths do not query SQLite or external APIs.
 */
object CausalEvMemory6179 {
    data class Edge(
        var wins: Long = 0L,
        var losses: Long = 0L,
        var pnlSol: Double = 0.0,
        var pnlPct: Double = 0.0,
        var lastUpdatedMs: Long = 0L,
    ) {
        val samples: Long get() = wins + losses
        val winRate: Double get() = if (samples > 0L) wins.toDouble() / samples else 0.5
        val avgPnlSol: Double get() = if (samples > 0L) pnlSol / samples else 0.0
        val avgPnlPct: Double get() = if (samples > 0L) pnlPct / samples else 0.0
        val profitFactorProxy: Double get() = ((wins + 1.0) / (losses + 1.0)) * (1.0 + avgPnlSol.coerceIn(-1.0, 1.0))
    }

    private val edges = ConcurrentHashMap<String, Edge>()

    fun recordTerminalOutcome(t: Trade) {
        try {
            val side = t.side.trim().uppercase()
            if (side != "SELL" && side != "PARTIAL_SELL") return
            if (side == "PARTIAL_SELL" && t.remainingQtyToken > 0.000000001) return
            if (!StrategyTruthLedger.hasValidEntryBasis(t) || StrategyTruthLedger.isRecoveryInventory(t)) return
            val key = causalKey(t)
            val e = edges.getOrPut(key) { Edge() }
            synchronized(e) {
                if (t.pnlSol > 0.0) e.wins++ else e.losses++
                e.pnlSol += t.pnlSol.takeIf { it.isFinite() } ?: 0.0
                e.pnlPct += t.pnlPct.takeIf { it.isFinite() } ?: 0.0
                e.lastUpdatedMs = System.currentTimeMillis()
            }
            try { PipelineHealthCollector.labelInc("CAUSAL_EV_MEMORY_RECORDED_6179") } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    fun sizeMultiplier(lane: String, source: String?, routeHint: String?, tacticHint: String?): Double {
        val key = causalKey(lane, source, routeHint, tacticHint)
        val exact = edges[key]
        val lanePrefix = key.substringBefore('|') + "|"
        val fallback = exact ?: edges.entries.asSequence()
            .filter { it.key.startsWith(lanePrefix) }
            .map { it.value }
            .maxByOrNull { it.samples }
        val e = fallback ?: return 1.0
        if (e.samples < 8L) return 1.0
        val ev = e.avgPnlSol
        val wr = e.winRate
        val raw = when {
            ev > 0.04 && wr >= 0.35 -> 1.18
            ev > 0.015 -> 1.10
            ev > 0.0 -> 1.04
            ev < -0.04 && e.samples >= 15 -> 0.72
            ev < -0.015 -> 0.84
            else -> 1.0
        }
        return raw.coerceIn(0.70, 1.22)
    }

    fun statusLine(limit: Int = 6): String = edges.entries.asSequence()
        .sortedByDescending { it.value.samples }
        .take(limit)
        .joinToString(" | ") { (k, e) -> "$k n=${e.samples} wr=${"%.0f".format(e.winRate * 100)} ev=${"%.4f".format(e.avgPnlSol)}" }
        .ifBlank { "no causal edges" }

    fun causalKey(t: Trade): String {
        val lane = StrategyTruthLedger.strategyLaneFor(t)
        val source = t.entryPriceSource.ifBlank { Regex("sourceFamily=([^ ]+)").find(t.reason)?.groupValues?.getOrNull(1) ?: "UNKNOWN_SOURCE" }
        val venue = Regex("venueFamily=([^ ]+)").find(t.reason)?.groupValues?.getOrNull(1)
            ?: t.entryPoolAddress.ifBlank { "UNKNOWN_VENUE" }
        val route = Regex("routeTruth=([^ ]+)").find(t.reason)?.groupValues?.getOrNull(1)
            ?: t.entryPoolAddress.ifBlank { "UNKNOWN_ROUTE" }
        val tactic = Regex("strategyTruth=([^ ]+)").find(t.reason)?.groupValues?.getOrNull(1)
            ?: StrategyTruthLedger.strategyTruthKey6149(t).substringAfterLast('|')
        return causalKey(lane, source, "$venue|$route", tactic)
    }

    fun causalKey(lane: String, source: String?, routeHint: String?, tacticHint: String?): String {
        fun norm(s: String?) = (s ?: "UNKNOWN").uppercase().replace('|', '_').replace(' ', '_').take(72)
        val venueRoute = norm(routeHint)
        return "${norm(lane)}|${norm(source)}|$venueRoute|${norm(tacticHint)}"
    }
}
