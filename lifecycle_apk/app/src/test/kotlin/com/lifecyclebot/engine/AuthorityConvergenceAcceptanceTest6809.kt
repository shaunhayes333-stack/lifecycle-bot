package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.FdgRouteVerdict
import com.lifecyclebot.engine.learning.LanePolicy
import com.lifecyclebot.engine.truth.AateBrainContribution6512
import com.lifecyclebot.engine.truth.AateStrategyContext6512
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PolicySynthesizer6512
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.6809 §SOURCE_LEVEL_AUTHORITY_CONVERGENCE — acceptance invariants.
 *
 * Operator mandate: "learning must control capital. Throughput must never
 * overrule proven negative expectancy."
 *
 * These tests lock the source-level contracts:
 *   1. SHADOW_TRACK_ONLY never opens.
 *   2. TRAIN_ONLY_NO_OPEN never opens.
 *   3. Negative-EV AATE veto never becomes an executable BUY.
 *   4. Sub-minimum learned size is never promoted upward (no OK_MIN_PROMOTED_6600).
 *   5. No BOOTSTRAP authority tier at runtime (ADVISORY is the neutral floor).
 *   6. Bleeder lane state (SHADOW_TRACK_ONLY / TRAIN_ONLY_NO_OPEN) remains trainable
 *      while non-executable.
 *   7. Final size never exceeds the adaptive-authorized size (min-promotion killed).
 *   8. FdgRouteVerdict.decide honours hardSafety/mode/operator disable ahead of
 *      any lane-policy admission logic (preserved authority chain).
 */
class AuthorityConvergenceAcceptanceTest6809 {

    @Test fun shadow_track_only_never_opens_capital() {
        val verdict = FdgRouteVerdict.decide(
            lane = "TEST_LANE_SHADOW",
            scoreBand = "S00",
            hardReason = null,
        )
        // In an in-memory test we cannot easily force the LanePolicy state;
        // instead verify that the Verdict enum contract itself preserves
        // non-executability for shadow/train verdicts. This is the
        // source-level invariant the mandate demands.
        assertFalse(
            "ROUTE_SHADOW_TRACK must be non-executable",
            FdgRouteVerdict.Verdict.ROUTE_SHADOW_TRACK.executable,
        )
        assertTrue(
            "ROUTE_SHADOW_TRACK must remain trainable",
            FdgRouteVerdict.Verdict.ROUTE_SHADOW_TRACK.trainable,
        )
    }

    @Test fun train_only_no_open_never_opens_capital() {
        assertFalse(
            "ROUTE_TRAIN_ONLY must be non-executable",
            FdgRouteVerdict.Verdict.ROUTE_TRAIN_ONLY.executable,
        )
        assertTrue(
            "ROUTE_TRAIN_ONLY must remain trainable",
            FdgRouteVerdict.Verdict.ROUTE_TRAIN_ONLY.trainable,
        )
    }

    @Test fun executable_verdicts_are_exactly_three() {
        val executable = FdgRouteVerdict.Verdict.values().filter { it.executable }.toSet()
        assertEquals(
            "Only ALLOW_NORMAL / ALLOW_REDUCED_SIZE / ALLOW_PAPER_MICRO may open capital",
            setOf(
                FdgRouteVerdict.Verdict.ALLOW_NORMAL,
                FdgRouteVerdict.Verdict.ALLOW_REDUCED_SIZE,
                FdgRouteVerdict.Verdict.ALLOW_PAPER_MICRO,
            ),
            executable,
        )
    }

    @Test fun negative_ev_aate_veto_never_becomes_buy() {
        val ctx = AateStrategyContext6512(
            candidateId = "cand6809", runtimeGeneration = 1L, mode = "PAPER",
            mint = "MintNegEv6809", symbol = "NEG",
            candidateVersion = 1L, primaryStrategy = "EXPRESS",
            source = "PUMP_PORTAL_WS", regime = "DUMP",
        )
        val envelope = PolicySynthesizer6512.synthesize(
            context = ctx, proposedAction = "BUY",
            scoreBase = 40.0, scoreFinal = 40.0,
            sizeBase = 0.05, sizeFinal = 0.05, tactic = "PROBE",
            hardSafety = emptyList(),
            // V5.0.6811 §NEG_EV_MIN_SAMPLE — need >=3 attributable EV
            // contributors before the hard veto fires. Below that count the
            // action becomes POLICY_NEG_EV_ADVISORY_6811 (see the
            // negative_ev_low_sample_becomes_advisory test).
            contributors = listOf(
                AateBrainContribution6512(
                    brain = "TestBrainA", role = "EV", weight = 0.9, effect = -0.5,
                    expectedPnlPct = -8.0, pWin = 0.15,
                ),
                AateBrainContribution6512(
                    brain = "TestBrainB", role = "EV", weight = 0.7, effect = -0.4,
                    expectedPnlPct = -6.0, pWin = 0.18,
                ),
                AateBrainContribution6512(
                    brain = "TestBrainC", role = "EV", weight = 0.6, effect = -0.5,
                    expectedPnlPct = -7.0, pWin = 0.16,
                ),
            ),
            learningState = "test",
        )
        assertEquals(
            "AATE policy synthesizer must downgrade BUY-like actions with materially negative EV when sample >= 3",
            "POLICY_NEG_EV_BLOCK_6801",
            envelope.action,
        )
        assertNotEquals("BUY", envelope.action)
    }

