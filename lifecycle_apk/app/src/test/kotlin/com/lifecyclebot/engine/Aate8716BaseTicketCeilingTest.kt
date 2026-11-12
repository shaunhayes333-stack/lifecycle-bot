package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697
import com.lifecyclebot.engine.truth.LiveSizeTarget8094
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8716 — the fixed 18 % wallet-share ceiling clamped 8714's cost-clearing base ticket back down before the seal. */
class Aate8716BaseTicketCeilingTest {
    @Test fun theCeilingAdmitsTheBaseTicket() {
        val t = LiveSizeTarget8094
        assertEquals("the base ticket's share lifts the ceiling", 0.2864, t.ceilingWithBaseTicket8716(0.18, 0.2864), 0.0)
        assertEquals("a larger ceiling stands", 0.25, t.ceilingWithBaseTicket8716(0.25, 0.05), 0.0)
        assertEquals("never past the doctrine's own maximum", LiveConcentrationDoctrine7697.MAX_SHARE_7697, t.ceilingWithBaseTicket8716(0.18, 0.9), 0.0)
        assertEquals(0.18, t.ceilingWithBaseTicket8716(0.18, Double.NaN), 0.0)
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("if (config.paperMode) 0.0 else try { com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697.share(tradeable8079) }"))
    }
}
