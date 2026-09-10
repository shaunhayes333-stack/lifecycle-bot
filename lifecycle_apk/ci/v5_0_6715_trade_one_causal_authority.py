#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected one exact match, got {n}")
    path.write_text(text.replace(old, new, 1))


def require(path: Path, needle: str, label: str):
    if needle not in path.read_text():
        raise SystemExit(f"{label}: required marker missing: {needle}")


# ---------------------------------------------------------------------------
# 1. Canonical trade-one causal freshness authority.
# ---------------------------------------------------------------------------
authority = SRC / "engine/truth/CausalFeedbackAuthority6715.kt"
authority.write_text(r'''package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * V5.0.6715 — TRADE-ONE CAUSAL FEEDBACK AUTHORITY.
 *
 * Contract:
 *  1. A MemeTrader decision ticket is stamped with the exact owner-lane and
 *     owner-lane/score-band feedback revisions that existed when FDG sealed it.
 *  2. Any clean terminal outcome advances terminalEpoch immediately. Pending
 *     tickets made under the prior epoch become stale and MUST re-enter FDG.
 *  3. A learning-eligible terminal blocks fresh economic admission for that
 *     owner scope until the exact owner learner ACKs it. Terminal reporting alone
 *     is not learning authority.
 *  4. Cold concurrency starts at one unresolved exposure per owner lane and per
 *     score band, then expands continuously as clean learned closes accumulate:
 *       cap = 1 + floor(sqrt(cleanLearnedCloses))
 *     (lane cap <= 6, band cap <= 3). There is no wait-for-N cliff.
 *  5. Non-Meme/cross-asset callers fail open here; their own canonical execution
 *     contracts remain authoritative.
 *
 * This object does NOT choose winners, set a target WR, or promise profitability.
 * It only makes the already-existing learning loop causally fresh: trade N cannot
 * execute on a pre-outcome decision after trade N-1 changed the learned state.
 */
object CausalFeedbackAuthority6715 {

    private val MEME_LANES = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )

    data class Admission(
        val allowed: Boolean,
        val reason: String,
        val forceRevalidate: Boolean = false,
        val laneCap: Int = 0,
        val bandCap: Int = 0,
        val laneUnresolved: Int = 0,
        val bandUnresolved: Int = 0,
    )

    private data class ScopeState(
        var terminalEpoch: Long = 0L,
        var learningRevision: Long = 0L,
        var cleanLearnedCloses: Int = 0,
        val reservedAttempts: MutableSet<String> = linkedSetOf(),
        val openPositions: MutableSet<String> = linkedSetOf(),
        val pendingLearning: MutableSet<String> = linkedSetOf(),
    )

    private data class ScopeStamp(val terminalEpoch: Long, val learningRevision: Long)
    private data class TicketStamp(
        val attemptId: String,
        val mode: String,
        val mint: String,
        val lane: String,
        val scoreBand: String,
        val scopes: Map<String, ScopeStamp>,
        val stampedAtMs: Long,
    )
    private data class Reservation(
        val attemptId: String,
        val mode: String,
        val mint: String,
        val lane: String,
        val scoreBand: String,
        val scopeKeys: List<String>,
        val reservedAtMs: Long,
    )

    private val lock = Any()
    private val scopes = HashMap<String, ScopeState>()
    private val ticketStamps = HashMap<String, TicketStamp>()
    private val reservations = HashMap<String, Reservation>()
    private val positionScopes = HashMap<String, List<String>>()
    private val earlyLearnAcks = HashSet<String>()
    private val terminalSeen = HashSet<String>()
    private val learnedSeen = HashSet<String>()

    private fun normMode(mode: String): String = mode.trim().uppercase().ifBlank { "UNKNOWN" }
    private fun normLane(raw: String): String = raw.trim().uppercase().replace('-', '_').replace(' ', '_').let {
        when (it) { "BLUE_CHIP" -> "BLUECHIP"; "PRESALE_SNIPE" -> "PROJECT_SNIPER"; else -> it }
    }
    fun isMemeOwnerLane(raw: String): Boolean = normLane(raw) in MEME_LANES

    fun scoreBand(score: Int): String = when {
        score < 0 -> "UNKNOWN"
        score <= 10 -> "S0-10"
        score <= 25 -> "S11-25"
        score <= 40 -> "S26-40"
        score <= 60 -> "S41-60"
        else -> "S61+"
    }

    private fun laneKey(mode: String, lane: String): String = "LANE|${normMode(mode)}|${normLane(lane)}"
    private fun bandKey(mode: String, lane: String, band: String): String = "BAND|${normMode(mode)}|${normLane(lane)}|$band"
    private fun keys(mode: String, lane: String, band: String): List<String> = listOf(laneKey(mode, lane), bandKey(mode, lane, band))
    private fun state(key: String): ScopeState = scopes.getOrPut(key) { ScopeState() }

    private fun cap(clean: Int, max: Int): Int = (1 + sqrt(clean.coerceAtLeast(0).toDouble()).toInt()).coerceIn(1, max)

    /** Stamp the policy state at actual decision/intent creation. Idempotent. */
    fun stampDecision(attemptId: String, mint: String, mode: String, lane: String, score: Int): Boolean {
        if (!isMemeOwnerLane(lane)) return true
        if (attemptId.isBlank() || mint.isBlank()) return false
        synchronized(lock) {
            if (ticketStamps.containsKey(attemptId)) return true
            val band = scoreBand(score)
            val ks = keys(mode, lane, band)
            val snap = ks.associateWith { k -> state(k).let { ScopeStamp(it.terminalEpoch, it.learningRevision) } }
            ticketStamps[attemptId] = TicketStamp(
                attemptId, normMode(mode), mint, normLane(lane), band, snap, System.currentTimeMillis(),
            )
            emit("CAUSAL_TICKET_STAMPED_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=${normMode(mode)} lane=${normLane(lane)} band=$band")
            return true
        }
    }

    /** Preserve the original decision epoch when an immutable ticket id is re-keyed. */
    fun transferStamp(oldAttemptId: String, newAttemptId: String) {
        if (oldAttemptId.isBlank() || newAttemptId.isBlank() || oldAttemptId == newAttemptId) return
        synchronized(lock) {
            val old = ticketStamps[oldAttemptId] ?: return
            ticketStamps.putIfAbsent(newAttemptId, old.copy(attemptId = newAttemptId))
        }
    }

    fun isDecisionCurrent(attemptId: String, mode: String, lane: String): Boolean {
        if (!isMemeOwnerLane(lane)) return true
        synchronized(lock) {
            val stamp = ticketStamps[attemptId] ?: return false
            if (!stamp.mode.equals(normMode(mode), true) || stamp.lane != normLane(lane)) return false
            return stamp.scopes.all { (k, v) ->
                val s = state(k)
                s.terminalEpoch == v.terminalEpoch && s.learningRevision == v.learningRevision
            }
        }
    }

    /**
     * Final admission boundary. Missing stamps are allowed only for a truly cold
     * scope (epoch=0/revision=0), so legacy/synthesised seals cannot be silently
     * freshened after learning has begun.
     */
    fun admit(attemptId: String, mint: String, mode: String, lane: String, score: Int): Admission {
        if (!isMemeOwnerLane(lane)) return Admission(true, "NON_MEME_FAIL_OPEN")
        val nm = normMode(mode); val nl = normLane(lane); val band = scoreBand(score)
        val canonicalLaneOpen = try {
            CanonicalPositionAuthority6441.openPositions().count {
                it.mode.equals(nm, true) && normLane(it.lane) == nl
            }
        } catch (_: Throwable) { 0 }
        synchronized(lock) {
            val ks = keys(nm, nl, band)
            val currentStates = ks.associateWith(::state)
            var stamp = ticketStamps[attemptId]
            if (stamp == null) {
                val trulyCold = currentStates.values.all { it.terminalEpoch == 0L && it.learningRevision == 0L && it.cleanLearnedCloses == 0 }
                if (!trulyCold) {
                    emit("CAUSAL_EXEC_STALE_EPOCH_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band reason=MISSING_FEEDBACK_STAMP")
                    return Admission(false, "MISSING_FEEDBACK_STAMP_REVALIDATE_6715", forceRevalidate = true)
                }
                val snap = ks.associateWith { k -> state(k).let { ScopeStamp(it.terminalEpoch, it.learningRevision) } }
                stamp = TicketStamp(attemptId, nm, mint, nl, band, snap, System.currentTimeMillis())
                ticketStamps[attemptId] = stamp
                emit("CAUSAL_TICKET_BOOTSTRAP_STAMPED_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band")
            }
            if (stamp.mode != nm || stamp.lane != nl || stamp.scoreBand != band) {
                releaseAttemptLocked(attemptId, removeStamp = true)
                emit("CAUSAL_EXEC_STALE_EPOCH_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} expected=$nm/$nl/$band stamped=${stamp.mode}/${stamp.lane}/${stamp.scoreBand} reason=IDENTITY_DRIFT")
                return Admission(false, "FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715", forceRevalidate = true)
            }
            val stale = stamp.scopes.any { (k, v) -> state(k).let { it.terminalEpoch != v.terminalEpoch || it.learningRevision != v.learningRevision } }
            if (stale) {
                releaseAttemptLocked(attemptId, removeStamp = true)
                emit("CAUSAL_EXEC_STALE_EPOCH_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band reason=LEARNER_REVISION_CHANGED")
                return Admission(false, "STALE_FEEDBACK_EPOCH_REVALIDATE_6715", forceRevalidate = true)
            }
            if (currentStates.values.any { it.pendingLearning.isNotEmpty() }) {
                emit("CAUSAL_EXEC_BLOCK_FEEDBACK_PENDING_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band pending=${currentStates.values.sumOf { it.pendingLearning.size }}")
                return Admission(false, "TERMINAL_FEEDBACK_NOT_LEARNED_6715")
            }
            reservations[attemptId]?.let {
                return Admission(true, "IDEMPOTENT_CAUSAL_RESERVATION_6715")
            }

            val laneState = currentStates.getValue(laneKey(nm, nl))
            val bandState = currentStates.getValue(bandKey(nm, nl, band))
            val laneCap = cap(laneState.cleanLearnedCloses, 6)
            val bandCap = cap(bandState.cleanLearnedCloses, 3)
            val laneUnresolved = maxOf(laneState.openPositions.size, canonicalLaneOpen) + laneState.reservedAttempts.size
            val bandUnresolved = bandState.openPositions.size + bandState.reservedAttempts.size
            if (laneUnresolved >= laneCap || bandUnresolved >= bandCap) {
                emit("CAUSAL_EXEC_BLOCK_FEEDBACK_PENDING_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band laneUnresolved=$laneUnresolved/$laneCap bandUnresolved=$bandUnresolved/$bandCap reason=UNRESOLVED_CAP")
                return Admission(false, "UNRESOLVED_FEEDBACK_CAP_6715", laneCap = laneCap, bandCap = bandCap, laneUnresolved = laneUnresolved, bandUnresolved = bandUnresolved)
            }
            val r = Reservation(attemptId, nm, mint, nl, band, ks, System.currentTimeMillis())
            reservations[attemptId] = r
            ks.forEach { state(it).reservedAttempts.add(attemptId) }
            emit("CAUSAL_EXEC_ADMITTED_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band lane=$laneUnresolved->$laneCap band=$bandUnresolved->$bandCap")
            return Admission(true, "CAUSAL_FRESH_6715", laneCap = laneCap, bandCap = bandCap, laneUnresolved = laneUnresolved, bandUnresolved = bandUnresolved)
        }
    }

    /** Exact canonical OPEN boundary; converts a pending decision reservation into exposure. */
    fun onPositionOpened(positionId: String, mode: String, mint: String, lane: String) {
        if (!isMemeOwnerLane(lane) || positionId.isBlank()) return
        synchronized(lock) {
            val nm = normMode(mode); val nl = normLane(lane)
            val r = reservations.values
                .filter { it.mode == nm && it.mint == mint && it.lane == nl }
                .maxByOrNull { it.reservedAtMs }
            val ks = r?.scopeKeys ?: keys(nm, nl, "UNKNOWN")
            if (r != null) {
                r.scopeKeys.forEach { state(it).reservedAttempts.remove(r.attemptId) }
                reservations.remove(r.attemptId)
                ticketStamps.remove(r.attemptId)
            } else {
                emit("CAUSAL_OPEN_WITHOUT_RESERVATION_6715", "positionId=${positionId.take(24)} mint=${mint.take(10)} mode=$nm lane=$nl")
            }
            ks.forEach { state(it).openPositions.add(positionId) }
            positionScopes[positionId] = ks
            emit("CAUSAL_POSITION_OPENED_6715", "positionId=${positionId.take(24)} mint=${mint.take(10)} mode=$nm lane=$nl scopes=${ks.joinToString(",")}")
        }
    }

    /** Canonical terminal truth advances epoch before any learner consumer order can matter. */
    fun onTerminal(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!isMemeOwnerLane(env.lane)) return true
        synchronized(lock) {
            if (!terminalSeen.add(env.tradeId)) return true
            val nm = normMode(env.mode); val nl = normLane(env.lane)
            val computed = keys(nm, nl, env.scoreBand.ifBlank { scoreBand(env.entryScore) })
            val ks = positionScopes[env.positionId] ?: computed
            val invalidated = linkedSetOf<String>()
            ks.forEach { k ->
                val s = state(k)
                s.openPositions.remove(env.positionId)
                invalidated.addAll(s.reservedAttempts)
                s.terminalEpoch += 1L
            }
            invalidated.forEach { releaseAttemptLocked(it, removeStamp = true) }
            if (invalidated.isNotEmpty()) {
                emit("CAUSAL_PENDING_INVALIDATED_ON_TERMINAL_6715", "positionId=${env.positionId.take(24)} lane=$nl count=${invalidated.size}")
            }
            val earlyAck = earlyLearnAcks.remove(env.positionId)
            if (env.learningEligible) {
                if (earlyAck) {
                    ks.forEach { k -> state(k).apply { learningRevision += 1L; cleanLearnedCloses += 1 } }
                    learnedSeen.add(env.positionId)
                    positionScopes.remove(env.positionId)
                    emit("CAUSAL_OWNER_LEARN_ACK_6715", "positionId=${env.positionId.take(24)} lane=$nl order=ACK_BEFORE_TERMINAL")
                } else {
                    ks.forEach { state(it).pendingLearning.add(env.positionId) }
                    positionScopes[env.positionId] = ks
                }
            } else {
                positionScopes.remove(env.positionId)
            }
            emit("CAUSAL_TERMINAL_OBSERVED_6715", "positionId=${env.positionId.take(24)} tradeId=${env.tradeId.take(24)} mode=$nm lane=$nl learningEligible=${env.learningEligible} pending=${if (env.learningEligible && !earlyAck) 1 else 0}")
            return true
        }
    }

    /** Called only after exact owner-bound policy mutation succeeds. */
    fun markLearned(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        synchronized(lock) {
            if (learnedSeen.contains(positionId)) return true
            val ks = positionScopes[positionId]
            if (ks == null) {
                earlyLearnAcks.add(positionId)
                emit("CAUSAL_OWNER_LEARN_ACK_EARLY_6715", "positionId=${positionId.take(24)} action=hold_until_terminal_consumer")
                return true
            }
            val wasPending = ks.any { state(it).pendingLearning.contains(positionId) }
            if (!wasPending) {
                earlyLearnAcks.add(positionId)
                return true
            }
            ks.forEach { k ->
                val s = state(k)
                s.pendingLearning.remove(positionId)
                s.learningRevision += 1L
                s.cleanLearnedCloses += 1
            }
            learnedSeen.add(positionId)
            positionScopes.remove(positionId)
            emit("CAUSAL_OWNER_LEARN_ACK_6715", "positionId=${positionId.take(24)} scopes=${ks.joinToString(",")}")
            return true
        }
    }

    fun releaseAttempt(attemptId: String) {
        if (attemptId.isBlank()) return
        synchronized(lock) { releaseAttemptLocked(attemptId, removeStamp = true) }
    }

    private fun releaseAttemptLocked(attemptId: String, removeStamp: Boolean) {
        val r = reservations.remove(attemptId)
        r?.scopeKeys?.forEach { state(it).reservedAttempts.remove(attemptId) }
        if (removeStamp) ticketStamps.remove(attemptId)
    }

    fun statusLine(): String = synchronized(lock) {
        val pending = scopes.values.sumOf { it.pendingLearning.size }
        val reserved = reservations.size
        val opens = scopes.filterKeys { it.startsWith("LANE|") }.values.sumOf { it.openPositions.size }
        val learned = scopes.filterKeys { it.startsWith("LANE|") }.values.sumOf { it.cleanLearnedCloses }
        "CausalFeedback6715 scopes=${scopes.size} learned=$learned pendingLearning=$pending reserved=$reserved trackedOpen=$opens tickets=${ticketStamps.size}"
    }

    internal fun resetForTest6715() = synchronized(lock) {
        scopes.clear(); ticketStamps.clear(); reservations.clear(); positionScopes.clear()
        earlyLearnAcks.clear(); terminalSeen.clear(); learnedSeen.clear()
    }

    private fun emit(label: String, detail: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle(label, detail) } catch (_: Throwable) {}
    }
}
''')

