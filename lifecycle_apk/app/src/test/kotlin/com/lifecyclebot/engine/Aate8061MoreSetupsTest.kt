package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8061 — every lane reads more setups: the owner's chart shapes plus the library setups its menu skipped. */
class Aate8061MoreSetupsTest {
    @Test fun menusGrewWithoutDuplicates() {
        val moon = LanePlaybook7907.menuIds("MOONSHOT")
        assertTrue(moon.contains("ACCELERATING_TAPE"))
        assertTrue(moon.contains("BUYERS_DOMINATING"))
        assertEquals(moon.size, moon.toSet().size)
        val shit = LanePlaybook7907.menuIds("SHITCOIN")
        assertTrue(shit.containsAll(listOf("BREAKOUT_RUNNER", "RS_LEADER", "IGNITION_5M", "NEW_HIGH_PUSH", "FIRST_PULLBACK_AFTER_RUN")))
        assertEquals(shit.size, shit.toSet().size)
        // Existing bins keep their place: the base menu is still the prefix.
        assertEquals("PLAN_BASE_BREAKOUT", moon[0])
        assertEquals("BREAKOUT_RUNNER", moon[2])
    }

    @Test fun shapesFireOnPriceFacts() {
        val f = LanePlaybook7907.F(age = 30.0, runup = 15.0, pxPeak = 0.99, dd = 1.0, bp = 66.0, liq = 20_000.0, mcap = 80_000.0,
            top = 10.0, chg5m = 4.0, chg1h = 12.0, holderGrowth = 0.0, volAccel = 50.0, planSetup = "", launchPhase = "")
        val fired = LanePlaybook7907.matches("SHITCOIN", f)!!.map { it.id }
        assertTrue(fired.contains("BUYERS_DOMINATING"))
        assertTrue(fired.contains("BASE_BREAKOUT_FLOW"))
    }
}
