package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7660ExpertCreditAssignmentTest {
    @Test fun sparseExpertTrustIsNeutralAndExpertsLearnSeparately() {
        SuperExpertTrust7660.resetForTest()
        repeat(7) {
            SuperExpertTrust7660.recordOutcome(mapOf("CROSSTALK:WHALETRACKER" to 1.0), 20.0)
        }
        assertEquals(1.0, SuperExpertTrust7660.trust("CROSSTALK:WHALETRACKER"), 0.0001)
        repeat(40) {
            SuperExpertTrust7660.recordOutcome(
                mapOf(
                    "CROSSTALK:WHALETRACKER" to 1.0,
                    "CROSSTALK:MOMENTUM" to -1.0,
                ),
                20.0,
            )
        }
        assertTrue(SuperExpertTrust7660.trust("CROSSTALK:WHALETRACKER") > 1.0)
        assertTrue(SuperExpertTrust7660.trust("CROSSTALK:MOMENTUM") < 1.0)
    }

    @Test fun onlyAlreadyParticipatingCachedExpertsAreAttributed() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceEstate7654.kt").readText()
        assertTrue(e.contains("cross.participatingAIs.distinct()"))
        assertTrue(e.contains("ARBITRAGE:"))
        assertTrue(!e.contains("analyzeCrossTalk("))
    }

    @Test fun expertMapIsPositionBoundAndPersisted() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(c.contains("expertUtility7660"))
        assertTrue(c.contains("SuperExpertTrust7660.recordOutcome("))
        assertTrue(c.contains("expertTrust7660"))
        assertTrue(o.contains("estate7654.expertUtilities7660(tree7638.bestPolicy)"))
    }
}
