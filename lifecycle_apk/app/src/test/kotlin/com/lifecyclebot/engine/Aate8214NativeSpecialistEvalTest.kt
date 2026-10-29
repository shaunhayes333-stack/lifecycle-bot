package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 8214 — specialists with their own native claim evaluate (bounded), and an allowing specialist replaces a refused rescue. */
class Aate8214NativeSpecialistEvalTest {
    @Before fun reset() = SpecialistElection8136.resetForTests8136()

    @Test fun nativeClaimEvaluatesBoundedAndStable() {
        val m = "Nat8214" + System.nanoTime()
        assertFalse("no claim, no evaluation", SpecialistElection8136.nativeEvalSlot8214(m, "MOONSHOT", "QUALITY", 1L, false))
        assertFalse("primary is not a native extra", SpecialistElection8136.nativeEvalSlot8214(m, "QUALITY", "QUALITY", 1L, true))
        assertTrue(SpecialistElection8136.nativeEvalSlot8214(m, "MOONSHOT", "QUALITY", 1L, true))
        assertTrue(SpecialistElection8136.nativeEvalSlot8214(m, "SHITCOIN", "QUALITY", 1L, true))
        assertTrue(SpecialistElection8136.nativeEvalSlot8214(m, "EXPRESS", "QUALITY", 1L, true))
        // superseded by V5.0.8429 — the cap is six (a READY resident proposal is a native claim; CORE / CYCLIC / TREASURY were
        // left behind the first three lanes in order). Was: the fourth lane refused.
        assertTrue(SpecialistElection8136.nativeEvalSlot8214(m, "DIP_HUNTER", "QUALITY", 1L, true))
        assertTrue(SpecialistElection8136.nativeEvalSlot8214(m, "MANIPULATED", "QUALITY", 1L, true))
        assertTrue(SpecialistElection8136.nativeEvalSlot8214(m, "BLUECHIP", "QUALITY", 1L, true))
        assertFalse("cap holds", SpecialistElection8136.nativeEvalSlot8214(m, "PROJECT_SNIPER", "QUALITY", 1L, true))
        assertTrue("a holder keeps its slot across cycles", SpecialistElection8136.nativeEvalSlot8214(m, "MOONSHOT", "QUALITY", 1L, true))
        assertTrue("a new candidate version reopens slots", SpecialistElection8136.nativeEvalSlot8214(m, "PROJECT_SNIPER", "QUALITY", 2L, true))
    }

    @Test fun gateAndElectionAreWired() {
        val b = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(b.contains("SpecialistElection8136.nativeEvalSlot8214(ts.mint, l, primaryLane, candidateVersion6600,"))
        val e = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SpecialistElection8136.kt").readText()
        val replace = e.indexOf("\"ADOPTED_FDG_ALLOW_AFTER_RESCUE_REFUSED_8214\"")
        assertTrue(replace in 1 until e.indexOf("\"OVERFLOW_SECOND_RESCUE:"))
        assertTrue("primary judgement still binds", e.contains("shouldTrade && !r.primaryJudgement && rescueRefused8214(r)"))
    }
}
