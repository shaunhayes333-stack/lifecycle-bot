
package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * V5.0.7644 - contextual policy learner for the Super Intelligence tree.
 *
 * Learns which multi-step planning policy actually performs best for each
 * lane + latent-state context from exact position-bound terminal outcomes.
 * It never executes, reserves capital, bypasses safety, or creates a new gate.
 */
object SuperPolicyBandit7644 {
    data class Stat(
        var n: Long = 0L,
        var wins: Long = 0L,
        var pnlSum: Double = 0.0,
        var pnlSqSum: Double = 0.0,
    ) {
        fun mean(): Double = if (n > 0) pnlSum / n.toDouble() else 0.0
        fun wr(): Double = if (n > 0) wins.toDouble() / n.toDouble() else 0.5
        fun variance(): Double {
            if (n <= 1) return 0.0
            val m = mean()
            return (pnlSqSum / n.toDouble() - m * m).coerceAtLeast(0.0)
        }
    }

    private val stats = ConcurrentHashMap<String, Stat>()

    private fun key(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
    ): String = lane.trim().uppercase() + "|" + state.name + "|" + policy.name

    fun recordOutcome(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        val k = key(lane, state, policy)
        val s = stats.computeIfAbsent(k) { Stat() }
        synchronized(s) {
            s.n += 1
            if (realizedReturnPct > 0.0) s.wins += 1
            s.pnlSum += realizedReturnPct
            s.pnlSqSum += realizedReturnPct * realizedReturnPct
        }
        try {
            PipelineHealthCollector.labelInc("SUPER_POLICY_BANDIT_OUTCOME_7644")
            PipelineHealthCollector.labelInc("SUPER_POLICY_BANDIT_OUTCOME_7644_" + policy.name)
        } catch (_: Throwable) {}
    }

    /**
     * Bounded contextual utility prior.
     *
     * Mean realised PnL and win-rate supply exploitation. A small uncertainty
     * bonus preserves exploration while sample count is low. Nothing here can
     * dominate the world/critic/CVaR tree because output is clamped +/-8.
     */
    fun policyPrior(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
    ): Double {
        val s = stats[key(lane, state, policy)] ?: return 0.0
        return synchronized(s) {
            if (s.n < 3L) return@synchronized 0.0
            val meanTerm = (s.mean() * 0.18).coerceIn(-5.0, 5.0)
            val wrTerm = ((s.wr() - 0.5) * 6.0).coerceIn(-3.0, 3.0)
            val uncertaintyBonus = if (s.n < 20L) {
                val contextTotal = SuperPolicyTree7638.Policy.entries.sumOf { p ->
                    stats[key(lane, state, p)]?.n ?: 0L
                }.coerceAtLeast(1L)
                (sqrt(2.0 * ln(contextTotal.toDouble().coerceAtLeast(2.0)) / s.n.toDouble()) * 1.5)
                    .coerceIn(0.0, 2.0)
            } else 0.0
            (meanTerm + wrTerm + uncertaintyBonus).coerceIn(-8.0, 8.0)
        }
    }

    fun exportState(): String {
        val arr = JSONArray()
        stats.forEach { (k, s) ->
            synchronized(s) {
                arr.put(
                    JSONObject()
                        .put("k", k)
                        .put("n", s.n)
                        .put("wins", s.wins)
                        .put("pnlSum", s.pnlSum)
                        .put("pnlSqSum", s.pnlSqSum)
                )
            }
        }
        return JSONObject().put("version", 7644).put("rows", arr).toString()
    }

    fun importState(raw: String) {
        if (raw.isBlank()) return
        try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k", "")
                if (k.isBlank()) continue
                stats[k] = Stat(
                    n = o.optLong("n", 0L),
                    wins = o.optLong("wins", 0L),
                    pnlSum = o.optDouble("pnlSum", 0.0),
                    pnlSqSum = o.optDouble("pnlSqSum", 0.0),
                )
            }
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val top = stats.entries
            .sortedByDescending { it.value.n }
            .take(10)
            .joinToString(" | ") { e ->
                synchronized(e.value) {
                    String.format(
                        java.util.Locale.US,
                        "%s n=%d wr=%.0f%% mean=%+.1f",
                        e.key,
                        e.value.n,
                        e.value.wr() * 100.0,
                        e.value.mean(),
                    )
                }
            }
        return "SUPER_POLICY_BANDIT_7644 rows=" + stats.size + " top=[" + top + "]"
    }

    internal fun resetForTest() { stats.clear() }
}
