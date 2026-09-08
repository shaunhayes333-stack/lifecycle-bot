package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6629 §8 SPECIALIST_PROPOSAL_ARBITER.
 *
 * Specialists may all propose for the same mint/version, but only one canonical
 * executable BUY may exist. Since V5.0.6653/6679, execution ownership is sealed
 * by FDG + LaneExecutionCoordinator; this object is retained for proposal and
 * counterfactual-learning telemetry plus legacy unit coverage. It MUST NOT become
 * a second post-FDG execution authority.
 *
 * V5.0.6699: because production no longer calls elect6629(), old contests were
 * never retired and the health dump grew to hundreds of liveContests with zero
 * elections. Contests are now thread-safe and TTL-bounded. Legacy elect6629()
 * remains deterministic for historical callers/tests, but the status line states
 * that sealed FDG ownership is authoritative.
 */
object SpecialistProposalArbiter6629 {

    data class Proposal6629(
        val mint: String,
        val candidateVersion: Long,
        val lane: String,
        val score: Double,
        val confidence: Double,
        val lanePriority: Int,
        val reason: String,
        val submittedAtMs: Long = System.currentTimeMillis(),
    )

    data class Election6629(
        val mint: String,
        val candidateVersion: Long,
        val elected: Proposal6629?,
        val runnersUp: List<Proposal6629>,
        val decidedAtMs: Long = System.currentTimeMillis(),
    )

    private data class ContestKey(val mint: String, val candidateVersion: Long)

    private val contests = ConcurrentHashMap<ContestKey, CopyOnWriteArrayList<Proposal6629>>()
    private val decisions = ConcurrentHashMap<ContestKey, Election6629>()

    private val proposalsAccepted = AtomicLong(0L)
    private val proposalsDuplicateLane = AtomicLong(0L)
    private val electionsCompleted = AtomicLong(0L)
    private val duplicateBuysSuppressed = AtomicLong(0L)
    private val submissionsSeen6699 = AtomicLong(0L)
    private val staleContestsPruned6699 = AtomicLong(0L)
    private val staleDecisionsPruned6699 = AtomicLong(0L)

    private const val CONTEST_TTL_MS_6699 = 90_000L
    private const val DECISION_TTL_MS_6699 = 10 * 60_000L

    private fun pruneStale6699(now: Long = System.currentTimeMillis()) {
        var contestPruned = 0L
        contests.entries.removeIf { (_, proposals) ->
            val newest = proposals.maxOfOrNull { it.submittedAtMs } ?: 0L
            val stale = newest <= 0L || now - newest > CONTEST_TTL_MS_6699
            if (stale) contestPruned++
            stale
        }
        if (contestPruned > 0L) {
            staleContestsPruned6699.addAndGet(contestPruned)
            try {
                repeat(contestPruned.coerceAtMost(1_000L).toInt()) {
                    PipelineHealthCollector.labelInc("SPECIALIST_STALE_CONTEST_PRUNED_6699")
                }
            } catch (_: Throwable) {}
        }

        var decisionPruned = 0L
        decisions.entries.removeIf { (_, decision) ->
            val stale = now - decision.decidedAtMs > DECISION_TTL_MS_6699
            if (stale) decisionPruned++
            stale
        }
        if (decisionPruned > 0L) staleDecisionsPruned6699.addAndGet(decisionPruned)
    }

    /** Record/replace one specialist proposal. Execution authority remains FDG-sealed. */
    fun submitProposal6629(proposal: Proposal6629): Boolean {
        if (proposal.mint.isBlank() || proposal.lane.isBlank()) return false
        val seen = submissionsSeen6699.incrementAndGet()
        if ((seen and 63L) == 0L || contests.size > 256) pruneStale6699()

        val key = ContestKey(proposal.mint, proposal.candidateVersion)
        var first = true
        contests.compute(key) { _, existing ->
            val list = existing ?: CopyOnWriteArrayList()
            val already = list.indexOfFirst { it.lane == proposal.lane }
            if (already >= 0) {
                first = false
                list[already] = proposal
                proposalsDuplicateLane.incrementAndGet()
            } else {
                list.add(proposal)
                proposalsAccepted.incrementAndGet()
            }
            list
        }
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_ACCEPTED_6629")
            PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_ACCEPTED_${proposal.lane.uppercase()}_6629")
        } catch (_: Throwable) {}
        return first
    }

    /**
     * Legacy deterministic election surface. Production execution must not call
     * this after FDG seal; LaneExecutionCoordinator/ExecutionDecisionSnapshot6510
     * are the executable owner authority.
     */
    fun elect6629(mint: String, candidateVersion: Long): Election6629 {
        pruneStale6699()
        val key = ContestKey(mint, candidateVersion)
        decisions[key]?.let { return it }
        val list = contests[key]?.toList().orEmpty()
        if (list.isEmpty()) return Election6629(mint, candidateVersion, null, emptyList())

        val ranked = list.sortedWith(
            compareByDescending<Proposal6629> { it.confidence }
                .thenByDescending { it.score }
                .thenBy { it.lanePriority }
                .thenBy { it.submittedAtMs },
        )
        val elected = ranked.first()
        val runners = ranked.drop(1)
        val decision = Election6629(mint, candidateVersion, elected, runners)
        decisions[key] = decision
        electionsCompleted.incrementAndGet()
        if (runners.isNotEmpty()) duplicateBuysSuppressed.addAndGet(runners.size.toLong())
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_ELECTION_COMPLETED_6629")
            PipelineHealthCollector.labelInc("SPECIALIST_ELECTION_WON_${elected.lane.uppercase()}_6629")
            for (r in runners) {
                PipelineHealthCollector.labelInc("SPECIALIST_ELECTION_RUNNER_UP_${r.lane.uppercase()}_6629")
                PipelineHealthCollector.labelInc("SPECIALIST_DUPLICATE_BUY_SUPPRESSED_6629")
            }
            ForensicLogger.lifecycle(
                "SPECIALIST_ELECTION_COMPLETED_6629",
                "mint=${mint.take(10)} cv=$candidateVersion elected=${elected.lane} " +
                    "confidence=${"%.2f".format(elected.confidence)} score=${"%.2f".format(elected.score)} " +
                    "runnersUp=${runners.size} runnerLanes=${runners.joinToString(",") { it.lane }} " +
                    "authority=LEGACY_TELEMETRY_ONLY_FDG_SEALED_EXECUTION",
            )
        } catch (_: Throwable) {}
        return decision
    }

    fun currentDecision6629(mint: String, candidateVersion: Long): Election6629? {
        pruneStale6699()
        return decisions[ContestKey(mint, candidateVersion)]
    }

    fun retireContest6629(mint: String, candidateVersion: Long) {
        contests.remove(ContestKey(mint, candidateVersion))
    }

    fun statusLine6629(): String {
        pruneStale6699()
        return "mode=FDG_SEALED_AUTH_TELEMETRY_ONLY accepted=${proposalsAccepted.get()} dupLane=${proposalsDuplicateLane.get()} " +
            "elections=${electionsCompleted.get()} dupBuysSuppressed=${duplicateBuysSuppressed.get()} " +
            "stalePruned=${staleContestsPruned6699.get()} liveContests=${contests.size} decisions=${decisions.size}"
    }

    internal fun resetForTest() {
        contests.clear()
        decisions.clear()
        proposalsAccepted.set(0L)
        proposalsDuplicateLane.set(0L)
        electionsCompleted.set(0L)
        duplicateBuysSuppressed.set(0L)
        submissionsSeen6699.set(0L)
        staleContestsPruned6699.set(0L)
        staleDecisionsPruned6699.set(0L)
    }
}
