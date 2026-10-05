package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistObjective7801
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7801SpecialistProbabilityContractTest {
    @Test fun sameReturnMeansDifferentSpecialistSuccess() {
        assertTrue(SpecialistObjective7801.evaluate("CASHGEN",4.0,20L*60_000L).mandateSuccess)
        assertFalse(SpecialistObjective7801.evaluate("MOONSHOT",4.0,20L*60_000L).mandateSuccess)
    }
    @Test fun moonshot500CanStillBeBadExitAfter1800Mfe() {
        val q=SpecialistObjective7801.exitQuality("MOONSHOT",500.0,1800.0,2L*60L*60_000L,"TRAILING_STOP")
        assertFalse(q.optimal); assertTrue(q.captureRatio<0.30)
    }
    @Test fun probabilityAndSuperStackConsumeNativeContract() {
        val root="src/main/kotlin/com/lifecyclebot/"
        assertTrue(File(root+"engine/ForwardOutcomeModel.kt").readText().contains("SpecialistObjective7801"))
        assertTrue(File(root+"engine/LiveProbabilityEngine.kt").readText().contains("SpecialistPerformance7801"))
        assertTrue(File(root+"engine/SuperWorldModel7634.kt").readText().contains("negativeEvRisk"))
        assertTrue(File(root+"engine/SuperIntelligencePlanner7633.kt").readText().contains("tailLane7801"))
        assertTrue(File(root+"engine/SuperIntelligenceCalibration7636.kt").readText().contains("mandateSuccess7801"))
    }
}