# ---------------------------------------------------------------------------
# 2. ExecutionIntent creation and final EXEC_GATE bind consume causal freshness.
# ---------------------------------------------------------------------------
exec_gate = SRC / "engine/ExecutableOpenGate.kt"

replace_once(
    exec_gate,
    '''        executionTickets[authoritative.attemptId] = authoritative
        try { PipelineHealthCollector.labelInc("EXEC_INTENT_CREATED")
''',
    '''        executionTickets[authoritative.attemptId] = authoritative
        // V5.0.6715 — stamp the actual FDG/intent creation epoch. Never stamp at
        // terminal/report time: this is the decision provenance trade N+1 must prove.
        try {
            val causalScore6715 = com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
                .currentForMint(authoritative.mint, authoritative.candidateVersion, authoritative.mode)
                ?.score?.toInt() ?: states[authoritative.mint]?.entryScore ?: -1
            com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.stampDecision(
                authoritative.attemptId, authoritative.mint, authoritative.mode,
                authoritative.canonicalLane, causalScore6715,
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("EXEC_INTENT_CREATED")
''',
    "stamp canonical intent feedback epoch",
)

replace_once(
    exec_gate,
    '''    private fun revalidateAndResealExpired6613(intent: ExecutionIntent): ExecutionIntent? {
        if (!resealedTickets6613.add(intent.attemptId)) return null
''',
    '''    private fun revalidateAndResealExpired6613(intent: ExecutionIntent): ExecutionIntent? {
        if (!resealedTickets6613.add(intent.attemptId)) return null
        // V5.0.6715 — time expiry can be refreshed; learning-state expiry cannot.
        // If a terminal/owner-learning revision changed after this ticket was sealed,
        // the candidate must re-enter FDG instead of being cosmetically resealed.
        if (!com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.isDecisionCurrent(
                intent.attemptId, intent.mode, intent.canonicalLane,
            )) {
            try {
                PipelineHealthCollector.labelInc("EXPIRED_TICKET_FEEDBACK_EPOCH_REJECT_6715")
                ForensicLogger.lifecycle("EXPIRED_TICKET_FEEDBACK_EPOCH_REJECT_6715", "attemptId=${intent.attemptId.take(28)} mint=${intent.mint.take(10)} lane=${intent.canonicalLane} action=reenter_fdg")
            } catch (_: Throwable) {}
            com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.releaseAttempt(intent.attemptId)
            return null
        }
''',
    "expired ticket cannot bypass newer learning",
)

