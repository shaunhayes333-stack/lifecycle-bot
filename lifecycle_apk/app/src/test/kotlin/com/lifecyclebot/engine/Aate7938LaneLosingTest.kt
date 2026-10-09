package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7938 — a lane proven losing on its own labels does not trade live on a borrowed cohort. */
class Aate7938LaneLosingTest {
    @Test fun moonshotLikeLaneIsProvenLosing() {
        assertTrue(LiveEdgeGate7877.laneProvenLosing7938(CellStat("LANE|MOONSHOT", 251, -8.2, 0.16, 0.04, 1.9, 38)))
    }

    @Test fun thinPositiveOrLatePayingLanesAreNot() {
        assertFalse(LiveEdgeGate7877.laneProvenLosing7938(null))
        assertFalse(LiveEdgeGate7877.laneProvenLosing7938(CellStat("LANE|X", 60, -9.0, 0.1, 0.0, 1.0, 0)))
        assertFalse(LiveEdgeGate7877.laneProvenLosing7938(CellStat("LANE|EXPRESS", 85, 12.7, 0.26, 0.14, 3.0, 10)))
        assertFalse(LiveEdgeGate7877.laneProvenLosing7938(CellStat("LANE|CRYPTO_ALT", 167, -3.7, 0.22, 0.04, 2.0, 0)))
        assertFalse(LiveEdgeGate7877.laneProvenLosing7938(CellStat("LANE|R", 200, -6.0, 0.1, 0.1, 1.0, 0, n240 = 40, meanNet240Pct = 3.0)))
    }
}
