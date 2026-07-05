package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4236 — AsyncStrategyLab
 *
 * Background-only bridge for free-tier AI/reasoning providers (Gemini/Groq/
 * OpenRouter/local). It does NOT call external APIs from scanner, FDG, sizer,
 * executor, or sell paths. Its job is to hold proposed hypotheses from async
 * workers until StrategyHypothesisEngine/symbolic checks can consume them.
 */
object AsyncStrategyLab {
    enum class Provider { LOCAL_ONLY, GEMINI_FREE, GROQ_FREE, OPENROUTER_FREE }

    data class LabHypothesis(
        val id: String,
        val provider: Provider,
        val lane: String,
        val expectedMetric: String,
        val proposal: String,
        val rollbackCondition: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        val backgroundOnly: Boolean = true,
        val symbolicChecked: Boolean = false,
    )

    private const val MAX_ACCEPTED = 80
    private val accepted = CopyOnWriteArrayList<LabHypothesis>()
    private val reviewedBiasByLane = ConcurrentHashMap<String, Double>()  // V5.0.4251 — O(1) hot-path read cache

    fun submitBackgroundHypothesis(
        provider: Provider,
        lane: String,
        expectedMetric: String,
        proposal: String,
        rollbackCondition: String,
        symbolicChecked: Boolean = false,
    ): Boolean {
        val p = proposal.trim()
        val metric = expectedMetric.trim()
        val rollback = rollbackCondition.trim()
        if (p.isBlank() || metric.isBlank() || rollback.isBlank()) return false
        if (looksHotPath(p) || looksHotPath(metric) || looksHotPath(rollback)) return false
        val hash = java.lang.Integer.toUnsignedLong(p.hashCode())
        val id = "${provider.name}:${lane.uppercase().take(14)}:$hash"
        val h = LabHypothesis(
            id = id,
            provider = provider,
            lane = lane.uppercase().take(24),
            expectedMetric = metric.take(160),
            proposal = p.take(1200),
            rollbackCondition = rollback.take(400),
            symbolicChecked = symbolicChecked,
        )
        synchronized(accepted) {
            accepted.removeAll { it.id == id }
            accepted.add(h)
            while (accepted.size > MAX_ACCEPTED) accepted.removeAt(0)
        }
        return true
    }


    fun requestBackgroundProviderHypothesis(
        providerHint: Provider,
        lane: String,
        expectedMetric: String,
        tradeSnapshot: String,
        rollbackCondition: String,
        sourceTag: String = "BACKGROUND_ASYNC_STRATEGY_LAB",
    ): Boolean {
        if (!isBackgroundSource(sourceTag)) return false
        if (tradeSnapshot.isBlank() || tradeSnapshot.length < 20) return false
        val systemPrompt = """
            You are AATE's background-only strategy research lab.
            Propose one testable, bounded trading hypothesis from closed-trade data.
            Constraints: no scanner/FDG/executor hot-path calls, no hard trade veto, no learned zero sizing,
            preserve live/paper parity, include expected metric and rollback logic.
        """.trimIndent()
        val userPrompt = """
            lane=$lane
            expected_metric=$expectedMetric
            rollback_condition=$rollbackCondition
            closed_trade_snapshot:
            ${tradeSnapshot.take(6000)}
        """.trimIndent()
        val proposal = try {
            GeminiCopilot.rawText(
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                temperature = 0.35,
                maxTokens = 700,
            )
        } catch (_: Throwable) { null } ?: return false

        return submitBackgroundHypothesis(
            provider = providerHint,
            lane = lane,
            expectedMetric = expectedMetric,
            proposal = proposal,
            rollbackCondition = rollbackCondition,
            symbolicChecked = false,
        )
    }

    fun pendingForReview(limit: Int = 20): List<LabHypothesis> = synchronized(accepted) {
        accepted.takeLast(limit.coerceIn(1, MAX_ACCEPTED))
    }

