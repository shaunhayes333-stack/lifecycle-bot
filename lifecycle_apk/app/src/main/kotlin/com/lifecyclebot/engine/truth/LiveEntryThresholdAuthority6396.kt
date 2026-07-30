package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6396 — LIVE ENTRY THRESHOLD AUTHORITY.
 *
 * Single source of truth for the live score-floor. Every stage
 * (LiveEntrySafetyHold, FDG, EXEC_GATE, PRE_ENTRY_DECISION_RECORD,
 *  LIVE_ENTRY_AUTHORITY telemetry) must consult ONE immutable
 * `EntryThresholdSnapshot` per token evaluation. No stage may
 * independently recalculate or increment the floor.
 *
 * Canonical effective-score scale (V5.0.6396 replaces the obsolete
 * ~0..100 anchor of 55/56 with the practical ~0..30 scale that AATE
 * V3/FDG actually produce):
 *
 *   BASELINE       = 15
 *   CAUTION        = 17
 *   TIGHTENED      = 20
 *   POSITIVE_LANE  = 13   (a demonstrably positive-expectancy lane
 *                          may relax by no more than 2 points)
 *   ABSOLUTE_MIN   = 12
 *   ABSOLUTE_MAX   = 22
 *
 * No combination of governor / lane / personality / volatility
 * modifiers may produce a finalFloor below ABSOLUTE_MIN or above
 * ABSOLUTE_MAX. Legacy raw-scale thresholds of 50/55/56 are
 * MECHANICALLY unreachable — the [clampFloor] function rejects
 * them.
 */
object LiveEntryThresholdAuthority6396 {

    const val SCORE_SCALE_VERSION: String = "V5.0.6396.EFFECTIVE_0_30"

    const val BASELINE: Int = 15
    const val CAUTION: Int = 17
    const val TIGHTENED: Int = 20
    const val POSITIVE_LANE: Int = 13
    const val ABSOLUTE_MIN: Int = 12
    const val ABSOLUTE_MAX: Int = 22

    /** POSITIVE_LANE is a relaxation of BASELINE — at most 2 points below. */
    const val POSITIVE_LANE_MAX_RELAX: Int = 2

    enum class GovernorTier { BASELINE, CAUTION, SOFT_TIGHT, RECOVERY, TIGHTENED, HOLD }

