
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7634SuperWorldModelTest {
    @Test fun worldModelExposesAllHorizons() {
        val s = SuperWorldModel7634.forecast(
            lane = "MOONSHOT",
            score = 80,
            quality = "A",
            regime = "PUMP",
            edgePhase = "EARLY",
            basePWin = 0.68,
            baseExpectancyPct = 24.0,
            baseConfidence = 0.80,
            disagreement = 0.10,
        )
        assertEquals(3, s.forecasts.size)
        assertTrue(s.forHorizon(SuperWorldModel7634.Horizon.IMPULSE) != null)
        assertTrue(s.forHorizon(SuperWorldModel7634.Horizon.TACTICAL) != null)
        assertTrue(s.forHorizon(SuperWorldModel7634.Horizon.THESIS) != null)
    }

    @Test fun runnerThesisPreservesTailOpportunity() {
        val s = SuperWorldModel7634.forecast(
            lane = "MOONSHOT",
            score = 85,
            quality = "A",
            regime = "PUMP",
            edgePhase = "EARLY",
            basePWin = 0.70,
            baseExpectancyPct = 30.0,
            baseConfidence = 0.85,
            disagreement = 0.05,
        )
        assertTrue(s.tailOpportunity > 0.35)
        assertTrue(
            s.forHorizon(SuperWorldModel7634.Horizon.THESIS)!!.expectedPnlPct >
                s.forHorizon(SuperWorldModel7634.Horizon.IMPULSE)!!.expectedPnlPct
        )
    }

    @Test fun plannerConsumesHorizonSpecificForecasts() {
        val planner = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligencePlanner7633.kt").readText()
        val oracle = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(planner.contains("world: SuperWorldModel7634.Snapshot? = null"))
        assertTrue(planner.contains("world?.forHorizon(horizon)"))
        assertTrue(oracle.contains("SuperWorldModel7634.forecast("))
        assertTrue(oracle.contains("contributions += world7634.contributionTag()"))
    }
}
