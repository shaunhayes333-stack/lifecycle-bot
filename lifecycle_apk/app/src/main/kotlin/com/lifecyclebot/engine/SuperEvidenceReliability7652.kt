package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7652 - exact-outcome reliability for evidence ancestry families.
 *
 * Family support is frozen into the canonical decision stamp at entry and graded
 * once at terminal close. Trust is lane-local, neutral when sparse, persistent,
 * bounded and advisory only.
 */
object SuperEvidenceReliability7652 {
    data class Stats(
        var n: Long = 0L,
        var directionCorrect: Long = 0L,
        var signedRealizedSum: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    private fun key(lane: String, family: SuperEvidenceTopology7651.Family): String =
        lane.trim().uppercase() + "|" + family.name

    fun recordOutcome(
        lane: String,
        familyUtility: Map<SuperEvidenceTopology7651.Family, Double>,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        familyUtility.forEach { (family, utility) ->
            if (!utility.isFinite() || kotlin.math.abs(utility) < 0.02) return@forEach
            val s = stats.computeIfAbsent(key(lane, family)) { Stats() }
            synchronized(s) {
                s.n += 1L
                val correct =
                    (utility > 0.0 && realizedReturnPct > 0.0) ||
                        (utility < 0.0 && realizedReturnPct <= 0.0)
                if (correct) s.directionCorrect += 1L
                val signed = if (utility >= 0.0) realizedReturnPct else -realizedReturnPct
                s.signedRealizedSum += signed.coerceIn(-100.0, 100.0)
            }
        }
        try { PipelineHealthCollector.labelInc("SUPER_EVIDENCE_OUTCOME_GRADED_7652") } catch (_: Throwable) {}
    }

    fun trust(lane: String, family: SuperEvidenceTopology7651.Family): Double {
        val s = stats[key(lane, family)] ?: return 1.0
        return synchronized(s) {
            if (s.n < 8L) return@synchronized 1.0
            val accuracy = (s.directionCorrect.toDouble() / s.n.toDouble()).coerceIn(0.0, 1.0)
            val meanSigned = (s.signedRealizedSum / s.n.toDouble())
            val payoffQuality = (0.5 + meanSigned / 40.0).coerceIn(0.0, 1.0)
            val sampleReliability = (s.n.toDouble() / (s.n + 20.0)).coerceIn(0.0, 1.0)
            val learned = 0.65 + accuracy * 0.35 + payoffQuality * 0.15
            (1.0 + (learned - 1.0) * sampleReliability).coerceIn(0.65, 1.15)
        }
    }

    fun exportJson(): JSONArray {
        val out = JSONArray()
        stats.forEach { (k, s) ->
            synchronized(s) {
                out.put(
                    JSONObject()
                        .put("k", k)
                        .put("n", s.n)
                        .put("directionCorrect", s.directionCorrect)
                        .put("signedRealizedSum", s.signedRealizedSum)
                )
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
                directionCorrect = o.optLong("directionCorrect", 0L),
                signedRealizedSum = o.optDouble("signedRealizedSum", 0.0),
            )
        }
    }

    internal fun resetForTest() {
        stats.clear()
    }
}