replace_once(
    exec_gate,
    '''        executionTickets.remove(intent.attemptId, intent)
        executionTickets[replacement.attemptId] = replacement
''',
    '''        executionTickets.remove(intent.attemptId, intent)
        com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.transferStamp(intent.attemptId, replacement.attemptId)
        executionTickets[replacement.attemptId] = replacement
''',
    "transfer causal stamp on valid reseal",
)

replace_once(
    exec_gate,
    '''    fun terminalizeAttempt6514(attemptId: String, mint: String, lane: String) {
        revokeAttempt6514(attemptId, mint, lane)
        // Terminal outcomes clear the retry-pending owner as well.
''',
    '''    fun terminalizeAttempt6514(attemptId: String, mint: String, lane: String) {
        revokeAttempt6514(attemptId, mint, lane)
        try { com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.releaseAttempt(attemptId) } catch (_: Throwable) {}
        // Terminal outcomes clear the retry-pending owner as well.
''',
    "release causal reservation on terminal attempt",
)

replace_once(
    exec_gate,
    '''        val claimKey6487 = executableClaimKey6487(modeUpper, mint, candidateVersion)
        val priorClaim6487 = executableBuyClaim6487.putIfAbsent(claimKey6487, execKey)
''',
    '''        // V5.0.6715 — TRADE-ONE CAUSAL FINALITY. This is intentionally
        // immediately before executable-claim publication: all safety/FDG/size
        // checks have passed, but no economic-open residue exists yet.
        if (fdgIntent6519.attemptId != execKey) {
            try { com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.transferStamp(fdgIntent6519.attemptId, execKey) } catch (_: Throwable) {}
        }
        val causalScore6715 = state?.entryScore
            ?: immutableAuthority6513?.score?.toInt()
            ?: try {
                com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
                    .currentForMint(mint, candidateVersion, modeUpper)?.score?.toInt()
            } catch (_: Throwable) { null }
            ?: -1
        val causalAdmission6715 = try {
            com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.admit(
                execKey, mint, modeUpper, canonicalSelectedLane, causalScore6715,
            )
        } catch (_: Throwable) {
            com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.Admission(false, "CAUSAL_AUTHORITY_EXCEPTION_6715", forceRevalidate = true)
        }
        if (!causalAdmission6715.allowed) {
            if (causalAdmission6715.forceRevalidate) {
                executionTickets.remove(fdgIntent6519.attemptId)
                executionTickets.remove(execKey)
                activeExecutionIntents6519.entries.removeIf { it.value.attemptId == fdgIntent6519.attemptId || it.value.attemptId == execKey }
            }
            return blocked(
                "EXEC_OPEN_BLOCKED_CAUSAL_FEEDBACK_6715",
                causalAdmission6715.reason,
                shadow = modeUpper == "PAPER",
            )
        }

        val claimKey6487 = executableClaimKey6487(modeUpper, mint, candidateVersion)
        val priorClaim6487 = executableBuyClaim6487.putIfAbsent(claimKey6487, execKey)
''',
    "causal freshness at final executable bind",
)

