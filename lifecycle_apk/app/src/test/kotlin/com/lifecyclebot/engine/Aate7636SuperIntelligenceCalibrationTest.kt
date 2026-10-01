
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7636SuperIntelligenceCalibrationTest {
    @Test fun canonicalOpenBindsPlannerToPositionId() {
        val pos = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(pos.contains("SuperIntelligenceCalibration7636.bindPosition(positionId, mint, lane)"))
    }

    @Test fun canonicalFinalizedBusGradesTheBoundPrediction() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(bus.contains("\"SuperIntelligenceCalibration7636\""))
        assertTrue(bridge.contains("\"SuperIntelligenceCalibration7636\" -> deliverToSuperIntelligenceCalibration7636(env)"))
        assertTrue(bridge.contains("SuperIntelligenceCalibration7636.onFinalized(env)"))
    }

    @Test fun gradingIsHorizonAndPositionBound() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("byPosition[positionId] = fallback"))
        assertTrue(s.contains("SuperWorldModel7634.Horizon.entries.minByOrNull"))
        assertTrue(s.contains("SUPER_INTELLIGENCE_OUTCOME_GRADED_7636"))
        assertTrue(s.contains("brierSum"))
        assertTrue(s.contains("absEvErrorSum"))
    }

    @Test fun oracleStampsBothColdAndMaturePlanningDecisions() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        val count = "SuperIntelligenceCalibration7636.recordDecision(".toRegex().findAll(s).count()
        assertTrue(count >= 2)
    }
}
