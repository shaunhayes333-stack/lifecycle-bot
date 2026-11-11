package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8694 — a protective sell's Sender tip is scaled to the position (8689: 252,480 lamports avg, 0.7 % of a 0.036 SOL stop). */
class Aate8694ProtectiveTipTest {
    @Test fun aStopOnASmallPositionNoLongerPaysTheFlatFloor() {
        val position = 36_000_000L                                   // 0.036 SOL
        val tip = CostTrim8151.protectiveTipLamports8694(252_480L, position, fullTip = false, previousSellUnlanded = false)
        assertTrue("0.3 % of the position, not 0.7 %: $tip", tip in 107_999L..108_000L)
        assertEquals("never under the ordinary tip", CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151,
            CostTrim8151.protectiveTipLamports8694(252_480L, 2_000_000L, false, false))
        assertEquals("a large position keeps the legacy tip", 200_000L,
            CostTrim8151.protectiveTipLamports8694(200_000L, 1_000_000_000L, false, false))
    }

    @Test fun rugsEmergenciesAndRetriesKeepTheFullTip() {
        assertEquals(400_000L, CostTrim8151.protectiveTipLamports8694(400_000L, 36_000_000L, fullTip = true, previousSellUnlanded = false))
        assertEquals(400_000L, CostTrim8151.protectiveTipLamports8694(400_000L, 36_000_000L, fullTip = false, previousSellUnlanded = true))
        assertEquals("unknown size changes nothing", 400_000L, CostTrim8151.protectiveTipLamports8694(400_000L, 0L, false, false))
    }

    @Test fun everyLiveSellPassesItsPositionSize() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(e.contains("CostTrim8151.protectiveTipLamports8694(legacy,"))
        assertTrue(e.contains("urgent = isDrainExit, positionSol8694 = ts.position.costSol)"))
        assertTrue(e.contains("urgent = isDrainExit || spike7944, positionSol8694 = ts.position.costSol)"))
    }
}
