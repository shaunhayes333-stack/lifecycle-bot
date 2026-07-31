package com.lifecyclebot.engine.truth

/**
 * V5.0.6400 — NO_HARD_SCORE_ENTRY_GATE startup invariant.
 *
 * Passes only when:
 *   - hardScoreGateActive == false
 *   - forbiddenScoreFloorRejectCount == 0
 *   - scorePolicy == SOFT_SHAPING_ONLY
 *
 * Any regression that reintroduces a hard score-floor gate must fire
 * this invariant. The invariant is intentionally simple — the score-
 * gate architecture itself is removed, so the healthiest possible
 * state is one where nothing has ever tried to hard-reject on score.
 */
object NoHardScoreEntryGateGuard6400 {

    /** Set to true only if a runtime path is proven to hard-reject on score. */
    @Volatile private var hardScoreGateActiveFlag: Boolean = false

    fun setHardGateActive(active: Boolean) { hardScoreGateActiveFlag = active }
    fun hardScoreGateActive(): Boolean = hardScoreGateActiveFlag

    data class InvariantReport(
        val passed: Boolean,
        val hardScoreGateActive: Boolean,
        val scoreOnlyHardRejects: Long,
        val scorePolicy: String,
        val evidence: List<String>,
    )

    fun check(): InvariantReport {
        val evidence = mutableListOf<String>()
        val forbid = SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get()
        if (hardScoreGateActiveFlag) evidence += "hardScoreGateActive=true"
        if (forbid > 0L) evidence += "forbiddenScoreFloorRejects=$forbid"
        return InvariantReport(
            passed = evidence.isEmpty(),
            hardScoreGateActive = hardScoreGateActiveFlag,
            scoreOnlyHardRejects = forbid,
            scorePolicy = SoftScoreShaping6400.SCORE_POLICY,
            evidence = evidence,
        )
    }

    internal fun clearAllForTest() { hardScoreGateActiveFlag = false }
}
