package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7801SpecialistManagementTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun allTwelveHaveNativeHeldProfiles() {
        val h = src("engine/HoldingLogicLayer.kt")
        val lanes = listOf(
            "MOONSHOT","PROJECT_SNIPER","EXPRESS","SHITCOIN","MANIPULATED",
            "DIP_HUNTER","CYCLIC","QUALITY","BLUECHIP","TREASURY","CASHGEN","CORE"
        )
        for (lane in lanes) {
            assertTrue("missing held profile: " + lane, h.contains("\"" + lane + "\" to ModeHoldParams"))
        }
        assertTrue(h.contains("stalledMode.contains(\"MOONSHOT\")"))
        assertTrue(h.contains("adaptiveBounds7801"))
    }

    @Test fun fluidHoldClocksAreNative() {
        val f = src("v3/scoring/FluidLearningAI.kt")
        assertTrue(f.contains("\"EXPRESS\" -> lerp(EXPRESS_MAX_HOLD_BOOTSTRAP"))
        assertTrue(f.contains("\"PROJECT_SNIPER\", \"PRESALE_SNIPE\" -> lerp(SNIPER_MAX_HOLD_BOOTSTRAP"))
        assertTrue(f.contains("\"MANIPULATED\", \"MANIP\" -> lerp(MANIP_MAX_HOLD_BOOTSTRAP"))
        assertTrue(f.contains("\"DIP_HUNTER\" -> lerp(DIP_MAX_HOLD_BOOTSTRAP"))
        assertTrue(f.contains("\"CASHGEN\" -> lerp(CASHGEN_MAX_HOLD_BOOTSTRAP"))
        assertTrue(f.contains("BLUECHIP_MAX_HOLD_BOOTSTRAP = 480.0"))
    }

    @Test fun advancedExitManagerMapsNativeSpecialists() {
        val a = src("v3/scoring/AdvancedExitManager.kt")
        for (profile in listOf("PROJECT_SNIPER(", "MANIPULATED(", "QUALITY(", "CASHGEN(", "CYCLIC(", "CORE(")) {
            assertTrue("missing AEM profile " + profile, a.contains(profile))
        }
        for (lane in listOf("PROJECT_SNIPER","EXPRESS","SHITCOIN","MANIPULATED","DIP_HUNTER","CYCLIC","QUALITY","BLUECHIP","TREASURY","CASHGEN","CORE")) {
            assertTrue("missing AEM map " + lane, a.contains("\"" + lane + "\""))
        }
    }

    @Test fun midHoldPivotCompatibilityIsSpecialistBounded() {
        val p = src("engine/HeldPositionPivotArbiter.kt")
        assertTrue(p.contains("pivotCompatible7801"))
        assertTrue(p.contains("\"TREASURY\", \"CASHGEN\" -> false"))
        assertTrue(p.contains("\"DIP_HUNTER\" -> to in setOf(\"REVIVAL\", \"STANDARD\")"))
        assertTrue(p.contains("\"MANIPULATED\", \"MANIP\" -> to in setOf(\"PUMP_SNIPER\", \"MICRO_CAP\")"))
    }

    @Test fun learnedHeldTuningCannotMorphFastLanesIntoRunners() {
        val t = src("engine/LiveStrategyTuner.kt")
        assertTrue(t.contains("specialistClamp7801"))
        assertTrue(t.contains("lane.contains(\"EXPRESS\")"))
        assertTrue(t.contains("holdMult = a.holdMult.coerceIn(0.65, 1.10)"))
        assertTrue(t.contains("lane.contains(\"MANIP\")"))
        assertTrue(t.contains("holdMult = a.holdMult.coerceIn(0.55, 1.00)"))
    }
}
