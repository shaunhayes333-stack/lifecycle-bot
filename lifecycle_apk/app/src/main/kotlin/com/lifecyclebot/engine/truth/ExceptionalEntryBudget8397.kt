package com.lifecyclebot.engine.truth

/**
 * The exception meters submitted live attempts, not evidence reads. Reservations count against
 * the same limits while an executor is in flight; only its finally/receipt may release or commit
 * one. An in-flight reservation is never expired underneath a possibly submitted transaction.
 * At most three reservations can exist, and qualification memory is independently bounded.
 */
internal class ExceptionalEntryBudget8397(
    private val laneGapMs: Long = 30 * 60_000L,
    private val maxPerHour: Int = 3,
    private val proofTtlMs: Long = 60_000L,
) {
    data class Candidate(val mint: String, val lane: String, val version: Long)
    data class Proof(val reason: String, val atMs: Long, val viaGrab: Boolean)
    data class Reservation(val attempt: String, val candidate: Candidate, val proof: Proof, val atMs: Long)
    data class Charge(val reservation: Reservation, val submittedAtMs: Long, val signature: String)

    private val proofs = LinkedHashMap<Candidate, Proof>()
    private val reserved = LinkedHashMap<String, Reservation>()
    private val submitted = LinkedHashMap<String, Charge>()
    private var total = 0L
    private var grabTotal = 0L
    private var last = "-"

    @Synchronized fun qualify(c: Candidate, reason: String, viaGrab: Boolean, nowMs: Long): Boolean {
        if (c.mint.isBlank() || c.lane.isBlank() || c.version <= 0L || reason.isBlank()) return false
        trim(nowMs)
        if (!canQualify(c, nowMs)) return false
        proofs.remove(c)
        proofs[c] = Proof(reason, nowMs, viaGrab)
        while (proofs.size > 512) proofs.remove(proofs.keys.first())
        return true
    }

    @Synchronized fun proof(c: Candidate, nowMs: Long): Proof? =
        proofs[c]?.takeIf { nowMs - it.atMs in 0L..proofTtlMs }

    @Synchronized fun recentProof(mint: String, lane: String, nowMs: Long): Proof? =
        proofs.entries.lastOrNull { it.key.mint == mint && it.key.lane == lane && nowMs - it.value.atMs in 0L..proofTtlMs }?.value

    @Synchronized fun laneHasSubmittedPass(lane: String, nowMs: Long): Boolean =
        submitted.values.any { it.reservation.candidate.lane == lane && nowMs - it.submittedAtMs in 0L..proofTtlMs }

    @Synchronized fun canQualify(c: Candidate, nowMs: Long): Boolean =
        reserved.values.any { it.candidate == c } || submitted.values.any { it.reservation.candidate == c && nowMs - it.submittedAtMs in 0L..proofTtlMs } || rateAllows(c.lane, nowMs)

    @Synchronized fun rateAllows(lane: String, nowMs: Long): Boolean {
        trim(nowMs)
        return reserved.values.none { it.candidate.lane == lane } &&
            submitted.values.none { it.reservation.candidate.lane == lane && nowMs - it.submittedAtMs < laneGapMs } &&
            reserved.size + submitted.size < maxPerHour
    }

    /** null = admitted reservation (or the identical retry); a reason never mutates the budget. */
    @Synchronized fun reserve(attempt: String, c: Candidate, nowMs: Long): String? {
        if (attempt.isBlank() || c.version <= 0L) return "EVIDENCE_EXCEPTION_IDENTITY_MISSING_8397"
        reserved[attempt]?.let { return if (it.candidate == c) null else "EVIDENCE_EXCEPTION_IDENTITY_MISMATCH_8397" }
        submitted[attempt]?.let { return if (it.reservation.candidate == c) "EVIDENCE_EXCEPTION_ALREADY_SUBMITTED_8397" else "EVIDENCE_EXCEPTION_IDENTITY_MISMATCH_8397" }
        val p = proof(c, nowMs) ?: return "EVIDENCE_EXCEPTION_PROOF_EXPIRED_8397"
        if (!rateAllows(c.lane, nowMs)) return "EVIDENCE_EXCEPTION_RATE_BOUND_8397"
        reserved[attempt] = Reservation(attempt, c, p, nowMs)
        return null
    }

    /** A nonblank signature is liability even if confirmation is unknown. Repeated receipts are idempotent. */
    @Synchronized fun submitted(attempt: String, signature: String, nowMs: Long): Charge? {
        if (signature.isBlank() || submitted.containsKey(attempt)) return null
        val r = reserved.remove(attempt) ?: return null
        val charge = Charge(r, nowMs, signature)
        submitted[attempt] = charge
        total++
        if (r.proof.viaGrab) grabTotal++
        last = "${r.candidate.lane} ${r.candidate.mint.take(10)} ${r.proof.reason}"
        return charge
    }

    @Synchronized fun release(attempt: String): Boolean = reserved.remove(attempt) != null
    @Synchronized fun counts(): Pair<Long, Long> = total to grabTotal
    @Synchronized fun last(): String = last
    @Synchronized fun reservations(): Int = reserved.size
    @Synchronized fun clear() { proofs.clear(); reserved.clear(); submitted.clear(); total = 0L; grabTotal = 0L; last = "-" }

    private fun trim(nowMs: Long) {
        proofs.entries.removeIf { nowMs - it.value.atMs > proofTtlMs }
        submitted.entries.removeIf { nowMs - it.value.submittedAtMs >= 3_600_000L }
    }
}
