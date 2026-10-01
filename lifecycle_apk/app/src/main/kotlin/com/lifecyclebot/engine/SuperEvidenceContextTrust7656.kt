package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7656 - state-conditioned epistemic trust.
 *
 * Family usefulness is contextual: the same LLM/cross-talk/scanner/specialist
 * evidence can be strong in ACCELERATING markets and weak in DISTRIBUTING or
 * FRAGILE states. Exact canonical outcomes train lane x latent-state x family
 * trust, hierarchically on top of the broader lane trust from 7652.
 */
object SuperEvidenceContextTrust7656 {
    data class Stats(
        var n: Long = 0L,
        var correct: Long = 0L,
        var signedRealizedSum: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    private fun key(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        family: SuperEvidenceTopology7651.Family,
    ): String = lane.trim().uppercase() + "|" + state.name + "|" + family.name

    fun recordOutcome(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        familyUtility: Map<SuperEvidenceTopology7651.Family, Double>,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        familyUtility.forEach { (family, utility) ->
            if (!utility.isFinite() || abs(utility) < 0.05) return@forEach
            val s = stats.computeIfAbsent(key(lane, state, family)) { Stats() }
            synchronized(s) {
                s.n += 1L
                val aligned =
                    (utility > 0.0 && realizedReturnPct > 0.0) ||
                        (utility < 0.0 && realizedReturnPct <= 0.0)
                if (aligned) s.correct += 1L
                val signed = if (utility > 0.0) realizedReturnPct else -realizedReturnPct
                s.signedRealizedSum += signed.coerceIn(-100.0, 100.0)
            }
        }
        try {
            PipelineHealthCollector.labelInc("SUPER_EVIDENCE_CONTEXT_GRADED_7656_" + state.name)
        } catch (_: Throwable) {}
    }

    /**
     * Multiplicative context factor around neutral=1.0. Sparse cells remain
     * neutral, so 7652's broader lane trust remains the hierarchical fallback.
     */
    fun trust(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        family: SuperEvidenceTopology7651.Family,
    ): Double {
        val s = stats[key(lane, state, family)] ?: return 1.0
        return synchronized(s) {
            if (s.n < 6L) return@synchronized 1.0
            val accuracy = (s.correct.toDouble() / s.n.toDouble()).coerceIn(0.0, 1.0)
            val meanSigned = (s.signedRealizedSum / s.n.toDouble()).coerceIn(-40.0, 40.0)
            val payoffQuality = (0.5 + meanSigned / 40.0).coerceIn(0.0, 1.0)
            val sample = (s.n.toDouble() / (s.n + 18.0)).coerceIn(0.0, 1.0)
            val learned = (0.62 + accuracy * 0.38 + payoffQuality * 0.16)
                .coerceIn(0.60, 1.18)
            (1.0 + (learned - 1.0) * sample).coerceIn(0.72, 1.15)
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
                        .put("correct", s.correct)
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
                correct = o.optLong("correct", 0L),
                signedRealizedSum = o.optDouble("signedRealizedSum", 0.0),
            )
        }
    }

    internal fun resetForTest() {
        stats.clear()
    }
}
