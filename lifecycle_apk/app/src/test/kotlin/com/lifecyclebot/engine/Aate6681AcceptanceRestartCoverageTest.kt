package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.6681 — Stop -> Start must still produce the mandatory 120s verdict. */
class Aate6681AcceptanceRestartCoverageTest {
    @Test
    fun acceptance_window_has_process_lifetime_restart_safe_closer() {
        val acceptance = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt"
        ).readText()
        val service = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()

        assertTrue(acceptance.contains("windowEpoch6681"))
        assertTrue(acceptance.contains("autonomousCloser6681"))
        assertTrue(acceptance.contains("EXECUTION_SPINE_AUTONOMOUS_CLOSE_FIRED_6681"))
        assertTrue(acceptance.contains("BackgroundTradingAuthority6469.isRuntimeActive()"))
        assertTrue(acceptance.contains("closeCompletedWindow()"))

        // Service shutdown is allowed to tear down its own executor. Acceptance
        // finality must therefore not depend exclusively on that executor.
        assertTrue(service.contains("acceptanceWindowExecutor6668.shutdownNow()"))
        assertFalse(
            "acceptance window must not share the stop-owned BotService executor",
            acceptance.contains("acceptanceWindowExecutor6668")
        )
    }
}