replace_once(
    exec_gate,
    '''        if (priorClaim6487 != null && priorClaim6487 != execKey) {
            try {
''',
    '''        if (priorClaim6487 != null && priorClaim6487 != execKey) {
            try { com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.releaseAttempt(execKey) } catch (_: Throwable) {}
            try {
''',
    "release causal reservation on mint-version loser",
)

replace_once(
    exec_gate,
    '''            return OpenVerdict(false, "DUPLICATE_EXECUTION_KEY_SUPPRESSED", shadowOnly = true, logName = "EXEC_OPEN_DUPLICATE_SUPPRESSED", attemptId = execKey)
        }
''',
    '''            try { com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.releaseAttempt(execKey) } catch (_: Throwable) {}
            return OpenVerdict(false, "DUPLICATE_EXECUTION_KEY_SUPPRESSED", shadowOnly = true, logName = "EXEC_OPEN_DUPLICATE_SUPPRESSED", attemptId = execKey)
        }
''',
    "release causal reservation on duplicate execution key",
)

# ---------------------------------------------------------------------------
# 3. Exact canonical OPEN converts reservation into unresolved exposure.
# ---------------------------------------------------------------------------
canon = SRC / "engine/truth/CanonicalPositionAuthority6441.kt"
replace_once(
    canon,
    '''    private fun lockEntryMetricsAtOpen6636(position: Position) {
        if (position.lifecycle != Lifecycle.OPEN && position.lifecycle != Lifecycle.PARTIALLY_CLOSED) return
''',
    '''    private fun lockEntryMetricsAtOpen6636(position: Position) {
        if (position.lifecycle != Lifecycle.OPEN && position.lifecycle != Lifecycle.PARTIALLY_CLOSED) return
        try {
            CausalFeedbackAuthority6715.onPositionOpened(
                position.positionId, position.mode, position.mint, position.lane,
            )
        } catch (_: Throwable) {}
''',
    "canonical open causal exposure hook",
)

