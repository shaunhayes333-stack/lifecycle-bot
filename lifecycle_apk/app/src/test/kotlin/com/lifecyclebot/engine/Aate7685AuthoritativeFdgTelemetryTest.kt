package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7685AuthoritativeFdgTelemetryTest {
    @Test fun finalSealedFdgDecisionHasDedicatedCounters() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(s.contains("fdgFinalAllow7685"))
        assertTrue(s.contains("fdgFinalBlock7685"))
        assertTrue(s.contains("if (phaseTag == \"FDG\")"))
        assertTrue(s.contains("val allowed7685 = !verdict.equals(\"BLOCK\", ignoreCase = true)"))
    }

    @Test fun reportPrefersFinalSealedPopulationAndKeepsLegacyMirrorDiagnosticOnly() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(s.contains("[FINAL_SEALED_7685]"))
        assertTrue(s.contains("legacyGateMirror"))
        assertTrue(s.contains("not used for choke diagnosis"))
        assertTrue(s.contains("FDG_GATE_MIRROR_DIVERGED_FROM_FINAL_7685"))
    }

    @Test fun executionAuthorityAlreadyEmitsOneFinalFdgVerdict() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(g.contains("val verdictLabel = if (executableFdg) finalVerdict else \"BLOCK\""))
        assertTrue(g.contains("ForensicLogger.decision(ForensicLogger.PHASE.FDG, symbol, verdictLabel"))
    }
}
