package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6396 — 10 regression tests for LIVE SCORE-SCALE REALIGNMENT AND
 * FALSE BUY-FAILURE ELIMINATION.
 */
class Bundle6396LiveScoreScaleTest {

    @Before fun setUp() {
        LiveEntryThresholdAuthority6396.clearAllForTest()
        EntryRejectionDedupeCache6396.clearAllForTest()
        EntryRejectionTelemetry6396.clearAllForTest()
        ScoreDistributionHistogram6396.clearAllForTest()
        FdgFanoutControl6396.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    // -------- canonical scale sanity --------------------------------------
    @Test fun canonical_baseline_caution_tightened_match_directive() {
        assertEquals(15, LiveEntryThresholdAuthority6396.BASELINE)
        assertEquals(17, LiveEntryThresholdAuthority6396.CAUTION)
        assertEquals(20, LiveEntryThresholdAuthority6396.TIGHTENED)
        assertEquals(13, LiveEntryThresholdAuthority6396.POSITIVE_LANE)
        assertEquals(12, LiveEntryThresholdAuthority6396.ABSOLUTE_MIN)
        assertEquals(22, LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
    }

    // -------- (1) score=14, baseline floor=15 -----------------------------
    @Test fun `p1_1 score_14_baseline_15__rejected_before_executor__no_buyfail`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d1", mint = "mint1", lane = "SHITCOIN",
            rawScore = 14.0, effectiveScore = 14.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
            metricEpoch = 1L,
        )
        assertEquals(15, snap.finalFloor)
        val v = LiveEntryThresholdAuthority6396.admit(snap, hardSafetyPassed = true)
        assertEquals(LiveEntryThresholdAuthority6396.AdmissionOutcome.REJECT_SCORE_FLOOR, v.outcome)
        // Emit as ENTRY_REJECTED_SCORE_FLOOR (not BUY_FAILED).
        val startPolicy = EntryRejectionTelemetry6396.entryPolicyReject.get()
        EntryRejectionTelemetry6396.emitScoreFloorReject(
            EntryRejectionTelemetry6396.ScoreFloorRejectRecord(
                mint = "mint1", symbol = "S1", lane = "SHITCOIN",
                rawScore = 14.0, effectiveScore = 14.0, finalFloor = 15,
                scoreScaleVersion = snap.scoreScaleVersion, metricEpoch = 1L,
                decisionId = "d1", governorState = "BASELINE",
                hardSafetyPassed = true, recheckEligibleAt = 0L,
            )
        )
        assertEquals(startPolicy + 1L, EntryRejectionTelemetry6396.entryPolicyReject.get())
        assertEquals(1L, EntryRejectionTelemetry6396.scoreFloorReject.get())
        assertEquals(1L, EntryRejectionTelemetry6396.preExecReject.get())
        // Forbidden counter guard — must throw.
        val threw = try {
            EntryRejectionTelemetry6396.assertNotBuyFailure("BUY_FAILED"); false
        } catch (_: IllegalStateException) { true }
        assertTrue("must reject BUY_FAILED counter for score-floor reject", threw)
    }