# ---------------------------------------------------------------------------
# 4. Every terminal reaches causal epoch authority even when learning excluded.
# ---------------------------------------------------------------------------
bus = SRC / "engine/truth/CanonicalFinalizedTradeBus6464.kt"
replace_once(
    bus,
    '''        "ForwardOutcomeModel", "UnifiedExitPolicyHead", "Dashboard",
''',
    '''        "ForwardOutcomeModel", "UnifiedExitPolicyHead", "CausalFeedback6715", "Dashboard",
''',
    "register causal feedback terminal consumer",
)

bridge = SRC / "engine/truth/FinalizedBusConsumerBridge6465.kt"
replace_once(
    bridge,
    '''            "UnifiedExitPolicyHead" -> deliverToUnifiedExitPolicyHead6696(env)
            "Dashboard"           -> deliverToDashboard(env)
''',
    '''            "UnifiedExitPolicyHead" -> deliverToUnifiedExitPolicyHead6696(env)
            "CausalFeedback6715"  -> deliverToCausalFeedback6715(env)
            "Dashboard"           -> deliverToDashboard(env)
''',
    "dispatch causal terminal consumer",
)
replace_once(
    bridge,
    '''    private val NON_LEARNING_CONSUMERS = setOf("Dashboard")
''',
    '''    private val NON_LEARNING_CONSUMERS = setOf("Dashboard", "CausalFeedback6715")
''',
    "causal terminal consumer bypasses learning quarantine",
)
replace_once(
    bridge,
    '''    private fun deliverToRewardPurity(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean =
''',
    '''    private fun deliverToCausalFeedback6715(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        CausalFeedbackAuthority6715.onTerminal(env)
    } catch (_: Throwable) { false }

    private fun deliverToRewardPurity(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean =
''',
    "causal terminal delivery implementation",
)

