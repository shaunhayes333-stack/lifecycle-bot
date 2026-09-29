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

    // V5.0.7346 §A_LOOKUP_BY_MINT_MUST_NOT_READ_EVERY_MINT.
    //
    // byAuthorityKey only ever grew (resetForTest was its only removal) and the
    // two by-mint queries below walked every value on every call —
    // candidateVersionFor alone has ~51 call sites, several inside per-lane and
    // per-hypothesis loops — so their cost rose for the whole uptime. Entries
    // are now also indexed by (generation, mode, mint); the queries read only
    // that bucket with the same filters and ordering. Entries from an earlier
    // runtime generation are dropped when the generation advances: every query
    // and key here is scoped to the current generation, so none could ever be
    // returned again.
    private val byMint7346 = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var indexedGeneration7346 = Long.MIN_VALUE
    private fun executableVerdict7403(verdict: String, mode: String): Boolean =
        verdict == "BUY" || (verdict == "PROBE_ONLY" && mode.equals("PAPER", true))

    private fun mintKey7346(generation: Long, mode: String, mint: String): String =
        "$generation:${mode.uppercase()}:${mint.trim()}"

    private fun cleanupGeneration7504(current: Long = BotRuntimeController.currentGeneration()) {
        if (current == indexedGeneration7346) return
        synchronized(this) {
            if (current == indexedGeneration7346) return
            indexedGeneration7346 = current
            val before7504 = byAuthorityKey.size
            byAuthorityKey.entries.removeIf { it.value.runtimeGeneration < current }
            byMint7346.keys.removeIf { (it.substringBefore(':').toLongOrNull() ?: current) < current }
            if (byAuthorityKey.size < before7504) try {
                PipelineHealthCollector.labelInc("EXEC_DECISION_OLD_GENERATION_PRUNED_7504")
            } catch (_: Throwable) {}
        }
    }

    private fun bucket7346(mint: String, mode: String): List<ExecutionDecisionSnapshot> {
        val generation7504 = BotRuntimeController.currentGeneration()
        cleanupGeneration7504(generation7504)
        val keys = byMint7346[mintKey7346(generation7504, mode, mint)] ?: return emptyList()
        return keys.mapNotNull { byAuthorityKey[it] }
    }

    private fun key(mint: String, version: Long, lane: String, generation: Long, mode: String): String =
        "$generation:${mode.uppercase()}:${mint.trim()}:$version:${lane.uppercase()}"

    fun record(snapshot: ExecutionDecisionSnapshot): ExecutionDecisionSnapshot {
        val sealed = if (snapshot.authorityVersion > 0L) snapshot else snapshot.copy(authorityVersion = authoritySeq6513.incrementAndGet())
        val k = key(sealed.mint, sealed.candidateVersion, sealed.executionLane, sealed.runtimeGeneration, sealed.mode)
        byAuthorityKey[k] = sealed
        byMint7346.computeIfAbsent(mintKey7346(sealed.runtimeGeneration, sealed.mode, sealed.mint)) {
            ConcurrentHashMap.newKeySet()
        }.add(k)
        cleanupGeneration7504(BotRuntimeController.currentGeneration())
        return sealed
    }

    fun currentForMint(mint: String, candidateVersion: Long, mode: String): ExecutionDecisionSnapshot? {
        val generation = BotRuntimeController.currentGeneration()
        return bucket7346(mint, mode).asSequence()
            .filter { it.runtimeGeneration == generation && it.mode.equals(mode, true) && it.mint == mint && it.candidateVersion == candidateVersion }
            .filter { executableVerdict7403(it.verdict, it.mode) && it.authoritativeSignal == "BUY" }
            .maxByOrNull { it.authorityVersion }
    }

    /** Keep a sealed executable generation stable across a wall-clock bucket boundary. */
    fun latestExecutableForMint7251(
        mint: String,
        mode: String,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ExecutionDecisionSnapshot? {
        val generation = BotRuntimeController.currentGeneration()
        return bucket7346(mint, mode).asSequence()
            .filter { it.runtimeGeneration == generation && it.mode.equals(mode, true) && it.mint == mint }
            .filter { executableVerdict7403(it.verdict, it.mode) && it.authoritativeSignal == "BUY" }
            .filter { it.generatedAtMs > 0L && nowMs - it.generatedAtMs in 0L..maxAgeMs }
            .maxWithOrNull(compareBy<ExecutionDecisionSnapshot> { it.generatedAtMs }.thenBy { it.authorityVersion })
    }

    fun get(mint: String, candidateVersion: Long, executionLane: String): ExecutionDecisionSnapshot? {
        val generation7504 = BotRuntimeController.currentGeneration()
        cleanupGeneration7504(generation7504)
        return byAuthorityKey[key(mint, candidateVersion, executionLane, generation7504, if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE")]
    }

    fun consume(mint: String, currentVersion: Long, currentVerdict: String, currentLane: String): ExecutionDecisionSnapshot? {
        val generation = BotRuntimeController.currentGeneration()
        val mode = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        val old = byAuthorityKey[key(mint, currentVersion, currentLane, generation, mode)] ?: return null
        val executable = executableVerdict7403(old.verdict, old.mode) &&
            old.executionLane.equals(currentLane, true)
        if (!executable) return null
        if (!(currentVerdict == "BUY" || (currentVerdict == "PROBE_ONLY" && mode.equals("PAPER", true)))) {
            try {
                ForensicLogger.lifecycle("EXEC_DECISION_RAW_VERDICT_DIAGNOSTIC_6512", "mint=${mint.take(10)} version=$currentVersion sealedVerdict=${old.verdict} mutableVerdict=$currentVerdict lane=${old.executionLane} action=continue_sealed_authority")
                PipelineHealthCollector.labelInc("EXEC_DECISION_RAW_VERDICT_DIAGNOSTIC_6512")
            } catch (_: Throwable) {}
        }
        return old
    }

    internal fun resetForTest() { byAuthorityKey.clear(); byMint7346.clear(); indexedGeneration7346 = Long.MIN_VALUE; authoritySeq6513.set(0L) }
}
