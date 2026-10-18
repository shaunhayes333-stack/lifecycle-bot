package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.*
import org.junit.Test

/**
 * 5.0.8207 — the legacy edge filter is one judgement. Behaviour: a GENERIC_PRIOR reason (volume floor, entry timing) yields to a
 * candidate-specific proven admission; a CURRENT_NEGATIVE (dead tape, distribution, buy pressure below floor) refuses even then;
 * without a specific admission every reason refuses; every verdict carries provenance (reason, age, source, owner, version).
 * Freshness: no direct birth witness means NOT fresh (watchlist discovery is never a birth).
 */
class Aate8207LegacyEdgeSingleJudgementTest {
    private val specificAdmit = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, "DIP_RECLAIM", 4.2, emptyList(), basis = "cell n=41 mean=+6.1 se=1.9")
    private val genericAdmit = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "GENERIC_PRIOR", false, null, 1.0, emptyList())
    private fun veto(reason: String) = FinalDecisionGate.EdgeVeto(timestamp = 10_000L, reason = reason, quality = "SKIP", source = "LEGACY_EDGE_FILTER", fresh = false)

    @Test fun reasonClasses() {
        val g = EdgeOptimizer.LegacyVetoClass8207.GENERIC_PRIOR; val n = EdgeOptimizer.LegacyVetoClass8207.CURRENT_NEGATIVE
        assertEquals(g, EdgeOptimizer.legacyVetoClass8207("Volume too low (12 < 30)"))
        assertEquals(g, EdgeOptimizer.legacyVetoClass8207("Wait for pullback after spike"))
        assertEquals(g, EdgeOptimizer.legacyVetoClass8207("Not optimal entry timing"))
        assertEquals(n, EdgeOptimizer.legacyVetoClass8207("Phase is DEAD - no activity"))
        assertEquals(n, EdgeOptimizer.legacyVetoClass8207("Distribution phase - smart money selling (bootstrap live)"))
        assertEquals(n, EdgeOptimizer.legacyVetoClass8207("Buy% too low (31% < 45%)"))
        assertEquals("unknown reasons never relax", n, EdgeOptimizer.legacyVetoClass8207("something new"))
        assertEquals(n, EdgeOptimizer.legacyVetoClass8207(null))
    }

    @Test fun genericPriorYieldsToSpecificAdmissionWithProvenance() {
        val checks = mutableListOf<FinalDecisionGate.GateCheck>(); val tags = mutableListOf<String>()
        val verdict = FinalDecisionGate.legacyEdgeVetoVerdict8207("M", veto("Volume too low (12 < 30)"), "MOONSHOT", 7L, specificAdmit, checks, tags, nowMs = 13_000L)
        assertNull(verdict)
        assertTrue(tags.contains("edge_veto_superseded_8207"))
        val c = checks.single { it.name == "edge_veto_sticky" }
        assertTrue(c.passed)
        val r = c.reason.orEmpty()
        assertTrue(r, r.contains("ageMs=3000") && r.contains("source=LEGACY_EDGE_FILTER") && r.contains("owner=MOONSHOT") && r.contains("v=7") &&
            r.contains("admission=ADMIT:SPECIFIC_EDGE_PROVEN_8136") && r.contains("setup=DIP_RECLAIM") && r.contains("class=GENERIC_PRIOR"))
    }

    @Test fun currentNegativeStillRefusesASpecificAdmission() {
        for (reason in listOf("Phase is DEAD - no activity", "Buy% too low (31% < 45%)", "Distribution phase - smart money selling (bootstrap live)")) {
            val checks = mutableListOf<FinalDecisionGate.GateCheck>(); val tags = mutableListOf<String>()
            assertEquals("EDGE_VETO_ACTIVE", FinalDecisionGate.legacyEdgeVetoVerdict8207("M", veto(reason), "MOONSHOT", 7L, specificAdmit, checks, tags, nowMs = 13_000L))
            assertTrue(tags.contains("edge_veto_sticky")); assertFalse(tags.contains("edge_veto_superseded_8207"))
            val r = checks.single { it.name == "edge_veto_sticky" }.reason.orEmpty()
            assertTrue(r, r.contains("single judgement") && r.contains("class=CURRENT_NEGATIVE") && r.contains("legacy='${reason.take(60)}'"))
        }
    }

    @Test fun withoutSpecificAdmissionEveryReasonRefuses() {
        for (adm in listOf(null, genericAdmit)) {
            val checks = mutableListOf<FinalDecisionGate.GateCheck>(); val tags = mutableListOf<String>()
            assertEquals("EDGE_VETO_ACTIVE", FinalDecisionGate.legacyEdgeVetoVerdict8207("M", veto("Volume too low (12 < 30)"), null, 3L, adm, checks, tags, nowMs = 11_000L))
            val r = checks.single().reason.orEmpty()
            assertTrue(r, r.contains("owner=CORE") && r.contains("v=3") && r.contains("ageMs=1000"))
        }
    }

    @Test fun freshnessNeedsADirectBirthWitness() {
        assertFalse("a mint nobody witnessed being born is not fresh", com.lifecyclebot.engine.truth.LaunchBirth8169.verifiedFresh8207("UnwitnessedMint8207" + System.nanoTime()))
        assertFalse(com.lifecyclebot.engine.truth.LaunchBirth8169.verifiedFresh8207(""))
    }

    @Test fun legacyEdgeBlockOnTheCandidateIsTheSameJudgement() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(src.contains("val legacyEdgeBlock8207 = candidate.blockReason.startsWith(\"Edge veto\")"))
        assertTrue(src.contains("|| legacyEdgeBlock8207) &&"))
        val ls = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LifecycleStrategy.kt").readText()
        assertEquals("both filterTrade call sites pass verified freshness", 2, Regex("""isFresh = com\.lifecyclebot\.engine\.truth\.LaunchBirth8169\.verifiedFresh8207\(ts\.mint\)\)""").findAll(ls).count())
        assertEquals("and no call site is left without it", 2, Regex("""EdgeOptimizer\.filterTrade\(""").findAll(ls).count())
    }
}
