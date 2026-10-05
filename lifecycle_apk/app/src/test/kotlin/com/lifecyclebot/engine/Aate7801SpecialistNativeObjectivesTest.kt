package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistObjective7801
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7801SpecialistNativeObjectivesTest {
    @Test fun same35PctOutcomeMeansDifferentThingsByLane() {
        val hold30m = 30L * 60_000L
        val moon = SpecialistObjective7801.evaluate("MOONSHOT",35.0,hold30m,"TEST")
        val express = SpecialistObjective7801.evaluate("EXPRESS",35.0,hold30m,"TEST")
        val treasury = SpecialistObjective7801.evaluate("TREASURY",35.0,hold30m,"TEST")
        val cash = SpecialistObjective7801.evaluate("CASHGEN",35.0,hold30m,"TEST")
        assertFalse(moon.mandateSuccess)
        assertTrue(express.mandateSuccess)
        assertTrue(treasury.mandateSuccess)
        assertTrue(cash.mandateSuccess)
        assertTrue(moon.utility < express.utility)
    }

    @Test fun allTwelveHaveDistinctNativeObjectiveCases() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/truth/SpecialistObjective7801.kt").readText()
        listOf("MOONSHOT","PROJECT_SNIPER","EXPRESS","SHITCOIN","MANIPULATED","DIP_HUNTER",
            "CYCLIC","QUALITY","BLUECHIP","TREASURY","CASHGEN").forEach {
            assertTrue("missing specialist objective $it", s.contains("\"$it\" ->"))
        }
        assertTrue(s.contains("else ->"))
        assertTrue(s.contains("CORE: general expert trader"))
    }

    @Test fun hunterLearningUsesSpecialistUtility() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/market/LaneHunter7297.kt").readText()
        assertTrue(s.contains("SpecialistObjective7801.evaluate"))
        assertTrue(s.contains("sumUtility"))
        assertTrue(s.contains("meanUtility"))
        assertTrue(s.contains("nativeDiscoveryMultiplier7801"))
    }

    @Test fun sharedLearningUsesSpecialistMandate() {
        val tactic=File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val finality=File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val selection=File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        assertTrue(tactic.contains("SpecialistObjective7801.evaluate"))
        assertTrue(finality.contains("SpecialistObjective7801.evaluate"))
        assertTrue(selection.contains("SpecialistObjective7801.evaluate"))
    }

    @Test fun runnerDamperNoLongerRequiresThirtyPctWrForPositiveTailEdge() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        assertTrue(s.contains("LANE_DAMPER_FAT_TAIL_EDGE_7801"))
        assertTrue(s.contains("m.meanPnlPct >= RUNNER_MEAN_PCT || m.pfExpectancyPp > 0.0"))
    }
}
