package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import org.junit.Assert.*
import org.junit.Test

class Aate8521NativeLaneMandateTest {
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
    private val protectedReasons = listOf(
        "MCAP_TOO_LOW: 2000", "MCAP too high for Quality (use BlueChip)", "LOW_MCAP", "HIGH_MCAP",
        "Too new: 1min < 5min", "TOO_YOUNG: 0.2h", "TOKEN_TOO_OLD_20m>12m", "TOO_FRESH: 2s < 19s",
        "BIRTH_METADATA_HYDRATING_7440", "NATIVE_MCAP_UNHYDRATED_DEFER_8236",
        "NATIVE_MOMENTUM_UNOBSERVED_DEFER_8236", "NATIVE_V3_UNSCORED_DEFER_8236", "PRICE_NOT_FRESH",
        "LANE_LIQ_BELOW_FLOOR_8140_MOONSHOT", "RUG_RISK", "SELLABILITY_REJECT", "DEV_SOLD",
        "POSITION_ALREADY_OPEN", "CYCLIC_RING_POSITION_ACTIVE", "DAILY_LIMIT: 10/10", "RECENT_TARGET: cooldown",
        "PHASE_HANDOFF_EXPANSION", "GRADUATED: not a launch", "TREASURY_ROLE_FLOOR_8031",
        "NO_KNOWN_MANIPULATION_EVIDENCE_7425",
    )

    private fun opinion(lane: String, eligible: Boolean = true, reason: String = "NATIVE_ALLOW") =
        SpecialistBrainBridge7542.Opinion(lane, eligible, 70, 70, 0.01, reason,
            "OWN_SETUP", "entry", "exit")

    @Test fun aWinningAuditCannotRetireAnyDeskIdentityOrMissingRequiredEvidence() {
        var audits = 0
        for (lane in lanes) for (reason in protectedReasons) {
            assertFalse("$lane: $reason", SpecialistBrainBridge7542.doorRetires8105(lane, false, reason) { _, _ ->
                audits++; true
            })
            assertFalse(reason, SpecialistBrainBridge7542.nativeRuleRetirable8048(reason))
            assertFalse(reason, RunnerGrabLane8343.mandateRefusal8343(reason))
        }
        assertEquals("binding rules never enter the retirement vote", 0, audits)
        for (reason in listOf("THRESHOLD_FAIL_SCORE", "NO_CONTINUATION", "LOW_CONFIDENCE: 40 < 50")) {
            assertTrue(SpecialistBrainBridge7542.doorRetires8105("EXPRESS", false, reason) { _, _ -> true })
            assertTrue(RunnerGrabLane8343.mandateRefusal8343(reason))
        }
    }

    @Test fun rejectingOneLaneDoesNotSuppressAnIndependentNativeSetup() {
        val out = linkedMapOf(
            "MOONSHOT" to opinion("MOONSHOT", false, "MCAP_TOO_LOW"),
            "SHITCOIN" to opinion("SHITCOIN", false, "TOO_OLD: 90h > 72h"),
            "EXPRESS" to opinion("EXPRESS"),
        )
        RunnerGrabLane8343.reviewNative8343(TokenState(mint = "mandate-test", symbol = "TEST"), out, 1_000L)
        assertFalse(out.getValue("MOONSHOT").eligible)
        assertFalse(out.getValue("SHITCOIN").eligible)
        assertEquals("OWN_SETUP", out.getValue("EXPRESS").setup)
        assertTrue(out.getValue("EXPRESS").eligible)
        assertTrue("CORE can still assess its own setup", RunnerGrabLane8343.coreYieldFalls8343(0,
            listOf("MCAP_TOO_LOW", "TOO_OLD: 90h > 72h")))
    }

    @Test fun aWatchlistAgeIsOnlyALowerBoundAndCannotAdmitALaunchLane() {
        val seen = NativeLaneAge8521.evidence8521(AdmissionBirth8144.Read8144(
            AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, 0L, 30_000L, "FIRST_SEEN"))
        assertNull(seen.exactMinutes)
        for (lane in listOf("SHITCOIN", "MANIPULATED", "QUALITY", "DIP_HUNTER", "CYCLIC")) {
            val reviewed = NativeLaneAge8521.review8521(opinion(lane), seen)
            assertFalse(lane, reviewed.eligible)
            assertFalse(lane, reviewed.gradeable)
            assertTrue(lane, reviewed.reason.startsWith("NATIVE_AGE_UNOBSERVED_DEFER_8521"))
        }
        // These lanes do not have a hard age requirement; missing birth does not add one.
        for (lane in listOf("BLUECHIP", "CASHGEN", "MOONSHOT", "TREASURY", "CORE", "EXPRESS"))
            assertTrue(lane, NativeLaneAge8521.review8521(opinion(lane), seen).eligible)
        val sniper = opinion("PROJECT_SNIPER", false, "BIRTH_METADATA_HYDRATING_7440")
        assertEquals("sniper retains its direct canonical birth check", sniper,
            NativeLaneAge8521.review8521(sniper, seen))
    }

