package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.*
import org.junit.Test

/** V5.0.8389 — the 8383 envelope never cuts below a specialist's own mandate (8387: MCAP_LT_10K refused 4488 launches). */
class Aate8389EarlyBandTest {
    private val early = listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "MANIPULATED", "PROJECT_SNIPER", "CORE")

    @Test fun earlyBandLanesKeepTheirLaunchBand() {
        for (lane in early) {
            assertTrue(lane, LaneCheatSheet8323.mcapMin8323(lane) < 10_000.0)
            assertFalse(lane, LanePlaybook7907.insideMandate8389(lane, "MCAP_LT_10K"))
            for (s in LanePlaybook7907.EARLY_FAMILY_8389) assertFalse("$lane/$s", LanePlaybook7907.retired8383(lane, s))
        }
    }

    @Test fun lanesWhoseMandateStartsHigherKeepTheirFloors() {
        assertTrue(LanePlaybook7907.insideMandate8389("TREASURY", "MCAP_LT_10K"))
        assertTrue(LanePlaybook7907.insideMandate8389("CASHGEN", "MCAP_LT_10K"))
        // bands that are not floors are never relaxed
        for (lane in early) {
            assertTrue(LanePlaybook7907.insideMandate8389(lane, "MCAP_LIQ_GT_25X"))
            assertTrue(LanePlaybook7907.insideMandate8389(lane, "BP_LT_45"))
        }
    }

    @Test fun liquidityFloorFollowsTheLanesOwnFloor() {
        for (lane in listOf("SHITCOIN", "EXPRESS", "MANIPULATED"))
            assertEquals(lane, LaneCheatSheet8323.liqMin8389(lane) >= 3_000.0, LanePlaybook7907.insideMandate8389(lane, "LIQ_LT_3K"))
    }
}
