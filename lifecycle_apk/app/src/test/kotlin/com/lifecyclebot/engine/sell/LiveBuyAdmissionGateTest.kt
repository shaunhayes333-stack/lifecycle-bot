package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.SafetyTier
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * V5.9.756 — Emergent CRITICAL ticket smoke tests.
 *
 * V5.0.3844 contract:
 *   - True SafetyTier.HARD_BLOCK still blocks.
 *   - Missing/stale safety without a true hard reason is PENALTY_ONLY/pending-proof
 *     and must not stop live evaluation before quote/pretrade checks.
 *
 * These use LiveBuyAdmissionGate.evaluateForTest which mirrors the
 * production decision logic without writing forensics. The real gate is
 * exercised in the Executor integration paths (liveBuy.main + liveTopUp).
 *
 * House style: JUnit 4 + org.junit.Assert.* — matches every other test
 * in this repo (SellSafetyPolicyTest, LiveExecutionGateTest, etc).
 */
class LiveBuyAdmissionGateTest {

    private val NOW = 1_700_000_000_000L  // arbitrary fixed clock for tests

    private fun assertBlocked(
        decision: LiveBuyAdmissionGate.Decision,
        expectedReason: String,
    ) {
        if (decision !is LiveBuyAdmissionGate.Decision.Blocked) {
            fail("Expected Blocked($expectedReason) but got $decision")
            return
        }
        assertEquals(expectedReason, decision.reasonCode)
    }

    @Test
    fun safety_missing_is_penalty_only_not_hard_block() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = 0L,
            nowMs = NOW,
        )
        assertTrue("Expected Approved/PENALTY_ONLY path for missing soft safety but got $d", d === LiveBuyAdmissionGate.Decision.Approved)
    }

    @Test
    fun safety_stale_is_penalty_only_not_hard_block() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = NOW - (LiveBuyAdmissionGate.SAFETY_STALE_MS + 5_000L),
            nowMs = NOW,
        )
        assertTrue("Expected Approved/PENALTY_ONLY path for stale soft safety but got $d", d === LiveBuyAdmissionGate.Decision.Approved)
    }

    @Test
    fun safety_hard_block_tier_blocks() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.HARD_BLOCK,
            lastSafetyCheckMs = NOW - 10_000L,
            nowMs = NOW,
        )
        assertBlocked(d, "SAFETY_HARD_BLOCK")
    }

    @Test
    fun fresh_safe_report_is_approved() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = NOW - 30_000L,
            nowMs = NOW,
        )
        assertTrue(
            "Expected Approved but got $d",
            d === LiveBuyAdmissionGate.Decision.Approved,
        )
    }

    @Test
    fun caution_tier_is_approved_because_only_HARD_BLOCK_rejects() {
        // CAUTION downgrades sizing/scoring upstream — NOT a hard block at
        // the executor admission boundary. Pins the gate boundary.
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.CAUTION,
            lastSafetyCheckMs = NOW - 30_000L,
            nowMs = NOW,
        )
        assertTrue(
            "Expected Approved for CAUTION but got $d",
            d === LiveBuyAdmissionGate.Decision.Approved,
        )
    }

    @Test
    fun safety_check_at_exact_boundary_is_fresh() {
        // age == SAFETY_STALE_MS → still fresh (production uses strict >).
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = NOW - LiveBuyAdmissionGate.SAFETY_STALE_MS,
            nowMs = NOW,
        )
        assertTrue(
            "Expected Approved at exact boundary but got $d",
            d === LiveBuyAdmissionGate.Decision.Approved,
        )
    }
}
