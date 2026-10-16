package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7796SpecialistHuntersTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun everyMemeSpecialistHasHunterProfile() {
        val s = src("engine/market/LaneHunter7297.kt")
        listOf(
            "SHITCOIN","QUALITY","BLUECHIP","DIP_HUNTER","MOONSHOT",
            "TREASURY","CASHGEN","EXPRESS","PROJECT_SNIPER","MANIPULATED",
            "CYCLIC","CORE"
        ).forEach { lane -> assertTrue("missing hunter for $lane", s.contains("\"$lane\"")) }
    }

    @Test fun moonshotHunterTargetsEarlyAsymmetryNotAnythingGreen() {
        val s = src("engine/market/LaneHunter7297.kt")
        assertTrue(s.contains("r.mcapUsd < 10_000.0 -> 6.0"))
        assertTrue(s.contains("r.ageHours <= 0.25 -> 3.0"))
        assertTrue(s.contains("r.priceChangeH1Pct > 0.0 && r.priceChangeH1Pct <= 120.0"))
        assertTrue(s.contains("exhaustionPenalty"))
    }

    @Test fun sniperHunterUsesTrueFirstMinutesWindow() {
        val s = src("engine/market/LaneHunter7297.kt")
        // V5.0.8031 — age must be KNOWN and within the first 3 minutes.
        assertTrue(s.contains("HunterMandates8031.knownAgeWithin8031(r.ageHours, 0.05)"))
        assertTrue(s.contains("PROJECT_SNIPER"))
    }

    @Test fun coreHypothesisExistsBeforeHunterClaimArbitration() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val core = s.indexOf("V5.0.7796 — CORE must exist BEFORE hunter-claim arbitration")
        // V5.0.7803 — resident hunter books replaced the single 7448 hunt claim.
        val claim = s.indexOf("residentHunterLanes7803.forEach")
        assertTrue(core > 0 && claim > core)
    }
}
