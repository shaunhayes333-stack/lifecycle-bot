package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6441 §4 — SAME-MINT DEDUP FIXED AT SOURCE.
 *
 * OPERATOR MANDATE §4:
 *   "Coalesce by canonical mint BEFORE repeated lane/V3/FDG/EXEC entry
 *    work. Merge scanner provenance into one candidate envelope/sourceSet.
 *    If mint already OPEN: update mark/position/exit intelligence only;
 *    do not create another entry candidate. Preserve intentional
 *    re-entry only after canonical CLOSED + cooldown rules. Remove
 *    duplicate suppression implementations downstream once upstream
 *    ownership is authoritative. Scanner dedup telemetry must count
 *    actual raw/coalesced/saved work."
 *
 * DESIGN
 * ──────
 * shouldCreateEntryCandidate(mint, source, nowMs) returns:
 *   ACCEPT  — mint is fresh (not OPEN); the caller may create a candidate.
 *   COALESCE — mint already has a candidate in-flight this cycle; caller
 *              merges its source into the existing envelope and DOES NOT
 *              create a new candidate.
 *   BLOCK    — mint is already OPEN (canonical). Caller must route to
 *              exit / mark-update path instead of entry-work.
 *   REENTRY_LOCKOUT — mint recently CLOSED and still in the intentional
 *                     re-entry cooldown window.
 *
 * The gate is stateless across cycles — beginCycle() clears the in-flight
 * candidate map. Callers do not need to synchronise on the returned
 * envelope; this module owns the state.
 */
object SameMintDedupAuthority6441 {

    enum class Decision { ACCEPT, COALESCE, BLOCK, REENTRY_LOCKOUT }

    data class CandidateEnvelope(
        val mint: String,
        val sources: Set<String>,
        val firstSeenMs: Long,
    )

    private const val REENTRY_COOLDOWN_MS = 90_000L

    private val inFlight = ConcurrentHashMap<String, CandidateEnvelope>()

    private val accepts = AtomicLong(0L)
    private val coalesces = AtomicLong(0L)
    private val blocks = AtomicLong(0L)
    private val lockouts = AtomicLong(0L)
    private val rawCandidates = AtomicLong(0L)

    fun beginCycle() {
        inFlight.clear()
    }

    fun shouldCreateEntryCandidate(mint: String, source: String, nowMs: Long = System.currentTimeMillis()): Decision {
        rawCandidates.incrementAndGet()
        if (CanonicalPositionAuthority6441.hasOpenMint(mint)) {
            blocks.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SAME_MINT_BLOCK_OPEN_6441") } catch (_: Throwable) {}
            return Decision.BLOCK
        }
        // Re-entry lockout: recently closed positions (canonical) can't
        // be re-opened within REENTRY_COOLDOWN_MS.
        val recentlyClosed = CanonicalPositionAuthority6441.closedPositions().any {
            it.mint == mint && (nowMs - it.lastMutationMs) < REENTRY_COOLDOWN_MS
        }
        if (recentlyClosed) {
            lockouts.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SAME_MINT_REENTRY_LOCKOUT_6441") } catch (_: Throwable) {}
            return Decision.REENTRY_LOCKOUT
        }
        val existing = inFlight[mint]
        if (existing != null) {
            inFlight[mint] = existing.copy(sources = existing.sources + source)
            coalesces.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SAME_MINT_COALESCED_6441") } catch (_: Throwable) {}
            return Decision.COALESCE
        }
        inFlight[mint] = CandidateEnvelope(mint = mint, sources = setOf(source), firstSeenMs = nowMs)
        accepts.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SAME_MINT_ACCEPTED_6441") } catch (_: Throwable) {}
        return Decision.ACCEPT
    }

    fun envelopeOf(mint: String): CandidateEnvelope? = inFlight[mint]

    fun statusLine(): String =
        "raw=${rawCandidates.get()} accepts=${accepts.get()} coalesces=${coalesces.get()} " +
            "blocks=${blocks.get()} lockouts=${lockouts.get()} inFlightNow=${inFlight.size}"
}
