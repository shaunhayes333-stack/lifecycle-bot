package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1099-pre — per-runtime candidate/lane election guard.
 *
 * This is the execution-side half of the lane fan-out repair: for a given
 * runtimeGeneration + mint + candidateVersion, exactly one primary lane may
 * request execution. Other lanes may continue telemetry, but central gates
 * block TradeAuthorizer/FinalExecutionPermit/Executor side effects.
 */
object LaneExecutionCoordinator {
    data class CandidateKey(
        val runtimeGeneration: Long,
        val mint: String,
        val candidateVersion: Long,
    )

    data class Election(
        val key: CandidateKey,
        val primaryLane: String,
        val secondaryTelemetryLane: String? = null,
        val createdAtMs: Long = System.currentTimeMillis(),
        val electionId: String = "",
        val authorityVersion: Long = 0L,
        val sealed: Boolean = false,
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val primaryLane: String,
        val candidateVersion: Long,
        val electionId: String = "",
        val authorityVersion: Long = 0L,
    )

    private const val TTL_MS = 30_000L
    private val versionSeq = AtomicLong(0L)
    private val authoritySeq6494 = AtomicLong(0L)
    private val elections = ConcurrentHashMap<String, Election>()
    private val duplicateOpenSuppressed = AtomicLong(0L)
    private val affinities = ConcurrentHashMap<String, Set<String>>()

    // V5.9.1135 — lane election must be priority-based, not first-caller-wins.
    private val lanePriority = mapOf(
        "MOONSHOT" to 100,
        "SHITCOIN" to 95,
        "EXPRESS" to 93,
        "MANIPULATED" to 90,
        "DIP_HUNTER" to 85,
        "PROJECT_SNIPER" to 80,
        "CRYPTO" to 75,
        "QUALITY" to 70,
        "BLUECHIP" to 60,
        "V3" to 58,
        "STANDARD" to 56,
        "CORE" to 55,
        "TREASURY" to 40,
        "SHADOW" to 10,
    )

    private fun priority(lane: String): Int = lanePriority[lane.uppercase()] ?: 50

    fun registerAffinity(mint: String, lanes: Set<String>) {
        val clean = lanes.map { it.uppercase() }.filter { it.isNotBlank() }.toSet()
        if (clean.isEmpty()) return
        affinities.merge(mint, clean) { old, new -> old + new }
    }

    private fun effectivePriority(mint: String, lane: String): Int {
        val laneUpper = lane.uppercase()
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val allAffinity = (affinities[mint] ?: emptySet()) + registryAffinity
        val boost = if (allAffinity.contains(laneUpper)) 30 else 0
        return priority(laneUpper) + boost
    }

