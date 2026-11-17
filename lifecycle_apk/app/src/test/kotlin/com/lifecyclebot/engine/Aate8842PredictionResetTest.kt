package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LearningResetSweep7781
import com.lifecyclebot.engine.truth.Mind8746
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8842: the clean predictor, its memory and its OOS veto must share one verified learning epoch. */
class Aate8842PredictionResetTest {
    @Test fun oldMindWeightsCannotBeRestoredIntoTheNewEpoch() {
        val currentEpoch = 1_794_817_800_000L
        assertFalse(Mind8746.compatibleSavedEpoch8842(1, EdgeModel8537.MODEL_EPOCH_8642, currentEpoch, currentEpoch))
        assertFalse(Mind8746.compatibleSavedEpoch8842(2, 8767, currentEpoch, currentEpoch))
        assertFalse(Mind8746.compatibleSavedEpoch8842(2, EdgeModel8537.MODEL_EPOCH_8642, currentEpoch - 1, currentEpoch))
        assertTrue(Mind8746.compatibleSavedEpoch8842(2, EdgeModel8537.MODEL_EPOCH_8642, currentEpoch, currentEpoch))

        val serialized = JSONObject(Mind8746.toJson8749(Array(10) { DoubleArray(10) }, DoubleArray(10),
            300L, DoubleArray(3), DoubleArray(3), DoubleArray(3), Double.NaN, currentEpoch))
        assertEquals(2, serialized.getInt("v"))
        assertEquals(currentEpoch, serialized.getLong("learningEpochMs"))
        assertEquals(EdgeModel8537.MODEL_EPOCH_8642, serialized.getInt("modelEpoch"))
    }

    @Test fun learningResetArchivesAndRemovesBothMindStores() {
        val files = LearningResetSweep7781.LEARNING_FILES_7975
        assertTrue(files.contains("mind_8746.json"))
        assertTrue(files.contains("mind_8842.json"))
        assertTrue(files.contains("edge_model_8722.bin"))
        assertEquals("MIND8842", Mind8746.KEY_8746)
    }

    @Test fun olderPendingLabelsCannotRetainPredictorAuthority() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        val learn = src.substringAfter("fun learn(s: Snap, netPct: Double, chainWitness: Boolean = false) {")
            .substringBefore("fun verdict(ts: TokenState")
        val rejectOld = learn.indexOf("LearningEpoch8164.preEpoch8164(s.atMs) || s.modelEpoch != MODEL_EPOCH_8642")
        val teachMind = learn.indexOf("Mind8746.learn8746(")
        val teachModel = learn.indexOf("global.learn(")
        assertTrue(rejectOld >= 0)
        assertTrue(rejectOld < teachMind)
        assertTrue(rejectOld < teachModel)
    }
}
