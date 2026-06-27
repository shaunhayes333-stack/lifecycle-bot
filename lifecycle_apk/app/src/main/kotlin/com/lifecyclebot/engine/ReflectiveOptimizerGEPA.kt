package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** V5.0.4243 — GEPA-style reflective optimizer, proposal-only. */
object ReflectiveOptimizerGEPA {
    data class ReflectionProposal(
        val id: String,
        val lane: String,
        val metric: String,
        val proposal: String,
        val rollback: String,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private const val MAX_PROPOSALS = 240
    private const val MIN_REFLECTION_INTERVAL_MS = 60_000L  // V5.0.4249 — prevent terminal-sell storm churn
    private val proposals = CopyOnWriteArrayList<ReflectionProposal>()
    private val lastRunByLaneMs = ConcurrentHashMap<String, Long>()

    fun runBackgroundReflection(lane: String = "STANDARD", sourceTag: String = "BACKGROUND_GEPA_REFLECTION"): Boolean {
        if (!isBackgroundSource(sourceTag)) return false
        val laneKey = lane.uppercase().take(32)
        val now = System.currentTimeMillis()
        val prev = lastRunByLaneMs[laneKey] ?: 0L
        if (now - prev < MIN_REFLECTION_INTERVAL_MS) return false
        lastRunByLaneMs[laneKey] = now
        val replayHint = try { CounterfactualReplayEngine.policyHints(lane) } catch (_: Throwable) { "" }
        val semanticHint = try { SemanticPatternGraph.summary() } catch (_: Throwable) { "" }
        val combined = listOf(replayHint, semanticHint).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.isBlank() || combined.contains("no missed exit alternatives", ignoreCase = true)) return false
        val proposal = "GEPA_REFLECTION lane=$lane evidence=[$combined] action=prefer bounded exit-policy experiment; no hard veto; no zero-size; background-review only"
        val rollback = "rollback if terminal sample>=40 and delta_capture_or_net_pnl worsens vs prior lane baseline"
        val item = ReflectionProposal(stableId(lane, proposal), laneKey, "exit_delta_capture", proposal.take(1000), rollback)
        synchronized(proposals) {
            proposals.removeAll { it.id == item.id }
            proposals.add(item)
            while (proposals.size > MAX_PROPOSALS) proposals.removeAt(0)
        }
        return try {
            AsyncStrategyLab.submitBackgroundHypothesis(
                provider = AsyncStrategyLab.Provider.LOCAL_ONLY,
                lane = item.lane,
                expectedMetric = item.metric,
                proposal = item.proposal,
                rollbackCondition = item.rollback,
                symbolicChecked = false,
            )
        } catch (_: Throwable) { false }
    }

    fun recent(limit: Int = 20): List<ReflectionProposal> = synchronized(proposals) { proposals.takeLast(limit.coerceIn(1, MAX_PROPOSALS)) }
    fun reset() { synchronized(proposals) { proposals.clear() }; lastRunByLaneMs.clear() }

    fun exportState(): String = synchronized(proposals) {
        val arr = JSONArray()
        proposals.takeLast(MAX_PROPOSALS).forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("lane", p.lane).put("metric", p.metric).put("proposal", p.proposal).put("rollback", p.rollback).put("createdAt", p.createdAtMs))
        }
        JSONObject().put("proposals", arr).toString()
    }

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val arr = JSONObject(raw).optJSONArray("proposals") ?: JSONArray()
            val restored = mutableListOf<ReflectionProposal>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                restored.add(ReflectionProposal(p.optString("id"), p.optString("lane"), p.optString("metric"), p.optString("proposal"), p.optString("rollback"), p.optLong("createdAt", System.currentTimeMillis())))
            }
            synchronized(proposals) {
                proposals.clear()
                proposals.addAll(restored.filter { it.id.isNotBlank() && it.proposal.isNotBlank() }.takeLast(MAX_PROPOSALS))
            }
        } catch (_: Throwable) {}
    }

    private fun isBackgroundSource(sourceTag: String): Boolean {
        val u = sourceTag.uppercase()
        return u.contains("BACKGROUND") && !u.contains("SCANNER") && !u.contains("FDG") && !u.contains("EXECUTOR") && !u.contains("BUY") && !u.contains("SELL")
    }

    private fun stableId(vararg parts: Any): String {
        val raw = parts.joinToString("|") { it.toString().take(200) }
        return "gepa_" + java.lang.Long.toUnsignedString(raw.hashCode().toLong() xor raw.length.toLong())
    }
}
