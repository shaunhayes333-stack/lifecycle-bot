package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6683 — matured toxic lane×score buckets remain fully trainable but
 * cannot be converted back into economic BUYs by a later execution patch.
 */
class Aate6683ShadowTrainAuthorityTest {

    @Test
    fun bucket_shadow_train_contract_is_authoritative_at_final_open() {
        val bucket = File("src/main/kotlin/com/lifecyclebot/engine/BucketExecutionState.kt").readText()
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()

        assertTrue(bucket.contains("then bucket.executionState = SHADOW_TRAIN_ONLY"))
        assertTrue(bucket.contains("must NOT create an executable paper/live BUY"))
        assertTrue(gate.contains("BucketExecutionState.isShadowTrainOnly(canonicalSelectedLane, gateScore)"))
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_SHADOW_TRAIN_ONLY_6683"))
        assertTrue(gate.contains("shadow = true"))
        assertFalse(gate.contains("EXEC_OPEN_SHADOW_TRAIN_SOFT_ALLOW"))
    }

    @Test
    fun shadow_block_preserves_counterfactual_learning_and_does_not_disable_lane() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val bucket = File("src/main/kotlin/com/lifecyclebot/engine/BucketExecutionState.kt").readText()

        assertTrue(gate.contains("NoTradeObservationStore.recordBlock"))
        assertTrue(gate.contains("action=shadow_train_counterfactual_no_economic_open"))
        assertTrue(bucket.contains("ScoreExpectancyTracker.bucketSamples(lane, score)"))
        assertTrue(bucket.contains("LosingPatternMemory.isDangerZone(lane, score)"))
        assertTrue(bucket.contains("if (samples < MIN_SAMPLES) return State.EXECUTABLE"))
    }
}
