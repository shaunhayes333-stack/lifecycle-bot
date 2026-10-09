package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7809 — LearningAttribution: every canonical position keeps the exact
 * entry identity that produced it, and every clean close feeds that identity
 * (not settlement-time mint state) back to the learners.
 */
class Aate7809LearningAttributionTest {

    private fun read(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    // ── 23: hypothesis / strategy-variant binding ──

    @Test fun second_bind_of_the_same_position_returns_its_variant_instead_of_missing() {
        StrategyHypothesisEngine.reset()
        StrategyHypothesisEngine.getSizeBias("QUALITY", 70, "NORMAL", "Mint7809A", "MEME>SETUP>STYLE>MOMENTUM", 11L)
        val first = StrategyHypothesisEngine.bindExecutedPosition7428("pos7809A", "Mint7809A", 11L, "QUALITY")
        val second = StrategyHypothesisEngine.bindExecutedPosition7428("pos7809A", "Mint7809A", 11L, "QUALITY")
        assertEquals("QUALITY_BASELINE", first)
        assertEquals(first, second)
    }

    @Test fun only_the_exact_candidate_version_and_owner_lane_bind() {
        StrategyHypothesisEngine.reset()
        StrategyHypothesisEngine.getSizeBias("QUALITY", 70, "NORMAL", "Mint7809B", "", 20L)
        assertEquals("", StrategyHypothesisEngine.bindExecutedPosition7428("pos7809B", "Mint7809B", 21L, "QUALITY"))
        assertEquals("QUALITY_BASELINE", StrategyHypothesisEngine.bindExecutedPosition7428("pos7809B", "Mint7809B", 20L, "QUALITY"))
        StrategyHypothesisEngine.getSizeBias("CORE", 70, "NORMAL", "Mint7809C", "", 30L)
        assertEquals("", StrategyHypothesisEngine.bindExecutedPosition7428("pos7809C", "Mint7809C", 30L, "QUALITY"))
    }

    @Test fun unseeded_execution_lanes_get_a_neutral_baseline_variant() {
        assertEquals("CYCLIC_BASELINE", com.lifecyclebot.engine.learning.StrategyVariantStore.activeFor("CYCLIC")?.id)
        assertEquals(0, com.lifecyclebot.engine.learning.StrategyVariantStore.activeFor("CYCLIC")?.samples?.get())
        assertEquals(null, com.lifecyclebot.engine.learning.StrategyVariantStore.activeFor("NOT_A_LANE_7809"))
        StrategyHypothesisEngine.reset()
        StrategyHypothesisEngine.getSizeBias("CORE", 70, "NORMAL", "Mint7809D", "", 40L)
        assertEquals("CORE_BASELINE", StrategyHypothesisEngine.bindExecutedPosition7428("pos7809D", "Mint7809D", 40L, "CORE"))
    }

    @Test fun excluded_close_releases_the_hypothesis_binding_without_training() {
        StrategyHypothesisEngine.reset()
        StrategyHypothesisEngine.getSizeBias("QUALITY", 70, "NORMAL", "Mint7809E", "", 50L)
        StrategyHypothesisEngine.bindExecutedPosition7428("pos7809E", "Mint7809E", 50L, "QUALITY")
        val before = StrategyHypothesisEngine.outcomeUpdateCount6512()
        StrategyHypothesisEngine.releasePosition7809("pos7809E")
        StrategyHypothesisEngine.recordOutcomeForPosition7428("pos7809E", 25.0)
        assertEquals(before, StrategyHypothesisEngine.outcomeUpdateCount6512())
        assertEquals("", StrategyHypothesisEngine.bindExecutedPosition7428("pos7809E", "Mint7809E", 50L, "QUALITY"))
    }

    @Test fun unified_policy_trains_the_bound_owner_across_lane_spellings_and_releases_excluded() {
        val before = UnifiedPolicyHead.trainedCount()
        assertTrue(UnifiedPolicyHead.bindDecisionFallback6713("pos7809U", "Mint7809U", "BLUE_CHIP", 60.0, 0.5, 5.0, 0.1, 0.5))
        assertTrue(UnifiedPolicyHead.recordOutcome6681("pos7809U", "Mint7809U", "BLUECHIP", 12.0))
        assertEquals(before + 1L, UnifiedPolicyHead.trainedCount())
        assertTrue(UnifiedPolicyHead.bindDecisionFallback6713("pos7809V", "Mint7809V", "QUALITY", 60.0, 0.5, 5.0, 0.1, 0.5))
        UnifiedPolicyHead.releasePosition7809("pos7809V")
        UnifiedPolicyHead.recordOutcome6681("pos7809V", "Mint7809V", "QUALITY", 12.0)
        assertEquals(before + 1L, UnifiedPolicyHead.trainedCount())
    }

    @Test fun excluded_terminal_releases_position_bound_learners_and_cross_asset_skips_tactics() {
        val bridge = read("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertEquals(4, Regex("releaseExcludedBinding7809\\(").findAll(bridge).count())
        assertTrue(bridge.contains("UnifiedPolicyHead.releasePosition7809(env.positionId)"))
        assertTrue(bridge.contains("StrategyHypothesisEngine.releasePosition7809(env.positionId)"))
        assertTrue(bridge.contains("TACTIC_NOT_APPLICABLE_CROSS_ASSET_7809"))
    }

    // ── 24: oracle evidence population and exact grading ──

    @Test fun book_wide_history_level_stays_global_so_it_cannot_authorise_refusals() {
        val oracle = read("engine/truth/PredictiveEntryOracle6915.kt")
        // Inspect Level construction, not the comment documenting the retired names.
        assertFalse(Regex("Level\\s*\\(\\s*\"global(?:Live|Paper)\"").containsMatchIn(oracle))
        assertTrue(Regex("Level\\s*\\(\\s*\"global\"").containsMatchIn(oracle))
        assertTrue(oracle.contains("levels.filter { it.name != \"global\" }"))
    }

    @Test fun oracle_freezes_the_owner_lane_forecast_on_the_position() {
        com.lifecyclebot.engine.truth.OracleEdgeProof7263.resetForTest()
        val f = com.lifecyclebot.engine.truth.PredictiveEntryOracle6915.Forecast(
            com.lifecyclebot.engine.truth.PredictiveEntryOracle6915.Verdict.ADMIT, 5.0, 0.6, 0.5, emptyList(), "test",
        )
        com.lifecyclebot.engine.truth.OracleEdgeProof7263.stampLane7809("Mint7809O", "QUALITY", f)
        assertTrue(com.lifecyclebot.engine.truth.OracleEdgeProof7263.bindPosition7809("pos7809O", "Mint7809O", "QUALITY"))
        assertFalse(com.lifecyclebot.engine.truth.OracleEdgeProof7263.bindPosition7809("pos7809P", "Mint7809None", "QUALITY"))
        val proof = read("engine/truth/OracleEdgeProof7263.kt")
        assertTrue(proof.contains("val bound7809 = positionStamps7809.remove(event.positionId)"))
        assertTrue(read("engine/truth/LearnedAdmissionInputs6909.kt").contains("OracleEdgeProof7263.stampLane7809(mint, laneKey, oracle6915)"))
    }

    @Test fun canonical_open_is_the_single_attribution_boundary() {
        val cpa = read("engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(cpa.contains("LearningAttributionBinder7809.onCanonicalOpen7809(positionId, mint, lane)"))
        val binder = read("engine/truth/LearningAttributionBinder7809.kt")
        assertTrue(binder.contains("OracleEdgeProof7263.bindPosition7809("))
        assertTrue(binder.contains("LaneHunter7297.bindPosition7809("))
        assertTrue(binder.contains("SpecialistCandidateBooks7803.bindPosition7809("))
    }

    // ── 25: forward label bookout ──

    @Test fun horizon_labels_book_only_inside_their_own_window() {
        val L = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        // V5.0.7946 — grace is half the horizon, 1 to 10 minutes.
        assertTrue(L.horizonOpen7809(5L * 60_000L, 5L * 60_000L))
        assertTrue(L.horizonOpen7809(450_000L, 5L * 60_000L))
        assertFalse(L.horizonOpen7809(451_000L, 5L * 60_000L))
        assertFalse(L.horizonOpen7809(4L * 60_000L, 5L * 60_000L))
        assertTrue(L.horizonOpen7809(70L * 60_000L, 60L * 60_000L))
        assertFalse(L.horizonOpen7809(71L * 60_000L, 60L * 60_000L))
    }

    @Test fun offwatch_batch_rotates_and_label_ticks_run_before_shadow_positions() {
        val labeler = read("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(labeler.contains("rotateDue7809("))
        assertTrue(labeler.contains("for (m in batch) offWatchAttemptAt7809[m] = nowMs"))
        val bs = read("engine/BotService.kt")
        assertTrue(bs.indexOf("ForwardReturnLabeler7731.tick({ m ->") < bs.indexOf("executor.checkShadowPositions(tokenStatesCopy)"))
    }

    // ── 26: hunters / resident books graded by the owner that produced the entry ──

    @Test fun resident_book_grades_only_the_owner_lane_that_held_the_candidate() {
        val books = com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
        books.resetForTests()
        books.publishHunt("MOONSHOT", "Mint7809S", "S7809")
        assertTrue(books.bindPosition7809("pos7809S", "Mint7809S", "MOONSHOT"))
        assertFalse(books.bindPosition7809("pos7809T", "Mint7809S", "QUALITY"))
        books.gradeSettled7809("pos7809S", true)
        books.gradeSettled7809("pos7809S", true)
        assertTrue(books.gradedLine7809().contains("MOONSHOT=1/1W"))
        assertTrue(books.gradedLine7809().contains("QUALITY=0/0W"))
    }

    @Test fun lane_hunter_prefers_the_claim_frozen_at_open() {
        val hunter = read("engine/market/LaneHunter7297.kt")
        assertTrue(hunter.contains("val boundClaim7809 = boundClaims7809.remove(e.positionId)"))
        assertTrue(hunter.contains("val c = boundClaim7809 ?: listOfNotNull(direct7803, cashgen7803)"))
        val settled = hunter.substringAfter("private fun onSettled(").substringBefore("fun statusLine()")
        assertTrue(settled.indexOf("isCleanForLearning7807(e)") < settled.indexOf("gradeSettled7809("))
    }
}