    data class EntryThresholdSnapshot(
        val decisionId: String,
        val mint: String,
        val lane: String,
        val rawScore: Double,
        val effectiveScore: Double,
        val baseFloor: Int,
        val governorDelta: Int,
        val laneDelta: Int,
        val personalityDelta: Int,
        val volatilityDelta: Int,
        val finalFloor: Int,
        val scoreScaleVersion: String,
        val metricEpoch: Long,
        val tier: GovernorTier,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    /** Legacy raw thresholds that MUST NOT appear as an effective floor. */
    private val LEGACY_FORBIDDEN: IntRange = 40..99

    fun baseFloorFor(tier: GovernorTier): Int = when (tier) {
        GovernorTier.BASELINE   -> BASELINE
        GovernorTier.CAUTION    -> CAUTION
        GovernorTier.SOFT_TIGHT -> CAUTION
        GovernorTier.RECOVERY   -> CAUTION
        GovernorTier.TIGHTENED  -> TIGHTENED
        GovernorTier.HOLD       -> TIGHTENED
    }

    /** Clamp finalFloor into [ABSOLUTE_MIN, ABSOLUTE_MAX]. Legacy values force clamp. */
    fun clampFloor(candidateFloor: Int): Int {
        if (candidateFloor in LEGACY_FORBIDDEN) return ABSOLUTE_MAX   // 40..99 -> hard clamp to 22
        return candidateFloor.coerceIn(ABSOLUTE_MIN, ABSOLUTE_MAX)
    }

    /**
     * Compose an immutable snapshot. All deltas are combined into ONE bounded
     * result (§"MANDATORY SCORE FLOOR" rule 5). No caller may re-increment
     * finalFloor after this call — downstream stages must reuse this record.
     */
    fun snapshot(
        decisionId: String,
        mint: String,
        lane: String,
        rawScore: Double,
        effectiveScore: Double,
        governorTier: GovernorTier,
        governorDelta: Int = 0,
        laneDelta: Int = 0,
        personalityDelta: Int = 0,
        volatilityDelta: Int = 0,
        metricEpoch: Long,
    ): EntryThresholdSnapshot {
        val base = baseFloorFor(governorTier)
        // Sum first, then clamp — no stage may internally increment.
        val proposed = base + governorDelta + laneDelta + personalityDelta + volatilityDelta
        val finalFloor = clampFloor(proposed)
        return EntryThresholdSnapshot(
            decisionId = decisionId, mint = mint, lane = lane,
            rawScore = rawScore, effectiveScore = effectiveScore,
            baseFloor = base, governorDelta = governorDelta,
            laneDelta = laneDelta, personalityDelta = personalityDelta,
            volatilityDelta = volatilityDelta, finalFloor = finalFloor,
            scoreScaleVersion = SCORE_SCALE_VERSION,
            metricEpoch = metricEpoch, tier = governorTier,
        )
    }

    /**
     * Positive-expectancy lane relaxation. Callers pass BASELINE and the
     * lane-quality delta must respect POSITIVE_LANE_MAX_RELAX.
     */
    fun positiveLaneRelax(baseline: Int, relaxPoints: Int): Int {
        val relax = relaxPoints.coerceIn(0, POSITIVE_LANE_MAX_RELAX)
        return (baseline - relax).coerceAtLeast(POSITIVE_LANE)
    }

    // -------- PARITY INVARIANT ---------------------------------------------

    val parityFailures = AtomicLong(0L)

    data class ParityVerdict(val ok: Boolean, val reason: String, val disagreements: List<String>)

    /**
     * Every stage that consumes the snapshot must publish the finalFloor
     * it saw. If any two stages disagree, this returns ENTRY_THRESHOLD_
     * PARITY_FAIL and the caller must suppress THAT SINGLE entry — never
     * globally stop unrelated trading (V5.0.6396 §"CANONICAL THRESHOLD
     * AUTHORITY").
     */
    fun parityCheck(vararg stageValues: Pair<String, Int>): ParityVerdict {
        if (stageValues.isEmpty()) return ParityVerdict(true, "NO_STAGES", emptyList())
        val ref = stageValues[0].second
        val disagreements = stageValues.filter { it.second != ref }.map { "${it.first}=${it.second}" }
        if (disagreements.isEmpty()) return ParityVerdict(true, "PARITY_OK", emptyList())
        parityFailures.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_THRESHOLD_PARITY_FAIL_6396") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("ENTRY_THRESHOLD_PARITY_FAIL_6396", "ref=${stageValues[0].first}=$ref disagreements=${disagreements.joinToString(",")}") } catch (_: Throwable) {}
        return ParityVerdict(false, "ENTRY_THRESHOLD_PARITY_FAIL", disagreements)
    }

    // -------- ADMISSION VERDICT --------------------------------------------

    enum class AdmissionOutcome { ADMIT, REJECT_SCORE_FLOOR, REJECT_HARD_SAFETY, REJECT_PARITY }
    data class AdmissionVerdict(
        val outcome: AdmissionOutcome,
        val snapshot: EntryThresholdSnapshot,
        val reason: String,
    )

    fun admit(snap: EntryThresholdSnapshot, hardSafetyPassed: Boolean): AdmissionVerdict {
        if (!hardSafetyPassed)
            return AdmissionVerdict(AdmissionOutcome.REJECT_HARD_SAFETY, snap, "HARD_SAFETY_VETO")
        if (snap.effectiveScore < snap.finalFloor)
            return AdmissionVerdict(AdmissionOutcome.REJECT_SCORE_FLOOR, snap,
                "SCORE_FLOOR_REJECT effectiveScore=${snap.effectiveScore.toInt()} floor=${snap.finalFloor}")
        return AdmissionVerdict(AdmissionOutcome.ADMIT, snap, "ADMITTED")
    }

    internal fun clearAllForTest() { parityFailures.set(0L) }
}