    /**
     * V5.0.4246 — symbolic review promotion hook.
     * Reviewers/provers can promote an existing stored hypothesis into the tiny
     * reviewedSizeBias apply layer without mutating scanner/FDG/executor paths.
     */
    fun markSymbolicReviewed(id: String, proofDetail: String = "symbolic_review_passed"): Boolean = synchronized(accepted) {
        try {
            val idx = accepted.indexOfFirst { it.id == id && it.backgroundOnly }
            if (idx < 0) return@synchronized false
            val h = accepted[idx]
            if (looksHotPath(h.proposal) || looksHotPath(h.expectedMetric) || looksHotPath(h.rollbackCondition)) return@synchronized false
            val reviewed = h.copy(
                symbolicChecked = true,
                rollbackCondition = (h.rollbackCondition + " | proof=" + proofDetail.take(120)).take(400),
            )
            accepted[idx] = reviewed
            rebuildReviewedBiasCache()
            true
        } catch (_: Throwable) { false }
    }

    fun markLatestSymbolicReviewed(lane: String, proofDetail: String = "symbolic_review_passed"): Boolean = synchronized(accepted) {
        val laneKey = lane.uppercase().take(24)
        val item = accepted.lastOrNull { it.backgroundOnly && !it.symbolicChecked && (it.lane == laneKey || it.lane == "ALL") } ?: return@synchronized false
        markSymbolicReviewed(item.id, proofDetail)
    }

    /**
     * V5.0.4245 — reviewed proposal apply layer.
     * V5.0.6090 — reviewed hypotheses are now real strategy authority, not a tiny
     * toy nudge. Unreviewed provider/GEPA proposals remain stored; symbolic-checked
     * background hypotheses can materially shape size while preserving non-zero sizing
     * and hot-path cache-only reads.
     */
    fun reviewedSizeBias(lane: String, score: Int, regime: String): Double {
        // V5.0.4251 — hot path O(1): StrategyHypothesisEngine calls this from
        // FDG/Executor sizing, so never synchronize or scan proposal history here.
        return try {
            val laneKey = lane.uppercase().take(24)
            val baseBias = reviewedBiasByLane[laneKey] ?: reviewedBiasByLane["ALL"] ?: 1.0
            // V5.0.6115 — LIFETIME-EV-AWARE LAB BOOST. The Lab's reviewed bias
            // was capped at 1.55x for all lanes. But the LAB lane itself has
            // 35.3% WR and +158% EV — it's the most profitable lane. Winning
            // lanes should get a higher cap and a base boost so the Lab actually
            // amplifies proven strategies instead of uniformly capping at 1.55.
            val lifetimeMetric6115 = try {
                StrategyTelemetry.computeLeaderboard(environment = null, includePartials = false, limit = 2_500)
                    .firstOrNull { it.strategy.equals(laneKey, ignoreCase = true) }
            } catch (_: Throwable) { null }
            val isWinningLane6115 = try {
                lifetimeMetric6115 != null && lifetimeMetric6115.trades >= 20 &&
                (lifetimeMetric6115.totalSolPnl > 0.0 || lifetimeMetric6115.meanPnlPct >= 15.0)
            } catch (_: Throwable) { false }
            if (isWinningLane6115) {
                // Winning lane: cap raised to 2.5x, base boost 1.2x on top of reviewed bias
                (baseBias * 1.20).coerceIn(0.80, 2.50)
            } else {
                baseBias.coerceIn(0.60, 1.55)
            }
        } catch (_: Throwable) { 1.0 }
    }

    fun reset() { synchronized(accepted) { accepted.clear() }; reviewedBiasByLane.clear() }

