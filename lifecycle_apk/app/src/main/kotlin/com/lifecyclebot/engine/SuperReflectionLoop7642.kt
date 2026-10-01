
package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7642 - autonomous reasoning reflection loop.
 *
 * Repeated position-bound reasoning failures generate background-only,
 * critic-reviewed hypotheses for AsyncStrategyLab. Nothing here changes live
 * configuration, execution, thresholds or capital directly.
 */
object SuperReflectionLoop7642 {
    private const val MIN_REPEAT = 3L
    private const val COOLDOWN_MS = 30L * 60L * 1000L

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val lastProposalMs = ConcurrentHashMap<String, Long>()

    fun observe(
        lane: String,
        failureMode: String,
        realizedReturnPct: Double,
        latentState: SuperWorldModel7634.LatentState,
        horizon: SuperWorldModel7634.Horizon,
        criticVerdict: String,
        treePolicy: SuperPolicyTree7638.Policy,
        arbiterDominant: String,
    ) {
        if (failureMode == "REASONING_OK" || failureMode.isBlank()) return
        val laneKey = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val key = laneKey + "|" + failureMode
        val n = counts.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        if (n < MIN_REPEAT) return

        val now = System.currentTimeMillis()
        val last = lastProposalMs[key] ?: 0L
        if (now - last < COOLDOWN_MS) return
        lastProposalMs[key] = now

        val repair = repairShape(failureMode)
        val context = String.format(
            java.util.Locale.US,
            "lane=%s failure=%s repeats=%d realized=%+.2f state=%s horizon=%s critic=%s tree=%s arbiter=%s",
            laneKey,
            failureMode,
            n,
            realizedReturnPct,
            latentState.name,
            horizon.name,
            criticVerdict,
            treePolicy.name,
            arbiterDominant,
        )

        val launched = ChokeReliefBus.launch("SUPER_REFLECTION_7642", laneKey + failureMode.take(12)) {
            val accepted = MultiAgentCriticStack.reviewAndSubmit(
                lane = laneKey,
                closedTradeSummary = context,
                candidateProposal = repair.proposal,
                expectedMetric = repair.metric,
                rollbackCondition = repair.rollback,
                sourceTag = "BACKGROUND_SUPER_REFLECTION_7642",
            )
            try {
                PipelineHealthCollector.labelInc(
                    if (accepted) "SUPER_REFLECTION_HYPOTHESIS_ACCEPTED_7642"
                    else "SUPER_REFLECTION_HYPOTHESIS_REJECTED_7642"
                )
                PipelineHealthCollector.labelInc("SUPER_REFLECTION_FAILURE_" + failureMode.take(48))
                ForensicLogger.lifecycle(
                    "SUPER_REFLECTION_7642",
                    context + " accepted=" + accepted + " proposal=" + repair.proposal.take(240),
                )
            } catch (_: Throwable) {}
        }
        if (!launched) lastProposalMs.remove(key)
    }

    private data class Repair(
        val proposal: String,
        val metric: String,
        val rollback: String,
    )

    private fun repairShape(failure: String): Repair {
        return when {
            failure.contains("MEMORY") -> Repair(
                proposal = "bounded soft test: reduce episodic-memory weighting for repeated-miss contexts and require stronger exact-strategy sample confidence before memory dominates; preserve world/critic/safety authority",
                metric = "reduce memory-attributed direction misses while preserving or improving realized mean return",
                rollback = "restore prior memory weighting if exact-strategy direction accuracy improves or realized mean return deteriorates",
            )
            failure.contains("TREE") -> Repair(
                proposal = "bounded soft test: increase policy-tree margin required for conviction branches and prefer reduced-then-scale when branch utilities are close; no hard veto or zero-size rule",
                metric = "reduce wrong tree-policy selections and improve plan-vs-realized direction accuracy",
                rollback = "restore prior tree margin if correct tree selections or realized expectancy decline",
            )
            failure.contains("CRITIC") -> Repair(
                proposal = "bounded soft test: recalibrate adversarial critic penalty for this lane using exact outcome error; keep safety facts independent and never convert critic opinion into hard veto",
                metric = "reduce critic-too-weak and critic-too-pessimistic classifications",
                rollback = "restore prior critic weighting if Brier or realized expectancy worsens",
            )
            failure.contains("HORIZON") || failure.contains("LATENT_STATE") -> Repair(
                proposal = "bounded soft test: increase world-model uncertainty for the failing horizon/state and require stronger cross-horizon coherence before conviction; preserve base/reduced policy availability",
                metric = "improve horizon Brier score, directional accuracy and latent-state realized expectancy",
                rollback = "restore prior uncertainty shaping when horizon calibration returns healthy",
            )
            else -> Repair(
                proposal = "bounded soft diagnostic hypothesis: lower meta-confidence for repeated unclassified reasoning misses and collect additional exact position-bound evidence before changing authority",
                metric = "reduce unclassified reasoning misses with no loss of realized expectancy",
                rollback = "remove diagnostic dampening when failure rate falls below baseline",
            )
        }
    }

    fun statusLine(): String {
        val top = counts.entries.sortedByDescending { it.value.get() }.take(8)
            .joinToString(" | ") { it.key + "=" + it.value.get() }
        return "SUPER_REFLECTION_7642 patterns=[" + top + "] proposals=" + lastProposalMs.size
    }

    internal fun resetForTest() {
        counts.clear()
        lastProposalMs.clear()
    }
}
