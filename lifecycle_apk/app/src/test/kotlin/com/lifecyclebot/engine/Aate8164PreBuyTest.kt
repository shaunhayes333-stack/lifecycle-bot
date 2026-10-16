package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.truth.LearningEpoch8164
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8164 — what happens before a buy: a learning reset stays reset, curve coins have a depth, and three closes do
 * not veto a lane's base.
 */
class Aate8164PreBuyTest {

    @After fun tearDown() { LearningEpoch8164.setForTest8164(0L) }

    private fun src(rel: String): String {
        val roots = listOf("src/main/kotlin/com/lifecyclebot", "app/src/main/kotlin/com/lifecyclebot", "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot")
        return roots.map { File(it, rel) }.first { it.exists() }.readText()
    }

    @Test fun closesSettledBeforeTheResetEpochAreHistoryNotEducation() {
        val epoch = 1_800_000_000_000L
        assertFalse(LearningEpoch8164.preEpoch8164(epoch - 1, 0L))          // never reset: nothing is excluded
        assertTrue(LearningEpoch8164.preEpoch8164(epoch - 1, epoch))        // settled before the reset
        assertFalse(LearningEpoch8164.preEpoch8164(epoch, epoch))           // at / after the reset: this run
        assertFalse(LearningEpoch8164.preEpoch8164(epoch + 60_000, epoch))
        assertFalse(LearningEpoch8164.preEpoch8164(0L, epoch))              // unknown settle time is not judged
    }

    @Test fun resetMarksTheEpochAndEveryLearningConsumerReadsIt() {
        val sweep = src("engine/truth/LearningResetSweep7781.kt")
        assertTrue(sweep.contains("LearningEpoch8164.mark8164(app)"))
        assertTrue(sweep.contains("LearningEpoch8164.load8164(app)"))
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bridge.contains("consumer !in NON_LEARNING_CONSUMERS && LearningEpoch8164.preEpoch8164(env.atMs)"))
        // The epoch lives outside the sweep's own list, so the reset cannot erase its own start line.
        assertFalse(sweep.contains("\"aate_learning_epoch_8164\""))
    }

    @Test fun anActiveCurveHasADepthFromItsCap() {
        // $10,000 cap at $106 SOL = 94.3 SOL cap -> ~25.1 real SOL -> ~$2,661 of depth.
        val usd = LiquidityTruth8037.curveDepthUsd8164(10_000.0, 106.0)
        assertNotNull(usd)
        assertEquals(2_661.0, usd!!, 5.0)
        // Unknown price or cap: no depth is invented.
        assertNull(LiquidityTruth8037.curveDepthUsd8164(Double.NaN, 106.0))
        assertNull(LiquidityTruth8037.curveDepthUsd8164(10_000.0, 0.0))
        // Past what an active curve can carry (it completes near 85 real SOL): not a curve reading.
        assertNull(LiquidityTruth8037.curveDepthUsd8164(412.0 * 106.0, 106.0))
        assertNotNull(LiquidityTruth8037.curveDepthUsd8164(400.0 * 106.0, 106.0))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("LiquidityTruth8037.hydrate8037(ts)"))
    }

    @Test fun threeLossesDoNotVetoALanesBase() {
        assertFalse(TacticSwitcher.tacticLosing8094(3, 0, -15.6))
        assertFalse(TacticSwitcher.tacticLosing8094(11, 1, -8.0))
        assertTrue(TacticSwitcher.tacticLosing8094(12, 1, -8.0))
    }
}
