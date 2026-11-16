package com.lifecyclebot.engine

import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8814 — a setup coin Helius cannot carry takes PumpPortal's keyed stream up to the setup mark (95 % of the day's
 * frames); launches still stop at 80 %, held coins keep everything above the setup mark.
 */
class Aate8814SetupOverflowTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aSetupHeliusRefusedUsesTheReserveButNotTheHeldTop() {
        val b = PumpFunWS.DAILY_FRAME_BUDGET_8192
        assertEquals(95L, PumpFunWS.SETUP_TOP_PCT_8814)
        // past the launch mark (80 %), Helius refused the coin: PumpPortal carries it
        assertFalse(PumpFunWS.launchSubAllowed8192(94_250L))
        assertTrue(PumpFunWS.setupOverflow8814(heliusUnavailable = true, keyed = true, framesToday = 94_250L, budget = b))
        // the top 5 % stays for held coins
        assertFalse(PumpFunWS.setupOverflow8814(heliusUnavailable = true, keyed = true, framesToday = 95_000L, budget = b))
        // Helius still able to carry it (queued / delivering): no paid frames spent
        assertFalse(PumpFunWS.setupOverflow8814(heliusUnavailable = false, keyed = true, framesToday = 81_000L, budget = b))
        // no verified key: nothing to overflow to
        assertFalse(PumpFunWS.setupOverflow8814(heliusUnavailable = true, keyed = false, framesToday = 81_000L, budget = b))
    }

    @Test fun watchForSetupFallsThroughToTheSubscribeWhenHeliusIsUnavailable() {
        val ws = src("network/PumpFunWS.kt")
        val setup = ws.substringAfter("fun watchForSetup8191(").substringBefore("fun lifecycleStreamCapable7420()")
        val gate = setup.indexOf("if (!meteredSubAllowed8193(mint, setup8317 = true)) {")
        val ask = setup.indexOf("val st = HeliusCurveTape8354.askState8386(mint, nowMs)", gate)
        val overflow = setup.indexOf("setupOverflow8814(st is HeliusCurveTape8354.TapeState8386.Unavailable, tradeStreamKeyed7284(), framesToday8192.get())")
        val subscribe = setup.indexOf("put(\"method\", \"subscribeTokenTrade\")")
        assertTrue(gate in 0 until ask && ask < overflow && overflow < subscribe)
        assertTrue(setup.contains("if (st is HeliusCurveTape8354.TapeState8386.Delivering) return true"))
        assertTrue(setup.contains("PUMP_SETUP_OVERFLOW_8814"))
        assertTrue(ws.contains("setup8814[overflowToPump="))
    }
}
