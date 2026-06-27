package com.lifecyclebot.engine

import com.lifecyclebot.engine.execution.MemeExecutionRouteStack
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4321 — Ultimate Edge Engine, first cache-first coordinator.
 *
 * Purpose: unify already-built edge systems into one lane card without adding
 * scanner/FDG/executor API calls. This is a bot-native AI helper/coroutine path:
 * callers enqueue refreshes, ChokeReliefBus performs bounded side-effect work,
 * and hot paths may read the latest cached card fail-open.
 *
 * Inputs today: SemanticPatternGraph DNA readback, SourceFamilyOpportunityScorecard,
 * MemeExecutionRouteStack coverage/readiness, ResearchScout cached hints.
 * Authority today: report/cache only. No hard gate, no zero-size, no trade action.
 */
object UltimateEdgeEngine {
    data class LaneEdgeCard(
        val mint: String,
        val symbol: String,
        val lane: String,
        val source: String,
        val scoreBias: Int,
        val sizeMult: Double,
        val semanticReason: String,
        val sourceSummary: String,
        val routeSummary: String,
        val researchHint: String,
        val generatedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun compact(): String = "ULTIMATE_EDGE_CARD_4321 lane=$lane source=$source scoreBias=$scoreBias sizeMult=${sizeMult.fmtEdgeLocal(3)} semantic=${semanticReason.take(90)} route=${routeSummary.take(120)} research=${researchHint.take(100)} report_only=true no_execution_authority=true"
    }

    private val cards = ConcurrentHashMap<String, LaneEdgeCard>()
    private fun key(mint: String, lane: String) = lane.uppercase().take(32) + ":" + mint.take(64)

    fun cached(mint: String, lane: String): LaneEdgeCard? = cards[key(mint, lane)]

    fun enqueueRefresh(
        mint: String,
        symbol: String,
        lane: String,
        source: String,
        entryScore: Int = 0,
        reason: String = "",
    ): Boolean {
        if (mint.isBlank()) return false
        return ChokeReliefBus.launch("ULTIMATE_EDGE_ENGINE_REFRESH_4321", mint) {
            val card = buildCard(mint, symbol, lane, source, entryScore, reason)
            cards[key(mint, lane)] = card
            try { PipelineHealthCollector.labelInc("ULTIMATE_EDGE_CARD_REFRESH_4321/${lane.take(24)}") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("ULTIMATE_EDGE_CARD_4321", "mint=${mint.take(10)} symbol=${symbol.take(18)} ${card.compact()}") } catch (_: Throwable) {}
        }
    }

