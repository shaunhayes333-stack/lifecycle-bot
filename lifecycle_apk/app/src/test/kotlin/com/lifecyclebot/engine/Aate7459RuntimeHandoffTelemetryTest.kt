package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7459RuntimeHandoffTelemetryTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun handoff_counters_consume_single_deduped_specialist_stage_authority() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val fn = s.substringAfter("fun recordDeskStage(").substringBefore("/**\n     * V5.0.6625 — fan-out helper")
        assertTrue(fn.contains("RuntimeTune6833.recordSized6833(l)"))
        assertTrue(fn.contains("RuntimeTune6833.recordTicket6833(l)"))
        assertTrue(fn.contains("RuntimeTune6833.recordExec6833(l)"))
        assertTrue(fn.indexOf("deskStageOnce6599.add") < fn.indexOf("RuntimeTune6833.recordSized6833(l)"))
    }

    @Test fun shitcoin_choke_evaluation_uses_real_stage_transitions() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val fn = s.substringAfter("fun recordDeskStage(").substringBefore("/**\n     * V5.0.6625 — fan-out helper")
        assertTrue(fn.contains("l == \"SHITCOIN\""))
        assertTrue(fn.contains("st in setOf(\"SIZED_EXECUTABLE\", \"TICKET\", \"EXEC\")"))
        assertTrue(fn.contains("RuntimeTune6833.evaluateHandoffChoke6833()"))
    }

    @Test fun runtime_tune_remains_advisory_only_for_handoff_diagnostics() {
        val s = src("engine/truth/RuntimeTune6833.kt")
        val fn = s.substringAfter("fun evaluateHandoffChoke6833()").substringBefore("data class HandoffSnapshot")
        assertTrue(fn.contains("SHITCOIN_HANDOFF_STALLED_6833"))
        assertFalse(fn.contains("return false"))
        assertFalse(fn.contains("block"))
    }
}
