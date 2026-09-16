package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6817 §UNRESOLVED_OWNER_QUARANTINE — operator directive Feb 2026:
 *   "UNRESOLVED_OWNER_* outcomes remain visible diagnostically. Exclude
 *    them from StrategyExpectancy, LaneExpectancyDamper, TacticSwitcher,
 *    GrowthRewardShaper, LosingStreakReflex, UnifiedPolicyHead,
 *    ForwardOutcomeModel, MetaPolicy, LaneExitTuner,
 *    StrategyHypothesisEngine, source/lane WR. Resolve owner from
 *    immutable entry snapshot / sealed FDG provenance first. Never
 *    default unresolved ownership into STANDARD/CORE for learning."
 *
 * DESIGN — single-source name-list filter consumed by every learner.
 *   • A close event carrying an UNRESOLVED_OWNER_* tag registers via
 *     `markUnresolvedOwner(positionId, tag)`.
 *   • Every learner consults `isQuarantined(positionId)` before
 *     admitting the terminal outcome into its rolling stats.
 *   • The authority NEVER auto-defaults an unresolved owner to
 *     STANDARD/CORE. Callers that need an owner must consult
 *     `resolveOwnerFromSealedEntry(positionId, entrySnapshot)` which
 *     returns the sealed entry lane or `null` — no fallback default.
 *   • Diagnostics remain visible via `unresolvedOutcomes()` which
 *     returns the full list (bounded by CAP).
 */
object UnresolvedOwnerLearningQuarantine6817 {

    private const val CAP = 4096

    /** Canonical learner list from the operator directive. Consumers
     *  wire `isQuarantined` into their trainable admission gate. */
    val LEARNER_CONSUMERS_6817 = listOf(
        "StrategyExpectancy",
        "LaneExpectancyDamper",
        "TacticSwitcher",
        "GrowthRewardShaper",
        "LosingStreakReflex",
        "UnifiedPolicyHead",
        "ForwardOutcomeModel",
        "MetaPolicy",
        "LaneExitTuner",
        "StrategyHypothesisEngine",
        "SourceWinRate",
        "LaneWinRate",
    )

    private val quarantined = ConcurrentHashMap<String, String>() // positionId -> unresolvedTag
    private val outcomes = ConcurrentHashMap<String, Long>()  // positionId -> atMs

    private val registered = AtomicLong(0L)
    private val quarantineHits = AtomicLong(0L)
    private val resolveAttempts = AtomicLong(0L)
    private val resolveFailures = AtomicLong(0L)

    /**
     * Register an UNRESOLVED_OWNER terminal outcome. The positionId is
     * quarantined from learning; the raw diagnostic tag is retained.
     */
    fun markUnresolvedOwner(positionId: String, unresolvedTag: String) {
        if (positionId.isBlank()) return
        if (quarantined.size >= CAP) {
            val oldest = outcomes.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                quarantined.remove(oldest); outcomes.remove(oldest)
            }
        }
        val prev = quarantined.putIfAbsent(positionId, unresolvedTag.take(120))
        outcomes[positionId] = System.currentTimeMillis()
        if (prev == null) {
            registered.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("UNRESOLVED_OWNER_QUARANTINED_6817")
                PipelineHealthCollector.labelInc(
                    "UNRESOLVED_OWNER_QUARANTINED_6817_${unresolvedTag.uppercase().take(32)}"
                )
                ForensicLogger.lifecycle(
                    "UNRESOLVED_OWNER_QUARANTINED_6817",
                    "positionId=${positionId.take(24)} tag=${unresolvedTag.take(120)} " +
                        "action=diagnostic_visible_learners_excluded",
                )
            } catch (_: Throwable) {}
        }
    }

    /** Every learner consults this. True → skip trainable admission. */
    fun isQuarantined(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        val hit = quarantined.containsKey(positionId)
        if (hit) {
            quarantineHits.incrementAndGet()
            try { PipelineHealthCollector.labelInc("UNRESOLVED_OWNER_LEARNER_SKIP_6817") } catch (_: Throwable) {}
        }
        return hit
    }

    /**
     * Resolve owner FROM SEALED ENTRY SNAPSHOT only. `entrySealedLane`
     * comes from `ExecutableEntryAuthority6450` / `CanonicalEntryAuthority6540`.
     * Callers that pass blank get `null` — never a STANDARD/CORE default.
     */
    fun resolveOwnerFromSealedEntry(
        positionId: String,
        entrySealedLane: String?,
    ): String? {
        resolveAttempts.incrementAndGet()
        val lane = entrySealedLane?.trim().orEmpty()
        if (lane.isBlank()) {
            resolveFailures.incrementAndGet()
            markUnresolvedOwner(positionId, "SEALED_ENTRY_LANE_MISSING")
            try {
                PipelineHealthCollector.labelInc("UNRESOLVED_OWNER_SEALED_ENTRY_MISSING_6817")
            } catch (_: Throwable) {}
            return null
        }
        // Reject the two default sentinels the operator flagged.
        if (lane.equals("STANDARD", ignoreCase = true) || lane.equals("CORE", ignoreCase = true)) {
            resolveFailures.incrementAndGet()
            markUnresolvedOwner(positionId, "DEFAULT_LANE_REJECTED_$lane")
            try {
                PipelineHealthCollector.labelInc(
                    "UNRESOLVED_OWNER_DEFAULT_LANE_REJECTED_6817_${lane.uppercase()}"
                )
            } catch (_: Throwable) {}
            return null
        }
        return lane
    }

    fun unresolvedOutcomes(limit: Int = 20): List<Pair<String, String>> =
        quarantined.entries.take(limit).map { it.key.take(24) to it.value }

    fun statusLine(): String =
        "registered=${registered.get()} quarantined=${quarantined.size} " +
            "hits=${quarantineHits.get()} resolveAttempts=${resolveAttempts.get()} " +
            "resolveFailures=${resolveFailures.get()}"

    internal fun clearForTest() {
        quarantined.clear(); outcomes.clear()
        registered.set(0L); quarantineHits.set(0L)
        resolveAttempts.set(0L); resolveFailures.set(0L)
    }
}
