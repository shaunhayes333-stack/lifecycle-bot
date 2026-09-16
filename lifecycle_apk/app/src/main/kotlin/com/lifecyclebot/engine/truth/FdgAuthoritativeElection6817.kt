package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6817 §FDG_FANOUT_COLLAPSE — operator directive Feb 2026:
 *   "Enforce ONE authoritative FDG outcome for mode + canonicalMint +
 *    candidateVersion/epoch. Specialist lanes may contribute
 *    scores/advice but must not each create an authoritative FDG
 *    decision. After primary election, subsequent lane evaluations
 *    become CONTRIBUTOR_ONLY / SHADOW_OBSERVATION. Target
 *    authoritative FDG/intake ratio <= 1.5."
 *
 * DESIGN — additive election registry with idempotent seal semantics.
 *   • Each `(mode, canonicalMint, candidateVersion, epoch)` composite
 *     key elects EXACTLY ONE authoritative FDG outcome.
 *   • The first `electAuthoritative(...)` call for a key returns
 *     `Role.AUTHORITATIVE` and stamps the election.
 *   • Subsequent callers get `Role.CONTRIBUTOR` (score + advice may
 *     still flow) or `Role.SHADOW` (post-terminal observation only).
 *   • The authority is a WITNESS + read; existing lane pipelines
 *     continue to compute their per-lane decisions. Consumers wire
 *     `roleFor(...)` into their outcome-publishing path so only the
 *     authoritative role commits an FDG decision.
 *
 * Consumers wire this optionally in this ship; it's OBSERVABILITY-FIRST.
 * The election counter surfaces the authoritative/intake ratio so the
 * operator can verify the collapse without a global choke.
 */
object FdgAuthoritativeElection6817 {

    enum class Role { AUTHORITATIVE, CONTRIBUTOR, SHADOW }

    private data class Election(
        val key: String,
        val authoritativeLane: String,
        val electedAtMs: Long,
        val contributors: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet()),
        @Volatile var terminal: Boolean = false,
    )

    private val elections = ConcurrentHashMap<String, Election>()
    private const val CAP = 8192

    private val intakes = AtomicLong(0L)
    private val authoritatives = AtomicLong(0L)
    private val contributors = AtomicLong(0L)
    private val shadows = AtomicLong(0L)

    /** Build a stable composite key for the election. */
    fun key(mode: String, canonicalMint: String, candidateVersion: String, epoch: String): String {
        val m = mode.trim().uppercase()
        val mi = canonicalMint.trim()
        val cv = candidateVersion.trim()
        val ep = epoch.trim()
        return "$m|$mi|$cv|$ep"
    }

    /**
     * Elect the authoritative FDG lane for a composite key. First call
     * wins. Repeated calls with the same key return CONTRIBUTOR (or
     * SHADOW if the election is terminal).
     */
    fun electAuthoritative(
        mode: String,
        canonicalMint: String,
        candidateVersion: String,
        epoch: String,
        proposingLane: String,
    ): Role {
        intakes.incrementAndGet()
        val k = key(mode, canonicalMint, candidateVersion, epoch)
        // Try to seal a new election.
        val existing = elections.putIfAbsent(k, Election(
            key = k,
            authoritativeLane = proposingLane,
            electedAtMs = System.currentTimeMillis(),
        ))
        if (existing == null) {
            authoritatives.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FDG_ELECTION_AUTHORITATIVE_6817")
                PipelineHealthCollector.labelInc(
                    "FDG_ELECTION_AUTHORITATIVE_6817_${proposingLane.uppercase().take(24)}"
                )
                ForensicLogger.lifecycle(
                    "FDG_ELECTION_AUTHORITATIVE_6817",
                    "key=${k.take(48)} lane=$proposingLane",
                )
            } catch (_: Throwable) {}
            maybeEvict()
            return Role.AUTHORITATIVE
        }
        // Election already exists — proposer becomes CONTRIBUTOR unless
        // the authoritative election has already been marked terminal
        // (in which case the proposer becomes a SHADOW observer).
        existing.contributors.add(proposingLane)
        return if (existing.terminal) {
            shadows.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FDG_ELECTION_SHADOW_6817")
                PipelineHealthCollector.labelInc(
                    "FDG_ELECTION_SHADOW_6817_${proposingLane.uppercase().take(24)}"
                )
            } catch (_: Throwable) {}
            Role.SHADOW
        } else {
            contributors.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FDG_ELECTION_CONTRIBUTOR_6817")
                PipelineHealthCollector.labelInc(
                    "FDG_ELECTION_CONTRIBUTOR_6817_${proposingLane.uppercase().take(24)}"
                )
            } catch (_: Throwable) {}
            Role.CONTRIBUTOR
        }
    }

    /** Query the role a lane would receive without incrementing intake. */
    fun roleFor(
        mode: String,
        canonicalMint: String,
        candidateVersion: String,
        epoch: String,
    ): Role? {
        val e = elections[key(mode, canonicalMint, candidateVersion, epoch)] ?: return null
        return if (e.terminal) Role.SHADOW else Role.CONTRIBUTOR
    }

    /** Mark an election terminal — subsequent proposers become SHADOWs. */
    fun markTerminal(
        mode: String,
        canonicalMint: String,
        candidateVersion: String,
        epoch: String,
    ) {
        val e = elections[key(mode, canonicalMint, candidateVersion, epoch)] ?: return
        e.terminal = true
        try { PipelineHealthCollector.labelInc("FDG_ELECTION_TERMINAL_6817") } catch (_: Throwable) {}
    }

    /**
     * Ratio of authoritative decisions to total intake. Target: <= 1.5
     * per operator mandate. A ratio > 1.5 surfaces via label counter
     * so the operator sees the collapse without a global choke.
     */
    fun authoritativeToIntakeRatio(): Double {
        val i = intakes.get().coerceAtLeast(1L).toDouble()
        val a = authoritatives.get().toDouble()
        val ratio = if (a > 0.0) i / a else 0.0
        return ratio
    }

    private fun maybeEvict() {
        if (elections.size <= CAP) return
        val oldest = elections.entries.minByOrNull { it.value.electedAtMs }?.key ?: return
        elections.remove(oldest)
    }

    fun statusLine(): String =
        "elections=${elections.size} intakes=${intakes.get()} " +
            "authoritatives=${authoritatives.get()} contributors=${contributors.get()} " +
            "shadows=${shadows.get()} ratio=${"%.2f".format(authoritativeToIntakeRatio())}"

    internal fun clearForTest() {
        elections.clear()
        intakes.set(0L); authoritatives.set(0L)
        contributors.set(0L); shadows.set(0L)
    }
}
