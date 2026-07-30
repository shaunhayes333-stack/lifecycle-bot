package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6397 — Adaptive Floor Brain tests. The floor is fluid within
 * the V5.0.6396 envelope [12, 22], driven by scanner heat, score
 * distribution, per-lane learning and SuperAGI/SSI/LLM advisories.
 */
class Bundle6397AdaptiveFloorBrainTest {

    @Before fun setUp() {
        AdaptiveFloorBrain6397.clearAllForTest()
        ScoreDistributionHistogram6396.clearAllForTest()
        LiveEntryThresholdAuthority6396.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    // -------- baseline sanity ---------------------------------------------
    @Test fun neutral_baseline_no_signals_returns_baseline() {
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.5))
        assertEquals(15, rec.baseFloor)
        assertEquals(15, rec.finalFloor)   // 15 + 0 + 0 + 0 + 0 = 15
    }

    // -------- scanner heat pulls floor up ---------------------------------
    @Test fun scanner_very_hot_raises_floor_by_2() {
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.95))
        assertEquals(15, rec.baseFloor)
        assertEquals(2, rec.scannerDelta)
        assertEquals(17, rec.finalFloor)
    }

    @Test fun scanner_cool_lowers_floor_by_1() {
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.1))
        assertEquals(-1, rec.scannerDelta)
        assertEquals(14, rec.finalFloor)
    }

    // -------- lane learning drives relaxation / tightening ----------------
    @Test fun positive_lane_ev_relaxes_by_2_pts() {
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "MOONSHOT", trades = 20,
                winRatePct = 62.0, expectancySol = 0.05))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "MOONSHOT", scannerHeatPct01 = 0.5))
        assertEquals(-2, rec.laneDelta)
        assertEquals(13, rec.finalFloor)
    }

    @Test fun negative_lane_ev_tightens_by_2_pts() {
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "SHITCOIN", trades = 20,
                winRatePct = 32.0, expectancySol = -0.02))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.5))
        assertEquals(+2, rec.laneDelta)
        assertEquals(17, rec.finalFloor)
    }

    @Test fun lane_insufficient_trades_no_bias() {
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "SHITCOIN", trades = 3,
                winRatePct = 100.0, expectancySol = 1.0))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN"))
        assertEquals(0, rec.laneDelta)
    }

    // -------- SuperAGI / SSI / LLM advisories combine (capped) ------------
    @Test fun advisories_from_super_agi_ssi_llm_combine_and_cap() {
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SUPER_AGI, +3, "risk-off climate")
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SSI, +2, "post-CPI vol expansion")
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, +3, "narrative rotation")
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.5))
        // 3+2+3 = 8, capped at ADVISORY_COMBINED_CAP=5.
        assertEquals(5, rec.advisoryDelta)
        // 15 base + 5 advisory (no other signals) = 20, in-range.
        assertEquals(20, rec.finalFloor)
    }

    @Test fun advisory_channels_clamped_per_channel() {
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, +99, "spam")
        val active = AdaptiveFloorBrain6397.activeAdvisories()
        assertEquals(1, active.size)
        assertEquals(AdaptiveFloorBrain6397.ADVISORY_MAX_DELTA, active[0].signedDelta)
    }

    @Test fun stale_advisories_are_ignored() {
        val now = 10_000_000L
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, +2, "old",
            nowMs = now - AdaptiveFloorBrain6397.ADVISORY_STALE_MS - 1L)
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", nowMs = now))
        assertEquals(0, rec.advisoryDelta)
    }

    // -------- score distribution nudges floor toward its median -----------
    @Test fun distribution_median_nudges_floor_within_plus_or_minus_2() {
        for (i in 1..80) ScoreDistributionHistogram6396.record(
            ScoreDistributionHistogram6396.Bucket.HARD_SAFETY_PASSED, 18.0)
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.5))
        // median=18, base=15 → diff=+3, but capped at +2 by design.
        assertEquals(+2, rec.distributionDelta)
        assertEquals(17, rec.finalFloor)
    }

    // -------- envelope is absolute — 55/56 remain unreachable -------------
    @Test fun no_combination_of_signals_can_reach_legacy_55_or_56() {
        // Push everything up as hard as possible.
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SUPER_AGI, +3, "max")
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SSI, +3, "max")
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, +3, "max")
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "SHITCOIN", trades = 50,
                winRatePct = 25.0, expectancySol = -0.10))
        for (i in 1..80) ScoreDistributionHistogram6396.record(
            ScoreDistributionHistogram6396.Bucket.HARD_SAFETY_PASSED, 22.0)
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.TIGHTENED,
                lane = "SHITCOIN", scannerHeatPct01 = 0.99))
        assertTrue(rec.finalFloor <= LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
        assertNotEquals(55, rec.finalFloor)
        assertNotEquals(56, rec.finalFloor)
    }

    @Test fun no_combination_of_signals_can_drop_below_absolute_min() {
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SUPER_AGI, -3, "min")
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.SSI, -3, "min")
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, -3, "min")
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "SHITCOIN", trades = 50,
                winRatePct = 80.0, expectancySol = 0.20))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.05))
        assertTrue(rec.finalFloor >= LiveEntryThresholdAuthority6396.ABSOLUTE_MIN)
    }

    // -------- snapshotFor composes the full canonical envelope ------------
    @Test fun snapshotFor_produces_a_valid_canonical_snapshot_with_all_deltas() {
        AdaptiveFloorBrain6397.postAdvisory(
            AdaptiveFloorBrain6397.AdvisoryChannel.LLM, +1, "narrative")
        val snap = AdaptiveFloorBrain6397.snapshotFor(
            decisionId = "d97", mint = "mintFluid", lane = "SHITCOIN",
            rawScore = 16.0, effectiveScore = 16.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
            metricEpoch = 97L)
        assertTrue(snap.finalFloor in
            LiveEntryThresholdAuthority6396.ABSOLUTE_MIN..LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
        assertEquals("V5.0.6396.EFFECTIVE_0_30", snap.scoreScaleVersion)
    }
}
