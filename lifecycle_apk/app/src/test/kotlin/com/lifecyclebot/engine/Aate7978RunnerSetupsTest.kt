package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7978RunnerSetupsTest {

    private fun f(
        age: Double = 8.0, mcap: Double = 8_000.0, chg5m: Double = 20.0, bp: Double = 70.0,
        bpm: Double = Double.NaN, hv: Double = Double.NaN, turnover: Double = Double.NaN, curve: Double = Double.NaN,
        live: Boolean = false, beta: Boolean = false, colour: Boolean = false,
    ) = LanePlaybook7907.F(
        age = age, runup = 30.0, pxPeak = 0.95, dd = 5.0, bp = bp, liq = 5_000.0, mcap = mcap, top = 10.0,
        chg5m = chg5m, chg1h = 20.0, holderGrowth = Double.NaN, volAccel = Double.NaN, planSetup = "", launchPhase = "",
        tapeBuyersPerMin = bpm, tapeBuyShare = 70.0, tapeLargest = 10.0,
        holderVel7978 = hv, turnover7978 = turnover, curve7978 = curve, live7978 = live, beta7978 = beta, colourBuy7978 = colour,
    )

    private fun fires(lane: String, x: LanePlaybook7907.F, id: String) = LanePlaybook7907.matches(lane, x)!!.any { it.id == id }

    @Test fun runnerSetupsFireOnTheFactsRunnersCarry() {
        assertTrue(fires("SHITCOIN", f(bpm = 8.0), "FRESH_LAUNCH_MOMENTUM"))
        assertFalse(fires("SHITCOIN", f(bpm = 3.0), "FRESH_LAUNCH_MOMENTUM"))
        assertTrue(fires("MOONSHOT", f(), "MICRO_CAP_IGNITION"))
        assertFalse(fires("MOONSHOT", f(mcap = 50_000.0), "MICRO_CAP_IGNITION"))
        assertTrue(fires("SHITCOIN", f(hv = 7.0), "HOLDER_SURGE"))
        assertTrue(fires("SHITCOIN", f(turnover = 4.0), "TURNOVER_SPIKE"))
        assertTrue(fires("MOONSHOT", f(curve = 0.9), "GRADUATION_APPROACH"))
        assertFalse(fires("MOONSHOT", f(curve = 0.5), "GRADUATION_APPROACH"))
        assertTrue(fires("SHITCOIN", f(live = true), "LIVESTREAM_LAUNCH"))
        assertTrue(fires("SHITCOIN", f(beta = true), "COPYCAT_BETA"))
        assertTrue(fires("QUALITY", f(colour = true), "COLOUR_SEQUENCE"))
        val sp = f(); sp.specialistEdge7978 = true
        assertTrue(fires("BLUECHIP", sp, "SPECIALIST_EDGE"))
        assertTrue(LanePlaybook7907.menuIds("MOONSHOT").containsAll(listOf("SPECIALIST_EDGE", "FRESH_LAUNCH_MOMENTUM", "GRADUATION_APPROACH")))
        assertFalse(LanePlaybook7907.menuIds("BLUECHIP").contains("FRESH_LAUNCH_MOMENTUM"))
    }

    @Test fun noProgressStopIsShadowOnly() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(src.contains("NO_PROGRESS_TIME_STOP_SHADOW_7978"))
        assertFalse(src.contains("labelInc(\"NO_PROGRESS_TIME_STOP_7973\")"))
    }
}
