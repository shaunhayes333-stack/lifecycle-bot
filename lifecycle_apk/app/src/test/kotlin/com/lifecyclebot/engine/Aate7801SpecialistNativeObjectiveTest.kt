package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistObjective7801
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7801SpecialistNativeObjectiveTest {
    @Test fun sameReturnMeansDifferentThingsToDifferentSpecialists() {
        val moon = SpecialistObjective7801.evaluate("MOONSHOT", 35.0, 60L*60_000L)
        val treasury = SpecialistObjective7801.evaluate("TREASURY", 35.0, 60L*60_000L)
        val cash = SpecialistObjective7801.evaluate("CASHGEN", 35.0, 30L*60_000L)
        val express = SpecialistObjective7801.evaluate("EXPRESS", 35.0, 10L*60_000L)
        assertFalse(moon.mandateSuccess)
        assertTrue(treasury.mandateSuccess)
        assertTrue(cash.mandateSuccess)
        assertTrue(express.mandateSuccess)
        assertTrue(moon.utility < treasury.utility)
    }

    @Test fun moonshotRequiresTailMagnitude() {
        assertFalse(SpecialistObjective7801.evaluate("MOONSHOT", 50.0, 2L*60L*60_000L).mandateSuccess)
        assertTrue(SpecialistObjective7801.evaluate("MOONSHOT", 150.0, 2L*60L*60_000L).mandateSuccess)
        assertTrue(SpecialistObjective7801.evaluate("MOONSHOT", 500.0, 2L*60L*60_000L).utility > 2.0)
    }

    @Test fun treasuryPenalisesCapitalDamageMoreThanSmallMiss() {
        val small = SpecialistObjective7801.evaluate("TREASURY", -1.0, 60L*60_000L)
        val deep = SpecialistObjective7801.evaluate("TREASURY", -10.0, 60L*60_000L)
        assertTrue(deep.utility < small.utility)
    }

    @Test fun specialistObjectiveIsWiredIntoHuntersAndLearning() {
        val hunter=File("src/main/kotlin/com/lifecyclebot/engine/market/LaneHunter7297.kt").readText()
        val tuner=File("src/main/kotlin/com/lifecyclebot/engine/learning/LaneExitTuner.kt").readText()
        val tactic=File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val bus=File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(hunter.contains("SpecialistObjective7801.evaluate"))
        assertTrue(tuner.contains("SpecialistObjective7801.evaluate"))
        assertTrue(tactic.contains("SpecialistObjective7801.evaluate"))
        assertTrue(bus.contains("SpecialistObjective7801.evaluate"))
    }

    @Test fun everyNativeHunterUsesLaneSpecificEvidence() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/market/LaneHunter7297.kt").readText()
        listOf("PROJECT_SNIPER","EXPRESS","SHITCOIN","MANIPULATED","DIP_HUNTER","CYCLIC",
            "QUALITY","BLUECHIP","TREASURY","CASHGEN","CORE").forEach { lane ->
            assertTrue("missing native discovery branch for $lane", s.contains("\"$lane\" ->"))
        }
        assertTrue(s.contains("nativeDiscoveryMultiplier7801"))
    }

    @Test fun ownershipUsesNativeThesisBeforeStaticPriority() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        assertTrue(s.contains("NATIVE THESIS OWNS THE TRADE"))
        assertTrue(s.contains("nativeConviction7801"))
        assertTrue(s.contains("lane != \"CORE\" && conviction >= 75"))
    }
}