# ---------------------------------------------------------------------------
# 5. Owner learner ACK advances learning revision. No report-only ACKs.
# ---------------------------------------------------------------------------
fabric = SRC / "engine/truth/AateDecisionEnvelope6512.kt"
replace_once(
    fabric,
    '''        if (!rewardedPositions.add(env.positionId)) return true
        if (policyAck6713 && UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
''',
    '''        if (!rewardedPositions.add(env.positionId)) return true
        if (policyAck6713) {
            try { CausalFeedbackAuthority6715.markLearned(env.positionId) } catch (_: Throwable) {}
        }
        if (policyAck6713 && UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
''',
    "exact owner policy ACK advances causal learning revision",
)

# ---------------------------------------------------------------------------
# 6. Lane expectancy starts soft influence at close #1; mature gates stay mature.
# ---------------------------------------------------------------------------
damper = SRC / "engine/LaneExpectancyDamper.kt"
replace_once(
    damper,
    '''        if (map.isEmpty()) "LaneExpectancyDamper[$env6679]: no shaped lanes (all lanes ≥ ${BLEEDER_MEAN_PCT}% or < $MIN_TRADES trades)"
''',
    '''        if (map.isEmpty()) "LaneExpectancyDamper[$env6679]: no shaped lanes (no same-mode terminal edge requiring a soft shape)"
''',
    "damper status no longer claims n8 prerequisite",
)
replace_once(
    damper,
    '''        for (m in board) {
            if (m.trades < MIN_TRADES) continue

            // Proven profitable asymmetric runners may be pressed, but only when
''',
    '''        for (m in board) {
            if (m.trades < 1) continue
            // V5.0.6715 — evidence is continuous from trade one. One outcome may
            // nudge size, never dominate it; confidence grows smoothly instead of
            // being exactly zero until the old n=8 cliff.
            val evidence6715 = (m.trades.toDouble() / (m.trades.toDouble() + 3.0)).coerceIn(0.0, 1.0)
            fun blend6715(raw: Double): Double = (1.0 + (raw - 1.0) * evidence6715).coerceIn(0.05, 1.60)

            // Proven profitable asymmetric runners may be pressed, but only when
''',
    "damper trade-one evidence ramp",
)
# Blend every actual boost/haircut write, leaving explicit 1.0 neutral writes alone.
dtxt = damper.read_text()
dtxt = dtxt.replace('out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, boost)',
                    'out[m.strategy.trim().uppercase()] = maxOf(out[m.strategy.trim().uppercase()] ?: 1.0, blend6715(boost))')
