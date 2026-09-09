package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6704 — pre-FDG sizing may never masquerade as executable authority. */
class Aate6704CausalSizingAuthorityTest {
    @Test
    fun `canonical sizing only propagates causal executable id from sealed intent`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt").readText()
        assertTrue(src.contains("activeExecutionIntent6519"))
        assertTrue(src.contains("SPECIALIST_PRE_FDG_SIZE_ADVISORY_6704"))
        assertTrue(src.contains("causalEventId = resolvedCausalEventId6674"))
        assertFalse(src.contains(":$resolvedCandidateVersion6620:SIZE\""))
    }
}
