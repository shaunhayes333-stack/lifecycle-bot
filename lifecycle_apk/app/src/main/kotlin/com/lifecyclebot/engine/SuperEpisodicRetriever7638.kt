
package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExactStrategyPerformance7429
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7638 - retrieval-augmented episodic memory for Super Intelligence.
 *
 * Exact playbook evidence is O(1). Semantic fallback is local-only and cached
 * so the oracle never introduces provider I/O or repeated graph scans.
 */
object SuperEpisodicRetriever7638 {
    data class Retrieval(
        val exactSamples: Long,
        val exactWinRatePct: Double,
        val exactMeanPnlPct: Double,
        val semanticSizeMult: Double,
        val semanticScoreDelta: Int,
        val confidence: Double,
        val priorUtilityDelta: Double,
        val source: String,
    ) {
        fun contributionTag(): String = String.format(
            java.util.Locale.US,
            "memory7638(src=%s,n=%d,wr=%.0f%%,ev=%+.1f,sem=%.2f/%+d,conf=%.2f,prior=%+.1f)",
            source,
            exactSamples,
            exactWinRatePct,
            exactMeanPnlPct,
            semanticSizeMult,
            semanticScoreDelta,
            confidence,
            priorUtilityDelta,
        )
    }

    private data class Cached(val value: Retrieval, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val TTL_MS = 20_000L

    fun retrieve(
        liveMode: Boolean,
        lane: String,
        tradeType: String,
        setup: String,
        style: String,
        tactic: String,
        sourceFamily: String,
        regime: String,
        edgePhase: String,
    ): Retrieval {
        val key = listOf(
            if (liveMode) "L" else "P",
            lane.uppercase(),
            tradeType.uppercase(),
            setup.uppercase(),
            style.uppercase(),
            tactic.uppercase(),
            sourceFamily.uppercase(),
            regime.uppercase(),
            edgePhase.uppercase(),
        ).joinToString("|")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.atMs <= TTL_MS }?.let { return it.value }

        val exact = try {
            ExactStrategyPerformance7429.evidenceFor7431(
                liveMode = liveMode,
                lane = lane,
                tradeType = tradeType,
                setup = setup,
                style = style,
                tactic = tactic,
            )
        } catch (_: Throwable) { null }

        var semanticMult = 1.0
        var semanticDelta = 0
        var semanticSource = "none"

        if (exact == null) {
            try {
                val sem = SemanticPatternGraph.entryDnaBias(
                    setup = listOf(setup, style, tactic, regime, edgePhase).joinToString(" "),
                    lane = lane,
                    source = sourceFamily,
                    dnaKey = "tradeType=" + tradeType + "|setup=" + setup + "|style=" + style,
                    limit = 8,
                )
                semanticMult = sem.sizeMult
                semanticDelta = sem.scoreDelta
                semanticSource = "semantic"
            } catch (_: Throwable) {}
        }

        val n = exact?.n ?: 0L
        val wr = exact?.winRatePct ?: 50.0
        val ev = exact?.meanPnlPct ?: 0.0
        val confidence = when {
            exact != null -> (n.toDouble() / (n + 8.0)).coerceIn(0.0, 1.0)
            semanticSource == "semantic" -> 0.25
            else -> 0.0
        }
        val prior = if (exact != null) {
            (ev * 0.22 + (wr - 50.0) * 0.10).coerceIn(-12.0, 12.0) * confidence
        } else {
            (((semanticMult - 1.0) * 30.0) + semanticDelta * 0.7)
                .coerceIn(-6.0, 6.0) * confidence
        }

        val r = Retrieval(
            exactSamples = n,
            exactWinRatePct = wr,
            exactMeanPnlPct = ev,
            semanticSizeMult = semanticMult,
            semanticScoreDelta = semanticDelta,
            confidence = confidence,
            priorUtilityDelta = prior,
            source = exact?.source ?: semanticSource,
        )
        cache[key] = Cached(r, now)
        if (cache.size > 2048) cache.entries.removeIf { now - it.value.atMs > TTL_MS }
        return r
    }
}
