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

    fun pendingForReview(limit: Int = 20): List<LabHypothesis> = synchronized(accepted) {
        accepted.takeLast(limit.coerceIn(1, MAX_ACCEPTED))
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

    private fun looksHotPath(s: String): Boolean {
        val u = s.uppercase()
        return listOf("SCANNER_HOT_PATH_API", "FDG_HOT_PATH_API", "EXECUTOR_HOT_PATH_API", "SYNCHRONOUS_BUY_API", "SYNC_LLM_IN_EXECUTOR").any { u.contains(it) }
    }
}
