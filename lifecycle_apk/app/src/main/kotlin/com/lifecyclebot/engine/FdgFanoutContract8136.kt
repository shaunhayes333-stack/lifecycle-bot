package com.lifecyclebot.engine

/**
 * V5.0.8136 — the FDG fanout measurement contract.
 *
 * InvariantGuardian divided "unique FDG decisions" by intake, but when the unique counter was zero (a path that never
 * sealed through FdgReEvalThrottle, or a fresh runtime) it substituted the broad gate allow+block count and still
 * labelled the ratio FDG_unique — a fallback counter raised FDG_FANOUT_EXPLOSION. The counters are now separate and
 * only the unique-decision counter can fault; a fallback count is telemetry. Pure.
 */
object FdgFanoutContract8136 {

    data class Counts(
        /** distinct (mode, mint, candidateVersion) that reached a sealed decision */
        val distinctCandidates: Long,
        /** lane decisions summed over the tracked candidates */
        val laneDecisions: Long,
        /** candidates currently tracked for the per-candidate lane mean */
        val trackedCandidates: Long,
        /** unique (mode, mint, candidateVersion, lane, evidenceVersion) decisions */
        val uniqueDecisions: Long,
        /** the same identity evaluated again with unchanged evidence */
        val unchangedRepeats: Long,
        /** a sealed verdict reused inside its TTL */
        val cacheHits: Long,
        /** same candidate and lane re-decided on new evidence */
        val superseded: Long,
        /** verdicts sealed into the throttle */
        val finalSealed: Long,
    ) {
        val laneDecisionsPerCandidate: Double
            get() = if (trackedCandidates > 0L) laneDecisions.toDouble() / trackedCandidates else 0.0
    }

    /** The only number FDG_FANOUT_EXPLOSION may use. */
    const val SOURCE_UNIQUE_8136 = "UNIQUE_DECISIONS"
    const val SOURCE_FALLBACK_8136 = "FALLBACK_TELEMETRY_ONLY"

    fun source8136(uniqueDecisions: Long): String = if (uniqueDecisions > 0L) SOURCE_UNIQUE_8136 else SOURCE_FALLBACK_8136

    /**
     * Pure: unique decisions per candidate, the ratio the fanout fault reads. 0.0 when the unique counter has no sample
     * (a fallback gate count can never fault). The denominator is the larger of intake and the distinct candidates
     * that reached a decision: a candidate restored without an intake pulse is still one candidate.
     */
    fun faultRatio8136(uniqueDecisions: Long, intake: Long, distinctCandidates: Long = 0L): Double {
        if (uniqueDecisions <= 0L) return 0.0
        val denom = maxOf(intake, distinctCandidates)
        if (denom <= 0L) return 0.0
        return uniqueDecisions.toDouble() / denom
    }

    fun line8136(c: Counts): String =
        "fanoutContract8136 distinctCandidates=${c.distinctCandidates} laneDecisionsPerCandidate=${"%.2f".format(c.laneDecisionsPerCandidate)} " +
            "unique=${c.uniqueDecisions} unchangedRepeats=${c.unchangedRepeats} cacheHits=${c.cacheHits} " +
            "superseded=${c.superseded} finalSealed=${c.finalSealed}"
}
