package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.DipHunterAI
import com.lifecyclebot.v3.scoring.ProjectSniperAI
import org.junit.Assert.*
import org.junit.Test

/** The inverse is a candidate-specific opportunity opinion; it cannot change structural proof. */
class Aate8522OperatorOpportunityDoorTest {
    private fun choice(action: NativeOpportunityPolicy8522.Action, lane: String, reason: String, originalEligible: Boolean = false) =
        NativeOpportunityPolicy8522.Decision(
            mint = "mint8522", lane = lane, mode = "PAPER", candidateVersion = 7L, featureRevision = 3L,
            snapshotAtMs = 10_500L, assessmentAtMs = 10_400L, inputAtMs = 10_400L, action = action,
            originalEligible = originalEligible, originalReason = reason, selectionPriority = 57.0,
        )

    @Test fun setupAndScoreRefusalsAreOpportunityOnly() {
        val accepted = mapOf(
            "QUALITY" to "QUALITY SCORE TOO LOW: 38 < 55",
            "BLUECHIP" to "THRESHOLD_FAIL:score=31<55 conf=42<60",
            "SHITCOIN" to "WR_RECOVERY_SCORE_FLOOR: score=21<47",
            "MANIPULATED" to "SCORE_TOO_LOW_31<44",
            "EXPRESS" to "NO_CONTINUATION: first impulse ended",
            "PROJECT_SNIPER" to "LOW_CONFIDENCE: 31 < 50",
            "DIP_HUNTER" to "CONFIDENCE_LOW: 31% < 55%",
        )
        accepted.forEach { (lane, reason) ->
            assertTrue("$lane: $reason", NativeOpportunityPolicy8522.opportunityReason8522(lane, reason))
            assertTrue("$lane: $reason", NativeOpportunityPolicy8522.doorEligible8522(false, reason,
                choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, lane, reason)))
        }
    }

    @Test fun safetyLiquidityCapAndProofCannotBePromoted() {
        val hard = listOf(
            "NATIVE_MCAP_TOO_LOW", "NATIVE_LANE_LIQ_BELOW_FLOOR_8140",
            "LP_UNLOCKED_CONFIRMED_8522", "TOP_HOLDER_TOO_DOMINANT",
            "FREEZE_AUTHORITY_ACTIVE", "BUY_FINAL_AUTHORITY_PROOF_PENDING_8522",
            "PHASE_HANDOFF_IGNITION", "FALLING_KNIFE: 60% > 50%",
        )
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER")
        hard.forEach { reason -> lanes.forEach { lane ->
            assertFalse("$lane $reason", NativeOpportunityPolicy8522.opportunityReason8522(lane, reason))
            assertFalse("$lane $reason", NativeOpportunityPolicy8522.doorEligible8522(false, reason,
                choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, lane, reason)))
        } }
    }

    @Test fun inverseRefuseStopsAnOriginallyAllowedLane() {
        val p = choice(NativeOpportunityPolicy8522.Action.REFUSE, "SHITCOIN", "PASS", originalEligible = true)
        assertFalse(NativeOpportunityPolicy8522.doorEligible8522(true, "PASS", p))
        assertTrue(NativeOpportunityPolicy8522.doorEligible8522(true, "PASS", null))
        val original = SpecialistBrainBridge7542.Opinion("SHITCOIN", true, 82, 77, 0.02, "PASS",
            "BREAKOUT", "buy", "sell")
        val applied = NativeOpportunityPolicy8522.apply8522(original, p)
        assertFalse(applied.eligible)
        assertEquals(82, applied.score)
        assertEquals(77, applied.confidence)
        assertEquals("PASS", original.reason)
    }

    @Test fun inverseTakeDoesNotFabricateANativeScore() {
        val original = SpecialistBrainBridge7542.Opinion("QUALITY", false, 31, 35, 0.0,
            "QUALITY_SCORE_TOO_LOW:31<55", "BREAKOUT", "buy", "sell")
        val p = choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, "QUALITY", original.reason)
        val applied = NativeOpportunityPolicy8522.apply8522(original, p)
        assertTrue(applied.eligible)
        assertEquals(31, applied.score)
        assertEquals(35, applied.confidence)
        assertEquals(0.0, applied.requestedSizeSol, 0.0)
        assertFalse(original.eligible)
    }

    @Test fun aCarriedChoiceExpiresOnModeVersionRevisionOrTime() {
        val p = choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, "QUALITY", "QUALITY_SCORE_TOO_LOW:31<55")
        fun current(mode: String = "PAPER", version: Long = 7L, rev: Long = 3L, now: Long = 11_000L) =
            NativeOpportunityPolicy8522.current8522(p, "mint8522", "QUALITY", mode, version, rev, now)
        assertNotNull(current())
        assertNull(current(mode = "LIVE"))
        assertNull(current(version = 8L))
        assertNull(current(rev = 4L))
        assertNull(current(now = 13_001L))
        assertNull(NativeOpportunityPolicy8522.current8522(p, "other-mint", "QUALITY", "PAPER", 7L, 3L, 11_000L))
    }

    @Test fun sniperAndDipCannotBypassAnUnrelatedHardNativeRefusal() {
        val sniper = ProjectSniperAI.TargetAssessment(false, "LOW_CONFIDENCE: 31 < 50",
            ProjectSniperAI.ThreatLevel.YELLOW, 0.0, 31, 80)
        val takeSniper = choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, "PROJECT_SNIPER", sniper.reason)
        val promoted = NativeOpportunityDoor8522.sniper8522(sniper, takeSniper)
        assertTrue(promoted.shouldEngage)
        assertTrue(promoted.positionSizeSol > 0.0)
        val unsafe = sniper.copy(reason = "LP_UNLOCKED_CONFIRMED_8522")
        assertFalse(NativeOpportunityDoor8522.sniper8522(unsafe, takeSniper).shouldEngage)

        val dip = DipHunterAI.DipSignal(false, 0.0, 31, "CONFIDENCE_LOW: 31% < 55%",
            DipHunterAI.DipQuality.NO_DIP, 0.0, 0.0)
        val takeDip = choice(NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE, "DIP_HUNTER", dip.reason)
        assertFalse(NativeOpportunityDoor8522.dip8522(dip, takeDip, 100.0, 100.0).shouldBuy)
        assertTrue(NativeOpportunityDoor8522.dip8522(dip, takeDip, 100.0, 120.0).shouldBuy)
        assertFalse(NativeOpportunityDoor8522.dip8522(dip.copy(reason = "FALLING_KNIFE: 60% > 50%"),
            takeDip, 100.0, 120.0).shouldBuy)
    }

    @Test fun sourceTimePolarityPolicyHasAnExplicitFiveSecondBound() {
        assertEquals("OPERATOR_INVERSE", com.lifecyclebot.engine.cortex.CortexOpportunityPolarity8522.POLICY_LABEL_8522)
        assertEquals(5_000L, com.lifecyclebot.engine.cortex.CortexOpportunityPolarity8522.MAX_AGE_MS_8522)
    }

    @Test fun staleOrUnprovedProjectionStaysAbstained() {
        val o = SpecialistBrainBridge7542.Opinion("QUALITY", false, 30, 35, 0.0,
            "QUALITY_SCORE_TOO_LOW:30<55", "BREAKOUT", "buy", "sell")
        val good = NativeOpportunityPolicy8522.decision8522(o, "TAKE_CANDIDATE", 6.2, "mint8522", "PAPER",
            7L, 3L, 10_500L, 10_400L, 10_400L, true)
        assertNotNull(good)
        assertNull(NativeOpportunityPolicy8522.decision8522(o, "TAKE_CANDIDATE", 6.2, "mint8522", "PAPER",
            7L, 3L, 10_500L, 10_400L, 10_400L, false))
        assertNull(NativeOpportunityPolicy8522.decision8522(o, "ABSTAIN", 6.2, "mint8522", "PAPER",
            7L, 3L, 10_500L, 10_400L, 10_400L, true))
        assertNull(NativeOpportunityPolicy8522.decision8522(o, "TAKE_CANDIDATE", Double.NaN, "mint8522", "PAPER",
            7L, 3L, 10_500L, 10_400L, 10_400L, true))
    }
}
