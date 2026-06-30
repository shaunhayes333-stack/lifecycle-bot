package com.lifecyclebot.engine

/**
 * V5.0.4548 — Inner-lane re-education doctrine marker.
 *
 * Strategy-failure buckets must not disable/amputate a lane or outsource the
 * lesson to a different lane. They must produce a lane-local curriculum:
 *   - change entry timing/wave/mcap band
 *   - change tactic/style/confirmation requirements
 *   - change hold/exit profile
 *   - stamp telemetry so clean terminal rows can validate the new tactic
 *
 * True hard safety still has authority to block: rugs, missing route/basis proof,
 * raw hard-floor failure, wallet/authority uncertainty, manual emergency cleanup,
 * global kill-switch/security guard, and cost-negative economics.
 */
object InnerLaneReeducationDoctrine {
    enum class FailureKind { HARD_SAFETY, STRATEGY_FAILURE, DATA_QUALITY, MECHANICAL_FAULT }
    enum class Curriculum { NONE, ENTRY_CONFIRMATION, TACTIC_SWITCH, HOLD_EXIT_RETRAIN, ROUTE_PROOF_RECHECK }

    fun curriculumFor(kind: FailureKind, lane: String, reason: String): Curriculum = when (kind) {
        FailureKind.HARD_SAFETY -> Curriculum.NONE
        FailureKind.MECHANICAL_FAULT -> Curriculum.ROUTE_PROOF_RECHECK
        FailureKind.DATA_QUALITY -> Curriculum.ENTRY_CONFIRMATION
        FailureKind.STRATEGY_FAILURE -> when {
            reason.contains("exit", true) || reason.contains("hold", true) -> Curriculum.HOLD_EXIT_RETRAIN
            reason.contains("confirm", true) || reason.contains("proof", true) -> Curriculum.ENTRY_CONFIRMATION
            else -> Curriculum.TACTIC_SWITCH
        }
    }

    fun allowsDisable(kind: FailureKind): Boolean = kind == FailureKind.HARD_SAFETY
}
