package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7907 — every lane runs a playbook of setups, educated from the field manual. */
class Aate7907LanePlaybookTest {
    private fun f(
        age: Double = Double.NaN, runup: Double = Double.NaN, pxPeak: Double = Double.NaN, dd: Double = Double.NaN,
        bp: Double = Double.NaN, top: Double = Double.NaN, chg5m: Double = Double.NaN, chg1h: Double = Double.NaN,
        plan: String = "", phase: String = "",
    ) = F(age, runup, pxPeak, dd, bp, Double.NaN, Double.NaN, top, chg5m, chg1h, Double.NaN, Double.NaN, plan, phase)

    @Test fun everyLaneHasSeveralIdeas() {
        for (lane in listOf("QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
            "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC", "CORE")) {
            assertTrue(lane, LanePlaybook7907.menuIds(lane).size >= 3)
        }
    }

    @Test fun setupsFireOnTheirConditions() {
        val launch = f(age = 20.0, runup = 40.0, bp = 60.0, top = 12.0, phase = "PRE_IGNITION")
        assertTrue(LanePlaybook7907.matches("SHITCOIN", launch)!!.any { it.id == "LAUNCH_CONTINUATION" })
        // A dip hunter never buys a falling knife: no reclaim, no setup.
        val knife = f(dd = 45.0, chg5m = -4.0, bp = 60.0)
        assertTrue(LanePlaybook7907.matches("DIP_HUNTER", knife)!!.isEmpty())
        val reclaim = f(dd = 45.0, chg5m = 2.0, bp = 60.0)
        assertTrue(LanePlaybook7907.matches("DIP_HUNTER", reclaim)!!.any { it.id == "CAPITULATION_HIGHER_LOW" })
        // A bar-confirmed plan setup is on the menu of every structure lane.
        assertTrue(LanePlaybook7907.matches("QUALITY", f(plan = "PULLBACK_RECLAIM"))!!.any { it.id == "PLAN_PULLBACK_RECLAIM" })
        assertEquals(null, LanePlaybook7907.matches("NOT_A_LANE", launch))
    }

    @Test fun learningCanOverturnTheStartingRules() {
        val losing = CortexLedger7885.Stat().also { s -> repeat(40) { s.add(if (it % 2 == 0) -8.0 else -4.0, false) } }
        assertTrue(LanePlaybook7907.provenLosing(losing, runnerLane = false))
        val tail = CortexLedger7885.Stat().also { s -> repeat(40) { s.add(if (it % 2 == 0) -8.0 else -4.0, it < 5) } }
        assertFalse(LanePlaybook7907.provenLosing(tail, runnerLane = true))
        val noTriggerPays = CortexLedger7885.Stat().also { s -> repeat(50) { s.add(if (it % 2 == 0) 6.0 else 2.0, false) } }
        assertTrue(LanePlaybook7907.noTriggerProvenPositive(noTriggerPays))
        assertFalse(LanePlaybook7907.noTriggerProvenPositive(null))
    }
}
