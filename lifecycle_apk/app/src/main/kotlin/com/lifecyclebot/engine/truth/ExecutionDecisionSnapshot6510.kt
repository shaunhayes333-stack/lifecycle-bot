package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.BotRuntimeController
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeModeAuthority
import java.util.concurrent.ConcurrentHashMap

data class ExecutionDecisionSnapshot(
    val mint: String, val candidateVersion: Long, val verdict: String,
    val executionLane: String, val score: Double, val generatedAtMs: Long,
    val runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    val mode: String = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE",
    val authorityVersion: Long = 0L,
    val authoritativeSignal: String = "BUY",
    val safetyVerdict: String = "UNKNOWN",
    val resolvedSizeSol: Double = 0.0,
)

object ExecutionDecisionSnapshot6510 {
    private val authoritySeq6513 = java.util.concurrent.atomic.AtomicLong(0L)
    private val byAuthorityKey = ConcurrentHashMap<String, ExecutionDecisionSnapshot>()

    private fun key(mint: String, version: Long, lane: String, generation: Long, mode: String): String =
        "$generation:${mode.uppercase()}:${mint.trim()}:$version:${lane.uppercase()}"

    fun record(snapshot: ExecutionDecisionSnapshot): ExecutionDecisionSnapshot {
        val sealed = if (snapshot.authorityVersion > 0L) snapshot else snapshot.copy(authorityVersion = authoritySeq6513.incrementAndGet())
        byAuthorityKey[key(sealed.mint, sealed.candidateVersion, sealed.executionLane, sealed.runtimeGeneration, sealed.mode)] = sealed
        return sealed
    }

    fun currentForMint(mint: String, candidateVersion: Long, mode: String): ExecutionDecisionSnapshot? {
        val generation = BotRuntimeController.currentGeneration()
        return byAuthorityKey.values.asSequence()
            .filter { it.runtimeGeneration == generation && it.mode.equals(mode, true) && it.mint == mint && it.candidateVersion == candidateVersion }
            .filter { it.verdict in setOf("BUY", "PROBE_ONLY") && it.authoritativeSignal == "BUY" }
            .maxByOrNull { it.authorityVersion }
    }

    fun get(mint: String, candidateVersion: Long, executionLane: String): ExecutionDecisionSnapshot? =
        byAuthorityKey[key(mint, candidateVersion, executionLane, BotRuntimeController.currentGeneration(), if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE")]

    fun consume(mint: String, currentVersion: Long, currentVerdict: String, currentLane: String): ExecutionDecisionSnapshot? {
        val generation = BotRuntimeController.currentGeneration()
        val mode = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        val old = byAuthorityKey[key(mint, currentVersion, currentLane, generation, mode)] ?: return null
        val executable = old.verdict in setOf("BUY", "PROBE_ONLY") && old.executionLane.equals(currentLane, true)
        if (!executable) return null
        if (currentVerdict !in setOf("BUY", "PROBE_ONLY")) {
            try {
                ForensicLogger.lifecycle("EXEC_DECISION_RAW_VERDICT_DIAGNOSTIC_6512", "mint=${mint.take(10)} version=$currentVersion sealedVerdict=${old.verdict} mutableVerdict=$currentVerdict lane=${old.executionLane} action=continue_sealed_authority")
                PipelineHealthCollector.labelInc("EXEC_DECISION_RAW_VERDICT_DIAGNOSTIC_6512")
            } catch (_: Throwable) {}
        }
        return old
    }

    internal fun resetForTest() { byAuthorityKey.clear(); authoritySeq6513.set(0L) }
}
