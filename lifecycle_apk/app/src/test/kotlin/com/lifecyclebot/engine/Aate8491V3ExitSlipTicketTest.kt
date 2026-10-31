package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.LiquidityExitPathAI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8491 — V3's exit-liquidity score prices the round trip at the real ticket, not a fixed $75. */
class Aate8491V3ExitSlipTicketTest {
    @Test fun plannedSizeIsTheRealTicket() {
        assertEquals(0.02 * 180.0, LiquidityExitPathAI.plannedUsd8491(0.02, 180.0), 1e-9)
        assertEquals(LiquidityExitPathAI.FALLBACK_PLANNED_USD_8491, LiquidityExitPathAI.plannedUsd8491(Double.NaN, 180.0), 0.0)
        assertEquals(LiquidityExitPathAI.FALLBACK_PLANNED_USD_8491, LiquidityExitPathAI.plannedUsd8491(0.02, 0.0), 0.0)
        assertEquals(LiquidityExitPathAI.FALLBACK_PLANNED_USD_8491, LiquidityExitPathAI.plannedUsd8491(Double.MAX_VALUE, 180.0), 0.0)
        // a $3.60 ticket into a $1,000 pool is ~0.7% round trip; $75 read ~14%
        assertTrue(LiquidityExitPathAI.estimateRoundTripSlippagePct(3.6, 1_000.0) < 1.0)
        assertTrue(LiquidityExitPathAI.estimateRoundTripSlippagePct(75.0, 1_000.0) > 10.0)
    }
}
