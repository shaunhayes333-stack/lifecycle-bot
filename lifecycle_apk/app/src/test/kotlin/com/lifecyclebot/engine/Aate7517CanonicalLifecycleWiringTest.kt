package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7517CanonicalLifecycleWiringTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun bootstrap_projects_restored_canonical_book() {
        val s = src()
        val region = s.substringAfter("EmergentGuardrails.rebuildFromCanonical6475(repairedPaperPositions6490)")
            .substringBefore("IndependentReconcilerScheduler6431.start")
        assertTrue(region.contains("CanonicalLifecycleAuthority6470.audit()"))
        assertTrue(region.contains("CANONICAL_LIFECYCLE_BOOTSTRAP_SYNC_7517"))
    }

    @Test fun periodic_integrity_pass_rechecks_lifecycle_projection() {
        val s = src()
        val region = s.substringAfter("if (loopCount % 12 == 0 && loopCount > 0)")
            .substringBefore("// V5.0.6450 §P0 — ProtectiveExitScheduler6450")
        assertTrue(region.contains("CanonicalLifecycleAuthority6470.audit()"))
        assertTrue(region.contains("CANONICAL_LIFECYCLE_PERIODIC_SYNC_7517"))
    }
}
