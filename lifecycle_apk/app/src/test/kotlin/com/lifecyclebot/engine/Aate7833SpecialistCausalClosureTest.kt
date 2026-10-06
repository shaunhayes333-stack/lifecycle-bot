package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7833SpecialistCausalClosureTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun primary_spine_passes_the_actual_decision_into_authorization() {
        val b = src("engine/BotService.kt")
        val block = b.substringAfter("val actualInitialSizeForAuth6649").substringBefore("// If TradeAuthorizer says SHADOW_ONLY")
        assertTrue(block.contains("fdgDecision7835 = fdgDecision"))
        assertTrue(block.contains("authResult.executionIntent7835"))
        assertFalse(block.contains("recordFdgAndGetIntent6533("))
        assertFalse(block.contains("activeExecutionIntent6519("))
    }

    @Test fun authorizer_elects_then_seals_the_exact_decision() {
        val h = src("engine/TradeAuthorizer.kt")
        assertTrue(h.indexOf("LaneExecutionCoordinator.canRequestExecution(") < h.indexOf("SpecialistPreauthSeal7834.ensure("))
        assertTrue(h.contains("fdgDecision7835?.candidateVersion7835"))
        assertFalse(h.contains("activeExecutionIntentForLane7809("))
        val seal = src("engine/SpecialistPreauthSeal7834.kt")
        assertTrue(seal.contains("candidateVersion = decision.candidateVersion7835"))
        assertTrue(seal.contains("resolvedSizeSol6558 = size"))
        assertTrue(seal.contains("it.hardNoReasons.isEmpty()"))
    }

    @Test fun all_canonical_specialists_share_the_same_intent_mirror_contract() {
        val gate = src("engine/ExecutableOpenGate.kt")
        val register = gate.substringAfter("fun registerCanonicalIntent6554(").substringBefore("internal fun sameDecisionContract6734")
        listOf("QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE","MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN").forEach { lane ->
            assertTrue("missing specialist lane $lane", register.contains("\"$lane\""))
        }
        assertTrue(register.contains("\"OWNER_SELECTED\""))
        assertTrue(register.contains("\"BUY_INTENT\""))
        assertTrue(register.contains("\"FDG_ALLOW\""))
        assertTrue(register.contains("\"MARK_READY\""))
    }
}