    private fun buildCard(mint: String, symbol: String, lane: String, source: String, entryScore: Int, reason: String): LaneEdgeCard {
        val safeLane = lane.ifBlank { "UNKNOWN" }.uppercase().take(32)
        val safeSource = source.ifBlank { "UNKNOWN" }.uppercase().take(64)
        val semantic = try {
            SemanticPatternGraph.entryDnaBias(
                setup = "$symbol $safeLane $safeSource score_$entryScore ${reason.take(80)}",
                lane = safeLane,
                deployer = "",
                source = safeSource,
                dnaKey = "lane_$safeLane source_$safeSource score_$entryScore",
            )
        } catch (_: Throwable) { SemanticPatternGraph.EntryBias(1.0, 0, "dna:error") }
        val coverage = try {
            MemeExecutionRouteStack.coverage(
                MemeExecutionRouteStack.ExecutionRouteContext(
                    side = MemeExecutionRouteStack.Side.BUY,
                    mint = mint,
                    symbol = symbol,
                    reason = "ultimate_edge_engine_4321",
                    sourceTags = safeSource,
                    callSite = "UltimateEdgeEngine",
                    sideEffectLight = true,
                    routeIntelligence = MemeExecutionRouteStack.RouteIntelligenceSnapshot(
                        pumpPortalSignal = safeSource.takeIf { it.contains("PUMP") } ?: "",
                        pumpFunBondingSignal = mint.endsWith("pump", ignoreCase = true) || safeSource.contains("PUMP"),
                        recommendedVenues = listOf(safeSource).filter { it != "UNKNOWN" },
                    )
                )
            )
        } catch (_: Throwable) { null }
        val routeSummary = coverage?.let { "supported=${it.supportedProviderNames} unsupported=${it.unsupportedProviderNames} adapterGaps=${it.adapterGapProviderNames} senderGaps=${it.adapterGapSenderNames}" } ?: "route:unavailable"
        val sourceSummary = try { SourceFamilyOpportunityScorecard.snapshot().ifBlank { "source_scorecard:empty" }.take(320) } catch (_: Throwable) { "source_scorecard:error" }
        val researchHint = try { ResearchScout.riskHint(mint).take(220) } catch (_: Throwable) { "ResearchScout:error" }
        val adapterPenalty = if ((coverage?.adapterGapProviderNames?.size ?: 0) >= 6) 0.98 else 1.0
        val sizeMult = (semantic.sizeMult * adapterPenalty).coerceIn(0.90, 1.08)
        return LaneEdgeCard(
            mint = mint,
            symbol = symbol,
            lane = safeLane,
            source = safeSource,
            scoreBias = semantic.scoreDelta.coerceIn(0, 5),
            sizeMult = sizeMult,
            semanticReason = semantic.reason,
            sourceSummary = sourceSummary,
            routeSummary = routeSummary,
            researchHint = researchHint,
        )
    }

    fun status(limit: Int = 8): String {
        val recent = cards.values.sortedByDescending { it.generatedAtMs }.take(limit.coerceIn(1, 20))
        if (recent.isEmpty()) return "ULTIMATE_EDGE_ENGINE_4321 cards=0 cache_first=true background_only=true no_execution_authority=true"
        return "ULTIMATE_EDGE_ENGINE_4321 cards=${cards.size} " + recent.joinToString(" | ") { "${it.lane}/${it.source} bias=${it.scoreBias} size=${it.sizeMult.fmtEdgeLocal(3)}" } + " cache_first=true background_only=true no_execution_authority=true"
    }

    fun exportState(): String = JSONArray().also { a ->
        cards.values.sortedByDescending { it.generatedAtMs }.take(240).forEach { c ->
            a.put(JSONObject()
                .put("mint", c.mint).put("symbol", c.symbol).put("lane", c.lane).put("source", c.source)
                .put("scoreBias", c.scoreBias).put("sizeMult", c.sizeMult)
                .put("semanticReason", c.semanticReason).put("sourceSummary", c.sourceSummary)
                .put("routeSummary", c.routeSummary).put("researchHint", c.researchHint).put("ts", c.generatedAtMs))
        }
    }.toString()

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val a = JSONArray(raw)
            cards.clear()
            for (i in 0 until a.length().coerceAtMost(240)) {
                val o = a.optJSONObject(i) ?: continue
                val card = LaneEdgeCard(
                    mint = o.optString("mint"),
                    symbol = o.optString("symbol"),
                    lane = o.optString("lane"),
                    source = o.optString("source"),
                    scoreBias = o.optInt("scoreBias", 0),
                    sizeMult = o.optDouble("sizeMult", 1.0).coerceIn(0.90, 1.08),
                    semanticReason = o.optString("semanticReason"),
                    sourceSummary = o.optString("sourceSummary"),
                    routeSummary = o.optString("routeSummary"),
                    researchHint = o.optString("researchHint"),
                    generatedAtMs = o.optLong("ts", System.currentTimeMillis()),
                )
                if (card.mint.isNotBlank() && card.lane.isNotBlank()) cards[key(card.mint, card.lane)] = card
            }
        } catch (_: Throwable) {}
    }

    fun reset() { cards.clear() }
}

private fun Double.fmtEdgeLocal(decimals: Int): String = try {
    java.lang.String.format(java.util.Locale.US, "% ." + decimals + "f", this).replace(" ", "")
} catch (_: Throwable) { this.toString() }
