package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class Aate8522NativeSafetyClassificationTest {
    @Test fun explicitOwnershipAndHolderFindingsCannotBecomeOpportunity() {
        for (reason in listOf(
            "LP_UNLOCKED_CONFIRMED_8522", "Large Amount of LP Unlocked", "LP_NOT_LOCKED",
            "LP_LOCK_BELOW_REQUIRED_8522: pool=actual locked=20% required=70%",
            "LP only 60% locked — needs ≥70% locked for live buy",
            "NATIVE_TOP_HOLDER_CONCENTRATION@QUALITY", "Top holder too dominant: 85% > 30%",
            "Top holder concentration high", "Top 10 holders high ownership", "Single holder ownership",
            "High ownership", "THRESHOLD_FAIL: conf=40<50;holder=75%>30%",
            "FATAL_WALLET_RISK_TEXT",
        )) {
            assertEquals(reason, NativeLaneConstraints8521.Kind.SAFETY, NativeLaneConstraints8521.kind8521(reason))
            assertFalse(reason, NativeLaneConstraints8521.mayAdapt8521(reason))
        }
    }

    @Test fun missingSafetyProofRemainsHydrationData() {
        for (reason in listOf(
            "LP_OWNERSHIP_PROOF_PENDING_8522", "LP lock status unknown", "HOLDER_DATA_PENDING",
            "HOLDER_DISTRIBUTION_PENDING", "HOLDER_DISTRIBUTION_RISK_UNVERIFIED_8269",
            "FREEZE_AUTHORITY_UNVERIFIED_7238", "MINT_AUTHORITY_UNKNOWN",
            "BUY_FINAL_AUTHORITY_PROOF_PENDING_8522", "LIVE_CRITICAL_PROOF_PENDING_8373",
            "LIVE_SAFETY_REPORT_NOT_FRESH_8269", "LIVE_SAFETY_POLICY_REFRESH_8522",
            "BUY_FINAL_SAFETY_CHANGED_8522", "BUY_ROUTE_PROOF_STALE_8522",
            "BUY_ROUTE_POOL_PROOF_PENDING_8522", "BUY_ROUTE_PLAN_INCOMPLETE_8522",
            "BUY_ROUTE_MINT_MISMATCH_8522", "BUY_EXECUTION_QUOTE_MISSING_8522", "TOKEN_MAP_PENDING",
        )) {
            assertEquals(reason, NativeLaneConstraints8521.Kind.DATA, NativeLaneConstraints8521.kind8521(reason))
            assertTrue(reason, NativeLaneConstraints8521.missingData8521(reason))
            assertFalse(reason, NativeLaneConstraints8521.mayAdapt8521(reason))
        }
    }

    @Test fun authorityRugAndStructuralRefusalsKeepTheirDirection() {
        for (reason in listOf(
            "BUY_FINAL_AUTHORITY_ACTIVE_8522", "MINT_AUTHORITY_ACTIVE", "FREEZE_AUTHORITY_ACTIVE_7238",
            "TOKEN2022_HOSTILE_EXTENSION_TRANSFERHOOK", "RUGCHECK_CONFIRMED_RUG",
            "STRUCTURAL_RISK_8136", "FRESH_LAUNCH_FIVE_MINUTE_CASCADE_7737",
            "FRESH_LAUNCH_SELL_DOMINANT_TAPE_7737", "FRESH_LAUNCH_ONE_WALLET_PUMP_7737",
            "FRESH_LAUNCH_CHASING_3X_FROM_CREATE_7737", "STAGE_PEAK_EXHAUSTION", "STAGE_DUMPING",
            "FALLING_KNIFE_8130",
            "DANGER: HOLDER_EXIT(-12) WEAK_BUYS(30%) LOW_VOL(20%) DEEP_DIP(55%)",
            "neutral_momentum;balanced_book_50;steady_velocity;exhaustion_or_spike_top;NO_CONFIRMED_SCALP_SETUP_7547",
        )) {
            assertEquals(reason, NativeLaneConstraints8521.Kind.SAFETY, NativeLaneConstraints8521.kind8521(reason))
            assertFalse(reason, NativeLaneConstraints8521.mayAdapt8521(reason))
        }
    }

    @Test fun benignHolderAndQualityPreferencesRemainAdaptable() {
        for (reason in listOf(
            "HOLDER_COUNT_TOO_LOW", "LOW_HOLDER_COUNT", "HOLDER_GROWTH_WEAK", "TOP_HOLDER_COUNT_TOO_LOW",
            "OWNERSHIP_CONVICTION_TOO_LOW", "QUALITY_SCORE_TOO_LOW", "THRESHOLD_FAIL_SCORE",
            "NO_CONTINUATION", "LOW_CONFIDENCE: 40 < 50", "CHASE_EXTENDED",
            "LEARNED_DANGER_BUCKET", "DANGER_BUCKET_SCORE_LOW", "EXHAUSTION_SCORE_LOW",
        )) {
            assertNull(reason, NativeLaneConstraints8521.kind8521(reason))
            assertTrue(reason, NativeLaneConstraints8521.mayAdapt8521(reason))
        }
    }

    @Test fun aWinningAuditCannotRetireTheseRiskOrProofRefusals() {
        var auditCalls = 0
        for (lane in listOf("QUALITY", "CASHGEN", "EXPRESS")) for (reason in listOf(
            "Top holder too dominant: 85% > 30%", "holder=75%>30%", "LP_UNLOCKED_CONFIRMED_8522",
            "LP_OWNERSHIP_PROOF_PENDING_8522", "BUY_FINAL_AUTHORITY_PROOF_PENDING_8522",
            "FRESH_LAUNCH_ONE_WALLET_PUMP_7737",
            "DANGER: HOLDER_EXIT(-12) WEAK_BUYS(30%) LOW_VOL(20%) DEEP_DIP(55%)",
            "neutral_momentum;balanced_book_50;steady_velocity;exhaustion_or_spike_top;NO_CONFIRMED_SCALP_SETUP_7547",
        )) {
            assertFalse("$lane: $reason", SpecialistBrainBridge7542.doorRetires8105(lane, false, reason) { _, _ ->
                auditCalls++; true
            })
            assertFalse(reason, RunnerGrabLane8343.mandateRefusal8343(reason))
        }
        assertEquals("hard risk and missing proof never enter the opportunity vote", 0, auditCalls)
    }

    @Test fun confirmedAdverseEvidenceStillWinsWhenOtherProofIsMissing() {
        val reason = "LP_UNLOCKED_CONFIRMED_8522|HOLDER_DATA_UNKNOWN"
        assertEquals(NativeLaneConstraints8521.Kind.SAFETY, NativeLaneConstraints8521.kind8521(reason))
        assertFalse(NativeLaneConstraints8521.mayAdapt8521(reason))
    }

    @Test fun existingLiquidityAndLaneMandatesStayProtected() {
        val reasons = mapOf(
            "NATIVE_LANE_LIQ_BELOW_FLOOR_8140_MOONSHOT" to NativeLaneConstraints8521.Kind.LIQUIDITY,
            "NATIVE_CASHFLOW_ROLE_LIQ_FLOOR_8522" to NativeLaneConstraints8521.Kind.LIQUIDITY,
            "NATIVE_CASHFLOW_ROLE_MCAP_FLOOR_8522" to NativeLaneConstraints8521.Kind.CAP,
            "NATIVE_TREASURY_ROLE_FLOOR_8031" to NativeLaneConstraints8521.Kind.LANE_SCOPE,
            "NATIVE_AGE_ABOVE_MAX_8521" to NativeLaneConstraints8521.Kind.AGE,
            "POSITION_ALREADY_OPEN" to NativeLaneConstraints8521.Kind.LIFECYCLE,
        )
        for ((reason, kind) in reasons) {
            assertEquals(reason, kind, NativeLaneConstraints8521.kind8521(reason))
            assertFalse(reason, NativeLaneConstraints8521.mayAdapt8521(reason))
        }
    }
}
