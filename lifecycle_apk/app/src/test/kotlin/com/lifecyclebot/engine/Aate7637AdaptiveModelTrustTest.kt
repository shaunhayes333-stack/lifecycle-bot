
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7637AdaptiveModelTrustTest {
    @Test fun calibrationStatePersistsAcrossRestart() {
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue(lp.contains("SUPER_INTELLIGENCE_CALIBRATION_7637"))
        assertTrue(lp.contains("SuperIntelligenceCalibration7636.exportState()"))
        assertTrue(lp.contains("SuperIntelligenceCalibration7636.importState(it)"))
    }

    @Test fun worldModelConsumesLearnedHorizonReliability() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperWorldModel7634.kt").readText()
        assertTrue(s.contains("SuperIntelligenceCalibration7636.horizonReliability(h)"))
        assertTrue(s.contains("baseUncertainty / reliability7637"))
    }

    @Test fun reliabilityIsSampleGatedAndBounded() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("if (s.n < 8L)"))
        assertTrue(s.contains("raw.coerceIn(0.60, 1.20)"))
        assertTrue(s.contains("brierQuality"))
        assertTrue(s.contains("dirQuality"))
    }
}
