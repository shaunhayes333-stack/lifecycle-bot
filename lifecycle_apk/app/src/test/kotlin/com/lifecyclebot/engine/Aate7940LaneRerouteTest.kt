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

/** V5.0.7941 — a lane whose own labels prove its pool pays is admitted past the generic floor. */
class Aate7941LaneProvenTest {
    @Test fun shitcoinPoolIsProvenPositiveMoonshotIsNot() {
        // V5.0.7942 — lost marks count as -100%: SHITCOIN's 264 lost of 2,749 sink it; MOONSHOT 5.0.7941 holds.
        org.junit.Assert.assertFalse(com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenPositive7941(CellStat("LANE|SHITCOIN", 2485, 4.4, 0.14, 0.08, 0.6, 264)))
        org.junit.Assert.assertTrue(com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenPositive7941(CellStat("LANE|MOONSHOT", 1119, 9.9, 0.15, 0.07, 1.5, 38)))
        org.junit.Assert.assertFalse(com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenPositive7941(CellStat("LANE|MOONSHOT", 251, -8.2, 0.16, 0.04, 1.9, 38)))
        org.junit.Assert.assertFalse(com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenPositive7941(CellStat("LANE|EXPRESS", 85, 12.7, 0.26, 0.14, 4.0, 10)))
    }
}