    // -------- (2) score=15, baseline=15, all hard checks pass -------------
    @Test fun `p1_2 score_15_baseline_15__eligible_for_normal_live_execution`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d2", mint = "mint2", lane = "SHITCOIN",
            rawScore = 15.0, effectiveScore = 15.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
            metricEpoch = 2L,
        )
        val v = LiveEntryThresholdAuthority6396.admit(snap, hardSafetyPassed = true)
        assertEquals(LiveEntryThresholdAuthority6396.AdmissionOutcome.ADMIT, v.outcome)
    }

    // -------- (3) score=17, CAUTION floor=17 → eligible with CAUTION sizing
    @Test fun `p1_3 score_17_caution_17__eligible_for_live_with_caution_sizing`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d3", mint = "mint3", lane = "SHITCOIN",
            rawScore = 17.0, effectiveScore = 17.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.CAUTION,
            metricEpoch = 3L,
        )
        assertEquals(17, snap.finalFloor)
        val v = LiveEntryThresholdAuthority6396.admit(snap, hardSafetyPassed = true)
        assertEquals(LiveEntryThresholdAuthority6396.AdmissionOutcome.ADMIT, v.outcome)
    }

    // -------- (4) score=19, TIGHTENED floor=20 -> pre-executor rejection --
    @Test fun `p1_4 score_19_tightened_20__pre_executor_reject_no_exec_failure`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d4", mint = "mint4", lane = "SHITCOIN",
            rawScore = 19.0, effectiveScore = 19.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.TIGHTENED,
            metricEpoch = 4L,
        )
        assertEquals(20, snap.finalFloor)
        assertEquals(LiveEntryThresholdAuthority6396.AdmissionOutcome.REJECT_SCORE_FLOOR,
            LiveEntryThresholdAuthority6396.admit(snap, true).outcome)
    }

    // -------- (5) score=20, TIGHTENED floor=20 → eligible -----------------
    @Test fun `p1_5 score_20_tightened_20__eligible_for_live_execution`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d5", mint = "mint5", lane = "SHITCOIN",
            rawScore = 20.0, effectiveScore = 20.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.TIGHTENED,
            metricEpoch = 5L,
        )
        assertEquals(LiveEntryThresholdAuthority6396.AdmissionOutcome.ADMIT,
            LiveEntryThresholdAuthority6396.admit(snap, true).outcome)
    }

    // -------- (6) score=26 + hard LP veto → hard safety block regardless --
    @Test fun `p1_6 score_26_with_hard_lp_veto__hard_safety_blocks_regardless`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d6", mint = "mint6", lane = "SHITCOIN",
            rawScore = 26.0, effectiveScore = 26.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
            metricEpoch = 6L,
        )
        val v = LiveEntryThresholdAuthority6396.admit(snap, hardSafetyPassed = false)
        assertEquals(LiveEntryThresholdAuthority6396.AdmissionOutcome.REJECT_HARD_SAFETY, v.outcome)
    }

    // -------- (7) snapshot minScore and rejection min must agree ----------
    @Test fun `p1_7 snapshot_minScore_equals_rejection_min_via_parity_check`() {
        val snap = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d7", mint = "mint7", lane = "SHITCOIN",
            rawScore = 14.0, effectiveScore = 14.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
            metricEpoch = 7L,
        )
        // Five stages all publish the same finalFloor -> parity OK.
        val ok = LiveEntryThresholdAuthority6396.parityCheck(
            "LIVE_ENTRY_AUTHORITY" to snap.finalFloor,
            "FDG_EFFECTIVE" to snap.finalFloor,
            "EXEC_GATE" to snap.finalFloor,
            "LIVE_ENTRY_SAFETY_HOLD" to snap.finalFloor,
            "PRE_ENTRY_DECISION_RECORD" to snap.finalFloor,
        )
        assertTrue(ok.ok)
        // Any disagreement -> ENTRY_THRESHOLD_PARITY_FAIL.
        val fail = LiveEntryThresholdAuthority6396.parityCheck(
            "LIVE_ENTRY_AUTHORITY" to 15,
            "FDG_EFFECTIVE" to 15,
            "EXEC_GATE" to 16,   // divergent
        )
        assertFalse(fail.ok)
        assertEquals("ENTRY_THRESHOLD_PARITY_FAIL", fail.reason)
    }

    // -------- (8) unchanged mint rejected repeatedly → dedupe suppressed --
    @Test fun `p1_8 unchanged_mint_repeat_reject__one_canonical_plus_dedupe_zero_exec`() {
        val firstFresh = EntryRejectionDedupeCache6396.shouldEmitFresh(
            "mintR", "SHITCOIN", 8L, 15, "hs-v1", 14.0, nowMs = 10_000L)
        assertTrue(firstFresh)
        // Same key, same score, cooldown not expired → suppress.
        val secondSuppress = EntryRejectionDedupeCache6396.shouldEmitFresh(
            "mintR", "SHITCOIN", 8L, 15, "hs-v1", 14.0, nowMs = 10_500L)
        assertFalse(secondSuppress)
        val thirdSuppress = EntryRejectionDedupeCache6396.shouldEmitFresh(
            "mintR", "SHITCOIN", 8L, 15, "hs-v1", 14.0, nowMs = 15_000L)
        assertFalse(thirdSuppress)
        // Zero executor invocations must have been issued for this dedup case.
        assertEquals(0L, FdgFanoutControl6396.execInvocations.get())
        // Material score change (>=2 pts) allows fresh rejection.
        val fourthFresh = EntryRejectionDedupeCache6396.shouldEmitFresh(
            "mintR", "SHITCOIN", 8L, 15, "hs-v1", 16.5, nowMs = 15_500L)
        assertTrue(fourthFresh)
        // Cooldown expiry also allows fresh emission.
        val fifthFresh = EntryRejectionDedupeCache6396.shouldEmitFresh(
            "mintR", "SHITCOIN", 8L, 15, "hs-v1", 16.5,
            nowMs = 15_500L + EntryRejectionDedupeCache6396.COOLDOWN_MS + 1L)
        assertTrue(fifthFresh)
    }

    // -------- (9) shadow-lane opinion: zero FDG and zero executor ---------
    @Test fun `p1_9 shadow_lane_opinion__zero_fdg_and_zero_executor_invocations`() {
        val startFdg = FdgFanoutControl6396.decisionsIssued.get()
        val startExec = FdgFanoutControl6396.execInvocations.get()
        val allowed = FdgFanoutControl6396.canShadowLaneExecute("mintS")
        assertFalse("shadow lane must never be allowed to execute", allowed)
        assertEquals(startFdg, FdgFanoutControl6396.decisionsIssued.get())
        assertEquals(startExec, FdgFanoutControl6396.execInvocations.get())
    }

    // -------- (10) no combination may produce finalFloor >22 or <12 --------
    @Test fun `p1_10 no_combination_can_produce_finalFloor_out_of_12_22`() {
        // Extreme negative deltas — attempt to drop below 12.
        val low = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d10a", mint = "mintX", lane = "SHITCOIN",
            rawScore = 5.0, effectiveScore = 5.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
            governorDelta = -20, laneDelta = -20, personalityDelta = -20,
            volatilityDelta = -20, metricEpoch = 10L,
        )
        assertTrue(low.finalFloor >= LiveEntryThresholdAuthority6396.ABSOLUTE_MIN)
        // Extreme positive deltas — attempt to rise above 22 (also blocks legacy 55/56).
        val high = LiveEntryThresholdAuthority6396.snapshot(
            decisionId = "d10b", mint = "mintY", lane = "SHITCOIN",
            rawScore = 5.0, effectiveScore = 5.0,
            governorTier = LiveEntryThresholdAuthority6396.GovernorTier.HOLD,
            governorDelta = 50, laneDelta = 20, personalityDelta = 5,
            volatilityDelta = 5, metricEpoch = 10L,
        )
        assertTrue(high.finalFloor <= LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
        // Legacy raw thresholds 55/56 MUST clamp to ABSOLUTE_MAX.
        assertEquals(22, LiveEntryThresholdAuthority6396.clampFloor(55))
        assertEquals(22, LiveEntryThresholdAuthority6396.clampFloor(56))
        assertEquals(22, LiveEntryThresholdAuthority6396.clampFloor(50))
        assertNotEquals(55, LiveEntryThresholdAuthority6396.clampFloor(55))
    }

    // -------- positive-lane relax rule -----------------------------------------
    @Test fun positive_lane_relax_at_most_2_points_and_never_below_13() {
        val relaxed = LiveEntryThresholdAuthority6396.positiveLaneRelax(15, relaxPoints = 5)
        // 5 clamped to POSITIVE_LANE_MAX_RELAX=2 → 15-2=13
        assertEquals(13, relaxed)
        val relaxed2 = LiveEntryThresholdAuthority6396.positiveLaneRelax(15, relaxPoints = 1)
        assertEquals(14, relaxed2)
    }

    // -------- histogram/adaptive gate is sample-count gated --------------------
    @Test fun histogram_adaptive_baseline_requires_min_samples_and_clamps() {
        // < MIN_SAMPLES_FOR_ADAPT -> null (governor must not adapt on noise).
        for (i in 1..10) ScoreDistributionHistogram6396.record(
            ScoreDistributionHistogram6396.Bucket.HARD_SAFETY_PASSED, 18.0)
        org.junit.Assert.assertNull(
            "with 10 samples the adaptive gate must remain closed",
            ScoreDistributionHistogram6396.recommendAdaptiveBaseline())
        // Fill with a distribution centred at 18 → median ~18, clamp OK.
        for (i in 1..60) ScoreDistributionHistogram6396.record(
            ScoreDistributionHistogram6396.Bucket.HARD_SAFETY_PASSED, 18.0)
        val rec = ScoreDistributionHistogram6396.recommendAdaptiveBaseline()
        assertNotNull(rec)
        assertTrue(rec!! in LiveEntryThresholdAuthority6396.ABSOLUTE_MIN..LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
    }

    // -------- fdg dedupe: repeated identical decision reuses id ---------------
    @Test fun fdg_dedupe_repeated_identical_decision_reuses_decisionId() {
        val key = FdgFanoutControl6396.FdgKey("mintFDG", 42L, "SHITCOIN", "BUY")
        val d1 = FdgFanoutControl6396.issueOrReuse(key) { "did-A" }
        val d2 = FdgFanoutControl6396.issueOrReuse(key) { "did-B" }
        assertEquals(d1.decisionId, d2.decisionId)
        assertEquals("did-A", d1.decisionId)
        assertEquals(1L, FdgFanoutControl6396.decisionsIssued.get())
        assertEquals(1L, FdgFanoutControl6396.decisionsReused.get())
    }
}
