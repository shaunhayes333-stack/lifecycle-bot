package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

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
     * V5.0.4245 — reviewed proposal apply layer.
     * Only symbolic-checked, background-only, lane-matched hypotheses may add a
     * tiny size multiplier. Unreviewed provider/GEPA proposals remain stored for
     * human/validator review and return neutral 1.0.
     */
    fun reviewedSizeBias(lane: String, score: Int, regime: String): Double = synchronized(accepted) {
        try {
            val laneKey = lane.uppercase().take(24)
            val scoreBand = when {
                score >= 80 -> "S80"; score >= 60 -> "S60"; score >= 40 -> "S40"; score >= 20 -> "S20"; else -> "S00"
            }
            val candidates = accepted.asSequence()
                .filter { it.backgroundOnly && it.symbolicChecked }
                .filter { it.lane == laneKey || it.lane == "ALL" || laneKey.contains(it.lane) || it.lane.contains(laneKey) }
                .filter { !looksHotPath(it.proposal) && !looksHotPath(it.expectedMetric) && !looksHotPath(it.rollbackCondition) }
                .toList()
            if (candidates.isEmpty()) return@synchronized 1.0
            var bias = 1.0
            candidates.takeLast(4).forEach { h ->
                val text = (h.expectedMetric + " " + h.proposal + " " + regime + " " + scoreBand).uppercase()
                bias *= when {
                    text.contains("SIZE_DOWN") || text.contains("RISK_DOWN") || text.contains("DRAWDOWN") -> 0.97
                    text.contains("SIZE_UP") || text.contains("COMPOUND") || text.contains("RUNNER_CAPTURE") -> 1.03
                    else -> 1.0
                }
            }
            bias.coerceIn(0.92, 1.08)
        } catch (_: Throwable) { 1.0 }
    }

    fun reset() { synchronized(accepted) { accepted.clear() } }

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
            }
        } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String = synchronized(accepted) {
        if (accepted.isEmpty()) "AsyncStrategyLab: no accepted background hypotheses"
        else buildString {
            appendLine("AsyncStrategyLab: accepted=${accepted.size} background_only=true")
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
