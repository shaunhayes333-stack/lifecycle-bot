package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7661ExpertCoalitionTest {
    @Test fun sparseCoalitionIsNeutralAndContextSpecific() {
        SuperExpertCoalition7661.resetForTest()
        val experts = listOf("CROSSTALK:WHALE", "CROSSTALK:MOMENTUM")
        repeat(9) {
            SuperExpertCoalition7661.recordOutcome(
                "MOONSHOT",
                SuperWorldModel7634.LatentState.ACCELERATING,
                mapOf(experts[0] to 1.0, experts[1] to 1.0),
                20.0,
            )
        }
        assertEquals(
            1.0,
            SuperExpertCoalition7661.trust(
                "MOONSHOT", SuperWorldModel7634.LatentState.ACCELERATING, experts
            ),
            0.0001,
        )
    }

    @Test fun coalitionLearnsPerLaneAndState() {
        SuperExpertCoalition7661.resetForTest()
        val experts = listOf("CROSSTALK:WHALE", "CROSSTALK:MOMENTUM")
        repeat(50) {
            SuperExpertCoalition7661.recordOutcome(
                "MOONSHOT",
                SuperWorldModel7634.LatentState.ACCELERATING,
                mapOf(experts[0] to 1.0, experts[1] to 1.0),
                25.0,
            )
        }
        assertTrue(
            SuperExpertCoalition7661.trust(
                "MOONSHOT", SuperWorldModel7634.LatentState.ACCELERATING, experts
            ) > 1.0
        )
        assertEquals(
            1.0,
            SuperExpertCoalition7661.trust(
                "CORE", SuperWorldModel7634.LatentState.ACCELERATING, experts
            ),
            0.0001,
        )
    }

    @Test fun coalitionTrustRefinesCachedCrossTalkOnly() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceEstate7654.kt").readText()
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(e.contains("SuperExpertCoalition7661.trust("))
        assertTrue(c.contains("SuperExpertCoalition7661.recordOutcome("))
        assertTrue(c.contains("expertCoalitions7661"))
    }
}
