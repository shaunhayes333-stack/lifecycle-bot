package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7410FdgCacheAndHeldEscalationTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun held_stale_mark_escalates_outside_discovery() {
        val s = src("engine/BotService.kt")
        val held = s.indexOf("HeldPositionSupervisor7246.solanaHeldPositions()")
        val repair = s.indexOf("HELD_STALE_MARK_ESCALATION_7410", held)
        assertTrue(held > 0 && repair > held)
        assertTrue(s.contains("requestExecutableQuote7301"))
        assertTrue(s.contains("HELD_STALE_MARK_ESCALATED_7410"))
    }
}
