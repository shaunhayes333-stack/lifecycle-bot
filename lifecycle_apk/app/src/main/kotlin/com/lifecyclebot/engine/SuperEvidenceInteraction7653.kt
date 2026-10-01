package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7653 - learned interaction graph between evidence ancestry families.
 *
 * Individual family reliability is not enough: two individually useful reasoners
 * can become redundant or harmful when combined, while two modest reasoners can be
 * genuinely complementary. This layer learns those pairwise interactions from the
 * exact position-bound terminal stream already owned by SuperIntelligenceCalibration.
 *
 * Pure/local/advisory. It cannot execute, reserve capital, size, veto or weaken safety.
 */
object SuperEvidenceInteraction7653 {
    enum class Relation { AGREE, CONFLICT }

    data class Stats(
        var n: Long = 0L,
        var aligned: Long = 0L,
        var signedRealizedSum: Double = 0.0,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    private fun pairKey(
        lane: String,
        a: SuperEvidenceTopology7651.Family,
        b: SuperEvidenceTopology7651.Family,
        relation: Relation,
    ): String {
        val ordered = listOf(a.name, b.name).sorted()
        return lane.trim().uppercase() + "|" + ordered[0] + "|" + ordered[1] + "|" + relation.name
    }

    private fun relation(a: Double, b: Double): Relation =
        if (a * b >= 0.0) Relation.AGREE else Relation.CONFLICT

    fun recordOutcome(
        lane: String,
        familyUtility: Map<SuperEvidenceTopology7651.Family, Double>,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        val active = familyUtility.entries
            .filter { it.value.isFinite() && abs(it.value) >= 0.05 }
            .sortedBy { it.key.name }

        for (i in 0 until active.size) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                val rel = relation(a.value, b.value)
                val s = stats.computeIfAbsent(pairKey(lane, a.key, b.key, rel)) { Stats() }

                // The interaction is judged by the pair's combined signed thesis.
                // A perfectly cancelling conflict does not receive directional credit.
                val combined = a.value + b.value
                val directional = when {
                    combined > 0.05 -> 1
                    combined < -0.05 -> -1
                    else -> 0
                }
                synchronized(s) {
                    s.n += 1L
                    if (directional != 0) {
                        val correct =
                            (directional > 0 && realizedReturnPct > 0.0) ||
                                (directional < 0 && realizedReturnPct <= 0.0)
                        if (correct) s.aligned += 1L
                        s.signedRealizedSum +=
                            (if (directional > 0) realizedReturnPct else -realizedReturnPct)
                                .coerceIn(-100.0, 100.0)
                    }
                }
            }
        }
        try { PipelineHealthCollector.labelInc("SUPER_EVIDENCE_INTERACTIONS_GRADED_7653") } catch (_: Throwable) {}
    }

    /**
     * Bounded learned correction over the already de-correlated family utilities.
     * Sparse pairs are neutral. The correction is sample-shrunk and capped so it
     * can refine reasoning structure but never dominate first-order evidence.
     */
    fun adjustment(
        lane: String,
        familyUtility: Map<SuperEvidenceTopology7651.Family, Double>,
    ): Double {
        val active = familyUtility.entries
            .filter { it.value.isFinite() && abs(it.value) >= 0.05 }
            .sortedBy { it.key.name }
        if (active.size < 2) return 0.0

        var total = 0.0
        var used = 0
        for (i in 0 until active.size) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                val rel = relation(a.value, b.value)
                val s = stats[pairKey(lane, a.key, b.key, rel)] ?: continue
                val pairAdjustment = synchronized(s) {
                    if (s.n < 12L) return@synchronized 0.0
                    val accuracy = (s.aligned.toDouble() / s.n.toDouble()).coerceIn(0.0, 1.0)
                    val meanSigned = (s.signedRealizedSum / s.n.toDouble()).coerceIn(-40.0, 40.0)
                    val payoffQuality = (meanSigned / 40.0).coerceIn(-1.0, 1.0)
                    val sample = (s.n.toDouble() / (s.n + 24.0)).coerceIn(0.0, 1.0)

                    // 50% directional accuracy and zero signed payoff are neutral.
                    val quality = ((accuracy - 0.50) * 1.2 + payoffQuality * 0.35)
                        .coerceIn(-0.75, 0.75) * sample

                    // Only the overlapping evidence mass is eligible for pair credit.
                    val overlap = minOf(abs(a.value), abs(b.value), 2.0)
                    val relationScale = if (rel == Relation.AGREE) 1.0 else 0.70
                    quality * overlap * relationScale
                }
                if (pairAdjustment != 0.0) {
                    total += pairAdjustment
                    used += 1
                }
            }
        }
        if (used == 0) return 0.0
        return total.coerceIn(-1.5, 1.5)
    }

    fun exportJson(): JSONArray {
        val out = JSONArray()
        stats.forEach { (k, s) ->
            synchronized(s) {
                out.put(
                    JSONObject()
                        .put("k", k)
                        .put("n", s.n)
                        .put("aligned", s.aligned)
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
                aligned = o.optLong("aligned", 0L),
                signedRealizedSum = o.optDouble("signedRealizedSum", 0.0),
            )
        }
    }

    internal fun resetForTest() {
        stats.clear()
    }
}
