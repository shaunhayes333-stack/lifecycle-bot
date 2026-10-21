package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveConfidence8296
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8296 — growth mandate (2x-5x daily from real live edge): AutoCompoundEngine's LIVE size multiplier is a pool fed
 * only by verified live closes (wins add 45%, losses drain in full), and a setup paying on its own realised live closes
 * compounds even when its forward labels are thin.
 */
class Aate8296LiveCompoundGrowthTest {

    @Test fun liveWinsGrowThePoolAndLossesDrainItInFull() {
        var pool = 0.0
        pool = LiveConfidence8296.nextPool8296(pool, 0.10)
        assertEquals(0.045, pool, 1e-12)
        pool = LiveConfidence8296.nextPool8296(pool, 0.20)
        assertEquals(0.135, pool, 1e-12)
        assertTrue(LiveConfidence8296.compoundMultiplier8296(pool) > 1.3)
        pool = LiveConfidence8296.nextPool8296(pool, -0.05)
        assertEquals(0.085, pool, 1e-12)
        pool = LiveConfidence8296.nextPool8296(pool, -1.0)
        assertEquals(0.0, pool, 0.0)                      // never negative
        assertEquals(1.0, LiveConfidence8296.compoundMultiplier8296(pool), 0.0)
        assertEquals(0.3, LiveConfidence8296.nextPool8296(0.3, Double.NaN), 0.0)
    }

    @Test fun theMultiplierFollowsAutoCompoundsCurveAndCap() {
        assertEquals(1.45, LiveConfidence8296.compoundMultiplier8296(0.15), 1e-12)
        assertEquals(LiveConfidence8296.LIVE_COMPOUND_MAX_MULT_8296, LiveConfidence8296.compoundMultiplier8296(10.0), 0.0)
        assertEquals(1.0, LiveConfidence8296.compoundMultiplier8296(Double.NaN), 0.0)
    }

    @Test fun realisedLiveWinsAloneEarnACompoundingShare() {
        assertEquals(0.05, LiveConfidence8296.realisedOnlyFrac8296(0.10, 3.0, 12.0), 1e-12)
        assertEquals(0.0, LiveConfidence8296.realisedOnlyFrac8296(0.10, 2.0, 12.0), 0.0)    // too few own closes
        assertEquals(0.0, LiveConfidence8296.realisedOnlyFrac8296(0.10, 5.0, -3.0), 0.0)    // own closes lose
        assertEquals(0.0, LiveConfidence8296.realisedOnlyFrac8296(0.0, 5.0, 12.0), 0.0)     // lane not proven
    }

    @Test fun autoCompoundReadsTheLivePoolInLive() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/AutoCompoundEngine.kt").readText()
        assertTrue(src.contains("if (liveRuntime8296()) com.lifecyclebot.engine.truth.LiveConfidence8296.liveCompoundMultiplier8296() else state.currentSizeMultiplier"))
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(p.contains("LiveConfidence8296.realisedOnlyFrac8296("))
        val c = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveConfidence8296.kt").readText()
        assertTrue(c.contains("livePool8296 = nextPool8296(livePool8296, netSol)"))
    }
}