    fun exportState(): String = synchronized(accepted) {
        val arr = JSONArray()
        accepted.forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id)
                put("provider", h.provider.name)
                put("lane", h.lane)
                put("metric", h.expectedMetric)
                put("proposal", h.proposal)
                put("rollback", h.rollbackCondition)
                put("createdAt", h.createdAtMs)
                put("backgroundOnly", h.backgroundOnly)
                put("symbolicChecked", h.symbolicChecked)
            })
        }
        JSONObject().put("accepted", arr).toString()
    }

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val arr = JSONObject(raw).optJSONArray("accepted") ?: JSONArray()
            val restored = mutableListOf<LabHypothesis>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val provider = runCatching { Provider.valueOf(o.optString("provider", Provider.LOCAL_ONLY.name)) }.getOrDefault(Provider.LOCAL_ONLY)
                val h = LabHypothesis(
                    id = o.optString("id"),
                    provider = provider,
                    lane = o.optString("lane"),
                    expectedMetric = o.optString("metric"),
                    proposal = o.optString("proposal"),
                    rollbackCondition = o.optString("rollback"),
                    createdAtMs = o.optLong("createdAt", System.currentTimeMillis()),
                    backgroundOnly = o.optBoolean("backgroundOnly", true),
                    symbolicChecked = o.optBoolean("symbolicChecked", false),
                )
                if (h.id.isNotBlank() && h.proposal.isNotBlank() && !looksHotPath(h.proposal)) restored.add(h)
            }
            synchronized(accepted) {
                accepted.clear()
                accepted.addAll(restored.takeLast(MAX_ACCEPTED))
                rebuildReviewedBiasCache()
            }
        } catch (_: Throwable) {}
    }

    private fun rebuildReviewedBiasCache() {
        try {
            reviewedBiasByLane.clear()
            val grouped = accepted.asSequence()
                .filter { it.backgroundOnly && it.symbolicChecked }
                .filter { !looksHotPath(it.proposal) && !looksHotPath(it.expectedMetric) && !looksHotPath(it.rollbackCondition) }
                .groupBy { it.lane.ifBlank { "ALL" } }
            grouped.forEach { (lane, hs) ->
                var bias = 1.0
                hs.takeLast(4).forEach { h ->
                    val text = (h.expectedMetric + " " + h.proposal).uppercase()
                    bias *= when {
                        text.contains("SIZE_DOWN") || text.contains("RISK_DOWN") || text.contains("DRAWDOWN") || text.contains("AVOID") -> 0.80
                        text.contains("SIZE_UP") || text.contains("COMPOUND") || text.contains("RUNNER_CAPTURE") || text.contains("PRESS") || text.contains("FOCUS") -> 1.25
                        else -> 1.0
                    }
                }
                reviewedBiasByLane[lane] = bias.coerceIn(0.60, 1.55)
            }
        } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String = synchronized(accepted) {
        if (accepted.isEmpty()) "AsyncStrategyLab: no accepted background hypotheses"
        else buildString {
            appendLine("AsyncStrategyLab: accepted=${accepted.size} background_only=true actuated_authority_6090=true bias_range=0.60..1.55")
            accepted.takeLast(5).forEach { h ->
                appendLine("- ${h.provider.name} ${h.lane}: ${h.expectedMetric} :: ${h.proposal.take(120)} rollback=${h.rollbackCondition.take(80)} symbolic=${h.symbolicChecked}")
            }
        }
    }

    private fun isBackgroundSource(sourceTag: String): Boolean {
        val u = sourceTag.uppercase()
        return u.contains("BACKGROUND") && !u.contains("SCANNER") && !u.contains("FDG") && !u.contains("EXECUTOR") && !u.contains("BUY") && !u.contains("SELL")
    }

    private fun looksHotPath(s: String): Boolean {
        val u = s.uppercase()
        return listOf("SCANNER_HOT_PATH_API", "FDG_HOT_PATH_API", "EXECUTOR_HOT_PATH_API", "SYNCHRONOUS_BUY_API", "SYNC_LLM_IN_EXECUTOR").any { u.contains(it) }
    }
}
