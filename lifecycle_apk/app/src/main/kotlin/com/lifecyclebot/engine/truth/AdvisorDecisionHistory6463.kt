package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6463 §P1 — ADVISOR DECISION HISTORY (last 20 with brain-vote breakdown).
 *
 * OPERATOR MANDATE (Feb 2026):
 *   "Advisor UI Timeline: Show the last 20 advisor decisions with
 *    brain-vote breakdown on the Pipeline Health screen so the operator
 *    can spot the pattern"
 *
 * DESIGN
 * ──────
 * Ring buffer of the last CAP=20 advisor decisions, each carrying:
 *   - timestamp
 *   - key (parameter)
 *   - proposed delta
 *   - severity (high/med/low)
 *   - source (rules / rules+llm / llm)
 *   - action taken (auto_applied / queued_inbox / low_agreement / cooldown_skip)
 *   - per-brain votes (name / agree / weight)
 *   - brainAgreement 0..1
 *   - resolved old/new values (populated when auto_applied)
 *
 * `formatForPipelineDump()` returns a readable multi-line string the
 * PipelineHealth UI already pipes into a scrolling text view — no
 * new UI layout files needed for the first release.
 */
object AdvisorDecisionHistory6463 {

    private const val CAP = 20

    enum class Action { AUTO_APPLIED, QUEUED_INBOX, LOW_AGREEMENT, COOLDOWN_SKIP, APPLY_NOOP, APPLY_FAILED, REVERTED }

    data class BrainVote(val brain: String, val agree: Boolean, val weight: Double)

    data class Decision(
        val atMs: Long,
        val key: String,
        val delta: Double,
        val severity: String,
        val source: String,
        val action: Action,
        val brainAgreement: Double,
        val votes: List<BrainVote>,
        val reason: String,
        val oldValue: Double = Double.NaN,
        val newValue: Double = Double.NaN,
    )

    private val ring = ConcurrentLinkedDeque<Decision>()
    private val recorded = AtomicLong(0L)

    fun record(d: Decision) {
        ring.addFirst(d)
        while (ring.size > CAP) ring.pollLast()
        recorded.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "ADVISOR_DECISION_RECORDED_6463",
                "key=${d.key} delta=${"%.4f".format(d.delta)} sev=${d.severity} " +
                    "action=${d.action.name} agree=${"%.2f".format(d.brainAgreement)} src=${d.source}",
            )
            PipelineHealthCollector.labelInc("ADVISOR_DECISION_${d.action.name}_6463")
        } catch (_: Throwable) {}
    }

    fun recent(limit: Int = CAP): List<Decision> = ring.take(limit)

    fun formatForPipelineDump(): String {
        if (ring.isEmpty()) return "AdvisorTimeline6463: (no decisions yet)"
        val sb = StringBuilder("AdvisorTimeline6463 (last ${ring.size}):\n")
        for (d in ring.take(CAP)) {
            val ageSec = (System.currentTimeMillis() - d.atMs) / 1000L
            val valueTag = if (d.newValue.isFinite()) " ${"%.4f".format(d.oldValue)}→${"%.4f".format(d.newValue)}" else ""
            val votesTag = if (d.votes.isEmpty()) "" else
                " votes=" + d.votes.joinToString(",") { "${it.brain.take(6)}:${if (it.agree) "+" else "-"}${"%.1f".format(it.weight)}" }
            sb.append("  ${ageSec}s ago ${d.action.name.padEnd(15)} ${d.key.padEnd(24)} Δ${"%+.4f".format(d.delta)} ")
              .append("sev=${d.severity} agree=${"%.2f".format(d.brainAgreement)} src=${d.source}${valueTag}${votesTag}\n")
              .append("    reason: ${d.reason.take(120)}\n")
        }
        return sb.toString()
    }

    fun statusLine(): String = "recorded=${recorded.get()} inRing=${ring.size}"

    internal fun resetForTest() { ring.clear(); recorded.set(0L) }
}
