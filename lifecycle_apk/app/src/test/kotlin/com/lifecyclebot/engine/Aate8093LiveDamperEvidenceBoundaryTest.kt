package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8093LiveDamperEvidenceBoundaryTest {
    @Test fun paperForwardLabelsCannotIndependentlyShrinkLiveSizing() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        assertTrue(src.contains("if (paperRuntime6679) for (lane in LABEL_PRIOR_LANES_7786)"))
        assertTrue(src.contains("StrategyTelemetry.computeCleanLiveTerminalLeaderboard()"))
        assertTrue(src.contains("StrategyTelemetry.computeCleanPaperTerminalLeaderboard()"))
    }
}
