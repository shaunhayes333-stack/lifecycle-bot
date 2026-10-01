package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7610PerLaneFanoutFairnessTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/IntakeFanoutGovernor6835.kt").readText()

    @Test fun laneBudgetIsScopedPerLaneNotFirstTwoDistinctLanes() {
        val s=src()
        val block=s.substringAfter("fun allowLaneEval(").substringBefore("/**\n     * V5.0.7265")
        assertTrue(block.contains("\"::LANE::\" + lane.take(20)"))
        assertTrue(block.contains("c.laneEvalSeen.get() >= LANE_EVAL_CAP"))
        assertTrue(block.contains("c.laneEvalSeen.incrementAndGet()"))
        assertFalse(block.contains("c.lanesSeen.size >= LANE_EVAL_CAP"))
    }

    @Test fun allLaneNamesGetIndependentKeys() {
        val s=src()
        assertTrue(s.contains("FANOUT_LANE_EVAL_CAPPED_6835_\$lane"))
        assertTrue(s.contains("scope=PER_LANE_7610"))
    }
}
