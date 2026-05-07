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
        assertEquals(listOf(200, 500), SellSafetyPolicy.ladder("profit_lock_12.0x"))
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("profit_lock_12.0x"))
        assertEquals(listOf(200, 500, 800), SellSafetyPolicy.ladder("capital_recovery_12.0x"))
        assertEquals(800, SellSafetyPolicy.maxSlippageBps("capital_recovery_12.0x"))
    }

    @Test
    fun onlyManualEmergencyOrHardRugCanUseDrainLadder() {
        // V5.9.495z43 retune — operator spec item 4 dropped non-manual cap
        // from 1200 to 1000 bps. 9999 bps still requires manual panic flag.
        assertEquals(1000, SellSafetyPolicy.maxSlippageBps("emergency_exit"))
        assertEquals(9999, SellSafetyPolicy.maxSlippageBps("MANUAL_EMERGENCY_RUG_DRAIN"))
        assertEquals(9999, SellSafetyPolicy.maxSlippageBps("RUG_DRAIN"))
    }

    @Test
    fun pumpPortalPartialAndRescueAreDisabled() {
        assertFalse(SellSafetyPolicy.pumpPortalPartialSellEnabled)
        assertFalse(SellSafetyPolicy.pumpPortalRescueSellEnabled)
    }
}