    // ── FAIR LANE ROTATION (V5.9.1335) ───────────────────────────────
    // Fairness remains available for pre-seal/fresh elections. Once FDG has
    // published an immutable ExecutionDecisionSnapshot, V5.0.6679 binds this
    // coordinator to that sealed owner instead of re-running any local contest.
    private const val FAIRNESS_DECAY_MS = 120_000L
    private const val FAIRNESS_LEAD_GRACE = 3
    private const val FAIRNESS_PER_LEAD = 6.0
    private val laneWinTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun recordPrimaryWin(lane: String) {
        val l = lane.uppercase()
        val now = System.currentTimeMillis()
        val dq = laneWinTimestamps.computeIfAbsent(l) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(now)
            while (dq.isNotEmpty() && now - dq.first() > FAIRNESS_DECAY_MS) dq.removeFirst()
        }
    }

    private fun recentWins(lane: String): Int {
        val dq = laneWinTimestamps[lane.uppercase()] ?: return 0
        val now = System.currentTimeMillis()
        synchronized(dq) {
            while (dq.isNotEmpty() && now - dq.first() > FAIRNESS_DECAY_MS) dq.removeFirst()
            return dq.size
        }
    }

    private fun minRecentWinsAcross(lanes: Collection<String>): Int =
        lanes.minOfOrNull { recentWins(it) } ?: 0

    private fun claimPriority(mint: String, lane: String, qualified: Collection<String>): Int = try {
        val floor = minRecentWinsAcross(qualified)
        val lead = (recentWins(lane) - floor - FAIRNESS_LEAD_GRACE).coerceAtLeast(0)
        effectivePriority(mint, lane) - (lead * FAIRNESS_PER_LEAD).toInt()
    } catch (_: Throwable) { effectivePriority(mint, lane) }

    private fun pickFreshPrimary(mint: String, qualified: List<String>): String? {
        if (qualified.isEmpty()) return null
        return qualified.maxByOrNull { claimPriority(mint, it, qualified) }
    }

    private fun qualifiedLanesFor(mint: String, vararg contesting: String): List<String> {
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val all = ((affinities[mint] ?: emptySet()) + registryAffinity + contesting.map { it.uppercase() })
            .filter { it.isNotBlank() }
        return if (all.isEmpty()) contesting.map { it.uppercase() } else all.toList()
    }

    fun candidateVersionFor(mint: String): Long {
        val bucket = System.currentTimeMillis() / TTL_MS
        return bucket
    }

    fun elect(
        mint: String,
        lanes: List<String>,
        preferred: String? = null,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Election {
        val clean = lanes.map { it.uppercase() }.filter { it.isNotBlank() }
        val primary = (preferred?.uppercase()?.takeIf { it in clean } ?: clean.firstOrNull() ?: "CORE")
        val secondary = clean.firstOrNull { it != primary }
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val now = System.currentTimeMillis()
        val old = elections[mapKey]
        if (old != null && now - old.createdAtMs <= TTL_MS) return old
        val authorityVersion6494 = authoritySeq6494.incrementAndGet()
        val e = Election(
            key = key,
            primaryLane = primary,
            secondaryTelemetryLane = secondary,
            createdAtMs = now,
            electionId = "${runtimeGeneration}:${candidateVersion}:$authorityVersion6494",
            authorityVersion = authorityVersion6494,
        )
        elections[mapKey] = e
        prune(now)
        return e
    }

    fun currentElection6600(
        mint: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Election? {
        val now = System.currentTimeMillis()
        return elections[mapKey(CandidateKey(runtimeGeneration, mint, candidateVersion))]
            ?.takeIf { now - it.createdAtMs <= TTL_MS }
    }

    /**
     * V5.0.6679 §SEALED_FDG_OWNER_BEFORE_CALLER_ORDER.
     *
     * The V5.0.6614 implementation assumed canonicalCycleLaneFor had already
     * elected the strongest specialist, but the coordinator did not actually
     * consume that authority. On a fresh key it simply elected `listOf(laneUpper)`,
     * making the first wrapper caller the immutable owner. Runtime 5.0.5720
     * captured the failure directly: FDG sealed PROJECT_SNIPER, then a CORE
     * TradeAuthorizer wrapper reached this method first, CORE won the election,
     * claimed the mint/version, and the true PROJECT_SNIPER attempt was later
     * suppressed by ONE_EXECUTABLE_BUY_PER_MINT_VERSION.
     *
     * Once FDG has a canonical decision for this exact mint/version/mode, caller
     * order has zero authority. Bind the election to ExecutionDecisionSnapshot6510.
     * If no sealed FDG snapshot exists yet, preserve the legacy pre-seal behavior;
     * nothing is fabricated and no lane is disabled.
     *
     * Do not re-elect it here using static priority once a sealed FDG owner exists;
     * the sealed specialist decision is the causal execution authority.
     */
    private fun sealedFdgOwnerLane6679(mint: String, candidateVersion: Long): String? = try {
        val mode6679 = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
            .currentForMint(mint, candidateVersion, mode6679)
            ?.executionLane
            ?.trim()
            ?.uppercase()
            ?.takeIf {
                it.isNotBlank() && it !in setOf("UNKNOWN", "STANDARD", "V3_CORE", "SHADOW")
            }
    } catch (_: Throwable) { null }

    fun canRequestExecution(
        mint: String,
        lane: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Verdict {
        val laneUpper = lane.uppercase()
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val now = System.currentTimeMillis()
        val existing = elections[mapKey]?.takeIf { now - it.createdAtMs <= TTL_MS }

        val sealedFdgOwner6679 = if (existing == null) sealedFdgOwnerLane6679(mint, candidateVersion) else null
        val e = existing ?: if (sealedFdgOwner6679 != null) {
            if (sealedFdgOwner6679 != laneUpper) {
                try {
                    PipelineHealthCollector.labelInc("LANE_CALLER_DEFERRED_TO_SEALED_FDG_OWNER_6679")
                    ForensicLogger.lifecycle(
                        "LANE_ELECTION_BOUND_TO_SEALED_FDG_6679",
                        "mint=${mint.take(10)} version=$candidateVersion caller=$laneUpper sealedOwner=$sealedFdgOwner6679 action=caller_order_has_no_authority",
                    )
                } catch (_: Throwable) {}
            }
            try { PipelineHealthCollector.labelInc("LANE_ELECTION_BOUND_TO_SEALED_FDG_6679") } catch (_: Throwable) {}
            elect(
                mint = mint,
                lanes = listOf(sealedFdgOwner6679),
                preferred = sealedFdgOwner6679,
                candidateVersion = candidateVersion,
                runtimeGeneration = runtimeGeneration,
            )
        } else {
            // Pre-FDG compatibility: if no canonical snapshot exists yet, keep the
            // existing claimant behavior. The later sealed FDG path is authoritative.
            elect(
                mint = mint,
                lanes = listOf(laneUpper),
                preferred = laneUpper,
                candidateVersion = candidateVersion,
                runtimeGeneration = runtimeGeneration,
            )
        }

        val allowed = e.primaryLane == laneUpper
        val finalElection6494 = if (allowed && !e.sealed) {
            e.copy(sealed = true).also { elections[mapKey] = it }
        } else e
        if (!allowed) duplicateOpenSuppressed.incrementAndGet()
        return Verdict(
            allowed = allowed,
            reason = if (allowed) "LANE_PRIMARY_ELECTED" else "LANE_TELEMETRY_ONLY primary=${finalElection6494.primaryLane}",
            primaryLane = finalElection6494.primaryLane,
            candidateVersion = finalElection6494.key.candidateVersion,
            electionId = finalElection6494.electionId,
            authorityVersion = finalElection6494.authorityVersion,
        )
    }

    fun duplicateOpenSuppressions(): Long = duplicateOpenSuppressed.get()

    fun releaseIfPrimary(
        mint: String,
        lane: String,
        reason: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Boolean {
        val laneUpper = lane.uppercase()
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        var mapKey = mapKey(key)
        var current = elections[mapKey]
        if (current == null) {
            val now = System.currentTimeMillis()
            val active = elections.entries
                .filter { (_, e) -> e.key.runtimeGeneration == runtimeGeneration && e.key.mint == mint && now - e.createdAtMs <= TTL_MS }
                .maxByOrNull { it.value.createdAtMs }
            if (active != null) {
                mapKey = active.key
                current = active.value
            }
        }
        if (current == null) {
            ChokeReliefBus.launch("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4421", mint) {
                try { PipelineHealthCollector.labelInc("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419_MISSING_ELECTION") } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419",
                        "mint=${mint.take(10)} lane=$laneUpper candidateVersion=$candidateVersion reason=$reason outcome=MISSING_ELECTION via=ChokeReliefBus"
                    )
                } catch (_: Throwable) {}
            }
            return false
        }
        if (current.primaryLane != laneUpper) {
            ChokeReliefBus.launch("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4421", mint) {
                try { PipelineHealthCollector.labelInc("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419_NOT_PRIMARY") } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419",
                        "mint=${mint.take(10)} lane=$laneUpper primary=${current.primaryLane} candidateVersion=${current.key.candidateVersion} reason=$reason outcome=NOT_PRIMARY via=ChokeReliefBus"
                    )
                } catch (_: Throwable) {}
            }
            return false
        }
        val removed = elections.remove(mapKey, current)
        if (removed) {
            try {
                ForensicLogger.lifecycle(
                    "LANE_PRIMARY_RELEASED",
                    "mint=${mint.take(10)} lane=$laneUpper candidateVersion=${current.key.candidateVersion} reason=$reason"
                )
            } catch (_: Throwable) {}
        }
        return removed
    }

    fun resetForTests() {
        elections.clear()
        affinities.clear()
        duplicateOpenSuppressed.set(0L)
        versionSeq.set(0L)
        authoritySeq6494.set(0L)
        laneWinTimestamps.clear()
    }

    private fun mapKey(key: CandidateKey): String = "${key.runtimeGeneration}:${key.mint}:${key.candidateVersion}"

    private fun prune(now: Long) {
        if (elections.size < 5_000) return
        elections.entries.removeIf { now - it.value.createdAtMs > TTL_MS }
    }
}
