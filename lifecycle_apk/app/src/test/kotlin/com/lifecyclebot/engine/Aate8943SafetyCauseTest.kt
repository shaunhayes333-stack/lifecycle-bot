package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8943 — an evidence coin's safety refusal names its hard finding in the evidence funnel (safety8943{...}). */
class Aate8943SafetyCauseTest {
    @Test fun theFindingIsReadFromTheTraceWithItsNumbersFolded() {
        assertEquals("HARD_BLOCK:LP only N% locked — EXTREME RUG R",
            DecisionEpisode8535.safetyCause8943(listOf("lane=MOONSHOT", "safety=HARD_BLOCK:LP only 0% locked — EXTREME RUG RISK, devs can pull")))
        assertEquals(DecisionEpisode8535.safetyCause8943(listOf("safety=HARD_BLOCK:LP only 12% locked — EXTREME RUG RISK")),
            DecisionEpisode8535.safetyCause8943(listOf("safety=HARD_BLOCK:LP only 3% locked — EXTREME RUG RISK")))
        assertNull(DecisionEpisode8535.safetyCause8943(listOf("lane=MOONSHOT", "edge=-3")))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/DecisionEpisode8535.kt").readText()
        assertTrue(src.contains("if (reason == \"SAFETY_REFUSED_8136\" && e.evidence.isNotBlank() && e.reached8876.add(\"SAFETY_CAUSE_8943\"))"))
        assertTrue(src.contains("safety8943{\$safety}"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 131. What refused the evidence coins (8943)"))
    }
}
