package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6707 — source-authority regression lock.
 *
 * Protects the repaired architecture rather than a runtime-log symptom:
 * canonical terminal finality must feed the pre-existing specialist learning
 * loop, while OrderSizeResolver must not grow another sibling WR authority.
 */
class Aate6707SourceAuthorityRestorationTest {

    @Test
    fun canonical_terminal_reconnects_original_meme_attribution_consumers() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()

        assertTrue(bridge.contains("ScoreExpectancyTracker.record(env.lane"))
        assertTrue(bridge.contains("HoldDurationTracker.record(env.lane"))
        assertTrue(bridge.contains("ExitReasonTracker.record(env.lane"))
        assertTrue(bridge.contains("LaneExitTuner.recordClose("))
        assertTrue(bridge.contains("MemeCausalLearning6568.record(env)"))
        assertTrue(bridge.contains("ColdStreakDamper.noteOutcome("))
        assertTrue(bridge.contains("DamageControlGate.noteOutcome("))
    }

    @Test
    fun canonical_owner_finality_feeds_existing_lane_learning_once() {
        val fabric = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()

        assertTrue(fabric.contains("rewardedPositions.add(env.positionId)"))
        assertTrue(fabric.contains("LanePolicy.recordOutcome(ownerLane6707"))
        assertTrue(fabric.contains("RetrainingDecay.noteOutcome(ownerLane6707"))
        assertTrue(fabric.contains("ExplorationBudget.onLaneOutcome(ownerLane6707"))
        assertTrue(fabric.contains("if (!lane.equals(env.lane, true))"))
    }

    @Test
    fun mandatory_sizer_has_no_second_winrate_authority_overlay() {
        val resolver = File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val removedOverlay = File("src/main/kotlin/com/lifecyclebot/engine/learning/AdaptiveWinRateAuthority6706.kt")

        assertFalse(resolver.contains("AdaptiveWinRateAuthority6706"))
        assertFalse(resolver.contains("ORDER_SIZE_ADAPTIVE_WR_HELD_6706"))
        assertFalse(bridge.contains("AdaptiveWinRateAuthority6706"))
        assertFalse(removedOverlay.exists())
    }

    @Test
    fun existing_lane_policy_remains_the_execution_feedback_source() {
        val policy = File("src/main/kotlin/com/lifecyclebot/engine/learning/LanePolicy.kt").readText()
        val router = File("src/main/kotlin/com/lifecyclebot/engine/learning/FdgRouteVerdict.kt").readText()
        val v3 = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()

        assertTrue(policy.contains("fun recordOutcome("))
        assertTrue(policy.contains("fun bleedExecutionCap("))
        assertTrue(router.contains("LanePolicy.bleedExecutionCap(lane, scoreBand)"))
        assertTrue(v3.contains("LanePolicy.recordOutcome(layer"))
        assertTrue(v3.contains("LaneExitTuner.recordClose("))
    }
}
