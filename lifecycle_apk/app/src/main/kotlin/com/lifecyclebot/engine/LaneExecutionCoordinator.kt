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
    private val affinities = ConcurrentHashMap<String, Set<String>>()

    // V5.9.1135 — lane election must be priority-based, not first-caller-wins.
    // 3102 showed TREASURY evaluating first in BotService and becoming primary
    // for fresh meme candidates, after which SHITCOIN/MOONSHOT/MANIP/DIP got
    // LANE_PREAUTH_SUPPRESSED. That starves the exact specialist lanes that had
    // been carrying ~40% WR the prior night. Treasury is still allowed to trade
    // when no specialist requests the book; it just cannot steal the book solely
    // because it ran earlier in the loop.
    private val lanePriority = mapOf(
        "MOONSHOT" to 100,
        "SHITCOIN" to 95,
        "MANIPULATED" to 90,
        "DIP_HUNTER" to 85,
        "PROJECT_SNIPER" to 80,
        "CRYPTO" to 75,
        "QUALITY" to 70,
        "BLUECHIP" to 60,
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
        val existing = elections[mapKey]?.takeIf { now - it.createdAtMs <= TTL_MS }
        if (existing == null && laneUpper == "TREASURY") {
            val deferred = elect(mint, listOf(laneUpper), laneUpper, candidateVersion, runtimeGeneration)
            // Treasury runs before the specialist lanes in BotService. If we allow
            // it on the first touch it can consume the one-book executable key and
            // force Moonshot/Shitcoin/Manip/Dip into telemetry-only. Defer exactly
            // the first Treasury touch; if no specialist claims this candidate,
            // Treasury will be allowed on the next pass while the election is live.
            duplicateOpenSuppressed.incrementAndGet()
            return Verdict(
                allowed = false,
                reason = "TREASURY_DEFER_SPECIALIST_FIRST primary=${deferred.primaryLane}",
                primaryLane = deferred.primaryLane,
                candidateVersion = deferred.key.candidateVersion,
            )
        }
        val e = when {
            existing == null -> elect(mint, listOf(laneUpper), laneUpper, candidateVersion, runtimeGeneration)
            effectivePriority(mint, laneUpper) > effectivePriority(mint, existing.primaryLane) -> {
                val upgraded = Election(
                    key = existing.key,
                    primaryLane = laneUpper,
                    secondaryTelemetryLane = existing.primaryLane,
                    createdAtMs = existing.createdAtMs,
                )
                elections[mapKey] = upgraded
                try {
                    ForensicLogger.lifecycle(
                        "LANE_PRIMARY_UPGRADED",
                        "mint=${mint.take(10)} from=${existing.primaryLane} to=$laneUpper candidateVersion=${existing.key.candidateVersion}"
                    )
                } catch (_: Throwable) {}
                upgraded
            }
            else -> existing
        }
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

    /**
     * V5.9.1138 — release a primary election when the winning lane fails before
     * actually opening a trade. Without this, the first primary lane can fail
     * FDG/finality/buy-open and still suppress every other lane for the full
     * candidate window, making runtime look like "only one layer trades".
     * This is fail-open for learning: it only releases when the caller is the
     * current primary for that mint/window.
     */
    fun releaseIfPrimary(
        mint: String,
        lane: String,
        reason: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Boolean {
        val laneUpper = lane.uppercase()
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val current = elections[mapKey] ?: return false
        if (current.primaryLane != laneUpper) return false
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
    }

    private fun mapKey(key: CandidateKey): String = "${key.runtimeGeneration}:${key.mint}:${key.candidateVersion}"

    private fun prune(now: Long) {
        if (elections.size < 5_000) return
        elections.entries.removeIf { now - it.value.createdAtMs > TTL_MS }
    }
}
