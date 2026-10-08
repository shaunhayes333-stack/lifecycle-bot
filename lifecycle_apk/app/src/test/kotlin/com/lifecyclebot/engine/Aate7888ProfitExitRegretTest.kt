package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitRegret7752
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7888 — profit exits learn from what the price did after them, like stops did in 7877. */
class Aate7888ProfitExitRegretTest {
    @Test fun profitExitsFollowedByRunsWidenTheTarget() {
        val ran = ExitRegret7752.Read7877(n = 20, meanRealized = 12.0, meanHold = 35.0, meanAfter = 20.0, holdBeatShare = 0.5)
        assertEquals(1.4, ExitRegret7752.stopMult7877(ran), 1e-9)
        val dumped = ExitRegret7752.Read7877(n = 20, meanRealized = 12.0, meanHold = 2.0, meanAfter = -8.0, holdBeatShare = 0.1)
        assertEquals(ExitRegret7752.STOP_MULT_MIN_7877, ExitRegret7752.stopMult7877(dumped), 1e-9)
    }

    @Test fun aStretchedPlanTargetLetsTheWinnerRunPastTheOldTarget() {
        val plan = TradePlan7739.Plan(TradePlan7739.Setup.PULLBACK_RECLAIM, -8.5, 11.1, 49.6, 0L)
        plan.firstTargetTaken = true
        val at50 = TradePlan7739.exitFor(plan, 50.0, 50.0, 300_000L, false, 4.0)
        assertTrue(at50!!.reason.startsWith("PLAN_TARGET_7739"))
        val stretched = TradePlan7739.exitFor(plan, 50.0, 50.0, 300_000L, false, 4.0, targetMult = 1.4)
        assertFalse(stretched?.reason?.startsWith("PLAN_TARGET_7739") ?: false)
    }
}
