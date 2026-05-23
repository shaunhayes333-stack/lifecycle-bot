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
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val primaryLane: String,
        val candidateVersion: Long,
    )

    private const val TTL_MS = 30_000L
    private val versionSeq = AtomicLong(0L)
    private val elections = ConcurrentHashMap<String, Election>()
    private val duplicateOpenSuppressed = AtomicLong(0L)

    fun candidateVersionFor(mint: String): Long {
        // 15-30s window bucket + monotonic suffix avoids same-second duplicate opens
        // while still letting genuinely new candidate data re-elect shortly after.
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
        val e = Election(key, primary, secondary, now)
        elections[mapKey] = e
        prune(now)
        return e
    }

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
        val e = elections[mapKey]
            ?.takeIf { now - it.createdAtMs <= TTL_MS }
            ?: elect(mint, listOf(laneUpper), laneUpper, candidateVersion, runtimeGeneration)
        val allowed = e.primaryLane == laneUpper
        if (!allowed) duplicateOpenSuppressed.incrementAndGet()
        return Verdict(
            allowed = allowed,
            reason = if (allowed) "LANE_PRIMARY_ELECTED" else "LANE_TELEMETRY_ONLY primary=${e.primaryLane}",
            primaryLane = e.primaryLane,
            candidateVersion = e.key.candidateVersion,
        )
    }

    fun duplicateOpenSuppressions(): Long = duplicateOpenSuppressed.get()

    fun resetForTests() {
        elections.clear()
        duplicateOpenSuppressed.set(0L)
        versionSeq.set(0L)
    }

    private fun mapKey(key: CandidateKey): String = "${key.runtimeGeneration}:${key.mint}:${key.candidateVersion}"

    private fun prune(now: Long) {
        if (elections.size < 5_000) return
        elections.entries.removeIf { now - it.value.createdAtMs > TTL_MS }
    }
}
