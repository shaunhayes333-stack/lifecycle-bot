package com.lifecyclebot.engine.sell

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** V5.9.601 sell-safety acceptance checks for policy + per-mint lock. */
class SellSafetyPolicyTest {
    @Test
    fun sameMintCannotAcquireTwoSellLocks() {
        val mint = "TEST_MINT_LOCK"
        SellExecutionLocks.release(mint)
        assertTrue(SellExecutionLocks.tryAcquire(mint))
        assertFalse(SellExecutionLocks.tryAcquire(mint))
        SellExecutionLocks.release(mint)
        assertTrue(SellExecutionLocks.tryAcquire(mint))
        SellExecutionLocks.release(mint)
    }

    @Test
    fun profitLockAndCapitalRecoveryNeverUseDrainSlippage() {
        // V5.9.1533 — operator spec item 2: HARD 500bps cap for all non-emergency
        // LIVE sells. The 800/1000bps ladder is GONE from live mode. Non-emergency
        // walk is 200→350→500, never exceeding 500.
        assertEquals(listOf(200, 350, 500), SellSafetyPolicy.ladder("profit_lock_12.0x"))
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("profit_lock_12.0x"))
        assertEquals(listOf(200, 350, 500), SellSafetyPolicy.ladder("capital_recovery_12.0x"))
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("capital_recovery_12.0x"))
    }

    @Test
    fun onlyManualEmergencyOrHardRugCanUseDrainLadder() {
        // V5.9.1533 — operator spec item 2: a plain "emergency_exit" string is NOT an
        // emergency exit (isEmergencyExit requires RUG/DRAIN or MANUAL+EMERGENCY) so it
        // is hard-capped at 500. Only HARD-RUG / MANUAL-EMERGENCY may exceed the cap.
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("emergency_exit"))
        assertEquals(9999, SellSafetyPolicy.maxSlippageBps("MANUAL_EMERGENCY_RUG_DRAIN"))
        assertEquals(9999, SellSafetyPolicy.maxSlippageBps("RUG_DRAIN"))
        // hard cap holds for the normal reasons
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("stop_loss"))
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("hard_stop"))
    }

    @Test
    fun pumpPortalPartialAndRescueAreDisabled() {
        assertFalse(SellSafetyPolicy.pumpPortalPartialSellEnabled)
        assertFalse(SellSafetyPolicy.pumpPortalRescueSellEnabled)
    }
}
