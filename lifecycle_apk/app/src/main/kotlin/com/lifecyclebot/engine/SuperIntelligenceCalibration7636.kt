
package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7636 - causal calibration ledger for the Super Intelligence stack.
 *
 * Decision-time world/critic/plan state is first keyed by mint+owner lane, then
 * bound to the canonical positionId at OPEN. Terminal grading is position-bound,
 * so a later same-mint evaluation can never steal credit from the trade that
 * actually opened.
 */
object SuperIntelligenceCalibration7636 {
    data class DecisionStamp(
        val mint: String,
        val lane: String,
        val world: SuperWorldModel7634.Snapshot,
        val planAction: SuperIntelligencePlanner7633.Action,
        val criticFragility: Double,
        val criticVerdict: String,
        val treePolicy: SuperPolicyTree7638.Policy,
        val treeConfidence: Double,
        val arbiterDominant: String,
        val arbiterMetaConfidence: Double,
        val atMs: Long,
    )

    data class HorizonStats(
        var n: Long = 0L,
        var brierSum: Double = 0.0,
        var absEvErrorSum: Double = 0.0,
        var directionCorrect: Long = 0L,
        var realizedSum: Double = 0.0,
    ) {
        fun brier(): Double = if (n > 0) brierSum / n else 0.0
        fun mae(): Double = if (n > 0) absEvErrorSum / n else 0.0
        fun directionAccuracy(): Double =
            if (n > 0) directionCorrect.toDouble() / n.toDouble() else 0.0
        fun meanRealized(): Double = if (n > 0) realizedSum / n else 0.0
    }

    data class StateStats(
        var n: Long = 0L,
        var wins: Long = 0L,
        var realizedSum: Double = 0.0,
    )

    private val pending = ConcurrentHashMap<String, DecisionStamp>()
    private val byPosition = ConcurrentHashMap<String, DecisionStamp>()
    private val settled = ConcurrentHashMap.newKeySet<String>()
    private val horizonStats = ConcurrentHashMap<SuperWorldModel7634.Horizon, HorizonStats>()
    private val stateStats = ConcurrentHashMap<SuperWorldModel7634.LatentState, StateStats>()
    private val failureModes7640 = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    private fun key(mint: String, lane: String): String =
        lane.trim().uppercase() + "|" + mint.trim()