dtxt = dtxt.replace('out[m.strategy.trim().uppercase()] = mult\n',
                    'out[m.strategy.trim().uppercase()] = blend6715(mult)\n')
damper.write_text(dtxt)
require(damper, "blend6715(mult)", "damper loss shape blended from trade one")

# ---------------------------------------------------------------------------
# 7. Score-band calibration has a soft raw mean from close #1; hard reject remains n15.
# ---------------------------------------------------------------------------
score = SRC / "engine/ScoreExpectancyTracker.kt"
replace_once(
    score,
    '''    /** Mean pnlPct for [layer]@[score] bucket, or null when under-sampled. */
    fun bucketMean(layer: String, score: Int): Double? {
''',
    '''    /** Raw clean mean from the first recorded close; used only for bounded soft sizing. */
    fun bucketRawMean6715(layer: String, score: Int): Double? {
        val w = windows[keyOf(layer, score)] ?: return null
        synchronized(w) {
            if (w.isEmpty()) return null
            val sane = w.map { when {
                it.isNaN() || it.isInfinite() -> 0.0
                it > 5000.0 -> 5000.0
                it < -100.0 -> -100.0
                else -> it
            } }
            return sane.sum() / sane.size
        }
    }

    /** Mean pnlPct for [layer]@[score] bucket, or null when under-sampled for HARD decisions. */
    fun bucketMean(layer: String, score: Int): Double? {
''',
    "score raw mean from trade one",
)
replace_once(
    score,
    '''        val mean = bucketMean(layer, score) ?: return 1.0   // null = too few samples → no shaping
        return when {
            mean >= 0.0    -> 1.0
            mean >= -8.0   -> 0.70
            mean >= -15.0  -> 0.45
            else           -> 0.25
        }
''',
    '''        val samples = bucketSamples(layer, score)
        val mean = bucketRawMean6715(layer, score) ?: return 1.0
        // V5.0.6715 — trade-one soft evidence. The hard shouldReject() contract
        // remains MIN_SAMPLES_FOR_REJECT=15; only size reacts immediately.
        val raw = when {
            mean >= 0.0    -> 1.0
            mean >= -8.0   -> 0.70
            mean >= -15.0  -> 0.45
            else           -> 0.25
        }
        val evidence = (samples.toDouble() / (samples.toDouble() + 3.0)).coerceIn(0.0, 1.0)
        return (1.0 + (raw - 1.0) * evidence).coerceIn(0.25, 1.0)
''',
    "score calibration soft response from trade one",
)

# ---------------------------------------------------------------------------
# 8. One severe clean loss changes tactic immediately; ordinary first loss is size-only.
# ---------------------------------------------------------------------------
tactic = SRC / "engine/learning/TacticSwitcher.kt"
replace_once(
    tactic,
    '''    // a single close with |pnl| >= 90% rotates immediately. This is only a
    // rotation (never a disable), and 90% is deliberately tight so real market
    // rugs (which are usually >=95% wipe) still trigger while day-to-day
    // volatility (typically <30%) does not.
    private const val TRADE_ONE_CATASTROPHIC_PNL = -90.0
''',
    '''    // a single severe CLEAN canonical close rotates immediately. V5.0.6715
    // moves this from rug-only (-90%) to policy-failure magnitude (-25%). Ordinary
    // first losses do NOT panic-rotate: the trade-one score/lane soft shapers trim
    // the next exposure instead. A <=-25% clean close is already beyond normal
    // stop intent and is strong evidence that this tactic/context is wrong NOW.
    private const val TRADE_ONE_CATASTROPHIC_PNL = -25.0
''',
    "trade-one severe tactic rotation threshold",
)

