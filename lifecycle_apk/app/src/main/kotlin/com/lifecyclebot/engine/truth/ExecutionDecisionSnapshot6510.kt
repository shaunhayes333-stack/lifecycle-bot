package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

data class ExecutionDecisionSnapshot(
    val mint: String, val candidateVersion: Long, val verdict: String,
    val executionLane: String, val score: Double, val generatedAtMs: Long,
)

object ExecutionDecisionSnapshot6510 {
    private val byMint = ConcurrentHashMap<String, ExecutionDecisionSnapshot>()
    fun record(snapshot: ExecutionDecisionSnapshot) { byMint[snapshot.mint] = snapshot }
    fun get(mint: String): ExecutionDecisionSnapshot? = byMint[mint]
    fun consume(mint: String, currentVersion: Long, currentVerdict: String, currentLane: String): ExecutionDecisionSnapshot? {
        val old = byMint[mint] ?: return null
        if (currentVersion <= 0L || old.candidateVersion == currentVersion) return old
        val executable = currentVerdict in setOf("BUY", "PROBE_ONLY") && currentLane.equals(old.executionLane, true)
        try {
            ForensicLogger.lifecycle("EXEC_DECISION_VERSION_REVALIDATED_6510", "mint=${mint.take(10)} oldVersion=${old.candidateVersion} newVersion=$currentVersion oldVerdict=${old.verdict} newVerdict=$currentVerdict oldLane=${old.executionLane} newLane=$currentLane result=${if (executable) "REFRESH" else "CANCEL"}")
            PipelineHealthCollector.labelInc(if (executable) "EXEC_DECISION_VERSION_REFRESHED_6510" else "FDG_BUY_TO_EXEC_SIGNAL_DIVERGENCE")
        } catch (_: Throwable) {}
        if (!executable) return null
        return old.copy(candidateVersion = currentVersion, verdict = currentVerdict, generatedAtMs = System.currentTimeMillis()).also { byMint[mint] = it }
    }
    internal fun resetForTest() = byMint.clear()
}
