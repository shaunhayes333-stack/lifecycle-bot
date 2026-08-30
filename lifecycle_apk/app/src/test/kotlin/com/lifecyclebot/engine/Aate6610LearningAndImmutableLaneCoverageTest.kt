package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6610 — Operator directive Feb 2026 (post-V5.0.6609 dump):
 *   * "Every finalized canonical MemeTrader trade must immediately update
 *     owner specialist... specialistLearningMissing must remain zero."
 *   * "A position's exit personality derives from immutable entryLane +
 *     current learned tactic. Do not allow a MOONSHOT position to
 *     accidentally acquire SHITCOIN exit logic through a mutable
 *     current-lane lookup."
 *
 * V5.0.6609 dump captured every specialist reporting learningN=0 across
 * 616 lifetime finalized trades, and a MOONSHOT position's top-up
 * journaled as lane=STANDARD (Trade.tradingMode defaulted to "STANDARD"
 * when the top-up trade was constructed).
 *
 * V5.0.6610 §LEARNING_FANOUT_TO_OWNER:
 *   AateDecisionEnvelope6512.onFinalized now bumps
 *   recordDeskStage(env.lane, "LEARNING", env.positionId) for the owner
 *   lane on every finalized trade (previously only cross-lane contributors
 *   got a stage bump). LanePolicy itself is still trained exactly once
 *   by V3JournalRecorder; this only ensures the operator's liveness
 *   report's learningN counter reflects reality.
 *
 * V5.0.6610 §IMMUTABLE_ENTRY_LANE_ON_TOPUP:
 *   Executor.recordPaperTopUp now constructs the Trade with
 *   tradingMode = pos.tradingMode instead of relying on the data-class
 *   default "STANDARD". A MOONSHOT position's top-up now journals as
 *   lane=MOONSHOT so downstream exit-personality resolution uses the
 *   immutable entry-lane.
 */
class Aate6610LearningAndImmutableLaneCoverageTest {

    @Test
    fun aate6610_owner_lane_receives_learning_stage_bump() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt"
        ).readText()
        assertTrue(
            "V5.0.6610: onFinalized must bump owner-lane LEARNING stage",
            src.contains("§LEARNING_FANOUT_TO_OWNER") &&
                src.contains("ToolkitSignalSheet.recordDeskStage(ownerLane6610, \"LEARNING\", env.positionId)") &&
                src.contains("SPECIALIST_LEARNING_OWNER_FANOUT_6610_")
        )
        // Owner-lane bump must fire for every configured meme desk.
        assertTrue(
            "V5.0.6610: owner-lane fanout must cover the full configured meme desk set",
            src.contains("\"QUALITY\",\"BLUECHIP\",\"BLUE_CHIP\",\"SHITCOIN\",\"CYCLIC\",\"EXPRESS\",\"CORE\",") &&
                src.contains("\"MOONSHOT\",\"PROJECT_SNIPER\",\"DIP_HUNTER\",\"MANIPULATED\",\"TREASURY\",\"CASHGEN\"")
        )
    }

    @Test
    fun aate6610_topup_preserves_immutable_entry_lane() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue(
            "V5.0.6610: paper top-up trade must preserve pos.tradingMode instead of defaulting to STANDARD",
            src.contains("§IMMUTABLE_ENTRY_LANE_ON_TOPUP") &&
                src.contains("tradingMode = pos.tradingMode.ifBlank { \"STANDARD\" }") &&
                src.contains("tradingModeEmoji = pos.tradingModeEmoji.ifBlank { \"📈\" }")
        )
    }
}
