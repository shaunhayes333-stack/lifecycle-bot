
package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7647 - propensity-aware policy lift estimator.
 *
 * This does not claim randomized causal identification. The policy tree is
 * deterministic, so observational outcomes are selection-biased. We therefore
 * persist the tree's softmax preference (propensity) and use bounded inverse-
 * propensity weighting to estimate whether a policy adds value relative to the
 * same lane + latent-state baseline.
 *
 * Output is a bounded advisory prior only. No execution or veto authority.
 */
object SuperCausalPolicyEvaluator7647 {
    data class WeightedStat(
        var n: Long = 0L,
        var weightSum: Double = 0.0,
        var weightedRewardSum: Double = 0.0,
    ) {
        fun mean(): Double {
            return if (weightSum > 0.0) weightedRewardSum / weightSum else 0.0
        }
    }

    data class BaselineStat(
        var n: Long = 0L,
        var rewardSum: Double = 0.0,
    ) {
        fun mean(): Double {
            return if (n > 0L) rewardSum / n.toDouble() else 0.0
        }
    }

    private val policyStats = ConcurrentHashMap<String, WeightedStat>()
    private val baselines = ConcurrentHashMap<String, BaselineStat>()

    private fun contextKey(
        lane: String,
        state: SuperWorldModel7634.LatentState,
    ): String {
        return lane.trim().uppercase() + "|" + state.name
    }

    private fun policyKey(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
    ): String {
        return contextKey(lane, state) + "|" + policy.name
    }

    fun recordOutcome(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
        selectionPropensity: Double,
        realizedReturnPct: Double,
    ) {
        if (!realizedReturnPct.isFinite()) return
        val p = selectionPropensity.takeIf { it.isFinite() }?.coerceIn(0.02, 1.0) ?: 1.0

        // Clip inverse propensity so one low-probability observation cannot
        // dominate the estimator.
        val ipw = (1.0 / p).coerceIn(1.0, 5.0)

        val ps = policyStats.computeIfAbsent(policyKey(lane, state, policy)) { WeightedStat() }
        synchronized(ps) {
            ps.n += 1L
            ps.weightSum += ipw
            ps.weightedRewardSum += realizedReturnPct * ipw
        }

        val bs = baselines.computeIfAbsent(contextKey(lane, state)) { BaselineStat() }
        synchronized(bs) {
            bs.n += 1L
            bs.rewardSum += realizedReturnPct
        }

        try {
            PipelineHealthCollector.labelInc("SUPER_CAUSAL_POLICY_OUTCOME_7647")
            PipelineHealthCollector.labelInc("SUPER_CAUSAL_POLICY_OUTCOME_7647_" + policy.name)
        } catch (_: Throwable) {}
    }

    /**
     * Bounded propensity-aware estimated lift over the same context baseline.
     * Neutral until both the policy and context have enough exact outcomes.
     */
    fun policyLift(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
    ): Double {
        val ps = policyStats[policyKey(lane, state, policy)] ?: return 0.0
        val bs = baselines[contextKey(lane, state)] ?: return 0.0
        return synchronized(ps) {
            synchronized(bs) {
                if (ps.n < 5L || bs.n < 10L) return@synchronized 0.0
                val liftPct = ps.mean() - bs.mean()
                val reliability = (ps.n.toDouble() / (ps.n + 12.0)).coerceIn(0.0, 1.0)
                (liftPct * 0.16 * reliability).coerceIn(-6.0, 6.0)
            }
        }
    }

    fun exportState(): String {
        val p = JSONArray()
        policyStats.forEach { (k, s) ->
            synchronized(s) {
                p.put(
                    JSONObject()
                        .put("k", k)
                        .put("n", s.n)
                        .put("weightSum", s.weightSum)
                        .put("weightedRewardSum", s.weightedRewardSum)
                )
            }
        }
        val b = JSONArray()
        baselines.forEach { (k, s) ->
            synchronized(s) {
                b.put(
                    JSONObject()
                        .put("k", k)
                        .put("n", s.n)
                        .put("rewardSum", s.rewardSum)
                )
            }
        }
        return JSONObject()
            .put("version", 7647)
            .put("policies", p)
            .put("baselines", b)
            .toString()
    }

    fun importState(raw: String) {
        if (raw.isBlank()) return
        try {
            val root = JSONObject(raw)
            val p = root.optJSONArray("policies") ?: JSONArray()
            for (i in 0 until p.length()) {
                val o = p.optJSONObject(i) ?: continue
                val k = o.optString("k", "")
                if (k.isBlank()) continue
                policyStats[k] = WeightedStat(
                    n = o.optLong("n", 0L),
                    weightSum = o.optDouble("weightSum", 0.0),
                    weightedRewardSum = o.optDouble("weightedRewardSum", 0.0),
                )
            }
            val b = root.optJSONArray("baselines") ?: JSONArray()
            for (i in 0 until b.length()) {
                val o = b.optJSONObject(i) ?: continue
                val k = o.optString("k", "")
                if (k.isBlank()) continue
                baselines[k] = BaselineStat(
                    n = o.optLong("n", 0L),
                    rewardSum = o.optDouble("rewardSum", 0.0),
                )
            }
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val top = policyStats.entries
            .sortedByDescending { it.value.n }
            .take(8)
            .joinToString(" | ") { e ->
                synchronized(e.value) {
                    String.format(
                        java.util.Locale.US,
                        "%s n=%d ipwMean=%+.1f",
                        e.key,
                        e.value.n,
                        e.value.mean(),
                    )
                }
            }
        return "SUPER_CAUSAL_POLICY_7647 policyRows=" + policyStats.size +
            " contexts=" + baselines.size + " top=[" + top + "]"
    }

    internal fun resetForTest() {
        policyStats.clear()
        baselines.clear()
    }
}
