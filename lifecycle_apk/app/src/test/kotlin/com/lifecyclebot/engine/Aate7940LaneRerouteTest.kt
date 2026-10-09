package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.7940 — tokens go to the lane whose own record pays at that stage. */
class Aate7940LaneRerouteTest {
    private val moonshot = CellStat("LANE|MOONSHOT", 251, -8.2, 0.16, 0.04, 1.9, 38)
    private val express = CellStat("LANE|EXPRESS", 85, 12.7, 0.26, 0.14, 4.0, 10)
    private val thin = CellStat("LANE|PROJECT_SNIPER", 10, 30.0, 0.5, 0.2, 5.0, 0)

    @Test fun losingOwnerHandsToAMeasuredPayingLane() {
        assertEquals("EXPRESS", TokenMetricStageRouter.rerouteTarget7940("MOONSHOT", moonshot,
            mapOf("MOONSHOT" to moonshot, "EXPRESS" to express, "PROJECT_SNIPER" to thin, "SHITCOIN" to null)))
    }

    @Test fun unprovenOwnerOrNoPayingAlternativeKeepsTheOwner() {
        assertEquals("EXPRESS", TokenMetricStageRouter.rerouteTarget7940("EXPRESS", express, mapOf("MOONSHOT" to moonshot)))
        assertEquals("MOONSHOT", TokenMetricStageRouter.rerouteTarget7940("MOONSHOT", moonshot, mapOf("PROJECT_SNIPER" to thin)))
    }
}
