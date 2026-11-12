package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8720 — the three structures the 8712 dataset paid on and the setup menu did not read. */
class Aate8720DataSetupsTest {
    private val added = listOf("FRESH_DEEP_POOL_8720", "DISTRIBUTED_HOLDERS_8720", "EARLY_CURVE_AT_HIGH_8720")

    @Test fun theMemeLanesCarryThemAndEveryEarlierBinStays() {
        for (lane in listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED")) {
            val ids = LanePlaybook7907.menuIds(lane)
            assertTrue(lane, ids.containsAll(added))
            val bins = (ids + LanePlaybook7907.NO_TRIGGER).map { LanePlaybook7907.setupIndexFor8076(lane, it) }
            assertEquals("$lane: one bin per setup", bins.size, bins.distinct().size)
            assertTrue("appended after the earlier setups",
                LanePlaybook7907.setupIndexFor8076(lane, added.first()) > LanePlaybook7907.setupIndexFor8076(lane, "MARKET_SECOND_WAVE_RECLAIM"))
        }
        assertFalse("DIP_HUNTER keeps its own menu", LanePlaybook7907.menuIds("DIP_HUNTER").contains("FRESH_DEEP_POOL_8720"))
        val pb = File("src/main/kotlin/com/lifecyclebot/engine/cortex/LanePlaybook7907.kt").readText()
        assertTrue(pb.contains("Setup(\"FRESH_DEEP_POOL_8720\", STRUCT) { f -> ok(f.age, 0.0, 5.0) && ge(f.liq, 10_000.0) && f.liq < 50_000.0 }"))
        assertTrue(pb.contains("Setup(\"EARLY_CURVE_AT_HIGH_8720\", STRUCT) { f -> f.curve7978.isFinite() && f.curve7978 < 0.02 && ge(f.pxPeak, 0.98) }"))
    }
}
