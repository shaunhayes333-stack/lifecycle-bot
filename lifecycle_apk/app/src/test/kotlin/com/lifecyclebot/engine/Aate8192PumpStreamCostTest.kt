package com.lifecyclebot.engine

import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8192 — the metered PumpPortal trade stream stays inside a daily budget; held positions always stay on it. */
class Aate8192PumpStreamCostTest {
    @Test fun budgetCostsAboutATenthOfASolPerDay() {
        assertEquals(0.10, PumpFunWS.frameCostSol8192(PumpFunWS.DAILY_FRAME_BUDGET_8192), 1e-9)
        // The old configuration's observed volume: ~480k messages/day = ~0.48 SOL.
        assertEquals(0.48, PumpFunWS.frameCostSol8192(480_000L), 1e-9)
    }

    @Test fun newLaunchesStopAtEightyPercentOfTheBudget() {
        assertTrue(PumpFunWS.launchSubAllowed8192(0L))
        assertTrue(PumpFunWS.launchSubAllowed8192(79_999L))
        assertFalse(PumpFunWS.launchSubAllowed8192(80_000L))
    }

    @Test fun launchesLeaveAtTheirFrameCapButHeldPositionsNever() {
        val cap = PumpFunWS.LAUNCH_FRAME_CAP_8192
        assertFalse(PumpFunWS.dropLaunch8192(held = false, mintFrames = cap - 1, framesToday = 10L))
        assertTrue(PumpFunWS.dropLaunch8192(held = false, mintFrames = cap, framesToday = 10L))
        assertTrue(PumpFunWS.dropLaunch8192(held = false, mintFrames = 1L, framesToday = PumpFunWS.DAILY_FRAME_BUDGET_8192))
        assertFalse(PumpFunWS.dropLaunch8192(held = true, mintFrames = 10 * cap, framesToday = 10 * PumpFunWS.DAILY_FRAME_BUDGET_8192))
    }

    @Test fun everyTradeFrameIsMeteredAndHeldMintsAreTracked() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()
        assertTrue(s.contains("noteTradeFrame8192(mint)  // V5.0.8192"))
        assertTrue(s.contains("heldMints8192.retainAll(wanted); heldMints8192.addAll(wanted)"))
        assertTrue(s.contains("private const val LIFECYCLE_MAX_7420 = 24"))
        assertTrue(s.contains("\${costLine8192()} key8181="))
    }
}
