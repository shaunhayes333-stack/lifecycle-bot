package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

object SuperExpertTrust7660 {
    data class Stats(
        var n: Long = 0L,
        var correct: Long = 0L,
        var signedRealizedSum: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()
    private fun key(expert: String): String = expert.trim().uppercase().take(96)

    fun recordOutcome(expertUtility: Map<String, Double>, realizedReturnPct: Double) {
        if (!realizedReturnPct.isFinite()) return
        expertUtility.forEach { (raw, utility) ->
            if (!utility.isFinite() || abs(utility) < 0.05) return@forEach
            val k = key(raw)
            if (k.isBlank()) return@forEach
            val s = stats.computeIfAbsent(k) { Stats() }
            synchronized(s) {
                s.n += 1L
                val ok = (utility > 0.0 && realizedReturnPct > 0.0) ||
                    (utility < 0.0 && realizedReturnPct <= 0.0)
                if (ok) s.correct += 1L
                val signed = if (utility > 0.0) realizedReturnPct else -realizedReturnPct
                s.signedRealizedSum += signed.coerceIn(-100.0, 100.0)
            }
        }
        try { PipelineHealthCollector.labelInc("SUPER_EXPERT_OUTCOME_GRADED_7660") } catch (_: Throwable) {}
    }

    fun trust(expert: String): Double {
        val s = stats[key(expert)] ?: return 1.0
        return synchronized(s) {
            if (s.n < 8L) return@synchronized 1.0
            val acc = (s.correct.toDouble() / s.n.toDouble()).coerceIn(0.0, 1.0)
            val mean = (s.signedRealizedSum / s.n.toDouble()).coerceIn(-40.0, 40.0)
            val payoff = (0.5 + mean / 40.0).coerceIn(0.0, 1.0)
            val sample = (s.n.toDouble() / (s.n + 20.0)).coerceIn(0.0, 1.0)
            val learned = (0.63 + acc * 0.37 + payoff * 0.15).coerceIn(0.60, 1.18)
            (1.0 + (learned - 1.0) * sample).coerceIn(0.70, 1.15)
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