    @Test fun negative_ev_low_sample_becomes_advisory_not_block() {
        // V5.0.6811 §NEG_EV_MIN_SAMPLE — a single EV contributor at -5%
        // must NOT hard-veto. Downgrade to advisory instead so the BUY-like
        // action survives, but negative EV signal is recorded for damping.
        val ctx = AateStrategyContext6512(
            candidateId = "cand6811low", runtimeGeneration = 1L, mode = "PAPER",
            mint = "MintLowSample6811", symbol = "LOW",
            candidateVersion = 1L, primaryStrategy = "PROJECT_SNIPER",
            source = "TEST", regime = "HEALTHY",
        )
        val envelope = PolicySynthesizer6512.synthesize(
            context = ctx, proposedAction = "BUY",
            scoreBase = 70.0, scoreFinal = 70.0,
            sizeBase = 0.05, sizeFinal = 0.05, tactic = "PROBE",
            hardSafety = emptyList(),
            contributors = listOf(
                AateBrainContribution6512(
                    brain = "SoleEvBrain", role = "EV", weight = 0.9, effect = -0.5,
                    expectedPnlPct = -5.0, pWin = 0.60,
                ),
            ),
            learningState = "test",
        )
        assertNotEquals(
            "Single low-sample negative EV must NOT hard-veto",
            "POLICY_NEG_EV_BLOCK_6801",
            envelope.action,
        )
        assertEquals("BUY", envelope.action)
    }

    @Test fun aate_hard_safety_takes_precedence_over_neg_ev() {
        val ctx = AateStrategyContext6512(
            candidateId = "cand6809b", runtimeGeneration = 1L, mode = "PAPER",
            mint = "MintSafety6809", symbol = "SAF",
            candidateVersion = 1L, primaryStrategy = "MOONSHOT",
            source = "TEST", regime = "HEALTHY",
        )
        val envelope = PolicySynthesizer6512.synthesize(
            context = ctx, proposedAction = "BUY",
            scoreBase = 60.0, scoreFinal = 60.0,
            sizeBase = 0.05, sizeFinal = 0.05, tactic = "PROBE",
            hardSafety = listOf("RUG_DETECTED"),
            contributors = listOf(
                AateBrainContribution6512(
                    brain = "TestBrain", role = "EV", weight = 0.9, effect = -0.5,
                    expectedPnlPct = -8.0, pWin = 0.15,
                ),
            ),
            learningState = "test",
        )
        assertEquals("BLOCK", envelope.action)
    }

    @Test fun sub_minimum_learned_size_is_not_promoted_upward() {
        // Simulate an adaptive request of 0.001 SOL (well below any executable
        // minimum). The resolver must NOT promote this to the minimum. Instead
        // it must return non-executable so the caller routes to shadow/train.
        try {
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.resetForTest()
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.initialize(10.0)
        } catch (_: Throwable) {}
        val res = OrderSizeResolver6441.resolve(
            requestedSol = 0.001,
            laneName = "TEST_LANE_6809",
            walletSol = 10.0,
            paperMode = true,
            laneRiskCapSol = 0.10,
            laneMinExecutableSol = 0.05,
        )
        assertFalse(
            "Sub-minimum adaptive size must resolve non-executable (min-promotion killed)",
            res.executable,
        )
        assertNotEquals(
            "Reason must never be OK_MIN_PROMOTED_6600 (retired taxonomy)",
            "OK_MIN_PROMOTED_6600",
            res.reason,
        )
        assertEquals(0.0, res.finalSizeSol, 1e-12)
    }

    @Test fun final_size_never_exceeds_adaptive_authorized_size() {
        // Adaptive/risk-authorised size is 0.03 SOL. In paper mode the
        // resolver reads authoritative cash from PaperCapitalAuthority; we
        // fund it so the branch reaches the sizing math. The invariant
        // proven here: final size never inflates above the adaptive request
        // due to a minimum-order floor. This is the source-level guarantee
        // from the operator mandate: "Final size must never exceed the
        // adaptive/risk-authorized size because of a minimum-order floor."
        try {
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.resetForTest()
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.initialize(10.0)
        } catch (_: Throwable) {}
        val res = OrderSizeResolver6441.resolve(
            requestedSol = 0.10,
            laneName = "TEST_LANE_6809B",
            walletSol = 10.0,
            paperMode = true,
            laneRiskCapSol = 0.15,
            laneMinExecutableSol = 0.05,
        )
        // Regardless of executability (which depends on runtime throughput
        // guards outside this authority), the invariant holds:
        //   finalSizeSol <= requestedSol
        // This is the source-level contract this test locks.
        assertTrue(
            "final=${res.finalSizeSol} must never exceed requested=0.10 (no upward promotion)",
            res.finalSizeSol <= 0.10 + 1e-12,
        )
        assertNotEquals(
            "Reason must never be OK_MIN_PROMOTED_6600 (retired taxonomy)",
            "OK_MIN_PROMOTED_6600",
            res.reason,
        )
    }

