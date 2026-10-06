package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7833SpecialistCausalClosureTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun primary_spine_seals_before_authorize_without_growing_hot_method() {
        val b = src("engine/BotService.kt")
        val block = b.substringAfter("val primaryCandidateVersion7467").substringBefore("ErrorLogger.info(\"BotService\", \"🧬 MEME_SPINE AUTH")
        val seal = block.indexOf("SpecialistPreauthSeal7834.ensure(")
        val auth = block.indexOf("TradeAuthorizer.authorize(")
        assertTrue(seal >= 0)
        assertTrue(auth > seal)
        assertFalse(block.contains("recordFdgAndGetIntent6533("))
    }

    @Test fun extracted_helper_preserves_authority_and_size_contract() {
        val h = src("engine/SpecialistPreauthSeal7834.kt")
        assertTrue(h.contains("recordFdgAndGetIntent6533("))
        assertTrue(h.contains("!fdgCanExecute || !resolvedSizeSol.isFinite() || resolvedSizeSol <= 0.0"))
        assertTrue(h.contains("preFdgVerdict = \"BUY\""))
        assertTrue(h.contains("hardNoReasons = hardNoReasons"))
        assertTrue(h.contains("resolvedSizeSol6558 = resolvedSizeSol"))
        assertTrue(h.contains("PRIMARY_SPINE_PREAUTH_SEAL_CREATED_7834"))
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
