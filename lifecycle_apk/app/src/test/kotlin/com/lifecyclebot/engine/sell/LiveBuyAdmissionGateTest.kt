package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.SafetyTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * V5.9.756 — Emergent CRITICAL ticket smoke tests.
 *
 * Acceptance items 1, 2, 5 from the ticket:
 *   1. Live buy with missing safety report is blocked.
 *   2. Live buy with stale safety report is blocked.
 *   5. Live buy with safety pending is blocked.
 *
 * These use [LiveBuyAdmissionGate.evaluateForTest] which mirrors the
 * production decision logic without writing forensics. The real gate is
 * exercised in the Executor integration paths (liveBuy.main + liveTopUp).
 *
 * Items 3 (high ownership), 4 (unlocked liquidity), 6 (Pump Portal WS),
 * 7 (Moonshot override) are upstream of the gate — they are properties of
 * the [SafetyReport] producer (TokenSafetyChecker). The gate's contract is
 * "if tier == HARD_BLOCK, refuse" — which is what we test here.
 */
class LiveBuyAdmissionGateTest {

    private val NOW = 1_700_000_000_000L  // arbitrary fixed clock for tests

    @Test fun `safety missing blocks`() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = 0L,        // never checked
            nowMs = NOW,
        )
        assertIs<LiveBuyAdmissionGate.Decision.Blocked>(d)
        assertEquals("SAFETY_DATA_MISSING", d.reasonCode)
    }

    @Test fun `safety stale blocks`() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = NOW - (LiveBuyAdmissionGate.SAFETY_STALE_MS + 5_000L),
            nowMs = NOW,
        )
        assertIs<LiveBuyAdmissionGate.Decision.Blocked>(d)
        assertEquals("SAFETY_DATA_STALE", d.reasonCode)
    }

    @Test fun `safety hard block tier blocks`() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.HARD_BLOCK,
            lastSafetyCheckMs = NOW - 10_000L,  // fresh
            nowMs = NOW,
        )
        assertIs<LiveBuyAdmissionGate.Decision.Blocked>(d)
        assertEquals("SAFETY_HARD_BLOCK", d.reasonCode)
    }

    @Test fun `fresh safe report is approved`() {
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = NOW - 30_000L,  // 30 s ago
            nowMs = NOW,
        )
        assertEquals(LiveBuyAdmissionGate.Decision.Approved, d)
    }

    @Test fun `caution tier is approved (only HARD_BLOCK rejects)`() {
        // CAUTION downgrades sizing/scoring upstream (in scorer / FDG) — it is
        // NOT a hard block at the executor admission boundary. If operator
        // wants CAUTION to block live, that is a scorer/FDG policy change,
        // not a gate change. This test pins the boundary.
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.CAUTION,
            lastSafetyCheckMs = NOW - 30_000L,
            nowMs = NOW,
        )
        assertEquals(LiveBuyAdmissionGate.Decision.Approved, d)
    }

    @Test fun `safety check at exact boundary is fresh`() {
        // age == SAFETY_STALE_MS → still fresh (strict > in production).
        val d = LiveBuyAdmissionGate.evaluateForTest(
            safetyTier = SafetyTier.SAFE,
            lastSafetyCheckMs = NOW - LiveBuyAdmissionGate.SAFETY_STALE_MS,
            nowMs = NOW,
        )
        assertEquals(LiveBuyAdmissionGate.Decision.Approved, d)
    }
}
