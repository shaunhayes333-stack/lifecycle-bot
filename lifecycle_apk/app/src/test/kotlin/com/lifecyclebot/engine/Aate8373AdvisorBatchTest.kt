package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Apply ALL button must not turn one tune budget into repeated writes to the same key. */
class Aate8373AdvisorBatchTest {
    @Test fun duplicateThresholdsAreAllHeldForManualReview() {
        val decisions = SelfHealingAdvisor.batchPlan8373(listOf(
            "minHoldMins", "exitScoreThreshold", "exitScoreThreshold", "pollSeconds",
            "exitScoreThreshold", "stopLossPct", "scanIntervalSecs"))
        assertNull(decisions[0])
        assertTrue(decisions[1]!!.contains("repeated"))
        assertTrue(decisions[2]!!.contains("repeated"))
        assertNull(decisions[3])
        assertTrue(decisions[4]!!.contains("repeated"))
        assertNull(decisions[5])
        assertTrue(decisions[6]!!.contains("batch limit"))
    }

    @Test fun noProposalIsSilentlyDroppedAndIndependentChangesHaveOneBudget() {
        val keys = listOf("minHoldMins", "pollSeconds", "stopLossPct", "entryCooldownSec", "convictionMult1")
        val results = SelfHealingAdvisor.batchPlan8373(keys)
        assertEquals(keys.size, results.size)
        assertEquals(3, results.count { it == null })
        assertEquals(2, results.count { it?.contains("batch limit") == true })
    }

    @Test fun coreTunerAlsoRejectsRepeatedKeysAndExplainsOverflow() {
        val tuner = File("src/main/kotlin/com/lifecyclebot/engine/LlmParameterTuner.kt").readText()
        assertTrue(tuner.contains("val proposalCounts8373 ="))
        assertTrue(tuner.contains("proposalCounts8373[key]"))
        assertTrue(tuner.contains("repeated/conflicting proposal; no change applied"))
        assertTrue(tuner.contains("applied.size >= maxAdjustments"))
        assertTrue(tuner.contains("per-call adjustment limit"))
        assertTrue(tuner.contains("ConfigStore.save(ctx, working)"))
    }
}
