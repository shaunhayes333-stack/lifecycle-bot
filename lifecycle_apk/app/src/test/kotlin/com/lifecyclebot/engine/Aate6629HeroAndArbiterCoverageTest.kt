package com.lifecyclebot.engine

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * V5.0.6629 §CAUSAL_AUTHORITY_REPAIR_HERO_AND_ARBITER coverage.
 *
 *   §6 PaperEconomicSnapshot6629 — every hero surface reads through
 *      the canonical facade so MEME/CRYPTO/MARKETS all consume the
 *      same journal-derived revision and any per-surface drift fires
 *      PAPER_ECONOMIC_SNAPSHOT_DIVERGENCE_6629.
 *   §8 SpecialistProposalArbiter6629 — multi-lane propose, one-BUY
 *      elect. Non-elected proposals still retain learning attribution
 *      via runnersUp so QUALITY / DIP_HUNTER / MANIPULATED / TREASURY /
 *      CASHGEN stop being silent owners.
 */
class Aate6629HeroAndArbiterCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6629_hero_snapshot_reads_are_counted_per_surface() {
        com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.resetForTest()
        // Journal authority not published in the unit test — reads
        // return null but must still be counted for the operator's
        // per-surface consumption dump.
        com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.read6629("MEME")
        com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.read6629("CRYPTO")
        com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.read6629("MARKETS")
        com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.read6629("MEME")
        val status = com.lifecyclebot.engine.truth.PaperEconomicSnapshot6629.statusLine6629()
        assertTrue("V5.0.6629 §6: total reads must count every surface consumption",
            status.contains("reads=4"))
        assertTrue("V5.0.6629 §6: per-surface breakdown must include MEME/CRYPTO/MARKETS",
            status.contains("MEME=2") && status.contains("CRYPTO=1") && status.contains("MARKETS=1"))
    }

    @Test
    fun aate6629_arbiter_elects_highest_confidence_score_lane_priority() {
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest()
        val mint = "aate6629-arbiter-mintA"
        val cv = 42L
        // Three specialists propose. QUALITY has the best score and
        // confidence — must be elected. CORE has middling score;
        // MANIPULATED has the highest raw score but the LOWEST
        // confidence — must lose.
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "QUALITY",
                score = 82.0, confidence = 78.0, lanePriority = 20, reason = "QUALITY_PROPOSAL_TEST",
            ))
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "CORE",
                score = 74.0, confidence = 62.0, lanePriority = 10, reason = "CORE_PROPOSAL_TEST",
            ))
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "MANIPULATED",
                score = 89.0, confidence = 40.0, lanePriority = 40, reason = "MANIPULATED_PROPOSAL_TEST",
            ))
        val election = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .elect6629(mint, cv)
        assertNotNull("V5.0.6629 §8: three proposals must elect a winner", election.elected)
        assertEquals("V5.0.6629 §8: highest-confidence lane must win",
            "QUALITY", election.elected!!.lane)
        assertEquals("V5.0.6629 §8: exactly two runners-up must be retained",
            2, election.runnersUp.size)
        val runnerLanes = election.runnersUp.map { it.lane }.toSet()
        assertTrue("V5.0.6629 §8: runners-up must include CORE + MANIPULATED",
            runnerLanes == setOf("CORE", "MANIPULATED"))
    }

    @Test
    fun aate6629_arbiter_duplicate_lane_replaces_older_proposal() {
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest()
        val mint = "aate6629-arbiter-mintB"
        val cv = 7L
        val first = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "DIP_HUNTER",
                score = 51.0, confidence = 55.0, lanePriority = 15, reason = "DIP_HUNTER_INITIAL",
            ))
        assertTrue("V5.0.6629 §8: first proposal from a lane must be marked first=true", first)
        val second = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "DIP_HUNTER",
                score = 71.0, confidence = 75.0, lanePriority = 15, reason = "DIP_HUNTER_REFINED",
            ))
        assertTrue("V5.0.6629 §8: second proposal from the same lane must be marked first=false", !second)
        val election = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .elect6629(mint, cv)
        assertEquals("V5.0.6629 §8: refined proposal must be used for election",
            75.0, election.elected!!.confidence, 0.001)
    }

    @Test
    fun aate6629_arbiter_election_is_memoized() {
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest()
        val mint = "aate6629-arbiter-mintC"
        val cv = 11L
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "TREASURY",
                score = 60.0, confidence = 60.0, lanePriority = 25, reason = "TREASURY_PROPOSAL",
            ))
        val a = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.elect6629(mint, cv)
        // A late proposal AFTER election must NOT change the elected lane.
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint = mint, candidateVersion = cv, lane = "CASHGEN",
                score = 99.0, confidence = 99.0, lanePriority = 5, reason = "CASHGEN_LATE",
            ))
        val b = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.elect6629(mint, cv)
        assertEquals("V5.0.6629 §8: election must be memoized (monotonic)",
            a.elected!!.lane, b.elected!!.lane)
    }

    @Test
    fun aate6629_arbiter_empty_contest_elects_nothing() {
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest()
        val election = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
            .elect6629("aate6629-arbiter-mintD", 99L)
        assertNull("V5.0.6629 §8: no proposals → no elected lane", election.elected)
        assertTrue("V5.0.6629 §8: empty contest has no runners-up",
            election.runnersUp.isEmpty())
    }

    @Test
    fun aate6641_empty_lookup_does_not_poison_late_specialist_proposal() {
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest()
        val mint = "aate6641-late-proposal"
        val cv = 6641L
        assertNull(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.elect6629(mint, cv).elected)
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.submitProposal6629(
            com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                mint, cv, "EXPRESS", 77.0, 0.77, 2, "LATE_FDG_CALLBACK",
            )
        )
        assertEquals("EXPRESS",
            com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.elect6629(mint, cv).elected?.lane)
    }

    @Test
    fun aate6629_arbiter_status_line_exposes_duplicate_buy_suppression() {
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.resetForTest()
        val mint = "aate6629-arbiter-mintE"
        val cv = 5L
        listOf("QUALITY", "CORE", "SHITCOIN").forEachIndexed { i, lane ->
            com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629
                .submitProposal6629(com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.Proposal6629(
                    mint = mint, candidateVersion = cv, lane = lane,
                    score = 70.0 - i, confidence = 70.0 - i, lanePriority = 10 + i, reason = "SUPPRESS_TEST",
                ))
        }
        com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.elect6629(mint, cv)
        val status = com.lifecyclebot.engine.truth.SpecialistProposalArbiter6629.statusLine6629()
        assertTrue("V5.0.6629 §8: three-lane contest must suppress two duplicate BUYs",
            status.contains("dupBuysSuppressed=2"))
    }

    @Test
    fun aate6629_pipeline_dump_publishes_hero_and_arbiter_blocks_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt"
        ).readText()
        assertTrue("V5.0.6629: pipeline dump must include the V5.0.6629 block",
            src.contains("CAUSAL AUTHORITY REPAIR (V5.0.6629)") &&
                src.contains("PaperEconomicSnapshot6629.statusLine6629") &&
                src.contains("SpecialistProposalArbiter6629.statusLine6629"))
    }

    @Test
    fun aate6629_hero_surfaces_route_through_snapshot_source_authority() {
        val mainSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
        ).readText()
        assertTrue("V5.0.6629 §6: MEME hero must read via PaperEconomicSnapshot6629",
            mainSrc.contains("PaperEconomicSnapshot6629.read6629(\"MEME\")"))
        val cryptoSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/CryptoAltActivity.kt"
        ).readText()
        assertTrue("V5.0.6629 §6: CRYPTO hero must read via PaperEconomicSnapshot6629",
            cryptoSrc.contains("PaperEconomicSnapshot6629.read6629(\"CRYPTO\")"))
        val marketsSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MultiAssetActivity.kt"
        ).readText()
        assertTrue("V5.0.6629 §6: MARKETS hero must read via PaperEconomicSnapshot6629",
            marketsSrc.contains("PaperEconomicSnapshot6629.read6629(\"MARKETS\")"))
    }
}
