package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8036 — USDP: a laddered climb with no pullbacks on depth far too thin for its cap is refused. */
class Aate8036FakeChartTest {
    @Test fun aStraightLadderIsManufactured() {
        val ladder = (0 until 25).map { 1.0 + it * (0.4 / 24) }   // +40%, never a red bar
        assertTrue(FlatChart8027.manufacturedClimb8036(ladder))
        assertTrue(ladder.size >= FlatChart8027.CLIMB_MIN_BARS_8036)
        assertTrue((ladder.last() / ladder.first() - 1.0) * 100.0 >= FlatChart8027.CLIMB_MIN_RISE_PCT_8036)
    }

    @Test fun anOrganicChartBreathes() {
        // +40% but every third bar pulls back 3% -> a third of bars red, well over the share cap.
        val organic = (0 until 25).map { i -> (1.0 + i * (0.4 / 24)) * (if (i % 3 == 2) 0.97 else 1.0) }
        assertTrue(1.0 / 3.0 > FlatChart8027.CLIMB_MAX_DOWN_SHARE_8036)
        assertFalse(FlatChart8027.manufacturedClimb8036(organic))
        // Too few bars or too small a rise is never judged.
        assertFalse(FlatChart8027.manufacturedClimb8036((0 until 10).map { 1.0 + it * 0.05 }))
        assertFalse(FlatChart8027.manufacturedClimb8036((0 until 25).map { 1.0 + it * 0.001 }))
    }

    @Test fun aCapNobodyCanSellInto() {
        assertTrue(FlatChart8027.thinDepthForCap8036(204_000_000.0, 500_000.0))
        assertFalse(FlatChart8027.thinDepthForCap8036(5_000_000.0, 500_000.0))
        assertFalse(FlatChart8027.thinDepthForCap8036(204_000_000.0, 0.0))
        assertTrue(204_000_000.0 / 500_000.0 > FlatChart8027.MAX_CAP_TO_DEPTH_8036)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/FlatChart8027.kt").readText()
        assertTrue(src.contains("MANUFACTURED_CLIMB_8036") && src.contains("THIN_DEPTH_FOR_CAP_8036"))
    }
}
