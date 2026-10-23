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

/** V5.0.8193 — setup watches (8191) and sniper launch watches (8190) share the 8192 budget and per-coin cap. */
// superseded by V5.0.8354 — these budget paths apply only while BotConfig.pumpPortalTradeStreamEnabled is ON (default OFF:
// no subscribeTokenTrade is sent at all; the curve tape comes from HeliusCurveTape8354). The pins hold for the ON path.
class Aate8193PumpStreamBudgetSharedTest {
    @Test fun everyMeteredSubscriptionPathChecksTheBudget() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()
        val setup = s.substringAfter("fun watchForSetup8191(").substringBefore("setupMints8191[mint] = nowMs")
        // superseded by V5.0.8317: a setup watch is exempt from the per-coin frame cap (the day's budget still binds).
        assertTrue(setup.contains("if (!meteredSubAllowed8193(mint, setup8317 = true)) return false"))
        val fresh = s.substringAfter("private fun registerFreshLifecycle7420(").substringBefore("lifecycleMints7420[mint] = now")
        assertTrue(fresh.contains("(framesByMint8192[mint]?.get() ?: 0L) >= LAUNCH_FRAME_CAP_8192"))
        assertTrue(fresh.contains("launchSubAllowed8192(framesToday8192.get())"))
        assertTrue(s.contains("framesToday8192.set(0L); framesByMint8192.clear()"))
        assertTrue(s.contains("fun watchLaunch8190(") && s.substringAfter("fun watchLaunch8190(").take(300).contains("registerFreshLifecycle7420(mint)"))
    }
}
