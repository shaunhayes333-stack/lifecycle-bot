package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LaneExecutionCoordinator
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6621 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §2/§5/§6 (operator
 * directive Feb 2026):
 *
 *   "specialist → canonical MemeExecutionIntent(lane) → executor
 *    preserves lane. The invariant is:
 *      ownerLane == FDG lane == executionIntent.lane == ticket.lane
 *      == executor lane == canonical position.entryLane == journal
 *      entryLane. No string reconstruction downstream."
 *
 *   "Make BUY execution transactional. Change specialist wrappers to
 *    return a definitive result (BuyResult sealed: Opened / Rejected /
 *    Failed). NO specialist state may be committed until
 *    BuyResult.Opened."
 *
 *   "Create/use one canonical MemeTrader entry coordinator:
 *      submitMemeSpecialistEntry(lane, candidate, specialistSignal,
 *      requestedSize). Individual specialist AIs produce
 *      proposals/signals. They do not independently open positions."
 *
 * DESIGN
 * ──────
 * MemeExecutionIntent6621 is the immutable envelope every MemeTrader
 * specialist creates BEFORE reaching the executor. Once sealed, no
 * downstream layer (FDG, ticket, executor, canonical position,
 * journal) may reclassify the lane or the candidate identity — they
 * validate and pass through. The intent is the source of truth for
 * the specialist's identity throughout the execution chain.
 *
 * BuyResult6621 is the sealed outcome the executor returns to the
 * coordinator. Only BuyResult6621.Opened permits specialist state
 * commit (addPosition / registerPosition / arm-learning). Rejected /
 * Failed leave the specialist registry empty.
 *
 * MemeEntryCoordinator6621 is the funnel every specialist entry
 * routes through. Given the size of the existing execution surface
 * (Executor.paperBuy is 500+ lines with numerous specialist wrappers),
 * Slice 2 delivers the CONTRACT + REGISTRY + COORDINATOR receiver
 * with counters; broad rollout of specialist entry sites onto the
 * coordinator is Slice 3. In this slice the coordinator SEALS every
 * accepted intent so downstream identity-checks (Slice 1's ownership
 * gate, ticket-restore, etc.) see the same lane/version bytes.
 */
object MemeExecutionIntent6621 {

    enum class Side { BUY, SELL, PARTIAL_SELL }
    enum class ExecutionMode { PAPER, LIVE }

    /**
     * IMMUTABLE. Every field is `val`. Once sealed, no method mutates
     * this object. Copies with different fields require a new attemptId
     * (i.e., a new specialist decision, not a "retry" of the old one).
     */
    data class Intent(
        val attemptId: String,
        val candidateId: String,
        val candidateVersion: Long,
        val mint: String,
        val symbol: String,
        val lane: String,
        val mode: ExecutionMode,
        val side: Side,
        val fdgVerdict: String,
        val requestedSol: Double,
        val sealedSol: Double,
        val createdAtMs: Long,
    ) {
        fun matchesTicketDimensions(
            ticketMint: String,
            ticketLane: String,
            ticketCandidateVersion: Long,
        ): Boolean =
            mint == ticketMint &&
                lane.equals(ticketLane, ignoreCase = false) &&
                candidateVersion == ticketCandidateVersion
    }

    private val sealed = ConcurrentHashMap<String, Intent>()
    private val sealCount = AtomicLong(0L)
    private val restoreMatches = AtomicLong(0L)
    private val restoreRejects = AtomicLong(0L)