# ---------------------------------------------------------------------------
# 9. Regression tests: freshness, feedback ACK ordering, and low-sample soft response.
# ---------------------------------------------------------------------------
(TEST / "Aate6715TradeOneCausalAuthorityTest.kt").write_text(r'''package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class Aate6715TradeOneCausalAuthorityTest {
    @Before fun reset() { CausalFeedbackAuthority6715.resetForTest6715() }

    @Test fun `cold owner lane permits one unresolved exposure then waits for its outcome`() {
        val lane = "EXPRESS"; val mode = "PAPER"; val score = 20
        assertTrue(CausalFeedbackAuthority6715.stampDecision("a1", "mint-a", mode, lane, score))
        val first = CausalFeedbackAuthority6715.admit("a1", "mint-a", mode, lane, score)
        assertTrue(first.reason, first.allowed)
        assertTrue(CausalFeedbackAuthority6715.stampDecision("a2", "mint-b", mode, lane, score))
        val second = CausalFeedbackAuthority6715.admit("a2", "mint-b", mode, lane, score)
        assertFalse(second.allowed)
        assertEquals("UNRESOLVED_FEEDBACK_CAP_6715", second.reason)
    }

    @Test fun `terminal invalidates old tickets and learner ack is required before fresh admission`() {
        val lane = "PROJECT_SNIPER"; val mode = "PAPER"; val score = 20
        CausalFeedbackAuthority6715.stampDecision("a1", "mint-a", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("a1", "mint-a", mode, lane, score).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("p1", mode, "mint-a", lane)
        // Ticket a2 is genuinely made before trade 1 finalizes.
        CausalFeedbackAuthority6715.stampDecision("a2", "mint-b", mode, lane, score)
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId="t1", atMs=System.currentTimeMillis(), realizedPnlSol=-0.01,
            realizedReturnPct=-20.0, mint="mint-a", lane=lane, positionId="p1",
            mode=mode, entryScore=score, scoreBand="S11-25", learningEligible=true,
        )
        assertTrue(CausalFeedbackAuthority6715.onTerminal(env))
        val stale = CausalFeedbackAuthority6715.admit("a2", "mint-b", mode, lane, score)
        assertFalse(stale.allowed)
        assertTrue(stale.forceRevalidate)
        // A brand-new post-terminal decision is current, but economic admission
        // remains held until the exact owner learner ACKs trade 1.
        CausalFeedbackAuthority6715.stampDecision("a3", "mint-c", mode, lane, score)
        val pending = CausalFeedbackAuthority6715.admit("a3", "mint-c", mode, lane, score)
        assertFalse(pending.allowed)
        assertEquals("TERMINAL_FEEDBACK_NOT_LEARNED_6715", pending.reason)
        assertTrue(CausalFeedbackAuthority6715.markLearned("p1"))
        // Learning revision changed, therefore a3 is stale too; trade 2 must be
        // newly judged under the learner state produced by trade 1.
        val postAckStale = CausalFeedbackAuthority6715.admit("a3", "mint-c", mode, lane, score)
        assertFalse(postAckStale.allowed)
        assertTrue(postAckStale.forceRevalidate)
        CausalFeedbackAuthority6715.stampDecision("a4", "mint-d", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("a4", "mint-d", mode, lane, score).allowed)
    }

    @Test fun `paper score calibration responds on the first loss without hard rejecting`() {
        ScoreExpectancyTracker.reset()
        ScoreExpectancyTracker.record("EXPRESS", 20, -20.0)
        assertEquals(1, ScoreExpectancyTracker.bucketSamples("EXPRESS", 20))
        assertNotNull(ScoreExpectancyTracker.bucketRawMean6715("EXPRESS", 20))
        assertFalse("hard reject must retain its mature sample rule", ScoreExpectancyTracker.shouldReject("EXPRESS", 20))
        val mult = ScoreExpectancyTracker.calibrationSizeMult("EXPRESS", 20)
        assertTrue("first loss must trim next paper size, got $mult", mult < 1.0)
        assertTrue("trade-one evidence must remain bounded, got $mult", mult > 0.25)
    }

    @Test fun `source contract has exact terminal consumer and exact owner learner ack`() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val fabric = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(bus.contains("CausalFeedback6715"))
        assertTrue(bridge.contains("deliverToCausalFeedback6715"))
        assertTrue(bridge.contains("setOf(\"Dashboard\", \"CausalFeedback6715\")"))
        assertTrue(fabric.contains("CausalFeedbackAuthority6715.markLearned(env.positionId)"))
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_CAUSAL_FEEDBACK_6715"))
        assertTrue(gate.contains("stampDecision("))
    }

    @Test fun `from trade one no longer means wait eight or fifteen for all soft influence`() {
        val damper = File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        val score = File("src/main/kotlin/com/lifecyclebot/engine/ScoreExpectancyTracker.kt").readText()
        val tactic = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(damper.contains("m.trades < 1"))
        assertFalse(damper.contains("if (m.trades < MIN_TRADES) continue"))
        assertTrue(score.contains("bucketRawMean6715"))
        assertTrue(score.contains("samples.toDouble() / (samples.toDouble() + 3.0)"))
        assertTrue(tactic.contains("TRADE_ONE_CATASTROPHIC_PNL = -25.0"))
    }
}
''')

# ---------------------------------------------------------------------------
# 10. Patch-rot assertions before the workflow even starts Gradle.
# ---------------------------------------------------------------------------
for p, marker in [
    (authority, "STALE_FEEDBACK_EPOCH_REVALIDATE_6715"),
    (exec_gate, "EXEC_OPEN_BLOCKED_CAUSAL_FEEDBACK_6715"),
    (canon, "CausalFeedbackAuthority6715.onPositionOpened"),
    (bus, '"CausalFeedback6715"'),
    (bridge, "deliverToCausalFeedback6715"),
    (fabric, "CausalFeedbackAuthority6715.markLearned"),
    (damper, "evidence6715"),
    (score, "bucketRawMean6715"),
    (tactic, "TRADE_ONE_CATASTROPHIC_PNL = -25.0"),
]:
    require(p, marker, f"6715 source consolidation {p.name}")

print("V5.0.6715 trade-one causal authority source repair applied")
