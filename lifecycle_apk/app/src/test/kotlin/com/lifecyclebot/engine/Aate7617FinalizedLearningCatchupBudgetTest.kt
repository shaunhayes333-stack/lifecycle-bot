package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7617FinalizedLearningCatchupBudgetTest {
    @Test fun durableRepairHasHigherCountCeilingButSameWallClockBudget() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/truth/IndependentReconcilerScheduler6431.kt").readText()
        val block=s.substringAfter("repairDurableBusPublishFailures7459(").substringBefore("if (n7459 > 0)")
        assertTrue(block.contains("limit = 128"))
        assertTrue(block.contains("maxWorkMs7514 = 2_500L"))
    }

    @Test fun durableRepairStillRunsOnlyOnIndependentIoScheduler() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/truth/IndependentReconcilerScheduler6431.kt").readText()
        assertTrue(s.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
        assertTrue(s.contains("delay(FULL_CADENCE_MS)"))
    }
}
