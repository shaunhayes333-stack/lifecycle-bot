package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7833 — all twelve resident specialists must reach authorization with
 * an already-sealed canonical FDG intent; TradeAuthorizer must never be asked
 * to wait for a seal that the same spine only planned to create afterwards. */
class Aate7833SpecialistCausalClosureTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun primary_spine_seals_before_authorize() {
        val b = src("engine/BotService.kt")
        val block = b.substringAfter("val primaryCandidateVersion7467")
            .substringBefore("ErrorLogger.info(\"BotService\", \"🧬 MEME_SPINE AUTH")
        val seal = block.indexOf("recordFdgAndGetIntent6533(")
        val auth = block.indexOf("TradeAuthorizer.authorize(")
        assertTrue("canonical FDG intent must be materialized", seal >= 0)
        assertTrue("seal must happen before TradeAuthorizer", auth > seal)
        assertTrue(block.contains("resolvedSizeSol6558 = actualInitialSizeForAuth6649"))
        assertTrue(block.contains("candidateVersion = primaryCandidateVersion7467"))
        assertTrue(block.contains("tokenMapRouteStatus = tokenMap6614.routeStatus"))
        assertTrue(block.contains("PRIMARY_SPINE_PREAUTH_SEAL_CREATED_7833"))
    }

    @Test fun post_auth_no_longer_owns_first_seal_creation() {
        val b = src("engine/BotService.kt")
        val afterAuth = b.substringAfter("ErrorLogger.info(\"BotService\", \"🧬 MEME_SPINE AUTH")
            .substringBefore("// V5.0.6658 §TICKET_STAMP_RETRIEVAL_PARITY")
        // Legacy fallback may remain defensive, but executable success must not
        // be the prerequisite for the FIRST canonical seal anymore.
        val beforeAuth = b.substringAfter("val primaryCandidateVersion7467")
            .substringBefore("val authResult = TradeAuthorizer.authorize(")
        assertTrue(beforeAuth.contains("recordFdgAndGetIntent6533("))
        assertTrue(beforeAuth.contains("fdgDecision.canExecute()"))
        assertFalse(beforeAuth.contains("authResult.isExecutable()"))
    }

    @Test fun all_canonical_specialists_share_the_same_intent_mirror_contract() {
        val gate = src("engine/ExecutableOpenGate.kt")
        val register = gate.substringAfter("fun registerCanonicalIntent6554(")
            .substringBefore("internal fun sameDecisionContract6734")
        listOf("QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN").forEach { lane ->
            assertTrue("missing specialist lane $lane", register.contains("\"$lane\""))
        }
        assertTrue(register.contains("\"OWNER_SELECTED\""))
        assertTrue(register.contains("\"BUY_INTENT\""))
        assertTrue(register.contains("\"FDG_ALLOW\""))
        assertTrue(register.contains("\"MARK_READY\""))
    }
}
