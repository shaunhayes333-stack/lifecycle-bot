package com.lifecyclebot.engine

import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8691 — PumpPortal's metered trade frames (0.01 SOL per 10k) were capped per UTC day by an in-memory counter, so
 * every restart reset the 0.10 SOL/day cap to zero (8689: 9,037 frames in ~14 min, ~0.9 SOL/day at that pace).
 */
class Aate8691FrameBudgetTest {
    @Test fun theDaysCountResumesAfterARestartAndResetsOnANewDay() {
        assertEquals(9037L, PumpFunWS.restoredFrames8691("20404,9037", 20404L))
        assertEquals("a new UTC day starts from zero", 0L, PumpFunWS.restoredFrames8691("20403,99000", 20404L))
        assertEquals(0L, PumpFunWS.restoredFrames8691(null, 20404L))
        assertEquals(0L, PumpFunWS.restoredFrames8691("garbage", 20404L))
        assertEquals(0L, PumpFunWS.restoredFrames8691("20404,-5", 20404L))
    }

    @Test fun theCountIsRestoredOnFirstUseAndSaved() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()
        assertTrue(s.contains("val restored = if (frameDay8192 == 0L) restoreFrames8691(day) else 0L"))
        assertTrue(s.contains("if (today % FRAME_PERSIST_EVERY_8691 == 0L) persistFrames8691(frameDay8192, today)"))
    }
}
