package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class Aate8521HydrationLaneRetryTest {
    private val now = 10_000_000L
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")

    @Test fun hydratedEstablishedTokensReturnToTheirOwnLaneAssessment() {
        for (lane in lanes) {
            val d = HydrationReeval8343.Deferred8343("token-$lane", setOf(lane), "NATIVE_LIQ_UNHYDRATED_DEFER", 77L, now - 1_000L)
            val window = HydrationReeval8343.retryWindow8521(d.lanes, 360.0)
            val q = HydrationReeval8343.queue8343(d, 10_000.0, 1_440.0, window, now, "CHAIN_READ")
            if (lane == "PROJECT_SNIPER") assertNull("expired launch-only scope", q)
            else {
                assertNotNull("$lane must grade its newly hydrated token", q)
                assertEquals(setOf(lane), q!!.lanes)
                assertEquals(77L, q.version)
                assertFalse("a data retry is not native approval", q.anyEligible)
            }
        }
    }

    @Test fun aSniperExpiryDoesNotDiscardAnEstablishedLaneWaitingOnTheSameMint() {
        val d = HydrationReeval8343.Deferred8343("shared", setOf("SNIPER", "BLUE_CHIP"), "MCAP_UNHYDRATED_DEFER", 81L, now)
        val q = HydrationReeval8343.queue8343(d, 15_000.0, 2_000.0,
            HydrationReeval8343.retryWindow8521(d.lanes, 360.0), now + 1L)
        assertNotNull(q)
        assertEquals(d.lanes, q!!.lanes)
        assertEquals(360.0, HydrationReeval8343.retryWindow8521(setOf("SNIPER"), 360.0), 0.0)
    }

    @Test fun retryStillNeedsObservedLiquidityAndAnUnexpiredDataRequest() {
        val d = HydrationReeval8343.Deferred8343("old", setOf("QUALITY"), "LIQ_UNHYDRATED_DEFER", 91L, now)
        val window = HydrationReeval8343.retryWindow8521(d.lanes, 360.0)
        assertNull(HydrationReeval8343.queue8343(d, 0.0, 2_000.0, window, now))
        assertNull(HydrationReeval8343.queue8343(d, Double.NaN, 2_000.0, window, now))
        assertNull(HydrationReeval8343.queue8343(d, 1_000.0, 2_000.0, window,
            now + HydrationReeval8343.DEFER_MEMORY_MS_8343 + 1L))
    }
}
