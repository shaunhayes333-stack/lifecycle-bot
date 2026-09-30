package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7511BackgroundZombieDiagnosticTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

    @Test fun zombie_requires_both_scan_and_fdg_stale() {
        val s = src()
        val region = s.substringAfter("V5.0.7511 — PumpPortal/probation/hot-warmup intake")
            .substringBefore("sb.append(com.lifecyclebot.engine.BotService.backgroundLivenessSnapshot6544())")
        assertTrue(region.contains("intakeAge < 60_000L && scanAge > 600_000L && fdgAge > 600_000L"))
        assertFalse(region.contains("scanAge > 600_000L || fdgAge > 600_000L"))
    }

    @Test fun diagnostic_label_is_preserved() {
        assertTrue(src().contains("BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579"))
    }
}