    fun recordDecision(
        mint: String,
        lane: String,
        world: SuperWorldModel7634.Snapshot,
        plan: SuperIntelligencePlanner7633.Plan,
        critic: SuperAdversarialCritic7635.Review,
        tree: SuperPolicyTree7638.Result,
        arbiter: SuperReasoningArbiter7639.Decision,
    ) {
        if (mint.isBlank()) return
        val laneKey = lane.trim().uppercase().ifBlank { world.lane }
        pending[key(mint, laneKey)] = DecisionStamp(
            mint = mint,
            lane = laneKey,
            world = world,
            planAction = plan.chosen,
            criticFragility = critic.thesisFragility,
            criticVerdict = critic.verdict,
            treePolicy = tree.bestPolicy,
            treeConfidence = tree.confidence,
            arbiterDominant = arbiter.dominant,
            arbiterMetaConfidence = arbiter.metaConfidence,
            atMs = System.currentTimeMillis(),
        )
        try { PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_DECISION_STAMPED_7636") } catch (_: Throwable) {}
        if (pending.size > 4096) {
            val cutoff = System.currentTimeMillis() - 10L * 60L * 1000L
            pending.entries.removeIf { it.value.atMs < cutoff }
        }
    }

    fun bindPosition(positionId: String, mint: String, lane: String): Boolean {
        if (positionId.isBlank() || mint.isBlank()) return false
        val laneKey = lane.trim().uppercase()
        val exact = pending.remove(key(mint, laneKey))
        val fallback = exact ?: pending.entries
            .filter { it.value.mint == mint }
            .maxByOrNull { it.value.atMs }
            ?.let { e ->
                pending.remove(e.key)
                e.value
            }
        if (fallback == null) {
            try { PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_BIND_MISSING_7636") } catch (_: Throwable) {}
            return false
        }
        byPosition[positionId] = fallback
        try {
            PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_POSITION_BOUND_7636")
            PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_POSITION_BOUND_7636_" + fallback.world.latentState.name)
        } catch (_: Throwable) {}
        return true
    }

    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!env.terminal || env.positionId.isBlank()) return true
        if (!settled.add(env.positionId)) return true
        val stamp = byPosition.remove(env.positionId) ?: run {
            try { PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_OUTCOME_NO_BOUND_PREDICTION_7636") } catch (_: Throwable) {}
            return true
        }

        val holdSec = (env.holdingTimeMs.coerceAtLeast(0L) / 1000L).toInt()
        val nearest = SuperWorldModel7634.Horizon.entries.minByOrNull {
            abs(it.seconds - holdSec)
        } ?: SuperWorldModel7634.Horizon.TACTICAL
        val forecast = stamp.world.forHorizon(nearest) ?: return true

        val y = if (env.realizedReturnPct > 0.0) 1.0 else 0.0
        val brier = (forecast.pWin - y) * (forecast.pWin - y)
        val evError = abs(forecast.expectedPnlPct - env.realizedReturnPct)
        val directionCorrect =
            (forecast.expectedPnlPct > 0.0 && env.realizedReturnPct > 0.0) ||
                (forecast.expectedPnlPct <= 0.0 && env.realizedReturnPct <= 0.0)

        val hs = horizonStats.computeIfAbsent(nearest) { HorizonStats() }
        synchronized(hs) {
            hs.n += 1
            hs.brierSum += brier
            hs.absEvErrorSum += evError
            if (directionCorrect) hs.directionCorrect += 1
            hs.realizedSum += env.realizedReturnPct
        }

        val ss = stateStats.computeIfAbsent(stamp.world.latentState) { StateStats() }
        synchronized(ss) {
            ss.n += 1
            if (env.realizedReturnPct > 0.0) ss.wins += 1
            ss.realizedSum += env.realizedReturnPct
        }

        val failureMode7640 = when {
            directionCorrect -> "REASONING_OK"
            stamp.world.latentState == SuperWorldModel7634.LatentState.ACCELERATING &&
                env.realizedReturnPct <= 0.0 -> "LATENT_STATE_OVERBULLISH"
            stamp.world.latentState == SuperWorldModel7634.LatentState.DISTRIBUTING &&
                env.realizedReturnPct > 0.0 -> "LATENT_STATE_OVERBEARISH"
            forecast.pWin >= 0.65 && env.realizedReturnPct <= 0.0 -> "HORIZON_PROBABILITY_OVERCONFIDENT"
            forecast.pWin <= 0.35 && env.realizedReturnPct > 0.0 -> "HORIZON_PROBABILITY_UNDERCONFIDENT"
            stamp.criticFragility < 0.35 && env.realizedReturnPct <= 0.0 -> "CRITIC_TOO_WEAK"
            stamp.criticFragility > 0.70 && env.realizedReturnPct > 0.0 -> "CRITIC_TOO_PESSIMISTIC"
            stamp.treePolicy == SuperPolicyTree7638.Policy.CONVICTION_RUNNER &&
                env.realizedReturnPct <= 0.0 -> "TREE_CONVICTION_POLICY_WRONG"
            stamp.treePolicy == SuperPolicyTree7638.Policy.WAIT_REASSESS &&
                env.realizedReturnPct > 0.0 -> "TREE_WAIT_POLICY_TOO_TIMID"
            stamp.arbiterDominant == "MEMORY" && env.realizedReturnPct <= 0.0 -> "MEMORY_OVERTRUST"
            stamp.arbiterDominant == "TREE" && env.realizedReturnPct <= 0.0 -> "TREE_OVERTRUST"
            stamp.arbiterDominant == "CRITIC" && env.realizedReturnPct > 0.0 -> "CRITIC_OVERTRUST"
            else -> "UNCLASSIFIED_REASONING_MISS"
        }
        failureModes7640.computeIfAbsent(failureMode7640) {
            java.util.concurrent.atomic.AtomicLong(0L)
        }.incrementAndGet()

        try {
            PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_OUTCOME_GRADED_7636")
            PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_HORIZON_GRADED_7636_" + nearest.name)
            PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_STATE_GRADED_7636_" + stamp.world.latentState.name)
            if (directionCorrect) PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_DIRECTION_CORRECT_7636")
            else PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_DIRECTION_WRONG_7636")
            ForensicLogger.lifecycle(
                "SUPER_INTELLIGENCE_OUTCOME_GRADED_7636",
                "positionId=" + env.positionId.take(24) +
                    " lane=" + stamp.lane +
                    " horizon=" + nearest.name +
                    " state=" + stamp.world.latentState.name +
                    " plan=" + stamp.planAction.name +
                    " critic=" + stamp.criticVerdict +
                    " tree=" + stamp.treePolicy.name +
                    " arbiter=" + stamp.arbiterDominant +
                    " failureMode=" + failureMode7640 +
                    " predP=" + String.format(java.util.Locale.US, "%.3f", forecast.pWin) +
                    " predE=" + String.format(java.util.Locale.US, "%+.2f", forecast.expectedPnlPct) +
                    " actual=" + String.format(java.util.Locale.US, "%+.2f", env.realizedReturnPct) +
                    " brier=" + String.format(java.util.Locale.US, "%.3f", brier),
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.7637 - calibration-derived trust for each planning horizon.
     *
     * Neutral until n>=8. Then Brier calibration + directional accuracy earn
     * a bounded trust multiplier. This is model trust, not execution authority.
     */
    fun horizonReliability(h: SuperWorldModel7634.Horizon): Double {
        val s = horizonStats[h] ?: return 1.0
        return synchronized(s) {
            if (s.n < 8L) return@synchronized 1.0
            val brierQuality = (1.0 - (s.brier() / 0.35)).coerceIn(0.0, 1.0)
            val dirQuality = s.directionAccuracy().coerceIn(0.0, 1.0)
            val raw = 0.55 + brierQuality * 0.35 + dirQuality * 0.30
            raw.coerceIn(0.60, 1.20)
        }
    }

    fun exportState(): String {
        val root = JSONObject().put("version", 7637)
        val hs = JSONArray()
        SuperWorldModel7634.Horizon.entries.forEach { h ->
            val s = horizonStats[h] ?: return@forEach
            synchronized(s) {
                hs.put(
                    JSONObject()
                        .put("h", h.name)
                        .put("n", s.n)
                        .put("brierSum", s.brierSum)
                        .put("absEvErrorSum", s.absEvErrorSum)
                        .put("directionCorrect", s.directionCorrect)
                        .put("realizedSum", s.realizedSum)
                )
            }
        }
        root.put("horizons", hs)

        val ss = JSONArray()
        stateStats.forEach { (state, s) ->
            synchronized(s) {
                ss.put(
                    JSONObject()
                        .put("state", state.name)
                        .put("n", s.n)
                        .put("wins", s.wins)
                        .put("realizedSum", s.realizedSum)
                )
            }
        }
        root.put("states", ss)
        return root.toString()
    }

    fun importState(raw: String) {
        if (raw.isBlank()) return
        try {
            val root = JSONObject(raw)
            val hs = root.optJSONArray("horizons") ?: JSONArray()
            for (i in 0 until hs.length()) {
                val o = hs.optJSONObject(i) ?: continue
                val h = try { SuperWorldModel7634.Horizon.valueOf(o.optString("h")) } catch (_: Throwable) { continue }
                horizonStats[h] = HorizonStats(
                    n = o.optLong("n", 0L),
                    brierSum = o.optDouble("brierSum", 0.0),
                    absEvErrorSum = o.optDouble("absEvErrorSum", 0.0),
                    directionCorrect = o.optLong("directionCorrect", 0L),
                    realizedSum = o.optDouble("realizedSum", 0.0),
                )
            }

            val ss = root.optJSONArray("states") ?: JSONArray()
            for (i in 0 until ss.length()) {
                val o = ss.optJSONObject(i) ?: continue
                val state = try { SuperWorldModel7634.LatentState.valueOf(o.optString("state")) } catch (_: Throwable) { continue }
                stateStats[state] = StateStats(
                    n = o.optLong("n", 0L),
                    wins = o.optLong("wins", 0L),
                    realizedSum = o.optDouble("realizedSum", 0.0),
                )
            }
            try { PipelineHealthCollector.labelInc("SUPER_INTELLIGENCE_CALIBRATION_RESTORED_7637") } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val horizons = SuperWorldModel7634.Horizon.entries.joinToString(" | ") { h ->
            val s = horizonStats[h]
            if (s == null || s.n == 0L) h.name + ":n=0"
            else synchronized(s) {
                String.format(
                    java.util.Locale.US,
                    "%s:n=%d brier=%.3f mae=%.1f dir=%.0f%% mean=%+.1f",
                    h.name,
                    s.n,
                    s.brier(),
                    s.mae(),
                    s.directionAccuracy() * 100.0,
                    s.meanRealized(),
                )
            }
        }
        val states = stateStats.entries
            .sortedByDescending { it.value.n }
            .take(6)
            .joinToString(" | ") { e ->
                synchronized(e.value) {
                    val wr = if (e.value.n > 0) e.value.wins * 100.0 / e.value.n else 0.0
                    val mean = if (e.value.n > 0) e.value.realizedSum / e.value.n else 0.0
                    String.format(java.util.Locale.US, "%s:n=%d wr=%.0f%% mean=%+.1f", e.key.name, e.value.n, wr, mean)
                }
            }
        val failures = failureModes7640.entries
            .sortedByDescending { it.value.get() }
            .take(8)
            .joinToString(" | ") { it.key + "=" + it.value.get() }
        return "SUPER_INTELLIGENCE_CALIBRATION_7636 pending=" + pending.size +
            " bound=" + byPosition.size +
            " horizons=[" + horizons + "] states=[" + states + "] failures7640=[" + failures + "]"
    }

    internal fun resetForTest() {
        pending.clear()
        byPosition.clear()
        settled.clear()
        horizonStats.clear()
        stateStats.clear()
        failureModes7640.clear()
    }
}
