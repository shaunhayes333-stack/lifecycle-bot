package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7612SpecialistIntentOwnershipReclaimTest {
    private fun src()=File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()

    @Test fun trunkPlaceholderMayYieldToCanonicalSpecialist() {
        val s=src()
        val fn=s.substringAfter("fun registerCanonicalIntent6554(")
            .substringBefore("internal fun sameDecisionContract6734")
        assertTrue(fn.contains("SPECIALIST_INTENT_RECLAIMED_FROM_TRUNK_7612"))
        assertTrue(fn.contains("canonicalLane(existing.canonicalLane) !in specialistOwners7612"))
        assertTrue(fn.contains("canonicalLane(intent.canonicalLane) in specialistOwners7612"))
        assertTrue(fn.contains("executionTickets.remove(old7612.attemptId, old7612)"))
    }

    @Test fun allTwelveSpecialistsAreOwnerClassNotTrunk() {
        val s=src()
        val fn=s.substringAfter("val specialistOwners7612 = setOf(")
            .substringBefore("val authoritative =")
        listOf(
            "QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN"
        ).forEach { assertTrue(fn.contains("\"$it\"")) }
        assertFalse(fn.contains("\"STANDARD\""))
        assertFalse(fn.contains("\"V3_CORE\""))
    }

    @Test fun canonicalIntentMirrorsOwnerBeforeIntent() {
        val s=src()
        val fn=s.substringAfter("SPECIALIST_CANONICAL_OWNER_MIRRORED_7612")
        val whole=s.substringAfter("if (authoritative.canonicalLane.uppercase() in setOf(")
        assertTrue(whole.indexOf("\"OWNER_SELECTED\"") < whole.indexOf("\"BUY_INTENT\""))
    }
}