    @Test fun provenOldEnoughQualifiesEstablishedLanesWithoutClaimingAnExactBirth() {
        val old = NativeLaneAge8521.Evidence(null, 180.0)
        for (lane in listOf("QUALITY", "DIP_HUNTER", "CYCLIC"))
            assertTrue(lane, NativeLaneAge8521.review8521(opinion(lane), old).eligible)
        val tooOld = NativeLaneAge8521.review8521(opinion("MANIPULATED"), old)
        assertFalse(tooOld.eligible)
        assertTrue("a real bound proves this refusal", tooOld.gradeable)
        assertTrue(tooOld.reason.startsWith("NATIVE_AGE_ABOVE_MAX_8521"))
        val stricterMinimum = NativeLaneAge8521.review8521(
            opinion("QUALITY", false, "Too new: 10min < 20min"), NativeLaneAge8521.Evidence(null, 10.0))
        assertFalse(stricterMinimum.eligible)
        assertFalse("a ten-minute lower bound cannot prove younger than twenty", stricterMinimum.gradeable)
    }

    @Test fun knownBirthEnforcesActualLaneAgeAndNewProofInvalidatesCachedNativeRead() {
        val exact = NativeLaneAge8521.Evidence(1.0, 1.0)
        assertTrue(NativeLaneAge8521.review8521(opinion("MANIPULATED"), exact).eligible)
        assertFalse(NativeLaneAge8521.review8521(opinion("DIP_HUNTER"), exact).eligible)
        val first = AdmissionBirth8144.Read8144(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, 0L, 30_000L, "FIRST_SEEN")
        val later = first.copy(minAgeMs = 31_000L)
        assertEquals(NativeLaneAge8521.fingerprint8521(first, 100_000L), NativeLaneAge8521.fingerprint8521(later, 101_000L))
        val proven = AdmissionBirth8144.Read8144(AdmissionBirth8144.Kind8144.KNOWN, 60_000L, 41_000L, "CHAIN_MINT_INIT")
        assertNotEquals(NativeLaneAge8521.fingerprint8521(later, 101_000L), NativeLaneAge8521.fingerprint8521(proven, 101_000L))
    }

    @Test fun boundedDesksWaitForAnObservedCapAndHydrationReturnsTheSameCandidateToAssessment() {
        for (lane in lanes) for (missing in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val read = NativeLaneConstraints8521.requireCap8521(opinion(lane), missing)
            if (lane == "CORE") assertTrue(read.eligible)
            else {
                assertFalse(lane, read.eligible)
                assertFalse(lane, read.gradeable)
                assertTrue(lane, ContinuationRead8348.dataDefer8348(read.reason))
            }
        }
        val original = opinion("EXPRESS")
        assertEquals(original, NativeLaneConstraints8521.requireCap8521(original, 20_000.0))
        assertFalse(NativeLaneConstraints8521.requireCap8521(
            opinion("TREASURY", false, "TREASURY_ROLE_FLOOR_8031"), 0.0).gradeable)
        HydrationReeval8343.resetForTests8343()
        try {
            val now = System.currentTimeMillis()
            val ts = TokenState(mint = "mcap-hydration-test", symbol = "TEST")
            val deferred = linkedMapOf("EXPRESS" to NativeLaneConstraints8521.requireCap8521(original, 0.0))
            RunnerGrabLane8343.reviewNative8343(ts, deferred, now)
            assertFalse(deferred.getValue("EXPRESS").eligible)
            ts.lastMcap = 20_000.0
            ts.lastLiquidityUsd = 4_000.0
            HydrationReeval8343.onHydrated8343(ts, ts.lastLiquidityUsd, now + 1L, "OBSERVED_CHAIN_TEST")
            assertTrue(HydrationReeval8343.targets8343(now + 1L).any { it.first == ts.mint })
            assertTrue(NativeLaneConstraints8521.requireCap8521(original, ts.lastMcap).eligible)
        } finally { HydrationReeval8343.resetForTests8343() }
    }
}
