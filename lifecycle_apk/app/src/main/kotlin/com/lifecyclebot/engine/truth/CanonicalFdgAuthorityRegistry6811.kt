package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6811 §AUTHORITY_CONSOLIDATION — one authoritative FDG decision per
 * (mode, mint, candidateVersion). Sibling lanes may continue scoring, learning
 * and telemetry; they MUST NOT re-enter the authoritative FDG boundary.
 *
 * Operator diagnosis Feb 2026:
 *   "One candidate/version is still reaching multiple authority-bearing lane/
 *    FDG paths after canonical ownership has already been selected. This
 *    causes FDG fan-out, contradictory sibling-lane decisions, repeated
 *    sizing, stale/frozen intent failures, and ticket loss."
 *
 * Example fan-out (V5.0.6810 snapshot):
 *   PROJECT_SNIPER: FDG BUY → immutable intent created
 *   STANDARD:       attempts execution without valid frozen FDG/intent →
 *                   FINALITY_BLOCKED_FROZEN_SNAPSHOT_NEEDS_REVALIDATION
 *   SHITCOIN:       runs another FDG/policy decision → POLICY_NEG_EV_BLOCK_6801
 *
 * The registry seals the first successful authoritative FDG for a
 * (mode, mint, candidateVersion) and rejects subsequent claims from other
 * lanes with an explicit `FDG_AUTH_DUPLICATE_SUPPRESSED_6811` counter.
 *
 * The seal carries the sealed executable notional so downstream sizing can
 * validate that no adaptive re-sizing has silently mutated the amount inside
 * the same intent lineage. Callers that legitimately need to revalidate
 * (material policy change, hard-safety invariant, cash below sealed) must
 * `invalidate()` the seal first and generate a new candidateVersion — silent
 * mutation is not permitted.
 */
object CanonicalFdgAuthorityRegistry6811 {

    /** Seals expire after this window if never consumed by intent creation.
     *  Wide enough to cover the fdg→intent→size→ticket pipeline, tight
     *  enough that a truly abandoned seal cannot linger forever. */
    private const val TTL_MS = 90_000L

    data class Seal(
        val mode: String,
        val mint: String,
        val candidateVersion: Long,
        val canonicalLane: String,
        val decisionId: String,
        val sealedNotional: Double,
        val sealedAtMs: Long,
        val authorityVersion: Long,
    )

    private val seals = ConcurrentHashMap<String, Seal>()
    private val claimAccepted = AtomicLong(0L)
    private val claimSuppressed = AtomicLong(0L)
    private val invalidations = AtomicLong(0L)

    private fun key(mode: String, mint: String, candidateVersion: Long): String =
        "${mode.uppercase()}|${mint}|$candidateVersion"

    /**
     * Attempt to claim authoritative FDG ownership for
     * (mode, mint, candidateVersion). Returns:
     *   • Result.Accepted(seal)  — this call owns the authoritative path
     *   • Result.Duplicate(existing) — another lane already owns it; caller
     *                                  MUST NOT enter authoritative FDG,
     *                                  MUST NOT create/reuse executable intent,
     *                                  MUST NOT create tickets.
     * A duplicate result increments FDG_AUTH_DUPLICATE_SUPPRESSED_6811 and
     * logs the attempt for forensic audit.
     */
    sealed class Result {
        data class Accepted(val seal: Seal) : Result()
        data class Duplicate(val existing: Seal, val attemptedLane: String) : Result()
    }

    fun claim(
        mode: String,
        mint: String,
        candidateVersion: Long,
        canonicalLane: String,
        decisionId: String,
        sealedNotional: Double,
        authorityVersion: Long,
    ): Result {
        if (mode.isBlank() || mint.isBlank() || candidateVersion <= 0L) {
            return Result.Accepted(
                Seal(mode, mint, candidateVersion, canonicalLane, decisionId,
                    sealedNotional, System.currentTimeMillis(), authorityVersion)
            )
        }
        val k = key(mode, mint, candidateVersion)
        val fresh = Seal(
            mode = mode.uppercase(),
            mint = mint,
            candidateVersion = candidateVersion,
            canonicalLane = CanonicalLaneIdentity6506.canonical(canonicalLane),
            decisionId = decisionId.take(64),
            sealedNotional = sealedNotional,
            sealedAtMs = System.currentTimeMillis(),
            authorityVersion = authorityVersion,
        )
        val existing = seals.putIfAbsent(k, fresh)
        if (existing == null) {
            claimAccepted.incrementAndGet()
            return Result.Accepted(fresh)
        }
        // A seal already exists for this (mode, mint, candidateVersion).
        if (System.currentTimeMillis() - existing.sealedAtMs > TTL_MS) {
            // Existing seal is stale; supersede.
            seals.replace(k, existing, fresh)
            claimAccepted.incrementAndGet()
            return Result.Accepted(fresh)
        }
        // Same lane re-entering (idempotent) is accepted without incrementing
        // the duplicate counter. Different lane attempting → suppressed.
        if (existing.canonicalLane.equals(fresh.canonicalLane, ignoreCase = true)) {
            return Result.Accepted(existing)
        }
        claimSuppressed.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("FDG_AUTH_DUPLICATE_SUPPRESSED_6811")
            ForensicLogger.lifecycle(
                "FDG_AUTH_DUPLICATE_SUPPRESSED_6811",
                "mode=${fresh.mode} mint=${fresh.mint.take(10)} " +
                    "candidateVersion=${fresh.candidateVersion} " +
                    "canonicalLane=${existing.canonicalLane} " +
                    "attemptedLane=${fresh.canonicalLane} " +
                    "existingDecisionId=${existing.decisionId} " +
                    "action=shadow_only_no_authoritative_fdg",
            )
        } catch (_: Throwable) {}
        return Result.Duplicate(existing, fresh.canonicalLane)
    }

    /** Peek without claim. Returns the current seal if fresh, else null. */
    fun peek(mode: String, mint: String, candidateVersion: Long): Seal? {
        if (mode.isBlank() || mint.isBlank() || candidateVersion <= 0L) return null
        val s = seals[key(mode, mint, candidateVersion)] ?: return null
        if (System.currentTimeMillis() - s.sealedAtMs > TTL_MS) {
            seals.remove(key(mode, mint, candidateVersion), s)
            return null
        }
        return s
    }

    /**
     * Explicit invalidation of the seal — callers use this when a MATERIAL
     * policy change forces a new candidateVersion / new authoritative FDG.
     * Sibling lanes cannot invalidate; only the authoritative owner or an
     * upstream invariant recovery may invalidate.
     */
    fun invalidate(mode: String, mint: String, candidateVersion: Long, byLane: String, reason: String) {
        val s = seals.remove(key(mode, mint, candidateVersion)) ?: return
        invalidations.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("FDG_AUTH_SEAL_INVALIDATED_6811")
            ForensicLogger.lifecycle(
                "FDG_AUTH_SEAL_INVALIDATED_6811",
                "mode=${s.mode} mint=${s.mint.take(10)} candidateVersion=${s.candidateVersion} " +
                    "priorLane=${s.canonicalLane} byLane=${CanonicalLaneIdentity6506.canonical(byLane)} " +
                    "reason=$reason",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "accepted=${claimAccepted.get()} suppressed=${claimSuppressed.get()} " +
            "invalidated=${invalidations.get()} liveSeals=${seals.size}"

    /** Test-only reset. */
    internal fun clearForTest() {
        seals.clear()
        claimAccepted.set(0L); claimSuppressed.set(0L); invalidations.set(0L)
    }
}
