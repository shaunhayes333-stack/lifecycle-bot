package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6629 §8 SPECIALIST_PROPOSAL_ARBITER.
 *
 * Operator (Feb 2026):
 *   > "Current qualified but functionally silent:
 *   >    QUALITY      490 qualified / 0 owner
 *   >    DIP_HUNTER    55 qualified / 0 owner
 *   >    MANIPULATED  227 qualified / 0 owner
 *   >    TREASURY     147 qualified / 0 owner
 *   >    CASHGEN      157 qualified / 0 owner
 *   >
 *   > Do NOT give every specialist permission to independently buy the
 *   > same mint. Instead allow each eligible specialist to emit a
 *   > SpecialistProposal. Canonical mint/version arbiter consumes ALL
 *   > proposals and elects one execution proposal for that mint/version.
 *   > All specialist proposals retain learning attribution. One
 *   > mint/version → maximum one executable BUY. Many specialists →
 *   > allowed to evaluate/propose/learn."
 *
 * This module IS that arbiter. Specialists submit `Proposal6629`
 * records via `submitProposal6629(...)`. The arbiter elects a winner
 * per `(mint + candidateVersion)` by ranking on `(confidence,
 * score, lanePriority)`. Non-elected proposals are preserved for
 * `LosingPatternMemory` / `StrategyTrustAI` attribution so a lane that
 * consistently proposes valid winners still learns even when another
 * lane is elected.
 *
 * IMPORTANT: this arbiter does NOT execute anything. It records
 * proposals and elects the causal winner. Callers still route the
 * elected proposal through the existing ExecutableOpenGate /
 * TradeAuthorizer / CanonicalEntryAuthority path. Wiring the meme
 * V5.0.6641 wires FDG proposals and the executable-open boundary to this
 * authority. A lane mismatch is fail-closed before a ticket can execute.
 */
object SpecialistProposalArbiter6629 {

    /**
     * A single specialist's proposal for a (mint, candidateVersion)
     * contest. Every field is derived from the specialist's own signal
     * — the arbiter never rewrites them.
     */
    data class Proposal6629(
        val mint: String,
        val candidateVersion: Long,
        val lane: String,
        val score: Double,
        val confidence: Double,
        val lanePriority: Int,       // lower = higher priority (matches lane routing table)
        val reason: String,
        val submittedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * Result of arbitration for one (mint, candidateVersion).
     * `elected` is null when no proposals were submitted (never nulled
     * once a winner is set).
     */
    data class Election6629(
        val mint: String,
        val candidateVersion: Long,
        val elected: Proposal6629?,
        val runnersUp: List<Proposal6629>,
        val decidedAtMs: Long = System.currentTimeMillis(),
    )

    private data class ContestKey(val mint: String, val candidateVersion: Long)

    private val contests = ConcurrentHashMap<ContestKey, MutableList<Proposal6629>>()
    private val decisions = ConcurrentHashMap<ContestKey, Election6629>()

    private val proposalsAccepted = AtomicLong(0L)
    private val proposalsDuplicateLane = AtomicLong(0L)
    private val electionsCompleted = AtomicLong(0L)
    private val duplicateBuysSuppressed = AtomicLong(0L)

    /**
     * Called by every eligible specialist for a mint. The arbiter
     * records the proposal but does NOT elect a winner until
     * `elect6629(mint, candidateVersion)` is called (typically at the
     * per-cycle end for the mint).
     *
     * Returns true if this is the first proposal from `lane` for the
     * contest, false if the lane already proposed (idempotent — same
     * proposal is replaced with the newer one).
     */
    fun submitProposal6629(proposal: Proposal6629): Boolean {
        if (proposal.mint.isBlank() || proposal.lane.isBlank()) return false
        val key = ContestKey(proposal.mint, proposal.candidateVersion)
        var first = true
        contests.compute(key) { _, existing ->
            val list = existing ?: mutableListOf()
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
     * Elect the winner for (mint, candidateVersion). Returns the
     * election result. If no proposals were submitted, returns an
     * election with `elected = null`. Elections are memoized per key
     * so calling `elect6629` twice for the same key returns the same
     * result.
     *
     * Ranking: (confidence desc, score desc, lanePriority asc,
     * submittedAtMs asc). Non-elected proposals populate runnersUp so
     * the caller can still emit learning attribution for them.
     */
    fun elect6629(mint: String, candidateVersion: Long): Election6629 {
        val key = ContestKey(mint, candidateVersion)
        // Memoized decision — arbitration is monotonic per contest.
        decisions[key]?.let { return it }
        val list = contests[key].orEmpty().toList()
        if (list.isEmpty()) {
            // Do not memoize an empty lookup. Open-gate timing may inspect the
            // contest before every specialist FDG callback has arrived; caching
            // that absence permanently made the arbiter causally inert.
            return Election6629(mint, candidateVersion, null, emptyList())
        }
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
                    "confidence=${"%.2f".format(elected.confidence)} " +
                    "score=${"%.2f".format(elected.score)} " +
                    "runnersUp=${runners.size} " +
                    "runnerLanes=${runners.joinToString(",") { it.lane }}",
            )
        } catch (_: Throwable) {}
        return decision
    }

    /**
     * Query the current decision (if any) for a contest without
     * forcing an election. Used by the eligibility gate to check
     * whether the arbiter has already elected another lane.
     */
    fun currentDecision6629(mint: String, candidateVersion: Long): Election6629? =
        decisions[ContestKey(mint, candidateVersion)]

    /**
     * Called after the elected BUY has been either sealed or rejected,
     * so the contest is retired and future proposals for the same
     * (mint, candidateVersion) do not accumulate. Safe to call twice.
     */
    fun retireContest6629(mint: String, candidateVersion: Long) {
        val key = ContestKey(mint, candidateVersion)
        contests.remove(key)
        // Decisions kept — they are the causal record.
    }

    fun statusLine6629(): String =
        "accepted=${proposalsAccepted.get()} dupLane=${proposalsDuplicateLane.get()} " +
            "elections=${electionsCompleted.get()} dupBuysSuppressed=${duplicateBuysSuppressed.get()} " +
            "liveContests=${contests.size} decisions=${decisions.size}"

    /** V5.0.6629 test-only reset. */
    fun resetForTest() {
        contests.clear()
        decisions.clear()
        proposalsAccepted.set(0L)
        proposalsDuplicateLane.set(0L)
        electionsCompleted.set(0L)
        duplicateBuysSuppressed.set(0L)
    }
}