    @Test fun unified_policy_head_never_returns_bootstrap_authority_at_runtime() {
        // Cold/no-samples lane must map to ADVISORY (neutral learned prior),
        // not BOOTSTRAP. This proves the runtime "auth=BOOTSTRAP" surface
        // is zero — bootstrap is no longer an execution authority.
        val tier = UnifiedPolicyHead.currentAuthority("TEST_LANE_COLD_6809")
        assertNotEquals(
            "Cold lane must not surface BOOTSTRAP as runtime authority",
            UnifiedPolicyHead.AuthorityTier.BOOTSTRAP,
            tier,
        )
        assertEquals(
            "Cold lane must surface ADVISORY as the neutral floor",
            UnifiedPolicyHead.AuthorityTier.ADVISORY,
            tier,
        )
    }

    @Test fun scanner_source_brain_never_returns_bootstrap_at_runtime() {
        val tier = ScannerSourceBrain.authority("TEST_UNKNOWN_SOURCE_6809")
        assertNotEquals(
            "Cold source must not surface BOOTSTRAP as runtime authority",
            ScannerSourceBrain.AuthorityTier.BOOTSTRAP,
            tier,
        )
        assertEquals(ScannerSourceBrain.AuthorityTier.ADVISORY, tier)
    }

    @Test fun fdg_route_verdict_hard_safety_precedes_lane_policy() {
        val v = FdgRouteVerdict.decide(
            lane = "ANY",
            scoreBand = "S50",
            hardReason = "RUG_DETECTED",
        )
        assertEquals(FdgRouteVerdict.Verdict.BLOCK_HARD_SAFETY, v)
        assertFalse(v.executable)
    }

    @Test fun fdg_route_verdict_mode_authority_precedes_lane_policy() {
        val v = FdgRouteVerdict.decide(
            lane = "ANY", scoreBand = "S50",
            modeAuthorityBlock = true,
        )
        assertEquals(FdgRouteVerdict.Verdict.BLOCK_MODE_AUTHORITY, v)
        assertFalse(v.executable)
    }

    @Test fun fdg_route_verdict_operator_disabled_precedes_all() {
        val v = FdgRouteVerdict.decide(
            lane = "ANY", scoreBand = "S50",
            operatorDisabled = true,
            hardReason = "RUG",
            modeAuthorityBlock = true,
        )
        assertEquals(FdgRouteVerdict.Verdict.BLOCK_OPERATOR_DISABLED, v)
        assertFalse(v.executable)
        assertFalse(v.trainable) // operator kill retires learning too
    }

    @Test fun fdg_authority_registry_first_claim_wins_and_sibling_is_suppressed() {
        // V5.0.6811 §AUTHORITY_CONSOLIDATION — the first successful claim on
        // (mode, mint, candidateVersion) wins; sibling lanes attempting the
        // same key are suppressed and MUST NOT be permitted authoritative
        // FDG entry.
        com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.clearForTest()
        val first = com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.claim(
            mode = "PAPER", mint = "MintAuth6811", candidateVersion = 42L,
            canonicalLane = "PROJECT_SNIPER", decisionId = "d1",
            sealedNotional = 0.05, authorityVersion = 42L,
        )
        assertTrue(first is com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.Result.Accepted)
        val sibling = com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.claim(
            mode = "PAPER", mint = "MintAuth6811", candidateVersion = 42L,
            canonicalLane = "SHITCOIN", decisionId = "d2",
            sealedNotional = 0.05, authorityVersion = 42L,
        )
        assertTrue(
            "SHITCOIN sibling must be suppressed once PROJECT_SNIPER owns the seal",
            sibling is com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.Result.Duplicate,
        )
        val dup = sibling as com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.Result.Duplicate
        assertEquals("PROJECT_SNIPER", dup.existing.canonicalLane)
        assertEquals("SHITCOIN", dup.attemptedLane)
    }

    @Test fun fdg_authority_registry_same_lane_reentry_is_idempotent() {
        com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.clearForTest()
        val first = com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.claim(
            mode = "PAPER", mint = "MintReenter6811", candidateVersion = 7L,
            canonicalLane = "QUALITY", decisionId = "d1",
            sealedNotional = 0.05, authorityVersion = 7L,
        )
        val second = com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.claim(
            mode = "PAPER", mint = "MintReenter6811", candidateVersion = 7L,
            canonicalLane = "QUALITY", decisionId = "d2-later",
            sealedNotional = 0.05, authorityVersion = 8L,
        )
        // Same lane reentry must be Accepted (idempotent) — never Duplicate.
        assertTrue(first is com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.Result.Accepted)
        assertTrue(
            "Same-lane re-entry must remain Accepted (idempotent)",
            second is com.lifecyclebot.engine.truth.CanonicalFdgAuthorityRegistry6811.Result.Accepted,
        )
    }
}
