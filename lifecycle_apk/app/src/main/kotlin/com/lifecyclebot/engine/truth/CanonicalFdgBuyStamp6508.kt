package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6508 §P0-1 — CANONICAL FDG BUY STAMP.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "FDG BUY MUST BECOME IMMUTABLE EXECUTION AUTHORITY
 *    On final FDG BUY create one canonical ExecutionTicket containing:
 *      mint, mode, canonicalLane, verdict=BUY, candidateVersion,
 *      decisionId, createdAt, sizing inputs.
 *    ...
 *    If EXEC_GATE receives UNKNOWN after a canonical FDG BUY for the
 *    same decisionId/candidateVersion, classify this as an invariant
 *    violation and rebuild from the canonical decision ticket rather
 *    than silently blocking."
 *
 * DESIGN (Phase 1 — this ship)
 * ────────────────────────────
 * Full immutable ExecutionTicket structure is scheduled for V5.0.6508b.
 * This ship lands the STAMP — a per-mint record of the last canonical
 * FDG BUY (candidateVersion, decisionId, createdAt) — so EXEC_GATE can
 * assert the mismatch invariant *right now* without waiting for the
 * ticket-machine rewrite.
 *
 * Stamps live for 60 s (well past the executable-open latency budget)
 * and are cleared on the FIRST successful EXEC_OPEN_REQUEST for the
 * same mint. A stamp is treated as MATCHING when its candidateVersion
 * equals the current candidate's version OR the createdAt is within
 * the 60 s window (fallback used when the caller has lost the
 * candidateVersion in a snapshot round-trip).
 */
object CanonicalFdgBuyStamp6508 {

    private const val TTL_MS = 60_000L

    data class Stamp(
        val mint: String,
        val symbol: String,
        val canonicalLane: String,
        val decisionId: String,
        val candidateVersion: Long,
        val createdAtMs: Long,
    )

    private val stamps = ConcurrentHashMap<String, Stamp>()
    private val stampCount = AtomicLong(0L)
    private val consumeCount = AtomicLong(0L)
    private val mismatchCount = AtomicLong(0L)

    /**
     * Stamp a canonical FDG BUY. Idempotent (same decisionId → replace).
     * Called by ExecutableOpenGate.canOpenExecutablePosition when the
     * incoming candidate carries a BUY verdict.
     */
    fun stamp(
        mint: String,
        symbol: String,
        canonicalLane: String,
        decisionId: String,
        candidateVersion: Long,
    ) {
        if (mint.isBlank()) return
        stamps[mint] = Stamp(
            mint = mint,
            symbol = symbol.take(24),
            canonicalLane = CanonicalLaneIdentity6506.canonical(canonicalLane),
            decisionId = decisionId.take(64),
            candidateVersion = candidateVersion,
            createdAtMs = System.currentTimeMillis(),
        )
        stampCount.incrementAndGet()
    }

    /**
     * Peek the stamp for a mint if it exists and is fresh (< TTL_MS).
     * Non-mutating.
     */
    fun peek(mint: String): Stamp? {
        if (mint.isBlank()) return null
        val s = stamps[mint] ?: return null
        if (System.currentTimeMillis() - s.createdAtMs > TTL_MS) {
            stamps.remove(mint, s)
            return null
        }
        return s
    }

    /**
     * Consume the stamp after a successful EXEC_OPEN_REQUEST landed.
     * Idempotent.
     */
    fun consume(mint: String) {
        if (mint.isBlank()) return
        if (stamps.remove(mint) != null) consumeCount.incrementAndGet()
    }

    /**
     * Report an invariant mismatch — EXEC_GATE received a non-BUY signal
     * while a fresh canonical FDG BUY stamp is still present for the
     * same candidateVersion. Emits the loud lifecycle line + bumps the
     * mismatch counter. Non-mutating (the stamp is intentionally kept
     * so the caller / operator can trace it).
     */
    fun reportMismatch(mint: String, incomingSignal: String, incomingCandidateVersion: Long) {
        val s = peek(mint) ?: return
        mismatchCount.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "EXEC_SIGNAL_AUTHORITY_MISMATCH_6508",
                "mint=${mint.take(10)} symbol=${s.symbol} lane=${s.canonicalLane} " +
                    "stampedDecisionId=${s.decisionId} stampedCandidateVersion=${s.candidateVersion} " +
                    "stampedAgeMs=${System.currentTimeMillis() - s.createdAtMs} " +
                    "incomingSignal=${incomingSignal.ifBlank { "UNKNOWN" }} " +
                    "incomingCandidateVersion=$incomingCandidateVersion " +
                    "action=diagnostic_pending_ticket_machine_6508b",
            )
            PipelineHealthCollector.labelInc("EXEC_SIGNAL_AUTHORITY_MISMATCH_6508")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "stamped=${stampCount.get()} consumed=${consumeCount.get()} " +
            "mismatch=${mismatchCount.get()} liveStamps=${stamps.size}"

    internal fun clearForTest() {
        stamps.clear()
        stampCount.set(0L); consumeCount.set(0L); mismatchCount.set(0L)
    }
}
