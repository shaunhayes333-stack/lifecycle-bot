package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.495z29 — Acceptance test for operator spec item 6 (slippage profiles).
 */
class SellSlippageProfileTest {

    @Test
    fun normal_profit_lock_uses_sane_initial() {
        val p = SellSlippageProfile.forTier(SellSlippageProfile.Tier.NORMAL_PROFIT_LOCK)
        // V5.9.495z38 retune — operator-reported real-money safety bug
        // forced PROFIT_LOCK initial down from 1500 → 300 bps. The
        // ladder (3 → 5 → 8 %) caps at 800 bps. Test updated to match.
        assertEquals(300, p.initialBps)
        assertTrue("ladder must escalate", p.ladderBps.zipWithNext().all { it.first <= it.second })
    }

    @Test
    fun emergency_drain_uses_higher_initial() {
        val n = SellSlippageProfile.forTier(SellSlippageProfile.Tier.NORMAL_PROFIT_LOCK)
        val r = SellSlippageProfile.forTier(SellSlippageProfile.Tier.CAPITAL_RECOVERY)
        val e = SellSlippageProfile.forTier(SellSlippageProfile.Tier.EMERGENCY_RUG_DRAIN)
        assertTrue(n.initialBps < r.initialBps)
        assertTrue(r.initialBps < e.initialBps)
    }

    @Test
    fun reason_string_routes_to_correct_tier() {
        assertEquals(SellSlippageProfile.Tier.EMERGENCY_RUG_DRAIN,
            SellSlippageProfile.tierForReason("RAPID_CATASTROPHE_STOP"))
        assertEquals(SellSlippageProfile.Tier.EMERGENCY_RUG_DRAIN,
            SellSlippageProfile.tierForReason("RAPID_HARD_FLOOR_STOP"))
        assertEquals(SellSlippageProfile.Tier.CAPITAL_RECOVERY,
            SellSlippageProfile.tierForReason("CAPITAL_RECOVERY"))
        assertEquals(SellSlippageProfile.Tier.NORMAL_PROFIT_LOCK,
            SellSlippageProfile.tierForReason("PROFIT_LOCK"))
        assertEquals(SellSlippageProfile.Tier.NORMAL_PROFIT_LOCK,
            SellSlippageProfile.tierForReason("PARTIAL_PROFIT_LOCK"))
        assertEquals(SellSlippageProfile.Tier.NORMAL_PROFIT_LOCK,
            SellSlippageProfile.tierForReason("RAPID_DRAWDOWN_FROM_PEAK_STOP"))
    }
}
