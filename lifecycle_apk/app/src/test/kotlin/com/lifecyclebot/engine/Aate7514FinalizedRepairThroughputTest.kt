package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7514FinalizedRepairThroughputTest {
    @Test fun scheduler_uses_larger_but_bounded_repair_batch() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/IndependentReconcilerScheduler6431.kt").readText()
        assertTrue(s.contains("limit = 32"))
        assertTrue(s.contains("maxWorkMs7514 = 2_500L"))
        assertTrue(s.contains("Dispatchers.IO"))
        assertTrue(s.contains("FULL_CADENCE_MS = 30_000L"))
    }

    @Test fun repair_yields_on_wall_clock_budget() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        val fn = s.substringAfter("fun repairDurableBusPublishFailures7459").substringBefore("fun statusLine")
        assertTrue(fn.contains("System.currentTimeMillis() - started7514 >= maxWorkMs7514"))
        assertTrue(fn.contains("FINALIZED_BUS_REPAIR_BUDGET_YIELD_7514"))
        assertTrue(fn.contains("if (repaired >= limit) break"))
    }
}