    /**
     * Create + seal a new MemeExecutionIntent. Callers pass any lane
     * string; the coordinator normalises trivial aliases (BLUE_CHIP →
     * BLUECHIP, SHIT_COIN → SHITCOIN, SNIPE → PROJECT_SNIPER) before
     * sealing, so the invariant holds byte-for-byte downstream. See
     * §11 canonical MemeLane enum work in Slice 3.
     */
    fun seal6621(
        candidateId: String,
        mint: String,
        symbol: String,
        rawLane: String,
        mode: ExecutionMode,
        side: Side,
        fdgVerdict: String,
        requestedSol: Double,
        sealedSol: Double,
        candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint),
        attemptId: String = "att-${UUID.randomUUID().toString().take(12)}",
    ): Intent {
        val laneCanonical = canonicaliseLane6621(rawLane)
        val intent = Intent(
            attemptId = attemptId,
            candidateId = candidateId.ifBlank { "cand-${mint.take(8)}" },
            candidateVersion = candidateVersion,
            mint = mint,
            symbol = symbol,
            lane = laneCanonical,
            mode = mode,
            side = side,
            fdgVerdict = fdgVerdict,
            requestedSol = requestedSol,
            sealedSol = sealedSol,
            createdAtMs = System.currentTimeMillis(),
        )
        sealed[intent.attemptId] = intent
        sealCount.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MEME_EXECUTION_INTENT_SEALED_6621")
            PipelineHealthCollector.labelInc("MEME_EXECUTION_INTENT_SEALED_${laneCanonical}_6621")
            ForensicLogger.lifecycle(
                "MEME_EXECUTION_INTENT_SEALED_6621",
                "attemptId=$attemptId lane=$laneCanonical mint=${mint.take(10)} " +
                    "candidateVersion=$candidateVersion side=$side mode=$mode " +
                    "requestedSol=${"%.4f".format(requestedSol)} sealedSol=${"%.4f".format(sealedSol)}",
            )
        } catch (_: Throwable) {}
        return intent
    }

    /**
     * Look up a sealed intent by attemptId. Returns null if no intent
     * was ever sealed for that id (which means someone is trying to
     * execute without the coordinator — that's the §7 defect).
     */
    fun byAttemptId(attemptId: String): Intent? = sealed[attemptId]

    /**
     * Validate that a ticket about to be restored/consumed carries the
     * SAME immutable dimensions as its sealed intent. Emits
     * EXEC_TICKET_RESTORE_MATCH_6621 on success, EXEC_TICKET_RESTORE_
     * MISMATCH_6621 on failure. This is the operator's §8 gate.
     */
    fun validateTicketRestore6621(
        attemptId: String,
        ticketMint: String,
        ticketLane: String,
        ticketCandidateVersion: Long,
    ): Boolean {
        val intent = sealed[attemptId] ?: run {
            try { PipelineHealthCollector.labelInc("EXEC_TICKET_RESTORE_NO_INTENT_6621") } catch (_: Throwable) {}
            restoreRejects.incrementAndGet()
            return false
        }
        val ok = intent.matchesTicketDimensions(ticketMint, ticketLane, ticketCandidateVersion)
        if (ok) {
            restoreMatches.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXEC_TICKET_RESTORE_MATCH_6621") } catch (_: Throwable) {}
        } else {
            restoreRejects.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EXEC_TICKET_RESTORE_MISMATCH_6621")
                ForensicLogger.lifecycle(
                    "EXEC_TICKET_RESTORE_MISMATCH_6621",
                    "attemptId=$attemptId " +
                        "intentMint=${intent.mint.take(10)} ticketMint=${ticketMint.take(10)} " +
                        "intentLane=${intent.lane} ticketLane=$ticketLane " +
                        "intentVer=${intent.candidateVersion} ticketVer=$ticketCandidateVersion " +
                        "action=refuse_restore_release_stale_pending",
                )
            } catch (_: Throwable) {}
        }
        return ok
    }

    /**
     * V5.0.6621 §11 boundary parser. Only legacy string aliases are
     * normalised here — never merge CORE/STANDARD/V3_CORE (operator
     * §11 explicit rule) and never fold CASHGEN into TREASURY.
     */
    fun canonicaliseLane6621(raw: String): String {
        val u = raw.uppercase().trim().replace('-', '_').replace(' ', '_')
        return when (u) {
            "BLUE_CHIP"      -> "BLUECHIP"
            "SHIT_COIN"      -> "SHITCOIN"
            "SNIPE"          -> "PROJECT_SNIPER"
            "PROJECTSNIPER"  -> "PROJECT_SNIPER"
            "DIPHUNTER"      -> "DIP_HUNTER"
            "CASH_GEN"       -> "CASHGEN"
            else -> u.ifBlank { "STANDARD" }
        }
    }

    fun statusLine(): String =
        "sealed=${sealCount.get()} live=${sealed.size} " +
            "restoreMatch=${restoreMatches.get()} restoreReject=${restoreRejects.get()}"

    internal fun resetForTest() {
        sealed.clear(); sealCount.set(0L)
        restoreMatches.set(0L); restoreRejects.set(0L)
    }
}
