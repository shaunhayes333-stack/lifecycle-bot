package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7633SuperIntelligencePlannerTest {
    @Test fun `strong coherent positive edge prefers funded action`() {
        val p = SuperIntelligencePlanner7633.plan(
            pWin = 0.72, expectancyPct = 28.0, confidence = 0.85,
            disagreement = 0.10, policyPWin = 0.70, hardSafetyBlocked = false,
        )
        assertTrue(p.chosen != SuperIntelligencePlanner7633.Action.WAIT)
        assertTrue(p.uncertainty < 0.30)
    }

    @Test fun `uncertain negative edge prefers waiting`() {
        val p = SuperIntelligencePlanner7633.plan(
            pWin = 0.42, expectancyPct = -12.0, confidence = 0.25,
            disagreement = 0.80, policyPWin = 0.45, hardSafetyBlocked = false,
        )
        assertEquals(SuperIntelligencePlanner7633.Action.WAIT, p.chosen)
    }

    @Test fun `hard safety fact always maps planner to wait`() {
        val p = SuperIntelligencePlanner7633.plan(
            pWin = 0.90, expectancyPct = 100.0, confidence = 1.0,
            disagreement = 0.0, policyPWin = 0.90, hardSafetyBlocked = true,
        )
        assertEquals(SuperIntelligencePlanner7633.Action.WAIT, p.chosen)
    }

    @Test fun `oracle exposes planner without making it execution authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(src.contains("SuperIntelligencePlanner7633.plan("))
        assertTrue(src.contains("SUPER_INTELLIGENCE_PLAN_7633"))
        assertTrue(src.contains("contributions += superPlan7633.contributionTag()"))
    }
}
