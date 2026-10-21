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
        // superseded by V5.0.8293: priors peak at $10k-$100k / 3-15 min (the sub-$10k / first-3-min peak was the realised
        // losing shape) and the asymmetry mandate lives in HunterMandates8293 at the native liquidity floor.
        assertTrue(s.contains("HunterMandates8293.moonshotCapPrior8293(r.mcapUsd)"))
        assertTrue(s.contains("HunterMandates8293.moonshotYouthPrior8293(r.ageHours)"))
        val m = src("engine/market/HunterRepair8293.kt")
        assertTrue(m.contains("ageHours <= 0.25 -> 3.0"))
        assertTrue(m.contains("h1.isFinite() && h1 > 0.0 && h1 <= 120.0 && liqUsd >= minLiq && tx1h >= 3"))
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
