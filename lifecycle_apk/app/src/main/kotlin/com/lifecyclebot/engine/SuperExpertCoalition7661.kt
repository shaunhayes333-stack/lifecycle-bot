package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7661 - contextual trust for expert coalitions.
 *
 * Learns whether a specific set of already-participating experts works well
 * together in a lane x latent-state context. This is subordinate to family
 * trust and individual expert trust; it never creates a new vote.
 */
object SuperExpertCoalition7661 {
    data class Stats(
        var n: Long = 0L,
        var correct: Long = 0L,
        var signedRealizedSum: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    private fun coalitionKey(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        experts: Collection<String>,
    ): String {
        val names = experts.map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(8)
        if (names.size < 2) return ""
        return lane.trim().uppercase() + "|" + state.name + "|" + names.joinToString("+")
    }

    fun recordOutcome(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        expertUtility: Map<String, Double>,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        val active = expertUtility.filterValues { it.isFinite() && abs(it) >= 0.05 }
        val cross = active.filterKeys { it.startsWith("CROSSTALK:") }
        if (cross.size < 2) return
        val k = coalitionKey(lane, state, cross.keys)
        if (k.isBlank()) return

        // Coalition thesis is the mean signed utility of its members.
        val thesis = cross.values.average()
        if (abs(thesis) < 0.05) return
        val s = stats.computeIfAbsent(k) { Stats() }
        synchronized(s) {
            s.n += 1L
            val ok = (thesis > 0.0 && realizedReturnPct > 0.0) ||
                (thesis < 0.0 && realizedReturnPct <= 0.0)
            if (ok) s.correct += 1L
            val signed = if (thesis > 0.0) realizedReturnPct else -realizedReturnPct
            s.signedRealizedSum += signed.coerceIn(-100.0, 100.0)
        }
        try { PipelineHealthCollector.labelInc("SUPER_EXPERT_COALITION_GRADED_7661") } catch (_: Throwable) {}
    }

    fun trust(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        experts: Collection<String>,
    ): Double {
        val k = coalitionKey(lane, state, experts)
        if (k.isBlank()) return 1.0
        val s = stats[k] ?: return 1.0
        return synchronized(s) {
            if (s.n < 10L) return@synchronized 1.0
            val acc = (s.correct.toDouble() / s.n.toDouble()).coerceIn(0.0, 1.0)
            val mean = (s.signedRealizedSum / s.n.toDouble()).coerceIn(-40.0, 40.0)
            val payoff = (0.5 + mean / 40.0).coerceIn(0.0, 1.0)
            val sample = (s.n.toDouble() / (s.n + 24.0)).coerceIn(0.0, 1.0)
            val learned = (0.64 + acc * 0.36 + payoff * 0.14).coerceIn(0.62, 1.16)
            (1.0 + (learned - 1.0) * sample).coerceIn(0.78, 1.12)
        }
    }

    fun exportJson(): JSONArray {
        val out = JSONArray()
        stats.forEach { (k, s) ->
            synchronized(s) {
                out.put(JSONObject()
                    .put("k", k)
                    .put("n", s.n)
                    .put("correct", s.correct)
                    .put("signedRealizedSum", s.signedRealizedSum))
            }
        }
        return out
    }

    fun importJson(rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val k = o.optString("k", "")
            if (k.isBlank()) continue
            stats[k] = Stats(
                n = o.optLong("n", 0L),
                correct = o.optLong("correct", 0L),
                signedRealizedSum = o.optDouble("signedRealizedSum", 0.0),
            )
        }
    }

    internal fun resetForTest() { stats.clear() }
}
