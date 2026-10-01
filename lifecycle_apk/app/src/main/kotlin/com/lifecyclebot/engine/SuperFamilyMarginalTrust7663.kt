package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7663 - trust learned from marginal rather than mere presence.
 *
 * A family is credited only when removing it would materially change the fused
 * chosen-policy reasoning. This prevents passengers on a winning trade from
 * receiving the same credit as the reasoners that actually moved the decision.
 */
object SuperFamilyMarginalTrust7663 {
    data class Stats(
        var n: Long = 0L,
        var correct: Long = 0L,
        var signedRealizedSum: Double = 0.0,
        var marginalMass: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    private fun key(lane: String, family: SuperEvidenceTopology7651.Family): String =
        lane.trim().uppercase() + "|" + family.name

    fun recordOutcome(
        lane: String,
        marginal: Map<SuperEvidenceTopology7651.Family, Double>,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        marginal.forEach { (family, m) ->
            if (!m.isFinite() || abs(m) < 0.10) return@forEach
            val s = stats.computeIfAbsent(key(lane, family)) { Stats() }
            synchronized(s) {
                s.n += 1L
                val ok = (m > 0.0 && realizedReturnPct > 0.0) ||
                    (m < 0.0 && realizedReturnPct <= 0.0)
                if (ok) s.correct += 1L
                val signed = if (m > 0.0) realizedReturnPct else -realizedReturnPct
                val importance = (abs(m) / 2.0).coerceIn(0.25, 1.5)
                s.signedRealizedSum += signed.coerceIn(-100.0, 100.0) * importance
                s.marginalMass += abs(m)
            }
        }
        try { PipelineHealthCollector.labelInc("SUPER_FAMILY_MARGINAL_GRADED_7663") } catch (_: Throwable) {}
    }

    fun trust(lane: String, family: SuperEvidenceTopology7651.Family): Double {
        val s = stats[key(lane, family)] ?: return 1.0
        return synchronized(s) {
            if (s.n < 10L || s.marginalMass < 2.0) return@synchronized 1.0
            val acc = (s.correct.toDouble() / s.n.toDouble()).coerceIn(0.0, 1.0)
            val mean = (s.signedRealizedSum / s.n.toDouble()).coerceIn(-50.0, 50.0)
            val payoff = (0.5 + mean / 50.0).coerceIn(0.0, 1.0)
            val sample = (s.n.toDouble() / (s.n + 28.0)).coerceIn(0.0, 1.0)
            val learned = (0.64 + acc * 0.36 + payoff * 0.14).coerceIn(0.62, 1.16)
            (1.0 + (learned - 1.0) * sample).coerceIn(0.80, 1.10)
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
                    .put("signedRealizedSum", s.signedRealizedSum)
                    .put("marginalMass", s.marginalMass))
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
                marginalMass = o.optDouble("marginalMass", 0.0),
            )
        }
    }

    internal fun resetForTest() { stats.clear() }
}
